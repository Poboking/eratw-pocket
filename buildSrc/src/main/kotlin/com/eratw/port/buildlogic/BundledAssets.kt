package com.eratw.port.buildlogic

import java.io.File

/**
 * 随包要递归落盘的语料目录（与 `GameAssetInstaller.DATA_DIRS` 同源，顺序也一致）。
 *
 * `plugins/` 是**运行期**读的，不是编译期：`ERB/SYSTEM.ERB:96-97` 的
 * `MAP_FROMXML CSV_MAP,LOADTEXT("plugins/tw_csv_chs.xml")` 在每次新开局跑一遍
 * （`@INIT_NG_OR_LOAD`），`plugins/schema.xml`+`bbas_*.xml` 供 `DT_FROMXML`。
 * 少了这个目录 `LOADTEXT` 读到空串 → `MAP_FROMXML` 在位置 0 抛
 * 「XML 解析错误」→ 开局直接死在 `@INIT_NG_OR_LOAD`（2026-10-22 真机实证）。
 * 只有 1.3 MB，全量随包。
 *
 * `bad/` 是 BADAPPLE 彩蛋的**文本帧**（不是图片）：`ERB/TITLE.ERB:123-158` 的 `@BADAPPLE`
 * 每帧 `LOADTEXT("bad/bad_apple{count}.txt")`（count 1..6573）。目录不在白名单时 LOADTEXT
 * 得空串，DEMO 空帧全速循环——真机上表现为「首页被清空 + 高频抖动」（2026-10-22
 * DevTools 实证：displayLines 里 `<font face='黑体'></font></p>` 空帧行）。55 MB / 6,572 个
 * 小 txt，全量随包；首启解包文件数因此 +6,572（684 MiB / 3,683 文件的已知口径相应放大）。
 *
 * @param includeBadFrames false = `-PeratwSkipBadFrames=1`：`bad/` 只服务已关闭的演示，不随包（省 44.6 MB）
 */
fun stagedDataDirs(includeBadFrames: Boolean): List<String> = buildList {
    add("CSV")
    add("resources")
    add("sound")
    add("plugins")
    if (includeBadFrames) add("bad")
}

/** 随包的游戏根配置文件；`emuera.config` 是 GameStorage 的「像游戏根」标志文件。 */
val stagedConfigFiles = listOf("emuera.config", "default.config", "fixed.config")

/**
 * 字体后缀**永不** STORED：`.ttf` 1.1 MB 压缩后 0.35 MB，而 `Typeface.createFromAsset`
 * 吃压缩条目完全正常（实测 noCompress 加 ttf 会让 debug 包从 3.92 MiB 涨到 5.32 MiB，白涨 1.4 MB）。
 * 语料目录里现在没有这些后缀，写死这张表是防将来某个 `resources` 子目录塞进字体时静默掉口径。
 */
val alwaysCompress = setOf("ttf", "otf", "ttc", "woff", "woff2")

/**
 * aapt2 的 `--no-compress` **只有「按后缀匹配」一种口径**，所以后缀表必须来自真实要随包的目录：
 * 手写一份会在语料新增 `.als`（CSV 里已有 19 个）或 `.xlsx` 时静默漏掉，条目掉进 DEFLATE，
 * 首启解包因此拿不到 `openFd` 长度、幂等判定退化成「存在即跳过」。
 *
 * 配置期扫一遍是 3,451 次文件名读取（几十毫秒）。三类特殊情况一起处理：
 * - **无后缀文件**（真语料 18 个零字节 `sound/游戏bgm/<地名>/bgm` 标记）：后缀匹配够不着，
 *   于是把**文件名本身**也塞进 noCompress（aapt2 拿它当后缀比，路径以 `bgm` 结尾就命中）；
 *   命中不了也只回退到 DEFLATE，`inspect_apk.py` 会把这类条目单独列出来。
 * - **分片清单 `program.parts` 与自述报告 `package.report`**：条目只有几十字节，
 *   但 STORED 之后 `AssetManager.openFd` 能直接拿到长度，解包侧的字节对账不用退化成
 *   「目标存在即视为已装」，所以一并写进后缀表。
 * - 字体后缀（[alwaysCompress]）反向挡掉：`.ttf` 压缩后省 0.7 MB，`createFromAsset` 照常吃。
 *
 * @param gameRoot 本次随包的游戏根（null = 无游戏根形态，返回空表）
 * @param dataDirs [stagedDataDirs] 的结果
 * @param configFiles [stagedConfigFiles]
 */
fun bundledSuffixes(gameRoot: File?, dataDirs: List<String>, configFiles: List<String>): Set<String> {
    val root = gameRoot ?: return emptySet()
    val found = sortedSetOf("parts", "report")
    dataDirs.forEach { name ->
        val dir = File(root, name)
        if (dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val suffix = f.name.substringAfterLast('.', "")
                if (suffix.isEmpty()) {
                    // 无后缀：用文件名本身当 noCompress 后缀（见上面第二条）
                    if (f.name.length <= 32 && f.name.all { c -> c.isLetterOrDigit() || c in "._-" }) {
                        found += f.name.lowercase()
                    }
                } else if (suffix.length <= 8 && suffix.all { it.isLetterOrDigit() }) {
                    val key = suffix.lowercase()
                    if (key !in alwaysCompress) found += key
                }
            }
        }
    }
    configFiles.forEach { name -> if (File(root, name).isFile) found += "config" }
    return found
}
