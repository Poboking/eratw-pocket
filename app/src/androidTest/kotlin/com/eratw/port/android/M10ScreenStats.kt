package com.eratw.port.android

import kotlin.math.max
import kotlin.math.min

/**
 * 一次截图的「屏上不只有文字与底色」统计（M10-CLOUD 取证装置的核心判据）。
 *
 * ## 为什么不是「没崩」而是「有画」
 *
 * 上一轮云真机（M6-DEVICE-RT）只证明了 Robo 5 分钟不崩、截图是演示屏。本类把「截图里必须有
 * 立绘/背景」换成可复算的像素统计，全部在**网格**上做（网格 = 按 [stride] 步长抽样，
 * 一个网格单位等于 [unitPx] 个屏幕像素），因此读数与机型分辨率无关。
 *
 * 六个量各自的作用：
 * - [distinctColors]：量化色（每通道 4 bit，共 4096 桶）的占用桶数。文字屏的色轴只有
 *   「底色 → 字色」几条直线上的过渡灰阶，桶数上不去；照片/绘制的立绘会摊开一大片彩色桶。
 * - [chromaPixelRatio]：`max(r,g,b)` 与 `min(r,g,b)` 之差达到 [CHROMA_MIN] 的像素比例。文字（含描边、
 *   反锯齿）几乎都在低饱和轴上，立绘与背景不是。
 * - [dominantColorRatio]：占比最高的那个色桶盖住全屏的比例。纯文字屏必然被一块平涂底色统治；
 *   这条只是**必要条件**，不单独承担区分（演示屏有底部输入条，底色占比只有 0.7 上下）。
 * - [longestChromaticRunPx]：高饱和像素最长的水平连续段（换算回屏幕像素）。字形的横向笔画
 *   最宽也就一个全角字符的宽度（破折号一类），立绘/背景的连续彩色段是几百像素。
 * - [imageLikeBlobCount] 与 [largestBlobCoverage]：对高饱和掩膜做四邻域连通块标记，
 *   只统计「够大、够实、够多彩」的块（判据见 [isImageLike]）。这是**结构性**判据：
 *   一行彩色标题的字会被连成一串窄条（高度不足 [BLOB_MIN_UNIT_PX]），而立绘是二维色块。
 *
 * 抽样会把细笔画打散成孤点，所以「文字屏」在网格上更不可能长出大色块 —— 抽样方向对本判据有利。
 *
 * ## 阈值从哪里来、怎么复核
 *
 * 默认阈值在 [ArtworkGate.DEFAULT]，全部可以用 `am instrument -e` 覆盖（见 [ArtworkGate.fromArgs]），
 * 组长在真机上不必重新编译就能收紧或放宽。阈值能不能区分「有立绘」和「只有文字」由
 * `M10StatsGateControlTest` 用两张同尺寸的合成图（一张纯文字+按钮、一张同样文字再加一块立绘）
 * 双向钉住：前者必须至少违例四条，后者必须零违例。这条对照用例不依赖游戏产物，任何机器上都能跑。
 */
class ScreenStats private constructor(
    val decodedWidth: Int,
    val decodedHeight: Int,
    val stride: Int,
    val unitPx: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val cells: Int,
    val distinctColors: Int,
    val dominantColorRatio: Float,
    val inkRatio: Float,
    val chromaPixelRatio: Float,
    val longestChromaticRunPx: Int,
    val imageLikeBlobCount: Int,
    val largestBlobCoverage: Float,
    val blobNotes: List<String>,
) {

    /** 逐条判据的可读读数（一行一项，直接进取证文本）。 */
    fun readings(): List<String> = listOf(
        "网格 ${gridWidth}x${gridHeight}（抽样步长 $stride，1 格 = ${unitPx} 屏幕像素，解码尺寸 ${decodedWidth}x${decodedHeight}）",
        "distinctColors=$distinctColors（量化 4bit/通道，满量程 $BUCKET_COUNT）",
        "chromaPixelRatio=${fmt(chromaPixelRatio)}（饱和像素比例，阈值见判据）",
        "dominantColorRatio=${fmt(dominantColorRatio)}（最大色桶占比）",
        "inkRatio=${fmt(inkRatio)}（非最大色桶占比）",
        "longestChromaticRunPx=$longestChromaticRunPx",
        "imageLikeBlobCount=$imageLikeBlobCount largestBlobCoverage=${fmt(largestBlobCoverage)}",
    ) + blobNotes.map { "  blob: $it" }

    override fun toString(): String = readings().joinToString(" | ")

    private class Blob(
        val x: Int,
        val y: Int,
        val wPx: Int,
        val hPx: Int,
        val fill: Float,
        val colors: Int,
        val coverage: Float,
    )

    companion object {
        /** 饱和阈：`max-min` 通道差达到这个数才算「彩色像素」。 */
        const val CHROMA_MIN = 40

        /** 量化位深：每通道 4 bit ⇒ 16 级，共 4096 桶（人眼在缩略图上分不出更细的差别）。 */
        const val BUCKET_BITS = 4
        const val BUCKET_COUNT = 4096

        /** imageLike 色块的尺寸下限（屏幕像素）：一个全角字的高度的好几倍。 */
        const val BLOB_MIN_UNIT_PX = 64

        /** 色块的紧致度：面积/外接矩形，低于它说明是散点而不是一块画。 */
        const val BLOB_MIN_FILL = 0.28f

        /** 色块内至少要有多少种量化色，才像「画」而不是一根纯色条。 */
        const val BLOB_MIN_COLORS = 10

        /** 色块面积占全屏比例的下限。 */
        const val BLOB_MIN_AREA_RATIO = 0.003f

        /** 网格宽度上限：再宽也不细化统计，只为控制耗时与内存。 */
        const val TARGET_GRID_WIDTH = 480

        /**
         * 对 [argb]（ARGB_8888，[width]x[height]）做统计。
         *
         * @param samplePx 解码像素 1 个等于多少**屏幕**像素（截屏解码时的 `inSampleSize` 实测值，
         *   合成对照图传 1）。网格步长由本函数按 [TARGET_GRID_WIDTH] 决定，所以一个网格单位
         *   等于 `samplePx x stride` 个屏幕像素，全部尺寸读数都换算回屏幕像素，
         *   判据才与机型分辨率、解码抽样都无关。
         */
        fun compute(argb: IntArray, width: Int, height: Int, samplePx: Int): ScreenStats {
            require(argb.size == width * height) { "像素数与尺寸不符：${argb.size} vs ${width}x$height" }
            val stride = max(1, (width + TARGET_GRID_WIDTH - 1) / TARGET_GRID_WIDTH)
            val unitPx = max(1, samplePx) * stride
            val gw = (width + stride - 1) / stride
            val gh = (height + stride - 1) / stride
            val n = gw * gh
            val bucket = IntArray(n)
            val chroma = ByteArray(n)
            val counts = IntArray(BUCKET_COUNT)

            var idx = 0
            for (gy in 0 until gh) {
                val rowBase = gy * stride * width
                var gx = 0
                while (gx < gw) {
                    val c = argb[rowBase + gx * stride]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val bk = ((r shr BUCKET_BITS) shl 8) or ((g shr BUCKET_BITS) shl 4) or (b shr BUCKET_BITS)
                    bucket[idx] = bk
                    counts[bk]++
                    if (max(r, max(g, b)) - min(r, min(g, b)) >= CHROMA_MIN) chroma[idx] = 1
                    idx++
                    gx++
                }
            }

            var dominantCount = 0
            var distinct = 0
            for (i in 0 until BUCKET_COUNT) {
                val cnt = counts[i]
                if (cnt > 0) {
                    distinct++
                    if (cnt > dominantCount) {
                        dominantCount = cnt
                    }
                }
            }
            val dominantRatio = if (n == 0) 0f else dominantCount.toFloat() / n
            val inkRatio = if (n == 0) 0f else (n - dominantCount).toFloat() / n
            var chromaCells = 0
            for (i in 0 until n) if (chroma[i] != 0.toByte()) chromaCells++
            val chromaRatio = if (n == 0) 0f else chromaCells.toFloat() / n

            // 最长水平连续高饱和段（按网格计，再换算回屏幕像素）。
            var longestRunCells = 0
            for (gy in 0 until gh) {
                var run = 0
                val base = gy * gw
                for (gx in 0 until gw) {
                    run = if (chroma[base + gx] != 0.toByte()) run + 1 else 0
                    if (run > longestRunCells) longestRunCells = run
                }
            }

            // 四邻域连通块标记（显式栈，不用递归，网格可达 50 万格）。
            val labels = IntArray(n)
            val stack = IntArray(n)
            val seenColor = IntArray(BUCKET_COUNT)
            val blobs = ArrayList<Blob>(8)
            var labelSeq = 0
            var i = 0
            while (i < n) {
                if (chroma[i] == 0.toByte() || labels[i] != 0) {
                    i++
                    continue
                }
                labelSeq++
                var sp = 0
                stack[sp++] = i
                labels[i] = labelSeq
                var minX = i % gw
                var maxX = minX
                var minY = i / gw
                var maxY = minY
                var area = 0
                var colors = 0
                while (sp > 0) {
                    val cell = stack[--sp]
                    val cx = cell % gw
                    val cy = cell / gw
                    area++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    val bk = bucket[cell]
                    if (seenColor[bk] != labelSeq) {
                        seenColor[bk] = labelSeq
                        colors++
                    }
                    if (cx > 0) sp = push(stack, sp, cell - 1, labels, chroma, labelSeq)
                    if (cx + 1 < gw) sp = push(stack, sp, cell + 1, labels, chroma, labelSeq)
                    if (cy > 0) sp = push(stack, sp, cell - gw, labels, chroma, labelSeq)
                    if (cy + 1 < gh) sp = push(stack, sp, cell + gw, labels, chroma, labelSeq)
                }
                val bboxCells = (maxX - minX + 1) * (maxY - minY + 1)
                val wPx = (maxX - minX + 1) * unitPx
                val hPx = (maxY - minY + 1) * unitPx
                val fill = if (bboxCells == 0) 0f else area.toFloat() / bboxCells
                val coverage = if (n == 0) 0f else area.toFloat() / n
                if (isImageLike(wPx, hPx, fill, colors, coverage)) {
                    blobs += Blob(minX * unitPx, minY * unitPx, wPx, hPx, fill, colors, coverage)
                }
                i++
            }

            val largest = blobs.maxByOrNull { it.coverage }
            val notes = blobs.sortedByDescending { it.coverage }.take(4).map {
                "位置(${it.x},${it.y}) 尺寸 ${it.wPx}x${it.hPx} 填充=${fmt(it.fill)} 色数=${it.colors} 占比=${fmt(it.coverage)}"
            }
            return ScreenStats(
                decodedWidth = width,
                decodedHeight = height,
                stride = stride,
                unitPx = unitPx,
                gridWidth = gw,
                gridHeight = gh,
                cells = n,
                distinctColors = distinct,
                dominantColorRatio = dominantRatio,
                inkRatio = inkRatio,
                chromaPixelRatio = chromaRatio,
                longestChromaticRunPx = if (longestRunCells == 0) 0 else (longestRunCells - 1) * unitPx + 1,
                imageLikeBlobCount = blobs.size,
                largestBlobCoverage = largest?.coverage ?: 0f,
                blobNotes = notes,
            )
        }

        /** 色块算不算「画」：够大、够实、够多彩、够占屏（四条同时成立）。 */
        fun isImageLike(wPx: Int, hPx: Int, fill: Float, colors: Int, coverage: Float): Boolean =
            wPx >= BLOB_MIN_UNIT_PX && hPx >= BLOB_MIN_UNIT_PX &&
                fill >= BLOB_MIN_FILL && colors >= BLOB_MIN_COLORS && coverage >= BLOB_MIN_AREA_RATIO

        private fun push(
            stack: IntArray,
            sp: Int,
            cell: Int,
            labels: IntArray,
            chroma: ByteArray,
            labelSeq: Int,
        ): Int {
            if (chroma[cell] == 0.toByte() || labels[cell] != 0) return sp
            labels[cell] = labelSeq
            stack[sp] = cell
            return sp + 1
        }

        private fun fmt(v: Float): String = String.format("%.4f", v)
    }
}

/**
 * 画面判据阈值（全部可被 `am instrument -e` 覆盖，见 [fromArgs]）。
 *
 * 判据是**合取**：六条都要满足才算「屏上有立绘/背景」。缺哪条就在失败信息里点名哪条，
 * 便于组长一眼看出是「根本没图上屏」还是「图上屏了但太小/太暗」。
 */
data class ArtworkGate(
    val minDistinctColors: Int,
    val minChromaPixelRatio: Float,
    val maxDominantColorRatio: Float,
    val minLongestChromaticRunPx: Int,
    val minImageLikeBlobs: Int,
    val minLargestBlobCoverage: Float,
) {
    /** 返回未通过的判据（空表 = 屏上确有画）。 */
    fun violations(stats: ScreenStats): List<String> {
        val out = ArrayList<String>(6)
        if (stats.distinctColors < minDistinctColors) {
            out += "distinctColors=${stats.distinctColors} < $minDistinctColors（色彩种类太少：只有字色与底色的话必然如此）"
        }
        if (stats.chromaPixelRatio < minChromaPixelRatio) {
            out += "chromaPixelRatio=${stats.chromaPixelRatio} < $minChromaPixelRatio（高饱和像素太少：立绘/背景应有成片彩色）"
        }
        if (stats.dominantColorRatio > maxDominantColorRatio) {
            out += "dominantColorRatio=${stats.dominantColorRatio} > $maxDominantColorRatio（整屏被一块平涂色统治）"
        }
        if (stats.longestChromaticRunPx < minLongestChromaticRunPx) {
            out += "longestChromaticRunPx=${stats.longestChromaticRunPx} < $minLongestChromaticRunPx（最长的连续彩色横段还不及一行字里的破折号宽）"
        }
        if (stats.imageLikeBlobCount < minImageLikeBlobs) {
            out += "imageLikeBlobCount=${stats.imageLikeBlobCount} < $minImageLikeBlobs（没有任何「够大够实够多彩」的色块 = 屏上没有图）"
        }
        if (stats.largestBlobCoverage < minLargestBlobCoverage) {
            out += "largestBlobCoverage=${stats.largestBlobCoverage} < $minLargestBlobCoverage（最大色块只占全屏 ${stats.largestBlobCoverage}）"
        }
        return out
    }

    fun describe(): String =
        "minDistinctColors=$minDistinctColors minChromaPixelRatio=$minChromaPixelRatio " +
            "maxDominantColorRatio=$maxDominantColorRatio minLongestChromaticRunPx=$minLongestChromaticRunPx " +
            "minImageLikeBlobs=$minImageLikeBlobs minLargestBlobCoverage=$minLargestBlobCoverage"

    companion object {
        /**
         * 默认阈值。依据是**实测读数**，不是拍脑袋：用 ImageMagick 渲了一张 1080x2400 的
         * 「演示屏近似图」（黑底 + 14 行中英数文 + 彩色标题行含破折号 + 灰底按钮 + 两条深灰输入条
         * + 一枚 30x30 的彩色小图标），再在同一张上叠一块 300x600 的多色「立绘」，
         * 按本类的同一套算法（解码抽样 2、网格步长 2、1 格 = 4 屏幕像素）跑出来的读数：
         *
         * | 读数 | 纯文字 | 文字 + 立绘 | 阈值 |
         * | --- | --- | --- | --- |
         * | distinctColors | 43 | 71 | 48 |
         * | chromaPixelRatio | 0.0022 | 0.0716 | 0.03 |
         * | dominantColorRatio | 0.9092 | 0.8397 | 0.92 |
         * | longestChromaticRunPx | 29 | 297 | 96 |
         * | imageLikeBlobCount | 0 | 1 | 1 |
         * | largestBlobCoverage | 0.0 | 0.0694 | 0.02 |
         *
         * 于是纯文字那张在 **chromaRatio（差 13 倍）、runPx（差 3.3 倍）、blobCount、
         * blobCoverage** 四条上都过不了，`distinctColors` 也差 5 桶。真正承担区分的是后四条：
         * 字号与分辨率都会推高「色彩种类」，而「成片彩色」「连续彩色段」「二维彩色块」是文字
         * 结构上长不出来的东西。所以 [minDistinctColors] 只设到 48（弱必要条件），不敢当主判据。
         *
         * 另两条要点：
         * - dominantColorRatio 在演示屏上是**过的**（0.909 0.92），它只挡「整屏一片纯色」；
         * - 立绘那块只有 1/8 屏（300x600 / 1080x2400）也能把 chromaRatio 拉到 0.0716，
         *   所以阈值 0.03 对应的是「图上屏但只占一小块」这种情况也能过，不会因为构图小而误红。
         *
         * 真机上任一读数贴着阈值时，取证文本里六个数都逐条列着，组长看得见（`-e gate...` 可覆盖）。
         */
        val DEFAULT = ArtworkGate(
            minDistinctColors = 48,
            minChromaPixelRatio = 0.03f,
            maxDominantColorRatio = 0.92f,
            minLongestChromaticRunPx = 96,
            minImageLikeBlobs = 1,
            minLargestBlobCoverage = 0.02f,
        )

        /**  instrumentation 参数覆盖：`-e gateDistinctColors=120 -e gateChromaRatio=0.05 ...` */
        fun fromArgs(get: (String) -> String?): ArtworkGate {
            fun intArg(key: String, def: Int): Int = get(key)?.trim()?.toIntOrNull() ?: def
            fun floatArg(key: String, def: Float): Float = get(key)?.trim()?.toFloatOrNull() ?: def
            return ArtworkGate(
                minDistinctColors = intArg("gateDistinctColors", DEFAULT.minDistinctColors),
                minChromaPixelRatio = floatArg("gateChromaRatio", DEFAULT.minChromaPixelRatio),
                maxDominantColorRatio = floatArg("gateDominantRatio", DEFAULT.maxDominantColorRatio),
                minLongestChromaticRunPx = intArg("gateRunPx", DEFAULT.minLongestChromaticRunPx),
                minImageLikeBlobs = intArg("gateBlobs", DEFAULT.minImageLikeBlobs),
                minLargestBlobCoverage = floatArg("gateBlobCoverage", DEFAULT.minLargestBlobCoverage),
            )
        }
    }
}
