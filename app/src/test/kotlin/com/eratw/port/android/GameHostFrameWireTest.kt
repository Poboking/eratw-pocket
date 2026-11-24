package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MODERN-R12-WIRE 的 **app 侧接线**源码哨兵：绘制帧可用查询（HTML 图形段缺帧面的宽度判据
 * 数据源）必须从宿主帧源一路喂到 sink 与回读链。
 *
 * `GameHost` 挂在 Android 运行时上（app 的 JVM 单测面没有 Robolectric，实例化不了），
 * 与 [GameHostMeasureWireTest] 同族用**源码面**钉：装配只存在于 `loadAndRun` 的
 * `GameSession(...)` 实参里，代码形状骗不了 grep。
 *
 * 钉住三件事（断链任意一环，缺帧面就静默退回「宽度保持图宽」的接线前旧读数，
 * 桌面/装置测试全绿——它们本就不接；屏上缺帧行 `|图宽−文本宽|` 的列偏回归）：
 * 1. `GameHost` 把 `SpriteBitmapSource`（同时是尺寸源与帧源）包成 lambda 现读
 *    `graphicsWiring` 喂 `GameSession(frameAvailableOf = …)`——现读是因为 installGraphics
 *    晚于会话构造，未挂档答 true = 未接线旧读数；
 * 2. `GameSession` 构造参保留并原样转发给 `LineBufferConsoleSink(frameAvailableOf = …)`；
 * 3. 回读链（`ConsoleBuiltinHost.printedLinesOf`）从 **sink 自持的那一份**取参喂
 *    `consolePrintedLines`——列坐标与折行/游标同尺，换别的源就与屏上错位。
 */
class GameHostFrameWireTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/$relative"),
            // 公开分仓：core-vm 在同级引擎仓 Kotera（原单体布局作兼容回落）。
            File("../../Kotera/core-vm/src/main/kotlin/com/eratw/port/$relative"),
            File("../core-vm/src/main/kotlin/com/eratw/port/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    private fun appSource(): String = sourceOf("android/GameHost.kt")

    private fun gameSessionSource(): String = sourceOf("core/proc/GameSession.kt")

    private fun consoleBuiltinHostSource(): String = sourceOf("core/proc/ConsoleBuiltinHost.kt")

    @Test
    fun `GameHost 把宿主帧源包成 frameAvailableOf 现读喂 GameSession`() {
        val src = appSource()
        assertTrue(
            "GameSession 装配必须带 frameAvailableOf 实参（MODERN-R12-WIRE 注入口）",
            src.contains("frameAvailableOf = { name -> graphicsWiring?.frames?.frameAvailable(name) ?: true },"),
        )
    }

    @Test
    fun `GameSession 构造参保留并转发给 sink`() {
        val src = gameSessionSource()
        assertTrue(
            "GameSession 构造参必须保留（删参 = app 侧实参编译错，转发面要一起在）",
            src.contains("frameAvailableOf: ((String) -> Boolean)? = null,"),
        )
        assertTrue(
            "GameSession 构造 LineBufferConsoleSink 时必须转发 frameAvailableOf（折行/游标的缺帧面数据源）",
            src.contains("frameAvailableOf = frameAvailableOf,"),
        )
    }

    @Test
    fun `回读链从 sink 取同一份 frameAvailableOf`() {
        val src = consoleBuiltinHostSource()
        assertTrue(
            "printedLinesOf 必须喂 sink?.frameAvailableOf（回读列坐标与折行/游标同尺）",
            src.contains("frameAvailableOf = sink?.frameAvailableOf,"),
        )
    }
}
