package com.eratw.port.platform.save

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 存档目录迁移询问框的 UI 最小面（M11-A-WIRE-2）。
 *
 * 实现方必须把 [show] 投到 **UI 线程**，并在玩家作答（或框被关掉）时调用一次 [answer]；
 * 关闭对话框按原版 `Dialog.ShowPrompt` 的口径算「否」（spec: `Dialog.cs:21-28`：
 * 只有 `DialogResult.Yes` 才是 true，关框 = false）。
 */
fun interface SaveMigrationPromptUi {
    fun show(title: String, message: String, answer: (Boolean) -> Unit)
}

/**
 * 把 core 的同步询问通道（`GameSession.saveMigrationPrompt`）接到 Android UI 上。
 *
 * core 侧在 `FileSystemOps` 构造期（会话装配线程 = 真机 VM 线程）同步问一次
 * 「要不要把根目录旧档搬进 `sav/`」（spec: `Config.cs:564-567`），答 No 时一个字节都不搬；
 * `sav/` 目录在问之前就已建好（`:555`），所以下一次启动不会再问（`:482` 的守卫）——
 * 「不重复问」由目录存在性承载，不需要额外持久化标志。
 *
 * 本类只做「阻塞 VM 线程直到 UI 作答」这一件事，**不碰 Android API**：
 * UI 线程投递与 Activity 生命周期守卫都在宿主侧（`GameHost`）。
 *
 * 作答是**一次性**的：重复调用、关框回调与后台取消同时到达时只认第一次；
 * UI 投递失败（Activity 已销毁等）按「否」收敛，绝不把装载线程永久挂住。
 */
class BlockingSaveMigrationPrompt(
    private val ui: SaveMigrationPromptUi,
) : (String, String) -> Boolean {

    override fun invoke(title: String, message: String): Boolean {
        val latch = CountDownLatch(1)
        val yes = AtomicBoolean(false)
        val answered = AtomicBoolean(false)
        val answer: (Boolean) -> Unit = { value ->
            if (answered.compareAndSet(false, true)) {
                yes.set(value)
                latch.countDown()
            }
        }
        // UI 投递失败 = 框弹不出来；按原版「关框 = 否」收敛（不搬文件比误搬安全）。
        runCatching { ui.show(title, message, answer) }.onFailure { answer(false) }
        try {
            latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return yes.get()
    }
}
