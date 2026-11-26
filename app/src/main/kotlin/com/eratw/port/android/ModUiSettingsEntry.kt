package com.eratw.port.android

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable

/**
 * 设置页的 mod UI 贡献项读数口（RT2 P0-LAST 第二条死路的第三个宿主：设置页）。
 *
 * ## 为什么需要这条通道
 * 活 [GameHost] 只挂在 [MainActivity] 的实例字段上（`MainActivity.host`，MainActivity.kt:295），
 * 设置页既没有那只宿主、也不是同一个 Activity；[GameHost.modUiSurface] 是实例方法
 * （GameHost.kt:1609），而它背后的 `modRuntimeSession` 是私有字段（GameHost.kt:330）。
 * 本件照 `HotConfigChannel`（SettingsActivity.kt:2323）与 `ModImportSessionChannel`
 * （ModImportUi.kt:202）的同款形态，做**显式装卸的进程级读数口**：壳在宿主就绪处装活引用
 * （`{ host?.modUiSurface() }`），销毁段认主卸载，设置页渲染时现取现用。
 *
 * 不装也不崩：read 为 null ⇒ [ModUiOverlay] 的 surfaceProvider 返回 null ⇒ 整块不渲染
 * （＝ 还没有这条入口时的行为），设置页其余部分零影响。
 *
 * ## 为什么不用 SessionVault.peek()
 * [SessionVault] 是「Activity 被系统销毁时的交接槽」，不是常驻注册表：
 * `takeoverIfAlive()` 取出宿主即清槽（SessionVault.kt:63-68，`VaultCore.takeover` :99-106），
 * 正常游玩期间槽是**空**的，只有旧壳 onDestroy 才回填（MainActivity.kt:1791）。拿它当缝
 * ＝ 平时永远不显示（假接线），所以本件单独开通道，不复用该槽。
 *
 * ## 页边界消费落在哪
 * 本件只读表面 + 点击入队（[ModUiSurface.click] 主线程，仅 `submit`）；**兑现点唯一在 VM
 * 线程页边界**：`ModRuntimeAssembly.kt:1298` 的 `onTurnBoundary()` → :1302
 * `scheduler.drainPending()` → :1357-1360 `consumeOnBoundary()` + `uiSurface.refresh()`。
 * `VmBoundaryScheduler.drainPending()` 有 `check(onVmThread())` fail-closed（:1122-1123），
 * 所以设置页离开/暂停时**不新增任何动作**：玩家返回游戏后 MainActivity.onStart 放行会话
 * （MainActivity.kt:1631），下一个页边界自然消费。这与 [MainActivity] 的做法逐字同口径 ——
 * 它的 onCreate/onPause/onStop 里也没有任何 mod 相关行，浮层厚度全在页边界那侧。
 *
 * TODO(verify)：真机上「设置页内点 mod 项 → 返回游戏后在下一回合边界兑现」需要一次实机
 * 走查（时间盒内无法跑真机）；判据 = 返回后日志面出现 `mod.ui 点击已兑现`（ModUiSurface.kt:116）。
 */
internal object ModUiSettingsEntry {

    /**
     * 运行中会话的 UI 表面提供者；null = 未接线（壳还没起 / 还没装）或已卸载。
     * provider 惰性取用：会话未装配完 / 已清场时它自己返回 null（`GameHost.modUiSurface` 对
     * 未 attach 的会话返回 null），两层判空合起来即「没有在跑的会话 ⇒ 不显示入口」。
     * 写入端在 UI 线程（MainActivity 生命周期两端），读取端在 UI 线程（设置页组合）⇒ `@Volatile`。
     */
    @Volatile
    private var liveSurface: (() -> ModUiSurface?)? = null

    /**
     * 当前接线的宿主身份（与 [liveSurface] 同生同灭）：口径同 `HotConfigChannel.wiredOwner`
     * （SettingsActivity.kt:2343）与 `CrashForensics.crashProviderOwner` —— 双壳交接窗口里
     * 旧壳的 onDestroy 可能跑在新壳接线**之后**（真机实测 `Activities: 2`），旧壳的卸载
     * 不得把新壳刚装好的读数口撅空。
     */
    @Volatile
    private var wiredOwner: Any? = null

    /**
     * 壳接线：装上 [provider] 并以 [owner]（装这条接线的宿主，通常为 [MainActivity]）为身份。
     * 幂等：重开一局会再次 install，后装者覆盖旧接线（`host` 换代时 provider 闭包同步换）。
     */
    fun install(owner: Any, provider: (() -> ModUiSurface?)?) {
        wiredOwner = owner
        liveSurface = provider
    }

    /** 认主卸载：只有归属正是 [owner] 才摘（旧壳无权撅新壳刚装的接线）。 */
    fun uninstall(owner: Any) {
        if (wiredOwner === owner) {
            wiredOwner = null
            liveSurface = null
        }
    }

    /** 现取现用：未接线或会话未装配时为 null（调用方按「无入口」呈现）。 */
    fun read(): ModUiSurface? = liveSurface?.invoke()

    /** 当前接线身份；null = 未接线（装/摘是否成对的判据钉在这里）。 */
    internal fun wiredOwnerSnapshot(): Any? = wiredOwner
}

/**
 * 设置页内容根上的 mod 贡献项浮层：与 `MainActivity.kt:989` 的 `ModUiOverlay` 是同一件，
 * 只是读数口换成设置页侧通道（[ModUiSettingsEntry]）。
 *
 * 文案取既有三语表 [ModUiCopy]（[ModUiOverlay] 自带的入口/面板/结果句全在里面），本件
 * 不新增文案键、不读偏好、不判语言 —— 语言由宿主经 [language] 传进来（`SettingsScreen`
 * 已有该形参，故**不加形参**）。
 */
@Composable
internal fun BoxScope.ModUiSettingsOverlay(language: SettingsLanguage) {
    ModUiOverlay(
        surfaceProvider = { ModUiSettingsEntry.read() },
        copy = ModUiCopy.of(language),
    )
}
