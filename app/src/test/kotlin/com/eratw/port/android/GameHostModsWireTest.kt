package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2-M0 / R7-C4 的 app 侧接线哨兵（2026-11-07；2026-11-20 A3 重锚）。
 *
 * `GameHost` 挂在 Android 运行时上（app 的 JVM 单测面没有 Robolectric，实例化不了），
 * 与 [GameHostSharpWireTest] 同族用**源码面**钉——生产会话构造点必须把 mods 两参
 * 真的传给 `GameSession`，否则正式包「导入 Mod → 重启生效」链是断的（此前只有测试在传）。
 *
 * 源码哨兵的锚法（2026-11-20 A3 重锚后）：不再逐字匹配赋值表达式（旧哨兵
 * `modsDirectory = metadata.programSha256?.let { ModStore.modsDirFor(gameRoot).toPath() }`
 * 连 `?.let { … }` 的写法细节一起钉死，装配段换写法就假红，失败模式与 A2 重锚前的
 * `host?.memoryCensusReportLine(),` 同构），改锚「具名实参绑定 + 块内拓扑」
 * （口径同 [ResourceCensusWiringTest] 的 A2 重锚）：
 * - `modsDirectory` / `baseProgramSha256` 是 GameSession 构造器的具名形参
 *   （core-vm/src/main/kotlin/com/eratw/port/core/proc/GameSession.kt:392/:399，均 `= null` 缺省），
 *   对端改名直接编译红，锚不可能静默漂移；
 * - 右值只要求「侧车声明 `programSha256` + 导入端同源 `modsDirFor(` + `lowercase(`」这些
 *   编译期绑定名出现在同一绑定表达式里，对换行、改拆分、换 `?.let`/`?.run`/`?.also` 等写法鲁棒；
 * - 旧哨兵的「无条件放开」负断言升级成结构化判据：绑定表达式必须引用 `programSha256`
 *   （锚①内含）+ `modsDirFor(` 全文件只许挂在接线一处（锚③）——任何无条件写法同时破两条，
 *   比逐字抓一种写法更严。`modsDirectory` 非空而 sha 缺失会撞 GameSession.kt:893-899 的
 *   requireNotNull（补丁身份校验比对口径），这就是 fail-closed 判据的语义出处；
 * - import 行不再逐字钉：改直引 Companion 函数等写法会动 import，绑定名 `modsDirFor(` 不会；
 * - 次序断言只钉「侧车读取先于会话构造」的块间拓扑，不钉局部变量名。
 *
 * 当前生产锚（写死的日子会漂，绑定名不会）：GameHost.kt 的 `GameSession(` 构造点
 * （全文件唯一，2026-11-20 实测 ：1326）块内的 `modsDirectory =`（:1332）与
 * `baseProgramSha256 =`（:1336）绑定、`BootMetadata.read` 侧车读取（:830 预读线程 +
 * :1276 await 兜底两处）。
 *
 * C# 锚说明：mods 接线是宿主移植的装配面（正式包「导入 Mod → 重启生效」链），
 * 反编译参考树里没有对应物；本文件的锚 = GameSession 构造器具名形参（core-vm 侧
 * 编译期绑定）+ 每笔重锚的反向实证（摘掉接线必须转红），读数见 BURN/PROGRESS.md。
 */
class GameHostModsWireTest {

    @Test
    fun `生产会话接线传入 modsDirectory 与 sidecar base SHA`() {
        val host = codeLines("GameHost.kt")
        // 会话构造点只许一处（装配点唯一口径，同 A2）：多处构造会让两参来源分叉。
        assertEquals(
            "GameSession 构造点只许一处（第二处多半绕过了侧车判据）",
            1,
            host.countOccurrences("GameSession("),
        )
        val args = gameSessionArgsBlock(host)
        assertTrue("GameSession 实参块找不到（构造写法换了 idiom？人工核一眼）", args.isNotEmpty())
        // 锚①：modsDirectory 具名实参绑定，右值同一绑定表达式里给出「侧车声明 + 导入端同源」
        // 两个编译期绑定名（写法细节 `?.let { … }` 不锚，摘线 / 无条件放开都在此红）。
        val modsBind = Regex("modsDirectory\\s*=\\s*[^,]*programSha256[^,]*modsDirFor\\(")
        assertTrue(
            "mods 目录必须由侧车 sha 判据守着、与导入端同源（ModStore.modsDirFor）；接线被摘或改无条件放开都在此红",
            modsBind.containsMatchIn(args),
        )
        // 锚②：baseProgramSha256 具名实参绑定，右值取侧车声明并小写化（比对口径不变）。
        val shaBind = Regex("baseProgramSha256\\s*=\\s*[^,]*programSha256[^,]*lowercase\\(")
        assertTrue(
            "base SHA 必须取 .meta 侧车声明并小写化；接线被摘在此红",
            shaBind.containsMatchIn(args),
        )
        // 锚③：同源读点全文件只许一处（多出来的多半是第二条放开路径）。
        assertEquals(
            "modsDirFor( 全文件只许挂在 modsDirectory 接线一处",
            1,
            host.countOccurrences("modsDirFor("),
        )
    }

    @Test
    fun `侧车缺 sha 时两参皆 null 且侧车读取先于会话构造`() {
        val host = codeLines("GameHost.kt")
        // fail-closed 的结构化表述：modsDirectory 绑定全文件唯一 + 锚①（绑定右值必须引用
        // programSha256，安全调用落 null）⇒ 任何「无条件放开」写法都同时破两条；
        // 缺 sha ⇒ 两参皆 null，与接线前轨迹一致（GameSession.kt:893-899 的 requireNotNull
        // 只拦「目录非空但 sha 缺失」的半接线态）。
        assertEquals(
            "modsDirectory 绑定只许一处（第二处 = 无条件放开路径）",
            1,
            host.countOccurrences("modsDirectory ="),
        )
        assertTrue(
            "装配段必须读出侧车 metadata（BootMetadata.read）",
            host.contains("BootMetadata.read"),
        )
        val metaAt = host.indexOf("BootMetadata.read")
        val sessionAt = host.indexOf("GameSession(")
        assertTrue("必须构造 GameSession", sessionAt >= 0)
        assertTrue(
            "侧车读取必须先于 GameSession 构造（metaAt=$metaAt sessionAt=$sessionAt）",
            sessionAt > metaAt,
        )
    }

    private val moduleRootCandidates = listOf(File("."), File("../app"), File("app"))

    private fun moduleRoot(): File =
        moduleRootCandidates.firstOrNull { File(it, "src/main/kotlin").isDirectory }
            ?: error("找不到 app/src/main/kotlin（试了 ${moduleRootCandidates.joinToString { it.path }}）")

    /** 主源文件的**代码行**（丢掉注释与 KDoc）：源码面钉的是接线，不是文档措辞。 */
    private fun codeLines(name: String): String =
        File(moduleRoot(), "src/main/kotlin/com/eratw/port/android/$name")
            .readText(Charsets.UTF_8)
            .lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/" + "*")
            }
            .joinToString("\n")

    /**
     * 抽出 `GameSession(` 构造点的**实参括号内**文本（括号配平，嵌套调用不算出界）。
     * 只取实参内容、不带构造头：块内的绑定/次序断言就不受实参列表写法影响。
     * 找不到开标记（构造换了 idiom 或被搬走）返回空串，由用例显式红掉让人来核。
     */
    private fun gameSessionArgsBlock(code: String): String {
        val marker = "GameSession("
        val start = code.indexOf(marker)
        if (start < 0) return ""
        var depth = 0
        for (i in start + marker.length - 1 until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(start + marker.length, i)
                }
            }
        }
        return ""
    }

    private fun String.countOccurrences(needle: String): Int {
        var index = 0
        var count = 0
        while (true) {
            val at = indexOf(needle, index)
            if (at < 0) return count
            count++
            index = at + needle.length
        }
    }
}
