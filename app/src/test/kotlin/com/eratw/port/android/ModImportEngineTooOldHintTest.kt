package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.modapi.ModApi
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
 * 导入成功页的「本包要求更新引擎」提示（本 lane）。
 *
 * ## 钉住的语义
 *
 * 1. 判据 = 包元数据声明的编译期面高于本机 `ModApi.VERSION`（`PackMeta.compiledSurface` >
 *    `ModApi.VERSION`），投影点 [PackMeta.engineSurfaceGap] 直接问装载期唯一 ABI 闸
 *    [ModDexGate.verdict]——高 / 等 / 低 / 未声明四种边界的出不出行，且**与 verdict 逐值同口径**
 *    （`isAllowed` ⇔ 无缺口），本面不重算 ABI 规则；
 * 2. 上屏措辞三件事齐全（要求版本 N、本机版本 M、本机不会装载它），三档语言都拼得出、
 *    不暴露内部词（compiledSurface / compiled / ABI / apiVersion 之类）；
 * 3. **不改导入成败、不串告警表**：未来面照常导入成功，提示单独一行上屏（不进
 *    [ImportedPatchDetail.parseWarnings]），与 DC 的引擎 API 下限告警各管各的账；
 * 4. 无缺口时成功页既有行逐字不变（[ImportedPatchDetail.engineTooOldLine] 为 null）。
 *
 * ## 为什么 zip 是自建的
 *
 * 与 `ModImportPackMetaEndToEndTest` / `ModImportEngineApiHintTest` 同因：`tools/modgen` 的
 * `PackMod` 是 `internal`、跨模块不可见，这里按同款固定键序 JSON 自建 zip。
 */
class ModImportEngineTooOldHintTest {

    private val future = ModApi.VERSION + 1

    // ------------------------------------------------------------------
    // 1. 真链路：编译期面高于本机 ⇒ detail 有缺口 + 成功页恰一行提示
    // ------------------------------------------------------------------

    @Test
    fun `compiledSurface高于本机——detail有缺口且提示行三件事齐全`() {
        val tmp = Files.createTempDirectory("eratw-enginetooold").toFile()
        val outcome = controllerIn(tmp).import(
            listOf(PickedModFile("eratw-new.ZIP", zipWithSurface(future))),
        )
        val detail = imported(outcome)

        assertEquals(
            "缺口读数 = 包要求 / 本机引擎",
            EngineSurfaceGap(required = future, current = ModApi.VERSION),
            detail.engineTooOld,
        )
        val line = requireNotNull(detail.engineTooOldLine(ModImportCopy.ZH)) { "高于本机必须出提示" }
        assertEquals(
            "提示按 ZH 档点名要求版本与本机版本",
            ModImportCopy.ZH.fill(ModImportCopy.ZH.engineTooOldWarningFormat, future, ModApi.VERSION),
            line,
        )
        assertTrue("要求版本要上屏：$line", line.contains("v$future"))
        assertTrue("本机版本要上屏：$line", line.contains("v${ModApi.VERSION}"))
        assertTrue("本机不会装载它要上屏：$line", line.contains("不会装载"))
        assertTrue("提示单独一行，不并进 parseWarnings", detail.parseWarnings.isEmpty())
    }

    // ------------------------------------------------------------------
    // 2. 边界：等于 / 低于 / 未声明 ⇒ 一条都不出
    // ------------------------------------------------------------------

    @Test
    fun `compiledSurface等于本机——不提示且成功页既有行不变`() {
        val tmp = Files.createTempDirectory("eratw-enginetooold").toFile()
        val detail = imported(
            controllerIn(tmp).import(listOf(PickedModFile("eratw-eq.ZIP", zipWithSurface(ModApi.VERSION)))),
        )

        assertNull("等于本机不该报", detail.engineTooOld)
        assertNull("提示行整行不出", detail.engineTooOldLine(ModImportCopy.ZH))
        assertTrue("告警区保持空", detail.parseWarnings.isEmpty())
    }

    @Test
    fun `compiledSurface低于本机——不提示`() {
        val tmp = Files.createTempDirectory("eratw-enginetooold").toFile()
        val low = if (ModApi.VERSION > 0) ModApi.VERSION - 1 else 0
        val detail = imported(
            controllerIn(tmp).import(listOf(PickedModFile("eratw-low.ZIP", zipWithSurface(low)))),
        )

        assertNull("低于本机不该报", detail.engineTooOld)
        assertNull("提示行整行不出", detail.engineTooOldLine(ModImportCopy.ZH))
    }

    @Test
    fun `未声明compiledSurface的老包——静默不报`() {
        val tmp = Files.createTempDirectory("eratw-enginetooold").toFile()
        val oldStyle = packModLikeZip(
            META_ENTRY to metaJson(version = "2.0"),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
        )
        assertNull("老包没有编译期面", packMetaOf(listOf(oldStyle)).compiledSurface)
        val detail = imported(controllerIn(tmp).import(listOf(PickedModFile("eratw-old.ZIP", oldStyle))))

        assertNull("未声明不该报", detail.engineTooOld)
        assertNull("提示行整行不出", detail.engineTooOldLine(ModImportCopy.ZH))
        assertTrue("告警区保持空", detail.parseWarnings.isEmpty())
    }

    // ------------------------------------------------------------------
    // 3. 判据同口径：engineSurfaceGap 逐值等于 ModDexGate.verdict.isAllowed 的否定
    // ------------------------------------------------------------------

    @Test
    fun `判据与ModDexGate-verdict逐值同口径——不重算ABI规则`() {
        val surfaces = listOf<Int?>(null, 0, ModApi.VERSION, ModApi.VERSION + 1)
        for (surface in surfaces) {
            val meta = metaWithSurface(surface)
            val allowed = ModDexGate.verdict(meta, ModApi.VERSION).isAllowed
            val gap = meta.engineSurfaceGap()
            assertEquals("verdict 放行 ⇔ 无缺口（surface=$surface）", allowed, gap == null)
            if (gap != null) {
                assertEquals("缺口要求 = 声明的编译期面", surface, gap.required)
                assertEquals("缺口本机 = ModApi.VERSION", ModApi.VERSION, gap.current)
            }
        }
        assertNull("本机引擎被抬高到包要求之上时放行", metaWithSurface(future).engineSurfaceGap(future))
    }

    // ------------------------------------------------------------------
    // 4. 三语措辞：都拼得出、都点名 N 与 M、都不暴露内部词
    // ------------------------------------------------------------------

    @Test
    fun `三档提示行——都点名要求与本机版本且不暴露内部词`() {
        val detail = ImportedPatchDetail(
            patchFileName = "A.patch",
            functionCount = 1,
            instructionCount = 2,
            entryFunction = "B_HELLO",
            sourceFileNames = listOf("A.ERB"),
            parseWarnings = emptyList(),
            engineTooOld = EngineSurfaceGap(required = future, current = ModApi.VERSION),
        )
        val internalNames = listOf(
            "compiledSurface", "compiled", "ABI", "abi", "apiVersion", "ModApi",
            "PackMeta", "surface", "VERSION", "manifest", "eratw-pack.json", "{0}", "{1}", "null",
        )
        for (copy in listOf(ModImportCopy.ZH, ModImportCopy.EN, ModImportCopy.JA)) {
            val line = requireNotNull(detail.engineTooOldLine(copy)) { "${copy.lang} 档应拼得出" }
            assertTrue("${copy.lang} 要求版本：$line", line.contains("$future"))
            assertTrue("${copy.lang} 本机版本：$line", line.contains("${ModApi.VERSION}"))
            for (name in internalNames) {
                assertFalse("${copy.lang} 档不许出现内部名 [$name]：$line", line.contains(name))
            }
        }
    }

    // ------------------------------------------------------------------
    // 5. 成败与账本：未来面照常导入成功，提示不串进其他告警通道
    // ------------------------------------------------------------------

    @Test
    fun `未来面不改导入成败且提示不串表`() {
        val tmp = Files.createTempDirectory("eratw-enginetooold").toFile()
        val detail = imported(
            controllerIn(tmp).import(listOf(PickedModFile("eratw-new.ZIP", zipWithSurface(future)))),
        )

        assertEquals("导入照常成功", ImportVersionChange.FirstInstall, detail.versionChange)
        requireNotNull(detail.engineTooOldLine(ModImportCopy.ZH)) { "未来面必须出提示" }
        assertTrue("包元数据告警通道保持独立", detail.packMetaWarnings.isEmpty())
        assertNull("引擎 API 下限告警（min-api）保持独立", detail.engineApiWarning)
        assertTrue("资源面告警通道保持独立", detail.resourceWarnings.isEmpty())
        assertTrue("解析告警通道保持独立", detail.parseWarnings.isEmpty())
    }

    // ------------------------------------------------------------------
    // 夹具（与 ModImportEngineApiHintTest 同款：自建 zip + 迷你基线）
    // ------------------------------------------------------------------

    private fun zipWithSurface(compiledSurface: Int): ByteArray = packModLikeZip(
        META_ENTRY to metaJson(version = "2.0", compiledSurface = compiledSurface),
        "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
    )

    /** 与 pack-mod 同款的固定键序 JSON。 */
    private fun metaJson(
        version: String,
        packageId: String = MODULE_PACKAGE_ID,
        compiledSurface: Int? = null,
    ): ByteArray {
        val surfaceKey = if (compiledSurface == null) "" else ",\"compiledSurface\":$compiledSurface"
        return "{\"schema\":1,\"packageId\":\"$packageId\",\"version\":\"$version\"$surfaceKey}"
            .toByteArray(Charsets.UTF_8)
    }

    /** 直接构造的元数据（判据同口径用例；绕开 zip 只读组合本身）。 */
    private fun metaWithSurface(compiledSurface: Int?): PackMeta = PackMeta(
        packageId = MODULE_PACKAGE_ID,
        declaredVersion = "2.0",
        version = "2.0",
        ignored = null,
        evidence = PackMetaEvidence(compiledSurface = compiledSurface),
    )

    private val fixedTimeMillis = 315532800000L

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

    private companion object {
        private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val MODULE_PACKAGE_ID = "eratw.enginetooold"
        private const val META_ENTRY = "eratw-pack.json"
        private const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"
    }
}
