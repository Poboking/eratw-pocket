package com.eratw.port.android

import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.mod.ModAudioWiring
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.uiElementsOf
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ModUiElement

/**
 * RT2 P0-LAST 的会话级交互缝绑定：建「交互生产触发点 + UiApi 真接线」两件套并挂到会话。
 *
 * 为什么住独立文件：`ModRuntimeAssembly` 的文件级函数数已到 detekt `TooManyFunctions` 阈值
 * （11/11），而 [ModRuntimeSession] 的构造签名被 baseline 的 `LongParameterList` 钉住、
 * `attach` 的环形复杂度也在阈值上沿 —— 所以这段后期装配逻辑放这里，`attach` 只留一行调用。
 *
 * 约定：
 * - 交互 wire 走**全链共享**管线（多 mod PRE/POST 全局序，见 `ModRuntimeHost.install` 的 adopt）；
 * - UI 派发经会话 `VmBoundaryScheduler.submit` 入队、页边界 `drainPending` 兑现
 *   （铁律「改状态一律经 runtime.submit」；主线程只读不可变快照）；
 * - 图片元素来源 = 作者经 `UiApi.registerImage` 的声明，宿主只读查询转发给表面
 *   （[imageElementsFrom]）；无声明时是空表，`ModUiSurface` 走零成本旁路。
 *
 * @param diagnostics 交互 handler 失败落诊断面（`DiagnosticDomain.INTERACTION`）。
 */
internal fun bindHostSeams(
    session: ModRuntimeSession,
    host: ModRuntimeHost,
    scheduler: VmBoundaryScheduler,
    logSink: (String) -> Unit,
    diagnostics: RuntimeDiagnostics,
) {
    val uiSurface = ModUiSurface(
        host = host,
        submit = { action -> scheduler.submit(action) },
        logSink = logSink,
        // 真接线（RT2 P1 资源注入第二刀）：作者声明的图片元素 → 浮层快照。
        imageElements = imageElementsFrom(host),
        stringResolver = modUiStringResolver(host),
    )
    // 装配即拍一次：会话开态、ModRuntimeHost.install 已完成，贡献项快照可读。
    uiSurface.refresh()
    session.hostSeams = ModHostSeams(
        interactionWire = ModInteractionWire(
            pipeline = host.interactionPipeline,
            diagnostics = diagnostics,
            logSink = logSink,
        ),
        uiSurface = uiSurface,
    )
}

/**
 * 浮层图片元素的**生产来源缝**：按包查作者经 `UiApi.registerImage` 的声明。
 *
 * 单独成文件级函数而不是就地写 lambda：装配层探针用例直接测这一份（`ModUiImageWiringTest`
 * 的「真装配探针」），注掉/改坏它必须让探针红——避免测试自己另抄一份等价 lambda，
 * 那样只能证明「两份 lambda 都能跑」，证明不了生产装配真的接上了。
 *
 * 每次 refresh 按包查；包未装载 / 未声明时返回空表，不制造占位元素。
 */
internal fun imageElementsFrom(host: ModRuntimeHost): (String) -> List<ModUiElement.Image> =
    { packageId -> host.uiElementsOf(packageId) }

/**
 * 浮层标签的作者文案解析器（生产装配的唯一一处）：把 `(packageId, labelKey, 语言码)` 递给
 * 会话级文案表 [ModRuntimeHost.strings]，未声明返回 `null` ⇒ 浮层回落 `PackageId / 键`。
 *
 * 为什么独立成函数而不是就地写 lambda：app 侧探针用例（`ModUiStringResolverTest`）直接对
 * **生产这一份实现**断言，避免用例自己另写一份解析逻辑造成「台架绿、真机白字」。
 *
 * 语言码由渲染侧逐帧传入（`ModUiCopy.lang.stored` = 壳层唯一读取单路 `UiPrefs.settingsLanguage`
 * 的结果）：装配期（[bindHostSeams]）没有 Android `Context`，在这里读偏好会自判语言、违反
 * AGENTS §6；作者侧 `context.strings.of()` 另读 [ModRuntimeHost.language]（壳层注入）。
 */
internal fun modUiStringResolver(host: ModRuntimeHost): (String, String, String) -> String? =
    { packageId, labelKey, language -> host.strings.resolve(packageId, labelKey, language) }

/**
 * mod 音频面装配（Lane AN；RT2 资源注入第二刀）：建宿主播放汇并挂进会话。
 *
 * 三件事收在一处，`attach` 只留一行调用（理由同 [bindHostSeams]：`ModRuntimeAssembly` 的
 * 文件级函数数与 `attach` 长度都在 detekt 阈值上沿）：
 * 1. [ModAudioSinkImpl] = platform `MediaPlayerChannelDecoder` 的后台执行器（prepare/start 都不在
 *    VM 线程；失败只 WARN 不抛）；
 * 2. `host.audioSink` = 让 `context.audio` 从 fail-closed 桩切到真实现（`install` 前的 adopt 读它）；
 * 3. [ModAudioWiring.install] = 挂会话级槽，供收口 `uninstall` 停声与旁路调用点按包播放。
 *
 * 必须在 `host.install` 之前调用：作者的 `onLoad` 里就可能放声音。
 *
 * @param store 本会话变量存储（`store.aux` 挂槽，无静态可变全局）。
 * @param host 本会话宿主；`resources` 未装配（关态 / 手工装配）⇒ 静默跳过，走 fail-closed 桩。
 */
internal fun bindAudioSink(
    store: VariableStore,
    host: ModRuntimeHost,
    logSink: (String) -> Unit,
) {
    val resources = host.resources ?: return
    val sink = ModAudioSinkImpl(logSink = logSink)
    host.audioSink = sink
    ModAudioWiring.install(store, sink, resources, logSink)
}
