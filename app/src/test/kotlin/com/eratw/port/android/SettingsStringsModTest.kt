package com.eratw.port.android

import com.eratw.port.core.mod.RegisteredFaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 从 SettingsStringsTest 拆出（detekt LargeClass）：Mod 相关文案表变大后整类越过行数阈值。
// 拆分只移动用例、不改断言；条目点名清单与形状闸口径逐字保留。
class SettingsStringsModTest {
    /**
     * 玩家排序配置（RT2 L2）新增的三条文案：按 UpdateNotifyCopyTest 的「条目点名清单计数」
     * 形状闸钉住（AGENTS §6.2：只改表不改闸 = 验收红）。反射完整性用例只钉「非空 + 占位符」，
     * 这里逐条点名、钉三语互不重复，且中文说明必须点明「优先级」与依赖造成的调整
     * （契约 §1.2 产品裁定，避免玩家把配置误读成对最终序的逐位指定）。
     */
    @Test
    fun `Mod 装载优先级文案三语齐备且点明优先级语义`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        // 条目点名清单：RT2 L2 首批 3 条 + ModDX-4 加载顺序 UI 波 3 条（空态 / 未知包行 /
        // 移除按钮），逐条列出（改名 / 漏档 / 只补一种语言都在这里红）。
        val keys: List<Pair<String, (SettingsStrings) -> String>> = listOf(
            "modPackageOrderTitle" to { s: SettingsStrings -> s.modPackageOrderTitle },
            "modPackageOrderHint" to { s: SettingsStrings -> s.modPackageOrderHint },
            "modPackageOrderEmpty" to { s: SettingsStrings -> s.modPackageOrderEmpty },
            "modPackageOrderListEmpty" to { s: SettingsStrings -> s.modPackageOrderListEmpty },
            "modPackageOrderUnknownTag" to { s: SettingsStrings -> s.modPackageOrderUnknownTag },
            "modPackageOrderUnknownRemove" to { s: SettingsStrings -> s.modPackageOrderUnknownRemove },
        )
        assertEquals("新增文案条目数变了（现有 6 条）", 6, keys.size)
        keys.forEach { (name, getter) ->
            val texts = all.map(getter)
            texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
            assertEquals("$name 三语重复", 3, texts.distinct().size)
        }
        assertEquals("Mod 装载优先级", all[0].modPackageOrderTitle)
        assertEquals("Mod load priority", all[1].modPackageOrderTitle)
        assertEquals("Mod 読み込み優先度", all[2].modPackageOrderTitle)
        // 产品裁定（契约 §1.2）：说明要点明「优先级」而非「完整顺序」，且点明依赖会改变实际序。
        assertTrue("中文说明没点明优先级", all[0].modPackageOrderHint.contains("优先级"))
        assertTrue("中文说明没点明不是完整顺序", all[0].modPackageOrderHint.contains("完整顺序"))
        assertTrue("中文说明没点明依赖改变装载序", all[0].modPackageOrderHint.contains("依赖"))
        // ModDX-4：空态点明缺省行为，未知包行标签如实说「不在已装列表」。
        assertTrue("空态没点明缺省序", all[0].modPackageOrderListEmpty.contains("缺省序"))
        assertEquals("未知包（不在已装列表）", all[0].modPackageOrderUnknownTag)
        assertEquals("Unknown package (not installed)", all[1].modPackageOrderUnknownTag)
        assertEquals("不明なパッケージ（未インストール）", all[2].modPackageOrderUnknownTag)
        assertEquals("移除", all[0].modPackageOrderUnknownRemove)
    }

    /**
     * Mod 运行时诊断卡（RT2-GATE-OPEN，2026-11-17 新增的 8 条文案 + 2 条参数化句式）的
     * 形状闸（AGENTS §6.2：只改表不改闸 = 验收红）。反射完整性用例只钉「非空 + 占位符」，
     * 这里逐条点名、钉三语互不重复，并钉住两态哨兵的四行读数（总闸开/关、会话已/未生效）。
     */
    @Test
    fun `Mod 运行时诊断文案三语齐备且两态读数点名`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        // 条目点名清单：本次新增 8 条，逐条列出（改名 / 漏档 / 只补一种语言都在这里红）。
        val keys: List<Pair<String, (SettingsStrings) -> String>> = listOf(
            "modDiagnosticsTitle" to { s: SettingsStrings -> s.modDiagnosticsTitle },
            "modDiagnosticsHint" to { s: SettingsStrings -> s.modDiagnosticsHint },
            "modDiagnosticsCopy" to { s: SettingsStrings -> s.modDiagnosticsCopy },
            "modDiagnosticsCopied" to { s: SettingsStrings -> s.modDiagnosticsCopied },
            "modDiagnosticsGateOn" to { s: SettingsStrings -> s.modDiagnosticsGateOn },
            "modDiagnosticsGateOff" to { s: SettingsStrings -> s.modDiagnosticsGateOff },
            "modDiagnosticsSessionOn" to { s: SettingsStrings -> s.modDiagnosticsSessionOn },
            "modDiagnosticsSessionOff" to { s: SettingsStrings -> s.modDiagnosticsSessionOff },
        )
        assertEquals("新增文案条目数变了（现有 8 条）", 8, keys.size)
        keys.forEach { (name, getter) ->
            val texts = all.map(getter)
            texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
            assertEquals("$name 三语重复", 3, texts.distinct().size)
        }
        assertEquals("Mod 运行时诊断", all[0].modDiagnosticsTitle)
        assertEquals("Mod runtime diagnostics", all[1].modDiagnosticsTitle)
        assertEquals("Mod ランタイム診断", all[2].modDiagnosticsTitle)
        assertTrue("中文说明要点明只读", all[0].modDiagnosticsHint.contains("只读"))
        // 两态哨兵：开/关、已生效/未生效各自可读，未生效那条要点明重开一局。
        assertEquals("总闸：开", all[0].modDiagnosticsGateOn)
        assertEquals("总闸：关", all[0].modDiagnosticsGateOff)
        assertEquals("本局 Mod 运行时：已生效", all[0].modDiagnosticsSessionOn)
        assertTrue(
            "未生效那条要点明重开一局后按总闸生效",
            all[0].modDiagnosticsSessionOff.contains("重开一局"),
        )
        assertEquals("Mod runtime in this session: active", all[1].modDiagnosticsSessionOn)
        assertEquals("今回の Mod ランタイム：有効", all[2].modDiagnosticsSessionOn)
        // 参数化句式逐字钉住（占位符一致性由反射闸管）。
        assertEquals("Mod：安装 1 个 · 启用 0 个 · 读不出 2 个", all[0].modDiagnosticsMods(1, 0, 2))
        assertEquals("Mods: 1 installed · 0 enabled · 2 unreadable", all[1].modDiagnosticsMods(1, 0, 2))
        assertEquals(
            "Mod：インストール 1 件・有効 0 件・読み込み不可 2 件",
            all[2].modDiagnosticsMods(1, 0, 2),
        )
        assertEquals("Mod 数据文件：3 个 · 4096 字节", all[0].modDiagnosticsSidecars(3, 4096L))
        assertEquals("Mod data files: 3 files · 4096 bytes", all[1].modDiagnosticsSidecars(3, 4096L))
        assertEquals("Mod データファイル：3 件・4096 バイト", all[2].modDiagnosticsSidecars(3, 4096L))
        // task-172：诊断快照导出的 5 条壳层文案（按钮 / 导出说明 / 弹窗标题 / 弹窗说明 / 复制）。
        assertDiagnosticExportCopy(all)
    }


    /**
     * Mod 管理面（task-2 详情 / 卸载 / 排序卡）新增文案的形状闸：三语都给值、互不互抄；
     * 参数化文案（格式化字段）逐条钉住三语输出，避免占位符漏档后只剩半句。
     *
     * 反射完整性用例只钉「非空 + 占位符一致」，这里按 AGENTS §6.2 的条目点名清单补计数：
     * 表里增删/改名而闸没跟上 = 验收红。
     */
    @Test
    fun `Mod 管理面新增文案三语齐备且读数分开`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        val keys = modManageCopyKeys()
        assertEquals("新增文案条目数变了（现有 25 条）", 25, keys.size)
        keys.forEach { (name, getter) ->
            val texts = all.map(getter)
            texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
            assertEquals("$name 三语重复", 3, texts.distinct().size)
        }
        // 关键三语原值（改名/换词都在这里红）。
        assertEquals("确认卸载", all[0].modManageUninstallConfirmTitle)
        assertEquals("Confirm uninstall", all[1].modManageUninstallConfirmTitle)
        assertEquals("アンインストールの確認", all[2].modManageUninstallConfirmTitle)
        assertEquals("上移", all[0].modPackageOrderMoveUp)
        assertEquals("Move up", all[1].modPackageOrderMoveUp)
        assertEquals("上へ", all[2].modPackageOrderMoveUp)
        // 参数化文案：三语输出与占位符逐条对齐。
        assertEquals("坏包：Boom: x", all[0].modManageFailureLine("坏包", "Boom: x"))
        assertEquals("坏包: Boom: x", all[1].modManageFailureLine("坏包", "Boom: x"))
        assertEquals("坏包：Boom: x", all[2].modManageFailureLine("坏包", "Boom: x"))
        assertEquals("包名：eratw.a", all[0].modManageDetailLine("包名", "eratw.a"))
        assertEquals("Package name: eratw.a", all[1].modManageDetailLine(all[1].modManageDetailPackageId, "eratw.a"))
        assertEquals("パッケージ名：eratw.a", all[2].modManageDetailLine(all[2].modManageDetailPackageId, "eratw.a"))
        assertEquals(
            "已勾选 2 个，按上面列表顺序优先装载；没勾的按缺省序。",
            all[0].modPackageOrderChosenCount(2),
        )
        assertEquals(
            "Selected 2; they load first in the order above. Unticked Mods use the default order.",
            all[1].modPackageOrderChosenCount(2),
        )
        assertEquals(
            "2 件を選択中。上のリスト順に優先して読み込まれます。未選択は既定順です。",
            all[2].modPackageOrderChosenCount(2),
        )
        assertTrue(
            "卸载确认正文要点明删哪些文件",
            all[0].modManageUninstallConfirmBody("口上补丁").contains("扩展包文件"),
        )
        assertTrue(
            "卸载确认正文要点名包标题",
            all[0].modManageUninstallConfirmBody("口上补丁").contains("口上补丁"),
        )
        assertTrue("卸载完成提示带包标题", all[0].modManageUninstallDone("口上补丁").contains("口上补丁"))
        assertTrue("卸载失败提示带包标题", all[1].modManageUninstallFailed("口上补丁").contains("口上补丁"))
        // 产品裁定（task-2 要求 4）：「缺扩展包文件」与「装载失败」是两种读数，措辞不许混成一句。
        assertTrue("缺扩展包文件说明要点名扩展包文件", all[0].modManageModuleMissing.contains("扩展包文件"))
        assertTrue("缺扩展包文件说明要点名重新导入修复", all[0].modManageModuleMissing.contains("重新导入"))
        assertTrue("配对读数要点名扩展包文件", all[0].modManageModulePaired.contains("扩展包文件"))
        // 诊断面按会话归零（Lane DX）后，行只代表本次会话 ⇒ 标题必须点名「会话」而不是「上次启动」。
        assertTrue("装载失败小节标题应点名本会话", all[0].modManageFailureHeading.contains("会话"))
        assertFalse(
            "装载失败小节标题不许再说「上次启动」（会误导成盘面现状）",
            all[0].modManageFailureHeading.contains("启动"),
        )
        // Lane DZ：重试装载入口的三语原值（按钮字面 + 投递提示）。
        assertEquals("重试装载", all[0].modManageRetryInstall)
        assertEquals("Retry loading", all[1].modManageRetryInstall)
        assertEquals("読み込みを再試行", all[2].modManageRetryInstall)
        assertTrue("投递提示要点名「下一回合边界」生效", all[0].modManageRetryQueued.contains("回合边界"))
        assertTrue("投递提示要点名结果会刷新", all[1].modManageRetryQueued.contains("turn boundary"))
        assertTrue("投递提示三语齐备", all[2].modManageRetryQueued.isNotEmpty())
        // Lane modsearch（task-168）：搜索框标签 / 「全部」档 / 筛选后空结果 / 计数行。
        assertModSearchCopy(all)
    }

    /**
     * Mod 版本读数（VS-1）新增 5 条文案的形状闸（AGENTS §6.2：只改表不改闸 = 验收红）。
     * 五条都是参数化读数（引擎 / API / 不兼容 / 数据版本 / 启用被挡），逐条钉三语输出与互不重复。
     */
    @Test
    fun `Mod 版本读数文案三语齐备且读数分开`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        // 条目点名清单：本次新增 5 条，逐条列出（漏档 / 改名 / 只补一种语言都在这里红）。
        val keys: List<Pair<String, (SettingsStrings) -> String>> = listOf(
            "modVersionEngine" to { s: SettingsStrings -> s.modVersionEngine(13) },
            "modVersionApi" to { s: SettingsStrings -> s.modVersionApi(13) },
            "modVersionIncompatible" to { s: SettingsStrings -> s.modVersionIncompatible(14, 13) },
            "modVersionData" to { s: SettingsStrings -> s.modVersionData(2, 0) },
            "modVersionEnableBlocked" to { s: SettingsStrings -> s.modVersionEnableBlocked(14, 13) },
        )
        assertEquals("新增文案条目数变了（现有 5 条）", 5, keys.size)
        keys.forEach { (name, getter) ->
            val texts = all.map(getter)
            texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
            assertEquals("$name 三语重复", 3, texts.distinct().size)
        }
        // 三语原值逐条钉住（改词 / 挪占位符都在这里红）。
        assertEquals("引擎 mod API v13", all[0].modVersionEngine(13))
        assertEquals("Engine mod API v13", all[1].modVersionEngine(13))
        assertEquals("エンジン mod API v13", all[2].modVersionEngine(13))
        assertEquals("API 版本 v13", all[0].modVersionApi(13))
        assertEquals("Mod API version v13", all[1].modVersionApi(13))
        assertEquals("API バージョン v13", all[2].modVersionApi(13))
        assertEquals("不兼容（需要 API v14，引擎 v13）", all[0].modVersionIncompatible(14, 13))
        assertEquals("Incompatible (needs API v14, engine v13)", all[1].modVersionIncompatible(14, 13))
        assertEquals("非対応（API v14 が必要、エンジンは v13 まで）", all[2].modVersionIncompatible(14, 13))
        assertEquals("数据版本 v2→v0", all[0].modVersionData(2, 0))
        assertEquals("Data version v2→v0", all[1].modVersionData(2, 0))
        assertEquals("データ版 v2→v0", all[2].modVersionData(2, 0))
        // 产品裁定：不兼容行与启用被挡提示都要点名「mod 要哪版 / 引擎到哪版」两个数字。
        assertTrue("不兼容行要点名 mod 需要的版本", all[0].modVersionIncompatible(14, 13).contains("v14"))
        assertTrue("不兼容行要点名引擎版本", all[0].modVersionIncompatible(14, 13).contains("v13"))
        assertTrue("启用被挡提示要点名 mod 需要的版本", all[0].modVersionEnableBlocked(14, 13).contains("v14"))
        assertEquals("无法启用：这个 Mod 需要 API v14，引擎只到 v13。", all[0].modVersionEnableBlocked(14, 13))
        assertEquals(
            "Can't enable: this mod needs API v14, but the engine only provides v13.",
            all[1].modVersionEnableBlocked(14, 13),
        )
        assertEquals(
            "有効にできません：この Mod は API v14 を必要としますが、エンジンは v13 までです。",
            all[2].modVersionEnableBlocked(14, 13),
        )
    }

    /** 条目点名清单：本次新增 25 条公开文案（格式化模板走用例里的逐条输出断言）。 */
    private fun modManageCopyKeys(): List<Pair<String, (SettingsStrings) -> String>> = listOf(
        "modManageTakeEffectHint" to { s: SettingsStrings -> s.modManageTakeEffectHint },
        "modManageFailureHeading" to { s: SettingsStrings -> s.modManageFailureHeading },
        "modManageDetailPackageId" to { s: SettingsStrings -> s.modManageDetailPackageId },
        "modManageDetailVersion" to { s: SettingsStrings -> s.modManageDetailVersion },
        "modManageDetailAuthor" to { s: SettingsStrings -> s.modManageDetailAuthor },
        "modManageDetailSource" to { s: SettingsStrings -> s.modManageDetailSource },
        "modManageDetailDescription" to { s: SettingsStrings -> s.modManageDetailDescription },
        "modManageDescriptionEmpty" to { s: SettingsStrings -> s.modManageDescriptionEmpty },
        "modManageModulePaired" to { s: SettingsStrings -> s.modManageModulePaired },
        "modManageModuleMissing" to { s: SettingsStrings -> s.modManageModuleMissing },
        "modManageModuleBuiltin" to { s: SettingsStrings -> s.modManageModuleBuiltin },
        "modManageExpandHint" to { s: SettingsStrings -> s.modManageExpandHint },
        "modManageUninstall" to { s: SettingsStrings -> s.modManageUninstall },
        "modManageUninstallConfirmTitle" to { s: SettingsStrings -> s.modManageUninstallConfirmTitle },
        "modManageUninstallBuiltin" to { s: SettingsStrings -> s.modManageUninstallBuiltin },
        // Lane DZ 回滚态重装的玩家入口（按钮 + 投递提示）。
        "modManageRetryInstall" to { s: SettingsStrings -> s.modManageRetryInstall },
        "modManageRetryQueued" to { s: SettingsStrings -> s.modManageRetryQueued },
        "modPackageOrderMoveUp" to { s: SettingsStrings -> s.modPackageOrderMoveUp },
        "modPackageOrderMoveDown" to { s: SettingsStrings -> s.modPackageOrderMoveDown },
        "modPackageOrderReset" to { s: SettingsStrings -> s.modPackageOrderReset },
        "modPackageOrderManualLabel" to { s: SettingsStrings -> s.modPackageOrderManualLabel },
        // Lane modsearch（task-168）：搜索框 / 来源筛选第一档 / 筛选后空结果 / 计数行。
        "modManageSearchLabel" to { s: SettingsStrings -> s.modManageSearchLabel },
        "modManageFilterAll" to { s: SettingsStrings -> s.modManageFilterAll },
        "modManageFilterEmpty" to { s: SettingsStrings -> s.modManageFilterEmpty },
        "modManageFilterCount(0,0)" to { s: SettingsStrings -> s.modManageFilterCount(0, 0) },
    )

    /**
     * per-mod 设置面板（task-4 Lane D）新增文案的形状闸：三语都给值、互不互抄；参数化文案
     * （单行展示 / clamp 提示）逐条钉住三语输出，避免占位符漏档后只剩半句。
     */
    @Test
    fun `per-mod 设置面板文案三语齐备且读数分开`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        val keys = modSettingCopyKeys()
        assertEquals("新增文案条目数变了（现有 13 条）", 13, keys.size)
        keys.forEach { (name, getter) ->
            val texts = all.map(getter)
            texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
            assertEquals("$name 三语重复", 3, texts.distinct().size)
        }
        // 关键三语原值（改名/换词都在这里红）。
        assertEquals("Mod 设置", all[0].modSettingSectionTitle)
        assertEquals("Mod settings", all[1].modSettingSectionTitle)
        assertEquals("Mod 設定", all[2].modSettingSectionTitle)
        assertEquals("开", all[0].modSettingBoolOn)
        assertEquals("On", all[1].modSettingBoolOn)
        assertEquals("オン", all[2].modSettingBoolOn)
        assertEquals("关", all[0].modSettingBoolOff)
        assertEquals("Off", all[1].modSettingBoolOff)
        assertEquals("オフ", all[2].modSettingBoolOff)
        assertEquals("恢复默认", all[0].modSettingResetButton)
        assertEquals("Restore defaults", all[1].modSettingResetButton)
        assertEquals("既定値に戻す", all[2].modSettingResetButton)
        assertEquals("确认恢复", all[0].modSettingResetConfirmOk)
        assertEquals("Restore", all[1].modSettingResetConfirmOk)
        assertEquals("戻す", all[2].modSettingResetConfirmOk)
        // 参数化文案：三语输出与占位符逐条对齐。
        assertEquals("音量：7", all[0].modSettingLine("音量", "7"))
        assertEquals("Volume: 7", all[1].modSettingLine("Volume", "7"))
        assertEquals("音量：7", all[2].modSettingLine("音量", "7"))
        assertEquals("超出允许范围，已修正为 10。", all[0].modSettingClamped("10"))
        assertEquals("Out of range; adjusted to 10.", all[1].modSettingClamped("10"))
        assertEquals("許容範囲外のため 10 に修正しました。", all[2].modSettingClamped("10"))
        // 语义断言：保存落点（不进存档）、生效时机、失败原因、整数校验各说各的，不许互相复读。
        assertTrue("说明要点名按包保存在本机", all[0].modSettingHint.contains("按包保存在本机"))
        assertTrue("说明要点名不写进存档", all[0].modSettingHint.contains("不写进存档"))
        // 生效时机口径（Lane CS 热通知落地后修正）：per-mod 设置 = 本局下一拍生效，不是重开一局。
        assertTrue("说明要点名生效时机", all[0].modSettingHint.contains("下一"))
        assertTrue("保存成功提示要点名生效时机", all[0].modSettingSaved.contains("下一"))
        assertTrue("保存失败提示要点名存储空间", all[0].modSettingSaveFailed.contains("存储空间"))
        assertTrue("非法整数提示要点名整数", all[0].modSettingInvalidInt.contains("整数"))
        // 恢复默认：确认正文要点名「作者声明的默认值 / 不碰存档 / 下一回合生效」，两条结果提示各说各的。
        assertTrue("确认正文要点名声明默认值", all[0].modSettingResetConfirmBody.contains("作者声明的默认值"))
        assertTrue("确认正文要点名不碰存档", all[0].modSettingResetConfirmBody.contains("存档"))
        assertTrue("确认正文要点名生效时机", all[0].modSettingResetConfirmBody.contains("下一回合"))
        assertTrue("恢复成功提示要点名生效时机", all[0].modSettingResetDone.contains("下一回合"))
        assertTrue("恢复失败提示要点名存储空间", all[0].modSettingResetFailed.contains("存储空间"))
    }

    /** 条目点名清单：本次新增 13 条公开文案（两个格式化模板走用例里的逐条输出断言）。 */
    private fun modSettingCopyKeys(): List<Pair<String, (SettingsStrings) -> String>> = listOf(
        "modSettingSectionTitle" to { s: SettingsStrings -> s.modSettingSectionTitle },
        "modSettingHint" to { s: SettingsStrings -> s.modSettingHint },
        "modSettingBoolOn" to { s: SettingsStrings -> s.modSettingBoolOn },
        "modSettingBoolOff" to { s: SettingsStrings -> s.modSettingBoolOff },
        "modSettingSaved" to { s: SettingsStrings -> s.modSettingSaved },
        "modSettingSaveFailed" to { s: SettingsStrings -> s.modSettingSaveFailed },
        "modSettingInvalidInt" to { s: SettingsStrings -> s.modSettingInvalidInt },
        "modSettingResetButton" to { s: SettingsStrings -> s.modSettingResetButton },
        "modSettingResetConfirmTitle" to { s: SettingsStrings -> s.modSettingResetConfirmTitle },
        "modSettingResetConfirmBody" to { s: SettingsStrings -> s.modSettingResetConfirmBody },
        "modSettingResetConfirmOk" to { s: SettingsStrings -> s.modSettingResetConfirmOk },
        "modSettingResetDone" to { s: SettingsStrings -> s.modSettingResetDone },
        "modSettingResetFailed" to { s: SettingsStrings -> s.modSettingResetFailed },
    )

    /**
     * 重新编译面（Lane J lane-hotrecompile）新增文案的形状闸：三语都给值、互不互抄；
     * 三条参数化文案逐条钉住三语输出，避免占位符漏档后只剩半句。
     *
     * 与导入卡同口径：编译失败证据串由 `ModImportCopy` 按语言取好，这里只钉壳层自己的字。
     */
    @Test
    fun `重新编译文案三语齐备且三态分开`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        val keys = modRecompileCopyKeys()
        assertEquals("新增文案条目数变了（现有 6 条）", 6, keys.size)
        keys.forEach { (name, getter) ->
            val texts = all.map(getter)
            texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
            assertEquals("$name 三语重复", 3, texts.distinct().size)
        }
        // 关键三语原值（改名/换词都在这里红）。
        assertEquals("重新编译", all[0].modManageRecompile)
        assertEquals("Recompile", all[1].modManageRecompile)
        assertEquals("再コンパイル", all[2].modManageRecompile)
        assertEquals("正在重新编译…", all[0].modManageRecompileBusy)
        assertEquals("源文件没有变化，无需重新编译。", all[0].modManageRecompileUpToDate)
        // 参数化文案：三语输出与占位符逐条对齐。
        assertEquals("已重新编译「口上补丁」，重开一局生效。", all[0].modManageRecompileDone("口上补丁"))
        assertEquals("Recompiled \"口上补丁\". Restart the game to apply.", all[1].modManageRecompileDone("口上补丁"))
        assertEquals(
            "「口上补丁」を再コンパイルしました。ゲームを再起動すると反映されます。",
            all[2].modManageRecompileDone("口上补丁"),
        )
        assertEquals(
            "「口上补丁」重新编译失败（已保留旧版本）：boom",
            all[0].modManageRecompileFailed("口上补丁", "boom"),
        )
        assertEquals(
            "Could not recompile \"口上补丁\" (the previous version is kept): boom",
            all[1].modManageRecompileFailed("口上补丁", "boom"),
        )
        assertEquals(
            "「口上补丁」の再コンパイルに失敗しました（以前の版は残っています）：boom",
            all[2].modManageRecompileFailed("口上补丁", "boom"),
        )
        // 语义断言：三态（成功 / 无变化 / 失败）各说各的，且失败面承诺「旧版本保留」。
        assertTrue("说明要点名源没变会跳过", all[0].modManageRecompileHint.contains("源没变"))
        assertTrue("无变化提示要点名无需重编", all[0].modManageRecompileUpToDate.contains("无需重新编译"))
        assertTrue("成功提示要点名重开一局", all[0].modManageRecompileDone("口上补丁").contains("重开一局"))
        assertTrue("失败提示要点名旧版本保留", all[0].modManageRecompileFailed("口上补丁", "boom").contains("保留旧版本"))
    }

    /** 条目点名清单：本次新增 6 条（4 条公开文案 + 2 个格式化模板的输出）。 */
    private fun modRecompileCopyKeys(): List<Pair<String, (SettingsStrings) -> String>> = listOf(
        "modManageRecompile" to { s: SettingsStrings -> s.modManageRecompile },
        "modManageRecompileHint" to { s: SettingsStrings -> s.modManageRecompileHint },
        "modManageRecompileBusy" to { s: SettingsStrings -> s.modManageRecompileBusy },
        "modManageRecompileUpToDate" to { s: SettingsStrings -> s.modManageRecompileUpToDate },
        "modManageRecompileDone" to { s: SettingsStrings -> s.modManageRecompileDone("口上补丁") },
        "modManageRecompileFailed" to { s: SettingsStrings -> s.modManageRecompileFailed("口上补丁", "boom") },
    )

    /**
     * Mod 运行时概览（Lane AW / 注册面读数）新增文案的形状闸：6 条公开文案 + 5 条参数化输出逐条点名
     * （AGENTS §6.2：只改表不改闸 = 验收红）。反射完整性用例只钉「非空 + 占位符」，
     * 这里钉三语互不重复与参数化输出的三语原值（漏档 / 只补一种语言都在这里红）。
     */
    @Test
    fun `Mod 运行时概览文案三语齐备且读数句式点名`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        val faces = RegisteredFaces(
            hooks = 2,
            retiredHooks = 1,
            events = mapOf("PRI" to 1, "NORMAL" to 2, "LATER" to 3, "ONLY" to 0),
            interactions = 4,
            services = 5,
            strings = 6,
            settings = 7,
            images = 8,
        )
        val keys: List<Pair<String, (SettingsStrings) -> String>> = listOf(
            "modRuntimeOverviewTitle" to { s: SettingsStrings -> s.modRuntimeOverviewTitle },
            "modRuntimeOverviewHint" to { s: SettingsStrings -> s.modRuntimeOverviewHint },
            "modRuntimeOverviewActive" to { s: SettingsStrings -> s.modRuntimeOverviewActive },
            "modRuntimeOverviewInactive" to { s: SettingsStrings -> s.modRuntimeOverviewInactive },
            "modRuntimeOverviewBadge" to { s: SettingsStrings -> s.modRuntimeOverviewBadge(2, 3) },
            "modRuntimeOverviewNone" to { s: SettingsStrings -> s.modRuntimeOverviewNone },
            "modRuntimeOverviewEmpty" to { s: SettingsStrings -> s.modRuntimeOverviewEmpty },
            "modRuntimeOverviewFailure" to { s: SettingsStrings -> s.modRuntimeOverviewFailure("boom") },
            "modRuntimeOverviewTotals" to { s: SettingsStrings -> s.modRuntimeOverviewTotals(3L, 12L) },
            "modRuntimeOverviewScope" to { s: SettingsStrings -> s.modRuntimeOverviewScope("lifecycle", 2L, 5L) },
            "modRuntimeOverviewSlow" to { s: SettingsStrings -> s.modRuntimeOverviewSlow(4L, 16L) },
            "modRuntimeOverviewFaces" to { s: SettingsStrings -> s.modRuntimeOverviewFaces(faces) },
            "modRuntimeOverviewStorage" to { s: SettingsStrings -> s.modRuntimeOverviewStorage("1.5 KB", 3) },
            "modRuntimeOverviewStorageMissing" to { s: SettingsStrings -> s.modRuntimeOverviewStorageMissing },
            "modRuntimeOverviewStorageOver" to { s: SettingsStrings -> s.modRuntimeOverviewStorageOver },
            "modRuntimeOverviewSettingsChanged" to { s: SettingsStrings -> s.modRuntimeOverviewSettingsChanged(2) },
            "modRuntimeOverviewSettingsChangedMissing" to { s: SettingsStrings ->
                s.modRuntimeOverviewSettingsChangedMissing
            },
        )
        assertEquals("新增文案条目数变了（现有 17 条 = 既有 15 条 + 存储超限提示 1 条 + 运行中计数 1 条）", 17, keys.size)
        keys.forEach { (name, getter) ->
            val texts = all.map(getter)
            texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
            assertEquals("$name 三语重复", 3, texts.distinct().size)
        }
        // 关键三语原值（改名 / 换词都在这里红）。
        assertEquals("Mod 运行时概览", all[0].modRuntimeOverviewTitle)
        assertEquals("Mod runtime overview", all[1].modRuntimeOverviewTitle)
        assertEquals("Mod ランタイム概要", all[2].modRuntimeOverviewTitle)
        assertEquals("运行中", all[0].modRuntimeOverviewActive)
        assertEquals("Active", all[1].modRuntimeOverviewActive)
        assertEquals("稼働中", all[2].modRuntimeOverviewActive)
        assertEquals("无", all[0].modRuntimeOverviewNone)
        assertEquals("None", all[1].modRuntimeOverviewNone)
        assertEquals("なし", all[2].modRuntimeOverviewNone)
        assertOverviewParamCopy(all)
        assertOverviewReadoutCopy(all)
    }

    /** 概览既有参数化文案的三语原值（占位符漏档会只剩半句）。 */
    private fun assertOverviewParamCopy(all: List<SettingsStrings>) {
        assertEquals("运行中 2/3", all[0].modRuntimeOverviewBadge(2, 3))
        assertEquals("Running 2/3", all[1].modRuntimeOverviewBadge(2, 3))
        assertEquals("稼働 2/3", all[2].modRuntimeOverviewBadge(2, 3))
        assertEquals("装载失败：boom", all[0].modRuntimeOverviewFailure("boom"))
        assertEquals("Load failure: boom", all[1].modRuntimeOverviewFailure("boom"))
        assertEquals("読み込み失敗：boom", all[2].modRuntimeOverviewFailure("boom"))
        assertEquals("累计 3 次 · 12 ms", all[0].modRuntimeOverviewTotals(3L, 12L))
        assertEquals("3 calls · 12 ms", all[1].modRuntimeOverviewTotals(3L, 12L))
        assertEquals("累計 3 回・12 ms", all[2].modRuntimeOverviewTotals(3L, 12L))
        assertEquals("lifecycle · 2 次 · 5 ms", all[0].modRuntimeOverviewScope("lifecycle", 2L, 5L))
        assertEquals("lifecycle · 2 calls · 5 ms", all[1].modRuntimeOverviewScope("lifecycle", 2L, 5L))
        assertEquals("lifecycle・2 回・5 ms", all[2].modRuntimeOverviewScope("lifecycle", 2L, 5L))
        assertEquals("慢处理器：4 次（单次超过 16 ms）", all[0].modRuntimeOverviewSlow(4L, 16L))
        assertEquals("Slow handlers: 4 (over 16 ms each)", all[1].modRuntimeOverviewSlow(4L, 16L))
        assertEquals("低速ハンドラ：4 件（1 回 16 ms 超）", all[2].modRuntimeOverviewSlow(4L, 16L))
    }

    /** 存储段 / 设置项读数三语原值：缺数据是显式「—」而不是 0（0 本身是真读数，不能混用）。 */
    private fun assertOverviewReadoutCopy(all: List<SettingsStrings>) {
        assertEquals("存储 1.5 KB · 3 键", all[0].modRuntimeOverviewStorage("1.5 KB", 3))
        assertEquals("Storage 1.5 KB · 3 keys", all[1].modRuntimeOverviewStorage("1.5 KB", 3))
        assertEquals("ストレージ 1.5 KB・3 キー", all[2].modRuntimeOverviewStorage("1.5 KB", 3))
        assertEquals("存储 —", all[0].modRuntimeOverviewStorageMissing)
        assertEquals("Storage —", all[1].modRuntimeOverviewStorageMissing)
        assertEquals("ストレージ —", all[2].modRuntimeOverviewStorageMissing)
        assertEquals("设置已改 2 项", all[0].modRuntimeOverviewSettingsChanged(2))
        assertEquals("Settings changed: 2", all[1].modRuntimeOverviewSettingsChanged(2))
        assertEquals("設定変更 2 件", all[2].modRuntimeOverviewSettingsChanged(2))
        assertEquals("设置已改 —", all[0].modRuntimeOverviewSettingsChangedMissing)
        assertEquals("Settings changed: —", all[1].modRuntimeOverviewSettingsChangedMissing)
        assertEquals("設定変更 —", all[2].modRuntimeOverviewSettingsChangedMissing)
    }

    /**
     * 注册面读数的三语原值闸：字段名保持 ASCII（作者对日志），只有前缀按语言翻译；
     * ONLY 桶恒 0 也照渲染（不因全零而隐藏）。
     */
    @Test
    fun `注册面读数三语原值点名`() {
        val faces = RegisteredFaces(
            hooks = 2,
            retiredHooks = 1,
            events = mapOf("PRI" to 1, "NORMAL" to 2, "LATER" to 3, "ONLY" to 0),
            interactions = 4,
            services = 5,
            strings = 6,
            settings = 7,
            images = 8,
        )
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val en = SettingsStrings.of(SettingsLanguage.EN)
        val ja = SettingsStrings.of(SettingsLanguage.JA)
        val zhFaces = "注册 hooks=2 退槽=1 events=PRI/NORMAL/LATER/ONLY=1/2/3/0 " +
            "interactions=4 services=5 strings=6 settings=7 images=8"
        val enFaces = "Registered hooks=2 retired=1 events=PRI/NORMAL/LATER/ONLY=1/2/3/0 " +
            "interactions=4 services=5 strings=6 settings=7 images=8"
        val jaFaces = "登録 hooks=2 解除=1 events=PRI/NORMAL/LATER/ONLY=1/2/3/0 " +
            "interactions=4 services=5 strings=6 settings=7 images=8"
        assertEquals(zhFaces, zh.modRuntimeOverviewFaces(faces))
        assertEquals(enFaces, en.modRuntimeOverviewFaces(faces))
        assertEquals(jaFaces, ja.modRuntimeOverviewFaces(faces))
    }

    /**
     * 第三方代码包信任确认 / 撤销确认（ADR-013 D3 / task-153 S4b、task-165 撤销二次确认）新增
     * 文案的形状闸：13 条公开字段 + 5 条参数化句式逐条点名（AGENTS §6.2：只改表不改闸 = 验收红）。
     * 二次确认与风险说明必须三语都如实写出「同一进程 / 没有沙箱 / 等同自行安装」，淡化风险在这里
     * 红；撤销确认也必须三语写清「删标记 / 下次装载重新确认 / 本局已装载代码不中途卸载」。
     */
    @Test
    fun `第三方代码包信任确认文案三语齐备且如实说明风险`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        val keys = trustCopyKeys()
        assertEquals(
            "新增文案条目数变了（现有 18 条 = 13 条公开字段 + 5 条参数化句式）",
            18,
            keys.size,
        )
        keys.forEach { (name, getter) ->
            val texts = all.map(getter)
            texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
            assertEquals("$name 三语重复", 3, texts.distinct().size)
        }
        assertTrustHonestCopy(all)
        assertTrustExactCopy(all)
        assertTrustRevokeConfirmCopy(all)
    }

    /** 诚实性闸：风险说明与二次确认正文都不得淡化「同进程 / 无沙箱 / 等同自行安装」。 */
    private fun assertTrustHonestCopy(all: List<SettingsStrings>) {
        val zh = all[0]
        val en = all[1]
        val ja = all[2]
        listOf(zh.modTrustHonestNote, zh.modTrustConfirmBody("demo.mod")).forEach {
            assertTrue("中文风险说明缺「同一个进程」：$it", it.contains("同一个进程"))
            assertTrue("中文风险说明缺「没有沙箱」：$it", it.contains("没有沙箱"))
            assertTrue("中文风险说明缺「自行安装」：$it", it.contains("自行安装"))
        }
        listOf(en.modTrustHonestNote, en.modTrustConfirmBody("demo.mod")).forEach {
            assertTrue("英文风险说明缺 same process：$it", it.contains("same process"))
            assertTrue("英文风险说明缺 no sandbox：$it", it.contains("no sandbox"))
            assertTrue("英文风险说明缺 yourself：$it", it.contains("yourself"))
        }
        listOf(ja.modTrustHonestNote, ja.modTrustConfirmBody("demo.mod")).forEach {
            assertTrue("日文风险说明缺 同じプロセス：$it", it.contains("同じプロセス"))
            assertTrue("日文风险说明缺 サンドボックス：$it", it.contains("サンドボックス"))
            assertTrue("日文风险说明缺 自分で：$it", it.contains("自分で"))
        }
    }

    /** 参数化输出三语原值与信任态显示映射（插槽漏档 / 映射错位都在这里红）。 */
    private fun assertTrustExactCopy(all: List<SettingsStrings>) {
        val zh = all[0]
        val en = all[1]
        val ja = all[2]
        // 后果与边界：二次确认正文带包名与「崩溃带走整局」，撤销后下次装载重新确认。
        assertTrue(zh.modTrustConfirmBody("demo.mod").contains("demo.mod"))
        assertTrue(zh.modTrustConfirmBody("demo.mod").contains("崩溃"))
        assertTrue(zh.modTrustRevoked("demo.mod").contains("下次"))
        assertTrustReadoutCopy(zh, en, ja)
        assertEquals("已信任", modTrustStateLabel(zh, true))
        assertEquals("未信任", modTrustStateLabel(zh, false))
        assertEquals("Trusted", modTrustStateLabel(en, true))
        assertEquals("Untrusted", modTrustStateLabel(en, false))
        assertEquals("信頼済み", modTrustStateLabel(ja, true))
        assertEquals("未信頼", modTrustStateLabel(ja, false))
        assertEquals(
            "「demo.mod」· 代码产物 2 个",
            modTrustRowReadout(zh, ModTrustRow("demo.mod", 2, false)),
        )
    }

    /** 信任 / 撤销 / 行读数参数化输出的三语原值。 */
    private fun assertTrustReadoutCopy(zh: SettingsStrings, en: SettingsStrings, ja: SettingsStrings) {
        assertEquals("「demo.mod」· 代码产物 3 个", zh.modTrustRowReadout("demo.mod", 3))
        assertEquals("\"demo.mod\" · 3 code artifact(s)", en.modTrustRowReadout("demo.mod", 3))
        assertEquals("「demo.mod」・コード成果物 3 件", ja.modTrustRowReadout("demo.mod", 3))
        assertEquals("已信任「demo.mod」，下次进入游戏时生效。", zh.modTrustGranted("demo.mod"))
        assertEquals(
            "Trusted \"demo.mod\". It will run the next time you enter the game.",
            en.modTrustGranted("demo.mod"),
        )
        assertEquals(
            "「demo.mod」を信頼しました。次回ゲーム開始時に反映されます。",
            ja.modTrustGranted("demo.mod"),
        )
        assertEquals(
            "已撤销「demo.mod」的信任，信任记录已删除，下次装载会重新要求确认。",
            zh.modTrustRevoked("demo.mod"),
        )
        assertEquals(
            "Revoked trust for \"demo.mod\". The trust record is deleted; the next load will ask again.",
            en.modTrustRevoked("demo.mod"),
        )
        assertEquals(
            "「demo.mod」の信頼を取り消しました。信頼記録を削除したので、次回読み込み時に再確認します。",
            ja.modTrustRevoked("demo.mod"),
        )
    }

    /**
     * 家督继任小节（task-160 / Lane HB、task-171 显示名）文案的形状闸：20 条公开字段 + 5 条参数化
     * 句式 = 25 条逐条点名（AGENTS §6.2：只改表不改闸 = 验收红）。条目清单见 [heirCopyKeys]。
     */
    @Test
    fun `家督继任文案三语齐备且条目点名`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        val keys = heirCopyKeys()
        assertEquals(
            "家督继任文案条目数变了（现有 25 条 = 20 条公开字段 + 5 条参数化句式" +
                "「模式行 / 候选行 / 候选带名行 / 候选二选一行 / 已指定行」；缺一条就少 1、多一条就多 1）",
            25,
            keys.size,
        )
        // 合法同形不进「三语互不重复」：破折号 2 条、未指定（ZH=JA）、模式行（全角冒号 ZH=JA）、
        // 候选行（#{0} 三语同形）、候选带名行与候选二选一行（ZH=JA 全角括号）——这 7 条只做非空检查，
        // 其余 18 条必须三语各异。
        val sharedShape = setOf(
            "modHeirModeUnknown",
            "modHeirNoneValue",
            "modHeirPendingNone",
            "modHeirModeLine",
            "modHeirCandidate",
            "modHeirCandidateNamed",
            "modHeirCandidateLabel",
        )
        assertEquals("参与三语互不重复检查的条数变了", 18, keys.count { it.first !in sharedShape })
        keys.forEach { (name, getter) ->
            val texts = all.map(getter)
            texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
            if (name !in sharedShape) {
                assertEquals("$name 三语重复", 3, texts.distinct().size)
            }
        }
        // 小节标题与两档规则标签的三语原值（改名 / 换词在这里红）。
        assertEquals("家督继任", all[0].modHeirSectionTitle)
        assertEquals("Family succession", all[1].modHeirSectionTitle)
        assertEquals("家督の後継", all[2].modHeirSectionTitle)
        assertEquals("手动指定", all[0].modHeirModeManual)
        assertEquals("Manual", all[1].modHeirModeManual)
        assertEquals("手動指定", all[2].modHeirModeManual)
    }

    /** 家督继任文案的兑现时机与规则门槛：入队 ≠ 立刻生效、非手动规则要指引去设置里改。 */
    @Test
    fun `家督继任文案钉住兑现时机与规则门槛`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        val zh = all[0]
        val en = all[1]
        val ja = all[2]
        // 破折号占位：无快照 / 无候选 / 未知规则都显式「—」，不留白。
        assertEquals("—", zh.modHeirNoneValue)
        assertEquals("—", zh.modHeirModeUnknown)
        // 参数化输出逐字（占位符漏档只剩半句在这里红）。
        assertEquals("继承规则：手动指定", zh.modHeirModeLine(zh.modHeirModeLabel, zh.modHeirModeManual))
        assertEquals("Rule: Manual", en.modHeirModeLine(en.modHeirModeLabel, en.modHeirModeManual))
        assertEquals("継承規則：手動指定", ja.modHeirModeLine(ja.modHeirModeLabel, ja.modHeirModeManual))
        assertEquals("#12", zh.modHeirCandidate(12L))
        // task-171 带名候选行：名字 + 稳定身份，缺名走上一行的纯 id 格式（调用方判定）。
        assertEquals("阿甲（#12）", zh.modHeirCandidateNamed("阿甲", 12L))
        assertEquals("阿甲 (#12)", en.modHeirCandidateNamed("阿甲", 12L))
        assertEquals("阿甲（#12）", ja.modHeirCandidateNamed("阿甲", 12L))
        // task-171 设置页二选一口径：有名字带名字、缺名/空白名回落纯 id（不出现空壳「（#12）」）。
        assertEquals("阿甲（#12）", zh.modHeirCandidateLabel("阿甲", 12L))
        assertEquals("阿甲 (#12)", en.modHeirCandidateLabel("阿甲", 12L))
        assertEquals("阿甲（#12）", ja.modHeirCandidateLabel("阿甲", 12L))
        assertEquals("#12", zh.modHeirCandidateLabel(null, 12L))
        assertEquals("#12", zh.modHeirCandidateLabel("  ", 12L))
        assertEquals("已指定：#12", zh.modHeirPending(zh.modHeirCandidate(12L)))
        assertEquals("Chosen: #12", en.modHeirPending(en.modHeirCandidate(12L)))
        assertEquals("指定済み：#12", ja.modHeirPending(ja.modHeirCandidate(12L)))
        // 语义承诺：入队 ≠ 立刻生效，提示必须点明「回合边界」；非手动规则必须指引去改设置。
        assertTrue("中文说明没点明回合边界生效", zh.modHeirHint.contains("回合边界"))
        assertTrue("英文说明没点明 turn boundary", en.modHeirHint.contains("turn boundary"))
        assertTrue("日文说明没点明ターン境界", ja.modHeirHint.contains("ターン境界"))
        assertTrue("非手动规则提示必须指引去本 Mod 设置里改", zh.modHeirManualOnlyHint.contains("设置"))
        assertTrue(
            "非手动规则提示必须点明当前规则不是手动指定",
            zh.modHeirManualOnlyHint.contains("手动指定"),
        )
        assertTrue("清除确认正文要点明回落到当前规则自动选择", zh.modHeirClearConfirmBody.contains("自动选择"))
        assertTrue("未就绪提示要点明「推进一回合」", zh.modHeirUnavailable.contains("回合"))
    }

    /** 家督继任文案条目点名清单（25 条 = 20 公开字段 + 5 参数化句式；AGENTS §6.2 形状闸）。 */
    private fun heirCopyKeys(): List<Pair<String, (SettingsStrings) -> String>> = listOf(
        "modHeirSectionTitle" to { s: SettingsStrings -> s.modHeirSectionTitle },
        "modHeirHint" to { s: SettingsStrings -> s.modHeirHint },
        "modHeirModeLabel" to { s: SettingsStrings -> s.modHeirModeLabel },
        "modHeirModeManual" to { s: SettingsStrings -> s.modHeirModeManual },
        "modHeirModeEldest" to { s: SettingsStrings -> s.modHeirModeEldest },
        "modHeirModeYoungest" to { s: SettingsStrings -> s.modHeirModeYoungest },
        "modHeirModeUnknown" to { s: SettingsStrings -> s.modHeirModeUnknown },
        "modHeirCandidatesLabel" to { s: SettingsStrings -> s.modHeirCandidatesLabel },
        "modHeirPendingNone" to { s: SettingsStrings -> s.modHeirPendingNone },
        "modHeirNoneValue" to { s: SettingsStrings -> s.modHeirNoneValue },
        "modHeirNoCandidates" to { s: SettingsStrings -> s.modHeirNoCandidates },
        "modHeirChooseButton" to { s: SettingsStrings -> s.modHeirChooseButton },
        "modHeirClearButton" to { s: SettingsStrings -> s.modHeirClearButton },
        "modHeirChooseQueued" to { s: SettingsStrings -> s.modHeirChooseQueued },
        "modHeirClearQueued" to { s: SettingsStrings -> s.modHeirClearQueued },
        "modHeirClearConfirmTitle" to { s: SettingsStrings -> s.modHeirClearConfirmTitle },
        "modHeirClearConfirmBody" to { s: SettingsStrings -> s.modHeirClearConfirmBody },
        "modHeirClearConfirmOk" to { s: SettingsStrings -> s.modHeirClearConfirmOk },
        "modHeirManualOnlyHint" to { s: SettingsStrings -> s.modHeirManualOnlyHint },
        "modHeirUnavailable" to { s: SettingsStrings -> s.modHeirUnavailable },
        "modHeirModeLine" to { s: SettingsStrings -> s.modHeirModeLine("L", "M") },
        "modHeirCandidate" to { s: SettingsStrings -> s.modHeirCandidate(12L) },
        "modHeirCandidateNamed" to { s: SettingsStrings -> s.modHeirCandidateNamed("阿甲", 12L) },
        "modHeirCandidateLabel" to { s: SettingsStrings -> s.modHeirCandidateLabel("阿甲", 12L) },
        "modHeirPending" to { s: SettingsStrings -> s.modHeirPending("C") },
    )
}

/**
 * 第三方代码包只读概览的形状闸辅助（task-162，task-195 补装载结论行）：12 条 = 10 条公开字段 +
 * 2 条参数化句式。
 *
 * 放在类外是刻意的：`SettingsStringsModTest` 已贴着 detekt `LargeClass` 阈值，清单塞进类里
 * 会把计数推过线（本波禁改阈值 / 禁 baseline / 禁 suppress）。
 */
internal fun assertCodeOverviewCopy(all: List<SettingsStrings>) {
    val keys: List<Pair<String, (SettingsStrings) -> String>> = listOf(
        "modCodeOverviewTitle" to { s: SettingsStrings -> s.modCodeOverviewTitle },
        "modCodeOverviewHint" to { s: SettingsStrings -> s.modCodeOverviewHint },
        "modCodeOverviewGateOn" to { s: SettingsStrings -> s.modCodeOverviewGateOn },
        "modCodeOverviewGateOff" to { s: SettingsStrings -> s.modCodeOverviewGateOff },
        "modCodeOverviewEmpty" to { s: SettingsStrings -> s.modCodeOverviewEmpty },
        "modCodeOverviewUnreadable" to { s: SettingsStrings -> s.modCodeOverviewUnreadable },
        "modCodeOverviewRow" to { s: SettingsStrings -> s.modCodeOverviewRow("demo.mod", 2, "1.5 KB") },
        "modCodeLoadLabel" to { s: SettingsStrings -> s.modCodeLoadLabel },
        "modCodeLoadUnknown" to { s: SettingsStrings -> s.modCodeLoadUnknown },
        "modCodeLoadLoaded" to { s: SettingsStrings -> s.modCodeLoadLoaded },
        "modCodeLoadSkipped" to { s: SettingsStrings -> s.modCodeLoadSkipped },
        "modCodeLoadEngineTooOld" to { s: SettingsStrings -> s.modCodeLoadEngineTooOld(9, 7) },
    )
    assertEquals(
        "新增文案条目数变了（现有 12 条 = 10 条公开字段 + 2 条参数化句式）",
        12,
        keys.size,
    )
    keys.forEach { (name, getter) ->
        val texts = all.map(getter)
        texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
        assertEquals("$name 三语重复", 3, texts.distinct().size)
    }
    val zh = all[0]
    val en = all[1]
    val ja = all[2]
    assertTrue("中文关态必须明说不会装载任何第三方代码", zh.modCodeOverviewGateOff.contains("不会装载任何第三方代码"))
    assertTrue("英文关态必须明说 no third-party code", en.modCodeOverviewGateOff.contains("no third-party code"))
    assertTrue("日文关态必须明说一切読み込まれません", ja.modCodeOverviewGateOff.contains("一切読み込まれません"))
    assertTrue("缺数据必须照「—」口径而不是 0", zh.modCodeOverviewUnreadable.contains("—"))
    assertEquals("「demo.mod」· 2 个版本 · 1.5 KB", zh.modCodeOverviewRow("demo.mod", 2, "1.5 KB"))
    assertEquals("\"demo.mod\" · 2 version(s) · 1.5 KB", en.modCodeOverviewRow("demo.mod", 2, "1.5 KB"))
    assertEquals("「demo.mod」・2 バージョン・1.5 KB", ja.modCodeOverviewRow("demo.mod", 2, "1.5 KB"))
    // 装载结论行：缺记录照「—」口径；引擎太旧句必须把两侧版本号都填进去（不残留槽位）。
    assertTrue("装载结论缺数据必须照「—」口径", zh.modCodeLoadUnknown.contains("—"))
    val tooOld = zh.modCodeLoadEngineTooOld(9, 7)
    assertTrue("引擎太旧句必须带包要求的版本：$tooOld", tooOld.contains("9"))
    assertTrue("引擎太旧句必须带本机版本：$tooOld", tooOld.contains("7"))
    assertTrue("引擎太旧句不许残留槽位：$tooOld", !tooOld.contains("{"))
}

/**
 * 第三方代码包装载总闸开关与二次确认文案的形状闸辅助（task-191）：6 条 = 开关标题 + 开关说明 +
 * 确认标题 + 确认正文 + 确认按钮 + 写盘失败红字。
 *
 * 确认正文三件事必须逐语都在：同进程同权限无沙箱（出错会带走整局）、只在了解来源时开启、
 * 下次进入游戏才生效。少说一件，玩家就是在不知情下把机器码放进同一进程跑。放类外同
 * [assertCodeOverviewCopy]：类已贴着 detekt `LargeClass` 阈值。
 */
internal fun assertModDexLoaderSwitchCopy(all: List<SettingsStrings>) {
    val keys: List<Pair<String, (SettingsStrings) -> String>> = listOf(
        "modCodeLoaderSwitchTitle" to { s: SettingsStrings -> s.modCodeLoaderSwitchTitle },
        "modCodeLoaderSwitchHint" to { s: SettingsStrings -> s.modCodeLoaderSwitchHint },
        "modCodeLoaderConfirmTitle" to { s: SettingsStrings -> s.modCodeLoaderConfirmTitle },
        "modCodeLoaderConfirmBody" to { s: SettingsStrings -> s.modCodeLoaderConfirmBody },
        "modCodeLoaderConfirmOk" to { s: SettingsStrings -> s.modCodeLoaderConfirmOk },
        "modCodeLoaderWriteFailed" to { s: SettingsStrings -> s.modCodeLoaderWriteFailed },
    )
    assertEquals(
        "新增文案条目数变了（现有 6 条 = 开关标题 + 开关说明 + 确认标题 + 确认正文 + " +
            "确认按钮 + 写盘失败）",
        6,
        keys.size,
    )
    keys.forEach { (name, getter) ->
        val texts = all.map(getter)
        texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
        assertEquals("$name 三语重复", 3, texts.distinct().size)
    }
    val bodies = listOf(
        "zh" to all[0].modCodeLoaderConfirmBody,
        "en" to all[1].modCodeLoaderConfirmBody,
        "ja" to all[2].modCodeLoaderConfirmBody,
    )
    val sandbox = mapOf(
        "zh" to "没有沙箱隔离",
        "en" to "no sandbox",
        "ja" to "サンドボックスはありません",
    )
    val crash = mapOf(
        "zh" to "崩溃会带走整局游戏",
        "en" to "take down the whole game session",
        "ja" to "ゲーム全体が失われる",
    )
    val source = mapOf(
        "zh" to "请只在了解代码来源时开启",
        "en" to "know where the code comes from",
        "ja" to "入手元が分かる場合にのみ",
    )
    val nextStart = mapOf(
        "zh" to "下次进入游戏时才生效",
        "en" to "next time the game starts",
        "ja" to "次回ゲーム起動時から有効",
    )
    for ((lang, body) in bodies) {
        assertTrue("$lang 确认正文必须说清无沙箱同进程", body.contains(sandbox.getValue(lang)))
        assertTrue("$lang 确认正文必须说清出错会带走整局", body.contains(crash.getValue(lang)))
        assertTrue("$lang 确认正文必须说清只在了解来源时开启", body.contains(source.getValue(lang)))
        assertTrue("$lang 确认正文必须说清下次进入游戏才生效", body.contains(nextStart.getValue(lang)))
    }
}

/**
 * 代码闸状态文案的形状闸辅助（task-179，task-187 补多版本残留态，task-195 补引擎太旧态）：
 * 7 条 = 1 条读数标签 + 6 条代码态。
 *
 * 六态必须两两可分是硬要求：把「未开启」和「待信任」写成同一句，玩家会以为「补个信任就好」，
 * 而总闸关着时补信任确实没用；「多版本残留」与「身份损坏」同样必须分开（两者都只能重导，
 * 但前者重导即可清，后者重导后要再确认身份闸）；「引擎太旧」必须说「更新应用」而不是「点信任」
 * （信任救不回来，只有换新引擎）。放类外同 [assertCodeOverviewCopy]：类已贴着
 * detekt `LargeClass` 阈值，清单塞进类里会把计数推过线（本波禁改阈值 / 禁 baseline / 禁 suppress）。
 */
internal fun assertCodeGateStateCopy(all: List<SettingsStrings>) {
    val keys: List<Pair<String, (SettingsStrings) -> String>> = listOf(
        "modManageCodeLabel" to { s: SettingsStrings -> s.modManageCodeLabel },
        "modCodeStateEnabled" to { s: SettingsStrings -> s.modCodeStateEnabled },
        "modCodeStateGateOff" to { s: SettingsStrings -> s.modCodeStateGateOff },
        "modCodeStateUntrusted" to { s: SettingsStrings -> s.modCodeStateUntrusted },
        "modCodeStateCorrupt" to { s: SettingsStrings -> s.modCodeStateCorrupt },
        "modCodeStateMultipleVersions" to { s: SettingsStrings -> s.modCodeStateMultipleVersions },
        "modCodeStateEngineTooOld" to { s: SettingsStrings -> s.modCodeStateEngineTooOld },
    )
    assertEquals("新增文案条目数变了（现有 7 条 = 1 条读数标签 + 6 条代码态）", 7, keys.size)
    keys.forEach { (name, getter) ->
        val texts = all.map(getter)
        texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
        assertEquals("$name 三语重复", 3, texts.distinct().size)
    }
    assertEquals("代码", all[0].modManageCodeLabel)
    assertEquals("code", all[1].modManageCodeLabel)
    assertEquals("コード", all[2].modManageCodeLabel)
    for (text in all) {
        val states = listOf(
            text.modCodeStateEnabled,
            text.modCodeStateGateOff,
            text.modCodeStateUntrusted,
            text.modCodeStateCorrupt,
            text.modCodeStateMultipleVersions,
            text.modCodeStateEngineTooOld,
        )
        assertEquals("${text.lang} 六态必须两两可分", 6, states.distinct().size)
        val hinted = listOf(
            ModCodeGateHintKind.MULTIPLE_VERSIONS to text.modCodeStateMultipleVersions,
            ModCodeGateHintKind.CODE_ID_CORRUPT to text.modCodeStateCorrupt,
            ModCodeGateHintKind.ENGINE_TOO_OLD to text.modCodeStateEngineTooOld,
            ModCodeGateHintKind.GATE_OFF to text.modCodeStateGateOff,
            ModCodeGateHintKind.UNTRUSTED to text.modCodeStateUntrusted,
        )
        for ((kind, word) in hinted) {
            val hint = ModCodeGateHint(kind, "demo.mod")
            val line = modCodeStateLine(text, ModCodePackState("demo.mod", hint))
            assertTrue("${text.lang} 读数行丢了状态词：$line", line.contains(word))
        }
        val enabled = modCodeStateLine(text, ModCodePackState("demo.mod", null))
        assertTrue("${text.lang} 已启用态丢了状态词：$enabled", enabled.contains(text.modCodeStateEnabled))
    }
}

/**
 * 已装列表搜索 / 来源筛选的形状闸辅助（Lane modsearch，task-168）：4 条 = 3 公开字段 + 1 参数化句式。
 *
 * 放类外同 [assertCodeOverviewCopy]：类已贴着 detekt `LargeClass` / `LongMethod` 阈值。两态分开
 * 是硬要求——「筛选后没有匹配」复用「一个都没装」的文案会把玩家的操作失误说成没装。
 */
private fun assertModSearchCopy(all: List<SettingsStrings>) {
    val zh = all[0]
    val en = all[1]
    val ja = all[2]
    assertEquals("搜索（名称 / 包名 / 作者）", zh.modManageSearchLabel)
    assertEquals("Search (name / package / author)", en.modManageSearchLabel)
    assertEquals("検索（名前 / パッケージ / 作者）", ja.modManageSearchLabel)
    assertEquals("全部", zh.modManageFilterAll)
    assertEquals("All", en.modManageFilterAll)
    assertEquals("すべて", ja.modManageFilterAll)
    SettingsLanguage.entries.forEach { language ->
        val text = SettingsStrings.of(language)
        assertNotEquals("$language 筛选后空结果不许复用「一个都没装」文案", text.modManageEmpty, text.modManageFilterEmpty)
    }
    // 参数化计数行：N / M 两个序位都得落进句子，0 条也要能说。
    assertEquals("筛选结果 2 / 共 3", zh.modManageFilterCount(2, 3))
    assertEquals("Showing 2 of 3", en.modManageFilterCount(2, 3))
    assertEquals("表示 2 / 全 3", ja.modManageFilterCount(2, 3))
    assertEquals("筛选结果 0 / 共 3", zh.modManageFilterCount(0, 3))
}

/**
 * 第三方代码包信任 / 撤销确认的条目点名清单（18 条 = 13 公开字段 + 5 参数化句式；
 * AGENTS §6.2 形状闸）。放类外同 [assertCodeOverviewCopy]：类已贴着 `LargeClass` 阈值。
 */
private fun trustCopyKeys(): List<Pair<String, (SettingsStrings) -> String>> = listOf(
    "modTrustTitle" to { s: SettingsStrings -> s.modTrustTitle },
    "modTrustHint" to { s: SettingsStrings -> s.modTrustHint },
    "modTrustHonestNote" to { s: SettingsStrings -> s.modTrustHonestNote },
    "modTrustEmpty" to { s: SettingsStrings -> s.modTrustEmpty },
    "modTrustStateTrusted" to { s: SettingsStrings -> s.modTrustStateTrusted },
    "modTrustStateUntrusted" to { s: SettingsStrings -> s.modTrustStateUntrusted },
    "modTrustGrantButton" to { s: SettingsStrings -> s.modTrustGrantButton },
    "modTrustRevokeButton" to { s: SettingsStrings -> s.modTrustRevokeButton },
    "modTrustConfirmTitle" to { s: SettingsStrings -> s.modTrustConfirmTitle },
    "modTrustConfirmOk" to { s: SettingsStrings -> s.modTrustConfirmOk },
    "modTrustWriteFailed" to { s: SettingsStrings -> s.modTrustWriteFailed },
    "modTrustRevokeConfirmTitle" to { s: SettingsStrings -> s.modTrustRevokeConfirmTitle },
    "modTrustRevokeConfirmOk" to { s: SettingsStrings -> s.modTrustRevokeConfirmOk },
    "modTrustRowReadout" to { s: SettingsStrings -> s.modTrustRowReadout("demo.mod", 3) },
    "modTrustConfirmBody" to { s: SettingsStrings -> s.modTrustConfirmBody("demo.mod") },
    "modTrustGranted" to { s: SettingsStrings -> s.modTrustGranted("demo.mod") },
    "modTrustRevoked" to { s: SettingsStrings -> s.modTrustRevoked("demo.mod") },
    "modTrustRevokeConfirmBody" to { s: SettingsStrings -> s.modTrustRevokeConfirmBody("demo.mod") },
)

/**
 * 撤销二次确认（task-165）的三语逐字闸：标题 / 确认按钮 / 正文原值，以及正文的三条承诺
 * 「删信任记录」「下次装载回到未信任」「本局已装载代码不中途卸载」——删任何一句在这里红。
 */
private fun assertTrustRevokeConfirmCopy(all: List<SettingsStrings>) {
    val zh = all[0]
    val en = all[1]
    val ja = all[2]
    assertEquals("撤销这个代码包的信任？", zh.modTrustRevokeConfirmTitle)
    assertEquals("Revoke trust for this code pack?", en.modTrustRevokeConfirmTitle)
    assertEquals("このコードパックの信頼を取り消しますか？", ja.modTrustRevokeConfirmTitle)
    assertEquals("确认撤销", zh.modTrustRevokeConfirmOk)
    assertEquals("Revoke", en.modTrustRevokeConfirmOk)
    assertEquals("取り消す", ja.modTrustRevokeConfirmOk)
    assertEquals(
        "「demo.mod」的信任记录会被删除，下次进入游戏时它回到「未信任」，必须重新确认才会运行。" +
            "本局已经装载的代码不会中途卸载。",
        zh.modTrustRevokeConfirmBody("demo.mod"),
    )
    assertEquals(
        "\"demo.mod\" will lose its trust record. The next time you enter the game it goes back " +
            "to untrusted and must be confirmed again before it runs. Code already loaded in " +
            "this session is not unloaded.",
        en.modTrustRevokeConfirmBody("demo.mod"),
    )
    assertEquals(
        "「demo.mod」の信頼記録を削除します。次回ゲーム開始時は「未信頼」に戻り、再確認するまで" +
            "実行されません。今回のプレイで読み込み済みのコードは途中で破棄されません。",
        ja.modTrustRevokeConfirmBody("demo.mod"),
    )
    assertTrue("撤销正文必须带包名", zh.modTrustRevokeConfirmBody("demo.mod").contains("demo.mod"))
    assertTrue("撤销正文必须点明回到未信任", zh.modTrustRevokeConfirmBody("x").contains("未信任"))
    assertTrue("撤销正文必须点明本局已装载代码不中途卸载", zh.modTrustRevokeConfirmBody("x").contains("不会中途卸载"))
}

/**
 * Mod 诊断快照导出（task-172）的形状闸：5 条 = 导出按钮 / 导出说明 / 弹窗标题 /
 * 弹窗说明 / 复制全部。放类外同 [assertCodeOverviewCopy]：类已贴着 detekt 阈值。
 *
 * 产品口径：「只读 / 不联网」与「不含设置值与存储值」必须在说明里明说——这是玩家敢不敢
 * 点「复制全部」的前提，删任何一句都在这里红。
 */
private fun assertDiagnosticExportCopy(all: List<SettingsStrings>) {
    val keys: List<Pair<String, (SettingsStrings) -> String>> = listOf(
        "modDiagnosticExport" to { s: SettingsStrings -> s.modDiagnosticExport },
        "modDiagnosticExportHint" to { s: SettingsStrings -> s.modDiagnosticExportHint },
        "modDiagnosticDialogTitle" to { s: SettingsStrings -> s.modDiagnosticDialogTitle },
        "modDiagnosticDialogHint" to { s: SettingsStrings -> s.modDiagnosticDialogHint },
        "modDiagnosticDialogCopy" to { s: SettingsStrings -> s.modDiagnosticDialogCopy },
    )
    assertEquals("新增文案条目数变了（现有 5 条）", 5, keys.size)
    keys.forEach { (name, getter) ->
        val texts = all.map(getter)
        texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
        assertEquals("$name 三语重复", 3, texts.distinct().size)
    }
    // 关键三语原值（改名 / 漏档都在这里红）。
    assertEquals("导出诊断", all[0].modDiagnosticExport)
    assertEquals("Export diagnostics", all[1].modDiagnosticExport)
    assertEquals("診断をエクスポート", all[2].modDiagnosticExport)
    assertEquals("Mod 诊断快照", all[0].modDiagnosticDialogTitle)
    assertEquals("Mod diagnostic snapshot", all[1].modDiagnosticDialogTitle)
    assertEquals("Mod 診断スナップショット", all[2].modDiagnosticDialogTitle)
    assertEquals("复制全部", all[0].modDiagnosticDialogCopy)
    assertEquals("Copy all", all[1].modDiagnosticDialogCopy)
    assertEquals("すべてコピー", all[2].modDiagnosticDialogCopy)
    // 产品口径：只读 / 不联网三语各钉一条，弹窗说明三语都要声明不含值本体。
    assertTrue("中文导出说明要点明只读", all[0].modDiagnosticExportHint.contains("只读"))
    assertTrue("中文导出说明要点明不联网", all[0].modDiagnosticExportHint.contains("不联网"))
    assertTrue("英文导出说明要点明 read-only", all[1].modDiagnosticExportHint.contains("read-only"))
    assertTrue("日文导出说明要点明読み取り専用", all[2].modDiagnosticExportHint.contains("読み取り専用"))
    assertTrue("中文弹窗说明必须点明不含设置值与存储值", all[0].modDiagnosticDialogHint.contains("不含设置值与存储值"))
    assertTrue("英文弹窗说明必须声明无值本体", all[1].modDiagnosticDialogHint.contains("No setting or storage values"))
    assertTrue("日文弹窗说明必须声明无值本体", all[2].modDiagnosticDialogHint.contains("設定値と保存値は含まれません"))
}

/**
 * 卸载影响预览（task-174）的条目点名清单：10 条公开字段（小标题 / 7 类标签 / 「无」/「—（无读数）」）
 * + 4 条参数化句式 = 14 条。
 *
 * 放类外同 [assertCodeOverviewCopy]：`SettingsStringsModTest` 已贴着 detekt `LargeClass` 阈值。
 */
private fun uninstallImpactCopyKeys(): List<Pair<String, (SettingsStrings) -> String>> = listOf(
    "modUninstallImpactHeading" to { s: SettingsStrings -> s.modUninstallImpactHeading },
    "modUninstallImpactCode" to { s: SettingsStrings -> s.modUninstallImpactCode },
    "modUninstallImpactSettings" to { s: SettingsStrings -> s.modUninstallImpactSettings },
    "modUninstallImpactSave" to { s: SettingsStrings -> s.modUninstallImpactSave },
    "modUninstallImpactMedia" to { s: SettingsStrings -> s.modUninstallImpactMedia },
    "modUninstallImpactAssets" to { s: SettingsStrings -> s.modUninstallImpactAssets },
    "modUninstallImpactChara" to { s: SettingsStrings -> s.modUninstallImpactChara },
    "modUninstallImpactMembers" to { s: SettingsStrings -> s.modUninstallImpactMembers },
    "modUninstallImpactNone" to { s: SettingsStrings -> s.modUninstallImpactNone },
    "modUninstallImpactMissing" to { s: SettingsStrings -> s.modUninstallImpactMissing },
    "modUninstallImpactCodeLine" to { s: SettingsStrings -> s.modUninstallImpactCodeLine(2, "4.0 KB") },
    "modUninstallImpactSaveLine" to { s: SettingsStrings -> s.modUninstallImpactSaveLine("2.0 KB", 3) },
    "modUninstallImpactAssetsLine" to { s: SettingsStrings -> s.modUninstallImpactAssetsLine(3, "1.0 KB") },
    "modUninstallImpactCountLine" to { s: SettingsStrings -> s.modUninstallImpactCountLine(5) },
)

/**
 * 卸载影响预览的三语逐字闸：14 条点名的同时钉住「无 / —（无读数）」两态与四类句式序位。
 *
 * 零读数写「无」、取不到写「—（无读数）」是硬要求：两者混用会让玩家以为卸载什么都不删。
 */
internal fun assertUninstallImpactCopy(all: List<SettingsStrings>) {
    val keys = uninstallImpactCopyKeys()
    assertEquals("新增文案条目数变了（现有 14 条 = 10 条公开字段 + 4 条参数化句式）", 14, keys.size)
    keys.forEach { (name, getter) ->
        val texts = all.map(getter)
        texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
        assertEquals("$name 三语重复", 3, texts.distinct().size)
    }
    val zh = all[0]
    val en = all[1]
    val ja = all[2]
    assertEquals("卸载会一并删除：", zh.modUninstallImpactHeading)
    assertEquals("Uninstalling also deletes:", en.modUninstallImpactHeading)
    assertEquals("アンインストールで以下も削除されます：", ja.modUninstallImpactHeading)
    assertEquals(
        listOf("代码", "包设置", "存档数据", "媒体文件", "资源文件", "角色模板", "包文件"),
        listOf(
            zh.modUninstallImpactCode,
            zh.modUninstallImpactSettings,
            zh.modUninstallImpactSave,
            zh.modUninstallImpactMedia,
            zh.modUninstallImpactAssets,
            zh.modUninstallImpactChara,
            zh.modUninstallImpactMembers,
        ),
    )
    assertEquals("无", zh.modUninstallImpactNone)
    assertEquals("none", en.modUninstallImpactNone)
    assertEquals("なし", ja.modUninstallImpactNone)
    assertEquals("—（无读数）", zh.modUninstallImpactMissing)
    assertEquals("— (no reading)", en.modUninstallImpactMissing)
    assertEquals("—（読取なし）", ja.modUninstallImpactMissing)
    assertEquals("2 个版本 / 4.0 KB", zh.modUninstallImpactCodeLine(2, "4.0 KB"))
    assertEquals("2 version(s) / 4.0 KB", en.modUninstallImpactCodeLine(2, "4.0 KB"))
    assertEquals("2 バージョン / 4.0 KB", ja.modUninstallImpactCodeLine(2, "4.0 KB"))
    assertEquals("2.0 KB / 3 项", zh.modUninstallImpactSaveLine("2.0 KB", 3))
    assertEquals("2.0 KB / 3 item(s)", en.modUninstallImpactSaveLine("2.0 KB", 3))
    assertEquals("2.0 KB / 3 件", ja.modUninstallImpactSaveLine("2.0 KB", 3))
    assertEquals("3 个 / 1.0 KB", zh.modUninstallImpactAssetsLine(3, "1.0 KB"))
    assertEquals("3 file(s) / 1.0 KB", en.modUninstallImpactAssetsLine(3, "1.0 KB"))
    assertEquals("3 個 / 1.0 KB", ja.modUninstallImpactAssetsLine(3, "1.0 KB"))
    assertEquals("5 项", zh.modUninstallImpactCountLine(5))
    assertEquals("5 item(s)", en.modUninstallImpactCountLine(5))
    assertEquals("5 件", ja.modUninstallImpactCountLine(5))
    // 「确实没有」与「取不到」三语都不许画成同一个词。
    SettingsLanguage.entries.forEach { language ->
        val text = SettingsStrings.of(language)
        assertNotEquals(
            "$language 零读数与取不到读数混用了",
            text.modUninstallImpactNone,
            text.modUninstallImpactMissing,
        )
    }
}

/**
 * 存档导入/导出卡的 mod 例外提示（MODDX4-D3 新增 2 条）的形状闸（AGENTS §6.2：只改表不改闸
 * = 验收红）。反射完整性用例只钉「非空 + 占位符」，这里逐条点名、逐字钉住三语原值：
 * 未装补丁 = 互通不受影响、带补丁的存档附 `.rt2` 扩展数据且 PC 读不到扩展部分、
 * 缺扩展数据/PC 玩过传回 = 警告非静默。规约锚 = specs 下的 save-format.md 第 11 节 +
 * era-arch-v61u.md:223-231 兼容性矩阵（「100% 互通」表述已作废）。
 * 上屏落位由组长接线：导出卡说明行邻域（SaveExportCard）与导入卡标题说明行邻域（SaveImportCard）。
 */
class SettingsStringsSaveModHintTest {

    @Test
    fun `存档卡 mod 例外提示三语齐备且诚实口径点名`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        // 条目点名清单：本次新增 2 条（改名 / 漏档 / 只补一种语言都在这里红）。
        val keys: List<Pair<String, (SettingsStrings) -> String>> = listOf(
            "saveModExportHint" to { s: SettingsStrings -> s.saveModExportHint },
            "saveModImportHint" to { s: SettingsStrings -> s.saveModImportHint },
        )
        assertEquals("新增文案条目数变了（现有 2 条）", 2, keys.size)
        keys.forEach { (name, getter) ->
            val texts = all.map(getter)
            texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
            assertEquals("$name 三语重复", 3, texts.distinct().size)
        }
        // 导出提示逐字：未装补丁照常互通；带补丁的存档附扩展数据文件，PC 读不到扩展部分
        //（照实说，不许夸大成「存档会坏」）。玩家面不出现内部文件名（`.rt2`），只讲「扩展数据文件」。
        assertEquals(
            "没装补丁（Mod）时，导出的存档电脑版照常能读；装了补丁的存档会附带一份扩展数据文件，电脑版读不到扩展部分。",
            all[0].saveModExportHint,
        )
        assertEquals(
            "With no patches (Mods) installed, exported saves read normally on PC; patched saves " +
                "carry a companion extension data file whose contents the PC version cannot read.",
            all[1].saveModExportHint,
        )
        assertEquals(
            "パッチ（Mod）を入れていなければ、書き出したセーブはパソコン版でそのまま読めます。パッチを入れたセーブには拡張データファイルが付随し、パソコン版は拡張部分を読めません。",
            all[2].saveModExportHint,
        )
        // 导入提示逐字：本体与扩展数据成对导入，缺扩展数据 = 警告非静默（矩阵第 5 行）。
        assertEquals(
            "没装补丁（Mod）时，存档与电脑版完全互通；装了补丁后，扩展部分单独成文件，本体和扩展数据要成对导入，缺了扩展数据应用会提醒。",
            all[0].saveModImportHint,
        )
        assertEquals(
            "With no patches (Mods) installed, saves interop fully with PC; with patches, the extension " +
                "part travels in a separate companion file — import the body and extension data as a pair, " +
                "and the app will warn when the extension data is missing.",
            all[1].saveModImportHint,
        )
        assertEquals(
            "パッチ（Mod）を入れていなければ、セーブはパソコン版と完全にやり取りできます。" +
                "パッチを入れた場合は拡張部分が別ファイル側にあり、本体と拡張データをペアで読み込んでください。" +
                "拡張データが足りないときはアプリが知らせます。",
            all[2].saveModImportHint,
        )
    }
}

/**
 * 玩家授权诊断通道开关的三语文案闸（判词 9 / PDIAG1）：6 条 = 开关标题 + 开关说明 + 确认标题 +
 * 确认正文 + 确认按钮 + 写盘失败红字。照 [assertModDexLoaderSwitchCopy] 的形状闸口径
 * （AGENTS §6.2：只改表不改闸 = 验收红）。
 *
 * 说明与正文各钉三件事逐语都在：**只放读侧**、**只落本机不自动发送**、**开启要重开一局才生效**。
 * 第三条是装配面的真实代价（启动点 GameHost.kt:1050 在装载尾段，本波不动 GameHost / MainActivity）——
 * 写成「立即生效」就是骗玩家，故逐语钉住措辞而不是只钉中文。
 */
class SettingsStringsPlayerDiagTest {

    @Test
    fun playerDiagCopyIsThreeLanguagesAndHonest() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        val keys: List<Pair<String, (SettingsStrings) -> String>> = listOf(
            "playerDiagTitle" to { s: SettingsStrings -> s.playerDiagTitle },
            "playerDiagHint" to { s: SettingsStrings -> s.playerDiagHint },
            "playerDiagConfirmTitle" to { s: SettingsStrings -> s.playerDiagConfirmTitle },
            "playerDiagConfirmBody" to { s: SettingsStrings -> s.playerDiagConfirmBody },
            "playerDiagConfirmOk" to { s: SettingsStrings -> s.playerDiagConfirmOk },
            "playerDiagWriteFailed" to { s: SettingsStrings -> s.playerDiagWriteFailed },
        )
        assertEquals(
            "新增文案条目数变了（现有 6 条 = 开关标题 + 开关说明 + 确认标题 + 确认正文 + 确认按钮 + 写盘失败）",
            6,
            keys.size,
        )
        keys.forEach { entry ->
            val name = entry.first
            val texts = all.map(entry.second)
            texts.forEach { assertTrue(name + " 有一档空文案", it.isNotEmpty()) }
            assertEquals(name + " 三语重复（同一条文案抄三遍 = 玩家看到的语言不对）", 3, texts.distinct().size)
        }
        // 逐字钉中文原值：改措辞要回这里一起改，不许悄悄把「只读」「重开一局」抹掉。
        assertEquals("开启只读诊断通道（排查卡死用）", all[0].playerDiagTitle)
        assertEquals("开启只读诊断通道？", all[0].playerDiagConfirmTitle)
        assertEquals("了解，开启", all[0].playerDiagConfirmOk)
        assertEquals("写入失败：授权位没有落盘，请再试一次", all[0].playerDiagWriteFailed)
        val readonlyHint = mapOf("zh" to "读侧", "en" to "read-side", "ja" to "読み取り")
        val readonlyBody = mapOf("zh" to "只读", "en" to "read-side", "ja" to "読み取り")
        val noUpload = mapOf("zh" to "不会自动发送", "en" to "never sent", "ja" to "自動送信")
        val restartHint = mapOf("zh" to "重开一局", "en" to "restart", "ja" to "再起動")
        val restartBody = mapOf("zh" to "重开一局", "en" to "next time the game starts", "ja" to "次回ゲーム起動時")
        listOf("zh" to all[0].playerDiagHint, "en" to all[1].playerDiagHint, "ja" to all[2].playerDiagHint)
            .forEach { pair ->
                val lang = pair.first
                val hint = pair.second
                assertTrue(lang + " 开关说明必须说清只放读侧：" + hint, hint.contains(readonlyHint.getValue(lang)))
                assertTrue(lang + " 开关说明必须说清不自动发送：" + hint, hint.contains(noUpload.getValue(lang)))
                assertTrue(lang + " 开关说明必须说清重开一局生效：" + hint, hint.contains(restartHint.getValue(lang)))
            }
        listOf(
            "zh" to all[0].playerDiagConfirmBody,
            "en" to all[1].playerDiagConfirmBody,
            "ja" to all[2].playerDiagConfirmBody,
        )
            .forEach { pair ->
                val lang = pair.first
                val body = pair.second
                assertTrue(lang + " 确认正文必须说清只放读侧：" + body, body.contains(readonlyBody.getValue(lang)))
                assertTrue(lang + " 确认正文必须说清不自动发送：" + body, body.contains(noUpload.getValue(lang)))
                assertTrue(lang + " 确认正文必须点明写侧要 debug 包装机：" + body, body.contains("debug"))
                assertTrue(lang + " 确认正文必须说清下次进入游戏才生效：" + body, body.contains(restartBody.getValue(lang)))
            }
    }
}
