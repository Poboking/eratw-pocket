package com.eratw.port.android.portrait

import android.content.SharedPreferences
import com.eratw.port.android.UiPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖屏四键（orientation / font_columns_portrait / command_tray_open / sprite_stage_collapsed）
 * 的读写与坏值回落口径钉（设计稿 §7 键表 + §5.2 档位表；契约 §2 PORTRAIT-P1 第 3 条）。
 *
 * 口径照既有纯 JVM 测试（如 `ShellUiRailGeometryTest` 钉档位表/`fromStored` 的形状）：
 * 不走 `Context`/`SharedPreferences` 的 Android 实现，注入内存版 `SharedPreferences` 测试桩
 * 直测 UiPrefs 的 internal read/write 层 —— Context→prefs() 接线与既有键同形，不另测。
 */
class UiPrefsPortraitTest {

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
    // 键名与档位表（设计稿 §7 表的键名逐字钉死：改名 = 玩家已存偏好丢失）
    // ------------------------------------------------------------------

    @Test
    fun `四个键名与设计稿第七节逐字一致`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeOrientation(sp, "portrait")
        UiPrefs.writeFontColumnsPortrait(sp, 75)
        UiPrefs.writeCommandTrayOpen(sp, true)
        UiPrefs.writeSpriteStageCollapsed(sp, true)
        assertTrue(sp.map.containsKey("orientation"))
        assertTrue(sp.map.containsKey("font_columns_portrait"))
        assertTrue(sp.map.containsKey("command_tray_open"))
        assertTrue(sp.map.containsKey("sprite_stage_collapsed"))
    }

    @Test
    fun `竖屏档位表只含 50 62 75 90 且缺省 62 在表内`() {
        // §5.2：50（特大）/ 62（大，缺省）/ 75（标准）/ 90（小）
        assertEquals(listOf(50, 62, 75, 90), UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT.map { it.columns })
        assertTrue(UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT.any { it.columns == UiPrefs.DEFAULT_FONT_COLUMNS_PORTRAIT })
        assertEquals(62, UiPrefs.DEFAULT_FONT_COLUMNS_PORTRAIT)
        // 档位名不重复（设置页单选项的文字）
        val labels = UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
    }

    // ------------------------------------------------------------------
    // orientation：读写 + 坏值回落（读侧与写侧同防线）
    // ------------------------------------------------------------------

    @Test
    fun `方向两值原样读写`() {
        val sp = FakeSharedPreferences()
        for (value in listOf("landscape", "portrait")) {
            UiPrefs.writeOrientation(sp, value)
            // map 原始值是 Any?：JUnit 的两参 assertEquals 没有 Object 版，这里用 == 直判
            assertTrue("写入落盘值", value == sp.map["orientation"])
            assertEquals(value, UiPrefs.readOrientation(sp))
        }
    }

    @Test
    fun `方向坏值与老版本 follow 一律回落横屏`() {
        // 归一函数：null / 拼错 / 大小写不合 / 老版本写坏 一律横屏（竖屏只能显式打开）
        assertEquals("landscape", UiPrefs.normalizeOrientation(null))
        assertEquals("landscape", UiPrefs.normalizeOrientation(""))
        assertEquals("landscape", UiPrefs.normalizeOrientation("vertical"))
        assertEquals("landscape", UiPrefs.normalizeOrientation("PORTRAIT"))
        assertEquals("landscape", UiPrefs.normalizeOrientation("竖屏"))
        // 0.2.40 曾把「跟随机转」当缺省写进过键表：老值必须回落横屏，不许复活那一档
        assertEquals("landscape", UiPrefs.normalizeOrientation("follow"))
        // 读侧：存进 XML 的是坏值 → 读回落
        val sp = FakeSharedPreferences()
        sp.map["orientation"] = "watermelon"
        assertEquals("landscape", UiPrefs.readOrientation(sp))
        // 写侧：传坏值 → 按横屏落盘（与 setFontColumns 同款防线）
        UiPrefs.writeOrientation(sp, "diagonal")
        assertTrue("写侧坏值按横屏落盘", "landscape" == sp.map["orientation"])
    }

    @Test
    fun `方向缺省是横屏`() {
        val sp = FakeSharedPreferences()
        assertEquals("landscape", UiPrefs.readOrientation(sp))
        assertEquals("landscape", UiPrefs.DEFAULT_ORIENTATION)
    }

    @Test
    fun `竖屏模式开关只认 portrait`() {
        val sp = FakeSharedPreferences()
        assertFalse("缺省关", UiPrefs.readOrientation(sp) == "portrait")
        UiPrefs.writeOrientation(sp, "portrait")
        assertTrue(UiPrefs.readOrientation(sp) == "portrait")
        UiPrefs.writeOrientation(sp, "landscape")
        assertFalse(UiPrefs.readOrientation(sp) == "portrait")
    }

    // ------------------------------------------------------------------
    // font_columns_portrait：读写 + 坏值回落 62（fontColumns 同款防线）
    // ------------------------------------------------------------------

    @Test
    fun `竖屏列数四档原样读写`() {
        val sp = FakeSharedPreferences()
        for (columns in listOf(50, 62, 75, 90)) {
            UiPrefs.writeFontColumnsPortrait(sp, columns)
            assertTrue("写入落盘值", columns == sp.map["font_columns_portrait"])
            assertEquals(columns, UiPrefs.readFontColumnsPortrait(sp))
        }
    }

    @Test
    fun `竖屏列数坏值回落 62`() {
        // 归一：表外值（含横屏的 136、0、负数）一律 62
        for (bad in listOf(0, 61, 100, 136, -1, 1000)) {
            assertEquals(62, UiPrefs.normalizeFontColumnsPortrait(bad))
        }
        // 读侧：XML 里的坏值 → 读回落
        val sp = FakeSharedPreferences()
        sp.map["font_columns_portrait"] = 136
        assertEquals(62, UiPrefs.readFontColumnsPortrait(sp))
        // 写侧：传坏值 → 按 62 落盘
        UiPrefs.writeFontColumnsPortrait(sp, 61)
        assertTrue("写侧坏值按 62 落盘", 62 == sp.map["font_columns_portrait"])
    }

    @Test
    fun `竖屏列数缺省是 62`() {
        val sp = FakeSharedPreferences()
        assertEquals(62, UiPrefs.readFontColumnsPortrait(sp))
    }

    // ------------------------------------------------------------------
    // command_tray_open / sprite_stage_collapsed：Boolean 读写 + 缺省
    // ------------------------------------------------------------------

    @Test
    fun `指令盘展开态缺省收起且可读写`() {
        val sp = FakeSharedPreferences()
        assertFalse(UiPrefs.readCommandTrayOpen(sp))
        assertFalse(UiPrefs.DEFAULT_COMMAND_TRAY_OPEN)
        UiPrefs.writeCommandTrayOpen(sp, true)
        assertTrue("写入落盘值", sp.map["command_tray_open"] == true)
        assertTrue(UiPrefs.readCommandTrayOpen(sp))
        UiPrefs.writeCommandTrayOpen(sp, false)
        assertFalse(UiPrefs.readCommandTrayOpen(sp))
    }

    @Test
    fun `立绘舞台折叠态缺省展开且可读写`() {
        val sp = FakeSharedPreferences()
        assertFalse(UiPrefs.readSpriteStageCollapsed(sp))
        assertFalse(UiPrefs.DEFAULT_SPRITE_STAGE_COLLAPSED)
        UiPrefs.writeSpriteStageCollapsed(sp, true)
        assertTrue("写入落盘值", sp.map["sprite_stage_collapsed"] == true)
        assertTrue(UiPrefs.readSpriteStageCollapsed(sp))
    }
}
