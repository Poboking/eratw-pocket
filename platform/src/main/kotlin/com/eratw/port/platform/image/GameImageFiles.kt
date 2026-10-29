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

    private val options = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    /** 只量尺寸不分配像素（`<img>` 缺 `width` 时推纵横比用）；见 [resourceNameSize] 的建实例理由。 */
    private fun newBoundsOnly(): BitmapFactory.Options =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }

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
     */
    fun resourceNameSize(resource: String): IntArray? {
        val file = locate(resource) ?: return null
        val boundsOnly = newBoundsOnly()
        // inJustDecodeBounds：解出来的 Bitmap 恒为 null，尺寸写在 options 上
        runCatching { BitmapFactory.decodeFile(file.absolutePath, boundsOnly) }
        val w = boundsOnly.outWidth
        val h = boundsOnly.outHeight
        if (w <= 0 || h <= 0) return null
        return intArrayOf(w, h)
    }

    /** DevTools `img` 命令：资源名实际命中的文件（null = 索引与直查都miss）。 */
    fun locateForDebug(resource: String): File? = locate(resource)

    /** 资源名 → 整文件解码；查不到 / 解不出返回 null。调用方负责缓存与回收。 */
    fun decodeByResourceName(resource: String): Bitmap? {
        val file = locate(resource) ?: return null
        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (t: Throwable) {
            Log.i(TAG, "按名解码失败 ${file.name}: ${t.javaClass.simpleName}")
            null
        } ?: return null
        // recycle 后再读 width/height 在 AOSP 是 undefined behavior（日志会打出 0x0），
        // 所以先取进局部变量，判断与日志都用局部值
        val w = bitmap.width
        val h = bitmap.height
        if (w > maxSide || h > maxSide) {
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
    }
}
