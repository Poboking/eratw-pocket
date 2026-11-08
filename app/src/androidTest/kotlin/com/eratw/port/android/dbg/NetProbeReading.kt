package com.eratw.port.android.dbg

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * 网络类型判定的**读数抄本**（真机装置共用：instrumentation 用例与 [NetProbeActivity]）。
 *
 * 与 `UpdateManager.isUnmetered` 同判据，但**不调它**：装置包（`com.eratw.port.android.test`）
 * 的 APK 里没有应用的类，运行时也拿不到应用的 UID。这里要的只是「这台 ROM 在有/没有
 * `ACCESS_NETWORK_STATE` 时分别把这三个 API 交成什么」，判据照 [NET] 那三条抄一份即可。
 */
object NetProbeReading {

    private const val NET = "android.permission.ACCESS_NETWORK_STATE"

    /** 一行一条，全打进文件里给人读；任何一步失败都写成读数，不抛出去。 */
    fun readAll(context: Context): List<String> {
        val self = context.packageName
        val granted = context.checkPermission(NET, android.os.Process.myPid(), android.os.Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED
        val lines = ArrayList<String>()
        lines += "package=$self sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}"
        lines += "granted[$NET]=$granted"
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return lines + "ConnectivityManager=null"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            return lines + "legacy activeNetworkInfo=${describe(runCatching { cm.activeNetworkInfo })}"
        }
        val network = runCatching { cm.activeNetwork }
        val caps = network.getOrNull()?.let { runCatching { cm.getNetworkCapabilities(it) } }
        @Suppress("DEPRECATION")
        val legacy = runCatching { cm.activeNetworkInfo }
        lines += "activeNetwork=${describe(network)}"
        lines += "caps=${caps?.let { describe(it) } ?: "没网络不查"}"
        lines += "legacy=${describe(legacy)}"
        // isUnmetered 的判据本体（UpdateManager.isUnmetered，API 23+ 那条分支）。
        val c = caps?.getOrNull()
        lines += "wouldBeUnmetered=" + if (c == null) "false（判不了→界面当蜂窝）" else {
            (
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    !c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                ).toString()
        }
        return lines
    }

    private fun describe(result: Result<Any?>): String = runCatching {
        when (val value = result.getOrThrow()) {
            null -> "null"
            is NetworkCapabilities -> value.summary()
            else -> value.toString()
        }
    }.getOrElse { "抛${it.javaClass.simpleName}:${it.message}" }

    private fun NetworkCapabilities.summary(): String {
        val transports = TRANSPORTS.filter { hasTransport(it.second) }.map { it.first }
        return "(transports=${transports.ifEmpty { listOf("NONE") }}, " +
            "notMetered=${hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)})"
    }

    private val TRANSPORTS = listOf(
        "WIFI" to NetworkCapabilities.TRANSPORT_WIFI,
        "ETHERNET" to NetworkCapabilities.TRANSPORT_ETHERNET,
        "CELLULAR" to NetworkCapabilities.TRANSPORT_CELLULAR,
        "VPN" to NetworkCapabilities.TRANSPORT_VPN,
    )
}
