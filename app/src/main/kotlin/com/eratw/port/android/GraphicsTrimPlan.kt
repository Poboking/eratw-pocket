package com.eratw.port.android

import android.content.ComponentCallbacks2

/**
 * `onTrimMemory` 档位 → 要不要收图形位图缓存（[GameHost.trimGraphicsCaches]）的分流表
 * （2026-10-31 稳定性审计 #5）。
 *
 * ## 为什么必须查表，不能用 `level >= …`
 *
 * `ComponentCallbacks2.TRIM_MEMORY_*` 是**两套不成序的刻度**拼在一起：
 * RUNNING 系 = 进程在前台、堆贴顶（RUNNING_MODERATE=5 < RUNNING_LOW=10 < RUNNING_CRITICAL=15），
 * 后台系 = 界面没了/进程进缓存（UI_HIDDEN=20、BACKGROUND=40、MODERATE=60、COMPLETE=80）。
 * 任何拿数值比大小的判式都是拿两套刻度的**巧合**当契约——旧写法
 * `level >= RUNNING_LOW(10)` 恰好只漏了最轻的 RUNNING_MODERATE(5)，但同样的比较一旦
 * 挪到别的基准（比如有人照着「UI_HIDDEN=20 更大」把它当更狠的档位来写）就会把整个
 * 前台 RUNNING 系全漏掉。改成逐档点名，数值巧合不再参与判定。
 *
 * ## 为什么 UI_HIDDEN(20) 也收，而不是「切后台只收小的」
 *
 * 回合边界的水位判据（[GameHost.releaseImagePixelsIfHeapHigh]）只看 **Java** 堆，
 * 平台层 `SpriteBitmapSource` 的三张 LruCache 是 native 位图，那套判据**看不见**它们 ——
 * `onTrimMemory` 是运行期唯一的 native 表回收入口。UI_HIDDEN 是其中最可靠、时机最好的一发
 * （切后台必发、发在屏幕消失之后）：工作表清了最坏是回前台重解一次，不清则后台进程顶着
 * 上百 MiB 的 native 位被 vivo（`ro.lmk.kill_heaviest_task=true`）当成最重任务杀掉 ——
 * 玩家报的「玩了一会儿就没了」正是这个形状。
 *
 * 未知档位（0、负数、OEM 自造值）一律**不收**：宁可不做也不炸，与 `onTrimMemory` 里
 * 其余 `runCatching` 兜底同口径。
 */
internal object GraphicsTrimPlan {
    /** 命中即收（帧缓冲 + 文件位图 + native 工作表）：七个系统档逐个点名，数值只进注释不进判定。 */
    private val COLLECT_LEVELS = setOf(
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, // 5
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,      // 10
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, // 15
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,        // 20
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,       // 40
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,         // 60
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,         // 80
    )

    /** 查表：该档位要不要收位图缓存。判式与上方 [COLLECT_LEVELS] 逐档同源。 */
    fun dropsSheets(level: Int): Boolean = level in COLLECT_LEVELS
}
