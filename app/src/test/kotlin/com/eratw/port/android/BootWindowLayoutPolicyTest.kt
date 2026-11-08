package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `C-WINDOW-NARROW-REGRESS@1` 的 **app 侧接线哨兵**（与 [GameHostMeasureWireTest] 同族：
 * `GameHost` 挂在 Android 运行时上，app 的 JVM 单测面没有 Robolectric，实例化不了，
 * 所以用源码面钉装配形状 —— 代码形状骗不了 grep）。
 *
 * 0.2.43 玩家回归「幻想乡之主改好感页 CFLAG_DEBUG 行582 除数为0」的根因是
 * [GameHost.applyBootFontMetrics] 把 `emuera.config` 的 `WINDOW WIDTH`（旧盘上会回落到
 * `ConfigSpec` 裸缺省 760）当**版面输入**灌进 View，令列数从 136+ 掉到 80，
 * `MAXWIDTH()/90` 变 0。修法是断开这条反向输入：`WindowX` 在 Android 上是版面解算的
 * **输出**（由 [CanvasConsoleView.windowWidthScriptPx] 反折后播回 core），不是输入。
 *
 * 定量链钉在 render 侧的 `ScreenLayoutWindowNarrowingRegressTest`；本用例只负责钉住
 * 「生产装配面不会再把配置宽灌回去」，防有人照着 0664085e 的旧 KDoc 改回来。
 */
class BootWindowLayoutPolicyTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    /** 只看 `applyBootFontMetrics` 的函数体，别让别处的同名调用把断言喂绿。 */
    private fun bootFunction(): String {
        val src = sourceOf("GameHost.kt")
        val start = src.indexOf("private fun applyBootFontMetrics(")
        assertTrue("GameHost 必须保留启动期字号/行高灌注函数", start >= 0)
        val end = src.indexOf("fun start(): Boolean", startIndex = start)
        assertTrue("applyBootFontMetrics 应位于 start() 之前", end > start)
        return src.substring(start, end)
    }

    /**
     * 紧邻函数的 KDoc 段（文档注释在声明**之前**，函数体切片刻意不含它：
     * KDoc 里为讲清回归会引用旧调用串，混进函数体切片会让「不得灌回配置宽」的
     * assertFalse 假红）。
     */
    private fun bootDoc(): String {
        val src = sourceOf("GameHost.kt")
        val fn = src.indexOf("private fun applyBootFontMetrics(")
        assertTrue("GameHost 必须保留启动期字号/行高灌注函数", fn >= 0)
        val docStart = src.lastIndexOf("/**", startIndex = fn)
        assertTrue("applyBootFontMetrics 必须有 KDoc 交代窗口宽的因果方向", docStart >= 0)
        assertTrue("KDoc 必须紧邻该函数（不要拿别处注释蒙混）", fn - docStart < 8000)
        return src.substring(docStart, fn)
    }

    @Test
    fun `启动期不把 emuera_config 的窗口宽灌进 View`() {
        val body = bootFunction()
        assertTrue(
            "必须显式以 (0, 0) 走「未接线」语义：ScreenLayout 按物理内容宽满宽解算，" +
                "与 0.2.42 逐像素相同，MAXWIDTH() 才不会掉到 90 以下",
            body.contains("view.applySourceWindowMetrics(0, 0)"),
        )
        assertFalse(
            "把 effective.windowX 灌回 View 会复原 0.2.43 的 CFLAG_DEBUG 行582 除零" +
                "（旧盘 WINDOW WIDTH 缺省 760 → 80 列 → MAXWIDTH()/90 = 0）",
            body.contains("applySourceWindowMetrics(effective.windowX"),
        )
        assertTrue(
            "字号/行高仍须由生效配置灌注（这是本函数保留的唯一职责）",
            body.contains("view.applySourceFontMetrics(effective.fontSize, effective.lineHeight)"),
        )
    }

    @Test
    fun `版面宽反折播种链仍保留`() {
        val src = sourceOf("GameHost.kt")
        assertTrue(
            "断开反向输入不等于删掉正向回填：View 的版面宽仍要按同尺折成脚本 px 交给 core",
            src.contains("windowWidthScriptPx = view.windowWidthScriptPx().toLong(),"),
        )
        assertTrue(
            "重建会话的装配点同样要带播种值（否则重开一局又分叉）",
            src.contains("windowWidthScriptPx = metrics.windowWidthScriptPx,"),
        )
        assertTrue(
            "ScriptPixelMetrics 必须留着这个字段，否则两处装配点编译不过",
            src.contains("val windowWidthScriptPx: Long,"),
        )
    }

    @Test
    fun `回归成因写在代码里而不是只留一句 TODO`() {
        val doc = bootDoc()
        // 下一个人要改这段时，必须看得见定量后果；这也是 C-WINDOW-NARROW-REGRESS 的取证面。
        assertTrue("KDoc 必须点名 0.2.43 回归", doc.contains("0.2.43"))
        assertTrue("KDoc 必须点名 MAXWIDTH()/90 的除零", doc.contains("MAXWIDTH()/90"))
        assertTrue("KDoc 必须点名 DEBUG.ERB 行582", doc.contains("DEBUG.ERB") && doc.contains("582"))
    }
}
