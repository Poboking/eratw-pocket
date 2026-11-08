package com.eratw.port.android.dbg

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.eratw.port.android.UpdateManager
import com.eratw.port.android.UpdateUiText
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 网络类型判定的**真机读数装置**（只抄现场，不改任何行为）。
 *
 * 存在的理由：玩家反馈「我用 wifi，提示更新还会说不是 wifi」，根因是 manifest 少声明
 * `ACCESS_NETWORK_STATE`（官方 apidoc 写明 `getActiveNetwork()` 与 `getActiveNetworkInfo()`
 * 都 Requires 它）。这类「少一行权限 = 真机上静默失效」的形状，纯 JVM 单测只钉得住声明面，
 * 钉不住**这台 ROM 到底怎么挡**——是抛 SecurityException 还是把结果挡成 null，
 * 只有以应用自己的 UID 跑一次才知道。
 *
 * 测的是**装在机上那一版**：instrumentation 跑在目标应用的进程与 UID 里，`UpdateManager`
 * 从目标 APK 解析，`requestedPermissions` 读的也是目标 APK 的 manifest ⇒ 换包前后各跑一次
 * 就是这条链的 before/after。前提是两个包同章（发布章的应用注不进 debug 章的装置 ⇒
 * 那种场合用 [NetProbeActivity]，它不依赖目标应用）。
 *
 * 读数一律落文件（vivo 实测 logcat 会被整条吞，同 [DeviceSweepTest]）：
 * `<externalFilesDir>/eratw/dbg/netprobe.txt`。
 *
 * ```bash
 * adb shell am instrument -w \
 *   -e class com.eratw.port.android.dbg.NetworkStateProbeTest \
 *   com.eratw.port.android.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
class NetworkStateProbeTest {

    @Test
    fun probe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val externalRoot = context.getExternalFilesDir(null)
            ?: error("getExternalFilesDir(null) 返回 null：装置没有落点")
        val out = File(externalRoot, "eratw/dbg/netprobe.txt").apply { parentFile?.mkdirs() }

        val unmetered = runCatching { UpdateManager.isUnmetered(context) }
        val lines = ArrayList<String>()
        lines += "target=${describeInstalledPackage(context)}"
        lines += NetProbeReading.readAll(context)
        lines += "isUnmetered=" + unmetered.fold(
            { it.toString() },
            { "抛${it.javaClass.simpleName}:${it.message}" },
        )
        // 界面对「判不了」一律当蜂窝：这一行为真 ⇒ 弹窗多一句「当前不是 Wi-Fi」、下载要二次确认。
        // （EP4-U0 拆分后判定层叫 UpdateUiText，与 UpdateUi.kt:373 弹窗那一支用的是同一个函数）
        lines += "uiWouldSayNotWifi=${UpdateUiText.cellularConfirmRequired(unmetered.getOrDefault(false))}"
        lines.forEach { Log.i(TAG, it) }

        out.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        assertTrue("读数没写进 ${out.absolutePath}", out.length() > 0L)
    }

    /** 装的是哪一版、申请了哪些权限——before/after 归因全靠这一行。 */
    private fun describeInstalledPackage(context: android.content.Context): String {
        val pm = context.packageManager
        val pkg = context.packageName
        val base = pm.getPackageInfo(pkg, 0)
        val requested: List<String> = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
        return "package=$pkg version=${base.versionName}+${longVersionCode(base)} " +
            "sdk=${Build.VERSION.SDK_INT} requests=$requested"
    }

    private fun longVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private companion object {
        const val TAG = "EratwNetProbe"
    }
}
