package com.eratw.port.platform.image

import java.util.concurrent.ConcurrentHashMap

/**
 * 「这个名字/路径刚刚查失败过」的短时负缓存。
 *
 * ## 它解决什么
 *
 * 绘制侧取图是**每帧**发生的：`CanvasConsoleView.onDraw` 对屏上每个图形段各问一次
 * [SpriteBitmapSource.frame]，而后者在缓存未命中时会真的碰磁盘
 * （`GameImageFiles.decodeByResourceName` → `locate` + `BitmapFactory.decodeFile`）。
 * 语料里有一批**永远解不出来**的名字 —— 资源名拼错、图确实不存在、解码器不支持该
 * 编码、单张超过 `maxSide` 被主动丢弃。这些名字没有缓存条目可命中，于是**每一帧**
 * 都要重走一次 `File.isFile` + 解码尝试。
 *
 * 一张解不出来的图，重试一次与重试六十次的结果完全一样；而 60 fps 下每帧几十次
 * 磁盘 stat/解码失败，是主线程实打实的开销 —— 玩家反馈的「快速切换场景 / 打开指令
 * 菜单时闪退」，主线程被这类重复失败拖长正是其中一条路径（切场景时新名字最多）。
 *
 * ## 判据与代价
 *
 * 失败后 [ttlMs] 内直接判「没有」，不再碰磁盘；过期自动放行一次，于是「文件后来
 * 才落地」（玩家自装 mod、解包未完成）最多晚一个 TTL 就被重新看见。换代
 * （[SpriteBitmapSource.invalidate]，读档 / RESTART / `SPRITECREATE` 覆盖）会 [clear]
 * 全部记录 —— 那条路上的资源集合本来就要重新认识。
 *
 * 取舍写在这里：宁可让一张**暂时**失败的图晚 [ttlMs] 出现，也不要每帧为它付一次
 * 磁盘解码尝试。原版 PC 上这张名字表是常驻字典（`AppContents.cs:39-51`），查不到
 * 就恒返回 null、**一次都不碰磁盘**；本类是把那个「查不到就不再找」的形态补回来，
 * 只是给它加了一条到期重试，因为安卓侧的资源可能晚于第一帧到位。
 *
 * ## 线程
 *
 * 读侧在 UI 线程（每帧），写侧也在 UI 线程（同一帧内解码失败），但 `invalidate()`
 * 来自 VM 线程 —— 所以底层用 [ConcurrentHashMap]，`clear` 与 `markFailed` 并发时
 * 最坏只是多记/少记一条，不影响正确性（下次失败会再记）。
 *
 * 纯逻辑、不认识 `Bitmap`，因此能在纯 JVM 单测里钉死（见 `FailedLookupCacheTest`）。
 *
 * @param ttlMs 失败记录的有效期（毫秒）；过期后允许再试一次
 * @param maxEntries 记录条数上限；到顶后整表清空（语料静态，重记一次的代价远小于
 *        为「淘汰哪一条」写一套 LRU）
 */
internal class FailedLookupCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    /** 键 → 失败时刻（[ttlMs] 的计时原点，调用方给单调时钟读数）。 */
    private val failures = ConcurrentHashMap<String, Long>()

    /** 当前记录条数（诊断/单测用）。 */
    val size: Int get() = failures.size

    /**
     * 这个名字现在该不该**跳过**（= 刚刚失败过且还没到期）。
     *
     * 到期的那条会顺手摘掉：不摘的话表只会越来越大，而到期判据本身就要读它。
     */
    fun shouldSkip(key: String, nowMs: Long): Boolean {
        val failedAt = failures[key] ?: return false
        // 减法比大小而不是 `failedAt + ttlMs <= nowMs`：时间戳是毫秒 Long，
        // 减法在溢出语义上更稳（与 DeferredRecycle.drain 同口径）。
        if (nowMs - failedAt >= ttlMs) {
            // 只在值没被别的线程改写时摘：并发下另一线程可能刚重新记了一条新的失败时刻，
            // 那种情况留着它（下一次 shouldSkip 会按新时刻判）。
            failures.remove(key, failedAt)
            return false
        }
        return true
    }

    /** 记一次失败。到容量上限时整表清空（见类 KDoc 的取舍）。 */
    fun markFailed(key: String, nowMs: Long) {
        if (failures.size >= maxEntries) failures.clear()
        failures[key] = nowMs
    }

    /** 忘掉全部记录（换代 / 退出时用）。 */
    fun clear() {
        failures.clear()
    }

    companion object {
        /**
         * 缺省有效期（毫秒）。
         *
         * 5 s 的取值依据：它要短于「玩家察觉图不见了」的耐心，又要长于一次场景切换的
         * 全部重绘窗口（真机重画间隔 p50 79 ms / max 494 ms，见 BOARD 的 GMF 读数），
         * 否则同一次切换里同一个失败名字还会被反复重试几十次。
         */
        const val DEFAULT_TTL_MS = 5_000L

        /** 记录上限：语料里解不出来的名字是两位数级别，4096 条足够兜住异常语料。 */
        const val DEFAULT_MAX_ENTRIES = 4096
    }
}
