package com.eratw.port.platform.image

import com.eratw.port.core.host.GRAPHICS_SLOT
import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.host.SPRITE_SLOT
import com.eratw.port.core.host.SpritePool
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SpriteBitmapSource.frameAvailable`（MODERN-R12-WIRE 的**只读探针**）的行为面 + 源码面钉子。
 *
 * ## 钉的是什么
 *
 * DIVW2 把缺帧行宽判据落在 core（`CellWidth.imgWidthPxOrNull` 的 `frameAvailableOf`），
 * 本波把**生产数据源**接上：本探针只读 `SpritePool` / `GraphicsBuffer` 的**注册态字段**
 * （契约 §0：不许把 `frameOf` 的惰性解码副作用带进 VM 折行/排版线程）。用例按注册态三支：
 *
 * 1. 查无 sprite ⇒ true（缺帧面不归探针判：miss 面在 `destSize` 已先落 AltText 单出口）；
 * 2. 动画 ⇒ 帧表非空（空表 = 原版 totaltime <= 0 恒无帧，SpriteAnime.cs:92-95 同界）；
 * 3. 静态 ⇒ `Sprite.isCreated`（缓冲未建 / GDISPOSE 后 = false，与画侧 `bitmapOf` 的
 *    null 支同判据；CSV 资源 sprite（id < 0）= 登记期事实恒 true）。
 *
 * ## 反向证明锚（契约 §4-A / §4-D）
 *
 * - A 撤覆写（回接口缺省 true）⇒ 第 2 组用例红；
 * - D 探针变异成触发解码的形态（如改调 `frameOf`）⇒ 第 5 组的零解码行为闸与源码面钉子红
 *   （`frameOf` 未命中支会把名字排进解码槽、解完登记负缓存——两条读数都会翻）。
 *
 * ## 纯 JVM 口径
 *
 * 与 `SpriteBitmapSourceGidFrameTest` 同款：`unitTests.isReturnDefaultValues = true`，
 * 真构造 `SpriteBitmapSource`（空资源根：文件层恒查不到 ⇒ 桩里没有成功解码路径），
 * sprite 表与图形池用 core 的真对象（`aux.slot` + `GraphicsPool.get().createFrom`）。
 * 注意第 3 组「帧表非空 ⇒ true」**不依赖**位图可建性：桩里 `Bitmap.createBitmap` 恒 null、
 * `frameOf` 会返回 null，而探针判的是注册态（位图解不出属瞬态形态，答 true = 接线前旧读数，
 * 见覆写 KDoc）——这条差异是设计内的，不是漏判。
 */
class SpriteBitmapSourceFrameAvailableProbeTest {

    private val sources = ArrayList<SpriteBitmapSource>()
    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        for (s in sources) runCatching { s.close() }
        for (r in roots) runCatching { r.deleteRecursively() }
    }

    /** 空资源根：文件层恒查不到（所有 sprite 命中都来自 aux 的 SpritePool）。 */
    private fun newSource(aux: RuntimeAux): SpriteBitmapSource {
        val root = File(
            System.getProperty("java.io.tmpdir"),
            "eratw-frameprobe-${System.nanoTime()}-${roots.size}",
        )
        root.mkdirs()
        roots.add(root)
        val src = SpriteBitmapSource(aux, GameImageFiles(root), clockMs = { 0L })
        sources.add(src)
        return src
    }

    private fun spritePool(aux: RuntimeAux): SpritePool = aux.slot(SPRITE_SLOT) { SpritePool() }

    private fun graphicsPool(aux: RuntimeAux): GraphicsPool = aux.slot(GRAPHICS_SLOT) { GraphicsPool() }

    // ------------------------------------------------------------------
    // 1) 查无 sprite：true（不可达支，语义钉住防误判文件图）
    // ------------------------------------------------------------------

    @Test
    fun `查无 sprite 答 true 且零解码零入队`() {
        val src = newSource(RuntimeAux())
        assertTrue("未注册名答 true（未判定 = 旧读数，防误判文件层图）", src.frameAvailable("nosuch"))
        assertTrue("空名同支", src.frameAvailable(""))
        // 零解码副作用：读数全零 + 解码线程从没起过（变异成 frameOf 就是这里红）。
        assertEquals(Triple(0, 0, 0L), src.decodeSlotReadout())
        assertNull(src.lastDecodeThreadName())
        assertEquals("负缓存不得被探针写入", 0, src.missingFileCount())
    }

    // ------------------------------------------------------------------
    // 2) 动画：帧表注册态（反向证明 A 的红面）
    // ------------------------------------------------------------------

    @Test
    fun `动画帧表空判缺帧且尺寸表仍命中`() {
        val aux = RuntimeAux()
        val src = newSource(aux)
        spritePool(aux).put(SpritePool.Sprite("an_empty", 8, 8, frames = ArrayList()))
        // 尺寸表命中（宽度面必先过这关才会问帧）+ 帧判定缺帧 ⇒ 折行落 AltText 文本尺。
        assertNotNull("尺寸表命中是缺帧面判据的前置", src.destSize("an_empty"))
        assertFalse("空帧表 = 恒无帧（totaltime <= 0 同界）", src.frameAvailable("an_empty"))
    }

    @Test
    fun `动画帧表非空判有帧`() {
        val aux = RuntimeAux()
        val src = newSource(aux)
        val buffer = graphicsPool(aux).get(1).also { it.createFrom(4, 4, IntArray(16)) }
        spritePool(aux).put(
            SpritePool.Sprite(
                "an_ok", 8, 8,
                frames = arrayListOf(SpritePool.Frame(buffer, 0, 0, 4, 4, 0, 0, 42)),
            ),
        )
        assertTrue("帧表非空 = 注册态有帧（位图可建性是瞬态面，不归探针）", src.frameAvailable("an_ok"))
    }

    // ------------------------------------------------------------------
    // 3) 静态：缓冲注册态
    // ------------------------------------------------------------------

    @Test
    fun `静态缓冲未建判缺帧 已建判有帧`() {
        val aux = RuntimeAux()
        val src = newSource(aux)
        val pool = graphicsPool(aux)
        // 空壳（GCProviders 前 = 未创建）⇒ 缺帧；真建（createFrom）⇒ 有帧。
        val shell = pool.get(9)
        spritePool(aux).put(SpritePool.Sprite("st_shell", 8, 8, buffer = shell))
        assertFalse("缓冲未建 = 画侧 bitmapOf 同判据的 null 支", src.frameAvailable("st_shell"))

        val live = pool.get(2).also { it.createFrom(4, 4, IntArray(16)) }
        spritePool(aux).put(SpritePool.Sprite("st_live", 8, 8, buffer = live))
        assertTrue(src.frameAvailable("st_live"))
    }

    @Test
    fun `静态缓冲 GDISPOSE 后翻缺帧`() {
        val aux = RuntimeAux()
        val src = newSource(aux)
        val buffer = graphicsPool(aux).get(3).also { it.createFrom(4, 4, IntArray(16)) }
        spritePool(aux).put(SpritePool.Sprite("st_gone", 8, 8, buffer = buffer))
        assertTrue(src.frameAvailable("st_gone"))
        buffer.dispose()
        assertFalse("GDISPOSE 后 = 缺帧（画侧同样出不了帧）", src.frameAvailable("st_gone"))
    }

    @Test
    fun `CSV 资源 sprite 按登记期事实判有帧`() {
        val aux = RuntimeAux()
        val src = newSource(aux)
        // 负号段缓冲 = CSV 父图（SpriteF.BaseImage）：登记期事实，不看像素驻留 ——
        // 与 Sprite.isCreated / SPRITECREATED 同一条口径（延迟解码不改脚本可见状态）。
        val parent = GraphicsBuffer(-1)
        assertTrue(parent.createDeferredFromFile(4, 4, "p.webp") { null })
        spritePool(aux).putResource(SpritePool.Sprite("res_csv", 8, 8, buffer = parent))
        assertTrue("id < 0 = 登记期事实，探针与 SPRITECREATED 同源", src.frameAvailable("res_csv"))
    }

    // ------------------------------------------------------------------
    // 4) 探针反复调用：零解码零入队（反向证明 D 的行为闸）
    // ------------------------------------------------------------------

    @Test
    fun `探针反复调用不解码不排队不写负缓存`() {
        val src = newSource(RuntimeAux())
        repeat(200) { i -> src.frameAvailable("missing_$i") }
        // 等解码槽静默（若探针被变异成 frameOf，未命中支会排队并异步解码），
        // 静默后两条读数仍为零才算「探针全程没碰解码」。
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            val (queued, inFlight, _) = src.decodeSlotReadout()
            if (queued == 0 && inFlight == 0) break
            Thread.sleep(20L)
        }
        assertEquals(Triple(0, 0, 0L), src.decodeSlotReadout())
        assertNull("解码线程名必须仍是 null（探针不触发 decodeFileResource）", src.lastDecodeThreadName())
        assertEquals("负缓存不得被探针写入", 0, src.missingFileCount())
    }

    // ------------------------------------------------------------------
    // 5) 源码面钉子：探针函数体不得出现解码路径的字面形态
    // ------------------------------------------------------------------

    @Test
    fun `探针函数体不调 frameOf 与解码路径`() {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/platform/image/SpriteBitmapSource.kt"),
            File("../platform/src/main/kotlin/com/eratw/port/platform/image/SpriteBitmapSource.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 SpriteBitmapSource.kt（试了 ${candidates.joinToString { it.path }}）")
        val body = bodyOf("override fun frameAvailable(")
        for (forbidden in listOf("frameOf", "spriteFrame", "bitmapOf", "decodeFile", "submitDecodeRequest")) {
            assertTrue(
                "探针体出现「$forbidden」= 把解码副作用带进排版线程（契约 §0 禁止）",
                !body.contains(forbidden),
            )
        }
    }

    /** 取某函数体（剥块注释后按大括号配平；与 DivImageAltFallbackAuditTest 同一把尺的简化版）。 */
    private fun bodyOf(signature: String): String {
        val code = stripComments(candidates().readText(Charsets.UTF_8))
        val start = code.indexOf(signature)
        assertTrue("代码里找不到 $signature", start >= 0)
        val open = code.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
            i++
        }
        error("$signature 的函数体没有闭合")
    }

    private fun candidates(): File {
        val list = listOf(
            File("src/main/kotlin/com/eratw/port/platform/image/SpriteBitmapSource.kt"),
            File("../platform/src/main/kotlin/com/eratw/port/platform/image/SpriteBitmapSource.kt"),
        )
        return list.firstOrNull { it.isFile }
            ?: error("找不到 SpriteBitmapSource.kt（试了 ${list.joinToString { it.path }}）")
    }

    private fun stripComments(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '/' && i + 1 < raw.length) {
                val next = raw[i + 1]
                if (next == '/') {
                    while (i < raw.length && raw[i] != '\n') i++
                    continue
                }
                if (next == '*') {
                    i += 2
                    while (i + 1 < raw.length && !(raw[i] == '*' && raw[i + 1] == '/')) {
                        if (raw[i] == '\n') sb.append('\n')
                        i++
                    }
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
