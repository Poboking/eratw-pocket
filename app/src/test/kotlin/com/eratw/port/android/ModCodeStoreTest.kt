package com.eratw.port.android

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 与 [MOD_CODE_ARTIFACT_EXTENSION] 组成文件名时的地址键长度。 */
private const val SHA_PREFIX_LENGTH: Int = 16

/** install 返回的完整 sha256 hex 长度。 */
private const val SHA256_HEX_LENGTH: Int = 64

private const val PKG: String = "demo.mod"
private const val OTHER_PKG: String = "other.mod"
private const val HEX_KEY: String = "0123456789abcdef"

/**
 * `ModCodeStore` 的 JUnit4 单测：只碰纯 JVM 文件系统（无 Robolectric，不触 `android.util.Log`
 * 失败日志分支）。
 *
 * 清理口径：本类产出的包体按设计是只读的，Windows 的只读属性会挡住 `File.delete()`，故
 * [tearDown] 先整树 `setWritable(true)` 再删。
 */
class ModCodeStoreTest {

    private lateinit var tempRoot: File
    private lateinit var store: ModCodeStore

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("mod-code-store-").toFile()
        store = ModCodeStore(File(tempRoot, "rt2"))
    }

    @After
    fun tearDown() {
        tempRoot.walkBottomUp().forEach { entry ->
            entry.setWritable(true)
            entry.delete()
        }
    }

    @Test
    fun `安装后可读为只读且文件名是地址键`() {
        val payload = "dex-payload-v1".toByteArray()
        val artifact = store.install(PKG, payload)

        assertTrue("产物必须是普通文件", artifact.file.isFile)
        assertTrue("产物必须可读", artifact.file.canRead())
        assertFalse("产物必须只读（Android 14+ DCL 硬约束）", artifact.file.canWrite())
        assertEquals(payload.size.toLong(), artifact.sizeBytes)
        assertEquals(SHA256_HEX_LENGTH, artifact.sha256Hex.length)
        assertEquals(
            artifact.sha256Hex.substring(0, SHA_PREFIX_LENGTH) + MOD_CODE_ARTIFACT_EXTENSION,
            artifact.file.name,
        )
        assertEquals(artifact.file, store.list(PKG).single().file)
    }

    @Test
    fun `同内容二次安装复用同一文件不新建`() {
        val payload = "same-payload".toByteArray()
        val first = store.install(PKG, payload)
        val second = store.install(PKG, payload)

        assertEquals(first.file.canonicalPath, second.file.canonicalPath)
        assertEquals(first.sha256Hex, second.sha256Hex)
        assertEquals(1, store.list(PKG).size)
    }

    @Test
    fun `不同内容落到新地址且旧文件仍在`() {
        val first = store.install(PKG, "payload-one".toByteArray())
        val second = store.install(PKG, "payload-two".toByteArray())

        assertNotEquals(first.sha256Hex, second.sha256Hex)
        assertNotEquals(first.file.canonicalPath, second.file.canonicalPath)
        assertTrue("旧版本不许被覆盖/删除", first.file.isFile)
        assertTrue("新版本必须落盘", second.file.isFile)

        val listed = store.list(PKG)
        assertEquals(2, listed.size)
        assertEquals(listed.sortedBy { it.sha256Hex }.map { it.sha256Hex }, listed.map { it.sha256Hex })
    }

    @Test
    fun `盘上包体被篡改后二次安装拒载`() {
        val payload = "payload-original".toByteArray()
        val artifact = store.install(PKG, payload)
        assertTrue(artifact.file.setWritable(true))
        artifact.file.writeBytes("payload-tampered".toByteArray())

        val failure = runCatching { store.install(PKG, payload) }.exceptionOrNull()
        assertTrue("篡改盘上包体必须拒载，实际：$failure", failure is ModCodeIntegrityException)
        assertEquals("坏件不许被静默覆盖成新内容", 1, store.list(PKG).size)
    }

    @Test
    fun `声明 sha 不符或形状非法都拒载`() {
        val payload = "payload-gated".toByteArray()
        val wrongSha = "0".repeat(SHA256_HEX_LENGTH)

        val mismatch = runCatching { store.install(PKG, payload, wrongSha) }.exceptionOrNull()
        assertTrue("期望 sha 不符必须拒载，实际：$mismatch", mismatch is ModCodeIntegrityException)
        assertFalse("校验必须先于建包目录", File(store.codeDir(), PKG).exists())

        val badShape = runCatching { store.install(PKG, payload, "../escape") }.exceptionOrNull()
        assertTrue("非法 sha 形状必须抛 IAE，实际：$badShape", badShape is IllegalArgumentException)
        assertTrue("非法 sha 不许落任何产物", store.list(PKG).isEmpty())
    }

    @Test
    fun `空包体与非法 packageId 被拒`() {
        val empty = runCatching { store.install(PKG, ByteArray(0)) }.exceptionOrNull()
        assertTrue("空包体必须拒载，实际：$empty", empty is ModCodeIntegrityException)

        val escaped = runCatching { store.install("../escape", "x".toByteArray()) }.exceptionOrNull()
        assertTrue("非法 packageId 必须抛 IAE，实际：$escaped", escaped is IllegalArgumentException)

        val badList = runCatching { store.list("bad/name") }.exceptionOrNull()
        assertTrue("list 非法 packageId 必须抛 IAE，实际：$badList", badList is IllegalArgumentException)

        val badClear = runCatching { store.clear("") }.exceptionOrNull()
        assertTrue("clear 非法 packageId 必须抛 IAE，实际：$badClear", badClear is IllegalArgumentException)
    }

    @Test
    fun `list 跳过坏名与目录并按地址键排序`() {
        val dir = File(store.codeDir(), PKG)
        assertTrue(dir.mkdirs())
        File(dir, "$HEX_KEY$MOD_CODE_ARTIFACT_EXTENSION").writeBytes("ok".toByteArray())
        File(dir, "fedcba9876543210$MOD_CODE_ARTIFACT_EXTENSION").writeBytes("ok2".toByteArray())
        File(dir, "0123456789ABCDEF$MOD_CODE_ARTIFACT_EXTENSION").writeBytes("upper".toByteArray())
        File(dir, "short$MOD_CODE_ARTIFACT_EXTENSION").writeBytes("short".toByteArray())
        File(dir, "$HEX_KEY.dex").writeBytes("wrong-ext".toByteArray())
        assertTrue(File(dir, "aaaaaaaaaaaaaaaa$MOD_CODE_ARTIFACT_EXTENSION").mkdirs())

        val listed = store.list(PKG)
        assertEquals(2, listed.size)
        assertEquals(listOf(HEX_KEY, "fedcba9876543210"), listed.map { it.sha256Hex })
        assertNotNull(listed.first().file)
    }

    @Test
    fun `prune 只删未引用版本并清空目录且幂等`() {
        val keepOne = store.install(PKG, "keep-me".toByteArray())
        val dropOne = store.install(PKG, "drop-me".toByteArray())
        store.install(OTHER_PKG, "other-only".toByteArray())

        assertEquals("未引用版本 = dropOne + other 包唯一样本，共 2 个", 2, store.prune(setOf(keepOne.sha256Hex)))
        assertTrue("保留集里的版本不许删", keepOne.file.isFile)
        assertFalse("未引用版本必须删", dropOne.file.exists())
        assertFalse("other 包被清空后目录必须清掉", File(store.codeDir(), OTHER_PKG).exists())
        assertEquals(1, store.list(PKG).size)

        assertEquals("prune 必须幂等（再跑返回 0）", 0, store.prune(setOf(keepOne.sha256Hex)))
    }

    @Test
    fun `clear 删净包树且幂等`() {
        store.install(PKG, "alpha".toByteArray())
        store.install(OTHER_PKG, "beta".toByteArray())

        assertTrue(store.clear(PKG))
        assertFalse(File(store.codeDir(), PKG).exists())
        assertTrue(store.list(PKG).isEmpty())
        assertTrue("clear 再跑必须仍返回 true", store.clear(PKG))
        assertTrue("不碰别的包", File(store.codeDir(), OTHER_PKG).isDirectory)

        assertTrue(store.clear(OTHER_PKG))
        assertFalse(File(store.codeDir(), OTHER_PKG).exists())
    }
}
