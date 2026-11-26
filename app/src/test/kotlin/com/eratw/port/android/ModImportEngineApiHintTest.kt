package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
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
 * 导入成功页的「引擎 API 下限」告警（Lane DC）：作者声明的 min-api 高于本机 [ModApi.VERSION]
 * 时，成功页必须提前说清「这个包要求什么、当前引擎是什么、装上后装配期会被拒载」。
 *
 * ## 钉住的语义
 *
 * 1. 判据 = `PackMeta.minApi > ModApi.VERSION` 且元数据被采信（与装配期 `ModChainAssembly`
 *    整链拒载同一条判据）；高 / 等 / 低 / 0 / 无元数据 / 形态非法 六种边界的出不出行；
 * 2. 上屏措辞三件事齐全（要求版本 N、当前版本 M、装上后会被拒载），且不暴露内部字段名；
 *    三档语言都能拼、槽位灌满无残留；
 * 3. **不改导入成败**：未来下限照常导入成功、照常把 min-api 写进产物 manifest，告警只走
 *    [ImportedPatchDetail.parseWarnings] 同一条通道，且与 `packMetaWarnings` /
 *    `resourceWarnings` 两本账互不串表；
 * 4. 重导同一份包每次都报（不依赖首次安装），不是一次性提示。
 *
 * ## 为什么 zip 是自建的
 *
 * 与 `ModImportPackMetaEndToEndTest` 同因：`tools/modgen` 的 `PackMod` 是 `internal`、跨模块
 * 不可见，这里按同款固定键序 JSON 自建 zip（键序/转义口径见 `PackModMeta.json`）。
 */
class ModImportEngineApiHintTest {

    private val future = ModApi.VERSION + 1

    // ------------------------------------------------------------------
    // 1. 真链路：高于当前引擎 ⇒ 成功页恰一条告警，三件事齐全
    // ------------------------------------------------------------------

    @Test
    fun `minApi高于当前引擎——成功页恰一条告警且三件事齐全`() {
        val tmp = Files.createTempDirectory("eratw-engineapi").toFile()
        val outcome = controllerIn(tmp).import(
            listOf(PickedModFile("eratw-future.ZIP", zipWithMinApi(future))),
        )
        val detail = imported(outcome)

        val warning = requireNotNull(detail.engineApiWarning) { "高于当前引擎必须出告警" }
        assertEquals(
            "告警按 ZH 档点名要求版本与当前版本",
            ModImportCopy.ZH.fill(ModImportCopy.ZH.engineApiGateWarningFormat, future, ModApi.VERSION),
            warning,
        )
        assertTrue("要求版本要上屏：$warning", warning.contains("v$future"))
        assertTrue("当前版本要上屏：$warning", warning.contains("v${ModApi.VERSION}"))
        assertTrue("要被拒载这件事要上屏：$warning", warning.contains("拒载"))
        assertEquals(
            "并入 parseWarnings 恰一次",
            1,
            detail.parseWarnings.count { it == warning },
        )
    }

    // ------------------------------------------------------------------
    // 2. 边界：等于 / 低于 / 不声明 / 无元数据 / 形态非法 ⇒ 一条都不出
    // ------------------------------------------------------------------

    @Test
    fun `minApi等于当前引擎——一条都不出`() {
        val tmp = Files.createTempDirectory("eratw-engineapi").toFile()
        val outcome = controllerIn(tmp).import(
            listOf(PickedModFile("eratw-eq.ZIP", zipWithMinApi(ModApi.VERSION))),
        )
        val detail = imported(outcome)

        assertNull("等于当前引擎不该报", detail.engineApiWarning)
        assertTrue("告警区保持空", detail.parseWarnings.isEmpty())
    }

    @Test
    fun `minApi低于当前引擎——一条都不出`() {
        val tmp = Files.createTempDirectory("eratw-engineapi").toFile()
        val detail = imported(controllerIn(tmp).import(listOf(PickedModFile("eratw-low.ZIP", zipWithMinApi(1)))))

        assertNull("低于当前引擎不该报", detail.engineApiWarning)
        assertTrue("告警区保持空", detail.parseWarnings.isEmpty())
    }

    @Test
    fun `元数据不声明minApi——按0处理且一条都不出`() {
        val tmp = Files.createTempDirectory("eratw-engineapi").toFile()
        val zip = packModLikeZip(
            META_ENTRY to metaJson(version = "2.0"),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
        )
        assertEquals("缺失 = 不设闸", 0, packMetaOf(listOf(zip)).minApi)
        val detail = imported(controllerIn(tmp).import(listOf(PickedModFile("eratw-nomin.ZIP", zip))))

        assertNull("不声明就不报", detail.engineApiWarning)
        assertTrue("告警区保持空", detail.parseWarnings.isEmpty())
    }

    @Test
    fun `没有元数据的老包——静默不报`() {
        val tmp = Files.createTempDirectory("eratw-engineapi").toFile()
        val oldStyle = packModLikeZip("main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8))
        assertEquals(PackMetaIgnore.MISSING, packMetaOf(listOf(oldStyle)).ignored)
        val detail = imported(controllerIn(tmp).import(listOf(PickedModFile("eratw-old.ZIP", oldStyle))))

        assertNull("老包一无所知，不报", detail.engineApiWarning)
        assertTrue("告警区保持空", detail.parseWarnings.isEmpty())
    }

    @Test
    fun `minApi形态非法——整份元数据不采信且静默`() {
        val bad = listOf(
            "{\"schema\":1,\"packageId\":\"$MODULE_PACKAGE_ID\",\"version\":\"9.9\",\"minApi\":-3}",
            "{\"schema\":1,\"packageId\":\"$MODULE_PACKAGE_ID\",\"version\":\"9.9\",\"minApi\":1.5}",
            "{\"schema\":1,\"packageId\":\"$MODULE_PACKAGE_ID\",\"version\":\"9.9\",\"minApi\":\"16\"}",
        )
        for (payload in bad) {
            val meta = parsePackMeta(payload.toByteArray(Charsets.UTF_8))
            assertEquals("读不准 ⇒ 整份不采信：$payload", PackMetaIgnore.MIN_API, meta.ignored)
            assertNull("未采信就不报：$payload", meta.engineApiGateWarning(ModImportCopy.ZH))
        }
    }

    // ------------------------------------------------------------------
    // 3. 三语措辞：都拼得出、不含内部名、槽位灌满
    // ------------------------------------------------------------------

    @Test
    fun `三档告警措辞——都点名N与M且不暴露内部字段名`() {
        val trusted = PackMeta(
            packageId = MODULE_PACKAGE_ID,
            declaredVersion = "2.0",
            version = "2.0",
            ignored = null,
            minApi = future,
        )
        val internalNames = listOf(
            "minApi", "min-api", "ModApi", "PackMeta", "VERSION", "manifest",
            "eratw-pack.json", "{0}", "{1}", "null",
        )
        for (copy in listOf(ModImportCopy.ZH, ModImportCopy.EN, ModImportCopy.JA)) {
            val warning = requireNotNull(trusted.engineApiGateWarning(copy)) { "${copy.lang} 档应拼得出" }
            assertTrue("${copy.lang} 要求版本：$warning", warning.contains("$future"))
            assertTrue("${copy.lang} 当前版本：$warning", warning.contains("${ModApi.VERSION}"))
            for (name in internalNames) {
                assertFalse("${copy.lang} 档不许出现内部名 [$name]：$warning", warning.contains(name))
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. 成败解耦：未来下限照常落盘，告警不串进另两本账
    // ------------------------------------------------------------------

    @Test
    fun `未来下限不改导入成败——照常落盘且告警不串表`() {
        val tmp = Files.createTempDirectory("eratw-engineapi").toFile()
        val controller = controllerIn(tmp)
        val detail = imported(controller.import(listOf(PickedModFile("eratw-future.ZIP", zipWithMinApi(future)))))

        assertEquals("导入照常成功", ImportVersionChange.FirstInstall, detail.versionChange)
        val installed = installedAfter(controller, tmp)
        assertEquals("min-api 原样搬运到产物 manifest（拒载在装配期）", future, installed.minApi)

        val warning = requireNotNull(detail.engineApiWarning) { "未来下限必须出告警" }
        assertTrue("包元数据告警通道保持独立", detail.packMetaWarnings.isEmpty())
        assertTrue("资源面告警通道保持独立", detail.resourceWarnings.isEmpty())
        assertEquals("并入 parseWarnings 恰一次", 1, detail.parseWarnings.count { it == warning })
    }

    // ------------------------------------------------------------------
    // 5. 幂等：重导同一份未来下限包，每次都报
    // ------------------------------------------------------------------

    @Test
    fun `重导同一份未来下限包——每次成功页都报而不是只报首装`() {
        val tmp = Files.createTempDirectory("eratw-engineapi").toFile()
        val controller = controllerIn(tmp)
        val zip = zipWithMinApi(future)

        val first = imported(controller.import(listOf(PickedModFile("eratw-future.ZIP", zip))))
        val second = imported(controller.import(listOf(PickedModFile("eratw-future.ZIP", zip))))

        val firstWarning = requireNotNull(first.engineApiWarning) { "首装要报" }
        requireNotNull(second.engineApiWarning) { "重导仍要报" }
        assertEquals("两次措辞一致", firstWarning, second.engineApiWarning)
        assertEquals("重导的版本读数仍是重装", ImportVersionChange.Reinstall("2.0"), second.versionChange)
    }

    // ------------------------------------------------------------------
    // 夹具（与 ModImportPackMetaEndToEndTest 同款：自建 zip + 迷你基线）
    // ------------------------------------------------------------------

    private fun zipWithMinApi(minApi: Int): ByteArray = packModLikeZip(
        META_ENTRY to metaJson(version = "2.0", minApi = minApi),
        "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
    )

    /** 与 pack-mod 同款的固定键序 JSON（键序/转义口径见 `PackModMeta.json`）。 */
    private fun metaJson(
        version: String,
        packageId: String = MODULE_PACKAGE_ID,
        minApi: Int? = null,
    ): ByteArray {
        val minApiKey = if (minApi == null) "" else ",\"minApi\":$minApi"
        return "{\"schema\":1,\"packageId\":\"$packageId\",\"version\":\"$version\"$minApiKey}"
            .toByteArray(Charsets.UTF_8)
    }

    /** 导入落盘后从 `mods/rt2` 读回本包 manifest（断言「搬运」是否真的到产物）。 */
    private fun installedAfter(controller: ModImportController, tmp: File): ModuleDescriptor {
        val packageId = controller.packageIdFor(listOf("main.ERB"))
        val rt2 = File(File(tmp, "mods"), ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME)
        return installedManifestOf(rt2, packageId)
            ?: throw AssertionError("导入成功后盘上应有 $packageId 的 manifest")
    }

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
        private const val MODULE_PACKAGE_ID = "eratw.engapi"
        private const val META_ENTRY = "eratw-pack.json"
        private const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"
    }
}
