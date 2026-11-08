package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [UpdateDownloadService] 的 FGS 五秒闸接线钉（Android 8+ / API 26 起）。
 *
 * ## 钉的是什么
 *
 * `Context.startForegroundService()` 起来的服务**必须在系统时限内调过一次 `startForeground()`**，否则平台按
 * `ForegroundServiceDidNotStartInTimeException` 杀整进程（上报端点 1 份 / 1 机，OnePlus PYB110 / API 37 实录
 * 「点下载就崩」）。落刀 `9948a5da`，其提交信息自陈「改动面为 Service 壳层接线，无 Robolectric 覆盖」，而当时
 * `app/src/test` 对 `startForeground` / `enterForegroundThenStop` 零命中 —— 本类补上这批钉子。
 *
 * 共同判据：**任何一条能让服务退场或早退的支路，都必须已经过一次前台声明**；这条写反了不编译错、也不会被任何
 * 行为用例抓到，只在真机上、只在系统那侧炸。
 *
 * ## 为什么是源码形状闸，不是行为测试（本路的失效面）
 *
 * `:app` 的单测依赖只有 `testImplementation(libs.junit4)`（`app/build.gradle.kts:542`），**没有 Robolectric**；
 * `startForeground` / `startForegroundService` 要 Android 框架与设备才跑得动，本机又无 adb 装置。为这一枚钉
 * 悄悄加 Robolectric 属构建面越权。⇒ 照本仓对「接线顺序」类缺陷的既有口径：源码形状闸，先例见 [UiFaultTest] 的
 * `回标题失败的关闭闸排在落报告之前`、[LiveReflowWiringTest] 与 [StallSentinelTest] 的 `codeLines(...)` 系列。
 * 取的是**代码行**（注释与 KDoc 已剔除），所以措辞改动不影响闸。
 *
 * - **会误红**：把 `enterForegroundThenStop` / `reassertForegroundWhileBusy` / `startForegroundCompat` 改名、
 *   换参数形状，或在 helper 与相邻方法之间插入别的方法 ⇒ 红。这是刻意的：动了接线就得回来对账一遍。
 * - **不覆盖**：真机上 `startForeground` 是否真的在时限内被系统接受（渠道建不出来、ROM 拒 dataSync 类型等运行时面）。
 *   形状闸判不到，见类末 TODO(verify)。
 *
 * ## 次序史（别照旧提交信息读）
 *
 * `9948a5da` 当时的字面口径是「先亮前台再抢闸」；`e8ac9a80`（2026-11-04 复查收口）把单飞闸挪回**一切本单状态改动
 * 之前**——旧序「已有下载在跑」那条早退会把新单状态泼在在飞下载身上，`stopForegroundCompat` 还会把在飞那条进度
 * 通知连同前台态一起撤掉。于是现口径变成「**抢闸最先 + 每条退场/早退支路各带一次前台声明**」：抢闸失败那条靠
 * `reassertForegroundWhileBusy` 对在飞单做同 id 合规重申，其余支路走 `enterForegroundThenStop`。本类钉的是
 * **现口径**，抢闸次序的判式见「抢闸失败的早退」那条用例的末段，退场次序的判式散在各支路用例里。
 *
 * TODO(verify): 真机（API 26+）点「下载」后在时限内不崩、以及 `startForeground` 抛异常时服务仍能正常退场，
 * 本机无 adb 装置，未验。
 */
class UpdateDownloadServiceForegroundGateTest {

    /**
     * 被测源码的代码行（注释与 KDoc 已剔除）：本类判的是接线，不是文档措辞。
     *
     * 必须是 `by lazy`：本类的源码取用 helper（[moduleRootCandidates]）声明在文件末尾，
     * 写成 eager 属性初始化会在构造期读到还没赋值的候选目录（Kotlin 属性按声明顺序初始化）。
     */
    private val svc: String by lazy { codeLines("UpdateDownloadService.kt") }

    // ------------------------------------------------------------------
    // 下载主线：亮前台 → 起线程 →（撤前台）→ 退场
    // ------------------------------------------------------------------

    /**
     * 主线两处退场都排在同一条前台声明之后。
     *
     * 甲 = 前台通知起不来（`UpdateDownloadService.kt:544-556`）：先放自己抢到的闸、再点名退场，这一退必须排在
     * 它自己那次声明调用之后（声明失败也算调过，五秒闸要的是一次调用）。
     * 乙 = 线程跑完（`:558-574`）：先撤前台再退场，整段必须排在亮前台之后 —— 若有人把 `val thread = Thread {`
     * 挪到声明之前，下载线程就会在没过闸的服务里跑到尾部退场，正是端点那枚崩的形状。
     */
    @Test
    fun `下载主线的前台声明排在起跑线程与两处退场之前`() {
        val declare = svc.indexOf("startForegroundCompat(NOTIFICATION_PROGRESS, first)")
        assertTrue("找不到主线的前台声明（被删或改了参数形状都算断线）", declare >= 0)

        val fgFail = svc.indexOf("if (foregrounded.isFailure) {")
        assertTrue("找不到「前台通知起不来」那条早退支路", fgFail >= 0)
        val failStop = svc.indexOf("stopSelf(startId)", fgFail)
        assertTrue("早退支路必须点名退场", failStop > fgFail)
        assertTrue("声明调用要排在它的失败退场之前（declare=$declare failStop=$failStop）", declare < failStop)
        val failRelease = svc.indexOf("releaseGate()", fgFail)
        assertTrue("抢到的闸要在退场前放掉，不许留死闸", failRelease > fgFail && failRelease < failStop)

        val threadStart = svc.indexOf("val thread = Thread {")
        assertTrue("找不到下载线程的起跑点", threadStart >= 0)
        assertTrue("线程必须在前台声明之后起跑：线程尾部就是退场", declare < threadStart)
        val workerStop = svc.indexOf("stopSelf()", threadStart)
        assertTrue("找不到下载线程收尾的退场", workerStop > threadStart)
        val workerDrop = svc.indexOf("stopForegroundCompat()", threadStart)
        assertTrue("收尾次序是先撤前台再退场", workerDrop > threadStart && workerDrop < workerStop)
    }

    // ------------------------------------------------------------------
    // 外层兜底：意图处理异常
    // ------------------------------------------------------------------

    /**
     * `onStartCommand` 的外层兜底（`:460-469`）：过前台 → 退场 → 回 `START_NOT_STICKY`。
     *
     * 源码注释写明「抛点可能在 startForeground 之前，也可能已经过了」⇒ 这里的前台声明**无条件**要有一次；顺序
     * 反过来（先 stopSelf 再声明）就是给已退场的服务补发声明，五秒闸照旧没守住。
     * 同时这一支**不许碰单飞闸**：能走到外层兜底的只有 CANCEL / INSTALL 两支，它们的异常与闸无关；闸在飞时误放
     * 一次，就是把在飞下载暴露成双开。
     */
    @Test
    fun `意图处理异常的外层兜底先过前台再退场且不碰单飞闸`() {
        val fallback = svc.indexOf("if (handled.isFailure) {")
        assertTrue("找不到 onStartCommand 的外层兜底支路", fallback >= 0)
        val declare = svc.indexOf("enterForegroundThenStop(startId)", fallback)
        assertTrue("异常收场必须先过一次前台声明", declare > fallback)
        val stop = svc.indexOf("stopSelf(startId)", declare)
        assertTrue("过闸之后才许退场", stop > declare)
        val sticky = svc.indexOf("return START_NOT_STICKY", stop)
        assertTrue("本服务一律死了不自动重开", sticky > stop)
        val span = svc.substring(fallback, stop)
        assertFalse("外层兜底不许放单飞闸（闸在飞时误放 = 双开）：$span", span.contains("releaseGate("))
        assertFalse("外层兜底不许绕记账位裸放闸：$span", span.contains("UpdateManager.releaseDownload"))
        assertFalse("外层兜底不许清在飞单的记账：$span", span.contains("activeJob = null"))
    }

    // ------------------------------------------------------------------
    // beginDownloadSafe 的两条本地收口支路
    // ------------------------------------------------------------------

    /**
     * 残缺意图与本地 catch 两支都只经 `enterForegroundThenStop` 退场 —— **同一个退场口径不许在两处各写一遍**。
     *
     * 甲（`:486-493`）：`jobFrom` 返回 null（缺文件名 / 缺摘要 / 尺寸非法 / 类别不认识）时，服务同样是
     * `startForegroundService` 起来的，照样要先过一次前台再退。
     * 乙（`:497-508`）：本地收口的异常先过闸，再按记账位 `gateHeld` 放**自己**那把 —— 判据不用 `activeJob`
     * （后者在抢闸前曾被赋值，窗口期会误放别人的闸，`e8ac9a80` 收的就是这个口）。
     */
    @Test
    fun `残缺意图与本地 catch 两支只经过闸退场 helper`() {
        val safeDef = svc.indexOf("private fun beginDownloadSafe(intent: Intent?, startId: Int): Boolean {")
        assertTrue("找不到 beginDownloadSafe（签名改了就是断线）", safeDef >= 0)
        val beginDef = svc.indexOf("private fun beginDownload(job: DownloadJob, startId: Int) {")
        assertTrue("找不到 beginDownload（两条支路的边界靠它定）", beginDef > safeDef)

        val nullJob = svc.indexOf("if (job == null) {")
        assertTrue("残缺意图支路必须在 beginDownloadSafe 内", nullJob > safeDef && nullJob < beginDef)
        val nullDeclare = svc.indexOf("enterForegroundThenStop(startId)", nullJob)
        assertTrue("残缺支必须先过一次前台再退", nullDeclare > nullJob && nullDeclare < beginDef)
        val nullReturn = svc.indexOf("return false", nullDeclare)
        assertTrue("残缺支的过闸要排在支路返回之前（找不到返回点即红线）", nullReturn > nullDeclare && nullReturn < beginDef)
        val nullSpan = svc.substring(nullJob, nullReturn)
        assertFalse("残缺支不许在 helper 之外另写退场（两处口径必漏一处）", nullSpan.contains("stopSelf"))

        val catchAt = svc.indexOf("} catch (t: Throwable) {")
        assertTrue("本地 catch 必须在 beginDownloadSafe 内", catchAt > safeDef && catchAt < beginDef)
        val catchDeclare = svc.indexOf("enterForegroundThenStop(startId)", catchAt)
        assertTrue("异常支必须先过一次前台", catchDeclare > catchAt && catchDeclare < beginDef)
        val gateCheck = svc.indexOf("if (gateHeld) {", catchDeclare)
        assertTrue("放账要按记账位 gateHeld 判（找不到即红线）", gateCheck > catchDeclare && gateCheck < beginDef)
        val catchSpan = svc.substring(catchAt, gateCheck)
        assertFalse("本地 catch 不许在 helper 之外另写退场", catchSpan.contains("stopSelf"))
        assertFalse("本地 catch 不许绕记账位裸放闸", catchSpan.contains("UpdateManager.releaseDownload"))
    }

    // ------------------------------------------------------------------
    // 抢闸失败（busy）的早退
    // ------------------------------------------------------------------

    /**
     * 「已经有一个下载在跑」那条早退：先对在飞单做同 id 前台重申，再回一句话，**且在飞单零扰动**。
     *
     * 这条是 `e8ac9a80` 之后五秒义务的新落点（旧写法在这里直接 `stopForegroundCompat` + `stopSelf`，把在飞那条
     * 进度通知连同前台态一起撤掉）。所以两条都要钉：①前台声明（重申）排在早退回话之前；②整条早退支路里不出现
     * 退场、不撤前台、不动在飞单的记账与闸。
     * 末段是次序判式：**抢闸排在一切本单状态改动之前**（现口径，见类 KDoc 的次序史）。
     */
    @Test
    fun `抢闸失败的早退先重申前台且在飞单零扰动`() {
        val gate = svc.indexOf("if (!UpdateManager.tryBeginDownload()) {")
        assertTrue("找不到单飞闸的获取点", gate >= 0)
        assertEquals("单飞闸只在一处获取", 1, svc.countOccurrences("tryBeginDownload()"))
        val reassert = svc.indexOf("reassertForegroundWhileBusy()", gate)
        assertTrue("busy 早退必须对在飞那条进度通知重申一次前台", reassert > gate)
        val busyToast = svc.indexOf("honestToast(copy().downloadBusyToast)", reassert)
        assertTrue("先过前台再回「已经有一个下载在跑」（顺序反了就是端点那枚崩）", busyToast > reassert)
        val stateAt = svc.indexOf("gateHeld = true", busyToast)
        assertTrue("早退支路的终点是本单记账位（找不到即红线）", stateAt > busyToast)

        val busySpan = svc.substring(gate, stateAt)
        assertFalse("busy 早退不许退场：服务要留在前台陪在飞那条单", busySpan.contains("stopSelf"))
        assertFalse("busy 早退不许撤前台（会把在飞那条进度通知一起撤掉）", busySpan.contains("stopForegroundCompat"))
        assertFalse("busy 早退不许动在飞单的记账", busySpan.contains("activeJob = null"))
        assertFalse("busy 早退不许放闸（闸本来就没抢到手）", busySpan.contains("releaseGate("))
        assertEquals("前台重申只接线一处（重申口径不许在两处各写一遍）", 2, svc.countOccurrences("reassertForegroundWhileBusy("))

        assertTrue("抢闸必须早于本单状态改动", gate < svc.indexOf("activeJob = job"))
        assertTrue(
            "抢闸必须早于主线的亮前台",
            gate < svc.indexOf("startForegroundCompat(NOTIFICATION_PROGRESS, first)"),
        )
    }

    // ------------------------------------------------------------------
    // 放闸与重申两个 helper 的本体
    // ------------------------------------------------------------------

    /**
     * `releaseGate`（`UpdateDownloadService.kt:273-278`）= 记账位守卫 → 摘账 → 放闸三段，
     * 且真正的放闸只接线一处。
     *
     * `UpdateManager.releaseDownload` 全文件只许出现在本 helper 内：其余支路一律经它退闸
     * （`UpdateManager.download` 的契约是「调用方负责 release」，散放就是死闸或双开的源头）。
     * 守卫判据必须是记账位 `gateHeld` 本尊 —— 用 `activeJob` 当判据是 `e8ac9a80` 收口前的旧病
     * （抢闸前赋值的窗口期会误放在飞单的闸）。
     */
    @Test
    fun `放闸 helper 只认记账位且真正放闸只接线一处`() {
        val def = svc.indexOf("private fun releaseGate() {")
        assertTrue("找不到放闸 helper（签名改了就是断线）", def >= 0)
        val nextDef = svc.indexOf("private fun reassertForegroundWhileBusy() {", def)
        assertTrue("放闸与前台重申之间不许插别的方法（否则本条判的不是这个 body）", nextDef > def)
        val body = svc.substring(def, nextDef)
        val guard = body.indexOf("if (gateHeld) {")
        assertTrue("没持有必须 no-op（防误放别人的闸）", guard >= 0)
        val clear = body.indexOf("gateHeld = false", guard)
        assertTrue("先摘自己的记账位再放闸", clear > guard)
        val release = body.indexOf("UpdateManager.releaseDownload()", clear)
        assertTrue("放闸调用要排在摘账之后", release > clear)
        assertEquals("helper 内摘账只此一次", 1, body.countOccurrences("gateHeld = false"))
        assertEquals("真正的放闸只接线一处（其余支路一律走本 helper）", 1, svc.countOccurrences("UpdateManager.releaseDownload()"))
        assertEquals("记账位置位只此一处（抢闸成功那一拍，早退支不许置位）", 1, svc.countOccurrences("gateHeld = true"))
    }

    /**
     * `reassertForegroundWhileBusy`（`UpdateDownloadService.kt:287-297`）的本体 =
     * 锁内取现渲染 → 包死的同 id 前台重申，**不撤、不退、不碰在飞单**。
     *
     * busy 早退路的调用次序已由上一节钉死；这里钉本体：重申只 `startForegroundCompat` 一次且整段
     * `runCatching` 包死（重申失败不许抛，在飞下载照跑），body 里不许出现撤前台、退场、放闸或在飞单
     * 记账 —— 重申若顺手撤了前台，在飞那条进度通知就跟着没了，服务也退化成非前台。
     */
    @Test
    fun `前台重申 helper 本体只重申不撤不退不碰在飞单`() {
        val def = svc.indexOf("private fun reassertForegroundWhileBusy() {")
        assertTrue("找不到前台重申 helper（签名改了就是断线）", def >= 0)
        val nextDef = svc.indexOf("private var worker: Thread? = null", def)
        assertTrue("重申与 worker 字段之间不许插别的方法（否则本条判的不是这个 body）", nextDef > def)
        val body = svc.substring(def, nextDef)
        val wrap = body.indexOf("runCatching {")
        assertTrue("重申整段要包死（找不到包口即红线）", wrap >= 0)
        val declare = body.indexOf("startForegroundCompat(", wrap)
        assertTrue("重申必须对在飞那条进度通知补一次前台声明", declare > wrap)
        val caught = body.indexOf(".onFailure", declare)
        assertTrue("重申失败不许抛（在飞下载照跑）", caught > declare)
        assertEquals("重申只亮一次前台", 1, body.countOccurrences("startForegroundCompat("))
        assertFalse("重申不许撤前台（会把在飞那条进度通知一起撤掉）", body.contains("stopForeground"))
        assertFalse("重申不许退场（busy 路服务要留在前台陪在飞单）", body.contains("stopSelf"))
        assertFalse("重申不许放闸（闸本来就没抢到手）", body.contains("releaseGate("))
        assertFalse("重申不许碰在飞单的记账", body.contains("activeJob"))
    }

    // ------------------------------------------------------------------
    // 过闸退场 helper 自己的内部次序
    // ------------------------------------------------------------------

    /**
     * `enterForegroundThenStop`（`:938-948` 的 helper）= 亮 → 撤 → 退三段，且**退场不受声明成败影响**。
     *
     * 最后一条是本 helper 的全部要点：亮前台整段 `runCatching` 包死（前台通知起不来不许抛），但
     * `stopSelf(startId)` 必须在包死块**之后**无条件执行 —— 有人把它挪进块里，声明一失败就连退场一起跳过，
     * 服务既不前台也不死，正好撞满系统那道时限闸。
     */
    @Test
    fun `过闸退场路内是亮撤退三段且退场不受声明成败影响`() {
        val def = svc.indexOf("private fun enterForegroundThenStop(startId: Int) {")
        assertTrue("找不到过闸退场 helper", def >= 0)
        val nextFun = svc.indexOf("private fun honestToast(text: String) {", def)
        assertTrue("helper 与 Toast 之间不许插入别的方法（否则本条判的不是这个 body）", nextFun > def)
        val body = svc.substring(def, nextFun)
        val declare = body.indexOf("startForegroundCompat(")
        assertTrue("helper 必须先亮一次前台", declare >= 0)
        val drop = body.indexOf("stopForegroundCompat()", declare)
        assertTrue("亮完随即撤下（玩家感知不到这条通知存在过）", drop > declare)
        val caught = body.indexOf("}.onFailure", drop)
        assertTrue("亮前台整段要包死（找不到收口即红线）", caught > drop)
        val stop = body.indexOf("stopSelf(startId)", caught)
        assertTrue("退场必须排在包死块之后：声明起不来也照样退", stop > caught)
        assertEquals("helper 内只亮一次前台", 1, body.countOccurrences("startForegroundCompat("))
        assertEquals("helper 内只退一次场", 1, body.countOccurrences("stopSelf"))
    }

    // ------------------------------------------------------------------
    // 全局接线计数 + 起跑侧成对
    // ------------------------------------------------------------------

    /**
     * 起跑侧与退场侧成对、以及每条退场的逐条对账：**新增一处退场/重申，就要新写一条判式**。
     *
     * 起跑必须按 SDK 分档：只有 API 26+ 用 `startForegroundService` 起来的服务才欠系统一次 `startForeground`，
     * 有人把它「简化」成无条件 `startService`，五秒义务与这批声明会一起失真（后台启动限制是另一枚崩）。
     * 平台调用点唯一：真碰 Android 的 `startForeground` 只在 compat wrapper 里那一发。
     * onTimeout 只可能在**已经在前台**的服务上触发，所以它不再重申、也不走过闸 helper（重申会把在飞通知撤下来）。
     */
    @Test
    fun `前台声明与退场的接线计数钉死且起跑侧成对`() {
        assertTrue("取到的必须是 UpdateDownloadService 的代码行", svc.contains("class UpdateDownloadService : Service()"))
        assertTrue("代码行不能是空串（取到空串时所有判式都会假绿）", svc.length > 1_000)

        assertEquals("起跑只有一处 startForegroundService", 1, svc.countOccurrences("startForegroundService(intent)"))
        assertTrue(
            "起跑必须按 SDK 分档（无条件 startService 会把五秒义务与前台声明一起摘掉）",
            svc.contains("if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)"),
        )

        assertEquals("平台的前台调用只在一处", 1, svc.countOccurrences("startForeground(id, notification)"))
        assertEquals("compat 声明 = 三处接线 + 一处定义", 4, svc.countOccurrences("startForegroundCompat("))
        assertEquals("过闸退场 helper = 三处接线 + 一处定义", 4, svc.countOccurrences("enterForegroundThenStop"))
        assertEquals("helper 接线只此三处（外层兜底 / 残缺 / 本地 catch）", 3, svc.countOccurrences("enterForegroundThenStop(startId)"))

        assertEquals("退场点总数（新增一处就要新写一条判式）", 6, svc.countOccurrences("stopSelf"))
        assertEquals(
            "带 startId 的退场：兜底 / 早退 / helper / onTimeout / L4 安装入口",
            5,
            svc.countOccurrences("stopSelf(startId)"),
        )
        assertEquals("无参退场只在线程收尾一处", 1, svc.countOccurrences("stopSelf()"))

        val timeout = svc.indexOf("override fun onTimeout(startId: Int, fgsType: Int) {")
        assertTrue("找不到 onTimeout", timeout >= 0)
        val startCommand = svc.indexOf("override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {", timeout)
        assertTrue("找不到 onStartCommand（onTimeout 的 body 边界靠它定）", startCommand > timeout)
        val timeoutBody = svc.substring(timeout, startCommand)
        assertEquals("onTimeout 的退场只走判定矩阵一处", 1, timeoutBody.countOccurrences("stopSelf(startId)"))
        assertEquals("onTimeout 不许再亮一次前台（服务本就在前台）", 0, timeoutBody.countOccurrences("startForegroundCompat"))
        assertEquals("onTimeout 不许走过闸退场 helper", 0, timeoutBody.countOccurrences("enterForegroundThenStop"))
    }

    /**
     * L4-7：`ACTION_INSTALL` 是一次性意图 —— 成功路径也要退场，且**只在没持单飞闸时**退：
     * 误停会把在飞下载连服务一起停掉（onDestroy 兜底会取消它）。
     *
     * 这条退场不需要前台声明（`ACTION_INSTALL` 由结果通知的 `PendingIntent.getService` 起，
     * 不是 `startForegroundService`），所以判式只管「退场 + 持闸守卫」两件。
     */
    @Test
    fun `安装入口成功路径退场且带持闸守卫`() {
        val branchAt = svc.indexOf("ACTION_INSTALL -> {")
        assertTrue("找不到 ACTION_INSTALL 分支", branchAt >= 0)
        val branchEnd = svc.indexOf("else -> beginDownloadSafe(", branchAt)
        assertTrue("找不到 ACTION_INSTALL 分支的终点", branchEnd > branchAt)
        val branch = svc.substring(branchAt, branchEnd)
        val stop = branch.indexOf("stopSelf(startId)")
        assertTrue("安装入口处理完必须退场（否则留常驻空服务）", stop >= 0)
        val guard = branch.indexOf("if (!gateHeld)")
        assertTrue("退场必须带持闸守卫（不许停掉在飞下载的服务）", guard in 0 until stop)
        assertEquals("安装入口只许退场一次", 1, branch.countOccurrences("stopSelf"))
    }

    // ------------------------------------------------------------------
    // 取源码：工作目录随 gradle 入口变（口径同 LiveReflowWiringTest / StallSentinelTest）
    // ------------------------------------------------------------------

    private val moduleRootCandidates = listOf(File("."), File("../app"), File("app"))

    private fun moduleRoot(): File =
        moduleRootCandidates.firstOrNull { File(it, "src/main/kotlin").isDirectory }
            ?: error("找不到 app/src/main/kotlin（试了 ${moduleRootCandidates.joinToString { it.path }}）")

    /** 主源文件的**代码行**（丢掉注释与 KDoc）：源码面钉子判的是接线，不是文档措辞。 */
    private fun codeLines(name: String): String =
        File(moduleRoot(), "src/main/kotlin/com/eratw/port/android/$name")
            .readText(Charsets.UTF_8)
            .lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }
            .joinToString("\n")

    private fun String.countOccurrences(needle: String): Int {
        var index = 0
        var count = 0
        while (true) {
            val at = indexOf(needle, index)
            if (at < 0) return count
            count++
            index = at + needle.length
        }
    }
}
