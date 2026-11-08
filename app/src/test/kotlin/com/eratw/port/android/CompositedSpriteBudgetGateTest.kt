package com.eratw.port.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RunTrail.shouldEvictCompositedSprites] —— 第三档（合成图具名 Sprite 逐出）的**进门判式**
 * （fix-hiresmem 车道，2026-11-04 端点收件箱 91 份）。
 *
 * ## 为什么单锁这一道门
 *
 * 逐出本体在 core 侧（`SpritePool.evictCompositedSpritesWithinBudget`，那里锁的是候选集与
 * 记账），而**要不要动玩家的立绘**是宿主决定，判式写错的两种后果都不在盘上：
 *
 * - **过早动手**（只看驻留、不看堆）：正常游玩合成一张立绘就摘一张，玩家白担一次全尺寸
 *   `GCREATE` + 重合成，还平白让 `SPRITECREATED` 的读数变得不稳定；
 * - **从不动手**（多加一道堆判据、或把预算读成不可知）：就是 11-04 那批读数 ——
 *   堆 96–99%、正号段驻留 250–309 MiB（预算 128–170 MiB），而 `turn.trim.budget`
 *   一行都不出现，最后 `LOW_MEMORY` / `iAwareF[LowMem]` / `SIGABRT` 静默杀掉，
 *   玩家看到的形状是「无提示卡死然后直接回标题」。
 *
 * 桌面装置与全部单测走 `budgetBytes = Long.MAX_VALUE` 那一支恒不动 —— 这条也钉住：
 * 512 MiB 挡的分配形状桌面复现不了（`GraphicsPool.PIXEL_BUDGET_DIVISOR` 的 KDoc 在册），
 * 门若不在桌面关上，桌面读数就不再是「同一件事」的证据。
 */
class CompositedSpriteBudgetGateTest {

    private val mib = 1024L * 1024L
    private val budget128 = 128L * mib

    @Test
    fun `贴顶且超预算才动手`() {
        // 真机形状：HONOR AAK-AN00 临死读数 used=494MiB/512、正号段驻留 327MiB
        assertTrue(
            RunTrail.shouldEvictCompositedSprites(
                usedMiB = 494L, maxMiB = 512L, residentBytes = 327L * mib, budgetBytes = budget128,
            ),
        )
    }

    @Test
    fun `驻留在预算内就不动玩家的图`() {
        // 同样的堆水位，但像素已被前两档收到预算内 ⇒ 第三档没有理由开工
        assertFalse(
            RunTrail.shouldEvictCompositedSprites(
                usedMiB = 494L, maxMiB = 512L, residentBytes = 100L * mib, budgetBytes = budget128,
            ),
        )
        // 恰好压在预算线上：判据是「之上」，不是「不低」
        assertFalse(
            RunTrail.shouldEvictCompositedSprites(
                usedMiB = 494L, maxMiB = 512L, residentBytes = budget128, budgetBytes = budget128,
            ),
        )
    }

    @Test
    fun `堆没贴顶就不动玩家的图`() {
        // 正常游玩：刚合成一批立绘、驻留超预算，但堆只到 60% —— 这一档等的是「要被系统杀」那一刻
        assertFalse(
            RunTrail.shouldEvictCompositedSprites(
                usedMiB = 307L, maxMiB = 512L, residentBytes = 300L * mib, budgetBytes = budget128,
            ),
        )
        // 水位的进门点 = RELEASE_AT_PERCENT（与上面两档开始回收同一个数）。
        // 用 500 MiB 当堆：80% 恰好是整数 400，进门点两侧各钉一发，不留整除歧义。
        val line = RunTrail.RELEASE_AT_PERCENT
        val maxMiB = 500L
        assertFalse(
            "低于 ${line}% 不许动手",
            RunTrail.shouldEvictCompositedSprites(
                usedMiB = maxMiB * (line - 1) / 100L,
                maxMiB = maxMiB,
                residentBytes = 300L * mib,
                budgetBytes = budget128,
            ),
        )
        assertTrue(
            "${line}% 就是进门点（与水位档对齐）",
            RunTrail.shouldEvictCompositedSprites(
                usedMiB = maxMiB * line / 100L,
                maxMiB = maxMiB,
                residentBytes = 300L * mib,
                budgetBytes = budget128,
            ),
        )
    }

    @Test
    fun `预算不可知（桌面与单测）恒不动`() {
        assertTrue(
            RunTrail.shouldEvictCompositedSprites(
                usedMiB = 511L, maxMiB = 512L, residentBytes = 4_000L * mib, budgetBytes = Long.MAX_VALUE,
            ).not(),
        )
    }

    @Test
    fun `堆上限读数缺失一律不动`() {
        assertFalse(
            RunTrail.shouldEvictCompositedSprites(
                usedMiB = 400L, maxMiB = 0L, residentBytes = 300L * mib, budgetBytes = budget128,
            ),
        )
    }

    /**
     * 四象限复扫，预算用**宿主真正传进来的那一个读数**（`GraphicsPool.sharedPixelBudgetBytes()`）。
     *
     * 上面几条用例的 128 MiB 是手写的真机挡位；这一条钉的是「桌面现取预算不是 `Long.MAX_VALUE`
     * 那条免死金牌」—— 桌面 JVM 的堆上限可知 ⇒ 预算是一个有限的 ÷4 读数，于是第三档在桌面上
     * **不是**被预算豁免的，而是被水位挡住的（本条象限 2）。将来若有人把这里改成「桌面一律按
     * 不可知处理」，象限 1 会红。
     */
    @Test
    fun `桌面现取预算档下的四象限`() {
        val budget = com.eratw.port.core.host.GraphicsPool.sharedPixelBudgetBytes()
        assertTrue("前置：桌面现取预算必须是一个可知读数（实际 $budget）", budget > 0L && budget != Long.MAX_VALUE)
        val over = budget + 1L
        val line = RunTrail.RELEASE_AT_PERCENT
        val maxMiB = 500L
        val high = maxMiB * line / 100L
        val low = maxMiB * (line - 1) / 100L

        // 象限 1：预算可知 × 堆贴顶 × 驻留超预算 ⇒ 动手（这一档存在的唯一理由）。
        assertTrue(RunTrail.shouldEvictCompositedSprites(high, maxMiB, over, budget))
        // 象限 2：预算可知 × 堆没贴顶 × 驻留超预算 ⇒ 不动（正常游玩不许摘玩家的立绘名字）。
        assertFalse(RunTrail.shouldEvictCompositedSprites(low, maxMiB, over, budget))
        // 象限 3：预算可知 × 堆贴顶 × 驻留恰好压在预算上 ⇒ 不动（判据是「之上」）。
        assertFalse(RunTrail.shouldEvictCompositedSprites(high, maxMiB, budget, budget))
        // 象限 4：预算不可知 × 堆贴顶 × 驻留拉满 ⇒ 不动（哨兵不能大于它自己）。
        assertFalse(RunTrail.shouldEvictCompositedSprites(high, maxMiB, Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
