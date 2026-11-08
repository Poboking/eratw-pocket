package com.eratw.port.platform.storage

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResourcePackStore] 包级画像的**真实形态/编码回归**（补 [ResourcePackStoreTest] 没覆盖到的组合）。
 *
 * 既有用例钉的是「各画像单信号」与安全闸；本文件钉四类组合边界：
 *
 * 1. **GBK 条目名 + 官方背景图包形态**：Windows 打包器按 OEM 代码页写非 ASCII 名、不置
 *    UTF-8 标志，[ResourcePackStore.openArchive] 的 GBK 回落与 [ResourcePackStore.detectLayout]
 *    的包裹层判定必须串起来工作（任一半失效都会落进乱码目录或全跳过）。
 * 2. **两层包裹仍是 STANDARD**：`normalizeEntry` 从**左起第一个** `resources` 段取相对路径，
 *    包裹层更深也不影响（与画像的「剥一层公共顶层」是两条独立规则）。
 * 3. **两信号共存**：裸 `背景图/` 与裸 `游戏bgm/` 同时出现时，判定顺序先命中背景图信号 ⇒
 *    整个包按 BACKGROUND_IMAGES 投放。这是**钉当前实现**，不是语义裁决（见该用例 KDoc 的
 *    TODO(verify)）。
 * 4. **认不出的三种空形态**：0 条目、坏 zip、纯目录条目。目录条目在画像第一趟就被排除
 *    （[ResourcePackStore.entryNames] 只收非目录名），所以纯目录包等价于空包；坏 zip 走
 *    `openArchive` 的异常折返。三者都必须是 `installed=0` + `UNRECOGNIZED_LAYOUT` 告警，
 *    不许静默成功。
 *
 * 装置与既有测试同口径：每个用例独立临时目录、`deleteOnExit` 清理。
 */
class ResourcePackStoreShapeTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    /** 与既有测试同口径的 zip 构造；[charset] 为 null 时用 JDK 默认（UTF-8 条目名）。 */
    private fun zipOf(entries: Map<String, String>, charset: Charset? = null): File {
        val file = File(tempDir("shape-pack"), "pack.zip")
        val zip = if (charset == null) {
            ZipOutputStream(file.outputStream())
        } else {
            ZipOutputStream(file.outputStream(), charset)
        }
        zip.use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    // ------------------------------------------------------------------
    // 1) GBK 条目名 + 官方背景图包形态
    // ------------------------------------------------------------------

    @Test
    fun `GBK 条目名的官方背景图包按画像投到 resources 背景图`() {
        val root = tempDir("shape-game-gbk")
        // 官方 BGI_Resource 包形态 + GitHub 下载多一层包裹目录，条目名按 GBK 写（不置 UTF-8 标志）。
        val pack = zipOf(
            linkedMapOf(
                "BGI_Resource-main/BGI.csv" to "通用浴室_0_0,00/1.webp\n",
                "BGI_Resource-main/00/1.webp" to "IMG",
                "BGI_Resource-main/通用/x.webp" to "IMG2",
            ),
            Charset.forName("GBK"),
        )

        val report = ResourcePackStore(root).install(pack)

        assertNull(report.error)
        // 画像与编码回落必须同时成功：GBK 没解对时条目名带 U+FFFD，会被安全闸逐条拒收。
        assertEquals(PackProfile.BACKGROUND_IMAGES, report.profile)
        assertEquals(3, report.installedFiles)
        assertEquals(0, report.skippedEntries)
        assertEquals(listOf("resources"), report.roots)
        assertEquals("IMG", File(root, "resources/背景图/00/1.webp").readText())
        // 关键：中文子目录按 GBK 正确还原（不是乱码目录名）
        assertEquals("IMG2", File(root, "resources/背景图/通用/x.webp").readText())
        // 根级 BGI.csv 落在索引目录根（scanStatus 靠它数行），且算「有索引」
        assertTrue(File(root, "resources/背景图/BGI.csv").isFile)
        assertFalse(report.structureNotes.any { it.kind == StructureNoteKind.IMAGES_WITHOUT_CSV_INDEX })
    }

    // ------------------------------------------------------------------
    // 2) 两层包裹 + STANDARD
    // ------------------------------------------------------------------

    @Test
    fun `两层包裹的资源包仍按 STANDARD 归一到左起第一个 resources 段`() {
        val root = tempDir("shape-game-deep")
        val pack = zipOf(mapOf("a/b/resources/背景图/x.webp" to "IMG"))

        val report = ResourcePackStore(root).install(pack)

        assertNull(report.error)
        assertEquals(PackProfile.STANDARD, report.profile)
        assertEquals(1, report.installedFiles)
        assertEquals("IMG", File(root, "resources/背景图/x.webp").readText())
        // 包裹层目录本身不落盘
        assertFalse(File(root, "a").exists())
    }

    // ------------------------------------------------------------------
    // 3) 两信号共存（钉当前行为）
    // ------------------------------------------------------------------

    /**
     * 裸 `背景图/` 与裸 `游戏bgm/` 同时在包里时，当前实现按 `detectLayout` 的判定顺序先命中
     * 背景图信号 ⇒ 整个包归 BACKGROUND_IMAGES，`游戏bgm/...` 条目被改投到
     * `resources/背景图/游戏bgm/...`（游戏不会把它当 BGM 播）。
     *
     * 这是**已知边界**，不是语义裁决：混装包到底该拆成两路投放（图片进画面、音乐进 sound）
     * 还是该拒绝并让玩家分包，属于产品口径，留给后续决策。
     * TODO(verify)：真实玩家的混装包样本 + 产品口径确认后，再决定是否改成分路投放。
     */
    @Test
    fun `裸背景图与裸游戏bgm 两信号共存时按判定顺序归 BACKGROUND_IMAGES`() {
        val root = tempDir("shape-game-both")
        val pack = zipOf(
            linkedMapOf(
                "背景图/BGI.csv" to "a,背景图/a.webp\n",
                "游戏bgm/博丽神社/0.MP3" to "AUDIO",
            ),
        )

        val report = ResourcePackStore(root).install(pack)

        assertNull(report.error)
        assertEquals(PackProfile.BACKGROUND_IMAGES, report.profile)
        assertEquals(2, report.installedFiles)
        assertEquals(listOf("resources"), report.roots)
        assertTrue(File(root, "resources/背景图/BGI.csv").isFile)
        // 当前行为：音乐条目被归到背景图目录下（不是 sound/游戏bgm/）
        assertEquals("AUDIO", File(root, "resources/背景图/游戏bgm/博丽神社/0.MP3").readText())
        assertFalse(File(root, "sound").exists())
        // 该分支不做 BGM_OUTSIDE_GAME_BGM 体检（只对 STANDARD/MIXED 取样）
        assertFalse(report.structureNotes.any { it.kind == StructureNoteKind.BGM_OUTSIDE_GAME_BGM })
    }

    // ------------------------------------------------------------------
    // 4) 认不出的三种空形态
    // ------------------------------------------------------------------

    @Test
    fun `空 zip 判 UNRECOGNIZED 且零落盘并点名`() {
        val root = tempDir("shape-game-empty")
        val pack = zipOf(emptyMap())

        val report = ResourcePackStore(root).install(pack)

        assertNull(report.error)
        assertEquals(PackProfile.UNRECOGNIZED, report.profile)
        assertEquals(0, report.installedFiles)
        assertEquals(0, report.skippedEntries)
        assertTrue(report.roots.isEmpty())
        val note = report.structureNotes.singleOrNull {
            it.kind == StructureNoteKind.UNRECOGNIZED_LAYOUT
        }
        assertNotNull(note)
        // 没有条目名可点名时 detail 回落成压缩包文件名（取证面）
        assertEquals("pack.zip", note!!.detail)
    }

    @Test
    fun `坏 zip 折成报告错误并点名 UNRECOGNIZED_LAYOUT`() {
        val root = tempDir("shape-game-bad")
        val bad = File(tempDir("shape-bad"), "bad.zip").apply { writeText("not a zip") }

        val report = ResourcePackStore(root).install(bad)

        assertNotNull(report.error)
        assertEquals(0, report.installedFiles)
        assertEquals(PackProfile.UNRECOGNIZED, report.profile)
        val note = report.structureNotes.singleOrNull {
            it.kind == StructureNoteKind.UNRECOGNIZED_LAYOUT
        }
        assertNotNull(note)
        assertEquals("bad.zip", note!!.detail)
    }

    @Test
    fun `纯目录条目 zip 不参与画像判定并判 UNRECOGNIZED`() {
        val root = tempDir("shape-game-dirs")
        // 目录条目（名以 / 结尾）在第一趟 entryNames 就被排除 ⇒ 等价于空包，画像不许靠它们认出来。
        val pack = zipOf(
            linkedMapOf(
                "resources/背景图/" to "",
                "sound/游戏bgm/博丽神社/" to "",
            ),
        )

        val report = ResourcePackStore(root).install(pack)

        assertNull(report.error)
        assertEquals(PackProfile.UNRECOGNIZED, report.profile)
        assertEquals(0, report.installedFiles)
        assertEquals(0, report.skippedEntries)
        val note = report.structureNotes.singleOrNull {
            it.kind == StructureNoteKind.UNRECOGNIZED_LAYOUT
        }
        assertNotNull(note)
        assertEquals("pack.zip", note!!.detail)
        // 目录条目只建文件不造空目录
        assertFalse(File(root, "resources").exists())
        assertFalse(File(root, "sound").exists())
    }
}
