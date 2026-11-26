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
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 包级元数据（`eratw-pack.json`）：pack-mod 写、导入端读 ⇒ 「版本变化读数」的真链路用例。
 *
 * ## 覆盖的语义
 *
 * 1. 有元数据：作者版本号落进产物 manifest，重导 v1 → v2 的成功页读数从「重装 v1」变
 *    「版本 v1 → v2：设置与数据保留」；
 * 2. 无元数据（老包）：回落 `"1"`，读数与改造前逐字一致；
 * 3. 坏档面：坏 JSON / schema 不符 / 版本形态非法（空 / 超长 / 控制字符）/ 条目超限 /
 *    packageId 与包内资源前缀对不上 ⇒ 一律回落 `"1"`，导入**不失败、不崩**；
 * 4. 身份口径：安装身份仍是内容 hash 派生的 `kojo-<hash>`，元数据里的 packageId 不参与，
 *    也不因它被静默改写；
 * 5. 重编链：重编（`compileToModule` 不带 version）按盘上旧 manifest 保留版本，不做假降级；
 * 6. 可选 min-api（`pack-mod --min-api`）：导入端**只搬运**——采信值原样写进产物 manifest 的
 *    `/6` `min-api` 段；缺失 / 不可信 ⇒ 0（不写段，老包字节逐位不变）；高于当前引擎也不在导入
 *    期拦（闸在装配期 `ModChainAssembly`）；重编按盘上旧 manifest 保留而不是抹掉。
 *
 * ## 为什么 zip 是自建的
 *
 * `tools/modgen` 的 `PackMod`/`PackModZip` 是 `internal`，跨 Gradle 模块对 `:app` 测试不可见
 * （详见 `ModImportPackModEndToEndTest` 的类 KDoc 与其中的 TODO(verify)）。这里按
 * `PackMod.kt:12-32` + `PackModMeta.kt` 的同合同布局自建 zip；条目内容用与 pack-mod 相同的
 * 固定键序 JSON（`{"schema":1,"packageId":"…","version":"…"}`），并另用 modgen 侧用例
 * 钉住「pack-mod 两次打包字节相同」，两侧互为契约。
 */
class ModImportPackMetaEndToEndTest {

    // ------------------------------------------------------------------
    // 1. 真链路：v1 → v2 的成功页读数
    // ------------------------------------------------------------------

    @Test
    fun `元数据v1到v2——重导后成功页读数是版本变化而非重装`() {
        val tmp = Files.createTempDirectory("eratw-packmeta").toFile()
        val controller = controllerIn(tmp)

        val first = packMetaZip(version = "1.0", source = PATCH_SOURCE)
        val firstDetail = imported(controller.import(listOf(PickedModFile("eratw-demo_v1.ZIP", first))))
        assertEquals(
            "首次安装",
            ImportVersionChange.FirstInstall,
            firstDetail.versionChange,
        )

        val second = packMetaZip(version = "2.0", source = PATCH_SOURCE)
        val secondDetail = imported(controller.import(listOf(PickedModFile("eratw-demo_v2.ZIP", second))))

        assertEquals(
            "重导读到版本变化",
            ImportVersionChange.Changed("1.0", "2.0"),
            secondDetail.versionChange,
        )
        assertEquals(
            "成功页读数",
            "版本 v1.0 → v2.0：设置与数据保留",
            secondDetail.versionChangeSummaryLine(ModImportCopy.ZH),
        )
    }

    @Test
    fun `元数据同版本重导——成功页读数仍是重装v1`() {
        val tmp = Files.createTempDirectory("eratw-packmeta").toFile()
        val controller = controllerIn(tmp)
        val zip = packMetaZip(version = "1", source = PATCH_SOURCE)
        imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip))))
        val detail = imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip))))

        assertEquals(ImportVersionChange.Reinstall("1"), detail.versionChange)
        assertEquals("重装 v1：设置与数据保留", detail.versionChangeSummaryLine(ModImportCopy.ZH))
    }

    // ------------------------------------------------------------------
    // 2. 老包：没有元数据条目 ⇒ 回落 "1"，读数与改造前一致
    // ------------------------------------------------------------------

    @Test
    fun `没有元数据条目——回落version1且读数与改造前一致`() {
        val tmp = Files.createTempDirectory("eratw-packmeta").toFile()
        val controller = controllerIn(tmp)
        val oldStyle = packModLikeZip("main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8))
        assertEquals(PackMetaIgnore.MISSING, packMetaOf(listOf(oldStyle)).ignored)

        imported(controller.import(listOf(PickedModFile("eratw-old.ZIP", oldStyle))))
        val detail = imported(controller.import(listOf(PickedModFile("eratw-old.ZIP", oldStyle))))

        assertEquals("老包读数逐字不变", ImportVersionChange.Reinstall("1"), detail.versionChange)
        assertEquals("重装 v1：设置与数据保留", detail.versionChangeSummaryLine(ModImportCopy.ZH))
    }

    // ------------------------------------------------------------------
    // 3. 坏档面：一律回落 "1"，导入不失败
    // ------------------------------------------------------------------

    @Test
    fun `坏JSON元数据——回落version1且导入成功`() {
        val tmp = Files.createTempDirectory("eratw-packmeta").toFile()
        val controller = controllerIn(tmp)
        val broken = packModLikeZip(
            META_ENTRY to "{\"schema\":1,\"packageId\":".toByteArray(Charsets.UTF_8),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
        )
        val meta = packMetaOf(listOf(broken))
        assertEquals(PackMetaIgnore.MALFORMED, meta.ignored)
        assertEquals("1", meta.version)
        assertNull(meta.declaredVersion)
        imported(controller.import(listOf(PickedModFile("eratw-broken.ZIP", broken))))
    }

    @Test
    fun `schema不认识——整份元数据回落不猜未来格式`() {
        val payload = "{\"schema\":2,\"packageId\":\"$MODULE_PACKAGE_ID\",\"version\":\"9.9\"}"
            .toByteArray(Charsets.UTF_8)
        val meta = parsePackMeta(payload)

        assertEquals(PackMetaIgnore.SCHEMA, meta.ignored)
        assertEquals("1", meta.version)
        assertFalse("未采信", meta.trusted)
    }

    @Test
    fun `版本形态非法——空串与超长回落VERSION`() {
        listOf("", "v".repeat(33)).forEach { bad ->
            val meta = parsePackMeta(metaJson(version = bad))
            assertEquals("非法版本应回落：${bad.length}", PackMetaIgnore.VERSION, meta.ignored)
            assertEquals("1", meta.version)
            assertNull("原文不进读数", meta.declaredVersion)
        }
    }

    @Test
    fun `版本含控制字符——JSON转义后仍被形态闸拦下_裸控制字符按坏档`() {
        // JSON 转义（\u0001 六个字符）由解析器还原成真控制字符 ⇒ 形态闸拦下。
        val escaped = "{\"schema\":1,\"packageId\":\"$MODULE_PACKAGE_ID\",\"version\":\"2.0\\u0001\"}"
            .toByteArray(Charsets.UTF_8)
        val gated = parsePackMeta(escaped)
        assertEquals("转义后的控制字符也必须回落", PackMetaIgnore.VERSION, gated.ignored)
        assertEquals("1", gated.version)

        // 裸控制字符进 JSON 字符串是语法错（MarketJson 直接拒）⇒ 按坏档回落，同样不崩。
        val raw = metaJson(version = "2.0\u0001")
        val malformed = parsePackMeta(raw)
        assertEquals(PackMetaIgnore.MALFORMED, malformed.ignored)
        assertEquals("1", malformed.version)
    }

    @Test
    fun `元数据条目超限——按没有元数据处理且不读进堆`() {
        val oversized = ByteArray(PACK_META_MAX_ENTRY_BYTES + 1) { 'x'.code.toByte() }
        val meta = packMetaOf(
            listOf(
                packModLikeZip(
                    META_ENTRY to oversized,
                    "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
                ),
            ),
        )
        assertEquals(PackMetaIgnore.MISSING, meta.ignored)
        assertEquals("1", meta.version)
    }

    // ------------------------------------------------------------------
    // 4. packageId 一致性口径 + 身份口径
    // ------------------------------------------------------------------

    @Test
    fun `元数据packageId与包内资源前缀不符——不采信版本但导入照旧`() {
        val tmp = Files.createTempDirectory("eratw-packmeta").toFile()
        val controller = controllerIn(tmp)
        val zip = packModLikeZip(
            META_ENTRY to metaJson(version = "7.7", packageId = "eratw.someoneelse"),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "$MODULE_PACKAGE_ID.assets/img/logo.png" to PNG,
        )
        val meta = packMetaOf(listOf(zip))
        assertEquals(PackMetaIgnore.PACKAGE_ID_PREFIX, meta.ignored)
        assertEquals("1", meta.version)
        assertEquals("自述包名仍留作取证", "eratw.someoneelse", meta.packageId)

        val detail = imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip))))
        assertEquals("不采信版本", ImportVersionChange.FirstInstall, detail.versionChange)
    }

    @Test
    fun `元数据packageId与资源前缀一致——采信版本且安装身份仍是内容hash`() {
        val tmp = Files.createTempDirectory("eratw-packmeta").toFile()
        val controller = controllerIn(tmp)
        val zip = packModLikeZip(
            META_ENTRY to metaJson(version = "3.1", packageId = MODULE_PACKAGE_ID),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "$MODULE_PACKAGE_ID.assets/img/logo.png" to PNG,
        )
        val meta = packMetaOf(listOf(zip))
        assertEquals("self-reported id", MODULE_PACKAGE_ID, meta.packageId)
        assertEquals("3.1", meta.version)
        assertTrue("采信", meta.trusted)

        val detail = imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip))))
        val identity = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        assertEquals("安装身份 = 内容 hash（与元数据 packageId 无关）", controller.packageIdFor(listOf("main.ERB")), identity)
        assertNotEquals(MODULE_PACKAGE_ID, identity)
        assertTrue(identity.startsWith("kojo-"))
    }

    // ------------------------------------------------------------------
    // 5. 重编链：不带 version 的 compileToModule 不从 v2 降回 v1
    // ------------------------------------------------------------------

    @Test
    fun `重编不传version——按盘上旧manifest保留版本不做假降级`() {
        val tmp = Files.createTempDirectory("eratw-packmeta").toFile()
        val controller = controllerIn(tmp)
        val zip = packMetaZip(version = "2.0", source = PATCH_SOURCE)
        imported(controller.import(listOf(PickedModFile("eratw-demo_v2.ZIP", zip))))

        // 重编路径：源从包内 unpack 出来（同 diskName），不传 version。
        val files = unpacked(zip)
        val rebuilt = controller.compileToModule(files, ModImportCopy.ZH)
        assertEquals("重编保留盘上版本", "2.0", ManifestCodec.decode(rebuilt.manifestBytes).version)
    }

    // ------------------------------------------------------------------
    // 6. CH：packageId 与包内资源前缀自相矛盾 ⇒ 玩家可见三语 WARN
    //    （其余未采信原因保持静默：作者侧问题、玩家无可行动）
    // ------------------------------------------------------------------

    @Test
    fun `包名与资源前缀不符——成功页恰一条WARN点名声明与资源目录`() {
        val detail = importedZip(packageIdMismatchZip())

        assertEquals("恰一条", 1, detail.packMetaWarnings.size)
        val warning = detail.packMetaWarnings.single()
        assertTrue("点名声明的包名", warning.contains(DECLARED_PACKAGE_ID))
        assertTrue("点名包内资源目录", warning.contains("$MODULE_PACKAGE_ID.assets/"))
        assertTrue("点名回落读数", warning.endsWith("1。") || warning.contains(" 1"))
        assertFalse("不暴露内部枚举名", warning.contains("PACKAGE_ID_PREFIX"))
        assertEquals("上屏恰一次（parseWarnings 合并口）", 1, detail.parseWarnings.count { it == warning })
    }

    @Test
    fun `包名与资源目录一致——采信版本且不出该告警`() {
        val tmp = Files.createTempDirectory("eratw-packmeta").toFile()
        val controller = controllerIn(tmp)
        val zip = packModLikeZip(
            META_ENTRY to metaJson(version = "3.1", packageId = MODULE_PACKAGE_ID),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "$MODULE_PACKAGE_ID.assets/img/logo.png" to PNG,
        )
        val detail = imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip))))
        assertTrue("一致 ⇒ 不告警", detail.packMetaWarnings.isEmpty())
        // 同包重导读到 3.1 ⇒ 版本被采信（不是回落的 "1"）。
        assertEquals(
            ImportVersionChange.Reinstall("3.1"),
            imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip)))).versionChange,
        )
    }

    @Test
    fun `包内无资源条目——无从自证不出告警且版本被采信`() {
        val tmp = Files.createTempDirectory("eratw-packmeta").toFile()
        val controller = controllerIn(tmp)
        val zip = packModLikeZip(
            META_ENTRY to metaJson(version = "4.2", packageId = DECLARED_PACKAGE_ID),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
        )
        assertTrue("无从自证 ⇒ 采信", packMetaOf(listOf(zip)).trusted)
        val detail = imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip))))
        assertTrue("无从自证 ⇒ 不告警", detail.packMetaWarnings.isEmpty())
        assertEquals(
            ImportVersionChange.Reinstall("4.2"),
            imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip)))).versionChange,
        )
    }

    @Test
    fun `其它未采信原因——一律静默不打扰玩家`() {
        val broken = packModLikeZip(
            META_ENTRY to "{\"schema\":1,\"packageId\":".toByteArray(Charsets.UTF_8),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
        )
        // 四个原因：缺失 / 坏档 / schema 不认识 / 版本形态非法 ⇒ 纯函数一律 null。
        val schema2Meta =
            "{\"schema\":2,\"packageId\":\"$DECLARED_PACKAGE_ID\",\"version\":\"9.9\"}".toByteArray(Charsets.UTF_8)
        val reasons = listOf(
            PackMetaIgnore.MISSING to packModLikeZip("main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8)),
            PackMetaIgnore.MALFORMED to broken,
            PackMetaIgnore.SCHEMA to packModLikeZip(
                META_ENTRY to schema2Meta,
                "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            ),
            PackMetaIgnore.VERSION to packModLikeZip(
                META_ENTRY to metaJson(version = "", packageId = DECLARED_PACKAGE_ID),
                "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            ),
        )
        for ((reason, zip) in reasons) {
            val meta = packMetaOf(listOf(zip))
            assertEquals("原因不变", reason, meta.ignored)
            assertNull("$reason 不配玩家文案", meta.packageIdPrefixWarning(ModImportCopy.ZH))
        }
        // 坏 JSON 的整链：导入成功、无告警。
        val tmp = Files.createTempDirectory("eratw-packmeta").toFile()
        val detail = imported(controllerIn(tmp).import(listOf(PickedModFile("eratw-broken.ZIP", broken))))
        assertTrue("坏档静默", detail.packMetaWarnings.isEmpty())
    }

    @Test
    fun `三语WARN都取得到且不含内部枚举名`() {
        val meta = packMetaOf(listOf(packageIdMismatchZip()))
        assertEquals(PackMetaIgnore.PACKAGE_ID_PREFIX, meta.ignored)
        val pair = listOf(ModImportCopy.ZH, ModImportCopy.EN, ModImportCopy.JA).map { copy ->
            meta.packageIdPrefixWarning(copy) ?: throw AssertionError("${copy.lang} 取不到 WARN")
        }
        assertEquals("三语不同文", 3, pair.toSet().size)
        val slots = Regex("[{](\\d+)[}]")
        for ((copy, warning) in listOf(ModImportCopy.ZH, ModImportCopy.EN, ModImportCopy.JA).zip(pair)) {
            assertTrue("${copy.lang} 点名声明包名", warning.contains(DECLARED_PACKAGE_ID))
            assertTrue("${copy.lang} 点名资源目录", warning.contains("$MODULE_PACKAGE_ID.assets/"))
            assertFalse("${copy.lang} 不暴露内部枚举名", warning.contains("PACKAGE_ID_PREFIX"))
            assertFalse("${copy.lang} 不暴露内部类名", warning.contains("PACK_META"))
            assertFalse("${copy.lang} 槽位已灌满", slots.containsMatchIn(warning))
        }
    }

    @Test
    fun `告警顺序稳定——包元数据WARN在既有资源告警之前且互不串表`() {
        val zip = packModLikeZip(
            META_ENTRY to metaJson(version = "7.7", packageId = DECLARED_PACKAGE_ID),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "$MODULE_PACKAGE_ID.assets/img/logo.png" to PNG,
            ".assets/bad.png" to PNG,
        )
        val detail = importedZip(zip)

        val packMetaWarning = detail.packMetaWarnings.single()
        val resourceWarning = detail.resourceWarnings.firstOrNull { it.contains(".assets/bad.png") }
            ?: throw AssertionError("应有资源条目名非法告警，实为 ${detail.resourceWarnings}")
        assertTrue("资源告警不混进包元数据表", detail.resourceWarnings.none { it == packMetaWarning })
        val packMetaIndex = detail.parseWarnings.indexOf(packMetaWarning)
        val resourceIndex = detail.parseWarnings.indexOf(resourceWarning)
        assertTrue(
            "包元数据 WARN 应先于资源告警（实际 $packMetaIndex vs $resourceIndex）",
            packMetaIndex >= 0 && resourceIndex > packMetaIndex,
        )
    }

    @Test
    fun `不一致时导入仍成功且身份仍是内容hash`() {
        val detail = importedZip(packageIdMismatchZip())
        val identity = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        assertTrue("内容身份", identity.startsWith("kojo-"))
        assertNotEquals("不被元数据包名改写", DECLARED_PACKAGE_ID, identity)
        assertEquals("资源面照旧落盘", 1, detail.resourceEntryCount)
    }

    @Test
    fun `不一致时版本读数回落1——同包重导读数仍是重装v1`() {
        val tmp = Files.createTempDirectory("eratw-packmeta").toFile()
        val controller = controllerIn(tmp)
        val zip = packageIdMismatchZip()
        val first = imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip))))
        assertEquals("首次安装", ImportVersionChange.FirstInstall, first.versionChange)

        val second = imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip))))
        assertEquals("声明 7.7 不被采信、读数回落 1", ImportVersionChange.Reinstall("1"), second.versionChange)
        assertEquals("重装 v1：设置与数据保留", second.versionChangeSummaryLine(ModImportCopy.ZH))
        assertEquals("重导仍有该 WARN", 1, second.packMetaWarnings.size)
    }

    // ------------------------------------------------------------------
    // 7. 可选 min-api：导入端只搬运（写进产物 manifest 的 `/6` 段），闸在装配期
    // ------------------------------------------------------------------

    @Test
    fun `元数据minApi——导入产物manifest带min-api段`() {
        val tmp = Files.createTempDirectory("eratw-packminapi").toFile()
        val controller = controllerIn(tmp)
        val zip = packModLikeZip(
            META_ENTRY to metaJson(version = "3.1", minApi = 15),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
        )
        assertEquals("采信作者声明的引擎下限", 15, packMetaOf(listOf(zip)).minApi)

        imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip))))

        val installed = installedAfter(controller, tmp)
        assertEquals("原样搬运到产物 manifest", 15, installed.minApi)
        assertEquals("min-api 不参与版本判定", "3.1", installed.version)
    }

    @Test
    fun `minApi高于当前引擎——导入照常落盘不在此判闸`() {
        val tmp = Files.createTempDirectory("eratw-packminapi").toFile()
        val controller = controllerIn(tmp)
        val future = ModApi.VERSION + 1
        val zip = packModLikeZip(
            META_ENTRY to metaJson(version = "2.0", minApi = future),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
        )

        val detail = imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip))))

        assertEquals("导入成功页照常出", ImportVersionChange.FirstInstall, detail.versionChange)
        assertEquals("高于当前引擎也照落盘（拒载在装配期）", future, installedAfter(controller, tmp).minApi)
    }

    @Test
    fun `重编不传minApi——按盘上旧manifest保留引擎下限`() {
        val tmp = Files.createTempDirectory("eratw-packminapi").toFile()
        val controller = controllerIn(tmp)
        val zip = packModLikeZip(
            META_ENTRY to metaJson(version = "2.0", minApi = 15),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
        )
        imported(controller.import(listOf(PickedModFile("eratw-demo.ZIP", zip))))

        // 重编路径：源从包内 unpack 出来（同 diskName），两个作者声明都不传。
        val rebuilt = controller.compileToModule(unpacked(zip), ModImportCopy.ZH)
        val descriptor = ManifestCodec.decode(rebuilt.manifestBytes)

        assertEquals("重编不该抹掉已声明的下限", 15, descriptor.minApi)
        assertEquals("版本同样保留", "2.0", descriptor.version)
    }

    @Test
    fun `minApi形态非法——整份元数据不采信但导入照常`() {
        val bad = listOf(
            "{\"schema\":1,\"packageId\":\"$MODULE_PACKAGE_ID\",\"version\":\"9.9\",\"minApi\":-3}",
            "{\"schema\":1,\"packageId\":\"$MODULE_PACKAGE_ID\",\"version\":\"9.9\",\"minApi\":1.5}",
            "{\"schema\":1,\"packageId\":\"$MODULE_PACKAGE_ID\",\"version\":\"9.9\",\"minApi\":\"16\"}",
        )
        for (payload in bad) {
            val meta = parsePackMeta(payload.toByteArray(Charsets.UTF_8))
            assertEquals("minApi 读不准 ⇒ 整份不采信：$payload", PackMetaIgnore.MIN_API, meta.ignored)
            assertEquals("版本一并回落", "1", meta.version)
            assertEquals("不设闸", 0, meta.minApi)
        }

        // 导入链不因此失败：坏 min-api 是作者侧问题，玩家无可行动，按老包行为安装。
        val tmp = Files.createTempDirectory("eratw-packminapi").toFile()
        val fallback = packModLikeZip(
            META_ENTRY to bad.first().toByteArray(Charsets.UTF_8),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
        )
        imported(controllerIn(tmp).import(listOf(PickedModFile("eratw-demo.ZIP", fallback))))
        val installed = installedAfter(controllerIn(tmp), tmp)
        assertEquals("不采信 ⇒ 不设闸", 0, installed.minApi)
        assertEquals("版本回落", "1", installed.version)
    }

    @Test
    fun `元数据不声明minApi——按0处理且老包产物不设闸`() {
        val tmp = Files.createTempDirectory("eratw-packminapi").toFile()
        val controller = controllerIn(tmp)
        val oldStyle = packModLikeZip("main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8))
        val meta = packMetaOf(listOf(oldStyle))
        assertEquals("缺元数据 = 老包常态，不采信但不动声色", PackMetaIgnore.MISSING, meta.ignored)
        assertEquals("缺失 = 不设闸", 0, meta.minApi)

        // 同一份源：直接调编码链与走导入链都必须落在 min-api=0 / 版本回落 "1"。
        val direct = controller.compileToModule(unpacked(oldStyle), ModImportCopy.ZH)
        val directDescriptor = ManifestCodec.decode(direct.manifestBytes)
        assertEquals(0, directDescriptor.minApi)
        assertEquals("1", directDescriptor.version)

        imported(controller.import(listOf(PickedModFile("eratw-old.ZIP", oldStyle))))
        val installed = installedAfter(controller, tmp)
        assertEquals("老包行为逐位不变", 0, installed.minApi)
        assertEquals("1", installed.version)
    }

    @Test
    fun `元数据包名与资源前缀不符——minApi一并归0不拿它拦装配`() {
        val zip = packModLikeZip(
            META_ENTRY to metaJson(version = "7.7", packageId = DECLARED_PACKAGE_ID, minApi = 15),
            "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "$MODULE_PACKAGE_ID.assets/img/logo.png" to PNG,
        )
        val meta = packMetaOf(listOf(zip))
        assertEquals(PackMetaIgnore.PACKAGE_ID_PREFIX, meta.ignored)
        assertEquals("整份不可信 ⇒ 不设闸", 0, meta.minApi)
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 自述包名与包内资源前缀自相矛盾的样例包（声明别人、资源目录是本包）。 */
    private fun packageIdMismatchZip(): ByteArray = packModLikeZip(
        META_ENTRY to metaJson(version = "7.7", packageId = DECLARED_PACKAGE_ID),
        "main.ERB" to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
        "$MODULE_PACKAGE_ID.assets/img/logo.png" to PNG,
    )

    private fun importedZip(zip: ByteArray): ImportedPatchDetail {
        val tmp = Files.createTempDirectory("eratw-packmeta").toFile()
        return imported(controllerIn(tmp).import(listOf(PickedModFile("eratw-demo.ZIP", zip))))
    }

    private fun packMetaZip(version: String, source: String): ByteArray = packModLikeZip(
        META_ENTRY to metaJson(version = version),
        "main.ERB" to source.toByteArray(Charsets.UTF_8),
    )

    /** 与 pack-mod 同款的固定键序 JSON（键序/转义口径见 `PackModMeta.json`）。 */
    private fun metaJson(
        version: String,
        packageId: String = MODULE_PACKAGE_ID,
        minApi: Int? = null,
    ): ByteArray {
        // 可选 minApi 键只在该值为正时由 pack-mod 写出（0/缺失 ⇒ 不写键，老包字节逐字不变）。
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

    private fun unpacked(zip: ByteArray): List<PickedModFile> =
        ZipInputStream(zip.inputStream()).use { zin ->
            val files = ArrayList<PickedModFile>()
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".ERB") || entry.name.endsWith(".ERH")) {
                    files += PickedModFile(entry.name, zin.readBytes(), fromArchive = true)
                }
                entry = zin.nextEntry
            }
            files
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
        private const val MODULE_PACKAGE_ID = "eratw.packmeta"

        /** 元数据里自述的「别人的」包名（与包内资源前缀不一致 ⇒ CH 告警的触发源）。 */
        private const val DECLARED_PACKAGE_ID = "eratw.someoneelse"
        private const val META_ENTRY = "eratw-pack.json"
        private const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    }
}
