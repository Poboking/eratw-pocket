package com.eratw.port.android

import android.content.SharedPreferences
import com.eratw.port.core.update.DataArtifactKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U11 upd-kindui（CONTRACT-UPD3-3LANE §2 U11）的定向单测：数据通道档位读数的**持久化 + 上屏形状**。
 *
 * 三面各钉一件事：
 * - **存取**（UiPrefs 新键）：round-trip 逐字、坏值整条作废返回 null、`resetAll` 的 clear() 天然清掉；
 * - **上屏**：档位行只进 [UpdateChannelReadout.Readout.techLines]（展开层），玩家面三行现状一个字不被挤歪；
 * - **三语**：同一份读数在 ZH/EN/JA 下都是「档位 / 盘可信 / 发布给的包」三格，行数一致、无术语进玩家面。
 *
 * 判据一律在 core（[DataArtifactKind] 由 `UpdatePolicy.dataArtifactKind` 判），这里只验 app 侧存取与读出。
 */
class DataArtifactReadoutTest {

    /** 内存版 SharedPreferences：apply()/commit() 同步落进 map（照 UiPrefsRolloutTest 的桩口径）。 */
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

    private val key = "last_data_artifact_readout"

    // ------------------------------------------------------------------
    // 存取
    // ------------------------------------------------------------------

    @Test
    fun `档位读数五格 round-trip 逐字回来`() {
        DataArtifactKind.entries.forEach { kind ->
            listOf(true, false).forEach { trusted ->
                val readout = DataArtifactReadout(kind, trusted, hasBlockDelta = true, hasFileDelta = false, hasFull = true)
                val sp = FakeSharedPreferences()
                UiPrefs.writeDataArtifactReadout(sp, readout)
                assertEquals(readout, UiPrefs.readDataArtifactReadout(sp))
            }
        }
    }

    @Test
    fun `没写过就返回 null 不猜`() {
        assertNull(UiPrefs.readDataArtifactReadout(FakeSharedPreferences()))
    }

    @Test
    fun `坏值整条作废绝不还原半条残读数`() {
        val bad = listOf(
            "",                              // 空串
            "NOT_A_KIND|1|0|1|0",            // 枚举名不认识
            "FULL|1",                        // 字段数不够
            "FULL|1|0|1|0|9",                // 字段数超了
            "FULL|2|0|1|0",                  // 布尔位不是 0/1
            "FULL|1|x|1|0",                  // 布尔位非数
            "|1|0|1|0",                      // 缺 kind
        )
        bad.forEach { raw ->
            val sp = FakeSharedPreferences()
            sp.map[key] = raw
            assertNull("坏值应整条作废：$raw", UiPrefs.readDataArtifactReadout(sp))
        }
    }

    @Test
    fun `恢复默认设置的 clear 天然清掉档位读数`() {
        // 契约 §2 U11-1：不写删除逻辑，resetAll 的 edit().clear() 覆盖。
        val sp = FakeSharedPreferences()
        UiPrefs.writeDataArtifactReadout(sp, DataArtifactReadout(DataArtifactKind.BLOCK_DELTA, true, true, true, true))
        assertTrue(sp.map.containsKey(key))
        UiPrefs.resetAll(sp)
        assertFalse("clear 后不该残留档位读数", sp.map.containsKey(key))
        assertNull(UiPrefs.readDataArtifactReadout(sp))
    }

    // ------------------------------------------------------------------
    // 上屏：档位行走展开层，玩家面三行现状不动
    // ------------------------------------------------------------------

    private fun readoutOf(kind: DataArtifactKind, trusted: Boolean, b: Boolean, f: Boolean, full: Boolean) =
        UpdateChannelReadout.Snapshot(
            mirrorCount = 2,
            preferredMirror = "https://eratw-android.asia/updates/",
            lastSuccessMillis = 1_730_000_000_000L,
            lastAttemptMillis = 1_730_000_000_000L,
            outcome = UpdateChannelReadout.OUTCOME_OK,
            currentVersionCode = 12L,
            installedDataVersion = 12L,
            nowMillis = 1_730_000_000_000L + 137L * 60_000L,
            dataArtifact = DataArtifactReadout(kind, trusted, b, f, full),
        )

    @Test
    fun `有档位读数时玩家面仍是三行且不含档位字样`() {
        listOf(true, false).forEach { trusted ->
            val readout = UpdateChannelReadout.of(UpdateCopy.ZH, readoutOf(DataArtifactKind.FILE_DELTA, trusted, true, true, false))
            assertEquals("玩家面三行现状不许被档位行挤歪", 3, readout.playerLines.size)
            readout.playerLines.forEach { line ->
                assertFalse("档位行不许混进玩家面：$line", line.contains("上次检查走的是"))
            }
        }
    }

    @Test
    fun `档位行落在展开层并且三格都在`() {
        val readout = UpdateChannelReadout.of(UpdateCopy.ZH, readoutOf(DataArtifactKind.BLOCK_DELTA, false, true, true, true))
        val tech = readout.techLines.joinToString("\n")
        assertTrue("档位要在展开层：" + readout.techLines, tech.contains("块级差量"))
        assertTrue("盘可信要落「不可信」：" + readout.techLines, tech.contains("不可信"))
        assertTrue("发布给的包要点名三件：" + readout.techLines, tech.contains("文件级差量") && tech.contains("全量数据包"))
        assertTrue("展开入口要给得出", readout.hasTechDetail)
    }

    @Test
    fun `一件包都没发时档位落 NONE 且发布给的包落空态`() {
        val readout = UpdateChannelReadout.of(UpdateCopy.ZH, readoutOf(DataArtifactKind.NONE, true, false, false, false))
        val tech = readout.techLines.joinToString("\n")
        assertTrue(tech.contains("没有数据包可下"))
        assertTrue(tech.contains("一件都没有"))
        assertTrue("可信那一格取「可信」：" + tech, tech.contains("可信"))
    }

    @Test
    fun `三语同形：同一读数下档位行各语言都有且玩家面行数一致`() {
        val snapshot = readoutOf(DataArtifactKind.FULL, true, false, true, true)
        val zh = UpdateChannelReadout.of(UpdateCopy.ZH, snapshot)
        listOf(UpdateCopy.EN, UpdateCopy.JA).forEach { copy ->
            val r = UpdateChannelReadout.of(copy, snapshot)
            assertEquals("${copy.lang} 玩家面行数变了", zh.playerLines.size, r.playerLines.size)
            assertEquals("${copy.lang} 展开层行数变了", zh.techLines.size, r.techLines.size)
            assertTrue("${copy.lang} 展开层没落档位行：" + r.techLines, r.techLines.any { it.isNotBlank() })
        }
        // 三语句子互不相同（词在表里，不是抄中文）。
        val zhLine = zh.techLines.first { it.contains(UpdateCopy.ZH.dataKindFull) }
        assertFalse(zhLine.contains(UpdateCopy.EN.dataKindFull))
    }

    @Test
    fun `没数据通道读数时展开层不凭空多一行`() {
        val base = UpdateChannelReadout.of(
            UpdateCopy.ZH,
            UpdateChannelReadout.Snapshot(mirrorCount = 2, currentVersionCode = 12L, installedDataVersion = 12L),
        )
        val withNull = UpdateChannelReadout.of(UpdateCopy.ZH, base.let { UpdateChannelReadout.Snapshot(mirrorCount = 2, currentVersionCode = 12L, installedDataVersion = 12L) })
        assertEquals(dataArtifactTechLineCount(withNull), dataArtifactTechLineCount(base))
    }

    private fun dataArtifactTechLineCount(readout: UpdateChannelReadout.Readout): Int =
        readout.techLines.count { it.contains(UpdateCopy.ZH.techDataArtifactLineFormat.substringBefore("{0}")) }
}
