package com.eratw.port.android

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.SettingsModel

/**
 * 设置页两处重置的**唯一编排** —— 纯 Kotlin、零 Android 依赖，JVM 单测直测。
 *
 * 两条路径共用同一份出厂目标表（[FactoryConfigDefaults.resetTargets]），只有副作用边界不同：
 *   * 顶栏「全部重置」= 设置页**所有功能**的出厂值：`GAME` 组条目回到出厂 `emuera.config`
 *     的目标值（与原版「改完点保存」同一个落点，spec: `ConfigDialog.cs:665-670` 的「保存」按钮
 *     只落盘、`:672-677` 的「保存并重启」落盘后顺带重启），**不写盘**；随后清移植版应用偏好；
 *   * 底部「恢复默认设置…」= 只恢复**原版设置页那批项**（`GAME` 组 —— 原版保存路径只遍历
 *     `configArray`，spec: `ConfigData.cs:430`）并**立即写盘**；不碰任何应用偏好。
 *
 * 条目写入一律走 [FactoryConfigDefaults.apply]（`fixed.config` 钉住的条目只计数、不强解钉，
 * spec: `ConfigItem.cs:20-27`），两个函数都把它的 `written` / `pinned` 原样带出，供上屏文案
 * 如实计数（R1.5）。这里只编排：Toast、`recreate()`、语言判定都留在 Activity 壳层。
 */
internal object ResetPaths {

    /**
     * 跨 Activity 重建交接的一次性目标表（UI 线程独占；[consumeStagedGameReset] 取走即清空）。
     *
     * 只在进程内存里活一轮：不写 SharedPreferences、不跨进程持久化。
     */
    private var stagedGameReset: Map<ConfigKey, ConfigValue>? = null

    /** 顶栏「全部重置」的结论：条目计数（[written] 写回 / [pinned] 被钉住）。 */
    data class AllResetOutcome(val written: Int, val pinned: Int)

    /**
     * 顶栏「全部重置」：`apply` → 清偏好 → 置一次性重启标记，顺序是**硬约束**。
     *
     * 标记必须在清偏好**之后**置：清偏好是对同一份 SharedPreferences 整表 clear
     * （`UiPrefs.kt:951-956` 的 `resetAll`），先置的标记会被一并抹掉
     * （标记键见 `UiPrefs.kt:935`），返回游戏时 [MainActivity] 就没有「重开生效」可消费。
     *
     * 不写盘、不回滚：本路径的契约就是「游戏配置留在模型里等玩家点保存」
     * （spec: `ConfigDialog.cs:665-670`），写盘与失败口径归 [gameItemsOnly]。
     *
     * 回调异常**向上传播**、不静默吞：`resetPrefs` 抛错时不会继续置标记，由调用方按自己的
     * 失败口径处理；这里不 try/finally 兜底成「半套状态」。
     */
    fun allReset(
        model: SettingsModel,
        targets: Map<ConfigKey, ConfigValue>,
        resetPrefs: () -> Unit,
        markRestartPending: () -> Unit,
    ): AllResetOutcome {
        val outcome = FactoryConfigDefaults.apply(model, targets)
        resetPrefs()
        markRestartPending()
        return AllResetOutcome(outcome.written, outcome.pinned)
    }

    /** 底部「恢复默认设置…」的结论：条目计数 + 是否真的写进了 `emuera.config`。 */
    data class GameItemsOutcome(val written: Int, val pinned: Int, val diskWritten: Boolean)

    /**
     * 底部「恢复默认设置…」：只把 `GAME` 组条目回到 [targets] 并立即写盘。
     *
     * `apply` → `writeDisk()`；`writeDisk` 返回 false ⇒ [SettingsModel.restore] 把内存模型
     * 回滚到进页时的 `baseline`（与原版取消即丢弃同一口径，spec: `ConfigDialog.cs:679-683`），
     * 并原样返回 `written` / `pinned` 与实际写盘状态，让提示不说「已写盘」。
     *
     * 本函数**没有**偏好 / 重启标记的回调参数 —— 覆盖范围由签名钉死（R3.1）：恢复原版项
     * 不得清应用偏好、不得置重启标记、不得切本页语言或重开会话。
     */
    fun gameItemsOnly(
        model: SettingsModel,
        targets: Map<ConfigKey, ConfigValue>,
        writeDisk: () -> Boolean,
    ): GameItemsOutcome {
        val outcome = FactoryConfigDefaults.apply(model, targets)
        if (!writeDisk()) {
            model.restore()
            return GameItemsOutcome(outcome.written, outcome.pinned, diskWritten = false)
        }
        return GameItemsOutcome(outcome.written, outcome.pinned, diskWritten = true)
    }

    /**
     * 把「全部重置」的目标表寄存下来，交给 `recreate()` 之后的新实例取走回填。
     *
     * 为什么需要一条跨重建的通道：顶栏重置按原版口径**不写盘**（spec: `ConfigDialog.cs:665-670`
     * 的「保存」按钮才落盘），但清完偏好必须 `recreate()` 让 `remember { UiPrefs.xxx }` 快照失效；
     * 而 `SettingsActivity.onCreateCore` 每次重建都会 `SettingsStore.load()` 把盘上的玩家旧值
     * 读回（`SettingsActivity.kt:362-364`），内存里刚重置的目标值会被这一读覆盖。于是把目标表
     * 先寄存一次，新实例 load 完再取走回填 —— 仍然不写盘，落盘仍旧等玩家点「保存 / 保存并重启」。
     *
     * 调用时机是硬约束：**必须在 [allReset] 成功返回之后**调。`resetPrefs` 抛异常时它之前不会
     * 执行；否则下次重建会莫名其妙回填一份根本没发生过的重置。
     */
    fun stageGameReset(targets: Map<ConfigKey, ConfigValue>) {
        stagedGameReset = targets
    }

    /** 取走寄存的目标表并立即清空（一次性）；没有寄存时返回 null。 */
    fun consumeStagedGameReset(): Map<ConfigKey, ConfigValue>? {
        val staged = stagedGameReset
        stagedGameReset = null
        return staged
    }
}
