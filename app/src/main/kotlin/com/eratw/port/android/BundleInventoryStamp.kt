package com.eratw.port.android

/**
 * 随包清单缓存的**纯编解码**（无 Android 依赖，可在普通 JVM 单测里钉死）。
 *
 * 冷启清点 APK assets 要递归整棵树（数千次 `AssetManager` JNI），而 assets 在一次安装内
 * 不可变。于是把「条目数 + 字节数」连同失效键（构建标识 + 安装时间）落成一行文本：
 *
 * ```
 * v1<TAB>stamp<TAB>entries<TAB>bytes
 * ```
 *
 * ## 失效策略
 *
 * [decode] 只有在「前缀 / 字段数 / 版本 / stamp」四项全等时才返回读数，
 * 任何一项不符（换包、重装、文件被截断、手改）都返回 null，调用方回退整树扫描。
 * 缓存只影响「首次启动要解多少」的随包读数，已落盘清点始终逐次走盘，
 * 所以缓存脏了最坏是多扫一遍，不会把缺件误判成已装。
 *
 * ## 分隔符安全
 *
 * [encode] 会把 stamp 里的制表符与换行替换成下划线，保证编码不会多出字段；
 * [decode] 按**原样**比较 stamp，因此带分隔符的 stamp 编码后无法再解回
 * （视为缓存未命中、回退整树扫描）—— 这是刻意的 fail-open：宁可多扫一遍，也不让
 * 一个畸形 stamp 命中别的缓存行。
 */
internal object BundleInventoryStamp {

    /** 当前格式版本；改格式必须换版本号，旧缓存自然失配。 */
    private const val VERSION = "v1"

    /** 字段分隔符（制表符）。 */
    private const val SEP = '\t'

    /** 固定字段数：版本 + stamp + 条目数 + 字节数。 */
    private const val FIELD_COUNT = 4

    /** 版本前缀（含分隔符）：先看前缀，避免把任意文本切成四段再逐段判。 */
    private const val PREFIX = VERSION + SEP

    /**
     * 编码一行缓存文本：`v1\t<stamp>\t<entries>\t<bytes>`。
     *
     * stamp 里的 `\t` 与 `\n` 会先替换成 `_`（见类头「分隔符安全」段），
     * 否则一条带制表符的 stamp 会把字段数撑成 5，[decode] 永远读不回来。
     * 条目数与字节数由调用方保证非负；这里不做箝位，以免把写侧的错误藏起来。
     */
    fun encode(stamp: String, entries: Int, bytes: Long): String =
        "$PREFIX${sanitize(stamp)}$SEP$entries$SEP$bytes"

    /**
     * 解码：前缀、字段数、版本、[stamp] 全等且两个数字非负可解析时才返回 `(entries, bytes)`。
     *
     * 任何异常（含超大文本、非数字、负数、Int/Long 溢出）都返回 null，绝不向外抛。
     *
     * @param text 缓存文件全文（不接受额外的前后缀或空行）
     * @param stamp 期望的失效键；与文件里的第二字段**逐字**比较（不做大小写/空白归一）
     */
    fun decode(text: String, stamp: String): Pair<Int, Long>? {
        return try {
            if (!text.startsWith(PREFIX)) return null
            val fields = text.split(SEP)
            if (fields.size != FIELD_COUNT) return null
            if (fields[0] != VERSION) return null
            if (fields[1] != stamp) return null
            val entries = fields[2].toIntOrNull() ?: return null
            val bytes = fields[3].toLongOrNull() ?: return null
            if (entries < 0 || bytes < 0L) return null
            entries to bytes
        } catch (_: Throwable) {
            // 理论上 split / parseInt 只会抛受检范围外的运行时异常（如 OOM），
            // 缓存读不出来就当没有：回退整树扫描，不打扰启动流程。
            null
        }
    }

    /** 把 stamp 中的字段分隔符与换行换成下划线，保证编码后的字段数恒为 4。 */
    private fun sanitize(stamp: String): String =
        stamp.replace('\t', '_').replace('\n', '_')
}
