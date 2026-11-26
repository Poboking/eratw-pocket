package com.eratw.port.android

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 显式卸载的存档段回收台账（task-134）：`mods/rt2/` 下的「已释放包」清单 + 会话内回收队列。
 *
 * ## 缺的是什么（EE 审计 ee481865a 量出来的设计缺口）
 *
 * 卸载只删盘上产物（[ModManageUninstall]），会话容器 `ModStorageStore` 里该包的分段没人摘；
 * 存档抓取（`ModSidecarStoragePort.captureChunks`）把激活段与孤儿段**全表**透传进 `.rt2`，
 * `SaveService.mergeChunks` 又只并集不裁剪 ⇒ 卸载后的段永久留在**后续每一次存档**里。
 *
 * ## 两类回收，各自落点
 *
 * 1. **持久台账**（本文件 [record]）：显式卸载成功时把 packageId 写进
 *    `mods/rt2/released-packages.txt`。清单是**盘面事实**，跨会话/跨进程有效；
 *    抓取侧（`ModSidecarStoragePort.captureChunks`）读它跳过这些包的段
 *    ⇒ **减在下次存档**（既有存档文件本笔不改写，不重写历史存档）。
 * 2. **会话内队列**（[ModStorageReclaimRequests]）：设置页卸载后投递 packageId，
 *    下一局/同局的 VM 页边界 drain 后调 `ModStorageStore.release`，把内存容器里的段摘掉。
 *    纯过期数据，不读它也不影响正确性（抓取侧已有台账闸），属于及时释放。
 *
 * ## 为什么清单落在 `mods/rt2/` 且用这个文件名
 *
 * - 路径走既有解析口径（[ModEnablement.rt2Dir] → `ModRuntimeAssembly.manifestDirFor`），
 *   不新开目录、不改存档布局；
 * - 文件名不以 `.manifest` 结尾 ⇒ `ModManageScanner`（只扫 `*.manifest`）不会把清单列成
 *   一个 Mod；也不以 `.erbm` / `.sources` / `.sources.sha256` / `.settings` 结尾且不以
 *   `kojo-` 开头 ⇒ `ModPackageInventory` 的成员枚举与孤儿 GC 都不认它（清单不会被当包产物删）；
 * - 内容每行一个 packageId，读侧逐行过 [ModEnablement.isValidPackageId]：清单内容会拿去
 *   拼盘上文件名（判在场产物），必须挡住 `../` 这类能逃出 `mods/rt2/` 的行。
 *
 * ## 语义边界（只做显式卸载）
 *
 * - 停用（`.off`）：**不写清单**——停用保留数据是既有语义（`ModEnablement` 类 KDoc），
 *   已停用包的分段照样随档走；
 * - 导入 / 换版本 / 重装：**不写清单**，且 [reclaimedPackageIds] 对「盘上仍有产物的包」
 *   一律不跳过——重导后同名包要按新包采集数据，不许被旧清单静默吞掉（旧条目就此惰性失效）；
 * - 其它路径（孤儿 GC、写失败回滚）不经过这里。
 *
 * 写失败（只读存储等）只返回 `false` 不抛：卸载的结论是「盘上成员删净」，清单是回收附加面，
 * 失败 = 本次回收不生效（下次卸载重试即可），不能把一次成功的卸载报成失败。
 */
object ModStorageReclaim {

    /** 已释放包清单文件名（落 `mods/rt2/`；命名理由见类 KDoc）。 */
    const val RELEASED_LIST_NAME: String = "released-packages.txt"

    /** 清单文件（不保证存在）。 */
    fun listFile(rt2Dir: File): File = File(rt2Dir, RELEASED_LIST_NAME)

    /**
     * 盘上已释放包集（读失败 / 文件缺失 / 坏行 = 空或跳过该行）。
     *
     * fail-open 是刻意的：清单读不出来就当没有回收台账，退回「全表透传」的旧行为——
     * 绝不因为一个坏文件把玩家的正常数据段误跳掉。
     */
    fun releasedPackageIds(rt2Dir: File): Set<String> {
        val file = listFile(rt2Dir)
        val text = runCatching { if (file.isFile) file.readText(StandardCharsets.UTF_8) else "" }
            .getOrElse { return emptySet() }
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { ModEnablement.isValidPackageId(it) }
            .toSet()
    }

    /**
     * 把 [packageId] 记进已释放清单（幂等）。返回 true = 盘上清单已包含该包。
     *
     * 覆盖写字面走「同目录临时文件 + 原子改名」（先写后 swap）：中途掉电/进程被杀时
     * 旧清单保持完整，不会出现半份清单把某个包误判成未释放。
     *
     * @throws IllegalArgumentException packageId 非法形状（与 [ModEnablement.markerFile] 同口径；
     *   在拼任何路径前就抛，与卸载链一致）。
     */
    fun record(rt2Dir: File, packageId: String): Boolean {
        // 只借用同一套形状闸（非法即抛）；标记文件本身与回收无关。
        ModEnablement.markerFile(rt2Dir, packageId)
        val existing = releasedPackageIds(rt2Dir)
        if (packageId in existing) return true
        val lines = (existing + packageId).sorted()
        return writeAtomically(rt2Dir, lines.joinToString(separator = "\n", postfix = "\n"))
    }

    /**
     * 抓取侧**跳过集**：已释放且盘上已无任何包产物的包。
     *
     * 「盘上无产物」这一道是语义⑤的边界闸：重导 / 换版本后同名包的产物又在了，
     * 此时旧清单条目惰性失效，该包新数据照常落档（不允许回收台账吞掉重新装回来的数据）。
     */
    fun reclaimedPackageIds(rt2Dir: File): Set<String> {
        val released = releasedPackageIds(rt2Dir)
        if (released.isEmpty()) return emptySet()
        return released.filterNotTo(mutableSetOf()) { hasInstalledArtifacts(rt2Dir, it) }
    }

    /** 该包在 `mods/rt2/` 下是否还有盘上产物（导入产物任一文件、资源目录或模板目录）。 */
    private fun hasInstalledArtifacts(rt2Dir: File, packageId: String): Boolean =
        // 模板目录（ERA-A2）也算在档产物：目录在而台账判「无产物」会让该包模板段被错误回收。
        File(rt2Dir, packageId + ModPackageInventory.ASSET_DIR_SUFFIX).exists() ||
            File(rt2Dir, packageId + ModPackageInventory.CHARA_DIR_SUFFIX).exists() ||
            ModPackageInventory.memberNames(packageId).any { File(rt2Dir, it).isFile }

    /** 原子覆盖写清单；任何 IO 失败返回 false（调用方按「本次回收未生效」处理）。 */
    private fun writeAtomically(rt2Dir: File, text: String): Boolean {
        val tmp: Path? = runCatching {
            if (!rt2Dir.isDirectory) rt2Dir.mkdirs()
            Files.createTempFile(rt2Dir.toPath(), "released-", ".tmp")
        }.getOrNull()
        if (tmp == null) return false
        return runCatching {
            Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
            moveReplacing(tmp, listFile(rt2Dir).toPath())
            true
        }.getOrElse {
            runCatching { Files.deleteIfExists(tmp) }
            false
        }
    }

    /** 优先原子改名；文件系统不支持（部分虚机盘）时退回覆盖改名。 */
    private fun moveReplacing(tmp: Path, target: Path) {
        val moved = runCatching {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse { if (it is AtomicMoveNotSupportedException) null else throw it }
        if (moved == null) Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * 设置页「显式卸载」→ 运行会话的**跨 Activity 投递队列**（task-134 ③）。
 *
 * 形态与 [ModReinstallRequests] / `ModSettingsNotifyChannel` 逐条同源：只存 packageId 字符串、
 * 不持会话引用；写入端（设置页 UI 线程）投递后立即返回，消费端在 VM 线程页边界
 * （`ModRuntimeSession.onTurnBoundary` 首段）drain，调用 `ModStorageStore.release(packageId)`
 * 把该包的分段从内存容器摘掉（**只摘点名的包**，不 deactivate 别的包）。
 *
 * 生命周期：会话收口 `GameHost` 退出清场处调 [clear]，未兑现请求不许漏进下一局首边界。
 * 队列本身是可选优化——不消费也不影响正确性，抓取侧靠 [ModStorageReclaim] 台账跳过。
 */
object ModStorageReclaimRequests {

    private val lock = Any()

    /** 待回收 packageId（插入序去重；guard = [lock]）。 */
    private var pending: LinkedHashSet<String> = LinkedHashSet()

    /** 显式卸载后投递；非法 packageId 静默忽略（脏键不许进队列）。 */
    fun request(packageId: String) {
        if (!ModEnablement.isValidPackageId(packageId)) return
        synchronized(lock) { pending.add(packageId) }
    }

    /** 取走全部积压（取走即清），保持首投递序、去重。无积压返回空表。 */
    fun drain(): List<String> = synchronized(lock) {
        val taken = pending
        pending = LinkedHashSet()
        taken.toList()
    }

    /** 丢弃全部积压（会话收口用）。 */
    fun clear() = synchronized(lock) { pending.clear() }

    /** 测试观察面：当前积压条数。 */
    internal fun pendingCount(): Int = synchronized(lock) { pending.size }
}
