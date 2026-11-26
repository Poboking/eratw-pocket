package com.eratw.port.android

import com.eratw.port.core.mod.ModSettingsSnapshot
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.save.SaveDiagnostics
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * per-mod 设置的**设备侧持久化**（RT2 per-mod 配置，app 侧唯一读写封装）。
 *
 * ## 落点与形态
 *
 * 每包一个文件 `mods/rt2/<packageId>.settings`（与 manifest 同目录，但**不进存档** ——
 * 设置是跨存档 / 跨局保留的设备级配置）。文本格式，逐行 hex 编码，避免任何转义争吵：
 *
 * ```
 * RT2SETTINGS/1
 * <hex(key)>=<hex(value)>
 * ```
 *
 * 键按原文排序落盘 ⇒ 同一份 `Map` 两次写出的字节相同（可做 golden / 断言）。编码与解析的
 * 纯函数部分在 [ModSettingsCodec]，本对象只做文件 IO 与快照装配。
 *
 * ## 三条硬约束（每条都有对应测试）
 *
 * 1. **原子写**：先写同目录临时文件、fsync、再 `ATOMIC_MOVE` 覆盖（不支持的 FS 回退
 *    `REPLACE_EXISTING`）。写到一半掉电只坏临时文件，正式文件永远是上一份完整内容。
 * 2. **坏值回退默认**：值文本对声明类型的合法性判定在 core `ModSettingsSnapshot` 里做
 *    （读侧回退声明默认值），本类只负责把文件读成 `Map`，不解释类型。
 * 3. **坏文件不炸启动**：文件缺失 / 头不对 / 行格式坏 / hex 坏 / UTF-8 坏 / 重复键 /
 *    超大 → 整份当「没有已保存值」返回空 Map（所有键回默认），**不抛、不崩**。
 *
 * 线程口径：写只在设置页（UI 线程）发生，读在 attach 装配期（VM 线程）发生；都不碰
 * SharedPreferences（铁律：非主线程不直接读 SharedPreferences）。
 *
 * ## 与包成员面的关系（task-95）
 *
 * `<packageId>.settings` 是包的第 6 个成员形状，但这件侧车**不参与导入写序**：导入链只写
 * 四件套（provenance / 指纹 / manifest / 容器，见 [ModPackageInventory.memberNames]），设置
 * 由设置页按需写入。删除语义因此分三种路径（互相不冲突）：
 *
 * 1. **显式卸载**（[ModManageUninstall.uninstall]）⇒ 删净，含本文件；
 * 2. **孤儿 GC**（[ModPackageInventory.gcOrphans]）⇒ 仅当该包已被既有判据判为孤儿（半成品 /
 *    源失联）时连带删；只有本文件、没有任何包文件时保守保留（作者先摆设置再导入是合法顺序）。
 * 3. **重导 / 升级**（[ModImportController] 的 reimport 分支）⇒ **保留**：BU 波已交付的
 *    显式语义（玩家可见文案「设置与数据保留」），本对象不参与该路径，也不得擅自改成清空。
 *
 * 路径拼装只有 [memberName] / [tempMemberName] 两个出口：包成员面与 GC 残留清理都引用这里，
 * 不许在别处再写一份字面量（源级哨兵测试钉住）。
 */
object ModSettingsStore {

    /** 后缀（与 manifest 同目录，命名空间分开）。 */
    const val SETTINGS_EXTENSION = ".settings"

    /** 单文件读取上限：设置是几个短值，超过这个尺寸只能是坏文件 / 恶意文件。 */
    private const val MAX_FILE_BYTES = 1 shl 20

    /** 原子写临时文件名前缀（`.<packageId>.settings.tmp`）。 */
    private const val TEMP_PREFIX = "."

    /** 原子写临时文件名后缀；GC 残留判据与 [write] 同源引用。 */
    private const val TEMP_SUFFIX = ".tmp"

    /** 本包设置文件名（**唯一**拼装出口；包成员面引用这里，不再另写字面量）。 */
    fun memberName(packageId: String): String = packageId + SETTINGS_EXTENSION

    /** 本包设置文件的原子写临时文件名（相对 `rt2Dir`；GC 残留清理引用这里）。 */
    fun tempMemberName(packageId: String): String = TEMP_PREFIX + memberName(packageId) + TEMP_SUFFIX

    /**
     * 过程残留文件名 → 所属 packageId；不是 `.<packageId>.settings.tmp` 形状 ⇒ null。
     *
     * 只做**文件名形状**解析，不做 `kojo-` 前缀/长度闸（安全阀在 [gcTempResidues] 一侧）；
     * 含路径分隔符或空包名一律不算。
     */
    fun tempResiduePackageId(name: String): String? {
        val suffix = SETTINGS_EXTENSION + TEMP_SUFFIX
        if (!name.startsWith(TEMP_PREFIX) || !name.endsWith(suffix)) return null
        val packageId = name.substring(TEMP_PREFIX.length, name.length - suffix.length)
        val valid = packageId.isNotEmpty() && packageId != "." && packageId != ".." &&
            '/' !in packageId && '\\' !in packageId
        return packageId.takeIf { valid }
    }

    /** 本包设置文件路径。 */
    fun settingsFile(rt2Dir: File, packageId: String): File {
        require(packageId.isNotBlank()) { "packageId 不能为空" }
        require('/' !in packageId && '\\' !in packageId && packageId != "." && packageId != "..") {
            "packageId 不能含路径分隔符：$packageId"
        }
        return File(rt2Dir, memberName(packageId))
    }

    /**
     * `mods/rt2/` 设置原子写过程残留清理：`.<kojo-…>.settings.tmp` 文件（由 [write] 写出）。
     *
     * 由 [ModPackageInventory.gcOrphans] 在包级判据之后转交（GC 全程不抛）。判据保守，与
     * 资源面残留清理同款：
     *
     * 1. **形状精确**：必须能被 [tempResiduePackageId] 解析回 packageId，且带
     *    [ModPackageInventory.PACKAGE_ID_PREFIX] 前缀与长度闸；非本链产物、目录一律不碰；
     * 2. **活配置永不因残留清理而丢**：同 packageId 的正式 `.settings` 仍是文件时保守保留
     *    残留（替换写可能刚走到 rename 之前，宁留垃圾也不删活配置）；
     * 3. **[ModPackageInventory.RESIDUE_GC_MIN_AGE_MILLIS] 保守时间窗**：`lastModified` 早于
     *    24 小时才删；时间不可读（`<= 0`）/ 窗内 / 未来时间戳一律保留。**时间窗是为了不误删
     *    在途 tmp，不是为了删得更干净**——写 tmp 与 rename 之间启动 GC 撞上时，窗内残留会被
     *    保留，让那次写还能正常收尾；
     * 4. 删除 + `MOD_PKG_GC` 留痕；删除失败不抛、原地保留并留 WARN。
     *
     * @param nowMillis 测试注入时钟；生产用默认值，与资源面残留清理看同一把钟
     *   （[ModPackageInventory.gcOrphans] 一轮里透传同一个 `nowMillis`）。
     */
    fun gcTempResidues(rt2Dir: File, entries: List<File>, nowMillis: Long = System.currentTimeMillis()) {
        val prefix = ModPackageInventory.PACKAGE_ID_PREFIX
        val residues = entries
            .filter { it.isFile }
            .mapNotNull { file -> tempResiduePackageId(file.name)?.let { packageId -> file to packageId } }
            .filter { (_, packageId) ->
                packageId.length > prefix.length && packageId.startsWith(prefix) &&
                    !settingsFile(rt2Dir, packageId).isFile
            }
            .filter { (file, _) -> ModPackageInventory.residuePastGcWindow(file, nowMillis) }
        for ((file, _) in residues) {
            val reason = "设置原子写中途退出残留，无在用设置文件"
            val removed = runCatching { file.delete() }.getOrDefault(false)
            SaveDiagnostics.note(
                "MOD_PKG_GC",
                file.name,
                if (removed) {
                    "设置过程残留文件已删除（$reason）"
                } else {
                    "设置过程残留文件删除失败，原地保留（$reason）"
                },
            )
        }
    }

    /** 读一包已持久化的值文本；任何损坏都返回空表（见类 KDoc 第 3 条）。 */
    fun read(rt2Dir: File, packageId: String): Map<String, String> {
        val file = settingsFile(rt2Dir, packageId)
        val usable = file.isFile && file.length() <= MAX_FILE_BYTES
        val text = if (usable) runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() else null
        return text?.let(ModSettingsCodec::parse) ?: emptyMap()
    }

    /**
     * 合并写入一包的值（读旧表 → 覆盖 [updates] → 原子写整份）。
     *
     * 只改 [updates] 里的键：设置页保存单项时不把其它键「顺手清空」。写失败返回 false
     * （调用方决定是否提示）；失败不留半截正式文件（见类 KDoc 第 1 条）。
     */
    fun saveValues(rt2Dir: File, packageId: String, updates: Map<String, String>): Boolean {
        if (updates.isEmpty()) return true
        val merged = LinkedHashMap(read(rt2Dir, packageId))
        merged.putAll(updates)
        return write(rt2Dir, packageId, merged)
    }

    /** 整份原子写（键排序后落盘；失败只清理临时文件并返回 false）。 */
    fun write(rt2Dir: File, packageId: String, values: Map<String, String>): Boolean {
        val file = settingsFile(rt2Dir, packageId)
        if (!rt2Dir.isDirectory && !rt2Dir.mkdirs() && !rt2Dir.isDirectory) return false
        val bytes = ModSettingsCodec.encode(values).toByteArray(StandardCharsets.UTF_8)
        val tmp = File(rt2Dir, tempMemberName(packageId))
        val written = runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
        }.isSuccess
        val moved = written && runCatching { moveAtomically(tmp, file) }.isSuccess
        if (!moved) runCatching { tmp.delete() }
        return moved
    }

    /**
     * attach 装配期用：为本批**已启用**的包构造设置快照（声明 + 已持久化值）。
     *
     * 声明真源 = `mods/rt2/` 目录下各 manifest 的 `settings` 段（与声明快照同一批文件；这里独立
     * 再解一次拿到 `ModuleDescriptor.settings`，不改 `ManifestLoader` 的公开返回面）。
     * 坏 manifest 会在此整抛 —— 与 attach「坏档整抛不半装」的既有口径一致。
     *
     * 每包都产快照（即使没声明任何设置项）：这样作者读未声明键拿到的是
     * 「设置项未声明」而不是「设置面未装配」，错误指向对。
     *
     * 解码走 [ManifestCodec] 的复用入口：attach 作用域内同一批 manifest 已在装载 / 链装配
     * 阶段解析过 ⇒ 这里命中复用，一次装配每份 manifest 只真解析一次（见 `ManifestCodec`
     * 类 KDoc「解码复用」节）；作用域外直调本函数时逐位等于既有独立解码口径。
     */
    fun snapshotsFor(gameRoot: File): Map<String, ModSettingsSnapshot> {
        val rt2Dir = ModEnablement.rt2Dir(gameRoot)
        val manifests = (rt2Dir.listFiles() ?: emptyArray())
            .filter { file -> file.isFile && file.name.endsWith(ModRuntimeAssembly.MANIFEST_EXTENSION) }
            .sortedBy { it.name }
        val enabled = ModEnablement.enabledOnly(rt2Dir, manifests.toList())
        val snapshots = LinkedHashMap<String, ModSettingsSnapshot>()
        for (file in enabled) {
            val descriptor = ManifestCodec.decode(file.readBytes())
            snapshots[descriptor.packageId] = ModSettingsSnapshot(
                packageId = descriptor.packageId,
                declarations = descriptor.settings,
                persistedValues = read(rt2Dir, descriptor.packageId),
            )
        }
        return snapshots
    }

    /** 原子替换：优先 ATOMIC_MOVE，文件系统不支持时退回 REPLACE_EXISTING。 */
    private fun moveAtomically(tmp: File, target: File) {
        val moved = runCatching {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.isSuccess
        if (!moved) Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * 一包设置「恢复默认」：**删掉整份 `.<packageId>.settings` 侧车**（不是逐键改写成空值）。
 *
 * 挂成 [ModSettingsStore] 的扩展而不是对象成员：对象内函数数已到 detekt `TooManyFunctions`
 * 的阈值（11 个即报红），本操作又不属于「编码/读盘」核心，扩展函数与对象成员在调用点
 * （`ModSettingsStore.resetToDefaults(...)`）外观一致。
 *
 * ## 为什么删整包文件，而不是删指定键后重写
 *
 * 1. **语义恰好等价**：缺键 = 没有已持久化值 = core 侧 [ModSettingsSnapshot.resolveText] 回退
 *    声明默认（`ModSettingsApi.kt:58-61`），这正是「恢复默认」要的终态；文件里连「值等于默认」
 *    的残留行也不留，作者读盘看到的与玩家看到的一致。
 * 2. **更原子**：`unlink` 是单次目录项删除，没有「读旧表 → 拼新表 → 临时文件 → rename」这段
 *    窗口；逐键重写反而要把 [ModSettingsStore.write] 的 tmp+fsync+ATOMIC_MOVE 再走一遍，任一步
 *    失败都会留下「删了一半」的中间态。设置按包收在独立侧车里（见 [ModSettingsStore] 类 KDoc），
 *    整包删就是最小可原子化的单位。
 * 3. **不会误伤别的包/别的成员**：路径只经 [ModSettingsStore.settingsFile]（唯一拼装出口），侧车
 *    与 manifest / 容器不共享文件。
 *
 * 幂等：文件本来就缺失（或缺 `rt2Dir`）⇒ 返回 true（已经是默认态），不抛。
 * 删除失败（权限 / IO 错）⇒ 返回 false 且文件原样保留，调用方提示失败；不尝试「先清空再删」
 * 之类的降级写法 —— 那会先把玩家原有值弄丢。
 *
 * 删除后必须由调用方把**被删掉的键**投递 [ModSettingsNotifyChannel]（键集合删前用
 * [ModSettingsStore.read] 取），热生效口径与 [ModSettingsStore.saveValues] 相同：VM 线程页边界
 * drain。
 */
internal fun ModSettingsStore.resetToDefaults(rt2Dir: File, packageId: String): Boolean {
    val file = settingsFile(rt2Dir, packageId)
    if (!file.exists()) return true
    val deleted = runCatching { file.delete() }.getOrDefault(false)
    return deleted && !file.exists()
}

/**
 * 设置页写入 → 运行会话热通知的**跨 Activity 投递队列**（ModDX DX-A2）。
 *
 * ## 为什么需要这条队列
 * 写入点在设置页（[com.eratw.port.android.SettingsActivity]，独立 Activity、UI 线程），消费点
 * 是游戏壳里 [GameHost.modRuntimeSession] 指着的运行会话（私有字段、VM 线程）。设置页拿不到
 * 会话引用，也不许在 UI 线程直接调 mod 回调（`onSettingsChanged` 是 VM 线程回调）。本件照
 * `ModUiSettingsEntry` / `HotConfigChannel` 的「进程级显式通道」形态做**只存数据不存引用**的
 * 队列：写入端 enqueue 后立即返回，消费端在 VM 线程页边界（`GameHost.onTurnBoundary` 的
 * `modRuntimeSession?.onTurnBoundary()` 之后）drain 并转发
 * [ModRuntimeSession.notifySettingsChanged]。因为不持有任何宿主/会话引用，双壳交接窗口里
 * 不存在悬挂引用，也就**不需要** `ModUiSettingsEntry` 那套 install/uninstall 认主装卸。
 *
 * ## 线程与生命周期口径
 * - 写入端：设置页 UI 线程（`ModSettingsSection.persist` 写盘成功后 enqueue；**写失败不 enqueue**）。
 * - 消费端：VM 线程页边界（drain 取走即清，消费失败也不回流）。与交互输入漏斗
 *   （`ModInteractionWire.notePlayerInput` → `consumeOnBoundary`）同拍同形态。
 * - 会话收口：`GameHost` 退出清场（`modRuntimeSession = null` 处）调 [clear] 丢弃未兑现积压，
 *   防止陈旧键漏进下一局的首边界。
 * - 已知边界：**完全没有会话期间**的写入会一直积压到下一局首边界才被 drain（无 attach 侧
 *   清场缝，GameHost attach 区本波归 DX-C 所有）。语义后果仅是冗余重述：mod 在 install 时
 *   经 `host.settings` 读到的已是盘上新值，补发的 `onSettingsChanged` 不携带旧值对比、不改
 *   任何状态。若组长裁定要掐掉，把 [clear] 挂到 attach 之后即可。
 *
 * 容量口径：一条积压 = 一次玩家写盘动作（每次伴随一个 Toast），自然有界，不做显式上限。
 */
object ModSettingsNotifyChannel {

    private val lock = Any()

    /** packageId → 已变更键（插入序去重；guard = [lock]）。 */
    private var pending: LinkedHashMap<String, LinkedHashSet<String>> = LinkedHashMap()

    /**
     * 设置页写入成功后投递一批变更键。键是否本包声明过的终审在消费端
     * （[ModRuntimeSession.notifySettingsChanged] 按会话内声明快照过滤），这里只按序收。
     */
    fun enqueue(packageId: String, keys: Collection<String>) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            val bucket = pending.getOrPut(packageId) { LinkedHashSet() }
            bucket.addAll(keys)
        }
    }

    /** 取走全部积压（取走即清）；每包键保持首投递序、去重。无积压返回空表。 */
    fun drain(): List<Pair<String, List<String>>> = synchronized(lock) {
        val taken = pending
        pending = LinkedHashMap()
        taken.map { (packageId, keys) -> packageId to keys.toList() }
    }

    /** 丢弃全部积压（会话收口用；见类 KDoc 生命周期口径）。 */
    fun clear() = synchronized(lock) { pending.clear() }

    /** 测试观察面：当前积压的包数。 */
    internal fun pendingPackageCount(): Int = synchronized(lock) { pending.size }
}

/**
 * `mods/rt2/<packageId>.settings` 的纯编解码（无 IO，可单测逐字节断言）。
 *
 * 格式与 fail-safe 口径见 [ModSettingsStore] 类 KDoc；本对象把「IO 与解析」拆开是为了让
 * 解析路径能被逐类坏输入点测，而不必每次落盘。
 */
internal object ModSettingsCodec {

    /** 文件头（格式版本；未来不兼容升级时改它，旧文件按「坏文件」回退默认）。 */
    private const val MAGIC = "RT2SETTINGS/1"

    /** hex 数字表（小写，写侧固定；读侧大小写都收）。 */
    private const val HEX_DIGITS = "0123456789abcdef"

    private const val HEX_RADIX_BITS = 4
    private const val HEX_NIBBLE_MASK = 0xF

    /** 字母 hex 位相对 'a' 的偏移（0..5 落到 10..15）。 */
    private const val HEX_LETTER_BASE = 10

    /** 编码整份文件内容（确定性：头 + 键排序后的 `hex=hex` 行，LF 收口）。 */
    fun encode(values: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append(MAGIC).append('\n')
        for (key in values.keys.sorted()) {
            sb.append(hexOf(key.toByteArray(StandardCharsets.UTF_8)))
            sb.append('=')
            sb.append(hexOf(values.getValue(key).toByteArray(StandardCharsets.UTF_8)))
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 解析文件内容；任何不合法返回空表（fail-safe，见 [ModSettingsStore] 类 KDoc 第 3 条）。 */
    fun parse(text: String): Map<String, String> {
        val lines = text.split('\n')
        val wellFormed = lines.size >= 2 && lines.first() == MAGIC && lines.last() == ""
        val parsed = if (wellFormed) lines.subList(1, lines.size - 1).mapNotNull { parseLine(it) } else emptyList()
        val unique = parsed.map { it.first }.toSet().size == parsed.size
        return if (wellFormed && parsed.size == lines.size - 2 && unique) {
            LinkedHashMap<String, String>().apply { for ((key, value) in parsed) put(key, value) }
        } else {
            emptyMap()
        }
    }

    /** 解析一行 `hex(key)=hex(value)`；格式坏返回 null（空 hex = 空串，合法）。 */
    private fun parseLine(line: String): Pair<String, String>? {
        val eq = line.indexOf('=')
        val key = if (eq > 0) decodeText(line.substring(0, eq)) else null
        val value = if (eq > 0) decodeText(line.substring(eq + 1)) else null
        return if (key != null && value != null) key to value else null
    }

    private fun hexOf(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt()
            sb.append(HEX_DIGITS[(v shr HEX_RADIX_BITS) and HEX_NIBBLE_MASK])
            sb.append(HEX_DIGITS[v and HEX_NIBBLE_MASK])
        }
        return sb.toString()
    }

    /** hex → UTF-8 文本；非法 hex / 非法 UTF-8 返回 null（调用方判坏文件）。 */
    private fun decodeText(hex: String): String? = decodeHex(hex)?.let { bytes ->
        runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }

    private fun decodeHex(hex: String): ByteArray? {
        val digits = hex.mapNotNull { hexDigit(it) }
        if (hex.length % 2 != 0 || digits.size != hex.length) return null
        val out = ByteArray(digits.size / 2)
        for (i in out.indices) {
            out[i] = ((digits[i * 2] shl HEX_RADIX_BITS) or digits[i * 2 + 1]).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + HEX_LETTER_BASE
        in 'A'..'F' -> c - 'A' + HEX_LETTER_BASE
        else -> null
    }
}
