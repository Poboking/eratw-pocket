package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C6（wave/eng-crash）崩溃可读的**源码面哨兵**：视图层行为在 JVM 上跑不起来
 * （app 模块无 Robolectric，`CanvasConsoleView` 那一族「JVM 起不了 View」的坑已在案），
 * 所以这里钉住三类可静态判定、错了就白做的事实：
 *
 * 1. **manifest**：崩溃屏在独立进程（`:crash`）、不导出、不进最近任务、不用应用主题；
 * 2. **处理器接线**：取证四步 → 崩溃屏（runCatching，三种 flag 齐）→ 交回 previous 的**顺序**，
 *    以及五个 extras 键在生产端与消费端一一对应；
 * 3. **标记纪律**：只在崩溃处理器里写、只在启动路径里读/清，且「进入主循环」那一处有清点。
 *
 * 行为层（独立进程真的弹起来、两颗按钮的实际跳转、系统崩溃对话框与崩溃屏的层叠关系）
 * 无设备未覆盖 —— 见交付报告 TODO(verify)。
 */
class CrashScreenWiringTest {

    // ------------------------------------------------------------------
    // manifest
    // ------------------------------------------------------------------

    @Test
    fun `崩溃屏在独立进程且不导出不进最近任务`() {
        val manifest = readMain("AndroidManifest.xml")
        val block = Regex("(?s)<activity\\s+android:name=\"\\.CrashScreenActivity\".*?/>")
            .find(manifest)?.value
            ?: error("manifest 里找不到 CrashScreenActivity 条目")
        assertTrue("必须独立进程（崩的就是 UI 线程时原进程画不出下一帧）：$block", block.contains("android:process=\":crash\""))
        assertTrue("只接本应用的显式意图：$block", block.contains("android:exported=\"false\""))
        assertTrue("这一屏不该留在最近任务：$block", block.contains("android:excludeFromRecents=\"true\""))
        assertTrue(
            "主题必须直接用平台主题，不依赖本应用的主题资源：$block",
            block.contains("android:theme=\"@android:style/Theme.Material.NoActionBar\""),
        )
        assertFalse("不许用应用主题（崩溃场景下少解析一层资源）：$block", block.contains("@style/Theme.Eratw"))
    }

    // ------------------------------------------------------------------
    // 处理器接线
    // ------------------------------------------------------------------

    @Test
    fun `取证之后拉起崩溃屏再交回旧处理器`() {
        val code = codeLines("CrashForensics.kt")
        val collect = code.indexOf("collectCrashForensics(context, thread, e)")
        val surface = code.indexOf("surfaceCrashScreen(context, thread, e, reported)")
        val handover = code.indexOf("previous?.uncaughtException(thread, e)")
        assertTrue("四步取证必须还在（collect=$collect）", collect >= 0)
        assertTrue("崩溃屏必须追加在取证之后（surface=$surface）", surface > collect)
        assertTrue("崩溃屏必须在交回旧处理器之前（handover=$handover）", handover > surface)
        assertTrue("拉起这一步必须整段 runCatching：runCatching { surfaceCrashScreen(", code.contains("runCatching { surfaceCrashScreen("))
    }

    @Test
    fun `拉起用的是新任务清任务无动画三种 flag`() {
        val code = codeLines("CrashForensics.kt")
        assertTrue(code, code.contains("Intent.FLAG_ACTIVITY_NEW_TASK or"))
        assertTrue(code, code.contains("Intent.FLAG_ACTIVITY_CLEAR_TASK"))
        assertTrue(code, code.contains("Intent.FLAG_ACTIVITY_NO_ANIMATION"))
        assertEquals(
            "只许一处 startActivity（崩溃处理器拉起崩溃屏）",
            1,
            code.countOccurrences("context.startActivity("),
        )
    }

    @Test
    fun `五个 extras 键生产端与消费端一一对应`() {
        val screen = codeLines("CrashScreenActivity.kt")
        val forensics = codeLines("CrashForensics.kt")
        val declared = Regex("const val (EXTRA_[A-Z_]+): String")
            .findAll(screen).map { it.groupValues[1] }.toList()
        assertEquals("extras 键数量变了就要两端一起改：$declared", 5, declared.size)
        for (name in declared) {
            assertTrue("崩溃处理器没有传 $name", forensics.contains("CrashScreenActivity.$name"))
            assertTrue(
                "崩溃屏没有读 $name（声明一处 + 读取一处）",
                screen.countOccurrences("($name") >= 1,
            )
        }
    }

    @Test
    fun `崩溃屏不碰 MainActivity 状态也不用 Compose`() {
        val code = codeLines("CrashScreenActivity.kt")
        assertFalse("不许引 Compose 树：$code", code.contains("androidx.compose"))
        assertFalse("不许碰宿主会话状态：$code", code.contains("GameHost"))
        assertFalse("不许读壳层状态：$code", code.contains("shell."))
        for (widget in listOf("ScrollView", "TextView", "Button")) {
            assertTrue("界面用纯平台控件，缺 $widget", code.contains(widget))
        }
        assertTrue("两颗按钮都从文案表取字", code.contains("copy.restartButton") && code.contains("copy.closeButton"))
        assertTrue("摘要只从 intent 的五个标量来", code.contains("summaryOf(intent)"))
    }

    // ------------------------------------------------------------------
    // 标记纪律
    // ------------------------------------------------------------------

    @Test
    fun `标记只在崩溃处理器里写`() {
        val writers = mainSources().filter { codeLines(it).contains("CrashSummaryMarker.write(") }
        assertEquals("写标记只许在崩溃处理器里（别处写 = 正常启动也会弹「上次崩溃」）", listOf("CrashForensics.kt"), writers)
        assertEquals(1, codeLines("CrashForensics.kt").countOccurrences("CrashSummaryMarker.write("))
    }

    @Test
    fun `启动路径读标记并给出可关闭横幅`() {
        val code = codeLines("MainActivity.kt")
        assertTrue("读标记：", code.contains("CrashSummaryMarker.read(ErrorReportCollector.reportDir("))
        assertTrue("消费点：", code.contains("showPendingCrashBannerIfAny()"))
        assertTrue("横幅走既有壳层提示面：", code.contains("EratwShellDialog.show("))
        assertTrue("横幅正文与按钮都从文案表取字：", code.contains("CrashSummaryText.bannerBody(") && code.contains("copy.bannerAck"))
        assertTrue("「知道了」清标记：", code.contains("onPositive = { clearCrashSummaryMarker() }"))
    }

    @Test
    fun `进入主循环后清标记且清点齐全`() {
        val code = codeLines("MainActivity.kt")
        assertEquals(
            "清点：定义 + 知道了 + 点卡外 + 首帧进入主循环",
            4,
            code.countOccurrences("clearCrashSummaryMarker()"),
        )
        val firstFrame = code.indexOf("consoleView.onFirstContentFrame = {")
        val lastClear = code.lastIndexOf("clearCrashSummaryMarker()")
        assertTrue("首帧回调必须在（$firstFrame）", firstFrame >= 0)
        assertTrue("首帧回调里必须清标记（lastClear=$lastClear）", lastClear > firstFrame)
    }

    @Test
    fun `crash 进程在 Application 早退`() {
        val code = codeLines("EratwApplication.kt")
        val gate = code.indexOf("if (isCrashScreenProcess())")
        val tier = code.indexOf("MemoryTier.SDK_INT_PROPERTY")
        assertTrue("crash 进程早退必须在（gate=$gate）", gate >= 0)
        assertTrue("早退必须在内存档位装配之前（tier=$tier）", tier > gate)
        assertTrue("早退必须真的 return", code.contains("if (isCrashScreenProcess()) {\n            runCatching"))
    }

    // ------------------------------------------------------------------
    // 读源码
    // ------------------------------------------------------------------

    private val moduleRootCandidates = listOf(File("."), File("../app"), File("app"))

    private fun moduleRoot(): File =
        moduleRootCandidates.firstOrNull { File(it, "src/main/kotlin").isDirectory }
            ?: error("找不到 app/src/main/kotlin（试了 ${moduleRootCandidates.joinToString { it.path }}）")

    /** 主源目录下的全部 Kotlin 文件（只用于「写点唯一」这类全量扫描）。 */
    private fun mainSources(): List<String> =
        File(moduleRoot(), "src/main/kotlin/com/eratw/port/android")
            .listFiles { f -> f.isFile && f.name.endsWith(".kt") }
            ?.map { it.name }
            ?.sorted()
            ?: error("读不到 app/src/main/kotlin/com/eratw/port/android")

    /** 任意主源文件（含 manifest）的原文。 */
    private fun readMain(relative: String): String =
        File(moduleRoot(), "src/main/$relative").readText(Charsets.UTF_8)

    /** app 主源文件里某个 kotlin 文件的**代码行**（丢掉注释与 KDoc）：钉接线，不钉文档措辞。 */
    private fun codeLines(name: String): String {
        val file = File(moduleRoot(), "src/main/kotlin/com/eratw/port/android/$name")
        return file.readText(Charsets.UTF_8)
            .lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/" + "*")
            }
            .joinToString("\n")
    }

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
