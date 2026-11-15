package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `C-WINDOW-EXPLICIT-WIRE@1` 的 **app 侧接线哨兵**（与 [GameHostMeasureWireTest] 同族：
 * `GameHost` 挂在 Android 运行时上，app 的 JVM 单测面没有 Robolectric，实例化不了，
 * 所以用源码面钉装配形状 —— 代码形状骗不了 grep）。
 *
 * 窗口宽（`WindowX`）在 Android 上走过两个极端，玩家各吃到一个回归：
 *
 * * 0.2.43（`0664085e`）**无条件**把 `effective.windowX` 灌进 View：旧盘上 `WINDOW WIDTH`
 *   缺键时读到引擎裸缺省 760，列数从 136+ 掉到 80，`DEBUG.ERB:582` 的 `MAXWIDTH()/90`
 *   除零（`C-WINDOW-NARROW-REGRESS@1`）。定量链钉在 render 侧的
 *   `ScreenLayoutWindowNarrowingRegressTest`。
 * * 0.2.44 起（`8f0f40b90`）**全断**反向输入（恒传 `(0, 0)`）：除零是断了，但
 *   「设置页调窗口宽度」也成了死功能 —— 屏上一格不动（2026-11-10 玩家反馈
 *   「调窗口宽度的功能好像又失效了」）。
 *
 * 两个方向错的是同一件事：**裸缺省不是玩家输入，显式写下的非缺省值才是**。根修口径两道闸：
 * `applyChainTo` 的 `ConfigChainLoad.touchedKeys` 记着「哪些键被三层链真正写过」——没写的盘
 * 保持 `0` = 未接线（满宽解算）；盘上「键在位、值 760」还有第二种来源（设置页写盘侧
 * `EmueraConfigWriter.mergeInto` 的「补全缺键」把裸缺省追加进旧盘），与玩家显式 760 不可区分，
 * 而 760 折算恒 ≈81 列、任何设备都落除零区 ⇒ 值等于引擎裸缺省同样不算窗口请求。
 * 两闸皆过才灌 `effective.windowX`（原版「按配置开窗」，`MainWindow.cs:687-688`）。
 * 本用例钉住这条**判值因果**，防有人再往任一个极端改回去。
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
     * KDoc 里为讲清回归会引用旧调用串，混进函数体切片会让 assertFalse 假红）。
     */
    private fun bootDoc(): String {
        val src = sourceOf("GameHost.kt")
        val fn = src.indexOf("private fun applyBootFontMetrics(")
        assertTrue("GameHost 必须保留启动期字号/行高灌注函数", fn >= 0)
        val docStart = src.lastIndexOf("/**", startIndex = fn)
        assertTrue("applyBootFontMetrics 必须有 KDoc 交代窗口宽的因果方向", docStart >= 0)
        assertTrue("KDoc 必须紧邻该函数（不要拿别处注释蒙混）", fn - docStart < 12000)
        return src.substring(docStart, fn)
    }

    @Test
    fun `窗口宽按显式非缺省值接线而不是无条件灌或全断`() {
        val body = bootFunction()
        assertTrue(
            "在位闸必须是「链上显式写过」（ConfigChainLoad.touchedKeys）：缺键盘的裸缺省读数出局 —— " +
                "无条件灌 effective.windowX 会复原 0.2.43 的 CFLAG_DEBUG 行582 除零" +
                "（旧盘 WINDOW WIDTH 缺省 760 → 80 列 → MAXWIDTH()/90 = 0）",
            body.contains("ConfigKey.WindowX in chain.touchedKeys"),
        )
        assertTrue(
            "裸缺省闸必须有：盘上「键在位、值 760」有三种不可区分来源（玩家显式 / 写盘侧补全缺键追加 / " +
                "出包根原文），而 760 折算恒 ≈81 列、任何设备都落 DEBUG.ERB /90 除零区 ⇒ 一律不算窗口请求",
            body.contains("effective.windowX != bareDefaultWidth"),
        )
        assertTrue(
            "裸缺省值必须取自 ConfigSpec 注册表（跟引擎缺省同源，不许手抄 760 常量）",
            body.contains("ConfigSpecTable.specOf(ConfigKey.WindowX)"),
        )
        assertTrue(
            "灌给 View 的宽度必须是两道闸的输出（写过且非缺省 → effective.windowX；否则 0 未接线）",
            body.contains("view.applySourceWindowMetrics(wiredWidth, 0)"),
        )
        assertFalse(
            "无条件灌 effective.windowX 会复原 0.2.43 的 CFLAG_DEBUG 行582 除零" +
                "（旧盘 WINDOW WIDTH 缺省 760 → 80 列 → MAXWIDTH()/90 = 0）",
            body.contains("applySourceWindowMetrics(effective.windowX"),
        )
        assertFalse(
            "恒传 (0, 0) 的全断形态是「调窗口宽度失效」的病灶本身（2026-11-10 玩家回归），不得回归",
            body.contains("view.applySourceWindowMetrics(0, 0)"),
        )
        assertTrue(
            "字号/行高仍须由生效配置灌注（这是本函数保留的另一职责）",
            body.contains("view.applySourceFontMetrics(effective.fontSize, effective.lineHeight)"),
        )
    }

    @Test
    fun `窗口高保持不接线`() {
        val body = bootFunction()
        // 第二实参恒 0（WindowY 不接线，纵向观感零变化 —— 用户裁定保持）。
        assertTrue(
            "WindowY 实参必须保持字面 0（不接线），窗口高的接线是另一条因果，不随本次修复顺带打开",
            body.contains("applySourceWindowMetrics(wiredWidth, 0)"),
        )
    }

    @Test
    fun `版面宽反折播种链仍保留`() {
        val src = sourceOf("GameHost.kt")
        assertTrue(
            "接线/满宽之后都要正向回填：View 的版面宽仍要按同尺折成脚本 px 交给 core",
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
        // 下一个人要改这段时，必须看得见两段回归的定量后果；这是 C-WINDOW-NARROW-REGRESS
        // 与 C-WINDOW-EXPLICIT-WIRE 两案共用的取证面。
        assertTrue("KDoc 必须点名 0.2.43 回归", doc.contains("0.2.43"))
        assertTrue("KDoc 必须点名 MAXWIDTH()/90 的除零", doc.contains("MAXWIDTH()/90"))
        assertTrue("KDoc 必须点名 DEBUG.ERB 行582", doc.contains("DEBUG.ERB") && doc.contains("582"))
        assertTrue(
            "KDoc 必须点名 2026-11-10 的「调窗口宽度失效」回归（本次修复的对象）",
            doc.contains("调窗口宽度"),
        )
        assertTrue(
            "KDoc 必须写明两道闸的判值因果：在位闸（touchedKeys）+ 裸缺省闸（写盘侧补全缺键的伪造 760）",
            doc.contains("touchedKeys") && doc.contains("mergeInto"),
        )
    }
}
