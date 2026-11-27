package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「导入包携带 mod 资源」的出口判据：`ModImportController.import` 成功路径把口上 zip 里
 * `"<来源目录名>.assets/"` 的条目**重挂**到 `mods/rt2/<导入端 packageId>.assets/`（来源目录名
 * 通常是模块包源码里的 `PACKAGE_ID`，见 `modgen pack-mod` 产物）。
 *
 * 合同与夹具口径见 [ModImportAssets]：
 * - 来源目录名合法 + 名字合法 ⇒ 落盘（相对路径原样、嵌套子目录保留；来源 ≠ 本包则重挂并 WARN）；
 * - 非法来源目录名 / 名字非法 ⇒ 跳过 + WARN 汇总（导入照常成功，重挂不放松名字校验）；
 * - 重挂后跨来源同名 ⇒ 整包拒导（STORAGE 失败面点名冲突）；同来源重名只跳过 + WARN；
 * - 超单文件 / 超单包 ⇒ 整面资源拒绝 + WARN，盘上零半套；
 * - 原子：临时目录不残留；重复导入按包级替换；
 * - 编译失败 ⇒ 资源零落盘（资源面在编译成功之后才可能落正式名）。
 *
 * 全部纯 JVM：base 上下文注入 + 内存 zip 夹具，不碰 Android 类。
 */
class ModImportAssetsTest {

    // ------------------------------------------------------------------
    // 夹具：mini 侧车 + 合成 base 计数 + 临时 mods 目录 + 内存 zip（照
    // ModImportControllerTest 的同款夹具；那份是 private，这里复制一份）
    // ------------------------------------------------------------------

    private fun miniMetadata(): BootMetadata {
        val names = listOf("A_ONE", "A_TWO", "A_SUM", "A_DEF")
        val argCounts = mapOf("A_TWO" to 1, "A_DEF" to 3)
        return BootMetadata(
            functions = names.mapIndexed { i, name ->
                FuncMeta(
                    index = i,
                    key = name,
                    name = name,
                    isMethod = name == "A_SUM",
                    argIntCount = argCounts[name] ?: 0,
                    argStrCount = 0,
                    returnsStr = false,
                    defIndex = i,
                    position = "mini.ERB:${i + 1}",
                )
            },
            methodTypes = mapOf("A_SUM" to BootMetadata.MethodType.INT),
        )
    }

    private fun miniBaseCounts(): PatchCompiler.BaseCounts =
        PatchCompiler.BaseCounts(
            instructionCount = 4000,
            functionCount = 4,
            statementCount = 100,
            stringCount = 10,
            longCount = 5,
            exprCount = 20,
        )

    /** 可编译的最小补丁源（只引用 base 的 A_ONE）；落盘名固定，packageId 可预先算定。 */
    private val patchSource = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"

    private fun contextOf(): BaseCompileContext =
        BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null)

    private fun controllerIn(tmp: File): ModImportController =
        ModImportController(
            modStore = ModStore(File(tmp, "mods")),
            baseSource = { contextOf() },
            gameRoot = tmp,
        )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 导入并断言成功，返回详情（失败时把 outcome 打进断言消息，便于定位）。 */
    private fun imported(outcome: ModImportOutcome): ImportedPatchDetail {
        val detail = (outcome as? ModImportOutcome.Imported)?.detail
        return detail ?: throw AssertionError("导入应成功，实为 $outcome")
    }

    /** 本次导入的 packageId：由落盘名集合稳定派生，可在造 zip 之前算出来。 */
    private fun packageIdOf(controller: ModImportController, diskName: String = SOURCE_NAME): String =
        controller.packageIdFor(listOf(diskName))

    private fun rt2Of(tmp: File): File = File(File(tmp, "mods"), "rt2")

    private fun assetDirOf(tmp: File, packageId: String): File = File(rt2Of(tmp), packageId + ".assets")

    /** rt2 下残留的临时/备份目录（原子落盘的收尾判据：正常路径必须为空）。 */
    private fun residueOf(tmp: File): List<String> =
        rt2Of(tmp).list()?.filter { it.contains(".tmp-") || it.contains(".bak-") } ?: emptyList()

    /**
     * 不可压缩的合成字节（均匀随机）。
     *
     * 超限用例必须用它而不是 `ByteArray(n)`：全零字节 deflate 后压缩比可到约 1000 倍，
     * 会先撞上 [ModZipGuard.MAX_COMPRESSION_RATIO] 的整包拒导（那是另一个用例的面），
     * 这里要测的是资源面的「单文件 / 单包」上限。随机字节压缩比约 1，闸放行。
     */
    private fun randomBytes(count: Int, seed: Long): ByteArray =
        ByteArray(count).also { java.util.Random(seed).nextBytes(it) }

    /**
     * 资源面告警（玩家可见文案，ZH 档）。
     *
     * 2026-11 改口径：`[mod.resources]` 取证前缀已从玩家面移除，告警改按 [ModImportCopy] 语言档
     * 拼正式文案，并由 [ImportedPatchDetail.resourceWarnings] 结构化带出——不再靠字符串前缀过滤
     * `parseWarnings`（等价改写：同一个列表同时是 parseWarnings 的子序列）。
     */
    private fun toolbarWarnings(detail: ImportedPatchDetail): List<String> = detail.resourceWarnings

    // ------------------------------------------------------------------
    // 1. 主路径：源 + 资源同包 ⇒ 资源落到 rt2/<packageId>.assets/ 且相对路径原样
    // ------------------------------------------------------------------

    @Test
    fun `源包顺带资源——前缀匹配条目落到 assets 目录且相对路径原样`() {
        val tmp = Files.createTempDirectory("eratw-modassets").toFile()
        val controller = controllerIn(tmp)
        val packageId = packageIdOf(controller)
        val zip = zipOf(
            SOURCE_NAME to patchSource.toByteArray(Charsets.UTF_8),
            "$packageId.assets/img/a.png" to PNG,
            "$packageId.assets/sub/deep/b.bin" to byteArrayOf(1, 2, 3),
            "readme.txt" to "不是源也不是资源，按既有合同跳过".toByteArray(Charsets.UTF_8),
        )

        val detail = imported(controller.import(listOf(PickedModFile("kojo_mod.ZIP", zip))))

        assertEquals("资源条目数应回显", 2, detail.resourceEntryCount)
        val assets = assetDirOf(tmp, packageId)
        assertTrue("资源目录应存在：$assets", assets.isDirectory)
        assertArrayEquals("资源字节原样", PNG, File(assets, "img/a.png").readBytes())
        assertArrayEquals("嵌套路径原样", byteArrayOf(1, 2, 3), File(assets, "sub/deep/b.bin").readBytes())
        assertTrue("无告警：${detail.parseWarnings}", detail.parseWarnings.isEmpty())
        assertTrue("临时目录不得残留：${residueOf(tmp)}", residueOf(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 2. 模块包前缀（eratw.demopack.assets/，= 源码里的 PACKAGE_ID）⇒ 重挂到本包 + 一条重挂 WARN
    // ------------------------------------------------------------------

    @Test
    fun `模块包前缀——来源目录名重挂到本包并汇总重挂告警`() {
        val tmp = Files.createTempDirectory("eratw-modassets").toFile()
        val controller = controllerIn(tmp)
        val packageId = packageIdOf(controller)
        val zip = zipOf(
            SOURCE_NAME to patchSource.toByteArray(Charsets.UTF_8),
            "eratw.demopack.assets/img/a.png" to PNG,
            "eratw.demopack.assets/b.png" to byteArrayOf(9, 9),
        )

        val detail = imported(controller.import(listOf(PickedModFile("kojo_mod.ZIP", zip))))

        assertEquals("重挂后的条目照常计数", 2, detail.resourceEntryCount)
        val assets = assetDirOf(tmp, packageId)
        assertTrue("资源目录应落在本包名下：$assets", assets.isDirectory)
        assertArrayEquals("资源字节原样（相对路径不变）", PNG, File(assets, "img/a.png").readBytes())
        assertArrayEquals("根级资源字节原样", byteArrayOf(9, 9), File(assets, "b.png").readBytes())
        assertFalse("不得按模块包 PACKAGE_ID 另建目录", File(rt2Of(tmp), "eratw.demopack.assets").exists())
        val warnings = toolbarWarnings(detail)
        assertEquals("重挂要汇总成一条 WARN：$warnings", 1, warnings.size)
        assertTrue(
            "WARN 要点名条数与来源目录名：${warnings[0]}",
            warnings[0].contains("2 条") && warnings[0].contains("eratw.demopack"),
        )
        assertTrue("临时目录不得残留：${residueOf(tmp)}", residueOf(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 2′. 非法来源目录名（`..` / 绝对 / 盘符 / 嵌套 / 控制字符 / 空）⇒ 跳过 + WARN，重挂不放松校验
    // ------------------------------------------------------------------

    @Test
    fun `非法来源目录名——逐条跳过并告警且不写到 assets 目录之外`() {
        val tmp = Files.createTempDirectory("eratw-modassets").toFile()
        val controller = controllerIn(tmp)
        val packageId = packageIdOf(controller)
        val zip = zipOf(
            SOURCE_NAME to patchSource.toByteArray(Charsets.UTF_8),
            "$packageId.assets/ok.png" to PNG,
            "../.assets/escape.png" to EVIL,
            "/abs.assets/abs.png" to EVIL,
            "C:evil.assets/drive.png" to EVIL,
            "nested/dir.assets/nested.png" to EVIL,
            ".assets/bare.png" to EVIL,
            "bad\u0001ctl.assets/ctl.png" to EVIL,
        )

        val detail = imported(controller.import(listOf(PickedModFile("kojo_mod.ZIP", zip))))

        assertEquals("只有合法来源目录名的条目落盘", 1, detail.resourceEntryCount)
        val assets = assetDirOf(tmp, packageId)
        assertTrue("合法条目应在", File(assets, "ok.png").isFile)
        assertEquals("每个非法来源目录名一条 WARN：${detail.parseWarnings}", 6, toolbarWarnings(detail).size)
        assertFalse("不得写到 mods 根（../ 逃逸）", File(File(tmp, "mods"), "escape.png").exists())
        assertFalse("不得写到游戏根", File(tmp, "escape.png").exists())
        assertFalse("绝对路径条目不得落盘", File(assets, "abs.png").exists())
        assertTrue("临时目录不得残留", residueOf(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 2″. 重挂后跨来源同名 ⇒ 整包拒导（STORAGE 失败面点名冲突，绝不后写盖先写）
    // ------------------------------------------------------------------

    @Test
    fun `重挂后跨来源同名冲突——整包拒导并点名冲突条目`() {
        val tmp = Files.createTempDirectory("eratw-modassets").toFile()
        val controller = controllerIn(tmp)
        val packageId = packageIdOf(controller)
        val zip = zipOf(
            SOURCE_NAME to patchSource.toByteArray(Charsets.UTF_8),
            "eratw.one.assets/img/a.png" to PNG,
            "eratw.two.assets/img/a.png" to EVIL,
        )

        val outcome = controller.import(listOf(PickedModFile("kojo_mod.ZIP", zip)))

        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("跨来源同名应整包拒导，实为 $outcome")
        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
        assertTrue(
            "失败文案要点名冲突资源与两个来源目录名：${failed.message}",
            failed.message.contains("img/a.png") &&
                failed.message.contains("eratw.one") &&
                failed.message.contains("eratw.two"),
        )
        assertFalse("拒导不得落资源目录", assetDirOf(tmp, packageId).exists())
        assertTrue("临时目录不得残留：${residueOf(tmp)}", residueOf(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 2‴. 同来源重名（两个 zip 各带一份）仍只跳过 + WARN（既有「同名取先」口径不变）
    // ------------------------------------------------------------------

    @Test
    fun `同来源重名——只跳过并告警不拒导`() {
        val tmp = Files.createTempDirectory("eratw-modassets").toFile()
        val controller = controllerIn(tmp)
        val first = zipOf(
            SOURCE_NAME to patchSource.toByteArray(Charsets.UTF_8),
            "eratw.demopack.assets/dup.png" to PNG,
        )
        val second = zipOf(
            SECOND_SOURCE_NAME to secondPatchSource.toByteArray(Charsets.UTF_8),
            "eratw.demopack.assets/dup.png" to EVIL,
        )

        val detail = imported(
            controller.import(
                listOf(PickedModFile("kojo_mod_1.ZIP", first), PickedModFile("kojo_mod_2.ZIP", second)),
            ),
        )

        // packageId 由**两个**源的落盘名共同派生，不能再拿单源算（包级替换用例同理）。
        val packageId = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        assertEquals("重名只保留先到的一条", 1, detail.resourceEntryCount)
        assertArrayEquals("先到者胜（绝不后写盖先写）", PNG, File(assetDirOf(tmp, packageId), "dup.png").readBytes())
        assertTrue(
            "要出重名 WARN：${detail.parseWarnings}",
            toolbarWarnings(detail).any { it.contains("重名") },
        )
    }

    // ------------------------------------------------------------------
    // 3. 非法资源名（../、绝对、空段、`.` 段、反斜杠）⇒ 逐条跳过 + WARN，且不写盘外
    // ------------------------------------------------------------------

    @Test
    fun `非法资源名——逐条跳过并告警且不写到 assets 目录之外`() {
        val tmp = Files.createTempDirectory("eratw-modassets").toFile()
        val controller = controllerIn(tmp)
        val packageId = packageIdOf(controller)
        val zip = zipOf(
            SOURCE_NAME to patchSource.toByteArray(Charsets.UTF_8),
            "$packageId.assets/ok/good.png" to PNG,
            "$packageId.assets/../escape.png" to EVIL,
            "$packageId.assets//abs.png" to EVIL,
            "$packageId.assets/a/./b.png" to EVIL,
            "$packageId.assets/a\\b.png" to EVIL,
        )

        val detail = imported(controller.import(listOf(PickedModFile("kojo_mod.ZIP", zip))))

        assertEquals("只有合法条目落盘", 1, detail.resourceEntryCount)
        val assets = assetDirOf(tmp, packageId)
        assertTrue("合法条目应在", File(assets, "ok/good.png").isFile)
        assertEquals("每个非法条目一条 WARN：${detail.parseWarnings}", 4, toolbarWarnings(detail).size)
        assertFalse("不得写到 mods 根（../ 逃逸）", File(File(tmp, "mods"), "escape.png").exists())
        assertFalse("不得写到游戏根", File(tmp, "escape.png").exists())
        assertFalse("反斜杠条目不得落盘", File(assets, "a/b.png").exists())
        assertTrue("临时目录不得残留", residueOf(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 4. 超单文件上限（4 MiB）⇒ 整面资源拒绝 + WARN，零半套
    // ------------------------------------------------------------------

    @Test
    fun `超单文件上限——整面资源拒绝且不留半套`() {
        val tmp = Files.createTempDirectory("eratw-modassets").toFile()
        val controller = controllerIn(tmp)
        val packageId = packageIdOf(controller)
        val oversize = randomBytes(ModImportAssets.MAX_RESOURCE_FILE_BYTES.toInt() + 1, seed = 11L)
        val zip = zipOf(
            SOURCE_NAME to patchSource.toByteArray(Charsets.UTF_8),
            "$packageId.assets/ok.png" to PNG,
            "$packageId.assets/big.bin" to oversize,
        )

        val detail = imported(controller.import(listOf(PickedModFile("kojo_mod.ZIP", zip))))

        assertEquals("整面拒绝 ⇒ 零资源", 0, detail.resourceEntryCount)
        assertFalse("不得创建资源目录（连合法条目也不落）", assetDirOf(tmp, packageId).exists())
        val warnings = toolbarWarnings(detail)
        assertTrue("要 WARN 点名上限：$warnings", warnings.any { it.contains("单文件上限") })
        assertTrue("临时目录不得残留", residueOf(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 5. 超单包总量上限（16 MiB）⇒ 整面资源拒绝 + WARN，零半套
    // ------------------------------------------------------------------

    @Test
    fun `超单包总量上限——整面资源拒绝且不留半套`() {
        val tmp = Files.createTempDirectory("eratw-modassets").toFile()
        val controller = controllerIn(tmp)
        val packageId = packageIdOf(controller)
        // 每条 3.5 MiB（低于单文件上限）× 5 = 17.5 MiB > 16 MiB。
        // 随机字节而非全零：全零的压缩比会让 zip 防御闸先拒整包（见 randomBytes 注释）。
        val chunk = randomBytes((3.5 * 1024 * 1024).toInt(), seed = 12L)
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries += SOURCE_NAME to patchSource.toByteArray(Charsets.UTF_8)
        for (i in 0 until 5) entries += "$packageId.assets/part$i.bin" to chunk
        val zip = zipOf(*entries.toTypedArray())

        val detail = imported(controller.import(listOf(PickedModFile("kojo_mod.ZIP", zip))))

        assertEquals("整面拒绝 ⇒ 零资源", 0, detail.resourceEntryCount)
        assertFalse("不得创建资源目录", assetDirOf(tmp, packageId).exists())
        val warnings = toolbarWarnings(detail)
        assertTrue("要 WARN 点名总量上限：$warnings", warnings.any { it.contains("资源总量超上限") })
        assertTrue("临时目录不得残留", residueOf(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 6. 编译失败但包里带资源 ⇒ 资源零落盘（也不会留临时目录）
    // ------------------------------------------------------------------

    @Test
    fun `源码编译失败——资源零落盘且临时目录清理`() {
        val tmp = Files.createTempDirectory("eratw-modassets").toFile()
        val controller = controllerIn(tmp)
        val packageId = packageIdOf(controller)
        // 函数内 GLOBAL #DIM 仍是编译期硬拒（原版装载错误，KI-GLOBAL-DIM 波不放宽该形态），
        // 资源面不得到正式名。函数外全局 #DIM 已于 2026-11-26 改判放行，不能再当失败夹具。
        val badSource = "@B_GLOBAL\n#DIM GLOBAL G_SIDE, 1\nRETURN 0\n"
        val zip = zipOf(
            SOURCE_NAME to badSource.toByteArray(Charsets.UTF_8),
            "$packageId.assets/img/a.png" to PNG,
        )

        val outcome = controller.import(listOf(PickedModFile("kojo_mod.ZIP", zip)))

        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("编译失败应折 Failed，实为 $outcome")
        assertEquals(ModImportFailureKind.COMPILE, failed.kind)
        assertFalse("资源零落盘", assetDirOf(tmp, packageId).exists())
        assertTrue("临时目录不得残留", residueOf(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 7. 重复导入 = 包级替换（新资源面整目录替换旧面，旧面的多余文件消失）
    // ------------------------------------------------------------------

    @Test
    fun `重复导入——同包重复导入按包级替换`() {
        val tmp = Files.createTempDirectory("eratw-modassets").toFile()
        val controller = controllerIn(tmp)
        val packageId = packageIdOf(controller)

        val first = zipOf(
            SOURCE_NAME to patchSource.toByteArray(Charsets.UTF_8),
            "$packageId.assets/old.png" to PNG,
        )
        imported(controller.import(listOf(PickedModFile("kojo_mod_v1.ZIP", first))))
        val assets = assetDirOf(tmp, packageId)
        assertTrue("第一版资源应在", File(assets, "old.png").isFile)

        val second = zipOf(
            SOURCE_NAME to patchSource.toByteArray(Charsets.UTF_8),
            "$packageId.assets/new.png" to byteArrayOf(7, 7, 7),
        )
        val detail = imported(controller.import(listOf(PickedModFile("kojo_mod_v2.ZIP", second))))

        assertEquals(1, detail.resourceEntryCount)
        assertTrue("新版资源应在", File(assets, "new.png").isFile)
        assertFalse("旧版多余文件必须消失（整目录替换）", File(assets, "old.png").exists())
        assertArrayEquals("新版资源字节应原样", byteArrayOf(7, 7, 7), File(assets, "new.png").readBytes())
        assertEquals("替换后目录里只应有 1 个文件", 1, assets.list()?.size)
        assertTrue("临时/备份目录不得残留：${residueOf(tmp)}", residueOf(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 8. 回归：不带资源的普通包 ⇒ 计数 0、不建资源目录、无告警
    // ------------------------------------------------------------------

    @Test
    fun `普通包不带资源——计数为零且不建资源目录`() {
        val tmp = Files.createTempDirectory("eratw-modassets").toFile()
        val controller = controllerIn(tmp)
        val packageId = packageIdOf(controller)

        val detail = imported(
            controller.import(listOf(PickedModFile(SOURCE_NAME, patchSource.toByteArray(Charsets.UTF_8)))),
        )

        assertEquals(0, detail.resourceEntryCount)
        assertFalse("普通包不得凭空建资源目录", assetDirOf(tmp, packageId).exists())
        assertTrue("无告警：${detail.parseWarnings}", detail.parseWarnings.isEmpty())
    }

    // ------------------------------------------------------------------
    // 9. 文本哨兵：app 侧复制的常量/名字规则必须与 core-vm 运行期同源（防漂移）
    // ------------------------------------------------------------------

    @Test
    fun `资源限额与名字规则与 ModResourceStore 源码保持同源`() {
        // Gradle 单测工作目录是模块根（app/），兜一层仓库根形态。
        // 公开分仓后 core-vm 在同级引擎仓 Kotera（composite build 的 ../Kotera）。
        // 三个候选逐个判存在再取（早先写成 elvis 链时中间那项没有 isFile 兜底，
        // 前一个候选落空就会停在不存在的路径上）。
        val source = listOf(
            File("../core-vm/src/main/kotlin/com/eratw/port/core/mod/ModResourceStore.kt"),
            File("core-vm/src/main/kotlin/com/eratw/port/core/mod/ModResourceStore.kt"),
            File("../../Kotera/core-vm/src/main/kotlin/com/eratw/port/core/mod/ModResourceStore.kt"),
        ).firstOrNull { it.isFile }
            ?: error("定位不到 ModResourceStore.kt 真源（cwd=${System.getProperty("user.dir")}）")
        val text = source.readText()

        assertTrue(
            "运行期单文件上限改了，导入期会与读取侧漂移：$source",
            text.contains("MAX_RESOURCE_FILE_BYTES: Long = 4L * 1024L * 1024L"),
        )
        assertTrue(
            "运行期单包上限改了，导入期会与读取侧漂移：$source",
            text.contains("MAX_RESOURCE_TOTAL_BYTES: Long = 16L * 1024L * 1024L"),
        )
        assertTrue(
            "运行期资源目录后缀改了，导入期会落到读不到的目录：$source",
            text.contains("""ASSET_DIR_SUFFIX: String = ".assets""""),
        )
        assertTrue(
            "运行期名字校验改了，导入期会放行读侧拒收的名字：$source",
            text.contains("private fun isValidResourceName(name: String): Boolean"),
        )
        assertTrue(
            "运行期逐段校验改了，导入期会接受 .. 段/空段：$source",
            text.contains("private fun isAllowedSegment(segment: String): Boolean"),
        )
        assertTrue(
            "运行期单字符校验改了，导入期会放行控制字符/冒号：$source",
            text.contains("private fun isAllowedNameChar(ch: Char): Boolean"),
        )
    }

    // ------------------------------------------------------------------
    // 辅助：字节断言（junit 4 无 assertArrayEquals 的 Objects 版本，直接比列表）
    // ------------------------------------------------------------------

    private fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) {
        assertEquals(message, expected.toList(), actual.toList())
    }

    private companion object {
        /** 合成 base sha（64 位小写十六进制）。 */
        private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        /** 固定的源落盘名（packageId 由它派生，造 zip 前可算）。 */
        private const val SOURCE_NAME = "KOJO_PATCH.ERB"

        /** 第二个 zip 用的源落盘名（跨 zip 重名资源用例：每个 zip 都必须有 ERB 条目）。 */
        private const val SECOND_SOURCE_NAME = "KOJO_PATCH_2.ERB"

        /** 第二个 zip 的补丁源（编不了也不影响资源面用例——但这里仍给能编过的最小正文）。 */
        private const val secondPatchSource = "@B_HELLO2\nCALL A_ONE(4)\nPRINTL HELLO_PATCH_2\nRETURN 0\n"

        /** 一张「图」的合成字节（任何字节都算资源；读取侧不校验格式）。 */
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

        /** 逃逸探测用的标记字节。 */
        private val EVIL = byteArrayOf(0x45, 0x56, 0x49, 0x4C)
    }
}
