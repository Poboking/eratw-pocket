package com.eratw.port.platform.input

import com.eratw.port.core.host.InputRequest

/**
 * 右栏选项扫描的「这一帧必须立刻重扫」判据
 * （2026-11-12 泡茶子选单「点红茶没反应」案的壳层根因）。
 *
 * ## 根因
 * `MainActivity.refreshShell` 对右栏扫描做了 `OPTION_SCAN_MS`（250ms）节流。等待页切换
 * （命令面板 → QOL 子选单弹窗）时，引擎侧的选项快照在 `noteInputWait` 入口就换了，
 * 但壳层要等最多 250ms 才重读 ⇒ 玩家在这段窗口里看到的仍是**上一页的按钮芯片**。
 * 点上去提交的是旧页的值：QOL 子选单的 `INPUT -1, 1` 收到面板按钮值（如 301），
 * `INRANGE(INPUT_VALUES, 0, OPTIONNUM)` 不成立 ⇒ 变体没切、弹窗直接返回
 * —— 玩家看到的就是「有选择窗口了但是点红茶没反应」。
 *
 * 这与既有 `shell.awaitingAnyKey` 那条注释是同一类缺陷（「不能放在 250ms 节流之后，
 * 否则脚钮慢半拍」）：**壳层有一批状态必须当帧跟上，不能吃节流**。
 *
 * ## 为什么不干脆每帧重扫
 * 每帧重扫会退回 2026-10-31 真机 OOM 现场：四五十枚候选 × 每 16ms 重建指纹，
 * 堆贴顶时那一发 16 字节分配就是压垮进程的最后一根稻草。所以只在**等待页身份变了**
 * 的那一帧绕过节流，其余帧照旧节流（同页重试、AWAIT 轮询重印都不额外扫）。
 *
 * ## 等待页身份由哪些位组成
 * 全部是廉价读、零分配（只做 enum ordinal 与 long 算术），可每帧调用：
 * - [InputRequest.scriptLine]：发起等待的脚本行标识。「同一函数内稳定、换行即变、
 *   `-1` = 未知」——**最强的一位**，直接表达「引擎走到另一条 INPUT 语句了」，
 *   且同页重试时不变（正好保持节流省电）；
 * - `form` / `type` / `mouseInput` / `hasDef`：`scriptLine` 未接线（`-1`）时的退化判据。
 *   本案面板页是 `INPUTS`（STR）、弹窗页是 `INPUT -1, 1`（INT + 鼠标 + 有默认值），
 *   即使 scriptLine 缺位也能分开；
 * - `awaitingAnyKey`：WAIT 系与取值等待互切。
 *
 * ## 稳定性（为什么不会每帧都为真）
 * 等待期内引擎的 `InputRequest` 是同一份值（`QueueInputSink.awaiting` 直到等待结束），
 * 上面每一位都不变 ⇒ 签名恒定 ⇒ 一页只触发一次立即重扫。签名是**值签名**，
 * 引擎换实例但值相同也不会误判。
 */
object RailScanGate {

    /** 无等待态（既没有取值等待也不在 anyKey 等待）的身份常量，不随帧变化。 */
    const val IDLE_SIGNATURE: Long = 0L

    /** 签名的乘法混入基（与仓内其余 64 位指纹同一套 `h * MIX + 位` 口径）。 */
    private const val MIX: Long = 31L

    /** 签名初值：非 0，保证「有等待」的签名不会与 [IDLE_SIGNATURE] 撞上。 */
    private const val SEED: Long = 17L

    /** 某个身份位缺位（请求 null / `scriptLine` 未接线）时的占位值，与真实值域不相交。 */
    private const val ABSENT: Long = -1L

    /**
     * 等待态身份签名；`request == null && !awaitingAnyKey`（没有等待方）恒为 [IDLE_SIGNATURE]。
     *
     * 纯函数、零分配。调用方每帧算一次，与上一帧比较即可判断「等待页是否换了」。
     */
    fun waitSignature(request: InputRequest?, awaitingAnyKey: Boolean): Long {
        if (request == null && !awaitingAnyKey) return IDLE_SIGNATURE
        var s = SEED
        s = s * MIX + if (request != null) 1L else 0L
        s = s * MIX + (request?.form?.ordinal?.toLong() ?: ABSENT)
        s = s * MIX + (request?.type?.ordinal?.toLong() ?: ABSENT)
        s = s * MIX + if (request?.mouseInput == true) 1L else 0L
        s = s * MIX + if (request?.hasDef == true) 1L else 0L
        s = s * MIX + if (awaitingAnyKey) 1L else 0L
        // scriptLine 放最后直接混入：换等待语句 ⇒ 签名必变 ⇒ 右栏当帧换页。
        s = s * MIX + (request?.scriptLine ?: ABSENT)
        return s
    }

    /**
     * 本帧是否必须重扫右栏（绕过节流）。
     *
     * @param signature 本帧 [waitSignature]
     * @param lastSignature 上一帧的签名；首帧传 [IDLE_SIGNATURE]
     * @return true = 等待页换了，当帧重扫；false = 沿用节流
     */
    fun mustScanNow(signature: Long, lastSignature: Long): Boolean = signature != lastSignature
}
