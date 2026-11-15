package com.eratw.port.platform.save

import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SidecarSaveInventory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「一键导出存档」的收集与打包（安卓设置页用；引擎侧不改动）。
 *
 * ## 导出什么
 *
 * 与 [SaveImport] 是一个对称面：只导**存档槽本体** `saveNN.sav`、它的 `.rt2` 侧车
 * `saveNN.sav.rt2`（RT2 C 车：.sav + .rt2 = 一个逻辑存档单元）与跨槽共享的
 * `global.sav`（及 `global.sav.rt2`）（spec: `save-format.md` §2.1；
 * `VariableEvaluator.cs:2018-2032`）。
 * `dat/` 下的 `chara_*.dat` / `var_*.dat` 是 `SAVECHARA` 族的角色/变量转储，不是存档槽
 * 的零件，导入侧也不收（[SaveImport] 只认 `.sav`），这里同样不碰。
 *
 * 目录按**生效的** [SaveConfig.useSaveFolder] 取（`Config.cs:470-481`）：开 ⇒ `sav/`，
 * 关 ⇒ 游戏根。另一处也扫一遍并用文件名的第一个命中：改过开关但还没重开游戏时，
 * 旧目录里的档仍是玩家的东西，导出不该静默漏掉；生效目录优先，是因为那才是引擎下一次
 * 读档会看的地方。
 *
 * ## 为什么是 zip
 *
 * 一次导出只产出一个文件，玩家发到 PC / 另一台手机都不必逐格挑；包内统一放 `sav/` 前缀，
 * 原版 eratw 解压后可直接落回游戏目录（`emuera.config` 用 `USE SAV FOLDER:YES`）。
 * 安卓侧导回来时 [SaveImport] 只认文件名，前缀不影响落位。
 *
 * ## 内存口径
 *
 * [collect] 只登记路径与大小，[writeZip] 逐个读文件边读边写：真机堆只有 OOM 前科
 * （BOARD 2026-10-27，512 MB 堆顶），不能把 80 个槽的档一次全吸进内存。
 * 单文件超过 [SaveImport.MAX_SAVE_BYTES] 的按「不像存档」跳过并在结果里报出来 ——
 * 与导入侧同一道体积闸。
 *
 * ## `@Suppress("NewApi")` 的理由（不是掩盖问题）
 *
 * 本类用 `java.time`（`DateTimeFormatter` / `LocalDateTime`，平台 API 26 起才有）。
 * 本模块 `minSdk = 21`，但**消费方 `:app` 开了核心库脱糖**
 * （`isCoreLibraryDesugaringEnabled = true` + `com.android.tools:desugar_jdk_libs_nio:2.1.5`，
 * 见 `app/build.gradle.kts`），打包时这些类会被替换成脱糖实现，Android 5.0 起可用。
 * lint 的 `NewApi` 是**按模块**判的，看不到消费方的脱糖配置 ⇒ 这里是误报。
 * 只在「真的靠脱糖」的这两个类上抑制，别处仍按默认口径查。
 */
@Suppress("NewApi")
object SaveExport {

    /** 包内目录前缀。原版 `USE SAV FOLDER:YES` 的落位（`SaveConfig.saveFolderPrefix` 同源）。 */
    private const val SAV_DIR = "sav"

    /** 存档槽名 `saveNN.sav`（`SaveDataProbe.saveName`，`VariableEvaluator.cs:2023-2032`）。 */
    private val SLOT_NAME = Regex("(?i)save\\d+\\.sav")

    /** 存档槽的 .rt2 侧车名 `saveNN.sav.rt2`（RT2 C 车：.sav + .rt2 = 一个逻辑存档单元）。 */
    private val SLOT_SIDECAR_NAME = Regex("(?i)save\\d+\\.sav\\.rt2")

    /** 全局档固定名（`getSaveDataPathG()`，`VariableEvaluator.cs:2018-2021`）。 */
    private const val GLOBAL_NAME = "global.sav"

    /** 全局档的 .rt2 侧车名（与 `global.sav` 同目录同名加后缀）。 */
    private const val GLOBAL_SIDECAR_NAME = "global.sav.rt2"

    private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    /** 一个待写进 zip 的条目：[name] 是包内路径，[file] 是磁盘上的原文件（写的时候才读）。 */
    data class Entry(val name: String, val file: File)

    /** 找到了但没进包的存档文件；[reason] 原样上屏。 */
    data class Skipped(val name: String, val reason: String)

    /** 一次导出的快照。 */
    data class Bundle(val entries: List<Entry>, val skipped: List<Skipped>) {
        val isEmpty: Boolean get() = entries.isEmpty()

        /** 包里有没有全局档（成功提示据此补一句）。 */
        val hasGlobal: Boolean
            get() = entries.any { it.file.name.equals(GLOBAL_NAME, ignoreCase = true) }
    }

    /** [writeZip] 的读数：失败时 [failed] 是第一个读不动的条目名（null = 不知道是哪条）。 */
    data class ZipResult(val ok: Boolean, val written: Int, val failed: String?)

    /**
     * 收集要导出的存档。
     *
     * ## 配对判据（MODGC2 裁定 1）
     *
     * 导出的是**完整逻辑存档单元**：`.rt2` 侧车只有在其同槽本体**同一批进包**时才收。
     * 本体缺失的侧车（DELDATA 历史遗留、迁移随迁失败留在根的那一半）一律进 [Bundle.skipped]，
     * 理由 = [SaveImport.REASON_SIDECAR_WITHOUT_BODY] —— 一个只有 `.rt2` 的包导到别的设备上
     * 谁也读不了（[SaveImport] 也会拒收），收它只是把孤儿搬到玩家另一个目录里。
     *
     * 配对按**文件名**跨目录成立：生效目录里是本体、兜底目录里是侧车时照样成对进包
     * （这正是迁移只搬走本体那一半的形态）。生效目录优先的「先到先得」不变。
     *
     * @param root          游戏根（[com.eratw.port.platform.storage.GameStorage.gameRoot]）
     * @param config        设置页当前的生效口径，只消费 [SaveConfig.useSaveFolder]
     * @param maxEntryBytes 单文件体积闸，缺省与导入侧同一值
     */
    fun collect(
        root: File,
        config: SaveConfig,
        maxEntryBytes: Int = SaveImport.MAX_SAVE_BYTES,
    ): Bundle {
        val effective = if (config.useSaveFolder) File(root, SAV_DIR) else root
        val fallback = if (config.useSaveFolder) root else File(root, SAV_DIR)
        val draft = Draft()

        // 第一趟：只收本体（槽 + global）。侧车能不能进包取决于本体在不在 ⇒ 必须先定本体。
        scanDir(effective, ::isBodyName, { null }, draft, maxEntryBytes)
        scanDir(fallback, ::isBodyName, { null }, draft, maxEntryBytes)
        val bodiesInBundle = draft.claimed.filterValues { it != null }.keys

        // 第二趟：侧车只在同槽本体已在包内时收；否则如实跳过（不替玩家把孤儿搬进包里）。
        val unpaired: (String) -> String? = { key ->
            if (bodyKeyOf(key) in bodiesInBundle) null else SaveImport.REASON_SIDECAR_WITHOUT_BODY
        }
        scanDir(effective, ::isSidecarName, unpaired, draft, maxEntryBytes)
        scanDir(fallback, ::isSidecarName, unpaired, draft, maxEntryBytes)

        val entries = draft.claimed.values.filterNotNull().sortedBy { it.name.lowercase(Locale.ROOT) }
        return Bundle(entries, draft.skipped)
    }

    /**
     * 一次收集的中间状态：[claimed] 兼去重与占位（同名只认生效目录先到的那一份，被闸挡下的
     * 名字也不再被兜底目录顶替），[skipped] 是逐条上屏理由。
     */
    private class Draft {
        val claimed = LinkedHashMap<String, Entry?>()
        val skipped = ArrayList<Skipped>()
    }

    /**
     * 扫一层目录（[accept] 只决定「收不收这一类」），把结果记进 [draft]。
     *
     * [unpaired] 是配对闸：回 null = 可以收，回理由 = 记 [Skipped]。
     */
    private fun scanDir(
        dir: File,
        accept: (String) -> Boolean,
        unpaired: (String) -> String?,
        draft: Draft,
        maxEntryBytes: Int,
    ) {
        val listed = dir.listFiles() ?: return
        for (file in listed.sortedBy { it.name.lowercase(Locale.ROOT) }) {
            if (!file.isFile || !accept(file.name)) continue
            val key = file.name.lowercase(Locale.ROOT)
            if (key in draft.claimed) continue
            // 两道通用闸（空文件 / 超体积）优先于配对闸：坏文件本来就进不了包。
            val reason = when {
                file.length() == 0L -> "是空文件"
                file.length() > maxEntryBytes -> "超过体积上限"
                else -> unpaired(key)
            }
            if (reason == null) {
                draft.claimed[key] = Entry("$SAV_DIR/${file.name}", file)
            } else {
                draft.claimed[key] = null
                draft.skipped += Skipped(file.name, reason)
            }
        }
    }

    /**
     * 把 [entries] 写成 zip。逐条流式拷贝，返回值如实报「写了几条 / 卡在哪条」——
     * 调用方据此上屏，不重试也不掩错。
     *
     * 只吞 [Exception]（读文件失败、盘满、zip 头写坏）：`Error`（OOM / StackOverflow）
     * 必须上抛给宿主的崩溃处理，不能被伪装成「导出失败」静默吞掉（BOARD 稳定性纪律）。
     * 注：`use` 块里 `ZipOutputStream.close()` 抛出的异常同样走这条 catch。
     */
    fun writeZip(out: OutputStream, entries: List<Entry>): ZipResult {
        var written = 0
        var current: String? = null
        return try {
            ZipOutputStream(out).use { zip ->
                for (entry in entries) {
                    current = entry.name
                    zip.putNextEntry(ZipEntry(entry.name))
                    entry.file.inputStream().use { input -> copy(input, zip) }
                    zip.closeEntry()
                    written++
                }
            }
            ZipResult(ok = true, written = written, failed = null)
        } catch (_: Exception) {
            ZipResult(ok = false, written = written, failed = current)
        }
    }

    /** 建议的文件名：`eratw-saves-yyyyMMdd-HHmmss.zip`（ASCII，PC 与安卓都不挑编码）。 */
    fun suggestName(now: LocalDateTime): String = "eratw-saves-${now.format(NAME_FORMAT)}.zip"

    private fun isBodyName(name: String): Boolean =
        name.equals(GLOBAL_NAME, ignoreCase = true) || SLOT_NAME.matches(name)

    private fun isSidecarName(name: String): Boolean =
        name.equals(GLOBAL_SIDECAR_NAME, ignoreCase = true) || SLOT_SIDECAR_NAME.matches(name)

    /** 侧车的小写名 → 同槽本体的小写名（`save00.sav.rt2` → `save00.sav`）。 */
    private fun bodyKeyOf(sidecarKey: String): String =
        sidecarKey.removeSuffix(SidecarSaveInventory.CONTAINER_SUFFIX)

    private fun copy(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
    }
}
