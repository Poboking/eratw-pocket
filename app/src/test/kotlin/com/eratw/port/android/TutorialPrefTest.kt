package com.eratw.port.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首启教程「已读」标记的壳层偏好钉子（2026-11-09）。
 *
 * 口径照 `EchoInputPrefTest`：注入内存版 SharedPreferences 桩直测 internal read/write 层。
 * 钉住四件事：缺省 false（首次安装才弹）、写后读 true、键名逐字 `tutorial_shown`、
 * 「全部重置」不清它（引导完成态，清掉会让老玩家每次重置再读一遍教程）。
 *
 * 另有一枚源码面钉子：**弹出时机**测不到（要真机首帧），只能钉源码 ——
 * 玩家明确要求「还没加载完不许弹」，放行闸必须是首帧非空内容落笔，
 * 而不是 setContent 首帧或 `shell.loading`（失败屏也把它复位成 false）。
 */
class TutorialPrefTest {

    /** 内存版 SharedPreferences：apply()/commit() 同步落进 map，键值可直接断言。 */
    private class FakeSharedPreferences : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map

        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return map[key] as? MutableSet<String> ?: defValues
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

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
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

    @Test
    fun `缺省未展示过首次安装才弹`() {
        val sp = FakeSharedPreferences()
        assertFalse("没看过教程就该弹", UiPrefs.readTutorialShown(sp))
        assertFalse("缺键不该占表", sp.contains("tutorial_shown"))
    }

    @Test
    fun `落已读后不再弹且键名逐字`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeTutorialShown(sp)
        assertTrue(UiPrefs.readTutorialShown(sp))
        assertEquals("键名改了会让老玩家重看教程", true, sp.map["tutorial_shown"])
    }

    @Test
    fun `全部重置不清教程已读标记`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeTutorialShown(sp)
        sp.edit().putBoolean("auto_update", false).apply()
        UiPrefs.resetAll(sp)
        assertTrue("引导完成态不是设置，重置不该再拦一次教程", UiPrefs.readTutorialShown(sp))
        assertFalse("设置键照清", sp.contains("auto_update"))
    }

    @Test
    fun `弹出时机钉在首帧非空内容落笔之后`() {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/MainActivity.kt"),
            File("../app/src/main/kotlin/com/eratw/port/android/MainActivity.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 MainActivity.kt（试了 ${candidates.joinToString { it.path }}）")
        val activity = file.readText(Charsets.UTF_8)

        assertTrue(
            "教程可见态必须从 false 起步（不能进页面就按偏好直接弹）",
            activity.contains("var tutorialVisible by remember { mutableStateOf(false) }"),
        )
        assertTrue(
            "放行闸必须盯首帧内容观测键",
            activity.contains("LaunchedEffect(firstContentFrameSeen)"),
        )
        assertTrue(
            "放行条件必须同时要求首帧已落笔与未读过",
            activity.contains("if (firstContentFrameSeen && !UiPrefs.tutorialShown(this@MainActivity))"),
        )
        val sets = Regex("firstContentFrameSeen = true").findAll(activity).count()
        assertEquals("置位点只该有一处", 1, sets)
        val callbackAt = activity.indexOf("consoleView.onFirstContentFrame = {")
        val setAt = activity.indexOf("firstContentFrameSeen = true")
        assertTrue("置位必须挂在首帧回调里", callbackAt in 0 until setAt)
        assertTrue(
            "置位点必须紧跟在回调开括号后的同一块里（不能被挪去别的相位）",
            activity.substring(callbackAt, setAt).count { it == '{' } == 1,
        )
    }
}