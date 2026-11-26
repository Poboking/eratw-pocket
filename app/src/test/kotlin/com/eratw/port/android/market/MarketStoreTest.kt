package com.eratw.port.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 落盘布局与已装记账的定向单测（CONTRACT §2.4/§2.5 判据）。
 * 反向证明锚：`self-heal` 用例摘掉 verifiedRecords 的剔除+写回即转红；
 * `uninstall removes dir record empty ancestors and staging shells` 用例摘掉 packStagingNames 清扫表即转红；
 * `sweep keeps only highest version dir and clears losers with staging shells` 用例摘掉
 * sweepSuperseded 的「保留最高版」选主判定（改成删最高版）即转红（CONTRACT-MKM-W5-GC §2.4）。
 */
class MarketStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: MarketStore

    private fun newStore(): MarketStore = MarketStore(tmp.root)

    private fun record(
        kind: String = "kojo-pack",
        id: String = "demo",
        version: String = "1.0.0",
        name: String = "Demo",
        bytes: Long = 100L,
    ): InstalledPack = InstalledPack(
        kind = kind,
        id = id,
        version = version,
        name = name,
        author = "author",
        license = "MIT",
        installedAtMs = 1_700_000_000_000L,
        containerSha256 = "a".repeat(64),
        contentDigest = "b".repeat(64),
        fileCount = 2,
        bytes = bytes,
    )

    /** 在盘上造一个在位落点（pack.json + files 树），模拟装好的包。 */
    private fun makeDir(kind: String, id: String, version: String): File {
        val dir = store.dirOf(kind, id, version)
        dir.mkdirs()
        File(dir, "files").mkdirs()
        File(dir, MarketContract.MANIFEST_FILE).writeText("schema=1")
        File(dir, "files/a.txt").writeText("alpha")
        return dir
    }

    @Before
    fun setUp() {
        store = newStore()
    }

    @Test
    fun `records roundtrip preserves every field`() {
        val record = record()
        store.upsertRecord(record)
        assertEquals(listOf(record), store.readRecords())
    }

    @Test
    fun `records escaping survives tabs newlines and backslashes in free text`() {
        val record = record(name = "含 TAB\t换行\n反斜杠\\ 与「」的自由文本")
        store.upsertRecord(record)
        assertEquals(listOf(record), store.readRecords())
    }

    @Test
    fun `null anchors survive roundtrip as empty fields`() {
        val record = record().copy(containerSha256 = null, contentDigest = null)
        store.upsertRecord(record)
        assertEquals(listOf(record), store.readRecords())
    }

    @Test
    fun `corrupt ledger lines are skipped not fatal`() {
        makeDir("kojo-pack", "demo", "1.0.0")
        store.upsertRecord(record())
        val ledger = File(tmp.root, "installed.journal")
        ledger.writeText("完全不成形的行（字段数不对）\n" + ledger.readText())
        assertEquals(listOf(record()), store.readRecords())
    }

    @Test
    fun `upsert replaces same pack version and keeps siblings and other versions`() {
        store.upsertRecord(record(id = "demo", version = "1.0.0"))
        store.upsertRecord(record(id = "other", version = "2.0.0"))
        store.upsertRecord(record(id = "demo", version = "1.1.0"))
        val read = store.readRecords()
        // 同包**同版本**才替换；不同版本并存（updateCheck 取最高，卸载按版本点各的）
        assertEquals(3, read.size)
        assertTrue(read.any { it.id == "demo" && it.version == "1.1.0" })
        assertTrue(read.any { it.id == "demo" && it.version == "1.0.0" })
        assertTrue(read.any { it.id == "other" })
    }

    @Test
    fun `ledger write leaves no temp file behind`() {
        store.upsertRecord(record())
        val leftovers = tmp.root.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue(leftovers.isEmpty())
        assertTrue(File(tmp.root, "installed.journal").isFile)
    }

    @Test
    fun `verified records self-heal drops lost dirs and writes back`() {
        val kept = record(id = "kept")
        val lost = record(id = "lost")
        makeDir("kojo-pack", "kept", "1.0.0")
        store.upsertRecord(kept)
        store.upsertRecord(lost)
        // lost 没有落点目录 = 丢条目
        val verified = store.verifiedRecords()
        assertEquals(listOf(kept), verified)
        // 自愈 = 写回：账里只剩在位那条
        assertEquals(listOf(kept), store.readRecords())
    }

    @Test
    fun `installed bytes uses the same verification as the list`() {
        makeDir("kojo-pack", "kept", "1.0.0")
        store.upsertRecord(record(id = "kept", bytes = 100L))
        store.upsertRecord(record(id = "lost", bytes = 900L))
        assertEquals(100L, store.installedBytes())
    }

    @Test
    fun `installed bytes empty when nothing installed`() {
        assertEquals(0L, store.installedBytes())
    }

    @Test
    fun `uninstall missing pack and dir is NotFound`() {
        assertEquals(UninstallOutcome.NotFound, store.uninstall("kojo-pack", "ghost", "1.0.0"))
    }

    @Test
    fun `uninstall removes dir record empty ancestors and staging shells`() {
        val dir = makeDir("kojo-pack", "demo", "1.0.0")
        store.upsertRecord(record())
        // 顺手摆上这个包在暂存区可能留下的全部残壳（契约派生名表）
        store.stagingRoot.mkdirs()
        MarketContract.packStagingNames("demo", "1.0.0").forEach { name ->
            val shell = File(store.stagingRoot, name)
            if (name.endsWith(MarketContract.ZIP_SUFFIX)) shell.writeText("zip") else shell.mkdirs()
        }
        val sibling = makeDir("kojo-pack", "sibling", "1.0.0")

        assertEquals(UninstallOutcome.Ok, store.uninstall("kojo-pack", "demo", "1.0.0"))
        assertFalse(dir.exists())
        assertEquals(emptyList<InstalledPack>(), store.readRecords())
        // 空壳父目录逐级收到 packs 根为止
        assertFalse(File(store.packsRoot, "kojo-pack/demo").exists())
        assertTrue(sibling.exists())
        // 残壳全清
        MarketContract.packStagingNames("demo", "1.0.0").forEach { name ->
            assertFalse("残壳应已清：$name", File(store.stagingRoot, name).exists())
        }
    }

    @Test
    fun `uninstall with missing dir but present record clears ledger only`() {
        store.upsertRecord(record())
        val outcome = store.uninstall("kojo-pack", "demo", "1.0.0")
        assertEquals(UninstallOutcome.Ok, outcome)
        assertEquals(emptyList<InstalledPack>(), store.readRecords())
    }

    @Test
    fun `empty ancestors collapse stops at packs root`() {
        makeDir("kojo-pack", "demo", "1.0.0")
        store.upsertRecord(record())
        store.uninstall("kojo-pack", "demo", "1.0.0")
        // packs/kojo-pack 空了也要收掉（逐级直到 packs 根；packs 根本身不删）
        assertFalse(File(store.packsRoot, "kojo-pack").exists())
        assertTrue(store.packsRoot.exists())
    }

    /** 在暂存区摆上这个包可能留下的全部残壳（契约派生名表，照卸载用例的做法）。 */
    private fun makeStagingShells(id: String, version: String) {
        store.stagingRoot.mkdirs()
        MarketContract.packStagingNames(id, version).forEach { name ->
            val shell = File(store.stagingRoot, name)
            if (name.endsWith(MarketContract.ZIP_SUFFIX)) shell.writeText("zip") else shell.mkdirs()
        }
    }

    private fun assertStagingShellsGone(id: String, version: String) {
        MarketContract.packStagingNames(id, version).forEach { name ->
            assertFalse("残壳应已清：$name", File(store.stagingRoot, name).exists())
        }
    }

    @Test
    fun `sweep keeps only highest version dir and clears losers with staging shells`() {
        makeDir("kojo-pack", "demo", "1.0.0")
        makeDir("kojo-pack", "demo", "1.2.0")
        makeDir("kojo-pack", "demo", "2.0.0")
        makeStagingShells("demo", "1.0.0")
        makeStagingShells("demo", "1.2.0")
        makeStagingShells("demo", "2.0.0")
        val solo = makeDir("kojo-pack", "solo", "0.0.9") // 跨 id 的目录不是本组的清扫对象

        assertEquals(2, store.sweepSuperseded(FakeVerifier()))

        assertTrue(store.dirOf("kojo-pack", "demo", "2.0.0").exists())
        assertFalse(store.dirOf("kojo-pack", "demo", "1.2.0").exists())
        assertFalse(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
        // 被删两版的暂存残壳全清；保留版的残壳归正常装卸流管（清扫只碰被删版）
        assertStagingShellsGone("demo", "1.0.0")
        assertStagingShellsGone("demo", "1.2.0")
        assertTrue(File(store.stagingRoot, MarketContract.packStagingName("demo", "2.0.0")).exists())
        assertTrue(solo.exists())
    }

    @Test
    fun `sweep groups by kind and id so each group keeps its own highest`() {
        makeDir("kojo-pack", "demo", "1.0.0")
        makeDir("kojo-pack", "demo", "2.0.0")
        makeDir("dict-pack", "demo", "1.0.0")
        makeDir("dict-pack", "demo", "1.5.0")

        assertEquals(2, store.sweepSuperseded(FakeVerifier()))

        // 同 id 跨 kind 是两个独立组：各留各的最高版、各删各的旧版
        assertTrue(store.dirOf("kojo-pack", "demo", "2.0.0").exists())
        assertFalse(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
        assertTrue(store.dirOf("dict-pack", "demo", "1.5.0").exists())
        assertFalse(store.dirOf("dict-pack", "demo", "1.0.0").exists())
    }

    @Test
    fun `sweep keeps illegal form dirs and clears provably lower ones`() {
        makeDir("kojo-pack", "demo", "1.0.0")
        makeDir("kojo-pack", "demo", "2.0.0")
        makeDir("kojo-pack", "demo", "not-a-version") // versionCompare 必抛的非法形态

        assertEquals(1, store.sweepSuperseded(FakeVerifier()))

        // 非法形态保守保留（不参与选主也永不删除）；可证更低的旧版照清
        assertTrue(store.dirOf("kojo-pack", "demo", "not-a-version").exists())
        assertTrue(store.dirOf("kojo-pack", "demo", "2.0.0").exists())
        assertFalse(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
    }

    @Test
    fun `sweep keeps tied versions that compare equal`() {
        makeDir("kojo-pack", "demo", "1.0.0")
        makeDir("kojo-pack", "demo", "01.0.0") // 同值异写，比平 = 保守保留
        makeDir("kojo-pack", "demo", "0.9.0")

        assertEquals(1, store.sweepSuperseded(FakeVerifier()))

        assertTrue(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
        assertTrue(store.dirOf("kojo-pack", "demo", "01.0.0").exists())
        assertFalse(store.dirOf("kojo-pack", "demo", "0.9.0").exists())
    }

    @Test
    fun `sweep returns zero for missing root empty group and single version`() {
        // 库根缺失
        assertEquals(0, store.sweepSuperseded(FakeVerifier()))
        // 组目录存在但没有任何版本目录
        File(store.packsRoot, "kojo-pack/empty").mkdirs()
        assertEquals(0, store.sweepSuperseded(FakeVerifier()))
        // 单版本 = nothing to sweep
        makeDir("kojo-pack", "demo", "1.0.0")
        assertEquals(0, store.sweepSuperseded(FakeVerifier()))
        assertTrue(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
    }

    @Test
    fun `sweep is idempotent and second pass returns zero`() {
        makeDir("kojo-pack", "demo", "1.0.0")
        makeDir("kojo-pack", "demo", "2.0.0")
        makeDir("kojo-pack", "demo", "3.0.0")

        assertEquals(2, store.sweepSuperseded(FakeVerifier()))
        assertEquals(0, store.sweepSuperseded(FakeVerifier()))
        assertTrue(store.dirOf("kojo-pack", "demo", "3.0.0").exists())
        assertFalse(store.dirOf("kojo-pack", "demo", "2.0.0").exists())
    }

    @Test
    fun `swept versions converge out of the ledger on next verified read`() {
        makeDir("kojo-pack", "demo", "1.0.0")
        makeDir("kojo-pack", "demo", "2.0.0")
        store.upsertRecord(record(id = "demo", version = "1.0.0"))
        store.upsertRecord(record(id = "demo", version = "2.0.0"))

        assertEquals(1, store.sweepSuperseded(FakeVerifier()))
        // 清扫不写账（mods/rt2 与台账零触碰）：孤儿账行留在盘上，由在位自愈在读数面收敛
        assertEquals(2, store.readRecords().size)
        val verified = store.verifiedRecords()
        assertEquals(listOf(record(id = "demo", version = "2.0.0")), verified)
        assertEquals(listOf(record(id = "demo", version = "2.0.0")), store.readRecords())
    }
}
