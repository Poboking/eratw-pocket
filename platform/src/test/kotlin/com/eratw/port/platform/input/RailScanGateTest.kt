package com.eratw.port.platform.input

import com.eratw.port.core.host.InputRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RailScanGate] 的判据：右栏扫描只在**等待页身份变了**的那一帧绕过节流。
 *
 * ## 为什么要这条闸（本案现场）
 * 玩家反馈「点泡茶旁边的 + 号，有选择窗口了但是点红茶还是没反应」。根因在壳层：
 * `MainActivity.refreshShell` 的右栏扫描吃 250ms 节流，而等待页切换（命令面板 → QOL 子选单
 * 弹窗）时引擎快照在 `noteInputWait` 入口就换了 ⇒ 右栏最多滞后 250ms，玩家点到的是
 * **上一页的芯片**。QOL 子选单的 `INPUT -1, 1`（`ERB/魔改内容/qol/QOL_USERCOM.ERB:276`）
 * 收到面板按钮值（如 301），`INRANGE(INPUT_VALUES, 0, OPTIONNUM)`（:282）不成立 ⇒
 * 变体没切、弹窗直接返回 ⇒「点了没反应」。
 *
 * ## 判据的重量
 * - **必须**在 `scriptLine` 变化时触发：它是「引擎走到另一条 INPUT 语句」的身份位
 *   （`InputRequest.scriptLine` 的契约：同一函数内稳定、换行即变、-1 = 未知）；
 * - **必须**在 `scriptLine` 缺位（-1）时靠 `form`/`type`/`mouseInput`/`hasDef` 退化分辨
 *   —— 本案面板页是 `INPUTS`（STR）、弹窗页是 `INPUT -1,1`（INT），退化也能分开；
 * - **不许**在同页重试（同 `scriptLine`、同形态）时触发：那会把 250ms 省电退回每帧重扫，
 *   即 2026-10-31 真机 OOM 现场（热路径上几十枚候选的指纹分配）。
 */
class RailScanGateTest {

    private fun req(
        type: InputRequest.InputType = InputRequest.InputType.INT,
        form: InputRequest.RequestForm = InputRequest.RequestForm.VALUE,
        mouseInput: Boolean = false,
        hasDef: Boolean = false,
        scriptLine: Long = -1L,
    ) = InputRequest(
        type = type,
        form = form,
        mouseInput = mouseInput,
        hasDef = hasDef,
        scriptLine = scriptLine,
    )

    // ------------------------------------------------------------------
    // 无等待态
    // ------------------------------------------------------------------

    /** 没有等待方（`pendingRequest() == null` 且不在 WAIT 系）恒为固定常量。 */
    @Test
    fun `无等待态签名恒为 IDLE`() {
        assertEquals(RailScanGate.IDLE_SIGNATURE, RailScanGate.waitSignature(null, awaitingAnyKey = false))
        assertEquals(
            RailScanGate.IDLE_SIGNATURE,
            RailScanGate.waitSignature(null, awaitingAnyKey = false),
        )
    }

    /** IDLE 常量本身是 0，签名计算不得在无等待时吐出 0 以外的值。 */
    @Test
    fun `无等待态不会与任何有等待签名混同`() {
        val waiting = RailScanGate.waitSignature(req(scriptLine = 0L), awaitingAnyKey = false)
        assertNotEquals(RailScanGate.IDLE_SIGNATURE, waiting)
        val anyKey = RailScanGate.waitSignature(null, awaitingAnyKey = true)
        assertNotEquals(RailScanGate.IDLE_SIGNATURE, anyKey)
    }

    // ------------------------------------------------------------------
    // 本案核心：命令面板 → QOL 子选单弹窗
    // ------------------------------------------------------------------

    /**
     * **本案**：面板页 `INPUTS`（STR，无鼠标）→ 弹窗页 `INPUT -1, 1`
     * （INT + 鼠标 + 有默认值）。签名必须变 —— 变了壳层才会当帧重扫右栏，
     * 玩家才不会点到上一页的芯片。
     */
    @Test
    fun `命令面板切到子选单弹窗 签名必变`() {
        val panel = RailScanGate.waitSignature(
            req(
                type = InputRequest.InputType.STR,
                form = InputRequest.RequestForm.VALUE,
                mouseInput = false,
                hasDef = false,
                scriptLine = 1001L,
            ),
            awaitingAnyKey = false,
        )
        val popup = RailScanGate.waitSignature(
            req(
                type = InputRequest.InputType.INT,
                form = InputRequest.RequestForm.VALUE,
                mouseInput = true,
                hasDef = true,
                scriptLine = 2048L,
            ),
            awaitingAnyKey = false,
        )
        assertNotEquals(panel, popup)
        assertTrue(RailScanGate.mustScanNow(popup, panel))
    }

    /**
     * 同一份现场重算签名必须幂等（每帧调用；不稳定就会变成每帧重扫 = OOM 回归）。
     */
    @Test
    fun `同一等待现场签名幂等`() {
        val r = req(mouseInput = true, hasDef = true, scriptLine = 2048L)
        val first = RailScanGate.waitSignature(r, awaitingAnyKey = false)
        repeat(100) {
            assertEquals(first, RailScanGate.waitSignature(r, awaitingAnyKey = false))
        }
        // 引擎换实例但值相同也不许误判（签名是值签名）。
        val same = req(mouseInput = true, hasDef = true, scriptLine = 2048L)
        assertEquals(first, RailScanGate.waitSignature(same, awaitingAnyKey = false))
    }

    /**
     * **同页重试**（同一 `scriptLine` 的第二次等待，例如输入非法后重显菜单）签名不变
     * ⇒ 不绕过节流。这条是「不许退回每帧重扫」的守门判据。
     */
    @Test
    fun `同页重试签名不变 不吃立即重扫`() {
        val a = RailScanGate.waitSignature(req(scriptLine = 2048L, hasDef = true), awaitingAnyKey = false)
        val b = RailScanGate.waitSignature(req(scriptLine = 2048L, hasDef = true), awaitingAnyKey = false)
        assertEquals(a, b)
        assertFalse(RailScanGate.mustScanNow(b, a))
    }

    // ------------------------------------------------------------------
    // scriptLine 缺位时的退化判据
    // ------------------------------------------------------------------

    /**
     * `scriptLine == -1`（宿主未接线）时，单靠 `type` / `mouseInput` / `hasDef` 仍要能
     * 分开面板页与弹窗页 —— 本案的两页恰好三项全不同，退化判据足够。
     */
    @Test
    fun `scriptLine 缺位时形态位仍能分开面板与弹窗`() {
        val panel = RailScanGate.waitSignature(
            req(type = InputRequest.InputType.STR, scriptLine = -1L),
            awaitingAnyKey = false,
        )
        val popup = RailScanGate.waitSignature(
            req(type = InputRequest.InputType.INT, mouseInput = true, hasDef = true, scriptLine = -1L),
            awaitingAnyKey = false,
        )
        assertNotEquals(panel, popup)
    }

    /** 只有 `scriptLine` 变化（形态完全相同）也必须触发：换行即换页。 */
    @Test
    fun `仅 scriptLine 变化也触发`() {
        val a = RailScanGate.waitSignature(req(scriptLine = 10L), awaitingAnyKey = false)
        val b = RailScanGate.waitSignature(req(scriptLine = 11L), awaitingAnyKey = false)
        assertNotEquals(a, b)
        assertTrue(RailScanGate.mustScanNow(b, a))
    }

    /** `form` 变化（VALUE → ANY_VALUE 等）计入签名。 */
    @Test
    fun `form 变化计入签名`() {
        val value = RailScanGate.waitSignature(req(form = InputRequest.RequestForm.VALUE), false)
        val anyValue = RailScanGate.waitSignature(req(form = InputRequest.RequestForm.ANY_VALUE), false)
        val enter = RailScanGate.waitSignature(req(form = InputRequest.RequestForm.ENTER_KEY), false)
        assertNotEquals(value, anyValue)
        assertNotEquals(value, enter)
        assertNotEquals(anyValue, enter)
    }

    /** `mouseInput` 单独变化（`INPUT x` → `INPUT x, 1`）计入签名：可点性语义变了。 */
    @Test
    fun `mouseInput 变化计入签名`() {
        val noMouse = RailScanGate.waitSignature(req(mouseInput = false), false)
        val mouse = RailScanGate.waitSignature(req(mouseInput = true), false)
        assertNotEquals(noMouse, mouse)
    }

    /** `hasDef` 单独变化（`INPUT` → `INPUT -1, 1` 的默认值位）计入签名。 */
    @Test
    fun `hasDef 变化计入签名`() {
        val noDef = RailScanGate.waitSignature(req(hasDef = false), false)
        val def = RailScanGate.waitSignature(req(hasDef = true), false)
        assertNotEquals(noDef, def)
    }

    // ------------------------------------------------------------------
    // WAIT 系与无等待互切
    // ------------------------------------------------------------------

    /** 无等待 → 有等待（执行期结束、落到 INPUT）：必须触发。 */
    @Test
    fun `从无等待进入等待触发重扫`() {
        val idle = RailScanGate.waitSignature(null, awaitingAnyKey = false)
        val waiting = RailScanGate.waitSignature(req(scriptLine = 42L), awaitingAnyKey = false)
        assertTrue(RailScanGate.mustScanNow(waiting, idle))
    }

    /** 有等待 → 无等待（等待交付、脚本继续跑）：右栏要清掉上一页选项，必须触发。 */
    @Test
    fun `从等待回到无等待触发重扫`() {
        val idle = RailScanGate.waitSignature(null, awaitingAnyKey = false)
        val waiting = RailScanGate.waitSignature(req(scriptLine = 42L), awaitingAnyKey = false)
        assertTrue(RailScanGate.mustScanNow(idle, waiting))
    }

    /** 取值等待 ↔ WAIT 系（anyKey）：两者可点性完全不同（`canSkip` / 继续脚钮），必须触发。 */
    @Test
    fun `取值等待与 WAIT 系互切触发重扫`() {
        val value = RailScanGate.waitSignature(req(scriptLine = 42L), awaitingAnyKey = false)
        val anyKey = RailScanGate.waitSignature(req(scriptLine = 42L), awaitingAnyKey = true)
        assertNotEquals(value, anyKey)
        assertTrue(RailScanGate.mustScanNow(anyKey, value))
        assertTrue(RailScanGate.mustScanNow(value, anyKey))
    }

    // ------------------------------------------------------------------
    // mustScanNow 语义
    // ------------------------------------------------------------------

    /** 签名相同 ⇒ false（沿用节流）；不同 ⇒ true（绕过节流）。 */
    @Test
    fun `mustScanNow 只在签名不同时为真`() {
        assertFalse(RailScanGate.mustScanNow(7L, 7L))
        assertTrue(RailScanGate.mustScanNow(7L, 8L))
        assertTrue(RailScanGate.mustScanNow(8L, 7L))
    }

    /** 首帧：上一帧记 IDLE、本帧已在等待 ⇒ 立即重扫（不会漏掉开局第一页）。 */
    @Test
    fun `首帧已在等待时立即重扫`() {
        val waiting = RailScanGate.waitSignature(req(scriptLine = 1L), awaitingAnyKey = false)
        assertTrue(RailScanGate.mustScanNow(waiting, RailScanGate.IDLE_SIGNATURE))
    }
}
