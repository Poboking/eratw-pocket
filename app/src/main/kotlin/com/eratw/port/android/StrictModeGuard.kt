package com.eratw.port.android

/**
 * StrictMode 的装载策略（只两个档，没有第三种「装了但换个罚则」的形状）。
 *
 * @param installs 要不要真的去 `StrictMode.setThreadPolicy/setVmPolicy`
 */
internal enum class StrictModePolicy(val installs: Boolean) {
    /** debug 构建：开侦测，处罚只有一项 —— 记日志。 */
    DETECT_AND_LOG(true),

    /** release 构建（玩家手上那一版）：一行策略都不装。 */
    DISABLED(false),
}

/**
 * StrictMode 的选型判据 + 「UI 线程磁盘 IO」豁免表。
 *
 * **本文件刻意不 import 任何 Android 类**：装策略与开豁免窗口的动作在
 * [EratwStrictMode]（`EratwApplication.kt`），这里只有纯判式，好在 JVM 单测里逐条钉死
 * （同 [UiFaultLog] / [HeartbeatJudgment] / [RunTrail] 的做法）。
 *
 * ## 为什么要装这个哨兵
 *
 * 玩家报「有时会玩着玩着就卡死 / 点击屏幕没反应」。本波已经定性收口一条：
 * `turn Nms` 出现 2 ms → 2342 ms → 15192 ms → 58515 ms 的尾部劣化、同期 Java 堆贴顶
 * （510/512 MiB）——**卡的是脚本线程 `eratw-vm`，不是 Android 主线程**（ADR-003：VM 单线程，
 * 渲染在 Choreographer 回调里异步消费行表）。但「主线程上藏了磁盘/网络/锁等待」这一类
 * 本仓真机确实出过两次（翻页读数回调跑在输入锁里、绘制路径无兜底），收口之后没有常驻哨兵
 * 防再犯 —— StrictMode 就是那条成本最低的回答「UI 线程这次到底有没有等磁盘」的路。
 *
 * ## 为什么罚则只有 `penaltyLog`，绝不 `penaltyDeath`
 *
 * 本仓的既定口径是「UI 线程出错只记日志、进程继续」，见 `MainActivity.kt:294`
 * （`uiStage`：RuntimeException 就地兜住、不外逃）与 `MainActivity.kt:331`
 * （`guardedAction`：按钮回调同口径）。理由写在 [UiFaultLog] 的类头里：Android 上主线程
 * 逃出去的异常直接结束进程，玩家看到的就是「点了一下，应用没了」。
 * `penaltyDeath` 等于主动把一次磁盘违规变成闪退 —— 那正是我们在治的症状，不能自己再造一遍。
 * `penaltyDialog` 更不行：它是**模态**对话框，游戏还在跑，玩家既关不掉也点不动。
 * `penaltyDropBox` 只写系统 dropbox，本仓取证走的是 logcat + 错误报告那条链（见
 * [ErrorReportCollector]），多一条进不去的路没有收益。
 *
 * ## 豁免表为什么必须存在
 *
 * UI 线程上的磁盘 IO 分两种：**待修缺陷**与**设计决定**。后者的共同形状是「这一发必须当场做完」，
 * 挪线程要么让取证随进程一起消失，要么改掉判据的成立条件。它们会被哨天天刷，把真信号埋掉 ——
 * 所以进表，且每条都写明理由。表外一律不开豁免口：见 [allowsUiDiskIo] 与 [rejectionMessage]。
 *
 * ## 进表的尺子（2026-11-01 obs-uiio 车道把上一路列出的六条逐条判完后的口径）
 *
 * 判据一句话：**这条 IO 会不会把一帧拖到玩家能感知的程度**，以及**挪走它会不会改掉语义**。
 * 三条都成立才进表：
 * 1. 落在「进程下一刻可能就没命」的边上（`onPause` / `onTrimMemory` / 冻结判读 / 崩溃处理器），
 *    或者落在**第一帧之前**（冷启动记账、首选项首读 —— 那两段没有在飞的帧可拖）；
 * 2. 落盘本身有节流或天然一次性（轨迹整份约 4 KB、状态文件一行、冷启动每进程一次），
 *    不是每帧每点都来一发；
 * 3. 挪走会改掉**不许变**的那几样：轨迹行的顺序与内容、`state.txt` 那套 `RunTrail.MARK_*`
 *    脏退出状态机的先后、报告文件的落盘时机。
 *
 * 同一把尺子也判掉了两条**不进表**的：
 * - 失败屏「复制报告」那一次**整份报告的读盘**（最坏约 15 万字符，行级截断口径在 core 的
 *   `ErrorReport.kt:185-204`）**挪走了** —— 它既不在「进程将死」
 *   的边上，也不是第一帧之前，而是落在一次点击里（正是本仓真机出过两次的输入路径形状）；
 *   见 `MainActivity.copyReportFile`，读数落在那一发的日志行里。
 * - 失败屏「分享报告」在 UI 线程上**不读报告内容**：内容经 `content://` 由对端进程拉走，
 *   那一发落在我们进程的 Binder 线程池上，而线程策略是按线程装的（`StrictMode.setThreadPolicy`
 *   只管调用它的那条线程，见 AOSP `frameworks/base` 的 `StrictMode.java`：`setThreadPolicy`
 *   写的是当前线程的 mask）⇒ 哨本来就盖不住，不进表；替代哨兵是 `MainActivity.shareReportFile`
 *   里那一行 UI 侧耗时读数。
 *
 * 表里有 2 条还是 7 条不是重点，重点是**每一条都在单测里点名叫过一次**（见
 * `StrictModeGuardTest` 的点名清单那条用例）：悄悄扩白名单才是重点。
 *
 * 命名口径（fix-uiowiring 车道收口）：这张表与 [EratwStrictMode.allowUiDiskIo] 管的是
 * **UI 线程磁盘 IO**（读写一并 —— 落地口放开的是 `allowThreadDiskWrites`，磁盘读与写同窗），
 * 出生时的「写盘 / Write」字样一律归一为「磁盘 IO / DiskIo」；改名只动标识符与注释，
 * 表内容、判式语义与安装面零变化。
 */
internal object StrictModeGuard {

    /** logcat 里我们这一路的 tag（系统自己那份固定叫 `StrictMode`，两份要能分清）。 */
    const val LOG_TAG: String = "EratwStrictMode"

    // ------------------------------------------------------------------
    // 装载判据
    // ------------------------------------------------------------------

    /**
     * 只有 debug 构建装哨兵。
     *
     * release 装了会怎样：`penaltyLog` 本身不杀进程，但它每次违规都要抓一份栈 + 打一段
     * logcat，而本仓 UI 线程上合法的磁盘读并不少（`SharedPreferences` 的读盘、随包清点）——
     * 结果是给玩家的包白白多一层抖动与日志量，换不到任何取证（没人看 release 的 logcat）。
     */
    fun policyFor(isDebug: Boolean): StrictModePolicy =
        if (isDebug) StrictModePolicy.DETECT_AND_LOG else StrictModePolicy.DISABLED

    // ------------------------------------------------------------------
    // 侦测项（从 detectAll 里逐项挑出来；字符串就是 Android 侧的落地开关名）
    // ------------------------------------------------------------------

    const val DETECT_DISK_READS: String = "detectDiskReads"
    const val DETECT_DISK_WRITES: String = "detectDiskWrites"
    const val DETECT_NETWORK: String = "detectNetwork"
    const val DETECT_CUSTOM_SLOW_CALLS: String = "detectCustomSlowCalls"
    const val DETECT_LEAKED_CLOSEABLES: String = "detectLeakedClosableObjects"

    const val PENALTY_LOG: String = "penaltyLog"

    /**
     * debug 档在主线程上开的侦测项，以及每一项的理由（挑出来什么、为什么不要别的）。
     *
     * 口径：只留「能回答 UI 线程这次有没有等」的四项，凡是**本仓天天合法命中**的一项都不开
     * —— 哨的价值在于报出来的每一行都值得看一眼。
     */
    val debugThreadDetections: List<String> = listOf(
        // 本波要回答的就是这一条：翻页/装载/取读数在 UI 线程读文件，是已收口过两次的事故形状。
        DETECT_DISK_READS,
        DETECT_DISK_WRITES,
        // UI 线程发网络 = 直接的网络 ANR；本仓唯一的出站 HTTP 是上报与更新下载，都不该在 UI 线程。
        DETECT_NETWORK,
        // 自定义违规标签的开关：不开它 [violationTag] 注进去的标签根本不会被报出来。
        DETECT_CUSTOM_SLOW_CALLS,
    )

    /** 侦测项之外刻意**不要**的项与理由（写在这里，防止后人「顺手加 detectAll」）。 */
    val rejectedDetections: Map<String, String> = linkedMapOf(
        // `System.gc()` 是 ERB 内建函数的官方行为（core-vm 的 HostMethods.kt:125），
        // 脚本自己就会调；开了它等于每回合都可能刷一行「你显式 GC 了」。
        "detectExplicitGc" to "脚本内建的 GC 调用是官方行为，命中天天有",
        // 轨迹与报告落盘用的就是裸流（Files 写文本），未缓冲 IO 是设计形状而不是缺陷。
        "detectUnbufferedIo" to "本仓落盘一律小文件裸写，纯噪声",
        // detectAll 会把上面两项一起带进来，还带上资源错配这类与 UI 卡顿无关的项。
        "detectAll" to "会连带 detectExplicitGc / detectUnbufferedIo / detectResourceMismatches，噪声盖掉真信号",
        // 锁等待侦测在本仓编译用的 SDK 里根本不是公开 API：
        // javap android-36 与 android-35 的 android.jar，StrictMode.ThreadPolicy.Builder
        // 只有 detectAll/detectCustomSlowCalls/detectDiskReads/detectDiskWrites/detectExplicitGc/
        // detectNetwork/detectResourceMismatches/detectUnbufferedIo 八项，没有 detectLocks。
        "detectLocks" to "compileSdk 36 的 android.jar 里不存在（非公开 API），编译不过",
    )

    /**
     * debug 档在进程级（VmPolicy）开的侦测项：只要一项。
     *
     * 其余项不要的理由：`detectActivityLeaks` 会被壳层 Compose/View 的生命周期常态命中；
     * `detectFileUriExposure` 早已由 content:// 的 Provider 收口（见 [UpdateFileProvider]）；
     * `detectCleartextNetwork` 会把本地测试用的 http 上报端点天天报出来。
     * 而 `detectLeakedClosableObjects` 正好盯住本仓最多的那一类资源（随包解包 / 轨迹落盘的流），
     * 漏关是**真**泄漏，且在 512 MiB 封顶的堆上会直接推高水位。
     */
    val debugVmDetections: List<String> = listOf(DETECT_LEAKED_CLOSEABLES)

    /** 处罚项：**有且只有** `penaltyLog`（分类理由见本类头）。单测钉死它不许长出第二项。 */
    val debugPenalties: List<String> = listOf(PENALTY_LOG)

    // ------------------------------------------------------------------
    // UI 线程磁盘 IO 的豁免表
    // ------------------------------------------------------------------

    /** `MainActivity.onPause` 的轨迹钉发（沿用该处 `uiStage` 已有的原文案，两套口径不打架）。 */
    const val WHERE_PAUSE_TRAIL: String = "切后台轨迹落盘"

    /** `MainActivity.onTrimMemory` 的取证钉发。 */
    const val WHERE_TRIM_TRAIL: String = "内存压力轨迹落盘"

    /**
     * `MainActivity.markHeartbeats` 的冻结钉行（obs-heartbeat 那路加的；判读成立就在 pump 帧里钉一发）。
     *
     * 调用点在帧循环里，冷却下限是 [HeartbeatJudgment.FREEZE_PIN_MIN_INTERVAL_MS]（30 s 评估一次）。
     */
    const val WHERE_FREEZE_TRAIL: String = "冻结钉行轨迹落盘"

    /** `MainActivity.onCreate` 最早处的冷启动记账（读上一轮收尾态 + 落脏退出补报 + 钉 `boot` 行）。 */
    const val WHERE_COLD_START: String = "冷启动记账落盘"

    /** `onStart` / `onStop` / `onDestroy` / 「结束应用」那几发的 `state.txt` 单行状态落盘。 */
    const val WHERE_LIFECYCLE_STATE: String = "生命周期状态落盘"

    /** [UiPrefs] 每进程一次的**首读**（首读之后全部走内存快照，不再命中磁盘）。 */
    const val WHERE_PREFS_COLD_READ: String = "首选项首读落盘"

    /** 崩溃处理器里的取证三件套：最小现场 + 报告落盘 + 一行 `stopped` 认账。 */
    const val WHERE_CRASH_FORENSICS: String = "崩溃现场取证落盘"

    /**
     * 唯一的豁免表：`where` → 为什么允许在 UI 线程做这次磁盘 IO。
     *
     * 进表的门槛写在类头「进表的尺子」那一节，三条缺一不可。**表是跟着理由走的，不是跟着条数走的**：
     * 每加一条要同时给 [EratwStrictMode.allowUiDiskIo] 一个点名这个常量的调用点 + 在
     * `StrictModeGuardTest` 的点名清单里补一条（那里少一条就红），并由组长过一遍；
     * 表外动作走 [rejectionMessage] 那条路才是常态。
     *
     * 注意窗口放开的**只有磁盘读写**（`StrictMode.allowThreadDiskWrites` 只清这两项侦测位），
     * 所以豁免点里顺手发网络请求照样被 [DETECT_NETWORK] 抓出来 —— 崩溃档那发同步补发就是例子。
     */
    val uiDiskIoExemptions: Map<String, String> = linkedMapOf(
        WHERE_PAUSE_TRAIL to
            "切后台必须立刻钉一发轨迹：下一次冷启动判脏退出时，盘上最后一行是不是 `pause` " +
            "就把「死在屏前」（真卡死/崩溃）与「回后台后被系统回收」（正常管控，不是 bug）分开。" +
            "挪到后台线程等于放弃这个时机 —— 进程可能在这一发写完之前就没命了。",
        WHERE_TRIM_TRAIL to
            "系统已经在喊内存紧张，这一发是区分「Java 堆贴顶」与「被 OEM 后台管控无差别杀掉」的" +
            "决定性读数；等下一个回合边界（VM 线程）再钉，最坏情况是根本到不了下一个边界。",
        WHERE_FREEZE_TRAIL to
            "冻结判读成立的那一瞬间就是这一行轨迹的全部价值：脏退出报告是**下一次冷启动**落的，" +
            "那时活的心跳读数早没了，只有盘上最后一行还认得出「谁先停」；等下一帧再钉，读到的就是" +
            "解冻之后的形状。这一发自己带 30 s 评估下限，整份只写约 4 KB。" +
            "（判词更正 2026-11-02，EP1 波 L4：原文还写着「挪到后台线程就要给 trail.txt 添第三个" +
            "写者 ⇒ 讲不清先后」，那条已经不成立 —— `eratw-stall` 哨兵就是第三个写者，先后由 " +
            "RunWatch 的 TrailWriteGate（fix-trailtear）按环形表追加序号定序。豁免留下的理由只剩" +
            "上面那条时序。）",
        WHERE_COLD_START to
            "这一段跑在 onCreate 的**第一帧之前**：那一刻没有在飞的帧可拖，玩家等的是启动不是响应。" +
            "更硬的一条是时序 —— 脏退出判据必须在装载开始之前读完上一轮的 state.txt 与 trail.txt，" +
            "并把「上次异常退出」那份补报落到盘上；挪到后台线程就是把这张卡的成立条件改成竞态" +
            "（consumeDirtyExitReportFile 拿到 null，玩家就再也交不出那份报告）。",
        WHERE_LIFECYCLE_STATE to
            "state.txt 是一行状态机（running / background / stopped / consumed），四种死法全靠这几发" +
            "落盘的**先后**才读得出意思：onStop 那一发晚一步，进程就可能已经被 FORCE STOP；" +
            "「结束应用」那一发后面紧跟 Runtime.exit(0)。异步 handoff 要么丢掉这一行，" +
            "要么让 background 与 running 抢同一份文件 —— 两种都是把脏退出判据改掉，不是把 IO 挪走。",
        WHERE_PREFS_COLD_READ to
            "整个进程只有这一发真的命中磁盘（首读之后 Android 走内存快照，其余调用点都是查表），" +
            "而它必须在第一帧之前把字号档位交出来：列数是 CanvasConsoleView 的构造期参数。" +
            "「Application 里 preload 后删表」的原案经 fix-uiowiring 车道判为**删不掉**：" +
            "AOSP 的 SharedPreferencesImpl 本就在独立线程异步 load，主线程的等待点在**首个 getter**" +
            "（awaitLoadedLocked 显式 noteDiskRead 把这一发记到当前线程）⇒ preload 只是把豁免点" +
            "从确定的一发 getSharedPreferences 摊成「装配/设置页/更新通道谁先摸到谁等」；" +
            "真机读数未过线（无设备，TODO(verify)）之前不删，删表要连点名清单条目一起删。",
        WHERE_CRASH_FORENSICS to
            "崩掉的那条线程就地写完取证三件套（crash-prefix + 报告 + 一行 stopped 认账）：" +
            "这是进程**最后一次能自己落盘**的机会，任何异步 handoff 都随进程一起消失。" +
            "少那一行 stopped，下一次冷启动就把这次崩误判成「死在无人认领处」再弹一次卡。" +
            "窗口只放磁盘读写：紧随其后的同步补发（3 s 上限）照旧会被网络侦测记一行，那是故意的。",
    )

    /**
     * 这个动作点是否允许在 UI 线程做磁盘 IO —— 表里没有就是不允许，没有兜底放行。
     *
     * 落地口 [EratwStrictMode.allowUiDiskIo] 放开的是 `allowThreadDiskWrites`，磁盘**读写一并**
     * （fix-uiowiring 车道把名字里的「Write」历史包袱归一掉了：表出生时只有两条写盘，
     * 后来「首选项首读」这样的读盘动作点也走同一张表）；非主线程调用不开窗口，闸在本体。
     */
    fun allowsUiDiskIo(where: String): Boolean = uiDiskIoExemptions.containsKey(where)

    /** 豁免理由（表外为 null）：日志与单测都读它，防止有人塞一条空壳豁免。 */
    fun reasonFor(where: String): String? = uiDiskIoExemptions[where]

    /**
     * 表外动作点试图开豁免口时的日志文案。
     *
     * 语义是「不开窗口、让哨照常报」，而不是「放行」：调用方拿到这句话之后仍然照常执行动作，
     * 违规该抓还是抓 —— 这样漏加表的表现是**多一行日志**，不是少一次侦测。
     */
    fun rejectionMessage(where: String): String =
        "UI 线程磁盘 IO 豁免表里没有「$where」：不开豁免窗口，让 StrictMode 照常报这一处。" +
            "确认这是设计决定才进表（要写理由 + 补单测），别在这里加白名单。"

    // ------------------------------------------------------------------
    // 豁免命中慢样本时的读数（豁免不等于闭嘴）
    // ------------------------------------------------------------------

    /**
     * 一次 UI 线程磁盘 IO 慢到多少毫秒算「值得留一行」。
     *
     * 取 2 s 而不是 5 s：本波定性出的第一条尾部劣化就是 2342 ms，2 s 已经是玩家能感觉到
     * 「顿一下」的下沿；`RunTrail.LONG_TURN_MS`（`RunWatch.kt:437`，5 s）是玩家**嘴上说卡死**
     * 的那条线，留给回合边界判据用，不该拿来要求一次只写一行的轨迹落盘。
     */
    const val SLOW_UI_DISK_IO_MS: Long = 2_000L

    /** 豁免窗口里的这次磁盘 IO 值不值得报（边界含等号：正好 2 s 也报）。 */
    fun isSlowUiDiskIo(durationMs: Long): Boolean = durationMs >= SLOW_UI_DISK_IO_MS

    /**
     * 注进 StrictMode 的自定义违规标签（`StrictMode.noteSlowCall` 的入参）。
     *
     * 带 `where` 原文：logcat 里那一行仍然读得出「是哪一处、为什么允许」，限流之后只剩
     * 一行计数时也不会退化成一串匿名数字。前缀固定，方便 grep。
     */
    fun violationTag(where: String): String = "eratw-ui-disk/$where"

    /** 慢样本自己打的那一行（含耗时、含豁免理由；限流节奏复用 [UiFaultLog]）。 */
    fun slowDiskIoMessage(where: String, durationMs: Long): String =
        "UI 线程磁盘 IO 豁免命中慢样本：$where 用了 ${durationMs}ms（阈值 ${SLOW_UI_DISK_IO_MS}ms）" +
            "｜标签 ${violationTag(where)}｜理由 ${reasonFor(where) ?: "表外（不该出现在这里）"}"
}
