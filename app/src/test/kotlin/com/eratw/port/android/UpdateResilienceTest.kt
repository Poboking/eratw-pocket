package com.eratw.port.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U2 下载韧性的定向单测（CONTRACT-UPD1-3LANE §2 U2 出口判据，组长收口补钉）。
 *
 * 两件都按仓内既有做法走**纯 JVM**：FGS 时限/onDestroy 的处置矩阵本就是 [UpdateDownloadPlan]
 * 里的纯函数；失败单读写走 [UiPrefs] 的 `internal readFailedDownload(sp)`，注入内存版
 * SharedPreferences 测试桩（与 `UiPrefsPortraitTest` 同形），不碰 Context 的 Android 实现。
 *
 * 为什么必须钉：系统时限到点后多放/少放一把单飞闸，或失败单被坏值还原成一张会白跑几百 MiB
 * 的单，都是玩家侧看不见、只在真机上偶发的错；这里每条分支都得有红绿证据。
 */
class UpdateResilienceTest {

    // ------------------------------------------------------------------
    // FGS 时限兜底
    // ------------------------------------------------------------------

    @Test
    fun `dataSync 预算是 6 小时`() {
        assertEquals(6L * 60L * 60L * 1000L, UpdateDownloadPlan.DATA_SYNC_FGS_BUDGET_MILLIS)
    }

    @Test
    fun `API 34 及以下没有 onTimeout 回调 一个动作也不做`() {
        assertTrue(UpdateDownloadPlan.fgsTimeoutSteps(34, workerAlive = true).isEmpty())
        assertTrue(UpdateDownloadPlan.fgsTimeoutSteps(21, workerAlive = false).isEmpty())
    }

    @Test
    fun `API 35 起 worker 活着先取消再 stopSelf`() {
        assertEquals(
            listOf(UpdateDownloadPlan.FgsTimeoutStep.CancelWorker, UpdateDownloadPlan.FgsTimeoutStep.StopSelf),
            UpdateDownloadPlan.fgsTimeoutSteps(35, workerAlive = true),
        )
        assertEquals(
            listOf(UpdateDownloadPlan.FgsTimeoutStep.CancelWorker, UpdateDownloadPlan.FgsTimeoutStep.StopSelf),
            UpdateDownloadPlan.fgsTimeoutSteps(36, workerAlive = true),
        )
    }

    @Test
    fun `API 35 起 worker 已死只 stopSelf 不重复取消`() {
        assertEquals(
            listOf(UpdateDownloadPlan.FgsTimeoutStep.StopSelf),
            UpdateDownloadPlan.fgsTimeoutSteps(35, workerAlive = false),
        )
    }

    @Test
    fun `onDestroy 没持闸一律空转 不许误放别人的闸`() {
        assertTrue(UpdateDownloadPlan.onDestroyFallbackSteps(gateHeld = false, workerAlive = true).isEmpty())
        assertTrue(UpdateDownloadPlan.onDestroyFallbackSteps(gateHeld = false, workerAlive = false).isEmpty())
    }

    @Test
    fun `onDestroy 持闸必放闸 worker 活着先取消 且从不自己 stopSelf`() {
        assertEquals(
            listOf(UpdateDownloadPlan.FgsTimeoutStep.CancelWorker, UpdateDownloadPlan.FgsTimeoutStep.ReleaseGate),
            UpdateDownloadPlan.onDestroyFallbackSteps(gateHeld = true, workerAlive = true),
        )
        val deadWorker = UpdateDownloadPlan.onDestroyFallbackSteps(gateHeld = true, workerAlive = false)
        assertEquals(listOf(UpdateDownloadPlan.FgsTimeoutStep.ReleaseGate), deadWorker)
        assertTrue(UpdateDownloadPlan.FgsTimeoutStep.StopSelf !in deadWorker)
    }

    @Test
    fun `系统时限路的 onDestroy 不许提前放闸 闸归 worker 收尾`() {
        // L4-11：onTimeout 的 stopSelf 之后 onDestroy 紧跟着来，worker 还在 interrupt 生效前的
        // 读阻塞里。此时提前放闸 = 下一单可与旧 worker 同写一个 .part。
        val timedOutAlive = UpdateDownloadPlan.onDestroyFallbackSteps(
            gateHeld = true,
            workerAlive = true,
            systemTimedOut = true,
        )
        assertEquals(listOf(UpdateDownloadPlan.FgsTimeoutStep.CancelWorker), timedOutAlive)
        assertTrue(
            "系统时限路不许在 onDestroy 里放闸（会与未收尾的 worker 双开）",
            UpdateDownloadPlan.FgsTimeoutStep.ReleaseGate !in timedOutAlive,
        )
        // worker 已死还持闸 = 记账错位：补放一次（幂等），死闸绝不能带进下一单。
        assertEquals(
            listOf(UpdateDownloadPlan.FgsTimeoutStep.ReleaseGate),
            UpdateDownloadPlan.onDestroyFallbackSteps(gateHeld = true, workerAlive = false, systemTimedOut = true),
        )
        // 非时限路行为逐字不变（默认参数 = false）。
        assertEquals(
            listOf(UpdateDownloadPlan.FgsTimeoutStep.CancelWorker, UpdateDownloadPlan.FgsTimeoutStep.ReleaseGate),
            UpdateDownloadPlan.onDestroyFallbackSteps(gateHeld = true, workerAlive = true, systemTimedOut = false),
        )
        assertTrue(UpdateDownloadPlan.onDestroyFallbackSteps(gateHeld = false, workerAlive = true, systemTimedOut = true).isEmpty())
    }

    // ------------------------------------------------------------------
    // 失败单持久化
    // ------------------------------------------------------------------

    private val record = FailedDownloadRecord(
        fileName = "eratw-0.2.40+17-abcdef0-full-deadbeef.apk",
        sizeBytes = 778_451_234L,
        sha256Hex = "a".repeat(64),
        kind = UpdateDownloadKind.Apk,
        assetsVersion = 0L,
    )

    @Test
    fun `失败单五元组写入后原样读出`() {
        val sp = FakeSharedPreferences()
        assertNull(UiPrefs.readFailedDownload(sp))

        UiPrefs.writeFailedDownload(sp, record)
        assertEquals(record, UiPrefs.readFailedDownload(sp))

        UiPrefs.clearFailedDownload(sp)
        assertNull(UiPrefs.readFailedDownload(sp))
    }

    @Test
    fun `数据包那一单的 assetsVersion 也随单还原`() {
        val sp = FakeSharedPreferences()
        val assets = record.copy(
            fileName = "eratw-assets-delta-9.zip",
            kind = UpdateDownloadKind.Assets,
            assetsVersion = 9L,
        )
        UiPrefs.writeFailedDownload(sp, assets)
        assertEquals(assets, UiPrefs.readFailedDownload(sp))
        assertEquals(9L, UiPrefs.readFailedDownload(sp)?.assetsVersion)
    }

    @Test
    fun `五个键缺一整单作废`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeFailedDownload(sp, record)
        val keyNames = listOf(
            "update_failed_file_name", "update_failed_size_bytes", "update_failed_sha256",
            "update_failed_kind", "update_failed_assets_version",
        )
        for (key in keyNames) {
            val copy = FakeSharedPreferences().also { UiPrefs.writeFailedDownload(it, record) }
            copy.map.remove(key)
            assertNull("缺 $key 应整单作废", UiPrefs.readFailedDownload(copy))
        }
        assertEquals("原桩不应被上面的副本影响", record, UiPrefs.readFailedDownload(sp))
    }

    @Test
    fun `坏值整单作废 非法文件名 非正字节数 未知通道 空白摘要`() {
        val badNames = listOf("../escape.apk", "-flag.apk", "", "a b.apk")
        for (name in badNames) {
            val sp = FakeSharedPreferences()
            UiPrefs.writeFailedDownload(sp, record.copy(fileName = name))
            assertNull("文件名 $name 应作废", UiPrefs.readFailedDownload(sp))
        }
        val zero = FakeSharedPreferences()
        UiPrefs.writeFailedDownload(zero, record.copy(sizeBytes = 0L))
        assertNull(UiPrefs.readFailedDownload(zero))

        val badKind = FakeSharedPreferences()
        UiPrefs.writeFailedDownload(badKind, record)
        badKind.map["update_failed_kind"] = "Unknown"
        assertNull(UiPrefs.readFailedDownload(badKind))

        val blankSha = FakeSharedPreferences()
        UiPrefs.writeFailedDownload(blankSha, record.copy(sha256Hex = "   "))
        assertNull(UiPrefs.readFailedDownload(blankSha))
    }

    @Test
    fun `失败单摘要必须过形状校验 坏形状作废 大小写归一`() {
        // L4-10：只判 isBlank 的旧口径会把「长度/字符不对的摘要」还原成一张必败的单：
        // 下载把整包下完、digest 在最后一字节才不符，每条镜像都白下一次几百 MiB。
        val badShapes = listOf("abc", "z".repeat(64), "a".repeat(63), "a".repeat(65), "a".repeat(64) + "-")
        for (bad in badShapes) {
            val sp = FakeSharedPreferences()
            UiPrefs.writeFailedDownload(sp, record.copy(sha256Hex = bad))
            assertNull("摘要 '$bad' 应整单作废", UiPrefs.readFailedDownload(sp))
        }
        // 形状合法的大小写混写要归一成小写（下载侧比较的是小写 hex）。
        val upper = FakeSharedPreferences()
        UiPrefs.writeFailedDownload(upper, record.copy(sha256Hex = "A".repeat(64)))
        assertEquals("a".repeat(64), UiPrefs.readFailedDownload(upper)?.sha256Hex)
    }

    @Test
    fun `清失败单把这五个键全摘掉 不碰别的键`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeFailedDownload(sp, record)
        sp.map["some_other_pref"] = "keep"
        UiPrefs.clearFailedDownload(sp)
        assertTrue(sp.map.keys.none { it.startsWith("update_failed_") })
        assertEquals("keep", sp.map["some_other_pref"])
    }

    // ------------------------------------------------------------------
    // 内存版 SharedPreferences（与 UiPrefsPortraitTest 同形，独立一份避免跨目录依赖）
    // ------------------------------------------------------------------

    private class FakeSharedPreferences : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return map[key] as? MutableSet<String> ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
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

            override fun putString(key: String?, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { removals.add(key); return this }
            override fun clear(): SharedPreferences.Editor {
                pending.clear(); removals.clear(); map.clear(); return this
            }
            override fun commit(): Boolean { flush(); return true }
            override fun apply() = flush()
            private fun flush() {
                for (k in removals) if (k != null) map.remove(k)
                for ((k, v) in pending) if (k != null) map[k] = v
                pending.clear(); removals.clear()
            }
        }
    }
}
