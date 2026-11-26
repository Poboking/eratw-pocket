package com.eratw.port.android

import com.eratw.port.core.compile.ConstantKeywordResolver
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.frontend.sizeConsts
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ModPatchLoader
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModDx3AssetMap
import com.eratw.port.core.mod.PatchImportTranscoder
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.WriteGrantDeclaration
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.frontend.ErbPreprocessor
import com.eratw.port.core.frontend.RenameDictionary
import com.eratw.port.platform.storage.ModStore
import com.eratw.port.platform.storage.ResourcePackStore
import com.eratw.port.modapi.ModApi
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * 一份 SAF 选中的口上源文件（R7-C5 导入链的输入）。
 *
 * [bytes] 是 SAF 流的完整字节（`ModStore.MAX_MOD_BYTES` 之内的口上文件量级远小于它，
 * 整份进堆可接受；更大的文件在 [com.eratw.port.android.UriNames.readBounded] 的读取口
 * 就被拦下）。zip 整包也以本形态进链：[ModImportController.import] 里按扩展名展开成
 * 条目源（[ModZip.unpack]），[displayName] 随之换成 zip 内条目的相对路径。
 */
class PickedModFile(
    /** SAF 的 DISPLAY_NAME（导入链用它过 [ModStore] 的文件名校验与排序）。 */
    val displayName: String,
    /** 文件字节（文本按 [text] 解码；口上 ERB/ERH 是文本文件）。 */
    val bytes: ByteArray,
    /** 是否来自 zip 整包展开（[ModImportController.import] 内部置位；直选文件恒 false）。 */
    val fromArchive: Boolean = false,
) {
    /**
     * 源文本（只在导入链里解码一次）：走 [ModSourceCodec]——严格 UTF-8 试解、解不动
     * 回落 CP932（社区口上文件几乎全是 Shift-JIS 编码，无条件 UTF-8 会整份乱码）。
     */
    val text: String by lazy { ModSourceCodec.decode(bytes) }

    /**
     * 落盘留档名：zip 条目把相对路径切出主名（`sub/foo.ERB` → `foo.ERB`，`/` 与 `\`
     * 双支持——个别 Windows 打包器用反斜杠写条目路径），直选文件名无分隔符原样返回。
     * [ModStore] 的文件名合同禁分隔符，路径串只能活到这一步；切主名顺带消解 zip 条目
     * 名里的 `../` 穿越（穿越段在最后一个分隔符之前）。直选文件不切：SAF 的
     * DISPLAY_NAME 理论上不含分隔符，真出现就该让 [ModStore.validateName] 的防呆闸
     * 报红，而不是静默把 `bad/name.ERB` 改名成 `name.ERB`。
     */
    internal val diskName: String
        get() = if (fromArchive) displayName.substringAfterLast('/').substringAfterLast('\\') else displayName
}

/**
 * base 产物的编译上下文（[PatchCompiler] 的全部输入，R7-C1 定义的符号面）。
 *
 * 字段与 [PatchCompiler] 的构造参数一一对应：侧车 [metadata] + base 基线计数
 * [baseCounts] + base `program.erbc` 的 SHA-256（[baseSha256]，补丁头 `base-sha256`
 * 字段与 I3 身份校验同源）+ CSV 根（[csvDir]，灌解析期符号表；null = 没有 CSV 可灌，
 * 解析期只吃侧车与内建符号）+ base 函数签名指纹查询口（[baseSignatures]，R7-① 覆盖声明
 * 的签名来源；缺省恒空串 = 本上下文声明不了覆盖，撞名仍走 I6-② 硬拒）。
 */
class BaseCompileContext(
    val metadata: BootMetadata,
    val baseCounts: PatchCompiler.BaseCounts,
    val baseSha256: String,
    val csvDir: File?,
    /**
     * base 侧车签名段查询口（大写归一函数键 → 规范指纹串；无该键或无签名段 ⇒ 空串）。
     *
     * 生产实现 = [ModChainAssembly.signatureDirectoryOfFile]（有界尾读 `program.meta`）。
     * 覆盖声明必须有非空指纹：MODERN 档空指纹的覆盖在装配期被签名闸整链拒绝
     * （spec: SymbolCatalog.kt:213-237），届时的报错是启动砖化而不是导入页文案。
     */
    val baseSignatures: (String) -> String = { "" },
)

/**
 * base 编译上下文的读取口。抽象成接口只为单测：端到端单测注入手工构造的
 * [BaseCompileContext]（mini 侧车 + 合成计数），不需要真的准备 328 MB 的产物。
 *
 * **ADR-011 边界**：本接口的生产实现 [ProductionBaseCompileContextSource] 只被
 * [ModImportController.import]（用户导入动作的响应链）调用；启动路径
 * （[com.eratw.port.android.MainActivity] 的 onCreate / startGameOrDemo）与
 * 游戏循环不得触碰——那条纪律见 `docs/eratw-架构决策记录.md` 的 ADR-011。
 * R7-D1 任务①起导入动作的宿主是 [SettingsActivity]（设置页入口卡）。
 */
fun interface BaseCompileContextSource {
    /** 从游戏根读出 base 编译上下文。任何失败都由调用方折成导入失败上屏。 */
    fun read(gameRoot: File): BaseCompileContext
}

/**
 * 生产实现：`erbc.v1` 的 `program.erbc`（mmap 装载取六个基线计数）+ `program.meta`
 * （侧车符号上下文）+ 流式 SHA-256。
 *
 * mmap 装载出的 [com.eratw.port.core.code.Program] 是方法局部量：六个计数读完即弃，
 * 映射与惰性语句池的文件句柄交给 GC 回收（真机 POSIX 无碍；Windows 单测里临时目录
 * 随测试结束删除，句柄在 GC 前挂着不影响断言）。
 *
 * 侧车与产物**配对性**由调用方约束：导入链读的是游戏根里的现成产物，若玩家中途换包，
 * 侧车与产物错位会在编译期以明确的错误暴露（补丁引用的函数名/常量对不上号），
 * 不做静默补偿。
 */
object ProductionBaseCompileContextSource : BaseCompileContextSource {

    override fun read(gameRoot: File): BaseCompileContext {
        val dir = File(gameRoot, LoadPipeline.ARTIFACT_DIR)
        val programFile = File(dir, LoadPipeline.PROGRAM_FILE)
        val metaFile = File(dir, LoadPipeline.META_FILE)
        if (!programFile.isFile) {
            throw IOException("游戏产物缺失：${programFile.absolutePath}（先完成随包解包才能导入补丁）")
        }
        if (!metaFile.isFile) {
            throw IOException("侧车缺失：${metaFile.absolutePath}（program.meta 必须与 program.erbc 配对存在）")
        }
        // 流式摘要：program.erbc 量级 328 MB，绝不整份读进堆再 digest。
        val sha = fileSha256Hex(programFile)
        val program = ProgramLoader.loadMapped(programFile.toPath())
        val counts = PatchCompiler.BaseCounts(
            instructionCount = program.instructionCount,
            functionCount = program.functions.size,
            statementCount = program.statementCount,
            stringCount = program.stringCount,
            longCount = program.constantCount,
            exprCount = program.exprCount,
        )
        val metadata = BootMetadata.read(metaFile.toPath())
        return BaseCompileContext(
            metadata,
            counts,
            sha,
            File(gameRoot, "CSV"),
            // 覆盖声明的签名来源：侧车尾段的大写归一指纹查询口（读不到 ⇒ 恒空串 ⇒
            // 撞名维持 I6-② 硬拒，绝不产出装配期必被签名闸炸掉的半成品覆盖）。
            ModChainAssembly.signatureDirectoryOfFile(metaFile),
        )
    }

    /**
     * 文件 SHA-256（64 位小写十六进制，[com.eratw.port.core.patch.PatchArtifacts.sha256Hex] 的流式变体）：
     * 1 MiB 缓冲逐块喂，328 MB 的产物不整份进堆。
     */
    fun fileSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath()).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val sb = StringBuilder(64)
        for (b in digest.digest()) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}

/** 导入失败的类别（UI 报错页按它选标题；[UNEXPECTED] 是三态之外的内部兜底，禁静默）。 */
enum class ModImportFailureKind {
    /** 编译期拒绝（I6 四闸 / 解析失败 / 词法错误）。 */
    COMPILE,

    /** 补丁与 base 身份不符（I3；含落盘读回的头字段比对失败）。 */
    IDENTITY,

    /** 存储面失败（ModStore 拒收 / 目录建不出 / 落盘失败 / SAF 读取失败）。 */
    STORAGE,

    /** 未预期异常（防御兜底；文案带异常类名，绝不静默吞）。 */
    UNEXPECTED,
}

/** 导入成功详情（UI 成功态与测试断言面）。 */
data class ImportedPatchDetail(
    /** 落盘的模块容器文件名（`mods/rt2/<packageId>.erbm`）。 */
    val patchFileName: String,
    /** 补丁新增函数数。 */
    val functionCount: Int,
    /** 补丁段新增指令数（绝对口径 − base）。 */
    val instructionCount: Int,
    /** 补丁入口函数名（补丁头 `entry-function` 字段，大写归一）。 */
    val entryFunction: String,
    /** 本次导入保存的源文件名（mods 里的 `.ERB`/`.ERH`；zip 条目如实列出 zip 内相对路径，按文件名序）。 */
    val sourceFileNames: List<String>,
    /** 编译期非致命告警（C1 的 level 1 语义；不影响成败）。 */
    val parseWarnings: List<String>,
    /**
     * Mod 运行总闸未开时的状态说明（M2-4 裁定 7）：非 null 时成功页追加一行，
     * 说明「导入内容要开启总闸后、重开一局才生效」。
     */
    val modRuntimeOffHint: String? = null,
    /**
     * 本包里被声明为**覆盖**游戏原函数的函数数（R7-①；放在字段表末尾，位置式构造的
     * 既有调用点与用例逐位不变）。
     *
     * 与 [functionCount] 不是相加关系：覆盖函数同时也在补丁新增函数里（它们是模块自己的
     * 载荷），本数只是其中「与游戏原函数同名、按覆盖装载」的那一部分。原版对形态一致的
     * 口上就是「后装载者胜出」，这里把它显式报给玩家，不留静默遮蔽。
     */
    val overrideCount: Int = 0,
    /**
     * 本次导入落盘的**资源条目数**（Lane BH：导入包携带 `"<packageId>.assets/"` 资源）。
     *
     * 0 = 本次没有资源面：普通口上包、条目全部被跳过、限额整面拒绝、或资源落盘失败。
     * 跳过/拒绝/失败原因见 [resourceWarnings]（玩家可见文案，同时也合并进 [parseWarnings] 上屏），不静默。
     */
    val resourceEntryCount: Int = 0,
    /**
     * 本次导入的资源面告警（**玩家可见文案**，已按本次 [ModImportCopy] 语言档拼好）。
     *
     * 与 [parseWarnings] 的关系：本列表也会原样合并进 [parseWarnings]（成功页靠它逐行展示），
     * 单列出来是给测试与后续 UI 单独取用，不必靠 `[mod.resources]` 字符串前缀反解析——该取证
     * 前缀已从玩家面移除。
     */
    val resourceWarnings: List<String> = emptyList(),
    /**
     * 本次导入相对盘上同包旧产物的版本关系（BU：新增 / 重装 / 版本变化；放字段表末尾，
     * 位置式构造的既有调用点与用例逐位不变）。
     *
     * 判据在 [versionChangeOf]（导入前读旧 `mods/rt2/<packageId>.manifest` 的 version）。
     * 只做字符串相等比较：降级与升级同形，原样显示「旧 → 新」不拦。成功页取用点 =
     * [ImportedPatchDetail.versionChangeSummaryLine]（首次安装整行不出）。
     */
    val versionChange: ImportVersionChange = ImportVersionChange.FirstInstall,
    /**
     * 包级元数据的玩家可见告警（CH）：目前唯一来源 = 自述包名与包内资源目录前缀自相矛盾
     * （`PackMetaIgnore.PACKAGE_ID_PREFIX`），已按本次 [ModImportCopy] 语言档拼好。
     *
     * 与 [resourceWarnings] 同口径：本列表也会原样合并进 [parseWarnings]（成功页靠它逐行展示），
     * 单列出来是给测试与后续 UI 单独取用。其余未采信原因（缺失 / 坏档 / schema 不认识 / 版本
     * 形态非法）是作者侧打包问题、玩家无可行动，不进本表（保持静默）。放字段表末尾，位置式
     * 构造的既有调用点与用例逐位不变。
     */
    val packMetaWarnings: List<String> = emptyList(),
    /**
     * 引擎 API 下限告警（DC）：本包元数据声明的 min-api 高于本机 `ModApi.VERSION` 时非空，
     * 已按本次 [ModImportCopy] 语言档拼好（判据 = [engineApiGateWarning]）。
     *
     * 与 [resourceWarnings] / [packMetaWarnings] 同口径：本值也会原样合并进 [parseWarnings]
     * （成功页靠它逐行展示），单列出来是给测试与后续 UI 单独取用。**不改导入成败**：本包照常
     * 落盘并写进产物 manifest 的 `/6` min-api 段，真正的拒载在装配期（`ModChainAssembly`
     * 整链 fail-closed）。min-api 为 0 / 低于或等于当前引擎 / 元数据未采信时恒为 null。
     * 放字段表末尾，位置式构造的既有调用点与用例逐位不变。
     */
    val engineApiWarning: String? = null,
    /**
     * 本次导入是否为**纯资源包**（MODDX3-A3 一期：manifest-only 载体形态，无 `.erbm`）。
     *
     * true 时成功页改用 [ModImportCopy.assetPackSucceededDetailFormat]（函数数 / 指令数 /
     * 入口函数三个槽位对纯资源包没有语义），「源文件已保存」行整行不出（没有留档源）。
     * 放字段表末尾，位置式构造的既有调用点与用例逐位不变。
     */
    val assetPackOnly: Boolean = false,
    /**
     * 本次导入随包落盘的**角色模板条目数**（ERA-A2 收录面：`Chara*.csv` →
     * `mods/rt2/<packageId>.chara/`）。0 = 本次没有随包模板；数量只做测试与诊断取证，
     * 不进成功页文案（不新增玩家可见字面量，故不动三语文案表/形状闸）。
     */
    val charaTemplateEntryCount: Int = 0,
    /**
     * 本次导入带了代码产物、且**下次装载不会运行它**时的代码闸提示（task-179）。
     *
     * null = 普通口上包（没有代码产物）或代码现在就会生效；非 null 时成功页追加一行，
     * 说明「为什么装了却没生效」以及该去哪里处理（判据在 [modCodeGateHint]，句式在
     * [ModImportCopy.modCodeGateHintFormat]）。放字段表末尾，位置式构造的既有调用点与用例
     * 逐位不变。
     */
    val codeGateHint: ModCodeGateHint? = null,
    /**
     * 本包编译期要求的引擎面高于本机时的版本缺口读数（本 lane）：非 null 时成功页追加一行 amber，
     * 说明「这个包要求什么版本、本机是什么版本、本机不会装载它」。
     *
     * 判据不在这里重算：填值点 = [PackMeta.engineSurfaceGap]，它直接问装载期唯一 ABI 闸
     * [ModDexGate.verdict]（`compiledSurface > ModApi.VERSION` ⇒ 拒），本值只是把结论折成两个
     * 版本号；**不改导入成败**（包照常落盘，装载期照旧拒载）。取用点 =
     * [ImportedPatchDetail.engineTooOldLine]。放字段表末尾，位置式构造的既有调用点与用例逐位不变。
     */
    val engineTooOld: EngineSurfaceGap? = null,
    /**
     * 本次导入是否为**成品包**（ERAFIN-C3：zip 自带编译产物两件套 `<pkg>.manifest` +
     * `<pkg>.erbm`，跳过编译链直接落盘，见 [ModFinC3PackagedImport]）。
     *
     * true 时成功页改用 [ModImportCopy.packagedSucceededDetailFormat]（成品包没有「入口
     * 函数」读数，[succeededDetailFormat] 的入口槽对它没有语义），且「源文件已保存」整行
     * 不出（成品包没有留档源）。与 [assetPackOnly] 互斥（纯资源包无 `.erbm`，成品包必有）。
     * 放字段表末尾，位置式构造的既有调用点与用例逐位不变。
     */
    val packagedModuleOnly: Boolean = false,
)

/**
 * 成功页的「资源 N 条」行（Lane BH，`resourceSummaryFormat` 的唯一取用点）。
 *
 * `resourceEntryCount <= 0` ⇒ 返回 null = **整行不显示**：普通口上包没有资源面，加一行
 * 「资源 0 条」纯属噪声。拆分纯函数是为了让「0 条不出行 / N 条带读数」进 JVM 单测
 * （Compose 里的 if 本身测不到）；上屏点 = `ModImportUi.SuccessBlock`。
 */
internal fun ImportedPatchDetail.resourceSummaryLine(copy: ModImportCopy): String? =
    if (resourceEntryCount > 0) copy.fill(copy.resourceSummaryFormat, resourceEntryCount) else null

/**
 * 成功页的详情行（MODDX3-A3：按包形态选模板；ERAFIN-C3 加成品包形态）。
 *
 * 纯资源包（[ImportedPatchDetail.assetPackOnly]）没有函数 / 指令 / 入口三个读数，
 * 既有 [ModImportCopy.succeededDetailFormat] 的槽位对它全是噪声；改用
 * [ModImportCopy.assetPackSucceededDetailFormat]（manifest 名 + 资源条数）。
 * 成品包（[ImportedPatchDetail.packagedModuleOnly]）有函数/指令读数但没有「入口函数」
 * （入口是补丁的定位信息，成品包是零基线模块本体，见 [ModFinC3PackagedImport]）；
 * 改用 [ModImportCopy.packagedSucceededDetailFormat]（容器名 + 函数数 + 指令数）。
 * 拆纯函数与 [resourceSummaryLine] 同款理由：Compose 里的分支测不到，上屏点 =
 * `ModImportUi.SuccessBlock`。
 */
internal fun ImportedPatchDetail.successDetailText(copy: ModImportCopy): String =
    when {
        assetPackOnly -> copy.fill(copy.assetPackSucceededDetailFormat, patchFileName, resourceEntryCount)
        packagedModuleOnly -> copy.fill(
            copy.packagedSucceededDetailFormat,
            patchFileName,
            functionCount,
            instructionCount,
        )
        else -> copy.fill(
            copy.succeededDetailFormat,
            patchFileName,
            functionCount,
            instructionCount,
            entryFunction,
        )
    }

/**
 * 成功页的「本包要求更新引擎」提示行（本 lane）：非 null 时上屏一行 amber。
 *
 * 判据 = [ImportedPatchDetail.engineTooOld] 非 null（与 [ModDexGate.verdict] 同口径：包编译期面
 * 高于本机 `ModApi.VERSION` ⇒ 装载期整包拒载）。拆纯函数与 [resourceSummaryLine] 同款理由：
 * Compose 里的分支测不到，上屏点 = `ModImportUi.SuccessBlock`。
 */
internal fun ImportedPatchDetail.engineTooOldLine(copy: ModImportCopy): String? =
    engineTooOld?.let { gap ->
        copy.fill(copy.engineTooOldWarningFormat, gap.required, gap.current)
    }

/**
 * 包元数据 → ABI 版本缺口读数（本 lane 的导入面投影）。
 *
 * **判据不在这里**：直接问装载期唯一 ABI 闸 [ModDexGate.verdict]，只把「被判拒」折成玩家可读的
 * 两个版本号（[EngineSurfaceGap.required] = 包要求、[EngineSurfaceGap.current] = 本机引擎）；
 * 未声明 / 不高于本机 ⇒ null。因此本函数与装载期判定不可能分叉：`verdict` 放行就一定 null，
 * 判拒就一定两个数齐全。
 *
 * @param engineApi 本机引擎的 mod-api 版本（缺省 [ModApi.VERSION] = 本 APK 编译进的内核面）
 */
internal fun PackMeta.engineSurfaceGap(engineApi: Int = ModApi.VERSION): EngineSurfaceGap? =
    if (ModDexGate.verdict(this, engineApi).isAllowed) {
        null
    } else {
        compiledSurface?.let { EngineSurfaceGap(required = it, current = engineApi) }
    }

/** 导入结果（[ModImportController.import] 的唯一出口；失败不抛，折成 [Failed] 上屏）。 */
sealed interface ModImportOutcome {

    /** 导入成功：补丁产物已落 `mods/`。 */
    data class Imported(val detail: ImportedPatchDetail) : ModImportOutcome

    /** 导入失败（UI 报错页原样展示 [message]，含函数名 + 文件:行的编译错误不截断）。 */
    data class Failed(val kind: ModImportFailureKind, val message: String) : ModImportOutcome
}

/**
 * 一批选中包的导入结果（task-178：逐包顺序导入、坏包隔离）。
 *
 * [items] 逐项保留**选中顺序**（分组口径见 [ModImportController.importBatch]）：一个 zip
 * 整包 = 一项，多只直选散装源合成一项。每项的 [Item.outcome] 是该项自己的事务结果——
 * 好包是 [ModImportOutcome.Imported]（产物已落盘），坏包是 [ModImportOutcome.Failed]
 * （该项已独立回滚，后续项照跑）。
 *
 * [batchFailure] 只装「批级」失败：空选择（无项可跑）或批量循环本身异常；此时已完成项
 * 仍原样留在 [items]（绝不因循环收尾失败丢掉已经装好的包），UI 在逐项行之后追加这段失败。
 */
data class ModImportBatchOutcome(
    /** 逐项结果，顺序 = 选中顺序。 */
    val items: List<Item>,
    /** 批级失败（空选择 / 循环本身异常）；null = 循环正常跑完。 */
    val batchFailure: ModImportOutcome.Failed? = null,
) {
    /**
     * 一个批次项：一个 zip 整包，或一组直选的散装源文件（合成一个口上包编译）。
     *
     * [zipName] 是玩家可读的项名：zip 用 SAF 显示名；散装源组按 [ModImportCopy.nameListSeparator]
     * 连名（与成功页源文件清单同一种连法，不另造分隔符）。
     */
    data class Item(
        /** 玩家可读的项名（zip 文件名 / 散装源名清单）。 */
        val zipName: String,
        /** 该项自己的事务结果（成功含 [ImportedPatchDetail]；失败含人话消息）。 */
        val outcome: ModImportOutcome,
    )

    /** 成功项数（每个成功项 = 一个已独立提交的包）。 */
    val succeededCount: Int get() = items.count { it.outcome is ModImportOutcome.Imported }

    /** 失败项数。 */
    val failedCount: Int get() = items.size - succeededCount
}

/**
 * R7-C5 导入链（CONTRACT-R7-C §2/C5）：SAF 选 `.ERB`/`.ERH`/口上 zip 包 → zip 解包成
 * 条目源（[ModZip]）→ [ModStore.save] 源留档 → [PatchCompiler] 设备端编译 →
 * 补丁产物落 `mods/` 的 `.patch`（与 [ModPatchLoader.PATCH_EXTENSION] 的装载扫描口径对齐）。
 *
 * 源文本解码（[ModSourceCodec]）：严格 UTF-8 试解、解不动回落 CP932——社区口上文件
 * 几乎全是 Shift-JIS 编码（原版 `EncodingHandler.cs:37-40` 同口径）。
 *
 * ## ADR-011：编译只发生在导入动作里
 *
 * 本类是**全仓唯一的 PatchCompiler 调用点**：编译、base 产物装载（mmap）、侧车解析
 * 全部发生在 [import]（用户点击导入后的后台线程动作）里。[SettingsActivity] 的导入动作
 * （R7-D1 任务①起的宿主）与 [com.eratw.port.android.GameHost] 的装载链之外，任何启动
 * 路径（[com.eratw.port.android.MainActivity] 的 onCreate / startGameOrDemo）不得引用
 * 本类与 [PatchCompiler]——M2-4 起导入链**进口即转码**：窗口补丁编译产物经
 * [PatchImportTranscoder] 转成零基线 Module（`mods/rt2/<packageId>.erbm`）与 RT2
 * manifest（同目录单向配对），运行期只走模块链（[ModChainAssembly] → linker → plan
 * → 会话）；`kojo-imports.patch` 并集产物随窗口模型退役。L5 起存量旧 `.patch` 在 ⑤′
 * 相位用盘上留档源二次进入本链转成同形三件套，成功且读回校验通过后才删旧档
 * （[LegacyPatchMigrator]，CONTRACT-RT2-M2LEGACY）。
 *
 * ## 全或无（不变量 I5）
 *
 * 本次导入产物落盘与读回校验是主流程的最后一步：编译失败 / 转码拒绝 / 编码失败都不落
 * 产物（源 `.ERB` 照契约顺序先落盘留档，那是编译输入，不是产物）。容器与 manifest 各自走
 * tmp+rename；落盘失败时临时文件已清理，同名旧产物原样保留。其后的 ⑤′ 存量迁移是
 * fail-closed 的独立相位：失败只保留旧 `.patch` 并上警告，绝不回退本次导入的 [ModImportOutcome.Imported]。
 *
 * ## 三态失败（契约 C5 出口判据①）
 *
 * 每一步的异常**分步接住**并折成 [ModImportOutcome.Failed]：
 * 1. 源文件落盘（[ModStore.save]）→ STORAGE；
 * 2. base 上下文读取（[BaseCompileContextSource.read]）→ STORAGE；
 * 3. 编译（[PatchCompiler.compile] 的 [PatchCompiler.PatchCompileException] / require）→ COMPILE；
 * 4. 转码（[PatchImportTranscoder.transcode] 的 require）→ COMPILE；
 * 5. 容器 / manifest 编码与落盘 + 写读往返校验 → COMPILE / STORAGE / IDENTITY。
 *
 * 未预料的异常走 [ModImportFailureKind.UNEXPECTED]（带异常类名，禁静默）。
 *
 * ## 资源面（导入包可携带，Lane BH）
 *
 * 口上 zip 除了 `.ERB`/`.ERH` 源，还可以带**模块资源目录**：条目名形如
 * `"<来源目录名>.assets/"`（来源目录名 = 模块包作者源码里的 `PACKAGE_ID`，例如
 * `modgen pack-mod` 的产物），相对路径原样当资源名，落点一律**重挂**到本端算出的
 * `mods/rt2/<packageId>.assets/`（`packageId` = 本次**编译产物**的包标识，等于 manifest
 * 里的值，绝不从文件名/条目名反推；读取侧 = `core-vm` 的 `ModResourceStore`，名字规则与
 * 4 MiB / 16 MiB 上限同源，作者可用 `tools/modgen` 的 `check-mod` 自检）。
 *
 * - 来源目录名 / 相对资源名非法：**跳过 + WARN**（进 [ImportedPatchDetail.parseWarnings]，
 *   不静默、不让整包失败；重挂不放松名字校验，`..`/绝对路径/控制字符仍被挡）；
 * - **重挂后跨来源同名冲突：整包拒导**（STORAGE 失败面点名冲突资源名与两个来源目录名，
 *   绝不后写盖先写）；
 * - 发生重挂时汇总一条 WARN 说明来源目录名；
 * - 超单文件/超单包上限：**整面资源不导入** + WARN（比运行期更严，见 [ModImportAssets]）；
 * - 原子落盘 + 重复导入按「包级替换」（新资源面整目录替换旧面）；任何失败零半套残留；
 * - **纯资源包导入（MODDX3-A3 一期解锁）**：0 个 `.ERB`/`.ERH` + ≥1 条可收下（合法名 +
 *   限额内）的 `<来源目录名>.assets/` 条目 + zip 根带可信 `eratw-pack.json`（schema=1 且
 *   `packageId` 合法）的 zip，按**载体形态**（manifest-only，无 `.erbm`）单独成包导入：
 *   manifest 由包元数据派生（`RT2MANIFEST/4`，零 [ManifestCodec] 改动；`packageId` =
 *   [assetPackageIdFor]，显示名 = 自述包名），资源照旧重挂到 `mods/rt2/<packageId>.assets/`。
 *   载体在链接期被五步链跳过（`ModChainAssembly` 单向配对只压「每 `.erbm` 必须有 manifest」
 *   这一个方向）；孤儿 GC 以「`.sources` 空名单」识别载体形态（普通导入的 `.sources` 恒
 *   非空，导入崩溃残留不可能命中此形）。三判据缺一（无可收下资源 / 无可信包元数据）维持
 *   既有失败面（`zipNoEntriesFormat` / 素材包指路），fail-closed；成品包导入（zip 自带
 *   `.manifest` + `.erbm`）仍是另一份导入合同，另行立项。
 *
 * @param modStore 源 `.ERB` 的存储面（platform 已建好的 [ModStore]，只调用不修改）。
 * @param baseSource base 编译上下文读取口（生产 = [ProductionBaseCompileContextSource]）。
 * @param gameRoot 游戏根（[ModStore.modsDirFor] 的同源口径；base 上下文也从这里读）。
 */
class ModImportController(
    private val modStore: ModStore,
    private val baseSource: BaseCompileContextSource,
    private val gameRoot: File,
    /**
     * 调用方（UI 线程）读好的第三方代码装载总闸快照（task-179），仅用于成功页的代码闸提示
     * （[ImportedPatchDetail.codeGateHint]）。缺省 true = 读不到就当开：宁缺一条提示，也不许
     * 误报「总闸关着」把玩家引到错误的修复动作上。放在构造器而不是 [import] 形参，是为了
     * 不改那条已入 detekt baseline 的长签名（改签名会让整条存量违例重新上闸）。
     */
    private val codeGateEnabled: Boolean = true,
) {

    /**
     * 执行一次导入：排序 → 源落盘 → 读 base 上下文 → 编译 → 身份预检 → 产物落盘
     * → ⑤′ 存量 `.patch` 迁移（删除相位）。主链任何失败都折成 [ModImportOutcome.Failed]
     * （不向上抛；调用方是 Android 线程，拿到结果直接渲染）；⑤′ 迁移失败是独立相位，
     * 只保留旧档并上警告，不回退本次导入成功（[LegacyPatchMigrator]）。
     *
     * [copy] 是壳层在起线程前取好的当前语言文案表（照 `UpdateCopy` 的口径：纯层不碰
     * SharedPreferences，语言随调用进线程）；缺省只为所有权外的既有单测留（对拍
     * [ModImportCopy.ZH]，消息逐字同形）。
     */
    fun import(
        files: List<PickedModFile>,
        copy: ModImportCopy = ModImportCopy.ZH,
        /** 调用方（UI 线程）读好的 Mod 运行总闸状态；仅用于成功页状态说明。 */
        modRuntimeEnabled: Boolean = true,
        /**
         * 存量 `.patch` 迁移相位的递归闸：迁移器二次进入本链时置 false，防止无限递归与
         * 重复删除。生产调用恒缺省 true（导入期自动迁移）；只有 [LegacyPatchMigrator] 的
         * `writePackage` 回调显式传 false。见 ⑤′ 段（CONTRACT-RT2-M2LEGACY）。
         */
        legacyMigrationEnabled: Boolean = true,
        /**
         * 包清单的可选显式收窄声明（`pack.json` 的 `overrideFunctions`；市场面透传，
         * SAF 直选无清单即缺省 `null`）。`null` = 未声明 ⇒ 覆盖名单按「模块函数名 ∩
         * base 函数名（事件名除外）」自动推导；非 `null` = 声明须与实际可覆盖集逐一
         * 相等，否则本次导入 COMPILE 失败（人话点名差在哪）。语义见
         * [PatchImportTranscoder.OverrideInputs] 的 `allowList`。
         */
        declaredOverrides: List<String>? = null,
    ): ModImportOutcome {
        if (files.isEmpty()) {
            return ModImportOutcome.Failed(ModImportFailureKind.STORAGE, copy.noFilesPicked)
        }
        // ---- ⓪ zip 整包展开（2026-11：SAF 现在也收口上 zip 包，解出 .ERB/.ERH 条目当普通源）----
        // 条目 displayName 保留 zip 内相对路径（排序与编译报错定位都用它），落盘留档时才切主名。
        // 原始 zip 字节同时留一份：编译产出 packageId 之后，资源面（[ModImportAssets]）还要
        // 从同包条目里捞 `"<packageId>.assets/"` 前缀的条目（Lane BH，源包顺带资源）。
        val flat = ArrayList<PickedModFile>(files.size)
        val archiveZips = ArrayList<ByteArray>()
        // ERA-A2 收录面：随包角色模板条目（`Chara*.csv`）在此分流，单独落到
        // `mods/rt2/<packageId>.chara/`，绝不进 `flat` 的编译源面（CSV 不是 ERB 语句）。
        val charaTemplates = ArrayList<ModZip.Entry>()
        // 空解包（0 条 .ERB/.ERH）的 zip 延后处置（MODDX3-A3）：它们可能是纯资源包
        // （pack.json + `.assets/` 条目，见 [importWithoutSources] / [importPureAssetPack]），
        // 也可能与口上源混合选择（`.assets/` 条目作本包资源贡献者）。立刻失败会堵死这两条路，
        // 所以先记下、等整个选择的源面定型后再判（失败面逐字不变，见 [emptyUnpackFailure]）。
        val emptyUnpackZips = ArrayList<PickedModFile>()
        for (f in files) {
            if (!ModZip.isZipName(f.displayName)) {
                flat.add(f)
                continue
            }
            archiveZips += f.bytes
            val unpacked = try {
                ModZip.unpack(f.bytes)
            } catch (e: ModZipGuard.Rejected) {
                // 不可信 zip 被闸拒（bomb / 洪泛 / 重名）：单独一类文案点名拒因与读数，
                // 不能与「加密/损坏」的泛化失败面混在一起——玩家要能分辨「包被拒」还是
                // 「包坏了」。拒绝发生在任何落盘之前，盘上零半套产物。
                return failed(ModImportFailureKind.STORAGE, ModZipGuard.rejectMessage(copy, f.displayName, e), e)
            } catch (e: Exception) {
                // 闸门之外的解包失败：加密条目（zip 标准加密位，JDK 不解密）与坏/截断的
                // deflate 流都在读取期抛 ZipException；非 zip 字节在 nextEntry 即抛。统一
                // 归「解包失败」面（文案本就写着「可能已加密或文件损坏」）。
                return failed(
                    ModImportFailureKind.STORAGE,
                    copy.fill(copy.zipUnpackFailedFormat, f.displayName, detailText(e)),
                    e,
                )
            }
            // unpack 已把 ERB/ERH 与 `Chara*.csv` 一起收进来（共用同一趟闸扫描），这里
            // 按 fail-closed 谓词再分一次流：模板进 charaTemplates，只有 ERB/ERH 进 flat。
            // 「空解包」判定仍只看 ERB/ERH（A3 纯资源包载体逻辑的调用前提，逐字不变）。
            val erbEntries = ArrayList<ModZip.Entry>()
            for (entry in unpacked) {
                if (isCharaTemplateCsv(entry.name)) charaTemplates += entry else erbEntries += entry
            }
            if (erbEntries.isEmpty()) {
                emptyUnpackZips += f
                continue
            }
            for (entry in erbEntries) flat.add(PickedModFile(entry.name, entry.bytes, fromArchive = true))
        }
        // MODDX3-A3：消费 ⓪ 延后处置的空解包 zip（0 条 .ERB/.ERH）。
        // - flat 非空（本次有真实口上源）：这些 zip 的 `.assets/` 资源已随 archiveZips 进入
        //   ④′ 资源面扫描（[ModImportAssets.stageSafely] 扫全部 archiveZips），作为本包资源
        //   贡献者被重挂收下；不再单独判失败（混合选择语义）。
        // - flat 为空（本次选的全是空解包 zip）：逐个尝试纯资源包载体形态，认下第一个可信
        //   候选（[importWithoutSources]）；一个都不认 ⇒ 维持既有失败面（[emptyUnpackFailure]
        //   的素材包指路 / zipNoEntries），fail-closed。
        if (flat.isEmpty() && emptyUnpackZips.isNotEmpty()) {
            // ERAFIN-C3：成品包（编译产物态）先判——zip 根级 <pkg>.manifest + 同名 <pkg>.erbm
            // 在场 = 跳过编译链直接落 mods/rt2 三件套（合同与 fail-closed 红线见
            // ModFinC3PackagedImport）。不命中维持既有路径（纯资源包 / 既有失败面）逐位不变。
            for (packagedZip in emptyUnpackZips) {
                val packaged = ModFinC3PackagedImport.packagedPairOf(packagedZip.bytes)
                if (packaged != null) {
                    return ModFinC3PackagedImport.importPackagedModule(
                        packagedZip,
                        packaged,
                        File(modStore.modsDir, ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME),
                        copy,
                        modRuntimeEnabled,
                    )
                }
            }
            // 代码产物面 fail-closed：包内声明带 `code.mod` 却没有可编译的口上源 ⇒ 无法确定包身份，
            // 既装不了代码产物也不能静默丢掉（与 ④‴ 同口径，不新增玩家可见字面量）。
            if (emptyUnpackZips.any { CODE_MOD_ENTRY in ModZip.entryNames(it.bytes) }) {
                val rejected = CodeArtifactRejectedException(
                    ModImportFailureKind.STORAGE,
                    "包内带有 $CODE_MOD_ENTRY 条目但没有可编译的口上源（ERB/ERH），无法确定代码产物归属",
                )
                return failed(
                    rejected.kind,
                    copy.fill(copy.codeArtifactStageFailedFormat, detailText(rejected)),
                    rejected,
                )
            }
            return importWithoutSources(emptyUnpackZips, archiveZips, copy, modRuntimeEnabled)
        }
        // 多文件的装载序 = 文件名序（C1 TODO-⑥：调用方按文件名排序喂入）；
        // 与 ModStore.compareNames 同源，保证「同一目录两张面同序」的三方口径。
        // compareNames 对路径串同样适用：zip 条目的 `/` 参与逐字符比较，同目录条目
        // 仍按文件名聚在一处（装载序只要求确定性，路径段不破坏这一点）。
        val sorted = sortSourceFiles(flat)

        // ---- ⓪′ 落盘名去重（同名取先 + 告警）----
        // zip 条目名可能带目录层级（`sub/foo.ERB`），落盘留档只留主名（[ModStore] 的
        // 文件名合同禁分隔符）。两份条目主名相同时取**先**到的一份（装载序在前），
        // 后到者跳过并出告警——同一份导入里哪份是「对的」无法判定，静默覆盖更糟。
        val dedupWarnings = ArrayList<String>()
        val deduped = ArrayList<PickedModFile>(sorted.size)
        val seenDiskNames = HashSet<String>()
        for (f in sorted) {
            val diskName = f.diskName
            if (!seenDiskNames.add(diskName)) {
                dedupWarnings += copy.fill(copy.dedupSkippedWarningFormat, f.displayName, diskName)
                continue
            }
            deduped.add(f)
        }

        // ---- ① 源 .ERB/.ERH 留档（契约顺序：save 在 compile 前；扩展名/大小校验在 ModStore）----
        try {
            for (f in deduped) modStore.save(f.diskName, f.bytes)
        } catch (e: Exception) {
            return failed(ModImportFailureKind.STORAGE, copy.fill(copy.saveSourceFailedFormat, detailText(e)), e)
        }

        // ---- ②′ 包级元数据：作者自述版本号 + 可选引擎下限（pack-mod 写的固定名条目；缺/坏档回落 "1"）----
        // 读点在编译前：version / min-api 都要随编码落进产物 manifest；元数据本身不落盘。
        val packMeta = packMetaOf(archiveZips)
        // CH：包元数据自述包名与包内资源目录前缀对不上是**玩家可观测的包内事实**（玩家能去问
        // 作者），升级成成功页 WARN；其余未采信原因（缺失/坏档/schema/版本形态）是作者侧问题、
        // 玩家无可行动，保持静默（只在 PackMeta.ignored 留诊断）。本次仍按内容安装、不拦导入。
        val packMetaWarnings = packMeta.packageIdPrefixWarning(copy)?.let { listOf(it) } ?: emptyList()
        // DC：作者声明的引擎下限高于本机 API ⇒ 成功页提前告知「装上后装配期会被拒载」。
        // 只加一行 WARN，不参与成败判定；与 parseWarnings 同一通道上屏。
        val engineApiWarning = packMeta.engineApiGateWarning(copy)
        // 本 lane：包编译期要求的引擎面比本机新 ⇒ 导入成功但装载期会被整包拒载。判据只读现成事实
        // （[PackMeta.compiledSurface] + [ModApi.VERSION]，经 [ModDexGate.verdict] 同一条闸），
        // 不重算 ABI、不改任何判性；只投影成成功页一行 amber 提示。
        val engineTooOld = packMeta.engineSurfaceGap()

        // ---- ②③④ 编译 + 转码 + 编码：源 → 零基线 Module + RT2 manifest ----
        // 三段合成 [compileToModule] 一处实现：导入（本函数 ⑤ 落盘）与重编
        // （[ModRecompiler] 原位原子替换）走同一条编译/转码/编码链，不另写编译器调用。
        // 失败种类经 [ModCompileException] 折回这里的收口（kind → 文案表条目）。
        val built = try {
            compileToModule(deduped, copy, declaredOverrides, packMeta.version, minApi = packMeta.minApi)
        } catch (e: ModCompileException) {
            return failed(e.kind, e.message, e)
        }

        // ---- ④′ 资源面预校验 + 临时落盘（Lane BH / 模块包重挂：导入包携带
        //      `"<来源目录名>.assets/"` 资源，重挂到本包）----
        // packageId 一律以编译产物为准（manifest 里的值 = built.packageId，二者不符说明产物链
        // 自身坏了，按 IDENTITY 拒收，绝不用文件名反推）。资源先写进 rt2 下的临时目录，等 ⑤ 的
        // 产物读回校验也过了才整体 swap 到正式名（[ModImportAssets] 的原子口径）。
        val importedDescriptor = ManifestCodec.decode(built.manifestBytes)
        val packageId = importedDescriptor.packageId
        if (packageId != built.packageId) {
            return failed(
                ModImportFailureKind.IDENTITY,
                copy.fill(copy.verifyWrittenFailedFormat, "manifest packageId=$packageId 与产物名 ${built.packageId} 不一致"),
                IllegalStateException("manifest packageId 与编码产物不一致"),
            )
        }
        val rt2Dir = File(modStore.modsDir, ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME)
        // BU：先读盘上同包旧 manifest 的 version（缺失 / 解码失败 ⇒ null = 首次安装，不拦导入），
        // 再按字符串相等判三态；读数在成功页由 ImportedPatchDetail.versionChangeSummaryLine 上屏。
        // 读点选在 ⑤ 落盘前：此刻旧四件套还是完整的，落盘后旧 manifest 已被本次产物原地替换。
        val versionChange = versionChangeOf(
            installedManifestOf(rt2Dir, packageId)?.version,
            importedDescriptor.version,
        )
        val assetPlan = try {
            ModImportAssets.stageSafely(archiveZips, packageId, rt2Dir, copy)
        } catch (e: ModImportAssets.AssetConflictException) {
            // 重挂后跨来源同名 ⇒ 整包拒导（既有 STORAGE 失败上屏通道，文案已点名冲突条目）；
            // 此时仅资源扫描相位失败，源留档已在 ① 落过（与其它失败相位同口径），无半套产物。
            return failed(ModImportFailureKind.STORAGE, e.message ?: copy.unknownStorageError, e)
        }
        val assetWarnings = ArrayList(assetPlan.warnings)

        // ---- ④″ 旁挂媒体资源面（ADR-014 S4）：与 `.assets` 同相位扫描、同口径 tmp→swap ----
        // 只消费元数据声明的单个 <dir>（`resources/mod/<dir>/…` + `sound/mod/<dir>/…`）；未声明
        // ⇒ [ModImportMediaAssets.stage] 立即返回空 Plan（老包路径逐位不变，零副作用）。
        // 跨包同名 <dir> ⇒ 整包拒导（与 `.assets` 冲突同口径，文案点名两个 packageId 与目录名）。
        val mediaPlan = try {
            ModImportMediaAssets.stageSafely(
                archiveZips,
                ModImportMediaAssets.MediaTarget(packageId, packMeta.resources, gameRoot, rt2Dir),
                copy,
            )
        } catch (e: ModImportMediaAssets.MediaDirConflictException) {
            assetPlan.staged?.discard()
            return failed(ModImportFailureKind.STORAGE, e.message ?: copy.unknownStorageError, e)
        }
        val mediaWarnings = ArrayList(mediaPlan.warnings)

        // ---- ④‴ 代码产物面（ADR-013 D1）：与资源面同相位 stage，只校验 + 系统临时目录暂存 ----
        // 包内没有 `code.mod` ⇒ null（普通口上包零副作用：不建 code/、不加 WARN）。
        // 有该条目 = 作者声明本包带编译好的运行包 ⇒ fail-closed on presence：任何校验失败都让
        // 整包拒导，不许「补丁装上、代码静默丢掉」（否则玩家会在 [ModDexLoader] 侧看到
        // 「盘上找不到该运行包的代码产物，请重新导入」的二次失败）。
        val codeArtifactPlan = try {
            // 运行期身份 = 作者在 eratw-pack.json 声明的包名；未声明/未采信 ⇒ stage 内部回退
            // 内层 manifest.json 的 packageId。磁盘命名空间身份（built.packageId = kojo-<8hex>）
            // 照旧只用于落盘地址，不参与代码包身份裁决（ADM：代码包身份一致化）。
            ModImportCodeArtifacts.stage(archiveZips, built.packageId, rawPackMetaPackageId(archiveZips))
        } catch (e: CodeArtifactRejectedException) {
            assetPlan.staged?.discard()
            mediaPlan.staged?.discard()
            return failed(e.kind, copy.fill(copy.codeArtifactStageFailedFormat, detailText(e)), e)
        } catch (e: IOException) {
            assetPlan.staged?.discard()
            mediaPlan.staged?.discard()
            return failed(
                ModImportFailureKind.STORAGE,
                copy.fill(copy.codeArtifactStageFailedFormat, detailText(e)),
                e,
            )
        }

        // ---- ⑤ 产物落盘（mods/rt2 单向配对）+ 读回校验 ----
        // 同集合重导 = 同 packageId = 同名文件原地替换；异集合并存为不同包。
        // 旧 .patch 的退役不再在这一步直接删（历史实现把删除塞在落盘 try 里、读回校验之前）；
        // 移到下面的 ⑤′ 迁移相位：用盘上留档源重走本链产出同形三件套，成功且读回校验通过
        // 之后才删，失败则旧档原样保留、逐条警告、本次导入照常成功（L5 / RT2-M2LEGACY）。
        val erbmName = built.packageId + ModPackageInventory.MODULE_EXTENSION
        val manifestName = built.packageId + ModPackageInventory.MANIFEST_EXTENSION
        val sourcesName = built.packageId + ModPackageInventory.SOURCES_EXTENSION
        val fingerprintName = built.packageId + ModPackageInventory.FINGERPRINT_EXTENSION
        val sourcesBytes = ModPackageInventory.sourcesBytes(deduped.map { it.diskName })
        // 内容指纹侧车（Lane O）：与 provenance 同源同序（同一份 deduped 的留档名 + 字节），
        // 供 [ModRecompiler.isUpToDate] 做内容级比对；`.sources` 线格式冻结，不塞哈希。
        val fingerprintBytes = ModSourceFingerprint.bytesFor(deduped)
        // ERA-A3 二期 A：pack.json overrides → 逐包覆盖侧车（派生段落，不碰 zip 收录/min-api 闸）。
        // 字节派生发生在落盘 try 之外（纯计算、已 fail-closed 收敛成空表或合法表），
        // 未采信声明的取证行并入 assetWarnings。
        val assetMapBytes = deriveAssetMapBytes(firstPackJson(archiveZips), packageId, assetWarnings)
        val assetMapName = packageId + ModDx3AssetMap.SIDECAR_EXTENSION
        try {
            // 写序（RT2 MODGC 裁定 4）：provenance 先 ⇒ 任一步崩溃时 GC 拿到的是「已声明
            // 源名单」，半成品判据（缺容器/manifest）仍成立，不会因缺 provenance 而漏清。
            // 指纹紧跟 provenance：两件都是源名单派生物；缺指纹只退回 mtime 判据、不算半成品。
            saveArtifact(rt2Dir, sourcesName, sourcesBytes)
            saveArtifact(rt2Dir, fingerprintName, fingerprintBytes)
            saveArtifact(rt2Dir, manifestName, built.manifestBytes)
            saveArtifact(rt2Dir, erbmName, built.erbmBytes)
        } catch (e: Exception) {
            assetPlan.staged?.discard()
            mediaPlan.staged?.discard()
            codeArtifactPlan?.discard()
            return failed(ModImportFailureKind.STORAGE, copy.fill(copy.artifactSaveFailedFormat, detailText(e)), e)
        }
        try {
            verifyWrittenPackage(rt2Dir, built.packageId, sourcesBytes, fingerprintBytes)
        } catch (e: Exception) {
            assetPlan.staged?.discard()
            mediaPlan.staged?.discard()
            codeArtifactPlan?.discard()
            return failed(ModImportFailureKind.IDENTITY, copy.fill(copy.verifyWrittenFailedFormat, detailText(e)), e)
        }

        // ---- ⑤‴ 随包角色模板落盘（ERA-A2 收录面）----
        // `mods/rt2/<packageId>.chara/`：packageId 到编译产物定型后才存在（与资源面同源）。
        // 同集合重导 = 同 packageId = 同名目录原地替换：先清旧目录，避免换包后旧模板冒充
        // 本次产物（同 NO 先到先得由装配期 registerTemplate 的 putIfAbsent 兜底，这里只管
        // 盘上不留残）。落盘失败复用既有的「产物落盘失败」文案口径，不新增玩家可见字面量。
        var charaTemplateEntryCount = 0
        if (charaTemplates.isNotEmpty()) {
            val charaDir = File(rt2Dir, built.packageId + CHARA_TEMPLATE_DIR_SUFFIX)
            try {
                if (charaDir.isDirectory && !charaDir.deleteRecursively()) {
                    throw IOException("无法清理旧角色模板目录：${charaDir.absolutePath}")
                }
                val seenCharaNames = HashSet<String>()
                for (entry in charaTemplates) {
                    val diskName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    // 多个 zip 混合选择时主名撞车：与源面 deduped 的「先到者胜」同口径。
                    if (!seenCharaNames.add(diskName)) continue
                    saveArtifact(charaDir, diskName, entry.bytes)
                    charaTemplateEntryCount++
                }
            } catch (e: Exception) {
                // 三个暂存面（.assets / 旁挂媒体 / 代码产物）都要随失败回收：媒体面 ④″ 已建出
                // `<pkg>.media.tmp-<nano>` 临时树，这里漏 discard 就是确定性泄漏——启动残留 GC
                // （ModPackageInventory.gcStagingResidues）只清崩溃残留，且带 24h 时间窗与
                // 「`.media` 清单在场不删」的保守口径，失败相位必须当场 discard、不能指望 GC
                // 兜底（与上方 ⑤ 落盘/读回两个失败相位的三面回收同口径）。
                assetPlan.staged?.discard()
                mediaPlan.staged?.discard()
                codeArtifactPlan?.discard()
                return failed(ModImportFailureKind.STORAGE, copy.fill(copy.artifactSaveFailedFormat, detailText(e)), e)
            }
        }

        // ---- ⑤⁗ 覆盖侧车落盘（ERA-A3 二期 A）----
        // 附加面、best-effort：四件套读回校验已过 ⇒ 包可用；侧车写失败只留 WARN、不回退导入成功，
        // 与 ⑤″ 资源面 swap 同口径（不让一张可选侧车的 IO 错误把合法包拒之门外）。
        // 无论有无 overrides 都写（无声明=空表）⇒ 重导覆盖旧侧车，不留陈旧覆盖（fail-closed）。
        try {
            saveArtifact(rt2Dir, assetMapName, assetMapBytes)
        } catch (e: Exception) {
            assetWarnings += "覆盖侧车落盘失败，本次覆盖声明未生效：${detailText(e)}"
        }
        // ---- ⑤‴ 代码产物落盘（ADR-013 D1）：内容寻址 + 只读 + 原子换名全在 [ModCodeStore] 内 ----
        // 放在四件套读回校验通过之后、资源面 swap 之前：全部校验过了才动 `mods/rt2/code/` 正式目录。
        // 同内容重导 ⇒ 复用既有地址键文件（只增不改、不删旧版本；旧版本清理归 GC）。
        // 落盘失败按 fail-closed 整包拒导：暂存文件与两个 staged 面都回收，不留 tmp。
        // 无代码产物时反向清理旧身份侧车（重导去掉 code.mod 不留脏映射），见 [finalizeCodeArtifact]。
        codeArtifactPlan.finalizeCodeArtifact(rt2Dir, built.packageId, copy, assetPlan, mediaPlan)
            ?.let { return it }        // ---- ⑤″ 资源面 swap（产物读回校验通过后才动正式名；失败回旧面并留 WARN）----
        var resourceEntryCount = 0
        val stagedAssets = assetPlan.staged
        if (stagedAssets != null) {
            try {
                stagedAssets.commit(rt2Dir)
                resourceEntryCount = stagedAssets.entryCount
            } catch (e: Exception) {
                stagedAssets.discard()
                assetWarnings += copy.fill(copy.resourceCommitFailedFormat, detailText(e))
            }
        }
        // ---- ⑤‴ 媒体资源面落盘（ADR-014 S4）：清单驱动，只删旧清单内、本次不再产出的文件 ----
        commitMedia(mediaPlan, copy, mediaWarnings)

        // ---- ⑤′ 存量 .patch 迁移（删除相位后移，L5 / CONTRACT-RT2-M2LEGACY）----
        // 旧并集补丁 kojo-imports.patch 与旧 per-import .patch 只在「迁移成功 + 三件套读回
        // 校验通过」后删。迁移器枚举盘上留档源（真相源，绝不读 .patch 内容），经 writePackage
        // 回调二次进入本 import 整条编译→转码→落盘链（legacyMigrationEnabled = false 防递归），
        // 产出独立 packageId 的同形三件套——零第二份编译/转码实现。无存量 .patch ⇒ NoLegacy，
        // 本段完全零副作用（逐字节零变化）。失败告警并入 parseWarnings，不拦截本次导入。
        val migrationWarnings = if (legacyMigrationEnabled) {
            migrateLegacyPatches(
                modsDir = modStore.modsDir,
                importDiskNames = deduped.map { it.diskName }.toHashSet(),
                copy = copy,
                importInner = { legacyPicked ->
                    import(legacyPicked, copy, modRuntimeEnabled, legacyMigrationEnabled = false)
                },
            )
        } else {
            emptyList()
        }

        // ---- ⑤⁘ 代码闸提示（task-179）：本次带了代码产物时，把「下次装载不会运行它」的原因
        // 说清（总闸关 / 还没信任）。判据与装载侧同源：总闸快照 = 调用方读好的
        // [dexLoaderEnabled]，信任 = [ModDexGate.isTrusted]，身份 = [ModCodeIdentity.read]。
        // 身份侧车刚由 ⑤‴ 写盘，损坏只可能是写后被外部改坏（照实报，不假设「刚写就一定好」）。
        val codeGateHint = if (codeArtifactPlan == null) {
            null
        } else {
            codeGateHintFor(rt2Dir, built.packageId, codeGateEnabled)
        }

        return ModImportOutcome.Imported(
            ImportedPatchDetail(
                patchFileName = erbmName,
                functionCount = built.report.functionCount,
                overrideCount = built.report.overrideCount,
                instructionCount = built.report.instructionCount,
                entryFunction = built.report.entryFunction,
                sourceFileNames = deduped.map { it.displayName },
                parseWarnings = built.report.parseWarnings + dedupWarnings + packMetaWarnings +
                    listOfNotNull(engineApiWarning) + assetWarnings + mediaWarnings + migrationWarnings,
                modRuntimeOffHint = if (modRuntimeEnabled) null else copy.modRuntimeOffHint,
                resourceEntryCount = resourceEntryCount,
                resourceWarnings = assetWarnings.toList(),
                versionChange = versionChange,
                packMetaWarnings = packMetaWarnings,
                engineApiWarning = engineApiWarning,
                charaTemplateEntryCount = charaTemplateEntryCount,
                codeGateHint = codeGateHint,
                engineTooOld = engineTooOld,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 批量导入（task-178：逐包顺序导入、坏包隔离、逐项结果）
    // ------------------------------------------------------------------

    /**
     * 批量导入：把一次多选拆成批次项后**逐项顺序**跑既有单包链 [import]。
     *
     * 复用口径（不复制单包逻辑）：批量层只做「分组 + 循环 + 收结果」，编译 / 转码 / 落盘 /
     * 身份校验 / 迁移仍只有 [import] 一份实现——每项调用即一次完整单包事务，失败已在该
     * 调用内折成 [ModImportOutcome.Failed] 并独立回滚，天然满足「好包照装、坏包只坏自己」。
     * 不再往本类里抽第二个单包函数体：既有 [import] 的复杂度已被 detekt 按签名冻结在
     * baseline（LongMethod / CyclomaticComplexMethod 等），抽壳会引入未冻结的新 ID——
     * 壳随小刀顺手抽、闸门不许跟着放宽。
     *
     * 分组与循环在 [ModImportBatchRunner]（单独成文件：分组 / 循环 / 收结果与单包链无关，
     * 留在本类会把类体撑过 detekt 的类体上限，而本类的既有复杂度已按签名冻结在 baseline，
     * 闸门不许跟着放宽）。分组口径：每个 zip 整包各成一项；非 zip 的直选源合成一项（保持
     * 「多选散装源 = 一个口上包」的既有语义）；项序 = 选中序（散装组落在首个散装文件的
     * 位置），UI 逐行照此序上屏。
     *
     * 失败隔离两道（[ModImportBatchRunner.run]）：单项异常 → 该项记 UNEXPECTED、后续项继续；
     * 循环本身异常 → 已完成项原样保留，失败原因进 [ModImportBatchOutcome.batchFailure]。
     */
    fun importBatch(
        files: List<PickedModFile>,
        copy: ModImportCopy = ModImportCopy.ZH,
        modRuntimeEnabled: Boolean = true,
        legacyMigrationEnabled: Boolean = true,
        declaredOverrides: List<String>? = null,
    ): ModImportBatchOutcome {
        if (files.isEmpty()) {
            return ModImportBatchOutcome(
                items = emptyList(),
                batchFailure = ModImportOutcome.Failed(ModImportFailureKind.STORAGE, copy.noFilesPicked),
            )
        }
        return ModImportBatchRunner.run(
            groups = ModImportBatchRunner.groupsOf(files),
            copy = copy,
            importOne = { group ->
                import(group, copy, modRuntimeEnabled, legacyMigrationEnabled, declaredOverrides)
            },
            unexpected = { unexpectedFailure(copy, it) },
        )
    }

    /** 未预期异常的统一折法：文案走既有 [ModImportCopy.unexpectedFormat]（不新增玩家可见字面量）。 */
    private fun unexpectedFailure(copy: ModImportCopy, e: Exception): ModImportOutcome.Failed =
        failed(
            ModImportFailureKind.UNEXPECTED,
            copy.fill(copy.unexpectedFormat, e.javaClass.simpleName, detailText(e)),
            e,
        )

    // ------------------------------------------------------------------
    // 覆盖侧车派生（ERA-A3 二期 A；pack.json overrides → <pkg>.assetmap）
    // ------------------------------------------------------------------

    /**
     * 从 `archiveZips` 里取第一份 pack.json 条目字节（与 [packMetaOf] 同源：第一个带该条目的包胜）。
     * 缺条目返回 null ⇒ [deriveAssetMapBytes] 派生出空表侧车（重导时覆盖旧的，fail-closed）。
     */
    private fun firstPackJson(archiveZips: List<ByteArray>): ByteArray? =
        archiveZips.firstNotNullOfOrNull {
            ModZip.fixedEntryBytes(it, PACK_META_ENTRY_NAME, PACK_META_MAX_ENTRY_BYTES)
        }

    /**
     *  pack.json → `<packageId>.assetmap` 侧车字节（导入派生段的唯一出口，主链与纯资源包链共用）。
     *
     * fail-closed：
     * - 坏 `overrides` 声明由 [EraA3AssetMapWriter.parse] 逐项丢弃，只把合法项编码进侧车；
     *   未采信项以取证行并入 [warnings]（作者侧诊断，不上玩家 UI 文案表，故不触发三语闸），
     *   不静默吞错；
     * - 无 overrides / 全坏 ⇒ 编码出**0 条目**空侧车：重导时用空表覆盖旧侧车，
     *   消费面聚合到空映射 ⇒ 维持既有官方资源行为（不留上次安装的陈旧覆盖）。
     * - 本函数纯计算、不读盘不写盘、不抛：任何派生失败都收敛成空表，绝不因作者声明坏档
     *   把一份合法资源包拒之门外。
     */
    private fun deriveAssetMapBytes(
        packJson: ByteArray?,
        packageId: String,
        warnings: MutableList<String>,
    ): ByteArray {
        val parsed = EraA3AssetMapWriter.parse(packJson)
        for (rejected in parsed.rejected) warnings += "覆盖声明未采信：$rejected"
        return EraA3AssetMapWriter.encode(packageId, parsed.entries).toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------
    // 纯资源包载体导入（MODDX3-A3 一期；manifest-only，无 .erbm）
    // ------------------------------------------------------------------

    /**
     * 无 `.ERB`/`.ERH` 源的空解包 zip 的处置口（[import] ⓪ 分流）：逐个尝试按纯资源包载体导入，
     * 认下**第一个可信候选**；一个都不认 ⇒ 维持既有失败面（[emptyUnpackFailure]），fail-closed。
     *
     * 判据三件缺一不可（[importPureAssetPack] 内逐件校验）：
     * 1. 可信 `eratw-pack.json`（[parsePackMeta]：schema=1 + packageId 自述合法；
     *    version 缺失/非法也只回落 "1"，不因元数据坏档阻挡资源包身份——与口上源链同口径）；
     * 2. ≥1 条**可收下**的 `<自述名>.assets/` 资源（合法名 + 限额内；全被跳过/超限 ⇒ 拒）；
     * 3. zip 内 0 条 `.ERB`/`.ERH`（[ModZip.unpack] 已空，本函数的调用前提）。
     *
     * 多候选只认第一个：混合选择里哪份资源是「本包」无法判定，静默合并会重演资源面同名冲突，
     * 取先 + 告知玩家（告警走既有资源面通道）比猜更稳。
     */
    private fun importWithoutSources(
        emptyZips: List<PickedModFile>,
        archiveZips: List<ByteArray>,
        copy: ModImportCopy,
        modRuntimeEnabled: Boolean,
    ): ModImportOutcome {
        for (zip in emptyZips) {
            val declared = parsePackMeta(
                ModZip.fixedEntryBytes(zip.bytes, PACK_META_ENTRY_NAME, PACK_META_MAX_ENTRY_BYTES),
            )
            // 可信身份 = schema 认得 + 自述包名合法。version 坏档只回落 "1"（parsePackMeta 已处理），
            // 不阻挡；但 schema 不认识 / 自述包名非法（ignored NON-null 的对应因）一律不当载体。
            if (!isTrustedCarrierMeta(declared)) continue
            when (val result = importPureAssetPack(zip, declared, archiveZips, copy, modRuntimeEnabled)) {
                is ModImportOutcome.Imported -> return result
                // 该候选资源不合规（全被跳过 / 超限 / 冲突）⇒ 不回退到下一个候选，直接维持失败面：
                // 「认下但资源坏」与「没认下」都指向同一份失败文案，fail-closed 不静默换候选。
                is ModImportOutcome.Failed -> return result
            }
        }
        val first = emptyZips.first()
        return emptyUnpackFailure(copy, first.displayName, first.bytes)
    }

    /**
     * 判断包元数据是否可信到足以当纯资源包载体身份：schema=1（[parsePackMeta] 已保证）+
     * 自述包名合法（市场包 id 正则已校验，存在 packageId 即合法）。
     *
     * 注意不要求 version 可信：[parsePackMeta] 对 version 坏档返回 ignored=VERSION 但 packageId
     * 仍保留；纯资源包没有运行期函数，version 只是成功页读数，回落 "1" 不影响资源可见性。
     * 唯一排除的是 PACKAGE_ID_PREFIX 冲突（自述名与包内资源前缀自相矛盾）——那种元数据整体不可信。
     */
    private fun isTrustedCarrierMeta(meta: PackMeta): Boolean =
        meta.packageId != null && meta.ignored != PackMetaIgnore.PACKAGE_ID_PREFIX

    /**
     * 落盘一份纯资源包载体：manifest-only（无 `.erbm`），manifest 由包元数据派生为 RT2MANIFEST/4
     * （displayName = 自述包名触发 /4 段，零 ManifestCodec 改动）；packageId = [assetPackageIdFor]。
     *
     * 落盘最小件集（`mods/rt2/`）：
     * - `<packageId>.manifest`：声明事务仍由 ModRuntimeAssembly.attach 全量加载；链接期被
     *   ModChainAssembly 单向配对跳过（载体无 `.erbm`，只压「每 `.erbm` 必有 manifest」方向）。
     * - `<packageId>.sources`：**空名单** provenance = 载体形态标记。普通导入恒非空，崩溃残留
     *   不可能命中此形（写序 provenance 先于容器），孤儿 GC 据此识别载体、不误删（见 RECON §3）。
     * - `<packageId>.assets/`：资源目录，[ModImportAssets.stageSafely] 原子重挂；运行期
     *   ModResourceStore 按 packageId 读，资源注入随 attach 无条件发生。
     * 不落 `.erbm`（无代码可装）、不落 `.sources.sha256`（载体无源可重编，mtime 判据无意义）。
     *
     * fail-closed：资源全被跳过 / 超限整面拒绝 ⇒ [emptyUnpackFailure] 维持既有失败面；
     * 任一步落盘 / 读回失败 ⇒ 清理临时目录与已落盘件（零半套残留），折 STORAGE / IDENTITY。
     */
    private fun importPureAssetPack(
        carrier: PickedModFile,
        declared: PackMeta,
        archiveZips: List<ByteArray>,
        copy: ModImportCopy,
        modRuntimeEnabled: Boolean,
    ): ModImportOutcome {
        val selfName = declared.packageId!!
        val packageId = assetPackageIdFor(selfName)
        val rt2Dir = File(modStore.modsDir, ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME)

        // ④′ 资源面：复用与口上源链完全相同的校验 + 原子落盘（限额 / 路径穿越 / 同名冲突同源）。
        val plan = try {
            ModImportAssets.stageSafely(archiveZips, packageId, rt2Dir, copy)
        } catch (e: ModImportAssets.AssetConflictException) {
            return failed(ModImportFailureKind.STORAGE, e.message ?: copy.unknownStorageError, e)
        }
        // ④″ 旁挂媒体资源面（ADR-014 S4）：载体包同样可声明 `resources`，口径与源链一致。
        val mediaPlan = try {
            ModImportMediaAssets.stageSafely(
                archiveZips,
                ModImportMediaAssets.MediaTarget(packageId, declared.resources, gameRoot, rt2Dir),
                copy,
            )
        } catch (e: ModImportMediaAssets.MediaDirConflictException) {
            plan.staged?.discard()
            return failed(ModImportFailureKind.STORAGE, e.message ?: copy.unknownStorageError, e)
        }
        // 无可收下资源（0 条 / 全跳过 / 整面超限）= 第三判据不成立 ⇒ fail-closed。
        if (plan.staged == null || plan.entryCount <= 0) {
            plan.staged?.discard()
            mediaPlan.staged?.discard()
            return emptyUnpackFailure(copy, carrier.displayName, carrier.bytes)
        }

        // ERA-A3 二期 A：pack.json overrides → 覆盖侧车字节（派生段纯计算，fail-closed 收敛成空表/合法表）。
        val carrierOverrideWarnings = ArrayList<String>()
        val assetMapBytes = deriveAssetMapBytes(
            ModZip.fixedEntryBytes(carrier.bytes, PACK_META_ENTRY_NAME, PACK_META_MAX_ENTRY_BYTES),
            packageId,
            carrierOverrideWarnings,
        )
        val assetMapName = packageId + ModDx3AssetMap.SIDECAR_EXTENSION

        // manifest 由 pack.json 派生：RT2MANIFEST/4（displayName 非空触发 /4 元数据段）。
        val descriptor = com.eratw.port.core.patch.ModuleDescriptor(
            packageId = packageId,
            version = declared.version,
            displayName = selfName,
        )
        val manifestBytes = try {
            ManifestCodec.encode(descriptor)
        } catch (e: Exception) {
            plan.staged?.discard()
            mediaPlan.staged?.discard()
            return failed(
                ModImportFailureKind.COMPILE,
                copy.fill(copy.artifactEncodeFailedFormat, detailText(e)),
                e,
            )
        }

        // 落盘序：provenance（空名单，载体标记）→ manifest → 读回校验 → 资源目录 swap。
        val sourcesBytes = ModPackageInventory.sourcesBytes(emptyList())
        writeCarrierArtifacts(rt2Dir, packageId, manifestBytes, sourcesBytes, copy)?.let { failure ->
            plan.staged?.discard()
            mediaPlan.staged?.discard()
            deleteCarrierArtifacts(rt2Dir, packageId)
            return failure
        }

        // 覆盖侧车落盘（附加面 best-effort，同主链 ⑤‴）：无声明写空表覆盖旧侧车，写失败只留 WARN。
        bestEffortWrite("覆盖侧车落盘失败，本次覆盖声明未生效：") {
            saveArtifact(rt2Dir, assetMapName, assetMapBytes)
        }?.let { carrierOverrideWarnings += it }

        var resourceEntryCount = 0
        val commitWarnings = ArrayList<String>()
        val staged = plan.staged!!
        try {
            staged.commit(rt2Dir)
            resourceEntryCount = plan.entryCount
        } catch (e: Exception) {
            // 与主链 ⑤″ 同口径：资源 swap 失败只留 WARN、不回退载体成功（manifest + 空 sources
            // 已读回校验通过，载体身份在场）；仅资源面这一附加面未生效。
            staged.discard()
            commitWarnings += copy.fill(copy.resourceCommitFailedFormat, detailText(e))
        }
        // 媒体资源面落盘（ADR-014 S4）：与 `.assets` 同口径，失败只留 WARN、不回退载体成功。
        commitMedia(mediaPlan, copy, commitWarnings)

        val metaWarnings = declared.packageIdPrefixWarning(copy)?.let { listOf(it) } ?: emptyList()
        // 两侧合并：HEAD 的载体覆盖 WARN（CARRIER-OVERRIDE 面）与 mediaimport 的媒体资源 WARN 都要上屏。
        val allWarnings = plan.warnings.toList() + mediaPlan.warnings + metaWarnings +
            commitWarnings + carrierOverrideWarnings
        return ModImportOutcome.Imported(
            ImportedPatchDetail(
                patchFileName = packageId + ModPackageInventory.MANIFEST_EXTENSION,
                functionCount = 0,
                instructionCount = 0,
                entryFunction = "",
                sourceFileNames = emptyList(),
                parseWarnings = allWarnings,
                modRuntimeOffHint = if (modRuntimeEnabled) null else copy.modRuntimeOffHint,
                resourceEntryCount = resourceEntryCount,
                resourceWarnings = plan.warnings.toList(),
                versionChange = versionChangeOf(installedManifestOf(rt2Dir, packageId)?.version, declared.version),
                packMetaWarnings = metaWarnings,
                assetPackOnly = true,
            ),
        )
    }

    /**
     * 载体件落盘 + 读回校验（纯资源包专用）：provenance（空名单）→ manifest → 读回比对
     * （manifest packageId 必须与派生身份一致；sources 必须是空名单 = 载体形态标记）。
     * 失败返回已构造的失败面（调用方负责清临时目录与已落盘件）；成功返回 null。
     */
    private fun writeCarrierArtifacts(
        rt2Dir: File,
        packageId: String,
        manifestBytes: ByteArray,
        sourcesBytes: ByteArray,
        copy: ModImportCopy,
    ): ModImportOutcome? {
        try {
            saveArtifact(rt2Dir, packageId + ModPackageInventory.SOURCES_EXTENSION, sourcesBytes)
            saveArtifact(rt2Dir, packageId + ModPackageInventory.MANIFEST_EXTENSION, manifestBytes)
        } catch (e: Exception) {
            return failed(
                ModImportFailureKind.STORAGE,
                copy.fill(copy.artifactSaveFailedFormat, detailText(e)),
                e,
            )
        }
        return try {
            val readBack = readBackOrThrow(
                File(rt2Dir, packageId + ModPackageInventory.MANIFEST_EXTENSION),
                "manifest",
                ManifestCodec::decode,
            )
            check(readBack.packageId == packageId) {
                "manifest packageId 不符：期望 $packageId，实为 ${readBack.packageId}"
            }
            val sourcesReadBack = readBackOrThrow(
                File(rt2Dir, packageId + ModPackageInventory.SOURCES_EXTENSION),
                "provenance",
            ) { it }
            check(sourcesReadBack.contentEquals(sourcesBytes)) {
                "载体 provenance 必须是空名单（载体形态判据）"
            }
            null
        } catch (e: Exception) {
            failed(
                ModImportFailureKind.IDENTITY,
                copy.fill(copy.verifyWrittenFailedFormat, detailText(e)),
                e,
            )
        }
    }

    /** 清理载体落盘件（失败路径零半套残留）：manifest + provenance + 覆盖侧车。资源目录由 staged.discard 清。 */
    private fun deleteCarrierArtifacts(rt2Dir: File, packageId: String) {
        File(rt2Dir, packageId + ModPackageInventory.MANIFEST_EXTENSION).delete()
        File(rt2Dir, packageId + ModPackageInventory.SOURCES_EXTENSION).delete()
        File(rt2Dir, packageId + ModDx3AssetMap.SIDECAR_EXTENSION).delete()
    }

    /**
     * 纯资源包之外的空解包 zip 维持**既有失败面**（与改造前逐字相同）：
     * 素材包（[ModZip.looksLikeAssetPack]）指路素材导入卡；否则 zipNoEntriesFormat。
     * 本函数是放宽闸的「反向证明」锚：若把 [importWithoutSources] 的载体分支去掉，
     * 所有空解包 zip 都落这里 ⇒ 纯资源包被旧文案拒绝。
     */
    private fun emptyUnpackFailure(copy: ModImportCopy, zipName: String, bytes: ByteArray): ModImportOutcome.Failed =
        if (ModZip.looksLikeAssetPack(bytes)) {
            ModImportOutcome.Failed(
                ModImportFailureKind.STORAGE,
                copy.fill(copy.zipLooksLikeAssetPackFormat, zipName),
            )
        } else {
            ModImportOutcome.Failed(
                ModImportFailureKind.STORAGE,
                copy.fill(copy.zipNoEntriesFormat, zipName),
            )
        }

    // ------------------------------------------------------------------
    // 纯函数辅助（internal：单测直接点名）
    // ------------------------------------------------------------------

    /**
     * 导入源的装载序：文件名序，[ModStore.compareNames]（ASCII 折叠、locale 无关）——
     * 与 ModStore / ModPatchLoader 三方同源（C1 TODO-⑥ 的调用方义务落在这里）。
     */
    internal fun sortSourceFiles(files: List<PickedModFile>): List<PickedModFile> =
        files.sortedWith { a, b -> ModStore.compareNames(a.displayName, b.displayName) }

    /**
     * 首个**可用** `@` 函数头的名字（补丁头 `entry-function` 字段的来源）。
     *
     * B4（2026-11 整包导入实测）：群友整合包排序第一的 M_KOJO_K16_5_IMAGE.ERB 全文件
     * 在 `[SKIPSTART]` 块内——旧实现不认 SKIPSTART，把跳过块里的 @ 名当 entry，
     * I3 校验「入口函数不存在」整包死。本预扫改用 core-vm 的 [ErbPreprocessor]
     * 驱动同一条状态机（PatchCompiler 预扫与 LogicalLineReader 的共用口径，
     * spec: ErbLoader.cs:25-181），与其零漂移：
     *
     *  - `[SKIPSTART]`..`[SKIPEND]` 块内的行不产出函数头。SKIPSTART **不嵌套**：
     *    重复使用原版 Warn level 1 后忽略、不叠栈（spec: ErbLoader.cs:43-60
     *    DuplicateSkipstart）；块内嵌套的 `[IF 宏]` 无法解锁区间——每条指令处理后
     *    无条件 `if (skip) disabled = true`（spec: ErbLoader.cs:163-166 强制位）。
     *  - 未闭合（SKIPSTART 无 SKIPEND）：状态持续到文件尾（原版 fileEnd 只 Warn
     *    level 1 不复位区间，spec: ErbLoader.cs:169-180 + :513 调用点）——本预扫
     *    丢弃告警面，行为等价「余行全禁用」。
     *  - `[SKIPEND]` 无配对：原版 UnexpectedSkipend level 1 忽略、不改状态
     *    （spec: ErbLoader.cs:127-141）。
     *  - `;` 注释行、指令行（`[` 起头整行消费）不可能是函数头。
     *  - 大小写不敏感（`[skipstart]` 同效，kw 按 Locale.ROOT 归一）与前导空白容忍
     *    （content 先 trim 再进状态机，process 的合同）。
     *
     * 判定与 [PatchCompiler] 的函数名口径逐字同源（`@` 之后到首个空白、`(`、`,` 的段）。
     * `entry-function` 在装载期没有运行语义（入口恒为 base 的，铁律），它只是补丁的
     * 定位信息（I7）；预扫取「装载序第一个可用函数头」稳定标识一份补丁。
     *
     * [macroNames] 是 `[IF 宏]` 的判定表：生产调用点（[compilePatch]）传 base 侧车
     * `dimParseConfig.macros` 的宏名集——与 [PatchCompiler] 预扫/编译的宏表**同一张表**
     * （spec: PatchCompiler.kt:971/:979 `metadata.dimParseConfig.macros.keys`，即
     * ErbLoader.cs:94 的 `[IF 宏]` 判定口径），预扫与编译器对同一文件不再有宏启用
     * 分支的判定差（ERA-W4 收口：此前预扫恒按空集跑，`[IF 已定义宏]` 启用分支里的
     * @ 行被预扫判禁用、编译器判启用，取到错位 entry 伤 I7 定位信息）。单测直呼时
     * 缺省空集 = 与收口前行为逐位一致（无宏分支的源零差异）。
     */
    internal fun firstFunctionNameOf(
        text: String,
        macroNames: Set<String> = emptySet(),
    ): String? {
        val preprocessor = ErbPreprocessor(
            debugMode = false,
            macroNames = macroNames,
            file = "ModImportController.firstFunctionNameOf",
        )
        for ((lineIndex, rawLine) in text.lineSequence().withIndex()) {
            // 首行 BOM（\uFEFF）必须剥掉：SAF 读出的 UTF-8 文件常带 BOM，不剥则首函数头
            // 匹配不到、entry 取名偏移到第二个函数（仅伤 I7 定位信息；SystemVariableBootstrap
            // 同款先例）。BOM 剥掉后再全行 trim（[ErbPreprocessor.process] 的合同：content
            // 需已 trim；尾部空白同样裁掉，指令判定与 @ 判定都不受影响）。
            val content = rawLine.trimStart('\uFEFF', ' ', '\t').trimEnd()
            if (content.isEmpty()) continue
            if (preprocessor.process(content, lineIndex + 1)) continue
            if (preprocessor.disabled) continue
            if (content.startsWith(";")) continue
            if (!content.startsWith("@")) continue
            val body = content.substring(1)
            var i = 0
            while (i < body.length && body[i] !in " \t(,") i++
            val name = body.substring(0, i).trim()
            if (name.isNotEmpty()) return name
        }
        return null
    }

    /**
     * 编译期符号表（[PatchCompiler] 的 `symbolTable` 参数）：
     *
     * 1. **CSV 全集**（[GameDataLoader]）：运行期注册表里真实存在的系统/CSV 变量逐条
     *    声明（[com.eratw.port.core.vars.VarDef.toVarInfo] 同源）——补丁解析期可见的变量集
     *    与运行期一致，不多不少；目录缺失时 [GameDataLoader.load] 返回 null，跳过
     *    （内建符号兜底仍在）。
     * 2. **base 全局 `#DIM`**（侧车 `global-dims` 段原文重解析，[DimParser] 同一口径）：
     *    base 的 ERH/ERB 全局声明（含 `#DIM CONST` 常量名）进表，补丁引用它们不再
     *    「未定义标识符」。声明失败 = 侧车与补丁编译器口径脱节，明确失败不静默跳过。
     * 3. **base 方法三分**（侧车 `method-types` 的 I/S 桶，[BootMetadata.knownMethodTypes]）：
     *    表达式位调用 base 方法需要返回类型；U 桶不声明（保守明确失败，波 0 缺口②的口径）。
     *
     * **判定维持（ERA-W4 复核，导入链残余差异两条；全部明确失败方向、无静默错值，
     * 语义待真机口上实测核对，不脑补收口）**：
     * 1. base `#DIM CONST` 常量在补丁里按变量引用解析（运行期查表），而 PC 全量编译
     *    可能把它折值进指令——同源差异待真机口上实测核对（C1 类尾 TODO 的宏展开同族）。
     *    卡点 = 真机上行为分歧的取证样本；无样本前改折值口径 = 脑补 PC 侧行为。
     * 2. 变量下标里的名字键（`CFLAG:ARG:口上セレクタ`）解析期走 [com.eratw.port.core.expr.Parser]
     *    的 indexOwner 保守放行路径，与全量编译「CSV 名字表命中与否」的严格判定有差：
     *    打错名字在补丁侧要到运行期才报错（不静默错值，方向安全）。收口 = Parser
     *    下标判定面收紧，属 core 车道所有权 ⇒ 需组长接线。
     */
    /**
     * 侧车 global-dims 灌装的容忍模式（R7-D3 冒烟 P0）：真产物侧车对 global-dims
     * **原文保真**，其中本就存在「含函数调用的常量表达式 / 行内注释」等非严格解析
     * 形态（实测 80 条；原版 C# 对非 SingleLongTerm 同样不折叠进编译期常量，运行期处理）。
     * 这些条目跳过 = 补丁里引用它们得到「未定义变量」的**明确失败**（方向安全，与
     * 静默错值相反）；失败条目逐条进告警（I7：文件:行 + 原文），不再一条失败整链死。
     */
    internal fun buildPatchSymbolTable(
        metadata: BootMetadata,
        csvDir: File?,
        /** `ConfigKey.UseERD`：与全量编译同门（关 ⇒ 折叠面不含 ERD 表，只折系统名表）。 */
        useERD: Boolean = true,
    ): SymbolTableBuild {
        val sym = SymbolTable()
        // ERH `#DIM CONST` 的**名字**先进符号表（侧车 const / str-const 两段就是它们折叠后的
        // 值表）：原版解析期靠 nameDic 认出「这是个常量」，口上里 `CASE 桜花道的鳥居`、
        // `MAX_DIARY_PAGE` 这类裸常量名就是这么命中的。只灌值表不灌名字 ⇒ 解析期
        // 「未定义的标识符」整包死（2026-11 蕾米莉亚色情特化 v1.3 实测第一抛）。
        // 声明在最前：CSV 与 global-dims 两段随后若出现同名条目，会以更精确的 spec
        // （真实维数/初值）覆盖这里的标量保守形态（declareVariable 后写覆盖前写）。
        val dimCfg = metadata.dimParseConfig
        for (name in dimCfg.intConstants.keys) {
            sym.declareVariable(name, OperandType.INT, 0, isCharacterData = false, isConst = true)
        }
        for (name in dimCfg.strConstants.keys) {
            sym.declareVariable(name, OperandType.STR, 0, isCharacterData = false, isConst = true)
        }
        // 常量关键字折叠的数据面：设备端与全量编译同源（CSV 目录 + ERB 下的 ERD 表），
        // 只是这里按设备现盘装载，不再吃编译机快照。
        val gameData = csvDir?.let { GameDataLoader.load(it.toPath()) }
        if (gameData != null) {
            for (def in gameData.registry.all()) {
                val info = def.toVarInfo()
                sym.declareVariable(info.name, info.operandType, info.dimension, info.isCharacterData, info.isConst)
            }
        }
        val consts = sizeConsts(dimCfg.macros, dimCfg.intConstants)
        // 只重算尺寸常量折叠表，其余解析口径**原样继承侧车 dim-config**——尤其是
        // systemSaveInBinary：丢掉它会退成 false，把 base 里合法的
        // `#DIM CHARADATA SAVEDATA X, n` 判成「非二进制存档下 SAVEDATA 不能是 CHARADATA」
        // 而整条跳过 ⇒ 补丁引用 CHILDNAME/REQUEST_DISHNAME 就是「未定义的标识符」
        // （2026-11 蕾米莉亚色情特化 v1.3 实测：78 条假跳过里 73 条即此因）。
        val cfg = dimCfg.copy(intConstants = consts)
        val warnings = ArrayList<String>()
        var skippedBadDims = 0
        for (raw in metadata.globalDims) {
            try {
                val keyword = raw.raw.substringBefore(' ', raw.raw).uppercase()
                val spec = DimParser.parse(keyword, raw.raw.substringAfter(' ', "").trim(), isPrivate = false, config = cfg)
                sym.declareVariable(
                    spec.name,
                    // 三态判序与全量预扫描同源（CorpusScan.kt:242）：`#DIMF`（4.981 浮点声明）
                    // 若折成 INT，补丁里对它的读写会落整型列 ⇒ 静默错值。isFloat 必须先判
                    // （DimSpec 的两个布尔位「都 false 不代表整数」，UserVariables.kt:72）。
                    when {
                        spec.isFloat -> OperandType.FLOAT
                        spec.isStr -> OperandType.STR
                        else -> OperandType.INT
                    },
                    spec.dimension,
                    // CHARADATA 位原样带给符号表（与 erbcc 全量预扫 CorpusScan 的
                    // global.declareVariable 同口径）：口上里 `CHILDNAME:16:ARG` 这类
                    // 「下标+角色」二维取用要靠它才解析得过。
                    isCharacterData = spec.isCharaData,
                    isConst = spec.isConst,
                )
            } catch (e: Exception) {
                skippedBadDims++
                warnings += "侧车 global-dims 跳过（${raw.file}:${raw.line}）${raw.raw.take(80)} —— ${e.message ?: e::class.simpleName}"
            }
        }
        if (skippedBadDims > 0) {
            warnings += "侧车 global-dims 共跳过 $skippedBadDims 条非严格解析条目（原版运行期处理形态，见告警明细）"
        }
        for ((name, type) in metadata.knownMethodTypes()) {
            when (type) {
                OperandType.INT -> sym.declareFunction(name, OperandType.INT)
                OperandType.STR -> sym.declareFunction(name, OperandType.STR)
                else -> Unit // UNKNOWN：不声明 = 表达式位调用保守明确失败（禁静默降级）
            }
        }
        // `#FUNCTIONF`（4.981 第三种 MethodType）在侧车 `method-types` 段只有 UNKNOWN，
        // 但 `function` 段逐条带 `returnsFloat` —— 名字类型按它补声明成 FLOAT。不补的话
        // 补丁里 `F()` 解析成整型调用：全量侧同一声明是 FLOAT（CorpusScan.kt:180
        // `FUNCTIONF -> OperandType.FLOAT`），裸浮点调用在整型落点会明确失败；补丁侧则
        // 保持整型节点、运行期按整型读浮点方法的返回（恒 0）= 静默错值。
        for (m in metadata.functions) {
            if (m.isMethod && m.returnsFloat) sym.declareFunction(m.key, OperandType.FLOAT)
        }
        // base 普通函数（非方法、非事件）补进表达式位存在性表（2026-11 蕾米莉亚包实测）：
        // LineParser 在 SET/IF 表达式位调用 `@IS_HERE(TARGET)` 时经 resolveFunction 判存在，
        // 不声明 = 「未定义的标识符」整包死。类型钉 INT：ExprCallTable 把它们归
        // nonMethodLabels（不降级、保持 FuncCall 节点交运行期重入通道），解析期类型位
        // 不参与编译产物——与 RealCorpusPatchSmokeTest 装置复刻面同步（改一处须同步另一处）。
        val methodKeys = metadata.knownMethodTypes().keys
        for (m in metadata.functions) {
            if (m.isEvent || m.isMethod || m.key in methodKeys) continue
            sym.declareFunction(m.key, OperandType.INT)
        }
        // 解析期常量关键字折叠面（`VariableStrArgTerm.cs:57-69`）：常量字符串下标必须在这里
        // 折成整数，否则补丁运行期按名查盘上 CSV——盘上表旧/半截就抛「…csv 中未定义关键字」。
        // 数据面缺（CSV 读不到）时保持 null：回退成旧的运行期查表，报错语义不变。
        val keywordLookup: ((String, Int, String) -> Long?)? = gameData?.let { data ->
            val gameRootForFold = csvDir?.parentFile?.toPath()
            val systemNames = data.registry.all().mapTo(HashSet()) { it.name.uppercase() }
            val erdTables = if (useERD && gameRootForFold != null) {
                runCatching { UserKeywordTables.foldSnapshot(gameRootForFold, systemNames) }
                    .getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            ConstantKeywordResolver(data.registry, erdTables + data.registry.systemKeywordTables())::resolve
        }
        return SymbolTableBuild(sym, warnings, keywordLookup)
    }

    /** [buildPatchSymbolTable] 的出口：符号表 + 侧车灌装告警（并入导入结果 `parseWarnings`）。 */
    internal data class SymbolTableBuild(
        val table: SymbolTable,
        val warnings: List<String>,
        /** 常量关键字的解析期折叠闭包；null = 数据面缺，回退运行期查表。 */
        val keywordLookup: ((String, Int, String) -> Long?)? = null,
    )

    /**
     * mods 目录里的补丁产物名（静态清单，报告页用；文件名序，[ModStore.compareNames]）。
     * 扩展名判定只引用 [ModPatchLoader.hasPatchExtension]（R7-D1 任务③：判定口径的
     * 唯一权威在 core-vm 装载器侧，app 面不再自持复制；列目录与排序的口径不变）。
     */
    internal fun patchFileNamesIn(modsDir: File): List<String> {
        if (!modsDir.isDirectory) return emptyList()
        val files = modsDir.listFiles { f -> f.isFile && ModPatchLoader.hasPatchExtension(f.name) } ?: return emptyList()
        return files.map { it.name }.sortedWith { a, b -> ModStore.compareNames(a, b) }
    }

    /**
     * 包身份稳定派生（M2-4 裁定 3）：[ModPackageInventory.PACKAGE_ID_PREFIX] +
     * sha256(排序后 diskName 列表 join '\n') 前 8 位十六进制。同集合重导 = 同 packageId =
     * 原地替换；稳定身份键归 M1-4，本函数不承担跨包身份语义。
     */
    internal fun packageIdFor(diskNames: List<String>): String {
        val joined = diskNames
            .sortedWith { a, b -> ModStore.compareNames(a, b) }
            .joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(joined.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(PACKAGE_ID_BYTES * HEX_CHARS_PER_BYTE)
        for (i in 0 until PACKAGE_ID_BYTES) {
            val v = digest[i].toInt() and BYTE_MASK
            hex.append(PACKAGE_ID_HEX[v ushr NIBBLE_BITS]).append(PACKAGE_ID_HEX[v and NIBBLE_MASK])
        }
        return ModPackageInventory.PACKAGE_ID_PREFIX + hex
    }

    /** 失败的统一收口：消息上屏（I7 可定位），异常只进日志面（由调用方决定是否记 logcat）。 */
    private fun failed(kind: ModImportFailureKind, message: String, cause: Throwable): ModImportOutcome.Failed {
        cause.printStackTrace()
        return ModImportOutcome.Failed(kind, message)
    }

    /**
     * 源 → 零基线模块产物的完整编译链（② base 上下文 + ③ 编译 + ④ 转码/编码）。
     *
     * 从 [import] 里原样抽出（M2-4 裁定 1/2 的相位）：导入把这里产出的字节按 ⑤ 的写序
     * 整包落盘，重编（[ModRecompiler]）把这里产出的 `.erbm` / `.manifest` 与内容指纹侧车
     * 原位原子替换。两个调用方共用这一份实现——编译/转码/编码只有这一条路，不另写编译器调用。
     *
     * [files] 必须是**已排序、已按落盘名去重**的源（[import] 的 `deduped`；重编直接读
     * `.sources` 名单，名单本身就是导入时去重后的落盘名序）：本函数不再排序/去重；
     * packageId 由 [files] 的 diskName 集合算定（[packageIdFor]）。
     *
     * 失败一律抛 [ModCompileException]，kind 值域 = [ModImportFailureKind]：调用方按
     * 自己的收口口径折回（import → [ModImportOutcome.Failed]，重编 → `RecompileResult.Failed`），
     * 两边对外的「失败不留半截产物」承诺一致。
     */
    internal fun compileToModule(
        files: List<PickedModFile>,
        copy: ModImportCopy = ModImportCopy.ZH,
        /**
         * 包清单的可选收窄声明（见 [import] 的同名参数）；重编路径（[ModRecompiler]）
         * 恒缺省 `null`——盘上产物已定型，重编不再获取新的作者声明。
         */
        declaredOverrides: List<String>? = null,
        /**
         * 产物 manifest 的版本号。`null` = 调用方没有作者自述版本（重编路径）：回落到**盘上
         * 旧产物的 version**，再回落到 [PACK_META_FALLBACK_VERSION]——重编不该把已导入的
         * v2 降级成 v1（下一次导入会因此误报「v2 → v1」）。导入路径恒传非空值（[packMetaOf]
         * 的结果，缺元数据时本身就是回落值 `"1"`），老包行为与改造前逐字相同。
         */
        version: String? = null,
        /**
         * 产物 manifest 的 `min-api` 段（作者声明的引擎下限；0 = 不设闸，见 `ModuleDescriptor.minApi`）。
         * `null` = 调用方没有作者声明（重编路径）：回落到**盘上旧 manifest 的 `minApi`**——重编不该把
         * 已声明的引擎下限悄悄抹掉（作者改源重编后包会突然在旧引擎上通过装配）。导入路径恒传非空值
         * （[packMetaOf] 的 `minApi`，缺元数据时本身就是 0），老包行为与改造前逐字相同
         * （`/6` 段只在 `minApi != 0` 时写，见 `ManifestCodec`）。
         */
        minApi: Int? = null,
    ): CompiledModuleArtifacts {
        val base = runCatching { baseSource.read(gameRoot) }.getOrElse { e ->
            failCompile(ModImportFailureKind.STORAGE, copy.fill(copy.readBaseFailedFormat, detailText(e)), e)
        }
        val compiled = runCatching { compilePatch(base, files, gameRoot, copy) }
            .getOrElse { e -> e.rethrowCompileFailureOr(copy) }
        // 两个回落共用一次旧 manifest 读点：导入路径作者声明齐全（不读盘），重编路径两者皆空
        // （读一次旧 descriptor 同时拿 version 与 minApi，与 BU 的版本回落同口径）。
        val rt2Dir = File(modStore.modsDir, ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME)
        val packageId = packageIdFor(files.map { it.diskName })
        val installed = if (version == null || minApi == null) installedManifestOf(rt2Dir, packageId) else null
        val effectiveVersion = version ?: installed?.version ?: PACK_META_FALLBACK_VERSION
        val effectiveMinApi = minApi ?: installed?.minApi ?: 0
        // ModDX4-B1：write-grants 的重编保真（口径与 version / minApi 同形）——重编不该把已
        // 声明的授权悄悄抹掉（作者改源重编后包会突然全部未授权）。导入路径恒空表（v1 无
        // 导入端声明载体：ERB 转码描述符不带 grants，pack.json 亦无该键）。
        val effectiveWriteGrants = installed?.writeGrants ?: emptyList()
        val spec = ArtifactSpec(declaredOverrides, effectiveVersion, effectiveMinApi, effectiveWriteGrants)
        return encodeArtifacts(base, files, compiled, copy, spec)
    }

    companion object {
        /**
         * 设备端并集产物的固定文件名（mods 根，[ModPatchLoader.PATCH_EXTENSION] 口径）。
         *
         * 存在理由：补丁窗口恒锚定裸 base 产物（I3），装载器按文件名序逐张叠加时，第二张
         * 同样锚裸 base 的窗口必撞 [com.eratw.port.core.code.ProgramOverlay] 的窗口对齐闸
         * ——多张设备端补丁**结构性不能并存**。所以导入链的产物只此一张：mods 里全部
         * 留档 .ERB + 本次新源一次性并集重编（= 原版 PC「全部口上一起装载」的设备端等价形）。
         */
        const val UNION_PATCH_NAME: String = "kojo-imports" + ModPatchLoader.PATCH_EXTENSION

        /** packageId 十六进制小写表（与 SHA-256 摘要前 4 字节同源）。 */
        private const val PACKAGE_ID_HEX: String = "0123456789abcdef"

        /** packageId 取摘要前几字节（裁定 3：8 位十六进制 = 4 字节）。 */
        private const val PACKAGE_ID_BYTES: Int = 4

        /** 每字节两位十六进制字符。 */
        private const val HEX_CHARS_PER_BYTE: Int = 2

        /** 单字节取数值的掩码。 */
        private const val BYTE_MASK: Int = 0xFF

        /** 低半字节掩码（`v and 0x0F`）。 */
        private const val NIBBLE_MASK: Int = 0x0F

        /** 高半字节右移位数。 */
        private const val NIBBLE_BITS: Int = 4

        /**
         * 纯资源包载体的包身份派生（MODDX3-A3）：`kojo-` 前缀 + sha256(作者自述包名) 前 8 位十六进制。
         *
         * 与 [ModImportController.packageIdFor] 同构（同前缀、同摘要前 4 字节、同 hex 表），只是
         * 输入语义不同：口上源包用「留档源名集合」，载体无源，用 pack.json 自述包名（[PackMeta.packageId]）。
         * 借用 kojo- 前缀 = 列表呈现 IMPORTED（[ModManageModel] 三态来源判据只看前缀）、有卸载按钮
         * 且资源面命名空间与普通包一致（ModResourceStore 按 packageId 读 `<pkg>.assets/`）。
         * 同一自述包名的载体重导 = 同 packageId = 原地替换（与口上源包「包级替换」同口径）。
         */
        internal fun assetPackageIdFor(declaredPackageId: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(declaredPackageId.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(PACKAGE_ID_BYTES * HEX_CHARS_PER_BYTE)
            for (i in 0 until PACKAGE_ID_BYTES) {
                val v = digest[i].toInt() and BYTE_MASK
                hex.append(PACKAGE_ID_HEX[v ushr NIBBLE_BITS]).append(PACKAGE_ID_HEX[v and NIBBLE_MASK])
            }
            return ModPackageInventory.PACKAGE_ID_PREFIX + hex.toString()
        }
    }
}

// ------------------------------------------------------------------
// 落盘与读回校验：只依赖参数、不碰控制器实例状态，故放顶层（也让控制器类体积
// 停在 detekt LargeClass 阈值内；.patch 不能走 ModStore.save，那边只收 .ERB 源文件面）。
// ------------------------------------------------------------------

/**
 * 原子落盘一份导入产物：同目录 `.tmp` → fsync → rename（镜像 [ModStore] 的原子性
 * 口径）。目标已存在（同集合重导 = 同 packageId）= 整份替换；任何失败都清理临时
 * 文件，目录里不会出现半截产物。
 *
 * `internal`（原 private）：ERAFIN-C3 成品包合同（[ModFinC3PackagedImport]）与编译链
 * 共用同一条原子落盘缝——成品包「直接落盘」不是第二套写盘实现，是同一份 tmp+fsync+rename。
 */
internal fun saveArtifact(dir: File, name: String, bytes: ByteArray) {
    ensureArtifactDir(dir)
    val target = File(dir, name)
    val tmp = File(dir, "$name.${System.nanoTime()}.tmp")
    var placed = false
    try {
        writeDurable(tmp, bytes)
        replaceArtifactFile(tmp, target)
        placed = true
    } finally {
        if (!placed) tmp.delete()
    }
}

/** 建产物目录（`mkdirs` 失败再复核一次：并发建目录时 mkdirs 返回 false 但目录已存在）。 */
private fun ensureArtifactDir(dir: File) {
    if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
        throw IOException("无法创建目录：${dir.absolutePath}")
    }
}

/** 写满并 fsync（不 fsync 的 rename 在断电后可能留下零字节产物）。 */
private fun writeDurable(file: File, bytes: ByteArray) {
    FileOutputStream(file).use { out ->
        out.write(bytes)
        out.flush()
        out.fd.sync()
    }
}

/** 同目录换名；目标已存在（同集合重导 = 同 packageId）先删再换，两段都可能抛 IOException。 */
private fun replaceArtifactFile(tmp: File, target: File) {
    if (tmp.renameTo(target)) return
    if (target.exists() && !target.delete()) {
        throw IOException("无法替换既有导入产物：${target.name}")
    }
    if (!tmp.renameTo(target)) throw IOException("无法落盘导入产物：${target.name}")
}

/**
 * 写读往返校验：容器走真 [ErbModuleContainer.decode]（摘要 + 记录区全量校验），
 * manifest 走真 [ManifestCodec.decode]，provenance 与内容指纹侧车按字节对账；四者
 * packageId/内容必须与本次派生值一致。读不出 / 解不开 / 对不上都在这里抛（调用方
 * 折成 IDENTITY 失败上屏）。
 *
 * 文件名从 packageId + [ModPackageInventory] 的扩展名现场派生：与落盘侧同源，
 * 不在参数里重复名字。
 */
private fun verifyWrittenPackage(
    dir: File,
    packageId: String,
    sourcesBytes: ByteArray,
    fingerprintBytes: ByteArray,
) {
    val erbmName = packageId + ModPackageInventory.MODULE_EXTENSION
    val manifestName = packageId + ModPackageInventory.MANIFEST_EXTENSION
    val sourcesName = packageId + ModPackageInventory.SOURCES_EXTENSION
    val fingerprintName = packageId + ModPackageInventory.FINGERPRINT_EXTENSION
    val module = readBackOrThrow(File(dir, erbmName), "模块容器", ErbModuleContainer::decode)
    check(module.packageId == packageId) {
        "模块容器 packageId 不符：期望 $packageId，实为 ${module.packageId}（$erbmName）"
    }
    val descriptor = readBackOrThrow(File(dir, manifestName), "manifest", ManifestCodec::decode)
    check(descriptor.packageId == packageId) {
        "manifest packageId 不符：期望 $packageId，实为 ${descriptor.packageId}（$manifestName）"
    }
    val sourcesReadBack = readBackOrThrow(File(dir, sourcesName), "provenance") { it }
    check(sourcesReadBack.contentEquals(sourcesBytes)) {
        "provenance 内容与本次留档源名单不符：$sourcesName"
    }
    val fingerprintReadBack = readBackOrThrow(File(dir, fingerprintName), "内容指纹") { it }
    check(fingerprintReadBack.contentEquals(fingerprintBytes)) {
        "内容指纹与本次留档源不符：$fingerprintName"
    }
}

/**
 * 异常原话段（`message ?: 类名`，控制器失败消息统一取法）：异常没有消息也没有类名的
 * 罕见形态按原字符串模板的渲染结果补成 "null"，保证收口前后逐字同形。原话本身是
 * 取证面（core 抛出的 detailMessage 保持原样不进文案表），只有外层的句子模板走
 * [ModImportCopy]。
 */
private fun detailText(e: Throwable): String = e.message ?: e::class.simpleName ?: "null"

/** 读回并解码一件刚落盘的产物；IO / 解码异常统一折成点名该文件的 [IOException]。 */
private fun <T> readBackOrThrow(file: File, label: String, decode: (ByteArray) -> T): T =
    try {
        decode(Files.readAllBytes(file.toPath()))
    } catch (e: IOException) {
        throw IOException("$label 落盘后读回失败：${file.name}（${detailText(e)}）", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("$label 落盘后读回失败：${file.name}（${detailText(e)}）", e)
    }

/**
 * 口上源文本的解码口：严格 UTF-8 试解，解不动回落 CP932。
 *
 * 与 core-vm 主装载链 `LogicalLineReader.decode` 同口径（严格 UTF-8 用
 * CharsetDecoder REPORT 模式，失败回落 windows-31j）；语义权威是原版
 * `EncodingHandler.cs:37-40`（无 BOM 时严格 UTF-8 探测、失败回退 CP932）。
 * core-vm 那份实现是 private 且 core 车道不归本路所有，故此处按同口径持有
 * 一份小实现（KDoc 对账，禁两边口径漂移——改解码规则须同步两处）。
 *
 * 社区口上文件几乎全是 Shift-JIS（CP932）编码：无条件按 UTF-8 解会把整份源变成
 * 乱码、编译全线爆炸（「导入口上包没有一个能成功」的主因）。
 *
 * CP932 用 `windows-31j`（JDK 的 CP932 等价名），缺字符集时退 `Shift_JIS` 近似——
 * 与 platform 层 MacroFile 的 charset 先例同款（platform/.../input/MacroFile.kt:60-62）。
 * JDK 裸 `Shift_JIS` 缺 NEC/IBM 扩展区（0x8740 ⇒ U+FFFD、0x815C ⇒ U+2014），
 * windows-31j 才是 CP932（0x8740 ⇒ U+2460、0x815C ⇒ U+2015）。
 *
 * BOM 分支（ERA-W4 收口，原 TODO(verify) 现位见 git 历史）：UTF-16/32 BOM 源按
 * BOM 直接解——判定序与 core-vm 主装载链 `LogicalLineReader.readAllLines` 的同款
 * 先例逐位同源（UTF-32 LE 必须先于 UTF-16 LE 判，前缀 FF FE 与后者重叠；spec:
 * EncodingHandler.cs:20-26 头 3 字节判 UTF-8 BOM + :27-32 StreamReader 的
 * detectEncodingFromByteOrderMarks 判其余 BOM，顺序 FF FE 00 00 → FF FE →
 * FE FF → 00 00 FE FF）。UTF-8 BOM 仍**不加**分支：EF BB BF 是合法 UTF-8 前缀，
 * 严格解出的首字符 U+FEFF 由 [ModImportController.firstFunctionNameOf] 的
 * trimStart 剥掉（主装载链 readAllLines 的 UTF-8 BOM 分支同效），保持既有路径
 * 逐字节不变。
 */
internal object ModSourceCodec {

    /** 日文回落编码 = CP932（`windows-31j`）；JVM 缺这个字符集时退回 `Shift_JIS` 等价近似。 */
    internal val CP932: Charset =
        runCatching { Charset.forName("windows-31j") }.getOrElse { Charset.forName("Shift_JIS") }

    /**
     * BOM 判定序与 core-vm `LogicalLineReader.readAllLines` 逐位同源
     * （spec: EncodingHandler.cs:20-32；UTF-32 LE 先于 UTF-16 LE 判，前缀重叠）。
     * 无 BOM 才走严格 UTF-8 → CP932 回落（[decodeUtf8OrCp932]）。
     */
    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 4 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte() ->
            String(bytes, 4, bytes.size - 4, Charsets.UTF_32LE)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 4 &&
            bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte() ->
            String(bytes, 4, bytes.size - 4, Charsets.UTF_32BE)
        else -> decodeUtf8OrCp932(bytes)
    }

    /**
     * 严格 UTF-8 解不动才回落 CP932。
     *
     * **判定维持（ERA-W4 复核，非收口项）**：回落档是宽松 REPLACE（坏字节 → U+FFFD），
     * 而原版 CP932 解码器是 `DecoderFallback.ExceptionFallback`（EncodingHandler.cs:67-71，
     * 坏字节抛）→ `EraStreamReader.Open` 吞成 false → **整文件跳过**（EraStreamReader.cs:50-59）。
     * 本函数无「读失败」回传通道，改严格须给解码失败补一条明确的导入失败口径，且
     * core-vm 同族 TODO 同样未接（LogicalLineReader.kt TODO(verify)：原版严格解码回退
     * 本波未接）——两边口径必须同步收口（本对象 KDoc 的对账义务），core 车道不归
     * 本路所有 ⇒ 跨车道，需组长接线，不单边硬改。语料侧实测：core 波 3,818 个 .ERB
     * 严格 CP932 失败 0 例（LogicalLineReader.kt TODO 记录），宽解只影响病态字节的
     * 失败形态，不影响真实口上包。
     */
    private fun decodeUtf8OrCp932(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        String(bytes, CP932)
    }
}

/**
 * 口上 zip 整包的解包口（纯 JVM `java.util.zip`，不碰 Android API；zip 字节在堆里，
 * 量级由 `ModStore.MAX_MOD_BYTES` 的读取闸兜住）。
 *
 * ## 条目筛选
 *
 * 解出 `.ERB`/`.ERH` 条目（[ModStore.hasErbExtension] / [ModStore.hasErhExtension]
 * 的大小写不敏感口径）与**角色模板** `Chara*.csv` 条目（[isCharaTemplateCsv] 的
 * fail-closed 名单）；txt/其余 csv/图片等条目跳过**不报错**——口上包夹带 readme 与
 * 素材是常态。目录条目（名字以 `/` 或 `\` 结尾）与空名条目同样跳过。
 *
 * `Chara*.csv` 是 ERA-A2「ERB 数据轨加可攻略新角色」的随包模板通道：这类条目由
 * [ModImportController.import] 在 ⓪ 展开后**分流**到 `mods/rt2/<packageId>.chara/`
 * 落盘，不进编译源面 `flat`（CSV 不是 ERB 语句）。[unpack] 的 keep 谓词把它与
 * ERB/ERH 一起收进来只是为了共用同一趟 [entriesWhere] 闸扫描，调用方必须按
 * [isCharaTemplateCsv] 再分一次流。
 *
 * ## 条目名编码探测
 *
 * 社区口上包主流是 Windows 打包（条目名 CP932），跨平台打包用 UTF-8；`java.util.zip`
 * 不做启发，选错 charset 时日文条目名变乱码（留档名不可读）。整包字节已在堆里，
 * 按两种 charset 各扫一遍条目名（零内容读取），「乱码名」（含 U+FFFD——两种 decoder
 * 对非法序列都 REPLACE 出它）少者胜，平手取 UTF-8。文本**内容**不受此影响：
 * 条目字节统一走 [ModSourceCodec] 的 UTF-8/CP932 解码口。
 *
 * ## 资源上限闸（不可信输入）
 *
 * 条目数 / 单条目体积 / 整包体积 / 压缩比 / 条目重名由 [ModZipGuard] 在**读流时**边读边
 * 计数，越限立刻抛 [ModZipGuard.Rejected] 早退（绝不先把条目读完再检查——检查之前堆
 * 已经爆了）。计数口径是整包所有条目（含被过滤掉的），[entriesWhere] 的单趟扫描同时
 * 承担「读命中条目进堆」与「整包过闸」两件事，资源面二扫读数与 ⓪ 一致。
 *
 * ## 失败面（调用方折成 STORAGE）
 *
 * 闸门拒绝抛 [ModZipGuard.Rejected]（[ModImportController.import] 单独 catch 折成拒因
 * 文案）；加密条目（zip 标准加密位）在内容读取时抛 [ZipException]；坏 zip / 非 zip 字节
 * 在 [nextEntry][ZipInputStream.getNextEntry] 即抛。三者都从 [unpack] 冒泡，由
 * [ModImportController.import] 捕获折成 STORAGE 明确报错，禁静默产出乱字节。
 */
internal object ModZip {

    /** 解出的一个源条目：[name] 保留 zip 内相对路径（排序、编译 relPath 与报错定位都用它）。 */
    class Entry(val name: String, val bytes: ByteArray)

    /** displayName 是否为口上 zip 包（`.ZIP` 尾段，大小写不敏感、必须有主名）。 */
    fun isZipName(name: String): Boolean = hasFoldedExtension(name, ".ZIP")

    /** 按条目名编码探测解出全部源条目与随包角色模板；两者皆无时返回空表。 */
    fun unpack(zipBytes: ByteArray): List<Entry> =
        entriesWhere(zipBytes) {
            ModStore.hasErbExtension(it) || ModStore.hasErhExtension(it) || isCharaTemplateCsv(it)
        }

    /**
     * 按 [keep] 筛条目名、**只把命中条目的字节进堆**（未命中的不 copy，资源面里的大图不会
     * 因为「顺便看一眼」进堆两次）。
     *
     * 目录项判定与 [unpack] 同一套（空名 / `'/'` / `'\\'` 收尾一律跳过）；[keep] 收到的是
     * zip 内原始条目名（未归一）。资源面（[ModImportAssets]）用它只捞 `.assets/` 候选。
     *
     * **单趟同时过 [ModZipGuard] 闸**：每个条目的字节都流式读一遍（跳过的条目也读、只是不
     * 进堆），条目数 / 单条目 / 整包 / 压缩比 / 重名任一越限立刻抛 [ModZipGuard.Rejected]。
     * 跳过的条目绝不能靠 [ZipInputStream.getNextEntry] 内部丢弃——那样闸就看不到它们的
     * 解压量，炸弹可以藏在任意非源条目里。
     */
    fun entriesWhere(zipBytes: ByteArray, keep: (String) -> Boolean): List<Entry> {
        val charset = nameCharsetOf(zipBytes)
        val progress = ModZipGuard.Progress(zipBytes.size.toLong())
        val entries = ArrayList<Entry>()
        ZipInputStream(ByteArrayInputStream(zipBytes), charset).use { zin ->
            while (true) {
                val item = zin.nextEntry ?: break
                val name = item.name
                progress.beginEntry(name)
                val out = if (isDirectoryEntry(name) || !keep(name)) null else ByteArrayOutputStream()
                ModZipGuard.readThrough(zin, name, progress, out)
                if (out != null) entries += Entry(name, out.toByteArray())
            }
        }
        return entries
    }

    /**
     * 只列条目名、**不把任何条目字节进堆**（资源前缀探测用：素材包里可能有几十 MB 图，不能为了
     * 看一眼目录把它们读进堆）。目录项判定同 [entriesWhere]。
     *
     * **全部五道闸照过**（[ModZipGuard.Progress.beginEntry] + [ModZipGuard.readThrough] 丢弃
     * 读，`out = null`）：[ZipInputStream.getNextEntry] 推进到下一条目前会把当前条目剩余数据
     * 完整解压丢弃（closeEntry 语义），只读名的路径不显式计数的话，压缩炸弹条目在这里被
     * 静默全量 inflate——条目洪泛拖不死这条路径，炸弹照样拖死。调用方都在 ⓪ 整包过闸之后，
     * charset 与 ⓪ 同一判定（[nameCharsetOf]）⇒ 五道闸是对已放行 zip 的幂等复核，不会误伤。
     */
    fun entryNames(zipBytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        val progress = ModZipGuard.Progress(zipBytes.size.toLong())
        ZipInputStream(ByteArrayInputStream(zipBytes), nameCharsetOf(zipBytes)).use { zin ->
            while (true) {
                val item = zin.nextEntry ?: break
                val name = item.name
                progress.beginEntry(name)
                ModZipGuard.readThrough(zin, name, progress, out = null)
                if (isDirectoryEntry(name)) continue
                names += name
            }
        }
        return names
    }

    /**
     * 目录条目判定：空名 / `'/'` 收尾 / `'\\'` 收尾（个别 Windows 打包器的写法）都算目录。
     *
     * 单独成函数：三处扫描（[entriesWhere] / [entryNames] / [mojibakeNameCount]）的目录口径
     * 必须逐字一致，改一处漏一处会让「哪些条目算候选」在两条路径上漂移。
     */
    private fun isDirectoryEntry(name: String): Boolean =
        name.isEmpty() || name.endsWith('/') || name.endsWith('\\')

    /**
     * 取**固定名**条目的字节；条目不在、或解压后超过 [maxBytes] ⇒ null（一律按「没有该条目」
     * 处理）。包元数据读取专用：边读边数字节，超限立刻放弃并返回，不把整段内容读进堆
     * （坏档/恶意包不能靠一条元数据条目把导入拖成 OOM）。
     *
     * **单趟同时过 [ModZipGuard] 五道闸**（与 [entriesWhere] / [entryNames] 同款）：非目标条目
     * 显式 [ModZipGuard.readThrough] 丢弃读计数——绝不能靠 [ZipInputStream.getNextEntry] 内部的
     * closeEntry 排水，那样闸看不到目标条目之前任意条目的解压量，压缩炸弹可以藏在它们里面
     * （M1-003 同坑；目标条目超 [maxBytes] 时改由「读尽后按尺寸弃用」处理，剩余数据同样全程
     * 计数）。调用面两类：外层包 zip（⓪ 已整包过闸 ⇒ 五道闸是幂等复核，不误伤）与 `code.mod`
     * 产物的**内层** zip（外层闸只管 `code.mod` 条目解压后的文件字节，内层再压缩是新的压缩域，
     * 没有 ⓪ 可借 ⇒ 这里就是内层的唯一体积闸）；越闸抛 [ModZipGuard.Rejected]，内层调用方
     * （[ModDexCarrierSource.readMeta]）必须就地折叠成单包归因行，不许把装配带崩。
     */
    fun fixedEntryBytes(zipBytes: ByteArray, entryName: String, maxBytes: Int): ByteArray? {
        val progress = ModZipGuard.Progress(zipBytes.size.toLong())
        ZipInputStream(ByteArrayInputStream(zipBytes), nameCharsetOf(zipBytes)).use { zin ->
            var found: ByteArray? = null
            while (found == null) {
                val item = zin.nextEntry ?: break
                progress.beginEntry(item.name)
                val sink = if (item.name == entryName) ByteArrayOutputStream() else null
                ModZipGuard.readThrough(zin, item.name, progress, sink)
                if (sink != null && sink.size() <= maxBytes) {
                    found = sink.toByteArray()
                }
            }
            return found
        }
    }


    /**
     * 条目名 charset 择优：两种候选（[Charsets.UTF_8] / CP932）各扫一遍条目名，
     * 「乱码名」（含 U+FFFD——两种 decoder 对非法序列都 REPLACE 出它）少者胜，平手取 UTF-8。
     *
     * 候选池就是 [NAME_CHARSETS]：**同一对** charset 也供 [looksLikeAssetPack] 双探，
     * 严禁改一处漏一处（改编码口径须两边同步）。
     */
    private fun nameCharsetOf(zipBytes: ByteArray): Charset =
        if (mojibakeNameCount(zipBytes, Charsets.UTF_8) <= mojibakeNameCount(zipBytes, ModSourceCodec.CP932)) {
            Charsets.UTF_8
        } else {
            ModSourceCodec.CP932
        }

    /**
     * 判定 zip 是否「像素材包」——跨卡指路（2026-11）：素材包被选进补丁（MOD）导入卡时，
     * 报错要指向「素材导入」卡，而不是只说没有 `.ERB`/`.ERH` 条目。
     *
     * [ModImportController.import] 只在 [unpack] 解出 0 条 `.ERB`/`.ERH` 时才问本函数：
     * 判 true 换成素材包指路文案；判 false（真·空口上包）维持既有 zipNoEntriesFormat。
     * 含 `.ERB`/`.ERH` 的口上包根本走不到这里，所以「口上包夹带素材」不会被误判。
     *
     * ## 判据（与 [ResourcePackStore] 的投放合同逐条同源）
     *
     * 1. 任一条目路径含 `resources` 或 `sound` 段（素材卡的两个投放根；段名 ASCII 大小写
     *    不敏感，与 [ResourcePackStore.normalizeEntry] 的段匹配同口径）；
     * 2. 根级（允许外面包一层下载目录——GitHub 的 zip 常带 `<repo>-master/`，素材卡同样
     *    允许一层包裹）出现 `BGI.csv` 文件、`背景图` 段或 `游戏bgm` 段：前者是背景图包
     *    的索引，后两者是「只丢一棵裸目录」的两种常见包形态（与 [ResourcePackStore] 的
     *    [ResourcePackStore.BACKGROUND_DIR] / [ResourcePackStore.BGM_DIR] 同一组常量）。
     *
     * ## charset 双探（与 [unpack] 同源）
     *
     * 素材包条目名常是中文 Windows 的 GBK / 日文 Windows 的 CP932 字节：按 UTF-8 解出的
     * `背景图` 是一串替换符，只探一种编码会漏判。两种候选各扫一遍、任一命中即算——
     * 与 [nameCharsetOf] 是**同一对** charset（那边按乱码计数择优，这边只求命中）。
     *
     * ## 失败面
     *
     * 空字节 / 坏 zip 一律 false：本函数只是「指路」的加分项，任何读不出来的形态都回到
     * 调用方的既有文案，绝不允许把一种失败改写成另一种。只读条目名、不读条目内容。
     */
    fun looksLikeAssetPack(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        for (charset in NAME_CHARSETS) {
            val hit = try {
                containsAssetEntry(bytes, charset)
            } catch (e: Exception) {
                // 坏 zip 在条目名层就抛；一种 charset 抛不排除另一种能读，逐档兜住。
                false
            }
            if (hit) return true
        }
        return false
    }

    /**
     * [looksLikeAssetPack] 的单档扫描：整包条目名扫完，任一命中 [isAssetEntryPath] 即真。
     *
     * 命中不提前返回：中途抛错（半截坏 zip）由调用方整档判 false——「坏 zip 一律 false」
     * 的口径要的是**整包可读**才敢指路，扫一半就下结论会把损坏包误报成素材包。
     */
    private fun containsAssetEntry(zipBytes: ByteArray, charset: Charset): Boolean {
        var hit = false
        ZipInputStream(ByteArrayInputStream(zipBytes), charset).use { zin ->
            while (true) {
                val item = zin.nextEntry ?: break
                if (isAssetEntryPath(item.name)) hit = true
            }
        }
        return hit
    }

    /**
     * 单个条目名（zip 内相对路径）是否落在素材卡的投放合同里，判据见 [looksLikeAssetPack]。
     * 反斜杠当分隔符（个别 Windows 打包器这么写条目名）；`.` 与空段丢掉后再判。
     */
    private fun isAssetEntryPath(rawName: String): Boolean {
        val segments = rawName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return false
        if (segments.any {
                it.equals(ResourcePackStore.RESOURCES_DIR, ignoreCase = true) ||
                    it.equals(ResourcePackStore.SOUND_DIR, ignoreCase = true)
            }
        ) {
            return true
        }
        // 根级：原样一条，或剥掉一层外层包裹（下载 zip 常见 `<repo>-master/`）。
        for (root in listOf(segments, segments.drop(1))) {
            if (root.isEmpty()) continue
            if (root[0].equals(BGI_CSV, ignoreCase = true)) return true
            if (root[0].equals(ResourcePackStore.BACKGROUND_DIR, ignoreCase = true) ||
                root[0].equals(ResourcePackStore.BGM_DIR, ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    /** 条目名双探的 charset 候选：与 [nameCharsetOf] 的择优池是**同一对**（禁两边各写一套）。 */
    private val NAME_CHARSETS: List<Charset> = listOf(Charsets.UTF_8, ModSourceCodec.CP932)

    /**
     * 背景图包根级的索引文件名（[looksLikeAssetPack] 判据 2）：`resources/背景图` 的图片靠
     * 这份 CSV 注册进资源表，孤零零一张它也是背景图包；[ResourcePackStore] 里同名逻辑
     * 走「目录里的 csv」聚合判定，无同名常量，故在此点名。
     */
    private const val BGI_CSV = "BGI.csv"

}

/**
 * 尾段扩展名逐字符 ASCII 折叠比较（与 ModStore.hasNamedExtension 的 foldCase 同思路，
 * locale 无关）。**两侧都折**：extension 常量写成大写、实际文件名常是小写，只折一侧
 * 会让判定永不命中（2026-11 实际踩过：`pack.zip` 判不成 zip，整包被当文本源编译）。
 *
 * 放在 [ModZip] 之外：纯字符串工具，不碰对象状态；对象方法数已到 detekt 阈值，
 * 工具函数不占名额。
 */
private fun hasFoldedExtension(name: String, extension: String): Boolean {
    val start = name.length - extension.length
    var matched = start > 0
    var i = 0
    while (matched && i < extension.length) {
        matched = name[start + i].lowercaseChar() == extension[i].lowercaseChar()
        i++
    }
    return matched
}

/**
 * 随包角色模板的 fail-closed 名单判定（ERA-A2 收录面）：只有条目**主名**以 `Chara`
 * 开头（大小写不敏感，与 CharaCsvLoader 的 `CHARA*.csv` 发现口径一致）且以 `.csv`
 * 结尾（大小写不敏感、主名非空）才收；其余 CSV（`data.csv` / `BGI.csv` 等）一律不收。
 *
 * 路径穿越与绝对路径一并拒：条目名以 `/` / `\` 开头、或含 `..` 段时不算模板
 * （[ModZip.entriesWhere] 的 keep 谓词返回 false ⇒ 条目被流式读过后丢弃，绝不进堆；
 * 落盘也只取主名，不按条目名建目录）。纯字符串函数，不碰对象状态。
 */
private fun isCharaTemplateCsv(entryName: String): Boolean {
    val normalized = entryName.replace('\\', '/')
    val safePath = entryName.isNotEmpty() && !entryName.startsWith('/') && !entryName.startsWith('\\') &&
        normalized.split('/').none { it == ".." }
    val base = normalized.substringAfterLast('/')
    return safePath && base.length > CHARA_PREFIX.length &&
        base.regionMatches(0, CHARA_PREFIX, 0, CHARA_PREFIX.length, ignoreCase = true) &&
        hasFoldedExtension(base, CHARA_CSV_EXTENSION)
}

/** [isCharaTemplateCsv] 的主名前缀与扩展名（大写常量给 [hasFoldedExtension] 折两侧用）。 */
private const val CHARA_PREFIX = "chara"
private const val CHARA_CSV_EXTENSION = ".CSV"

/** 随包模板落盘子目录后缀：`mods/rt2/<packageId>.chara/`（ERA-A2 收录面）。 */
private const val CHARA_TEMPLATE_DIR_SUFFIX = ".chara"

/**
 * 乱码条目名计数：名字含 U+FFFD 替换符 = 该 charset 解错了条目名。
 *
 * 放在 [ModZip] 之外：只依赖 zip 字节与 charset，不碰任何对象状态；口上包对象的方法数已到
 * detekt 阈值，纯工具函数不占名额。
 *
 * 扫到 [ModZipGuard.MAX_ENTRIES] 条就停：本函数只是「前若干条名字够不够判 charset」的
 * 启发式，不能先被条目洪泛拖成整包扫描（真越限由随后的 [ModZip.entriesWhere] 抛闸）。
 *
 * 条目内容**显式丢弃读、带总预算**（[PROBE_DISCARD_BUDGET]）：本探针是整条导入 / 预览链
 * **第一个触碰条目内容的点**（charset 未定，守卫流建不出来），而
 * [ZipInputStream.getNextEntry] 的 closeEntry 会把当前条目剩余数据完整解压丢弃——不计数
 * 的话，压缩炸弹条目在这里被全量 inflate（64 MiB 压缩可放大到数十 GB）且守卫的早退闸
 * 完全看不见。预算爆掉就停探、**不抛**：charset 只是启发式，这包横竖会被随后的守卫闸以
 * 体积类拒因拒绝（拒因与 charset 无关），探针结果无所谓。刻意不挂
 * [ModZipGuard.Progress]：重名闸按解码后的名字判定，UTF-8 探针把 CP932 合法名解成
 * U+FFFD 塌缩串会误判重名——探针层不许新增任何抛点（否则合法 Shift-JIS 包会被误拒）。
 */
private fun mojibakeNameCount(zipBytes: ByteArray, charset: Charset): Int {
    var bad = 0
    var seen = 0
    var budgetLeft = PROBE_DISCARD_BUDGET
    ZipInputStream(ByteArrayInputStream(zipBytes), charset).use { zin ->
        while (seen < ModZipGuard.MAX_ENTRIES && budgetLeft > 0L) {
            val item = zin.nextEntry ?: break
            seen++
            if (item.name.indexOf('\uFFFD') >= 0) bad++
            budgetLeft -= discardWithinBudget(zin, budgetLeft)
        }
    }
    return bad
}

/**
 * charset 探针丢弃读的总预算（[mojibakeNameCount] 两次探针各自独立计一次）。复用
 * [ModZipGuard.MAX_TOTAL_BYTES]（整包解压上界同口径，不新增阈值）：守卫放行的包总解压量
 * 不会越过它 ⇒ 探针预算对合法包永不触发，charset 判定与无预算时完全一致；只有注定被守卫
 * 拒掉的包才会把预算吃穿，而那种包的 charset 判定结果无关紧要。
 */
private const val PROBE_DISCARD_BUDGET: Long = ModZipGuard.MAX_TOTAL_BYTES

/**
 * 把 [zin] 当前条目剩余数据读到结尾（丢弃，不进堆），最多 [budget] 字节；返回实际读到的
 * 字节数。超预算就停（单块粒度，与 [ModZipGuard.readThrough] 的早退粒度同源）。调用方预算
 * 归零后**不得再推进** [ZipInputStream.getNextEntry]，否则 closeEntry 会把半截条目的剩余
 * 数据无预算地丢完，预算闸就被绕开了。
 */
private fun discardWithinBudget(zin: ZipInputStream, budget: Long): Long {
    val buffer = ByteArray(ModZipGuard.READ_CHUNK_BYTES)
    var total = 0L
    while (total < budget) {
        val read = zin.read(buffer)
        if (read < 0) break
        total += read
    }
    return total
}

/**
 * [ModImportController.compileToModule] 的 ③ 编译相位产出：`PatchResult` + 符号表/改名告警
 * （后者并入导入报告的 parseWarnings）。
 */
private class PatchCompileOutput(
    val result: PatchCompiler.PatchResult,
    val symbolWarnings: List<String>,
    val renameWarnings: List<String>,
)

/** [ModCompileException] 的唯一 `throw` 出口：各相位函数只调本函数，不各写一条 throw。 */
private fun failCompile(
    kind: ModImportFailureKind,
    message: String,
    cause: Throwable? = null,
): Nothing = throw ModCompileException(kind, message, cause)

/** 把编译相位的意外异常折成 [ModCompileException]（本链已折好的原样透传，其余按 UNEXPECTED）。 */
private fun Throwable.rethrowCompileFailureOr(copy: ModImportCopy): Nothing {
    if (this is ModCompileException) throw this
    failCompile(
        ModImportFailureKind.UNEXPECTED,
        copy.fill(copy.compileUnexpectedFormat, this::class.simpleName ?: "null", message ?: "null"),
        this,
    )
}

// ---- ③ 编译（M2-4 裁定 2：只编本次导入的源，留档源不再拉进并集）----
// 每次导入独立成包；同一源集合重导 = 同 packageId = 原地替换。多口上包组合
// 由链接期 LinkOrder 解决（era-arch-v61u.md:75「不再需要源码」）。
private fun ModImportController.compilePatch(
    base: BaseCompileContext,
    importSources: List<PickedModFile>,
    gameRoot: File,
    copy: ModImportCopy,
): PatchCompileOutput {
    // entry 跨源回退（B4，2026-11 整包导入实测）：排序第一的文件可能整文件在
    // [SKIPSTART] 块内（群友整合包的 M_KOJO_K16_5_IMAGE.ERB 排最前、全文件被跳过，
    // PatchCompiler 同样不编译它）——按装载序找第一个能提供 @ 函数头的源；全部为空
    // 才报 COMPILE。旧实现只看第一个文件，整包在「入口函数不存在」上死。
    // 宏表 = base 侧车 dimParseConfig.macros，与 PatchCompiler 编译期同一张表
    // （ERA-W4 收口，见 [firstFunctionNameOf] KDoc）。
    val entry = importSources.firstNotNullOfOrNull {
        firstFunctionNameOf(it.text, base.metadata.dimParseConfig.macros.keys)
    } ?: failCompile(ModImportFailureKind.COMPILE, copy.fill(copy.noEntryFunctionFormat, importSources.size))
    // 侧车灌装告警（R7-D3 P0：global-dims 容忍模式的失败条目明细）在 try 内赋值、
    // 块外并入 parseWarnings——I7 口径：告警不拦截导入，但必须可上屏可定位。
    var symbolWarnings: List<String> = emptyList()
    var renameWarnings: List<String> = emptyList()
    val result = try {
        // 生效配置一口取定：`useERD` 决定补丁编译的折叠面是否含 ERD 表
        // （与全量编译 CorpusCompile 的 `ConfigKey.UseERD` 门同源）。
        val deviceConfig = com.eratw.port.core.config.ConfigStore()
        com.eratw.port.core.config.EmueraConfigParser.loadChainFromDisk(gameRoot.toPath(), deviceConfig)
        val effective = com.eratw.port.core.config.EffectiveConfig.of(deviceConfig)
        val symbolBuild = buildPatchSymbolTable(
            base.metadata,
            base.csvDir,
            useERD = effective.bool(com.eratw.port.core.config.ConfigKey.UseERD),
        )
        symbolWarnings = symbolBuild.warnings
        val sources = importSources.map { PatchCompiler.SourceFile(relPath = it.displayName, text = it.text) }
        // `[[替换名]]` 字典与全量编译同源（CSV/_Rename.csv）：口上包大量写
        // `CASE [[比基尼套装]]`，不喂字典则 `[[` 漏到词法器撞 LexicalAnalyzer.cs:809-813
        // 的 RenameDic==null 分支，整包解析失败（2026-11 蕾米莉亚包实测）。
        // CONFIG-WIRE U7：装载按 `useRenameFile` 门控（spec: Process.cs:261-274 只在
        // 开关开时 `LoadEraExRenameFile`；ErbLoader.cs:370 逐文件再按
        // `UseRenameFile && RenameDic != null` 决定应用）——开关关时喂字典反而是
        // 偏离原版。开关开而文件缺席保持 null 并留痕（原版 PrintError MissingRename，
        // Process.cs:270-273；后续 `[[` 撞词法器与原版同形）。
        val useRename = effective.useRenameFile
        // 同名非事件函数重复定义的装载期告警门（U8-② / L26）：原版门 =
        // `!label.IsEvent && Config.WarnNormalFunctionOverloading`（spec: ErbLoader.cs:466；
        // 键缺省 false，spec: ConfigData.cs:99），4.981 出厂 emuera.config 第 48 行是
        // `DUPLICATED FUNCTIONS WARNING:YES` ⇒ 出厂行为不变；玩家关掉开关后，导入报告
        // 不再刷「同名覆盖」告警（覆盖语义不变，仍是后定义者生效）。
        val warnDuplicateFunctions =
            effective.bool(com.eratw.port.core.config.ConfigKey.WarnNormalFunctionOverloading)
        val renameDic = if (useRename) RenameDictionary.load(base.csvDir?.toPath()) else null
        if (useRename && renameDic == null && base.csvDir != null) {
            renameWarnings = listOf(
                "USE _RENAME.CSV FILE:YES 但 CSV/_Rename.csv 不存在，[[置換名]] 不展开" +
                    "（原版 PrintError MissingRename，spec: Process.cs:270-273）",
            )
        }
        PatchCompiler(
            base.metadata,
            base.baseCounts,
            symbolBuild.table,
            renameDic = renameDic,
            // 常量关键字下标解析期折成整数（原版装载期 Restructure 同相位）；
            // 不喂则补丁里 `M_CFLAG:LOCAL:…` 退回运行期按名查盘上 CSV。
            constantKeywordLookup = symbolBuild.keywordLookup,
            // 同名非事件函数重复定义告警按玩家配置门控（出厂 YES ⇒ 行为不变）。
            warnNormalFunctionOverloading = warnDuplicateFunctions,
            // 2026-11-23 KI 改判（BOARD KI-2）：撞名覆盖不再需要前置判据——原 R7-① 参数
            // `overrideDeclarable`（base 侧车给得出签名指纹才放行覆盖，给不出整包硬拒）已随
            // 链接期三态改判（SymbolCatalog.build diagnostics 告警放行）撤销：撞名一律按覆盖
            // 装载 + 告警（原版语义 = 后装载者胜出，spec: LabelDictionary.cs:107-113、
            // ErbLoader.cs:466-475），缺指纹键的 ABI 未校验告警由转码降级清单汇进导入告警面。
        )
            .compile(sources, entry)
    } catch (e: PatchCompiler.PatchCompileException) {
        failCompile(ModImportFailureKind.COMPILE, e.message ?: copy.compileFailedFallback, e)
    } catch (e: IllegalArgumentException) {
        // PatchCompiler.compile 的 require（补丁源为空 / entry 为空）同属编译面。
        failCompile(ModImportFailureKind.COMPILE, e.message ?: copy.compileFailedFallback, e)
    }
    return PatchCompileOutput(result, symbolWarnings, renameWarnings)
}

// ---- ④ 进口即转码：窗口补丁 → 零基线 Module + RT2 manifest（M2-4 裁定 1）----
// 产物经既有生产装配路消费（mods/rt2 单向配对 → ModChainAssembly → linker →
// plan → GameSession），运行期单管线；不再写 kojo-imports.patch。
private fun ModImportController.encodeArtifacts(
    base: BaseCompileContext,
    files: List<PickedModFile>,
    compiled: PatchCompileOutput,
    copy: ModImportCopy,
    spec: ArtifactSpec,
): CompiledModuleArtifacts {
    val program = compiled.result.program
    val baseCounts = base.baseCounts
    val packageId = packageIdFor(files.map { it.diskName })
    val transcoded = runCatching {
        PatchImportTranscoder.transcode(
            compiled.result,
            baseCounts,
            base.metadata.functions.associate { it.index to it.key },
            packageId,
            // 同一个查询口直递转码器：编译期判「能不能覆盖」与转码期取「覆盖的指纹」
            // 必须是同一份数据，不留第二口径。末位 = 包清单的可选收窄声明
            // （有清单才有；null = 按同名自动推导）。
            PatchImportTranscoder.OverrideInputs(base.baseSignatures, spec.declaredOverrides),
        )
    }.getOrElse { e ->
        if (e is IllegalArgumentException) {
            failCompile(ModImportFailureKind.COMPILE, e.message ?: copy.compileFailedFallback, e)
        } else {
            failCompile(
                ModImportFailureKind.UNEXPECTED,
                copy.fill(copy.compileUnexpectedFormat, e::class.simpleName ?: "null", e.message ?: "null"),
                e,
            )
        }
    }
    // 版本号 / min-api / write-grants 由导入端赋值，不交给转码器（core 层保持零策略）：转码器
    // 产出的 descriptor 版本恒为历史默认值、min-api 恒 0、write-grants 恒空表，这里按包元数据
    // 读到的作者声明覆盖后再编码（min-api 只在非 0 时由 ManifestCodec 升 `/6` 并写段，
    // write-grants 只在非空时升 `/8`，空时字节与老包逐字相同）。
    validateImportWriteGrants(base, spec.writeGrants)
    val descriptor = transcoded.descriptor.copy(
        version = spec.version,
        minApi = spec.minApi,
        writeGrants = spec.writeGrants,
    )
    val erbmBytes = runCatching { ErbModuleContainer.encode(transcoded.module) }.getOrElse { e ->
        failCompile(ModImportFailureKind.COMPILE, copy.fill(copy.artifactEncodeFailedFormat, detailText(e)), e)
    }
    val manifestBytes = runCatching { ManifestCodec.encode(descriptor) }.getOrElse { e ->
        failCompile(ModImportFailureKind.COMPILE, copy.fill(copy.artifactEncodeFailedFormat, detailText(e)), e)
    }
    return CompiledModuleArtifacts(
        packageId = packageId,
        erbmBytes = erbmBytes,
        manifestBytes = manifestBytes,
        report = CompiledModuleReport(
            functionCount = program.functions.size,
            overrideCount = transcoded.descriptor.overrideFunctions.size,
            instructionCount = program.instructionCount - baseCounts.instructionCount,
            entryFunction = compiled.result.entryFunction,
            parseWarnings = compiled.result.parseWarnings + compiled.symbolWarnings +
                compiled.renameWarnings +
                // 2026-11-23 KI 改判（BOARD KI-2）：降级覆盖键告警——base 侧车无该键签名指纹，
                // 覆盖照常装载（链接期 last-wins 放行）但 ABI 未校验，玩家可见（KI-4 报错页复用）。
                transcoded.degradedOverrideKeys.map { key ->
                    "覆盖函数 $key 在本体侧车无签名指纹，按覆盖装载但 ABI 未校验——" +
                        "若该函数形参/返回通道与本体不一致，报错在运行期，请向口上作者反馈"
                },
        ),
    )
}

/**
 * 编码相位的「产物落盘口径」：覆盖声明 + 版本号 + min-api 段 + write-grants 段。
 *
 * 收成一个参数而不是各自占位，是为了不把 [encodeArtifacts] 的参数表继续拉长（detekt
 * 参数表上限）；四者同属「这次导入要写进 manifest 的口径」，不是四个独立概念。
 */
private class ArtifactSpec(
    val declaredOverrides: List<String>?,
    val version: String,
    val minApi: Int,
    /** ModDX4-B1：随产物 manifest 落盘的受控写授权（重编保真携带；导入路径恒空表）。 */
    val writeGrants: List<WriteGrantDeclaration> = emptyList(),
)

/**
 * 声明期 write-grants 校验（ModDX4-B1，ModImportController 的 manifest 解析/校验段）：
 * 坏 grant（键不存在 / 类型不符 / 角色变量 / 不可寻址形态 / 常量）**整包导入拒**
 * （[ModCompileException] / COMPILE 面，与编译失败同一收口 —— 无任何产物落盘）。
 *
 * 目录真源 = [buildPatchSymbolTable] 的 base 变量表（CSV 数据面 + 侧车 global-dims，与补丁
 * 编译器解析期同一张表）；只在 grants 非空时才构建（今天 100% 的导入包 grants 为空表，
 * 代价为零路径 —— 与 `installedManifestOf` 回落同类的惰性防御）。校验判据与装载相位
 * [ModDx4WriteGate.build] 同一清单，逐条对齐：那边是运行时授权闸的声明面兜底，这边在
 * 导入链入口就点名原因。
 */
private fun ModImportController.validateImportWriteGrants(
    base: BaseCompileContext,
    grants: List<WriteGrantDeclaration>,
) {
    if (grants.isEmpty()) return
    val table = buildPatchSymbolTable(base.metadata, base.csvDir, useERD = false).table
    for (grant in grants) {
        val reason = importGrantRejectionReason(table, grant) ?: continue
        throw ModCompileException(ModImportFailureKind.COMPILE, reason)
    }
}

/**
 * 单条 grant 的导入期校验（判据清单与装载相位 [ModDx4WriteGate.build] 逐条对齐）：
 * 非法返回点名原因的拒绝文案，合法返回 null。懒链逐条求值、首个命中即拒绝原因，
 * 单一 return 构成 detekt ReturnCount 合规形；判据与装载相位两侧对齐，改一处须同步另一处。
 */
private fun importGrantRejectionReason(
    table: com.eratw.port.core.stmt.SymbolTable,
    grant: WriteGrantDeclaration,
): String? {
    val info = table.resolveVariable(grant.name, null)
    val checks = listOf<() -> String?>(
        {
            if (info == null) {
                "write-grants 键不存在：${grant.name}（base 变量表里没有这个名字；坏 grant 整包导入拒）"
            } else {
                null
            }
        },
        {
            if (checkNotNull(info).isCharacterData) {
                "write-grants 键是角色变量：${grant.name}（角色变量写不在写面 v1；坏 grant 整包导入拒）"
            } else {
                null
            }
        },
        {
            val dimension = checkNotNull(info).dimension
            if (dimension > 1) {
                "write-grants 键不可按名寻址：${grant.name}（$dimension 维变量，" +
                    "本写面只达 0 维标量与 1 维非角色数组；坏 grant 整包导入拒）"
            } else {
                null
            }
        },
        {
            if (checkNotNull(info).isConst) {
                "write-grants 键是常量：${grant.name}（CSV/GAMEBASE 常量与 #DIM CONST 不可写；" +
                    "坏 grant 整包导入拒）"
            } else {
                null
            }
        },
        {
            if (checkNotNull(info).operandType == OperandType.FLOAT) {
                "write-grants 键类型不符：${grant.name}（引擎定义是 FLOAT，本写面只有 INT/STR 两通道；" +
                    "坏 grant 整包导入拒）"
            } else {
                null
            }
        },
        {
            if ((checkNotNull(info).operandType == OperandType.STR) != grant.isStr) {
                "write-grants 键类型不符：${grant.name}（引擎定义是 ${checkNotNull(info).operandType}，" +
                    "授权写的是 ${if (grant.isStr) "STR" else "INT"}；不隐式转换，坏 grant 整包导入拒）"
            } else {
                null
            }
        },
    )
    return checks.firstNotNullOfOrNull { check -> check() }
}


/**
 * [ModImportController.compileToModule] 的产物：两件二进制 + 导入报告的计数面（不含落盘）。
 *
 * 字节已经过 [ErbModuleContainer] / [ManifestCodec] 编码，但调用方**仍须在落正式名前
 * 解码自检**（[ModRecompiler.replaceArtifacts] 的做法）：编码器坏字节不许进 `mods/rt2`。
 */
internal class CompiledModuleArtifacts(
    /** 包标识：由源落盘名集合算定（导入与重编都必须与盘上现役包同名）。 */
    val packageId: String,
    /** `.erbm` 容器字节（`ErbModuleContainer.encode`）。 */
    val erbmBytes: ByteArray,
    /** RT2 `.manifest` 字节（`ManifestCodec.encode`）。 */
    val manifestBytes: ByteArray,
    /** 导入报告面（计数 + 告警；与两件产物字节同一份编译结果）。 */
    val report: CompiledModuleReport,
)

/**
 * [CompiledModuleArtifacts] 的报告面：导入链 [ModImportController.import] 组装
 * `ImportedPatchDetail` 的计数/告警来源；重编链只用两件产物字节，不读报告面。
 */
internal class CompiledModuleReport(
    /** 模块函数总数（导入报告面）。 */
    val functionCount: Int,
    /** 声明式覆盖的函数数（导入报告面）。 */
    val overrideCount: Int,
    /** 本补丁新增指令数（已扣 base 基数）。 */
    val instructionCount: Int,
    /** 补丁定位用的入口函数名（装载期无语义，见 [ModImportController.firstFunctionNameOf]）。 */
    val entryFunction: String,
    /** 编译/符号表/改名告警（`result + symbol + rename` 序，导入链再并去重与迁移告警）。 */
    val parseWarnings: List<String>,
)

/**
 * [ModImportController.compileToModule] 的失败出口：kind 与 [ModImportFailureKind] 同值域，
 * 文案已按 [ModImportCopy] 取好语言（非主线程不再碰语言源）。
 *
 * 导入链 [ModImportController.import] 折回 [ModImportOutcome.Failed]；重编链
 * [ModRecompiler.recompileWith] 折回 `RecompileResult.Failed`。两边的共同承诺：
 * 抛本异常时**没有任何产物被写到正式名**（编译相位纯内存，落盘/替换只在成功路径发生）。
 */
internal class ModCompileException(
    val kind: ModImportFailureKind,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 附加面 best-effort 落盘：写失败只回一条 WARN，不回退已成功的主面（同主链 ⑤‴ / ⑤″ 口径）。
 * 返回 null = 写成功；返回文案 = 该文案并进 WARN 清单上屏。
 */
private inline fun bestEffortWrite(failurePrefix: String, write: () -> Unit): String? =
    runCatching { write() }.exceptionOrNull()?.let { failurePrefix + detailText(it) }

/**
 * 媒体资源面落盘（ADR-014 S4）：与 `.assets` 同口径——失败只留 WARN、不回退已成功的源留档/
 * 产物/`.assets`；`.media` 清单换名与旧文件清理都在 [ModImportMediaAssets.Staged.commit] 内
 * 完成（失败尽力回滚，KDoc 已声明不承诺强事务）。
 *
 * 提为文件级（而非 [ModImportController] 成员）是为了压该类 `LargeClass` 行数闸（实测 602/600）。
 */
private fun commitMedia(
    plan: ModImportMediaAssets.Plan,
    copy: ModImportCopy,
    warnings: MutableList<String>,
) {
    val staged = plan.staged ?: return
    runCatching { staged.commit() }.onFailure { e ->
        staged.discard()
        warnings += copy.fill(copy.mediaCommitFailedFormat, detailText(e))
    }
}

/**
 * 无代码产物分支的身份侧车清理：`null` = 干净（含本来就没有），非 null = 删不掉，调用方折
 * STORAGE 整包拒导。
 *
 * 放在类外只为把 [ModImportController] 的类体行数留在 detekt LargeClass 阈值内——它仍是导入链
 * ⑤‴ 的一段（`ModCodeIdentity.clear` 的失败面包装）。
 */
private fun clearCodeIdentity(rt2Dir: File, packageId: String): IOException? =
    if (ModCodeIdentity.clear(rt2Dir, packageId)) {
        null
    } else {
        IOException("代码身份侧车删除失败：${ModCodeIdentity.fileName(packageId)}")
    }

/**
 * 导入链 ⑤‴ 代码产物收尾：落盘（含 `<diskPkg>.codeid` 侧车）或反向清理旧侧车，失败面整包拒导。
 *
 * 返回 `null` = 已就绪/已清理；非 null = 本次导入失败（兄弟面暂存已 discard、异常已打栈）。
 * 搬到类外是为了类体行数闸（同 [clearCodeIdentity]），语义与失败分类逐条不变：
 * - [ModCodeIntegrityException]（内容寻址/只读/侧车被篡改）⇒ IDENTITY；
 * - 其余 IO / 侧车写失败 ⇒ STORAGE。
 */
private fun ModImportCodeArtifacts.Staged?.finalizeCodeArtifact(
    rt2Dir: File,
    packageId: String,
    copy: ModImportCopy,
    assetPlan: ModImportAssets.Plan,
    mediaPlan: ModImportMediaAssets.Plan,
): ModImportOutcome.Failed? {
    val failure: Pair<ModImportFailureKind, Throwable>? = if (this != null) {
        runCatching { commit(ModCodeStore(rt2Dir), rt2Dir) }
            .exceptionOrNull()
            ?.let { cause ->
                discard()
                val kind = if (cause is ModCodeIntegrityException) {
                    ModImportFailureKind.IDENTITY
                } else {
                    ModImportFailureKind.STORAGE
                }
                kind to cause
            }
    } else {
        clearCodeIdentity(rt2Dir, packageId)?.let { ModImportFailureKind.STORAGE to it }
    }
    val (kind, cause) = failure ?: return null
    assetPlan.staged?.discard()
    mediaPlan.staged?.discard()
    cause.printStackTrace()
    return ModImportOutcome.Failed(kind, copy.fill(copy.codeArtifactCommitFailedFormat, detailText(cause)))
}
