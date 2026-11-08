package com.eratw.port.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * SAF 选中文件的两个读取口（R7-D1 任务②的 app 内公共工具）：显示名与带上限的字节读取。
 *
 * 抽取背景：MOD 导入链（R7-C5 落在 MainActivity 的私有段）与存档导入
 * （[SettingsActivity.importSaves]）此前各持一份私有同形逻辑——
 * `MainActivity.modDisplayNameOf` / `MainActivity.modReadBounded` 与
 * [SettingsActivity.displayNameOf] / [SettingsActivity.readBounded] 的 query 列、
 * 光标判定、回落链、64 KiB 缓冲与「先累加、后判上限」的顺序逐字相同，只差两个参数：
 *
 * * 显示名回落：MOD 导入链取不到时回落 `null`（调用方折成读错误行上屏）；存档导入回落
 *   `SettingsStrings.selectedFileFallback` 的文案。经 [fallback] 参数表达。
 * * 读取上限：MOD 导入链 = [com.eratw.port.platform.storage.ModStore.MAX_MOD_BYTES]
 *   （64 MiB，2026-11 从 32 MiB 抬高——口上 zip 整包要过这道闸）；存档导入 =
 *   [com.eratw.port.platform.save.SaveImport.MAX_SAVE_BYTES]（8 MiB）。经 [maxBytes] 参数表达。
 *
 * 纯核心（[fallbackDisplayName] / [readBoundedBytes]）不依赖 Android 类，JVM 单测对拍
 * 两侧行为；[displayNameOf] / [readBounded] 是 Android 面薄壳（[ContentResolver] 进出），
 * 行为逐字沿用被替代的两份私有实现（R7-D1 交付附对拍表）。
 */
internal object UriNames {

    /**
     * SAF 选中文件的显示名：查询 [DocumentsContract.Document.COLUMN_DISPLAY_NAME] 列，
     * 取不到（无行 / null 值 / 查询抛出）回落 uri 末段（`/` 之后的一段；无分隔符时原样），
     * 再取不到返回 [fallback]（传 null = 允许无回落，MOD 导入链的形态）。
     */
    internal fun displayNameOf(resolver: ContentResolver, uri: Uri, fallback: String?): String? {
        val queried = runCatching {
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried ?: fallbackDisplayName(uri.lastPathSegment, fallback)
    }

    /**
     * 显示名的回落链（[displayNameOf] 的纯核心，单测对拍两侧原实现用）：
     * uri 末段取 `/` 之后的一段（无分隔符原样），末段也没有才回落 [fallback]。
     */
    internal fun fallbackDisplayName(uriLastSegment: String?, fallback: String?): String? =
        uriLastSegment?.substringAfterLast('/') ?: fallback

    /**
     * 带上限的读取：超过 [maxBytes] 立刻收手返回 null（选错的文件不该先把整份吸进堆里）。
     * 打不开 / 读失败返回 null（错误由调用方折成结果行上屏）。
     */
    internal fun readBounded(resolver: ContentResolver, uri: Uri, maxBytes: Long): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { input -> readBoundedBytes(input, maxBytes) }
    }.getOrNull()

    /**
     * 带上限读取的纯核心（[readBounded] 的可测体）：64 KiB 缓冲循环，「先累加、后判上限」
     * —— 累加后超过 [maxBytes] 即放弃整份返回 null（与被替代的两份私有实现逐字同序，
     * 不返回部分数据）。
     */
    internal fun readBoundedBytes(input: InputStream, maxBytes: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}