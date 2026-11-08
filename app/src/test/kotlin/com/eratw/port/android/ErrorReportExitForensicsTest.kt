package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExitForensics] 的定向单测：报告里「系统取证」那一节的判式与格式。
 *
 * ## 为什么这一节的判错代价特别高
 *
 * 玩家上报里有一类「上次异常退出，但报告里没有 throwable」（2026-10 收口的 14 条）。
 * lmkd 收进程 / 用户划掉 / 包更新 / native 崩（信号）/ Java OOM 这五种死，Java 侧的取证
 * 全是空的，唯一带判词的是系统记的那份退出记录。于是：
 * - **原因码表接错**（比如把 3 当成 CRASH）就把「系统收进程」判成「我们的异常没兜住」，
 *   修复方向整个带偏；常量值取自本机 compileSdk 36 的 `android.jar`（见 [ExitForensics] 类头），
 *   这里逐条钉住，防止谁照抄网上或旧版本的编号；
 * - **判读句写反**（把用户主动停止说成缺陷）会污染台账，让 14 条又变回一堆噪声。
 *
 * 纯 JVM、无 Android 依赖：读数以 [ProcessExitRow] 与标量参数进来，格式化器由调用方给，
 * 所以这里能整节断言（同 `RunTrailTest` 的做法）。
 */
class ErrorReportExitForensicsTest {

    private val now = 1_700_000_000_000L

    private fun row(
        reasonCode: Int = ExitForensics.REASON_LOW_MEMORY,
        status: Int = 0,
        importance: Int = 100,
        agoMs: Long = 5 * 60_000L,
        pssKb: Long = 900_000L,
        rssKb: Long = 1_200_000L,
        description: String? = null,
        pid: Int = 4321,
    ) = ProcessExitRow(
        timestampMs = now - agoMs,
        pid = pid,
        reasonCode = reasonCode,
        status = status,
        importance = importance,
        pssKb = pssKb,
        rssKb = rssKb,
        description = description,
    )

    private fun ts(ms: Long) = "T$ms"

    private fun lines(rows: List<ProcessExitRow>, pssKib: Long = 512_000L, rssKib: Long = 640_000L, apiLevel: Int = 34) =
        ExitForensics.lines(pssKib, rssKib, rows, now, apiLevel) { ms -> ts(ms) }

    // ---- 原因码表（出处：compileSdk 36 android.jar 的 ApplicationExitInfo）----

    @Test
    fun 原因码与重要度编号锁在系统常量上() {
        assertEquals(0, ExitForensics.REASON_UNKNOWN)
        assertEquals(1, ExitForensics.REASON_EXIT_SELF)
        assertEquals(2, ExitForensics.REASON_SIGNALED)
        assertEquals(3, ExitForensics.REASON_LOW_MEMORY)
        assertEquals(4, ExitForensics.REASON_CRASH)
        assertEquals(5, ExitForensics.REASON_CRASH_NATIVE)
        assertEquals(6, ExitForensics.REASON_ANR)
        assertEquals(7, ExitForensics.REASON_INITIALIZATION_FAILURE)
        assertEquals(8, ExitForensics.REASON_PERMISSION_CHANGE)
        assertEquals(9, ExitForensics.REASON_EXCESSIVE_RESOURCE_USAGE)
        assertEquals(10, ExitForensics.REASON_USER_REQUESTED)
        assertEquals(11, ExitForensics.REASON_USER_STOPPED)
        assertEquals(12, ExitForensics.REASON_DEPENDENCY_DIED)
        assertEquals(13, ExitForensics.REASON_OTHER)
        assertEquals(14, ExitForensics.REASON_FREEZER)
        assertEquals(15, ExitForensics.REASON_PACKAGE_STATE_CHANGE)
        assertEquals(16, ExitForensics.REASON_PACKAGE_UPDATED)
        assertEquals(30, ExitForensics.API_R)
    }

    @Test
    fun 原因名带码未知码不冒充() {
        assertEquals("LOW_MEMORY(3)", ExitForensics.reasonName(3))
        assertEquals("CRASH_NATIVE(5)", ExitForensics.reasonName(5))
        assertEquals("REASON#(99)", ExitForensics.reasonName(99))
    }

    @Test
    fun 重要度名能分出屏前与后台() {
        assertEquals("FOREGROUND(100)", ExitForensics.importanceName(100))
        assertEquals("VISIBLE(200)", ExitForensics.importanceName(200))
        assertEquals("BACKGROUND/EMPTY(400)", ExitForensics.importanceName(400))
        assertEquals("GONE(1000)", ExitForensics.importanceName(1000))
        assertEquals("IMPORTANCE#(777)", ExitForensics.importanceName(777))
    }

    // ---- 状态列：SDK 36 没有 subreason，用信号/退出码承担细分 ----

    @Test
    fun 信号型原因给信号名其余给原始码() {
        val killed = ExitForensics.statusText(ExitForensics.REASON_SIGNALED, 9)
        assertTrue(killed, killed.contains("SIGKILL"))
        assertTrue(ExitForensics.statusText(2, 11).contains("SIGSEGV"))
        // CRASH_NATIVE 的 status 也是信号号，同一张名表。
        assertTrue(ExitForensics.statusText(5, 11).contains("SIGSEGV"))
        assertEquals("code=0", ExitForensics.statusText(ExitForensics.REASON_EXIT_SELF, 0))
        // 没名的信号如实给号，不硬编成某个具体信号。
        assertTrue(ExitForensics.statusText(2, 43).contains("signal=43"))
    }

    // ---- 判读：每类死都要落到一个方向，认不出就说不认得 ----

    @Test
    fun 良性退出只收系统明确认领的几种() {
        listOf(
            ExitForensics.REASON_USER_REQUESTED,
            ExitForensics.REASON_USER_STOPPED,
            ExitForensics.REASON_PACKAGE_STATE_CHANGE,
            ExitForensics.REASON_PACKAGE_UPDATED,
            ExitForensics.REASON_PERMISSION_CHANGE,
        ).forEach {
            assertTrue("原因码 $it 应判良性（不落「上次异常退出」）", ExitForensics.isBenignExit(row(it)))
        }
        listOf(
            ExitForensics.REASON_LOW_MEMORY,
            ExitForensics.REASON_SIGNALED,
            ExitForensics.REASON_CRASH,
            ExitForensics.REASON_CRASH_NATIVE,
            ExitForensics.REASON_ANR,
            ExitForensics.REASON_UNKNOWN,
            // 这两码**只在备注写明管控判词时**才良性；没有判词就是现场（见下面两例）。
            ExitForensics.REASON_OTHER,
            ExitForensics.REASON_EXIT_SELF,
        ).forEach {
            assertFalse("原因码 $it 是现场，不许判良性", ExitForensics.isBenignExit(row(it)))
        }
    }

    /**
     * ROM 的管控回收在系统那边只肯写「其它 / 自行退出」，判词全在备注原文里。
     *
     * 五条形制逐字取自 2026-11-04 的内部收件箱样本（样本未随公开仓，617 份的
     * 500 份切片）的 `退出#0` 行；次数按该形状在切片里出现的**总次数**（跨报告类型）数，
     * 与 `ExitForensics.isOemManagedStop` 的出处表同源：
     * - `OTHER(13)` + `[UNKNOWN] stop com.eratw.port.android due to o-stop(40)`：18 次
     *   （HUAWEI NAM-AL00 / 荣耀；重要度 FOREGROUND(100) 与 BACKGROUND/EMPTY(400) 都有 ⇒
     *   「屏前死的」这一维不能进判据，不然只盖住一半）；
     * - `OTHER(13)` + `[UNKNOWN] o-kill(109)` / `o-kill(32)`：3 次；
     * - `OTHER(13)` + `PRESTART WITHOUT START ACTIVITY`：38 次（ROM 预热了进程、一个界面
     *   都没起就收回；重要度恒 BACKGROUND/EMPTY(400)）；
     * - `OTHER(13)` + `stop com.eratw.port.android due to from pid 1454|40|F|J:50`：
     *   2 次，OPPO PHJ110（ColorOS 的裁决串，竖线后是 adj/前后台/J 分）；
     * - `EXIT_SELF(1)` + `stop com.eratw.port.android due to SPEG`：1 次（荣耀的功耗治理
     *   被系统记成「自行退出」——这一码不能整码判良性，理由见下面那条「不许洗白」）。
     *
     * 落在「上次异常退出」这一类里的是其中 **14 份**：旧判据只看原因码，这 14 份全部漏在外面
     * ⇒ 每一次冷启多一份报告 + 弹一张不该弹的卡（该类共 182 份，退出行停在 OTHER(13) 的
     * 13 份、停在 EXIT_SELF(1) 的 18 份）。
     */
    @Test
    fun 备注写明是ROM管控时按非缺陷处理() {
        val oemStop = listOf(
            "[UNKNOWN] stop com.eratw.port.android due to o-stop(40)",
            "[UNKNOWN] o-kill(109)",
            "[UNKNOWN] o-kill(32)",
            "PRESTART WITHOUT START ACTIVITY",
            "stop com.eratw.port.android due to from pid 1454|40|F|J:50",
        )
        oemStop.forEach {
            assertTrue(
                "OTHER(13) + 备注「$it」是 ROM 收进程，不该落「上次异常退出」",
                ExitForensics.isBenignExit(row(reasonCode = ExitForensics.REASON_OTHER, description = it)),
            )
        }
        assertTrue(
            "EXIT_SELF(1) 也可能装着 ROM 的治理判词",
            ExitForensics.isBenignExit(
                row(reasonCode = ExitForensics.REASON_EXIT_SELF, description = "stop com.eratw.port.android due to SPEG")
            ),
        )
        // 判读句要跟着改口，否则一份**别的**报告（崩溃/脚本报错）里出现这一行时，
        // 「系统标为其它，未给细分」与备注原文自相矛盾。
        val verdict = ExitForensics.verdict(
            row(reasonCode = ExitForensics.REASON_OTHER, description = "[UNKNOWN] stop com.eratw.port.android due to o-stop(40)")
        )
        assertTrue(verdict, verdict.contains("非缺陷"))
        assertTrue(verdict, verdict.contains("管控"))
    }

    /**
     * 判词只往「翻成良性」这一方向用，**不许**反过来把真现场洗白。
     *
     * 三条钉：①没有备注（或备注是「（无）」）的 OTHER(13) / EXIT_SELF(1) 继续照报；
     * ②不带裁决竖线的 `due to from pid 322`（收件箱里那一形全在已盖住的 USER_REQUESTED(10)
     * 名下）不算管控判词，认不出就说认不出；③ LOW_MEMORY / CRASH / ANR 这些**有明确判词**的
     * 码，就算备注里混进一个管控串也不许被备注翻掉——那是系统给死因下的结论，备注只是补充。
     */
    @Test
    fun 认不出的备注与有判词的原因码都不许洗白() {
        assertFalse(ExitForensics.isBenignExit(row(reasonCode = ExitForensics.REASON_OTHER, description = null)))
        assertFalse(
            "备注空占位不算判词",
            ExitForensics.isBenignExit(row(reasonCode = ExitForensics.REASON_OTHER, description = "（无）")),
        )
        assertFalse(
            "自行退出（没有判词）仍是现场：谁调的 exit 要人看轨迹",
            ExitForensics.isBenignExit(row(reasonCode = ExitForensics.REASON_EXIT_SELF, description = "（无）")),
        )
        assertFalse(
            "没有裁决竖线的 from pid 说不清是谁的意思，继续报",
            ExitForensics.isBenignExit(
                row(reasonCode = ExitForensics.REASON_OTHER, description = "stop com.eratw.port.android due to from pid 322")
            ),
        )
        assertFalse(
            "备注不许翻掉系统给死因下的结论",
            ExitForensics.isBenignExit(
                row(reasonCode = ExitForensics.REASON_LOW_MEMORY, description = "[UNKNOWN] o-kill(109)")
            ),
        )
        assertFalse(
            ExitForensics.isBenignExit(row(reasonCode = ExitForensics.REASON_CRASH, description = "crash"))
        )
        assertFalse(
            ExitForensics.isBenignExit(row(reasonCode = ExitForensics.REASON_SIGNALED, status = 9))
        )
    }

    @Test
    fun 判读把系统收进程与我们的异常分开() {
        assertTrue(ExitForensics.verdict(row(ExitForensics.REASON_LOW_MEMORY)).contains("lmkd"))
        assertTrue(ExitForensics.verdict(row(ExitForensics.REASON_CRASH)).contains("没有 throwable"))
        assertTrue(ExitForensics.verdict(row(ExitForensics.REASON_CRASH_NATIVE)).contains("native"))
        assertTrue(ExitForensics.verdict(row(ExitForensics.REASON_USER_REQUESTED)).contains("非缺陷"))
        assertTrue(ExitForensics.verdict(row(ExitForensics.REASON_PACKAGE_UPDATED)).contains("非缺陷"))
        assertTrue(ExitForensics.verdict(row(ExitForensics.REASON_SIGNALED, status = 7)).contains("SIGBUS"))
    }

    @Test
    fun 认不出的原因码只按原始读数记录() {
        val text = ExitForensics.verdict(row(reasonCode = 99))
        assertTrue(text, text.contains("99"))
        assertFalse("不许把不认识的码硬解释成某类死", text.contains("lmkd"))
    }

    /**
     * ANR 那句不许替读者判「不是内存」。
     *
     * 2026-11-02 OPPO PERM00 那份报告就是这一句把方向带反的：`原因=ANR(6)` 同屏的轨迹是
     * `used=511MiB max=512MiB`（99%）、`turn 2930ms`、`trim=runningCritical freed=0MiB`
     * —— 堆贴顶时 ART 一遍遍冻结全部线程，主线程当然「没让出」。系统的判词只说得出
     * 「主线程迟迟没让出」这一件事，是哪一种要人看轨迹，所以这一句改成给判据、不给结论。
     */
    @Test
    fun ANR判读把方向交给轨迹而不是断言不是内存() {
        val text = ExitForensics.verdict(row(ExitForensics.REASON_ANR))
        assertFalse("ANR 不能断言「不是内存」：GC 抖动正是主线程不让出的常见成因", text.contains("不是内存"))
        assertTrue(text, text.contains("主线程"))
        assertTrue("必须指出用哪一列分开两种成因", text.contains("轨迹"))
        assertTrue("堆贴顶那一支要给方向", text.contains("降常驻"))
    }

    // ---- 整节格式 ----

    @Test
    fun 足迹行给PSS与RSS且不重复Java堆三列() {
        val first = lines(emptyList()).first()
        assertTrue(first, first.contains("PSS=500MiB"))
        assertTrue(first, first.contains("RSS=625MiB"))
        assertTrue("Java 堆三列在装载埋点/运行轨迹里已有，这里只做说明", first.contains("不在此重复"))
        assertFalse("不许把 maxMemory 那三列再抄一遍", first.contains("max="))
    }

    @Test
    fun 读不到的标量渲染成未知而不是负数() {
        val text = lines(emptyList(), pssKib = ExitForensics.UNKNOWN, rssKib = ExitForensics.UNKNOWN).first()
        assertTrue(text, text.contains("PSS=${ExitForensics.UNKNOWN_TEXT}"))
        assertTrue(text, text.contains("RSS=${ExitForensics.UNKNOWN_TEXT}"))
        assertFalse(text, text.contains("-1"))
    }

    @Test
    fun 一条退出记录一行且字段齐全备注被截断() {
        val longDesc = "x".repeat(ExitForensics.DESCRIPTION_MAX_CHARS + 500)
        val out = lines(listOf(row(reasonCode = 3, importance = 100, description = longDesc)))
        val exitLine = out[1]
        assertTrue(exitLine, exitLine.startsWith("退出#0 T${now - 5 * 60_000L}"))
        assertTrue(exitLine, exitLine.contains("5分钟前"))
        assertTrue(exitLine, exitLine.contains("pid=4321"))
        assertTrue(exitLine, exitLine.contains("原因=LOW_MEMORY(3)"))
        assertTrue(exitLine, exitLine.contains("重要度=FOREGROUND(100)"))
        assertTrue(exitLine, exitLine.contains("当时PSS=878MiB"))
        assertTrue(exitLine, exitLine.contains("当时RSS=1171MiB"))
        assertTrue(exitLine, exitLine.contains("…（截断）"))
        assertTrue("整行不许把 1200 个字符的备注带进来", exitLine.length < 600)
        assertEquals("足迹 + 1 条记录 + 判读", 3, out.size)
    }

    @Test
    fun 记录按时间降序且最多三条() {
        val rows = listOf(
            row(agoMs = 1_000L, pid = 1),
            row(agoMs = 9_000_000L, pid = 2),
            row(agoMs = 500_000L, pid = 3),
            row(agoMs = 4_000L, pid = 4),
        )
        val out = lines(rows)
        assertEquals(ExitForensics.MAX_EXIT_ROWS, out.count { it.startsWith("退出#") })
        assertTrue(out[1], out[1].contains("pid=1"))
        assertTrue(out[2], out[2].contains("pid=4"))
        assertTrue(out[3], out[3].contains("pid=3"))
    }

    @Test
    fun 没有记录时按API档如实说明() {
        val old = lines(emptyList(), apiLevel = 26)
        assertTrue(old[1], old[1].contains("接口不可用"))
        assertTrue(old[1], old[1].contains("API 26"))
        val modern = lines(emptyList(), apiLevel = 34)
        assertTrue(modern[1], modern[1].contains("没给出本包条目"))
        assertFalse("没有记录就不许凭空判读", modern.any { it.startsWith("判读：") })
    }

    @Test
    fun 有时钟偏差时不写出负数年龄() {
        val out = lines(listOf(row(agoMs = -10_000L)))
        assertTrue(out[1], out[1].contains("时间不可靠"))
        assertFalse(out[1], out[1].contains("-1分钟"))
    }

    // ---- statm 解析 ----

    @Test
    fun statm第二列是常驻页乘页大小得RSS() {
        // 真机一行形如 `size resident shared text lib data dt`（单位：页）；
        // 常驻 500,000 页 × 4 KiB/页 = 2,000,000 KiB。
        assertEquals(2_000_000L, ExitForensics.rssKibFromStatm("262144 500000 120000 4000 0 200000 0", 4L))
    }

    @Test
    fun statm残缺或页大小非法一律当读不到() {
        assertEquals(ExitForensics.UNKNOWN, ExitForensics.rssKibFromStatm(null, 4L))
        assertEquals(ExitForensics.UNKNOWN, ExitForensics.rssKibFromStatm("", 4L))
        assertEquals(ExitForensics.UNKNOWN, ExitForensics.rssKibFromStatm("262144", 4L))
        assertEquals(ExitForensics.UNKNOWN, ExitForensics.rssKibFromStatm("262144 abc", 4L))
        assertEquals(ExitForensics.UNKNOWN, ExitForensics.rssKibFromStatm("262144 100", 0L))
    }

    @Test
    fun 年龄文本按量级换单位() {
        assertEquals("不足1分钟", ExitForensics.ageText(30_000L))
        assertEquals("59分钟前", ExitForensics.ageText(59 * 60_000L))
        assertEquals("23小时前", ExitForensics.ageText(23 * 3_600_000L))
        assertEquals("2天前", ExitForensics.ageText(2 * 86_400_000L + 7L))
    }

    @Test
    fun 空备注渲染成无而不是空尾巴() {
        assertEquals("（无）", ExitForensics.clampDescription(null))
        assertEquals("（无）", ExitForensics.clampDescription("  \n  "))
        assertEquals("a b", ExitForensics.clampDescription("a\nb"))
    }
}
