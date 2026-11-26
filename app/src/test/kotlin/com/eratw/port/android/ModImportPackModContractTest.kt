package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ModResourceStore
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「pack-mod 打得进去」与「导入端/运行期收得下」判据对拍的**导入侧**（pack 侧见
 * `tools/modgen/.../PackModImportContractTest`，那张 KDoc 对照表是唯一口径）。
 *
 * 本类不改导入端任何 main 代码（`ModImportAssets.kt`/`ModResourceStore.kt` 只读），只钉住：
 * - **R1 资源前缀形态**：`PACKAGE_ID` 为空白串 / `.` / `..` 时 pack 旧口径会产出 `.assets/…`、
 *   `..assets/…`、`...assets/…`；导入端把前缀为空段/点段的条目逐条 WARN 跳过 ⇒ 资源全丢。
 *   本类断言导入端**确实静默跳过**（不是整包拒导），这正是 pack 侧必须收紧的理由。
 * - **合法形态真往返**：pack 同形夹具 zip ⇒ 导入 ⇒ `ModResourceStore` 按目标 packageId 读回原字节。
 * - **R2 源主名形态**：主名含 `..`/Windows 保留字符/超长时 `ModStore.save` 抛错 ⇒ 整包
 *   `Failed(STORAGE)`（硬失败，非静默）；pack 侧因此拒收，作者在打包期就看到原因。
 *
 * 为什么是两段对拍而不是同进程 A/B：`PackModZip` 是 `tools:modgen` 的 `internal object`，`:app`
 * 测试源集看不见（且 `app` 的 build 脚本不在本波写面，不能加测试依赖）。夹具按 pack-mod 合同构造
 * （见 `ModImportPackModEndToEndTest` 的合同对齐清单），不是真跑 pack-mod。
 *
 * 宿主 FS 限制：Windows 造不出 `* ? " < > |` 与尾随点/空格的**资源文件**，这类形态只做纯谓词断言
 * （[ModImportAssets.isValidResourceName]），不做真写盘；Android 目标端可写且运行期同样收。
 */
class ModImportPackModContractTest {

    // ------------------------------------------------------------------
    // R1：资源前缀非法 ⇒ 导入端逐条 WARN 跳过（pack 侧必须拒收的形态）
    // ------------------------------------------------------------------

    @Test
    fun `R1资源前缀为空段点段——导入端逐条跳过且运行期读不到`() {
        val tmp = Files.createTempDirectory("eratw-modcontract").toFile()
        val controller = controllerIn(tmp)
        val zip = packModLikeZip(
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            ".assets/leak.png" to PNG,
            "..assets/dot.png" to PNG,
            "...assets/dotdot.png" to PNG,
        )

        val detail = imported(controller.import(listOf(PickedModFile("bad-prefix.ZIP", zip))))

        assertEquals("前缀非法 ⇒ 一条资源都落不下", 0, detail.resourceEntryCount)
        assertEquals("三条资源各留一条 WARN（不静默吞）", 3, detail.resourceWarnings.size)
        val packageId = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        assertFalse("不得建出资源目录：${assetDirOf(tmp, packageId)}", assetDirOf(tmp, packageId).exists())
        assertTrue("临时目录不得残留：${residueOf(tmp)}", residueOf(tmp).isEmpty())
    }

    // ------------------------------------------------------------------
    // 合法形态：pack 同形夹具 ⇒ 导入 ⇒ 运行期读回（真往返）
    // ------------------------------------------------------------------

    @Test
    fun `合法资源形态——导入后运行期按目标packageId读回原字节`() {
        val tmp = Files.createTempDirectory("eratw-modcontract").toFile()
        val controller = controllerIn(tmp)
        val zip = packModLikeZip(
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "$MODULE_PACKAGE_ID.assets/ui/button.png" to PNG,
            "$MODULE_PACKAGE_ID.assets/a b/c d.png" to byteArrayOf(4, 5, 6),
            "$MODULE_PACKAGE_ID.assets/中文/名字.png" to byteArrayOf(7, 8, 9),
            "$MODULE_PACKAGE_ID.assets/a.b/c" to byteArrayOf(10),
        )

        val detail = imported(controller.import(listOf(PickedModFile("good-pack.ZIP", zip))))

        assertEquals("四条资源都应落盘", 4, detail.resourceEntryCount)
        assertEquals(
            "只该有「来源目录名重挂」一条 WARN（合法资源名不该报非法）：${detail.resourceWarnings}",
            1,
            detail.resourceWarnings.size,
        )
        assertTrue(
            "重挂 WARN 点名来源目录名：${detail.resourceWarnings}",
            detail.resourceWarnings[0].contains(MODULE_PACKAGE_ID),
        )
        val packageId = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        assertNotEquals("目标 packageId 由导入端派生", MODULE_PACKAGE_ID, packageId)
        val api = ModResourceStore(rt2Of(tmp)).apiFor(packageId)
        assertArrayEquals("普通路径读回", PNG, api.open("ui/button.png")!!)
        assertArrayEquals("段内空格读回", byteArrayOf(4, 5, 6), api.open("a b/c d.png")!!)
        assertArrayEquals("非 ASCII 读回", byteArrayOf(7, 8, 9), api.open("中文/名字.png")!!)
        assertArrayEquals("点号目录读回", byteArrayOf(10), api.open("a.b/c")!!)
    }

    // ------------------------------------------------------------------
    // R2：源主名导入端收不下 ⇒ 整包 STORAGE 硬失败（pack 侧必须拒收的形态）
    // ------------------------------------------------------------------

    @Test
    fun `R2源主名含点——导入端整包STORAGE失败`() {
        assertStorageFailure("v1..2.ERB")
    }

    @Test
    fun `R2源主名含Windows保留字符——导入端整包STORAGE失败`() {
        listOf("a*b.ERB", "a?b.ERB", "a\"b.ERB", "a<b.ERB", "a>b.ERB", "a|b.ERB").forEach { name ->
            assertStorageFailure(name)
        }
    }

    @Test
    fun `R2源主名超长——导入端整包STORAGE失败`() {
        assertStorageFailure("x".repeat(125) + ".ERB")
    }

    // ------------------------------------------------------------------
    // 同主名去重：导入端只留第一份 + WARN（静默度低，本波不收紧）
    // ------------------------------------------------------------------

    @Test
    fun `同主名不同目录——导入端去重留一份且留WARN`() {
        val tmp = Files.createTempDirectory("eratw-modcontract").toFile()
        val controller = controllerIn(tmp)
        val zip = packModLikeZip(
            "a/main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "b/main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
        )

        val detail = imported(controller.import(listOf(PickedModFile("dup-main.ZIP", zip))))

        assertEquals("去重后只留第一份源（zip 内相对路径如实保留）", listOf("a/main.ERB"), detail.sourceFileNames)
        assertTrue(
            "去重必须留 WARN 点名被丢的条目：${detail.parseWarnings}",
            detail.parseWarnings.any { it.contains("b/main.ERB") || it.contains("main.ERB") },
        )
    }

    // ------------------------------------------------------------------
    // 宿主造不出的形态：三处谓词仍收（纯函数断言，不写盘）
    // ------------------------------------------------------------------

    @Test
    fun `Android可写但Windows造不出的资源名——导入端谓词仍收`() {
        listOf("a*b.png", "a?b.png", "a\"b.png", "a<b.png", "a>b.png", "a|b.png", "trailing.").forEach { name ->
            assertTrue("导入端谓词应收（Android 目标端可写）：[$name]", ModImportAssets.isValidResourceName(name))
        }
    }

    // ------------------------------------------------------------------
    // 夹具与断言助手（照 ModImportPackModEndToEndTest 同款，private 故复制）
    // ------------------------------------------------------------------

    /** 断言某个源主名的 zip 条目让整包导入硬失败（`ModStore.save` 抛错 ⇒ STORAGE）。 */
    private fun assertStorageFailure(entryName: String) {
        val tmp = Files.createTempDirectory("eratw-modcontract").toFile()
        val controller = controllerIn(tmp)
        val zip = packModLikeZip(
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            entryName to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
        )

        val outcome = controller.import(listOf(PickedModFile("bad-source.ZIP", zip)))

        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("应整包失败而不是静默跳过：$outcome")
        assertEquals("失败类目应为 STORAGE：$failed", ModImportFailureKind.STORAGE, failed.kind)
        assertTrue("失败文案要可上屏：${failed.message}", failed.message.isNotBlank())
    }

    /** 固定时间戳：1980-01-01T00:00:00Z（zip 的 DOS 时间下限，pack-mod 同款）。 */
    private val fixedTimeMillis = 315532800000L

    /** 按 pack-mod 合同造一个模块包 zip：字典序、固定时间戳、不写目录条目。 */
    private fun packModLikeZip(vararg moduleFiles: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in moduleFiles.sortedBy { it.first }) {
                val entry = ZipEntry(name)
                entry.time = fixedTimeMillis
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

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

    private fun controllerIn(tmp: File): ModImportController =
        ModImportController(
            modStore = ModStore(File(tmp, "mods")),
            baseSource = { BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null) },
            gameRoot = tmp,
        )

    private fun imported(outcome: ModImportOutcome): ImportedPatchDetail =
        (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("导入应成功，实为 $outcome")

    private fun rt2Of(tmp: File): File = File(File(tmp, "mods"), "rt2")

    private fun assetDirOf(tmp: File, packageId: String): File = File(rt2Of(tmp), packageId + ".assets")

    private fun residueOf(tmp: File): List<String> =
        rt2Of(tmp).list()?.filter { it.contains(".tmp-") || it.contains(".bak-") } ?: emptyList()

    private fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) {
        assertEquals(message, expected.toList(), actual.toList())
    }

    private companion object {
        /** 合成 base sha（64 位小写十六进制）。 */
        private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        /** 模块包源码里的 PACKAGE_ID（= pack-mod 给资源加的前缀）。 */
        private const val MODULE_PACKAGE_ID = "eratw.demopack"

        /** 可编译的最小补丁源（与其它导入用例同款）。 */
        private const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"

        /** 一张「图」的合成字节（读取侧不校验格式）。 */
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    }
}
