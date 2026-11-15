package com.eratw.port.android

import com.eratw.port.core.diag.ErrorReport
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 死前心跳（`LastBreath`）的落盘/读回/成文形状（lane `bug-silent` 第一件，CONTRACT-BUGFIX-SERIES §2）。
 *
 * ## 钉的是什么
 *
 * 端点 2026-11-08/09 那 10 份「上次异常退出」全部无签名，共同缺口是「死前那一拍没有任何
 * 落盘读数」。本类钉住补上这条通道之后的五个可判据面：
 *
 * 1. **写→读回字段齐全**：堆三列、会话状态、图形池、事件、冻结现场一个不少；
 * 2. **原子替换**：旧文件（含被写坏的半截）不被读成新记录，也不会留下 `.tmp` 残骸；
 * 3. **超限截断**：超过 [LastBreath.MAX_BYTES] 时按行截断、仍可完整解析；
 * 4. **失败静默**：目录不可写（用一个同名普通文件占位）时只返回 false，绝不抛；
 * 5. **报告小节**：有留痕 ⇒ 各字段都上屏；无留痕 ⇒ 恰好一行说明，且插进
 *    `ErrorReport.extraSectionLines` 后既有小节（`[ERB 调用栈与 VM 现场]` / `[补充现场]`）
 *    的形状一个字不动。
 *
 * 另钉「上一轮」的认主口径：本进程自己写的（pid 相同）不许冒充「上次运行死前现场」，
 * 上一轮那份在本轮第一次写盘时被轮换成 `lastbreath.prev.txt`，两边互不覆盖。
 *
 * 纯 JVM（`java.io` + `java.nio.file`），断言签名随 app 模块用 JUnit4（消息在前）。
 */
class ExitForensicsLastBreathTest {

    private lateinit var dir: File
    private var enabledBefore = true

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("lastbreath-test").toFile()
        enabledBefore = LastBreath.enabled
        LastBreath.enabled = true
    }

    @After
    fun tearDown() {
        LastBreath.enabled = enabledBefore
        runCatching { dir.deleteRecursively() }
    }

    private fun snapshot(
        pid: Int = 4242,
        used: Long = 504L,
        max: Long = 512L,
        native: Long = 291L,
        state: String = "Ablup_Begin",
        note: String = "BeginFlow=Ablup_Begin/beginAblup→Ablup_Begin CALLED；无输入等待；VM线程=alive",
        gfxEntries: Int = 657,
        gfxRetained: Long = 167L,
        events: List<String> = listOf("{\"t\":6724000,\"type\":\"turn.end\",\"n\":\"12\"}"),
        stallGap: Long = 0L,
        stallLines: List<String> = emptyList(),
        wall: Long = 1_794_103_235_145L,
        at: Long = 6_724_559L,
    ) = LastBreath.Snapshot(
        pid = pid,
        writtenWallMs = wall,
        atMs = at,
        heapUsedMiB = used,
        heapMaxMiB = max,
        nativeAllocMiB = native,
        sessionState = state,
        sessionNote = note,
        gfxEntries = gfxEntries,
        gfxRetainedMiB = gfxRetained,
        eventLines = events,
        stallGapMs = stallGap,
        stallLines = stallLines,
    )

    // ---- ① 写 → 读回 ----

    @Test
    fun `写回读字段齐全`() {
        val snapshot = snapshot()
        assertTrue("落盘必须成功", LastBreath.write(dir, snapshot))
        val back = LastBreath.read(dir)
        assertNotNull("读回不许 null", back)
        requireNotNull(back)
        assertEquals(4242, back.pid)
        assertEquals(1_794_103_235_145L, back.writtenWallMs)
        assertEquals(6_724_559L, back.atMs)
        assertEquals(504L, back.heapUsedMiB)
        assertEquals(512L, back.heapMaxMiB)
        assertEquals(291L, back.nativeAllocMiB)
        assertEquals("Ablup_Begin", back.sessionState)
        assertTrue("会话说明要原样带回来：${back.sessionNote}", back.sessionNote.contains("VM线程=alive"))
        assertEquals(657, back.gfxEntries)
        assertEquals(167L, back.gfxRetainedMiB)
        assertEquals(1, back.eventLines.size)
        assertTrue("事件行原样带回来：${back.eventLines}", back.eventLines[0].contains("turn.end"))
        assertEquals(LastBreath.FORMAT_VERSION, back.version)
        assertEquals("98% 是报告里那句「堆贴顶」的判据", 98L, back.heapPercent())
    }

    @Test
    fun `冻结现场与备注一并带回来`() {
        val snapshot = snapshot(
            stallGap = 1_787L,
            stallLines = listOf("（下面是冻结哨兵在冻结瞬间抓的栈）", "ui#0 Arrays.copyOf:4275"),
        )
        assertTrue(LastBreath.write(dir, snapshot))
        val back = requireNotNull(LastBreath.read(dir))
        assertEquals(1_787L, back.stallGapMs)
        assertEquals(2, back.stallLines.size)
        assertTrue(back.stallLines[1].contains("Arrays.copyOf"))
    }

    // ---- ② 原子替换 ----

    @Test
    fun `旧文件被整体替换且不留临时残骸`() {
        // 先造一份「写坏的半截」：只有前两行、没有 pid。
        File(dir, LastBreath.FILE_NAME).writeText("v=1\nat=1\n", Charsets.UTF_8)
        assertNull("半截记录必须解析失败（不许当成现场）", LastBreath.read(dir))

        assertTrue(LastBreath.write(dir, snapshot(used = 500L)))
        val back = requireNotNull(LastBreath.read(dir))
        assertEquals(500L, back.heapUsedMiB)
        assertFalse(
            "临时文件必须已被 rename 掉（残留就是在报告里露出半截的前兆）",
            File(dir, LastBreath.TMP_FILE_NAME).exists(),
        )
        // 再写一次：读到的是第二份的完整读数，不是两份的拼合。
        assertTrue(LastBreath.write(dir, snapshot(used = 511L)))
        assertEquals(511L, requireNotNull(LastBreath.read(dir)).heapUsedMiB)
        assertFalse(File(dir, LastBreath.TMP_FILE_NAME).exists())
    }

    @Test
    fun `换行与超长值不会把记录切成两行`() {
        val snapshot = snapshot(state = "SYSTEM_TITLE\n含换行的状态", note = "x".repeat(400))
        assertTrue(LastBreath.write(dir, snapshot))
        val back = requireNotNull(LastBreath.read(dir))
        assertEquals("换行必须折成空格", "SYSTEM_TITLE 含换行的状态", back.sessionState)
        assertTrue(
            "超长值必须被截到 ${LastBreath.MAX_LINE_CHARS} 字符以内（否则整份记录会顶爆上限）",
            back.sessionNote.length <= LastBreath.MAX_LINE_CHARS,
        )
    }

    // ---- ③ 超限截断 ----

    @Test
    fun `超过体积上限时按行截断且仍可解析`() {
        val many = (1..400).map { "{\"t\":$it,\"type\":\"turn.end\",\"pad\":\"" + "x".repeat(80) + "\"}" }
        assertTrue(LastBreath.write(dir, snapshot(events = many)))
        val file = File(dir, LastBreath.FILE_NAME)
        val size = file.length()
        assertTrue("落盘体积必须 ≤ ${LastBreath.MAX_BYTES} 字节，实际 $size", size <= LastBreath.MAX_BYTES.toLong())
        val back = requireNotNull(LastBreath.read(dir))
        assertEquals("主键与被截断前的字段都在", 4242, back.pid)
        assertTrue("事件行必须被截掉一部分（400 条放不进 16 KiB）", back.eventLines.size < many.size)
        assertTrue(
            "末尾必须留一行截断说明，读的人才知道这份不是全量",
            back.notes.any { it.contains("已截断") },
        )
    }

    @Test
    fun `渲染超限时不在行中间剪断`() {
        val snapshot = snapshot(events = (1..300).map { "ev$it=" + "y".repeat(100) })
        val text = LastBreath.cap(LastBreath.render(snapshot))
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= LastBreath.MAX_BYTES)
        text.lines().filter { it.isNotBlank() }.forEach { line ->
            val at = line.indexOf('=')
            assertTrue("每一行都必须是完整的 key=value：$line", at > 0)
        }
        assertNotNull("截断之后仍必须是合法记录", LastBreath.parse(text))
    }

    // ---- ④ 失败静默 ----

    @Test
    fun `目录不可写时静默返回false`() {
        val blocker = File(dir, "not-a-dir")
        blocker.writeText("occupied", Charsets.UTF_8)
        // 用「同名普通文件」占位：mkdirs 必然失败，写盘路径必须整体静默。
        assertFalse("目录建不出来 ⇒ false", LastBreath.write(blocker, snapshot()))
        assertNull("读也不可能读到东西", LastBreath.read(blocker))
        assertTrue(
            "写盘失败不许把占位文件挪走/改写",
            blocker.readText(Charsets.UTF_8) == "occupied",
        )
    }

    @Test
    fun `缺失目录下的读取一律返回null`() {
        val missing = File(dir, "nope")
        assertNull(LastBreath.read(missing))
        assertNull(LastBreath.readText(File(missing, LastBreath.FILE_NAME)))
        assertNull(LastBreath.readPrevious(missing, 1))
    }

    // ---- ⑤ 报告小节 ----

    @Test
    fun `有留痕时各字段都上报告`() {
        assertTrue(LastBreath.write(dir, snapshot(stallGap = 1_787L, stallLines = listOf("ui#0 Arrays.copyOf:4275"))))
        val lines = LastBreath.reportLines(dir, currentPid = 999)
        assertEquals("首行必须是固定小节标题", LastBreath.SECTION_HEADER, lines[0])
        val body = lines.joinToString("\n")
        assertTrue("必须带 pid 与墙钟时间：$body", body.contains("pid=4242") && body.contains("2026-11-08"))
        assertTrue("必须带堆水位与百分比：$body", body.contains("used=504MiB") && body.contains("98%"))
        assertTrue("必须带 native 分配：$body", body.contains("native 分配=291MiB"))
        assertTrue("必须带会话状态：$body", body.contains("会话状态：Ablup_Begin"))
        assertTrue("必须带 BeginFlow 步：$body", body.contains("BeginFlow=Ablup_Begin/beginAblup"))
        assertTrue("必须带图形池两项：$body", body.contains("图形池：657 项") && body.contains("像素驻留 167MiB"))
        assertTrue("必须带最近事件：$body", body.contains("最近 HostEvent（1 条"))
        assertTrue("必须带冻结现场：$body", body.contains("冻结现场：UI 帧间隔 1787ms"))
        assertTrue("必须带冻结帧：$body", body.contains("ui#0 Arrays.copyOf:4275"))
    }

    @Test
    fun `没有冻结时如实写一行无而不是省掉这一项`() {
        assertTrue(LastBreath.write(dir, snapshot()))
        val body = LastBreath.reportLines(dir, currentPid = 999).joinToString("\n")
        assertTrue(body, body.contains("冻结现场：无（该进程没有超过 2000ms 的 UI 帧间隔）"))
    }

    @Test
    fun `无留痕时恰好一行说明`() {
        val lines = LastBreath.reportLines(dir, currentPid = 999)
        assertEquals("无留痕只许一行", 1, lines.size)
        assertEquals(LastBreath.NO_SNAPSHOT, lines[0])
        assertTrue("说明里要讲清为什么没有", lines[0].contains("无：上一轮没留下心跳文件"))
    }

    @Test
    fun `关掉心跳后报告节退化为一行说明且不再写盘`() {
        LastBreath.enabled = false
        assertEquals(listOf(LastBreath.NO_SNAPSHOT), LastBreath.reportLines(dir, 999))
        LastBreath.enabled = true
    }

    @Test
    fun `插进补充现场后既有小节形状不变`() {
        assertTrue(LastBreath.write(dir, snapshot()))
        val breath = LastBreath.reportLines(dir, currentPid = 999)
        val erbSection = listOf(
            ErbStack.ERB_MARK,
            "#0 CALLEE 行1（物理行2） pc=42 调用点 CALLER 行1（物理行2） pc=7",
            ErbStack.JAVA_MARK,
            "  at Thread.run:1",
        )
        val text = ErrorReport(
            kind = "上次异常退出",
            timestampText = "2026-11-09 00:00:00",
            errorType = "（无异常对象，见补充现场）",
            errorMessage = "",
            vmStackLines = erbSection,
            extraSectionLines = listOf("t=1ms used=504MiB max=512MiB") + breath,
        ).render()
        val lines = text.lines()
        // 既有两节一字不动
        assertTrue("核心报告类型行必须在：$text", text.contains("报告类型：上次异常退出"))
        assertTrue("ERB 段标题必须仍是固定串", lines.indexOf(ErbStack.ERB_MARK) >= 0)
        assertTrue("补充现场小节必须在", text.contains("[补充现场]"))
        assertTrue("新节标题必须在", text.contains(LastBreath.SECTION_HEADER))
        assertTrue("报告必须完整收尾（不许被新节截断）", text.trimEnd().endsWith("报告结束"))
        val erb = lines.indexOf(ErbStack.ERB_MARK)
        val java = lines.indexOf(ErbStack.JAVA_MARK)
        assertEquals("erb 段仍是一帧一行", 1, java - erb - 1)
    }

    // ---- 上一轮认主与轮换 ----

    @Test
    fun `上一轮那份在本轮首写时被轮换且两份都可读`() {
        // 上一轮（pid 1111）先落一份
        assertTrue(LastBreath.write(dir, snapshot(pid = 1111, used = 511L)))
        // 本轮（pid 2222）第一次写之前先轮换
        assertTrue("跨进程 ⇒ 必须轮换", LastBreath.rotatePreviousRun(dir, currentPid = 2222))
        assertTrue(
            "上一轮那份必须原样留在 prev 里",
            File(dir, LastBreath.PREV_FILE_NAME).isFile,
        )
        assertTrue(LastBreath.write(dir, snapshot(pid = 2222, used = 90L)))
        val previous = requireNotNull(LastBreath.readPrevious(dir, currentPid = 2222))
        assertEquals("readPrevious 只认别的进程写的", 1111, previous.pid)
        assertEquals(511L, previous.heapUsedMiB)
        assertFalse(
            "同一进程已经轮换过就不许再轮换（否则会把上一轮那份覆盖掉）",
            LastBreath.rotatePreviousRun(dir, currentPid = 2222),
        )
    }

    @Test
    fun `本进程自己写的那份不算上次运行`() {
        assertTrue(LastBreath.write(dir, snapshot(pid = 4242)))
        assertNull("本进程读数不许冒充死前现场", LastBreath.readPrevious(dir, currentPid = 4242))
        val lines = LastBreath.reportLines(dir, currentPid = 4242)
        assertEquals(listOf(LastBreath.NO_SNAPSHOT), lines)
    }

    @Test
    fun `冷启补报先于心跳时直接读到上一轮那份`() {
        // 冷启补报在 onCreate 里落，早于第一帧心跳 ⇒ 此时 prev 还不存在，必须能直接读 target。
        assertTrue(LastBreath.write(dir, snapshot(pid = 1111, used = 508L)))
        val previous = requireNotNull(LastBreath.readPrevious(dir, currentPid = 2222))
        assertEquals(1111, previous.pid)
        assertEquals(508L, previous.heapUsedMiB)
    }
    // ---- 接线哨兵（反向证明用：把任一处接线去掉，这一条就红）----

    @Test
    fun `心跳写入与报告节两处接线都在`() {
        val host = source(
            "src/main/kotlin/com/eratw/port/android/GameHost.kt",
            "../app/src/main/kotlin/com/eratw/port/android/GameHost.kt",
        )
        val diagnostics = source(
            "src/main/kotlin/com/eratw/port/android/RuntimeDiagnostics.kt",
            "../app/src/main/kotlin/com/eratw/port/android/RuntimeDiagnostics.kt",
        )
        assertTrue(
            "宿主帧回调必须接到诊断子系统（接线委托不许漂）",
            host.contains("diagnostics.sampleMemoryIfNeeded(nowMs)") &&
                host.contains("diagnostics.flushLastBreath()"),
        )
        assertTrue(
            "UI 帧回调里必须真的打心跳（去掉这一行，死前就不会再有任何落盘）",
            diagnostics.contains("lastBreathTick(nowMs)"),
        )
        assertTrue("心跳周期必须取既有常量", diagnostics.contains("LastBreath.INTERVAL_MS"))
        assertTrue("写盘必须走一次性 daemon 的单飞闸", diagnostics.contains("lastBreathWriting"))
        assertTrue(
            "冻结现场必须复用既有哨兵的进程级槽位（不另造第二个哨兵）",
            diagnostics.contains("MainActivity.currentStallSceneLines()"),
        )
        val governor = source(
            "src/main/kotlin/com/eratw/port/android/MemoryGovernor.kt",
            "../app/src/main/kotlin/com/eratw/port/android/MemoryGovernor.kt",
        )
        assertTrue(
            "后台堆水位档必须接在既有切后台入口上（宿主委托 + governor 内 reclaimByHeapWatermark）",
            governor.contains("reclaimByHeapWatermark()") && host.contains("memory.reclaimForBackgroundFootprint()"),
        )

        val collector = source(
            "src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt",
            "../app/src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt",
        )
        assertTrue("报告必须在补充现场里调用 lastBreathLines", collector.contains("lastBreathLines(context)"))
        assertTrue("小节正文由 LastBreath 统一给", collector.contains("LastBreath.reportLines"))
    }

    private fun source(vararg candidates: String): String {
        val file = candidates.map(::File).firstOrNull { it.isFile }
            ?: error("找不到源文件（试了 ${candidates.joinToString()}）")
        return file.readText(Charsets.UTF_8)
    }
}
