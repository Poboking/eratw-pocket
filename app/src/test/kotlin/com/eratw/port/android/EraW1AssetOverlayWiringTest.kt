package com.eratw.port.android

import com.eratw.port.core.host.ResourceContents
import com.eratw.port.core.host.ResourceFileHost
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.image.PngImage
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.EraA3AssetOverlay
import com.eratw.port.core.mod.ModDx3AssetMap
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.LinkOrder
import com.eratw.port.core.patch.ModuleLinker
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.audio.ChannelDecoder
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.platform.audio.FileSoundSourceResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ERA-W1：A3 素材覆盖轨道的**生产接线**正反证（A3 交付留下的 GameHost / 音声面挂账收账）。
 *
 * - 链接序真源提取：[modLinkOrderIdsOf] 从真装配产物（ModChainAssembly.assembleWithCatalog）
 *   的 candidates 提取，候选列表序即链接序（SymbolCatalog 类 KDoc）；显式 LinkOrder 声明
 *   必须原样复现在提取结果里，旁路（null）= 空表。
 * - 覆盖聚合生产口：[assetOverlayFor] 读盘上真侧车，声明序定跨包胜者（last-wins：遍历序
 *   靠后者胜），链外启用包追加在后；聚合结果喂进 [ResourceContents.load] 三参重载（生产
 *   装配形态）后 sprite 图源确实改指 mod 资源；映射关掉（EMPTY）回落官方路径。
 * - 音声面生产构造形态：resolver 带 gameRoot + overlay 时 BGM 命中 mod 文件，关掉回落官方，
 *   目标缺失 fail-closed 回落（与 SoundResourcePaths.resolveInGameRoot 同口径）。
 *
 * 本类不依赖 ERATW_CORPUS / ERATW_GAMEDIR（mini base + 真导入链），缺环境变量也全量实跑。
 */
class EraW1AssetOverlayWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // 假宿主（照 EraA3CoverageProbeTest 的 CsvHost 形态）
    // ------------------------------------------------------------------

    private class CsvHost(
        private val body: String,
        private val sizes: Map<String, IntArray>,
    ) : ResourceFileHost {
        override fun listCsvFiles(): List<String> = listOf("resources/probe.csv")
        override fun readBytes(path: String): ByteArray? =
            if (path == "resources/probe.csv") body.toByteArray(Charsets.UTF_8) else ByteArray(0)
        override fun imageSize(path: String, bytes: ByteArray?): IntArray? = sizes[path]?.copyOf()
        override fun decode(path: String, bytes: ByteArray): PngImage? = null
    }

    /** 一行 CSV + 官方/mod 图源尺寸（官方 10x10、mod 20x20，读数可分辨）。 */
    private fun hostFor(officialSprite: String, officialPath: String, modPaths: List<String>): CsvHost {
        val sizes = LinkedHashMap<String, IntArray>()
        sizes[officialPath] = intArrayOf(10, 10)
        for (mod in modPaths) sizes[mod] = intArrayOf(20, 20)
        val file = officialPath.removePrefix("resources/")
        return CsvHost("$officialSprite,$file", sizes)
    }

    // ------------------------------------------------------------------
    // 真导入链装置（照 EraA2CharaTemplateTrackTest）
    // ------------------------------------------------------------------

    private fun fixtureBytes(rel: String): ByteArray {
        val candidates = listOf(
            File("fixtures/era-a2-chara", rel),
            File("../fixtures/era-a2-chara", rel),
            File("../../fixtures/era-a2-chara", rel),
            // 公开分仓后夹具在同级引擎仓 Kotera（composite build 的 ../Kotera）。
            File("../../Kotera/fixtures/era-a2-chara", rel),
        )
        return candidates.firstOrNull { it.isFile }?.readBytes()
            ?: error("找不到夹具 fixtures/era-a2-chara/$rel")
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun controllerIn(root: File): ModImportController =
        ModImportController(
            modStore = com.eratw.port.platform.storage.ModStore(File(root, "mods")),
            baseSource = {
                BaseCompileContext(
                    miniMetadata(),
                    PatchCompiler.BaseCounts(
                        instructionCount = 4000,
                        functionCount = 4,
                        statementCount = 100,
                        stringCount = 10,
                        longCount = 5,
                        exprCount = 20,
                    ),
                    BASE_SHA,
                    csvDir = null,
                    baseSignatures = { BaseSignatureCatalog.fingerprint("SIG|$it") },
                )
            },
            gameRoot = root,
        )

    private fun miniMetadata(): BootMetadata {
        val names = listOf("A_ONE", "A_TWO", "A_SUM", "A_DEF")
        val argCounts = mapOf("A_TWO" to 1, "A_DEF" to 3)
        return BootMetadata(
            functions = names.mapIndexed { i, name ->
                FuncMeta(
                    index = i,
                    key = name,
                    name = name,
                    isMethod = name == "A_SUM",
                    argIntCount = argCounts[name] ?: 0,
                    argStrCount = 0,
                    returnsStr = false,
                    defIndex = i,
                    position = "mini.ERB:${i + 1}",
                )
            },
            methodTypes = mapOf("A_SUM" to BootMetadata.MethodType.INT),
        )
    }

    private val baseMetas = listOf(FuncMeta(index = 0, key = "ENTRY"))

    private fun baseProgram() = com.eratw.port.core.code.CodeBuilder().let { b ->
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(com.eratw.port.core.code.Opcode._RET, 0, 0, 0, 0)
        b.build(entry)
    }

    /** 导入两个包（源名不同 ⇒ packageId 不同），返回按包名升序的 (p1, p2)。 */
    private fun importTwoPackages(root: File): Pair<String, String> {
        val controller = controllerIn(root)
        val packJson = fixtureBytes("pack/pack-min.json")
        val a = controller.import(
            listOf(
                PickedModFile(
                    "a.ZIP",
                    zipOf("eratw-pack.json" to packJson, "a_probe.ERB" to fixtureBytes("ERB/era_a2_probe.ERB")),
                ),
            ),
        )
        val b = controller.import(
            listOf(
                PickedModFile(
                    "b.ZIP",
                    zipOf("eratw-pack.json" to packJson, "b_second.ERB" to fixtureBytes("ERB/era_a2_second.ERB")),
                ),
            ),
        )
        assertTrue("两包都应导入成功：$a / $b", a is ModImportOutcome.Imported && b is ModImportOutcome.Imported)
        val p1 = controller.packageIdFor(listOf("a_probe.ERB"))
        val p2 = controller.packageIdFor(listOf("b_second.ERB"))
        assertTrue("两包 packageId 必须不同", p1 != p2)
        return if (p1 < p2) p1 to p2 else p2 to p1
    }

    // ------------------------------------------------------------------
    // modLinkOrderIdsOf：链接序真源提取
    // ------------------------------------------------------------------

    @Test
    fun `EraW1_modLinkOrderIdsOf_旁路返回空表`() {
        assertEquals(emptyList<String>(), modLinkOrderIdsOf(null))
    }

    @Test
    fun `EraW1_modLinkOrderIdsOf_缺省序为包名升序且声明序可复现`() {
        val root = tmp.newFolder("game-linkorder")
        val (lo, hi) = importTwoPackages(root)

        // 缺省 LinkOrder（无显式排序）：plan = packageId 规范序 ⇒ 提取结果同名序。
        val defaultOutcome = requireNotNull(
            ModChainAssembly.assembleWithCatalog(
                ChainAssemblyInputs(root, true, baseProgram(), baseMetas),
            ),
        )
        assertEquals(listOf(lo, hi), modLinkOrderIdsOf(defaultOutcome))
        assertTrue("base 合成标记不得混入链接序", modLinkOrderIdsOf(defaultOutcome).none { it == ModuleLinker.BASE_OWNER_ID })

        // 显式声明 [hi, lo]：candidates 候选列表序 = 链接序 ⇒ 提取结果原样复现声明序。
        val declaredOutcome = requireNotNull(
            ModChainAssembly.assembleWithCatalog(
                ChainAssemblyInputs(root, true, baseProgram(), baseMetas, linkOrder = LinkOrder(listOf(hi, lo))),
            ),
        )
        assertEquals(listOf(hi, lo), modLinkOrderIdsOf(declaredOutcome))
    }

    // ------------------------------------------------------------------
    // assetOverlayFor：磁盘侧车聚合生产口
    // ------------------------------------------------------------------

    /** rt2 目录 + 每包一份真侧车与资源（SPRITE BGI_X → 本包资源），manifest 文件按名落位。 */
    private fun installPackages(gameRoot: File, packageIds: List<String>) {
        val rt2 = File(File(gameRoot, "mods"), ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME)
        assertTrue(rt2.mkdirs() || rt2.isDirectory)
        for (pkg in packageIds) {
            File(rt2, "$pkg.manifest").writeText("ERATW-MANIFEST-STUB")
            val sidecar = ModDx3AssetMap.encode(
                pkg,
                listOf(ModDx3AssetMap.Entry(ModDx3AssetMap.Kind.SPRITE, "BGI_X", "resources/x-$pkg.png")),
            )
            File(rt2, "$pkg.assetmap").writeText(sidecar)
            File(rt2, "$pkg.assets/resources").mkdirs()
            File(rt2, "$pkg.assets/resources/x-$pkg.png").writeText("mod-$pkg")
        }
    }

    @Test
    fun `EraW1_assetOverlayFor_声明序定链内胜者且链外包追加在后`() {
        val gameRoot = tmp.newFolder("game-overlay")
        installPackages(gameRoot, listOf("w1-a", "w1-b", "w1-c"))

        // 遍历序 = [w1-b, w1-a]（声明序）+ [w1-c]（链外启用包按名追加）⇒ 同名覆盖 last-wins 胜者 = w1-c。
        val overlay = assetOverlayFor(gameRoot, listOf("w1-b", "w1-a"))
        val target = overlay.sprite("BGI_X")
        assertNotNull("覆盖必须聚合出映射", target)
        assertEquals("w1-c", target!!.packageId)
        assertEquals("mods/rt2/w1-c.assets/resources/x-w1-c.png", target.resourcePath)

        // 去掉链外包（声明 [w1-b, w1-a]）⇒ 链内胜者 = 声明序靠后者 w1-a（last-wins：后到者胜）。
        val gameRoot2 = tmp.newFolder("game-overlay-chain-only")
        installPackages(gameRoot2, listOf("w1-a", "w1-b"))
        val chainOnly = assetOverlayFor(gameRoot2, listOf("w1-b", "w1-a"))
        assertEquals("w1-a", chainOnly.sprite("BGI_X")!!.packageId)
    }

    @Test
    fun `EraW1_assetOverlayFor_聚合结果进生产装载口_映射关掉回落官方`() {
        val gameRoot = tmp.newFolder("game-overlay-load")
        installPackages(gameRoot, listOf("w1-a"))
        val officialPath = "resources/背景图/x.png"
        val modPath = "mods/rt2/w1-a.assets/resources/x-w1-a.png"

        val pool = SpritePool()
        val report = ResourceContents.load(
            pool,
            hostFor("BGI_X", officialPath, listOf(modPath)),
            assetOverlayFor(gameRoot, listOf("w1-a")),
        )
        assertTrue("装载不该有告警：${report.warnings}", report.warnings.isEmpty())
        assertEquals("盘上真侧车聚合出的覆盖必须改指 mod 图源", modPath, pool.find("BGI_X")!!.buffer!!.fileSource)

        // 反向证明：映射关掉（EMPTY，= 关态生产形态）→ 官方路径逐位不变。
        val poolOff = SpritePool()
        ResourceContents.load(
            poolOff,
            hostFor("BGI_X", officialPath, listOf(modPath)),
            EraA3AssetOverlay.AssetMap.EMPTY,
        )
        assertEquals(officialPath, poolOff.find("BGI_X")!!.buffer!!.fileSource)
    }

    // ------------------------------------------------------------------
    // 音声面生产构造形态（GameHost 装配段同形：直接构造器 + trailing lambda）
    // ------------------------------------------------------------------

    private class RecordingDecoder : ChannelDecoder {
        override val totalSeconds: Double = 1.0
        override val positionSeconds: Double = 0.0
        override fun seekTo(seconds: Double) = Unit
    }

    @Test
    fun `EraW1_音声resolver生产构造_覆盖命中与关掉回落官方`() {
        val gameRoot = tmp.newFolder("game-sound")
        File(gameRoot, "sound/gamebgm").mkdirs()
        val official = File(gameRoot, "sound/gamebgm/bgm.mp3").apply { writeText("official") }
        File(gameRoot, "mods/rt2/w1-a.assets/sound").mkdirs()
        val mod = File(gameRoot, "mods/rt2/w1-a.assets/sound/bgm.mp3").apply { writeText("mod") }
        val rt2 = File(gameRoot, "mods/rt2")
        File(rt2, "w1-a.assetmap").writeText(
            ModDx3AssetMap.encode(
                "w1-a",
                listOf(ModDx3AssetMap.Entry(ModDx3AssetMap.Kind.BGM, "gamebgm/bgm.mp3", "sound/bgm.mp3")),
            ),
        )

        val overlay = assetOverlayFor(gameRoot, listOf("w1-a"))
        // 与 GameHost 装配段同形：soundDir + gameRoot + overlay + trailing lambda。
        val soundDir = File(gameRoot, FileSoundSourceResolver.SOUND_DIR_NAME)
        val resolver = FileSoundSourceResolver(soundDir, gameRoot, overlay) { file ->
            assertEquals("命中覆盖时交给解码器的必须是 mod 文件", mod.canonicalPath, file.canonicalPath)
            RecordingDecoder()
        }
        assertNotNull("覆盖命中的 BGM 必须建出解码器（不静默）", resolver.resolve("gamebgm/bgm.mp3"))

        // 反向证明一：映射关掉（EMPTY，= 关态生产形态）→ 官方文件。
        val officialDecoder = FileSoundSourceResolver(soundDir, gameRoot, EraA3AssetOverlay.AssetMap.EMPTY) { file ->
            assertEquals("映射关掉时必须回落官方文件", official.canonicalPath, file.canonicalPath)
            RecordingDecoder()
        }
        assertNotNull(officialDecoder.resolve("gamebgm/bgm.mp3"))

        // 反向证明二：目标缺失的映射 fail-closed 回落官方（不返回 null、不崩）。
        val rt2Missing = File(gameRoot, "mods/rt2-missing")
        assertTrue(rt2Missing.mkdirs() || rt2Missing.isDirectory)
        File(rt2Missing, "w1-a.assetmap").writeText(
            ModDx3AssetMap.encode(
                "w1-a",
                listOf(ModDx3AssetMap.Entry(ModDx3AssetMap.Kind.BGM, "gamebgm/bgm.mp3", "sound/missing.mp3")),
            ),
        )
        val missingOverlay = EraA3AssetOverlay.collect(rt2Missing, listOf("w1-a"))
        assertNull("官方文件不存在的名字仍按不存在处理", resolver.resolve("不存在的名字.mp3"))
        val fallback = FileSoundSourceResolver(soundDir, gameRoot, missingOverlay) { file ->
            assertEquals("目标缺失必须回落官方文件", official.canonicalPath, file.canonicalPath)
            RecordingDecoder()
        }
        assertNotNull(fallback.resolve("gamebgm/bgm.mp3"))
    }

    private companion object {
        private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
