package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.BoolSettingDeclaration
import com.eratw.port.core.patch.IntSettingDeclaration
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.IntSettingDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modfamily.FamilyDeclarations
import com.eratw.port.modfamily.FamilyMod
import com.eratw.port.modfamily.generated.FamilyFacade
import com.eratw.port.modfamily.generated.FamilyView
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 停用包（`mods/rt2/<packageId>.off` 在场）与 per-mod 设置的交叉面审计（task-130）。
 *
 * 审计面四情形，本类钉**盘面/装配面事实**；设置页渲染面的「停用态不出编辑区」由
 * `SettingsActivity.kt` 的 `ModSettingsSection` 一条 `if (!mod.enabled) return` 早退实现
 * （判据取扫描真源字段，与列表开关同源）。单测源集没有 Compose 运行时，故该接线由源码哨兵
 * 钉住（用例 3c），并同时钉「停用不碰盘、重新启用后值仍在」这一修改正当性来源：
 *
 * 1. 停用包仍在管理列表、`enabled=false`，且声明面（`InstalledMod.settings`）完整；
 * 2. 停用期写入不被拒：`ModSettingsStore.saveValues` 不检查启用状态，照常落盘
 *    `mods/rt2/<pkg>.settings`（设置页 persist 同款入口）；
 * 3. 停用期保存的值**不丢**：重新启用 + 新一局 attach 后，mod 的 `context.settings` 读到新值；
 *    但会话**中途**停用后保存的那一次，热通知 rebind 被 `snapshotsFor` 的 `.off` 过滤跳过
 *    （`ModSettingsStore.kt` 的 `enabledOnly` 行）⇒ 即使随后重新启用，本局也不兑现，须重开一局。
 *    这是「Toast 承诺下一回合生效」与实际的唯一分叉；值本身仍在盘上（用例三、四各自钉一头）。
 * 4. 卸载删净设置（既有 `ModSettingsPackageMemberTest` 已钉）；只剩 `.settings` 的孤儿不进扫描
 *    列表（无 manifest 就不产 `InstalledMod`）、不产快照、GC 保守保留 ⇒ 无孤儿显示/编辑面。
 *
 * 用例二~四走生产入口：扫描 = [ModManageScanner.scan]，写盘 = [ModSettingsStore.saveValues]，
 * 装配 = [ModRuntimeAssembly.attach]（缺省宿主构造缝），热通知 = 设置页同款 enqueue +
 * `GameHost` 页边界同序 drain → [ModRuntimeSession.notifySettingsChanged]。
 */
class ModDisabledSettingsAuditTest {

    private val logs: MutableList<String> = mutableListOf()

    private fun tempGameRoot(prefix: String): Path {
        val root = Files.createTempDirectory(prefix)
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun rt2Of(root: Path): File = root.resolve("mods").resolve("rt2").toFile()

    private fun writeSettingsManifest(root: Path, packageId: String) {
        val descriptor = ModuleDescriptor(
            packageId = packageId,
            displayName = "带设置项的包",
            settings = listOf(
                IntSettingDeclaration("cfg.volume", default = 5L, min = 0L, max = 10L, title = "音量"),
                BoolSettingDeclaration("cfg.auto", default = true, title = "自动"),
            ),
        )
        Files.write(rt2Of(root).toPath().resolve("$packageId.manifest"), ManifestCodec.encode(descriptor))
    }

    private fun writeModManifest(root: Path, packageId: String, volumeDefault: Long = 5L) {
        val manifest = ModManifest(
            packageId = packageId,
            displayName = "带设置项的包",
            settings = listOf(
                IntSettingDecl("cfg.volume", default = volumeDefault, min = 0L, max = 10L, title = "音量"),
            ),
        )
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        Files.write(rt2Of(root).toPath().resolve("$packageId.manifest"), bytes)
    }

    private fun attach(
        root: Path,
        store: VariableStore,
        providers: List<Pair<String, EraMod>>,
    ): ModRuntimeSession = checkNotNull(
        ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = providers,
        ),
    ) { "开态生产装配必须产出会话；日志=$logs" }

    private class VolumeProbe(private val reads: MutableMap<String, String>) : EraMod {
        override fun onLoad(context: ModContext) {
            reads["volume"] = context.settings.int("cfg.volume").toString()
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    // ---------------- 情形 1：停用包仍在列表，声明面完整 ----------------

    @Test
    fun `停用包仍在管理列表且声明面完整_设置区数据源不空`() {
        val root = tempGameRoot("disabled-settings-scan")
        try {
            writeSettingsManifest(root, "mod.off.settings")
            assertTrue(ModEnablement.setEnabled(rt2Of(root), "mod.off.settings", false))

            val scan = ModManageScanner.scan(root.toFile())

            val mod = scan.mods.single { it.packageId == "mod.off.settings" }
            assertFalse("停用读数必须上屏（列表行开关凭它渲染）", mod.enabled)
            assertEquals(
                "声明面不随启停清空 ⇒ 设置页对停用包照常渲染编辑区的数据源就在这",
                listOf("cfg.volume", "cfg.auto"),
                mod.settings.map { it.key },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ---------------- 情形 2：停用期仍可写入（不检查启用状态） ----------------

    @Test
    fun `停用期保存照常落盘_不检查启用状态但快照面仍跳过`() {
        val root = tempGameRoot("disabled-settings-write")
        try {
            val rt2 = rt2Of(root)
            writeSettingsManifest(root, "mod.off.write")
            assertTrue(ModEnablement.setEnabled(rt2, "mod.off.write", false))

            assertTrue(
                "设置页 persist（saveValues）在停用态必须照常成功：UI 的 Toast 正反馈来源",
                ModSettingsStore.saveValues(rt2, "mod.off.write", mapOf("cfg.volume" to "8")),
            )
            assertTrue("值必须真落盘", ModSettingsStore.settingsFile(rt2, "mod.off.write").isFile)
            assertEquals("8", ModSettingsStore.read(rt2, "mod.off.write")["cfg.volume"])
            assertFalse(
                "停用包照旧不产快照（装配期跳过它）",
                ModSettingsStore.snapshotsFor(root.toFile()).containsKey("mod.off.write"),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ---------------- 情形 3a：停用期写入不丢，再启用 + 新一局读到 ----------------

    @Test
    fun `停用期保存的值重新启用后经真装配入口读到新值`() {
        val root = tempGameRoot("disabled-settings-reenable")
        try {
            val rt2 = rt2Of(root)
            val pkg = "mod.off.reenable"
            writeModManifest(root, pkg)
            assertTrue(ModEnablement.setEnabled(rt2, pkg, false))

            assertTrue(ModSettingsStore.saveValues(rt2, pkg, mapOf("cfg.volume" to "8")))
            assertFalse(
                "停用期间不产快照 = 本局不装配它",
                ModSettingsStore.snapshotsFor(root.toFile()).containsKey(pkg),
            )

            assertTrue("玩家重新启用", ModEnablement.setEnabled(rt2, pkg, true))
            val reads = mutableMapOf<String, String>()
            val session = attach(root, VariableStore(), listOf(pkg to VolumeProbe(reads)))
            try {
                assertEquals(
                    "停用期保存的值不丢：重新启用 + 新一局 attach 后 onLoad 读到新值 8",
                    "8",
                    reads["volume"],
                )
            } finally {
                session.shutdown()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ---------------- 情形 3b：本会话中途停用 → 热通知不兑现（本局） ----------------

    /** 生产形状游戏根：`mods/rt2/` 下投递全部注册载体的内置 manifest（与热生效 E2E 同款）。 */
    private fun productionGameRoot(): Path {
        val root = Files.createTempDirectory("disabled-settings-effect")
        val rt2 = root.resolve("mods").resolve("rt2")
        Files.createDirectories(rt2)
        for (packageId in ModCarrierRegistry.providers(noMarkersRoot()).map { it.first }) {
            val manifest = File("src/main/assets/mods/rt2/$packageId.manifest")
            assertTrue("内置 RT2 manifest 缺席：${manifest.path}", manifest.isFile)
            Files.copy(manifest.toPath(), rt2.resolve(manifest.name))
        }
        return root
    }

    /** 无停用标记的游戏根（目录不存在 ⇒ 停用集为空）。 */
    private fun noMarkersRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "eratw-disabled-settings-" + System.nanoTime())

    private fun familyOf(providers: List<Pair<String, EraMod>>): FamilyMod =
        providers.first { it.first == FamilyDeclarations.PACKAGE_ID }.second as FamilyMod

    private fun viewOf(session: ModRuntimeSession, child: com.eratw.port.modapi.EntityId): FamilyView {
        val context = checkNotNull(session.host.contextOf(FamilyDeclarations.PACKAGE_ID)) {
            "激活的 mod 必须有 context"
        }
        return FamilyFacade(context.entities).of(child)
    }

    private fun setDay(store: VariableStore, day: Long) {
        store.writeInt("DAY", longArrayOf(0L), day)
    }

    @Test
    fun `本会话中途停用后改设置_热通知不兑现_重开一局才生效`() {
        val root = productionGameRoot()
        val rt2 = rt2Of(root)
        val familyPkg = FamilyDeclarations.PACKAGE_ID
        val growthKey = FamilyDeclarations.GROWTH_PER_DAY_KEY
        ModSettingsNotifyChannel.clear()
        val store = VariableStore()
        val providers = ModCarrierRegistry.providers(noMarkersRoot())
        val session = attach(root, store, providers)
        try {
            val family = familyOf(providers)
            assertTrue(
                "family 必须真装成功；日志=$logs",
                logs.any { it == "mod.install pkg=${FamilyDeclarations.PACKAGE_ID} active=true" },
            )
            val child = family.adoptChild("甲", null)
            setDay(store, 0L)
            session.onTurnBoundary()
            assertEquals("缺键回落声明默认 growthPerDay=1", 1L, viewOf(session, child).age)

            // 本会话中途停用（标记只影响下一次装配，本局会话不变）。
            assertTrue(ModEnablement.setEnabled(rt2, familyPkg, false))
            // 设置页 persist 的生产镜像：写盘成功才投递热通知。
            assertTrue(ModSettingsStore.saveValues(rt2, familyPkg, mapOf(growthKey to "5")))
            ModSettingsNotifyChannel.enqueue(familyPkg, listOf(growthKey))

            setDay(store, 1L)
            session.onTurnBoundary()
            assertEquals("热通知兑现前的业务拍仍读旧快照", 2L, viewOf(session, child).age)

            val drained = ModSettingsNotifyChannel.drain()
            assertEquals(
                "停用期保存照常投递热通知（UI Toast + 入队都无 enabled 判据）",
                listOf(familyPkg to listOf(growthKey)),
                drained,
            )
            drained.forEach { (packageId, keys) -> session.notifySettingsChanged(packageId, keys) }

            // 随后重新启用：热通知已消费，rebind 因 .off 过滤被跳过 ⇒ 本局不再有机会兑现。
            assertTrue(ModEnablement.setEnabled(rt2, familyPkg, true))
            setDay(store, 2L)
            session.onTurnBoundary()
            assertEquals(
                "停用期保存的值本局不生效（要重开一局）——与「下一回合生效、不用重开」的 UI 承诺分叉",
                3L,
                viewOf(session, child).age,
            )
        } finally {
            session.shutdown()
        }
        // 重开一局（生产装配入口）：新值真生效 ⇒ 值不丢，只是本局时效性不一致。
        assertReopenReadsSavedGrowth(root)
        root.toFile().deleteRecursively()
    }

    /** 重开一局（生产装配入口）后首拍读到的 growthPerDay：停用期保存的值 5。 */
    private fun assertReopenReadsSavedGrowth(root: Path) {
        val store = VariableStore()
        val providers = ModCarrierRegistry.providers(root.toFile())
        val session = attach(root, store, providers)
        try {
            val child = familyOf(providers).adoptChild("乙", null)
            setDay(store, 0L)
            session.onTurnBoundary()
            assertEquals(
                "重开一局后读到停用期保存的新值 5（值不丢）",
                5L,
                viewOf(session, child).age,
            )
        } finally {
            session.shutdown()
        }
    }

    // ---------------- 情形 4：只剩设置的孤儿无显示/编辑面 ----------------

    @Test
    fun `只剩设置侧车的孤儿不进列表不产快照且GC保守保留`() {
        val root = tempGameRoot("disabled-settings-orphan")
        try {
            val rt2 = rt2Of(root)
            val pkg = "kojo-0rphan01"
            assertTrue(ModSettingsStore.write(rt2, pkg, mapOf("cfg.volume" to "7")))

            assertTrue(
                "没有 manifest 就不产 InstalledMod ⇒ 设置页没有可编辑行",
                ModManageScanner.scan(root.toFile()).mods.none { it.packageId == pkg },
            )
            assertTrue(
                "没有 manifest 也不产 attach 快照",
                ModSettingsStore.snapshotsFor(root.toFile()).isEmpty(),
            )

            ModPackageInventory.gcOrphans(rt2, root.resolve("mods").toFile())
            assertTrue(
                "只有设置侧车、没有任何包文件 ⇒ 保守保留（作者先摆设置再导入是合法顺序）",
                ModSettingsStore.settingsFile(rt2, pkg).isFile,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ---------------- 情形 3c：停用不出编辑面，但已存值一个字节不动 ----------------

    @Test
    fun `停用不出编辑面但已存设置不丢_重新启用后设置区与值都回来`() {
        val root = tempGameRoot("disabled-settings-hide")
        try {
            val rt2 = rt2Of(root)
            val pkg = "mod.off.hide"
            writeSettingsManifest(root, pkg)
            assertTrue(ModSettingsStore.saveValues(rt2, pkg, mapOf("cfg.volume" to "8")))
            val savedBytes = ModSettingsStore.settingsFile(rt2, pkg).readBytes()

            assertTrue(ModEnablement.setEnabled(rt2, pkg, false))
            val disabled = ModManageScanner.scan(root.toFile()).mods.single { it.packageId == pkg }
            assertFalse("停用读数来自扫描真源，设置区判据与列表开关同源", disabled.enabled)
            assertTrue(
                "声明面仍在：不出编辑面是 UI 判据的结果，不是数据丢了",
                disabled.settings.isNotEmpty(),
            )

            val section = modSettingsSectionSource()
            assertSectionHiddenForDisabledByWiring(section)
            assertEquals(
                "停用不碰盘：已存设置字节与停用前逐字节相同",
                savedBytes.toList(),
                ModSettingsStore.settingsFile(rt2, pkg).readBytes().toList(),
            )
            assertEquals("停用期读回来的仍是那份值", "8", ModSettingsStore.read(rt2, pkg)["cfg.volume"])

            assertTrue("玩家重新启用", ModEnablement.setEnabled(rt2, pkg, true))
            val reenabled = ModManageScanner.scan(root.toFile()).mods.single { it.packageId == pkg }
            assertTrue("删掉 .off 重新扫描后早退条件不再命中 ⇒ 设置区回来", reenabled.enabled)
            assertTrue(
                "设置区数据源（声明面）仍在",
                reenabled.settings.map { it.key }.contains("cfg.volume"),
            )
            assertEquals(
                "重新启用后设置区读到停用前那份值（值没丢，不必重配）",
                "8",
                ModSettingsStore.read(rt2, pkg)["cfg.volume"],
            )
            assertEquals(
                "且盘上字节一个没变",
                savedBytes.toList(),
                ModSettingsStore.settingsFile(rt2, pkg).readBytes().toList(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 源码哨兵（Gradle 单测 cwd = app 模块目录；与 ModSettingsPackageMemberTest 同款）
    // ------------------------------------------------------------------

    /** 停用态不出编辑面的接线断言：判据、同源、早退位置（单测无 Compose 运行时）。 */
    private fun assertSectionHiddenForDisabledByWiring(section: String) {
        val guard = "if (!mod.enabled) return"
        assertTrue("详情不出设置区 = ModSettingsSection 按 mod.enabled 早退", section.contains(guard))
        assertFalse(
            "判据不得自读 .off / isDisabled：设置区与列表开关必须同源",
            section.contains("isDisabled") || section.contains("\".off\""),
        )
        assertTrue(
            "早退必须在状态声明（remember/mutableStateOf）之前，停用态整段编辑面不可达",
            section.indexOf(guard) < section.indexOf("val context = LocalContext.current"),
        )
        assertTrue(
            "早退必须在写盘调用之前：停用态根本走不到 saveValues",
            section.indexOf(guard) < section.indexOf("ModSettingsStore.saveValues("),
        )
    }

    /** 抽 `ModSettingsSection` 函数段源码：单测源集无 Compose 运行时，接线只能以源码钉。 */
    private fun modSettingsSectionSource(): String {
        val source = sourceOf("SettingsActivity.kt")
        val start = source.indexOf("private fun ModSettingsSection(")
        assertTrue("SettingsActivity.kt 里找不到 ModSettingsSection", start >= 0)
        val end = source.indexOf("@Composable", start + 1)
        return source.substring(start, if (end > start) end else source.length)
    }

    private fun sourceOf(name: String): String {
        val dir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(dir, "app/src/main/kotlin/com/eratw/port/android/$name"),
            File(dir, "src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }
}
