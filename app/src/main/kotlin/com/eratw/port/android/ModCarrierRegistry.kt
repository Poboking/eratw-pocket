package com.eratw.port.android

import com.eratw.port.modapi.EraMod
import com.eratw.port.modfamily.FamilyDeclarations
import com.eratw.port.modfamily.FamilyMod
import com.eratw.port.modlivingworld.CharacterDeclarations
import com.eratw.port.modlivingworld.CharacterMod
import com.eratw.port.modlivingworld.EventDeclarations
import com.eratw.port.modlivingworld.EventMod
import com.eratw.port.modlivingworld.MemoryDeclarations as LwMemoryDeclarations
import com.eratw.port.modlivingworld.MemoryMod as LwMemoryMod
import com.eratw.port.modlivingworld.RelationshipDeclarations
import com.eratw.port.modlivingworld.RelationshipMod
import com.eratw.port.modmemory.MemoryDeclarations
import com.eratw.port.modmemory.MemoryMod

/**
 * RT2 Kotlin mod **载体注册表**（K4-min 的 build-time mod registry，RT2-B② 接线）。
 *
 * ## 它是什么
 *
 * 把「编译进 APK 的 Kotlin mod 载体」静态点名的一张表：唯一生产来源，[attach] 开态经它取
 * `modProviders` 名单（`GameHost` 调用点 `if (modRuntime)` **惰性取**——总闸关时不碰本表，
 * 「关态一个对象都不建」的 B1 判据延伸到载体实例）。
 *
 * ## 登记规则
 *
 * - 每个 Kotlin 载体在此**显式登记一行**：`packageId to 载体实例工厂`；不搞 classpath 扫描
 *   或 ServiceLoader——装载面要的是「点名可审计」，出包体检与日志都按这张表对账。
 * - [providers] 每次**逐会话取新实例**（[EraMod] 不跨会话共享状态；声明是无状态的，
 *   实例仍不复用，装进去的 Registration 全挂当次会话）。
 * - packageId 必须与该载体的磁盘 manifest（mods 目录 rt2 子树下的点 manifest 文件）声明一致：不在快照内 =
 *   `ModRuntimeHost.install` 逐 mod fail-closed（`active=false` 上日志面），不整链炸、
 *   不静默跳过——所以「登记了载体但没装 manifest」的显性症状是日志里一条
 *   `mod.install pkg=… active=false`，不是崩溃。
 *
 * ## 现有载体
 *
 * - `eratw.family`（[FamilyMod]，:mod-family 的 F1 纵向切片载体；磁盘 manifest 随
 *   RT2-B③ 资产投递落地，今天开闸装它 = 逐 mod fail-closed 上日志，属预期形态）。
 * - `eratw.memory`（[MemoryMod]，:mod-memory 的 M9-1 记忆 Mod 参考实现；磁盘 manifest
 *   同样随内置资产投递，`BuiltinManifestBuild` 双真源产出）。
 * - `eratw.lw.character` / `eratw.lw.relationship` / `eratw.lw.memory` / `eratw.lw.event`
 *   （M10-1 Living World Demo 四载体，:mod-livingworld；磁盘 manifest 同上，四包独立
 *   packageId 同住一个 Gradle 模块）。
 */
object ModCarrierRegistry {

    /** 编译进 APK 的 Kotlin mod 载体名单（新增载体在此登记一行；顺序 = install 序）。 */
    fun providers(): List<Pair<String, EraMod>> = listOf(
        FamilyDeclarations.PACKAGE_ID to FamilyMod(),
        MemoryDeclarations.PACKAGE_ID to MemoryMod(),
        // M10-1：Living World Demo 四载体（§21.9 四 Mod 组合；四个独立 packageId，
        // 全部定义在 :mod-livingworld 一个 Gradle 模块内）。
        CharacterDeclarations.PACKAGE_ID to CharacterMod(),
        RelationshipDeclarations.PACKAGE_ID to RelationshipMod(),
        LwMemoryDeclarations.PACKAGE_ID to LwMemoryMod(),
        EventDeclarations.PACKAGE_ID to EventMod(),
    )
}
