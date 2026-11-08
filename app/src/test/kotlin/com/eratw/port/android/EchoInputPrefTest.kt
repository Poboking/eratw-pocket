package com.eratw.port.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「键入回显复刻」（ECHO-INPUT）开关的壳层偏好钉子（2026-11-06）。
 *
 * 玩家侧缺省必须是**关**：点「选择口上剧本」页的剧本后看不到那行编号、整页不抖
 * （回显行仍进逻辑行表，只是不进上屏帧 —— 语义面由 core 的 EchoInputToggleTest 钉）。
 * 口径照 `LiveReflowPrefTest`：注入内存版 SharedPreferences 桩直测 internal read/write 层，
 * 公开函数只是 `prefs(context)` 的一行接线，另测只是复述实现。
 *
 * 钉住四件事：缺省 false、写开读开写关读关、键名逐字 `echo_input`、与既有偏好互不污染。
 */
class EchoInputPrefTest {

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

    @Test
    fun `缺省是关`() {
        val sp = FakeSharedPreferences()
        assertFalse(UiPrefs.DEFAULT_ECHO_INPUT)
        assertFalse("从来没写过 ⇒ 玩家侧默认不回显", UiPrefs.readEchoInput(sp))
        sp.map["echo_input"] = false
        assertFalse("显式存的 false 与缺省同一读数", UiPrefs.readEchoInput(sp))
    }

    @Test
    fun `写开后读回开 写关后读回关`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeEchoInput(sp, true)
        assertTrue(sp.map["echo_input"] == true)
        assertTrue(UiPrefs.readEchoInput(sp))

        UiPrefs.writeEchoInput(sp, false)
        assertTrue(sp.map["echo_input"] == false)
        assertFalse(UiPrefs.readEchoInput(sp))
    }

    @Test
    fun `键名逐字是 echo_input`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeEchoInput(sp, true)
        assertEquals(setOf("echo_input"), sp.map.keys)
    }

    @Test
    fun `写它不动既有偏好 别的键也不会把它读成开`() {
        val sp = FakeSharedPreferences()
        sp.map["font_columns"] = 90
        sp.map["screen_fit"] = false
        sp.map["lean_options"] = true
        sp.map["live_reflow"] = true
        val before = HashMap(sp.map)

        UiPrefs.writeEchoInput(sp, true)
        assertEquals("既有键一字不变", before, sp.map - "echo_input")

        // 反向：清空后把邻居全置 true，这一项仍必须回落缺省关
        val other = FakeSharedPreferences()
        listOf("font_columns", "screen_fit", "lean_options", "live_reflow", "option_rail").forEach {
            other.map[it] = true
        }
        assertFalse(UiPrefs.readEchoInput(other))
        assertFalse(other.map.containsKey("echo_input"))
    }

    @Test
    fun `恢复默认设置的整表 clear 能把它带回关`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeEchoInput(sp, true)
        assertTrue(UiPrefs.readEchoInput(sp))

        UiPrefs.resetAll(sp)
        assertFalse("clear 后读侧必须自带缺省", UiPrefs.readEchoInput(sp))
    }
}
