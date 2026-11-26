package com.eratw.port.android

import com.eratw.port.android.market.JsonNumber
import com.eratw.port.android.market.JsonObject
import com.eratw.port.android.market.JsonString
import com.eratw.port.android.market.MarketJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 代码包运行期身份侧车的读取结论（[ModCodeIdentity.read] 的返回）。
 *
 * 三态是刻意的 fail-closed：装载端必须能区分「没有侧车」（普通口上包 / 老代码包，按
 * 磁盘目录身份走既有路径）与「侧车坏了」（作者声明与盘面自相矛盾，**不许猜** —— 猜错要么
 * 把 A 包的代码装进 B 包，要么让坏档静默退化成没有身份）。
 */
internal sealed interface CodeIdSidecar {

    /** 侧车不存在：本包没有运行期身份声明（无代码产物，或本次改动之前导入的老代码包）。 */
    data object Absent : CodeIdSidecar

    /** 侧车在场且可信：运行期身份 = 作者在 `eratw-pack.json` 里声明的包名（或其回退值）。 */
    data class Bound(val runtimePackageId: String) : CodeIdSidecar

    /** 侧车在场但读不懂（头不对 / 行数不对 / 包名形态非法 / 超长 / UTF-8 坏）：调用方必须拒载。 */
    data object Corrupt : CodeIdSidecar
}

/**
 * 代码包**运行期身份**侧车（ADM 裁决：代码包身份一致化）。
 *
 * ## 为什么需要它
 *
 * 磁盘命名空间的身份是内容派生的 `kojo-<8hex>`（[ModImportController.packageIdFor]：源清单
 * 排序后 sha256 前 4 字节），它随源集合变化、玩家不可读；而**作者声明的包名**（`eratw-pack.json`
 * 的 `packageId` / 回退内层 `manifest.json` 的 `packageId`）才是代码产物的运行期身份。二者
 * 结构性不同，导入端又不允许拿「文件名反推身份」——所以把「磁盘目录 → 运行期身份」的映射
 * 落在本侧车里，装载端（后续 lane 接线）读它而不是重新猜。
 *
 * ## 线格式（冻结）
 *
 * ```
 * RT2CODEID/1
 * <runtimePackageId>
 * ```
 *
 * 两行 + 行尾换行，UTF-8。单值（一个磁盘包只能有一个运行期身份），任何多余内容都算坏档。
 *
 * ## 三条硬约束
 *
 * 1. **原子写**：同目录临时文件 → `ATOMIC_MOVE` 覆盖（文件系统不支持时退回
 *    `REPLACE_EXISTING`）。写到一半掉电只坏临时文件，正式侧车永远是上一份完整内容。
 * 2. **坏档 fail-closed**：读侧不修、不猜、不回退到磁盘目录身份——[CodeIdSidecar.Corrupt] 由
 *    装载端按拒载处理（本笔只定义读结论，不接线装载）。
 * 3. **无代码产物的包不产生本文件**：没有 `code.mod` 的导入在成功收口时显式
 *    [clear]（重导去掉代码产物的包会清掉旧侧车，不让旧运行期身份残留）。
 *
 * 文件命名 = 磁盘包名 + [ModPackageInventory.CODE_ID_EXTENSION]：与包成员同目录，
 * 由 [ModPackageInventory.memberNames] 纳入包成员面（卸载/GC/换包同口径清理）。
 */
internal object ModCodeIdentity {

    /** 侧车首行（版本闸；改格式必须换这一行，老读端据此判坏档而不是误读）。 */
    internal const val HEADER_LINE: String = "RT2CODEID/1"

    /** 侧车字节上限：两行 ASCII 包名远小于它；超限直接判坏档（不读进堆）。 */
    internal const val MAX_SIDECAR_BYTES: Long = 256L

    /** 原子写临时文件名前缀；GC 残留判据按 `.tmp` 形状兜底，故用点前缀 + `.tmp` 结尾。 */
    private const val TEMP_PREFIX: String = ".codeid-"

    /** 原子写临时文件名后缀（与 [ModSettingsStore] / [ModCodeStore] 的残留判据同形状）。 */
    private const val TEMP_SUFFIX: String = ".tmp"

    /** 行数 = 头 + 包名 + 空尾行（末尾换行切出来是三个元素）。 */
    private const val LINE_COUNT: Int = 3

    /** 侧车文件名（唯一拼装出口；包成员面引用这里，不再另写字面量）。 */
    fun fileName(diskPackageId: String): String = diskPackageId + ModPackageInventory.CODE_ID_EXTENSION

    /**
     * 原子写侧车：`rt2Dir/<diskPackageId>.codeid` ← [HEADER_LINE] + [runtimePackageId]。
     *
     * @param diskPackageId 磁盘命名空间身份（`kojo-<8hex>`，导入链编译产物）
     * @param runtimePackageId 运行期身份（作者声明的包名，已过形态闸）
     * @throws IOException 目录不可建 / 写失败 / 改名失败（调用方按整包拒导处理）
     */
    fun write(rt2Dir: File, diskPackageId: String, runtimePackageId: String) {
        require(ModEnablement.isValidPackageId(diskPackageId)) { "非法磁盘包名：$diskPackageId" }
        require(ModEnablement.isValidPackageId(runtimePackageId)) { "非法运行期包名：$runtimePackageId" }
        if (!rt2Dir.isDirectory && !rt2Dir.mkdirs() && !rt2Dir.isDirectory) {
            throw IOException("无法创建 rt2 目录：${rt2Dir.absolutePath}")
        }
        val target = File(rt2Dir, fileName(diskPackageId))
        val tmp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, rt2Dir)
        val outcome = runCatching {
            FileOutputStream(tmp).use { out ->
                out.write((HEADER_LINE + "\n" + runtimePackageId + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            moveReplacing(tmp, target)
        }
        if (outcome.isFailure) {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
            throw IOException("代码身份侧车写入失败：${target.absolutePath}", outcome.exceptionOrNull())
        }
    }

    /** 读侧车（不抛、不修；坏档折 [CodeIdSidecar.Corrupt]）。 */
    fun read(rt2Dir: File, diskPackageId: String): CodeIdSidecar {
        val file = File(rt2Dir, fileName(diskPackageId))
        return when {
            !file.exists() -> CodeIdSidecar.Absent
            !file.isFile || file.length() > MAX_SIDECAR_BYTES -> CodeIdSidecar.Corrupt
            else -> parseSidecar(runCatching { String(file.readBytes(), Charsets.UTF_8) }.getOrNull())
        }
    }

    /** 线格式判据（单点）：2 行 + 头行准确 + 第二行过包名形态闸，否则坏档。 */
    private fun parseSidecar(text: String?): CodeIdSidecar {
        val lines = text?.split('\n') ?: return CodeIdSidecar.Corrupt
        val wellFormed = lines.size == LINE_COUNT && lines[LINE_COUNT - 1].isEmpty() &&
            lines[0] == HEADER_LINE && ModEnablement.isValidPackageId(lines[1])
        return if (wellFormed) CodeIdSidecar.Bound(lines[1]) else CodeIdSidecar.Corrupt
    }

    /** 删侧车（幂等；返回 true = 文件已不在）。失败只可能是 IO 错，调用方按 fail-closed 处理。 */
    fun clear(rt2Dir: File, diskPackageId: String): Boolean {
        val file = File(rt2Dir, fileName(diskPackageId))
        if (!file.exists()) return true
        file.setWritable(true)
        return file.delete()
    }

    /** 原子替换：优先 ATOMIC_MOVE；文件系统不支持（部分虚机盘）时退回 REPLACE_EXISTING。 */
    private fun moveReplacing(tmp: File, target: File) {
        val moved = runCatching {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse { failure ->
            if (failure is AtomicMoveNotSupportedException) null else throw failure
        }
        if (moved == null) Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * 包元数据里 `packageId` 的**原文**（不套 `PACK_ID_RE` 形态闸）。
 *
 * 为什么需要单独取：代码包身份裁决要求「声明了运行期包名但形态非法 ⇒ STORAGE 拒收」，而
 * [PackMeta.packageId] 形态非法会折成 null、与「没声明」不可区分，拿它裁决会把坏声明静默
 * 当成未声明回退到内层。这里复刻 [packMetaOf] 的取件与 schema 闸（缺失 / 不可解析 / schema
 * 不认识 ⇒ null = 元数据整体未采信，不参与身份裁决），但返回原值、不判形态。
 *
 * 只读取、不写盘，与 [packMetaOf] 同一次导入里可能各扫一遍 zip：仅在带 `code.mod` 的包上
 * 调用，条目 ≤ `PACK_META_MAX_ENTRY_BYTES`，代价可忽略。
 */
internal fun rawPackMetaPackageId(archiveZips: List<ByteArray>): String? {
    val entry = archiveZips.firstNotNullOfOrNull { zipBytes ->
        ModZip.fixedEntryBytes(zipBytes, PACK_META_ENTRY_NAME, PACK_META_MAX_ENTRY_BYTES)
    } ?: return null
    val obj = entry.let { MarketJson.parseOrNull(String(it, Charsets.UTF_8)) as? JsonObject }
    val schemaAccepted = (obj?.opt("schema") as? JsonNumber)?.intValueOrNull() == PACK_META_SCHEMA
    return if (schemaAccepted && obj != null) (obj.opt("packageId") as? JsonString)?.value else null
}
