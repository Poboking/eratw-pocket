package com.eratw.port.android

import kotlin.math.roundToInt

// 各相位的权重（= 实测耗时占比，取整到 0.4 精度；来源见 [ModImportStage] 的 KDoc）。
// 单独起名而不是裸字面量：一是权重表要能一眼校对，二是改相位顺序时不至于把两个数字看串。
private const val WEIGHT_UNPACK = 4f
private const val WEIGHT_SAVE_SOURCES = 3f
private const val WEIGHT_BASE_CONTEXT = 58f
private const val WEIGHT_SYMBOL_TABLE = 12f
private const val WEIGHT_COMPILE = 14f
private const val WEIGHT_ENCODE = 5f
private const val WEIGHT_STAGE_ASSETS = 2f
private const val WEIGHT_WRITE = 1f
private const val WEIGHT_VERIFY = 0.6f
private const val WEIGHT_MIGRATE = 0.4f

/** 比例 → 百分数（与 [ModImportStage.TOTAL_WEIGHT] 无关，纯单位换算）。 */
private const val PERCENT_SCALE = 100f

/**
 * 导入链的**阶段表**（进度条的唯一真相源）。
 *
 * ## 为什么阶段要细到 10 段
 * 进度条只需要「玩家看得懂的五段」，但性能取证需要「时间到底花在哪」。两者用同一套
 * 枚举、两种投影：枚举按真实相位切（含 `BASE_CONTEXT` / `SYMBOL_TABLE` 这种玩家不必
 * 知道的内部相位），玩家面文案只取 [display]（五段）。**不为了好看把相位合并掉** ——
 * 合并了就再也量不出「读 base 产物」占了多少。
 *
 * ## 权重怎么来的
 * 权重 = 真包（因幡帝海外版 V8，32 源）在本机的实测占比取整，见
 * `<internal-workdir>/CONTRACT-IMPORT-UX.md` 的读数表。权重只影响进度条的**视觉速度**，
 * 不影响任何判定：估得不准最多是「走得不匀」，绝不会让导入多做或少做一步。
 */
enum class ModImportStage(val display: ModImportStageDisplay, internal val weight: Float) {
    /** 解包 zip / 认非 zip 容器（逐选中文件推进）。 */
    UNPACK(ModImportStageDisplay.READING, WEIGHT_UNPACK),

    /** 源 `.ERB/.ERH` 留档到 mods（逐文件推进）。 */
    SAVE_SOURCES(ModImportStageDisplay.READING, WEIGHT_SAVE_SOURCES),

    /** 读 base 产物：383 MB `program.erbc` mmap + 61 MB `program.meta` 侧车解析。 */
    BASE_CONTEXT(ModImportStageDisplay.COMPILING, WEIGHT_BASE_CONTEXT),

    /** 符号表：CSV 装载 + ERD + global-dims + 替换名字典。 */
    SYMBOL_TABLE(ModImportStageDisplay.COMPILING, WEIGHT_SYMBOL_TABLE),

    /** PatchCompiler 逐文件解析 + poison 收敛 + 发射。 */
    COMPILE(ModImportStageDisplay.COMPILING, WEIGHT_COMPILE),

    /** 转码 + 容器/清单编码。 */
    ENCODE(ModImportStageDisplay.COMPILING, WEIGHT_ENCODE),

    /** 资源面 / 媒体面 / 代码产物面扫描与暂存。 */
    STAGE_ASSETS(ModImportStageDisplay.WRITING, WEIGHT_STAGE_ASSETS),

    /** 四件套落盘（含 fsync）。 */
    WRITE(ModImportStageDisplay.WRITING, WEIGHT_WRITE),

    /** 读回校验。 */
    VERIFY(ModImportStageDisplay.FINISHING, WEIGHT_VERIFY),

    /** 存量 `.patch` 迁移（无存量时零耗时）。 */
    MIGRATE(ModImportStageDisplay.FINISHING, WEIGHT_MIGRATE),
    ;

    internal companion object {
        /** 权重总和（分母）：加阶段必须同步这里，否则进度条永远走不满。 */
        const val TOTAL_WEIGHT: Float = 100f

        /** 按权重累计出的「已走完前面所有阶段」的比例。 */
        fun completedBefore(stage: ModImportStage): Float {
            var sum = 0f
            for (s in entries) {
                if (s == stage) break
                sum += s.weight
            }
            return sum / TOTAL_WEIGHT
        }
    }
}

/**
 * `STAGE_ASSETS` 的分母：资源面 / 媒体面 / 代码产物面 —— **三个面**同相位推进。
 * 分母是「面」不是「文件」：每一面内部有多少条目只有它自己知道，逐文件播报会失真。
 */
internal const val STAGE_ASSET_FACES = 3

/**
 * 玩家面的五段（进度条上那句话）。枚举值即语义，文案在 [ModImportCopy] 的三语表里。
 */
enum class ModImportStageDisplay {
    /** 读取与解包源。 */
    READING,

    /** 编译补丁。 */
    COMPILING,

    /** 写入补丁产物。 */
    WRITING,

    /** 校验与收尾。 */
    FINISHING,
}

/**
 * 一发进度读数（跨线程传递的不可变快照）。
 *
 * [overall] 已含阶段权重与阶段内比例，UI 直接喂给进度条即可；[done]/[total] 只用于
 * 「第 12/32 个文件」这类细分读数（[total] = 0 表示本阶段不可细分）。
 */
data class ModImportProgress(
    val stage: ModImportStage,
    val overall: Float,
    val done: Int = 0,
    val total: Int = 0,
) {
    /** 百分比整数（显示用；向上取整避免开跑就显示 0% 长时间不动）。 */
    val percent: Int get() = (overall.coerceIn(0f, 1f) * PERCENT_SCALE).roundToInt()

    /** 本阶段是否可细分（可细分时 UI 才显示「12/32」）。 */
    val hasItems: Boolean get() = total > 0
}

/**
 * 进度发射器：控制器内部唯一的进度出口。
 *
 * 职责只有三件：记住「上一个阶段已走完」的累计权重、按阶段内比例折成 [ModImportProgress]、
 * 去重后交给 [sink]。**不发重复读数**（同阶段同进度），避免 UI 线程被无意义的
 * `runOnUiThread` 淹没；`sink` 为 null 时整条链零开销（重编路径就是 null）。
 */
internal class ModImportProgressTracker(
    private val sink: ((ModImportProgress) -> Unit)?,
) {
    private var stage: ModImportStage = ModImportStage.UNPACK
    private var stageBase: Float = 0f
    private var done: Int = 0
    private var total: Int = 0
    private var lastEmitted: ModImportProgress? = null

    /** 进入新阶段（上一阶段视作已走完）。[items] > 0 表示本阶段按条目推进。 */
    fun enter(next: ModImportStage, items: Int = 0) {
        stage = next
        stageBase = ModImportStage.completedBefore(next)
        done = 0
        total = if (items > 0) items else 0
        emit(force = true)
    }

    /** 本阶段推进一项（[count] 默认 1）。 */
    fun advance(count: Int = 1) {
        done += count
        emit(force = false)
    }

    /** 本阶段直接置为「已推进到 [value]」。 */
    fun advanceTo(value: Int) {
        done = value
        emit(force = false)
    }

    /** 本阶段收尾（不进入下一阶段时用；保证进度条走到本阶段末）。 */
    fun finishStage() {
        if (total > 0) done = total
        emit(force = true)
    }

    /** 整链收尾：强制打满 100%（正常路径早已到 99%+，这里只兜住四舍五入的尾巴）。 */
    fun complete() {
        stage = ModImportStage.entries.last()
        stageBase = ModImportStage.completedBefore(stage)
        total = if (total > 0) total else 0
        done = if (total > 0) total else done
        emit(force = true, clampFull = true)
    }

    private fun emit(force: Boolean, clampFull: Boolean = false) {
        val sink = sink ?: return
        val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 1f
        val overall = if (clampFull) 1f else (stageBase + stage.weight / ModImportStage.TOTAL_WEIGHT * fraction)
        val next = ModImportProgress(stage, overall.coerceIn(0f, 1f), done, total)
        if (!force && next == lastEmitted) return
        lastEmitted = next
        sink(next)
    }
}

/** 玩家面的阶段文案（三语表在 [ModImportCopy]；这里只做「阶段 → 词」的一处映射）。 */
internal fun ModImportCopy.stageText(display: ModImportStageDisplay): String = when (display) {
    ModImportStageDisplay.READING -> stageReading
    ModImportStageDisplay.COMPILING -> stageCompiling
    ModImportStageDisplay.WRITING -> stageWriting
    ModImportStageDisplay.FINISHING -> stageFinishing
}
