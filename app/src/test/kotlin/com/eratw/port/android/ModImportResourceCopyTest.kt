package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lane BH 玩家面文案判据（切片 2）：
 *
 * 1. 成功页「资源 N 条」只在 N > 0 时出（[ImportedPatchDetail.resourceSummaryLine]）；
 * 2. 资源面告警是**正式玩家文案**、按本次导入的 [ModImportCopy] 语言档拼、不再带 `[mod.resources]`
 *    取证前缀，且结构化进 [ImportedPatchDetail.resourceWarnings]（同时仍在 `parseWarnings` 里上屏）；
 * 3. 模块包来源前缀重挂的 ZH 文案点名条数与来源目录名（语义断言与 [ModImportAssetsTest] 同轴）。
 */
class ModImportResourceCopyTest {

    // ------------------------------------------------------------------
    // 1. 成功页资源行：0 条整行不出
    // ------------------------------------------------------------------

    @Test
    fun `资源行——零条时三语都不显示`() {
        val detail = detailOf(resourceEntryCount = 0)
        for (copy in listOf(ModImportCopy.ZH, ModImportCopy.EN, ModImportCopy.JA)) {
            assertNull("0 条不许出资源行（${copy.lang}）：${detail.resourceSummaryLine(copy)}", detail.resourceSummaryLine(copy))
        }
    }

    // ------------------------------------------------------------------
    // 2. 成功页资源行：N 条时三语各按自己的句子带上读数
    // ------------------------------------------------------------------

    @Test
    fun `资源行——三条时三语各自显示读数`() {
        val detail = detailOf(resourceEntryCount = 3)
        assertEquals("资源 3 条", detail.resourceSummaryLine(ModImportCopy.ZH))
        assertEquals("3 resource files", detail.resourceSummaryLine(ModImportCopy.EN))
        assertEquals("リソース 3 件", detail.resourceSummaryLine(ModImportCopy.JA))
    }

    // ------------------------------------------------------------------
    // 3. 重挂告警（ZH）：点名条数与来源目录名、无取证前缀
    // ------------------------------------------------------------------

    @Test
    fun `资源告警——ZH 档重挂文案点名条数与来源目录名`() {
        val detail = importModulePack(ModImportCopy.ZH)

        val warnings = detail.resourceWarnings
        assertEquals("重挂要汇总成一条：$warnings", 1, warnings.size)
        val line = warnings[0]
        assertTrue("要点名重挂条数：$line", line.contains("2 条"))
        assertTrue("要点名来源目录名：$line", line.contains(MODULE_PACKAGE_ID))
        assertFalse("取证前缀不许混进玩家面：$line", line.contains("[mod.resources]"))
        assertTrue("结构化字段必须同时进上屏列表：${detail.parseWarnings}", line in detail.parseWarnings)
    }

    // ------------------------------------------------------------------
    // 4. 重挂告警（EN）：按 EN 语言档拼，整句逐字钉住
    // ------------------------------------------------------------------

    @Test
    fun `资源告警——EN 档按英文句子拼出且无汉字`() {
        val detail = importModulePack(ModImportCopy.EN)

        val expected = "The import pack has 2 resource entries whose source directory is not this package; " +
            "reattached them to this package's resource directory: $MODULE_PACKAGE_ID"
        assertEquals("EN 告警要按 EN 文案键拼：${detail.resourceWarnings}", listOf(expected), detail.resourceWarnings)
        assertFalse(
            "EN 档不许混汉字：${detail.resourceWarnings}",
            detail.resourceWarnings.any { line -> line.any { it in '\u4e00'..'\u9fff' } },
        )
    }

    // ------------------------------------------------------------------
    // 5. 三语文案键形状：灌满读数后无残留槽位、非空、不带取证前缀
    // ------------------------------------------------------------------

    @Test
    fun `资源告警文案键——三语灌满不留槽位且非取证面`() {
        val keys: List<(ModImportCopy) -> String> = listOf(
            { it.resourceEntryInvalidNameFormat },
            { it.resourceEntryDuplicateFormat },
            { it.resourceFileTooLargeFormat },
            { it.resourceTotalTooLargeFormat },
            { it.resourcePrefixRebasedFormat },
            { it.resourcePrefixConflictFormat },
            { it.resourceStageFailedFormat },
            { it.resourceCommitFailedFormat },
        )
        for (copy in listOf(ModImportCopy.ZH, ModImportCopy.EN, ModImportCopy.JA)) {
            for (pick in keys) {
                val rendered = copy.fill(pick(copy), "A", "B", "C")
                assertTrue("灌满后不许残留槽位（${copy.lang}）：$rendered", '{' !in rendered && '}' !in rendered)
                assertFalse("玩家文案不许带取证前缀（${copy.lang}）：$rendered", rendered.contains("[mod.resources]"))
            }
        }
    }

    // ------------------------------------------------------------------
    // 夹具：mini base + 临时 mods + 内存 zip（照 ModImportAssetsTest 同款，private 故复制）
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

    private fun controllerIn(tmp: File): ModImportController =
        ModImportController(
            modStore = ModStore(File(tmp, "mods")),
            baseSource = { BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null) },
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

    /** 一张模块包（pack-mod 产物布局）⇒ 来源目录名（= 模块 PACKAGE_ID）被重挂到本包。 */
    private fun importModulePack(copy: ModImportCopy): ImportedPatchDetail {
        val tmp = Files.createTempDirectory("eratw-modrescopy").toFile()
        val controller = controllerIn(tmp)
        val zip = zipOf(
            SOURCE_NAME to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "$MODULE_PACKAGE_ID.assets/img/a.png" to PNG,
            "$MODULE_PACKAGE_ID.assets/b.png" to PNG,
        )
        val outcome = controller.import(listOf(PickedModFile("kojo_mod.ZIP", zip)), copy = copy)
        return (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("导入应成功（重挂只出告警、不拦导入），实为 $outcome")
    }

    /** 只填必填字段的成功详情（资源行纯函数只看 resourceEntryCount）。 */
    private fun detailOf(resourceEntryCount: Int): ImportedPatchDetail = ImportedPatchDetail(
        patchFileName = "kojo-demo.erbm",
        functionCount = 1,
        instructionCount = 3,
        entryFunction = "B_HELLO",
        sourceFileNames = listOf(SOURCE_NAME),
        parseWarnings = emptyList(),
        resourceEntryCount = resourceEntryCount,
    )

    private companion object {
        /** 合成 base sha（64 位小写十六进制）。 */
        private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        /** 固定源落盘名（packageId 由它稳定派生）。 */
        private const val SOURCE_NAME = "KOJO_PATCH.ERB"

        /** 模块包源码里的 PACKAGE_ID（= pack-mod 给资源加的前缀，与本端算出的 packageId 不同）。 */
        private const val MODULE_PACKAGE_ID = "eratw.packmod"

        /** 可编译的最小补丁源。 */
        private const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"

        /** 一张「图」的合成字节。 */
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    }
}
