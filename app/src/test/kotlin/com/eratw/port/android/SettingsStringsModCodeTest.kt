package com.eratw.port.android

import org.junit.Test

/**
 * 第三方代码包（ADR-013 路 B）与卸载影响预览两面的玩家文案形状闸。
 *
 * 从 `SettingsStringsModTest` 拆出（detekt `LargeClass`：两条 lane 的文案条目叠加后整类越过
 * 600 行阈值）。**拆分只移动用例、不改断言**：条目点名清单与互斥断言仍在
 * `SettingsStringsModTest.kt` 文件尾的顶层辅助里（`internal` 可见），这里只负责触发。
 */
class SettingsStringsModCodeTest {

    /**
     * 第三方代码包只读概览（ADR-013 路 B / task-162 / task-195 补装载结论行）新增文案的形状闸：
     * 10 条公开字段 + 2 条参数化句式逐条点名（AGENTS §6.2：只改表不改闸 = 验收红）。
     */
    @Test
    fun `第三方代码包只读概览文案三语齐备且关态说明不装载`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        assertCodeOverviewCopy(all)
    }

    /**
     * 卸载影响预览（Lane uninstallpreview / task-174）新增文案的形状闸：10 条公开字段 +
     * 4 条参数化句式逐条点名。
     */
    @Test
    fun `卸载影响预览文案三语齐备且零与缺读数可分辨`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        assertUninstallImpactCopy(all)
    }

    /**
     * 第三方代码包概览（ADR-013 路 B）装载总闸开关与二次确认文案（task-191）的形状闸：
     * 6 条 = 开关标题 + 开关说明 + 确认标题 + 确认正文 + 确认按钮 + 写盘失败红字逐条点名。
     */
    @Test
    fun `第三方代码包装载总开关文案三语齐备且确认讲清后果`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        assertModDexLoaderSwitchCopy(all)
    }

    /**
     * 代码闸状态文案（task-179 / task-187 / task-195 / ADR-013 路 B）的形状闸：7 条 = 1 条读数
     * 标签（代码）+ 6 条代码态（已启用 / 未开启 / 待信任 / 身份损坏 / 多版本残留 / 引擎太旧）逐条点名。
     */
    @Test
    fun `代码闸状态文案三语齐备且六态互斥`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        assertCodeGateStateCopy(all)
    }
}
