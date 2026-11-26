package com.eratw.port.android

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.SettingsModel
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台出厂缺省（CONFIG-OVERLAY 策略面）的钉子。
 *
 * 四件事必须钉死，否则「抬阈值」会变成悄悄改玩家配置：
 * 1. 覆盖值落在放宽后的 GUI 值域内且**不贴上限**（贴上限 = 玩家无处再调高，
 *    越界则设置页显示与生效分叉）；
 * 2. **只填空、不改写**：配置链文件里写过的键一律不覆盖（含玩家显式写 5000）；
 * 3. 「恢复默认设置」的回落值与平台出厂缺省**同源**（否则恢复一次就退回桌面口径）；
 * 4. 语料显式写着 15000 的键不会被覆盖层注入 —— 该值由构建期校准与安装期迁移处理，
 *    这里只钉「覆盖层不许假装自己改得动它」。
 */
class PortFactoryConfigTest {

    /**
     * 阈值口径（2026-11-12 主人裁定把出厂阈值从 180 s 提到 360 s）：出厂 360 s、
     * GUI 值域 0..600000、引擎缺省仍是官方 5000、且**缺省不贴夹取上限**。
     *
     * 最后一条是硬要求：缺省写成 600000（= 上限）会让设置页显示一个「和实际生效一致但
     * 玩家无处再调高」的数 —— 那不是可调区间，是一堵墙。
     */
    @Test
    fun `出厂缺省是 360 秒且落在放宽后的 spec 值域内且不贴上限`() {
        val spec = ConfigSpecTable.specOf(ConfigKey.InfiniteLoopAlertTime)
        assertNotNull("条目必须在表里", spec)
        val s = spec!!
        assertEquals("平台出厂缺省 = 6 分钟", 360_000, PortFactoryConfig.INFINITE_LOOP_ALERT_MS)
        val range = s.guiIntRange
        assertNotNull("该条目必须是 GUI 数值条目（官方 numericUpDown 有上下限）", range)
        assertEquals("GUI 值域 = 0..600000（官方 0..100000 是桌面标定）", 0..600_000, range)
        assertTrue(
            "平台出厂缺省越界会被夹掉：${PortFactoryConfig.INFINITE_LOOP_ALERT_MS} 不在 $range",
            PortFactoryConfig.INFINITE_LOOP_ALERT_MS in range!!,
        )
        assertTrue(
            "0 = 关闭的开关位必须仍在值域内（skia Process.ScriptProc.cs:35、1.824 Process.cs:1012 的 Config.InfiniteLoopAlertTime > 0）",
            0 in range,
        )
        assertTrue(
            "缺省不得贴夹取上限（否则玩家想再调高无处可去）",
            PortFactoryConfig.INFINITE_LOOP_ALERT_MS < range.last,
        )
        assertEquals("引擎缺省保持桌面口径不改", ConfigValue.IntValue(5000), s.defaultValue)
        assertTrue(
            "引擎缺省也不得贴上限",
            (s.defaultValue as ConfigValue.IntValue).value < range.last,
        )
    }

    /**
     * **设置页显示值 == 实际生效值**（2026-11-10 明示的钉子）。
     *
     * 三处必须逐字对上：
     * 1. `ConfigSpec.guiIntRange` 是设置页数值框的输入区间 —— 平台缺省落在里面，
     *    所以 UI 不会把它夹成另一个数（解析期本来也不夹，见 `ConfigSpec.guiIntRange` 的 KDoc）；
     * 2. 「恢复默认设置」写进 `SettingsModel` 的实际值 == [PortFactoryConfig] 的出厂缺省；
     * 3. 写进去的值再被设置页读出来（`SettingsModel.store.itemOf(...).int()`）仍是同一个数。
     */
    @Test
    fun `设置页显示值与实际生效值同源且都不被夹`() {
        val model = SettingsModel()
        FactoryConfigDefaults.apply(model, emptyMap())

        val shown = model.store.itemOf(ConfigKey.InfiniteLoopAlertTime).int()
        assertEquals(
            "恢复默认后的实际值必须等于平台出厂缺省",
            PortFactoryConfig.INFINITE_LOOP_ALERT_MS,
            shown,
        )
        val range = ConfigSpecTable.specOf(ConfigKey.InfiniteLoopAlertTime)!!.guiIntRange!!
        assertEquals(
            "设置页会显示的数（未被夹）必须就是生效值",
            shown,
            shown.coerceIn(range.first, range.last),
        )
        assertEquals("6 分钟 = 360000 ms，逐字钉死", 360_000, shown)
    }

    @Test
    fun `配置链文件没写过的键才注入覆盖`() {
        val root = Files.createTempDirectory("portcfg-empty")
        try {
            // 真实的 4.980 语料 `emuera.config` 没有 PRINTC 两项之外的这个键吗？有 —— 见下一个用例。
            // 这条只造一份「确实没写过」的最小配置，验证空档位的注入路径本身。
            Files.createDirectories(root.resolve("CSV"))
            Files.write(
                root.resolve("emuera.config"),
                "ITEMS PER LINE FOR PRINTC:5\nNUMBER OF ITEM CHARACTERS FOR PRINTC:25\n".toByteArray(Charsets.UTF_8),
            )
            val overlay = PortFactoryConfig.overlayFor(root)
            assertEquals(
                "没写过 ⇒ 注入平台出厂缺省",
                ConfigValue.IntValue(PortFactoryConfig.INFINITE_LOOP_ALERT_MS),
                overlay[ConfigKey.InfiniteLoopAlertTime],
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * 根因钉（2026-11-12）：4.980 语料 `emuera.config:31` 显式写着 15000。
     * 「只填空、不改写」的覆盖层因此永远注入不了出厂值 —— 修法不在这一层，
     * 而是构建期把随包资产校到 360000、安装期把旧盘上的 15000 一次性迁移掉。
     * 本用例钉「覆盖层仍然不许覆盖写过的键」，防止有人用「反正要改」为由把它改成强制注入。
     */
    @Test
    fun `真实语料形状的 15000 不会被覆盖层强行改写`() {
        val root = Files.createTempDirectory("portcfg-corpus")
        try {
            Files.createDirectories(root.resolve("CSV"))
            Files.write(
                root.resolve("emuera.config"),
                "\uFEFF; 4.980 语料随包配置\nMILLISECONDS FOR INFINITE LOOP WARNING:15000\n".toByteArray(Charsets.UTF_8),
            )
            assertFalse(
                "语料写过的键以语料为准；改值走构建期校准/安装期迁移，不归覆盖层管",
                PortFactoryConfig.overlayFor(root).containsKey(ConfigKey.InfiniteLoopAlertTime),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `玩家显式写过的键一律不覆盖`() {
        val root = Files.createTempDirectory("portcfg-written")
        try {
            // 玩家显式写 5000（正好等于引擎缺省）也必须尊重 —— 判据是有没有写过，不是值相不相等。
            Files.write(root.resolve("emuera.config"), "MILLISECONDS FOR INFINITE LOOP WARNING:5000\n".toByteArray(Charsets.UTF_8))
            assertFalse(
                "写过的键不得覆盖",
                PortFactoryConfig.overlayFor(root).containsKey(ConfigKey.InfiniteLoopAlertTime),
            )
            // 固定档（CSV/_fixed.config）同样算「写过」；真实语料目录名是大写 CSV。
            Files.createDirectories(root.resolve("CSV"))
            Files.deleteIfExists(root.resolve("emuera.config"))
            Files.write(
                root.resolve("CSV/_fixed.config"),
                "; 注释行不算\nMILLISECONDS FOR INFINITE LOOP WARNING:15000\n".toByteArray(Charsets.UTF_8),
            )
            assertFalse(PortFactoryConfig.overlayFor(root).containsKey(ConfigKey.InfiniteLoopAlertTime))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * 真实语料目录是大写 `CSV`，而官方字面量是 `"csv"`（`Program.cs:207`）。Android 文件系统
     * 区分大小写：写死 `CsvDir/` 会让两层 CSV 配置永远判「不存在」（2026-11-12 发现）。
     */
    @Test
    fun `大写 CSV 目录下的默认档与固定档都能被识别`() {
        val rootDefault = Files.createTempDirectory("portcfg-csv-default")
        try {
            Files.createDirectories(rootDefault.resolve("CSV"))
            Files.write(
                rootDefault.resolve("CSV/_default.config"),
                "MILLISECONDS FOR INFINITE LOOP WARNING:15000\n".toByteArray(Charsets.UTF_8),
            )
            assertFalse(
                "CSV/_default.config 写过的键同样算写过",
                PortFactoryConfig.overlayFor(rootDefault).containsKey(ConfigKey.InfiniteLoopAlertTime),
            )
        } finally {
            rootDefault.toFile().deleteRecursively()
        }

        val rootFixed = Files.createTempDirectory("portcfg-csv-fixed")
        try {
            Files.createDirectories(rootFixed.resolve("CSV"))
            Files.write(
                rootFixed.resolve("CSV/fixed.config"),
                "MILLISECONDS FOR INFINITE LOOP WARNING:15000\n".toByteArray(Charsets.UTF_8),
            )
            assertFalse(
                "CSV/fixed.config（无下划线回落档）同样算写过",
                PortFactoryConfig.overlayFor(rootFixed).containsKey(ConfigKey.InfiniteLoopAlertTime),
            )
        } finally {
            rootFixed.toFile().deleteRecursively()
        }
    }

    @Test
    fun `玩家用日文标签写过同样不得覆盖`() {
        val root = Files.createTempDirectory("portcfg-jp")
        try {
            // 写盘侧 `EmueraConfigWriter.writeLine(item, english)` 两种标签都能产出；
            // 官方键匹配（`ConfigSpec.matchesUpperKey`，`ConfigData.cs:262-284` 比 Name/Text/EngText）
            // 也同时认日文标签。只查英文标签会把这类配置当成「没写过」而覆盖掉。
            val jp = ConfigSpecTable.specOf(ConfigKey.InfiniteLoopAlertTime)!!.jpLabel
            Files.write(
                root.resolve("emuera.config"),
                "$jp:18000\n".toByteArray(Charsets.UTF_8),
            )
            assertFalse(
                "日文标签写过的键同样不得覆盖",
                PortFactoryConfig.overlayFor(root).containsKey(ConfigKey.InfiniteLoopAlertTime),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `键名切分忽略注释与空行并统一大写`() {
        val keys = HashSet<String>()
        PortFactoryConfig.scanKeys(
            ";MILLISECONDS FOR INFINITE LOOP WARNING:5000\r\n\n" +
                "  items per line for printc:5\r\n" +
                "MILLISECONDS FOR INFINITE LOOP WARNING:15000  ; 行尾注释\n" +
                "没有冒号的行\n",
            keys,
        )
        assertEquals(setOf("ITEMS PER LINE FOR PRINTC", "MILLISECONDS FOR INFINITE LOOP WARNING"), keys)
    }

    /**
     * 语料 `emuera.config` 带 UTF-8 BOM（`EmueraConfigParser` 的首行解析会剥它）。
     * 键名扫描不剥会读成 `\uFEFFMILLISECONDS…`，于是「玩家写过」永远判不出来 ——
     * 覆盖层会把这个键从玩家手里抢走。
     */
    @Test
    fun `首行 BOM 不遮蔽键名`() {
        val keys = HashSet<String>()
        PortFactoryConfig.scanKeys(
            "\uFEFFMILLISECONDS FOR INFINITE LOOP WARNING:15000\n",
            keys,
        )
        assertEquals(setOf("MILLISECONDS FOR INFINITE LOOP WARNING"), keys)
    }

    /**
     * 老玩家盘上的历史值迁移规则（安装期由 `GameAssetInstaller` 执行）。
     *
     * 键名必须与 `ConfigSpec` 的标签逐字同源：迁移器按大写键名匹配，写错一个字母
     * 整条规则就静默不生效 —— 这类失败在屏上没有任何形状，只有这条用例能拦。
     * 两种写盘标签（英/日）都要覆盖，理由见 [PortFactoryConfig.CONFIG_VALUE_MIGRATIONS]。
     */
    @Test
    fun `一次性迁移规则把随包旧值 15000 改成 360000`() {
        val spec = ConfigSpecTable.specOf(ConfigKey.InfiniteLoopAlertTime)!!
        val byKey = PortFactoryConfig.CONFIG_VALUE_MIGRATIONS.associateBy { it.key }
        assertEquals(
            "键名不许重复（重复会让标记内容与执行次数说不清）",
            PortFactoryConfig.CONFIG_VALUE_MIGRATIONS.size,
            byKey.size,
        )
        val en = byKey[spec.upperEnLabel]
        assertNotNull("英文标签规则必须存在", en)
        assertEquals("迁移源必须是随包发行过的原值", "15000", en!!.from)
        assertEquals("迁移目标 = 当前平台出厂缺省", "360000", en.to)
        assertEquals(
            "from/to 必须能对上解析器产出的十进制文本",
            PortFactoryConfig.INFINITE_LOOP_ALERT_MS.toString(),
            en.to,
        )
        val jp = byKey[spec.upperJpLabel]
        assertNotNull("玩家关掉英文输出后设置页写的是日文标签，同样要迁", jp)
        assertEquals("日文标签规则与英文规则同源同目标", en.copy(key = spec.upperJpLabel), jp)
    }

    @Test
    fun `恢复默认的回落值与平台出厂缺省同源`() {
        assertTrue(
            "恢复默认必须回平台标定，而不是桌面 5000",
            PortFactoryConfig.factoryDefaultOf(ConfigKey.InfiniteLoopAlertTime) ==
                ConfigValue.IntValue(PortFactoryConfig.INFINITE_LOOP_ALERT_MS) as ConfigValue?,
        )
        assertNull("未覆盖的键不给平台缺省", PortFactoryConfig.factoryDefaultOf(ConfigKey.MaxLog))
    }
}
