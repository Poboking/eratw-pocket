package com.eratw.port.android

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.eratw.port.core.diag.ErrorReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 错误报告的 Android 侧采集与出口（方案①「本地报告包 + 玩家手动分享」，2026-10-27）。
 *
 * ## 分工
 *
 * 报告的**内容与格式**在 core-vm（[ErrorReport]，纯 JVM、有定向单测）；本类只做
 * 壳侧四件事：
 * 1. **采集**：版本/设备/系统（[headerLines]）、构建标识（`BuildConfig.eratwBuildStamp`，
 *    gradle 注入）与产物指纹（随包 `package.report` 的 `program-sha256`，
 *    [programSha256FromAssets]）、ERB 调用栈与 VM 线程 Java 栈顶
 *    （[vmStackLines]，反射口径与 `DevToolsHost.callstack` 一致）、屏上尾部
 *    （`GameHost.debugConsoleLines`）；
 * 2. **落盘**：`filesDir/eratw-reports/eratw-report-<时间戳>.txt`，滚动保留
 *    [KEEP_REPORTS] 份；
 * 3. **复制**：整份报告进剪贴板（群聊里最省事的交法）；
 * 4. **分享**：经 [ReportFileProvider] 的 content URI 走系统分享面板。
 *
 * ## 铁律
 *
 * - **绝不抛**：报告生成本身若把失败路径炸了，就是用诊断代码制造第二场事故；
 *   任何异常只 Log 并返回 null。
 * - **无网络**：方案①的边界就是「玩家手动分发」，本类不发任何东西。
 * - **无变量取值**：见 [ErrorReport] 类头的隐私口径，采集时不得顺手加。
 */
object ErrorReportCollector {

    private const val TAG = "ErrorReport"

    /** 报告目录名（filesDir 下）。 */
    const val REPORT_DIR_NAME = "eratw-reports"

    /** 滚动保留的报告份数：失败反复触发也不挤爆私有存储。 */
    const val KEEP_REPORTS = 10

    /** 屏上尾部采集行数（截断由 [ErrorReport] 再兜一道）。 */
    private const val CONSOLE_TAIL_LINES = 60

    /** 随包自述报告的 assets 路径（写侧：`app/build.gradle.kts` 的 `stageGameAssets`）。 */
    private const val ASSET_PACKAGE_REPORT = "erbc.v1/package.report"

    /**
     * 从 APK assets 读随包 `package.report` 的 `program-sha256` 行（产物指纹）。
     *
     * 解包器（platform 的 `GameAssetInstaller`）只按固定名字取 program/meta/parts/manifest，
     * **不把 `package.report` 落盘**，所以这里只能从 `AssetManager` 直读。该文件是几 KB 的
     * 纯文本自述（不是 329 MB 的 program），逐行读到目标行就停。
     *
     * 铁律：**绝不抛**——assets 缺失、IO 失败、行不存在一律返回 null，
     * 由 core 渲染成「产物指纹：未知」；报告缺行总比报告写不出来强。
     */
    private fun programSha256FromAssets(context: Context): String? = runCatching {
        context.assets.open(ASSET_PACKAGE_REPORT).bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.startsWith("program-sha256 ")) {
                    return trimmed.removePrefix("program-sha256 ")
                        .trim()
                        .trim('"')
                        .takeIf { it.isNotEmpty() && it != "-" }
                }
                line = reader.readLine()
            }
            null
        }
    }.getOrNull()

    /**
     * 采集现场并落一份报告；返回报告文件，失败返回 null（原因见 Log）。
     *
     * 构建标识（`BuildConfig.eratwBuildStamp`）与产物指纹（[programSha256FromAssets]）
     * 在这里进报告：值由宿主给，节式与「未知」兜底口径在 core（[ErrorReport]）。
     *
     * @param context 任意 Android 上下文（取 filesDir 与包信息）
     * @param host 当前宿主；null（崩溃发生在装载前）时跳过会话侧现场
     * @param kind 报告类型（「脚本运行报错」「启动失败」「应用崩溃」…）
     * @param error 异常对象；null = 无异常对象的失败（状态机终局等）
     * @param extraLines 补充现场行（调用方自定读数）
     */
    fun collect(
        context: Context,
        host: GameHost?,
        kind: String,
        error: Throwable?,
        extraLines: List<String> = emptyList(),
    ): File? = runCatching {
        val now = System.currentTimeMillis()
        val report = ErrorReport(
            kind = kind,
            timestampText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now)),
            headerLines = headerLines(context),
            buildStamp = BuildConfig.eratwBuildStamp,
            programSha256 = programSha256FromAssets(context),
            errorType = error?.javaClass?.name ?: "（无异常对象，见补充现场）",
            errorMessage = error?.message ?: "",
            scriptLocation = ErrorReport.scriptLocationOf(error),
            vmStackLines = vmStackLines(host),
            consoleTailLines = host?.debugConsoleLines(CONSOLE_TAIL_LINES).orEmpty(),
            extraSectionLines = extraLines,
        )
        val dir = reportDir(context)
        if (!dir.isDirectory) dir.mkdirs()
        var file = File(dir, ErrorReport.fileNameAt(now, java.util.TimeZone.getDefault().toZoneId()))
        if (file.exists()) {
            // 同一秒内的第二份（罕见）：毫秒后缀避让，不覆盖前一份。
            file = File(dir, "eratw-report-" + now + ".txt")
        }
        file.writeText(report.render(), Charsets.UTF_8)
        prune(dir)
        Log.i(TAG, "错误报告已生成：${file.name}（${file.length()} B）")
        file
    }.getOrElse {
        Log.w(TAG, "错误报告生成失败（不影响失败路径本身）", it)
        null
    }

    /** 版本与设备小节。 */
    private fun headerLines(context: Context): List<String> {
        val version = runCatching {
            val pm = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pm.versionName} (versionCode ${pm.versionCode})"
        }.getOrDefault("（未知）")
        return listOf(
            "app 版本：$version",
            "设备：${Build.MANUFACTURER} ${Build.MODEL}",
            "系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        )
    }

    /**
     * ERB 函数调用栈（#0 最内帧）+ VM 线程 Java 栈顶。
     * 反射口径与 `DevToolsHost.callstack` 一致：`ProcessRuntime.vm` → `funcStack`/`depth`，
     * 帧表映射 `program.functions[i].name`；字段名变更时优雅降级成一行说明。
     */
    private fun vmStackLines(host: GameHost?): List<String> {
        if (host == null) return emptyList()
        val session = host.debugSession() ?: return emptyList()
        val frames = runCatching {
            val vf = com.eratw.port.core.proc.ProcessRuntime::class.java.getDeclaredField("vm")
            vf.isAccessible = true
            val vm = vf.get(session.runtime) ?: return emptyList()
            val fs = vm.javaClass.getDeclaredField("funcStack").apply { isAccessible = true }
                .get(vm) as IntArray
            val depth = vm.javaClass.getDeclaredField("depth").apply { isAccessible = true }
                .getInt(vm)
            val fns = session.program.functions
            (depth - 1 downTo 0).take(ErrorReport.STACK_MAX_LINES)
                .mapIndexed { k, i -> "#$k ${fns.getOrNull(fs[i])?.name ?: "?"}" }
        }.getOrElse {
            listOf("（ERB 调用栈读数不可用：${it.javaClass.simpleName}）")
        }
        val javaTop = host.debugVmThread()?.stackTrace
            ?.take(8)
            ?.map { "  at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            .orEmpty()
        return frames + listOf("--- java ---") + javaTop
    }

    /** 报告目录（[ReportFileProvider] 与本类共用同一个根）。 */
    fun reportDir(context: Context): File = File(context.filesDir, REPORT_DIR_NAME)

    /** 只保留最新 [keep] 份（文件名内嵌时间戳，字典序即时间序）。 */
    fun prune(dir: File, keep: Int = KEEP_REPORTS) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("eratw-report-") } ?: return
        if (files.size <= keep) return
        files.sortedBy { it.name }.take(files.size - keep).forEach { f ->
            runCatching { f.delete() }
                .onFailure { Log.w(TAG, "旧报告删除失败：${f.name}", it) }
        }
    }

    /** 整份报告进剪贴板；成功返回 true。 */
    fun copyToClipboard(context: Context, file: File): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(
            ClipData.newPlainText("eratw 错误报告", file.readText(Charsets.UTF_8)),
        )
        true
    }.getOrElse {
        Log.w(TAG, "报告复制到剪贴板失败", it)
        false
    }

    /**
     * 系统分享面板的 Intent（text/plain + content URI）。
     *
     * URI 由自研的 [ReportFileProvider] 提供（本工程不引 androidx.core，
     * FileProvider 在编译类路径上不可用）。
     */
    fun shareIntent(context: Context, file: File): Intent? = runCatching {
        val uri = Uri.Builder()
            .scheme(android.content.ContentResolver.SCHEME_CONTENT)
            .authority(ReportFileProvider.AUTHORITY)
            .appendPath(file.name)
            .build()
        Intent(Intent.ACTION_SEND).apply {
            type = ClipDescription.MIMETYPE_TEXT_PLAIN
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(
                Intent.EXTRA_TEXT,
                "eraTW 安卓移植版报错：见附件报告（eratw-reports/${file.name}）",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrElse {
        Log.w(TAG, "分享 Intent 构造失败", it)
        null
    }
}
