package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 在 [rt2] 的 `code/<packageId>/` 下写一个合法产物文件（`<16 位小写 hex>.mod` 普通文件）。 */
private fun writeArtifact(rt2: File, packageId: String, addressKey: String, payload: String): File {
    val dir = File(File(rt2, MOD_CODE_DIR_NAME), packageId)
    dir.mkdirs()
    return File(dir, addressKey + MOD_CODE_ARTIFACT_EXTENSION).apply { writeText(payload) }
}

/**
 * [ModCodeOverview] 的 JUnit4 单测：只碰纯 JVM 文件系统（无 Robolectric、无 Android 依赖）。
 *
 * 夹具直接写普通文件而不用 [ModCodeStore.install]：install 会把产物设成只读，Windows 上
 * `TemporaryFolder` 删不掉会漏临时目录；本卡只读，产物是否只读不影响读数口径。
 */
class ModCodeOverviewTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `总闸关时整块只报未开启且不扫 code 目录`() {
        val rt2 = temp.newFolder("rt2")
        writeArtifact(rt2, PKG, ADDRESS_KEY, "payload")
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val on = buildModCodeOverview(rt2, dexLoaderEnabled = true)
        assertEquals("反证前提：同一 fixture 总闸开必须产出一行", 1, on.rows.size)
        val off = buildModCodeOverview(rt2, dexLoaderEnabled = false)
        assertTrue("总闸关必须是显式未开启", off.gateOff)
        assertTrue("关态不产出行（D5：不扫 code/）", off.rows.isEmpty())
        assertFalse("关态不走「空表」文案分支", off.empty)
        assertFalse("关态不是读盘失败", off.readFailed)
        assertEquals("总闸关必须走关态文案", zh.modCodeOverviewGateOff, modCodeGateLine(zh, false))
        assertTrue(zh.modCodeOverviewGateOff.contains("未开启"))
        assertTrue(
            "关态必须明说不会装载任何第三方代码",
            zh.modCodeOverviewGateOff.contains("不会装载任何第三方代码"),
        )
    }

    @Test
    fun `总闸开但没有 code 目录时是显式空态而不是未开启`() {
        val rt2 = temp.newFolder("empty-rt2")
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val overview = buildModCodeOverview(rt2, dexLoaderEnabled = true)
        assertFalse(overview.gateOff)
        assertTrue("总闸开且没有包 = 显式空态", overview.empty)
        assertFalse("目录读得到就不算读盘失败", overview.readFailed)
        assertTrue(overview.rows.isEmpty())
        assertEquals("本机还没有第三方代码包。", zh.modCodeOverviewEmpty)
    }

    @Test
    fun `单包单版本读出包名版本数与体积`() {
        val rt2 = temp.newFolder("rt2")
        val artifact = writeArtifact(rt2, PKG, ADDRESS_KEY, PAYLOAD_ONE)
        val overview = buildModCodeOverview(rt2, dexLoaderEnabled = true)
        val row = overview.rows.single()
        assertEquals(PKG, row.packageId)
        assertEquals(1, row.versions)
        assertEquals(artifact.length(), row.bytes)
        assertFalse("没写过信任标记就是未信任", row.trusted)
        assertTrue("本行属于总闸开的快照", row.dexLoaderEnabled)
        assertFalse("有行就不是空态", overview.empty)
    }

    @Test
    fun `多版本体积按件累加且与存储列表口径一致`() {
        val rt2 = temp.newFolder("rt2")
        writeArtifact(rt2, PKG, ADDRESS_KEY, PAYLOAD_ONE)
        writeArtifact(rt2, PKG, OTHER_ADDRESS_KEY, PAYLOAD_TWO)
        val row = buildModCodeOverview(rt2, dexLoaderEnabled = true).rows.single()
        val listed = ModCodeStore(rt2).list(PKG)
        assertEquals("版本数与 ModCodeStore.list 同口径", listed.size, row.versions)
        assertEquals("体积 = 逐件 sizeBytes 之和", listed.sumOf { it.sizeBytes }, row.bytes)
        assertEquals(2, row.versions)
    }

    @Test
    fun `信任标记实读两态且无产物的标记不伪造行`() {
        val rt2 = temp.newFolder("rt2")
        writeArtifact(rt2, PKG, ADDRESS_KEY, PAYLOAD_ONE)
        writeArtifact(rt2, OTHER_PKG, ADDRESS_KEY, PAYLOAD_TWO)
        assertTrue("夹具前置：信任写标记成功", ModTrustStore.trust(rt2, PKG))
        // 有标记但 code/ 下没有产物目录：信任态照实读，但不许因此长出一行。
        File(rt2, GHOST_PKG + ModDexGate.TRUST_MARKER_SUFFIX).writeText("")
        val rows = buildModCodeOverview(rt2, dexLoaderEnabled = true).rows
        assertEquals("只有真实产物包才出行", 2, rows.size)
        assertTrue("已信任的包读成已信任", rows.first { it.packageId == PKG }.trusted)
        assertFalse("没写标记的包读成未信任", rows.first { it.packageId == OTHER_PKG }.trusted)
        assertTrue("反证：标记确实被 trustedPackages 看见", ModTrustStore.trustedPackages(rt2).contains(GHOST_PKG))
    }

    @Test
    fun `坏包名目录与非产物文件一律忽略`() {
        val rt2 = temp.newFolder("rt2")
        writeArtifact(rt2, PKG, ADDRESS_KEY, PAYLOAD_ONE)
        val codeDir = File(rt2, MOD_CODE_DIR_NAME)
        writeArtifact(rt2, BAD_PKG, ADDRESS_KEY, PAYLOAD_TWO)
        File(File(codeDir, PKG), "notes.txt").writeText("noise")
        writeArtifact(rt2, PKG, ADDRESS_KEY.uppercase(), PAYLOAD_TWO)
        File(File(codeDir, PKG), "sub").mkdirs()
        File(codeDir, "loose.mod").writeText("noise")
        val rows = buildModCodeOverview(rt2, dexLoaderEnabled = true).rows
        assertEquals("只留合法包名的包", listOf(PKG), rows.map { it.packageId })
        assertTrue("反证：坏包名目录物理存在但仍被过滤", File(codeDir, BAD_PKG).isDirectory)
        assertEquals("坏文件名不进版本数", 1, rows.single().versions)
    }

    @Test
    fun `体积格式化边界与既有 formatStorageBytes 同口径`() {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val zero = ModCodeRow(PKG, 1, 0L, false, true)
        val kib = ModCodeRow(PKG, 1, KIB_BYTES, false, true)
        val kibHalf = ModCodeRow(PKG, 1, KIB_HALF_BYTES, false, true)
        val mib = ModCodeRow(PKG, 1, MIB_BYTES, false, true)
        assertEquals("「demo.mod」· 1 个版本 · 0 B", modCodeRowReadout(zh, zero))
        assertEquals("「demo.mod」· 1 个版本 · 1.0 KB", modCodeRowReadout(zh, kib))
        assertEquals("「demo.mod」· 1 个版本 · 1.5 KB", modCodeRowReadout(zh, kibHalf))
        assertEquals("「demo.mod」· 1 个版本 · 1.0 MB", modCodeRowReadout(zh, mib))
        assertEquals(formatStorageBytes(KIB_HALF_BYTES), modCodeRowReadout(zh, kibHalf).substringAfterLast("· "))
    }

    @Test
    fun `包按包名升序且同包只一行`() {
        val rt2 = temp.newFolder("rt2")
        writeArtifact(rt2, "z.mod", ADDRESS_KEY, PAYLOAD_ONE)
        writeArtifact(rt2, "a.mod", ADDRESS_KEY, PAYLOAD_ONE)
        writeArtifact(rt2, "m.mod", ADDRESS_KEY, PAYLOAD_ONE)
        writeArtifact(rt2, "m.mod", OTHER_ADDRESS_KEY, PAYLOAD_TWO)
        val rows = buildModCodeOverview(rt2, dexLoaderEnabled = true).rows
        assertEquals(listOf("a.mod", "m.mod", "z.mod"), rows.map { it.packageId })
        assertEquals("同包多版本合成一行", 2, rows.first { it.packageId == "m.mod" }.versions)
    }

    @Test
    fun `三语行读数与总闸行逐字`() {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val en = SettingsStrings.of(SettingsLanguage.EN)
        val ja = SettingsStrings.of(SettingsLanguage.JA)
        val row = ModCodeRow(PKG, 2, KIB_BYTES, true, true)
        assertEquals("「demo.mod」· 2 个版本 · 1.0 KB", modCodeRowReadout(zh, row))
        assertEquals("\"demo.mod\" · 2 version(s) · 1.0 KB", modCodeRowReadout(en, row))
        assertEquals("「demo.mod」・2 バージョン・1.0 KB", modCodeRowReadout(ja, row))
        assertEquals(zh.modCodeOverviewGateOn, modCodeGateLine(zh, true))
        assertEquals(ja.modCodeOverviewGateOff, modCodeGateLine(ja, false))
    }

    @Test
    fun `rt2 目录不可解析时降级为缺数据而不是空包`() {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val overview = buildModCodeOverview(null, dexLoaderEnabled = true)
        assertTrue("null rt2 必须降级为读盘失败", overview.readFailed)
        assertFalse("读盘失败不是空态（不能显示成没装）", overview.empty)
        assertFalse(overview.gateOff)
        assertTrue(overview.rows.isEmpty())
        assertTrue("缺数据照「—」口径", zh.modCodeOverviewUnreadable.contains("—"))
    }

    @Test
    fun `装载结论按磁盘包名 join 到行上，没结论的行显式为空`() {
        val rt2 = temp.newFolder("rt2")
        writeArtifact(rt2, PKG, ADDRESS_KEY, PAYLOAD_ONE)
        writeArtifact(rt2, OTHER_PKG, ADDRESS_KEY, PAYLOAD_TWO)
        val gap = EngineSurfaceGap(required = MOD_ENGINE_API_VERSION + 1, current = MOD_ENGINE_API_VERSION)
        val report = ModCodeLoadReport(
            listOf(
                CodeCarrierRecord(
                    diskPackageId = PKG,
                    runtimePackageId = null,
                    outcome = CodeCarrierOutcome.ENGINE_TOO_OLD,
                    rejectReason = "ABI 闸原文",
                    engineTooOld = gap,
                ),
            ),
        )
        val rows = buildModCodeOverview(rt2, dexLoaderEnabled = true, loadReport = report).rows
        assertEquals("每包一行不变（join 不增删行）", 2, rows.size)
        val target = rows.first { it.packageId == PKG }
        assertEquals(CodeCarrierOutcome.ENGINE_TOO_OLD, target.loadConclusion?.outcome)
        assertEquals("版本缺口随结论一起投影到行上", gap, target.loadConclusion?.engineGap)
        assertNull("没进结论表的包不许编结论", rows.first { it.packageId == OTHER_PKG }.loadConclusion)
        assertNull(
            "不传装载结论（本进程还没装配）时整列都空，不许拿盘面猜",
            buildModCodeOverview(rt2, dexLoaderEnabled = true).rows.first().loadConclusion,
        )
    }

    @Test
    fun `装载结论行三语取词与缺数据口径`() {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val en = SettingsStrings.of(SettingsLanguage.EN)
        val ja = SettingsStrings.of(SettingsLanguage.JA)
        val gap = EngineSurfaceGap(required = MOD_ENGINE_API_VERSION + 1, current = MOD_ENGINE_API_VERSION)
        val base = ModCodeRow(PKG, 1, KIB_BYTES, true, true)
        val loaded = base.copy(loadConclusion = ModCodeLoadConclusion(CodeCarrierOutcome.LOADED))
        val tooOld = base.copy(
            loadConclusion = ModCodeLoadConclusion(CodeCarrierOutcome.ENGINE_TOO_OLD, gap),
        )
        val skipped = base.copy(loadConclusion = ModCodeLoadConclusion(CodeCarrierOutcome.NEEDS_TRUST))
        assertTrue("缺数据必须照「—」口径", modCodeLoadLabel(zh, null).contains("—"))
        assertEquals(zh.modCodeLoadLoaded, modCodeLoadLabel(zh, loaded.loadConclusion))
        assertEquals(zh.modCodeLoadEngineTooOld(gap.required, gap.current), modCodeLoadLabel(zh, tooOld.loadConclusion))
        assertEquals(zh.modCodeLoadSkipped, modCodeLoadLabel(zh, skipped.loadConclusion))
        for (text in listOf(zh, en, ja)) {
            val values = listOf(
                text.modCodeLoadUnknown,
                text.modCodeLoadLoaded,
                text.modCodeLoadSkipped,
                text.modCodeLoadEngineTooOld(gap.required, gap.current),
            )
            assertEquals("${text.lang} 四类装载结论必须两两可分", 4, values.distinct().size)
        }
        val line = modCodeLoadLine(zh, tooOld)
        assertTrue("读数行必须带标签：$line", line.contains(zh.modCodeLoadLabel))
        assertTrue("读数行必须带结论：$line", line.contains(zh.modCodeLoadEngineTooOld(gap.required, gap.current)))
        assertTrue("标签与结论之间走既有「标签：值」句式", line.contains("："))
    }

    @Test
    fun `双身份包信任态按运行期标记实读而不是磁盘身份`() {
        val rt2 = temp.newFolder("rt2-dual")
        writeArtifact(rt2, DUAL_DISK_ID, ADDRESS_KEY, PAYLOAD_ONE)
        ModCodeIdentity.write(rt2, DUAL_DISK_ID, DUAL_RUNTIME_ID)
        // 旧版缺陷留下的磁盘身份标记：装载闸从来不读，概览也不许把它当成已信任。
        File(rt2, DUAL_DISK_ID + ModDexGate.TRUST_MARKER_SUFFIX).writeText("", Charsets.UTF_8)
        assertFalse(
            "磁盘身份标记不得冒充已信任（与装载闸同键）",
            buildModCodeOverview(rt2, dexLoaderEnabled = true).rows.single().trusted,
        )
        File(rt2, DUAL_DISK_ID + ModDexGate.TRUST_MARKER_SUFFIX).delete()

        assertTrue("信任写入必须成功（真身份桥）", ModTrustStore.trust(rt2, DUAL_DISK_ID))
        val row = buildModCodeOverview(rt2, dexLoaderEnabled = true).rows.single()
        assertTrue("信任态必须按运行期身份实读", row.trusted)
        assertNull("侧车健康时不得是坏档行", row.blockedReason)
        assertTrue("反证：标记落在运行期身份", File(rt2, DUAL_RUNTIME_ID + ModDexGate.TRUST_MARKER_SUFFIX).isFile)
        assertFalse("反证：不得写磁盘身份标记", File(rt2, DUAL_DISK_ID + ModDexGate.TRUST_MARKER_SUFFIX).exists())
    }

    @Test
    fun `身份侧车损坏的概览行显式不可判而不是未信任`() {
        val rt2 = temp.newFolder("rt2-dual-bad")
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        writeArtifact(rt2, DUAL_DISK_ID, ADDRESS_KEY, PAYLOAD_ONE)
        File(rt2, ModCodeIdentity.fileName(DUAL_DISK_ID)).writeText("not-a-codeid", Charsets.UTF_8)
        val row = buildModCodeOverview(rt2, dexLoaderEnabled = true).rows.single()
        assertFalse("坏档不得显示已信任", row.trusted)
        assertEquals("坏档必须把原因保留到行上", TrustMarkerBlockReason.IDENTITY_CORRUPT, row.blockedReason)
        assertEquals("概览卡坏档必须显式「身份损坏」", zh.modCodeStateCorrupt, modCodeRowTrustLabel(zh, row))
        assertNotEquals("不许把坏档显示成「未信任」", zh.modTrustStateUntrusted, modCodeRowTrustLabel(zh, row))
    }

    @Test
    fun `单身份包的信任读数不得凭空长出坏档原因`() {
        val rt2 = temp.newFolder("rt2-single")
        writeArtifact(rt2, PKG, ADDRESS_KEY, PAYLOAD_ONE)
        assertTrue(ModTrustStore.trust(rt2, PKG))
        val row = buildModCodeOverview(rt2, dexLoaderEnabled = true).rows.single()
        assertTrue("单身份包信任态逐字节不变", row.trusted)
        assertNull("单身份包没有坏档原因", row.blockedReason)
    }

    private companion object {
        const val PKG: String = "demo.mod"
        const val OTHER_PKG: String = "other.mod"
        const val BAD_PKG: String = "Bad Pkg"
        const val GHOST_PKG: String = "ghost.mod"
        const val ADDRESS_KEY: String = "0123456789abcdef"
        const val OTHER_ADDRESS_KEY: String = "fedcba9876543210"
        const val PAYLOAD_ONE: String = "abc"
        const val PAYLOAD_TWO: String = "abcdefgh"
        const val KIB_BYTES: Long = 1024L
        const val KIB_HALF_BYTES: Long = 1536L
        const val MIB_BYTES: Long = 1_048_576L
        const val DUAL_DISK_ID: String = "kojo-0123abcd"
        const val DUAL_RUNTIME_ID: String = "author.dual"
    }
}
