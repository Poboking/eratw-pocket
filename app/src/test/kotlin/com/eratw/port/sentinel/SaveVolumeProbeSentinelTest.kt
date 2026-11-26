package com.eratw.port.sentinel

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * 源码级哨兵：**存档/平台主源不许出现「Android 上被特改」的卷空间 API**（bug② 的防再犯闸，2026-11-19）。
 *
 * ## 为什么这类雷必须靠源码闸挡
 *
 * `sun.nio.fs.UnixFileSystemProvider.getFileStore(Path)` 在 AOSP libcore 里被整段改成恒抛：
 * `platform/libcore` → `ojluni/src/main/java/sun/nio/fs/UnixFileSystemProvider.java`，
 * 方法体 `:373-388`，`:375` 的 `BEGIN Android-changed: getFileStore(Path) always throws SecurityException.`
 * 与 `:387` 的 `throw new SecurityException("getFileStore");` 就是判据本身。
 * 后果：`java.nio.file.Files.getFileStore(..)` **桌面 JVM 恒成功、Android 恒抛** —— 编译期绿、
 * 桌面单测绿、真机 100% 崩。这类「桌面编译面有、Android 运行面没有」的 API 与
 * [com.eratw.port.core.AndroidApiCompatTest] 治的那一族同型，区别只是后者靠 android.jar 索引
 * 判「编译面缺失」，而本 API 在 android.jar 里**签名齐全**（只是方法体被换掉）⇒ 索引法抓不到，
 * 只能在源码面按名字扫。
 *
 * ## 禁什么、为什么
 *
 * - `getFileStore`：上面的恒抛点；`Files.getFileStore` / `FileSystems...getFileStore` 都中招。
 * - `FileStore`：这个类型**只能**经 `getFileStore` 拿到（`FileSystem.getFileStores()` 在 libcore
 *   同样不可用），出现即说明有人又去摸卷属性；`usableSpace` / `getTotalSpace` /
 *   `getUnallocatedSpace` 都在它身上。内部重载也没留后门：
 *   `ojluni/src/main/java/sun/nio/fs/LinuxFileSystemProvider.java:48-53` 把
 *   `getFileStore(UnixPath)` 也改成 `throw new SecurityException("getFileStore")`。
 * - `getTotalSpace` / `getUnallocatedSpace`：同上，只存在于 `FileStore` / 部分 `File` 重载的
 *   不可用面；且「总量」在存档预算里本来就不是正确口径（含系统保留块）。
 *
 * **不禁 `File.usableSpace`（`java.io.File`）**：libcore 的 `java/io/File.java:1865` `getUsableSpace()`
 * 只做 `fs.getSpace(this, SPACE_USABLE)`，不经过被禁的 `getFileStore`，Android 上可用 —— 这正是
 * 同仓既有正确写法的依据（见下）。
 *
 * ## 正确写法只有一个出处
 *
 * `platform/src/main/kotlin/com/eratw/port/platform/storage/GameAssetInstaller.kt:1974-1987` 的
 * `freeBytes(root: File)`：沿父链找**最近存在的祖先**再问 `usableSpace`（不存在的路径读成 0 会
 * 误判盘满）、`0` 视同探测失败、任何异常一律 fail-open 到 `Long.MAX_VALUE`（真盘满由带路径的
 * 写入异常现形）。KDoc `:1952-1973` 已写明为何不用 `android.os.StatFs`。存档侧要探测空间就复用
 * 这份口径，不要另起一套。
 *
 * ## 扫描口径
 *
 * 扫 `core-vm` 与 `platform` 两个模块的 `src/main`（`.kt` / `.java`）：测试代码不扫（那里出现这些
 * 名字往往正是用例在复刻 Android 形状），注释与字符串字面量先抹掉再判（KDoc 里举例不算违规）。
 * 命中即红并打印 `相对路径:行号 命中token`。
 *
 * **匹配必须是标识符词边界，不能是裸子串**：`ConfigFileStore`（`platform/.../config/` 下的配置
 * 落盘类，与卷空间 API 毫无关系）含子串 `FileStore`；`SettingsStore.kt`、`GameAssetInstaller.kt`
 * 也引用它。子串判据会让这道闸命中一堆无辜文件并**永远红**（那些文件改不了也不该改），
 * 闸一旦永远红就没人看了 —— 所以判据钉在 `[A-Za-z0-9_$]` 之外的词边界上，四个 token 一致。
 *
 * 扫不到源树或扫到的文件数异常少 ⇒ 直接 fail，不许静默跳过（同 [com.eratw.port.core.AndroidApiCompatTest]
 * 的「闸不许静默跳过」纪律）。量程自证：`core-vm/src/main` + `platform/src/main` 的 `.kt/.java`
 * 实测 397 个（2026-11-19 本机），阈值取 200。
 *
 * ## 双向实测（2026-11-19）
 *
 * - HEAD 态：`1 failed`，命中**恰好一行** `core-vm/.../save/NioSaveTransactionIo.kt:72 命中 getFileStore`
 *   —— 词边界改完后零 `ConfigFileStore` 误报（旧子串判据曾命中 12 行，含 `ConfigFileStore.kt:40/201/202`、
 *   `SettingsStore.kt:58/61/148`、`GameAssetInstaller.kt:4/1477/1534`）；
 * - 临时把该行换成 `GameAssetInstaller.freeBytes` 口径：`1 passed`、`failures="0"`；
 * - `git checkout --` 回退后再次 `1 failed`、同一行。
 */
class SaveVolumeProbeSentinelTest {

    /**
     * 被禁 token 与其来源。token 大小写敏感：Kotlin/Java 里这些名字不会以别的形态出现。
     *
     * [pattern] 用标识符词边界（前后都不是 `[A-Za-z0-9_$]`）：只有真的调 `Files.getFileStore(..)`、
     * 声明 `java.nio.file.FileStore` 这类才会命中；名字里**含**这个子串的类型（`ConfigFileStore`）
     * 不命中，否则本闸永远红。
     */
    private class Banned(val token: String, val why: String) {
        val pattern: Regex = Regex("(?<![A-Za-z0-9_\\$])" + Regex.escape(token) + "(?![A-Za-z0-9_\\$])")
    }

    private val banned: List<Banned> = listOf(
        Banned("getFileStore", "AOSP libcore UnixFileSystemProvider.java:387 恒抛 SecurityException"),
        Banned("FileStore", "FileStore 只能经 getFileStore 取得，Android 上不可达"),
        Banned("getTotalSpace", "卷总量口径，只在不可达的 FileStore/卷属性面上"),
        Banned("getUnallocatedSpace", "FileStore 专有，Android 上不可达"),
    )

    @Test
    fun `主源不许出现 Android 上被特改的卷空间 API`() {
        // 公开分仓后模块分居两仓：本仓（壳层）有 platform，同级引擎仓 Kotera 有 core-vm。
        // 逐模块按「各自所属的仓根」定位；引擎仓不在场（如未同级检出）时跳过本用例
        // —— 数据缺失不是回归，引擎侧主源的同类闸由引擎仓自己的 CI 跑。
        val modules = listOf("core-vm", "platform")
        var scanned = 0
        val hits = mutableListOf<String>()
        for (module in modules) {
            val moduleRepo = moduleRepoRoot(module)
            val root = moduleRepo.resolve(module).resolve("src/main")
            if (!Files.isDirectory(root)) {
                throw org.junit.AssumptionViolatedException(
                    "找不到 $module 的主源目录：$root —— " +
                        "公开分仓后 core-vm 在同级引擎仓（Kotera），不在场则跳过本哨兵",
                )
            }
            val files = Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".kt") || it.fileName.toString().endsWith(".java") }
                    .toList()
            }
            if (files.isEmpty()) fail("$module 主源下一个 .kt/.java 都没扫到：$root")
            scanned += files.size
            for (file in files) {
                val rel = moduleRepo.relativize(file).toString().replace('\\', '/')
                val lines = codeOnly(String(Files.readAllBytes(file), Charsets.UTF_8)).lines()
                lines.forEachIndexed { index, line ->
                    for (rule in banned) {
                        if (rule.pattern.containsMatchIn(line)) {
                            hits += "$rel:${index + 1} 命中 ${rule.token}（${rule.why}）"
                        }
                    }
                }
            }
        }
        // 两道自证：扫到的量要像整个主源（防路径算错后扫了个空目录还绿）。
        assertTrue("只扫到 $scanned 个主源文件，明显不是 core-vm + platform 全量，先查 repoRoot 解析", scanned > 200)
        assertTrue("主源出现 Android 上被特改的卷空间 API（桌面绿/真机崩，AOSP libcore " +
                "UnixFileSystemProvider.java:373-388）：\n  " + hits.joinToString("\n  ") +
                "\n改用 platform 的 GameAssetInstaller.freeBytes 口径（沿父链找最近存在祖先 + java.io.File.usableSpace " +
                "+ 探测失败 fail-open），不要把一次探测失败升级成拒绝存档。", hits.isEmpty())
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 某模块所属的仓根：platform 归本仓（壳层），core-vm 归同级引擎仓（Kotera）。 */
    private fun moduleRepoRoot(module: String): Path {
        val here = repoRoot()
        if (module != "core-vm") return here
        val sibling = here.resolveSibling("Kotera")
        return if (Files.isDirectory(sibling)) sibling else here
    }

    private fun repoRoot(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts")) && Files.isDirectory(dir.resolve("app"))) {
                return dir
            }
            dir = dir.parent
        }
        error("从 ${Path.of("").toAbsolutePath()} 向上找不到仓根（要有 settings.gradle.kts + core-vm）")
    }

    /**
     * 抹掉注释与字符串/字符字面量，保留换行以维持行号。
     *
     * Kotlin 块注释可嵌套，故按深度计；三引号原始串单独一态（里面可以有裸 `"` 与 `$`）。
     * 未闭合的串按行末收口（容错，不让一个坏文件吞掉后续所有行）。
     *
     * 按「一种状态一小段」拆开而不是挤在一个大 `when` 里：单函数堆六态既过不了 detekt 的
     * 方法长度/圈复杂度闸，也看不出每个状态在哪收口。
     */
    private fun codeOnly(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            i = when {
                src.startsWith("//", i) -> skipLineComment(src, i + 2, out)
                src.startsWith("/*", i) -> skipBlockComment(src, i + 2, out)
                src.startsWith(RAW_DELIM, i) -> skipRawString(src, i + RAW_DELIM.length, out)
                src[i] == '"' -> skipQuoted(src, i + 1, '"', out)
                src[i] == '\'' -> skipQuoted(src, i + 1, '\'', out)
                else -> copyCode(src, i, out)
            }
        }
        return out.toString()
    }

    /** 复制普通代码直到「可能是注释/字面量起点」的字符；返回停下的下标（可能等于 [from]）。 */
    private fun copyCode(src: String, from: Int, out: StringBuilder): Int {
        var i = from
        while (i < src.length && !isStopAt(src, i)) {
            out.append(src[i])
            i++
        }
        return i
    }

    /** 这个位置是否该让 [codeOnly] 换状态：引号起字面量、斜杠起注释。 */
    private fun isStopAt(src: String, at: Int): Boolean {
        val c = src[at]
        return c == '"' || c == '\'' || isCommentStart(src, at)
    }

    private fun isCommentStart(src: String, at: Int): Boolean = when {
        src[at] != '/' -> false
        at + 1 >= src.length -> false
        else -> src[at + 1] == '/' || src[at + 1] == '*'
    }

    /** 行注释到行末：注释体抹成空格，换行原样保留（行号靠它对齐）。 */
    private fun skipLineComment(src: String, from: Int, out: StringBuilder): Int {
        var i = from
        out.append("  ")
        while (i < src.length && src[i] != '\n') {
            out.append(' ')
            i++
        }
        return i
    }

    /** 块注释按嵌套深度收口：Kotlin 允许嵌套，提前于第一个闭合符收口会漏掉后半段代码。 */
    private fun skipBlockComment(src: String, from: Int, out: StringBuilder): Int {
        var i = from
        var depth = 1
        out.append("  ")
        while (i < src.length && depth > 0) {
            if (src.startsWith("/*", i)) {
                depth++
                out.append("  ")
                i += 2
            } else if (src.startsWith("*/", i)) {
                depth--
                out.append("  ")
                i += 2
            } else {
                out.append(mask(src[i]))
                i++
            }
        }
        return i
    }

    /** 三引号原始串：里面可以有裸 `"`，只在下一个 `"""` 收口。 */
    private fun skipRawString(src: String, from: Int, out: StringBuilder): Int {
        var i = from
        out.append(" ".repeat(RAW_DELIM.length))
        while (i < src.length) {
            if (src.startsWith(RAW_DELIM, i)) {
                out.append(" ".repeat(RAW_DELIM.length))
                return i + RAW_DELIM.length
            }
            out.append(mask(src[i]))
            i++
        }
        return i
    }

    /** 普通串 / 字符字面量：认 `\` 转义；未闭合时按行末收口。 */
    private fun skipQuoted(src: String, from: Int, quote: Char, out: StringBuilder): Int {
        var i = from
        out.append(' ')
        while (i < src.length) {
            val c = src[i]
            if (c == '\\' && i + 1 < src.length) {
                out.append("  ")
                i += 2
            } else if (c == quote || c == '\n') {
                out.append(mask(c))
                return i + 1
            } else {
                out.append(' ')
                i++
            }
        }
        return i
    }

    private fun mask(c: Char): Char = if (c == '\n') '\n' else ' '

    private companion object {
        const val RAW_DELIM = "\"\"\""
    }
}
