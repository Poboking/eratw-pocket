package com.eratw.port.android.dbg

/**
 * DBG3 剧本文件解析（纯 Kotlin，零 android import）。
 *
 * ## 剧本格式（钉死；与 hawkeye `play` 子命令的用法说明一致）
 *
 * UTF-8 文本，一行一步；空行与 `#` 开头的行跳过；每行 = `提交文本 ;; 期望子串`
 * （`;;` 后可空；两侧 trim；`;;` 只按**第一次出现**切分，期望子串里允许再出现）。
 *
 * 特殊提交 token（写在 `;;` 之前的位置）：
 * - `$any` —— 提交空文本（放行任意键/回车类等待）；
 * - `$skip` —— 以 mesSkip=true 提交空文本（跳过消息的回车）。
 *
 * 期望子串的比对范围 = 该步提交后**新增的控制台行文本**（见 PlayChainTest 的驱动段）。
 */
object PlayScript {

    /** 一步：要提交什么 + 提交后期望在新行里看到什么。 */
    class Step(
        /** 提交文本（`$any`/`$skip` 已折成空串）。 */
        val input: String,
        /** mesSkip 位（`$skip` = true）。 */
        val skip: Boolean,
        /** 期望子串；null = 不检查。 */
        val expect: String?,
        /** 行号（1 起，诊断用）。 */
        val sourceLine: Int,
    )

    /** 解析；任何行都不抛（畸形行折成带原样的注释性 Step 由调用方过滤？——不：直接跳过并计数）。 */
    class Parsed(
        val steps: List<Step>,
        /** 被跳过的行数（空行/注释/畸形）。 */
        val skippedLines: Int,
    )

    fun parse(text: String): Parsed {
        val steps = ArrayList<Step>()
        var skipped = 0
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                skipped++
                return@forEachIndexed
            }
            val sep = line.indexOf(";;")
            val inputPart = (if (sep >= 0) line.substring(0, sep) else line).trim()
            val expectPart = if (sep >= 0) line.substring(sep + 2).trim() else ""
            if (inputPart.isEmpty()) {
                skipped++
                return@forEachIndexed
            }
            val skip = inputPart == "\$skip"
            val input = when {
                skip || inputPart == "\$any" -> ""
                else -> inputPart
            }
            steps.add(
                Step(
                    input = input,
                    skip = skip,
                    expect = expectPart.takeIf { it.isNotEmpty() },
                    sourceLine = i + 1,
                ),
            )
        }
        return Parsed(steps, skipped)
    }
}
