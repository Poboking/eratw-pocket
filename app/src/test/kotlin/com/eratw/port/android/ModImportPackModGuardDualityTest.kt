package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `pack-mod` 产出 ↔ [ModZipGuard] 接受：对偶契约的 app 侧半段。
 *
 * ## 为什么要两段接力（而不是同进程 A/B）
 *
 * `pack-mod` 的实现是 `tools:modgen` 的 `internal object PackMod`/`PackModZip`，`:app` 的测试源集
 * 看不见；`ModZipGuard` 又不在 modgen 的测试类路径上。⇒ 契约拆两段，靠**同一份 zip 字节**焊住：
 *
 * - modgen 侧 `tools/modgen/src/test/kotlin/com/eratw/port/modgen/PackModGuardDualityTest.kt`
 *   真跑 `PackMod.runCli` 出包，把条目数/单条目/总量/压缩比/重名对照**从 [ModZipGuard] 源码现场解析**
 *   出的 `MAX_*` 常量，并断言产出的 sha256 == [REAL_PACK_FIXTURE_SHA256]；
 * - 本类（app 侧）内嵌同一份字节（[REAL_PACK_FIXTURE_B64]），真走 [ModZip.unpack] 与真
 *   [ModImportController.import] 导入成功。
 *
 * 两侧都绿 = 「pack-mod 产出的正常包必被 guard 接受并完整导入」成立；任一侧判据漂移（pack 产物
 * 形态变了 / guard 收紧到误杀正常包）都会让对偶用例红。
 *
 * ## 边界与拒收
 *
 * - 边界（任务书口径：500 条目 / 单条目 7 MiB / 总量 30 MiB / 压缩比近 110）不得误杀；
 * - 超限（条目数 / 单条目 / 总量 / 压缩比 / 重名）必拒，拒因经 [ModZipGuard.rejectMessage] 在
 *   ZH/EN/JA 三语文案里点名；
 * - 至少一条走真导入路径，证明闸拒折成 STORAGE 失败面且盘上零半套。
 */
class ModImportPackModGuardDualityTest {

    // ------------------------------------------------------------------
    // 1. 真 pack-mod 产出字节：guard 接受 + 真导入成功（两段接力焊点）
    // ------------------------------------------------------------------

    @Test
    fun `真pack产出字节——sha256与pack侧一致且条目形态完整`() {
        val bytes = realPackFixture()

        assertEquals(
            "夹具字节漂移：必须与 modgen 侧 PackModGuardDualityTest.PACKED_FIXTURE_SHA256 相同",
            REAL_PACK_FIXTURE_SHA256,
            sha256Hex(bytes),
        )
        // modgen 侧同一份字节是 3 条目：main.ERB + 骨架自带 .assets/README.txt + pack-mod 元数据。
        val names = ModZip.entryNames(bytes)
        assertEquals("pack-mod 合同：3 条目（源/资源/元数据）", 3, names.size)
        assertTrue("源条目应为模块根下的 main.ERB：$names", REAL_PACK_SOURCE_ENTRY in names)
        assertTrue("资源条目应带模块 PACKAGE_ID 前缀：$names", "$PACKAGE_ID.assets/README.txt" in names)
        assertTrue("pack-mod 应写元数据条目：$names", names.any { it == "eratw-pack.json" })
        val entries = ModZip.unpack(bytes)
        assertEquals("只有 1 条 .ERB 命中导入筛选", 1, entries.size)
        assertEquals(REAL_PACK_SOURCE_ENTRY, entries[0].name)
        assertArrayEquals(
            "源字节原样解出",
            PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            entries[0].bytes,
        )
    }

    @Test
    fun `真pack产出字节——真导入成功且资源重挂到目标包`() {
        val tmp = Files.createTempDirectory("eratw-dualcontract").toFile()
        val controller = controllerIn(tmp)

        val detail = imported(controller.import(listOf(PickedModFile("eratw-mymod-pack.ZIP", realPackFixture()))))

        val packageId = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        assertTrue("目标 packageId 由导入端派生：$packageId", packageId.startsWith("kojo-"))
        assertEquals("骨架自带 1 条资源应全部导入", 1, detail.resourceEntryCount)
        val assets = File(File(File(tmp, "mods"), "rt2"), "$packageId.assets")
        assertTrue("资源目录应落在目标包名下：$assets", assets.isDirectory)
        assertTrue("骨架 README 应重挂到目标包：$assets", File(assets, "README.txt").isFile)
        val residue = File(File(tmp, "mods"), "rt2").list()?.filter { it.contains(".tmp-") || it.contains(".bak-") }
        assertTrue("临时/备份目录不得残留：$residue", residue.isNullOrEmpty())
    }

    // ------------------------------------------------------------------
    // 2. 边界包：不误杀（口径同任务书 ①500 条目 / ②单条目 7MiB / ③总量 30MiB / ④压缩比近 110）
    // ------------------------------------------------------------------

    @Test
    fun `边界包500条目——不被条目数闸误杀且全部读出`() {
        val entries = ArrayList<String>()
        entries += REAL_PACK_SOURCE_ENTRY
        repeat(BOUNDARY_ENTRY_COUNT - 1) { index -> entries += "src/f%03d.ERB".format(index) }
        val zip = packContractZip(entries.map { it to "RETURN 0\n".toByteArray(Charsets.UTF_8) })

        val unpacked = ModZip.unpack(zip)

        assertEquals("500 条目 < 上限 ${ModZipGuard.MAX_ENTRIES}，必须全放行", BOUNDARY_ENTRY_COUNT, unpacked.size)
        assertEquals("条目名不得被改写", BOUNDARY_ENTRY_COUNT, ModZip.entryNames(zip).size)
    }

    @Test
    fun `边界包单条目7MiB——不被单条目闸误杀`() {
        // 7 MiB < 8 MiB 上限；用高熵字节，避免顺带触发压缩比闸（那是另一道判据）。
        val payload = randomBytes(7 * 1024 * 1024, seed = 41L)
        val zip = packContractZip(listOf("big.ERB" to payload))

        val entries = ModZip.unpack(zip)

        assertEquals(1, entries.size)
        assertEquals("7 MiB 条目应原样解出", payload.size, entries[0].bytes.size)
        assertArrayEquals("字节原样", payload, entries[0].bytes)
    }

    @Test
    fun `边界包总量30MiB——不被总量闸误杀`() {
        // 30 MiB < 32 MiB 上限；随机字节 ⇒ 压缩比约 1，专门把「总量闸」与「压缩比闸」分开验。
        val chunk = randomBytes(6 * 1024 * 1024, seed = 42L)
        val zip = packContractZip((0 until 5).map { "part$it.ERB" to chunk })

        val entries = ModZip.unpack(zip)

        assertEquals(5, entries.size)
        assertEquals("总解压 30 MiB 应全部放行", 30L * 1024 * 1024, entries.sumOf { it.bytes.size.toLong() })
    }

    @Test
    fun `边界包压缩比接近上限110——不被压缩比闸误杀`() {
        // 高压缩比但未越线：1.5 MiB 里每 256 字节只有一个随机字节，其余全零 ⇒ ratio 量级在 100 上下。
        // 压缩比闸门槛是总解压 > 1 MiB，本样本恰好越过门槛，所以这条用例真的压在闸边缘上。
        val payload = highRatioPayload(sizeBytes = 1536 * 1024, noisePeriod = 256, seed = 43L)
        val zip = packContractZip(listOf("highratio.ERB" to payload))
        val ratio = payload.size.toLong() / zip.size.toLong()

        assertTrue(
            "压缩比样本应贴近闸上限（实测 $ratio，上限 ${ModZipGuard.MAX_COMPRESSION_RATIO}）",
            ratio > 90L,
        )
        assertTrue("构造样本必须仍在 guard 接受范围内：ratio=$ratio", ratio < ModZipGuard.MAX_COMPRESSION_RATIO)
        val entries = ModZip.unpack(zip)
        assertEquals("高压缩比但未越线的包不得误杀", 1, entries.size)
        assertEquals("解压后字节原样", payload.size, entries[0].bytes.size)
    }

    // ------------------------------------------------------------------
    // 3. 超限包：必拒 + 拒因点名（ZH/EN/JA 三语文案，来自既有键）
    // ------------------------------------------------------------------

    @Test
    fun `超限包条目数——拒因点名且三语文案同源`() {
        val entries = (0 until ModZipGuard.MAX_ENTRIES + 1).map { "g%03d.txt".format(it) to byteArrayOf(1) }
        val rejected = assertRejected(packContractZip(entries), ModZipGuard.Reason.ENTRY_COUNT)
        assertTrue("读数越过上限：${rejected.observed}", rejected.observed > ModZipGuard.MAX_ENTRIES)
        assertTriLingual(rejected, "条目数超过上限", "too many entries", "エントリ数が上限を超えています")
    }

    @Test
    fun `超限包单条目——拒因点名且三语文案同源`() {
        val overLimit = randomBytes(ModZipGuard.MAX_ENTRY_BYTES.toInt() + 1024, seed = 44L)
        val rejected = assertRejected(packContractZip(listOf("big.ERB" to overLimit)), ModZipGuard.Reason.ENTRY_SIZE)
        assertEquals("拒因要点名越界条目：${rejected.entryName}", "big.ERB", rejected.entryName)
        assertTriLingual(rejected, "解压后超过上限", "over the size limit after unpacking", "展開後に上限を超えています")
    }

    @Test
    fun `超限包总量——拒因点名且三语文案同源`() {
        val chunk = randomBytes((6.5 * 1024 * 1024).toInt(), seed = 45L)
        val zip = packContractZip((0 until 5).map { "part$it.ERB" to chunk })
        val rejected = assertRejected(zip, ModZipGuard.Reason.TOTAL_SIZE)
        assertTrue("读数越过总量上限：${rejected.observed}", rejected.observed > ModZipGuard.MAX_TOTAL_BYTES)
        assertTriLingual(rejected, "整包解压后超过上限", "over the total size limit", "全体の展開後サイズが上限を超えています")
    }

    @Test
    fun `超限包压缩比——拒因点名且三语文案同源`() {
        val rejected = assertRejected(zeroBombZip("bomb.ERB", 4 * 1024 * 1024), ModZipGuard.Reason.COMPRESSION_RATIO)
        assertTriLingual(rejected, "压缩比超过上限", "over the compression ratio limit", "圧縮率が上限を超えています")
    }

    @Test
    fun `超限包重名——拒因点名且三语文案同源`() {
        val duplicate = corruptEntryName(
            packContractZip(listOf("keep/aaa1.txt" to byteArrayOf(11), "keep/bbb2.txt" to byteArrayOf(22))),
            from = "bbb2.txt",
            to = "aaa1.txt",
        )
        val rejected = assertRejected(duplicate, ModZipGuard.Reason.DUPLICATE_NAME)
        assertEquals("keep/aaa1.txt", rejected.entryName)
        assertTriLingual(rejected, "重名条目", "duplicate entries", "同名のエントリがあります")
    }

    // ------------------------------------------------------------------
    // 4. 闸拒走真导入路径：折成 STORAGE 失败面，盘上零半套
    // ------------------------------------------------------------------

    @Test
    fun `闸拒真导入——STORAGE失败面点名拒因且盘上零半套`() {
        val tmp = Files.createTempDirectory("eratw-dualcontract").toFile()
        val controller = controllerIn(tmp)
        val entries = (0 until ModZipGuard.MAX_ENTRIES + 1).map { "h%03d.ERB".format(it) to "RETURN 0\n".toByteArray() }

        val failed = failedOf(controller.import(listOf(PickedModFile("huge-pack.ZIP", packContractZip(entries)))))

        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
        assertTrue("要点名 zip：${failed.message}", failed.message.contains("huge-pack.ZIP"))
        assertTrue("要点名拒因：${failed.message}", failed.message.contains("条目数超过上限"))
        val leftovers = File(tmp, "mods").walkTopDown().filter { it.isFile }.toList()
        assertTrue("拒导后不得有半套产物：$leftovers", leftovers.isEmpty())
    }

    // ------------------------------------------------------------------
    // 夹具与断言助手
    // ------------------------------------------------------------------

    private fun realPackFixture(): ByteArray = Base64.getDecoder().decode(REAL_PACK_FIXTURE_B64)

    /** pack-mod 同合同造包：条目名排序、1980-01-01 固定时间戳、不写目录条目、BEST_COMPRESSION。 */
    private fun packContractZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)
            for ((name, bytes) in entries.sortedBy { it.first }) {
                val entry = ZipEntry(name)
                entry.time = FIXED_ZIP_TIME_MILLIS
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zeroBombZip(entryName: String, decompressedBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            val chunk = ByteArray(64 * 1024)
            var left = decompressedBytes
            while (left > 0) {
                val count = minOf(left, chunk.size)
                zip.write(chunk, 0, count)
                left -= count
            }
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun randomBytes(count: Int, seed: Long): ByteArray =
        ByteArray(count).also { Random(seed).nextBytes(it) }

    /** 高压缩比但不是「全零炸弹」：每 [noisePeriod] 字节一个随机字节，其余为零。 */
    private fun highRatioPayload(sizeBytes: Int, noisePeriod: Int, seed: Long): ByteArray {
        val bytes = ByteArray(sizeBytes)
        val random = Random(seed)
        var offset = 0
        while (offset < sizeBytes) {
            bytes[offset] = random.nextInt(256).toByte()
            offset += noisePeriod
        }
        return bytes
    }

    /** 与 ModZipGuardTest 同款：把第一次出现的 [from] 等长覆写，造条目重名样本。 */
    private fun corruptEntryName(zipBytes: ByteArray, from: String, to: String): ByteArray {
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

    private fun assertRejected(zip: ByteArray, reason: ModZipGuard.Reason): ModZipGuard.Rejected {
        val rejected = assertThrows(ModZipGuard.Rejected::class.java) { ModZip.unpack(zip) }
        assertEquals("拒因必须是 $reason，实为 ${rejected.reason}", reason, rejected.reason)
        return rejected
    }

    /** 拒因必须在三语文案里各自点名（文案键与 [ModZipGuard.rejectMessage] 的映射同源）。 */
    private fun assertTriLingual(rejected: ModZipGuard.Rejected, zhToken: String, enToken: String, jaToken: String) {
        val zh = ModZipGuard.rejectMessage(ModImportCopy.ZH, "pkg.ZIP", rejected)
        assertTrue("ZH 要点名 zip 与拒因：$zh", zh.contains("pkg.ZIP") && zh.contains(zhToken))
        val en = ModZipGuard.rejectMessage(ModImportCopy.EN, "pkg.ZIP", rejected)
        assertTrue("EN 要点名 zip 与拒因：$en", en.contains("pkg.ZIP") && en.contains(enToken))
        val ja = ModZipGuard.rejectMessage(ModImportCopy.JA, "pkg.ZIP", rejected)
        assertTrue("JA 要点名 zip 与拒因：$ja", ja.contains("pkg.ZIP") && ja.contains(jaToken))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun controllerIn(tmp: File): ModImportController =
        ModImportController(
            modStore = ModStore(File(tmp, "mods")),
            baseSource = { BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null) },
            gameRoot = tmp,
        )

    private fun imported(outcome: ModImportOutcome): ImportedPatchDetail =
        (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("导入应成功，实为 $outcome")

    private fun failedOf(outcome: ModImportOutcome): ModImportOutcome.Failed =
        outcome as? ModImportOutcome.Failed ?: throw AssertionError("导入应失败，实为 $outcome")

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

    private companion object {
        /** 合成 base sha（64 位小写十六进制）。 */
        const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        /** app 侧模块包源码里的 PACKAGE_ID（= pack-mod 给资源加的前缀）。 */
        const val PACKAGE_ID = "eratw.mymod"

        /** 真 pack-mod 产出里的唯一条目（new-mod 骨架 + 补丁源）。 */
        const val REAL_PACK_SOURCE_ENTRY = "main.ERB"

        /** 边界口径：任务书要求的 500 条目。 */
        const val BOUNDARY_ENTRY_COUNT = 500

        /** 1980-01-01T00:00:00Z，pack-mod 的固定条目时间戳。 */
        const val FIXED_ZIP_TIME_MILLIS = 315532800000L

        /** 可编译的最小补丁源（与其它导入用例同款）。 */
        const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"

        /** 真 pack-mod 产出的 sha256（与 modgen 侧 PACKED_FIXTURE_SHA256 逐字相同）。 */
        const val REAL_PACK_FIXTURE_SHA256 = "909c3b728db80f593b4963c2a5e739dfbc90fafb33c897730cdd1dabca710fe1"

        /** 真 pack-mod 产出的 zip 字节（两段接力的焊点；改 pack-mod 合同须两侧同步重生成）。 */
        const val REAL_PACK_FIXTURE_B64 =
                "UEsDBBQACAgIAAAAIQAAAAAAAAAAAAAAAAAPAAkAZXJhdHctcGFjay5qc29uVVQFAAGANc4Sq1YqTs5IzU1UsjLUUSpITM5OTE/1" +
                "TFGyUkotSiwp18utzM1PUdJRKkstKs7MzwOKG+gZKtUCAFBLBwhOTLDRNwAAADYAAABQSwMEFAAICAgAAAAhAAAAAAAAAAAAAAAA" +
                "AB0ACQBlcmF0dy5teW1vZC5hc3NldHMvUkVBRE1FLnR4dFVUBQABgDXOEq1ZXVNbR9K+16+Y4grqtSXsTVxbqSRVJJDECWAXZjdv" +
                "vTeuYzgYxUJHJQlvnK2tkrABSUgIm28EBmHzYWwkYWMsBBL/ZdHMOecqf2GfnjkSwmDXXrwpV5DOmenp6X66++mWHtTC/3APPRoy" +
                "+v88XrKX0jw5ar1/IkpTZibHy7Pmy1K1MuFyieU36gGWMCxmWPfncewPb+DP43i1GLHef+CxMZ5+CikBre/BVVojEgmeeyxm96vl" +
                "aTFzYJ1k5K6kOfmK5w54/piPboj5A2vrBY8VRLEoYmlz6YkLW0OeYPi652u1xHydF/sVc2YVL0m90uvqUflbtxYK6eGQh1WLE9BE" +
                "La2/Nmf2+Ngozx3y6RgvJtX+00iUl9f55gh7YPxmXL2mXb/3l74vcPGkq1pMibcn5sw2Liq2s3xlHocxuol2X7/Zz/4dmcE/xmOv" +
                "RXJcLGeUMfhUytyNV0uTTG+0Y/w0MmKdTFnZpFhehXF4ZcI8Srv6DH9Y/z3sDuohYzjYp4eYSMbNTJHnDyHIyh/hGh4cMWYvzUAI" +
                "1IJW1mZUTBybL6JmZsH6kOeVJ7iFel6trFjv5+qa0PrKBG4HF7hcjF04zm0EdH9zU09HW3tXhzv8e7iphTHm8TAczXfnrQR2Zszj" +
                "kpgbrx7BWzM885z5h32+S4Xpv3tD4dBH4iDsYwEDmi+kXyrhgf4o1EwqNP4HCXx02368XTcMOTc1zktp2ORSOSHvH/pl11J3ErOF" +
                "T13LZW094bFF2Iund6xckdWsD9+r45XFaf9mlMdTAJmHFyqIATG3LFbW4ArzVUnk3tOHkUN88LjdTH3n6VR9kZjc4rED6GO+2aSl" +
                "mQV84GPPePoDoOLiL7d4Ic3HFgkP0tN8chU3tyNT2MOTh/YoWaHN3x80vP0AfELMpsWTVYU0nppVVyNUHIxaJ+PsC9bl/Q54QOgh" +
                "Is6eX7tBL+hIkdk3j8ZwTyu/Wz2MW1vROmBZl9Hf4xj3TtgI6oxC4Zd2o482ukR8Wsqc5MezYnoS0WcvTkG7Ia+/LeDFoTydtyNx" +
                "cpZ9tGDlXtLxuYo9X4QZYWe+vI0wQ8BblcdirkDJ5KoW8DLrcZknMhLcM9hCQbucEfNb6hjrxShde/5AwAsvCmJhsh6Sy9uur7u1" +
                "If3bdr3PpyEOvYYf4ApLtSG98THjuec8PWKPJ3lijT8tw8jqCVPqs2/Y193f4jbd7FvWynAYcphrSPN7B/RQmBEOYwXW03u9q637" +
                "5g8dd3o9Nxg/PIDrSIC8B7yv8GYuRyHiG9Zw/2S1tGXGXylgwoS4nhmPUfoc35cRLwP3I52bb7d9/0vbjx13b7ZfYQ/1YAgPIfbv" +
                "HT13bt7qvnKm+Y0W9k9YZZP9y+Xi4yV4CfbiU0keOa4er7F6Wr56taaruiu0V6mZjyJZF0QqJy2nxNozOalZBh4ADIEP5RAXUIL3" +
                "bkcNVi1PUh7sZipJA3n2dEVMPLPKZRwh0lOoJKeRpL0coYyW2SfXa4HAaSSlUNw3qDvqUdjtr0IRmMleWbfH0yKx4ero6bnVQ2GV" +
                "P7RfL+AoXp5GRLKP1KDTJTywh/3a1tONw+31J+bsIoXXu1lCVuylPbOoEmwrQl5lcCUO3kABoGKQ2JBorxbjiBDEl8jGIOrhtVYC" +
                "toIxM/ydhtbPCE3yTJmxI+JN1jxatMtT7P9+8nR0e35uAwTiIvsCQQ1BZEoVagAz4l3WQgDELOdwkrUd4em52spzeTwUDnr990Pu" +
                "fokNZLyGmuO+H9T1MF43XWF/DAILTdXyGt8o46tOYGn6Sff5DHz7TaNvp9Hp05Hp0+jr02j2NJpvarnkGGPgEyfUsivUlDZztK5d" +
                "TizvOB4A+PBw7QPyrURRUto1c8lZg1ro04fhLPiEMLWQ51ObSjiymZXfgNhLpPk0//1hlO0LteVCoWnQ31yLEp8ZBCBgMQ8MRY4+" +
                "VyEaXF7zd6bRs/xk1M4eNSIAqcxeeY4SQVE29YR9/Q378tp1pgqBCitcQRUfVQHwSuRTHoV+z12P23OV6Ik9e8JLm7T/xhcfBwui" +
                "yhjwwIQMebVaGquWx+okRrzNMgcviDQEB45riAyRKMuAOAs1XtizVxaRuJW7eHTcLK2rCEVSyVTM+DiyhLm/Rrvf5/jeyLmM76xQ" +
                "+e1wVcxU6sSRTADm5R2CZ0Kee5rfrwfdAf99FYhEONeiFElpBz6UmnfnKQZkRvyb9ybtbG66IACo1nzhXiCAoK0Iq1JNadPU0uDy" +
                "9judzHw3B5ldJLPDpw/p/rBbya5RCqomJOviUS3n8COeP7Eja3xxm2wwRbHPN+bFWtbeSbrqhIMyzKuzz8CDmMmLZFSsHfPjNJ6A" +
                "/uLq/OWetb9BQMudVItHovhWrE7XLyZiczy1Vi2nRHYM9cbKFRwXkpOq2LT/ShIJuRcwYN95w0Na4AetDwX8kXMEPHC7+0ec+PPt" +
                "Dvrzq37vNmU/Xiyi/kM47KW0UtDkRyUrl7Mqh0DfGYcAgAO/02FnIIQC6tZ1z1FtqzleXV0Fb63Uxc7MQ/egvC7dTdZ5N8tksq+h" +
                "zl59Z68/lcn3mmQV8vvnMKYN93sNT3gQvnUb9/8rhNVyiNzqDvi0R81NH4kBznyGEQAwwsFhHaXY8A1LnLS6/zqg8hS9wAPlBv7h" +
                "LU/Pm1NjF8SHwkbg88npE/nqcATuoru8zYrlOPKReAb2tspzcXDmWlX6exczSyfm9oRVeAwokE1kxVYPya8KYYkECwT1AFKDJxTW" +
                "ggBZcodSwdQkTxeU8RtBXKeZwIEimMzj4itp6rEWt5lH0lL0cM924RE8sdZfA7JSgSXVAzU2BMSarKlyA4qFQ/5S5tqc6gWpUuYq" +
                "/OU4ZaTVd6gndNd0HkxRzK/Bh59AIVPuc7vdiFciBNLP/4+QVNUaCRO7AnpQno5elApWSGZBeOEzlLTGJuu8QZUMmMERnH1vje/w" +
                "xDZIHDoHaEROixyDLVAVTEarh6M8OYdTlHmqlS2qEdaHbb67gGpEOQWf9yLVkxUQn7NmFIQjnxYTKXOyoLpNhL2jKLJhTT/D3655" +
                "fY96vX0Pzjip1x++o27Y/AvaNvedjt7em90/3v3b7R972to77n7/U1v39x1XUGwGtGEfZeIvWzuvsCBKsQyRTrf7WmtrJxGNe4bh" +
                "+6ysju627zo72huFUWDRXt0/PHTp3p5bnZ13u261n1NBrqi/udvT1t1+q+sK69P8/d5+LYwG/BvmQxd7a6AZYGmhA8AhLpXf2/G/" +
                "vY2im5ouaVyJJi6/UU4k3l1eP8ffHIS4YcvPGbGxXNXJFFWRvSP+fIIC5M2mCQI9ufERmarJJwPLC2ETkaL6Z7IeeVs+cJ3B2Ekn" +
                "YB+jGzz9Ai53JjQSYQqRYuYQIJOUA3SF0JOhYUgiAWCax9NAHvUQYC/U/iSJp8UWsadmjEmwfns8hW4APZU5A3ofU93kcoonsiqU" +
                "AFo5WvrFCPu8fqZeKbr9lBfRLB7W6JccLD0HjEVyhEZHk6R2zT4JIqEHMWv/EDsoFLsaAg4pXOtvGwjrQYdUQjU24PX3f0y29eBD" +
                "Lw0XAsP3AJHB5q5HvxpBX/8d9fyrrxDXoZD7N+2hdgWcJuC7Ut9aHxoRnh5qPubIAmouSKeTPyPaodrwT7VUUlY4N78AyNStFYVE" +
                "HqkWd+rWosz/conuqJ7MH8ibwnpgnYtIwOYIzVd4OikXy5KfKsJFlO1zcTR8ipTgPHG0xFPvxDN0ThElDYs/UXDg17r5iUJW5ikb" +
                "5ekrHE9N99NttJEgFfg/EaHluHI1ypTaDmhG5qGuA4v8EfsfBnQCVw+v/YXRqHFsEUmPiv9CvlpetiKjcmncIUCklA8u73vU59Nx" +
                "mTBSGf4MGgb90R+C8TGeAXffgQXA1JEV8VliTyVe8Ctrf5PovRSOg1BzsJDefthDkqwWl6AwsG5lN7BYUo9z+Al70T/fd4c0QENX" +
                "Uy5yZDLOwkZY83VrfiPE7MWUHGtlVADiouD9ysGgjFaWajcygOq8KZ4SmfMzMOcU8FY97BAKOqU4SoxTwlvJhBzyRHmPT0POa2cw" +
                "8glZfUawn8hPeNAIoh2jyhAK6wHQHz9p3XIx7cUnlM2AP/PNzEWZQ7oWGqaO9aJQGln0G3eMIT08SEm3hf1LUR0aMUVUMoHDxe66" +
                "8ozLZR7Jker6ov1sVWrU6wULQ/zLQR3arNeAIZolIrYVmlOxm7cUuEN9RkAnMm4uEsSJx1y7/lenG6MH+0pgyIkQKqpgPVCFqa3U" +
                "lcUK7PqXNxjSBk0w1HPEgeQMjJ+UzdkNNecylydoyCURAxFy1Ea+UGlvfoOfzCt814zlA8fUg0SGJOjE5DOxuiH5k6RHC3knKYMA" +
                "bUVrE4m4S3WggN95OagyA0aNBHnYP7Sg/+ybHgwaQfWVyuFwSG8htqdGEfkNKEq0CQz7PXyagJo0lm8chUjNk4ASzz5H3lfXIREz" +
                "q9XSFrutUmCn1ASJNk5KrmTNLCA5h432wlt41Lmn3MtjH8zYGLLz/T4tDA3FxJwYXVf8xgmtq5KCrmRrs8zW1tZ6J70kYjtibrda" +
                "LNFMqOEVMgf6439HNpvVihY5cErKO9Po0Nl3OEEOnThGJaHERocpnCCTiWgeXvKGvvNp/gcAKJp60E9lNkWQVMdcLa7z48dO35xa" +
                "I8I1W7DXV4DFmkwn0TiegmEiSNBKXVq++0KOUJLWQcJenCKN5BMlXo6Ud8CZKS5WsujC+dOEtbvpoiiEgrimMmWjfbCQHEgDWtTk" +
                "SfNoV5WMGv5Up08zL5xPVoin0LaIxFMeO2DdtF1S3lStFxPxSPVowzlnM0oztdgUnQwXn2RhKXucfsLhhaf4AJHK2s7PGGgL5ATl" +
                "hyD11x6myCiSHQKWuHB5Gh+s/CxNmFMyTr6i4TB4GvEaRFlBPD9Qg1U7MkU2UOOl8iy0pVvMyUGOjLvaOEpOLqHoSyK9Cp3WyaKC" +
                "Iy/s4cD6zzKDWrBf9yvMNreohgBH8TT9hIDi7WCb7OpRCHNyDnVkfCyljmcNYdjhDwe9eohu8h9QSwcII1tyHLMOAABoGwAAUEsD" +
                "BBQACAgIAAAAIQAAAAAAAAAAAAAAAAAIAAkAbWFpbi5FUkJVVAUAAYA1zhJzcIr3cPXx8edydvTxUXCM9/dz1TDW5AoI8vQL8VEA" +
                "S8UHOIY4e3AFuYaEBvkpGHABAFBLBwj6BXSzMgAAADMAAABQSwECFAAUAAgICAAAACEATkyw0TcAAAA2AAAADwAJAAAAAAAAAAAA" +
                "AAAAAAAAZXJhdHctcGFjay5qc29uVVQFAAGANc4SUEsBAhQAFAAICAgAAAAhACNbchyzDgAAaBsAAB0ACQAAAAAAAAAAAAAAfQAA" +
                "AGVyYXR3Lm15bW9kLmFzc2V0cy9SRUFETUUudHh0VVQFAAGANc4SUEsBAhQAFAAICAgAAAAhAPoFdLMyAAAAMwAAAAgACQAAAAAA" +
                "AAAAAAAAhA8AAG1haW4uRVJCVVQFAAGANc4SUEsFBgAAAAADAAMA2QAAAPUPAAAAAA=="
    }
}
