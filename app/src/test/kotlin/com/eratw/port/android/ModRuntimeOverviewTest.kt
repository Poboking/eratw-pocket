package com.eratw.port.android

import com.eratw.port.core.mod.MOD_STORAGE_WARN_BYTES
import com.eratw.port.core.mod.MOD_STORAGE_WARN_KEYS
import com.eratw.port.core.mod.ModStorageSectionCodec
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.mod.RegisteredFaces
import com.eratw.port.modapi.TimingSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Mod 运行时概览」的纯读数面用例（Lane AW）。
 *
 * 只喂值、不碰 Android / 不碰活会话：聚合口径（top 3 / 合计 / 阈值换算）、隐藏口径
 * （账本 null、未接线）、失败栏与慢栏的显隐判据都在这里钉死。
 */
class ModRuntimeOverviewTest {

    private fun sample(scope: String, count: Long, totalNanos: Long): TimingSample =
        TimingSample(
            scope = scope,
            count = count,
            totalNanos = totalNanos,
            maxNanos = totalNanos,
            averageNanos = if (count == 0L) 0L else totalNanos / count,
            lastNanos = totalNanos,
        )

    private fun dataOf(
        activeIds: Set<String> = emptySet(),
        timing: Map<String, List<TimingSample>> = emptyMap(),
        slow: Map<String, Long> = emptyMap(),
        thresholdNanos: Long = 16_000_000L,
        faces: Map<String, RegisteredFaces> = emptyMap(),
    ): ModRuntimeData = ModRuntimeData(
        activeIds = activeIds,
        timingByPackage = timing,
        slowCountByPackage = slow,
        thresholdNanos = thresholdNanos,
        facesByPackage = faces,
    )

    @Test
    fun `账本为 null 或读数口未接线时整块隐藏`() {
        // 账本 null（关态 / 手工装配）是「整块隐藏」的唯一判据：必须是 null，不是空概览。
        assertNull(buildModRuntimeOverview(listOf("a"), emptyMap(), null))
        // 未接线 / 已卸载的读数口同理给 null，而不是抛穿设置页。
        ModRuntimeOverviewEntry.uninstall(this)
        assertNull(ModRuntimeOverviewEntry.read(listOf("a")))
        // 有账本但没有任何已装包：空概览（仍渲染卡片 + 空态行），不是隐藏。
        val empty = buildModRuntimeOverview(emptyList(), emptyMap(), dataOf())
        assertNotNull(empty)
        assertEquals(emptyList<ModRuntimeRow>(), empty!!.rows)
    }

    @Test
    fun `装载失败原因出现在对应行且空栏显示无`() {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val overview = buildModRuntimeOverview(
            packageIds = listOf("eratw.kojo.broken", "eratw.kojo.ok"),
            failures = mapOf("eratw.kojo.broken" to "boom: manifest 缺 entry"),
            data = dataOf(activeIds = setOf("eratw.kojo.ok")),
        )!!
        val broken = overview.rows[0]
        val ok = overview.rows[1]
        assertEquals("boom: manifest 缺 entry", broken.failure)
        assertNull(ok.failure)
        assertTrue("失败行要点名原因", failureLine(zh, broken).contains("boom"))
        assertEquals("没失败的行要给「无」而不是空串", zh.modRuntimeOverviewNone, failureLine(zh, ok))
        // 失败行不会因为别的包失败而串上 active（失败包不在会话 active 集里）。
        assertFalse(broken.active)
        assertTrue(ok.active)
    }

    @Test
    fun `慢计数为 0 不显示慢栏且阈值随行给出`() {
        val overview = buildModRuntimeOverview(
            packageIds = listOf("a", "b"),
            failures = emptyMap(),
            data = dataOf(
                slow = mapOf("a" to 0L, "b" to 3L),
                thresholdNanos = 16_000_000L,
            ),
        )!!
        val clean = overview.rows[0]
        val slow = overview.rows[1]
        assertFalse("0 次慢处理器不该挂慢栏", showsSlowLine(clean))
        assertTrue(showsSlowLine(slow))
        assertEquals(0L, clean.slowCount)
        assertEquals("16 ms 阈值要换算成毫秒", 16L, slow.slowThresholdMillis)
        // 慢栏文案带上次数与阈值毫秒，读数可自证（换阈值就在断言的 16 上红）。
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val line = zh.modRuntimeOverviewSlow(slow.slowCount, slow.slowThresholdMillis)
        assertTrue(line.contains("3"))
        assertTrue(line.contains("16"))
    }

    @Test
    fun `timing 聚合按累计耗时降序取最慢三条且合计全量`() {
        val samples = listOf(
            sample("lifecycle.onLoad", count = 5L, totalNanos = 5_000_000L),
            sample("interaction.onCall", count = 2L, totalNanos = 40_000_000L),
            sample("hook.onTurn", count = 7L, totalNanos = 12_500_000L),
            sample("ui.render", count = 3L, totalNanos = 1_000_000L),
        )
        val overview = buildModRuntimeOverview(
            packageIds = listOf("pkg"),
            failures = emptyMap(),
            data = dataOf(timing = mapOf("pkg" to samples)),
        )!!
        val row = overview.rows.single()
        // top 3 按累计耗时降序：40ms > 12.5ms → 13ms（四舍五入）> 5ms，1ms 那条必须被截掉。
        assertEquals(listOf("interaction.onCall", "hook.onTurn", "lifecycle.onLoad"), row.topScopes.map { it.scope })
        assertEquals(3, row.topScopes.size)
        assertEquals(40L, row.topScopes[0].totalMillis)
        assertEquals(13L, row.topScopes[1].totalMillis)
        assertEquals(2L, row.topScopes[0].count)
        // 合计是全量（包含没进 top 3 的那条）：次数 5+2+7+3=17，累计 58.5ms → 59ms。
        assertEquals(17L, row.totalCount)
        assertEquals(59L, row.totalMillis)
    }

    @Test
    fun `纳秒到毫秒四舍五入且负值归零`() {
        assertEquals(0L, nanosToMillisRounded(0L))
        assertEquals(0L, nanosToMillisRounded(499_999L))
        assertEquals(1L, nanosToMillisRounded(500_000L))
        assertEquals(1L, nanosToMillisRounded(1_499_999L))
        assertEquals(2L, nanosToMillisRounded(1_500_000L))
        assertEquals(16L, nanosToMillisRounded(16_000_000L))
        assertEquals(16L, nanosToMillisRounded(15_500_000L))
        assertEquals(15L, nanosToMillisRounded(15_499_999L))
        assertEquals("时钟异常不得让屏上出现负耗时", 0L, nanosToMillisRounded(-1L))
    }

    @Test
    fun `读数口的装摘认主不得互相撅空`() {
        val owner = Any()
        val other = Any()
        val payload = dataOf()
        try {
            ModRuntimeOverviewEntry.install(owner) { payload }
            assertEquals("装好后现取现用", payload, ModRuntimeOverviewEntry.read(listOf("a")))
            ModRuntimeOverviewEntry.install(other) { null }
            assertNull("后装者覆盖旧接线", ModRuntimeOverviewEntry.read(listOf("a")))
            ModRuntimeOverviewEntry.uninstall(owner)
            assertNull("旧壳无权摘新壳的接线", ModRuntimeOverviewEntry.read(listOf("a")))
            ModRuntimeOverviewEntry.install(owner) { payload }
            ModRuntimeOverviewEntry.uninstall(owner)
            assertNull("归属正确即摘空", ModRuntimeOverviewEntry.read(listOf("a")))
        } finally {
            ModRuntimeOverviewEntry.uninstall(owner)
            ModRuntimeOverviewEntry.uninstall(other)
        }
    }

    @Test
    fun `没有已装包的行读取为空读数而非抛异常`() {
        val overview = buildModRuntimeOverview(
            packageIds = listOf("missing"),
            failures = emptyMap(),
            data = dataOf(activeIds = setOf("other")),
        )!!
        val row = overview.rows.single()
        assertEquals(0L, row.totalCount)
        assertEquals(0L, row.totalMillis)
        assertTrue(row.topScopes.isEmpty())
        assertEquals(0L, row.slowCount)
        assertFalse("未在 active 集的包要显示未运行", row.active)
    }

    @Test
    fun `注册面读数进概览行且渲染行点名每个字段`() {
        val faces = RegisteredFaces(
            hooks = 3,
            retiredHooks = 1,
            events = mapOf("PRI" to 1, "NORMAL" to 2, "LATER" to 3, "ONLY" to 0),
            interactions = 4,
            services = 5,
            strings = 6,
            settings = 7,
            images = 8,
        )
        val overview = buildModRuntimeOverview(
            packageIds = listOf("pkg"),
            failures = emptyMap(),
            data = dataOf(activeIds = setOf("pkg"), faces = mapOf("pkg" to faces)),
        )!!
        val row = overview.rows.single()
        assertEquals("读数快照要原样进 row", faces, row.faces)
        // 渲染行逐字段点名（截断 / 少一档 / 顺序乱都在这里红）。
        val expected = "注册 hooks=3 退槽=1 events=PRI/NORMAL/LATER/ONLY=1/2/3/0 " +
            "interactions=4 services=5 strings=6 settings=7 images=8"
        assertEquals(expected, SettingsStrings.of(SettingsLanguage.ZH).modRuntimeOverviewFaces(row.faces))
    }

    @Test
    fun `注册面全零也渲染且三语各自点名`() {
        val overview = buildModRuntimeOverview(
            packageIds = listOf("pkg"),
            failures = emptyMap(),
            data = dataOf(),
        )!!
        val row = overview.rows.single()
        assertEquals("没注册任何面要给全零快照，不是 null / 不隐藏", RegisteredFaces.EMPTY, row.faces)
        val texts = SettingsLanguage.entries.map { SettingsStrings.of(it).modRuntimeOverviewFaces(row.faces) }
        texts.forEach { line ->
            listOf("hooks=0", "PRI/NORMAL/LATER/ONLY=0/0/0/0", "services=0", "images=0").forEach {
                assertTrue("全零行缺点 $it：$line", line.contains(it))
            }
        }
        assertEquals("三语注册面行不得雷同", SettingsLanguage.entries.size, texts.distinct().size)
    }

    @Test
    fun `存储段读数与设置已改数进概览行`() {
        val storage = ModStorageReadout(bytes = 1536L, keys = 3)
        val overview = buildModRuntimeOverview(
            packageIds = listOf("pkg"),
            failures = emptyMap(),
            data = ModRuntimeData(
                activeIds = setOf("pkg"),
                timingByPackage = emptyMap(),
                slowCountByPackage = emptyMap(),
                thresholdNanos = 16_000_000L,
                storageByPackage = mapOf("pkg" to storage),
                settingsChangedByPackage = mapOf("pkg" to 2),
            ),
        )!!
        val row = overview.rows.single()
        assertEquals("存储段读数要原样进 row", storage, row.storage)
        assertEquals("设置已改项数要原样进 row", 2, row.settingsChanged)
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val readout = checkNotNull(row.storage)
        assertEquals("存储 1.5 KB · 3 键", zh.modRuntimeOverviewStorage(formatStorageBytes(readout.bytes), readout.keys))
        assertEquals("设置已改 2 项", zh.modRuntimeOverviewSettingsChanged(checkNotNull(row.settingsChanged)))
    }

    @Test
    fun `缺存储段与会话设置快照时显式破折号不是零`() {
        val overview = buildModRuntimeOverview(listOf("pkg"), emptyMap(), dataOf())!!
        val row = overview.rows.single()
        assertNull("没有存储段时读数必须缺省 null（不是 0 键）", row.storage)
        assertNull("没有会话设置快照时读数必须缺省 null（不是 0 项）", row.settingsChanged)
        val texts = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        assertEquals("存储 —", texts[0].modRuntimeOverviewStorageMissing)
        assertEquals("Storage —", texts[1].modRuntimeOverviewStorageMissing)
        assertEquals("ストレージ —", texts[2].modRuntimeOverviewStorageMissing)
        assertEquals("设置已改 —", texts[0].modRuntimeOverviewSettingsChangedMissing)
        assertEquals("Settings changed: —", texts[1].modRuntimeOverviewSettingsChangedMissing)
        assertEquals("設定変更 —", texts[2].modRuntimeOverviewSettingsChangedMissing)
    }

    @Test
    fun `字节数格式按 KB 与 MB 一位小数且显式零字节`() {
        assertEquals("0 B", formatStorageBytes(0L))
        assertEquals("512 B", formatStorageBytes(512L))
        assertEquals("1023 B", formatStorageBytes(1023L))
        assertEquals("1.0 KB", formatStorageBytes(1024L))
        assertEquals("1.5 KB", formatStorageBytes(1536L))
        assertEquals("1.0 MB", formatStorageBytes(1024L * 1024L))
        assertEquals("2.5 MB", formatStorageBytes(5L * 1024L * 1024L / 2L))
    }

    @Test
    fun `账本 null 时注册面也无从渲染`() {
        // 注册面读数不能成为绕过「账本 null ⇒ 整块隐藏」的后门。
        ModRuntimeOverviewEntry.uninstall(this)
        assertNull(
            "账本 null 必须整块隐藏",
            buildModRuntimeOverview(listOf("pkg"), emptyMap(), null),
        )
        assertNull("读数口未接线时同样整块隐藏", ModRuntimeOverviewEntry.read(listOf("pkg")))
    }

    // -----------------------------------------------------------------
    // 存储超限读数（Lane storagereadout）：判据单源 = core footprint，app 侧只转出 + 标一行。
    // -----------------------------------------------------------------

    /** 造一行只关心存储读数的行（其余字段取不影响显隐判据的最小值）。 */
    private fun rowOfStorage(storage: ModStorageReadout?): ModRuntimeRow = ModRuntimeRow(
        packageId = "pkg",
        active = true,
        failure = null,
        totalCount = 0L,
        totalMillis = 0L,
        topScopes = emptyList(),
        slowCount = 0L,
        slowThresholdMillis = 16L,
        storage = storage,
    )

    /** 用真实 store 铺 [keys] 个键，值统一为一个字节，返回该包的概览读数。 */
    private fun readoutWithKeyCount(keys: Int): ModStorageReadout {
        val store = ModStorageStore()
        val api = store.apiFor("pkg")
        repeat(keys) { api.put("k$it", "v") }
        return checkNotNull(storageReadoutsOf(store, listOf("pkg"))["pkg"]) { "有段就必须给读数" }
    }

    @Test
    fun `未超限的存储段不标超限且字节与旧单段编码同源`() {
        val store = ModStorageStore()
        val api = store.apiFor("pkg")
        api.put("alpha", "1")
        api.put("beta", "22")
        api.put("gamma", "333")
        val readout = checkNotNull(storageReadoutsOf(store, listOf("pkg"))["pkg"])
        // 字节口径不得漂移：仍等于 sectionsSnapshot 单段走 encode 的大小（旧实现算式）。
        val snapshot = store.sectionsSnapshot().single()
        assertEquals(
            "存储字节必须与旧单段编码同源",
            ModStorageSectionCodec.encode(listOf(snapshot)).size.toLong(),
            readout.bytes,
        )
        assertEquals(3, readout.keys)
        assertFalse("3 键 / 几十字节远低于两档阈值", readout.overKeysLimit)
        assertFalse(readout.overBytesLimit)
        assertFalse("未超限不得出现标记", rowOfStorage(readout).showsStorageWarning)
    }

    @Test
    fun `键数越 core 阈值时标超限而字节未越限`() {
        val readout = readoutWithKeyCount(MOD_STORAGE_WARN_KEYS + 1)
        assertEquals("键数 = 阈值 + 1", MOD_STORAGE_WARN_KEYS + 1, readout.keys)
        assertTrue("严格大于阈值即越限（判据在 core）", readout.overKeysLimit)
        assertFalse("小值短键远不到字节阈值", readout.overBytesLimit)
        assertTrue("越限行必须标", rowOfStorage(readout).showsStorageWarning)
    }

    @Test
    fun `键数恰好等于阈值不算超限`() {
        // core 口径是严格大于（ModStorageFootprint.kt:73-74）；app 侧只转出不改判。
        val readout = readoutWithKeyCount(MOD_STORAGE_WARN_KEYS)
        assertEquals(MOD_STORAGE_WARN_KEYS, readout.keys)
        assertFalse(readout.overKeysLimit)
        assertFalse(readout.overBytesLimit)
        assertFalse("等于阈值不标", rowOfStorage(readout).showsStorageWarning)
    }

    @Test
    fun `编码字节越 core 阈值时标超限而键数未越限`() {
        val store = ModStorageStore()
        val api = store.apiFor("pkg")
        val chunk = "x".repeat(60_000)
        repeat(5) { api.put("big$it", chunk) }
        val readout = checkNotNull(storageReadoutsOf(store, listOf("pkg"))["pkg"])
        assertEquals(5, readout.keys)
        assertTrue("编码字节必须越过 256 KiB", readout.bytes > MOD_STORAGE_WARN_BYTES)
        assertFalse("只有 5 键，键数未越限", readout.overKeysLimit)
        assertTrue(readout.overBytesLimit)
        assertTrue("字节越限同样要标", rowOfStorage(readout).showsStorageWarning)
    }

    @Test
    fun `缺存储段时超限标记不出现且仍显示显式破折号`() {
        val readouts = storageReadoutsOf(ModStorageStore(), listOf("ghost"))
        assertNull("没有段 ≠ 空段：读数必须 null", readouts["ghost"])
        assertFalse("缺段不得标超限", rowOfStorage(null).showsStorageWarning)
        // 缺段行照旧显式「—」（不是 0 B，也不是任何标记）。
        assertEquals("存储 —", SettingsStrings.of(SettingsLanguage.ZH).modRuntimeOverviewStorageMissing)
    }

    @Test
    fun `存储超限提示三语齐备且不含内部术语`() {
        val texts = SettingsLanguage.entries.map { SettingsStrings.of(it).modRuntimeOverviewStorageOver }
        assertEquals("三语必须互不相同（漏档 / 只补一种都会只剩重复串）", 3, texts.distinct().size)
        assertEquals("⚠ 存储数据量偏大，可能让存档变大；建议作者检查写入的数据量。", texts[0])
        assertEquals(
            "⚠ This mod stores a lot of data, which may make saves larger. " +
                "Consider asking the author to check how much it writes.",
            texts[1],
        )
        assertEquals(
            "⚠ 保存データが多いため、セーブが大きくなる可能性があります。" +
                "作者に書き込み量の確認をおすすめします。",
            texts[2],
        )
        texts.forEach { line ->
            assertTrue("提示必须以可辨识预警符起头：$line", line.startsWith("⚠"))
            listOf("packageId", ".rt2", "aux", "MST1", "1024", "256").forEach { term ->
                assertFalse("玩家文案泄露内部术语 $term：$line", line.contains(term))
            }
        }
    }
}
