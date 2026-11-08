package com.eratw.port.platform.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.Log
import android.util.LruCache
import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.host.ResourceFileHost
import com.eratw.port.core.image.PngImage
import com.eratw.port.render.memory.PixelPreflight
import java.io.File

/**
 * [ResourceFileHost] 的 Android 实现：扫 `resources` 下的 CSV、按路径读字节、读图片头、解码。
 *
 * 目录口径对应原版 `Program.ContentDir` = `<gameRoot>/resources`
 * （`Program.cs` 的 `ContentDir` 绑定 + `AppContents.cs:115` 的递归扫盘）。
 * 图片解码交给已装好的 [ImageDecoderHost]（`:platform` 的 `BitmapImageDecoder`，能吃 png/webp），
 * 与 `GCREATEFROMFILE` 同一通道，避免 CSV 资源与脚本图出现两套色彩/透明度口径。
 *
 * 只读文件头（[imageSize]）用 `inJustDecodeBounds`：4.980 的 4 个 CSV 引用 134 张图（全量解码
 * 约 105 MiB），4.981 的 266 个 CSV 引用 2,795 张（570.6 MB）。尺寸探测只走头部，**一个字节的
 * 图片内容都不进堆**（`bytes == null` 走 `decodeFile`），像素留给首访问
 * （见 `GraphicsBuffer.createDeferred`）。这条是 4.981 真机启动 OOM 的修复点：装载期若先把
 * 整份字节读进来，让 512 MiB 挡的机器必然撞墙（判据在 `ResourceContents` 的类 KDoc）。
 *
 * TODO(verify)：`BitmapFactory` 的两条调用是**唯一**的 Android-API 面，`android.jar` 是桩
 * （`unitTests.isReturnDefaultValues` ⇒ `decodeByteArray/decodeFile` 恒返回 null、
 * `outWidth/outHeight` 停在 0），所以 [imageSize] 在单测里**恒返回 null**（走调用方的
 * 「尺寸未知」回落）。真机验收口径：CSV 引用的立绘 XRAY 图层能正常合成 ⇒ 头部读数正确；
 * 纯逻辑面（[isCsvName] / [relativeOf]）已由 `ResourceCsvHostTest` 钉死。
 */
class ResourceCsvHost(
    private val gameRoot: File,
    private val decoder: ImageDecoderHost,
) : ResourceFileHost {

    override fun listCsvFiles(): List<String> {
        val root = File(gameRoot, RESOURCES_DIR)
        if (!root.isDirectory) return emptyList()
        // 根路径只取一次：`walkTopDown()` 每命中一个文件都要一次 `absolutePath`，
        // 原先的 `relative()` 每次都重算 `gameRoot.absolutePath`（扫盘 134 张图时纯属重复）。
        val base = gameRoot.absolutePath
        return root.walkTopDown()
            .filter { it.isFile && isCsvName(it.name) }
            .map { relativeOf(base, it.absolutePath) }
            .toList()
    }

    /**
     * 整文件读字节（**Java 堆**上的一份 `ByteArray`）。
     *
     * 分配前预检（lane oomalloc）：这一句自己就是 Java 堆上的一笔整份字节。0.2.38 的
     * 130402 / 130617 两份报告里，崩溃现场的 `--- java ---` 段原样帧名是
     * `ResourceCsvHost.readBytes:44` → `FilesKt.readBytes:74`（`Arrays.copyOf` 在扩那份量级），
     * 抛出来的却是 parseRow 里十几字节的串拼接 —— 堆被逐张整份读进来的图字节顶满、
     * 下一笔什么都炸。装载期那条路已由 3eecad37 改成「登记期只读文件头、字节留给像素首访问」，
     * 本函数因此只剩两条来路：CSV 正文（KB 级）与延迟源落地时那一份图片压缩字节
     * （语料里 MB～几十 MB 级，4.981 的 CSV 引用图共 570.6 MB）。堆贴顶时这一笔照样能把整局带走，
     * 而它的失败出口与既有口径同一条：返回 null ⇒ CSV 侧「打开失败，已跳过」/
     * 像素侧「解不出来」，缺这一块但游戏继续。
     *
     * 判式见 [PixelPreflight.affordsFileBytes]：这一口**只走头寸腿**（空闲装得下就读），
     * 不套「单件上限」那条加码 —— 那条量的是解码后的 ARGB 像素阵列，压缩字节和它不是一个单位，
     * 而本口的失败面也比像素那一路大（CSV 正文读不到 = 整张表的 sprite 一起没，不是一块图）。
     * 4.981 语料 3,090 张图里单张最大文件 8.7 MiB，离任何挡位的上限差一个量级，
     * 这一腿在字节侧本来一次也不会生效，摘掉它不改任何现有读数（lane preflightblast 尺寸普查）。
     * `runCatching` 原本就吞 Throwable（含 OOM），
     * 所以这里只补**预检**，不改动失败折成 null 的既有形状。
     */
    override fun readBytes(path: String): ByteArray? = runCatching {
        val file = File(gameRoot, path)
        val length = file.length()
        if (!bytesReadable(length)) {
            Log.i(TAG, "整文件读字节预检不足 $path（$length B）—— 按读不到降级，${PixelPreflight.readout(length)}")
            return@runCatching null
        }
        file.readBytes()
    }.getOrNull()

    override fun imageSize(path: String, bytes: ByteArray?): IntArray? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (bytes != null) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } else {
            BitmapFactory.decodeFile(File(gameRoot, path).path, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return intArrayOf(options.outWidth, options.outHeight)
    }

    override fun decode(path: String, bytes: ByteArray): PngImage? = decoder.decode(path, bytes)

    /**
     * 只解 `(x, y, w, h)` 这一块（PIXREG）：CSV 拼图的**大表**整张解会被像素预算拒
     * （4.981 的 `汎用モブ子dairi版.webp` = 6962x6189 = 164.4 MiB ARGB > 预算 128 MiB），
     * 而 `GDRAWSPRITE` 合成单层只用到它的 sprite 子矩形（该表最大 850x1280 ≈ 4.3 MiB）。
     * 修的就是「布娃娃预览整页空白」：解码预检拒 ⇒ 源像素空 ⇒ 合成全透明。
     *
     * 两条通道，按**整张大小**选：
     * 1. 整张 ARGB **超像素预算**（> [REGION_WHOLE_SHEET_MIN_BYTES]，本语料仅那张 164.4 MiB 表）：
     *    由 [wholeSheetBitmap] 整张解一次落 native，之后每层只 `getPixels` 拷子矩形。
     *    必须这样：`BitmapRegionDecoder` 对 **lossy WebP 没有真正的子集解码**，逐笔都要把
     *    6964x6191 的比特流过一遍 —— 一屏 20+ 层就是 20+ 次全解，玩家体感 5~6 秒
     *    （切种族换一张表同理）。
     * 2. 常规表：逐笔 `BitmapRegionDecoder`，**decoder 复用**（[regionDecoders] 8 条 LRU）——
     *    每笔 `newInstance` 都要重开并解析文件头，一屏 20+ 层就是 20+ 次重复解析。
     *
     * 验收尺寸只判「**不小于** w×h」（WebP 上 `decodeRegion` 会返回略大的位图，真机读数要
     * 288x244 得 289x244），严格相等会把绝大多数层判死：合成只画上第一层、画布不透明像素
     * 200498 → 3689，屏幕上就是「大立绘只剩两个小碎片」。
     *
     * 线程：`GDRAWSPRITE` 在 VM 线程调进来，渲染线程不走这条；三张表都带锁。
     * 失败（文件不在 / 区域越界 / 解码异常）一律 null ⇒ 调用方退回整张 [decode] 或按缺图降级。
     */
    override fun decodeRegion(path: String, x: Int, y: Int, w: Int, h: Int): PngImage? {
        if (w <= 0 || h <= 0 || x < 0 || y < 0) return null
        val startedNs = System.nanoTime()
        val key = "$path#$x,$y,$w,$h"
        synchronized(regionLock) { regionCache.get(key) }?.let { return it }
        val file = File(gameRoot, path)
        if (!file.isFile) {
            Log.w(TAG, "区域解码：文件不在 $path")
            return null
        }
        // **超大表：整张解一次，之后每层只拷子矩形**（2026-11-06 性能）。
        //
        // 为什么必须这样：`BitmapRegionDecoder` 对 **lossy WebP** 没有真正的子集解码，每笔
        // `decodeRegion` 都要把 6964x6191 的比特流过一遍 —— 布娃娃页一屏 20+ 层就是 20+ 次全解，
        // 真机实测玩家要等 5~6 秒（切种族换一张表同理）。整张解一次（native 位图）后每层只做
        // 一次 `getPixels` 小块拷贝，20 层合计不到 10 ms。
        //
        // 触发面被 [REGION_WHOLE_SHEET_MIN_BYTES] 卡在「整张超过像素预算」的极少数表：常规立绘表
        // （122 MiB 级）仍走 [BitmapRegionDecoder]，不会与 `SpriteBitmapSource.sheetCache` 重复驻留。
        wholeSheetBitmap(path)?.let { sheet ->
            val sx = x.coerceAtMost(maxOf(0, sheet.width - 1))
            val sy = y.coerceAtMost(maxOf(0, sheet.height - 1))
            val cw = w.coerceAtMost(sheet.width - sx)
            val ch = h.coerceAtMost(sheet.height - sy)
            if (cw <= 0 || ch <= 0) return null
            val cropped = IntArray(cw * ch)
            runCatching { sheet.getPixels(cropped, 0, cw, sx, sy, cw, ch) }.getOrNull() ?: return null
            val image = if (cw == w && ch == h) PngImage(w, h, cropped) else padTo(cropped, cw, ch, w, h)
            synchronized(regionLock) { regionCache.put(key, image) }
            regionDecodeCount++
            regionDecodeNanos += System.nanoTime() - startedNs
            return image
        }
        // 常规表（整张 <= 像素预算）：逐笔 `BitmapRegionDecoder`，**decoder 复用**
        // （[regionDecoders] 8 条 LRU）——每笔 newInstance 都要重开并解析文件头，
        // 一屏 20+ 层就是 20+ 次重复解析，纯浪费。
        val regionDecoder = obtainDecoder(file)
        if (regionDecoder == null) {
            Log.w(TAG, "区域解码：decoder 建不出来 $path")
            return null
        }
        val bitmap = runCatching {
            // 非预乘存底（ BitmapImageDecoder.pixelArrayDecodeOptions 的单一来源）：
            // 这张位图只 getPixels 抽数组、随即 recycle，要的是文件直通像素——
            // 预乘存底再去预乘读出的那一步正是「脸红变脸黑」（预乘值被当直通值消费）
            // 的押注面，真机 Skia WebP 区域解码已实测过契约外行为（见上）。
            regionDecoder.decodeRegion(Rect(x, y, x + w, y + h), BitmapImageDecoder.pixelArrayDecodeOptions())
        }.onFailure {
            // Skia 内部状态偶发失败：丢掉这张嘴重建，重试留给下一笔。
            releaseDecoder(path, regionDecoder)
            Log.w(TAG, "区域解码异常 $path ($x,$y ${w}x$h)：$it")
        }.getOrNull()
        if (bitmap == null) {
            Log.w(TAG, "区域解码：解不出位图 $path ($x,$y ${w}x$h)")
            return null
        }
        // **尺寸只要求「不小于」**：WebP 上 `BitmapRegionDecoder.decodeRegion` 会返回**略大**的位图
        // （真机读数：要 288x244 得 289x244 —— 解码器按内部对齐把宽补到整块），
        // 严格相等一判就会把绝大多数层判死：合成只画上第一层、画布不透明像素 200498 → 3689，
        // 屏幕上就是「大立绘只剩两个小碎片」。多出来的列/行直接丢弃，取左上 w×h。
        if (bitmap.width < w || bitmap.height < h) {
            Log.w(TAG, "区域解码：尺寸不足 $path 要 ${w}x$h 得 ${bitmap.width}x${bitmap.height}")
            bitmap.recycle()
            return null
        }
        val pixels = IntArray(w * h)
        // stride = w：从（可能更宽的）位图里按行取前 w 列，逐行拼进紧凑数组。
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        val image = PngImage(w, h, pixels)
        synchronized(regionLock) { regionCache.put(key, image) }
        regionDecodeCount++
        regionDecodeNanos += System.nanoTime() - startedNs
        return image
    }

    /**
     * 每张表一个复用 `BitmapRegionDecoder`（[REGION_DECODERS] 条 LRU，逐出即 `recycle`）。
     *
     * 复用是 2026-11-06 性能波的一部分：每笔 `newInstance` 都要重开并解析文件头，
     * 布娃娃页一屏 20+ 层合成就是 20+ 次重复解析（真机体感 5~6 秒的一部分）。
     * 失败由 [releaseDecoder] 丢掉重建，坏状态不留在缓存里。
     */
    private val regionDecoders = object : LruCache<String, BitmapRegionDecoder>(REGION_DECODERS) {
        override fun sizeOf(key: String, value: BitmapRegionDecoder): Int = 1

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: BitmapRegionDecoder,
            newValue: BitmapRegionDecoder?,
        ) {
            runCatching { if (!oldValue.isRecycled) oldValue.recycle() }
        }
    }

    /** 取（或建）[file] 对应的复用 decoder；建不出来返回 null。 */
    private fun obtainDecoder(file: File): BitmapRegionDecoder? {
        val path = file.path
        synchronized(regionLock) { regionDecoders.get(path) }?.let { if (!it.isRecycled) return it }
        val created = runCatching { BitmapRegionDecoder.newInstance(file.path, false) }.getOrNull() ?: return null
        synchronized(regionLock) { regionDecoders.put(path, created) }
        return created
    }

    /** 丢掉一张 decoder（失败重建用）。 */
    private fun releaseDecoder(path: String, decoder: BitmapRegionDecoder) {
        synchronized(regionLock) { if (regionDecoders.get(path) === decoder) regionDecoders.remove(path) }
        runCatching { if (!decoder.isRecycled) decoder.recycle() }
    }

    /**
     * 超大表的**整张 native 位图**缓存（键 = 路径）：只对「整张 ARGB 超像素预算」的表启用。
     *
     * 为什么不共用 `SpriteBitmapSource.sheetCache`：那个服务「CSV sprite 直接上屏」，这里服务
     * 「脚本合成取源像素」，两条链通常不同屏；共用会把平台层与渲染层的生命周期缠在一起。
     * 触发面由 [REGION_WHOLE_SHEET_MIN_BYTES] 卡死 —— 本语料只有 `汎用モブ子dairi版.webp`
     * （6962x6189 = 164.4 MiB）越线，常规立绘表一个字节行为都不变。
     */
    private val wholeSheets = object : LruCache<String, Bitmap>(WHOLE_SHEET_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            runCatching { if (!oldValue.isRecycled) oldValue.recycle() }
        }
    }

    /** 整张解不出/不够格的路径（负缓存：别每层都重读一次文件头再失败）。 */
    private val wholeSheetRefused = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * 取（必要时整张解码）[path] 的 native 位图；不够大 / 太大 / 解不出都返回 null，
     * 调用方退回 [BitmapRegionDecoder] 逐笔区域解码。
     *
     * 判据顺序：`imageSize` 只读文件头拿尺寸（一个像素都不解）→ 低于 [REGION_WHOLE_SHEET_MIN_BYTES]
     * 不接管（区域解码更省）→ 高于 [WHOLE_SHEET_SINGLE_MAX_BYTES] 放弃（宁慢不 OOM）→ 整张解码。
     */
    private fun wholeSheetBitmap(path: String): Bitmap? {
        synchronized(regionLock) { wholeSheets.get(path) }?.let { if (!it.isRecycled) return it }
        if (wholeSheetRefused.contains(path)) return null
        val file = File(gameRoot, path)
        if (!file.isFile) return null
        val size = imageSize(path, null) ?: run {
            wholeSheetRefused.add(path)
            return null
        }
        val bytes = size[0].toLong() * size[1].toLong() * 4L
        if (bytes <= REGION_WHOLE_SHEET_MIN_BYTES) return null
        if (bytes > WHOLE_SHEET_SINGLE_MAX_BYTES) {
            Log.w(TAG, "整张解码放弃：$path 需 $bytes B > 单张上限 $WHOLE_SHEET_SINGLE_MAX_BYTES B")
            wholeSheetRefused.add(path)
            return null
        }
        val decoded = runCatching {
            // 非预乘存底，与区域解码同源（见 BitmapImageDecoder 类 KDoc 像素口径段）：
            // 这份整张位图同样只 getPixels、从不画上 Canvas。
            BitmapFactory.decodeFile(file.path, BitmapImageDecoder.pixelArrayDecodeOptions())
        }.onFailure {
            Log.w(TAG, "整张解码异常 $path：$it")
        }.getOrNull()
        if (decoded == null) {
            Log.w(TAG, "整张解码失败 $path")
            wholeSheetRefused.add(path)
            return null
        }
        synchronized(regionLock) { wholeSheets.put(path, decoded) }
        Log.i(TAG, "整张解码 ${size[0]}x${size[1]}（${bytes / (1024 * 1024)} MiB native）：$path")
        return decoded
    }

    /** 把 `cw x ch` 的紧凑像素按左上角贴进 `w x h`（目标更大时右下留透明）。 */
    private fun padTo(src: IntArray, cw: Int, ch: Int, w: Int, h: Int): PngImage {
        val out = IntArray(w * h)
        for (row in 0 until ch) System.arraycopy(src, row * cw, out, row * w, cw)
        return PngImage(w, h, out)
    }
    /**
     * 区域解码的累计耗时/笔数（诊断读数）。
     *
     * 为什么留这个：玩家能感知的「点一下要等好几秒」必须能量化，不能靠体感。
     * 清零后走一次页面、再读差值，就是这一次合成的解码总开销。
     */
    @Volatile
    var regionDecodeNanos: Long = 0L
        private set

    @Volatile
    var regionDecodeCount: Long = 0L
        private set

    /** 把 [regionDecodeNanos] / [regionDecodeCount] 清零（页面重建前后各读一次，差值即本次开销）。 */
    fun resetRegionDecodeStats() {
        regionDecodeNanos = 0L
        regionDecodeCount = 0L
    }
    /** [decodeRegion] 的缓存锁（两张表共用；临界区都只有查/放，不含解码本体）。 */
    private val regionLock = Any()

    /** 区域解码结果缓存：键 = 路径 + 矩形，按像素字节 LRU（[REGION_CACHE_BYTES]）。 */
    private val regionCache = object : LruCache<String, PngImage>(REGION_CACHE_BYTES) {
        override fun sizeOf(key: String, value: PngImage): Int = value.pixels.size * 4
    }

    /**
     * 纯逻辑伴生（`AndroidApiSurfaceGuardTest` 的登记要求 a）：本类唯一碰 Android 的地方是
     * [imageSize] 的 `BitmapFactory`，扫盘判定与相对路径换算全是纯函数，放这里由
     * `ResourceCsvHostTest` 在纯 JVM 下钉死 —— `android.jar` 是桩（`unitTests.
     * isReturnDefaultValues`），留在实例方法里就等于零覆盖。
     */
    internal companion object {
        /** 原版 `Program.ContentDir` 的目录名（`AppContents.cs:109-115`）。 */
        const val RESOURCES_DIR = "resources"

        /**
         * 「这么大一份整文件字节现在读得起吗」的判式（纯 JVM 转发 [PixelPreflight.affordsBytes]）：
         * 登记要求 a) 的那一侧 —— 本类的 Android 面只有 `BitmapFactory`，读字节的闸只是一层转发，
         * 由 `GraphicsDecodePreflightGateTest` 在真机贴顶挡位的注入读数下钉住。
         */
        internal fun bytesReadable(lengthBytes: Long): Boolean = PixelPreflight.affordsFileBytes(lengthBytes)

        /** 与图片解码那一族同一个 logcat 标签（取证行要能一起捞）。 */
        internal const val TAG = "eratw-image"

        /**
         * 区域解码缓存预算（64 MiB）：布娃娃页一层最大 850x1280 = 4.3 MiB，一屏 20+ 层
         * 合计可达 80 MiB 级；32 MiB 会在换色重画时来回逐出重解，64 MiB 让「同一页再画一遍」
         * 基本全命中（换色/切种族后的重画是玩家最常走的路径）。
         */
        private const val REGION_CACHE_BYTES = 64 * 1024 * 1024

        /** [ResourceCsvHost.regionDecoders] 的同时留存数（一张表一张嘴）。 */
        private const val REGION_DECODERS = 8

        /**
         * 「整张解码接管」的门槛（整张 ARGB 字节）：超过**像素预算**（128 MiB）的表才由
         * [ResourceCsvHost.wholeSheetBitmap] 整张解一次 —— lossy WebP 没有真正的子集解码，
         * 这种表逐笔区域解码每层都要全解一遍，一屏 20+ 层就是玩家体感的 5~6 秒。
         */
        private const val REGION_WHOLE_SHEET_MIN_BYTES = 128L * 1024L * 1024L

        /** 整张 native 位图的单张上限：再大就放弃（宁慢不 OOM），退回逐笔区域解码。 */
        private const val WHOLE_SHEET_SINGLE_MAX_BYTES = 192L * 1024L * 1024L

        /** [ResourceCsvHost.wholeSheets] 的预算（native 堆）：一张 164.4 MiB 的表 + 余量。 */
        private const val WHOLE_SHEET_CACHE_BYTES = 192 * 1024 * 1024


        /** 后缀是否是 CSV（大小写不敏感；口径同 `AppContents.cs:115` 只认 `.csv`）。 */
        fun isCsvName(name: String): Boolean = name.endsWith(".csv", ignoreCase = true)

        /**
         * 绝对路径 → 「相对 [baseAbs]、`/` 分隔」的路径（CSV 第二列的图片路径也按 `/` 拼）。
         *
         * **分隔符不读 `File.separatorChar`**：Android 恒 `/`，而这条逻辑的单测跑在 Windows 上
         * （`\`），读平台分隔符会让同一份逻辑在两边给出不同字符串 —— 那正是「真机对、CI 红」
         * 的经典形状。两种分隔符都认，输出恒 `/`。
         */
        fun relativeOf(baseAbs: String, fullAbs: String): String {
            val base = baseAbs.trimEnd('/', '\\')
            val rest = if (fullAbs.startsWith(base)) fullAbs.substring(base.length) else fullAbs
            return rest.trimStart('/', '\\').replace('\\', '/')
        }
    }
}
