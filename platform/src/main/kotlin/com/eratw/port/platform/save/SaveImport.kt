package com.eratw.port.platform.save

import com.eratw.port.core.data.save.SaveFormat
import com.eratw.port.core.fn.EraDataState
import com.eratw.port.core.fn.SaveDataCheck
import com.eratw.port.core.fn.SaveFileKind
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveDataProbe
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
 * 只有 `saveNN.sav` 本体。`dat/` 下的 `chara_*.dat` / `var_*.dat` 是 `SAVECHARA` 族的
 * **角色/变量转储**（spec: `save-format.md` §2.1 表 + `VariableEvaluator.cs:2039-2047`，
 * 本作只用在 fromEN 的模块化立绘更新流程上，出货语料的 `dat/` 是空目录），不是存档槽的零件，
 * 导入流程一律不碰。
 *
 * `global.sav` 是**跨槽共享的单文件**（`getSaveDataPathG()` 不吃下标，
 * `SaveConfig.globalPath()` 同源），里面装 `IsGlobal` 变量与 `RANDDATA`
 * （`save-format.md` §2.2 第 7 条与 §2.2 末条：槽本体写侧就排除了 `IsGlobal`）。
 * 它按文件名识别后同样能导 —— 但它是**另一格**，与某个槽不是一次导入的两个必需件。
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

    /** 全局档的固定文件名（`getSaveDataPathG`，`VariableEvaluator.cs:2018-2021`）。 */
    private const val GLOBAL_NAME = "global.sav"

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

    /**
     * 判定一个被选中的文件。
     *
     * @param fileName  选中文件的显示名（只认扩展名与槽号，路径一律不参与拼路径 —— 防越界）
     * @param bytes     文件字节
     * @param config    存档层口径，只消费它的**路径规则**（`UseSaveFolder` 决定落 `sav/` 还是根、
     *                  `datDir` 不参与本流程）。字节是原样拷贝的：容器是二进制还是文本、压没压缩，
     *                  都由文件自己说了算，本类不改写也不重编码。
     * @param gameBase  本游戏 GAMEBASE 常量；读不到时传 null，见类 KDoc 的「校验口径」段
     * @param slotCount `SAVENOS` 的生效值（`Config.SaveDataNos`，夹在 20..80）—— 只在
     *                  「文件名不含槽号」时用来找空位
     * @param occupied  读目标格现有字节（null = 空格）；由调用方接真盘
     */
    fun plan(
        fileName: String,
        bytes: ByteArray,
        config: SaveConfig,
        gameBase: GameBaseData?,
        slotCount: Int,
        occupied: (String) -> ByteArray?,
    ): Plan {
        val name = fileName.trim()
        if (!name.lowercase(Locale.ROOT).endsWith(".sav")) {
            return Reject("只能导入 .sav 存档文件")
        }
        if (bytes.isEmpty()) return Reject(SaveDataProbe.MES_CORRUPTED)
        if (bytes.size > MAX_SAVE_BYTES) {
            return Reject("文件超过 ${MAX_SAVE_BYTES / (1024 * 1024)} MB，不像存档")
        }

        val global = name.lowercase(Locale.ROOT) == GLOBAL_NAME
        val kind = if (global) SaveFileKind.GLOBAL else SaveFileKind.NORMAL
        val check = probe(bytes, kind, gameBase)
        if (check.state != EraDataState.OK) return Reject(check.mes)

        val targetRel: String
        val slotLabel: String
        if (global) {
            targetRel = config.globalPath()
            slotLabel = "全局存档"
        } else {
            // 槽号优先按文件名认（PC 上就是把 sav 目录里的这一格搬过去），认不出来才找空位。
            // 位数超过 Long 的槽号同样是「编号太大」，不能掉进「找空位」分支：那会把一个
            // 明确指定了槽位的文件静默塞进别的格（玩家以为覆盖了 save12，实际写进了 save0）。
            val named = SLOT_NAME.matchEntire(name)?.groupValues?.get(1)
            val no = when {
                named != null -> {
                    // 与函数 CHKDATA 的 ArgIsTooLarge 同一道界（FunctionMethodCreator.cs:3531-3534）。
                    val value = named.toLongOrNull() ?: return Reject("存档位编号太大")
                    if (value > Int.MAX_VALUE) return Reject("存档位编号太大")
                    value.toInt()
                }
                else -> (0 until slotCount.coerceAtLeast(1)).firstOrNull {
                    occupied(config.normalPath(it)) == null
                } ?: return Reject("存档位已经满了，没有空位")
            }
            targetRel = config.normalPath(no)
            slotLabel = "存档位 $no"
        }

        val existing = occupied(targetRel)
        return Place(
            targetRel = targetRel,
            slotLabel = slotLabel,
            mes = check.mes,
            occupied = existing != null,
            occupiedMes = existing?.let { probe(it, kind, gameBase).takeIf { c -> c.state == EraDataState.OK }?.mes },
        )
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
