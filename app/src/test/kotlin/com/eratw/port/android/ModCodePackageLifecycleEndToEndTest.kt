package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码包生命周期端到端：真文件系统临时目录 + 真导入控制器 / 真 store / 真信任及装配链，
 * 不打桩中间层。
 *
 * 覆盖七段（任务书 task-194）：
 * 1. 导入后代码产物按内容寻址落成只读 `.mod`，`.codeid` / `.manifest` / `.erbm` 就位、无临时件残留；
 * 2. 信任落 `<运行期身份>.trusted`，撤销后标记消失且装配结局回落到 `needs-trust`；
 * 3. 总闸开 + 已信任 ⇒ 载体名单含运行期身份并建加载器；总闸关 ⇒ 零扫描（不碰 store、不建工厂）；
 * 4. 卸载删净本包 code/ 与身份/信任/媒体/设置/四件套，且不误伤另一个包的逐字节内容；
 * 5. 孤儿 GC：源失联但产物齐全的完整包按**现状**保留（完整包一律保留，只回收半成品）；
 * 6. 重导同包新内容清掉旧 `.mod`、更新身份侧车、`list` 只剩一份；
 * 7. 代码目录不可读/被占位时各只读面返回显式失败或空集合，不抛异常（fail-closed）。
 *
 * 夹具口径与 [ModImportRealCodeProductEndToEndTest]、[ModCodeCarrierAssemblyEndToEndTest] 同源：
 * 包身份由编译产物派生（`kojo-<8hex>`），先跑一次「只带 `main.ERB`」的导入拿到磁盘包名，
 * 再以该包名作 `eratw-pack.json` 声明身份与内层 `manifest.json` 身份重导引入代码产物——
 * 这是本波「代码包身份一致化」后的统一形态（磁盘身份 == 运行期身份）。
 */
abstract class CodeLifecycleTestBase {
    private val roots = mutableListOf<File>()

    /** 建一个独立游戏根（含 `mods/`）；测试结束统一摘只读再删。 */
    protected fun newGameRoot(label: String): File =
        Files.createTempDirectory("eratw-codelifecycle-$label").toFile().also { roots += it }

    @After
    fun cleanupRoots() {
        roots.forEach(::cleanupTree)
        roots.clear()
    }
}

/** 导入 / 信任 / 装配 / 卸载主链。 */
class ModCodePackageLifecycleEndToEndTest : CodeLifecycleTestBase() {

    @Test
    fun `导入后代码产物按内容寻址落成只读件且身份侧车就位`() {
        val gameRoot = newGameRoot("import")
        val fixture = importLifecyclePack(gameRoot)
        val rt2 = fixture.rt2
        val codeDir = File(rt2, MOD_CODE_DIR_NAME)
        val packageCodeDir = File(codeDir, fixture.packageId)

        assertTrue("包名形态应为 kojo-<8hex>，实际 ${fixture.packageId}", PACKAGE_ID_REGEX.matches(fixture.packageId))
        val artifacts = ModCodeStore(rt2).list(fixture.packageId)
        assertEquals("同包只应留一份产物", 1, artifacts.size)
        val artifact = artifacts.single()
        assertEquals("地址键 = sha256 前 16 位", sha256Hex(fixture.codeMod).take(SHA16_CHARS), artifact.sha256Hex)
        assertEquals("产物文件名 = 地址键 + .mod", artifact.sha256Hex + MOD_CODE_ARTIFACT_EXTENSION, artifact.file.name)
        assertTrue("产物必须是普通文件", artifact.file.isFile)
        assertFalse("产物必须只读", artifact.file.canWrite())
        assertEquals("落盘字节必须是内层 code.mod 原字节", fixture.codeMod.toList(), artifact.file.readBytes().toList())
        assertEquals("包目录里只应有一个产物文件", listOf(artifact.file.name), packageCodeDir.list()?.toList())

        val sidecar = File(rt2, ModCodeIdentity.fileName(fixture.packageId))
        assertTrue(".codeid 侧车必须落盘", sidecar.isFile)
        assertEquals(
            "侧车内容 = 固定头 + 运行期身份 + 换行",
            ModCodeIdentity.HEADER_LINE + "\n" + fixture.packageId + "\n",
            sidecar.readText(Charsets.UTF_8),
        )
        assertEquals(
            "侧面身份解析为 Bound",
            ModCodeIdentity.read(rt2, fixture.packageId),
            CodeIdSidecar.Bound(fixture.packageId),
        )
        assertTrue(".manifest 四件套必须在场", File(rt2, fixture.packageId + ModPackageInventory.MANIFEST_EXTENSION).isFile)
        assertTrue(".erbm 四件套必须在场", File(rt2, fixture.packageId + ModPackageInventory.MODULE_EXTENSION).isFile)
        assertNoTmpResidue(codeDir)
    }

    @Test
    fun `信任使用运行期身份落标记撤销后清除`() {
        val gameRoot = newGameRoot("trust")
        val fixture = importLifecyclePack(gameRoot)
        val rt2 = fixture.rt2
        val marker = trustMarker(rt2, fixture.packageId)

        assertFalse("导入后不得自动信任", marker.exists())
        assertTrue("信任动作应成功", ModTrustStore.trust(rt2, fixture.packageId))
        assertTrue("信任标记必须出现", marker.isFile)
        assertTrue("装载闸应读到已信任", ModDexGate.isTrusted(rt2, fixture.packageId))
        assertEquals("信任清单应含运行期身份", listOf(fixture.packageId), ModTrustStore.trustedPackages(rt2))
        assertTrue("code 包清单应聚合到该包", ModTrustStore.codePackages(File(rt2, MOD_CODE_DIR_NAME)).isNotEmpty())

        assertTrue("撤销信任应成功", ModTrustStore.distrust(rt2, fixture.packageId))
        assertFalse("撤销后标记必须消失", marker.exists())
        assertFalse("装载闸应读到未信任", ModDexGate.isTrusted(rt2, fixture.packageId))
        assertFalse("无产物包不得被信任", ModTrustStore.trust(rt2, UNKNOWN_PACKAGE_ID))
    }

    @Test
    fun `已信任且总闸开时载体名单含运行期身份`() {
        val gameRoot = newGameRoot("assemble")
        val fixture = importLifecyclePack(gameRoot, entrypoint = ENTRYPOINT)
        val rt2 = fixture.rt2
        val factory = CountingFactory()
        val logs = mutableListOf<String>()
        assertTrue(ModTrustStore.trust(rt2, fixture.packageId))

        val collection = providerOf(gameRoot, factory = factory, logSink = logs::add).collect()

        assertEquals("应装配唯一载体", 1, collection.carriers.size)
        assertEquals("载体按运行期身份登记", fixture.packageId, collection.carriers.single().first)
        val record = collection.records.single()
        assertEquals("磁盘身份", fixture.packageId, record.diskPackageId)
        assertEquals("运行期身份取 .codeid 的 Bound", fixture.packageId, record.runtimePackageId)
        assertEquals(CodeCarrierOutcome.LOADED, record.outcome)
        assertEquals("canonical wire 形态", "loaded", record.outcome.wire)
        assertEquals(
            "工厂按运行期身份拿本包唯一产物建 loader",
            listOf(fixture.packageId to fixture.artifactFile),
            factory.created,
        )
        assertTrue("入口类实例必须是 EraMod", collection.carriers.single().second is AssembledLifecycleMod)
        assertTrue("装载闸日志应记 loaded，实际：$logs", logs.any { it.contains("outcome=loaded") })
    }

    @Test
    fun `撤销信任后装配结局回落到needs-trust`() {
        val gameRoot = newGameRoot("needs-trust")
        val fixture = importLifecyclePack(gameRoot, entrypoint = ENTRYPOINT)
        val rt2 = fixture.rt2
        val factory = CountingFactory()
        val provider = providerOf(gameRoot, factory = factory)

        val before = provider.collect()
        assertEquals("未信任不装配", 0, before.carriers.size)
        assertEquals(CodeCarrierOutcome.NEEDS_TRUST, before.records.single().outcome)
        assertEquals("needs-trust", before.records.single().outcome.wire)
        assertTrue("未信任不得建 loader", factory.created.isEmpty())

        assertTrue(ModTrustStore.trust(rt2, fixture.packageId))
        assertEquals("信任后装配成功", 1, provider.collect().carriers.size)

        assertTrue(ModTrustStore.distrust(rt2, fixture.packageId))
        val after = provider.collect()
        assertEquals("撤销后回到 needs-trust", CodeCarrierOutcome.NEEDS_TRUST, after.records.single().outcome)
        assertEquals("撤销后不装配", 0, after.carriers.size)
    }

    @Test
    fun `总闸关时零扫描不碰存储也不建加载器`() {
        val gameRoot = newGameRoot("master-off")
        val fixture = importLifecyclePack(gameRoot, entrypoint = ENTRYPOINT)
        assertTrue(ModTrustStore.trust(fixture.rt2, fixture.packageId))
        val factory = CountingFactory()
        val logs = mutableListOf<String>()
        val storeCalls = mutableListOf<File>()

        val collection = providerOf(
            gameRoot = gameRoot,
            enabled = false,
            factory = factory,
            logSink = logs::add,
            storeFactory = { dir -> storeCalls += dir; ModCodeStore(dir) },
        ).collect()

        assertTrue("总闸关载体名单必须为空", collection.carriers.isEmpty())
        assertTrue("总闸关不得产出台账", collection.records.isEmpty())
        assertTrue("总闸关不得碰代码存储：$storeCalls", storeCalls.isEmpty())
        assertTrue("总闸关不得建 loader：${factory.created}", factory.created.isEmpty())
        assertTrue("总闸关不得写装载日志：$logs", logs.isEmpty())
    }

    @Test
    fun `卸载删净代码身份信任媒体设置七件套`() {
        val gameRoot = newGameRoot("uninstall")
        val fixture = importLifecyclePack(gameRoot)
        val rt2 = fixture.rt2
        val packageId = fixture.packageId
        val codeDir = File(rt2, MOD_CODE_DIR_NAME)
        val mediaDirName = "life"
        val mediaFile = putFile(gameRoot, "resources/mod/$mediaDirName/one.png")
        val soundFile = putFile(gameRoot, "sound/mod/$mediaDirName/two.ogg")
        writeMediaManifest(
            rt2,
            packageId,
            mediaDirName,
            "resources/mod/$mediaDirName/one.png",
            "sound/mod/$mediaDirName/two.ogg",
        )
        val settings = File(rt2, ModSettingsStore.memberName(packageId))
        settings.writeText("settings", Charsets.UTF_8)
        assertTrue(ModTrustStore.trust(rt2, packageId))

        assertTrue("卸载应成功", ModManageUninstall.uninstall(rt2, packageId))

        assertFalse("本包 code/ 目录必须删净", File(codeDir, packageId).exists())
        assertTrue("code/ 根必须保留", codeDir.isDirectory)
        ModPackageInventory.memberNames(packageId).forEach { member ->
            assertFalse("成员必须删净：$member", File(rt2, member).exists())
        }
        assertFalse("信任标记必须删净", trustMarker(rt2, packageId).exists())
        assertFalse(".media 清单必须删净", mediaManifestOf(rt2, packageId).exists())
        assertFalse(".settings 必须删净", settings.exists())
        assertFalse("媒体文件必须删净", mediaFile.exists())
        assertFalse("音频文件必须删净", soundFile.exists())
        assertFalse("媒体空目录必须删净", File(gameRoot, "resources/mod/$mediaDirName").exists())
        assertFalse("音频空目录必须删净", File(gameRoot, "sound/mod/$mediaDirName").exists())
        assertTrue("资源根必须保留", File(gameRoot, "resources/mod").isDirectory)
        assertTrue("音频根必须保留", File(gameRoot, "sound/mod").isDirectory)
        assertTrue("卸载台账应记本包", packageId in ModStorageReclaim.releasedPackageIds(rt2))
    }

    @Test
    fun `卸载A包后B包七件套逐字节仍在`() {
        val gameRoot = newGameRoot("two-packages")
        val fixtureA = importLifecyclePack(gameRoot, source = PATCH_SOURCE_A, sourceName = "life_a.ERB")
        val fixtureB = importLifecyclePack(gameRoot, source = PATCH_SOURCE_B, sourceName = "life_b.ERB")
        assertTrue("两包身份必须不同", fixtureA.packageId != fixtureB.packageId)
        val rt2 = fixtureA.rt2
        val codeDir = File(rt2, MOD_CODE_DIR_NAME)
        val mediaDirName = "keep"
        val mediaFile = putFile(gameRoot, "resources/mod/$mediaDirName/b.png")
        writeMediaManifest(rt2, fixtureB.packageId, mediaDirName, "resources/mod/$mediaDirName/b.png")
        val settingsB = File(rt2, ModSettingsStore.memberName(fixtureB.packageId))
        settingsB.writeText("settings-b", Charsets.UTF_8)
        assertTrue(ModTrustStore.trust(rt2, fixtureA.packageId))
        assertTrue(ModTrustStore.trust(rt2, fixtureB.packageId))

        val snapshot = snapshotPackage(rt2, fixtureB)

        assertTrue("卸载 A 应成功", ModManageUninstall.uninstall(rt2, fixtureA.packageId))
        assertFalse("A 的 code 目录必须删净", File(codeDir, fixtureA.packageId).exists())
        assertTrue("B 的 code 目录必须保留", File(codeDir, fixtureB.packageId).isDirectory)
        snapshot.forEach { (file, bytes) ->
            assertTrue("B 的文件必须保留：${file.name}", file.isFile)
            assertEquals("B 的文件必须逐字节不变：${file.name}", bytes.toList(), file.readBytes().toList())
        }
        assertTrue("B 的信任标记必须保留", trustMarker(rt2, fixtureB.packageId).isFile)
        assertTrue("B 的媒体文件必须保留", mediaFile.isFile)
        assertFalse("A 的信任标记必须删净", trustMarker(rt2, fixtureA.packageId).exists())
        assertTrue("B 的 code 产物仍可列出", ModCodeStore(rt2).list(fixtureB.packageId).isNotEmpty())
    }

    private fun snapshotPackage(rt2: File, fixture: LifecycleFixture): Map<File, ByteArray> {
        val files = mutableListOf<File>()
        ModPackageInventory.memberNames(fixture.packageId).forEach { files += File(rt2, it) }
        files += File(File(rt2, MOD_CODE_DIR_NAME), fixture.packageId).listFiles().orEmpty().toList()
        files += trustMarker(rt2, fixture.packageId)
        files += mediaManifestOf(rt2, fixture.packageId)
        files += File(rt2, ModSettingsStore.memberName(fixture.packageId))
        return files.associateWith { it.readBytes() }
    }
}

// ---------------------------------------------------------------------------
// 双身份信任收口（task-196）：磁盘身份 ≠ 运行期身份时的卸载 / GC / 重装
// ---------------------------------------------------------------------------

/**
 * 双身份信任标记生命周期端到端。
 *
 * 背景（ADR-013 S6）：代码包的**磁盘身份**是编译派生的 `kojo-<8hex>` 目录名，**运行期身份**是
 * 包作者在 `eratw-pack.json` / 内层 `manifest.json` 里声明的包名，两者由 `.codeid` 侧车桥接
 * （[ModCodeIdentity] → [ModDexIdentity]）。装载闸 [ModDexLoader] 按**运行期身份**查
 * `<runtime>.trusted`，而 [ModManageUninstall] 的成员名单只能按磁盘身份拼路径——两套身份不等时
 * 卸载删的是 `<disk>.trusted`，真正被装载闸读取的运行期标记会留下来，于是同一代码包重装后会
 * **静默继承旧信任**。本组用例钉的是「卸载按 `.codeid` 解析出运行期身份后补删」与
 * 「包变孤儿后 [gcOrphanTrustMarkers] 按盘面认领关系回收残留」两条收口路径，以及坏档 fail-closed。
 *
 * 夹具：真导入链造出磁盘 ≠ 运行期的包（见 [importDualIdentityPack]），信任标记按装载闸的同一
 * 落盘形态直接写盘（`<runtime>.trusted`，即 [ModDexGate.trustMarkerName]），不引入第二套映射。
 */
class ModCodeDualIdentityTrustGcTest : CodeLifecycleTestBase() {

    @Test
    fun `双身份卸载补删运行期信任标记后重装不再已信任`() {
        val gameRoot = newGameRoot("dual-uninstall")
        val runtimePackageId = "author.pack1"
        val fixture = importDualIdentityPack(gameRoot, runtimePackageId, entrypoint = ENTRYPOINT)
        val rt2 = fixture.rt2
        val diskPackageId = fixture.packageId
        assertTrue("磁盘身份必须是编译派生形态", PACKAGE_ID_REGEX.matches(diskPackageId))
        assertTrue("双身份必须不相等", diskPackageId != runtimePackageId)
        assertEquals(
            "侧车必须桥到作者声明的运行期身份",
            CodeIdSidecar.Bound(runtimePackageId),
            ModCodeIdentity.read(rt2, diskPackageId),
        )

        // 信任标记按装载闸读取面落盘（运行期身份），装配应成功。
        val runtimeMarker = trustMarker(rt2, runtimePackageId)
        runtimeMarker.writeText("", Charsets.UTF_8)
        assertTrue("运行期身份应被装载闸判为已信任", ModDexGate.isTrusted(rt2, runtimePackageId))
        val factory = CountingFactory()
        val before = providerOf(gameRoot, factory = factory).collect()
        assertEquals("双身份包应装配唯一载体", 1, before.carriers.size)
        assertEquals(runtimePackageId, before.carriers.single().first)
        assertEquals(CodeCarrierOutcome.LOADED, before.records.single().outcome)

        assertTrue("卸载应成功", ModManageUninstall.uninstall(rt2, diskPackageId))

        assertFalse("运行期信任标记必须补删净（否则重装静默继承）", runtimeMarker.exists())
        assertFalse("磁盘面信任标记同样不得留下", trustMarker(rt2, diskPackageId).exists())
        assertFalse("装载闸必须回到未信任", ModDexGate.isTrusted(rt2, runtimePackageId))
        assertTrue("code 产物目录必须删净", !File(File(rt2, MOD_CODE_DIR_NAME), diskPackageId).exists())
        ModPackageInventory.memberNames(diskPackageId).forEach { member ->
            assertFalse("成员必须删净：$member", File(rt2, member).exists())
        }
        val after = providerOf(gameRoot, factory = factory).collect()
        assertTrue("卸载后不得再有载体", after.carriers.isEmpty())
        assertTrue("卸载后不得再有代码包台账", after.records.isEmpty())
    }

    @Test
    fun `双身份卸载A包不得动B包的运行期信任标记`() {
        val gameRoot = newGameRoot("dual-two-packages")
        val runtimeA = "author.packa"
        val runtimeB = "author.packb"
        val fixtureA = importDualIdentityPack(
            gameRoot,
            runtimeA,
            entrypoint = ENTRYPOINT,
            source = PATCH_SOURCE_A,
            sourceName = "life_a.ERB",
        )
        val fixtureB = importDualIdentityPack(
            gameRoot,
            runtimeB,
            entrypoint = ENTRYPOINT,
            source = PATCH_SOURCE_B,
            sourceName = "life_b.ERB",
        )
        val rt2 = fixtureA.rt2
        assertTrue("两包磁盘身份必须不同", fixtureA.packageId != fixtureB.packageId)
        trustMarker(rt2, runtimeA).writeText("", Charsets.UTF_8)
        trustMarker(rt2, runtimeB).writeText("", Charsets.UTF_8)
        assertEquals("装载闸应同时信任两包", 2, ModTrustStore.trustedPackages(rt2).size)

        assertTrue("卸载 A 应成功", ModManageUninstall.uninstall(rt2, fixtureA.packageId))

        assertFalse("A 的运行期标记必须删净", trustMarker(rt2, runtimeA).exists())
        assertTrue("B 的运行期信任标记必须保留", trustMarker(rt2, runtimeB).isFile)
        assertTrue("B 的产物必须保留", fixtureB.artifactFile.isFile)
        assertTrue("B 的 code 目录必须保留", File(File(rt2, MOD_CODE_DIR_NAME), fixtureB.packageId).isDirectory)
        assertEquals(
            "B 的运行期身份必须保留",
            CodeIdSidecar.Bound(runtimeB),
            ModCodeIdentity.read(rt2, fixtureB.packageId),
        )
        val collection = providerOf(gameRoot).collect()
        assertEquals("只剩 B 一个载体", 1, collection.carriers.size)
        assertEquals("载体按 B 的运行期身份登记", runtimeB, collection.carriers.single().first)
        assertEquals(CodeCarrierOutcome.LOADED, collection.records.single().outcome)
        assertEquals(runtimeB, collection.records.single().runtimePackageId)
    }

    @Test
    fun `卸载后重装同一代码包不继承旧信任`() {
        val gameRoot = newGameRoot("dual-reinstall")
        val runtimePackageId = "author.packr"
        val first = importDualIdentityPack(gameRoot, runtimePackageId, entrypoint = ENTRYPOINT)
        val rt2 = first.rt2
        trustMarker(rt2, runtimePackageId).writeText("", Charsets.UTF_8)
        assertTrue(ModDexGate.isTrusted(rt2, runtimePackageId))
        assertTrue("卸载应成功", ModManageUninstall.uninstall(rt2, first.packageId))
        assertFalse("旧运行期标记必须删净", trustMarker(rt2, runtimePackageId).exists())

        // 同源同内容重装 ⇒ 命中同一磁盘包名，侧车重新声明同一运行期身份。
        val second = importDualIdentityPack(gameRoot, runtimePackageId, entrypoint = ENTRYPOINT)
        assertEquals("重装必须命中同一磁盘包名", first.packageId, second.packageId)
        assertEquals(
            "新侧车仍需声明该运行期身份",
            CodeIdSidecar.Bound(runtimePackageId),
            ModCodeIdentity.read(rt2, second.packageId),
        )
        assertFalse("重装后不得继承旧信任", trustMarker(rt2, runtimePackageId).exists())
        assertFalse("装载闸必须判未信任", ModDexGate.isTrusted(rt2, runtimePackageId))

        val factory = CountingFactory()
        val collection = providerOf(gameRoot, factory = factory).collect()
        assertEquals("未信任不得装配", 0, collection.carriers.size)
        assertEquals("结局必须是待信任", CodeCarrierOutcome.NEEDS_TRUST, collection.records.single().outcome)
        assertTrue("未信任不得建 loader", factory.created.isEmpty())
    }

    @Test
    fun `侧车缺失时卸载不猜运行期身份且不抛`() {
        val gameRoot = newGameRoot("dual-missing-sidecar")
        val runtimePackageId = "author.packm"
        val fixture = importDualIdentityPack(gameRoot, runtimePackageId)
        val rt2 = fixture.rt2
        val runtimeMarker = trustMarker(rt2, runtimePackageId)
        runtimeMarker.writeText("", Charsets.UTF_8)
        val sidecar = File(rt2, ModCodeIdentity.fileName(fixture.packageId))
        assertTrue("夹具应先有侧车", sidecar.isFile)
        assertTrue("删侧车应成功", sidecar.delete())

        val uninstalled = runCatching { ModManageUninstall.uninstall(rt2, fixture.packageId) }

        assertTrue("侧车缺失时卸载不得抛出：$uninstalled", uninstalled.isSuccess)
        assertTrue("磁盘面成员应照常删净", uninstalled.getOrDefault(false))
        ModPackageInventory.memberNames(fixture.packageId).forEach { member ->
            assertFalse("成员必须删净：$member", File(rt2, member).exists())
        }
        assertTrue("身份不可判时不得猜删来源不明的运行期标记", runtimeMarker.isFile)

        // 包已不剩任何代码产物 ⇒ 该标记变孤儿，由 GC 按盘面认领关系回收。
        ModPackageInventory.gcOrphans(rt2, File(gameRoot, "mods"))
        assertFalse("孤儿运行期标记必须由 GC 回收", runtimeMarker.exists())
        assertTrue("GC 不得抛出且不误伤 code 根", File(rt2, MOD_CODE_DIR_NAME).isDirectory)
    }

    @Test
    fun `侧车损坏时卸载只删磁盘面且GC随后回收残留标记`() {
        val gameRoot = newGameRoot("dual-corrupt-sidecar")
        val runtimePackageId = "author.packx"
        val fixture = importDualIdentityPack(gameRoot, runtimePackageId)
        val rt2 = fixture.rt2
        val runtimeMarker = trustMarker(rt2, runtimePackageId)
        runtimeMarker.writeText("", Charsets.UTF_8)
        val sidecar = File(rt2, ModCodeIdentity.fileName(fixture.packageId))
        sidecar.writeText("not-a-codeid", Charsets.UTF_8)
        assertEquals(CodeIdSidecar.Corrupt, ModCodeIdentity.read(rt2, fixture.packageId))

        val uninstalled = runCatching { ModManageUninstall.uninstall(rt2, fixture.packageId) }

        assertTrue("坏侧车时卸载不得抛出：$uninstalled", uninstalled.isSuccess)
        assertTrue("磁盘面仍应删净（坏档不得把卸载卡成残留）", uninstalled.getOrDefault(false))
        ModPackageInventory.memberNames(fixture.packageId).forEach { member ->
            assertFalse("成员必须删净：$member", File(rt2, member).exists())
        }
        assertTrue("运行期身份不可判，标记卸载时保留", runtimeMarker.isFile)

        ModPackageInventory.gcOrphans(rt2, File(gameRoot, "mods"))
        assertFalse("包已成孤儿，GC 必须回收残留标记", runtimeMarker.exists())
    }

    @Test
    fun `孤儿GC只清无主运行期标记并保留存活包标记`() {
        val gameRoot = newGameRoot("dual-gc-orphan")
        val runtimePackageId = "author.packo"
        val fixture = importDualIdentityPack(gameRoot, runtimePackageId)
        val rt2 = fixture.rt2
        val ownerMarker = trustMarker(rt2, runtimePackageId).apply { writeText("", Charsets.UTF_8) }
        val ghostMarker = trustMarker(rt2, "ghost.pack").apply { writeText("", Charsets.UTF_8) }

        ModPackageInventory.gcOrphans(rt2, File(gameRoot, "mods"))

        assertTrue("存活包按 .codeid 认领，标记必须保留", ownerMarker.isFile)
        assertFalse("无主运行期标记必须回收", ghostMarker.exists())
        assertTrue("存活包产物必须保留", fixture.artifactFile.isFile)
        assertEquals(
            "GC 后装载闸仍应信任存活包",
            listOf(runtimePackageId),
            ModTrustStore.trustedPackages(rt2),
        )
    }

    @Test
    fun `存活包侧车损坏时信任标记GC整轮保守跳过`() {
        val gameRoot = newGameRoot("dual-gc-conservative")
        val runtimePackageId = "author.packc"
        val fixture = importDualIdentityPack(gameRoot, runtimePackageId)
        val rt2 = fixture.rt2
        val orphanMarker = trustMarker(rt2, "ghost.pack").apply { writeText("", Charsets.UTF_8) }
        val sidecar = File(rt2, ModCodeIdentity.fileName(fixture.packageId))
        sidecar.writeText("not-a-codeid", Charsets.UTF_8)
        assertEquals(CodeIdSidecar.Corrupt, ModCodeIdentity.read(rt2, fixture.packageId))

        ModPackageInventory.gcOrphans(rt2, File(gameRoot, "mods"))

        assertTrue("存活包侧车坏 ⇒ 认领关系不可判，孤儿标记本轮必须保留", orphanMarker.isFile)
        assertTrue("坏档本身不得被 GC 顺手删掉（留给只读面显式报错）", sidecar.isFile)
        assertTrue("存活包产物必须保留", fixture.artifactFile.isFile)

        // 侧车修好后（重导同包即可）下一轮 GC 恢复正常回收。
        val repaired = importDualIdentityPack(gameRoot, runtimePackageId)
        assertEquals("修侧车应命中同一磁盘包名", fixture.packageId, repaired.packageId)
        ModPackageInventory.gcOrphans(rt2, File(gameRoot, "mods"))
        assertFalse("身份恢复可判后孤儿标记必须回收", orphanMarker.exists())
    }
}

/** 孤儿 GC / 重导清理 / 读失败 fail-closed。 */
class ModCodePackageLifecycleGcTest : CodeLifecycleTestBase() {

    /**
     * 现状锁（**不是设计承诺**）：源文件失联但产物齐全的包一律保留。
     *
     * 现行 [ModPackageInventory.gcOrphans] 只按「完整包 vs 半成品」判，完整包（`.erbm` + `.manifest`
     * 都在）即便源已不在 `mods/` 也保留——源失联的整体回收判据已撤销。本用例钉的是当前行为，
     * 后续若要改成「源失联即回收」，须先改本断言而不是让它静默漂移。
     */
    @Test
    fun `源文件失联但产物齐全的完整包按现状保留`() {
        val gameRoot = newGameRoot("gc-full")
        val fixture = importLifecyclePack(gameRoot)
        val rt2 = fixture.rt2
        val modsDir = File(gameRoot, "mods")
        val sources = modsDir.walkTopDown().filter { it.isFile && it.name.endsWith(".ERB") }.toList()
        assertTrue("夹具应已把源文件存进 mods/", sources.isNotEmpty())
        sources.forEach { assertTrue("清源文件应成功：${it.name}", it.delete()) }

        ModPackageInventory.gcOrphans(rt2, modsDir)

        assertTrue("完整包的 code 产物必须保留", fixture.artifactFile.isFile)
        assertTrue("完整包的 .erbm 必须保留", File(rt2, fixture.packageId + ModPackageInventory.MODULE_EXTENSION).isFile)
        assertTrue("完整包的 .manifest 必须保留", File(rt2, fixture.packageId + ModPackageInventory.MANIFEST_EXTENSION).isFile)
        assertTrue("完整包的 .codeid 必须保留", File(rt2, ModCodeIdentity.fileName(fixture.packageId)).isFile)
        assertEquals("产物仍可列出", 1, ModCodeStore(rt2).list(fixture.packageId).size)
    }

    @Test
    fun `只剩代码目录的半成品被孤儿GC删净`() {
        val gameRoot = newGameRoot("gc-half")
        val rt2 = rt2Of(gameRoot).apply { mkdirs() }
        val codeDir = File(rt2, MOD_CODE_DIR_NAME)
        val halfPackage = "kojo-half0001"
        val artifact = ModCodeStore(rt2).install(halfPackage, HALF_PACKAGE_BYTES)
        assertTrue("夹具应先有产物", artifact.file.isFile)

        ModPackageInventory.gcOrphans(rt2, File(gameRoot, "mods"))

        assertFalse("半成品的 code 包目录必须删净", File(codeDir, halfPackage).exists())
        assertTrue("code/ 根必须保留", codeDir.isDirectory)
        ModPackageInventory.gcOrphans(rt2, File(gameRoot, "mods"))
        ModCodeReclaim.gcOrphans(rt2)
        assertFalse("重复 GC 必须幂等", File(codeDir, halfPackage).exists())
    }

    @Test
    fun `重导同包新内容清掉旧版本并更新身份侧车`() {
        val gameRoot = newGameRoot("reimport")
        val fixture = importLifecyclePack(gameRoot)
        val rt2 = fixture.rt2
        val packageId = fixture.packageId
        val sidecar = File(rt2, ModCodeIdentity.fileName(packageId))
        assertEquals("首导侧车 = 磁盘身份", CodeIdSidecar.Bound(packageId), ModCodeIdentity.read(rt2, packageId))

        val newRuntime = "kojo-lifevtwo"
        val newCodeMod = codeModOf(newRuntime, OTHER_FAKE_DEX)
        val upgraded = imported(
            controllerIn(gameRoot).import(
                listOf(
                    PickedModFile(
                        ZIP_NAME,
                        zipOf("main.ERB" to PATCH_SOURCE.toByteArray(), CODE_MOD_ENTRY to newCodeMod),
                    ),
                ),
            ),
        )
        assertEquals(
            "重导仍命中同一磁盘包名",
            packageId,
            upgraded.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION),
        )

        val artifacts = ModCodeStore(rt2).list(packageId)
        assertEquals("同包只留最新版本", 1, artifacts.size)
        assertEquals("新版本地址键", sha256Hex(newCodeMod).take(SHA16_CHARS), artifacts.single().sha256Hex)
        assertFalse("旧版本文件必须删净", fixture.artifactFile.exists())
        assertEquals("旧内容不得残留", newCodeMod.toList(), artifacts.single().file.readBytes().toList())
        assertEquals(
            "身份侧车必须更新到新运行期身份",
            ModCodeIdentity.HEADER_LINE + "\n" + newRuntime + "\n",
            sidecar.readText(Charsets.UTF_8),
        )
    }

    @Test
    fun `代码目录不可读或被占位时只读面返回空且不抛`() {
        val gameRoot = newGameRoot("fail-closed")
        val fixture = importLifecyclePack(gameRoot)
        val rt2 = fixture.rt2
        val codeDir = File(rt2, MOD_CODE_DIR_NAME)
        val backup = File(rt2, "code-backup")
        assertTrue("夹具先移走 code/", codeDir.renameTo(backup))

        assertTrue("code/ 缺失时 list 必须返回空", ModCodeStore(rt2).list(fixture.packageId).isEmpty())
        assertTrue("code/ 缺失时信任清单必须返回空", ModTrustStore.codePackages(codeDir).isEmpty())
        ModCodeReclaim.gcOrphans(rt2)
        ModPackageInventory.gcOrphans(rt2, File(gameRoot, "mods"))
        assertTrue("code/ 缺失时装配不得抛", providerOf(gameRoot).collect().carriers.isEmpty())

        File(rt2, MOD_CODE_DIR_NAME).writeText("not-a-directory")
        assertTrue("用普通文件占位 code/", File(rt2, MOD_CODE_DIR_NAME).isFile)
        assertTrue("code/ 被文件占位时 list 必须返回空", ModCodeStore(rt2).list(fixture.packageId).isEmpty())
        assertTrue("code/ 被文件占位时信任清单必须返回空", ModTrustStore.codePackages(codeDir).isEmpty())
        ModCodeReclaim.gcOrphans(rt2)
        ModPackageInventory.gcOrphans(rt2, File(gameRoot, "mods"))
        val collection = providerOf(gameRoot).collect()
        assertTrue("code/ 被文件占位时装配不得抛", collection.carriers.isEmpty())
        assertTrue("code/ 被文件占位时不得出台账", collection.records.isEmpty())
        assertEquals(
            "身份侧车不受 code/ 读取失败影响",
            CodeIdSidecar.Bound(fixture.packageId),
            ModCodeIdentity.read(rt2, fixture.packageId),
        )

        assertTrue("恢复占位应成功", File(rt2, MOD_CODE_DIR_NAME).delete())
        assertTrue("恢复 code/ 应成功", backup.renameTo(codeDir))
        assertEquals("恢复后产物可读", 1, ModCodeStore(rt2).list(fixture.packageId).size)
    }

    @Test
    fun `code目录被文件占位时卸载不抛且清掉其余成员`() {
        val gameRoot = newGameRoot("uninstall-fail-closed")
        val fixture = importLifecyclePack(gameRoot)
        val rt2 = fixture.rt2
        val codeDir = File(rt2, MOD_CODE_DIR_NAME)
        val backup = File(rt2, "code-backup")
        assertTrue(codeDir.renameTo(backup))
        File(rt2, MOD_CODE_DIR_NAME).writeText("not-a-directory")
        assertTrue("用普通文件占位 code/", File(rt2, MOD_CODE_DIR_NAME).isFile)

        val uninstalled = ModManageUninstall.uninstall(rt2, fixture.packageId)

        assertTrue("卸载不得抛出且应返回 true", uninstalled)
        ModPackageInventory.memberNames(fixture.packageId).forEach { member ->
            assertFalse("成员必须删净：$member", File(rt2, member).exists())
        }
        assertFalse("信任标记必须删净", trustMarker(rt2, fixture.packageId).exists())
        assertTrue("恢复占位应成功", File(rt2, MOD_CODE_DIR_NAME).delete())
        assertTrue("恢复 code/ 应成功", backup.renameTo(codeDir))
    }
}

// ---------------------------------------------------------------------------
// 信任面统一到单一身份桥（task-199）：设置页授权 / 撤销 / 显示与装载闸同键
// ---------------------------------------------------------------------------

/**
 * 信任面（写 / 撤 / 显示）双身份端到端。
 *
 * 缺陷面（task-199）：设置页只按**磁盘身份**写 `<disk>.trusted`，装载闸按**运行期身份**读
 * `<runtime>.trusted`，双身份包授权后仍 needs-trust。本组用例全部走真实路径：真导入链造包、
 * [ModTrustBoard]（设置页信任卡的状态机本体）、[ModTrustStore]、[modCodePackState] /
 * [codeGateHintFor]（代码卡与导入收尾面），装载用真 [ModCodeCarrierProvider] + 真 store；
 * 唯一替身是 [CountingFactory]（不真构造 `DexClassLoader`）。
 *
 * 每例都在同一次用例里对齐三层读数：信任表行 = 盘面标记身份 = 装载结局。
 */
class ModCodeDualIdentityTrustBridgeE2ETest : CodeLifecycleTestBase() {

    @Test
    fun `信任卡授予双身份包后装载闸放行且标记落在运行期身份`() {
        val gameRoot = newGameRoot("bridge-grant")
        val runtimePackageId = "author.bridge"
        val fixture = importDualIdentityPack(gameRoot, runtimePackageId, entrypoint = ENTRYPOINT)
        val rt2 = fixture.rt2
        val diskPackageId = fixture.packageId
        assertTrue("前置：必须是双身份包", diskPackageId != runtimePackageId)

        val board = ModTrustBoard(rt2)
        val row = board.scan().single()
        assertEquals("信任表行按磁盘身份列包", diskPackageId, row.packageId)
        assertFalse("未授权前必须未信任", row.trusted)
        assertNull("侧车健康时不得是坏档行", row.blockedReason)

        board.requestTrust(diskPackageId)
        assertTrue("设置页确认信任必须落盘成功", board.confirmTrust())
        assertNull("健康包不得有受控失败原因", board.lastBlockedReason)
        assertTrue("重扫必须显示已信任", board.rows.single().trusted)

        val runtimeMarker = trustMarker(rt2, runtimePackageId)
        assertTrue("标记必须落在运行期身份上（装载闸读取键）", runtimeMarker.isFile)
        assertEquals("标记必须与 .off 同构（空文件）", 0L, runtimeMarker.length())
        assertFalse("不得再写磁盘身份标记", trustMarker(rt2, diskPackageId).exists())
        assertTrue(ModDexGate.isTrusted(rt2, runtimePackageId))

        val factory = CountingFactory()
        val collection = providerOf(gameRoot, factory = factory).collect()
        assertEquals("授权后装载闸必须放行", 1, collection.carriers.size)
        assertEquals("载体按运行期身份登记", runtimePackageId, collection.carriers.single().first)
        assertEquals(CodeCarrierOutcome.LOADED, collection.records.single().outcome)
        assertEquals(listOf(runtimePackageId to fixture.artifactFile), factory.created)
    }

    @Test
    fun `撤销双身份包信任后装载闸回待信任且两类标记都消失`() {
        val gameRoot = newGameRoot("bridge-revoke")
        val runtimePackageId = "author.bridgerev"
        val fixture = importDualIdentityPack(gameRoot, runtimePackageId, entrypoint = ENTRYPOINT)
        val rt2 = fixture.rt2
        val board = ModTrustBoard(rt2)
        board.requestTrust(fixture.packageId)
        assertTrue(board.confirmTrust())
        assertTrue("前置：授权后必须能装载", providerOf(gameRoot).collect().carriers.size == 1)
        // 旧版缺陷留下的磁盘身份标记：撤销必须顺手清掉，玩家才不会觉得「撤销没生效」。
        trustMarker(rt2, fixture.packageId).writeText("", Charsets.UTF_8)

        assertTrue("撤销必须成功", board.revoke(fixture.packageId))

        assertFalse("运行期标记必须删净", trustMarker(rt2, runtimePackageId).exists())
        assertFalse("旧版磁盘身份标记也必须删净", trustMarker(rt2, fixture.packageId).exists())
        assertFalse("重扫必须回未信任", board.rows.single().trusted)
        assertNull("撤销健康包不得报坏档", board.lastBlockedReason)
        val factory = CountingFactory()
        val collection = providerOf(gameRoot, factory = factory).collect()
        assertEquals("撤销后不得装配", 0, collection.carriers.size)
        assertEquals(CodeCarrierOutcome.NEEDS_TRUST, collection.records.single().outcome)
        assertTrue("撤销后不得建加载器", factory.created.isEmpty())
    }

    @Test
    fun `侧车损坏时授予信任受控失败且盘面零写入`() {
        val gameRoot = newGameRoot("bridge-corrupt")
        val runtimePackageId = "author.bridgebad"
        val fixture = importDualIdentityPack(gameRoot, runtimePackageId, entrypoint = ENTRYPOINT)
        val rt2 = fixture.rt2
        File(rt2, ModCodeIdentity.fileName(fixture.packageId)).writeText("not-a-codeid", Charsets.UTF_8)
        assertEquals("前置：侧车必须判坏", CodeIdSidecar.Corrupt, ModCodeIdentity.read(rt2, fixture.packageId))
        val before = rt2.walkTopDown().map { it.relativeTo(rt2).path }.sorted().toList()

        val board = ModTrustBoard(rt2)
        val row = board.scan().single()
        assertFalse("坏档行不得显示已信任", row.trusted)
        assertEquals("坏档行必须给出坏档原因", TrustMarkerBlockReason.IDENTITY_CORRUPT, row.blockedReason)
        board.requestTrust(fixture.packageId)
        assertFalse("坏档信任必须受控失败", board.confirmTrust())
        assertEquals(
            "坏档失败原因必须可上屏",
            TrustMarkerBlockReason.IDENTITY_CORRUPT,
            board.lastBlockedReason,
        )
        assertNotNull("坏档后行仍须显示（供玩家看到重新导入提示）", board.rows.single().blockedReason)

        val after = rt2.walkTopDown().map { it.relativeTo(rt2).path }.sorted().toList()
        assertEquals("坏档信任操作盘面零写入", before, after)
        assertTrue("不得落任何信任标记", anyTrustMarker(rt2).isEmpty())

        // 概览卡与卸载影响预览两面：坏档必须显式不可判，且不许猜 id 去问会话快照。
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val overviewRow = buildModCodeOverview(rt2, dexLoaderEnabled = true).rows.single()
        assertEquals("概览卡坏档必须保留原因", TrustMarkerBlockReason.IDENTITY_CORRUPT, overviewRow.blockedReason)
        assertEquals("概览卡坏档显式不可判", zh.modCodeStateCorrupt, modCodeRowTrustLabel(zh, overviewRow))
        val requested = mutableListOf<List<String>>()
        val snapshotOwner = Any()
        ModRuntimeOverviewEntry.install(snapshotOwner) { packageIds ->
            requested += packageIds
            ModRuntimeData(
                activeIds = emptySet(),
                timingByPackage = emptyMap(),
                slowCountByPackage = emptyMap(),
                thresholdNanos = 0L,
                storageByPackage = packageIds.associateWith { ModStorageReadout(bytes = 2_048L, keys = 3) },
            )
        }
        try {
            val saveLine = buildUninstallImpactLines(zh, rt2, fixture.packageId, dexLoaderEnabled = true)
                .first { it.startsWith(zh.modUninstallImpactSave) }
            assertEquals(
                "坏档卸载影响预览的 storage 段必须显式「—」",
                zh.modManageDetailLine(zh.modUninstallImpactSave, zh.modUninstallImpactMissing),
                saveLine,
            )
            assertTrue("坏档不许猜 id 去问会话快照", requested.isEmpty())
        } finally {
            ModRuntimeOverviewEntry.uninstall(snapshotOwner)
        }

        val factory = CountingFactory()
        val collection = providerOf(gameRoot, factory = factory).collect()
        assertTrue("坏档不得装配", collection.carriers.isEmpty())
        assertEquals(CodeCarrierOutcome.CORRUPT_IDENTITY, collection.records.single().outcome)
        assertTrue("坏档不得建加载器", factory.created.isEmpty())
    }

    @Test
    fun `侧车缺失时信任按磁盘身份落盘且装载放行`() {
        val gameRoot = newGameRoot("bridge-absent")
        val runtimePackageId = "author.bridgegone"
        val fixture = importDualIdentityPack(gameRoot, runtimePackageId, entrypoint = ENTRYPOINT)
        val rt2 = fixture.rt2
        assertTrue("前置：删侧车", File(rt2, ModCodeIdentity.fileName(fixture.packageId)).delete())

        val board = ModTrustBoard(rt2)
        board.requestTrust(fixture.packageId)
        assertTrue("缺侧车时运行期身份回落到磁盘身份，信任必须成功", board.confirmTrust())
        assertNull(board.lastBlockedReason)
        val diskMarker = trustMarker(rt2, fixture.packageId)
        assertTrue("缺侧车时标记仍按磁盘身份落盘（既有行为逐字节不变）", diskMarker.isFile)
        assertEquals(0L, diskMarker.length())
        assertFalse("没有侧车佐证，不得凭作者声明猜写运行期标记", trustMarker(rt2, runtimePackageId).exists())
        assertTrue(ModTrustStore.isTrusted(rt2, fixture.packageId))

        val factory = CountingFactory()
        val collection = providerOf(gameRoot, factory = factory).collect()
        assertEquals("缺侧车时装载闸按磁盘身份放行", 1, collection.carriers.size)
        assertEquals("回落身份 = 磁盘身份", fixture.packageId, collection.carriers.single().first)
        assertEquals(CodeCarrierOutcome.LOADED, collection.records.single().outcome)
    }

    @Test
    fun `信任卡概览卡卸载预览与装载闸四面读数一致`() {
        val gameRoot = newGameRoot("bridge-faces")
        val runtimePackageId = "author.bridgeface"
        val fixture = importDualIdentityPack(gameRoot, runtimePackageId, entrypoint = ENTRYPOINT)
        val rt2 = fixture.rt2
        val diskPackageId = fixture.packageId
        val board = ModTrustBoard(rt2)
        // 假会话快照：storage 段只按运行期身份存在（与 `storage.activate` 同键），并记下被问的 id 清单。
        val requested = mutableListOf<List<String>>()
        val runtimeSection = ModStorageReadout(bytes = 2_048L, keys = 3)
        val snapshotOwner = Any()
        ModRuntimeOverviewEntry.install(snapshotOwner) { packageIds ->
            requested += packageIds
            ModRuntimeData(
                activeIds = emptySet(),
                timingByPackage = emptyMap(),
                slowCountByPackage = emptyMap(),
                thresholdNanos = 0L,
                storageByPackage = packageIds.associateWith { id ->
                    if (id == runtimePackageId) runtimeSection else null
                },
            )
        }
        try {
            assertInitialDualIdentityFaces(rt2, diskPackageId, runtimePackageId, board, requested)
            board.requestTrust(diskPackageId)
            assertTrue(board.confirmTrust())
            assertFacesAfterGrant(gameRoot, rt2, diskPackageId, board)
            assertTrue(board.revoke(diskPackageId))
            assertFacesAfterRevoke(gameRoot, rt2, diskPackageId, board)
        } finally {
            ModRuntimeOverviewEntry.uninstall(snapshotOwner)
        }
    }

    /** 四面一致的前置：初始（未授权）时信任表 / 代码卡 / 导入收尾面 / 概览卡 / 卸载预览必须同判「未信任」。 */
    private fun assertInitialDualIdentityFaces(
        rt2: File,
        diskPackageId: String,
        runtimePackageId: String,
        board: ModTrustBoard,
        requested: MutableList<List<String>>,
    ) {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        assertFalse("面 1 初始信任表未信任", board.scan().single().trusted)
        assertEquals(
            "初始代码卡必须报未信任",
            ModCodeGateHintKind.UNTRUSTED,
            modCodePackState(rt2, diskPackageId, dexLoaderEnabled = true)?.hint?.kind,
        )
        assertEquals(
            "导入收尾面同判据",
            ModCodeGateHintKind.UNTRUSTED,
            codeGateHintFor(rt2, diskPackageId, codeGateEnabled = true)?.kind,
        )
        val beforeRow = buildModCodeOverview(rt2, dexLoaderEnabled = true).rows.single()
        assertFalse("面 2 初始概览卡必须未信任", beforeRow.trusted)
        assertNull("初始概览卡不得报坏档", beforeRow.blockedReason)
        assertEquals("概览卡状态词", zh.modTrustStateUntrusted, modCodeRowTrustLabel(zh, beforeRow))
        val beforeSave = buildUninstallImpactLines(zh, rt2, diskPackageId, dexLoaderEnabled = true)
            .first { it.startsWith(zh.modUninstallImpactSave) }
        assertTrue("面 3 预览 storage 段必须按运行期身份读到：$beforeSave", beforeSave.contains("2.0 KB"))
        assertTrue("预览 storage 段键数来自运行期段：$beforeSave", beforeSave.contains("3"))
        assertEquals("预览只许按运行期身份问会话快照", listOf(listOf(runtimePackageId)), requested)
    }

    /** 授权后的四面读数：信任表 / 代码卡 / 导入收尾面 / 概览卡全部放行，装载闸 LOADED。 */
    private fun assertFacesAfterGrant(
        gameRoot: File,
        rt2: File,
        diskPackageId: String,
        board: ModTrustBoard,
    ) {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        assertTrue("授权后信任表已信任", board.rows.single().trusted)
        assertNull("授权后代码卡必须无闸提示", modCodePackState(rt2, diskPackageId, dexLoaderEnabled = true)?.hint)
        assertNull("授权后导入收尾面无闸提示", codeGateHintFor(rt2, diskPackageId, codeGateEnabled = true))
        val afterRow = buildModCodeOverview(rt2, dexLoaderEnabled = true).rows.single()
        assertTrue("授权后概览卡必须已信任（与装载闸同键）", afterRow.trusted)
        assertNull("授权后概览卡不得报坏档", afterRow.blockedReason)
        assertEquals("概览卡状态词", zh.modTrustStateTrusted, modCodeRowTrustLabel(zh, afterRow))
        assertEquals(
            "面 4 授权后装载结局",
            CodeCarrierOutcome.LOADED,
            providerOf(gameRoot).collect().records.single().outcome,
        )
    }

    /** 撤销后的四面读数：信任表 / 概览卡 / 代码卡回未信任，装载闸 NEEDS_TRUST。 */
    private fun assertFacesAfterRevoke(gameRoot: File, rt2: File, diskPackageId: String, board: ModTrustBoard) {
        assertFalse("撤销后信任表未信任", board.rows.single().trusted)
        assertFalse(
            "撤销后概览卡必须回未信任",
            buildModCodeOverview(rt2, dexLoaderEnabled = true).rows.single().trusted,
        )
        assertEquals(
            "撤销后代码卡必须回未信任",
            ModCodeGateHintKind.UNTRUSTED,
            modCodePackState(rt2, diskPackageId, dexLoaderEnabled = true)?.hint?.kind,
        )
        assertEquals(
            "撤销后装载结局",
            CodeCarrierOutcome.NEEDS_TRUST,
            providerOf(gameRoot).collect().records.single().outcome,
        )
    }

    @Test
    fun `双身份包卸载后 storage 回收按运行期身份投递`() {
        val gameRoot = newGameRoot("bridge-reclaim")
        val runtimePackageId = "author.bridgerec"
        val fixture = importDualIdentityPack(gameRoot, runtimePackageId, entrypoint = ENTRYPOINT)
        val rt2 = fixture.rt2
        assertEquals(
            "前置：运行期身份可解析",
            runtimePackageId,
            ModTrustStore.resolveRuntimeId(rt2, fixture.packageId),
        )
        ModStorageReclaimRequests.clear()
        try {
            assertTrue("卸载应成功", uninstallModAndQueueReclaim(rt2, fixture.packageId))
            assertEquals(
                "storage 段按运行期身份命名，投递键必须是运行期身份（磁盘身份会让 release 空转）",
                listOf(runtimePackageId),
                ModStorageReclaimRequests.drain(),
            )
        } finally {
            ModStorageReclaimRequests.clear()
        }
    }

    @Test
    fun `侧车损坏时卸载成功但不猜运行期身份、回收不投递`() {
        val gameRoot = newGameRoot("bridge-reclaim-bad")
        val runtimePackageId = "author.bridgerecbad"
        val fixture = importDualIdentityPack(gameRoot, runtimePackageId, entrypoint = ENTRYPOINT)
        val rt2 = fixture.rt2
        File(rt2, ModCodeIdentity.fileName(fixture.packageId)).writeText("not-a-codeid", Charsets.UTF_8)
        assertNull("前置：坏档不得解析运行期身份", ModTrustStore.resolveRuntimeId(rt2, fixture.packageId))
        ModStorageReclaimRequests.clear()
        try {
            assertTrue("坏档不得把卸载卡成残留", uninstallModAndQueueReclaim(rt2, fixture.packageId))
            assertTrue("身份不可判时不得猜 id 投递回收", ModStorageReclaimRequests.drain().isEmpty())
        } finally {
            ModStorageReclaimRequests.clear()
        }
    }

    @Test
    fun `信任卡路径下同磁盘身份重装不继承旧信任`() {
        val gameRoot = newGameRoot("bridge-reinstall")
        val runtimePackageId = "author.bridgeagain"
        val first = importDualIdentityPack(gameRoot, runtimePackageId, entrypoint = ENTRYPOINT)
        val rt2 = first.rt2
        val board = ModTrustBoard(rt2)
        board.requestTrust(first.packageId)
        assertTrue(board.confirmTrust())
        assertTrue(trustMarker(rt2, runtimePackageId).isFile)
        assertTrue("卸载应成功", uninstallModAndQueueReclaim(rt2, first.packageId))
        ModStorageReclaimRequests.clear()
        assertFalse("卸载必须补删运行期标记", trustMarker(rt2, runtimePackageId).exists())

        val second = importDualIdentityPack(gameRoot, runtimePackageId, entrypoint = ENTRYPOINT)
        assertEquals("重装必须命中同一磁盘包名", first.packageId, second.packageId)
        val rescanned = ModTrustBoard(rt2).scan().single()
        assertFalse("重装后不得继承旧信任", rescanned.trusted)
        assertNull("重装侧车健康不得报坏档", rescanned.blockedReason)
        assertFalse(trustMarker(rt2, runtimePackageId).exists())
        val factory = CountingFactory()
        val collection = providerOf(gameRoot, factory = factory).collect()
        assertEquals("重装未信任不得装配", 0, collection.carriers.size)
        assertEquals(CodeCarrierOutcome.NEEDS_TRUST, collection.records.single().outcome)
        assertTrue(factory.created.isEmpty())
    }

    @Test
    fun `正常单身份包信任与撤销行为逐字节不变`() {
        val gameRoot = newGameRoot("bridge-single")
        val fixture = importLifecyclePack(gameRoot, entrypoint = ENTRYPOINT)
        val rt2 = fixture.rt2
        val board = ModTrustBoard(rt2)
        assertFalse(board.scan().single().trusted)
        board.requestTrust(fixture.packageId)
        assertTrue(board.confirmTrust())
        val markerFile = trustMarker(rt2, fixture.packageId)
        assertTrue(markerFile.isFile)
        assertEquals("单身份包标记名与内容逐字节不变", 0L, markerFile.length())
        assertFalse("不得出现临时件残留", rt2.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        assertEquals(
            CodeCarrierOutcome.LOADED,
            providerOf(gameRoot, factory = CountingFactory()).collect().records.single().outcome,
        )
        assertTrue(board.revoke(fixture.packageId))
        assertFalse(markerFile.exists())
        assertEquals(
            "撤销后回待信任",
            CodeCarrierOutcome.NEEDS_TRUST,
            providerOf(gameRoot).collect().records.single().outcome,
        )
    }
}

// ---------------------------------------------------------------------------
// 夹具：真导入链 + 真 store + 真装配（与既有 e2e 用例同款，private 故复制）
// ---------------------------------------------------------------------------

private data class LifecycleFixture(
    val packageId: String,
    val rt2: File,
    val codeMod: ByteArray,
    val artifactFile: File,
)

/**
 * 真导入出带代码产物的包，返回磁盘包名与落盘产物。
 *
 * 两段式（代码包身份一致化后的统一形态）：先导「只有 `main.ERB`」的一版拿到编译派生的磁盘包名，
 * 再以该包名作 `eratw-pack.json` 声明身份与内层 `manifest.json` 身份重导，使磁盘身份 == 运行期身份。
 */
private fun importLifecyclePack(
    gameRoot: File,
    dex: ByteArray = FAKE_DEX,
    entrypoint: String? = null,
    source: String = PATCH_SOURCE,
    sourceName: String = "main.ERB",
): LifecycleFixture {
    val plain = imported(
        controllerIn(gameRoot).import(
            listOf(PickedModFile(ZIP_NAME, zipOf(sourceName to source.toByteArray()))),
        ),
    )
    val packageId = plain.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
    val codeMod = codeModOf(packageId, dex, entrypoint)
    val detail = imported(
        controllerIn(gameRoot).import(
            listOf(PickedModFile(ZIP_NAME, packWithCode(codeMod, packageId, source, sourceName))),
        ),
    )
    assertEquals("重导包名必须稳定", packageId, detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION))
    val rt2 = rt2Of(gameRoot)
    val artifactFile = ModCodeStore(rt2).list(packageId).single().file
    return LifecycleFixture(packageId, rt2, codeMod, artifactFile)
}

/**
 * 真导入出「磁盘身份 ≠ 运行期身份」的代码包。
 *
 * 与 [importLifecyclePack] 同一两段式手法，区别只在第二次导入声明的是 `runtimePackageId`：
 * `eratw-pack.json` 与内层 `code.mod` 的 `manifest.json` 同填该值（导入链要求两者一致），
 * 磁盘包名仍由源内容派生（`kojo-<8hex>`），`.codeid` 侧车落 `Bound(runtimePackageId)` ——
 * 这正是 ADR-013 S6 之后第三方代码包的现实形态：运行期身份由包作者声明，磁盘身份是编译派生。
 */
private fun importDualIdentityPack(
    gameRoot: File,
    runtimePackageId: String,
    entrypoint: String? = null,
    source: String = PATCH_SOURCE,
    sourceName: String = "main.ERB",
): LifecycleFixture {
    val plain = imported(
        controllerIn(gameRoot).import(
            listOf(PickedModFile(ZIP_NAME, zipOf(sourceName to source.toByteArray()))),
        ),
    )
    val diskPackageId = plain.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
    val codeMod = codeModOf(runtimePackageId, FAKE_DEX, entrypoint)
    val detail = imported(
        controllerIn(gameRoot).import(
            listOf(PickedModFile(ZIP_NAME, packWithCode(codeMod, runtimePackageId, source, sourceName))),
        ),
    )
    assertEquals(
        "重导必须命中同一磁盘包名",
        diskPackageId,
        detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION),
    )
    val rt2 = rt2Of(gameRoot)
    val artifactFile = ModCodeStore(rt2).list(diskPackageId).single().file
    return LifecycleFixture(diskPackageId, rt2, codeMod, artifactFile)
}

private fun packWithCode(
    codeMod: ByteArray,
    declaredPackageId: String? = null,
    source: String = PATCH_SOURCE,
    sourceName: String = "main.ERB",
): ByteArray {    val meta = declaredPackageId?.let { id ->
        PACK_META_ENTRY_NAME to """{"schema":1,"packageId":"$id","version":"1.0"}""".toByteArray(Charsets.UTF_8)
    }
    return zipOf(
        sourceName to source.toByteArray(),
        CODE_MOD_ENTRY to codeMod,
        *listOfNotNull(meta).toTypedArray(),
    )
}

private fun codeModOf(innerPackageId: String, dex: ByteArray = FAKE_DEX, entrypoint: String? = null): ByteArray {
    val entry = entrypoint?.let { ""","entrypoint":"$it"""" }.orEmpty()
    return zipOf(
        "classes.dex" to dex,
        "manifest.json" to """{"packageId":"$innerPackageId","version":"1"$entry}""".toByteArray(Charsets.UTF_8),
    )
}

private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private fun controllerIn(tmp: File): ModImportController = ModImportController(
    modStore = ModStore(File(tmp, "mods")),
    baseSource = { BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null) },
    gameRoot = tmp,
)

private fun imported(outcome: ModImportOutcome): ImportedPatchDetail =
    (outcome as? ModImportOutcome.Imported)?.detail ?: throw AssertionError("导入应成功，实为 $outcome")

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

private fun miniBaseCounts(): PatchCompiler.BaseCounts = PatchCompiler.BaseCounts(
    instructionCount = 4000,
    functionCount = 4,
    statementCount = 100,
    stringCount = 10,
    longCount = 5,
    exprCount = 20,
)

private fun rt2Of(gameRoot: File): File = File(File(gameRoot, "mods"), "rt2")

private fun trustMarker(rt2: File, packageId: String): File = File(rt2, ModDexGate.trustMarkerName(packageId))

/** rt2 根下所有信任标记（普通文件、`.trusted` 后缀），用于断言「盘面零写入」/「无残留」。 */
private fun anyTrustMarker(rt2: File): List<File> = rt2.listFiles().orEmpty()
    .filter { it.isFile && it.name.endsWith(ModDexGate.TRUST_MARKER_SUFFIX) }

private fun mediaManifestOf(rt2: File, packageId: String): File =
    File(rt2, packageId + ModImportMediaAssets.MEDIA_MANIFEST_SUFFIX)

private fun writeMediaManifest(rt2: File, packageId: String, dir: String, vararg paths: String) {
    val text = ModImportMediaAssets.MANIFEST_VERSION + " " + ModImportMediaAssets.MANIFEST_DIR_KEY + dir +
        "\n" + paths.joinToString("\n") + "\n"
    mediaManifestOf(rt2, packageId).writeText(text, Charsets.UTF_8)
}

private fun putFile(gameRoot: File, relativePath: String): File {
    val file = File(gameRoot, relativePath)
    file.parentFile?.mkdirs()
    file.writeBytes(byteArrayOf(1, 2, 3))
    return file
}

private fun assertNoTmpResidue(codeDir: File) {
    val residue = codeDir.walkTopDown()
        .filter { it.isFile && it.name.startsWith(CODE_TMP_PREFIX) && it.name.endsWith(CODE_TMP_SUFFIX) }
        .map { it.name }
        .toList()
    assertTrue("code 目录临时残留：$residue", residue.isEmpty())
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun cleanupTree(root: File) {
    root.walkBottomUp().forEach { item ->
        item.setWritable(true)
        item.delete()
    }
}

private fun providerOf(
    gameRoot: File,
    enabled: Boolean = true,
    factory: DexLoaderFactory? = CountingFactory(CodeLifecycleTestBase::class.java.classLoader),
    logSink: (String) -> Unit = {},
    storeFactory: (File) -> ModCodeStore = ::ModCodeStore,
): ModCodeCarrierProvider = ModCodeCarrierProvider(
    gameRoot = gameRoot,
    masterEnabled = enabled,
    loaderFactory = factory,
    logSink = logSink,
    storeFactory = storeFactory,
)

/** 记录「按什么运行期身份、拿哪个产物文件」建了 loader；父加载器 = 测试 classpath。 */
private class CountingFactory(
    private val parent: ClassLoader? = CodeLifecycleTestBase::class.java.classLoader,
) : DexLoaderFactory {
    val created = mutableListOf<Pair<String, File>>()

    override fun create(packageId: String, artifactFile: File): ClassLoader {
        created += packageId to artifactFile
        return object : ClassLoader(parent) {}
    }
}

/** 合法入口类（测试 classpath 可见的最小 EraMod 实现，无参构造）。 */
class AssembledLifecycleMod : EraMod {
    override fun onLoad(context: ModContext) = Unit

    override fun onEnable() = Unit

    override fun onDisable() = Unit

    override fun onUnload() = Unit
}

private const val CODE_MOD_ENTRY = "code.mod"
private const val PACK_META_ENTRY_NAME = "eratw-pack.json"
private const val ZIP_NAME = "lifecycle.ZIP"
private const val CODE_TMP_PREFIX = "code-"
private const val CODE_TMP_SUFFIX = ".tmp"
private const val SHA16_CHARS = 16
private const val UNKNOWN_PACKAGE_ID = "kojo-missing0"
private val PACKAGE_ID_REGEX = Regex("kojo-[0-9a-f]{8}")
private val ENTRYPOINT = AssembledLifecycleMod::class.java.name
private const val PATCH_SOURCE = "@B_LIFE\nCALL A_ONE(3)\nPRINTL LIFECYCLE_PATCH\nRETURN 0\n"
private const val PATCH_SOURCE_A = "@B_LIFE_A\nCALL A_ONE(3)\nPRINTL LIFECYCLE_A\nRETURN 0\n"
private const val PATCH_SOURCE_B = "@B_LIFE_B\nCALL A_TWO(3)\nPRINTL LIFECYCLE_B\nRETURN 0\n"
private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private val FAKE_DEX = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)
private val OTHER_FAKE_DEX = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x36, 0x01)
private val HALF_PACKAGE_BYTES = "orphan-code-bytes".toByteArray(Charsets.UTF_8)
