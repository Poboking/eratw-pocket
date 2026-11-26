package com.eratw.port.android

import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.SettingDeclaration
import java.io.File

/** 已装 Mod 的来源（只影响设置页那行小字，不参与装配裁决）。 */
enum class ModSource {
    /** 随包内置的 Kotlin 载体（只有 manifest，没有 `.erbm`）。 */
    BUILTIN,

    /** 编译好的数据包（`.erbm` + 同名 manifest 成对在场）。 */
    DATA_PACKAGE,

    /** 玩家从口上导入的包（`kojo-` 前缀，由 `ModImportController` 产出）。 */
    IMPORTED,
}

/**
 * 设置页「已安装的 Mod」列表项（批2-3）。
 *
 * 全部字段来自盘上那件 manifest 的 `meta` 段（`RT2MANIFEST/3` 只有包名与版本，
 * `/4` 才带显示名 / 作者 / 说明）；`enabled` 来自 [ModEnablement] 的标记文件。
 * [title] 是「显示名，没有就退回包名」—— 列表永远有东西可显示。
 */
data class InstalledMod(
    val packageId: String,
    val displayName: String,
    val version: String,
    val author: String,
    val description: String,
    val source: ModSource,
    val enabled: Boolean,
    /**
     * 同名 `.erbm` 模块容器是否在场（数据包配对状态）。
     *
     * 内置载体本来就只有 manifest、没有 `.erbm`，所以「缺模块」只对 [ModSource.IMPORTED]
     * 有意义：导入链写盘的三件套缺了 `.erbm` 就是半成品，装配期不会被当作模块装载。
     * 详情展开面把它做成与「装载失败」不同的读数（配对问题 vs 代码/声明问题）。
     */
    val hasModule: Boolean = false,
    /**
     * 作者在 manifest `settings` 段声明的设置项（`RT2MANIFEST/5` 才有；`/3`、`/4` 恒空）。
     *
     * 顺序 = 作者声明顺序 = 设置页渲染顺序。设置页据此自动出控件，不再为每个 mod 手写 UI；
     * 值本身不在扫描结果里（[ModSettingsStore] 按包读设备侧文件）。
     */
    val settings: List<SettingDeclaration> = emptyList(),
    /**
     * 缺依赖体检读数（ModDX2-C）：`requires` 声明的依赖键里既不在其余已扫 manifest 包名
     * 集合、也不在内置载体名单（ModRegistry 单源）的那些（键 = 约束条目的 `@` 前段包名，
     * 声明序、去重）。空表 = 依赖齐。
     *
     * 盘面**静态事实**：不随启停变（停用的包照标），不做版本区间裁决（越界是链接期
     * `ModuleLinkOrder.checkRequiresVersion` 的活，这里只管「包在不在」）；玩家装完包
     * 不开游戏就该看到还差哪个前置（对标 LOOT/SMAPI 交互警告）。
     */
    val missingRequires: List<String> = emptyList(),

    /**
     * 导入时间读数（KI-6）：manifest 文件的 mtime（导入链落盘时刻，卸载重导会重写）。
     * 读不到 = 0（老包 / 文件系统不提供），UI 行整行不出。口上管理卡展示用。
     */
    val importedAtMillis: Long = 0L,

    /**
     * 本包声明覆盖的本体函数键（KI-6：覆盖关系展示；manifest `override-functions` 段原文，
     * 已按声明序排序去重）。空表 = 无声明覆盖。展示用；真装载裁决在链接器，这里只是盘面事实。
     */
    val overrideFunctions: List<String> = emptyList(),
) {
    /** 列表主标题：显示名优先，空则回退包名。 */
    val title: String get() = displayName.ifBlank { packageId }

    /** 导入包缺模块 = 半成品（只有清单没有代码，重启也不会生效）。 */
    val missingModule: Boolean get() = source == ModSource.IMPORTED && !hasModule
}

/**
 * 一次扫描的结果：可展示的条目 + 读不出来的 manifest 明细（`文件名：解码器原因`）。
 *
 * [skipped] 不是错误页素材而是「列表下方那行小字」：坏 manifest 在装配期本来就会
 * fail-closed 整包拒绝（`ManifestCodec` 严格逐段），设置页没必要抢先把玩家挡在门外，
 * 但也不能假装它不存在 —— 玩家会问「我明明装了一个怎么没显示」。
 */
data class ModManageScan(
    val mods: List<InstalledMod>,
    val skipped: List<String>,
) {
    companion object {
        /** 未扫描 / 无 Mod 的缺省值。 */
        val Empty: ModManageScan = ModManageScan(emptyList(), emptyList())
    }
}

/**
 * 扫 `mods/rt2/` 出设置页列表（只读、幂等、不打日志）。
 *
 * 排序 = 停用的排后面，再按显示名（无则包名）不区分大小写；「停用的沉底」让玩家一眼
 * 看到自己刚刚关掉的那一个去哪儿了。
 *
 * 扫描顺带做缺依赖体检（[InstalledMod.missingRequires]，ModDX2-C）：玩家装完包不开游戏
 * 就能看到还差哪个前置，不用等到链接期整链炸。
 */
object ModManageScanner {
    fun scan(gameRoot: File): ModManageScan {
        val rt2Dir = ModEnablement.rt2Dir(gameRoot)
        val manifests = rt2Dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(ModRuntimeAssembly.MANIFEST_EXTENSION) }
            ?.sortedBy { it.name }
            ?: return ModManageScan.Empty
        // 第一遍：解码全部 manifest（缺依赖体检要跨包对照「其余已扫 manifest」的包名集合，
        // 必须先看完整个盘面再逐包算，所以拆成解码/装配两遍）。
        val decoded = ArrayList<ModuleDescriptor>(manifests.size)
        val skipped = ArrayList<String>(0)
        for (file in manifests) {
            val descriptor = try {
                ManifestCodec.decode(file.readBytes())
            } catch (e: IllegalArgumentException) {
                // ManifestFormatException 是 IllegalArgumentException 的子类；读不出来的
                // 那件连同解码器给的原因一起记下（设置页只展示，不进装配链结论）。
                skipped += if (e.message.isNullOrBlank()) file.name else "${file.name}：${e.message}"
                continue
            }
            decoded += descriptor
        }
        val scannedIds = decoded.mapTo(HashSet(decoded.size)) { it.packageId }
        // 内置载体名单走 ModRegistry 单源（经 ModCarrierRegistry 读；versionDeclarations
        // 不建载体实例 ——「关态一个对象都不建」的 B1 判据延伸到设置页扫描面）。
        val carrierIds = ModCarrierRegistry.versionDeclarations().mapTo(HashSet()) { it.first }
        val mods = ArrayList<InstalledMod>(decoded.size)
        for (descriptor in decoded) {
            val packageId = descriptor.packageId
            val hasModule = File(rt2Dir, packageId + ModPackageInventory.MODULE_EXTENSION).isFile
            val manifestFile = File(rt2Dir, packageId + ModRuntimeAssembly.MANIFEST_EXTENSION)
            mods += InstalledMod(
                packageId = packageId,
                displayName = descriptor.displayName,
                version = descriptor.version,
                author = descriptor.author,
                description = descriptor.description,
                source = sourceOf(packageId, hasModule),
                enabled = !ModEnablement.isDisabled(rt2Dir, packageId),
                hasModule = hasModule,
                settings = descriptor.settings,
                missingRequires = missingRequiresOf(descriptor, scannedIds, carrierIds),
                importedAtMillis = manifestFile.lastModified(),
                overrideFunctions = descriptor.overrideFunctions.sorted().distinct(),
            )
        }
        val sorted = mods.sortedWith(
            compareBy<InstalledMod> { it.enabled }.reversed()
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )
        return ModManageScan(sorted, skipped)
    }

    /**
     * 缺依赖体检（ModDX2-C）：`requires` 声明的每个依赖键既不在**其余**已扫 manifest 的
     * 包名集合、也不在内置载体名单 ⇒ 记为缺。键解析复用 [ModuleDescriptor.parseRequiresConstraint]
     * 单一语法权威（条目语法已在构造期 fail-closed，重放必成功），不新造语法。
     *
     * - 「其余」不含自身：requires 指向自身是链接期「依赖环」拒载面
     *   （`ModuleLinkOrder.buildGraph` 的 requireNotSelf 同口径），不算已满足；
     * - 声明序 = 显示序；同一键重复声明只记一次；
     * - 只判存在性，不判版本区间（区间越界是链接期 checkRequiresVersion 的活）。
     */
    private fun missingRequiresOf(
        descriptor: ModuleDescriptor,
        scannedIds: Set<String>,
        carrierIds: Set<String>,
    ): List<String> {
        if (descriptor.requires.isEmpty()) return emptyList()
        val others = scannedIds - descriptor.packageId
        val missing = ArrayList<String>(0)
        for (entry in descriptor.requires) {
            val key = ModuleDescriptor.parseRequiresConstraint(entry).packageId
            if (key !in others && key !in carrierIds && key !in missing) missing += key
        }
        return missing
    }

    private fun sourceOf(packageId: String, hasModule: Boolean): ModSource = when {
        packageId.startsWith(ModPackageInventory.PACKAGE_ID_PREFIX) -> ModSource.IMPORTED
        hasModule -> ModSource.DATA_PACKAGE
        else -> ModSource.BUILTIN
    }
}
