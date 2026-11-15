package com.eratw.port.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.eratw.port.android.ui.EratwShellDialog
import com.eratw.port.core.code.InfiniteLoopAlert
import com.eratw.port.core.code.InfiniteLoopAnswer
import com.eratw.port.platform.save.BlockingSaveMigrationPrompt
import com.eratw.port.platform.save.SaveMigrationPromptUi
import com.eratw.port.platform.update.UpdateCheckTransport
import com.eratw.port.platform.vm.BlockingInfiniteLoopPrompt
import com.eratw.port.platform.vm.InfiniteLoopPromptUi
import com.eratw.port.render.canvas.CanvasConsoleView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一 UI 询问弹窗协调器：把 GameHost 的三份跨线程阻塞询问（存档迁移 / UPDATECHECK /
 * 死循环告警）从宿主里拆出去 —— 弹窗展示、取消、超时与「恰好完成一次」语义全部内聚在本类，
 * 宿主只保留两件事：装配（把 [saveMigrationPrompt] / [infiniteLoopPrompt] 交给 GameSession、
 * 把 [updateTransport] 交给 UPDATECHECK 宿主）与**死循环取证钩子** [onInfiniteLoopSeen]
 * （告警弹出的同一现场把快照钉进宿主单槽 + 回合轨迹，`ErrorReportLoopAlertTest` 钉其形态）。
 *
 * 线程模型与 [GameHost] 同源：VM 线程经平台层 `Blocking*Prompt` 阻塞等待，UI 线程展示；
 * [UiPromptSlot] 内部保证取消/展示/完成三者的跨线程时序（见其类 KDoc 的两道封窗说明）。
 * `close()` / `onPause()` 经三个 `cancelPending*` 入口放行挂起询问。
 */
internal class UiPromptCoordinator(
    private val viewProvider: () -> CanvasConsoleView,
    /** 宿主关闭判据（[GameHost.closed] 的读口）。 */
    private val isClosed: () -> Boolean,
    /** 死循环告警弹出同一现场的取证钩子（UI 线程执行）：宿主写单槽快照 + 钉回合轨迹。 */
    private val onInfiniteLoopSeen: (Activity, InfiniteLoopAlert) -> Unit,) {

    /** 动态视图读口：`GameHost.reattachView` 换绑后逐次取新值（弹窗/Toast/Context 自动跟随新壳）。 */
    private val view: CanvasConsoleView get() = viewProvider()
    private val promptLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val saveMigrationSlot = UiPromptSlot<Boolean>(promptLock)
    private val updateCheckSlot = UiPromptSlot<Boolean>(promptLock)
    private val infiniteLoopSlot = UiPromptSlot<InfiniteLoopAnswer>(promptLock)

    val saveMigrationPrompt = BlockingSaveMigrationPrompt(
        SaveMigrationPromptUi { title, message, answer -> askSaveMigrationOnUi(title, message, answer) },
    )

    val updateTransport = UpdateCheckTransport()
    private var currentUpdateAskTicket: UpdateAskTicket? = null
    private val updatePrompt = BlockingSaveMigrationPrompt(
        SaveMigrationPromptUi { title, message, answer -> askUpdateCheckOnUi(title, message, answer) },
    )

    val infiniteLoopPrompt = BlockingInfiniteLoopPrompt(
        InfiniteLoopPromptUi { alert, answer -> askInfiniteLoopOnUi(alert, answer) },
    )


    private fun askSaveMigrationOnUi(title: String, message: String, answer: (Boolean) -> Unit) {
        saveMigrationSlot.show(
            view = view,
            isClosed = isClosed,
            answer = answer,
            fallbackAnswer = false,
            buildDialog = { activity, deliver ->
                EratwShellDialog.builder(activity)
                    .setView(EratwShellDialog.content(activity, title, message))
                    .setPositiveButton("是") { _, _ -> deliver(true) }
                    .setNegativeButton("否") { _, _ -> deliver(false) }
                    .setOnCancelListener { deliver(false) }
                    .create()
            },
            onShown = { Log.i(TAG, "存档迁移：已弹询问框（$title）") },
        )
    }

    fun cancelPendingSaveMigrationPrompt() {
        if (saveMigrationSlot.cancel(view, false)) {
            Log.i(TAG, "存档迁移询问被取消（离开前台/关闭）→ 按否处理")
        }
    }

    private fun askInfiniteLoopOnUi(alert: InfiniteLoopAlert, answer: (InfiniteLoopAnswer) -> Unit) {
        infiniteLoopSlot.show(
            view = view,
            isClosed = isClosed,
            answer = answer,
            fallbackAnswer = InfiniteLoopAnswer.NoUi,
            onBeforeBuild = { activity ->
                onInfiniteLoopSeen(activity, alert)
            },
            buildDialog = { activity, deliver ->
                val lang = UiPrefs.settingsLanguage(activity)
                val variant = langVariantOf(lang)
                val copy = PortraitShellCopy.of(lang)
                EratwShellDialog.builder(activity)
                    .setView(EratwShellDialog.content(activity, alert.title(variant), alert.message(variant)))
                    .setPositiveButton(copy.dialogYes) { _, _ -> deliver(InfiniteLoopAnswer.Exit(variant)) }
                    .setNegativeButton(copy.dialogNo) { _, _ -> deliver(InfiniteLoopAnswer.Continue) }
                    .setOnCancelListener { deliver(InfiniteLoopAnswer.Continue) }
                    .create()
            },
            onShown = { Log.i(TAG, "死循环：已弹告警框（${alert.displaySource}:${alert.lineNo}）") },
        )
    }

    fun cancelPendingInfiniteLoopPrompt() {
        if (infiniteLoopSlot.cancel(view, InfiniteLoopAnswer.NoUi)) {
            Log.i(TAG, "死循环告警被取消（关闭）→ 按无 UI 处理")
        }
    }

    fun askUpdateCheck(title: String, message: String): Boolean? {
        currentUpdateAskTicket = null
        val yes = updatePrompt(title, message)
        val ticket = currentUpdateAskTicket
        currentUpdateAskTicket = null
        return if (ticket == null || ticket.timedOut || ticket.notDelivered) null else yes
    }

    private fun askUpdateCheckOnUi(title: String, message: String, answer: (Boolean) -> Unit) {
        val ticket = UpdateAskTicket()
        currentUpdateAskTicket = ticket
        val timeout = Runnable {
            if (!ticket.claim(timeout = true)) return@Runnable
            updateCheckSlot.dismissDialog(view)
            Log.i(TAG, "UPDATECHECK 询问 ${UPDATE_ASK_TIMEOUT_MS}ms 未作答 → 按原版异常支（RESULT=3）")
            answer(false)
        }
        mainHandler.postDelayed(timeout, UPDATE_ASK_TIMEOUT_MS)

        updateCheckSlot.show(
            view = view,
            isClosed = isClosed,
            answer = answer,
            fallbackAnswer = false,
            onNotDelivered = {
                ticket.claim(notDelivered = true)
                mainHandler.removeCallbacks(timeout)
            },
            onDismiss = { mainHandler.removeCallbacks(timeout) },
            buildDialog = { activity, deliver ->
                EratwShellDialog.builder(activity)
                    .setView(EratwShellDialog.content(activity, title, message))
                    .setPositiveButton("是") { _, _ ->
                        if (ticket.claim()) {
                            mainHandler.removeCallbacks(timeout)
                            deliver(true)
                        }
                    }
                    .setNegativeButton("否") { _, _ ->
                        if (ticket.claim()) {
                            mainHandler.removeCallbacks(timeout)
                            deliver(false)
                        }
                    }
                    .setOnCancelListener {
                        if (ticket.claim()) {
                            mainHandler.removeCallbacks(timeout)
                            deliver(false)
                        }
                    }
                    .create()
            },
            onShown = {
                Log.i(TAG, "UPDATECHECK：已弹询问框（$title），${UPDATE_ASK_TIMEOUT_MS}ms 内未作答按超时处理")
            },
        )
    }

    fun cancelPendingUpdatePrompt() {
        val cancelled = updateCheckSlot.cancel(view, false) {
            currentUpdateAskTicket?.claim(notDelivered = true)
        }
        if (cancelled) {
            Log.i(TAG, "UPDATECHECK 询问被取消（宿主关闭）→ 按没问到处理（RESULT=3）")
        }
    }

    fun openUpdateLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        view.context.applicationContext.startActivity(intent)
        Log.i(TAG, "UPDATECHECK：已把链接交给系统打开")
    }


    private class UpdateAskTicket {
        private val claimed = AtomicBoolean(false)

        var timedOut: Boolean = false
            private set

        var notDelivered: Boolean = false
            private set

        fun claim(timeout: Boolean = false, notDelivered: Boolean = false): Boolean {
            if (!claimed.compareAndSet(false, true)) return false
            timedOut = timeout
            this.notDelivered = notDelivered
            return true
        }
    }

    /**
     * 统一管理跨线程阻塞询问弹窗（VM 线程发起 -> UI 线程展示 -> 关闭/退后台自动取消并兜底返回）。
     *
     * ## 两道跨线程窗口的封法（2026-11-13 外部审计采纳）
     * * **取消 vs 已排队的展示任务**：[show] 经 `view.post` 排队的 Runnable 与 [cancel]
     *   之间没有先后保证 —— 旧写法的 Runnable 只查宿主 closed，`onPause` 取消（≠ 关闭）
     *   之后回前台会弹出已答过兜底值的**僵尸框**。现在每笔请求是一个独立的 [Pending]
     *   对象，Runnable 执行前先核 `pending === 本请求`（对象身份即令牌）：已被 [cancel]
     *   兑付兜底、或被更新的 [show] 覆盖 ⇒ 按未送达收口，绝不弹框。
     * * **恰好完成一次**：玩家按钮 / [cancel] 兜底 / 展示失败 / 活动缺失四路竞争同一笔
     *   完成，[Pending.deliver] 用 `AtomicBoolean.compareAndSet` 只放行第一路。平台层
     *   的阻塞弹窗（`BlockingSaveMigrationPrompt` / `BlockingInfiniteLoopPrompt`）各有
     *   自己的 answered 闸，宿主侧不再依赖它 —— 完成态在本槽内自洽。
     */
    private class UiPromptSlot<A>(private val lock: Any) {

        /** 槽内当前有效请求；null = 空闲。对象身份就是请求令牌（见类 KDoc 的封窗说明）。 */
        private var pending: Pending<A>? = null

        @Volatile
        private var pendingDialog: AlertDialog? = null

        private class Pending<A>(
            val rawAnswer: (A) -> Unit,
            val fallback: A,
            val onNotDelivered: (() -> Unit)?,
        ) {
            private val completed = AtomicBoolean(false)

            /** 恰好完成一次：四路完成竞争同一状态，只有第一路真正把答案交出去。 */
            fun deliver(a: A) {
                if (completed.compareAndSet(false, true)) rawAnswer(a)
            }
        }

        fun show(
            view: CanvasConsoleView,
            isClosed: () -> Boolean,
            answer: (A) -> Unit,
            fallbackAnswer: A,
            onBeforeBuild: ((Activity) -> Unit)? = null,
            onNotDelivered: (() -> Unit)? = null,
            onDismiss: (() -> Unit)? = null,
            buildDialog: (Activity, (A) -> Unit) -> AlertDialog,
            onShown: () -> Unit,
        ) {
            val req = Pending(answer, fallbackAnswer, onNotDelivered)
            synchronized(lock) { pending = req }
            view.post {
                // 执行前先核令牌（这一查必须在宿主 closed 之前）：本请求已被 cancel 兑付
                // 兜底、或被更新的 show 覆盖 ⇒ 按未送达收口，绝不弹框。cancel 那一路已经
                // 把兜底交出去的话，这里 deliver 的 CAS 会把重复完成拦成 no-op。
                if (synchronized(lock) { pending !== req }) {
                    onNotDelivered?.invoke()
                    req.deliver(req.fallback)
                    return@post
                }
                val activity = view.context.findActivity()
                if (isClosed() || activity == null || activity.isFinishing || activity.isDestroyed) {
                    synchronized(lock) { if (pending === req) pending = null }
                    onNotDelivered?.invoke()
                    req.deliver(req.fallback)
                    return@post
                }
                onBeforeBuild?.invoke(activity)
                val dialog = buildDialog(activity) { a ->
                    synchronized(lock) { if (pending === req) pending = null }
                    req.deliver(a)
                }
                EratwShellDialog.dress(dialog)
                dialog.setOnDismissListener {
                    onDismiss?.invoke()
                    synchronized(lock) { if (pending === req) pending = null }
                    if (pendingDialog === dialog) pendingDialog = null
                }
                pendingDialog = dialog
                if (runCatching { dialog.show() }.isFailure) {
                    pendingDialog = null
                    onNotDelivered?.invoke()
                    req.deliver(req.fallback)
                } else {
                    onShown()
                }
            }
        }

        fun dismissDialog(view: CanvasConsoleView) {
            val dialog = pendingDialog
            if (dialog != null) view.post { runCatching { dialog.dismiss() } }
        }

        fun cancel(
            view: CanvasConsoleView,
            fallbackAnswer: A,
            onCancelledBeforeCallback: (() -> Unit)? = null,
        ): Boolean {
            val req = synchronized(lock) {
                pending.also { pending = null }
            }
            val dialog = pendingDialog
            pendingDialog = null
            if (dialog != null) view.post { runCatching { dialog.dismiss() } }
            if (req == null) return false
            onCancelledBeforeCallback?.invoke()
            req.deliver(fallbackAnswer)
            return true
        }
    }

    companion object {
        private const val TAG = "GameHost"
        private const val UPDATE_ASK_TIMEOUT_MS: Long = 45_000L
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
