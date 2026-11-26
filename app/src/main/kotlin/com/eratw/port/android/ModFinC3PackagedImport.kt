package com.eratw.port.android

import com.eratw.port.android.market.MarketContract
import com.eratw.port.android.market.MarketVerifier
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.ManifestCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 成品包导入合同（ERAFIN-C3）：zip 自带编译产物两件套（`<pkg>.manifest` + `<pkg>.erbm`），
 * 跳过 ERB 编译链直接落 `mods/rt2` 三件套。相位函数在
 * `ModFinC3PackagedImportPhases.kt`、共享小工具在 `ModFinC3PackagedImportUtil.kt`
 * （单独成文件的理由：detekt `TooManyFunctions` 的 file 阈值，三文件分居各停在阈值内）。
 *
 * ## 形态边界（RECON 必答 1 钉死）
 *
 * - **成品包 = 编译产物态 zip**：zip **根级**同时存在同名主名的 `<pkg>.manifest` 与
 *   `<pkg>.erbm` 两个条目，且 zip 内没有 `.ERB`/`.ERH` 源（有源的 zip 走编译链，互斥判定
 *   由调用方 [ModImportController.import] 的 ⓪ 分流保证）。产物正源 = modgen 的
 *   DemoModuleBuild 先例（`DemoModuleBuild.kt:80-85`：`.erbm` 容器 + 点 manifest，manifest
 *   `artifact-sha256` 钉 `.erbm` 实际摘要；文件名惯例 = packageId + 扩展名）。
 * - **author pack = 源态 zip**（`pack-mod` 产物：ERB/ERH 源 + `eratw-pack.json` +
 *   `<pkg>.assets/`），走既有编译链（AuthorClosedLoopSmokeTest ↔ ModImportAuthorPackEndToEndTest
 *   的两段接力），与本合同无关。
 *
 * ## fail-closed 红线（契约 §范围 1）
 *
 * 1. **manifest 缺失拒**：识别判据本身要求两件套同主名在场；只有 `.erbm` 没有 manifest 的
 *    zip 不被识别成成品包，维持既有失败面（`emptyUnpackFailure` → `zipNoEntriesFormat`，
 *    ModImportController.kt 的空解包分支）。
 * 2. **摘要闸**：manifest 的 `artifact-sha256` 段必须非空且与 `.erbm` 实际字节摘要逐字相等
 *    （`ManifestCodec.kt:344-345` 的 `/artifact-sha256` 段；demo 先例必钉，实测
 *    `eratw.demo.manifest` 钉值与 `eratw.demo.erbm` 的 sha256sum 一致）。空串 = 未钉 = 拒：
 *    本合同没有编译链兜底校验，manifest 钉的摘要是「manifest 声明 ↔ .erbm 实际字节」的
 *    唯一锚，放行未钉摘要的包会让「篡改 .erbm 字节」的反向证明形同虚设。
 *    （`.erbm` 容器自身还有一层尾部 SHA-256 摘要闸，`ErbModuleContainer.kt:130-134`，
 *    那是容器完整性；本闸对的是「manifest 说的」与「容器是」两个事实的对账。）
 * 3. **packageId 自洽闸**：manifest 解码出的 packageId 与 `.erbm` 容器解码出的 packageId
 *    必须相等（两件套不是同一个包 = 拒），且过 [ModEnablement.isValidPackageId] 形状闸
 *    （落盘名防穿越，与停用标记拼名同一条闸；`ModEnablement.kt:31`）。身份恒取**内容解码**
 *    （manifest ↔ 容器互证），绝不从 zip 条目名反推（同 ModImportAssets 的重挂纪律）。
 * 4. **重导/版本语义与既有导入链同口径**：同 packageId = 原地替换（saveArtifact 的原子
 *    换名），versionChange 按「落盘前读盘上旧 manifest version」三态判（BU 口径，
 *    `ModImportVersion.kt:65/:83`）。
 *
 * ## 落盘缝（RECON 必答 5）
 *
 * 复用既有落盘路径：`mods/rt2` 目录取 [ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME]（与装配链
 * `manifestFilesIn` 同源；该函数收 rt2 下**全部** `.manifest`，不限 kojo- 前缀，所以成品包
 * 的非 `kojo-` packageId 照常进装配）；三件套写序照 [ModPackageInventory.memberNames] 的
 * provenance → manifest → 容器（写序纪律：provenance 先写，任一步崩溃时孤儿 GC 拿到的是
 * 「已声明源名单」；成品包无源，写**空名单** provenance）。读回校验照
 * `ModImportController.verifyWrittenPackage` 的口径（manifest/容器解码 + packageId 对账；
 * 成品包无内容指纹件，不比指纹）。孤儿 GC：`hasModule && hasManifest` = 完整包保留
 * （`ModPackageInventory.kt:452-468`），空名单 provenance 不构成删除依据（源失联判据已撤销）。
 *
 * ## 附加面
 *
 * - **资源面**：zip 内 `<pkg>.assets/` 条目照源链同口径重挂（[ModImportAssets.stageSafely]，
 *   限额 / 名字 / 跨来源冲突全同源）。
 * - **代码产物面**：zip 内 `code.mod` 条目照源链 ④‴ 同口径收下（[ModImportCodeArtifacts.stage]，
 *   fail-closed on presence；运行期身份 = 成品包 manifest 的 packageId——成品包无编译产物
 *   `kojo-` 派生身份，`eratw-pack.json` 不消费，内层 manifest 自述身份由
 *   [ModImportCodeArtifacts.stage] 的 requireInnerArtifact 独立复核）；无条目的重导反向清
 *   `<pkg>.codeid` 身份侧车（源链 finalizeCodeArtifact 同口径）。
 * - 其余条目（readme / CSV / 图片等）跳过不报错——与源链对非源条目的既有处置同口径。
 * - 旁挂媒体面与随包模板面本波不接（成品包带这两类条目时跳过；源态包才走
 *   `ModImportMediaAssets` / chara 分流），登记交付报告。
 *
 * ## 语言纪律
 *
 * 与 [ModImportCopy] 硬规矩同源：本对象不做语言判定，玩家可见失败文案一律按调用方传入的
 * [copy] 拼（失败原话是取证面，原样拼进模板，不进文案表）。
 *
 * ## 失败通道
 *
 * 相位函数照 `ModImportController.compileToModule` 的模式：内部抛
 * [PackagedImportException]（单点 throw = [failPackaged]），主入口一个 catch 收口折
 * [ModImportOutcome.Failed]——抛异常时**没有任何产物被写到正式名**（校验全在前、暂存面
 * discard 在后，半套不留）。
 */
internal object ModFinC3PackagedImport {

    /**
     * zip 里找成品包两件套：根级同名主名的 `<pkg>.manifest` + `<pkg>.erbm`。
     *
     * 返回 null = 不是成品包形态（调用方维持既有路径）。命中时返回该组两件套的字节与主名；
     * 多组两件套取**主名字典序第一**的一组（确定性，不依赖条目出现顺序；多组是作者的打包
     * 错误，v1 取第一组——与纯资源包「只认第一个候选」的既有口径同形）。
     *
     * 扫描走 [ModZip.entriesWhere]（同趟过 [ModZipGuard] 条目数/重名/洪泛闸；zip 在
     * [ModImportController.import] 的 ⓪ 已过一次闸，闸幂等重入无副作用）。
     */
    fun packagedPairOf(zipBytes: ByteArray): PackagedPair? {
        val entries = ModZip.entriesWhere(zipBytes) { name ->
            name.indexOf('/') < 0 &&
                (packagedHasFoldedExtension(name, PACKAGED_MANIFEST_ENTRY_SUFFIX) ||
                    packagedHasFoldedExtension(name, PACKAGED_MODULE_ENTRY_SUFFIX))
        }
        val manifests = HashMap<String, ByteArray>()
        val modules = HashMap<String, ByteArray>()
        for (entry in entries) {
            val stem = entry.name.substring(0, entry.name.lastIndexOf('.'))
            if (packagedHasFoldedExtension(entry.name, PACKAGED_MANIFEST_ENTRY_SUFFIX)) {
                manifests[stem] = entry.bytes
            } else {
                modules[stem] = entry.bytes
            }
        }
        for (stem in manifests.keys.sorted()) {
            modules[stem]?.let { return PackagedPair(stem, manifests.getValue(stem), it) }
        }
        return null
    }

    /**
     * 市场桥的输入形态化：把 mod-pack 已装目录的 `files/` 树折成内存 zip 字节
     * （条目名 = 剥 `files/` 前缀的相对路径，与 SAF 直选 zip 的条目形态逐位同形——成品包
     * 合同因此只写一份实现）。条目读不到 / 读失败抛 [IOException]（调用方折 STORAGE）；
     * 清单锚的条目缺 = 已装树与清单不符，绝不静默丢条目。zip 是纯内存中间形态，不落盘。
     */
    fun zipBytesOf(packDir: File, entries: List<com.eratw.port.android.market.PackManifestFile>): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            for (entry in entries) {
                val bytes = File(packDir, entry.path).readBytes()
                zip.putNextEntry(ZipEntry(entry.path.removePrefix(MarketContract.CONTENT_DIR + "/")))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return sink.toByteArray()
    }

    /**
     * 成品包导入事务：校验（身份自洽 + 摘要对账）→ 三件套落盘 → 读回校验 → 资源/代码产物面
     * 收尾。调用前提 = [pair] 已由 [packagedPairOf] 找到（zip 确实是成品包形态）。
     *
     * 失败折 [ModImportOutcome.Failed]（不抛）；任何失败都不在 `mods/rt2` 留半套产物
     * （资源/代码暂存 discard，三件套写序下缺任一件 = 半成品，交给孤儿 GC 判据兜底）。
     */
    fun importPackagedModule(
        zip: PickedModFile,
        pair: PackagedPair,
        rt2Dir: File,
        copy: ModImportCopy,
        modRuntimeEnabled: Boolean,
    ): ModImportOutcome = try {
        installPackaged(zip, pair, rt2Dir, copy, modRuntimeEnabled)
    } catch (e: PackagedImportException) {
        failedPackaged(e.kind, e.message ?: e.kind.name, e)
    }

    /**
     * 市场已装 mod-pack 包装进游戏（成品包合同的市场库入口）：读 `pack.json`（verifier 校验，
     * 与源态路径同一条数据面）→ `files/` 树折 zip → 两件套识别 → [importPackagedModule]。
     *
     * 两件套缺失 / 清单缺席或解析拒 / 树读失败都折 [LibraryOutcome.Failed]
     * （[ModImportFailureKind.STORAGE]，problem 是取证面原话，由桥上屏）；此刻导入事务
     * 未触达、`mods/` 零落盘、台账零写入。调用前提 = [packDir] 已存在（NotFound 语义归桥）。
     */
    fun importModPackLibrary(
        packDir: File,
        verifier: MarketVerifier,
        gameRoot: File,
        modRuntimeEnabled: Boolean,
    ): LibraryOutcome = packagedModPackOutcome(packDir, verifier, gameRoot, modRuntimeEnabled)

    /** 市场库入口的终态：导入成功（含详情）或失败原话（STORAGE 面取证原话 / 合同失败面）。 */
    sealed interface LibraryOutcome {
        /** 成品包合同导入成功：[detail] 交桥走簿记。 */
        data class Imported(val detail: ImportedPatchDetail) : LibraryOutcome

        /** 失败：[failure] 与源态桥的 ImportFailed 同一上屏通道（失败原话原文不截断）。 */
        data class Failed(val failure: ModImportOutcome.Failed) : LibraryOutcome
    }

    /** 一组成品包两件套（同一根级主名的 manifest + 容器字节；[stem] = 条目主名）。 */
    internal class PackagedPair(val stem: String, val manifestBytes: ByteArray, val erbmBytes: ByteArray)
}

// ---------------------------------------------------------------------------
// 相位一：身份闸（全部在任何落盘之前，fail-closed）与主事务（各相位按序收口）
// ---------------------------------------------------------------------------

/** 身份闸：manifest/容器解码互证 + packageId 形状闸 + artifact-sha256 摘要对账（首个问题即拒）。 */
private fun validatedPackagedIdentity(pair: ModFinC3PackagedImport.PackagedPair): PackagedValidatedIdentity {
    val descriptor = try {
        ManifestCodec.decode(pair.manifestBytes)
    } catch (e: IllegalArgumentException) {
        failPackaged(ModImportFailureKind.IDENTITY, "成品包 manifest 解不开：${packagedDetailText(e)}", e)
    }
    val module = try {
        ErbModuleContainer.decode(pair.erbmBytes)
    } catch (e: IllegalArgumentException) {
        failPackaged(ModImportFailureKind.IDENTITY, "成品包容器 .erbm 解不开：${packagedDetailText(e)}", e)
    } catch (e: IOException) {
        failPackaged(ModImportFailureKind.IDENTITY, "成品包容器 .erbm 解不开：${packagedDetailText(e)}", e)
    }
    if (descriptor.packageId != module.packageId) {
        failPackaged(
            ModImportFailureKind.IDENTITY,
            "成品包身份自洽失败：manifest 写的是 ${descriptor.packageId}，" +
                "容器是 ${module.packageId}（不是同一个包）",
            IllegalStateException("成品包 manifest 与 .erbm 的 packageId 不一致"),
        )
    }
    if (!ModEnablement.isValidPackageId(descriptor.packageId)) {
        failPackaged(
            ModImportFailureKind.IDENTITY,
            "成品包包名不合法：${descriptor.packageId}（只允许字母数字与 . _ -，且不得以符号开头）",
            IllegalStateException("成品包 packageId 未过形状闸"),
        )
    }
    val pinned = descriptor.artifactSha256
    if (pinned.isEmpty()) {
        failPackaged(
            ModImportFailureKind.IDENTITY,
            "成品包 manifest 没有钉 .erbm 摘要（artifact-sha256 段为空）：" +
                "本合同没有编译链兜底，未钉摘要的包拒收",
            IllegalStateException("成品包 artifact-sha256 缺失"),
        )
    }
    val actual = packagedSha256Hex(pair.erbmBytes)
    if (pinned != actual) {
        failPackaged(
            ModImportFailureKind.IDENTITY,
            "成品包 .erbm 摘要与 manifest 钉的不符（包内容与说明不是同一份东西）：" +
                "期望 $pinned，实算 $actual",
            IllegalStateException("成品包 artifact-sha256 不符"),
        )
    }
    return PackagedValidatedIdentity(descriptor, module)
}

/**
 * 主事务：身份闸 → 版本读数 → 附加面暂存 → 三件套落盘+读回 → 收尾（只有成功路径写正式名）。
 *
 * 暂存面建出之后的每个失败点都带**交叉回收**再上抛（源态链同口径：代码暂存失败回收
 * 资源/媒体面 `ModImportController.kt:748-754`、落盘/读回失败回收全部已建暂存面
 * `ModImportController.kt:788-800`）。缺了这两道，失败包会在 `mods/rt2` 里泄漏
 * `<pkg>.assets.tmp-<nano>` 资源暂存目录、在系统临时目录泄漏代码产物暂存文件，只能等
 * 24h 残留 GC 的时间窗兜底（`ModPackageInventory.RESIDUE_GC_MIN_AGE_MILLIS` 的保守窗，
 * 窗内一律保留）。
 */
private fun installPackaged(
    zip: PickedModFile,
    pair: ModFinC3PackagedImport.PackagedPair,
    rt2Dir: File,
    copy: ModImportCopy,
    modRuntimeEnabled: Boolean,
): ModImportOutcome {
    val identity = validatedPackagedIdentity(pair)
    val packageId = identity.packageId
    val versionChange = versionChangeOf(
        installedManifestOf(rt2Dir, packageId)?.version,
        identity.descriptor.version,
    )
    val assetPlan = stagedPackagedAssets(zip, packageId, rt2Dir, copy)
    val codePlan = try {
        stagedPackagedCode(zip, packageId)
    } catch (e: PackagedImportException) {
        assetPlan.staged?.discard()
        throw e
    }
    try {
        persistPackagedTrio(rt2Dir, identity, pair, ModPackageInventory.sourcesBytes(emptyList()), copy)
    } catch (e: PackagedImportException) {
        codePlan?.discard()
        assetPlan.staged?.discard()
        throw e
    }
    val (resourceEntryCount, warnings) = finalizePackagedSurfaces(rt2Dir, identity, codePlan, assetPlan, copy)
    return ModImportOutcome.Imported(
        ImportedPatchDetail(
            patchFileName = packageId + ModPackageInventory.MODULE_EXTENSION,
            functionCount = identity.module.functions.size,
            instructionCount = identity.module.program.instructionCount,
            entryFunction = "",
            sourceFileNames = emptyList(),
            parseWarnings = warnings,
            modRuntimeOffHint = if (modRuntimeEnabled) null else copy.modRuntimeOffHint,
            resourceEntryCount = resourceEntryCount,
            resourceWarnings = assetPlan.warnings.toList(),
            versionChange = versionChange,
            packagedModuleOnly = true,
        ),
    )
}
