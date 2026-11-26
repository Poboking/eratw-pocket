package com.eratw.port.android

import org.junit.After
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
 * ADR-013 D1 代码产物面（导入端）的出口判据：
 *
 * 1. 包内没有 `code.mod` ⇒ [ModImportCodeArtifacts.stage] 返回 null（普通口上包零副作用）；
 * 2. 有 `code.mod` ⇒ 整份字节校验（体积 / 内层 zip 形状 / classes.dex / manifest 身份）后暂存
 *    到系统临时目录，[ModImportCodeArtifacts.Staged.commit] 才走 [ModCodeStore.install]
 *    的内容寻址落盘（只读、sha 前 16 位点名、同内容复用、 différent 内容只增不改）；
 * 3. 失败语义 fail-closed on presence：坏档/超限 → STORAGE，包身份不符 → IDENTITY，
 *    两种都带 [CodeArtifactRejectedException.kind] 上屏；暂存文件任何路径都不留。
 *
 * 纯 JVM：不碰 Android 类，`mods/rt2` 用临时目录模拟。
 */
class ModImportCodeArtifactsTest {

    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        for (root in roots) {
            // Windows 上产物是只读的：整树先可写再删，否则 deleteRecursively 静默失败。
            root.walkBottomUp().forEach { it.setWritable(true) }
            root.deleteRecursively()
        }
        roots.clear()
    }

    // ------------------------------------------------------------------
    // 夹具：临时目录 / zip 构造 / 合法 .mod 包
    // ------------------------------------------------------------------

    private fun tempDir(): File =
        Files.createTempDirectory("eratw-code-artifact").toFile().also { roots += it }

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

    /** 合法 `.mod` 包字节：可选 dex / manifest 以便逐个判据做减法。 */
    private fun artifactZip(
        packageId: String = PKG,
        dex: ByteArray? = DEX_BYTES,
        manifest: ByteArray? = null,
    ): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        if (dex != null) entries += DEX_ENTRY to dex
        entries += MANIFEST_ENTRY to (manifest ?: manifestOf(packageId))
        return zipOf(*entries.toTypedArray())
    }

    /** 外层导入 zip：是否带 `code.mod`、带入哪份 payload。 */
    private fun importZip(codeMod: ByteArray?): ByteArray =
        if (codeMod == null) {
            zipOf("PATCH.ERB" to "@B_X\nRETURN 0\n".toByteArray(Charsets.UTF_8))
        } else {
            zipOf(
                "PATCH.ERB" to "@B_X\nRETURN 0\n".toByteArray(Charsets.UTF_8),
                CODE_MOD_ENTRY to codeMod,
            )
        }

    /** [declaredPackageId] = `eratw-pack.json` 声明的运行期身份原文（null = 未声明/未采信）。 */
    private fun stageOf(
        zips: List<ByteArray>,
        packageId: String = PKG,
        declaredPackageId: String? = null,
    ): ModImportCodeArtifacts.Staged? =
        ModImportCodeArtifacts.stage(zips, packageId, declaredPackageId)

    /** 期望拒收时取出异常（kind 是上屏通道判据，必须逐例断言）。 */
    private fun rejectionOf(block: () -> Unit): CodeArtifactRejectedException {
        try {
            block()
        } catch (e: CodeArtifactRejectedException) {
            return e
        }
        throw AssertionError("应拒收，实际未抛 CodeArtifactRejectedException")
    }

    private fun storeIn(root: File): ModCodeStore = ModCodeStore(root)

    // ------------------------------------------------------------------
    // 判据 1：无代码条目 ⇒ null，正式目录零副作用
    // ------------------------------------------------------------------

    @Test
    fun `无代码产物条目——stage 返回 null 且不建 code 目录`() {
        val rt2 = tempDir()
        val staged = stageOf(listOf(importZip(codeMod = null)))
        assertNull("普通口上包不得进入代码产物面", staged)
        assertFalse("没有 code.mod 时不得新建 code/ 目录", ModCodeStore(rt2).codeDir().exists())
    }

    @Test
    fun `空 zip 列表——stage 返回 null`() {
        assertNull(stageOf(emptyList()))
    }

    // ------------------------------------------------------------------
    // 判据 2：合法产物 stage → commit
    // ------------------------------------------------------------------

    @Test
    fun `合法代码产物——stage 暂存到系统临时目录且 sha 取自条目内容`() {
        val payload = artifactZip()
        val staged = stageOf(listOf(importZip(payload)))
        assertNotNull("带 code.mod 的包必须进代码产物面", staged)
        staged!!
        assertTrue("暂存文件退出时仍在", staged.tmpFile.isFile)
        assertEquals("暂存字节 = 条目字节", payload.toList(), staged.tmpFile.readBytes().toList())
        assertEquals("sha256 是 64 位小写 hex", 64, staged.sha256Hex.length)
        assertEquals("sha256 只认小写 hex", staged.sha256Hex.lowercase(), staged.sha256Hex)
        assertEquals("sizeBytes = 条目字节数", payload.size.toLong(), staged.sizeBytes)
        val prefixed = staged.tmpFile.name.startsWith("eratw-code-artifact-")
        assertTrue("暂存文件名带本面前缀（便于回收排查）", prefixed)
    }

    @Test
    fun `合法代码产物——commit 落内容寻址只读产物且字节一致`() {
        val root = tempDir()
        val payload = artifactZip()
        val store = storeIn(root)
        val staged = stageOf(listOf(importZip(payload)))!!
        staged.commit(store, root)
        assertFalse("commit 后暂存文件必须被回收", staged.tmpFile.exists())
        val artifacts = store.list(PKG)
        assertEquals(1, artifacts.size)
        val artifact = artifacts.single()
        assertEquals("地址键 = sha256 前 16 位", staged.sha256Hex.substring(0, 16), artifact.file.nameWithoutExtension)
        assertEquals("盘上字节 = 条目字节", payload.toList(), artifact.file.readBytes().toList())
        assertEquals("产物大小读数", payload.size.toLong(), artifact.sizeBytes)
        assertFalse("产物必须只读（Android 14+ DCL 硬约束）", artifact.file.canWrite())
        assertEquals("产物地址键 = 内容 sha256 前 16 位", sha256Of(payload).substring(0, 16), artifact.sha256Hex)
    }

    @Test
    fun `重复导入同内容——commit 幂等复用同一产物文件`() {
        val root = tempDir()
        val store = storeIn(root)
        val payload = artifactZip()
        stageOf(listOf(importZip(payload)))!!.commit(store, root)
        val first = store.list(PKG).single()
        stageOf(listOf(importZip(payload)))!!.commit(store, root)
        val second = store.list(PKG).single()
        assertEquals("同内容重导只增一个文件", first.file.absolutePath, second.file.absolutePath)
        assertEquals("旧文件仍在且被复用", first.file.absolutePath, store.list(PKG).single().file.absolutePath)
    }

    @Test
    fun `不同内容新版本——升级后同包只留新版本（旧产物被清）`() {
        val root = tempDir()
        val store = storeIn(root)
        val v1 = artifactZip(dex = byteArrayOf(0x64, 0x65, 0x78, 0x01))
        val v2 = artifactZip(dex = byteArrayOf(0x64, 0x65, 0x78, 0x02))
        stageOf(listOf(importZip(v1)))!!.commit(store, root)
        val v1File = store.list(PKG).single().file
        val v2Staged = stageOf(listOf(importZip(v2)))!!
        v2Staged.commit(store, root)
        val artifacts = store.list(PKG)
        assertEquals("升级后同包恰剩一件（剩两件装载端就按多版本歧义永久拒载）", 1, artifacts.size)
        assertEquals(
            "留下的必须是最新装入的地址键",
            v2Staged.sha256Hex.substring(0, 16),
            artifacts.single().file.nameWithoutExtension,
        )
        assertFalse("旧版本文件必须被删", v1File.exists())
        assertTrue("盘上字节 = v2", artifacts.single().file.readBytes().toList() == v2.toList())
    }

    @Test
    fun `升级清理只碰本包——别的包产物一件不少`() {
        val root = tempDir()
        val store = storeIn(root)
        val other = artifactZip(dex = byteArrayOf(0x64, 0x65, 0x78, 0x03))
        stageOf(listOf(importZip(other)), packageId = OTHER_PKG)!!.commit(store, root)
        assertEquals("先给别的包装一件", 1, store.list(OTHER_PKG).size)
        stageOf(listOf(importZip(artifactZip(dex = byteArrayOf(0x64, 0x65, 0x78, 0x01)))))!!.commit(store, root)
        stageOf(listOf(importZip(artifactZip(dex = byteArrayOf(0x64, 0x65, 0x78, 0x02)))))!!.commit(store, root)
        assertEquals("本包升级后只剩一件", 1, store.list(PKG).size)
        assertEquals("其它包的产物不得被本包清理波及", 1, store.list(OTHER_PKG).size)
    }

    @Test
    fun `旧版本删不掉——只 WARN 不抛，之后重导幂等收干净`() {
        val root = tempDir()
        val store = storeIn(root)
        val v1 = artifactZip(dex = byteArrayOf(0x64, 0x65, 0x78, 0x01))
        val v2 = artifactZip(dex = byteArrayOf(0x64, 0x65, 0x78, 0x02))
        stageOf(listOf(importZip(v1)))!!.commit(store, root)
        val refuseDelete: (File) -> Boolean = { false }
        stageOf(listOf(importZip(v2)))!!.commit(store, root, refuseDelete)
        assertEquals("删除失败不许让导入失败，旧版本保留待下次", 2, store.list(PKG).size)
        stageOf(listOf(importZip(v2)))!!.commit(store, root)
        assertEquals("同内容重导（默认删除器）后只剩最新一件", 1, store.list(PKG).size)
        assertTrue("留下的必须是 v2", store.list(PKG).single().file.readBytes().toList() == v2.toList())
    }

    // ------------------------------------------------------------------
    // 判据 3：失败语义（fail-closed on presence）
    // ------------------------------------------------------------------

    @Test
    fun `超出体积上限——拒收为 STORAGE`() {
        // 随机字节：零填充会先撞外层 zip 的压缩比闸（ModZipGuard），测不到本面的体积闸。
        val oversize = ByteArray((MAX_CODE_MOD_BYTES + 1L).toInt())
        java.util.Random(7L).nextBytes(oversize)
        val rejected = rejectionOf { stageOf(listOf(importZip(oversize))) }
        assertEquals(ModImportFailureKind.STORAGE, rejected.kind)
        assertTrue("文案要能定位体积/上限", (rejected.message ?: "").contains(MAX_CODE_MOD_BYTES.toString()))
    }

    @Test
    fun `条目不是合法 zip——拒收为 STORAGE`() {
        val rejected = rejectionOf {
            stageOf(listOf(importZip("not-a-zip".toByteArray(Charsets.UTF_8))))
        }
        assertEquals(ModImportFailureKind.STORAGE, rejected.kind)
    }

    @Test
    fun `内层缺 classes dex——拒收为 STORAGE`() {
        val rejected = rejectionOf { stageOf(listOf(importZip(artifactZip(dex = null)))) }
        assertEquals(ModImportFailureKind.STORAGE, rejected.kind)
        assertTrue((rejected.message ?: "").contains("classes.dex"))
    }

    @Test
    fun `内层缺 manifest json——拒收为 STORAGE`() {
        val payload = zipOf(DEX_ENTRY to DEX_BYTES)
        val rejected = rejectionOf { stageOf(listOf(importZip(payload))) }
        assertEquals(ModImportFailureKind.STORAGE, rejected.kind)
        assertTrue((rejected.message ?: "").contains("manifest.json"))
    }

    @Test
    fun `manifest 非法 JSON——拒收为 STORAGE`() {
        val payload = artifactZip(manifest = "{not json".toByteArray(Charsets.UTF_8))
        val rejected = rejectionOf { stageOf(listOf(importZip(payload))) }
        assertEquals(ModImportFailureKind.STORAGE, rejected.kind)
    }

    @Test
    fun `manifest packageId 与声明身份不一致——拒收为 IDENTITY`() {
        // 身份一致化后：内层 manifest 比的是**运行期身份**（声明值），不再比磁盘 kojo 包名。
        val payload = artifactZip(packageId = "kojo-deadbeef")
        val rejected = rejectionOf { stageOf(listOf(importZip(payload)), PKG, PKG) }
        assertEquals("包与代码产物配错属于身份失败面", ModImportFailureKind.IDENTITY, rejected.kind)
    }

    @Test
    fun `内层非目标条目是压缩炸弹——拒收为 STORAGE（内层 zip 也要过体积闸）`() {
        // 炸弹条目排在 classes.dex 之前：scanInnerArtifact 推进条目时 getNextEntry 的 closeEntry
        // 会把非目标条目静默全量 inflate——dex/manifest 各有 per-entry 上限，非目标条目没有，
        // 不挂总账炸弹就在导入期被无界解压且闸看不见。
        val payload = zipOf(
            "pack-res.bin" to ByteArray(BOMB_ENTRY_BYTES),
            DEX_ENTRY to DEX_BYTES,
            MANIFEST_ENTRY to manifestOf(PKG),
        )
        val rejected = rejectionOf { stageOf(listOf(importZip(payload))) }
        assertEquals(ModImportFailureKind.STORAGE, rejected.kind)
        assertTrue("要点名内层越限：${rejected.message}", (rejected.message ?: "").contains("解压量越限"))
    }

    @Test
    fun `多个 zip 各带代码产物——拒收为 STORAGE（不猜用哪一份）`() {
        val rejected = rejectionOf {
            stageOf(listOf(importZip(artifactZip()), importZip(artifactZip())))
        }
        assertEquals(ModImportFailureKind.STORAGE, rejected.kind)
    }

    @Test
    fun `目标包身份非法——拒收为 STORAGE`() {
        val rejected = rejectionOf { stageOf(listOf(importZip(artifactZip())), "bad/id") }
        assertEquals(ModImportFailureKind.STORAGE, rejected.kind)
    }

    // ------------------------------------------------------------------
    // 判据 4：暂存文件回收
    // ------------------------------------------------------------------

    @Test
    fun `discard——回收暂存文件且幂等`() {
        val staged = stageOf(listOf(importZip(artifactZip())))!!
        assertTrue(staged.tmpFile.isFile)
        staged.discard()
        assertFalse("discard 后不留 tmp", staged.tmpFile.exists())
        staged.discard()
        assertFalse("重复 discard 是空操作", staged.tmpFile.exists())
    }

    @Test
    fun `校验失败——不落暂存文件（失败面零半套产物）`() {
        val root = tempDir()
        val store = storeIn(root)
        val before = store.list(PKG)
        rejectionOf { stageOf(listOf(importZip(artifactZip(packageId = "kojo-deadbeef"))), PKG, PKG) }
        assertEquals("失败不得落产物", before, store.list(PKG))
    }

    private fun sha256Of(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PKG = "kojo-1a2b3c4d"
        const val OTHER_PKG = "kojo-0f0e0d0c"
        const val DEX_ENTRY = "classes.dex"
        const val MANIFEST_ENTRY = "manifest.json"

        /** 内层炸弹样本的解压体积（256 MiB 全零；单条目 8 MiB 闸必先于压缩比闸触发）。 */
        const val BOMB_ENTRY_BYTES = 256 * 1024 * 1024
        val DEX_BYTES = byteArrayOf(0x64, 0x65, 0x78, 0x0a)
    }
}
