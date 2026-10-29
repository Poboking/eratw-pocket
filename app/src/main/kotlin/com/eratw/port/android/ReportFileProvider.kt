package com.eratw.port.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * 报告文件的最小只读 ContentProvider（方案① 报错收集，2026-10-27）。
 *
 * ## 为什么自研而不用 androidx.core.content.FileProvider
 *
 * 壳层依赖表里没有 androidx.core（Compose 是 implementation 作用域，不把它抬到本模块
 * 编译类路径），为一个 Provider 新增依赖还要过一次没有代理缺省的 Gradle 下载；
 * 而分享面板真正用到的面只有 query（拿 `_display_name`/`_size`）+ openFile（只读流），
 * 两条都不到六十行。自研反而把暴露面收死了：
 *
 * - **只读**：`openFile` 只认 `r`，写模式一律拒绝；
 * - **只出报告目录**：请求路径必须在 [ErrorReportCollector.reportDir] 的规范路径之内
 *   （canonical path 前缀比对，挡 `..` 与符号链接逃逸），文件名段由
 *   `Uri.lastPathSegment` 提供，禁止再带子目录；
 * - **只出报告**：文件名必须匹配 `eratw-report-` 前缀，其它 filesDir 内容一律不可达；
 * - **不导出**：manifest 里 `exported=false` + `grantUriPermissions=true`，
 *   只有本应用经 `FLAG_GRANT_READ_URI_PERMISSION` 临时授权的接收方（分享目标）能读。
 */
class ReportFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("报告 Provider 只读：mode=$mode")
        val file = resolve(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = resolve(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        cursor.newRow().apply {
            cols.forEach { col ->
                when (col) {
                    OpenableColumns.DISPLAY_NAME -> add(file.name)
                    OpenableColumns.SIZE -> add(file.length())
                    else -> add(null)
                }
            }
        }
        return cursor
    }

    /** 分享面板按 MIME 过滤候选；报告恒为纯文本。 */
    override fun getType(uri: Uri): String = "text/plain"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("报告 Provider 只读")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("报告 Provider 只读")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("报告 Provider 只读")

    /** 把 content URI 解析回报告目录内的报告文件；越界一律拒绝。 */
    private fun resolve(uri: Uri): File {
        val ctx = context ?: throw IllegalStateException("Provider 未attached")
        val dir = ErrorReportCollector.reportDir(ctx)
        val name = uri.lastPathSegment ?: throw IOException("空路径：$uri")
        if (name.contains('/') || name.contains('\\') || name.contains("..")) {
            throw SecurityException("报告路径只允许单段文件名：$name")
        }
        if (!name.startsWith("eratw-report-") || !name.endsWith(".txt")) {
            throw SecurityException("只允许读错误报告：$name")
        }
        val file = File(dir, name)
        val dirCanon = dir.canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(dirCanon)) {
            throw SecurityException("路径越界：$name")
        }
        if (!file.isFile) throw IOException("报告不存在：$name")
        return file
    }

    companion object {

        /** authorities（与 AndroidManifest.xml 的 provider 声明一致）。 */
        const val AUTHORITY = "com.eratw.port.android.reportfiles"
    }
}
