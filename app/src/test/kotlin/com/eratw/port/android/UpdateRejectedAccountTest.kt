package com.eratw.port.android

import android.content.SharedPreferences
import com.eratw.port.core.update.Artifact
import com.eratw.port.core.update.AssetsChannel
import com.eratw.port.core.update.DataArtifactKind
import com.eratw.port.core.update.UpdateInfo
import com.eratw.port.core.update.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U13 upd-baccount 的定向单测：坏基线拒装的**记账（写侧）+ 挡板（读侧）+ 上屏形状**。
 *
 * 三面各钉一件事：
 * - **存取**（UiPrefs 新键族 `update_rejected_*`）：round-trip 逐字、坏值整条作废返回 null、
 *   `clearRejectedBundleState` 账与挡板读数一起摘、「恢复默认设置」的 clear() 天然清掉；
 * - **挡板**（[UpdateDownloadPlan.selectDataArtifactAvoidingRejected]）：无账/异键与普通选件
 *   逐字同结论，同键单步回落（块级→文件级→全量→暂不取），回落单调不摆动 —— 判定仍全走 core
 *   既有四参形，app 只把被拒档从候选摘掉；
 * - **上屏**：挡板说明行只进 [UpdateChannelReadout.Readout.techLines]（展开层），玩家面三行
 *   现状一个字不被挤歪；没生效就不加行；三语同形。
 *
 * 记账点唯一（只在拒装出口写）与清账点（应用成功 / 换版本暂存成功）本体吃 Context，测不到；
 * 这里钉 [UpdateManager.rememberRejectedBundle] / [UpdateManager.rememberRejectedFallback] 的
 * 注入层与 UiPrefs 的落盘形状。
 */
class UpdateRejectedAccountTest {

    /** 内存版 SharedPreferences：apply()/commit() 同步落进 map（照 DataArtifactReadoutTest 的桩口径）。 */
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

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
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

    private val rejectedKey = "update_rejected_bundle"
    private val fallbackKey = "update_rejected_fallback"

    // ------------------------------------------------------------------
    // 一、存取（写侧）
    // ------------------------------------------------------------------

    @Test
    fun `拒装账三格 round-trip 逐字回来`() {
        for (kind in DataArtifactKind.entries) {
            val sp = FakeSharedPreferences()
            val account = RejectedBundleAccount(20260109L, kind, 1_770_000_000_000L)
            UiPrefs.writeRejectedBundleAccount(sp, account)
            assertEquals("$kind：账要逐字回来", account, UiPrefs.readRejectedBundleAccount(sp))
        }
    }

    @Test
    fun `拒装账没写过就返回 null 不猜`() {
        assertNull(UiPrefs.readRejectedBundleAccount(FakeSharedPreferences()))
    }

    @Test
    fun `拒装账坏值整条作废绝不还原半条残账`() {
        val bads = listOf(
            "格子数不对" to "20260109|BLOCK_DELTA",
            "档位名不认识" to "20260109|NOT_A_KIND|1770000000000",
            "版本非正数" to "0|BLOCK_DELTA|1770000000000",
            "版本不是数" to "abc|BLOCK_DELTA|1770000000000",
            "时刻为负" to "20260109|FULL|-1",
            "时刻不是数" to "20260109|FULL|x",
            "空串" to "",
        )
        for ((label, raw) in bads) {
            val sp = FakeSharedPreferences()
            sp.edit().putString(rejectedKey, raw).apply()
            assertNull("$label：坏值必须整条作废", UiPrefs.readRejectedBundleAccount(sp))
        }
    }

    @Test
    fun `挡板读数两格 round-trip 逐字回来且 NONE 合法`() {
        for (kind in DataArtifactKind.entries) {
            val sp = FakeSharedPreferences()
            val fallback = RejectedBundleFallback(20260109L, kind)
            UiPrefs.writeRejectedBundleFallback(sp, fallback)
            assertEquals("$kind：读数要逐字回来", fallback, UiPrefs.readRejectedBundleFallback(sp))
        }
    }

    @Test
    fun `挡板读数坏值整条作废`() {
        val bads = listOf(
            "格子数不对" to "20260109",
            "档位名不认识" to "20260109|NOT_A_KIND",
            "版本非正数" to "0|FULL",
            "版本不是数" to "abc|FULL",
        )
        for ((label, raw) in bads) {
            val sp = FakeSharedPreferences()
            sp.edit().putString(fallbackKey, raw).apply()
            assertNull("$label：坏值必须整条作废", UiPrefs.readRejectedBundleFallback(sp))
        }
    }

    @Test
    fun `clearRejectedBundleState 把账与挡板读数一起摘`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeRejectedBundleAccount(sp, RejectedBundleAccount(20260109L, DataArtifactKind.FULL, 1L))
        UiPrefs.writeRejectedBundleFallback(sp, RejectedBundleFallback(20260109L, DataArtifactKind.FILE_DELTA))
        UiPrefs.clearRejectedBundleState(sp)
        assertNull("账要摘掉", UiPrefs.readRejectedBundleAccount(sp))
        assertNull("挡板读数要摘掉", UiPrefs.readRejectedBundleFallback(sp))
    }

    @Test
    fun `clearRejectedBundleFallback 只摘挡板读数不动账`() {
        val sp = FakeSharedPreferences()
        val account = RejectedBundleAccount(20260109L, DataArtifactKind.BLOCK_DELTA, 1L)
        UiPrefs.writeRejectedBundleAccount(sp, account)
        UiPrefs.writeRejectedBundleFallback(sp, RejectedBundleFallback(20260109L, DataArtifactKind.FULL))
        UiPrefs.clearRejectedBundleFallback(sp)
        assertEquals("账要留着（挡板还要靠它拦下一轮）", account, UiPrefs.readRejectedBundleAccount(sp))
        assertNull("挡板读数要摘掉", UiPrefs.readRejectedBundleFallback(sp))
    }

    @Test
    fun `恢复默认设置的 clear 天然清掉拒装账与挡板读数`() {
        val sp = FakeSharedPreferences()
        UiPrefs.writeRejectedBundleAccount(sp, RejectedBundleAccount(20260109L, DataArtifactKind.FILE_DELTA, 1L))
        UiPrefs.writeRejectedBundleFallback(sp, RejectedBundleFallback(20260109L, DataArtifactKind.NONE))
        sp.edit().clear().apply()
        assertNull(UiPrefs.readRejectedBundleAccount(sp))
        assertNull(UiPrefs.readRejectedBundleFallback(sp))
    }

    @Test
    fun `rememberRejectedBundle 落盘版本档位时刻三格`() {
        val sp = FakeSharedPreferences()
        UpdateManager.rememberRejectedBundle(sp, 20260109L, DataArtifactKind.BLOCK_DELTA, 1_770_000_000_000L)
        assertEquals(
            "记账函数要经 UiPrefs 注入层落盘",
            RejectedBundleAccount(20260109L, DataArtifactKind.BLOCK_DELTA, 1_770_000_000_000L),
            UiPrefs.readRejectedBundleAccount(sp),
        )
    }

    @Test
    fun `rememberRejectedFallback 落盘被拒版本与改走档位`() {
        val sp = FakeSharedPreferences()
        UpdateManager.rememberRejectedFallback(
            sp,
            RejectedBundleAccount(20260109L, DataArtifactKind.FILE_DELTA, 1L),
            DataArtifactKind.FULL,
        )
        assertEquals(
            RejectedBundleFallback(20260109L, DataArtifactKind.FULL),
            UiPrefs.readRejectedBundleFallback(sp),
        )
    }

    // ------------------------------------------------------------------
    // 二、挡板（读侧）
    // ------------------------------------------------------------------

    private val block = Artifact("eratw-assets-blockdelta-9.zip", 20_480L, "b".repeat(64))
    private val fileDelta = Artifact("eratw-assets-8to9.zip", 102_400L, "c".repeat(64))
    private val full = Artifact("eratw-assets-9.zip", 712_345_678L, "d".repeat(64))

    private fun info(
        baseVersion: Long,
        delta: Artifact? = null,
        blockDelta: Artifact? = null,
        fullArtifact: Artifact? = full,
        assetsChannel: AssetsChannel? = AssetsChannel(9L, full),
    ): UpdateInfo = UpdateInfo(
        format = 1,
        generated = "2026-11-05T00:00:00Z",
        note = "",
        apk = null,
        assets = assetsChannel,
        mirrors = emptyList(),
        warnings = emptyList(),
        assetsFull = fullArtifact,
        assetsDelta = delta,
        assetsBlockDelta = blockDelta,
        assetsBaseVersion = baseVersion,
    )

    @Test
    fun `无账时挡板与普通选件逐字同结论`() {
        val subjects = listOf(
            "只有全量" to info(UpdateManifest.NO_ASSETS_BASE_VERSION),
            "全量 + 文件级差量（够格）" to info(8L, delta = fileDelta),
            "全量 + 块级 + 文件级（都够格）" to info(8L, delta = fileDelta, blockDelta = block),
        )
        for (trusted in listOf(true, false)) {
            for (installed in listOf(0L, 8L, 9L)) {
                for ((label, subject) in subjects) {
                    val plain = UpdateDownloadPlan.selectDataArtifact(subject, installed, trusted)
                    val avoided = UpdateDownloadPlan.selectDataArtifactAvoidingRejected(
                        subject, installed, trusted, rejected = null,
                    )
                    assertEquals(
                        "$label @ installed=$installed trusted=$trusted：无账不许挪落点",
                        plain.artifact?.fileName, avoided.artifact?.fileName,
                    )
                    assertEquals("档位不许挪", plain.kind, avoided.kind)
                    assertNull("$label：没踩挡板就不许带 avoidedKind", avoided.avoidedKind)
                }
            }
        }
    }

    @Test
    fun `账异版本或异档位时照常选件不踩挡板`() {
        // 同一清单（目标版本 9、这次会选中块级档）：账版本不同 / 账档位不同都要照常放行。
        val subject = info(8L, delta = fileDelta, blockDelta = block)
        val cases = listOf(
            "版本不同" to RejectedBundleAccount(20260110L, DataArtifactKind.BLOCK_DELTA, 1L),
            "档位不同" to RejectedBundleAccount(9L, DataArtifactKind.FILE_DELTA, 1L),
        )
        for ((label, account) in cases) {
            val plain = UpdateDownloadPlan.selectDataArtifact(subject, 8L, true)
            val avoided = UpdateDownloadPlan.selectDataArtifactAvoidingRejected(subject, 8L, true, account)
            assertEquals("$label：异键要照常走原落点", plain.artifact?.fileName, avoided.artifact?.fileName)
            assertEquals("$label：异键档位不许挪", plain.kind, avoided.kind)
            assertNull("$label：没踩挡板就不许带 avoidedKind", avoided.avoidedKind)
        }
    }

    @Test
    fun `块级被拒单步回落文件级`() {
        val subject = info(8L, delta = fileDelta, blockDelta = block)
        val account = RejectedBundleAccount(9L, DataArtifactKind.BLOCK_DELTA, 1L)
        val selection = UpdateDownloadPlan.selectDataArtifactAvoidingRejected(subject, 8L, true, account)
        assertEquals("块级被拒要改走文件级", DataArtifactKind.FILE_DELTA, selection.kind)
        assertEquals("字节要落在文件级件上", fileDelta.fileName, selection.artifact?.fileName)
        assertEquals("挡板要留下被拒档的读数", DataArtifactKind.BLOCK_DELTA, selection.avoidedKind)
    }

    @Test
    fun `块级被拒且文件级不在清单时回落全量`() {
        val subject = info(8L, delta = null, blockDelta = block)
        val account = RejectedBundleAccount(9L, DataArtifactKind.BLOCK_DELTA, 1L)
        val selection = UpdateDownloadPlan.selectDataArtifactAvoidingRejected(subject, 8L, true, account)
        assertEquals(DataArtifactKind.FULL, selection.kind)
        assertEquals(full.fileName, selection.artifact?.fileName)
        assertEquals(DataArtifactKind.BLOCK_DELTA, selection.avoidedKind)
    }

    @Test
    fun `块级被拒且盘不可信时直接落全量不走文件级`() {
        // trusted=false 是既有口径（盘不可信差量一律不选）；回落借 core 同一条判定，不许另开小灶。
        val subject = info(8L, delta = fileDelta, blockDelta = block)
        val account = RejectedBundleAccount(9L, DataArtifactKind.BLOCK_DELTA, 1L)
        val selection = UpdateDownloadPlan.selectDataArtifactAvoidingRejected(subject, 8L, false, account)
        assertEquals(DataArtifactKind.FULL, selection.kind)
        assertEquals(full.fileName, selection.artifact?.fileName)
    }

    @Test
    fun `文件级被拒回落全量（盘可信也落全量）`() {
        // 夹具不带块级件：普通选件本来就是文件级，挡板才能命中 FILE_DELTA 键。
        val subject = info(8L, delta = fileDelta)
        val account = RejectedBundleAccount(9L, DataArtifactKind.FILE_DELTA, 1L)
        val selection = UpdateDownloadPlan.selectDataArtifactAvoidingRejected(subject, 8L, true, account)
        assertEquals("文件级被拒要改走全量", DataArtifactKind.FULL, selection.kind)
        assertEquals(full.fileName, selection.artifact?.fileName)
        assertEquals(DataArtifactKind.FILE_DELTA, selection.avoidedKind)
    }

    @Test
    fun `全量被拒暂不取数据包`() {
        val subject = info(8L)
        val account = RejectedBundleAccount(9L, DataArtifactKind.FULL, 1L)
        val selection = UpdateDownloadPlan.selectDataArtifactAvoidingRejected(subject, 8L, true, account)
        assertNull("没有下一档，这一趟不许再给件", selection.artifact)
        assertEquals(DataArtifactKind.NONE, selection.kind)
        assertEquals("挡板读数要记下被拒的是全量", DataArtifactKind.FULL, selection.avoidedKind)
    }

    @Test
    fun `回落单调：同版本反复检查停在同一处不摆动`() {
        val subject = info(8L, delta = fileDelta, blockDelta = block)
        val account = RejectedBundleAccount(9L, DataArtifactKind.BLOCK_DELTA, 1L)
        val first = UpdateDownloadPlan.selectDataArtifactAvoidingRejected(subject, 8L, true, account)
        val second = UpdateDownloadPlan.selectDataArtifactAvoidingRejected(subject, 8L, true, account)
        assertEquals("同一份账反复查要同结论", first.kind, second.kind)
        assertEquals(first.artifact?.fileName, second.artifact?.fileName)
        assertTrue(
            "回落后的档位不许再撞上被拒档",
            second.kind != account.kind && second.avoidedKind == account.kind,
        )
    }

    @Test
    fun `清单没发任何数据包时挡板不参与判定`() {
        // assets 整组缺席（线上没有这种清单，但 core 判式允许）：普通选件落 NONE，挡板对 NONE 不参与。
        val subject = info(8L, fullArtifact = null, assetsChannel = null)
        val account = RejectedBundleAccount(9L, DataArtifactKind.FULL, 1L)
        val plain = UpdateDownloadPlan.selectDataArtifact(subject, 8L, true)
        val avoided = UpdateDownloadPlan.selectDataArtifactAvoidingRejected(subject, 8L, true, account)
        assertEquals("没发数据包该没得选", DataArtifactKind.NONE, plain.kind)
        assertEquals("本来就没得选，档位不许被挡板挪动", plain.kind, avoided.kind)
        assertNull(avoided.avoidedKind)
    }

    @Test
    fun `只有旧键 assets 指向的全量被拒时挡板也拦得住`() {
        // assets-full 新键缺席、旧键 assets 还指着全量：普通选件落 FULL（core 的 fullDataArtifact
        // 会拿旧键承载）。同一版本这档被拒过 ⇒ 挡板照拦，暂不取数据包。
        val subject = info(8L, fullArtifact = null)
        val account = RejectedBundleAccount(9L, DataArtifactKind.FULL, 1L)
        val selection = UpdateDownloadPlan.selectDataArtifactAvoidingRejected(subject, 8L, true, account)
        assertNull("旧键全量被拒也要拦住", selection.artifact)
        assertEquals(DataArtifactKind.NONE, selection.kind)
        assertEquals(DataArtifactKind.FULL, selection.avoidedKind)
    }

    // ------------------------------------------------------------------
    // 三、上屏（展开层一行，玩家面三行不动）
    // ------------------------------------------------------------------

    private fun snapshotWith(fallback: RejectedBundleFallback?): UpdateChannelReadout.Snapshot =
        UpdateChannelReadout.Snapshot(
            mirrorCount = 2,
            currentVersionCode = 12L,
            installedDataVersion = 12L,
            rejectedFallback = fallback,
        )

    @Test
    fun `挡板生效时展开层多一行说明且玩家面仍三行`() {
        val readout = UpdateChannelReadout.of(
            UpdateCopy.ZH,
            snapshotWith(RejectedBundleFallback(20260109L, DataArtifactKind.FILE_DELTA)),
        )
        assertEquals("玩家面三行现状不许被挤歪", 3, readout.playerLines.size)
        val tech = readout.techLines.joinToString("\n")
        assertTrue("被拒说明要在展开层：" + readout.techLines, tech.contains("被拒收"))
        assertTrue("被拒版本要带上：$tech", tech.contains("20260109"))
        assertTrue("改走的档位要带上：$tech", tech.contains(UpdateCopy.ZH.dataKindFileDelta))
    }

    @Test
    fun `暂不取数据包走 skip 形态`() {
        val readout = UpdateChannelReadout.of(
            UpdateCopy.ZH,
            snapshotWith(RejectedBundleFallback(20260109L, DataArtifactKind.NONE)),
        )
        assertEquals(3, readout.playerLines.size)
        val tech = readout.techLines.joinToString("\n")
        assertTrue("没有下一档要讲「暂不取」：$tech", tech.contains("暂不取数据包"))
        assertFalse("暂不取形态不许套改走档位的词：$tech", tech.contains(UpdateCopy.ZH.dataKindFull))
    }

    @Test
    fun `没有挡板读数时展开层不凭空多一行`() {
        val zh = UpdateChannelReadout.of(UpdateCopy.ZH, snapshotWith(null))
        val en = UpdateChannelReadout.of(UpdateCopy.EN, snapshotWith(null))
        val ja = UpdateChannelReadout.of(UpdateCopy.JA, snapshotWith(null))
        for ((lang, r) in listOf("ZH" to zh, "EN" to en, "JA" to ja)) {
            assertFalse(
                "$lang：没挡板就不许有被拒行：" + r.techLines,
                r.techLines.any { it.contains("被拒收") || it.contains("rejected") || it.contains("拒否") },
            )
        }
        val zhWith = UpdateChannelReadout.of(
            UpdateCopy.ZH,
            snapshotWith(RejectedBundleFallback(20260109L, DataArtifactKind.FULL)),
        )
        assertEquals(
            "有读数与没读数只差一行",
            zh.techLines.size + 1,
            zhWith.techLines.size,
        )
    }

    @Test
    fun `挡板行与档位行可以同时在场各占一行`() {
        val readout = UpdateChannelReadout.of(
            UpdateCopy.ZH,
            UpdateChannelReadout.Snapshot(
                mirrorCount = 2,
                currentVersionCode = 12L,
                installedDataVersion = 12L,
                dataArtifact = DataArtifactReadout(
                    kind = DataArtifactKind.FILE_DELTA,
                    diskTrusted = false,
                    hasBlockDelta = true,
                    hasFileDelta = true,
                    hasFull = true,
                ),
                rejectedFallback = RejectedBundleFallback(20260109L, DataArtifactKind.FULL),
            ),
        )
        assertEquals(3, readout.playerLines.size)
        val tech = readout.techLines.joinToString("\n")
        assertTrue("档位行要在：$tech", tech.contains(UpdateCopy.ZH.techDataArtifactLineFormat.substringBefore("{0}")))
        assertTrue("挡板行要在：$tech", tech.contains("被拒收"))
    }

    @Test
    fun `三语同形：同一读数下挡板行各语言都有且行数一致`() {
        val snapshot = snapshotWith(RejectedBundleFallback(20260109L, DataArtifactKind.FULL))
        val zh = UpdateChannelReadout.of(UpdateCopy.ZH, snapshot)
        for (copy in listOf(UpdateCopy.EN, UpdateCopy.JA)) {
            val r = UpdateChannelReadout.of(copy, snapshot)
            assertEquals("${copy.lang} 玩家面行数变了", zh.playerLines.size, r.playerLines.size)
            assertEquals("${copy.lang} 展开层行数变了", zh.techLines.size, r.techLines.size)
            assertTrue(
                "${copy.lang} 展开层没落挡板行：" + r.techLines,
                r.techLines.any { it.contains("20260109") && it.isNotBlank() },
            )
        }
        val zhLine = zh.techLines.first { it.contains("20260109") }
        assertFalse("挡板行不许串语言", zhLine.contains(UpdateCopy.EN.techRejectedFallbackLineFormat.substringBefore("{0}")))
    }
}
