package com.eratw.port.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长页忙碌提示（PRAY-BUSY）的壳层偏好钉子。
 *
 * 玩家侧缺省必须是**开**（主人拍板：长页要看得见「游戏自己在祈祷」）；关掉只少一层像素，
 * 不改任何脚本语义。口径照 `EchoInputPrefTest`：注入内存版 SharedPreferences 桩直测
 * internal read/write 层，公开函数只是 `prefs(context)` 的一行接线。
 */
class BusyIndicatorPrefTest {

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
    fun `缺省是开`() {
        val sp = FakeSharedPreferences()
        assertTrue(UiPrefs.DEFAULT_BUSY_INDICATOR)
        assertTrue("从来没写过 ⇒ 玩家侧默认显示忙碌提示", UiPrefs.readBusyIndicator(sp))
    }

    @Test
    fun `写关后读回关 写开后读回开`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeBusyIndicator(sp, false)
        assertEquals(false, sp.map["busy_indicator"])
        assertFalse(UiPrefs.readBusyIndicator(sp))

        UiPrefs.writeBusyIndicator(sp, true)
        assertEquals(true, sp.map["busy_indicator"])
        assertTrue(UiPrefs.readBusyIndicator(sp))
    }

    @Test
    fun `键名逐字是 busy_indicator 且不碰邻居`() {
        val sp = FakeSharedPreferences()
        sp.map["disable_bad_apple"] = true
        sp.map["echo_input"] = false
        val before = HashMap(sp.map)

        UiPrefs.writeBusyIndicator(sp, false)
        assertEquals("既有键一字不变", before, sp.map - "busy_indicator")
        assertEquals(setOf("busy_indicator"), sp.map.keys - before.keys)
    }

    @Test
    fun `恢复默认设置的整表 clear 能把它带回开`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeBusyIndicator(sp, false)
        assertFalse(UiPrefs.readBusyIndicator(sp))

        UiPrefs.resetAll(sp)
        assertTrue("clear 后读侧必须自带缺省开", UiPrefs.readBusyIndicator(sp))
    }
}
