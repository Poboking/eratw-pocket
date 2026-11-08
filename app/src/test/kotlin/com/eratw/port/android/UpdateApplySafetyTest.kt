package com.eratw.port.android

import android.content.SharedPreferences
import com.eratw.port.core.update.DataArtifactKind
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * C-2 / C-4 / C-8 的定向单测：**应用前复检**、**放弃记账**、**清指针竞态**。
 *
 * 这三条都是「下载完成」与「下次冷启应用」之间那条时间缝隙上的坑，判据全部抽成纯函数/
 * 纯落盘函数，于是这里不需要 Context：
 * - C-2：暂存包的大小与基线在应用前再核一次（盘上这件还是不是登记的那件、差量包还停不停在
 *   选件时的基线上）；
 * - C-4：放弃一版数据包时记「版本+档位」，让下一轮选件挡板生效，不再无限重下；
 * - C-8：清指针只清自己处理的那份，并发登记进来的新指针必须留着。
 */
class UpdateApplySafetyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val pathKey = "staged_assets_path"
    private val versionKey = "staged_assets_version"
    private val attemptsKey = "apply_attempts"
    private val sizeKey = "staged_assets_size"
    private val shaKey = "staged_assets_sha256"
    private val baseKey = "staged_assets_base_version"

    // ------------------------------------------------------------------
    // C-4：give-up 记账
    // ------------------------------------------------------------------

    @Test
    fun `放弃记账把版本与档位落进拒装账`() {
        val sp = FakeSharedPreferences()
        assertTrue(
            "档位已知 ⇒ 必须记下",
            UpdateManager.recordGiveUpBundle(sp, 20260109L, DataArtifactKind.FILE_DELTA, 1_770_000_000_000L),
        )
        assertEquals(
            RejectedBundleAccount(20260109L, DataArtifactKind.FILE_DELTA, 1_770_000_000_000L),
            UiPrefs.readRejectedBundleAccount(sp),
        )
    }

    @Test
    fun `档位探不出来时不写假账`() {
        val sp = FakeSharedPreferences()
        assertFalse(UpdateManager.recordGiveUpBundle(sp, 20260109L, null, 1L))
        assertFalse(UpdateManager.recordGiveUpBundle(sp, 0L, DataArtifactKind.FULL, 1L))
        assertNull("没探出档位就不该有一条账去拦选件", UiPrefs.readRejectedBundleAccount(sp))
    }

    // ------------------------------------------------------------------
    // C-2：应用前复检
    // ------------------------------------------------------------------

    @Test
    fun `差量包基线与盘上不符才丢弃`() {
        assertTrue("差量包、盘上已是别的版本 ⇒ 丢", UpdateManager.stagedBaseReject(isDelta = true, installedVersion = 7L, baseVersion = 6L))
        assertFalse("差量包、盘还停在基线 ⇒ 照常应用", UpdateManager.stagedBaseReject(isDelta = true, installedVersion = 6L, baseVersion = 6L))
    }

    @Test
    fun `全量包不要求基线`() {
        assertFalse(
            "全量包相对任何一版都不拼，基线不参与判定",
            UpdateManager.stagedBaseReject(isDelta = false, installedVersion = 7L, baseVersion = 0L),
        )
    }

    @Test
    fun `暂存大小不符才丢弃而旧指针不记大小不拦`() {
        assertTrue(UpdateManager.stagedSizeReject(registeredSize = 1024L, actualSize = 1023L))
        assertFalse(UpdateManager.stagedSizeReject(registeredSize = 1024L, actualSize = 1024L))
        assertFalse("老版本登记的指针没记过大小 ⇒ 不拦（兼容旧指针）", UpdateManager.stagedSizeReject(registeredSize = 0L, actualSize = 512L))
    }

    @Test
    fun `包内实况认得出差量档`() {
        assertTrue(
            "带 update-delta.txt ⇒ 文件级差量",
            UpdateManager.stagedBundleIsDelta(bundle("CSV/a.txt" to "A", "update-delta.txt" to "CSV/dead.txt\n")) == true,
        )
        assertTrue(
            "带 update-blocks 条目 ⇒ 块级差量",
            UpdateManager.stagedBundleIsDelta(bundle("update-blocks/000.patch" to "PATCH")) == true,
        )
        assertFalse(
            "什么都不带 ⇒ 全量包",
            UpdateManager.stagedBundleIsDelta(bundle("CSV/a.txt" to "A")) == true,
        )
        assertNull("连归档都打不开 ⇒ null，交给 applyBundle 的抛错面", UpdateManager.stagedBundleIsDelta(temp.newFile()))
    }

    // ------------------------------------------------------------------
    // C-8：清指针竞态
    // ------------------------------------------------------------------

    @Test
    fun `clearStaged 只清自己那份指针`() {
        val sp = FakeSharedPreferences()
        sp.edit()
            .putString(pathKey, "/tmp/new.zip")
            .putLong(versionKey, 9L)
            .putInt(attemptsKey, 2)
            .putLong(sizeKey, 3L)
            .putString(shaKey, "s")
            .putLong(baseKey, 8L)
            .apply()

        assertFalse("并发登记的新一份必须留着", UpdateManager.clearStaged(sp, "/tmp/old.zip"))
        assertEquals("并发登记的新一份必须留着", "/tmp/new.zip", sp.getString(pathKey, ""))
        assertEquals(9L, sp.getLong(versionKey, 0L))
        assertEquals("新指针的重试次数也不能被清", 2, sp.getInt(attemptsKey, 0))

        assertTrue("自己那份才清", UpdateManager.clearStaged(sp, "/tmp/new.zip"))
        assertNull("自己那份才清", sp.getString(pathKey, null))
        assertFalse(sp.contains(versionKey))
        assertFalse(sp.contains(attemptsKey))
        assertFalse("C-2 的三个新键一起清", sp.contains(sizeKey))
        assertFalse(sp.contains(shaKey))
        assertFalse(sp.contains(baseKey))
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun bundle(vararg entries: Pair<String, String>): File =
        zipTo(temp.newFile(), entries.map { (name, text) -> name to text.toByteArray(Charsets.UTF_8) })

    /** STORED 条目：长度与 CRC 显式给出，ZipAssetTree 才拿得到确定的解压后字节数。 */
    private fun zipTo(target: File, entries: List<Pair<String, ByteArray>>): File {
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
                out.putNextEntry(entry)
                out.write(bytes)
                out.closeEntry()
            }
        }
        return target
    }

    /** 内存版 SharedPreferences：apply() 同步落进 map（照 UpdateRejectedAccountTest 的桩口径）。 */
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
}
