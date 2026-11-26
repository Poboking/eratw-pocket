package com.eratw.port.android

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.registry.ModRegistry
import java.io.File

/**
 * RT2 Kotlin mod **载体注册表**（K4-min 的 build-time mod registry，RT2-B② 接线）。
 *
 * ## 它是什么
 *
 * 把「编译进 APK 的 Kotlin mod 载体」交给装配侧的一张表：唯一生产来源，[attach] 开态经它取
 * `modProviders` 名单（`GameHost` 调用点 `if (modRuntime)` **惰性取**——总闸关时不碰本表，
 * 「关态一个对象都不建」的 B1 判据延伸到载体实例）。
 *
 * ## 名单从哪来（2026-11-16 起）
 *
 * 来自 `:mod-registry` 生成的 `ModRegistry.carriers`：构建期扫各 `mod-*` 模块 main 源集里
 * 打了 `@ModCarrier` 的声明 object。**本文件不再点名任何载体**，也不搞 classpath 扫描或
 * ServiceLoader —— 注解在**构建期**被展开成一张静态表，运行期仍是普通对象引用，零反射。
 *
 * 为什么换掉「显式登记一行」：这条登记义务原先要在**两处**各履行一次（这里是设备侧装配，
 * `tools/modgen` 的 `BuiltinManifestBuild.carriers` 是资产侧），漏一处不报错、不失败，只是
 * 那个 mod **静默不生效**。现在两边都读 `ModRegistry.carriers`，登记面只剩声明 object 自身。
 *
 * ## 登记规则
 *
 * - 载体在自己的声明 object 上打 `@ModCarrier` 并实现 `ModCarrierDeclaration`（给出
 *   `packageId` / `manifest` / `newMod()`），即自动在册；漏实现是**编译错误**，不是静默失效。
 * - [providers] 每次**逐会话取新实例**（[EraMod] 不跨会话共享状态；声明是无状态的，
 *   实例仍不复用，装进去的 Registration 全挂当次会话）。
 * - packageId 必须与该载体的磁盘 manifest（mods 目录 rt2 子树下的点 manifest 文件）声明一致：不在快照内 =
 *   `ModRuntimeHost.install` 逐 mod fail-closed（`active=false` 上日志面），不整链炸、
 *   不静默跳过——所以「登记了载体但没装 manifest」的显性症状是日志里一条
 *   `mod.install pkg=… active=false`，不是崩溃。
 * - 顺序由 `ModRegistry` 按 packageId 升序给出（与构建期目录扫描顺序无关，逐位确定）。
 */
object ModCarrierRegistry {

    /**
     * 编译进 APK 的 Kotlin mod 载体名单（来源 = `ModRegistry`；顺序 = 按 packageId 升序的 install 序）。
     *
     * [gameRoot] 只为读 `mods/rt2/` 的停用标记（[ModEnablement]）：被玩家关掉的载体连实例
     * 都不建，与数据包侧「manifest + `.erbm` 一起摘掉」同一语义（批2-3）。
     */
    fun providers(gameRoot: File): List<Pair<String, EraMod>> {
        val disabled = ModEnablement.disabledPackageIds(ModEnablement.rt2Dir(gameRoot))
        if (disabled.isEmpty()) {
            return ModRegistry.carriers.map { carrier -> carrier.packageId to carrier.newMod() }
        }
        return ModRegistry.carriers.mapNotNull { carrier ->
            if (carrier.packageId in disabled) null else carrier.packageId to carrier.newMod()
        }
    }

    /**
     * 编译期载体 + 代码包载体的**合并名单**（ADR-013 S6b 的接线缝）。
     *
     * 先按 [providers] 取内置名单（顺序、停用过滤、逐会话新实例口径全不变），再把内置
     * packageId 集合（只读快照）交给 [codeCarriersOf]，后者返回的代码包载体按 packageId
     * 去重后追加。**内置优先**：同一 packageId 出现在两边时只留内置实例——同一身份双注册会让
     * `ModRuntimeHost.install` 逐 mod fail-closed，两边都不可用。
     *
     * 为什么用「回调 + 内置 id 集合」而不是在这里 `new` 一个代码包 Provider：本对象无 Context，
     * 装配期跑在 VM 线程上，dex 总闸与类加载器工厂必须由调用方在 main thread 取好后**单路传入**
     * （与 `GameHost.modRuntime` 同一条线程纪律）；本对象保持纯函数接缝，接线不为它引入单例或
     * 静态可变全局。回调为空表（总闸关 / 无代码包）时结果与 [providers] 逐元素相同。
     */
    fun providers(
        gameRoot: File,
        codeCarriersOf: (Set<String>) -> List<Pair<String, EraMod>>,
    ): List<Pair<String, EraMod>> {
        val builtin = providers(gameRoot)
        val taken = builtin.mapTo(mutableSetOf()) { it.first }
        val merged = builtin.toMutableList()
        for (carrier in codeCarriersOf(taken.toSet())) {
            if (taken.add(carrier.first)) {
                merged += carrier
            }
        }
        return merged
    }

    /**
     * 单个载体的**新实例**（Lane DZ 回滚态重装的实例来源）。
     *
     * 与 [providers] 同一构造口径（每取一次 `newMod()` 一个新实例、状态不跨调用复用），
     * 只是按 packageId 定取；停用标记（[ModEnablement]）同样先查——玩家关掉的载体不建实例。
     * 不在册 / 已停用 / packageId 形状不合法 ⇒ null（调用方据此不投递重装请求）。
     *
     * 为什么可重装只认载体：回滚态条目的替换要求「同一 packageId 有可再装载的 mod 实例」，
     * 内置载体的声明 object 无状态、可反复 `newMod()`；导入包/数据包的实例来自导入链，
     * 会话内没有等价的廉价再取口（那是「重开一局 / 重新导入」的语义）。
     */
    fun providerFor(gameRoot: File, packageId: String): EraMod? {
        val carrier = ModRegistry.carriers.firstOrNull { it.packageId == packageId }
        val disabled = packageId in ModEnablement.disabledPackageIds(ModEnablement.rt2Dir(gameRoot))
        return if (carrier == null || disabled) null else carrier.newMod()
    }

    /**
     * 版本读数用的声明表（VS-1）：packageId → 作者声明的 `EraMod.apiVersion`。
     *
     * 只读 `ModCarrierDeclaration.apiVersion`——它就是「不造实例就能读的诊断入口」；
     * **绝不**在这里 `newMod()`：设置页在总闸关着时也会读它，而「关态一个对象都不建」的 B1
     * 判据延伸到载体实例（见类 KDoc）。`dataVersion` 没有声明级入口，读数面因此只从装载门的
     * 失败原因里取（见 [ModVersionReadout]）。
     */
    fun versionDeclarations(): List<Pair<String, Int>> =
        ModRegistry.carriers.map { carrier -> carrier.packageId to carrier.apiVersion }
}

/**
 * 第三方代码包载体来源（ADR-013 S6b）。
 *
 * 为什么是 fun interface 而不是直接把 `ModCodeCarrierProvider` 塞进 `GameHost` 构造参数：
 * `GameHost` 是 public 类，构造参数类型必须 public；而 provider 及其结果类型是 app 模块
 * **internal**（不想把它们抬成对外面）。这层接口只暴露「内置 id 集 → 追加名单」一件事，
 * 与 [ModCarrierRegistry.providers] 两参重载的回调签名同形。
 *
 * 实现方（壳层 `MainActivity`）必须在 **UI 线程**构造好 provider（总闸与 `DexClassLoader`
 * 工厂定死），进入装配期后本接口只被调用一次、不碰偏好表。
 */
fun interface ModCodeCarrierSource {
    /**
     * @param builtinPackageIds 内置载体已占用的 packageId 集（已扣掉停用包）
     * @return 需要追加的载体名单（未重名、未重复运行期身份；顺序由实现方定）
     */
    fun carriersFor(builtinPackageIds: Set<String>): List<Pair<String, EraMod>>
}
