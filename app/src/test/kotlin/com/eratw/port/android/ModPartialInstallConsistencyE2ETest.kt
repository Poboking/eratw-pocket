package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.fn.entityRuntimeOf
import com.eratw.port.core.mod.FunctionHookWiring
import com.eratw.port.core.mod.ModAudioWiring
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModEventWiring
import com.eratw.port.core.mod.RegisteredFaces
import com.eratw.port.core.mod.registeredFacesOf
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.IntSettingDecl
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 部分装载失败后的一致性审计（task-116 立项，task-119 翻转 D1/D2 现状锁）。
 *
 * 口径：分两轮。
 * - task-116 只测量，把当时的「失败包半活」写成 `现状锁` 断言；
 * - task-119（本文件当前形态）修 D1（install 失败路径与 shutdown 的 storage 激活集回退）与
 *   D2（失败包的 archetype 不注入实体运行时），对应用例已改成「应然」断言并改名。
 *
 * 仍标 `现状锁` 的是**本轮不修**的项：D3 失败包 entry 残留（诊断面 / shutdown 补调的载体，
 * 不删）、以及诊断跨会话残留、声明快照冻结两条观察项。
 * D4（同会话重装失败包被重复装载闸硬拒）已由 task-123 / Lane DZ 落地产品裁决：本次调用前
 * 已登记且已回滚失败的 entry 允许同会话替换重装（`ModHostReinstall.openReinstallSlot`），
 * 对应用例随之改成「替换后真装载」的应然断言并改名；其余重复装载仍硬失败。
 *
 * 载体：真 `ModRuntimeAssembly.attach` + 真声明快照（mods/rt2 下的 manifest），
 * 用 [ProbeMod] 在校验点注入 onLoad / onEnable 失败。
 */
class ModPartialInstallConsistencyE2ETest {

    private val logs: MutableList<String> = mutableListOf()

    @Before
    fun setUp() {
        logs.clear()
        ModManageDiagnostics.reset()
    }

    @After
    fun tearDown() {
        ModManageDiagnostics.reset()
    }

    // ------------------------------------------------------------------
    // ① 两包 B.onLoad 抛后 A 的真实世界状态
    // ------------------------------------------------------------------

    @Test
    fun `B 的 onLoad 抛后 A 的世界状态完整且已启用`() {
        val root = tempGameRoot(PKG_A, PKG_B)
        val store = VariableStore()
        val a = ProbeMod(PKG_A, register = true)
        val b = ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE))
        val session = attach(root, store, listOf(PKG_A to a, PKG_B to b))
        try {
            assertNotNull("A 的 context 应仍在 entries 里", session.host.contextOf(PKG_A))
            assertNull("A 不应带失败", session.host.failureOf(PKG_A))
            assertTrue("A 的存储分段应保持激活", session.storage.isActive(PKG_A))
            assertEquals("只有 A 是活跃包（失败包不进 activePackages）", setOf(PKG_A), session.activePackages)

            assertEquals("A.onLoad 恰好一次", 1, a.loadCount)
            assertEquals("A.onEnable 恰好一次", 1, a.enableCount)
            assertEquals("A 未被卸载", 0, a.disableCount)
            assertEquals("A 未被卸载", 0, a.unloadCount)

            val faces = session.host.registeredFacesOf(PKG_A)
            assertEquals("A 的 hook 注册面完整保留", 1, faces.hooks)
            assertEquals("A 的 events 三桶都在", mapOf("PRI" to 1, "NORMAL" to 1, "LATER" to 1, "ONLY" to 0), faces.events)
            assertEquals("A 的 interaction hook 在", 1, faces.interactions)
            assertEquals("A 的字符串在", 1, faces.strings)
            assertEquals("A 的声明设置项在（isLivePackage 才读数）", 1, faces.settings)
            assertEquals("A 的图片注册在", 1, faces.images)

            assertTrue("A 的成功行应上日志", logs.any { it == "mod.install pkg=$PKG_A active=true" })
            assertTrue(
                "B 的失败行应上日志",
                logs.any { it.startsWith("mod.install pkg=$PKG_B active=false failure=IllegalStateException:") },
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `B 的 onLoad 抛后 B 自身注册面被回滚为零`() {
        val root = tempGameRoot(PKG_A, PKG_B)
        val store = VariableStore()
        val a = ProbeMod(PKG_A, register = true)
        val b = ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE))
        val session = attach(root, store, listOf(PKG_A to a, PKG_B to b))
        try {
            assertEquals("失败包的注册面必须被 revokeEntry 撤干净", RegisteredFaces.EMPTY, session.host.registeredFacesOf(PKG_B))
            assertEquals("B.onLoad 已进入（失败发生在 onLoad 之后段）", 1, b.loadCount)
            assertEquals("B.onEnable 不应被调", 0, b.enableCount)
            assertEquals("安装期失败不补 onDisable（由 shutdown 补）", 0, b.disableCount)
            assertEquals("安装期失败不补 onUnload（由 shutdown 补）", 0, b.unloadCount)
            assertFalse("失败包不应算 live", session.activePackages.contains(PKG_B))

            // 现状锁：失败包 entry 仍留在 entries 里（contextOf/failureOf 可读），
            // 这是诊断面（"上次启动失败"）与 shutdown 补调的载体；修复后若 entry 不残留，本断言应翻转。
            assertNotNull("现状锁：失败包 entry 残留，contextOf 可读", session.host.contextOf(PKG_B))
            val failure = session.host.failureOf(PKG_B)
            assertNotNull("现状锁：failureOf 可读失败", failure)
            assertEquals("失败原因原样保留", ON_LOAD_FAILURE, failure?.message)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `B 装配失败后存储激活集不再包含它`() {
        val root = tempGameRoot(PKG_A, PKG_B)
        val store = VariableStore()
        val session = attach(
            root,
            store,
            listOf(
                PKG_A to ProbeMod(PKG_A, register = true),
                PKG_B to ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE)),
            ),
        )
        try {
            // 应然（task-119 D1）：install 的 onLoad/onEnable 失败 catch 与构造段 catch（:346-357）
            // 同形回退 —— 仅当本次调用前该包非激活时 deactivate(packageId)。
            // 对称先例：读档拒载 rejectEntryOnRestore（ModRuntimeHost.kt:852）与版本门同一操作。
            // deactivate 只移激活位、数据整段留下 ⇒ 失败包按孤儿段保留（禁用≠删除）。
            assertFalse("失败包的存储分段不得仍处于激活集", session.storage.isActive(PKG_B))
            assertTrue(
                "失败包应落 inactivePackages（孤儿口径：数据保留、本会话不启用）",
                session.storage.inactivePackages().contains(PKG_B),
            )
            assertTrue("失败隔离不误伤：A 的存储分段仍应激活", session.storage.isActive(PKG_A))
            assertTrue("deactivate 不删数据：B 的分段仍在容器里", session.storage.packages().contains(PKG_B))
        } finally {
            session.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // ② shutdown 零收口
    // ------------------------------------------------------------------

    @Test
    fun `shutdown 后注册面与引擎槽零收口且晚到派发走旁路`() {
        val root = tempGameRoot(PKG_A, PKG_B)
        val store = VariableStore()
        val a = ProbeMod(PKG_A, register = true)
        val b = ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE))
        val session = attach(root, store, listOf(PKG_A to a, PKG_B to b))
        // 装配期：A 的 PREFIX hook 对 TARGET 做 Replace(999)，派发应拿到 999 而不是原函数值。
        assertEquals(999L, dispatchTarget(store) { 1L })
        assertTrue("装配期 hook 引擎槽应绑着本会话 pipeline", hookPipelineBound(store))
        assertTrue("装配期事件引擎应已挂上 A 的 EVENT 处理器", ModEventWiring.hasHandlers(store, EVENT_KEY))
        assertNotNull("装配期音频槽应已挂上", ModAudioWiring.sinkOf(store))

        session.shutdown()

        assertEquals("shutdown 后 A 的注册面必须归零", RegisteredFaces.EMPTY, session.host.registeredFacesOf(PKG_A))
        assertEquals("shutdown 后 B 的注册面必须归零", RegisteredFaces.EMPTY, session.host.registeredFacesOf(PKG_B))
        assertFalse("shutdown 后 hook 引擎槽必须解绑 pipeline（holder 留槽属设计）", hookPipelineBound(store))
        assertFalse("shutdown 后事件引擎应解绑", ModEventWiring.hasHandlers(store, EVENT_KEY))
        assertNull("shutdown 后音频槽应解绑", ModAudioWiring.sinkOf(store))
        assertNull("shutdown 后投射桥应解绑", store.charaSlotProjector)
        assertEquals("shutdown 后晚到派发必须完全旁路（不跑任何 mod hook）", 1L, dispatchTarget(store) { 1L })
        assertTrue("A 在 shutdown 时收到 onDisable", a.disableCount >= 1)
        assertTrue("A 在 shutdown 时收到 onUnload", a.unloadCount >= 1)
        assertTrue(
            "shutdown 收口日志应报告装上来的包数=1",
            logs.contains("mod.shutdown 完成 mods=1"),
        )

        // 幂等：二次 shutdown 不得重复调生命周期（幂等由 session.shutDown 短路保证）。
        val disableBefore = a.disableCount
        val unloadBefore = a.unloadCount
        session.shutdown()
        assertEquals("二次 shutdown 不得二次 onDisable", disableBefore, a.disableCount)
        assertEquals("二次 shutdown 不得二次 onUnload", unloadBefore, a.unloadCount)
    }

    @Test
    fun `shutdown 后存储激活集清空且数据整段保留`() {
        val root = tempGameRoot(PKG_A, PKG_B)
        val store = VariableStore()
        val session = attach(
            root,
            store,
            listOf(
                PKG_A to ProbeMod(PKG_A, register = true),
                PKG_B to ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE)),
            ),
        )
        session.shutdown()
        // 应然（task-119 D1）：ModRuntimeHost.shutdown 收尾对全部 entries 统一 deactivate。
        // 与读档缝 rejectEntryOnRestore（:852）同一幂等操作（只 activePackages -= packageId），
        // 重复 deactivate 是 no-op、不清数据；会话结束后这些段按孤儿口径随档保留。
        assertFalse("shutdown 后成功包不得仍激活", session.storage.isActive(PKG_A))
        assertFalse("shutdown 后失败包不得仍激活", session.storage.isActive(PKG_B))
        assertTrue(
            "shutdown 后两包都应在 inactivePackages（孤儿口径）",
            session.storage.inactivePackages().containsAll(listOf(PKG_A, PKG_B)),
        )
        assertTrue(
            "deactivate 不删数据：两包分段仍在容器里",
            session.storage.packages().containsAll(listOf(PKG_A, PKG_B)),
        )
    }

    @Test
    fun `shutdown 对安装期失败包补调 onDisable 与 onUnload 并打回滚态诊断行`() {
        val root = tempGameRoot(PKG_A, PKG_B)
        val b = ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE))
        val session = attach(
            root,
            VariableStore(),
            listOf(PKG_A to ProbeMod(PKG_A, register = true), PKG_B to b),
        )
        assertNotNull("前置：失败包 entry 残留（现状锁的另一面）", session.host.contextOf(PKG_B))
        session.shutdown()
        assertTrue("现状锁：shutdown 会把失败包也当条目遍历并补调 onDisable", b.disableCount == 1)
        assertTrue("现状锁：shutdown 会补调失败包 onUnload", b.unloadCount == 1)
        assertTrue(
            "日志应打安装期失败回滚态行",
            logs.contains("mod.shutdown pkg=$PKG_B 安装期失败(回滚态) IllegalStateException: $ON_LOAD_FAILURE"),
        )
    }

    // ------------------------------------------------------------------
    // ④ onLoad 成功 + onEnable 抛
    // ------------------------------------------------------------------

    @Test
    fun `onEnable 抛后 onLoad 注册面已回滚且诊断面记 onEnable 失败`() {
        val root = tempGameRoot(PKG_C)
        val store = VariableStore()
        val c = ProbeMod(PKG_C, register = true, enableFailure = IllegalStateException(ON_ENABLE_FAILURE))
        val session = attach(root, store, listOf(PKG_C to c))
        try {
            assertEquals("onEnable 抛后注册面必须归零", RegisteredFaces.EMPTY, session.host.registeredFacesOf(PKG_C))
            assertTrue("C 不在 activePackages", session.activePackages.isEmpty())
            assertEquals("C.onLoad 已进入", 1, c.loadCount)
            assertEquals("C.onEnable 已进入（异常在 onEnable 内抛）", 1, c.enableCount)
            assertEquals("安装期失败不补 onDisable（由 shutdown 补）", 0, c.disableCount)
            assertFalse("onEnable 失败后事件引擎不应留处理器", ModEventWiring.hasHandlers(store, EVENT_KEY))
            assertEquals(
                "诊断摘要应记 onEnable 失败",
                "IllegalStateException: $ON_ENABLE_FAILURE",
                ModManageDiagnostics.snapshot()[PKG_C],
            )
            // 应然（task-119 D1）：onEnable 失败同样走生命周期段 catch 的 storage 对称回退。
            assertFalse("onEnable 失败后存储分段不得仍激活", session.storage.isActive(PKG_C))
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `onEnable 抛后 shutdown 收口且存储段退回非激活`() {
        val root = tempGameRoot(PKG_C)
        val store = VariableStore()
        val c = ProbeMod(PKG_C, register = true, enableFailure = IllegalStateException(ON_ENABLE_FAILURE))
        val session = attach(root, store, listOf(PKG_C to c))
        session.shutdown()
        assertEquals("shutdown 后 C 的注册面归零", RegisteredFaces.EMPTY, session.host.registeredFacesOf(PKG_C))
        assertTrue("失败包被 shutdown 补调 onDisable", c.disableCount >= 1)
        assertTrue("失败包被 shutdown 补调 onUnload", c.unloadCount >= 1)
        assertTrue("日志应打安装期失败回滚态行", logs.any { it.startsWith("mod.shutdown pkg=$PKG_C 安装期失败(回滚态)") })
        // 应然（task-119 D1）：失败包的段在 install 失败时已回退，shutdown 再统一收口（幂等）。
        assertFalse("shutdown 后 C 的存储分段不得仍激活", session.storage.isActive(PKG_C))
        assertTrue("C 应在 inactivePackages（孤儿口径）", session.storage.inactivePackages().contains(PKG_C))
    }

    // ------------------------------------------------------------------
    // ③ 失败后再 install / 重装配
    // ------------------------------------------------------------------

    @Test
    fun `同 host 运行期可追加已进声明快照但 attach 时未装载的包`() {
        // 盘上 manifest 在 attach 前就存在 ⇒ 声明快照含 D；attach 时没给 D 的 provider ⇒ 只是「已声明未装载」。
        val root = tempGameRoot(PKG_A, PKG_B, PKG_D)
        val store = VariableStore()
        val session = attach(
            root,
            store,
            listOf(
                PKG_A to ProbeMod(PKG_A, register = true),
                PKG_B to ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE)),
            ),
        )
        try {
            val d = ProbeMod(PKG_D, register = true)
            val states = session.host.install(listOf(PKG_D to d))
            assertEquals("追加装载应返回一条状态", 1, states.size)
            assertTrue("D 应装成功（声明快照里有它）", states[0].active)
            assertNull("D 不应带失败", states[0].failure)
            assertEquals("D.onLoad 被调一次", 1, d.loadCount)
            assertEquals("D.onEnable 被调一次", 1, d.enableCount)
            assertEquals("D 的注册面应完整挂上", 1, session.host.registeredFacesOf(PKG_D).hooks)
            assertTrue("D 的存储分段被激活", session.storage.isActive(PKG_D))
            // 观察项：`mod.install pkg=` 汇总行与 ModManageDiagnostics 上报点是 attach（ModRuntimeAssembly
            // 的 report 循环），直调 host.install 的追加装载不走这两个面 ⇒ 运行期热装对玩家诊断不可见。
            assertFalse("直调 host.install 不写 ModManageDiagnostics", ModManageDiagnostics.snapshot().containsKey(PKG_D))
            assertFalse("直调 host.install 不打 mod.install 汇总行", logs.any { it.startsWith("mod.install pkg=$PKG_D ") })
            // 观察项：session.activePackages 是 attach 时的不可变快照，不含追加装载的 D。
            assertEquals("attach 期的 activePackages 快照不随追加装载更新", setOf(PKG_A), session.activePackages)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `现状锁 - 同 host 运行期追加未进声明快照的包被 fail-closed 拒绝`() {
        val root = tempGameRoot(PKG_A, PKG_B)
        val store = VariableStore()
        val session = attach(
            root,
            store,
            listOf(
                PKG_A to ProbeMod(PKG_A, register = true),
                PKG_B to ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE)),
            ),
        )
        // attach 之后才落盘：声明快照在 attach 时已冻结，不会再扫盘。
        writeDeclManifest(root, PKG_D)
        try {
            val d = ProbeMod(PKG_D, register = true)
            val states = session.host.install(listOf(PKG_D to d))
            assertEquals("同 host 追加新包应返回一条失败态", 1, states.size)
            assertFalse("D 不应被激活", states[0].active)
            val failure = states[0].failure
            assertNotNull("D 应带 fail-closed 原因", failure)
            assertEquals(
                "失败类应为 IllegalArgumentException（声明快照不一致）",
                IllegalArgumentException::class.java,
                failure?.javaClass,
            )
            assertTrue(
                "失败原因应点名声明快照缺该包：${failure?.message}",
                failure?.message?.contains("声明快照不含 packageId=$PKG_D") == true,
            )
            assertNull("D 不应留下 context", session.host.contextOf(PKG_D))
            assertNull("D 不应留下 failure 记录在 entries", session.host.failureOf(PKG_D))
            assertFalse("D 的存储分段不得被激活", session.storage.isActive(PKG_D))
            assertEquals("D 的生命周期一次都没进", 0, d.loadCount)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `同 host 重装已失败包走同会话替换并真装载`() {
        val root = tempGameRoot(PKG_A, PKG_B)
        val store = VariableStore()
        val b = ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE))
        val session = attach(root, store, listOf(PKG_A to ProbeMod(PKG_A, register = true), PKG_B to b))
        try {
            val retry = ProbeMod(PKG_B, register = true)
            // 旧断言「重复装载闸硬拒（IllegalArgumentException + retry.loadCount==0 + 注册面为空）」
            // 已被 task-123 / Lane DZ 的产品裁决取替：**本次调用前**已登记且已回滚失败的 entry 允许
            // 同会话替换（ModHostReinstall.kt:34-47 幂等回滚后摘旧条目、再走正常装载路径，storage
            // 段重新激活同一份数据）。故这里钉新语义：不再抛、真装载、读数归位；批内重复 / 装载中 /
            // 已收口三种重复装载的硬失败语义仍由 core-vm 既有回归用例锁死。
            val states = session.host.install(listOf(PKG_B to retry))
            assertEquals("替换装载只回一条状态", 1, states.size)
            assertTrue("替换后该包应处于激活态", states[0].active)
            assertNull("成功装载不留失败读数：${states[0].failure}", states[0].failure)
            assertEquals("新实例 onLoad 恰好一次", 1, retry.loadCount)
            assertEquals("新实例 onEnable 恰好一次", 1, retry.enableCount)
            // 会话激活集（ModRuntimeSession.activePackages）只由会话侧重装入口
            // reinstallRolledBack 记账（ModRuntimeAssembly.kt:1488-1521 的 lateActivePackageIds）；
            // 本用例直接调宿主 install，走的是容器自身的替换装载路径，不经会话记账 ⇒ 这里钉
            // 「容器内确有新实例的 context」，激活面另由 storage.isActive / registeredFacesOf 覆盖。
            assertNotNull("替换装载后新实例应留下 context", session.host.contextOf(PKG_B))
            assertNull("旧失败读数被替换清掉", session.host.failureOf(PKG_B))
            val faces = session.host.registeredFacesOf(PKG_B)
            assertEquals("注册面来自新实例且只一份", 1, faces.hooks)
            assertEquals(
                "事件面只一份（旧条目的注册面已幂等撤销）",
                mapOf("PRI" to 1, "NORMAL" to 1, "LATER" to 1, "ONLY" to 0),
                faces.events,
            )
            assertEquals("交互面只一份", 1, faces.interactions)
            assertEquals("文案面只一份", 1, faces.strings)
            assertEquals("图片面只一份", 1, faces.images)
            assertTrue("存储段重新激活（同一份数据，不是新建空段）", session.storage.isActive(PKG_B))
            assertEquals("回滚态旧实例不再被调生命周期", 1, b.loadCount)
            assertEquals("回滚态旧实例不补 onEnable", 0, b.enableCount)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `同 store 重装配不重复累计且注册面计数不翻倍`() {
        val root = tempGameRoot(PKG_A, PKG_B)
        val store = VariableStore()
        val first = attach(
            root,
            store,
            listOf(
                PKG_A to ProbeMod(PKG_A, register = true),
                PKG_B to ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE)),
            ),
        )
        first.shutdown()

        val a2 = ProbeMod(PKG_A, register = true)
        val second = attach(root, store, listOf(PKG_A to a2))
        try {
            assertEquals("重装配应成功并只装 A", setOf(PKG_A), second.activePackages)
            assertEquals("新实例 onLoad 恰好一次", 1, a2.loadCount)
            assertEquals("新实例 onEnable 恰好一次", 1, a2.enableCount)
            val faces = second.host.registeredFacesOf(PKG_A)
            assertEquals("hook 不翻倍", 1, faces.hooks)
            assertEquals("events 不翻倍", mapOf("PRI" to 1, "NORMAL" to 1, "LATER" to 1, "ONLY" to 0), faces.events)
            assertEquals("interaction 不翻倍", 1, faces.interactions)
            assertEquals("strings 不翻倍", 1, faces.strings)
            assertEquals("images 不翻倍", 1, faces.images)
            assertTrue("新会话的存储分段重新激活", second.storage.isActive(PKG_A))
            assertTrue("事件引擎重新挂上", ModEventWiring.hasHandlers(store, EVENT_KEY))
        } finally {
            second.shutdown()
        }
    }

    @Test
    fun `会话边界 - 新会话开始归零后上一会话的失败行不再残留`() {
        val root = tempGameRoot(PKG_A, PKG_B)
        val store = VariableStore()
        attach(
            root,
            store,
            listOf(
                PKG_A to ProbeMod(PKG_A, register = true),
                PKG_B to ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE)),
            ),
        ).shutdown()
        // 进程级 object 在两次会话之间本身不会自己清：真正的边界是「下一个会话装配开始时归零」
        // （生产唯一调用点 = `GameHost.assembleSession` 顶部、早于链装配与 attach；
        // 源码序由 `ModDiagnosticsSessionScopeWireTest` 钉死）。这里模拟那一次归零。
        assertEquals(
            "上一会话结束到下一会话开始之间，读数仍是上一会话的（归零发生在会话开始那一刻）",
            "IllegalStateException: $ON_LOAD_FAILURE",
            ModManageDiagnostics.snapshot()[PKG_B],
        )
        ModManageDiagnostics.reset()
        assertNull("会话开始归零后，上一会话没再装的包不得继续挂在新会话设置页上", ModManageDiagnostics.snapshot()[PKG_B])
        val second = attach(root, store, listOf(PKG_A to ProbeMod(PKG_A, register = true)))
        try {
            assertNotNull("重装配本身必须成功", second.host.contextOf(PKG_A))
            assertTrue("本会话全成功 ⇒ 设置页失败小节应为空（不继承旧账）", ModManageDiagnostics.snapshot().isEmpty())
        } finally {
            second.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 顺序 / 观察项
    // ------------------------------------------------------------------

    @Test
    fun `B 先失败不阻断整链：A 仍完整装配并启用`() {
        val root = tempGameRoot(PKG_A, PKG_B)
        val store = VariableStore()
        val b = ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE))
        val a = ProbeMod(PKG_A, register = true)
        val session = attach(root, store, listOf(PKG_B to b, PKG_A to a))
        try {
            assertEquals("A 仍应是唯一活跃包", setOf(PKG_A), session.activePackages)
            assertEquals("A.onEnable 被调", 1, a.enableCount)
            assertEquals("A 注册面完整", 1, session.host.registeredFacesOf(PKG_A).hooks)
            assertEquals("B 注册面回滚为零", RegisteredFaces.EMPTY, session.host.registeredFacesOf(PKG_B))
            assertEquals(
                "诊断只记 B 失败",
                mapOf(PKG_B to "IllegalStateException: $ON_LOAD_FAILURE"),
                ModManageDiagnostics.snapshot().filterKeys { it == PKG_B },
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `失败包的 archetype 声明不进实体运行时`() {
        val root = tempGameRoot(PKG_A, PKG_B)
        val store = VariableStore()
        val session = attach(
            root,
            store,
            listOf(
                PKG_A to ProbeMod(PKG_A, register = true),
                PKG_B to ProbeMod(PKG_B, register = true, loadFailure = IllegalStateException(ON_LOAD_FAILURE)),
            ),
        )
        try {
            // 应然（task-119 D2）：entityRuntime 回填（ModRuntimeAssembly.kt 的 EntitySeamInputs）
            // 只注入本次 install 报告 active 的包的 archetype；失败包（B）的声明面已被 revoke，
            // 原型表不得再半活。EntityRuntime.archetypes 是 ENTITY_CREATE / QUERY 族唯一的
            // 入口闸（EntityMethods.kt:368 / :386 / :165），查表返回 null 即入口 fail-closed。
            // 正常全成功场景：注入集合与声明快照逐位同序（filterValues 保留 LinkedHashMap 序）。
            val line = logs.firstOrNull { it.startsWith("mod.attach entityRuntime 回填") }
            assertNotNull("应有 entityRuntime 回填日志", line)
            assertTrue("回填计数应只含活跃包（archetypes=1 mods=1）：$line", line!!.contains("archetypes=1 mods=1"))
            val runtime = entityRuntimeOf(store.aux)
            assertNotNull("实体运行时槽应已回填", runtime)
            assertNull("失败包的原型不得可查（ENTITY_CREATE 入口 fail-closed）", runtime!!.archetypes("$PKG_B.trait"))
            assertNotNull("活跃包的原型仍应可查", runtime.archetypes("$PKG_A.trait"))
        } finally {
            session.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    // 在册测试债的最小机械对齐（KI-GLOBAL-DIM 波登记）：dispatchCall 已加 strRet 且返回 ModValue，
    // helper 维持原 Long 口径，内部解包，调用点断言不动。
    private fun dispatchTarget(store: VariableStore, original: () -> Long): Long =
        (FunctionHookWiring.dispatchCall(
            store,
            HOOK_KEY,
            LongArray(0),
            emptyArray(),
            strRet = false,
        ) { _, _ -> ModValue.IntValue(original()) } as ModValue.IntValue).value

    /**
     * hook 引擎槽是否仍绑着 pipeline。
     *
     * `FunctionHookWiring.Handle` 是 internal（app 测试不可见），而它的 holder 按设计恒留槽
     * （FunctionHookWiring.kt:45-74：解绑只把 `pipeline` 置 null）。所以不能拿「槽非空」当判据，
     * 这里用一次字段读取取 `pipeline` 的真实值，与生命周期测试里的 `peek<Handle>().pipeline == null` 同口径。
     */
    private fun hookPipelineBound(store: VariableStore): Boolean {
        val handle = store.aux.peek<Any>(FunctionHookWiring.AUX_SLOT) ?: return false
        val field = handle.javaClass.getDeclaredField("pipeline")
        field.isAccessible = true
        return field.get(handle) != null
    }

    private fun tempGameRoot(vararg packageIds: String): Path {
        val root = Files.createTempDirectory("mod-partial-consistency")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        for (pkg in packageIds) writeDeclManifest(root, pkg)
        return root
    }

    private fun rt2(root: Path): Path = root.resolve("mods").resolve("rt2")

    private fun writeDeclManifest(root: Path, packageId: String) {
        val manifest = ModManifest(
            packageId = packageId,
            hooks = listOf(HookDecl(HOOK_KEY, HookPhase.PREFIX)),
            interactionHooks = listOf(InteractionHookDecl(InteractionPhase.PRE)),
            archetypes = listOf(ArchetypeDecl("$packageId.trait")),
            settings = listOf(IntSettingDecl(KNOB_KEY, 7L, 0L, 10L, title = "Knob")),
        )
        val descriptor = ModDeclarationAdapter.toDescriptor(manifest)
        Files.write(rt2(root).resolve("${packageId.replace('.', '_')}.manifest"), ManifestCodec.encode(descriptor))
    }

    private fun attach(
        root: Path,
        store: VariableStore,
        providers: List<Pair<String, EraMod>>,
    ): ModRuntimeSession {
        logs.clear()
        return ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line ->
                logs += line
                ModManageDiagnostics.record(line)
            },
            modProviders = providers,
        ) ?: error("enabled=true 时真装配入口不应返回 null")
    }

    private class ProbeMod(
        val packageId: String,
        private val register: Boolean,
        private val loadFailure: Throwable? = null,
        private val enableFailure: Throwable? = null,
    ) : EraMod {
        var loadCount: Int = 0
        var enableCount: Int = 0
        var disableCount: Int = 0
        var unloadCount: Int = 0

        override fun onLoad(context: ModContext) {
            loadCount++
            if (register) {
                context.hooks.register(HOOK_KEY, HookPhase.PREFIX) {
                    HookResult.Replace(ModValue.IntValue(DISPATCH_REPLACE))
                }
                context.events.register(EVENT_KEY, EventBucket.PRI) { EventResult.CONTINUE }
                context.events.register(EVENT_KEY, EventBucket.NORMAL) { EventResult.CONTINUE }
                context.events.register(EVENT_KEY, EventBucket.LATER) { EventResult.CONTINUE }
                context.interactions.registerHook(InteractionPhase.PRE) { InteractionResult.CONTINUE }
                context.strings.declare("greet", "你好", "hello", "こんにちは")
                context.ui.registerImage("res/hint.png", "提示", null)
            }
            loadFailure?.let { throw it }
        }

        override fun onEnable() {
            enableCount++
            enableFailure?.let { throw it }
        }

        override fun onDisable() {
            disableCount++
        }

        override fun onUnload() {
            unloadCount++
        }
    }

    private companion object {
        const val PKG_A = "mod.partial.a"
        const val PKG_B = "mod.partial.b"
        const val PKG_C = "mod.partial.c"
        const val PKG_D = "mod.partial.d"
        const val HOOK_KEY = "TARGET"
        const val EVENT_KEY = "EVENT"
        const val KNOB_KEY = "partial.knob"
        const val ON_LOAD_FAILURE = "onLoad 故意失败"
        const val ON_ENABLE_FAILURE = "onEnable 故意失败"
        const val DISPATCH_REPLACE = 999L
    }
}
