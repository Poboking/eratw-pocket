package com.eratw.port.platform.image

import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.image.PngImage
import com.eratw.port.render.memory.PixelPreflight
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图形装载那一族的 **Java 堆分配前预检**闸（lane oomalloc，2026-11-05）。
 *
 * 钉的是端点里还活着的那一族签名（0.2.38 / 0.2.39，512 MiB 挡，free 只剩几百 KB～4 MiB）：
 * - `BitmapImageDecoder.decode` 的 `IntArray(w*h)`（core 侧 `GraphicsMethods.kt:1756-1761`
 *   只 `catch (e: Exception)`，`OutOfMemoryError` 是 Error ⇒ 直接穿到 VM 线程）；
 * - `ResourceCsvHost.readBytes` 的整份 `ByteArray`
 *   （0.2.38 那三份装载期报告的帧名原样是 `ResourceCsvHost.readBytes:44`）。
 *
 * 两条判式都用装置性质（`PixelPreflight` 的两条覆写性质，口径同
 * `GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY`）把读数拉到真机贴顶挡位，断言
 * **「预算不够 ⇒ 走既有降级出口 ⇒ 不抛」**；android.jar 是桩，所以真解码
 * （`BitmapFactory.decodeByteArray` 恒 null）那一段只能钉源码面，见最后两条用例。
 */
class GraphicsDecodePreflightGateTest {

    private val max512 = 536_870_912L
    private val max256 = 268_435_456L

    @After
    fun clearOverrideProperties() {
        System.clearProperty(PixelPreflight.MAX_MEMORY_OVERRIDE_PROPERTY)
        System.clearProperty(PixelPreflight.USED_OVERRIDE_PROPERTY)
        System.clearProperty("eratw.graphics.pixelBudgetBytes")
    }

    private fun deviceTier(usedBytes: Long, maxBytes: Long = max512) {
        System.setProperty(PixelPreflight.MAX_MEMORY_OVERRIDE_PROPERTY, maxBytes.toString())
        System.setProperty(PixelPreflight.USED_OVERRIDE_PROPERTY, usedBytes.toString())
    }

    /** 真机 084134（1,440,016 要 / 974,768 空闲）那一档的已用读数。 */
    private val toppedOutUsed = max512 - 974_768L

    // ---- 解码侧：像素落地的预检 ----

    @Test
    fun `贴顶挡位下一张六百见方的立绘判要不起`() {
        deviceTier(toppedOutUsed)
        assertFalse("1.44 MiB 像素阵列在只剩 951 KB 的堆上必须拒", BitmapImageDecoder.pixelLandingAllowed(600, 600))
        assertFalse(BitmapImageDecoder.pixelLandingAllowed(500, 500))
        assertFalse(BitmapImageDecoder.pixelLandingAllowed(2_000, 1_000))
        // 反面钉子：装得进空闲的小件**不许**被误拦（误拦的代价是屏上白缺一块）。
        assertTrue(BitmapImageDecoder.pixelLandingAllowed(1, 1))
    }

    @Test
    fun `空闲充足时解码预检一律放行`() {
        deviceTier(100L * MIB)
        assertTrue(BitmapImageDecoder.pixelLandingAllowed(600, 600))
        assertTrue(BitmapImageDecoder.pixelLandingAllowed(8_000, 4_000))
        assertTrue(BitmapImageDecoder.pixelLandingAllowed(1, 1))
    }

    @Test
    fun `单张超过本挡像素预算的一件在 256 MiB 挡判要不起`() {
        // 256 MiB 挡的像素预算 = 64 MiB：122 MiB 的一张工作表要「自己那份 + 一整个预算档」
        // = 186 MiB 空闲，而 used=100 MiB 时只剩 156 MiB ⇒ 拒（加码腿，理由同 9a5225fc 之后
        // 「Java 堆侧不随档位放宽」）。这一条与旧口径同读数，改的只是它不再写成永久拒。
        deviceTier(100L * MIB, max256)
        assertFalse(BitmapImageDecoder.pixelLandingAllowed(8_000, 4_000))
        assertTrue(BitmapImageDecoder.pixelLandingAllowed(1_000, 1_000))
    }

    @Test
    fun `越预算档的那张语料大图在空闲充足时不再被永久拒`() {
        // 4.981 语料唯一越过 512 MiB 挡预算（128 MiB）的一张：
        // `resources/汎用モブ子dairi版/汎用モブ子dairi版.webp` 6964x6191 = 164.5 MiB ARGB，
        // 装载期 CSV 必经（旧写法的绝对否决 = 这张图任何时候都解不出来，而 core 的解码失败支
        // 照样按同尺寸分配一份全透明 IntArray，内存一分没省、图永久没了）。
        // 三形之一「空闲充足时正常解出」：注入读数走 [BitmapImageDecoder.pixelLandingAllowed]，
        // 真机「解出来」那一段 android.jar 是桩、登记为需真机验证。
        deviceTier(69L * MIB)
        assertTrue(
            "空闲 443 MiB 时这张 164.5 MiB 的图必须放行，" +
                PixelPreflight.readout(BitmapImageDecoder.pixelBytes(6_964, 6_191)),
            BitmapImageDecoder.pixelLandingAllowed(6_964, 6_191),
        )
        // 三形之二「贴顶才降级」：084134 现场（空闲只剩 951 KB）照样拒。
        deviceTier(toppedOutUsed)
        assertFalse(BitmapImageDecoder.pixelLandingAllowed(6_964, 6_191))
        // 中途也要拒：空闲 280 MiB 装得下它本身，但装不下「它 + 一整个预算档」。
        deviceTier(max512 - 280L * MIB)
        assertFalse(BitmapImageDecoder.pixelLandingAllowed(6_964, 6_191))
    }

    @Test
    fun `读字节那一口只走头寸腿不吃单件上限`() {
        // ResourceCsvHost.readBytes 的两条来路里，CSV 正文读不到 = 整张表的 sprite 一起没
        // （ResourceContents 的「打开失败，已跳过」支），所以这一口不许被像素单位的
        // 加码腿碰：200 MiB 的一份压缩字节在 512 MiB 挡空闲充足时照常读。
        deviceTier(100L * MIB)
        assertTrue("头寸够就该读得到，单件上限腿不归这里管", ResourceCsvHost.bytesReadable(200L * MIB))
        // 头寸腿仍然是无条件的：贴顶时连 1.44 MB 都拒（原报那一族）。
        deviceTier(toppedOutUsed)
        assertFalse(ResourceCsvHost.bytesReadable(1_440_000L))
    }

    @Test
    fun `尺寸与字节的换算与 GraphicsBuffer 同尺`() {
        assertEquals(1_440_000L, BitmapImageDecoder.pixelBytes(600, 600))
        assertEquals(1_000_000L, BitmapImageDecoder.pixelBytes(500, 500))
        assertEquals(0L, BitmapImageDecoder.pixelBytes(0, 0))
    }

    // ---- 读字节侧：整份 ByteArray 的预检（这条是真跑起来的，纯文件 IO）----

    private fun hostWith(root: File): ResourceCsvHost =
        ResourceCsvHost(root, object : ImageDecoderHost {
            override fun decode(path: String, bytes: ByteArray): PngImage? = null
        })

    @Test
    fun `readBytes 贴顶时按读不到降级而不是抛`() {
        val root = Files.createTempDirectory("eratw-preflight-csv").toFile()
        try {
            val resources = File(root, ResourceCsvHost.RESOURCES_DIR).apply { mkdirs() }
            val csv = File(resources, "list.csv").apply { writeText("A,b.webp,0,0,16,16\n") }
            // 空闲只剩 8 B 的挡位：连一份几十字节的 CSV 都判要不起 —— 出口是「打开失败，已跳过」
            // 那一条既有告警支（ResourceContents.load 里判 null），不是抛出。
            deviceTier(max512 - 8L)
            val host = hostWith(root)
            assertNull(host.readBytes("resources/list.csv"))
            assertTrue(csv.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `readBytes 正常挡位读数一字不变`() {
        val root = Files.createTempDirectory("eratw-preflight-ok").toFile()
        try {
            val resources = File(root, ResourceCsvHost.RESOURCES_DIR).apply { mkdirs() }
            val text = "TW_title001,title.webp,0,16,1041,16\n"
            File(resources, "list.csv").writeText(text)
            deviceTier(100L * MIB)
            val host = hostWith(root)
            val read = host.readBytes("resources/list.csv")
            assertNotNull(read)
            assertEquals(text, String(read!!))
            // 不存在的文件仍按「读不到」答 null（不抛、不返回空数组冒充成功）。
            assertNull(host.readBytes("resources/没有这个.csv"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `字节数换算与判式共用一把尺`() {
        deviceTier(toppedOutUsed)
        assertFalse(ResourceCsvHost.bytesReadable(1_440_000L))
        assertTrue(ResourceCsvHost.bytesReadable(0L))
        deviceTier(100L * MIB)
        assertTrue(ResourceCsvHost.bytesReadable(1_440_000L))
    }

    // ---- 桩环境下跑不到的解码本体：源码面钉子 ----

    private fun mainSource(relative: String): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(workingDir, "src/main/kotlin/com/eratw/port/platform/image/$relative"),
            File(workingDir, "platform/src/main/kotlin/com/eratw/port/platform/image/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("定位不到 platform 主源 $relative（尝试 ${candidates.map { it.path }}）")
        return file.readText()
    }

    /** 去掉整行 `//` 注释：源码面钉子只管**代码**，注释里引用旧写法不该把钉子判红。 */
    private fun codeOnly(source: String): String =
        source.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }

    @Test
    fun `解码像素分配点在预检之后且被兜住`() {
        val body = mainSource("BitmapImageDecoder.kt")
        val code = codeOnly(body)
        val allocate = code.indexOf("val pixels = IntArray(w * h)")
        val precheck = code.indexOf("if (!pixelLandingAllowed(w, h))")
        assertTrue("解码像素分配点没找到（改动分配形态时同步本钉子）", allocate > 0)
        assertTrue("预检必须在 IntArray 分配**之前**，事后收账救不了这一笔", precheck in 0 until allocate)
        assertTrue("分配本体要被兜住：OutOfMemoryError 不是 Exception", code.contains("catch (t: Throwable)"))
        assertFalse(
            "本类不许用只 catch Exception 的写法（那正是穿到 VM 线程的那道缝）",
            Regex("""catch \(e: Exception\)""").containsMatchIn(code),
        )
    }

    @Test
    fun `预检不足的那一支落回既有缺图支而不是抛出`() {
        // 三形之三「OOM 不穿 VM 线程」的入口面：预检拒掉的这一笔必须走「解不出来」那一条
        // （返回 null ⇒ GCREATEFROMFILE / GLOAD 折成 0、CSV 行走告警支），
        // 抛出就等于把一次「要不起」升级成整局没了 —— 与本路要防的原 bug 同形。
        val code = codeOnly(mainSource("BitmapImageDecoder.kt"))
        val allocate = code.indexOf("val pixels = IntArray(w * h)")
        val rejectAt = code.indexOf("if (!pixelLandingAllowed(w, h))")
        assertTrue("预检支必须在分配之前", rejectAt in 0 until allocate)
        val span = code.substring(rejectAt, minOf(code.length, rejectAt + 400))
        assertTrue("预检不足必须按缺图降级返回 null", span.contains("return null"))
        assertFalse("预检不足不许抛出 Error/Exception", span.contains("throw "))
    }

    @Test
    fun `取图口的像素落地也兜住 OOM`() {
        val code = codeOnly(mainSource("SpriteBitmapSource.kt"))
        val read = code.indexOf("val pixels = try {")
        assertTrue("bitmapOf 的像素落地读取要被 try 罩住（首访问解码 / 降级晋升都是 Java 堆份）", read > 0)
        val span = code.substring(read, minOf(code.length, read + 400))
        assertTrue(span.contains("resident ?: buffer.pixels"))
        assertTrue(span.contains("catch (t: OutOfMemoryError)"))
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
