package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [UpdateManager.seedBundledDataVersion] 的定向单测（2026-11-06 主人反馈的幻影更新窗）。
 *
 * 钉的是「装完最新整包还弹数据更新」这一条的根因：随包解包从不写 `update/data-version.txt`，
 * 盘上读数停在 0/旧版，而清单的 assets-version 缺省等于整包 versionCode（`emit_update.py:2514-2516`）
 * ⇒ 每次检查都判成「有数据更新」。这里只测抬版本这一拍（纯 JVM、临时目录、不碰 Android 运行时）。
 */
class UpdateBundledDataVersionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val marker = "update/data-version.txt"

    private fun markerFile(root: File) = File(root, marker)

    @Test
    fun `随包解包把盘上数据版本抬到整包的 versionCode`() {
        val root = tmp.newFolder("game-root")
        assertEquals(0L, UpdateManager.installedDataVersion(root))

        UpdateManager.seedBundledDataVersion(root, 17L)

        assertEquals(17L, UpdateManager.installedDataVersion(root))
    }

    @Test
    fun `已经应用过更高版本的数据包时不回拨`() {
        val root = tmp.newFolder("game-root")
        markerFile(root).apply {
            parentFile?.mkdirs()
            writeText("20")
        }

        UpdateManager.seedBundledDataVersion(root, 17L)

        assertEquals("装更早的整包不该把数据读数往回拨", 20L, UpdateManager.installedDataVersion(root))
    }

    @Test
    fun `同版本重复落标记不改变读数`() {
        val root = tmp.newFolder("game-root")
        UpdateManager.seedBundledDataVersion(root, 17L)

        UpdateManager.seedBundledDataVersion(root, 17L)

        assertEquals(17L, UpdateManager.installedDataVersion(root))
    }

    @Test
    fun `读不出整包版本时一个字节都不写`() {
        val root = tmp.newFolder("game-root")

        UpdateManager.seedBundledDataVersion(root, 0L)
        UpdateManager.seedBundledDataVersion(root, -1L)

        assertFalse("versionCode 读不出时不能凭空声明数据版本", markerFile(root).exists())
    }
}
