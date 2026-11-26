package com.eratw.port.android

import android.content.SharedPreferences
import com.eratw.port.render.canvas.FrameSample
import com.eratw.port.render.canvas.FrameTrace
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 逐帧诊断的**玩家侧取证通道**闸（[FrameTraceChannel] + [UiPrefs] 开关）。
 *
 * 为什么要有这一笔：判词 9 之前 release 包里 `DevToolsHost.startOnce` 的 `if (!DEBUGGABLE) return`
 * 让 `frames on` 命令根本敲不到，玩家能用的只剩「设置页开关 + 分享诊断」这一条路；判词 9 之后玩家
 * 还能在设置页 Mod 档显式授权起**只读**通道（`frames` 属放开面），但这条设置页路仍是缺省路径；
 * 这条路一旦哪一环断了（开关不落盘、报告不带帧、关掉不清空），真机现场就永远交不出来，
 * 而 JVM 侧的所有读数都证明不了那件事。所以逐个钉：
 *
 * 1. 开关缺省关、键名固定、随 `resetAll` 回缺省；
 * 2. 关态 ⇒ 环空、报告写「未开启」且没有判定行；
 * 3. 开态 ⇒ 环收帧、报告含逐帧表与自动判定行；
 * 4. 开着但还没帧 ⇒ 报告写「还没有帧」（与「未开启」分开）；
 * 5. 关掉 ⇒ 环清空；
 * 6. 表头文案里的「240 帧」与 [FrameTrace.DEFAULT_CAPACITY] 同源。
 *
 * 「关态 `onDraw` 不写环」的 JVM 等价证明：`onDraw` 只读 View 上的接收端字段，而那个字段
 * 唯一的赋值来源是 [FrameTraceChannel.attach] / [FrameTraceChannel.detach]。关态同步过
 * 一次 detach 之后接收端恒为 null ⇒ 环不可能长 —— 这就是关态那条链的全部判据，
 * 真机像素面由逐帧表本身在报告里交出来。
 */
class FrameTraceChannelTest {

    /** 内存版 SharedPreferences（口径与 `BusyIndicatorPrefTest` 的同名桩逐字相同）。 */
    private class FakeSharedPreferences : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map

        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val v = map[key]
            return if (v is MutableSet<*>) {
                v.filterIsInstance<String>().toMutableSet()
            } else {
                defValues
            }
        }

        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = HashMap<String?, Any?>()
            private val removals = HashSet<String?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                pending[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                removals.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                pending.clear()
                removals.clear()
                map.clear()
                return this
            }

            override fun commit(): Boolean {
                flush()
                return true
            }

            override fun apply() = flush()

            private fun flush() {
                for (k in removals) if (k != null) map.remove(k)
                for ((k, v) in pending) if (k != null) map[k] = v
                pending.clear()
                removals.clear()
            }
        }
    }

    @After
    fun cleanup() {
        // 进程级单例：每个用例收尾都摘掉，免得把现场带进下一条用例。
        FrameTraceChannel.detach()
    }

    /** 造一帧（判据只看版面量，内容给固定值即可）。 */
    private fun sample(no: Long, lineHeight: Float = 40f): FrameSample = FrameSample(
        frameNo = no,
        revision = no,
        rows = 24,
        visibleRowBase = 100,
        hitRowBase = 100,
        offsetFromEnd = 0,
        lineHeight = lineHeight,
        viewHeight = 960,
        rowsVisible = 24,
        rebaked = false,
        awaitingAnyKey = false,
        heightLines = 24,
        fontPx = 32f,
        rowCount = 24,
        planHeight = 960f,
        artAdvance = lineHeight,
        artRows = 0,
    )

    @Test
    fun prefDefaultsOffAndRoundTrips() {
        val sp = FakeSharedPreferences()
        assertFalse("逐帧诊断缺省必须关（关态零开销是这条通道的前提）", UiPrefs.readFrameTrace(sp))

        UiPrefs.writeFrameTrace(sp, true)
        assertEquals("键名逐字是 frame_trace", true, sp.map["frame_trace"])
        assertTrue("开关必须落盘 —— 不然玩家重开游戏又得再点一次", UiPrefs.readFrameTrace(sp))

        UiPrefs.writeFrameTrace(sp, false)
        assertFalse(UiPrefs.readFrameTrace(sp))
    }

    @Test
    fun resetAllBringsItBackToOff() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeFrameTrace(sp, true)
        UiPrefs.resetAll(sp)
        assertFalse("「恢复默认设置」必须把开关带回关", UiPrefs.readFrameTrace(sp))
    }

    @Test
    fun disabledMeansNoFramesAndReportSaysOff() {
        FrameTraceChannel.detach()
        assertEquals("关态环必须是空的", 0, FrameTraceChannel.size())
        val lines = FrameTraceChannel.reportLines(enabled = false)
        assertTrue("报告必须有一个独立小节表头：$lines", lines.any { it == FrameTraceChannel.SECTION_TITLE })
        assertTrue("关态报告必须写「未开启逐帧诊断」：$lines", lines.any { it == FrameTraceChannel.EMPTY_LINE })
        assertFalse("没有样本就不该有判定行：$lines", lines.any { it.contains("verdict:") })
    }

    @Test
    fun enabledButEmptySaysNoFrames() {
        FrameTraceChannel.detach()
        val lines = FrameTraceChannel.reportLines(enabled = true)
        assertTrue("开着但没帧要单独说：$lines", lines.any { it == FrameTraceChannel.NO_FRAMES_LINE })
        assertFalse("不许混成「未开启」：$lines", lines.any { it == FrameTraceChannel.EMPTY_LINE })
    }

    @Test
    fun recordedFramesAppearInReportWithVerdict() {
        FrameTraceChannel.detach()
        FrameTraceChannel.trace.record(sample(1, lineHeight = 40f))
        FrameTraceChannel.trace.record(sample(2, lineHeight = 41f))
        assertEquals(2, FrameTraceChannel.size())

        val lines = FrameTraceChannel.reportLines(enabled = true)
        assertTrue("报告正文必须是逐帧表（含表头行）：$lines", lines.any { it.contains("frameNo") })
        assertTrue("报告末尾必须带自动判定行：$lines", lines.any { it.contains("verdict:") })
        assertTrue(
            "相邻帧行高 40→41 必须判成「版面量在跳」，口径不许在报告这一层被改写：$lines",
            lines.any { it.contains("版面量在跳") },
        )
        assertFalse("有帧时不该再写「还没有帧」：$lines", lines.any { it == FrameTraceChannel.NO_FRAMES_LINE })
    }

    @Test
    fun reportSectionStaysInsideTheSharedWindow() {
        FrameTraceChannel.detach()
        repeat(FrameTrace.DEFAULT_CAPACITY) { i -> FrameTraceChannel.trace.record(sample(i.toLong() + 1)) }
        val lines = FrameTraceChannel.reportLines(enabled = true)
        // 错误报告的「补充现场」是共享尾窗（EXTRA_MAX_LINES = 80 条物理行，核心层 takeLast）。
        // 这一节吃掉一大半，内存读数/心跳/冻结现场就会被整段挤掉 —— 那正是本仓踩过一次的坑。
        // 上界 = 空行 + 小节标题 + 摘要 + 表头 + 明细 N + 判定。
        val budget = FrameTrace.SUMMARY_FIXED_LINES + FrameTrace.REPORT_FRAME_ROWS + 2
        assertTrue(
            "frames 小节必须远小于 80 行尾窗，实得 ${lines.size} 行（预算 $budget）",
            lines.size <= budget,
        )
        assertTrue("末行仍是判定行：${lines.last()}", lines.last().startsWith("verdict:"))
    }

    @Test
    fun detachClearsRing() {
        FrameTraceChannel.trace.record(sample(1))
        assertEquals(1, FrameTraceChannel.size())
        FrameTraceChannel.detach()
        assertEquals("关掉开关必须清空环 —— 报告里要立刻变回「未开启」", 0, FrameTraceChannel.size())
    }

    @Test
    fun sectionTitleMatchesCapacity() {
        assertEquals("环容量与表头文案必须同源", 240, FrameTrace.DEFAULT_CAPACITY)
        assertTrue(
            "报告表头里的「最近 N 帧」要与真实容量一致：${FrameTraceChannel.SECTION_TITLE}",
            FrameTraceChannel.SECTION_TITLE.contains("${FrameTrace.DEFAULT_CAPACITY} 帧"),
        )
    }

    @Test
    fun copyIsThreeLanguagesAndDistinct() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        all.forEach { t ->
            assertTrue("${t.lang} 的开关标题为空", t.frameTraceTitle.isNotEmpty())
            assertTrue("${t.lang} 的开关说明为空", t.frameTraceHint.isNotEmpty())
        }
        assertEquals("标题三语重复（同一条文案抄三遍 = 玩家看到的语言不对）", 3, all.map { it.frameTraceTitle }.distinct().size)
        assertEquals("说明三语重复", 3, all.map { it.frameTraceHint }.distinct().size)
        assertTrue(
            "中文说明必须讲清「复现后再分享」这个动作序，且点明不会自动发送",
            all[0].frameTraceHint.let { it.contains("分享") && it.contains("不会自动发送") },
        )
    }
}
