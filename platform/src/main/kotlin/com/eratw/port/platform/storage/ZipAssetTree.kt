package com.eratw.port.platform.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 把**下载下来的资产包**（zip）当作 [GameAssetTree] 喂给 [GameAssetInstaller]。
 *
 * ## 为什么要这一层
 *
 * 首启解包吃的是 APK 内置 assets（[AndroidAssetTree]），而「数据更新」吃的是镜像仓上的一个
 * zip。两边的读取面是同样的三件事（开流 / 列目录 / 拿长度），所以只要再实现一次
 * [GameAssetTree]，[GameAssetInstaller] 那套已经验证过的机制就能原样复用：
 * program 与侧车的**配对事务**、`program-sha256` 核对、同目录临时文件 + 改名、
 * 跨进程互斥锁、`emuera.config` 只拷一次 —— 一条都不必重写，也就不会写歪。
 *
 * ## 条目长度比 assets 更好拿
 *
 * [ZipEntry.getSize] 读的是中央目录里的**解压后字节数**，STORED 与 DEFLATE 都有值；
 * 而 [AndroidAssetTree] 对压缩条目只能给 null（于是退化成「存在即跳过」）。所以走 zip 这条通道时
 * [GameAssetInstaller.needsCopy] 的字节数判据恒成立，不必担心压缩条目带来的漏换。
 * 拿不到（罕见：流式写入、中央目录没有长度）才返回 null。
 *
 * ## zip-slip 防线（这条通道读的是**外部来的**归档）
 *
 * assets 的条目名由 aapt 生成，不会有 `../`；镜像仓上的 zip 却可能被人改过 —— 而解包侧会拿
 * 条目名直接 `File(gameRoot, 名字)`。所以索引阶段就把不安全的条目名**整个丢掉**：
 * 带 `..` 段、带 `.` 段（目录条目与 `././foo` 这类多级前缀都收敛在归一里，见 [safeEntryName]）、
 * 以斜杠开头、含空段（双斜杠）、反斜杠折分后仍畸形、或含控制字符的条目都不进索引。
 * 丢掉的条数记在 [rejectedEntryCount] 里，**调用方必须在大于 0 时拒绝应用**：合法发布包永远
 * 不会有这种条目，出现了就说明包不可信，而不是「跳过几个文件继续装」。
 *
 * 目录条目（名字以斜杠结尾）一律不进索引：空目录在 [GameAssetInstaller] 侧本来就是跳过，
 * 而文件条目自己会撑起父目录，不需要目录条目代劳。
 *
 * 只读、不写：本类不建任何文件，落盘全部由 [GameAssetInstaller] 负责。
 *
 * ## 差量包的删除表（`update-delta.txt`）
 *
 * 数据包有两种：全量（整棵资产树）与差量（只带变更/新增 + 一张要删的表，
 * 契约 CONTRACT-UPDATE §1.3）。差量表固定在 **zip 根**、名字是 [DELTA_LIST_NAME]，
 * 一行一个游戏根相对路径。本类把它**从文件索引里摘出来**单独解析，所以：
 *
 * - 这条控制条目永远不会被 [GameAssetInstaller] 当成资产落进游戏根（它不是游戏文件）；
 * - 它也出现在不了 [listDir] —— 少一条索引就少一条「根目录里多出来的怪文件」；
 * - 表里的每一行照用 [safeEntryName] 判一次：`..`、空段、控制字符这些形态计入
 *   [rejectedEntryCount]，**与坏条目名同一条拒装通道**（删除面的 zip-slip 比写入面更危险，
 *   而调用方早就有「`rejectedEntryCount` 大于 0 就整包拒装」的闸，不新增第二条判定口径）；
 * - 删除表本身**不在这里执行**，也不在这里过滤保护名单 —— 那是 [GameAssetInstaller] 的活
 *   （它才知道游戏根在哪、包里有哪些条目）。
 *
 * 表读不出来（截断的 zip）或超过 [DELTA_LIST_MAX_BYTES]：同样只计入 [rejectedEntryCount]
 * 并留空表，**不在构造器里抛** —— 拒装由调用方按那条既有闸决定。
 *
 * 全量包没有这条目 ⇒ [isDeltaPackage] 为 false、[deltaDeletions] 为空，行为与本类加入
 * 差量面之前逐字相同。
 *
 * ## 条目名一律按 UTF-8 解
 *
 * Android 的 [ZipFile] 只有单参构造器（没有 JDK 的字符集重载），条目名恒按 UTF-8 解 ——
 * 所以发布侧（`devtools/emit_update.py`）必须用 UTF-8 写条目名。语料的 `resources` 与 `sound`
 * 下都有中文文件名，这条不对就会解成乱码名，屏上表现为「立绘缺失」而不是报错，最难查。
 *
 * @param zipFile 已下载并**通过清单摘要核对**的包（核对在更新通道做，本类不重复算哈希）
 */
class ZipAssetTree(zipFile: File) : GameAssetTree, DeltaDeletionSource, AutoCloseable {

    private val zip: ZipFile = ZipFile(zipFile)

    /** 条目全名 → 条目。重名条目按先到先得只留第一条：写侧（`emit_update.py`）撞重名会被
     *  `ZipOutputStream` 直接拒，所以这里只是读外部归档时的防御，不指望它当语义。 */
    private val entries: Map<String, ZipEntry>

    /** 目录前缀 → 直接子项名（与 [GameAssetTree.listDir] 同口径：目录名不带尾斜杠）。 */
    private val children: Map<String, List<String>>

    /** 因路径不安全而被丢出的条目数（见类头 zip-slip 防线段；差量删除表里的非法行同样计入）。 */
    val rejectedEntryCount: Int

    /** zip 根里有没有那张删除表（空表也算差量档：发布侧「这一版没有要删的」是合法形态）。 */
    private var deltaPackage: Boolean = false

    override val isDeltaPackage: Boolean get() = deltaPackage

    /** 删除表内容（归一后的相对路径，去重、保表内行序）。全量包为空表。 */
    private var deletePaths: List<String> = emptyList()

    override fun deltaDeletions(): List<String> = deletePaths

    init {
        val entryIndex = LinkedHashMap<String, ZipEntry>()
        val childIndex = LinkedHashMap<String, LinkedHashSet<String>>()
        var rejected = 0
        var deltaEntry: ZipEntry? = null
        val iterator = zip.entries()
        while (iterator.hasMoreElements()) {
            val entry = iterator.nextElement()
            val name = safeEntryName(entry.name)
            if (name == null || entry.isDirectory) {
                if (!entry.isDirectory) rejected++
                continue
            }
            // 控制条目：不进索引、不撑父链（于是既落不了盘也列不出来），单独解析。先到先得。
            if (name == DELTA_LIST_NAME) {
                if (deltaEntry == null) deltaEntry = entry
                continue
            }
            if (entryIndex.containsKey(name)) continue
            entryIndex[name] = entry
            // 把这条文件的**整条父链**撑起来：`listDir` 要能列出中间目录，而不只是列出文件。
            // 只登记直接父级的话，`listDir("")` 就只剩根上的散文件，四个语料目录全都看不见 ——
            // 本类的 KDoc 说「与 AndroidAssetTree 同口径」，口径就得连这一条一起成立。
            var prefix = name
            while (true) {
                val slash = prefix.lastIndexOf('/')
                val child = if (slash < 0) prefix else prefix.substring(slash + 1)
                val parent = if (slash < 0) "" else prefix.substring(0, slash)
                childIndex.getOrPut(parent) { LinkedHashSet() } += child
                if (slash < 0) break
                prefix = parent
            }
        }
        if (deltaEntry != null) {
            deltaPackage = true
            val text = readDeltaList(deltaEntry)
            if (text == null) {
                // 条目在中央目录里却读不出/超长：按「不可信包」处理（拒装由调用方看计数决定），
                // 而不是当成「表是空的」继续装 —— 后者会把本该删掉的旧文件留在盘上，
                // 屏上表现是「更新完还在读上一版的脚本」，最难查。
                rejected++
            } else {
                val parsed = parseDeltaList(text)
                deletePaths = parsed.paths
                rejected += parsed.rejectedLineCount
            }
        }
        rejectedEntryCount = rejected
        entries = entryIndex
        children = childIndex.mapValues { it.value.toList() }
    }

    override fun openFile(path: String): InputStream? {
        val entry = entries[path] ?: return null
        return try {
            zip.getInputStream(entry)
        } catch (e: IOException) {
            // 条目在索引里却读不出（截断的 zip / 坏 CRC）：包成异常上抛，不吞成 null ——
            // null 会被 plan() 当成「包里没有这条资产」而静默跳过。
            throw IOException("资产包条目读取失败：$path（${e.message}）", e)
        }
    }

    override fun listDir(path: String): List<String> = children[path] ?: emptyList()

    /** 中央目录里的解压后字节数；条目不存在或长度未知（-1）返回 null，0 是有效长度。 */
    override fun fileSize(path: String): Long? {
        val entry = entries[path] ?: return null
        val size = entry.size
        return if (size < 0L) null else size
    }

    override fun close() {
        zip.close()
    }

    /**
     * 读删除表原文；读不出或超过 [DELTA_LIST_MAX_BYTES] 返回 null（调用方按「不可信包」处理）。
     *
     * 上限的存在不是为了省内存（8 MiB 的表按每条路径三四十字节算是十几万条，而语料总共才
     * 3,971 条文件），而是为了挡住「有人往这一条里塞了几百 MiB」这种包：索引阶段无上限读，
     * 等于把整轮的内存预算交给一个不可信的中央目录。
     */
    private fun readDeltaList(entry: ZipEntry): String? {
        val out = ByteArrayOutputStream(16 * 1024)
        return try {
            zip.getInputStream(entry).use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (out.size() + n > DELTA_LIST_MAX_BYTES) return null
                    out.write(chunk, 0, n)
                }
                out.toString("UTF-8")
            }
        } catch (e: IOException) {
            null
        }
    }

    /** 差量删除表的一次解析结果：归一后的相对路径（去重、保行序）+ 被丢出的非法行数。 */
    internal class DeltaList(val paths: List<String>, val rejectedLineCount: Int)

    companion object {
        /**
         * 差量包删除表的**固定条目名**（相对 zip 根，一字不差照 `CONTRACT-UPDATE` §1.2/§1.3
         * 与发布侧 `devtools/emit_update.py` 对齐）。发布侧写这个名字，读侧只认这个名字。
         *
         * 它是归档的控制条目、不是游戏文件：既不进索引也不落盘（见类头）。
         */
        const val DELTA_LIST_NAME = "update-delta.txt"

        /** 删除表原文的读取上限：8 MiB（语料总共 3,971 条文件，正常表只有几十 KB）。 */
        const val DELTA_LIST_MAX_BYTES = 8 * 1024 * 1024

        /**
         * 解析差量删除表：一行一个游戏根相对路径。
         *
         * 口径（发布侧 U-D 必须按同一条生成，否则差量表会被静默读短）：
         * - 行尾 LF 与 CRLF 都收（**只剥行尾那个 `\r`**）；整行为空白（含只有空格）跳过；
         * - **不做 trim**：路径里的空格属于文件名本身，两头一 trim 就会把「删得掉的文件」
         *   写成一条不存在的路径，而删除面对「盘上没有」是静默的 —— 那种错只能靠不 trim 挡住；
         * - 每一行走 [safeEntryName] 归一 —— 于是反斜杠、开头的 `./` 与 `/` 都能接受，
         *   而带 `..` 段、空段或控制字符的行**计入拒绝行数**（调用方据此整包拒装）；
         * - 重复行只留第一条，不算非法（表是集合语义，不是队列语义）；
         * - 行首 `#` **不是注释**：语料里 `#` 开头的文件名是真实存在的（游戏根的
         *   `#双击LazyloadingV3.0开启游戏`、ERB 树里的 `CCOMF/#コマンドリスト.txt`；
         *   实测 `CSV`/`resources`/`sound`/`plugins`/`bad` 五个随包目录**今天**没有这种名字，
         *   但 mod 落进 `resources` 就有了）。把 `#` 当注释会让「删掉 `#` 系文件」永远做不到，
         *   而漏删的表现是盘上留着旧文件、屏上看不出任何异常；
         * - 指向表自己（[DELTA_LIST_NAME]）的行算非法：那条目不是盘上的游戏文件。
         *
         * 保护名单（`sav`、`update`、`emuera.config`、`.install.lock`）**不在这里过滤** ——
         * 那是 [GameAssetInstaller] 的执行面（它才知道游戏根的边界在哪）。
         */
        internal fun parseDeltaList(text: String): DeltaList {
            val paths = LinkedHashSet<String>()
            var rejected = 0
            text.lineSequence().forEachIndexed { index, raw ->
                val line = if (index == 0) raw.removePrefix("\uFEFF") else raw
                if (line.isBlank()) return@forEachIndexed
                val name = safeEntryName(line.removeSuffix("\r"))
                if (name == null || name == DELTA_LIST_NAME) {
                    rejected++
                    return@forEachIndexed
                }
                paths.add(name)
            }
            return DeltaList(paths.toList(), rejected)
        }

        /**
         * 条目名归一 + 安全判定；不合法返回 null（调用方计入 [rejectedEntryCount]）。
         *
         * 反斜杠先折成斜杠（Windows 压缩工具的常见产物），再**循环**剥掉开头的 `/` 与 `./`
         * （一层剥不净就剥两层：`././CSV/a.txt` 只剥一层会留下 `./CSV/a.txt`，见下面「为什么循环」），
         * 尾斜杠（目录条目）也剥掉；剩下的每一段都必须非空且既不是 `.` 也不是 `..`，
         * 全名不许含控制字符。空名与 `.` 也算不合法：它们没有落盘意义，留着只会让 [listDir]
         * 多出一个假子项。
         *
         * ## 为什么前缀要循环剥（W5-misc 第 2 条之三）
         *
         * 这个返回值同时是**索引键**、`listDir` 的父链片段、以及落进数据摘要里的那条路径。
         * 只剥一层时 `././foo` 会留下 `./foo`：
         * - 索引键成了 `./foo`，而 [GameAssetInstaller] 按 `foo` 查 ⇒ 「包里没有这条资产」，
         *   静默少一个文件，屏上表现为「更新完还在读上一版」；
         * - 父链会多出一个名为 `.` 的目录（`a/./b` 撑起 `a` 的子项 `.`），[listDir] 列出假子项、
         *   解包器对它再走一遍名字校验；
         * - 摘要读侧（`GameAssetInstaller.isRecordablePath`）对**任何**带 `.` 段或前导 `/` 的路径
         *   直接判「这份表不是发布侧写的」⇒ 整份摘要作废，差量通道永久回落全量。
         * 两侧口径必须对称：**能写进摘要的形态**与**这里愿意认的形态**是同一套，
         * 于是不管包里的名字带几层前缀，出口要么是规范相对路径、要么是「拒装」，不会有中间态。
         */
        internal fun safeEntryName(raw: String): String? {
            var name = raw.replace('\\', '/')
            // 循环剥前缀：开头的 `/` 与 `./` 有几层剥几层，直到形态收敛。
            // 越界路径不在这里洗白：本循环只认 `/` 与 `./` 两种开头，`..` 段一律交给下面的
            // 段检查判死 —— `../x` 原样进来原样被拒；`./../x` 剥掉开头那层 `./` 后剩 `../x`
            // 同样被拒。以点开头的真实文件名（`.hidden`）不受影响：它不以 `./` 开头。
            while (true) {
                val next = when {
                    name.startsWith("/") -> name.substring(1)
                    name.startsWith("./") -> name.substring(2)
                    else -> break
                }
                name = next
            }
            if (name.endsWith("/")) name = name.dropLast(1)
            if (name.isEmpty() || name == ".") return null
            if (name.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
            if (name.any { it.code < 0x20 }) return null
            return name
        }
    }
}
