package com.eratw.port.android

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *「第三方代码包装了但不生效」的提示判据（task-179，task-187 补多版本残留态，task-195 补引擎太旧态）。
 *
 * 三块：
 * 1. **纯裁决**：六态矩阵 + 无代码零提示 + 优先级「多版本残留 > 身份损坏 > 引擎太旧 > 总闸关 > 未信任」——
 *    优先级不是文案排序，装载侧 [ModDexLoader.decide] 先判总闸再判信任，提示若反过来
 *    会把玩家引到错误的修复动作（先去点信任，结果信任后照样不生效）；多版本与身份损坏
 *    同属「只能重导」，多版本排更前是因为它 = 装载端永久拒载，是「升级后一直不生效」
 *    这条主诉的直接解释；引擎太旧排在总闸/信任前是因为它只有换新引擎能解，点信任没用
 *    （理由见 [ModCodeGateHintKind] KDoc）。
 * 2. **盘面装配**：判据与装载侧同源（产物表 / 信任标记 / 身份侧车 / 产物件数 / 最近装载结论），
 *    没有代码产物的包整行不出（普通口上包不许凭空多一行「代码」读数）。
 * 3. **两个展示面取词**：导入成功页（[ModImportCopy] 两槽句式，引擎太旧不上导入页）与设置页
 *    （[SettingsStrings] 六态状态词 + 既有「标签：值」句式），三语不许空、不许同文。
 */
class ModCodeGateHintTest {

    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        for (root in roots) {
            root.walkBottomUp().forEach { it.setWritable(true) }
            root.deleteRecursively()
        }
        roots.clear()
    }

    private fun tempDir(): File =
        Files.createTempDirectory("eratw-code-gate-hint").toFile().also { roots += it }

    /** 造一个「盘上有代码产物」的包目录（产物名必须是合法地址键形状）。 */
    private fun codeOnDisk(rt2: File, packageId: String) {
        val dir = File(File(rt2, MOD_CODE_DIR_NAME), packageId)
        dir.mkdirs()
        File(dir, "0123456789abcdef" + MOD_CODE_ARTIFACT_EXTENSION).writeBytes(ByteArray(8) { 1 })
    }

    /** 再放一件不同地址键的产物：模拟「升级后新旧版本并存」（装载端据此判多版本歧义）。 */
    private fun secondVersionOnDisk(rt2: File, packageId: String) {
        val dir = File(File(rt2, MOD_CODE_DIR_NAME), packageId)
        File(dir, "fedcba9876543210" + MOD_CODE_ARTIFACT_EXTENSION).writeBytes(ByteArray(8) { 2 })
    }

    private fun trust(rt2: File, packageId: String) {
        File(rt2, packageId + ModDexGate.TRUST_MARKER_SUFFIX).writeText("1")
    }

    private fun importCopies(): List<ModImportCopy> =
        listOf(ModImportCopy.ZH, ModImportCopy.EN, ModImportCopy.JA)

    private fun settingsTexts(): List<SettingsStrings> =
        listOf(SettingsLanguage.ZH, SettingsLanguage.EN, SettingsLanguage.JA).map(SettingsStrings::of)

    private fun gateHint(
        gate: Boolean,
        trusted: Boolean,
        corrupt: Boolean,
        multiple: Boolean = false,
        hasCode: Boolean = true,
    ): ModCodeGateHint? = modCodeGateHint(
        packageId = PKG,
        facts = ModCodeGateFacts(
            hasArtifacts = hasCode,
            multipleVersions = multiple,
            identityCorrupt = corrupt,
            runtimeGateEnabled = gate,
            trusted = trusted,
        ),
    )

    /** 同上，但盘面已读出「包要求的引擎面比本机高 1」（该态只由装载结论产生）。 */
    private fun engineTooOldHint(
        gate: Boolean,
        trusted: Boolean,
        corrupt: Boolean,
        multiple: Boolean = false,
    ): ModCodeGateHint? = modCodeGateHint(
        packageId = PKG,
        facts = ModCodeGateFacts(
            hasArtifacts = true,
            multipleVersions = multiple,
            identityCorrupt = corrupt,
            runtimeGateEnabled = gate,
            trusted = trusted,
            engineTooOld = engineTooOldGap(),
        ),
    )

    /** 装载侧结论的展示投影：`ENGINE_TOO_OLD` + 版本缺口（`required` 比本机高 1）。 */
    private fun engineTooOldGap(): EngineSurfaceGap =
        EngineSurfaceGap(required = MOD_ENGINE_API_VERSION + 1, current = MOD_ENGINE_API_VERSION)

    /** 造一份「该包因引擎太旧被拒载」的装载结论快照（装配期发布的那类）。 */
    private fun engineTooOldReport(): ModCodeLoadReport = ModCodeLoadReport(
        listOf(
            CodeCarrierRecord(
                diskPackageId = PKG,
                runtimePackageId = null,
                outcome = CodeCarrierOutcome.ENGINE_TOO_OLD,
                rejectReason = "ABI 闸原文",
                engineTooOld = engineTooOldGap(),
            ),
        ),
    )

    // ------------------------------------------------------------------
    // 1. 纯裁决矩阵
    // ------------------------------------------------------------------

    @Test
    fun `没有代码产物时任何开关组合都零提示`() {
        for (gate in listOf(true, false)) {
            for (trusted in listOf(true, false)) {
                for (corrupt in listOf(true, false)) {
                    assertNull(
                        "无代码产物不该出提示（gate=$gate trusted=$trusted corrupt=$corrupt）",
                        gateHint(gate = gate, trusted = trusted, corrupt = corrupt, hasCode = false),
                    )
                }
            }
        }
    }

    @Test
    fun `代码齐全且总闸开已信任身份完好——已生效零提示`() {
        assertNull(
            "总闸开 + 已信任 + 身份完好 = 下次装载就运行，不该有提示",
            gateHint(gate = true, trusted = true, corrupt = false),
        )
    }

    @Test
    fun `总闸关——报总闸关（未信任也先报总闸关）`() {
        assertEquals(ModCodeGateHintKind.GATE_OFF, gateHint(gate = false, trusted = true, corrupt = false)?.kind)
        assertEquals(ModCodeGateHintKind.GATE_OFF, gateHint(gate = false, trusted = false, corrupt = false)?.kind)
    }

    @Test
    fun `总闸开但未信任——报未信任`() {
        assertEquals(
            ModCodeGateHintKind.UNTRUSTED,
            gateHint(gate = true, trusted = false, corrupt = false)?.kind,
        )
    }

    @Test
    fun `优先级——身份损坏盖过总闸关与未信任（重导才修得回来）`() {
        assertEquals(
            ModCodeGateHintKind.CODE_ID_CORRUPT,
            gateHint(gate = false, trusted = false, corrupt = true)?.kind,
        )
        assertEquals(
            ModCodeGateHintKind.CODE_ID_CORRUPT,
            gateHint(gate = true, trusted = true, corrupt = true)?.kind,
        )
    }

    @Test
    fun `优先级——总闸关盖过未信任（先修最粗的那道闸）`() {
        assertEquals(
            ModCodeGateHintKind.GATE_OFF,
            gateHint(gate = false, trusted = false, corrupt = false)?.kind,
        )
    }

    @Test
    fun `优先级——多版本残留盖过身份损坏与总闸关（都是先清盘再谈生效）`() {
        assertEquals(
            "多版本 + 身份损坏：两者都只能重导，但多版本残留是装载端直接拒载的确定事实",
            ModCodeGateHintKind.MULTIPLE_VERSIONS,
            gateHint(gate = false, trusted = false, corrupt = true, multiple = true)?.kind,
        )
        assertEquals(
            ModCodeGateHintKind.MULTIPLE_VERSIONS,
            gateHint(gate = false, trusted = true, corrupt = false, multiple = true)?.kind,
        )
        assertEquals(
            ModCodeGateHintKind.MULTIPLE_VERSIONS,
            gateHint(gate = true, trusted = false, corrupt = false, multiple = true)?.kind,
        )
    }

    @Test
    fun `多版本残留——总闸开已信任身份完好也照报（装载端拒绝猜版本）`() {
        assertEquals(
            ModCodeGateHintKind.MULTIPLE_VERSIONS,
            gateHint(gate = true, trusted = true, corrupt = false, multiple = true)?.kind,
        )
    }

    @Test
    fun `无代码产物时多版本标志也不出提示`() {
        assertNull(
            "没有代码产物就不该有「多版本」提示（多版本判据只在有产物时成立）",
            gateHint(gate = true, trusted = true, corrupt = false, multiple = true, hasCode = false),
        )
    }

    @Test
    fun `引擎太旧——报需要更新引擎并原样带上版本缺口`() {
        val hint = engineTooOldHint(gate = true, trusted = true, corrupt = false)
        assertEquals(ModCodeGateHintKind.ENGINE_TOO_OLD, hint?.kind)
        assertEquals("版本缺口必须原样带进提示（供设置页读数行显示两侧版本）", engineTooOldGap(), hint?.engineGap)
    }

    @Test
    fun `优先级——身份损坏盖过引擎太旧（重导才修得回来）`() {
        assertEquals(
            ModCodeGateHintKind.CODE_ID_CORRUPT,
            engineTooOldHint(gate = true, trusted = true, corrupt = true)?.kind,
        )
    }

    @Test
    fun `优先级——引擎太旧盖过总闸关与未信任（先更新应用，点信任没用）`() {
        assertEquals(
            ModCodeGateHintKind.ENGINE_TOO_OLD,
            engineTooOldHint(gate = false, trusted = false, corrupt = false)?.kind,
        )
        assertEquals(
            ModCodeGateHintKind.ENGINE_TOO_OLD,
            engineTooOldHint(gate = true, trusted = false, corrupt = false)?.kind,
        )
    }

    @Test
    fun `优先级——多版本残留盖过引擎太旧（先清盘再谈更新引擎）`() {
        assertEquals(
            ModCodeGateHintKind.MULTIPLE_VERSIONS,
            engineTooOldHint(gate = true, trusted = true, corrupt = false, multiple = true)?.kind,
        )
    }

    @Test
    fun `非引擎太旧的提示不带版本缺口`() {
        assertNull(gateHint(gate = false, trusted = true, corrupt = false)?.engineGap)
        assertNull(gateHint(gate = true, trusted = false, corrupt = false)?.engineGap)
    }

    @Test
    fun `提示原样带上包名`() {
        assertEquals(PKG, gateHint(gate = false, trusted = true, corrupt = false)?.packageId)
    }

    // ------------------------------------------------------------------
    // 2. 盘面装配（判据与装载侧同源）
    // ------------------------------------------------------------------

    @Test
    fun `盘上没有代码产物——不出代码态（普通口上包零噪音）`() {
        assertNull(modCodePackState(tempDir(), PKG, dexLoaderEnabled = true))
    }

    @Test
    fun `盘上有代码产物但未信任——待信任`() {
        val rt2 = tempDir()
        codeOnDisk(rt2, PKG)
        val state = modCodePackState(rt2, PKG, dexLoaderEnabled = true)
        assertNotNull("有代码产物必须给出代码态", state)
        assertEquals(ModCodeGateHintKind.UNTRUSTED, state?.hint?.kind)
    }

    @Test
    fun `盘上有代码产物且已信任——总闸开已启用、总闸关报总闸关`() {
        val rt2 = tempDir()
        codeOnDisk(rt2, PKG)
        trust(rt2, PKG)
        assertNull("已信任 + 总闸开 = 已生效，不出提示", modCodePackState(rt2, PKG, true)?.hint)
        assertEquals(
            ModCodeGateHintKind.GATE_OFF,
            modCodePackState(rt2, PKG, dexLoaderEnabled = false)?.hint?.kind,
        )
    }

    @Test
    fun `盘面读不出引擎太旧——装载结论报太旧时出该态（反证：不传结论即已生效）`() {
        val rt2 = tempDir()
        codeOnDisk(rt2, PKG)
        trust(rt2, PKG)
        assertNull("盘面本身看不出 ABI 缺口：不传装载结论时仍是已生效", modCodePackState(rt2, PKG, true)?.hint)
        val state = modCodePackState(rt2, PKG, dexLoaderEnabled = true, loadReport = engineTooOldReport())
        assertEquals(ModCodeGateHintKind.ENGINE_TOO_OLD, state?.hint?.kind)
        assertEquals(engineTooOldGap(), state?.hint?.engineGap)
    }

    @Test
    fun `装载结论里别的包的缺口不串到本包`() {
        val rt2 = tempDir()
        codeOnDisk(rt2, PKG)
        trust(rt2, PKG)
        val other = ModCodeLoadReport(
            listOf(
                CodeCarrierRecord(
                    diskPackageId = "other.pkg",
                    runtimePackageId = null,
                    outcome = CodeCarrierOutcome.ENGINE_TOO_OLD,
                    engineTooOld = engineTooOldGap(),
                ),
            ),
        )
        assertNull("按磁盘包名 join，别的包的缺口不许串过来", modCodePackState(rt2, PKG, true, loadReport = other)?.hint)
    }

    @Test
    fun `盘上留有两份代码产物——报多版本残留，删剩一份后回到已生效`() {
        val rt2 = tempDir()
        codeOnDisk(rt2, PKG)
        secondVersionOnDisk(rt2, PKG)
        trust(rt2, PKG)
        assertEquals(
            "盘上多份产物 = 装载端按多版本歧义永久拒载，已信任 + 总闸开也必须报多版本",
            ModCodeGateHintKind.MULTIPLE_VERSIONS,
            modCodePackState(rt2, PKG, dexLoaderEnabled = true)?.hint?.kind,
        )
        assertEquals("删之前确实有两件", 2, ModCodeStore(rt2).list(PKG).size)
        ModCodeStore(rt2).list(PKG).first().file.delete()
        assertNull("清成一份且已信任 = 已生效，不该再有提示", modCodePackState(rt2, PKG, true)?.hint)
    }

    @Test
    fun `身份侧车损坏——报身份损坏（已信任也救不回来）`() {
        val rt2 = tempDir()
        codeOnDisk(rt2, PKG)
        trust(rt2, PKG)
        File(rt2, ModCodeIdentity.fileName(PKG)).writeText("这不是身份侧车")
        assertEquals(
            ModCodeGateHintKind.CODE_ID_CORRUPT,
            modCodePackState(rt2, PKG, dexLoaderEnabled = true)?.hint?.kind,
        )
    }

    @Test
    fun `包名形状非法——收敛成没有代码产物而不是崩`() {
        assertNull(modCodePackState(tempDir(), "../evil", dexLoaderEnabled = true))
    }

    // ------------------------------------------------------------------
    // 3. 两个展示面的取词
    // ------------------------------------------------------------------

    @Test
    fun `导入页三档提示句都灌进包名与原因且不留槽位`() {
        for (copy in importCopies()) {
            for (kind in ModCodeGateHintKind.entries) {
                if (kind == ModCodeGateHintKind.ENGINE_TOO_OLD) continue
                val text = modCodeGateHintText(copy, ModCodeGateHint(kind, PKG))
                assertNotNull("$kind 在 ${copy.lang} 档必须有提示句", text)
                assertTrue("提示句必须带包名：$text", text!!.contains(PKG))
                assertTrue("提示句不许残留槽位：$text", !text.contains("{"))
            }
        }
    }

    @Test
    fun `导入页不显示引擎太旧——导入面不判 ABI`() {
        for (copy in importCopies()) {
            assertNull(
                "导入收尾只读盘面、不判 ABI（${copy.lang}）：该态只由装载结论产生，导入页不该多一句",
                modCodeGateHintText(copy, ModCodeGateHint(ModCodeGateHintKind.ENGINE_TOO_OLD, PKG)),
            )
        }
    }

    @Test
    fun `导入页无代码产物与已生效时都不出提示句`() {
        for (copy in importCopies()) {
            assertNull(modCodeGateHintText(copy, null))
        }
    }

    @Test
    fun `导入页四个原因三档非空且互不相同`() {
        for (copy in importCopies()) {
            val values = listOf(
                copy.modCodeGateReasonGateOff,
                copy.modCodeGateReasonUntrusted,
                copy.modCodeGateReasonCorrupt,
                copy.modCodeGateReasonMultipleVersions,
            )
            for (value in values) {
                assertTrue("${copy.lang} 档原因不许空", value.isNotBlank())
                assertTrue("${copy.lang} 档原因不许残留槽位", !value.contains("{"))
            }
            assertEquals("四个原因在 ${copy.lang} 档必须互不相同", values.size, values.toSet().size)
        }
    }

    @Test
    fun `设置页六态状态词三档非空且互不相同`() {
        val hints = listOf(
            null,
            ModCodeGateHint(ModCodeGateHintKind.MULTIPLE_VERSIONS, PKG),
            ModCodeGateHint(ModCodeGateHintKind.GATE_OFF, PKG),
            ModCodeGateHint(ModCodeGateHintKind.UNTRUSTED, PKG),
            ModCodeGateHint(ModCodeGateHintKind.CODE_ID_CORRUPT, PKG),
            ModCodeGateHint(ModCodeGateHintKind.ENGINE_TOO_OLD, PKG, engineGap = engineTooOldGap()),
        )
        for (text in settingsTexts()) {
            val values = hints.map { modCodeStateLabel(text, it) }
            for (value in values) assertTrue("${text.lang} 档状态词不许空", value.isNotBlank())
            assertEquals("同一档六态必须互不相同（${text.lang}）", values.size, values.toSet().size)
        }
    }

    @Test
    fun `设置页引擎太旧态说明要指到更新应用`() {
        val text = SettingsStrings.of(SettingsLanguage.ZH)
        val line = modCodeStateLine(
            text,
            ModCodePackState(PKG, ModCodeGateHint(ModCodeGateHintKind.ENGINE_TOO_OLD, PKG, engineTooOldGap())),
        )
        assertTrue("读数行必须带标签：$line", line.contains(text.modManageCodeLabel))
        assertTrue("读数行必须带引擎太旧状态词：$line", line.contains(text.modCodeStateEngineTooOld))
        assertTrue("状态词要告诉玩家更新应用（而不是点信任）：$line", line.contains("更新"))
    }

    @Test
    fun `设置页读数行复用标签冒号值句式且问题态带状态词`() {
        val text = SettingsStrings.of(SettingsLanguage.ZH)
        val state = ModCodePackState(PKG, ModCodeGateHint(ModCodeGateHintKind.UNTRUSTED, PKG))
        val line = modCodeStateLine(text, state)
        assertTrue("读数行必须带标签：$line", line.contains(text.modManageCodeLabel))
        assertTrue("读数行必须带状态词：$line", line.contains(text.modCodeStateUntrusted))
        assertTrue("标签与状态词之间走既有「标签：值」句式", line.contains("："))
        assertEquals(
            "已生效态用启用词",
            text.modManageDetailLine(text.modManageCodeLabel, text.modCodeStateEnabled),
            modCodeStateLine(text, ModCodePackState(PKG, null)),
        )
    }

    private companion object {
        const val PKG: String = "kojo-test01"
    }
}
