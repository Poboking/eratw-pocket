package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [StrictModeGuard] 的口径（OBS strictmode 车道）。
 *
 * 钉住四件事，每一件都是「装错了对玩家有伤害」的形状：
 * 1. **release 不装**：玩家那一版不许有任何 StrictMode 行为（哪怕只是多打日志）；
 * 2. **罚则只有 penaltyLog**：`penaltyDeath` 会把一次磁盘违规直接变成玩家眼前的闪退 ——
 *    正是本波在治的症状；判式在 [StrictModeGuard.debugPenalties]；
 * 3. **豁免表里每一条都在表内且理由非空**，表外一律不放行：豁免表是 UI 线程磁盘 IO 的唯一白名单，
 *    散落的 `strictModeOff {}` 魔法会把它变成没人知道边界的东西。本车道（obs-uiio）把上一路列出的
 *    六处逐条裁完之后表变长了，所以**条目数不再由测试硬写**：改成 [pinnedExemptions] 那份点名清单
 *    跟着表走 —— 每条表项都要在清单里有一句自己的决定性理由关键词，少一句就红（见
 *    [豁免表跟着点名清单走]），这样「再加一条」必然伴随「再加一条说法」，糊不过去；
 * 4. **慢样本阈值**：豁免不等于闭嘴，2 s 以上的 UI 线程磁盘 IO 必须留下带标签的一行
 *    （本波定性出的第一条尾部劣化就是 `turn 2342ms`）。
 *
 * 为什么是纯 JVM 单测：判式全在 [StrictModeGuard]（不 import 任何 Android 类），同
 * [UiFaultLog] / [HeartbeatJudgment] / `RunTrailTest` 的先例。`EratwStrictMode` 那半边
 * 只碰 `android.os.StrictMode`，靠编译 + 真机 logcat 验（本轮无设备 ⇒ 见交付报告的 TODO(verify)）。
 *
 * 另附源码面的防扩散断言（取代码行，KDoc 与行注释不参与）：
 * 1. [豁免调用点与表一一对应] —— **MainActivity.kt 与 UiPrefs.kt** 两个文件里，豁免口点名的常量
 *    集合与表完全一致（每条表项都真有调用点，每个调用点都在表里）；
 * 2. [strictmode 的直接调用只有一处] —— `android.os.StrictMode` 只许在 [EratwApplication] 被直接调用；
 * 3. [没有任何会杀进程的罚则] —— `app/src/main` 里没有 `penaltyDeath`/`penaltyDialog` 那类形状；
 * 4. [裁走的两条] 与 [设置页复制诊断是拆分形状，组合口已删] —— 判为**乙**（读数挪后台）与
 *    **丙**（分享：哨盖不住）那几处，各自钉住落地形状，防止有人把它们又塞回豁免表；
 * 5. [主线程闸在豁免口本体，调用方不各自分支] —— 非主线程不开窗口的闸只许写在
 *    `EratwStrictMode.allowUiDiskIo` 本体一处（fix-uiowiring 收口，调用方分支反成双闸隐患）。
 */
class StrictModeGuardTest {

    // ------------------------------------------------------------------
    // 1. 装载判据：release 不装
    // ------------------------------------------------------------------

    /** release 构建拿到的是 DISABLED，且 `installs=false`：一条策略都不落地。 */
    @Test
    fun `release 不装 strictmode`() {
        val release = StrictModeGuard.policyFor(isDebug = false)
        assertEquals("release 档必须是 DISABLED", StrictModePolicy.DISABLED, release)
        assertFalse("release 档 installs 必须为 false", release.installs)
    }

    /** debug 构建才装，且装的是「侦测 + 只记日志」那一档。 */
    @Test
    fun `debug 才装 strictmode`() {
        val debug = StrictModeGuard.policyFor(isDebug = true)
        assertEquals("debug 档必须是 DETECT_AND_LOG", StrictModePolicy.DETECT_AND_LOG, debug)
        assertTrue("只有 debug 档 installs 为 true", debug.installs)
    }

    // ------------------------------------------------------------------
    // 2. 选型：侦测项与处罚项
    // ------------------------------------------------------------------

    /** 处罚项有且只有 `penaltyLog`：多出一项就要在判式里写明理由，测试先挡住。 */
    @Test
    fun `处罚只有记日志`() {
        assertEquals(
            "处罚表只能有一项（penaltyDeath / penaltyDialog 一律不许进来）",
            listOf(StrictModeGuard.PENALTY_LOG),
            StrictModeGuard.debugPenalties,
        )
        assertTrue(
            "处罚项里出现 death 或 dialog 就是会杀进程或弹窗的形状",
            StrictModeGuard.debugPenalties.none { it.contains("Death") || it.contains("Dialog") },
        )
    }

    /** 主线程四项侦测逐条钉住：磁盘读、磁盘写、网络、自定义慢调用（标签靠它才报得出来）。 */
    @Test
    fun `主线程侦测项点名四件`() {
        val expected = setOf(
            StrictModeGuard.DETECT_DISK_READS,
            StrictModeGuard.DETECT_DISK_WRITES,
            StrictModeGuard.DETECT_NETWORK,
            StrictModeGuard.DETECT_CUSTOM_SLOW_CALLS,
        )
        assertEquals("侦测项必须与判式表逐条对应，既不少也不多", expected, StrictModeGuard.debugThreadDetections.toSet())
    }

    /** 被拒的项（`detectAll` / `detectExplicitGc` / `detectUnbufferedIo` / `detectLocks`）不许混进开的那张表。 */
    @Test
    fun `拒掉的侦测项都在拒因表里有说法`() {
        for (rejected in StrictModeGuard.rejectedDetections.keys) {
            assertFalse(
                "「$rejected」已写明不要的理由，不许再出现在启用的侦测项里",
                StrictModeGuard.debugThreadDetections.contains(rejected) ||
                    StrictModeGuard.debugVmDetections.contains(rejected),
            )
        }
        assertTrue(
            "每项被拒动作都要写明理由（空理由等于没理由）",
            StrictModeGuard.rejectedDetections.values.all { it.isNotBlank() },
        )
    }

    // ------------------------------------------------------------------
    // 3. 豁免表
    // ------------------------------------------------------------------

    /**
     * 豁免表的**点名清单**：`where` → 那一条理由里必须出现的决定性说法。
     *
     * 这就是「表跟着理由走」的实现：条目数不写死在这里，写死的是「每一条都要有一句自己的理由」——
     * 往 [StrictModeGuard.uiDiskIoExemptions] 加一行而不在这里补一行，[豁免表跟着点名清单走] 立刻红；
     * 把某条理由换成一句空话，这一条自己的断言立刻红。
     */
    private val pinnedExemptions: Map<String, String> = linkedMapOf(
        // 切后台：进程可能在这一发写完之前就没命。
        StrictModeGuard.WHERE_PAUSE_TRAIL to "没命",
        // 内存压力：这一发是两种死法的决定性读数。
        StrictModeGuard.WHERE_TRIM_TRAIL to "决定性读数",
        // 冻结钉行：trail.txt 只能有两个写者（UI 与 VM），异步 handoff 会添第三个。
        StrictModeGuard.WHERE_FREEZE_TRAIL to "第三个写者",
        // 冷启动记账：跑在第一帧之前，没有在飞的帧可拖。
        StrictModeGuard.WHERE_COLD_START to "第一帧之前",
        // 生命周期状态：一行状态机靠落盘的先后才读得出意思。
        StrictModeGuard.WHERE_LIFECYCLE_STATE to "先后",
        // 首选项：每进程只落一次磁盘，且必须早于第一帧给出字号档位。
        StrictModeGuard.WHERE_PREFS_COLD_READ to "只有这一发真的命中磁盘",
        // 崩溃现场：进程最后一次能自己落盘的机会。
        StrictModeGuard.WHERE_CRASH_FORENSICS to "最后一次能自己落盘",
    )

    /** 两处最早就有的豁免仍在表内且理由非空（理由就是「为什么允许」的那一行）。 */
    @Test
    fun `两处豁免在表内且有理由`() {
        for (where in listOf(StrictModeGuard.WHERE_PAUSE_TRAIL, StrictModeGuard.WHERE_TRIM_TRAIL)) {
            assertTrue("「$where」必须能豁免", StrictModeGuard.allowsUiDiskIo(where))
            val reason = StrictModeGuard.reasonFor(where)
            assertNotNull("「$where」必须有理由（不许塞空壳豁免）", reason)
            assertTrue("「$where」的理由要写满一句以上", (reason?.length ?: 0) >= 20)
        }
    }

    /**
     * 表跟着点名清单走，不跟着某个写死的数字走。
     *
     * 三条：键集合一致（加一条表项必须先在这里补一句理由）、条目数一致（同一件事从两个方向挡住）、
     * 每一条的理由都点到自己那句决定性说法（防止把理由写成一句谁都对得上的空话）。
     */
    @Test
    fun `豁免表跟着点名清单走`() {
        assertEquals(
            "豁免表的每一条都必须在 pinnedExemptions 里点名句（新增一条先补一句自己的理由断言）",
            pinnedExemptions.keys,
            StrictModeGuard.uiDiskIoExemptions.keys,
        )
        assertEquals(
            "表大小与点名清单一致（现读到 ${StrictModeGuard.uiDiskIoExemptions.size} 条 / 点名 ${pinnedExemptions.size} 条）",
            pinnedExemptions.size,
            StrictModeGuard.uiDiskIoExemptions.size,
        )
        for ((where, phrase) in pinnedExemptions) {
            val reason = StrictModeGuard.reasonFor(where)
            assertNotNull("「$where」必须有理由（不许塞空壳豁免）", reason)
            assertTrue("「$where」的理由要写满一句以上：$reason", reason!!.length >= 20)
            assertTrue(
                "「$where」的理由必须点出它自己的那句决定性说法「$phrase」：$reason",
                reason.contains(phrase),
            )
        }
    }

    /**
     * 新加的五条里最危险的两条单独钉：钉的不是措辞而是**不可挪的理由**。
     *
     * 冻结钉行要写出「轨迹文件只有两个写者」与自带的 30 s 冷却下限；崩溃取证要写出
     * 「报告 + 一行 stopped 认账」这两样都会随异步 handoff 一起丢。缺任何一句，
     * 后人就会把这两条当成「嫌日志吵所以放行」。
     */
    @Test
    fun `冻结钉行与崩溃取证写明不可挪的理由`() {
        val freeze = requireNotNull(StrictModeGuard.reasonFor(StrictModeGuard.WHERE_FREEZE_TRAIL))
        assertTrue("冻结钉行的理由要写明 trail.txt 的写者问题：$freeze", freeze.contains("trail.txt"))
        assertTrue("冻结钉行的理由要写明它自带 30 s 下限：$freeze", freeze.contains("30 s"))
        val crash = requireNotNull(StrictModeGuard.reasonFor(StrictModeGuard.WHERE_CRASH_FORENSICS))
        assertTrue("崩溃取证的理由要带上那一行 stopped 认账：$crash", crash.contains("stopped"))
        assertTrue("崩溃取证的理由要带上报告本身：$crash", crash.contains("报告"))
    }

    /** 表外动作点一律不放行：拼错的、近似字符串的、空的、以及本车道判为**乙/丙**那两处。 */
    @Test
    fun `表外一律不许豁免`() {
        val notExempt = listOf(
            "",
            "内存采样",
            "切后台让出焦点",
            StrictModeGuard.WHERE_PAUSE_TRAIL + " ",
            StrictModeGuard.WHERE_PAUSE_TRAIL.replace("轨迹", "轨迹轨迹"),
            "freeze 钉行",
            "EratwRun",
            // 乙：整份报告的读数已挪到后台线程，不该有豁免口。
            "报告读数上屏",
            // 丙：分享这一发的磁盘读发生在对端进程 / Binder 线程，哨本来就盖不住。
            "分享报告",
            // 判式表里的常量名不许当动作点用（动作点是值，不是字段名）。
            "WHERE_FREEZE_TRAIL",
        )
        for (where in notExempt) {
            assertFalse("表外动作点「$where」不许放行", StrictModeGuard.allowsUiDiskIo(where))
            assertNull("表外动作点没有理由可取：$where", StrictModeGuard.reasonFor(where))
        }
    }

    /** 表外的拒绝文案要能自解释：写清「不开窗口、照常报」，并指向进表的正确路径。 */
    @Test
    fun `表外的拒绝文案说清下一步`() {
        val message = StrictModeGuard.rejectionMessage("内存压力采样")
        assertTrue("拒绝文案要点名是哪个动作点：$message", message.contains("内存压力采样"))
        assertTrue("拒绝文案要写清不开豁免窗口：$message", message.contains("不开豁免窗口"))
        assertTrue("拒绝文案要指向进表的条件：$message", message.contains("补单测"))
    }

    // ------------------------------------------------------------------
    // 4. 慢样本判式与标签
    // ------------------------------------------------------------------

    /** 阈值边界含等号：1999 ms 不报，2000 ms 起报（本波第一条尾部劣化 2342 ms 必须报）。 */
    @Test
    fun `慢样本阈值边界`() {
        assertFalse("阈值以下不许占日志", StrictModeGuard.isSlowUiDiskIo(0L))
        assertFalse("1999ms 不到线", StrictModeGuard.isSlowUiDiskIo(StrictModeGuard.SLOW_UI_DISK_IO_MS - 1L))
        assertTrue("正好到线要报", StrictModeGuard.isSlowUiDiskIo(StrictModeGuard.SLOW_UI_DISK_IO_MS))
        assertTrue("2342ms 是本波定性出的形状，必须报", StrictModeGuard.isSlowUiDiskIo(2_342L))
        assertTrue("58515ms 必须报", StrictModeGuard.isSlowUiDiskIo(58_515L))
        assertEquals("阈值取 2s（玩家已能感觉「顿一下」的下沿）", 2_000L, StrictModeGuard.SLOW_UI_DISK_IO_MS)
    }

    /** 自定义违规标签带动作点原文：限流之后只剩一行，也仍读得出是哪一处。 */
    @Test
    fun `违规标签带动作点原文`() {
        val tag = StrictModeGuard.violationTag(StrictModeGuard.WHERE_PAUSE_TRAIL)
        assertTrue("标签要有固定前缀，方便 grep：$tag", tag.startsWith("eratw-ui-disk/"))
        assertTrue("标签要把动作点原样带出来：$tag", tag.contains(StrictModeGuard.WHERE_PAUSE_TRAIL))
        // noteSlowCall 的入参就是这一个字符串：两处豁免的标签必须互不相同，否则读不出谁慢。
        assertFalse(
            "两个豁免点的标签不许撞车",
            tag == StrictModeGuard.violationTag(StrictModeGuard.WHERE_TRIM_TRAIL),
        )
    }

    /** 慢样本那行日志：耗时、阈值、标签、以及「为什么允许」的理由都要在同一行里。 */
    @Test
    fun `慢样本日志一行读全`() {
        val line = StrictModeGuard.slowDiskIoMessage(StrictModeGuard.WHERE_TRIM_TRAIL, 2_342L)
        assertTrue("要带动作点：$line", line.contains(StrictModeGuard.WHERE_TRIM_TRAIL))
        assertTrue("要带实测耗时：$line", line.contains("2342ms") || line.contains("2_342ms"))
        assertTrue("要带阈值：$line", line.contains("2000ms"))
        assertTrue("要带能被 grep 的标签：$line", line.contains(StrictModeGuard.violationTag(StrictModeGuard.WHERE_TRIM_TRAIL)))
        assertTrue("要带为什么允许的理由：$line", line.contains("决定性读数"))
    }

    // ------------------------------------------------------------------
    // 5. 源码面：豁免口不许散落
    // ------------------------------------------------------------------

    /**
     * 源码里点名的**常量字段名** → 表里的动作点（字段值）。
     *
     * 调用点断言要用它把「`StrictModeGuard.WHERE_X`」这段源码文字换成表里的那个字符串；
     * 与 [pinnedExemptions] 的一致性由 [豁免调用点与表一一对应] 末尾那条断言兜住 ——
     * 表里加一条而这两份清单少一条，都会红。
     */
    private val constantNameToWhere: Map<String, String> = linkedMapOf(
        "WHERE_PAUSE_TRAIL" to StrictModeGuard.WHERE_PAUSE_TRAIL,
        "WHERE_TRIM_TRAIL" to StrictModeGuard.WHERE_TRIM_TRAIL,
        "WHERE_FREEZE_TRAIL" to StrictModeGuard.WHERE_FREEZE_TRAIL,
        "WHERE_COLD_START" to StrictModeGuard.WHERE_COLD_START,
        "WHERE_LIFECYCLE_STATE" to StrictModeGuard.WHERE_LIFECYCLE_STATE,
        "WHERE_PREFS_COLD_READ" to StrictModeGuard.WHERE_PREFS_COLD_READ,
        "WHERE_CRASH_FORENSICS" to StrictModeGuard.WHERE_CRASH_FORENSICS,
    )

    /** 会开豁免口的三个文件：壳层（MainActivity）与首选项（UiPrefs 的首读）、崩溃取证（CrashForensics）。 */
    private val exemptionHostFiles = listOf("MainActivity.kt", "UiPrefs.kt", "CrashForensics.kt")

    /**
     * 豁免口与表一一对应：表里每一条都有调用点，调用点只许点名的常量，谁也不许多、谁也不许少。
     *
     * 判式跟着表走（不写死条数），但**四处生命周期状态落盘**是写死的：那一处少一个调用点
     * 就等于悄悄关掉一条边的取证（回前台 / 切后台 / onDestroy / 结束应用），这种数量只能钉死。
     */
    @Test
    fun `豁免调用点与表一一对应`() {
        val code = exemptionHostFiles.joinToString("\n") { codeLines(it) }
        val calls = Regex("EratwStrictMode\\.allowUiDiskIo\\(\\s*([A-Za-z_.]+)")
            .findAll(code).map { it.groupValues[1] }.toList()
        val named = calls.map { it.substringAfter("StrictModeGuard.") }
        assertTrue(
            "豁免口的入参必须写成 StrictModeGuard.WHERE_*（裸字符串与变量都读不出是哪一条）：$calls",
            calls.all { it.startsWith("StrictModeGuard.WHERE_") },
        )
        val unknown = named.filter { !constantNameToWhere.containsKey(it) }
        assertTrue("有豁免口点名的常量不在清单里（先补清单再谈进表）：$unknown", unknown.isEmpty())
        assertEquals(
            "被开过豁免口的动作点必须恰好是表里的全部（表里加一条却不开口 = 空转的白名单；开口却不在表里 = 越权）：$named",
            StrictModeGuard.uiDiskIoExemptions.keys,
            named.mapNotNull { constantNameToWhere[it] }.toSet(),
        )
        assertTrue(
            "豁免口总数（$calls）不许少于表大小（${StrictModeGuard.uiDiskIoExemptions.size}）",
            calls.size >= StrictModeGuard.uiDiskIoExemptions.size,
        )
        assertEquals(
            "生命周期状态落盘的豁免口必须五处齐全（回前台 / 切后台 / onDestroy / 结束应用 / 失败屏重启应用）",
            5,
            calls.count { it.endsWith("WHERE_LIFECYCLE_STATE") },
        )
        assertEquals(
            "两份清单（点名理由与常量对照）必须覆盖同一批表项",
            pinnedExemptions.keys,
            constantNameToWhere.values.toSet(),
        )
        assertTrue(
            "onPause 的 uiStage 段名要与豁免表同一个词，两套日志才能对得上",
            codeLines("MainActivity.kt").contains("uiStage(StrictModeGuard.WHERE_PAUSE_TRAIL)"),
        )
    }

    /**
     * 上一路清单里被裁成**乙**与**丙**的那两处，钉住落地形状：别被后人又塞回豁免表。
     *
     * - 乙（失败屏「复制报告」）：整份报告的读盘挪到后台线程、剪贴板写入与 Toast 回主线程，
     *   并且**没有**豁免口 —— 判据是「这一次点击的 UI 侧不做磁盘 IO」那行读数在源码里；
     * - 丙（失败屏「分享报告」）：不进表，替代哨兵是 UI 侧那行一次性耗时读数。
     */
    @Test
    fun `裁走的两条`() {
        val code = codeLines("MainActivity.kt")
        assertTrue("复制报告要读的是拆分后的后台口：$code", code.contains("ErrorReportCollector.readReportText(file)"))
        assertTrue("剪贴板写入要回主线程：$code", code.contains("ErrorReportCollector.putClipboardText("))
        assertTrue("复制报告这一发不许开豁免口", !code.contains("WHERE_REPORT_CLIPBOARD"))
        assertTrue(
            "乙 的 UI 侧读数要在（挪没挪成功都读得出来）",
            code.contains("这一次点击的 UI 侧不做磁盘 IO"),
        )
        assertTrue("丙 的替代哨兵要在：UI 侧耗时读数", code.contains("分享报告：UI 侧这一下"))
    }

    /**
     * 主线程闸收在豁免口**本体**（EratwStrictMode），调用方一律无条件调用。
     *
     * 窗口状态机（重入位与慢样本计数）按「只在主线程读写」实现 —— 在别的线程上开合
     * 会把那条边踩脏，代价是主线程从此永久静默放行。上一路（obs-uiio）因为
     * `EratwApplication.kt` 不归它，只在两个调用方各钉了一道 `Looper` 闸；fix-uiowiring
     * 车道把闸收进本体（非主线程 = 不开窗口、走本体的日志口径、动作照做），调用方再自己
     * 分支就变成**双重闸**：闸的口径一旦在两处各写一份，改一处忘一处就是静默放行。
     *
     * 三条钉死：
     * 1. 本体里有闸：`EratwApplication.kt` 的 `allowUiDiskIo` 用 `Looper.myLooper()` 对比主线程；
     * 2. `MainActivity.kt` 整个文件不再出现 `Looper` —— 崩溃档那发已经收成无条件调用，
     *    行为与旧 if/else 分支逐字等价（非主线程那一支原本就是直接跑 body）；
     * 3. `UiPrefs.kt` 的 `Looper` 读数只剩日志文案一个用途：豁免调用是**无条件**的一条
     *    （`val prefs = EratwStrictMode.allowUiDiskIo(...)`，不许再包 `if (onMain)`）。
     */
    @Test
    fun `主线程闸在豁免口本体，调用方不各自分支`() {
        val landing = codeLines("EratwApplication.kt")
        assertTrue("豁免口本体要有主线程闸：非主线程不开窗口", landing.contains("Looper.myLooper() != Looper.getMainLooper()"))
        assertTrue("本体闸的非主线程分支不许静默：要留一行日志口径", landing.contains("被非主线程调用：不开窗口"))
        val crash = codeLines("MainActivity.kt")
        assertFalse(
            "MainActivity 不许再自己按 Looper 分支决定开不开豁免窗（闸在 EratwStrictMode 本体）",
            crash.contains("Looper"),
        )
        val prefs = codeLines("UiPrefs.kt")
        assertTrue(
            "首读那发的豁免调用必须是无条件的一条（onMain 只服务日志文案）",
            prefs.contains("val prefs = EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_PREFS_COLD_READ) {"),
        )
    }

    /**
     * 设置页「复制诊断」也走拆分两步（fix-uiowiring 把最后一条 UI 线程组合口拆掉）：
     * 读盘在后台线程、剪贴板写入与 Toast 回主线程；组合口 `copyToClipboard` 已从
     * [ErrorReportCollector] 删除，任何源码再出现这个名字就是给按钮回调埋回「调用线程读盘」的雷。
     */
    @Test
    fun `设置页复制诊断是拆分形状，组合口已删`() {
        val code = codeLines("SettingsActivity.kt")
        assertTrue("复制诊断要读的是拆分后的后台口：$code", code.contains("ErrorReportCollector.readReportText(file)"))
        assertTrue("剪贴板写入要回主线程：$code", code.contains("ErrorReportCollector.putClipboardText("))
        val offenders = mainSourceFiles().filter { codeLinesOf(it).contains("copyToClipboard") }.map { it.name }
        assertTrue("copyToClipboard 组合口不许在 app/src/main 任何文件里复活（发现：$offenders）", offenders.isEmpty())
    }

    /** 直接碰 `android.os.StrictMode` 的只有落地层一个文件：判式与调用方都只能通过它。 */
    @Test
    fun `strictmode 的直接调用只有一处`() {
        // `(?<!Eratw)` 是必需的：`EratwStrictMode.allowUiDiskIo` 里也含 "StrictMode.allow"，
        // 那正是我们要求业务侧用的口，不是违例。
        val directCall = Regex("(?<!Eratw)StrictMode\\.(set|get|allow|note|enableDefaults)")
        val offenders = mainSourceFiles().filter { directCall.containsMatchIn(codeLinesOf(it)) }
            .map { it.name }
        assertEquals(
            "android.os.StrictMode 只许在 EratwApplication.kt 里被直接调用（其余一律走 StrictModeGuard 判式 + EratwStrictMode 的口）",
            listOf("EratwApplication.kt"),
            offenders.sorted(),
        )
    }

    /** `app/src/main` 里没有任何会杀进程/弹窗的罚则形状。 */
    @Test
    fun `没有任何会杀进程的罚则`() {
        val banned = listOf("penaltyDeath", "penaltyDialog", "penaltyFlashScreen", "penaltyDropBox", "penaltyListener")
        val hits = mutableListOf<String>()
        for (file in mainSourceFiles()) {
            for (line in codeLinesOf(file).lines()) {
                for (word in banned) {
                    if (line.contains(word)) hits += "${file.name}: ${line.trim()}"
                }
            }
        }
        assertTrue("发现了被禁的罚则（会把违规变成闪退或模态框）：\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    // ------------------------------------------------------------------
    // 取源码：工作目录随 gradle 版本/入口变，候选路径都试（同 StabilityL17ReportAndDebugTest）
    // ------------------------------------------------------------------

    private val moduleRootCandidates = listOf(
        File("."),
        File("../app"),
        File("app"),
    )

    /** 已存在的模块根（`app/`），三个候选按 gradle 入口不同各试一次。 */
    private fun moduleRoot(): File = moduleRootCandidates.firstOrNull { File(it, "src/main/kotlin").isDirectory }
        ?: error("找不到 app/src/main/kotlin（试了 ${moduleRootCandidates.joinToString { it.path }}）")

    private fun packageDir(): File = File(moduleRoot(), "src/main/kotlin/com/eratw/port/android")

    /** `app/src/main/kotlin` 整棵树里的 kt 文件（含 `dbg` 子包）：豁免口会不会藏在别处，要连子包一起看。 */
    private fun mainSourceFiles(): List<File> =
        File(moduleRoot(), "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun codeLines(name: String): String = codeLinesOf(File(packageDir(), name))

    private fun codeLinesOf(file: File): String {
        require(file.isFile) { "找不到 ${file.path}" }
        return file.readText(Charsets.UTF_8).lines().filterNot { line ->
            val t = line.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }.joinToString("\n")
    }
}
