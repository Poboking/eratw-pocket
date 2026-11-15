package com.eratw.port.platform.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玩家可见症状的**数值钉**（0.2.44「脸红变白团」回归）。
 *
 * ## 症状（三份玩家截图一致）
 *
 * 咲夜两颊各一团纯白、灵梦口鼻一大团纯白、魔理沙两侧两团纯白 —— 位置与形状正是
 * 「照れ / 吐息 / 汗」这类**先画到透明画布的半透明特效层**的 alpha 轮廓；
 * 同一屏的**不透明身体完全正常**。
 *
 * ## 为什么是这个形状（算式）
 *
 * 特效层实测像素（语料 `072永琳.webp` 赤面格）RGB≈(248,156,149)、alpha 20~176。
 * 正确合成（直通域 source-over）与「谎报预乘」合成分别是：
 *
 * ```
 * 正确：  out = src*a + dst*(1-a)
 * 谎报：  out = src   + dst*(1-a)      // 位图存直通值却标 premultiplied，Skia 按预乘消费
 * ```
 *
 * 叠在皮肤 (245,206,200) 上：
 *
 * | alpha | 正确 | 谎报 |
 * |---|---|---|
 * | 20  | (245,202,196) | **(255,255,255)** |
 * | 40  | (245,198,192) | **(255,255,255)** |
 * | 80  | (246,190,184) | **(255,255,255)** |
 * | 120 | (246,182,176) | **(255,255,255)** |
 * | 176 | (247,171,165) | (255,220,211) |
 *
 * 即：**alpha ≲ 120 的整片特效层被推到纯白**，恰好就是玩家截图里的白团。
 * alpha=255 的不透明身体两边同值 ⇒ 身体永远正常 —— 这也是为什么只有特效层出问题。
 *
 * ## 与本测试的关系
 *
 * 算式本身是 [com.eratw.port.platform.image.SpriteBitmapSource] 降级支取图口径的下游后果：
 * 那条支必须用 [BitmapImageDecoder.renderDecodeOptions]（`inPremultiplied = true`，
 * 让 BitmapFactory 在解码时完成直通→预乘换算），不许「直通解码 + setPremultiplied(true)」。
 * 本类把**症状**钉在数值上，避免将来有人再用「只翻标志不动像素」的理由把那句写回去
 * （源码面钉子见 `SpriteBitmapSourceDemotedPayloadTest`）。
 */
class DemotedPremultipliedSymptomTest {

    /** 语料 `072永琳.webp` 赤面格实测像素（RGB 直通值）与其 alpha 档。 */
    private val blushRgb = intArrayOf(248, 156, 149)
    private val skinRgb = intArrayOf(245, 206, 200)

    /** 正确合成：直通域 source-over（= `GraphicsBuffer.blendPixel` 与 Skia 的预乘消费）。 */
    private fun correctOver(src: IntArray, a: Int, dst: IntArray): IntArray {
        val af = a / 255.0
        return IntArray(3) { i -> Math.round(src[i] * af + dst[i] * (1 - af)).toInt() }
    }

    /** 谎报预乘：位图存直通值却标 premultiplied ⇒ Skia 用 `src + dst*(1-a)`。 */
    private fun premulLieOver(src: IntArray, a: Int, dst: IntArray): IntArray {
        val af = a / 255.0
        return IntArray(3) { i -> Math.min(255, Math.round(src[i] + dst[i] * (1 - af)).toInt()) }
    }

    @Test
    fun `低 alpha 照れ层在谎报预乘下被打成纯白_正确合成只是淡粉`() {
        for (a in intArrayOf(20, 40, 80, 120)) {
            val correct = correctOver(blushRgb, a, skinRgb)
            val lie = premulLieOver(blushRgb, a, skinRgb)
            assertTrue(
                "a=$a 正确合成必须仍是皮肤色系的淡粉（G/B 不许接近 255），实得 ${correct.toList()}",
                correct[1] < 220 && correct[2] < 220,
            )
            assertEquals(
                "a=$a 谎报预乘必须把三通道全部打满 —— 玩家截图里那团纯白就是这个，实得 ${lie.toList()}",
                listOf(255, 255, 255),
                lie.toList(),
            )
        }
    }

    @Test
    fun `不透明像素两边同值_所以只有特效层出问题身体永远正常`() {
        for (a in intArrayOf(255)) {
            val correct = correctOver(blushRgb, a, skinRgb)
            val lie = premulLieOver(blushRgb, a, skinRgb)
            assertTrue(
                "a=255 时预乘标志不影响结果 ⇒ 不透明身体在两种口径下逐值相同（实得 $correct / $lie）",
                correct.contentEquals(lie),
            )
        }
    }

    @Test
    fun `高 alpha 档只偏白不饱和_与截图里白团边缘的过渡一致`() {
        val correct = correctOver(blushRgb, 176, skinRgb)
        val lie = premulLieOver(blushRgb, 176, skinRgb)
        assertTrue("正确：仍是淡粉 ${correct.toList()}", correct[1] < 220)
        assertTrue(
            "谎报：R 打满、G/B 抬到 200 以上（截图白团边缘的渐变），实得 ${lie.toList()}",
            lie[0] == 255 && lie[1] >= 200 && lie[2] >= 200,
        )
        assertTrue("谎报档必须比正确档更亮（这就是「变白」的方向）", lie[1] > correct[1] && lie[2] > correct[2])
    }
}
