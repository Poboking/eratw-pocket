package com.eratw.codee2e

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext

/**
 * 跨模块真产物 e2e 夹具的**载体入口类**：本类 FQN 与 modgen 侧 `RealCodeProductFixtureTest.ENTRYPOINT`
 * （真产物内层 `manifest.json.entrypoint`）逐字相同，`ModCodeCarrierAssemblyEndToEndTest` 的
 * 「真产物带 entrypoint ⇒ 装载」正向断言靠它落到 `EraMod` 实例。
 *
 * 为什么入口类要真实存在于 app 测试源集：真产物里的 `classes.dex` 是夹具假 dex（不解析字节），
 * 装配面的类解析走「夹具注入的 ClassLoader + 父加载器 = 测试 classpath」，所以入口 FQN 必须能在
 * 测试 classpath 解析出无参构造的 [EraMod]；真机 ART `DexClassLoader` / dex2oat 实装载不在本波
 * （`TODO(verify)`，见 `ModCodeCarrierAssemblyEndToEndTest` 头 KDoc）。
 *
 * 改夹具模板 / 入口 FQN 的波次必须同步改这里的包名与类名（`EXPECTED_ENTRYPOINT` 会断言二者同值）。
 */
class CodeE2eMod : EraMod {
    override fun onLoad(context: ModContext) = Unit

    override fun onEnable() = Unit

    override fun onDisable() = Unit

    override fun onUnload() = Unit
}
