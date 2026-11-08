package com.eratw.port.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIVE-REFLOW 开关（`UiPrefs.liveReflow` / `live_reflow`）的读写与键隔离钉子
 * （契约 CONTRACT-LIVE-REFLOW §1 冻结 API、§6 L5）。
 *
 * 口径照 `UiPrefsPortraitTest`：不走 Android 的 `Context`/`SharedPreferences` 实现，
 * 注入内存版 `SharedPreferences` 测试桩直测 [UiPrefs] 的 internal read/write 层 ——
 * `liveReflow(context)` / `setLiveReflow(context, …)` 只是同一对函数的一行接线
 *（与既有键同形），另测它的唯一价值是复述实现。
 *
 * 钉住的四件事：
 *   1. 缺省 **false**（关 = 现有横屏/竖屏路径逐字不变）；
 *   2. 写 true 读回 true、再写 false 读回 false（设置页进出状态的落点）；
 *   3. 键名逐字 `live_reflow`（改名 = 玩家已存偏好丢失）；
 *   4. 与既有壳层偏好键**互不污染**（两个方向都测：写它不动别人、写别人不动它）。
 */
class LiveReflowPrefTest {

    /** 内存版 SharedPreferences 测试桩：apply()/commit() 同步落进 map，键值可在测试里直接断言。 */
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
                removals.add(key!!)
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

    // ------------------------------------------------------------------
    // 缺省 / 读写往返
    // ------------------------------------------------------------------

    @Test
    fun `缺省是关`() {
        val sp = FakeSharedPreferences()
        // 空表即「从来没写过」：缺省必须关 —— 关态下现有横屏/竖屏路径逐字不变
        assertFalse(UiPrefs.readLiveReflow(sp))
        // 显式存进去的 false 与缺省同一读数
        sp.map["live_reflow"] = false
        assertFalse(UiPrefs.readLiveReflow(sp))
    }

    @Test
    fun `写开后读回开 写关后读回关`() {
        val sp = FakeSharedPreferences()

        UiPrefs.writeLiveReflow(sp, true)
        assertTrue("写入落盘值", sp.map["live_reflow"] == true)
        assertTrue(UiPrefs.readLiveReflow(sp))

        // 再关回去：不残留上一次的 true（设置页来回切两次必须回到关）
        UiPrefs.writeLiveReflow(sp, false)
        assertTrue("写回落在同一个键上", sp.map["live_reflow"] == false)
        assertFalse(UiPrefs.readLiveReflow(sp))
    }

    @Test
    fun `键名逐字是 live_reflow`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeLiveReflow(sp, true)
        // 改名 = 玩家已存偏好丢失，所以键名在这里逐字钉死
        assertEquals(setOf("live_reflow"), sp.map.keys)
    }

    // ------------------------------------------------------------------
    // 与既有键互不污染（键名都是私有常量，这里照 `UiPrefsPortraitTest` 的做法用字面量钉）
    // ------------------------------------------------------------------

    /** 既有壳层偏好键（`UiPrefs` 的私有常量，值与语义都是玩家已存偏好的落点）。 */
    private val otherKeys = listOf(
        "font_columns",
        "screen_fit",
        "lean_options",
        "option_rail",
        "disable_bad_apple",
        "auto_update",
        "font_columns_portrait",
        "orientation",
        "command_tray_open",
        "sprite_stage_collapsed",
    )

    @Test
    fun `写它不动既有偏好`() {
        val sp = FakeSharedPreferences()
        // 先按既有键各放一个值（类型也照真身：Int / Boolean / String）
        sp.map["font_columns"] = 90
        sp.map["screen_fit"] = false
        sp.map["lean_options"] = true
        sp.map["option_rail"] = false
        sp.map["orientation"] = "portrait"
        sp.map["font_columns_portrait"] = 75
        val before = HashMap(sp.map)

        UiPrefs.writeLiveReflow(sp, true)

        assertEquals("既有键的落盘值一字不变", before, sp.map - "live_reflow")
        assertTrue(UiPrefs.readLiveReflow(sp))
    }

    @Test
    fun `别的开关全开也不会把这一项读成开`() {
        val sp = FakeSharedPreferences()
        for (key in otherKeys) sp.map[key] = true

        // 一次都没写过 ⇒ 关（缺省不会被邻居的 true 带偏：键名不相交）
        assertFalse(UiPrefs.readLiveReflow(sp))
        assertFalse(sp.map.containsKey("live_reflow"))
    }

    @Test
    fun `开着的时候别人写自己的键不会把它带关`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeLiveReflow(sp, true)

        // 模拟别的偏好各自落盘（同一个 editor 通路）
        sp.edit()
            .putInt("font_columns", 136)
            .putBoolean("screen_fit", true)
            .putBoolean("lean_options", false)
            .putBoolean("option_rail", true)
            .putString("orientation", "landscape")
            .putBoolean("command_tray_open", false)
            .apply()

        // 反向：别人写自己的键，不许把 LIVE-REFLOW 顺带关掉/覆盖掉
        assertTrue(UiPrefs.readLiveReflow(sp))
        assertTrue("落盘值仍是 true", sp.map["live_reflow"] == true)
    }
}
