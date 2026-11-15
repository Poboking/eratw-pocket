package com.eratw.port.buildlogic

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * staging：把随包件搬到 `build/generated/gameAssets`，再由 AGP 当 assets 根打进 APK。
 * （原 app/build.gradle.kts 的 ad-hoc `stageGameAssets` 任务类化 —— 逻辑逐行同源搬运，
 *  不改任何构建语义；旧任务名 `stageErbcAssets` 的别名仍在 app 脚本里。）
 *
 * 为什么必须拷（而不是把游戏根直接当 assets 源）：AGP 的 `assets.srcDir` 会把目录内容
 * **摊平到 assets 根**，路径前缀 `resources/`、`CSV/` 就没了，而装载侧按相对路径找图；
 * 游戏根里又不能用的东西更多（ERB 源、exe/dll、sav 存档、readme 集）。所以这里做一层
 * 「链接农场」：只把要的四类目录（见 [stagedDataDirs]）+ 三个配置按原相对路径落进 staging，其余一概不进。
 *
 * 335 MB 的语料用**同卷硬链接**落 staging（`Files.createLink`），staging 侧对链接项只读不写
 * —— 语料在共享根是只读的（AGENTS 第 1 节），链接不复制内容也不改内容；跨卷或链接被拒时
 * 自动退回 1 MiB 流式拷贝，日志分别给出 linked/copied 两个计数。
 *
 * 程序侧沿用既有机制：`program.erbc` 单条，或按 `-PeratwErbcChunkMb` 切成
 * `program.partNNN.erbc` + `program.parts` 清单（每行「文件名 TAB 字节数」）。
 * **分片是裸字节切片、不是独立容器**（CMP-74：规约 §7 已按实产口径改正）：拼接只认
 * program.parts 的行序，拼回的字节必须与切分前的 program.erbc 逐字节相同；每片不带头、
 * 不保证落在指令边界。`program.parts` 保持两列是为了兼容设备侧解析器
 * （platform/GameAssetInstaller.readPartsManifest），每片 sha256 记在随包 manifest 的
 * `shard` 行里（本任务追加的 package 段）。
 * 分片名保留 .erbc 后缀是有意的：aapt2 的 noCompress 按后缀匹配，叫
 * program.erbc.part000 会掉进压缩、STORED 口径失效（2026-10-21 实测：3 片全变 DEFLATE）。
 *
 * 额外写一份 `erbc.v1/package.report`（解包器只按固定名字取 program/meta/parts/manifest，
 * 不会碰它）：随包条目数、各类字节数、程序压缩口径、后缀表、APK 体积上限、清单自身的
 * sha256（`manifest-sha256`，CMP-74 的「报告 ↔ 清单」对账锚点）。
 * `tools/apkpack/inspect_apk.py` 读它并与真实 zip 逐类对账 —— APK 自此**自描述**，
 * 核验不必猜「这个包本该多大」。
 *
 * 输入指纹：全走注解（[InputDirectory]/[InputFile]/[Input]/[OutputDirectory]），与旧
 * `inputs.dir/file/property` 逐条同义；属名从位置序号改成属性名 —— 增量键改名会让改动后的
 * **首次**构建重跑一次 staging，产物不变。
 *
 * 语料是 335 MB，Gradle 每次要 xxhash 一遍（实测几十秒内）；不开这条的代价是
 * 「改了 CSV 却命中 UP-TO-DATE」出旧包，不能省。
 */
abstract class StageGameAssetsTask : DefaultTask() {

    /** staging 输出根（`assets.srcDir` 指它）。 */
    @get:OutputDirectory
    abstract val outRoot: DirectoryProperty

    /** erbc 产物目录（program.erbc / program.meta / manifest.txt…）；未随包 erbc 时不给值。 */
    @get:Optional
    @get:InputDirectory
    abstract val erbcDir: DirectoryProperty

    /** 游戏根里现存的随包语料目录（[stagedDataDirs] 命中的那些；整体内容哈希）。 */
    @get:InputFiles
    abstract val corpusDirInputs: ConfigurableFileCollection

    /** 语料根 ERB 目录（只随包里面的 .ERD 表；独立命名输入，与旧 `withPropertyName("corpusErb")` 同名）。 */
    @get:Optional
    @get:InputDirectory
    abstract val corpusErb: DirectoryProperty

    /** 游戏根里现存的配置文件（[stagedConfigFiles] 命中的那些）。 */
    @get:InputFiles
    abstract val configFileInputs: ConfigurableFileCollection

    /** 语料内容补丁索引目录（`devtools/corpus-patches`；`root: game` 的条目改随包字节）。 */
    @get:Optional
    @get:InputDirectory
    abstract val corpusPatchesDir: DirectoryProperty

    /** 版本单源 Version.kt —— CMP-66：判据本身也是输入，改了必须重跑（对账才跑得到）。 */
    @get:InputFile
    abstract val erbccVersionSource: RegularFileProperty

    /**
     * CMP-66 编译器指纹的输入面：core-vm/src/main/kotlin 整树。改编译器实现不改 VERSION 时，
     * 上一轮 staging 会命中 UP-TO-DATE、compiler-digest 对账就永远不跑。这里只登记输入指纹
     * （不是跨项目 dependsOn，不触发编译）。
     */
    @get:InputFiles
    abstract val compilerSources: ConfigurableFileCollection

    /**
     * 随包件落盘的并行度（`-PeratwStageWorkers`）。10k 条硬链接/拷贝是元数据操作，
     * 单线程做一遍要几十秒；按物理核口径分给线程池能真并行。设 1 = 完全退回旧串行行为，
     * 供 A/B 与事故回退。作为 [Input] 登记：翻了这个值就重跑 staging，不会拿旧产物冒充新结果。
     */
    @get:Input
    abstract val stageWorkers: Property<Int>

    /** 分片字节数（0 = 单条）。分片大小改了必须重切，不能命中 UP-TO-DATE。 */
    @get:Input
    abstract val chunkBytes: Property<Long>

    /** [chunkBytes] 的 MiB 原值（只进 package.report 的 `program-chunk-mb`）。 */
    @get:Internal
    abstract val chunkMiB: Property<Int>

    /** true = 程序条目允许 DEFLATE；false = STORED（首启纯拷贝、可按字节判重拷）。改了必须重打包。 */
    @get:Input
    abstract val programStored: Property<Boolean>

    /** `-PeratwRequireComplete`：完整形态自检开关。开关翻了必须重跑（staging 才会执行闸）。 */
    @get:Input
    abstract val requireComplete: Property<Boolean>

    /** `-PeratwAllowStaleErbc` 放行开关：翻了必须重跑（对账才跑得到）。 */
    @get:Input
    abstract val allowStaleErbc: Property<Boolean>

    /**
     * `-PeratwForeignCorpus`（ENGC-6 构建期通道，pack `--foreign-corpus`）：声明本次随包的
     * 游戏根**不是** eraTW 语料补丁表所属的那个根 ⇒ root=game 的补丁整表不适用
     * （不套、也不因「游戏根目标缺席」判红），否则第三方游戏根出不了完整资产包。
     * 缺省 false = 与引入本开关前逐字节相同（判红护栏原样保留）。
     * 作为 [Input] 登记：翻这个值必须重跑 staging，不许拿另一根的产物冒充。
     */
    @get:Input
    abstract val foreignCorpus: Property<Boolean>

    /**
     * 引擎档印章源文件（`-PeratwEngineStampFile`；ENGC-6 构建期通道，CONTRACT §8.0.4）。
     *
     * 非空 ⇒ 执行期把该文件**字节**写进 staging 根 `engine-profile.json`
     * （staging 根 = APK 的 `assets/`，所以 APK 条目是 `assets/engine-profile.json`，
     * 与 core-vm `EngineStamp.ASSET_NAME` 逐字对齐；目录不存在则建，APK 运行期按印章档装配）；
     * 缺省 ⇒ 删掉 staging 根可能残留的旧印章（含旧口径误写的 `assets/engine-profile.json` 一层，
     * 属性从有到无的场景），保证「无 stampFile ⇒ APK 无印章」恒成立。
     * 只拷字节 + 轻校验（非空、可按 schema/profile/engine/version 四字段解析），
     * **不内置档位表**：profile/engine/version 的合法值单源在
     * `core-vm EngineProfiles.TARGETS` 与 python 出包侧同步表，staging 侧只认字节。
     * 声明为 [InputFile]：印章文件内容变更 ⇒ 必须重跑（不得 UP-TO-DATE）。
     */
    @get:Optional
    @get:InputFile
    abstract val engineStampFile: RegularFileProperty

    /** 本次随包的游戏根（null = 只随包 erbc 的形态）。运行期值，目录内容不整树哈希。 */
    @get:Internal
    abstract val gameRoot: DirectoryProperty

    /** erbc 目录是否来自兜底路径（决定新鲜度对账 hard 与 report 的 `erbc-dir-fallback`）。 */
    @get:Internal
    abstract val erbcDirIsFallback: Property<Boolean>

    /** 仓库根（[verifyErbcFreshness] 的版本单源与编译器指纹源码树挂在其下）。 */
    @get:Internal
    abstract val repoRoot: DirectoryProperty

    /** 要随包的语料目录名（[stagedDataDirs] 结果；内容指纹在 [corpusDirInputs]）。 */
    @get:Internal
    abstract val dataDirs: ListProperty<String>

    /** 要随包的配置文件名（[stagedConfigFiles]；现存文件的指纹在 [configFileInputs]）。 */
    @get:Internal
    abstract val configFiles: ListProperty<String>

    /** APK 体积上限的固定余量（`package.report` 的 `apk-ceiling-bytes`）。 */
    @get:Internal
    abstract val apkOverheadAllowance: Property<Long>

    /** 配置期扫描出的 noCompress 后缀表（[bundledSuffixes] 结果；report 的 `no-compress-suffixes`）。 */
    @get:Internal
    abstract val noCompressSuffixes: ListProperty<String>

    @TaskAction
    fun stage() {
        val chunkBytes = chunkBytes.get()
        val outRoot = outRoot.get().asFile
        val gameRoot = gameRoot.orNull?.asFile
        val erbcDir = erbcDir.orNull?.asFile
        val dataDirs = dataDirs.get()
        val configFiles = configFiles.get()
        val programStored = programStored.get()
        val requireComplete = requireComplete.get()
        val allowStaleErbc = allowStaleErbc.get()
        val erbcDirIsFallback = erbcDirIsFallback.get()
        val erbcChunkMiB = chunkMiB.get()
        val apkOverheadAllowance = apkOverheadAllowance.get()
        val stagedSuffixes = noCompressSuffixes.get()
        val repoRoot = repoRoot.get().asFile

        // 兜底目录（erbcc 的默认输出位）里的产物是「上一次谁编的」：本任务只读目录、不触发编译，
        // 而 `program.meta` 的 program-sha256 只能证明 erbc/meta 互相配对，证明不了它出自当前源码。
        // 所以正式包（-PeratwRequireComplete）直接拒绝兜底目录，调试包留警告 + report 标记。
        // （不挂 `dependsOn(":tools:erbcc:compileCorpus")`：跨项目依赖在 configure-on-demand 下
        //  会在执行计划阶段撞 erbcc 的 classloader scope 未锁定，2026-10-22 实测。）
        if (erbcDir != null && erbcDirIsFallback) {
            if (requireComplete) {
                throw GradleException(
                    "随包 erbc 取自兜底目录（$erbcDir），无法证明是当前源码编译的：" +
                        "先跑 ./gradlew :tools:erbcc:compileCorpus，或用 -PeratwErbcDir 显式指定产物目录",
                )
            }
            logger.warn(
                "stageGameAssets: erbc 取自兜底目录（$erbcDir）⇒ 不保证是最新编译；" +
                    "正式包请先跑 :tools:erbcc:compileCorpus 或显式 -PeratwErbcDir。" +
                    "产物指纹见 package.report 的 program-sha256 行",
            )
        }

        // CMP-66：显式 pin 的 erbc 目录必须能自证「出自当前编译器 + 当前语料」。
        // 判据全在 manifest（version / build-mode / 容器版本 / 产物摘要 / 逐文件源 sha）；
        // 显式档 hard=true（不符即拒），兜底档沿用既有政策（警告 + report 标记，正式包
        // 由 -PeratwRequireComplete 在下面挡）。fixture 用 -PeratwAllowStaleErbc 放行。
        val erbcFreshness: String = when {
            erbcDir == null -> "no-erbc-dir"
            allowStaleErbc -> {
                logger.warn(
                    "stageGameAssets: -PeratwAllowStaleErbc → 跳过 CMP-66 新鲜度对账（$erbcDir）；" +
                        "只许在 fixture/体积测算用，正式包去掉这个开关",
                )
                "override(-PeratwAllowStaleErbc)"
            }
            else -> verifyErbcFreshness(
                erbcDir, gameRoot?.let { File(it, "ERB") }, hard = !erbcDirIsFallback,
                repoRoot = repoRoot, logger = logger,
            )
                ?: "unverified(见上面的告警)"
        }

        // 幂等 staging：只整体重建 erbc.v1（几件、每次都要自证），语料四类目录改走
        // 「差量同步」——目标已与源同一 inode（硬链接）就跳过，不再全删 10k 条链接。
        // 全删重建会让下游 merge/compress/package 的输入指纹每次都变，把 1 GiB 级重打包
        // 从「只有内容真变了才做」退化成「只要本任务重跑就做」。残留件由 pruneStaged 清掉。
        File(outRoot, "erbc.v1").deleteRecursively()
        var linked = 0
        var copied = 0
        var extensionless = 0
        val report = LinkedHashMap<String, String>()
        // cb001 接线②（D8 缺口闭环）：applied_corpus_patches 列必须由产物侧自己写，
        // 不再由出包脚本代生成。默认空形状（无随包 manifest 或 manifest 无留痕时取 "-"，
        // 列仍在，不让「缺列」被脚本兜成默认绿）；1 段算完随包 manifest.txt 后，
        // 按真实 corpus-patch 留痕行覆盖（见 formatAppliedCorpusPatches）。
        var appliedCorpusPatchesValue = APPLIED_CORPUS_PATCHES_EMPTY
        report["schema"] = "eratw-package-report"
        report["version"] = "1"
        report["program-mode"] = if (programStored) "stored" else "deflate"
        report["program-chunk-mb"] = erbcChunkMiB.toString()
        report["game-root"] = gameRoot?.absolutePath ?: "-"
        report["erbc-source"] = erbcDir?.absolutePath ?: "-"
        // 兜底档 = 目录内容可能是上一次任意一次编译留下的；显式 pin 才是刻意复用。
        report["erbc-dir-fallback"] = erbcDirIsFallback.toString()
        // CMP-66：新鲜度对账结论（verified(...) / override(...) / no-erbc-dir / unverified）。
        report["erbc-freshness"] = erbcFreshness

        // --- 1. 程序产物：单条或分片 + 侧车 + 编译清单 -------------------------------
        val target = File(outRoot, "erbc.v1").apply { mkdirs() }
        var erbcEntries = 0
        var erbcBytes = 0L
        var programParts = 0
        // 分片表（文件名 / 字节数 / SHA-256），行序即拼接顺序；单条档为空。
        // `program.parts` 仍保持两列（文件名 TAB 字节数）：设备侧
        // `GameAssetInstaller.readPartsManifest` 只解析两列（platform 模块不在本路文件面），
        // 每片 sha 因此登记在随包清单的 `shard` 行里（inspect_apk.py 对账用）。
        val shards = mutableListOf<Triple<String, Long, String>>()
        if (erbcDir != null) {
            val program = File(erbcDir, "program.erbc")
            val meta = File(erbcDir, "program.meta")
            if (!program.isFile || !meta.isFile) {
                throw GradleException("erbc 目录缺 program.erbc / program.meta：$erbcDir")
            }
            // 侧车自带 program-sha256（BootMetaWriter 写在头部，见 BootMetaWriter 类头第 3 段），
            // 打包期先对账：配对错的组合装上去只会停在装载器失败屏，不如在这里挡住。
            val declaredSha = meta.useLines { lines ->
                lines.map { it.trim() }
                    .firstOrNull { it.startsWith("program-sha256 ") }
                    ?.removePrefix("program-sha256 ")
                    ?.trim()
                    ?.trim('"')
                    ?.takeIf { it.isNotEmpty() && it != "-" }
            }
            // 无条件算指纹：对账用它，package.report 也写它 —— 拿到 APK 的人可以拿这一行
            // 与构建日志对齐，不必再猜「这份包里的产物是哪次编的」。
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(program, "r").use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val programSha = digest.digest().hexLower()
            report["program-sha256"] = programSha
            if (declaredSha != null) {
                if (!programSha.equals(declaredSha, ignoreCase = true)) {
                    throw GradleException(
                        "program.erbc 与 program.meta 不配对：meta 声明 $declaredSha，实际 $programSha",
                    )
                }
                logger.lifecycle("stageGameAssets: program-sha256 对账通过 $programSha")
            } else {
                logger.lifecycle("stageGameAssets: meta 未声明 program-sha256，跳过对账（实际 $programSha）")
            }

            val programBytes = program.length()
            report["program-bytes"] = programBytes.toString()
            if (chunkBytes <= 0L || programBytes <= chunkBytes) {
                // 单条：整文件落 staging（能链接就链接，省 378 MB 的拷贝）
                if (linkOrCopy(program, File(target, "program.erbc"))) linked++ else copied++
                logger.lifecycle("stageGameAssets: program.erbc 单条 asset ${mb(programBytes)}")
            } else {
                // 分片 = **裸字节切片**（不是独立容器）：`program.parts` 的行序就是拼接顺序，
                // 逐片拷贝时顺手算 sha256（manifest 的 shard 行 / inspect_apk 对账用）。
                var offset = 0L
                var index = 0
                while (offset < programBytes) {
                    val size = minOf(chunkBytes, programBytes - offset)
                    val name = "program.part%03d.erbc".format(index)
                    val shardDigest = MessageDigest.getInstance("SHA-256")
                    copyRange(program, File(target, name), offset, size, shardDigest)
                    copied++
                    shards += Triple(name, size, shardDigest.digest().hexLower())
                    offset += size
                    index += 1
                }
                File(target, "program.parts").writeText(
                    shards.joinToString("\n", postfix = "\n") { "${it.first}\t${it.second}" },
                )
                programParts = shards.size
                logger.lifecycle(
                    "stageGameAssets: program.erbc 切 ${shards.size} 片（每片<=${mb(chunkBytes)}），合计 ${mb(programBytes)}",
                )
            }
            report["program-parts"] = programParts.toString()
            if (linkOrCopy(meta, File(target, "program.meta"))) linked++ else copied++
            // 段 3 语句记录偏移表侧车（可选加速件）：装载期读它可跳过逐条反序列化
            // 171 万条语句的索引趟（真机实测 9.5 s → 百毫秒级）。没有该文件就打一条
            // 提示，装载器会自动回落扫描。
            val stmtIdx = File(erbcDir, "program.stmt.idx")
            if (stmtIdx.isFile) {
                if (linkOrCopy(stmtIdx, File(target, "program.stmt.idx"))) linked++ else copied++
                logger.lifecycle("stageGameAssets: program.stmt.idx ${mb(stmtIdx.length())}（装载提速侧车）")
            } else {
                logger.warn("stageGameAssets: erbc 目录缺 program.stmt.idx（装载会回落语句扫描，慢 9 秒级）")
            }

            // --- CMP-74：随包清单 = 清单 ↔ 随包文件集的可对账单源 -------------------
            // erbcc 的构建清单（manifest-format 2）已登记 program.erbc/program.meta/
            // program.stmt.idx；分片是**打包期**才发生的，所以在同一份清单末尾追加
            // `manifest-source package` 段：package-artifact = 本包 erbc.v1 里实际存在的
            // 每个文件（除 manifest.txt 自身），container-artifact = 切分前的整容器，
            // shard = 按行序可拼回整容器的每片（字节数 + sha256）。
            // 旧产物（无 manifest-format 行，CMP-74 之前的 `artifacts 1`）在这里补格式标记，
            // 随包面始终是可对账的新格式；构建面目录里的清单保持原样不动。
            val buildManifest = File(erbcDir, "manifest.txt")
            val baseManifest = if (buildManifest.isFile) {
                buildManifest.readText()
            } else {
                logger.warn("stageGameAssets: erbc 目录缺 manifest.txt（$erbcDir）；随包清单按实际文件重建，源文件清单缺席")
                "# erbc package manifest —— erbcc 构建清单缺席，打包期按实际随包文件生成\n"
            }
            val markedManifest = if (baseManifest.lineSequence().any { it.startsWith("manifest-format ") }) {
                baseManifest
            } else {
                "manifest-format $ERATW_MANIFEST_FORMAT\n" + baseManifest
            }
            val physical = target.listFiles().orEmpty()
                .filter { it.isFile && it.name != "manifest.txt" }
                .sortedBy { it.name }
            val shardSha = shards.associate { it.first to it.third }
            val packageBlock = buildString {
                append("manifest-source package\n")
                append("package-artifacts ").append(physical.size).append('\n')
                for (f in physical) {
                    val sha = when {
                        f.name == "program.erbc" -> programSha
                        f.name in shardSha -> shardSha.getValue(f.name)
                        else -> sha256Of(f)
                    }
                    append("package-artifact erbc.v1/").append(f.name).append(' ')
                        .append(f.length()).append(' ').append(sha).append('\n')
                }
                append("container-artifact erbc.v1/program.erbc ")
                    .append(programBytes).append(' ').append(programSha).append('\n')
                append("container-shards ").append(shards.size).append('\n')
                for ((name, size, sha) in shards) {
                    append("shard erbc.v1/").append(name).append(' ')
                        .append(size).append(' ').append(sha).append('\n')
                }
            }
            val stagedManifest = File(target, "manifest.txt")
            val stagedManifestText =
                (if (markedManifest.endsWith("\n")) markedManifest else markedManifest + "\n") + packageBlock
            stagedManifest.writeText(stagedManifestText)
            // package.report 的 `manifest-sha256` 让「报告 ↔ 清单」也能对账（inspect_apk.py）。
            report["manifest-sha256"] = sha256Of(stagedManifest)
            // cb001 接线②：产物侧从随包 manifest 的真实 corpus-patch 留痕行自证本节套用的
            // 语料补丁，写进 package.report 的独立列；形状与出包脚本逐字一致，坏留痕形状
            // 直接构建失败（绝不静默丢补丁）。留痕行本身不动。
            appliedCorpusPatchesValue = formatAppliedCorpusPatches(stagedManifestText)

            erbcEntries = target.listFiles().orEmpty().count { it.isFile }
            erbcBytes = target.listFiles().orEmpty().sumOf { it.length() }
            report["entries-erbc"] = erbcEntries.toString()
            report["bytes-erbc"] = erbcBytes.toString()
        } else {
            logger.warn(
                "stageGameAssets: 没有 erbc 产物目录（-PeratwErbcDir / ERATW_ERBC_DIR 都没给，" +
                    "默认路径也不存在）：这个 APK 装上不会有 program.erbc，**玩不了**；" +
                    "先跑 ./gradlew :tools:erbcc:compileCorpus",
            )
        }

        // --- 2. 语料四类目录（CSV / resources / sound / plugins）：按原相对路径落链接农场 ----
        // root=game 的语料补丁（如 CB-005 的 CSV/_Rename.csv）在这里换成修后字节再落 staging：
        // 硬链接是只读的，穿透写会改坏共享根的语料（AGENTS 铁律），所以命中补丁的文件
        // 走「删链接 + 写字节」而不是 link 后修改。
        val patchesDir = corpusPatchesDir.orNull?.asFile
        val gameRootPatches = if (gameRoot != null && patchesDir != null) {
            planGameRootPatches(gameRoot, patchesDir, logger, foreignCorpus.getOrElse(false))
        } else {
            emptyMap()
        }
        val stagedPatchTargets = mutableSetOf<String>()
        val stagedDirs = LinkedHashMap<String, Pair<Int, Long>>()
        val workers = stageWorkers.get().coerceAtLeast(1)
        if (gameRoot != null) {
            // --- 2a. 先只做目录遍历、收集待同步件（单线程，不碰 I/O）------------------
            val items = mutableListOf<StageItem>()
            dataDirs.forEach { name ->
                val src = File(gameRoot, name)
                if (!src.isDirectory) {
                    logger.warn("stageGameAssets: 游戏根缺 $name/（$src）：该类条目不随包")
                    stagedDirs[name] = 0 to 0L
                    return@forEach
                }
                src.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.toRelativeString(src).replace('\\', '/')
                    val patched = gameRootPatches["$name/$rel"]
                    if (patched != null) stagedPatchTargets += "$name/$rel"
                    items += StageItem(f, File(outRoot, "$name/$rel"), rel, name, patched, !f.name.contains('.'))
                }
            }
            // --- 2b. 差量清理：源里已删的件不许留在 staged 里混进包 ------------------
            dataDirs.forEach { name ->
                val expected = items.filter { it.group == name }.mapTo(HashSet()) { it.rel }
                pruneStaged(File(outRoot, name), expected)
            }
            // --- 2c. 并行落盘：10k 条硬链接/补丁写入分给 workers 条线程 --------------
            val results = stageFilesParallel(items, workers)
            // 报告记当前形态（稳定），日志另报本次真动手的件数（看并行/幂等效果）
            linked += results.count { it.isLink }
            copied += results.count { !it.isLink }
            extensionless += results.count { it.extensionless }
            val byGroup = results.groupBy { it.group }
            dataDirs.forEach { name ->
                // 缺目录的形状与旧版逐字一致：stagedDirs 记 0，但不往 report 写这两列
                if (!File(gameRoot, name).isDirectory) return@forEach
                val rs = byGroup[name].orEmpty()
                val count = rs.size
                val bytes = rs.sumOf { it.bytes }
                stagedDirs[name] = count to bytes
                report["entries-$name"] = count.toString()
                report["bytes-$name"] = bytes.toString()
                logger.lifecycle(
                    "stageGameAssets: $name $count 个文件 ${mb(bytes)}（${bytes} B）",
                )
            }
            logger.lifecycle(
                "stageGameAssets: 随包落盘 workers=$workers · 提交 ${results.size} 件 · " +
                    "本次实际新建/重写 ${results.count { it.linked || it.copied }} 件 · " +
                    "幂等跳过 ${results.count { !it.linked && !it.copied }} 件",
            )
            val unapplied = gameRootPatches.keys - stagedPatchTargets
            if (unapplied.isNotEmpty()) {
                throw GradleException(
                    "stageGameAssets: 语料补丁目标未随包：$unapplied（补丁声明的目标目录不在随包面，" +
                        "erbc 修了但玩家拿不到同一份数据）→ 判红",
                )
            }
            // --- 2b. ERB 目录里的 **ERD 数据表**（只带 .ERD，不带 ERB/ERH 源码）----
            // 原版在 `<游戏根>/ERB` 下递归扫 `*.ERD` 建用户变量关键字表
            // （`ErhLoader.cs:342-388`），本侧 `UserKeywordTables.discover(gameRoot)` 同口径。
            // ERB 源码刻意不随包（编译产物 erbc.v1 取代它），但表是**运行期**数据：
            // 缺表 ⇒ `SEMEN_MATRIX:角色:x:子宫内射精量` 这类三段下标在运行期报
            // 「不能用字符串指定（未找到 ERD 表 …）」（2026-11-03 真机 4.981 新游戏实证）。
            run {
                val src = File(gameRoot, "ERB")
                var count = 0
                var bytes = 0L
                val erdRels = HashSet<String>()
                if (src.isDirectory) {
                    src.walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".ERD", ignoreCase = true) }
                        .forEach { f ->
                            val rel = f.toRelativeString(src).replace('\\', '/')
                            erdRels += rel
                            val dst = File(outRoot, "ERB/$rel")
                            if (dst.isFile && sameFile(dst, f)) {
                                // 幂等：链接还在就别重建；报告仍按当前形态记一条链接
                                linked++
                            } else if (linkOrCopy(f, dst)) linked++ else copied++
                            count++
                            bytes += f.length()
                        }
                } else {
                    logger.warn("stageGameAssets: 游戏根缺 ERB/（$src）：ERD 表不随包")
                }
                pruneStaged(File(outRoot, "ERB"), erdRels)
                stagedDirs["ERB"] = count to bytes
                report["entries-ERB"] = count.toString()
                report["bytes-ERB"] = bytes.toString()
                logger.lifecycle(
                    "stageGameAssets: ERB(ERD 表) $count 个文件 ${mb(bytes)}（${bytes} B）",
                )
            }
            // 配置：`emuera.config` 是 GameStorage 的游戏根标志、也是解包器「只拷一次」那条；
            // default/fixed 存在才带。
            var configCount = 0
            var configBytes = 0L
            configFiles.forEach { name ->
                val f = File(gameRoot, name)
                val dst = File(outRoot, name)
                if (f.isFile) {
                    if (dst.isFile && sameFile(dst, f)) {
                        // 幂等：链接还在就别重建（校准会在下面单独把它解链后改写）
                        linked++
                    } else if (linkOrCopy(f, dst)) linked++ else copied++
                    configCount++
                    configBytes += f.length()
                } else if (dst.isFile) {
                    dst.delete() // 源已删 ⇒ staged 不留旧配置
                }
            }
            // 安卓出厂校准（2026-11-11）：只对 staged 的 emuera.config 本份拷贝改写，
            // 语料本体零接触。⚠️ staged 件可能还是指向语料的硬链接：改写前必须先解除
            // 链接（delete 后新写），直接写硬链接 = 改语料，违反 AGENTS 第 1 节。
            val stagedConfig = File(outRoot, "emuera.config")
            if (stagedConfig.isFile && ANDROID_FACTORY_CALIBRATION.isNotEmpty()) {
                val original = stagedConfig.readText(Charsets.UTF_8)
                val (calibrated, applied) = applyFactoryCalibration(original, ANDROID_FACTORY_CALIBRATION)
                if (calibrated != original) {
                    val sizeBefore = stagedConfig.length()
                    stagedConfig.delete()
                    stagedConfig.writeText(calibrated, Charsets.UTF_8)
                    configBytes += stagedConfig.length() - sizeBefore
                    logger.lifecycle(
                        "stageGameAssets: 安卓出厂校准 ${applied.size} 项（${applied.joinToString("、")}）",
                    )
                }
                report[FACTORY_CALIBRATION_KEY] =
                    if (applied.isEmpty()) APPLIED_CORPUS_PATCHES_EMPTY else applied.joinToString(" | ")
            } else {
                report[FACTORY_CALIBRATION_KEY] = APPLIED_CORPUS_PATCHES_EMPTY
            }
            stagedDirs["配置"] = configCount to configBytes
            report["entries-config"] = configCount.toString()
            report["bytes-config"] = configBytes.toString()
            if (configCount == 0) {
                logger.warn("stageGameAssets: 游戏根没有 emuera.config：首启只能靠内部根兜底")
            }
        } else {
            // 只随 erbc 的形态：整体删改成了只重建 erbc.v1，所以上次全量包留下的
            // 图/声/配置必须在这里显式清掉，否则「零随包条目」包里会混进 1 GiB 旧资源。
            dataDirs.forEach { name -> File(outRoot, name).deleteRecursively() }
            File(outRoot, "ERB").deleteRecursively()
            configFiles.forEach { name -> File(outRoot, name).delete() }
            logger.lifecycle(
                "stageGameAssets: 未给 -PeratwGameRoot 且 ERATW_GAMEDIR 无效 ⇒ 只随包 erbc.v1，" +
                    "缺图缺声音（不是「装机即玩」形态）",
            )
        }

        // --- 2d. 引擎档印章（ENGC-6 构建期通道）：字节落 staging 根 -------------------
        // 属性有值 ⇒ 校验后逐字节写印章；缺省 ⇒ 清掉残留旧印章
        // （属性从有到无的场景，保证「无 stampFile ⇒ APK 无印章」恒成立）。
        // 校验失败让任务失败并带原样内容 —— 半个坏印章随包比打不出包更糟。
        //
        // **落点是 `outRoot/engine-profile.json`，不是 `outRoot/assets/...`**：`outRoot` 整个目录
        // 就是 APK 的 `assets/`（同级的 CSV/emuera.config/erbc.v1 都是这么随包的），
        // 多写一层会变成 APK 条目 `assets/assets/engine-profile.json`，
        // 而运行期 `EngineProfileStamp` 按 core-vm `EngineStamp.ASSET_NAME`="engine-profile.json"
        // 经 AssetManager 打开 = APK 的 `assets/engine-profile.json` ⇒ 印章**永远读不到**，
        // 配置模式出的包会静默退化成「无印章 = 全量 snake 行为」（fail-open）。
        // 2026-11-12 B7 端到端实测抓到（pack 的 config-mode 印章回读判红即此路径不一致），
        // 对账口径三方 = 这里写的相对路径 / `EngineStamp.ASSET_NAME` / `engine_compat.STAMP_ASSET_PATH`。
        val stampTarget = File(outRoot, "engine-profile.json")
        // 旧口径（多套一层 assets/）写出来的位置：staging 根是复用的，不清就会被下一次
        // assembleRelease 继续随包，表现为「包里有印章但游戏不认」。
        val legacyStampTarget = File(outRoot, "assets/engine-profile.json")
        val stampSrc = engineStampFile.orNull?.asFile
        if (stampSrc != null) {
            val stampBytes = stampSrc.readBytes()
            check(stampBytes.isNotEmpty()) {
                "引擎档印章文件是空的（$stampSrc）：非空四字段 JSON 才是合法印章"
            }
            val stampText = stampBytes.toString(Charsets.UTF_8)
            val stampFields = parseEngineStamp(stampText)
            check(stampFields != null) {
                "引擎档印章不是 schema/profile/engine/version 四字段 JSON（$stampSrc），原样内容：\n$stampText"
            }
            stampTarget.parentFile?.mkdirs()
            Files.write(stampTarget.toPath(), stampBytes)
            legacyStampTarget.delete()
            logger.lifecycle(
                "stageGameAssets: 引擎档印章 engine-profile.json（APK 条目 assets/engine-profile.json，" +
                    "profile=${stampFields.profile} " +
                    "engine=${stampFields.engine} version=${stampFields.version}，${stampBytes.size} B）",
            )
        } else {
            val cleaned = listOf(stampTarget, legacyStampTarget).filter { it.isFile }.onEach { it.delete() }
            if (cleaned.isNotEmpty()) {
                logger.lifecycle("stageGameAssets: 无 -PeratwEngineStampFile ⇒ 清掉 staging 残留旧印章（${cleaned.size} 处）")
            }
        }

        // --- 3. package.report：随包读数落进 assets，供 inspect_apk.py 逐类对账 -------
        val stagedBytes = erbcBytes + stagedDirs.values.sumOf { it.second }
        val stagedEntries = erbcEntries + stagedDirs.values.sumOf { it.first }
        report["staged-entries"] = stagedEntries.toString()
        report["staged-bytes"] = stagedBytes.toString()
        report["extensionless-files"] = extensionless.toString()
        report["linked-files"] = linked.toString()
        report["copied-files"] = copied.toString()
        report["no-compress-suffixes"] = (stagedSuffixes + if (programStored) listOf("erbc", "meta") else emptyList())
            .sorted()
            .joinToString(",")
        // APK 体积上限 = 随包字节 + 代码/资源开销余量 + 分片死区余量。
        // 单条实测总开销 5,245,013 B（718,297,161 − 713,052,148）。
        // **开分片必须额外留「一片」的死区**：2026-10-21 实测 `-PeratwErbcChunkMb=128`
        // 打成 3 片后 APK 778,661,112 B / 随包 713,052,148 B ⇒ 空隙 65.6 MB，
        // 而单条同样内容只差 5.2 MB —— AGP 的 zip 写入在「先写完语料、再回补大条目」时
        // 会把其中一片挪到包尾、原地留洞。结论：随包形态别开分片（缺省就是单条）。
        val alignSlack = if (programParts > 1) chunkBytes else 0L
        val apkCeiling = stagedBytes + apkOverheadAllowance + alignSlack
        report["align-slack-bytes"] = alignSlack.toString()
        report["apk-ceiling-bytes"] = apkCeiling.toString()
        // cb001 接线②：写盘前把产物侧自证的语料补丁独立列落进自述面。列必须始终存在
        // （无补丁取空形状 "-"，不是缺列）——出包脚本的严格闸据此判「产物是否自己能自证」。
        report[APPLIED_CORPUS_PATCHES_KEY] = appliedCorpusPatchesValue
        File(target, "package.report").bufferedWriter().use { w ->
            report.forEach { (k, v) -> w.write("$k $v\n") }
        }

        // --- 4. 闸：单条目 2 GiB 上限 + 完整形态自检 --------------------------------
        // 单条目 2 GiB 是 ZIP32 / libziparchive 的硬上限；超了装得进也读不出。
        val cap = 2L * 1024L * 1024L * 1024L - 1L
        val files = outRoot.walkTopDown().filter { it.isFile }.toList()
        files.filter { it.length() > cap }.forEach {
            throw GradleException("assets 单条目超过 2 GiB 上限：${it.name} = ${it.length()}")
        }
        if (requireComplete) {
            val missing = mutableListOf<String>()
            val artifactDir = File(outRoot, "erbc.v1")
            if (!File(artifactDir, "program.erbc").isFile && !File(artifactDir, "program.parts").isFile) {
                missing += "erbc.v1 程序产物（program.erbc 或 program.parts + 分片）"
            }
            if (!File(artifactDir, "program.meta").isFile) missing += "erbc.v1/program.meta"
            dataDirs.forEach { name ->
                val dir = File(outRoot, name)
                if (!dir.isDirectory || dir.walkTopDown().none { it.isFile }) missing += "$name/"
            }
            if (!File(outRoot, "emuera.config").isFile) missing += "emuera.config"
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "随包形态不完整（-PeratwRequireComplete）：缺 ${missing.joinToString("、")}" +
                        "；游戏根=${gameRoot?.absolutePath ?: "(未给)"} erbc=${erbcDir?.absolutePath ?: "(未给)"}",
                )
            }
        }

        logger.lifecycle(
            "stageGameAssets: ${files.size} 条 staging 文件（链接 $linked / 拷贝 $copied，" +
                "无后缀 $extensionless 个），随包 ${b(stagedBytes)} B（${mb(stagedBytes)}）" +
                "，其中程序 ${b(erbcBytes)} B",
        )
        logger.lifecycle(
            "stageGameAssets: 程序条目 ${if (programStored) "STORED（首启纯拷贝、可按字节判重拷）" else "允许 DEFLATE（首启多一次 inflate）"}；" +
                "APK 体积上限 ${b(apkCeiling)} B（${mb(apkCeiling)}，含分片死区余量 ${b(alignSlack)} B）",
        )
    }
}

/** MiB 可读串（report 与日志共用）。 */
private fun mb(bytes: Long): String = String.format("%.1f MiB", bytes / 1024.0 / 1024.0)

/** 千位分隔字节数（report 与日志共用）。 */
private fun b(bytes: Long): String = String.format("%,d", bytes)

/** package.report 里 applied_corpus_patches 独立列的键（与 pack_release.py 同字面）。 */
const val APPLIED_CORPUS_PATCHES_KEY = "applied_corpus_patches"

/** 随包 manifest.txt 里一条语料补丁留痕行的前缀（与 CorpusPatches.kt:326 同字面，尾随空格有意）。 */
const val CORPUS_PATCH_MARKER = "corpus-patch "

/** 本次没套任何补丁时独立列的空形状（与 pack_release.py 的 APPLIED_CORPUS_PATCHES_EMPTY 同字面）。 */
const val APPLIED_CORPUS_PATCHES_EMPTY = "-"

/** 64 位小写 hex sha 形状（与 pack_release.py 的 _parse_patch_fields 同判据）。 */
private val PATCH_SHA_RE = Regex("[0-9a-f]{64}")

/**
 * 产物侧自证：从随包 manifest.txt 文本生成 applied_corpus_patches 独立列的值。
 *
 * 形状与出包脚本的 format_applied_corpus_patches 逐字一致：单行值、多项以 " | " 分隔、
 * 每项形如 `<id> <target> <bad-sha> <fixed-sha>`、无补丁取 "-"。真语料 target 含空格
 * （如 051 Nitori 开头、中间夹日文括号的那类相对路径），按「头一个 id + 尾两个 64 位
 * 小写 hex sha + 中间全当 target」拆，不按空格数硬切。非留痕行一律跳过。
 * 坏形状（字段不足 4 段、或病灶/修后 sha 不是 64 位小写 hex）抛 GradleException 让构建失败，
 * 绝不静默丢一个补丁。
 */
fun formatAppliedCorpusPatches(manifestText: String): String {
    val items = mutableListOf<String>()
    for (line in manifestText.lineSequence()) {
        if (!line.startsWith(CORPUS_PATCH_MARKER)) continue
        val fields = line.removePrefix(CORPUS_PATCH_MARKER).trim().split(Regex("\\s+"))
        if (fields.size < 4) {
            throw GradleException("语料补丁留痕字段不足 4 段：$line")
        }
        val patchId = fields.first()
        val badSha = fields[fields.size - 2]
        val fixedSha = fields.last()
        if (!PATCH_SHA_RE.matches(badSha) || !PATCH_SHA_RE.matches(fixedSha)) {
            throw GradleException("语料补丁留痕的病灶/修后 sha 不是 64 位小写 hex：$line")
        }
        val target = fields.subList(1, fields.size - 2).joinToString(" ")
        items += "$patchId $target $badSha $fixedSha"
    }
    return if (items.isEmpty()) APPLIED_CORPUS_PATCHES_EMPTY else items.joinToString(" | ")
}

/** package.report 里 factory-calibration 独立列的键（生效条目按 " | " 连接，与补丁列同形状）。 */
const val FACTORY_CALIBRATION_KEY = "factory-calibration"

/**
 * 安卓出厂校准表（2026-11-11 QQ 群反馈波）：staged 的 `emuera.config` 是 PC 语料原样，
 * 手机首装的默认值按这张表改写 —— **只动 staging 拷贝，语料本体一个字节不动**。
 *
 * 生效链（三处自动同源，零额外接线）：
 * - 首装：`GameAssetInstaller` 只拷一次 ⇒ 新装玩家拿到校准后的移动默认；
 * - 老玩家：盘上那份永不覆盖 ⇒ 校准本身不改写它；历史出厂值（如 4.980 的死循环阈值
 *   15000）由 `PortFactoryConfig.CONFIG_VALUE_MIGRATIONS` +
 *   `GameAssetInstaller.migrateConfigValues` 在升级后首个冷启动一次性迁移；
 * - 重置默认：`FactoryConfigDefaults` 读的就是这份 staged 资产 ⇒ 重置也回校准值。
 *
 * 表就是群友征集结果的落点：字高/行高/窗口宽度等日后回填只改这里，并同步 `FactoryCalibrationTest`。
 */
val ANDROID_FACTORY_CALIBRATION: Map<String, String> = mapOf(
    // actifse（11-10）：PRINTC 每行项目数改 4（随包出厂原是 PC 语料作者调的 5）。
    "ITEMS PER LINE FOR PRINTC" to "4",
    // 2026-11-12：4.980 语料把死循环告警阈值显式写成 15000（emuera.config:31）。平台层
    // 「只填空、不改写」的覆盖策略因此永远注入不了出厂值，线上实际是 15 s、长链反复弹框。
    // 在随包资产里直接校到 360 s：新装/重装立即生效；老玩家盘上的 15000 由安装器一次性迁移
    // （见 PortFactoryConfig.CONFIG_VALUE_MIGRATIONS 与 GameAssetInstaller.migrateConfigValues）。
    "MILLISECONDS FOR INFINITE LOOP WARNING" to "360000",
)

/**
 * 安卓出厂校准的纯函数：把 `KEY:VALUE` 形态的行按 [table] 改写，其余行（注释/空行/
 * 未知键）原样保留、行序不动；表里有而文件里没有的键追加到文末。键匹配不分大小写
 * （原版配置键全大写，这里归一后比对），值原样落。CRLF/LF 逐行保持。
 *
 * 返回（校准后全文，实际生效的 `KEY=VALUE` 清单 —— report 自证列与日志共用）。
 * 表为空 = 原样返回（测试基准）；坏行不抛 —— 校准只在「认识的键」上生效，
 * 多余的宽容换不来风险，少改一行才是不该发生的事。
 */
fun applyFactoryCalibration(text: String, table: Map<String, String>): Pair<String, List<String>> {
    if (table.isEmpty()) return text to emptyList()
    val eol = if (text.contains("\r\n")) "\r\n" else "\n"
    val lines = text.split("\n")
    val out = ArrayList<String>(lines.size + table.size)
    val applied = LinkedHashMap<String, String>()
    for (line in lines) {
        val lineEnd = if (line.endsWith("\r")) "\r" else ""
        val body = line.removeSuffix("\r")
        val idx = body.indexOf(':')
        var replaced = false
        if (idx > 0) {
            val key = body.substring(0, idx).trim().uppercase()
            val value = table.entries.firstOrNull { it.key.trim().uppercase() == key }?.value
            if (value != null) {
                out += body.substring(0, idx + 1) + value + lineEnd
                applied[key] = value
                replaced = true
            }
        }
        if (!replaced) out += line
    }
    val missing = table.entries.filter { it.key.trim().uppercase() !in applied }
    val rewritten = out.joinToString("\n")
    if (missing.isEmpty()) return rewritten to applied.map { (k, v) -> "$k=$v" }
    // 追加键：先保证全文以完整行结束（原文没以换行收尾就补一个，行尾风格随原文），
    // 再逐键一行，最后一行也以换行收尾 —— 配置文件的标准结尾，解析端两种都吃。
    val base = if (rewritten.endsWith("\n")) rewritten else rewritten + eol
    val result = base + missing.joinToString(eol) { "${it.key.trim().uppercase()}:${it.value}" } + eol
    val appliedList = applied.map { (k, v) -> "$k=$v" } + missing.map { (k, v) -> "${k.trim().uppercase()}=$v" }
    return result to appliedList
}

/**
 * 引擎档印章轻解析结果（schema/profile/engine/version 四字段，CONTRACT §8.0.4）。
 */
internal data class EngineStampFields(
    val schema: Long,
    val profile: String,
    val engine: String,
    val version: String,
)

/**
 * 印章轻校验纯函数：从印章文本提取四字段；任何一缺 ⇒ null（调用侧让任务失败带原样内容）。
 *
 * 只认「四字段都在且形态可解析」，**不内置档位表**：profile/engine/version 的合法值
 * 单源在 core-vm `EngineProfiles.TARGETS` 与 python 出包侧同步表，staging 侧只拷字节。
 * schema 是数字字面量，其余三串是字符串；字段序任意（JSON 语义），宽松匹配容空白。
 */
internal fun parseEngineStamp(text: String): EngineStampFields? {
    val schema = Regex("\"schema\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
        ?: return null
    fun stringField(name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
    val profile = stringField("profile") ?: return null
    val engine = stringField("engine") ?: return null
    val version = stringField("version") ?: return null
    return EngineStampFields(schema, profile, engine, version)
}

/**
 * 从 [src] 的 [offset] 起拷 [count] 字节到 [dst]，按 1 MiB 块流式走，不整份进堆。
 *
 * @param digest 非 null 时顺便把拷过的字节喂给它（分片 sha256 随切随算，不再回头读一遍 378 MB）
 */
private fun copyRange(src: File, dst: File, offset: Long, count: Long, digest: MessageDigest? = null) {
    RandomAccessFile(src, "r").use { input ->
        FileOutputStream(dst).use { output ->
            input.seek(offset)
            val buffer = ByteArray(1 shl 20)
            var remaining = count
            while (remaining > 0L) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, want)
                if (read < 0) throw GradleException("提前 EOF：${src.name}")
                output.write(buffer, 0, read)
                digest?.update(buffer, 0, read)
                remaining -= read.toLong()
            }
        }
    }
}

/** 一条待同步的随包件：src → dst；rel 相对本组目录（差量清理用），group 用于分类计数。 */
internal data class StageItem(
    val src: File,
    val dst: File,
    val rel: String,
    val group: String,
    val patched: ByteArray?,
    val extensionless: Boolean,
)

/**
 * 单件同步结果：linked/copied 二选一，两者都 false = 幂等跳过；bytes 是随包字节数。
 * [isLink] 记的是**当前 staged 形态**（是否与源同一 inode），与本次有没有动手无关 ——
 * package.report 的 linked/copied 两列据此汇总，语义从「本次动作数」改成「当前形态数」，
 * 这样幂等重跑不会让报告字节变化、进而把下游 1 GiB 重打包又打回 executed。
 */
internal data class StageResult(
    val group: String,
    val linked: Boolean,
    val copied: Boolean,
    val bytes: Long,
    val extensionless: Boolean,
    val isLink: Boolean,
)

/**
 * 落一件。三种「不用重写」的形状，都是为了避免让下游 1 GiB 重打包白白重跑：
 * - 补丁件：staged 字节与补丁字节逐字相同 ⇒ 跳过；
 * - 普通件：staged 与源是**同一 inode**（硬链接）⇒ 内容必然逐字节相同 ⇒ 跳过；
 * - 其余才建链接或流式拷贝。
 *
 * 幂等判定只认 inode / 内容，不认 mtime：Gradle 的输入指纹本身就是内容哈希，
 * 保住字节就等于保住 UP-TO-DATE。
 */
internal fun stageOne(item: StageItem): StageResult {
    val patched = item.patched
    if (patched != null) {
        if (item.dst.isFile && item.dst.readBytes().contentEquals(patched)) {
            return StageResult(item.group, false, false, patched.size.toLong(), item.extensionless, false)
        }
        if (item.dst.exists()) item.dst.delete()
        item.dst.parentFile?.mkdirs()
        Files.write(item.dst.toPath(), patched)
        return StageResult(item.group, false, true, patched.size.toLong(), item.extensionless, false)
    }
    if (item.dst.isFile && sameFile(item.dst, item.src)) {
        return StageResult(item.group, false, false, item.src.length(), item.extensionless, true)
    }
    val linked = linkOrCopy(item.src, item.dst)
    return StageResult(item.group, linked, !linked, item.src.length(), item.extensionless, linked)
}

/** 同一 inode 判据；路径不存在/跨卷/无权限一律当「不同」，退回重建而不是静默跳过。 */
internal fun sameFile(a: File, b: File): Boolean = try {
    Files.isSameFile(a.toPath(), b.toPath())
} catch (_: Throwable) {
    false
}

/**
 * 并行落盘：10k 条硬链接/补丁写入是元数据操作，线程池能真吃满多核。
 * `workers=1` 完全退回旧串行行为（A/B 与事故回退口）；结果按提交顺序返回，
 * 日志与计数仍由主线程汇总，输出顺序稳定、Gradle 增量键不受线程调度影响。
 */
internal fun stageFilesParallel(items: List<StageItem>, workers: Int): List<StageResult> {
    if (items.isEmpty()) return emptyList()
    if (workers <= 1) return items.map(::stageOne)
    val pool = Executors.newFixedThreadPool(minOf(workers, items.size))
    return try {
        val futures = items.map { item -> pool.submit(Callable { stageOne(item) }) }
        futures.map { it.get() }
    } finally {
        pool.shutdown()
    }
}

/** 删掉 [dir] 下不在 [expected] 里的旧件：源已删 ⇒ 包里不许留旧字节。 */
internal fun pruneStaged(dir: File, expected: Set<String>) {
    if (!dir.isDirectory) return
    dir.walkTopDown().filter { it.isFile }.forEach { f ->
        val rel = f.toRelativeString(dir).replace('\\', '/')
        if (rel !in expected) f.delete()
    }
}

/**
 * 整文件落 staging：先试同卷硬链接（0 拷贝、0 空间），被拒（跨卷/权限/文件被占）再流式拷。
 *
 * @return true = 建了链接，false = 走了拷贝
 */
private fun linkOrCopy(src: File, dst: File): Boolean {
    dst.parentFile?.mkdirs()
    if (dst.exists()) dst.delete()
    return try {
        Files.createLink(dst.toPath(), src.toPath())
        true
    } catch (_: Throwable) {
        copyRange(src, dst, 0L, src.length())
        false
    }
}
