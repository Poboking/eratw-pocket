package com.eratw.port.android.market

import com.eratw.port.android.ModFinC3PackagedImport
import com.eratw.port.android.ModImportController
import com.eratw.port.android.ModImportFailureKind
import com.eratw.port.android.ModImportOutcome
import com.eratw.port.android.ModPackageInventory
import com.eratw.port.android.ModRuntimeAssembly
import com.eratw.port.android.ModSettingsStore
import com.eratw.port.android.PickedModFile
import com.eratw.port.platform.storage.ModStore
import java.io.File
import java.io.IOException

/**
 * 「市场已装包 → 装进游戏」的接线桥（CONTRACT-MKM-W2 §0，MKM-W2 波 mkm-wire lane）。
 *
 * 把市场库里**已装好**的包（[InstalledPack]）送进既有 R7 导入链（[ModImportController]：
 * 源留档 → 编译 → 转码 → `mods/rt2/` 三件套），并自带一份导入台账
 * （[MarketImportsLedger]，`<filesDir>/market/imports.jsonl`）支撑「更新重导后旧版清理」；
 * MKM-W4-UNLINK 波在此之上补双向面：末版本卸载联动（[syncUninstalled]）把装进游戏的
 * 产物三件套与台账记录一并清掉。全程零既有文件改动：byte[] 缝已存在（PickedModFile 直收
 * 本地字节），导入链与市场管线都只是被调用方。
 *
 * ## 数据面选择（契约要求写明）
 *
 * 1. **包清单来源 = `MarketVerifier.parsePackManifest`**（构造注入的 [MarketVerifier]
 *    接口；生产装配传 `MarketVerifierImpl`，装配点归 UI 波）。不自己拼 JSON 解析——
 *    清单校验闸（路径/重复/字节/sha 形态）是 W1 已验语义，桥复用不复制。
 * 2. **ModImportController 注入形态 = 工厂**（`controllerFactory: (gameRoot) -> Controller`），
 *    照 SettingsActivity `modImportController()` 的「无状态随用随建」形态（:1170-1174）：
 *    导入链每次导入现建，baseSource / ModStore 的生产装配由调用方在工厂里给，桥不持有
 *    任何导入链状态。
 * 3. **条目过滤谓词 = 直引 [ModStore.hasErbExtension] / [ModStore.hasErhExtension]**，
 *    与 ModZip.unpack 的条目筛选（ModImportController.kt:972-973 调用点）同源零复制。
 *
 * 桥对市场 W1 文件面的引用只到市场骨架（MarketTypes.kt）的接口与数据类型，不 import
 * 任何 W1 实现类。
 *
 * ## 流程（§0.3 + §2.4 的端态判据）
 *
 * ① 已装目录定位（[MarketContract] 派生路径，缺 = [MarketBridgeOutcome.NotFound]）→
 * ② 读 pack.json 清单（缺席 / 读不出 / 解析拒 = STORAGE 面的 [MarketBridgeOutcome.ImportFailed]）→
 * ③ 过滤 `.ERB`/`.ERH` 条目成 `List<PickedModFile>`（displayName = 剥掉 `files/` 前缀的
 * 包内相对路径，fromArchive 同 zip 条目口径；零条目 = [MarketBridgeOutcome.EmptyPack]，
 * **导入链未被触达、mods 零落盘、台账零写入**）→ ④ 调导入链（失败原样折进
 * [MarketBridgeOutcome.ImportFailed]，台账不写）→ ⑤ 成功后簿记：查台账旧 packageId，
 * 与本次不同且旧全套在场就删旧（`.sources`/`.manifest`/`.erbm` 导入产物 + `<pkg>.settings`
 * 设置侧车 + `<pkg>.assets/` 资源整树，存在才删，不碰其它包），然后写台账；相同 = 原地替换
 * （导入链同名覆盖语义），无删除动作。
 *
 * **删除序取「导入成功之后、写台账之前」**（任务 §⑤ 流程）：失败安全的取舍——若先删旧
 * 再导入、导入半路失败，玩家手里的旧版本就两头落空（旧三件套已删、新三件套没成）；
 * 后删序下导入失败旧版原样保留，台账指向不变，失败只等于「什么都没发生」。契约 §2.4
 * 钉的是端态（旧三件套被删、新三件套在场、台账指向新包），两种序都满足；端态断言见
 * MarketGameBridgeTest。
 *
 * ## 线程
 *
 * 全部动作阻塞调用线程（照 install/import 既有口径，CONTRACT §0.5）：清单读取、编译
 * 链、台账 IO 都是同步调用，UI 侧自理后台线程。
 *
 * @param marketRoot 市场库根（`<filesDir>/market`；已装包与台账都在其下）。
 * @param gameRoot 游戏根（导入链的 mods/ 与 rt2 目录都从它派生，与控制器工厂同一根）。
 * @param controllerFactory 导入链构造工厂（见上「数据面选择」2）。
 * @param verifier 清单解析口（见上「数据面选择」1）。
 */
class MarketGameBridge(
    private val marketRoot: File,
    private val gameRoot: File,
    private val controllerFactory: (gameRoot: File) -> ModImportController,
    private val verifier: MarketVerifier,
) {

    /** 导入台账（桥独占文件；文件名常量单源在 [MarketImportsLedger.FILE_NAME]）。 */
    private val ledger = MarketImportsLedger(File(marketRoot, MarketImportsLedger.FILE_NAME))

    /**
     * 把一个市场已装包装进游戏（见类 KDoc 流程）。幂等性：同版本原地替换；换版本 =
     * 旧三件套清理 + 新包落位 + 台账改指。
     *
     * ERAFIN-C3：`mod-pack` kind 走**成品包导入合同**（[importInstalledModPack]；包的
     * `files/` 树带编译产物两件套，跳过编译链直接落 `mods/rt2`），旧三 kind 走既有源态
     * 编译链，路径互斥按 kind 分流。
     */
    fun importInstalled(pack: InstalledPack, modRuntimeEnabled: Boolean): MarketBridgeOutcome {
        // ERAFIN-C3 分流闸（guard：mod-pack 走成品包导入合同，旧三 kind 落进下方既有主体，
        // 主体与旧版逐位不变——基线里本函数的 ReturnCount 条目因此继续命中，不产生新红）。
        if (pack.kind == MarketContract.KIND_MOD) return importInstalledModPack(pack, modRuntimeEnabled)
        // ① 已装目录定位：路径派生唯一真源 = MarketContract 的目录段名，不拼第二份
        //（`<marketRoot>/packs/<kind>/<id>/<version>/`，MarketStore.dirOf 的同源派生）。
        val packDir = File(File(File(File(marketRoot, MarketContract.PACKS_DIR), pack.kind), pack.id), pack.version)
        if (!packDir.isDirectory) return MarketBridgeOutcome.NotFound

        // ②③ 读清单 + 过滤（先过滤后调用）。
        val phase = collectSources(packDir)
        if (phase is SourcePhase.Stop) return phase.outcome
        val ready = phase as SourcePhase.Ready

        // ④ 真链导入：失败原样透出（编译错误带文件:行），台账不写（成功才写）。
        // 覆盖名单由导入链按同名现推；包清单的可选收窄声明原样透传（null = 未声明）。
        val detail = when (
            val outcome = controllerFactory(gameRoot)
                .import(
                    ready.picked,
                    modRuntimeEnabled = modRuntimeEnabled,
                    declaredOverrides = ready.declaredOverrides,
                )
        ) {
            is ModImportOutcome.Failed -> return MarketBridgeOutcome.ImportFailed(outcome)
            is ModImportOutcome.Imported -> outcome.detail
        }
        val newPackageId = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)

        // ⑤ 簿记失败不盖成功态：返回 LedgerFailed，否则 Ok。
        val bookProblem = recordImport(pack, newPackageId)
        return bookProblem ?: MarketBridgeOutcome.Ok(newPackageId, detail)
    }

    /**
     * `mod-pack` 已装包的装进游戏（ERAFIN-C3 成品包导入合同）：
     *
     * ① 已装目录定位（NotFound 语义与源态路径同形）→ ② 读 `pack.json` 清单
     * （verifier 解析，与源态路径同一条数据面）→ ③ 把 `files/` 树按清单条目**折成内存 zip
     * 字节**（[ModFinC3PackagedImport.importModPackLibrary]，条目名 = 剥 `files/` 前缀的
     * 包内相对路径——与 SAF 直选 zip 的条目形态逐位同形，成品包合同因此只写一份实现）
     * → ④ 成品包合同
     * （[ModFinC3PackagedImport.importPackagedModule]：身份自洽 + `artifact-sha256` 摘要
     * 对账 + `mods/rt2` 三件套落盘 + 资源/代码产物面）→ ⑤ 簿记（[recordImport] 与源态路径
     * 共用；packageId 取成品包 manifest 的自述身份，可能是非 `kojo-` 前缀——
     * [deletePackageArtifacts] 按 packageId 拼名删除，无前缀假设）。
     *
     * 两件套缺失（`pack.json` 不锚定 `<pkg>.manifest`+`<pkg>.erbm`）= 包不是合法成品包，
     * 折 STORAGE 失败（problem 是取证面原话，与既有「pack.json 读不出来」同一上屏通道）；
     * 导入链未被触达、`mods/` 零落盘、台账零写入。
     */
    private fun importInstalledModPack(pack: InstalledPack, modRuntimeEnabled: Boolean): MarketBridgeOutcome {
        val packDir = File(File(File(File(marketRoot, MarketContract.PACKS_DIR), pack.kind), pack.id), pack.version)
        if (!packDir.isDirectory) return MarketBridgeOutcome.NotFound
        val result = ModFinC3PackagedImport.importModPackLibrary(packDir, verifier, gameRoot, modRuntimeEnabled)
        return when (result) {
            is ModFinC3PackagedImport.LibraryOutcome.Failed -> MarketBridgeOutcome.ImportFailed(result.failure)
            is ModFinC3PackagedImport.LibraryOutcome.Imported -> {
                val newPackageId = result.detail.patchFileName
                    .removeSuffix(ModPackageInventory.MODULE_EXTENSION)
                val bookProblem = recordImport(pack, newPackageId)
                bookProblem ?: MarketBridgeOutcome.Ok(newPackageId, result.detail)
            }
        }
    }

    /**
     * 市场库卸载联动（CONTRACT-MKM-W4-UNLINK §0）：`(kind, id)` 的最后一个库版本被卸载后，
     * 由调用方（UI 卸载动作链）在 `MarketService.uninstall` 成功后传入库内剩余版本数。
     * 还有残留版本（[remainingVersions] != 0）⇒ 游戏产物归「最新在装版本」口径，不联动，
     * 出 [BridgeSyncOutcome.Kept]；卸到零 ⇒ 查 [MarketImportsLedger] 取该键的 packageId，
     * 删 `mods/rt2/` 该包的容器三件套（`.sources` / `.manifest` / `.erbm` 固定集合，存在才删，
     * 只碰本包不碰其它包）、随包附加侧车（设置 / 资源 / 模板 / 覆盖与身份侧车），再清台账该键
     * 记录，出 [BridgeSyncOutcome.Cleaned]。
     *
     * **删序 = 先删产物、后清台账**（契约 §0.3，与导入「成功后删旧、写台账最后」镜像）：
     * 中断时残留台账记录指向已删产物，下次同包装回 = 台账覆盖，无孤儿闸风险；反序（先清
     * 台账）会让半路失败的删除丢掉重试面 —— 记录没了，再调本函数查不到该删什么。
     *
     * **fail-soft**（契约 §0.4）：联动失败不抛出、不清台账（保重试面），返回
     * [BridgeSyncOutcome.Failed] 人话出口；市场卸载本身不受影响（卸载语义在先且已成功）。
     *
     * **零文件可删**（产物早被 gcOrphans 清过）⇒ [BridgeSyncOutcome.Cause.GamesAbsent]
     * 的 Kept（worker 对契约 §2.3 二选一的裁定，测试钉住）：一个字节不动，台账残留行是
     * 契约 §0.3 已 Bless 的安全态。
     *
     * 桥不算库账：[remainingVersions] 由调用方以 `installed()` 现算传入（契约 §0.2）；
     * 非 0 一律按「仍有在装版本」处理（负数视为调用方 bug，宁可不动手也不删错）。
     *
     * @param kind 市场包种类（与 [InstalledPack.kind] 同面）。
     * @param id 市场包 id（与 [InstalledPack.id] 同面）。
     * @param remainingVersions 卸载后该键在市场库里的剩余版本数（0 = 末版本已卸）。
     */
    fun syncUninstalled(kind: String, id: String, remainingVersions: Int): BridgeSyncOutcome =
        when {
            remainingVersions != 0 -> BridgeSyncOutcome.Kept(BridgeSyncOutcome.Cause.VersionsRemaining)
            else ->
                // 末版本联动主体：相位一（台账查记录）→ 相位二（删三件套）→ 相位三（清台账）。
                when (val phase = lookupRecord(kind, id)) {
                    is SyncPhase.Record -> removeArtifacts(kind, id, phase.record)
                    is SyncPhase.Stop -> phase.outcome
                }
        }

    /** 相位一：台账查记录（读失败折 Failed；查无此键折 Kept(LedgerAbsent)，零删零写）。 */
    private fun lookupRecord(kind: String, id: String): SyncPhase = try {
        val found = ledger.lookup(kind, id)
        if (found == null) {
            SyncPhase.Stop(BridgeSyncOutcome.Kept(BridgeSyncOutcome.Cause.LedgerAbsent))
        } else {
            SyncPhase.Record(found)
        }
    } catch (e: IOException) {
        SyncPhase.Stop(BridgeSyncOutcome.Failed("卸载联动没做成：导入台账读不出来（${e.message}）"))
    }

    /**
     * 相位二：删本包三件套并按实删件数定态 —— 删除失败折 Failed（台账不清，保重试面）；
     * 零删 = 产物早不在，折 Kept(GamesAbsent)；有删 = 进相位三。
     */
    private fun removeArtifacts(kind: String, id: String, record: MarketImportRecord): BridgeSyncOutcome =
        when (val removal = deletePackageArtifacts(record.packageId, "卸载联动没做成：游戏产物三件套删除失败（可重试）")) {
            is ArtifactRemoval.Problem -> BridgeSyncOutcome.Failed(removal.problem)
            is ArtifactRemoval.Done ->
                if (removal.removed == 0) {
                    BridgeSyncOutcome.Kept(BridgeSyncOutcome.Cause.GamesAbsent)
                } else {
                    // 相位三：清台账。remove 失败时产物可能已删一部分 —— 台账记录在就是
                    // 重试面，下次同包装回也照 §0.3 覆盖自愈。
                    try {
                        ledger.remove(kind, id)
                        BridgeSyncOutcome.Cleaned(record.packageId)
                    } catch (e: IOException) {
                        BridgeSyncOutcome.Failed(
                            "卸载联动没做成：游戏产物已删，但台账清不掉（下次同包装回会覆盖自愈）：${e.message}",
                        )
                    }
                }
        }

    /**
     * ②③ 相位：读 pack.json 清单（缺席 / 读不出 / 解析拒 = STORAGE 面失败）→ 过滤
     * `.ERB`/`.ERH` 条目成源列表（readme/csv 等静默跳过）。零条目 = EmptyPack——此刻
     * 导入链未被触达，mods/ 零落盘、台账零写入。[SourcePhase.Stop] 即整个动作的出口。
     */
    private fun collectSources(packDir: File): SourcePhase {
        val manifestText = try {
            File(packDir, MarketContract.MANIFEST_FILE).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            return SourcePhase.Stop(storageFailureOf("已装包的 pack.json 读不出来：${e.message}"))
        } ?: return SourcePhase.Stop(storageFailureOf("已装目录里没有 pack.json：${packDir.path}"))
        val manifest = when (val parsed = verifier.parsePackManifest(manifestText)) {
            is MarketParseResult.Ok -> parsed.value
            is MarketParseResult.Invalid -> return SourcePhase.Stop(storageFailureOf(parsed.problem))
        }
        val picked = ArrayList<PickedModFile>(manifest.files.size)
        for (entry in manifest.files) {
            if (!isSourceEntry(entry.path)) continue
            val bytes = try {
                File(packDir, entry.path).readBytes()
            } catch (e: IOException) {
                return SourcePhase.Stop(storageFailureOf("包内容读不出来：${entry.path}（${e.message}）"))
            }
            // displayName = 剥掉 files/ 前缀的包内相对路径（zip 条目同形态：相对路径进
            // 排序与编译报错定位，落盘留档时由 PickedModFile.diskName 切主名）。
            picked += PickedModFile(
                entry.path.removePrefix(MarketContract.CONTENT_DIR + "/"),
                bytes,
                fromArchive = true,
            )
        }
        if (picked.isEmpty()) return SourcePhase.Stop(MarketBridgeOutcome.EmptyPack)
        return SourcePhase.Ready(picked, manifest.overrideFunctions)
    }

    /**
     * ⑤ 簿记相位：查台账旧 packageId → 换包删旧三件 → 写台账。任一步失败 = LedgerFailed
     * （导入本体已落盘，人话必须交代；台账没记下本次，下次同包导入照「无旧版」路径自愈）。
     * 返回 null = 簿记干净，调用方出 Ok。
     */
    private fun recordImport(pack: InstalledPack, newPackageId: String): MarketBridgeOutcome.LedgerFailed? {
        val old = try {
            ledger.lookup(pack.kind, pack.id)
        } catch (e: IOException) {
            return ledgerFailedOf("导入已落盘，但台账读不出来（本次导入未记账）：${e.message}")
        }
        if (old != null && old.packageId != newPackageId) {
            // 换包删旧三件套（存在才删；零删 = 旧产物早不在，同样算干净）。失败折 LedgerFailed。
            val removal =
                deletePackageArtifacts(old.packageId, "导入已落盘，但旧版三件套删除失败（本次导入未记账）")
            if (removal is ArtifactRemoval.Problem) return ledgerFailedOf(removal.problem)
        }
        try {
            ledger.upsert(
                MarketImportRecord(
                    kind = pack.kind,
                    id = pack.id,
                    version = pack.version,
                    packageId = newPackageId,
                    importedAtMs = System.currentTimeMillis(),
                ),
            )
        } catch (e: IOException) {
            return ledgerFailedOf("导入已落盘，但台账写不进去（本次导入未记账）：${e.message}")
        }
        return null
    }

    // ------------------------------------------------------------------
    // 私有派生与清理
    // ------------------------------------------------------------------

    /**
     * 条目过滤谓词：`.ERB`/`.ERH` 尾段（大小写不敏感、须有主名）。与 ModZip.unpack 的
     * 条目筛选同源（直引 ModStore 判定函数，零复制）；其余条目（readme/csv/图片）静默跳过。
     * 文件级私有（不碰实例状态；压类函数数，同 helper 的相位函数先例）。
     */
    private fun isSourceEntry(manifestPath: String): Boolean =
        ModStore.hasErbExtension(manifestPath) || ModStore.hasErhExtension(manifestPath)

    /**
     * 删一个 packageId 的盘上全套（导入产物 + 设置侧车 + 资源目录 + 随包角色模板目录；
     * 存在才删），导入换包与卸载联动两路共用的删除件。只动本 packageId 自己的件，rt2 目录里
     * 其它任何文件（别的包、内置资产、玩家手放件）一律不碰；中途某件删除失败即收口
     * （名单后续成员不再尝试），problem 点名删不掉的文件。
     *
     * **删除面 = 容器三件套 + 附加侧车**：容器三件套（`.sources` / `.manifest` / `.erbm`）是
     * 有语义定义的固定集合（换包撤旧、卸载清包都整组删），直接引用
     * [ModPackageInventory.SOURCES_EXTENSION] / [ModPackageInventory.MANIFEST_EXTENSION] /
     * [ModPackageInventory.MODULE_EXTENSION]；**不拿 [ModPackageInventory.memberNames] 顶替** ——
     * 后者是「全部包成员」名单，会随附加侧车（`.sources.sha256` / `.assetmap` / `.codeid`）增减
     * 漂移，让删除面与计数跟着变。附加侧车（指纹 / 覆盖侧车 / 代码身份侧车 / `.settings`）
     * 属另一层，不参与本判据、不计数（见下），其中 `.codeid` 由卸载/GC 随包清。
     * 设置侧车 = [ModSettingsStore.memberName]；资源整树 =
     * [ModPackageInventory.ASSET_DIR_SUFFIX]；模板整树 = [ModPackageInventory.CHARA_DIR_SUFFIX]。
     * 本文件不写任何设置后缀 / 资源后缀 / 模板后缀的字符串字面量（源级哨兵钉住，照 CX 波口径）。
     *
     * **侧车不计入 [ArtifactRemoval.Done.removed]**：该计数只数容器三件套（`.sources` /
     * `.manifest` / `.erbm`），因为调用方的 `removed == 0` 决定「零文件可删 ⇒ Kept(GamesAbsent)」
     * 还是「有产物被删 ⇒ 进相位三/清台账」；把侧车算进去，会让「产物早被 gc 清掉、只剩残留设置」
     * 被误判成「有产物被删」而错误清台账。侧车的删除是机会性的：删掉了不改变计数，删不掉则按
     * 同一重试面口径折 [ArtifactRemoval.Problem]（见下）。
     *
     * **侧车删除失败 = Problem（与导入产物同口径）**：市场链路的两路调用方都已把
     * Problem 折成人话失败出口（换包 = `LedgerFailed`「导入已落盘但本次未记账」、卸载联动 =
     * `Failed`「可重试」）且都不清台账/不吞脏，重试面完整；静默留一个删不掉的 `.settings`
     * 正是本笔要根治的「旧设置静默复活」缺陷本身，故不选 WARN + 继续。
     *
     * 已知重试态：若失败发生在导入产物已删完之后（如只剩侧车删不掉），重试时 removed == 0
     * 会按契约落 `Kept(GamesAbsent)`（台账残留行 = §0.3 已 Bless 的安全态），但该轮仍会继续
     * 清侧车 —— 侧车清理不因「零产物」而跳过。
     */
    private fun deletePackageArtifacts(packageId: String, failurePrefix: String): ArtifactRemoval {
        val rt2 = ModRuntimeAssembly.manifestDirFor(gameRoot)
        val members = listOf(
            packageId + ModPackageInventory.SOURCES_EXTENSION,
            packageId + ModPackageInventory.MANIFEST_EXTENSION,
            packageId + ModPackageInventory.MODULE_EXTENSION,
        ).map { File(rt2, it) }.filter { it.isFile }
        // indexOfFirst 惰性求值：删到第一件失败即停，后续成员不再尝试（与旧循环 break 同语义）。
        val failedIndex = members.indexOfFirst { !it.delete() }
        val settings = File(rt2, ModSettingsStore.memberName(packageId))
        val assets = File(rt2, packageId + ModPackageInventory.ASSET_DIR_SUFFIX)
        // 随包角色模板目录（ERA-A2）：与资源目录同款机会性整树删除，不计数、失败折 Problem。
        val charaDir = File(rt2, packageId + ModPackageInventory.CHARA_DIR_SUFFIX)
        val failure = when {
            failedIndex >= 0 -> members[failedIndex].name
            // 设置侧车（单文件）与资源/模板目录（整树）在导入产物之后机会性清掉；不计数。
            settings.isFile && !settings.delete() -> settings.name
            assets.exists() && !assets.deleteRecursively() -> assets.name
            charaDir.exists() && !charaDir.deleteRecursively() -> charaDir.name
            else -> null
        }
        return failure?.let { ArtifactRemoval.Problem("$failurePrefix：$it") }
            ?: ArtifactRemoval.Done(members.size)
    }

}

/**
 * 库内容面的 STORAGE 失败统一出口（文件级：不碰实例状态，压桥的类函数数——桥既有 11 个
 * 函数贴着 detekt `TooManyFunctions` 阈值，mod-pack 分流的两个新函数进来就超，把两个无状态
 * 构造点搬出来补差）。
 */
private fun storageFailureOf(problem: String): MarketBridgeOutcome =
    MarketBridgeOutcome.ImportFailed(ModImportOutcome.Failed(ModImportFailureKind.STORAGE, problem))

/** 簿记面失败统一出口（[MarketBridgeOutcome.LedgerFailed] 的构造单点；文件级，压类函数数）。 */
private fun ledgerFailedOf(problem: String): MarketBridgeOutcome.LedgerFailed =
    MarketBridgeOutcome.LedgerFailed(problem)

/**
 * ②③ 相位的出口：[Ready] = 源列表就绪，可以进导入链；[Stop] = 整个动作到此为止
 * （[MarketBridgeOutcome] 已定形，调用方原样返回）。
 */
private sealed interface SourcePhase {
    /** 过滤后的源条目就绪（`declaredOverrides` = 清单的可选收窄声明，null = 未声明）。 */
    data class Ready(
        val picked: List<PickedModFile>,
        val declaredOverrides: List<String>?,
    ) : SourcePhase

    /** 提前收口：携带既定的失败 / 空包出口。 */
    data class Stop(val outcome: MarketBridgeOutcome) : SourcePhase
}

/**
 * 卸载联动相位一（台账查记录）的出口：[Record] = 记录在手、进删除相位；[Stop] = 整个
 * 动作到此为止（[BridgeSyncOutcome] 已定形，调用方原样返回）。
 */
private sealed interface SyncPhase {
    /** 台账记录就绪（packageId 即删除名单的来源）。 */
    data class Record(val record: MarketImportRecord) : SyncPhase

    /** 提前收口：携带既定的 Kept / Failed 出口。 */
    data class Stop(val outcome: BridgeSyncOutcome) : SyncPhase
}

/**
 * 三件套 + 侧车删除相位的出口（导入换包与卸载联动两路共用）：[Done] = 名单走完（[Done.removed]
 * = 实删导入产物件数，0 = 产物本就不在场；设置侧车/资源目录不计数）；[Problem] = 某件删除
 * 失败即收口（含删不掉的侧车）。
 */
private sealed interface ArtifactRemoval {
    /** 名单走完：[removed] = 实删导入产物件数（0 = 三件本就不在场；侧车清掉也不加数）。 */
    data class Done(val removed: Int) : ArtifactRemoval

    /** 中途失败：[problem] 人话点名删不掉的文件，调用方折各自的失败出口。 */
    data class Problem(val problem: String) : ArtifactRemoval
}
