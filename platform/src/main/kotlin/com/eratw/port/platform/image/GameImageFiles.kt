package com.eratw.port.platform.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.eratw.port.core.host.FileOps
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 按**资源名**兜一层文件解码（M10-GFX）。
 *
 * ## 为什么要有这一层
 *
 * 脚本侧的图有两条来路：
 * 1. `SPRITECREATE 名字, GID, ...` 建的 sprite（走 `store.aux` 的 `sprite` 槽，
 *    像素来自 `GCREATEFROMFILE` 灌进 `GraphicsBuffer` 的那份）；
 * 2. 从来没被 `SPRITECREATE` 过、但 `<img src='...'>` / `PRINT_IMG` 直接按名字引用的图。
 *    原版对 2 的处理是把 `<img ...>` 标签当文本画（spec: `ConsoleImagePart.cs:60-64`）。
 *
 * 本作语料的标题图恰好两条都要用到：`ERB/TITLE.ERB:59` 的 `<img src='TW_title001'>`
 * 由 `ERB/リソース管理/ResourceManager.ERB:7-9` 先 `GCREATEFROMFILE RM_TITLE, "title.webp"`
 * 再 `SPRITECREATE "TW_title001", RM_TITLE, 0, I*16, 1041, 16` 建出来 —— 正常走 1；
 * 但 `resources/MISC/list.csv` 里同名资源表（`TW_title001,title.webp,0,16,1041,16`）
 * 在原版是另一条 `AppContents.LoadContents` 的 CSV 资源路径（spec: `AppContents.cs:107-178`）；
 * 该表现由 `ResourceContents.load` 在 `GameHost.installGraphics` 装配时登记进
 * `SpritePool`（`putResource`），所以名字能查到就走表。
 * 本层是表查不到时的兜底：**只在 sprite 表未命中**时用，可见结果与原版一致，
 * 差别只是「文本标签」变成「图」。
 *
 * ## 名字 → 文件
 *
 * 语料里资源名分两类形态：`title.webp` 这种**带扩展名**的（CSV 第一列的包文件名），
 * 和 `TW_title001` / `bodyGraph_X` 这种**纯名字**的（sprite 名）。
 * 后者没有可靠的「名字 → 目录」映射（`mkResourceXml.py` 生成的 XML 才是那张表，
 * 本端口未接），所以本类只做**候选文件名扫描**：在游戏根的
 * [CANDIDATE_DIRS] × [IMAGE_EXTENSIONS] 里找一个名字对得上的文件（下钻深度见 [MAX_DEPTH]），
 * 找到就整文件解码。找不到返回 null（与原版「画标签」等价）。
 * TODO(verify)：本层命中的实测量（真机上有多少 `<img>` 走到这里而不是 sprite 表），
 * 卡点：需要 logcat 计数 + 真机取证，本波只保证不崩、不挡主路。
 *
 * @param gameRoot 游戏根（`GameStorage.gameRoot()`）
 * @param fileOps 宿主文件口（本类不用它读字节：`FileOps.readText` 是文本口径，
 *                 图片必须按字节读，见下方注释）
 */
class GameImageFiles(
    private val gameRoot: File,
    @Suppress("unused") private val fileOps: FileOps? = null,
    private val maxSide: Int = DEFAULT_MAX_SIDE,
) {

    /** 只量尺寸不分配像素（`<img>` 缺 `width` 时推纵横比用）；见 [resourceNameSize] 的建实例理由。 */
    private fun newBoundsOnly(): BitmapFactory.Options =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }

    /**
     * 整文件解码的 `Options`，**每次解码新建**，与 [newBoundsOnly] 同源同理由。
     *
     * 早先它是一个共享的 `private val options` 字段，被 [decodeByResourceName] 复用 —— 单线程时
     * 省一次分配，接上后台预取（[SpriteBitmapSource.prefetchAsync]）之后就成了并发写：
     * `BitmapFactory` 把 `outWidth` / `outHeight` 等 `out*` 字段写在同一个实例上，后台预取线程与
     * UI 同步解码线程同时解两张不同的图时，读到的尺寸/配置可能来自另一张图（表现为错图、
     * 错尺寸，最坏按错误的宽高分配像素）。`Options` 是纯数据对象、构造成本可忽略，
     * 所以按「每次解码一份」换掉跨线程串味。
     */
    private fun newDecodeOptions(): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

    /**
     * 名字 → 文件的索引。**并发容器**：本类被两个线程同时用 —— VM 线程（`GCREATEFROMFILE`
     * 语句链经 `GraphicsMethods.readImageFile` 的解码通道之外，还有 `SpriteBitmapSource` 在
     * HTML 展平里查尺寸）与 UI 线程（`CanvasConsoleView.onDraw` 经 `SpriteBitmapSource.frame`
     * 取图，CanvasConsoleView.kt:319/:436）。两条路都会走到 [locate] 的「未命中 → 补登记」，
     * 普通 `HashMap` 边读边写是数据竞争，所以用 `ConcurrentHashMap` +
     * [ConcurrentHashMap.putIfAbsent]（谁先登记都行，值同一个文件）。
     *
     * 构造即后台预热（见下方 init 块）；构建本身仍由 lazy SYNCHRONIZED 保护（先到先建）。
     */
    private val index by lazy { buildIndex() }

    // 首次按名查询发生在 CanvasConsoleView.onDraw（UI 线程）。若等 lazy 现建，第一帧要付
    // walkTopDown 数千文件 + 整图解码的成本，明显掉帧、最坏 ANR。所以构造时即起 daemon
    // 后台线程触发预热：本类在 VM 线程构造（GameHost.installGraphics），daemon 不阻塞进程
    // 退出；游戏根没有资源目录时 buildIndex 快速返回空，预热无害。
    // 并发安全：lazy 默认 SYNCHRONIZED，预热线程与 UI 首查先到先建、另一方等锁；
    // 预热线程只读 index（触发构建），不写任何共享可变状态，无新竞态面。
    // 注意本块必须在 index 声明之后：Kotlin 按文本顺序初始化，放前面线程读 index
    // 可能撞上未初始化的 lazy delegate。
    //
    // 栈大小 512 KB（原 64 KB）：`buildIndex` 走 `walkTopDown`，那是**递归**下钻目录，
    // 64 KB 在深目录树上会直接 StackOverflowError（实测 `:platform:testDebugUnitTest`
    // 每次都在这里打一行 `StackOverflowError thrown from the UncaughtExceptionHandler`）。
    // 预热挂了不影响功能 —— `index` 是 lazy，UI 首查会同步重建 —— 但预热等于白起，
    // 而白起的代价是玩家进场第一帧付整棵树 walk 的成本（掉帧/最坏 ANR），所以必须让它活着。
    // 另外线程体整个兜住 Throwable：这是 best-effort 预热，任何异常都不该冒到
    // UncaughtExceptionHandler（那会打红字吓人，还让日志看起来像真崩了）。
    init {
        Thread(
            null,
            { runCatching { index } },
            "gameimage-warmup",
            512 * 1024,
        ).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * 资源名对应的候选尺寸（宽, 高）；查不到或量不出来返回 null。
     *
     * `Options` 每次新建，不当场复用：`inJustDecodeBounds` 量出来的尺寸写在
     * `outWidth/outHeight` 这两个**实例字段**上，共享一份有两处坑 ——
     * 1）`decodeFile` 解不动时不保证复位这两个字段，读到的可能是**上一次量的那张图**；
     * 2）本类同时被 VM 线程（排版量宽）与绘制线程（每帧取图，见 `SpriteBitmapSource`
     *    的 KDoc「绘制侧每帧都会重新问一次」）调用，共享可变字段就是跨线程串味。
     *
     * ## 正值尺寸缓存（feat-memory-image-cache 2026-11-02）
     *
     * 本方法在 VM 线程的 HTML 排版链上被反复调（`HtmlRunFlatten` 对每个缺 `width` 的
     * `<img>` 都要问一次纵横比，同一页多次展平就多次问），旧写法每次都真开一次文件量头
     * （`decodeFile` 的 bounds 档也要 open + 解析容器头）。语料是静态的：同一个名字量出来
     * 的尺寸在进程内不会变（mod 覆盖内容走「重启游戏生效」，进程重启即重建），所以量到的
     * **正值**结果按归一化名字缓存（[sizeCache]），排版链从此零文件 IO。
     * 负结果（量不出）不缓存：那是损坏文件的形态，罕见，缓存它要另立过期语义，不值得。
     */
    fun resourceNameSize(resource: String): IntArray? {
        val key = normalizeKey(resource) ?: return null
        sizeCache[key]?.let { return unpackSize(it) }
        val file = locate(resource) ?: return null
        val boundsOnly = newBoundsOnly()
        // inJustDecodeBounds：解出来的 Bitmap 恒为 null，尺寸写在 options 上
        runCatching { BitmapFactory.decodeFile(file.absolutePath, boundsOnly) }
        val w = boundsOnly.outWidth
        val h = boundsOnly.outHeight
        if (w <= 0 || h <= 0) return null
        // 只缓存正值；putIfAbsent —— 并发首量同一文件时谁先都行（同一文件同一尺寸）。
        sizeCache.putIfAbsent(key, packSize(w, h))
        return intArrayOf(w, h)
    }

    /**
     * 正值尺寸缓存：归一化名字 → 打包的 (宽, 高)（[packSize] / [unpackSize]）。
     *
     * 条目数上界 = 语料里被问过尺寸的不同名字数（索引本身 ~3 千文件 × 2 键形态），
     * 每条只是一个 Long —— 总量与单条都天然有界，**不设 LRU、不做整表清空**：
     * 语料静态，这点重量的代价远小于一套淘汰机制的复杂度（取舍同 [FailedLookupCache]，
     * 但它有 TTL 语义要清、这里没有）。
     */
    private val sizeCache = ConcurrentHashMap<String, Long>()

    /** 归一化键：与 [locate] 同式（trim + 小写 + 拒空/拒 `..`）；null = 该串不做键。 */
    private fun normalizeKey(resource: String): String? {
        val key = resource.trim().lowercase()
        if (key.isEmpty() || key.contains("..")) return null
        return key
    }

    /**
     * 按**路径**整文件解码（`GraphicsBuffer.fileSource` 的落地口）。
     *
     * 与 [decodeByResourceName] 的区别只有两点，都是为了「一张工作表当多帧图源」这个形态：
     * - 键是**路径**而不是资源名（G_ID 缓冲登记的就是 `GCREATEFROMFILE` 拿到的那个路径）；
     * - 不套 [maxSide] 那道闸：`GCREATEFROMFILE` 已经按 `GraphicsBuffer.MAX_IMAGE_SIZE`
     *   校过尺寸，这里再按「单边像素」拒一次会把 8000x4000 的立绘工作表整个丢掉。
     *
     * 解出来的位图在 native 堆（API 26+），不进 Dalvik 堆 —— 这正是绕开 512 MiB 堆挡的关键：
     * 同尺寸的 ARGB 若先解成 `IntArray`，单张就是 122 MiB。
     */
    fun decodeFileByPath(path: String): Bitmap? {
        val key = path.trim()
        if (key.isEmpty() || key.contains("..")) return null
        val direct = File(key)
        val file = if (direct.isAbsolute) direct else File(gameRoot, key)
        if (!file.isFile) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath, newDecodeOptions())
        } catch (t: Throwable) {
            Log.i(TAG, "按路径解码失败 $key: ${t.javaClass.simpleName}")
            null
        }
    }

    /** DevTools `img` 命令：资源名实际命中的文件（null = 索引与直查都miss）。 */
    fun locateForDebug(resource: String): File? = locate(resource)

    /** 资源名 → 整文件解码；查不到 / 解不出返回 null。调用方负责缓存与回收。 */
    fun decodeByResourceName(resource: String): Bitmap? {
        val file = locate(resource) ?: return null
        // bounds-first（feat-memory-image-cache）：先只量容器头、不分配像素，
        // 超 [maxSide] 直接拒 —— 旧写法先把整张图解成 native 位图再 recycle，
        // 语料实测那 5 张超限图单张 72–122 MiB，等于每命中一次白付一次全尺寸解码
        // （主线程上就是一次可感的停顿 + native 堆尖峰）。量不出的坏文件
        // （outWidth/outHeight ≤ 0）放行走原路：下面的整解码会如实返回 null。
        val bounds = newBoundsOnly()
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        if (exceedsMaxSide(bounds.outWidth, bounds.outHeight, maxSide)) {
            Log.i(TAG, "按名解码拒超大图 ${file.name} ${bounds.outWidth}x${bounds.outHeight}")
            return null
        }
        val options = newDecodeOptions()
        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (t: Throwable) {
            Log.i(TAG, "按名解码失败 ${file.name}: ${t.javaClass.simpleName}")
            null
        } ?: return null
        // recycle 后再读 width/height 在 AOSP 是 undefined behavior（日志会打出 0x0），
        // 所以先取进局部变量，判断与日志都用局部值。解码后再判一遍是兜底：
        // bounds 与实际解码之间语料不会变（同 [resourceNameSize] 的静态性论证），
        // 留着它只为防解码器对损坏头量错尺寸的极端形态。
        val w = bitmap.width
        val h = bitmap.height
        if (exceedsMaxSide(w, h, maxSide)) {
            bitmap.recycle()
            Log.i(TAG, "按名解码跳过超大图 ${file.name} ${w}x$h")
            return null
        }
        return bitmap
    }

    /**
     * 名字命中一个文件（归一化：`FileOps` 那条根越界拒绝在这里不适用，本类只读不写）。
     *
     * 索引必须是**并发安全**的：`locate` 不只读，未命中时还要把「原样带扩展名」那条
     * 直查结果回填进索引，而调用方 [SpriteBitmapSource] 同时被 VM 线程（排版量宽）与
     * 绘制线程（每帧取图）用 —— 普通 `HashMap` 的边读边写会丢条目甚至死循环。
     */
    private fun locate(resource: String): File? {
        val key = resource.trim().lowercase()
        if (key.isEmpty() || key.contains("..")) return null
        index[key]?.let { return it }
        // 带扩展名的写法（`title.webp`）：原样试一次
        val direct = File(gameRoot, key)
        if (direct.isFile) index.putIfAbsent(key, direct)
        return index[key]
    }

    /**
     * 一次性扫候选目录建索引（构造时由 daemon 预热线程触发，不等首次查图）。
     *
     * 下钻深度上限见 [MAX_DEPTH] —— 它不是「3 层够用」的经验值，而是按语料实测分布定的：
     * 原实现写死 `maxDepth(3)`，把 `resources` 下最深那一层的 125 张图整个漏掉了
     * （`MISC/infoThumb/<角色>/<表情>/x.png` 这一形态，例
     * `resources/MISC/infoThumb/ちんこ/キノコ/えりんぎ.png`，相对 `resources` 深度 4）。
     */
    private fun buildIndex(): ConcurrentHashMap<String, File> {
        val map = ConcurrentHashMap<String, File>(1024)
        for (dir in CANDIDATE_DIRS) {
            val root = File(gameRoot, dir)
            if (!root.isDirectory) continue
            root.walkTopDown().maxDepth(MAX_DEPTH).forEach { file ->
                if (!file.isFile) return@forEach
                val ext = file.extension.lowercase()
                if (ext !in IMAGE_EXTENSIONS) return@forEach
                val stem = file.nameWithoutExtension.lowercase()
                map.putIfAbsent(stem, file)
                map.putIfAbsent(file.name.lowercase(), file)
            }
        }
        Log.i(TAG, "资源名索引：${map.size / 2} 个图片文件（根=${gameRoot.absolutePath}）")
        return map
    }

    companion object {
        /** 只在这些子目录里找（`resources` 是原版内容根，spec: `AppContents.cs:109-115` 的 `Program.ContentDir`）。 */
        private val CANDIDATE_DIRS = arrayOf("resources", "img", "dat")
        private val IMAGE_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg", "bmp", "gif")

        /**
         * 候选目录下钻深度上限（`walkTopDown().maxDepth`，根自身算 0）。
         *
         * 语料实测（`eratw-画蛇添足版eratw4.980/resources`，3,140 张图片按**相对
         * `resources`** 的深度分布）：
         *
         * | 深度 | 0 | 1 | 2 | 3 | 4 | ≥5 |
         * |---|---|---|---|---|---|---|
         * | 张数 | 1 | 982 | 882 | 1,150 | **125** | **0** |
         *
         * 原值 3 会漏掉深度 4 那 125 张（`MISC/infoThumb/<角色>/<表情>/x.png` 形态），
         * 表现为「按名兜底查不到 → 退回原版的画标签」。取 8 是「实测最大深度 4 + 4 层余量」：
         * 上限本身只用来防符号链接环（`FileTreeWalk` 无环检测，软链自引用会无限下钻），
         * 不是内容政策，所以只要不切到语料就够。新增更深的资源时按这张表复核一次。
         */
        private const val MAX_DEPTH = 8

        /**
         * 按名兜底的边长上限。
         *
         * 语料实测（2026-10-22 扫 [CANDIDATE_DIRS] 三个目录共 3,139 张能量出尺寸的图片）：
         * 任一边 > 4096 的只有 **5** 张（`As06MARK-IV/anime/服_*_笑顔动_25.webp` 4 张 8000x3000~4000、
         * `isaac/cg/isaac_cg.webp` 8192x2304），**0** 张超过原版的函数层上限 8192
         * （spec: `AbstractImage.cs:8` 的 `MAX_IMAGESIZE` → `FunctionMethodCreator.cs:6905` / `:8177`）。
         * 也就是说这里的 4096 比原版口径**紧一倍**，会把那 5 张整文件图丢掉。
         * TODO(verify)：这 5 张都是拼图集，正常由 `SPRITECREATE` 走 sprite 表命中、不经本类，
         *   所以暂不放宽；放宽到 8192 会让一次解码要 75 MB 位图，真机 OOM 与否需要实测。
         *   卡点：需要 logcat 计数确认「按名兜底」实际命中过哪些资源名。
         */
        private const val DEFAULT_MAX_SIDE = 4096
        private const val TAG = "eratw-image"

        /**
         * 把 (宽, 高) 打包成一个 Long（宽在高 32 位、高在低 32 位），给 [sizeCache] 当值 ——
         * 一条 Long 顶一个 `intArrayOf`，条目小一半还不引数组分配。只对正值宽高有意义
         * （[GameImageFiles.resourceNameSize] 只缓存正值），负数不进这条通道。
         */
        internal fun packSize(w: Int, h: Int): Long =
            (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)

        /** [packSize] 的逆变换；返回 (宽, 高)。 */
        internal fun unpackSize(packed: Long): IntArray =
            intArrayOf((packed shr 32).toInt(), (packed and 0xFFFFFFFFL).toInt())

        /**
         * 单边超限判定（纯函数，测试口径用）：任一边 > [maxSide] 即超限。
         * 宽/高 ≤ 0（解码器量不出的坏头）**不**算超限 —— 那该由解码路径自己返回 null，
         * 超限闸只管「解得出来但太大」这一种形态。
         */
        internal fun exceedsMaxSide(w: Int, h: Int, maxSide: Int): Boolean =
            w > maxSide || h > maxSide
    }
}
