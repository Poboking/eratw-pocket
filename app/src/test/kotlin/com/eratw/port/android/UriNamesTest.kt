package com.eratw.port.android

import com.eratw.port.platform.save.SaveImport
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * R7-D1 任务②的对拍用例：[UriNames] 抽取自 MainActivity.modDisplayNameOf / modReadBounded
 * 与 SettingsActivity.displayNameOf / readBounded 两份私有实现，纯核心（回落链 / 带上限
 * 读取循环）在这里逐项对拍两侧行为——既有断言只挪家零放宽：
 *
 * * 回落链：MOD 导入链的 `fallback = null`（取不到返回 null，调用方折成读错误行）与
 *   存档导入的 `fallback = 文案`（取不到回落文案）共用同一条链。
 * * 读取循环：64 KiB 缓冲、「先累加、后判上限」（累加后超限即放弃整份，不返回部分数据）、
 *   读失败返回 null 的语义与两侧原实现逐字同序。
 *
 * Android 面（[UriNames.displayNameOf] / [UriNames.readBounded] 的 ContentResolver 薄壳）
 * 无法在 JVM 单测里构造，行为逐字沿用被替代实现（差异只在参数化，见 UriNames 类 KDoc）。
 */
class UriNamesTest {

    // ------------------------------------------------------------------
    // 回落链（displayNameOf 的纯核心）
    // ------------------------------------------------------------------

    @Test
    fun `回落链——uri 末段取斜杠后的段且无分隔符时原样`() {
        assertEquals("c.txt", UriNames.fallbackDisplayName("/a/b/c.txt", null))
        assertEquals("plain", UriNames.fallbackDisplayName("plain", null))
        assertEquals("c.txt", UriNames.fallbackDisplayName("/a/b/c.txt", "选中的文件"))
        assertEquals("plain", UriNames.fallbackDisplayName("plain", "选中的文件"))
    }

    @Test
    fun `回落链——MOD 导入形态取不到时返回 null`() {
        assertNull("MOD 导入链传 fallback=null：末段也没有就是 null（调用方折成读错误行）",
            UriNames.fallbackDisplayName(null, null))
    }

    @Test
    fun `回落链——存档导入形态末段也没有时回落文案`() {
        assertEquals("选中的文件", UriNames.fallbackDisplayName(null, "选中的文件"))
    }

    @Test
    fun `回落链——末段空串是有效值不触发回落（与两侧原实现逐字一致）`() {
        // 原实现形态：uri.lastPathSegment?.substringAfterLast('/') ?: fallback ——
        // lastPathSegment 为空串时 substringAfterLast 返回空串（非 null），回落不触发。
        assertEquals("", UriNames.fallbackDisplayName("", null))
        assertEquals("", UriNames.fallbackDisplayName("", "选中的文件"))
    }

    // ------------------------------------------------------------------
    // 带上限读取（readBounded 的纯核心）
    // ------------------------------------------------------------------

    @Test
    fun `读取——小于上限整份返回且字节逐位一致`() {
        val data = ByteArray(200) { (it % 251).toByte() }
        val out = UriNames.readBoundedBytes(ByteArrayInputStream(data), maxBytes = 1024)
        assertArrayEquals(data, out)
    }

    @Test
    fun `读取——恰好等于上限返回上限加一放弃`() {
        val data = ByteArray(128) { 7 }
        assertArrayEquals(data, UriNames.readBoundedBytes(ByteArrayInputStream(data), maxBytes = 128L))
        assertNull(UriNames.readBoundedBytes(ByteArrayInputStream(data), maxBytes = 127L))
    }

    @Test
    fun `读取——先累加后判序单个大块跨线也放弃且不返回部分数据`() {
        // 单块就跨线（块 64 KiB > 上限）：第一次累加即超限 ⇒ null，绝不吐半份。
        val huge = ByteArray(64 * 1024 + 16) { 1 }
        assertNull(UriNames.readBoundedBytes(ByteArrayInputStream(huge), maxBytes = 1024L))
    }

    @Test
    fun `读取——空流返回空数组`() {
        assertArrayEquals(ByteArray(0), UriNames.readBoundedBytes(ByteArrayInputStream(ByteArray(0)), maxBytes = 1L))
    }

    @Test
    fun `读取——存档档上限（8 MiB）超一字节被拒`() {
        // 按需产出的流：负向路径在累加超限那一刻放弃，不必真物化 8 MiB。
        assertNull(UriNames.readBoundedBytes(producingStream(SaveImport.MAX_SAVE_BYTES + 1L), SaveImport.MAX_SAVE_BYTES.toLong()))
    }

    @Test
    fun `读取——MOD 档上限（32 MiB）内小流整份通过`() {
        // 32 MiB 分界的负向需要物化 32 MiB，过重；分界逻辑与常量解耦（上限只是参数），
        // 已由上面的小参数用例钉死，这里只钉 MOD 档常量可作参数整份通过。
        val data = ByteArray(4096) { 3 }
        assertArrayEquals(data, UriNames.readBoundedBytes(ByteArrayInputStream(data), ModStore.MAX_MOD_BYTES))
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * 按需产出 [total] 字节的流（不真分配 [total] 字节）：用来钉大上限档的拒绝分界。
     * [read] 单字节形态与 [InputStream.read] 的默认批读都走这里。
     */
    private fun producingStream(total: Long): InputStream = object : InputStream() {
        var served = 0L
        override fun read(): Int = if (served < total) {
            served++
            served.toInt() and 0xFF
        } else {
            -1
        }
    }
}