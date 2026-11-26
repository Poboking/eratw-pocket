package com.eratw.port.android

import java.io.File

/**
 * 装载端解析出的**代码包运行期身份**（ADR-013 S6：`.codeid` 侧车 → 装载链判据）。
 *
 * 与 [CodeIdSidecar] 的区别：那是「侧车怎么读」的原始结论，这里是「装载链怎么用」的裁决结果 ——
 * 磁盘 id 已过形状闸、回退语义与坏档拒载语义都已定死，[ModDexLoader] 只需在这个封闭面上二选一。
 *
 * 两个 id 的结构性区别见 [ModCodeIdentity] 的 KDoc：磁盘 id（`kojo-<8hex>`）只负责定位盘面，
 * 运行期身份才是信任闸、上屏文案与日志的主体。
 */
internal sealed interface DexCodeIdentity {

    /** 磁盘命名空间 id（调用方已过形状闸；侧车与产物都按它定位）。 */
    val diskPackageId: String

    /** 侧车路径（诊断/取证面；无侧车时是「本应在此的路径」，坏档拒载消息要点名它）。 */
    val sidecarPath: String

    /** 身份可信：运行期身份可以是侧车声明的作者 id，也可以是无侧车时的磁盘 id 回退。 */
    data class Resolved(
        override val diskPackageId: String,
        val runtimePackageId: String,
        override val sidecarPath: String,
    ) : DexCodeIdentity

    /** 侧车在场但读不懂：fail-closed —— 装载端必须拒载，不猜、也不静默退回磁盘身份。 */
    data class Corrupt(
        override val diskPackageId: String,
        override val sidecarPath: String,
    ) : DexCodeIdentity
}

/**
 * ADR-013 S6 装载端身份解析的唯一出口：把侧车三态折成 [DexCodeIdentity] 两态。
 *
 * 三条判据（线格式/坏档判据的唯一真源在 [ModCodeIdentity.read]，本对象不重复判）：
 * 1. [CodeIdSidecar.Absent] → [DexCodeIdentity.Resolved]，运行期身份回退磁盘 id
 *    （普通口上包 / 本功能之前导入的老代码包）；
 * 2. [CodeIdSidecar.Bound] → [DexCodeIdentity.Resolved]，运行期身份 = 侧车声明值；
 * 3. [CodeIdSidecar.Corrupt] → [DexCodeIdentity.Corrupt]，由装载端按拒载处理（fail-closed）。
 *
 * 盘面根不可得（`rt2Dir == null`）也按坏档处理：连「侧车在不在」都判不了时，宁可拒载。
 */
internal object ModDexIdentity {

    /**
     * 解析一个磁盘包的运行期身份。
     *
     * @param rt2Dir mods/rt2 根（`code` 目录的父目录）；`null` = 盘面根不可得，按坏档 fail-closed
     * @param diskPackageId 磁盘命名空间 id；调用方必须先过 [ModEnablement.isValidPackageId]
     *   （本函数照常用它拼侧车路径，不做形态校验）
     */
    fun resolve(rt2Dir: File?, diskPackageId: String): DexCodeIdentity {
        val path = sidecarPath(rt2Dir, diskPackageId)
        val sidecar = if (rt2Dir == null) {
            CodeIdSidecar.Corrupt
        } else {
            runCatching { ModCodeIdentity.read(rt2Dir, diskPackageId) }
                .getOrDefault(CodeIdSidecar.Corrupt)
        }
        return when (sidecar) {
            is CodeIdSidecar.Bound -> DexCodeIdentity.Resolved(diskPackageId, sidecar.runtimePackageId, path)
            CodeIdSidecar.Absent -> DexCodeIdentity.Resolved(diskPackageId, diskPackageId, path)
            CodeIdSidecar.Corrupt -> DexCodeIdentity.Corrupt(diskPackageId, path)
        }
    }

    /** 侧车路径的唯一拼装出口（文件名与 [ModCodeIdentity.read] 同源，不另写字面量）。 */
    private fun sidecarPath(rt2Dir: File?, diskPackageId: String): String {
        val name = ModCodeIdentity.fileName(diskPackageId)
        return if (rt2Dir == null) name else File(rt2Dir, name).absolutePath
    }
}
