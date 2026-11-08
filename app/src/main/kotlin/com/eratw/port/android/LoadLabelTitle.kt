package com.eratw.port.android

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.config.EmueraConfigParser
import java.io.File

/**
 * 装载等待标题的 LOADLABEL 取值（CONFIG-WIRE U4）。
 *
 * 原版装载序列在 `!Config.DisplayReport` 时 `PrintSingleLine(Config.LoadLabel)` +
 * `RefreshStrings`（Process.cs:277-281）——玩家在装载等待屏看到的就是这一行；
 * `DisplayReport=YES` 时改打逐项装载报表、不打这行（同处 if 的另一半）。
 * 移植侧的等价可见面 = 装载覆盖层标题（`MainActivity.syncLoading`），
 * 配置装载失败时回落壳层三语文案（不阻断启动）。
 *
 * [ConfigKey.LoadLabel] 是 REPLACE 组键（ConfigData.cs:161，缺省 "Now Loading..."）——
 * 只经 `_Replace.csv` 链生效（4.981 语料 `_Replace.csv:14` 把它覆盖成报错指引文案，
 * 原版装载屏如实显示）；[ConfigKey.DisplayReport] 缺省 false（ConfigData.cs:77）。
 *
 * 玩家可见文本是**配置读数**不是壳层文案，不进文案表（照根 AGENTS §6 的语料锚点口径）；
 * 回落值由调用方传壳层文案表条目（`ShellFailureCopy.loadingGameLine`）。
 */
internal fun loadLabelTitle(gameRoot: File, fallback: String): String {
    val store = runCatching {
        val s = ConfigStore()
        val chain = EmueraConfigParser.loadChainFromDisk(gameRoot.toPath(), s)
        // REPLACE 组键只从 CSV/_Replace.csv 来（spec: Process.cs:240-246 的
        // `Config.UseReplaceFile` 守卫 + `ConfigData.cs:594-639`）——只读三层主链会永远
        // 停在缺省 "Now Loading..."，语料覆盖值读不到。
        if (s.itemOf(ConfigKey.UseReplaceFile).bool()) {
            EmueraConfigParser.loadReplaceFromDisk(gameRoot.toPath(), s, chain.csvDir)
        }
        s
    }.getOrNull() ?: return fallback
    val effective = EffectiveConfig.of(store)
    if (effective.bool(ConfigKey.DisplayReport)) return fallback
    return effective.string(ConfigKey.LoadLabel).takeIf { it.isNotBlank() } ?: fallback
}
