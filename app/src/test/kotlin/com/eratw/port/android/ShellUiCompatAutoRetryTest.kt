package com.eratw.port.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldAutoRetryCompat] —— 「内存不够族失败先自动降一档，再谈弹屏」的判据契约。
 *
 * ## 这一刀修的是玩家面的哪句话
 *
 * 同批反馈（2026-10-30，两张「启动失败：堆不足」屏）里最扎人的不是失败本身，是这两句：
 * 「点过重试，但是重复报错了」与「你们试试兼容模式，按理说不应该呀，明明优化了内存占用」。
 * 前一句说明玩家**会**按「重试」（同一条路，必然再错），后一句说明「兼容模式」是**别人教他**
 * 才按的 —— 那颗按钮 2026-10-28 就在屏上了，缺的从来不是能力，是让玩家自己诊断出该按哪颗。
 *
 * ## 2026-11 补的一半：只有**乙**族才自动降档
 *
 * 真机 vivo V2199A（堆上限 512 MiB）现场：预检判「需 540.2 MiB > 512 MiB」拒载 → 旧判据
 * 只问「是不是堆不足族」⇒ 自动降档 → 省内存档绕过同一道闸 → 装载 OOM → 进程被系统随机
 * 一刀（致命 OOM 落在性能 SDK 线程和一次 dumpsys 上）⇒ **玩家一张画面都没看到**。
 * 病根是判据把「装载根本没开始」与「装载中真爆了」混成了一族（见 [HeapFailureStage]）：
 * - 甲 [HeapFailureStage.PREFLIGHT_REJECTED]：静态估算已判装不下 ⇒ 不许自动降档；
 * - 乙 [HeapFailureStage.LOAD_OOM]：上限本来够、是实际峰值高于估算 ⇒ 换档确实有救。
 *
 * ## 判据（逐条锁死）
 *
 * ① 只有乙族自动降档；② 已经在省内存档 ⇒ 没得更降，必须上屏 —— 这条同时挡掉
 * 「换档也失败后再自动来一遍」；③ 一次会话只自动降一回（pump 每帧都会再看这张屏，
 * 没有闩锁就是原地转圈，正是 [failureActions] 那一刀拆掉的同型病）。
 */
class ShellUiCompatAutoRetryTest {

    @Test
    fun `乙族（装载中真 OOM）首次失败自动降省内存档`() {
        assertTrue(
            "上限本来够、实际峰值更高：换档确实有救，玩家不该为了进游戏先搞懂那颗按钮",
            shouldAutoRetryCompat(
                heapFailure = HeapFailureStage.LOAD_OOM,
                compatInUse = false,
                autoRetried = false,
            ),
        )
    }

    @Test
    fun `甲族（预检拒载）绝不自动降档`() {
        assertFalse(
            "静态估算已判「上限 < 需求」：自动再试一次 = 明知装不下还硬试，真机代价是进程被杀、玩家什么都没看到",
            shouldAutoRetryCompat(
                heapFailure = HeapFailureStage.PREFLIGHT_REJECTED,
                compatInUse = false,
                autoRetried = false,
            ),
        )
    }

    @Test
    fun `非内存族不降档`() {
        assertFalse(
            "格式坏 / 文件缺失降多少档都一样，白等一遍装载",
            shouldAutoRetryCompat(
                heapFailure = HeapFailureStage.NONE,
                compatInUse = false,
                autoRetried = false,
            ),
        )
    }

    @Test
    fun `已在省内存档时不降（换档再失败必须上屏）`() {
        assertFalse(
            "没得更降 —— 这一条是死圈闸的一半：换档失败后屏必须出现",
            shouldAutoRetryCompat(
                heapFailure = HeapFailureStage.LOAD_OOM,
                compatInUse = true,
                autoRetried = false,
            ),
        )
    }

    @Test
    fun `一次会话只自动降一回`() {
        assertFalse(
            "pump 每帧都调 renderFailureIfNeeded：没有这枚闩锁就是失败→降档→又失败→…",
            shouldAutoRetryCompat(
                heapFailure = HeapFailureStage.LOAD_OOM,
                compatInUse = false,
                autoRetried = true,
            ),
        )
    }

    @Test
    fun `全组合只有唯一放行格`() {
        var passes = 0
        for (stage in HeapFailureStage.values()) {
            for (inUse in booleanArrayOf(false, true)) {
                for (tried in booleanArrayOf(false, true)) {
                    if (shouldAutoRetryCompat(stage, inUse, tried)) passes++
                }
            }
        }
        assertTrue(
            "十二个组合里只该有一格放行（乙族 且 未在用 且 未自动降过），实际 $passes",
            passes == 1,
        )
    }

    @Test
    fun `甲族的屏上出口仍在（不自动降档不等于把路堵死）`() {
        // 甲族停在失败屏，但「少占内存再试」那一颗要亮：估算按 1.1 倍保守标定，
        // 换档的实际峰值本来就低于它，玩家按下去仍有成功机会（取舍见 GameHost.canRetryCompat）。
        val actions = failureActions(
            repeated = false,
            restartStuck = false,
            canBackToTitle = false,
            canRetryCompat = true,
        )
        assertTrue("甲族第一次上屏就必须给那颗按钮", actions.canRetryCompat)
        assertTrue("「重试」也不收走：玩家可能刚腾出存储", actions.canRetry)
        assertFalse("第一次失败还不知道重试有没有用，先不给「结束应用」", actions.canExitApp)
    }
}
