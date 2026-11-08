package com.eratw.port.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四个 QoL 开关的壳层偏好读写层（CONTRACT-MODERN-T6 §3.1 / T6-1 / T6-2）。
 *
 * 覆盖 `screen_fit` / `option_rail` / `lean_options` / `disable_bad_apple` 四对
 * internal `readX`/`writeX`（以 `SharedPreferences` 为参）；公开函数
 *（`screenFit(context)` 等）只是 `prefs(context)` 的一行接线（[UiPrefs.readLiveReflow] 同款先例），
 * 另测公开函数只是复述实现。
 *
 * 钉住四件事：
 *   1. 缺省值逐项：列数守护开 / 选项栏开 / 精简选项关 / 标题彩蛋屏蔽开，
 *      且 `readScreenFit` 的缺省就是 [UiPrefs.DEFAULT_SCREEN_FIT]（不再是字面量）；
 *   2. 每个开关 off→on→off（缺省为开的三项是 关→开→关）双向回读；
 *   3. 键名逐字（改名 = 玩家已存偏好丢失）；
 *   4. 四个开关互不污染（任一开关翻转不影响其余三项，邻居键也不会把它们读成别的值）。
 */
class QolPrefTest {

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
    fun `四个开关的缺省值`() {
        val sp = FakeSharedPreferences()
        assertTrue("列数守护缺省开", UiPrefs.DEFAULT_SCREEN_FIT)
        assertTrue("守护开关的缺省就是 DEFAULT_SCREEN_FIT（不是字面量）", UiPrefs.readScreenFit(sp))
        assertTrue("选项栏缺省开", UiPrefs.readOptionRail(sp))
        assertFalse("精简选项缺省关（关 = 原样显示全部识别结果）", UiPrefs.readLeanOptions(sp))
        assertTrue("标题彩蛋缺省屏蔽", UiPrefs.readDisableBadApple(sp))
        // 常量与读侧必须同值：设置页「恢复默认」显示的就是这几个常量。
        assertTrue(UiPrefs.DEFAULT_OPTION_RAIL)
        assertFalse(UiPrefs.DEFAULT_LEAN_OPTIONS)
        assertTrue(UiPrefs.DEFAULT_DISABLE_BAD_APPLE)
    }

    @Test
    fun `列数守护 关→开→关 双向回读`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeScreenFit(sp, false)
        assertTrue(sp.map["screen_fit"] == false)
        assertFalse(UiPrefs.readScreenFit(sp))

        UiPrefs.writeScreenFit(sp, true)
        assertTrue(sp.map["screen_fit"] == true)
        assertTrue(UiPrefs.readScreenFit(sp))

        UiPrefs.writeScreenFit(sp, false)
        assertFalse(UiPrefs.readScreenFit(sp))
    }

    @Test
    fun `选项栏 关→开→关 双向回读`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeOptionRail(sp, false)
        assertTrue(sp.map["option_rail"] == false)
        assertFalse(UiPrefs.readOptionRail(sp))

        UiPrefs.writeOptionRail(sp, true)
        assertTrue(sp.map["option_rail"] == true)
        assertTrue(UiPrefs.readOptionRail(sp))

        UiPrefs.writeOptionRail(sp, false)
        assertFalse(UiPrefs.readOptionRail(sp))
    }

    @Test
    fun `精简选项 开→关→开 双向回读`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeLeanOptions(sp, true)
        assertTrue(sp.map["lean_options"] == true)
        assertTrue(UiPrefs.readLeanOptions(sp))

        UiPrefs.writeLeanOptions(sp, false)
        assertTrue(sp.map["lean_options"] == false)
        assertFalse(UiPrefs.readLeanOptions(sp))

        UiPrefs.writeLeanOptions(sp, true)
        assertTrue(UiPrefs.readLeanOptions(sp))
    }

    @Test
    fun `标题彩蛋屏蔽 关→开→关 双向回读`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeDisableBadApple(sp, false)
        assertTrue(sp.map["disable_bad_apple"] == false)
        assertFalse("关 = 放行（emptySet 支）", UiPrefs.readDisableBadApple(sp))

        UiPrefs.writeDisableBadApple(sp, true)
        assertTrue(sp.map["disable_bad_apple"] == true)
        assertTrue("开 = 屏蔽（DEFAULT_DISABLED 支）", UiPrefs.readDisableBadApple(sp))

        UiPrefs.writeDisableBadApple(sp, false)
        assertFalse(UiPrefs.readDisableBadApple(sp))
    }

    @Test
    fun `四个键名逐字不变`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeScreenFit(sp, false)
        UiPrefs.writeOptionRail(sp, false)
        UiPrefs.writeLeanOptions(sp, true)
        UiPrefs.writeDisableBadApple(sp, false)
        assertEquals(
            "键名改名 = 玩家已存偏好丢失",
            setOf("screen_fit", "option_rail", "lean_options", "disable_bad_apple"),
            sp.map.keys,
        )

        // 反向：只塞邻居键（四个真键一个都没有）⇒ 四项一律回落缺省
        val decoy = FakeSharedPreferences()
        listOf("font_columns", "live_reflow", "echo_input", "loading_style", "rail_button_size")
            .forEach { decoy.map[it] = true }
        assertEquals(UiPrefs.DEFAULT_SCREEN_FIT, UiPrefs.readScreenFit(decoy))
        assertTrue(UiPrefs.readOptionRail(decoy))
        assertFalse(UiPrefs.readLeanOptions(decoy))
        assertTrue(UiPrefs.readDisableBadApple(decoy))
    }

    @Test
    fun `四个开关互不污染`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeScreenFit(sp, false)
        UiPrefs.writeOptionRail(sp, false)
        UiPrefs.writeLeanOptions(sp, true)
        UiPrefs.writeDisableBadApple(sp, false)
        // 四键同处一表也各读各的
        assertFalse(UiPrefs.readScreenFit(sp))
        assertFalse(UiPrefs.readOptionRail(sp))
        assertTrue(UiPrefs.readLeanOptions(sp))
        assertFalse(UiPrefs.readDisableBadApple(sp))

        // 只翻精简选项一份，其余三项读数一位不动
        UiPrefs.writeLeanOptions(sp, false)
        assertFalse(UiPrefs.readLeanOptions(sp))
        assertFalse(UiPrefs.readScreenFit(sp))
        assertFalse(UiPrefs.readOptionRail(sp))
        assertFalse(UiPrefs.readDisableBadApple(sp))

        // 反向：写别的偏好不会把这四项读成别的值
        sp.map["font_columns"] = 90
        sp.map["live_reflow"] = true
        sp.map["echo_input"] = true
        sp.map["option_rail_button_size"] = 3
        assertFalse(UiPrefs.readScreenFit(sp))
        assertFalse(UiPrefs.readOptionRail(sp))
        assertFalse(UiPrefs.readLeanOptions(sp))
        assertFalse(UiPrefs.readDisableBadApple(sp))
    }
}
