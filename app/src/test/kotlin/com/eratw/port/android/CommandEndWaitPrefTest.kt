package com.eratw.port.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「指令后等一次按键」（COMEND-WAIT）开关的壳层偏好钉子（2026-11-22）。
 *
 * 玩家侧缺省必须是**开** = 原版口径：指令打出来的口上停在屏上，点一下才回指令菜单
 * （`Process.cs:2417` 置位 → `:2449-2455` 的 `setWait()`）；关掉才是 2026-11-02 那版
 * 「不等待」的行为。口径照 `EchoInputPrefTest`：注入内存版 SharedPreferences 桩直测
 * internal read/write 层，公开函数只是 `prefs(context)` 的一行接线。
 *
 * 钉住四件事：缺省 true、写开读开写关读关、键名逐字 `command_end_wait`、与既有偏好互不污染。
 */
class CommandEndWaitPrefTest {

    /** 内存版 SharedPreferences：apply()/commit() 同步落进 map，键值可直接断言。 */
    private class FakeSharedPreferences : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map

        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            defValues

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
    fun `缺省是开`() {
        val sp = FakeSharedPreferences()
        assertTrue(UiPrefs.DEFAULT_COMMAND_END_WAIT)
        assertTrue("从来没写过 ⇒ 玩家侧默认按原版等一次", UiPrefs.readCommandEndWait(sp))
        sp.map["command_end_wait"] = true
        assertTrue("显式存的 true 与缺省同一读数", UiPrefs.readCommandEndWait(sp))
    }

    @Test
    fun `写关后读回关 写开后读回开`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeCommandEndWait(sp, false)
        assertTrue(sp.map["command_end_wait"] == false)
        assertFalse(UiPrefs.readCommandEndWait(sp))

        UiPrefs.writeCommandEndWait(sp, true)
        assertTrue(sp.map["command_end_wait"] == true)
        assertTrue(UiPrefs.readCommandEndWait(sp))
    }

    @Test
    fun `键名逐字是 command_end_wait`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeCommandEndWait(sp, false)
        assertEquals(setOf("command_end_wait"), sp.map.keys)
    }

    @Test
    fun `写它不动既有偏好 别的键也不会把它读成关`() {
        val sp = FakeSharedPreferences()
        sp.map["font_columns"] = 90
        sp.map["screen_fit"] = false
        sp.map["echo_input"] = false
        sp.map["busy_indicator"] = true
        val before = HashMap(sp.map)

        UiPrefs.writeCommandEndWait(sp, false)
        assertEquals("既有键一字不变", before, sp.map - "command_end_wait")

        // 反向：清空后把邻居全置 false，这一项仍必须回落缺省开
        val other = FakeSharedPreferences()
        listOf("font_columns", "screen_fit", "echo_input", "busy_indicator", "option_rail").forEach {
            other.map[it] = false
        }
        assertTrue(UiPrefs.readCommandEndWait(other))
        assertFalse(other.map.containsKey("command_end_wait"))
    }

    @Test
    fun `恢复默认设置的整表 clear 能把它带回开`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeCommandEndWait(sp, false)
        assertFalse(UiPrefs.readCommandEndWait(sp))

        UiPrefs.resetAll(sp)
        assertTrue("clear 后读侧必须自带缺省（开 = 原版）", UiPrefs.readCommandEndWait(sp))
    }
}
