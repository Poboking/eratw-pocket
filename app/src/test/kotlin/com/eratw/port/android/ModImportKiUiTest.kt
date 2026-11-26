package com.eratw.port.android

import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KI-UI 波（BOARD KI-4 / KI-6 / KI-8）的纯层出口判据，全部纯 JVM：
 *
 * - **KI-4 报错专页**：导向行按失败类别指明该找谁（口上包自身 → 口上作者；存储 → 本机；
 *   未预期 → 移植版），错误报告五段定长且 message 原文不吞（I7 可定位纪律）；
 * - **KI-8 月更重复导入**：预览阶段读盘上旧 manifest ⇒ 逐包出「将替换已装旧版」一行，
 *   旧版导入时间取 manifest mtime（读不到走无时间格式），无旧包整行不出；
 * - **KI-6 口上管理卡**：扫描器把 manifest `override-functions` 段（排序去重）与 manifest
 *   mtime（导入时间）填进 [InstalledMod]。
 */
class ModImportKiUiTest {

    // ------------------------------------------------------------------ KI-4：报错导向与错误报告

    @Test
    fun `报错导向——编译与身份指口上作者 存储指本机 未预期指移植版`() {
        assertEquals(
            ModImportCopy.ZH.failureGuidanceAuthorFormat,
            modImportFailureGuidance(ModImportFailureKind.COMPILE, ModImportCopy.ZH),
        )
        assertEquals(
            ModImportCopy.ZH.failureGuidanceAuthorFormat,
            modImportFailureGuidance(ModImportFailureKind.IDENTITY, ModImportCopy.ZH),
        )
        assertEquals(
            ModImportCopy.ZH.failureGuidanceStorageFormat,
            modImportFailureGuidance(ModImportFailureKind.STORAGE, ModImportCopy.ZH),
        )
        assertEquals(
            ModImportCopy.ZH.failureGuidanceUnexpectedFormat,
            modImportFailureGuidance(ModImportFailureKind.UNEXPECTED, ModImportCopy.ZH),
        )
    }

    @Test
    fun `错误报告——五段定长 类别与来源行拼对 message原文不吞`() {
        val message = "E: foo.erb(12): 语法错误\n第二行原文"
        val text = modImportErrorReportText(
            kind = ModImportFailureKind.COMPILE,
            message = message,
            copy = ModImportCopy.ZH,
            nowMillis = 1_700_000_000_000,
            sourceNames = listOf("摩卡橙.zip", "风神少女.zip"),
        )
        val lines = text.split("\n")
        assertEquals("首行 = 报告标题", ModImportCopy.ZH.errorReportTitle, lines[0])
        assertEquals(
            "类别行 = 报错页同一归类",
            ModImportCopy.ZH.fill(ModImportCopy.ZH.errorReportKindFormat, ModImportCopy.ZH.failureTitleCompile),
            lines[1],
        )
        assertTrue(
            "时间行必须含 ISO 形 yyyy-MM-dd HH:mm（与设备语言/冒号字形无关）：${lines[2]}",
            lines[2].contains(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")),
        )
        assertEquals(
            "来源行按逗号连名",
            ModImportCopy.ZH.fill(ModImportCopy.ZH.errorReportSourcesFormat, "摩卡橙.zip, 风神少女.zip"),
            lines[3],
        )
        assertEquals("分隔线固定在 message 之前", "----", lines[4])
        assertEquals("message 原文不吞不改（含换行）", message, lines.drop(5).joinToString("\n"))
    }

    @Test
    fun `错误报告——来源空表省行且时间戳形状稳定`() {
        val text = modImportErrorReportText(
            kind = ModImportFailureKind.STORAGE,
            message = "盘满",
            copy = ModImportCopy.ZH,
            nowMillis = 1_700_000_000_000,
        )
        val lines = text.split("\n")
        assertEquals("无来源 = 四段（标题/类别/时间/分隔线/message 之首段）", 5, lines.size)
        assertTrue(formatImportTimestamp(1_700_000_000_000).matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
    }

    // ------------------------------------------------------------------ KI-8：预览替换提示

    @Test
    fun `预览替换提示——盘上有旧manifest时逐包出替换行且读数齐全`() {
        val tmp = Files.createTempDirectory("eratw-ki-replace").toFile()
        val rt2 = File(File(tmp, "mods"), "rt2")
        rt2.mkdirs()
        val names = listOf("A.ERB", "B.ERH")
        val pid = ModImportPreview.sourcePackageId(names)
        val manifest = File(rt2, "$pid.manifest")
        manifest.writeBytes(ManifestCodec.encode(ModuleDescriptor(packageId = pid, version = "9.9")))
        manifest.setLastModified(1_700_000_000_000)
        val zip = KiFixture.zipOf(
            "A.ERB" to "RETURN 0\n".toByteArray(),
            "B.ERH" to "RETURN 0\n".toByteArray(),
        )
        val preview = ModImportPreview.compute(
            listOf(PickedModFile("月更口上.zip", zip)),
            rt2,
        ) as Preview.Ready
        val face = preview.packages.single()
        assertEquals("旧版本读数来自盘上 manifest", "9.9", face.installedVersion)
        assertEquals("旧版导入时间 = manifest mtime", 1_700_000_000_000, face.installedAtMillis)
        val expected = ModImportCopy.ZH.fill(
            ModImportCopy.ZH.previewReplaceFormat,
            "9.9",
            formatImportTimestamp(1_700_000_000_000),
        )
        assertTrue(
            "预览行里要有「将替换已装旧版 v9.9（旧版导入于 …）」：${ModImportPreview.previewLines(preview, ModImportCopy.ZH)}",
            ModImportPreview.previewLines(preview, ModImportCopy.ZH).contains(expected),
        )
    }

    @Test
    fun `预览替换提示——无旧包不出行 mtime读不到走无时间格式`() {
        // 无旧包：整行不出（既有用例已覆盖 compute 全链，这里直接构造 Ready 验证渲染口径）。
        val clean = Preview.Ready(
            code = CodeFace(0, 0, 0, 0),
            assets = AssetsFace(0, 0),
            media = null,
            warnings = emptyList(),
            packages = listOf(PackageFace("新包.zip", "kojo-1a2b3c4d", "1.0", 1, 0, null)),
        )
        assertTrue(ModImportPreview.previewLines(clean, ModImportCopy.ZH).none { it.contains("将替换") })
        // mtime 读不到（0）：替换行走无时间格式（不显示「导入于 1970-…」这种假时间）。
        val stale = Preview.Ready(
            code = CodeFace(0, 0, 0, 0),
            assets = AssetsFace(0, 0),
            media = null,
            warnings = emptyList(),
            packages = listOf(
                PackageFace(
                    "新包.zip",
                    "kojo-1a2b3c4d",
                    "1.0",
                    1,
                    0,
                    null,
                    installedVersion = "0.9",
                    installedAtMillis = 0L,
                ),
            ),
        )
        val lines = ModImportPreview.previewLines(stale, ModImportCopy.ZH)
        assertTrue(
            "mtime=0 走无时间格式：$lines",
            lines.contains(ModImportCopy.ZH.fill(ModImportCopy.ZH.previewReplaceNoTimeFormat, "0.9")),
        )
    }

    // ------------------------------------------------------------------ KI-6：扫描器新读数

    @Test
    fun `扫描器新读数——覆盖函数排序去重 导入时间取manifest mtime`() {
        val root = Files.createTempDirectory("eratw-ki-scan").toFile()
        try {
            val rt2 = ModEnablement.rt2Dir(root)
            rt2.mkdirs()
            val manifest = File(rt2, "kojo-1a2b3c4d.manifest")
            manifest.writeBytes(
                ManifestCodec.encode(
                    ModuleDescriptor(
                        packageId = "kojo-1a2b3c4d",
                        version = "1.2",
                        overrideFunctions = setOf("Z_FUNC", "A_FUNC", "Z_FUNC"),
                    ),
                ),
            )
            manifest.setLastModified(1_700_000_000_000)
            File(rt2, "kojo-1a2b3c4d.erbm").writeText("module")

            val mod = ModManageScanner.scan(root).mods.single()
            assertEquals("覆盖函数排序去重", listOf("A_FUNC", "Z_FUNC"), mod.overrideFunctions)
            assertEquals("导入时间 = manifest mtime", 1_700_000_000_000, mod.importedAtMillis)
            assertTrue("普通口上包是 IMPORTED 来源", mod.source == ModSource.IMPORTED)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `扫描器新读数——无覆盖声明的包两字段回退缺省`() {
        val root = Files.createTempDirectory("eratw-ki-scan-plain").toFile()
        try {
            val rt2 = ModEnablement.rt2Dir(root)
            rt2.mkdirs()
            File(rt2, "eratw.builtin.manifest").writeBytes(
                ManifestCodec.encode(ModuleDescriptor(packageId = "eratw.builtin")),
            )
            val mod = ModManageScanner.scan(root).mods.single()
            assertTrue("无声明 = 空覆盖清单", mod.overrideFunctions.isEmpty())
            assertTrue("manifest 在场则 mtime 可读", mod.importedAtMillis > 0L)
            assertEquals("内置来源不受影响", ModSource.BUILTIN, mod.source)
        } finally {
            root.deleteRecursively()
        }
    }
}

/** KI-UI 测试夹具（内存 zip；与 ModImportPreviewTest.PreviewFixture 同款小工具）。 */
private object KiFixture {
    fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
