package com.eratw.port.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 云真机（Firebase Test Lab）用的**手机 UX 取证用例**：把「装载 → 标题页 → 壳层交互」走一遍，
 * 关键帧全部打 logcat（`UXCLOUD` tag）并逐帧落 PNG（设备内，随录像一起可回看）。
 *
 * ## 为什么不在普通 JVM 测
 * 判据是「屏上真的画出了什么」：字号是否可读、标题图是否溢出、加载条是否渲染成色块
 * 而不是裸 `<shape ...>` 文本、Compose 壳层（选项栏/输入条/设置页）是否正常。这些只能
 * 在真设备上看像素；FTL 只带回 `--record-video` 的录像 + logcat，所以每个关键帧都记一行
 * `UXCLOUD shot=<名字> 2400x1080 hash=<像素哈希>`，回读录像与 logcat 即可对齐。
 *
 * ## 交互脚本（坐标按 2400x1080 横屏，MediumPhone.arm / vivo V2199A 同分辨率）
 * 1. 等游戏装好（本用例不判「多久」，只等固定预算）；
 * 2. 截标题页；
 * 3. 点右上「设置」→ 截设置页 → 返回；
 * 4. 点右侧选项栏第一项（x≈2170,y≈200）→ 看是否推进（截帧对比哈希）。
 *
 * 三条断言只钉「不会崩 + 有画面」：Activity 存活、截图非全黑、设置页能打开。
 * 视觉好坏留给录像人工判读（这也是 M10-CLOUD 的口径）。
 */
class PhoneUxCloudTest {

    @Test
    fun launchTitleSettingsAndOptionRail() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val dir = File(
            instrumentation.targetContext.getExternalFilesDir(null),
            "uxcloud",
        ).apply { mkdirs() }

        val shots = ArrayList<Pair<String, Int>>()
        fun shot(name: String) {
            try {
                // uiautomator 2.3.0 的截图入口是「写文件 + 返回 Boolean」，不是返回 Bitmap。
                val file = File(dir, "$name.png")
                val ok = device.takeScreenshot(file)
                if (!ok) {
                    Log.i(TAG, "shot=$name (takeScreenshot 返回 false)")
                    return
                }
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp == null) {
                    Log.i(TAG, "shot=$name (解码失败)")
                    return
                }
                val hash = pixelHash(bmp)
                shots += name to hash
                Log.i(TAG, "shot=$name ${bmp.width}x${bmp.height} hash=$hash")
                bmp.recycle()
            } catch (t: Throwable) {
                Log.i(TAG, "shot=$name 失败 ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        Log.i(TAG, "ActivityScenario.launch 返回")
        shot("01-launch")

        // 装载预算：云机首启解包 + 332MB erbc 装载，按 12 分钟兜底；用例本身不做节奏判据。
        // 用小包（-PeratwSkipAssets=1）跑壳层 UI 时可用 FTL 环境变量 uxBootSec 缩短。
        val bootSec = InstrumentationRegistry.getArguments()
            .getString("uxBootSec")?.toLongOrNull() ?: 720L
        val deadline = System.currentTimeMillis() + bootSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000)
            shot("02-boot-${((deadline - System.currentTimeMillis()) / 1000).toInt()}s")
        }
        shot("03-title")

        // ---- 翻页延迟实测（负责人实测「点开始游戏后像卡住，44 秒才出下一页」）----
        // 走真实输入路径：点底部小凸边展开输入条 → 点文本域 → `input text 0` → 回车。
        // 屏幕哈希一变就算「下一页出现」；app 侧同时会打 `turn.latency ms=`（logcat 互证）。
        val w = device.displayWidth
        val h = device.displayHeight
        val scratch = File(dir, "live.png")

        fun frameHash(): Int {
            return try {
                if (!device.takeScreenshot(scratch)) return 0
                val bmp = BitmapFactory.decodeFile(scratch.absolutePath) ?: return 0
                val hash = pixelHash(bmp)
                bmp.recycle()
                hash
            } catch (_: Throwable) {
                0
            }
        }

        device.click(w / 2, h - 20) // 底部「︿ 上滑输入」小凸边 → 展开输入条
        Thread.sleep(900)
        shot("04-input-shown")
        device.click((w * 0.35).toInt(), h - 70) // 文本域
        Thread.sleep(600)
        device.executeShellCommand("input text 0")
        Thread.sleep(400)
        val beforeTurn = frameHash()
        val turnStart = System.currentTimeMillis()
        device.executeShellCommand("input keyevent 66") // ImeAction.Done → onSubmit
        var turnMs = -1L
        for (i in 0 until 120) { // 最多 60s 采样
            Thread.sleep(500)
            val now = frameHash()
            if (beforeTurn != 0 && now != 0 && now != beforeTurn) {
                turnMs = System.currentTimeMillis() - turnStart
                break
            }
        }
        Log.i(TAG, "UXCLOUD turn ms=$turnMs beforeHash=$beforeTurn")
        shot("05-after-start-game")

        // 标题页 → 设置页（Compose）→ 返回。
        device.findObject(By.text("设置"))?.click()
        device.wait(Until.hasObject(By.text("全部重置")), 10_000)
        Thread.sleep(1_500)
        shot("06-settings")
        val settingsVisible = device.hasObject(By.text("全部重置")) ||
            device.hasObject(By.text("保存"))
        Log.i(TAG, "settingsVisible=$settingsVisible")

        device.pressBack()
        Thread.sleep(2_000)
        shot("07-back-to-game")

        // 右侧选项栏第一项（2400x1080 横屏：栏宽 150dp，首项中心约 2170,200）。
        device.click(2170, 200)
        Thread.sleep(4_000)
        shot("08-after-rail-tap")

        assertTrue("截图数量太少：${shots.size}", shots.size >= 4)
        val nonBlank = shots.count { it.second != 0 }
        assertTrue("截图全黑（$nonBlank/${shots.size} 非空）", nonBlank >= 4)
        assertTrue("设置页没打开（找不到「全部重置」/「保存」）", settingsVisible)
        scenario.close()
        Log.i(TAG, "done shots=${shots.size} settingsVisible=$settingsVisible")
    }

    private fun pixelHash(bmp: Bitmap): Int {
        var acc = 17
        var nonBlack = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                if (c and 0xFFFFFF != 0) nonBlack++
                acc = acc * 31 + c
                x += 37
            }
            y += 37
        }
        return if (nonBlack == 0) 0 else acc
    }

    private companion object {
        const val TAG = "UXCLOUD"
        const val BOOT_BUDGET_MS = 12 * 60 * 1000L
    }
}
