package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lane DZ 的玩家入口：设置页「重试装载」→ [ModReinstallRequests]（设置 → 会话数据队列）→
 * `GameHost` 页边界 → [ModRuntimeSession.reinstallRolledBack]（走 `host.install` 的
 * `openReinstallSlot` 回滚态替换槽）。
 *
 * 行为面直接打生产缝 [ModRuntimeAssembly.attach]（与 [ModSettingsNotifyWireTest] 同款装配），
 * 钉四件事：
 *
 * 1. 队列语义：去重、首投递序、取走即清、非法 packageId 静默丢弃；
 * 2. 可重试形态判据：只有「内置载体 + 本会话装载失败」给入口；
 * 3. 会话侧重装：回滚态（onEnable 失败登记 entry）可重装成功并进激活集；
 *    活着的包 / 构造段失败（无 entry）/ 已收口会话一律拒绝（不抛、不重复登记）；
 * 4. 接线源码钉：页边界 drain 位次、收口 clear 位次、设置页每条写盘成功路径各一处投递点、
 *    `ModManageCard` 仍是 5 参（failures/versions 收进 `ModManageReadouts`）且 `SettingsScreen`
 *    形参表一个新名字都不加（页面级状态收束进 `ModManageFilterUi` 就是为了这条）。
 *
 * 反向证明（交付报告里给红例名）：删掉 `reinstallRolledBack` 成功分支里的
 * `lateActivePackageIds = lateActivePackageIds + packageId` 记账行 ⇒
 * 「回滚态重装成功进激活集」用例红（实测 `AssertionError: 重装成功后必须进会话激活集`）。
 * 另记一笔实测结论：摘 `host.failureOf` 前置闸**不会**让本文件变红 —— `host.install` 抛出的
 * 「packageId 重复装载」被 `runCatching` 兜住并只上日志，故该闸是防御层而非可观测判据。
 */
class ModReinstallRetryWireTest {

    private val logs: MutableList<String> = mutableListOf()

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-reinstall-retry")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, packageId: String, displayName: String) {
        val bytes = ManifestCodec.encode(
            ModDeclarationAdapter.toDescriptor(ModManifest(packageId = packageId, displayName = displayName)),
        )
        Files.write(root.resolve("mods").resolve("rt2").resolve("$packageId.manifest"), bytes)
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

    /** 生命周期探针：`failOnEnable = true` 的实例在 onEnable 抛 ⇒ 会话内回滚态。 */
    private class LifecycleMod(private val failOnEnable: Boolean) : EraMod {
        override fun onLoad(context: ModContext) = Unit

        override fun onEnable() {
            if (failOnEnable) error("boom")
        }

        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    /** app 模块单测工作目录 = app/（Gradle 缺省），先按相对 app 再按仓库根兜一层；行尾归一成 LF。 */
    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("源码不在候选路径里：$relative")
        return file.readText().replace("\r\n", "\n")
    }

    /** 某 composable/函数签名的形参名（按声明序，每行一个形参时有效）。 */
    private fun paramNames(source: String, signatureStart: String): List<String> =
        source.substringAfter(signatureStart)
            .substringBefore(") {")
            .lineSequence()
            .map { it.trim().removeSuffix(",") }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore(":") }
            .toList()

    @Test
    fun `重装请求队列去重保序取走即清且非法键忽略`() {
        ModReinstallRequests.clear()
        ModReinstallRequests.request("mod.a")
        ModReinstallRequests.request("mod.b")
        ModReinstallRequests.request("mod.a")
        ModReinstallRequests.request("")
        ModReinstallRequests.request("../evil")
        assertEquals("同包重复投递必须去重（一次点击一条）", 2, ModReinstallRequests.pendingCount())
        assertEquals("首投递序、去重", listOf("mod.a", "mod.b"), ModReinstallRequests.drain())
        assertEquals("取走即清", 0, ModReinstallRequests.pendingCount())
        assertEquals("无积压再 drain = 空表", emptyList<String>(), ModReinstallRequests.drain())
        ModReinstallRequests.request("mod.c")
        ModReinstallRequests.clear()
        assertEquals("会话收口清场后不许漏进下一局", 0, ModReinstallRequests.pendingCount())
    }

    @Test
    fun `可重试形态只认内置载体加本会话失败行`() {
        assertTrue("内置载体 + 失败行 = 可重试", reinstallRetryable(ModSource.BUILTIN, "Boom: x"))
        assertFalse("内置载体没失败 = 不给（活着的不重装）", reinstallRetryable(ModSource.BUILTIN, null))
        assertFalse("导入包没有 newMod 实例来源 = 不给", reinstallRetryable(ModSource.IMPORTED, "Boom: x"))
        assertFalse("数据包同理", reinstallRetryable(ModSource.DATA_PACKAGE, "Boom: x"))
    }

    @Test
    fun `回滚态重装成功进激活集且打成功装载行`() {
        val root = tempGameRoot()
        val pkg = "mod.retry.ok"
        writeManifest(root, pkg, "重试探针")
        val session = checkNotNull(attach(root, listOf(pkg to LifecycleMod(failOnEnable = true))))
        assertNotNull("onEnable 失败必须登记回滚态 entry（可重装的前提）", session.host.failureOf(pkg))
        assertFalse("失败包不许算激活", session.activePackages.contains(pkg))

        assertTrue(
            "回滚态应可重装替换（openReinstallSlot 判据满足）",
            session.reinstallRolledBack(pkg, LifecycleMod(failOnEnable = false)),
        )
        assertNull("重装成功后旧失败读数必须清掉", session.host.failureOf(pkg))
        assertTrue("重装成功后必须进会话激活集（运行时概览 active 位同源）", session.activePackages.contains(pkg))
        assertEquals(
            "重装成功必须打 canoncial 成功行（ModManageDiagnostics 据此清失败行）",
            1,
            logs.count { it == "mod.install pkg=$pkg active=true" },
        )
        session.shutdown()
    }

    @Test
    fun `活着的包与已收口会话都拒绝重装且不抛`() {
        val root = tempGameRoot()
        val pkg = "mod.retry.alive"
        writeManifest(root, pkg, "活着的包")
        val session = checkNotNull(attach(root, listOf(pkg to LifecycleMod(failOnEnable = false))))
        assertNull("装载成功的包没有回滚失败", session.host.failureOf(pkg))

        // 反向证明锚点在成功分支的激活集记账行（见类 KDoc）；此处三道前置闸是防御层：
        // 即便摘掉 failureOf 闸，重复装载抛的 require 也会被 reinstallRolledBack 的 runCatching 兜住。
        assertFalse("活着的包不许重装", session.reinstallRolledBack(pkg, LifecycleMod(failOnEnable = false)))
        assertEquals(
            "拒绝路径不许重复登记装载行",
            1,
            logs.count { it.startsWith("mod.install pkg=$pkg ") },
        )

        session.shutdown()
        assertFalse("收口后的会话不许再起实例", session.reinstallRolledBack(pkg, LifecycleMod(failOnEnable = false)))
    }

    @Test
    fun `构造段失败没有回滚态entry不算可重试形态`() {
        val root = tempGameRoot()
        // provider 在册但盘上没有 manifest ⇒ install 逐 mod fail-closed（规划期拒载，不进 entry）。
        val pkg = "mod.retry.nomanifest"
        val session = checkNotNull(attach(root, listOf(pkg to LifecycleMod(failOnEnable = false))))
        assertNull("规划期拒载不登记 entry（failureOf 为空）", session.host.failureOf(pkg))
        assertFalse(
            "没有回滚态 entry 时不许重装（否则会撞 openReinstallSlot 的 require）",
            session.reinstallRolledBack(pkg, LifecycleMod(failOnEnable = false)),
        )
        session.shutdown()
    }

    @Test
    fun `重装仍失败时失败读数换新而不是丢失`() {
        val root = tempGameRoot()
        val pkg = "mod.retry.again"
        writeManifest(root, pkg, "重试仍失败")
        val session = checkNotNull(attach(root, listOf(pkg to LifecycleMod(failOnEnable = true))))
        val first = session.host.failureOf(pkg)
        assertNotNull(first)

        assertFalse("第二次仍失败 ⇒ 重装返回 false", session.reinstallRolledBack(pkg, LifecycleMod(failOnEnable = true)))
        assertNotNull("失败读数不许消失（界面继续报真实读数）", session.host.failureOf(pkg))
        assertEquals(
            "两次失败各打一条 canonical 失败行",
            2,
            logs.count { it.startsWith("mod.install pkg=$pkg active=false failure=") },
        )
        session.shutdown()
    }

    @Test
    fun `接线源码钉_页边界drain与收口clear与唯一投递点`() {
        val gameHost = sourceOf("GameHost.kt")
        assertTrue(
            "页边界 drain 必须排在会话节拍之后",
            gameHost.indexOf("ModReinstallRequests.drain()") >
                gameHost.indexOf("modRuntimeSession?.onTurnBoundary()"),
        )
        assertTrue(
            "收口 clear 必须排在会话置空之后",
            gameHost.indexOf("ModReinstallRequests.clear()") > gameHost.indexOf("modRuntimeSession = null"),
        )
        assertEquals(
            "消费点只许一处",
            1,
            gameHost.split("ModReinstallRequests.drain").size - 1,
        )
        assertTrue(
            "载体新实例只许经注册表的定取口",
            gameHost.contains("ModCarrierRegistry.providerFor(root, packageId)"),
        )
        assertTrue(
            "dex 包的重装载入口必须回退 dex 供给核单包取件（ERAFIN2 清偿 Lane DZ 沿挂；" +
                "缝缺省 carrierFor = null fail-closed）",
            gameHost.contains("?: dexCarriers?.carrierFor(root, packageId, modLogSink)"),
        )

        val settings = sourceOf("SettingsActivity.kt")
        // 类头判据 4 =「设置页每条写盘成功路径各一处投递点」：本波给代码包行
        // （ModCodeRowView 重试按钮，SettingsActivity 第二条写路径）加了第二个投递点，与
        // Mod 管理行 onRetryModInstall 各一处——总数钉死为 2，第三条野路子进来即红。
        assertEquals(
            "设置页两条写路径各一处重装投递点（Mod 管理行 + 代码包行）",
            2,
            settings.split("ModReinstallRequests.request").size - 1,
        )
        assertTrue(
            "按钮渲染必须过可重试形态判据",
            settings.indexOf("reinstallRetryable(mod.source, failure)") in
                0 until settings.indexOf("actions.onRetryInstall(mod)"),
        )
        assertSettingsScreenSignaturePinned(settings)

        val carriers = sourceOf("ModCarrierRegistry.kt")
        assertTrue(
            "载体定取口必须存在且返回 newMod 实例",
            carriers.contains("fun providerFor(gameRoot: File, packageId: String): EraMod?") &&
                carriers.contains("else carrier.newMod()"),
        )
        assertEquals(
            "实例只许 newMod 现造、不许缓存（providers 两处 + providerFor 一处）",
            3,
            carriers.split("carrier.newMod()").size - 1,
        )
    }

    /**
     * 设置页骨架形参表的源码钉（从主用例抽出，避免主用例顶破 detekt LongMethod）。
     *
     * 旧断言逐字钉的是 `failures: Map<String, String>` / `versions: List<ModVersionRow>` 两行；
     * Lane modsearch 把这俩视图状态收进 `ModManageReadouts`、把查询/来源筛选收进
     * `ModManageFilterUi` 之后，形参**个数仍是 5**，但逐字文本不再成立。这里更新成当前签名，
     * 判据不变：ModManageCard 不许靠扁平化顶爆形参（新增能力走 data class 收束）。
     */
    private fun assertSettingsScreenSignaturePinned(settings: String) {
        assertTrue(
            "ModManageCard 必须仍是 5 参（视图状态收进 data class，不靠扁平形参堆）",
            settings.contains(
                "private fun ModManageCard(\n" +
                    "    text: SettingsStrings,\n" +
                    "    scan: ModManageScan,\n" +
                    "    readouts: ModManageReadouts,\n" +
                    "    filter: ModManageFilterUi,\n" +
                    "    actions: ModManageActions,\n" +
                    ")",
            ),
        )
        // 「ModManageCard 恰 5 参」只是代理；真实意图是**设置页整页骨架一个形参都不许新增**
        // （SettingsScreen 已有 61 个，每加一个功能都要连带改调用点与组合节点）。这里按声明序
        // 逐名钉死：新增、删除、改名都红。
        assertEquals(
            "SettingsScreen 形参表一个新名字都不许加（按声明序逐名钉死）",
            SETTINGS_SCREEN_PARAM_NAMES,
            paramNames(settings, "private fun SettingsScreen("),
        )
    }

    private companion object {
        /**
         * `SettingsScreen` 形参名（按声明序）：60 个已是设置页的既有基线。
         * 这张表就是「设置页一个形参都不许新增」的判据——新能力走局部 `remember` 或 data class 收束。
         */
        val SETTINGS_SCREEN_PARAM_NAMES: List<String> = listOf(
            "filePath",
            "loadNotice",
            "clampNotices",
            "dirty",
            "englishLabels",
            "onEnglishLabelsChange",
            "groups",
            "onApplyText",
            "onApplyBool",
            "displayOf",
            "onResetAll",
            "onRestoreDefaults",
            "onSave",
            "onSaveAndRestart",
            "onBack",
            "importing",
            "importLines",
            "overwritePending",
            "onPickSaveFiles",
            "onOverwriteConfirm",
            "onOverwriteCancel",
            "modImportCopy",
            "modImportState",
            "onOpenModImport",
            "marketCopy",
            // 10f411a18（MKM-STATS，cherry-pick 时已并入 main）给市场行加了读数形参，
            // 逐名表按当前 SettingsScreen 声明序刷新——「一个都不许新增」判据不变。
            "marketEntryOverview",
            "onOpenMarket",
            "resourcePackCopy",
            "backgroundPackState",
            "bgmPackState",
            "archivePackState",
            "bgmLocation",
            "onBgmLocationChange",
            "onPickBackgroundFiles",
            "onPickBackgroundFolder",
            "onPickBgmFiles",
            "onPickBgmFolder",
            "onPickResourcePacks",
            "onClearBackground",
            "onClearBgm",
            "onRestartForResources",
            "exporting",
            "exportLines",
            "diagBusy",
            "diagLines",
            "onShareDiagnostics",
            "onCopyDiagnostics",
            "onExport",
            "text",
            "language",
            "onLanguageChange",
            "onCheckUpdate",
            "updateChecking",
            "updateReadout",
            "downloadProgress",
            "updateCopy",
            "failedDownloadKind",
            "onRetryFailedDownload",
            "onCancelDownload",
            "skippedUpdateVersion",
            "onRestoreUpdateReminder",
        )
    }
}
