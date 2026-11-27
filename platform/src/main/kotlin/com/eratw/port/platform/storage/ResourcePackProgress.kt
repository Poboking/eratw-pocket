package com.eratw.port.platform.storage

/**
 * 素材直投（背景图 / BGM）的**实时进度**（2026-11-26 第二十一批）。
 *
 * ## 为什么需要它
 *
 * 直投是「选文件夹 → 逐条流式写到游戏根」：一个几百 MB 的背景图夹、上百首 BGM，全程在
 * 后台线里跑，此前对 UI 一个字节都不说。玩家点完按钮后卡上只剩一句「正在导入素材…」，
 * 跑上一两分钟看起来就是卡死——这是玩家反馈的原话来源。
 *
 * 本件只描述**事实**（阶段、已写多少、总量多少、正在写哪一条），不带玩家句子：句子在
 * app 侧 [com.eratw.port.android.ResourcePackCopy] 里按语言出词，平台层不碰文案。
 *
 * ## 读数的诚实边界
 *
 * - [totalFiles] 是「按扩展名与目标过滤后**打算投放**的条数」，不是源里的全部条数；
 *   同名冲突、地点 0-9 满员、越界路径等仍会让实际落盘数少于它——所以 [doneFiles] 与
 *   [totalFiles] 相等只在真正收尾时才保证（收尾那一条由 store 强制对齐）。
 * - [totalBytes] 只累加源声明了 size 的条目（SAF 提供方不给 size 时为 -1，按 0 计）。
 *   声明总量为 0 时比例退化为「按文件数」，两者都不可知时 [fraction] 为 null（不定态扫描带）。
 * - [doneBytes] 是**实际写入**的字节（含正在写的那一条已落缓冲的部分），以写入量为准而不是
 *   声明量：声明可以撒谎，进度条不能跟着撒谎。
 */
data class ResourcePackProgress(
    val phase: ResourcePackPhase,
    /** 已写完的文件数（[ResourcePackPhase.WRITING] 之后才有意义）。 */
    val doneFiles: Int = 0,
    /** 计划投放的文件数（枚举完才知道；扫描阶段为 0）。 */
    val totalFiles: Int = 0,
    /** 实际写入字节（含当前文件已写部分）。 */
    val doneBytes: Long = 0L,
    /** 源声明总字节（未知 size 的条目按 0 计；为 0 时比例退化到按文件数）。 */
    val totalBytes: Long = 0L,
    /** 正在写的那一条（源侧相对名，`/` 分隔；用于卡片上「当前：xxx」）。 */
    val currentName: String? = null,
    /** 扫描阶段已发现的条目数（[ResourcePackPhase.SCANNING] 专用）。 */
    val discovered: Int = 0,
) {

    /**
     * 进度条比例：优先按字节（大文件也有连续推进），声明总量不可知时按文件数；
     * 两者都没有（还没枚举完、或整批没有可投放条目）返回 null —— 由 UI 画不定态扫描带。
     */
    val fraction: Float?
        get() {
            if (doneBytes > 0L && totalBytes > 0L) {
                return (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            }
            if (totalFiles > 0) return (doneFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
            return null
        }
}

/** 直投的四个阶段（顺序即真实执行顺序）。 */
enum class ResourcePackPhase {
    /** 枚举来源（SAF 目录树可能要逐层查几百次，这一段本身就可能耗时数秒）。 */
    SCANNING,

    /** 逐条写入游戏根（长尾主要在这里）。 */
    WRITING,

    /** 文件写完，收尾（背景图无现成 CSV 时要生成 自动索引.csv）。 */
    INDEXING,

    /** 收尾已出报告（此时 UI 一般已切到结果态，只用于把最后一条读数对齐到 100%）。 */
    FINISHED,
}
