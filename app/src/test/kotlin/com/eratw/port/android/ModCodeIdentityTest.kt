package com.eratw.port.android

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码包身份一致化（ADM 裁决 1–4）的导入侧判据：
 *
 * 1. 代码产物的**运行期身份** = 作者在 `eratw-pack.json` 声明的包名；未声明 / 未采信时回退
 *    内层 `manifest.json` 的 `packageId`（裁决 2）；
 * 2. 内层 `manifest.json` 的 `packageId` 必须等于运行期身份（裁决 3）：形态非法 ⇒ STORAGE，
 *    合法但不一致 ⇒ IDENTITY；
 * 3. 磁盘命名空间身份（`kojo-<8hex>`）不因声明而改：代码地址目录与侧车文件名仍用它（裁决 1）；
 * 4. `<diskPkg>.codeid` 侧车两行冻结格式、原子写；坏档 fail-closed（[CodeIdSidecar.Corrupt]）。
 *
 * 纯 JVM：`mods/rt2` 用临时目录模拟（同 [ModImportCodeArtifactsTest] 口径）。
 */
class ModCodeIdentityTest {

    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        for (root in roots) {
            // Windows 上代码产物是只读的：整树先可写再删。
            root.walkBottomUp().forEach { it.setWritable(true) }
            root.deleteRecursively()
        }
        roots.clear()
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun tempDir(): File =
        Files.createTempDirectory("eratw-code-identity").toFile().also { roots += it }

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

    private fun manifestOf(packageId: String): ByteArray =
        """{"packageId":"$packageId","version":"1","apiVersion":1}""".toByteArray(Charsets.UTF_8)

    private fun codeMod(innerPackageId: String): ByteArray = zipOf(
        "classes.dex" to DEX_BYTES,
        "manifest.json" to manifestOf(innerPackageId),
    )

    private fun importZip(codeMod: ByteArray): ByteArray = zipOf(
        "PATCH.ERB" to "@B_X\nRETURN 0\n".toByteArray(Charsets.UTF_8),
        "code.mod" to codeMod,
    )

    /** 走真实 stage 入口：磁盘身份 + 声明运行期身份原文 + 内层 manifest 身份。 */
    private fun stage(disk: String = DISK_PKG, declared: String?, inner: String): ModImportCodeArtifacts.Staged? =
        ModImportCodeArtifacts.stage(listOf(importZip(codeMod(inner))), disk, declared)

    private fun rejectionOf(block: () -> Unit): CodeArtifactRejectedException {
        try {
            block()
        } catch (e: CodeArtifactRejectedException) {
            return e
        }
        throw AssertionError("应拒收，实际未抛 CodeArtifactRejectedException")
    }

    // ------------------------------------------------------------------
    // 裁决 2：运行期身份来源
    // ------------------------------------------------------------------

    @Test
    fun `声明与内层一致——运行期身份取声明值且侧车原子落盘`() {
        val rt2 = tempDir()
        val staged = stage(declared = DECLARED_ID, inner = DECLARED_ID)
        assertNotNull("声明与内层一致必须通过", staged)
        assertEquals("运行期身份 = 作者声明值", DECLARED_ID, staged!!.runtimePackageId)
        assertEquals("磁盘命名空间身份不因声明而改", DISK_PKG, staged.packageId)

        staged.commit(ModCodeStore(rt2), rt2)

        assertFalse("commit 后暂存文件必须回收", staged.tmpFile.exists())
        assertEquals("产物仍按磁盘包名内容寻址", 1, ModCodeStore(rt2).list(DISK_PKG).size)
        assertTrue("代码地址目录仍用磁盘包名", File(rt2, "code/$DISK_PKG").isDirectory)
        assertFalse("不得按运行期身份另建代码目录", File(rt2, "code/$DECLARED_ID").exists())
        assertEquals(
            "侧车线格式冻结：头行 + 包名 + 尾换行",
            "RT2CODEID/1\n$DECLARED_ID\n",
            File(rt2, "$DISK_PKG.codeid").readText(Charsets.UTF_8),
        )
        assertEquals(CodeIdSidecar.Bound(DECLARED_ID), ModCodeIdentity.read(rt2, DISK_PKG))
    }

    @Test
    fun `未声明——回退内层 manifest 身份`() {
        val rt2 = tempDir()
        val staged = stage(declared = null, inner = "author.fallback")
        assertNotNull("未声明不是坏档，必须通过", staged)
        assertEquals("未声明 ⇒ 运行期身份 = 内层 manifest", "author.fallback", staged!!.runtimePackageId)

        staged.commit(ModCodeStore(rt2), rt2)
        assertEquals("侧车记的也是回退值", CodeIdSidecar.Bound("author.fallback"), ModCodeIdentity.read(rt2, DISK_PKG))
    }

    @Test
    fun `声明合法但内层不一致——IDENTITY 并点名两侧`() {
        val rejected = rejectionOf { stage(declared = "author.declared", inner = "author.other") }
        assertEquals("配错属于身份失败面", ModImportFailureKind.IDENTITY, rejected.kind)
        val message = rejected.message.orEmpty()
        assertTrue("必须点名内层值：$message", "author.other" in message)
        assertTrue("必须点名运行期身份：$message", "author.declared" in message)
    }

    // ------------------------------------------------------------------
    // 裁决 3：声明形态 / 内层形态
    // ------------------------------------------------------------------

    @Test
    fun `声明形态非法——STORAGE 不静默回退`() {
        val rejected = rejectionOf { stage(declared = "bad id!", inner = "bad id!") }
        assertEquals("坏声明是坏档，不是配错", ModImportFailureKind.STORAGE, rejected.kind)
        assertTrue("点名形态不合法：${rejected.message}", "形态不合法" in rejected.message.orEmpty())
    }

    @Test
    fun `声明缺失且内层形态非法——STORAGE`() {
        val rejected = rejectionOf { stage(declared = null, inner = "bad id!") }
        assertEquals("内层 manifest 形态非法是坏档", ModImportFailureKind.STORAGE, rejected.kind)
    }

    @Test
    fun `无代码产物——stage 返回 null 且不产生身份侧车`() {
        val rt2 = tempDir()
        val plain = zipOf("PATCH.ERB" to "@B_X\nRETURN 0\n".toByteArray(Charsets.UTF_8))
        assertNull(
            "普通口上包零副作用",
            ModImportCodeArtifacts.stage(listOf(plain), DISK_PKG, DECLARED_ID),
        )
        assertTrue("不得写任何文件", rt2.listFiles().orEmpty().isEmpty())
        assertEquals(CodeIdSidecar.Absent, ModCodeIdentity.read(rt2, DISK_PKG))
    }

    // ------------------------------------------------------------------
    // 裁决 4：侧车读侧 fail-closed
    // ------------------------------------------------------------------

    @Test
    fun `侧车坏档——三种形状都折 Corrupt 不猜`() {
        val cases = mapOf(
            "头不对" to "RT2CODEID/2\nauthor.x\n",
            "行数不对" to "RT2CODEID/1\nauthor.x",
            "包名形态非法" to "RT2CODEID/1\nbad id!\n",
        )
        for ((label, content) in cases) {
            val rt2 = tempDir()
            File(rt2, "$DISK_PKG.codeid").writeText(content, Charsets.UTF_8)
            assertEquals("坏档 fail-closed：$label", CodeIdSidecar.Corrupt, ModCodeIdentity.read(rt2, DISK_PKG))
        }
    }

    @Test
    fun `侧车超长——Corrupt 不读进堆`() {
        val rt2 = tempDir()
        File(rt2, "$DISK_PKG.codeid").writeText("RT2CODEID/1\n${"a".repeat(300)}\n", Charsets.UTF_8)
        assertEquals(CodeIdSidecar.Corrupt, ModCodeIdentity.read(rt2, DISK_PKG))
    }

    @Test
    fun `旧侧车被新声明原子覆盖——重导换身份以最新为准`() {
        val rt2 = tempDir()
        ModCodeIdentity.write(rt2, DISK_PKG, "author.old")
        assertEquals(CodeIdSidecar.Bound("author.old"), ModCodeIdentity.read(rt2, DISK_PKG))

        ModCodeIdentity.write(rt2, DISK_PKG, "author.new")
        assertEquals("重导换身份以最新为准", CodeIdSidecar.Bound("author.new"), ModCodeIdentity.read(rt2, DISK_PKG))
        assertEquals("不得留临时残留", 1, rt2.listFiles().orEmpty().count { it.name.endsWith(".codeid") })
    }

    @Test
    fun `clear 幂等——无代码产物的包不留旧身份`() {
        val rt2 = tempDir()
        ModCodeIdentity.write(rt2, DISK_PKG, DECLARED_ID)
        assertTrue("首删成功", ModCodeIdentity.clear(rt2, DISK_PKG))
        assertEquals(CodeIdSidecar.Absent, ModCodeIdentity.read(rt2, DISK_PKG))
        assertTrue("本来就没有也算成功（幂等）", ModCodeIdentity.clear(rt2, DISK_PKG))
    }

    private companion object {
        /** 磁盘命名空间身份（内容派生，玩家不可读）。 */
        const val DISK_PKG = "kojo-1a2b3c4d"

        /** 作者声明的运行期身份（与磁盘身份结构性不同）。 */
        const val DECLARED_ID = "author.codemod"

        val DEX_BYTES = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)
    }
}
