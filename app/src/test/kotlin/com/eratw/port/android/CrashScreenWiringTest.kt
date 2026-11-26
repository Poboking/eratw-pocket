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

    @Test
    fun `崩溃屏版本号小字接线：正常屏与 fallback 都从文案表取并注入 BuildConfig`() {
        val code = codeLines("CrashScreenActivity.kt")
        assertEquals(
            "正常屏一处 + fallback 一处",
            2,
            code.countOccurrences("copy.versionLine(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)"),
        )
    }

    @Test
    fun `竖屏失败屏与横屏失败屏都带版本号小字`() {
        val portrait = codeLines("ShellUi.kt")
        assertTrue(
            "竖屏 FailureOverlay 要在按钮后渲染版本号小字",
            portrait.contains("failureCopy.versionLine(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)"),
        )
        val landscape = codeLines("MainActivity.kt")
        assertTrue(
            "横屏失败屏 snapshot 要追加一行版本号",
            landscape.contains("versionLine(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)"),
        )
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
    // 安装时机（RECON-STAB-C C1）
    // ------------------------------------------------------------------

    /**
     * 缺省未捕获异常处理器必须装在**进程入口**且先于其余装配（C1 前移）。
     *
     * 判据函数/装配函数本身对不代表接线对 —— 把 `CrashForensics.install(this)` 从
     * EratwApplication 挪回 MainActivity（= 撤掉前移）行为面看不出差别，冷启早段崩与
     * SettingsActivity 会话又回到零取证，所以钉在生产源码上：
     * ① 进程入口必须有进程级 install 调用；
     * ② 安装必须早于 EventJournal.begin（onCreate 里其余装配的第一步）——「最早一步」
     *    就是本条的全部；
     * ③ 安装必须在崩溃屏进程早退**之后**（`:crash` 进程不装，维持 C6 登记现状）。
     */
    @Test
    fun `崩溃处理器装在进程入口且早于其余装配`() {
        val app = codeLines("EratwApplication.kt")
        val gate = app.indexOf("if (isCrashScreenProcess())")
        val install = app.indexOf("CrashForensics.install(this)")
        val begin = app.indexOf("EventJournal.begin(this)")
        assertTrue("进程入口必须装崩溃处理器（找不到 = C1 前移被撤）", install >= 0)
        assertTrue(
            "安装必须早于事件日志装配（install=$install begin=$begin）：晚一步就漏掉那段窗口里的崩",
            install in 0 until begin,
        )
        assertTrue(
            "安装必须在崩溃屏进程早退之后（gate=$gate install=$install）：崩溃屏进程不装处理器",
            install > gate,
        )
    }

    /**
     * 处理器仍然**只包一层**（双重判定不许拆），壳层调用点保留幂等刷新。
     *
     * Application 与 MainActivity 两个入口都会进 installHandler：判定拆掉任何一半，
     * 每次壳层重建都会在崩溃路径上再套一轮采集 + 补发。壳层调用点撤掉则宿主读数口
     * （crashHostProvider）没人刷新，崩溃报告永远拿不到宿主。
     */
    @Test
    fun `处理器只包一层且壳层保留幂等刷新`() {
        val forensics = codeLines("CrashForensics.kt")
        assertTrue(
            "双重判定之一必须在：installedCrashHandler 指针比对",
            forensics.contains("current === installedCrashHandler"),
        )
        assertTrue(
            "双重判定之二必须在：crashHandlerWrapped CAS（防换掉默认处理器后的重复套娃）",
            forensics.contains("crashHandlerWrapped.compareAndSet(false, true)"),
        )
        assertTrue("进程级安装入口必须在（C1 前移的落点）", forensics.contains("internal fun install(context: Context)"))
        assertTrue(
            "壳层刷新入口必须在（宿主读数口随 Activity 换代）",
            forensics.contains("internal fun install(") && forensics.contains("hostProvider: () -> GameHost?"),
        )
        assertEquals(
            "setDefaultUncaughtExceptionHandler 只许一处（包一层 = 单一安装点）",
            1,
            forensics.countOccurrences("Thread.setDefaultUncaughtExceptionHandler(handler)"),
        )
        val main = codeLines("MainActivity.kt")
        assertTrue(
            "MainActivity 保留刷新调用（找不到 = 宿主读数口没人刷新，崩溃报告拿不到宿主）",
            main.contains("CrashForensics.install(this, hostProvider = { host })"),
        )
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
