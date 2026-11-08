package com.eratw.port.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 崩溃屏（C6）：玩家当场读得到「出了什么事」，而不是对着桌面反复点图标。
 *
 * ## 为什么必须是独立进程（manifest 的 `android:process=":crash"`）
 *
 * 崩溃线程可能就是 UI 线程：那一刻原进程的 Choreographer 已经不会再喂下一帧，任何在
 * `MainActivity` 窗口上画的尝试都可能只画到一半就被系统回收。独立进程不共享那条死线程的
 * 消息循环，于是「当场」两个字才成立。代价是：**不许碰 `MainActivity` 的任何状态**——
 * 本类只吃 intent 里的五个标量（见 [EXTRA_EXCEPTION_TYPE] 一族），界面上的一切都用
 * 平台控件现搭（`ScrollView` + `TextView` + 两颗 `Button`），不引 Compose 树。
 *
 * ## 为什么这一屏自己取语言
 *
 * 崩溃处理器跑在崩溃线程上（可能是 UI 线程），在那里读首选项就是一次 UI 线程磁盘读——
 * 那不在取证豁免窗口里。本进程（`:crash`）不装 StrictMode（[EratwApplication] 对
 * `:crash` 进程早退），所以在自己的 `onCreate` 里读一次是安全的。
 *
 * ## 一切失败都静默降级
 *
 * 取语言失败退中文，摘要解析失败退标题，连视图都搭不起来就摆一个最朴素的 `TextView`。
 * 这一屏的职责是「别让玩家什么都看不到」，它自己再抛一次就是拿「想显示错误」换「更快的死」。
 *
 * 主题刻意用平台自己的 `Theme.Material.NoActionBar`（manifest 里点名），不依赖本应用的
 * 主题资源：这套界面存在的场景就是「进程刚刚出过事」，少解析一层应用资源就少一个失败点。
 */
class CrashScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val copy = runCatching { CrashScreenCopy.of(UiPrefs.settingsLanguage(this)) }
            .getOrDefault(CrashScreenCopy.ZH)
        val summary = runCatching { summaryOf(intent) }.getOrNull()
        val body = runCatching {
            summary?.let { CrashSummaryText.screenText(copy, it) } ?: copy.title
        }.getOrDefault(copy.title)
        val content = runCatching { buildContent(copy, body) }.getOrElse { fallbackView(body) }
        runCatching { setContentView(content) }
    }

    /** 界面：可滚正文 + 「重新启动」「关闭」两颗按钮（全平台控件，无 Compose）。 */
    private fun buildContent(copy: CrashScreenCopy, body: String): View {
        val message = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dip(28), dip(48), dip(28), dip(24))
            // 允许长按复制：玩家发反馈时不必依赖截图（口径同崩壳失败最后一屏）。
            setTextIsSelectable(true)
            text = body
        }
        val scroll = ScrollView(this).apply { addView(message) }
        val restart = Button(this).apply {
            text = copy.restartButton
            isAllCaps = false
            setOnClickListener { relaunchGame() }
        }
        val close = Button(this).apply {
            text = copy.closeButton
            isAllCaps = false
            setOnClickListener { finishQuietly() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(restart, weighted())
            addView(close, weighted())
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
            )
            addView(
                buttons,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /** 连控件都搭不起来时的最后一屏：一行可选文字，至少还能截图。 */
    private fun fallbackView(body: String): View = TextView(this).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.BLACK)
        textSize = 15f
        setPadding(dip(28), dip(48), dip(28), dip(24))
        setTextIsSelectable(true)
        text = body
    }

    /**
     * 「重新启动」：直接起主进程的 [MainActivity]。
     *
     * 崩溃屏是前台窗口，所以这一发不受「后台启动 Activity」限制；`CLEAR_TASK` 把死进程留下
     * 的旧任务清掉。起不来（ROM 拦了）就留在这一屏，玩家还能点「关闭」，不会静默什么都不做。
     */
    private fun relaunchGame() {
        val started = runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                ),
            )
        }.onFailure { Log.w(LOG_TAG, "崩溃屏重启游戏失败（留在本屏等玩家关闭）", it) }.isSuccess
        if (started) finishQuietly()
    }

    /** 「关闭」：结束这一屏。不额外杀进程 —— `:crash` 进程里没有别的组件，系统自会回收。 */
    private fun finishQuietly() {
        runCatching { finishAndRemoveTask() }
            .onFailure { runCatching { finish() } }
    }

    /** intent 里的五个标量 → 摘要；异常类型缺失（不可能是我们发的）返回 null。 */
    private fun summaryOf(intent: Intent?): CrashSummary? {
        val type = intent?.getStringExtra(EXTRA_EXCEPTION_TYPE)?.takeIf { it.isNotBlank() } ?: return null
        return CrashSummary(
            exceptionType = type,
            atMillis = intent.getLongExtra(EXTRA_AT_MILLIS, 0L),
            threadName = intent.getStringExtra(EXTRA_THREAD_NAME).orEmpty(),
            censusLine = intent.getStringExtra(EXTRA_CENSUS_LINE)?.takeIf { it.isNotBlank() },
            reportName = intent.getStringExtra(EXTRA_REPORT_NAME)?.takeIf { it.isNotBlank() },
        )
    }

    private fun weighted(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }

    private fun dip(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** logcat tag（与 [MainActivity.TAG] 分开：两个进程的日志要能分得清）。 */
        internal const val LOG_TAG: String = "EratwCrashScreen"

        // 崩溃摘要的五个标量。键名带包前缀，避免与别的启动方撞车。
        internal const val EXTRA_EXCEPTION_TYPE: String = "com.eratw.port.android.crash.exception_type"
        internal const val EXTRA_AT_MILLIS: String = "com.eratw.port.android.crash.at_millis"
        internal const val EXTRA_THREAD_NAME: String = "com.eratw.port.android.crash.thread_name"
        internal const val EXTRA_CENSUS_LINE: String = "com.eratw.port.android.crash.census_line"
        internal const val EXTRA_REPORT_NAME: String = "com.eratw.port.android.crash.report_name"
    }
}
