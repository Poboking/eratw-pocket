package com.eratw.port.android

import java.io.File

/**
 * 卸载影响预览的输入快照（只读读数集合）。
 *
 * 每个字段都对应 [ModManageUninstall] 卸载时会真正删掉的一类盘面/会话数据；`null` 只表示
 * **这个读数取不到**（对话框里显示「—」），而不是「没有」——「没有」用 0 表示（显示「无」）。
 * 两者分开是硬要求：把取不到的读数画成 0 会让玩家以为卸载什么都不会删。
 *
 * 输入面刻意**不含启用态**：停用包与启用包卸载删的是同一批文件，影响预览必须照样能算。
 *
 * @property storage 存档侧车段读数（[ModStorageReadout]：序列化字节 + 条目数）；会话未接线时 null
 * @property settingsKeys 包设置 `.settings` 的键数（[ModSettingsStore.read] 的条数）
 * @property code 代码概览行（[ModCodeRow]：版本数 + 字节）；代码区读不到时 null
 * @property mediaEntries `<packageId>.media` 媒体清单条目数；清单损坏时 null
 * @property assets `.assets` 资源目录读数；目录不可读时 null
 * @property chara `.chara` 角色模板目录读数；目录不可读时 null
 * @property packageMembers 包成员文件数（[ModPackageInventory.memberNames] 里实际在场的个数）
 */
internal data class ModUninstallImpactInput(
    val storage: ModStorageReadout? = null,
    val settingsKeys: Int? = null,
    val code: ModCodeRow? = null,
    val mediaEntries: Int? = null,
    val assets: ModUninstallImpactDir? = null,
    val chara: ModUninstallImpactDir? = null,
    val packageMembers: Int? = null,
)

/**
 * 目录读数：条目数 + 字节数。
 *
 * [entries] 为 0 表示「没有这些文件」（屏上写「无」），与输入整体为 null 的「取不到」区分。
 */
internal data class ModUninstallImpactDir(
    val entries: Int,
    val bytes: Long,
)

/** 预览行的类别；**声明顺序即屏上顺序**（[buildModUninstallImpact] 按此顺序逐项输出）。 */
internal enum class ModUninstallImpactCategory {
    CODE,
    SETTINGS,
    STORAGE,
    MEDIA,
    ASSETS,
    CHARA,
    MEMBERS,
}

/**
 * 一行预览的纯数据。
 *
 * @property count 条目数；null = 读数缺失（「—」），0 = 确实没有（「无」）
 * @property bytes 体积；只有 CODE / STORAGE / ASSETS 三行用（其余行为 null）
 */
internal data class ModUninstallImpactItem(
    val category: ModUninstallImpactCategory,
    val count: Int?,
    val bytes: Long? = null,
)

/** 媒体清单首行前缀：`RT2MEDIA/1 dir=`（版本行与写侧同源拼装，不另写字面量）。 */
private const val MEDIA_HEADER_PREFIX: String =
    ModImportMediaAssets.MANIFEST_VERSION + " " + ModImportMediaAssets.MANIFEST_DIR_KEY

/**
 * 把输入快照拍成稳定顺序的预览行数据（纯函数，不读盘、不碰 Android）。
 *
 * 七行全出、不按 0 隐藏：卸载影响是「会删什么」的清单，缺一行比多一行更误导。
 */
internal fun buildModUninstallImpact(input: ModUninstallImpactInput): List<ModUninstallImpactItem> = listOf(
    ModUninstallImpactItem(ModUninstallImpactCategory.CODE, input.code?.versions, input.code?.bytes),
    ModUninstallImpactItem(ModUninstallImpactCategory.SETTINGS, input.settingsKeys, null),
    ModUninstallImpactItem(ModUninstallImpactCategory.STORAGE, input.storage?.keys, input.storage?.bytes),
    ModUninstallImpactItem(ModUninstallImpactCategory.MEDIA, input.mediaEntries, null),
    ModUninstallImpactItem(ModUninstallImpactCategory.ASSETS, input.assets?.entries, input.assets?.bytes),
    ModUninstallImpactItem(ModUninstallImpactCategory.CHARA, input.chara?.entries, null),
    ModUninstallImpactItem(ModUninstallImpactCategory.MEMBERS, input.packageMembers, null),
)

/**
 * 影响预览的多行文本：首行是小标题，其后按 [ModUninstallImpactCategory] 声明顺序每类一行。
 *
 * 行格式复用详情页的「标签：值」句式（[SettingsStrings.modManageDetailLine]），分隔符留在文案表里。
 */
internal fun impactSummaryLines(text: SettingsStrings, input: ModUninstallImpactInput): List<String> =
    listOf(text.modUninstallImpactHeading) + buildModUninstallImpact(input).map { item -> impactLine(text, item) }

/** 单行 = 类别标签 + 读数（「无」/「—」/「数量 / 体积」）。 */
private fun impactLine(text: SettingsStrings, item: ModUninstallImpactItem): String =
    text.modManageDetailLine(impactLabel(text, item.category), impactValue(text, item))

/** 类别标签（三语在文案表，此处只做枚举到条目的映射）。 */
private fun impactLabel(text: SettingsStrings, category: ModUninstallImpactCategory): String = when (category) {
    ModUninstallImpactCategory.CODE -> text.modUninstallImpactCode
    ModUninstallImpactCategory.SETTINGS -> text.modUninstallImpactSettings
    ModUninstallImpactCategory.STORAGE -> text.modUninstallImpactSave
    ModUninstallImpactCategory.MEDIA -> text.modUninstallImpactMedia
    ModUninstallImpactCategory.ASSETS -> text.modUninstallImpactAssets
    ModUninstallImpactCategory.CHARA -> text.modUninstallImpactChara
    ModUninstallImpactCategory.MEMBERS -> text.modUninstallImpactMembers
}

/**
 * 单行读数：`null` ⇒「—」（取不到），0 ⇒「无」（确实没有），其余按类别拼数量与体积。
 *
 * 体积文本由 [formatStorageBytes] 出（与运行时概览同一口径，B/KB/MB 是三种语言共用的技术单位）。
 */
private fun impactValue(text: SettingsStrings, item: ModUninstallImpactItem): String {
    val count = item.count
    val size = item.bytes?.let(::formatStorageBytes).orEmpty()
    return when {
        count == null -> text.modUninstallImpactMissing
        count == 0 -> text.modUninstallImpactNone
        item.category == ModUninstallImpactCategory.CODE -> text.modUninstallImpactCodeLine(count, size)
        item.category == ModUninstallImpactCategory.STORAGE -> text.modUninstallImpactSaveLine(size, count)
        item.category == ModUninstallImpactCategory.ASSETS -> text.modUninstallImpactAssetsLine(count, size)
        else -> text.modUninstallImpactCountLine(count)
    }
}

/**
 * 盘面 + 会话读数 → 预览多行（设置页卸载确认对话框的唯一数据入口）。
 *
 * 全程**只读**：读 `.settings`、扫 `code/`、数 `.media` / `.assets` / `.chara` / 包成员，
 * 一个字节都不写、一个文件都不删——删除语义仍只在 [ModManageUninstall] 里。
 *
 * 两套身份各管一段（与 [uninstallModAndQueueReclaim] 同口径）：盘面产物 / 设置 / 媒体 / 成员按
 * **磁盘身份**拼路径（`code/<diskId>/`、`<diskId>.settings` 等）；运行中会话的 storage 段按
 * **运行期身份**命名（`storage.activate/release` 同键），故先经 [ModTrustStore.resolveRuntimeId]
 * 解析再读。侧车坏档 ⇒ 解析返回 null ⇒ storage 显式「—」（既有 fail-closed 口径），不猜任何段。
 *
 * @param rt2Dir `mods/rt2` 运行期目录
 * @param packageId 目标包（磁盘身份，与设置页行、卸载入口一致）
 * @param dexLoaderEnabled 代码区总闸（与只读概览卡同口径，只决定代码行的读数口径）
 */
internal fun buildUninstallImpactLines(
    text: SettingsStrings,
    rt2Dir: File,
    packageId: String,
    dexLoaderEnabled: Boolean,
): List<String> {
    val runtimeId = ModTrustStore.resolveRuntimeId(rt2Dir, packageId)
    val storage = runtimeId?.let { id ->
        runCatching { ModRuntimeOverviewEntry.read(listOf(id))?.storageByPackage?.get(id) }.getOrNull()
    }
    return impactSummaryLines(text, readUninstallImpactInput(rt2Dir, packageId, dexLoaderEnabled, storage))
}

/** 逐项取盘面读数（只读）；取不到的分项给 null，交给 [impactValue] 显示「—」。 */
private fun readUninstallImpactInput(
    rt2Dir: File,
    packageId: String,
    dexLoaderEnabled: Boolean,
    storage: ModStorageReadout?,
): ModUninstallImpactInput = ModUninstallImpactInput(
    storage = storage,
    settingsKeys = ModSettingsStore.read(rt2Dir, packageId).size,
    code = buildModCodeOverview(rt2Dir, dexLoaderEnabled).rows.firstOrNull { it.packageId == packageId },
    mediaEntries = mediaEntryCount(rt2Dir, packageId),
    assets = dirReadout(File(rt2Dir, packageId + ModPackageInventory.ASSET_DIR_SUFFIX)),
    chara = dirReadout(File(rt2Dir, packageId + ModPackageInventory.CHARA_DIR_SUFFIX)),
    packageMembers = memberCount(rt2Dir, packageId),
)

/**
 * 媒体清单条目数：清单不在 ⇒ 0（没有旁挂媒体）；清单在但读不动/首行不合法 ⇒ null。
 *
 * 条目数 = 首行版本行之後的行数（写侧每行一个相对路径），与 [ModMediaReclaim] 的台账口径一致。
 */
private fun mediaEntryCount(rt2Dir: File, packageId: String): Int? {
    val manifest = ModMediaReclaim.manifestFile(rt2Dir, packageId)
    if (!manifest.isFile) return 0
    return runCatching { manifest.readLines(Charsets.UTF_8) }.getOrNull()
        ?.takeIf { lines -> lines.firstOrNull()?.startsWith(MEDIA_HEADER_PREFIX) == true }
        ?.let { lines -> (lines.size - 1).coerceAtLeast(0) }
}

/** 目录读数：不存在 ⇒ 条目 0；存在但遍历失败 ⇒ null。 */
private fun dirReadout(dir: File): ModUninstallImpactDir? {
    val files = if (dir.isDirectory) {
        runCatching { dir.walkTopDown().filter { it.isFile }.toList() }.getOrNull() ?: return null
    } else {
        emptyList<File>()
    }
    return ModUninstallImpactDir(entries = files.size, bytes = files.sumOf { it.length() })
}

/** 包成员文件数：按 [ModPackageInventory.memberNames] 逐名判在场（只读，不建不删）。 */
private fun memberCount(rt2Dir: File, packageId: String): Int =
    ModPackageInventory.memberNames(packageId).count { name -> File(rt2Dir, name).isFile }
