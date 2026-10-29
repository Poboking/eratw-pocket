package com.eratw.port.platform.config

import com.eratw.port.core.config.ConfigFile
import com.eratw.port.core.config.ConfigWarning
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.config.SettingsModel
import java.io.File
import java.io.IOException

/**
 * 一次 [SettingsStore.load] 的结果：模型 + 读盘/解析诊断。
 *
 * 原版读配置**没有返回值**（`ConfigData.loadConfig` 是 void，spec: `ConfigData.cs:512-592`）：
 * 坏行只发一条 level-1 告警、中止该文件剩余行，然后照常 `SetConfig`（spec: `ConfigData.cs:579-586`、
 * `:491-494`）。本类把那条链的可见部分整体交给调用方，**不吞**：
 *
 * @property model 解析进的那份模型；[error] 非空时是调用方传进来的原样模型（值未被改动）
 * @property fileExists 读之前 `emuera.config` 是否存在（原版对缺失的处理是「先按缺省跑，再补写一份」，
 *   spec: `ConfigData.cs:496-508`；本层不做自动补写，由调用方决定何时 [SettingsStore.save]）。
 *   读盘/解码失败时报 true —— 能抛错就说明文件在。
 * @property warnings 解析期告警（原版 `ParserMediator.ConfigWarn` 的形状，spec: `ConfigData.cs:579-586`）
 * @property abortedAtLine 首个抛错行；非空时该行之后的行全部没读（spec: `ConfigData.cs:524-586` 的
 *   `try` 包住整个循环）
 * @property droppedLines 因 [abortedAtLine] 被丢弃的行数
 * @property error 读盘 / 解码失败（如不是合法 UTF-8，见 [ConfigFileStore.load]）；此时 [model] 保持原样
 */
data class SettingsLoadResult(
    val model: SettingsModel,
    val fileExists: Boolean,
    val warnings: List<ConfigWarning>,
    val abortedAtLine: Int?,
    val droppedLines: Int,
    val error: IOException? = null,
) {

    /** 读盘 + 解码成功（解析告警与原版一样不影响成功）。 */
    val ok: Boolean get() = error == null
}

/**
 * 设置页的**文件装配层**：把游戏根的 `emuera.config` 读进 [SettingsModel]、把模型合并写回。
 *
 * 只有装配与错误转达，没有任何新语义：
 *   * 读：BOM 剥离与解码归 [ConfigFileStore]（原版据 BOM 选编码，spec: `EncodingHandler.cs:15-41`），
 *     逐行解析归 [EmueraConfigParser.loadGameFile]（spec: `ConfigData.cs:512-592`）；
 *   * 写：保留式合并归 [SettingsModel.buildMerge]（内部是 [com.eratw.port.core.config.EmueraConfigWriter.mergeInto]），
 *     UTF-8 BOM + 同目录临时文件 + `rename` 原子写回归 [ConfigFileStore.save]（原版 `SaveConfig`
 *     用 `EncodingHandler.UTF8BOMEncoding` 整份重写，spec: `ConfigData.cs:429`）。
 *
 * 文件范围：只碰 `SaveConfig` 的目标 `Program.ExeDir + "emuera.config"`
 * （spec: `ConfigData.cs:33`、`:424-429`）。装载链里的 `default.config` / `fixed.config`
 * （spec: `ConfigData.cs:481-493`）是**只读输入**，属启动路径，不在本类文件面内；需要叠加时由装配层
 * 先把它们读进同一份模型再传给 [load]（参数 `base`），顺序与原版一致（default → emuera → fixed）。
 *
 * 失败口径：[load] 不抛（读盘/解码失败进 [SettingsLoadResult.error]）；[save] 失败返回 false 且**原文件不动**
 * （[ConfigFileStore.save] 的约定）。合并函数抛异常属于编程错误，原样上抛。
 */
class SettingsStore(val fileStore: ConfigFileStore) {

    /** 直接指向一个配置文件（测试与工具链用；生产走 [inDirectory]）。 */
    constructor(file: File) : this(ConfigFileStore(file))

    /** 目标文件。 */
    val file: File get() = fileStore.file

    /**
     * 读盘并解析进模型。
     *
     * @param base 承载解析结果的模型，默认新建一份全缺省模型（[SettingsModel]）；文件不存在时它保持缺省值
     *   （`fileExists == false`），对应原版「没有 emuera.config 就按缺省跑」（spec: `ConfigData.cs:491-508`）
     * @return 模型 + 告警 + 中止位置；读盘或解码失败时 [SettingsLoadResult.error] 非空、模型不被改动
     */
    fun load(base: SettingsModel = SettingsModel()): SettingsLoadResult {
        val loaded = try {
            fileStore.load()
        } catch (e: IOException) {
            // 非法 UTF-8：原版会按 Shift-JIS 兜底解出乱码（spec: EncodingHandler.cs:15-41），
            // 本层拒绝解码、把错误交给调用方，绝不把乱码当配置（同 ConfigFileStore 的写法）。
            return SettingsLoadResult(
                model = base,
                fileExists = true,
                warnings = emptyList(),
                abortedAtLine = null,
                droppedLines = 0,
                error = e,
            )
        }
        if (loaded == null) {
            return SettingsLoadResult(
                model = base,
                fileExists = false,
                warnings = emptyList(),
                abortedAtLine = null,
                droppedLines = 0,
            )
        }
        val lines = splitReadLines(loaded.text)
        val parsed = EmueraConfigParser.loadGameFile(ConfigFile(file.name, lines), base.store)
        // 解析完把当前状态定为基线：刚读进来的值不算「未保存的修改」。
        base.markClean()
        return SettingsLoadResult(
            model = base,
            fileExists = true,
            warnings = parsed.warnings,
            abortedAtLine = parsed.abortedAtLine,
            droppedLines = parsed.droppedLines,
        )
    }

    /**
     * 把 [model] 合并写回文件：读原文 → [SettingsModel.buildMerge] → UTF-8 BOM 原子写回。
     *
     * @return 写成功为 true，并顺手 [SettingsModel.markClean]（脏标记归零）；失败 false 且原文件不动
     */
    fun save(model: SettingsModel): Boolean {
        val saved = fileStore.save { original -> model.buildMerge(original) }
        if (saved) {
            model.markClean()
        }
        return saved
    }

    /**
     * `StreamReader.ReadLine` 的形状：按 `\n` 切、剥掉行尾的 `\r`，并且**不为末尾那个换行多产出一行空串**
     * （原版逐行 `ReadLine` 直到 null，`"A\n"` 只有一行）。
     *
     * **与 C# 的一处已知窄分歧**：原版读盘是 `File.ReadAllLines`（`EraStreamReader.cs:52`），
     * 它把**裸 `\r`** 也当行尾；本层与 `EmueraConfigWriter` 的切行口径一致，只认 `\n`，
     * 于是老 Mac 行尾（只有 `\r`）的文件会被读成一整行。
     * TODO(verify)：不能只改读侧 —— 写侧的 `mergeInto` 也按 `\n` 切，只改读侧会让「被并进上一行的
     * 内容」在写回时丢掉；真要支持，必须先改 core 的 `EmueraConfigWriter`（不在本路文件面）。
     * Android/Windows 的 `StreamWriter` 只产出 CRLF / LF，这类文件只可能来自手工编辑。
     */
    private fun splitReadLines(text: String): List<String> {
        if (text.isEmpty()) {
            return emptyList()
        }
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        return if (text.endsWith('\n') && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    companion object {

        /**
         * 游戏根目录形态：`<gameRoot>/emuera.config`（spec: `ConfigData.cs:33` 的 `Program.ExeDir` 口径）。
         * [gameRoot] 是 `ERB` 的上一级目录，与 `core/proc` 的路径口径相同。
         */
        fun inDirectory(gameRoot: File): SettingsStore = SettingsStore(ConfigFileStore.inDirectory(gameRoot))
    }
}
