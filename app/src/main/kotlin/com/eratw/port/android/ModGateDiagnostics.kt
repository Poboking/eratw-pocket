package com.eratw.port.android

import android.content.Context
import com.eratw.port.core.fn.entityRuntimeOf
import com.eratw.port.core.save.SaveFolderMigration
import com.eratw.port.core.save.SidecarSaveInventory
import com.eratw.port.platform.storage.GameStorage
import java.io.File

/**
 * Mod 运行时总闸的**单一读数源**（RT2-GATE-OPEN，2026-11-17）：设置页诊断卡与 debug 包
 * `DevToolsHost.modgate` 共用同一份快照，避免两处各拼一套口径。
 *
 * ## 为什么独立成文件（而不挂在 `DevToolsHost.kt` 里）
 *
 * 本类的**主消费者是 release 玩家面**（设置页的只读诊断卡），而 `DevToolsHost` 整份文件的身份
 * 是「真机实时调试通道 仅 debug 包」（`startOnce` 第一行 `FLAG_DEBUGGABLE` 闸）。把 release
 * 面的读数源寄居在 debug 通道文件里，等于让「将来删/关调试通道」顺带打死玩家面诊断卡；
 * 独立成文件后依赖方向单向：debug 通道 → 读数源，release 设置页 → 读数源。
 *
 * ## 三个读数口径（都不许在本类里另立一套知识）
 *
 * 1. **总闸** —— [UiPrefs.modRuntime]（下一次会话装配生效）；
 * 2. **本局是否装配** —— 会话 `store.aux` 上的**装配接缝标记** `ENTITY_RUNTIME_SLOT`：
 *    `ModRuntimeAssembly.attach` **开态尾段**无条件写入（`backfillEntityRuntimeSeam`），
 *    关态在函数首行 `if (!enabled) return null` 之前就返回 ⇒ 槽缺席。读写都用 core 的
 *    公开缝 [entityRuntimeOf]（`EntityMethods.kt:69`），**不反射、不读私有字段**：
 *    这样既不受 R8/混淆影响，也不会因为 `GameHost` 内部字段改名而静默漂移读数；
 * 3. **Mod 计数 / 存档侧车** —— 前者复用设置页同一套 [ModManageScanner]（设置页把列表那次
 *    扫描直接传进来，列表与摘要必然同源，不会出现「列表 N / 摘要 M」分叉）；
 *    后者只认 core 的 **逻辑存档单元形状** [SidecarSaveInventory]（本体 + `.rt2` 容器后缀
 *    同一份知识），存档目录名取 [SaveFolderMigration.SAV_FOLDER]（C# 锚 `SaveConfig.cs:38`
 *    的 `sav` 目录 + `Config.cs:562-563` 的 `global.sav` / `save*.sav` 口径）。
 *
 * 只读：不写偏好、不改装配状态、不删任何文件；任何一项取不到都按空/未装配降级，
 * 绝不把异常抛穿到设置页或调试通道。
 */
internal object ModGateDiagnostics {

    /** 一次诊断快照（字段顺序 = 设置页与 `modgate` 的展示顺序）。 */
    data class Snapshot(
        val gate: Boolean,
        val sessionAttached: Boolean,
        val installed: Int,
        val enabled: Int,
        val unreadable: Int,
        val sidecars: Int,
        val sidecarBytes: Long,
    )

    /** `modgate` 的一行取证读数（日志/取证面 ASCII `key=value`，不参与玩家可见文案表）。 */
    fun report(context: Context): String {
        val d = snapshot(context)
        return "modgate gate=${d.gate} sessionAttached=${d.sessionAttached} " +
            "mods=${d.installed} enabled=${d.enabled} unreadable=${d.unreadable} " +
            "sidecars=${d.sidecars} sidecarBytes=${d.sidecarBytes}"
    }

    /** 现场快照；任何一项取不到都按空/未装配降级，绝不抛穿到设置页或调试通道。 */
    fun snapshot(context: Context): Snapshot {
        val root = runCatching { GameStorage(context).gameRoot() }.getOrNull()
        val scan = root?.let { runCatching { ModManageScanner.scan(it) }.getOrNull() }
            ?: ModManageScan.Empty
        return snapshotOf(context, root, scan)
    }

    /**
     * 复用调用方**已扫好的**安装清单（设置页列表与卡尾摘要是同一次扫描）。
     *
     * 设置页本来就为列表扫过 `mods/rt2/`；若摘要再扫一遍，盘上文件在两次扫描之间变化
     * （玩家刚切换启停）就会出现「列表 N 个 / 摘要 M 个」的分叉读数。同一份 [scan] 传进来，
     * 列表与摘要必然同源。
     */
    fun snapshot(context: Context, scan: ModManageScan): Snapshot {
        val root = runCatching { GameStorage(context).gameRoot() }.getOrNull()
        return snapshotOf(context, root, scan)
    }

    private fun snapshotOf(context: Context, root: File?, scan: ModManageScan): Snapshot {
        val sidecar = scanSidecars(root)
        return Snapshot(
            gate = runCatching { UiPrefs.modRuntime(context) }
                .getOrDefault(UiPrefs.DEFAULT_MOD_RUNTIME),
            sessionAttached = sessionAttached(),
            installed = scan.mods.size,
            enabled = scan.mods.count { it.enabled },
            unreadable = scan.skipped.size,
            sidecars = sidecar.first,
            sidecarBytes = sidecar.second,
        )
    }

    /**
     * 本局引擎实例里是否存在 Mod 运行时：读 `store.aux` 的装配接缝槽
     * （[entityRuntimeOf]），命中 = `attach` 开态尾段跑过。
     *
     * 槽里没有活宿主（还没开局 / 已收口）= 未装配；总闸关 = `attach` 在写槽之前返回 =
     * 未装配 —— 两态都如实回答，不猜、不写缓存。
     */
    private fun sessionAttached(): Boolean {
        val session = SessionVault.peek()?.debugSession() ?: return false
        return entityRuntimeOf(session.store.aux) != null
    }

    /** 侧车容器计数与总字节；M4-3 之后存档落 `gameRoot/sav`，迁移期老档仍可能在 `gameRoot`，两处都扫。 */
    private fun scanSidecars(root: File?): Pair<Int, Long> {
        if (root == null) return 0 to 0L
        var count = 0
        var bytes = 0L
        for (dir in listOf(root, File(root, SaveFolderMigration.SAV_FOLDER))) {
            val files = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (file in files) {
                if (!file.isFile || !isSidecarContainer(file.name)) continue
                count++
                bytes += file.length()
            }
        }
        return count to bytes
    }

    /**
     * 只认 `.rt2` 容器成员：伴生件本体必须是逻辑存档本体，且名字正是容器路径。
     *
     * 与 `ModRuntimeAssembly.loadRegistryUnion` 的并集扫描同形（同一份 [SidecarSaveInventory]
     * 知识、同一判据顺序）；两处并成 core 侧一个 `isContainerMember` 谓词会更好，但那要动
     * `core-vm`（本波禁改），故此处只保持判据逐字同源。
     */
    private fun isSidecarContainer(name: String): Boolean {
        val body = SidecarSaveInventory.companionBodyOf(name) ?: return false
        return SidecarSaveInventory.isLogicalSaveBody(body) &&
            name == SidecarSaveInventory.containerPath(body)
    }
}
