import com.eratw.port.buildlogic.ConvertArtProfileTask
import com.eratw.port.buildlogic.StageGameAssetsTask
import com.eratw.port.buildlogic.bundledSuffixes
import com.eratw.port.buildlogic.stagedDataDirs
import com.eratw.port.buildlogic.stagedConfigFiles
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // 壳层 UI 用 Jetpack Compose（游戏画面仍是 CanvasConsoleView，经 AndroidView 嵌入）。
    alias(libs.plugins.kotlin.compose)
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
//   -PeratwGameRoot=<目录>   显式指定游戏根（缺省读环境变量 ERATW_GAMEDIR）；
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
// 出正式包（环境变量都导出的话，游戏根不用再传 -PeratwGameRoot）：
//   ./gradlew --configure-on-demand :app:assembleDebug -PeratwRequireComplete=1
//   python tools/apkpack/inspect_apk.py --require-bundled
// 机制自证（不跑 328 MB 全量编译时，用伪游戏根把分片/清单/STORED/幂等跑一遍）：
//   python tools/apkpack/make_fixture.py
//   ./gradlew --configure-on-demand :app:assembleDebug -PeratwGameRoot=<fixture>/game \
//       -PeratwErbcDir=<fixture>/game/erbc.v1 -PeratwErbcChunkMb=1 -PeratwAllowStaleErbc=1
//   python tools/apkpack/inspect_apk.py --require-bundled
// 日常小包：./gradlew --configure-on-demand :app:assembleDebug -PeratwSkipAssets=1
//
// 体积测算（2026-10-21 在开发机实测量；数字都在 :app:clean 之后取，增量打包会留下
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
//     （2026-10-21 在开发机：真语料 + 按真产物字节数做的伪程序 328,799,098 + 49,224,530 B；
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

val bundlingEnabled = !skipBundledAssets && (bundledGameRoot != null || bundledErbcDir != null)

// 随包目录/配置/后缀表的判据与实现搬到 buildSrc（BundledAssets）：bundledSuffixes 显式传参
// （gameRoot/dataDirs/configFiles），不再闭包读脚本全局。stagedSuffixes 仍留在脚本侧 ——
// androidResources 的 noCompress 在配置期就要它，任务类也在接线时复用同一份。
val stagedSuffixes: Set<String> =
    if (bundlingEnabled) bundledSuffixes(bundledGameRoot, stagedDataDirs(!skipBadFrames), stagedConfigFiles)
    else emptySet()

// ===========================================================================
// 上报链 / 自动更新三条通道：缺参数要大声，包内要留不含机密的读数（OBS2-L4 packguard）
// ===========================================================================
// ==== BEGIN channel-guard（ReportChannelBuildStampTest 按这对标记切片，改名要同步） ====
//
// 病（组长 2026-11-05 取证，L3 判读）：本地报告池里 17 份 `-dirty` 内测直发包在端点侧**一个都不在**
// —— 这些包的整条上报链当年就没接上。洞在本文件原来的写法：三个 `-Peratw*` 参数读成空串回退，
// 配置期零警告零失败 ⇒ 手敲 `:app:assembleRelease`（发布规范点名禁止，但没有任何东西挡它）
// 照样编译全绿、包照出，只是玩家崩了没人收得到。
// `devtools/pack_release.py` 的 resValues/arsc 回读体检只护住**走脚本**的那条路，不走脚本没人管。
//
// 本闸只做两件事，两条都不许改成硬失败（`--offline` 与刻意内测是发布规范里的合法用法）：
//  1. 请求了 release 侧任务而通道是关的 ⇒ 配置期打一条 `logger.warn`（比 lifecycle 更响，
//     `--quiet` 之外任何日志级别都在屏上；要静音它只能不出 release 包）。
//  2. 无论开关，都往包里烙一条**不含机密**的通道读数 `reportChannelStamp`：
//     `v1;endpoint=<host|off>;token=<on|off>;mirrors=<host[+host]|off>`，
//     同时落 resValue 与 BuildConfig ⇒ 任意现成 APK 事后能被 `aapt2 dump resources`
//     或既有判读判出「当年带没带参数」（含只到 host 级的端点，认得出报的是哪个域）。
//
// 令牌的纪律：值只进既有的 `eratw_report_token` 那一条 resValue（运行期上传要用），
// 绝不进警告文案、绝不进读数、绝不写日志；本闸不读也不写 `local.report.properties`，
// 判定只看这三个键在不在（空 = 关），与凭据从哪来无关。

/** 读数里「这条通道没接」的取值；与 host 级读数同形（小写字母），所以整条 stamp 不需要转义。 */
val channelOff = "off"

/**
 * host 级读数的合法形状：DNS 名的字符集、总长 1..253（DNS 单名上限），且首字符必须是
 * 字母/数字（RFC 1123 单名约束）——首字符放宽到 `.`/`-` 的旧形状与 ReportChannelBuildStampTest
 * 的复算正则不同形，畸形参数（如以点开头）会原样进读数而复算侧判 invalid，两臂对账假红。
 * 两边必须逐字同形，改动要同步测试侧的 HOST_SHAPE。
 */
val channelHostPattern = Regex("^[a-z0-9][a-z0-9.-]{0,252}$")

/** 三条通道的原始读数（空串 = 整条关）。令牌单列，只以「有/无」形态往下走。 */
val reportEndpointRaw: String = providers.gradleProperty("eratwReportEndpoint").orNull ?: ""
val reportTokenRaw: String = providers.gradleProperty("eratwReportToken").orNull ?: ""
val updateMirrorsRaw: String = providers.gradleProperty("eratwUpdateMirrors").orNull
    ?: providers.environmentVariable("ERATW_UPDATE_MIRRORS").orNull
    ?: ""

/**
 * URL → host 级读数：协议、路径、查询、端口与任何内嵌的账号口令（`@` 之前的整段丢掉）
 * 一概不进产物。结果只允许 [a-z0-9.-] 且长度 1..253，否则给 `invalid` ——
 * 宁可读成一个没有信息量的标记，也不把没剥干净的东西写进 APK 与日志。
 */
fun channelHost(raw: String): String {
    val url = raw.trim()
    if (url.isEmpty()) return channelOff
    val authority = url.substringAfter("://", url)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    val host = authority.substringAfterLast('@', authority).substringBefore(':').lowercase()
    return if (channelHostPattern.matches(host)) host else "invalid"
}

/**
 * 镜像表（`|` 分隔，与 `UpdateManager.MIRROR_SEPARATOR` 同形）→ host 级读数。
 * 有界性：最多显示 4 个 host（内置源实际 2-3 个），超出只补 `+n<总数>`；
 * 于是整段上限 = 4×253 + 3 个分隔符 + 3 个字符的计数 ≈ 1.1 KiB 的**常量**，
 * 不是会随输入变大的表（契约 §0.10：容量写死 + 算术理由）。
 */
fun channelMirrorHosts(raw: String): String {
    val hosts = raw.split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { channelHost(it) }
        .distinct()
    return when {
        hosts.isEmpty() -> channelOff
        hosts.size <= 4 -> hosts.joinToString("+")
        else -> hosts.take(4).joinToString("+") + "+n" + hosts.size
    }
}

/**
 * 写进包的那条读数（不含机密）。三个分量各自成形状，拼出来的字符集只有
 * 字母/数字/`.`/`-`/`;`/`=`/`+` ⇒ 既不用 XML 转义，也不可能带出引号、反斜杠或令牌。
 */
val reportChannelStamp = "v1;endpoint=" + channelHost(reportEndpointRaw) +
    ";token=" + (if (reportTokenRaw.isBlank()) channelOff else "on") +
    ";mirrors=" + channelMirrorHosts(updateMirrorsRaw)

/**
 * 「这次构建会不会产出 release 包」的保守判定：只认得出 release 字样与会连带跑 release 的
 * 聚合任务；认不出的一律不出警告（宁少喊一次，也不要在日常 debug 构建里把这条刷成背景噪音
 * —— 天天见的警告等于没有警告）。
 * 认识的写法：`:app:assembleRelease`、`assembleRelease`、`bundleRelease`、`packageRelease*`、
 * `installRelease`、`compileReleaseKotlin`，以及 `assemble` / `build` / `buildDependents` /
 * `buildNeeded` / 不带任务名（gradle 缺省跑 `assemble`，它含 release）。
 * 不认识：gradle 的驼峰缩写（`aRel` 之类）—— 那种调用拿不到警告，但读数照常烙进包，
 * 事后仍判得出（见回执的 TODO(verify)：精确判定要等 taskGraph，而它与配置缓存互斥）。
 */
val releaseBuildRequested: Boolean = gradle.startParameter.taskNames.let { names ->
    names.isEmpty() || names.any { task ->
        val name = task.substringAfterLast(':')
        name.contains("release", ignoreCase = true) ||
            name in setOf("assemble", "build", "buildDependents", "buildNeeded")
    }
}

if (releaseBuildRequested) {
    val closedChannels = listOfNotNull(
        if (reportEndpointRaw.isBlank()) "-eratwReportEndpoint（报错上报端点：没它 = 一份报告都发不出去）" else null,
        if (reportTokenRaw.isBlank()) "-eratwReportToken（上报令牌：没它与没端点同罪，端点会 401 全丢）" else null,
        if (updateMirrorsRaw.isBlank()) "-eratwUpdateMirrors（或环境变量 ERATW_UPDATE_MIRRORS：没它 = 自动更新整条通道关，一次网络请求都不发）" else null,
    )
    if (closedChannels.isNotEmpty()) {
        logger.warn(
            buildString {
                // 英文标记行先行：Windows 控制台（GBK）会把中文刷成乱码，这一行保证「屏上至少看得懂
                // 出事了」，与上面 -PeratwSkipAssets 那条同类警告的乱码教训同源。
                append("\n[eratw-channel] WARNING: report/update channels are OFF in this RELEASE build.\n")
                append("eratw-channel: 这次 release 构建没带这些参数：\n")
                closedChannels.forEach { append("  ").append(it).append('\n') }
                append("  后果：包照出、编译全绿，但玩家崩溃端点收不到任何东西、装上也不会自动更新；\n")
                append("        这类包只能自己内测，**不许发给玩家**（2026-11-05 的 17 份 -dirty 包就是这么漏的）。\n")
                append("  打开：-PeratwReportEndpoint=https://<上报域名> -PeratwReportToken=<令牌>\n")
                append("        -PeratwUpdateMirrors=https://<镜像根>/eratw/（多条用 | 分隔；cmd 下走环境变量，| 是管道符）\n")
                append("        正式包请走唯一入口：python devtools/pack_release.py（它会回读 APK 逐项体检）。\n")
                append("  刻意不上报（--offline / 内测包）是合法用法：本条只是留痕，不会让构建失败。\n")
                append("  写进包的事后读数（不含令牌）：resValue eratw_report_channel_stamp = ").append(reportChannelStamp).append('\n')
            },
        )
    }
}
// ==== END channel-guard ====

// M4-SKEL：最小可运行外壳。minSdk 21 + core library desugaring 同时覆盖老机型（Android 5.0）
// 与新系统。
//
// compileSdk / targetSdk = 36（Android 16，2026-10-30 随玩家反馈「一加，安卓16，直接闪退」收口）：
// - AGP 还是 8.8.2（自报「tested up to compileSdk = 35」⇒ 每次构建一条 warning）。**刻意不**跟着
//   升 AGP：8.9+ 要 Gradle 8.11.1，那是全 lane 共用的 wrapper，而本仓没有用到任何 API 36 的
//   新符号，warning 只是噪音。等真要写 36 专属代码时再一并升。
// - targetSdk 36 的两条行为变更对本外壳都是空：edge-to-edge 取消 opt-out（我们本来就是
//   `setDecorFitsSystemWindows(false)` + 沉浸式，见 MainActivity.applyImmersiveMode），
//   以及 ≥600dp 屏忽略 `screenOrientation`（手机 411dp 不受影响；平板会自由旋转，
//   版面按屏宽反推列数因此不会崩，只是不再是横屏 199 列）。
android {
    namespace = "com.eratw.port.android"
    // A-5：compileSdk/minSdk/targetSdk 走 catalog 的 versions 段（值与改前逐字同值）。
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.eratw.port.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
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
        // 0.2.31：收编 TRYC 动态名空名守卫（「高级列表功能」真机致命报错根修）与生产闸
        //        10 亿步确定性闸；随包语料首收群友口上（蕾米莉亚色情特化v1.3，K16 编号5
        //        变体，纯新增不改基线文件）。
        // 0.2.32：修「上次异常退出」每次冷启都弹 —— 后台被系统回收（vivo 真机退出记录全是
        //        从后台发出的 FORCE STOP）单列一态 `background`，不再当异常退出落报告/弹卡；
        //        真死在屏前（状态仍是 `running`）照旧补报。
        // 0.2.33：稳定化验证包 —— 收编 XML_GET 空输入解析（35d5bb6d）与 RAND 参数为 0
        //        守卫（e36e0de0），消掉 0.2.32 上报里 12 条 CodeEE 启动失败；设置页新增
        //        「运行诊断」卡，玩家可导出最近 64 行内存轨迹 + 死因判读（静默退出那一桶
        //        从此有读数）。
        // 0.2.35：R7 补丁/mod 底座（补丁格式/base 身份绑定、ModStore、Symbols 下沉、
        //        LineTable/ConstantPool 组合形态）+ 稳定性总波 L1–L18 + 应用内自动更新
        //        通道（内置镜像源）+ OBS 观察性波（三路心跳/翻页分位/输入序号）；
        //        随包语料继续收群友口上（蕾米莉亚色情特化v1.3，K16 编号5 变体）。
        // 0.2.36：稳定性/性能收口包 —— 图形池与精灵池的像素预算（Java 堆侧主动回收，
        //        解 0.2.35 真机堆贴顶 OOM）+ 地图右侧残缺（列数 110→136）与重印抖动；
        //        挖虫波 W10/W12 与 R7-D 收官（补丁 privateDims 载体、PRINTDATA 执行面、
        //        编译器三 TODO）+ Rust 侧 erbcc 并入（RUST1 全波，与 Kotlin 腿逐字节对拍）；
        //        报告取证增强（OOM 内存分项/图像簿记/约会读数/失败屏按族讲）。
        // 0.2.37 (12)：止血包 —— 语句级脚本错误不再升成「启动失败」（可恢复终局）+
        //        指令收尾的按键等待收窄到「清掃被拒」+ 自产 HTML 串往返不再自产脏串
        //        （`HTML_GETPRINTEDSTR` 交回前剥掉行级 p/nobr 外壳）+ 报错节流与堆账面。
        // 0.2.38 (13)：随包语料换 eraTW4.981-261017 根 + 群友整合口上三件套收编
        //        （摩卡橙 ver0.05 已在根内保留根版；蕾米莉亚色情特化v1.3 K16_5 变体、
        //        风神少女v0.01 K29_1 本次新入）。
        // 0.2.40 (16)：erbcc 双修后语料重编（#DIM CONST 接按位 & | ^ ~、POWER 浮点
        //        实参返回浮点）+ CLICKQ 复核加固等 11-04 收编 + 收编对面线的内存档位
        //        放开档（API≥26 且堆≥512MiB 抬内存闸，OOM 簇）与更新弹窗两修；线上
        //        code 15 已被 0.2.39+15 占用，本版从 14 直接跳 16 防单调闸回退。随包
        //        语料继续含群友口上整合三件套（摩卡橙 / 蕾米莉亚色情特化 / 风神少女）。
        //        11-04 末次重出前再收编：每日起床 Palam/Status/USERCOM/LOOK 折叠开关
        //        跨 BEGIN TRAIN 保留、方向锁解锁改回 sensorLandscape、出包数据源同源闸。
        //        语料按当前 erbcc 以 compat 档重编（4,324 文件 / 13 条已登记降级）。
        //        同日重出（仍是 0.2.40 / 16，对外只算一个版本）：竖屏模式收口 —— 首版把
        //        方向缺省写成「跟随机转」（FULL_SENSOR）又删了 manifest 的
        //        sensorLandscape，手机竖着拿启动会直接进竖屏三区壳（玩家实测即报 bug，
        //        镜像已撤回重出）。方向只剩横/竖两态：缺省锁横屏（与原版一致），竖屏
        //        改为设置页「操作与显示」里的显式开关（缺省关）；方向锁按钮解锁按本局
        //        方向回位（竖屏局回 SENSOR_PORTRAIT）；改开关后返回会问「现在重开」。
        // 0.2.43 / 19（2026-11-07）：收 rt2 契约层（EntityContracts/SymbolCatalog/SidecarSaveBundle/
        //        ModuleDescriptor 等）+ 立绘脸黑修复（blendPixel 直通域 + 非预乘解码）+ 存档求援
        //        回收量口径 + CBG 绘序/图层精确几何/尺寸源 miss 单出口 + OOM 账本载荷记账等合流面。
        versionCode = 19
        versionName = "0.2.43"

        // 方案③ 报错上报端点（服务端模板见 tools/report-endpoint/）：缺省空 =
        // 上报通道整体关闭（零网络行为）；要启用在出包时带这两个 -P 参数：
        //   -PeratwReportEndpoint=https://<上报域名> -PeratwReportToken=<令牌>
        // 「缺参数就静默关闭」这件事由文件头的 channel-guard 兜：release 构建缺它会打一条大声警告，
        // 并且无论开不开，包内都留一条不含机密的读数 eratw_report_channel_stamp（见 reportChannelStamp）。
        resValue("string", "eratw_report_endpoint", reportEndpointRaw)
        resValue("string", "eratw_report_token", reportTokenRaw)

        // 国内镜像仓自动更新：镜像基址表（`|` 分隔，见 UpdateManager.MIRROR_SEPARATOR）。
        // 缺省空 = **整条通道关**：一次网络请求都不发，与上报端点同一套「不带参数就静默关闭」
        // 的口径 —— 也正因为会静默关闭，devtools/pack_release.py 把它当成必须体检的一项，
        // channel-guard 把它一起纳进警告与读数（三个参数一个闸，别只结两个）。
        //   -PeratwUpdateMirrors=https://oss.example/eratw/|https://mirror2.example/eratw/
        // 也可以走环境变量 ERATW_UPDATE_MIRRORS（同值）。出包脚本**走的是环境变量这一条**：
        // Windows 下 gradlew 必须经 `cmd /c` 拉起，而 `|` 在 cmd 里是管道符 —— 同一个 -P 参数
        // 在 PowerShell/Git-Bash 下好好的，到 cmd 就被拆成「管道给 https:」，实测退出码 255。
        // 地址必须 https（明文由 core 的 UpdatePolicy.normalizeMirror 拒掉）。
        resValue("string", "eratw_update_mirrors", updateMirrorsRaw)

        // 事后判读用的通道读数（**不含任何机密**：只有有/无与 host 级域名）。
        // 为什么 resValue 与 BuildConfig 各落一份：resValue 进 resources.arsc，能被
        // `aapt2 dump resources` 与 pack_release.py 那套回读判据拿到（判现成的包）；
        // BuildConfig 进 dex 与错误报告侧的取证面，且是纯 JVM 单测唯一能直接读到的落点
        // （ReportChannelBuildStampTest 钉的就是它）。两边必须逐字相同，测试会互相对账。
        resValue("string", "eratw_report_channel_stamp", reportChannelStamp)
        buildConfigField("String", "eratwReportChannelStamp", "\"$reportChannelStamp\"")

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

        // 资源收紧：本 APK 自带资源只有字体、主题、一张图标和设置页「关于」卡的那张开发者头像，
        // 没有任何 locale/密度变体。
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
        // A-5：17 走 catalog 的 versions.jvmTarget（与纯 JVM 模块的 jvmToolchain 同源）。
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
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
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

dependencies {
    // eraKcore（composite build，见 settings.gradle.kts 的 includeBuild + dependencySubstitution）
    implementation("com.eratw.port:core-vm")
    implementation(project(":render-canvas"))
    implementation(project(":platform"))
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    // 壳层 Compose：只用于输入条 / 右侧选项栏 / 覆盖层 / 设置入口；游戏画面仍是
    // CanvasConsoleView（经 AndroidView 嵌入），渲染与命中路径零改动。
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    // M10-CLOUD 取证装置。uiautomator 锁 2.3.0：2.4.0 的 minSdk 是 23，会在 manifest 合并处
    // 挡住本工程（minSdk 21）。runner 1.7.0 以 compile 作用域带入 junit，故不单独声明。
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // 壳层布局回归（如右侧选项栏的画布预留宽）：纯 JVM 单测即可覆盖，不需要设备。
    testImplementation(libs.junit4)
    // T2-63：把随包的 baseline profile 在「非 Android 12」机型上真正落地。
    //
    // APK 里的 `assets/dexopt/baseline.prof` 只有 API 31+（Android 12）由 dex2oat 在**安装时**
    // 自动消费；API 24~30 上系统不会去读它。profileinstaller 在首启后把 profile 写进
    // `<app>/files/profileinstaller/` 并触发一次后台 dexopt，让 24~30 也能拿到同一份热点表。
    // API 21~23 没有 profile 支持的 ART 通道，库自身会空转（不影响启动）。
    implementation(libs.androidx.profileinstaller)
}

// 两臂自证的钉子（ReportChannelBuildStampTest）：把「构建期这三条通道到底带没带参数」以
// **不含令牌**的形态传给单测 JVM —— 用例据此独立复算读数，再与包的两个落点
// （BuildConfig.eratwReportChannelStamp、生成的 resValues）三方对账：
//   不带参数跑一次 ⇒ 期望 endpoint=off;token=off;mirrors=off；
//   带参数跑同一条命令 ⇒ 期望端点变成 host、token 变 on、mirrors 变成 host 表。
// 传的是原始端点/镜像串（它们本来就是包内明文资源，不是机密）；令牌只传布尔「有/无」，
// 值绝不出现在系统属性、测试输出或任何产物里。
tasks.withType<Test>().configureEach {
    systemProperty("eratw.build.reportEndpoint", reportEndpointRaw)
    systemProperty("eratw.build.reportTokenPresent", reportTokenRaw.isNotBlank().toString())
    systemProperty("eratw.build.updateMirrors", updateMirrorsRaw)
}

// T2-63 的 ConvertArtProfileTask 类已抽到 buildSrc（com.eratw.port.buildlogic.ConvertArtProfileTask）：
// 逐字搬运 + loader 发现适配（buildSrc 任务类不天然持有 buildscript classloader，改候选链探测）。
// 语义说明全在任务类 KDoc；这里只做注册与参数接线。
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
// —— 语料在共享根是只读的，链接不复制内容也不改内容；跨卷或链接被拒时
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
    // stageGameAssets 的任务类与执行体在 buildSrc（StageGameAssetsTask / BundledAssets），
    // 这里只做配置期接线：算好的参数逐个注入，不读 project 全局态。
    // 输入指纹由任务类注解声明，与旧 inputs.dir/file/property 逐条同义；属性名从
    // 位置序号改成实名 —— 增量键改名会让本次改动后的首次构建重跑一次 staging，产物不变。
    // 注：cfg* 中转变量是必须的 —— 注册 lambda 的隐式接收者是任务实例，Kotlin 里
    // 接收者成员会遮蔽外层同名 val（requireComplete/allowStaleErbc 都重名），
    // 裸名引用会拿到 Property 而不是脚本侧的布尔值。
    val cfgChunkMiB = erbcChunkMiB
    val cfgRequireComplete = requireComplete
    val cfgAllowStaleErbc = allowStaleErbc
    val cfgProgramStored = !erbcCompressed
    val cfgGameRoot = bundledGameRoot
    val cfgErbcDir = bundledErbcDir
    val cfgIsFallback = erbcDirIsFallback
    val cfgDataDirs = stagedDataDirs(!skipBadFrames)
    val cfgConfigFiles = stagedConfigFiles
    val cfgSuffixes = stagedSuffixes
    // apkOverheadAllowance 与任务属性重名，必须中转（同上遮蔽规则）；set 自己 = 循环解析
    val cfgApkOverhead = apkOverheadAllowance
    val stageGameAssets = tasks.register<StageGameAssetsTask>("stageGameAssets") {
        group = "eratw"
        description = "把 erbc 产物 + CSV/resources/sound/plugins/配置搬进 assets staging（装机即玩随包）"
        chunkMiB.set(cfgChunkMiB)
        chunkBytes.set(cfgChunkMiB.toLong() * 1024L * 1024L)
        programStored.set(cfgProgramStored)
        requireComplete.set(cfgRequireComplete)
        allowStaleErbc.set(cfgAllowStaleErbc)
        erbcDirIsFallback.set(cfgIsFallback)
        gameRoot.set(cfgGameRoot)
        erbcDir.set(cfgErbcDir)
        dataDirs.set(cfgDataDirs)
        configFiles.set(cfgConfigFiles)
        noCompressSuffixes.set(cfgSuffixes.toList())
        apkOverheadAllowance.set(cfgApkOverhead)
        outRoot.set(gameAssetsRoot)
        // 输入指纹（与旧 inputs.dir/file 逐条同义）：erbcDir 已在上面 set（@Optional @InputDirectory），
        // 这里补语料目录/配置文件；语料是 335 MB，Gradle 每次要 xxhash 一遍（实测几十秒内）；
        // 不改这条的代价是「改了 CSV 却命中 UP-TO-DATE」出旧包，不能省。
        cfgGameRoot?.let { root ->
            cfgDataDirs.forEach { name -> File(root, name).takeIf { it.isDirectory }?.let { corpusDirInputs.from(it) } }
            cfgConfigFiles.forEach { name -> File(root, name).takeIf { it.isFile }?.let { configFileInputs.from(it) } }
            // CMP-66：语料根本身也是输入 —— 否则「ERB 改了、erbc 没重编」会被 UP-TO-DATE 整个
            // 跳过，新鲜度对账根本没机会跑（实测：只改 ERB 再跑，`stageGameAssets UP-TO-DATE`）。
            File(root, "ERB").takeIf { it.isDirectory }?.let { corpusErb.set(it) }
        }
        // root=game 的语料补丁（CSV/_Rename.csv 等）在 staging 时改随包字节；索引改了必须重跑。
        rootProject.file("devtools/corpus-patches").takeIf { it.isDirectory }?.let { corpusPatchesDir.set(it) }
        erbccVersionSource.set(erbccVersionSrc)
        compilerSources.from(fileTree(coreVmKotlinSrc) { include("**/*.kt") })
        repoRoot.set(eraKcoreHome)
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
