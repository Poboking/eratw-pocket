package com.eratw.port.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画面左右留白居中档位（`UiPrefs.contentMarginFraction` / `content_margin_fraction`）的
 * 读写与键隔离钉子（2026-11-14 圆屏适配）。
 *
 * 口径照 [LiveReflowPrefTest]：不走 Android 的 `Context`/`SharedPreferences` 实现，
 * 注入内存版 `SharedPreferences` 测试桩直测 [UiPrefs] 的 internal read/write 层 ——
 * `contentMarginFraction(context)` / `setContentMarginFraction(context, …)` 只是同一对
 * 函数的一行接线（与既有键同形）。
 *
 * 钉住的五件事：
 *   1. 缺省 **0**（关 = 满宽，全部读数与本功能加入前逐值相同）；
 *   2. 档位值白名单：读不认识回落缺省、写不认识归一成缺省（0.05 这种档间值不许落盘）；
 *   3. 写中档读回中档、再写关读回关（设置页来回切两次必须回到关）；
 *   4. 键名逐字 `content_margin_fraction`（改名 = 玩家已存偏好丢失）；
 *   5. 与既有壳层偏好键**互不污染**（两个方向都测）。
 */
class ContentMarginPrefTest {

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
    // 缺省 / 档位白名单 / 读写往返
    // ------------------------------------------------------------------

    @Test
    fun `缺省是关`() {
        val sp = FakeSharedPreferences()
        // 空表即「从来没写过」：缺省必须关 —— 关 = 满宽，与本功能加入前逐值相同
        assertEquals(UiPrefs.DEFAULT_CONTENT_MARGIN_FRACTION, UiPrefs.readContentMarginFraction(sp))
        assertEquals(0f, UiPrefs.DEFAULT_CONTENT_MARGIN_FRACTION)
        assertEquals(0f, UiPrefs.readContentMarginFraction(sp))
    }

    @Test
    fun `档位白名单四档且首档是关`() {
        assertEquals(
            "档位表逐字钉住：改档位 = 改玩家可见行为，必须先改这里",
            listOf(0f, 0.04f, 0.08f, 0.12f),
            UiPrefs.CONTENT_MARGIN_OPTIONS,
        )
    }

    @Test
    fun `写中档读回中档 再写关读回关`() {
        val sp = FakeSharedPreferences()

        UiPrefs.writeContentMarginFraction(sp, 0.08f)
        assertTrue("写入落盘值", sp.map["content_margin_fraction"] == 0.08f)
        assertEquals(0.08f, UiPrefs.readContentMarginFraction(sp))

        // 再关回去：不残留上一次的中档（设置页来回切两次必须回到关）
        UiPrefs.writeContentMarginFraction(sp, 0f)
        assertTrue("写回落在同一个键上", sp.map["content_margin_fraction"] == 0f)
        assertEquals(0f, UiPrefs.readContentMarginFraction(sp))
    }

    @Test
    fun `档间值与档外值读写两侧都归一成关`() {
        val sp = FakeSharedPreferences()
        // 写侧：0.05 不在白名单 ⇒ 落盘缺省（档位只会来自设置页枚举，兜底防将来传错）
        UiPrefs.writeContentMarginFraction(sp, 0.05f)
        assertTrue(sp.map["content_margin_fraction"] == 0f)
        // 读侧：盘上出现档案外的值（旧版本/手改）⇒ 读数回落缺省，不外溢成任意比例
        sp.map["content_margin_fraction"] = 0.99f
        assertEquals(0f, UiPrefs.readContentMarginFraction(sp))
    }

    @Test
    fun `键名逐字是 content_margin_fraction`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeContentMarginFraction(sp, 0.04f)
        // 改名 = 玩家已存偏好丢失，所以键名在这里逐字钉死
        assertEquals(setOf("content_margin_fraction"), sp.map.keys)
    }

    // ------------------------------------------------------------------
    // 与既有键互不污染（键名都是私有常量，照 LiveReflowPrefTest 的做法用字面量钉）
    // ------------------------------------------------------------------

    /** 既有壳层偏好键（`UiPrefs` 的私有常量，值与语义都是玩家已存偏好的落点）。 */
    private val otherKeys = listOf(
        "font_columns",
        "screen_fit",
        "busy_indicator",
        "option_rail",
        "disable_bad_apple",
        "auto_update",
        "font_columns_portrait",
        "orientation",
        "command_tray_open",
        "sprite_stage_collapsed",
        "live_reflow",
        "input_knob",
    )

    @Test
    fun `写它不动既有偏好`() {
        val sp = FakeSharedPreferences()
        // 先按既有键各放一个值（类型也照真身：Int / Boolean / Float / String）
        sp.map["font_columns"] = 90
        sp.map["screen_fit"] = false
        sp.map["busy_indicator"] = true
        sp.map["input_knob"] = 0.5f
        sp.map["orientation"] = "portrait"
        sp.map["font_columns_portrait"] = 75
        val before = HashMap(sp.map)

        UiPrefs.writeContentMarginFraction(sp, 0.12f)

        assertEquals("既有键的落盘值一字不变", before, sp.map - "content_margin_fraction")
        assertEquals(0.12f, UiPrefs.readContentMarginFraction(sp))
    }

    @Test
    fun `别的键落盘也不会把留白读成非零`() {
        val sp = FakeSharedPreferences()
        for (key in otherKeys) sp.map[key] = true

        // 一次都没写过 ⇒ 关（缺省不会被邻居带偏：键名不相交）
        assertEquals(0f, UiPrefs.readContentMarginFraction(sp))
        assertTrue(!sp.map.containsKey("content_margin_fraction"))
    }
}
