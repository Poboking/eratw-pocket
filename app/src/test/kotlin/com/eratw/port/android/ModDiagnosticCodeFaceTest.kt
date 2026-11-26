package com.eratw.port.android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [readModCodeFace] / [buildModDiagnosticReport] 的「代码包装载面」单测（task-193）。
 *
 * 纯 JVM、真盘夹具：本节的读数就是盘面现状（`code/` 产物、身份侧车、信任标记），
 * 所以用例只写盘、不碰会话、不建类加载器。断言逐行点名，并额外守两条硬口径：
 * 1. 正文不许出现内部键名（`mod.dex` / `.codeid` / `.trusted` / 枚举常量名等），
 *    玩家看到的是人话；
 * 2. 拿不到的「最近一次装载结论」必须照「—」，不许猜。
 */
class ModDiagnosticCodeFaceTest {

    // ------------------------------------------------------------------
    // 1. 总闸关：不扫 code/，两态分开写
    // ------------------------------------------------------------------

    @Test
    fun `总闸关时只写未开启且不去读代码产物目录`() {
        val root = tempRoot()
        writeArtifact(root, DISK_ID, KEY_A)
        val face = readModCodeFace(root, dexLoaderEnabled = false)
        assertFalse("总闸关时读数必须保持关态", face.dexLoaderEnabled)
        assertTrue("总闸关时不许扫出代码包行", face.rows.isEmpty())
        assertFalse("总闸关不算读失败", face.readFailed)
        val report = buildModDiagnosticReport(input(face, dexLoaderGate = false))
        assertTrue(report.contains("代码包装载面（只读取证）"))
        assertTrue(report.contains("  第三方代码包装载总开关：未开启（默认关）"))
        assertTrue(report.contains("  未开启：本快照不扫描代码产物目录（总开关关时本机不跑第三方代码）"))
        assertFalse("关闸时不许出现逐包读数", report.contains("有代码产物的包"))
        assertFalse("关闸时不许出现装载结论行", report.contains("最近一次装载结论"))
    }

    // ------------------------------------------------------------------
    // 2. 总闸开但盘上没有代码包：0 个，不抛
    // ------------------------------------------------------------------

    @Test
    fun `总闸开但没有代码产物时给 0 个且不抛`() {
        val face = readModCodeFace(tempRoot(), dexLoaderEnabled = true)
        assertTrue(frameMessage(face), face.rows.isEmpty())
        assertFalse("空站点的 code 目录不算读失败", face.readFailed)
        val report = buildModDiagnosticReport(input(face, dexLoaderGate = true))
        assertTrue(report.contains("  第三方代码包装载总开关：开（默认关）"))
        assertTrue(report.contains("  有代码产物的包：0 个"))
        assertFalse("没有代码包时不许出现逐包行", report.contains("磁盘身份"))
    }

    // ------------------------------------------------------------------
    // 3. 已信任的单版本包：每一行逐字点名
    // ------------------------------------------------------------------

    @Test
    fun `已信任的单版本包每行读数逐字点名`() {
        val root = tempRoot()
        writeArtifact(root, DISK_ID, KEY_A, ONE_KIB)
        markTrusted(root, DISK_ID)
        val face = readModCodeFace(root, dexLoaderEnabled = true)
        assertEquals("只应有一行代码包", 1, face.rows.size)
        val row = face.rows.single()
        assertEquals(DISK_ID, row.diskPackageId)
        assertEquals(CodeIdSidecar.Absent, row.identity)
        assertEquals(1, row.versions)
        assertEquals(ModCodeTrustState.TRUSTED, row.trust)
        assertNull("前置条件齐时不该有盘面裁决", row.hint)
        val report = buildModDiagnosticReport(input(face, dexLoaderGate = true))
        assertTrue(report.contains("[代码 1] 磁盘身份：$DISK_ID"))
        assertTrue(report.contains("  运行期身份：$DISK_ID（无侧车，装载回退磁盘身份）"))
        assertTrue(report.contains("  信任状态：已信任（运行期身份有标记，装载端同一口径）"))
        assertTrue(report.contains("  代码产物：1 个版本 / 1.0 KB"))
        assertTrue(report.contains("  装载闸裁决：无阻挡（前置条件齐，装载端可放行）"))
        assertTrue(report.contains("  最近一次装载结论：—（本会话未尝试装载，不猜）"))
        assertTrue(report.contains("说明：本节是盘面取证 + 本会话装载结论，不是未来装载的判据"))
    }

    // ------------------------------------------------------------------
    // 4. 未信任：说清装载会拒，且信任口径按运行期身份
    // ------------------------------------------------------------------

    @Test
    fun `未信任的包写清装载会拒`() {
        val root = tempRoot()
        writeArtifact(root, DISK_ID, KEY_A)
        val face = readModCodeFace(root, dexLoaderEnabled = true)
        val row = face.rows.single()
        assertEquals(ModCodeTrustState.UNTRUSTED, row.trust)
        assertEquals(ModCodeGateHintKind.UNTRUSTED, row.hint?.kind)
        val report = buildModDiagnosticReport(input(face, dexLoaderGate = true))
        assertTrue(report.contains("  信任状态：未信任（两套身份都没有信任标记）"))
        assertTrue(report.contains("  装载闸裁决：未信任，装载会拒（设置页信任该包后才会装载）"))
    }

    // ------------------------------------------------------------------
    // 5. 多版本残留：读数与裁决都得点名
    // ------------------------------------------------------------------

    @Test
    fun `多版本残留时读数与裁决都点名`() {
        val root = tempRoot()
        writeArtifact(root, DISK_ID, KEY_A)
        writeArtifact(root, DISK_ID, KEY_B)
        val face = readModCodeFace(root, dexLoaderEnabled = true)
        val row = face.rows.single()
        assertEquals(2, row.versions)
        assertEquals(ModCodeGateHintKind.MULTIPLE_VERSIONS, row.hint?.kind)
        val report = buildModDiagnosticReport(input(face, dexLoaderGate = true))
        assertTrue(report.contains("  代码产物：2 个版本 / 2 B（多版本残留，装载会拒）"))
        assertTrue(report.contains("  装载闸裁决：多版本残留，装载会拒（重新导入，只留最新一份）"))
    }

    // ------------------------------------------------------------------
    // 6. 身份侧车损坏：运行期身份照「—」，不替装载端编一个身份
    // ------------------------------------------------------------------

    @Test
    fun `身份侧车损坏时运行期身份照破折号`() {
        val root = tempRoot()
        writeArtifact(root, DISK_ID, KEY_A)
        writeCorruptSidecar(root, DISK_ID)
        val face = readModCodeFace(root, dexLoaderEnabled = true)
        val row = face.rows.single()
        assertTrue("坏侧车必须折成 Corrupt", row.identity is CodeIdSidecar.Corrupt)
        assertEquals(ModCodeTrustState.CORRUPT_IDENTITY, row.trust)
        assertEquals(ModCodeGateHintKind.CODE_ID_CORRUPT, row.hint?.kind)
        val report = buildModDiagnosticReport(input(face, dexLoaderGate = true))
        assertTrue(report.contains("  运行期身份：—（身份侧车损坏，装载会拒）"))
        assertTrue(report.contains("  信任状态：无法判定（身份侧车损坏，装载会拒）"))
        assertTrue(report.contains("  装载闸裁决：身份侧车损坏，装载会拒（重新导入可重建）"))
    }

    // ------------------------------------------------------------------
    // 7. 运行期身份与磁盘身份不同：按运行期身份判信任（装载端同口径）
    // ------------------------------------------------------------------

    @Test
    fun `运行期身份有信任标记时判为已信任`() {
        val root = tempRoot()
        writeArtifact(root, DISK_ID, KEY_A)
        ModCodeIdentity.write(root, DISK_ID, RUNTIME_ID)
        markTrusted(root, RUNTIME_ID)
        val face = readModCodeFace(root, dexLoaderEnabled = true)
        val row = face.rows.single()
        assertTrue("侧车声明的运行期身份必须读出来", row.identity is CodeIdSidecar.Bound)
        assertEquals(ModCodeTrustState.TRUSTED, row.trust)
        assertNull("运行期身份已信任时不该有信任类裁决", row.hint)
        val report = buildModDiagnosticReport(input(face, dexLoaderGate = true))
        assertTrue(report.contains("  运行期身份：$RUNTIME_ID（身份侧车声明）"))
        assertTrue(report.contains("  信任状态：已信任（运行期身份有标记，装载端同一口径）"))
    }

    // ------------------------------------------------------------------
    // 8. 信任标记只落在磁盘身份上：装载端仍判未信任（两套口径分开写）
    // ------------------------------------------------------------------

    @Test
    fun `信任标记只在磁盘身份上时判为未信任`() {
        val root = tempRoot()
        writeArtifact(root, DISK_ID, KEY_A)
        ModCodeIdentity.write(root, DISK_ID, RUNTIME_ID)
        markTrusted(root, DISK_ID)
        val face = readModCodeFace(root, dexLoaderEnabled = true)
        val row = face.rows.single()
        assertEquals(ModCodeTrustState.TRUSTED_ON_DISK_ID, row.trust)
        assertEquals(ModCodeGateHintKind.UNTRUSTED, row.hint?.kind)
        val report = buildModDiagnosticReport(input(face, dexLoaderGate = true))
        assertTrue(
            report.contains("  信任状态：未信任（信任标记只在磁盘身份上，运行期身份缺标记，装载端仍判未信任）"),
        )
    }

    // ------------------------------------------------------------------
    // 9. 取不到盘面（rt2 目录未知）：照「—」、不抛
    // ------------------------------------------------------------------

    @Test
    fun `盘面取不到时照破折号且不抛`() {
        val face = readModCodeFace(null, dexLoaderEnabled = true)
        assertTrue("rt2 目录未知必须标读失败", face.readFailed)
        assertTrue("读失败时不许编造代码包行", face.rows.isEmpty())
        val report = buildModDiagnosticReport(input(face, dexLoaderGate = true))
        assertTrue(report.contains("  代码产物目录：—（本次读不出，不展开逐包读数，不猜）"))
        assertFalse("读失败时不许出现逐包行", report.contains("磁盘身份"))
    }

    // ------------------------------------------------------------------
    // 10. 正文不许出现内部键名 / null / 枚举常量名
    // ------------------------------------------------------------------

    @Test
    fun `正文不出现内部键名`() {
        val root = tempRoot()
        writeArtifact(root, DISK_ID, KEY_A)
        writeArtifact(root, DISK_ID, KEY_B)
        writeCorruptSidecar(root, DISK_ID)
        val face = readModCodeFace(root, dexLoaderEnabled = true)
        val report = buildModDiagnosticReport(input(face, dexLoaderGate = true))
        for (token in INTERNAL_TOKENS) {
            assertFalse("正文不许出现内部键名：$token", report.contains(token))
        }
        assertFalse("正文不许出现 null", report.contains("null"))
    }

    // ------------------------------------------------------------------
    // 11. 装载端已发布结论：逐包行照本会话结论写（真盘 + 合成快照）
    // ------------------------------------------------------------------

    @Test
    fun `装载端有结论时逐包行照结论写`() {
        val root = tempRoot()
        writeArtifact(root, DISK_ID, KEY_A)
        val published = loadReportOf(
            CodeCarrierRecord(
                diskPackageId = DISK_ID,
                runtimePackageId = null,
                outcome = CodeCarrierOutcome.LOADED,
            ),
        )
        val face = readModCodeFace(root, dexLoaderEnabled = true, loadReport = published)
        assertEquals("结论对象必须原样带进读数", published, face.loadReport)
        val report = buildModDiagnosticReport(input(face, dexLoaderGate = true))
        assertTrue(report.contains("  最近一次装载结论：装载成功"))
        assertFalse("有结论时不许再写「未尝试装载」", report.contains("本会话未尝试装载"))
    }

    // ------------------------------------------------------------------
    // 12. 磁盘身份换代：按侧车声明的运行期身份兜底 join
    // ------------------------------------------------------------------

    @Test
    fun `磁盘身份换代后按运行期身份兜底`() {
        val root = tempRoot()
        writeArtifact(root, DISK_ID, KEY_A)
        ModCodeIdentity.write(root, DISK_ID, RUNTIME_ID)
        markTrusted(root, RUNTIME_ID)
        // 旧装配的结论键在换代前的磁盘身份上；本次盘面已经是新目录。
        val published = loadReportOf(
            CodeCarrierRecord(
                diskPackageId = "com.demo.previous",
                runtimePackageId = RUNTIME_ID,
                outcome = CodeCarrierOutcome.LOADED,
            ),
        )
        val face = readModCodeFace(root, dexLoaderEnabled = true, loadReport = published)
        val report = buildModDiagnosticReport(input(face, dexLoaderGate = true))
        assertTrue("磁盘身份对不上时必须按运行期身份兜底，否则会误报未装载", report.contains("  最近一次装载结论：装载成功"))
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun loadReportOf(vararg records: CodeCarrierRecord): ModCodeLoadReport =
        ModCodeLoadReport(records.toList())

    private fun tempRoot(): File = Files.createTempDirectory("mod-diag-codeface").toFile()

    /** 在 `code/<磁盘身份>/` 下放一个合法产物（名 = 16 位小写十六进制 + `.mod`）。 */
    private fun writeArtifact(root: File, diskId: String, addressKey: String, bytes: Int = 1) {
        val dir = File(root, "$MOD_CODE_DIR_NAME/$diskId")
        assertTrue("夹具必须能建产物目录", dir.isDirectory || dir.mkdirs())
        File(dir, "$addressKey$MOD_CODE_ARTIFACT_EXTENSION").writeText("x".repeat(bytes))
    }

    /** 落一个坏身份侧车（行数不足，读取端按坏档折 Corrupt）。 */
    private fun writeCorruptSidecar(root: File, diskId: String) {
        File(root, ModCodeIdentity.fileName(diskId)).writeText("RT2CODEID/1\n")
    }

    /** 落一个信任标记（空文件，与设置页写盘同形）。 */
    private fun markTrusted(root: File, packageId: String) {
        File(root, ModDexGate.trustMarkerName(packageId)).writeText("")
    }

    private fun input(face: ModCodeFace?, dexLoaderGate: Boolean): ModDiagnosticReportInput =
        ModDiagnosticReportInput(
            engineVersion = "0.2.47",
            runtimeGate = true,
            dexLoaderGate = dexLoaderGate,
            scan = ModManageScan.Empty,
            codeFace = face,
        )

    private fun frameMessage(face: ModCodeFace): String =
        "期望 0 行，实际 ${face.rows.size} 行（readFailed=${face.readFailed}）"

    private companion object {
        const val DISK_ID: String = "com.demo.disk"
        const val RUNTIME_ID: String = "com.demo.runtime"
        const val KEY_A: String = "0123456789abcdef"
        const val KEY_B: String = "fedcba9876543210"
        const val ONE_KIB: Int = 1024
        val INTERNAL_TOKENS: List<String> = listOf(
            "mod.dex",
            ".codeid",
            "RT2CODEID",
            "codeid",
            ".trusted",
            "ModDex",
            "dexLoader",
            "packageId",
            "MULTIPLE_VERSIONS",
            "UNTRUSTED",
            "GATE_OFF",
            "CODE_ID_CORRUPT",
            "TRUSTED_ON_DISK_ID",
        )
    }
}
