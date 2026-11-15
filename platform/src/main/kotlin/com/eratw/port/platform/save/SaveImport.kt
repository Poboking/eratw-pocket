package com.eratw.port.platform.save

import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveDataCheck
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveDataProbe
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.save.SidecarSaveInventory
import com.eratw.port.core.vars.GameBaseData
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * 「导入已有存档」的落位决策与落盘（安卓设置页用；引擎侧不改动）。
 *
 * ## 为什么决策与落盘要拆成两步
 *
 * [plan] 是纯函数（给定文件名 + 字节 + 口径，不碰盘、不碰 Android API），[commit] 才写文件。
 * 于是「这个文件能不能进、进到哪一格、那一格里已经有谁」全部可以在 JVM 单测里钉死，
 * 不需要真机；设置页只负责把用户选中的东西喂进来、再把结果原样上屏。
 *
 * ## 一个槽到底需要哪几个文件
 *
 * 只有 `saveNN.sav` 本体与它的 `.rt2` 侧车（RT2 C 车：.sav + .rt2 = 一个逻辑存档单元，
 * 侧车落位路径 = 槽本体路径 + `.rt2`）。`dat/` 下的 `chara_*.dat` / `var_*.dat` 是 `SAVECHARA` 族的
 * **角色/变量转储**（spec: `save-format.md` §2.1 表 + `VariableEvaluator.cs:2039-2047`，
 * 本作只用在 fromEN 的模块化立绘更新流程上，出货语料的 `dat/` 是空目录），不是存档槽的零件，
 * 导入流程一律不碰。
 *
 * `global.sav` 是**跨槽共享的单文件**（`getSaveDataPathG()` 不吃下标，
 * `SaveConfig.globalPath()` 同源），里面装 `IsGlobal` 变量与 `RANDDATA`
 * （`save-format.md` §2.2 第 7 条与 §2.2 末条：槽本体写侧就排除了 `IsGlobal`）。
 * 它按文件名识别后同样能导 —— 但它是**另一格**，与某个槽不是一次导入的两个必需件。
 *
 * ## 配对判据（MODGC2 裁定 1：复制入相位）
 *
 * `.rt2` 侧车能不能落位，取决于**同槽本体是否可配**：真盘上已有同槽 `.sav`，或同一次多选里
 * 本体也判了可落位（[planBatch] 两趟判定，与选择顺序无关）。都不成立 ⇒ [Reject]，理由
 * [REASON_SIDECAR_WITHOUT_BODY] —— 不替玩家造一个空本体，也不单落半套（半套侧车读档时
 * 会配到别的本体上，比不收更坏）。导出面（`SaveExport`）用同一条判据的另一半：本体不在包
 * 里就不收侧车。落盘时同一单元的成员**本体先、侧车随后**，本体写失败则整单元不写。
 *
 * ## 校验口径与游戏内保持一致
 *
 * 头探测一律走 [SaveDataProbe.checkBytes]（`VariableEvaluator.cs:2159-2217` 的分支序），
 * 拒绝文案直接复用原版 zhs 常量（`MES_CORRUPTED` / `MES_DIFFERENT_GAME` /
 * `MES_DIFFERENT_VERSION`，lang/emuera.zhs.xml:326-329）—— 设置页说的「能不能读」
 * 和游戏内 `CHKDATA` 说的必须是同一句话，不允许两边各判一次。
 *
 * [gameBase] 为 null 表示**调用方读不到自己的 GAMEBASE**（换目录装/CSV 缺失），
 * 此时跳过游戏码与版本两道闸，只做「是不是存档 + 类别对不对」—— 判定权交回给读档那一刻的
 * 引擎（`LOADDATA` 侧仍有同一道 `GameBaseGate`），这里不伪造「属于另一个游戏」。
 *
 * ## `@Suppress("NewApi")` 的理由（不是掩盖问题）
 *
 * [commit] 用 `java.nio.file`（`Path` / `Files` / `StandardCopyOption`，平台 API 26 起才有）。
 * 本模块 `minSdk = 21`，但**消费方 `:app` 开了核心库脱糖**，且用的是 `desugar_jdk_libs_nio`
 * 变体（`app/build.gradle.kts` 的 `isCoreLibraryDesugaringEnabled = true` +
 * `com.android.tools:desugar_jdk_libs_nio:2.1.5`）—— NIO 文件 API 正是该变体补的那一块，
 * 打包后 Android 5.0 起可用。lint 的 `NewApi` 按模块判、看不到消费方的脱糖配置 ⇒ 误报。
 * 只在「真的靠脱糖」的这两个类上抑制，别处仍按默认口径查。
 */
@Suppress("NewApi")
object SaveImport {

    /**
     * 单个体积上限。真机 OOM 取证（BOARD 2026-10-27：512 MB 堆顶、LOS 347 MB）之后加的闸：
     * 本作实档 60-80 KB、`global.sav` 486 B，选错文件（视频/整包）不该先把几十 MB 吸进堆里。
     */
    const val MAX_SAVE_BYTES: Int = 8 * 1024 * 1024

    /** 存档位名的识别（`SaveDataProbe.saveName` 的 `save%02d.sav` 反解，大小写不敏感）。 */
    private val SLOT_NAME = Regex("(?i)save(\\d+)\\.sav")

    /** 存档槽 .rt2 侧车名的识别（RT2 C 车：同名加后缀；槽号同法反解）。 */
    private val SLOT_SIDECAR_NAME = Regex("(?i)save(\\d+)\\.sav\\.rt2")

    /** 全局档的固定文件名（`getSaveDataPathG`，`VariableEvaluator.cs:2018-2021`）。 */
    private const val GLOBAL_NAME = "global.sav"

    /** 全局档 .rt2 侧车的固定文件名。 */
    private const val GLOBAL_SIDECAR_NAME = "global.sav.rt2"

    /**
     * 侧车文件名的尾巴（本体名 + `.rt2`，后缀常量单源在 [SidecarSaveInventory.CONTAINER_SUFFIX]）。
     */
    private const val SIDECAR_TAIL = ".sav" + SidecarSaveInventory.CONTAINER_SUFFIX

    /**
     * 「侧车没有同槽本体」的判读原话（MODGC2 裁定 4：导出跳过与导入拒收**共用同一句**）。
     *
     * 中文原文上屏；设置页的 `SettingsStrings.saveReason` 按**这个常量**出三语译文
     * （禁止两处各写一份字面量，否则一边改了另一边静默漂移）。
     */
    const val REASON_SIDECAR_WITHOUT_BODY = "缺少同槽的 .sav 本体"

    /** [plan] 的结果。 */
    sealed interface Plan

    /**
     * 可以落位。
     *
     * @property targetRel   相对游戏根的落位路径（与引擎读的同一个口径：[SaveConfig.normalPath] /
     *                       [SaveConfig.globalPath]，`UseSaveFolder` 决定带不带 `sav/` 前缀）
     * @property slotLabel   给玩家看的位置名（「存档位 7」「全局存档」）
     * @property mes         这份存档自己写的备注（原版 `RESULTS:0` 的那个文本）
     * @property occupied    目标格里**已经有文件**；true 就必须先确认再落位。
     *                       判据是存在性而不是「能不能读」—— 那一格里躺着一个读不动的坏档时，
     *                       它仍然是玩家的档，不能因为读不出备注就静默盖掉。
     * @property occupiedMes 已有那一份的备注；读不出（坏档 / 不是存档）时为 null
     */
    data class Place(
        val targetRel: String,
        val slotLabel: String,
        val mes: String,
        val occupied: Boolean,
        val occupiedMes: String?,
    ) : Plan

    /** 不收。[reason] 就是上屏的原话，设置页不再改写。 */
    data class Reject(val reason: String) : Plan

    /** [planBatch] 的一项：一次多选里的一个文件（显示名 + 字节）。 */
    class Item(val fileName: String, val bytes: ByteArray)

    /**
     * 一次判定的**公共口径**（[plan] / [planBatch] 的入参包；七件散参数凑一包，调用方一处构造）。
     *
     * @property config 存档层口径，只消费它的**路径规则**（`UseSaveFolder` 决定落 `sav/` 还是根、
     *   `datDir` 不参与本流程）
     * @property gameBase 本游戏 GAMEBASE 常量；读不到时传 null，见类 KDoc 的「校验口径」段
     * @property slotCount `SAVENOS` 的生效值（`Config.SaveDataNos`，夹在 20..80）—— 只在
     *   「文件名不含槽号」时用来找空位
     * @property occupied 读目标格现有字节（null = 空格）；由调用方接真盘
     * @property bodyPresent 同槽本体是否**可配**（`.rt2` 侧车专用，MODGC2 裁定 1）：缺省 = 真盘上有；
     *   [planBatch] 传「在盘 **或** 本批本体已判可落位」。只看本体落位路径，不读本体字节 ——
     *   本体能不能读由它自己那一条判，与侧车无关。
     */
    class Context(
        val config: SaveConfig,
        val gameBase: GameBaseData?,
        val slotCount: Int,
        val occupied: (String) -> ByteArray?,
        val bodyPresent: (String) -> Boolean = { rel -> occupied(rel) != null },
    )

    /**
     * 判定一个被选中的文件。
     *
     * @param fileName 选中文件的显示名（只认扩展名与槽号，路径一律不参与拼路径 —— 防越界）
     * @param bytes    文件字节（原样拷贝：容器是二进制还是文本、压没压缩，都由文件自己说了算，
     *                 本类不改写也不重编码）
     * @param context  落位口径（见 [Context]）
     */
    fun plan(fileName: String, bytes: ByteArray, context: Context): Plan {
        val name = fileName.trim()
        val lower = name.lowercase(Locale.ROOT)
        // RT2 C 车：.sav 与它的 .rt2 侧车一并认（后者是同一个逻辑存档单元的另一半）。
        val sidecar = lower.endsWith(SIDECAR_TAIL)
        if (!lower.endsWith(".sav") && !sidecar) {
            return Reject("只能导入 .sav 存档文件")
        }
        if (bytes.isEmpty()) return Reject(SaveDataProbe.MES_CORRUPTED)
        if (bytes.size > MAX_SAVE_BYTES) {
            return Reject("文件超过 ${MAX_SAVE_BYTES / (1024 * 1024)} MB，不像存档")
        }

        val global = lower == GLOBAL_NAME || lower == GLOBAL_SIDECAR_NAME
        val kind = if (global) SaveFileKind.GLOBAL else SaveFileKind.NORMAL
        val check = if (sidecar) probeSidecar(bytes) else probe(bytes, kind, context.gameBase)
        if (check.state != EraDataState.OK) return Reject(check.mes)

        val targetRel: String
        val slotLabel: String
        if (global) {
            targetRel = if (sidecar) context.config.globalPath() + ".rt2" else context.config.globalPath()
            slotLabel = "全局存档"
        } else {
            // 槽号优先按文件名认（PC 上就是把 sav 目录里的这一格搬过去），认不出来才找空位。
            // 位数超过 Long 的槽号同样是「编号太大」，不能掉进「找空位」分支：那会把一个
            // 明确指定了槽位的文件静默塞进别的格（玩家以为覆盖了 save12，实际写进了 save0）。
            val named = SLOT_NAME.matchEntire(name)?.groupValues?.get(1)
                ?: SLOT_SIDECAR_NAME.matchEntire(name)?.groupValues?.get(1)
            val no = when {
                named != null -> {
                    // 与函数 CHKDATA 的 ArgIsTooLarge 同一道界（FunctionMethodCreator.cs:3531-3534）。
                    val value = named.toLongOrNull() ?: return Reject("存档位编号太大")
                    if (value > Int.MAX_VALUE) return Reject("存档位编号太大")
                    value.toInt()
                }
                else -> (0 until context.slotCount.coerceAtLeast(1)).firstOrNull {
                    context.occupied(context.config.normalPath(it)) == null
                } ?: return Reject("存档位已经满了，没有空位")
            }
            targetRel = context.config.normalPath(no) + if (sidecar) ".rt2" else ""
            slotLabel = "存档位 $no"
        }

        // MODGC2 裁定 1：侧车不单独落位 —— 同槽本体必须已在盘，或本批本体已判可落位。
        // 半套配对（只有 .rt2、没有 .sav）读档时配到的是别的本体甚至空槽，比不收更坏。
        if (sidecar && !context.bodyPresent(bodyTargetOf(targetRel))) {
            return Reject(REASON_SIDECAR_WITHOUT_BODY)
        }

        val existing = context.occupied(targetRel)
        return Place(
            targetRel = targetRel,
            slotLabel = slotLabel,
            mes = check.mes,
            occupied = existing != null,
            occupiedMes = existing?.let {
                (if (sidecar) probeSidecar(it) else probe(it, kind, context.gameBase))
                    .takeIf { c -> c.state == EraDataState.OK }?.mes
            },
        )
    }

    /**
     * 整批判定（MODGC2 裁定 1）：**先判本体、再判侧车**，结果与玩家选文件的顺序无关。
     *
     * 为什么不能逐个 [plan] 就完事：要不要收一个 `.rt2` 取决于**同槽本体在这一批里是否也判了
     * 可落位**。多选时 SAF 给的顺序由选择/厂商实现决定（解压出来的文件按名字排也可能先给
     * `save03.sav.rt2` 再给 `save03.sav`），逐条判会把先到的侧车判成孤儿。
     *
     * 两趟判的边界很清楚：
     * - 第一趟只判**本体**（名字不以 `.rt2` 结尾），判成 [Place] 的落位路径进批内集合；
     * - 第二趟只判**侧车**，`bodyPresent` = 真盘上有 **或** 批内本体已判可落位。
     *
     * 批内本体被拒（坏档、体积超限、编号太大）⇒ 它不进批内集合 ⇒ 其侧车同批被拒：**不会**
     * 出现「本体没进来、侧车进来了」的半套落位。
     *
     * 单条判读抛异常不许拖死整批（设置页既有纪律）：逐条 `catch (Exception)` 收成
     * [Reject]（`Error` 照旧上抛给宿主崩溃处理，不伪装成判读失败）。
     *
     * @param items 一次多选的全部文件（顺序 = 上屏顺序）
     * @param context 落位口径（见 [Context]）；[Context.bodyPresent] 在第二趟会被替换成
     *   「在盘 or 批内已判可落位」
     * @return 与 [items] 一一对应的判定
     */
    fun planBatch(items: List<Item>, context: Context): List<Plan> {
        val results = arrayOfNulls<Plan>(items.size)
        val batchBodies = HashSet<String>()
        val onDisk = Context(context.config, context.gameBase, context.slotCount, context.occupied)

        // 第一趟：本体。侧车一律跳过（它们不参与「批内本体」集合）。
        items.forEachIndexed { index, item ->
            if (isSidecarName(item.fileName)) return@forEachIndexed
            val judged = judge(item, onDisk)
            results[index] = judged
            if (judged is Place) batchBodies += judged.targetRel
        }

        // 第二趟：侧车。配对判据 = 在盘 or 本批已判可落位。
        val withBatch = Context(context.config, context.gameBase, context.slotCount, context.occupied) { rel ->
            context.occupied(rel) != null || rel in batchBodies
        }
        items.forEachIndexed { index, item ->
            if (!isSidecarName(item.fileName)) return@forEachIndexed
            results[index] = judge(item, withBatch)
        }

        return items.indices.map { index ->
            checkNotNull(results[index]) { "planBatch 漏判第 $index 项（本体/侧车两趟必须覆盖全部项）" }
        }
    }

    /** 选中的文件是不是 `.rt2` 侧车（`saveNN.sav.rt2` / `global.sav.rt2`）。 */
    fun isSidecarName(fileName: String): Boolean =
        fileName.trim().lowercase(Locale.ROOT).endsWith(SIDECAR_TAIL)

    /**
     * 落位路径 → 它所属逻辑单元的本体落位路径（`sav/save00.sav.rt2` → `sav/save00.sav`）。
     * 设置页按这个 key 把本体与侧车归成一个单元（一起确认、一起落盘/取消）。
     */
    fun bodyTargetOf(targetRel: String): String =
        if (targetRel.lowercase(Locale.ROOT).endsWith(SidecarSaveInventory.CONTAINER_SUFFIX)) {
            targetRel.dropLast(SidecarSaveInventory.CONTAINER_SUFFIX.length)
        } else {
            targetRel
        }

    /**
     * [plan] 的单条判定 + 异常收口（一个文件的未预期异常不许拖死整批）。
     *
     * 形参名 `expected` 是 detekt 的口径（`TooGenericExceptionCaught` 的
     * `allowedExceptionNameRegex` 放行 `expected*`）：这里的语义确实是「单个文件的判读异常是
     * 预期内的，收成一条拒收」，不是漏抓 —— `Error` 不在 `Exception` 之内，照旧上抛给宿主。
     */
    private fun judge(item: Item, context: Context): Plan = try {
        plan(item.fileName, item.bytes, context)
    } catch (expected: Exception) {
        Reject(expected.message?.takeIf { it.isNotBlank() } ?: SaveDataProbe.MES_CORRUPTED)
    }

    /**
     * 落位：先写同目录的 `.part` 再换名，**半截写不会污染已有槽位**（选中文件读到一半被
     * 系统杀掉 / 盘满时，玩家原来的档还在）。目标已存在时按覆盖处理（调用方已确认过）。
     *
     * 落位前的两道额外闸（都是防御性的，[plan] 生成的 `targetRel` 本来就在根内）：
     *   * **根内约束**：`targetRel` 归一化后必须落在 `root` 之内（`../x`、绝对路径一律 false）。
     *     本函数的第二个调用场景是「用户选的文件名 → 落位」，路径字符串一旦从外部进来，
     *     就只认「归一化后仍以 root 为前缀」这一条。
     *   * **先 sync 再换名**：`close()` 落的是内核页缓存，`rename` 不保证数据已落盘；
     *     换名成功后掉电会留下长度正确但内容为空洞的档。这里先 `fd.sync()`（与
     *     [com.eratw.port.platform.config.ConfigFileStore] 同一纪律），失败即 false、原档不动。
     *
     * 失败只吞 [Exception]：`Error`（OOM / StackOverflow）必须上抛给宿主的崩溃处理，
     * 不能被当成「落盘失败」静默吞掉（BOARD 稳定性纪律）。
     *
     * @return false = 落盘失败（越界路径 / 只读目录 / 盘满 / 换名被拒），调用方按失败上屏，不重试不掩错
     */
    fun commit(root: File, targetRel: String, bytes: ByteArray): Boolean = try {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val dst = rootPath.resolve(targetRel).normalize()
        if (dst == rootPath || !dst.startsWith(rootPath)) {
            false
        } else {
            val dir = dst.parent ?: rootPath
            Files.createDirectories(dir)
            val tmp = dir.resolve(dst.fileName.toString() + ".part")
            try {
                FileOutputStream(tmp.toFile()).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING)
                true
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    } catch (_: Exception) {
        false
    }

    /**
     * .rt2 侧车的头探测：容器能解码即 OK（CRC / 魔数 / 版本闸都在 decode 里）。
     * 绑定三元组的校验在读档那一刻的 restoreBundle —— 导入侧不装，也不伪造。
     */
    private fun probeSidecar(bytes: ByteArray): SaveDataCheck = try {
        SidecarSaveBundle.decode(bytes)
        SaveDataCheck(EraDataState.OK, "Mod 扩展存档（.rt2 侧车）")
    } catch (_: Exception) {
        SaveDataCheck(EraDataState.ETC_ERROR, SaveDataProbe.MES_CORRUPTED)
    }

    /**
     * 头探测。有 GAMEBASE 就走游戏内那条完整链（含两道闸）；没有就退到
     * 「容器能解析 + 类别对得上」，与 [SaveDataProbe.checkBytes] 的前两个分支同形。
     */
    private fun probe(bytes: ByteArray, kind: SaveFileKind, gameBase: GameBaseData?): SaveDataCheck {
        if (gameBase != null) return SaveDataProbe.checkBytes(bytes, kind, gameBase)
        val head = SaveFormat.peek(bytes)
            ?: return SaveDataCheck(EraDataState.ETC_ERROR, SaveDataProbe.MES_CORRUPTED)
        // 原版只在二进制容器里比类别（VariableEvaluator.cs:2196-2201），文本档没有类型可比。
        if (head.binary && head.fileType != kind) {
            return SaveDataCheck(EraDataState.ETC_ERROR, SaveDataProbe.MES_CORRUPTED)
        }
        return SaveDataCheck(EraDataState.OK, head.text)
    }
}
