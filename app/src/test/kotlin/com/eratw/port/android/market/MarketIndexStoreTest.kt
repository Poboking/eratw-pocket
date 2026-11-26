package com.eratw.port.android.market

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarketIndexStore] 的盘上镜像读写定向单测（契约 §2.6）：
 * 读不存在 = Absent；读坏文件 = Invalid 且盘上原样保留；写新 = 覆盖；
 * 校验拒 / IO 失败的写 = 盘上旧镜像一个字节不动、不留 .tmp 残壳。
 * 语义锚 = service.ts:358-379（读三态）与 service.ts:508-518（校验通过才覆盖写原文）。
 */
class MarketIndexStoreTest {

    private val verifier = MarketVerifierImpl()
    private val now = 1_729_000_000_000L

    /** 合法最小镜像（packs 空数组也合法）。 */
    private val minIndex = "{\"schema\": 1, \"generatedAt\": \"2026-10-19T08:40:41.000Z\", \"packs\": []}"

    /** 与 minIndex 同 schema 但内容不同（覆盖写后应读到新内容）。 */
    private val minIndexNew =
        "{\"schema\": 1, \"generatedAt\": \"2026-10-20T00:00:00.000Z\", \"packs\": []}"

    private fun tempDir(): File = Files.createTempDirectory("eratw-mkm-store").toFile()

    private fun storeIn(dir: File): MarketIndexStore = MarketIndexStore(File(dir, "index.json"))

    @Test
    fun `read_缺席返回Absent`() {
        val dir = tempDir()
        assertEquals(MarketIndexState.Absent, storeIn(dir).read(verifier, now))
        // 目录都没建过也不算错误（Absent 不是失败态）
        assertNull((storeIn(dir).read(verifier, now) as? MarketIndexState.Invalid)?.problem)
    }

    @Test
    fun `read_合法镜像返回Ok并盖章`() {
        val dir = tempDir()
        val store = storeIn(dir)
        File(dir, "index.json").writeText(minIndex)
        val state = store.read(verifier, now)
        assertTrue(state is MarketIndexState.Ok)
        assertEquals(0, (state as MarketIndexState.Ok).index.packs.size)
        assertEquals(now, state.index.fetchedAtMs)
    }

    @Test
    fun `read_坏JSON返回Invalid且盘上原样保留`() {
        val dir = tempDir()
        val file = File(dir, "index.json")
        file.writeText("{\"schema\": 1, ")
        val before = file.readBytes()
        val state = storeIn(dir).read(verifier, now)
        assertTrue(state is MarketIndexState.Invalid)
        assertTrue((state as MarketIndexState.Invalid).problem.isNotEmpty())
        assertTrue("坏文件须原样保留", file.readBytes().contentEquals(before))
    }

    @Test
    fun `read_空文件与字段不过都Invalid且原样保留`() {
        val dir = tempDir()
        val file = File(dir, "index.json")
        file.writeText("")
        assertTrue(storeIn(dir).read(verifier, now) is MarketIndexState.Invalid)
        assertEquals("", file.readText())
        file.writeText("{\"schema\": 2, \"generatedAt\": \"t\", \"packs\": []}")
        val before = file.readBytes()
        val state = storeIn(dir).read(verifier, now)
        assertTrue(state is MarketIndexState.Invalid)
        assertTrue((state as MarketIndexState.Invalid).problem.contains("schema"))
        assertTrue(file.readBytes().contentEquals(before))
    }

    @Test
    fun `write_新写成功且读回一致`() {
        val dir = tempDir()
        val store = storeIn(dir)
        val outcome = store.write(verifier, minIndex)
        assertTrue(outcome is MarketIndexStore.WriteOutcome.Ok)
        val state = store.read(verifier, now)
        assertTrue(state is MarketIndexState.Ok)
        assertEquals(minIndex, File(dir, "index.json").readText())
        assertFalse(File(dir, "index.json.tmp").exists())
    }

    @Test
    fun `write_校验拒不落盘不动旧文件不留残壳`() {
        val dir = tempDir()
        val store = storeIn(dir)
        val file = File(dir, "index.json")
        file.writeText(minIndex)
        val before = file.readBytes()
        val bad = "{\"schema\": 2, \"generatedAt\": \"t\", \"packs\": []}"
        val outcome = store.write(verifier, bad)
        assertTrue(outcome is MarketIndexStore.WriteOutcome.Rejected)
        assertTrue((outcome as MarketIndexStore.WriteOutcome.Rejected).problem.contains("schema"))
        assertTrue("旧镜像原样", file.readBytes().contentEquals(before))
        assertFalse("不留临时残壳", File(dir, "index.json.tmp").exists())
    }

    @Test
    fun `write_覆盖旧镜像`() {
        val dir = tempDir()
        val store = storeIn(dir)
        File(dir, "index.json").writeText(minIndex)
        assertTrue(store.write(verifier, minIndexNew) is MarketIndexStore.WriteOutcome.Ok)
        val state = store.read(verifier, now)
        assertTrue(state is MarketIndexState.Ok)
        assertEquals("2026-10-20T00:00:00.000Z", (state as MarketIndexState.Ok).index.generatedAt)
    }

    @Test
    fun `write_IO失败不动旧文件且清残壳`() {
        // 落点被一个非空目录占住：temp 写成功、rename 覆盖必失败 ⇒ Failed 且旧位原样
        val dir = tempDir()
        val target = File(dir, "index.json")
        assertTrue(target.mkdir())
        File(target, "junk").writeText("x") // 空目录会被 rename 替换掉，非空目录才会真的失败
        val store = MarketIndexStore(target)
        val outcome = store.write(verifier, minIndex)
        assertTrue(outcome is MarketIndexStore.WriteOutcome.Failed)
        assertTrue("旧位（目录）原样", target.isDirectory)
        assertTrue(File(target, "junk").exists())
        assertFalse("不留临时残壳", File(dir, "index.json.tmp").exists())
    }

    @Test
    fun `write_父目录建不出来判Failed`() {
        // 父路径被普通文件占住：mkdirs 必失败 ⇒ Failed，且不触碰任何既有文件
        val dir = tempDir()
        val blocker = File(dir, "blocker")
        blocker.writeText("x")
        val store = MarketIndexStore(File(blocker, "index.json"))
        val outcome = store.write(verifier, minIndex)
        assertTrue(outcome is MarketIndexStore.WriteOutcome.Failed)
        assertEquals("x", blocker.readText())
    }
}
