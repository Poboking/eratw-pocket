package com.eratw.port.android

import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.platform.storage.ModStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 一个已导入 ERB 数据包（`mods/rt2/<packageId>.sources` 有档）的重编快照。
 *
 * [upToDate] 是 make 式判据（本文件 [ModRecompiler.isUpToDate]）：源文件名以 `.sources`
 * 为准，内容判据以 `<packageId>.sources.sha256`（[ModSourceFingerprint]）的摘要集合为准
 * —— 侧车在场时「逐条内容摘要相等 + 两件产物在场非空」才算 upToDate；侧车缺失/坏格式
 * （老包迁移期）退回「产物 mtime 不早于每一份源」的旧判据。`.sources` 线格式仍是仅文件名
 * 一行一条（不塞哈希），内容指纹走独立侧车，因此「内容变了但 mtime 没变」不再漏判。
 */
data class RecompileTarget(
    /** 包标识（`kojo-` 前缀，导入时按源文件名集合算定，重编不许变）。 */
    val packageId: String,
    /** `.sources` 名单里的源文件数。 */
    val sourceCount: Int,
    /** 现役产物字节数（`.erbm` + `.manifest`；缺一件按 0 计）。 */
    val artifactBytes: Long,
    /** 源与产物一致、无需重编。 */
    val upToDate: Boolean,
)

/**
 * 重编结果三态（设置页一按钮三提示的唯一来源；失败路径一律「旧件原样保留」）。
 *
 * [Skipped.reason] 是取证面文案（非 UI 文案表条目）：只有
 * [ModRecompiler.SKIP_REASON_UP_TO_DATE] 是「源没变」的语义常量，其余是诊断串。
 */
sealed interface RecompileResult {
    /** 重编并原子替换成功；[packageId] 与重编前一致。 */
    data class Updated(
        val packageId: String,
        val sourceCount: Int,
        val artifactBytes: Long,
    ) : RecompileResult

    /** 没动手：源没变，或 `.sources` 名单不可解析（半成品包）。 */
    data class Skipped(val packageId: String, val reason: String) : RecompileResult

    /** 动手了但失败；磁盘上的 `.erbm` / `.manifest` 保持重编前状态。 */
    data class Failed(val packageId: String, val message: String) : RecompileResult
}

/**
 * 已导入 ERB 数据包的「重新编译」链（设置页 Mod 管理 → 详情 → 重新编译）。
 *
 * 复用导入链同一条编译/转码/编码实现（[ModImportController.compileToModule]），不另写
 * 编译器调用：重编与导入产出的模块、manifest 由同一套零基线编译器 + RT2 转码器产生，
 * 差别只在落盘口径 —— 导入是整包新装（走 `ModImportController.import` 的 `.sources` →
 * `.sources.sha256` → `.manifest` → `.erbm` 写序），重编是**原位替换既有四件套中的三件**
 * 且 packageId 不变：`.sources` 名单是重编的输入，不改；`.erbm` / `.manifest` /
 * `.sources.sha256` 先落同目录 tmp（fsync），产物解码自检通过后
 * `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` 逐个换名，三件都换成功才返回
 * [RecompileResult.Updated]；任何一步失败就把已换掉的件用旧字节写回（回滚也是
 * tmp + 原子搬，不留半截文件），所以失败时旧件仍在。指纹侧车与产物同一批换：只换产物
 * 不换指纹会让下一次判据看到「旧内容指纹 + 新产物」，方向刚好是「多余的再重编一次」，
 * 不会误判 upToDate。
 *
 * 进程在多次 move 之间被杀仍可能留下「新 `.erbm` + 旧 `.manifest`」（单文件原子、
 * 跨文件无事务）；此时 `.manifest` 早于新 `.erbm`，upToDate 判据仍为真 —— 记
 * TODO(verify)，彻底解需线格式升级（版本代数）或启动期一致性校验，由组长接线。
 */
object ModRecompiler {
    /** [RecompileResult.Skipped.reason] 中「源未变更」的语义常量（UI 据此选提示文案）。 */
    const val SKIP_REASON_UP_TO_DATE: String = "up-to-date"

    /** `.sources` 缺失/空名单时的诊断串（半成品包；不参与 UI 文案判定）。 */
    private const val SKIP_REASON_MISSING_SOURCES: String = "缺少可解析的 .sources 源名单"

    /** 扫 [gameRoot]/mods/rt2 下所有 `kojo-*` 数据包的重编快照（坏 `.sources` 不进列表）。 */
    fun targets(gameRoot: File): List<RecompileTarget> = targetsOf(ModStore.modsDirFor(gameRoot))

    /**
     * 对 [packageId] 重编一次：生产路径入口（设置页用；base 上下文 = 设备上已装载的
     * 零基线程序，见 [ProductionBaseCompileContextSource]）。[copy] 只影响编译失败
     * 证据串的语言，与导入链同一套 [ModImportCopy]。
     */
    fun recompile(
        gameRoot: File,
        packageId: String,
        copy: ModImportCopy = ModImportCopy.ZH,
    ): RecompileResult {
        val modsDir = ModStore.modsDirFor(gameRoot)
        val controller = ModImportController(
            modStore = ModStore(modsDir),
            baseSource = ProductionBaseCompileContextSource,
            gameRoot = gameRoot,
        )
        return recompileWith(controller, modsDir, packageId, copy)
    }

    /** [targets] 的测试缝：mods 根可注入（生产恒 `gameRoot/mods`）。 */
    internal fun targetsOf(modsDir: File): List<RecompileTarget> {
        val rt2Dir = File(modsDir, ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME)
        val entries = if (rt2Dir.isDirectory) rt2Dir.listFiles().orEmpty() else emptyArray()
        return entries
            .asSequence()
            .filter { it.isFile && it.name.endsWith(ModPackageInventory.SOURCES_EXTENSION) }
            .map { it.name.removeSuffix(ModPackageInventory.SOURCES_EXTENSION) }
            .filter { it.startsWith(ModPackageInventory.PACKAGE_ID_PREFIX) }
            .sorted()
            .mapNotNull { packageId -> targetOf(modsDir, rt2Dir, packageId) }
            .toList()
    }

    /** [recompile] 的测试缝：编译上下文与 mods 根都可注入（分级用例不碰设备产物）。 */
    internal fun recompileWith(
        controller: ModImportController,
        modsDir: File,
        packageId: String,
        copy: ModImportCopy = ModImportCopy.ZH,
    ): RecompileResult {
        val rt2Dir = File(modsDir, ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME)
        val sourceNames = readSourceNames(rt2Dir, packageId).orEmpty()
        return when {
            sourceNames.isEmpty() ->
                RecompileResult.Skipped(packageId, SKIP_REASON_MISSING_SOURCES)
            isUpToDate(modsDir, rt2Dir, packageId, sourceNames) ->
                RecompileResult.Skipped(packageId, SKIP_REASON_UP_TO_DATE)
            else -> rebuild(controller, modsDir, packageId, sourceNames, copy)
        }
    }

    /**
     * make 式「源没变」判据。两段口径，按指纹侧车是否存在分流：
     *
     * - **有指纹**（本波之后导入/重编/迁移过的包）：`.erbm` 与 `.manifest` 都在场非空，
     *   且 `<packageId>.sources.sha256` 的摘要集合与盘上现源**逐条相等**
     *   （[ModSourceFingerprint]）。这是内容判据：源内容变了哪怕 mtime 更旧也判「要重编」；
     * - **无指纹**（老包迁移期回退）：`.erbm` 与 `.manifest` 都在场非空，且两件产物的
     *   mtime 都不早于任何一份源文件；源文件逐个在场且非空。侧车缺失/坏格式一律走这条，
     *   老包不因新增侧车被判坏。
     *
     * 产物缺一件 = 半成品包（导入中断/GC 前状态），一律判「需要重编」，让重编能把它
     * 补全，而不是当 upToDate 跳过。
     */
    internal fun isUpToDate(
        modsDir: File,
        rt2Dir: File,
        packageId: String,
        sourceNames: List<String>,
    ): Boolean {
        val erbm = File(rt2Dir, packageId + ModPackageInventory.MODULE_EXTENSION)
        val manifest = File(rt2Dir, packageId + ModPackageInventory.MANIFEST_EXTENSION)
        val artifactsReady = erbm.isFile && erbm.length() > 0L &&
            manifest.isFile && manifest.length() > 0L
        val stored = ModSourceFingerprint.readSidecar(rt2Dir, packageId)
        return artifactsReady && if (stored == null) {
            // 老包 mtime 回退判据：每份源都在场非空、且不晚于产物。
            val artifactTime = minOf(erbm.lastModified(), manifest.lastModified())
            sourceNames.all { name ->
                val source = File(modsDir, name)
                source.isFile && source.length() > 0L && source.lastModified() <= artifactTime
            }
        } else {
            ModSourceFingerprint.currentIn(modsDir, sourceNames) == stored
        }
    }
}

/** 源文件读取结果：缺哪一件要能点名上屏（失败提示里带文件名）。 */
private sealed interface SourceRead {
    class Ok(val files: List<PickedModFile>) : SourceRead
    class Missing(val name: String) : SourceRead
}

/** 编译结果：只分「产出成功」与「可上屏的失败消息」，调用方不必知道异常种类。 */
private sealed interface BuildOutcome {
    class Built(val artifacts: CompiledModuleArtifacts) : BuildOutcome
    class Failed(val message: String) : BuildOutcome
}

/** 重编主体：读源 → 编译 → 校验包标识 → 原子替换（全部失败路径都不碰正式名）。 */
private fun rebuild(
    controller: ModImportController,
    modsDir: File,
    packageId: String,
    sourceNames: List<String>,
    copy: ModImportCopy,
): RecompileResult {
    val rt2Dir = File(modsDir, ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME)
    return when (val read = readSources(modsDir, sourceNames)) {
        is SourceRead.Missing -> RecompileResult.Failed(packageId, "源文件缺失或为空：${read.name}")
        is SourceRead.Ok -> when (val built = compileModule(controller, read.files, copy)) {
            is BuildOutcome.Failed -> RecompileResult.Failed(packageId, built.message)
            is BuildOutcome.Built -> finishReplace(rt2Dir, packageId, built.artifacts, read.files)
        }
    }
}

private fun readSources(modsDir: File, sourceNames: List<String>): SourceRead {
    val picked = ArrayList<PickedModFile>(sourceNames.size)
    for (name in sourceNames) {
        val source = File(modsDir, name)
        val bytes = if (source.isFile && source.length() > 0L) {
            runCatching { source.readBytes() }.getOrNull()
        } else {
            null
        }
        if (bytes == null || bytes.isEmpty()) return SourceRead.Missing(name)
        picked += PickedModFile(name, bytes)
    }
    return SourceRead.Ok(picked)
}

private fun compileModule(
    controller: ModImportController,
    files: List<PickedModFile>,
    copy: ModImportCopy,
): BuildOutcome = try {
    BuildOutcome.Built(controller.compileToModule(files, copy))
} catch (e: ModCompileException) {
    BuildOutcome.Failed(e.message)
}

private fun finishReplace(
    rt2Dir: File,
    packageId: String,
    built: CompiledModuleArtifacts,
    files: List<PickedModFile>,
): RecompileResult {
    if (built.packageId != packageId) {
        return RecompileResult.Failed(
            packageId,
            "源集合与包标识不符（重编产物是 ${built.packageId}），已保留旧件；请重新导入",
        )
    }
    // 指纹按「本次实际参与编译的源字节」算（[PickedModFile.bytes]），不重读盘上文件：
    // 重编与判据之间源文件再被动过也不影响「这批产物对应该批源」这条配对关系。
    val fingerprintBytes = ModSourceFingerprint.bytesFor(files)
    val failure = runCatching { replaceArtifacts(rt2Dir, packageId, built, fingerprintBytes) }.exceptionOrNull()
    return if (failure == null) {
        RecompileResult.Updated(packageId, files.size, artifactBytes(rt2Dir, packageId))
    } else {
        RecompileResult.Failed(packageId, "产物替换失败：${failure.message ?: failure::class.simpleName ?: "null"}")
    }
}

private fun targetOf(modsDir: File, rt2Dir: File, packageId: String): RecompileTarget? {
    val sourceNames = readSourceNames(rt2Dir, packageId).orEmpty()
    if (sourceNames.isEmpty()) return null
    return RecompileTarget(
        packageId = packageId,
        sourceCount = sourceNames.size,
        artifactBytes = artifactBytes(rt2Dir, packageId),
        upToDate = ModRecompiler.isUpToDate(modsDir, rt2Dir, packageId, sourceNames),
    )
}

private fun readSourceNames(rt2Dir: File, packageId: String): List<String>? =
    runCatching { ModPackageInventory.storedSourceNames(rt2Dir, packageId) }.getOrNull()

private fun artifactBytes(rt2Dir: File, packageId: String): Long {
    val erbm = File(rt2Dir, packageId + ModPackageInventory.MODULE_EXTENSION)
    val manifest = File(rt2Dir, packageId + ModPackageInventory.MANIFEST_EXTENSION)
    return (if (erbm.isFile) erbm.length() else 0L) + (if (manifest.isFile) manifest.length() else 0L)
}

/**
 * 三件产物原位原子替换（先 tmp + fsync，再 ATOMIC_MOVE）：容器、manifest、内容指纹侧车。
 *
 * 落 tmp 后先在内存里解码自检（[ErbModuleContainer.decode] / [ManifestCodec.decode]）：
 * 编码器真出了坏字节也不许进正式名。三件里任一换名失败时按旧字节回滚，保证「失败保留
 * 旧件」这条对外承诺在跨文件层面也成立。指纹侧车与产物同批换：只换产物不换指纹会让下次
 * 判据看到「旧指纹 + 新产物」，方向是多余的再重编一次，不会误判 upToDate。
 */
private fun replaceArtifacts(
    rt2Dir: File,
    packageId: String,
    built: CompiledModuleArtifacts,
    fingerprintBytes: ByteArray,
) {
    ErbModuleContainer.decode(built.erbmBytes)
    ManifestCodec.decode(built.manifestBytes)

    val erbmName = packageId + ModPackageInventory.MODULE_EXTENSION
    val manifestName = packageId + ModPackageInventory.MANIFEST_EXTENSION
    val fingerprintName = packageId + ModPackageInventory.FINGERPRINT_EXTENSION
    val artifacts = listOf(
        Triple(erbmName, built.erbmBytes, File(rt2Dir, erbmName)),
        Triple(manifestName, built.manifestBytes, File(rt2Dir, manifestName)),
        Triple(fingerprintName, fingerprintBytes, File(rt2Dir, fingerprintName)),
    )
    val oldBytes = artifacts.map { (_, _, target) ->
        if (target.isFile) runCatching { target.readBytes() }.getOrNull() else null
    }
    val tmps = ArrayList<File>(artifacts.size)
    try {
        for ((name, bytes, _) in artifacts) tmps += writeTmp(rt2Dir, name, bytes)
    } catch (e: IOException) {
        tmps.forEach { it.delete() }
        throw e
    }
    try {
        for (i in artifacts.indices) moveAtomic(tmps[i], artifacts[i].third)
    } catch (e: IOException) {
        tmps.forEach { it.delete() }
        // 逐件回滚：有旧字节就写回（同样 tmp + 换名），原本不存在就删掉半成品。
        // 回滚自身再失败也不能掩盖首错，故逐件 runCatching 且不吞首错。
        for (i in artifacts.indices) {
            val name = artifacts[i].first
            val target = artifacts[i].third
            val old = oldBytes[i]
            runCatching {
                if (old == null) target.delete() else moveAtomic(writeTmp(rt2Dir, name, old), target)
            }
        }
        throw e
    }
}

/** 同目录 tmp 落盘：写完 flush + fd.sync，断电也只丢掉 tmp，不会脏正式名。 */
private fun writeTmp(dir: File, name: String, bytes: ByteArray): File {
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建目录：$dir")
    val tmp = File(dir, "$name.${System.nanoTime()}.tmp")
    try {
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    } catch (e: IOException) {
        tmp.delete()
        throw e
    }
    return tmp
}

private fun moveAtomic(tmp: File, target: File) {
    if (tmp.renameTo(target)) return
    // 先试真原子换名；不支持原子语义的文件系统（FAT/exFAT 等）退回普通 REPLACE_EXISTING
    // —— 上面 renameTo 已失败一次，第二次 move 失败会真抛，不掩盖错误。
    val atomic = runCatching {
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
    if (atomic.isSuccess) return
    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
}
