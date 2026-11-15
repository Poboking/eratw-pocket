package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右栏刷新闸的**宿主接线形状闸**（泡茶子选单「点红茶没反应」案的 app 半段）。
 *
 * ## 这条闸钉的是什么
 * 判据本身是纯函数 [com.eratw.port.platform.input.RailScanGate]（行为由 `RailScanGateTest`
 * 覆盖）；这里钉的是**壳层有没有真的用它**。少接一条下半截，右栏就退回「换页最多滞后
 * 250ms」，玩家在那段窗口里点到的是上一页的芯片 —— 提交值被新等待页消费但语义不符：
 * QOL 子选单的 `INPUT -1, 1`（`ERB/魔改内容/qol/QOL_USERCOM.ERB:276`）收到面板按钮值
 * （如 301），`INRANGE(INPUT_VALUES, 0, OPTIONNUM)`（:282）不成立 ⇒ 变体没切、弹窗直接
 * 返回 ⇒ 玩家看到「有选择窗口了但是点红茶还是没反应」。
 *
 * ## 为什么用源码形状闸
 * `MainActivity` 是 Android `Activity`，app 模块无 Robolectric（同 `MouseButtonWireGateTest`
 * / `PageTrailWireTest` 的分工口径），`refreshShell` 的调用面只能读源码钉；行为面由
 * platform 的定向单测承担。
 *
 * ## 反向证明（本笔验收口径）
 * 把 `!waitPageChanged &&` 从节流条件里撤掉 ⇒ 本文件「节流必须让位于等待页切换」一条红；
 * 恢复复绿。把 `platform` 侧的 `waitSignature` 里 `scriptLine` 项撤掉 ⇒ `RailScanGateTest`
 * 的「仅 scriptLine 变化也触发」红。
 */
class RailScanGateWireTest {

    /** 与 `MouseButtonWireGateTest` 同款：两种工作目录都能读到大仓源码。 */
    private fun appSource(name: String): String {
        for (base in listOf(File("src/main/kotlin"), File("../app/src/main/kotlin"))) {
            val f = File(base, "com/eratw/port/android/$name")
            if (f.isFile) return f.readText()
        }
        throw AssertionError("读不到 app 源文件 $name（工作目录 ${File(".").absolutePath}）")
    }

    /** 去掉注释行，避免 KDoc 里的举例串被当成接线证据。 */
    private fun codeOf(name: String): String = appSource(name).lines().filterNot { line ->
        val t = line.trim()
        t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
    }.joinToString("\n")

    /**
     * `refreshShell` 的选项扫描段必须：算等待页签名 → 判是否换页 → 记签名 →
     * **节流让位于换页**。四件缺一不可。
     */
    @Test
    fun `refreshShell 接上了等待页身份闸`() {
        val main = codeOf("MainActivity.kt")
        assertTrue(
            "refreshShell 没算等待页签名 ⇒ 右栏换页仍吃 250ms 节流（本案根因）",
            main.contains("RailScanGate.waitSignature(host?.pendingRequest(), state.awaitingAnyKey)"),
        )
        assertTrue(
            "没有把本帧签名与上一帧比较 ⇒ 闸不成立",
            main.contains("RailScanGate.mustScanNow(waitSignature, lastWaitSignature)"),
        )
        assertTrue(
            "换页后必须记住新签名，否则同一页会每帧都判成「换页」⇒ 退回每帧重扫（OOM 回归）",
            main.contains("lastWaitSignature = waitSignature"),
        )
    }

    /**
     * **核心判据**：节流条件必须让位于等待页切换。
     *
     * 允许的形态：`if (!waitPageChanged && now - optionScanAt < OPTION_SCAN_MS) return`
     * 禁止的形态：`if (now - optionScanAt < OPTION_SCAN_MS) return`（无守卫的旧写法）
     */
    @Test
    fun `节流必须让位于等待页切换`() {
        val main = codeOf("MainActivity.kt")
        assertTrue(
            "节流条件没有 !waitPageChanged 的让位 ⇒ 换页仍滞后 250ms（玩家点到上一页芯片）",
            main.contains("if (!waitPageChanged && now - optionScanAt < OPTION_SCAN_MS) return"),
        )
        assertFalse(
            "出现了无守卫的旧节流写法：换页会被它挡回去，本案根因复发",
            Regex("""if \(now - optionScanAt < OPTION_SCAN_MS\) return""").containsMatchIn(main),
        )
    }

    /**
     * 闸不许把「无等待」也当成变化源：`lastWaitSignature` 初值必须是 `IDLE_SIGNATURE`，
     * 且只在 `refreshShell` 一处赋值 —— 多处写会让判据在帧间抖动，退回每帧重扫。
     */
    @Test
    fun `签名状态只有一个写点且初值为 IDLE`() {
        val main = codeOf("MainActivity.kt")
        assertTrue(
            "lastWaitSignature 初值必须是 IDLE_SIGNATURE（首帧已在等待时才会立即重扫）",
            main.contains("private var lastWaitSignature: Long = RailScanGate.IDLE_SIGNATURE"),
        )
        val writes = Regex("""lastWaitSignature = """).findAll(main).count()
        assertTrue(
            "lastWaitSignature 只许在 refreshShell 的闸里写一次（实际 $writes 处 ⇒ 帧间抖动风险）",
            writes == 1,
        )
    }

    /**
     * 平台层判据必须留在 `platform`（纯 JVM 可测），不许被内联回 `MainActivity`
     * —— 内联后这条闸就再也没法用单测钉，只能靠真机。
     */
    @Test
    fun `判据留在 platform 层可单测`() {
        val rel = "com/eratw/port/platform/input/RailScanGate.kt"
        // gradle test 的 cwd = app/；从仓库根跑时是另一条（与 appSource 同款双路径探测）。
        for (base in listOf(File("../platform/src/main/kotlin"), File("platform/src/main/kotlin"))) {
            val f = File(base, rel)
            if (f.isFile) {
                val text = f.readText()
                assertTrue("RailScanGate 缺 waitSignature", text.contains("fun waitSignature("))
                assertTrue("RailScanGate 缺 mustScanNow", text.contains("fun mustScanNow("))
                assertTrue("RailScanGate 缺 IDLE_SIGNATURE", text.contains("const val IDLE_SIGNATURE"))
                assertTrue(
                    "签名必须吃 scriptLine（换等待语句的强信号），撤掉它只有 form/type 退化判据",
                    text.contains("request?.scriptLine"),
                )
                return
            }
        }
        throw AssertionError("找不到 platform 的 RailScanGate.kt（工作目录 ${File(".").absolutePath}）")
    }
}
