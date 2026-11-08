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
}
