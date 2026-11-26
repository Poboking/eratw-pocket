package com.eratw.port.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.android.ui.design.ShellShape
import com.eratw.port.android.ui.design.ShellType
import com.eratw.port.core.mod.ModResourceStore
import com.eratw.port.modapi.ModUiElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * mod 浮层图片元素的**解码 / 降级 / 渲染**（RT2 资源注入渲染面）。
 *
 * 三段职责分开，好各自守住自己的失效面：
 *
 *  1. **取字节**：`ModUiSurface.requestImageBytes` 把读取递到 VM 线程（`ModResourceApi` 的
 *     线程契约是「只在 VM 线程调用」；浮层在 Compose 主线程，不能就地去读）；
 *  2. **解码 / 降级**：[ModUiImageLoader] 对**任何**输入（null、空、超大、非图片、坏图、
 *     解码抛异常、解码期 OOM）都只产出 [ModUiImageOutcome.Placeholder]，绝不把异常放回
 *     Compose 主线程 —— 设置页 / 游戏面永远不会因为一张坏图崩掉；
 *  3. **渲染**：[ModUiImageElementView] 在 IO 线程解码（不在主线程做整读/解码），成功上
 *     `Image`，失败上替代文本或占位框；失败原因进 WARN 日志（不进玩家可见文案 —— 技术标识
 *     不是文案，见 AGENTS §6）。
 *
 * 解码器经 [ModUiImageDecoder] 泛型注入：生产是 [BitmapFactoryModUiImageDecoder]；JVM 单测
 * 注入假实现（`app` 的单测环境没有 Robolectric，`android.graphics.Bitmap` 不可实例化），
 * **真 BitmapFactory 那一层留在真机/仪器测试**（TODO(verify)）。
 */

/** 图片降级的失败类别（只进日志与诊断，不上屏为文案）。 */
internal enum class ModUiImageFailure {
    /** 资源面读不到该名字（缺文件 / 名字非法 / 读失败 / 资源面未装配）。 */
    MISSING,

    /** 文件存在但是 0 字节。 */
    EMPTY,

    /** 容器格式不在白名单（PNG / JPEG / WebP）；坏字节在这一步被挡住，不进解码器。 */
    UNSUPPORTED_FORMAT,

    /** 字节数超过资源面单文件上限（读盘后文件变大等）。 */
    TOO_LARGE,

    /** 解码期内存不足（`OutOfMemoryError`，必须当降级处理，不许上抛）。 */
    OUT_OF_MEMORY,

    /** 解码器返回 null 或抛非 OOM 异常（损坏的图片数据）。 */
    DECODE_FAILED,
}

/** 一次图片元素的处理结果：要么可渲染的图，要么替代文本/占位。 */
internal sealed interface ModUiImageOutcome<out B> {
    /** 解码成功；[image] 是解码器给的图（生产 = `Bitmap`）。 */
    data class Ready<B>(val image: B) : ModUiImageOutcome<B>

    /** 降级：渲染 [altText]（空串 = 只渲染占位框），[reason] 只进日志。 */
    data class Placeholder(
        val reason: ModUiImageFailure,
        val altText: String,
    ) : ModUiImageOutcome<Nothing>
}

/**
 * 解码器缝：把「字节 → 图」隔离在一个可替换的点上。
 *
 * 允许抛任何 `Throwable`（含 `OutOfMemoryError`）：[ModUiImageLoader] 统一用 `runCatching`
 * 兜住并降级，所以生产解码器不需要自己写 try/catch。
 */
internal fun interface ModUiImageDecoder<B> {
    /** @return null = 数据不可解码（加载器记 [ModUiImageFailure.DECODE_FAILED] 并降级）。 */
    fun decode(bytes: ByteArray, maxSidePx: Int?): B?
}

/**
 * 图片元素加载器：**只做守卫 + 降级决策**，不做 IO、不碰 Android API（因此 JVM 单测可直测）。
 *
 * 守卫顺序（每步失败即降级，不抛）：
 * 1. `bytes == null` ⇒ [ModUiImageFailure.MISSING]；
 * 2. 空字节 ⇒ [ModUiImageFailure.EMPTY]；
 * 3. 超 [maxBytes]（默认 = 资源面单文件上限，同源 [ModResourceStore.MAX_RESOURCE_FILE_BYTES]）
 *    ⇒ [ModUiImageFailure.TOO_LARGE]；
 * 4. 容器魔数不在 PNG / JPEG / WebP 白名单 ⇒ [ModUiImageFailure.UNSUPPORTED_FORMAT]；
 * 5. 解码抛 `OutOfMemoryError` ⇒ [ModUiImageFailure.OUT_OF_MEMORY]；其余异常 / 返回 null
 *    ⇒ [ModUiImageFailure.DECODE_FAILED]。
 *
 * @param logSink WARN 落点（生产 = 会话 logSink；测试 = 断言用收集器）。
 * @param maxBytes 字节数上限（默认与资源面同源；单测可压低来覆盖 TOO_LARGE 分支）。
 */
internal class ModUiImageLoader<B>(
    private val decoder: ModUiImageDecoder<B>,
    private val logSink: (String) -> Unit = {},
    private val maxBytes: Int = MAX_MOD_UI_IMAGE_BYTES,
) {

    /** 解码一个图片元素；任何失败都返回 [ModUiImageOutcome.Placeholder]，本函数不抛。 */
    fun decode(element: ModUiElement.Image, bytes: ByteArray?): ModUiImageOutcome<B> =
        guardFailure(bytes)?.let { reason -> degrade(element, reason) }
            ?: decodeGuarded(element, bytes ?: EMPTY_BYTES)

    /** 解码前的守卫：返回降级原因；全部通过返回 null（此时 [bytes] 非空）。 */
    private fun guardFailure(bytes: ByteArray?): ModUiImageFailure? = when {
        bytes == null -> ModUiImageFailure.MISSING
        bytes.isEmpty() -> ModUiImageFailure.EMPTY
        bytes.size > maxBytes -> ModUiImageFailure.TOO_LARGE
        !looksLikeSupportedImageContainer(bytes) -> ModUiImageFailure.UNSUPPORTED_FORMAT
        else -> null
    }

    /** 真解码：`runCatching` 兜全 `Throwable`（含 `OutOfMemoryError`），失败只降级。 */
    private fun decodeGuarded(element: ModUiElement.Image, bytes: ByteArray): ModUiImageOutcome<B> {
        val decoded = runCatching { decoder.decode(bytes, element.maxSidePx) }
        return decoded.fold(
            onSuccess = { image ->
                if (image == null) degrade(element, ModUiImageFailure.DECODE_FAILED) else ModUiImageOutcome.Ready(image)
            },
            onFailure = { failure -> degradeForFailure(element, failure) },
        )
    }

    /** 解码抛异常：OOM 单列（必须当降级处理，不许上抛），其余归 [ModUiImageFailure.DECODE_FAILED]。 */
    private fun degradeForFailure(element: ModUiElement.Image, failure: Throwable): ModUiImageOutcome.Placeholder {
        val reason = if (failure is OutOfMemoryError) {
            ModUiImageFailure.OUT_OF_MEMORY
        } else {
            ModUiImageFailure.DECODE_FAILED
        }
        logSink(
            "mod.ui 图片解码失败已降级 name=${element.resourceName} reason=$reason：" +
                "${failure.javaClass.name}: ${failure.message}",
        )
        return ModUiImageOutcome.Placeholder(reason, element.altText)
    }

    private fun degrade(element: ModUiElement.Image, reason: ModUiImageFailure): ModUiImageOutcome.Placeholder {
        logSink("mod.ui 图片已降级为替代文本/占位 name=${element.resourceName} reason=$reason")
        return ModUiImageOutcome.Placeholder(reason, element.altText)
    }
}

/** 图片字节数上限：与资源面单文件上限同源（不是另一个魔数）。 */
internal val MAX_MOD_UI_IMAGE_BYTES: Int = ModResourceStore.MAX_RESOURCE_FILE_BYTES.toInt()

/** 空字节（[ModUiImageLoader.decode] 守卫通过后的占位实参，不会进解码器）。 */
private val EMPTY_BYTES = ByteArray(0)

/**
 * PNG 文件签名（8 字节）：`89 50 4E 47 0D 0A 1A 0A`。
 *
 * 用 ISO-8859-1 字符串而非八个裸字节字面量：每个字节都是有名字的转义，且不给 detekt
 * MagicNumber 留成片魔数。
 */
private val PNG_MAGIC = "\u0089PNG\r\n\u001a\n".toByteArray(Charsets.ISO_8859_1)

/** JPEG 起始标记 `FF D8 FF`。 */
private val JPEG_MAGIC = "\u00ff\u00d8\u00ff".toByteArray(Charsets.ISO_8859_1)

/** RIFF 容器前缀（WebP 的外层）。 */
private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)

/** RIFF 头之后、块类型 `WEBP` 前的 4 字节块长度字段宽度。 */
private const val RIFF_CHUNK_TYPE_OFFSET: Int = 8

/** WebP 的 RIFF 块类型（偏移 [RIFF_CHUNK_TYPE_OFFSET] 起 4 字节）。 */
private val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)

/** 支持的容器白名单：PNG / JPEG / WebP（与类型 KDoc 的承诺一致）。 */
internal fun looksLikeSupportedImageContainer(bytes: ByteArray): Boolean =
    bytes.startsWithMagic(PNG_MAGIC) || bytes.startsWithMagic(JPEG_MAGIC) || isWebp(bytes)

private fun isWebp(bytes: ByteArray): Boolean =
    bytes.startsWithMagic(RIFF_MAGIC) &&
        bytes.size >= RIFF_CHUNK_TYPE_OFFSET + WEBP_MAGIC.size &&
        bytes.copyOfRange(RIFF_CHUNK_TYPE_OFFSET, RIFF_CHUNK_TYPE_OFFSET + WEBP_MAGIC.size)
            .contentEquals(WEBP_MAGIC)

private fun ByteArray.startsWithMagic(magic: ByteArray): Boolean =
    size >= magic.size && copyOfRange(0, magic.size).contentEquals(magic)

/**
 * 采样率决策（纯函数，无 Android API）：`inSampleSize` 取 2 的幂，使解码后最长边落在
 * `[maxSidePx, 2 * maxSidePx)` —— 先粗降一次再精确缩放，避免整张大图先上堆。
 *
 * @return 采样的分母（≥1）；`maxSidePx == null` / 非法 / 尺寸未知时返回 1（不采样）。
 */
internal fun modUiImageSampleSize(width: Int, height: Int, maxSidePx: Int?): Int {
    val limit = maxSidePx?.takeIf { it > 0 } ?: return 1
    val longest = if (width >= height) width else height
    return if (width > 0 && height > 0) sampleSizeFor(longest, limit) else 1
}

private fun sampleSizeFor(longest: Int, limit: Int): Int {
    var sample = 1
    while (longest / (sample * 2) >= limit) {
        sample *= 2
    }
    return sample
}

/**
 * 生产解码器：`BitmapFactory` 两趟解码（先只读边界、再按采样率真解），最后按
 * [ModUiElement.Image.maxSidePx] 精确缩放。
 *
 * 不做自己的 try/catch：加载器统一兜住（含 OOM）。TODO(verify)：BitmapFactory 真实解码、
 * 采样与精确缩放、OOM 降级需真机/仪器测试覆盖 —— JVM 单测环境下 `Bitmap` 不可实例化，
 * 本层只保证编译与结构正确。
 */
internal object BitmapFactoryModUiImageDecoder : ModUiImageDecoder<Bitmap> {

    override fun decode(bytes: ByteArray, maxSidePx: Int?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            decodeSampled(bytes, bounds.outWidth, bounds.outHeight, maxSidePx)
        } else {
            null
        }
    }

    private fun decodeSampled(bytes: ByteArray, width: Int, height: Int, maxSidePx: Int?): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = modUiImageSampleSize(width, height, maxSidePx) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return decoded?.let { bitmap -> maxSidePx?.let { limit -> scaleIfNeeded(bitmap, limit) } ?: bitmap }
    }

    /** 采样后仍超出上限才精确缩放；null 上限 = 不缩放。 */
    private fun scaleIfNeeded(bitmap: Bitmap, limit: Int): Bitmap =
        if (longestSide(bitmap) > limit) scaleToMaxSide(bitmap, limit) else bitmap

    /** 精确缩放到最长边 = [limit]；缩放产物是独立 Bitmap，原图随即回收。 */
    private fun scaleToMaxSide(decoded: Bitmap, limit: Int): Bitmap {
        val longest = longestSide(decoded)
        val ratio = limit.toFloat() / longest
        val targetWidth = (decoded.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (decoded.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun longestSide(bitmap: Bitmap): Int = if (bitmap.width >= bitmap.height) bitmap.width else bitmap.height
}

/**
 * 渲染一个图片元素（浮层专用）。
 *
 * 取字节经 [ModUiSurface.requestImageBytes]（递到 VM 线程，守资源面线程契约），解码在
 * `Dispatchers.IO`（整读 + 解码不占主线程），失败渲染替代文本或占位框。
 *
 * @param packageId 元素归属包（资源面按包隔离）。
 */
@Composable
internal fun ModUiImageElementView(
    element: ModUiElement.Image,
    packageId: String,
    surface: ModUiSurface,
) {
    val loader = remember(surface) {
        ModUiImageLoader(decoder = BitmapFactoryModUiImageDecoder, logSink = surface.logWarn)
    }
    val outcome = remember(element, packageId) { mutableStateOf<ModUiImageOutcome<Bitmap>?>(null) }
    LaunchedEffect(element, packageId, surface) {
        val bytes = surface.awaitImageBytes(packageId, element.resourceName)
        outcome.value = withContext(Dispatchers.IO) { loader.decode(element, bytes) }
    }
    val ready = outcome.value
    if (ready is ModUiImageOutcome.Ready) {
        val maxSide = element.maxSidePx?.let { with(LocalDensity.current) { it.toDp() } } ?: DEFAULT_IMAGE_MAX_SIDE
        Image(
            bitmap = ready.image.asImageBitmap(),
            contentDescription = element.altText.ifEmpty { null },
            contentScale = ContentScale.Fit,
            modifier = Modifier.sizeIn(maxWidth = maxSide, maxHeight = maxSide),
        )
    } else {
        ModUiImagePlaceholder(element.altText)
    }
}

/** 挂起等待资源字节（VM 线程投递、主线程恢复；surface 未装配 / 队列不可用时拿到 null）。 */
private suspend fun ModUiSurface.awaitImageBytes(packageId: String, resourceName: String): ByteArray? =
    suspendCancellableCoroutine { continuation ->
        requestImageBytes(packageId, resourceName) { bytes ->
            if (continuation.isActive) continuation.resume(bytes)
        }
    }

/** 替代文本 / 占位框：技术失败原因不上屏（不是玩家可见文案），只进 WARN 日志。 */
@Composable
private fun ModUiImagePlaceholder(altText: String) {
    Box(
        modifier = Modifier
            .size(PLACEHOLDER_SIDE)
            // 占位框走壳层 token：旧版取 MaterialTheme 的 outline/shapes —— 壳层没有装配 M3 主题，
            // 落到实机上是一圈与壳层无关的默认色。
            .border(BORDER_WIDTH, EratwInk.border(), ShellShape.Inset),
        contentAlignment = Alignment.Center,
    ) {
        if (altText.isNotEmpty()) {
            Text(altText, color = EratwInk.textMuted(), style = ShellType.Label)
        }
    }
}

/** 未声明 `maxSidePx` 时的渲染上限（覆盖层尺寸，不是资源面硬闸）。 */
private val DEFAULT_IMAGE_MAX_SIDE = 512.dp

/** 占位框边长。 */
private val PLACEHOLDER_SIDE = 96.dp

/** 占位框描边宽度。 */
private val BORDER_WIDTH = 1.dp
