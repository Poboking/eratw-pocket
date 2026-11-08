package com.eratw.port.android

import com.eratw.port.core.diag.ErrorReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报告内存现场（[MemoryForensics]）与最小退化报告（[ErrorReportCollector.minimalReportText]）
 * 的定向单测（2026-10-31 玩家 OOM 报告的两条加固项）。
 *
 * 纯 JVM：两段都是纯格式化，读数以标量/异常对象进来，不需要 Android 框架。
 */
class ErrorReportMemoryForensicsTest {

    // ------------------------------------------------------------------
    // MemoryForensics：四问一次答完
    // ------------------------------------------------------------------

    @Test
    fun `内存现场三行读数加图形侧读数`() {
        val lines = MemoryForensics.lines(
            javaUsedMiB = 412,
            javaMaxMiB = 512,
            nativeAllocatedMiB = 87,
            dalvikPssMiB = 430,
            nativePssMiB = 90,
            totalPssMiB = 560,
            graphicsLines = listOf(
                "图形池：1234 项 / 2000 张已创建 / 96 MiB 像素驻留 / 位图缓存 48 MiB",
                "图像簿记：位图位 3200 / 图像表 3100 项",
            ),
        )
        assertEquals(5, lines.size)
        assertEquals("本轮 Java 堆：已用 412 MiB / 上限 512 MiB", lines[0])
        assertEquals("本轮 native 堆：已分配 87 MiB", lines[1])
        assertEquals("本轮 PSS 分项：dalvik 430 MiB / native 90 MiB / 总 560 MiB", lines[2])
        assertTrue(lines[3].startsWith("图形池："))
        assertTrue(lines[4].startsWith("图像簿记："))
    }

    @Test
    fun `图形侧读不到时整段省略而不是占位`() {
        val lines = MemoryForensics.lines(
            javaUsedMiB = 10, javaMaxMiB = 512,
            nativeAllocatedMiB = 1, dalvikPssMiB = 8, nativePssMiB = 2, totalPssMiB = 12,
            graphicsLines = emptyList(),
        )
        assertEquals(3, lines.size)
        assertTrue(lines.none { it.contains("图形池") })
        assertTrue(lines.none { it.contains("图像簿记") })
    }

    // ------------------------------------------------------------------
    // minimalReportText：完整报告写不出时的最后一张网
    // ------------------------------------------------------------------

    @Test
    fun `最小报告带端点验真标记与定位三行`() {
        val cause = IllegalStateException("堆见底")
        val error = RuntimeException("包装层", cause)
        val text = ErrorReportCollector.minimalReportText("应用崩溃", "2026-11-01 08:00:00", error)
        assertTrue("首行必须是 worker.js REPORT_MARKER，否则端点拒收", text.startsWith("eraTW 安卓移植版 错误报告\n"))
        assertTrue(text.contains("报告类型：应用崩溃"))
        assertTrue(text.contains("报告时间：2026-11-01 08:00:00"))
        assertTrue(text.contains("类型：java.lang.RuntimeException"))
        assertTrue("因果链最深层是能定位的那句话", text.contains("根因：java.lang.IllegalStateException：堆见底"))
        assertTrue(text.contains("报告结束"))
    }

    @Test
    fun `最小报告对无异常对象与空消息不空行`() {
        val text = ErrorReportCollector.minimalReportText("状态机终局", "2026-11-01 08:00:00", null)
        assertTrue(text.contains("类型：（无异常对象）"))
        assertTrue("error 为 null 时不渲染信息行", text.lineSequence().none { it.startsWith("信息：") })
        assertTrue("无因果链时不渲染根因行", text.lineSequence().none { it.startsWith("根因：") })
    }

    @Test
    fun `超长消息截断到 core 的单行上限`() {
        val long = "x".repeat(ErrorReport.LINE_MAX_CHARS + 100)
        val text = ErrorReportCollector.minimalReportText("应用崩溃", "t", RuntimeException(long))
        val line = text.lineSequence().first { it.startsWith("信息：") }
        val prefix = "信息：".length
        assertTrue(
            "超长行必须截断，原样进报告会淹没关键信息",
            line.length <= prefix + ErrorReport.LINE_MAX_CHARS + "…（超长截断）".length,
        )
        assertTrue(line.endsWith("…（超长截断）"))
    }

    /**
     * 退化报告的定位五行的缺省口径：调用方**取不到就别传**，本函数不替它编。
     *
     * 三件事钉死：
     * - 五格全传齐时逐行都在（版本行/构建行的标签与 core 全份报告逐字一致 ⇒
     *   `devtools/triage_reports.py` 的 `^app 版本：` / `^构建标识：` 两条正则能在退化报告上
     *   取到数，收件箱那三份退化报告从此不再进 `ver=?` 桶）；
     * - 逐格缺省 ⇒ 那一行**不存在**（不是「未知」占位）：这张网要尽可能小，
     *   而「读数不可用」在退化报告里和「没这行」对定位的差别为零；
     * - 行的先后不变：身份四行在「类型：」之前（与全份报告的「先身份、后错误」同序），
     *   既有的类型/信息/根因/脚本位置四行一个都不挪。
     */
    @Test
    fun `退化报告补身份与水位行且逐格可缺省`() {
        val error = OutOfMemoryError(
            "Failed to allocate a 224 byte allocation with 1504 free bytes and 1504B until OOM, " +
                "target footprint 536870912, growth limit 536870912",
        )
        val full = ErrorReportCollector.minimalReportText(
            kind = "应用崩溃",
            timestampText = "2026-11-03 13:05:12",
            error = error,
            appVersionText = "0.2.40 (versionCode 16)",
            buildStamp = "65e0930-dirty",
            memoryTierLine = "内存档位=放开（API 34、Java 堆上限 512 MiB） 像素预算=128MiB " +
                "工作表缓存=384MiB GC阈值=65% 预检系数=70%",
            heapLine = "退化时堆读数：已用 509MiB / 上限 512MiB / native 474MiB",
            trailTailLine = "t=18068ms used=131MiB max=512MiB native=27MiB pause sys=5005MiB lmk=216MiB",
        )
        assertTrue(full, full.contains("app 版本：0.2.40 (versionCode 16)"))
        assertTrue(full, full.contains("构建标识：65e0930-dirty"))
        assertTrue(full, full.contains("内存档位=放开（API 34、Java 堆上限 512 MiB）"))
        assertTrue(full, full.contains("退化时堆读数：已用 509MiB / 上限 512MiB / native 474MiB"))
        assertTrue(full, full.contains("轨迹末行：t=18068ms used=131MiB max=512MiB"))
        assertTrue(full, full.contains("类型：java.lang.OutOfMemoryError"))
        assertTrue(
            "首行仍是端点验真标记（新增行不许挤掉它）",
            full.startsWith("eraTW 安卓移植版 错误报告\n"),
        )
        fun indexOfStart(prefix: String) = full.lines().indexOfFirst { it.startsWith(prefix) }
        assertTrue(
            "身份四行排在错误三行之前（与全份报告同序）",
            indexOfStart("app 版本：") < indexOfStart("类型："),
        )
        assertTrue(
            "轨迹末行排在堆读数之后、类型之前",
            indexOfStart("退化时堆读数：") < indexOfStart("轨迹末行：") &&
                indexOfStart("轨迹末行：") < indexOfStart("类型："),
        )
        assertEquals("报告结束", full.lines().last { it.isNotBlank() })

        val bare = ErrorReportCollector.minimalReportText("应用崩溃", "2026-11-03 13:05:12", error)
        listOf("app 版本：", "构建标识：", "内存档位", "退化时堆读数：", "轨迹末行：").forEach {
            assertFalse("取不到就整行缺省，不写占位：$it", bare.lineSequence().any { l -> l.startsWith(it) })
        }
        assertTrue("缺省行不影响既有四行", bare.contains("类型：java.lang.OutOfMemoryError"))
    }
}
