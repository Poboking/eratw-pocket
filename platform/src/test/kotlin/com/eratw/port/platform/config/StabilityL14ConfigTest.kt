package com.eratw.port.platform.config

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * L14 稳定性：`emuera.config` 读入体积闸（[ConfigFileStore.MAX_CONFIG_BYTES]）。
 *
 * 钉的口径：
 *   * 超过上限 ⇒ [ConfigFileStore.load] 抛可读 [IOException]（点名字节数与上限、含绝对路径），
 *     **不截断、不调合并函数**；
 *   * [ConfigFileStore.save] 随之返回 false，原文件一个字节都不动、不留临时文件；
 *   * 恰好等于上限仍然可读（闸门是 `>`，不是 `>=`），避免把「上限」本身误杀。
 *
 * 装置的证伪方式：把 `load()` 里的 `length()` 判断删掉，前两条用例会从「抛/不调用」变成
 * 「正常读出」而红；把 `>` 改成 `>=`，第三条用例会红。
 */
class StabilityL14ConfigTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun storeIn(dir: File): ConfigFileStore = ConfigFileStore.inDirectory(dir)

    /** 按字节数铺满（固定 8 KiB 块，测试自身也不整份进堆）。 */
    private fun fill(file: File, size: Long) {
        FileOutputStream(file).use { out ->
            val chunk = ByteArray(8 * 1024) { 'A'.code.toByte() }
            var left = size
            while (left > 0L) {
                val n = minOf(left, chunk.size.toLong()).toInt()
                out.write(chunk, 0, n)
                left -= n
            }
        }
    }

    @Test
    fun `超过上限的配置 load 抛可读 IOException`() {
        val dir = temp.newFolder()
        val file = File(dir, ConfigFileStore.FILE_NAME)
        fill(file, ConfigFileStore.MAX_CONFIG_BYTES + 1)

        val e = assertThrows(IOException::class.java) { storeIn(dir).load() }

        val message = e.message!!
        assertTrue("要点名 oversized：$message", message.contains("过大"))
        assertTrue("要带上限读数：$message", message.contains("上限"))
        assertTrue("要带路径便于定位：$message", message.contains(file.absolutePath))
    }

    @Test
    fun `超过上限时 save 返回 false 且原文件一字不动`() {
        val dir = temp.newFolder()
        val file = File(dir, ConfigFileStore.FILE_NAME)
        fill(file, ConfigFileStore.MAX_CONFIG_BYTES + 1)
        val before = file.length()
        var mergeCalled = false

        val ok = storeIn(dir).save {
            mergeCalled = true
            "NEW KEY:1\n"
        }

        assertFalse("拒绝读取的档不能被当成「无原文」写回", ok)
        assertFalse("合并函数不应拿到这份文本", mergeCalled)
        assertEquals(before, file.length())
        assertEquals(
            "失败路径不能留下临时文件",
            listOf(ConfigFileStore.FILE_NAME),
            dir.listFiles()!!.map { it.name }.sorted(),
        )
    }

    @Test
    fun `恰好等于上限的配置仍可读`() {
        val dir = temp.newFolder()
        val file = File(dir, ConfigFileStore.FILE_NAME)
        fill(file, ConfigFileStore.MAX_CONFIG_BYTES)

        val loaded = storeIn(dir).load()

        assertTrue("闸门是严格大于，上限本身必须放行", loaded != null)
        assertEquals(ConfigFileStore.MAX_CONFIG_BYTES.toInt(), loaded!!.text.length)
    }
}
