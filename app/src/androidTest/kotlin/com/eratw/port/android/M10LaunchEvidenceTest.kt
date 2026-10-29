package com.eratw.port.android

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M10-CLOUD 取证用例：装机即玩 + 截图必须有立绘/背景。
 *
 * 一条用例走完「启动 → 首启解包 → 装载 → 首屏 → 交互探针 → 取证落盘」，
 * 中间不 sleep 固定秒数（出口判据全部来自 logcat 可观测量与屏幕像素哈希，
 * 见 [M10LoadWatcher]）。所有读数同时写：设备上的取证文本 + logcat（云测只能带回 logcat）。
 *
 * 判据分两组：
 * - 硬失败：抓不到屏、logcat 有 FATAL/ANR、装载在预算内没收敛、**终态截图过不了
 *   [ArtworkGate]（画面只有文字与底色）**；
 * - 只记录不判死：`Displayed` 行缺失（部分云机不打印该 tag）、回车后画面是否推进
 *   （那是 m10-play 的判据，这里只当交叉读数）。
 *
 * 当前状态（随包 APK 还没做好、图形 sink 还没接上）必然报红：停在演示屏 →
 * 没有任何 imageLike 色块。这是**设计如此**，不许为了好看放宽到永真。
 *
 * ## Firebase Test Lab 命令块（组长执行；本机无 gcloud，参数写法未实机核对 ⇒ TODO(verify)）
 *
 * 产物两条都来自 `:app`：被测 APK 用 m10-pkg400 的随包版（400 MB 级），
 * 测试 APK 由本目录的 androidTest 编出来。两条 APK 的路径与 instrumentation 声明
 * （`androidx.test.runner.AndroidJUnitRunner` + `targetPackage=com.eratw.port.android`）
 * 已在 HOMEJIA 上实测产出并核对；`gcloud` 的参数写法没有本机装置可核 ⇒ 组长跑前先
 * `gcloud firebase test android run --help --type instrumentation` 对一遍。
 *
 * ```
 * # 1) 编译两个 APK（androidTest 的接线走 tools/m10-cloud 的 init 脚本，不动 build.gradle.kts）
 * ./gradlew --configure-on-demand -Pandroid.useAndroidX=true \
 *   -I tools/m10-cloud/androidtest-wiring.init.gradle \
 *   -PeratwErbcDir=<erbc.v1 目录> -PeratwGameRoot=<游戏根> \
 *   :app:assembleDebug :app:assembleDebugAndroidTest
 *
 * # 2) 四档矩阵跑组（MediumPhone.arm x API 26/30/33/35）
 * gcloud firebase test android run --type instrumentation \
 *   --project eratw-78be0 \
 *   --app  app/build/outputs/apk/debug/app-debug.apk \
 *   --test app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
 *   --device model=MediumPhone.arm,version=26 \
 *   --device model=MediumPhone.arm,version=30 \
 *   --device model=MediumPhone.arm,version=33 \
 *   --device model=MediumPhone.arm,version=35 \
 *   --results-history-name m10-cloud-playability \
 *   --results-dir D:/DSH/ftl-results \
 *   --timeout 45m \
 *   --num-flaky-test-attempts 0 \
 *   --use-orchestrator=false \
 *   --record-video \
 *   --other-logs logcat,host_exec_start
 * ```
 *
 * 三条口径说明：
 * - `--use-orchestrator=false`：编排器会在每个用例之间清进程与数据，首启 361 MiB 解包就要
 *   重跑一遍，四台机x多个用例会把 45 分钟预算吃光；本装置的用例本来就设计成一条走全程。
 * - `--timeout 45m` 是 instrumentation 的上限。首启解包 + 装载在云机上按 10 分钟量级预留。
 * - 截图与取证文本**不会**被 FTL 从设备目录里带回来，能带走的只有 `--record-video` 的录像
 *   和结果包里 `logs` 目录下的 `logcat.log`；所以 [M10Evidence] 每行都同时打 logcat（tag `M10EVIDENCE`），
 *   拉回结果包后 `grep M10EVIDENCE logcat.log` 就是同一份取证文本。
 *   USB 真机（vivo V2199A）则直接拉目录：
 *   `adb pull /sdcard/Android/data/com.eratw.port.android/files/m10-evidence D:/DSH/m10-evidence/`。
 *
 * 本地（有设备/模拟器时）同一条用例：
 * `./gradlew :app:connectedDebugAndroidTest`，或
 * `adb shell am instrument -w -e class com.eratw.port.android.M10LaunchEvidenceTest \
 *   -e m10TimeoutSec 1200 -e gateChromaRatio 0.03 com.eratw.port.android.test/androidx.test.runner.AndroidJUnitRunner`。
 */
class M10LaunchEvidenceTest {

    @Test
    fun launchUnpackLoadAndGateOnArtwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val gate = ArtworkGate.fromArgs { key -> args.getString(key) }
        val timeoutSec = args.getString("m10TimeoutSec")?.toLongOrNull() ?: DEFAULT_TIMEOUT_SEC
        val pollMs = args.getString("m10PollMs")?.toLongOrNull() ?: DEFAULT_POLL_MS
        val probeEnter = args.getString("m10SendEnter")?.equals("true", ignoreCase = true) ?: true

        val dir = M10Evidence.evidenceDir(context)
        val evidence = M10Evidence.open(context, "launch")
        evidence.record("取证目录=${dir.absolutePath}")
        evidence.record("画面判据：${gate.describe()}")
        evidence.record("轮询节奏=${pollMs}ms 预算=${timeoutSec}s（预算是上限，判据来自可观测量）")

        val device: UiDevice? = try {
            UiDevice.getInstance(instrumentation)
        } catch (t: Throwable) {
            evidence.record("UiDevice 不可用（${t.javaClass.simpleName} ${t.message}）：抓屏只能走窗口软件渲染，logcat 只能读自身 uid")
            null
        }
        val logcat = M10Logcat(device)
        val capture = M10ScreenCapture(device, dir)
        val holder = M10ActivityHolder()
        val shots = ArrayList<Pair<String, M10ScreenCapture.Shot>>()
        var stageIndex = 1

        fun stage(label: String, name: String): M10ScreenCapture.Shot? =
            capture.capture(stageIndex, name) { holder.current() }?.also {
                stageIndex++
                shots += label to it
                evidence.record("截图[$label] ${it.note()}")
            }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        holder.refresh(scenario)
        evidence.record("ActivityScenario.launch(MainActivity) 返回，开始轮询")
        stage("启动瞬间", "01-launch")

        var sawUnpackStage = false
        var sawLoadStage = false
        val watcher = M10LoadWatcher(
            logcat = logcat,
            pollScreenHash = { capture.pollHash { holder.current() } },
            pollIntervalMs = pollMs,
            deadlineElapsedMs = SystemClock.elapsedRealtime() + timeoutSec * 1000L,
        )
        val (state, dump) = watcher.runUntilTerminal { round, current ->
            evidence.record(
                "轮${round.index} +${round.elapsedMs}ms 哈希=${round.hash} 稳定=${round.stable} " +
                    "装载行=${round.loadLines} 解包行=${round.unpackLines} → ${round.note}",
            )
            if (!sawUnpackStage && current.installRan()) {
                sawUnpackStage = true
                stage("解包中", "02-unpacking")
            }
            if (!sawLoadStage && current.loadStarted()) {
                sawLoadStage = true
                stage("装载中", "03-loading")
            }
        }
        holder.refresh(scenario)

        evidence.recordSection("收敛")
        evidence.record("终态=${state.label} 轮数=${watcher.rounds.size} logcat通道=${logcat.channel}")
        evidence.record("解包进度行数=${dump.unpackLines.size} 解包耗时=${dump.unpackDurationMs()?.let { "$it ms" } ?: "无法从时间戳推得（tag 或格式不在预期内）"}")
        evidence.recordAll("Displayed | ", dump.displayedLines)
        evidence.recordAll("load | ", dump.loadLines)
        evidence.record("load.peak | ${dump.peakLine ?: "(没有这一行)"}")
        evidence.recordAll("解包完成 | ", dump.unpackDoneLines)
        evidence.recordAll("失败行 | ", dump.failureLines)
        evidence.recordAll("FATAL | ", dump.fatalLines)
        evidence.recordAll("ANR | ", dump.anrLines)

        val terminal = stage("终态", "04-terminal")
        val terminalStats = terminal?.stats()
        if (terminal != null && terminalStats != null) {
            evidence.recordSection("终态截图统计（${terminal.note()}）")
            terminalStats.readings().forEach { evidence.record("  $it") }
        }

        // 画面判据在 READY 之外也要跑：产物缺失/装载失败屏同样必须被证明「只有文字」。
        val violations = ArrayList<String>()
        if (terminal == null) {
            violations += "抓屏失败：UiDevice 与窗口软件渲染两条路都没出 PNG，画面判据无从计算"
        } else if (terminalStats == null) {
            violations += "截图已落盘但解码失败：${terminal.file.absolutePath}"
        } else {
            violations += gate.violations(terminalStats).map { "画面判据：$it" }
        }
        if (dump.fatalLines.isNotEmpty()) {
            violations += "logcat 有 FATAL EXCEPTION：${dump.fatalLines.first()}"
        }
        if (dump.anrLines.isNotEmpty()) violations += "logcat 有 ANR：${dump.anrLines.first()}"
        if (state == TerminalState.TIMEOUT) {
            violations += "装载在预算 ${timeoutSec}s 内没收敛（轮数=${watcher.rounds.size}，最后一轮读数见取证文本）"
        }
        if (dump.displayedLines.isEmpty()) {
            val note = "没有 Displayed 行（通道=${logcat.channel}）：只作交叉读数，不判死"
            evidence.record(note)
        }

        // 交互探针：按一次回车，看这一屏会不会变（m10-play 的判据，这里只记录）。
        if (probeEnter && device != null && terminal != null) {
            val before = terminal.hash
            var changed = false
            try {
                device.pressEnter()
                val until = SystemClock.elapsedRealtime() + ENTER_PROBE_MS
                while (SystemClock.elapsedRealtime() < until) {
                    if (capture.pollHash { holder.current() } != before) {
                        changed = true
                        break
                    }
                    Thread.sleep(POLL_PROBE_MS)
                }
            } catch (t: Throwable) {
                evidence.record("回车探针异常（不计入判据）：${t.javaClass.simpleName} ${t.message}")
            }
            evidence.record("回车探针：画面${if (changed) "已推进" else "未变化"}（不计入本用例判据，交互判据归 m10-play）")
            stage(if (changed) "回车后(已推进)" else "回车后(未变化)", "05-after-enter")
        }

        evidence.recordSection("结论")
        evidence.record("违例 ${violations.size} 条")
        violations.forEach { evidence.record("  X $it") }
        shots.forEach { (label, shot) -> evidence.record("  屏证[$label] ${shot.file.absolutePath}") }
        // 每一张的阶段读数都留一行：能直接看出「画是从哪一屏开始出现的」，而不是只有终态一个点。
        shots.forEach { (label, shot) ->
            runCatching { shot.stats() }.onSuccess { evidence.record("  读数[$label] $it") }
                .onFailure { evidence.record("  读数[$label] 计算失败：${it.javaClass.simpleName} ${it.message}") }
        }
        val evidenceFile = evidence.flush()

        scenario.close()

        val report = buildString {
            appendLine("M10-CLOUD 取证未过。")
            appendLine("终态=${state.label} 轮数=${watcher.rounds.size} logcat通道=${logcat.channel}")
            appendLine("取证文本=${evidenceFile.absolutePath}")
            appendLine("截图=${shots.map { it.second.file.name }.joinToString(", ")}")
            appendLine("判据=${gate.describe()}")
            terminalStats?.let { appendLine("读数=${it}") }
            appendLine("logcat 关键行：Displayed=${dump.displayedLines.size} 装载行=${dump.loadLines.size} peak=${dump.peakLine != null} 解包行=${dump.unpackLines.size} FATAL=${dump.fatalLines.size} ANR=${dump.anrLines.size}")
            appendLine("违例：")
            violations.forEach { appendLine("  - $it") }
        }
        Log.i(M10Evidence.TAG, "用例结论：违例 ${violations.size} 条，文本 ${evidenceFile.absolutePath}")
        assertTrue(report.toString(), violations.isEmpty())
    }

    /** 取证目录必须真的能写：写不进就把这条单独报出来，别让画面判据背锅。 */
    @Test
    fun evidenceDirectoryIsWritableOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = M10Evidence.evidenceDir(context)
        val probe = File(dir, "m10-write-probe.txt")
        probe.writeText("ok")
        assertTrue("取证目录不可写：${dir.absolutePath}", probe.isFile && probe.length() > 0)
        probe.delete()
    }

    companion object {
        /** 首启要解 361 MiB 再装载，云机上按 20 分钟预留；可用 `-e m10TimeoutSec` 覆盖。 */
        const val DEFAULT_TIMEOUT_SEC = 1200L

        const val DEFAULT_POLL_MS = 2000L
        private const val ENTER_PROBE_MS = 30_000L
        private const val POLL_PROBE_MS = 500L
    }
}
