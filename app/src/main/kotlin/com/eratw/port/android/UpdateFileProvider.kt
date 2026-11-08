package com.eratw.port.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.eratw.port.core.update.UpdatePolicy
import java.io.File
import java.io.IOException

/**
 * 更新包的最小只读 Provider（国内镜像仓自动更新，2026-10-29）。
 *
 * ## 为什么非要有个 Provider
 *
 * 从 Android 7.0（API 24）起，`file://` URI 跨应用传递会直接抛 `FileUriExposedException`。
 * 系统安装器是**另一个进程**，所以把下载好的 APK 交给它只能走 `content://`，
 * 而这个 `content://` 必须由本应用暴露的 Provider 来解。
 *
 * ## 与 [ReportFileProvider] 同一套收口，理由也同一套
 *
 * 不引 `androidx.core` 的 `FileProvider`：壳层的 Compose 依赖是 `implementation` 作用域，
 * 抬不出这个类，为一个 Provider 再拉一条依赖还要过一次没有代理缺省的 Gradle 下载；
 * 而安装器真正用到的面只有 `openFile`（读）与 `query`（拿名字和大小）。收口照抄报告 Provider 的三条：
 *
 * - **只读**：`openFile` 只认 `r`；
 * - **只出下载目录**：请求必须落在 [UpdateManager.downloadDir] 的规范路径之内
 *   （canonical path 前缀比对，挡 `..` 与软链逃逸）；
 * - **只出更新包**：文件名必须以 `eratw-` 开头、`.apk` 结尾，并过 [UpdateManager] 的
 *   allowlist 字符集判定 —— 下载目录里还躺着没应用的数据包（几百 MiB 的 zip），
 *   那些是本应用自己解包用的，不该被任何外部进程读到。
 *
 * 配合 manifest 里的 `exported=false` + `grantUriPermissions=true`，只有本应用经
 * `FLAG_GRANT_READ_URI_PERMISSION` 临时授权的接收方（系统安装器）能读。
 */
class UpdateFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("更新包 Provider 只读：mode=$mode")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
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
        return MatrixCursor(cols).apply {
            newRow().apply {
                cols.forEach { col ->
                    when (col) {
                        OpenableColumns.DISPLAY_NAME -> add(file.name)
                        OpenableColumns.SIZE -> add(file.length())
                        else -> add(null)
                    }
                }
            }
        }
    }

    /** 安装器按 MIME 挑处理者；更新包恒为 Android 安装包。 */
    override fun getType(uri: Uri): String = APK_MIME

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("更新包 Provider 只读")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("更新包 Provider 只读")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("更新包 Provider 只读")

    /** 把 content URI 解析回下载目录里的更新包；越界、非更新包、不存在一律拒绝。 */
    private fun resolve(uri: Uri): File {
        val ctx = context ?: throw IllegalStateException("Provider 尚未附加")
        val dir = UpdateManager.downloadDir(ctx)
        val name = uri.lastPathSegment ?: throw IOException("空路径：$uri")
        if (!com.eratw.port.core.update.UpdatePolicy.isArtifactFileName(name)) {
            throw SecurityException("更新包名不合法：$name")
        }
        if (!name.startsWith(APK_PREFIX) || !name.endsWith(".apk")) {
            throw SecurityException("只允许读更新安装包：$name")
        }
        val file = File(dir, name)
        if (!file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            throw SecurityException("路径越界：$name")
        }
        if (!file.isFile) throw IOException("更新包不存在：$name")
        return file
    }

    companion object {

        /** authorities（与 `AndroidManifest.xml` 的 provider 声明一致）。 */
        const val AUTHORITY = "com.eratw.port.android.updatefiles"

        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val APK_PREFIX = "eratw-"

        /**
         * 给一个已下载的 APK 造授权 URI（[UpdateManager.launchInstaller] 用）。
         *
         * 只带文件名一段：读侧 [resolve] 会把它认回 [UpdateManager.downloadDir] 里的同一个文件，
         * 所以外部进程拿到 URI 也换不出别的目录。
         */
        fun uriFor(apk: File): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).path(apk.name).build()
    }
}
