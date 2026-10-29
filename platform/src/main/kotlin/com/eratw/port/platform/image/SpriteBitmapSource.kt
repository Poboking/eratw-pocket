package com.eratw.port.platform.image

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.host.SpritePool
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.sprite.SpriteAnimeClock
import com.eratw.port.render.sprite.SpriteAnimeTimeline
import com.eratw.port.render.view.ImageFrame
import com.eratw.port.render.view.ImageFrameSource
import java.util.WeakHashMap

/**
 * 资源名 → 可绘制位图（M10-GFX 的上屏取图口）。
 *
 * ## 查表顺序与原版同构
 *
 * 原版所有图形语句最后都落到同一张名字表：
 * `AppContents.GetSprite(name)`（spec: `AppContents.cs:39-51`，键按
 * `ToUpper(InvariantCulture)` 归一，`AppContents.cs:45`），
 * `PRINT_IMG` 与 HTML 的 `<img>` 用它（spec: `ConsoleImagePart.cs:60`）、
 * `SETBGIMAGE` 用它（spec: `EmueraConsole.cs:899`）、CBG 也用它。
 * 本类的第一个来源就是 core 挂在 `store.aux` 上的同一张表
 * （[SpritePool]，槽名 `sprite`，由 `SPRITECREATE` / `SPRITEANIMECREATE` 写入）；
 * 查不到时按**文件**兜一层（`resources/<name>.<ext>`，见 [GameImageFiles]）——
 * 原版没有这层（sprite 查不到就把 `<img ...>` 标签当文本画，spec: `ConsoleImagePart.cs:60-64`），
 * 但那是用户可见的降级，不是脚本可见行为，按平台最优补上。
 *
 * ## 像素来自 GraphicsBuffer
 *
 * `SpriteG` / `SpriteF` 的像素是它那张 `GraphicsImage`（缓冲）的一个子矩形
 * （spec: `AppContents.cs:80-105` 的 `CreateSpriteG(name, parent, rect, ...)`）。
 * 移植侧同构：[SpritePool.Sprite.buffer] 是 [GraphicsBuffer]，
 * `srcX/srcY/srcW/srcH` 就是子矩形。缓冲的 ARGB 数组转成 `Bitmap` 之后按 gid 缓存，
 * **数组引用换了才重建**（`GCREATE` / `GLOAD` 每次都换新数组，引用比较即换代检测），
 * 这样一屏十来个 sprite 也只会在图真的变了时分配一次。
 *
 * 按名字兜底的文件图同样**只解一次**：`fileCache` 以资源名为键，[frameOf] 先查缓存、
 * miss 才碰磁盘。绘制侧每帧都会重新问一次（`CanvasConsoleView.drawBackgrounds` 与
 * `drawImage` 都按资源名现取），命中缓存还去 `BitmapFactory.decodeFile` 等于每帧重解
 * 一遍磁盘图，新解出来的位图还会在命中旧缓存时被丢掉（native 内存白占）。
 *
 * ## 动画 sprite 的取帧（GFX-07）
 *
 * `SpriteAnime` 的帧号是**时刻的函数**：原版 `GetCurrentFrame` 用
 * `(DateTime.Now - StartTime) 毫秒 % totaltime` 逐帧累减选出（SpriteAnime.cs:90-119），
 * 第一次取帧时把 `StartTime` 置为当下并返回第 0 帧（:96-102）；`SETANIMETIMER` 只改
 * **重绘节奏**（EmueraConsole.cs:967-980），不参与帧推进。所以「同一 sprite 在不同时刻
 * 取到不同帧」不需要任何状态机推进：每次取图现读一次宿主时钟，减起点、走一遍
 * [SpriteAnimeTimeline.frameAt] 即可（帧序、`<= 0` 的闭区间边界、停止条件都在那个纯函数里）。
 * 与渲染帧的同步 = 取图发生在 `onDraw` 里，画的就是此刻该显示的那一帧；掉帧自然跳帧，
 * 与原版一致。周期重绘由宿主按 [animeRepaintIntervalMs] 排（见该方法的 KDoc）。
 *
 * 每只 sprite 一份 [SpriteAnimeClock]（起点），按对象身份挂在 [animeClocks]：
 * `SPRITECREATE` 覆盖同名换新对象 ⇒ 新对象新起点，动画从头播，与原版换新 `SpriteAnime` 同构。
 * 静态 sprite（`frames == null`）不经过时钟，恒画它那一块矩形 —— 「未注册动画的 sprite
 * 恒第 0 帧」在类型上就成立。
 *
 * **帧内偏移随帧透传**：`AnimeFrame.Offset`（SpriteAnime.cs:20）是「这一帧相对
 * `DestBaseSize` 画布该往右下挪多少」，`AddFrame` 里先过一遍 `Normalize`
 * （SpriteAnime.cs:24-36，把 `Offset + SrcRectangle.Size` 与 `DestBaseSize` 求交，
 * 交出来的 X/Y 回写 `Offset`、宽高回写 `SrcRectangle`）。原版画的时候把它叠在
 * `DestBasePosition` 上（:153-154）。本类只负责把 `offsetX/offsetY` 与
 * `sprite.posX/posY` 一起放进 [Frame] / [ImageFrame]，换算在纯 JVM 的
 * `GraphicGeometry.spriteDestRect`（`:render`）里做 —— 这样那一式有单测钉住。
 * 语料实证：`l13/留琴/留琴-服/anime.xml` 每帧 `param="0,0,704,734,15,0,42"`（xpos=15）、
 * `留琴-兔女郎/anime.xml` 每帧 `param="0,0,752,752,0,80,42"`（ypos=80）；
 * 静态侧 6,851 个资源条目带非零 `xpos/ypos`（`As06MARK-IV/拼接.xml` 的小件拼装）。
 *
 * ## 线程约定
 *
 * 本类被两个线程同时访问：**VM 线程**（装配在 `GameHost.installGraphics`，
 * 语句侧经 `HtmlRunFlatten.ImageSizeSource.destSize` 与展平链查图，GameHost.kt:494-511）
 * 与 **UI 线程**（`CanvasConsoleView.onDraw` 经 [ImageFrameSource.frame] 取图，
 * CanvasConsoleView.kt:319、:436）。可变状态因此有两条纪律：
 * - [bufferCache] / [fileCache] 用 `LruCache`（`get` / `put` / `evictAll` 内部 synchronized）；
 *   取图一律**先查缓存、未命中才解码**（[frameOf]、[cachedFile]），所以常态帧零解码。
 *   竞态（两线程同时未命中同一个名字）下会各建一份位图：先登记的那份继续服务绘制，
 *   后到的那份被 [cacheFile] `recycle` 交还像素内存 —— 不崩、也不漏（回收的永远是
 *   本线程独占的那份），不为这次分配加锁。
 * - [GameImageFiles] 的资源索引本来是普通 `HashMap` 且在 [frameOf] 未命中路径上**边读边写**
 *   （两个线程都可能触发补登记），已改成 `ConcurrentHashMap` + `putIfAbsent`（见其 KDoc）。
 * - `aux.peek`（sprite 表）与 `invalidate()`：前者读的是 core 的 `store.aux`，后者只调
 *   `LruCache.evictAll`（自身同步）。跨线程可见性按 `LruCache` 的锁边界成立；sprite 表
 *   本身的并发口径归 core（`RuntimeAux`），不在本类补。
 * - [animeClocks] 用 `WeakHashMap` + 一把私有锁：键是 `SpritePool.Sprite` 身份
 *   （默认 `equals` = 同一对象），弱引用保证 `SPRITEDISPOSE` 之后不吊着 sprite；
 *   [SpriteAnimeClock] 本身不线程安全，全部读写都在那把锁里。
 *
 * @param aux 本 VM 的附属状态容器（只 `peek` 不 `slot`：查图不该顺手建表）
 * @param maxBitmapBytes 位图缓存上限（字节）；按 `Runtime.maxMemory` 的一小部分给默认值，
 *                       语料里 1041×1041 的拼图一张就是 4.3 MB，不设上限会顶掉整局
 * @param clockMs 动画取帧的时钟读数（毫秒，只需单调、不要求 epoch）。默认
 *                [SystemClock.elapsedRealtime]：单调，改系统时间不会让帧倒退；
 *                原版读 `DateTime.Now` 但也只取差值（SpriteAnime.cs:108），等价。
 *                注入点只给单测/装置用，生产不传。
 */
class SpriteBitmapSource(
    private val aux: RuntimeAux,
    private val files: GameImageFiles,
    maxBitmapBytes: Int = DEFAULT_CACHE_BYTES,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) : ImageFrameSource, HtmlRunFlatten.ImageSizeSource {

    /** 一帧可绘制内容：位图 + 子矩形 + 目标尺寸 + 落位（`ASprite` 的 pos 与动画帧 Offset）。 */
    class Frame(
        val bitmap: Bitmap,
        val srcX: Int,
        val srcY: Int,
        val srcW: Int,
        val srcH: Int,
        /** 目标宽（`ASprite.DestBaseSize.Width`）；<= 0 = 脚本没给，绘制侧按位图尺寸画。 */
        val destW: Int,
        val destH: Int,
        /** `ASprite.DestBasePosition`（`SPRITECREATE` 的 `xpos/ypos`）；动画 sprite 恒 0。 */
        val basePosX: Int = 0,
        val basePosY: Int = 0,
        /** 动画**当前帧**的 `AnimeFrame.Offset`（`SPRITEANIMEADDFRAME` 第 7/8 参）；静态恒 0。 */
        val offsetX: Int = 0,
        val offsetY: Int = 0,
        /** 是否 `SpriteAnime`：决定落位算式走不带 `DestBasePosition.IsEmpty` 门的那一支。 */
        val anime: Boolean = false,
    ) {
        val width: Int get() = if (destW > 0) destW else srcW
        val height: Int get() = if (destH > 0) destH else srcH
    }

    private val bufferCache = object : LruCache<Int, BitmapEntry>(maxBitmapBytes) {
        override fun sizeOf(key: Int, value: BitmapEntry): Int = value.bytes
    }
    private val fileCache = object : LruCache<String, Bitmap>(maxBitmapBytes / 2) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 动画 sprite 的计时起点（键 = sprite 身份，弱引用；见类 KDoc 的线程约定）。 */
    private val animeLock = Any()
    private val animeClocks = WeakHashMap<SpritePool.Sprite, SpriteAnimeClock>()

    /**
     * 按资源名取一帧（[ImageFrameSource] 的实现口）：句柄就是这张 `Bitmap`，
     * 几何是 sprite 在拼图里的子矩形与 `DestBaseSize`。
     *
     * 动画 sprite 每调一次就现读一次 [clockMs]，所以同一资源在连续两个渲染帧里可能给到
     * 不同子矩形（同一张拼图位图、不同 `srcX/srcY`）—— 这正是原版逐帧换图的效果，
     * 位图本身不重建（[bitmapOf] 按缓冲 id 缓存）。
     */
    override fun frame(resource: String): ImageFrame? {
        val f = frameOf(resource) ?: return null
        return ImageFrame(
            handle = f.bitmap,
            srcX = f.srcX,
            srcY = f.srcY,
            srcW = f.srcW,
            srcH = f.srcH,
            destW = f.destW,
            destH = f.destH,
            basePosX = f.basePosX,
            basePosY = f.basePosY,
            offsetX = f.offsetX,
            offsetY = f.offsetY,
            anime = f.anime,
        )
    }

    /**
     * 内部帧形态（位图 + 子矩形 + 目标尺寸）。
     *
     * 文件层**先查缓存再解码**：原版这张名字表是常驻字典（spec: `AppContents.cs:39-51`），
     * 取图不产生任何 IO；HTML 的 `<img>` 与 `PRINT_IMG` 每帧都会走到这里
     * （CanvasConsoleView.kt:319、:436），若每次先 `decodeByResourceName` 整张解码、
     * 再在 [cacheFile] 里把新位图丢掉，就是「每帧每图数十 MB 分配 + 旧位图不回收」的 OOM 路径。
     * 命中缓存直接返回（已回收的条目按未命中处理）；未命中才解码并登记。
     */
    fun frameOf(resource: String): Frame? {
        if (resource.isEmpty()) return null
        val sprite = aux.peek<SpritePool>(SLOT_SPRITE)?.find(resource)
        if (sprite != null) return spriteFrame(sprite)
        val bitmap = cachedFile(resource)
            ?: files.decodeByResourceName(resource)?.let { cacheFile(resource, it) }
            ?: return null
        return Frame(bitmap, 0, 0, bitmap.width, bitmap.height, 0, 0)
    }

    /**
     * `<img>` 没写 `width` 时按纵横比推宽所需的基图目标尺寸（`ASprite.DestBaseSize`）。
     *
     * 位布局约定见 [packDestSize]：高 16 位 = 宽、低 16 位 = 高。宽 ≥ 0x8000 时打包
     * 结果是负 Int——解包侧（render/adapter/HtmlRunFlatten.kt:332-333）用
     * `base ushr 16`（无符号右移）取宽、`base and 0xFFFF` 取高，不受符号影响；
     * 该 round-trip 由 SpriteBitmapSourcePackTest 钉死 0x7FFF/0x8000/0xFFFF 边界。
     */
    override fun destSize(resource: String): Int? {
        val sprite = aux.peek<SpritePool>(SLOT_SPRITE)?.find(resource)
        if (sprite != null) return packDestSize(sprite.width, sprite.height)
        val size = files.resourceNameSize(resource) ?: return null
        return packDestSize(size[0], size[1])
    }

    /**
     * 缓存里还有没有这张资源（绘制侧用它判断「上一次画过的图现在没了」要不要重擦）。
     * 先走 [cachedFile]：已缓存的图不再解码（同 [frameOf] 的「先查后解」）。
     */
    fun has(resource: String): Boolean = frameOf(resource) != null

    /** DevTools img 命令：文件层定位（sprite 表 miss 时的兜底文件）。 */
    fun locateFileForDebug(resource: String) = files.locateForDebug(resource)

    /** 换代（读档 / RESTART / `SPRITECREATE` 覆盖）后作废全部缓存。 */
    fun invalidate() {
        bufferCache.evictAll()
        fileCache.evictAll()
        // 起点也一起清：原版换图/读档后是新的 SpriteAnime（StartTime 归零），动画从头播。
        synchronized(animeLock) { animeClocks.clear() }
    }

    /**
     * 宿主排周期重绘用的读数：`SETANIMETIMER` 的毫秒数折算后的结果，0 = 不排。
     *
     * 原版 `setRedrawTimer`（EmueraConsole.cs:967-980）：`<= 0` 关定时器、`< 10` 抬到 10。
     * 帧推进不读它（见类 KDoc），它只决定「多久重画一次」；[SpriteAnimeTimeline.repaintIntervalMs]
     * 是纯函数，这里只负责把 core 的池级读数（`SETANIMETIMER` 写的 `SpritePool.animeTimerMs`）接出来。
     *
     * 接线点：`GameHost.kt:1169` 的 `view.attachAnimeRepaint(gfx.frames::animeRepaintIntervalMs)`，
     * 由 `CanvasConsoleView.attachAnimeRepaint` / `scheduleAnimeTick` 按本值排下一次重画
     * （定时器为 0 时退回既有的一次性重绘）。
     */
    fun animeRepaintIntervalMs(): Int =
        SpriteAnimeTimeline.repaintIntervalMs(aux.peek<SpritePool>(SLOT_SPRITE)?.animeTimerMs ?: 0)

    private fun spriteFrame(sprite: SpritePool.Sprite): Frame? {
        sprite.frames?.let { frames ->
            // totaltime <= 0（没有任何帧）⇒ GetCurrentFrame 返回 null，一个像素都不画（SpriteAnime.cs:92-95）。
            if (frames.isEmpty()) return null
            // 读时钟 + 算帧号整段收进 animeLock：SpriteAnimeClock 的起点是读-改-写且本身
            // 不线程安全（UI 取图与 VM 排版两个线程都会进这里），必须串行；锁内只有
            // clockMs() 读数与纯函数，无重入路径。
            val idx = synchronized(animeLock) {
                animeClocks.getOrPut(sprite) { SpriteAnimeClock() }
                    .frameAt(clockMs(), SpriteAnimeTimeline.delaysOf(frames))
            }
            if (idx < 0) return null
            // 帧表只由 VM 线程 append（SPRITEANIMEADDFRAME），没有收缩路径；getOrNull 只兜
            // 「delaysOf 读到的 size 与此刻不一致」的弱一致窗口，热路径上是一次边界检查。
            val f = frames.getOrNull(idx) ?: return null
            val bitmap = bitmapOf(f.buffer) ?: return null
            if (f.srcW <= 0 || f.srcH <= 0) return null
            // 帧内偏移随帧走：`AnimeFrame.Offset`（SpriteAnime.cs:20，写入点
            // GraphicsMethods 的 SPRITEANIMEADDFRAME 第 7/8 参）。绘制侧按
            // SpriteAnime.cs:153-154 把它叠到 DestBasePosition 上，丢掉就是整段动画偏移。
            return Frame(
                bitmap, f.srcX, f.srcY,
                minOf(f.srcW, bitmap.width - f.srcX),
                minOf(f.srcH, bitmap.height - f.srcY),
                sprite.width, sprite.height,
                sprite.posX, sprite.posY, f.offsetX, f.offsetY,
                anime = true,
            )
        }
        val buffer = sprite.buffer ?: return null
        val bitmap = bitmapOf(buffer) ?: return null
        val x = sprite.srcX.coerceIn(0, maxOf(0, bitmap.width - 1))
        val y = sprite.srcY.coerceIn(0, maxOf(0, bitmap.height - 1))
        val w = sprite.srcW.coerceIn(1, bitmap.width - x)
        val h = sprite.srcH.coerceIn(1, bitmap.height - y)
        // 静态 sprite 的 `DestBasePosition`（ASpriteSingle.cs:79-85 的位移项）。
        return Frame(bitmap, x, y, w, h, sprite.width, sprite.height, sprite.posX, sprite.posY)
    }

    /** 缓冲 → 位图（按 gid 缓存，数组引用换了才重建）。 */
    private fun bitmapOf(buffer: GraphicsBuffer): Bitmap? {
        // CSV 资源表的缓冲是延迟解码的（首像素访问才落地）：先取像素再读尺寸，
        // 并要求 `pixels.length == width * height` —— `Bitmap.createBitmap` 对长度不符
        // 抛的是 IllegalArgumentException（不是 OOM），漏判就是 UI 线程直接崩。
        val pixels = buffer.pixels
        val w = buffer.width
        val h = buffer.height
        if (w <= 0 || h <= 0 || pixels.size != w * h) return null
        val cached = bufferCache.get(buffer.id)
        if (cached != null && cached.pixels === pixels && !cached.bitmap.isRecycled) return cached.bitmap
        val bitmap = try {
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (t: OutOfMemoryError) {
            Log.w(TAG, "gid=${buffer.id} ${w}x${h} 建位图 OOM，跳过")
            return null
        }
        bufferCache.put(buffer.id, BitmapEntry(bitmap, pixels, bitmap.byteCount))
        return bitmap
    }

    /**
     * 文件图缓存命中：已回收的条目按未命中处理（顺手摘掉，免得 [frameOf] 每帧都读到它）。
     * 这张位图可能被上一次 [Frame] 的 handle 引用着，回收方在本类之外，所以这里只判不回收。
     */
    private fun cachedFile(resource: String): Bitmap? {
        val cached = fileCache.get(resource) ?: return null
        if (cached.isRecycled) {
            fileCache.remove(resource)
            return null
        }
        return cached
    }

    /**
     * 登记一张刚解码的位图；同名字已有一份活的位图时**保留命中者、回收本次这份**。
     *
     * 原版的名字表是常驻字典（spec: `AppContents.cs:39-51`），同一名字换图只在
     * `LoadContents` 重新载入时发生。本端走到这里时 [cachedFile] 已判过未命中，
     * 因此 `put` 返回非空只意味着**竞态**：另一线程在这两步之间登记了同名字的位图，
     * 而它已经可能被取成 [Frame] 的 handle 交给绘制 —— 回收它会崩（Canvas 拒绝画已回收位图），
     * 所以改成保留它、把本次新解码的这份 `recycle` 交还像素内存。
     * 这正是早先「先解码再进缓存、发现已有缓存就把新位图丢掉」那条泄漏的收口：
     * 4.3 MB 的拼图每丢一次就是一次 OOM 的分子。容量淘汰由缓存上限兜底，本类不代它回收。
     */
    private fun cacheFile(resource: String, bitmap: Bitmap): Bitmap {
        val previous = fileCache.put(resource, bitmap)
        if (previous != null && previous !== bitmap && !previous.isRecycled) {
            fileCache.put(resource, previous)
            runCatching { bitmap.recycle() }
            return previous
        }
        return bitmap
    }

    /** 缓存里的一份位图 + 它是从哪个像素数组来的（引用比较 = 换代检测）。 */
    private class BitmapEntry(val bitmap: Bitmap, val pixels: IntArray, val bytes: Int)

    companion object {
        /** `GraphicsMethods.kt` 的 sprite 槽名（core 侧私有常量，这里按同名约定复述一份）。 */
        const val SLOT_SPRITE = "sprite"

        /**
         * 宽高打包成一个 Int：`(width and 0xFFFF) shl 16 or (height and 0xFFFF)`。
         *
         * 位布局约定：**高 16 位是宽、低 16 位是高**，各 16 位，字段值先截断到 0xFFFF。
         * 宽 ≥ 0x8000（bit15 置位）时打包结果为负 Int——这是约定内行为，不是溢出 bug；
         * 解包必须用 `ushr 16`（无符号右移）取宽、`and 0xFFFF` 取高（生产解包点：
         * render/adapter/HtmlRunFlatten.kt:332-333），两侧口径由 SpriteBitmapSourcePackTest
         * 的 round-trip 边界用例（0x7FFF/0x8000/0xFFFF）钉死。抽成纯 JVM 函数是为了
         * 让该测试不触碰 Android API 也能验证。
         */
        fun packDestSize(width: Int, height: Int): Int =
            (width and 0xFFFF) shl 16 or (height and 0xFFFF)

        /** 位图缓存上限：语料拼图一张 4.3 MB，8 张常驻 + 余量。 */
        private const val DEFAULT_CACHE_BYTES = 32 * 1024 * 1024

        private const val TAG = "eratw-image"
    }
}
