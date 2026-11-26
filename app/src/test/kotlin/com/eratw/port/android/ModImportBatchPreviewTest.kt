package com.eratw.port.android

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批导入预览的定向用例（纯 JVM，不碰 Android）：
 *
 * - 「共 N 个包」与逐包摘要（文件名 / 包身份 / 版本 / 条目数）；
 * - 单包只出一句措辞，不重复逐条摘要；
 * - 同批两个包声明同一 `<dir>` 时预览期点名（[PreviewWarning.BatchMediaDirConflict]）；
 * - 身份口径与导入侧对拍：[ModImportPreview.sourcePackageId] / [ModImportController.assetPackageIdFor]；
 * - 三语行数一致且各出各语言的包数措辞。
 *
 * 这批用例只读预览（compute 前后不落盘由既有 [ModImportPreviewTest] 的反证覆盖）。
 */
class ModImportBatchPreviewTest {

    @Test
    fun `两个源包各出一条摘要且身份是各自的源哈希`() {
        val tmp = BatchPreviewFixture.tempDir()
        val picked = listOf(
            BatchPreviewFixture.picked("alice.zip", BatchPreviewFixture.zipOf("a.ERB" to "A".toByteArray())),
            BatchPreviewFixture.picked("bob.zip", BatchPreviewFixture.zipOf("b.ERB" to "B".toByteArray())),
        )
        val ready = BatchPreviewFixture.readyOf(BatchPreviewFixture.previewOf(picked, tmp))

        assertEquals(2, ready.packages.size)
        assertEquals(listOf("alice.zip", "bob.zip"), ready.packages.map { it.displayName })
        assertEquals(ModImportPreview.sourcePackageId(listOf("a.ERB")), ready.packages[0].packageId)
        assertEquals(ModImportPreview.sourcePackageId(listOf("b.ERB")), ready.packages[1].packageId)
        assertEquals(listOf(1, 1), ready.packages.map { it.sourceFileCount })
        assertEquals(listOf(0, 0), ready.packages.map { it.assetEntryCount })
        assertEquals(listOf(null, null), ready.packages.map { it.version })
        assertTrue(ready.warnings.none { it is PreviewWarning.BatchMediaDirConflict })
    }

    @Test
    fun `多包预览行出共 N 个包与逐包摘要`() {
        val tmp = BatchPreviewFixture.tempDir()
        val picked = listOf(
            BatchPreviewFixture.picked("alice.zip", BatchPreviewFixture.zipOf("a.ERB" to "A".toByteArray())),
            BatchPreviewFixture.picked("bob.zip", BatchPreviewFixture.zipOf("b.ERB" to "B".toByteArray())),
        )
        val lines = ModImportPreview.previewLines(BatchPreviewFixture.previewOf(picked, tmp), ModImportCopy.ZH)

        assertTrue("缺包数行：$lines", lines.any { it.contains("共 2 个包") })
        assertEquals("逐包摘要行数不对：$lines", 1, lines.count { it.contains("alice.zip") })
        assertEquals("逐包摘要行数不对：$lines", 1, lines.count { it.contains("bob.zip") })
        assertTrue("摘要行缺版本占位：$lines", lines.any { it.contains("alice.zip") && it.contains("版本未知") })
    }

    @Test
    fun `单包只出一句单包措辞不出包数行`() {
        val tmp = BatchPreviewFixture.tempDir()
        val picked = listOf(
            BatchPreviewFixture.picked("solo.zip", BatchPreviewFixture.zipOf("a.ERB" to "A".toByteArray())),
        )
        val ready = BatchPreviewFixture.readyOf(BatchPreviewFixture.previewOf(picked, tmp))
        val lines = ModImportPreview.previewLines(ready, ModImportCopy.ZH)

        assertEquals(1, ready.packages.size)
        assertTrue("缺单包措辞：$lines", lines.contains(ModImportCopy.ZH.previewPackageCountSingleLine))
        assertTrue("单包不该出包数行：$lines", lines.none { it.contains("共 1 个包") })
        assertTrue("单包不该再出逐包摘要行：$lines", lines.none { it.contains("solo.zip") })
    }

    @Test
    fun `同批两个包声明同一目录时预览期点名`() {
        val tmp = BatchPreviewFixture.tempDir()
        val picked = listOf(
            BatchPreviewFixture.picked(
                "alice.zip",
                BatchPreviewFixture.zipOf(
                    "eratw-pack.json" to BatchPreviewFixture.packMeta("alice", "demo"),
                    "alice.assets/pic.png" to ByteArray(4),
                ),
            ),
            BatchPreviewFixture.picked(
                "bob.zip",
                BatchPreviewFixture.zipOf(
                    "eratw-pack.json" to BatchPreviewFixture.packMeta("bob", "demo"),
                    "bob.assets/pic.png" to ByteArray(4),
                ),
            ),
        )
        val ready = BatchPreviewFixture.readyOf(BatchPreviewFixture.previewOf(picked, tmp))
        val conflicts = ready.warnings.filterIsInstance<PreviewWarning.BatchMediaDirConflict>()

        assertEquals("批内目录共用只该点名一次：$conflicts", 1, conflicts.size)
        assertEquals("demo", conflicts[0].mediaDirName)
        assertEquals("alice.zip", conflicts[0].firstName)
        assertEquals("bob.zip", conflicts[0].secondName)
        val lines = ModImportPreview.previewLines(ready, ModImportCopy.ZH)
        assertTrue("预警行缺批内共用点名：$lines", lines.any { it.contains("共用该目录") })
        assertTrue("预警行缺包名：$lines", lines.any { it.contains("alice.zip") && it.contains("bob.zip") })
    }

    @Test
    fun `不同目录的批不产生批内冲突`() {
        val tmp = BatchPreviewFixture.tempDir()
        val picked = listOf(
            BatchPreviewFixture.picked(
                "alice.zip",
                BatchPreviewFixture.zipOf(
                    "eratw-pack.json" to BatchPreviewFixture.packMeta("alice", "demo"),
                    "alice.assets/pic.png" to ByteArray(4),
                ),
            ),
            BatchPreviewFixture.picked(
                "bob.zip",
                BatchPreviewFixture.zipOf(
                    "eratw-pack.json" to BatchPreviewFixture.packMeta("bob", "another"),
                    "bob.assets/pic.png" to ByteArray(4),
                ),
            ),
        )
        val ready = BatchPreviewFixture.readyOf(BatchPreviewFixture.previewOf(picked, tmp))

        assertTrue(ready.warnings.none { it is PreviewWarning.BatchMediaDirConflict })
        assertEquals(listOf("demo", "another"), ready.packages.map { it.mediaDirName })
    }

    @Test
    fun `裸源文件与 zip 混批各有摘要且 zip 采信版本`() {
        val tmp = BatchPreviewFixture.tempDir()
        val picked = listOf(
            BatchPreviewFixture.picked("口上.ERB", "口上".toByteArray()),
            BatchPreviewFixture.picked(
                "alice.zip",
                BatchPreviewFixture.zipOf(
                    "eratw-pack.json" to BatchPreviewFixture.packMeta("alice", "demo"),
                    "alice.assets/pic.png" to ByteArray(4),
                    "a.ERB" to "A".toByteArray(),
                ),
            ),
        )
        val ready = BatchPreviewFixture.readyOf(BatchPreviewFixture.previewOf(picked, tmp))

        assertEquals(2, ready.packages.size)
        assertEquals("口上.ERB", ready.packages[0].displayName)
        assertNull("裸源文件没有版本来源", ready.packages[0].version)
        assertEquals(1, ready.packages[0].sourceFileCount)
        assertEquals("1.0", ready.packages[1].version)
        assertEquals(1, ready.packages[1].assetEntryCount)
        assertEquals("demo", ready.packages[1].mediaDirName)
    }

    @Test
    fun `元数据不可信时版本回落成未知且不参与目录点名`() {
        val tmp = BatchPreviewFixture.tempDir()
        val picked = listOf(
            BatchPreviewFixture.picked(
                "ghost.zip",
                BatchPreviewFixture.zipOf(
                    "eratw-pack.json" to BatchPreviewFixture.packMeta("ghost", "demo"),
                    "real.assets/pic.png" to ByteArray(4),
                ),
            ),
        )
        val ready = BatchPreviewFixture.readyOf(BatchPreviewFixture.previewOf(picked, tmp))

        assertNull("自述包名与资源前缀不符 ⇒ 版本不可信", ready.packages[0].version)
        assertNull("不可信元数据的 resources 一并归 null", ready.packages[0].mediaDirName)
        assertEquals(1, ready.packages.size)
        val lines = ModImportPreview.previewLines(ready, ModImportCopy.ZH)
        assertTrue("单包的版本未知由字段回落承担，行面只出单包措辞：$lines", lines.contains(ModImportCopy.ZH.previewPackageCountSingleLine))
    }

    @Test
    fun `纯资源包逐包身份走自述包名哈希`() {
        val tmp = BatchPreviewFixture.tempDir()
        val picked = listOf(
            BatchPreviewFixture.picked(
                "alpha.zip",
                BatchPreviewFixture.zipOf(
                    "eratw-pack.json" to BatchPreviewFixture.packMeta("alpha", "demo"),
                    "alpha.assets/pic.png" to ByteArray(4),
                ),
            ),
            BatchPreviewFixture.picked(
                "beta.zip",
                BatchPreviewFixture.zipOf(
                    "eratw-pack.json" to BatchPreviewFixture.packMeta("beta", "another"),
                    "beta.assets/pic.png" to ByteArray(8),
                ),
            ),
        )
        val ready = BatchPreviewFixture.readyOf(BatchPreviewFixture.previewOf(picked, tmp))

        assertEquals(2, ready.packages.size)
        assertEquals(ModImportController.assetPackageIdFor("alpha"), ready.packages[0].packageId)
        assertEquals(ModImportController.assetPackageIdFor("beta"), ready.packages[1].packageId)
        assertEquals(listOf(1, 1), ready.packages.map { it.assetEntryCount })
        assertEquals("1.0", ready.packages[1].version)
        assertEquals(0, ready.code.sourceFileCount)
    }

    @Test
    fun `批内目录共用与已装包目录冲突分开出现`() {
        val tmp = BatchPreviewFixture.tempDir()
        BatchPreviewFixture.writeManifest(tmp, "kojo-other-0000", "demo")
        val picked = listOf(
            BatchPreviewFixture.picked(
                "alice.zip",
                BatchPreviewFixture.zipOf(
                    "eratw-pack.json" to BatchPreviewFixture.packMeta("alice", "demo"),
                    "alice.assets/pic.png" to ByteArray(4),
                ),
            ),
            BatchPreviewFixture.picked(
                "bob.zip",
                BatchPreviewFixture.zipOf(
                    "eratw-pack.json" to BatchPreviewFixture.packMeta("bob", "demo"),
                    "bob.assets/pic.png" to ByteArray(4),
                ),
            ),
        )
        val ready = BatchPreviewFixture.readyOf(BatchPreviewFixture.previewOf(picked, tmp))

        val installed = ready.warnings.filterIsInstance<PreviewWarning.MediaDirConflict>()
        val sameBatch = ready.warnings.filterIsInstance<PreviewWarning.BatchMediaDirConflict>()
        assertEquals("已装包冲突只说明一次：$installed", 1, installed.size)
        assertEquals("kojo-other-0000", installed[0].ownerPackageId)
        assertEquals("demo", installed[0].mediaDirName)
        assertEquals(1, sameBatch.size)
        val lines = ModImportPreview.previewLines(ready, ModImportCopy.ZH)
        assertTrue("缺已装包冲突行：$lines", lines.any { it.contains("kojo-other-0000") })
        assertTrue("缺批内共用行：$lines", lines.any { it.contains("共用该目录") })
    }

    @Test
    fun `三语批摘要行数一致且各出各语言的包数措辞`() {
        val tmp = BatchPreviewFixture.tempDir()
        val picked = listOf(
            BatchPreviewFixture.picked("alice.zip", BatchPreviewFixture.zipOf("a.ERB" to "A".toByteArray())),
            BatchPreviewFixture.picked("bob.zip", BatchPreviewFixture.zipOf("b.ERB" to "B".toByteArray())),
        )
        val preview = BatchPreviewFixture.previewOf(picked, tmp)
        val zh = ModImportPreview.previewLines(preview, ModImportCopy.ZH)
        val en = ModImportPreview.previewLines(preview, ModImportCopy.EN)
        val ja = ModImportPreview.previewLines(preview, ModImportCopy.JA)

        assertEquals(zh.size, en.size)
        assertEquals(zh.size, ja.size)
        assertTrue("ZH 缺包数措辞：$zh", zh.any { it.contains("共 2 个包") })
        assertTrue("EN 缺包数措辞：$en", en.any { it.contains("2 packages selected") })
        assertTrue("JA 缺包数措辞：$ja", ja.any { it.contains("パッケージ 2 件") })
        assertFalse("ZH 不该有逐包行以外的包数重复：$zh", zh.count { it.contains("共 2 个包") } > 1)
    }

    @Test
    fun `同批重复选同一文件按两次列出但同一包身份不互相点名`() {
        val tmp = BatchPreviewFixture.tempDir()
        val zip = BatchPreviewFixture.zipOf(
            "eratw-pack.json" to BatchPreviewFixture.packMeta("alice", "demo"),
            "a.ERB" to "A".toByteArray(),
            "alice.assets/pic.png" to ByteArray(4),
        )
        val picked = listOf(
            BatchPreviewFixture.picked("alice.zip", zip),
            BatchPreviewFixture.picked("alice.zip", zip),
        )
        val ready = BatchPreviewFixture.readyOf(BatchPreviewFixture.previewOf(picked, tmp))

        assertEquals("重复选中按两次列出（不去重）", 2, ready.packages.size)
        assertEquals(
            "同一份内容只能有同一个包身份",
            ready.packages[0].packageId,
            ready.packages[1].packageId,
        )
        assertTrue(
            "同一个包被列两次不该被点名成「两个包共用目录」：${ready.warnings}",
            ready.warnings.none { it is PreviewWarning.BatchMediaDirConflict },
        )
        val lines = ModImportPreview.previewLines(ready, ModImportCopy.ZH)
        assertTrue("缺包数行：$lines", lines.any { it.contains("共 2 个包") })
        assertEquals("两行摘要都应在：$lines", 2, lines.count { it.contains("alice.zip") })
    }
}

/** 批预览单测夹具（内存 zip + 临时 rt2 + 预览入口；函数数压在 detekt 每对象 11 条内）。 */
private object BatchPreviewFixture {

    fun tempDir(): File = Files.createTempDirectory("batch-preview").toFile()

    fun picked(displayName: String, bytes: ByteArray): PickedModFile = PickedModFile(displayName, bytes)

    /** 自述元数据：`packageId` 与 `resources` 由调用方给，包内资源前缀须与 `packageId` 同名才被采信。 */
    fun packMeta(packageId: String, resources: String?): ByteArray {
        val resourcesField = resources?.let { ",\"resources\":\"" + it + "\"" } ?: ""
        return ("{\"schema\":1,\"packageId\":\"" + packageId + "\",\"version\":\"1.0\"" + resourcesField + "}")
            .toByteArray()
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

    fun previewOf(picked: List<PickedModFile>, tmp: File): Preview =
        ModImportPreview.compute(picked, rt2Of(tmp))

    fun readyOf(preview: Preview): Preview.Ready =
        preview as? Preview.Ready ?: throw AssertionError("预览应可用，实为 $preview")
}
