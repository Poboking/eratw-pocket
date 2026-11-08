package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FN-04 分段量宽的 **app 侧接线**源码哨兵（FONT-MEASURE F1）。
 *
 * `GameHost` 挂在 Android 运行时上（app 的 JVM 单测面没有 Robolectric，实例化不了），
 * 与 [ErrorReportThrottleTest] 同族用**源码面**钉：装配只存在于 `loadAndRun` 的
 * `GameSession(...)` 实参里，代码形状骗不了 grep。
 *
 * 钉住三件事（断链任意一环，真机就静默退回「半角 = FontSize/2」的内建等宽近似，
 * 桌面/装置测试全绿——它们本就不接）：
 * 1. `AndroidTextMeasure.fromContext` 在 UI 线程构造（实例锁保护的 Paint，VM 线程跨线程调用）；
 * 2. `GameSession(textMeasureRun = …)` 闭包把请求的**五个字段逐个透传**给
 *    `AndroidTextMeasure.measurePixels`（text / fontName / fontSize / styleMask / charSpace；
 *    缺一个字段那段样式在量宽里就被抹平）；
 * 3. core 侧承接点 `ConsoleBuiltinHost` 的 `textMeasureRun` 构造参转发（GameSession →
 *    ConsoleBuiltinHost）与 `measureTextRun` **显式覆写**都在（后者是 `BuiltinHost by delegate`
 *    的委托陷阱，行为面钉在 core-vm 的 `ConsoleBuiltinHostMeasureWireTest`）。
 */
class GameHostMeasureWireTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    private fun coreSourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/$relative"),
            File("../../eraKcore/core-vm/src/main/kotlin/com/eratw/port/$relative"),
            File("../../core-vm/src/main/kotlin/com/eratw/port/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `GameHost 在 UI 线程构造 AndroidTextMeasure 并注入 GameSession`() {
        val src = sourceOf("GameHost.kt")
        assertTrue(
            "量宽实例必须在 UI 线程构造（Paint 由实例锁保护，VM 线程跨线程调用）",
            src.contains("AndroidTextMeasure.fromContext(view.context)"),
        )
        assertTrue(
            "GameSession 装配必须带 textMeasureRun 实参（FN-04 注入口）",
            src.contains("textMeasureRun = { req ->"),
        )
        // 五字段透传：缺一个字段，那段样式就在量宽里被抹平（粗斜体 / face / 字距各占一位）。
        assertTrue(
            "闭包必须把 TextMeasureRequest 的五个字段逐个透传给 measurePixels",
            src.contains("textMeasure.measurePixels(req.text, req.fontName, req.fontSize, req.styleMask, req.charSpace)"),
        )
    }

    @Test
    fun `GameSession 把 textMeasureRun 转发给 ConsoleBuiltinHost`() {
        val src = coreSourceOf("core/proc/GameSession.kt")
        assertTrue(
            "GameSession 构造 ConsoleBuiltinHost 时必须转发 textMeasureRun（GameSession.kt 的宿主装配段）",
            src.contains("textMeasureRun = textMeasureRun,"),
        )
        assertTrue(
            "GameSession 构造参必须保留（删参 = app 侧实参编译错，但闭包转发面要一起在）",
            src.contains("textMeasureRun: ((TextMeasureRequest) -> Long?)? = null,"),
        )
    }

    @Test
    fun `ConsoleBuiltinHost 保留 measureTextRun 的显式覆写`() {
        val src = coreSourceOf("core/proc/ConsoleBuiltinHost.kt")
        assertTrue(
            "构造参必须在（生产注入的落点）",
            src.contains("textMeasureRun: ((TextMeasureRequest) -> Long?)? = null,"),
        )
        assertTrue(
            "BuiltinHost by delegate 的带默认实现成员必须显式覆写，否则注入值被委托缺省 null 遮蔽（委托陷阱）",
            src.contains("override fun measureTextRun(request: TextMeasureRequest): Long?"),
        )
    }
}
