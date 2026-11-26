package com.eratw.port.android

import android.content.res.AssetManager

/**
 * ADR-013 D2 闸 2 的 compat 表读侧：把随 APK 打包的 [ASSET_NAME] 读成 [ModDexGate.SurfaceCompat]。
 *
 * 读侧纪律（与 [ModDexGate] 的「表只是信息面、不是判据」对齐）：
 * - **单次读**：装配期在 main thread 读一次，结果经构造参数注入 [ModCodeCarrierProvider]，逐包装载
 *   不再碰资产面（同 `GameHost.modRuntime` 的单路传值纪律）；
 * - **缺失 / 读失败 ⇒ 空表且不抛**：资产被裁掉、AssetManager 抛 IO 异常都退化为
 *   [ModDexGate.SurfaceCompat.EMPTY]，行为与「无任何 compat 信息」逐位一致（[ModDexGate.verdict]
 *   根本不看表）；
 * - **坏行不算失败**：逐行解析的坏行由 [ModDexGate.loadCompatTable] 计数丢弃（只做诊断），读侧不
 *   二次过滤、不因坏行降级整表。
 *
 * 资产内容的证据与维护口径写在表头注释里（表内只有 `#` 注释行与 `surface<TAB>minEngineApi` 两列行）。
 */
internal object ModCompatTable {

    /** 资产路径（相对 assets 根）：APK 内 `assets/mod-api-abi-compat.tsv`。 */
    const val ASSET_NAME: String = "mod-api-abi-compat.tsv"

    /**
     * 从 [source] 取表文本并解析成 [ModDexGate.SurfaceCompat]。
     *
     * [source] 返回 null（资产缺失）或抛异常（读失败）⇒ [ModDexGate.SurfaceCompat.EMPTY]，**不抛**：
     * compat 表是信息面，读不到不能连带把 dex 装载链带死（ADR-013 D2）。
     *
     * @param source 取表文本的惰性来源（生产 = [loadFromAssets] 的资产读取；测试 = 内存文本）
     */
    fun load(source: () -> String?): ModDexGate.SurfaceCompat {
        val text = runCatching(source).getOrNull() ?: return ModDexGate.SurfaceCompat.EMPTY
        return ModDexGate.loadCompatTable(text)
    }

    /**
     * 读 `assets/` 下的 [ASSET_NAME] 并解析；资产缺失（FileNotFoundException）与读取中途失败一律
     * 返回空表，不抛、不重试。
     *
     * 调用点纪律：只在 main thread 装配期读一次（与总闸 / 工厂同一条「取好结论再进装配期」纪律）；
     * 本对象不认识 Context 与偏好表，[assets] 由调用方给。
     */
    fun loadFromAssets(assets: AssetManager): ModDexGate.SurfaceCompat = load {
        assets.open(ASSET_NAME).use { input -> input.readBytes().toString(Charsets.UTF_8) }
    }
}

/**
 * 装配期 ABI 读数快照：引擎 API 版本 + compat 表（ADR-013 D2 闸 2 的信息面）。
 *
 * 两者是同一类「main thread 取好、单路传进装配期」的读数（同总闸 / [DexLoaderFactory] 的纪律），
 * 收成一个构造参数是为了不撞 [ModCodeCarrierProvider] 构造参数表的 detekt `LongParameterList`
 * 阈值（参数表已满，不再加第 7 个并列形参）；**不是**把判定与信息面合并语义：
 * [ModDexGate.verdict] 仍只看 `compiledSurface <= engineApiVersion`，[compat] 只影响拒绝文案。
 */
internal class ModAbiReadout(
    /** 当前引擎的 mod-api surface 版本（判定真值：[ModDexGate.verdict] 的判据输入之一）。 */
    val engineApiVersion: Int = MOD_ENGINE_API_VERSION,
    /** compat 表读数；缺省空表 = 与接线前逐位一致（只补拒绝文案，不参与判定）。 */
    val compat: ModDexGate.SurfaceCompat = ModDexGate.SurfaceCompat.EMPTY,
)
