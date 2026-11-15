package com.eratw.port.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `UiPromptSlot` 并发契约的源码面哨兵（照 `BusyIndicatorWiringTest` 口径 —— 私有类依赖
 * Android `AlertDialog`/`CanvasConsoleView`，JVM 单测跑不了行为面；2026-11-13 外部审计
 * 采纳的封窗改造随波钉住）。
 *
 * * **取消 vs 已排队的展示任务**：[UiPromptSlot.show] 经 `view.post` 排队的 Runnable
 *   必须先核请求令牌（`pending !== req` ⇒ 按未送达收口），否则 `onPause` 取消（≠ 关闭）
 *   之后回前台会弹出已答过兜底值的僵尸框。
 * * **恰好完成一次**：四路完成（玩家按钮 / cancel 兜底 / 展示失败 / 活动缺失）必须竞争
 *   同一个 `AtomicBoolean.compareAndSet` 完成态，不得各自裸调 rawAnswer。
 */
class UiPromptSlotContractTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
            File("app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        // 仓里可能是 CRLF 工作树：多行锚点必须先归一，否则「明明在」也 0 命中（2026-11-08 实测）。
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `取消后已排队的展示任务必须按令牌失效`() {
        val src = sourceOf("UiPromptCoordinator.kt")
        // 展示 Runnable 的第一件事就是核令牌（必须排在查宿主 closed 之前）：
        assertTrue(
            "show 的排队 Runnable 必须先核请求令牌（pending !== req ⇒ 按未送达收口，不弹框）",
            src.contains("if (synchronized(lock) { pending !== req })"),
        )
        assertTrue(
            "令牌失配路径必须按未送达收口并走兜底完成（onNotDelivered + deliver(fallback)）",
            src.contains("req.deliver(req.fallback)"),
        )
        assertTrue(
            "不得再走裸 pendingAnswer 单槽（令牌化改造的回归闸）",
            !src.contains("pendingAnswer"),
        )
    }

    @Test
    fun `四路完成必须竞争同一个恰好一次状态`() {
        val src = sourceOf("UiPromptCoordinator.kt")
        assertTrue(
            "完成闸必须是 AtomicBoolean.compareAndSet（只放行第一路）",
            src.contains("private val completed = AtomicBoolean(false)") &&
                src.contains("completed.compareAndSet(false, true)"),
        )
        assertTrue(
            "玩家按钮（buildDialog 回调）与 cancel 兜底都必须经 deliver，不得裸调 rawAnswer",
            src.contains("req.deliver(a)") && src.contains("req.deliver(fallbackAnswer)"),
        )
    }
}
