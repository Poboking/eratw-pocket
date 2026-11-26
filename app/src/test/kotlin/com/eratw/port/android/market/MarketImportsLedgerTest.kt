package com.eratw.port.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 导入台账 imports.jsonl 的定向单测（CONTRACT-MKM-W2 §2.1 判据）：
 *
 * 1. append / compact 正确（同键覆盖取最新）；
 * 2. 坏行 / 空行跳过不炸；
 * 3. tmp + rename 原子（compact 无临时残壳；文件整份替换）；
 * 4. 文件不存在 = 空台账。
 *
 * 反向证明锚：摘掉 upsert 的 compact 分支（恒走 appendLine），`compact 越闸整文件重写`
 * 用例即转红；摘掉 decodeOrNull 的缺键闸，`坏行整行跳过` 用例即转红。
 */
class MarketImportsLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 生产缺省闸太大（64 条），测试统一给小闸让 compact 路径可实测。 */
    private fun newLedger(compactAfterDeadLines: Int = 3): MarketImportsLedger =
        MarketImportsLedger(File(tmp.root, MarketImportsLedger.FILE_NAME), compactAfterDeadLines)

    private fun record(
        kind: String = "kojo-pack",
        id: String = "demo",
        version: String = "1.0.0",
        packageId: String = "kojo-aaaaaaaa",
        importedAtMs: Long = 1_700_000_000_000L,
    ): MarketImportRecord = MarketImportRecord(kind, id, version, packageId, importedAtMs)

    private fun ledgerFile(): File = File(tmp.root, MarketImportsLedger.FILE_NAME)

    // ------------------------------------------------------------------
    // 判据 4：文件不存在 = 空台账
    // ------------------------------------------------------------------

    @Test
    fun `文件不存在等于空台账且读路径不创建文件`() {
        val ledger = newLedger()
        assertNull(ledger.lookup("kojo-pack", "demo"))
        assertTrue(ledger.all().isEmpty())
        assertTrue("读路径不得创建台账文件", !ledgerFile().exists())
    }

    // ------------------------------------------------------------------
    // 判据 1：append / lookup（同键覆盖取最新）
    // ------------------------------------------------------------------

    @Test
    fun `同键重复 upsert 取最新一条且历史行还在文件里`() {
        val ledger = newLedger(compactAfterDeadLines = 64) // 不触发 compact，纯 append 面
        ledger.upsert(record(version = "1.0.0", packageId = "kojo-11111111", importedAtMs = 100L))
        ledger.upsert(record(version = "1.0.1", packageId = "kojo-22222222", importedAtMs = 200L))

        val latest = ledger.lookup("kojo-pack", "demo")
        assertEquals("1.0.1", latest?.version)
        assertEquals("kojo-22222222", latest?.packageId)
        assertEquals(200L, latest?.importedAtMs)
        assertEquals("append 面：两行都在，取末条", 2, ledgerFile().readLines().size)
    }

    @Test
    fun `多键并存且互不覆盖`() {
        val ledger = newLedger(compactAfterDeadLines = 64)
        ledger.upsert(record(id = "alpha", version = "1.0.0", packageId = "kojo-11111111"))
        ledger.upsert(record(id = "beta", version = "2.0.0", packageId = "kojo-22222222"))
        ledger.upsert(record(id = "alpha", version = "1.1.0", packageId = "kojo-33333333"))

        assertEquals("kojo-33333333", ledger.lookup("kojo-pack", "alpha")?.packageId)
        assertEquals("kojo-22222222", ledger.lookup("kojo-pack", "beta")?.packageId)
        assertNull("不同 kind 同 id 是另一个键", ledger.lookup("dict-pack", "alpha"))
        assertEquals(3, ledger.all().size)
    }

    // ------------------------------------------------------------------
    // 判据 1：compact（死行到闸整文件重写为每键最新一条）
    // ------------------------------------------------------------------

    @Test
    fun `compact 越闸整文件重写为每键最新一条且无临时残壳`() {
        val ledger = newLedger(compactAfterDeadLines = 3)
        // 4 次 demo 同键 upsert：第 4 次死行数达 3（前 3 行都成了死行）⇒ 本次走 compact。
        ledger.upsert(record(version = "1.0.0", packageId = "kojo-11111111"))
        ledger.upsert(record(version = "1.0.1", packageId = "kojo-22222222"))
        ledger.upsert(record(version = "1.0.2", packageId = "kojo-33333333"))
        ledger.upsert(record(version = "1.0.3", packageId = "kojo-44444444"))

        assertEquals("compact 后每键只剩最新一条", 1, ledger.all().size)
        val latest = ledger.lookup("kojo-pack", "demo")
        assertEquals("1.0.3", latest?.version)
        assertEquals("kojo-44444444", latest?.packageId)
        assertEquals("整文件重写 = 单行", 1, ledgerFile().readLines().size)
        assertTrue(
            "tmp+rename 原子：不留临时残壳",
            tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun `compact 保留其他键的最新行`() {
        val ledger = newLedger(compactAfterDeadLines = 2)
        ledger.upsert(record(id = "alpha", version = "1.0.0", packageId = "kojo-11111111"))
        ledger.upsert(record(id = "beta", version = "2.0.0", packageId = "kojo-22222222"))
        ledger.upsert(record(id = "alpha", version = "1.0.1", packageId = "kojo-44444444")) // 死行 1，仍走 append
        // alpha 第 3 次 upsert：死行 2（alpha 两行旧账）⇒ 本次走 compact。
        ledger.upsert(record(id = "alpha", version = "1.1.0", packageId = "kojo-33333333"))

        assertEquals("kojo-33333333", ledger.lookup("kojo-pack", "alpha")?.packageId)
        assertEquals("beta 的账在 compact 后原样保留", "kojo-22222222", ledger.lookup("kojo-pack", "beta")?.packageId)
        assertEquals("compact 后每键一条", 2, ledger.all().size)
    }

    // ------------------------------------------------------------------
    // 判据 2：坏行 / 空行跳过
    // ------------------------------------------------------------------

    @Test
    fun `坏行与空行整行跳过不炸`() {
        val good = MarketImportsLedger(tmp.root.resolve(MarketImportsLedger.FILE_NAME), 64)
        good.upsert(record(id = "keep", version = "1.0.0", packageId = "kojo-11111111"))

        // 逐条坏形：多键 / 重复键 / 时间戳不是数字（闸各自单独可证）。
        val broken = listOf(
            "", // 空行
            "   ", // 纯空白行
            "这不是 JSON", // 非 JSON
            "{\"kind\":\"kojo-pack\"}", // 缺键
            "{\"kind\":\"kojo-pack\",\"id\":\"x\",\"version\":\"1.0.0\"," +
                "\"packageId\":\"kojo-99999999\",\"importedAtMs\":1,\"extra\":true}", // 多键
            "{\"kind\":\"kojo-pack\",\"id\":\"x\",\"kind\":\"kojo-pack\",\"version\":\"1.0.0\"," +
                "\"packageId\":\"kojo-99999999\",\"importedAtMs\":1}", // 重复键
            "{\"kind\":\"kojo-pack\",\"id\":\"x\",\"version\":\"1.0.0\",\"packageId\":\"kojo-99999999\"," +
                "\"importedAtMs\":\"not-a-number\"}", // 时间戳非数字
        )
        ledgerFile().appendText(broken.joinToString(separator = "\n", postfix = "\n"))

        assertEquals("坏行全跳过，好行照常取用", "kojo-11111111", good.lookup("kojo-pack", "keep")?.packageId)
        assertNull("坏行不产出记录", good.lookup("kojo-pack", "x"))
        assertEquals(1, good.all().size)
    }

    // ------------------------------------------------------------------
    // 转义面：任意字符串值不腐蚀台账
    // ------------------------------------------------------------------

    @Test
    fun `字符串值含引号反斜杠换行时转义往返无损`() {
        val ledger = newLedger()
        val weird = MarketImportRecord(
            kind = "kojo-pack",
            id = "demo",
            version = "1.0.0",
            packageId = "kojo-aaaaaaaa",
            importedAtMs = 1L,
        )
        // id/packageId 走受控字符集，但台账对任意输入必须不腐蚀：直接把转义过的行写进
        // 文件再读回（覆盖引号、反斜杠、换行、制表四种转义路径）。
        ledger.upsert(weird)
        val escapedLine = "{\"kind\":\"a\\\\b\\\"c\\nd\\te\",\"id\":\"escaped\",\"version\":\"1.0.0\"," +
            "\"packageId\":\"kojo-bbbbbbbb\",\"importedAtMs\":7}"
        ledgerFile().appendText(escapedLine + "\n")

        val weird2 = ledger.all().last()
        assertEquals("a\\b\"c\nd\te", weird2.kind)
        assertEquals("escaped", weird2.id)
        assertEquals(7L, weird2.importedAtMs)
        assertEquals("前一条 demo 的账不受坏形邻居影响", "kojo-aaaaaaaa", ledger.lookup("kojo-pack", "demo")?.packageId)
    }

    // ------------------------------------------------------------------
    // MKM-W4-UNLINK 判据 4：remove（原子重写删记录）
    // ------------------------------------------------------------------

    @Test
    fun `remove 删键重写 其它键保留 历史行与坏行一并自愈`() {
        val ledger = newLedger(compactAfterDeadLines = 64) // 不触发 compact 的闸，remove 自带整文件重写
        ledger.upsert(record(id = "gone", version = "1.0.0", packageId = "kojo-11111111"))
        ledger.upsert(record(id = "gone", version = "1.1.0", packageId = "kojo-22222222")) // 同键历史行
        ledger.upsert(record(id = "keep", version = "2.0.0", packageId = "kojo-33333333"))
        ledgerFile().appendText("这不是 JSON\n") // 坏行（外部改动形态）

        ledger.remove("kojo-pack", "gone")

        assertNull("gone 键清空（含历史行）", ledger.lookup("kojo-pack", "gone"))
        assertEquals("keep 键原样保留", "kojo-33333333", ledger.lookup("kojo-pack", "keep")?.packageId)
        assertEquals("重写后只剩 keep 最新一条（历史行与坏行一并抹掉）", 1, ledger.all().size)
        assertTrue(
            "tmp+rename 原子：不留临时残壳",
            tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun `remove 空台账与键不存在都是零动作 no-op`() {
        // ① 空台账（文件不存在）：no-op，连文件都不建。
        val fresh = newLedger()
        fresh.remove("kojo-pack", "demo")
        assertTrue("空台账 no-op：不得创建台账文件", !ledgerFile().exists())

        // ② 键不存在（台账有别的键）：no-op，盘上字节零改动。
        val seeded = newLedger()
        seeded.upsert(record(id = "keep", version = "1.0.0", packageId = "kojo-11111111"))
        val before = ledgerFile().readBytes()
        seeded.remove("kojo-pack", "absent")
        assertTrue("键不存在 no-op：文件字节零改动", before.contentEquals(ledgerFile().readBytes()))
        assertEquals("keep 的账不受影响", "kojo-11111111", seeded.lookup("kojo-pack", "keep")?.packageId)
    }

    @Test
    fun `remove 删到空 台账成合法空文件态`() {
        val ledger = newLedger()
        ledger.upsert(record(id = "only", version = "1.0.0", packageId = "kojo-11111111"))

        ledger.remove("kojo-pack", "only")

        assertTrue("唯一记录删掉后 all 为空", ledger.all().isEmpty())
        assertNull("lookup 为空", ledger.lookup("kojo-pack", "only"))
        assertTrue(
            "删到空 = 重写为空文件（合法空台账形态），无临时残壳",
            ledgerFile().isFile && ledgerFile().readLines().isEmpty() &&
                tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") },
        )
    }
}
