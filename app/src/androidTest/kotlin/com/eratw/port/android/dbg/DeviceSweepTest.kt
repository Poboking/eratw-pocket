package com.eratw.port.android.dbg

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DBG2 全量函数清扫装置的 **Android 胶水**（参数解析 / 设备路径 / 沙箱落点），核心驱动
 * 在 [FunctionSweeper]（纯 Kotlin、零 android import，组长日后挪桌面侧复用只搬那个文件）。
 *
 * ## 跑法（真机已装 737MB 全随包包，erbc.v1 已解包；装置不 push 语料）
 *
 * ```bash
 * adb shell am instrument -w \
 *   -e class com.eratw.port.android.dbg.DeviceSweepTest \
 *   [-e erbc <erbc目录>] [-e game <游戏根>] [-e out <台账路径>] \
 *   [-e steps 2000000] [-e shard 0 -e nshard 1] [-e only <key正则>] \
 *   [-e commit <基线commit>] [-e selftest 1] \
 *   com.eratw.port.android.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * hawkeye 的 `sweep` 子命令（devtools/hawkeye.py）把上面整段（含 30 分钟看门狗、pull、
 * 摘要）包成一条命令，优先用那条。
 *
 * ## 参数（am instrument -e；路径缺省 = 真机热更产物与 DevToolsHost 的同一口径
 * `<externalFilesDir>`（/sdcard/Android/data/com.eratw.port.android/files）之下）
 *
 * | 参数 | 缺省 | 说明 |
 * |---|---|---|
 * | erbc | `<内部 filesDir>/eratw/erbc.v1` | 产物目录（program.erbc + program.meta） |
 * | game | `<内部 filesDir>/eratw` | 游戏根（CSV/resources/sound/emuera.config 所在层） |
 * | out | `<externalFilesDir>/eratw/dbg/report.jsonl` | 台账；nshard 大于 1 且未显式给 out 时按 `report.s<i>of<n>.jsonl` 分文件（多进程并发写同一文件必坏，见 SweeperLedger） |
 * | steps | 2000000 | 单次 run 步数闸 |
 * | wallms | 10000 | 逐函数墙钟预算（AWAIT/输入挂起兜底；`0` = 关） |
 * | skipmethods | 无 | `1` = 跳过 #FUNCTION/#FUNCTIONS 方法（不可直调） |
 * | shard / nshard | 0 / 1 | 只跑 defIndex % n == i |
 * | only | 无 | 大写 key 的正则；被过滤掉的函数不写行 |
 * | commit | 空串 | 基线 commit（进 meta 行便于差分对账） |
 * | selftest | 无 | `1` = 不清扫，跑 [SweepSelfTest] 四点机制自测 |
 *
 * ## 线程与收口
 *
 * 清扫全程占用 instrumentation 测试线程（装载 + 逐函数串行调用，每进程一个会话、绝不
 * 重复建会话）；台账逐行即时落盘（无用户态缓冲），进程被看门狗击杀时最后一行 RUNNING
 * 即肇事函数。真机 logcat 可能被 ROM 整条吞（vivo 实测）——一切结果以台账文件为准，
 * log 输出只是辅助。
 */
class DeviceSweepTest {

    @Test
    fun sweep() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val externalRoot = context.getExternalFilesDir(null)
            ?: error("getExternalFilesDir(null) 返回 null：外部存储不可用，装置无法定位产物")
        // 数据面（erbc/game）在**内部** filesDir 下的 eratw（GameStorage 首启解包落点，
        // run-as com.eratw.port.android ls files/eratw 可证）；台账 out 留外置（adb 好取）。
        val dataRoot = File(context.filesDir, "eratw")

        val nshard = (args.getString("nshard") ?: "1").toIntOrNull()?.coerceAtLeast(1) ?: 1
        val shard = (args.getString("shard") ?: "0").toIntOrNull()?.coerceIn(0, nshard - 1) ?: 0
        val outArg = args.getString("out")?.takeIf { it.isNotBlank() }
        val out = File(outArg ?: defaultOutPath(File(externalRoot, "eratw"), shard, nshard))

        if (args.getString("selftest") == "1") {
            runSelfTest(out)
            return
        }

        val erbc = File(args.getString("erbc")?.takeIf { it.isNotBlank() } ?: File(dataRoot, "erbc.v1").absolutePath)
        val game = File(args.getString("game")?.takeIf { it.isNotBlank() } ?: dataRoot.absolutePath)
        val only = args.getString("only")?.takeIf { it.isNotBlank() }?.toRegex()
        val config = SweepConfig(
            erbcDir = erbc.toPath(),
            gameRoot = game.toPath(),
            sandboxSavDir = File(context.cacheDir, SANDBOX_DIR).toPath(),
            outPath = out.toPath(),
            maxStepsPerRun = args.getString("steps")?.toLongOrNull() ?: FunctionSweeper.DEFAULT_MAX_STEPS,
            wallMs = args.getString("wallms")?.toLongOrNull() ?: DbgAssembly.DEFAULT_WALL_MS,
            skipMethods = args.getString("skipmethods") == "1",
            shard = shard,
            nshard = nshard,
            only = only,
            commit = args.getString("commit") ?: "",
        )
        Log.i(TAG, "sweep 开始：erbc=$erbc game=$game out=$out shard=$shard/$nshard")
        // 进程钉活动：防 Cached App Freezer 冻结 instrumentation（装载 mmap 永久 D 态，2026-10-25 vivo 实锤）
        DevKeepAliveActivity.start(context)
        try {
            FunctionSweeper.run(config) { line -> Log.i(TAG, line) }
        } finally {
            DevKeepAliveActivity.stop()
        }
        Log.i(TAG, "sweep 结束（台账=$out）")
    }

    /**
     * selftest 路径：跑 [SweepSelfTest] 四点，摘要落 `<out 同目录>/selftest.txt`，
     * 任一点 FAIL 即 JUnit 断言失败（`am instrument -w` 直接读得到 failed 计数与断言文本）。
     */
    private fun runSelfTest(out: File) {
        val checks = SweepSelfTest.run()
        val text = SweepSelfTest.render(checks)
        val summary = File(out.absolutePath.removeSuffix(".jsonl").removeSuffix(".selftest") + ".selftest.txt")
        summary.parentFile?.mkdirs()
        summary.writeText(text, Charsets.UTF_8)
        Log.i(TAG, text)
        val failed = checks.filter { !it.passed }
        assertTrue(
            "DBG2 selftest 未全绿（${failed.size}/${checks.size} FAIL，摘要=$summary）：\n" +
                failed.joinToString("\n") { "${it.name}: ${it.detail}" },
            failed.isEmpty(),
        )
    }

    /**
     * 台账缺省路径：nshard = 1 用 `report.jsonl`；大于 1 按 `report.s<i>of<n>.jsonl`
     * 分文件（hawkeye 侧的 `device_out_path` 按同一规则镜像，两端改动必须同步）。
     */
    private fun defaultOutPath(eratwRoot: File, shard: Int, nshard: Int): String {
        val name = if (nshard <= 1) "report.jsonl" else "report.s${shard}of$nshard.jsonl"
        return File(File(eratwRoot, "dbg"), name).absolutePath
    }

    companion object {
        private const val TAG = "DBG2SWEEP"

        /** 存档沙箱在 instrumentation context.cacheDir 下的子目录（读真游戏根、写全落这里）。 */
        const val SANDBOX_DIR = "dbg2-sweep/sav"
    }
}
