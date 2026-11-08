package com.eratw.port.platform.storage

import com.eratw.port.platform.config.ConfigFileStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * 首启解包失败（文案可读、直接进宿主失败屏，见 `GameHost.ensureAssetsInstalled`）。
 *
 * 与 [IOException] 的关系：本类继承它，是为了让「解包」与「装载」两侧的失败在宿主眼里
 * 仍是同一类 IO 事故（`BootFailures` 的兜底分支会保留 `message` 原话）；
 * 但解包失败**不**走「产物缺失」文案 —— 屏上要看得出是「APK 自带的数据没落到盘」。
 */
class AssetInstallException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * APK assets 的只读视图（解包器唯一的外部依赖）。
 *
 * 抽出来是为了纯 JVM 可测：真机实现是 [AndroidAssetTree]（走 `AssetManager`），
 * 单测用临时目录（见 `GameAssetInstallerTest`）。三个方法的语义都按「不存在就返回空」
 * 设计 —— 调用方不需要区分「路径不存在」与「读失败」。
 */
interface GameAssetTree {

    /** 以流打开 [path]；不存在或不是文件时返回 null（不抛）。 */
    fun openFile(path: String): InputStream?

    /** [path] 目录下的直接子项名（文件与子目录混排）；不是目录或不存在时返回空表。 */
    fun listDir(path: String): List<String>

    /**
     * [path] 作为**未压缩条目**的字节数；压缩条目、目录、不存在一律返回 null。
     *
     * 只用于「目标与资产的字节数是否一致」这一处判断：拿不到（压缩条目）时退化成
     * 「目标存在即视为已装」（写侧经临时文件改名，目标存在即完整）。
     *
     * **0 是有效长度，不是「拿不到」**（语料 `sound/` 下 18 个零字节 `bgm` 标记走的就是这一路）。
     * TODO(verify)：真机上「非空 STORED 条目报 0」没有取证手段（本层没有 Robolectric 装置）；
     * 若某机型真这么报，会被 [GameAssetInstaller] 的落盘后核对判成「与声明不符」而拒绝落盘，
     * 屏上看得到那条 `声明 0 B，实读 N B`。卡点：需要真机 `openFd` 读数。
     */
    fun fileSize(path: String): Long?
}

/**
 * 只由**差量数据包**实现的第二张脸：告诉解包器「这一版要删掉盘上哪些文件」。
 *
 * ## 为什么不塞进 [GameAssetTree]
 *
 * [GameAssetTree] 是「只读的一棵资产树」，三个方法的语义都是「不存在就返回空」；删除表不属于树，
 * 它是**这一版相对上一版的 diff 的另一半**。把它做成独立接口，APK 随包视图（[AndroidAssetTree]）
 * 一个字都不用改，解包器用 `assets as? DeltaDeletionSource` 问一次就分得出两种包 ——
 * 也不用给接口加缺省实现（Java 接口的缺省方法要 D8 脱糖，minSdk 21 上多一条构建面不确定性，
 * 收益只有「少一个类型」，不值）。
 *
 * 实现方只负责**如实报出表内容**；保护名单、游戏根边界、与包内条目的先后次序全在
 * [GameAssetInstaller.applyDeltaDeletions] 那一侧 —— 因为只有解包器知道游戏根在哪。
 */
interface DeltaDeletionSource {

    /** 这个包是不是差量档：归档根里有那张删除表（哪怕表是空的）即为是。全量档恒 false。 */
    val isDeltaPackage: Boolean

    /** 要删除的**游戏根相对路径**（`/` 分隔，已按 zip 条目名口径归一；未过滤保护名单）。 */
    fun deltaDeletions(): List<String>
}

/**
 * 盘上资产树的**文件级摘要**里的一条：字节数 + 内容 SHA-256（小写十六进制）。
 *
 * 两个字段各有分工：字节数是便宜的粗筛（一次 `File.length()` 的 stat，不读内容），
 * 十六进制摘要是贵的细筛（整份读一遍才得出来）。调用方按场合选深度，见
 * [GameAssetInstaller.checkDataDigest]。
 */
class DataDigestEntry(val bytes: Long, val sha256Hex: String)

/**
 * 盘上资产树的**文件级摘要**（[GameAssetInstaller] 每应用一次数据包就重算一份）。
 *
 * @param version 这份状态对应的 `assets-version`（与 `update/data-version.txt` 同源的那个数）
 * @param entries 相对路径 → 该文件；相对路径一律 `/` 分隔、不含 `..`、不含绝对路径前缀
 */
class DataDigest(
    val version: Long,
    val entries: Map<String, DataDigestEntry>,
)

/**
 * APK 自带数据 → 游戏根的**首启解包器**（M8-ASSETSINSTALL）。
 *
 * ## 为什么需要
 *
 * 装载侧（`ProgramLoader` / `GameSession`）只吃真实文件系统路径：`.erbc` 要 `Path`/mmap，
 * 语料要按相对路径逐文件打开。APK 里的 assets 是 zip 条目、不是路径，所以首次启动必须
 * 把「随包的游戏数据」落一份到 [gameRoot]（[GameStorage.gameRoot] 选出的那个根），
 * 之后照旧走 `GameHost` 的装载链。这是侧载分发与云真机的唯一可行路径 ——
 * adb 一次只能推单个文件，推不了语料里成百上千个 CSV/图片。
 *
 * ## 资产布局（由 `app/build.gradle.kts` 的 `stageErbcAssets` 产出）
 *
 * - `erbc.v1/program.erbc`：单条产物；或
 * - `erbc.v1/program.parts`：分片清单，每行「分片文件名 TAB 字节数」，分片本体是
 *   `erbc.v1/program.partNNN.erbc`（名字由打包侧决定，本类**只认清单顺序**）；
 * - `erbc.v1/program.meta`：侧车（第 3 行是 `program-sha256 "hex"` 或 `-`）；
 * - `erbc.v1/manifest.txt`：可选，原样随包；
 * - `CSV/`、`resources/`、`sound/`：目录递归；
 * - `emuera.config`：原版配置（`ConfigData.cs:33`），**只拷一次**，之后永不覆盖；
 *   但升级路径上做「缺键补齐」（见 [mergeConfigUpgrades]）：新包新增键追加进盘上
 *   旧配置，盘上已有键一律不碰。
 *
 * ## 幂等与「不覆盖用户数据」
 *
 * 每个目标文件都经「同目录临时文件 + 改名」落盘，所以**目标存在即完整**：
 * - 知道资产字节数（STORED 条目，或分片清单）时，目标存在且字节数相同才跳过，不同就重拷
 *   （覆盖上一版产物 / 截断的残留）；**0 字节也是已知长度**——语料 `sound/` 下有 18 个
 *   零字节 `bgm` 标记（`app/build.gradle.kts` 的体积测算段），目标被写成非空同样要重拷；
 * - 拿不到字节数（DEFLATE 条目）时只比存在性 —— 不会为了「比大小」把 328 MB 解压一遍；
 * - `emuera.config` 是**用户可改**的（游戏内设置直接写回该文件），存在就跳过、不比大小
 *   （升级键由 [mergeConfigUpgrades] 单独补齐，见该函数 KDoc）；
 * - `sav/` 不在清单里：存档是用户数据，不随包。
 *
 * 这一段判据属于**首启档**。数据更新走 [forceReplace] = true 的**更新档**：长度核对换成
 * 「包里有就重写」，其余（配置只拷一次、逐文件改名、配对事务、跨进程锁）原样不动。
 *
 * 目标路径按资产名**原样大小写**落盘（不改写、不折叠）：原版跑在大小写不敏感的 NTFS 上，
 * 兜底在读取侧（`EmueraConfigParser.resolveSubDir` 认 `csv`/`CSV`、`GameImageFiles` 建小写索引、
 * sprite 名按 `AppContents.cs:84` 的 `ToUpper` 归一），解包侧再折一次只会让两处口径分叉。
 * 这里唯一要防的形态是「两条只差大小写的资产映射到同一个目标 ⇒ 每轮来回重拷、永不收敛」：
 * 真语料四个随包目录实测没有这种条目（`find CSV resources sound plugins -type f` 逐条折小写后
 * `sort | uniq -d` 空输出），所以不加检测、也不加第二条跳过规则。
 *
 * 「目标存在即完整」靠的是**同目录改名**这一步；写侧没有 `fd.sync()`（真机成本：随包 3,971 条
 * 就是 3,971 次 fsync）。所以这里的「完整」是对**进程被杀**成立，对**整机掉电**不成立——
 * 极端情况下会留下一个长度正确的截断文件。TODO(verify)：要不要只给 `erbc.v1/` 两条大文件加
 * sync；卡点：没有可复现的掉电取证手段，且首启耗时读数还没钉过基线。
 *
 * ## 差量档：只替换包内条目 + 按删除表删
 *
 * 资产树实现 [DeltaDeletionSource] 且 `isDeltaPackage` 为真时，这一轮就是**差量档**
 * （归档里只有变更/新增文件 + 一张 `update-delta.txt`，见 `ZipAssetTree`）。差量档在
 * 更新档（[forceReplace]）之上只做三件加法，其余语义一条不动：
 *
 * 1. **执行删除表**（[applyDeltaDeletions]，落完新文件之后）：删除表以外的条目一律不碰盘 ——
 *    这是「改一条 ERB 只要下几 MB」的全部依据，多删一个文件就是玩家的存档外挂了。
 * 2. **产物对必须成对出现**（[requireDeltaArtifactPair]）：包里带了 `program.erbc` 却没带
 *    `program.meta`（或反过来）直接**在动盘之前**拒掉。差量包只换一半产物 = 盘上留下
 *    「新 program + 旧侧车」—— 侧车记的函数表/标签表与产物里的字节对不上，装载期按侧车
 *    取语句就会走到错位置（这类错在屏上没有统一形状，可能开局就报、可能跑到某条指令才报）；
 *    而首启/全量档不存在这个形态（整对必带），所以这条只在差量档开火。
 * 3. **摘要**里的删除项照表移除、新增/变更项照落盘内容重算（见下一节）。
 *
 * 删除面的安全判据（[deletionIsWithinAssetUniverse] + [deletionIsProtected]）：
 * **路径归一沿用 `ZipAssetTree.safeEntryName` 那一套**（`..` 段、空段、控制字符、反斜杠折分），
 * 并且执行前还要拿 canonical 路径确认落在游戏根**之内**（防御表被写成指向根外的软链形态）。
 * 只删 `[gameRoot]/erbc.v1` 与 [DATA_DIRS] 六类目录**之内**的东西 —— 解包器只会往这些地方落盘，
 * 所以它也只该从这里删东西：游戏根下还有 `dat/`、`font/`、`lang/`、`profile/` 这些**随包解包
 * 从不负责**的东西（`profile/` 里是玩家的自定义），一张写错的表不该把它们删掉。
 * 再加一层与发布侧 `devtools/emit_update.py` 的 `NEVER_BUNDLED` **同名单**的保护：路径的任何一段
 * 是 `sav`、`update`、`emuera.config`、`.install.lock` 都不许删。按段判不挡任何合法删除 ——
 * 实测六个随包目录里这四个名字一条都没有
 * （`find CSV resources sound plugins bad ERB \( -name sav -o -name update -o -name emuera.config -o -name .install.lock \)`
 * 空输出；2026-11-01 在游戏根 `eratw-画蛇添足版eratw4.980` 上跑的，2026-11-03 补上 ERB 后
 * 在 4.980 / 4.981 两处游戏根复测仍空输出）。
 *
 * 上面这些**不合判据的行是「跳过并计数」，不是抛异常**：表里写着保护名单，本来就是「不该删」，
 * 抛出去反而会让整轮应用失败 —— 而更新通道的失败重试只有三次，之后丢包、下次再下一遍，
 * 一张写歪的表能把玩家的更新永远卡在循环里。计数留在 [Result.blockedDeletions]，调用方该记日志
 * 并回传给发布侧。真正抛异常的只有两种：删不下去的 IO 失败，以及第 2 条那种「半对产物」。
 *
 * ## 文件级摘要：`update/data-digest.txt`
 *
 * 给了 [recordDataVersion]（>= 0）时，每轮应用成功后把「盘上现在有哪些资产文件、各多大、
 * 内容摘要是什么」记进游戏根的 `update/data-digest.txt`（与更新通道自己的 `data-version.txt`
 * 同目录、同生命周期）。它补的是版本号补不了的洞：
 *
 * `data-version.txt` 只说「这盘是第 N 版」，不说「盘上真的有第 N 版的那些文件」。
 * 差量档的正确性**完全建立在「盘上就是基线那一版」之上**（`UpdatePolicy.deltaUsable` 判的
 * 就是版本号相等），而半截被杀的旧一轮、玩家自己用文件管理器动过的手、文件系统坏块，
 * 都会让版本号继续撒谎、内容已经不对。差量一旦应用在这种盘上，得到的是「自洽却与版本号
 * 不符」的语料 —— 不报错，只是玩家读到旧脚本（AGENTS.md 铁律里最难查的那类）。
 *
 * 口径：
 * - **增量维护**：本轮真正写过的每个文件，落盘时顺手喂一次 SHA-256（字节已经流过一遍，
 *   不额外读盘），其余条目从上一份摘要**原样带过来**，删除表删掉的从摘要里移除。
 *   所以一轮差量应用的额外成本是「差量那几个文件」，不是「整棵 680 MiB 的树」；
 * - **只记自己管的东西**：`emuera.config` 永不进摘要（玩家会在游戏里改它，改了就该不一样），
 *   `sav/`、`update/` 也永不进（只由各自的通道写）；
 * - 写失败**不算这轮失败**：数据已经落对了，摘要只是下一轮的凭据。原因留在
 *   [Result.recordProblem]，调用方记日志即可；下一轮差量会因为缺摘要而回落全量 ——
 *   少赚一次差量，不赔上语料；
 * - 差量档**盘上没有可读摘要时不写新摘要**（见 [writeDataDigest]）：半张表（只有这一个
 *   差量里的三个文件）比没有摘要更坏 —— 它会让 `checkDataDigest` 只查这三个文件就报「一致」。
 * - **随包档也能写摘要**（C-1）：调用方传本机 versionCode 时，首启那一趟把 APK 里的资产全落
 *   一遍，顺手就攒出完整表；换 APK 时若盘上已有旧数据、本轮只落了变化的那部分，被跳过的
 *   条目必须有上一份摘要可继承，否则整份不写（同理：半张表更坏）。
 *
 * 格式与发布侧的生成口径写在 [encodeDataDigest] 的 KDoc 里（逐行钉死，U-D 按同一形状产出）。
 *
 * ## 内存（512 MiB 堆机型的硬约束）
 *
 * 全量产物 361.1 MiB（`program.erbc` 328,799,098 B + `program.meta` 49,224,530 B）。
 * 本类**没有任何整份 ByteArray**：所有拷贝走 [BUFFER_BYTES]（1 MiB）固定缓冲，
 * SHA-256 在拷贝过程中用 [MessageDigest.update] 增量喂，所以解包期堆增量是
 * 「1 MiB 缓冲 + 若干小 String（清单/头部）」，与产物大小无关。压缩包（
 * `-PeratwErbcCompress`）时走的是 `AssetInputStream` 的流式 inflate，同样只有 1 MiB 常驻。
 *
 * ## 配对核对与配对事务
 *
 * program 拷完（单条或分片拼完）算 SHA-256，与侧车第 3 行声明的 `program-sha256` 比对；
 * 不一致就**清掉刚落的中间文件并抛** [AssetInstallException] —— 盘上的旧产物（若有）由
 * 下方的事务回滚恢复，本类**不再删旧产物**（旧版 program + 旧版侧车是「上一版配对」，
 * 删掉它只会把可降级的状态破坏掉；2026-10-22 审查修正，此前实现删的是 `target` 而非
 * 刚落的中间文件，注释与行为不符）。
 * 声明是 `-`（写侧没给哈希）时只报 `programSha256Verified = false`，不拦。
 *
 * **事务**：program 与 meta 是一对，必须同版。规划期**双向联动**——任一侧与 APK 不一致
 * 就整对重装（单向「program 变 ⇒ meta 跟」之外，补上「meta 变 ⇒ program 跟」，否则
 * 单独换 meta 也是错配）；执行前把两个目标的旧版备份成 `.bak`，全部成功才删备份，
 * 任一步失败（SHA 不配对、中途 IO 错误……）回滚到「上一版配对」。没有这层事务时，
 * 中途失败会留下「新 program + 旧 meta」，下一轮字节相符双双跳过，错配就永久化。
 * 头部只读前 [HEADER_BYTES] 字节 —— 侧车 49 MB，为 3 行头部做整份解析不划算（见
 * [declaredProgramSha] 的注释）。
 *
 * ## 跨进程互斥（DEV-02）
 *
 * 真机实证：解包中途 Activity 被回收/重建时，上一代的解包线程照常跑完，下一代不认得它，
 * 于是两条解包并发写同一批目标与同一批 `.tmp`。表现是其中之一在 [copyStep] 开头
 * 「无法删除解包中间文件」。进程内的槽（宿主侧）只能挡住同一进程内的两代 Activity，
 * 挡不住「宿主进程被杀后重启」与「多进程宿主」，所以互斥必须落在文件系统上：
 *
 * - 锁文件是数据根下的 [LOCK_FILE]（`gameRoot` 是两侧共用的那个根，见 `GameStorage.gameRoot`）；
 * - 用 [RandomAccessFile] 的 `channel.tryLock()` 拿**整文件排他锁**（OS 级 `fcntl`/`LockFile`，
 *   跨进程真实生效；JVM 的 `synchronized` 与静态锁只覆盖本进程，不满足这条）；
 * - 持锁之后才跑 [plan]：第二实例要么等到锁、要么直接看到「已就位」（`needed` 全空）；
 * - 等待有界（[LOCK_WAIT_TIMEOUT_MS]，可用构造参数缩短，测试用），超时抛**带锁路径**的
 *   [AssetInstallException]，明确写「另一实例可能仍在解包」，不静默成功；
 * - 同 JVM 的第二实例拿锁时 JVM 会抛 [OverlappingFileLockException]（不是返回 null）：
 *   这里捕获并转入同一条等待路径，不让它漏成运行时异常；
 * - 释放靠 `finally` 里 `FileLock.release()` + `channel.close()`：进程被 kill 时由内核回收，
 *   不会留下「锁住了但没人解锁」的死锁盘。
 *
 * 锁文件本身是 0 字节常驻文件，不算解包产物：`GameStorage.looksLikeGameRoot` 只认
 * `emuera.config` 与 `erbc.v1/program.erbc` 两个标志，不受它影响。
 */
class GameAssetInstaller(
    private val assets: GameAssetTree,
    private val gameRoot: File,
    /**
     * 等另一实例放锁的上限（毫秒），到点抛 [AssetInstallException]。
     *
     * 缺省 [LOCK_WAIT_TIMEOUT_MS]：首启全量解包（361.1 MiB）在慢机上要数分钟，
     * 等待方必须给对面留出整轮的时间；超过这个上限说明不是「正常的一轮首启」。
     */
    private val lockWaitMillis: Long = LOCK_WAIT_TIMEOUT_MS,
    /** 等待期的轮询间隔（毫秒）；只影响抢锁的响应速度，不影响正确性。 */
    private val lockPollMillis: Long = LOCK_POLL_MS,
    /**
     * **更新档**：包里有这条资产就一律重写目标，不再比字节数（缺省 false = 首启档）。
     *
     * 首启档的判据是「目标存在且长度相同 ⇒ 跳过」，这对「APK 里那份没变」是对的，对
     * **数据更新**是致命的：新立绘与旧的恰好同长（webp 重编码很容易撞长度）就会被判成
     * 「已经装好了」，于是更新通道跑完、玩家看到的还是老图。更新档把「长度核对」换成
     * 「包里有就必须落一次盘」，其余语义一条不动：
     *
     * - `emuera.config` **仍然只拷一次**：那是玩家能在游戏内改写的配置，覆盖等于抹掉设置；
     * - 同目录临时文件 + 改名的**逐文件原子性**不变，中途失败不会留下半写的文件；
     * - program / 侧车 / 偏移表的**配对事务与 sha 核对**不变，仍是「要么整对新版、要么整对旧版」；
     * - 包里没有的条目**不删**：更新只覆盖包内条目，不清理盘上残留（与首启档一致，
     *   也让「只发变化部分的小包」成为可能）。**差量档是这条的例外**：归档根带一张删除表，
     *   表内条目才删、表外一律不动，见类头「差量档」段与 [DeltaDeletionSource]。
     */
    private val forceReplace: Boolean = false,
    /**
     * 本轮应用成功后把**文件级摘要**写进 `update/data-digest.txt`，并给它标这个版本号；
     * 缺省 **-1 = 完全不写**（老调用点）。
     *
     * 为什么由解包器自己写、而不是让更新通道写：摘要的内容是「这一轮到底落了哪些字节」，
     * 只有落盘现场拿得到（每条的字节数与边拷边算的 SHA-256）。让调用方事后补一份，就得把
     * 整棵 680 MiB 的树再读一遍才能算出同样的东西 —— 那正是差量要省掉的成本。
     *
     * **首启 / 换 APK 的随包解包也应该传本机 versionCode**（C-1）：新装一盘时这一趟会把
     * APK 里的资产全落一遍，顺手就攒出完整摘要，于是 `data-version.txt` 与 `data-digest.txt`
     * 同源同值、差量基线立刻可用，不必先花 600 MiB 走一次全量更新。换 APK 时盘上已有旧数据、
     * 本轮只落变化的那部分，这时摘要靠 [writeDataDigest] 的「跳过条目必须有出处」那条闸
     * 兜底：继承不到旧摘要就不写半张表。
     *
     * 传进来的数应当与调用方写进 `update/data-version.txt` 的**同一个** `assets-version`
     * 一致（两份文件一起说真话才有意义）；不一致时以版本号为判据、摘要只做体检，
     * 见类头「文件级摘要」段。
     */
    private val recordDataVersion: Long = -1L,
) {

    /** 本轮要不要为落盘的文件算内容摘要（= 调用方给了合法的版本号）；见 [recordDataVersion]。 */
    private val wantsDigest: Boolean = recordDataVersion >= 0L

    /** 单条进度：`copied` / `total` 都按**资产条目**计（分片各算一条），`label` 是正在落盘的资产名。 */
    class Progress(
        val copied: Int,
        val total: Int,
        val label: String,
    )

    /**
     * 一轮解包的结果。
     *
     * @param copiedFiles 本轮真正写入的文件数（0 = 已在位，一个字节都没动）
     * @param skippedFiles 目标已在位而跳过的文件数
     * @param copiedBytes 本轮写入的字节数
     * @param programSha256Verified program 是否按侧车声明核对通过（没有 program 可拷时为 false）
     * @param deletedFiles **差量档**按删除表真正删掉的盘上文件数（全量/首启档恒 0）
     * @param blockedDeletions 删除表里**没执行**的行数：越出资产范围、撞保护名单、
     *   指向目录、或包内自己也带了这条。非 0 说明这张表与发布侧的口径不符，
     *   调用方**必须记日志并回传**（数据照样是好的，只是该删的旧文件还留在盘上）
     * @param recordProblem 文件级摘要**没写下去**的原因（null = 写好了，或本轮没要求写）；
     *   非空不影响本轮数据的正确性，只影响下一轮能不能走差量
     */
    class Result(
        val copiedFiles: Int,
        val skippedFiles: Int,
        val copiedBytes: Long,
        val programSha256Verified: Boolean,
        val deletedFiles: Int = 0,
        val blockedDeletions: Int = 0,
        val recordProblem: String? = null,
    )

    /**
     * 把 assets 里存在的游戏数据解包到 [gameRoot]；重复调用幂等。
     *
     * 只做「该拷什么」的规划与落地；文件系统与资产读写的失败都会包成带路径的
     * [AssetInstallException] 文案（屏上直接可读）。
     *
     * **整轮在 [LOCK_FILE] 的跨进程排他锁里跑**（见类头「跨进程互斥」段）：另一实例在解包时
     * 本调用最多等 [LOCK_WAIT_TIMEOUT_MS]，等到之后 `plan()` 看到的目标已在位 ⇒ 直接返回
     * 「跳过 N 个」，不会去碰对方的中间文件；等不到则抛可读的 [AssetInstallException]。
     * 锁与中间文件无关：`.tmp` 的清理语义与失败文案保持原样。
     *
     * @param onProgress 每落完一个资产条目回调一次（真机上就是 logcat 的「解包中 N/M」）
     */
    fun install(onProgress: (Progress) -> Unit = {}): Result = withInstallLock { installLocked(onProgress) }

    /** [install] 的本体（此时已持有跨进程锁；规划与落盘全在锁内）。 */
    private fun installLocked(onProgress: (Progress) -> Unit): Result {
        // 差量档的资格在**动盘之前**就问完：包里只带半对产物（有 program 没侧车）就整包拒掉，
        // 这时候一个字节都还没落，失败是「什么都没发生」而不是「装坏了一版」（见 KDoc）。
        val deltaDeletions = deltaDeletionsOrNull()
        if (deltaDeletions != null) requireDeltaArtifactPair()
        val steps = plan()
        val pending = steps.filter { it.needed }
        val total = pending.sumOf { it.sources.size }
        var copiedFiles = 0
        var copiedBytes = 0L
        var verified = false
        // 本轮真正写过的文件 → 摘要条目；只有要写摘要时才攒（见 [recordDataVersion]）。
        val written = LinkedHashMap<String, DataDigestEntry>()
        // 1 MiB 缓冲整轮只分配一次：几百个小文件共用同一块，避免每文件一块的垃圾叠加。
        val buffer = ByteArray(BUFFER_BYTES)
        var done = 0

        fun advance(label: String) {
            done++
            onProgress(Progress(done, total, label))
        }

        // 配对事务：program 与 meta 必须同版（见类头「配对事务」段）。执行前把两个目标
        // 的旧版备份成 .bak；全部成功才删备份；任一步失败回滚 —— 删掉本轮已 rename 的
        // 新 target、恢复备份、清掉全部中间文件，让盘上停在「上一版配对」。恢复是**收集式**
        // 的（一条失败不带走其余，未恢复项逐条点名，判据见 [rollbackPairTargets]）。
        val pairTargets = pending.filter { it.pairArtifact }.map { it.target }.distinct()
        val backups = LinkedHashMap<File, File>()
        try {
            if (pending.isNotEmpty()) ensureDir(gameRoot)
            for (target in pairTargets) {
                val bak = File(target.parentFile, target.name + BAK_SUFFIX)
                if (bak.isFile) {
                    // 上轮回滚失败的残留：target 缺失时把 bak 取回当旧产物（取回后走下面
                    // 的正常备份），并存时保守清掉 —— 状态本身不该出现。
                    if (!target.isFile) {
                        if (!bak.renameTo(target)) {
                            throw AssetInstallException("无法恢复上一版产物：${bak.absolutePath}")
                        }
                    } else if (!bak.delete()) {
                        throw AssetInstallException("无法清理残留备份：${bak.absolutePath}")
                    }
                }
                if (!target.isFile) continue
                if (!target.renameTo(bak)) {
                    throw AssetInstallException("无法备份旧产物：${target.absolutePath}")
                }
                backups[target] = bak
            }
            for (step in pending) {
                ensureDir(step.target.parentFile)
                val outcome = copyStep(step, buffer, ::advance)
                copiedBytes += outcome.bytes
                copiedFiles++
                if (step.verifyProgram) verified = outcome.verified
                val sha = outcome.sha256Hex
                if (sha != null && recordedInDigest(step.relPath)) {
                    written[step.relPath] = DataDigestEntry(outcome.bytes, sha)
                }
            }
            for (bak in backups.values) bak.delete()
        } catch (t: Throwable) {
            // 中间文件先清（它不依赖回滚成败）：旧写法把这段排在恢复循环**之后**，
            // 于是「恢复失败」这一路连本轮的 .tmp 都没扫掉，下次解包又多一份孤儿。
            for (step in pending) {
                File(step.target.parentFile, step.target.name + TMP_SUFFIX).delete()
            }
            throw rollbackPairTargets(backups, t)
        }
        // 删除表在配对事务**之后**：新内容先就位，再按表清理。这个次序让「同一条路径既被
        // 包内更新、又写在删除表里」这种自相矛盾的表自动按「包里的那份」兑现（见
        // [applyDeltaDeletions] 的表内优先级）。
        val deletions = applyDeltaDeletions(deltaDeletions)
        // 配置缺键补齐在配对事务**之外**：它不参与 program/meta 的配对回滚，失败也不该
        // 把已经落好的产物一起回滚掉（两件事的成败互不牵连）。
        mergeConfigUpgrades()
        // 摘要放在最后一拍：本轮任何一步没成（含配置补齐抛出来）就不会有摘要 ——
        // 宁缺勿错，写一份「说自己是第 N 版却只覆盖了三个文件」的表比没有表更坏。
        val recordProblem = writeDataDigest(
            written = written,
            skippedInPlace = steps.filter { !it.needed }.map { it.relPath },
            deletedPaths = deletions.deletedPaths,
            isDelta = deltaDeletions != null,
        )
        return Result(
            copiedFiles = copiedFiles,
            skippedFiles = steps.size - copiedFiles,
            copiedBytes = copiedBytes,
            programSha256Verified = verified,
            deletedFiles = deletions.deletedCount,
            blockedDeletions = deletions.blockedCount,
            recordProblem = recordProblem,
        )
    }

    // ------------------------------------------------------------------
    // 配对事务的回滚
    // ------------------------------------------------------------------

    /**
     * 配对事务失败后的**收集式**回滚：把 [backups] 里每一份 `.bak` 尽力恢复到目标名，
     * 逐条判成败、不把第一条失败当整批失败；全部恢复成功时把原始失败 [cause] 原样交回。
     *
     * ## 为什么不能「第一条失败就抛」
     *
     * 旧写法在恢复循环里直接 `throw`：于是**第一条恢复失败就把剩下的备份全丢在原处**。
     * 真实事故形态是磁盘满 / fd 耗尽这一类「一次失败、次次失败」的环境：program 恢复成功、
     * meta 没恢复（或反过来），盘上就是「新 program + 旧 meta」这类**半套配对** ——
     * 而装载侧读到的正是这一对（侧车声明的 `program-sha256` 与 program 本体不符 ⇒ 屏上
     * 是「产物缺失」，看不出它其实是上一轮的半成品）。
     * 收集式之后：能恢复的**全都**恢复，恢复不了的**逐条**写进异常文案（屏上可见），
     * 盘上残留的 .bak 仍由下一轮 [installLocked] 开头的恢复逻辑兜底（口径不变）。
     *
     * @param cause 进入 catch 的那个原始失败（首个失败）；回滚干净时它就是要抛的那一个
     * @param restore 恢复动作（默认 `renameTo`）；**只为单测可注入**而存在 —— 真机路径传缺省值，
     *        「renameTo 回 false」在纯 JVM 上造不出来（临时目录里改名不会失败），
     *        所以那组判据走这条注入口，见 `GameAssetInstallerTest` 的 `回滚收集式` 用例。
     * @return 调用方直接 `throw` 的异常：回滚干净 = [cause] 本身；有未恢复项 = 包着 [cause] 的
     *         [AssetInstallException]（文案列出每一项未恢复的 `备份 → 目标`）。
     */
    internal fun rollbackPairTargets(
        backups: Map<File, File>,
        cause: Throwable,
        restore: (bak: File, target: File) -> Boolean = { bak, target -> bak.renameTo(target) },
    ): Throwable {
        if (backups.isEmpty()) return cause
        val unrecovered = ArrayList<String>()
        var restored = 0
        for ((target, bak) in backups) {
            // 本轮已 rename 就位的新产物先让位（旧写法同一条语句）。删不掉也不要紧：
            // 下面 rename 会照原样失败，那条路记进 unrecovered。
            target.delete()
            if (!bak.isFile) {
                // 备份自己不在（被人删 / 上一轮回滚后又手抖清过）：没东西可恢复，
                // 也不算失败 —— 盘上停在「缺这个产物」，下一轮 install 会重新落它。
                continue
            }
            // runCatching：renameTo 正常只回 false，但 NFS / 特殊文件系统上也可能直接抛
            // （IOException）。抛与回 false 在这里同一档：记未恢复、继续跑下一份。
            val ok = runCatching { restore(bak, target) }.getOrDefault(false)
            if (ok) restored++ else unrecovered += "${bak.absolutePath} → ${target.absolutePath}"
        }
        if (unrecovered.isEmpty()) return cause
        // 原始失败（首个失败）保留在 cause 链上：屏上那句「产物缺失」的线索不能因为回滚
        // 又出岔子就被顶掉 —— 排查时先要看得见的仍是「这一轮为什么失败」。
        return AssetInstallException(
            "回滚未完全成功：${unrecovered.size}/${backups.size} 项没能恢复到目标名（" +
                unrecovered.joinToString("；") + "）；已恢复 $restored 项，" +
                "残留的 .bak 下一轮解包开头会兜底取回。原始失败：" +
                (cause.message ?: cause.javaClass.simpleName),
            cause,
        )
    }

    // ------------------------------------------------------------------
    // 跨进程互斥
    // ------------------------------------------------------------------

    /**
     * 在 [LOCK_FILE] 的排他锁里跑 [block]。
     *
     * 锁的作用域是「规划 + 落盘」整轮（[install] 的全部工作）：只锁 [plan] 不锁落地等于没锁，
     * 只锁落地则是「两边都规划成 needed，先到的拷完、后到的再拷一遍」。
     *
     * 释放顺序是 `FileLock.release()` 再 `close()`，两步都在 `finally` 里：抛异常、被 kill、
     * 线程被中断都只可能留下「channel 已关 ⇒ OS 锁已被内核回收」，不会卡死下一轮。
     */
    private fun <T> withInstallLock(block: () -> T): T {
        ensureDir(gameRoot)
        val lockFile = File(gameRoot, LOCK_FILE)
        val raf = try {
            // 用 RandomAccessFile 而不是 java.nio.file.Files：minSdk 21 上 java.nio.file 不保证
            // 可用，而 RandomAccessFile 的 channel 从 API 1 就有（tryLock 自 API 1 起有）。
            openLockFile(lockFile)
        } catch (e: IOException) {
            throw AssetInstallException(
                "无法打开解包互斥锁：${lockFile.absolutePath}（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        }
        raf.use { file ->
            val channel = file.channel
            val lock = try {
                awaitLock(channel, lockFile)
            } catch (t: Throwable) {
                closeQuietly(channel)
                throw t
            }
            try {
                return block()
            } finally {
                try {
                    lock.release()
                } catch (_: IOException) {
                    // 释放失败只可能是 channel 已失效；close 之后 OS 侧锁同样已被回收。
                }
                closeQuietly(channel)
            }
        }
    }

    /**
     * 有界等待排他锁：拿到就返回，超时抛可读错误。
     *
     * 两条「拿不到」的路径合并处理：
     * - `tryLock()` 返回 null = **另一进程**持锁；
     * - 抛 [OverlappingFileLockException] = **同一 JVM** 的另一实例/线程持锁（JVM 在进程内
     *   先做重叠检测，直接抛而不是返回 null）。DEV-02 的两份解包经常正是同 JVM 两代 Activity，
     *   所以这条必须捕获后走同一条等待，不能原地漏成运行时异常。
     *
     * TODO(verify)：外部数据根（`Android/data/...`，走 FUSE）上的 `fcntl` 排他锁支持没有真机
     * 取证；若某机型上 `tryLock` 直接抛 `IOException`（不支持），这里会转成可读的
     * 「无法获取解包互斥锁」。卡点：需要一台外部根可写的真机，`/proc/locks` 读数。
     */
    private fun awaitLock(channel: FileChannel, lockFile: File): FileLock {
        val deadlineNanos = System.nanoTime() + lockWaitMillis * NANOS_PER_MILLI
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (e: IOException) {
                throw AssetInstallException(
                    "无法获取解包互斥锁：${lockFile.absolutePath}（${e.javaClass.simpleName}: ${e.message}）",
                    e,
                )
            }
            if (lock != null) return lock
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) throw lockTimeout(lockFile)
            // 轮询间隔按剩余时间截断：最后一跳最多睡到 deadline，不越界多睡一轮。
            val sleepMillis = (remainingNanos / NANOS_PER_MILLI)
                .coerceAtMost(lockPollMillis.coerceAtLeast(1L))
                .coerceAtLeast(1L)
            try {
                Thread.sleep(sleepMillis)
            } catch (e: InterruptedException) {
                // 中断 = 宿主不要这一代了：把中断标志还回去（上层可能还在用），
                // 但不静默当成功 —— 数据没保证就位，必须让宿主走失败屏。
                Thread.currentThread().interrupt()
                throw AssetInstallException(
                    "等待另一实例解包时被中断：锁 ${lockFile.absolutePath}；" +
                        "另一实例可能仍在解包，数据可能未就位",
                    e,
                )
            }
        }
    }

    /** 等待超时的可读错误：必须写出锁路径与「对面可能还在解包」，屏上才判得出该不该重启重试。 */
    private fun lockTimeout(lockFile: File): AssetInstallException {
        val waited = if (lockWaitMillis >= 1000L) "${lockWaitMillis / 1000} 秒" else "$lockWaitMillis 毫秒"
        return AssetInstallException(
            "等待另一实例解包超时（$waited）：锁 ${lockFile.absolutePath} 仍被占用；" +
                "另一实例可能仍在解包，数据可能未就位，请稍后重启应用再试",
        )
    }

    /**
     * 打开（必要时创建）锁文件，open 失败在 [LOCK_OPEN_RETRY_MS] 预算内重试。
     *
     * 只重试 open：`tryLock` 那边的「拿不到」已经由 [awaitLock] 的有界等待覆盖，
     * 这里兜的是「文件还开不出来」的那一拍（Windows 并发首建，见 [LOCK_OPEN_RETRY_MS]）。
     * 中断照旧把标志还回去再抛 —— 上层可能还靠它收摊。
     */
    private fun openLockFile(lockFile: File): RandomAccessFile {
        val deadlineNanos = System.nanoTime() + LOCK_OPEN_RETRY_MS * NANOS_PER_MILLI
        while (true) {
            try {
                return RandomAccessFile(lockFile, "rw")
            } catch (e: FileNotFoundException) {
                if (System.nanoTime() >= deadlineNanos) throw e
                try {
                    Thread.sleep(LOCK_OPEN_RETRY_POLL_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
    }

    private fun closeQuietly(channel: FileChannel) {
        try {
            channel.close()
        } catch (_: IOException) {
            // 关闭失败无处可报：OS 锁随进程退出回收，这里不覆盖正在上抛的解包失败。
        }
    }

    // ------------------------------------------------------------------
    // 差量档：删除表
    // ------------------------------------------------------------------

    /**
     * 这一轮是不是差量档，是就返回那张删除表；不是（全量档、APK 随包档）返回 null。
     *
     * 「是差量档但表是空的」返回**空表**而不是 null：差量档还有第二条语义要兑现
     * （产物对必须成对出现，见 [requireDeltaArtifactPair]），用 null 表示「不是差量档」、
     * 用空表表示「是差量档、这版没东西要删」，两件事才分得开。
     */
    private fun deltaDeletionsOrNull(): List<String>? {
        val source = assets as? DeltaDeletionSource ?: return null
        return if (source.isDeltaPackage) source.deltaDeletions() else null
    }

    /**
     * 差量档的产物对闸：`program.erbc`（或分片清单）与 `program.meta` 必须**同时**在包里。
     *
     * 只带一条就抛，而且是在动盘之前抛（此时没有任何写入，失败等于「什么都没发生」）。
     * 判错的后果不是「少装一点」而是「装坏」：侧车记的是**这条产物的**装载期元数据
     * （函数标志与事件合成入口、`#DIM` 声明、PRINTDATA 块归属、`$标签` 表 —— 见 core
     * `BootMetadata` 类头「为什么要有侧车」段），换一半就等于拿旧地图走新路。
     * 这正是类头「配对事务」段早就为**首启档**堵掉的形态（「中途失败会留下新 program + 旧 meta，
     * 下一轮字节相符双双跳过，错配就永久化」），差量档把同一道闸从「事务」换成「拒收」：
     * 差量包的表里既然只有一条，说明发布侧本来就只打算换一条，解包器没有第三份信息可以补，
     * 唯一安全的动作是不装。全量档与首启档不查这条 —— 它们本来就整对带
     * （`emit_update.py` 的 `ARTIFACT_FILES` 白名单），加了只会让历史包莫名多一道闸。
     *
     * `program.stmt.idx` 不在闸门内：那是**可选**加速件，缺了装载侧回落扫描，语义不变。
     */
    private fun requireDeltaArtifactPair() {
        val hasProgram = assets.fileSize(PROGRAM_ASSET) != null || assets.fileSize(PARTS_ASSET) != null
        val hasMeta = assets.fileSize(META_ASSET) != null
        if (hasProgram == hasMeta) return
        throw AssetInstallException(
            if (hasProgram) {
                "差量包只带了 program 没带侧车 $META_FILE：装上就是「新产物 + 旧侧车」的错配，已拒绝应用"
            } else {
                "差量包只带了侧车 $META_FILE 没带 $PROGRAM_FILE：侧车记录的语句表与盘上产物对不上，已拒绝应用"
            },
        )
    }

    /** 一轮删除表的结果，只给 [Result] 用。 */
    private class DeletionOutcome(
        val deletedCount: Int,
        val blockedCount: Int,
        val deletedPaths: List<String>,
    )

    /**
     * 执行差量删除表：只删表里列的、而且只删「解包器自己管得着」的。
     *
     * 四条判据按顺序过，任何一条不过就**跳过并计入 [Result.blockedDeletions]**（不抛，理由见
     * 类头「差量档」段末：一张写歪的表不该把玩家的更新永远卡在重试循环里）：
     *
     * 1. [deletionIsProtected] —— 任一段撞保护名单（`sav`、`update`、`emuera.config`、
     *    `.install.lock`，与发布侧 `devtools/emit_update.py` 的 `NEVER_BUNDLED` 同名单）；
     * 2. [deletionWithinAssetUniverse] —— 必须落在 [ARTIFACT_DIR] 或 [DATA_DIRS] 之内：
     *    解包器从不往游戏根的 `dat`、`font`、`lang`、`profile` 这些地方落盘，也就没资格删它们；
     * 3. **包内优先级**：这条路径在包里也有 ⇒ 按新内容兑现（已在上面的拷贝里落好），
     *    表里那条不再执行。这是「既改又删」的自相矛盾表的唯一自洽解，也不计受阻；
     * 4. 产物对的两条（[PROGRAM_FILE] / [META_FILE]）只能被替换不能被删 —— 想清空产物请发全量档。
     *
     * 过了四关再看盘：canonical 路径必须仍在游戏根**之内**（表已由上游按条目名口径归一，
     * 这一道是防「软链/大小写折叠」这类归一化挡不住的形态）；盘上没有 ⇒ 幂等，静默不算受阻；
     * 是目录 ⇒ 不做递归删除，计受阻；**删不下去 ⇒ 抛**（`File.delete()` 返回 false 是真故障：
     * 只读挂载、被别的进程占着，那必须让屏上看得到）。
     *
     * 空目录不收拾：删完文件留下的空目录对装载侧无意义（读的是文件路径，不是目录清单），
     * 而「顺手回收空父目录」要再判一遍「这目录是不是别人还有用」，赔进去的复杂度不值。
     */
    private fun applyDeltaDeletions(paths: List<String>?): DeletionOutcome {
        if (paths.isNullOrEmpty()) return DeletionOutcome(0, 0, emptyList())
        val rootPath = canonicalRootPath()
        val deleted = ArrayList<String>()
        var blocked = 0
        for (rel in paths) {
            if (deletionIsProtected(rel) || !deletionWithinAssetUniverse(rel)) {
                blocked++
                continue
            }
            if (assets.fileSize(rel) != null) continue
            if (rel == PROGRAM_ASSET || rel == META_ASSET) {
                blocked++
                continue
            }
            val target = File(gameRoot, rel)
            val canonical = runCatching { target.canonicalFile }.getOrNull()
            if (canonical == null || !isInsideRoot(rootPath, canonical.absolutePath)) {
                blocked++
                continue
            }
            if (!target.exists()) continue
            if (!target.isFile) {
                blocked++
                continue
            }
            if (!target.delete()) {
                throw AssetInstallException("无法删除差量表指出的旧文件：${target.absolutePath}")
            }
            deleted += rel
        }
        return DeletionOutcome(deleted.size, blocked, deleted)
    }

    /** 游戏根的 canonical 形态；根还不存在时退回绝对路径（此时任何删除目标都必然「盘上没有」）。 */
    private fun canonicalRootPath(): String =
        runCatching { gameRoot.canonicalFile.absolutePath }.getOrDefault(gameRoot.absolutePath)

    /** [path] 是否等于 [rootPath] 或落在它下面（按本机的路径分隔符判段，不做字符串前缀糊弄）。 */
    private fun isInsideRoot(rootPath: String, path: String): Boolean {
        if (path == rootPath) return true
        if (path.startsWith(rootPath + File.separatorChar)) return true
        // Windows 上 canonical 恒用反斜杠，但测试装置与上游条目名可能给出正斜杠形态：
        // 两种分隔符都认，认漏一种会把合法删除误判成「越界」（Linux/Android 上这条永不成立）。
        return File.separatorChar == '\\' && path.startsWith("$rootPath/")
    }

    // ------------------------------------------------------------------
    // 文件级摘要（update/data-digest.txt）
    // ------------------------------------------------------------------

    /**
     * 把本轮状态并进摘要并落盘；返回**没写下去的原因**（null = 写好了或本轮不需要写）。
     *
     * 四种「不需要写/不能写」：
     * - [recordDataVersion] 小于 0 ⇒ 调用方没要求记（老调用点），一个字节都不写；
     * - **差量档而盘上没有可读摘要** ⇒ 不写（见类头：半张表会让下次体检假报「一致」）；
     * - 本轮既没写文件也没删文件 ⇒ 不重写（幂等重跑时不该把同一份内容再刷一遍，
     *   也不该因为上一份摘要读不出就凭空造一张只有 0 条的表）；
     * - 本轮**已在位而跳过**的条目里有上一份摘要覆盖不到的 ⇒ 不写（C-1）：随包解包第一趟
     *   可能「部分文件已在位被跳过」，若没有旧摘要可继承，写下去的表只覆盖本轮拷的那几个
     *   文件，而 checkDataDigest 只查表里有的条目 ⇒ 假报「一致」。缺的条目要算 SHA 就得把
     *   整棵 680 MiB 再读一遍 —— 那正是这条通道要省的成本，所以宁可不写：旧盘（本特性之前
     *   装的）第一次仍按「没摘要」处理，下一次全量更新会把完整表重建出来。
     *
     * 并表口径：**上一份 ⊕ 本轮写过的 −本轮删掉的**（按相对路径覆盖）。删掉的一条如果本轮
     * 又写了（差量表与包内条目撞车，按包内兑现），它在「本轮写过」里活下来 —— 与
     * [applyDeltaDeletions] 的优先级一致。
     *
     * @param skippedInPlace 本轮判定「已在位、不用拷」的条目相对路径（[Step.needed] 为 false）；
     *   只用来判断表是否完整，不参与并表内容
     */
    private fun writeDataDigest(
        written: Map<String, DataDigestEntry>,
        skippedInPlace: List<String>,
        deletedPaths: List<String>,
        isDelta: Boolean,
    ): String? {
        if (recordDataVersion < 0L) return null
        val previous = readDataDigest(gameRoot)
        if (isDelta && previous == null) return null
        if (written.isEmpty() && deletedPaths.isEmpty()) return null
        val merged = LinkedHashMap<String, DataDigestEntry>()
        previous?.let { merged.putAll(it.entries) }
        merged.putAll(written)
        for (rel in deletedPaths) merged.remove(rel)
        val inherited = previous?.entries?.keys.orEmpty()
        val uncovered = skippedInPlace.filter { recordedInDigest(it) && it !in inherited }
        if (uncovered.isNotEmpty()) {
            return "本轮有 ${uncovered.size} 条资产已在位被跳过、又没有上一份摘要可继承" +
                "（例如 ${uncovered.first()}）：宁可不写，也不写只覆盖 ${merged.size} 条的半张表"
        }
        return try {
            writeDataDigestAtomic(merged, recordDataVersion)
            null
        } catch (e: Throwable) {
            // 摘要写不下去不牵动本轮：数据已经落对，缺的只是下一轮的凭据（下一轮回落全量）。
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** 同目录 `.tmp` + 改名（与 [copyStep] 同纪律）：摘要写成半截会被读成「没有摘要」。 */
    private fun writeDataDigestAtomic(entries: Map<String, DataDigestEntry>, version: Long) {
        val file = dataDigestFile(gameRoot)
        ensureDir(file.parentFile)
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        if (tmp.exists() && !tmp.delete()) {
            throw AssetInstallException("无法删除摘要中间文件：${tmp.absolutePath}")
        }
        try {
            FileOutputStream(tmp).use { out ->
                out.write(encodeDataDigest(version, entries).toByteArray(StandardCharsets.UTF_8))
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw AssetInstallException("无法落盘 ${file.absolutePath}")
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    // ------------------------------------------------------------------
    // 规划
    // ------------------------------------------------------------------

    /**
     * 本轮要落盘的文件清单（含「已在位、不用拷」的条目，用来统计跳过数）。
     *
     * 顺序固定：program → 侧车 → manifest → 配置 → CSV → resources → sound。
     * program 的两种形态（单条 / 分片）只取其一 —— 有清单就**只**按清单拼，
     * 免得同一目录里残留的单条与分片互相打架。
     */
    private fun plan(): List<Step> {
        val steps = ArrayList<Step>()
        val artifactDir = File(gameRoot, ARTIFACT_DIR)

        val manifestParts = readPartsManifest()
        val programTarget = File(artifactDir, PROGRAM_FILE)
        val metaTarget = File(artifactDir, META_FILE)

        // meta 是否需要重拷（双向联动的另一半）：APK 侧车与盘上 meta 不一致时，program
        // 也要跟着整对重装 —— 单独换 meta 会留下「旧 program + 新 meta」的错配。
        // 用一次 probe 同时回答存在性与长度（见 probe KDoc），别为同一条资产问两遍。
        val metaSource = probe(META_ASSET)
        val metaStale = metaSource != null && needsCopy(metaTarget, metaSource.expectedBytes)

        // 单条形态只在「没有分片清单」时才探：probe 会 open 一次，别为一条不要的资产白读。
        val programSingle = if (manifestParts == null) probe(PROGRAM_ASSET) else null
        val programStep: Step? = when {
            manifestParts != null -> Step(
                target = programTarget,
                sources = manifestParts,
                relPath = PROGRAM_ASSET,
                needed = forceReplace || metaStale ||
                    needsCopy(programTarget, manifestParts.sumOf { it.expectedBytes ?: 0L }),
                verifyProgram = true,
                pairArtifact = true,
            )
            programSingle != null -> Step(
                target = programTarget,
                sources = listOf(programSingle),
                relPath = PROGRAM_ASSET,
                needed = forceReplace || metaStale || needsCopy(programTarget, programSingle.expectedBytes),
                verifyProgram = true,
                pairArtifact = true,
            )
            else -> null
        }
        programStep?.let { steps += it }
        // program 重拷时侧车必须跟着换：两侧只保证「同一版配对」，不能一新一旧。
        val programStale = programStep?.needed == true

        metaSource?.let { source ->
            steps += Step(
                target = metaTarget,
                sources = listOf(source),
                relPath = META_ASSET,
                needed = forceReplace || programStale || needsCopy(metaTarget, source.expectedBytes),
                pairArtifact = true,
            )
        }

        // 段 3 语句记录偏移表侧车（2026-10-23 装载提速；**可选**）：
        // APK 里带了就跟 program/meta 同对待（program 重拷 ⇒ 它也重拷，避免一新一旧）；
        // 旧包没有这条资产 ⇒ 装载期自动回落扫描，行为与提速前逐字相同。
        val stmtIdxTarget = File(artifactDir, STMT_IDX_FILE)
        probe(STMT_IDX_ASSET)?.let { source ->
            steps += Step(
                target = stmtIdxTarget,
                sources = listOf(source),
                relPath = STMT_IDX_ASSET,
                needed = forceReplace || programStale || needsCopy(stmtIdxTarget, source.expectedBytes),
                pairArtifact = true,
            )
        }

        val manifestTarget = File(artifactDir, MANIFEST_FILE)
        probe(MANIFEST_ASSET)?.let { source ->
            steps += Step(
                target = manifestTarget,
                sources = listOf(source),
                relPath = MANIFEST_ASSET,
                needed = forceReplace || needsCopy(manifestTarget, source.expectedBytes),
            )
        }

        // 配置只拷一次：游戏内设置会写回它（`ConfigData.SaveToFile`），覆盖等于抹掉玩家配置。
        // 新包新增键的升级走 installLocked 收尾的 mergeConfigUpgrades（缺键才补、不覆盖）。
        val configTarget = File(gameRoot, CONFIG_FILE)
        probe(CONFIG_FILE)?.let { source ->
            steps += Step(
                target = configTarget,
                sources = listOf(source),
                relPath = CONFIG_FILE,
                needed = !configTarget.isFile,
            )
        }

        for (dir in DATA_DIRS) walkTree(dir, steps)
        return steps
    }

    /**
     * 一次探针同时回答「这条资产在不在」与「它是 STORED 的话有多少字节」。
     *
     * 分成 `assetExists` + `fileSize` 两次问，真机上就是每条资产两次 `openFd` —— 压缩条目
     * 每次都吞一个 `FileNotFoundException`（填栈才是成本大头），3,971 条语料在**每次启动**
     * 的规划段都要付这笔白给的开销（`AndroidAssetTree` 的 `fileSize` 段）。
     *
     * @return null = 资产里没有这条路径；否则是只读长度（压缩条目为 null）的 [Step.Source]
     */
    private fun probe(assetPath: String): Step.Source? {
        assets.fileSize(assetPath)?.let { return Step.Source(assetPath, it) }
        val stream = assets.openFile(assetPath) ?: return null
        stream.close()
        return Step.Source(assetPath, null)
    }

    /**
     * 这条资产要不要（重）写：[expected] 已知就按字节数对账，未知时见下。
     *
     * ## 0 字节目标是「残缺」，不是「已装」
     *
     * [expected] == null 只出现在**压缩条目**上，而这些条目恰好全是语料正文
     * （`*.xml` / `*.csv` / `*.ERB`）。它们中间没有任何一条在合法形态下是空文件，
     * 所以「目标存在且长度 0」只能是**上一次落盘没写完**（进程在解包途中被杀、
     * 存储写满、rename 前掉电）留下的空壳。旧判据只看存在性 ⇒ 这个空壳永远修不回来。
     *
     * 这不是理论风险：2026-10-31 有 8 份真机报告在
     * `RM_リソース情報初期化` 的 `XML_DOCUMENT RM_XML_TEMP, LOADTEXT(...)`
     * 上抛「缺少根元素」—— 报告里的 `行49` 是 0 起行号，就是那一条 ——
     * 而 `LOADTEXT` 读成 `""` 只有两条路：文件不存在 / 文件是空的。
     * `ENUMFILES` 刚刚把同一个名字列了出来，所以只剩「空壳」这一种。
     * 两台设备（Android 16 / 一加、vivo）各连报 3~5 次、每次死在同一行，
     * 正是「空壳永久留存」的指纹。
     *
     * 代价：真存在 0 字节的**压缩**资产时每次启动会白拷它一次（一次 `openFile`），
     * 而 `sound/` 那 18 个零字节 `bgm` 标记是 **STORED** 条目（`expected == 0`，
     * 走第一条分支、永远判「已装」），不受本改动影响。
     */
    private fun needsCopy(target: File, expected: Long?): Boolean = when {
        !target.isFile -> true
        expected != null -> target.length() != expected
        else -> target.length() == 0L
    }

    /**
     * 递归收集 [dir] 下的文件（按名字排序，落盘顺序稳定、日志可复现）。
     *
     * 资产里没有「列目录」之外的元信息：文件与空目录在 `AssetManager.list` 下同为空表，
     * 所以判定顺序是「目标已是文件 → 文件」→「有子项 → 目录」→「能打开 → 文件」→「否则空目录，跳过」。
     * 顺序一个都不许换（同名既是文件又是目录前缀的畸形 zip 条目按目录优先），
     * 但长度只问一次 —— 修复前这里问两遍，见 [probe]。
     */
    private fun walkTree(dir: String, steps: MutableList<Step>) {
        for (name in assets.listDir(dir).sorted()) {
            requireSafeAssetName(dir, name)
            val path = "$dir/$name"
            val target = File(gameRoot, path)
            val stored = assets.fileSize(path)
            if (target.isFile) {
                steps += Step(
                    target = target,
                    sources = listOf(Step.Source(path, stored)),
                    relPath = path,
                    needed = forceReplace || needsCopy(target, stored),
                )
                continue
            }
            val children = assets.listDir(path)
            if (children.isNotEmpty()) {
                walkTree(path, steps)
                continue
            }
            if (stored == null) {
                val stream = assets.openFile(path) ?: continue
                stream.close()
            }
            steps += Step(target, listOf(Step.Source(path, stored)), path, needed = true)
        }
    }

    /**
     * assets 条目名只能是**单段文件名**，否则拒绝整个解包。
     *
     * `AssetManager.list` 在真机上只返回单段名（无 `/`），但本函数的入参是 [GameAssetTree]，
     * 契约里没写死这一点：条带 `/`、`\`、NUL 的 `name` 会被拼进 `"$dir/$name"` 再 `File(gameRoot, path)`，
     * `..` 则直接指到 [gameRoot] 之外。解包器是往玩家可见目录里写文件的最后一道闸 ——
     * 坏产物（或以后换成目录实现）不该能写出游戏根。宁可整包拒绝并在屏上报出那一条，
     * 也不静默跳过（跳过会让装载阶段报「产物缺失」，看不出根因）。
     *
     * 只拒绝**整段**为 `.` / `..` 的名字：`v1..2.csv` 这类名字里的 `..` 是合法文件名字符。
     */
    private fun requireSafeAssetName(dir: String, name: String) {
        val bad = name.isEmpty() ||
            name == "." ||
            name == ".." ||
            name.indexOf('/') >= 0 ||
            name.indexOf('\\') >= 0 ||
            name.indexOf('\u0000') >= 0
        if (bad) {
            throw AssetInstallException("APK assets 条目名非法（只能是单段文件名）：$dir/$name")
        }
    }

    /**
     * 读分片清单 `program.parts`（每行「文件名 TAB 字节数」）；没有清单返回 null。
     *
     * 清单是打包侧与解包侧唯一的顺序契约（分片名可能不是字典序），所以顺序**只认行序**。
     * 空清单、缺 TAB、字节数非正、文件名带路径分隔符都直接报错 —— 这些只可能来自坏产物，
     * 静默跳过只会让装载阶段报「产物缺失」，屏上看不出根因。
     */
    private fun readPartsManifest(): List<Step.Source>? {
        // `openFile` 的契约就是「不存在或不是文件返回 null」（见 GameAssetTree），
        // 所以这里不需要先问一遍存在性 —— 那等于白多打一次 openFd。
        val text = assets.openFile(PARTS_ASSET)?.use { input ->
            input.reader(Charsets.UTF_8).use { it.readText() }
        } ?: return null
        val sources = ArrayList<Step.Source>()
        var lineNo = 0
        for (raw in text.lineSequence()) {
            lineNo++
            val line = raw.removeSuffix("\r").trim()
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) {
                throw AssetInstallException("$PARTS_ASSET 第 $lineNo 行不是「文件名 TAB 字节数」：$line")
            }
            val name = line.substring(0, tab).trim()
            val size = line.substring(tab + 1).trim().toLongOrNull()
                ?: throw AssetInstallException("$PARTS_ASSET 第 $lineNo 行字节数不是整数：$line")
            if (size <= 0L) throw AssetInstallException("$PARTS_ASSET 第 $lineNo 行字节数必须为正：$line")
            if (name.contains('/') || name.contains('\\') || name.contains("..")) {
                throw AssetInstallException("$PARTS_ASSET 第 $lineNo 行分片名非法：$name")
            }
            sources += Step.Source("$ARTIFACT_DIR/$name", size)
        }
        if (sources.isEmpty()) throw AssetInstallException("$PARTS_ASSET 是空清单（没有任何分片）")
        return sources
    }

    // ------------------------------------------------------------------
    // 配置缺键补齐（升级路径）
    // ------------------------------------------------------------------

    /**
     * 盘上 `emuera.config` 的缺键补齐：资产配置里有、而盘上没有该键的行，逐行**追加**
     * 到盘上文件尾部；盘上已有键（含玩家改过的值）一概不碰。
     *
     * ## 为什么需要
     *
     * 「只拷一次、存在即跳过」保住了玩家的游戏内设置，但把「新包新增键」永远挡在升级
     * 路径之外：0.2.0 时代装的 gameRoot 没有 `VALID EXTENSIONS FOR LOADTEXT AND
     * SAVETEXT:xml,txt,json`（语料 `emuera.config:73`），4.980 语料的 XML 资源装载
     * （`ERB/SYSTEM.ERB:96-97` 的 `LOADTEXT("plugins/tw_csv_chs.xml")`）在白名单缺
     * `xml` 时读回空串，`XML_GET` 在位置 0 报「缺少根元素」、开局中止（2026-10-31
     * 玩家报告 ×5，同一玩家升级 app 后 47 秒内连报 5 次）。补齐后语义与「全新安装拿到
     * 整份新配置」一致；缺省值语义不受影响（core 缺键回落出厂值，见
     * `HostMethods.validExtensions` 的缺省 `["txt"]`）。
     *
     * ## 口径
     *
     * - 键匹配与 core 解析器同口径（`EmueraConfigParser.loadConfigFile`，
     *   ConfigData.cs:529-543）：首行剥 BOM、行首 `;` 注释跳过、按**所有**冒号切分、
     *   `parts[0].trim().uppercase(Locale.ROOT)`；同一键资产里只认首条。
     * - 追加的行取资产原文（含值与前后空白），行尾 `\n`：语料整份 LF，盘上文件也是
     *   本工程落盘的（同 LF）。
     * - 盘上文件**严格 UTF-8 解不开就整份跳过**：原版对非 UTF-8 配置回落 shift_jis
     *   （`EmueraConfigParser.readConfigFiles` 的口径来源 EncodingHandler.cs:15-41），
     *   这种文件我们不重写 —— 重写等于替玩家换码页；白名单因此回落缺省 `["txt"]`
     *   的语义损失由 core 层承担，换码页的损失无人能兜。
     * - 写侧与 [copyStep] 同纪律：同目录 `.tmp` + 改名，目标名一出现就是完整文件。
     * - 无资产配置、盘上还没有配置（全新安装由拷贝步负责整份落地）、无缺失键：
     *   本步零写入，纯只读开销（每次启动多读一份 3 KB 级配置文本）。
     * - 盘上配置超过 [ConfigFileStore.MAX_CONFIG_BYTES] 时**整份跳过**（不读、不写）：本步在
     *   每次启动的安装锁内都会跑一遍，而这里是全量 `readBytes()`；坏档/选错文件留下的
     *   超大配置会让每轮启动都把堆打满（512 MB 堆机型上即 OOM 崩溃循环）。与「非 UTF-8
     *   整份跳过」同一处置：不重写玩家的文件，缺失键由 core 的缺省值兜住。
     */
    private fun mergeConfigUpgrades() {
        val assetText = assets.openFile(CONFIG_FILE)?.use { stream ->
            stream.reader(Charsets.UTF_8).use { it.readText() }
        } ?: return
        val target = File(gameRoot, CONFIG_FILE)
        if (!target.isFile) return
        if (target.length() > ConfigFileStore.MAX_CONFIG_BYTES) return
        val bytes = try {
            target.readBytes()
        } catch (e: IOException) {
            throw AssetInstallException(
                "无法读取待升级配置：${target.absolutePath}（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        }
        val decoded = runCatching {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull() ?: return // 非 UTF-8 的盘上配置不重写（见 KDoc）
        val existingKeys = configKeys(decoded).keys
        val missing = configKeys(assetText).filterKeys { it !in existingKeys }
        if (missing.isEmpty()) return
        val out = ByteArrayOutputStream(bytes.size + 128)
        out.write(bytes)
        if (decoded.isNotEmpty() && !decoded.endsWith("\n")) out.write('\n'.code)
        for (line in missing.values) {
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
        }
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { it.write(out.toByteArray()) }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    throw AssetInstallException("无法落盘 ${target.absolutePath}")
                }
            }
        } catch (e: AssetInstallException) {
            tmp.delete()
            throw e
        } catch (e: IOException) {
            tmp.delete()
            throw AssetInstallException(
                "升级 ${target.name} 失败（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        }
    }

    /**
     * 与 core 解析器同口径的键提取（见 [mergeConfigUpgrades] 的口径段）：
     * 返回「键 → 该键首条原文行」的有序表（保序 ⇒ 追加顺序 = 资产行序，可复现）。
     */
    private fun configKeys(text: String): LinkedHashMap<String, String> {
        val keys = LinkedHashMap<String, String>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = if (index == 0) raw.removePrefix("\uFEFF") else raw
            if (line.isEmpty() || line[0] == ';') return@forEachIndexed
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEachIndexed
            val key = line.substring(0, colon).trim().uppercase(Locale.ROOT)
            // 不用 `keys.putIfAbsent(key, line)`：`HashMap#putIfAbsent` 是 **API 24** 的方法，
            // 本模块 minSdk 21 —— Android 5/6 上首启解包会抛 `NoSuchMethodError`
            // （2026-10-31 lint NewApi 抓到的两族真崩之一）。这里单线程逐行解析，
            // `containsKey` + 赋值与 putIfAbsent 语义逐字相同（**保留首次出现**）。
            if (key.isNotEmpty() && !keys.containsKey(key)) keys[key] = line
        }
        return keys
    }

    // ------------------------------------------------------------------
    // 落地
    // ------------------------------------------------------------------

    /** 一条 step 的拷贝结果；[sha256Hex] 只在解包器算了摘要时非空（见 [recordDataVersion]）。 */
    private class CopyOutcome(val bytes: Long, val verified: Boolean, val sha256Hex: String?)

    /**
     * 按 [Step.sources] 的顺序把字节拼进 [Step.target]。
     *
     * 先写同目录 `<name>.tmp`，全部校验通过后改名 —— 目标名一旦出现就是完整文件
     * （这也是「存在即跳过」成立的前提）。校验失败时把目标和中间文件都删掉。
     */
    private fun copyStep(step: Step, buffer: ByteArray, advance: (String) -> Unit): CopyOutcome {
        val tmp = File(step.target.parentFile, step.target.name + TMP_SUFFIX)
        if (tmp.exists() && !tmp.delete()) {
            throw AssetInstallException("无法删除解包中间文件：${tmp.absolutePath}")
        }
        // program 的 sha 是**配对核对**要的；摘要的 sha 是**文件级摘要**要的。两者都只要
        // 「字节流过时顺手算一遍」，不额外读盘 —— 但只有真要写摘要时才开这个头（首启解包
        // 不写摘要 ⇒ 一条 SHA-256 都不算，与加这一档之前逐字同成本）。
        val digest = if (step.verifyProgram || wantsDigest) MessageDigest.getInstance("SHA-256") else null
        var total = 0L
        try {
            FileOutputStream(tmp).use { out ->
                for (source in step.sources) {
                    val input = assets.openFile(source.assetPath)
                        ?: throw AssetInstallException("APK assets 里缺 ${source.assetPath}")
                    val written = input.use { copy(it, out, buffer, digest) }
                    if (source.expectedBytes != null && written != source.expectedBytes) {
                        throw AssetInstallException(
                            "${source.assetPath} 与声明不符：声明 ${source.expectedBytes} B，实读 $written B",
                        )
                    }
                    total += written
                    advance(source.assetPath)
                }
            }
            var verified = false
            var sha256Hex: String? = null
            if (digest != null) {
                val actual = hex(digest.digest())
                sha256Hex = actual
                if (step.verifyProgram) {
                    val declared = declaredProgramSha()
                    if (declared != null && !declared.equals(actual, ignoreCase = true)) {
                        // 只清刚落的中间文件，**不动 target**：旧产物（若存在）已被 install 的
                        // 事务备份，由回滚统一恢复成「上一版配对」；首次安装时 target 本来
                        // 就不存在。删旧产物只会破坏可降级状态（2026-10-22 审查修正）。
                        tmp.delete()
                        throw AssetInstallException(
                            "program.erbc 与 program.meta 不配对：侧车声明 $declared，实际 $actual",
                        )
                    }
                    verified = declared != null
                }
            }
            if (!tmp.renameTo(step.target)) {
                step.target.delete()
                if (!tmp.renameTo(step.target)) {
                    throw AssetInstallException("无法落盘 ${step.target.absolutePath}")
                }
            }
            return CopyOutcome(total, verified, sha256Hex)
        } catch (e: AssetInstallException) {
            tmp.delete()
            throw e
        } catch (e: IOException) {
            tmp.delete()
            throw AssetInstallException(
                "解包 ${step.target.name} 失败（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        }
    }

    /** 1 MiB 块流式拷贝；[digest] 非空时边拷边喂（不缓存整份字节）。 */
    private fun copy(input: InputStream, output: OutputStream, buffer: ByteArray, digest: MessageDigest?): Long {
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            output.write(buffer, 0, n)
            digest?.update(buffer, 0, n)
            total += n
        }
        return total
    }

    /**
     * 侧车声明的 `program-sha256`（小写十六进制）；`-`、缺行、文件不存在都返回 null。
     *
     * **只读前 [HEADER_BYTES] 字节**：该行固定是第 3 条记录（`BootMetaCodec.encode` 的写出序，
     * `BootMetadata.kt:271-273`；读侧同序 `:621-624`），而侧车整份 49 MB —— 为了 3 行头部
     * 调 `BootMetadata.read` 会把整份文本解析成记录表（那是装载期才该付的成本，
     * 512 MiB 堆机型上不该在解包段再付一次）。
     *
     * 头部解析与 core 的口径一致（引号串或裸 `-`），由单测拿 `BootMetadata` 自己的编码器
     * 当 oracle 钉住；core 目前没有暴露「只读头部」的入口，若将来 `w8-bootmeta` 补上，
     * 这里应改成直接调它（见交付报告的「需组长接线」）。
     */
    internal fun declaredProgramSha(): String? {
        val head = readHead(META_ASSET) ?: return null
        val line = head.lineSequence()
            .map { it.removeSuffix("\r") }
            .firstOrNull { it.startsWith(SHA_PREFIX) }
            ?: return null
        val raw = line.removePrefix(SHA_PREFIX).trim()
        if (raw.isEmpty() || raw == "-") return null
        return raw.trim('"').trim().lowercase().ifEmpty { null }
    }

    /** 读资产开头 [HEADER_BYTES] 字节（UTF-8；截断处的多字节字符不影响 ASCII 头部行）。 */
    private fun readHead(path: String): String? {
        val input = assets.openFile(path) ?: return null
        return input.use { stream ->
            val buf = ByteArray(HEADER_BYTES)
            var len = 0
            while (len < buf.size) {
                val n = stream.read(buf, len, buf.size - len)
                if (n < 0) break
                len += n
            }
            String(buf, 0, len, Charsets.UTF_8)
        }
    }

    /** 目录不存在就建；建不出来直接报可读错误（磁盘满/权限在真机上很常见）。 */
    private fun ensureDir(dir: File?) {
        if (dir == null) return
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw AssetInstallException("无法创建解包目录：${dir.absolutePath}")
        }
    }

    private fun hex(bytes: ByteArray): String = hexString(bytes)

    /**
     * 一条待落盘记录：[sources] 按顺序拼成一条 [target]（单条资产也走这条路径，只是长度为 1）。
     *
     * @param needed false = 目标已在位，本轮跳过（不建目录、不碰文件）
     * @param relPath 目标相对**游戏根**的路径（`/` 分隔），文件级摘要用它当键；
     *   不从 [target] 反推 —— 反推要碰 canonical 与分隔符，而摘要的键必须与包内条目名
     *   一字不差（差量档与删除表、与发布侧生成的那份表都要对得上）
     * @param verifyProgram 是否是 program 产物：拷完要按侧车声明的 `program-sha256` 核对
     * @param pairArtifact 是否参与 program/meta 配对事务：旧版先备份成 `.bak`，
     *   整轮成功才删备份，任一步失败回滚（见类头「配对事务」段）
     */
    private class Step(
        val target: File,
        val sources: List<Source>,
        val relPath: String,
        val needed: Boolean,
        val verifyProgram: Boolean = false,
        val pairArtifact: Boolean = false,
    ) {
        /** @param expectedBytes 已知的资产字节数（STORED 条目/清单声明）；null = 压缩条目，拿不到 */
        class Source(val assetPath: String, val expectedBytes: Long?)
    }

    companion object {
        /** 产物目录名（与 `GameHost.ARTIFACT_DIR` 同源：`erbcc compile` 的缺省输出）。 */
        const val ARTIFACT_DIR = "erbc.v1"

        /** 产物文件名（与 `GameHost.PROGRAM_FILE` 同源）。 */
        const val PROGRAM_FILE = "program.erbc"

        /** 侧车文件名（与 `GameHost.META_FILE` 同源）。 */
        const val META_FILE = "program.meta"

        /** 分片清单文件名（`app/build.gradle.kts` 的 `stageErbcAssets` 写出）。 */
        const val PARTS_FILE = "program.parts"

        /** 编译清单，随包但不参与装载（原样落盘，便于真机上核对版本）。 */
        const val MANIFEST_FILE = "manifest.txt"

        /**
         * 段 3 语句记录偏移表侧车（可选加速件；`StmtOffsetSidecar.FILE_NAME` 同值）。
         *
         * 有它 ⇒ 装载期跳过「逐条反序列化 171 万条语句只为拿偏移」的索引趟；
         * 没有 ⇒ 装载器回落扫描，语义完全一致（只是慢）。
         */
        const val STMT_IDX_FILE = "program.stmt.idx"

        /** 原版配置文件名（`ConfigData.cs:33` 的 `Program.ExeDir + "emuera.config"`）。 */
        const val CONFIG_FILE = "emuera.config"

        /**
         * 首启要递归落盘的语料目录（存档 `sav` 不在内：那是用户数据）。
         *
         * `plugins/` 必须在内：`ERB/SYSTEM.ERB:96-97` 每局开局都跑
         * `MAP_FROMXML …,LOADTEXT("plugins/tw_csv_chs.xml")`，`DT_FROMXML` 还要
         * `plugins/schema.xml` / `plugins/bbas_*.xml`。缺目录时 `LOADTEXT` 得空串，
         * 解析器在位置 0 报「XML 解析错误」，开局直接中止（2026-10-22 真机实证）。
         *
         * `bad/` 必须在内：`ERB/TITLE.ERB:123-158` 的 `@BADAPPLE` 每帧
         * `LOADTEXT("bad/bad_apple{count}.txt")`（1..6573）。缺目录时 LOADTEXT 得空串，
         * DEMO 空帧全速循环——真机上表现为「首页被清空 + 高频抖动」（2026-10-22
         * DevTools 实证：displayLines 出现 `<font face='黑体'></font></p>` 空帧行）。
         *
         * `ERB/` 只随包**ERD 数据表**（ERB 目录里的 .ERD 文件，不是 ERB 源码）：原版在
         * `<游戏根>/ERB` 下扫后缀 .ERD 的文件建关键字表（`ErhLoader.cs:342-388`），本侧
         * `UserKeywordTables.discover(gameRoot)` 同口径。缺表时用户变量的第 3 个下标
         * 不能用字符串指定，运行期直接 CodeEE（2026-11-03 真机 4.981 新游戏
         * `INITIALIZEBEFORETRAIN` 报「未找到 ERD 表 SEMEN_MATRIX.csv」）。
         *
         * 列表须与 `app/build.gradle.kts` 的 `stagedDataDirs`（+ ERD 单独一段）保持一致。
         */
        val DATA_DIRS: List<String> =
            listOf("CSV", "resources", "sound", "plugins", "bad", "ERB")

        /** 拷贝块大小：1 MiB，整轮复用一块（见类头「内存」段）。 */
        const val BUFFER_BYTES = 1 shl 20

        /** 侧车头部读取上限：第 3 行固定在前几十字节，4 KiB 足够且不建第二个缓冲。 */
        const val HEADER_BYTES = 4 * 1024

        /** 落盘中间文件后缀（与目标同目录，改名是原子的同卷操作）。 */
        const val TMP_SUFFIX = ".tmp"

        /** 配对事务的旧版备份后缀（整轮成功才删；失败回滚时 rename 回目标名）。 */
        const val BAK_SUFFIX = ".bak"

        /**
         * 跨进程互斥锁文件名，落在数据根（[gameRoot]）下。
         *
         * 0 字节常驻文件：内容无意义（锁是 OS 对 inode 的排他状态），删掉也无害 —— 下一轮
         * 会重新 `CREATE` 出来。**不放在 `erbc.v1/` 里**：那目录归产物所有，带 `.lock`
         * 会让「按产物目录内容核对版本」的口径多一条噪音。
         */
        const val LOCK_FILE = ".install.lock"

        /**
         * 等另一实例放锁的上限：5 分钟。
         *
         * 口径：首启全量解包（361.1 MiB / 3,971 条语料）在真机上要数分钟，等待方必须允许对面
         * 跑完整轮；超过 5 分钟仍没放锁，就不是「同一台设备的正常首启」，交给上层报可读失败
         * （宿主会显示「等待另一实例解包超时 … 请稍后重启应用再试」）。
         */
        const val LOCK_WAIT_TIMEOUT_MS: Long = 5L * 60L * 1000L

        /** 抢锁轮询间隔：100 ms（只影响响应速度；解包本身是分钟级，这点开销可忽略）。 */
        const val LOCK_POLL_MS: Long = 100L

        // ------------------------------------------------------------------
        // 首启解包前的空间预检（M8-SPACE）
        // ------------------------------------------------------------------
        // 三者都是**显式调用**的静态入口，宿主在调 [install] 之前问一次即可；[install] 本体
        // 不查空间、也不把它们塞进任何逐文件循环 —— 那里每多一次系统调用就是 3,971 次的成本。

        /**
         * 余量下限：64 MiB。
         *
         * 余量取「固定下限」与「5%」的较大者（见 [requiredWithMargin]）：语料里有 18 个
         * 0 字节标记文件，说明「目录项 + 簇尾」的开销与**文件数**成正比、与字节数无关，
         * 百分比在小产物上会退化成几 MiB 不够用。64 MiB 相对全量产物（361.1 MiB）是
         * 「一次首启最多多占」的可接受量级，也覆盖了 3,971 条语料的目录项开销。
         */
        const val MARGIN_FLOOR_BYTES: Long = 64L * 1024L * 1024L

        /**
         * 解包前要预留的总字节数：`requiredBytes + max(64 MiB, requiredBytes / 20)`。
         *
         * **饱和而非回绕**：`requiredBytes` 极大时加法会溢出成负数，而负数必然「小于可用
         * 空间」⇒ [hasSpaceFor] 静默放行，预检等于白做。所以溢出时饱和到 [Long.MAX_VALUE]，
         * 让 [hasSpaceFor] 随之为 false（宁可按「没空间」报可读失败，也不放行一轮注定中途
         * 失败的解包）。负数 `requiredBytes` 按 0 处理，只返回余量本身 —— 不会算出负的
         * 预留量，也不会让「误传负数」变成无条件的通过。
         *
         * @param requiredBytes 解包实际要写的字节数（各资产声明长度之和；含临时文件与旧版
         *   备份同时存在的峰值时由调用方自己加）
         */
        fun requiredWithMargin(requiredBytes: Long): Long {
            val required = requiredBytes.coerceAtLeast(0L)
            val margin = (required / 20L).coerceAtLeast(MARGIN_FLOOR_BYTES)
            val sum = required + margin
            // 两项都非负：和小于被加数只可能是 Long 回绕（溢出），饱和处理。
            return if (sum < required) Long.MAX_VALUE else sum
        }

        /**
         * [root] 所在文件系统的可用字节数（`File.usableSpace`，已扣除系统保留块）。
         *
         * [root] 可能还不存在：首启时数据根往往要等 [install] 才被创建，所以沿父链找到
         * **最近一个存在的祖先**再问 —— 不存在的路径 `usableSpace` 读成 0，会误判成
         * 「盘满」。父链走到头（连根都不存在，理论上不会发生）也按探测失败处理。
         * 已存在的路径同样可能读回 0（老 ROM、外部根走 FUSE 时 `statvfs` 失败就报 0）：
         * **0 视同探测失败**，与下面的 fail-open 段同一条处理，不据此拒绝安装。
         *
         * 用 JVM 自带的 `File.usableSpace` 而不是 `android.os.StatFs`：两者在 Android 上
         * 最终都走 statvfs，但前者不把本文件拖进 platform 的 Android-API 白名单闸
         * （`AndroidApiSurfaceGuardTest`），算术面因此保持纯 JVM、可被普通单测审查。
         *
         * **探测失败按「有空间」处理（返回 [Long.MAX_VALUE]）**，与 `SaveFileIo.isReadOnly`
         * 的「查不到 ≠ 拒绝」同一条纪律：预检只是把「玩家白等一轮才失败」提前成一句可读的
         * 话，真正的落盘失败仍由 [install] 的带路径 IO 异常兜底（屏上同样看得到）。若把
         * 一次探测异常升级成「空间不足、拒绝安装」，那所有探测不到的机型（老 ROM、
         * 外部根走 FUSE、单测环境里的 android.jar 桩）会**永远装不上**，代价远大于漏报。
         *
         * 用 `usableSpace` 而不是 `totalSpace - freeSpace`：后者含系统保留块，报大了
         * 会放过一轮写到一半才 ENOSPC 的解包。
         */
        fun freeBytes(root: File): Long {
            return try {
                var probe: File? = root.absoluteFile
                while (probe != null && !probe.exists()) probe = probe.parentFile
                val free = probe?.usableSpace ?: Long.MAX_VALUE
                // 0 视同探测失败（见 KDoc 的 fail-open 段）：老 ROM / 外部根走 FUSE 时
                // `statvfs` 失败会报 0，按「没空间」判会把这类机型永远挡在安装门外；
                // 真盘满时落盘侧仍会给出带路径的 IO 错误，代价只是晚一步报。
                if (free <= 0L) Long.MAX_VALUE else free
            } catch (_: Throwable) {
                // 见 KDoc：探测失败不阻塞安装。
                Long.MAX_VALUE
            }
        }

        /**
         * [root] 的剩余空间是否够装下 [requiredBytes]（含余量，见 [requiredWithMargin]）。
         *
         * 探测失败（[freeBytes] 返回 [Long.MAX_VALUE]）时为 true —— 理由见 [freeBytes] 的
         * 契约段：预检只负责提前给话，不负责替落盘做否决。
         */
        fun hasSpaceFor(root: File, requiredBytes: Long): Boolean =
            freeBytes(root) >= requiredWithMargin(requiredBytes)

        /**
         * 打开锁文件本身的重试预算（毫秒）。
         *
         * 为什么 open 还要重试：Windows 上「两路并发**首次**创建锁文件」有一拍已知的竞态 ——
         * 一边正在 `CreateFile` 建文件，另一边同时 open 会拿到 `ERROR_ACCESS_DENIED`
         * （`FileNotFoundException: 拒绝访问`），而不是等到共享打开成功；NTFS 的 create
         * 窗口不是原子的。Android（Linux 语义，`O_CREAT` 原子）没有这一拍，但桌面机与
         * CI 全是 Windows，DEV-02 的并发用例就是在这个拍点上间歇红。给一个短暂的
         * 重试预算（远小于 [LOCK_WAIT_TIMEOUT_MS]），耗尽仍开不出才报「无法打开解包互斥锁」。
         */
        const val LOCK_OPEN_RETRY_MS: Long = 2_000L

        /** 锁文件 open 重试的间隔：50 ms（这一拍是毫秒级窗口，50 ms 足够跨过去）。 */
        const val LOCK_OPEN_RETRY_POLL_MS: Long = 50L

        private const val NANOS_PER_MILLI: Long = 1_000_000L

        /** 侧车头部那行的前缀（`BootMetaCodec.encode` 的写出形态）。 */
        private const val SHA_PREFIX = "program-sha256 "

        private const val PROGRAM_ASSET = "$ARTIFACT_DIR/$PROGRAM_FILE"
        private const val META_ASSET = "$ARTIFACT_DIR/$META_FILE"
        private const val PARTS_ASSET = "$ARTIFACT_DIR/$PARTS_FILE"
        private const val MANIFEST_ASSET = "$ARTIFACT_DIR/$MANIFEST_FILE"
        private const val STMT_IDX_ASSET = "$ARTIFACT_DIR/$STMT_IDX_FILE"

        // ------------------------------------------------------------------
        // 差量删除表的安全判据（执行面在 applyDeltaDeletions）
        // ------------------------------------------------------------------

        /**
         * 删除表里**路径的任何一段**撞上这四个名字就不许删。
         *
         * 名单与发布侧 `devtools/emit_update.py` 的 `NEVER_BUNDLED` 逐字同值
         * （`emuera.config`、`sav`、`.install.lock`、`update`）：那四样是「随包数据里没有、
         * 也就不该由数据包负责」的东西 —— 玩家能在游戏里改写的配置、存档、跨进程锁、
         * 更新通道自己的状态。改一边必须改另一边，否则发布侧过滤得掉的条目会在设备侧被放行。
         *
         * 判**任意一段**而不只判首段：`sav` 与 `update` 在游戏根下各只有一个，随包六类目录里
         * 这四个名字一条不存（2026-11-01 在游戏根跑的
         * `find CSV resources sound plugins bad \( -name sav -o -name update -o -name emuera.config -o -name .install.lock \)`
         * = 空输出；2026-11-03 补上 ERB 复测仍空输出），所以按段判既不挡任何合法删除，又能挡住「表里写 `CSV/sav/x.txt` 这类
         * 名字撞车的条目」。宁可用一条更严的规则，也不要在删除面上赌「这个二级目录大概不是存档」。
         */
        internal fun deletionIsProtected(relPath: String): Boolean =
            relPath.split('/').any { it in NEVER_DELETE_SEGMENTS }

        /**
         * 删除目标必须落在**解包器自己会落盘的范围**之内：[ARTIFACT_DIR] 或 [DATA_DIRS] 之一打头。
         *
         * 判据的来源是「谁写的才轮得到谁删」：游戏根下还有 `dat/`、`font/`、`lang/`、`profile/`、
         * `setting.json` 这些东西（游戏根实测清单），它们由玩家/原版工具/别的通道放那儿，
         * 解包器一个字节都没写过 —— 一张写歪的差量表把它们删掉，就是「更新一次数据、
         * 玩家的中文字体没了」这种事故。范围外的一律计受阻、不执行、不抛。
         */
        internal fun deletionWithinAssetUniverse(relPath: String): Boolean {
            val head = relPath.substringBefore('/')
            return head == ARTIFACT_DIR || DATA_DIRS.contains(head)
        }

        /**
         * 这条相对路径能不能进文件级摘要。
         *
         * 排除 `emuera.config`：玩家会在游戏里改它（`ConfigData.SaveToFile`），改了就一定与
         * 摘要不符 —— 让它进表等于让体检永远报假问题。排除带制表符/控制字符/反斜杠的名字：
         * 摘要一行一条、用 TAB 分列，这种名字会把行拆歪（真机上 `AssetManager.list` 也可能
         * 给出这类条目名。语料读数：2026-11-01 逐目录 walk 五棵随包树，带控制字符或制表符的
         * 文件名 **0 条**、首尾是空格或点的名字 **0 条**，所以这条排除今天不挡任何真文件，
         * 只是把「一行三列」这个格式前提守住）。
         */
        internal fun recordedInDigest(relPath: String): Boolean =
            relPath != CONFIG_FILE &&
                relPath.isNotEmpty() &&
                !relPath.contains('\\') &&
                relPath.none { it.code < 0x20 }

        // ------------------------------------------------------------------
        // 文件级摘要 update/data-digest.txt：格式常量 + 编解码 + 体检
        // ------------------------------------------------------------------

        /**
         * 更新通道自己的状态目录（游戏根下，与 `UpdateManager` 的 `data-version.txt` 同目录）。
         *
         * 它随包解包从不触碰（不在 [DATA_DIRS] 里）、发布侧永不打包（`NEVER_BUNDLED`）、
         * 差量删除表也不许删（[NEVER_DELETE_SEGMENTS]）—— 三处同口径，少一处就有一条通道
         * 能把玩家的更新状态抹掉。
         */
        const val UPDATE_DIR = "update"

        /**
         * 删除面的保护段名表：与发布侧 `devtools/emit_update.py` 的 `NEVER_BUNDLED` 一字不差。
         * 声明必须排在 [UPDATE_DIR] / [CONFIG_FILE] / [LOCK_FILE] 之后 —— companion 的属性
         * 按声明顺序初始化，早一步取到的是尚未赋值的 null。
         */
        private val NEVER_DELETE_SEGMENTS: Set<String> = setOf(CONFIG_FILE, "sav", LOCK_FILE, UPDATE_DIR)

        /** 文件级摘要的文件名（在 [UPDATE_DIR] 下）。 */
        const val DATA_DIGEST_FILE = "data-digest.txt"

        /** 摘要文件第 1 行的魔数（格式号另起一列，见 [encodeDataDigest]）。 */
        const val DIGEST_MAGIC = "eratw-data-digest"

        /** 当前摘要格式号：1。读到别的数字按「没有摘要」处理（宁回落全量，不猜格式）。 */
        const val DIGEST_FORMAT = 1

        /** 摘要文件的读取上限：8 MiB（语料 3,971 条实际约 250 KB；超限=坏文件，按没有摘要处理）。 */
        const val DIGEST_MAX_BYTES = 8 * 1024 * 1024L

        /** [checkDataDigest] 最多报几条问题（剩下的从略，只报总数）。 */
        const val DIGEST_MAX_PROBLEMS = 20

        /** 摘要文件的落点（公开给调用方：写日志与体检都要指同一个文件，别各拼各的）。 */
        fun dataDigestFile(gameRoot: File): File = File(File(gameRoot, UPDATE_DIR), DATA_DIGEST_FILE)

        /**
         * 读盘上摘要；**任何一点不合 [encodeDataDigest] 的形状都返回 null**（= 按「没记过」处理）。
         *
         * 为什么这么不宽容：这份文件唯一的用处是「差量档的资格凭据」。一份读一半信一半的
         * 摘要会让体检报「盘上就是第 N 版」，而差量恰恰是在这句话之上才敢只下几 MB。
         * 判 null 的代价是「这次改走全量（≈680 MiB）」，永远比「缝出一份自洽却不对的语料」便宜。
         */
        fun readDataDigest(gameRoot: File): DataDigest? = try {
            val file = dataDigestFile(gameRoot)
            if (!file.isFile || file.length() > DIGEST_MAX_BYTES) {
                null
            } else {
                parseDataDigest(file.readText(StandardCharsets.UTF_8))
            }
        } catch (_: Throwable) {
            // 读不出/超上限/权限问题：一律按「没记过」处理，见 readDataDigest 的 KDoc。
            null
        }

        /**
         * 摘要的**唯一合法形状**（发布侧 `devtools/emit_update.py` 要产出同样形状的东西时，
         * 逐行照这里，一字不差）。
         *
         * 编码 UTF-8、**无 BOM**；行尾**只用 LF**（`\n`）；文件以换行结尾。
         *
         * ```text
         * eratw-data-digest 1
         * version 7
         * entries 2
         * CSV\配置.txt<TAB>1234<TAB>e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
         * erbc.v1/program.erbc<TAB>328799098<TAB>（64 位十六进制，小写）
         * ```
         *
         * 逐行：
         * 1. `eratw-data-digest 1` —— [DIGEST_MAGIC] + 空格 + [DIGEST_FORMAT]，**必须第 1 行第 1 列**；
         * 2. `version ` + 十进制版本号 = 那一份数据包的 `assets-version`（与
         *    `update/data-version.txt` 里的数同源同值；这里是 Long，不写加号、不写前导零）；
         * 3. `entries ` + 十进制条数 = 下面数据行的行数（发布侧 `--verify-only` 就核这一条）；
         * 4. 第 4 行起每条文件一行，三列以**一个制表符**分隔：
         *    `相对路径` `字节数` `sha256 十六进制（64 位，小写）`
         *    - 相对路径：相对**游戏根**，分隔符恒为正斜杠，不带前导斜杠、不带 `..` 段、
         *      不带 `.` 段；大小写与盘上原样一致（本工程不改大小写，见类头「幂等」段）；
         *    - 字节数：十进制、非负；
         *    - 摘要：该文件**内容**的 SHA-256，小写十六进制，长度恒 64；
         * 5. **数据行按相对路径排序**：Kotlin 的 `String.compareTo`（UTF-16 码元序）。
         *    发布侧用 Python 的 `sorted()`（码点序）得到同一个序 —— 代理对不改变相对次序，
         *    两种序在这份数据上逐字相同。**别用任何带 locale 的折叠排序。**
         *
         * 内容范围：只有解包器写过的东西（[ARTIFACT_DIR] 下的产物 + [DATA_DIRS] 六类目录）。
         * `emuera.config`、`sav`、`update` 里的任何东西都**不在表里**（见 [recordedInDigest]），
         * 所以发布侧生成时也要按同一份白名单过滤，否则两份表永远对不上。
         *
         * 排序在这里做一次就够（写入侧排序 ⇒ 磁盘上的文件本身有序，读侧不必再排）。
         */
        internal fun encodeDataDigest(version: Long, entries: Map<String, DataDigestEntry>): String {
            val out = StringBuilder(entries.size * 80 + 64)
            out.append(DIGEST_MAGIC).append(' ').append(DIGEST_FORMAT).append('\n')
            out.append("version ").append(version).append('\n')
            out.append("entries ").append(entries.size).append('\n')
            for (rel in entries.keys.sorted()) {
                val entry = entries[rel] ?: continue
                out.append(rel).append('\t').append(entry.bytes).append('\t').append(entry.sha256Hex).append('\n')
            }
            return out.toString()
        }

        /**
         * [encodeDataDigest] 的逆；不合形状返回 null（口径见 [readDataDigest] 的「为什么不宽容」）。
         *
         * 多余的非空行、条数与行数不符、重复路径、摘要不是 64 位小写十六进制、路径越出
         * 资产范围或撞保护名单 —— 任一即整份作废。CRLF 读得进（只剥行尾 `\r`），因为发布侧
         * 在 Windows 上手搓一份表是很常见的事故，形状判据不该把它变成「读不出」。
         */
        internal fun parseDataDigest(text: String): DataDigest? {
            val lines = text.split('\n')
            if (lines.size < 4) return null
            if (lines[0].removeSuffix("\r") != "$DIGEST_MAGIC $DIGEST_FORMAT") return null
            val versionLine = lines[1].removeSuffix("\r")
            if (!versionLine.startsWith("version ")) return null
            val version = versionLine.substring("version ".length).toLongOrNull() ?: return null
            val countLine = lines[2].removeSuffix("\r")
            if (!countLine.startsWith("entries ")) return null
            val count = countLine.substring("entries ".length).toIntOrNull() ?: return null
            if (count < 0 || lines.size - 3 < count) return null
            val entries = LinkedHashMap<String, DataDigestEntry>(count)
            for (index in 3 until 3 + count) {
                val line = lines[index].removeSuffix("\r")
                val columns = line.split('\t')
                if (columns.size != 3) return null
                val rel = columns[0]
                val bytes = columns[1].toLongOrNull() ?: return null
                val sha = columns[2]
                if (bytes < 0L || !isDigestSha(sha)) return null
                if (!isRecordablePath(rel)) return null
                if (entries.containsKey(rel)) return null
                entries[rel] = DataDigestEntry(bytes, sha)
            }
            // 数据段之后不许再有非空行（半行残留 = 写坏了，整份作废）。
            for (index in 3 + count until lines.size) {
                if (lines[index].removeSuffix("\r").isNotEmpty()) return null
            }
            return DataDigest(version, entries)
        }

        /** 形状合格的摘要列：64 位小写十六进制（大写也读得进 ⇒ 归一到小写再判）。 */
        private fun isDigestSha(text: String): Boolean =
            text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        /**
         * 摘要里一条路径的硬形状判据：**已归一的相对路径**，且落在资产范围之内、不撞保护名单。
         *
         * 与 `ZipAssetTree.safeEntryName` 的区别：那条要把外部条目名**归一**（折反斜杠、剥
         * 开头斜杠）再判；这里判的是**我们自己写下去的**那行，凡是带归一痕迹的形态
         * （反斜杠、前导斜杠、`.` 段）一律按「这份表不是 [encodeDataDigest] 写的」作废。
         */
        private fun isRecordablePath(relPath: String): Boolean {
            if (!recordedInDigest(relPath) || relPath.startsWith("/") || relPath.endsWith("/")) return false
            val segments = relPath.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
            if (deletionIsProtected(relPath)) return false
            return deletionWithinAssetUniverse(relPath)
        }

        /**
         * 盘上摘要与真实树对不对得上：**null = 盘上没有摘要**（调用方按「不能走差量」处理）；
         * 非 null = 问题清单，空表 = 摘要说的那些文件都在、长度也都对。
         *
         * ## 这条是给谁用的
         *
         * 给**选包**那一拍用的（`UpdatePolicy.dataArtifactFor` 之前）：版本号只说「盘上是第 N 版」，
         * 这条能说「盘上真的有第 N 版的那 3,971 个文件」。有摘要且体检过 ⇒ 差量敢只下几 MB；
         * 没摘要或有问题 ⇒ 回落全量。判定口径不在本层做（core 才是判定层，见契约 §1.2），
         * 本层只把读数交出去。
         *
         * ## 深度
         *
         * - [verifyContent] = false（缺省）：逐条一次 `File.length()` 的 stat，3,971 条约几十毫秒，
         *   每次检查更新都跑得起。抓得住「半截被杀」「文件被删」，抓不住「同长但内容不同」；
         * - = true：每条整份读一遍算 SHA-256，成本≈整棵 680 MiB 的读盘。**只在玩家已经点了
         *   下载、动盘之前那一道闸**用，别放进定时检查里 —— 那样每次检查更新都要读一遍全量数据。
         *
         * 只查**摘要里有的**条目：盘上多出来的东西（上一版没删干净的残留）不算问题，
         * 那正是「包里没有的条目不删盘上残留」这条既有口径承认的状态。
         *
         * ## C-3：盘可信闸补全
         *
         * - [expectedVersion] 非空时还要与 `update/data-version.txt` 的读数比一次：两份文件
         *   同源同值才敢说「盘上真的有第 N 版的那批文件」。版本对不上（随包解包前推了标记、
         *   摘要还是旧版，或反过来）⇒ 报问题、按不可信处理，别让差量建立在两次不同版本的
         *   读数上；
         * - **entries 为 0 的表按问题报**：形状上它是合法的（发布侧也为 0 条留了这个形状），
         *   但「查了 0 条就报一致」是假可信 —— 差量资格不能建立在一张什么都没说的表上。
         */
        fun checkDataDigest(
            gameRoot: File,
            verifyContent: Boolean = false,
            expectedVersion: Long? = null,
        ): List<String>? {
            val digest = readDataDigest(gameRoot) ?: return null
            val problems = ArrayList<String>()
            if (expectedVersion != null && digest.version != expectedVersion) {
                problems += "版本不符：摘要 version ${digest.version}，data-version.txt 是 $expectedVersion"
            }
            if (digest.entries.isEmpty()) {
                problems += "摘要里一条文件都没有（entries 0）—— 按不可信处理"
            }
            for ((rel, entry) in digest.entries) {
                val target = File(gameRoot, rel)
                if (!target.isFile) {
                    problems += "$rel：摘要说在，盘上没有"
                } else if (target.length() != entry.bytes) {
                    problems += "$rel：长度不符，摘要 ${entry.bytes} B，盘上 ${target.length()} B"
                } else if (verifyContent) {
                    val actual = sha256OfFile(target)
                    if (actual == null) {
                        problems += "$rel：读不出来"
                    } else if (!actual.equals(entry.sha256Hex, ignoreCase = true)) {
                        problems += "$rel：内容不符（同长不同内容）"
                    }
                }
                if (problems.size >= DIGEST_MAX_PROBLEMS) {
                    problems += "……其余从略（共查 ${digest.entries.size} 条）"
                    break
                }
            }
            return problems
        }

        /** 整份读一遍算 SHA-256（1 MiB 一块，不建整份 ByteArray）；读不出返回 null。 */
        private fun sha256OfFile(file: File): String? = try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val chunk = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    digest.update(chunk, 0, n)
                }
            }
            hexString(digest.digest())
        } catch (_: Throwable) {
            // 读不出（被别的进程占着 / 坏块）：交给调用方按「有问题」处理，不在这里替盘上表态。
            null
        }

        private fun hexString(bytes: ByteArray): String =
            bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }
}
