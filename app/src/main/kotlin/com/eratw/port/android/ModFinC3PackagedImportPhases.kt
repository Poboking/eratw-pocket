package com.eratw.port.android

import com.eratw.port.android.market.MarketContract
import com.eratw.port.android.market.MarketParseResult
import com.eratw.port.android.market.MarketVerifier
import com.eratw.port.android.market.PackManifest
import java.io.File
import java.io.IOException

/**
 * ERAFIN-C3 成品包导入合同的**相位函数与市场库相位**（internal：主文件
 * [ModFinC3PackagedImport] 按序收口）。单独成文件的理由：detekt `TooManyFunctions`
 * 的 file 阈值（11）——合同主文件与相位/工具三文件分居，各文件函数数都停在阈值内
 * （与 ModImportController 把 commitMedia / finalizeCodeArtifact 搬文件级压类指标同一件事）。
 *
 * ## 相位清单（与主 KDoc 的 fail-closed 红线一一对应）
 *
 * 1. 身份闸 = `ModFinC3PackagedImport.packagedPairOf` + [validatedPackagedIdentity]（主文件）。
 * 2. 附加面暂存 = [stagedPackagedAssets] / [stagedPackagedCode]（校验 + 临时目录，零正式名）。
 * 3. 三件套落盘 + 读回 = [persistPackagedTrio]（provenance → manifest → 容器，写序照
 *    ModPackageInventory.memberNames；读回照 verifyWrittenPackage 口径）。
 * 4. 收尾 = [finalizePackagedSurfaces]（代码产物 commit 或反向清旧侧车 + 资源面 swap）。
 * 5. 市场库相位 = [packagedModPackOutcome]（pack.json 读+解析 → files/ 树折 zip → 两件套
 *    识别 → 导入事务，失败面在此定形）。
 *
 * 全部相位函数 fail-closed：任何失败都折 [PackagedImportException]（单点
 * [failPackaged]）或直接返回失败态，**只有成功路径写正式名**；暂存面 discard 先于失败上抛。
 */

// ---------------------------------------------------------------------------
// 相位二：资源面 / 代码产物面暂存（校验 + 临时目录，零正式名写入）
// ---------------------------------------------------------------------------

internal fun stagedPackagedAssets(
    zip: PickedModFile,
    packageId: String,
    rt2Dir: File,
    copy: ModImportCopy,
): ModImportAssets.Plan = try {
    ModImportAssets.stageSafely(listOf(zip.bytes), packageId, rt2Dir, copy)
} catch (e: ModImportAssets.AssetConflictException) {
    failPackaged(ModImportFailureKind.STORAGE, e.message ?: copy.unknownStorageError, e)
}

internal fun stagedPackagedCode(zip: PickedModFile, packageId: String): ModImportCodeArtifacts.Staged? = try {
    ModImportCodeArtifacts.stage(listOf(zip.bytes), packageId, packageId)
} catch (e: CodeArtifactRejectedException) {
    failPackaged(e.kind, e.message ?: e.kind.name, e)
} catch (e: IOException) {
    failPackaged(ModImportFailureKind.STORAGE, "代码产物暂存失败：${packagedDetailText(e)}", e)
}

// ---------------------------------------------------------------------------
// 相位三：三件套落盘 + 读回校验（provenance → manifest → 容器，写序照 memberNames）
// ---------------------------------------------------------------------------

internal fun persistPackagedTrio(
    rt2Dir: File,
    identity: PackagedValidatedIdentity,
    pair: ModFinC3PackagedImport.PackagedPair,
    sourcesBytes: ByteArray,
    copy: ModImportCopy,
) {
    val packageId = identity.packageId
    try {
        saveArtifact(rt2Dir, packageId + ModPackageInventory.SOURCES_EXTENSION, sourcesBytes)
        saveArtifact(rt2Dir, packageId + ModPackageInventory.MANIFEST_EXTENSION, pair.manifestBytes)
        saveArtifact(rt2Dir, packageId + ModPackageInventory.MODULE_EXTENSION, pair.erbmBytes)
    } catch (e: IOException) {
        failPackaged(ModImportFailureKind.STORAGE, copy.fill(copy.artifactSaveFailedFormat, packagedDetailText(e)), e)
    }
    try {
        readBackPackagedTrio(rt2Dir, packageId, sourcesBytes)
    } catch (e: IllegalStateException) {
        failPackaged(ModImportFailureKind.IDENTITY, copy.fill(copy.verifyWrittenFailedFormat, packagedDetailText(e)), e)
    } catch (e: IOException) {
        failPackaged(ModImportFailureKind.IDENTITY, copy.fill(copy.verifyWrittenFailedFormat, packagedDetailText(e)), e)
    } catch (e: IllegalArgumentException) {
        failPackaged(ModImportFailureKind.IDENTITY, copy.fill(copy.verifyWrittenFailedFormat, packagedDetailText(e)), e)
    }
}

/** 三件套读回对账：manifest/容器解码 + packageId 对账 + provenance 空名单（check 抛 IllegalStateException）。 */
private fun readBackPackagedTrio(rt2Dir: File, packageId: String, sourcesBytes: ByteArray) {
    val manifestBack = File(rt2Dir, packageId + ModPackageInventory.MANIFEST_EXTENSION).readBytes()
    check(com.eratw.port.core.patch.ManifestCodec.decode(manifestBack).packageId == packageId) {
        "manifest 读回 packageId 不符：期望 $packageId"
    }
    val erbmBack = File(rt2Dir, packageId + ModPackageInventory.MODULE_EXTENSION).readBytes()
    check(com.eratw.port.core.mod.ErbModuleContainer.decode(erbmBack).packageId == packageId) {
        "容器读回 packageId 不符：期望 $packageId"
    }
    val sourcesBack = File(rt2Dir, packageId + ModPackageInventory.SOURCES_EXTENSION).readBytes()
    check(sourcesBack.contentEquals(sourcesBytes)) { "成品包 provenance 必须是空名单（无留档源）" }
}

// ---------------------------------------------------------------------------
// 相位四：代码产物收尾（commit 或反向清旧侧车，失败整包拒导）与资源面 swap
// ---------------------------------------------------------------------------

internal fun finalizePackagedSurfaces(
    rt2Dir: File,
    identity: PackagedValidatedIdentity,
    codePlan: ModImportCodeArtifacts.Staged?,
    assetPlan: ModImportAssets.Plan,
    copy: ModImportCopy,
): Pair<Int, List<String>> {
    if (codePlan != null) {
        try {
            codePlan.commit(ModCodeStore(rt2Dir), rt2Dir)
        } catch (e: ModCodeIntegrityException) {
            codePlan.discard()
            assetPlan.staged?.discard()
            failPackaged(ModImportFailureKind.IDENTITY, "成品包代码产物落盘被拒：${packagedDetailText(e)}", e)
        } catch (e: IOException) {
            codePlan.discard()
            assetPlan.staged?.discard()
            failPackaged(ModImportFailureKind.STORAGE, "成品包代码产物落盘失败：${packagedDetailText(e)}", e)
        }
    } else if (!ModCodeIdentity.clear(rt2Dir, identity.packageId)) {
        assetPlan.staged?.discard()
        failPackaged(
            ModImportFailureKind.STORAGE,
            "代码身份侧车删除失败：${ModCodeIdentity.fileName(identity.packageId)}",
            IOException("成品包重导无代码产物，旧身份侧车清不掉"),
        )
    }
    var resourceEntryCount = 0
    val warnings = ArrayList(assetPlan.warnings)
    val staged = assetPlan.staged
    if (staged != null) {
        try {
            staged.commit(rt2Dir)
            resourceEntryCount = staged.entryCount
        } catch (e: IOException) {
            staged.discard()
            warnings += copy.fill(copy.resourceCommitFailedFormat, packagedDetailText(e))
        }
    }
    return resourceEntryCount to warnings
}

// ---------------------------------------------------------------------------
// 市场库相位（pack.json 读+解析 → files/ 树折 zip → 两件套识别 → 导入事务）
// ---------------------------------------------------------------------------

/** 市场库相位的中间态：合同输入就绪 / 失败已定形（导入链未触达、零落盘、零台账）。 */
private sealed interface PackagedMarketPhase {
    /** 合同输入就绪（zip 已折叠成内存字节，条目名与 SAF 形态同形）。 */
    data class Ready(val picked: PickedModFile, val pair: ModFinC3PackagedImport.PackagedPair) : PackagedMarketPhase

    /** 失败已定形（STORAGE 面取证原话，由桥上屏）。 */
    data class Stopped(val failure: ModImportOutcome.Failed) : PackagedMarketPhase
}

/**
 * 市场已装 mod-pack 包装进游戏的完整相位链（[ModFinC3PackagedImport.importModPackLibrary]
 * 的实现体）：读 `pack.json`（verifier 校验，与源态路径同一条数据面）→ `files/` 树折 zip →
 * 两件套识别 → [ModFinC3PackagedImport.importPackagedModule]。失败面在此定形（zero-落盘、
 * 零台账），调用方只做 NotFound 前置与簿记。
 *
 * 语言口径 = [ModImportCopy.ZH]（与源态桥路径的控制器缺省文案表同源，事实行为一致）。
 */
internal fun packagedModPackOutcome(
    packDir: File,
    verifier: MarketVerifier,
    gameRoot: File,
    modRuntimeEnabled: Boolean,
): ModFinC3PackagedImport.LibraryOutcome = when (val phase = packagedMarketPhaseOf(packDir, verifier)) {
    is PackagedMarketPhase.Stopped -> ModFinC3PackagedImport.LibraryOutcome.Failed(phase.failure)
    is PackagedMarketPhase.Ready -> when (
        val outcome = ModFinC3PackagedImport.importPackagedModule(
            phase.picked,
            phase.pair,
            ModRuntimeAssembly.manifestDirFor(gameRoot),
            ModImportCopy.ZH,
            modRuntimeEnabled,
        )
    ) {
        is ModImportOutcome.Failed -> ModFinC3PackagedImport.LibraryOutcome.Failed(outcome)
        is ModImportOutcome.Imported -> ModFinC3PackagedImport.LibraryOutcome.Imported(outcome.detail)
    }
}

/** 市场相位链：pack.json 读+解析 → 折 zip → 两件套识别（失败面逐段定形，零落盘零台账）。 */
private fun packagedMarketPhaseOf(packDir: File, verifier: MarketVerifier): PackagedMarketPhase {
    val (manifest, manifestProblem) = packManifestParsed(packDir, verifier)
    if (manifest == null) {
        return packagedStop(manifestProblem ?: "已装包的 pack.json 读不出来或缺席")
    }
    return when (val zip = packagedZipOf(packDir, manifest)) {
        is PackagedZip.Broken -> packagedStop("包内容读不出来：${zip.detail}")
        // 两件套识别 + 就绪态装配（识别失败 = STORAGE 面原话，导入链未触达、零落盘零台账）。
        is PackagedZip.Bytes -> {
            val pair = ModFinC3PackagedImport.packagedPairOf(zip.bytes)
            if (pair == null) {
                packagedStop("包里没有成品包两件套（<包名>.manifest + <包名>.erbm）：${packDir.path}")
            } else {
                PackagedMarketPhase.Ready(
                    PickedModFile(packDir.name + MarketContract.ZIP_SUFFIX, zip.bytes, fromArchive = true),
                    pair,
                )
            }
        }
    }
}

/**
 * 折 zip 的两态：折叠成功（内存字节）/ 读盘失败（取证原话）。
 *
 * 单拆一型只为把 try/catch 挪出 [packagedMarketPhaseOf]——相位函数的 `return` 条数受
 * detekt `ReturnCount` 限（2 条），失败态走型别而不是第二个 return。
 */
private sealed interface PackagedZip {
    /** 折叠成功：条目名 = 剥 `files/` 前缀的包内相对路径（与 SAF 直选 zip 同形）。 */
    data class Bytes(val bytes: ByteArray) : PackagedZip

    /** 读盘失败：`packagedDetailText` 洗过的取证原话（上屏面）。 */
    data class Broken(val detail: String) : PackagedZip
}

/** `files/` 树折成内存 zip；读盘异常收敛成 [PackagedZip.Broken]（不打断相位链）。 */
private fun packagedZipOf(packDir: File, manifest: PackManifest): PackagedZip = try {
    PackagedZip.Bytes(ModFinC3PackagedImport.zipBytesOf(packDir, manifest.files))
} catch (e: IOException) {
    PackagedZip.Broken(packagedDetailText(e))
}

/** STORAGE 面失败定形单点（原话由调用方给，均为取证面事实）。 */
private fun packagedStop(problem: String): PackagedMarketPhase =
    PackagedMarketPhase.Stopped(ModImportOutcome.Failed(ModImportFailureKind.STORAGE, problem))

/** pack.json 的读+解析：返回（manifest 或 null，失败原话或 null）；原话是取证面。 */
private fun packManifestParsed(
    packDir: File,
    verifier: MarketVerifier,
): Pair<PackManifest?, String?> = try {
    val text = File(packDir, MarketContract.MANIFEST_FILE).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    if (text == null) {
        null to "已装目录里没有 pack.json：${packDir.path}"
    } else {
        when (val parsed = verifier.parsePackManifest(text)) {
            is MarketParseResult.Ok -> parsed.value to null
            is MarketParseResult.Invalid -> null to parsed.problem
        }
    }
} catch (e: IOException) {
    null to "已装包的 pack.json 读不出来：${e.message}"
}
