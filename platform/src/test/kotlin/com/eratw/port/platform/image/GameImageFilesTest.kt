package com.eratw.port.platform.image

import java.io.File
import java.util.Collections
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
}
