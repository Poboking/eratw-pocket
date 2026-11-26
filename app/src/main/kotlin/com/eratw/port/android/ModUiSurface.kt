package com.eratw.port.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwGhostButton
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.android.ui.design.ShellPress
import com.eratw.port.android.ui.design.ShellShape
import com.eratw.port.android.ui.design.ShellSpace
import com.eratw.port.android.ui.design.ShellType
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.modapi.ModUiElement
import kotlinx.coroutines.delay

/** 一条玩家可见的 mod 交互项（`UiApi.registerInteraction(labelKey, onClick)` 的注册产物）。 */
internal data class ModUiContribution(
    /** 注册方的 PackageId（跨包去重的第一键）。 */
    val packageId: String,
    /** 作者给的标签键（所在域三语文案表键；这里原样展示，不做翻译）。 */
    val labelKey: String,
)

/**
 * 一条浮层图片元素贡献（[ModUiElement.Image] + 归属包）。
 *
 * 元素本身只描述意图（资源名 / 替代文本 / 期望最大边长），字节在渲染时经
 * [ModUiSurface.requestImageBytes] 按包名 + 资源名读取。
 */
internal data class ModUiImageContribution(
    /** 元素归属包（资源面按包隔离）。 */
    val packageId: String,
    /** 作者声明的图片元素。 */
    val element: ModUiElement.Image,
)

/**
 * 一次点击派发的回传结果（UI 读它回显执行结果/失败；`failure == null && handled == false`
 * 表示该项在队列兑现前已被撤销）。
 */
internal data class ModUiClickOutcome(
    val packageId: String,
    val labelKey: String,
    val handled: Boolean,
    val failure: Throwable?,
)

/**
 * `UiApi` 的**真接线**表面（RT2 P0-LAST 第二条死路）。
 *
 * 现状问题：`registerInteraction` 只往 per-context 的 `InstanceUiRegistry` 里塞一个
 * `LinkedHashMap` 条目，Android 侧没人枚举、没人渲染、没人派发。这里补上三段：
 *
 *  1. **枚举**（[refresh]）：VM 线程把 `host.installedContexts` 的已注册键拍成不可变快照，
 *     快照写 @Volatile，主线程只读（不跨线程碰可变注册表）；
 *  2. **渲染**（[ModUiOverlay]）：独立 Composable 浮层，只在「有贡献项」时出现；
 *  3. **派发**（[click]）：主线程只把动作 `submit` 进会话的 VmBoundaryScheduler 队列
 *     （铁律：改状态一律经 runtime.submit），VM 页边界兑现时才真正执行作者 handler；
 *     执行结果/异常写 @Volatile 回传给 UI。
 *
 * 文案（入口/面板）由调用侧从 [ModUiCopy] 三语表取好传入本件 —— 本类**不读偏好、不判语言**，
 * 与 QuickMacroUi 的既有前端件同一口径。
 *
 * 线程契约：[refresh] 只许 VM 线程；[contributions]/[images]/[lastOutcome] 任意线程读；
 * [click]/[requestImageBytes] 主线程（仅入队）。
 *
 * @param imageElements 图片元素来源缝：`packageId -> 该包声明的元素`。**缺省空表**（行为零
 *   变化）；生产上由装配侧注入「作者声明面」的读取实现 —— 元素注册面（`UiApi`）尚未接线，
 *   见交付里的「需组长接线」。
 * @param stringResolver 作者自带文案解析缝：`(packageId, labelKey, languageCode) -> 文案`；
 *   **缺省恒 null ⇒ 既有装配逐位不变**（标签仍是 `PackageId / 键` 技术标识）。生产上由
 *   `ModHostSeamWiring` 绑到会话级文案表（RT2 多语言文案，见 [labelOf]）。
 */
internal class ModUiSurface(
    private val host: ModRuntimeHost,
    private val submit: (() -> Unit) -> Unit,
    private val logSink: (String) -> Unit,
    private val imageElements: (String) -> List<ModUiElement.Image> = { emptyList() },
    internal val stringResolver: (String, String, String) -> String? = { _, _, _ -> null },
) {

    @Volatile
    private var snapshot: List<ModUiContribution> = emptyList()

    @Volatile
    private var imageSnapshot: List<ModUiImageContribution> = emptyList()

    @Volatile
    private var outcome: ModUiClickOutcome? = null

    /** 重拍贡献项快照（VM 线程；装载/卸载/注册变化后调用）。 */
    fun refresh() {
        snapshot = host.installedContexts
            .flatMap { context ->
                context.uiRegisteredKeys().map { labelKey -> ModUiContribution(context.packageId, labelKey) }
            }
            .sortedWith(compareBy({ it.packageId }, { it.labelKey }))
        imageSnapshot = collectImageContributions()
    }

    /**
     * 拍图片元素快照：来源缝抛错（装配侧实现有 bug）也只降级为空表并记 WARN，
     * 不让一张图把整个浮层快照弄没。
     */
    private fun collectImageContributions(): List<ModUiImageContribution> =
        runCatching {
            host.installedContexts
                .flatMap { context ->
                    imageElements(context.packageId).map { element ->
                        ModUiImageContribution(context.packageId, element)
                    }
                }
                .sortedWith(compareBy({ it.packageId }, { it.element.resourceName }))
        }.onFailure { logSink("mod.ui 图片元素快照失败已降级为空表：${it.javaClass.name}: ${it.message}") }
            .getOrDefault(emptyList())

    /** 当前贡献项不可变快照（主线程读；空 = 不渲染入口）。 */
    fun contributions(): List<ModUiContribution> = snapshot

    /** 当前图片元素不可变快照（主线程读；空 = 不渲染图片区）。 */
    fun images(): List<ModUiImageContribution> = imageSnapshot

    /** WARN 落点（渲染侧解码降级等由本类转进会话日志）；以函数值暴露，不额外占一个成员函数。 */
    val logWarn: (String) -> Unit
        get() = logSink

    /** 最近一次点击的派发结果（主线程低频轮询读）。 */
    fun lastOutcome(): ModUiClickOutcome? = outcome

    /**
     * 主线程点击派发：只入队，不就地执行作者 handler。
     *
     * @return 该项是否仍在当前快照里（false = 已被撤销，未派发）。
     */
    fun click(contribution: ModUiContribution): Boolean {
        if (contribution !in snapshot) {
            logSink("mod.ui 点击被拒（贡献项已撤销）：${contribution.packageId}/${contribution.labelKey}")
            return false
        }
        submit { dispatch(contribution) }
        return true
    }

    /** VM 页边界兑现（在 `runtime.submit` 队列里跑）：执行作者 handler 并回传结果/失败。 */
    private fun dispatch(contribution: ModUiContribution) {
        val context = host.installedContexts.firstOrNull { it.packageId == contribution.packageId }
        if (context == null) {
            outcome = ModUiClickOutcome(contribution.packageId, contribution.labelKey, handled = false, failure = null)
            logSink("mod.ui 派发时包已不在会话里：${contribution.packageId}/${contribution.labelKey}")
            return
        }
        runCatching { context.uiClick(contribution.labelKey) }
            .onSuccess { handled ->
                outcome = ModUiClickOutcome(contribution.packageId, contribution.labelKey, handled, null)
                logSink(
                    "mod.ui 点击已兑现 pkg=${contribution.packageId} key=${contribution.labelKey} " +
                        "handled=$handled",
                )
            }
            .onFailure { t ->
                outcome = ModUiClickOutcome(contribution.packageId, contribution.labelKey, handled = false, failure = t)
                logSink(
                    "mod.ui 点击失败已隔离 pkg=${contribution.packageId} key=${contribution.labelKey}：" +
                        "${t.javaClass.name}: ${t.message}",
                )
            }
    }

    /**
     * 主线程请求一张图片的字节：把读取**递到 VM 线程**（`ModResourceApi` 的线程契约是
     * 「只在 VM 线程调用」），结果经 [deliver] 回调（VM 线程调用一次；投递失败时同步回调 null）。
     *
     * 读失败（缺包 / 资源面 fail-closed / 名字非法 / 读盘异常 / OOM）一律 null + WARN，
     * 绝不把异常抛回 Compose。
     */
    fun requestImageBytes(packageId: String, resourceName: String, deliver: (ByteArray?) -> Unit) {
        val queued = runCatching {
            submit { deliver(readImageBytes(packageId, resourceName)) }
        }
        if (queued.isFailure) {
            val failure = queued.exceptionOrNull()
            logSink(
                "mod.ui 图片读取投递失败已降级 pkg=$packageId name=$resourceName：" +
                    "${failure?.javaClass?.name}: ${failure?.message}",
            )
            deliver(null)
        }
    }

    /** VM 线程：按包名 + 资源名读字节；所有失败面都收敛成 null（调用侧降级为占位）。 */
    private fun readImageBytes(packageId: String, resourceName: String): ByteArray? {
        val context = host.installedContexts.firstOrNull { it.packageId == packageId }
        if (context == null) {
            logSink("mod.ui 图片读取时包已不在会话里：$packageId/$resourceName")
            return null
        }
        val result = runCatching { context.resources.open(resourceName) }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            logSink(
                "mod.ui 图片读取失败已降级 pkg=$packageId name=$resourceName：" +
                    "${failure.javaClass.name}: ${failure.message}",
            )
        }
        val bytes = result.getOrNull()
        if (failure == null && bytes == null) {
            logSink("mod.ui 图片缺字节已降级 pkg=$packageId name=$resourceName")
        }
        return bytes
    }

    /** 诊断读数：当前快照贡献项数。 */
    fun contributionCount(): Int = snapshot.size
}

/**
 * 游戏内 mod 交互浮层：入口只在「有贡献项或图片元素」时出现，展开后逐条渲染作者注册项 /
 * 图片元素。
 *
 * 刷新是**低频**的（[POLL_MILLIS] 级 LaunchedEffect，禁每帧轮询）；点击经 [ModUiSurface.click]
 * 走 `runtime.submit` 队列，结果/失败由 [ModUiSurface.lastOutcome] 回显；图片字节经
 * [ModUiSurface.requestImageBytes] 递到 VM 线程读、IO 线程解码（见 [ModUiImageElementView]），
 * 坏图/缺图只降级为替代文本或占位框，不崩浮层。
 */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.ModUiOverlay(
    surfaceProvider: () -> ModUiSurface?,
    copy: ModUiCopy,
) {
    val surface = surfaceProvider() ?: return
    var expanded by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(surface) {
        while (true) {
            delay(POLL_MILLIS)
            tick += 1
        }
    }
    // 读 tick 让状态订阅成立：每秒重拍一次快照，避免主线程高频触碰 VM 侧注册表。
    val contributions = remember(tick, surface) { surface.contributions() }
    val images = remember(tick, surface) { surface.images() }
    if (contributions.isEmpty() && images.isEmpty()) return
    Column(modifier = Modifier.align(Alignment.TopEnd).padding(ENTRY_PADDING)) {
        if (!expanded) {
            EratwGhostButton(text = copy.entryLabel, onClick = { expanded = true })
        } else {
            ModUiExpandedPanel(
                surface = surface,
                copy = copy,
                contributions = contributions,
                images = images,
                onClose = { expanded = false },
            )
        }
    }
}

/**
 * 展开后的浮层面板：标题行（含关闭）× 说明 + 图片元素 + 作者注册项 + 最近一次点击结果。
 *
 * 面板长相走壳层 token：面板底比游戏画面暗一档、Sheet 圆角、一像素描边。旧版取的是系统主题的
 * surface 色 —— 壳层没有装配自己的 M3 主题，落到实机上就是一块与壳层无关的默认灰紫。
 *
 * 关闭从「面板底部一行按钮」提到标题行右端的方块钮：浮层可能被作者塞进十几项，
 * 关闭压在列表末尾时玩家得先滚到底才能退。关掉涟漪、按 [ShellPress] 压一层灰。
 */
@Composable
private fun ModUiExpandedPanel(
    surface: ModUiSurface,
    copy: ModUiCopy,
    contributions: List<ModUiContribution>,
    images: List<ModUiImageContribution>,
    onClose: () -> Unit,
) {
    Surface(
        color = EratwInk.panelAlt(),
        tonalElevation = 0.dp,
        shape = ShellShape.Sheet,
        border = BorderStroke(1.dp, EratwInk.border()),
    ) {
        Column(
            modifier = Modifier
                .padding(ShellSpace.DialogPadding)
                .widthIn(max = PANEL_MAX_WIDTH),
            verticalArrangement = Arrangement.spacedBy(ShellSpace.CardGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = copy.panelTitle,
                    color = EratwInk.text(),
                    style = ShellType.Section,
                    modifier = Modifier.weight(1f),
                )
                PanelCloseBox(label = copy.closeLabel, onClose = onClose)
            }
            Text(copy.panelHint, color = EratwInk.textMuted(), style = ShellType.Label)
            images.forEach { image ->
                ModUiImageElementView(
                    element = image.element,
                    packageId = image.packageId,
                    surface = surface,
                )
            }
            contributions.forEach { contribution ->
                ModUiActionRow(
                    label = surface.labelOf(contribution.packageId, contribution.labelKey, copy.lang.stored),
                    packageId = contribution.packageId,
                    onClick = { surface.click(contribution) },
                )
            }
            surface.lastOutcome()?.let { ModUiOutcomeLine(surface = surface, last = it, copy = copy) }
        }
    }
}

/**
 * 面板右上的关闭方块：视觉 20dp 的 ×，热区按 [ShellSpace.TouchMin] 撑满 ——
 * 浮层是压在游戏画面上的小面板，点偏一点就点到作者注册项上，热区不能跟着字缩。
 */
@Composable
private fun PanelCloseBox(label: String, onClose: () -> Unit) {
    val pressSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(ShellSpace.TouchMin)
            .clip(ShellShape.Inset)
            .background(ShellPress.tint(pressSource, EratwInk.border()))
            .clickable(
                interactionSource = pressSource,
                indication = null,
                onClick = onClose,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "×", color = EratwInk.textDim(), style = ShellType.Tab)
    }
}

/**
 * 最近一次点击的回执行：三态各自一色 + **各自一个符号**（失败 `!`、已执行 `✓`、被撤销 `·`）。
 *
 * 只靠颜色传意在色弱玩家眼里等于没有状态（M3 无障碍口径）；符号与字色同源，扫一眼就分得清。
 */
@Composable
private fun ModUiOutcomeLine(surface: ModUiSurface, last: ModUiClickOutcome, copy: ModUiCopy) {
    val item = surface.labelOf(last.packageId, last.labelKey, copy.lang.stored)
    val line = when {
        last.failure != null -> copy.fill(copy.failedLabelFormat, item)
        last.handled -> copy.fill(copy.executedLabelFormat, item)
        else -> copy.fill(copy.rejectedLabelFormat, item)
    }
    val tone = when {
        last.failure != null -> EratwInk.danger()
        last.handled -> EratwInk.accent()
        else -> EratwInk.textMuted()
    }
    val glyph = when {
        last.failure != null -> "!"
        last.handled -> "✓"
        else -> "·"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ShellSpace.Hairline),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = glyph, color = tone, style = ShellType.Tab)
        Text(text = line, color = tone, style = ShellType.Label)
    }
}

/**
 * 浮层里的一条作者注册项：整行可点、按压走 [ShellPress] 叠色、不带描边框。
 *
 * 为什么不套 [com.eratw.port.android.ui.design.ShellListRow]：那是「列表卡片」语义（描边 + 面板底），
 * 而这些项本来就贴在一块已经描了边的浮层面板上，再套一层框就是卡里套卡。这里只要「两行可点的文字」：
 * 主行是作者给的标签，副行是归属包 —— 一屏里挂着好几个包时，玩家点之前得知道这是谁加的。
 */
@Composable
private fun ModUiActionRow(label: String, packageId: String, onClick: () -> Unit) {
    val pressSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ShellSpace.RowMinHeight)
            .clip(ShellShape.Inset)
            .background(ShellPress.tint(pressSource, EratwInk.text()))
            .clickable(
                interactionSource = pressSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = ShellSpace.RowPadding, vertical = ShellSpace.CardGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = EratwInk.text(),
                style = ShellType.Tab,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = packageId,
                color = EratwInk.textMuted(),
                style = ShellType.Overline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 贡献项的显示标签：**先问作者自带三语文案**（[ModUiSurface.stringResolver] + 当前语言码），
 * 没声明（`null`）才回落宿主既有呈现 `PackageId / 键` —— 技术标识而不是空串，作者漏声明时
 * 屏上仍看得出是哪一项。
 *
 * 语言码由调用侧给 `ModUiCopy.lang.stored`（壳层唯一读取单路 `UiPrefs.settingsLanguage`
 * 在构造 copy 时已解析），本处不二次读偏好、不自判 Locale。
 */
internal fun ModUiSurface.labelOf(packageId: String, labelKey: String, language: String): String =
    stringResolver(packageId, labelKey, language) ?: "$packageId / $labelKey"
/** 浮层刷新间隔（毫秒）：低频轮询，禁每帧。 */
private const val POLL_MILLIS: Long = 1_000L

/** 入口与屏幕右上的距离（浮层不贴边）。 */
private val ENTRY_PADDING = 8.dp

/** 展开面板的宽度上限：面板是**辅助面**，不该盖住半个游戏画面。 */
private val PANEL_MAX_WIDTH = 320.dp
