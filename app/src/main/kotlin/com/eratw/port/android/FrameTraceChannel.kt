package com.eratw.port.android

import android.content.Context
import com.eratw.port.render.canvas.CanvasConsoleView
import com.eratw.port.render.canvas.FrameTrace

/**
 * 逐帧诊断的**玩家可用取证通道**（2026-11-23「地图疯狂跳动」专项）。
 *
 * ## 为什么需要这一层
 * 逐帧环形缓冲本来只挂 DevTools 命令（`frames on`），而 `DevToolsHost.startOnce` 有硬闸
 * `if (!DEBUGGABLE) return` —— release 包根本不起 `cmd.txt` 通道，玩家敲不了命令；
 * 而换 debug 包要换签名、玩家装了得卸载、存档全丢。所以玩家侧必须换一条不依赖 adb 的路：
 * **设置页开关**（[UiPrefs.frameTraceEnabled]）+ **报告带帧**（分享诊断时整段进报告）。
 *
 * 环放在这一层而不是 View 里，是因为「记的人」与「读的人」不是同一个 Activity：
 * 记录发生在游戏画面的 `CanvasConsoleView.onDraw`（[MainActivity]），报告生成发生在
 * 设置页或失败屏（[ErrorReportCollector]）。进程级单例让两边共享同一个环 —— 与
 * `LockWatch` / `SlowUiGate` 同一套 app 层口径（core 层禁静态可变全局，本层不禁）。
 *
 * ## 生命周期与零开销
 * [attach] / [detach] 只在 `MainActivity` 的起停边界调用：挂上时把环交给 View
 * （`CanvasConsoleView.setFrameTrace`），摘掉时置 null 并清空环形缓冲 —— 于是
 * 「关掉开关 ⇒ 环清空」不依赖玩家再进一次设置页。**开关关时 `onDraw` 只多一次
 * `@Volatile` 字段读**（View 侧 `frameTrace` 为 null），不建对象、不碰行表。
 *
 * ## 报告口径
 * [reportLines] 出的是「补充现场」里的独立小节：表头注明记录期，正文是 [FrameTrace.dump]
 * 的逐帧表 + 自动判定行；环空（开关没开或刚关掉）时只写一行「未开启逐帧诊断」，
 * 让报告读者一眼分清「没数据」与「数据正常」。
 */
object FrameTraceChannel {

    /** 与设置页开关同源的进程级环形缓冲（容量 = [FrameTrace.DEFAULT_CAPACITY]，240 帧约 4 秒现场）。 */
    val trace: FrameTrace = FrameTrace(FrameTrace.DEFAULT_CAPACITY)

    @Volatile
    private var attachedView: CanvasConsoleView? = null

    /** 报告小节标题（表头注记录期，见类文档）。 */
    const val SECTION_TITLE: String = "== 逐帧诊断（设置页「记录逐帧诊断」开启期间记录，最近 240 帧）=="

    /** 环空且开关关着时写的那一行 —— 告诉玩家去哪里开。 */
    const val EMPTY_LINE: String = "未开启逐帧诊断（设置 → 诊断 → 记录逐帧诊断，复现问题后再点分享）"

    /** 开关开着但还没落到任何帧（刚打开就点分享）：与「没开」分开写，免得玩家以为开关没用。 */
    const val NO_FRAMES_LINE: String = "已开启逐帧诊断，但还没有画面帧记录下来（回到游戏玩一会儿、复现问题后再分享）"

    /**
     * 是否开着（真源 = [UiPrefs.frameTraceEnabled]）。
     *
     * 只读设置，不看 [attachedView]：`MainActivity` 在 `onStart` 用它决定挂/摘，
     * 报告生成方也用它写「未开启」那一行。
     */
    fun enabled(context: Context): Boolean = UiPrefs.frameTraceEnabled(context)

    /**
     * 挂到游戏画面上（清空上一段现场再开始记）。
     *
     * 为什么清空：诊断包是「这一段帧怎么摆」的快照，把上一次开开关时的现场混进来
     * 会把报告读者引到错的页。
     */
    fun attach(view: CanvasConsoleView) {
        trace.clear()
        attachedView = view
        view.setFrameTrace(trace)
    }

    /** 摘掉（并清空环形缓冲）—— 关掉开关后报告里立刻变回「未开启」。 */
    fun detach() {
        attachedView?.setFrameTrace(null)
        attachedView = null
        trace.clear()
    }

    /** 按设置位同步一次（`MainActivity.onStart` / `onResume` 与设置页开关都用这一条）。 */
    fun sync(context: Context, view: CanvasConsoleView) {
        if (enabled(context)) attach(view) else detach()
    }

    /**
     * 报告里的那一节（独立段落，`ErrorReportCollector` 的两条路共用）。
     *
     * 三种形态各写清楚，报告读者一眼能分清：
     * - 开关关着 ⇒ [EMPTY_LINE]（去哪里开）；
     * - 开关开着但环还空 ⇒ [NO_FRAMES_LINE]；
     * - 有数据 ⇒ 表头 + 全环摘要与尾部明细 + 自动判定行（[FrameTrace.summaryLines]）。
     *
     * **为什么不是整张 240 帧的表**：错误报告的「补充现场」是共享尾窗
     * （`ErrorReport.EXTRA_MAX_LINES` = 80 条物理行，核心层 `takeLast`），240 行表格会把同一
     * 窗口里的内存读数、心跳、冻结现场整段挤出去。所以报告只带全环统计 + 最近 20 帧明细，
     * 完整表仍在 DevTools 的 `frames` 命令里。
     *
     * @param enabled 当前设置位（由调用方用 `FrameTraceChannel.enabled(context)` 取；
     *   本函数不碰 Context，便于纯 JVM 单测）
     *
     * 任何异常都退化成一行说明 —— 报告生成路径绝不允许因为诊断小节而整份失败。
     */
    fun reportLines(enabled: Boolean): List<String> = runCatching {
        when {
            !enabled -> listOf("", SECTION_TITLE, EMPTY_LINE)
            trace.size == 0 -> listOf("", SECTION_TITLE, NO_FRAMES_LINE)
            else -> listOf("", SECTION_TITLE) + trace.summaryLines(FrameTrace.REPORT_FRAME_ROWS)
        }
    }.getOrElse { listOf("", SECTION_TITLE, "逐帧诊断读取失败：${it.javaClass.simpleName}") }

    /** 环里现有帧数（单测与 DevTools 读数用）。 */
    fun size(): Int = trace.size
}
