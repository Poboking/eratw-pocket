package com.eratw.port.android.market

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 市场包的**本机收藏**（CONTRACT-MKM-STATS-UI §1 D2）。
 *
 * 数据裁定：kojo-market 无用户系统，全网收藏量不可得——做本机收藏交互（玩家标星喜欢的
 * 包），**不伪造全网数字**。收藏键按 `(kind,id)` 不分版本：收藏的是包不是版本，同包新版本
 * 发布后收藏关系自然延续。
 *
 * 存储：`<filesDir>/market/favorites.json`（`{"schema":1,"favorites":["kind/id",...]}`）。
 * 读侧防御：文件不存在 / 读失败 / JSON 语法或形状不对 / 条目畸形 ⇒ 回空集（内存态，盘上
 * 一个字节不动——读路径不写纪律与 [MarketIndexStore] 同源；下一次 toggle 落盘自然重建）。
 * 写侧 = 临时文件 fsync 后 rename 覆盖（原子替换），失败返回 false 由调用方折人话。
 *
 * 模块纪律：纯 JVM 零 Android API，定向单测可跑。
 */
class MarketFavoritesStore(private val file: File) {

/**
 * 读全量收藏集：文件缺席 / 读失败 / 损坏（语法错、非对象、schema 不对、favorites 非数组、
 * 条目非字符串或空串或重复）一律回空集——收藏是纯偏好数据，宁可让玩家重新点一次星，
 * 不让坏文件崩设置页。
 */
    fun read(): Set<String> {
        if (!file.exists()) return emptySet()
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return emptySet()
        }
        val root = MarketJson.parseOrNull(text) ?: return emptySet()
        val obj = root as? JsonObject ?: return emptySet()
        val schema = (obj.opt("schema") as? JsonNumber)?.intValueOrNull()
        if (schema != MarketContract.FAVORITES_SCHEMA) return emptySet()
        val favoritesNode = obj.opt("favorites") as? JsonArray ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (item in favoritesNode.items) {
            val key = (item as? JsonString)?.value
            if (key.isNullOrEmpty()) continue
            out.add(key)
        }
        return out
    }

    /** 覆盖写全量收藏集（键排序后落盘，字节面确定性）；失败返回 false（盘上旧文件原样）。 */
    fun write(favorites: Set<String>): Boolean {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) return false
        val body = buildString {
            append("{\"schema\":")
            append(MarketContract.FAVORITES_SCHEMA)
            append(",\"favorites\":[")
            favorites.sorted().forEachIndexed { index, key ->
                if (index > 0) append(',')
                append('"')
                append(key)
                append('"')
            }
            append("]}")
        }
        val temp = File(parent, file.name + ".tmp")
        return try {
            FileChannel.open(
                temp.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                channel.write(ByteBuffer.wrap(body.toByteArray(Charsets.UTF_8)))
                channel.force(true)
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: IOException) {
            runCatching { temp.delete() }
            false
        } catch (_: SecurityException) {
            runCatching { temp.delete() }
            false
        }
    }
}
