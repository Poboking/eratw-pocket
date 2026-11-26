package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.LinkOrderException
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestFormatException
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModApi
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * task-111「失败形态 × 玩家可见面」矩阵：**每一种会拒绝 mod 的闸都真造一次失败**，然后断言
 * 玩家侧到底能不能看到原因（不是只验闸会不会拒）。
 *
 * ## 三列可见面（本文件断言的就是它们）
 * 1. `ModManageDiagnostics.snapshot()`——生产链唯一装载失败记录面。接线行 =
 *    `GameHost.kt:1242` 的共享 `modLogSink`（`ModManageDiagnostics.record(line)` +
 *    `Log.i(TAG, "modrt: $line")`），**装配期与 attach 期共用同一 lambda**（Lane DP）；
 *    canonical 行由 `ModRuntimeAssembly.kt:470-473`（会话期逐包 install 报告）与
 *    `ModChainAssembly.kt:432 / :343`（装配期整链拒载留痕）生产。
 * 2. 设置页渲染输入：`SettingsActivity.kt:4778-4785`（条目行 failure）、`:4976-4996`（「上次
 *    启动时装载失败」小节，标题 + 逐条 `{标题或包名}：{原因}`）、`:5000-5008`（坏 manifest
 *    小字 `prefix + skipped.joinToString`）、`ModRuntimeOverview.kt:65-68 / :98-118`（运行时
 *    概览失败栏，空 = 「无」）。Composable 在纯 JVM 单测里跑不了，这里断言的是它们调用的
 *    纯函数与逐字表达式（调用点行号见各断言消息）。
 * 3. `ModManageScanner.scan()`——列表会不会把失败包读成「正常装着」、坏档会不会进 skipped。
 *
 * ## 整链级拒载契约（组 B，task-114 / Lane DP 起成立）
 * 装配期整链拒载发生在 `attach` 之前，会话侧 canonical 行生产者走不到；故装配层必须在
 * `throw` 之前按包级补一条与会话侧逐字同形的行到同一个 `logSink`。组 B 的两条用例就是
 * 这条契约的回归闸：留痕被摘掉或行形状写错 ⇒ 诊断面/设置页断言红。
 *
 * ## 反向证明（成对存在，缺一即证明不了可见性）
 * - `反向证明 - 诊断面未接线时同一失败在设置页不可见`：同一现场把 `record` 从 logSink 摘掉
 *   ⇒ 日志仍有 canonical 行、诊断面为空、设置页小节为空。生产 `GameHost.kt:1242` 少一行就是这个红。
 * - `反向证明 - 失败行被读成成功时诊断面不记失败`：把 canonical 行改写成 `active=true` 再喂
 *   解析器 ⇒ 失败被当成成功、诊断面清空；原行喂入才记录。上报行若写错（assemble 把失败包报成
 *   success）就是这个红。
 */
class ModFailureVisibilityMatrixE2ETest {

    private val logs: MutableList<String> = mutableListOf()

    private val zh = SettingsStrings.of(SettingsLanguage.ZH)

    @Before
    fun setUp() {
        logs.clear()
        ModManageDiagnostics.reset()
    }

    @After
    fun tearDown() {
        ModManageDiagnostics.reset()
    }

    // =================================================================
    // 组 A：attach 期逐包拒载（canonical 行 → snapshot → 设置页）
    // =================================================================

    /**
     * A1：`apiVersion` 超前（`ModRuntimeHost.enforceVersionGate` → `ModApiVersionMismatchException`）。
     * 该包的 manifest 正常可读，所以设置页列表有它、版本行会标「不兼容」。
     */
    @Test
    fun `A1 apiVersion超前 - 诊断面有逐包记录且设置页失败行与版本行都可读`() {
        val root = tempGameRoot()
        writeModuleManifest(root, PKG_A)
        val session = attach(root, listOf(PKG_A to VersionMod(apiVersion = ModApi.VERSION + 1)))
        try {
            val line = installLineOf(PKG_A)
            assertTrue("必须按版本门拒载：$line", line.contains("active=false failure=ModApiVersionMismatchException:"))

            val snapshot = ModManageDiagnostics.snapshot()
            val reason = snapshot[PKG_A]
            assertNotNull("玩家可见面①：失败必须进诊断快照（生产接线 GameHost.kt:1242），实际=$snapshot", reason)
            assertTrue("摘要应带异常类型：$reason", reason!!.startsWith("ModApiVersionMismatchException: "))
            assertTrue(
                "摘要应点名需要的 API 版本：$reason",
                reason.contains("需要 mod API v${ModApi.VERSION + 1}"),
            )

            val scan = ModManageScanner.scan(root.toFile())
            assertEquals("盘上 manifest 可读 ⇒ 列表必须列出该包", listOf(PKG_A), scan.mods.map { it.packageId })
            assertTrue("可读 manifest 不得进 skipped：${scan.skipped}", scan.skipped.isEmpty())

            val section = settingsFailureSection(scan, snapshot)
            assertEquals("设置页失败小节标题", zh.modManageFailureHeading, section.first())
            assertEquals("失败小节条数（标题 + 1 条）", 2, section.size)
            assertTrue(
                "设置页行必须是「标题：原因」且带包名与类型：${section[1]}",
                section[1].startsWith("${scan.mods.single().title}：") &&
                    section[1].contains("ModApiVersionMismatchException"),
            )

            val overviewRow = overviewRowOf(listOf(PKG_A), snapshot)
            assertFalse("失败包不得算 active", overviewRow.active)
            assertEquals("概览失败栏原因 = 诊断摘要", reason, overviewRow.failure)
            assertTrue(
                "概览失败栏文案：${failureLine(zh, overviewRow)}",
                failureLine(zh, overviewRow).contains("装载失败：ModApiVersionMismatchException"),
            )

            val versionRows = buildModVersionRows(
                packageIds = listOf(PKG_A),
                declaredApiVersions = mapOf(PKG_A to ModApi.VERSION + 1),
                engineApiVersion = MOD_ENGINE_API_VERSION,
                failures = snapshot,
            )
            assertTrue("声明版本高于引擎必须标 incompatible", versionRows.single().incompatible)
            assertNull("不兼容时版本行让位给警告行", modVersionLine(zh, versionRows.single()))
            assertTrue(
                "警告行应说清两侧版本：${modVersionIncompatibleLine(zh, versionRows.single())}",
                modVersionIncompatibleLine(zh, versionRows.single())!!.contains("需要 API v${ModApi.VERSION + 1}"),
            )
        } finally {
            session.shutdown()
        }
    }

    /**
     * A2：数据版本降级（读档缝 `rejectEntryOnRestore` → `ModDataVersionDowngradeException`）。
     * 首会话声明 v2 存档，次会话只声明 v1 ⇒ 读档时拒载；设置页版本行应显示 `v2→v1` 缺口。
     */
    @Test
    fun `A2 数据版本降级 - 读档后诊断面记录降级且设置页显示v2到v1缺口`() {
        val root = tempGameRoot()
        writeDeclManifest(root, PKG_A)
        val store1 = VariableStore()
        val first = attach(root, listOf(PKG_A to MigratingMod(declaredDataVersion = 2, payload = "shape-v2")), store1)
        binaryOps(store1, root).saveGlobal()
        first.shutdown()
        ModManageDiagnostics.reset()

        val store2 = VariableStore()
        val second = attach(root, listOf(PKG_A to MigratingMod(declaredDataVersion = 1, payload = "shape-v3")), store2)
        try {
            assertTrue("install 期 storage 为空，降级只能在读档缝发现", ModManageDiagnostics.snapshot().isEmpty())
            assertTrue("读档必须完成（失败隔离到该包）", binaryOps(store2, root).loadGlobal())

            val snapshot = ModManageDiagnostics.snapshot()
            val reason = snapshot[PKG_A]
            assertNotNull("玩家可见面①：降级必须进诊断快照，实际=$snapshot", reason)
            assertTrue("摘要应带异常类型：$reason", reason!!.startsWith("ModDataVersionDowngradeException: "))
            assertTrue("摘要应带存档版本：$reason", reason.contains("存档记录 v2"))
            assertTrue("摘要应带 mod 声明版本：$reason", reason.contains("mod 只声明到 v1"))

            val scan = ModManageScanner.scan(root.toFile())
            val versionRows = buildModVersionRows(
                packageIds = scan.mods.map { it.packageId },
                declaredApiVersions = mapOf(PKG_A to 1),
                engineApiVersion = MOD_ENGINE_API_VERSION,
                failures = snapshot,
            )
            assertEquals("设置页数据版本缺口", ModDataVersionGap(2, 1), versionRows.single().dataVersionGap)
            assertTrue(
                "设置页版本行应显示缺口：${modVersionLine(zh, versionRows.single())}",
                modVersionLine(zh, versionRows.single())!!.contains("数据版本 v2→v1"),
            )
            assertTrue(
                "设置页失败小节应点名降级：${settingsFailureSection(scan, snapshot)}",
                settingsFailureSection(scan, snapshot).any { it.contains("ModDataVersionDowngradeException") },
            )
            assertTrue(
                "概览失败栏应显示降级：${failureLine(zh, overviewRowOf(listOf(PKG_A), snapshot))}",
                failureLine(zh, overviewRowOf(listOf(PKG_A), snapshot)).contains("降级"),
            )
        } finally {
            second.shutdown()
        }
    }

    /**
     * A3：数据迁移失败（`migrateForInstall` 抛 `ModDataMigrationException`）。读档时旧档 v1、
     * 载体声明 v2 且 `onMigrate` 抛错 ⇒ 只有该包被拒；版本行显示 `v1→v2` 迁移缺口。
     */
    @Test
    fun `A3 数据迁移失败 - 读档后诊断面记录迁移失败且设置页显示v1到v2缺口`() {
        val root = tempGameRoot()
        writeDeclManifest(root, PKG_A)
        val store1 = VariableStore()
        val first = attach(root, listOf(PKG_A to MigratingMod(declaredDataVersion = 1, payload = "shape-v1")), store1)
        binaryOps(store1, root).saveGlobal()
        first.shutdown()
        ModManageDiagnostics.reset()

        val store2 = VariableStore()
        val failing = MigratingMod(declaredDataVersion = 2, payload = "shape-v2", migrateThrows = true)
        val second = attach(root, listOf(PKG_A to failing), store2)
        try {
            assertTrue("读档必须完成（迁移失败被隔离到该包）", binaryOps(store2, root).loadGlobal())
            assertEquals("onMigrate 应被调用一次", listOf(1 to 2), failing.migrations)

            val snapshot = ModManageDiagnostics.snapshot()
            val reason = snapshot[PKG_A]
            assertNotNull("玩家可见面①：迁移失败必须进诊断快照，实际=$snapshot", reason)
            assertTrue("摘要应带异常类型：$reason", reason!!.startsWith("ModDataMigrationException: "))
            assertTrue("摘要应带迁移方向：$reason", reason.contains("数据迁移 v1→v2 失败"))
            assertTrue("摘要应带根因：$reason", reason.contains(MIGRATE_FAILURE))

            val scan = ModManageScanner.scan(root.toFile())
            val versionRows = buildModVersionRows(
                packageIds = scan.mods.map { it.packageId },
                declaredApiVersions = mapOf(PKG_A to 2),
                engineApiVersion = MOD_ENGINE_API_VERSION,
                failures = snapshot,
            )
            assertEquals("设置页数据版本缺口", ModDataVersionGap(1, 2), versionRows.single().dataVersionGap)
            assertTrue(
                "设置页版本行应显示迁移缺口：${modVersionLine(zh, versionRows.single())}",
                modVersionLine(zh, versionRows.single())!!.contains("数据版本 v1→v2"),
            )
            assertTrue(
                "设置页失败小节应点名迁移失败：${settingsFailureSection(scan, snapshot)}",
                settingsFailureSection(scan, snapshot).any { it.contains("数据迁移 v1→v2 失败") },
            )
        } finally {
            second.shutdown()
        }
    }

    /** A4：`onLoad` 抛原始异常（install 逐包围栏）⇒ snapshot 记录原始类型与消息。 */
    @Test
    fun `A4 onLoad抛异常 - 诊断面记录原始异常且设置页失败行带包名`() {
        val root = tempGameRoot()
        writeModuleManifest(root, PKG_A)
        val session = attach(root, listOf(PKG_A to FailingMod()))
        try {
            val line = installLineOf(PKG_A)
            assertTrue(
                "必须记录原始异常类型：$line",
                line.contains("active=false failure=IllegalStateException: $ON_LOAD_FAILURE"),
            )

            val snapshot = ModManageDiagnostics.snapshot()
            val reason = snapshot[PKG_A]
            assertNotNull("玩家可见面①：onLoad 失败必须进诊断快照，实际=$snapshot", reason)
            assertEquals("摘要 = 类型: 原始消息", "IllegalStateException: $ON_LOAD_FAILURE", reason)

            val scan = ModManageScanner.scan(root.toFile())
            val section = settingsFailureSection(scan, snapshot)
            assertTrue("设置页失败小节必须出现：$section", section.any { it.contains(ON_LOAD_FAILURE) })
            assertTrue(
                "概览失败栏应带原始消息：${failureLine(zh, overviewRowOf(listOf(PKG_A), snapshot))}",
                failureLine(zh, overviewRowOf(listOf(PKG_A), snapshot)).contains(ON_LOAD_FAILURE),
            )
        } finally {
            session.shutdown()
        }
    }

    /**
     * A5：缺依赖（规划期 `ModDependencyMissingException`）。依赖者被拒、无关包照常；
     * 诊断面只记被拒的那一个。
     */
    @Test
    fun `A5 缺依赖 - 诊断面点名缺失包且设置页只列失败包`() {
        val root = tempGameRoot()
        writeModuleManifest(root, PKG_A)
        writeModuleManifest(root, PKG_B)
        val session = attach(
            root,
            listOf(
                PKG_A to FailingMod(noop = true),
                PKG_B to RequiresMod(requires = listOf(PKG_GHOST)),
            ),
        )
        try {
            val snapshot = ModManageDiagnostics.snapshot()
            assertEquals("只有 B 失败，A 成功 ⇒ 快照只一条，实际=$snapshot", 1, snapshot.size)
            val reason = snapshot[PKG_B]
            assertNotNull("玩家可见面①：缺依赖必须进诊断快照，实际=$snapshot", reason)
            assertTrue("摘要应带异常类型：$reason", reason!!.startsWith("ModDependencyMissingException: "))
            assertTrue("摘要应点名缺失包：$reason", reason.contains("缺少依赖 $PKG_GHOST"))

            val scan = ModManageScanner.scan(root.toFile())
            assertEquals("两个 manifest 都可读", listOf(PKG_A, PKG_B).sorted(), scan.mods.map { it.packageId }.sorted())
            assertTrue("设置页列表不得标成坏档：${scan.skipped}", scan.skipped.isEmpty())
            val section = settingsFailureSection(scan, snapshot)
            assertEquals("失败小节只列被拒的 B（标题 + 1 条）", 2, section.size)
            assertTrue(
                "设置页行应点名缺失包：${section[1]}",
                section[1].startsWith("${scan.mods.first { it.packageId == PKG_B }.title}：") &&
                    section[1].contains(PKG_GHOST),
            )
        } finally {
            session.shutdown()
        }
    }

    /** A6：依赖成环（`ModDependencyCycleException`）⇒ 环上两个包各一条可读原因，环外不受影响。 */
    @Test
    fun `A6 依赖成环 - 诊断面逐包记录环成员且设置页逐条列出`() {
        val root = tempGameRoot()
        writeModuleManifest(root, PKG_A)
        writeModuleManifest(root, PKG_B)
        val session = attach(
            root,
            listOf(
                PKG_A to RequiresMod(requires = listOf(PKG_B)),
                PKG_B to RequiresMod(requires = listOf(PKG_A)),
            ),
        )
        try {
            val snapshot = ModManageDiagnostics.snapshot()
            assertEquals("环上两包都必须有记录，实际=$snapshot", setOf(PKG_A, PKG_B), snapshot.keys)
            for (packageId in listOf(PKG_A, PKG_B)) {
                val reason = snapshot.getValue(packageId)
                assertTrue("$packageId 摘要应带类型：$reason", reason.startsWith("ModDependencyCycleException: "))
                assertTrue("$packageId 摘要应点名环成员：$reason", reason.contains("$PKG_A -> $PKG_B -> $PKG_A"))
            }
            val scan = ModManageScanner.scan(root.toFile())
            val section = settingsFailureSection(scan, snapshot)
            assertEquals("失败小节应逐条列出环上两包（标题 + 2 条）", 3, section.size)
            assertEquals("设置页条目顺序 = 诊断快照顺序", 2, section.drop(1).size)
            assertTrue("设置页每行都应带环成员：$section", section.drop(1).all { it.contains("->") })
        } finally {
            session.shutdown()
        }
    }

    /** A7：依赖连坐（`ModDependencyUnavailableException`）⇒ 依赖者原因里带依赖包根因。 */
    @Test
    fun `A7 依赖失败连坐 - 诊断面记录依赖者与根因且设置页两行`() {
        val root = tempGameRoot()
        writeModuleManifest(root, PKG_A)
        writeModuleManifest(root, PKG_B)
        val session = attach(
            root,
            listOf(
                PKG_A to FailingMod(),
                PKG_B to RequiresMod(requires = listOf(PKG_A)),
            ),
        )
        try {
            val snapshot = ModManageDiagnostics.snapshot()
            assertEquals("A 原始失败 + B 依赖失败，实际=$snapshot", setOf(PKG_A, PKG_B), snapshot.keys)
            val reasonOfB = snapshot.getValue(PKG_B)
            assertTrue("B 应记依赖不可用：$reasonOfB", reasonOfB.startsWith("ModDependencyUnavailableException: "))
            assertTrue("B 的原因应带 A 的根因：$reasonOfB", reasonOfB.contains("根因 IllegalStateException"))
            assertTrue("设置页失败小节应同时列出两包", settingsFailureSection(snapshotOfScan(root), snapshot).size == 3)
        } finally {
            session.shutdown()
        }
    }

    /**
     * A8：载体登记了但没有 manifest（半成品/被停用泄漏进 providers）⇒ 构造段 fail-closed。
     * 该包不在盘面清单里，设置页列表区没有它，但「上次启动时装载失败」小节的
     * `titles[packageId] ?: packageId` 兜底（SettingsActivity.kt:4991）仍能把包名报出来。
     */
    @Test
    fun `A8 载体无manifest - 诊断面有记录且设置页失败小节按包名兜底显示`() {
        val root = tempGameRoot()
        val session = attach(root, listOf(PKG_A to FailingMod(noop = true)))
        try {
            val snapshot = ModManageDiagnostics.snapshot()
            val reason = snapshot[PKG_A]
            assertNotNull("玩家可见面①：声明快照缺包必须进诊断快照，实际=$snapshot", reason)
            assertTrue("摘要应带构造期 require 文案：$reason", reason!!.contains("声明快照不含 packageId=$PKG_A"))

            val scan = ModManageScanner.scan(root.toFile())
            assertTrue("盘上没有 manifest ⇒ 列表无此项", scan.mods.isEmpty())
            assertTrue("没有 manifest 可读 ⇒ skipped 也为空", scan.skipped.isEmpty())

            val titles = scan.mods.associate { it.packageId to it.title }
            val fallbackLine = zh.modManageFailureLine(titles[PKG_A] ?: PKG_A, reason)
            assertTrue(
                "设置页兜底必须用包名而不是空白：$fallbackLine",
                fallbackLine.startsWith("$PKG_A：") && fallbackLine.contains("声明快照不含 packageId"),
            )
            assertTrue("设置页失败小节确实非空（failures 非空即渲染）", settingsFailureSection(scan, snapshot).size == 2)
        } finally {
            session.shutdown()
        }
    }

    // =================================================================
    // 组 B：整链拒载（attach 之前 fail-closed ⇒ 装配期必须自己留痕）
    // =================================================================

    /**
     * B1：`minApi` 超前（`ModChainAssembly` 引擎 API 版本闸 → `LinkOrderException` 整链拒）。
     *
     * **契约（task-114 / Lane DP）**：整链拒发生在 `attach` 之前，会话侧 canonical 行生产者
     * 走不到，故装配层在 `throw` 前按包级补一条与会话侧逐字同形的 `mod.install …` 行到同一个
     * `logSink`（`GameHost.kt:1242` 的共享 `modLogSink`）⇒ 诊断面收入、设置页失败小节渲染出
     * 同一原因。失败屏文案不动（仍点名包名 + min-api + VERSION + 「更新 app」）。
     * 本用例是留痕契约的回归闸：留痕被摘掉 / 行形状写错 ⇒ 红。
     */
    @Test
    fun `B1 minApi超前 - 整链拒载后诊断面与设置页都能看到原因`() {
        val root = tempGameRoot()
        writeChainManifest(root, PKG_A, "mod_a.manifest", minApi = ModApi.VERSION + 1)
        writeContainer(root, PKG_A, "mod_a.erbm")

        val failure = captureChainFailure(root)
        val message = failure.message!!
        assertTrue("失败屏原文应点名包名，实得：$message", message.contains(PKG_A))
        assertTrue("失败屏原文应带需要的 min-api，实得：$message", message.contains("${ModApi.VERSION + 1}"))
        assertTrue("失败屏原文应带当前引擎版本，实得：$message", message.contains("ModApi.VERSION ${ModApi.VERSION}"))
        assertTrue("失败屏原文应给「更新 app」归因，实得：$message", message.contains("请更新 app"))

        val line = installLineOf(PKG_A)
        assertTrue(
            "装配期必须按包级留痕且与会话侧同形：$line",
            line.startsWith("mod.install pkg=$PKG_A active=false failure=LinkOrderException: "),
        )
        assertTrue("行内原因须带 min-api 读数：$line", line.contains("min-api ${ModApi.VERSION + 1}"))

        val snapshot = ModManageDiagnostics.snapshot()
        val reason = snapshot[PKG_A]
        assertNotNull("整链拒载必须进诊断面（既有解析器零改动），实际=$snapshot", reason)
        assertTrue("摘要须带异常类型：$reason", reason!!.startsWith("LinkOrderException: "))
        assertTrue("摘要须带原因：$reason", reason.contains("min-api ${ModApi.VERSION + 1}"))

        val scan = ModManageScanner.scan(root.toFile())
        assertEquals("manifest 可读 ⇒ 设置页列表照常列出", listOf(PKG_A), scan.mods.map { it.packageId })
        assertTrue("这不是坏档 ⇒ skipped 必须为空：${scan.skipped}", scan.skipped.isEmpty())

        val section = settingsFailureSection(scan, snapshot)
        assertEquals("设置页失败小节必须渲染（标题 + 1 条）", 2, section.size)
        assertEquals("失败小节标题", zh.modManageFailureHeading, section[0])
        assertTrue(
            "设置页行须带包名与原因：${section[1]}",
            section[1].contains("min-api ${ModApi.VERSION + 1}"),
        )

        val row = overviewRowOf(listOf(PKG_A), snapshot)
        assertFalse("失败包不得算 active", row.active)
        assertEquals("概览失败栏原因 = 诊断摘要", reason, row.failure)
        assertTrue(
            "概览失败栏文案：${failureLine(zh, row)}",
            failureLine(zh, row).contains("装载失败：LinkOrderException"),
        )
    }

    /**
     * B2：坏 `.erbm` 容器（`decodeErbmContainers` fail-closed 整包拒）。
     *
     * **契约（task-114 / Lane DP）**：与 B1 同一条留痕线。坏字节解不开时容器里没有可读
     * packageId（decode 先核尾部摘要），故包级归因取文件名词干 —— 生产写侧恒定
     * `mods/rt2/<packageId>.erbm`（导入/重编译/卸载/库存四处同源），夹具同样用生产命名。
     * 断言诊断面收入、设置页失败小节渲染；失败屏文案（点名坏档文件 + fail-closed）不动。
     */
    @Test
    fun `B2 坏erbm容器 - 整链拒载后诊断面与设置页都能看到原因`() {
        val root = tempGameRoot()
        writeChainManifest(root, PKG_A, "mod_a.manifest", minApi = 0)
        Files.write(root.resolve("mods").resolve("rt2").resolve("$PKG_A.erbm"), "not-a-container".toByteArray())

        val failure = captureChainFailure(root)
        val message = failure.message!!
        assertTrue("失败屏原文应点名坏档文件，实得：$message", message.contains("$PKG_A.erbm"))
        assertTrue("失败屏原文应标明 fail-closed 归因，实得：$message", message.contains("fail-closed"))

        val line = installLineOf(PKG_A)
        assertTrue(
            "装配期必须按包级留痕且与会话侧同形：$line",
            line.startsWith("mod.install pkg=$PKG_A active=false failure=IllegalArgumentException: "),
        )
        assertTrue("行内原因须点名坏档文件：$line", line.contains("$PKG_A.erbm"))

        val snapshot = ModManageDiagnostics.snapshot()
        val reason = snapshot[PKG_A]
        assertNotNull("整链拒载必须进诊断面（既有解析器零改动），实际=$snapshot", reason)
        assertTrue("摘要须带实际异常类型：$reason", reason!!.startsWith("IllegalArgumentException: "))
        assertTrue("摘要须点名坏档文件：$reason", reason.contains("$PKG_A.erbm"))
        assertTrue("摘要须保留 fail-closed：$reason", reason.contains("fail-closed"))

        val scan = ModManageScanner.scan(root.toFile())
        assertEquals("manifest 可读 ⇒ 列表照常列出", listOf(PKG_A), scan.mods.map { it.packageId })
        assertTrue("坏容器不是坏 manifest ⇒ skipped 为空：${scan.skipped}", scan.skipped.isEmpty())
        val section = settingsFailureSection(scan, snapshot)
        assertEquals("设置页失败小节必须渲染（标题 + 1 条）", 2, section.size)
        assertTrue("设置页行须点名坏档文件：${section[1]}", section[1].contains("$PKG_A.erbm"))

        val row = overviewRowOf(listOf(PKG_A), snapshot)
        assertEquals("概览失败栏原因 = 诊断摘要", reason, row.failure)
        assertTrue(
            "概览失败栏文案：${failureLine(zh, row)}",
            failureLine(zh, row).contains("装载失败：IllegalArgumentException"),
        )
    }

    // =================================================================
    // 组 C：盘面坏档（scanner.skipped 可见；诊断面不可见）
    // =================================================================

    /**
     * C1：坏 manifest（`ManifestCodec.decode` 抛 `ManifestFormatException`）⇒ `attach` 整包拒。
     * 诊断面为空（还没到 install），但设置页「坏档小字」点名文件与原因（SettingsActivity.kt:5003）。
     */
    @Test
    fun `C1 坏manifest - 诊断面为空但设置页坏档小字点名文件`() {
        val root = tempGameRoot()
        Files.write(root.resolve("mods").resolve("rt2").resolve("mod_a.manifest"), "garbage-manifest".toByteArray())

        val failure = assertThrows(ManifestFormatException::class.java) {
            attach(root, emptyList())
        }
        assertTrue("失败屏必须有可读 message（GameHost.describe 取 message 上屏）", !failure.message.isNullOrBlank())
        assertTrue("attach 整包拒必须在 install 之前，日志不得有 mod.install 行：$logs", installLines().isEmpty())
        assertTrue("诊断面为空（原因走失败屏 + 坏档小字）", ModManageDiagnostics.snapshot().isEmpty())

        val scan = ModManageScanner.scan(root.toFile())
        assertTrue("列表无此项（manifest 读不出）", scan.mods.isEmpty())
        assertEquals("坏档必须进 skipped", 1, scan.skipped.size)
        val brokenLine = zh.modManageBrokenPrefix + scan.skipped.joinToString("、")
        assertTrue("设置页坏档小字必须点名文件：$brokenLine", brokenLine.contains("mod_a.manifest"))
        assertTrue("设置页坏档小字必须带原因分隔符：$brokenLine", brokenLine.contains("："))
        assertTrue("没有装载失败记录 ⇒ 失败小节不渲染", settingsFailureSection(scan, emptyMap()).isEmpty())
    }

    // =================================================================
    // 组 D：反向证明（可见面断言真的依赖生产接线/上报行语义）
    // =================================================================

    /**
     * D1（反向证明①「清空诊断面 ⇒ 红」）：同一失败现场，`record` 未接线时日志仍有 canonical 行、
     * 但诊断面为空、设置页失败小节为空；接线后同一现场诊断面非空。生产 `GameHost.kt:1242` 若删掉
     * `record(line)`，`A1` 的可见性断言就是红（本用例证明这一行是承重的）。
     */
    @Test
    fun `D1 反向证明 - 诊断面未接线时同一失败在设置页不可见`() {
        val root = tempGameRoot()
        writeModuleManifest(root, PKG_A)
        val unplugged = attach(
            root,
            listOf(PKG_A to VersionMod(apiVersion = ModApi.VERSION + 1)),
            forwardToDiagnostics = false,
        )
        try {
            assertTrue("上报行本身不能少：$logs", installLineOf(PKG_A).contains("active=false failure="))
            assertTrue("反向证明：摘掉 record 后诊断面必须为空", ModManageDiagnostics.snapshot().isEmpty())
            assertTrue(
                "反向证明：摘掉 record 后设置页失败小节为空",
                settingsFailureSection(ModManageScanner.scan(root.toFile()), emptyMap()).isEmpty(),
            )
        } finally {
            unplugged.shutdown()
        }

        ModManageDiagnostics.reset()
        val wired = attach(root, listOf(PKG_A to VersionMod(apiVersion = ModApi.VERSION + 1)))
        try {
            assertNotNull("接线后同一现场必须可见（成对证明）", ModManageDiagnostics.snapshot()[PKG_A])
        } finally {
            wired.shutdown()
        }
    }

    /**
     * D2（反向证明②「把失败包读成成功 ⇒ 红」）：把真实 canonical 失败行改写成 `active=true`
     * 再喂 `ModManageDiagnostics.record` ⇒ 记录被清空/不产生；原行喂入才有记录。若 `ModRuntimeAssembly`
     * 把失败包报成 success（或解析器把 `active=false` 读成成功），`A1` 的可见性断言就是红。
     * 另断言「有 active=false 但没 failure=」⇒ 摘要为 `?`（玩家至少知道失败、但看不到原因）。
     */
    @Test
    fun `D2 反向证明 - 失败行被读成成功时诊断面不记失败`() {
        val root = tempGameRoot()
        writeModuleManifest(root, PKG_A)
        val session = attach(
            root,
            listOf(PKG_A to VersionMod(apiVersion = ModApi.VERSION + 1)),
            forwardToDiagnostics = false,
        )
        try {
            val canonical = installLineOf(PKG_A)
            assertTrue("夹具必须是真失败行：$canonical", canonical.contains("active=false failure="))

            ModManageDiagnostics.record("mod.install pkg=$PKG_A active=true")
            assertTrue("把失败读成成功 ⇒ 诊断面必须没有失败记录", ModManageDiagnostics.snapshot().isEmpty())

            ModManageDiagnostics.record(canonical)
            assertNotNull("原失败行喂入才产生记录", ModManageDiagnostics.snapshot()[PKG_A])

            ModManageDiagnostics.reset()
            ModManageDiagnostics.record("mod.install pkg=$PKG_A active=false")
            assertEquals(
                "只有 active=false 无 failure= ⇒ 摘要为占位 ?, 实际=${ModManageDiagnostics.snapshot()}",
                "?",
                ModManageDiagnostics.snapshot()[PKG_A],
            )
        } finally {
            session.shutdown()
        }
    }

    // =================================================================
    // 组 E：停用对照（不是失败，不得误报）
    // =================================================================

    /**
     * E1：`.off` 停用不是失败：生产 `ModCarrierRegistry.providers` 过滤后 providers 为空，
     * `attach` 不产生该包 install 行、诊断面为空、列表标 enabled=false、skipped 为空。
     */
    @Test
    fun `E1 停用对照 - off标记包不产生失败读数也不进坏档`() {
        val root = tempGameRoot()
        writeModuleManifest(root, PKG_A)
        val rt2 = ModEnablement.rt2Dir(root.toFile())
        assertTrue("停用标记必须写成功", ModEnablement.setEnabled(rt2, PKG_A, false))
        val providers = listOf(PKG_A to FailingMod(noop = true))
            .filterNot { it.first in ModEnablement.disabledPackageIds(rt2) }
        assertTrue("生产同源过滤后 providers 为空", providers.isEmpty())

        val session = attach(root, providers)
        try {
            assertTrue("停用包不得有 mod.install 行：$logs", logs.none { it.startsWith("mod.install pkg=$PKG_A ") })
            assertTrue("停用不是失败 ⇒ 诊断面为空", ModManageDiagnostics.snapshot().isEmpty())
            val scan = ModManageScanner.scan(root.toFile())
            assertEquals("列表仍列出（供玩家重新启用）", listOf(PKG_A), scan.mods.map { it.packageId })
            assertFalse("enabled 必须读到停用态", scan.mods.single().enabled)
            assertTrue("停用不是坏档 ⇒ skipped 为空", scan.skipped.isEmpty())
            assertTrue("设置页失败小节为空", settingsFailureSection(scan, emptyMap()).isEmpty())
        } finally {
            session.shutdown()
        }
    }

    // =================================================================
    // 夹具：游戏根 / manifest / 容器 / 真装配 / 真存档
    // =================================================================

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-failure-visibility")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun rt2(root: Path): Path = root.resolve("mods").resolve("rt2")

    /** 模块 manifest（无 archetypes，够 attach 的声明快照与扫描解码）。 */
    private fun writeModuleManifest(
        root: Path,
        packageId: String,
        minApi: Int = 0,
        fileName: String = defaultManifestFileName(packageId),
    ) {
        val descriptor = ModuleDescriptor(packageId = packageId, minApi = minApi)
        Files.write(rt2(root).resolve(fileName), ManifestCodec.encode(descriptor))
    }

    /** 声明 manifest（带 archetype；数据版本迁移夹具要求能建实体上下文）。 */
    private fun writeDeclManifest(
        root: Path,
        packageId: String,
        fileName: String = defaultManifestFileName(packageId),
    ) {
        val manifest = ModManifest(
            packageId = packageId,
            archetypes = listOf(ArchetypeDecl("$packageId.trait")),
        )
        Files.write(rt2(root).resolve(fileName), ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest)))
    }

    private fun defaultManifestFileName(packageId: String): String = "${packageId.replace('.', '_')}.manifest"

    private fun writeChainManifest(root: Path, packageId: String, fileName: String, minApi: Int) {
        val descriptor = ModuleDescriptor(packageId = packageId, minApi = minApi)
        Files.write(rt2(root).resolve(fileName), ManifestCodec.encode(descriptor))
    }

    /** 合法 `.erbm` 容器（只加新函数 ANEW，无覆盖声明 ⇒ 免签名真目录夹具）。 */
    private fun writeContainer(root: Path, packageId: String, fileName: String) {
        val b = CodeBuilder()
        val entry = b.declareFunction("ANEW", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val module = CompiledModule(
            packageId,
            b.build(entry),
            listOf(FuncMeta(index = 0, key = "ANEW")),
            emptyMap(),
        )
        Files.write(rt2(root).resolve(fileName), ErbModuleContainer.encode(module, allowSuperCalls = true))
    }

    /**
     * 真装配入口（生产缝）：`logSink` 与 `GameHost.kt:1242` 同形（`record` + 文本日志）；
     * [forwardToDiagnostics]=false 用于反向证明，模拟该接线行缺失。
     */
    private fun attach(
        root: Path,
        providers: List<Pair<String, EraMod>>,
        store: VariableStore = VariableStore(),
        forwardToDiagnostics: Boolean = true,
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
                if (forwardToDiagnostics) ModManageDiagnostics.record(line)
            },
            modProviders = providers,
        ) ?: error("enabled=true 时真装配入口不应返回 null")
    }

    private fun binaryOps(store: VariableStore, root: Path): FileSystemOps = FileSystemOps(
        store,
        RootedSaveFileIo(root),
        SaveConfig(systemSaveInBinary = true, zipSaveData = true),
    )

    /** 整链装配夹具：base = ENTRY@0 / TARGET@1，各一条 `_RET`。 */
    private fun base(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("TARGET", b.here(), arity = 1, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    private val baseMetas: List<FuncMeta> = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "TARGET", argIntCount = 1),
    )

    /**
     * 真链装配入口（生产缝）：装配期 `logSink` 与 `GameHost.kt:1242` 的共享 `modLogSink` 同形
     * （`record` + 文本日志；装配期与 attach 期共用一个 lambda，Lane DP）；期望整链拒载时
     * 返回捕获到的异常。调用方保证夹具有 `.erbm` ⇒ 走 `assembleChain`（无容器时整块旁路）。
     */
    private fun captureChainFailure(root: Path, forwardToDiagnostics: Boolean = true): Throwable {
        logs.clear()
        try {
            ModChainAssembly.assemble(
                ChainAssemblyInputs(
                    root.toFile(), true, base(), baseMetas,
                    logSink = { line ->
                        logs += line
                        if (forwardToDiagnostics) ModManageDiagnostics.record(line)
                    },
                ),
            )
            fail("夹具必须被整链拒载（否则测不到失败形态）")
        } catch (expected: LinkOrderException) {
            return expected
        } catch (expected: IllegalArgumentException) {
            return expected
        }
        error("unreachable")
    }

    // =================================================================
    // 可见面断言助手（逐字复刻设置页渲染表达式，行号见各处断言消息）
    // =================================================================

    private fun installLines(): List<String> = logs.filter { it.startsWith("mod.install pkg=") }

    private fun installLineOf(packageId: String): String =
        installLines().firstOrNull { it.startsWith("mod.install pkg=$packageId ") }
            ?: error("缺少 $packageId 的 mod.install 上报行；logs=$logs")

    /**
     * 设置页「上次启动时装载失败」小节的渲染输入（SettingsActivity.kt:4976-4996）：
     * 标题 + 逐条 `{scan 里的显示名 ?: packageId}：{诊断摘要}`；failures 为空 ⇒ 整节不渲染。
     */
    private fun settingsFailureSection(scan: ModManageScan, failures: Map<String, String>): List<String> {
        if (failures.isEmpty()) return emptyList()
        val titles = scan.mods.associate { it.packageId to it.title }
        return listOf(zh.modManageFailureHeading) +
            failures.map { (packageId, reason) -> zh.modManageFailureLine(titles[packageId] ?: packageId, reason) }
    }

    private fun snapshotOfScan(root: Path): ModManageScan = ModManageScanner.scan(root.toFile())

    /** 运行时概览单行（ModRuntimeOverviewCard.kt:33 的同一组入参；账本用空 timing 快照）。 */
    private fun overviewRowOf(packageIds: List<String>, failures: Map<String, String>): ModRuntimeRow {
        val data = ModRuntimeData(
            activeIds = emptySet(),
            timingByPackage = emptyMap(),
            slowCountByPackage = emptyMap(),
            thresholdNanos = 16_000_000L,
        )
        val overview = buildModRuntimeOverview(packageIds, failures, data)
        checkNotNull(overview) { "data 非 null 时概览不得为 null" }
        return overview.rows.single()
    }

    // =================================================================
    // 被测 mod 载体
    // =================================================================

    private class VersionMod(
        override val apiVersion: Int = ModApi.VERSION,
    ) : EraMod {
        override fun onLoad(context: ModContext) = Unit
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    /** onLoad 抛固定原始异常（[noop]=true 时不抛，用于陪跑成功包）。 */
    private class FailingMod(private val noop: Boolean = false) : EraMod {
        override fun onLoad(context: ModContext) {
            if (!noop) error(ON_LOAD_FAILURE)
        }

        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    private class RequiresMod(override val requires: List<String> = emptyList()) : EraMod {
        override fun onLoad(context: ModContext) = Unit
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    /** 数据版本载体：onLoad 写载荷，onMigrate 可注入失败（真存档往返夹具，照迁移 E2E 同源）。 */
    private class MigratingMod(
        private val declaredDataVersion: Int,
        private val payload: String,
        private val migrateThrows: Boolean = false,
    ) : EraMod {
        val migrations: MutableList<Pair<Int, Int>> = mutableListOf()
        private var context: ModContext? = null

        override val dataVersion: Int get() = declaredDataVersion

        override fun onLoad(context: ModContext) {
            this.context = context
            context.storage.put(PAYLOAD_KEY, payload)
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit

        override fun onMigrate(fromDataVersion: Int, toDataVersion: Int) {
            migrations += fromDataVersion to toDataVersion
            if (migrateThrows) throw IllegalStateException(MIGRATE_FAILURE)
            val old = context?.storage?.get(PAYLOAD_KEY)
            context?.storage?.put(PAYLOAD_KEY, "migrated($fromDataVersion->$toDataVersion):$old")
        }
    }

    private companion object {
        private const val PKG_A: String = "mod.vis.a"
        private const val PKG_B: String = "mod.vis.b"
        private const val PKG_GHOST: String = "mod.vis.ghost"
        private const val PAYLOAD_KEY: String = "payload"
        private const val ON_LOAD_FAILURE: String = "onLoad 故意失败"
        private const val MIGRATE_FAILURE: String = "migrate 故意失败"
    }
}
