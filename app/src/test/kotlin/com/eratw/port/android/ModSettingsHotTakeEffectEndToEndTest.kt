package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modfamily.FamilyDeclarations
import com.eratw.port.modfamily.FamilyMod
import com.eratw.port.modfamily.generated.FamilyFacade
import com.eratw.port.modfamily.generated.FamilyView
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * per-mod 设置热生效的**生产装配链**端到端切片（task-90 / Lane CS）。
 *
 * 本文件把三波产物接成一条真链，每一环都走生产入口而不是测试替身：
 * 1. 设置页写盘 = [ModSettingsStore.saveValues]（`SettingsActivity.kt` 的 persist 同款入口），
 *    落盘格式就是 `mods/rt2/<pkg>.settings` 真文件；
 * 2. 热通知 = [ModSettingsNotifyChannel.enqueue] + `GameHost.kt` 页边界同序
 *    （`modRuntimeSession?.onTurnBoundary()` 之后再 drain → `notifySettingsChanged`）；
 * 3. 业务拍 = [ModRuntimeAssembly.attach] 缺省构造缝装配的 `GameTickDriver` 真节拍，
 *    经声明快照里的 `family.growth` 任务驱动 [FamilyMod.advanceOneDay]，行为变化读实体 age。
 *
 * 判据（对应 KDoc「读值在业务方法体内现读、不在 onLoad 缓存」）：
 * - 缺键起步回落**声明默认** 1（不是 0）；
 * - 热通知之前的那一拍仍读旧快照（rebind 处是负载，不是摆设）；
 * - 热通知后的下一拍真读到新值 5 ⇒ age 增量 1→5；
 * - 越界坏值回落声明默认且不崩；
 * - `showTitles` 翻转后下一次业务调用（`adoptChild`）写空串标题。
 *
 * 未注入设置面（UOE）在 app 装配链上不可构造：[ModRuntimeAssembly.attach] 在 `install` 之前
 * 无条件注入按包快照（`ModRuntimeAssembly.kt` 的 `host.settings = ModSettingsStore.snapshotsFor`
 * 行）⇒ 「未注入」只存在于宿主侧直调；其 fail-closed 行为由
 * `mod-family` 的 `FamilyModTest` 两条用例钉（`advanceOneDay` 与 `adoptChild` 各一）。
 */
class ModSettingsHotTakeEffectEndToEndTest {

    private val logs: MutableList<String> = mutableListOf()

    /** 生产形状游戏根：`mods/rt2/` 下投递**全部**注册载体的内置 manifest。 */
    private fun productionGameRoot(): Path {
        val root = Files.createTempDirectory("mod-settings-effect")
        val rt2 = root.resolve("mods").resolve("rt2")
        Files.createDirectories(rt2)
        // ModContextImpl 构造期要求 packageId 在声明快照内 ⇒ 生产「全部随包 manifest 都已投递」
        // 的形状 = 逐 in-registry 载体把 `app/src/main/assets/mods/rt2/` 的 manifest 复制过去。
        for (packageId in ModCarrierRegistry.providers(noMarkersRoot()).map { it.first }) {
            val manifest = File("src/main/assets/mods/rt2/$packageId.manifest")
            assertTrue("内置 RT2 manifest 缺席（先跑 generateBuiltinRt2Manifest）：${manifest.path}", manifest.isFile)
            Files.copy(manifest.toPath(), rt2.resolve(manifest.name))
        }
        return root
    }

    /** 无停用标记的游戏根（目录不存在 ⇒ 停用集为空 ⇒ providers 走全量分支）。 */
    private fun noMarkersRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "eratw-settings-effect-" + System.nanoTime())

    private fun rt2Dir(root: Path): File = root.resolve("mods").resolve("rt2").toFile()

    /** 生产装配入口：直打 [ModRuntimeAssembly.attach]（缺省 newHost = 宿主编排），providers 取生产注册表。 */
    private fun attach(root: Path, store: VariableStore, providers: List<Pair<String, EraMod>>): ModRuntimeSession =
        checkNotNull(
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

    private fun familyOf(providers: List<Pair<String, EraMod>>): FamilyMod =
        providers.first { it.first == FamilyDeclarations.PACKAGE_ID }.second as FamilyMod

    /** `GameHost.kt` 页边界镜像：会话节拍之后 drain 积压并转发（与会话缺席即丢弃同口径）。 */
    private fun deliverSettings(session: ModRuntimeSession) {
        ModSettingsNotifyChannel.drain().forEach { (packageId, keys) ->
            session.notifySettingsChanged(packageId, keys)
        }
    }

    /** 时间权威在 ERB/变量层：测试只写 DAY 给节拍读，不假设引擎会写它。 */
    private fun setDay(store: VariableStore, day: Long) {
        store.writeInt("DAY", longArrayOf(0L), day)
    }

    private fun viewOf(session: ModRuntimeSession, child: EntityId): FamilyView {
        val context = checkNotNull(session.host.contextOf(FamilyDeclarations.PACKAGE_ID)) { "激活的 mod 必须有 context" }
        return FamilyFacade(context.entities).of(child)
    }

    /**
     * 设置页 `persist` 的生产镜像：`saveValues` 写盘成功才 `enqueue` 热通知（失败不投递），
     * 顺序与 `SettingsActivity.kt` 的 persist 分支一致；写盘格式就是真 `.settings` 文件。
     */
    private fun persistSetting(root: Path, key: String, raw: String) {
        val ok = ModSettingsStore.saveValues(rt2Dir(root), FamilyDeclarations.PACKAGE_ID, mapOf(key to raw))
        assertTrue("设置写盘必须成功：$key=$raw", ok)
        ModSettingsNotifyChannel.enqueue(FamilyDeclarations.PACKAGE_ID, listOf(key))
    }

    @Test
    fun `真装配下改growthPerDay_下一个业务拍读到新值_年龄增量真变`() {
        val root = productionGameRoot()
        val store = VariableStore()
        val providers = ModCarrierRegistry.providers(noMarkersRoot())
        val session = attach(root, store, providers)
        try {
            assertTrue(
                "生产注册表 + 内置资产必须让 family 真装成功；日志=$logs",
                logs.any { it == "mod.install pkg=${FamilyDeclarations.PACKAGE_ID} active=true" },
            )
            val family = familyOf(providers)
            val child = family.adoptChild("甲", null)
            assertEquals("新子嗣 age 起点 0", 0L, viewOf(session, child).age)

            // 拍 1：盘上还没有 .settings ⇒ 缺键回落声明默认 1（不是 0）。
            setDay(store, 0L)
            session.onTurnBoundary()
            assertEquals("缺键必须回落声明默认 growthPerDay=1（不是 0）", 1L, viewOf(session, child).age)

            // 玩家改设置：设置页同款写盘入口（写成功才 enqueue，失败不投递）。
            persistSetting(root, FamilyDeclarations.GROWTH_PER_DAY_KEY, "5")

            // 拍 2：页边界先跑业务拍，再 drain 热通知 ⇒ 这一拍仍读旧快照 1（rebind 是负载）。
            setDay(store, 1L)
            session.onTurnBoundary()
            assertEquals("热通知兑现前的业务拍仍读旧快照", 2L, viewOf(session, child).age)
            deliverSettings(session)

            // 拍 3：热通知后的下一个业务拍必须真读到新值 5。
            setDay(store, 2L)
            session.onTurnBoundary()
            assertEquals("热通知后下一拍必须真读新值 5（1→5 增量真变）", 7L, viewOf(session, child).age)
            assertEquals("三拍都由真节拍跑过", 3L, family.state.growthTicks)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `真装配下翻转showTitles_下一次adoptChild标题真变`() {
        val root = productionGameRoot()
        val providers = ModCarrierRegistry.providers(noMarkersRoot())
        val session = attach(root, VariableStore(), providers)
        try {
            val family = familyOf(providers)
            val kept = family.adoptChild("甲", null)
            assertEquals("声明默认 showTitles=true 保留称号", "甲", viewOf(session, kept).title)

            persistSetting(root, FamilyDeclarations.SHOW_TITLES_KEY, "false")
            deliverSettings(session)

            val blanked = family.adoptChild("乙", null)
            assertEquals("翻转后下一次业务调用的标题必须写空串", "", viewOf(session, blanked).title)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `越界坏值回落声明默认1且不崩`() {
        val root = productionGameRoot()
        val store = VariableStore()
        val providers = ModCarrierRegistry.providers(noMarkersRoot())
        val session = attach(root, store, providers)
        try {
            val child = familyOf(providers).adoptChild("甲", null)
            // 设置页写盘面不做范围校验（它只落字符串），范围判定在快照 resolveText：
            // 99 越过声明 range 1..10 ⇒ 静默回落声明默认，不抛不崩。
            persistSetting(root, FamilyDeclarations.GROWTH_PER_DAY_KEY, "99")
            val persisted = ModSettingsStore.read(rt2Dir(root), FamilyDeclarations.PACKAGE_ID)
            assertEquals(
                "坏值必须以真落盘格式写到盘上（不是测试手拼非法内容）",
                "99",
                persisted[FamilyDeclarations.GROWTH_PER_DAY_KEY],
            )
            assertNotNull("设置面在装配期已注入", session.host.contextOf(FamilyDeclarations.PACKAGE_ID))
            deliverSettings(session)

            setDay(store, 0L)
            session.onTurnBoundary()
            assertEquals("越界坏值回落声明默认 1（不是 0、也不是 99）", 1L, viewOf(session, child).age)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `真装配下恢复默认_下一个业务拍回落声明默认`() {
        val root = productionGameRoot()
        val store = VariableStore()
        val providers = ModCarrierRegistry.providers(noMarkersRoot())
        val session = attach(root, store, providers)
        try {
            val family = familyOf(providers)
            val child = family.adoptChild("甲", null)

            // 前置：玩家把 growthPerDay 改成 5 并经页边界热生效。
            persistSetting(root, FamilyDeclarations.GROWTH_PER_DAY_KEY, "5")
            deliverSettings(session)
            setDay(store, 0L)
            session.onTurnBoundary()
            assertEquals("前置：改值后第一拍按 5 增长", 5L, viewOf(session, child).age)

            // 恢复默认 = 设置页 ModSettingResetControl 的生产镜像：先取旧键集合，再整包删侧车，
            // 删除成功才投递热通知（键集合来自删前 read）。
            val rt2 = rt2Dir(root)
            val oldKeys = ModSettingsStore.read(rt2, FamilyDeclarations.PACKAGE_ID).keys.toList()
            assertTrue("恢复默认必须成功", ModSettingsStore.resetToDefaults(rt2, FamilyDeclarations.PACKAGE_ID))
            assertTrue("旧键集合要含被改的键", oldKeys.contains(FamilyDeclarations.GROWTH_PER_DAY_KEY))
            assertTrue("侧车必须整份删掉", ModSettingsStore.read(rt2, FamilyDeclarations.PACKAGE_ID).isEmpty())
            ModSettingsNotifyChannel.enqueue(FamilyDeclarations.PACKAGE_ID, oldKeys)

            // 拍 2：页边界先跑业务拍、再 drain ⇒ 这一拍仍读旧快照 5（rebind 是负载）。
            setDay(store, 1L)
            session.onTurnBoundary()
            assertEquals("热通知兑现前的业务拍仍读旧快照 5", 10L, viewOf(session, child).age)
            deliverSettings(session)

            // 拍 3：热通知后的下一个业务拍必须回落作者声明的默认值 1（年龄只 +1）。
            setDay(store, 2L)
            session.onTurnBoundary()
            assertEquals("恢复默认后下一个业务拍回落声明默认 1", 11L, viewOf(session, child).age)
        } finally {
            session.shutdown()
        }
    }
}
