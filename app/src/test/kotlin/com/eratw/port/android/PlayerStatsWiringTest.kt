package com.eratw.port.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 匿名玩家心跳**接线层**的定向单测：真 [UiPrefs] 读写 + 假 SharedPreferences + 假传输口。
 *
 * 重点不是「发了没」，而是四条最容易静默退化的纪律：
 * 1. 首次成功才记当日日期；失败（非 2xx / 抛错）不记，下次冷启再试；
 * 2. 编号一旦生成就**稳定复用**（跨天、失败重试都不重抽），否则服务端按 id 去重会虚增玩家数；
 * 3. 关闸 / 没配端点 = 零出网，连编号都不生成、偏好表一个键都不写；
 * 4. 出网 body 只有 kind 与 id，端点与令牌走的还是错误报告那一条通道。
 *
 * 另有一组源码面钉子：这里测不到「冷启有没有挂上」「设置页有没有开关」「恢复默认
 * 会不会把编号清掉」，那些漏了在屏上没有任何表现，只能钉源码。
 */
class PlayerStatsWiringTest {

    private companion object {
        const val ENDPOINT = "https://eratw.example/report"
        const val TOKEN = "test-token"
        const val KEY_ENABLED = "player_stats_enabled"
        const val KEY_ID = "player_stats_id"
        const val KEY_LAST_DAY = "player_stats_last_day"
        const val DAY_1 = "1970-01-01"
        const val DAY_2 = "1970-01-02"
        val FIXED_ID = "ab".repeat(32)
    }

    /** 内存版 SharedPreferences：apply() 同步落进 map，便于直接断言写了哪几个键。 */
    private class FakeSp : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = HashMap<String?, Any?>()
            private val removals = HashSet<String?>()
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
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

    private class FakeTransport(var result: Boolean = true, var fail: Boolean = false) :
        UpdateTelemetrySink.Transport {
        val calls: MutableList<Triple<String, String, String>> = mutableListOf()
        override fun post(endpoint: String, token: String, body: String): Boolean {
            if (fail) throw IllegalStateException("桩：传输层炸了")
            calls.add(Triple(endpoint, token, body))
            return result
        }
    }

    private val fixedRandom: (Int) -> ByteArray = { size -> ByteArray(size) { 0xAB.toByte() } }

    private fun ping(
        sp: SharedPreferences,
        transport: UpdateTelemetrySink.Transport,
        nowMillis: Long = 0L,
        endpoint: String = ENDPOINT,
        random: (Int) -> ByteArray = fixedRandom,
    ): Boolean = PlayerStatsWiring.pingOnce(sp, endpoint, TOKEN, transport, nowMillis, random)

    // -----------------------------------------------------------------
    // 成功路径
    // -----------------------------------------------------------------

    @Test
    fun `首次成功会生成稳定编号并只记当日`() {
        val sp = FakeSp()
        val transport = FakeTransport()
        assertTrue(ping(sp, transport))
        assertEquals(1, transport.calls.size)
        assertEquals(ENDPOINT, transport.calls[0].first)
        assertEquals(TOKEN, transport.calls[0].second)
        assertEquals("{\"kind\":\"player-presence\",\"id\":\"$FIXED_ID\"}", transport.calls[0].third)
        assertEquals(FIXED_ID, sp.map[KEY_ID])
        assertEquals(DAY_1, sp.map[KEY_LAST_DAY])
    }

    @Test
    fun `同一天再次冷启零出网`() {
        val sp = FakeSp()
        val transport = FakeTransport()
        assertTrue(ping(sp, transport))
        transport.calls.clear()
        assertFalse(ping(sp, transport))
        assertTrue("节流闸该把第二拍挡住", transport.calls.isEmpty())
        assertEquals(DAY_1, sp.map[KEY_LAST_DAY])
    }

    @Test
    fun `跨天再发但编号稳定复用`() {
        val sp = FakeSp()
        val transport = FakeTransport()
        assertTrue(ping(sp, transport))
        assertTrue(ping(sp, transport, nowMillis = 86_400_000L))
        assertEquals(2, transport.calls.size)
        assertEquals(transport.calls[0].third, transport.calls[1].third)
        assertEquals(FIXED_ID, sp.map[KEY_ID])
        assertEquals(DAY_2, sp.map[KEY_LAST_DAY])
    }

    @Test
    fun `已有的合法编号直接用不再取随机数`() {
        val sp = FakeSp()
        sp.map[KEY_ID] = "c".repeat(64)
        val transport = FakeTransport()
        assertTrue(ping(sp, transport, random = { throw AssertionError("不该再取随机数") }))
        assertEquals("{\"kind\":\"player-presence\",\"id\":\"${"c".repeat(64)}\"}", transport.calls[0].third)
    }

    // -----------------------------------------------------------------
    // 关闸：零出网、零编号、零写盘
    // -----------------------------------------------------------------

    @Test
    fun `玩家关掉统计时零出网且不生成编号`() {
        val sp = FakeSp()
        sp.map[KEY_ENABLED] = false
        val transport = FakeTransport()
        assertFalse(ping(sp, transport))
        assertTrue(transport.calls.isEmpty())
        assertNull(sp.map[KEY_ID])
        assertNull(sp.map[KEY_LAST_DAY])
        assertEquals("只该有玩家自己写的那个开关", setOf(KEY_ENABLED), sp.map.keys)
    }

    @Test
    fun `端点没配时零出网且不生成编号`() {
        val sp = FakeSp()
        val transport = FakeTransport()
        assertFalse(ping(sp, transport, endpoint = ""))
        assertFalse(ping(sp, transport, endpoint = "http://eratw.example/report"))
        assertTrue(transport.calls.isEmpty())
        assertTrue("开发包 / CI 包一个键都不该多写", sp.map.isEmpty())
    }

    // -----------------------------------------------------------------
    // 失败路径：不记日期、不重抽编号、不外逃
    // -----------------------------------------------------------------

    @Test
    fun `非 2xx 不记当日下次重试复用同一枚编号`() {
        val sp = FakeSp()
        val transport = FakeTransport(result = false)
        assertFalse(ping(sp, transport))
        assertEquals(FIXED_ID, sp.map[KEY_ID])
        assertNull("没数上不许记成已数过", sp.map[KEY_LAST_DAY])

        transport.result = true
        val second = FakeTransport()
        assertTrue(ping(sp, second))
        assertEquals(FIXED_ID, sp.map[KEY_ID])
        assertEquals(DAY_1, sp.map[KEY_LAST_DAY])
        assertEquals("{\"kind\":\"player-presence\",\"id\":\"$FIXED_ID\"}", second.calls[0].third)
    }

    @Test
    fun `传输抛错不外逃也不记当日`() {
        val sp = FakeSp()
        val transport = FakeTransport(fail = true)
        assertFalse(ping(sp, transport))
        assertNull(sp.map[KEY_LAST_DAY])
    }

    @Test
    fun `存着的坏编号会重抽合法的`() {
        val sp = FakeSp()
        sp.map[KEY_ID] = "../evil"
        val transport = FakeTransport()
        assertTrue(ping(sp, transport))
        assertEquals(FIXED_ID, sp.map[KEY_ID])
        assertNotEquals("../evil", sp.map[KEY_ID])
        assertEquals("{\"kind\":\"player-presence\",\"id\":\"$FIXED_ID\"}", transport.calls[0].third)
    }

    @Test
    fun `随机源给错长度时一个字节都不出网`() {
        val sp = FakeSp()
        val transport = FakeTransport()
        assertFalse(ping(sp, transport, random = { ByteArray(4) }))
        assertTrue(transport.calls.isEmpty())
        assertNull(sp.map[KEY_ID])
        assertNull(sp.map[KEY_LAST_DAY])
    }

    // -----------------------------------------------------------------
    // 源码面钉子：这些地方漏接，屏上不会有任何表现
    // -----------------------------------------------------------------

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `冷启接线点设置页开关与身份保留都在册`() {
        val host = sourceOf("GameHost.kt")
        assertTrue("冷启没挂上玩家心跳", host.contains("PlayerStatsWiring.reportAsync(view.context)"))
        assertTrue("心跳必须排在漏斗冲刷之后", host.indexOf("UpdateTelemetryWiring.flushPendingAsync") <
            host.indexOf("PlayerStatsWiring.reportAsync"))

        val activity = sourceOf("SettingsActivity.kt")
        assertTrue("设置页没有三语统计文案", activity.contains("PlayerStatsCopy.of(text.lang)"))
        assertTrue("开关没有落偏好", activity.contains("UiPrefs.setPlayerStatsEnabled(context, enabled)"))

        val prefs = sourceOf("UiPrefs.kt")
        assertTrue("恢复默认会把匿名编号重抽（徽章会虚增）",
            prefs.contains("KEY_PLAYER_STATS_ID,") && prefs.substringAfter("private val KEEP_ON_RESET")
                .substringBefore(")").contains("KEY_PLAYER_STATS_ID"))

        val strings = SettingsLanguage.entries.map { PlayerStatsCopy.of(it).title }
        assertEquals("三语标题得是三句不同的话", 3, strings.distinct().size)
        assertTrue(SettingsLanguage.entries.all { PlayerStatsCopy.of(it).hint.isNotEmpty() })
    }
}
