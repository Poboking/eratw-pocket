package com.eratw.port.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eratw.port.core.config.ConfigClampLabels
import com.eratw.port.core.config.ConfigClampLanguage
import com.eratw.port.core.config.ConfigClampNotice
import com.eratw.port.core.config.ConfigGroup
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigPageLabels
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.SettingsConstraint
import com.eratw.port.core.config.SettingsEntry
import com.eratw.port.core.config.SettingsGroupView
import com.eratw.port.core.config.SettingsKind
import com.eratw.port.core.config.SettingsModel
import com.eratw.port.core.config.SettingsRejectReason
import com.eratw.port.core.config.SettingsSaveImpact
import com.eratw.port.core.config.SettingsSetResult
import com.eratw.port.core.data.csv.CsvLoader
import com.eratw.port.core.data.csv.GameBaseLoader
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.platform.config.SettingsStore
import com.eratw.port.platform.save.SaveImport
import com.eratw.port.platform.storage.GameStorage
import com.eratw.port.render.font.BundledFontFamilies
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 设置页（Compose 版）：把 [SettingsModel] 的 123 个条目按「文件 → 原版设置页 → 条目」铺成卡片列表，
 * 写回走 [SettingsStore]（保留未知键 / 注释 / 行序 + UTF-8 BOM 原子写）。
 *
 * ## 三组的读写口径（与改 Compose 前逐条一致）
 *   * `GAME`（`emuera.config` / `default.config` / `fixed.config` 的唯一组，100 项，spec: `ConfigData.cs:45-151`）
 *     —— 可编辑；每次写入都走 [SettingsModel.set] 的同一条校验链，被拒绝时弹 Toast 并在输入框里
 *     回显模型现值、给出原版拒因；
 *   * `DEBUG`（`debug.config`，7 项，spec: `ConfigData.cs:152-158`）与
 *     `REPLACE`（`_Replace.csv`，16 项，spec: `ConfigData.cs:159-174`）—— **只读展示**：
 *     不接任何 [SettingsModel.set]。原版这两组没有从 `emuera.config` 设置页写回的路径
 *     （保存只遍历 `configArray`，spec: `ConfigData.cs:430`）。
 *
 * ## 中文化
 *   条目标题的优先级：①「显示原始配置键名」开关打开 ⇒ `emuera.config` 的英文键原文；
 *   ② [ConfigPageLabels] 的简体中文译文（原版 `lang.emuera-zhs.xml`）；③ app 侧兜底中文表
 *   [ZH_CONFIG_LABELS]（补原版 zhs 缺译的 6 条 + 不在任何设置页上的 25 条，共 31 条）；
 *   ④ 都没覆盖 ⇒ 回落配置键原文（日文），不编造。页标题一律是中文页名（见 [GAME_PAGE_SPECS]）。
 *
 * ## 生效时机（M5-CONFIG 的运行时重载口径）
 *   改动只落在内存模型里，点「保存」才写 `<gameRoot>/emuera.config`；保存前退出即丢弃。
 *   写盘之后按 [com.eratw.port.core.config.ConfigApplyMode] 分两桶处理，**不再一刀切重启**：
 *   * 热更键（[com.eratw.port.core.config.ConfigApplyMode.HOT_KEYS]）⇒ 经 [HotConfigChannel] 推进
 *     运行中会话的条目表，同一局的下一帧就读到新值（等价原版 `ConfigDialog.cs:1107-1109` 的活推）；
 *   * 重启键 ⇒ 只有它们才需要重开会话，等价原版 `SaveReboot` → `Reboot()`
 *     （spec: `ConfigDialog.cs:672-675`、`MainWindow.cs:1066-1069`、`:1022-1026`）。
 *   于是「保存并重启」在「本次改动全是热更键」时**不重启**（原地刷新即生效），
 *   而「保存」在含重启键时如实提示哪几项要重启。
 *
 *   注意本页控制不了的另一样东西：字号档位（[UiPrefs.fontColumns]）是 `CanvasConsoleView` 的
 *   构造期参数，改了要重建 Activity —— 那条路仍在 [MainActivity] 的 `onActivityResult`
 *   （`recreate()`），不走本表。
 *
 * ## Compose 侧的两个新设置（存 [UiPrefs]，与 `emuera.config` 解耦）
 *   * 字号档位（列数）：110 标准 / 90 大 / 75 特大 / 150 小；
 *   * 一屏一帧开关（缺省开）。
 *   这两项随改随存，不参与「保存」，由装配层从 [UiPrefs] 读取。
 *
 * @see UiPrefs
 * @see HotConfigChannel
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var store: SettingsStore
    private lateinit var model: SettingsModel

    /** 读取诊断（读盘失败 / 解析中止 / 告警 / 文件缺失），只在打开页面时算一次。 */
    private var loadNotice: String? = null

    /**
     * 模型改动版本号 —— [SettingsModel] 不是可观察对象，Compose 侧靠它在每次成功写入 / 重置 /
     * 保存之后重新取 `model.groups`。只在 UI 线程写（设置页所有回调都在 UI 线程）。
     */
    private var revision by mutableIntStateOf(0)

    /** 「显示原始配置键名」开关。默认关：与中文界面的默认阅读习惯一致。 */
    private var englishLabels by mutableStateOf(false)

    /**
     * 夹取提示的显示语种：页面固定文案是简体中文，故取简中；WINAPI 那条原版 zhs 文件没有译文，
     * 按 `TranslatableString.Text` 的回落链显示日语原文（spec: `Lang.cs:23-33`）。
     */
    private val clampLanguage = ConfigClampLanguage.CHINESE_SIMPLIFIED

    /** 游戏根：导入存档的落位基准（与 [GameStorage] 选的根同源，进程内只有一个）。 */
    private lateinit var gameRootFile: File

    // -----------------------------------------------------------------
    // 导入已有存档（页面上唯一的一件事：选文件 → 交给 SaveImport 判 → 落盘 → 把结果上屏）
    // -----------------------------------------------------------------

    /** 读盘/写盘进行态（后台线程跑着的时候按钮不放行第二次）。 */
    private var importing by mutableStateOf(false)

    /** 已经发生的事，一行一条（最新在前，最多留 [IMPORT_LINES_MAX] 条）。 */
    private var importLines by mutableStateOf<List<ImportLine>>(emptyList())

    /** 等着「覆盖」确认的那些项；空表 = 没有对话框。 */
    private var overwritePending by mutableStateOf<List<PendingImport>>(emptyList())

    /**
     * 系统文件选择器（SAF `ACTION_OPEN_DOCUMENT`，多选）。
     *
     * 注册必须落在 Activity 进入 STARTED 之前 ⇒ 只能是成员属性（AndroidX 的硬要求）。
     * 做成多选而不是「一次挑一个」：一个存档位是一格、`global.sav` 是另一格，
     * 让玩家一次圈完，页面就不需要写「你先导这个再导那个」那种话。
     * 不做权限申请：`ACTION_OPEN_DOCUMENT` 的读授权由系统给，目标目录又是应用私有目录。
     */
    private val pickSaveFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (!uris.isNullOrEmpty()) importSaves(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameRootFile = GameStorage(this).gameRoot()
        store = SettingsStore(File(gameRootFile, "emuera.config"))
        val loaded = store.load()
        model = loaded.model
        loadNotice = when {
            loaded.error != null -> "读取失败：${loaded.error?.message}"
            loaded.abortedAtLine != null ->
                "第 ${loaded.abortedAtLine} 行解析失败，之后 ${loaded.droppedLines} 行未读"
            loaded.warnings.isNotEmpty() ->
                "解析告警 ${loaded.warnings.size} 条（首条：${loaded.warnings.first().message}）"
            !loaded.fileExists -> "未找到 emuera.config，当前显示缺省值"
            else -> null
        }
        setContent {
            SettingsTheme {
                // 在内容作用域里读这两个状态：模型写入后整页重组（见 revision 的 KDoc）。
                val rev = revision
                val english = englishLabels
                val groups = remember(rev) { model.groups.filter { it.entries.isNotEmpty() } }
                val notices = remember(rev) { model.clampNotices().map(::clampNoticeLine) }
                val dirty = remember(rev) { model.dirty }
                SettingsScreen(
                    filePath = store.file.absolutePath,
                    loadNotice = loadNotice,
                    clampNotices = notices,
                    dirty = dirty,
                    englishLabels = english,
                    onEnglishLabelsChange = { englishLabels = it },
                    groups = groups,
                    onApplyText = ::applyText,
                    onApplyBool = ::applyBool,
                    displayOf = ::displayOf,
                    onResetAll = ::resetAll,
                    onSave = ::save,
                    onSaveAndRestart = ::saveAndRestart,
                    onBack = { finish() },
                    importing = importing,
                    importLines = importLines,
                    overwritePending = overwritePending,
                    onPickSaveFiles = { pickSaveFiles.launch(arrayOf("*/*")) },
                    onOverwriteConfirm = ::commitOverwrite,
                    onOverwriteCancel = ::discardOverwrite,
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // 模型写入（全部在 UI 线程；失败只提示、不动模型）
    // -----------------------------------------------------------------

    /** 文本 / 数值 / 枚举的提交：走 [SettingsModel.set] 的校验链，拒绝时 Toast 原版拒因。 */
    private fun applyText(id: String, raw: String): SettingsSetResult {
        val result = model.set(id, raw)
        if (result is SettingsSetResult.Rejected) {
            rejectToast(result)
        } else {
            revision++
        }
        return result
    }

    /** 勾选框 / 开关的提交：走 [SettingsModel.set] 的布尔形态（只接受 `bool` 条目）。 */
    private fun applyBool(id: String, value: Boolean): SettingsSetResult {
        val result = model.set(id, value)
        if (result is SettingsSetResult.Rejected) {
            rejectToast(result)
        } else {
            revision++
        }
        return result
    }

    /**
     * 「全部重置」—— 只重置可编辑的 `GAME` 组：`DEBUG` / `REPLACE` 是只读组，不参与写回
     * （原版保存路径只遍历 `configArray`，spec: `ConfigData.cs:430`）。
     */
    private fun resetAll() {
        val n = model.resetAllEditable()
        revision++
        toast("已恢复缺省 $n 项（尚未写盘）")
    }

    /**
     * 「保存」：把模型合并写回 `emuera.config`，再按作用域表处理生效面。
     *
     * 与改 Compose 前的差别只在输入框提交时机：旧版行内 `EditText` 要显式 `clearFocus()`
     * 才把焦点里的值并进模型，本版一律用 [AlertDialog] 编辑，点「确定」即已提交，
     * 因此保存前不再需要清焦点这一步（校验链与写盘失败口径不变）。
     *
     * **影响面必须在 [SettingsStore.save] 之前算**：写盘成功会 [SettingsModel.markClean]，
     * 之后 [SettingsModel.changedKeys] 恒为空表。
     */
    private fun save() {
        val impact = model.pendingImpact()
        if (!store.save(model)) {
            toast("写盘失败，原文件未改动")
            revision++
            return
        }
        val hot = pushHot(impact)
        toast(saveSummary(impact, hot))
        revision++
    }

    /**
     * 「保存并重启游戏」＝ 原版 `ConfigDialogResult.SaveReboot`（spec: `ConfigDialog.cs:672-678`
     * 写盘 → `MainWindow.cs:1066-1069` 的 `Reboot()`）：先照 `SaveConfig()` 落盘，成功则以
     * [EXTRA_RESTART_GAME] 结果交回 [MainActivity] 拆会话重开；写盘失败不重启（原文件未改动）。
     *
     * 表驱动的例外：本次改动**全是热更键**且热更真的落地了，就不给壳发重启信号 ——
     * 原地刷新已经生效，重开会话只是白丢当前进度。含重启键、没有任何改动、或当前没有
     * 在跑的会话（provider 取不到条目表，热更没落地）时仍照原版重启：用户按的就是
     * 「保存并重启」。
     */
    private fun saveAndRestart() {
        val impact = model.pendingImpact()
        if (!store.save(model)) {
            toast("写盘失败，原文件未改动，未重启")
            revision++
            return
        }
        if (impact.hotOnly) {
            val hot = pushHot(impact)
            if (hot.isNotEmpty()) {
                toast("已写入并热更 ${hot.size} 项，立即生效，未重启")
                revision++
                finish()
                return
            }
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESTART_GAME, true))
        finish()
    }

    /**
     * 把热更键推进运行中会话的条目表；返回真正落地的键（没有在跑的会话时为空表）。
     * 落地口径见 [HotConfigChannel] 与 `SettingsModel.pushHotInto`。
     */
    private fun pushHot(impact: SettingsSaveImpact): List<ConfigKey> =
        if (impact.hot.isEmpty()) emptyList() else HotConfigChannel.push(model)

    /** 保存结果的一行提示（区分「热更已生效」「N 项要重启」「无在跑会话」三种）。 */
    private fun saveSummary(impact: SettingsSaveImpact, hot: List<ConfigKey>): String = when {
        impact.isEmpty -> "没有改动，已重写 ${store.file.name}"
        impact.requiresRestart && hot.isNotEmpty() ->
            "已写入：热更 ${hot.size} 项已生效；${restartHint(impact.restart)} 需重启"
        impact.requiresRestart -> "已写入；${restartHint(impact.restart)} 需重启生效"
        hot.isNotEmpty() -> "已写入并热更 ${hot.size} 项，立即生效"
        else -> "已写入；当前没有在跑的会话，下次进入游戏生效"
    }

    /** 重启键清单的短提示：最多列 3 个中文标签，多的折成「等 N 项」。 */
    private fun restartHint(keys: List<ConfigKey>): String {
        val head = keys.take(3).joinToString("、") { key ->
            ConfigPageLabels.byKey(key)?.zhsLabel ?: ZH_CONFIG_LABELS[key] ?: key.name
        }
        return if (keys.size > 3) "$head 等 ${keys.size} 项" else head
    }

    /** 模型里某个条目的当前显示文本（对话框被拒后回显用）。 */
    private fun displayOf(id: String): String =
        model.entry(id)?.currentValue?.let(::display) ?: ""

    /**
     * 一条夹取提示的显示文本：正文按界面语言取本地化文案；有标题的按原版
     * `Dialog.Show(标题, 正文)` 的形状拼成一行，WINAPI 那条原版没有标题
     * （spec: `Config.cs:458` 走 `MessageBox.Show(正文)`）就只显示正文。
     */
    private fun clampNoticeLine(notice: ConfigClampNotice): String {
        val body = ConfigClampLabels.textFor(notice, clampLanguage)
        val title = ConfigClampLabels.titleFor(notice, clampLanguage) ?: return body
        return "$title：$body"
    }

    private fun rejectToast(result: SettingsSetResult.Rejected) {
        toast("${rejectReasonText(result.reason)}：${result.message}")
    }

    // -----------------------------------------------------------------
    // 导入已有存档（页面只做「选 → 交判 → 落盘 → 上屏」，判定本体在 SaveImport）
    // -----------------------------------------------------------------

    /**
     * 把选中的文件判一遍再落位。
     *
     * 读盘与写盘都在后台线程：设置页打开时游戏会话还活着，UI 线程不能压在 IO 上。
     * 但落位口径（存档目录规则 / 存档位总数 / GAMEBASE）先在 UI 线程取一次快照 ——
     * [model] 不是线程安全的，而且一次多选里各文件必须用同一套口径判。
     * 需要确认覆盖的那几项不落盘，攒给对话框由玩家定。
     */
    private fun importSaves(uris: List<Uri>) {
        if (importing) return
        val config: SaveConfig
        val slotCount: Int
        try {
            val effective = model.effectiveConfig()
            config = SaveConfig(
                systemSaveInBinary = effective.bool(ConfigKey.SystemSaveInBinary),
                zipSaveData = effective.bool(ConfigKey.ZipSaveData),
                useSaveFolder = effective.bool(ConfigKey.UseSaveFolder),
            )
            slotCount = effective.saveDataNos
        } catch (t: Throwable) {
            toast("读不到存档目录的口径（${t.message}），未导入")
            return
        }
        val base = gameBaseOrNull()
        importing = true
        Thread {
            val lines = mutableListOf<ImportLine>()
            val pending = mutableListOf<PendingImport>()
            for (uri in uris) {
                val name = displayNameOf(uri)
                val bytes = readBounded(uri)
                if (bytes == null) {
                    lines += ImportLine("未导入 $name：读不到内容，或它超过体积上限", false)
                    continue
                }
                when (val plan = SaveImport.plan(name, bytes, config, base, slotCount, ::occupiedAt)) {
                    is SaveImport.Reject -> lines += ImportLine("未导入 $name：${plan.reason}", false)
                    is SaveImport.Place -> if (plan.occupied) {
                        pending += PendingImport(plan, bytes, name)
                    } else {
                        lines += placeLine(plan, bytes)
                    }
                }
            }
            runOnUiThread {
                importing = false
                importLines = (lines + importLines).take(IMPORT_LINES_MAX)
                overwritePending = pending
            }
        }.start()
    }

    /** 一条落位的结果行：成功报「哪一格 + 存档自己的备注」，失败照实说没写进去（不掩错、不重试）。 */
    private fun placeLine(place: SaveImport.Place, bytes: ByteArray): ImportLine =
        if (SaveImport.commit(gameRootFile, place.targetRel, bytes)) {
            // 没写备注的档不留一个光秃秃的冒号在后面
            val note = oneLine(place.mes)
            ImportLine(if (note.isEmpty()) "已导入 ${place.slotLabel}" else "已导入 ${place.slotLabel}：$note", ok = true)
        } else {
            ImportLine("${place.slotLabel} 没能写进去（磁盘满或目录不可写）", ok = false)
        }

    /** 对话框选「覆盖」：把攒下的项后台落盘，结果并进同一叠提示行。 */
    private fun commitOverwrite() {
        val items = overwritePending
        overwritePending = emptyList()
        if (items.isEmpty()) return
        importing = true
        Thread {
            val lines = items.map { placeLine(it.place, it.bytes) }
            runOnUiThread {
                importing = false
                importLines = (lines + importLines).take(IMPORT_LINES_MAX)
            }
        }.start()
    }

    /** 对话框选「取消」：那些项一项都不写，玩家原来那一格里的东西原样留着。 */
    private fun discardOverwrite() {
        val lines = overwritePending.map { ImportLine("未导入 ${it.place.slotLabel}：那一格里已经有存档", false) }
        overwritePending = emptyList()
        if (lines.isNotEmpty()) importLines = (lines + importLines).take(IMPORT_LINES_MAX)
    }

    /**
     * 本游戏的 GAMEBASE 常量（判「这档是不是这游戏的」）。
     *
     * 读不到就交 null：[SaveImport] 会跳过游戏码与版本两道闸，只做「是不是存档 + 类别对不对」，
     * 判读权留回给读档那一刻的引擎。宁可不判，也不许设置页伪造一句「属于另一个游戏」。
     */
    private fun gameBaseOrNull(): GameBaseData? {
        val csvDir = File(gameRootFile, "CSV").toPath()
        if (CsvLoader.resolveFile(csvDir, "GameBase.csv") == null) return null
        return runCatching { GameBaseLoader.load(csvDir, mutableListOf()) }.getOrNull()
    }

    /**
     * 那一格里现在有什么：null = 空格；读不动 / 超体积上限时回空数组 ——
     * 「占位」就是占位，不能因为读不出备注就把它当空的静默盖掉。
     */
    private fun occupiedAt(relativePath: String): ByteArray? {
        val file = File(gameRootFile, relativePath)
        if (!file.isFile) return null
        if (file.length() > SaveImport.MAX_SAVE_BYTES) return ByteArray(0)
        return runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
    }

    /** 选中文件的显示名（SAF 的 DISPLAY_NAME 列）。取不到就回落 uri 末段 —— 只用来上屏与认槽号。 */
    private fun displayNameOf(uri: Uri): String {
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "选中的文件"
    }

    /** 带上限的读取：超过 [SaveImport.MAX_SAVE_BYTES] 立刻收手，选错的文件不该先把整份吸进堆里。 */
    private fun readBounded(uri: Uri): ByteArray? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > SaveImport.MAX_SAVE_BYTES) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }.getOrNull()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {

        /**
         * [setResult] 结果 extra：true = 「保存并重启游戏」（原版 `ConfigDialogResult.SaveReboot`，
         * spec: `ConfigDialog.cs:672-678` + `MainWindow.cs:1066-1069`）。
         *
         * 常量名与语义由 [MainActivity] 的 `onActivityResult` 消费，**不可改名**。
         */
        const val EXTRA_RESTART_GAME = "com.eratw.port.android.settings.RESTART_GAME"

        /** 导入结果最多留几行（卡片不是日志，留头几条就够看清刚才发生了什么）。 */
        private const val IMPORT_LINES_MAX = 12
    }
}

/**
 * 热更投递口 —— 把设置页的热更键写进**运行中会话**的那份条目表（M5-CONFIG 的运行时重载口径）。
 *
 * ## 为什么需要一个口
 * 设置页读盘用的 [SettingsModel.store] 与运行中 `GameSession.configStore` 是**两份实例**
 * （会话那份在装配期定死：属性声明 `GameSession.kt:277`、init 写死 `:301`）。
 * 原版没有这个问题：`ConfigData.Instance` 是进程级
 * 静态单例，设置对话框改完 `ConfigDialog.cs:1107-1109` 直接推给 `CBProc` 就生效。移植侧按铁律 4
 * 不许有静态可变全局，于是把「运行中会话的条目表」做成一个**可注入的提供者**：
 * 壳在会话生命周期两端各接线一次（[install] 装 provider、传 null 卸载），设置页保存时经
 * [push] 现取现用；provider 返回 null（没有在跑的会话：设置页单独打开、已退回标题、
 * 会话已关 / 正在拆）就是空操作，只落盘。
 *
 * ## 接线（已落地，上游形态）
 * `GameSession.configStore` 是 public val（`GameSession.kt:277`，init 装配期一次写死 `:301`）；
 * `GameHost` 侧取用口 = `liveConfigStore()`（读 @Volatile 的 `session`），壳侧接线在
 * **MainActivity 生命周期两端**：装载页就绪处 `HotConfigChannel.install { host?.liveConfigStore() }`、
 * 销毁清场处 `HotConfigChannel.install(null)`——先摘通道再拆会话，杜绝「保存时推给一个
 * 正在拆的会话」。不接也不崩：push 空表，热更键退化为「写盘 + 下次进入游戏生效」。
 *
 * ## 落地口径
 * 真正的写入与重算在 core 的 `SettingsModel.pushHotInto` → `EffectiveConfig.reloadHot`：
 * 只写 [com.eratw.port.core.config.ConfigApplyMode.HOT_KEYS] 里的键，重启键一个字节都不动。
 */
object HotConfigChannel {

    /**
     * 运行中会话的条目表提供者；字段 null = 未接线（壳还没 start）或已卸载。provider 惰性
     * 取用，每次 [push] 现调一次：会话未装配完 / 已清场时它自己返回 null（GameHost 侧的
     * `session` 是 @Volatile），两层判空合起来即「没有在跑的会话 ⇒ 空表」。
     * 写入端跨线程：install/卸载在 UI 线程（MainActivity 生命周期两端），卸载也可能由
     * 重启分流触发，读取在 UI 线程（设置页保存）⇒ `@Volatile`。
     */
    @Volatile
    private var liveStore: (() -> ConfigStore?)? = null

    /**
     * 壳接线：装上 [provider]；传 null 即卸载（MainActivity 销毁/重启分流清场侧）。
     * 幂等：重开一局会再次 start，重复 install 同一 provider 无害。
     */
    fun install(provider: (() -> ConfigStore?)?) {
        liveStore = provider
    }

    /**
     * 把 [model] 相对基线的热更键推进运行中的条目表。
     *
     * @return 真正落地的键；未接线或 provider 取不到活会话时为空表（调用方据此改提示文案）
     */
    fun push(model: SettingsModel): List<ConfigKey> {
        val target = liveStore?.invoke() ?: return emptyList()
        return model.pushHotInto(target)
    }
}

// =====================================================================
// 主题 / 配色
// =====================================================================

/**
 * 设置页的深色主题 —— 色值与主界面 `ShellUi.kt` 的 `EratwTheme` 一致（同一套 Material3
 * `darkColorScheme`），这样从游戏回到设置页不会有明暗跳变。
 */
@Composable
private fun SettingsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7FAF7F),
            background = Color.Black,
            surface = Color(0xFF262626),
            onBackground = Color(0xFFEDEDED),
            onSurface = Color(0xFFEDEDED),
        ),
        content = content,
    )
}

/** 设置页用到的固定色（与主界面的灰阶同一档）。 */
private object UiColors {
    val Background: Color = Color.Black
    val Card: Color = Color(0xFF262626)
    val CardAlt: Color = Color(0xFF1B1B1B)
    val Border: Color = Color(0xFF3A3A3A)
    val Text: Color = Color(0xFFEDEDED)
    val TextDim: Color = Color(0xFFB0B0B0)
    val TextMuted: Color = Color(0xFF8A8A8A)
    val Header: Color = Color(0xFF7FD4FF)
    val Accent: Color = Color(0xFF9FC79F)
    val Warn: Color = Color(0xFFFFB060)
    val PrimaryButton: Color = Color(0xFF3F6B3F)
}

// =====================================================================
// 页面骨架
// =====================================================================

/** 设置页的整页骨架：顶栏（返回 / 标题 / 全部重置）→ 列表 → 底部「保存」区。 */
@Composable
private fun SettingsScreen(
    filePath: String,
    loadNotice: String?,
    clampNotices: List<String>,
    dirty: Boolean,
    englishLabels: Boolean,
    onEnglishLabelsChange: (Boolean) -> Unit,
    groups: List<SettingsGroupView>,
    onApplyText: (String, String) -> SettingsSetResult,
    onApplyBool: (String, Boolean) -> SettingsSetResult,
    displayOf: (String) -> String,
    onResetAll: () -> Unit,
    onSave: () -> Unit,
    onSaveAndRestart: () -> Unit,
    onBack: () -> Unit,
    importing: Boolean,
    importLines: List<ImportLine>,
    overwritePending: List<PendingImport>,
    onPickSaveFiles: () -> Unit,
    onOverwriteConfirm: () -> Unit,
    onOverwriteCancel: () -> Unit,
) {
    val context = LocalContext.current
    // 两项 app 侧偏好：随改随存（UiPrefs 直接写 SharedPreferences），不参与 emuera.config 的保存。
    var fontColumns by remember { mutableIntStateOf(UiPrefs.fontColumns(context)) }
    var screenFit by remember { mutableStateOf(UiPrefs.screenFit(context)) }
    // 更精简的游戏选项：只显示带数字尾标签的常用指令（缺省关）
    var leanOptions by remember { mutableStateOf(UiPrefs.leanOptions(context)) }
    // 启动画面样式（玩家面加载页外观）：随改随存，下一次启动生效
    var loadingStyle by remember { mutableStateOf(UiPrefs.loadingStyle(context)) }
    // 底边抓手位置（输入栏手势条）
    var knobAt by remember { mutableFloatStateOf(UiPrefs.inputKnobFraction(context)) }
    // 右侧选项栏按钮大小档（随改随存；纯壳层几何，不必重开游戏）
    var railButtonSize by remember { mutableStateOf(UiPrefs.railButtonSize(context)) }
    // 标题彩蛋屏蔽（引擎级 EngineFunctionPolicy 的宿主开关）
    var disableBadApple by remember { mutableStateOf(UiPrefs.disableBadApple(context)) }
    // 正在编辑的配置条目（null = 没有对话框）；布尔项不弹框，直接走开关。
    var editing by remember { mutableStateOf<EditRequest?>(null) }

    // 自适应布局：列数与保存按钮位置全部由可用宽高推导（SettingsLayoutPolicy），不按
    // 横竖屏开关硬分支 —— 平板竖屏、折叠屏展开同样能吃到更宽档位。卡片用瀑布网格而不是
    // 等高网格：设置卡片高度差异很大（字体卡 300dp+、高级卡 100dp），等高网格会在每行
    // 留下大段空洞。
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(UiColors.Background)
            .safeDrawingPadding(),
    ) {
        val layout = settingsLayoutFor(maxWidth.value, maxHeight.value)
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                filePath = filePath,
                dirty = dirty,
                actionsInTopBar = layout.actionsInTopBar,
                onBack = onBack,
                onResetAll = onResetAll,
                onSave = onSave,
                onSaveAndRestart = onSaveAndRestart,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(layout.columns),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxHeight()
                        .widthIn(max = layout.contentMaxWidthDp.dp)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
                    verticalItemSpacing = 10.dp,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (loadNotice != null || clampNotices.isNotEmpty()) {
                        item(key = "notices", span = StaggeredGridItemSpan.FullLine) {
                            NoticeCard(loadNotice = loadNotice, clampNotices = clampNotices)
                        }
                    }
                    item(
                        key = "ui-prefs-header",
                        span = StaggeredGridItemSpan.FullLine,
                    ) {
                        GroupHeader(title = "应用偏好", subtitle = "只影响安卓壳层 · 随改随存")
                    }
                    item(key = "ui-font") {
                        FontCard(
                            fontColumns = fontColumns,
                            onFontColumns = { columns ->
                                fontColumns = columns
                                UiPrefs.setFontColumns(context, columns)
                            },
                            fontName = displayOf(ConfigKey.FontName.name),
                            onFontName = { family ->
                                // 写的是 emuera.config 的 `FONT NAME`（ConfigKey.FontName），
                                // 不是 app 侧 UiPrefs —— 字体名要经 GameSession → 控制台 sink
                                // 一路传下去，只有写进同一份配置才和 PC 侧 `FONT NAME:` 等价。
                                // 该项在 ConfigApplyMode 表里是 RESTART（GameSession.kt:303 烘死进
                                // LineBufferConsoleSink 构造参数），保存后需重开游戏。
                                onApplyText(ConfigKey.FontName.name, family)
                            },
                        )
                    }
                    item(key = "ui-control") {
                        ControlCard(
                            railButtonSize = railButtonSize,
                            onRailButtonSize = { size ->
                                railButtonSize = size
                                UiPrefs.setRailButtonSize(context, size)
                            },
                            screenFit = screenFit,
                            onScreenFit = { enabled ->
                                screenFit = enabled
                                UiPrefs.setScreenFit(context, enabled)
                            },
                            leanOptions = leanOptions,
                            onLeanOptions = { enabled ->
                                leanOptions = enabled
                                UiPrefs.setLeanOptions(context, enabled)
                            },
                            knobAt = knobAt,
                            onKnobAt = { f ->
                                knobAt = f
                                UiPrefs.setInputKnobFraction(context, f)
                            },
                        )
                    }
                    item(key = "ui-launch") {
                        LaunchCard(
                            loadingStyle = loadingStyle,
                            onLoadingStyle = { style ->
                                loadingStyle = style
                                UiPrefs.setLoadingStyle(context, style)
                            },
                            disableBadApple = disableBadApple,
                            onDisableBadApple = { disabled ->
                                disableBadApple = disabled
                                UiPrefs.setDisableBadApple(context, disabled)
                            },
                        )
                    }
                    item(key = "ui-advanced") {
                        AdvancedCard(
                            englishLabels = englishLabels,
                            onEnglishLabelsChange = onEnglishLabelsChange,
                        )
                    }
                    item(key = "save-import", span = StaggeredGridItemSpan.FullLine) {
                        SaveImportCard(
                            importing = importing,
                            lines = importLines,
                            onPickSaveFiles = onPickSaveFiles,
                        )
                    }
                    groups.forEach { group ->
                        when (group.group) {
                            ConfigGroup.GAME -> {
                                item(key = "group-game", span = StaggeredGridItemSpan.FullLine) {
                                    GroupHeader(
                                        title = "游戏配置",
                                        subtitle = "${group.fileName} · ${group.entries.size} 项 · 可编辑",
                                    )
                                }
                                gameSections(group.entries).forEach { section ->
                                    item(key = "page-${section.title}") {
                                        EntryCard(
                                            title = section.title,
                                            hint = PAGE_HINTS[section.title],
                                            entries = section.entries,
                                            englishLabels = englishLabels,
                                            onApplyBool = onApplyBool,
                                            onEdit = { entry -> editing = editRequestFor(entry, englishLabels) },
                                        )
                                    }
                                }
                            }

                            ConfigGroup.DEBUG, ConfigGroup.REPLACE -> {
                                item(key = "group-${group.group}", span = StaggeredGridItemSpan.FullLine) {
                                    ReadOnlyCard(group = group, englishLabels = englishLabels)
                                }
                            }
                        }
                    }
                }
            }
            if (!layout.actionsInTopBar) {
                BottomBar(onSave = onSave, onSaveAndRestart = onSaveAndRestart)
            }
        }
    }

    editing?.let { request ->
        EditDialog(
            request = request,
            displayOf = displayOf,
            onApplyText = onApplyText,
            onDismiss = { editing = null },
        )
    }

    // 目标格里已经有玩家的东西 ⇒ 只有玩家自己能决定要不要盖掉。
    if (overwritePending.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onOverwriteCancel,
            title = { Text("这些位置已经有存档") },
            text = {
                Column {
                    overwritePending.forEach { item ->
                        // 那一格里躺着读不出来的东西时也要说清「有东西」，不许显示成空的一格
                        val existing = item.place.occupiedMes?.takeIf { it.isNotBlank() }?.let(::oneLine)
                            ?: "读不出备注"
                        val incoming = oneLine(item.place.mes).ifEmpty { "无备注" }
                        Text(
                            text = "${item.place.slotLabel} 已有：$existing\n" +
                                "要放进来的：$incoming（${item.fileName}）",
                            color = UiColors.Text,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onOverwriteConfirm) {
                    Text("覆盖这 ${overwritePending.size} 格", color = UiColors.Accent, fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = onOverwriteCancel) {
                    Text("取消", color = UiColors.TextMuted, fontSize = 13.sp)
                }
            },
        )
    }
}

/**
 * 顶栏：返回、标题、当前文件与未保存标记、「全部重置」。
 *
 * 矮屏横屏（[actionsInTopBar]）把「保存 / 保存并重启」也收进来，让列表多出底栏那一条
 * 高度；竖屏与平板仍走底部大按钮，拇指更容易够到。
 */
@Composable
private fun TopBar(
    filePath: String,
    dirty: Boolean,
    actionsInTopBar: Boolean,
    onBack: () -> Unit,
    onResetAll: () -> Unit,
    onSave: () -> Unit,
    onSaveAndRestart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UiColors.CardAlt),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = if (actionsInTopBar) 8.dp else 10.dp),
                modifier = Modifier.semantics { contentDescription = "返回" },
            ) {
                Text(
                    text = if (actionsInTopBar) "←" else "← 返回",
                    color = UiColors.Text,
                    fontSize = if (actionsInTopBar) 18.sp else 14.sp,
                )
            }
            Text(
                text = "设置",
                color = UiColors.Text,
                fontSize = if (actionsInTopBar) 18.sp else 20.sp,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onResetAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    text = if (actionsInTopBar) "重置" else "全部重置",
                    color = UiColors.Accent,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
            if (actionsInTopBar) {
                OutlinedButton(
                    onClick = onSaveAndRestart,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("保存并重启", fontSize = 12.sp, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSave,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UiColors.PrimaryButton,
                        contentColor = UiColors.Text,
                    ),
                ) {
                    Text("保存", fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        Text(
            text = "文件：$filePath" + if (dirty) " · 未保存" else "",
            color = if (dirty) UiColors.Warn else UiColors.TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        HorizontalDivider(color = UiColors.Border)
    }
}

/** 读取提示 / 夹取提示卡片（原版用对话框弹的那几条，这里平铺在顶部，免得一进页就挡操作）。 */
@Composable
private fun NoticeCard(loadNotice: String?, clampNotices: List<String>) {
    SettingsCard {
        SectionTitle("读取提示")
        loadNotice?.let {
            Text(it, color = UiColors.Warn, fontSize = 12.sp)
        }
        clampNotices.forEach {
            Text(it, color = UiColors.Warn, fontSize = 12.sp)
        }
    }
}

/**
 * 导入已有存档卡片：一个按钮 + 刚才到底发生了什么，一行一条。
 *
 * 这里刻意不写「该怎么准备文件」那类话：玩家选中之后要什么，[SaveImport] 当场判，
 * 判不过就把原话贴在行尾（存档数据已损坏 / 试图读取的存档属于另一个游戏 / 存档版本不同…），
 * 收不收、落到哪一格都由它说，页面只负责转述。
 */
@Composable
private fun SaveImportCard(
    importing: Boolean,
    lines: List<ImportLine>,
    onPickSaveFiles: () -> Unit,
) {
    SettingsCard {
        SectionTitle("导入已有存档")
        Button(
            onClick = onPickSaveFiles,
            enabled = !importing,
            modifier = Modifier.height(40.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = UiColors.PrimaryButton,
                contentColor = UiColors.Text,
            ),
        ) {
            Text(if (importing) "正在导入…" else "选择存档文件", fontSize = 14.sp)
        }
        lines.forEach { line ->
            Text(
                text = line.text,
                color = if (line.ok) UiColors.TextDim else UiColors.Warn,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 一行导入结果（[ok] = 真的落进去了；其余按提示色显示）。 */
private class ImportLine(val text: String, val ok: Boolean)

/** 存档备注压成一行并截断：卡片与对话框都是一行一条的版面，而原版备注里可以带换行。 */
private fun oneLine(text: String): String {
    val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
    return if (flat.length <= 60) flat else flat.take(60) + "…"
}

/** 字号与正文字体：两件都影响长文本阅读，独立成卡，方便在宽屏里和操作卡并排。 */
@Composable
private fun FontCard(
    fontColumns: Int,
    onFontColumns: (Int) -> Unit,
    fontName: String,
    onFontName: (String) -> Unit,
) {
    SettingsCard {
        SectionTitle("字号与字体")
        PrefCaption("字号")
        UiPrefs.FONT_COLUMN_OPTIONS.forEach { option ->
            RadioChoiceRow(
                selected = fontColumns == option.columns,
                label = option.label,
                hint = option.hint,
                onClick = { onFontColumns(option.columns) },
            )
        }
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        // 正文字体（GFX-12 换字体波）：选择项来自 BundledFontFamilies.SELECTABLE ——
        // 表里每个族的覆盖/宽度口径都有实测读数；当前五族全部可选，默认族排第一。
        PrefCaption("正文字体")
        val currentFamily = BundledFontFamilies.resolve(fontName)
        BundledFontFamilies.SELECTABLE.forEach { family ->
            RadioChoiceRow(
                selected = currentFamily.id == family.id,
                label = family.family,
                // 显示**字数覆盖**（按语料实际出现次数加权）而不是码点种类覆盖：
                // 后者会骗人（Ark 与 Fusion 种类只差 10 个，字数差 5.7 倍）。
                hint = "${family.note} · 字数覆盖 " +
                    String.format(java.util.Locale.ROOT, "%.2f%%", family.occurrenceCoverage * 100),
                onClick = { onFontName(family.family) },
            )
        }
        Text(
            text = "改字体需重开游戏生效（写 emuera.config 的 FONT NAME）",
            color = UiColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 操作与显示：选项按钮大小、底边抓手、适应屏幕、精简选项。 */
@Composable
private fun ControlCard(
    railButtonSize: UiPrefs.RailButtonSize,
    onRailButtonSize: (UiPrefs.RailButtonSize) -> Unit,
    screenFit: Boolean,
    onScreenFit: (Boolean) -> Unit,
    leanOptions: Boolean,
    onLeanOptions: (Boolean) -> Unit,
    knobAt: Float,
    onKnobAt: (Float) -> Unit,
) {
    SettingsCard {
        SectionTitle("操作与显示")
        // 选项按钮大小：右栏的栏宽/按钮高/标题字号一起变（几何表在 ShellUi.railMetricsOf）。
        // 底下那条预览用同一条绘制通路画，所以「选完看到什么就是什么」。
        PrefCaption("选项按钮大小")
        UiPrefs.RailButtonSize.entries.forEach { size ->
            RadioChoiceRow(
                selected = railButtonSize == size,
                label = size.label,
                hint = size.hint,
                onClick = { onRailButtonSize(size) },
            )
        }
        RailSizePreview(
            // 预览与右栏同一条绘制通路，连系统字体缩放这一刀也一起照上：
            // 「选完看到什么就是什么」，否则大字号机器上预览比实际小一圈。
            metrics = railMetricsForFontScale(railMetricsOf(railButtonSize), LocalDensity.current.fontScale),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "右栏随时可用右上角的螺旋图标收起；改大小不需要重开游戏",
            color = UiColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        // 底边抓手位置（输入栏的手势条）：三档，避免必须靠拖动来调位置
        PrefCaption("底边抓手位置")
        listOf(0.12f to "靠左", 0.5f to "居中", 0.97f to "靠右").forEach { (fraction, label) ->
            RadioChoiceRow(
                selected = kotlin.math.abs(knobAt - fraction) < 0.05f,
                label = label,
                onClick = { onKnobAt(fraction) },
            )
        }
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        SwitchRow(
            title = "适应屏幕（一页正好铺满）",
            hint = "开：按屏幕大小自动缩放字号/行高，一页内容一屏看全（存档页也不用上滑）；关：只按屏宽定字号，字更大",
            checked = screenFit,
            onCheckedChange = onScreenFit,
        )
        SwitchRow(
            title = "更精简的游戏选项",
            hint = "开：右侧选项栏只保留带数字标签的常用指令（如「会话[31]」「移动[400]」）；关：显示全部识别结果（缺省）",
            checked = leanOptions,
            onCheckedChange = onLeanOptions,
        )
    }
}

/** 启动相关：加载页外观与标题彩蛋。 */
@Composable
private fun LaunchCard(
    loadingStyle: UiPrefs.LoadingStyle,
    onLoadingStyle: (UiPrefs.LoadingStyle) -> Unit,
    disableBadApple: Boolean,
    onDisableBadApple: (Boolean) -> Unit,
) {
    SettingsCard {
        SectionTitle("启动")
        PrefCaption("启动画面样式")
        UiPrefs.LoadingStyle.entries.forEach { style ->
            RadioChoiceRow(
                selected = loadingStyle == style,
                label = style.label,
                hint = style.hint,
                onClick = { onLoadingStyle(style) },
            )
        }
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        SwitchRow(
            title = "屏蔽标题彩蛋（BADAPPLE）",
            // 引擎级屏蔽（EngineFunctionPolicy），改的是引擎行为、不动语料：
            // 别人的 clone 用的上游语料里 `CALL BADAPPLE` 可能是活的，那一行就是
            // 6573 帧 AWAIT 16（≈105 秒）的纯装饰动画。缺省屏蔽。
            hint = "开：标题画面跳过那段约 105 秒的黑白动画（缺省）；关：照原样播放",
            checked = disableBadApple,
            onCheckedChange = onDisableBadApple,
        )
    }
}

/** 高级：标签语言与存储口径说明。 */
@Composable
private fun AdvancedCard(
    englishLabels: Boolean,
    onEnglishLabelsChange: (Boolean) -> Unit,
) {
    SettingsCard {
        SectionTitle("高级")
        SwitchRow(
            title = "显示原始配置键名",
            hint = "按 emuera.config 里的英文键名显示每一项",
            checked = englishLabels,
            onCheckedChange = onEnglishLabelsChange,
        )
        Text(
            text = "以上偏好随改随存，不写入 emuera.config",
            color = UiColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 卡片内的小节标题（比 [SectionTitle] 低一级）。 */
@Composable
private fun PrefCaption(text: String) {
    Text(
        text = text,
        color = UiColors.TextDim,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

/**
 * 一行单选项：整行可点，RadioButton 本身不再吃第二次点击。
 *
 * 宽度交给 `weight`：在手机横屏的两列里，长提示会换行而不是把单选项挤出卡片。
 */
@Composable
private fun RadioChoiceRow(
    selected: Boolean,
    label: String,
    hint: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, color = UiColors.Text, fontSize = 14.sp, maxLines = 1)
        if (hint != null) {
            Text(
                text = hint,
                color = UiColors.TextMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        }
    }
}

/** 「标题 + 小字说明 + 右侧开关」的一行；整行可点，不必精确戳到小开关。 */
@Composable
private fun SwitchRow(
    title: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = UiColors.Text, fontSize = 14.sp)
            if (hint != null) {
                Text(hint, color = UiColors.TextMuted, fontSize = 11.sp)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 分组小标题（文件级，不在卡片里）。 */
@Composable
private fun GroupHeader(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, color = UiColors.TextDim, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(subtitle, color = UiColors.TextMuted, fontSize = 11.sp)
    }
}

/** 可编辑组的一页（一个原版设置页 = 一张卡片）。 */
@Composable
private fun EntryCard(
    title: String,
    hint: String?,
    entries: List<SettingsEntry>,
    englishLabels: Boolean,
    onApplyBool: (String, Boolean) -> SettingsSetResult,
    onEdit: (SettingsEntry) -> Unit,
) {
    SettingsCard {
        SectionTitle(title)
        if (hint != null) {
            Text(hint, color = UiColors.TextMuted, fontSize = 11.sp)
        }
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                HorizontalDivider(color = UiColors.Border)
            }
            EditableEntryRow(
                entry = entry,
                englishLabels = englishLabels,
                onApplyBool = onApplyBool,
                onEdit = onEdit,
            )
        }
    }
}

/** 一个可编辑条目：中文标题 + 布尔开关 / 可点开的值按钮 + 生效值提示。 */
@Composable
private fun EditableEntryRow(
    entry: SettingsEntry,
    englishLabels: Boolean,
    onApplyBool: (String, Boolean) -> SettingsSetResult,
    onEdit: (SettingsEntry) -> Unit,
) {
    val current = entry.currentValue
    val boolValue = (current as? ConfigValue.Bool)?.value == true
    val rowModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(6.dp))
        .then(
            if (entry.kind == SettingsKind.BOOL) {
                Modifier.toggleable(
                    value = boolValue,
                    role = Role.Switch,
                    onValueChange = { onApplyBool(entry.id, it) },
                )
            } else {
                Modifier.clickable { onEdit(entry) }
            },
        )
        .padding(horizontal = 2.dp, vertical = 6.dp)
    Column(modifier = rowModifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = configLabel(entry, englishLabels),
                    color = UiColors.Text,
                    fontSize = 14.sp,
                )
                if (entry.fixed) {
                    Text("已被 fixed.config 钉住", color = UiColors.Warn, fontSize = 11.sp)
                }
            }
            when (entry.kind) {
                SettingsKind.BOOL -> Switch(checked = boolValue, onCheckedChange = null)

                else -> OutlinedButton(
                    onClick = { onEdit(entry) },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = display(current).ifEmpty { "（空）" },
                        color = UiColors.Accent,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                }
            }
        }
        if (entry.effectiveValue != current) {
            Text(
                text = "生效值：${display(entry.effectiveValue)}",
                color = UiColors.Warn,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 只读组（`debug.config` / `_Replace.csv`）的卡片：不接任何写入监听。 */
@Composable
private fun ReadOnlyCard(group: SettingsGroupView, englishLabels: Boolean) {
    SettingsCard {
        SectionTitle(
            when (group.group) {
                ConfigGroup.DEBUG -> "调试窗口（debug.config）· 只读"
                ConfigGroup.REPLACE -> "替换数据（_Replace.csv）· 只读"
                ConfigGroup.GAME -> "游戏配置 · 只读"
            },
        )
        Text(readOnlyHint(group.group), color = UiColors.TextMuted, fontSize = 11.sp)
        group.entries.forEachIndexed { index, entry ->
            if (index > 0) {
                HorizontalDivider(color = UiColors.Border)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = configLabel(entry, englishLabels),
                        color = UiColors.Text,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "当前值：${display(entry.currentValue)} · " +
                            "缺省值：${display(entry.defaultValue)} · " +
                            "生效值：${display(entry.effectiveValue)}",
                        color = UiColors.TextMuted,
                        fontSize = 11.sp,
                    )
                }
                if (entry.kind == SettingsKind.BOOL) {
                    Switch(
                        checked = (entry.currentValue as? ConfigValue.Bool)?.value == true,
                        onCheckedChange = null,
                        enabled = false,
                    )
                }
            }
        }
    }
}

/** 底部操作区：保存并重启（次要）+ 保存（主按钮，大）；只在竖屏/高屏出现。 */
@Composable
private fun BottomBar(onSave: () -> Unit, onSaveAndRestart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UiColors.CardAlt),
    ) {
        HorizontalDivider(color = UiColors.Border)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onSaveAndRestart,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("保存并重启", fontSize = 13.sp)
            }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = UiColors.PrimaryButton,
                    contentColor = UiColors.Text,
                ),
            ) {
                Text("保存", fontSize = 15.sp)
            }
        }
        Text(
            text = "保存＝写盘（热更项立即生效，重启项下次进入游戏生效）；" +
                "保存并重启＝写盘后重开（本次改动全是热更项时不重启）",
            color = UiColors.TextMuted,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp),
        )
    }
}

/** 统一卡片外观：深灰 #262626、圆角 8dp、细描边、内边距 12/10（与主界面底栏同色）。 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = UiColors.Card),
        border = BorderStroke(1.dp, UiColors.Border),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            content = content,
        )
    }
}

/** 卡片标题（浅蓝，和主界面「设置」入口同一色系）。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = UiColors.Header,
        fontSize = 15.sp,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

// =====================================================================
// 编辑对话框
// =====================================================================

/** 一次条目编辑：文本 / 数值用输入框，枚举用单选列表。 */
private class EditRequest(
    val entryId: String,
    val title: String,
    val initial: String,
    val numeric: Boolean,
    val choices: List<String>,
)

/**
 * 一次「需要先确认」的导入：字节已经在手上（体积闸保证它不会大）。
 *
 * 判据、落位路径、那一格里已有谁都全由 [SaveImport.plan] 定，这里只搬运结果 ——
 * 页面上不再判一次，免得设置页和游戏内 `CHKDATA` 说出不一样的一句话。
 */
private class PendingImport(
    val place: SaveImport.Place,
    val bytes: ByteArray,
    val fileName: String,
)

/** 把条目快照变成一次编辑请求（点开值按钮时调用，拿的是模型当前值）。 */
private fun editRequestFor(entry: SettingsEntry, englishLabels: Boolean): EditRequest {
    val current = display(entry.currentValue)
    val enumChoices = if (entry.kind == SettingsKind.ENUM) {
        val constants = (entry.constraint as? SettingsConstraint.Choices)?.constants.orEmpty()
        if (current in constants || constants.isEmpty()) constants else constants + current
    } else {
        emptyList()
    }
    return EditRequest(
        entryId = entry.id,
        title = configLabel(entry, englishLabels),
        initial = current,
        numeric = entry.kind == SettingsKind.INT,
        choices = enumChoices,
    )
}

/**
 * 编辑对话框：确定时把输入交给 [onApplyText]（即 [SettingsModel.set] 的校验链）。
 *
 * 被拒绝时不关框：框内显示拒因、输入回显模型现值（与旧版「回显现值 + Toast」等价），
 * 玩家可以改完再确定，或取消离开。
 */
@Composable
private fun EditDialog(
    request: EditRequest,
    displayOf: (String) -> String,
    onApplyText: (String, String) -> SettingsSetResult,
    onDismiss: () -> Unit,
) {
    var text by remember(request) { mutableStateOf(request.initial) }
    var selected by remember(request) { mutableStateOf(request.initial) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    val isEnum = request.choices.isNotEmpty()

    val confirm: () -> Unit = {
        val candidate = if (isEnum) selected else text
        when (val result = onApplyText(request.entryId, candidate)) {
            is SettingsSetResult.Rejected -> {
                error = "${rejectReasonText(result.reason)}：${result.message}"
                val restored = displayOf(request.entryId)
                text = restored
                selected = restored
            }

            is SettingsSetResult.Applied -> onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title, fontSize = 16.sp) },
        text = {
            Column {
                if (isEnum) {
                    request.choices.forEach { choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = choice }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == choice,
                                onClick = { selected = choice },
                            )
                            Text(choice, color = UiColors.Text, fontSize = 14.sp)
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            error = null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (request.numeric) KeyboardType.Number else KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        color = UiColors.Warn,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = confirm) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// =====================================================================
// 标签与分组
// =====================================================================

/**
 * 一个条目在设置页上的中文标题。
 *
 * 优先级：
 *   1. 「显示原始配置键名」打开 ⇒ `emuera.config` 的英文键原文（`AConfigItem.EngText`，
 *      spec: `AConfigItem.cs:23`）；
 *   2. [ConfigPageLabels] 有简体中文译文 ⇒ 用它（原版 `lang.emuera-zhs.xml` 的页面译文）；
 *   3. app 侧兜底表 [ZH_CONFIG_LABELS] —— 补两类缺译：原版 zhs 没有 id 的 6 条页面标签、
 *      以及根本不在任何设置页上的 25 条（9 条无页面标签的 `configArray` + 16 条 `_Replace.csv`）；
 *   4. 都没覆盖 ⇒ 回落配置键原文（日文），**不编造**译文。
 */
private fun configLabel(entry: SettingsEntry, englishLabels: Boolean): String {
    if (englishLabels) {
        return entry.enLabel
    }
    val zhs = ConfigPageLabels.byKey(entry.key)?.zhsLabel
    if (zhs != null) {
        return zhs
    }
    return ZH_CONFIG_LABELS[entry.key] ?: entry.jpLabel
}

/**
 * app 侧中文兜底标签表（31 条）。
 *
 * 覆盖的是 [ConfigPageLabels] 里**拿不到简体中文**的条目：
 *   * 无页面标签的 `configArray` 9 条（原版 `ConfigDialog` 没有给它们控件，
 *     spec: `ConfigLabelTest` 的「不在设置页上的 configArray 条目恰好是这九个」）；
 *   * 有页面标签但 zhs 文件缺译的 6 条（`CheckDuplicateIdentifier`、Rikai 三条、
 *     两个 resx 来源条目，spec: `ConfigPageLabels.kt` 对应的 note）；
 *   * `_Replace.csv` 16 条（原版没有设置页，标的是配置文件键本身）。
 *
 * 这些译文是 app 侧的显示文本，**不改 core 的标签表**（那张表登记的是原版语言文件的
 * 真实覆盖范围，缺译即 null，不编造）。
 */
private val ZH_CONFIG_LABELS: Map<ConfigKey, String> = mapOf(
    // —— 无页面标签的 configArray 9 条 ——
    ConfigKey.SkipFrame to "最大跳帧数",
    ConfigKey.AllowLongInputByMouse to "允许鼠标输入长文本（ONEINPUT 系）",
    ConfigKey.SystemIgnoreStringSet to "字符串变量赋值强制使用字符串表达式",
    ConfigKey.UseLazyLoading to "延迟加载（UseLazyLoading）",
    ConfigKey.ChangeMasterNameIfDebug to "调试命令下更改 MASTER 名称",
    ConfigKey.LastKey to "最终更新代码",
    ConfigKey.CompatiDRAWLINE to "DRAWLINE 始终另起新行",
    ConfigKey.EmueraIcon to "自定义窗口图标路径",
    ConfigKey.CBUseClipboard to "将显示文本复制到剪贴板",
    // —— 有页面标签、但原版 zhs 文件缺译的 6 条 ——
    ConfigKey.Ctrl_Z_Enabled to "启用 Ctrl-Z 撤销",
    ConfigKey.ReplaceContinuationBR to "行连接换行符替换字符串（用半角引号包裹）",
    ConfigKey.CheckDuplicateIdentifier to "检查 ERD 标识符与局部变量重名",
    ConfigKey.RikaiEnabled to "启用弹窗翻译词典（日语→英语）",
    ConfigKey.RikaiFilename to "词典文件路径（相对 Emuera 本体）",
    ConfigKey.RikaiUseSeparateBoxes to "高亮显示翻译中的词句",
    // —— _Replace.csv 16 条（只读展示） ——
    ConfigKey.MoneyLabel to "货币单位",
    ConfigKey.MoneyFirst to "货币单位在前",
    ConfigKey.LoadLabel to "启动时简略显示",
    ConfigKey.MaxShopItem to "贩卖道具数",
    ConfigKey.DrawLineString to "DRAWLINE 字符",
    ConfigKey.BarChar1 to "BAR 字符 1",
    ConfigKey.BarChar2 to "BAR 字符 2",
    ConfigKey.TitleMenuString0 to "系统菜单 0",
    ConfigKey.TitleMenuString1 to "系统菜单 1",
    ConfigKey.ComAbleDefault to "COM_ABLE 初始值",
    ConfigKey.StainDefault to "污渍初始值",
    ConfigKey.TimeupLabel to "时间切显示",
    ConfigKey.ExpLvDef to "EXPLV 初始值",
    ConfigKey.PalamLvDef to "PALAMLV 初始值",
    ConfigKey.pbandDef to "PBAND 初始值",
    ConfigKey.RelationDef to "RELATION 初始值",
)

/** 一页设置项：中文页名 + 该页在原版对话框里的条目顺序。 */
private class ConfigPageSpec(val title: String, val keys: List<ConfigKey>)

/**
 * `GAME` 组的页面划分 —— 页名与原版 `ConfigDialog` 的分页一一对应
 * （spec: `ConfigDialog.cs:489-627` 的 `TranslateUI` 分页赋值），页内顺序照抄控件顺序。
 *
 * 页名一律用中文（原版是日语页名，旧版页面把「文件名」当标题，这里改成真正的页名）。
 * 最后一页「其他（高级）」收的是原版设置页上**没有控件**的条目，保留可编辑性以便写回配置。
 */
private val GAME_PAGE_SPECS: List<ConfigPageSpec> = listOf(
    ConfigPageSpec(
        "环境",
        listOf(
            ConfigKey.UseMouse,
            ConfigKey.UseMenu,
            ConfigKey.UseDebugCommand,
            ConfigKey.AllowMultipleInstances,
            ConfigKey.UseKeyMacro,
            ConfigKey.AutoSave,
            ConfigKey.UseSaveFolder,
            ConfigKey.EnglishConfigOutput,
            ConfigKey.MaxLog,
            ConfigKey.InfiniteLoopAlertTime,
            ConfigKey.SaveDataNos,
            ConfigKey.TextEditor,
            ConfigKey.EditorType,
            ConfigKey.EditorArgument,
            ConfigKey.Ctrl_Z_Enabled,
        ),
    ),
    ConfigPageSpec(
        "显示",
        listOf(
            ConfigKey.TextDrawingMode,
            ConfigKey.FPS,
            ConfigKey.PrintCPerLine,
            ConfigKey.PrintCLength,
            ConfigKey.ButtonWrap,
            ConfigKey.EmueraLang,
        ),
    ),
    ConfigPageSpec(
        "窗口",
        listOf(
            ConfigKey.WindowX,
            ConfigKey.WindowY,
            ConfigKey.SizableWindow,
            ConfigKey.WindowMaximixed,
            ConfigKey.SetWindowPos,
            ConfigKey.WindowPosX,
            ConfigKey.WindowPosY,
            ConfigKey.ScrollHeight,
        ),
    ),
    ConfigPageSpec(
        "字体",
        listOf(
            ConfigKey.BackColor,
            ConfigKey.ForeColor,
            ConfigKey.FocusColor,
            ConfigKey.LogColor,
            ConfigKey.FontName,
            ConfigKey.FontSize,
            ConfigKey.LineHeight,
        ),
    ),
    ConfigPageSpec(
        "系统",
        listOf(
            ConfigKey.IgnoreCase,
            ConfigKey.UseRenameFile,
            ConfigKey.UseReplaceFile,
            ConfigKey.SearchSubdirectory,
            ConfigKey.SortWithFilename,
            ConfigKey.AllowFunctionOverloading,
            ConfigKey.WarnFunctionOverloading,
            ConfigKey.WarnNormalFunctionOverloading,
            ConfigKey.SystemAllowFullSpace,
            ConfigKey.useLanguage,
        ),
    ),
    ConfigPageSpec(
        "系统（二）",
        listOf(
            ConfigKey.SystemIgnoreTripleSymbol,
            ConfigKey.SystemSaveInBinary,
            ConfigKey.ZipSaveData,
            ConfigKey.SystemNoTarget,
            ConfigKey.ForbidUpdateCheck,
            ConfigKey.UseERD,
            ConfigKey.VarsizeDimConfig,
            ConfigKey.ValidExtension,
            ConfigKey.ReplaceContinuationBR,
        ),
    ),
    ConfigPageSpec(
        "兼容性",
        listOf(
            ConfigKey.CompatiErrorLine,
            ConfigKey.CompatiCALLNAME,
            ConfigKey.CompatiRAND,
            ConfigKey.TimesNotRigorousCalculation,
            ConfigKey.CompatiFunctionNoignoreCase,
            ConfigKey.CompatiCallEvent,
            ConfigKey.CompatiSPChara,
            ConfigKey.CompatiLinefeedAs1739,
            ConfigKey.CompatiFuncArgOptional,
            ConfigKey.CompatiFuncArgAutoConvert,
        ),
    ),
    ConfigPageSpec(
        "解析",
        listOf(
            ConfigKey.WarnBackCompatibility,
            ConfigKey.DisplayReport,
            ConfigKey.CheckDuplicateIdentifier,
            ConfigKey.ReduceArgumentOnLoad,
            ConfigKey.DisplayWarningLevel,
            ConfigKey.IgnoreUncalledFunction,
            ConfigKey.FunctionNotFoundWarning,
            ConfigKey.FunctionNotCalledWarning,
        ),
    ),
    ConfigPageSpec(
        "剪贴板",
        listOf(
            ConfigKey.CBIgnoreTags,
            ConfigKey.CBReplaceTags,
            ConfigKey.CBNewLinesOnly,
            ConfigKey.CBClearBuffer,
            ConfigKey.CBTriggerLeftClick,
            ConfigKey.CBTriggerMiddleClick,
            ConfigKey.CBTriggerDoubleLeftClick,
            ConfigKey.CBTriggerAnyKeyWait,
            ConfigKey.CBTriggerInputWait,
            ConfigKey.CBMaxCB,
            ConfigKey.CBBufferSize,
            ConfigKey.CBScrollCount,
            ConfigKey.CBMinTimer,
        ),
    ),
    ConfigPageSpec(
        "弹窗翻译",
        listOf(
            ConfigKey.RikaiEnabled,
            ConfigKey.RikaiFilename,
            ConfigKey.RikaiColorBack,
            ConfigKey.RikaiColorText,
            ConfigKey.RikaiUseSeparateBoxes,
        ),
    ),
    ConfigPageSpec(
        "其他（高级）",
        listOf(
            ConfigKey.SkipFrame,
            ConfigKey.ChangeMasterNameIfDebug,
            ConfigKey.CompatiDRAWLINE,
            ConfigKey.AllowLongInputByMouse,
            ConfigKey.SystemIgnoreStringSet,
            ConfigKey.UseLazyLoading,
            ConfigKey.EmueraIcon,
            ConfigKey.CBUseClipboard,
            ConfigKey.LastKey,
        ),
    ),
)

/** 页尾小字说明（只给需要的页加；null = 不加）。 */
private val PAGE_HINTS: Map<String, String> = mapOf(
    "其他（高级）" to "原版设置页没有列出这些项；其中 CompatiDRAWLINE 在原版永不写出（读取时被重定向）",
    "弹窗翻译" to "复用字体页的「背景颜色 / 文本颜色」译文（原版如此）",
)

/** 把 `GAME` 组的条目按 [GAME_PAGE_SPECS] 分页；未登记的条目（将来新增的键）落进「其他」。 */
private fun gameSections(entries: List<SettingsEntry>): List<PageSection> {
    val byKey = entries.associateBy { it.key }
    val used = HashSet<ConfigKey>()
    val sections = ArrayList<PageSection>()
    for (page in GAME_PAGE_SPECS) {
        val items = page.keys.mapNotNull { byKey[it] }
        used += page.keys
        if (items.isNotEmpty()) {
            sections += PageSection(page.title, items)
        }
    }
    val rest = entries.filter { it.key !in used }
    if (rest.isNotEmpty()) {
        sections += PageSection("其他", rest)
    }
    return sections
}

/** 一页渲染用的数据（页名 + 该页条目快照）。 */
private class PageSection(val title: String, val entries: List<SettingsEntry>)

/** 只读组的来源说明（原版这两个文件都没有从 `emuera.config` 设置页写回的路径）。 */
private fun readOnlyHint(group: ConfigGroup): String = when (group) {
    ConfigGroup.DEBUG -> "只读：原版由 debug.config 读取，本页不写回"
    ConfigGroup.REPLACE -> "只读：原版由 _Replace.csv 读取，没有设置页，本页不写回"
    ConfigGroup.GAME -> ""
}

// =====================================================================
// 值与拒因
// =====================================================================

/** 配置值 → 文本（与写进 `emuera.config` 的形态一致，旧版设置页同一套）。 */
private fun display(value: ConfigValue): String = when (value) {
    is ConfigValue.Bool -> if (value.value) "YES" else "NO"
    is ConfigValue.IntValue -> value.value.toString()
    is ConfigValue.LongValue -> value.value.toString()
    is ConfigValue.StringValue -> value.value
    is ConfigValue.CharValue -> value.value.toString()
    is ConfigValue.ColorValue -> value.toConfigString()
    is ConfigValue.StringList -> value.toConfigString()
    is ConfigValue.LongList -> value.value.joinToString("/")
    is ConfigValue.EnumValue -> value.constant
}

/** 拒绝原因的中文名（原版文案在 `result.message` 里原样给出）。 */
private fun rejectReasonText(reason: SettingsRejectReason): String = when (reason) {
    SettingsRejectReason.UNKNOWN_ID -> "未知条目"
    SettingsRejectReason.TYPE_MISMATCH -> "类型不符"
    SettingsRejectReason.INVALID_TEXT -> "解析失败"
    SettingsRejectReason.EMPTY_TEXT -> "空文本"
    SettingsRejectReason.OUT_OF_RANGE -> "超出范围"
    SettingsRejectReason.FIXED -> "已被 fixed.config 钉住"
}
