package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modfamily.FamilyApi
import com.eratw.port.modfamily.FamilyDeclarations
import com.eratw.port.modfamily.FamilyMod
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 家督继任桥的兑现面与接线源码钉（task-160 / Lane HB）。
 *
 * 行为面直接打生产缝：临时 gameRoot 写**生产同源** manifest 字节（[FamilyDeclarations.manifest]
 * 经 [ModDeclarationAdapter] + [ManifestCodec] 编码），[ModRuntimeAssembly.attach] 装真
 * [FamilyMod]，再按 GameHost 页边界口径 `ModHeirBridge.apply(ModHeirRequests.drain(), api, mode)`
 * 兑现——测的是生产函数，不另写镜像逻辑。
 *
 * 钉五件事：
 * 1. 快照回写 = 会话真值（规则 / 候选 / 已指定都从活 FamilyApi + context.settings 现读）；
 * 2. 反证一：入队本身不越过页边界改快照（旧快照原样保留到下一次 drain）；
 * 3. 反证二：作者面拒绝（非手动规则 / 非法候选）不伪造成功——快照 `pendingRaw` 保持空；
 * 4. 没有活 family 载体（本局未启用 / 未装）时 apply 返 false 并清空快照（设置页整块不渲染）；
 * 5. 接线源码钉：GameHost 页边界 drain 与收口 clear 各一处、ModRuntimeAssembly 只绑一次、
 *    设置页点选/清除只各入队一处。
 */
class ModHeirWireTest {

    private val pkg = FamilyDeclarations.PACKAGE_ID
    private val logs: MutableList<String> = mutableListOf()

    @Before
    fun reset() {
        ModHeirRequests.clear()
        ModHeirFeed.clear()
    }

    @After
    fun cleanup() {
        ModHeirRequests.clear()
        ModHeirFeed.clear()
    }

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-heir")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    /** 写生产同源 manifest 字节：与随包资产走同一条编码链，不手抄声明。 */
    private fun writeFamilyManifest(root: Path) {
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(FamilyDeclarations.manifest))
        Files.write(root.resolve("mods").resolve("rt2").resolve("$pkg.manifest"), bytes)
    }

    private fun attach(root: Path, providers: List<Pair<String, com.eratw.port.modapi.EraMod>>): ModRuntimeSession? =
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

    private fun attachWithFamily(root: Path): ModRuntimeSession =
        checkNotNull(attach(root, listOf(pkg to FamilyMod()))) {
            "attach 返回 null（总闸关？）；日志：${logs.joinToString(" | ")}"
        }

    /** 本会话装进宿主的那件活 FamilyApi；没装成活就把装配日志带进断言消息。 */
    private fun heirApi(session: ModRuntimeSession): FamilyApi =
        checkNotNull((session.carrierModOf(pkg) as? FamilyMod)?.api) {
            "本会话没有活 FamilyMod（activePackages=${session.activePackages}）；" +
                "装配日志：${logs.joinToString(" | ")}"
        }

    /** GameHost.drainModHeirBridge 的规则读数镜像（源钉证明生产里就是这条缝）。 */
    private fun successionMode(session: ModRuntimeSession): String? =
        session.host.contextOf(pkg)?.settings?.enumName(FamilyDeclarations.SUCCESSION_MODE_KEY)

    /** GameHost.drainModHeirBridge 的测试镜像：drain → apply（生产接线由源码钉保证）。 */
    private fun drainAndApply(session: ModRuntimeSession, api: FamilyApi?): Boolean =
        ModHeirBridge.apply(ModHeirRequests.drain(), api, successionMode(session))

    @Test
    fun `快照回写等于会话真值 默认规则 eldest 下候选空且未指定`() {
        val root = tempGameRoot()
        writeFamilyManifest(root)
        val session = attachWithFamily(root)
        try {
            assertTrue("family 载体必须在本会话 activePackages 里", pkg in session.activePackages)
            assertEquals(
                "规则默认值来自 manifest 声明（eldest），不是桥自己猜的",
                FamilyDeclarations.SUCCESSION_ELDEST,
                successionMode(session),
            )
            assertTrue("有活 family 时 apply 必须发布快照", drainAndApply(session, heirApi(session)))
            val snapshot = checkNotNull(ModHeirFeed.read())
            assertEquals(pkg, snapshot.packageId)
            assertEquals(FamilyDeclarations.SUCCESSION_ELDEST, snapshot.mode)
            assertTrue("本局还没有子嗣 ⇒ 候选为空", snapshot.candidates.isEmpty())
            assertNull("没有手动指定 ⇒ pendingRaw 为空", snapshot.pendingRaw)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `反证一 入队本身不越过页边界改快照 旧快照原样保留到 drain`() {
        val root = tempGameRoot()
        writeFamilyManifest(root)
        val session = attachWithFamily(root)
        try {
            val stale = ModHeirSnapshot(pkg, "manual", listOf(ModHeirCandidate(77L)), 77L)
            ModHeirFeed.publish(stale)
            // 设置页只入队，不调作者面：快照必须原样留在旧值。
            assertTrue(ModHeirRequests.requestChoose(pkg, 88L))
            assertEquals("入队不得直接改快照（页边界才是唯一兑现点）", stale, ModHeirFeed.read())
            assertEquals("请求还在队列里等页边界", 1, ModHeirRequests.pendingCount())
            drainAndApply(session, heirApi(session))
            assertEquals("drain 后积压归零", 0, ModHeirRequests.pendingCount())
            val refreshed = checkNotNull(ModHeirFeed.read())
            assertEquals("drain 后快照被会话真值覆盖（规则从会话读回）", FamilyDeclarations.SUCCESSION_ELDEST, refreshed.mode)
            assertNull("非手动规则下指定被作者面拒绝，不伪造已指定", refreshed.pendingRaw)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `反证二 手动规则下非法候选仍被作者面拒绝 快照不出现已指定`() {
        val root = tempGameRoot()
        writeFamilyManifest(root)
        // 盘上设置先写成 manual：装配期快照按 enabledOnly 读入，context.settings 现读即 manual。
        ModSettingsStore.write(
            ModEnablement.rt2Dir(root.toFile()),
            pkg,
            mapOf(FamilyDeclarations.SUCCESSION_MODE_KEY to FamilyDeclarations.SUCCESSION_MANUAL),
        )
        val session = attachWithFamily(root)
        try {
            assertEquals(
                "设置面热读 = 盘上 manual",
                FamilyDeclarations.SUCCESSION_MANUAL,
                successionMode(session),
            )
            // 12345L 不是本局子嗣 ⇒ 作者面 selectableHeir 拒绝；桥不得替作者面兜底。
            assertTrue(ModHeirRequests.requestChoose(pkg, 12345L))
            assertTrue(drainAndApply(session, heirApi(session)))
            val snapshot = checkNotNull(ModHeirFeed.read())
            assertEquals(FamilyDeclarations.SUCCESSION_MANUAL, snapshot.mode)
            assertNull("非法候选被拒 ⇒ 快照不得出现已指定", snapshot.pendingRaw)
            assertTrue("候选仍为空（没有子嗣）", snapshot.candidates.isEmpty())
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `没有活 family 载体时 apply 返 false 并清空快照`() {
        val root = tempGameRoot()
        writeFamilyManifest(root)
        // 本局不装 family 载体（模拟停用 / 装载失败 / 非载体装配）。
        val session = checkNotNull(attach(root, emptyList()))
        try {
            assertFalse("family 不在 activePackages", pkg in session.activePackages)
            ModHeirFeed.publish(ModHeirSnapshot(pkg, "manual", listOf(ModHeirCandidate(5L)), 5L))
            assertTrue(ModHeirRequests.requestClear(pkg))
            assertFalse("没有活载体时 apply 必须返 false", drainAndApply(session, null))
            assertNull("快照必须清空：设置页整块不渲染，不出现点了没反应的按钮", ModHeirFeed.read())
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `快照候选携带既有 title 作为显示名 且与候选顺序一致`() {
        val root = tempGameRoot()
        writeFamilyManifest(root)
        val session = attachWithFamily(root)
        try {
            val mod = checkNotNull(session.carrierModOf(pkg) as? FamilyMod) { "本会话没有活 FamilyMod" }
            val head = mod.adoptChild("当主", null)
            val eldest = mod.adoptChild("甲", head)
            val youngest = mod.adoptChild("长女", head)
            val api = heirApi(session)

            assertTrue(drainAndApply(session, api))
            val snapshot = checkNotNull(ModHeirFeed.read())
            assertEquals(
                "名字一次页边界取好（Compose 不读会话）；顺序必须与作者面候选一致",
                listOf(head.raw to "当主", eldest.raw to "甲", youngest.raw to "长女"),
                snapshot.candidates.map { it.raw to it.displayName },
            )
            assertEquals(
                "快照候选序 = 作者面稳定序（按 raw 升序），不得因带名字而重排",
                api.candidates().map { it.raw },
                snapshot.candidates.map { it.raw },
            )
            assertTrue(drainAndApply(session, api))
            assertEquals(
                "只读投影：重复页边界不改变快照候选（含名字）",
                snapshot.candidates,
                checkNotNull(ModHeirFeed.read()).candidates,
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `关闭称号时候选显示名回落 null 由设置页显示纯 raw`() {
        val root = tempGameRoot()
        writeFamilyManifest(root)
        ModSettingsStore.write(
            ModEnablement.rt2Dir(root.toFile()),
            pkg,
            mapOf(FamilyDeclarations.SHOW_TITLES_KEY to "false"),
        )
        val session = attachWithFamily(root)
        try {
            val mod = checkNotNull(session.carrierModOf(pkg) as? FamilyMod) { "本会话没有活 FamilyMod" }
            mod.adoptChild("当主", null)
            val api = heirApi(session)

            assertTrue(drainAndApply(session, api))
            val snapshot = checkNotNull(ModHeirFeed.read())
            assertTrue("本局应有候选", snapshot.candidates.isNotEmpty())
            assertTrue(
                "空标题 ⇒ 显示名 null，设置页回落 #raw（不显示空壳名字行）",
                snapshot.candidates.all { it.displayName == null },
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `手动指定后已指定候选在快照里仍带显示名`() {
        val root = tempGameRoot()
        writeFamilyManifest(root)
        ModSettingsStore.write(
            ModEnablement.rt2Dir(root.toFile()),
            pkg,
            mapOf(FamilyDeclarations.SUCCESSION_MODE_KEY to FamilyDeclarations.SUCCESSION_MANUAL),
        )
        val session = attachWithFamily(root)
        try {
            val mod = checkNotNull(session.carrierModOf(pkg) as? FamilyMod) { "本会话没有活 FamilyMod" }
            val head = mod.adoptChild("当主", null)
            val child = mod.adoptChild("甲", head)
            assertTrue(ModHeirRequests.requestChoose(pkg, child.raw))
            assertTrue(drainAndApply(session, heirApi(session)))

            val snapshot = checkNotNull(ModHeirFeed.read())
            assertEquals("manual 下合法指定必须生效", child.raw, snapshot.pendingRaw)
            val chosen = snapshot.candidates.firstOrNull { it.raw == child.raw }
            assertEquals("已指定行从同一份快照取名字，不额外读会话", "甲", chosen?.displayName)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `接线源码钉 页边界 drain 与收口 clear 各一处且顺序正确`() {
        val gameHost = sourceOf("GameHost.kt")
        val boundary = gameHost.indexOf("modRuntimeSession?.onTurnBoundary()")
        val drainCall = gameHost.indexOf("drainModHeirBridge()")
        assertTrue("GameHost 必须调用页边界 drainModHeirBridge", drainCall > 0)
        assertTrue(
            "drain 必须排在 onTurnBoundary 之后（同一拍页边界，不早于会话边界 drain）",
            drainCall > boundary,
        )
        assertEquals(
            "drainModHeirBridge 文本只该两处：定义 + 调用（多一处 = 第二兑现点）",
            2,
            Regex("drainModHeirBridge\\(\\)").findAll(gameHost).count(),
        )
        val sessionNull = gameHost.indexOf("modRuntimeSession = null")
        val requestClear = gameHost.indexOf("ModHeirRequests.clear()")
        // 失败兜底里也有一处 Feed.clear()，收口那处取最后一次出现。
        val feedClear = gameHost.lastIndexOf("ModHeirFeed.clear()")
        assertTrue("收口必须先置空会话再清请求（与既有两条 clear 同序）", requestClear > sessionNull)
        assertTrue("收口快照清空紧随请求清空", feedClear > requestClear)
        assertTrue(
            "收口 feed 清空必须真的排在请求清空之后（不是被失败兜底那处顶替）",
            feedClear > gameHost.indexOf("ModStorageReclaimRequests.clear()"),
        )

        val assembly = sourceOf("ModRuntimeAssembly.kt")
        assertEquals(
            "活载体表必须且只在装配返回前绑一次",
            1,
            Regex("bindCarrierMods\\(modProviders\\)").findAll(assembly).count(),
        )

        val settings = sourceOf("SettingsActivity.kt")
        assertEquals(
            "家督继任小节只能挂一处（ModManageCard 卡尾）",
            1,
            Regex("ModHeirSection\\(text, scan\\)").findAll(settings).count(),
        )
        assertEquals(
            "点选只入队 choose 一处",
            1,
            Regex("requestChoose\\(FamilyDeclarations.PACKAGE_ID").findAll(settings).count(),
        )
        assertEquals(
            "清除只入队 clear 一处",
            1,
            Regex("requestClear\\(FamilyDeclarations.PACKAGE_ID").findAll(settings).count(),
        )
    }

    /** 读 app 主源码（测试工作目录可能是 app/ 或仓库根，两处都试）。 */
    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile } ?: error("找不到源文件 $relative")
        return file.readText().replace("\r\n", "\n")
    }
}
