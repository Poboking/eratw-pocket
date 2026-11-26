package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * MODDX3-A3 一期——纯资源包导入解锁的探针（CONTRACT-MODDX3-A3 §3）。
 *
 * 三臂：
 * 1. **成功臂**：0 个 .ERB/.ERH + ≥1 条 `<自述名>.assets/` 资源 + 可信 `eratw-pack.json`
 *    的 zip 导入成功，落盘最小件集（manifest RT2MANIFEST/4 + 空 .sources + 资源目录），
 *    无 .erbm；packageId = [ModImportController.assetPackageIdFor]（kojo- 前缀身份）；
 *    资源重挂、成功页文案、三语形状与既有资源面口径一致。
 * 2. **反向证明臂**：把放宽闸退回旧判据（[ModImportController] ⓪ 的 `unpacked.isEmpty()`
 *    立刻失败口径）——用 [ModZip.looksLikeAssetPack] + zipNoEntries 的人工复合证明：
 *    纯资源包 zip 在旧判据下必被拒导，且拒绝文案逐字落在旧的两条模板上。
 * 3. **fail-closed 臂**：无资源纯空包 / 无可信 pack.json / assets 超限 / 路径穿越 / 无资源
 *    可收下，一律维持既有失败面、零半套残留。
 *
 * 注：本测试**不跑**全量导入的 PatchCompiler 真编译（无源），baseSource 注入 mini 上下文只为
 * 满足构造器签名；纯资源包路径在 ⓪ 之后即返回，不触碰 baseSource（这是设计契约：纯资源包
 * 不需要 base 产物、不需要编译）。
 */
class ModDx3ResourcePackTest {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun miniMetadata(): BootMetadata = BootMetadata(
        functions = listOf(
            FuncMeta(
                index = 0,
                key = "A_ONE",
                name = "A_ONE",
                isMethod = false,
                argIntCount = 0,
                argStrCount = 0,
                returnsStr = false,
                defIndex = 0,
                position = "mini.ERB:1",
            ),
        ),
        methodTypes = emptyMap(),
    )

    private fun controllerIn(tmp: File): ModImportController = ModImportController(
        modStore = ModStore(File(tmp, "mods")),
        baseSource = {
            BaseCompileContext(
                miniMetadata(),
                PatchCompiler.BaseCounts(4000, 1, 100, 10, 5, 20),
                BASE_SHA,
                csvDir = null,
            )
        },
        gameRoot = tmp,
    )

    private fun tempDir(): File = Files.createTempDirectory("eratw-dx3-packs").toFile()

    /** 固定键序 pack.json（与 PackModMeta.json 同口径：schema → packageId → version）。 */
    private fun metaJson(packageId: String, version: String = "1", schema: Int = 1): ByteArray =
        "{\"schema\":$schema,\"packageId\":\"$packageId\",\"version\":\"$version\"}".toByteArray(Charsets.UTF_8)

    /** 构造 zip 字节（条目按入参顺序写入，名字原样保留；限额/编码由被测链负责）。 */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun imported(outcome: ModImportOutcome): ImportedPatchDetail =
        (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("纯资源包应导入成功，实为 $outcome")

    private fun failed(outcome: ModImportOutcome): ModImportOutcome.Failed =
        outcome as? ModImportOutcome.Failed ?: throw AssertionError("应失败，实为 $outcome")

    private fun rt2(tmp: File) = File(File(tmp, "mods"), "rt2")

    // ------------------------------------------------------------------
    // 臂 1：成功——manifest-only 载体落盘 + 资源重挂 + 三语文案
    // ------------------------------------------------------------------

    @Test
    fun `纯资源包——manifest-only 载体落盘最小件集且无 erbm`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val selfName = "eratw.artpack"
        val zip = zipOf(
            PACK_META to metaJson(selfName, version = "2"),
            "$selfName.assets/chara/sakura.png" to PNG,
            "$selfName.assets/bgm/theme.ogg" to OGG,
        )
        val outcome = controller.import(listOf(PickedModFile("artpack.zip", zip)))
        val detail = imported(outcome)
        val packageId = ModImportController.assetPackageIdFor(selfName)

        // 产物名指向 manifest 而非 erbm；无函数/指令/入口；无留档源；载体标记置位。
        assertEquals(packageId + ModPackageInventory.MANIFEST_EXTENSION, detail.patchFileName)
        assertEquals(0, detail.functionCount)
        assertEquals(0, detail.instructionCount)
        assertEquals("", detail.entryFunction)
        assertTrue(detail.sourceFileNames.isEmpty())
        assertTrue("载体标记必须为 true", detail.assetPackOnly)
        assertEquals(2, detail.resourceEntryCount)

        val dir = rt2(tmp)
        assertTrue("manifest 应落盘", File(dir, "$packageId.manifest").isFile)
        assertTrue("空 sources（载体形态标记）应落盘", File(dir, "$packageId.sources").isFile)
        assertEquals("\n", File(dir, "$packageId.sources").readText(Charsets.UTF_8))
        assertFalse("载体不得落 erbm", File(dir, "$packageId.erbm").exists())
        assertFalse("载体无源可重编，不落指纹侧车", File(dir, "$packageId.sources.sha256").exists())
        assertTrue("资源目录应落盘", File(dir, "$packageId.assets").isDirectory)
        assertTrue("资源应重挂到载体 packageId 命名空间", File(dir, "$packageId.assets/chara/sakura.png").isFile)

        // manifest 由 pack.json 派生为 RT2MANIFEST/4（displayName 非空触发元数据段）。
        val descriptor = ManifestCodec.decode(File(dir, "$packageId.manifest").readBytes())
        assertEquals(packageId, descriptor.packageId)
        assertEquals("2", descriptor.version)
        assertEquals(selfName, descriptor.displayName)
        val header = File(dir, "$packageId.manifest").readText(Charsets.UTF_8).lineSequence().first()
        assertEquals("RT2MANIFEST/4", header)
    }

    @Test
    fun `纯资源包——成功页详情用载体模板且资源行上屏`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val selfName = "eratw.artpack"
        val zip = zipOf(
            PACK_META to metaJson(selfName),
            "$selfName.assets/a.png" to PNG,
        )
        val detail = imported(controller.import(listOf(PickedModFile("artpack.zip", zip))))
        val packageId = ModImportController.assetPackageIdFor(selfName)
        val manifestName = packageId + ModPackageInventory.MANIFEST_EXTENSION

        assertEquals(
            "纯资源包详情应走载体模板（ZH）",
            "纯资源包导入成功：$manifestName（1 条资源）",
            detail.successDetailText(ModImportCopy.ZH),
        )
        assertTrue(
            "EN 载体详情：${detail.successDetailText(ModImportCopy.EN)}",
            detail.successDetailText(ModImportCopy.EN).contains("Asset-only pack imported"),
        )
        assertTrue(
            "JA 载体详情：${detail.successDetailText(ModImportCopy.JA)}",
            detail.successDetailText(ModImportCopy.JA).contains("リソースのみパック"),
        )
        assertEquals("资源 N 条行应上屏", "资源 1 条", detail.resourceSummaryLine(ModImportCopy.ZH))

        // 普通口上包（assetPackOnly=false）详情仍走既有模板（逐字不变，护住旧用例）。
        val ordinary = ImportedPatchDetail("p.erbm", 2, 7, "B_HELLO", emptyList(), emptyList())
        assertEquals(
            "普通包详情不变",
            "导入成功：p.erbm（2 个函数 / 7 条指令，入口 B_HELLO）",
            ordinary.successDetailText(ModImportCopy.ZH),
        )
    }

    @Test
    fun `纯资源包——packageId 为 kojo 前缀且与自述包名稳定派生`() {
        val id = ModImportController.assetPackageIdFor("eratw.artpack")
        assertTrue("必须 kojo- 前缀（IMPORTED 身份 + 卸载按钮）", id.startsWith("kojo-"))
        assertEquals(8, id.removePrefix("kojo-").length)
        assertEquals("同一自述包名派生稳定", id, ModImportController.assetPackageIdFor("eratw.artpack"))
        assertTrue("不同自述包名派生不同", id != ModImportController.assetPackageIdFor("eratw.other"))
    }

    @Test
    fun `载体存活——模拟启动期孤儿 GC 不删 manifest-only 纯资源包`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val selfName = "eratw.artpack"
        val zip = zipOf(
            PACK_META to metaJson(selfName),
            "$selfName.assets/a.png" to PNG,
        )
        imported(controller.import(listOf(PickedModFile("artpack.zip", zip))))
        val packageId = ModImportController.assetPackageIdFor(selfName)
        val dir = rt2(tmp)

        assertTrue("载体形态判据应命中", ModPackageInventory.isManifestOnlyCarrier(packageId, dir))

        // 模拟下次启动装配开态的孤儿 GC；载体（无 erbm）不得被当半成品清掉。
        ModPackageInventory.gcOrphans(dir, File(tmp, "mods"))
        assertTrue("GC 后 manifest 仍在", File(dir, "$packageId.manifest").isFile)
        assertTrue("GC 后空 sources 仍在", File(dir, "$packageId.sources").isFile)
        assertTrue("GC 后资源目录仍在", File(dir, "$packageId.assets").isDirectory)

        // 反向：普通「有 manifest 无 erbm 且非空 provenance」的残留仍被当半成品清掉（原判据不回退）。
        val stale = "kojo-deadbeef"
        File(dir, "$stale.manifest").writeBytes(ManifestCodec.encode(
            com.eratw.port.core.patch.ModuleDescriptor(packageId = stale, version = "1"),
        ))
        File(dir, "$stale.sources").writeText("GONE.ERB\n")
        ModPackageInventory.gcOrphans(dir, File(tmp, "mods"))
        assertFalse("无容器非空 provenance 的残留仍应被清", File(dir, "$stale.manifest").exists())
    }

    // ------------------------------------------------------------------
    // 臂 2：反向证明——旧判据必拒导（放宽闸存在才有成功臂）
    // ------------------------------------------------------------------

    @Test
    fun `反向证明——旧判据下纯资源包被拒导且落在旧拒导文案上`() {
        val selfName = "eratw.artpack"
        val carrierZip = zipOf(
            PACK_META to metaJson(selfName),
            "$selfName.assets/a.png" to PNG,
        )
        // 旧判据（改造前 ⓪ 的 unpacked.isEmpty() 立即失败）：先看不到任何 .ERB/.ERH 条目 ⇒
        // 走 looksLikeAssetPack 分叉判文案。纯资源包既非素材包投放形态（无 resources/sound 段），
        // 故旧文案逐字 = zipNoEntriesFormat —— 证明「无放宽闸 ⇒ 此包必红」。
        assertFalse("载体 zip 不应被素材包指路误判", ModZip.looksLikeAssetPack(carrierZip))
        assertTrue("载体 zip 解包确为空（旧判据的拒导前提）", ModZip.unpack(carrierZip).isEmpty())

        val tmp = tempDir()
        val controller = controllerIn(tmp)
        // 无 pack.json 与载体同形（0 源 + 同名 .assets 条目）⇒ 不被认作载体 ⇒ 旧失败面原样出现。
        val noMeta = zipOf("$selfName.assets/a.png" to PNG)
        val failedOutcome = failed(controller.import(listOf(PickedModFile("nometa.zip", noMeta))))
        assertEquals(ModImportFailureKind.STORAGE, failedOutcome.kind)
        assertTrue(
            "旧拒导文案逐字：${failedOutcome.message}",
            failedOutcome.message.contains("口上 zip 里没有可导入的 .ERB/.ERH 条目"),
        )
        assertFalse("拒导不落任何产物", rt2(tmp).let { it.isDirectory && it.listFiles()?.isNotEmpty() == true })
    }

    // ------------------------------------------------------------------
    // 臂 3：fail-closed——坏包一律维持既有失败面、零半套残留
    // ------------------------------------------------------------------

    @Test
    fun `fail-closed——无资源纯空包与素材包维持旧失败面`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val emptyPack = failed(controller.import(listOf(PickedModFile("empty.zip", zipOf("readme.txt" to "x".toByteArray())))))
        assertEquals(ModImportFailureKind.STORAGE, emptyPack.kind)
        assertTrue(emptyPack.message.contains("口上 zip 里没有可导入的 .ERB/.ERH 条目"))

        val assetPack = failed(controller.import(listOf(PickedModFile("bg.zip", zipOf("resources/x.webp" to ByteArray(3))))))
        assertEquals(ModImportFailureKind.STORAGE, assetPack.kind)
        assertTrue("素材包仍指路素材导入卡", assetPack.message.contains("这看起来是素材包，请到素材导入"))
    }

    @Test
    fun `fail-closed——schema 不认识或自述包名非法不认作载体`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val badSchema = zipOf(
            PACK_META to metaJson("eratw.artpack", schema = 2),
            "eratw.artpack.assets/a.png" to PNG,
        )
        val f1 = failed(controller.import(listOf(PickedModFile("badschema.zip", badSchema))))
        assertEquals(ModImportFailureKind.STORAGE, f1.kind)
        assertTrue(f1.message.contains("口上 zip 里没有可导入的 .ERB/.ERH 条目"))

        // 自述包名非法（含大写与斜杠）⇒ parsePackMeta 不采信 packageId ⇒ 不认载体。
        val badId = zipOf(
            PACK_META to "{\"schema\":1,\"packageId\":\"Bad/ID\",\"version\":\"1\"}".toByteArray(Charsets.UTF_8),
            "eratw.artpack.assets/a.png" to PNG,
        )
        val f2 = failed(controller.import(listOf(PickedModFile("badid.zip", badId))))
        assertEquals(ModImportFailureKind.STORAGE, f2.kind)
        assertTrue(f2.message.contains("口上 zip 里没有可导入的 .ERB/.ERH 条目"))
    }

    @Test
    fun `fail-closed——资源全被跳过或超限整面拒绝不落载体`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        val selfName = "eratw.artpack"

        // 资源名含路径穿越段（..）⇒ isValidResourceName 拒 ⇒ 无可收下资源 ⇒ fail-closed。
        val traversal = zipOf(
            PACK_META to metaJson(selfName),
            "$selfName.assets/../evil.png" to PNG,
        )
        val f1 = failed(controller.import(listOf(PickedModFile("traversal.zip", traversal))))
        assertEquals(ModImportFailureKind.STORAGE, f1.kind)
        assertTrue("穿越包维持旧失败面：${f1.message}", f1.message.contains("口上 zip 里没有可导入的 .ERB/.ERH 条目"))
        assertFalse("失败不落 manifest", rt2(tmp).listFiles()?.any { it.name.endsWith(".manifest") } == true)

        // 单文件超 4 MiB ⇒ 整面拒绝（既有资源面闸）⇒ fail-closed。
        val oversize = zipOf(
            PACK_META to metaJson(selfName),
            "$selfName.assets/huge.png" to ByteArray(ModImportAssets.MAX_RESOURCE_FILE_BYTES.toInt() + 1),
        )
        val f2 = failed(controller.import(listOf(PickedModFile("oversize.zip", oversize))))
        assertEquals(ModImportFailureKind.STORAGE, f2.kind)
    }

    @Test
    fun `fail-closed——可信元数据但零 assets 条目不认作载体`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp)
        // 有可信 pack.json，但没有任何 .assets 资源条目 ⇒ 第三判据不成立 ⇒ fail-closed。
        val noAssets = zipOf(PACK_META to metaJson("eratw.artpack"))
        val failedOutcome = failed(controller.import(listOf(PickedModFile("noassets.zip", noAssets))))
        assertEquals(ModImportFailureKind.STORAGE, failedOutcome.kind)
        assertFalse("不落任何载体件", rt2(tmp).listFiles()?.isNotEmpty() == true)
    }

    private companion object {
        const val PACK_META = "eratw-pack.json"
        const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val OGG = byteArrayOf(0x4F, 0x67, 0x67, 0x53, 1, 2, 3, 4)
    }
}
