package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [ModZipGuard] 的口径闸：不可信 zip 的条目数 / 单条目体积 / 整包体积 / 压缩比 / 条目重名。
 *
 * 断言分两层：
 * 1. `ModZip.unpack` / `ModZip.entryNames` 层 —— 闸在**读流时**抛 [ModZipGuard.Rejected]，
 *    且爆炸样本的耗时与读数都远小于其标称解压量（证明「早退」而不是「读完再判」）。
 * 2. `ModImportController.import` 层 —— 闸拒折成 STORAGE 失败面、文案点名拒因、盘上零半套。
 *
 * 夹具与 [ModImportAssetsTest]/[ModImportControllerTest] 同款（那些是 private，这里复制一份）。
 * 造包用内存 zip；压缩炸弹用全零流（压缩比约 1000 倍），与随机字节（压缩比约 1）对照。
 */
class ModZipGuardTest {

    @Test
    fun `普通包按筛选把命中条目原样解出`() {
        val zip = ModZipFixtures.zipOf(
            "a/patch.ERB" to "RETURN 0\n".toByteArray(Charsets.UTF_8),
            "b/readme.txt" to "skip me".toByteArray(Charsets.UTF_8),
            "c/header.ERH" to "#DIM X = 1\n".toByteArray(Charsets.UTF_8),
        )

        val entries = ModZip.unpack(zip)

        assertEquals("只解 .ERB/.ERH 条目", listOf("a/patch.ERB", "c/header.ERH"), entries.map { it.name })
        assertArrayEquals("命中条目字节原样", "RETURN 0\n".toByteArray(Charsets.UTF_8), entries[0].bytes)
        assertArrayEquals("#DIM X = 1\n".toByteArray(Charsets.UTF_8), entries[1].bytes)
    }

    @Test
    fun `条目数正好到上限放行`() {
        val entries = (0 until ModZipGuard.MAX_ENTRIES).map { "f%03d.txt".format(it) to byteArrayOf(1) }
        val zip = ModZipFixtures.zipOf(*entries.toTypedArray())

        val unpacked = ModZip.unpack(zip)

        assertTrue("全是 .txt，解出 0 条但不抛", unpacked.isEmpty())
        assertEquals(ModZipGuard.MAX_ENTRIES, ModZip.entryNames(zip).size)
    }

    @Test
    fun `条目数超限即拒且文案点名上限`() {
        val entries = (0 until ModZipGuard.MAX_ENTRIES + 1).map { "g%03d.txt".format(it) to byteArrayOf(1) }
        val zip = ModZipFixtures.zipOf(*entries.toTypedArray())

        val rejected = assertThrows(ModZipGuard.Rejected::class.java) { ModZip.unpack(zip) }

        assertEquals(ModZipGuard.Reason.ENTRY_COUNT, rejected.reason)
        val message = ModZipGuard.rejectMessage(ModImportCopy.ZH, "huge.zip", rejected)
        assertTrue("要点名 zip：$message", message.contains("huge.zip"))
        assertTrue("要点名上限条目数：$message", message.contains("512"))
        assertTrue("要点名条目数：$message", message.contains("条目数超过上限"))
    }

    @Test
    fun `单条目正好到上限放行`() {
        val atLimit = ModZipFixtures.randomBytes(ModZipGuard.MAX_ENTRY_BYTES.toInt(), seed = 4L)
        val zip = ModZipFixtures.zipOf("big.ERB" to atLimit)

        val entries = ModZip.unpack(zip)

        assertEquals(1, entries.size)
        assertEquals("正好 8 MiB 是放行线（严格大于才拒）", ModZipGuard.MAX_ENTRY_BYTES.toInt(), entries[0].bytes.size)
    }

    @Test
    fun `单条目超限即拒`() {
        val overLimit = ModZipFixtures.randomBytes(ModZipGuard.MAX_ENTRY_BYTES.toInt() + 1024, seed = 5L)
        val zip = ModZipFixtures.zipOf("big.ERB" to overLimit)

        val rejected = assertThrows(ModZipGuard.Rejected::class.java) { ModZip.unpack(zip) }

        assertEquals(ModZipGuard.Reason.ENTRY_SIZE, rejected.reason)
        assertTrue("读数越过 8 MiB：${rejected.observed}", rejected.observed > ModZipGuard.MAX_ENTRY_BYTES)
        val message = ModZipGuard.rejectMessage(ModImportCopy.ZH, "big.zip", rejected)
        assertTrue("要点名条目：$message", message.contains("big.ERB"))
        assertTrue("要点名上限 MiB：$message", message.contains("8"))
    }

    @Test
    fun `整包总量超限即拒`() {
        val chunk = ModZipFixtures.randomBytes((6.8 * 1024 * 1024).toInt(), seed = 6L)
        val entries = (0 until 5).map { "part$it.ERB" to chunk }.toTypedArray()

        val rejected = assertThrows(ModZipGuard.Rejected::class.java) { ModZip.unpack(ModZipFixtures.zipOf(*entries)) }

        assertEquals("34 MiB > 32 MiB，单条 6.8 MiB 未超", ModZipGuard.Reason.TOTAL_SIZE, rejected.reason)
        assertTrue("读数越过 32 MiB：${rejected.observed}", rejected.observed > ModZipGuard.MAX_TOTAL_BYTES)
    }

    @Test
    fun `零字节压缩炸弹在闸处早退`() {
        // 压缩比闸：4 MiB 全零 ⇒ zip 仅几 KB，读数应在 1 MiB 档就停（而不是读完 4 MiB）。
        val ratioBomb = ModZipFixtures.zeroBombZip("bomb.ERB", 4 * 1024 * 1024)
        val ratioRejected = assertThrows(ModZipGuard.Rejected::class.java) { ModZip.unpack(ratioBomb) }
        assertEquals(ModZipGuard.Reason.COMPRESSION_RATIO, ratioRejected.reason)
        assertTrue("读数应停在 1 MiB 档：${ratioRejected.observed}", ratioRejected.observed < 2L * 1024 * 1024)

        // 大炸弹：256 MiB 全零 —— 无论先撞单条目闸还是压缩比闸，都必须在读完之前停。
        val bigBomb = ModZipFixtures.zeroBombZip("bomb.ERB", 256 * 1024 * 1024)
        val startedAt = System.nanoTime()
        val bigRejected = assertThrows(ModZipGuard.Rejected::class.java) { ModZip.unpack(bigBomb) }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val volumeGates = setOf(ModZipGuard.Reason.ENTRY_SIZE, ModZipGuard.Reason.COMPRESSION_RATIO)
        assertTrue("只允许体积类闸拒：${bigRejected.reason}", bigRejected.reason in volumeGates)
        assertTrue("必须在 2s 内早退，实际 ${elapsedMs}ms", elapsedMs < 2000)
        assertTrue("读数 ${bigRejected.observed} 应远小于 256 MiB", bigRejected.observed < 16L * 1024 * 1024)
    }

    @Test
    fun `重名条目即拒——被过滤条目同样过闸`() {
        // ZipOutputStream 拒写同名条目：先写两个等长不同名条目，再把第二处局部头名字覆写。
        // ZipInputStream 只读局部头（无视中央目录），改名等长 ⇒ 偏移不变，够构造重名样本。
        val zip = ModZipFixtures.zipOf(
            "keep/aaa1.txt" to byteArrayOf(11),
            "keep/bbb2.txt" to byteArrayOf(22),
        )
        val duplicate = ModZipFixtures.corruptEntryName(zip, from = "bbb2.txt", to = "aaa1.txt")

        val rejected = assertThrows(ModZipGuard.Rejected::class.java) { ModZip.unpack(duplicate) }

        assertEquals("两个 .txt 都不命中 keep，但仍要在读条目阶段拒", ModZipGuard.Reason.DUPLICATE_NAME, rejected.reason)
        assertEquals("keep/aaa1.txt", rejected.entryName)
    }

    @Test
    fun `entryNames 只读名也要过体积闸——炸弹条目不得静默解压丢弃`() {
        // 256 MiB 全零单条目：条目数 / 重名闸都不越限；压缩比闸线 ≈ 120 × ~260 KB ≈ 30 MiB，
        // 单条目 8 MiB 闸先到 ⇒ 拒因应为 ENTRY_SIZE（与 unpack 的大炸弹样本同判）。
        // ZipInputStream.getNextEntry 的 closeEntry 会把当前条目剩余数据完整解压丢弃：
        // entryNames 若只读名不计数，炸弹在这里被静默全量 inflate、五道体积闸全部失效。
        val bomb = ModZipFixtures.zeroBombZip("bomb.ERB", 256 * 1024 * 1024)
        val startedAt = System.nanoTime()
        val rejected = assertThrows(ModZipGuard.Rejected::class.java) { ModZip.entryNames(bomb) }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals("只读名的路径同样要过单条目体积闸", ModZipGuard.Reason.ENTRY_SIZE, rejected.reason)
        assertTrue("必须在 2s 内早退，实际 ${elapsedMs}ms", elapsedMs < 2000)
        assertTrue("读数 ${rejected.observed} 应远小于 256 MiB", rejected.observed < 16L * 1024 * 1024)
    }

    @Test
    fun `fixedEntryBytes 只取固定名也要过体积闸——目标条目之前的条目不得静默解压丢弃`() {
        // 炸弹条目排在目标条目之前：推进到目标条目前必须先把炸弹条目读尽，
        // closeEntry 丢弃语义不计数的话，五道体积闸在这条路径全部失效——而这条路径的调用面
        // 既有 ⓪ 已过闸的外层包 zip（幂等复核），也有没有 ⓪ 可借的产物内层 zip（唯一体积闸）。
        val zip = ModZipFixtures.zeroBombPlusTargetZip(
            bombName = "bomb.bin",
            targetName = "target.txt",
            decompressedBytes = 256 * 1024 * 1024,
        )
        val startedAt = System.nanoTime()
        val rejected = assertThrows(ModZipGuard.Rejected::class.java) {
            ModZip.fixedEntryBytes(zip, "target.txt", maxBytes = 1024)
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals("固定名取字节的路径同样要过单条目体积闸", ModZipGuard.Reason.ENTRY_SIZE, rejected.reason)
        assertTrue("必须在 2s 内早退，实际 ${elapsedMs}ms", elapsedMs < 2000)
        assertTrue("读数 ${rejected.observed} 应远小于 256 MiB", rejected.observed < 16L * 1024 * 1024)
    }
}

/**
 * 闸拒 + 真实导入路径：拒发生在任何落盘之前，盘上零半套；截断/损坏仍归既有「解包失败」面。
 */
class ModZipGuardImportTest {

    @Test
    fun `截断包仍归解包失败面而非闸门`() {
        val tmp = ModZipFixtures.tempDir()
        val controller = ModZipFixtures.controllerIn(tmp)
        val zip = ModZipFixtures.zipOf(
            "src.ERB" to ModZipFixtures.randomBytes(64 * 1024, seed = 9L),
        )
        val truncated = zip.copyOf(zip.size * 6 / 10)

        val outcome = controller.import(listOf(PickedModFile("broken_trunc.zip", truncated)))

        val failed = ModZipFixtures.failedOf(outcome)
        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
        assertTrue("要点名 zip：${failed.message}", failed.message.contains("broken_trunc.zip"))
        assertTrue("要归解包失败面：${failed.message}", failed.message.contains("解包失败"))
    }

    @Test
    fun `闸拒导入后盘上零半套且文案点名拒因`() {
        val tmp = ModZipFixtures.tempDir()
        val controller = ModZipFixtures.controllerIn(tmp)
        val entries = (0 until ModZipGuard.MAX_ENTRIES + 1)
            .map { "h%03d.ERB".format(it) to "RETURN 0\n".toByteArray() }
        val zip = ModZipFixtures.zipOf(*entries.toTypedArray())

        val outcome = controller.import(listOf(PickedModFile("huge.zip", zip)))

        val failed = ModZipFixtures.failedOf(outcome)
        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
        assertTrue("要点名 zip 与拒因：${failed.message}", failed.message.contains("huge.zip"))
        assertTrue("要点名拒因：${failed.message}", failed.message.contains("条目数超过上限"))
        val leftovers = File(tmp, "mods").walkTopDown().filter { it.isFile }.toList()
        assertTrue("拒导后不得有半套产物：$leftovers", leftovers.isEmpty())
        val rt2Residue = File(File(tmp, "mods"), "rt2").list()?.filter { it.contains(".tmp-") } ?: emptyList()
        assertTrue("临时目录不得残留：$rt2Residue", rt2Residue.isEmpty())
    }

    @Test
    fun `entryNames 只读名也过条目数闸`() {
        val entries = (0 until ModZipGuard.MAX_ENTRIES + 1).map { "n%03d.txt".format(it) to byteArrayOf(1) }
        val zip = ModZipFixtures.zipOf(*entries.toTypedArray())

        val rejected = assertThrows(ModZipGuard.Rejected::class.java) { ModZip.entryNames(zip) }

        assertEquals(ModZipGuard.Reason.ENTRY_COUNT, rejected.reason)
    }
}

/** 测试夹具：内存 zip / 合成 base 上下文 / 临时目录（三语形状闸不涉及）。 */
private object ModZipFixtures {

    /** 合成 base sha（64 位小写十六进制）。 */
    private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
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

    /** 单条目「压缩炸弹」：写入 [decompressedBytes] 个零字节（deflate 压缩比约 1000 倍）。 */
    fun zeroBombZip(entryName: String, decompressedBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(entryName))
            val zeroChunk = ByteArray(64 * 1024)
            var left = decompressedBytes
            while (left > 0) {
                val count = minOf(left, zeroChunk.size)
                z.write(zeroChunk, 0, count)
                left -= count
            }
            z.closeEntry()
        }
        return out.toByteArray()
    }

    /** 「压缩炸弹条目在前 + 小目标条目在后」的双条目 zip（fixedEntryBytes 的排水面样本）。 */
    fun zeroBombPlusTargetZip(bombName: String, targetName: String, decompressedBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(bombName))
            val zeroChunk = ByteArray(64 * 1024)
            var left = decompressedBytes
            while (left > 0) {
                val count = minOf(left, zeroChunk.size)
                z.write(zeroChunk, 0, count)
                left -= count
            }
            z.closeEntry()
            z.putNextEntry(ZipEntry(targetName))
            z.write("ok".toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return out.toByteArray()
    }

    /** 不可压缩的合成字节（均匀随机；压缩比约 1，不会误触压缩比闸）。 */
    fun randomBytes(count: Int, seed: Long): ByteArray =
        ByteArray(count).also { java.util.Random(seed).nextBytes(it) }

    fun tempDir(): File = Files.createTempDirectory("eratw-zipguard").toFile()

    fun failedOf(outcome: ModImportOutcome): ModImportOutcome.Failed =
        outcome as? ModImportOutcome.Failed ?: throw AssertionError("导入应失败，实为 $outcome")

    /**
     * 把 zip 里**第一次出现**的 [from]（ASCII、与 [to] 等长）覆写成 [to]。
     *
     * 用途：构造条目重名样本。`ZipOutputStream` 自己就拒写同名条目，只能写合法包再改局部头。
     */
    fun corruptEntryName(zipBytes: ByteArray, from: String, to: String): ByteArray {
        val target = from.toByteArray(Charsets.US_ASCII)
        val replacement = to.toByteArray(Charsets.US_ASCII)
        require(target.size == replacement.size) { "等长替换才能不改字节偏移：$from -> $to" }
        val result = zipBytes.copyOf()
        for (offset in 0..result.size - target.size) {
            if (target.indices.all { result[offset + it] == target[it] }) {
                replacement.copyInto(result, offset)
                return result
            }
        }
        throw AssertionError("zip 里找不到待覆写的条目名：$from")
    }

    fun controllerIn(tmp: File): ModImportController = ModImportController(
        modStore = ModStore(File(tmp, "mods")),
        baseSource = { contextOf() },
        gameRoot = tmp,
    )

    private fun contextOf(): BaseCompileContext =
        BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null)

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

    private fun miniBaseCounts(): PatchCompiler.BaseCounts =
        PatchCompiler.BaseCounts(
            instructionCount = 4000,
            functionCount = 4,
            statementCount = 100,
            stringCount = 10,
            longCount = 5,
            exprCount = 20,
        )
}
