package com.eratw.port.platform.update

import com.eratw.port.core.update.UpdateCheckDecision
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

/**
 * `UPDATECHECK` 的**取流面**宿主实现（CONTRACT-UPDATE §2 U-F）：把原版写在语句方法体里的
 * 两件 core 干不了的事（判网络、取 URL 的前两行）搬到宿主侧。
 *
 * ## 与原版 `UPDATECHECK_Instruction.DoInstruction` 的对应
 *
 * | 原版 | 本类 | 证据 |
 * |---|---|---|
 * | `NetworkInterface.GetIsNetworkAvailable()` | [isNetworkAvailable] | `reference/emuera-cs/MinorShift.Emuera.GameProc.Function/FunctionIdentifier.cs:2772` |
 * | `new WebClient()` + `webClient.OpenRead(url)` | [fetchVersionInfo] | 同文件 `:2778`、`:2786` |
 * | `new StreamReader(stream)` + 两次 `ReadLine()` | 同上的前两行 | 同文件 `:2787`、`:2790-2791` |
 *
 * ## 为什么不是「照抄 WebClient」
 *
 * 原版那条 `OpenRead`（`:2786`）是**没有超时的同步阻塞**，跑在桌面单进程的 UI/proc 线程上；
 * 移植侧调用点在 VM 线程，一次无上限阻塞等于把整局游戏停在那条语句上（弱网时玩家看到的是
 * 「按钮点了没反应」）。所以这里两条超时都是硬要求（连接 [connectTimeoutMillis] / 读 [readTimeoutMillis]），
 * 拿不到就返回 null 让决策表落 RESULT=3 —— 与原版「`OpenRead` 抛出被 `:2834-2837` 的 catch
 * 收成 3」同一个出口，只是把「等到天荒地老」换成「等到超时」。
 *
 * 另一处刻意的不同：只收 `http` / `https`。原版 `WebClient` 顺带支持 `ftp`，而
 * `GameBase.csv` 里那一行是**作者手写的**，`file:` 之类的形态在 Android 上要么读不到、
 * 要么读到应用私有目录里的东西；判 null 落 3 比开这个口子干净。
 *
 * 字节上限 [maxBytes]：流的前两行之外一概不读。本函数不该为一句话的版本号把
 * 玩家几百 MiB 流量拉进内存（清单里那份 743 MiB 的数据包就放在同一个域名下）。
 *
 * 本类不碰 Android API（`java.net` 在纯 JVM 与 Android 上同形），因此 core 侧的决策表
 * 与这里的取流面可以各自独立验证。
 */
class UpdateCheckTransport(
    /** `HttpURLConnection.setConnectTimeout`（毫秒）。 */
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    /** `HttpURLConnection.setReadTimeout`（毫秒）。 */
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MS,
    /** 两行合计最多读多少字符（超出即放弃，按「取回内容不对」处理）。 */
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {

    /**
     * 网络是否可用 —— 原版 `NetworkInterface.GetIsNetworkAvailable()`（`FunctionIdentifier.cs:2772`）
     * 的等价物。
     *
     * .NET 那个方法的实际判据是「存在一块**状态为 Up 且非回环**的网卡」，它**不**测连通性、
     * 不测能否上网（拔了网线的以太网口照样算 available）。所以这里用 Java 的同名 API
     * `java.net.NetworkInterface`（Android 也提供）逐块看 `isUp` 与非回环，判据与原版同形；
     * 换成 `ConnectivityManager` 的「有活动网络且已验证」会**更严格**，把原版给 5 的
     * 「链路在但上不了网」翻成后面的取流失败 ⇒ 3，读数就变了。
     *
     * 枚举本身抛异常（权限 / 设备实现问题）时按「不可用」交出 false（⇒ RESULT=5）。
     * 这一条是**登记过的移植差异**，不是原版行为：原版 `:2772` 的调用在 try **之外**
     * （try 从 `:2784` 才开始），`GetIsNetworkAvailable()` 抛出时不走 RESULT=3 那条 catch，
     * 而是整条语句报错（`Process.cs:571-593` 的 `handleExceptionInSystemProc`，调用点 `:471`）。
     * 移植侧不给「一句 `UPDATECHECK` 把游戏打成失败屏」这个出口：Android 上枚举网卡失败的
     * 常见原因是 ROM 权限收紧，此时「没网」是比崩溃更有用的读数，而且原版这一支本来也
     * 没有可读的 RESULT 语义可言（`RESULT` 保持上一条语句的值）。
     */
    fun isNetworkAvailable(): Boolean = try {
        val listed: List<NetworkInterface> = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        // 逐块判：某一块状态读不出（个别 ROM 的 `isLoopback()` / `isUp()` 会抛）只是它自己不算数，
        // 不能因此把「其实有可用网卡」翻成 false ⇒ 每块各自兜底（判据在本文件的纯函数里，可单测）。
        listed.any { nf -> usableNetworkInterface({ nf.isLoopback }, { nf.isUp }) }
    } catch (_: Throwable) {
        false
    }

    /**
     * 取回 [url] 的前两行（原版 `:2786-2791`）。
     *
     * 返回 null 的每一种情况都对应原版的一条 catch：连不上、非 2xx、读流异常、超时。
     * 「第一行或第二行为空」不是失败 —— 那由决策表自己判（`:2792-2799` 同样落 3），
     * 所以这里**只读两行**，读到多少算多少，末行缺了给 null。
     *
     * [url] **原样使用、不 trim**：原版把 `GameBase.csv` 那一行的原文直接交给 `OpenRead`
     * （`:2786`），前后带空格的 URL 在桌面侧就是一次抛异常 ⇒ RESULT=3。这里若替作者把空格削掉
     * 再取，同一条语句会从 3 变成 0/1/2 —— 那是改语义，不是兼容性修补。
     */
    fun fetchVersionInfo(url: String): UpdateCheckDecision.RemoteInfo? {
        if (!isHttpScheme(url)) return null
        return try {
            val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            // 30x 交给 JDK 自己跟随（HttpURLConnection 的默认行为）；重定向到非 http 方案时
            // JDK 会拒，那一支落回 null ⇒ RESULT=3，与原版 WebClient 抛出后同解。
            connection.setRequestProperty("User-Agent", USER_AGENT)
            try {
                val code = connection.responseCode
                if (code !in 200..299) return null
                val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                try {
                    // [maxBytes] 是**两行合计**的上限：第二行只剩「总量减去第一行」的预算，
                    // 任何一行超预算都按「取回内容不对」放弃（返回 null ⇒ 决策表落 3）。
                    val first = reader.readCappedLine(maxBytes) ?: return null
                    val second = reader.readCappedLine(maxBytes - first.length)
                    UpdateCheckDecision.RemoteInfo(first, second)
                } finally {
                    runCatching { reader.close() }
                }
            } finally {
                runCatching { connection.disconnect() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {

        /** 连接超时：3 秒。原版没有这一档（`OpenRead` 无上限），移植侧的兜底之一。 */
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 3_000

        /** 读超时：5 秒。取到第一字节后允许的节奏，比连接档宽是因为慢源的首字节最贵。 */
        const val DEFAULT_READ_TIMEOUT_MS: Int = 5_000

        /**
         * 两行合计的字符上限：4 KiB。
         *
         * 原版一条 `ReadLine()` 会把整行读进内存（`:2790`），所以一个把版本名写成 100 MiB 的
         * 源在桌面上就会把模拟器撑爆 —— 那是原版的固有毛病，但移植侧同一句代码跑在 512 MiB
         * 堆限制的设备上（真机读数见 `GameHost` 的装载段注释），后果从「卡一下」变成 OOM。
         * 一行版本号的实际形状是几十字符，4 KiB 已经宽到不可能误伤。
         */
        const val DEFAULT_MAX_BYTES: Int = 4_096

        /** 标识：与 [com.eratw.port.core.update.UpdatePolicy] 的镜像下载链不共用，那条在 app 侧。 */
        const val USER_AGENT: String = "eratw-android-port/update-check"

        fun isHttpScheme(url: String): Boolean =
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
    }
}

/**
 * 单块网卡的可用性判据（纯函数，单测直接钉）：`!isLoopback && isUp`。
 *
 * **两个读数都在 runCatching 里**：个别 ROM 上 `NetworkInterface.isLoopback()` 与 `isUp()`
 * 都会抛（读网卡属性失败）；那只是「这一块不算数」，不许把整机判成没网。抽成纯函数是为了
 * 让这条兜底能被 JVM 单测钉住（真 `NetworkInterface` 造不出来，只能按可抛的读数注入）。
 */
internal fun usableNetworkInterface(isLoopback: () -> Boolean, isUp: () -> Boolean): Boolean =
    runCatching { !isLoopback() && isUp() }.getOrDefault(false)

/**
 * 带字符上限的一行读取 —— `BufferedReader.readLine()` 的等价物，多三条边界：
 * - 读到换行（`\n` / `\r\n` / 单独的 `\r`）即返回，行内容不含换行符（原版 `ReadLine` 同形）；
 * - 到流末尾且一个字符都没读到返回 **null**（原版 `ReadLine` 在 EOF 返回 null，`:2792` 判的就是它）；
 * - **超过 [cap] 个字符即放弃**（返回 null）：调用方把它当「取回内容不对」处理（决策表落 3），
 *   绝不把一条畸形长行截断成前缀混进版本判断 —— 截断后的前缀看起来仍可能是一条正常版本行。
 *
 * `cap` 是**剩余预算**（两行合计口径由调用方扣减）：`cap <= 0` 直接返回 null。
 * 内部用 `read()` 逐字符：只有两行、上限 4 KiB，比 `readLine` 的「整行进内存」便宜且可控。
 */
internal fun BufferedReader.readCappedLine(cap: Int): String? {
    if (cap <= 0) return null
    val builder = StringBuilder()
    while (builder.length < cap) {
        val c = read()
        if (c == EOF) return if (builder.isEmpty()) null else builder.toString()
        if (c == LF) return builder.toString()
        if (c == CR) {
            mark(1)
            val next = read()
            if (next != LF && next != EOF) reset()
            return builder.toString()
        }
        builder.append(c.toChar())
    }
    // 撞上上限：这一行恰好在这里结束时照常返回；后面还有内容 ⇒ 超预算 ⇒ 整行作废（null）。
    return when (val next = read()) {
        EOF, LF -> builder.toString()
        CR -> {
            mark(1)
            val after = read()
            if (after != LF && after != EOF) reset()
            builder.toString()
        }
        else -> null
    }
}

/** `BufferedReader.read()` 的三态读数：`Int`，EOF = -1（不是 Char，故比较用整型常量）。 */
private const val EOF: Int = -1
private const val LF: Int = 0x0A
private const val CR: Int = 0x0D

/** `NetworkInterface.getNetworkInterfaces()` 返回的是 JDK 的 `Enumeration`，收成表好判空。 */
private fun java.util.Enumeration<NetworkInterface>.toList(): List<NetworkInterface> {
    val out = ArrayList<NetworkInterface>()
    while (hasMoreElements()) out.add(nextElement())
    return out
}
