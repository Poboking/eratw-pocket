package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.MOD_STORAGE_PACKAGE_VERSION_KEY
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModStorageSection
import com.eratw.port.core.mod.ModStorageSectionCodec
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.mod.putReservedKey
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * ModDX2-A（包版本升级迁移回调）的 app 装配线判据：版本对账 + 记账键接线对**真装配链**断言。
 *
 * 宿主派发缝的多 mod 语义与台架作者面在 `:mod-testing` 的 `PackageUpgradeNotifyTest`；
 * 本套只断装配侧独有的事实：attach 灌入的版本读数、记账键的真实落位（per-package storage
 * 分段）、回调序（onSaveLoaded 之后）、读档拒载包不派发、关态零接触与侧车零漂移。
 */
class ModPackageUpgradeAssemblyTest {

    private val logs: MutableList<String> = mutableListOf()

    /** 临时游戏根；`mods/rt2/` 目录按需建（与 ModRuntimeAssemblyWireTest 同款 fixture）。 */
    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-dx2a-root")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, fileName: String, manifest: ModManifest) {
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    /** 录制生命周期与升级回调拍序的 mod（回调序判据的载体；空载段也留痕，无空覆写块）。 */
    private class TraceMod(
        private val trace: MutableList<String>,
        override val dataVersion: Int = 0,
    ) : EraMod {
        override fun onLoad(context: ModContext) {
            trace += "onLoad"
        }

        override fun onEnable() {
            trace += "onEnable"
        }

        override fun onDisable() {
            trace += "onDisable"
        }

        override fun onUnload() {
            trace += "onUnload"
        }

        override fun onSaveLoaded() {
            trace += "onSaveLoaded"
        }

        override fun onPackageUpgrade(fromVersion: String, toVersion: String) {
            trace += "onPackageUpgrade($fromVersion->$toVersion)"
        }
    }

    private fun attach(
        root: Path,
        providers: List<Pair<String, EraMod>> = emptyList(),
        enabled: Boolean = true,
    ): ModRuntimeSession? = ModRuntimeAssembly.attach(
        gameRoot = root.toFile(),
        store = VariableStore(),
        nativeRegistry = NativeMethodRegistry(),
        onVmThread = { true },
        enabled = enabled,
        logSink = { line -> logs += line },
        modProviders = providers,
        newHost = { inputs ->
            ModRuntimeHost(
                snapshot = inputs.snapshot,
                entityKernel = inputs.entityKernel,
                nativeMethods = inputs.nativeMethods,
                logSink = inputs.logSink,
                runtime = inputs.runtime,
                shortIdOf = inputs.shortIdOf,
                intVarView = inputs.intVarView,
                strVarView = inputs.strVarView,
                storage = inputs.storage,
            )
        },
    )

    /**
     * 从分段载荷真源读记账键（不经作者视图）：拒载/停用的包退激活后 `apiFor` 的读写按
     * fail-closed 被拒（「禁用不等于删除」口径），断言必须对 encode→decode 的盘面读数。
     */
    private fun recordOf(session: ModRuntimeSession, packageId: String): String? =
        ModStorageSectionCodec.decode(session.storage.encode(), emptySet())
            .firstOrNull { it.packageId == packageId }
            ?.entries?.get(MOD_STORAGE_PACKAGE_VERSION_KEY)
            ?.let { value -> (value as? ModValue.StrValue)?.value }

    /** 种子写入走宿主专用门（ModDX-4 BS：作者视图写侧对保留前缀 fail-closed 拒，apiFor 不再收保留键）。 */
    private fun seedRecord(session: ModRuntimeSession, packageId: String, version: String) {
        session.storage.putReservedKey(packageId, MOD_STORAGE_PACKAGE_VERSION_KEY, version)
    }

    @org.junit.Test
    fun `升级路径触发回调fromTo正确且严格排在onSaveLoaded之后并回写新版本`() {
        val root = tempGameRoot()
        writeManifest(root, "mod.a.manifest", ModManifest(packageId = "mod.a", version = "2.0"))
        val trace = mutableListOf<String>()
        val session = attach(root, listOf("mod.a" to TraceMod(trace)))!!
        assertEquals(
            "attach 必须把包 manifest 版本灌入对账读数（当前侧）",
            "2.0",
            session.packageVersions["mod.a"],
        )
        seedRecord(session, "mod.a", "1.0")
        trace.clear()

        session.notifySaveLoaded()

        assertEquals(
            "升级回调必须到达、from/to 原样、且严格排在 onSaveLoaded 之后",
            listOf("onSaveLoaded", "onPackageUpgrade(1.0->2.0)"),
            trace,
        )
        assertEquals("派发后记账键必须回写当前版本", "2.0", recordOf(session, "mod.a"))
        session.shutdown()
    }

    @org.junit.Test
    fun `首次见到本包只记账不回调`() {
        val root = tempGameRoot()
        writeManifest(root, "mod.a.manifest", ModManifest(packageId = "mod.a", version = "2.0"))
        val trace = mutableListOf<String>()
        val session = attach(root, listOf("mod.a" to TraceMod(trace)))!!
        trace.clear()

        session.notifySaveLoaded()

        assertEquals("首次见到本包不得派发升级回调", listOf("onSaveLoaded"), trace)
        assertEquals("首次 = 只把当前版本记进记账键", "2.0", recordOf(session, "mod.a"))
        session.shutdown()
    }

    @org.junit.Test
    fun `记账版本相等零动作`() {
        val root = tempGameRoot()
        writeManifest(root, "mod.a.manifest", ModManifest(packageId = "mod.a", version = "2.0"))
        val trace = mutableListOf<String>()
        val session = attach(root, listOf("mod.a" to TraceMod(trace)))!!
        seedRecord(session, "mod.a", "2.0")
        trace.clear()

        session.notifySaveLoaded()

        assertEquals("版本相等不得回调", listOf("onSaveLoaded"), trace)
        assertEquals("2.0", recordOf(session, "mod.a"))
        session.shutdown()
    }

    @org.junit.Test
    fun `新局记账当前版本且不回调_旧记账覆写`() {
        val root = tempGameRoot()
        writeManifest(root, "mod.a.manifest", ModManifest(packageId = "mod.a", version = "2.0"))
        val trace = mutableListOf<String>()
        val session = attach(root, listOf("mod.a" to TraceMod(trace)))!!
        seedRecord(session, "mod.a", "0.9")
        trace.clear()

        session.notifyNewGame()

        assertEquals("新局不是装载既有存档，不派发升级回调", emptyList<String>(), trace)
        assertEquals(
            "新局必须把当前版本无条件记账（旧记账不承接，避免假升级读数）",
            "2.0",
            recordOf(session, "mod.a"),
        )
        session.shutdown()
    }

    @org.junit.Test
    fun `读档拒载的包不派发升级回调且记账键原样保留`() {
        val root = tempGameRoot()
        writeManifest(root, "mod.a.manifest", ModManifest(packageId = "mod.a", version = "2.0"))
        val trace = mutableListOf<String>()
        // dataVersion=1 < 恢复段的 2 ⇒ 读档缝降级拒载（fail-closed），包退激活。
        val session = attach(root, listOf("mod.a" to TraceMod(trace, dataVersion = 1)))!!
        val restored = ModStorageStore.load(
            ModStorageSectionCodec.encode(
                listOf(
                    ModStorageSection(
                        "mod.a",
                        linkedMapOf(MOD_STORAGE_PACKAGE_VERSION_KEY to ModValue.StrValue("1.0")),
                        dataVersion = 2,
                    ),
                ),
            ),
            knownPackages = setOf("mod.a"),
        )
        session.adoptStorage(restored)
        trace.clear()

        session.notifySaveLoaded()

        assertEquals("拒载的包连 onSaveLoaded 都不达，更不派发升级回调", emptyList<String>(), trace)
        assertEquals(
            "拒载包的记账键原样保留（不删数据、不回写）",
            "1.0",
            recordOf(session, "mod.a"),
        )
        assertFalse("拒载包已退激活：对账必须跳过不碰", session.storage.isActive("mod.a"))
        session.shutdown()
    }

    @org.junit.Test
    fun `关态零接触且无mod会话读档零漂移`() {
        val root = tempGameRoot()
        // 关态：attach 返回 null（没有会话对象 = 记账/对账全链不跑），且不产生任何日志。
        val offSession = attach(root, enabled = false)
        assertNull("关态必须返回 null（零接触）", offSession)
        assertTrue("关态不得产生任何日志", logs.isEmpty())

        // 开态但没装任何 mod：读档末尾对账零动作，会话存储逐字节零漂移、零日志。
        writeManifest(root, "mod.a.manifest", ModManifest(packageId = "mod.a", version = "2.0"))
        val session = attach(root)!!
        val before = session.storage.encode()
        val logsBefore = logs.size

        session.notifySaveLoaded()

        val after = session.storage.encode()
        assertTrue(
            "无 mod 会话读档不得改动侧车载荷（零漂移）",
            before.contentEquals(after),
        )
        assertEquals("无 mod 会话读档不得产生新日志", logsBefore, logs.size)
        session.shutdown()
        root.toFile().deleteRecursively()
    }
}
