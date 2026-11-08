package com.eratw.port.android

import android.content.SharedPreferences
import com.eratw.port.core.update.MirrorHealth
import com.eratw.port.core.update.UpdatePolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U4 upd-rollout 的 app 侧定向单测：灰度 bucket 稳定与镜像健康度读写（P1-5 / P1-7）。
 *
 * 口径照 `UiPrefsPortraitTest` / `UiPrefsResetTest`：注入内存版 SharedPreferences 测试桩，
 * 直测 UiPrefs 的 internal 层，不走 Context 与 Android 实现（纯 JVM 可跑）。
 * 判定矩阵在 core 的 `UpdateRolloutTest`，这里只钉「持久化」与「app 读数能喂回 core」。
 */
class UiPrefsRolloutTest {

    /** 内存版 SharedPreferences：apply()/commit() 同步落进 map，键值可在测试里直接断言。 */
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

    private val now = 1_700_000_000_000L
    private val a = "https://a.example/eratw/"
    private val b = "https://b.example/eratw/"

    // ---- 灰度 bucket ---------------------------------------------------

    @Test
    fun `bucket 首次生成落在 0 到 99 且落盘`() {
        val sp = FakeSharedPreferences()
        assertEquals(UiPrefs.NO_ROLLOUT_BUCKET, UiPrefs.readRolloutBucket(sp))
        val first = UiPrefs.rolloutBucket(sp)
        assertTrue("bucket 越界：$first", first in 0 until UpdatePolicy.ROLLOUT_BUCKETS)
        assertTrue("必须落盘", first == sp.map["rollout_bucket"])
    }

    @Test
    fun `同设备反复取 bucket 稳定`() {
        val sp = FakeSharedPreferences()
        val first = UiPrefs.rolloutBucket(sp)
        repeat(20) { assertEquals(first, UiPrefs.rolloutBucket(sp)) }
        assertTrue(sp.map.containsKey("rollout_bucket"))
    }

    @Test
    fun `bucket 坏值回落并重新生成`() {
        val sp = FakeSharedPreferences()
        for (bad in listOf(-1, 100, 999, Int.MIN_VALUE)) {
            sp.map["rollout_bucket"] = bad
            assertEquals(UiPrefs.NO_ROLLOUT_BUCKET, UiPrefs.readRolloutBucket(sp))
            val generated = UiPrefs.rolloutBucket(sp)
            assertTrue("坏值 $bad 后生成的 bucket：$generated", generated in 0..99)
            assertTrue("重新生成必须落盘", generated == sp.map["rollout_bucket"])
        }
    }

    @Test
    fun `写侧坏值兜底成一次新生成`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeRolloutBucket(sp, -7)
        val stored = sp.map["rollout_bucket"] as Int
        assertTrue(stored in 0..99)
        assertEquals(stored, UiPrefs.readRolloutBucket(sp))
    }

    @Test
    fun `freshRolloutBucket 每次都落在 0 到 99`() {
        repeat(50) {
            assertTrue(UiPrefs.freshRolloutBucket() in 0 until UpdatePolicy.ROLLOUT_BUCKETS)
        }
    }

    // ---- 镜像健康度 ----------------------------------------------------

    @Test
    fun `失败累加与成功清零`() {
        val sp = FakeSharedPreferences()
        UiPrefs.noteMirrorResult(sp, a, success = false, nowMillis = now)
        UiPrefs.noteMirrorResult(sp, a, success = false, nowMillis = now + 1)
        assertEquals(MirrorHealth(2, 0L), UiPrefs.readMirrorHealth(sp)[a])
        UiPrefs.noteMirrorResult(sp, a, success = true, nowMillis = now + 2)
        assertEquals(MirrorHealth(0, now + 2), UiPrefs.readMirrorHealth(sp)[a])
    }

    @Test
    fun `失败保留最后成功时刻且计数封顶`() {
        val sp = FakeSharedPreferences()
        UiPrefs.noteMirrorResult(sp, a, true, 1000L)
        for (i in 1..(UiPrefs.MIRROR_FAILURE_CAP + 10)) {
            UiPrefs.noteMirrorResult(sp, a, false, 2000L + i)
        }
        val h = requireNotNull(UiPrefs.readMirrorHealth(sp)[a])
        assertEquals(UiPrefs.MIRROR_FAILURE_CAP, h.consecutiveFailures)
        assertEquals(1000L, h.lastSuccessMillis)
    }

    @Test
    fun `负时刻按 0 兜底`() {
        val sp = FakeSharedPreferences()
        UiPrefs.noteMirrorResult(sp, a, true, -5L)
        val h = requireNotNull(UiPrefs.readMirrorHealth(sp)[a])
        assertEquals(0L, h.lastSuccessMillis)
        assertEquals(0, h.consecutiveFailures)
    }

    @Test
    fun `表满时不收新地址但已有条目照常更新`() {
        val sp = FakeSharedPreferences()
        for (i in 1..UpdatePolicy.MAX_MIRRORS) {
            UiPrefs.noteMirrorResult(sp, "https://m$i.example/", false, now)
        }
        assertEquals(UpdatePolicy.MAX_MIRRORS, UiPrefs.readMirrorHealth(sp).size)
        UiPrefs.noteMirrorResult(sp, "https://fresh.example/", false, now)
        assertEquals(UpdatePolicy.MAX_MIRRORS, UiPrefs.readMirrorHealth(sp).size)
        assertFalse(UiPrefs.readMirrorHealth(sp).containsKey("https://fresh.example/"))
        UiPrefs.noteMirrorResult(sp, "https://m1.example/", true, now + 5)
        assertEquals(MirrorHealth(0, now + 5), UiPrefs.readMirrorHealth(sp)["https://m1.example/"])
    }

    @Test
    fun `非法地址不进健康表`() {
        val sp = FakeSharedPreferences()
        UiPrefs.noteMirrorResult(sp, "http://明文.example/", false, now)
        UiPrefs.noteMirrorResult(sp, "", false, now)
        UiPrefs.noteMirrorResult(sp, "https://no-dot-host", false, now)
        assertTrue(UiPrefs.readMirrorHealth(sp).isEmpty())
    }

    @Test
    fun `健康表编解码往返与坏行丢弃`() {
        val rows = linkedMapOf(a to MirrorHealth(3, 100L), b to MirrorHealth(0, 200L))
        assertEquals(rows, UiPrefs.decodeMirrorHealth(UiPrefs.encodeMirrorHealth(rows)))
        val raw = listOf(
            "$a\t2\t300",                      // 合法
            "$b\tx\t1",                        // 失败数非数
            "$b\t2",                           // 段数不对
            "\t1\t1",                          // 空 base
            "https://bad example/\t1\t1",      // base 带空白
            "$a\t9\t999",                      // 重复 base：取第一条
            "https://n.example/\t-5\t-8",      // 负数夹紧到非负
        ).joinToString("\n")
        val decoded = UiPrefs.decodeMirrorHealth(raw)
        assertEquals(MirrorHealth(2, 300L), decoded[a])
        assertFalse(decoded.containsKey(b))
        assertEquals(MirrorHealth(0, 0L), decoded["https://n.example/"])
        assertEquals(2, decoded.size)
    }

    @Test
    fun `写进 prefs 的健康度能喂回 core 把失联源降位`() {
        val sp = FakeSharedPreferences()
        repeat(UpdatePolicy.MIRROR_DEMOTE_FAILURES) {
            UiPrefs.noteMirrorResult(sp, a, false, now)
        }
        UiPrefs.noteMirrorResult(sp, b, true, now)
        val order = UpdatePolicy.mirrorOrderWithHealth(
            builtIn = listOf(a, b),
            health = UiPrefs.readMirrorHealth(sp),
            nowMillis = now,
        )
        assertEquals("app 侧记的失败数必须能让 core 把失联源降位", listOf(b, a), order)
    }

    @Test
    fun `检查一轮里每条源只记一次健康度`() {
        // 壳层跑不进纯 JVM（checkNow 走网络），这里钉源码面：U4 曾「先无条件记一次成功，再在验签
        // 分支里补一次失败」—— 成功把连续失败清零，于是每轮都失败的坏源永远停在 1 次、
        // 碰不到 MIRROR_DEMOTE_FAILURES 阈值，P1-7 要降位的正是这种源。
        val check = File(appMainSourceDir(), "UpdateManager.kt").readText()
            .substringAfter("fun checkNow(context: Context")
            .substringBefore("private fun fetchManifest")
        assertEquals(
            "取不到文本 / 取到文本两条出口各记一次，多一条就是重复记账",
            2,
            Regex("rememberMirrorHealth\\(context, mirror").findAll(check).count(),
        )
        assertTrue(
            "取到文本那一支必须走 core 判据源，不许写死成功",
            check.contains("success = UpdatePolicy.mirrorFetchHealthy"),
        )
        assertEquals(
            "checkNow 里不许再有恒成功的记账（那会先把降位抹平）",
            0,
            Regex("success = true").findAll(check).count(),
        )
    }

    /** 照 UpdateChannelReadoutTest 的口径：找不到源码目录就判红，不许判成跳过。 */
    private fun appMainSourceDir(): File {
        val fromModule = File("src/main/kotlin/com/eratw/port/android")
        if (fromModule.isDirectory) return fromModule
        val fromRepoRoot = File("app/src/main/kotlin/com/eratw/port/android")
        if (fromRepoRoot.isDirectory) return fromRepoRoot
        throw IllegalStateException("找不到 app main 源码目录（工作目录 = ${File(".").absolutePath}）")
    }
}
