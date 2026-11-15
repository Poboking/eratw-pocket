package com.eratw.port.android

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigPageLabels
import com.eratw.port.core.config.ConfigSpecTable
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页三语表的完整性钉子。
 *
 * 编译器只能保证「每种语言都给了值」，保证不了「同一条文案的占位符三语一致」
 * （`{0}` / `{1}` 漏一个，运行时会留下半句 `{1}`）。这里用反射逐字段比对，
 * 再钉住几条有事实来源的映射：存档判读理由（日文取原版 `Lang.cs:1486/1490/1494`）
 * 与条目名的三路选择。
 */
class SettingsStringsTest {

    private val placeholder = Regex("\\{\\d+}")

    private fun fields(): List<java.lang.reflect.Field> =
        SettingsStrings::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && it.type == java.lang.String::class.java }
            .onEach { it.isAccessible = true }

    private fun value(field: java.lang.reflect.Field, language: SettingsLanguage): String =
        field.get(SettingsStrings.of(language)) as String

    // -----------------------------------------------------------------
    // 三语完整性
    // -----------------------------------------------------------------

    @Test
    fun `每条文案三种语言都非空且占位符一致`() {
        val all = fields()
        assertTrue("只反射到 ${all.size} 个字段，表结构变了？", all.size > 100)
        for (field in all) {
            val holders = SettingsLanguage.entries.map { lang ->
                val text = value(field, lang)
                assertTrue("${lang.name}.${field.name} 是空串", text.isNotEmpty())
                placeholder.findAll(text).map { it.value }.toSortedSet()
            }
            assertEquals(
                "${field.name} 的占位符三语不一致：$holders",
                holders[0],
                holders[1],
            )
            assertEquals(
                "${field.name} 的占位符三语不一致：$holders",
                holders[0],
                holders[2],
            )
        }
    }

    @Test
    fun `缺省语言是简体中文且不认识的值回落缺省`() {
        assertEquals(SettingsLanguage.ZH, SettingsLanguage.DEFAULT)
        assertEquals(SettingsLanguage.ZH, SettingsLanguage.fromStored(null))
        assertEquals(SettingsLanguage.ZH, SettingsLanguage.fromStored("xx"))
        assertEquals(SettingsLanguage.EN, SettingsLanguage.fromStored("en"))
        assertEquals(SettingsLanguage.JA, SettingsLanguage.fromStored("ja"))
    }

    // -----------------------------------------------------------------
    // 条目名：三种语言各走各的真实来源
    // -----------------------------------------------------------------

    @Test
    fun `条目名按语言取页面译文或配置键名`() {
        val key = ConfigKey.UseMouse
        val spec = ConfigSpecTable.specOf(key)!!
        val page = ConfigPageLabels.byKey(key)!!

        assertEquals(spec.enLabel, SettingsStrings.of(SettingsLanguage.EN).configKeyLabel(key, false))
        assertEquals(page.jaLabel, SettingsStrings.of(SettingsLanguage.JA).configKeyLabel(key, false))
        assertEquals(page.zhsLabel, SettingsStrings.of(SettingsLanguage.ZH).configKeyLabel(key, false))

        // 「显示原始配置键名」覆盖所有语言的条目名选择
        SettingsLanguage.entries.forEach { lang ->
            assertEquals(
                spec.enLabel,
                SettingsStrings.of(lang).configKeyLabel(key, englishKeyNames = true),
            )
        }
    }

    @Test
    fun `中文缺译时走 app 侧兜底表而不是日文配置键`() {
        // REPLACE 组没有设置页标签，原版 zhs 也没有译文；兜底表必须命中。
        assertEquals(
            "货币单位",
            SettingsStrings.of(SettingsLanguage.ZH).configKeyLabel(ConfigKey.MoneyLabel, false),
        )
        assertEquals(
            ConfigSpecTable.specOf(ConfigKey.MoneyLabel)!!.jpLabel,
            SettingsStrings.of(SettingsLanguage.JA).configKeyLabel(ConfigKey.MoneyLabel, false),
        )
    }

    // -----------------------------------------------------------------
    // 存档理由 / 槽位名
    // -----------------------------------------------------------------

    @Test
    fun `已知存档理由按语言本地化且未知理由原样保留`() {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val en = SettingsStrings.of(SettingsLanguage.EN)
        val ja = SettingsStrings.of(SettingsLanguage.JA)

        // 中文模式与改动前逐字一致（平台原话直接上屏）
        assertEquals("存档数据已损坏", zh.saveReason("存档数据已损坏"))
        assertEquals("The save data is corrupted", en.saveReason("存档数据已损坏"))
        assertEquals("セーブデータが壊れています", ja.saveReason("存档数据已损坏"))

        assertEquals(
            "異なるゲームのセーブデータです",
            ja.saveReason("试图读取的存档属于另一个游戏"),
        )
        assertEquals(
            "セーブデータのバーションが異なります",
            ja.saveReason("存档版本不同"),
        )
        assertEquals(
            "The file is over 8 MB and does not look like a save",
            en.saveReason("文件超过 8 MB，不像存档"),
        )
        assertEquals("空のファイルです", ja.saveReason("是空文件"))

        // MODGC2：侧车缺同槽本体 —— 导出跳过与导入拒收共用一句（映射源 = SaveImport 常量，
        // 两边不许各写一份字面量）。
        val unpaired = com.eratw.port.platform.save.SaveImport.REASON_SIDECAR_WITHOUT_BODY
        assertEquals(unpaired, zh.saveReason(unpaired))
        assertEquals("The matching .sav save is missing", en.saveReason(unpaired))
        assertEquals("同じスロットの .sav 本体がありません", ja.saveReason(unpaired))

        // 不认识的句子不改写（不掩盖平台/引擎的话）
        assertEquals("Mystery failure", en.saveReason("Mystery failure"))
        assertEquals("Mystery failure", ja.saveReason("Mystery failure"))
    }

    @Test
    fun `存档槽位名按语言翻译`() {
        val en = SettingsStrings.of(SettingsLanguage.EN)
        val ja = SettingsStrings.of(SettingsLanguage.JA)
        val zh = SettingsStrings.of(SettingsLanguage.ZH)

        assertEquals("Slot 7", en.slotLabel("存档位 7"))
        assertEquals("セーブ 7", ja.slotLabel("存档位 7"))
        assertEquals("Global save", en.slotLabel("全局存档"))
        assertEquals("グローバルセーブ", ja.slotLabel("全局存档"))
        assertEquals("存档位 0", zh.slotLabel("存档位 0"))
    }

    @Test
    fun `拒因明细按语言重述且未知句子原样保留`() {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val en = SettingsStrings.of(SettingsLanguage.EN)
        val ja = SettingsStrings.of(SettingsLanguage.JA)

        assertEquals("非法的值", zh.rejectDetail("不正な指定です"))
        assertEquals("Invalid value", en.rejectDetail("不正な指定です"))
        assertEquals("不正な指定です", ja.rejectDetail("不正な指定です"))

        // 原版 zhs 文件里的官方译文（lang.emuera-zhs.xml:215）
        assertEquals("包含非数字的文字", zh.rejectDetail("数字でない文字が含まれています"))
        // 带区间的自定文案：数字与区间原样搬运
        assertEquals("Value out of range (-5..20)", en.rejectDetail("設定値が範囲外です(-5..20)"))
        assertEquals("设置值超出范围（-5..20）", zh.rejectDetail("設定値が範囲外です(-5..20)"))
        // 不认识的句子不改写
        assertEquals("Unhandled core message", en.rejectDetail("Unhandled core message"))
    }

    @Test
    fun `参数里的花括号不会被当成占位符展开`() {
        // 文件名可能含有 {n}；单趟替换必须原样保留，不能再去顶别的位置。
        assertEquals(
            "Exported 2 saves → a{1}.zip",
            SettingsStrings.of(SettingsLanguage.EN).exportDone(2, false, "a{1}.zip"),
        )
        assertEquals(
            "跳过 x：a{0}",
            SettingsStrings.of(SettingsLanguage.ZH).exportSkipped("x", "a{0}"),
        )
    }

    @Test
    fun `参数化句式没有留下未替换的占位符`() {
        val en = SettingsStrings.of(SettingsLanguage.EN)
        val ja = SettingsStrings.of(SettingsLanguage.JA)

        val samples = listOf(
            en.loadAborted(3, 9),
            en.restartHint("A, B, C", 7),
            en.exportDone(3, true, "saves.zip"),
            en.exportFailed("save02.sav", 1),
            en.overwriteLine("Slot 1", "old", "new", "save01.sav"),
            ja.loadAborted(3, 9),
            ja.restartHint("A、B、C", 7),
            ja.exportDone(3, true, "saves.zip"),
            ja.exportFailed("save02.sav", 1),
            ja.overwriteLine("セーブ 1", "旧", "新", "save01.sav"),
        )
        samples.forEach {
            assertFalse("留下了未替换的占位符：$it", placeholder.containsMatchIn(it))
        }
    }

    // -----------------------------------------------------------------
    // 关于卡：三处事实不随语言变，也不许在页面代码里另抄一份
    // -----------------------------------------------------------------

    @Test
    fun `关于卡的开发者与联系方式三语同值`() {
        SettingsLanguage.entries.forEach { lang ->
            val t = SettingsStrings.of(lang)
            assertEquals("${lang.name}.aboutDeveloperName", "Poboking", t.aboutDeveloperName)
            assertEquals("${lang.name}.aboutRepoUrl", "https://github.com/Poboking/eratw-pocket", t.aboutRepoUrl)
            assertEquals("${lang.name}.aboutGroupId", "1130136207", t.aboutGroupId)
        }
    }

    @Test
    fun `素材现状行区分未导入与有图无索引`() {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        assertEquals("背景图：未导入", zh.resourcePackBackground(0, 0))
        // 只有图没有 CSV：背景补丁仍不生效，读数必须点名索引缺失
        assertEquals("背景图：有 12 张图，但没有索引（CSV）", zh.resourcePackBackground(12, 0))
        assertEquals("背景图：12 张 · 索引 300 行", zh.resourcePackBackground(12, 300))
        assertEquals("BGM：未导入", zh.resourcePackBgm(0, 0))
        assertEquals("BGM：7 首 · 3 个地点", zh.resourcePackBgm(7, 3))
        assertEquals(
            "已导入 9 个文件（来源 2 个）；覆盖 1 个，跳过 4 项。",
            zh.resourcePackImported(9, 2, 1, 4),
        )
    }

    @Test
    fun `压缩包兼容卡三语各自独立且点名两个投放根`() {
        val all = listOf(
            SettingsStrings.of(SettingsLanguage.ZH),
            SettingsStrings.of(SettingsLanguage.EN),
            SettingsStrings.of(SettingsLanguage.JA),
        )

        // 上面那条完整性用例只钉「非空 + 占位符一致」：漏翻或三语互抄照样能过，这里补一条。
        assertEquals("压缩包卡说明三语重复", 3, all.map { it.resourcePackIntro }.distinct().size)
        all.forEach {
            // 投放根是三语共用的事实：换语言也照抄这两段路径。
            assertTrue(
                "说明句没提 resources/：${it.resourcePackIntro}",
                it.resourcePackIntro.contains("resources/"),
            )
            assertTrue(
                "说明句没提 sound/：${it.resourcePackIntro}",
                it.resourcePackIntro.contains("sound/"),
            )
            assertTrue(
                "兼容卡应点名 zip：${it.resourcePackIntro}",
                it.resourcePackIntro.contains("zip"),
            )
        }
        // 中文底稿要点名「只有手里有现成压缩包时才用」与官方包索引。
        val zh = all.first()
        assertTrue("没说只是兼容局面", zh.resourcePackIntro.contains("只有手里是现成"))
        assertTrue("没提官方背景图包索引", zh.resourcePackIntro.contains("BGI.csv"))
    }

    @Test
    fun `版本行同时带 versionName 与 versionCode`() {
        assertEquals(
            "当前版本 0.3.1（build 17）",
            SettingsStrings.of(SettingsLanguage.ZH).aboutVersion("0.3.1", 17),
        )
        assertEquals("Version 0.3.1 (build 17)", SettingsStrings.of(SettingsLanguage.EN).aboutVersion("0.3.1", 17))
        assertEquals("ビルド abc1234", SettingsStrings.of(SettingsLanguage.JA).aboutBuild("abc1234"))
    }

    // -----------------------------------------------------------------
    // 恢复默认设置（列表底部卡片）：三语文案齐备、确认按钮不是通用「确定」
    // -----------------------------------------------------------------

    @Test
    fun `恢复默认设置的文案三语齐备且各自独立`() {
        val zh = SettingsStrings.of(SettingsLanguage.ZH)
        val en = SettingsStrings.of(SettingsLanguage.EN)
        val ja = SettingsStrings.of(SettingsLanguage.JA)

        assertEquals("恢复默认设置", zh.restoreDefaultsTitle)
        assertEquals("Restore defaults", en.restoreDefaultsTitle)
        assertEquals("既定値に戻す", ja.restoreDefaultsTitle)

        // 三语不许互相抄：反射那条完整性用例只钉「非空 + 占位符一致」，漏翻译照样能过。
        val all = listOf(zh, en, ja)
        assertEquals("卡片标题三语重复", 3, all.map { it.restoreDefaultsTitle }.distinct().size)
        assertEquals("确认正文三语重复", 3, all.map { it.restoreDefaultsConfirmBody }.distinct().size)
        assertEquals("确认按钮三语重复", 3, all.map { it.restoreDefaultsConfirmOk }.distinct().size)

        SettingsLanguage.entries.forEach { lang ->
            val t = SettingsStrings.of(lang)
            listOf(
                t.restoreDefaultsTitle,
                t.restoreDefaultsHint,
                t.restoreDefaultsButton,
                t.restoreDefaultsConfirmTitle,
                t.restoreDefaultsConfirmBody,
                t.restoreDefaultsConfirmOk,
                t.restoreDefaultsDone,
                t.restoreDefaultsPinnedFormat,
                t.restoreDefaultsWriteFailed,
            ).forEach { assertTrue("${lang.name} 的恢复默认文案为空", it.isNotEmpty()) }

            val pinned = t.restoreDefaultsDonePinned(2)
            assertTrue("${lang.name} 的钉住提示要带成功前缀", pinned.startsWith(t.restoreDefaultsDone))
            assertTrue("${lang.name} 的钉住提示漏了项目数", pinned.contains("2"))
            assertFalse("${lang.name} 的钉住提示有槽位残留", pinned.contains("{0}"))
        }
    }

    /**
     * ECHO-INPUT 开关（2026-11-06 新增的两条文案）：三语都得给值、都不许互相抄，
     * 且提示要点明「默认关 / 重开一局」这两个玩家会踩的口径。
     */
    @Test
    fun `键入回显开关文案三语齐备且互不重复`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        all.forEach { t ->
            assertTrue("${t.lang} 的标题为空", t.echoInputTitle.isNotEmpty())
            assertTrue("${t.lang} 的提示为空", t.echoInputHint.isNotEmpty())
        }
        assertEquals("标题三语重复", 3, all.map { it.echoInputTitle }.distinct().size)
        assertEquals("提示三语重复", 3, all.map { it.echoInputHint }.distinct().size)
        assertEquals("回显所选项编号", all[0].echoInputTitle)
        assertEquals("Echo the selected number", all[1].echoInputTitle)
        assertEquals("選択した番号を表示する", all[2].echoInputTitle)
    }

    /**
     * PRAY-BUSY 开关（2026-11 新增的两条文案）的形状闸：三语都给值、互不互抄，
     * 且中文底稿点名「少女祈祷中」与 1 秒阈值（玩家得知道它什么时候出现）。
     */
    @Test
    fun `长页忙碌提示文案三语齐备且互不重复`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        all.forEach { t ->
            assertTrue("${t.lang} 的标题为空", t.busyIndicatorTitle.isNotEmpty())
            assertTrue("${t.lang} 的提示为空", t.busyIndicatorHint.isNotEmpty())
        }
        assertEquals("标题三语重复", 3, all.map { it.busyIndicatorTitle }.distinct().size)
        assertEquals("提示三语重复", 3, all.map { it.busyIndicatorHint }.distinct().size)
        assertEquals("长页忙碌提示", all[0].busyIndicatorTitle)
        assertEquals("Long-page busy hint", all[1].busyIndicatorTitle)
        assertEquals("長い処理中の表示", all[2].busyIndicatorTitle)
        assertTrue("中文提示要点名「少女祈祷中」", all[0].busyIndicatorHint.contains("少女祈祷中"))
        assertTrue("中文提示要点明 1 秒阈值", all[0].busyIndicatorHint.contains("1 秒"))
    }

    /**
     * 「画面左右留白居中」一族（2026-11-14 圆屏适配新增的 6 条文案）的形状闸：
     * 三语都给值、标题/提示互不互抄，四个档名逐字钉住；中文底稿点名
     * 「黑边 / 圆屏 / 默认关 / 重开一局」这四个玩家会踩的口径。
     */
    @Test
    fun `画面左右留白居中文案三语齐备且互不重复`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        all.forEach { t ->
            listOf(
                t.contentMarginLabel,
                t.contentMarginHint,
                t.contentMarginOff,
                t.contentMarginSmall,
                t.contentMarginMedium,
                t.contentMarginLarge,
            ).forEach { entry ->
                assertTrue("${t.lang} 有条目为空", entry.isNotEmpty())
            }
        }
        assertEquals("标题三语重复", 3, all.map { it.contentMarginLabel }.distinct().size)
        assertEquals("提示三语重复", 3, all.map { it.contentMarginHint }.distinct().size)
        assertEquals("画面左右留白居中", all[0].contentMarginLabel)
        assertEquals("Screen side margins (center content)", all[1].contentMarginLabel)
        assertEquals("画面左右の余白中央寄せ", all[2].contentMarginLabel)
        assertEquals("关", all[0].contentMarginOff)
        assertEquals("Off", all[1].contentMarginOff)
        assertEquals("オフ", all[2].contentMarginOff)
        assertEquals("小", all[0].contentMarginSmall)
        assertEquals("Small", all[1].contentMarginSmall)
        assertEquals("中", all[0].contentMarginMedium)
        assertEquals("Medium", all[1].contentMarginMedium)
        assertEquals("大", all[0].contentMarginLarge)
        assertEquals("Large", all[1].contentMarginLarge)
        assertTrue("中文提示要点名「黑边」", all[0].contentMarginHint.contains("黑边"))
        assertTrue("中文提示要点名「圆屏」", all[0].contentMarginHint.contains("圆屏"))
        assertTrue("中文提示要点明默认关", all[0].contentMarginHint.contains("默认关"))
        assertTrue("中文提示要点明重开一局生效", all[0].contentMarginHint.contains("重开一局"))
    }

    /**
     * 右栏布局/过滤/置顶一族的文案闸（2026-11-11 RAIL-OPTIONS）：三档布局的档名与
     * 说明三语齐备互不互抄；两张新功能卡的参数化句式逐字钉住（计数行与编号兜底）。
     */
    @Test
    fun `右栏布局与过滤置顶文案三语齐备`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        all.forEach { t ->
            UiPrefs.RailLayoutMode.entries.forEach { mode ->
                assertTrue("${t.lang} 的 $mode 档名为空", t.railModeLabel(mode).isNotEmpty())
                assertTrue("${t.lang} 的 $mode 档说明为空", t.railModeHint(mode).isNotEmpty())
            }
            listOf(
                t.railFilterTitle, t.railFilterRuleLabel, t.railFilterRuleHint,
                t.railFilterHiddenHeader, t.railFilterRestore, t.railFilterClear, t.railFilterEmpty,
                t.railPinnedTitle, t.railPinnedUnpin, t.railPinnedClear, t.railPinnedEmpty,
                t.railPinnedHistoryHeader, t.railPinnedHistoryEmpty, t.railPinnedHint,
                t.commonCollapse,
                t.railLayoutLabel, t.railPerRowLabel,
            ).forEach { entry ->
                assertTrue("${t.lang} 有一条空文案", entry.isNotEmpty())
            }
        }
        assertEquals("布局档名三语重复", 3, all.map { it.railLayoutRowScrollLabel }.distinct().size)
        assertEquals("行内横滑", all[0].railLayoutRowScrollLabel)
        assertEquals("Row scroll", all[1].railLayoutRowScrollLabel)
        assertEquals("横スクロール", all[2].railLayoutRowScrollLabel)
        // 参数化句式逐字钉住（占位符一致性由反射闸管，这里钉中/英/日的读数形状）。
        assertEquals("已置顶 3/8", all[0].railPinnedCountLine(3, 8))
        assertEquals("3/8 pinned", all[1].railPinnedCountLine(3, 8))
        assertEquals("3/8 固定中", all[2].railPinnedCountLine(3, 8))
        assertEquals("编号 999", all[0].railPinnedUnknown("999"))
        assertEquals("No. 999", all[1].railPinnedUnknown("999"))
        assertEquals("番号 999", all[2].railPinnedUnknown("999"))
        assertEquals("展开全部（32）", all[0].commonShowAll(32))
        assertEquals("Show all (32)", all[1].commonShowAll(32))
        assertEquals("すべて表示（32）", all[2].commonShowAll(32))
        assertEquals("收起", all[0].commonCollapse)
        assertEquals("Collapse", all[1].commonCollapse)
        assertEquals("たたむ", all[2].commonCollapse)
    }

    /**
     * 快捷宏（2026-11-11 重做）：编辑器卡内文案三语齐备互不互抄；参数化句式逐字钉住。
     *
     * 这一版把「名字 + 指令集串」换成「步骤 + 连发次数 + 整组重复」，所以断言也从
     * 「玩家要懂反斜杠转义」变成「玩家只面对步骤与次数」——语法提示里不许再要求玩家手写转义。
     */
    @Test
    fun `快捷宏文案三语齐备且句式读数固定`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        all.forEach { t ->
            listOf(
                t.railQuickMacroTitle, t.railQuickMacroEmpty, t.railQuickMacroAdd,
                t.railQuickMacroEdit, t.railQuickMacroDelete, t.railQuickMacroClear,
                t.railQuickMacroStepsHeader, t.railQuickMacroStepsEmpty, t.railQuickMacroValueLabel,
                t.railQuickMacroTimesLabel, t.railQuickMacroAddStep, t.railQuickMacroRepeatLabel,
                t.railQuickMacroFromRecent, t.railQuickMacroNoRecent,
                t.railQuickMacroTapHint, t.railQuickMacroManualToggle, t.railQuickMacroPreviewHeader,
                t.railQuickMacroSave,
                t.railQuickMacroCancel, t.railQuickMacroHint,
            ).forEach { entry ->
                assertTrue("${t.lang} 有一条空的快捷宏文案", entry.isNotEmpty())
            }
        }
        assertEquals("快捷宏标题三语重复", 3, all.map { it.railQuickMacroTitle }.distinct().size)
        assertEquals("已配置 3/6", all[0].railQuickMacroCountLine(3, 6))
        assertEquals("3/6 configured", all[1].railQuickMacroCountLine(3, 6))
        assertEquals("3/6 件設定済み", all[2].railQuickMacroCountLine(3, 6))
        assertEquals("这条宏会提交 12 段输入", all[0].railQuickMacroSegments(12))
        assertEquals("This macro submits 12 inputs", all[1].railQuickMacroSegments(12))
        assertEquals("最多 6 个快捷宏，先删一个再新增。", all[0].railQuickMacroFull(6))
        assertEquals("将这样跑：会话×5", all[0].railQuickMacroPreview("会话×5"))
        assertEquals("Runs as: 会话×5", all[1].railQuickMacroPreview("会话×5"))
        assertEquals("このように実行：会話×5", all[2].railQuickMacroPreview("会話×5"))
    }

    /**
     * 玩家排序配置（RT2 L2）新增的三条文案：按 UpdateNotifyCopyTest 的「条目点名清单计数」
     * 形状闸钉住（AGENTS §6.2：只改表不改闸 = 验收红）。反射完整性用例只钉「非空 + 占位符」，
     * 这里逐条点名、钉三语互不重复，且中文说明必须点明「优先级」与依赖造成的调整
     * （契约 §1.2 产品裁定，避免玩家把配置误读成对最终序的逐位指定）。
     */
    @Test
    fun `Mod 装载优先级文案三语齐备且点明优先级语义`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        // 条目点名清单：本次新增 3 条，逐条列出（改名 / 漏档 / 只补一种语言都在这里红）。
        val keys: List<Pair<String, (SettingsStrings) -> String>> = listOf(
            "modPackageOrderTitle" to { s: SettingsStrings -> s.modPackageOrderTitle },
            "modPackageOrderHint" to { s: SettingsStrings -> s.modPackageOrderHint },
            "modPackageOrderEmpty" to { s: SettingsStrings -> s.modPackageOrderEmpty },
        )
        assertEquals("新增文案条目数变了（现有 3 条）", 3, keys.size)
        keys.forEach { (name, getter) ->
            val texts = all.map(getter)
            texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
            assertEquals("$name 三语重复", 3, texts.distinct().size)
        }
        assertEquals("Mod 装载优先级", all[0].modPackageOrderTitle)
        assertEquals("Mod load priority", all[1].modPackageOrderTitle)
        assertEquals("Mod 読み込み優先度", all[2].modPackageOrderTitle)
        // 产品裁定（契约 §1.2）：说明要点明「优先级」而非「完整顺序」，且点明依赖会改变实际序。
        assertTrue("中文说明没点明优先级", all[0].modPackageOrderHint.contains("优先级"))
        assertTrue("中文说明没点明不是完整顺序", all[0].modPackageOrderHint.contains("完整顺序"))
        assertTrue("中文说明没点明依赖改变装载序", all[0].modPackageOrderHint.contains("依赖"))
    }

    /**
     * 竖屏字号档一族（L29 PORTRAIT-SETTINGS 新增的 6 条文案）的形状闸：三语都给值、互不互抄；
     * 四档档名与 `UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT` **逐档对齐**（顺序即 50/62/75/90），
     * 坏值回落缺省 62 档的档名；中文提示点名「仅竖屏模式 / 重开一局」两个玩家会踩的口径。
     */
    @Test
    fun `竖屏字号档文案三语齐备且档名逐档对齐`() {
        val all = SettingsLanguage.entries.map { SettingsStrings.of(it) }
        // 条目点名清单：本次新增 6 条，逐条列出（改名 / 漏档 / 只补一种语言都在这里红）。
        val keys: List<Pair<String, (SettingsStrings) -> String>> = listOf(
            "portraitFontTitle" to { s: SettingsStrings -> s.portraitFontTitle },
            "portraitFontHint" to { s: SettingsStrings -> s.portraitFontHint },
            "portraitColExtraLargeLabel" to { s: SettingsStrings -> s.portraitColExtraLargeLabel },
            "portraitColLargeLabel" to { s: SettingsStrings -> s.portraitColLargeLabel },
            "portraitColStandardLabel" to { s: SettingsStrings -> s.portraitColStandardLabel },
            "portraitColSmallLabel" to { s: SettingsStrings -> s.portraitColSmallLabel },
        )
        assertEquals("新增文案条目数变了（现有 6 条）", 6, keys.size)
        keys.forEach { (name, getter) ->
            val texts = all.map(getter)
            texts.forEach { assertTrue("$name 有一档空文案", it.isNotEmpty()) }
            assertEquals("$name 三语重复", 3, texts.distinct().size)
        }
        assertEquals("竖屏字号", all[0].portraitFontTitle)
        assertEquals("Portrait text size", all[1].portraitFontTitle)
        assertEquals("縦画面の文字サイズ", all[2].portraitFontTitle)
        assertTrue("中文提示要点名仅竖屏模式生效", all[0].portraitFontHint.contains("仅竖屏模式"))
        assertTrue("中文提示要点明重开一局", all[0].portraitFontHint.contains("重开一局"))
        // 档位表是唯一权威：四档顺序 = 50/62/75/90，档名逐档对齐（表改了这里必须跟）。
        assertEquals("竖屏档位表变了（文案映射需同步）", 4, UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT.size)
        assertEquals(
            listOf("特大 · 约 50 列", "大 · 约 62 列（默认）", "标准 · 约 75 列", "小 · 约 90 列"),
            UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT.map { all[0].portraitFontColumnLabel(it.columns) },
        )
        // 坏值回落 62 档档名（与 UiPrefs.normalizeFontColumnsPortrait 的缺省回落同口径）。
        all.forEach { t ->
            assertEquals(
                "${t.lang} 的坏值没有回落 62 档档名",
                t.portraitFontColumnLabel(UiPrefs.DEFAULT_FONT_COLUMNS_PORTRAIT),
                t.portraitFontColumnLabel(136),
            )
        }
    }
}
