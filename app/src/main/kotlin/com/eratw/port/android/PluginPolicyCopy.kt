package com.eratw.port.android

import com.eratw.port.core.policy.DisabledErbPatchCatalog

/**
 * 补丁/插件策略域的玩家面文案表（PLUGIN-POLICY，2026-11-07，三语；第二轮改为整体禁载）。
 *
 * ## 为什么单独一张表
 *
 * 除「一条链路整组一起动」这条既有理由（见 [ResourcePackCopy] / [ModImportCopy]）外，
 * 还有一条硬约束：[SettingsStrings] 的私有构造器已有 252 个文案参数，JVMS 4.3.3 规定
 * 方法参数总数（含 this）不得超过 255 —— 本波最初把四条键并进大表，app 定向单测直接
 * 在类加载期抛 `ClassFormatError: Too many arguments in method signature`（不是断言红，
 * 是整个 SettingsStrings 类装不进来）。故补丁兼容性卡自成一表；日后给设置页加键也别再
 * 往大表塞，先走独立域表。
 *
 * ## 三条硬规矩（与 [ResourcePackCopy] 同口径）
 *
 * 1. **取语言只有一条路**：[of] 只吃 [SettingsLanguage]，壳层从 `SettingsStrings.lang`
 *    取好一次往里传；本表不碰 SharedPreferences、不判系统 Locale。
 * 2. **玩家面不内联字面量**：卡片只消费本表的键，目录 id 到展示名的映射只经
 *    [PluginPolicyCatalog.displayName] → [nameFor]（未映射直接炸，不让裸 id 上屏）。
 * 3. **三语同加**：漏一档 `of` 的 when 与三档实例对不上，编译或形状闸必红。
 *
 * 本表没有任何带读数的模板，故不提供 `fill`（形状闸的槽位两条按「零槽位」判）。
 *
 * @property lang 这一组对应的设置页语言
 */
data class PluginPolicyCopy(
    val lang: SettingsLanguage,

    /** 设置页 Mod 档卡片标题。 */
    val title: String,
    /** 卡片说明：为什么这些补丁被整体禁载、禁载后还有没有影响。 */
    val intro: String,
    /** 每个目录条目后面的状态词（整体禁载，不是停用某个方法）。 */
    val disabled: String,
    /** 目录 id `ai-chat` 的展示名（4.981 AI对话补丁）。 */
    val aiChatName: String,
) {
    /**
     * 目录 id → 本档展示名。**只认引擎目录里的 id**（[DisabledErbPatchCatalog]），
     * 新增条目漏补三语文案键直接炸，不让裸 id 上屏冒充文案（AGENTS.md §6）。
     */
    fun nameFor(id: String): String = when (id) {
        DisabledErbPatchCatalog.AI_CHAT.id -> aiChatName
        else -> error("PluginPolicyCatalog 条目 $id 未映射 PluginPolicyCopy 文案键（补三语键）")
    }

    companion object {

        /** 与设置页同源取语言：不许在策略侧再造一套语言判定。 */
        fun of(lang: SettingsLanguage): PluginPolicyCopy = when (lang) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }

        /** 简体中文：玩家面底稿。 */
        val ZH: PluginPolicyCopy = PluginPolicyCopy(
            lang = SettingsLanguage.ZH,
            title = "补丁兼容性",
            intro = "以下游戏自带补丁在本版已整体禁载（其脚本不会装载）——相关报错不会再出现，游戏其余部分不受影响",
            disabled = "已整体禁载",
            aiChatName = "AI对话补丁（需调用外部模型接口）",
        )

        /** English：与 [ZH] 逐条同形，措辞另写。 */
        val EN: PluginPolicyCopy = PluginPolicyCopy(
            lang = SettingsLanguage.EN,
            title = "Patch compatibility",
            intro = "These bundled patches are excluded entirely - their ERB scripts are never loaded; related errors are gone and nothing else is affected",
            disabled = "Fully excluded",
            aiChatName = "AI chat patch (external model API)",
        )

        /** 日本語：与 [ZH] 逐条同形，措辞另写。 */
        val JA: PluginPolicyCopy = PluginPolicyCopy(
            lang = SettingsLanguage.JA,
            title = "パッチ互換性",
            intro = "以下の同梱パッチは本版でスクリプトごと読み込みを無効化しています——関連エラーは表示されず、他の機能には影響しません",
            disabled = "読み込み無効",
            aiChatName = "AI会話パッチ（外部モデル API）",
        )
    }
}
