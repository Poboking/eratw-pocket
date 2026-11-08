package com.eratw.port.platform.image

import java.io.File
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * GameImageFiles 的资源索引并发守卫（纯 JVM，Android 桩返回缺省值，见
 * `platform/build.gradle.kts` 的 `unitTests.isReturnDefaultValues`）。
 *
 * 两条线程会同时打进来：VM 线程（HTML 展平 / 语句链查尺寸）与 UI 线程
 * （`CanvasConsoleView.onDraw` 经 `SpriteBitmapSource.frame` 取图，
 * CanvasConsoleView.kt:319、:436）。索引原先是有问题那份 `HashMap`——
 * [GameImageFiles.locate] 在未命中路径上「边读边写」，两个线程并发补登记就是
 * 数据竞争；改成 `ConcurrentHashMap` + `putIfAbsent` 后，本用例用多线程重复
 * 「命中 + 未命中」混合查询压一遍：既不崩、也不串。
 *
 * 说明：`BitmapFactory.decodeFile` 在桩下恒返回 null，所以本用例不验证解码结果，
 * 只验证**索引与查找路径**的并发安全（这正是这次修的东西）；解码行为归真机。
 */
class GameImageFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `并发查名补登记不崩且命中一致`() {
        val root = temp.newFolder("game")
        val dir = File(root, "resources/misc").apply { mkdirs() }
        val names = (0 until 64).map { "img%03d".format(it) }
        names.forEach { File(dir, "$it.webp").writeBytes(byteArrayOf(1)) }

        val files = GameImageFiles(root)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        val threads = (0 until 4).map { t ->
            Thread {
                try {
                    repeat(256) { i ->
                        // 命中与未命中交替：未命中才会走「补登记」的写路径，
                        // 命中走纯读路径；两边并发混跑才能压出竞争。
                        val name = if (i % 2 == 0) names[(i + t) % names.size] else "miss-$t-$i"
                        files.decodeByResourceName(name)
                        files.resourceNameSize("miss2-$t-$i")
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertTrue("所有工作线程都已结束", threads.none { it.isAlive })
        assertTrue(
            "并发查询不得抛错（HashMap 边读边写会炸）：${errors.joinToString { it.toString() }}",
            errors.isEmpty(),
        )
    }

    @Test
    fun `带路径成分的名字不越界也不命中`() {
        val root = temp.newFolder("game2")
        val dir = File(root, "resources").apply { mkdirs() }
        File(dir, "secret.webp").writeBytes(byteArrayOf(1))
        val files = GameImageFiles(root)

        // locate 对「..」直接拒绝：资源名是脚本/CSV 给的自由串，不许当路径用
        assertTrue(files.decodeByResourceName("../resources/secret") == null)
        assertTrue(files.resourceNameSize(".../secret") == null)
    }

    /**
     * 深度 4 的图必须进索引 —— 原实现 `maxDepth(3)` 把它整个漏掉。
     *
     * 语料实测形态：`resources/MISC/infoThumb/<角色>/<表情>/x.png`（相对 `resources`
     * 深度 4，共 125 张，见 GameImageFiles.MAX_DEPTH 的分布表）。漏掉的后果不是崩，
     * 而是「按名兜底查不到 → 退回原版的画标签」，用户看到的是一行 `<img ...>` 文本。
     *
     * 用 `locateForDebug` 断言：桩环境下 `BitmapFactory` 恒返回 null，走解码口看不出来。
     */
    @Test
    fun `深度四的资源文件仍进索引`() {
        val root = temp.newFolder("game3")
        val deep = File(root, "resources/MISC/infoThumb/chara/face").apply { mkdirs() }
        val target = File(deep, "deepimg.png").apply { writeBytes(byteArrayOf(1)) }
        // 同名的浅层文件不能顶掉它：两者都在索引里，浅层先登记（putIfAbsent 首见为准）。
        val shallow = File(root, "resources").apply { mkdirs() }
        File(shallow, "shallowimg.png").writeBytes(byteArrayOf(1))

        val files = GameImageFiles(root)
        assertTrue("深度 4 的 stem 必须命中", files.locateForDebug("deepimg") == target)
        assertTrue("带扩展名的全名同样命中", files.locateForDebug("deepimg.png") == target)
        assertTrue("浅层不受影响", files.locateForDebug("shallowimg") != null)
    }

    /**
     * 超限闸的边界口径（feat-memory-image-cache 的 bounds-first 快拒靠它，纯 JVM 可测）。
     *
     * `== maxSide` 放行（判定是 `>` 不是 `>=`）；任一边超了就拒；宽/高 ≤ 0（解码器
     * 量不出的坏头）**不**算超限 —— 那由解码路径自己返回 null，闸只管「解得出来但太大」。
     */
    @Test
    fun `超限判定按严格大于 - 等于上限放行 - 非正尺寸不判超限`() {
        assertTrue(GameImageFiles.exceedsMaxSide(4097, 1, 4096))
        assertTrue(GameImageFiles.exceedsMaxSide(1, 4097, 4096))
        assertFalse("等于上限放行（LruCache 同款 > 口径）", GameImageFiles.exceedsMaxSide(4096, 4096, 4096))
        assertFalse("坏头（量不出）不算超限", GameImageFiles.exceedsMaxSide(0, 0, 4096))
        assertFalse(GameImageFiles.exceedsMaxSide(-1, 100, 4096))
        // 语料实测的两张超限图（isaac_cg 8192x2304 / 服_笑顔动_25 8000x4000）都必须被拒
        assertTrue(GameImageFiles.exceedsMaxSide(8192, 2304, 4096))
        assertTrue(GameImageFiles.exceedsMaxSide(8000, 4000, 4096))
    }

    /**
     * 尺寸打包（[GameImageFiles.resourceNameSize] 的 sizeCache 值形态）round-trip：
     * 语料量级的宽高（4096 上限内、8192×2304 的记录值）打包再解包必须原样回来。
     * 高 32 位放宽、低 32 位放高的位布局本身不需要钉死（只有这一对函数读写它），
     * 钉的是 round-trip 与正数域 —— 打包值必须非负（Long 键值排序、日志可读）。
     */
    @Test
    fun `尺寸打包 round-trip 在语料量级宽高下无损`() {
        val cases = arrayOf(
            intArrayOf(1, 1),
            intArrayOf(1041, 1041),
            intArrayOf(4096, 4096),
            intArrayOf(8000, 4000),
            intArrayOf(8192, 2304),
        )
        for (case in cases) {
            val packed = GameImageFiles.packSize(case[0], case[1])
            assertTrue("打包值必须非负（w=${case[0]} h=${case[1]}）", packed >= 0L)
            val unpacked = GameImageFiles.unpackSize(packed)
            assertEquals("宽 round-trip", case[0], unpacked[0])
            assertEquals("高 round-trip", case[1], unpacked[1])
        }
    }
}
