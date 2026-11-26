package com.eratw.port.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话升进程级（2026-11-13）的**装配面哨兵**：[SessionVault] 的槽内核语义由
 * [VaultCoreTest] 钉死，这里钉的是「谁在哪个生命周期点碰槽」——六处接线点从源码上
 * 缺一即红，防的是未来重构把接回链拆断而编译全绿（和半透明主题同一失败形状：
 * 删掉一行 = 玩家的会话在某条销毁路下原样丢失，直到有人复测才露出来）。
 *
 * 六处（类头因果见 [SessionVault] / [GameHost.reattachView]）：
 * 1. `GameHost.close` 尾部认主出槽（`SessionVault.discard(this)`）——所有拆会话路一处收口；
 * 2. `MainActivity` 交接成功处入槽（`SessionVault.install(candidate)`）——槽是权威；
 * 3. `MainActivity` 首次布局回调先问槽（`takeoverIfAlive` + `reattachView`）——接回优先于冷启；
 * 4. `MainActivity.onDestroy` 以 `isFinishing` 分水岭——系统销毁**不得** close 会话；
 * 5. `MainActivity` 接回即解冻 + 系统销毁冻结认视图主并重新入槽（2026-11-17 玩家 bug）；
 * 6. `GameHost.view` 可换绑（`@Volatile private var`）、`isBoundToView` 可判归属——接回的物理前提。
 */
class SessionHandoverWireTest {

    private val moduleRoot: File = arrayOf(
        File("."),
        File("../app"),
        File("app"),
    ).firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("找不到 app 模块根（AndroidManifest.xml 不在任何候选目录）")

    private val srcDir = File(moduleRoot, "src/main/kotlin/com/eratw/port/android")

    private fun codeOf(name: String): String = File(srcDir, name).readText()

    @Test
    fun `close 尾部认主出槽`() {
        val gameHost = codeOf("GameHost.kt")
        val closeBody = gameHost.substringAfter("fun close() {").substringBefore("private fun shutdownForExit")
        assertTrue(
            "GameHost.close 尾部缺 SessionVault.discard(this)：close 是所有拆会话路" +
                "（玩家退出 / restartGame / 启动失败收口）的共同收口，漏了这一句，被拆的" +
                "死会话会一直赖在槽里（下个实例 takeover 时还要再 kill 一次，且诊断面失真）。",
            closeBody.contains("SessionVault.discard(this)"),
        )
    }

    @Test
    fun `交接成功处入槽`() {
        val main = codeOf("MainActivity.kt")
        assertTrue(
            "unpackAndStart 交接成功处缺 SessionVault.install(candidate)：交接即入槽是" +
                "会话升进程级的第一环——Activity 在交接后任何时刻被系统销毁，会话都要有主。",
            main.contains("SessionVault.install(candidate)"),
        )
    }

    @Test
    fun `装配处先问槽 接回优先于冷启`() {
        val main = codeOf("MainActivity.kt")
        val takeoverBlock = main.substringAfter("SessionVault.takeoverIfAlive()")
        assertTrue(
            "首次布局回调缺接回分支：takeoverIfAlive 之后必须先试 reattachView，" +
                "接回失败还要就地 close（防无主会话泄漏）。",
            takeoverBlock.contains("reattachView(consoleView)") &&
                takeoverBlock.contains("revived?.close()"),
        )
        // 接回分支必须在 startGameOrDemo 之前（接回优先于冷启装载，否则双份装载）。
        assertTrue(
            "接回分支（takeoverIfAlive）应出现在 startGameOrDemo 调用之前——先冷启再问槽" +
                "会双份装载。",
            main.indexOf("SessionVault.takeoverIfAlive()") < main.lastIndexOf("startGameOrDemo(storage)"),
        )
    }

    @Test
    fun `onDestroy 以 isFinishing 分水岭 系统销毁不拆会话`() {
        val main = codeOf("MainActivity.kt")
        // 分界取 onDestroy 特有的收尾语句（releaseSession 全文件唯一）——不能用
        // super.onDestroy()：onDestroy 开头的注释里就引用着这个字样，会提前截断。
        val onDestroy = main.substringAfter("override fun onDestroy() {")
            .substringBefore("consoleView.releaseSession()")
        assertTrue(
            "onDestroy 缺 isFinishing 分水岭：系统销毁（进程存活）必须保留会话（冻结而非" +
                "close），只有玩家主动退出才拆——分水岭删掉就退回「后台回收即丢进度」。",
            onDestroy.contains("if (isFinishing)") && onDestroy.contains("setBackgroundPaused(true)"),
        )
        // close 只许在 isFinishing 分支内出现。
        val finishingBranch = onDestroy.substringAfter("if (isFinishing)").substringBefore("} else {")
        assertTrue("玩家退出分支应 close（认主出槽在 GameHost.close 内）", finishingBranch.contains("host?.close()"))
    }

    /**
     * 2026-11-17 玩家「切后台有概率卡回主菜单 + 最新一天自动存档不见」的装配面回归。
     *
     * 会话升进程级改造里三处不对称叠在一起才出这两个现象：
     * - 解冻只挂在 onStart（那时新壳 host 还是 null ⇒ no-op），接回分支缺一发 ⇒ VM 永久
     *   停在 awaitResume，画面卡住、挂起的存档检查点永远到不了 VM 安全点；
     * - takeover 清槽后接回壳从不重新入槽 ⇒ 它自己再被系统销毁时会话成无主孤儿，
     *   下一实例冷启回标题，旧壳里的存档检查点同样永远兑现不了；
     * - onDestroy 冻结不认主 ⇒ 旧壳迟到的冻结会把新壳刚做的解冻盖回去。
     * 本哨兵把三处补齐钉住；槽内核的「重新 install 恢复接手能力」由 [VaultCoreTest] 钉。
     */
    @Test
    fun `接回解冻 销毁冻结认视图主并重新入槽`() {
        val main = codeOf("MainActivity.kt")

        val takeoverBlock = main.substringAfter("SessionVault.takeoverIfAlive()")
            .substringBefore("return@addOnGlobalLayoutListener")
        assertTrue(
            "接回分支缺 setBackgroundPaused(false)：旧壳 onDestroy 冻结会话后留槽，而 onStart " +
                "的解冻早于首帧接回（host 尚为 null，no-op）。缺这一发，接回的 VM 永久卡在 " +
                "awaitResume —— 画面卡住 + 已登记的存档检查点永远到不了安全点。",
            Regex("revived\\.setBackgroundPaused\\(false\\)").containsMatchIn(takeoverBlock),
        )

        val onDestroy = main.substringAfter("override fun onDestroy() {")
            .substringBefore("consoleView.releaseSession()")
        assertTrue(
            "onDestroy 非 finishing 分支的冻结缺视图归属认主：双壳交接窗口下旧壳 onDestroy " +
                "迟到，reattachView 已把 host 的 view 换成新壳的视图，旧壳必须跳过冻结。" +
                "不能用槽归属——takeover 已清槽，接回壳自己后续销毁会被误判成「不拥有」。",
            onDestroy.contains("isBoundToView(consoleView)"),
        )
        assertTrue(
            "onDestroy 非 finishing 分支缺 SessionVault.install(owned)：takeover 清槽后，" +
                "接回壳自己再被系统销毁时不把会话交还进程，下一实例问槽扑空 → 冷启回主菜单，" +
                "挂起的后台存档检查点也没有 VM 安全点兑现。",
            onDestroy.contains("SessionVault.install(owned)"),
        )
    }

    @Test
    fun `GameHost view 可换绑且带可见性保证`() {
        val gameHost = codeOf("GameHost.kt")
        assertTrue(
            "GameHost 构造参数 view 必须是 @Volatile var：reattachView 要在 UI 线程换绑，" +
                "而 VM 线程经 view.post 读它——val 或无 @Volatile 的 var 都不满足。",
            Regex("@Volatile\\s+private\\s+var\\s+view\\s*:").containsMatchIn(gameHost),
        )
        assertTrue(
            "GameHost 缺 reattachView(newView)：接回的物理前提（渲染桥换绑）。",
            gameHost.contains("fun reattachView(newView: CanvasConsoleView): Boolean"),
        )
        assertTrue(
            "GameHost 缺 isBoundToView：onDestroy 冻结/重新入槽的归属判据——" +
                "reattachView 换绑后旧壳必须自动失去资格，而接回壳自己仍有资格。",
            gameHost.contains("fun isBoundToView(candidate: CanvasConsoleView): Boolean"),
        )
    }
}
