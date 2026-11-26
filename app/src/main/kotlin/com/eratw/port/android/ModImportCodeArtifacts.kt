package com.eratw.port.android

import android.util.Log
import com.eratw.port.android.market.JsonObject
import com.eratw.port.android.market.JsonString
import com.eratw.port.android.market.MarketJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * 导入 zip 里**代码产物**的条目名（ADR-013 D1 / 2026-11 裁决 A1）。
 *
 * 取值 `code.mod`，**zip 根级固定名**：内容 = 作者编译好的 `.mod` 包**整份字节**（本身是一个
 * 含 `classes.dex` + `manifest.json` 的 zip，见 ADR-013 D1）。与包元数据条目
 * [PACK_META_ENTRY_NAME] 同一套「包根固定名 + 条目存在与否自证」的先例：
 *
 * - 打包端（`modgen pack-mod --code <file>`）只在这个参数给了才写本条目；
 * - 导入端只认这一个根级名字，**不做模糊匹配**（不做 `.mod` 后缀扫描）——作者把别的
 *   `.mod` 文件误放进包根时，宁可不认，也不猜哪一份是代码产物；
 * - 包内没有本条目 ⇒ 普通口上包，导入行为与引入本面前逐字节相同（不建 `code/`、不加 WARN）。
 *
 * 与 `tools/modgen/.../PackModCode.kt` 的同名常量由 modgen 侧哨兵测试读本文件源码文本比对。
 */
internal const val CODE_MOD_ENTRY: String = "code.mod"

/**
 * 代码产物整份字节上限：`4L * 1024L * 1024L`（4 MiB）。
 *
 * 依据：ADR-013 D1 的 `.mod` 是「编译好的 dex + manifest」，常见成品 100 KB–2 MB；4 MiB 给
 * Kotlin 标准库引用与多 dex 留余量，同时远低于 [ModZipGuard.MAX_ENTRY_BYTES]（外层 zip 的
 * 单条目解压上限），保证「包本身能过 ⓪ 展开闸」与「代码产物自身有独立上限」两层判据不互相
 * 顶掉。超限按 fail-closed 拒收（见 [ModImportCodeArtifacts]）。
 *
 * 与 modgen 侧 `PackModCode.MAX_CODE_MOD_BYTES` 同名同值，由哨兵测试读源码文本兜底。
 */
internal const val MAX_CODE_MOD_BYTES: Long = 4L * 1024L * 1024L

/** 代码产物内层 zip 里的 dex 条目名（ADR-013 D1：必有）。 */
private const val CODE_ARTIFACT_DEX_ENTRY: String = "classes.dex"

/** 代码产物内层 zip 里的清单条目名（ADR-013 D1：必有，JSON 对象，含 packageId）。 */
private const val CODE_ARTIFACT_MANIFEST_ENTRY: String = "manifest.json"

/** 内层 `manifest.json` 的字节上限（ADR-013 D1 字段表很小，4 KiB 足够；超限视为坏档）。 */
private const val MAX_CODE_ARTIFACT_MANIFEST_BYTES: Long = 4096L

/** 内层清单里声明包身份的键名（ADR-013 D1：`packageId`）。 */
private const val CODE_ARTIFACT_PACKAGE_ID_KEY: String = "packageId"

/** 暂存临时文件前缀：落在系统临时目录，**从不进 `mods/rt2`**（正式目录零半套产物）。 */
private const val CODE_ARTIFACT_TEMP_PREFIX: String = "eratw-code-artifact-"

/** 暂存临时文件后缀。 */
private const val CODE_ARTIFACT_TEMP_SUFFIX: String = ".tmp"

/** 内层条目读取块大小（只为「超限即放弃」，不追求吞吐）。 */
private const val ENTRY_READ_CHUNK: Int = 8 * 1024

/** 1 MiB 的字节数（上限读数换算成人读单位）。 */
private const val BYTES_PER_MIB: Long = 1024L * 1024L

/** sha256 十六进制输出的位宽（每字节两位）与掩码。 */
private const val HEX_SHIFT: Int = 4
private const val HEX_MASK: Int = 0x0f

/** 单字节取无符号数值的掩码（`byte and 0xFF`）。 */
private const val BYTE_MASK: Int = 0xFF

/** 十六进制小写字符表（[ModCodeStore] 的内容寻址口径：小写 hex）。 */
private const val HEX_CHARS: String = "0123456789abcdef"

/**
 * 代码产物面拒收异常：把「该失败归哪一档」随异常带走，控制器只负责上屏。
 *
 * - [ModImportFailureKind.IDENTITY]：身份类（sha 与内容不符、内层清单 packageId 与本次导入包
 *   不一致）——这类失败说明「文件不是这个包该有的那份」，玩家侧动作是重新拿包；
 * - [ModImportFailureKind.STORAGE]：其余（体积超限、内层 zip 形状非法、缺 classes.dex /
 *   manifest.json、暂存 IO 失败）——落在「存储/包形状」失败面。
 *
 * 失败语义是 **fail-closed on presence**（裁决 B）：包内出现 [CODE_MOD_ENTRY] 就代表作者
 * 声明「本包带代码产物」，任何一项校验失败都让整包导入失败（折成
 * [ModImportOutcome.Failed]），绝不「装上补丁但静默丢代码」——否则玩家会在
 * [ModDexLoader] 那边看到「盘上找不到该运行包的代码产物，请重新导入」的二次失败。
 */
internal class CodeArtifactRejectedException(
    val kind: ModImportFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 代码产物面（ADR-013 D1 / 导入端接线，2026-11）：从导入 zip 里认出 [CODE_MOD_ENTRY] 条目，
 * 校验「它真的是本包该有的那份 `.mod`」，再按 [ModImportMediaAssets] 同款的
 * stage/commit 两段式落盘。
 *
 * ## 为什么要有 stage 段
 *
 * 导入主链的既有纪律是「所有附加面先校验+临时落盘，产物四件套读回校验也过了才 commit」：
 * 临时落盘把「校验期失败」与「正式目录写入期失败」分开，失败时正式目录零半套产物。
 * 代码产物沿用同一形态：
 *
 * - [stage] 只读 zip 条目 + 校验 + 写系统临时文件（并做 sha 读回复核），**不碰** `mods/rt2`；
 * - [Staged.commit] 才调 [ModCodeStore.install]（写临时→`setReadOnly()`→rename 的原子口径
 *   由 store 自己负责，本类不复制第二份实现）；
 * - 任一段失败都 `discard()` 掉临时文件，失败面不留 `.tmp`。
 *
 * ## 为什么不在这里装载 dex
 *
 * 本类只做「把作者编译好的产物搬进内容寻址目录」。真正的装载闸（信任标记、引擎 API
 * surface、总闸开关）在 [ModDexLoader] / [ModDexGate]，本波不碰。
 */
internal object ModImportCodeArtifacts {

    /**
     * 已校验、已暂存的代码产物。持有系统临时文件与内容寻址所需的 sha256。
     *
     * [commit] 与 [discard] 都只允许调一次语义上的一次性动作；重复 commit 会因临时文件已被
     * 消费而抛 IOException（调用方按失败处理），重复 discard 是空操作（幂等）。
     */
    internal class Staged internal constructor(
        /** 系统临时目录里的暂存文件；`commit`/`discard` 之后不存在。测试用 internal 可见。 */
        internal val tmpFile: File,
        /**
         * 磁盘命名空间身份（来自编译产物，不是文件名反推）：`code.mod` 落盘地址
         * `code/<packageId>/` 与侧车文件名仍用它，**不因作者声明包名而改**。
         */
        val packageId: String,
        /**
         * 代码包的**运行期身份**：作者在 `eratw-pack.json` 声明的包名；未声明 / 未采信时
         * 回退内层 `manifest.json` 的 `packageId`（裁决 2）。它只进
         * [ModCodeIdentity] 侧车与装载期映射，不参与磁盘命名空间。
         */
        val runtimePackageId: String,
        /** 条目字节的 sha256 小写 hex（内容寻址点名用，也是 `install` 的期望值）。 */
        val sha256Hex: String,
        /** 条目字节数（上屏/诊断用）。 */
        val sizeBytes: Long,
    ) {

        /**
         * 落正式目录：读暂存字节 → [ModCodeStore.install]（内容寻址 + 只读 + 原子换名）→
         * 无论成败都删暂存文件。
         *
         * `expectedSha256` 传暂存期算出的值：install 会复算比对，暂存文件若在 stage 与 commit
         * 之间被改动（磁盘损坏 / 并发破坏），这里抛 [ModCodeIntegrityException]（由控制器折成
         * IDENTITY 失败面）。同内容重导 ⇒ 复用既有地址键文件、不新建。
         *
         * 产物落盘后立刻写 [ModCodeIdentity] 侧车（磁盘目录 → 运行期身份的映射）：侧车写失败
         * 抛 IOException 由控制器折 STORAGE 失败面——宁可整包拒导，也不留「代码装上了但装载端
         * 不知道它的运行期身份」的半态（产物是内容寻址文件，失败残留由 GC 回收）。
         *
         * 侧车写成功（身份闸都过）之后才清同包旧版本（[pruneSupersededArtifacts]）：顺序刻意
         * 放在最后——失败的导入不许动盘上既有版本；清理本身失败只 WARN，不改本次导入结局。
         *
         * @param deleteFile 旧版本单文件删除器（默认先摘只读再删）；单测注入失败器验证 WARN 分支
         */
        fun commit(
            store: ModCodeStore,
            rt2Dir: File,
            deleteFile: (File) -> Boolean = deleteArtifactFile,
        ) {
            try {
                store.install(packageId, tmpFile.readBytes(), sha256Hex)
                ModCodeIdentity.write(rt2Dir, packageId, runtimePackageId)
                pruneSupersededArtifacts(store, deleteFile)
            } finally {
                discard()
            }
        }

        /** 删暂存文件；幂等（文件已不存在即空操作）。 */
        fun discard() {
            if (!tmpFile.exists()) return
            tmpFile.setWritable(true)
            if (!tmpFile.delete()) tmpFile.deleteOnExit()
        }

        /**
         * 升级类导入的收尾清理（ADR-013 D1：同包目录只留最新版本）。
         *
         * 为什么必须有这一步：产物文件名 = sha256 前 16 位地址键，同包内容一变新旧文件就并存；
         * 装载端在「盘上多份产物且调用方没给期望 sha」时按 AmbiguousArtifact 拒载（生产链
         * ModCodeCarrierProvider 从不给）⇒ 玩家升级一次就永久不生效。清理只碰
         * [ModCodeStore.list] 返回的、已归属本包（[packageId]）的产物，绝不跨包。
         *
         * 为什么不用 [ModCodeStore.prune]：那是遍历 `code/` 下所有包目录的全局 GC（按引用集删），
         * 拿来做单包清理会把别的包的产物一起删——ModCodeStoreTest 的跨包用例已钉住该语义。
         *
         * 失败语义：只 WARN、绝不让导入失败——产物与身份侧车都已落盘，判失败只会让玩家连新版本
         * 都留不住；残留在设置页由 [ModCodeGateHintKind.MULTIPLE_VERSIONS] 解释，重导一次会再清
         * 一遍（幂等）。[sha256Hex] 的前 16 位地址键就是保留项（[ModCodeStore.list] 的地址键
         * 口径），用 startsWith 兼容地址键长度变化。
         *
         * @param store 本包的代码产物库（`rt2` 下的 `code/`）
         * @param deleteFile 单文件删除器（默认先摘只读再删：Windows 只读属性会挡 delete；
         *   单测注入失败器验证 WARN 分支）
         */
        private fun pruneSupersededArtifacts(store: ModCodeStore, deleteFile: (File) -> Boolean) {
            val siblings = runCatching { store.list(packageId) }.getOrElse { failure ->
                runCatching {
                    Log.w(SUPERSEDED_ARTIFACT_LOG_TAG, "读取既有代码产物失败，跳过旧版本清理：${describe(failure)}")
                }
                return
            }
            for (artifact in siblings) {
                if (sha256Hex.startsWith(artifact.sha256Hex, ignoreCase = true)) continue
                val deleted = runCatching { deleteFile(artifact.file) }.getOrDefault(false)
                if (!deleted) {
                    val line = "旧版本代码产物删除失败（保留待下次重导）file=${artifact.file.name}"
                    runCatching { Log.w(SUPERSEDED_ARTIFACT_LOG_TAG, line) }
                }
            }
        }
    }

    /**
     * 校验并暂存代码产物。
     *
     * 身份裁决（ADM：代码包身份一致化）：
     * 1. [packageId] 是磁盘命名空间身份（`kojo-<8hex>`），仍须过形态闸；
     * 2. [declaredPackageId] 是 `eratw-pack.json` 里作者声明的包名**原文**（未套形态闸，因为
     *    [PackMeta.packageId] 形态非法时会折成 null、与「未声明」不可区分）：声明了就必须
     *    形态合法（否则 STORAGE），它就是运行期身份；
     * 3. 未声明 ⇒ 回退内层 `manifest.json` 的 `packageId`（裁决 2）；
     * 4. 内层 `manifest.json` 的 `packageId` 必须**等于运行期身份**（不再与磁盘包名比）：
     *    形态非法 ⇒ STORAGE，合法但不一致 ⇒ IDENTITY。
     *
     * @param archiveZips 本次导入里所有 zip 的原始字节（⓪ 已整包过 [ModZipGuard] 闸）
     * @param packageId 磁盘命名空间身份（编译产物里的值，调用方已与 manifest 对过）
     * @param declaredPackageId 作者声明的运行期包名原文；null = 元数据缺失 / 未采信 / 未声明
     * @param metaDriftLog 双源元数据漂移的取证 sink（脚注见 [reportMetaDrift]）：默认写 logcat，
     *   单测注入收集器断言；sink 抛异常一律吞掉，**绝不改变导入结局**
     * @return `null` = 所有 zip 都没有 [CODE_MOD_ENTRY] 条目（普通口上包：零副作用、零 WARN）；
     *   否则返回已校验的 [Staged]
     * @throws CodeArtifactRejectedException 有该条目但校验不过（kind 见类 KDoc）
     * @throws IOException 暂存临时文件创建/写入失败（控制器折成 STORAGE）
     */
    fun stage(
        archiveZips: List<ByteArray>,
        packageId: String,
        declaredPackageId: String? = null,
        metaDriftLog: (String) -> Unit = ::logMetaDriftToLogcat,
    ): Staged? {
        if (!ModEnablement.isValidPackageId(packageId)) {
            throw reject(ModImportFailureKind.STORAGE, "代码产物的目标包身份不合法：$packageId")
        }
        requireDeclaredIdentity(declaredPackageId)
        val entries = locate(archiveZips)
        if (entries.isEmpty()) return null
        if (entries.size > 1) {
            // 多个 zip 各带一份 code.mod：无法判定哪份属于本次导入（混合选择常见），fail-closed。
            throw reject(ModImportFailureKind.STORAGE, "本次选中的多个 zip 都带 $CODE_MOD_ENTRY 条目，无法判定用哪一份")
        }
        val payload = entries.single().bytes
        requireSize(payload)
        val runtimePackageId = requireInnerArtifact(payload, declaredPackageId, archiveZips, metaDriftLog)
        return stageToTemp(payload, packageId, runtimePackageId)
    }

    /**
     * 在所有 zip 里找 [CODE_MOD_ENTRY] 条目的字节。先用 [ModZip.entryNames]（只读条目名、不读
     * 字节）筛出「真带该条目的 zip」，再对命中的 zip 走 [ModZip.entriesWhere] 取字节——绝大多数
     * 普通包一次都不会进第二次扫描。
     */
    private fun locate(archiveZips: List<ByteArray>): List<ModZip.Entry> =
        archiveZips.flatMap { zip ->
            if (CODE_MOD_ENTRY !in ModZip.entryNames(zip)) {
                emptyList()
            } else {
                ModZip.entriesWhere(zip) { name -> name == CODE_MOD_ENTRY }
            }
        }

    /** 体积闸：整份条目字节 ≤ [MAX_CODE_MOD_BYTES]（超限 = 作者的代码产物过不了本包形态）。 */
    private fun requireSize(payload: ByteArray) {
        val limit = MAX_CODE_MOD_BYTES / BYTES_PER_MIB
        if (payload.size.toLong() > MAX_CODE_MOD_BYTES) {
            throw reject(
                ModImportFailureKind.STORAGE,
                "代码产物 $CODE_MOD_ENTRY 体积 ${payload.size} 字节超过上限 $MAX_CODE_MOD_BYTES 字节（$limit MiB）",
            )
        }
    }

    /**
     * 内层 `.mod` 包形状与身份校验（ADR-013 D1）：合法 zip、有非空 `classes.dex`、有可解析的
     * `manifest.json` 且其 `packageId` == 本次导入包。
     *
     * 内层条目字节也走「读满上限即放弃」的读法（[readCapped]）：外层 zip 的单条目解压量上限
     * 只约束 `code.mod` 这一层的**解压**大小，内层 dex 若被压缩炸弹包着仍能无限膨胀，必须
     * 在内层再设一道闸。
     *
     * 校验**全部通过后**才做双源元数据漂移取证（[reportMetaDrift]）：身份不对的包直接拒收，
     * 其清单没有对账价值；漂移只写 canonical 日志，不参与这里的任何拒收判据。
     */
    /**
     * 声明值（`eratw-pack.json` 的 `packageId` 原文）形态闸：声明了但形态非法 ⇒ STORAGE。
     *
     * 为什么不静默回退内层：声明在场就代表作者指定了运行期身份，坏声明是**作者意图不可判**，
     * 猜成「没声明、用内层」会把配错的包装上去，装载端再拿错身份找死产物。
     */
    private fun requireDeclaredIdentity(declaredPackageId: String?) {
        if (declaredPackageId != null && !ModEnablement.isValidPackageId(declaredPackageId)) {
            throw reject(
                ModImportFailureKind.STORAGE,
                "$PACK_META_ENTRY_NAME 声明的 $CODE_ARTIFACT_PACKAGE_ID_KEY 形态不合法",
            )
        }
    }

    private fun requireInnerArtifact(
        payload: ByteArray,
        declaredPackageId: String?,
        archiveZips: List<ByteArray>,
        metaDriftLog: (String) -> Unit,
    ): String {
        val inner = scanInnerArtifact(payload)
        requireDex(inner.dex)
        val manifestBytes = requireManifest(inner.manifest)
        val innerPackageId = requireManifestIdentity(manifestBytes)
        val runtimePackageId = declaredPackageId ?: innerPackageId
        requireRuntimeIdentity(innerPackageId, runtimePackageId)
        reportMetaDrift(archiveZips, manifestBytes, metaDriftLog)
        return runtimePackageId
    }


    /**
     * 内层 `manifest.json` 身份校验：严格 JSON 解析（复用市场域 [MarketJson]，全仓零 JSON 库
     * 口径）→ 顶层对象 → [CODE_ARTIFACT_PACKAGE_ID_KEY] 是字符串 → 形态合法（与
     * [ModEnablement.isValidPackageId] 同源），返回其值供运行期身份裁决。
     *
     * 前四步失败归 STORAGE（坏档）：坏的产物或不是 `.mod` 包，作者侧问题。
     */
    private fun requireManifestIdentity(manifestBytes: ByteArray): String {
        val declared = declaredPackageIdOf(manifestBytes) ?: throw reject(
            ModImportFailureKind.STORAGE,
            "$CODE_ARTIFACT_MANIFEST_ENTRY 不是合法 JSON 对象或缺少字符串键 $CODE_ARTIFACT_PACKAGE_ID_KEY",
        )
        if (!ModEnablement.isValidPackageId(declared)) {
            throw reject(
                ModImportFailureKind.STORAGE,
                "$CODE_ARTIFACT_MANIFEST_ENTRY 的 $CODE_ARTIFACT_PACKAGE_ID_KEY 形态不合法",
            )
        }
        return declared
    }

    /**
     * 运行期身份裁决（裁决 3）：内层清单的 `packageId` 必须等于运行期身份 —— 后者 = 作者在
     * `eratw-pack.json` 的声明，未声明时回退内层自己的值（裁决 2，此时相等恒成立）。
     *
     * 合法但不一致归 IDENTITY：说明包里的代码产物与作者声明的包名配错了（拿错文件 / 包被
     * 二次编辑）。**不再与磁盘命名空间身份（`kojo-<8hex>`）比较**——后者是内容派生、玩家不可读
     * 的地址键，作者无从声明。
     */
    private fun requireRuntimeIdentity(innerPackageId: String, runtimePackageId: String) {
        if (innerPackageId != runtimePackageId) {
            throw reject(
                ModImportFailureKind.IDENTITY,
                "$CODE_ARTIFACT_MANIFEST_ENTRY 的 $CODE_ARTIFACT_PACKAGE_ID_KEY=$innerPackageId " +
                    "与本次导入的包 $runtimePackageId 不一致",
            )
        }
    }

    /** 从内层清单取作者自述包名；JSON 坏 / 顶层非对象 / 键缺失或非字符串都返回 null。 */
    private fun declaredPackageIdOf(manifestBytes: ByteArray): String? {
        val node = MarketJson.parseOrNull(String(manifestBytes, Charsets.UTF_8)) as? JsonObject ?: return null
        return (node.opt(CODE_ARTIFACT_PACKAGE_ID_KEY) as? JsonString)?.value
    }

    /** 身份面：内层清单形态非法（坏档）归 STORAGE；与运行期身份不一致（配错）归 IDENTITY。 */

    /**
     * 字节 → 系统临时文件（`Files.createTempFile`），写完读回复核 sha256 后返回 [Staged]。
     *
     * 读回复核是「内容寻址」链路的第一道自证：暂存期就发现磁盘写坏/并发破坏时，宁可在这里
     * 判 IDENTITY 失败，也不把坏字节留在正式目录等 [ModDexLoader] 装载期才发现。
     *
     * 失败清理走 finally（不 catch：任何异常都只负责删临时文件，再原样抛给控制器分档）。
     */
    private fun stageToTemp(payload: ByteArray, packageId: String, runtimePackageId: String): Staged {
        val temp = Files.createTempFile(CODE_ARTIFACT_TEMP_PREFIX, CODE_ARTIFACT_TEMP_SUFFIX).toFile()
        var staged = false
        try {
            temp.writeBytes(payload)
            val sha = sha256Hex(payload)
            if (sha256Hex(temp.readBytes()) != sha) {
                throw reject(
                    ModImportFailureKind.IDENTITY,
                    "代码产物暂存后 sha256 与条目内容不一致（疑似磁盘写入损坏）",
                )
            }
            staged = true
            return Staged(temp, packageId, runtimePackageId, sha, payload.size.toLong())
        } finally {
            if (!staged) {
                temp.setWritable(true)
                temp.delete()
            }
        }
    }

}

// 双源元数据漂移取证（ADR-013 D1：包根 `eratw-pack.json` ↔ 内层 `manifest.json`）。
// 检测与日志都放对象外，对象函数数停在 detekt TooManyFunctions 阈值内。

/** 漂移日志 tag（`Eratw` 前缀沿用 app 侧排障 tag 习惯；玩家不可见，只进 logcat WARN）。 */
private const val META_DRIFT_LOG_TAG: String = "EratwModImport"

/**
 * 漂移取证：包侧元数据（[packMetaOf] + [packRootFieldsOf]，与装载闸同一份解析口径）与内层清单
 * （[innerManifestFieldsOf]）对账，逐条写 [log]。
 *
 * 三条纪律：
 *
 * - **只取证、不裁决**：无论漂移多少条，导入结局与暂存字节都不变；
 * - **不复用控制器的解析结果**：控制器虽已解析过，但其文件不在本波授权面，故这里按同一入口
 *   重读（包根条目 ≤ [PACK_META_MAX_ENTRY_BYTES]，且只对带 `code.mod` 的包发生，代价可忽略；
 *   `entrypoint` / `apiVersion` 不在 [PackMeta] 采信面内，另按 [packRootFieldsOf] 取一次——与
 *   [ModCodeIdentity.rawPackMetaPackageId] 的既有双扫口径同源）；
 * - **sink 故障不外溢**：日志面异常一律吞掉（默认 sink 在 JVM 单测里本就会抛 "not mocked"）。
 *
 * @param archiveZips 本次导入的原始 zip 字节（包侧元数据来源）
 * @param manifestBytes 已通过身份校验的内层清单字节（注：坏 JSON 早已在身份阶段拒收，
 *   这里 `null` 只是防御）
 * @param log 单行日志 sink
 */
private fun reportMetaDrift(
    archiveZips: List<ByteArray>,
    manifestBytes: ByteArray,
    log: (String) -> Unit,
) {
    val fields = innerManifestFieldsOf(manifestBytes) ?: return
    val meta = packMetaOf(archiveZips)
    val packRoot = packRootFieldsOf(archiveZips)
    for (drift in packMetaDriftOf(meta, fields, packRoot)) {
        runCatching { log(packMetaDriftLogLine(drift)) }
    }
}

/** 默认 sink：logcat WARN；`Log` stub 在 JVM 单测抛 "not mocked"，runCatching 一并吞掉。 */
private fun logMetaDriftToLogcat(line: String) {
    runCatching { Log.w(META_DRIFT_LOG_TAG, line) }
}

/** 旧版本清理的日志 tag：与漂移取证同一条导入排障线（玩家不可见，只进 logcat WARN）。 */
private const val SUPERSEDED_ARTIFACT_LOG_TAG: String = "EratwModImport"

/**
 * 旧版本单文件默认删除器：先摘只读再删（Windows 只读属性会挡 delete），返回是否删掉。
 *
 * 放成属性而不是函数：本文件顶层函数数已到 detekt TooManyFunctions 的文件级阈值，删除器
 * 只有这一处实现，由 [ModImportCodeArtifacts.Staged.commit] 的默认参数使用。
 */
private val deleteArtifactFile: (File) -> Boolean = { file ->
    file.setWritable(true)
    file.delete()
}

// 内层 `.mod` 包的扫描/读取：与「包级」校验分开放顶层，控制器面只剩 stage/commit 两段式
// 与拒收分档（对象函数数也停在 detekt TooManyFunctions 阈值内）。

/** 内层 zip 的两个必查条目（null = 缺；形状闸分别判，避免一个函数里堆四种拒收）。 */
private class InnerArtifact {
    var dex: ByteArray? = null
    var manifest: ByteArray? = null
}

/**
 * 单趟扫内层 zip，只留下 dex 与 manifest 的字节（体积各自走 [readCapped] 闸 + 全程过
 * [ModZipGuard] 五道闸）。
 *
 * 内层 zip 没有 ⓪ 整包闸可借：外层闸只管 `code.mod` 条目解压后的**文件字节**（≤ 4 MiB），
 * 内层再压缩是新的压缩域，deflate 极限比率仍能把几 MiB 压缩字节放大到 GB 级——所以内层
 * 自挂一本账（[ModZipGuard.Progress]，分母 = 产物压缩字节数）。
 */
private fun scanInnerArtifact(payload: ByteArray): InnerArtifact {
    val inner = InnerArtifact()
    var failure: Throwable? = null
    try {
        ZipInputStream(ByteArrayInputStream(payload)).use { zin ->
            readInnerEntries(zin, inner, payload.size.toLong())
        }
    } catch (e: ZipException) {
        failure = e
    } catch (e: ModZipGuard.Rejected) {
        failure = e
    } catch (e: IOException) {
        failure = e
    }
    if (failure != null) {
        val reason = when (failure) {
            is ZipException -> "代码产物不是合法 zip 或已损坏"
            is ModZipGuard.Rejected -> "代码产物内层解压量越限（疑似压缩炸弹）"
            else -> "代码产物读取失败"
        }
        throw reject(ModImportFailureKind.STORAGE, "$reason：${describe(failure)}", failure)
    }
    return inner
}

/**
 * 非目标条目必须**显式带闸排水**（[ModZipGuard.readThrough]）：`getNextEntry` 的 closeEntry
 * 会把当前条目剩余数据静默全量解压丢弃，dex/manifest 各有 per-entry 上限、非目标条目没有
 * ——不计数的话炸弹藏在非目标条目里，dex/manifest 两道闸形同虚设。
 */
private fun readInnerEntries(zin: ZipInputStream, inner: InnerArtifact, archiveBytes: Long) {
    val progress = ModZipGuard.Progress(archiveBytes)
    while (true) {
        val entry = zin.nextEntry ?: break
        progress.beginEntry(entry.name)
        when (entry.name) {
            CODE_ARTIFACT_DEX_ENTRY -> inner.dex = readCapped(zin, entry.name, MAX_CODE_MOD_BYTES, progress)
            CODE_ARTIFACT_MANIFEST_ENTRY -> {
                inner.manifest = readCapped(zin, entry.name, MAX_CODE_ARTIFACT_MANIFEST_BYTES, progress)
            }
            else -> ModZipGuard.readThrough(zin, entry.name, progress, out = null)
        }
    }
}

private fun requireDex(dex: ByteArray?) {
    if (dex == null || dex.isEmpty()) {
        throw reject(ModImportFailureKind.STORAGE, "代码产物内缺少非空的 $CODE_ARTIFACT_DEX_ENTRY")
    }
}

private fun requireManifest(manifest: ByteArray?): ByteArray =
    manifest ?: throw reject(ModImportFailureKind.STORAGE, "代码产物内缺少 $CODE_ARTIFACT_MANIFEST_ENTRY")

/**
 * 读一个内层条目的全部字节，超过 [maxBytes] 立刻拒收（不把可能膨胀的炸弹读进堆）。
 *
 * 抛 [CodeArtifactRejectedException]（不是 IOException）——调用方的 `catch (IOException)`
 * 只兜「读坏」，超限是「坏档判据」而不是 IO 故障。
 */
private fun readCapped(
    zin: ZipInputStream,
    entryName: String,
    maxBytes: Long,
    progress: ModZipGuard.Progress,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(ENTRY_READ_CHUNK)
    while (true) {
        val read = zin.read(buffer)
        if (read < 0) break
        progress.onRead(entryName, read)
        if (out.size().toLong() + read > maxBytes) {
            throw reject(
                ModImportFailureKind.STORAGE,
                "代码产物内条目 $entryName 超过 ${maxBytes / BYTES_PER_MIB.coerceAtLeast(1)} MiB 级上限",
            )
        }
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
/** 统一拒收出口：拼好异常（kind 决定上屏通道）。 */
private fun reject(
    kind: ModImportFailureKind,
    message: String,
    cause: Throwable? = null,
): CodeArtifactRejectedException = CodeArtifactRejectedException(kind, message, cause)

/** 异常人话：消息缺省时退回类名，别把 null 拼进玩家可见文案。 */
private fun describe(error: Throwable): String = error.message ?: error.javaClass.simpleName
/** 单趟 sha256 小写 hex（与 [ModCodeStore] 的内容寻址口径同源；只服务本文件）。 */
private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val out = StringBuilder(digest.size shl 1)
    for (byte in digest) {
        val value = byte.toInt() and BYTE_MASK
        out.append(HEX_CHARS[value ushr HEX_SHIFT and HEX_MASK])
        out.append(HEX_CHARS[value and HEX_MASK])
    }
    return out.toString()
}
