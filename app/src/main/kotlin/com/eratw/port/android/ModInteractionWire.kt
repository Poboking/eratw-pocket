package com.eratw.port.android

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.mod.InteractionPipeline
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.InteractionContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * **玩家真实输入 → 交互管线**的生产触发点（RT2 P0-LAST 第一条死路，§21.4 接线）。
 *
 * 为什么需要这一层：core 的 [InteractionPipeline] 只有 `context.interactions.run(...)` 一条
 * 入口，而 mod 自己不会替玩家发起互动 —— 引擎/宿主在玩家操作时**从不**驱动它，作者注册的
 * PRE/POST 阶段钩子等于死代码。这里补上「输入状态机 → 交互管线」的缝：
 *
 *  - 采集在 [GameHost] 的输入漏斗（`deliver` / 颜色点击 / cbg 按钮）里，**UI/输入线程**写；
 *  - 消费在 [ModRuntimeSession.onTurnBoundary] 的**页边界只一次**（2026-10-31 真机 OOM 的
 *    频率纪律：禁止每帧轮询）；VM 线程 `getAndSet(0)` 取走证据，没新输入就零成本返回。
 *
 * 失败隔离口径与 core 既有管线一致：单个 handler 抛异常由管线记进 `failures` 并继续其它
 * handler（不中断边界）；本层再把每个失败包落到诊断面与日志、并兜住整次触发，**绝不把
 * 异常抛回页边界**（否则一次作者 bug 会掐掉整个 tick 链）。
 *
 * 线程契约：[notePlayerInput] 任意线程可调（只写 @Volatile / 原子计数）；
 * [consumeOnBoundary] 只许 VM 线程（由 session 边界驱动）。
 */
internal class ModInteractionWire(
    private val pipeline: InteractionPipeline,
    private val diagnostics: RuntimeDiagnostics?,
    private val logSink: (String) -> Unit,
) {

    /** 待消费的输入笔数；与 [lastSource]/[lastText] 一起构成「拍快照」，消费时清零。 */
    private val pending = AtomicInteger(0)

    @Volatile
    private var lastSource: String = SOURCE_NONE

    @Volatile
    private var lastText: String = ""

    /** 记录一笔玩家真实输入（任意线程；文本截断防止元数据撑爆诊断环）。 */
    fun notePlayerInput(source: String, text: String) {
        lastSource = source
        lastText = text.take(MAX_TEXT)
        pending.incrementAndGet()
    }

    /** 尚未消费的输入笔数（诊断/测试读数用）。 */
    fun pendingCount(): Int = pending.get()

    /**
     * 页边界消费一次输入证据并把它们送进共享交互管线。
     *
     * @return 本次是否真的触发了一次 `run`（false = 无新输入，零成本旁路）。
     */
    fun consumeOnBoundary(): Boolean {
        val count = pending.getAndSet(0)
        if (count <= 0) return false
        return dispatchPending(count)
    }

    /** 把本拍证据送进共享管线；宿主侧隔离异常，绝不抛回页边界。 */
    private fun dispatchPending(count: Int): Boolean {
        val source = lastSource
        val text = lastText
        val context = InteractionContext(
            interactionId = INTERACTION_ID,
            // 引擎侧没有「玩家角色」的扩展实体身份可传：EntityId 是 pkgShortId+generation+
            // handleSlot 的打包值，不是 chara 行号；无扩展实体时只能 NULL（mod-api 契约）。
            actor = EntityId.NULL,
            targets = emptyList(),
            primaryTarget = null,
            metadata = mapOf("source" to source, "text" to text, "count" to count.toString()),
        )
        val result = runCatching {
            pipeline.run(context) { null }
        }.getOrElse { t ->
            logSink(
                "mod.interaction 页边界输入 source=$source count=$count 触发异常已隔离（边界不中断）：" +
                    "${t.javaClass.name}: ${t.message}",
            )
            return false
        }
        // core 已按包隔离单个 handler 的失败；宿主这边把它落到诊断面 + 日志（可观测，不是第二次吞）。
        for (packageId in result.failures) {
            diagnostics?.record(
                day = null,
                phase = null,
                domain = DiagnosticDomain.INTERACTION,
                packageId = packageId,
                key = INTERACTION_ID,
                outcome = DiagnosticOutcome.FAILED,
                reasonCode = "HANDLER_THREW",
                detail = { "player input source=$source count=$count" },
            )
            logSink("mod.interaction 页边界输入 source=$source count=$count 失败已隔离 pkg=$packageId")
        }
        if (result.interrupted || result.replaced) {
            logSink(
                "mod.interaction 页边界输入 source=$source count=$count " +
                    "interrupted=${result.interrupted} replaced=${result.replaced}",
            )
        }
        return true
    }

    /**
     * 回合级触发（ModDX-4 M4 判据 2）：页边界**每拍**一次，把 [contextProvider] 产出的上下文
     * （witness frame 在 metadata / targets 里，读数见 `ModWitnessFrameReader`）送进共享管线——
     * 与 [notePlayerInput] 的输入触发（id = [INTERACTION_ID]）并列的第二条生产触发点。
     *
     * 诚实口径：本触发 = **回合级**（页边界节拍，mod 互动钩子每拍可观察）；COM 级接线
     * （真实互动发起时机的输入状态机缝）登记后续波。宿主只承诺 PRE/POST 相位到达性；
     * PRECONDITION / MAIN / SETTLEMENT 若被声明注册，按 [InteractionPipeline] 既有五阶段
     * 语义围绕空主流程（main = null）运行，宿主不赋予额外语义、不拦（管线自身 INTERRUPT
     * 可中止本拍派发）。
     *
     * 零开销口径（判据 4）：管线无任何已注册 handler（= 无 mod 声明并注册 interaction
     * hook，[InteractionPipeline.hasRegisteredHandlers]）时直接旁路返回 false——
     * **先闸后建**：[contextProvider] 不被调用，witness 读数一次都不发生。
     *
     * 失败隔离与 [consumeOnBoundary] 同款：单个 handler 抛异常由管线按包隔离进 failures
     * （诊断面 + 日志留痕），整次触发异常由本层兜住，绝不抛回页边界。
     *
     * @return 本次是否真的触发了一次 run（false = 无注册 handler 零成本旁路 / 异常已隔离）。
     */
    fun runTurnFrame(contextProvider: () -> InteractionContext): Boolean =
        if (!pipeline.hasRegisteredHandlers()) {
            false
        } else {
            dispatchTurnFrame(contextProvider())
        }

    /** 单次回合级派发（异常兜底 + 失败回写 + 读数行）；保证不把异常抛回页边界。 */
    private fun dispatchTurnFrame(context: InteractionContext): Boolean {
        val result = runCatching { pipeline.run(context) { null } }.getOrElse { t ->
            logSink(
                "mod.interaction 回合级触发 id=${context.interactionId} 异常已隔离（边界不中断）：" +
                    "${t.javaClass.name}: ${t.message}",
            )
            return false
        }
        // core 已按包隔离单个 handler 的失败；宿主这边落诊断面 + 日志（可观测，不是第二次吞）。
        for (packageId in result.failures) {
            diagnostics?.record(
                day = null,
                phase = null,
                domain = DiagnosticDomain.INTERACTION,
                packageId = packageId,
                key = context.interactionId,
                outcome = DiagnosticOutcome.FAILED,
                reasonCode = "HANDLER_THREW",
                detail = { "turn boundary id=${context.interactionId}" },
            )
            logSink("mod.interaction 回合级触发 id=${context.interactionId} 失败已隔离 pkg=$packageId")
        }
        // 有活动的拍留一行读数（取证面，非玩家可见文案）；频率 = 页边界（非每帧，2026-10-31 频率纪律）。
        logSink(
            "mod.interaction 回合级触发 id=${context.interactionId} " +
                "witness=${context.metadata[WITNESS_COUNT_KEY] ?: "0"}",
        )
        return true
    }

    companion object {
        /** 宿主输入互动的固定 id（引擎无 interaction key 声明字段，只能自造稳定标识）。 */
        const val INTERACTION_ID: String = "host.input"

        /** 宿主回合级互动的固定 id（ModDX-4 M4；witness frame 经 metadata 透传）。 */
        const val TURN_INTERACTION_ID: String = "host.turn"

        /** 回合级 metadata 键：在场者角色号清单（逗号分隔，升序；不含 TARGET 本身）。 */
        const val WITNESS_KEY: String = "witness"

        /** 回合级 metadata 键：在场者人数（与 [WITNESS_KEY] 条目数一致）。 */
        const val WITNESS_COUNT_KEY: String = "witnessCount"

        /** 回合级 metadata 键：当前 TARGET 角色号（无效 = -1，如实透传不发明）。 */
        const val TARGET_KEY: String = "target"

        /** 回合级 metadata 键：TARGET 的现在位置读数（`CFLAG` 300 号元素；读不到缺席）。 */
        const val TARGET_POS_KEY: String = "targetPos"

        /** 回合级 metadata 键：边界游戏日读数（与页边界 ② 同一 `DAY` 读数源；读不到缺席）。 */
        const val DAY_KEY: String = "day"

        /** 采集来源：控制台按钮点击（含鼠标色字/bg 按钮）、控制台命令/键盘提交。 */
        const val SOURCE_CLICK: String = "click"

        /** 采集来源：命令行输入/回车提交。 */
        const val SOURCE_COMMAND: String = "command"

        private const val MAX_TEXT: Int = 200
        private const val SOURCE_NONE: String = "none"
    }
}

/**
 * 交互触发点与 UI 表面的后期装配束（RT2 P0-LAST 两条死路共用）。
 *
 * 由 `ModRuntimeAssembly.attach` 在会话构造后赋值一次（`ModRuntimeSession.hostSeams`），
 * 缺省 null = 关态/无 mod，全部旁路。GameHost 采集输入时读 `interactionWire`，
 * MainActivity 取 UI 表面时读 `uiSurface`。
 */
internal class ModHostSeams(
    val interactionWire: ModInteractionWire,
    val uiSurface: ModUiSurface,
)
