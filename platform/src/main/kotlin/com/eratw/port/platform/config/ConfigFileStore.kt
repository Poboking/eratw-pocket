package com.eratw.port.platform.config

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Android 侧的 `emuera.config` 文件存储：读原文 → 交给保留式合并 → 以 UTF-8 with BOM 原子写回。
 *
 * 三件事在这里落地，其余语义都在 core 层：
 *   * **BOM**：原版写出用 `EncodingHandler.UTF8BOMEncoding`（spec: `Config.cs:17`、
 *     `EncodingHandler.cs:13`），即 UTF-8 且带 BOM（spec: `ConfigData.cs:429` 的 `StreamWriter`）。
 *     读入时剥掉 BOM；写回时固定补上，与 `SaveConfig` 的产物同形。
 *   * **换行**：只做探测（`LoadedConfig.lineSeparator`）供接线与诊断；逐行保留由
 *     `EmueraConfigWriter.mergeInto` 负责，所以混合行尾也不会被这一层整平。
 *   * **原子性**：先写同目录临时文件、`fsync`、再 `rename` 覆盖目标
 *     （Android/Linux 的 `rename(2)` 是原子替换）。任何一步失败都返回 false 并删掉临时文件，
 *     原文件保持原样；不会出现写了一半的配置。桌面 JVM 的 `renameTo` 不覆盖既有目标，
 *     走带备份回滚的兜底路径（见 [renameReplacing]），只影响开发机上的单测/工具链。
 *
 * 合并函数由调用方注入（[save] 的参数），本类不认识配置条目 —— 这样平台层不复制条目表。
 * 生产接线处传 `EmueraConfigWriter.mergeInto` 的部分应用，例如闭包
 * `{ original -> EmueraConfigWriter.mergeInto(original, store, EffectiveConfig.of(store).englishConfigOutput) }`
 * （`:platform` 自 M5-B 起依赖 `:core-vm`，见 `platform/build.gradle.kts`；[com.eratw.port.platform.config.SettingsStore]
 * 直接传 `SettingsModel.buildMerge`）。
 *
 * 写出编码**恒为 UTF-8 with BOM**：原版 `SaveConfig` 用 `Config.Encode` =
 * `EncodingHandler.UTF8BOMEncoding`（spec: `Config.cs:17`、`ConfigData.cs:429`），不看原文有没有 BOM。
 * 因此「BOM 全保留」在这条路径上的含义是**不丢** BOM；原文若没有 BOM 会被补上，与原版一致。
 *
 * 解码口径：只认合法 UTF-8（缺 BOM 也按 UTF-8 解）。原版 `EncodingHandler.DetectEncoding`
 * 在没有 BOM 时会嗅探 UTF-8 / Shift-JIS（spec: `EncodingHandler.cs:15-41`），本类不复制那套
 * 嗅探 —— 非法 UTF-8 直接抛 [IOException]，[save] 随即返回 false，**绝不把乱码写回**。
 * TODO(verify)：Shift-JIS 旧配置的解码需要接 `core` 的编码探测（`LangCodec` 那一套）；
 * 在接线之前，非 UTF-8 的配置一律读失败并拒绝写回（宁可不出设置页，也不写乱码）。
 */
class ConfigFileStore(val file: File) {

    /**
     * 一次读盘的结果。
     *
     * @param text 已剥掉 UTF-8 BOM 的全文（BOM 不计入首行文本）
     * @param hasBom 原文件是否带 UTF-8 BOM（`EncodingHandler.cs:23-25` 据此判编码）
     * @param lineSeparator 探测到的行尾：出现过 CRLF 就是 CRLF，否则 LF；混合文件由合并层逐行保留
     */
    data class LoadedConfig(
        val text: String,
        val hasBom: Boolean,
        val lineSeparator: String,
    )

    /**
     * 读入当前文件；**不存在返回 null**。读失败或不是合法 UTF-8 时抛 [IOException]。
     *
     * 空文件（包括只有 BOM 的三字节文件）返回空串 —— 调用方按「无原文」处理。
     */
    fun load(): LoadedConfig? {
        if (!file.isFile) {
            return null
        }
        val bytes = file.readBytes()
        val hasBom = bytes.size >= UTF8_BOM.size &&
            bytes[0] == UTF8_BOM[0] &&
            bytes[1] == UTF8_BOM[1] &&
            bytes[2] == UTF8_BOM[2]
        val body = if (hasBom) bytes.copyOfRange(UTF8_BOM.size, bytes.size) else bytes
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (e: CharacterCodingException) {
            throw IOException("emuera.config 不是合法 UTF-8，拒绝改写：${file.absolutePath}", e)
        }
        return LoadedConfig(text, hasBom, detectLineSeparator(text))
    }

    /**
     * 读当前文件、调用 [merge] 得到新全文，再以 UTF-8 with BOM 原子写回。
     *
     * @param merge 保留式合并函数；生产环境传 `EmueraConfigWriter.mergeInto` 的部分应用。
     *   它接收**已剥 BOM** 的原文（文件缺失时是空串），返回不含 BOM 的新全文。
     *   合并函数抛异常时不写盘，异常原样上抛（那是调用方的编程错误，不该被当成 I/O 失败吞掉）。
     * @return 写成功为 true；目标是目录、文件不可读/不可解码、建目录失败、写临时文件失败、
     *   rename 失败一律 false，**原文件不动**。文件缺失时按空原文合并并创建（对应
     *   `SaveConfig` 首次建文件的路径，spec: `ConfigData.cs:429`）。
     */
    fun save(merge: (String) -> String): Boolean {
        // 目标若是目录（用户/工具把 emuera.config 建成目录），rename 兜底会把整个目录改名成 .bak，
        // 相当于毁掉里面的东西；直接拒绝，原样返回 false。
        if (file.isDirectory) {
            return false
        }
        val loaded = try {
            load()
        } catch (_: IOException) {
            return false
        }
        val merged = merge(loaded?.text ?: "")
        return writeUtf8WithBom(merged)
    }

    /** 同目录临时文件 + `fsync` + `rename`；失败清理临时文件，目标文件保持原样。 */
    private fun writeUtf8WithBom(text: String): Boolean {
        val target = file.absoluteFile
        val directory = target.parentFile ?: return false
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            return false
        }
        val temp = try {
            File.createTempFile("${target.name}.", ".tmp", directory)
        } catch (_: IOException) {
            return false
        }
        var renamed = false
        try {
            FileOutputStream(temp).use { out ->
                out.write(UTF8_BOM)
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            renamed = renameReplacing(temp, target)
            return renamed
        } catch (_: IOException) {
            return false
        } finally {
            if (!renamed) {
                temp.delete()
            }
        }
    }

    /**
     * 用 [temp] 替换 [target]。
     *
     * 主路径就是 `rename`：Android/Linux 的 `rename(2)` 会原子替换已存在目标，这是本类在真机上的
     * 唯一路径。桌面 JVM（Windows）的 `java.io.File.renameTo` 不覆盖已存在目标，于是落到兜底：
     * 先把目标改名成同目录备份，临时文件就位后再删备份；第二步失败就把备份改回原名。
     * 兜底不是原子的（桌面不跑游戏，只用它做单测/工具链验证），但不丢数据。
     */
    private fun renameReplacing(temp: File, target: File): Boolean {
        if (temp.renameTo(target)) {
            return true
        }
        val directory = target.parentFile ?: return false
        var backup = File(directory, "${target.name}.bak")
        var suffix = 0
        while (backup.exists()) {
            suffix++
            backup = File(directory, "${target.name}.$suffix.bak")
        }
        if (!target.renameTo(backup)) {
            return false
        }
        if (temp.renameTo(target)) {
            backup.delete()
            return true
        }
        backup.renameTo(target) // 回滚；即使这一步也失败，内容仍在同目录备份里，没有丢
        return false
    }

    /** 探测口径：出现过 CRLF 就算 CRLF，否则 LF；混合文件的逐行保留在 core 的合并层。 */
    private fun detectLineSeparator(text: String): String =
        if (text.contains("\r\n")) CRLF else LF

    companion object {
        /** 文件名 —— 固定放在游戏目录根（spec: `ConfigData.cs:33` 的 `Program.ExeDir + "emuera.config"`）。 */
        const val FILE_NAME: String = "emuera.config"

        private const val CRLF = "\r\n"
        private const val LF = "\n"

        /** UTF-8 BOM 的三字节（`EF BB BF`；代码位 U+FEFF 见 `EncodingHandler.cs:13`）。 */
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** 游戏目录里的标准位置（`<目录>/emuera.config`）。 */
        fun inDirectory(directory: File): ConfigFileStore =
            ConfigFileStore(File(directory, FILE_NAME))
    }
}
