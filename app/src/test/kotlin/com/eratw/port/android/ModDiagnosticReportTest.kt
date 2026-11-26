package com.eratw.port.android

import com.eratw.port.core.patch.StrSettingDeclaration
import com.eratw.port.modapi.ModApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildModDiagnosticReport] 的 JUnit4 单测：纯 JVM，无 Robolectric / 无 Android 依赖。
 *
 * 夹具只走既有数据类构造，不碰盘、不碰会话：拼装器是纯函数，同一输入必须逐字逐行一致，
 * 所以每个用例都点名具体行（而不是「contains 关键词」了事）。契约点名的 10 个用例
 * （全空 / 单 mod / 顺序 / 失败原因 / 停用 / 存储缺失「—」 / 总闸未开启 / skipped 明细 /
 * 哨兵不泄漏 / 行尾空白）逐条落在下方。
 *
 * 夹具一律「零参基线 + `copy` 变形」：detekt `LongParameterList` 默认阈值 6，
 * 8 参的 `baseInput(...)` 会直接报红（本波禁 suppress）。
 */
class ModDiagnosticReportTest {

    // ---------------------------------------------------------------
    // 1. 全空输入：头与尾都在、不出现 null / 空表
    // ---------------------------------------------------------------

    @Test
    fun `全空输入照常出头条与尾条且不写 null`() {
        val report = buildModDiagnosticReport(baseInput())
        val lines = report.lines()
        assertEquals("标题行必须是第一行", "eratw Mod 诊断快照（只读）", lines[0])
        assertEquals("引擎版本：0.2.47", lines[1])
        assertEquals("Mod API 版本：v${ModApi.VERSION}", lines[2])
        // 兼容版本 = 输入里携带的引擎兼容 API 版本；基线输入取 [ModApi.VERSION]，
        // 所以用同一个常量断言，避免每次 ABI 抬版就要手改死数字（对面 MODDX 波次已抬到 21）。
        assertEquals("引擎 API 兼容版本：v${ModApi.VERSION}", lines[3])
        assertEquals("Mod 运行时总闸：开（默认开）", lines[4])
        assertEquals("代码包装载总闸：未开启（默认关）", lines[5])
        assertEquals("已安装 Mod：0 个（启用 0 个，停用 0 个）", lines[6])
        assertEquals("无法读取清单：0 个", lines[8])
        assertEquals("装载失败记录：0 条", lines[9])
        assertFalse("全空输入不许出现 null", report.contains("null"))
        assertFalse("全空输入不许出现 [0]", report.contains("[0]"))
    }

    // ---------------------------------------------------------------
    // 2. 单 mod 全字段：每行逐字点名
    // ---------------------------------------------------------------

    @Test
    fun `单 mod 每行读数逐字点名`() {
        val one = "com.demo.one"
        val report = buildModDiagnosticReport(
            baseInput().copy(
                scan = scanOf(mod(pkg = one, name = "示例一", version = "1.0.0")),
                storageByPackage = mapOf(one to ModStorageReadout(bytes = 1536L, keys = 3)),
                settingsChangedByPackage = mapOf(one to 2),
                codeOverview = overview(
                    ModCodeRow(
                        packageId = one,
                        versions = 2,
                        bytes = 4608L,
                        trusted = true,
                        dexLoaderEnabled = true,
                    ),
                    dexLoaderEnabled = true,
                ),
            ),
        )
        assertTrue(report.contains("[1] $one"))
        assertTrue(report.contains("  名称：示例一"))
        assertTrue(report.contains("  版本：1.0.0"))
        assertTrue(report.contains("  来源：内置"))
        assertTrue(report.contains("  状态：启用"))
        assertTrue(report.contains("  装载失败：—"))
        assertTrue(report.contains("  存储：1.5 KB / 3 键"))
        assertTrue(report.contains("  已改设置：2 项"))
        assertTrue(report.contains("  代码包：2 个版本 / 4.5 KB / 已信任"))
    }

    // ---------------------------------------------------------------
    // 3. 多 mod：编号按扫描顺序、段间空行
    // ---------------------------------------------------------------

    @Test
    fun `多 mod 编号按扫描顺序且段间空行`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(scan = scanOf(mod(pkg = "pkg.a", name = "甲"), mod(pkg = "pkg.b", name = "乙"))),
        )
        val headers = report.lines().filter { it.startsWith("[") }
        assertEquals("编号按扫描顺序，不许按名称重排", listOf("[1] pkg.a", "[2] pkg.b"), headers)
        assertTrue("段之间要有空行", report.contains("  代码包：—\n\n[2] pkg.b"))
        assertTrue("包名顺序不许颠倒", report.indexOf("[1] pkg.a") < report.indexOf("[2] pkg.b"))
    }

    // ---------------------------------------------------------------
    // 4. 装载失败原因：命中的包出原因，没命中的照「—」
    // ---------------------------------------------------------------

    @Test
    fun `装载失败原因只落在对应包上`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                scan = scanOf(mod(pkg = "pkg.bad", name = "坏的"), mod(pkg = "pkg.ok", name = "好的")),
                failures = mapOf("pkg.bad" to "IllegalStateException: boom"),
            ),
        )
        assertTrue(report.contains("  装载失败：IllegalStateException: boom"))
        assertTrue("没失败的包照 —", report.contains("  装载失败：—"))
        assertTrue("尾条诊断计数 = 失败表条数", report.contains("装载失败记录：1 条"))
        assertFalse("整包名不许被写进别包段", report.contains("[2] pkg.bad"))
    }

    // ---------------------------------------------------------------
    // 5. 停用态：状态行明说停用，计数拆开
    // ---------------------------------------------------------------

    @Test
    fun `停用包标已停用且计数拆开`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                scan = scanOf(
                    mod(pkg = "pkg.off", name = "关掉的", enabled = false),
                    mod(pkg = "pkg.on", name = "开着的"),
                ),
            ),
        )
        assertTrue(report.contains("已安装 Mod：2 个（启用 1 个，停用 1 个）"))
        assertTrue("停用包必须明说已停用", report.contains("  状态：已停用"))
        assertTrue(report.contains("  状态：启用"))
    }

    // ---------------------------------------------------------------
    // 6. 存储 / 已改设置缺失照「—」，不写 0
    // ---------------------------------------------------------------

    @Test
    fun `存储与已改设置缺失照破折号不写零`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                scan = scanOf(mod(pkg = "pkg.a"), mod(pkg = "pkg.b")),
                storageByPackage = mapOf("pkg.a" to null),
                settingsChangedByPackage = mapOf("pkg.b" to null),
            ),
        )
        assertTrue("没有存储段照 —", report.contains("  存储：—"))
        assertTrue("没有已改项照 —", report.contains("  已改设置：—"))
        assertFalse("缺失读数不许写 0 键", report.contains("0 键"))
        assertFalse("缺失读数不许写 0 项", report.contains("0 项"))
    }

    // ---------------------------------------------------------------
    // 7. 代码包总闸未开启 = 「未开启」，不是「没装」
    // ---------------------------------------------------------------

    @Test
    fun `代码包总闸未开启写未开启而不是没装`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                scan = scanOf(mod(pkg = "pkg.a")),
                codeOverview = ModCodeOverview(dexLoaderEnabled = false, rows = emptyList(), readFailed = false),
            ),
        )
        assertTrue(report.contains("代码包装载总闸：未开启（默认关）"))
        assertTrue("单包读数必须是「未开启」", report.contains("  代码包：未开启"))
        assertFalse("「未开启」不是「没装」，不许写成缺失破折号", report.contains("  代码包：—"))
        assertFalse("关态不许报版本数（没扫 code/）", report.contains("个版本"))
    }

    // ---------------------------------------------------------------
    // 8. skipped 明细逐条原样 + 计数
    // ---------------------------------------------------------------

    @Test
    fun `读不出的 manifest 明细逐条原样`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                scan = ModManageScan(emptyList(), listOf("bad.a：manifest 缺失", "bad.b：解码失败")),
            ),
        )
        assertTrue(report.contains("无法读取清单：2 个"))
        assertTrue(
            "明细按扫描顺序逐条",
            report.indexOf("  - bad.a：manifest 缺失") < report.indexOf("  - bad.b：解码失败"),
        )
        assertTrue(report.contains("  - bad.a：manifest 缺失\n  - bad.b：解码失败"))
    }

    // ---------------------------------------------------------------
    // 9. 哨兵值不泄漏：作者 / 说明 / 设置默认值都不进报告
    // ---------------------------------------------------------------

    @Test
    fun `作者说明与设置值哨兵不许泄漏`() {
        val setting = StrSettingDeclaration(key = "greeting", default = SENTINEL_SETTING_VALUE, title = "打招呼")
        val report = buildModDiagnosticReport(
            baseInput().copy(
                scan = scanOf(
                    mod(pkg = "pkg.a", name = "甲").copy(
                        author = SENTINEL_AUTHOR,
                        description = SENTINEL_DESCRIPTION,
                        settings = listOf(setting),
                    ),
                ),
            ),
        )
        assertFalse("作者不许进快照", report.contains(SENTINEL_AUTHOR))
        assertFalse("说明不许进快照", report.contains(SENTINEL_DESCRIPTION))
        assertFalse("设置值本体不许进快照", report.contains(SENTINEL_SETTING_VALUE))
        assertFalse("设置默认值本体不许进快照", report.contains(setting.defaultText()))
        assertTrue("但包名照旧要在", report.contains("[1] pkg.a"))
    }

    // ---------------------------------------------------------------
    // 10. 行尾空白：每行不许尾巴空格、结尾不许空行
    // ---------------------------------------------------------------

    @Test
    fun `每行行尾无多余空白且结尾不带空行`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                scan = ModManageScan(
                    listOf(mod(pkg = "pkg.a", name = "甲"), mod(pkg = "pkg.b", name = "乙")),
                    listOf("bad.a：manifest 缺失"),
                ),
                failures = mapOf("pkg.a" to "boom"),
            ),
        )
        report.lines().forEachIndexed { index, line ->
            assertEquals("第 ${index + 1} 行行尾有多余空白：[$line]", line.trimEnd(), line)
        }
        assertFalse("结尾不许空行 / 换行", report.endsWith("\n"))
        assertFalse("不许连续空行", report.contains("\n\n\n"))
        assertEquals("每行都有内容或是刻意空行", report.trim(), report)
    }

    // ---------------------------------------------------------------
    // 11. 来源三值：内置 / 数据包 / 导入
    // ---------------------------------------------------------------

    @Test
    fun `来源三值各按原文标注`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                scan = scanOf(
                    mod(pkg = "pkg.builtin", source = ModSource.BUILTIN),
                    mod(pkg = "pkg.data", source = ModSource.DATA_PACKAGE),
                    mod(pkg = "pkg.import", source = ModSource.IMPORTED),
                ),
            ),
        )
        assertTrue(report.contains("  来源：内置"))
        assertTrue(report.contains("  来源：数据包"))
        assertTrue(report.contains("  来源：导入"))
    }

    // ---------------------------------------------------------------
    // 12. 显示名 / 版本 / 引擎版本缺失照「—」
    // ---------------------------------------------------------------

    @Test
    fun `显示名与版本缺失照破折号`() {
        val report = buildModDiagnosticReport(
            ModDiagnosticReportInput(
                engineVersion = "",
                runtimeGate = false,
                dexLoaderGate = true,
                scan = scanOf(mod(pkg = "pkg.a")),
            ),
        )
        assertTrue("引擎版本空照 —", report.contains("引擎版本：—"))
        assertTrue("显示名空照 —", report.contains("  名称：—"))
        assertTrue("版本空照 —", report.contains("  版本：—"))
        assertTrue("总闸关不许写成默认值", report.contains("Mod 运行时总闸：关（默认开）"))
        assertTrue(report.contains("  代码包：—"))
    }

    // ---------------------------------------------------------------
    // 13. 总闸开但本包无产物行 / 未信任：两种读数分开
    // ---------------------------------------------------------------

    @Test
    fun `总闸开时无产物照破折号未信任照原样`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                scan = scanOf(mod(pkg = "pkg.a"), mod(pkg = "pkg.b")),
                codeOverview = overview(
                    ModCodeRow(
                        packageId = "pkg.a",
                        versions = 1,
                        bytes = 512L,
                        trusted = false,
                        dexLoaderEnabled = true,
                    ),
                    dexLoaderEnabled = true,
                ),
            ),
        )
        assertTrue(report.contains("  代码包：1 个版本 / 512 B / 未信任"))
        assertTrue("没产物的包照 —", report.contains("  代码包：—"))
    }

    // ---------------------------------------------------------------
    // 14. 没有装载结论对象：照「—」并说清「未尝试」，不是「没装」
    // ---------------------------------------------------------------

    @Test
    fun `没有装载结论对象时照破折号且不猜`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                dexLoaderGate = true,
                codeFace = faceWith(codeRow("pkg.a"), loadReport = null),
            ),
        )
        assertTrue(report.contains("  最近一次装载结论：—（本会话未尝试装载，不猜）"))
        assertFalse("没有结论时不许编一个结局", report.contains("装载成功"))
    }

    // ---------------------------------------------------------------
    // 15. 有结论对象但本包本次没跑到：与「没有对象」两态分开
    // ---------------------------------------------------------------

    @Test
    fun `有结论对象但本包没跑到时照破折号`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                dexLoaderGate = true,
                codeFace = faceWith(codeRow("pkg.a"), loadReport = ModCodeLoadReport.EMPTY),
            ),
        )
        assertTrue(report.contains("  最近一次装载结论：—（本会话未尝试装载）"))
        assertFalse("没有对象才带「不猜」后缀", report.contains("本会话未尝试装载，不猜"))
    }

    // ---------------------------------------------------------------
    // 16. LOADED：照结论写「装载成功」，不复述盘面裁决
    // ---------------------------------------------------------------

    @Test
    fun `装载成功照本会话结论写`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                dexLoaderGate = true,
                codeFace = faceWith(
                    codeRow("pkg.a"),
                    loadReport = loadReportOf(carrierRecord("pkg.a", CodeCarrierOutcome.LOADED)),
                ),
            ),
        )
        assertTrue(report.contains("  最近一次装载结论：装载成功"))
    }

    // ---------------------------------------------------------------
    // 17. ENGINE_TOO_OLD：版本号取记录里的 EngineSurfaceGap，不重算
    // ---------------------------------------------------------------

    @Test
    fun `引擎太旧按记录缺口复读双版本号`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                dexLoaderGate = true,
                codeFace = faceWith(
                    codeRow("pkg.a"),
                    loadReport = loadReportOf(
                        carrierRecord(
                            "pkg.a",
                            CodeCarrierOutcome.ENGINE_TOO_OLD,
                            rejectReason = "compiledSurface 3 > 2",
                            gap = EngineSurfaceGap(required = 3, current = 2),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(report.contains("  最近一次装载结论：引擎太旧：要求 v3、本机 v2"))
        assertFalse("有缺口读数时不退回原因句", report.contains("  最近一次装载结论：compiledSurface 3 > 2"))
    }

    // ---------------------------------------------------------------
    // 18. REJECTED：原因句原样照抄（含标点 / 英文），不重拼不翻译
    // ---------------------------------------------------------------

    @Test
    fun `拒载原因原样照抄不重拼`() {
        val reason = "ABI 闸拒载：compiledSurface=3 > engineApiVersion=2 (ModDexGate.verdict)"
        val report = buildModDiagnosticReport(
            baseInput().copy(
                dexLoaderGate = true,
                codeFace = faceWith(
                    codeRow("pkg.a"),
                    loadReport = loadReportOf(
                        carrierRecord("pkg.a", CodeCarrierOutcome.REJECTED, rejectReason = reason),
                    ),
                ),
            ),
        )
        assertTrue("原因句必须逐字照抄（含标点与大小写）", report.contains("  最近一次装载结论：$reason"))
    }

    // ---------------------------------------------------------------
    // 19. DISABLED：总闸关结局 = 未尝试，不是拒载
    // ---------------------------------------------------------------

    @Test
    fun `总闸关结局写未尝试装载`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                dexLoaderGate = true,
                codeFace = faceWith(
                    codeRow("pkg.a"),
                    loadReport = loadReportOf(carrierRecord("pkg.a", CodeCarrierOutcome.DISABLED)),
                ),
            ),
        )
        assertTrue(report.contains("  最近一次装载结论：未尝试装载（第三方代码包装载总开关未开启）"))
        assertFalse("总闸关不许写成拒载", report.contains("拒载"))
    }

    // ---------------------------------------------------------------
    // 20. 磁盘身份与运行期身份不同：先磁盘 join，再按运行期身份兜底
    // ---------------------------------------------------------------

    @Test
    fun `磁盘身份对不上时按运行期身份兜底 join`() {
        val report = buildModDiagnosticReport(
            baseInput().copy(
                dexLoaderGate = true,
                codeFace = faceWith(
                    codeRow("pkg.new", identity = CodeIdSidecar.Bound("pkg.runtime")),
                    loadReport = loadReportOf(
                        carrierRecord("pkg.old", CodeCarrierOutcome.LOADED, runtimeId = "pkg.runtime"),
                    ),
                ),
            ),
        )
        assertTrue(report.contains("  运行期身份：pkg.runtime（身份侧车声明）"))
        assertTrue("磁盘身份换过代时不许误报未装载", report.contains("  最近一次装载结论：装载成功"))
    }

    // ---------------------------------------------------------------
    // 夹具（全部走「零参基线 + copy」，避开 detekt LongParameterList 的 6 参阈值）
    // ---------------------------------------------------------------

    /** 零参基线：引擎版本固定、运行时总闸开、代码包总闸关、空清单。 */
    private fun baseInput(): ModDiagnosticReportInput = ModDiagnosticReportInput(
        engineVersion = "0.2.47",
        runtimeGate = true,
        dexLoaderGate = false,
        scan = ModManageScan.Empty,
    )

    private fun scanOf(vararg mods: InstalledMod): ModManageScan = ModManageScan(mods.toList(), emptyList())

    /** 合成「代码包装载面」：只给行与结论对象，不碰盘（行语义由 CodeFaceTest 真盘覆盖）。 */
    private fun faceWith(vararg rows: ModCodeFaceRow, loadReport: ModCodeLoadReport?): ModCodeFace =
        ModCodeFace(
            dexLoaderEnabled = true,
            rows = rows.toList(),
            readFailed = false,
            loadReport = loadReport,
        )

    /** 单行盘面读数：默认单版本、无侧车、未信任、无盘面裁决。 */
    private fun codeRow(
        diskId: String,
        identity: CodeIdSidecar = CodeIdSidecar.Absent,
    ): ModCodeFaceRow = ModCodeFaceRow(
        diskPackageId = diskId,
        identity = identity,
        versions = 1,
        bytes = ONE_KIB.toLong(),
        trust = ModCodeTrustState.UNTRUSTED,
        hint = null,
    )

    private fun loadReportOf(vararg records: CodeCarrierRecord): ModCodeLoadReport =
        ModCodeLoadReport(records.toList())

    /** 单条装载结论：默认无运行期身份 / 无原因句 / 无版本缺口。 */
    private fun carrierRecord(
        diskId: String,
        outcome: CodeCarrierOutcome,
        runtimeId: String? = null,
        rejectReason: String? = null,
        gap: EngineSurfaceGap? = null,
    ): CodeCarrierRecord = CodeCarrierRecord(
        diskPackageId = diskId,
        runtimePackageId = runtimeId,
        outcome = outcome,
        rejectReason = rejectReason,
        engineTooOld = gap,
    )

    private fun overview(row: ModCodeRow, dexLoaderEnabled: Boolean): ModCodeOverview =
        ModCodeOverview(dexLoaderEnabled = dexLoaderEnabled, rows = listOf(row), readFailed = false)

    /** 只带「列表可见字段」的条目；作者 / 说明 / 设置项等敏感字段由用例自己 `copy` 补。 */
    private fun mod(
        pkg: String,
        name: String = "",
        version: String = "",
        source: ModSource = ModSource.BUILTIN,
        enabled: Boolean = true,
    ): InstalledMod = InstalledMod(
        packageId = pkg,
        displayName = name,
        version = version,
        author = "",
        description = "",
        source = source,
        enabled = enabled,
    )

    private companion object {
        const val SENTINEL_AUTHOR: String = "SENTINEL_AUTHOR_VALUE"
        const val SENTINEL_DESCRIPTION: String = "SENTINEL_DESCRIPTION_VALUE"
        const val SENTINEL_SETTING_VALUE: String = "SENTINEL_SETTING_VALUE"
        const val ONE_KIB: Int = 1024
    }
}
