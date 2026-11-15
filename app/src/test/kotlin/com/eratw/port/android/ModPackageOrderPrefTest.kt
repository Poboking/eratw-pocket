package com.eratw.port.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RT2 玩家排序配置（L2）的壳层偏好钉子：写读往返 / 空串 / 去重 / trim / 非法字符 /
 * resetAll / 纯转换函数。
 *
 * 口径照 [ModRuntimeGateTest]：注入内存版 SharedPreferences 桩直测 [UiPrefs] 的
 * internal read/write 层与 [UiPrefs.parsePreferredPackageIds] 纯函数，公开函数只做
 * prefs(context) 接线、不进 JVM 单测。
 *
 * 归一化语义（去重 / 去空 / 保序 / trim）与「配置来源」都在壳层，核心 LinkOrder /
 * ModuleLinkOrder.plan 一行不改；行为对拍在 [ModPackageOrderWireTest]。
 */
class ModPackageOrderPrefTest {

    // ---- 内存版 SharedPreferences（照 ModRuntimeGateTest 的桩口径，独立一份避免跨类依赖）----

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
                pending[key] = value; return this
            }
            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                pending[key] = values; return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key] = value; return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                removals.add(key); return this
            }
            override fun clear(): SharedPreferences.Editor {
                pending.clear(); removals.clear(); map.clear(); return this
            }
            override fun commit(): Boolean {
                flush(); return true
            }
            override fun apply() = flush()

            private fun flush() {
                for (k in removals) if (k != null) map.remove(k)
                for ((k, v) in pending) if (k != null) map[k] = v
                pending.clear(); removals.clear()
            }
        }
    }

    // ---- 纯转换函数 [UiPrefs.parsePreferredPackageIds] ----

    @Test
    fun `空串解析为空表（缺省 LinkOrder 行为）`() {
        assertEquals(emptyList<String>(), UiPrefs.parsePreferredPackageIds(""))
        assertEquals(emptyList<String>(), UiPrefs.parsePreferredPackageIds("   "))
        assertEquals(emptyList<String>(), UiPrefs.parsePreferredPackageIds(",, ,"))
    }

    @Test
    fun `parse 去重且保序`() {
        assertEquals(listOf("a", "b", "c"), UiPrefs.parsePreferredPackageIds("a,b,a,c,b"))
    }

    @Test
    fun `parse 去空与首尾 trim`() {
        assertEquals(listOf("a", "b"), UiPrefs.parsePreferredPackageIds(" a , , b ,\t,  "))
    }

    @Test
    fun `parse 段内空白等非法字符整段丢弃`() {
        assertEquals(listOf("good", "ok"), UiPrefs.parsePreferredPackageIds("a b, good,c\td, ok"))
    }

    // ---- internal read/write 层 ----

    @Test
    fun `缺省读回落空串`() {
        val sp = FakeSharedPreferences()
        assertEquals("", UiPrefs.readPreferredPackageIds(sp))
    }

    @Test
    fun `写读原始串往返逐字一致`() {
        val sp = FakeSharedPreferences()
        val raw = " z-mod , a-mod ,,b-mod "
        UiPrefs.writePreferredPackageIds(sp, raw)
        assertEquals(raw, sp.map["mod_package_order"])
        assertEquals(raw, UiPrefs.readPreferredPackageIds(sp))
    }

    @Test
    fun `写入列表读回逐值一致（保序往返）`() {
        val sp = FakeSharedPreferences()
        val input = listOf("z-mod", "a-mod", "m-mod")
        UiPrefs.writePreferredPackageIds(sp, input.joinToString(","))
        assertEquals(input, UiPrefs.parsePreferredPackageIds(UiPrefs.readPreferredPackageIds(sp)))
    }

    @Test
    fun `去重保序往返（摘掉去重即红）`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writePreferredPackageIds(sp, "a,b,a,c,b")
        assertEquals(listOf("a", "b", "c"), UiPrefs.parsePreferredPackageIds(UiPrefs.readPreferredPackageIds(sp)))
    }

    @Test
    fun `键名逐字是 mod_package_order 且不碰邻居`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeModRuntime(sp, true)
        UiPrefs.writeEchoInput(sp, true)
        val before = sp.map.toMap()
        UiPrefs.writePreferredPackageIds(sp, "a,b")
        assertEquals("既有键一字不变", before, sp.map - "mod_package_order")
        assertEquals(setOf("mod_package_order"), sp.map.keys - before.keys)
    }

    @Test
    fun `resetAll 清掉键回到缺省空串`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writePreferredPackageIds(sp, "a,b,c")
        UiPrefs.resetAll(sp)
        assertEquals("", UiPrefs.readPreferredPackageIds(sp))
        assertEquals(
            emptyList<String>(),
            UiPrefs.parsePreferredPackageIds(UiPrefs.readPreferredPackageIds(sp)),
        )
    }
}
