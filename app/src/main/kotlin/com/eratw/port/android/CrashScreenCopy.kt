package com.eratw.port.android

import java.io.File
import java.util.Date
import java.util.Locale

/**
 * 崩溃摘要的数据面：崩溃处理器采到的四样读数（异常类型、时间、线程、取证与报告）。
 *
 * 刻意只留**玩家读得懂的那一层**：异常类型与线程名是原样字符串，资源取证行是宿主已经
 * 折好的一行（[com.eratw.port.core.host.ResourceContents.censusLine] 的产物），报告名是
 * 落盘文件名。堆栈全文一律不进这里 —— 那一份在报告正文里，崩溃屏只给摘要。
 *
 * @property exceptionType 异常类简名（`e.javaClass.simpleName`；空则退回全名）
 * @property atMillis 崩溃时刻（epoch 毫秒；0 = 不知道）
 * @property threadName 崩溃线程名（`main` / `eratw-vm` / 其他）
 * @property censusLine 资源/引擎取证行；null = 那一刻取不到（宿主还没建或已关）
 * @property reportName 崩溃报告文件名；null = 报告没落成（[CrashForensics] 的 reported 判据）
 */
internal data class CrashSummary(
    val exceptionType: String,
    val atMillis: Long,
    val threadName: String,
    val censusLine: String?,
    val reportName: String?,
)

/**
 * 崩溃摘要 / 崩溃屏 / 下次启动兜底横幅的玩家面文案表（三语，C6 收口）。
 *
 * ## 为什么单独一张表而不是并进 [ShellFailureCopy]
 *
 * 那张表是「开局 → 失败屏」链路的句子，取语言的口径是**壳层现取**
 * （`ShellFailureCopy.of(UiPrefs.settingsLanguage(context))`）；崩溃屏跑在 `:crash`
 * 独立进程里，取值口是崩溃屏自己的 `onCreate`，与主进程壳层没有任何共享状态（设计约束：
 * 崩溃线程可能就是 UI 线程，原进程画不出下一帧，所以崩溃屏不许碰 `MainActivity` 的任何
 * 状态）。两张表共用一个 [SettingsLanguage] 枚举与同一副 `fill` 语义，但生命周期分开。
 *
 * ## 三条硬规矩（照 [ShellFailureCopy] / [UpdateCopy]）
 *
 * 1. **取语言只有一条路**：[of] 只吃 [SettingsLanguage]；
 * 2. **玩家可见文案零内联**：崩溃屏与兜底横幅的每一句都从本表取，异常原话、线程名、
 *    资源取证行只当 `fill` 实参（原话不翻）；
 * 3. **值里不许出现等号**：形状闸按 `toString` 的等号计数对账（[CopyShapeGate]），
 *    带等号会让条目数校验失真。
 *
 * 原版 PC 没有对应物（`reference/emuera-cs` 全仓没有未捕获异常处理器注册），这一屏是
 * 移植侧新增体验，不是语义对齐项（ADR-005 管不到它）—— 与 `RunWatch.surfaceCrashNotice`
 * 的裁定同源。
 *
 * @property lang 这一组对应的设置页语言
 */
internal data class CrashScreenCopy(
    val lang: SettingsLanguage,

    /** 崩溃屏大标题。 */
    val title: String,
    /** 「发生了什么」行：{0} 异常类型、{1} 一句人间话。 */
    val whatHappenedFormat: String,
    /** 内存不够那一族的人间话。 */
    val causeOutOfMemory: String,
    /** 嵌套过深那一族的人间话。 */
    val causeStackOverflow: String,
    /** 脚本自身出错那一族的人间话。 */
    val causeScript: String,
    /** 认不出类型时的兜底人间话。 */
    val causeUnknown: String,

    /** 「何时/哪一步」行：{0} 时间、{1} 线程的人话标签。 */
    val whereFormat: String,
    /** 时间读不出来时的占位词（不让槽位留空）。 */
    val timeUnknown: String,
    /** 崩在界面线程。 */
    val threadMain: String,
    /** 崩在脚本线程。 */
    val threadScript: String,
    /** 其余线程：{0} 线程名。 */
    val threadOtherFormat: String,
    /** 线程名都拿不到时的占位词。 */
    val threadUnknown: String,

    /** 资源/引擎取证行：{0} 已有的一行读数或 [censusUnavailable]。 */
    val censusFormat: String,
    /** 取证行取不到时的短占位词。 */
    val censusUnavailable: String,

    /** 报告已落盘：{0} 文件名。 */
    val reportSavedFormat: String,
    /** 报告已落盘时的下一步指引（下次启动可复制或分享）。 */
    val reportSavedHint: String,
    /** 报告没落成（OOM / 存储满）时的说法。 */
    val reportMissing: String,

    /** 崩溃屏「重新启动」。 */
    val restartButton: String,
    /** 崩溃屏「关闭」。 */
    val closeButton: String,

    /** 下次启动兜底横幅的标题。 */
    val bannerTitle: String,
    /** 下次启动兜底横幅正文：{0} 异常类型、{1} 一句人间话。 */
    val bannerBodyFormat: String,
    /** 下次启动兜底横幅的关闭按钮。 */
    val bannerAck: String,
) {

    /** 句式占位符单趟替换：参数本身带花括号不会被二次展开（写法同 [ShellFailureCopy.fill]）。 */
    fun fill(template: String, vararg args: Any?): String =
        SLOT.replace(template) { match ->
            val arg = match.groupValues[1].toIntOrNull()?.let { args.getOrNull(it) }
            arg?.toString() ?: match.value
        }

    companion object {
        /** ICU 与 OpenJDK 都认的写法：花括号走字符类，不写反斜杠转义。 */
        private val SLOT = Regex("[{](\\d+)[}]")

        /** 与设置页同源取语言。 */
        fun of(lang: SettingsLanguage): CrashScreenCopy = when (lang) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }

        /** 简体中文。 */
        val ZH: CrashScreenCopy = CrashScreenCopy(
            lang = SettingsLanguage.ZH,
            title = "游戏出错了",
            whatHappenedFormat = "发生了什么：{0}（{1}）",
            causeOutOfMemory = "手机内存不够，游戏被系统中断了",
            causeStackOverflow = "脚本嵌套太深，算不下去了",
            causeScript = "游戏脚本执行时出了问题",
            causeUnknown = "遇到了一次意外错误",
            whereFormat = "出错时正在：{0}，位置是{1}",
            timeUnknown = "时间未知",
            threadMain = "界面线程",
            threadScript = "脚本线程",
            threadOtherFormat = "「{0}」这条线程",
            threadUnknown = "说不上来的线程",
            censusFormat = "资源与引擎状态：{0}",
            censusUnavailable = "取证不可用",
            reportSavedFormat = "完整记录已保存在手机上：eratw-reports/{0}",
            reportSavedHint = "下次启动游戏时，可以把这份记录复制或分享给开发者。",
            reportMissing = "完整记录这次没能保存下来（可能是手机存储空间不足）。",
            restartButton = "重新启动",
            closeButton = "关闭",
            bannerTitle = "上次游戏出错了",
            bannerBodyFormat = "上次游戏出错：{0}（{1}）。记录已经保存在手机上，下次可以分享给开发者。",
            bannerAck = "知道了",
        )

        /** English. */
        val EN: CrashScreenCopy = CrashScreenCopy(
            lang = SettingsLanguage.EN,
            title = "The game ran into a problem",
            whatHappenedFormat = "What happened: {0} ({1})",
            causeOutOfMemory = "the phone ran out of memory and the game was stopped",
            causeStackOverflow = "the script nested too deeply and could not continue",
            causeScript = "the game script failed while running",
            causeUnknown = "something unexpected went wrong",
            whereFormat = "When it happened: {0}, on {1}",
            timeUnknown = "unknown time",
            threadMain = "the screen thread",
            threadScript = "the script thread",
            threadOtherFormat = "the \"{0}\" thread",
            threadUnknown = "an unknown thread",
            censusFormat = "Resources and engine: {0}",
            censusUnavailable = "not available",
            reportSavedFormat = "The full record is saved on this phone: eratw-reports/{0}",
            reportSavedHint = "Next time you start the game you can copy or share this record with the developer.",
            reportMissing = "The full record could not be saved this time (the phone may be low on storage).",
            restartButton = "Restart",
            closeButton = "Close",
            bannerTitle = "The game errored last time",
            bannerBodyFormat = "Last time the game stopped with: {0} ({1}). The record is saved on this phone and can be shared with the developer.",
            bannerAck = "Got it",
        )

        /** 日本語。 */
        val JA: CrashScreenCopy = CrashScreenCopy(
            lang = SettingsLanguage.JA,
            title = "ゲームでエラーが発生しました",
            whatHappenedFormat = "何が起きたか：{0}（{1}）",
            causeOutOfMemory = "スマートフォンのメモリが足りず、ゲームが中断されました",
            causeStackOverflow = "スクリプトの入れ子が深すぎて続行できませんでした",
            causeScript = "ゲームのスクリプト実行中に問題が起きました",
            causeUnknown = "予期しない問題が発生しました",
            whereFormat = "発生時の状況：{0}、{1}",
            timeUnknown = "時刻不明",
            threadMain = "画面のスレッド",
            threadScript = "スクリプトのスレッド",
            threadOtherFormat = "「{0}」のスレッド",
            threadUnknown = "不明なスレッド",
            censusFormat = "リソースとエンジンの状態：{0}",
            censusUnavailable = "取得できません",
            reportSavedFormat = "詳細な記録はスマートフォンに保存済みです：eratw-reports/{0}",
            reportSavedHint = "次回ゲームを起動したときに、この記録を開発者へコピーまたは共有できます。",
            reportMissing = "詳細な記録は今回保存できませんでした（スマートフォンの空き容量が不足している可能性があります）。",
            restartButton = "再起動",
            closeButton = "閉じる",
            bannerTitle = "前回ゲームでエラーが発生しました",
            bannerBodyFormat = "前回ゲームが停止した原因：{0}（{1}）。記録はスマートフォンに保存済みで、次回開発者へ共有できます。",
            bannerAck = "了解",
        )
    }
}

/**
 * 崩溃摘要 → 屏上文字（纯格式化，零 Android 依赖，JVM 单测直测）。
 *
 * 只有三类句子进屏：发生了什么 / 何时与哪条线程 / 资源与引擎取证，尾巴再补一句
 * 「完整记录在哪」。堆栈、类名全限定串、路径一律不进来 —— 那是报告正文的职责。
 */
internal object CrashSummaryText {

    /** 异常类型 → 一句人间话。认不出就给兜底那句，绝不回显原文（原文里常带路径与类名）。 */
    fun causePhrase(copy: CrashScreenCopy, exceptionType: String): String = when {
        exceptionType.contains("OutOfMemory", ignoreCase = true) -> copy.causeOutOfMemory
        exceptionType.contains("StackOverflow", ignoreCase = true) -> copy.causeStackOverflow
        exceptionType.contains("CodeEE", ignoreCase = true) ||
            exceptionType.contains("VmException", ignoreCase = true) -> copy.causeScript
        else -> copy.causeUnknown
    }

    /** 线程名 → 人话标签（`main` / `eratw-vm` 是宿主自造的两条线，用玩家读得懂的叫法）。 */
    fun threadLabel(copy: CrashScreenCopy, threadName: String): String = when {
        threadName.isBlank() -> copy.threadUnknown
        threadName == "main" -> copy.threadMain
        threadName == "eratw-vm" -> copy.threadScript
        else -> copy.fill(copy.threadOtherFormat, threadName)
    }

    /** 「发生了什么」那一行。 */
    fun whatLine(copy: CrashScreenCopy, summary: CrashSummary): String =
        copy.fill(
            copy.whatHappenedFormat,
            summary.exceptionType.ifBlank { copy.causeUnknown },
            causePhrase(copy, summary.exceptionType),
        )

    /** 「何时/哪一步」那一行：时间 + 线程。崩溃处理器里没有阶段字段，不新造采集面。 */
    fun whereLine(copy: CrashScreenCopy, summary: CrashSummary): String =
        copy.fill(copy.whereFormat, timeText(copy, summary.atMillis), threadLabel(copy, summary.threadName))

    /** 资源/引擎取证行：取不到就给「取证不可用」，绝不抛。 */
    fun censusLine(copy: CrashScreenCopy, summary: CrashSummary): String =
        copy.fill(copy.censusFormat, summary.censusLine?.takeIf { it.isNotBlank() } ?: copy.censusUnavailable)

    /** 尾巴那句「完整记录在哪」：报告落成与否两种说法。 */
    fun reportLines(copy: CrashScreenCopy, summary: CrashSummary): List<String> {
        val name = summary.reportName?.takeIf { it.isNotBlank() }
        return if (name == null) {
            listOf(copy.reportMissing)
        } else {
            listOf(copy.fill(copy.reportSavedFormat, name), copy.reportSavedHint)
        }
    }

    /** 崩溃屏正文（段之间空一行；`ScrollView` + `TextView` 直接吃这串）。 */
    fun screenText(copy: CrashScreenCopy, summary: CrashSummary): String = listOf(
        copy.title,
        whatLine(copy, summary),
        whereLine(copy, summary),
        censusLine(copy, summary),
    ).plus(reportLines(copy, summary))
        .joinToString("\n\n")

    /** 下次启动兜底横幅正文（一句话，不带路径）。 */
    fun bannerBody(copy: CrashScreenCopy, summary: CrashSummary): String =
        copy.fill(
            copy.bannerBodyFormat,
            summary.exceptionType.ifBlank { copy.causeUnknown },
            causePhrase(copy, summary.exceptionType),
        )

    /** 时间读数；0 或格式化失败给 [CrashScreenCopy.timeUnknown]（槽位不留空）。 */
    fun timeText(copy: CrashScreenCopy, atMillis: Long): String {
        if (atMillis <= 0L) return copy.timeUnknown
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(atMillis))
        }.getOrDefault(copy.timeUnknown)
    }
}

/**
 * 「上次崩溃摘要」标记：崩溃处理器写、下一次冷启动读、玩家点掉或进入主循环后清。
 *
 * 存放位置是 `filesDir/eratw-reports/crash-summary.txt` —— 与报告同目录但不带
 * `eratw-report-` 前缀，滚动清理（只删 `eratw-report-` 开头）碰不到它。
 *
 * 格式是一行一个 `key=value`（值只保留单行、`=` 之后原样保留），解析按首个等号切：
 * 损坏的行忽略，缺 `type` / `at` / `thread` 任一项即整条作废（返回 null），
 * 让「读不出来」表现为「不弹横幅」，绝不抛到启动路径上。
 *
 * 本对象只碰 `java.io`，不 import 任何 Android 类 ⇒ JVM 单测用临时目录直测。
 */
internal object CrashSummaryMarker {

    /** 标记文件名（同目录里的报告是 `eratw-report-*`，两者互不遮蔽）。 */
    const val FILE_NAME: String = "crash-summary.txt"

    private const val KEY_TYPE = "type"
    private const val KEY_AT = "at"
    private const val KEY_THREAD = "thread"
    private const val KEY_CENSUS = "census"
    private const val KEY_REPORT = "report"

    /** 标记文件（目录不存在也返回路径，读失败由 [read] 折成 null）。 */
    fun file(dir: File): File = File(dir, FILE_NAME)

    /** 摘要 → 标记正文。 */
    fun encode(summary: CrashSummary): String = buildString {
        append(KEY_TYPE).append('=').append(singleLine(summary.exceptionType)).append('\n')
        append(KEY_AT).append('=').append(summary.atMillis).append('\n')
        append(KEY_THREAD).append('=').append(singleLine(summary.threadName)).append('\n')
        summary.censusLine?.takeIf { it.isNotBlank() }
            ?.let { append(KEY_CENSUS).append('=').append(singleLine(it)).append('\n') }
        summary.reportName?.takeIf { it.isNotBlank() }
            ?.let { append(KEY_REPORT).append('=').append(singleLine(it)).append('\n') }
    }

    /** 标记正文 → 摘要；缺关键字段或整份不可读返回 null。 */
    fun decode(text: String): CrashSummary? {
        val fields = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val at = line.indexOf('=')
            if (at > 0) {
                val key = line.substring(0, at).trim()
                if (key.isNotEmpty()) fields[key] = line.substring(at + 1).trim()
            }
        }
        val type = fields[KEY_TYPE]?.takeIf { it.isNotEmpty() } ?: return null
        val at = fields[KEY_AT]?.toLongOrNull() ?: return null
        val thread = fields[KEY_THREAD]?.takeIf { it.isNotEmpty() } ?: return null
        return CrashSummary(
            exceptionType = type,
            atMillis = at,
            threadName = thread,
            censusLine = fields[KEY_CENSUS]?.takeIf { it.isNotEmpty() },
            reportName = fields[KEY_REPORT]?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * 写标记（先写临时文件再改名，半截文件不会被下一次启动读成一份「半真」的摘要）。
     *
     * 绝不抛：崩的就是这条线程，写不成最多是「下次启动少一句提示」。
     *
     * @return true = 落盘成功
     */
    fun write(dir: File, summary: CrashSummary): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val body = encode(summary)
        val target = file(dir)
        val tmp = File(dir, FILE_NAME + ".tmp")
        tmp.writeText(body, Charsets.UTF_8)
        if (tmp.renameTo(target)) {
            true
        } else {
            // 某些 ROM 的 rename 会因目标已存在失败：退回直写，读完即删临时件。
            target.writeText(body, Charsets.UTF_8)
            tmp.delete()
            true
        }
    }.getOrDefault(false)

    /** 读标记；不存在 / 不可读 / 内容坏都返回 null。 */
    fun read(dir: File): CrashSummary? = runCatching {
        val file = file(dir)
        if (!file.isFile) return null
        decode(file.readText(Charsets.UTF_8))
    }.getOrNull()

    /** 清标记（玩家点掉横幅 / 已进入主循环）。绝不抛。 */
    fun clear(dir: File): Boolean = runCatching { file(dir).delete() }.getOrDefault(false)

    /** 标记只存单行值：换行与回车折成空格，避免一条读数被切成两行。 */
    private fun singleLine(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim()
}
