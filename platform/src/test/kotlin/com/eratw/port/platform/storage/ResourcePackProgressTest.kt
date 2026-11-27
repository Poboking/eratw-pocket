package com.eratw.port.platform.storage

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 素材直投的**进度读数**回归（第二十一批）。
 *
 * 玩家反馈「导入时间太长，一度以为卡了」——卡上此前只有一句进行态、没有任何读数。这批给
 * `installSource` 加了 `onProgress`，本文件钉住四条不能退的口径：
 *
 * 1. 阶段顺序真实（扫描 → 写入 → 收尾 → 完成），最后一条把分母对齐到实落数（比例到 1.0）；
 * 2. 读数只增不减（已写文件 / 已写字节都单调）——倒退的进度条比没有进度条更像坏了；
 * 3. 比例优先按字节、退化按文件数、都不可知为 null（UI 画不定态扫描带）；
 * 4. 回调坏了不许把导入带死（进度是旁路信息），跳过条目也不许把收尾卡住。
 */
class ResourcePackProgressTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    /** 内存源：按声明字节与内容长度一致造条目（`size` 用真实内容长度，便于断言字节比例）。 */
    private fun memorySource(label: String, files: Map<String, String>): MaterialSource = object : MaterialSource {
        override val label: String = label

        override fun entries(): List<MaterialEntry> = files.map { (name, content) ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            MaterialEntry(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        }

        override fun entriesWithProgress(onDiscovered: (Int) -> Unit): List<MaterialEntry> {
            val list = entries()
            // 模拟 SAF 逐目录上报：先报到一半，再报完整数（真实枚举也是一批一批地报）。
            list.take(list.size / 2).forEachIndexed { index, _ -> onDiscovered(index + 1) }
            onDiscovered(list.size)
            return list
        }
    }

    @Test
    fun `直投进度从扫描走到收尾且比例拉满`() {
        val root = tempDir("progress-bg")
        val source = memorySource(
            "背景图夹",
            mapOf(
                "resources/背景图/00/a.webp" to "AAAA",
                "resources/背景图/00/b.webp" to "BBBBB",
                "resources/背景图/BGI.csv" to "CC",
            ),
        )
        val events = ArrayList<ResourcePackProgress>()
        val report = ResourcePackStore(root).installSource(source, MaterialTarget.BACKGROUND) { events += it }

        assertEquals(3, report.installedFiles)
        assertTrue("至少要报到收尾那条", events.size >= 2)
        assertEquals(ResourcePackPhase.SCANNING, events.first().phase)
        assertEquals(ResourcePackPhase.FINISHED, events.last().phase)

        val last = events.last()
        assertEquals(last.doneFiles, last.totalFiles)
        assertEquals(last.doneBytes, last.totalBytes)
        assertEquals(1f, last.fraction)

        // 阶段不许倒退（扫描 → 写入 → 收尾 → 完成）。
        val order = events.map { it.phase.ordinal }
        assertEquals("阶段顺序必须单调", order.sorted(), order)

        // 已写字节单调不减（含正在写的那一条的分块读数）。
        val bytes = events.filter { it.phase == ResourcePackPhase.WRITING }.map { it.doneBytes }
        assertEquals("已写字节必须单调", bytes.sorted(), bytes)
    }

    @Test
    fun `扫描阶段会把已发现数报上来且按增量放行`() {
        val root = tempDir("progress-scan")
        // 30 个文件：按增量（8 条）放行的门槛必须真能看到「已发现 8 / …」这类读数，
        // 同时不能变成每个文件一次回调（那会把 UI 线程刷爆）。
        val source = memorySource(
            "BGM 夹",
            (0 until 30).associate { "博丽神社/$it.mp3" to "X" },
        )
        val events = ArrayList<ResourcePackProgress>()
        ResourcePackStore(root).installSource(source, MaterialTarget.BGM) { events += it }

        val scanning = events.filter { it.phase == ResourcePackPhase.SCANNING }
        assertTrue("扫描阶段要有读数", scanning.isNotEmpty())
        assertTrue("扫描读数里要出现「已发现 >= 8」", scanning.any { it.discovered >= 8 })
        // 已发现数单调不减，且绝不超过真实条数。
        assertEquals(scanning.map { it.discovered }.sorted(), scanning.map { it.discovered })
        assertTrue("已发现数不许超过条目总数", scanning.all { it.discovered <= 30 })
        assertTrue("扫描回调必须被节流，不能一条一个", scanning.size <= 30)
        // 扫描阶段没有分母：此刻只能画不定态。
        assertTrue("扫描阶段比例应为空", scanning.all { it.fraction == null })
    }

    @Test
    fun `比例按字节优先再退化按文件数`() {
        // 字节与文件数都可知：字节优先。
        assertEquals(
            0.4f,
            ResourcePackProgress(
                phase = ResourcePackPhase.WRITING,
                doneFiles = 1,
                totalFiles = 4,
                doneBytes = 40,
                totalBytes = 100,
            ).fraction,
        )
        // 声明总量不可知（SAF 不给 size）：退化到文件数。
        assertEquals(
            0.5f,
            ResourcePackProgress(
                phase = ResourcePackPhase.WRITING,
                doneFiles = 2,
                totalFiles = 4,
            ).fraction,
        )
        // 都没有：空（UI 画扫描带）。
        assertNull(ResourcePackProgress(phase = ResourcePackPhase.SCANNING).fraction)
        // 比例夹在 0..1（声明量撒谎时不越界）。
        assertEquals(
            1f,
            ResourcePackProgress(
                phase = ResourcePackPhase.WRITING,
                doneFiles = 4,
                totalFiles = 4,
                doneBytes = 120,
                totalBytes = 100,
            ).fraction,
        )
    }

    /** 同一个名字可以出现多次的源（测同名跳过；Map 装不下重复键）。 */
    private fun listSource(label: String, files: List<Pair<String, String>>): MaterialSource =
        object : MaterialSource {
            override val label: String = label

            override fun entries(): List<MaterialEntry> = files.map { (name, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                MaterialEntry(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
            }
        }

    @Test
    fun `跳过条目仍能收尾到完成`() {
        val root = tempDir("progress-skip")
        // 两条条目映射到同一个目标名：第二条被跳过（`同一批里有同名文件`）。
        // 走 BGM：背景图在没有 CSV 时会额外生成 自动索引.csv，那份也算落盘，会把计数搅浑。
        val source = listSource(
            "重复夹",
            listOf(
                "博丽神社/1.mp3" to "AAAA",
                "博丽神社/1.mp3" to "BBBB",
            ),
        )
        val events = ArrayList<ResourcePackProgress>()
        val report = ResourcePackStore(root).installSource(source, MaterialTarget.BGM) { events += it }

        assertEquals(1, report.installedFiles)
        assertEquals(1, report.skippedEntries)
        // 分母对齐到实落数：进度条到 100%，不是停在「2 个里写了 1 个」。
        assertEquals(ResourcePackPhase.FINISHED, events.last().phase)
        assertEquals(events.last().doneFiles, events.last().totalFiles)
        assertEquals(1f, events.last().fraction)
    }

    @Test
    fun `进度回调抛异常不许把导入带死`() {
        val root = tempDir("progress-throw")
        // BGM 走单文件投放（背景图无 CSV 时还会额外生成 自动索引.csv，那一份也算落盘）。
        val source = memorySource("正常夹", mapOf("博丽神社/0.mp3" to "AAAA"))
        val report = ResourcePackStore(root).installSource(source, MaterialTarget.BGM) {
            throw IllegalStateException("UI 那条线坏了")
        }
        assertNotNull("进度坏了也得照常落盘", report)
        assertEquals(1, report.installedFiles)
        assertTrue(File(root, "sound/游戏bgm/博丽神社/0.mp3").isFile)
    }

    @Test
    fun `声明大小缺失时整批按文件数出比例`() {
        val root = tempDir("progress-nosize")
        // SAF 提供方不给 size（-1）：分母不能只算「知道大小的那些」，否则比例早早到 100%。
        val source = object : MaterialSource {
            override val label: String = "无大小"

            override fun entries(): List<MaterialEntry> =
                listOf(MaterialEntry("博丽神社/1.mp3", -1L) { ByteArrayInputStream("AAAA".toByteArray()) })
        }
        val events = ArrayList<ResourcePackProgress>()
        ResourcePackStore(root).installSource(source, MaterialTarget.BGM) { events += it }

        val writing = events.filter { it.phase == ResourcePackPhase.WRITING }
        assertTrue("写入阶段要有读数", writing.isNotEmpty())
        assertTrue("缺 size 时声明总量应为 0（退化按文件数）", writing.all { it.totalBytes == 0L })
        assertEquals("计划条数仍要知道", 1, writing.first().totalFiles)
    }

    /** 读满 [bytesBeforeFail] 字节后抛 IOException 的源（模拟 SAF 一条读到一半中断）。 */
    private class FailingStream(private val bytesBeforeFail: Int) : java.io.InputStream() {
        private var served = 0

        override fun read(): Int = throw IOException("模拟读取中断")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= bytesBeforeFail) throw IOException("模拟读取中断")
            val n = minOf(len, bytesBeforeFail - served)
            java.util.Arrays.fill(b, off, off + n, 1)
            served += n
            return n
        }
    }

    @Test
    fun `写到一半失败也不许让进度倒退`() {
        val root = tempDir("progress-fail")
        val source = object : MaterialSource {
            override val label: String = "半路坏"

            override fun entries(): List<MaterialEntry> = listOf(
                MaterialEntry("博丽神社/1.mp3", 4096) { FailingStream(512) },
                MaterialEntry("博丽神社/2.mp3", 8) { ByteArrayInputStream("BBBBBBBB".toByteArray()) },
            )
        }
        val events = ArrayList<ResourcePackProgress>()
        val report = ResourcePackStore(root).installSource(source, MaterialTarget.BGM) { events += it }

        assertEquals("失败那条不落盘、后一条照常落", 1, report.installedFiles)
        assertEquals(1, report.skippedEntries)
        val bytes = events.filter { it.phase == ResourcePackPhase.WRITING }.map { it.doneBytes }
        // 失败条目已写的 512 字节进了「已处理」，下一条报数不许退回 0 —— 进度条不倒着走。
        assertEquals("已写字节必须单调（含半路失败）", bytes.sorted(), bytes)
        assertTrue("失败那条的已写字节要算进去", bytes.any { it > 0 })
    }
}
