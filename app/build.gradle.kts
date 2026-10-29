import java.io.FileOutputStream
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.file.Files
import java.io.RandomAccessFile
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
    // 壳层 UI 用 Jetpack Compose（游戏画面仍是 CanvasConsoleView，经 AndroidView 嵌入）。
    id("org.jetbrains.kotlin.plugin.compose")
}

// 目标 ABI 白名单：默认只留真机在用的两个 32/64 位 ARM 变体；
// -PeratwAbi=x86_64 之类的值覆盖它（逗号分隔）。当前无 jniLibs，故不影响现有产物。
val abiList: List<String> = (providers.gradleProperty("eratwAbi").orNull ?: "arm64-v8a,armeabi-v7a")
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

// ===========================================================================
// 随包口径（M10-PKG400）：装机即玩 = 程序产物 + CSV/resources/sound/plugins/emuera.config 全进 APK assets，
// 首启由 platform/storage/GameAssetInstaller 自行解包，不接 adb、不推文件。
//
// 缺省**开**（产品判据是「一条命令出能玩的包」），三个出口：
//   -PeratwSkipAssets=1      日常开发打包：一个随包条目都不搬（APK 回到 ~4 MB）；
//   -PeratwGameRoot=<目录>   显式指定游戏根（缺省读环境变量 ERATW_GAMEDIR，AGENTS 第 5 节已导出）；
//   -PeratwErbcDir=<目录>    显式指定 erbc 产物目录（缺省读 ERATW_ERBC_DIR，再兜底 erbcc 的
//                            tools/erbcc/build/erbc-out/erbc.v1）；两处都没有就只随包语料，
//                            并打出「这个 APK 装上也玩不了」的告警（不许静默出残包）。
// 缺产物**不失败**（CI 与新人 clone 直接 assembleDebug 要能过），要「必须完整」就用
//   -PeratwRequireComplete=1 —— 组长出正式包时用它，缺任一类条目当场挡住。
//
// 新鲜度（CMP-66）：给了 erbc 目录就必须能自证「出自当前编译器 + 当前语料」——按
// erbcc 的 manifest 对账 version / build-mode（受限档拒随包）/ 容器版本 / program.erbc
// 摘要 / 逐文件源 sha / **编译器指纹 compiler-digest**（编译器源码改过没重编 ⇒ 拒）。
// 显式 `-PeratwErbcDir` 不符即**失败**；兜底目录沿用「警告 + report
// 标记」的旧政策，正式包由 -PeratwRequireComplete 挡。fixture 流程（伪产物没有真
// manifest）用 -PeratwAllowStaleErbc=1 放行，该开关与 -PeratwRequireComplete 互斥。
//
// 清单 ↔ 随包对账（CMP-74）：erbcc 的 `manifest.txt`（manifest-format 2）登记产物目录里的
// program.erbc / program.meta / program.stmt.idx；分片在打包期才发生，所以本任务在随包清单
// 末尾追加 `manifest-source package` 段（package-artifact 逐文件、container-artifact 整容器、
// container-shards + shard 逐片 sha256），并把清单自身的 sha 写进 `package.report` 的
// `manifest-sha256`。`tools/apkpack/inspect_apk.py` 据此三方对账（清单/parts/实包）。
// 旧产物（无 manifest-format 行）由本任务补标记后照常出包，但建议重编拿新格式。
//
// 出正式包（AGENTS 第 5 节的 env 都导出的话，游戏根不用再传 -PeratwGameRoot）：
//   ./gradlew --configure-on-demand :app:assembleDebug -PeratwRequireComplete=1
//   python tools/apkpack/inspect_apk.py --require-bundled
// 机制自证（不跑 328 MB 全量编译时，用伪游戏根把分片/清单/STORED/幂等跑一遍）：
//   python tools/apkpack/make_fixture.py
//   ./gradlew --configure-on-demand :app:assembleDebug -PeratwGameRoot=<fixture>/game \
//       -PeratwErbcDir=<fixture>/game/erbc.v1 -PeratwErbcChunkMb=1 -PeratwAllowStaleErbc=1
//   python tools/apkpack/inspect_apk.py --require-bundled
// 日常小包：./gradlew --configure-on-demand :app:assembleDebug -PeratwSkipAssets=1
//
// 体积测算（2026-10-21 在 HOMEJIA 实测量；数字都在 :app:clean 之后取，增量打包会留下
// 上一版大条目造成的空洞、读数虚高）：
//   程序产物 STORED        378,023,628 B（program.erbc 328,799,098 + program.meta 49,224,530）
//   resources/            327,799,249 B（3,399 个文件，2,547 webp + 592 png 为主，最大单条 9.1 MB）
//   sound/                  6,658,822 B（52 个文件，含 18 个无后缀零字节 `bgm` 标记）
//   CSV/                      567,311 B（222 个文件，含 19 个 .als）
//   plugins/                1,350,000 B（8 个文件：MAP_FROMXML/DT_FROMXML 运行期要读的 XML + 两个 dll）
//   emuera.config + 兜底        4,075 B
//   ⇒ 随包合计            713,052,089 B（679.9 MiB）
//   ⇒ 打包开销实测 5,245,013 B（dex + resources.arsc + 签名 + 1.1 MB 自带字体）
//   ⇒ **全 STORED 随包 APK 实测 718,297,161 B（685.0 MiB），一条命令 54 秒出包**
//     （2026-10-21 在 HOMEJIA：真语料 + 按真产物字节数做的伪程序 328,799,098 + 49,224,530 B；
//      伪程序只为量体积与验机制，正式包必须换 :tools:erbcc:compileCorpus 的真产物）
//   ⇒ 「APK ≤ 420 MB」与「程序条目 STORED」在算术上不能同时成立：只有把程序条目改
//     DEFLATE（-PeratwErbcCompress，实测 378 MB → 84,747,684 B）才落到 **约 425 MB**，
//     离 420 还差 5 MB；且压缩条目拿不到 `openFd` 长度 ⇒ 解包侧退化成「目标存在即跳过」，
//     **APK 升级后旧产物不会被换掉**（见 GameAssetInstaller.needsCopy）。所以缺省走 STORED：
//     体积换正确性，上限由 `package.report` 自描述、由 tools/apkpack/inspect_apk.py 逐类对账卡。
//     要出 420 MB 那一档必须显式加 -PeratwErbcCompress，核验时配 --allow-compressed。
// ===========================================================================

/**
 * APK 里除随包字节之外的固定开销余量（dex + resources.arsc + 签名 + 目录项 + 自带字体）。
 * 实测 5,245,013 B（2026-10-21，718,297,161 − 713,052,148）；给 24 MiB，
 * 够 release 开 minify 与将来加 `.so` 时用。
 */
val apkOverheadAllowance = 24L * 1024L * 1024L

/**
 * 随包清单（`erbc.v1/manifest.txt`）认识的格式版本，与 erbcc 的
 * `ErbcArtifacts.MANIFEST_FORMAT` **同值**：app 的 buildscript classpath 上没有 erbcc 的
 * 编译产物（配置期也拿不到那个对象），所以只能在这里写真值；改格式要两边一起改（CMP-74）。
 *
 * - 缺 `manifest-format` 行 = 旧格式 v1（`artifacts 1` 只登记 program.erbc）；
 * - 大于本值 = 打包脚本还不认识的未来格式 ⇒ 拒绝，而不是猜字段。
 */
val eratwManifestFormat = 2

/** 缺省构建里没有这些产物；显式传了 `-PeratwGameRoot` / `-PeratwErbcDir` 就必须存在（那是用户意图）。 */
fun requireDir(label: String, path: String): File {
    val dir = File(path)
    if (!dir.isDirectory) throw GradleException("$label 不是目录：$dir")
    return dir
}

val skipBundledAssets = providers.gradleProperty("eratwSkipAssets").isPresent
// 程序条目（program.erbc / program.parts / program.meta）是否允许 DEFLATE。缺省 false = STORED。
val erbcCompressed = providers.gradleProperty("eratwErbcCompress").isPresent
// 正式包用：随包五类条目缺任一项就 fail，免得静默出一个「装上也玩不了」的包。
val requireComplete = providers.gradleProperty("eratwRequireComplete").isPresent
// BADAPPLE 字符画帧（`bad/`，6573 个 txt、44.6 MB）只有 TITLE.ERB 的 @BADAPPLE 演示会读，
// 而演示在本移植缺省关闭（`#DIM CONST TITLE_DEMO_ENABLED = 0`）⇒ 打开这个开关可以把它
// 从随包目录里剔掉：APK 少 44.6 MB，云测上传与首启解包都更快。想恢复演示时去掉开关即可
// （GameAssetInstaller 对缺失的 `bad/` 目录是容忍的：assets.listDir 返回空表即跳过）。
val skipBadFrames = providers.gradleProperty("eratwSkipBadFrames").isPresent

val gameRootProp = providers.gradleProperty("eratwGameRoot").orNull
val gameRootEnv = providers.environmentVariable("ERATW_GAMEDIR").orNull
val erbcDirProp = providers.gradleProperty("eratwErbcDir").orNull
val erbcDirEnv = providers.environmentVariable("ERATW_ERBC_DIR").orNull

val bundledGameRoot: File? = when {
    skipBundledAssets -> null
    gameRootProp != null -> requireDir("eratwGameRoot", gameRootProp)
    gameRootEnv != null && File(gameRootEnv).isDirectory -> File(gameRootEnv)
    else -> null
}
val bundledErbcDir: File? = when {
    skipBundledAssets -> null
    erbcDirProp != null -> requireDir("eratwErbcDir", erbcDirProp)
    erbcDirEnv != null && File(erbcDirEnv).isDirectory -> File(erbcDirEnv)
    // 兜底找 erbcc 的输出：`:tools:erbcc:compileCorpus` 的缺省落 `tools/erbcc/build/erbc-out/erbc.v1`，
    // 而 erbcc 自己的 `--out` 帮助文案写的是 `tools/erbcc/erbc-out/`（Main.kt:60）—— 两处都试，
    // 免得「明明编了却没随包」这种问题要靠人肉猜路径。
    else -> listOf(
        File(projectDir, "../eraKcore/tools/erbcc/build/erbc-out/erbc.v1"),
        File(projectDir, "../eraKcore/tools/erbcc/erbc-out/erbc.v1"),
        File(projectDir, "../tools/erbcc/build/erbc-out/erbc.v1"),
        File(projectDir, "../tools/erbcc/erbc-out/erbc.v1"),
    ).firstOrNull { it.isDirectory }
}
val erbcChunkMiB = providers.gradleProperty("eratwErbcChunkMb").orNull?.trim()?.toIntOrNull() ?: 0

/**
 * erbc 目录是不是**兜底找到的** erbcc 输出（既没给 `-PeratwErbcDir` 也没给有效的 `ERATW_ERBC_DIR`）。
 *
 * 兜底目录里的产物是「上一次谁编的」—— staged 任务的输入只是目录内容，**不会触发编译**，
 * 所以陈旧 `.erbc` 会被静默随包（`program.meta` 里的 `program-sha256` 只能证明 erbc/meta
 * 互相配对，证明不了它出自当前源码）。兜底档因此：正式包（`-PeratwRequireComplete`）直接
 * 拒绝，调试包留警告；显式 pin 的目录保持手动（跨 lane 复用同一份产物是刻意行为），
 * 三个档的来源与产物指纹都写进 `package.report`。
 */
val erbcDirIsFallback = bundledErbcDir != null && erbcDirProp == null &&
    (erbcDirEnv == null || !File(erbcDirEnv).isDirectory)

/**
 * 陈旧产物放行开关（**只给 fixture / 体积测算用**）：跳过下面对 manifest 的 version / 源 sha 对账。
 *
 * `tools/apkpack/make_fixture.py` 造的伪产物没有真 manifest（只有 `file ERB/... 12` 这种假行），
 * 而那个生成器不在本波可改面里；不开口子就会把文档里的 fixture 出包流程打断。
 * 与 `-PeratwRequireComplete` 互斥：正式包不许既要求完整、又放行无法证明新鲜的产物。
 */
val allowStaleErbc = providers.gradleProperty("eratwAllowStaleErbc").isPresent

/** eraKcore 姊妹仓（settings.gradle.kts 的 includeBuild 目标）：必须与本仓同级检出。 */
val eraKcoreHome: File = File(rootProject.projectDir, "../eraKcore")

/** erbcc 版本单源（core-vm 的 Version.kt）：优先 eraKcore 姊妹仓。 */
val erbccVersionSrc: File = listOf(
    File(eraKcoreHome, "core-vm/src/main/kotlin/com/eratw/port/core/Version.kt"),
    rootProject.file("core-vm/src/main/kotlin/com/eratw/port/core/Version.kt"),
).firstOrNull { it.isFile }
    ?: throw GradleException("找不到 core-vm 的 Version.kt：请把 eraKcore 仓库检出在本仓同级目录（../eraKcore）")

/** core-vm Kotlin 源根（CMP-66 编译器指纹用）。 */
val coreVmKotlinSrc: File = listOf(
    File(eraKcoreHome, "core-vm/src/main/kotlin"),
    rootProject.file("core-vm/src/main/kotlin"),
).firstOrNull { it.isDirectory }
    ?: throw GradleException("找不到 core-vm 源码根：请把 eraKcore 仓库检出在本仓同级目录（../eraKcore）")

/**
 * 当前 erbcc 版本的单源 = core-vm 的 `Version.kt` 的 `const val VERSION`。
 *
 * 为什么读源码而不是复用某个常量：app 的 buildscript classpath 上没有 core-vm 的编译产物
 * （配置期也拿不到 `com.eratw.port.core.Version` 这个对象），而再手写一遍版本串必然漂移。
 * 这个文件是 `Version` 的唯一落点（`Version.NAME` 在下一行，正则只认 `VERSION`）。
 */
fun expectedErbccVersion(): String {
    val src = erbccVersionSrc
    if (!src.isFile) {
        throw GradleException("找不到版本单源 ${src}：CMP-66 的 version 对账没有判据")
    }
    val m = Regex("""\bVERSION\s*:\s*String\s*=\s*"([^"]+)"""").find(src.readText())
    return m?.groupValues?.get(1)
        ?: throw GradleException("无法从 ${src} 解析 Version.VERSION（CMP-66 的 version 对账没有判据）")
}

/** 文件 SHA-256 小写十六进制（流式，328 MB 的 program.erbc 不整份进堆）。 */
fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    RandomAccessFile(file, "r").use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * 编译器指纹（CMP-66 的另一半）：与 erbcc 的 `ErbcCompilerDigest` **同口径** ——
 * 取 `core-vm/src/main/kotlin` 整树 `.kt`（编译链跨 compile/code/expr/lexer/stmt/vars 等包，
 * 逐包枚举会漏），按相对**仓库根**的路径（`\`→`/`）字典序，逐文件
 * `u32be(路径字节数) ‖ 路径 UTF-8 ‖ u32be(文件字节数) ‖ 文件字节` 喂 SHA-256。
 *
 * 为什么在构建脚本里重写一遍而**不**依赖 erbcc 的类：app 的 buildscript classpath 上没有
 * erbcc/core-vm 的编译产物（同 [expectedErbccVersion] 的处境），而给 `stageGameAssets` 挂
 * 跨项目 `dependsOn` 会撞 classloader scope 竞态（见 [verifyErbcFreshness] 的说明）。
 * **换文件集或换编码口径必须两处一起改**（erbc 的 `ErbcCompilerDigest.kt` 与本函数），
 * 否则清单与打包期会互相判红。
 *
 * @return digest（小写十六进制）与参与计算的文件数
 */
fun compilerDigestOf(rootDir: File): Pair<String, Int> {
    val srcRoot = File(rootDir, "core-vm/src/main/kotlin")
    if (!srcRoot.isDirectory) {
        throw GradleException("找不到编译器源码树 $srcRoot：compiler-digest 没有判据")
    }
    val files = srcRoot.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .map { it.toRelativeString(rootDir).replace('\\', '/') to it }
        .sortedBy { it.first }
        .toList()
    if (files.isEmpty()) {
        throw GradleException("编译器源码树 $srcRoot 里没有 .kt 文件：compiler-digest 没有判据")
    }
    val digest = MessageDigest.getInstance("SHA-256")
    for ((rel, file) in files) {
        val relBytes = rel.toByteArray(Charsets.UTF_8)
        val bytes = file.readBytes()
        digest.update(ByteBuffer.allocate(4).putInt(relBytes.size).array())
        digest.update(relBytes)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } to files.size
}

/**
 * CMP-66：显式 erbc 目录的**新鲜度对账**（`-PeratwErbcDir` / `ERATW_ERBC_DIR`，以及兜底目录）。
 *
 * 背景：`stageGameAssets` 刻意不挂 `dependsOn(:tools:erbcc:compileCorpus)`
 * （configure-on-demand 下跨项目依赖会撞 classloader scope，2026-10-22 实测），所以它只能
 * 「读现成目录」。兜底目录已有 `-PeratwRequireComplete` 拒绝，但**显式 pin 的目录连 version
 * 与源 sha 都不对账** ⇒ 改过编译器代码或语料之后照样把旧产物打进包（BOARD CMP-66）。
 *
 * 判据全部来自 erbcc 落盘时写的确定性 `manifest.txt`（[ErbcArtifacts.manifestText]）：
 * 1. **version**：`erbcc <v>` 必须等于当前源码的 `Version.VERSION`；`build-mode` 必须是
 *    `full`（`--limit` 出来的受限产物不得随包）；`container-version` 必须与
 *    `program.erbc` 头里的真实版本一致（magic `ERBC` + u16 小端，防截断/串包）。
 * 2. **产物自证**：`artifact erbc.v1/program.erbc <bytes> <sha256>` 与磁盘字节逐位一致。
 * 3. **源 sha**：`source <rel> <bytes> <sha256>` 与语料根的**当前**文件逐个对账，文件集
 *    （增/删）也必须一致 —— 语料改过而没重编 ⇒ 拒绝。
 * 4. **编译器指纹**：`compiler-digest` 必须等于当前 `core-vm/src/main/kotlin` 整树重算值
 *    （[compilerDigestOf]）—— 编译器改过而没重编 ⇒ 拒绝。缺这一行（旧格式/旧编译器产物）
 *    同样拒绝：语料摘要与产物摘要在「只有编译器换了」时全都对得上，这一行是唯一判据。
 *
 * @param corpusRoot 优先用调用方给的「本次随包语料根」（`<gameRoot>/ERB`）；给不出时退回
 *   manifest 里登记的 `corpus-root`（编译机路径，同一台机器上可用）。
 * @param hard true = 显式 pin（用户意图就是「用这一份」），任何不符直接 [GradleException]；
 *   false = 兜底目录（保持既有政策：警告 + `package.report` 标记，正式包由
 *   `-PeratwRequireComplete` 在别处挡）。
 * @return 写进 `package.report` 的 `erbc-freshness` 值；null = 未能验证（已按 hard 处理）。
 */
fun verifyErbcFreshness(dir: File, corpusRoot: File?, hard: Boolean): String? {
    fun reject(msg: String): String? {
        if (hard) throw GradleException("CMP-66 新鲜度对账失败：$msg")
        project.logger.warn("stageGameAssets: $msg")
        return null
    }

    val manifest = File(dir, "manifest.txt")
    if (!manifest.isFile) {
        return reject(
            "erbc 目录缺 manifest.txt（$dir）→ 无法证明产物出自当前编译器/当前语料；" +
                "先跑 ./gradlew :tools:erbcc:compileCorpus，或用 -PeratwErbcDir 指向最新产物目录",
        )
    }
    val lines = manifest.readLines()
    fun field(name: String): String? =
        lines.firstOrNull { it.startsWith("$name ") }?.removePrefix("$name ")?.trim()

    // ---- 0. 清单格式（CMP-74）-----------------------------------------------
    // 缺 `manifest-format` = 旧格式 v1（只有 `artifacts 1`，侧车不在表里）：宽松放行，
    // 随包清单由本任务补 package 段；比本脚本新的格式则拒绝 —— 未来字段不可猜。
    val manifestFormat = field("manifest-format")?.toIntOrNull()
    if (manifestFormat != null && manifestFormat > eratwManifestFormat) {
        return reject(
            "manifest 格式 ${manifestFormat} 比本打包脚本认识的 ${eratwManifestFormat} 新：" +
                "升级打包脚本后再出包（旧脚本猜新字段会把清单对账跳过）",
        )
    }
    if (manifestFormat == null) {
        project.logger.warn(
            "stageGameAssets: manifest 无 manifest-format 行（旧格式 v1，artifacts 只登记 program.erbc）：" +
                "随包清单会按实际文件补 package 段；建议重跑 ./gradlew :tools:erbcc:compileCorpus 拿新格式",
        )
    }

    // ---- 1. version ---------------------------------------------------------
    val expected = expectedErbccVersion()
    val declared = field("erbcc")
    if (declared != expected) {
        return reject(
            "产物版本与当前编译器不一致：manifest 记 erbcc ${declared ?: "(缺)"}，源码是 $expected → " +
                "改过编译器就必须重编产物（./gradlew :tools:erbcc:compileCorpus）",
        )
    }
    val buildMode = field("build-mode")
    if (buildMode != "full") {
        return reject(
            "产物不是全量档（build-mode ${buildMode ?: "(缺；旧 manifest)"}）：受限产物（--limit N）不得随包，" +
                "请用 :tools:erbcc:compileCorpus 的全量产物",
        )
    }

    // ---- 1b. 编译器指纹 -----------------------------------------------------
    // 语料没改、产物自洽，唯独编译器源码换了（红 J 的 NEXT/STEP 死循环就是这样混进包的）：
    // 上面的 version 行只认 erbcc 的版本串，改编译器实现不动 VERSION 时它照样放行。
    val declaredCompilerDigest = field("compiler-digest")
    if (declaredCompilerDigest == null) {
        return reject(
            "manifest 没有 compiler-digest 行（旧编译器/旧格式产物）→ 无法证明产物出自当前编译器；" +
                "先跑 ./gradlew :tools:erbcc:compileCorpus 再出包",
        )
    }
    val (currentCompilerDigest, compilerFileCount) = compilerDigestOf(eraKcoreHome)
    if (!declaredCompilerDigest.equals(currentCompilerDigest, ignoreCase = true)) {
        return reject(
            "产物由旧编译器产出：manifest compiler-digest $declaredCompilerDigest" +
                "（compiler-sources ${field("compiler-sources") ?: "(缺)"} 个），当前编译器源码树 " +
                "$currentCompilerDigest（$compilerFileCount 个）→ 先跑 ./gradlew :tools:erbcc:compileCorpus 再出包",
        )
    }
    val program = File(dir, "program.erbc")
    if (!program.isFile) return reject("erbc 目录缺 program.erbc：$dir")
    val declaredContainerVersion = field("container-version")?.toIntOrNull()
    val head = ByteArray(6)
    try {
        RandomAccessFile(program, "r").use { it.readFully(head) }
    } catch (e: Exception) {
        return reject("program.erbc 读不出 6 字节头（${e::class.simpleName}: ${e.message}）：$program")
    }
    val headMagic = String(head, 0, 4, Charsets.US_ASCII)
    if (headMagic != "ERBC") {
        return reject("program.erbc 头 magic 不是 ERBC（实际 '${headMagic.escape()}')：$program")
    }
    val headVersion = (head[4].toInt() and 0xff) or ((head[5].toInt() and 0xff) shl 8)
    if (declaredContainerVersion == null || declaredContainerVersion != headVersion) {
        return reject(
            "manifest 的 container-version（${declaredContainerVersion ?: "(缺)"}）与 program.erbc 头里的版本" +
                "（$headVersion）不一致：清单与产物不同源",
        )
    }

    // ---- 2. 产物自证 --------------------------------------------------------
    val artifact = lines.firstOrNull { it.startsWith("artifact ") }?.split(' ')
    if (artifact == null || artifact.size != 4 || !artifact[1].endsWith("program.erbc")) {
        return reject("manifest 缺 `artifact ... program.erbc` 条目：$manifest")
    }
    val programSha = sha256Of(program)
    val declaredArtifactSha = artifact[3]
    if (!declaredArtifactSha.equals(programSha, ignoreCase = true)) {
        return reject("program.erbc 与 manifest 登记的 sha256 不一致：清单 $declaredArtifactSha，实际 $programSha")
    }
    val declaredArtifactBytes = artifact[2].toLongOrNull()
    if (declaredArtifactBytes != program.length()) {
        return reject("program.erbc 大小与 manifest 登记不一致：清单 ${declaredArtifactBytes ?: "?"}，实际 ${program.length()}")
    }

    // ---- 3. 源 sha（语料文件集 + 逐文件摘要）--------------------------------
    val sources = lines.filter { it.startsWith("source ") }.mapNotNull { line ->
        // 行形 `source <相对路径> <字节数> <sha256>`，路径里可能有空格（eratw 语料里
        // `DLC/DLC FUNCTION.ERB`、`MOVEMENTS/自由行動/FREE_ACT_19 抱卵.ERB` 都是），
        // 所以必须**从右往左**切：末段是摘要、倒数第二段是字节数，其余整段是路径。
        //
        // 旧实现 `split(' ', limit = 4)` 是从**左**切，`p[1]` 只拿到路径的第一个词
        // （`DLC/DLC`），`p[2]` 拿到 `FUNCTION.ERB` 而不是字节数 ⇒ 该条被当成坏行。
        // 更糟的是这种「截断后的路径」会大量重复，`declaredSet` 去重后从 3971 塌成 1738，
        // 报出来的错是「清单 1738 / 磁盘 3971 → 语料改过但没重编」，**把真因指向语料**，
        // 实际语料一个字都没动（2026-10-26 出 release 包时实测）。
        // 判据与 erbcc 侧 `ArtifactFreshness.entry` 逐字同口径（那边一开始就是右切的）。
        val rest = line.removePrefix("source ")
        val shaSep = rest.lastIndexOf(' ')
        if (shaSep <= 0) return@mapNotNull null
        val sizeSep = rest.lastIndexOf(' ', shaSep - 1)
        if (sizeSep <= 0) return@mapNotNull null
        val bytes = rest.substring(sizeSep + 1, shaSep).toLongOrNull() ?: return@mapNotNull null
        Triple(rest.substring(0, sizeSep), bytes, rest.substring(shaSep + 1))
    }
    val declaredCount = field("source-files")?.toIntOrNull()
    if (declaredCount == null || declaredCount != sources.size) {
        return reject("manifest 的 source 条目数（${sources.size}）与 source-files（${declaredCount ?: "(缺)"}）不一致")
    }
    val corpus = corpusRoot?.takeIf { it.isDirectory }
        ?: field("corpus-root")?.let { File(it) }?.takeIf { it.isDirectory }
    if (corpus == null) {
        project.logger.warn(
            "stageGameAssets: 找不到语料根（既没给 -PeratwGameRoot/ERATW_GAMEDIR 的有效 ERB/，" +
                "manifest 的 corpus-root 也不在本机）→ CMP-66 只验到 version/容器摘要，**源 sha 未对账**",
        )
        return "version-only(源的 sha 未对账：找不到语料根)"
    }
    val actual = corpus.walkTopDown()
        .filter { it.isFile }
        .filter { it.name.endsWith(".erb", ignoreCase = true) || it.name.endsWith(".erh", ignoreCase = true) }
        .filter { !it.name.startsWith("GOLDEN_", ignoreCase = true) }
        .map { it.toRelativeString(corpus).replace('\\', '/') }
        .sorted()
        .toList()
    val declaredSet = sources.map { it.first }.toSortedSet()
    if (actual.toSet() != declaredSet) {
        val missing = declaredSet - actual.toSet()
        val extra = actual.toSet() - declaredSet
        return reject(
            "语料文件集与产物清单不一致（清单 ${declaredSet.size} / 磁盘 ${actual.size}）：" +
                "磁盘缺 ${missing.take(3)}${if (missing.size > 3) "…" else ""}；" +
                "清单缺 ${extra.take(3)}${if (extra.size > 3) "…" else ""} → 语料改过但没重编（语料根 $corpus）",
        )
    }
    var checked = 0
    for ((rel, bytes, sha) in sources) {
        val f = File(corpus, rel)
        if (!f.isFile || bytes == null) {
            return reject("语料文件缺失或 manifest 行结构不对：source $rel")
        }
        if (f.length() != bytes) {
            return reject("语料文件大小变了：$rel（清单 $bytes，实际 ${f.length()}）→ 语料改过但没重编")
        }
        val actualSha = sha256Of(f)
        if (!actualSha.equals(sha, ignoreCase = true)) {
            return reject("语料文件 sha256 变了：$rel（清单 $sha，实际 $actualSha）→ 语料改过但没重编")
        }
        checked++
    }
    return "verified(erbcc $expected, compiler ${currentCompilerDigest.take(12)}, " +
        "container v$headVersion, program $programSha, 源 $checked 个文件来自 $corpus)"
}

/** manifest 里的版本串可能带控制字符；报错文案里把不可见字符转义，免得日志里看不见差异。 */
fun String.escape(): String = buildString {
    for (c in this@escape) {
        if (c.code in 32..126) append(c) else append("\\u%04x".format(c.code))
    }
}
val bundlingEnabled = !skipBundledAssets && (bundledGameRoot != null || bundledErbcDir != null)

/**
 * 随包要递归落盘的语料目录（与 `GameAssetInstaller.DATA_DIRS` 同源，顺序也一致）。
 *
 * `plugins/` 是**运行期**读的，不是编译期：`ERB/SYSTEM.ERB:96-97` 的
 * `MAP_FROMXML CSV_MAP,LOADTEXT("plugins/tw_csv_chs.xml")` 在每次新开局跑一遍
 * （`@INIT_NG_OR_LOAD`），`plugins/schema.xml`+`bbas_*.xml` 供 `DT_FROMXML`。
 * 少了这个目录 `LOADTEXT` 读到空串 → `MAP_FROMXML` 在位置 0 抛
 * 「XML 解析错误」→ 开局直接死在 `@INIT_NG_OR_LOAD`（2026-10-22 真机实证）。
 * 只有 1.3 MB，全量随包。
 *
 * `bad/` 是 BADAPPLE 彩蛋的**文本帧**（不是图片）：`ERB/TITLE.ERB:123-158` 的 `@BADAPPLE`
 * 每帧 `LOADTEXT("bad/bad_apple{count}.txt")`（count 1..6573）。目录不在白名单时 LOADTEXT
 * 得空串，DEMO 空帧全速循环——真机上表现为「首页被清空 + 高频抖动」（2026-10-22
 * DevTools 实证：displayLines 里 `<font face='黑体'></font></p>` 空帧行）。55 MB / 6,572 个
 * 小 txt，全量随包；首启解包文件数因此 +6,572（684 MiB / 3,683 文件的已知口径相应放大）。
 */
val stagedDataDirs = buildList {
    add("CSV")
    add("resources")
    add("sound")
    add("plugins")
    // `bad/` 只服务已关闭的演示；`-PeratwSkipBadFrames=1` 时不随包（省 44.6 MB）。
    if (!skipBadFrames) add("bad")
}

/** 随包的游戏根配置文件；`emuera.config` 是 GameStorage 的「像游戏根」标志文件。 */
val stagedConfigFiles = listOf("emuera.config", "default.config", "fixed.config")

/**
 * 字体后缀**永不** STORED：`.ttf` 1.1 MB 压缩后 0.35 MB，而 `Typeface.createFromAsset`
 * 吃压缩条目完全正常（实测 noCompress 加 ttf 会让 debug 包从 3.92 MiB 涨到 5.32 MiB，白涨 1.4 MB）。
 * 语料目录里现在没有这些后缀，写死这张表是防将来某个 `resources` 子目录塞进字体时静默掉口径。
 */
val alwaysCompress = setOf("ttf", "otf", "ttc", "woff", "woff2")

/**
 * aapt2 的 `--no-compress` **只有「按后缀匹配」一种口径**，所以后缀表必须来自真实要随包的目录：
 * 手写一份会在语料新增 `.als`（CSV 里已有 19 个）或 `.xlsx` 时静默漏掉，条目掉进 DEFLATE，
 * 首启解包因此拿不到 `openFd` 长度、幂等判定退化成「存在即跳过」。
 *
 * 配置期扫一遍是 3,451 次文件名读取（几十毫秒）。三类特殊情况一起处理：
 * - **无后缀文件**（真语料 18 个零字节 `sound/游戏bgm/<地名>/bgm` 标记）：后缀匹配够不着，
 *   于是把**文件名本身**也塞进 noCompress（aapt2 拿它当后缀比，路径以 `bgm` 结尾就命中）；
 *   命中不了也只回退到 DEFLATE，`inspect_apk.py` 会把这类条目单独列出来。
 * - **分片清单 `program.parts` 与自述报告 `package.report`**：条目只有几十字节，
 *   但 STORED 之后 `AssetManager.openFd` 能直接拿到长度，解包侧的字节对账不用退化成
 *   「目标存在即视为已装」，所以一并写进后缀表。
 * - 字体后缀（[alwaysCompress]）反向挡掉：`.ttf` 压缩后省 0.7 MB，`createFromAsset` 照常吃。
 */
fun bundledSuffixes(): Set<String> {
    val root = bundledGameRoot ?: return emptySet()
    val found = sortedSetOf("parts", "report")
    stagedDataDirs.forEach { name ->
        val dir = File(root, name)
        if (dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val suffix = f.name.substringAfterLast('.', "")
                if (suffix.isEmpty()) {
                    // 无后缀：用文件名本身当 noCompress 后缀（见上面第二条）
                    if (f.name.length <= 32 && f.name.all { c -> c.isLetterOrDigit() || c in "._-" }) {
                        found += f.name.lowercase()
                    }
                } else if (suffix.length <= 8 && suffix.all { it.isLetterOrDigit() }) {
                    val key = suffix.lowercase()
                    if (key !in alwaysCompress) found += key
                }
            }
        }
    }
    stagedConfigFiles.forEach { name -> if (File(root, name).isFile) found += "config" }
    return found
}

val stagedSuffixes: Set<String> = if (bundlingEnabled) bundledSuffixes() else emptySet()

// M4-SKEL：最小可运行外壳。无 AndroidX；minSdk 21 + core library desugaring
// 同时覆盖老机型（Android 5.0）与新系统（targetSdk 35 的 edge-to-edge）。
android {
    namespace = "com.eratw.port.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eratw.port.android"
        minSdk = 21
        targetSdk = 35
        // 0.2.0：从「能跑」到「能装给人玩」的第一版 —— 带设置页导入已有存档。
        // 0.2.1：只收修复，不带新玩法 —— REF 形参同一函数递归丢目标、死循环闸改墙钟为主、
        //        内建变量形参绑定补齐（补丁设置页不再整页「未启动」）、WAIT 系页面「继续」脚钮、
        //        画布右栏预留修选项排版、「适应屏幕」字号换代防抖、选项按钮大小四档。
        // 0.2.2：只收修复 —— 0.2.1 真机启动即崩：扁平通道关（Android 生产）时数据节按语句
        //        实例查，而波次 C 之后没人实现这条路（UnsupportedOperationException）；
        //        补「实例 → 语句池下标」反查表。
        // 0.2.3：只收修复 —— 旧版本存档里已删除的变量只走标签不建数组（读档不再被 256 MiB
        //        分配预算误判成坏档）。
        // 出包带 signingConfigs.releaseLocal 的 -PeratwRelease* 四项 = 发布章（见其 KDoc：
        // 不带那四项会静默签成 debug keystore）。换章后老内测包不能覆盖升级（要先卸载、
        // 存档随之丢失），所以签名与版本号一起在放包前定死。
        versionCode = 5
        versionName = "0.2.3"

        // 方案③ 报错上报端点（Cloudflare Worker + KV，tools/report-endpoint/）：缺省空 =
        // 上报通道整体关闭（零网络行为）；要启用在出包时带这两个 -P 参数：
        //   -PeratwReportEndpoint=https://<worker 域名> -PeratwReportToken=<wrangler secret 同值>
        resValue("string", "eratw_report_endpoint",
            providers.gradleProperty("eratwReportEndpoint").orNull ?: "")
        resValue("string", "eratw_report_token",
            providers.gradleProperty("eratwReportToken").orNull ?: "")

        // 构建标识（2026-10-28）：同一个 0.2.0 (2) 一小时内出过三个互不相同的包，
        // 报错报告只带 versionName 根本分不清崩的是哪个 APK。出包工具（devtools/
        // pack_release.py）把 git sha 以 -PeratwBuildStamp=<sha> 传进来，编译期烙进
        // BuildConfig，由 ErrorReportCollector 写进每份报告的「构建与产物标识」节。
        // 这里是**刻意的常量注入**，构建脚本绝不去调 git：配置期跑 git 会污染
        // configuration cache 的输入、离线/CI 直接挂掉。不带参数 = "unknown"，
        // 报告照写（写 unknown 也比没这行强）。
        val buildStamp = (providers.gradleProperty("eratwBuildStamp").orNull ?: "unknown")
            .replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "eratwBuildStamp", "\"$buildStamp\"")

        // M10-CLOUD 取证装置（androidTest）需要；uiautomator 只能取 2.3.0——2.4.0 的 minSdk 是 23，
        // 会在 manifest 合并处挡住本工程的 minSdk 21（runner/core 1.7.0 与 monitor 1.8.0 都是 minSdk 21）。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 资源收紧：本 APK 自带资源只有字体、主题与一张图标，没有任何 locale/密度变体。
        // locale 过滤在 aapt2 merge 阶段就丢，依赖将来带进来的多语言资源不会进包。
        // （AGP 8.8 的 defaultConfig.resourceConfigurations 已废弃，等价物是 androidResources.localeFilters。）
        // 密度：本工程没有任何 -hdpi/-xhdpi 限定目录，故无需 density 过滤，也不开 APK 分包
        // （目标是单个通用包，splits.density 会把安装步骤变复杂、收益为 0）。

        // ABI 收紧：本工程是纯 JVM/Kotlin，当前没有任何 .so（jniLibs 为空），
        // 这两条现在不改变体积，作用是挡住将来某个 AAR 带进 x86/mips 的 .so。
        // 要跑 x86_64 模拟器时加 -PeratwAbi=x86_64（逗号分隔可写多个）。
        ndk {
            abiFilters += abiList
        }
    }

    /**
     * 自用 release 签名（PERF 2026-10-26）。
     *
     * ## 为什么必须能出 release 包
     *
     * `debuggable=true` 的包在 ART 上走「解释执行 + 有限 JIT」路径：dex2oat 以
     * `verify-none` 落地、不做 AOT 编译，热循环（本工程的 ERB 脚本 VM）吞吐比
     * `debuggable=false` 低一个数量级。真机实测「点一个选项等 3~4 秒」的主因就在这里 ——
     * 脚本侧一回合要跑上千万条 VM 指令，解释器模式下每条多花几十纳秒，累起来就是秒级。
     *
     * 本工程此前**没有任何 signingConfig** ⇒ `assembleRelease` 产出的是未签名包，
     * 装不上；能装的只有 debug 包 ⇒ 用户从来没跑过 AOT 编译的代码。
     *
     * ## 为什么复用 debug keystore
     *
     * 与 `assembleDebug` 同签名 ⇒ 可以直接覆盖安装、**存档与设置原样保留**（不用卸载）。
     * 密钥不进仓（`~/.android/debug.keystore` 是 SDK 自动生成的），也不引入新的凭据。
     * 要出正式分发包时用 `-PeratwReleaseStoreFile/-PeratwReleaseStorePassword/
     * -PeratwReleaseKeyAlias/-PeratwReleaseKeyPassword` 换成自己的 keystore。
     */
    signingConfigs {
        create("releaseLocal") {
            fun prop(key: String, def: String): String =
                (providers.gradleProperty(key).orNull ?: def)
            storeFile = file(prop("eratwReleaseStoreFile", System.getProperty("user.home") + "/.android/debug.keystore"))
            storePassword = prop("eratwReleaseStorePassword", "android")
            keyAlias = prop("eratwReleaseKeyAlias", "androiddebugkey")
            keyPassword = prop("eratwReleaseKeyPassword", "android")
        }
    }

    buildTypes {
        release {
            // R8 关掉：本工程没有反射密集的第三方库，混淆对性能没有正收益，
            // 反而会让真机 trace / `sample` 采样里的方法名不可读（排障口径优先）。
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("releaseLocal")
        }
        debug {
            // 显式写死，防将来有人把缺省改成 true 之后又踩回解释器路径。
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // core-vm 的 java.nio.file（Files/Path）与 java.time 在 API 21 上靠它补齐
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        // 错误报告的「构建标识」来自生成的 BuildConfig（见 defaultConfig 的
        // buildConfigField("String", "eratwBuildStamp", ...)），必须开。
        buildConfig = true
        // 壳层 Compose（2026-10-23）：游戏画面仍走 CanvasConsoleView，只壳层用 Composable。
        compose = true
    }

    // 打包口径（AGP 8.8 起 aaptOptions 已废弃，等价项都落在 androidResources / packaging）。
    androidResources {
        // 只要中文/英文资源（系统 locale 之外的兜底）。当前工程没有多语言资源，此条对体积是零收益，
        // 作用是把「依赖不许带进整张 locale 表」写死在构建里。
        localeFilters += listOf("zh", "en")
        // 随包条目一律 STORED：图片（webp/png/jpg）与音频（mp3/ogg）本来就已压缩，
        // 二次压缩只白涨构建时间；程序产物按上面的测算缺省也 STORED（首启纯拷贝、
        // 且解包侧能用 `openFd` 拿到字节数做「按大小判重拷」）。
        // webp/ogg 在 aapt2 默认表里本来就不压，这里显式写出，防止 AGP 换默认表后悄悄回退。
        // 字体不进来（见 alwaysCompress）；后缀表来自配置期真实扫描（见 bundledSuffixes）。
        val noCompressList = linkedSetOf("webp", "ogg")
        if (!erbcCompressed) noCompressList += listOf("erbc", "meta")
        noCompressList += stagedSuffixes
        noCompress += noCompressList.toList()
        // aapt2 的默认忽略表（不改行为，只把口径钉在这里，避免升级后默认值漂移）。
        // 注意 `<dir>_*` 只挡**目录**：语料里 `CSV/_default.config`、`resources/isaac/cg/_cg.xml`
        // 这类下划线开头的**文件**是游戏要读的，2026-10-21 实包验证它们照常进 assets。
        ignoreAssetsPattern =
            "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    packaging {
        resources {
            // 许可证/构建期元数据不进包：debug 包 26 条 entry 里这类占的是零头，
            // 但 release 打开 minify 后它们会是仅剩的可去掉项。
            excludes += listOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.version",
                "kotlin/**",
                "**/*.kotlin_builtins",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // eraKcore（composite build，见 settings.gradle.kts 的 includeBuild + dependencySubstitution）
    implementation("com.eratw.port:core-vm")
    implementation(project(":render-canvas"))
    implementation(project(":platform"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    // 壳层 Compose：只用于输入条 / 右侧选项栏 / 覆盖层 / 设置入口；游戏画面仍是
    // CanvasConsoleView（经 AndroidView 嵌入），渲染与命中路径零改动。
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    // M10-CLOUD 取证装置。uiautomator 锁 2.3.0：2.4.0 的 minSdk 是 23，会在 manifest 合并处
    // 挡住本工程（minSdk 21）。runner 1.7.0 以 compile 作用域带入 junit，故不单独声明。
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    // 壳层布局回归（如右侧选项栏的画布预留宽）：纯 JVM 单测即可覆盖，不需要设备。
    testImplementation("junit:junit:4.13.2")
    // T2-63：把随包的 baseline profile 在「非 Android 12」机型上真正落地。
    //
    // APK 里的 `assets/dexopt/baseline.prof` 只有 API 31+（Android 12）由 dex2oat 在**安装时**
    // 自动消费；API 24~30 上系统不会去读它。profileinstaller 在首启后把 profile 写进
    // `<app>/files/profileinstaller/` 并触发一次后台 dexopt，让 24~30 也能拿到同一份热点表。
    // API 21~23 没有 profile 支持的 ART 通道，库自身会空转（不影响启动）。
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}

// ===========================================================================
// T2-63：ART Profile（`.prof`）→ `baseline-prof.txt` 转换器
//
// 为什么自己写这个任务：契约要求「跑四条真实游玩路径生成 profile」，而**采集**与**转换**
// 是两件事。采集只能上真机（`adb shell am profile start --sampling 100 -p <包名>`），
// 产出的 `.prof` 是 ART 自己的二进制格式（含 dex 校验和、方法索引），人读不了；AGP 只在
// **构建期**把「人可读的 baseline-prof.txt」编成 `assets/dexopt/baseline.prof`，没有反向工具。
//
// 转换靠 `profgen`——它没有公开的 Maven 坐标，但**完整地打在 AGP 自己的 jar 里**
// （`com.android.tools.profgen`，108 个类，AGP 8.8.2 实测）。本任务与 AGP 同处 buildscript
// classpath，运行期一定加载得到，因此不引入任何新依赖（调用方式见 convert() 里的反射说明）：
//   - `DexDataKt.Apk(File, String)`        读 APK 里的 classes*.dex（拿方法表与校验和）
//   - `ArtProfileKt.ArtProfile(File)`      读 ART 二进制 profile
//   - `ProfileDumperKt.dumpProfile(...)`   按 dex 方法表还原成 `HSPL...;->m()V` 文本
//
// ⚠️ 调用方必须传**生成该 profile 的同一个 APK**：ArtProfile 里记的是 dex 校验和，
// 换包（哪怕只改了 KB 级代码）都会让还原出来的方法表错位——profgen 会按校验和拒绝，
// 这就是我们要的行为（宁可报错，不可静默产出错表）。
//
// 用法（真机采集完把 .prof 拉回本机后）：
//   ./gradlew --configure-on-demand :app:convertArtProfile \
//       -PeratwArtProfile=D:/DSH/t63.prof \
//       -PeratwArtApk=app/build/outputs/apk/release/app-release.apk
// 缺省输出到 `app/src/main/baseline-prof.txt`（AGP 会把该文件编进 assets/dexopt/baseline.prof）。
// ===========================================================================
abstract class ConvertArtProfileTask : DefaultTask() {

    /** ART 采样 profile（`am profile stop` 之后从设备拉回来的那个文件）。 */
    @get:InputFile
    abstract val profileFile: RegularFileProperty

    /** 生成该 profile 的**同一个** APK（提供 dex 方法表）。 */
    @get:InputFile
    abstract val apkFile: RegularFileProperty

    /** 输出的可读 profile；AGP 只认 `src/main/baseline-prof.txt` 这个位置。 */
    @get:OutputFile
    abstract val outFile: RegularFileProperty

    @TaskAction
    fun convert() {
        // 为什么用反射而不是直接 new：Gradle 给 build script 的**编译** classpath 是收窄过的
        // `gradle-api`（2026-10-27 实测：直接写 `com.android.tools.profgen.DexDataKt` 报
        // Unresolved reference），但**运行**期 profgen 一定在——AGP 的 jar 就在本脚本的
        // classloader 上。所以用 `Class.forName` 拿类、按「名字 + 参数个数 + 首参类型」定位
        // 那几个 Kotlin 文件门面（`*Kt`）上的静态方法。
        val loader: ClassLoader = try {
            Class.forName("com.android.tools.profgen.DexDataKt").classLoader
        } catch (_: Throwable) {
            // 兜底：`plugins {}` 解析出来的 AGP 不挂在 buildscript 的 `classpath` 配置上时，
            // 显式把 buildscript classpath 拼成一个 URLClassLoader 再试一次。
            val cp = project.buildscript.configurations.getByName("classpath").files
            URLClassLoader(
                cp.map { it.toURI().toURL() }.toTypedArray(),
                ClassLoader.getSystemClassLoader(),
            )
        }
        fun find(owner: String, name: String, firstParam: Class<*>, arity: Int): java.lang.reflect.Method {
            val cls = loader.loadClass(owner)
            return cls.methods.firstOrNull {
                it.name == name &&
                    it.parameterCount == arity &&
                    firstParam.isAssignableFrom(it.parameterTypes[0])
            } ?: throw GradleException(
                "profgen 里找不到 $owner.$name(首参 $firstParam, 共 $arity 个参数)；" +
                    "AGP 版本升级后 ABI 可能变了——照 BOARD 报红，别静默跳过",
            )
        }
        // 1) 读 APK 的 dex 方法表（profile 里只存方法索引，必须靠它还原名字）
        val apk = find("com.android.tools.profgen.DexDataKt", "Apk", File::class.java, 2)
            .invoke(null, apkFile.get().asFile, "app")
        val dexes = apk!!.javaClass.getMethod("getDexes").invoke(apk) as List<*>
        logger.lifecycle("convertArtProfile: APK 含 ${dexes.size} 个 dex")
        // 2) 读 ART 二进制 profile
        val art = find("com.android.tools.profgen.ArtProfileKt", "ArtProfile", File::class.java, 1)
            .invoke(null, profileFile.get().asFile)!!
        val data = art.javaClass.getMethod("getProfileData").invoke(art) as Map<*, *>
        val names = data.keys.map { it!!.javaClass.getMethod("getName").invoke(it) as String }
        logger.lifecycle("convertArtProfile: profile 覆盖 dex $names")
        // ObfuscationMap 在 profgen 的签名里是**非空**参数（传 null 会被 Kotlin 的入参检查当场抛
        // NullPointerException: Parameter specified as non-null is null）。本工程 release 不开 R8，
        // 没有映射表，给一个空映射即可（等价于 Kotlin 侧的 `ObfuscationMap.empty`）。
        val emptyObf = loader.loadClass("com.android.tools.profgen.ObfuscationMap")
            .getConstructor(List::class.java)
            .newInstance(emptyList<Any>())
        // 3) 还原成 HSP 规则文本；末位 `false` = 不按 R8 映射重写（release 未开 minify，名字即 dex 名）
        outFile.get().asFile.parentFile?.mkdirs()
        val dumper = find("com.android.tools.profgen.ProfileDumperKt", "dumpProfile", File::class.java, 5)
        try {
            dumper.invoke(null, outFile.get().asFile, art, apk, emptyObf, false)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // 反射会把 profgen 自己的异常包一层；不拆开就只剩一句 InvocationTargetException，
            // 排障时完全看不出是「参数为 null」「dex 校验和对不上」还是别的。
            val cause = e.targetException
            throw GradleException(
                "convertArtProfile: profgen dumpProfile 失败（${cause.javaClass.name}: ${cause.message}）",
                cause,
            )
        }
        val rules = outFile.get().asFile.readLines().count { it.isNotBlank() && !it.startsWith("#") }
        if (rules == 0) {
            throw GradleException(
                "convertArtProfile: 还原出 0 条规则（${profileFile.get().asFile}）——" +
                    "要么 profile 是空的（采样期间没跑到代码），要么 APK 与 profile 不同源" +
                    "（dex 校验和对不上，profgen 会把方法表丢掉）",
            )
        }
        logger.lifecycle("convertArtProfile: 写出 ${outFile.get().asFile} —— $rules 条规则")
    }
}

tasks.register<ConvertArtProfileTask>("convertArtProfile") {
    group = "eratw"
    description = "把真机采到的 ART .prof 还原成 src/main/baseline-prof.txt（T2-63）"
    // 惰性：只有真的请求这个任务时才要 -PeratwArtProfile，日常 assemble 不受影响。
    val profPath = providers.gradleProperty("eratwArtProfile").orNull
        ?: throw GradleException(
                "convertArtProfile 需要 -PeratwArtProfile=<设备上拉回来的 .prof>；" +
                "采集方法见 devtools/t63_profile.py 的 collect 子命令",
        )
    profileFile.set(file(profPath))
    val apkOverride = providers.gradleProperty("eratwArtApk").orNull
    if (apkOverride != null) {
        apkFile.set(file(apkOverride))
    } else {
        apkFile.set(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    }
    // 缺省直接覆盖 `src/main/baseline-prof.txt`（真机采集的正式用法）；
    // `-PeratwArtOut` 是给**闭环自检**用的出口：把已打进包的 baseline.prof 反解到临时文件，
    // 与源 txt 比对，不必污染工作区。
    val outOverride = providers.gradleProperty("eratwArtOut").orNull
    if (outOverride != null) {
        outFile.set(file(outOverride))
    } else {
        outFile.set(layout.projectDirectory.file("src/main/baseline-prof.txt"))
    }
}

// ---------------------------------------------------------------------------
// staging：把随包件搬到 `build/generated/gameAssets`，再由 AGP 当 assets 根打进 APK。
//
// 为什么必须拷（而不是把游戏根直接当 assets 源）：AGP 的 `assets.srcDir` 会把目录内容
// **摊平到 assets 根**，路径前缀 `resources/`、`CSV/` 就没了，而装载侧按相对路径找图；
// 游戏根里又不能用的东西更多（ERB 源、exe/dll、sav 存档、readme 集）。所以这里做一层
// 「链接农场」：只把要的四类目录（见 `stagedDataDirs`）+ 三个配置按原相对路径落进 staging，其余一概不进。
//
// 335 MB 的语料用**同卷硬链接**落 staging（`Files.createLink`），staging 侧对链接项只读不写
// —— 语料在共享根是只读的（AGENTS 第 1 节），链接不复制内容也不改内容；跨卷或链接被拒时
// 自动退回 1 MiB 流式拷贝，日志分别给出 linked/copied 两个计数。
//
// 程序侧沿用既有机制：`program.erbc` 单条，或按 `-PeratwErbcChunkMb` 切成
// `program.partNNN.erbc` + `program.parts` 清单（每行「文件名 TAB 字节数」）。
// **分片是裸字节切片、不是独立容器**（CMP-74：规约 §7 已按实产口径改正）：拼接只认
// program.parts 的行序，拼回的字节必须与切分前的 program.erbc 逐字节相同；每片不带头、
// 不保证落在指令边界。`program.parts` 保持两列是为了兼容设备侧解析器
// （platform/GameAssetInstaller.readPartsManifest），每片 sha256 记在随包 manifest 的
// `shard` 行里（本任务追加的 package 段）。
// 分片名保留 .erbc 后缀是有意的：aapt2 的 noCompress 按后缀匹配，叫
// program.erbc.part000 会掉进压缩、STORED 口径失效（2026-10-21 实测：3 片全变 DEFLATE）。
//
// 额外写一份 `erbc.v1/package.report`（解包器只按固定名字取 program/meta/parts/manifest，
// 不会碰它）：随包条目数、各类字节数、程序压缩口径、后缀表、APK 体积上限、清单自身的
// sha256（`manifest-sha256`，CMP-74 的「报告 ↔ 清单」对账锚点）。
// `tools/apkpack/inspect_apk.py` 读它并与真实 zip 逐类对账 —— APK 自此**自描述**，
// 核验不必猜「这个包本该多大」。
// ---------------------------------------------------------------------------

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

/** 摘要字节 → 小写十六进制（manifest / package.report 的唯一写法）。 */
fun ByteArray.hexLower(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

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

val gameAssetsRoot = layout.buildDirectory.dir("generated/gameAssets")

// `-PeratwAllowStaleErbc` 是 fixture 口子，`-PeratwRequireComplete` 是正式包闸：两者同给
// = 自相矛盾（要求完整却放行无法证明新鲜的产物），配置期直接拒（同 skipAssets 那条的精神）。
if (allowStaleErbc && requireComplete) {
    throw GradleException(
        "-PeratwAllowStaleErbc 与 -PeratwRequireComplete 互斥：正式包必须能证明 erbc 产物新鲜" +
            "（CMP-66 的 version/源 sha 对账），fixture 才用放行开关。",
    )
}

if (bundlingEnabled) {
    val stageGameAssets = tasks.register("stageGameAssets") {
        group = "eratw"
        description = "把 erbc 产物 + CSV/resources/sound/plugins/配置搬进 assets staging（装机即玩随包）"
        val chunkBytes = (erbcChunkMiB.toLong()) * 1024L * 1024L
        val outRoot = gameAssetsRoot.get().asFile
        val gameRoot = bundledGameRoot
        val erbcDir = bundledErbcDir
        val dataDirs = stagedDataDirs
        val configFiles = stagedConfigFiles
        val programStored = !erbcCompressed

        // 输入：产物目录 + 三类语料目录 + 配置文件。语料是 335 MB，Gradle 每次要 xxhash 一遍
        // （实测几十秒内）；不改这条的代价是「改了 CSV 却命中 UP-TO-DATE」出旧包，不能省。
        erbcDir?.let { inputs.dir(it) }
        gameRoot?.let { root ->
            dataDirs.forEach { name ->
                val dir = File(root, name)
                if (dir.isDirectory) inputs.dir(dir)
            }
            configFiles.forEach { name ->
                val f = File(root, name)
                if (f.isFile) inputs.file(f)
            }
        }
        // 分片大小与压缩口径是任务输入：不加这两行，改 -PeratwErbcChunkMb /
        // -PeratwErbcCompress 会命中 UP-TO-DATE 静默不重切、不重打包。
        inputs.property("chunkBytes", chunkBytes)
        inputs.property("programStored", programStored)
        // CMP-66：语料根本身也是输入 —— 否则「ERB 改了、erbc 没重编」会被 UP-TO-DATE 整个跳过，
        // 新鲜度对账根本没机会跑（实测：只改 ERB 再跑，`stageGameAssets UP-TO-DATE`）。
        // 语料 167 MB / 4.5k 文件，Gradle 的指纹比自算 SHA-256 快得多；不声明这条，
        // 后面 verifyErbcFreshness 的源 sha 对账就只是「跑得到的时候才有效」。
        gameRoot?.let { root ->
            val erbDir = File(root, "ERB")
            if (erbDir.isDirectory) inputs.dir(erbDir).withPropertyName("corpusErb")
        }
        // 完整度开关同样是输入：不加这行，上一轮「语料齐但没 erbc 产物」的 staging 会命中
        // UP-TO-DATE、doLast 整个跳过 ⇒ -PeratwRequireComplete 那条挡住不了任何东西，
        // 照样静默出一个「装上也玩不了」的正式包（2026-10-21 接力实测）。
        inputs.property("requireComplete", requireComplete)
        // CMP-66：新鲜度对账的判据本身也是输入 —— 版本单源改了（= 编译器变了）或放行开关翻了，
        // 都必须重跑 doLast，否则对账会被 UP-TO-DATE 整个跳过。
        inputs.property("allowStaleErbc", allowStaleErbc)
        inputs.file(erbccVersionSrc)
            .withPropertyName("erbccVersionSource")
        // CMP-66 编译器指纹：改编译器实现不改 VERSION 时，上一轮的 staging 会命中 UP-TO-DATE、
        // doLast 整个跳过，compiler-digest 对账就永远不跑。这里只登记输入指纹（不是跨项目
        // dependsOn，不触发编译），不声明它等于把新判据又关掉。
        inputs.files(
            fileTree(coreVmKotlinSrc) { include("**/*.kt") },
        ).withPropertyName("compilerSources")
        outputs.dir(outRoot)

        doLast {
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
            fun mb(bytes: Long): String = String.format("%.1f MiB", bytes / 1024.0 / 1024.0)
            fun b(bytes: Long): String = String.format("%,d", bytes)

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
                else -> verifyErbcFreshness(erbcDir, gameRoot?.let { File(it, "ERB") }, hard = !erbcDirIsFallback)
                    ?: "unverified(见上面的告警)"
            }

            outRoot.deleteRecursively()
            var linked = 0
            var copied = 0
            var extensionless = 0
            val report = LinkedHashMap<String, String>()
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
                    "manifest-format $eratwManifestFormat\n" + baseManifest
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
                stagedManifest.writeText(
                    (if (markedManifest.endsWith("\n")) markedManifest else markedManifest + "\n") + packageBlock,
                )
                // package.report 的 `manifest-sha256` 让「报告 ↔ 清单」也能对账（inspect_apk.py）。
                report["manifest-sha256"] = sha256Of(stagedManifest)

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
            val stagedDirs = LinkedHashMap<String, Pair<Int, Long>>()
            if (gameRoot != null) {
                dataDirs.forEach { name ->
                    val src = File(gameRoot, name)
                    if (!src.isDirectory) {
                        logger.warn("stageGameAssets: 游戏根缺 $name/（$src）：该类条目不随包")
                        stagedDirs[name] = 0 to 0L
                        return@forEach
                    }
                    var count = 0
                    var bytes = 0L
                    src.walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = f.toRelativeString(src)
                        if (!f.name.contains('.')) extensionless++
                        val dst = File(outRoot, "$name/$rel")
                        if (linkOrCopy(f, dst)) linked++ else copied++
                        count++
                        bytes += f.length()
                    }
                    stagedDirs[name] = count to bytes
                    report["entries-$name"] = count.toString()
                    report["bytes-$name"] = bytes.toString()
                    logger.lifecycle(
                        "stageGameAssets: $name $count 个文件 ${mb(bytes)}（${bytes} B）",
                    )
                }
                // 配置：`emuera.config` 是 GameStorage 的游戏根标志、也是解包器「只拷一次」那条；
                // default/fixed 存在才带。
                var configCount = 0
                var configBytes = 0L
                configFiles.forEach { name ->
                    val f = File(gameRoot, name)
                    if (f.isFile) {
                        if (linkOrCopy(f, File(outRoot, name))) linked++ else copied++
                        configCount++
                        configBytes += f.length()
                    }
                }
                stagedDirs["配置"] = configCount to configBytes
                report["entries-config"] = configCount.toString()
                report["bytes-config"] = configBytes.toString()
                if (configCount == 0) {
                    logger.warn("stageGameAssets: 游戏根没有 emuera.config：首启只能靠内部根兜底")
                }
            } else {
                logger.lifecycle(
                    "stageGameAssets: 未给 -PeratwGameRoot 且 ERATW_GAMEDIR 无效 ⇒ 只随包 erbc.v1，" +
                        "缺图缺声音（不是「装机即玩」形态）",
                )
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
                stagedDataDirs.forEach { name ->
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

    android.sourceSets.getByName("main").assets.srcDir(gameAssetsRoot)
    tasks.named("preBuild").configure { dependsOn(stageGameAssets) }
} else if (skipBundledAssets) {
    // 两个开关同时给 = 自相矛盾：随包全关时 `stageGameAssets` 根本没注册，
    // 「缺任一类条目就挡住」那条闸没人执行，结果是一个自称「要求完整」却只有 4 MiB 的包。
    if (requireComplete) {
        throw GradleException(
            "-PeratwRequireComplete 与 -PeratwSkipAssets 互斥：随包已关闭，完整度无从校验。" +
                "出正式包请去掉 -PeratwSkipAssets（游戏根取 -PeratwGameRoot 或 ERATW_GAMEDIR）。",
        )
    }
    logger.lifecycle(
        "eratw: -PeratwSkipAssets ⇒ 不随包（日常开发口径，APK 约 4 MiB；" +
            "装机即玩的正式包去掉这个开关，或 -PeratwRequireComplete=1）",
    )
}

// 旧任务名的空壳：别的会话/脚本里可能还写着 `stageErbcAssets`，让它指向新任务而不是「task not found」。
tasks.register("stageErbcAssets") {
    group = "deprecated"
    description = "已并入 stageGameAssets（随包口径见 app/build.gradle.kts 文件头）"
    if (bundlingEnabled) dependsOn("stageGameAssets") else doLast { logger.lifecycle("stageErbcAssets: 随包已关闭，无事可做（-PeratwSkipAssets 或未给游戏根/产物）") }
}
