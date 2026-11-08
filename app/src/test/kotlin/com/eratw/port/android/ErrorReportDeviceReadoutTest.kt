package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEEL1/L2：机型档硬件读数的**纯格式化**（无 Android 依赖，`:app` JVM 单测直接锁，口径同
 * [MemoryForensics] / [ExitForensics] 的既有测试族）。
 *
 * 采集侧（`ErrorReportCollector.deviceReadoutLines`）碰 `Build` / `ActivityManager` / `StatFs` /
 * `Settings.Secure` / 电池广播，在 JVM 里跑不了；但收件箱按机型/ROM/内存档分桶吃的是**读数形状**，
 * 那几行的标签、单位与「未知」兜底必须在纯 JVM 钉死：单位漂一格（GiB↔MiB）、小数点被宿主区域
 * 换成逗号、布尔文案改了，分桶统计就整层错位。本用例钉的正是这一层。
 *
 * 断言的是设备小节的第 4 行起（前三行 app 版本/设备/系统逐字不动，由既有报告用例与真实采集覆盖，
 * 不在纯格式化层）——[DeviceForensics.lines] 恒 11 行，本用例逐项核对。
 */
class ErrorReportDeviceReadoutTest {

    private fun readout(
        brand: String? = null,
        product: String? = null,
        fingerprint: String? = null,
        abis: List<String>? = null,
        totalRamBytes: Long? = null,
        isLowRamDevice: Boolean? = null,
        availableStorageBytes: Long? = null,
        totalStorageBytes: Long? = null,
        fontScale: Double? = null,
        densityDpi: Int? = null,
        accessibilityEnabled: Boolean? = null,
        batteryTempTenthsC: Int? = null,
    ): List<String> = DeviceForensics.lines(
        brand = brand,
        product = product,
        fingerprint = fingerprint,
        abis = abis,
        totalRamBytes = totalRamBytes,
        isLowRamDevice = isLowRamDevice,
        availableStorageBytes = availableStorageBytes,
        totalStorageBytes = totalStorageBytes,
        fontScale = fontScale,
        densityDpi = densityDpi,
        accessibilityEnabled = accessibilityEnabled,
        batteryTempTenthsC = batteryTempTenthsC,
    )

    private val gib = 1024L * 1024L * 1024L

    @Test
    fun `恒十一行且顺序写死`() {
        val lines = readout()
        assertEquals(11, lines.size)
        val labels = lines.map { it.substringBefore("：") }
        assertEquals(
            listOf(
                "厂牌(BRAND)",
                "产品(PRODUCT)",
                "ROM 标识(FINGERPRINT)",
                "支持的 ABI",
                "总内存",
                "低内存设备(isLowRamDevice)",
                "可用存储",
                "字体缩放(fontScale)",
                "屏幕密度(densityDpi)",
                "无障碍服务开关",
                "电池温度",
            ),
            labels,
        )
    }

    @Test
    fun `全取不到时每行都写未知_绝不留空也不抛`() {
        val lines = readout()
        val unknown = DeviceForensics.UNKNOWN_TEXT
        assertTrueAll(lines) { it.endsWith("：$unknown") }
    }

    @Test
    fun `OPPO_PLD110_一档完整读数逐字对齐`() {
        val lines = readout(
            brand = "OPPO",
            product = "PLD110",
            fingerprint = "OPPO/OP4E79/OP4E79:13/TP1A.build123:user/release-keys",
            abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
            totalRamBytes = 8 * gib,
            isLowRamDevice = false,
            availableStorageBytes = 12 * gib,
            totalStorageBytes = 128 * gib,
            fontScale = 1.15,
            densityDpi = 480,
            accessibilityEnabled = false,
            batteryTempTenthsC = 295,
        )
        assertEquals(
            listOf(
                "厂牌(BRAND)：OPPO",
                "产品(PRODUCT)：PLD110",
                "ROM 标识(FINGERPRINT)：OPPO/OP4E79/OP4E79:13/TP1A.build123:user/release-keys",
                "支持的 ABI：arm64-v8a,armeabi-v7a,armeabi（3 项）",
                "总内存：8.0GiB",
                "低内存设备(isLowRamDevice)：否",
                "可用存储：12.0GiB / 共 128.0GiB",
                "字体缩放(fontScale)：1.15",
                "屏幕密度(densityDpi)：480dpi",
                "无障碍服务开关：否",
                "电池温度：29.5℃",
            ),
            lines,
        )
    }

    @Test
    fun `整数运算的_GiB_不引浮点误差`() {
        // 7.3GiB → 十分位整数算法：bytes*10/GIB，不靠浮点格式化（避免 7.3000001 之类）。
        val bytes = (73L * gib) / 10L // 7.3 GiB
        val line = readout(totalRamBytes = bytes).first { it.startsWith("总内存：") }
        assertEquals("总内存：7.3GiB", line)
    }

    @Test
    fun `布尔三态_未知_是_否`() {
        assertEquals("低内存设备(isLowRamDevice)：是", readout(isLowRamDevice = true).first { it.startsWith("低内存") })
        assertEquals("低内存设备(isLowRamDevice)：否", readout(isLowRamDevice = false).first { it.startsWith("低内存") })
        assertEquals("低内存设备(isLowRamDevice)：未知", readout(isLowRamDevice = null).first { it.startsWith("低内存") })
    }

    @Test
    fun `空白或空集合也走未知`() {
        // FINGERPRINT 整串为空、ABIs 空表：不是「没有这一行」，而是这一行写未知。
        val lines = readout(fingerprint = "   ", abis = emptyList())
        assertTrue(lines.any { it == "ROM 标识(FINGERPRINT)：未知" })
        assertTrue(lines.any { it == "支持的 ABI：未知" })
    }

    @Test
    fun `可用存储缺总量时只报可用`() {
        assertEquals(
            "可用存储：5.0GiB",
            readout(availableStorageBytes = 5 * gib).first { it.startsWith("可用存储：") },
        )
        // 可用读不到但总量读得到：仍报「共」，可用退未知（不是整行消失）。
        assertEquals(
            "可用存储：${DeviceForensics.UNKNOWN_TEXT} / 共 128.0GiB",
            readout(availableStorageBytes = null, totalStorageBytes = 128 * gib)
                .first { it.startsWith("可用存储：") },
        )
    }

    @Test
    fun `字号缩放与电池温度固定小数点不受区域影响`() {
        // 选择器口径与金标准用例的标签一致：「字体缩放(fontScale)：」「屏幕密度(densityDpi)：」
        // 带括号后缀，前缀取到括号前为止。
        assertEquals("字体缩放(fontScale)：1.00", readout(fontScale = 1.0).first { it.startsWith("字体缩放") })
        assertEquals("字体缩放(fontScale)：0.90", readout(fontScale = 0.9).first { it.startsWith("字体缩放") })
        assertEquals("电池温度：25.0℃", readout(batteryTempTenthsC = 250).first { it.startsWith("电池温度") })
    }

    @Test
    fun `非法密度值退未知`() {
        assertEquals(
            "屏幕密度(densityDpi)：${DeviceForensics.UNKNOWN_TEXT}",
            readout(densityDpi = 0).first { it.startsWith("屏幕密度") },
        )
    }

    private fun <T> assertTrueAll(items: List<T>, pred: (T) -> Boolean) {
        org.junit.Assert.assertTrue(items.joinToString("|"), items.all(pred))
    }
}
