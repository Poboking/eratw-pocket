package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.BoolSettingDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.IntSettingDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页写入 → 运行会话热通知接线（ModDX DX-A2）的行为面 + 接线源码钉。
 *
 * 行为面直接打生产缝 [ModRuntimeAssembly.attach]（缺省 newHost，与 [ModSettingsAttachWireTest]
 * 同款装配），钉三件事：
 *
 * 1. 写入成功 → 通道积压 → 页边界 drain → `ModRuntimeSession.notifySettingsChanged` →
 *    mod 的 `onSettingsChanged` 到达且键正确（含同包多次写入合并、去重、首投递序）；
 * 2. 键过滤终审在会话侧：未声明键被丢弃、未随本会话启用的包整批丢弃（不抛）；
 * 3. 已收口会话 no-op 不崩（`shutDown` 闸）；
 * 4. 组长接线判据（DX-A1 判据 1）：派发前重注——回调里 `context.settings` 读到的已是
 *    盘上新值（`ModRuntimeHost.rebindSettings` + `settingsSnapshotOf` 重读源）。
 *
 * 接线源码钉与 [ModUiSettingsEntryWireTest] 同族：GameHost 页边界 drain 行、退出清场 clear 行、
 * 设置页 persist 的「写成功才 enqueue」顺序。
 */
class ModSettingsNotifyWireTest {

    private val logs: MutableList<String> = mutableListOf()

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-settings-notify")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, fileName: String, manifest: ModManifest) {
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    private fun attach(root: Path, providers: List<Pair<String, EraMod>>): ModRuntimeSession? =
        ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = VariableStore(),
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
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

    /** 把 `onSettingsChanged` 收进 [received] 的探针 mod（每包一条 (pkg, keys) 记录）。 */
    private class RecordingMod(
        private val packageId: String,
        private val received: MutableList<Pair<String, List<String>>>,
    ) : EraMod {
        override fun onLoad(context: ModContext) = Unit
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
        override fun onSettingsChanged(changedKeys: List<String>) {
            received += packageId to changedKeys
        }
    }

    /** GameHost.kt 页边界 drain 行的测试镜像（生产形态钉在源码钉用例里）。 */
    private fun drainAndDeliver(session: ModRuntimeSession?) {
        ModSettingsNotifyChannel.drain().forEach { (packageId, keys) ->
            session?.notifySettingsChanged(packageId, keys)
        }
    }

    @Test
    fun `写入成功经通道到会话 回调到达且键正确`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_notify_a.manifest",
            ModManifest(
                packageId = "mod.notify.a",
                displayName = "探针包A",
                settings = listOf(
                    IntSettingDecl("cfg.volume", default = 5L, min = 0L, max = 10L, title = "音量"),
                    BoolSettingDecl("cfg.auto", default = true, title = "自动"),
                ),
            ),
        )
        val received = mutableListOf<Pair<String, List<String>>>()
        val session = checkNotNull(attach(root, listOf("mod.notify.a" to RecordingMod("mod.notify.a", received))))

        // 玩家在同一页边界窗口里先后改两项（两次 persist = 两次 enqueue）。
        ModSettingsNotifyChannel.enqueue("mod.notify.a", listOf("cfg.volume"))
        ModSettingsNotifyChannel.enqueue("mod.notify.a", listOf("cfg.auto"))
        drainAndDeliver(session)

        assertEquals("同一包的两次写入必须合并成一次回调", 1, received.size)
        assertEquals(listOf("cfg.volume", "cfg.auto"), received.single().second)
        assertEquals("键序 = 首投递序", "mod.notify.a", received.single().first)
        session.shutdown()
    }

    @Test
    fun `未声明键被会话侧过滤 未启用包整批丢弃`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_notify_b.manifest",
            ModManifest(
                packageId = "mod.notify.b",
                displayName = "探针包B",
                settings = listOf(
                    BoolSettingDecl("cfg.auto", default = true, title = "自动"),
                ),
            ),
        )
        val received = mutableListOf<Pair<String, List<String>>>()
        val session = checkNotNull(attach(root, listOf("mod.notify.b" to RecordingMod("mod.notify.b", received))))

        ModSettingsNotifyChannel.enqueue("mod.notify.b", listOf("cfg.auto", "cfg.not_declared"))
        ModSettingsNotifyChannel.enqueue("mod.not.enabled", listOf("cfg.auto"))
        drainAndDeliver(session)

        assertEquals("未启用包不得产生任何回调", 1, received.size)
        assertEquals("未声明键必须在会话侧被丢弃", listOf("cfg.auto"), received.single().second)
        assertTrue("被丢弃的积压不许留痕日志面（静默丢弃即口径）", logs.none { it.contains("cfg.not_declared") })
        session.shutdown()
    }

    @Test
    fun `重注后回调里context_settings读到新值_组长接线判据`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_notify_d.manifest",
            ModManifest(
                packageId = "mod.notify.d",
                displayName = "探针包D",
                settings = listOf(
                    IntSettingDecl("cfg.volume", default = 5L, min = 0L, max = 10L, title = "音量"),
                ),
            ),
        )
        // 回调瞬间把 context.settings 的当前值定格记录（回调返回后快照不再被读）。
        val observed = mutableListOf<Long>()
        val probe = object : EraMod {
            private var contextRef: ModContext? = null
            override fun onLoad(context: ModContext) {
                contextRef = context
            }

            override fun onEnable() = Unit
            override fun onDisable() = Unit
            override fun onUnload() = Unit
            override fun onSettingsChanged(changedKeys: List<String>) {
                observed += contextRef?.settings?.int("cfg.volume") ?: -1L
            }
        }
        val session = checkNotNull(attach(root, listOf("mod.notify.d" to probe)))

        // 模拟玩家改音量 5 → 8：设置页同款写盘入口（saveValues）先落盘、成功后才 enqueue。
        val rt2Dir = File(root.toFile(), "mods").resolve("rt2")
        assertTrue("写盘必须成功（写失败不 enqueue 是设置页接线口径）",
            ModSettingsStore.saveValues(rt2Dir, "mod.notify.d", mapOf("cfg.volume" to "8")))
        ModSettingsNotifyChannel.enqueue("mod.notify.d", listOf("cfg.volume"))
        drainAndDeliver(session)

        assertEquals("回调必须恰好到达一次", 1, observed.size)
        assertEquals(
            "回调里 context.settings 必须已是盘上新值 8（rebindSettings 重注兑现 DX-A1 判据 1）",
            8L,
            observed.single(),
        )
        session.shutdown()
    }

    @Test
    fun `已收口会话与无会话都是no op不崩`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_notify_c.manifest",
            ModManifest(
                packageId = "mod.notify.c",
                displayName = "探针包C",
                settings = listOf(BoolSettingDecl("cfg.auto", default = true, title = "自动")),
            ),
        )
        val received = mutableListOf<Pair<String, List<String>>>()
        val session = checkNotNull(attach(root, listOf("mod.notify.c" to RecordingMod("mod.notify.c", received))))

        // 无会话：drain 结果直接丢弃（GameHost 侧 safe-call），不得抛。
        ModSettingsNotifyChannel.enqueue("mod.notify.c", listOf("cfg.auto"))
        drainAndDeliver(null)
        assertTrue("无会话 = 什么也不发", received.isEmpty())

        // 会话已收口：直接调会话入口必须 no-op（shutDown 闸），不抛、不回调。
        session.shutdown()
        session.notifySettingsChanged("mod.notify.c", listOf("cfg.auto"))
        assertTrue("收口后不得再收回调", received.isEmpty())

        // 收口清场：GameHost 退出清场的 clear 行让积压不漏进下一局。
        ModSettingsNotifyChannel.enqueue("mod.notify.c", listOf("cfg.auto"))
        ModSettingsNotifyChannel.clear()
        assertTrue("clear 后积压必须为空", ModSettingsNotifyChannel.drain().isEmpty())
    }

    @Test
    fun `通道drain取走即清并按包去重保序`() {
        ModSettingsNotifyChannel.enqueue("pkg.a", listOf("k2", "k1"))
        ModSettingsNotifyChannel.enqueue("pkg.a", listOf("k1", "k3"))
        ModSettingsNotifyChannel.enqueue("pkg.b", listOf("kb"))
        assertEquals(
            "同包合并去重、包序 = 首投递序",
            listOf("pkg.a" to listOf("k2", "k1", "k3"), "pkg.b" to listOf("kb")),
            ModSettingsNotifyChannel.drain(),
        )
        assertEquals("取走即清（二次 drain 必为空）", emptyList<Pair<String, List<String>>>(), ModSettingsNotifyChannel.drain())
        ModSettingsNotifyChannel.enqueue("pkg.c", listOf("kc"))
        assertEquals("计数观察面", 1, ModSettingsNotifyChannel.pendingPackageCount())
        ModSettingsNotifyChannel.clear()
        assertTrue(ModSettingsNotifyChannel.drain().isEmpty())
    }

    // ---- 接线源码钉（与 ModUiSettingsEntryWireTest 同族：只钉调用点存在与顺序） ----

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试过 ${candidates.joinToString()}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `页边界drain排在会话节拍之后 收口clear排在会话置空之后`() {
        val gameHost = sourceOf("GameHost.kt")
        val boundary = gameHost.indexOf("modRuntimeSession?.onTurnBoundary()")
        val drain = gameHost.indexOf("ModSettingsNotifyChannel.drain()")
        assertTrue("页边界必须接线 drain（GameHost.onTurnBoundary）", boundary >= 0)
        assertTrue("drain 必须排在会话节拍之后（mod 看到推进后的世界）", drain > boundary)
        val cleared = gameHost.indexOf("modRuntimeSession = null")
        val clear = gameHost.indexOf("ModSettingsNotifyChannel.clear()")
        assertTrue("退出清场必须接线 clear", clear > cleared)
    }

    @Test
    fun `设置页只在写盘成功后入队 失败路径不投递`() {
        val source = sourceOf("SettingsActivity.kt")
        val persist = source.substringAfter("val persist: (String, String) -> Unit")
            .substringBefore("\n    Text(")
        val enqueue = persist.indexOf("ModSettingsNotifyChannel.enqueue(mod.packageId, listOf(key))")
        val okBranch = persist.indexOf("if (ok)")
        val elseBranch = persist.indexOf("} else {")
        assertTrue("persist 必须接线通道入队", enqueue >= 0)
        assertTrue("入队必须落在写盘成功分支内：if (ok) 之后", okBranch >= 0 && enqueue > okBranch)
        assertTrue("入队必须落在 else（失败提示）之前", elseBranch > enqueue)
        assertEquals("persist 切片内只许一处投递点", 1, persist.split("ModSettingsNotifyChannel.enqueue").size - 1)
        // 旧断言「全文件只许一处投递点」已不成立：Lane 之后「恢复默认」控件是第二条**合法**的写盘
        // 成功路径（`ModSettingsStore.resetToDefaults` 删除成功才把旧键投递，KDoc 明写失败只提示）。
        // 所以口径收紧为「每条写盘路径各一处」：persist 切片 1 处 + reset 控件成功分支 1 处 = 全文件恰 2 处，
        // 任何第三条（例如新的失败路径也投递）仍会红。
        val reset = source.substringAfter("private fun ModSettingResetControl(")
            .substringBefore("\n@Composable")
        val resetFailed = reset.indexOf("if (!ModSettingsStore.resetToDefaults(rt2Dir, packageId))")
        val resetElse = reset.indexOf("} else {")
        val resetEnqueue = reset.indexOf("ModSettingsNotifyChannel.enqueue(packageId, oldKeys)")
        assertTrue("恢复默认必须接线通道入队", resetEnqueue >= 0)
        assertTrue(
            "恢复默认的失败分支只许提示、不许投递（if → $resetFailed else → $resetElse 入队 $resetEnqueue）",
            resetFailed >= 0 && resetElse > resetFailed,
        )
        assertTrue("入队必须落在删除成功分支内（} else { 之后）", resetEnqueue > resetElse)
        assertEquals("恢复默认切片内只许一处投递点", 1, reset.split("ModSettingsNotifyChannel.enqueue").size - 1)
        assertEquals(
            "全文件只许两条写盘成功路径各一处投递点（persist + 恢复默认），第三条出现即红",
            2,
            source.split("ModSettingsNotifyChannel.enqueue").size - 1,
        )
    }
}
