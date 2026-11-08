package com.eratw.port.platform.image

import com.eratw.port.core.host.RuntimeAux
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FEEL1-L1「未命中不在调用线程解码」的行为面 + 源码面钉子。
 *
 * ## 钉的病灶
 *
 * `frameOf` 缓存未命中时原本在**调用线程**（= `onDraw` 那条 UI 线程）同步解码
 * （CONTRACT-FEEL1-3LANE §2 L1 现场，SpriteBitmapSource.kt:579-589 的未命中支现在是
 * 「排解码槽 + 返回 null」）。本文件把处置刀的四个可判事实钉住：
 * 1. 未命中支**不在调用线程解码**（行为面：解码线程名读数 + 排队读数；两条都可证伪 ——
 *    把 [SpriteBitmapSource.frameOf] 的未命中支短路回 `decodeFileResource(resource)`
 *    同步解码，第 1/2/3 条立刻红，见每条的「短路即红」注）；
 * 2. 同名在途只排一次，且 [SpriteBitmapSource.prefetchAsync] 与未命中支**共用同一条队列**
 *    （没有第二张表）；
 * 3. 队列满按**拒收**并计数（溢出策略：拒收不丢已排队，理由在 DECODE_QUEUE_CAPACITY KDoc）；
 * 4. 异步失败照样进 [FailedLookupCache] 负缓存，TTL 判式一字未动。
 *
 * ## 纯 JVM 口径（为什么能构造真对象）
 *
 * 本模块 `unitTests.isReturnDefaultValues = true`：`Handler` / `Looper` / `LruCache` /
 * `BitmapFactory` 在桩里全部**返回缺省值不抛** ⇒ `SpriteBitmapSource` 可以真构造、解码槽线程
 * 可以真起跑；`BitmapFactory.decodeFile` 恒 null ⇒ 桩里**没有成功解码路径**，「解出来 →
 * 投主线程通知」那半段只能由源码面钉子（第 5 组用例）钉字面形状，真机读数在交付 TODO(verify)。
 * 线程归属用两条既有诊断读数（[SpriteBitmapSource.lastDecodeThreadName] /
 * [SpriteBitmapSource.decodeSlotReadout]），时钟用构造注入的假钟：解码线程撞钟时挂起
 * （[CountDownLatch] 带 8 s 硬上限，失败也不留僵尸线程），调用线程撞钟时照常给数。
 */
class SpriteBitmapSourceDecodeSlotTest {

    private val sources = ArrayList<SpriteBitmapSource>()
    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        for (s in sources) runCatching { s.close() }
        for (r in roots) runCatching { r.deleteRecursively() }
    }

    /** 空资源根：`GameImageFiles` 索引扫不到任何文件 ⇒ 按名解码恒失败（桩里唯一可驱动的分支）。 */
    private fun newSource(
        clock: () -> Long = { STUB_NOW },
        gateOnDecodeThread: CountDownLatch? = null,
    ): SpriteBitmapSource {
        val root = File(createTempDirFile(), "eroot-${roots.size}")
        root.mkdirs()
        roots.add(root)
        val blockingClock: () -> Long = {
            if (gateOnDecodeThread != null && Thread.currentThread().name == DECODE_THREAD_NAME) {
                gateOnDecodeThread.await(8, TimeUnit.SECONDS)
            }
            clock()
        }
        val src = SpriteBitmapSource(RuntimeAux(), GameImageFiles(root), clockMs = blockingClock)
        sources.add(src)
        return src
    }

    private fun createTempDirFile(): File =
        File(System.getProperty("java.io.tmpdir"), "eratw-decodeslot-${System.nanoTime()}")

    private fun awaitUntil(timeoutMs: Long = 3_000L, pred: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (pred()) return
            Thread.sleep(5)
        }
        throw AssertionError("等待条件超时（${timeoutMs}ms）")
    }

    // ------------------------------------------------------------------
    // 1) 未命中不在调用线程解码（可证伪核心）
    // ------------------------------------------------------------------

    /**
     * 解码线程被假钟挂起时，`frameOf` 未命中**当帧返回 null 且不留任何解码痕迹**。
     * 短路即红：未命中支改回同步解码 ⇒ `decodeFileResource` 在调用线程上跑，
     * `lastDecodeThreadName` 在返回前就被写成测试线程名，第一条断言红。
     */
    @Test
    fun `未命中当帧返回空且不落任何调用线程解码痕迹`() {
        val gate = CountDownLatch(1)
        val src = newSource(gateOnDecodeThread = gate)
        try {
            assertNull(src.frameOf("未命中-a"))
            assertNull("未命中支若在调用线程解码，这里已经写着调用线程名", src.lastDecodeThreadName())
        } finally {
            gate.countDown()
        }
    }

    /**
     * 解码只发生在自有槽线程上（线程名 = `eratw-img-decode`，不是 `main`/JUnit 线程）。
     * 短路即红：同步解码时该读数等于调用线程名。
     */
    @Test
    fun `解码跑在eratw-img-decode槽线程而不是调用线程`() {
        val src = newSource()
        assertNull(src.frameOf("未命中-b"))
        awaitUntil { src.lastDecodeThreadName() != null }
        assertEquals(DECODE_THREAD_NAME, src.lastDecodeThreadName())
        assertNotEquals(Thread.currentThread().name, src.lastDecodeThreadName())
    }

    // ------------------------------------------------------------------
    // 2) 同名在途只排一次 + 预取与未命中共用同一槽
    // ------------------------------------------------------------------

    /**
     * 解码线程挂在钟上时（条目已出队、在途未摘）：同名的第二次 `frameOf` 与
     * [SpriteBitmapSource.prefetchAsync] 都被在途台账去重 —— 排队读数恒 0、在途恒 1、
     * 丢弃恒 0。这一条同时钉「预取与未命中补解是**同一条队列**」：两条来路进的是
     * 同一个 [DecodeQueue]，不存在第二张表。
     * 短路即红：同步解码时首调就完成（在途 0），第二次调用被**负缓存**拦（TTL 内）
     * 或再次同步解码 —— 稳态读数 (0,1,0) 取不到。
     */
    @Test
    fun `同名在途只排一次且预取与未命中共用一条队列`() {
        val gate = CountDownLatch(1)
        val src = newSource(gateOnDecodeThread = gate)
        try {
            assertNull(src.frameOf("dup-1"))
            awaitUntil { val r = src.decodeSlotReadout(); r.second == 1 && r.first == 0 }
            assertNull(src.frameOf("dup-1"))
            src.prefetchAsync("dup-1")
            val readout = src.decodeSlotReadout()
            assertEquals("去重后不该多占坑", Triple(0, 1, 0L), readout)
        } finally {
            gate.countDown()
        }
        awaitUntil { src.missingFileCount() == 1 }
        awaitUntil { val r = src.decodeSlotReadout(); r.first == 0 && r.second == 0 }
    }

    // ------------------------------------------------------------------
    // 3) 队列满：拒收 + 计数（溢出策略钉死）
    // ------------------------------------------------------------------

    /**
     * 解码线程挂在钟上、[SpriteBitmapSource.DECODE_QUEUE_CAPACITY] 个新名字把 keys 填满后，
     * 再多一个名字按**拒收**处理并计入丢弃读数；已排队的**不丢**（溢出策略：拒收而非丢最旧，
     * 取舍写在容量 KDoc：绘制侧每帧重问，被拒的名字下一帧自然重排）。
     * 短路即红：同步解码时队列根本满不起来，读数 (0,0,0) 与期望差一截。
     */
    @Test
    fun `队列满拒收新请求并计数且不丢已排队`() {
        val gate = CountDownLatch(1)
        val src = newSource(gateOnDecodeThread = gate)
        try {
            assertNull(src.frameOf("fill-0"))
            awaitUntil { val r = src.decodeSlotReadout(); r.second == 1 && r.first == 0 }
            for (i in 1..SpriteBitmapSource.DECODE_QUEUE_CAPACITY) {
                assertNull(src.frameOf("fill-$i"))
            }
            val before = src.decodeSlotReadout()
            assertEquals("keys 应被填到容量上界", SpriteBitmapSource.DECODE_QUEUE_CAPACITY, before.first)
            assertEquals("1 条在解 + 64 条排队", SpriteBitmapSource.DECODE_QUEUE_CAPACITY + 1L, before.second.toLong())
            assertEquals(0L, before.third)
            assertNull(src.frameOf("overflow-1"))
            val after = src.decodeSlotReadout()
            assertEquals("被拒的不许挤掉已排队的", SpriteBitmapSource.DECODE_QUEUE_CAPACITY, after.first)
            assertEquals("拒收必须计数", 1L, after.third)
        } finally {
            gate.countDown()
        }
    }

    // ------------------------------------------------------------------
    // 4) 负缓存：异步失败也进 missingFiles，TTL 判式原样活
    // ------------------------------------------------------------------

    /**
     * 桩里解码恒失败 ⇒ 槽线程解完把名字记进负缓存；假钟恒给同一读数 ⇒ TTL 内
     * 第二次 `frameOf` 在**调用线程**上就被 [FailedLookupCache.shouldSkip] 拦下（不排队、
     * 不碰磁盘、不计数）；`invalidate()` 换代时负缓存整表作废的既有口径也不动。
     */
    @Test
    fun `异步失败进负缓存且TTL内不再排队`() {
        val src = newSource()
        assertNull(src.frameOf("neg-1"))
        awaitUntil { src.missingFileCount() == 1 }
        // complete 在 runDecodeTask 返回之后才摘在途，先把稳态等出来再断言。
        awaitUntil { src.decodeSlotReadout() == Triple(0, 0, 0L) }
        assertNull(src.frameOf("neg-1"))
        assertEquals("TTL 内的重问只在调用线程过负缓存闸", Triple(0, 0, 0L), src.decodeSlotReadout())
        assertEquals(1, src.missingFileCount())
        src.invalidate()
        assertEquals("换代清负缓存的既有口径不许动", 0, src.missingFileCount())
    }

    /**
     * [close] 之后：不起新线程、一切按「已收口」拒收并计数；排过的名字整表丢弃。
     */
    @Test
    fun `收口后一切拒收并计数`() {
        val src = newSource()
        src.close()
        assertNull(src.frameOf("closed-1"))
        val readout = src.decodeSlotReadout()
        assertEquals(0, readout.first)
        assertEquals(0, readout.second)
        assertTrue("收口后的请求要落进丢弃读数", readout.third >= 1L)
    }

    // ------------------------------------------------------------------
    // 5) 源码面钉子（桩环境跑不到的形状：主线程投递与泄漏纪律）
    // ------------------------------------------------------------------

    /** 容量常数是写死的（本波契约 §0 第 10 条），且未命中支的形状不许退回同步解码。 */
    @Test
    fun `源码面未命中支只排队不解码`() {
        val src = sourceText()
        val frameOfBody = bodyOf(src, "fun frameOf(resource: String): Frame?")
        assertTrue("未命中支必须走解码槽", frameOfBody.contains("requestFileDecode"))
        assertFalse("frameOf 里出现 decodeFileResource 就是回退同步解码", frameOfBody.contains("decodeFileResource("))
        assertFalse(frameOfBody.contains("decodeByResourceName"))
        val dispatchBody = bodyOf(src, "private fun requestFileDecode(resource: String)")
        assertTrue(dispatchBody.contains("submitDecodeRequest"))
        assertFalse("投递口出现按名解码调用就是回退", dispatchBody.contains("decodeByResourceName"))
        assertFalse(dispatchBody.contains("BitmapFactory"))
        assertTrue("容量必须是写死常数 64", src.contains("internal const val DECODE_QUEUE_CAPACITY = 64"))
        assertTrue("槽线程名是诊断读数的判据，改名的同时必须改本测试", src.contains("DECODE_THREAD_NAME = \"eratw-img-decode\""))
    }

    /**
     * 就绪通知的两条硬约束（契约 §2 L1-2）：解码线程**绝不直接碰订阅方**（一律
     * `mainHandler.post`），post 里的调用再整段 `runCatching`（通知不许把槽线程带死）。
     */
    @Test
    fun `源码面就绪通知必须经主线程投递`() {
        val src = sourceText()
        val body = bodyOf(src, "private fun notifyDecodeReady(resource: String)")
        assertTrue("通知口整段 runCatching（埋点不许搞崩解码槽）", body.contains("runCatching"))
        val postAt = body.indexOf("mainHandler.post")
        val callAt = body.indexOf("listener(resource)")
        assertTrue("必须经主线程 Handler 投递", postAt >= 0)
        assertTrue("订阅方调用只能发生在 post 之后（不许在解码线程直接调）", callAt > postAt)
    }

    /** 收口纪律：close 必须摘监听（防会话钉 View）、收队列、清负缓存。 */
    @Test
    fun `源码面收口摘监听并清台账`() {
        val body = bodyOf(sourceText(), "fun close()")
        for (needed in listOf(
            "decodeClosed = true",
            "decodeQueue.close()",
            "decodeReadyListener = null",
            "missingFiles.clear()",
            "worker?.interrupt()",
        )) {
            assertTrue("close 缺 $needed", body.contains(needed))
        }
    }

    /** 解码槽线程体失败路径：异常整段兜住 + finally 必过 complete（旧 AbortPolicy 抛漏即永久哑火）。 */
    @Test
    fun `源码面槽线程体的兜底与摘牌`() {
        val src = sourceText()
        val task = bodyOf(src, "private fun runDecodeTask(resource: String)")
        assertTrue("失败不许把线程带死", task.contains("catch (t: Throwable)"))
        assertTrue("解不出来要退回缺图支（decodeFileResource 内部已记负缓存）", task.contains("decodeFileResource"))
        val loop = bodyOf(src, "private fun decodeWorkerLoop()")
        val completeAt = loop.indexOf("decodeQueue.complete(resource)")
        assertTrue("每条请求必定摘在途（finally）", loop.contains("finally") && completeAt > 0)
    }

    // ------------------------------------------------------------------
    // 6) 判式与队列的纯 JVM 直测（形状同 shouldLogSlowDecode 先例）
    // ------------------------------------------------------------------

    /** 三条闸的**优先序是判式的一部分**：负缓存 > 同名在途 > 容量闸，全过才 Enqueue。 */
    @Test
    fun `判式优先序 负缓存压过一切`() {
        assertEquals(DecodeRequest.NegativeCached, SpriteBitmapSource.planDecodeRequest(true, true, true))
        assertEquals(DecodeRequest.NegativeCached, SpriteBitmapSource.planDecodeRequest(true, false, false))
        assertEquals(DecodeRequest.AlreadyQueued, SpriteBitmapSource.planDecodeRequest(false, true, false))
        assertEquals(DecodeRequest.AlreadyQueued, SpriteBitmapSource.planDecodeRequest(false, true, true))
        assertEquals(DecodeRequest.Rejected, SpriteBitmapSource.planDecodeRequest(false, false, false))
        assertEquals(DecodeRequest.Enqueue, SpriteBitmapSource.planDecodeRequest(false, false, true))
    }

    /** 队列台账：容量闸、去重、负缓存直拦、FIFO、complete 后放行重排、close 后恒拒。 */
    @Test
    fun `队列台账容量去重与收口`() {
        val q = DecodeQueue(2)
        assertEquals(DecodeRequest.Enqueue, q.offer("a", false))
        assertEquals("同名在途去重", DecodeRequest.AlreadyQueued, q.offer("a", false))
        assertEquals(DecodeRequest.Enqueue, q.offer("b", false))
        assertEquals("容量闸", DecodeRequest.Rejected, q.offer("c", false))
        assertEquals("负缓存连坑都不占", DecodeRequest.NegativeCached, q.offer("d", true))
        assertEquals("容量闸的拒收计了一次", 1L, q.droppedCount)
        assertEquals(2, q.queuedCount)
        assertEquals("a", q.poll(0))
        assertEquals(DecodeRequest.Enqueue, q.offer("c", false))
        q.complete("a")
        assertEquals(DecodeRequest.AlreadyQueued, q.offer("b", false))
        assertEquals(2, q.inFlightCount)
        q.close()
        assertTrue(q.isClosed)
        assertEquals("收口后恒拒", DecodeRequest.Rejected, q.offer("z", false))
        assertNull(q.poll(0))
        assertEquals("在途台账整表清", 0, q.inFlightCount)
    }

    /** 空队列 poll 带超时；close 唤醒挂着的人。 */
    @Test
    fun `空队列超时收口不挂人`() {
        val q = DecodeQueue(4)
        assertNull(q.poll(20))
        val waiter = Thread { q.poll(5_000) }
        waiter.start()
        Thread.sleep(50)
        q.close()
        waiter.join(2_000)
        assertFalse("close 必须唤醒等待中的 poll", waiter.isAlive)
    }

    /** 容量非正数在**构造期**就拒绝（宁可装配期炸，不要运行期哑）。 */
    @Test(expected = IllegalArgumentException::class)
    fun `容量必须为正`() {
        DecodeQueue(0)
    }

    private companion object {
        /** 假钟恒给同一读数：负缓存 TTL（5 s）在单测里恒不失效，判式可钉。 */
        const val STUB_NOW = 1_000L

        /** 与 SpriteBitmapSource 的私有常数对齐（源码面钉子同时钉住这个字面串）。 */
        const val DECODE_THREAD_NAME = "eratw-img-decode"
    }

    // ---- 源码面夹具（口径同 app 侧 StrictModeGuardTest / CanvasMemoryReleaseAuditTest）----

    private fun sourceText(): String {
        val candidates = listOf(
            File("../platform/src/main/kotlin/com/eratw/port/platform/image/SpriteBitmapSource.kt"),
            File("platform/src/main/kotlin/com/eratw/port/platform/image/SpriteBitmapSource.kt"),
            File("src/main/kotlin/com/eratw/port/platform/image/SpriteBitmapSource.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 SpriteBitmapSource.kt（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    /** 取某个 `fun 签名` 的函数体（大括号配平 + 剥行注释；口径同 CanvasMemoryReleaseAuditTest）。 */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到 $signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("$signature 后没有函数体", open >= 0)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripComments(source.substring(open + 1, i))
                }
                else -> {}
            }
            i++
        }
            error("$signature 的函数体没有配平")
    }

    private fun stripComments(body: String): String =
        body.lineSequence().map { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }.joinToString("\n")
}
