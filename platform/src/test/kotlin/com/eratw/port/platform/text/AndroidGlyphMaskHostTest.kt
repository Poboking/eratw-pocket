package com.eratw.port.platform.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlyphMaskMath] 的纯 JVM 单测（CONTRACT-M11-E §5 glyph-platform 判据③）。
 *
 * 断言的是「换算约定」本身：ARGB→alpha 覆盖度、sizePx/styleMask→绘制计划、离屏盒布局、
 * 取墨包围盒与行主序裁剪、bearing 原点系、缓存键口径。
 * bearing 原点系按**核心侧权威** `core/host/GlyphMaskHost.kt:41-59` 钉：
 * 掩码左上角 = (⌊penX⌋+bearingX, lineTop+bearingY)，`bearingY` 参照行盒顶、正数向下（判据①）。
 * 样式位映射逐条对 C#：bit1=粗体 / bit2=斜体 / bit4=删除线 / bit8=下划线
 * （spec: Process.cs:1512-1527、FunctionMethodCreator.cs:6365-6380）；
 * 「无墨回 null ⇒ 调用方落回推进盒」对空白码位不落墨口径（spec: 本仓 GraphicsMethods.kt:447-449）。
 *
 * Android 薄封装（Paint/Bitmap/Canvas/getPixels 调用本体）不在此覆盖 ——
 * platform 单测侧 android.jar 是桩（build.gradle.kts 的 isReturnDefaultValues），
 * 真栅格读数归组长在有 SDK 的闸/真机上验（判据④）。
 */
class AndroidGlyphMaskHostTest {

    // ---- 码位 → 串 ----

    @Test
    fun `码位转串 合法BMP与增补平面 代理区与越界回null`() {
        assertEquals("A", GlyphMaskMath.codePointToString(0x41))
        // 增补平面码位 ⇒ 两个 UTF-16 码元（GDRAWTEXT 消费端按 codePointAt+charCount 取用，
        // spec: 本仓 GraphicsMethods.kt:484-485）
        val astral = GlyphMaskMath.codePointToString(0x20000)
        assertNotNull(astral)
        assertEquals(2, astral!!.length)
        assertEquals(0x20000, astral.codePointAt(0))
        assertNull(GlyphMaskMath.codePointToString(0))
        assertNull(GlyphMaskMath.codePointToString(-1))
        assertNull(GlyphMaskMath.codePointToString(0x110000))
        // 孤立代理码元（D800..DFFF）不是合法码位，也不许喂栅格器
        assertNull(GlyphMaskMath.codePointToString(0xD800))
        assertNull(GlyphMaskMath.codePointToString(0xDFFF))
    }

    // ---- sizePx/styleMask → 绘制计划 ----

    @Test
    fun `字号非正或非有限回null`() {
        // 原版 emSize ≤ 0 时 new Font 抛 ArgumentException（spec: FunctionMethodCreator.cs:6584 分支，
        // 本仓 GraphicsMethods.kt:539-543 同判）；移植侧不抛，null = 无字形回落
        assertNull(GlyphMaskMath.paintPlanFor(0f, 0))
        assertNull(GlyphMaskMath.paintPlanFor(-12f, 1))
        assertNull(GlyphMaskMath.paintPlanFor(Float.NaN, 0))
        assertNull(GlyphMaskMath.paintPlanFor(Float.POSITIVE_INFINITY, 0))
        assertEquals(18f, GlyphMaskMath.paintPlanFor(18f, 0)!!.textSizePx, 0f)
    }

    @Test
    fun `样式位按脚本约定映射 4是删除线 8是下划线`() {
        // C# 侧脚本位：1→Bold、2→Italic、4→Strikeout、8→Underline
        // （spec: FunctionMethodCreator.cs:6365-6380、Process.cs:1512-1527）。
        // 注意 GDI+ FontStyle 的枚举数值 4=Underline、8=Strikeout 与脚本位恰好互换
        // （spec: 本仓 GlyphRaster.kt:59-63 的警告）—— 本用例钉的就是「不许原样透传数值」。
        val p0 = GlyphMaskMath.paintPlanFor(20f, 0)!!
        assertTrue(!p0.syntheticBold && !p0.syntheticItalic && !p0.underline && !p0.strikeout)

        val p1 = GlyphMaskMath.paintPlanFor(20f, 1)!!
        assertTrue(p1.syntheticBold && !p1.syntheticItalic && !p1.underline && !p1.strikeout)

        val p2 = GlyphMaskMath.paintPlanFor(20f, 2)!!
        assertTrue(!p2.syntheticBold && p2.syntheticItalic && !p2.underline && !p2.strikeout)

        val p4 = GlyphMaskMath.paintPlanFor(20f, 4)!!
        assertTrue(!p4.syntheticBold && !p4.syntheticItalic && !p4.underline && p4.strikeout)

        val p8 = GlyphMaskMath.paintPlanFor(20f, 8)!!
        assertTrue(!p8.syntheticBold && !p8.syntheticItalic && p8.underline && !p8.strikeout)

        val p15 = GlyphMaskMath.paintPlanFor(20f, 0xF)!!
        assertTrue(p15.syntheticBold && p15.syntheticItalic && p15.underline && p15.strikeout)
    }

    @Test
    fun `样式位只认低四位 高位吞掉`() {
        // spec: FunctionMethodCreator.cs:6365-6380 只判 1/2/4/8 四个位；
        // GSETFONT 侧再叠 mask and 0xF（spec: 本仓 GraphicsMethods.kt:382）。
        val p = GlyphMaskMath.paintPlanFor(20f, 0x10)!!
        assertTrue(!p.syntheticBold && !p.syntheticItalic && !p.underline && !p.strikeout)
        val q = GlyphMaskMath.paintPlanFor(20f, 0x12)!!
        assertTrue(q.syntheticItalic && !q.syntheticBold)
    }

    // ---- 离屏盒布局 ----

    @Test
    fun `盒布局按advance向上向下与pad确定性生成`() {
        // 期望：width = ceil(10.2)+2*2 = 15；height = ceil(12+3)+2*2 = 19；
        // drawX = 2（笔位列）；baselineY = 2 + ceil(12) = 14（Android 基线行）；
        // lineTopY = 2（内容区顶行 = pad 行，声明为核心侧的「行盒顶」，见 GlyphMaskMath KDoc）。
        val l = GlyphMaskMath.layoutFor(10.2f, 12f, 3f, 2)!!
        assertEquals(15, l.width)
        assertEquals(19, l.height)
        assertEquals(2, l.drawX)
        assertEquals(14, l.baselineY)
        assertEquals(2, l.lineTopY)
        // 退化入参一律回 null（宿主放弃掩码，调用方落回推进盒）
        assertNull(GlyphMaskMath.layoutFor(-1f, 12f, 3f, 2))
        assertNull(GlyphMaskMath.layoutFor(Float.NaN, 12f, 3f, 2))
        assertNull(GlyphMaskMath.layoutFor(10f, -0.5f, 3f, 2))
        assertNull(GlyphMaskMath.layoutFor(10f, 12f, 3f, -1))
        // 超上限（如病态大字号）回 null，不生成巨位图
        assertNull(GlyphMaskMath.layoutFor(10f, 9000f, 3f, 2))
        // 上限边界：above+below=4092、pad=2 ⇒ height=4096（恰等于 MAX_SIDE_PX）放行；
        // above+below=4093 ⇒ height=4097 超限回 null。
        assertEquals(GlyphMaskMath.MAX_SIDE_PX, GlyphMaskMath.layoutFor(9f, 4089f, 3f, 2)!!.height)
        assertNull(GlyphMaskMath.layoutFor(9f, 4090f, 3f, 2))
    }

    @Test
    fun `外扩像素按字号夹逼2到16`() {
        assertEquals(2, GlyphMaskMath.padPxFor(4f))       // ceil(1)=1 ⇒ 夹到下限 2
        assertEquals(5, GlyphMaskMath.padPxFor(18f))      // ceil(4.5)=5
        assertEquals(16, GlyphMaskMath.padPxFor(400f))    // ceil(100) ⇒ 夹到上限 16
    }

    // ---- ARGB → 覆盖度 ----

    @Test
    fun `alpha通道提取 负整数与满值边界`() {
        assertEquals(0x80, GlyphMaskMath.alphaOf(0x80FF0000.toInt()))
        assertEquals(255, GlyphMaskMath.alphaOf(-1)) // 0xFFFFFFFF 在 Int 里是 -1
        assertEquals(0, GlyphMaskMath.alphaOf(0x00FFFFFF))
        // 契约值域：0..255 覆盖度（CONTRACT-M11-E §4），RGB 三通道全丢
        val pixels = intArrayOf(0x01ABCDEF, 0xFF000000.toInt(), 0x00000000.toInt(), -1)
        val alpha = GlyphMaskMath.extractAlpha(pixels, 2, 2)
        assertEquals(4, alpha.size) // size = width*height（行主序契约）
        assertArrayEquals(byteArrayOf(1, -1, 0, -1), alpha) // 1, 255, 0, 255（补码）
    }

    // ---- 取墨包围盒 / 裁剪 / bearing ----

    @Test
    fun `全盒无墨回null 空白码位不落墨口径`() {
        // 空格等无轮廓字形 FillPath 不出像素（spec: 本仓 GraphicsMethods.kt:447-449），
        // inkBounds=null ⇒ 宿主回 null ⇒ 调用方走自己的空白/回落分支。
        assertNull(GlyphMaskMath.inkBounds(ByteArray(6), 3, 2))
        // 阈值 0 时哪怕 1/255 的抗锯齿淡边也算墨；阈值抬到 1 则只剩满墨
        val faint = byteArrayOf(0, 1, 0, 0, 0, -1) // 第 1 列上淡下满（-1 = 255）
        val b0 = GlyphMaskMath.inkBounds(faint, 3, 2)!!
        assertEquals(1, b0.left)
        assertEquals(2, b0.bottom)
        val b1 = GlyphMaskMath.inkBounds(faint, 3, 2, threshold = 1)!!
        // 只剩 (x=2, y=1) 的满墨：左=2、右开=3、上=1、下开=2
        assertEquals(2, b1.left)
        assertEquals(3, b1.right)
        assertEquals(1, b1.top)
        assertEquals(2, b1.bottom)
    }

    @Test
    fun `包围盒裁剪保持行主序`() {
        // 4×3 盒，墨落在 x∈[1,3)、y∈[0,2)：
        val w = 4
        val alpha = ByteArray(12)
        val expect = byteArrayOf(10, 20, 30, 40)
        byteArrayOf(0, 10, 20, 0, 0, 30, 40, 0).copyInto(alpha, 0, 0, 8)
        val b = GlyphMaskMath.inkBounds(alpha, w, 3)!!
        assertEquals(1, b.left); assertEquals(3, b.right)
        assertEquals(0, b.top); assertEquals(2, b.bottom)
        val crop = GlyphMaskMath.cropAlpha(alpha, w, b)
        assertArrayEquals(expect, crop)
        assertEquals(b.width * b.height, crop.size) // size = width*height（§4 契约）
    }

    @Test
    fun `bearing换算 笔位与行盒顶原点系 正数向下`() {
        // 权威口径：core/host/GlyphMaskHost.kt:41-59（掩码左上角 = (⌊penX⌋+bearingX, lineTop+bearingY)，
        // bearingY = 掩码顶缘相对**行盒顶**的偏移、正数向下、可为负、越界由核心侧 compositeGlyphMask 裁）。
        // 盒：drawX=2（笔位列）、baselineY=14、lineTopY=2（=pad 行，本路声明的行盒顶）
        // —— 与 layoutFor(10.2f, 12f, 3f, 2) 同一档。
        val layout = GlyphMaskMath.OffscreenLayout(
            width = 15, height = 19, drawX = 2, baselineY = 14, lineTopY = 2,
        )
        // 墨盒 x∈[1,6)、y∈[5,9) ⇒ bearingX = 1-2 = -1（左悬出，负值合法，口径不变）；
        // bearingY = 5-2 = +3：墨迹顶在行盒顶**下方** 3px。
        // 交叉核对 FreeType 系换算（权威 :55-57 的 bearingY = ascentFromBoxTop - topBearing）：
        // ascentFromBoxTop = baselineY - lineTopY = 12、topBearing = baselineY - bounds.top = 9 ⇒ 12-9 = 3。
        val b = GlyphMaskMath.InkBounds(left = 1, top = 5, right = 6, bottom = 9)
        assertEquals(-1, GlyphMaskMath.bearingX(b, layout))
        assertEquals(3, GlyphMaskMath.bearingY(b, layout))
        // 落点自洽（核心侧 destY = lineTop + bearingY 必须回到墨迹左上角行）：2 + 3 = 5 = bounds.top
        assertEquals(b.top, layout.lineTopY + GlyphMaskMath.bearingY(b, layout))
        // 基线无关性：推导里基线相消 ⇒ 同一 lineTopY、不同 baselineY 的盒，bearingY 不变
        val tallerBox = GlyphMaskMath.OffscreenLayout(width = 15, height = 25, drawX = 2, baselineY = 20, lineTopY = 2)
        assertEquals(GlyphMaskMath.bearingY(b, layout), GlyphMaskMath.bearingY(b, tallerBox))
        // 负 bearingY：上伸部悬出行盒顶（墨顶 y=0 落在 pad 悬出区内）⇒ 0-2 = -2；宿主不裁、不报错
        val overhang = GlyphMaskMath.InkBounds(left = 2, top = 0, right = 6, bottom = 9)
        assertEquals(-2, GlyphMaskMath.bearingY(overhang, layout))
        // 墨顶恰落在行盒顶那一行 ⇒ bearingY = 0（本路离屏盒的满上升字形，如 CJK 方块字）
        val atLineTop = GlyphMaskMath.InkBounds(left = 2, top = 2, right = 6, bottom = 14)
        assertEquals(0, GlyphMaskMath.bearingY(atLineTop, layout))
        // 降部字形：墨盒 y∈[10,18) 跨过基线（14）⇒ 有像素沉到基线以下。
        // 新口径 bearingY = 10-2 = 8（相对基线还差 12-8 = 4px，即旧基线系读数 14-10 = 4；
        // 换算式 权威:56 的 bearingY = ascentFromBoxTop(12) - topBearing(4) = 8）。
        // 落点自洽：行盒顶 + bearingY + 掩码高 = 2 + 8 + 8 = 18 = bounds.bottom（墨迹底缘）。
        val desc = GlyphMaskMath.InkBounds(2, 10, 6, 18)
        assertEquals(8, GlyphMaskMath.bearingY(desc, layout))
        assertEquals(desc.bottom, layout.lineTopY + GlyphMaskMath.bearingY(desc, layout) + desc.height)
    }

    // ---- 缓存键 ----

    @Test
    fun `缓存键同入参同键 逐维不同必不同键`() {
        // 原版 Font 桶键含字号（spec: FontFactory.cs:9,18-25）；字体名序号比较 ⇒ 大小写分桶
        // （spec: FontFactory.cs:40）。
        val a = GlyphMaskMath.cacheKey(0x41, "Gothic", 18f, 0)
        assertEquals(a, GlyphMaskMath.cacheKey(0x41, "Gothic", 18f, 0))
        assertTrue(a != GlyphMaskMath.cacheKey(0x42, "Gothic", 18f, 0))
        assertTrue(a != GlyphMaskMath.cacheKey(0x41, "Gothic", 19f, 0))
        assertTrue(a != GlyphMaskMath.cacheKey(0x41, "Gothic", 18f, 1))
        assertTrue(a != GlyphMaskMath.cacheKey(0x41, "gothic", 18f, 0))
        // null 与空串区分：null=未设字体走兜底，""=显式空名
        assertTrue(GlyphMaskMath.cacheKey(0x41, null, 18f, 0) != GlyphMaskMath.cacheKey(0x41, "", 18f, 0))
    }
}
