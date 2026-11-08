package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [failureActions] 是 2026-10-30 玩家反馈「点过重试，但是重复报错了」的修复契约。
 *
 * ## 现象
 *
 * 失败屏上恒亮的只有「重试」，而它走的正是刚刚失败的那同一条路：装载/开局失败 → 点重试
 * → 新宿主在同一条路上再撞一次 → 又是同一张屏。另一圈同形状：
 * 「重启停止」屏（`MainActivity.renderRestartStuck`）字里写着「请结束本应用进程后重开」，
 * 屏上却没有对应按钮，只剩「重试」——再点还是等满那 120 秒。
 *
 * ## 判据（逐条锁死）
 *
 * ①「结束应用」只在**再点重试也不会变**的两屏点亮：玩家按过「重试」之后又失败、或失败本身
 * 就是等不到上一局让出堆。② 点亮它**不许收走别的按钮**：玩家可能刚腾出存储、刚杀掉后台别的
 * 应用，那些只有他自己知道，把「重试」藏掉等于替他把活路堵上。③ 说明行只跟「重试又失败」走，
 * 「重启停止」不是重试过，不该看到那句。
 *
 * 为何是 JVM 单测而非 instrumented：判据本身无 Android 运行期依赖（同
 * `ShellUiContinueButtonTest` 的先例）；按钮的实际摆放由 `FailureOverlay` 的布局保证。
 */
class ShellUiFailureActionsTest {

    @Test
    fun `首次失败不给结束应用`() {
        val actions = failureActions(
            repeated = false,
            restartStuck = false,
            canBackToTitle = true,
            canRetryCompat = true,
        )
        assertFalse("第一次失败还不知道重试有没有用，先把重试留给玩家", actions.canExitApp)
        assertNull("没有可解释的第二次，就不该占屏", actions.note)
    }

    @Test
    fun `重试又失败时给结束应用并说明`() {
        val actions = failureActions(
            repeated = true,
            restartStuck = false,
            canBackToTitle = false,
            canRetryCompat = false,
        )
        assertTrue("重试走的是同一条路，屏必须自己认出来并给出口", actions.canExitApp)
        assertEquals(
            "说明行取的是原话常量，改字要连着判据一起改",
            REPEATED_FAILURE_NOTE,
            actions.note,
        )
    }

    @Test
    fun `点亮结束应用不收走任何既有出口`() {
        val actions = failureActions(
            repeated = true,
            restartStuck = false,
            canBackToTitle = true,
            canRetryCompat = true,
        )
        assertTrue("重复失败不等于重试永远无效：玩家可能刚腾出存储", actions.canRetry)
        assertTrue(actions.canBackToTitle)
        assertTrue(actions.canRetryCompat)
    }

    @Test
    fun `重启停止屏恒给结束应用`() {
        val actions = failureActions(
            repeated = false,
            restartStuck = true,
            canBackToTitle = false,
            canRetryCompat = false,
        )
        assertTrue(
            "这一屏的字面自救路径就是结束进程，屏上必须有对应那颗（旧版只有「重试」= 死圈）",
            actions.canExitApp,
        )
        assertNull("它不是「同一处失败第二次」，不该看到那句重复说明", actions.note)
    }

    @Test
    fun `两种出口条件叠在一起时说明行照给`() {
        val actions = failureActions(
            repeated = true,
            restartStuck = true,
            canBackToTitle = false,
            canRetryCompat = false,
        )
        assertTrue(actions.canExitApp)
        assertEquals(REPEATED_FAILURE_NOTE, actions.note)
    }

    @Test
    fun `可点性入参原样透传不由本判式改判`() {
        // 四组 = repeated × canBackToTitle × canRetryCompat 的全部组合里，
        // 只有「结束应用」是新增判据，另两枚必须逐值等于入参。
        for (repeated in booleanArrayOf(false, true)) {
            for (backToTitle in booleanArrayOf(false, true)) {
                for (compat in booleanArrayOf(false, true)) {
                    val actions = failureActions(
                        repeated = repeated,
                        restartStuck = false,
                        canBackToTitle = backToTitle,
                        canRetryCompat = compat,
                    )
                    assertTrue(
                        "「回到标题」只由 GameHost.canBackToTitle 一处判，这里不许二次改判",
                        actions.canBackToTitle == backToTitle,
                    )
                    assertTrue(
                        "「兼容模式重试」只由 GameHost.canRetryCompat 一处判，这里不许二次改判",
                        actions.canRetryCompat == compat,
                    )
                    assertTrue(
                        "「结束应用」= repeated ∨ restartStuck，本屏 restartStuck 恒 false",
                        actions.canExitApp == repeated,
                    )
                }
            }
        }
    }

    // ---- 2026-10-31：「上一局的内存还没释放」那一屏（真机「返回键退出再进 ⇒ 每次重试都进不去」）----

    @Test
    fun `旧会话屏第一次上屏就给出口`() {
        val actions = failureActions(
            repeated = false,
            restartStuck = false,
            canBackToTitle = false,
            canRetryCompat = false,
            staleSession = true,
        )
        assertTrue(
            "那 184 MiB 是活对象、进程内重试解不开：等 repeated 等于让玩家先白按一次",
            actions.canExitApp,
        )
        assertTrue("这一族的出路就是换一个干净进程，按钮必须写成「重启应用」", actions.exitIsRestart)
        assertNull("它不是「同一处失败第二次」，不该看到那句重复说明", actions.note)
    }

    @Test
    fun `其余失败族的出口仍是结束应用而不是重启`() {
        // repeated 与 restartStuck 两条既有点亮路径都不许被改成「重启应用」：
        // 玩家可能只是想先退出去（腾存储 / 杀后台），替他再拉起来是自作主张。
        for (repeated in booleanArrayOf(false, true)) {
            for (stuck in booleanArrayOf(false, true)) {
                val actions = failureActions(
                    repeated = repeated,
                    restartStuck = stuck,
                    canBackToTitle = false,
                    canRetryCompat = false,
                )
                assertTrue(
                    "只有 staleSession 那一族才是重启（repeated=$repeated stuck=$stuck）",
                    !actions.exitIsRestart,
                )
            }
        }
    }

    @Test
    fun `旧会话屏点亮出口不收走重试`() {
        val actions = failureActions(
            repeated = false,
            restartStuck = false,
            canBackToTitle = true,
            canRetryCompat = true,
            staleSession = true,
        )
        assertTrue("重试仍留着：玩家可能刚杀掉后台别的应用，那些只有他自己知道", actions.canRetry)
        assertTrue(actions.canBackToTitle)
        assertTrue(actions.canRetryCompat)
    }

    // ---- 2026-11：「内存不够」拆成甲/乙两族之后，甲族（预检拒载）那一条圈 ----

    @Test
    fun `甲族第一次上屏不给结束应用，给少占内存再试`() {
        // 甲族 = 预检就判「上限 < 需求」，装载根本没开始（判据见 HeapFailureStage）。
        // 这一屏不许自动降档（见 ShellUiCompatAutoRetryTest），所以「少占内存再试」是屏上
        // 唯一有意义的分叉；「重试」保持原样走的还是同一条预检。
        val actions = failureActions(
            repeated = false,
            restartStuck = false,
            canBackToTitle = false,
            canRetryCompat = true,
        )
        assertTrue("换档是甲族唯一可能奏效的那条路", actions.canRetryCompat)
        assertFalse("第一次失败就先给「结束应用」等于替玩家放弃", actions.canExitApp)
        assertNull("没有第二次失败，就不该占屏那句说明", actions.note)
    }

    @Test
    fun `甲族重试又失败必须给结束应用（屏上文案说明为什么再点也不会变）`() {
        // 走的是玩家真实那条路：甲族屏 → 按「重试」 → 同一份静态估算再拒一次。
        // 第二次上屏时 MainActivity 已把 retriedAfterFailure 记成 true。
        val actions = failureActions(
            repeated = true,
            restartStuck = false,
            canBackToTitle = false,
            canRetryCompat = true,
        )
        assertTrue("再点一次走的还是同一条预检，必须给「结束应用」", actions.canExitApp)
        assertFalse(
            "这一族的重试还有意义（换档/腾内存后可能成），不该写成「重启应用」自作主张拉起来",
            actions.exitIsRestart,
        )
        assertEquals(REPEATED_FAILURE_NOTE, actions.note)
        assertTrue("「少占内存再试」也不收走：换档确实可能有用", actions.canRetryCompat)
    }

    @Test
    fun `结束应用是甲族唯一的新增出口，按钮面不认族别`() {
        // failureActions 不认识 HeapFailureStage：族别只决定「要不要自动降档」那一次
        // （判据在 ShellUiCompatAutoRetryTest），屏上给哪几枚按钮与族别无关 ——
        // 宿主把两族都算成 canRetryCompat = true（取舍见 GameHost.canRetryCompat）。
        // 这条钉住「多出来的只有结束应用，别的一枚都不许多、也不许少」。
        for (repeated in booleanArrayOf(false, true)) {
            val actions = failureActions(
                repeated = repeated,
                restartStuck = false,
                canBackToTitle = false,
                canRetryCompat = true,
            )
            assertTrue("重试恒亮", actions.canRetry)
            assertTrue("换档那颗两族都亮", actions.canRetryCompat)
            assertFalse("装载失败没有可复活的会话", actions.canBackToTitle)
            assertTrue("结束应用只在重复失败时多出来", actions.canExitApp == repeated)
        }
    }
}
