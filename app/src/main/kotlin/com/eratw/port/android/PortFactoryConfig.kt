package com.eratw.port.android

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.DEFAULT_CONFIG_CANDIDATES
import com.eratw.port.core.config.EMUERA_CONFIG_FILE_NAME
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.config.FIXED_CONFIG_CANDIDATES
import com.eratw.port.platform.storage.ConfigValueMigration
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android 出厂缺省覆盖（CONFIG-OVERLAY 的策略面，2026-11-09）。
 *
 * 机制在 core（[com.eratw.port.core.config.ConfigOverlay]），**策略在这里**：本对象只说
 * 「安卓这台设备上，哪几个配置项的出厂缺省该和桌面不同、为什么」。
 *
 * ## 为什么需要（ADR-005 例外登记项）
 *
 * `InfiniteLoopAlertTime` 是**墙钟**阈值（`ConfigData.cs:75`，缺省 5000 ms）：官方语义是
 * 「一段脚本连续跑超过阈值就问玩家要不要中止，答继续只重启计时器」（skia `Process.cs:481-515`、
 * `:512-513`；1.824 `:493-512`、`:509-510`）—— 它假定「一段正常脚本跑不到 5 秒」。这个假定在桌面上成立，在安卓上不成立：
 *
 * - 日始长段实测 **123,496,792 条语句 ≈ 2.90 亿条指令**（`specs/verify-notes.md` §22.2，
 *   地图重修开启 / gen 362 存档），玩家机吞吐 ≈2.22M 指令/秒 ⇒ 同段 ≈130 s；
 * - 解释器吞吐差距是**结构性**的：ADR-010 已判定不重写 VM 解释器（栈码实测判负
 *   0.986×/0.899×、flat 语句表判负 +193 MiB），所以「把这段压到 5 s 以内」没有可行路径；
 * - 于是 5 s 阈值在手机上等于「每 5 秒弹一次框」，玩家读作「卡无限循环」。
 *
 * 结论：这是个**按平台重新标定**的时间量，不是可修的缺陷 ⇒ 抬到 **360 s（6 分钟）**，
 * 并把 `ConfigSpec.kt` 该项的 GUI 值域上限从官方的 `100000` 放宽到 `600000`。
 * 语义上仍逐字忠实：到阈值仍会问、答继续仍在跑，且玩家在设置页可随时改回。
 *
 * ## 为什么是 360 s（余量来源与口径）
 *
 * * **下界（实测口径）**：最坏合法链 = 130 s（同一数字的来历见上，`verify-notes` §22.2 的
 *   123,496,792 条语句 ÷ 2.22M 指令/秒）。这是**玩家机**吞吐，不是桌面吞吐。
 * * **余量倍数 2.77×**：360 / 130 ≈ 2.77 —— 覆盖同一台机器 ±15% 的降频抖动，也覆盖
 *   `CHARANUM` 继续上涨带来的线性增长；**不**覆盖「更慢的机器」，那部分由
 *   `VmConfig.monotonicMs` 的后台扣减与「答继续后阈值翻倍」（360→720→1440…，封顶 30 min）
 *   兜 —— 见 `core/code/Vm.kt` 的 `escalatedLoopAlertMs`。
 * * **上界（不贴 GUI 夹取上限）**：缺省 360000 与放宽后的 600000 之间留 1.67× 的调节空间，
 *   玩家真想再放松时有地方可去。写成 600000（=夹取上限）会让设置页显示一个和实际生效
 *   一致但**无处可调**的数，这正是「不要贴上限」的含义（2026-11-10 明示）。
 * * **不再取「官方夹取上限」**：官方 `100000` 是桌面标定（5 s 阈值够用），不是物理上限；
 *   安卓一条合法长链就 130 s，贴着它等于「必然在同一段里再问一次」。
 *
 * 与阈值同等重要的另外两半（本轮同批落地，缺一仍是误报）：
 * 1. 墙钟只计 **VM 真在跑**的时间（`VmConfig.monotonicMs`，宿主扣掉后台累计）；
 * 2. 答「继续」后同 run 内阈值**翻倍**，一段长链问一次而不是四次。
 *
 * ## 这个出厂值必须真正落进生效配置（2026-11-12 修）
 *
 * 4.980 语料的 `emuera.config:31` 显式写着
 * `MILLISECONDS FOR INFINITE LOOP WARNING:15000`。本对象「只填空、不改写」的策略会把这个
 * 已有键当成玩家写过 ⇒ 平台覆盖被过滤掉，180 s 从未生效，线上实际阈值是 15 s：正常长链
 * 反复弹框，玩家点「是」就留下「可能进入了无限循环因此选择强制终止」。
 *
 * 现在两处一起保证值真的落地：
 * 1. 构建期 `stageGameAssets` 的安卓出厂校准把随包 `emuera.config` 改成 360000（新装即生效）；
 * 2. 安装期 [CONFIG_VALUE_MIGRATIONS] 把盘上仍是随包原值 15000 的旧配置一次性改成 360000，
 *    由安装器写下的 `update/config-migrations.txt` 标记保证只跑一次。
 *
 * ## 玩家显式写过的键不动
 *
 * 只看**配置链文件里有没有这个键**（`CSV/_default.config` → `emuera.config` →
 * `CSV/_fixed.config`，`ConfigData.cs:479-494`）：写了就以玩家的为准，一个字节都不覆盖
 * （上面那次 15000 → 360000 的历史值迁移是唯一例外，且只跑一次）。
 * 这正是「出厂缺省」的定义 —— 只填空，不改写。判据按**英文或日文标签**做行首匹配
 * （`ConfigData.cs:262-284` 两种标签都认；语料与移植侧写盘默认英文，玩家关掉英文输出后
 * 设置页写日文），比较前统一大写、忽略行尾空白与 `;` 之后的注释。
 *
 * 目录名按 `EmueraConfigParser.resolveCsvDir` 定位，不写死 `CsvDir/`：语料实际目录是
 * `CSV`，Android 文件系统区分大小写，写死会在真机上把两份 CSV 配置永远判成「不存在」。
 * 候选文件名按官方回落链取 `_default.config` → `default.config` 与
 * `_fixed.config` → `fixed.config`。
 *
 * ## 与「恢复默认设置」的一致性
 *
 * 设置页的「恢复默认」读的是随包出厂 `emuera.config`（[FactoryConfigDefaults]）。
 * 这份资产现在带 360000（见上），[FactoryConfigDefaults.apply] 仍保留
 * [factoryDefaultOf] 回落，保证旧包或测试夹具缺这个键时也不会被抹回桌面的 5 s。
 */
internal object PortFactoryConfig {

    /**
     * 安卓出厂 `InfiniteLoopAlertTime`（毫秒）= **360 s（6 分钟）**。
     *
     * 取值依据（「130 s 实测 + 2.77× 余量」的完整推导）见类头「为什么是 360 s」。
     * 一句话：最坏合法链 130 s 是**实测**下界，360 s 是它的 2.77 倍，且离 `ConfigSpec.kt`
     * 放宽后的 GUI 上限 600000 还有 1.67× 空间 —— 缺省**不贴**上限，玩家想再调高有处可去。
     *
     * 0 = 关闭的语义逐字不变（skia `Process.ScriptProc.cs:35`、1.824 `Process.cs:1012` 的开关位）：本常量 > 0，
     * 与「玩家把它改成 0」不冲突。
     */
    const val INFINITE_LOOP_ALERT_MS: Int = 360_000

    /**
     * 本平台出厂缺省表（**唯一真源**）：机制层只认这张表，测试也钉这张表。
     *
     * 新增条目前先问三件事：① 官方缺省是不是桌面标定（时间/像素/吞吐相关）；② 安卓上
     * 这个差别是不是结构性的（不是可修 bug）；③ 玩家能不能在设置页改回去。三条都成立才加。
     */
    val OVERLAY: Map<ConfigKey, ConfigValue> = mapOf(
        ConfigKey.InfiniteLoopAlertTime to ConfigValue.IntValue(INFINITE_LOOP_ALERT_MS),
    )

    /**
     * 随包历史值 → 当前出厂值的一次性迁移表（安装期由 `GameAssetInstaller` 执行）。
     *
     * 4.980 语料把阈值显式写成 15000（`emuera.config:31`）；只靠 [OVERLAY] 的「只填空」
     * 永远改不到它。老玩家盘上的 15000 由本表在升级后的首个冷启动改写成
     * [INFINITE_LOOP_ALERT_MS]，其余值（0 = 关闭、玩家自己调的毫秒数）一律不动。
     *
     * `from` 必须是**随包发行过的原值**，不是当前语料值；因此这里只登记 15000 一条。
     * 安装器用 `update/config-migrations.txt` 按**规则行**记账（`KEY:from->to`），每条规则
     * 最多执行一次：玩家事后把值改回 15000 属于显式选择，不会被再次改写；将来新增规则
     * （同键换一个历史值也算新规则）仍能在老盘上执行。
     *
     * 两个键名条目对应**同一历史值的两种写盘标签**：语料与出厂设置是英文
     * （`emuera.config:75` 的 `OUTPUT ENGLISH ITEMS IN THE CONFIG FILE:YES`），但玩家关掉
     * 这项后，设置页保存会把整份配置重写成日文标签（`EmueraConfigWriter.writeLine`）；
     * 只认英文会让这类盘的 15000 永远迁不掉（[overlayFor] 的双标签判定同一教训）。
     */
    val CONFIG_VALUE_MIGRATIONS: List<ConfigValueMigration> = run {
        val spec = ConfigSpecTable.specOf(ConfigKey.InfiniteLoopAlertTime)
        val target = INFINITE_LOOP_ALERT_MS.toString()
        buildList {
            add(
                ConfigValueMigration(
                    key = spec?.upperEnLabel ?: "MILLISECONDS FOR INFINITE LOOP WARNING",
                    from = "15000",
                    to = target,
                ),
            )
            if (spec != null) {
                add(ConfigValueMigration(key = spec.upperJpLabel, from = "15000", to = target))
            }
        }
    }

    /**
     * 某键的**平台出厂缺省**；不在本平台覆盖表里返回 null（调用方回落引擎缺省）。
     *
     * 「恢复默认设置」必须走这里而不是 `ConfigSpec.defaultValue`：否则恢复一次就把
     * 平台标定抹回桌面口径。
     */
    fun factoryDefaultOf(key: ConfigKey): ConfigValue? = OVERLAY[key]

    /**
     * 本次会话该注入的覆盖表：**只保留玩家从未显式写过的键**。
     *
     * 判据是「配置链文件里有没有这个键」，不是「值等不等于缺省」—— 后者会把玩家
     * 显式写的 5000 也当成没写过（那是玩家的选择，必须尊重）。
     *
     * 读文件失败（不存在 / 无权限 / 编码坏）一律按「没写过」处理：出厂缺省的意义就是
     * 在没有任何玩家输入时给一个平台合理值；读不到文件恰恰说明没有玩家输入。
     */
    fun overlayFor(gameRoot: Path?): Map<ConfigKey, ConfigValue> {
        if (gameRoot == null) return OVERLAY
        val written = writtenKeys(gameRoot)
        if (written.isEmpty()) return OVERLAY
        return OVERLAY.filterKeys { key ->
            // 键名匹配必须与官方**同一口径**：`ConfigSpec.matchesUpperKey` 同时认
            // 日文标签与英文标签（`ConfigData.cs:262-284` 比 Name/Text/EngText；
            // 写盘侧的 `EmueraConfigWriter.writeLine(item, english)` 两种都能产出）。
            // 只认英文标签会让「玩家用日文标签写过」的配置被当成没写过而被覆盖——
            // 这正是「只填空不改写」最不能犯的错，故两条都查。
            val spec = ConfigSpecTable.specOf(key) ?: return@filterKeys true
            spec.upperJpLabel !in written && spec.upperEnLabel !in written
        }
    }

    /**
     * 配置链文件里出现过的键名集合（大写、去行尾空白、忽略 `;` 之后注释与空行）。
     *
     * 只做「这一行是不是 `KEY:value`」的键名切分，不解析值：值的合法性由
     * `EmueraConfigParser` 在装载期判定，这里只回答「有没有写过」这一个布尔问题。
     */
    fun writtenKeys(gameRoot: Path): Set<String> {
        val out = HashSet<String>()
        // 目录名不能写死：语料实际是 `CSV`，Windows 开发根也可能叫 `CsvDir`/`csv`。
        // 解析口径与装载链同源（`Process.cs:207` 的字面 "csv" + 大小写不敏感定位）。
        val csvDir = EmueraConfigParser.resolveCsvDir(gameRoot)
        val files = ArrayList<Path>()
        EmueraConfigParser.locateFirstExisting(csvDir, DEFAULT_CONFIG_CANDIDATES)?.let(files::add)
        files.add(gameRoot.resolve(EMUERA_CONFIG_FILE_NAME))
        EmueraConfigParser.locateFirstExisting(csvDir, FIXED_CONFIG_CANDIDATES)?.let(files::add)
        for (f in files) {
            val text = try {
                if (!Files.isRegularFile(f)) continue
                // `Files.readString` 需要 API 26+；这里走最保守的一条读法（真机 minSdk 更低）。
                String(Files.readAllBytes(f), Charsets.UTF_8)
            } catch (_: IOException) {
                continue
            } catch (_: java.nio.charset.CharacterCodingException) {
                continue
            }
            scanKeys(text, out)
        }
        return out
    }

    /** 纯函数形态（供 JVM 单测直接喂文本）：把 [text] 里出现的键名并入 [into]。 */
    fun scanKeys(text: String, into: MutableSet<String>) {
        text.split('\n').forEachIndexed { index, raw ->
            var line = raw.removeSuffix("\r")
            // 语料 `emuera.config` 带 UTF-8 BOM；不剥掉会把首行键名读成
            // `\uFEFFMILLISECONDS…`，于是「玩家写过」永远判不出来。
            if (index == 0) line = line.removePrefix("\uFEFF")
            line = line.trimStart()
            if (line.isEmpty() || line.startsWith(";")) return@forEachIndexed
            val body = line.substringBefore(';')
            val colon = body.indexOf(':')
            if (colon <= 0) return@forEachIndexed
            val key = body.substring(0, colon).trim().uppercase()
            if (key.isNotEmpty()) into += key
        }
    }
}
