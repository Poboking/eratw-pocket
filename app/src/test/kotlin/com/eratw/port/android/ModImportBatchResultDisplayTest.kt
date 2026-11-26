package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-183 批结果上屏投影判据：**明细封顶 + 折叠摘要 + 失败消息截断 + 展开口径**。
 *
 * 被测面是 [ModImportBatchResultUi.kt] 的文件级纯函数，Compose 里只剩「按行类型取色 / 点按切
 * 展开态」：
 *
 * 1. **[modImportBatchResultDisplayLines]**：成功明细上屏 ≤5、失败明细上屏 ≤8，超出部分合成一条
 *    [ModImportBatchResultLine.Overflow]（带未显示计数）；展开后该类全上屏、折叠行变「收起」；
 *    逐项行仍按选中序交叉保留，批级失败垫尾。
 * 2. **[truncateBatchResultMessage]**：换行压成空格、总量收口 ≤200（含尾部省略号）、截断点落在
 *    代理对中间时少取一个 char。
 * 3. **[modImportBatchResultLines]** 仍是不封顶 / 不截断的完整行序：单包既有对拍与入口卡摘要都
 *    依赖它（本文件用 30 项 × 400 字反证它没被上屏口径污染）。
 *
 * 另有两例**反证用例**专门钉「不该折叠 / 不该截断」：恰好等于上限时不许出折叠行；恰好 200 字符
 * 时不许截。改坏封顶常量或截断算式它们先红。
 */
class ModImportBatchResultDisplayTest {

    // ------------------------------------------------------------------
    // 判据 1：封顶与折叠摘要
    // ------------------------------------------------------------------

    @Test
    fun `默认上屏五条成功八条失败超额各折成一行摘要`() {
        val outcome = ModImportBatchOutcome(
            items = (1..7).map { imported("ok$it.ZIP") } + (1..10).map { failed("bad$it.ZIP") },
        )

        val lines = modImportBatchResultDisplayLines(outcome)

        assertEquals(
            "汇总 1 + 成功 5 + 成功折叠 1 + 失败 8 + 失败折叠 1",
            16,
            lines.size,
        )
        assertEquals("汇总行在首", ModImportBatchResultLine.Summary(7, 10), lines[0])
        assertTrue(
            "成功明细前五条上屏：${lines.subList(1, 6)}",
            lines.subList(1, 6).all { it is ModImportBatchResultLine.Succeeded },
        )
        assertTrue(
            "失败明细前八条上屏：${lines.subList(6, 14)}",
            lines.subList(6, 14).all { it is ModImportBatchResultLine.Failed },
        )
        assertEquals(
            "成功超出的 2 条折成折叠行（逐项行之后）",
            ModImportBatchResultLine.Overflow(ModImportBatchOverflowKind.SUCCEEDED, 2, false),
            lines[14],
        )
        assertEquals(
            "失败超出的 2 条折成折叠行（末行）",
            ModImportBatchResultLine.Overflow(ModImportBatchOverflowKind.FAILED, 2, false),
            lines[15],
        )
    }

    @Test
    fun `逐项行按选中序交叉保留不按成败重排`() {
        val outcome = ModImportBatchOutcome(
            items = listOf(
                failed("b1.ZIP"),
                imported("a1.ZIP"),
                failed("b2.ZIP"),
                imported("a2.ZIP"),
            ),
        )

        val lines = modImportBatchResultDisplayLines(outcome)

        assertEquals("汇总 + 4 逐项", 5, lines.size)
        assertEquals(ModImportBatchResultLine.Failed::class, lines[1]::class)
        assertEquals(ModImportBatchResultLine.Succeeded("a1.ZIP"), lines[2])
        assertEquals(ModImportBatchResultLine.Failed::class, lines[3]::class)
        assertEquals(ModImportBatchResultLine.Succeeded("a2.ZIP"), lines[4])
    }

    @Test
    fun `展开成功后该类全部上屏且折叠行变为收起态`() {
        val outcome = ModImportBatchOutcome(
            items = (1..7).map { imported("ok$it.ZIP") },
        )

        val lines = modImportBatchResultDisplayLines(outcome, showAllSucceeded = true)

        assertEquals("汇总 1 + 成功 7 + 收起行 1", 9, lines.size)
        assertEquals(ModImportBatchResultLine.Succeeded("ok7.ZIP"), lines[7])
        assertEquals(
            "展开态折叠行 hiddenCount 归零、expanded=true",
            ModImportBatchResultLine.Overflow(ModImportBatchOverflowKind.SUCCEEDED, 0, true),
            lines[8],
        )
    }

    @Test
    fun `展开失败不影响成功类的折叠态`() {
        val outcome = ModImportBatchOutcome(
            items = (1..7).map { imported("ok$it.ZIP") } + (1..10).map { failed("bad$it.ZIP") },
        )

        val lines = modImportBatchResultDisplayLines(outcome, showAllFailed = true)

        assertEquals("汇总 1 + 成功 5 + 成功折叠 1 + 失败 10 + 失败收起 1", 18, lines.size)
        assertEquals(
            "成功类仍折叠",
            ModImportBatchResultLine.Overflow(ModImportBatchOverflowKind.SUCCEEDED, 2, false),
            lines[16],
        )
        assertEquals(
            "失败类展开",
            ModImportBatchResultLine.Overflow(ModImportBatchOverflowKind.FAILED, 0, true),
            lines[17],
        )
    }

    @Test
    fun `两类都未超额时不出现折叠行`() {
        val outcome = ModImportBatchOutcome(
            items = (1..2).map { imported("ok$it.ZIP") } + (1..3).map { failed("bad$it.ZIP") },
        )

        val lines = modImportBatchResultDisplayLines(outcome)

        assertEquals("汇总 + 5 逐项，无折叠行", 6, lines.size)
        assertTrue(
            "无折叠行：${lines}",
            lines.none { it is ModImportBatchResultLine.Overflow },
        )
    }

    /** 反证 1：恰好等于上限（成功 5 / 失败 8）不算超额，不许出折叠行。 */
    @Test
    fun `恰好等于上限时不折叠`() {
        val outcome = ModImportBatchOutcome(
            items = (1..5).map { imported("ok$it.ZIP") } + (1..8).map { failed("bad$it.ZIP") },
        )

        val lines = modImportBatchResultDisplayLines(outcome)

        assertEquals("汇总 + 5 成功 + 8 失败，恰好等于上限", 14, lines.size)
        assertTrue(
            "等于上限不许折叠：${lines}",
            lines.none { it is ModImportBatchResultLine.Overflow },
        )
    }

    // ------------------------------------------------------------------
    // 判据 2：失败 message 单行化 + 200 字符截断
    // ------------------------------------------------------------------

    @Test
    fun `失败消息单行化并把总量收口到二百字符含省略号`() {
        val raw = "第一行\n第二行\r\n第三行\r尾行" + "x".repeat(300)

        val out = truncateBatchResultMessage(raw)

        assertFalse("不许留换行：$out", out.contains('\n') || out.contains('\r'))
        assertEquals("总量收口 200（含尾部省略号）", 200, out.length)
        assertTrue("保尾省略号：${out.takeLast(8)}", out.endsWith("…"))
        assertEquals("单行化把 CRLF / LF / CR 压成空格", "a b c", truncateBatchResultMessage("a\r\nb\rc"))
    }

    @Test
    fun `逐项失败行的 message 走同一截断口径`() {
        val raw = "坏消息" + "y".repeat(300)
        val outcome = ModImportBatchOutcome(
            items = listOf(failed("bad.ZIP", raw)),
        )

        val lines = modImportBatchResultDisplayLines(outcome)
        val failedLine = lines[1] as? ModImportBatchResultLine.Failed
            ?: throw AssertionError("第二行应是失败项：${lines[1]}")

        assertEquals(200, failedLine.message.length)
        assertTrue(failedLine.message.endsWith("…"))
        assertEquals("标题仍走既有归类口径", ModImportCopy.ZH.failureTitleCompile, failedLine.title)
    }

    /** 反证 2：恰好 200 字符不截断；短消息原样（只做单行化）。 */
    @Test
    fun `恰好二百字符不截断`() {
        val exact = "x".repeat(200)

        assertEquals("恰好等于上限原样返回", exact, truncateBatchResultMessage(exact))
        assertEquals("短消息原样", "编译坏了", truncateBatchResultMessage("编译坏了"))
    }

    @Test
    fun `截断点落在代理对中间时不劈开 emoji`() {
        val raw = "a".repeat(198) + "\uD83D\uDE00" + "z".repeat(100)

        val out = truncateBatchResultMessage(raw)

        assertTrue("保尾省略号", out.endsWith("…"))
        assertEquals("少取一个 char，总长 199", 199, out.length)
        assertFalse(
            "不许留孤立代理项：$out",
            out.any { it.isHighSurrogate() || it.isLowSurrogate() },
        )
    }

    // ------------------------------------------------------------------
    // 判据 3：批级失败垫尾 / 完整行序不受上屏口径污染
    // ------------------------------------------------------------------

    @Test
    fun `批级失败垫尾且只一条`() {
        val outcome = ModImportBatchOutcome(
            items = listOf(imported("ok.ZIP")),
            batchFailure = ModImportOutcome.Failed(ModImportFailureKind.UNEXPECTED, "循环坏了" + "z".repeat(300)),
        )

        val lines = modImportBatchResultDisplayLines(outcome, ModImportCopy.EN)
        val last = lines.last() as? ModImportBatchResultLine.BatchFailed
            ?: throw AssertionError("末行应是批级失败：${lines.last()}")

        assertEquals("批级失败只有一条", 1, lines.count { it is ModImportBatchResultLine.BatchFailed })
        assertEquals(ModImportCopy.EN.failureTitleUnexpected, last.title)
        assertEquals(200, last.message.length)
        assertTrue(last.message.endsWith("…"))
    }

    @Test
    fun `完整行序不封顶不截断单包对拍面不变`() {
        val raw = "z".repeat(400)
        val outcome = ModImportBatchOutcome(
            items = (1..30).map { failed("bad$it.ZIP", raw) },
        )

        val lines = modImportBatchResultLines(outcome)

        assertEquals("汇总 + 30 逐项，完整行序不设限", 31, lines.size)
        val failedLine = lines[1] as? ModImportBatchResultLine.Failed
            ?: throw AssertionError("第二行应是失败项：${lines[1]}")
        assertEquals("完整行序里的 message 不截断", 400, failedLine.message.length)
    }

    // ------------------------------------------------------------------
    // 判据 4：三语折叠面新键
    // ------------------------------------------------------------------

    @Test
    fun `三语折叠面新键齐备且读数随语言灌入`() {
        for (copy in listOf(ModImportCopy.ZH, ModImportCopy.EN, ModImportCopy.JA)) {
            assertTrue(copy.batchResultOverflowSucceededFormat.isNotBlank())
            assertTrue(copy.batchResultOverflowFailedFormat.isNotBlank())
            assertTrue(copy.batchResultCollapse.isNotBlank())
        }

        assertEquals(
            "ZH 成功折叠行灌读数",
            "还有 2 个成功结果未显示（点此展开）",
            ModImportCopy.ZH.fill(ModImportCopy.ZH.batchResultOverflowSucceededFormat, 2),
        )
        assertEquals(
            "EN 失败折叠行灌读数",
            "7 more failed results are hidden (tap to expand)",
            ModImportCopy.EN.fill(ModImportCopy.EN.batchResultOverflowFailedFormat, 7),
        )
        assertTrue(
            "JA 折叠行用「省略」措辞",
            ModImportCopy.JA.fill(ModImportCopy.JA.batchResultOverflowFailedFormat, 3).contains("省略"),
        )
        assertEquals("折叠行展开后的收起开关", "收起", ModImportCopy.ZH.batchResultCollapse)
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun detail(): ImportedPatchDetail = ImportedPatchDetail(
        patchFileName = "p.erbm",
        functionCount = 1,
        instructionCount = 2,
        entryFunction = "E",
        sourceFileNames = emptyList(),
        parseWarnings = emptyList(),
    )

    private fun imported(zipName: String): ModImportBatchOutcome.Item =
        ModImportBatchOutcome.Item(zipName, ModImportOutcome.Imported(detail()))

    private fun failed(zipName: String, message: String = "坏了"): ModImportBatchOutcome.Item =
        ModImportBatchOutcome.Item(zipName, ModImportOutcome.Failed(ModImportFailureKind.COMPILE, message))
}
