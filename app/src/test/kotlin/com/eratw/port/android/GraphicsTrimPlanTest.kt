package com.eratw.port.android

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GraphicsTrimPlan] 分流口径：TRIM_MEMORY 档位是**两套不成序的刻度**（前台 RUNNING 系
 * 5/10/15 全比后台 UI_HIDDEN=20 小），回收集必须逐档点名、不能拿数值比大小。
 * 这些判式锁的是 2026-10-31 稳定性审计 #5 的改动点：旧写法 `level >= RUNNING_LOW(10)`
 * 跨刻度比较，恰好只漏了最轻的 RUNNING_MODERATE(5)；换成查表后七个系统档全收。
 * 本类的数值断言在证伪时真实拦下过一次「把 RUNNING_CRITICAL 记成 5」的口径错误
 * （实际 =15）—— 这正是数值自证要钉死的东西。
 *
 * 为什么是 JVM 单测：`ComponentCallbacks2.TRIM_MEMORY_*` 是编译期内联常量，判式本身
 * 无 Android 运行期依赖（同 [RunTrailTest] 的先例）；真正的回收动作在
 * `SpriteBitmapSource.trimCaches`，那边由平台模块的用例锁。
 */
class GraphicsTrimPlanTest {
    /** 数值口径自证：KDoc 的「MODERATE=5 … COMPLETE=80」必须逐个对得上 SDK 常量。 */
    @Test
    fun `档位数值与 KDoc 声明一致`() {
        assertEquals(5, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(10, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(15, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(20, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(40, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertEquals(60, ComponentCallbacks2.TRIM_MEMORY_MODERATE)
        assertEquals(80, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    /** 修复点本身：旧判式漏掉的最轻前台档必须命中回收集。 */
    @Test
    fun `RUNNING_MODERATE 也要收工作表`() {
        assertTrue(GraphicsTrimPlan.dropsSheets(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    }

    /** 其余五个系统档维持旧行为：UI_HIDDEN 是切后台必发的那一发，也是 native 表唯一可靠回收点。 */
    @Test
    fun `其余系统档全在回收集里`() {
        for (level in intArrayOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )) {
            assertTrue("level=$level 应在回收集", GraphicsTrimPlan.dropsSheets(level))
        }
    }

    /** 未知档（OEM 自造值 / 0 / 负数）一律不收：宁可不做也不炸。 */
    @Test
    fun `未知档不收`() {
        assertFalse(GraphicsTrimPlan.dropsSheets(0))
        assertFalse(GraphicsTrimPlan.dropsSheets(-1))
        assertFalse(GraphicsTrimPlan.dropsSheets(0x1234))
    }
}
