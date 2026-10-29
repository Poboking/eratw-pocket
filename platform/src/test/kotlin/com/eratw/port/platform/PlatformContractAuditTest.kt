package com.eratw.port.platform

import com.eratw.port.core.config.ConfigChainRole
import com.eratw.port.core.config.ConfigFile
import com.eratw.port.core.config.ConfigGroup
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigLayerPresence
import com.eratw.port.core.config.ConfigLineStatus
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.config.SettingsConstraint
import com.eratw.port.core.config.SettingsModel
import com.eratw.port.core.config.SettingsRejectReason
import com.eratw.port.core.config.SettingsSetResult
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.platform.config.SettingsStore
import com.eratw.port.platform.image.AndroidGraphicsSink
import com.eratw.port.platform.input.KeyMacroController
import com.eratw.port.platform.input.TouchGestureController
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.input.InputSession
import com.eratw.port.render.input.KeyMacroTable
import com.eratw.port.render.input.MacroKeys
import com.eratw.port.render.view.GraphicsLayer
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M7 平台面契约审计 —— 把 `platform` 模块里**装配层与输入层**的读数逐条对拍回 C#。
 *
 * 覆盖面（契约 w7-platformaudit）：
 *   1. **三层配置链合并**：`default.config` → `emuera.config` → `fixed.config`，顺序即优先级、
 *      `fixed` 层解析成功后钉位（`ConfigData.cs:479-493`、`:512-592`、`:573-576`）；
 *   2. **保留式写回**：已知键原位替换，未知键 / 注释 / 缩进 / 顺序 / BOM / 行尾逐字符保留，
 *      缺失键按注册顺序追加（`ConfigData.cs:424-464`，写出编码 `Config.cs:17`）；
 *   3. **宏条分组**：10 组 × 12 键 = 120 槽、组名前缀与存盘行序（`KeyMacro.cs:15-19`、
 *      `:59-87`、`:99-158`），`Ctrl+数字` 换组后的组名标签（`MainWindow.cs:514-528`、
 *      `:1601-1611`）；
 *   4. **双击跳过**：本波契约口径（300ms 内第二击 = 跳过，`docs/eratw-项目实施方案.md:165`），
 *      C# 侧只有右键/`Esc` 的 `mesSkip = true`（`MainWindow.cs:814-816`、`:1415-1422`、
 *      `EmueraConsole.cs:1371-1373`）；
 *   5. **只读组展示**：`GAME` 可编辑、`DEBUG` / `REPLACE` 只读（`ConfigData.cs:23-27`、
 *      `:430`、`Process.cs:240-246`）；
 *   6. **`PRINT_RECT` 的填充色**（M11-A L4 / GFX-05）：段色 = 打印那一刻的 `userStyle.Color`、
 *      alpha 恒 0xFF（`EmueraConsole.cs:3197-3200`、`ConsoleShapePart.cs:76`、
 *      `Process.cs:1436-1451`、`ConfigItem.cs:272`）—— 落在 `image/AndroidGraphicsSink`。
 *
 * 纪律：本文件只新建测试，产品代码零改动。与 C# 或契约有分歧时**按现状钉桩**，在对应用例的
 * KDoc 里登记 `TODO(verify)` 与 C# 行号，由组长决定是否接线。所有输入都写在 `TemporaryFolder`
 * 里，不读语料、不硬编码机器路径。
 */
class PlatformContractAuditTest {

    @get:Rule
    val temp = TemporaryFolder()

    // -----------------------------------------------------------------
    // 1. 三层配置链合并
    // -----------------------------------------------------------------

    /**
     * 三次读取的顺序就是优先级：后面的文件覆盖前面的（`ConfigData.cs:491-493`），
     * 且 `fixed` 层最后读、解析成功即钉住（`:573-576`）。
     */
    @Test
    fun `三层链按default_emuera_fixed的顺序叠加`() {
        val root = gameDir()
        val csv = root.resolve("CSV")
        writeConfig(csv.resolve("_default.config"), "IGNORE CASE:NO\nWINDOW WIDTH:900\n")
        writeConfig(root.resolve("emuera.config"), "WINDOW WIDTH:1024\n")
        writeConfig(csv.resolve("_fixed.config"), "WINDOW WIDTH:1200\nLINE HEIGHT:21\n")

        val chain = EmueraConfigParser.loadChainFromDisk(root)
        val store = chain.store

        assertEquals(
            "loadConfig 的调用顺序就是优先级（ConfigData.cs:491-493）",
            listOf(ConfigChainRole.DEFAULT, ConfigChainRole.EMUERA, ConfigChainRole.FIXED),
            chain.layers.map { it.role },
        )
        assertEquals(3, chain.results.size)
        assertTrue("三层文件都在位", chain.layers.all { it.presence == ConfigLayerPresence.PRESENT })
        assertEquals(2, chain.defaultLineCount)
        assertEquals(1, chain.emueraLineCount)
        assertEquals(2, chain.fixedLineCount)

        assertFalse("default 层的 IGNORE CASE:NO 没有被后两层改写", store.itemOf(ConfigKey.IgnoreCase).bool())
        assertEquals("fixed 层压过 emuera 与 default", 1200, store.itemOf(ConfigKey.WindowX).int())
        assertEquals(21, store.itemOf(ConfigKey.LineHeight).int())
        assertEquals(
            "fixed 层写过的非枚举条目全被钉住（ConfigData.cs:573-576）",
            setOf(ConfigKey.WindowX, ConfigKey.LineHeight),
            chain.fixedKeys,
        )
        assertEquals(
            "三层真正写过的键取并集",
            setOf(ConfigKey.IgnoreCase, ConfigKey.WindowX, ConfigKey.LineHeight),
            chain.touchedKeys,
        )
    }

    /**
     * 下划线形优先：`_default.config` 存在就**不再看** `default.config`
     * （`ConfigData.cs:483-486` 是 `if (!File.Exists(...)) 换名`），`_fixed.config` 同理
     * （`:487-490`）。
     *
     * 键必须用条目表里的标签原文（`ConfigData.cs:74` 的 `"Lines per scroll"`，比较前两侧
     * 都大写化，`AConfigItem.cs:22-23`）；自造的缩写会落成 `UNMATCHED_KEY` 而被静默跳过
     * （`ConfigData.cs:540-543`），读到的仍是缺省值 1。
     */
    @Test
    fun `下划线形的候选文件优先于同名无下划线文件`() {
        val root = gameDir()
        val csv = root.resolve("CSV")
        writeConfig(csv.resolve("_default.config"), "FPS:15\n")
        writeConfig(csv.resolve("default.config"), "FPS:30\n")
        writeConfig(csv.resolve("_fixed.config"), "LINES PER SCROLL:3\n")
        writeConfig(csv.resolve("fixed.config"), "LINES PER SCROLL:9\n")
        writeConfig(root.resolve("emuera.config"), "WINDOW WIDTH:1024\n")

        val chain = EmueraConfigParser.loadChainFromDisk(root)

        assertEquals(
            "命中 _default.config（ConfigData.cs:483-486）",
            "_default.config",
            chain.layer(ConfigChainRole.DEFAULT).resolvedPath?.fileName?.toString(),
        )
        assertEquals(
            "命中 _fixed.config（ConfigData.cs:487-490）",
            "_fixed.config",
            chain.layer(ConfigChainRole.FIXED).resolvedPath?.fileName?.toString(),
        )
        assertEquals("无下划线那份的 30 不生效", 15, chain.store.itemOf(ConfigKey.FPS).int())
        assertEquals("无下划线那份的 9 不生效", 3, chain.store.itemOf(ConfigKey.ScrollHeight).int())
        assertEquals(1, chain.result(ConfigChainRole.DEFAULT)!!.appliedCount)
        assertEquals(1, chain.result(ConfigChainRole.FIXED)!!.appliedCount)
        assertEquals(
            "两层各读到 1 行（无下划线那两份没被算进行数）",
            2,
            chain.defaultLineCount + chain.fixedLineCount,
        )
    }

    /** 钉位不只拒绝再次解析，也拒绝设置页的写入与重置（`ConfigItem.cs:20-27`、`ConfigData.cs:573-576`）。 */
    @Test
    fun `fixed层钉住的条目_设置页写入与重置都被拒`() {
        val root = gameDir()
        val csv = root.resolve("CSV")
        writeConfig(root.resolve("emuera.config"), "LINE HEIGHT:30\n")
        writeConfig(csv.resolve("_fixed.config"), "LINE HEIGHT:21\n")

        val chain = EmueraConfigParser.loadChainFromDisk(root)
        val item = chain.store.itemOf(ConfigKey.LineHeight)
        assertTrue("fixed 层最后读并置钉", item.fixed)
        assertEquals("emuera 的 30 被 fixed 的 21 压掉", 21, item.int())

        val model = SettingsModel(chain.store)
        assertTrue(model.entry("LineHeight")!!.fixed)

        val rejected = model.set("LineHeight", "19") as? SettingsSetResult.Rejected
        assertNotNull("钉住的条目写入必须被拒", rejected)
        assertEquals(SettingsRejectReason.FIXED, rejected!!.reason)
        assertEquals("ConfigItem.Value setter 在 Fixed 时静默丢弃（ConfigItem.cs:20-27），本层转成显式拒绝", "この項目は固定されているため変更できません", rejected.message)
        assertFalse("单项重置同样写不进", model.reset("LineHeight"))
        assertEquals("值一个都没变", 21, item.int())
        assertFalse("值写入器本身也拒绝（ConfigItem.cs:20-27）", item.assign(ConfigValue.IntValue(19)))
        assertEquals(21, item.int())
    }

    /**
     * 官方怪癖：`ConfigItem.TryParse` 末尾 `return flag`（`ConfigItem.cs:212`），
     * 而 5 个枚举分支从不给 `flag` 赋值（`:172-211`）—— 于是 `fixed.config` 里的枚举行
     * **值被改写但不置钉**（`ConfigData.cs:573`）。原样复刻，不修（ADR-005）。
     */
    @Test
    fun `fixed层的枚举条目值生效但钉位不置_官方怪癖`() {
        val root = gameDir()
        val csv = root.resolve("CSV")
        writeConfig(csv.resolve("_fixed.config"), "TEXT EDITOR COMMAND LINE SETTING:SAKURA\n")

        val chain = EmueraConfigParser.loadChainFromDisk(root)
        val editor = chain.store.itemOf(ConfigKey.EditorType)
        assertEquals("值确实被 fixed 层改写", "SAKURA", editor.enumConstant())
        assertFalse("枚举分支不给 flag 赋值 ⇒ 钉不住", editor.fixed)
        assertTrue("所以 fixed 层没有任何钉位", chain.fixedKeys.isEmpty())

        val model = SettingsModel(chain.store)
        assertTrue(model.entry("EditorType")!!.constraint is SettingsConstraint.Choices)
        assertTrue("没被钉住 ⇒ 设置页可写", model.set("EditorType", "EMEDITOR") is SettingsSetResult.Applied)
        assertEquals("EMEDITOR", editor.enumConstant())
    }

    /**
     * 坏行只中止**本文件**剩余行：C# 的 `try/catch` 包住整个读行循环（`ConfigData.cs:524-586`），
     * 异常冒出去之后该文件的后续行不再读；但 `LoadConfig` 的三次调用是独立的（`:491-493`），
     * 下一层照常叠加。
     */
    @Test
    fun `坏行只中止本文件剩余行_下一层照常叠加`() {
        val root = gameDir()
        val csv = root.resolve("CSV")
        writeConfig(csv.resolve("_default.config"), "IGNORE CASE:NO\n")
        writeConfig(root.resolve("emuera.config"), "FONT SIZE:abc\nWINDOW WIDTH:1024\n")
        writeConfig(csv.resolve("_fixed.config"), "LINE HEIGHT:21\n")

        val chain = EmueraConfigParser.loadChainFromDisk(root)
        val emuera = chain.result(ConfigChainRole.EMUERA)!!

        assertEquals(1, emuera.abortedAtLine)
        assertEquals("坏行之后的 1 行被丢弃", 1, emuera.droppedLines)
        assertEquals(1, chain.warnings.size)
        assertEquals("emuera.config", chain.warnings[0].fileName)
        assertEquals(1, chain.warnings[0].lineNo)
        assertEquals(ConfigKey.FontSize, chain.warnings[0].configKey)
        assertEquals("数字でない文字が含まれています", chain.warnings[0].message) // Lang.cs:962

        assertEquals("坏行不改值", 18, chain.store.itemOf(ConfigKey.FontSize).int())
        assertEquals("坏行之后的行没读进来", 760, chain.store.itemOf(ConfigKey.WindowX).int())
        assertFalse("default 层已在坏行之前生效", chain.store.itemOf(ConfigKey.IgnoreCase).bool())
        assertEquals("fixed 层不受上一层中止影响", 21, chain.store.itemOf(ConfigKey.LineHeight).int())
        assertEquals(listOf(ConfigChainRole.EMUERA), chain.abortedLayers.map { it.role })
    }

    // -----------------------------------------------------------------
    // 2. 保留式写回
    // -----------------------------------------------------------------

    /**
     * 链式装载后的写回只动 `emuera.config`：已知键原位替换，注释 / 未知键 / 行首空格的缩进行 /
     * 顺序 / BOM / 每行自己的行尾逐字符保留（`EmueraConfigWriter.mergeInto` 的契约，
     * 对应原版 `SaveConfig` 的遍历口径 `ConfigData.cs:424-464`）。
     *
     * `default.config` / `fixed.config` 是**只读输入**，一个字节都不许动。
     *
     * 输入里同时放了注释 / 未知键 / 行首空格的缩进行 / 一个已知键 / 一个 emuera 层改过的键：
     * `WINDOW WIDTH` 在原位被替换成 `ウィンドウ幅:1024`，`LINE HEIGHT` 来自 fixed 层且原文件
     * 没有，按 `configArray` 顺序追加（`ConfigData.cs:430-453`）。
     */
    @Test
    fun `链式装载后写回_注释未知键顺序与行尾全保留`() {
        val root = gameDir()
        val csv = root.resolve("CSV")
        writeConfig(csv.resolve("_default.config"), "FPS:15\n")
        writeConfig(csv.resolve("_fixed.config"), "LINE HEIGHT:21\n")
        val emuera = root.resolve("emuera.config")
        writeConfig(
            emuera,
            "; 顶注释\r\nUNKNOWN KEY:keep\r\nFONT SIZE:22\r\n ; 缩进的分号不是注释\r\nWINDOW WIDTH:1024\r\n",
        )
        val defaultBytes = Files.readAllBytes(csv.resolve("_default.config"))
        val fixedBytes = Files.readAllBytes(csv.resolve("_fixed.config"))

        val chain = EmueraConfigParser.loadChainFromDisk(root)
        val model = SettingsModel(chain.store)
        assertTrue(SettingsStore.inDirectory(root.toFile()).save(model))
        assertFalse("写盘成功即清脏", model.dirty)

        val text = readBody(emuera)
        val lines = text.removeSuffix("\r\n").split("\r\n")
        assertEquals("; 顶注释", lines[0])
        assertEquals("未知键原样（ConfigData.cs:540-543 的静默跳过）", "UNKNOWN KEY:keep", lines[1])
        assertEquals(
            "已知键原位替换成写出行（ConfigItem.cs:84-87）",
            "フォントサイズ:22",
            lines[2],
        )
        assertEquals(
            "行首空格的 ; 不算注释（ConfigData.cs:529 只看第 0 个字符），且无冒号 ⇒ 原样保留",
            " ; 缩进的分号不是注释",
            lines[3],
        )
        assertTrue(
            "fixed 层的值随写回落到 emuera.config（SaveConfig 遍历整个 configArray，ConfigData.cs:430-453）",
            text.contains("一行の高さ:21\r\n"),
        )
        assertTrue("emuera 层的值同样写回", text.contains("ウィンドウ幅:1024\r\n"))
        assertEquals("切换过的行只出现一次", 1, lines.count { it == "フォントサイズ:22" })
        assertFalse("逐行行尾保留 ⇒ 不出现裸 LF", text.replace("\r\n", "").contains('\n'))
        assertArrayEquals(
            "default.config 是只读输入，原样不动",
            defaultBytes,
            Files.readAllBytes(csv.resolve("_default.config")),
        )
        assertArrayEquals(
            "fixed.config 同理",
            fixedBytes,
            Files.readAllBytes(csv.resolve("_fixed.config")),
        )
    }

    /**
     * 缺失的已知键按 `configArray` 的注册顺序追加到文件末尾（`ConfigData.cs:430` 的遍历顺序），
     * 追加块沿用原文件的主流行尾；原文件以换行结尾时，追加块也以行尾结束
     * （`ConfigData.cs:447`、`:451` 的 `WriteLine`）。
     *
     * 三条「不写出」规则（`ConfigData.cs:433`）同时钉住：`CompatiDRAWLINE` 恒跳过、
     * `ChangeMasterNameIfDebug` 为 true 时跳过、`LastKey` 为 0 时跳过。
     */
    @Test
    fun `缺失键按注册顺序追加并沿用主流行尾`() {
        val root = gameDir()
        val emuera = root.resolve("emuera.config")
        writeConfig(emuera, "FONT SIZE:22\r\n")

        val chain = EmueraConfigParser.loadChainFromDisk(root)
        val model = SettingsModel(chain.store)
        assertTrue(SettingsStore.inDirectory(root.toFile()).save(model))

        val text = readBody(emuera)
        val lines = text.removeSuffix("\r\n").split("\r\n")
        assertEquals("1 行原文 + 96 行补齐（100 条 GAME，减去已存在的 FONT SIZE 与 3 条跳过规则）", 97, lines.size)
        assertEquals("首行已知键原位替换", "フォントサイズ:22", lines[0])
        assertEquals(
            "追加块的第一条是 configArray 的第一条（ConfigData.cs:45）",
            "大文字小文字の違いを無視する:YES",
            lines[1],
        )
        assertTrue("原文件以 CRLF 结尾 ⇒ 追加块也用 CRLF", text.endsWith("\r\n"))
        assertFalse(text.replace("\r\n", "").contains('\n'))

        assertFalse(
            "CompatiDRAWLINE 永不写出（ConfigData.cs:433 第一个条件）",
            lines.any { it.startsWith("DRAWLINEを常に新しい行で行う") },
        )
        assertFalse(
            "ChangeMasterNameIfDebug 为 true 时不写出（ConfigData.cs:433 第二个条件）",
            lines.any { it.startsWith("デバッグコマンドを使用した時にMASTERの名前を変更する") },
        )
        assertFalse(
            "LastKey 为 0 时不写出（ConfigData.cs:433 第三个条件）",
            lines.any { it.startsWith("最終更新コード") },
        )
    }

    /**
     * 三组条目各有自己的输入文件：主文件链只查 `configArray`（`ConfigData.cs:539` 的
     * `GetConfigItem`），`replaceArray` 只能由 `_Replace.csv` 写入（`Process.cs:240-246`）。
     * 于是写在 `emuera.config` 里的 `REPLACE` 键既不改值，写回时也按未知行原样保留 ——
     * 不会凭空多出第二份、也不会被删掉。
     */
    @Test
    fun `REPLACE组的键写在emuera_config里既不生效也不被改写`() {
        val root = gameDir()
        val emuera = root.resolve("emuera.config")
        writeConfig(emuera, "Currency symbol:€\r\nFONT SIZE:22\r\n")

        val chain = EmueraConfigParser.loadChainFromDisk(root)
        val result = chain.result(ConfigChainRole.EMUERA)!!
        assertEquals(ConfigLineStatus.UNMATCHED_KEY, result.records[0].status)
        assertEquals(
            "replaceArray 的缺省值没被主文件链碰到",
            "\$",
            chain.store.itemOf(ConfigKey.MoneyLabel).string(),
        )

        assertTrue(SettingsStore.inDirectory(root.toFile()).save(SettingsModel(chain.store)))
        val text = readBody(emuera)
        assertEquals("该行只出现一次", 1, Regex("Currency symbol").findAll(text).count())
        assertTrue("逐字符保留（含 € 与 CRLF）", text.contains("Currency symbol:€\r\n"))
    }

    /** `DEBUG` / `REPLACE` 两组在设置页上只读：可编辑判据是 C# 的数组归属，不是条目名字（`ConfigData.cs:23-27`、`:430`）。 */
    @Test
    fun `只读组展示_DEBUG与REPLACE不可编辑且不参与全部重置`() {
        val model = SettingsModel()

        assertEquals(
            "setDefault 的注册顺序：configArray → debugArray → replaceArray（ConfigData.cs:45-51、:152-158、:159-174）",
            listOf(ConfigGroup.GAME, ConfigGroup.DEBUG, ConfigGroup.REPLACE),
            model.groups.map { it.group },
        )
        assertEquals(
            listOf("emuera.config", "debug.config", "_Replace.csv"),
            model.groups.map { it.fileName },
        )
        assertTrue(model.group(ConfigGroup.GAME).editable)
        assertFalse("debug.config 不在 SaveConfig 路径上（ConfigData.cs:430）", model.group(ConfigGroup.DEBUG).editable)
        assertFalse("_Replace.csv 没有写回入口（Process.cs:240-246）", model.group(ConfigGroup.REPLACE).editable)
        assertEquals(ConfigSpecTable.gameCount, model.group(ConfigGroup.GAME).entries.size)
        assertEquals(ConfigSpecTable.debugCount, model.group(ConfigGroup.DEBUG).entries.size)
        assertEquals(ConfigSpecTable.replaceCount, model.group(ConfigGroup.REPLACE).entries.size)

        val debugItem = model.store.itemOf(ConfigKey.DebugWindowWidth)
        val money = model.store.itemOf(ConfigKey.MoneyLabel)
        assertTrue(debugItem.assign(ConfigValue.IntValue(999)))
        assertTrue(money.assign(ConfigValue.StringValue("円")))

        assertEquals(
            "「全部重置」只写可编辑组",
            ConfigSpecTable.gameCount,
            model.resetAllEditable(),
        )
        assertEquals("DEBUG 值不被重置", 999, debugItem.int())
        assertEquals("REPLACE 值不被重置", "円", money.string())
        assertEquals("GAME 组回到缺省", 18, model.store.itemOf(ConfigKey.FontSize).int())

        // TODO(verify)：设置页的只读只由 editable 标志表达，SettingsModel.set 本身不按组拒绝写入
        // （原版 REPLACE 组也只有 LoadReplaceFile 一个入口，Process.cs:240-246，没有程序化 set 的等价物）。
        // 卡点＝契约没要求模型层拦截，本波不擅自加守卫；界面必须只看 editable 决定是否给出入口。
        assertTrue(model.set("MoneyLabel", "€") is SettingsSetResult.Applied)
        assertEquals("€", money.string())
        assertFalse(model.group(ConfigGroup.REPLACE).editable)
    }

    // -----------------------------------------------------------------
    // 3. 宏条分组与换组
    // -----------------------------------------------------------------

    /**
     * 宏条容量与存盘行序：10 组 × 12 键 = 120 槽（`KeyMacro.cs:15-19`），槽位号
     * `fkey + group * 12`（`:144-153`）；存盘先 10 行组名再 120 行宏（`:69-76`），
     * 组名行的前缀是**写死的日文**「グループ」（`:71`、`:105`），与界面语言无关。
     */
    @Test
    fun `宏条十组各十二槽_存盘行序与读回一致`() {
        val scheme = KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE
        val table = KeyMacroTable(scheme)

        assertEquals(10, KeyMacroTable.MAX_GROUP)
        assertEquals(12, KeyMacroTable.MAX_FKEY)
        assertEquals(120, KeyMacroTable.SLOT_COUNT)
        assertEquals("组 0 的宏名前缀不带组号（Lang.cs:4226）", "宏快捷键F1:", table.nameOf(0, 0))
        assertEquals("组 n>0 的前缀是 G{n}:（KeyMacro.cs:53）", "G5:宏快捷键F1:", table.nameOf(0, 5))
        assertEquals("F12 = 第 12 个键", "宏快捷键F12:", table.nameOf(11, 0))
        assertEquals("组名缺省取 Lang.KeyMacro.SetMacroGroup（KeyMacro.cs:42）", "设置为按键宏组9", table.groupName(9))
        assertFalse("没改过就不落盘（KeyMacro.cs:61-64）", table.changed)

        table.set(0, 0, "一")
        table.set(11, 9, "末")
        assertTrue("SetMacro 置脏（KeyMacro.cs:146）", table.changed)

        val lines = table.serializeLines()
        assertEquals("10 行组名 + 120 行宏（KeyMacro.cs:69-76）", 130, lines.size)
        assertEquals("グループ0:设置为按键宏组0", lines[0])
        assertEquals("グループ9:设置为按键宏组9", lines[9])
        assertEquals("宏快捷键F1:一", lines[10])
        assertEquals("组 9 的 F12 = 槽 119，行 10 + 119（KeyMacro.cs:147-152）", "G9:宏快捷键F12:末", lines[129])

        val reloaded = KeyMacroTable(scheme)
        reloaded.loadLines(lines)
        assertEquals("一", reloaded.get(0, 0))
        assertEquals("末", reloaded.get(11, 9))
        assertEquals("设置为按键宏组9", reloaded.groupName(9))
    }

    /**
     * `Ctrl+数字` 换组（`MainWindow.cs:514-528`）：主键盘 48..57 减 48、小键盘 96..105 减 96，
     * 都是 0..9 共 10 组 —— 所以 `Ctrl+0` 是组 0，没有「第 10 组」。
     * 换组只改 `macroGroup` 并给出组名标签（`:1601-1611`），**不吞键**（`:527` 之后走 `:530` 的
     * `break`），也不提交。
     */
    @Test
    fun `Ctrl加数字换组后功能键取对应组的槽位`() {
        val scheme = KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE
        val table = KeyMacroTable(scheme)
        table.loadLines(
            listOf(
                table.nameOf(11, 3) + "组三F12",
                table.nameOf(11, 5) + "组五F12",
            ),
        )
        val controller = KeyMacroController(InputSession(macroTable = table))

        val to3 = controller.onKey(MacroKeys.VK_DIGIT_0 + 3, MacroKeys.Modifier.CONTROL)
        assertTrue(to3.decision is MacroKeys.Decision.SwitchGroup)
        assertEquals("macroGroup = 3（MainWindow.cs:1604）", 3, controller.session.macroGroup)
        assertEquals("标签取自 KeyMacro.GetGroupName（:1605）", "设置为按键宏组3", to3.groupLabel)
        assertFalse("换组键交回控件（:527 → :530 的 break）", to3.consumed)

        val f12In3 = controller.onKey(MacroKeys.VK_F12, MacroKeys.Modifier.NONE)
        assertTrue(f12In3.decision is MacroKeys.Decision.Prefill)
        assertEquals("槽位 = 11 + 3 × 12（KeyMacro.cs:150-153）", "组三F12", controller.session.box.text)
        assertTrue("只预填不提交（MainWindow.cs:507-512）", controller.session.submissions.isEmpty())

        val to5 = controller.onKey(MacroKeys.VK_NUMPAD_0 + 5, MacroKeys.Modifier.CONTROL)
        assertEquals("小键盘 96..105 同样减 96（MainWindow.cs:521-524）", 5, controller.session.macroGroup)
        assertEquals("设置为按键宏组5", to5.groupLabel)
        controller.onKey(MacroKeys.VK_F12, MacroKeys.Modifier.NONE)
        assertEquals("组五F12", controller.session.box.text)

        val to0 = controller.onKey(MacroKeys.VK_DIGIT_0, MacroKeys.Modifier.CONTROL)
        assertEquals("Ctrl+0 是组 0（没有第 10 组）", 0, controller.session.macroGroup)
        assertEquals("设置为按键宏组0", to0.groupLabel)
        val empty = controller.onKey(MacroKeys.VK_F12, MacroKeys.Modifier.NONE)
        assertEquals("组 0 的 F12 没有宏 ⇒ 清框", "", controller.session.box.text)
        assertEquals("", (empty.decision as MacroKeys.Decision.Prefill).text)
        assertTrue(controller.session.submissions.isEmpty())
    }

    // -----------------------------------------------------------------
    // 4. 双击跳过与显式提交
    // -----------------------------------------------------------------

    /**
     * 双击窗口按两次点击的**抬手时间**量，闭区间 300ms；第二击还必须落在第一击的
     * **位置邻域**内（列距 <= [TouchGestureController.DEFAULT_DOUBLE_TAP_COLUMN_SLOP]、
     * 纵向不超过一行）才算跳过 —— M10-PLAY 收口 M7 钉桩②③。C# 侧无双击跳过实现：
     * `MainWindow.cs:776-782` 的双击只喂剪贴板处理器，跳过全靠右键 `mesSkip = true`
     * （`:814-816`、`EmueraConsole.cs:1371-1373`）；邻域阈值没有 C# 行号可引，它是
     * 「真机快速点两个不同按钮不得被误判成跳过」这条产品判据的实现（口径见
     * [TouchGestureController] 类文档「边界」；`docs/eratw-项目实施方案.md:165` 只钉 300ms）。
     */
    @Test
    fun `双击跳过按抬手时间量窗口且第二击须在位置邻域内`() {
        val touch = TouchGestureController()
        assertEquals("第一击仍是一次单击", TouchGestureController.Action.Tap(3), tap(touch, 3, 0L))
        assertEquals(
            "恰 300ms 仍在闭区间内；第二击列 3 -> 7（差 4 <= 6 格邻域）同一行 => 跳过",
            TouchGestureController.Action.Skip,
            tap(touch, 7, 300L),
        )
        assertEquals(
            "Skip 消费掉候选，第三击重新起算",
            TouchGestureController.Action.Tap(2),
            tap(touch, 2, 650L),
        )

        val measuredOnUp = TouchGestureController()
        assertEquals(TouchGestureController.Action.Tap(1), tap(measuredOnUp, 1, 0L))
        measuredOnUp.onDown(col = 9, y = 100f, timeMs = 250L)
        assertEquals(
            "按下时间在窗口内、抬手时间 301ms 超窗 => 单击（按抬手时间量）",
            TouchGestureController.Action.Tap(9),
            measuredOnUp.onUp(y = 100f, timeMs = 301L),
        )

        val twoButtons = TouchGestureController()
        assertEquals(TouchGestureController.Action.Tap(10), tap(twoButtons, 10, 0L))
        assertEquals(
            "窗口内但列距 20 > 6 格邻域 => 两个不同按钮各算一次单击，不误判成跳过",
            TouchGestureController.Action.Tap(30),
            tap(twoButtons, 30, 150L),
        )
        assertEquals(
            "邻域外那击重新起算候选：紧接着同列同点再击仍是跳过",
            TouchGestureController.Action.Skip,
            tap(twoButtons, 30, 250L),
        )

        val farRow = TouchGestureController()
        assertEquals(TouchGestureController.Action.Tap(10), tap(farRow, 10, 0L))
        val lowerRowY = 100f + 2f * TouchGestureController.DEFAULT_PIXELS_PER_LINE
        farRow.onDown(col = 10, y = lowerRowY, timeMs = 100L)
        assertEquals(
            "同列但纵向跨两行（> 一行邻域）=> 单击",
            TouchGestureController.Action.Tap(10),
            farRow.onUp(y = lowerRowY, timeMs = 150L),
        )
    }

    /**
     * 双击跳过**不预填、不提交**：手势控制器只产出
     * [TouchGestureController.Action.Skip]；真正交给核心层必须由绑定层显式调用
     * [KeyMacroController.submit]。M7 钉桩①的装配已在 M10-PLAY 收口：绑定层收到
     * Skip 后调 [KeyMacroController.requestSkip]，**下一次显式提交**自动带
     * `mesSkip = true`（原版右键当场 `PressEnterKey(keySkip = true, ...)`，
     * `EmueraConsole.cs:1371-1373`、`MainWindow.cs:814-816`；跳过态在下一个取值
     * 输入前结束，`EmueraConsole.cs:1451-1456` 的输入循环）。没有待标记时
     * [KeyMacroController.submit] 维持鼠标左键那一路的 `mesSkip = false`（`:818-820`）。
     */
    @Test
    fun `双击跳过不预填不提交_标志搭上下一次显式提交`() {
        val touch = TouchGestureController()
        val controller = KeyMacroController()

        assertEquals(TouchGestureController.Action.Tap(3), tap(touch, 3, 0L))
        assertEquals(TouchGestureController.Action.Skip, tap(touch, 4, 100L))
        assertEquals("跳过不是预填：框里仍然空", "", controller.session.box.text)
        assertTrue("手势层没有任何提交动作", controller.session.submissions.isEmpty())

        controller.requestSkip()
        val submission = controller.submit()
        assertNotNull(submission)
        assertTrue("requestSkip 之后下一次显式提交带 mesSkip = true", submission!!.mesSkip)
        assertTrue("触屏提交按鼠标左键那一路（MainWindow.cs:818-820）", submission.inputsByMouse)
        assertEquals("直到这里只产出了显式提交这一条", 1, controller.session.submissions.size)
        assertFalse("标志送出即消费", controller.hasSkipRequest)

        val normal = controller.submit()
        assertNotNull(normal)
        assertFalse("没有待标记时 submit 仍是 mesSkip = false", normal!!.mesSkip)

        controller.requestSkip()
        controller.clearSkipRequest()
        val cleared = controller.submit()
        assertFalse("clearSkipRequest 作废后下一次提交不带跳过", cleared!!.mesSkip)

        controller.requestSkip()
        val skipNow = controller.submitSkip()
        assertNotNull(skipNow)
        assertTrue("当场提交形态（submitSkip）自带 mesSkip = true", skipNow!!.mesSkip)
        assertFalse("submitSkip 顺带清掉待标记（同一次跳过不重复计）", controller.hasSkipRequest)
    }

    /**
     * 跳过标志的**最后一跳**（M10-PLAY 把 M7 钉桩①办到底）：绑定层拿到的
     * [Submission] 交进生产队列 [QueueInputSink] 之后，取值等待必须还能把 `mesSkip`
     * 取出来 —— 那是语句层写 `ExecContext.mesSkip` 的唯一通道
     * （`StatementExecutor.waitInput` 的 `if (ctx.input.consumeMesSkip()) ctx.mesSkip = true`，
     * 清零在下一条取值等待之前，`EmueraConsole.cs:1451-1456`）。
     * 上一用例只证到 `Submission.mesSkip`，本用例把「队列 → 等待 → 标志」这段也钉死：
     * 原版链路是右键 `PressEnterKey(keySkip = true, ...)`（`MainWindow.cs:814-816`）→
     * `EmueraConsole.cs:1371-1373` 在入口置 `MesSkip` → 下一个取值输入前结束。
     * 真机 `GameHost`/`MainActivity` 那一步（`session?.inputSink?.submit(text, mesSkip)`）
     * 属 app 模块文件面，这里按同形态复刻，不动 app 目录的任何文件。
     */
    @Test
    fun `跳过标志经生产队列交到取值等待_consumeMesSkip读出后即清`() {
        val touch = TouchGestureController()
        val controller = KeyMacroController()
        val sink = QueueInputSink()

        assertEquals(TouchGestureController.Action.Tap(3), tap(touch, 3, 0L))
        assertEquals(TouchGestureController.Action.Skip, tap(touch, 4, 100L))
        controller.session.box.assign("7")
        controller.requestSkip()
        val submission = controller.submit()
        assertNotNull(submission)

        // 绑定层那一步：提交原样入队（先入队后取值，QueueInputSink 类文档第 1 条）
        assertTrue(sink.submit(submission!!.text, mesSkip = submission.mesSkip))
        val outcome = sink.waitInput(InputRequest(type = InputRequest.InputType.INT))
        assertEquals("取到的仍是这条提交的整数", 7L, outcome.intValue)
        assertTrue(
            "跳过态交到 ExecContext.mesSkip 的写入点（EmueraConsole.cs:1373）",
            sink.consumeMesSkip(),
        )
        assertFalse("读过即清：同一条提交不得重复解释跳过", sink.consumeMesSkip())

        // 普通回车那一路不带跳过，且不得继承上一条的跳过标志
        controller.session.box.assign("8")
        val normal = controller.submit()
        assertNotNull(normal)
        assertTrue(sink.submit(normal!!.text, mesSkip = normal.mesSkip))
        assertEquals(8L, sink.waitInput(InputRequest(type = InputRequest.InputType.INT)).intValue)
        assertFalse("没有 keySkip 的提交不产生跳过态", sink.consumeMesSkip())
    }

    // -----------------------------------------------------------------
    // 5. 装配顺序（设置页文件装配层）
    // -----------------------------------------------------------------

    /**
     * [SettingsStore.load] 的 KDoc 要求装配层把 `default` / `fixed` 先读进同一份模型再传 `base`，
     * 而 `load` 自己总是最后叠 `emuera.config`。原版的顺序是 `default` → `emuera` → `fixed`
     * （`ConfigData.cs:491-493`），差别只在**不置钉**的条目上（`:573-576` 只钉
     * `TryParse` 返回 true 的类型，5 个枚举型不返回 true，`ConfigItem.cs:172-212`）：
     *
     *   * 被钉住的整型：`emuera.config` 写不进，两边结论一致（`:20-27` 的守卫）；
     *   * 枚举型：按本装配顺序 `emuera.config` 会盖掉 `fixed.config`，而原版是 `fixed` 盖 `emuera`
     *     —— 当前的 `EMEDITOR` 就是分歧读数。
     *
     * TODO(verify)：这是装配层的顺序接线问题，不属本路文件面（`platform/config` 产品代码零改动）。
     * 卡点＝要按原版顺序，就得在 `SettingsStore.load` 之后再叠一次 `fixed` 层（或改
     * `load` 的入参形状），由组长决定接线方式；生产宿主走的 `ConfigChainSource.replayChainOn`
     * 不受影响（`EmueraConfigParser.replayChainOn` 按 `default` → `emuera` → `fixed` 重放）。
     */
    @Test
    fun `装配层先fixed后load_emuera会越过fixed层的枚举条目`() {
        val root = gameDir()
        val emuera = root.resolve("emuera.config")
        writeConfig(emuera, "TEXT EDITOR COMMAND LINE SETTING:EMEDITOR\r\nLINE HEIGHT:30\r\n")

        // 装配层按 KDoc 的做法先把 fixed 层读进同一份模型（fix = true，ConfigData.cs:493）。
        val store = ConfigStore()
        EmueraConfigParser.loadGameFile(
            ConfigFile(
                "_fixed.config",
                listOf("TEXT EDITOR COMMAND LINE SETTING:SAKURA", "LINE HEIGHT:21"),
            ),
            store,
            fix = true,
        )
        assertEquals("SAKURA", store.itemOf(ConfigKey.EditorType).enumConstant())
        assertTrue("整型被钉住", store.itemOf(ConfigKey.LineHeight).fixed)

        val model = SettingsModel(store)
        val loaded = SettingsStore.inDirectory(root.toFile()).load(model)
        assertTrue(loaded.ok)
        assertNull(loaded.abortedAtLine)
        assertTrue("两行都匹配上了", loaded.warnings.isEmpty())

        assertEquals(
            "钉住的那条：emuera 写不进 ⇒ 与原版 fixed 最后读的结论一致",
            21,
            store.itemOf(ConfigKey.LineHeight).int(),
        )
        assertEquals(
            "枚举那条：原版 fixed 最后读应为 SAKURA；本装配顺序让 emuera 后写",
            "EMEDITOR",
            store.itemOf(ConfigKey.EditorType).enumConstant(),
        )
    }

    // -----------------------------------------------------------------
    // 6. PRINT_RECT 的填充色（GFX-05，AndroidGraphicsSink）
    // -----------------------------------------------------------------

    /**
     * **不注入取色口**时的读数：恒为出厂色 `Config.ForeColor` = 192,192,192
     * （spec: `ConfigData.cs:68`）。这就是 GFX-05 修复**前**真机上的全部读数
     * （`SETCOLOR` 完全不生效，血量条 / 好感量表全灰），本用例把这条现状钉住，
     * 免得以后有人把缺省值「顺手」改成别的颜色。
     */
    @Test
    fun `print rect 未接取色口时恒为出厂灰`() {
        val layer = GraphicsLayer()
        val sink = AndroidGraphicsSink(layer, CellMetrics(18))
        sink.printRect(listOf(50L to false))
        val rect = layer.drainInline().single() as InlineGraphic.Rect
        assertEquals("缺省读数 = Config.ForeColor（ConfigData.cs:68）", 0xFF_C0C0C0.toInt(), rect.colorArgb)
    }

    /**
     * 段色 = **打印那一刻**由调用方交进来的色（spec: `EmueraConsole.cs:3197-3200` 把
     * `userStyle.Color` 当实参交给 `ConsoleShapePart.CreateShape`，`:76` 落在段上）。
     *
     * 取色职责在**语句层**（`ConsoleSink.foregroundColor` 那份状态：SETCOLOR 写它、RESETCOLOR 清回
     * null；GFX-05 的收口形态是语句层读色后走带色重载，sink 不再挂 `currentForeRgb` 提供者——
     * 早先那版给 sink 加第 4 构造参的做法已撤销）。本组用例因此钉的是 sink 这一侧的两件事：
     * ① 缺省重载回落出厂灰；② 带色重载把色冻进段，且 4 参形态的几何没被碰坏。
     */
    @Test
    fun `print rect 缺省给出厂灰且带色重载落进段`() {
        val layer = GraphicsLayer()
        val sink = AndroidGraphicsSink(layer, CellMetrics(18))
        // SETCOLOR 之前：出厂灰（语句层未设色时自己回落 DEFAULT_RECT_COLOR）
        sink.printRect(listOf(50L to false))
        // SETCOLOR 255,0,0 之后（语料形态：`ERB/ビジュアル関連/ColorBar.ERB:152-155` 逐目盛换色再画条）
        sink.printRect(listOf(100L to true, 15L to true, 100L to true, 70L to true), 0xFF0000)

        val parts = layer.drainInline()
        assertEquals(2, parts.size)
        assertEquals(0xFF_C0C0C0.toInt(), (parts[0] as InlineGraphic.Rect).colorArgb)
        assertEquals(
            "GFX-05：改前这一段仍是 0xC0C0C0（SETCOLOR 不生效）",
            0xFFFF0000.toInt(),
            (parts[1] as InlineGraphic.Rect).colorArgb,
        )
        val second = parts[1] as InlineGraphic.Rect
        assertEquals(100, second.xOffsetPx)
        assertEquals(15, second.yOffsetPx)
        assertEquals("ConsoleRectangleShapePart.cs:27,50-56：整段宽 = (int)(X+W) - (int)X", 100, second.widthPx)
        assertEquals(70, second.heightPx)
    }

    /**
     * 段上的色是**调用时被冻进段的快照**，不是绘制时回看当前色（原版同一条：节点构造期就把色存进段，
     * spec: `ConsoleShapePart.cs:76` 对 `ConsoleRectangleShapePart.cs:45` 的 `base.Color`）。
     * 这条区分对真机有意义：色带（`ColorBar.ERB`）一段一段换色画，绘制与提交都发生在之后。
     */
    @Test
    fun `print rect 的色是打印时刻的快照`() {
        val layer = GraphicsLayer()
        val sink = AndroidGraphicsSink(layer, CellMetrics(18))
        sink.printRect(listOf(1L to true), 0x00FF00)
        // 入队之后调用方才换色（下一次控制台更新、排空队列之前）——已入队的段不得回改
        sink.printRect(listOf(2L to true), 0x0000FF)

        val parts = layer.drainInline()
        assertEquals("已入队的段不回改", 0xFF00FF00.toInt(), (parts[0] as InlineGraphic.Rect).colorArgb)
        assertEquals(0xFF0000FF.toInt(), (parts[1] as InlineGraphic.Rect).colorArgb)
    }

    /**
     * alpha 恒 0xFF：输入按 `0xRRGGBB` 解释，高位一律补 0xFF。
     *
     * 依据（原版这条链上根本拿不到半透明色）：`SETCOLOR` 走 `Color.FromArgb(r, g, b)`
     * 三参重载（A=255，spec: `Process.cs:1436`）；`SETCOLORBYNAME` 在 A==0 时直接报
     * `TransparentUnsupported`（spec: `Process.cs:1443-1451`）；配置项的色值同样按
     * `Color.FromArgb(r, g, b)` 解析（spec: `ConfigItem.cs:272`）。语料里的「半透明」
     * 是 `MIX_COLOR` 与背景预先混出来的**纯色**（`CALC_ALPHA_COLOR`，
     * 语料 `ERB/魔改内容/PRINT系/UI_Components_&_Tables.ERB:420-427`）。
     * 补不满 alpha 的代价是实打实的：Android `Paint.setColor` 按 ARGB 解释，
     * alpha=0 时色块整体透明、屏上一个像素都不出。
     */
    @Test
    fun `print rect 一律补满 alpha`() {
        val layer = GraphicsLayer()
        val sink = AndroidGraphicsSink(layer, CellMetrics(18))
        sink.printRect(listOf(1L to true), 0x00_FF8800)
        sink.printRect(listOf(2L to true), 0x12_FF8800)
        val parts = layer.drainInline()
        assertEquals(0xFF_FF8800.toInt(), (parts[0] as InlineGraphic.Rect).colorArgb)
        assertEquals("高位脏值按 RGB 域解释，alpha 恒 0xFF", 0xFF_FF8800.toInt(), (parts[1] as InlineGraphic.Rect).colorArgb)
    }

    // -----------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------

    private fun gameDir(): Path = temp.newFolder("game").toPath()

    /** 写一份带 UTF-8 BOM 的配置（原版写出编码 `Config.cs:17`，读入按 BOM 认编码 `EncodingHandler.cs:23-25`）。 */
    private fun writeConfig(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.write(path, UTF8_BOM + text.toByteArray(Charsets.UTF_8))
    }

    /** 断言带 BOM 并返回正文；BOM 不计入首行文本（`ConfigFile.stripBom` 的口径）。 */
    private fun readBody(path: Path): String {
        val bytes = Files.readAllBytes(path)
        assertTrue("写回必须带 UTF-8 BOM（ConfigData.cs:429 用 Config.Encode = UTF8BOMEncoding）", bytes.size >= 3)
        assertArrayEquals(UTF8_BOM, bytes.copyOfRange(0, 3))
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }

    /** 一次原地单击：按下与抬起的纵坐标相同。 */
    private fun tap(touch: TouchGestureController, col: Int, timeMs: Long): TouchGestureController.Action {
        touch.onDown(col, 100f, timeMs)
        return touch.onUp(100f, timeMs)
    }

    companion object {
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
