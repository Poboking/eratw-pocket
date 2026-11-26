package com.eratw.port.android.portrait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 竖屏抽屉锚点（BOARD PORTRAIT ②b）的口径钉：样例行走设计稿附录 A 的原文形态，
 * 期望值按「最后一条状态区头」手推；折行续行（logicalStart = false）一律不参与判定。
 */
class PortraitAnchorJumpTest {

    private fun anchor(vararg rows: Pair<String, Boolean>): Int? =
        PortraitAnchorJump.anchorOffset(
            texts = rows.map { it.first },
            logicalStart = rows.map { it.second },
        )

    @Test
    fun `日期行只有一处时锚到它`() {
        val hit = anchor(
            "你睁开眼睛，新的一天开始了。" to true,
            "" to true,
            "第47日(五)  所持金: ¥5,177,463,124 （筹码：664CM）" to true,
            "体力 ▁▁▁▁ ( 3249/ 3845)  气力 ▁▁▁▁ ( 4245/ 4545)" to true,
            "【主菜单】" to true,
        )
        assertEquals("锚点必须落在日期行（状态区头）", 2, hit)
    }

    @Test
    fun `滚动缓冲里有两处日期行时取最后一条`() {
        val hit = anchor(
            "旧屏：第30日(四)  所持金: ¥100" to true,
            "体力 ▁▁▁▁ ( 100/ 100)" to true,
            "新的一屏正文。" to true,
            "夏之月 17日 (六)9时00分" to true,
            "体力 ▁▁▁▁ ( 2666/ 3000)" to true,
        )
        assertEquals("最近的屏才是玩家要看的区间", 3, hit)
    }

    @Test
    fun `折行续行不冒充行首`() {
        val hit = anchor(
            "第47日(五)  所持金: ¥5,177,463,124" to true,
            "（筹码：664CM） 夏之月 17日 (六)9时00分" to false,
            "体力 ▁▁▁▁ ( 3249/ 3845)" to true,
        )
        assertEquals("续行只是折出来的尾巴，锚点仍是唯一的逻辑行首", 0, hit)
    }

    @Test
    fun `没有日期行时退到数值行首`() {
        val hit = anchor(
            "叙述文本，没有状态区头。" to true,
            "体力 ▁▁▁▁ ( 3249/ 3845)  气力 ▁▁▁▁ ( 4245/ 4545)" to true,
            "又一段叙述。" to true,
        )
        assertEquals("状态头缺位时退到数值行", 1, hit)
    }

    @Test
    fun `完全没有状态区时返回 null 交给调用侧退回上翻一屏`() {
        val hit = anchor(
            "只是几行叙述文本。" to true,
            "再接一段叙述。" to true,
        )
        assertNull("不许拿 0 或末行冒充锚点", hit)
    }

    @Test
    fun `文本与行首标记长度不一致时拒绝判定`() {
        val hit = PortraitAnchorJump.anchorOffset(
            texts = listOf("第47日(五)  所持金: ¥1"),
            logicalStart = emptyList(),
        )
        assertNull("调用侧数据错位时不猜", hit)
    }
}
