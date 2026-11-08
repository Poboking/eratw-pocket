package com.eratw.port.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.eratw.port.platform.storage.MaterialEntry
import com.eratw.port.platform.storage.MaterialSource
import java.io.IOException
import java.util.ArrayDeque

/**
 * SAF 直选素材的两个 [MaterialSource] 实现：多选文件 / 整棵目录树。
 *
 * ## 为什么走 [MaterialSource] 而不是先复制到缓存
 *
 * [com.eratw.port.platform.storage.ResourcePackStore.installSource] 逐条流式写入游戏根，
 * 单条失败只跳过该条；先复制到缓存等于把整夹素材占两倍空间，还会让「几百 MB 的
 * 背景图」在手机上多写一遍。这里只枚举名字与大小，内容在 store 的写入循环里才打开。
 *
 * ## 目录树枚举
 *
 * SAF 的 `ACTION_OPEN_DOCUMENT_TREE` 授权给的是树根，子项要按
 * `DocumentsContract.buildChildDocumentsUriUsingTree` 逐层查。相对路径（`/` 分隔）是
 * store 判断「带不带 resources/背景图 层」「哪个目录是地点」的唯一依据，所以这里必须保留
 * 完整层级；隐藏目录与 `__MACOSX` 照 [com.eratw.port.platform.storage.FolderMaterialSource]
 * 的口径跳过，顺序按相对路径排序，BGM 自动编号才可复现。
 */
internal class SafFileMaterialSource(
    override val label: String,
    private val resolver: ContentResolver,
    private val uris: List<Uri>,
) : MaterialSource {

    override fun entries(): List<MaterialEntry> {
        val out = ArrayList<MaterialEntry>(uris.size)
        for (uri in uris) {
            val name = UriNames.displayNameOf(resolver, uri, fallback = null)
                ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: continue
            out += MaterialEntry(name, SafMaterialSources.sizeOf(resolver, uri)) {
                resolver.openInputStream(uri) ?: throw IOException("打不开 $name")
            }
        }
        return out
    }
}

internal class SafTreeMaterialSource(
    override val label: String,
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : MaterialSource {

    override fun entries(): List<MaterialEntry> {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<MaterialEntry>()
        val stack = ArrayDeque<Pair<String, String>>()
        val visited = HashSet<String>()
        stack.addLast(rootId to "")
        while (stack.isNotEmpty()) {
            val (documentId, relDir) = stack.removeLast()
            if (!visited.add(documentId)) continue
            val childrenUri = runCatching {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            }.getOrNull() ?: continue
            val cursor = runCatching {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null,
                    null,
                    null,
                )
            }.getOrNull() ?: continue
            cursor.use {
                while (it.moveToNext()) {
                    val childId = it.getString(0)?.takeIf(String::isNotBlank) ?: continue
                    val name = it.getString(1)?.takeIf(String::isNotBlank) ?: continue
                    val relative = if (relDir.isEmpty()) name else "$relDir/$name"
                    if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".") && !name.equals("__MACOSX", ignoreCase = true)) {
                            stack.addLast(childId to relative)
                        }
                        continue
                    }
                    val childUri = runCatching {
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    }.getOrNull() ?: continue
                    out += MaterialEntry(relative, SafMaterialSources.sizeOf(resolver, childUri)) {
                        resolver.openInputStream(childUri) ?: throw IOException("打不开 $name")
                    }
                }
            }
        }
        out.sortBy { it.name }
        return out
    }
}

/** SAF 查询的薄壳：树根显示名与文件声明大小。 */
internal object SafMaterialSources {

    /** 目录树根的显示名（= BGM 卡的默认地点名）；取不到时回落 URI 末段。 */
    fun treeLabel(resolver: ContentResolver, treeUri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val documentUri = documentId?.let {
            runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }.getOrNull()
        }
        val queried = documentUri?.let { UriNames.displayNameOf(resolver, it, fallback = null) }
        return queried?.takeIf { it.isNotBlank() }
            ?: treeUri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "folder"
    }

    /** [OpenableColumns.SIZE]；提供方不给（-1）时交给 store 的写入量预算兜底。 */
    fun sizeOf(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L
}
