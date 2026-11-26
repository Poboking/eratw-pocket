package com.eratw.port.android.market

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 盘上索引镜像的读写（`<filesDir>/market/index.json`；mkm-pipe 路复用本类）。
 *
 * 语义（契约 §2.6 + 真源 service.ts:358-379 的读三态、service.ts:508-518 的「校验通过才
 * 覆盖写」）：
 * - 读：文件不存在 = [MarketIndexState.Absent]；存在但 JSON 语法或字段校验不过 =
 *   [MarketIndexState.Invalid]（盘上原样保留，读路径一个字节都不写）；通过 = Ok。
 *   `fetchedAtMs` 由调用方盖章传入（本机取数时刻，非索引原文；service.ts:398-403）。
 * - 写：先经 [MarketVerifier] 全量校验，不过 = Rejected（盘上一个字节不动——失败的刷新
 *   永远不许把好用的旧列表换成坏文件）；通过才覆盖：**原文一字不改地写成镜像**
 *   （service.ts:509-511「坏条目留在盘上、每次读都重新报进 dropped，不在这里悄悄洗成
 *   干净的」）。落盘 = 同目录临时文件写满并 fsync，再 rename 覆盖目标；任一步失败 =
 *   Failed 且旧镜像原样在位（临时文件已清，失败不留残壳）。
 */
class MarketIndexStore(private val file: File) {

    /** 写盘结果三态：校验拒（盘未动）/ IO 失败（盘未动，旧镜像原样）/ 成功覆盖。 */
    sealed interface WriteOutcome {
        data object Ok : WriteOutcome
        data class Rejected(val problem: String) : WriteOutcome
        data class Failed(val problem: String) : WriteOutcome
    }

    /** 读盘上镜像的三态判别（零网络；不存在的文件不算错误）。 */
    fun read(verifier: MarketVerifier, fetchedAtMs: Long): MarketIndexState {
        if (!file.exists()) return MarketIndexState.Absent
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return MarketIndexState.Invalid("索引镜像读不出（磁盘或权限问题）：${e.message}")
        }
        return when (val parsed = verifier.parseIndex(text, fetchedAtMs)) {
            is MarketParseResult.Ok -> MarketIndexState.Ok(parsed.value)
            is MarketParseResult.Invalid -> MarketIndexState.Invalid(parsed.problem)
        }
    }

    /**
     * 校验通过才覆盖写（写的是 [rawJson] 原文镜像）；返回 [WriteOutcome]。
     * 失败（校验拒 / IO 错）时盘上旧镜像保证原样。
     */
    fun write(verifier: MarketVerifier, rawJson: String): WriteOutcome {
        val checked = verifier.parseIndex(rawJson, 0L)
        if (checked is MarketParseResult.Invalid) return WriteOutcome.Rejected(checked.problem)
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            return WriteOutcome.Failed("索引目录建不出来：${parent.path}")
        }
        val temp = File(parent, file.name + ".tmp")
        return try {
            FileChannel.open(
                temp.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                channel.write(ByteBuffer.wrap(rawJson.toByteArray(Charsets.UTF_8)))
                channel.force(true)
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            WriteOutcome.Ok
        } catch (e: IOException) {
            cleanupTempQuietly(temp)
            WriteOutcome.Failed("索引镜像落盘失败：${e.message}")
        } catch (e: SecurityException) {
            cleanupTempQuietly(temp)
            WriteOutcome.Failed("索引镜像落盘被拒（权限）：${e.message}")
        }
    }

    /** 失败路径的临时文件清扫（删不掉也不影响旧镜像，只多一个 .tmp 残壳下次再清）。 */
    private fun cleanupTempQuietly(temp: File) {
        runCatching { temp.delete() }
    }
}
