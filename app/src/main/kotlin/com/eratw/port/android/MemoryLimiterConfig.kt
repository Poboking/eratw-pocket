package com.eratw.port.android

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

/**
 * Android 17（API 37）MemoryLimiter 的**设备档位表**（OOM-B 波，2026-11-08）。
 *
 * ## 为什么要读设备上的表
 *
 * `RunTrail.memoryLimiterBudgetMiB` 的硬编码硬表复刻的是 AOSP **标准**平台档
 * （Android 17 官方文档「标准平台限制」表，2026-11-08 逐值核对一致）。但平台允许厂商
 * 覆写档位表：AOSP android-17.0.0_r1 `MemoryLimiter.java` 的运行期配置路径
 * `CONFIG_PATH = "/vendor/etc/memory-limiter-config.xml"`（读取经 `Injector.configFile()`
 * → `getConfiguration(file, memTotal)`）；官方文档把标准配置文件写在
 * `/system/etc/memory-limiter-config.xml`。两路都试：vendor 是服务实读路径，system 是
 * 文档口径的标准档落点，任一路读到的标准表都与硬表逐值同源（见 [readDevice]）。
 * 覆写过档位的机型上，硬表算出来的预算可能偏高（晚收 ⇒ 进程被 LMK 杀、无栈轨迹）或偏低
 * （早收 ⇒ 多花几次重解）。
 *
 * ## 判据口径（与平台实现一致）
 *
 * 每个 `<limitSet>` 一档，`minimumRequiredMemTotal` 是**进入该档的最低 RAM**（MiB，平台
 * 常量 MB = 1024×1024）。选择规则 = 满足 `minimumRequiredMemTotal ≤ totalRamMiB` 的**最大**
 * 那档；一档都不满足（设备 RAM 低于表里最小门槛）⇒ null = MemoryLimiter 不适用。
 * android-17.0.0_r1 `MemoryLimiter.getConfiguration(List<LimitSet>, long)` 只用最小门槛
 * 比较、**不校验 `maximumAllowedMemTotal`**（该字段在标准表里用于表意），本实现跟随
 * 实现行为。平台原实现只用到内存两项，swap 两项读下来保留但本波不参与判式
 * （CONTRACT-OOM-B §5）。
 *
 * ## TODO(verify)（卡点：无 Android 17 真机，现有设备全 API 35）
 *
 * - 应用 UID 读 `/vendor/etc/memory-limiter-config.xml` 是否被 SELinux 拒绝；
 *   被拒时 [readDevice] 回落 `/system/etc/...`，两路都读不到才回落硬编码表。
 * - 与 `adb shell am memory-limiter status` 的 visibleMem/notVisibleMem 实读对账。
 */
object MemoryLimiterConfig {

    /** 厂商覆写路径（AOSP r1 `MemoryLimiter.java` 的 CONFIG_PATH 指向 vendor）。 */
    const val VENDOR_PATH: String = "/vendor/etc/memory-limiter-config.xml"

    /** 官方文档口径的标准档路径（vendor 读不到时试这一路；读到的标准表与硬表逐值同源）。 */
    const val SYSTEM_PATH: String = "/system/etc/memory-limiter-config.xml"

    /** 一档限制：RAM 门槛（MiB）+ 可见 / 不可见进程的 mem 上限（MiB）+ swap 两项（MiB）。 */
    data class LimitSet(
        val minimumRequiredMemTotalMiB: Long,
        val memVisibleMiB: Long,
        val memNotVisibleMiB: Long,
        val swapVisibleMiB: Long,
        val swapNotVisibleMiB: Long,
    )

    /** 档位表来源（trace 行的 `src=` 标记）。 */
    enum class Origin(val label: String) {
        VENDOR("vendor"),
        SYSTEM("system"),
    }

    /** [readDevice] 的读数：档位表 + 来源。 */
    data class DeviceConfig(val origin: Origin, val sets: List<LimitSet>)

    /**
     * 解析一份 `memory-limiter-config.xml`，返回 `limitSet` 列表（按文件顺序）。
     *
     * 口径跟随平台（android-17.0.0_r1 `MemoryLimiter.getConfiguration(file, memTotal)`）：
     * - `<version>` 必须是 1（缺失 / 其它值 ⇒ 平台整份拒绝，这里同样给空表）；
     * - 每个 `<limitSet>` 的五个数值字段（`minimumRequiredMemTotal` / `memVisible` /
     *   `memNotVisible` / `swapVisible` / `swapNotVisible`）全部必需（平台逐个 `longValue()`，
     *   缺一个就是 NPE / NumberFormatException ⇒ 整份配置作废）。故**任一条目坏 ⇒ 整表空**，
     *   不拿半张表当预算；
     * - 字段是**同名子元素**（官方文档的 XML 格式 / `memory-limiter-config.xsd`）；
     *   `maximumAllowedMemTotal` 不参与运行期选择（见类 KDoc）。
     *
     * 解析失败 / 空表 ⇒ 调用方按「这一路读不出配置」处理。
     */
    fun parse(xml: String): List<LimitSet> = runCatching<List<LimitSet>> {
        val factory = DocumentBuilderFactory.newInstance()
        // 设备上的系统文件不该带 DTD；支持该 feature 的实现顺手关掉外部实体。
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val version = doc.getElementsByTagName("version").item(0)?.textContent?.trim()?.toIntOrNull()
        if (version != 1) return@runCatching emptyList()
        val nodes = doc.getElementsByTagName("limitSet")
        if (nodes.length == 0) return@runCatching emptyList()
        val sets = ArrayList<LimitSet>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: return@runCatching emptyList()
            sets.add(limitSetOf(el) ?: return@runCatching emptyList())
        }
        sets
    }.getOrDefault(emptyList())

    /**
     * 选择适用档：`minimumRequiredMemTotal ≤ totalRamMiB` 里门槛最大的那一档。
     * 一档都不满足（含空表）⇒ null = MemoryLimiter 不适用。
     */
    fun select(totalRamMiB: Long, sets: List<LimitSet>): LimitSet? =
        sets.filter { it.minimumRequiredMemTotalMiB <= totalRamMiB }
            .maxByOrNull { it.minimumRequiredMemTotalMiB }

    /**
     * 读设备上的档位表：先 [VENDOR_PATH]（平台实读路径），读不到 / 解析不出条目再试
     * [SYSTEM_PATH]（文档口径的标准档落点）；两路都没有 ⇒ null（调用方回落硬编码标准档）。
     *
     * @param readFile 路径 → 文本；缺省读真实系统文件（测试注入假体，保证纯 JVM 可测）。
     */
    fun readDevice(readFile: (String) -> String? = ::readSystemFile): DeviceConfig? {
        readFile(VENDOR_PATH)?.let { parse(it) }?.takeIf { it.isNotEmpty() }
            ?.let { return DeviceConfig(Origin.VENDOR, it) }
        readFile(SYSTEM_PATH)?.let { parse(it) }?.takeIf { it.isNotEmpty() }
            ?.let { return DeviceConfig(Origin.SYSTEM, it) }
        return null
    }

    /** 读系统文件；不存在 / 读失败（含 SELinux 拒绝）⇒ null，不外抛。 */
    private fun readSystemFile(path: String): String? = runCatching {
        val f = java.io.File(path)
        if (f.isFile) f.readText() else null
    }.getOrNull()

    private fun limitSetOf(el: Element): LimitSet? {
        val min = longField(el, "minimumRequiredMemTotal") ?: return null
        val visible = longField(el, "memVisible") ?: return null
        val notVisible = longField(el, "memNotVisible") ?: return null
        val swapVisible = longField(el, "swapVisible") ?: return null
        val swapNotVisible = longField(el, "swapNotVisible") ?: return null
        return LimitSet(
            minimumRequiredMemTotalMiB = min,
            memVisibleMiB = visible,
            memNotVisibleMiB = notVisible,
            swapVisibleMiB = swapVisible,
            swapNotVisibleMiB = swapNotVisible,
        )
    }

    /** 读同名子元素的文本；缺失 / 不是非负整数 ⇒ null（调用方整份拒绝）。 */
    private fun longField(el: Element, name: String): Long? {
        val kids = el.getElementsByTagName(name)
        val node = if (kids.length == 0) null else kids.item(0) as? Element
        return node?.textContent?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
    }
}
