package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导入前只读预览（[ModImportPreview]）的出口判据：
 * - 分流：源包（代码 / 资源 / 媒体三面）与纯资源包（自述包名身份）都算；空选择 / 坏 zip /
 *   认不出载体元数据 ⇒ [Preview.Unavailable]（**不抛**，导入仍可继续）；
 * - 计数口径与 S4 采信面一致：只有合法条目进读数，扩展名白名单外的静默跳过；
 * - 限额预警只提示不拦截（[Preview.Ready] + [PreviewWarning]），S4 的 `stage` 才真拒（反证①）；
 * - 跨包 `<dir>` 冲突点名占用包，自家清单不算冲突；
 * - packageId 只读推导与控制器逐位对拍（反证②：输入换序同值、改一个名字就不同）；
 * - 全程只读：compute 前后盘面零变化，而同一条 stage 路径会留下痕迹（反证③）。
 *
 * 全部纯 JVM：内存 zip 夹具 + 临时目录，不碰 Android 类。
 */
class ModImportPreviewTest {

    @Test
    fun `空选择——Unavailable 不抛且文案是没选文件`() {
        val tmp = Files.createTempDirectory("eratw-preview").toFile()
        val preview = ModImportPreview.compute(emptyList(), PreviewFixture.rt2Of(tmp))
        val unavailable = preview as? Preview.Unavailable
        assertNotNull("空选择必须是 Unavailable，实为 $preview", unavailable)
        assertEquals(ModImportCopy.ZH.noFilesPicked, unavailable!!.reason)
    }

    @Test
    fun `源包——代码面按 erb-erh-erbm 计数且无元数据时媒体面为 null`() {
        val tmp = Files.createTempDirectory("eratw-preview").toFile()
        val zip = PreviewFixture.zipOf(
            "src/A.ERB" to "RETURN 0\n".toByteArray(),
            "src/B.erh" to "RETURN 0\n".toByteArray(),
            "C.erb" to "RETURN 0\n".toByteArray(),
            "pkg/mod.erbm" to ByteArray(64) { 1 },
        )
        val picked = listOf(
            PickedModFile("口上.zip", zip),
            PickedModFile("D.ERB", "RETURN 0\n".toByteArray()),
        )
        val ready = readyOf(previewOf(picked, tmp))
        assertEquals(4, ready.code.sourceFileCount)
        assertEquals(3, ready.code.erbFileCount)
        assertEquals(1, ready.code.erhFileCount)
        assertEquals(1, ready.code.compiledModuleCount)
        assertEquals(0, ready.assets.entryCount)
        assertNull("没有 eratw-pack.json 的包不该有媒体面", ready.media)
    }

    @Test
    fun `资源面——只数被采信的条目且字节按解压后累计`() {
        val tmp = Files.createTempDirectory("eratw-preview").toFile()
        val zip = PreviewFixture.zipOf(
            "A.ERB" to "RETURN 0\n".toByteArray(),
            "demo.assets/a.png" to ByteArray(10) { 1 },
            "demo.assets/sub/b.txt" to ByteArray(5) { 2 },
            "demo.assets/../evil.png" to ByteArray(7) { 3 },
            "nested/demo.assets/c.png" to ByteArray(9) { 4 },
        )
        val ready = readyOf(previewOf(listOf(PickedModFile("资源.zip", zip)), tmp))
        assertEquals("只认合法来源目录名 + 合法相对名", 2, ready.assets.entryCount)
        assertEquals(15L, ready.assets.totalBytes)
    }

    @Test
    fun `媒体面——两树条目-字节与扩展名分组按条目数降序`() {
        val tmp = Files.createTempDirectory("eratw-preview").toFile()
        val zip = PreviewFixture.zipOf(
            "eratw-pack.json" to PreviewFixture.packMeta("demo"),
            "resources/mod/demo/a.png" to ByteArray(10) { 1 },
            "resources/mod/demo/sub/b.txt" to ByteArray(5) { 2 },
            "resources/mod/demo/c.csv" to ByteArray(3) { 3 },
            "resources/mod/other/x.png" to ByteArray(4) { 4 },
            "sound/mod/demo/se.mp3" to ByteArray(7) { 5 },
        )
        val media = readyOf(previewOf(listOf(PickedModFile("媒体.zip", zip)), tmp)).media
        assertNotNull("声明了 resources 就必须有媒体面", media)
        assertEquals("demo", media!!.dirName)
        assertEquals(2, media.resourceEntryCount)
        assertEquals(15L, media.resourceBytes)
        assertEquals(1, media.soundEntryCount)
        assertEquals(7L, media.soundBytes)
        assertEquals(
            "白名单外的 .csv 静默跳过、目录不符的条目静默忽略",
            listOf("mp3" to 1, "png" to 1, "txt" to 1),
            media.extensions.map { it.extension to it.count },
        )
    }

    @Test
    fun `坏 zip——Unavailable 带解包失败文案且绝不抛`() {
        val tmp = Files.createTempDirectory("eratw-preview").toFile()
        val broken = PickedModFile("坏包.zip", ByteArray(32) { 0x5A })
        val preview = ModImportPreview.compute(listOf(broken), PreviewFixture.rt2Of(tmp))
        val unavailable = preview as? Preview.Unavailable
        assertNotNull("坏 zip 不能抛，必须是 Unavailable，实为 $preview", unavailable)
        assertTrue("reason 要点名是哪个包", unavailable!!.reason.contains("坏包.zip"))
    }

    @Test
    fun `纯资源包——身份走自述包名哈希且自家清单不算目录冲突`() {
        val tmp = Files.createTempDirectory("eratw-preview").toFile()
        val zip = PreviewFixture.zipOf(
            "eratw-pack.json" to PreviewFixture.packMeta("demo"),
            "demo.assets/a.png" to ByteArray(10) { 1 },
        )
        // 自家清单（身份 = assetPackageIdFor(自述包名)）先放在 rt2：预览必须跳过它。
        val ownId = ModImportController.assetPackageIdFor("demo")
        PreviewFixture.writeManifest(tmp, ownId, "demo")
        val ready = readyOf(previewOf(listOf(PickedModFile("纯资源.zip", zip)), tmp))
        assertEquals(0, ready.code.sourceFileCount)
        assertEquals(1, ready.assets.entryCount)
        assertEquals("demo", ready.media!!.dirName)
        assertTrue(
            "自家清单不算冲突，实为 ${ready.warnings}",
            ready.warnings.none { it is PreviewWarning.MediaDirConflict },
        )
    }

    @Test
    fun `限额预警——预览只提示不拦截而 S4 真拒（反证①）`() {
        val tmp = Files.createTempDirectory("eratw-preview").toFile()
        val zip = PreviewFixture.zipOf(
            "eratw-pack.json" to PreviewFixture.packMeta("demo"),
            "resources/mod/demo/big.png" to ByteArray(10) { 1 },
        )
        // 预览：注入 4 字节单文件上限 ⇒ 只产一条 OverLimit，整体仍是 Ready（不拦）。
        val tinyMedia = ModImportMediaAssets.MediaLimits(maxResourceFileBytes = 4)
        val preview = ModImportPreview.compute(
            listOf(PickedModFile("媒体.zip", zip)),
            PreviewFixture.rt2Of(tmp),
            ModImportCopy.ZH,
            ModImportPreview.Limits(media = tinyMedia),
        )
        val ready = readyOf(preview)
        val over = ready.warnings.filterIsInstance<PreviewWarning.OverLimit>()
        assertEquals("单文件超限只报一条预警", 1, over.size)
        assertTrue("预警要点名超额条目", over.first().subject.endsWith("big.png"))
        assertEquals(4L, over.first().limitBytes)
        assertEquals(10L, over.first().observedBytes)

        // 反向证明：同一条 stage 入口用同一组上限 ⇒ 整面拒（staged == null），预览无权拦。
        val staged = ModImportMediaAssets.stageSafely(
            archiveZips = listOf(zip),
            target = ModImportMediaAssets.MediaTarget("kojo-preview", "demo", tmp, PreviewFixture.rt2Of(tmp)),
            copy = ModImportCopy.ZH,
            limits = tinyMedia,
        )
        assertNull("S4 必须真拒（staged == null），实为 $staged", staged.staged)
        assertTrue("S4 的拒绝理由里要有该条目", staged.warnings.isNotEmpty())
    }

    @Test
    fun `两树合计超限——CombinedOverLimit 且仍 Ready`() {
        val tmp = Files.createTempDirectory("eratw-preview").toFile()
        val zip = PreviewFixture.zipOf(
            "eratw-pack.json" to PreviewFixture.packMeta("demo"),
            "resources/mod/demo/a.png" to ByteArray(6) { 1 },
            "sound/mod/demo/se.mp3" to ByteArray(6) { 2 },
        )
        val tinyCombined = ModImportMediaAssets.MediaLimits(
            maxResourceTotalBytes = 100,
            maxSoundTotalBytes = 100,
            maxCombinedBytes = 10,
        )
        val ready = readyOf(
            ModImportPreview.compute(
                listOf(PickedModFile("媒体.zip", zip)),
                PreviewFixture.rt2Of(tmp),
                ModImportCopy.ZH,
                ModImportPreview.Limits(media = tinyCombined),
            ),
        )
        val combined = ready.warnings.filterIsInstance<PreviewWarning.CombinedOverLimit>()
        assertEquals(1, combined.size)
        assertEquals(10L, combined.first().limitBytes)
        assertEquals(12L, combined.first().observedBytes)
    }

    @Test
    fun `跨包目录冲突——预览点名占用包且自家清单不点名`() {
        val tmp = Files.createTempDirectory("eratw-preview").toFile()
        PreviewFixture.writeManifest(tmp, "kojo-other-b4", "demo")
        val zip = PreviewFixture.zipOf(
            "eratw-pack.json" to PreviewFixture.packMeta("demo"),
            "resources/mod/demo/a.png" to ByteArray(10) { 1 },
        )
        val picked = listOf(PickedModFile("媒体.zip", zip))
        val ready = readyOf(previewOf(picked, tmp))
        val conflict = ready.warnings.filterIsInstance<PreviewWarning.MediaDirConflict>()
        assertEquals(1, conflict.size)
        assertEquals("demo", conflict.first().mediaDirName)
        assertEquals("kojo-other-b4", conflict.first().ownerPackageId)

        // 反向证明：把该清单名换成「本次包自己的」packageId 后就不该再报冲突。
        val ownId = ModImportPreview.sourcePackageId(listOf("A.ERB"))
        val tmp2 = Files.createTempDirectory("eratw-preview").toFile()
        PreviewFixture.writeManifest(tmp2, ownId, "demo")
        val selfZip = PreviewFixture.zipOf(
            "eratw-pack.json" to PreviewFixture.packMeta("demo"),
            "A.ERB" to "RETURN 0\n".toByteArray(),
        )
        val selfReady = readyOf(previewOf(listOf(PickedModFile("口上.zip", selfZip)), tmp2))
        assertTrue(
            "自家清单不该被算成跨包冲突，实为 ${selfReady.warnings}",
            selfReady.warnings.none { it is PreviewWarning.MediaDirConflict },
        )
    }

    @Test
    fun `packageId 对拍——与控制器同口径且随输入变化（反证②）`() {
        val tmp = Files.createTempDirectory("eratw-preview").toFile()
        val names = listOf("src/A.ERB", "src/B.erh", "C.erb")
        val controller = PreviewFixture.controllerIn(tmp)
        assertEquals(
            "预览与控制器必须是同一套哈希（含 PACKAGE_ID_PREFIX）",
            controller.packageIdFor(names),
            ModImportPreview.sourcePackageId(names),
        )
        assertEquals(
            "顺序不同不该改身份",
            controller.packageIdFor(names.reversed()),
            ModImportPreview.sourcePackageId(names),
        )
        assertFalse(
            "改一个文件名必须换身份（证明不是常量）",
            controller.packageIdFor(listOf("src/A.ERB", "src/B.erh", "D.erb")) ==
                ModImportPreview.sourcePackageId(names),
        )
    }

    @Test
    fun `只读——compute 前后盘面零变化而 stage 同路径会留痕（反证③）`() {
        val tmp = Files.createTempDirectory("eratw-preview").toFile()
        PreviewFixture.writeManifest(tmp, "kojo-other-b4", "other")
        val zip = PreviewFixture.zipOf(
            "eratw-pack.json" to PreviewFixture.packMeta("demo"),
            "resources/mod/demo/a.png" to ByteArray(10) { 1 },
            "A.ERB" to "RETURN 0\n".toByteArray(),
        )
        val before = PreviewFixture.snapshot(tmp)
        previewOf(listOf(PickedModFile("口上.zip", zip)), tmp)
        assertEquals("预览绝不许落盘/建目录", before, PreviewFixture.snapshot(tmp))

        // 反向证明：同一条 S4 stage 路径会写盘，说明上面这份快照探针是灵敏的。
        ModImportMediaAssets.stageSafely(
            archiveZips = listOf(zip),
            target = ModImportMediaAssets.MediaTarget("kojo-probe", "demo", tmp, PreviewFixture.rt2Of(tmp)),
            copy = ModImportCopy.ZH,
        )
        assertFalse("stage 必须改变盘面，否则探针无效", before == PreviewFixture.snapshot(tmp))
    }

    @Test
    fun `三语渲染——Ready 逐档出读数且 Unavailable 只有一行`() {
        val tmp = Files.createTempDirectory("eratw-preview").toFile()
        val zip = PreviewFixture.zipOf(
            "eratw-pack.json" to PreviewFixture.packMeta("demo"),
            "A.ERB" to "RETURN 0\n".toByteArray(),
            "demo.assets/a.png" to ByteArray(10) { 1 },
            "resources/mod/demo/a.png" to ByteArray(10) { 1 },
            "sound/mod/demo/se.mp3" to ByteArray(4) { 2 },
        )
        val ready = readyOf(previewOf(listOf(PickedModFile("口上.zip", zip)), tmp))
        val zh = ModImportPreview.previewLines(ready, ModImportCopy.ZH)
        val en = ModImportPreview.previewLines(ready, ModImportCopy.EN)
        val ja = ModImportPreview.previewLines(ready, ModImportCopy.JA)
        assertEquals("三档行数必须一致", zh.size, en.size)
        assertEquals(zh.size, ja.size)
        assertTrue("ZH 代码面行：$zh", zh.first().contains("代码面"))
        assertTrue("EN 代码面行：$en", en.first().contains("Code"))
        assertTrue("JA 代码面行：$ja", ja.first().contains("コード面"))
        assertTrue("ZH 媒体行要点名目录：$zh", zh.any { it.contains("demo") })
        assertTrue("ZH 扩展名行要有条目数：$zh", zh.any { line -> line.contains("png") && line.contains("条") })

        val unavailable = Preview.Unavailable("zip 坏了")
        val lines = ModImportPreview.previewLines(unavailable, ModImportCopy.ZH)
        assertEquals(1, lines.size)
        assertTrue("不可用行要带 reason：$lines", lines.first().contains("zip 坏了"))
    }

    private fun previewOf(picked: List<PickedModFile>, tmp: File): Preview =
        ModImportPreview.compute(picked, PreviewFixture.rt2Of(tmp))

    private fun readyOf(preview: Preview): Preview.Ready =
        preview as? Preview.Ready ?: throw AssertionError("预览应可用，实为 $preview")
}

/** 预览单测夹具（内存 zip + 临时 rt2 + 控制器身份对拍；函数数压在 detekt 每对象 11 条内）。 */
private object PreviewFixture {

    /**
     * 自述元数据：`packageId` 与 `resources` 同值、且与包内 `<名字>.assets/` 前缀一致——
     * 这是 `packMetaOf` 采信的前提（自述包名必须落在包内资源目录前缀集里，否则判
     * [PackMetaIgnore.PACKAGE_ID_PREFIX] 降级成不可信载体，纯资源包会被判 Unavailable）。
     */
    fun packMeta(name: String): ByteArray {
        val prefix = "{\"schema\":1,\"packageId\":\""
        val suffix = "\",\"version\":\"1.0\",\"resources\":\"" + name + "\"}"
        return (prefix + name + suffix).toByteArray()
    }

    fun rt2Of(tmp: File): File = File(File(tmp, "mods"), "rt2")

    fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
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

    fun writeManifest(tmp: File, packageId: String, dirName: String) {
        val rt2 = rt2Of(tmp)
        rt2.mkdirs()
        val header = ModImportMediaAssets.MANIFEST_VERSION + " " +
            ModImportMediaAssets.MANIFEST_DIR_KEY + dirName + "\n"
        File(rt2, packageId + ModImportMediaAssets.MEDIA_MANIFEST_SUFFIX).writeText(header)
    }

    fun snapshot(root: File): List<String> {
        val files = ArrayList<String>()
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            files += root.toPath().relativize(file.toPath()).toString() + ":" + file.length()
        }
        return files.sorted()
    }

    fun controllerIn(tmp: File): ModImportController = ModImportController(
        modStore = ModStore(File(tmp, "mods")),
        baseSource = { baseContext() },
        gameRoot = tmp,
    )

    fun baseContext(): BaseCompileContext = BaseCompileContext(
        BootMetadata(functions = emptyList(), methodTypes = emptyMap()),
        PatchCompiler.BaseCounts(
            instructionCount = 0,
            functionCount = 0,
            statementCount = 0,
            stringCount = 0,
            longCount = 0,
            exprCount = 0,
        ),
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        csvDir = null,
    )
}
