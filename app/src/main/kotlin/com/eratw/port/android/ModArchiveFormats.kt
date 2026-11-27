package com.eratw.port.android

/**
 * 非 zip 压缩包的**识别表**（纯函数；单测逐格式点名）。
 *
 * ## 为什么只识别、不解包
 *
 * RAR5 没有任何纯 JVM 实现（junrar 只认 RAR4 且不支持加密）；7z / LZH / ALZ 要往 APK 里塞
 * JNI 或数 MB 依赖。口上包本体是文本 + zip 容器，玩家真正需要的信息不是「移植版多认三种
 * 容器」，而是「这个文件我该拿它怎么办」——解压后选 `.ERB`，或者重打成 zip。
 *
 * ## 口径
 *
 * - 尾段逐字符 **ASCII 折叠**比较（与 `ModStore` 的扩展名判定同源语义、locale 无关；
 *   不调 `lowercase()`，土耳其语环境 `I` 会折成 `ı`）。
 * - 必须有主名：`.rar` 这种隐藏文件名不算。
 * - 只认**最后一个扩展名**：`a.rar.txt` 不是 RAR（玩家真把压缩包改过名，交给后面的
 *   解码/编译失败面报，别在这里猜）。
 * - 扩展名表是唯一数据源：新格式只往 [Format.extensions] 里加，判定循环不要另抄一份
 *   （本项目已经因为手抄副本漏字符踩过 `#DIM X;注释` 那一坑）。
 */
internal object ModArchiveFormats {

    /**
     * 识别得出的容器族。
     *
     * [label] 直接上屏：这些是玩家在发布页/压缩软件里看到的名字，翻译反而对不上号。
     */
    enum class Format(val label: String, val extensions: List<String>) {
        RAR("RAR", listOf(".RAR")),
        SEVEN_ZIP("7z", listOf(".7Z")),
        TAR("TAR", listOf(".TAR")),
        GZIP("gzip", listOf(".GZ", ".TGZ")),
        BZIP2("bzip2", listOf(".BZ2", ".TBZ", ".TBZ2")),
        XZ("xz", listOf(".XZ", ".TXZ")),
        LZH("LZH", listOf(".LZH", ".LHA")),
        ALZ("ALZ", listOf(".ALZ")),
        CAB("CAB", listOf(".CAB")),
        ZSTD("zstd", listOf(".ZST", ".ZSTD")),
    }

    /** 文件名命中的容器族；不是压缩包（含 zip / `.ERB` / 无扩展名）返回 null。 */
    fun of(fileName: String): Format? = Format.entries.firstOrNull { format ->
        format.extensions.any { hasTrailingExtension(fileName, it) }
    }

    /** 尾段比较：大小写不敏感、必须有主名（长度严格大于扩展名）。 */
    private fun hasTrailingExtension(name: String, extension: String): Boolean {
        val start = name.length - extension.length
        if (start <= 0) return false
        return extension.indices.all { i -> foldAscii(name[start + i]) == foldAscii(extension[i]) }
    }

    /** 只折 A-Z（与 `ModStore.compareNames` 同款），其余原样。 */
    private fun foldAscii(c: Char): Char = if (c in 'A'..'Z') c + ASCII_CASE_BIT else c

    /** `a` 与 `A` 的码位差。 */
    private const val ASCII_CASE_BIT = 32
}
