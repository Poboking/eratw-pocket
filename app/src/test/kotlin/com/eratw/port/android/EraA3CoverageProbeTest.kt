package com.eratw.port.android

import com.eratw.port.core.host.ResourceContents
import com.eratw.port.core.host.ResourceFileHost
import com.eratw.port.core.host.SoundResourcePaths
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.image.PngImage
import com.eratw.port.core.mod.EraA3AssetOverlay
import com.eratw.port.core.mod.ModDx3AssetMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ERA-A3 二期 B：四类素材（背景图 / CG / 立绘 / BGM）各一条覆盖探针，全部走**真消费面**。
 *
 * - 背景图 / CG / 立绘 → [ResourceContents.load] 的 [EraA3AssetOverlay.AssetMap] 重载，
 *   观感落在 `SpritePool.find(name).buffer.fileSource`（渲染侧据它按路径解位图，
 *   `SpriteBitmapSource.kt:278`）——这是官方 CSV 资源表消费面的真实读数，不是侧车自身读写。
 * - BGM → [SoundResourcePaths.resolveInGameRoot] 的覆盖重载，观感落在返回 File 的绝对路径。
 *
 * 反向证明：每条探针都断言「映射关掉（[EraA3AssetOverlay.AssetMap.EMPTY]）→ 旧资源名/官方路径」，
 * 与「映射打开 → mod 路径」成对；若把覆盖分支摘掉，后一半断言即红。
 */
class EraA3CoverageProbeTest {

    /** 假宿主：提供一行 CSV + 两份图源的文件头尺寸（登记期不读字节，见 ResourceContents 类 KDoc）。 */
    private class CsvHost(
        private val body: String,
        private val sizes: MutableMap<String, IntArray>,
    ) : ResourceFileHost {
        val probed = ArrayList<String>()
        override fun listCsvFiles(): List<String> = listOf("resources/probe.csv")
        override fun readBytes(path: String): ByteArray? =
            if (path == "resources/probe.csv") body.toByteArray(Charsets.UTF_8) else ByteArray(0)
        override fun imageSize(path: String, bytes: ByteArray?): IntArray? {
            probed += path
            return sizes[path]?.copyOf()
        }
        override fun decode(path: String, bytes: ByteArray): PngImage? = null
    }

    private fun overlayFor(vararg entries: ModDx3AssetMap.Entry): EraA3AssetOverlay.AssetMap {
        val sidecar = ModDx3AssetMap.decode(ModDx3AssetMap.encode("kojo-probe01", entries.toList()))
        assertTrue("侧车应合法：${sidecar.diagnostics}", sidecar.ok)
        return EraA3AssetOverlay.collect(listOf("kojo-probe01")) { sidecar }
    }

    private fun spriteEntry(official: String, mod: String) =
        ModDx3AssetMap.Entry(ModDx3AssetMap.Kind.SPRITE, official, mod)

    /**
     * 建宿主：官方图 10x10、mod 图 20x20（读数可分辨）。CSV 只含一行，
     * 第二列是官方图在 resources 下的相对名（含 '.' 过形态闸）；覆盖映射把它改指向 mod 图。
     * [giveModSize] = false 模拟「声明了覆盖但目标文件打不开」的 fail-closed 分支。
     */
    private fun makeHost(
        officialSprite: String,
        officialPath: String,
        modPath: String,
        giveModSize: Boolean = true,
    ): CsvHost {
        val sizes = LinkedHashMap<String, IntArray>()
        sizes[officialPath] = intArrayOf(10, 10)
        if (giveModSize) sizes[modPath] = intArrayOf(20, 20)
        val file = officialPath.removePrefix("resources/")
        return CsvHost("$officialSprite,$file", sizes)
    }

    private fun assertSpriteUses(
        officialSprite: String,
        officialPath: String,
        modPath: String,
        overlay: EraA3AssetOverlay.AssetMap,
        expectPath: String,
        expectWidth: Int,
    ) {
        val host = makeHost(officialSprite, officialPath, modPath)
        val pool = SpritePool()
        val report = ResourceContents.load(pool, host, overlay)
        assertTrue("装载不该有告警：$officialSprite ${report.warnings}", report.warnings.isEmpty())
        val sprite = pool.find(officialSprite)
        assertNotNull("官方 sprite 名 $officialSprite 必须仍可查（换皮不改脚本）", sprite)
        assertEquals(expectPath, sprite!!.buffer!!.fileSource)
        assertEquals(expectWidth, sprite.width)
    }

    @Test
    fun `背景图覆盖探针走真消费面且映射关掉回落官方`() {
        val official = "BGI_BATH_0"
        val officialPath = "resources/背景图/bath.png"
        val modPath = "mods/rt2/kojo-probe01.assets/resources/背景图/bath-custom.png"
        val overlay = overlayFor(spriteEntry(official, "resources/背景图/bath-custom.png"))

        assertSpriteUses(official, officialPath, modPath, overlay, modPath, 20)
        assertSpriteUses(official, officialPath, modPath, EraA3AssetOverlay.AssetMap.EMPTY, officialPath, 10)
    }

    @Test
    fun `CG覆盖探针走真消费面且映射关掉回落官方`() {
        val official = "CG_EVENT_1"
        val officialPath = "resources/artist/cg.png"
        val modPath = "mods/rt2/kojo-probe01.assets/resources/artist/cg-custom.png"
        val overlay = overlayFor(spriteEntry(official, "resources/artist/cg-custom.png"))

        assertSpriteUses(official, officialPath, modPath, overlay, modPath, 20)
        assertSpriteUses(official, officialPath, modPath, EraA3AssetOverlay.AssetMap.EMPTY, officialPath, 10)
    }

    @Test
    fun `立绘覆盖探针走真消费面且映射关掉回落官方`() {
        // 立绘 XRAY 图层资源同样走 CSV 资源表（RECON §1.3）：sprite 名即图层资源名。
        val official = "CHARA_01_BASE"
        val officialPath = "resources/汎用立绘/base.png"
        val modPath = "mods/rt2/kojo-probe01.assets/resources/汎用立绘/base-custom.png"
        val overlay = overlayFor(spriteEntry(official, "resources/汎用立绘/base-custom.png"))

        assertSpriteUses(official, officialPath, modPath, overlay, modPath, 20)
        assertSpriteUses(official, officialPath, modPath, EraA3AssetOverlay.AssetMap.EMPTY, officialPath, 10)
    }

    @Test
    fun `坏侧车与打不开目标都 fail-closed 回落官方`() {
        val official = "BGI_BATH_0"
        val officialPath = "resources/背景图/bath.png"
        val modPath = "mods/rt2/kojo-probe01.assets/resources/背景图/bath-custom.png"

        // 坏侧车：ok=false → collect 跳过 → 空表 → 官方
        val broken = ModDx3AssetMap.Sidecar("kojo-probe01", emptyList(), false, listOf("损坏"))
        val brokenOverlay = EraA3AssetOverlay.collect(listOf("kojo-probe01")) { broken }
        assertSpriteUses(official, officialPath, modPath, brokenOverlay, officialPath, 10)

        // 映射目标文件打不开（mod 资源没有 imageSize）：消费面 WARN 并回落官方，不崩
        val overlay = overlayFor(spriteEntry(official, "resources/背景图/bath-custom.png"))
        val host = makeHost(official, officialPath, modPath, giveModSize = false)
        val pool = SpritePool()
        val report = ResourceContents.load(pool, host, overlay)
        assertTrue("覆盖目标打不开必须留 WARN（不静默吞错）", report.warnings.any { it.contains("覆盖资源打不开") })
        assertEquals(officialPath, pool.find(official)!!.buffer!!.fileSource)
    }

    @Test
    fun `BGM覆盖探针走真消费面且映射关掉回落官方`() {
        val gameRoot = Files.createTempDirectory("a3-bgm").toFile()
        val soundDir = File(gameRoot, "sound").apply { mkdirs() }
        val official = File(soundDir, "gamebgm/bgm.mp3").apply {
            parentFile?.mkdirs()
            writeText("official")
        }
        val mod = File(gameRoot, "mods/rt2/kojo-probe01.assets/sound/custom.mp3").apply {
            parentFile?.mkdirs()
            writeText("mod")
        }
        val overlay = overlayFor(
            ModDx3AssetMap.Entry(ModDx3AssetMap.Kind.BGM, "gamebgm/bgm.mp3", "sound/custom.mp3"),
        )

        val hit = SoundResourcePaths.resolveInGameRoot(gameRoot, "gamebgm/bgm.mp3", overlay)
        assertNotNull("BGM 覆盖必须命中", hit)
        assertEquals(mod.canonicalPath, hit!!.canonicalPath)

        // 反向证明：映射关 → 官方文件
        val off = SoundResourcePaths.resolveInGameRoot(gameRoot, "gamebgm/bgm.mp3", EraA3AssetOverlay.AssetMap.EMPTY)
        assertNotNull(off)
        assertEquals(official.canonicalPath, off!!.canonicalPath)

        // 目标不存在的映射：fail-closed 回落官方
        val missingOverlay = overlayFor(
            ModDx3AssetMap.Entry(ModDx3AssetMap.Kind.BGM, "gamebgm/bgm.mp3", "sound/missing.mp3"),
        )
        val fallback = SoundResourcePaths.resolveInGameRoot(gameRoot, "gamebgm/bgm.mp3", missingOverlay)
        assertEquals(official.canonicalPath, fallback!!.canonicalPath)
    }
}
