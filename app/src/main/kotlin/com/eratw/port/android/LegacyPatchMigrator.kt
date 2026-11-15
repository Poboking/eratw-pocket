package com.eratw.port.android

import com.eratw.port.core.loader.ModPatchLoader
import com.eratw.port.platform.storage.ModStore
import java.io.File

/**
 * 存量 legacy `.patch` 的升级迁移器（L5 / CONTRACT-RT2-M2LEGACY：补 M2-4「进口即转码」漏掉的
 * 存量升级路径）。
 *
 * ## 根因层与落点（装载/编译相位 + app 导入壳，不是 UI 表象）
 *
 * M2-4 起 [ModImportController.import] 只把**本次导入源**转成 `mods/rt2/` 三件套；老用户盘上
 * 既有 [ModImportController.UNION_PATCH_NAME]（历史并集 overlay）与 per-import `.patch` 仍走旧
 * overlay 路。原删除相位在落盘成功后**直接删掉**这些旧 `.patch`，却没把它们的内容转成新管线
 * 产物 ⇒ 玩家若不重新选源导入，旧补丁效果静默消失。留档源 `.ERB/.ERH` 其实仍在
 * [ModStore.modsDir]（真相源），所以本迁移器在**删除相位之前**用盘上留档源重走既有编译→
 * 转码→落盘链，产出与本次导入同形的三件套；成功才由控制器删旧档。改动落点 = ① 所指层本身
 * （导入链的编译/转码出口 + 删除相位），不是 UI 提示、不是给旧 `.patch` 开装载白名单、不是把
 * 删除改成「不删」——后三者都会让两条运行期管线长期并存（AGENTS §3.1 升级规则）。
 *
 * ## 复用纪律（裁定 5，禁止第二份编译/转码实现）
 *
 * 本类**不含任何编译、转码、编码、落盘或读回实现**：这些全部经 [writePackage] 回调复用
 * [ModImportController.import] 整条链（其内含 `PatchCompiler` / `PatchImportTranscoder` /
 * `ErbModuleContainer.encode` / `ManifestCodec.encode` / `saveArtifact` / `verifyWrittenPackage`）。
 * 回调把留档源还原成与本次导入同形的 `PickedModFile`，用 `legacyMigrationEnabled = false`
 * 二次进入 `import`——同一份编译→转码→落盘→读回代码，零复制。本类只负责枚举、决策、汇总原因。
 *
 * ## fail-closed（裁定 2/3）
 *
 * - 无存量 `.patch` ⇒ [MigrationResult.NoLegacy]，不迁移、不删、不告警，导入逐字节零变化；
 * - 无留档源 / 读源失败 / 编译·转码·写盘·读回失败 ⇒ [MigrationResult.Kept]，**旧 `.patch`
 *   一个都不删**、逐条警告点名原因，本次导入主流程照常成功（不半删、不砖化）；
 * - 只有迁移成功（[MigrationWriteOutcome.Ok]，内含三件套读回校验通过）⇒
 *   [MigrationResult.Migrated]，把识别到的旧 `.patch` 交回控制器删除，且只在这一步之后删。
 * - **绝不猜源、绝不把旧 `.patch` 当源塞进新管线**：输入只认 [ModStore.hasErbExtension] /
 *   [ModStore.hasErhExtension] 命中的留档源；`.patch` 内容一个字节都不读。
 *
 * ## 诊断面文案口径（不新增三语文案，AGENTS §6.1 表外面）
 *
 * 失败告警是「诊断/取证面」原话，与既有 `symbolBuild.warnings` / `renameWarnings` 同口径：
 * 并进 `ImportedPatchDetail.parseWarnings`、由 `warningLineFormat` 上屏，不进 [ModImportCopy]
 * 文案表（KDoc「留在表外的共用面」明写带原文的诊断告警保持原样）。读源失败复用既有孤儿格式串
 * [ModImportCopy.readArchivedFailedFormat]（R7 并集时代读留档源的遗留键，本波正好归位），
 * 其余原因直接取复用链 `Failed.message`（已是 `copy.fill` 既有格式串 / 异常原话的产物）。
 *
 * ## TODO(verify)
 *
 * `.rt2` 转码链是移植侧扩展（原版 Emuera 无 Mod 代码容器可比），**无 C# 反编译锚**；语义权威 =
 * `CONTRACT-RT2-M2-4.md` / [com.eratw.port.core.mod.PatchImportTranscoder] KDoc /
 * `LinkableModule` 契约。真机开态升级路径（迁移包经 `ModChainAssembly.assemble` 单向配对
 * 消费、模块函数/事件成员真执行）仍 📱 挂账，本波不假造完成。
 */
internal object LegacyPatchMigrator {

    /** 留档源扩展名（旧 `.patch` 主名比对的两档，与 ModStore 源判定同源）。 */
    private val SOURCE_EXTENSIONS: List<String> =
        listOf(ModStore.ERB_EXTENSION, ModStore.ERH_EXTENSION)

    /**
     * 识别存量 `.patch` 并用盘上留档源迁移。
     *
     * @param modsDir mods 目录（留档源与旧 `.patch` 都在这里）。
     * @param importDiskNames 本次导入落盘的源名（旧 `.patch` 的 `hitImported` 判定用）。
     * @param copy 当前语言文案表（读源失败复用 `readArchivedFailedFormat`）。
     * @param writePackage 复用编译/转码/落盘链的回调（生产 = 二次进入 `import`）。
     */
    fun migrate(
        modsDir: File,
        importDiskNames: Set<String>,
        copy: ModImportCopy,
        writePackage: (List<LegacySource>) -> MigrationWriteOutcome,
    ): MigrationResult {
        val allFiles = modsDir.listFiles().orEmpty().filter { it.isFile }

        // 1) 存量 `.patch` 识别 = 原删除相位命中口径（并集产物 / 命中本次导入源 / 命中留档源）。
        // 玩家手放的其它 .patch 一概不算存量（装载侧 fail-closed 裁决），保持不删。
        val archivedNames = allFiles
            .filter { isSourceName(it.name) }
            .mapTo(HashSet()) { it.name }
        val stalePatches = allFiles.filter { file ->
            if (!ModPatchLoader.hasPatchExtension(file.name)) return@filter false
            if (file.name == ModImportController.UNION_PATCH_NAME) return@filter true
            val stem = file.name.removeSuffix(ModPatchLoader.PATCH_EXTENSION)
            SOURCE_EXTENSIONS.any { (stem + it) in importDiskNames } ||
                SOURCE_EXTENSIONS.any { (stem + it) in archivedNames }
        }
        if (stalePatches.isEmpty()) return MigrationResult.NoLegacy

        // 2) 留档源枚举（真相源；绝不读 `.patch` 内容）。按 compareNames 与写盘 / packageId 同序。
        val sourceFiles = allFiles
            .filter { isSourceName(it.name) }
            .sortedWith { a, b -> ModStore.compareNames(a.name, b.name) }
        if (sourceFiles.isEmpty()) {
            return MigrationResult.Kept(
                stalePatches,
                listOf("存量 .patch 未迁移：mods 里找不到留档 .ERB/.ERH 源（旧补丁原样保留，不猜源）"),
            )
        }

        // 3) 读源字节（IO 失败 = 不迁移、保留旧档、点名文件；文本解码交回导入链同一口）。
        val sources = ArrayList<LegacySource>(sourceFiles.size)
        for (file in sourceFiles) {
            val bytes = runCatching { file.readBytes() }.getOrElse { e ->
                return MigrationResult.Kept(
                    stalePatches,
                    listOf(
                        "存量 .patch 未迁移：" + copy.fill(
                            copy.readArchivedFailedFormat,
                            file.name,
                            e.message ?: e::class.simpleName ?: "null",
                        ),
                    ),
                )
            }
            sources += LegacySource(file.name, bytes)
        }

        // 4) 复用编译/转码/落盘链（回调内部产出独立 packageId 的同形三件套并读回校验）。
        val outcome = runCatching { writePackage(sources) }
            .getOrElse { e -> MigrationWriteOutcome.Err(e.message ?: e::class.simpleName ?: "null") }
        return when (outcome) {
            is MigrationWriteOutcome.Ok -> MigrationResult.Migrated(outcome.packageId, stalePatches)
            is MigrationWriteOutcome.Err -> MigrationResult.Kept(
                stalePatches,
                listOf("存量 .patch 未迁移（旧补丁原样保留，本次导入照常成功）：${outcome.reason}"),
            )
        }
    }

    private fun isSourceName(name: String): Boolean =
        ModStore.hasErbExtension(name) || ModStore.hasErhExtension(name)
}

/** 盘上留档源（迁移的编译输入）：主名 + 原始字节，文本解码交回导入链的 `ModSourceCodec` 同一口。 */
internal data class LegacySource(val diskName: String, val bytes: ByteArray)

/** 复用编译/转码/落盘链的出口：`Ok` = 三件套已落盘且读回校验通过；`Err` = 点名原因。 */
internal sealed interface MigrationWriteOutcome {
    data class Ok(val packageId: String) : MigrationWriteOutcome
    data class Err(val reason: String) : MigrationWriteOutcome
}

/** 迁移结果：控制器据它决定删不删旧档、要不要上警告。 */
internal sealed interface MigrationResult {
    /** 盘上无存量 `.patch`：零变化，不迁移、不删、不告警。 */
    data object NoLegacy : MigrationResult

    /** 迁移成功：三件套读回校验通过，[stalePatches] 可删且只在这一步之后删。 */
    data class Migrated(val packageId: String, val stalePatches: List<File>) : MigrationResult

    /** 迁移失败：[stalePatches] 原样保留，[warnings] 逐条点名原因。 */
    data class Kept(val stalePatches: List<File>, val warnings: List<String>) : MigrationResult
}
