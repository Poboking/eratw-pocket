package com.eratw.port.android

import com.eratw.port.core.host.BusyIndicator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长页忙碌提示（PRAY-BUSY）的**接线哨兵**。
 *
 * `GameHost` / `MainActivity` / `SettingsActivity` 挂在 Android 运行时上，app 的 JVM 单测面
 * 实例化不了，所以照 `QolGateWireTest` 的源码面口径钉形状：删掉任一条读点 / 推点 / 清点 /
 * 传值，本类当场红。
 *
 * 三条硬约束里这里钉「缺省零成本」与「单一清除出口」：
 * - 开关关 ⇒ `RunTrail.shouldShowBusyIndicator` 恒假，检查点一次 sink 调用都不发；
 * - 清点全仓只有一个页侧 `setBusyIndicator(null)`（在 `clearBusyIndicator` 里，由
 *   `onTurnReady` 与轻量 turn 边界共用）+ `close()` 的幂等清场一处，共两处（漏清会挂一条不消失的横带）。
 */
class BusyIndicatorWiringTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `阈值只有一把尺`() {
        assertEquals(BusyIndicator.MIN_ELAPSED_MS, RunTrail.BUSY_INDICATOR_MIN_MS)
        assertFalse(RunTrail.shouldShowBusyIndicator(999L, enabled = true))
        assertTrue(RunTrail.shouldShowBusyIndicator(1_000L, enabled = true))
        assertTrue(RunTrail.shouldShowBusyIndicator(130_000L, enabled = true))
        assertFalse("开关关 ⇒ 缺省零成本，一次都不推", RunTrail.shouldShowBusyIndicator(130_000L, enabled = false))
    }

    @Test
    fun `页中检查点推指示器 交付点单一出口清空`() {
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "推点必须走同一把尺的判据",
            host.contains("RunTrail.shouldShowBusyIndicator(pageMs, busyIndicator)"),
        )
        assertTrue(
            "推点必须写进真 sink（经 session.console）",
            host.contains("session?.console?.setBusyIndicator(BusyIndicator(pageMs))"),
        )
        // 清除点**恰好两处**，且各守一条不可替代的路径（多一处就是有第二条真相）：
        // ① `clearBusyIndicator` —— 唯一的页侧出口，`onTurnReady`（页交付）与
        //    `onWaitBoundaryReached`（每个 turn 边界）共用。后者是 4.981 动态地图循环
        //    超时自动推进时唯一还会响的清除点（没有提交起点就不触发 onTurnReady）；
        // ② `close()` —— 幂等清场入口（onDestroy / 失败收口 / 换局）。终局异常与玩家中途
        //    退出这两条路走不到页交付，少了 ② 指示器就会留到失败屏/下一局。
        // 页中**推**点（`midPageCheckpoint`）绝不允许出现清除调用 —— 否则长页里指示器每
        // 250 ms 被自己摘一次，玩家永远看不到。
        assertEquals(
            "清除点只允许单一页侧出口与 close() 两处",
            2,
            Regex("setBusyIndicator\\(null\\)").findAll(host).count(),
        )
        assertTrue(
            "页侧清除点钉在共用出口的函数体里",
            host.contains("fresh.console.setBusyIndicator(null)"),
        )
        val pushBody = host.substringAfter("private fun midPageCheckpoint()")
            .substringBefore("private fun ")
        assertFalse(
            "页中推点不得自带清除（否则指示器每 250 ms 被自己摘掉）",
            pushBody.contains("setBusyIndicator(null)"),
        )
    }

    @Test
    fun `清场入口也要摘指示器（终局异常与中途退出走不到交付点）`() {
        val host = sourceOf("GameHost.kt")
        // `close()` 是幂等清场唯一入口；清除出口正常只在 onTurnReady，那两条路都走不到它。
        val closeBody = host.substringAfter("fun close() {").substringBefore("fun shutdownForExit")
        assertTrue(
            "close() 必须摘掉忙碌指示器，否则终局异常后横带会留到失败屏/下一局",
            closeBody.contains("setBusyIndicator(null)"),
        )
        assertTrue(
            "清场里每一步都不许抛（close() 的既有契约）",
            closeBody.contains("runCatching { session?.console?.setBusyIndicator(null) }"),
        )
    }

    @Test
    fun `开关在 UI 线程读好交给宿主 宿主不许摸偏好表`() {
        val activity = sourceOf("MainActivity.kt")
        assertTrue(
            "GameHost 构造点必须显式传 busyIndicator（值来自 UI 线程这一拍的 UiPrefs 读数）",
            activity.contains("busyIndicator = UiPrefs.busyIndicator(this),"),
        )
        val host = sourceOf("GameHost.kt")
        assertFalse(
            "VM 线程装载链不许直接读 SharedPreferences（照 liveReflow/failureCopy 先例）",
            host.contains("UiPrefs.busyIndicator("),
        )
        assertTrue("宿主必须吃构造参数", host.contains("private val busyIndicator: Boolean = true"))
    }

    @Test
    fun `设置页有开关条目且写点随改随存`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue("状态初值必须读偏好", settings.contains("UiPrefs.busyIndicator(context)"))
        assertTrue("开关条目必须上屏", settings.contains("title = text.busyIndicatorTitle"))
        assertTrue("写点必须随改随存", settings.contains("UiPrefs.setBusyIndicator(context, enabled)"))
    }

    @Test
    fun `文案三语齐备且互不重复`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        all.forEach { t ->
            assertTrue("${t.lang} 的标题为空", t.busyIndicatorTitle.isNotEmpty())
            assertTrue("${t.lang} 的提示为空", t.busyIndicatorHint.isNotEmpty())
        }
        assertEquals("标题三语重复", 3, all.map { it.busyIndicatorTitle }.distinct().size)
        assertEquals("提示三语重复", 3, all.map { it.busyIndicatorHint }.distinct().size)
        assertTrue("中文提示要点名「少女祈祷中」", all[0].busyIndicatorHint.contains("少女祈祷中"))
        assertTrue("中文提示要点明 1 秒阈值", all[0].busyIndicatorHint.contains("1 秒"))
    }

    /**
     * 魔法之森「切图一直加载」的接线闸（2026-11-03 真机 longturn 94173 ms → 173380 ms）。
     *
     * 反向证明：把 `onTurnBoundary` 装配行删了、把边界处理函数换成 `onTurnBoundaryReached`
     * （每帧跑存档/采样/回收）、或把页中检查点改回读 `turnStartNs`，本类当场红 ⇒
     * 横带重新常驻、长页读数重新一路涨到分钟级。
     */
    @Test
    fun `超时帧 turn 边界复位页尺并清横带 且不跑页级重动作`() {
        val host = sourceOf("GameHost.kt")
        // 必须是**生效代码行**：用 contains 会被注释掉的装配行骗过（反向证明实测踩过）。
        val wire = host.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("fresh.inputSink.onTurnBoundary =") }
        assertEquals(
            "轻量 turn 边界必须接到 sink（只挂 onTurnReady 对超时连续帧无效）",
            "fresh.inputSink.onTurnBoundary = { onWaitBoundaryReached(fresh) }",
            wire,
        )
        val boundary = host.substringAfter("private fun onWaitBoundaryReached(")
            .substringBefore("private fun ")
        assertTrue("边界必须按 turn 归零页尺", boundary.contains("pageStartNs.set(0L)"))
        assertTrue("边界必须撤掉长页横带", boundary.contains("clearBusyIndicator(fresh)"))
        for (heavy in listOf(
            "drainSaveCheckpoint",
            "EventJournal.record",
            "RunWatch.sampleTurn",
            "modRuntimeSession?.onTurnBoundary()",
            "memory.releaseImagePixelsIfHeapHigh",
        )) {
            assertFalse("33 ms 一帧不得跑页级重动作：$heavy", boundary.contains(heavy))
        }
        val mid = host.substringAfter("private fun midPageCheckpoint()").substringBefore("private fun ")
        assertTrue("页中检查点必须读按 turn 收口的页尺", mid.contains("pageStartNs.get()"))
        assertFalse("页中检查点不得再读 step 延迟尺（整段循环不复位）", mid.contains("turnStartNs.get()"))
        assertTrue("玩家提交必须开启新一页计时", host.contains("pageStartNs.set(System.nanoTime())"))
        assertTrue(
            "清除仍只走单一页侧出口",
            host.contains("private fun clearBusyIndicator(fresh: GameSession)"),
        )
    }
}
