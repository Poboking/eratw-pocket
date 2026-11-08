package com.eratw.port.android

import android.content.Context
import com.eratw.port.core.config.ConfigFile
import com.eratw.port.core.config.ConfigGroup
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.config.SettingsModel
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 两条重置路径（顶栏「全部重置」与底部「恢复默认设置…」）的目标值 = **本移植版随包出厂的
 * emuera.config**。
 *
 * 这份文件由 `stageGameAssets` 从构建期游戏根搬进 APK assets，首启由
 * `GameAssetInstaller` 解包到游戏根、之后永不覆盖玩家改动 —— 所以它是唯一一份
 * 「没被玩家改过、也没被补丁改过」的出厂原件。恢复默认读它，而不是读游戏根里
 * 现在那份 emuera.config（那正是要被改掉的对象），也不是回 `ConfigSpec` 的引擎裸缺省
 * （那会把移植版自带的 PRINTC 5 / 字号 18 / 黑体 / 900 行日志等一并抹成上游 Emuera 的值）。
 *
 * 三层兜底：
 *  1. 出厂文件写了的键 ⇒ 用出厂值；
 *  2. 出厂文件没写的键 ⇒ [apply] 回落 `ConfigSpec.defaultValue`（引擎缺省）；
 *  3. 整个 assets 条目读不到或不是合法 UTF-8 ⇒ [read] 返回 null，目标表入口 [resetTargets]
 *     把它收敛成空表，交给 [apply] 走与第 2 条同一条逐键回落 —— 两条路径都照常按引擎缺省
 *     写回并计数，「恢复默认」不会因此变成一个死按钮。
 *
 * 解析走生产同一把尺（[EmueraConfigParser.loadGameFile] + [ConfigStore]），不另写一套
 * 键值切分：英文键名、`;` 注释、空值、枚举、颜色、数值夹取前的原文读数全部与装载链一致。
 */
internal object FactoryConfigDefaults {

    private const val CONFIG_ASSET = "emuera.config"

    /** 逐项写入的结论：`pinned` 是被 fixed.config 钉住、[ConfigValue] 写不进去的条目数。 */
    data class Outcome(val written: Int, val pinned: Int)

    /**
     * 从 APK assets 读出厂配置并解析成「键 → 值」目标表；读不到返回 null。
     *
     * 解码口径与 `ConfigFileStore.load` 逐字同源：剥 UTF-8 BOM、严格 UTF-8（坏字节不猜测
     * Shift-JIS，直接判失败走兜底），避免同一份出厂文件在设置页与装载链里读出两个值。
     */
    fun read(context: Context): Map<ConfigKey, ConfigValue>? = try {
        val bytes = context.assets.open(CONFIG_ASSET).use { it.readBytes() }
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body))
            .toString()
        parse(text)
    } catch (_: IOException) {
        null
    }

    /**
     * 出厂目标表的**唯一入口**：设置页两处重置都只认这一份读数（R1.1）。
     *
     * 失败形状收敛成空表而不是 null：`context` 为 null、assets 条目读不到、或不是合法 UTF-8，
     * 一律返回空表，由 [apply] 逐键回落 `ConfigSpec.defaultValue` —— 与「出厂文件缺这个键」
     * 走的是同一条回落路径，所以两种失败对同一批 `GAME` 键产出逐键相同的目标值（R1.3/R1.4）。
     *
     * 这里**不预填默认值**：预填会让「出厂文件到底写了哪些键」在目标表里彻底看不出来，
     * 也会让只在 JVM 测试里喂夹具的调用点读出与生产不同的表。回落只发生在 [apply]。
     */
    fun resetTargets(context: Context?): Map<ConfigKey, ConfigValue> =
        context?.let { read(it) } ?: emptyMap()

    /**
     * 把出厂配置**文本**解析成目标表；纯函数，供 JVM 单测直接喂夹具文本。
     *
     * 只收 GAME 组：`emuera.config` / default.config / fixed.config 本来也只认这一组
     * （`ConfigData.cs:539`），REPLACE / DEBUG 组不参与恢复，免得把 `_Replace.csv` 的
     * 货币单位、污渍初值这类只读条目当成可写目标。
     */
    fun parse(text: String): Map<ConfigKey, ConfigValue> {
        val store = ConfigStore()
        EmueraConfigParser.loadGameFile(ConfigFile(CONFIG_ASSET, splitLines(text)), store)
        return store.snapshot().filterKeys { key ->
            ConfigSpecTable.specOf(key)?.group == ConfigGroup.GAME
        }
    }

    /**
     * 把目标表逐项写进设置页模型；出厂文件缺席的键回落引擎缺省。
     *
     * 直接 `assign` 而不是 `SettingsModel.set`：后者按「玩家输入字符串」走校验链，
     * 而这里的目标值已经是解析好的 [ConfigValue]，再走一次字符串往返只会引入
     * 无谓的解析分歧。`changedKeys()` 比的是 `baseline` 与条目现值，直接 assign
     * 同样会被 `SettingsStore.save` 认出来。
     *
     * `assign` 返回 false 只可能是条目被 fixed.config 钉住（`ConfigItem.assign` 的首句守卫）：
     * 钉住的条目**不强行解钉**（原版口径），但如实计数，让提示不说「全部已恢复」这种假话。
     */
    fun apply(model: SettingsModel, targets: Map<ConfigKey, ConfigValue>): Outcome {
        var written = 0
        var pinned = 0
        for (spec in ConfigSpecTable.all) {
            if (spec.group != ConfigGroup.GAME) continue
            val target = targets[spec.key] ?: spec.defaultValue
            if (model.store.itemOf(spec.key).assign(target)) written++ else pinned++
        }
        return Outcome(written, pinned)
    }

    /** 与 `SettingsStore.splitReadLines` 同口径：按换行切、剥行尾回车、不产出末尾空行。 */
    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        return if (text.endsWith('\n') && lines.last().isEmpty()) lines.dropLast(1) else lines
    }
}
