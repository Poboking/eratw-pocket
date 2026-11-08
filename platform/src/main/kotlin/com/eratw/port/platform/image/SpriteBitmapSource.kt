package com.eratw.port.platform.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.eratw.port.core.host.GRAPHICS_SLOT
import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.MemoryTier
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.host.SPRITE_SLOT
import com.eratw.port.core.host.SpritePool
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.sprite.SpriteAnimeClock
import com.eratw.port.render.sprite.SpriteAnimeTimeline
import com.eratw.port.render.view.ImageFrame
import com.eratw.port.render.view.ImageFrameSource
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

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
 * ## 未命中不在调用线程解码（本波 FEEL1-L1 的处置刀）
 *
 * 症状是玩家那句「点一下没反应」：[frameOf] 在缓存**未命中**时原本**在调用线程上同步解码**
 * （调用线程就是 UI 线程，取证行的出处见 [logSlowDecode] 的 KDoc），单张 8000x4000 的 webp
 * 解成 ARGB 是 122 MiB 级、中端机上是几百毫秒的一次阻塞，一屏多张就是几帧连吃。
 * 现在未命中只做三件事：**查负缓存 → 把名字排进本类自有的单线程解码槽 → 返回 null**。
 * `null` 就是既有的「缺图降级」形状（绘制侧走 `CanvasConsoleView.drawImage` 的缺帧回落，
 * 版面几何不动，尺寸本来就按资源名算、不依赖像素），解码在槽里跑完并经 [cacheFile] 登记同一张表，
 * 就绪之后由 [setDecodeReadyListener] 通知口把「这张图有了」投回主线程重画。
 *
 * 只有**按资源名**这一条通道改成异步：按名通道被 [GameImageFiles] 的 `maxSide = 4096` 闸住
 * （单张至多 4096x4096x4B = 64 MiB），解出来的条目**必定**进得了 [fileCache] 或 [sheetCache]
 * （见 [cacheFile] 的路由），于是「排一次、解一次、之后一直命中」成立。
 * `GraphicsBuffer.fileSource` 那条**按路径**的通道（[bitmapOf] 的 fileSource 支）保持同步：
 * 它不设尺寸闸，装不进预算的条目按既有口径**不入表**（[fitsSheetCache]），改成异步就等于
 * 「每帧重排一张永远留不住的解码」，而且它的键是路径、与屏上资源名不同源，就绪通知要落到行
 * 还得再加一张台账 —— 那一刀的取舍与前置条件写在交付回执，不在本波顺手做。
 *
 * ## 线程约定
 *
 * 本类被两个线程同时访问：**VM 线程**（装配在 `GameHost.installGraphics`，
 * 语句侧经 `HtmlRunFlatten.ImageSizeSource.destSize` 与展平链查图，GameHost.kt:494-511）
 * 与 **UI 线程**（`CanvasConsoleView.onDraw` 经 [ImageFrameSource.frame] 取图，
 * CanvasConsoleView.kt:319、:436）。可变状态因此有两条纪律：
 * - [bufferCache] / [fileCache] 用 `LruCache`（`get` / `put` / `evictAll` 内部 synchronized）；
 *   取图一律**先查缓存、未命中就把解码排进解码槽**（[frameOf]、[cachedFile]），所以常态帧零解码、
 *   未命中帧在调用线程也是零解码（见下面「未命中不在调用线程解码」）。
 *   竞态（两线程同时未命中同一个名字）下会各建一份位图：先登记的那份继续服务绘制，
 *   后到的那份被 [cacheFile] `recycle` 交还像素内存 —— 不崩、也不漏（回收的永远是
 *   本线程独占的那份），不为这次分配加锁。
 * - [GameImageFiles] 的资源索引本来是普通 `HashMap` 且在 [frameOf] 未命中路径上**边读边写**
 *   （两个线程都可能触发补登记），已改成 `ConcurrentHashMap` + `putIfAbsent`（见其 KDoc）。
 * - `aux.peek`（sprite 表）与 `invalidate()`：前者读的是 core 的 `store.aux`，后者只调
 *   `LruCache.evictAll`（自身同步）。跨线程可见性按 `LruCache` 的锁边界成立；sprite 表
 *   本身的并发口径归 core（`RuntimeAux`），不在本类补。
 * - 第三条线程是**解码槽线程**（原「预取线程」，见 [decodeQueue] 与 [startDecodeWorker]；
 *   最多一条、空闲自退）：它**只碰文件层**（[files] 的按名解码 + [cacheFile] 的登记），
 *   不读 `GraphicsBuffer.pixels`、不进 [frameOf]；竞争窗口里的重复解码由 [cacheFile] 的
 *   单胜者纪律收口（见该函数的 KDoc）。它现在同时服务两条来路：语句侧入队的预取
 *   （[prefetchAsync]）与绘制侧未命中的补解（[submitDecodeRequest]），**同一条有界队列、
 *   同一个在途台账**，不存在第二张表。锁序只有一条：`workerLock → decodeQueue` 的内部锁
 *   （起停线程时读一眼队列长度）；[submitDecodeRequest] 先拿队列锁、放开之后才拿 `workerLock`，
 *   反向嵌套不存在，故不会成环。
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
    /**
     * gid 帧的「缓冲 → 句柄」一跳（**单测注入点，生产不传**：null = 走 [bitmapOf]）。
     *
     * 为什么需要它：本模块单测跑在纯 JVM 上（`unitTests.isReturnDefaultValues = true`），
     * `android.graphics.Bitmap` 是桩 —— `Bitmap.createBitmap` 恒返回 null，[bitmapOf] 的
     * 像素支在桩里**永远建不出位图**。没有这一跳，`frameByGid` 的「gid 命中 → 非空帧」
     * 这条链在本机没有任何可执行证据（真机帧率/像素读数又不可自动化）。
     *
     * 类型是 `Any?` 而不是 `Bitmap?`：桩里连一个 `Bitmap` 实例都造不出来，测试只能拿
     * 「不透明句柄」当替身（与 [ImageFrame.handle] 的 `Any?` 契约同口径）。注入点只影响
     * 「最后一跳」—— gid 查表、只查不建、未建/已 dispose 的闸、几何映射全在
     * [frameByGid] 里照跑；[bitmapOf] 的 fileSource 快路 / 降级复用 / OOM 兜底 / LRU 预算
     * 一个字节都没被绕开（生产恒 null）。
     */
    private val gidFrameHandle: ((GraphicsBuffer) -> Any?)? = null,
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

    /** [fileCache] 的预算（字节）；与它的 maxSize 同源，供 [fitsFileCache] 的路由判定用。 */
    private val fileCacheBudgetBytes: Int = maxBitmapBytes / 2

    private val fileCache = object : LruCache<String, Bitmap>(fileCacheBudgetBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 文件源位图缓存预算：见 [sheetCache]、[SHEET_CACHE_BUDGET_BYTES] 与档位读法
     * [defaultSheetCacheBytes]。
     *
     * 旧口径 `Runtime.getRuntime().maxMemory() / 2` 已废（2026-10-30 稳定性审计 #3(c)）：
     * 那是 **Java 堆**数字，却用来预算 **native** 位图。新口径的**额度**仍是与 Java 堆无关的
     * 文档化常数（保守档 [SHEET_CACHE_BUDGET_BYTES] / 放开档
     * [SHEET_CACHE_BUDGET_BYTES_GENEROUS]，写成 `MiB × 1024 × 1024` 的 Int 字面量：
     * 384 MiB = 402,653,184 < `Int.MAX_VALUE`，不溢出）——
     * 旧 KDoc 记过的「≥4 GiB 堆上 `/2` 溢出成负数、静默落到 64 MiB、每帧被逐出重解」
     * 那条 Long 溢出教训由常数定义方式直接根除（两档之间只做**选取**，仍不做任何运行期除法）。
     *
     * 别误读成「额度跟着堆走」：堆上限只在 [defaultSheetCacheBytes] 里当**档位信号**用
     * （[com.eratw.port.core.host.MemoryTier] 的第二条线，判的是「这台机是不是大内存机」），
     * 选出来的是那两个常数之一，不是任何除法结果。
     */
    private val sheetBytes: Int = defaultSheetCacheBytes()

    /**
     * 文件源缓冲的位图缓存。
     *
     * 键有两族（feat-memory-image-cache 起）：
     * - `GraphicsBuffer.fileSource` 的**路径**（[bitmapOf] 的 fileSource 支，本类原有）；
     * - **资源名** —— 按名引用但装不进 [fileCache] 预算的位图改挂本表（[cacheFile] 的路由）。
     *   两族键都是指向游戏内同一文件的串：资源名经 [GameImageFiles.locate] 归一成相对路径，
     *   与路径键撞串只可能发生在同一文件上，缓存同一位图无损；分表记录反而要为
     *   「同一个名字查两张表」多付一次心智负担，所以共用一张表（读侧 [cachedFile] 两族同查）。
     *
     * 单独一张表、预算也单给（[sheetBytes]），因为这里的条目尺寸与上面两张表不是一个量级：
     * 本作语料的立绘动图是**整张工作表**（`8000x4000` 的 webp，ARGB 122 MiB），
     * 塞进 32 MiB 的 [bufferCache] 会被当场逐出 ⇒ 每一帧都重解一次 ⇒ 帧率崩 + 原生内存反复大起大落。
     * 反过来，这张表里的条目是**位图**（native 堆），不占 Dalvik 堆，预算也不再与
     * Java 堆挂钩 —— 文档化常数（保守档 [SHEET_CACHE_BUDGET_BYTES] = 192 MiB、放开档 [SHEET_CACHE_BUDGET_BYTES_GENEROUS] = 384 MiB，理由与取舍见各自 KDoc；
     * 旧口径 `maxMemory()/2` 在 512 MiB 挡上放行 256 MiB native，堆遥测却看不见它）。
     *
     * ## 逐出即主动回收
     *
     * 条目被逐出（容量淘汰或 `invalidate()`）时不再只等 GC：进 [sheetRecycle] 排一会儿队，
     * 隔 [RECYCLE_GRACE_MS] 之后在 UI 线程上 `recycle()`。**不能当场回收** —— 那张位图
     * 很可能正被本帧的 `Canvas` 引用（硬件加速会把引用挂到 RenderNode 上直到帧提交），
     * 当场回收就是下一帧 `trying to use a recycled bitmap` 崩。理由与判据见
     * [DeferredRecycle] 的类 KDoc。
     *
     * 为什么只对这张表做：这张表的条目是 122 MiB 级的 native 位图，靠 GC 收要等到
     * 下一次 `NativeAllocationRegistry` 触发，而那时原生内存已经翻倍了；`bufferCache`
     * （32 MiB）与 `fileCache` 的条目小得多，沿用原口径（靠 GC）不折腾。
     */
    private val sheetCache = object : LruCache<String, Bitmap>(sheetBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            // newValue != null 表示这次是「同键替换」，旧值确实不该再被任何人引用；
            // 容量淘汰与 evictAll 都给 null。两种都要回收，所以只判 oldValue 非回收态。
            if (!oldValue.isRecycled) sheetRecycle.add(oldValue, SystemClock.elapsedRealtime())
        }
    }

    /**
     * 等待回收的位图（见 [sheetCache] 的「逐出即主动回收」）。
     *
     * 1 s 的宽限：一帧绘制是毫秒级，1 s 后连三缓冲都翻完了，不可能还有 Canvas 引用。
     */
    /**
     * [regionBitmapOf] 的位图缓存（原生表，[REGION_BITMAP_CACHE_BYTES]）：键 = 路径 + 源矩形。
     *
     * 不做显式 `recycle`（与 [sheetCache] 的 DeferredRecycle 不同）：这里单张最大 4.3 MiB、整表预算
     * 16 MiB，逐出后由 GC 收 native —— 少一条「回收与绘制线程抢同一张图」的时序面。
     */
    private val regionBitmaps = object : LruCache<String, Bitmap>(REGION_BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val sheetRecycle = DeferredRecycle<Bitmap>(RECYCLE_GRACE_MS)

    /**
     * 降级缓冲的 native 位图缓存（2026-11-08「查看其他人的位置」OOM 收尾刀）：键 = gid。
     *
     * ## 为什么不进 [bufferCache]
     *
     * [bufferCache] 预算 32 MiB，而降级位图的工作集是「一屏头像」级（0.2.43 报告那屏
     * ≈ 100 × 1.44 MiB = 144 MiB）：装不下就逐帧重解 —— native 解码虽无 Java 堆风险，
     * 但一屏几十张的解码量在等待输入的静态页上就是可感的掉帧。独立预算
     * （[defaultDemotedCacheBytes]，与池像素预算同分母）让整屏基本驻留。
     *
     * ## 凭据为什么是弱引用
     *
     * [DemotedEntry.credRef] 持的是降级代取图凭据（[GraphicsBuffer.demotedCredential]）
     * 的**弱**引用：命中条件是 `credRef.get() === 当代凭据`。缓冲留在降级代时 core 强引用着
     * 该凭据（堆内载荷载体 / 堆外闭包）⇒ 弱引用必活、命中稳定；一旦晋升（写路径）core
     * 换掉 Surface，旧凭据归 GC 管，我们的引用不越权延长它的生命周期 —— 若强引用，换代后
     * 的旧载荷会一直被这里钉在 Java 堆里（账本归零、载荷常驻，正是本仓猎过的第二类 OOM
     * 出口）。弱引用被清 ⇒ miss ⇒ 按当代凭据重解，与 [BitmapEntry.pixelsRef] 同一条纪律。
     *
     * ## 逐出即主动回收
     *
     * 条目是 MB 级 native 位图，靠 GC 还太晚：`entryRemoved` 把旧图排进 [sheetRecycle]
     * （与 [sheetCache] 共用同一条「宽限期 + 主线程排干」队列，见 [drainSheetRecycle]）。
     * `trimCaches` / `invalidate` 的 `evictAll` 走同一个钩子，会话收口由
     * [drainSheetRecycleOnClose] 兜底。
     */
    private val demotedCache: LruCache<Int, DemotedEntry> by lazy {
        object : LruCache<Int, DemotedEntry>(demotedCacheBudgetBytes()) {
            override fun sizeOf(key: Int, value: DemotedEntry): Int = value.bitmap.byteCount

            override fun entryRemoved(evicted: Boolean, key: Int, oldValue: DemotedEntry, newValue: DemotedEntry?) {
                // 与 sheetCache.entryRemoved 同口径：同键替换与容量淘汰/evictAll 都算作废，
                // 只判 oldValue 非回收态。宽限期内当前帧可能还引用着旧图，不当场 recycle。
                if (!oldValue.bitmap.isRecycled) sheetRecycle.add(oldValue.bitmap, SystemClock.elapsedRealtime())
            }
        }
    }

    /** [demotedCache] 的预算：优先取**活着的**池读数（尊重装置 override），取不到退缺省算式。 */
    private fun demotedCacheBudgetBytes(): Int {
        val fromPool = runCatching { aux.peek<GraphicsPool>(GRAPHICS_SLOT)?.pixelBudgetBytes() }.getOrNull()
        return fromPool?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: defaultDemotedCacheBytes()
    }

    /** [demotedCache] 的条目：位图 + 降级代取图凭据（弱引用，理由见该缓存的 KDoc）。 */
    private class DemotedEntry(val bitmap: Bitmap, val credRef: WeakReference<Any>)

    /** 动画 sprite 的计时起点（键 = sprite 身份，弱引用；见类 KDoc 的线程约定）。 */
    private val animeLock = Any()
    private val animeClocks = WeakHashMap<SpritePool.Sprite, SpriteAnimeClock>()

    /**
     * 「按资源名解不出来」的短时负缓存（见 [FailedLookupCache] 的类 KDoc）。
     *
     * 绘制侧每帧对屏上每个图各问一次 [frameOf]，未命中就去碰磁盘；解不出来的名字
     * （拼错、图不存在、超 `maxSide` 被丢）没有缓存条目可命中 ⇒ **每一帧**都要重走一次
     * `locate` + `decodeFile`。这张表把那些名字在 [FailedLookupCache.DEFAULT_TTL_MS]
     * 内直接判成「没有」，把主线程从重复的磁盘解码尝试里摘出来 —— 玩家反馈的
     * 「快速切换场景 / 打开指令菜单时闪退」，切场景时新名字最多、这条开销也最重。
     */
    private val missingFiles = FailedLookupCache()

    /**
     * 「按文件路径解不出来」的短时负缓存（`GraphicsBuffer.fileSource` 那条通道）。
     *
     * 与 [missingFiles] 分开记：键空间不同（资源名 vs 相对/绝对路径），同一个名字
     * 可能资源层查不到而路径层查得到，混在一张表里会互相误杀。
     */
    private val missingPaths = FailedLookupCache()

    /**
     * 解码槽：未命中补解与后台预取**共用的**一条有界队列（同一资源在途只排一次）。
     *
     * 容量 [DECODE_QUEUE_CAPACITY] 与判式都在 [DecodeQueue] / [planDecodeRequest]（纯 JVM，
     * 可在桌面单测直接驱动）。工作线程由 [startDecodeWorker] 按需起、空闲自退，
     * 玩家停在标题页时不常驻；daemon 线程不挡进程退出。
     *
     * 原实现这里是 `ThreadPoolExecutor(1, 1, ArrayBlockingQueue(64), AbortPolicy)` 加一只
     * `prefetchInFlight` 并发集合：队列与在途台账分两处、满时靠抛异常静默丢，**丢了多少**
     * 没有读数，而「永远解不完」与「解得慢」在日志里长得一样。改成自建台账后判式可测、
     * 丢弃可数。那条 `ConcurrentHashMap.newKeySet()` 是 API 24 的教训（本模块 `minSdk = 21`，
     * 2026-10-31 lint 抓到过）一并带过来：[DecodeQueue] 只用普通 `ArrayDeque` + `HashSet`
     * 加一把私有锁串行化，不碰任何高版本并发 API。
     */
    private val decodeQueue = DecodeQueue(DECODE_QUEUE_CAPACITY)

    /** 解码线程起停的临界区（锁序：`workerLock → decodeQueue` 的内部锁，反向不存在，见类 KDoc）。 */
    private val workerLock = Any()

    /** 当前解码线程；null = 没有活着的线程（还没起 / 空闲自退 / 已收口）。 */
    private var decodeWorker: Thread? = null

    /** 收口标志（[close] 置位）：置位后不起新线程，队列自身也拒绝新请求。 */
    private var decodeClosed = false

    /**
     * 「解码就绪」通知口（本波 FEEL1-L1 的上屏回路）。
     *
     * 装配侧（`GameHost.installGraphics` / `attachConsoleFeed`）把它接到
     * `CanvasConsoleView.onGraphicResourceReady`（行权闸在 render-canvas 侧，见该类）。**本类不认识 View**：`:platform` 不依赖 `:render-canvas`，接线由宿主做，
     * 没有新构造形参（见交付「需组长接线」）。
     *
     * 投递纪律：解码在槽线程上跑完，通知一律经 [notifyDecodeReady] **投回主线程**再调本口
     * —— 订阅方拿到回调时已经在 UI 线程，可以直接碰 View。null = 没人订阅（单测/装置形态），
     * 此时图照样缓存，只是上屏要等下一次自然重绘。
     */
    private var decodeReadyListener: ((String) -> Unit)? = null

    /**
     * 挂上「解码就绪」通知口（见 [decodeReadyListener] 的投递纪律）；传 null 摘掉。
     *
     * 只在装配期调一次（与 [prefetchAsync] 的接线同批）；写口是 `@Volatile` 语义的字段赋值，
     * 与读口 [notifyDecodeReady] 并发最坏是「这一张不通知」，下一帧自然重排重解，不影响正确性。
     */
    fun setDecodeReadyListener(listener: ((String) -> Unit)?) {
        decodeReadyListener = listener
    }

    /**
     * 后台预取一张按名引用的文件图（立绘首帧卡顿的对策）。
     *
     * ## 产品口径：宁可掉一帧，绝不空白
     *
     * 预取**只是把解码挪到后台**，不是「必须先预取完才能画」：`HTML_PRINT` 的 `<img>` 与
     * `PRINT_IMG` 每帧按资源名现取（`ConsoleFeed.publish` 在 UI 线程只做查表入队，
     * `GraphicsSinkBridge.printImage` / `setBgImage` 在 VM 线程只做查表入队）。后台线程解好的图
     * 经 [cacheFile] 登记（小图进 [fileCache]、超预算的按名大图进 [sheetCache]），[frameOf]
     * 直接命中；**没赶上时未命中路径也不再于调用线程解码**，而是把同一个名字排进同一个槽
     * （[submitDecodeRequest]，在途台账会把重复请求并成一条），本帧按既有的缺图形状降级、
     * 解好之后由 [decodeReadyListener] 补一次重画。所以这里任何失败（队列满、线程已收、
     * 解码异常）都只影响提前量，一律静默丢弃 + 计数，绝不抛到调用方、绝不占住 UI 线程。
     *
     * ## 不走的路
     *
     * - sprite 表命中的名字直接返回：`GraphicsBuffer.pixels` 由 VM 线程写，跨线程读是数据竞争，
     *   而 sprite 的位图本来只在绘制侧按 [frameOf] 的 sprite 分支建（与预取无关）。
     * - 已在缓存里的名字直接返回（正常情况下也不会再解）。
     *
     * 线程口径见 [decodeQueue] / [startDecodeWorker] 与类 KDoc；收口点是 [close]。
     */
    fun prefetchAsync(resource: String) {
        if (resource.isEmpty()) return
        // 先查两条「不用预取」的路，再排队：sprite 名字绝不能进后台线程（见方法 KDoc）。
        if (aux.peek<SpritePool>(SLOT_SPRITE)?.find(resource) != null) return
        if (cachedFile(resource) != null) return
        submitDecodeRequest(resource)
    }

    /**
     * 把一个「按资源名解码一次」的请求排进解码槽（[prefetchAsync] 与 [frameOf] 的未命中支
     * 共用这一处，因此只有**一条**队列、**一份**在途台账）。
     *
     * 判式全在 [planDecodeRequest]（纯函数）与 [DecodeQueue.offer]：负缓存优先、同名在途去重、
     * 队列满按**拒收**并计数（不丢已排队的：绘制侧每帧都会重新问一次，被拒的名字下一帧自然
     * 重排，重排的是**当下还在屏上**的那些 —— 丢最旧反而会解一张已经离屏的图）。
     *
     * @return 判式结果；调用方（未命中路径与预取路径）都不据此改变返回值 —— 两条路都返回
     *         「这一帧没有这张图」，排队成功与否只影响提前量与 [decodeQueue] 的丢弃读数。
     */
    private fun submitDecodeRequest(resource: String): DecodeRequest {
        val outcome = decodeQueue.offer(resource, missingFiles.shouldSkip(resource, clockMs()))
        if (outcome == DecodeRequest.Enqueue) startDecodeWorker()
        return outcome
    }

    /**
     * 需要干活时确保有一条解码线程（幂等；已收口 [decodeClosed] 后不再起）。
     *
     * 起线程整段在 [workerLock] 里：两只线程同时提交时不能各起一条（槽的口径是**最多一条**，
     * 两条同时解两张 122 MiB 工作表就是双份 native 尖峰）。
     *
     * 与 [decodeWorkerLoop] 的「空闲摘牌」配套：提交方是**先入队、后起线程**，摘牌方是
     * **在锁内确认队列真空才把 [decodeWorker] 置 null** —— 于是「入队发生在摘牌检查之后」
     * 这一路必然走到 `decodeWorker == null` 并重新起线程，不存在「排进去了但没人读」的丢失窗口。
     */
    private fun startDecodeWorker() {
        synchronized(workerLock) {
            if (decodeClosed) return
            val live = decodeWorker
            if (live != null && live.isAlive) return
            val thread = Thread({ decodeWorkerLoop() }, DECODE_THREAD_NAME).apply {
                isDaemon = true
                // 与旧预取线程同档：后台补解不许和 VM 线程抢 CPU（真机 VM 链本来就是瓶颈）。
                priority = Thread.MIN_PRIORITY
            }
            decodeWorker = thread
            thread.start()
        }
    }

    /**
     * 解码线程体：取一条、解一条，空闲 [DECODE_KEEPALIVE_MS] 就摘牌退场。
     *
     * 每条请求**必定**过 [DecodeQueue.complete]（`finally`）：在途台账少摘一次，那个名字就
     * 永远排不进去（旧实现靠 `AbortPolicy` 抛异常来摘标记，抛漏一次是永久哑火）。
     */
    private fun decodeWorkerLoop() {
        while (true) {
            val resource = decodeQueue.poll(DECODE_KEEPALIVE_MS)
            if (resource == null) {
                if (decodeQueue.isClosed) return
                synchronized(workerLock) {
                    // 队列里还有货（摘牌与入队撞车）就继续干，别把活留下没人接。
                    if (decodeQueue.queuedCount == 0 && !decodeClosed) {
                        if (decodeWorker === Thread.currentThread()) decodeWorker = null
                        return
                    }
                }
                continue
            }
            try {
                runDecodeTask(resource)
            } finally {
                decodeQueue.complete(resource)
            }
        }
    }

    /**
     * 槽线程体：再查一次缓存（排队期间别处可能已经解好了），未命中才整文件解码并登记，
     * 解出来的一张要经 [cacheFile] 进**既有**的缓存口径（不立第二张表），随后通知订阅方。
     *
     * 只碰文件层（[files] 的按名解码 + [cacheFile] 的登记，按预算路由进
     * [fileCache] / [sheetCache]，两表内部 synchronized）；不读任何 `GraphicsBuffer`，
     * 也不进 [frameOf] —— 那条路才碰 sprite。登记用 [cacheFile]：与 UI 线程并发解
     * 同一个名字时，后到的那份由它回收（见 [cacheFile] 的 KDoc）。
     *
     * 异常整段兜住：解码槽是 best-effort，失败（含 OOM）只让这张图退回「缺图降级 +
     * 下一帧重排」，绝不让线程带死（线程带死 = 之后所有未命中的图都永远解不出来）。
     */
    private fun runDecodeTask(resource: String) {
        try {
            if (cachedFile(resource) != null) return
            // 解不出来时 decodeFileResource 内部已经把名字记进 missingFiles（负缓存口径不变）。
            if (decodeFileResource(resource) == null) return
            notifyDecodeReady(resource)
        } catch (t: Throwable) {
            Log.w(TAG, "解码槽失败 $resource: ${t.javaClass.simpleName}")
        }
    }

    /**
     * 解码就绪的通知：**整段投回主线程**，绝不从解码线程直接调订阅方（硬约束）。
     *
     * [mainHandler] 绑的是主 Looper，`post` 之后订阅方（`CanvasConsoleView` 的行级失效口）
     * 落在 UI 线程上才碰 View。桩环境（纯 JVM 单测）下 `post` 返回缺省值、不执行 ——
     * 所以本口的行为面只能由源码面钉子（见 SpriteBitmapSourceDecodeSlotTest），
     * 真机读数登记在交付 TODO(verify)。
     */
    private fun notifyDecodeReady(resource: String) {
        val listener = decodeReadyListener ?: return
        runCatching {
            mainHandler.post { runCatching { listener(resource) } }
        }
    }

    /**
     * 解码槽的诊断读数（取证/单测用，不参与任何判式）：
     * 「排在队里的名字数 + 在途名数 + 被容量拒掉的请求数」。
     *
     * 与 [cacheBytes] 同一档：只在诊断口调，低频；三个读数都取自 [decodeQueue] 的内部锁快照。
     */
    internal fun decodeSlotReadout(): Triple<Int, Int, Long> =
        Triple(decodeQueue.queuedCount, decodeQueue.inFlightCount, decodeQueue.droppedCount)

    /** 上一次「按名解码」跑在哪条线程上（诊断读数；null = 本会话还没解过）。见 [decodeFileResource]。 */
    @Volatile
    private var lastDecodeThreadSample: String? = null

    /** 诊断读数用：最近一次按名解码所在线程名（证明未命中路径不在调用线程解码）。 */
    internal fun lastDecodeThreadName(): String? = lastDecodeThreadSample

    /** 诊断读数用：[missingFiles] 当前的条数（负缓存是否被异步路径如实登记）。 */
    internal fun missingFileCount(): Int = missingFiles.size

    /**
     * 收掉解码槽（线程 + 队列 + 在途台账）与两张负缓存，并排空工作表的延迟回收队列
     * （进程退出 / QUIT / 换代时由宿主调用），**幂等**。
     *
     * [DecodeQueue.close] 会把排在队里的名字整表丢掉并唤醒阻塞中的线程，正在跑的那一次解码
     * 由 `interrupt` 打断（口径同旧 `shutdownNow`）；被打断的那张图最多让下一帧重排一次。
     * 收口之后再调 [prefetchAsync] / [frameOf] 未命中支会被队列按「已收口」拒收并静默丢弃
     * （[decodeClosed] 之后不起新线程），所以本方法不需要额外的判式分支。
     *
     * [decodeReadyListener] 一起摘：那条 lambda 强持 View（生产接线是
     * `CanvasConsoleView` 的失效口），留着就等于让已结束的会话把一整棵视图树钉在
     * [mainHandler] 的队列上 —— 与 `CanvasConsoleView.releaseSession` 的「切断重引用」同一纪律。
     *
     * ## 回收队列的收口（2026-10-30 稳定性审计 #4）
     *
     * 旧写法只收线程池：`sheetCache` 与它的 [sheetRecycle] 原样留着，而队列**强引用**着
     * 逐出的位图 —— `DeferredRecycle.drainAll` 此前没有生产调用方（只有单测），
     * `bitmapOf` 的主线程 drain 路径在 close 之后也不会再被走到 ⇒ 会话一换，那些
     * 122 MiB 级的 native 位图被队列一路钉到进程结束。现在 close 里整表作废
     * （条目经 `entryRemoved` 入队）并把队列交给主线程收干（线程纪律见
     * [drainSheetRecycleOnClose]）。
     */
    fun close() {
        val worker = synchronized(workerLock) {
            decodeClosed = true
            decodeQueue.close()
            val w = decodeWorker
            decodeWorker = null
            w
        }
        runCatching { worker?.interrupt() }
        decodeReadyListener = null
        missingFiles.clear()
        missingPaths.clear()
        runCatching { sheetCache.evictAll() }
        drainSheetRecycleOnClose()
    }

    /**
     * 投递到主线程的回收口：[close] 可能在 VM/装配/meta 线程上跑（`GameHost` 的收口与
     * 装配竞态），而 `Bitmap.recycle()` 必须与绘制同线程串行（见 [drainSheetRecycle]）。
     * `Looper.getMainLooper()` 在任意线程取都安全，构造期拿也没有问题。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 会话收口时把 [sheetRecycle] 排干，且**不绕过宽限期**。
     *
     * 线程口径：不在主线程就整段 post 回主线程，绝不从后台线程 `recycle()`。
     * 分两步：
     * 1. 先走既有 [drainSheetRecycle] —— 等够 [RECYCLE_GRACE_MS] 的当场回收；
     * 2. 还有剩（多半是 `sheetCache.evictAll` 刚入队的）就延后一个宽限期，用
     *    [DeferredRecycle.drainAllAndRecycle] 整段收干：那一刻距入队至少已过
     *    [RECYCLE_GRACE_MS]，在途帧早已提交，回收与「下一次 drain」等价且不崩。
     *
     * 延后期到点前宿主若已被 GC，postDelayed 的闭包仍持有本实例，队列不会漏收；
     * 收完即释放对位图的强引用（[DeferredRecycle.drainAll] 清空队列）。
     */
    private fun drainSheetRecycleOnClose() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { drainSheetRecycleOnClose() }
            return
        }
        drainSheetRecycle()
        if (sheetRecycle.size > 0) {
            mainHandler.postDelayed({
                sheetRecycle.drainAllAndRecycle(::recycleSheetBitmap)
            }, RECYCLE_GRACE_MS)
        }
    }

    /**
     * 按资源名取一帧（[ImageFrameSource] 的实现口）：句柄就是这张 `Bitmap`，
     * 几何是 sprite 在拼图里的子矩形与 `DestBaseSize`。
     *
     * 动画 sprite 每调一次就现读一次 [clockMs]，所以同一资源在连续两个渲染帧里可能给到
     * 不同子矩形（同一张拼图位图、不同 `srcX/srcY`）—— 这正是原版逐帧换图的效果，
     * 位图本身不重建（[bitmapOf] 按缓冲 id 缓存）。
     */
    override fun frame(resource: String): ImageFrame? {
        // 逐出队列的驱动点放在**这里**，不放在 bitmapOf（2026-10-31）。
        // bitmapOf 只服务 sprite 分支，而「纯文件图」的场景（标题页 `<img>`、整屏背景）
        // 一帧都不经过它 ⇒ sheetCache 逐出的 122 MiB 级工作表会一直挂在回收队列里等
        // 一个永远不来的 drain。frame() 是绘制侧唯一的取图口，屏上只要有图就每帧都走。
        drainSheetRecycle()
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
     * 按 gid 取一帧（[ImageFrameSource.frameByGid] 的实现口）：像素在 core 的
     * [GraphicsPool] 里，句柄是 [bitmapOf] 产出的那张 `Bitmap`，几何按缓冲自身尺寸给。
     *
     * ## 为什么走 [bitmapOf] 而不另开一条像素路
     *
     * [bitmapOf] 已经是「缓冲 → 位图」的唯一实现：`fileSource` 快路（`GCREATEFROMFILE` /
     * CSV 父图**不解 `pixels`**，见 :911 一带）、降级中的缓存复用、OOM 兜底、`bufferCache`
     * 的 LRU 预算与逐出，全在那一处。gid 路只多一个查表入口，不重建任何缓存/预算口径。
     *
     * 像素也不进 `CbgLayerSnapshot`、不为它注册同名 sprite（理由见
     * [ImageFrameSource.frameByGid] 的契约）：两条路都会把 MB 级像素送进 L3 StateHash 链。
     *
     * ## 失败回落（一律 null ⇒ 绘制侧不画，与接入前行为一致）
     *
     * - `gid` 不存在：`GraphicsPool.peek` **只查不建**（`get` 会惰性登记空条目，见
     *   `GraphicsPool.get` 的 KDoc）—— 一次取图不会为 gid 长出条目；
     * - 条目在但未 `GCREATE` / 已 `GDISPOSE`：`width`/`height` 有一侧 <= 0；
     * - 像素拿不到：延迟解码失败、文件源解不出来、[bitmapOf] 的 OOM 兜底。
     *
     * 线程口径与 [frame] 相同：读 `aux` 与 [bitmapOf] 都发生在绘制线程（类 KDoc 的三线程约定）。
     *
     * 几何口径：`srcW/srcH` 与 `destW/destH` 都取缓冲的 `width`/`height`（= core 快照的
     * `widthPx`/`heightPx`）。`fileSource` 解出的位图可能比登记尺寸小（见 [spriteFrame] 同款
     * 注释），源矩形由绘制侧的 `GraphicGeometry.sourceRectClipped` 对实际位图求交 —— 与
     * 按名路同一道安规。
     *
     * @param gid 图形缓冲编号；< 0 一律 null（`CbgLayerSnapshot.gid` 用 -1 表示「无 gid」）
     */
    override fun frameByGid(gid: Int): ImageFrame? {
        if (gid < 0) return null
        val buffer = aux.peek<GraphicsPool>(SLOT_GRAPHICS)?.peek(gid) ?: return null
        val w = buffer.width
        val h = buffer.height
        // 未创建 / 已 dispose：surface 整代置 null ⇒ 尺寸归零（GraphicsBuffer.isCreated 同源判据）。
        if (w <= 0 || h <= 0) return null
        // 生产恒 null ⇒ 走 bitmapOf；单测注入见构造参数 gidFrameHandle 的 KDoc。
        val injected = gidFrameHandle
        val handle: Any? = if (injected != null) injected(buffer) else bitmapOf(buffer)
        if (handle == null) return null
        return ImageFrame(
            handle = handle,
            srcX = 0,
            srcY = 0,
            srcW = w,
            srcH = h,
            destW = w,
            destH = h,
        )
    }

    /**
     * 内部帧形态（位图 + 子矩形 + 目标尺寸）。
     *
     * 文件层**先查缓存**：原版这张名字表是常驻字典（spec: `AppContents.cs:39-51`），
     * 取图不产生任何 IO；HTML 的 `<img>` 与 `PRINT_IMG` 每帧都会走到这里
     * （CanvasConsoleView.kt:319、:436），若每次先 `decodeByResourceName` 整张解码、
     * 再在 [cacheFile] 里把新位图丢掉，就是「每帧每图数十 MB 分配 + 旧位图不回收」的 OOM 路径。
     * 命中缓存直接返回（已回收的条目按未命中处理）。
     *
     * **未命中不在调用线程解码**（本波 FEEL1-L1 的处置刀，见类 KDoc 同名段）：把名字排进
     * [decodeQueue] 就返回 null —— 调用线程（`onDraw` 那条）在这里花的只是一次查表 +
     * 一次入队，不再是「一张 122 MiB 工作表、几百毫秒」。返回值 null 与今天「解不出来」
     * 走的是同一条缺图降级支（绘制侧按资源名算尺寸、不依赖像素，版面几何一字不动）；
     * 解好之后由 [decodeReadyListener] 补一次行级重画。
     */
    fun frameOf(resource: String): Frame? {
        if (resource.isEmpty()) return null
        val sprite = aux.peek<SpritePool>(SLOT_SPRITE)?.find(resource)
        if (sprite != null) return spriteFrame(sprite)
        val bitmap = cachedFile(resource)
        if (bitmap == null) {
            requestFileDecode(resource)
            return null
        }
        return Frame(bitmap, 0, 0, bitmap.width, bitmap.height, 0, 0)
    }

    /**
     * 未命中支的唯一动作：把「按名解码一次」排进解码槽（结果只进诊断读数，不改返回值）。
     *
     * 单独抽一个函数只为把「这里不解码」这件事写成**一个可被源码面钉子钉住的形状**：
     * 本函数体里出现 `decodeByResourceName` / `BitmapFactory` 就是回退成同步解码。
     */
    private fun requestFileDecode(resource: String) {
        submitDecodeRequest(resource)
    }

    /**
     * 解码槽线程体用的按名解码一次，**失败会被记进 [missingFiles]**（负缓存口径一字未动）。
     *
     * 与旧写法 `cachedFile(...) ?: files.decodeByResourceName(...)?.let { cacheFile(...) }`
     * 的差别只有一处：解不出来时记一笔负缓存，于是同一个名字在 [FailedLookupCache]
     * 的有效期内不再碰磁盘 —— 现在这条判式在**调用线程**上先过一遍（[submitDecodeRequest]），
     * 免得一个永远解不出来的名字每帧排一次队。
     *
     * 取图计时只包 `decodeByResourceName` 那一步（[logSlowDecode]，纯取证日志、零行为）：
     * `now` 仍是**解码之前**的读数并原样交给 `markFailed`，负缓存的 TTL 口径一字未动。
     * 本函数跑在解码槽线程上，`onMainThread()` 为 false ⇒ 取证行天然静默；**留着它**有两个理由：
     * 一是「两条同步解码路都要覆盖」的钉子（SpriteBitmapSourceSlowDecodeTest）钉的就是这个字面串，
     * 二是这条通道若将来被别处（主线程）复用，判式会立刻如实报出来。
     */
    private fun decodeFileResource(resource: String): Bitmap? {
        val now = clockMs()
        if (missingFiles.shouldSkip(resource, now)) return null
        lastDecodeThreadSample = Thread.currentThread().name
        val decoded = files.decodeByResourceName(resource)
        logSlowDecode("资源名 $resource", now, clockMs(), onMainThread())
        if (decoded == null) {
            missingFiles.markFailed(resource, now)
            return null
        }
        return cacheFile(resource, decoded)
    }

    /**
     * 本线程是不是主线程（= UI 线程，`onDraw` 的那条）。
     *
     * 只有主线程上的同步解码才是玩家嘴里的「点了一下没反应」；VM 线程与预取线程慢是
     * 提前量问题，与 ANR 无关，报出来只会把取证线糊成一片（判据见 [logSlowDecode]）。
     * `Looper` 两个读数在任意线程都取得到，非 Android 运行期（纯 JVM 桩）返回缺省值时
     * 两侧同为 null ⇒ 判成「不是主线程」，也就是**什么都不报** —— 单测因此不会被日志干扰。
     */
    private fun onMainThread(): Boolean =
        runCatching { Looper.myLooper() === Looper.getMainLooper() }.getOrDefault(false)

    /**
     * 主线程同步解码的慢样本一行 logcat（**纯取证、零行为**：2026-11-02 EP1 波 L4）。
     *
     * 补的是 BOARD 在册缺口 ⑦ 那条唯一没入库的取证补丁。要读的现场是玩家那句
     * 「点去白玉楼时候未响应」那一类 ANR：`onDraw` 里的取图链（[frameOf] → [decodeFileResource]、
     * [bitmapOf] 的 fileSource 支）在缓存**未命中**时是**主线程同步解码**，一张 8000x4000 的
     * webp 解成 ARGB 就是 122 MiB（8000×4000×4 = 128,000,000 B，见 [SHEET_CACHE_BUDGET_BYTES]
     * 的推导），而工作表一旦撞不进 [sheetCache] 预算就会**每帧重解**（[sheetBytes] KDoc 记过
     * 那条退化）。此前这条链在盘上、日志里都**一个字都不留** ⇒ 现场只剩「没响应」三个字。
     *
     * 纪律：
     * - **不改**任何缓存/解码路径，不改返回值，不新增分配以外的动作（一次时钟读 + 一次判定）；
     * - 整段 `runCatching`：埋点把游戏搞崩是本末倒置（与 app 侧 `RunWatch` 同一口径）；
     * - 门槛、只报主线程、同键冷却三条判式都抽在 [shouldLogSlowDecode]（纯函数，JVM 直测）。
     */
    private fun logSlowDecode(label: String, startedAtMs: Long, endedAtMs: Long, onMain: Boolean) {
        runCatching {
            val elapsed = endedAtMs - startedAtMs
            val sinceLastMs = if (lastSlowDecodeAtMs < 0L) -1L else endedAtMs - lastSlowDecodeAtMs
            if (!shouldLogSlowDecode(onMain, elapsed, lastSlowDecodeKey == label, sinceLastMs)) return
            lastSlowDecodeKey = label
            lastSlowDecodeAtMs = endedAtMs
            Log.i(TAG, "主线程同步解码慢：$label 用 ${elapsed}ms（阈值 ${SLOW_DECODE_LOG_MS}ms）——" +
                "这条只取证，不改解码与缓存行为")
        }
    }

    /** 上一次记行的键与时刻（**同一张图**在 [SLOW_DECODE_LOG_COOLDOWN_MS] 内只记一行；见 [logSlowDecode]）。 */
    @Volatile
    private var lastSlowDecodeKey: String? = null

    @Volatile
    private var lastSlowDecodeAtMs = -1L

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

    /** DevTools img 命令：文件层定位（sprite 表 miss 时的兜底文件）。 */
    fun locateFileForDebug(resource: String) = files.locateForDebug(resource)

    /** 换代（读档 / RESTART / `SPRITECREATE` 覆盖）后作废全部缓存。 */
    fun invalidate() {
        bufferCache.evictAll()
        fileCache.evictAll()
        sheetCache.evictAll()
        demotedCache.evictAll()
        // 负缓存一起清：换代（读档 / RESTART / SPRITECREATE 覆盖）之后资源集合本来就
        // 要重新认识，留着「上次查不到」的记录只会让新一局的头几帧少图。
        missingFiles.clear()
        missingPaths.clear()
        // 起点也一起清：原版换图/读档后是新的 SpriteAnime（StartTime 归零），动画从头播。
        synchronized(animeLock) { animeClocks.clear() }
    }

    /**
     * 交还绘制层缓存占的像素内存：清空 [bufferCache]、[fileCache] 与 [sheetCache]，
     * **不动** [animeClocks]。
     *
     * 与 [invalidate] 的区别只在时钟：动画帧号是「起点 + 宿主时钟」的函数
     * （[SpriteAnimeClock]，见类 KDoc 的动画段），清掉起点会让在播的动画从头播一遍，
     * 这是脚本可见的语义变化；缓存只是加速层，清掉后下一帧按需重建位图，帧号不变。
     *
     * ## 四张表的回收口径
     *
     * - [bufferCache] / [fileCache]：只**解除引用**、不 `recycle` —— 条目小（MB 级），
     *   正被上一次 [Frame] 当作 handle 画着的位图仍可画，等引用清空后由 GC 归还像素内存。
     * - [sheetCache]（2026-10-30 稳定性审计 #3(a) 新增）：条目是 122 MiB 级的 **native**
     *   工作表位图，靠 GC 还太晚。`evictAll` 触发 `entryRemoved` ⇒ 条目进 [sheetRecycle]，
     *   由既有的「宽限期 + 主线程」纪律真正 `recycle()`（见 [drainSheetRecycle]）——
     *   **不**当场回收，绝不回收 RenderNode 可能还持有引用的位图。以前 `onTrimMemory`
     *   够不到这张最大的表，512 MiB 的 native 位在内存压力下纹丝不动。
     * - [demotedCache]（2026-11-08）：条目同为 MB 级 native 位图，挂 [sheetRecycle]
     *   同一条队列（见该缓存的 KDoc）。
     *
     * 线程口径：`LruCache.evictAll` 内部 synchronized（类 KDoc 的线程约定），
     * UI 线程与 VM 线程同时调用都安全。各缓存分次清理、不构成原子快照：并发取图可能在
     * 前一个 `evictAll` 之后又登记一条，留到下一次 [trimCaches] 再收 —— 不影响正确性，
     * 只是本函数的读数（[cacheBytes]）在并发下可能略大于 0。
     */
    fun trimCaches() {
        bufferCache.evictAll()
        fileCache.evictAll()
        sheetCache.evictAll()
        demotedCache.evictAll()
    }

    /**
     * 四个位图缓存当前占用的字节数之和（诊断读数）。
     *
     * `LruCache.size()` 是各条目 `sizeOf` 之和，即已用字节（不是条目数）；
     * `size()` 自身在缓存内部锁里读，跨线程调用安全。只供诊断，调用频率低。
     *
     * 2026-10-30 审计 #3(b) 起**含** [sheetCache]：以前 `bitmapCache=` 遥测列只报两张小表，
     * 最大的那坨 native 位图（预算最高旧值 256 MiB）不进读数，玩家上报里看不出内存去哪了。
     * 2026-11-08 起再**含** [demotedCache]（降级位图，预算随池像素预算 128 MiB 级）：
     * 「降级 native 取图」这条新链的驻留若不进读数，崩报里又是一笔算不清的账。
     * 四表预算之和见 [defaultNativeCacheBudgetBytes]，远小于 `Int.MAX_VALUE`，求和不溢出。
     */
    fun cacheBytes(): Int =
        bufferCache.size() + fileCache.size() + sheetCache.size() + demotedCache.size()

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
            // 帧矩形是入表时按**当时**的父图尺寸校验的（`SPRITEANIMEADDFRAME`），之后同 gid 的
            // `GCREATE` 会把父图换小、`fileSource` 位图也可能比登记尺寸小 —— 这时 f 的源矩形
            // 会越出位图。原版走 GDI+，越界源矩形被静默裁掉；安卓 `Canvas.drawBitmap` 对
            // 越界源矩形抛 IllegalArgumentException，一条「帧先入表、父图后重建」的旧动画
            // 就能崩掉 UI 线程。这里裁成与位图的交集，空交集 = 这一帧不画（GDI+ 同口径）。
            val src = clampFrameSrc(f.srcX, f.srcY, f.srcW, f.srcH, bitmap.width, bitmap.height)
                ?: return null
            // 帧内偏移随帧走：`AnimeFrame.Offset`（SpriteAnime.cs:20，写入点
            // GraphicsMethods 的 SPRITEANIMEADDFRAME 第 7/8 参）。绘制侧按
            // SpriteAnime.cs:153-154 把它叠到 DestBasePosition 上，丢掉就是整段动画偏移。
            return Frame(
                bitmap, src[0], src[1], src[2], src[3],
                sprite.width, sprite.height,
                sprite.posX, sprite.posY, f.offsetX, f.offsetY,
                anime = true,
            )
        }
        val buffer = sprite.buffer ?: return null
        // **超大 CSV 父图：整张解不出来时按 sprite 矩形区域解码兜底**（2026-11-06 布娃娃复盘）。
        //
        // 原版父图整张常驻（`AppContents.cs:273` 一次 `LoadImage`），PC 上无所谓；移植侧整张
        // 解成 Bitmap 是 164 MiB native（本语料 `汎用モブ子dairi版.webp`），
        // 直接 `<img src='那张表里的 sprite'>` 上屏就得把整张抬进 native 堆。
        //
        // 顺序刻意是「先原路径、失败再兜底」：122 MiB 的立绘工作表整张解码一直是通的，
        // 那条路径一个字节行为都不许变（玩家天天看）；只有整张确实解不出来（内存/解码器拒绝）
        // 的超大父图才走区域解码。语义不变 —— `Frame` 的 src 矩形从整张图坐标改成区域内
        // (0,0)，绘制口径逐字一致。
        val bitmap = bitmapOf(buffer)
        if (bitmap == null) {
            regionBitmapOf(buffer, sprite)?.let { region ->
                return Frame(
                    region, 0, 0, region.width, region.height,
                    sprite.width, sprite.height, sprite.posX, sprite.posY,
                )
            }
            return null
        }
        val x = sprite.srcX.coerceIn(0, maxOf(0, bitmap.width - 1))
        val y = sprite.srcY.coerceIn(0, maxOf(0, bitmap.height - 1))
        val w = sprite.srcW.coerceIn(1, bitmap.width - x)
        val h = sprite.srcH.coerceIn(1, bitmap.height - y)
        // 静态 sprite 的 `DestBasePosition`（ASpriteSingle.cs:79-85 的位移项）。
        return Frame(bitmap, x, y, w, h, sprite.width, sprite.height, sprite.posX, sprite.posY)
    }

    /**
     * 这张缓冲能否按区域解码兜底：CSV 父图（有区域源）且整张 ARGB 超 [REGION_DECODE_THRESHOLD_BYTES]。
     *
     * 阈值 96 MiB：语料里超它的父图只有那张 164.4 MiB 的布娃娃拼图（4.981 全量扫过），
     * 其余立绘表连这条判据都不会进。像素字节用 Long 算，避免被改坏的 CSV
     * （100000x100000）在 Int 上溢出成小数。
     */
    private fun shouldRegionDecode(buffer: GraphicsBuffer): Boolean =
        regionDecodeNeeded(buffer.regionSource != null, buffer.width, buffer.height)

    /**
     * 按 sprite 的 `SrcRectangle` 取一块位图（区域解码），键 = 路径 + 矩形。
     *
     * 走 core 的 [GraphicsBuffer.regionPixels]（宿主侧 `BitmapRegionDecoder`，见
     * `ResourceCsvHost.decodeRegion`），这里再转成 Bitmap 落 [regionBitmaps]（原生表，16 MiB）。
     * 拿不到就返回 null ⇒ 调用方退回整张 [bitmapOf] 路径，与改前一致。
     */
    private fun regionBitmapOf(buffer: GraphicsBuffer, sprite: SpritePool.Sprite): Bitmap? {
        if (!shouldRegionDecode(buffer)) return null
        val path = buffer.fileSource ?: return null
        val sx = sprite.srcX
        val sy = sprite.srcY
        val sw = sprite.srcW.coerceAtLeast(1)
        val sh = sprite.srcH.coerceAtLeast(1)
        if (sx < 0 || sy < 0 || sx + sw > buffer.width || sy + sh > buffer.height) return null
        val key = "$path#$sx,$sy,$sw,$sh"
        regionBitmaps.get(key)?.let { if (!it.isRecycled) return it }
        val pixels = buffer.regionPixels(sx, sy, sw, sh) ?: return null
        if (pixels.size != sw * sh) return null
        val bitmap = runCatching {
            Bitmap.createBitmap(pixels, sw, sh, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        regionBitmaps.put(key, bitmap)
        return bitmap
    }

    /**
     * 把等够宽限期的旧工作表位图真正 `recycle()` 掉。
     *
     * **只在主线程做**：回收动作必须与「谁在用这张位图」在同一线程上串行，而绘制是
     * UI 线程的 `onDraw`；VM 线程（HTML 展平查尺寸）也会走到 [bitmapOf]，那里调
     * `recycle()` 就可能回收掉 UI 线程正在画的图。非主线程直接跳过 —— 队列留着，
     * 下一次 UI 线程取图时再收。
     *
     * 每次取图都调一次是有意的：它只在队列非空且等够时才做实际工作，常态是
     * 一次 `isEmpty` 判断（队列空直接返回）。
     */
    private fun drainSheetRecycle() {
        if (sheetRecycle.size == 0) return
        if (Looper.myLooper() != Looper.getMainLooper()) return
        sheetRecycle.drainDueAndRecycle(SystemClock.elapsedRealtime(), ::recycleSheetBitmap)
    }

    /**
     * 真正把一张出队的位图 `recycle()`：已回收的跳过（同一张绝不回收两次），
     * 单张失败只吞掉这一张、不中断整段排干。只在主线程被调（[drainSheetRecycle] /
     * [drainSheetRecycleOnClose] 两条路都先过了 Looper 判定）。
     */
    private fun recycleSheetBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) runCatching { bitmap.recycle() }
    }

    /** 缓冲 → 位图（按 gid 缓存，数组引用换了才重建）。 */
    private fun bitmapOf(buffer: GraphicsBuffer): Bitmap? {
        drainSheetRecycle()
        // 文件源优先：GCREATEFROMFILE / CSV 父图登记的缓冲**不解 `pixels`**，直接按路径解位图。
        // 这一支是 2026-10-30「点爱抚闪退」的正解 —— 立绘工作表单张 8000x4000，
        // 解成 IntArray 就是 122 MiB 的 Dalvik 堆分配，玩家机 512 MiB 挡一撞就 OOM。
        // 位图落在 native 堆，且这里缓存得住（[sheetCache]），不再每帧重解。
        val sourcePath = buffer.fileSource
        if (sourcePath != null) {
            val cached = sheetCache.get(sourcePath)
            if (cached != null && !cached.isRecycled) return cached
            // 解不出来的路径记一笔负缓存：否则这张工作表每帧都要重走一次
            // `File.isFile` + `decodeFile`（见 FailedLookupCache 的类 KDoc）。
            val now = clockMs()
            if (missingPaths.shouldSkip(sourcePath, now)) return null
            val decoded = files.decodeFileByPath(sourcePath)
            // 立绘工作表这条支路是**单张最大**的同步解码（8000x4000 ⇒ 122 MiB，见类 KDoc），
            // 而且逐出后会每帧重解 ⇒ 取证行必须覆盖它，否则最该看见的那条链反而是哑的。
            logSlowDecode("路径 $sourcePath", now, clockMs(), onMainThread())
            if (decoded == null) {
                missingPaths.markFailed(sourcePath, now)
                return null
            }
            // 超预算的条目不进表：`LruCache.put` 会把它**先入表再当场逐出**，`entryRemoved`
            // 随即把它排进 [sheetRecycle] —— 返回给绘制侧的那张图 1 s 后就会 `recycle()`，
            // 下一帧再画就是 `trying to use a recycled bitmap`（真机崩）；而且它没被缓存住，
            // 本来也是每帧重解。官方 `AppContents` 没有这张表，直接返回解码结果即可
            // （本帧画面不变）。判据见 [fitsSheetCache]。
            if (fitsSheetCache(decoded.byteCount.toLong(), sheetBytes)) {
                sheetCache.put(sourcePath, decoded)
            }
            return decoded
        }
        // CSV 资源表的缓冲是延迟解码的（首像素访问才落地）：先取像素再读尺寸，
        // 并要求 `pixels.length == width * height` —— `Bitmap.createBitmap` 对长度不符
        // 抛的是 IllegalArgumentException（不是 OOM），漏判就是 UI 线程直接崩。
        // 乘法用 Long：CSV 被改坏成 `100000x100000` 时 `w * h` 在 Int 上会溢出成负数/小正数，
        // 有机会与真实长度「碰巧相等」从而放过这道闸（2026-10-31 复查）。
        //
        // 降级无损（2026-11-04 OOM 波）：core 的像素预算闸（GraphicsPool 第二档）会把
        // 「画过的」合成缓冲压成 PNG 闭包（GCREATED/宽高/像素逐字不变，见
        // `GraphicsBuffer.demotePixelsToPng`）。
        //
        // 2026-11-08 `0.2.43 查看其他人的位置` OOM 的正解：**降级缓冲取图绝不借 `pixels` 读口**。
        // `GraphicsBuffer.pixels` 是**有状态读**：一次缓存 miss 就把 PNG 解回 `IntArray(w*h)` 并
        // 发布新代 Surface，1.44 MiB/张重新钉进 Dalvik 堆。一屏几十~上百张 `highres_顔絵_*`
        // 就是 144 MiB 级常驻；而 `QueueInputSink.waitInput` 期间 VM 阻塞在 `Condition.await`，
        // 既没有 G 语句推进预算闸、也没有页中检查点，三档自救全部静默 ⇒ 512 MiB 贴顶，
        // 哨兵线程连 `ResultKt.createFailure` 都分配不出来。渲染是纯消费者，不该改引擎状态。
        //
        // 现在降级代把取图材料经 `GraphicsBuffer.demotedCredential()`（零分配凭据）与
        // `demotedPngBytes()`（字节）交出来（只读、不换代、不动账本），本侧直接
        // `BitmapFactory` 解成 native 位图：Java 堆零像素阵列，降级因此**粘得住**。
        // 凭据身份即代际：写 → 晋升 → 再降级必换凭据对象，旧位图按 `credRef === 当代凭据`
        // 判失效，不会画出上一代的脸。堆内载荷凭据 = 载体本体；堆外载荷（OOM-B
        // PngPayloadStore）凭据 = DecodeClosure 本体，其 readBytes 是一次性拷贝，
        // 只有 miss 帧才该付 —— 命中帧靠凭据口零分配直返。
        val resident = buffer.pixelsIfResident()
        val demotedCred = if (resident == null) buffer.demotedCredential() else null
        if (demotedCred != null) {
            val cachedDemoted = demotedCache.get(buffer.id)
            if (cachedDemoted != null && cachedDemoted.credRef.get() === demotedCred &&
                !cachedDemoted.bitmap.isRecycled &&
                cachedDemoted.bitmap.width == buffer.width && cachedDemoted.bitmap.height == buffer.height
            ) {
                return cachedDemoted.bitmap
            }
            val demotedPng = buffer.demotedPngBytes()
            if (demotedPng == null) {
                // 堆外载荷已被换代口 close（晋升竞态）或读不出来：按既有「缺图」支降级
                // 返回 null，**绝不**回落到 `pixels` 晋升 —— 那条回落正是本波要拆的欠账。
                demotedCache.remove(buffer.id)
                return null
            }
            // `pixelArrayDecodeOptions()` 的 `inPremultiplied = false` 保证解出来的是 PNG 里的
            // **直通** ARGB（与 `GraphicsBuffer` 的 `0xAARRGGBB` 布局逐字相同）；随后的
            // `setPremultiplied(true)` 只翻 alpha 元数据、不动像素 —— 与常规支
            // `Bitmap.createBitmap(IntArray, ...)` 的产物（直通值 + premul 标志）逐字同构，
            // 降级前后画面不许变暗/变亮。缺失这一步会让半透明层在降级瞬间跳色。
            val bitmap = try {
                BitmapFactory.decodeByteArray(
                    demotedPng, 0, demotedPng.size, BitmapImageDecoder.pixelArrayDecodeOptions(),
                )?.also { it.setPremultiplied(true) }
            } catch (t: OutOfMemoryError) {
                Log.w(TAG, "gid=${buffer.id} 降级 PNG 落 native 位图 OOM，跳过本帧")
                null
            } catch (t: Throwable) {
                Log.w(TAG, "gid=${buffer.id} 降级 PNG 解不出来，跳过本帧")
                null
            }
            if (bitmap == null || bitmap.isRecycled ||
                bitmap.width != buffer.width || bitmap.height != buffer.height
            ) {
                // 载荷损坏或尺寸与缓冲不一致：按既有「缺图」支降级返回 null，**绝不**回落
                // 到 `pixels` 晋升 —— 那条回落正是本波要拆的欠账。
                demotedCache.remove(buffer.id)
                return null
            }
            demotedCache.put(buffer.id, DemotedEntry(bitmap, WeakReference(demotedCred)))
            demotedPngDecodeCount.incrementAndGet()
            return bitmap
        }
        // 像素落地（CSV 父图 / GCREATEFROMFILE 的首访问延迟解码）本身是一笔 **Java 堆** `IntArray(w*h)`：
        // 降级态已在上面那一支被 native 取图截走，这里**只剩非降级缓冲**，不会再有
        // 「渲染把刚压下去的像素解回来」这条回路（2026-11-08）。
        // core 的 `GraphicsBuffer.pixels` 只把「宿主解码器抛的异常」折成 null
        // （`core/host/GraphicsBuffer.kt:250-256` 那支 `catch (t: Throwable)`），而 UI 线程走到
        // 这里时堆已贴顶的那一份（lane oomalloc：0.2.39 上报里 1.44 / 1.0 / 0.5 MiB 一族
        // 就是这种瞬时份）照样能把整局带走。取图口唯一的降级出口就是返回 null
        // （绘制侧按缺图那一支走，版面几何不动），所以在这里兜住它、不上抛。
        val pixels = try {
            resident ?: buffer.pixels
        } catch (t: OutOfMemoryError) {
            Log.w(TAG, "gid=${buffer.id} ${buffer.width}x${buffer.height} 像素落地 OOM，跳过本帧")
            return null
        }
        val w = buffer.width
        val h = buffer.height
        if (w <= 0 || h <= 0 || pixels.size.toLong() != w.toLong() * h.toLong()) {
            // 缓冲已释放（GDISPOSE 把尺寸归零、像素换空数组）或换代到半套状态：顺手摘掉
            // 该 gid 的缓存条目，免得一张已无主的旧位图与旧像素数组被缓存钉住不放。
            // `LruCache.remove` 与 get/put 走同一条内部锁（类 KDoc 的线程约定）；从读尺寸
            // 到 remove 的窗口里别的线程可能刚 put 一份新条目 —— 那份要么对应同一缓冲的新
            // 数组、要么同样过期，摘掉只会让下一帧按需重建，不会画出错图。
            bufferCache.remove(buffer.id)
            return null
        }
        val cached = bufferCache.get(buffer.id)
        // 命中条件 = 「同一份像素数组」**且**「同一写代数」：数组引用只表达换代（新 Surface），
        // 表达不了原地重写 —— `GCREATE` 一次 + 每帧 `GDRAWSPRITE` 重画的合成画布，引用不变、
        // 内容每帧都变（`GraphicsBuffer.pixelRevision` 的 KDoc 记了真机现象）。
        if (cached != null && cached.pixelsRef.get() === pixels && cached.revision == buffer.pixelRevision &&
            !cached.bitmap.isRecycled
        ) { return cached.bitmap }
        // **合成进行中先用旧位图顶着**（2026-11-06 性能）：一屏立绘是 20+ 笔 `GDRAWSPRITE`，
        // 每笔都推进写代数。若每次查出「代数变了」就重建 850x1280 位图（4.3 MiB 分配 + 逐像素
        // 拷贝），一屏合成期间 UI 会重建十几次 —— 纯浪费，还把 VM 线程的合成拖慢（玩家看到的
        // 就是「点一下要等好几秒」）。距上一笔写还不到 [REBUILD_SETTLE_MS] 时先沿用旧位图，并置
        // [hasPendingRebuild] 让绘制侧继续排帧；等这波写空闲下来再重建一次。
        if (cached != null && cached.pixelsRef.get() === pixels && !cached.bitmap.isRecycled &&
            // 尺寸必须与当前缓冲一致：`GCREATE` 换成别的尺寸后，旧位图拿来当渲染源会让
            // 绘制侧的 src 矩形越界（换代既换像素数组也换宽高，这里必须一起校验）。
            cached.bitmap.width == w && cached.bitmap.height == h &&
            buffer.msSinceLastWrite() < REBUILD_SETTLE_MS
        ) {
            pendingRebuilds.incrementAndGet()
            return cached.bitmap
        }
        if (pendingRebuilds.get() > 0) pendingRebuilds.decrementAndGet()
        val bitmap = try {
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (t: OutOfMemoryError) {
            Log.w(TAG, "gid=${buffer.id} ${w}x${h} 建位图 OOM，跳过")
            return null
        }
        bufferCache.put(buffer.id, BitmapEntry(bitmap, WeakReference(pixels), bitmap.byteCount, buffer.pixelRevision))
        // 记一次「按新写代数重建」：绘制侧据此再排一帧（见 [cacheRebuilds] 的 KDoc）。
        rebuildCount++
        return bitmap
    }

    /**
     * 文件图缓存命中：先查 [fileCache]（小图），再查 [sheetCache]（超预算按名大图，
     * 见 [cacheFile] 的路由）。已回收的条目按未命中处理（顺手摘掉，免得 [frameOf]
     * 每帧都读到它）。这张位图可能被上一次 [Frame] 的 handle 引用着，回收方在本类之外，
     * 所以这里只判不回收。
     */
    private fun cachedFile(resource: String): Bitmap? {
        fileCache.get(resource)?.let { cached ->
            if (!cached.isRecycled) return cached
            // 已回收条目顺手摘掉；**不**在此返回 null —— 同名键还可能挂在 sheetCache
            // （小图经 bitmapOf 的路径支进 sheetCache、又按名进 fileCache，见 cacheFile 路由）。
            fileCache.remove(resource)
        }
        sheetCache.get(resource)?.let { cached ->
            if (!cached.isRecycled) return cached
            // sheetCache 的 entryRemoved 会把旧条目排进延迟回收；已回收的加不进去，无需处理。
            sheetCache.remove(resource)
        }
        return null
    }

    /**
     * 登记一张刚解码的位图；同名字已有一份活的位图时**保留命中者、回收本次这份**。
     *
     * ## 路由（feat-memory-image-cache：fileCache 超预算条目守卫）
     *
     * `LruCache.put` 对**单条大于 maxSize** 的条目是「先入表、再当场逐出」。fileCache
     * 没有守卫的旧口径下，语料里 29 张 ARGB 16–64 MiB 的图装不进 16 MiB 预算 —— 每帧
     * 都「解码 → 入表 → 当场逐出 → 下帧再解码」，解码永不命中还白付每帧一张位图。
     * 现在按 [fitsFileCache] 分流：
     *
     * - 装得进 [fileCache]：照旧入表。
     * - 装不进、但装得进 [sheetCache]（[fitsSheetCache]）：按**资源名**键挂 sheetCache ——
     *   预算 192 MiB 装得住语料全部按名图（按名通道被 [GameImageFiles] 的 maxSide=4096
     *   闸住，单张至多 4096×4096×4B = 64 MiB），还享受它的 [DeferredRecycle] 宽限回收。
     *   两侧读口 [cachedFile] 两表同查，命中链不断。
     * - 两边都装不下（只有 decodeFileByPath 无尺寸闸的工作表通道可能造出这种条目）：
     *   不进任何表，直接返回 —— 与 [bitmapOf] fileSource 支的既有口径一致，本帧可用、
     *   下一帧重解，绝不「先入表再当场逐出」。
     *
     * sheetCache 支的同键替换语义与 fileCache 支不同，是各表回收纪律的差异使然：
     * fileCache 的条目靠 GC 归还像素，竞态败者**当场** `recycle`（它从未交给绘制）；
     * sheetCache 的 `entryRemoved` 走 [DeferredRecycle]，被替换下来的旧图可能正被
     * 本帧的 Canvas 引用（与 [bitmapOf] fileSource 支的既有行为同口径），宽限期兜住。
     *
     * 原版的名字表是常驻字典（spec: `AppContents.cs:39-51`），同一名字换图只在
     * `LoadContents` 重新载入时发生。本端走到这里时 [cachedFile] 已判过未命中，
     * 因此 `put` 返回非空只意味着**竞态**：另一线程在这两步之间登记了同名字的位图。
     * 这正是早先「先解码再进缓存、发现已有缓存就把新位图丢掉」那条泄漏的收口：
     * 4.3 MB 的拼图每丢一次就是一次 OOM 的分子。
     */
    private fun cacheFile(resource: String, bitmap: Bitmap): Bitmap {
        val bytes = bitmap.byteCount.toLong()
        if (fitsFileCache(bytes, fileCacheBudgetBytes)) {
            val previous = fileCache.put(resource, bitmap)
            if (previous != null && previous !== bitmap && !previous.isRecycled) {
                fileCache.put(resource, previous)
                runCatching { bitmap.recycle() }
                return previous
            }
            return bitmap
        }
        if (fitsSheetCache(bytes, sheetBytes)) {
            sheetCache.put(resource, bitmap)
            return bitmap
        }
        return bitmap
    }

    /**
     * [ImageFrameSource.cacheRebuilds] 的计数：每次「写代数变了 ⇒ 位图重建」+1。
     *
     * 绘制侧靠它决定要不要再排一帧：UI 线程可能在脚本合成到一半时截图（取到半成品位图），
     * 而控制台帧发布之后若没有新输入，就不会再有下一帧来纠正 —— 屏幕上就永远停着那半张图。
     */
    @Volatile
    private var rebuildCount: Long = 0L

    override val cacheRebuilds: Long get() = rebuildCount

    /**
     * 降级缓冲被渲染侧**直接解成 native 位图**的次数（2026-11-08 取证读数）。
     *
     * 每一笔都等价于「少了一次 `GraphicsBuffer.pixels` 透明晋升 ⇒ 少了一份 1.44 MiB 级
     * Java 堆 `IntArray` 常驻」。崩报里非 0 就说明修复那条路真的在跑、屏也没画缺；恒 0 且
     * 报告仍贴顶，则要回头查是不是有别处还在借 `pixels` 读口。只读计数，失败路径调用安全。
     * 用原子量而不是 `@Volatile Long`：VM 线程与 UI 线程都会走到 [bitmapOf]。
     */
    private val demotedPngDecodeCount = java.util.concurrent.atomic.AtomicLong(0L)

    /** [demotedPngDecodeCount] 的只读出口，供 `GameHost.graphicsPoolReportLine` 并进崩报。 */
    fun demotedPngFrames(): Long = demotedPngDecodeCount.get()

    /**
     * 「有一笔位图重建被稳定期推迟了」：绘制侧据此继续排帧，直到这波图形写空闲下来。
     *
     * 与 [cacheRebuilds] 分工：那个是「已经重建完了，画面该重画」，这个是「还没重建，等它」。
     * 只置位不重建不会让绘制侧空转成死循环 —— 写空闲后的第一帧就会重建并把标志清掉。
     */
    /**
     * 被稳定期推迟的重建**笔数**（计数而不是布尔：同一个 source 服务多块缓冲，
     * 布尔会被「另一块缓冲刚重建完」清掉，把还欠着的那块永远留在旧图上）。
     */
    private val pendingRebuilds = java.util.concurrent.atomic.AtomicInteger(0)

    override val hasPendingRebuild: Boolean get() = pendingRebuilds.get() > 0

    /**
     * 缓存里的一份位图 + 它是从哪个像素数组来的（弱引用比较 = 换代检测）。
     *
     * 为什么必须是**弱**引用：这份 [IntArray] 的所有者是 core 的
     * [GraphicsBuffer.surface]，缓存只是拿它当身份凭据，不该越权延长它的生命周期。
     * 从前它是强引用，于是 `demotePixelsToPng` / `dispose` 把像素从 core 换掉之后，
     * 一次「降级」在 Java 堆里反而可能同时留下旧数组（被这里钉住）与 PNG 载荷，
     * 而池级账本显示 0 —— 这是长时间游玩 OOM 的第二个出口。
     *
     * 弱引用被 GC 清掉时凭据失配 ⇒ 下一帧按新数组重建位图，脚本语义零变化；
     * 缓冲本身还强引用着当代像素时它不会被清，稳态命中率不受影响。
     */
    private class BitmapEntry(
        val bitmap: Bitmap,
        val pixelsRef: WeakReference<IntArray>,
        val bytes: Int,
        /** 建位图时缓冲的写代数（[GraphicsBuffer.pixelRevision]）；原地重写靠它失效。 */
        val revision: Int = 0,
    )

    companion object {
        /**
         * `store.aux` 的 sprite 槽名。
         *
         * 现在**只有一个来源**：core 的 [SPRITE_SLOT]。早先这里是「按同名约定复述一份」
         * 的字面量 `"sprite"` —— 那种约定一改就静默错位（回收扫到空表、取图拿不到池），
         * 而两边都编译得过。依赖方向是 `platform -> core-vm`，所以常量只能放 core 侧。
         */
        const val SLOT_SPRITE = SPRITE_SLOT

        /**
         * `store.aux` 的图形缓冲池槽名（core 的 [GRAPHICS_SLOT]，理由同 [SLOT_SPRITE]：
         * 常量单一来源，改约定不会静默错位）。gid 路取图用。
         */
        const val SLOT_GRAPHICS = GRAPHICS_SLOT

        /**
         * 工作表位图「逐出到回收」的宽限期（毫秒）：见 [sheetCache] 与 [DeferredRecycle]。
         *
         * 1 s 的依据：`onDraw` 一帧的绘制是毫秒级，硬件加速的 RenderNode 引用活到帧提交为止；
         * 1 s 足够翻完三缓冲。取更短（比如 16 ms）就等于赌「一帧内一定画完」，
         * 赌输的代价是 `trying to use a recycled bitmap` 直接崩。
         */
        const val RECYCLE_GRACE_MS = 1_000L

        /**
         * 父图**整张** ARGB 超过这个字节数才改走区域解码（[regionDecodeNeeded]）：
         * 语料里只有 4.981 那张 164.4 MiB 的布娃娃拼图越线，其余立绘表全部沿用原路径
         * （整张 + [sheetCache]），行为零变化。
         *
         * `internal` 只为桌面用例能点名这条线（阈值本体不动；生产调用面仍是 [shouldRegionDecode]）。
         */
        internal const val REGION_DECODE_THRESHOLD_BYTES = 96L * 1024L * 1024L

        /**
         * [shouldRegionDecode] 的**纯判式本体**（读口在桌面用例里可直调，见
         * `SpriteBitmapSourceRegionDecodeTest`）：判据逐字等价于拆分前的两行 ——
         * 先看有没有区域源（没有就一个字节都不算），再把整张 ARGB 字节数按 Long 算出来，**严格大于**
         * 阈值才算越线。
         *
         * 全程 Long 的理由同 `PixelPreflight.pixelBytes`：被改坏的表头（100000x100000）在 Int 上会
         * 绕成小正数，于是「这张图很小」的谎报会把该走区域解码的那张送回整张解码。非正尺寸折成 0 B
         * ⇒ 判「不越线」—— 与拆分前的算式同值（`0 > 阈值` 为假）。
         *
         * @param hasRegionSource 缓冲是否带区域解码源（CSV 父图那一档）
         * @param width 父图宽（像素）
         * @param height 父图高（像素）
         */
        internal fun regionDecodeNeeded(hasRegionSource: Boolean, width: Int, height: Int): Boolean {
            if (!hasRegionSource) return false
            val bytes = width.toLong() * height.toLong() * 4L
            return bytes > REGION_DECODE_THRESHOLD_BYTES
        }

        /**
         * 位图重建的「写空闲」门槛（毫秒）：距最近一笔图形写不足这个时长就先不重建。
         *
         * 取 16 ms（约一帧）：一屏 20+ 层合成期间 UI 不至于每帧重建一次 4.3 MiB 位图；
         * 合成一旦停顿到一帧以上就立刻重建，玩家感知不到延迟。
         */
        private const val REBUILD_SETTLE_MS = 16L

        /** [regionBitmaps] 的预算：最大单层 850x1280 ≈ 4.3 MiB，够放当前页最常用的那几层。 */
        private const val REGION_BITMAP_CACHE_BYTES = 16 * 1024 * 1024

        /**
         * 工作表位图缓存预算（字节；2026-10-30 稳定性审计 #3(c)）。
         *
         * **为什么不再是 `maxMemory()/2`**：那是 Java 堆数字，而这张表装的是 native 位图 ——
         * 512 MiB 堆的机器一开就是 256 MiB 的 native 额度，且旧 [cacheBytes] 不统计这张表，
         * 堆遥测全程「健康」，native 侧静默膨胀到 PSS 0.4–0.7 GB 被系统杀（玩家收件箱的
         * 静默闪退那条）。预算与堆挡位挂钩本身就是错配。
         *
         * **规则：与 Java 堆无关的文档化常数上限，取 192 MiB**。依据：
         * - 必须容得下语料最大单张：8000×4000×4B = 128,000,000 ≈ 122 MiB。`LruCache`
         *   放不下的条目会被当场逐出 ⇒ 每帧重解（旧 [sheetBytes] KDoc 记过这条退化），
         *   所以下限不是拍脑袋，是「单张最大工作表 + 头部余量」；
         * - 严格低于旧口径的最坏放行（512 MiB 挡的 256 MiB，以及 clamp 顶格 384 MiB），
         *   对 native 侧只会更省；
         * - 内存压力兜底不靠预算自适应，靠 [trimCaches] 现在能整表释放 + [cacheBytes]
         *   进遥测读数。
         *
         * **没选 `Debug.getNativeHeapAllocatedSize()` 余量式**的原因：Android 不暴露进程
         * native 上限，「余量」没有可对的分母；预算是构造期一次性读数，拿开机快照去收缩
         * 反而更不可解释。桌面 JVM 单测里 `Debug` 恒为桩（返回 0），本机无法实测真机
         * native 水位 —— 该取舍的实机读数登记在交付报告 TODO(verify)。
         *
         * 全程 Long 字面量再收窄（192 MiB = 201,326,592 < `Int.MAX_VALUE`，不溢出；
         * 旧口径的「`maxMemory()/2` 溢出成负数」教训已随运行期除法一起废除）。
         */
        internal const val SHEET_CACHE_BUDGET_BYTES: Int = 192 * 1024 * 1024

        /**
         * 放开档（判式两个条件见 `core/host/MemoryTier.kt`：API >= 26 **且** Java 堆 >= 512 MiB）
         * 的工作表缓存预算：**384 MiB**（= 保守档的两倍）。
         *
         * 为什么这一族机型敢给到 384 MiB：这张表装的是 **native** 位图（上面那条 KDoc 记过
         * 拿 Java 堆数字预算 native 位的错配，PSS 0.4–0.7 GB 那笔实测在册），而判式的第二条
         * 又要求这台机的堆挡位 >= 512 MiB —— OEM 按整机内存配这一档，等于同时确认了这是
         * 大内存机。版本线那一条（API >= 26）在本仓只由两个读数支撑、机制尚未一手取证，
         * 逐字理由与 TODO(verify) 见 `core/host/MemoryTier.kt` 的类头，别把这里读成已证事实。
         * 保守档那三条依据（容得下最大单张 122 MiB、严格低于旧口径最坏放行、
         * 兜底靠 [trimCaches] 而不是预算自适应）在 384 MiB 下一条都没被推翻，只是余量翻倍。
         *
         * 与保守档同理由做成**文档化常数**（不做任何运行期除法；384 MiB = 402,653,184 B
         * < `Int.MAX_VALUE`，不溢出）。真正的 native 水位没有可对的分母（Android 不暴露进程
         * native 上限），所以这里只抬账面预算，压力兜底照旧走 [trimCaches] 整表释放 +
         * 报告里的 PSS 分项；**放开档在真机上的 PSS 分布尚未取证**，登记在交付报告
         * TODO(verify)。
         */
        internal const val SHEET_CACHE_BUDGET_BYTES_GENEROUS: Int = 384 * 1024 * 1024

        /**
         * 本档生效的工作表缓存预算（构造期一次现取，见 [sheetBytes]，不在绘制热路径上）：
         * 放开档读 [SHEET_CACHE_BUDGET_BYTES_GENEROUS]，其余（含没落性质的桌面装置与单测）读
         * [SHEET_CACHE_BUDGET_BYTES]，与旧写法逐字相同。
         *
         * public 的唯一理由：app 侧进程启动那行档位取证要读**同一个数**（不许另立一份预算读数，
         * 两个常数两份算式就是下一次「账面与实际不一致」的源头）；本模块的单测仍直读那两个
         * internal 常数。
         *
         * `maxMemoryBytes` 只用来**判档**（[com.eratw.port.core.host.MemoryTier] 的第二条线），
         * 不参与预算算术 —— 本函数仍在两个文档化常数之间做**选取**，不做任何运行期除法。
         * 缺省 = 本进程现取；显式传它只为定向单测在不自控测试 JVM 挡位的前提下复刻两档、
         * 并钉住「本函数确实查了档位」这条接线，生产不传。
         */
        fun defaultSheetCacheBytes(maxMemoryBytes: Long = Runtime.getRuntime().maxMemory()): Int =
            if (MemoryTier.isGenerous(maxMemoryBytes)) {
                SHEET_CACHE_BUDGET_BYTES_GENEROUS
            } else {
                SHEET_CACHE_BUDGET_BYTES
            }

        /**
         * 降级位图缓存的预算（字节，[demotedCache] 用）。
         *
         * **为什么从 GraphicsPool 的像素预算派生，而不是另拍一个常数**：降级位图是 core
         * 降级载荷（`GraphicsBuffer.demotedPngBytes`）的渲染像，一张位图的 `byteCount`
         * 恒等于该缓冲的原始像素字节（PNG 只是把同一批像素压小了）。也就是说「降级位图
         * 全集」的上界 = 池里被降级那批缓冲的像素字节总量，而池自己就是拿同一条
         * `maxMemory ÷ PIXEL_BUDGET_DIVISOR` 当预算闸的 —— 同分母读数保证**一屏头像工作集
         * 基本装得下**（0.2.43 `查看其他人的位置` 那屏 ≈ 100 × 1.44 MiB，512 MiB 挡预算
         * 128 MiB ⇒ 极端情况下也只有最旧的零头被逐出重解，不再是整屏逐帧重解），
         * 且总量与 core 侧记账同源，native 侧不会比 Java 堆侧更宽。
         *
         * 与 [defaultSheetCacheBytes] 的「两常数选取」不同：这条**做除法**（复用 core 的
         * [GraphicsPool.defaultPixelBudgetBytes]，`Int` 溢出由 `coerceAtMost` 挡住 ——
         * 堆 ≥ 8 GiB 的装置上 maxMemory/4 才可能越界，且 2026-11-08 起该算式与池共享一份）。
         */
        fun defaultDemotedCacheBytes(maxMemoryBytes: Long = Runtime.getRuntime().maxMemory()): Int =
            GraphicsPool.defaultPixelBudgetBytes(maxMemoryBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        /**
         * 四张 native 缓存表的**账面预算之和**（字节，报告取证用）：bufferCache = 构造缺省
         * `DEFAULT_CACHE_BYTES`（32 MiB）、fileCache 为其半（16 MiB）、工作表缓存
         * [defaultSheetCacheBytes]（保守 192 / 放开 384 MiB）、降级位图缓存
         * [defaultDemotedCacheBytes]（512 MiB 堆 = 128 MiB，随池像素预算同分母）。
         *
         * 旧报告写死「32 + 16 + 192 = 240 MiB」，那是放开档之前那份常数快照：工作表缓存翻到
         * 384 后，写死 240 会把真机读数 309 MiB（实际 < 432 MiB）误报成「超预算」，把排查
         * 往错方向带（2026-11-04 报告 004311 就是这形状）。降级位图缓存入账后同理 ——
         * 预算与读数只许这一份算式。
         */
        fun defaultNativeCacheBudgetBytes(maxMemoryBytes: Long = Runtime.getRuntime().maxMemory()): Long =
            DEFAULT_CACHE_BYTES.toLong() * 3L / 2L + defaultSheetCacheBytes(maxMemoryBytes) +
                defaultDemotedCacheBytes(maxMemoryBytes)

        /**
         * 「主线程同步解码慢到多少毫秒才记一行 logcat」的门槛（BOARD 在册缺口 ⑦ 那条取证补丁）。
         *
         * **取值理由（不是拍脑袋，四条读数夹出这一段）**：
         * - 下界：一帧预算 16.7 ms（60 Hz Choreographer），一次小图解码 + 缓存登记本来就在
         *   几毫秒到几十毫秒；门槛低于 100 ms 就会把「正常取图」也报出来；
         * - 上界：玩家报的是 ANR 那一类（「点去白玉楼时候未响应」），而本仓「卡死」的下沿取
         *   5 000 ms（app 侧 `RunTrail.LONG_TURN_MS`，`RunWatch.kt:517`）——门槛真取到秒级
         *   就只能记到「已经卡死了」，读不出是哪张图把主线程拖进去的；
         * - 形状：语料最大的单张工作表是 `8000x4000` 的 webp，解成 ARGB 是
         *   8000×4000×4 = 128,000,000 B ≈ **122 MiB**（推导与本文件 [SHEET_CACHE_BUDGET_BYTES]
         *   同源：:167 与 :723 那两处记的就是这一张），中端机上 `BitmapFactory` 解这个量级
         *   是几百毫秒级 ⇒ 门槛要落在「明显高于小图抖动、又远低于 ANR」的位置；
         * - **200 ms**（≈12 帧）正是 BOARD 判词里那份作废草稿用的数，沿用它有两个理由：
         *   端点侧与前一路的人工读报口径可比；且它是上面三条夹出来的**唯一**整数位。
         *
         * `TODO(verify)`：真机不在场（`adb devices` 空），200 ms 在装机上会命中多少行**没有读数**。
         */
        internal const val SLOW_DECODE_LOG_MS = 200L

        /**
         * 同一条解码键两次记行的最小间隔（毫秒）。
         *
         * 为什么必须有冷却：这张表的退化形状正是「逐出 ⇒ **每帧**重解同一张图」
         * （[sheetBytes] 与 [sheetCache] 的 KDoc 记过那条），没有冷却就是每秒 60 行 logcat，
         * 把取证线糊成噪声。取 5 s 与 app 侧「长页钉发冷却」同一量级
         * （`RunTrail.LONG_TURN_PIN_COOLDOWN_MS`，`RunWatch.kt:520`；跨模块只作口径引用，
         * platform 不依赖 app）：让「还在慢、慢到第几秒」在日志里是一条斜线而不是一个点。
         */
        internal const val SLOW_DECODE_LOG_COOLDOWN_MS = 5_000L

        /**
         * 慢解码取证行的纯判式（[logSlowDecode] 的全部条件，抽出来只为可 JVM 直测）。
         *
         * 三条各管一件事，缺一就变形：
         * - **只报主线程**（[onMain]）：VM 线程/预取线程慢是提前量问题，与「点了没反应」无关；
         * - **够慢才报**（[elapsedMs] >= [SLOW_DECODE_LOG_MS]）：常态路径零日志；
         * - **同键带冷却**（[sameKeyAsLast] + [sinceLastLogMs]）：每帧重解那条形要压成每 5 秒一行。
         * 不同键之间**不互相压制** —— 一屏两张大图都在慢是两件事，得各记一行。
         *
         * @param sinceLastLogMs 同键距上一次记行的毫秒；负数 = 本进程还没记过（首条必走）
         */
        internal fun shouldLogSlowDecode(
            onMain: Boolean,
            elapsedMs: Long,
            sameKeyAsLast: Boolean,
            sinceLastLogMs: Long,
        ): Boolean {
            if (!onMain) return false
            if (elapsedMs < SLOW_DECODE_LOG_MS) return false
            if (sameKeyAsLast && sinceLastLogMs in 0L until SLOW_DECODE_LOG_COOLDOWN_MS) return false
            return true
        }

        /**
         * 缓存未命中时「这一步该做什么」的纯判式（[submitDecodeRequest] / [DecodeQueue.offer]
         * 的全部条件，抽出来只为可 JVM 直测 —— 口径同 [shouldLogSlowDecode]）。
         *
         * 三条闸的**优先序是判式的一部分**，换序就是改行为：
         * - **负缓存最前**（[negativeSkipped]）：一个 [FailedLookupCache.DEFAULT_TTL_MS] 内
         *   已经解不出来的名字，既不碰磁盘也不排队 —— 排队等于给一条注定失败的路占一个坑，
         *   还会把在途台账占住、让真正要图的窗口挤不进去（这就是旧实现「每帧重解」的另一半）。
         * - **同名在途其次**（[alreadyQueued]）：同一资源在途只排一次。绘制侧每帧问一次，
         *   没有这一条就是一屏 34 个名字 × 60 帧的重复解码；旧实现靠 `ConcurrentHashSet` 的
         *   `add` 返回值做同一件事，语义原样搬进判式。
         * - **容量闸最后**（[queueHasRoom]）：前两条都过了才轮到占坑；满了按**拒收**
         *   （不是丢最旧，理由见 [DECODE_QUEUE_CAPACITY] 的 KDoc），并由 [DecodeQueue] 计数。
         *
         * 三条都不满足才 [DecodeRequest.Enqueue]。纯函数、无副作用：入队动作在 [DecodeQueue.offer]。
         */
        internal fun planDecodeRequest(
            negativeSkipped: Boolean,
            alreadyQueued: Boolean,
            queueHasRoom: Boolean,
        ): DecodeRequest {
            if (negativeSkipped) return DecodeRequest.NegativeCached
            if (alreadyQueued) return DecodeRequest.AlreadyQueued
            if (!queueHasRoom) return DecodeRequest.Rejected
            return DecodeRequest.Enqueue
        }

        /**
         * 工作表位图是否容得进 [sheetCache]（= 是否允许 `put`）。
         *
         * `LruCache.put` 对**单条大于 maxSize** 的条目是「先入表、再当场逐出」：逐出时
         * `entryRemoved(newValue = null)` 把它排进 [DeferredRecycle]，[RECYCLE_GRACE_MS]
         * 之后在主线程 `recycle()`。于是调用方拿到的正是那张「1 s 后会被回收」的位图：
         * 下一帧 Canvas 再画它就是 `trying to use a recycled bitmap` 直接崩，而且因为没缓存住，
         * 每帧还会重解一次（[bitmapOf] 的 fileSource 支）。`byteCount <= 0` 同样按不入表处理
         * （桩环境与异常位图不占预算）。纯 JVM 可测：只做算术，不碰 Bitmap/LruCache。
         *
         * 边界：`byteCount == budgetBytes` 允许（LruCache 的裁剪条件是 `size > maxSize`）；
         * 预算侧用 Long 比较，`byteCount` 来自 `Bitmap.byteCount` 的 Int 也不会在收窄时翻转。
         */
        internal fun fitsSheetCache(byteCount: Long, budgetBytes: Int): Boolean =
            byteCount > 0L && byteCount <= budgetBytes.toLong()

        /**
         * 按名位图是否容得进 [fileCache]（= 是否允许 `put`，[cacheFile] 的路由判据）。
         *
         * 判据与 [fitsSheetCache] 同形：`byteCount <= 0` 不入表（桩环境与异常位图不占预算），
         * 边界 `byteCount == budgetBytes` 允许（LruCache 的裁剪条件是 `size > maxSize`）。
         * 单独起名而不与 sheet 版合并，是为了两处调用点的 KDoc 各自指向自己的判据 ——
         * 两张表的预算语义不同（fileCache 条目靠 GC、sheetCache 条目靠延迟回收），将来
         * 若一侧要加额外条件（如 fileCache 加条目数上限）不会牵连另一侧。
         */
        internal fun fitsFileCache(byteCount: Long, budgetBytes: Int): Boolean =
            byteCount > 0L && byteCount <= budgetBytes.toLong()

        /**
         * 动画帧源矩形与位图求交：返回 `(x, y, w, h)`，空交集返回 null（这一帧不画）。
         *
         * 与 GDI+ `DrawImage` 的越界口径一致：源矩形超出的部分被静默裁掉，完全落在位图外
         * 则整帧不画（`SpriteAnime.cs:144` 把 `SrcRectangle` 交给 GDI+，反编译里没有判界线）。
         * 合法帧（`SPRITEANIMEADDFRAME` 入表时按父图像素尺寸校验过）返回值逐项不变；
         * 只有「帧先入表、同 gid 的父图随后被 `GCREATE` 换小」或「解码位图比登记尺寸小」
         * 这类换代窗口才会真裁。中间量升 Long：`srcX + srcW` 在 Int 上会回绕，回绕后
         * 可能落回位图内、把一个越界帧当成合法帧。
         *
         * 纯 JVM 可测：只做算术，不碰 Bitmap；由 [spriteFrame] 的动画支调用。
         */
        internal fun clampFrameSrc(
            srcX: Int,
            srcY: Int,
            srcW: Int,
            srcH: Int,
            bitmapW: Int,
            bitmapH: Int,
        ): IntArray? {
            if (srcW <= 0 || srcH <= 0 || bitmapW <= 0 || bitmapH <= 0) return null
            val x = maxOf(srcX, 0)
            val y = maxOf(srcY, 0)
            val right = minOf(srcX.toLong() + srcW.toLong(), bitmapW.toLong())
            val bottom = minOf(srcY.toLong() + srcH.toLong(), bitmapH.toLong())
            val w = right - x
            val h = bottom - y
            if (w <= 0L || h <= 0L) return null
            return intArrayOf(x, y, w.toInt(), h.toInt())
        }

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

        /**
         * 解码槽的队列容量（**张**，写死的常数；有界性硬约束见本波契约 §0 第 10 条）。
         *
         * **64 的算术**（不是拍脑袋）：一屏的图请求上界 = 「一屏可见行数」而不是「语料图片数」，
         * 因为队列里只可能同时存在**当前屏上问过的名字**（其余名字没人问、命中或不命中都不排队）：
         * - 一页可见行真机读数 28~34 行（`CanvasConsoleView.bandMaxHeightPx` 的内容高份额口径），
         *   每行最多一段内联图 ⇒ 上界约 34 张；
         * - 最坏形状是标题图那种**竖带合并块**：eratw 标题图 35 条带合成一块
         *   （`CanvasConsoleView.drawBandStack` 的 KDoc 记的就是这 35 片），一条带就是一个名字，
         *   一次排版能问出 35 个 ⇒ 已经贴着 34 的线上；
         * - 再叠一张立绘 + 背景层的余量 ⇒ 取整到 2 的幂、给约 1.8 倍余量 = **64**。
         *
         * 上界的意义不在「够不够装」，而在**装不满**：队满了按拒收计（[planDecodeRequest] 的
         * [DecodeRequest.Rejected]），而绘制侧每帧都会重新问一次，被拒的名字下一帧自然重排 ⇒
         * 溢出只表现为「这张图晚几帧出现」，不表现为「永远不出现」，也不表现为内存上涨。
         * 反面无界的代价本仓有过前科（BOARD 在册的 `findings.jsonl` 涨到 42 MB）。
         *
         * 与旧口径同值：旧预取线程池的 `ArrayBlockingQueue(PREFETCH_QUEUE_CAPACITY)` 也是 64，
         * 本次把两条来路（预取 / 未命中补解）并进这一条队列，**没有新增第二张表**，
         * 上界仍是同一个 64，只是现在它可数、可测。
         */
        internal const val DECODE_QUEUE_CAPACITY = 64

        /** 解码线程空闲存活时长：过了就摘牌退场，下一批请求再起一条（口径同旧预取线程）。 */
        private const val DECODE_KEEPALIVE_MS = 1_000L

        private const val DECODE_THREAD_NAME = "eratw-img-decode"

        private const val TAG = "eratw-image"
    }
}

/**
 * 缓存未命中时对一个资源名做的事（[SpriteBitmapSource.planDecodeRequest] 的返回值）。
 *
 * 四条都**不抛、不阻塞、不在调用线程解码**，区别只在「有没有占一个坑」与「要不要计数」：
 * - [Enqueue]：入队，随后由解码槽线程解一次并经 `cacheFile` 登记进既有缓存；
 * - [NegativeCached]：[FailedLookupCache] 的有效期内，直接当「没有这张图」，连队都不排
 *   （这就是旧实现「每帧重解一张解不出来的图」那条主线程开销的出口）；
 * - [AlreadyQueued]：同名已在途（排队中或正在解），去重丢弃；
 * - [Rejected]：队列已满，拒收并计入 [DecodeQueue.droppedCount]。
 */
internal enum class DecodeRequest { Enqueue, NegativeCached, AlreadyQueued, Rejected }

/**
 * 解码槽的**有界**队列 + 在途台账（纯 JVM：不认识 `Bitmap` / `Handler` / `Thread`，
 * 因此容量、去重、丢弃计数都能在桌面单测里直接驱动，口径同 [DeferredRecycle]）。
 *
 * ## 为什么自建而不用线程池
 *
 * 旧写法是 `ThreadPoolExecutor(1, 1, ArrayBlockingQueue(64), AbortPolicy)` 加一只并发集合，
 * 队列长度与在途名分属两处、满时靠抛异常静默丢，**丢了多少没有读数**——而「永远解不完」与
 * 「解得慢」在日志里长得一模一样（本波要结清的正是这条取证空白）。本类把两件事合到一把锁里：
 * 键空间恒等于在途名（`queued ⊆ inFlight`），判式 [SpriteBitmapSource.planDecodeRequest] 纯函数化，
 * 每一次拒收都计数。
 *
 * ## 有界性（本波契约 §0 第 10 条）
 *
 * [keys] 的长度上界 = [capacity]（入队前过容量闸，满了直接拒收并计数），[inFlight] 的上界
 * = [capacity] + 1（从 [keys] 取出、正在解的那一条**已离开 keys** 但仍在途，去重不许出现
 * 空窗；容量闸只看 [keys] 的长度，所以「64 条排着 + 1 条在解」是允许的最坏形状），
 * 每条在途名都恰有一条队列条目或一次在跑的解码与之对应，[complete] 在 `finally` 里摘，
 * 于是**不存在第三个会涨的容器**。取值理由见
 * [SpriteBitmapSource.DECODE_QUEUE_CAPACITY] 的 KDoc（一屏可见行 28~34 与标题图 35 条带夹出来的 64）。
 *
 * ## 线程与锁
 *
 * 全部动作在一把私有 [ReentrantLock] 里串行；[poll] 用 [Condition.await] 挂起而不是自旋。
 * 这里**没有**用 `synchronized` + `Object.wait`：`Any` 在 Kotlin 里不暴露 `wait`/`notifyAll`，
 * 编译期就过不去。API 面只用 `ArrayDeque` / `HashSet` / `ReentrantLock`：本模块 `minSdk = 21`，
 * 旧 KDoc 记过 `ConcurrentHashMap.newKeySet()` 是 API 24 才有的那条 `NoSuchMethodError` 前科
 * （2026-10-31 lint），这里干脆不碰任何高版本并发 API。
 * 与 [SpriteBitmapSource] 的 `workerLock` 之间的锁序恒为 `workerLock → 本锁`，反向不嵌套。
 *
 * @param capacity 队列容量（张）；必须为正数，非正数在构造期就拒绝（宁可装配期炸，不要运行期哑）
 */
internal class DecodeQueue(private val capacity: Int) {

    init {
        require(capacity > 0) { "解码槽容量必须是正数，收到 $capacity" }
    }

    private val lock = ReentrantLock()
    private val notEmpty: Condition = lock.newCondition()
    private val keys = ArrayDeque<String>()
    private val inFlight = HashSet<String>()
    private var closed = false

    /** 被容量闸拒掉的请求累计数（诊断读数；[DecodeRequest.Rejected] 的每一条计一次）。 */
    var droppedCount: Long = 0L
        private set

    /**
     * 排一个名字（[SpriteBitmapSource.planDecodeRequest] 的三条闸按序过）。
     *
     * @param negativeSkipped 该名字是否还在 [FailedLookupCache] 的有效期内（调用方读，本类不认识负缓存）
     */
    fun offer(resource: String, negativeSkipped: Boolean): DecodeRequest {
        lock.lock()
        try {
            val outcome = if (closed) {
                // 已收口：与「队列满」同一个出口（拒收 + 计数），调用方按「这一帧没有这张图」降级。
                DecodeRequest.Rejected
            } else {
                SpriteBitmapSource.planDecodeRequest(
                    negativeSkipped,
                    inFlight.contains(resource),
                    keys.size < capacity,
                )
            }
            when (outcome) {
                DecodeRequest.Enqueue -> {
                    inFlight.add(resource)
                    keys.addLast(resource)
                    notEmpty.signalAll()
                }
                DecodeRequest.Rejected -> droppedCount += 1L
                else -> {}
            }
            return outcome
        } finally {
            lock.unlock()
        }
    }

    /**
     * 取下一条待解的名字；没有就最多等 [timeoutMs]，超时或已收口返回 null。
     *
     * 名字**留在 [inFlight] 里**（正在解也算在途，去重不许出现空窗），直到 [complete] 摘掉。
     * 被中断时把中断位交还调用方并返回 null —— 唯一的中断来源是收口路径。
     */
    fun poll(timeoutMs: Long): String? {
        lock.lock()
        try {
            var remainingNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (keys.isEmpty() && !closed) {
                if (remainingNs <= 0L) return null
                try {
                    remainingNs = notEmpty.awaitNanos(remainingNs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return if (keys.isEmpty()) null else keys.removeFirst()
        } finally {
            lock.unlock()
        }
    }

    /** 解完（成功或失败都算解完）：摘掉在途标记，让这个名字将来还能重新排。 */
    fun complete(resource: String) {
        lock.lock()
        try {
            inFlight.remove(resource)
        } finally {
            lock.unlock()
        }
    }

    /** 排在队里的名字数（不含正在解的那一条）。 */
    val queuedCount: Int
        get() {
            lock.lock()
            try {
                return keys.size
            } finally {
                lock.unlock()
            }
        }

    /** 在途名数（排队中 + 正在解），上界 = [capacity]。 */
    val inFlightCount: Int
        get() {
            lock.lock()
            try {
                return inFlight.size
            } finally {
                lock.unlock()
            }
        }

    /** 队列是否已收口（[close] 之后 [poll] 立即返回 null、[offer] 一律拒收）。 */
    val isClosed: Boolean
        get() {
            lock.lock()
            try {
                return closed
            } finally {
                lock.unlock()
            }
        }

    /**
     * 收口：清空队列与在途台账并唤醒等待中的线程（**不**清 [droppedCount]，那是历史读数）。
     * 幂等。收口后本槽不再接受任何请求（[offer] 恒返回 [DecodeRequest.Rejected]）。
     */
    fun close() {
        lock.lock()
        try {
            closed = true
            keys.clear()
            inFlight.clear()
            notEmpty.signalAll()
        } finally {
            lock.unlock()
        }
    }
}
