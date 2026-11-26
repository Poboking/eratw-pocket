package com.eratw.port.android.market

import com.eratw.port.android.ImportedPatchDetail
import com.eratw.port.android.ModImportCopy
import com.eratw.port.android.ModImportFailureKind
import com.eratw.port.android.ModImportOutcome
import com.eratw.port.android.modImportFailureTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 市场覆盖层纯逻辑的定向单测（CONTRACT-MKM-W1 §2 mkm-ui 判据 2；hermetic，零网络零盘）。
 *
 * 语义锚全部在 kojo-editor `packages/plugins/ui-market/src/`（只读参考），逐用例在注释里
 * 带行号：market-view.ts（分组 / 行动作 / 拒因表 / 更新话术 / 读数）、actions.ts（快照与
 * 乐观更新）、market.ts（版本比较与 minAppVersion 闸）、market-source.ts（端点行）。
 * 反向证明锚：`拒因表穷尽 18 码且同语言内互不重复` 钉的是 rejectPhrase 的 else 兜底臂
 * （摘掉该臂 = 未知码用例红）；`rowAction 三态` 钉的是 !appOk 早退臂（摘掉 = 版本不足用例红）。
 */
class MarketViewModelTest {

    private val copy = MarketCopy.ZH

    // ---------------------------------------------------------------- 夹具

    private fun entry(
        id: String,
        version: String = "1.0.0",
        kind: String = MarketContract.KIND_KOJO,
        name: String = id,
        minAppVersion: String = "0.0.1",
    ) = MarketIndexEntry(
        kind = kind,
        id = id,
        version = version,
        name = name,
        author = "作者",
        license = "MIT",
        redistribute = true,
        minAppVersion = minAppVersion,
        summary = null,
        sourceUrl = null,
        gameId = null,
        zipUrl = "https://example.invalid/$id-$version.zip",
        zipSha256 = "a".repeat(64),
        zipBytes = 1024L,
        fileCount = 2,
        unpackedBytes = 4096L,
        coverUrl = null,
    )

    private fun pack(
        id: String,
        version: String = "1.0.0",
        kind: String = MarketContract.KIND_KOJO,
        name: String = id,
        bytes: Long = 100L,
    ) = InstalledPack(
        kind = kind,
        id = id,
        version = version,
        name = name,
        author = "作者",
        license = "MIT",
        installedAtMs = 0L,
        containerSha256 = null,
        contentDigest = "0123456789abcdef".repeat(4),
        fileCount = 2,
        bytes = bytes,
    )

    private fun okIndex(packs: List<MarketIndexEntry>, fetchedAtMs: Long = 1_000L) = MarketIndexState.Ok(
        MarketIndex(
            schema = MarketContract.SCHEMA_VERSION,
            generatedAt = null,
            packs = packs,
            dropped = 0,
            coverIgnored = 0,
            fetchedAtMs = fetchedAtMs,
        ),
    )

    private fun snapshotOf(
        packs: List<MarketIndexEntry>,
        installed: List<InstalledPack> = emptyList(),
        updates: Map<String, UpdateCheckState?> = emptyMap(),
    ): MarketViewModel.MarketSnapshot = MarketOptimistic.snapshotOf(
        index = okIndex(packs),
        updates = updates,
        installed = installed,
        installedBytes = installed.sumOf { it.bytes },
        endpoint = MarketContract.DEFAULT_INDEX_URL,
    )

    private fun reduce(
        state: MarketViewModel.MarketUiState,
        event: MarketViewModel.MarketUiEvent,
    ): MarketViewModel.MarketUiState = MarketViewModel.reduce(state, event, copy)

    // ---------------------------------------------- 行键与显式态（market-view.ts :583-595/:267-276）

    @Test
    fun `行键形态与 TS 同形（kind 斜杠 id 艾特 version）`() {
        assertEquals("kojo-pack/demo@1.0.0", MarketViewModel.installedKey("kojo-pack", "demo", "1.0.0"))
        assertEquals(
            MarketViewModel.installedKey(MarketContract.KIND_DICT, "d1", "2.0.0"),
            MarketViewModel.indexRowKey(entry("d1", "2.0.0", kind = MarketContract.KIND_DICT)),
        )
    }

    @Test
    fun `显式态只吃服务三态判别联（absent、invalid、ok 空、ok 非空）`() {
        assertEquals(
            MarketViewModel.MarketViewStatus.LIST_MISSING,
            MarketViewModel.resolveStatus(MarketIndexState.Absent),
        )
        assertEquals(
            MarketViewModel.MarketViewStatus.LIST_BROKEN,
            MarketViewModel.resolveStatus(MarketIndexState.Invalid("problem")),
        )
        assertEquals(MarketViewModel.MarketViewStatus.NO_PACKS, MarketViewModel.resolveStatus(okIndex(emptyList())))
        assertEquals(
            MarketViewModel.MarketViewStatus.READY,
            MarketViewModel.resolveStatus(okIndex(listOf(entry("demo")))),
        )
    }

    // ------------------------------------------------------------ 行动作（market-view.ts rowAction :471-476）

    @Test
    fun `rowAction 三态——版本不足与 Failed 态不给按钮`() {
        // 版本闸不过 ⇒ none（哪怕索引说可更新）
        assertEquals(
            MarketViewModel.MarketRowAction.NONE,
            MarketViewModel.rowAction(appOk = false, update = UpdateCheckState.Updatable("9.9.9")),
        )
        // 本机没装 ⇒ install（「没装」是另一个动作，market-view.ts :473）
        assertEquals(MarketViewModel.MarketRowAction.INSTALL, MarketViewModel.rowAction(appOk = true, update = null))
        // 可更新 ⇒ update
        assertEquals(
            MarketViewModel.MarketRowAction.UPDATE,
            MarketViewModel.rowAction(appOk = true, update = UpdateCheckState.Updatable("1.1.0")),
        )
        // failed 也归不给按钮（market-view.ts :474：比较判据读不出时不给「安装」去盖没人核对过的东西）
        assertEquals(
            MarketViewModel.MarketRowAction.NONE,
            MarketViewModel.rowAction(appOk = true, update = UpdateCheckState.Failed("版本号读不出")),
        )
        assertEquals(
            MarketViewModel.MarketRowAction.NONE,
            MarketViewModel.rowAction(appOk = true, update = UpdateCheckState.Latest(sameContainer = null)),
        )
        assertEquals(
            MarketViewModel.MarketRowAction.NONE,
            MarketViewModel.rowAction(appOk = true, update = UpdateCheckState.Ahead("1.0.1")),
        )
        // Latest 的 sameContainer 分句形状（false = 容器不同）同样不给按钮
        assertEquals(
            MarketViewModel.MarketRowAction.NONE,
            MarketViewModel.rowAction(appOk = true, update = UpdateCheckState.Latest(sameContainer = false)),
        )
    }

    // ------------------------------------------------------------ 分组（market-view.ts buildGroups :604-629）

    @Test
    fun `分组按 KINDS 契约序、组内按名码元序再按 id、空组不出现`() {
        val groups = MarketViewModel.buildGroups(
            copy = copy,
            packs = listOf(
                entry("beta", kind = MarketContract.KIND_DICT),
                entry("b2", name = "Alpha", kind = MarketContract.KIND_DICT),
                entry("kojo1", name = "Alpha"),
            ),
            appVersion = "1.0.0",
            updates = emptyMap(),
            filterKind = null,
        )
        assertEquals(
            "组顺序 = KINDS 契约序",
            listOf(MarketContract.KIND_KOJO, MarketContract.KIND_DICT),
            groups.map { it.kind },
        )
        // 组内码元序：同名时按 id（kojo 组 Alpha/kojo1；dict 组 Alpha/b2 < beta）
        assertEquals(listOf("Alpha"), groups[0].rows.map { it.entry.name })
        assertEquals(listOf("Alpha", "beta"), groups[1].rows.map { it.entry.name })
        assertEquals(listOf("b2", "beta"), groups[1].rows.map { it.entry.id })
    }

    @Test
    fun `组内排序是码元序不是 locale 序（大写排在小写前）`() {
        val groups = MarketViewModel.buildGroups(
            copy = copy,
            packs = listOf(entry("lower", name = "alpha"), entry("upper", name = "Alpha")),
            appVersion = "1.0.0",
            updates = emptyMap(),
            filterKind = null,
        )
        assertEquals(listOf("Alpha", "alpha"), groups.single().rows.map { it.entry.name })
    }

    @Test
    fun `过滤 chips 换档只留那一组（filterKind = null 是全部）`() {
        val packs = listOf(entry("k", kind = MarketContract.KIND_KOJO), entry("d", kind = MarketContract.KIND_DICT))
        val all = MarketViewModel.buildGroups(copy, packs, "1.0.0", emptyMap(), null)
        assertEquals(2, all.size)
        val dictOnly = MarketViewModel.buildGroups(copy, packs, "1.0.0", emptyMap(), MarketContract.KIND_DICT)
        assertEquals(listOf(MarketContract.KIND_DICT), dictOnly.map { it.kind })
    }

    @Test
    fun `行的 appOk 与 installed 标记来自版本闸与更新三态`() {
        val groups = MarketViewModel.buildGroups(
            copy = copy,
            packs = listOf(entry("too_new", minAppVersion = "9.0.0"), entry("same", version = "1.0.0")),
            appVersion = "1.0.0",
            updates = mapOf(
                MarketViewModel.installedKey("kojo-pack", "same", "1.0.0") to
                    UpdateCheckState.Latest(sameContainer = null),
            ),
            filterKind = null,
        )
        val rows = groups.single().rows
        val tooNew = rows.first { it.entry.id == "too_new" }
        val same = rows.first { it.entry.id == "same" }
        assertFalse("minAppVersion 不满足 ⇒ appOk=false", tooNew.appOk)
        assertTrue("latest 态 ⇔ 已装同版本（market-view.ts :620 的等价形态）", same.installed)
        assertEquals(MarketViewModel.MarketRowAction.NONE, tooNew.action)
    }

    // ------------------------------------ 版本比较与 minAppVersion 闸（market.ts :165/:716-737/:847-859）

    @Test
    fun `版本比较是数值比较不是字典序，9 位段不溢出`() {
        assertEquals(0, MarketVersion.compareVersions("1.0.0", "1.0.0"))
        assertEquals(-1, MarketVersion.compareVersions("1.0.0", "1.0.1"))
        assertEquals(-1, MarketVersion.compareVersions("1.2.3", "1.10.0"))
        assertEquals(1, MarketVersion.compareVersions("1.10.0", "1.2.3"))
        assertEquals(1, MarketVersion.compareVersions("999999999.0.0", "1.0.0"))
        assertEquals(null, MarketVersion.compareVersions("1.0", "1.0.0"))
        assertEquals(null, MarketVersion.compareVersions("1.0.0a", "1.0.0"))
    }

    @Test
    fun `minAppVersion 闸剥预发布段后三段比较（严格 semver 会挡掉整个 beta 站）`() {
        assertTrue(MarketVersion.minAppVersionSatisfied("1.0.0", "1.0.0"))
        assertTrue("prerelease 按核心三段算（market.ts :847-859）", MarketVersion.minAppVersionSatisfied("1.0.0-rc1", "1.0.0"))
        assertTrue(MarketVersion.minAppVersionSatisfied("1.10.0", "1.2.3"))
        assertFalse(MarketVersion.minAppVersionSatisfied("0.9.0", "1.0.0"))
        assertFalse(MarketVersion.minAppVersionSatisfied("1.2.3", "1.10.0"))
        assertFalse("minAppVersion 非严格三段 ⇒ 拒", MarketVersion.minAppVersionSatisfied("1.0.0", "1.0"))
        assertFalse("当前版本剥完预发布段仍不合法 ⇒ 拒", MarketVersion.minAppVersionSatisfied("beta", "1.0.0"))
        // GATE2D 门③ UI 壳侧钉子：对外参数序 (currentAppVersion, minAppVersion) 不得与接口版接反
        assertFalse(MarketVersion.minAppVersionSatisfied(currentAppVersion = "0.4.0", minAppVersion = "0.5.0"))
        assertTrue(MarketVersion.minAppVersionSatisfied(currentAppVersion = "0.5.0", minAppVersion = "0.4.0"))
    }

    // ---------------------------------------- 拒因人话表（market-view.ts REJECT_TEXT :77-114 + rejectNote :163）

    @Test
    fun `拒因映射表的键与骨架封闭集逐一对账`() {
        assertEquals(MarketRejectCodes.ALL.toSet(), REJECT_PHRASE_KEYS.keys)
    }

    @Test
    fun `拒因表穷尽 18 码且同语言内互不重复（未知码走兜底）`() {
        for (one in listOf(MarketCopy.ZH, MarketCopy.EN, MarketCopy.JA)) {
            val phrases = MarketRejectCodes.ALL.map { MarketPhrases.rejectPhrase(one, it) }
            assertTrue("每码必有一句人话", phrases.all { it.isNotBlank() })
            assertEquals("同语言内同句对应两个码 = 反查漂移", phrases.size, phrases.toSet().size)
            // 未知码 ⇒ 兜底句带机器码原样（不静默）；且不与任何已知码的句子相撞
            val unknown = MarketPhrases.rejectPhrase(one, "no-such-code")
            assertTrue("兜底句必须原样带上未知码", unknown.contains("no-such-code"))
            assertFalse(phrases.contains(unknown))
        }
        // ZH 对拍两条（词面即底稿）：容器不对账与网络失败两句的原样中文
        assertEquals("取回来的包和注册表钉的不是同一份，本机没动", MarketPhrases.rejectPhrase(copy, MarketRejectCodes.SHA_MISMATCH))
        assertEquals(
            "这个包没能从注册表取回来（网络或地址的问题），本机没动",
            MarketPhrases.rejectPhrase(copy, MarketRejectCodes.DOWNLOAD_FAILED),
        )
    }

    // -------------------------------------------- 更新话术（market-view.ts updatePhrase :451-468）

    @Test
    fun `updatePhrase 四态全覆盖——只说事实不催动作`() {
        fun phraseOf(update: UpdateCheckState?): String {
            val row = MarketViewModel.MarketRow(
                entry = entry("demo"),
                appOk = true,
                installed = false,
                update = update,
                action = MarketViewModel.MarketRowAction.INSTALL,
            )
            return MarketPhrases.updatePhrase(copy, row)
        }
        assertEquals("没装 ⇒ 空串不占行（market-view.ts :453）", "", phraseOf(null))
        assertTrue("可更新带索引版本读数", phraseOf(UpdateCheckState.Updatable("1.2.0")).contains("1.2.0"))
        // Ahead 带本机版本读数（market-view.ts :458 `u.local.version`，CONTRACT-MKM-W6 缝 2）
        assertEquals("本机已是 1.1.0（比注册表那条新）", phraseOf(UpdateCheckState.Ahead("1.1.0")))
        // Latest 按 sameContainer 分句（market-view.ts :460）：false 明说重装会覆盖，true/null 归「已安装」
        assertEquals("已安装", phraseOf(UpdateCheckState.Latest(sameContainer = true)))
        assertEquals("已安装", phraseOf(UpdateCheckState.Latest(sameContainer = null)))
        assertEquals(
            "已安装（注册表那份与本机这份内容不同，重装会覆盖）",
            phraseOf(UpdateCheckState.Latest(sameContainer = false)),
        )
        assertEquals("failed 态由契约生成的那句照显（market-view.ts :462-466）", "原因原话", phraseOf(UpdateCheckState.Failed("原因原话")))
        assertEquals("failed 无话 ⇒ 兜底句", copy.updateFailedFallback, phraseOf(UpdateCheckState.Failed(" ")))
    }

    // ------------------------------------------------------------ 进度与装成话术（market-view.ts :414-442/:423-434）

    @Test
    fun `进度句总数已知给百分比（向下取整封顶 100）未知给已收读数`() {
        val busy = MarketPhrases.installBusyPhrase(copy, isUpdate = false, name = "Alpha")
        assertEquals(
            "$busy（25%）",
            MarketPhrases.installProgressPhrase(copy, false, "Alpha", doneBytes = 250, totalBytes = 1000),
        )
        assertEquals(
            "$busy（99%）",
            MarketPhrases.installProgressPhrase(copy, false, "Alpha", doneBytes = 999, totalBytes = 1000),
        )
        assertEquals(
            "超卖只许少说不许超（封顶 100）",
            "$busy（100%）",
            MarketPhrases.installProgressPhrase(copy, false, "Alpha", doneBytes = 2500, totalBytes = 1000),
        )
        assertTrue(
            "总数未知（chunked 等）⇒ 报已收多少，不编假百分比",
            MarketPhrases
                .installProgressPhrase(copy, false, "Alpha", doneBytes = 512, totalBytes = 0)
                .contains("512 B"),
        )
        assertTrue("更新动作换「更新」那半边", MarketPhrases.installProgressPhrase(copy, true, "Alpha", 1, 0).contains("更新"))
    }

    @Test
    fun `装成与更新成的版本一律取记账那条`() {
        assertEquals("已装上「Alpha」（版本 1.0.0）", MarketPhrases.installDonePhrase(copy, false, "Alpha", "1.0.0"))
        assertEquals("已把「Alpha」更新到版本 1.0.1", MarketPhrases.installDonePhrase(copy, true, "Alpha", "1.0.1"))
    }

    // -------------------------------------------- 卸载与告示话术（actions.ts :237-254、market-view.ts :306-328）

    @Test
    fun `卸载三态各有一句实话（NotFound 是另一句，不是失败）`() {
        assertEquals("已卸掉「Alpha」（已装列表与市场目录都已清除）", MarketPhrases.uninstallPhrase(copy, "Alpha", UninstallOutcome.Ok))
        assertEquals("本机没有这个包，不用卸", MarketPhrases.uninstallPhrase(copy, "Alpha", UninstallOutcome.NotFound))
        assertTrue(MarketPhrases.uninstallPhrase(copy, "Alpha", UninstallOutcome.Failed("盘满")).contains("盘满"))
    }

    @Test
    fun `剔除与封面告示一条没丢就空串不占行`() {
        assertEquals("", MarketPhrases.droppedNotice(copy, 0))
        assertTrue(MarketPhrases.droppedNotice(copy, 2).contains("2"))
        assertEquals("", MarketPhrases.coverIgnoredNotice(copy, 0))
        assertTrue(MarketPhrases.coverIgnoredNotice(copy, 1).contains("1"))
    }

    // ---------------------------------------- 读数（market-view.ts formatBytes :373-378、formatClock :331-336 等）

    @Test
    fun `体积读数阈值固定（别让面板出现 1048576）`() {
        assertEquals("512 B", MarketReadouts.formatBytes(512L))
        assertEquals("2.0 KB", MarketReadouts.formatBytes(2048L))
        assertEquals("1.5 MB", MarketReadouts.formatBytes(1_572_864L))
        assertEquals("—", MarketReadouts.formatBytes(-1L))
    }

    @Test
    fun `数据时间短读数——0 与缺席给还没有，正值给本地时刻形状`() {
        assertEquals("—", MarketReadouts.formatClock(0L))
        assertTrue(
            MarketReadouts.formatClock(1_000L).matches(Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}")),
        )
        assertEquals(copy.noDataTime, MarketPhrases.syncTimePhrase(copy, MarketIndexState.Absent))
        assertEquals(
            copy.noDataTime,
            MarketPhrases.syncTimePhrase(copy, okIndex(emptyList(), fetchedAtMs = 0L)),
        )
        val synced = MarketPhrases.syncTimePhrase(copy, okIndex(emptyList()))
        assertTrue(synced.startsWith(copy.syncTimeFormat.substringBefore(" {")))
    }

    @Test
    fun `已装计数短语——一条没装连占用 0 都不占行`() {
        assertEquals("", MarketReadouts.installedCountPhrase(copy, 0, 0L))
        assertTrue(MarketReadouts.installedCountPhrase(copy, 2, 2048L).contains("2 个"))
        assertEquals("3 个", MarketReadouts.groupCountPhrase(copy, 3))
    }

    // ------------------------------------------------------------ 快照与乐观更新（actions.ts :73-93/:130-154）

    @Test
    fun `快照装配——非 Ok 态不给 packs`() {
        val invalid = MarketOptimistic.snapshotOf(
            MarketIndexState.Invalid("bad"),
            emptyMap(),
            emptyList(),
            0L,
            MarketContract.DEFAULT_INDEX_URL,
        )
        assertEquals(0, invalid.packs.size)
        val ok = snapshotOf(listOf(entry("demo")))
        assertEquals(1, ok.packs.size)
    }

    @Test
    fun `乐观安装——同键替换否则追加，行内更新三态随本机版本重算`() {
        val snap = snapshotOf(listOf(entry("demo", "1.0.0")))
        val key = MarketViewModel.installedKey("kojo-pack", "demo", "1.0.0")
        // 追加：同版本装成 ⇒ 行内三态变 Latest（目录源无容器事实 = sameContainer null，乐观行不演出旧按钮）
        val appended = MarketOptimistic.afterInstalled(snap, pack("demo", "1.0.0", bytes = 300))
        assertEquals(1, appended.installed.size)
        assertEquals(300L, appended.installedBytes)
        assertEquals(UpdateCheckState.Latest(sameContainer = null), appended.updates[key])
        // 容器源装回且容器摘要 = 索引 zip 锚 ⇒ sameContainer true
        val container = MarketOptimistic.afterInstalled(
            snap,
            pack("demo", "1.0.0", bytes = 300).copy(containerSha256 = "a".repeat(64)),
        )
        assertEquals(UpdateCheckState.Latest(sameContainer = true), container.updates[key])
        // 替换：同键重装字节按差值累加
        val replaced = MarketOptimistic.afterInstalled(appended, pack("demo", "1.0.0", bytes = 200))
        assertEquals(1, replaced.installed.size)
        assertEquals(200L, replaced.installedBytes)
        // 本机版本高于索引 ⇒ Ahead 带本机版本；低于 ⇒ Updatable（market.ts updateRelationOf :1011-1014）
        val ahead = MarketOptimistic.afterInstalled(snap, pack("demo", "1.1.0"))
        assertEquals(UpdateCheckState.Ahead("1.1.0"), ahead.updates[key])
        val updatable = MarketOptimistic.afterInstalled(snap, pack("demo", "0.9.0"))
        assertEquals(UpdateCheckState.Updatable("1.0.0"), updatable.updates[key])
    }

    @Test
    fun `按包卸载乐观——单次摘除该包全部版本且不同包不受影响（CONTRACT-MKM-W4-UI3 裁定 3）`() {
        val snap = snapshotOf(
            packs = listOf(entry("demo"), entry("keep")),
            installed = listOf(
                pack("demo", "1.0.0", bytes = 100),
                pack("demo", "1.1.0", bytes = 200),
                pack("keep", bytes = 50),
            ),
        )
        val out = MarketOptimistic.afterPackageUninstalled(snap, pack("demo", "1.1.0"))
        assertEquals("该包全部版本一并摘除（被去重隐藏的旧版本也不许残留显示面）", 1, out.installed.size)
        assertEquals("keep", out.installed.single().id)
        assertEquals("占用按各版本记账条之和扣减", 50L, out.installedBytes)
        assertNull("本机一条都没有 ⇒ 三态归「没装」", out.updates[MarketViewModel.installedKey("kojo-pack", "demo", "1.0.0")])
        // 字节扣减不为负
        val over = MarketOptimistic.afterPackageUninstalled(
            snapshotOf(
                emptyList(),
                listOf(pack("demo", "1.0.0", bytes = 100), pack("demo", "1.1.0", bytes = 200)),
            ),
            pack("demo", "1.1.0"),
        )
        assertEquals(0L, over.installedBytes)
    }

    @Test
    fun `按包卸载成败折叠——全成才出成功、任一失败取首个 problem、全没卸成是不用卸`() {
        // 双版本都成功 = Ok（宿主循环据此出成功文案，不带版本号）
        assertEquals(
            UninstallOutcome.Ok,
            MarketPackageUninstall.fold(listOf(UninstallOutcome.Ok, UninstallOutcome.Ok)),
        )
        // 任一失败 = 失败文案，取首个 problem（第二个失败的原话不上屏，detail 纪律照旧）
        assertEquals(
            UninstallOutcome.Failed("盘满"),
            MarketPackageUninstall.fold(
                listOf(UninstallOutcome.Ok, UninstallOutcome.Failed("盘满"), UninstallOutcome.Failed("第二个原话")),
            ),
        )
        // NotFound 不计失败（该版本目录已不在位，端态同样是「没了」）：有 Ok 兜底 = Ok
        assertEquals(
            UninstallOutcome.Ok,
            MarketPackageUninstall.fold(listOf(UninstallOutcome.Ok, UninstallOutcome.NotFound)),
        )
        // 一条都没卸成 = NotFound（本机没有这个包，不用卸）；空表（快照滞后兜底）同
        assertEquals(
            UninstallOutcome.NotFound,
            MarketPackageUninstall.fold(listOf(UninstallOutcome.NotFound, UninstallOutcome.NotFound)),
        )
        assertEquals(UninstallOutcome.NotFound, MarketPackageUninstall.fold(emptyList()))
    }

    @Test
    fun `已装行——按包去重后种类契约序、名码元序、摘要前 8 位与来源`() {
        // CONTRACT-MKM-W4-UI3 裁定 2：同 (kind,id) 只留最高版本一行（组长裁定偏离
        // market-view.ts buildInstalledRows :647-663 的全版本列出）；upper 的 1.0.0 被去重
        val rows = MarketOptimistic.buildInstalledRows(
            copy,
            listOf(
                pack("beta", kind = MarketContract.KIND_DICT, name = "Beta"),
                pack("lower", name = "alpha"),
                pack("upper", name = "Alpha", version = "2.0.0"),
                pack("upper", name = "Alpha", version = "1.0.0"),
                pack("weird", kind = "weird-kind", name = "X"),
            ),
        )
        assertEquals(
            listOf(
                listOf("upper", "Alpha", "2.0.0"),
                listOf("lower", "alpha", "1.0.0"),
                listOf("beta", "Beta", "1.0.0"),
                listOf("weird", "X", "1.0.0"),
            ),
            rows.map { listOf(it.rec.id, it.rec.name, it.rec.version) },
        )
        assertEquals("01234567", rows[0].digestPrefix)
        assertEquals(copy.installedSourceFolder, rows[0].sourceLabel)
        val archived = MarketOptimistic
            .buildInstalledRows(copy, listOf(pack("demo").copy(containerSha256 = "a".repeat(64))))
            .single()
        assertEquals(copy.installedSourceContainer, archived.sourceLabel)
        val noDigest = MarketOptimistic
            .buildInstalledRows(copy, listOf(pack("demo").copy(contentDigest = null)))
            .single()
        assertNull("目录源没有内容摘要 ⇒ 前缀为 null（不拿容器摘要顶替）", noDigest.digestPrefix)
    }

    // ----------------------- 已装行按包去重（CONTRACT-MKM-W4-UI3 裁定 2：偏离 market-view.ts :647-663）

    @Test
    fun `已装行按包去重——同 kind,id 双版本只留最高版、不同 id 不受影响、空表照旧`() {
        // 双版本 → 单行最高版；数值比较不是字典序（1.2.3 < 1.10.0）
        assertEquals(
            "1.10.0",
            MarketOptimistic
                .buildInstalledRows(copy, listOf(pack("demo", "1.2.3"), pack("demo", "1.10.0")))
                .single().rec.version,
        )
        assertEquals(
            "1.1.0",
            MarketOptimistic
                .buildInstalledRows(copy, listOf(pack("demo", "1.0.0"), pack("demo", "1.1.0")))
                .single().rec.version,
        )
        // 输入顺序不影响结论
        assertEquals(
            "1.1.0",
            MarketOptimistic
                .buildInstalledRows(copy, listOf(pack("demo", "1.1.0"), pack("demo", "1.0.0")))
                .single().rec.version,
        )
        // 不同 id 不受影响；同 id 跨 kind 也不受影响（去重键 = kind+id 两元组）
        val distinct = MarketOptimistic.buildInstalledRows(
            copy,
            listOf(
                pack("a", "1.0.0"),
                pack("b", "2.0.0"),
                pack("a", "3.0.0", kind = MarketContract.KIND_DICT),
            ),
        )
        assertEquals(3, distinct.size)
        // 空表照旧
        assertTrue(MarketOptimistic.buildInstalledRows(copy, emptyList()).isEmpty())
        // 任一边不是合法三段退字典序兜底（去重不许挑输入，两种输入序同一结论）
        assertEquals(
            "beta",
            MarketOptimistic
                .buildInstalledRows(copy, listOf(pack("demo", "beta"), pack("demo", "1.0.0")))
                .single().rec.version,
        )
        assertEquals(
            "beta",
            MarketOptimistic
                .buildInstalledRows(copy, listOf(pack("demo", "1.0.0"), pack("demo", "beta")))
                .single().rec.version,
        )
    }

    // ------------------------------------------ 状态机转移（照 ModImportUiReducer 的 reduce 形态）

    @Test
    fun `打开覆盖层两段式——本地段秒上屏并回填端点草稿`() {
        var state = reduce(MarketViewModel.initial("1.0.0"), MarketViewModel.MarketUiEvent.ReloadStarted)
        assertTrue("busy 只罩本地读那一下的起点", state.busy)
        state = reduce(state, MarketViewModel.MarketUiEvent.LocalLoaded(snapshotOf(listOf(entry("demo")))))
        assertFalse("本地段到手 busy 归还（market-panel.ts:213）", state.busy)
        assertEquals("端点草稿回填现值", MarketContract.DEFAULT_INDEX_URL, state.endpointDraft)
        assertEquals(1, state.groups.size)
    }

    @Test
    fun `过滤 chips 换档重建分组且保留快照`() {
        val base = reduce(
            MarketViewModel.initial("1.0.0"),
            MarketViewModel.MarketUiEvent.LocalLoaded(
                snapshotOf(listOf(entry("k"), entry("d", kind = MarketContract.KIND_DICT))),
            ),
        )
        assertEquals(2, base.groups.size)
        val filtered = reduce(base, MarketViewModel.MarketUiEvent.FilterChanged(MarketContract.KIND_DICT))
        assertEquals(listOf(MarketContract.KIND_DICT), filtered.groups.map { it.kind })
        assertEquals(base.snapshot, filtered.snapshot)
    }

    @Test
    fun `安装全程——互斥闸占用、进度句与阶段、装成乐观落账`() {
        val snap = snapshotOf(listOf(entry("demo", "1.0.0")))
        val key = MarketViewModel.installedKey("kojo-pack", "demo", "1.0.0")
        var state = reduce(
            MarketViewModel.initial("1.0.0"),
            MarketViewModel.MarketUiEvent.LocalLoaded(snap),
        )
        state = reduce(state, MarketViewModel.MarketUiEvent.InstallStarted(key, "正在安装「demo」…"))
        assertEquals(key, state.installingKey)
        assertEquals(MarketViewModel.InstallPhase.DOWNLOAD, state.phase)
        // 下载中段：进度句接进同一条状态行（不弹窗）
        state = reduce(
            state,
            MarketViewModel.MarketUiEvent.InstallProgress(
                MarketInstallPhase.DOWNLOAD,
                doneBytes = 500,
                totalBytes = 1000,
            ),
        )
        assertTrue(state.note.contains("50%"))
        assertEquals(MarketViewModel.InstallPhase.DOWNLOAD, state.phase)
        // 下载收口那笔照旧直通（字节口径照旧）：仍是 DOWNLOAD 段，不再从字节流推断阶段（W6 缝 3）
        state = reduce(
            state,
            MarketViewModel.MarketUiEvent.InstallProgress(
                MarketInstallPhase.DOWNLOAD,
                doneBytes = 1000,
                totalBytes = 1000,
            ),
        )
        assertEquals(MarketViewModel.InstallPhase.DOWNLOAD, state.phase)
        // 服务 EXTRACT 回调到场：复用既有解压与核对句（总量 0 = 不定态进度条）
        state = reduce(state, MarketViewModel.MarketUiEvent.InstallProgress(MarketInstallPhase.EXTRACT, 0, 0))
        assertEquals(MarketViewModel.InstallPhase.EXTRACT, state.phase)
        assertEquals("包已取回，正在解压并核对内容…", state.note)
        // 服务 COMMIT 回调到场：复用既有搬运句
        state = reduce(state, MarketViewModel.MarketUiEvent.InstallProgress(MarketInstallPhase.COMMIT, 300, 0))
        assertEquals(MarketViewModel.InstallPhase.COMMIT, state.phase)
        assertEquals("正在搬进本机市场库…", state.note)
        // 装成：互斥闸归还 + 乐观落账（行立刻出现）+ 成句版本取记账那条
        state = reduce(
            state,
            MarketViewModel.MarketUiEvent.InstallFinished(
                key,
                "已装上「demo」（版本 1.0.0）",
                pack("demo", "1.0.0", bytes = 300),
            ),
        )
        assertNull(state.installingKey)
        assertNull(state.progress)
        assertFalse(state.noteWarn)
        assertEquals(1, state.snapshot?.installed?.size)
        assertEquals(300L, state.snapshot?.installedBytes)
        assertEquals(UpdateCheckState.Latest(sameContainer = null), state.snapshot?.updates?.get(key))
        // 行动作翻面：装成后这一行不再给安装按钮
        val row = state.groups.single().rows.single()
        assertEquals(MarketViewModel.MarketRowAction.NONE, row.action)
        assertTrue(row.installed)
    }

    @Test
    fun `安装失败——出声、闸归还、不落账`() {
        val snap = snapshotOf(listOf(entry("demo", "1.0.0")), installed = emptyList())
        val key = MarketViewModel.installedKey("kojo-pack", "demo", "1.0.0")
        var state = reduce(MarketViewModel.initial("1.0.0"), MarketViewModel.MarketUiEvent.LocalLoaded(snap))
        state = reduce(state, MarketViewModel.MarketUiEvent.InstallStarted(key, "正在安装「demo」…"))
        state = reduce(state, MarketViewModel.MarketUiEvent.InstallFinished(key, "「demo」没装成：网络的问题", null))
        assertNull(state.installingKey)
        assertTrue(state.noteWarn)
        assertTrue(state.note.contains("没装成"))
        assertEquals(0, state.snapshot?.installed?.size)
    }

    @Test
    fun `卸载确认门与按包乐观移除——未确认前一个字节都不动`() {
        val snap = snapshotOf(listOf(entry("demo")), installed = listOf(pack("demo", bytes = 300)))
        var state = reduce(MarketViewModel.initial("1.0.0"), MarketViewModel.MarketUiEvent.LocalLoaded(snap))
        val row = state.installedRows.single()
        // 未确认：只置确认目标，不叫服务（actions.ts :242-244）
        state = reduce(state, MarketViewModel.MarketUiEvent.UninstallConfirm(row))
        assertEquals(row, state.pendingUninstall)
        state = reduce(state, MarketViewModel.MarketUiEvent.UninstallStarted(row.key, "正在卸掉「demo」…"))
        assertEquals(row.key, state.installingKey)
        state = reduce(
            state,
            MarketViewModel.MarketUiEvent.PackageUninstallFinished(
                row.key,
                "已卸掉「demo」（已装列表与市场目录都已清除）",
                row.rec,
            ),
        )
        assertNull("卸成后确认框收起", state.pendingUninstall)
        assertNull(state.installingKey)
        assertEquals(0, state.snapshot?.installed?.size)
        assertEquals(0L, state.snapshot?.installedBytes)
    }

    @Test
    fun `装卸失败后的权威重读静默替换快照（note 原样保留）`() {
        var state = reduce(
            MarketViewModel.initial("1.0.0"),
            MarketViewModel.MarketUiEvent.LocalLoaded(snapshotOf(listOf(entry("demo")))),
        )
        state = reduce(state, MarketViewModel.MarketUiEvent.InstallFinished("k", "「demo」没装成：网络的问题", null))
        val noteBefore = state.note
        state = reduce(
            state,
            MarketViewModel.MarketUiEvent.InstalledRevalidated(
                snapshotOf(listOf(entry("demo"), entry("fresh"))),
            ),
        )
        assertEquals(2, state.snapshot?.packs?.size)
        assertEquals("重读不改口（失败句还在，market-panel.ts:272-275）", noteBefore, state.note)
        assertTrue(state.noteWarn)
    }

    @Test
    fun `端点行——草稿回填、保存回执落状态行、快照地址同步`() {
        var state = reduce(
            MarketViewModel.initial("1.0.0"),
            MarketViewModel.MarketUiEvent.LocalLoaded(snapshotOf(listOf(entry("demo")))),
        )
        state = reduce(state, MarketViewModel.MarketUiEvent.EndpointDraftChanged("https://mirror.example/index.json"))
        assertEquals("https://mirror.example/index.json", state.endpointDraft)
        state = reduce(
            state,
            MarketViewModel.MarketUiEvent.EndpointSaved(
                "https://mirror.example/index.json",
                "已改用自建索引地址（只放行 GitHub 系主机，下次刷新列表生效）",
            ),
        )
        assertEquals("已改用自建索引地址（只放行 GitHub 系主机，下次刷新列表生效）", state.note)
        assertEquals("https://mirror.example/index.json", state.snapshot?.endpoint)
    }

    // ------------------------------------------ 端点行投影（market-source.ts :30-37/:126-135/:161-182）

    @Test
    fun `端点覆盖判定与保存回执四态`() {
        assertTrue(MarketEndpointUi.isOverridden("https://mirror.example/index.json"))
        assertFalse(MarketEndpointUi.isOverridden(MarketContract.DEFAULT_INDEX_URL))
        assertEquals(
            copy.endpointStatusDefault,
            MarketEndpointUi.endpointStatus(copy, MarketContract.DEFAULT_INDEX_URL),
        )
        assertEquals(
            copy.endpointStatusOverridden,
            MarketEndpointUi.endpointStatus(copy, "https://mirror.example/index.json"),
        )
        assertEquals(copy.endpointKept, MarketEndpointUi.endpointNote(copy, EndpointEditResult.Kept))
        assertEquals(copy.endpointResetDone, MarketEndpointUi.endpointNote(copy, EndpointEditResult.Reset))
        assertEquals(
            copy.endpointSetDone,
            MarketEndpointUi.endpointNote(copy, EndpointEditResult.Set("https://mirror.example/index.json")),
        )
        assertEquals("没有保存：协议必须是 https", MarketEndpointUi.endpointNote(copy, EndpointEditResult.Invalid("协议必须是 https")))
    }

    // --------------------------------- 装进游戏（CONTRACT-MKM-W3-UI2：桥五态 + 忙态单飞）

    /** 桥 Ok 态夹具：导入链成功详情（packageId 形态照 MarketGameBridge 的 kojo- + 8 hex）。 */
    private fun bridgeDetail(packageId: String = "kojo-deadbeef") = ImportedPatchDetail(
        patchFileName = "$packageId.erbm",
        functionCount = 3,
        instructionCount = 120,
        entryFunction = "SYSTEM_TITLE",
        sourceFileNames = listOf("a.ERB"),
        parseWarnings = emptyList(),
        modRuntimeOffHint = null,
    )

    @Test
    fun `桥五态各有一句人话且互不重复（五态封闭 when 穷尽，无未知臂可摘）`() {
        val failure = ModImportOutcome.Failed(ModImportFailureKind.COMPILE, "a.ERB:12 编译错误原话")
        val outcomes = listOf(
            MarketBridgeOutcome.Ok("kojo-deadbeef", bridgeDetail()),
            MarketBridgeOutcome.NotFound,
            MarketBridgeOutcome.EmptyPack,
            MarketBridgeOutcome.ImportFailed(failure),
            MarketBridgeOutcome.LedgerFailed("台账读不出来原话"),
        )
        val notes = outcomes.map { MarketBridgeUi.bridgeNote(copy, "demo", it) }
        assertTrue("五态每态必有一句", notes.all { it.isNotBlank() })
        assertEquals("同语言内两句同文 = 反查漂移", notes.size, notes.toSet().size)
        // Ok：packageId 短码上屏 + 只说已入 mods/rt2 与重启生效（裁定 5：总闸口径不进文案）
        assertTrue(notes[0].contains("kojo-deadbeef"))
        assertTrue(notes[0].contains("mods/rt2"))
        assertFalse("总闸提示不进成功句（设置页开关已有说明）", notes[0].contains("总闸"))
        // ImportFailed：包名与失败类别人话上屏，failure 原文不上屏（契约 §2.4 detail 不上屏纪律）
        assertTrue(notes[3].contains("demo"))
        assertEquals(
            "（{1} 槽 = 类别人话，复用既有导入失败句式）",
            notes[3],
            copy.fill(
                copy.bridgeFailedFormat,
                "demo",
                modImportFailureTitle(ModImportFailureKind.COMPILE, ModImportCopy.of(copy.lang)),
            ),
        )
        assertFalse("failure 原文不上屏", notes[3].contains("编译错误原话"))
        assertEquals(
            "STORAGE 类同样走类别标题（不是原话）",
            copy.fill(
                copy.bridgeFailedFormat,
                "demo",
                modImportFailureTitle(ModImportFailureKind.STORAGE, ModImportCopy.of(copy.lang)),
            ),
            MarketBridgeUi.bridgeNote(
                copy,
                "demo",
                MarketBridgeOutcome.ImportFailed(ModImportOutcome.Failed(ModImportFailureKind.STORAGE, "盘满原话")),
            ),
        )
        // LedgerFailed：人话必须交代「导入本体已落盘、只有记账没成」（Outcome KDoc），problem 原话不上屏
        assertTrue(notes[4].contains("mods/rt2"))
        assertFalse("problem 原话不上屏", notes[4].contains("台账读不出来原话"))
        // 忙态短语：包名进句
        assertEquals("正在把「demo」装进游戏…", MarketBridgeUi.bridgeBusyPhrase(copy, "demo"))
    }

    @Test
    fun `装进游戏忙态单飞——闸占用时重复点击不触发第二次 import`() {
        var state = reduce(
            MarketViewModel.initial("1.0.0"),
            MarketViewModel.MarketUiEvent.LocalLoaded(
            snapshotOf(packs = emptyList(), installed = listOf(pack("demo"))),
        ),
        )
        val row = state.installedRows.single()
        assertTrue("空闲可点", MarketBridgeUi.bridgeImportEnabled(state))
        // 第一次点击：占用与装/卸同一把 installingKey 闸（宿主入口与行按钮同一判据单源）
        state = reduce(state, MarketViewModel.MarketUiEvent.InstallStarted(row.key, "正在把「demo」装进游戏…"))
        assertEquals(row.key, state.installingKey)
        assertFalse("进行中：单飞闸拒绝重复点击（第二次 import 进不去）", MarketBridgeUi.bridgeImportEnabled(state))
        // 结果落屏：闸归还；Ok 半边 warn = false 且市场库不动（乐观退化为幂等替换）
        state = reduce(
            state,
            MarketViewModel.MarketUiEvent.InstallFinished(row.key, "已装进游戏（kojo-deadbeef）…", pack("demo")),
        )
        assertNull(state.installingKey)
        assertFalse(state.noteWarn)
        assertTrue("闸归还后可再点", MarketBridgeUi.bridgeImportEnabled(state))
        assertEquals("装进游戏不动市场库（同包原地替换）", 1, state.snapshot?.installed?.size)
    }

    @Test
    fun `装进游戏失败半边——出声、闸归还、市场库不动`() {
        var state = reduce(
            MarketViewModel.initial("1.0.0"),
            MarketViewModel.MarketUiEvent.LocalLoaded(
            snapshotOf(packs = emptyList(), installed = listOf(pack("demo"))),
        ),
        )
        val row = state.installedRows.single()
        state = reduce(state, MarketViewModel.MarketUiEvent.InstallStarted(row.key, "正在把「demo」装进游戏…"))
        state = reduce(state, MarketViewModel.MarketUiEvent.InstallFinished(row.key, "「demo」没装进游戏：补丁编译失败（未落盘）", null))
        assertNull("失败半边闸归还", state.installingKey)
        assertTrue("失败出声且是失败色", state.noteWarn)
        assertTrue(state.note.contains("没装进游戏"))
        assertEquals("导入失败不动市场库", 1, state.snapshot?.installed?.size)
    }

    // ----------------- 一键更新链（CONTRACT-MKM-W4-UI3 裁定 1：假桥注入，JVM 级数调用次数）

    @Test
    fun `更新链编排——装库成功且台账有记录才调桥且只调一次（假桥数次数）`() {
        var calls = 0
        var gotPack: InstalledPack? = null
        var gotRuntime: Boolean? = null
        val fakeBridge: (InstalledPack, Boolean) -> MarketBridgeOutcome = { pack, modRuntimeEnabled ->
            calls++
            gotPack = pack
            gotRuntime = modRuntimeEnabled
            MarketBridgeOutcome.Ok("kojo-deadbeef", bridgeDetail())
        }
        val rec = pack("demo", "1.1.0")
        // 装库成功 + 台账有记录 ⇒ 桥被调一次，实参 = 新版本记账条 + 总闸读数原样
        val chained = MarketUpdateChain.run(
            installOk = true,
            ledgerHasRecord = true,
            pack = rec,
            modRuntimeEnabled = true,
            importInstalled = fakeBridge,
        )
        assertEquals("台账有记录 ⇒ 桥被调一次", 1, calls)
        assertEquals("实参 = 新版本记账条", "1.1.0", gotPack?.version)
        assertEquals("总闸读数原样进桥", true, gotRuntime)
        assertTrue(chained is MarketBridgeOutcome.Ok)
        // 台账无记录（从未装进游戏）⇒ 桥不被调（只装库，不擅自改游戏状态）
        calls = 0
        assertNull(
            MarketUpdateChain.run(true, false, rec, modRuntimeEnabled = true, importInstalled = fakeBridge),
        )
        assertEquals("台账无记录 ⇒ 桥零调用", 0, calls)
        // 装库失败 ⇒ 桥不被调（既有拒因文案收口，不进桥段）
        assertNull(
            MarketUpdateChain.run(false, true, rec, modRuntimeEnabled = true, importInstalled = fakeBridge),
        )
        assertNull(
            MarketUpdateChain.run(false, false, rec, modRuntimeEnabled = true, importInstalled = fakeBridge),
        )
        assertEquals("装库失败 ⇒ 桥零调用", 0, calls)
    }

    @Test
    fun `更新链完成句两形态——只装库用既有更新成句、链成用新句、桥失败照既有五态`() {
        // 桥段没跑（台账无记录）= 只装库形态：既有 updateDoneFormat（版本取记账那条）
        assertEquals(
            copy.fill(copy.updateDoneFormat, "demo", "1.1.0"),
            MarketUpdateChain.doneNote(copy, "demo", "1.1.0", chainOutcome = null),
        )
        // 桥 Ok = 装库+装游戏形态（新句；装库成功段不重复播报）
        assertEquals(
            copy.fill(copy.updateChainDoneFormat, "demo", "1.1.0"),
            MarketUpdateChain.doneNote(copy, "demo", "1.1.0", MarketBridgeOutcome.Ok("kojo-deadbeef", bridgeDetail())),
        )
        // 桥失败 = 既有 bridgeNote 五态（ImportFailed 的 failure 原文不上屏，detail 纪律照旧）
        val failure = MarketBridgeOutcome.ImportFailed(
            ModImportOutcome.Failed(ModImportFailureKind.COMPILE, "编译错误原话"),
        )
        val failedNote = MarketUpdateChain.doneNote(copy, "demo", "1.1.0", failure)
        assertEquals(
            copy.fill(
                copy.bridgeFailedFormat,
                "demo",
                modImportFailureTitle(ModImportFailureKind.COMPILE, ModImportCopy.of(copy.lang)),
            ),
            failedNote,
        )
        assertFalse("failure 原文不上屏", failedNote.contains("编译错误原话"))
        assertEquals(
            copy.bridgeLedgerFailed,
            MarketUpdateChain.doneNote(copy, "demo", "1.1.0", MarketBridgeOutcome.LedgerFailed("台账原话")),
        )
    }

    @Test
    fun `一键更新落屏——乐观重算去重行集刷新且 updateCheck 随新版本转 Latest（裁定 4）`() {
        val key = MarketViewModel.installedKey("kojo-pack", "demo", "1.1.0")
        val snap = snapshotOf(
            packs = listOf(entry("demo", "1.1.0")),
            installed = listOf(pack("demo", "1.0.0", bytes = 100)),
            updates = mapOf(key to UpdateCheckState.Updatable("1.1.0")),
        )
        var state = reduce(MarketViewModel.initial("1.0.0"), MarketViewModel.MarketUiEvent.LocalLoaded(snap))
        assertEquals("去重后一行（旧版本）", 1, state.installedRows.size)
        assertEquals(MarketViewModel.MarketRowAction.UPDATE, state.groups.single().rows.single().action)
        state = reduce(state, MarketViewModel.MarketUiEvent.InstallStarted(key, "正在更新「demo」…"))
        state = reduce(
            state,
            MarketViewModel.MarketUiEvent.InstallFinished(
                key,
                "已把「demo」更新到版本 1.1.0，并装进了游戏（重启游戏生效）",
                pack("demo", "1.1.0", bytes = 300),
            ),
        )
        assertNull(state.installingKey)
        assertFalse(state.noteWarn)
        assertEquals(
            "updateCheck 随新版本自然转 Latest（目录源无容器事实 = null）",
            UpdateCheckState.Latest(sameContainer = null),
            state.snapshot?.updates?.get(key),
        )
        assertEquals("去重行集刷新：两个版本只留新版本一行", 1, state.installedRows.size)
        assertEquals("1.1.0", state.installedRows.single().rec.version)
        assertEquals("占用 = 新旧两版本记账条之和（旧版本目录还在库内，W5 GC 挂账）", 400L, state.snapshot?.installedBytes)
        assertEquals(
            "行动作翻面：Latest ⇒ 不再给按钮",
            MarketViewModel.MarketRowAction.NONE,
            state.groups.single().rows.single().action,
        )
    }

    @Test
    fun `按包卸载落屏——单事件摘全部版本、确认框收起、闸归还（裁定 3）`() {
        val snap = snapshotOf(
            packs = listOf(entry("demo")),
            installed = listOf(pack("demo", "1.0.0", bytes = 100), pack("demo", "1.1.0", bytes = 200)),
        )
        var state = reduce(MarketViewModel.initial("1.0.0"), MarketViewModel.MarketUiEvent.LocalLoaded(snap))
        assertEquals("去重后一行", 1, state.installedRows.size)
        val row = state.installedRows.single()
        state = reduce(state, MarketViewModel.MarketUiEvent.UninstallConfirm(row))
        assertEquals(row, state.pendingUninstall)
        state = reduce(state, MarketViewModel.MarketUiEvent.UninstallStarted(row.key, "正在卸掉「demo」…"))
        assertEquals(row.key, state.installingKey)
        state = reduce(
            state,
            MarketViewModel.MarketUiEvent.PackageUninstallFinished(
                row.key,
                "已卸掉「demo」（已装列表与市场目录都已清除）",
                row.rec,
            ),
        )
        assertNull("卸成后确认框收起", state.pendingUninstall)
        assertNull(state.installingKey)
        assertFalse(state.noteWarn)
        assertEquals("该包全部版本一并摘除", 0, state.snapshot?.installed?.size)
        assertEquals(0L, state.snapshot?.installedBytes)
        assertEquals(0, state.installedRows.size)
        // 失败半边：removed = null ⇒ 不做乐观、warn 出声（权威数字交宿主重读修正）
        state = reduce(state, MarketViewModel.MarketUiEvent.InstalledRevalidated(snap))
        state = reduce(state, MarketViewModel.MarketUiEvent.UninstallConfirm(state.installedRows.single()))
        state = reduce(
            state,
            MarketViewModel.MarketUiEvent.PackageUninstallFinished(row.key, "卸载没有成功：盘满", null),
        )
        assertTrue(state.noteWarn)
        assertEquals("失败半边市场库原样", 2, state.snapshot?.installed?.size)
    }

    // ---------------------------------------------- 手动清扫（CONTRACT-MKM-W5-GC 余项 D2）
    // 反向证明锚：`清扫回执三分支` 摘掉 sweepDonePhrase 的 Failed 臂（Swept-only 折叠）⇒
    // 失败用例红；`清扫开始占用同一把互斥闸` 摘掉 SweepStarted 的哨兵闸占用 ⇒ 闸断言红。

    @Test
    fun `清扫开始占用同一把互斥闸并出声`() {
        val state = reduce(MarketViewModel.initial("1.0.0"), MarketViewModel.MarketUiEvent.SweepStarted)

        assertEquals(MarketViewModel.SWEEP_GATE_KEY, state.installingKey)
        assertEquals(copy.sweepBusy, state.note)
        assertFalse(state.noteWarn)
        // 哨兵闸占用 = 全部 mutation 按钮（装/更新/卸/桥）随既有单源判定一并禁用——
        // 服务端清扫与装卸共用同一把 mutationLock，闸面与锁面单源
        assertFalse(MarketBridgeUi.bridgeImportEnabled(state))
    }

    @Test
    fun `清扫回执三分支（清零、清掉 N 个、失败）`() {
        val started = reduce(MarketViewModel.initial("1.0.0"), MarketViewModel.MarketUiEvent.SweepStarted)

        // 0 个：实话不是失败，闸归还、不给失败色
        val sweptZero = reduce(started, MarketViewModel.MarketUiEvent.SweepFinished(SweepOutcome.Swept(0)))
        assertNull(sweptZero.installingKey)
        assertEquals(copy.sweepNothing, sweptZero.note)
        assertFalse(sweptZero.noteWarn)
        assertTrue(MarketBridgeUi.bridgeImportEnabled(sweptZero))

        // N 个：计数上屏
        val sweptTwo = reduce(started, MarketViewModel.MarketUiEvent.SweepFinished(SweepOutcome.Swept(2)))
        assertNull(sweptTwo.installingKey)
        assertEquals("已清掉 2 个旧版本", sweptTwo.note)
        assertFalse(sweptTwo.noteWarn)

        // 失败：原话上屏不静默，失败色，闸照常归还（回执必落屏 = 闸必清）
        val failed = reduce(started, MarketViewModel.MarketUiEvent.SweepFinished(SweepOutcome.Failed("占用")))
        assertNull(failed.installingKey)
        assertEquals("清扫没有成功：占用", failed.note)
        assertTrue(failed.noteWarn)
    }

    // ---------------------------------------------------------------- 统计与收藏（CONTRACT-MKM-STATS-UI）

    @Test
    fun `统计落屏——Ok与Absent都以缓存态进状态机不进状态行`() {
        val base = MarketViewModel.initial("0.4.0")
        // Ok：带映射落屏
        val ok = reduce(
            base,
            MarketViewModel.MarketUiEvent.StatsLoaded(MarketStatsState.Ok(MarketStatsSnapshot(1L, mapOf("u" to 5L)))),
        )
        assertTrue(ok.stats is MarketStatsState.Ok)
        assertEquals("", ok.note)
        // Absent：取数失败且无旧缓存——按缺席落屏，状态行不废话（统计失败不挤安装回执）
        val absent = reduce(base, MarketViewModel.MarketUiEvent.StatsLoaded(MarketStatsState.Absent))
        assertEquals(MarketStatsState.Absent, absent.stats)
        assertEquals("", absent.note)
    }

    @Test
    fun `下载量读数——命中给真值未命中折破折号不编0`() {
        val ok = MarketStatsState.Ok(MarketStatsSnapshot(1L, mapOf("https://example.invalid/a.zip" to 127L)))
        assertEquals(
            "下载 127",
            MarketReadouts.downloadCountPhrase(
                copy,
                MarketStatsUi.downloadsFor(
                    ok,
                    "https://example.invalid/a.zip",
                ),
            ),
        )
        assertEquals(
            "—",
            MarketReadouts.downloadCountPhrase(
                copy,
                MarketStatsUi.downloadsFor(ok, "https://example.invalid/miss.zip"),
            ),
        )
        assertEquals(
            "—",
            MarketReadouts.downloadCountPhrase(copy, MarketStatsUi.downloadsFor(MarketStatsState.Absent, "u")),
        )
        assertEquals("—", MarketReadouts.downloadCountPhrase(copy, null))
    }

    @Test
    fun `收藏集落屏与toggle——换显示面不改快照不占互斥闸`() {
        val base = reduce(
            MarketViewModel.initial("0.4.0"),
            MarketViewModel.MarketUiEvent.LocalLoaded(snapshotOf(listOf(entry("p1"), entry("p2")))),
        )
        val loaded = reduce(base, MarketViewModel.MarketUiEvent.FavoritesLoaded(setOf("kojo-pack/p1")))
        assertEquals(setOf("kojo-pack/p1"), loaded.favorites)
        assertNull(loaded.installingKey)
        val toggled = reduce(
            loaded,
            MarketViewModel.MarketUiEvent.FavoriteToggled(setOf("kojo-pack/p1", "kojo-pack/p2")),
        )
        assertEquals(setOf("kojo-pack/p1", "kojo-pack/p2"), toggled.favorites)
        assertNull(toggled.installingKey)
        // 快照原样保留（收藏是纯显示面维度）
        assertEquals(base.snapshot, toggled.snapshot)
    }

    @Test
    fun `收藏过滤档——在档各组只留收藏条目换档重建不回源`() {
        val packs = listOf(entry("fav1"), entry("fav2"), entry("plain"))
        val base = reduce(
            MarketViewModel.initial("0.4.0"),
            MarketViewModel.MarketUiEvent.LocalLoaded(snapshotOf(packs)),
        )
        val withFav = reduce(
            base,
            MarketViewModel.MarketUiEvent.FavoritesLoaded(setOf(MarketContract.favoriteKey("kojo-pack", "fav1"))),
        )
        // 未在档：全部行可见
        assertEquals(3, withFav.groups.flatMap { it.rows }.size)
        // 在档：只剩 fav1
        val filtered = reduce(withFav, MarketViewModel.MarketUiEvent.FavoritesOnlyChanged(true))
        assertTrue(filtered.favoritesOnly)
        val visible = filtered.groups.flatMap { it.rows }.map { it.entry.id }
        assertEquals(listOf("fav1"), visible)
        // 撤档：全量回来（快照的 packs 原样，无网络回源）
        val unfiltered = reduce(filtered, MarketViewModel.MarketUiEvent.FavoritesOnlyChanged(false))
        assertFalse(unfiltered.favoritesOnly)
        assertEquals(3, unfiltered.groups.flatMap { it.rows }.size)
    }

    @Test
    fun `收藏集变化且过滤在档——行集随新集合重建`() {
        val packs = listOf(entry("fav1"), entry("fav2"))
        val base = reduce(
            MarketViewModel.initial("0.4.0"),
            MarketViewModel.MarketUiEvent.LocalLoaded(snapshotOf(packs)),
        )
        val on = reduce(base, MarketViewModel.MarketUiEvent.FavoritesOnlyChanged(true))
        val withFav = reduce(
            on,
            MarketViewModel.MarketUiEvent.FavoritesLoaded(setOf(MarketContract.favoriteKey("kojo-pack", "fav2"))),
        )
        val visible = withFav.groups.flatMap { it.rows }.map { it.entry.id }
        assertEquals(listOf("fav2"), visible)
    }

    @Test
    fun `收藏过滤与kind过滤可叠加`() {
        val packs = listOf(
            entry("fav-kojo", kind = MarketContract.KIND_KOJO),
            entry("plain-kojo", kind = MarketContract.KIND_KOJO),
            entry("fav-dict", kind = MarketContract.KIND_DICT),
        )
        val base = reduce(
            MarketViewModel.initial("0.4.0"),
            MarketViewModel.MarketUiEvent.LocalLoaded(snapshotOf(packs)),
        )
        val favSet = setOf(
            MarketContract.favoriteKey("kojo-pack", "fav-kojo"),
            MarketContract.favoriteKey("dict-pack", "fav-dict"),
        )
        val both = reduce(
            reduce(base, MarketViewModel.MarketUiEvent.FavoritesLoaded(favSet)),
            MarketViewModel.MarketUiEvent.FavoritesOnlyChanged(true),
        )
        val kojoOnly = reduce(both, MarketViewModel.MarketUiEvent.FilterChanged(MarketContract.KIND_KOJO))
        assertEquals(listOf("fav-kojo"), kojoOnly.groups.flatMap { it.rows }.map { it.entry.id })
    }
}
