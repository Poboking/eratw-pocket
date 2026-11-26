package com.eratw.port.android

/**
 * 游戏内 mod 交互浮层的三语文案（RT2 P0-LAST：UiApi 贡献项真实上屏）。
 *
 * 为什么单独一张域表：壳层文案表 `SettingsStrings` 属另一 lane 的写范围（禁改），而 AGENTS §6
 * 又禁止玩家可见文案内联字面量 —— 所以交互浮层按既有域表范式自立一表：三档 ZH/EN/JA 同加、
 * 协同 `ModUiCopyShapeTest` 的形状闸（条目数 / 非空 / 槽位对齐 / 语言自证）。
 *
 * MODDX4-G8 追加：设置页「已安装的 Mod」卡每包日志回看入口的玩家可见文案也进本表
 * （`logViewer*` 四条，域归属同为「mod 诊断面」，且 `SettingsStrings` 同样禁改）。
 *
 * 取语言唯一路：[of]（调用侧从 `UiPrefs.settingsLanguage(context)` 取）；本表不读偏好、
 * 不自判 Locale。
 *
 * 作者注册项的标签键（`UiApi.registerInteraction(labelKey, ...)`）是所在域三语文案表键，
 * **由作者侧解析**；本浮层只原样展示 PackageId / 标签键两个技术标识，不在这里二次翻译。
 */
data class ModUiCopy(
    val lang: SettingsLanguage,
    /** 浮层入口按钮。 */
    val entryLabel: String,
    /** 展开面板标题。 */
    val panelTitle: String,
    /** 面板说明：这些项是作者注册的。 */
    val panelHint: String,
    /** 无贡献项时的常驻提示（当前入口本身不渲染，留给面板/诊断文案复用）。 */
    val emptyLabel: String,
    /** 收起面板按钮。 */
    val closeLabel: String,
    /** 已执行回显：{0} = PackageId / 标签键。 */
    val executedLabelFormat: String,
    /** 执行失败回显：{0} = PackageId / 标签键。 */
    val failedLabelFormat: String,
    /** 未执行回显（撤销/未命中）：{0} = PackageId / 标签键。 */
    val rejectedLabelFormat: String,
    /** 设置页 Mod 卡的每包日志回看入口按钮（MODDX4-G8）。 */
    val logViewerLabel: String,
    /** 无会话 / 无日志时的诚实空态（fail-closed：不造假数据）。 */
    val logViewerEmpty: String,
    /** 把当前快照写进剪贴板的按钮。 */
    val logViewerCopy: String,
    /** 复制完成的回执 Toast。 */
    val logViewerCopied: String,
) {

    /**
     * 句式占位符 `{0}`：单趟替换（参数里带 `{n}` 不二次展开）。
     *
     * 花括号走字符类而不是反斜杠转义 —— Android 的 ICU 正则直接抛 PatternSyntaxException，
     * 与 `ModImportCopy.fill` / `SettingsStrings` 同一写法。
     */
    fun fill(template: String, vararg args: Any): String =
        SLOT.replace(template) { match ->
            val arg = match.groupValues[1].toIntOrNull()?.let { args.getOrNull(it) }
            arg?.toString() ?: match.value
        }

    companion object {
        private val SLOT = Regex("[{](\\d+)[}]")

        /** 与设置页同源取语言（唯一路，不在本文件自判 Locale）。 */
        fun of(lang: SettingsLanguage): ModUiCopy = when (lang) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }

        val ZH: ModUiCopy = ModUiCopy(
            lang = SettingsLanguage.ZH,
            entryLabel = "Mod 交互",
            panelTitle = "Mod 交互项",
            panelHint = "作者注册的交互项（按包与标签键排序）",
            emptyLabel = "当前没有 Mod 注册交互项",
            closeLabel = "收起",
            executedLabelFormat = "已执行：{0}",
            failedLabelFormat = "执行失败：{0}",
            rejectedLabelFormat = "未执行（该项已撤销或未命中）：{0}",
            logViewerLabel = "最近日志",
            logViewerEmpty = "当前会话没有这个 Mod 的日志",
            logViewerCopy = "复制日志",
            logViewerCopied = "日志已复制",
        )

        val EN: ModUiCopy = ModUiCopy(
            lang = SettingsLanguage.EN,
            entryLabel = "Mod actions",
            panelTitle = "Mod interaction items",
            panelHint = "Items registered by mods (sorted by package and label key)",
            emptyLabel = "No mod interaction items registered",
            closeLabel = "Collapse",
            executedLabelFormat = "Executed: {0}",
            failedLabelFormat = "Failed: {0}",
            rejectedLabelFormat = "Not executed (item withdrawn or missing): {0}",
            logViewerLabel = "Recent logs",
            logViewerEmpty = "No logs for this mod in the current session",
            logViewerCopy = "Copy logs",
            logViewerCopied = "Logs copied",
        )

        val JA: ModUiCopy = ModUiCopy(
            lang = SettingsLanguage.JA,
            entryLabel = "Mod 操作",
            panelTitle = "Mod インタラクション項目",
            panelHint = "作者が登録した項目（パッケージとラベルキー順）",
            emptyLabel = "登録された項目はありません",
            closeLabel = "閉じる",
            executedLabelFormat = "実行済み：{0}",
            failedLabelFormat = "実行失敗：{0}",
            rejectedLabelFormat = "未実行（項目が取り消されたか見つかりません）：{0}",
            logViewerLabel = "最近のログ",
            logViewerEmpty = "現在のセッションにこのModのログはありません",
            logViewerCopy = "ログをコピー",
            logViewerCopied = "ログをコピーしました",
        )
    }
}
