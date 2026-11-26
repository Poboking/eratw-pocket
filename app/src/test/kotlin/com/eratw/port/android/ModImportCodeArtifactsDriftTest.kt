package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 漂移检测在导入链上的接线判据（[ModImportCodeArtifacts.stage] 的取证 sink）：
 *
 * 1. 双源漂移 ⇒ sink 收到稳定日志行，但 **stage 照常返回 Staged**（检测不改变导入结局）；
 * 2. 无漂移 / 无 `code.mod` / 内层身份不一致 ⇒ sink 零调用（不刷噪声、也不越过身份闸取证）；
 * 3. 默认 sink（logcat）在 JVM 单测里被 "not mocked" 顶到时必须被吞掉，主链不炸。
 *
 * 纯 JVM：zip 在内存里构造，暂存文件落系统临时目录（测试结束由 discard 回收）。
 */
class ModImportCodeArtifactsDriftTest {

    @Test
    fun `compiledSurface 双源漂移——sink 收到 ERROR 行且导入结局不变`() {
        val lines = ArrayList<String>()
        val zip = importZip(
            artifactZip(codeManifest(compiledSurface = SURFACE_CODE)),
            packMeta(compiledSurface = SURFACE_ENGINE),
        )
        val staged = ModImportCodeArtifacts.stage(listOf(zip), PKG) { line -> lines += line }
        assertNotNull("漂移只取证，不得改变导入结局", staged)
        staged!!.discard()
        assertEquals("一处漂移一行日志", 1, lines.size)
        val line = lines.single()
        assertTrue("先看到错误级", line.contains("severity=ERROR"))
        assertTrue("点名字段", line.contains("field=compiledSurface"))
        assertTrue("带包侧采信值", line.contains("eratw-pack=$SURFACE_ENGINE"))
        assertTrue("带内层自述值", line.contains("内层manifest=$SURFACE_CODE"))
    }

    @Test
    fun `entrypoint 双源冲突——sink 收到 ERROR 行且导入结局不变`() {
        val lines = ArrayList<String>()
        val zip = importZip(
            artifactZip(codeManifest(entrypoint = INNER_ENTRY)),
            packMeta(entrypoint = PACK_ENTRY),
        )
        val staged = ModImportCodeArtifacts.stage(listOf(zip), PKG) { line -> lines += line }
        assertNotNull("入口不一致只取证，不得改变导入结局", staged)
        staged!!.discard()
        assertEquals("一处漂移一行日志", 1, lines.size)
        val line = lines.single()
        assertTrue("入口漂移按错误级", line.contains("severity=ERROR"))
        assertTrue("点名字段", line.contains("field=entrypoint"))
        assertTrue("带包根采信值", line.contains("eratw-pack=$PACK_ENTRY"))
        assertTrue("带内层自述值", line.contains("内层manifest=$INNER_ENTRY"))
    }

    @Test
    fun `内层声明 entrypoint、包根未声明——INFO 行且导入结局不变`() {
        val lines = ArrayList<String>()
        val zip = importZip(
            artifactZip(codeManifest(entrypoint = INNER_ENTRY)),
            packMeta(apiVersion = null),
        )
        val staged = ModImportCodeArtifacts.stage(listOf(zip), PKG) { line -> lines += line }
        assertNotNull("包根缺该键属预期形态，不得改变导入结局", staged)
        staged!!.discard()
        assertEquals("内层单源 + 包根未声明：两键各一行事实", 2, lines.size)
        val line = lines.first { it.contains("field=entrypoint") }
        assertTrue("包根写侧本代不输出该键 ⇒ 记 INFO", line.contains("severity=INFO"))
        assertTrue("形态是内层单源", line.contains("kind=INNER_ONLY"))
        assertTrue("包侧固定写未声明", line.contains("eratw-pack=<未声明>"))
        assertTrue("带内层自述值", line.contains("内层manifest=$INNER_ENTRY"))
        val api = lines.first { it.contains("field=apiVersion") }
        assertTrue("apiVersion 同理记 INFO", api.contains("severity=INFO"))
    }

    @Test
    fun `两侧一致——sink 零调用`() {
        val lines = ArrayList<String>()
        val staged = ModImportCodeArtifacts.stage(
            listOf(
                importZip(
                    artifactZip(codeManifest(compiledSurface = SURFACE_ENGINE)),
                    packMeta(compiledSurface = SURFACE_ENGINE),
                ),
            ),
            PKG,
        ) { line -> lines += line }
        assertNotNull(staged)
        staged!!.discard()
        assertTrue("无漂移不得刷日志", lines.isEmpty())
    }

    @Test
    fun `内层身份不一致——仍判 IDENTITY 且取证不发生`() {
        val lines = ArrayList<String>()
        val failure = rejectionOf {
            // 声明了运行期身份 PKG，内层写着别人的包名 ⇒ 配错（身份一致化后不再比磁盘包名）。
            ModImportCodeArtifacts.stage(
                listOf(importZip(artifactZip(codeManifest(packageId = "kojo-deadbeef")), packMeta())),
                PKG,
                PKG,
            ) { line -> lines += line }
        }
        assertEquals(ModImportFailureKind.IDENTITY, failure.kind)
        assertTrue("身份闸之后才取证：拒收的包不产生漂移日志", lines.isEmpty())
    }

    @Test
    fun `默认 sink 在 JVM——logcat stub 抛 not mocked 也被吞掉`() {
        val zip = importZip(
            artifactZip(codeManifest(compiledSurface = SURFACE_CODE)),
            packMeta(compiledSurface = SURFACE_ENGINE),
        )
        val staged = ModImportCodeArtifacts.stage(listOf(zip), PKG)
        assertNotNull("默认 sink 的运行时异常不得把导入带崩", staged)
        staged!!.discard()
    }

    @Test
    fun `没有 code_mod——stage 返回 null 且 sink 零调用`() {
        val lines = ArrayList<String>()
        val staged = ModImportCodeArtifacts.stage(
            listOf(importZip(codeMod = null, packMeta = packMeta())),
            PKG,
        ) { line -> lines += line }
        assertNull("普通口上包不进代码产物面", staged)
        assertTrue(lines.isEmpty())
    }

    // ------------------------------------------------------------------
    // 夹具：内存 zip / 内层清单 / 包元数据
    // ------------------------------------------------------------------

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

    private fun codeManifest(
        packageId: String = PKG,
        version: String = VERSION,
        compiledSurface: Int? = null,
        entrypoint: String? = null,
        apiVersion: Int = API_VERSION,
    ): ByteArray {
        val surface = compiledSurface?.let { value -> ",\"compiledSurface\":$value" } ?: ""
        val entry = entrypoint?.let { value -> ",\"entrypoint\":\"$value\"" } ?: ""
        return """{"packageId":"$packageId","version":"$version","apiVersion":$apiVersion$surface$entry}"""
            .toByteArray(Charsets.UTF_8)
    }

    private fun packMeta(
        version: String = VERSION,
        compiledSurface: Int? = null,
        entrypoint: String? = null,
        apiVersion: Int? = API_VERSION,
    ): ByteArray {
        val surface = compiledSurface?.let { value -> ",\"compiledSurface\":$value" } ?: ""
        val entry = entrypoint?.let { value -> ",\"entrypoint\":\"$value\"" } ?: ""
        val api = apiVersion?.let { value -> ",\"apiVersion\":$value" } ?: ""
        return """{"schema":1,"packageId":"$PKG","version":"$version"$surface$entry$api}"""
            .toByteArray(Charsets.UTF_8)
    }

    private fun artifactZip(manifest: ByteArray): ByteArray =
        zipOf(DEX_ENTRY to DEX_BYTES, MANIFEST_ENTRY to manifest)

    private fun importZip(codeMod: ByteArray?, packMeta: ByteArray?): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries += "PATCH.ERB" to "@B_X\nRETURN 0\n".toByteArray(Charsets.UTF_8)
        if (packMeta != null) entries += PACK_META_ENTRY_NAME to packMeta
        if (codeMod != null) entries += CODE_MOD_ENTRY to codeMod
        return zipOf(*entries.toTypedArray())
    }

    private fun rejectionOf(block: () -> Unit): CodeArtifactRejectedException {
        try {
            block()
        } catch (e: CodeArtifactRejectedException) {
            return e
        }
        throw AssertionError("应拒收，实际未抛 CodeArtifactRejectedException")
    }

    private companion object {
        const val PKG = "kojo-1a2b3c4d"
        const val VERSION = "1.0"
        const val SURFACE_ENGINE = 18
        const val SURFACE_CODE = 20
        const val API_VERSION = 1
        const val INNER_ENTRY = "demo.InnerEntry"
        const val PACK_ENTRY = "demo.PackEntry"
        const val DEX_ENTRY = "classes.dex"
        const val MANIFEST_ENTRY = "manifest.json"
        val DEX_BYTES = byteArrayOf(0x64, 0x65, 0x78, 0x0a)
    }
}
