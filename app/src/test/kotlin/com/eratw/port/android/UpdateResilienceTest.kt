package com.eratw.port.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    // 源码面接线钉（LoadPipeline / MainActivity / UpdateManager 是 Android 壳层，跑不进纯 JVM）
    // ------------------------------------------------------------------

    @Test
    fun `让位判定单一入口保留供空间预检 解包段不再整轮短路（接线钉）`() {
        // 两条 lane 合流后的口径：CONTRACT-FIX-P13-SUPPRESS 的 unpackSuppressed 仍是
        // MainActivity 空间预检的唯一判定入口（F1/F2/F3/F4 加固都在它里面），但
        // CONTRACT-BOOTUNPACK 已把「该拷什么」下沉到 GameAssetInstaller 的逐文件让位档，
        // ensureAssetsInstalled 不再拿它短路整轮 —— 配置补齐 / 出厂值迁移 / RT2 投递 /
        // 摘要维护在让位档下必须照常跑。旧钉的三条断言（suppress 短路 / report 判空）
        // 已被下面 BOOTUNPACK 的接线钉取代，这里只钉「入口还在」与「整轮照跑」。
        val src = appMainSourceDir().resolve("LoadPipeline.kt").readText()
        assertTrue(
            "空间预检的单一判定入口掉了：$src",
            src.contains("internal fun unpackSuppressed(gameRoot: File, bundledVersionCode: Long)"),
        )
        assertTrue(
            "解包必须无条件照跑（装载相位只让位该拷什么）",
            src.contains("val report = GameAssetInstaller("),
        )
    }

    @Test
    fun `让位前置必须确认盘上产物在位 缺件时放弃让位走解包自愈（F1 钉）`() {
        // marker/摘要完好而 erbc.v1 被玩家或清理工具删掉时，若仍让位则本轮一个字节不拷，
        // 装载每次启动死在「产物缺失」失败屏、重装同版 APK 都救不回 —— 判定函数里必须有
        // 在位读数、缺件放弃分支（自愈恢复），且结论是「版本更高」与「产物在位」的合取；
        // 在位清点与实例读口 artifactsOnDisk 同一份口径（不许再造第二份清单）。
        val src = appMainSourceDir().resolve("LoadPipeline.kt").readText()
        assertTrue(
            "让位前置的产物在位读数掉了：$src",
            src.contains("val artifactsPresent = artifactsOnDiskIn(gameRoot)"),
        )
        assertTrue(
            "产物缺失时必须放弃让位（走随包解包自愈）：$src",
            src.contains("放弃让位，走随包解包自愈"),
        )
        assertTrue(
            "让位结论必须是「版本更高」与「产物在位」的合取：$src",
            src.contains("return newerThanBundled && artifactsPresent"),
        )
        assertTrue(
            "产物在位清点必须与实例读口同源（artifactsOnDisk 委托 artifactsOnDiskIn）：$src",
            src.contains("fun artifactsOnDisk(): Boolean = artifactsOnDiskIn(storage.gameRoot())"),
        )
    }

    @Test
    fun `让位读数必须合并 marker 与 digest 两源（F2 钉）`() {
        // 应用段先落摘要、marker 最后改名且改名失败只记日志不抛 —— suppress 只读 marker 时，
        // marker 停旧版会让随包解包把新版语料覆盖回旧版，循环以同形复发。两源取大落在 core
        // 纯函数（UnpackSuppressPolicyTest），这里钉 suppress 消费点确实在用它。
        val src = appMainSourceDir().resolve("LoadPipeline.kt").readText()
        assertTrue(
            "两源合并读数掉了：$src",
            src.contains("UpdatePolicy.effectiveDiskDataVersion(markerVersion, digestVersion)"),
        )
        assertTrue(
            "digest 版本读数掉了：$src",
            src.contains("GameAssetInstaller.readDataDigest(gameRoot)?.version ?: 0L"),
        )
    }

    @Test
    fun `空间预检与解包必须复用同一份 suppress 判定（F3 钉）`() {
        // suppress 成立时本轮随包解包零拷贝，解包前的空间缺口不再构成硬停理由；判定必须调
        // LoadPipeline.unpackSuppressed 这一份函数，不许在预检里复制第二份判据逻辑。
        val src = appMainSourceDir().resolve("MainActivity.kt").readText()
        assertTrue(
            "预检没接单一 suppress 判定：$src",
            src.contains("LoadPipeline.unpackSuppressed(gameRoot, UpdateManager.currentVersionCode(this))"),
        )
        assertTrue(
            "suppress 时要跳过空间硬停：$src",
            src.contains("if (!suppressUnpack && missingBytes > 0L"),
        )
    }

    @Test
    fun `marker 读数必须过坏值对称化（F4 钉）`() {
        // marker 巨值会对任何未来 versionCode 恒让位，连换新 APK 的自愈路都关死 —— 读侧必须
        // 消费 core 的 sanitize（解析失败/负值/超界归 0，fail-open），不许就地裸 toLongOrNull。
        val src = appMainSourceDir().resolve("UpdateManager.kt").readText()
        assertTrue(
            "marker 坏值对称化没接上：$src",
            src.contains("UpdatePolicy.sanitizeDataVersionMarker("),
        )
    }

    /** 照 UiPrefsRolloutTest 的口径：找不到源码目录就判红，不许判成跳过。 */
    private fun appMainSourceDir(): File {
        val fromModule = File("src/main/kotlin/com/eratw/port/android")
        if (fromModule.isDirectory) return fromModule
        val fromRepoRoot = File("app/src/main/kotlin/com/eratw/port/android")
        if (fromRepoRoot.isDirectory) return fromRepoRoot
        error("找不到 app main 源码目录（工作目录 = ${File(".").absolutePath}）")
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
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private class FakeEditor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = HashMap<String?, Any?>()
        private val removals = HashSet<String?>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor { pending[key] = value; return this }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

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

    @Test
    fun `随包解包让位下沉到装载相位（接线钉）`() {
        // LoadPipeline 是 Android 壳层跑不进纯 JVM；这里钉源码面：4c20b57b9 的
        // 「整轮解包短路」已删除、盘上版本只作为事实传给解包器、digest/标记闭环段
        // 不再判空 report。逐文件让位的行为证据在 platform 的 GameAssetInstallerDeferTest，
        // 判据矩阵在 core 的 UnpackSuppressPolicyTest / BundledAssetNeedsCopyTest。
        val src = appMainSourceDir().resolve("LoadPipeline.kt").readText()
        // 判读只认**生效代码行**：裸 contains 会被注释掉的接线骗绿（11-15 的教训，
        // 与 BootUnpackWireTest.effectiveLines 同口径）。本用例原先被误关在
        // FakeSharedPreferences 类体里从未执行，修正结构后这条判据必须真的能判红。
        val effective = src.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() && !line.startsWith("//") &&
                    !line.startsWith("/*") && !line.startsWith("*")
            }
            .toList()
        val policyCall = "unpackSuppressedByNewerDiskData(diskDataVersion, dataVersionCode)"
        assertTrue(
            "让位与否仍须由 core 纯函数判（此处只用于日志，不用于短路整轮）",
            effective.any { it.contains(policyCall) },
        )
        assertTrue("盘上版本必须交给装载相位", effective.any { it == "diskDataVersion = diskDataVersion," })
        assertFalse("不许再用 suppress 把整轮解包短路", effective.any { it.startsWith("val report = if (suppressUnpack)") })
        assertFalse("report 恒非空，digest/标记闭环段不许再判空", effective.any { it.startsWith("if (report != null)") })
    }

}
