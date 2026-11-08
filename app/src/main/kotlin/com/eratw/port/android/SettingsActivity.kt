package com.eratw.port.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eratw.port.core.config.ConfigClampLabels
import com.eratw.port.core.config.ConfigClampNotice
import com.eratw.port.core.config.ConfigGroup
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.SettingsConstraint
import com.eratw.port.core.config.SettingsEntry
import com.eratw.port.core.config.SettingsGroupView
import com.eratw.port.core.config.SettingsKind
import com.eratw.port.core.config.SettingsModel
import com.eratw.port.core.config.SettingsSaveImpact
import com.eratw.port.core.config.SettingsSetResult
import com.eratw.port.core.data.csv.CsvLoader
import com.eratw.port.core.data.csv.GameBaseLoader
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.platform.config.SettingsStore
import com.eratw.port.platform.save.SaveExport
import com.eratw.port.platform.save.SaveImport
import com.eratw.port.platform.storage.GameStorage
import com.eratw.port.platform.storage.MaterialSource
import com.eratw.port.platform.storage.MaterialTarget
import com.eratw.port.platform.storage.ModStore
import com.eratw.port.platform.storage.ResourcePackReport
import com.eratw.port.platform.storage.ResourcePackStatus
import com.eratw.port.platform.storage.ResourcePackStore
import com.eratw.port.platform.storage.StructureNoteKind
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.render.font.BundledFontFamilies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

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
 * ## 显示语言（只作用于本页）
 *   顶部「显示语言」卡片可选 简体中文 / English / 日本語，随改随存（[UiPrefs.settingsLanguage]）。
 *   三档各自取**真实存在**的文本，不编造：
 *   * 中文 —— 原版 `lang.emuera-zhs.xml` 的页面译文（缺译回落日文），再不行用 app 侧
 *     [SettingsStrings] 的兜底表；
 *   * 日文 —— 原版 `Lang.cs` 的页面标签（`pageJaLabel`），没有页面标签的条目回落配置键原文；
 *   * 英文 —— 原版没有英文语言文件，条目名取 `emuera.config` 的英文键名（`enLabel`），
 *     壳层文案用 [SettingsStrings] 的英文。
 *   「显示原始配置键名」开关仍是独立覆盖：中文/日文模式下强制显示英文键名（英文模式天然如此）。
 *   游戏画面、加载页等其它界面**不跟随**这里的语言。
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
 *   注意本页控制不了的另一样东西：**显示大小档**（[UiPrefs.fontColumns] 派生的
 *   `CanvasConsoleView.minColumnsOverride` + `fontScale`）是构造期参数，改了要重建 Activity ——
 *   那条路仍在 [MainActivity] 的 `onActivityResult`（`recreate()`），不走本表。
 *
 *   字号/行距本体（`emuera.config` 的 `FONT SIZE` / `LINE HEIGHT`）在「字体」页，
 *   由 [GameHost] 在每次会话开始前灌进 [com.eratw.port.render.canvas.CanvasConsoleView]
 *   （`applySourceFontMetrics`），重开游戏生效；列数守护会在屏宽不足时压小实际字号。
 *
 * ## Compose 侧的两个新设置（存 [UiPrefs]，与 `emuera.config` 解耦）
 *   * 显示大小档：136 标准（×1.00）/ 90 大（×1.51）/ 75 特大（×1.81）/ 150 小（×0.91），
 *     倍率乘在 `FONT SIZE` 上，同一档位值也是列数守护下限（列越少，字号越大）；
 *   * 列数守护开关（默认开）：关掉后字号优先，宽地图行可能折行。
 *   这两项随改随存，不参与「保存」，由装配层从 [UiPrefs] 读取。
 *
 * @see UiPrefs
 * @see HotConfigChannel
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var store: SettingsStore
    private lateinit var model: SettingsModel

    /** 读取诊断（读盘失败 / 解析中止 / 告警 / 文件缺失），只在打开页面时算一次；按当前语言上屏。 */
    private var loadNotice: LoadNotice? = null

    /**
     * 模型改动版本号 —— [SettingsModel] 不是可观察对象，Compose 侧靠它在每次成功写入 / 重置 /
     * 保存之后重新取 `model.groups`。只在 UI 线程写（设置页所有回调都在 UI 线程）。
     */
    private var revision by mutableIntStateOf(0)

    /** 「显示原始配置键名」开关。默认关：与界面语言的默认阅读习惯一致。 */
    private var englishLabels by mutableStateOf(false)

    /** 设置页显示语言（只作用于本页；存 [UiPrefs]，`onCreate` 读一次）。 */
    private var language by mutableStateOf(SettingsLanguage.DEFAULT)

    /** 当前语言的文案表（语言切换时整个页面重组）。 */
    private val text: SettingsStrings get() = SettingsStrings.of(language)

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

    // -----------------------------------------------------------------
    // 一键导出存档（全部槽 + global.sav 打成一个 zip，落点交给系统选择器）
    // -----------------------------------------------------------------

    /** 读盘/写盘进行态：收集、选位置、写 zip 三段都不放行第二次。 */
    private var exporting by mutableStateOf(false)

    /** 导出结果，一行一条（最新在前，最多留 [EXPORT_LINES_MAX] 条）。 */
    private var exportLines by mutableStateOf<List<ExportLine>>(emptyList())

    /** 已收集好的快照；等系统选择器给出目标文件后再写盘（取消就丢弃）。 */
    private var pendingExport: SaveExport.Bundle? = null

    // -----------------------------------------------------------------
    // 运行诊断（闪退但没有任何报告的一类：files/eratw-run 的轨迹 + 死因判读）
    // -----------------------------------------------------------------

    /** 生成进行态：读盘 + 拼串 + 写报告都在后台，按钮不放行第二次。 */
    private var diagBusy by mutableStateOf(false)

    /** 诊断结果，一行一条（最新在前，最多留 [DIAG_LINES_MAX] 条）。 */
    private var diagLines by mutableStateOf<List<DiagLine>>(emptyList())

    /**
     * 「立即检查更新」的进行中态（EP4 U2-②）：[UpdateUi.checkNowInteractive] 真的拿到单飞闸
     * 才置真，结果上屏的那一次回调里随改随消。按钮据此禁用 + 换话术 —— 旧行为是点下去只 Toast
     * 一句「正在检查…」、按钮还能连点，最坏几十秒里玩家可以攒出三条并发检查线程。
     */
    private var updateChecking by mutableStateOf(false)

    /** U5 P2-12：设置页是否在前台；低频刷新只在前台读盘，后台不空转。 */
    @Volatile
    private var settingsForeground = false

    override fun onResume() {
        super.onResume()
        settingsForeground = true
    }

    override fun onPause() {
        settingsForeground = false
        super.onPause()
    }

    /**
     * 通知权限回调（P1，Lead 发现）：更新弹窗也挂在设置页上（[UpdateUi.checkNowInteractive] 的
     * offer 弹窗以设置页为宿主），玩家在设置页点「下载」触发 `POST_NOTIFICATIONS` 授权时，
     * 回调必须转发给 [UpdateUi.onPermissionResult]。
     *
     * 不转发的话 `UpdateUi.pendingStart` 永远挂着：那一单不会跑，之后每一次「下载」点击都被
     * 当成「授权还没回来」静默吞掉，直到进程重启。与 [MainActivity] 同一口径：不判结果、只转发，
     * 异常只落 logcat。
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        runCatching { UpdateUi.onPermissionResult(requestCode, grantResults) }
            .onFailure { Log.w(TAG, "权限回调异常（不影响设置页与游戏）", it) }
    }

    /**
     * 系统「新建文件」选择器（SAF `ACTION_CREATE_DOCUMENT`，zip）。
     *
     * 同样必须注册在 Activity 进入 STARTED 之前 ⇒ 成员属性。目标由玩家自己选：
     * 不申请任何存储权限，也不替玩家决定放哪（[android.content.Intent] 的落点由系统给）。
     */
    private val createExportFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val bundle = pendingExport
        pendingExport = null
        if (uri == null || bundle == null) {
            // 玩家取消了选择：什么都没写，也不必报一句废话
            exporting = false
            return@registerForActivityResult
        }
        writeExport(uri, bundle)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 这一页打开时要读盘：`GameStorage.gameRoot()` 会建目录、`SettingsStore.load()`
        // 会解析 emuera.config。配置文件被改坏、存储只读、目录被外部删掉都会在这两步抛 ——
        // 而它和游戏是**同一个进程**，这一页崩一次就是整局跟着死。
        // 设置页不是必经路径：关掉它退回游戏，比闪退好得多（玩家至少还能继续玩）。
        try {
            onCreateCore()
        } catch (t: Throwable) {
            Log.e(TAG, "设置页打开失败，退回游戏", t)
            runCatching {
                Toast.makeText(
                    this,
                    "设置页打不开（${t.javaClass.simpleName}），已返回游戏",
                    Toast.LENGTH_LONG,
                ).show()
            }
            runCatching { finish() }
        }
    }

    /** [onCreate] 的正体（读盘 + 装 Compose 树）；兜底在 [onCreate] 那一层。 */
    private fun onCreateCore() {
        language = UiPrefs.settingsLanguage(this)
        gameRootFile = GameStorage(this).gameRoot()
        // 素材现状（sound/游戏bgm 与 resources/背景图 两棵小树）可能落在慢存储上：
        // 先用「未知」态把页面立起来，后台读一次，读完用既有 StatusRefreshed 回 UI 线程
        // 刷新（不新开事件类型；读不出来仍是 null = 按「未知」呈现，不假装全零）。
        backgroundPackState = ResourcePackUiState.Idle(null)
        bgmPackState = ResourcePackUiState.Idle(null)
        archivePackState = ResourcePackUiState.Idle(null)
        Thread({
            val status = runCatching { ResourcePackStore.scanStatus(gameRootFile) }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                for (slot in PackSlot.entries) {
                    setPackState(
                        slot,
                        ResourcePackUiReducer.reduce(packStateOf(slot), ResourcePackEvent.StatusRefreshed(status)),
                    )
                }
            }
        }, "eratw-respack-scan").start()
        store = SettingsStore(File(gameRootFile, "emuera.config"))
        val loaded = store.load()
        model = loaded.model
        // 顶栏「全部重置」按原版口径不写盘，而 load() 已把盘上的玩家旧值读回模型 ⇒ 用一次性载体
        // 把出厂目标接回内存（重建前 resetAll 已寄存，取走即清空；不在这里写盘，落盘仍等玩家点保存）。
        ResetPaths.consumeStagedGameReset()?.let { FactoryConfigDefaults.apply(model, it) }
        loadNotice = when {
            loaded.error != null -> LoadNotice.Error(loaded.error?.message.orEmpty())
            loaded.abortedAtLine != null ->
                // 跨模块的 val 不能智能转换（SettingsStore.LoadResult 是 public API）
                LoadNotice.Aborted(loaded.abortedAtLine as Int, loaded.droppedLines)
            loaded.warnings.isNotEmpty() ->
                LoadNotice.Warnings(loaded.warnings.size, loaded.warnings.first().message)
            !loaded.fileExists -> LoadNotice.Missing
            else -> null
        }
        setContent {
            SettingsTheme {
                // 在内容作用域里读这几个状态：模型写入 / 语言切换后整页重组。
                val rev = revision
                val lang = language
                val strings = SettingsStrings.of(lang)
                // 更新面三语表（EP4 U3a）：语言只在这里取一次（与设置页自己的 `language` 同源，
                // 就是 UiPrefs 那条读数），通道读数初值、进度条与按钮只做「按这个 copy 出词」。
                val updateCopy = UpdateCopy.of(UiPrefs.settingsLanguage(this))
                val english = englishLabels
                val groups = remember(rev) { model.groups.filter { it.entries.isNotEmpty() } }
                val notices = remember(rev, lang) { model.clampNotices().map { clampNoticeLine(it, strings) } }
                val dirty = remember(rev) { model.dirty }
                // U5 P2-12 + P2（主线程 IO）：通道现状里读的是盘上 data-version.txt 与偏好，
                // 所以初值先用**纯判读的空快照**（一帧的「尚未检查」占位），真读数在 IO 线程取；
                // 界面在前台时低频刷新，也只重读读数 —— 不开第二条下载线程、不改通知刷新节流。
                var updateReadout by remember(lang) {
                    mutableStateOf(UpdateChannelReadout.of(updateCopy, UpdateChannelReadout.Snapshot()))
                }
                LaunchedEffect(lang) {
                    updateReadout = withContext(Dispatchers.IO) {
                        UpdateUi.channelReadout(this@SettingsActivity, gameRootFile)
                    }
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(SETTINGS_READOUT_REFRESH_MILLIS)
                        if (settingsForeground) {
                            updateReadout = withContext(Dispatchers.IO) {
                                UpdateUi.channelReadout(this@SettingsActivity, gameRootFile)
                            }
                        }
                    }
                }
                // 下载进度条要走自己的心跳：上面那份完整现状会读偏好与盘上暂存，5 秒一次已是上限，
                // 加密到毫秒级就等于在 UI 线程高频读盘。这里只读 UpdateDownloadService 的进程内活字段
                // （progressLock 里取一份纯数据），在跑 250ms 一跳、空闲 1s 探一次，跟手且不碰任何盘。
                var downloadProgress by remember { mutableStateOf(UpdateDownloadService.progress()) }
                LaunchedEffect(Unit) {
                    var running = downloadProgress.running
                    while (true) {
                        delay(if (running) SETTINGS_PROGRESS_REFRESH_MILLIS else SETTINGS_PROGRESS_IDLE_POLL_MILLIS)
                        if (settingsForeground) {
                            val next = UpdateDownloadService.progress()
                            downloadProgress = next
                            running = next.running
                        }
                    }
                }
                SettingsScreen(
                    filePath = store.file.absolutePath,
                    loadNotice = loadNotice?.text(strings),
                    clampNotices = notices,
                    dirty = dirty,
                    englishLabels = english,
                    onEnglishLabelsChange = { englishLabels = it },
                    text = strings,
                    language = lang,
                    onLanguageChange = ::changeLanguage,
                    groups = groups,
                    onApplyText = ::applyText,
                    onApplyBool = ::applyBool,
                    displayOf = ::displayOf,
                    onResetAll = ::resetAll,
                    onRestoreDefaults = ::restoreDefaults,
                    onSave = ::save,
                    onSaveAndRestart = ::saveAndRestart,
                    onBack = { finish() },
                    importing = importing,
                    importLines = importLines,
                    overwritePending = overwritePending,
                    onPickSaveFiles = { pickSaveFiles.launch(arrayOf("*/*")) },
                    onOverwriteConfirm = ::commitOverwrite,
                    onOverwriteCancel = ::discardOverwrite,
                    // 补丁导入域三语表（A-8/T-2c）：与更新面同口径，语言只从这里取一次
                    // （就是上面那条 `language` 读数），入口卡与覆盖层只做「按这个 copy 出词」。
                    modImportCopy = ModImportCopy.of(lang),
                    modImportState = modImportState,
                    onOpenModImport = ::openModImport,
                    backgroundPackState = backgroundPackState,
                    bgmPackState = bgmPackState,
                    archivePackState = archivePackState,
                    bgmLocation = bgmLocationInput,
                    onBgmLocationChange = { bgmLocationInput = it },
                    // 素材导入域三语表（Lane B）：与补丁导入同口径，语言只从上面那条
                    // `language` 读数取一次，卡片只做「按这个 copy 出词」。
                    resourcePackCopy = ResourcePackCopy.of(lang),
                    onPickBackgroundFiles = { pickBackgroundImages.launch(Unit) },
                    onPickBackgroundFolder = { pickBackgroundFolder.launch(null) },
                    onPickBgmFiles = { pickBgmTracks.launch(Unit) },
                    onPickBgmFolder = { pickBgmFolder.launch(null) },
                    onPickResourcePacks = { pickResourcePacks.launch(Unit) },
                    onRestartForResources = ::finishForPatchRestart,
                    exporting = exporting,
                    exportLines = exportLines,
                    diagBusy = diagBusy,
                    diagLines = diagLines,
                    onShareDiagnostics = { generateDiagnostics(share = true) },
                    onCopyDiagnostics = { generateDiagnostics(share = false) },
                    onExport = ::startExport,
                    onCheckUpdate = {
                        updateChecking = UpdateUi.checkNowInteractive(this, gameRootFile) { updateChecking = false }
                    },
                    updateChecking = updateChecking,
                    updateReadout = updateReadout,
                    downloadProgress = downloadProgress,
                    // 更新面三语表（EP4 U3a）：语言只从这里取一次（与设置页自己的 `language` 同源，
                    // 就是 UiPrefs 那条读数），启动卡的更新段只做「按这个 copy 出词」，不判语言。
                    updateCopy = updateCopy,
                    // P2：失败单的入口必须读**盘上那份**（冷启后它还在）——无参重载只读进程内内存，
                    // 进程被杀后按钮就永远不出现了，落盘等于白存。
                    failedDownloadKind = UpdateDownloadService.lastFailedDownload(this)?.kind,
                    onRetryFailedDownload = { UpdateDownloadService.retryLastFailed(this) },
                    // P2：通知看不见时取消动作只挂在通知 action 上；设置页给一个同屏可达的停止入口。
                    onCancelDownload = { UpdateDownloadService.cancel(this) },
                    skippedUpdateVersion = UiPrefs.updateSkippedVersionCode(this),
                    onRestoreUpdateReminder = ::restoreUpdateReminder,
                )
                // 补丁导入覆盖层（R7-D1 任务①）：入口卡片点「打开」后弹出；导入页 /
                // 报错页 / 报告页三页的形态在 ModImportChrome 里原样保留。按需组合，
                // 关掉即从组合树摘除。可见性与状态直接读本 Activity 的 mutableStateOf。
                if (modImportVisible) {
                    ModImportChrome(
                        state = modImportState,
                        onDismiss = { modImportVisible = false },
                        onPickFiles = { pickModFiles.launch(Unit) },
                        onRestartGame = ::finishForPatchRestart,
                        intro = strings.modImportIntro,
                        pickButton = strings.modImportPickButton,
                        copy = ModImportCopy.of(lang),
                    )
                }
            }
        }
    }

    /** 切语言：只改本页文案并随改随存（游戏画面不跟随，也不重开会话）。 */
    private fun changeLanguage(next: SettingsLanguage) {
        if (next == language) return
        language = next
        UiPrefs.setSettingsLanguage(this, next)
    }

    /**
     * 「恢复更新提醒」：清掉弹窗里「以后都不」写下的 `update_skipped_version_code`
     * （契约 §1.5 的出口 —— 跳过只对那一个 versionCode 生效，这里连登记一起抹掉）。
     * 不动自动更新总开关，也不立即发起检查：下一次冷启/手动检查自然恢复提醒。
     */
    private fun restoreUpdateReminder() {
        UiPrefs.setUpdateSkippedVersionCode(this, 0L)
        revision++
        toast(text.updateRestoreDone)
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
     * 顶栏「全部重置」—— 重置设置页**所有功能**的出厂值：`GAME` 组条目回到随包出厂
     * `emuera.config` 的目标值（与底部「恢复默认设置…」同一份 [FactoryConfigDefaults.resetTargets]，
     * 对同一批键逐键相同），再清掉移植版应用偏好；**不写盘**，仍保留原版「改完点保存 /
     * 保存并重启」的口径（spec: `ConfigDialog.cs:665-670`、`:672-677` 两个按钮才落盘）。
     *
     * 顺序与重建口径：
     *  1. 一次性重启标记 [UiPrefs.setRestoreRestartPending] 必须在清偏好**之后**置 ——
     *     [UiPrefs.resetAll] 是整表 `clear`，早置会被一并抹掉。顺序由 [ResetPaths.allReset] 钉住，
     *     返回游戏时 [MainActivity] 消费这个标记弹「重开生效」询问，不擅自重开（会丢未存档进度）；
     *  2. 清偏好后必须 `recreate()`：`remember { UiPrefs.xxx }` 是进页时的快照，不重建整棵树，
     *     屏上还是重置前的显示大小 / 语言 / 方向；
     *  3. 但重建会走 [onCreateCore] 的 `SettingsStore.load()`，把盘上的玩家旧值读回模型 ——
     *     所以重建**之前**把目标表寄存到 [ResetPaths.stageGameReset]，新实例 load 完由
     *     [ResetPaths.consumeStagedGameReset] 接回内存（不写盘，`dirty` 与「保存」按钮自然出现）；
     *  4. 对 `emuera.config` 只重置可编辑的 `GAME` 组，`DEBUG` / `REPLACE` 是只读组，不参与写回
     *     （原版保存路径只遍历 `configArray`，spec: `ConfigData.cs:430`）；被 fixed.config 钉住的
     *     条目只计数、不强解钉（spec: `ConfigItem.cs:20-27`），提示如实带上没改成的项目数。
     *
     * 提示按 R2.3 写实：偏好已即时生效、游戏配置尚未写盘；要落盘只能走「保存 / 保存并重启」
     * （`SettingsStore.save` 成功会 `markClean()`，`changedKeys()` 随之变空，这里不另推热更，
     * 与 [save] 同款口径）。
     */
    private fun resetAll() {
        val targets = FactoryConfigDefaults.resetTargets(this)
        val outcome = ResetPaths.allReset(
            model,
            targets,
            { UiPrefs.resetAll(this) },
            { UiPrefs.setRestoreRestartPending(this) },
        )
        // 清偏好 + 置标记都成功之后才寄存：resetPrefs 抛异常时不许留下跨重建残留。
        ResetPaths.stageGameReset(targets)
        revision++
        toast(
            if (outcome.pinned > 0) text.resetAllDonePinned(outcome.written, outcome.pinned)
            else text.resetAllDone(outcome.written),
        )
        recreate()
    }

    /**
     * 「恢复默认设置」（列表底部卡片，二次确认后才进这里）—— 只恢复**原版设置页那批项**：
     * `GAME` 组条目回到随包出厂 `emuera.config` 的目标值并立即写盘
     * （原版「保存」按钮同一落点，spec: `ConfigDialog.cs:665-670`）。
     *
     * 出厂值唯一来源是 [FactoryConfigDefaults.resetTargets]：与顶栏 [resetAll] 传的是同一份读数，
     * 两处对同一批键逐键相同（R1.1）。assets 读不到时它返回空表，
     * [FactoryConfigDefaults.apply] 逐键回落 `ConfigSpec.defaultValue` —— 按钮不会变成死按钮，
     * 提示照常带计数（R1.3）。
     *
     * 与顶栏 [resetAll] 刻意分开的覆盖范围（用户裁定，R3.1）：
     *  1. **不**调用任何 [UiPrefs] 成员：不清应用偏好、不置一次性重启标记，也不切本页语言；
     *  2. **不** `recreate()` —— 只动游戏配置，本页的偏好快照没有失效的理由，也不该重开会话；
     *  3. 写盘失败先 [SettingsModel.restore] 把内存模型回滚到进页时的基线、且不动偏好
     *     （原版取消即丢弃同一口径，spec: `ConfigDialog.cs:679-683`），提示如实说没写盘；
     *  4. [FactoryConfigDefaults.apply] 对 fixed.config 钉住的条目只计数、不强解钉
     *     （spec: `ConfigItem.cs:20-27`），成功提示必须如实带上没改成的项目数。
     */
    private fun restoreDefaults() {
        val outcome = ResetPaths.gameItemsOnly(
            model,
            FactoryConfigDefaults.resetTargets(this),
            { store.save(model) },
        )
        if (!outcome.diskWritten) {
            toast(text.restoreDefaultsWriteFailed)
            revision++
            return
        }
        toast(
            if (outcome.pinned > 0) text.restoreDefaultsDonePinned(outcome.pinned)
            else text.restoreDefaultsDone,
        )
        revision++
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
            toast(text.saveWriteFailed)
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
            toast(text.saveRestartWriteFailed)
            revision++
            return
        }
        if (impact.hotOnly) {
            val hot = pushHot(impact)
            if (hot.isNotEmpty()) {
                toast(text.savedHotNoRestart(hot.size))
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
    private fun saveSummary(impact: SettingsSaveImpact, hot: List<ConfigKey>): String {
        val t = text
        return when {
            impact.isEmpty -> t.summaryNoChange(store.file.name)
            impact.requiresRestart && hot.isNotEmpty() ->
                t.summaryHotRestart(hot.size, restartHint(impact.restart, t))
            impact.requiresRestart -> t.summaryRestart(restartHint(impact.restart, t))
            hot.isNotEmpty() -> t.summaryHot(hot.size)
            else -> t.summaryNoSession
        }
    }

    /** 重启键清单的短提示：最多列 3 个当前语言标签，多的折成「等 N 项」。 */
    private fun restartHint(keys: List<ConfigKey>, t: SettingsStrings): String {
        val head = keys.take(3).joinToString(t.listSeparator) { key ->
            t.configKeyLabel(key, englishLabels)
        }
        return t.restartHint(head, keys.size)
    }

    /** 模型里某个条目的当前显示文本（对话框被拒后回显用）。 */
    private fun displayOf(id: String): String =
        model.entry(id)?.currentValue?.let(::display) ?: ""

    /**
     * 一条夹取提示的显示文本：正文按界面语言取本地化文案；有标题的按原版
     * `Dialog.Show(标题, 正文)` 的形状拼成一行，WINAPI 那条原版没有标题
     * （spec: `Config.cs:458` 走 `MessageBox.Show(正文)`）就只显示正文。
     */
    private fun clampNoticeLine(notice: ConfigClampNotice, t: SettingsStrings): String {
        val language = t.lang.clampLanguage
        val body = ConfigClampLabels.textFor(notice, language)
        val title = ConfigClampLabels.titleFor(notice, language) ?: return body
        return t.titleBody(title, body)
    }

    private fun rejectToast(result: SettingsSetResult.Rejected) {
        val t = text
        toast(t.rejectToast(t.rejectReason(result.reason), t.rejectDetail(result.message)))
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
        if (importing || exporting) return
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
        } catch (error: Throwable) {
            toast(text.configUnreadableImport(error.message.orEmpty()))
            return
        }
        val base = gameBaseOrNull()
        val t = text
        importing = true
        Thread {
            val lines = mutableListOf<ImportLine>()
            val pending = mutableListOf<PendingImport>()
            try {
                for (uri in uris) {
                    val name = displayNameOf(uri, t.selectedFileFallback)
                    try {
                        val bytes = readBounded(uri)
                        if (bytes == null) {
                            lines += ImportLine(t.importUnread(name), false)
                            continue
                        }
                        when (val plan = SaveImport.plan(name, bytes, config, base, slotCount, ::occupiedAt)) {
                            is SaveImport.Reject ->
                                lines += ImportLine(t.importRejected(name, t.saveReason(plan.reason)), false)
                            is SaveImport.Place -> if (plan.occupied) {
                                pending += PendingImport(plan, bytes, name)
                            } else {
                                lines += placeLine(plan, bytes, t)
                            }
                        }
                    } catch (error: Throwable) {
                        // 一个文件判读时抛了未预期的异常，不许拖死整批，也不许让按钮永久卡在「正在导入」
                        val detail = error.message?.takeIf { it.isNotBlank() } ?: t.unexpectedFailure
                        lines += ImportLine(t.importRejected(name, detail), false)
                    }
                }
            } finally {
                runOnUiThread {
                    importing = false
                    importLines = (lines + importLines).take(IMPORT_LINES_MAX)
                    overwritePending = pending
                }
            }
        }.start()
    }

    /** 一条落位的结果行：成功报「哪一格 + 存档自己的备注」，失败照实说没写进去（不掩错、不重试）。 */
    private fun placeLine(place: SaveImport.Place, bytes: ByteArray, t: SettingsStrings): ImportLine {
        val label = t.slotLabel(place.slotLabel)
        return if (SaveImport.commit(gameRootFile, place.targetRel, bytes)) {
            // 没写备注的档不留一个光秃秃的冒号在后面
            ImportLine(t.imported(label, oneLine(place.mes)), ok = true)
        } else {
            ImportLine(t.importWriteFailed(label), ok = false)
        }
    }

    /** 对话框选「覆盖」：把攒下的项后台落盘，结果并进同一叠提示行。 */
    private fun commitOverwrite() {
        val items = overwritePending
        overwritePending = emptyList()
        if (items.isEmpty()) return
        val t = text
        importing = true
        Thread {
            val lines = items.map { placeLine(it.place, it.bytes, t) }
            runOnUiThread {
                importing = false
                importLines = (lines + importLines).take(IMPORT_LINES_MAX)
            }
        }.start()
    }

    /** 对话框选「取消」：那些项一项都不写，玩家原来那一格里的东西原样留着。 */
    private fun discardOverwrite() {
        val t = text
        val lines = overwritePending.map { ImportLine(t.importSkippedOccupied(t.slotLabel(it.place.slotLabel)), false) }
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
    private fun displayNameOf(uri: Uri, fallback: String): String =
        // R7-D1 任务②：实现收进 [UriNames]（MOD 导入链与存档导入共用的公共工具）；
        // 本薄包装保持原签名，[fallback] 之外的回落（末段也没有）在这里补成文案。
        UriNames.displayNameOf(contentResolver, uri, fallback) ?: fallback

    /** 带上限的读取：超过 [SaveImport.MAX_SAVE_BYTES] 立刻收手，选错的文件不该先把整份吸进堆里。 */
    private fun readBounded(uri: Uri): ByteArray? =
        UriNames.readBounded(contentResolver, uri, SaveImport.MAX_SAVE_BYTES.toLong())

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // -----------------------------------------------------------------
    // 补丁（MOD）导入（R7-D1 任务①：入口从 MainActivity 左下角悬浮钮迁到这里；
    // 导入链与状态机从 MainActivity 的私有段原样平移，差异只有三处——游戏根改用
    // [gameRootFile]、会话读数经 [ModImportSessionChannel]、销毁判据只有 isFinishing）。
    // ADR-011：编译只发生在这条用户动作链里——唯一的 PatchCompiler / 产物装载调用点
    // 在 [importMods] 的后台线程；启动路径（MainActivity 的 onCreate / startGameOrDemo）
    // 与 GameHost 装载链不碰本段任何入口。
    // -----------------------------------------------------------------

    /** 导入覆盖层是否打开（[ModImportChrome] 的组合时机；状态本身仍走 [ModImportUiState]）。 */
    private var modImportVisible by mutableStateOf(false)

    /** 导入页状态机（导入中 / 成功 / 失败三态 + 报告快照；转移只走 [ModImportUiReducer]）。 */
    private var modImportState by mutableStateOf<ModImportUiState>(ModImportUiState.Idle(null))

    /** 导入单飞闸：编译上下文要读 328 MB 的 base 产物，并行导入只会翻倍内存峰值。 */
    private val modImportBusy = AtomicBoolean(false)

    /**
     * SAF 多选口上源（`.ERB`/`.ERH`/`.zip`，读授权由 ACTION_OPEN_DOCUMENT 系统授予，
     * 无存储权限）。回调在 UI 线程：读字节与编译都挪后台（上下文读取十几秒，ANR 口径照
     * b076990）。注册必须落在 Activity 进入 STARTED 之前 ⇒ 成员属性（与 [pickSaveFiles]
     * 同一硬要求）。
     *
     * 2026-11 起换 [OpenModDocuments]（mime 提示 zip 等形态）：框架自带的
     * [ActivityResultContracts.OpenMultipleDocuments] 固定万用 mime 且**写死** mime 列表，
     * 部分 OEM 文件选择器只按 EXTRA_MIME_TYPES 过滤可见项，zip 会被藏起来——玩家拿到的
     * 口上包恰是 zip。自建 contract 在 EXTRA_MIME_TYPES 里点名列三类 zip mime，同时保
     * 万用 mime 兜底，既提示又不缩窄可见面。
     */
    private val pickModFiles = registerForActivityResult(
        OpenModDocuments(),
    ) { uris ->
        if (!uris.isNullOrEmpty()) importMods(uris)
    }

    // -----------------------------------------------------------------
    // 素材包导入（Mod 档）：资产 zip 不走编译，直接按 resources/、sound/ 投放。
    // 存储/路径/上限全在 [ResourcePackStore]（纯 JVM 有单测），这里只做 SAF 读流、
    // 后台线程与状态机接线。
    // -----------------------------------------------------------------

    /** 素材导入状态：背景图 / BGM / 压缩包兼容卡各一份（转移只走 [ResourcePackUiReducer]）。 */
    private var backgroundPackState by mutableStateOf<ResourcePackUiState>(ResourcePackUiState.Idle(null))
    private var bgmPackState by mutableStateOf<ResourcePackUiState>(ResourcePackUiState.Idle(null))
    private var archivePackState by mutableStateOf<ResourcePackUiState>(ResourcePackUiState.Idle(null))

    /** BGM 卡的地点名：只用于「直接选音频」时给根级文件定地点（选文件夹时用文件夹名）。 */
    private var bgmLocationInput by mutableStateOf("")

    /** 三张卡各自的结果槽位；写入只经 [setPackState] / [postPackEvent]，别的线程不碰。 */
    private enum class PackSlot { BACKGROUND, BGM, ARCHIVE }

    /** 单飞闸：直投与解包都会写几百 MB，同一时刻只放行一批。 */
    private val resourcePackBusy = AtomicBoolean(false)

    /** 素材包只收 zip / 7z：包内的相对路径是唯一能还原 `resources/`、`sound/` 层级的载体。 */
    private val pickResourcePacks = registerForActivityResult(
        OpenModDocuments(),
    ) { uris ->
        if (!uris.isNullOrEmpty()) importResourcePacks(uris)
    }

    /**
     * 背景图直选（多选）。mime 提示 image 通配：手机相册 / 文件管理器都认这一档，
     * 玩家选完就投进 `resources/背景图/`，不需要先打包。
     */
    private val pickBackgroundImages = registerForActivityResult(
        OpenMaterialDocuments(arrayOf("image/*")),
    ) { uris ->
        if (!uris.isNullOrEmpty()) importMaterialFiles(MaterialTarget.BACKGROUND, uris)
    }

    /** BGM 直选（多选）：audio 通配覆盖 mp3；非 mp3 由 store 逐条跳过并点名。 */
    private val pickBgmTracks = registerForActivityResult(
        OpenMaterialDocuments(arrayOf("audio/*")),
    ) { uris ->
        if (!uris.isNullOrEmpty()) importMaterialFiles(MaterialTarget.BGM, uris)
    }

    /** 背景图整夹（SAF 目录树）：递归投放，散图自动建索引。 */
    private val pickBackgroundFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) importMaterialFolder(MaterialTarget.BACKGROUND, uri)
    }

    /** BGM 整夹（SAF 目录树）：选中地点目录时目录名就是地点名。 */
    private val pickBgmFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) importMaterialFolder(MaterialTarget.BGM, uri)
    }

    private fun packStateOf(slot: PackSlot): ResourcePackUiState = when (slot) {
        PackSlot.BACKGROUND -> backgroundPackState
        PackSlot.BGM -> bgmPackState
        PackSlot.ARCHIVE -> archivePackState
    }

    private fun setPackState(slot: PackSlot, state: ResourcePackUiState) {
        when (slot) {
            PackSlot.BACKGROUND -> backgroundPackState = state
            PackSlot.BGM -> bgmPackState = state
            PackSlot.ARCHIVE -> archivePackState = state
        }
    }

    /**
     * 结果回 UI 线程。三张卡共享同一份现状读数：任何一次导入结束后把 [status] 同步给
     * 其余两槽（只换读数、保留各自结果），否则另一张卡会一直挂着导入前的旧数字。
     */
    private fun postPackEvent(slot: PackSlot, event: ResourcePackEvent) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            setPackState(slot, ResourcePackUiReducer.reduce(packStateOf(slot), event))
            val status = packStateOf(slot).status
            for (other in PackSlot.entries) {
                if (other == slot) continue
                setPackState(other, ResourcePackUiReducer.reduce(packStateOf(other), ResourcePackEvent.StatusRefreshed(status)))
            }
        }
    }

    /** 打开覆盖层：顺手刷新一次报告快照（I7 读数口 + mods 静态清单）。 */
    private fun openModImport() {
        modImportVisible = true
        modImportState = ModImportUiReducer.reduce(
            modImportState,
            ModImportEvent.ReportRefreshed(modImportReportSnapshot(ModImportCopy.of(language))),
        )
    }

    /**
     * 报告快照：会话的 I7 四读数（经 [ModImportSessionChannel] 跨 Activity 读
     * MainActivity 持有的会话）+ mods 目录的静态清单。读不出也折成快照上屏
     * （[ModPatchReportSnapshot.unreadable]，禁静默）；通道未接线或会话没起
     * （标题屏 / 演示屏）时会话侧四项为空，报告只显示 mods 清单。
     *
     * [copy] 由壳层取好传入（调用点多在后台线程，语言照 `UpdateCopy` 的口径随调用进线程）。
     */
    private fun modImportReportSnapshot(copy: ModImportCopy): ModPatchReportSnapshot? {
        val session = runCatching { ModImportSessionChannel.read() }
            .onFailure { Log.w(TAG, "补丁状态：会话读数获取失败（按无会话呈现）", it) }
            .getOrNull()
        val stored = runCatching {
            modImportController().patchFileNamesIn(ModStore.modsDirFor(gameRootFile))
        }.getOrElse { e ->
            Log.w(TAG, "补丁状态：mods 目录读取失败", e)
            return ModPatchReportSnapshot.unreadable(e.message ?: e::class.simpleName ?: copy.unknownStorageError)
        }
        return ModPatchReportSnapshot.of(
            loadedPatches = session?.loadedPatches ?: emptyList(),
            failures = session?.patchLoadFailures ?: emptyList(),
            warnings = session?.patchLoadWarnings ?: emptyList(),
            rolledBack = session?.patchRolledBack ?: false,
            storedPatchFiles = stored,
        )
    }

    /** 导入链与 mods 清单共用的控制器（无状态，随用随建；[ModStore] 也是薄包装）。 */
    private fun modImportController(): ModImportController = ModImportController(
        modStore = ModStore(ModStore.modsDirFor(gameRootFile)),
        baseSource = ProductionBaseCompileContextSource,
        gameRoot = gameRootFile,
    )

    /**
     * 导入动作的响应链：后台读字节 → [ModImportController.import]（源落盘 → 读 base
     * 上下文 → 编译 → I3 预检 → 产物落 `.patch`）→ 回 UI 线程更新状态机。
     *
     * 为什么全后台：上下文读取（mmap 装载 + 侧车解析）十几秒，UI 线程做必 ANR；
     * 为什么读不到的文件不整体放弃：多选里一份坏文件不该挡住其余文件（错误文案
     * 附在导入结果后面一起上屏）。
     */
    private fun importMods(uris: List<Uri>) {
        if (!modImportBusy.compareAndSet(false, true)) {
            Log.w(TAG, "补丁导入请求被忽略：上一次导入还在进行")
            return
        }
        // 语言一次取好随调用进线程（照 UpdateCopy 的口径：纯层与后台线程不碰 prefs）。
        val copy = ModImportCopy.of(language)
        modImportState = ModImportUiReducer.reduce(modImportState, ModImportEvent.ImportStarted(uris.size), copy)
        Thread({
            try {
                val picked = ArrayList<PickedModFile>(uris.size)
                val readErrors = ArrayList<String>()
                for (uri in uris) {
                    val name = UriNames.displayNameOf(contentResolver, uri, fallback = null)
                    val bytes = if (name == null) null else UriNames.readBounded(
                        contentResolver,
                        uri,
                        ModStore.MAX_MOD_BYTES,
                    )
                    if (name == null || bytes == null) {
                        readErrors.add(copy.fill(copy.sourceReadFailedFormat, uri.lastPathSegment ?: uri))
                        continue
                    }
                    picked.add(PickedModFile(name, bytes))
                }
                val outcome = if (picked.isEmpty()) {
                    ModImportOutcome.Failed(
                        ModImportFailureKind.STORAGE,
                        copy.fill(copy.allReadsFailedFormat, readErrors.joinToString("\n")),
                    )
                } else {
                    modImportController().import(picked, copy)
                }
                val report = modImportReportSnapshot(copy)
                val event = when (outcome) {
                    is ModImportOutcome.Imported ->
                        ModImportEvent.ImportSucceeded(outcome.detail, report)
                    is ModImportOutcome.Failed ->
                        ModImportEvent.ImportFailed(
                            outcome.kind,
                            if (readErrors.isEmpty()) outcome.message
                            else outcome.message + "\n" + readErrors.joinToString("\n"),
                            report,
                        )
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    modImportState = ModImportUiReducer.reduce(modImportState, event)
                }
            } catch (t: Throwable) {
                // 导入线程没有静默出口：兜底也上屏（报错页），宁可重复呈现不可吞。
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    modImportState = ModImportUiReducer.reduce(
                        modImportState,
                        ModImportEvent.ImportFailed(
                            ModImportFailureKind.UNEXPECTED,
                            copy.fill(copy.unexpectedFormat, t::class.simpleName ?: "null", t.message ?: "null"),
                            modImportReportSnapshot(copy),
                        ),
                    )
                }
            } finally {
                modImportBusy.set(false)
            }
        }, "eratw-modimport").start()
    }

    /**
     * 素材包导入的响应链：SAF → 缓存临时包 → [ResourcePackStore.install] →
     * [ResourcePackImportBatch] 判三态 → 刷新现状读数。
     *
     * 先落缓存文件而不是整份进堆：背景图包在数百 MB 量级，[UriNames.readBounded] 那条
     * 「整份读进 ByteArray」的口不适用。逐包失败不打断其余包；**成功不再等于「install 没抛」**
     * ——自动分类后整包被跳过 / 结构认不出也必须报失败，「0 文件」永远进不了成功态。
     *
     * 全部玩家面文案在进线程前取一次 [ResourcePackCopy]（后台不碰 prefs、不判 Locale）；
     * 跳过明细、结构体检警示行与失败原因都由该表拼句，壳层不再出现内联中文。
     */
    private fun importResourcePacks(uris: List<Uri>) {
        if (!resourcePackBusy.compareAndSet(false, true)) {
            Log.w(TAG, "素材导入请求被忽略：上一次导入还在进行")
            return
        }
        val copy = ResourcePackCopy.of(language)
        setPackState(
            PackSlot.ARCHIVE,
            ResourcePackUiReducer.reduce(packStateOf(PackSlot.ARCHIVE), ResourcePackEvent.ImportStarted(uris.size)),
        )
        Thread({
            try {
                val store = ResourcePackStore(gameRootFile)
                val items = ArrayList<ResourcePackBatchItem>(uris.size)
                // 还没走到 install 就失败的包（不是 zip / 7z / 缓存读不出或超上限），每包一行。
                val preflightFailures = ArrayList<String>()
                val samples = ArrayList<String>()
                val warnings = ArrayList<String>()
                for ((index, uri) in uris.withIndex()) {
                    val name = UriNames.displayNameOf(contentResolver, uri, fallback = null)
                        ?: uri.lastPathSegment
                        ?: uri.toString()
                    val isZip = name.endsWith(".zip", ignoreCase = true)
                    val isSevenZip = name.endsWith(".7z", ignoreCase = true)
                    if (!isZip && !isSevenZip) {
                        preflightFailures += copy.fill(copy.failedNotZipFormat, name)
                        continue
                    }
                    // 魔数在 store 里再判一次；这里只按扩展名放行，临时文件保留原后缀便于排障。
                    val suffix = if (isSevenZip) "7z" else "zip"
                    val temp = File(cacheDir, "resource-pack-$index-${System.nanoTime()}.$suffix")
                    try {
                        if (copyToTemp(uri, temp) == null) {
                            // 单键覆盖两种失败：读不出来 / 超过上限（{1} 是上限读数）。
                            preflightFailures += copy.fill(
                                copy.failedReadFormat,
                                name,
                                RESOURCE_PACK_ARCHIVE_MAX / (1024 * 1024),
                            )
                            continue
                        }
                        val report = store.install(temp)
                        items += ResourcePackBatchItem(
                            name = name,
                            installedFiles = report.installedFiles,
                            skippedEntries = report.skippedEntries,
                            overwrittenFiles = report.overwrittenFiles,
                            error = report.error,
                        )
                        samples += report.skippedSamples
                        warnings += structureWarningLines(copy, report.structureNotes)
                        // 落了一部分但被上限截停：仍算成功包，但错误原话必须留痕（禁静默）。
                        if (report.error != null && report.installedFiles > 0) {
                            warnings += copy.fill(
                                copy.noteLineFormat,
                                copy.fill(copy.failedUnzipFormat, name, report.error),
                            )
                        }
                    } finally {
                        temp.delete()
                    }
                }
                val status = runCatching { ResourcePackStore.scanStatus(gameRootFile) }.getOrNull()
                val verdict = ResourcePackImportBatch.judge(copy, items, preflightFailures)
                // 部分成功时逐包失败原因也要上屏（结构体检行之后再列）；全失败时这些行
                // 已经并进 ImportFailed.message，别重复。
                if (verdict.outcome == ResourcePackBatchOutcome.PARTIAL) {
                    warnings += verdict.failedLines
                }
                val shownSamples = samples.take(RESOURCE_PACK_NOTES_MAX)
                val shownWarnings = warnings.take(RESOURCE_PACK_NOTES_MAX)
                val summary = ResourcePackImportSummary(
                    packs = verdict.packs,
                    failedPacks = verdict.failedPacks,
                    files = verdict.files,
                    overwritten = verdict.overwritten,
                    skipped = verdict.skipped,
                    skippedSamples = shownSamples,
                    warningLines = shownWarnings,
                    message = verdict.message,
                )
                val event = when (verdict.outcome) {
                    ResourcePackBatchOutcome.SUCCEEDED ->
                        ResourcePackEvent.ImportSucceeded(summary, status)
                    ResourcePackBatchOutcome.PARTIAL ->
                        ResourcePackEvent.ImportPartial(summary, verdict.failedPacks, status)
                    ResourcePackBatchOutcome.FAILED -> ResourcePackEvent.ImportFailed(
                        // 失败态只有一条 message：把结构体检警示行接在逐包失败明细后面
                        // （全跳过 / 结构认不出时，那几句才是玩家最需要看到的指路）。
                        message = buildString {
                            append(verdict.message)
                            for (line in shownWarnings) append('\n').append(line)
                        },
                        skippedSamples = shownSamples,
                        status = status,
                    )
                }
                postPackEvent(PackSlot.ARCHIVE, event)
            } catch (t: Throwable) {
                Log.e(TAG, "素材导入未预期异常", t)
                val status = runCatching { ResourcePackStore.scanStatus(gameRootFile) }.getOrNull()
                postPackEvent(
                    PackSlot.ARCHIVE,
                    ResourcePackEvent.ImportFailed(
                        message = copy.fill(
                            copy.unexpectedFormat,
                            t::class.simpleName ?: copy.unknownStorageError,
                            t.message ?: copy.unknownStorageError,
                        ),
                        skippedSamples = emptyList(),
                        status = status,
                    ),
                )
            } finally {
                resourcePackBusy.set(false)
            }
        }, "eratw-respack").start()
    }

    /**
     * 直选文件（单选 / 多选）→ [ResourcePackStore.installSource]。不复制到缓存、不打包：
     * SAF 流在 store 的写入循环里逐条读，落盘即止。
     *
     * BGM 的地点名取卡里那段输入；留空时 store 会落进「自定义」并在结果里点名。
     */
    private fun importMaterialFiles(target: MaterialTarget, uris: List<Uri>) {
        val slot = if (target == MaterialTarget.BGM) PackSlot.BGM else PackSlot.BACKGROUND
        val label = if (target == MaterialTarget.BGM) bgmLocationInput.trim() else ""
        importMaterial(slot, target) { SafFileMaterialSource(label, contentResolver, uris) }
    }

    /** 整夹导入：目录树递归枚举（不先复制），文件夹名就是 BGM 卡的默认地点名。 */
    private fun importMaterialFolder(target: MaterialTarget, treeUri: Uri) {
        val slot = if (target == MaterialTarget.BGM) PackSlot.BGM else PackSlot.BACKGROUND
        importMaterial(slot, target) {
            SafTreeMaterialSource(SafMaterialSources.treeLabel(contentResolver, treeUri), contentResolver, treeUri)
        }
    }

    private fun importMaterial(slot: PackSlot, target: MaterialTarget, source: () -> MaterialSource) {
        if (!resourcePackBusy.compareAndSet(false, true)) {
            Log.w(TAG, "素材直投请求被忽略：上一次导入还在进行")
            return
        }
        val copy = ResourcePackCopy.of(language)
        setPackState(slot, ResourcePackUiReducer.reduce(packStateOf(slot), ResourcePackEvent.ImportStarted(1)))
        Thread({
            try {
                val material = source()
                val report = ResourcePackStore(gameRootFile).installSource(material, target)
                postMaterialReport(slot, copy, material.label, report)
            } catch (t: Throwable) {
                Log.e(TAG, "素材直投未预期异常", t)
                val status = runCatching { ResourcePackStore.scanStatus(gameRootFile) }.getOrNull()
                postPackEvent(
                    slot,
                    ResourcePackEvent.ImportFailed(
                        message = copy.fill(
                            copy.unexpectedFormat,
                            t::class.simpleName ?: copy.unknownStorageError,
                            t.message ?: copy.unknownStorageError,
                        ),
                        skippedSamples = emptyList(),
                        status = status,
                    ),
                )
            } finally {
                resourcePackBusy.set(false)
            }
        }, "eratw-respack-direct").start()
    }

    /**
     * 直投结果的玩家面汇总：判三态复用 [ResourcePackImportBatch]（成功 iff 真有文件落盘），
     * 再加上直投专属的三行提示（自动索引 / 自动编号 / 落盘被上限截停）。
     */
    private fun postMaterialReport(
        slot: PackSlot,
        copy: ResourcePackCopy,
        sourceLabel: String,
        report: ResourcePackReport,
    ) {
        val status = runCatching { ResourcePackStore.scanStatus(gameRootFile) }.getOrNull()
        val name = sourceLabel.ifBlank { copy.materialSelectionLabel }
        val item = ResourcePackBatchItem(
            name = name,
            installedFiles = report.installedFiles,
            skippedEntries = report.skippedEntries,
            overwrittenFiles = report.overwrittenFiles,
            error = report.error,
        )
        val verdict = ResourcePackImportBatch.judge(copy, listOf(item), material = true)
        val warnings = ArrayList<String>()
        // 0 文件时失败句已经点名「没有可用的素材」，结构体检那条不再重复一遍。
        val notes = if (report.installedFiles == 0) {
            report.structureNotes.filterNot { it.kind == StructureNoteKind.MATERIAL_NO_USABLE_FILES }
        } else {
            report.structureNotes
        }
        warnings += structureWarningLines(copy, notes)
        report.generatedIndex?.let {
            warnings += copy.fill(copy.noteLineFormat, copy.fill(copy.autoIndexGeneratedFormat, it))
        }
        if (report.autoRenamed > 0) {
            warnings += copy.fill(copy.noteLineFormat, copy.fill(copy.autoRenamedFormat, report.autoRenamed))
        }
        if (report.error != null && report.installedFiles > 0) {
            warnings += copy.fill(copy.noteLineFormat, copy.fill(copy.materialImportFailedFormat, name, report.error))
        }
        val samples = report.skippedSamples.take(RESOURCE_PACK_NOTES_MAX)
        val shownWarnings = warnings.take(RESOURCE_PACK_NOTES_MAX)
        val summary = ResourcePackImportSummary(
            packs = verdict.packs,
            failedPacks = verdict.failedPacks,
            files = verdict.files,
            overwritten = verdict.overwritten,
            skipped = verdict.skipped,
            skippedSamples = samples,
            warningLines = shownWarnings,
            message = verdict.message,
        )
        val event = when (verdict.outcome) {
            ResourcePackBatchOutcome.SUCCEEDED -> ResourcePackEvent.ImportSucceeded(summary, status)
            ResourcePackBatchOutcome.PARTIAL -> ResourcePackEvent.ImportPartial(summary, verdict.failedPacks, status)
            ResourcePackBatchOutcome.FAILED -> ResourcePackEvent.ImportFailed(
                message = buildString {
                    append(verdict.message)
                    for (line in shownWarnings) append('\n').append(line)
                },
                skippedSamples = samples,
                status = status,
            )
        }
        postPackEvent(slot, event)
    }

    /**
     * SAF 流 → 缓存文件，超过 [RESOURCE_PACK_ARCHIVE_MAX] 返回 null（不返回部分文件）。
     * 打不开 / 读失败同样返回 null；错误由调用方折成明细行上屏。
     */
    private fun copyToTemp(uri: Uri, target: File): Long? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > RESOURCE_PACK_ARCHIVE_MAX) return null
                    out.write(buffer, 0, read)
                }
                total
            }
        }
    }.getOrNull()

    /**
     * 「重启游戏使补丁/素材生效」的落点：补丁产物在 `mods/`、背景图索引在启动期装载，
     * 都要新会话——设置页的重启通道就是 [EXTRA_RESTART_GAME] 结果
     * （[MainActivity.onActivityResult] 消费后拆会话重开）。与「保存并重启游戏」同一条路。
     */
    private fun finishForPatchRestart() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESTART_GAME, true))
        finish()
    }

    // -----------------------------------------------------------------
    // 一键导出存档（收集在后台，落点由系统选择器给，写盘也在后台）
    // -----------------------------------------------------------------

    /**
     * 点「一键导出存档」：按当前模型的口径收集全部存档，再弹系统「新建文件」选择器。
     *
     * 口径与导入同源（[SettingsModel.effectiveConfig] 的三项），这样「导出时看哪几个目录」
     * 与「导入时往哪落」永远是同一句话。收集放在后台：槽多时也是一次目录枚举 + 逐文件
     * 取大小，不能压在 UI 线程上。
     */
    private fun startExport() {
        if (exporting || importing) return
        val t = text
        val config: SaveConfig
        try {
            val effective = model.effectiveConfig()
            config = SaveConfig(
                systemSaveInBinary = effective.bool(ConfigKey.SystemSaveInBinary),
                zipSaveData = effective.bool(ConfigKey.ZipSaveData),
                useSaveFolder = effective.bool(ConfigKey.UseSaveFolder),
            )
        } catch (error: Throwable) {
            toast(t.configUnreadableExport(error.message.orEmpty()))
            return
        }
        exporting = true
        Thread {
            val bundle = runCatching { SaveExport.collect(gameRootFile, config) }.getOrNull()
            runOnUiThread {
                when {
                    bundle == null -> {
                        exporting = false
                        pushExportLine(ExportLine(t.exportCollectFailed, false))
                    }

                    bundle.isEmpty -> {
                        exporting = false
                        pushExportLine(ExportLine(t.exportNothing, false))
                        bundle.skipped.forEach { pushExportLine(skipLine(it, t)) }
                    }

                    else -> {
                        pendingExport = bundle
                        val launched = runCatching {
                            createExportFile.launch(SaveExport.suggestName(LocalDateTime.now()))
                        }.isSuccess
                        if (!launched) {
                            pendingExport = null
                            exporting = false
                            pushExportLine(ExportLine(t.exportPickerFailed, false))
                        }
                    }
                }
            }
        }.start()
    }

    /** 选择器给了目标文件：后台写 zip，成功/失败与跳过的文件各占一行。 */
    private fun writeExport(uri: Uri, bundle: SaveExport.Bundle) {
        val t = text
        Thread {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { out -> SaveExport.writeZip(out, bundle.entries) }
            }.getOrNull() ?: SaveExport.ZipResult(ok = false, written = 0, failed = null)
            val name = displayNameOf(uri, t.selectedFileFallback)
            runOnUiThread {
                exporting = false
                if (result.ok) {
                    // 没写全时不许报成成功：zip 里卡在半截的条目照样写不回去
                    pushExportLine(
                        ExportLine(t.exportDone(result.written, bundle.hasGlobal, name), true),
                    )
                } else {
                    val failed = result.failed?.substringAfterLast('/')
                    pushExportLine(ExportLine(t.exportFailed(failed, result.written), false))
                }
                bundle.skipped.forEach { pushExportLine(skipLine(it, t)) }
            }
        }.start()
    }

    private fun pushExportLine(line: ExportLine) {
        exportLines = (listOf(line) + exportLines).take(EXPORT_LINES_MAX)
    }

    private fun skipLine(skipped: SaveExport.Skipped, t: SettingsStrings): ExportLine =
        ExportLine(t.exportSkipped(skipped.name, t.saveReason(skipped.reason)), false)

    // -----------------------------------------------------------------
    // 运行诊断（读 files/eratw-run 的轨迹 → 判读 → 落成可分享的报告）
    // -----------------------------------------------------------------

    /**
     * 生成一份运行诊断；`share = true` 走系统分享面板，否则整份进剪贴板。
     *
     * 轨迹只有 64 行（约几 KB），但读盘与拼串仍放后台：设置页可能在装载刚失败的机器上
     * 打开，UI 线程不该再碰文件。生成物写进错误报告目录（`eratw-reports`），才能复用
     * [ReportFileProvider] 的只读分享通道（它只放行 `eratw-report-*.txt`）。
     */
    private fun generateDiagnostics(share: Boolean) {
        if (diagBusy) return
        diagBusy = true
        val t = text
        Thread {
            val file = runCatching { writeDiagnosticsReport() }.getOrNull()
            runOnUiThread {
                diagBusy = false
                if (file == null) {
                    pushDiagLine(DiagLine(t.diagFailed, false))
                    return@runOnUiThread
                }
                pushDiagLine(DiagLine(t.diagReady(file.name), true))
                if (share) launchDiagnosticsShare(file, t) else copyDiagnostics(file, t)
            }
        }.start()
    }

    /** 读 `files/eratw-run` 的两份文件，拼成诊断文本并落盘；任何一步失败都返回 null。 */
    private fun writeDiagnosticsReport(): File? {
        val run = File(filesDir, "eratw-run")
        val state = runCatching { File(run, RunTrail.STATE_FILE).readText() }.getOrNull()
        val trail = runCatching { File(run, RunTrail.TRAIL_FILE).readLines() }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
        val now = LocalDateTime.now()
        val report = RunDiagnostics.reportText(
            state = state,
            trail = trail,
            headerLines = ErrorReportCollector.headerLines(this),
            buildStamp = BuildConfig.eratwBuildStamp,
            generatedAtText = now.format(DIAG_TIME),
        )
        val dir = ErrorReportCollector.reportDir(this)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, "eratw-report-${now.format(DIAG_NAME)}-trail.txt")
        file.writeText(report, Charsets.UTF_8)
        ErrorReportCollector.prune(dir)
        return file
    }

    /**
     * 「复制诊断」：整份诊断进剪贴板。
     *
     * UI 线程磁盘 IO 的裁定（fix-uiowiring 车道，沿 obs-uiio 在失败屏「复制报告」的**乙**拆法）：
     * 这一发同样落在一颗按钮的回调里，既不在「进程将死」的边上也不是第一帧之前 ⇒ 读盘挪后台线程，
     * 剪贴板写入与 Toast 回主线程（Android 10+ 只有前台应用能写剪贴板，挪走写入才是改语义）。
     * 两条 Toast 文案（[SettingsStrings.diagCopied] / [SettingsStrings.diagCopyFailed]）一字不变，
     * 只是到的时机晚一个后台读的耗时；读失败与剪贴板失败共用「复制失败」那一句，与旧组合口一致。
     */
    private fun copyDiagnostics(file: File, t: SettingsStrings) {
        Thread {
            val reportText = ErrorReportCollector.readReportText(file)
            runOnUiThread {
                val ok = reportText != null && ErrorReportCollector.putClipboardText(this, reportText)
                toast(if (ok) t.diagCopied else t.diagCopyFailed)
            }
        }.start()
    }

    private fun launchDiagnosticsShare(file: File, t: SettingsStrings) {
        val chooser = ErrorReportCollector.shareIntent(this, file)
            ?.let { Intent.createChooser(it, t.diagShareTitle) }
        if (chooser == null) {
            toast(t.diagShareFailed)
            return
        }
        runCatching { startActivity(chooser) }.onFailure { toast(t.diagShareFailed) }
    }

    private fun pushDiagLine(line: DiagLine) {
        diagLines = (listOf(line) + diagLines).take(DIAG_LINES_MAX)
    }

    companion object {

        /** logcat 标签（只在「设置页打不开」那条兜底日志上用）。 */
        private const val TAG = "EratwSettings"

        /**
         * [setResult] 结果 extra：true = 「保存并重启游戏」（原版 `ConfigDialogResult.SaveReboot`，
         * spec: `ConfigDialog.cs:672-678` + `MainWindow.cs:1066-1069`）。
         *
         * 常量名与语义由 [MainActivity] 的 `onActivityResult` 消费，**不可改名**。
         */
        const val EXTRA_RESTART_GAME = "com.eratw.port.android.settings.RESTART_GAME"

        /** 导入结果最多留几行（卡片不是日志，留头几条就够看清刚才发生了什么）。 */
        private const val IMPORT_LINES_MAX = 12

        /** 导出结果的行数上限（与导入同一口径）。 */
        private const val EXPORT_LINES_MAX = 12

        /** 诊断结果最多留几行（卡片不是日志）。 */
        private const val DIAG_LINES_MAX = 6

        /**
         * 设置页通道现状 / 下载进度的前台刷新间隔（U5 P2-12）：比服务的 3 秒进度心跳更疏，
         * 只重读进程内已有读数，不开第二条下载线程、不改通知那边的刷新节流。
         */
        private const val SETTINGS_READOUT_REFRESH_MILLIS = 5_000L

        /** 下载进度条在跑时的心跳：只读进程内活字段，不读偏好、不读盘，所以可以加密。 */
        private const val SETTINGS_PROGRESS_REFRESH_MILLIS = 250L

        /** 没有下载时探一次「是不是刚开跑」的间隔；够挡住按钮点下到服务起单的空窗即可。 */
        private const val SETTINGS_PROGRESS_IDLE_POLL_MILLIS = 1_000L

        /** 素材导入结果里保留的跳过/错误明细条数（多包拼接后仍要能一屏读完）。 */
        private const val RESOURCE_PACK_NOTES_MAX = 12

        /** 一份素材 zip 的落盘上限（解包后另有 [ResourcePackStore.MAX_TOTAL_BYTES] 兜底）。 */
        private const val RESOURCE_PACK_ARCHIVE_MAX = 1024L * 1024 * 1024

        /** 诊断文件名里的时间戳（与错误报告同款，本地时区）。 */
        private val DIAG_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** 诊断正文里的生成时间。 */
        private val DIAG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
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
 * 壳在会话生命周期两端各接线一次（[install] 带宿主身份装 provider、[uninstall] 认主卸载），
 * 设置页保存时经 [push] 现取现用；provider 返回 null（没有在跑的会话：设置页单独打开、
 * 已退回标题、会话已关 / 正在拆）就是空操作，只落盘。
 *
 * ## 接线（已落地，上游形态）
 * `GameSession.configStore` 是 public val（`GameSession.kt:277`，init 装配期一次写死 `:301`）；
 * `GameHost` 侧取用口 = `liveConfigStore()`（读 @Volatile 的 `session`），壳侧接线在
 * **MainActivity 生命周期两端**：装载页就绪处 `HotConfigChannel.install(this) { host?.liveConfigStore() }`、
 * 销毁清场处 `HotConfigChannel.uninstall(this)`（认主：旧壳无权撬新壳的接线）——
 * 两者都跑在 UI 线程上，与设置页保存的 [push] 天然互斥，不会推给一个正在拆的会话。
 * 不接也不崩：push 空表，热更键退化为「写盘 + 下次进入游戏生效」。
 *
 * ## 落地口径
 * 真正的写入与重算在 core 的 `SettingsModel.pushHotInto` → `EffectiveConfig.reloadHot`：
 * 只写 [com.eratw.port.core.config.ConfigApplyMode.HOT_KEYS] 里的键，重启键一个字节都不动。
 */
object HotConfigChannel {

    /**
     * 运行中会话的条目表提供者；null = 未接线（壳还没 start）或已卸载。provider 惰性
     * 取用，每次 [push] 现调一次：会话未装配完 / 已清场时它自己返回 null（GameHost 侧的
     * `session` 是 @Volatile），两层判空合起来即「没有在跑的会话 ⇒ 空表」。
     * 写入端跨线程：install/uninstall 在 UI 线程（MainActivity 生命周期两端），卸载也可能由
     * 重启分流触发，读取在 UI 线程（设置页保存）⇒ `@Volatile`。
     */
    @Volatile
    private var liveStore: (() -> ConfigStore?)? = null

    /**
     * 当前接线的宿主身份（与 [liveStore] 同生同灭）：闭包读的是宿主的活字段，必然捕获
     * 装它的壳（fix-staticpin 阶段一实测：捕获槽反射可见、接通期间 WeakReference 收不回），
     * 所以「静态槽指向当前活壳」不可消除、也不需要消除 —— 需要消除的是**指向死壳**的形态。
     * 口径与 `CrashForensics.crashProviderOwner` 同款：卸载只认身份，旧壳在双壳交接窗口里
     * （真机实测 `Activities: 2`：旧壳的 onDestroy 可能跑在新壳接线**之后**）不得把新壳
     * 刚装好的通道撬空；否则热更键静默退化成「只落盘」，且摘除点与接线点对不上账。
     */
    @Volatile
    private var wiredOwner: Any? = null

    /**
     * 壳接线：以 [owner]（装这条接线的宿主）为身份装上 [provider]。
     * 重复 install 以最后一次为准（新一局装载完成点覆盖旧接线，幂等）。
     */
    fun install(owner: Any, provider: (() -> ConfigStore?)?) {
        wiredOwner = owner
        liveStore = provider
    }

    /**
     * 认主卸载（MainActivity 销毁/重启分流清场侧）：只有当 [owner] 正是当前接线的宿主
     * 才摘；别人的接线（新壳装的）一个字节不动。[liveStore] 为 null 时槽本来就是空的，
     * 身份位也一并复位，不留「null provider + 陈旧 owner」的畸形态。
     */
    fun uninstall(owner: Any) {
        if (wiredOwner === owner) {
            wiredOwner = null
            liveStore = null
        }
    }

    /** 当前接线的宿主身份；null = 未接线（装/摘是否成对的判据钉在这里，JVM 单测直读）。 */
    internal fun wiredOwnerSnapshot(): Any? = wiredOwner

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

/**
 * 设置页用到的固定色 —— 全部转引 [EratwInk]（壳层唯一墨色源）。
 *
 * 这层名字留着是因为页面里几十处按语义取色（`UiColors.TextMuted` 这一类），
 * 而值只许有一处：将来调色只动 [EratwInk]，设置页与游戏外壳的弹窗不会因此分叉。
 */
private object UiColors {
    val Background: Color = EratwInk.background()
    val Card: Color = EratwInk.panel()
    val CardAlt: Color = EratwInk.panelAlt()
    val Border: Color = EratwInk.border()
    val Text: Color = EratwInk.text()
    val TextDim: Color = EratwInk.textDim()
    val TextMuted: Color = EratwInk.textMuted()
    val Accent: Color = EratwInk.accent()
    val Warn: Color = EratwInk.warn()
    val PrimaryButton: Color = EratwInk.primaryButton()

    // 四个分区各自一色：卡片左侧细条、卡片标题、分区带都用它，扫一眼就知道自己在哪一档。
    // 移植版沿用原来的浅蓝（不动老玩家的既视感）；原版引擎取紫，和警告橙、按钮绿都不撞；
    // Mod 取低饱和铜金（暖调、但比警告橙暗）；关于/反馈取主题主绿的亮档。
    val ZonePort: Color = EratwInk.zonePort()
    val ZoneEngine: Color = EratwInk.zoneEngine()
    val ZoneMod: Color = EratwInk.zoneMod()
    val ZoneAbout: Color = EratwInk.zoneAbout()
}

/**
 * 设置页的四个分区（顶栏下方的一排标签页）。
 *
 * 分档的依据是**写什么**，不是主题：[PORT] 与 [ABOUT] 里的东西随改随存、只落
 * `SharedPreferences`（`UiPrefs`）和根本不写；[ENGINE] 那一档是原版 Emuera 的
 * `emuera.config` 条目，改完必须点「保存」才生效；[MOD] 不写配置，只往游戏根投放
 * 素材/补丁文件（补丁与图像类素材要重启才生效）。
 */
private enum class SettingsZone(val accent: Color) {
    PORT(UiColors.ZonePort),
    ENGINE(UiColors.ZoneEngine),
    MOD(UiColors.ZoneMod),
    ABOUT(UiColors.ZoneAbout),
    ;

    companion object {
        /** 默认档：移植版功能 —— 绝大多数玩家进设置页只为这一档，别让他们先翻过 100 条引擎项。 */
        val DEFAULT: SettingsZone = PORT
    }
}

/**
 * 当前分区的主题色。卡片外观（左侧细条、标题色）由它推导，
 * 于是每张卡不必各自声明属于哪一档：分区切换时整档卡片一起换色。
 */
private val LocalZoneAccent = compositionLocalOf { UiColors.ZonePort }

private fun SettingsStrings.zoneTabLabel(zone: SettingsZone): String = when (zone) {
    SettingsZone.PORT -> zonePortTab
    SettingsZone.ENGINE -> zoneEngineTab
    SettingsZone.MOD -> zoneModTab
    SettingsZone.ABOUT -> zoneAboutTab
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
    onRestoreDefaults: () -> Unit,
    onSave: () -> Unit,
    onSaveAndRestart: () -> Unit,
    onBack: () -> Unit,
    importing: Boolean,
    importLines: List<ImportLine>,
    overwritePending: List<PendingImport>,
    onPickSaveFiles: () -> Unit,
    onOverwriteConfirm: () -> Unit,
    onOverwriteCancel: () -> Unit,
    modImportCopy: ModImportCopy,
    modImportState: ModImportUiState,
    onOpenModImport: () -> Unit,
    resourcePackCopy: ResourcePackCopy,
    backgroundPackState: ResourcePackUiState,
    bgmPackState: ResourcePackUiState,
    archivePackState: ResourcePackUiState,
    bgmLocation: String,
    onBgmLocationChange: (String) -> Unit,
    onPickBackgroundFiles: () -> Unit,
    onPickBackgroundFolder: () -> Unit,
    onPickBgmFiles: () -> Unit,
    onPickBgmFolder: () -> Unit,
    onPickResourcePacks: () -> Unit,
    onRestartForResources: () -> Unit,
    exporting: Boolean,
    exportLines: List<ExportLine>,
    diagBusy: Boolean,
    diagLines: List<DiagLine>,
    onShareDiagnostics: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onExport: () -> Unit,
    text: SettingsStrings,
    language: SettingsLanguage,
    onLanguageChange: (SettingsLanguage) -> Unit,
    onCheckUpdate: () -> Unit,
    updateChecking: Boolean,
    updateReadout: UpdateChannelReadout.Readout,
    downloadProgress: DownloadProgress,
    updateCopy: UpdateCopy,
    failedDownloadKind: UpdateDownloadKind?,
    onRetryFailedDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    skippedUpdateVersion: Long,
    onRestoreUpdateReminder: () -> Unit,
) {
    val context = LocalContext.current
    // 两项 app 侧偏好：随改随存（UiPrefs 直接写 SharedPreferences），不参与 emuera.config 的保存。
    var fontColumns by remember { mutableIntStateOf(UiPrefs.fontColumns(context)) }
    var screenFit by remember { mutableStateOf(UiPrefs.screenFit(context)) }
    // 更精简的游戏选项：只显示屏上自带编号的常用指令（默认关）
    var leanOptions by remember { mutableStateOf(UiPrefs.leanOptions(context)) }
    // 竖屏模式（默认关 = 锁横屏）：随改随存；重建 Activity 才换壳，返回游戏时由
    // MainActivity.onActivityResult 认这次改动并征求「现在重开」。
    var portraitMode by remember { mutableStateOf(UiPrefs.portraitMode(context)) }
    // 窗口宽窄变化时重新排版（LIVE-REFLOW，默认关）：随改随存，下一次会话装配生效
    // （与字号/列数档同口径：改完要重开一局）。
    var liveReflow by remember { mutableStateOf(UiPrefs.liveReflow(context)) }
    // 键入回显复刻（ECHO-INPUT，默认关 = 点剧本后不再多出一行编号）：随改随存，
    // 下一次会话装配生效（与 LIVE-REFLOW 同口径，改完要重开一局）。
    var echoInput by remember { mutableStateOf(UiPrefs.echoInput(context)) }
    // 启动画面样式（玩家面加载页外观）：随改随存，下一次启动生效
    var loadingStyle by remember { mutableStateOf(UiPrefs.loadingStyle(context)) }
    // 底边抓手位置（输入栏手势条）
    var knobAt by remember { mutableFloatStateOf(UiPrefs.inputKnobFraction(context)) }
    // 右侧选项栏按钮大小档（随改随存；纯壳层几何，不必重开游戏）
    var railButtonSize by remember { mutableStateOf(UiPrefs.railButtonSize(context)) }
    // 标题彩蛋屏蔽（引擎级 EngineFunctionPolicy 的宿主开关）
    var disableBadApple by remember { mutableStateOf(UiPrefs.disableBadApple(context)) }
    // 自动更新开关（国内镜像仓通道）：随改随存，下一次检查生效。
    var autoUpdate by remember { mutableStateOf(UiPrefs.autoUpdateEnabled(context)) }
    // 仅 Wi-Fi 下载（U5 P2-10）：默认关，随改随存，偏好键在 UiPrefs。
    var wifiOnly by remember { mutableStateOf(UiPrefs.wifiOnlyDownload(context)) }
    // 匿名玩家数统计：默认开、可在设置里关；随改随存，冷启接线读的就是这个键。
    var playerStats by remember { mutableStateOf(UiPrefs.playerStatsEnabled(context)) }
    // 正在编辑的配置条目（null = 没有对话框）；布尔项不弹框，直接走开关。
    var editing by remember { mutableStateOf<EditRequest?>(null) }
    // 「恢复默认设置」的二次确认（列表底部那张卡片点出来的）
    var confirmRestore by remember { mutableStateOf(false) }
    // 当前分区（页内状态，不落盘）：进页先看移植版功能，原版引擎那一档要主动翻过去。
    var zone by remember { mutableStateOf(SettingsZone.DEFAULT) }
    val gridState = rememberLazyStaggeredGridState()
    // 换档必须回到列表顶：网格记的是「第几格」，从条目最多的引擎档翻到一半再切回来，
    // 会停在短列表的末尾，看着像上面的卡片被吃掉了。
    LaunchedEffect(zone) { gridState.scrollToItem(0) }

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
        // 「保存 / 保存并重启」只在会真的写 emuera.config 的地方出现：原版引擎那一档，
        // 或已经有没写回去的改动（移植版档里改正文字体也会脏）。
        val saveActionsVisible = zone == SettingsZone.ENGINE || dirty
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                filePath = filePath,
                dirty = dirty,
                actionsInTopBar = layout.actionsInTopBar,
                saveActionsVisible = saveActionsVisible,
                text = text,
                onBack = onBack,
                onResetAll = onResetAll,
                onSave = onSave,
                onSaveAndRestart = onSaveAndRestart,
            )
            ZoneTabs(zone = zone, onZone = { zone = it }, text = text)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalZoneAccent provides zone.accent) {
                    LazyVerticalStaggeredGrid(
                        state = gridState,
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
                        // 读取/夹取提示跟着当前档走：它说的是 emuera.config 这一份文件，
                        // 但玩家从哪一档进来都该第一眼看见，所以放在 when 之前、列表最顶。
                        if (loadNotice != null || clampNotices.isNotEmpty()) {
                            item(key = "notices", span = StaggeredGridItemSpan.FullLine) {
                                NoticeCard(loadNotice = loadNotice, clampNotices = clampNotices, text = text)
                            }
                        }
                        when (zone) {
                            SettingsZone.PORT -> {
                                item(key = "ui-prefs-header", span = StaggeredGridItemSpan.FullLine) {
                                    GroupHeader(title = text.appPrefsTitle, subtitle = text.appPrefsSubtitle)
                                }
                                item(key = "language", span = StaggeredGridItemSpan.FullLine) {
                                    LanguageCard(
                                        text = text,
                                        language = language,
                                        onLanguageChange = onLanguageChange,
                                    )
                                }
                                item(key = "ui-font") {
                                    FontCard(
                                        text = text,
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
                                        text = text,
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
                                        portraitMode = portraitMode,
                                        onPortraitMode = { enabled ->
                                            portraitMode = enabled
                                            UiPrefs.setPortraitMode(context, enabled)
                                        },
                                        liveReflow = liveReflow,
                                        onLiveReflow = { enabled ->
                                            liveReflow = enabled
                                            UiPrefs.setLiveReflow(context, enabled)
                                        },
                                        echoInput = echoInput,
                                        onEchoInput = { enabled ->
                                            echoInput = enabled
                                            UiPrefs.setEchoInput(context, enabled)
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
                                        text = text,
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
                                        autoUpdate = autoUpdate,
                                        onAutoUpdate = { enabled ->
                                            autoUpdate = enabled
                                            UiPrefs.setAutoUpdateEnabled(context, enabled)
                                        },
                                        wifiOnly = wifiOnly,
                                        onWifiOnly = { enabled ->
                                            wifiOnly = enabled
                                            UiPrefs.setWifiOnlyDownload(context, enabled)
                                        },
                                        playerStats = playerStats,
                                        onPlayerStats = { enabled ->
                                            playerStats = enabled
                                            UiPrefs.setPlayerStatsEnabled(context, enabled)
                                        },
                                        onCheckUpdate = onCheckUpdate,
                                        updateChecking = updateChecking,
                                        updateReadout = updateReadout,
                                        downloadProgress = downloadProgress,
                                        updateCopy = updateCopy,
                                        failedDownloadKind = failedDownloadKind,
                                        onRetryFailedDownload = onRetryFailedDownload,
                                        onCancelDownload = onCancelDownload,
                                        skippedUpdateVersion = skippedUpdateVersion,
                                        onRestoreUpdateReminder = onRestoreUpdateReminder,
                                    )
                                }
                                item(key = "ui-advanced") {
                                    AdvancedCard(
                                        text = text,
                                        englishLabels = englishLabels,
                                        onEnglishLabelsChange = onEnglishLabelsChange,
                                    )
                                }
                                item(key = "ui-diag", span = StaggeredGridItemSpan.FullLine) {
                                    DiagnosticsCard(
                                        text = text,
                                        busy = diagBusy,
                                        lines = diagLines,
                                        onShare = onShareDiagnostics,
                                        onCopy = onCopyDiagnostics,
                                    )
                                }
                                item(key = "save-group-header", span = StaggeredGridItemSpan.FullLine) {
                                    GroupHeader(title = text.saveGroupTitle, subtitle = text.saveGroupSubtitle)
                                }
                                item(key = "save-export", span = StaggeredGridItemSpan.FullLine) {
                                    SaveExportCard(
                                        text = text,
                                        busy = importing || exporting,
                                        exporting = exporting,
                                        lines = exportLines,
                                        onExport = onExport,
                                    )
                                }
                                item(key = "save-import", span = StaggeredGridItemSpan.FullLine) {
                                    SaveImportCard(
                                        text = text,
                                        busy = importing || exporting,
                                        importing = importing,
                                        lines = importLines,
                                        onPickSaveFiles = onPickSaveFiles,
                                    )
                                }
                            }

                            SettingsZone.ENGINE -> {
                                groups.forEach { group ->
                                    when (group.group) {
                                        ConfigGroup.GAME -> {
                                            item(key = "group-game", span = StaggeredGridItemSpan.FullLine) {
                                                GroupHeader(
                                                    title = text.gameGroupTitle,
                                                    subtitle = text.gameGroupSubtitle(group.fileName, group.entries.size),
                                                )
                                            }
                                            gameSections(group.entries).forEach { section ->
                                                item(key = "page-${section.page.name}") {
                                                    EntryCard(
                                                        text = text,
                                                        title = text.pageTitle(section.page),
                                                        hint = text.pageHint(section.page),
                                                        entries = section.entries,
                                                        englishLabels = englishLabels,
                                                        onApplyBool = onApplyBool,
                                                        onEdit = { entry ->
                                                            editing = editRequestFor(entry, englishLabels, text)
                                                        },
                                                    )
                                                }
                                            }
                                        }

                                        ConfigGroup.DEBUG, ConfigGroup.REPLACE -> {
                                            item(key = "group-${group.group}", span = StaggeredGridItemSpan.FullLine) {
                                                ReadOnlyCard(
                                                    group = group,
                                                    englishLabels = englishLabels,
                                                    text = text,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            SettingsZone.MOD -> {
                                item(key = "mod-header", span = StaggeredGridItemSpan.FullLine) {
                                    GroupHeader(title = text.modGroupTitle, subtitle = text.modGroupSubtitle)
                                }
                                val materialBusy = backgroundPackState is ResourcePackUiState.Importing ||
                                    bgmPackState is ResourcePackUiState.Importing ||
                                    archivePackState is ResourcePackUiState.Importing
                                item(key = "resource-pack-background", span = StaggeredGridItemSpan.FullLine) {
                                    ResourcePackBackgroundCard(
                                        state = backgroundPackState,
                                        busy = materialBusy,
                                        text = text,
                                        copy = resourcePackCopy,
                                        onPickFiles = onPickBackgroundFiles,
                                        onPickFolder = onPickBackgroundFolder,
                                        onRestart = onRestartForResources,
                                    )
                                }
                                item(key = "resource-pack-bgm", span = StaggeredGridItemSpan.FullLine) {
                                    ResourcePackBgmCard(
                                        state = bgmPackState,
                                        busy = materialBusy,
                                        location = bgmLocation,
                                        onLocationChange = onBgmLocationChange,
                                        text = text,
                                        copy = resourcePackCopy,
                                        onPickFiles = onPickBgmFiles,
                                        onPickFolder = onPickBgmFolder,
                                        onRestart = onRestartForResources,
                                    )
                                }
                                item(key = "resource-pack-archive", span = StaggeredGridItemSpan.FullLine) {
                                    ResourcePackArchiveCard(
                                        state = archivePackState,
                                        busy = materialBusy,
                                        text = text,
                                        copy = resourcePackCopy,
                                        onPick = onPickResourcePacks,
                                        onRestart = onRestartForResources,
                                    )
                                }
                                item(key = "mod-import", span = StaggeredGridItemSpan.FullLine) {
                                    ModImportCard(
                                        state = modImportState,
                                        onOpenModImport = onOpenModImport,
                                        text = text,
                                        copy = modImportCopy,
                                    )
                                }
                            }

                            SettingsZone.ABOUT -> {
                                item(key = "about-header", span = StaggeredGridItemSpan.FullLine) {
                                    GroupHeader(title = text.zoneAboutTab, subtitle = text.aboutGroupSubtitle)
                                }
                                item(key = "about", span = StaggeredGridItemSpan.FullLine) {
                                    AboutCard(text = text)
                                }
                                item(key = "credits", span = StaggeredGridItemSpan.FullLine) {
                                    CreditsCard(text = text)
                                }
                            }
                        }
                        // 列表最底部：恢复默认设置。刻意不进顶栏 —— 顶栏「重置 / 全部重置」覆盖设置页
                        // 所有功能（含清应用偏好）且不写盘；本卡片只恢复原版 emuera.config 项并立即写盘。
                        item(key = "restore-defaults", span = StaggeredGridItemSpan.FullLine) {
                            RestoreDefaultsCard(text = text, onRestore = { confirmRestore = true })
                        }
                    }
                }
            }
            // 保存条只在「这一档真的会写 emuera.config」或「确实有没写回去的改动」时出现：
            // 移植版那一档随改随存，摆两个保存按钮只会让人以为不点就丢。
            if (!layout.actionsInTopBar && saveActionsVisible) {
                BottomBar(text = text, onSave = onSave, onSaveAndRestart = onSaveAndRestart)
            }
        }
    }

    editing?.let { request ->
        EditDialog(
            request = request,
            text = text,
            displayOf = displayOf,
            onApplyText = onApplyText,
            onDismiss = { editing = null },
        )
    }

    // 恢复默认会立刻覆盖写好的 emuera.config（应用偏好与存档不受影响），二次确认不给「顺手点掉」的机会。
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = EratwInk.raised(),
            tonalElevation = 0.dp,
            title = { Text(text.restoreDefaultsConfirmTitle) },
            text = {
                Text(text.restoreDefaultsConfirmBody, color = UiColors.Text, fontSize = 13.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    onRestoreDefaults()
                }) {
                    Text(text.restoreDefaultsConfirmOk, color = UiColors.Warn, fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) {
                    Text(text.cancelText, color = UiColors.TextMuted, fontSize = 13.sp)
                }
            },
        )
    }

    // 目标格里已经有玩家的东西 ⇒ 只有玩家自己能决定要不要盖掉。
    if (overwritePending.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onOverwriteCancel,
            shape = RoundedCornerShape(14.dp),
            containerColor = EratwInk.raised(),
            tonalElevation = 0.dp,
            title = { Text(text.overwriteTitle) },
            text = {
                Column {
                    overwritePending.forEach { item ->
                        // 那一格里躺着读不出来的东西时也要说清「有东西」，不许显示成空的一格
                        val existing = item.place.occupiedMes?.takeIf { it.isNotBlank() }?.let(::oneLine)
                            ?: text.overwriteUnknownNote
                        val incoming = oneLine(item.place.mes).ifEmpty { text.overwriteNoNote }
                        Text(
                            text = text.overwriteLine(
                                text.slotLabel(item.place.slotLabel),
                                existing,
                                incoming,
                                item.fileName,
                            ),
                            color = UiColors.Text,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onOverwriteConfirm) {
                    Text(
                        text = text.overwriteConfirm(overwritePending.size),
                        color = UiColors.Accent,
                        fontSize = 13.sp,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onOverwriteCancel) {
                    Text(text.cancelText, color = UiColors.TextMuted, fontSize = 13.sp)
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
 *
 * [saveActionsVisible] 是另一件事：只有原版引擎那一档（或有没写回去的改动）才摆保存按钮 ——
 * 移植版功能随改随存，多两个按钮只会让人以为不点就丢。
 */
@Composable
private fun TopBar(
    filePath: String,
    dirty: Boolean,
    actionsInTopBar: Boolean,
    saveActionsVisible: Boolean,
    text: SettingsStrings,
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
                modifier = Modifier.semantics { contentDescription = text.back },
            ) {
                Text(
                    text = if (actionsInTopBar) "←" else text.back,
                    color = UiColors.Text,
                    fontSize = if (actionsInTopBar) 18.sp else 14.sp,
                )
            }
            Text(
                text = text.title,
                color = UiColors.Text,
                fontSize = if (actionsInTopBar) 18.sp else 20.sp,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onResetAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    text = if (actionsInTopBar) text.reset else text.resetAll,
                    color = UiColors.Accent,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
            if (actionsInTopBar && saveActionsVisible) {
                OutlinedButton(
                    onClick = onSaveAndRestart,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(text.saveAndRestart, fontSize = 12.sp, maxLines = 1)
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
                    Text(text.save, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        Text(
            text = text.filePrefix + filePath + if (dirty) text.unsavedSuffix else "",
            color = if (dirty) UiColors.Warn else UiColors.TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        HorizontalDivider(color = UiColors.Border)
    }
}

/**
 * 分区标签页：移植版功能 / 原版引擎 / Mod / 关于。
 *
 * 钉在顶栏之下、列表之上，滚动时不跟着走 —— 玩家在 100 多条引擎项里翻到一半，抬头还能
 * 换档，不必先滚回页顶。每一档用自己的主题色（选中时底色与字色都跟着走），
 * 卡片色带和卡片标题取同一个色，所以「现在在哪一档」在页面上任何一处都读得出来。
 */
@Composable
private fun ZoneTabs(zone: SettingsZone, onZone: (SettingsZone) -> Unit, text: SettingsStrings) {
    Column(modifier = Modifier.fillMaxWidth().background(UiColors.CardAlt)) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsZone.entries.forEach { option ->
                val selected = option == zone
                Text(
                    text = text.zoneTabLabel(option),
                    color = if (selected) option.accent else UiColors.TextDim,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) option.accent.copy(alpha = 0.16f) else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onZone(option) })
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        HorizontalDivider(color = UiColors.Border)
    }
}

/** 读取提示 / 夹取提示卡片（原版用对话框弹的那几条，这里平铺在顶部，免得一进页就挡操作）。 */
@Composable
private fun NoticeCard(loadNotice: String?, clampNotices: List<String>, text: SettingsStrings) {
    SettingsCard {
        SectionTitle(text.noticesTitle)
        loadNotice?.let {
            Text(it, color = UiColors.Warn, fontSize = 12.sp)
        }
        clampNotices.forEach {
            Text(it, color = UiColors.Warn, fontSize = 12.sp)
        }
    }
}

/** 显示语言卡片：三档各用母语写自己的名字，切换即重绘本页并落盘。 */
@Composable
private fun LanguageCard(
    text: SettingsStrings,
    language: SettingsLanguage,
    onLanguageChange: (SettingsLanguage) -> Unit,
) {
    SettingsCard {
        SectionTitle(text.languageTitle)
        Text(text.languageHint, color = UiColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        SettingsLanguage.entries.forEach { option ->
            RadioChoiceRow(
                selected = language == option,
                label = option.nativeName,
                onClick = { onLanguageChange(option) },
            )
        }
    }
}

/**
 * 导出存档卡片：一个按钮，把全部存档槽连同全局档打成一个 zip。
 *
 * 落点不写死在设置页里 —— 交给系统「新建文件」对话框，玩家选哪就写哪，壳层不申请
 * 任何存储权限（与导入同一套 SAF 口径）。结果行照实说：写了几个、含不含全局档、
 * 哪些文件被跳过，失败了也照原样贴出来。
 */
@Composable
private fun SaveExportCard(
    text: SettingsStrings,
    busy: Boolean,
    exporting: Boolean,
    lines: List<ExportLine>,
    onExport: () -> Unit,
) {
    SettingsCard {
        SectionTitle(text.exportTitle)
        Text(
            text = text.exportHint,
            color = UiColors.TextMuted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onExport,
            enabled = !busy,
            modifier = Modifier.height(40.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = UiColors.PrimaryButton,
                contentColor = UiColors.Text,
            ),
        ) {
            Text(if (exporting) text.exportBusy else text.exportButton, fontSize = 14.sp)
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

/**
 * 补丁（MOD）导入入口卡（R7-D1 任务①）：入口从 MainActivity 左下角悬浮钮迁到这里
 * （玩家发现性——MOD 导入是设置动作，不是游戏内动作），2026-11 起与素材导入卡一起
 * 归入设置的 Mod 档。卡片只承担入口按钮与最近一次
 * 导入结果的短摘要（[modImportCardSummary]）；导入页 / 报错页 / 报告页三页的完整形态
 * 在 [ModImportChrome] 覆盖层里原样保留，点「打开补丁导入」弹出。
 *
 * 文案口径（A-8/T-2c 收口）：说明句接 [SettingsStrings.modImportIntro] 三语（zh/ja/en），
 * 与覆盖层 [ModImportChrome] 共用同一条键；卡内标题 / 按钮字 / 摘要行接 [ModImportCopy]
 * 三语（[copy]），本卡不再写字面量。
 */
@Composable
private fun ModImportCard(
    state: ModImportUiState,
    onOpenModImport: () -> Unit,
    text: SettingsStrings,
    copy: ModImportCopy,
) {
    SettingsCard {
        SectionTitle(copy.cardTitle)
        Text(
            text = text.modImportIntro,
            color = UiColors.TextMuted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onOpenModImport,
            // 进行中不放行第二次（与覆盖层内「选择口上文件」按钮同一判据）。
            enabled = state !is ModImportUiState.Importing,
            modifier = Modifier.height(40.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = UiColors.PrimaryButton,
                contentColor = UiColors.Text,
            ),
        ) {
            Text(copy.cardOpenButton, fontSize = 14.sp)
        }
        val summary = modImportCardSummary(state, copy)
        if (summary != null) {
            Text(
                text = summary,
                color = if (state is ModImportUiState.Failed) UiColors.Warn else UiColors.TextDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 素材直投卡（Mod 档）：背景图 / BGM 各一张，玩家直接选文件（单选 / 多选）或整夹，
 * 不必把素材先打成 zip / 7z。
 *
 * 现状读数来自 [ResourcePackStore.scanStatus]：每张卡只显示自己那一路；
 * 「有图无索引」单列成一行——那是背景图补丁最常见的失败形态（只丢图片，
 * `SPRITECREATED` 仍为假），读数必须替玩家点出来。
 *
 * 结果区句子全部来自 [ResourcePackCopy]（卡片只按状态摆位）：成功 / 部分成功才挂
 * 「重启游戏生效」，而这一档要求 [ResourcePackImportSummary.files] > 0 —— 一个文件都
 * 没落盘的导入在状态机里只会是 Failed，不会误报成功。
 */
@Composable
private fun ResourcePackBackgroundCard(
    state: ResourcePackUiState,
    busy: Boolean,
    text: SettingsStrings,
    copy: ResourcePackCopy,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onRestart: () -> Unit,
) {
    MaterialImportCard(
        title = copy.backgroundTitle,
        intro = copy.backgroundIntro,
    ) {
        val status = state.status
        if (status == null) {
            Text(text = text.resourcePackStatusUnavailable, color = UiColors.Warn, fontSize = 12.sp)
        } else {
            Text(
                text = text.resourcePackBackground(status.backgroundImages, status.backgroundIndexRows),
                color = if (status.backgroundReady) UiColors.TextDim else UiColors.TextMuted,
                fontSize = 12.sp,
            )
            // 导入成功 ≠ 游戏内已启用：4.981 的背景图 / 音乐都是 DLC 开关，默认关。
            // 刚导完的结果区会把启用步骤再顶一次，这里就不再重复。
            if (status.backgroundReady && !state.showsEnableStepsInOutcome) {
                Text(
                    text = copy.backgroundEnabledHint,
                    color = UiColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        MaterialPickRow(
            filesLabel = if (state is ResourcePackUiState.Importing) copy.importingStageText else copy.backgroundPickFiles,
            folderLabel = copy.backgroundPickFolder,
            enabled = !busy,
            onPickFiles = onPickFiles,
            onPickFolder = onPickFolder,
        )
        ResourcePackOutcome(state, copy, listOf(copy.backgroundEnabledHint), onRestart)
    }
}

/** BGM 直投卡：多了「地点名」输入，只用于直接选音频时给根级文件定地点。 */
@Composable
private fun ResourcePackBgmCard(
    state: ResourcePackUiState,
    busy: Boolean,
    location: String,
    onLocationChange: (String) -> Unit,
    text: SettingsStrings,
    copy: ResourcePackCopy,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onRestart: () -> Unit,
) {
    MaterialImportCard(
        title = copy.bgmTitle,
        intro = copy.bgmIntro,
    ) {
        val status = state.status
        if (status == null) {
            Text(text = text.resourcePackStatusUnavailable, color = UiColors.Warn, fontSize = 12.sp)
        } else {
            Text(
                text = text.resourcePackBgm(status.bgmTracks, status.bgmFolders),
                color = if (status.bgmReady) UiColors.TextDim else UiColors.TextMuted,
                fontSize = 12.sp,
            )
            // 官方 BGM 包只有 上午/下午/晚上 子目录，不切差分模式同样没声音。
            // 刚导完的结果区会把启用步骤再顶一次，这里就不再重复。
            if (status.bgmReady && !state.showsEnableStepsInOutcome) {
                Text(
                    text = copy.bgmEnabledHint,
                    color = UiColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = location,
            onValueChange = onLocationChange,
            singleLine = true,
            label = { Text(copy.bgmLocationLabel, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Text(
            text = copy.bgmLocationHint,
            color = UiColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(8.dp))
        MaterialPickRow(
            filesLabel = if (state is ResourcePackUiState.Importing) copy.importingStageText else copy.bgmPickFiles,
            folderLabel = copy.bgmPickFolder,
            enabled = !busy,
            onPickFiles = onPickFiles,
            onPickFolder = onPickFolder,
        )
        ResourcePackOutcome(state, copy, listOf(copy.bgmEnabledHint), onRestart)
    }
}

/** 压缩包兼容卡：只在玩家手里已有 zip / 7z 时用；平时推荐上面两张直投卡。 */
@Composable
private fun ResourcePackArchiveCard(
    state: ResourcePackUiState,
    busy: Boolean,
    text: SettingsStrings,
    copy: ResourcePackCopy,
    onPick: () -> Unit,
    onRestart: () -> Unit,
) {
    MaterialImportCard(
        title = text.resourcePackTitle,
        intro = text.resourcePackIntro,
    ) {
        Spacer(Modifier.height(8.dp))
        MaterialPickButton(
            text = if (state is ResourcePackUiState.Importing) copy.importingStageText else text.resourcePackButton,
            enabled = !busy,
            onClick = onPick,
            modifier = Modifier.fillMaxWidth(),
        )
        ResourcePackOutcome(state, copy, archiveEnableHints(state.status, copy), onRestart)
    }
}

/**
 * 压缩包卡没有现状读数区：启用步骤按导入后刷新的 [ResourcePackStatus] 推。
 * 读数取不到时两条都给——宁可多提醒一句，也别让玩家以为导完就生效。
 */
private fun archiveEnableHints(status: ResourcePackStatus?, copy: ResourcePackCopy): List<String> =
    buildList {
        if (status == null || status.backgroundReady) add(copy.backgroundEnabledHint)
        if (status == null || status.bgmReady) add(copy.bgmEnabledHint)
    }

/** 结果区已经顶出启用步骤时，卡上方的常驻提示不再重复同一句。 */
private val ResourcePackUiState.showsEnableStepsInOutcome: Boolean
    get() = when (this) {
        is ResourcePackUiState.Succeeded -> summary.hasLandedFiles
        is ResourcePackUiState.Partial -> summary.hasLandedFiles
        else -> false
    }

/** 素材卡共用的骨架：标题 + 说明 + 读数 / 按钮 / 结果区（由调用方摆位）。 */
@Composable
private fun MaterialImportCard(
    title: String,
    intro: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsCard {
        SectionTitle(title)
        Text(text = intro, color = UiColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** 「选文件 / 选文件夹」两颗按钮并排：文字短，窄屏各占一半。 */
@Composable
private fun MaterialPickRow(
    filesLabel: String,
    folderLabel: String,
    enabled: Boolean,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MaterialPickButton(
            text = filesLabel,
            enabled = enabled,
            onClick = onPickFiles,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = onPickFolder,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(40.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(text = folderLabel, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun MaterialPickButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = UiColors.PrimaryButton,
            contentColor = UiColors.Text,
        ),
    ) {
        Text(text = text, fontSize = 14.sp, maxLines = 1)
    }
}

/**
 * 结果区：成功 / 部分成功 / 失败三态各自摆位（句子全部由 [ResourcePackCopy] 出词）。
 *
 * [enableHints] 是这一路导入在游戏内还要做的启用动作：导入成功只代表文件落盘，
 * 4.981 的开关都是每个存档各自的 SAVEDATA，App 不能替玩家打开。有文件落盘时
 * 结果区把这段步骤再顶一次，别让玩家停在「导入成功」却看不到画面 / 听不到声音。
 */
@Composable
private fun ResourcePackOutcome(
    state: ResourcePackUiState,
    copy: ResourcePackCopy,
    enableHints: List<String>,
    onRestart: () -> Unit,
) {
    when (state) {
        is ResourcePackUiState.Succeeded -> ResourcePackResultArea(state.summary, copy, enableHints, onRestart)
        is ResourcePackUiState.Partial -> ResourcePackResultArea(state.summary, copy, enableHints, onRestart)
        is ResourcePackUiState.Failed -> {
            Text(
                text = state.message,
                color = UiColors.Warn,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            state.skippedSamples.forEach { sample ->
                Text(
                    text = copy.fill(copy.skippedLineFormat, sample),
                    color = UiColors.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }

        is ResourcePackUiState.Importing, is ResourcePackUiState.Idle -> Unit
    }
}

/**
 * 成功 / 部分成功共用的结果区：汇总句 + 跳过明细 + 结构体检警示行 + 启用步骤 + 重启按钮。
 * 启用步骤与重启按钮只在真有文件落盘时出现（[ResourcePackImportSummary.hasLandedFiles]）。
 */
@Composable
private fun ResourcePackResultArea(
    summary: ResourcePackImportSummary,
    copy: ResourcePackCopy,
    enableHints: List<String>,
    onRestart: () -> Unit,
) {
    Text(
        text = summary.message,
        color = UiColors.TextDim,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
    summary.skippedSamples.forEach { sample ->
        Text(
            text = copy.fill(copy.skippedLineFormat, sample),
            color = UiColors.TextMuted,
            fontSize = 11.sp,
        )
    }
    summary.warningLines.forEach { line ->
        Text(text = line, color = UiColors.Warn, fontSize = 11.sp)
    }
    if (summary.hasLandedFiles) {
        // 落盘了但现状读数显示还没就绪（例如图片缺索引）时没有启用步骤可给，
        // 只剩结构体检行；别留一个空的小标题。
        if (enableHints.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(text = copy.enableStepsTitle, color = UiColors.TextDim, fontSize = 12.sp)
            enableHints.forEach { hint ->
                Text(
                    text = hint,
                    color = UiColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onRestart,
            modifier = Modifier.height(40.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = UiColors.PrimaryButton,
                contentColor = UiColors.Text,
            ),
        ) {
            Text(text = copy.restartButton, fontSize = 14.sp)
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
    text: SettingsStrings,
    busy: Boolean,
    importing: Boolean,
    lines: List<ImportLine>,
    onPickSaveFiles: () -> Unit,
) {
    SettingsCard {
        SectionTitle(text.importTitle)
        Button(
            onClick = onPickSaveFiles,
            enabled = !busy,
            modifier = Modifier.height(40.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = UiColors.PrimaryButton,
                contentColor = UiColors.Text,
            ),
        ) {
            Text(if (importing) text.importBusy else text.importButton, fontSize = 14.sp)
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

/**
 * 运行诊断卡片：把 `files/eratw-run` 的轨迹导出成可分享文本。
 *
 * 为什么放在设置页而不是只靠冷启补报：脏退出报告只在「上一轮没走到收尾点」时才有，
 * 而 lmkd / OEM 直接收进程常常连报告都留不下 —— 玩家手里唯一能交的就是这份轨迹。
 * 判读在 [RunDiagnostics]（纯 JVM、有定向单测），本卡只负责触发与转述结果。
 */@Composable
private fun DiagnosticsCard(
    text: SettingsStrings,
    busy: Boolean,
    lines: List<DiagLine>,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    SettingsCard {
        SectionTitle(text.diagTitle)
        Text(text = text.diagHint, color = UiColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onShare,
                enabled = !busy,
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = UiColors.PrimaryButton,
                    contentColor = UiColors.Text,
                ),
            ) {
                Text(if (busy) text.diagBusy else text.diagShareButton, fontSize = 14.sp)
            }
            OutlinedButton(
                onClick = onCopy,
                enabled = !busy,
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, UiColors.Border),
            ) {
                Text(text.diagCopyButton, fontSize = 14.sp)
            }
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

/**
 * 关于与反馈卡：版本号、开发者、项目仓库、Bug 反馈群。
 *
 * 版本三读数全部取自 `BuildConfig`（`versionName` / `versionCode` 在 app/build.gradle.kts，
 * `eratwBuildStamp` 由出包脚本以 `-PeratwBuildStamp` 烙进来）—— 页面上不抄写版本常量，
 * 抄一次就多一处会和包里实际版本不一致的地方。构建标识为 `unknown`（没走出包脚本的本地构建）
 * 时不显示那一行。
 */
@Composable
private fun AboutCard(text: SettingsStrings) {
    val context = LocalContext.current
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.dev_avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .border(1.dp, LocalZoneAccent.current.copy(alpha = 0.45f), CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text.aboutDeveloperCaption, color = UiColors.TextMuted, fontSize = 11.sp)
                Text(text.aboutDeveloperName, color = LocalZoneAccent.current, fontSize = 16.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = text.aboutVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    color = UiColors.Text,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = UiColors.Border)
        AboutContactRow(
            caption = text.aboutRepoCaption,
            value = text.aboutRepoUrl,
            button = text.aboutRepoButton,
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(text.aboutRepoUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val opened = runCatching { context.startActivity(intent) }.isSuccess
                if (!opened) {
                    Toast.makeText(context, text.aboutRepoFailed, Toast.LENGTH_SHORT).show()
                }
            },
        )
        HorizontalDivider(color = UiColors.Border)
        AboutContactRow(
            caption = text.aboutFeedbackCaption,
            value = text.aboutGroupId,
            button = text.aboutCopyGroupButton,
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("eratw-feedback-group", text.aboutGroupId))
                Toast.makeText(context, text.aboutCopied(text.aboutGroupId), Toast.LENGTH_SHORT).show()
            },
        )
        val buildStamp = BuildConfig.eratwBuildStamp
        if (buildStamp != "unknown") {
            Text(
                text = text.aboutBuild(buildStamp),
                color = UiColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 关于卡里的一行联系方式：小字类别名 + 原值 + 一个动作钮（开链接 / 复制）。 */
@Composable
private fun AboutContactRow(
    caption: String,
    value: String,
    button: String,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(caption, color = UiColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = UiColors.Text,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.height(36.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, UiColors.Border),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(button, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/**
 * 署名声明卡：本体归属 → 本项目范围 → 借物表 → 责任归属。
 *
 * 措辞全在 [SettingsStrings] 的三语表里，这里一个字都不写死：这一段是要拿出来逐条核对的声明，
 * 改口径必须和三语表同一次改完，否则三种语言各说一套。
 *
 * 版式：节名用当前分区的主题色（与卡片标题同族），节与节之间只一条发丝线，不再叠第二层底色；
 * 借物表左侧压一条同色的细竖线 —— 四行「人名 —— 身份」于是成一段清单，而不是四句散话。
 */
@Composable
private fun CreditsCard(text: SettingsStrings) {
    SettingsCard {
        SectionTitle(text.creditsTitle)
        CreditsSection(text.creditsHeadGame, text.creditsGameBody, first = true)
        CreditsSection(text.creditsHeadScope, text.creditsScopeBody)
        CreditsList(
            caption = text.creditsHeadThanks,
            items = listOf(
                text.creditsItemEmuera,
                text.creditsItemKoujo,
                text.creditsItemCommunity,
                text.creditsItemTouhou,
            ),
        )
        CreditsFooter(text.creditsLiability)
    }
}

/** 声明卡里的一节：分区色节名 + 正文。 */
@Composable
private fun CreditsSection(caption: String, body: String, first: Boolean = false) {
    if (!first) {
        CreditsRule()
    }
    Text(
        text = caption,
        color = LocalZoneAccent.current,
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
        text = body,
        color = UiColors.TextDim,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
}

/** 借物表：一条分区色竖线托住整组署名。 */
@Composable
private fun CreditsList(caption: String, items: List<String>) {
    CreditsRule()
    Text(
        text = caption,
        color = LocalZoneAccent.current,
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(LocalZoneAccent.current.copy(alpha = 0.35f)),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            items.forEachIndexed { index, credit ->
                Text(
                    text = credit,
                    color = UiColors.Text,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = if (index == items.lastIndex) 0.dp else 5.dp),
                )
            }
        }
    }
}

/** 责任归属那一行：整卡的收场句，压到最浅一档字色，读完即止。 */
@Composable
private fun CreditsFooter(line: String) {
    CreditsRule()
    Text(
        text = line,
        color = UiColors.TextMuted,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
}

@Composable
private fun CreditsRule() {
    HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 10.dp))
}

/** 打开页面时的读取诊断（结构化保存，切换语言时按当前语言重新上屏）。 */
private sealed interface LoadNotice {
    data class Error(val message: String) : LoadNotice
    data class Aborted(val line: Int, val dropped: Int) : LoadNotice
    data class Warnings(val count: Int, val first: String) : LoadNotice
    object Missing : LoadNotice
}

private fun LoadNotice.text(t: SettingsStrings): String = when (this) {
    is LoadNotice.Error -> t.loadError(message)
    is LoadNotice.Aborted -> t.loadAborted(line, dropped)
    is LoadNotice.Warnings -> t.loadWarnings(count, first)
    LoadNotice.Missing -> t.loadMissing
}

/** 一行导入结果（[ok] = 真的落进去了；其余按提示色显示）。 */
private class ImportLine(val text: String, val ok: Boolean)

/** 一行导出结果（[ok] = zip 真的写成了；其余按提示色显示）。 */
private class ExportLine(val text: String, val ok: Boolean)

/** 一行运行诊断结果（[ok] = 报告真的生成成了；其余按提示色显示）。 */
private class DiagLine(val text: String, val ok: Boolean)

/** 存档备注压成一行并截断：卡片与对话框都是一行一条的版面，而原版备注里可以带换行。 */
private fun oneLine(text: String): String {
    val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
    return if (flat.length <= 60) flat else flat.take(60) + "…"
}

/** 字号与正文字体：两件都影响长文本阅读，独立成卡，方便在宽屏里和操作卡并排。 */
@Composable
private fun FontCard(
    text: SettingsStrings,
    fontColumns: Int,
    onFontColumns: (Int) -> Unit,
    fontName: String,
    onFontName: (String) -> Unit,
) {
    SettingsCard {
        SectionTitle(text.fontTitle)
        PrefCaption(text.fontSizeLabel)
        UiPrefs.FONT_COLUMN_OPTIONS.forEach { option ->
            RadioChoiceRow(
                selected = fontColumns == option.columns,
                label = text.fontColumnLabel(option.columns),
                hint = text.fontColumnHint(option.columns),
                onClick = { onFontColumns(option.columns) },
            )
        }
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        // 正文字体（GFX-12 换字体波）：选择项来自 BundledFontFamilies.SELECTABLE ——
        // 表里每个族的覆盖/宽度口径都有实测读数；当前八族全部可选，默认族排第一。
        PrefCaption(text.fontFamilyLabel)
        val currentFamily = BundledFontFamilies.resolve(fontName)
        BundledFontFamilies.SELECTABLE.forEach { family ->
            RadioChoiceRow(
                selected = currentFamily.id == family.id,
                label = family.displayName,
                // 显示**字数覆盖**（按语料实际出现次数加权）而不是码点种类覆盖：
                // 后者会骗人（Ark 与 Fusion 种类只差 10 个，缺字数却差 5.8 倍）。
                hint = text.fontNote(family.id, family.note) + " · " + text.coverage(
                    String.format(java.util.Locale.ROOT, "%.2f", family.occurrenceCoverage * 100),
                ),
                hintMaxLines = 3,
                onClick = { onFontName(family.family) },
            )
        }
        Text(
            text = text.fontRestartHint,
            color = UiColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 操作与显示：选项按钮大小、底边抓手、适应屏幕、精简选项。 */
@Composable
private fun ControlCard(
    text: SettingsStrings,
    railButtonSize: UiPrefs.RailButtonSize,
    onRailButtonSize: (UiPrefs.RailButtonSize) -> Unit,
    screenFit: Boolean,
    onScreenFit: (Boolean) -> Unit,
    leanOptions: Boolean,
    onLeanOptions: (Boolean) -> Unit,
    portraitMode: Boolean,
    onPortraitMode: (Boolean) -> Unit,
    liveReflow: Boolean,
    onLiveReflow: (Boolean) -> Unit,
    echoInput: Boolean,
    onEchoInput: (Boolean) -> Unit,
    knobAt: Float,
    onKnobAt: (Float) -> Unit,
) {
    SettingsCard {
        SectionTitle(text.controlTitle)
        // 选项按钮大小：右栏的栏宽/按钮高/标题字号一起变（几何表在 ShellUi.railMetricsOf）。
        // 底下那条预览用同一条绘制通路画，所以「选完看到什么就是什么」。
        PrefCaption(text.railSizeLabel)
        UiPrefs.RailButtonSize.entries.forEach { size ->
            RadioChoiceRow(
                selected = railButtonSize == size,
                label = text.railLabel(size),
                hint = text.railHint(size),
                hintMaxLines = 3,
                onClick = { onRailButtonSize(size) },
            )
        }
        RailSizePreview(
            // 预览与右栏同一条绘制通路，连系统字体缩放这一刀也一起照上：
            // 「选完看到什么就是什么」，否则大字号机器上预览比实际小一圈。
            metrics = railMetricsForFontScale(railMetricsOf(railButtonSize), LocalDensity.current.fontScale),
            labels = listOf(
                text.railSampleTalk to 31,
                text.railSampleMove to 400,
                text.railSampleBuy to 10000,
            ),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = text.railFootnote,
            color = UiColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        // 底边抓手位置（输入栏的手势条）：三档，避免必须靠拖动来调位置
        PrefCaption(text.knobLabel)
        listOf(
            0.12f to text.knobLeft,
            0.5f to text.knobCenter,
            0.97f to text.knobRight,
        ).forEach { (fraction, label) ->
            RadioChoiceRow(
                selected = kotlin.math.abs(knobAt - fraction) < 0.05f,
                label = label,
                onClick = { onKnobAt(fraction) },
            )
        }
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        SwitchRow(
            title = text.screenFitTitle,
            hint = text.screenFitHint,
            checked = screenFit,
            onCheckedChange = onScreenFit,
        )
        SwitchRow(
            title = text.leanTitle,
            hint = text.leanHint,
            checked = leanOptions,
            onCheckedChange = onLeanOptions,
        )
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        // 竖屏是**显式开关**（默认关）：关 = 锁横屏，与原版版面一致；开 = 锁竖屏三区壳。
        // 没有「跟随机转」——手机竖着拿启动时那会让整个壳意外切成竖屏。
        SwitchRow(
            title = text.portraitTitle,
            hint = text.portraitHint,
            checked = portraitMode,
            onCheckedChange = onPortraitMode,
        )
        // 窗口宽窄变化时重新排版（LIVE-REFLOW，默认关）：开 = 转屏/分屏/键盘/折叠屏让窗口
        // 宽度一变，引擎就把显示过的文字按新宽度重新排一遍（横屏同样生效）。与字号/列数档
        // 同口径 —— 是会话构造期参数，改完要重开一局。
        SwitchRow(
            title = text.liveReflowTitle,
            hint = text.liveReflowHint,
            checked = liveReflow,
            onCheckedChange = onLiveReflow,
        )
        // 键入回显复刻（ECHO-INPUT，默认关）：关 = 输入交付不打那行编号，选择剧本页不再
        // 因为多出一行把整页往上顶；开 = 原版复刻，动态地图那笔帧闸修复仍然生效。
        SwitchRow(
            title = text.echoInputTitle,
            hint = text.echoInputHint,
            checked = echoInput,
            onCheckedChange = onEchoInput,
        )
    }
}

/**
 * 启动相关：加载页外观、标题彩蛋与这条更新通道的现状。
 *
 * 更新区（EP4 U2 + 组长接线 + U3a 三语化）的形状：`updateChecking` 只让一发检查在飞、按钮进行中态由
 * [UpdateUiText.checkButtonLabel] 在设置页自己的词条后面加省略号；`updateReadout` 是 [UpdateChannelReadout]
 * 判好的三行玩家面 + 可展开的技术细节，本层一个字都不判；`updateCopy` 是同一层的三语词源，
 * 由 Activity 一次取好传进来（语言与 `text.lang` 同源，本层不判语言）；`failedDownloadKind` 非空才给
 * 「接着下」那颗按钮（[UpdateDownloadService.lastFailedDownload] 有单才给得出，点了原单重放、断点保留 ——
 * 这里只传**哪一件**，那句话 by UpdateProgressText 按当前语言的三语表现出词）。
 */
@Composable
private fun LaunchCard(
    text: SettingsStrings,
    loadingStyle: UiPrefs.LoadingStyle,
    onLoadingStyle: (UiPrefs.LoadingStyle) -> Unit,
    disableBadApple: Boolean,
    onDisableBadApple: (Boolean) -> Unit,
    autoUpdate: Boolean,
    onAutoUpdate: (Boolean) -> Unit,
    wifiOnly: Boolean,
    onWifiOnly: (Boolean) -> Unit,
    playerStats: Boolean,
    onPlayerStats: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    updateChecking: Boolean,
    updateReadout: UpdateChannelReadout.Readout,
    downloadProgress: DownloadProgress,
    updateCopy: UpdateCopy,
    failedDownloadKind: UpdateDownloadKind?,
    onRetryFailedDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    skippedUpdateVersion: Long,
    onRestoreUpdateReminder: () -> Unit,
) {
    SettingsCard {
        SectionTitle(text.launchTitle)
        PrefCaption(text.loadingStyleLabel)
        UiPrefs.LoadingStyle.entries.forEach { style ->
            RadioChoiceRow(
                selected = loadingStyle == style,
                label = text.loadingLabel(style),
                hint = text.loadingHint(style),
                onClick = { onLoadingStyle(style) },
            )
        }
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        SwitchRow(
            title = text.badAppleTitle,
            // 引擎级屏蔽（EngineFunctionPolicy），改的是引擎行为、不动语料：
            // 别人的 clone 用的上游语料里 `CALL BADAPPLE` 可能是活的，那一行就是
            // 6573 帧 AWAIT 16（≈105 秒）的纯装饰动画。默认屏蔽。
            hint = text.badAppleHint,
            checked = disableBadApple,
            onCheckedChange = onDisableBadApple,
        )
        SwitchRow(
            title = text.autoUpdateTitle,
            // 国内镜像仓通道：冷启后自动查一次，发现新版**弹窗确认**才下载，不静默装、不静默换数据。
            hint = text.autoUpdateHint,
            checked = autoUpdate,
            onCheckedChange = onAutoUpdate,
        )
        // U5 P2-10：默认关 = 保持既有蜂窝二次确认；开 = 蜂窝点下载直接说明「已设为仅 Wi-Fi」。
        SwitchRow(
            title = text.wifiOnlyTitle,
            hint = text.wifiOnlyHint,
            checked = wifiOnly,
            onCheckedChange = onWifiOnly,
        )
        // 匿名玩家数：只发一枚本地随机编号，服务端只回聚合总数；关掉即零出网。
        // 文案走独立三语小表（SettingsStrings 已贴着 JVM 255 参数槽上限，见 PlayerStatsCopy 类头），
        // 语言与 text.lang 同源，不在这里另写一套只有中文的话。
        val statsCopy = PlayerStatsCopy.of(text.lang)
        SwitchRow(
            title = statsCopy.title,
            hint = statsCopy.hint,
            checked = playerStats,
            onCheckedChange = onPlayerStats,
        )
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        // 进行中态（EP4 U2-②）：只有真的拿到单飞闸才禁用，结果回来随改随消。话术用按钮自己的词条加省略号，
        // 不在这里新增一套只有中文的文案（三语表键位归 U3）。
        OutlinedButton(
            onClick = onCheckUpdate,
            enabled = !updateChecking,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(40.dp),
        ) {
            val label = UpdateUiText.checkButtonLabel(text.checkUpdateButton, updateChecking)
            Text(label, color = if (updateChecking) UiColors.TextMuted else UiColors.Text, fontSize = 14.sp)
        }
        // 下载进度条：紧贴「立即检查更新」下方。只在真有单在跑时占版面，下载一结束整块收掉，
        // 不给静止的空条。结构化读数来自 UpdateDownloadService.progress() 的进程内快照（快心跳），
        // 词一律走 UpdateCopy —— 这里一个玩家面句子都不自己拼。
        if (downloadProgress.running) {
            DownloadProgressBar(progress = downloadProgress, copy = updateCopy)
            // P2：通知看不见时（拒绝通知权限 / 关掉进度渠道）取消动作只挂在通知 action 上，
            // 玩家在应用内没有任何停法 —— 这里补一颗同屏可达的「停止下载」（走服务的 ACTION_CANCEL）。
            OutlinedButton(
                onClick = onCancelDownload,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, UiColors.Border),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(updateCopy.cancelNotifyAction, color = UiColors.Accent, fontSize = 13.sp)
            }
        }
        // 通道现状（EP4 U2-①）：分行讲人话 —— 这条通道能不能查、上次查到了什么、有没有东西等着应用。
        // 「包里没配地址」和「配了但连不上」在玩家眼里是同一个「它不更新」，处理人却完全不同，
        // 所以两种形状各有一句话；地址原文与本机版本号这类取证数字收在下面「看技术细节」里，
        // 不再直接甩在正文上（旧摘要那条裸 URL 与「整包 12，盘上数据 12」玩家读不出所以然）。
        var showTechDetail by remember { mutableStateOf(false) }
        updateReadout.playerLines.forEach { line ->
            Text(
                text = line,
                color = UiColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (updateReadout.hasTechDetail) {
            Text(
                text = UpdateUiText.techDetailLabel(updateCopy, showTechDetail),
                color = UiColors.Accent,
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showTechDetail = !showTechDetail }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
            if (showTechDetail) {
                updateReadout.techLines.forEach { line ->
                    Text(
                        text = line,
                        color = UiColors.TextMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        // 「接着下刚才没下完的那一份」（组长接线 EP4 U1-④）：没给通知权限的玩家失败后原本只能回游戏里
        // 把弹窗重头点一遍，这里留一条同屏可达的入口。入口的**出现条件**与**正文**都不在本层判：
        // 单子有没有留下由服务说，怎么说由 UpdateProgressText 说，避免同一句话在两个文件里各写一遍。
        // 传的是**哪一件**而不是成单那一刻的名字：`DownloadJob.label` 是下单那一刻的语言，
        // 玩家在设置页中途换语言就会把旧语言的词带进这一行（EP4 U3b 的渲染期现取同口径）。
        failedDownloadKind?.let { kind ->
            OutlinedButton(
                onClick = onRetryFailedDownload,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, UiColors.Border),
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = UpdateProgressText.settingsRetryLabel(updateCopy, kind),
                    color = UiColors.Accent,
                    fontSize = 13.sp,
                )
            }
        }
        // 「以后都不」的恢复口（契约 §1.5）：跳过的只是那一个 versionCode，这里给出口；
        // 「立即检查更新」不看这条（UpdateUi.checkNowInteractive 传 0 无视抑制）。
        if (skippedUpdateVersion > 0L) {
            Text(
                text = text.updateSkippedLine(skippedUpdateVersion),
                color = UiColors.Warn,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = onRestoreUpdateReminder,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, UiColors.Border),
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(text.updateRestoreButton, color = UiColors.Accent, fontSize = 13.sp)
            }
        }
    }
}

/**
 * 「立即检查更新」正下方的可视化下载进度条。
 *
 * 形状与启动画面的现代风加载页同源：圆角轨道 + 横向渐变填充；总量拿不到时不假装 0%，
 * 改画一条来回扫的窄光带（同一套「算不出就别编」的口径）。显眼靠强调色描边面板、10dp 色条
 * 与 20sp 大号百分比，而不是把字堆大：设置页整体是 11~14sp 密排，这一块独立成卡才看得见。
 *
 * 只有 [DownloadProgress.running] 为真时才被组合进来，非下载态整块收掉、不占版面；
 * 词一律从 [UpdateCopy] 取（[UpdateProgressText.labelFor] / [UpdateProgressText.progressText]），
 * 本层一个玩家面句子都不自己拼。
 */
@Composable
private fun DownloadProgressBar(progress: DownloadProgress, copy: UpdateCopy) {
    val kind = progress.kind ?: UpdateDownloadKind.Apk
    val label = UpdateProgressText.labelFor(copy, kind)
    val caption = UpdateProgressText.progressText(copy, kind, progress.doneBytes, progress.totalBytes)
    val fraction = UpdateChannelReadout.progressFraction(progress.doneBytes, progress.totalBytes)
    val percent = progress.percent.coerceIn(0L, 100L)
    val accent = UiColors.Accent
    Column(
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = UiColors.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (fraction != null) "$percent%" else "· · ·",
                color = accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (fraction != null) {
            DeterminateBar(fraction = fraction, accent = accent)
        } else {
            IndeterminateBar(accent = accent)
        }
        Spacer(Modifier.height(6.dp))
        Text(text = caption, color = UiColors.TextDim, fontSize = 11.sp)
    }
}

/** 已知总量：按比例填充；读数每 250ms 一跳，用短补间抹平台阶感，不让它一格一格蹦。 */
@Composable
private fun DeterminateBar(fraction: Float, accent: Color) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(220),
    )
    DownloadBarCanvas(accent = accent) { w, h ->
        val filled = w * animated
        if (filled > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.55f), accent)),
                size = Size(filled, h),
            )
        }
    }
}

/** 总量未知：不编百分比，画一条来回扫的窄光带（与启动画面的未知态同一套口径）。 */
@Composable
private fun IndeterminateBar(accent: Color) {
    val transition = rememberInfiniteTransition(label = "download-sweep")
    val sweep by transition.animateFloat(
        initialValue = -0.30f,
        targetValue = 1.30f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1500, easing = LinearEasing)),
        label = "sweep",
    )
    DownloadBarCanvas(accent = accent) { w, h ->
        val band = w * 0.24f
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(accent.copy(alpha = 0f), accent, accent.copy(alpha = 0f)),
            ),
            topLeft = Offset((w + band) * sweep - band, 0f),
            size = Size(band, h),
        )
    }
}

/** 两条进度条共用的 10dp 圆角轨：先铺底，再由调用方在 [DrawScope] 里画填充。 */
@Composable
private fun DownloadBarCanvas(
    accent: Color,
    fill: DrawScope.(Float, Float) -> Unit,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(percent = 50)),
    ) {
        val w = size.width
        val h = size.height
        drawRect(color = accent.copy(alpha = 0.16f), size = Size(w, h))
        fill(w, h)
    }
}

/** 高级：标签语言与存储口径说明。英文模式下条目名本就是配置键，开关不再重复出现。 */
@Composable
private fun AdvancedCard(
    text: SettingsStrings,
    englishLabels: Boolean,
    onEnglishLabelsChange: (Boolean) -> Unit,
) {
    SettingsCard {
        SectionTitle(text.advancedTitle)
        if (text.lang != SettingsLanguage.EN) {
            SwitchRow(
                title = text.rawKeysTitle,
                hint = text.rawKeysHint,
                checked = englishLabels,
                onCheckedChange = onEnglishLabelsChange,
            )
        }
        Text(
            text = text.prefsFootnote,
            color = UiColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 列表底部的「恢复默认设置」卡片：只负责开二次确认，真正的动作在 Activity 的
 * `restoreDefaults()`。用警示色而不是主按钮色 —— 它会立刻覆盖写好的 emuera.config，不可撤销。
 *
 * 摆在四个分区共同的列表末尾而不是顶栏：顶栏「重置 / 全部重置」覆盖设置页所有功能
 * （含清应用偏好）且不写盘、要玩家自己再点保存；本卡片只恢复原版 emuera.config 项并立即写盘，
 * 两个动作挨在一起最容易被误点。
 */
@Composable
private fun RestoreDefaultsCard(text: SettingsStrings, onRestore: () -> Unit) {
    SettingsCard {
        SectionTitle(text.restoreDefaultsTitle)
        Text(text = text.restoreDefaultsHint, color = UiColors.TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRestore,
            modifier = Modifier.height(40.dp),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, UiColors.Warn),
        ) {
            Text(text.restoreDefaultsButton, color = UiColors.Warn, fontSize = 14.sp)
        }
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
    hintMaxLines: Int = 2,
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
                maxLines = hintMaxLines,
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

/**
 * 分区带：色块 + 分区名 + 一句话说明，下面压一条同色的细线。
 *
 * 说明句只交代这一档的写入规则（随改随存 / 要按保存 / 只是信息），不写「怎么用」；
 * 颜色本身已经把「移植版加的」和「原版引擎搬过来的」分完了。
 */
@Composable
private fun GroupHeader(title: String, subtitle: String) {
    val accent = LocalZoneAccent.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(15.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(title, color = accent, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(
                text = subtitle,
                color = UiColors.TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(5.dp))
        HorizontalDivider(color = accent.copy(alpha = 0.30f))
    }
}

/** 可编辑组的一页（一个原版设置页 = 一张卡片）。 */
@Composable
private fun EntryCard(
    text: SettingsStrings,
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
                text = text,
                entry = entry,
                englishLabels = englishLabels,
                onApplyBool = onApplyBool,
                onEdit = onEdit,
            )
        }
    }
}

/** 一个可编辑条目：当前语言标题 + 布尔开关 / 可点开的值按钮 + 生效值提示。 */
@Composable
private fun EditableEntryRow(
    text: SettingsStrings,
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
                    text = text.configLabel(entry, englishLabels),
                    color = UiColors.Text,
                    fontSize = 14.sp,
                )
                if (entry.fixed) {
                    Text(text.pinnedByFixed, color = UiColors.Warn, fontSize = 11.sp)
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
                        text = display(current).ifEmpty { text.emptyValue },
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
                text = text.effectiveValue(display(entry.effectiveValue)),
                color = UiColors.Warn,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 只读组（`debug.config` / `_Replace.csv`）的卡片：不接任何写入监听。 */
@Composable
private fun ReadOnlyCard(group: SettingsGroupView, englishLabels: Boolean, text: SettingsStrings) {
    SettingsCard {
        SectionTitle(
            when (group.group) {
                ConfigGroup.DEBUG -> text.debugReadOnlyTitle
                ConfigGroup.REPLACE -> text.replaceReadOnlyTitle
                ConfigGroup.GAME -> text.gameReadOnlyTitle
            },
        )
        Text(
            text = when (group.group) {
                ConfigGroup.DEBUG -> text.debugReadOnlyHint
                ConfigGroup.REPLACE -> text.replaceReadOnlyHint
                ConfigGroup.GAME -> ""
            },
            color = UiColors.TextMuted,
            fontSize = 11.sp,
        )
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
                        text = text.configLabel(entry, englishLabels),
                        color = UiColors.Text,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = text.currentDefaultEffective(
                            display(entry.currentValue),
                            display(entry.defaultValue),
                            display(entry.effectiveValue),
                        ),
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
private fun BottomBar(text: SettingsStrings, onSave: () -> Unit, onSaveAndRestart: () -> Unit) {
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
                Text(text.saveAndRestart, fontSize = 13.sp)
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
                Text(text.save, fontSize = 15.sp)
            }
        }
        Text(
            text = text.bottomFootnote,
            color = UiColors.TextMuted,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp),
        )
    }
}

/**
 * 统一卡片外观：深灰 #262626、圆角 8dp、细描边、内边距 12/10（与主界面底栏同色）。
 *
 * 左边一条 3dp 的分区色带：卡片滚出屏幕再滚回来，也看得出它属于哪一档 ——
 * 分区色由 [LocalZoneAccent] 给，卡片自己不需要声明归属。
 *
 * 色带用 [BoxScope.matchParentSize] 的 Canvas **叠画**在卡片左缘，不用
 * `Row(height(IntrinsicSize.Min))` + 独立 3dp 列。后者在内容动态增高时（素材导入卡
 * 从「无结果」变成「成功摘要 + 重启按钮」）会让 `LazyStaggeredGrid` 复用旧的固有高度：
 * 新加的行被压成 0 高、整块裁在卡片外，读屏树里也看不到（2026-11-04 模拟器实测）。
 * Box 先量卡片、Canvas 再按父尺寸叠画，卡片高度完全由内容决定，没有固有测量。
 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val accent = LocalZoneAccent.current
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = UiColors.Card),
            border = BorderStroke(1.dp, UiColors.Border),
        ) {
            Column(
                // 起点 15dp：让出 3dp 色带 + 与旧版一致的 12dp 正文内距。
                modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                content = content,
            )
        }
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = 3.dp.toPx()
            val radius = 8.dp.toPx()
            val path = Path().apply {
                addRoundRect(
                    RoundRect(0f, 0f, width, size.height, radius, radius),
                )
            }
            drawPath(path, accent.copy(alpha = 0.75f))
        }
    }
}

/** 卡片标题（跟随当前分区的主题色）。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = LocalZoneAccent.current,
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
private fun editRequestFor(entry: SettingsEntry, englishLabels: Boolean, text: SettingsStrings): EditRequest {
    val current = display(entry.currentValue)
    val enumChoices = if (entry.kind == SettingsKind.ENUM) {
        val constants = (entry.constraint as? SettingsConstraint.Choices)?.constants.orEmpty()
        if (current in constants || constants.isEmpty()) constants else constants + current
    } else {
        emptyList()
    }
    return EditRequest(
        entryId = entry.id,
        title = text.configLabel(entry, englishLabels),
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
    text: SettingsStrings,
    displayOf: (String) -> String,
    onApplyText: (String, String) -> SettingsSetResult,
    onDismiss: () -> Unit,
) {
    var input by remember(request) { mutableStateOf(request.initial) }
    var selected by remember(request) { mutableStateOf(request.initial) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    val isEnum = request.choices.isNotEmpty()

    val confirm: () -> Unit = {
        val candidate = if (isEnum) selected else input
        when (val result = onApplyText(request.entryId, candidate)) {
            is SettingsSetResult.Rejected -> {
                error = text.rejectToast(text.rejectReason(result.reason), text.rejectDetail(result.message))
                val restored = displayOf(request.entryId)
                input = restored
                selected = restored
            }

            is SettingsSetResult.Applied -> onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 与 ui/EratwDialogCard 同一副卡片长相（14dp 圆角 + 浮层面板底、不吃 tonalElevation 的
        // 那层渐变），设置页的框与游戏里的弹窗才是一族东西。
        shape = RoundedCornerShape(14.dp),
        containerColor = EratwInk.raised(),
        tonalElevation = 0.dp,
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
                        value = input,
                        onValueChange = {
                            input = it
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
            TextButton(onClick = confirm) { Text(text.okText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text.cancelText) }
        },
    )
}

// =====================================================================
// 标签与分组
// =====================================================================

/** 一页设置项：页枚举（页名按当前语言取）+ 该页在原版对话框里的条目顺序。 */
private class ConfigPageSpec(val page: SettingsPage, val keys: List<ConfigKey>)

/**
 * `GAME` 组的页面划分 —— 页名与原版 `ConfigDialog` 的分页一一对应
 * （spec: `ConfigDialog.cs:489-627` 的 `TranslateUI` 分页赋值），页内顺序照抄控件顺序。
 *
 * 页名一律用中文（原版是日语页名，旧版页面把「文件名」当标题，这里改成真正的页名）。
 * 最后一页「其他（高级）」收的是原版设置页上**没有控件**的条目，保留可编辑性以便写回配置。
 */
private val GAME_PAGE_SPECS: List<ConfigPageSpec> = listOf(
    ConfigPageSpec(
        SettingsPage.ENV,
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
        SettingsPage.DISPLAY,
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
        SettingsPage.FONT,
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
        SettingsPage.SYSTEM,
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
        SettingsPage.SYSTEM2,
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
        SettingsPage.COMPAT,
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
        SettingsPage.PARSING,
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
        SettingsPage.CLIPBOARD,
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
        SettingsPage.RIKAI,
        listOf(
            ConfigKey.RikaiEnabled,
            ConfigKey.RikaiFilename,
            ConfigKey.RikaiColorBack,
            ConfigKey.RikaiColorText,
            ConfigKey.RikaiUseSeparateBoxes,
        ),
    ),
    ConfigPageSpec(
        SettingsPage.ADVANCED,
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

/** 把 `GAME` 组的条目按 [GAME_PAGE_SPECS] 分页；未登记的条目（将来新增的键）落进「其他」。 */
private fun gameSections(entries: List<SettingsEntry>): List<PageSection> {
    val byKey = entries.associateBy { it.key }
    val used = HashSet<ConfigKey>()
    val sections = ArrayList<PageSection>()
    for (page in GAME_PAGE_SPECS) {
        val items = page.keys.mapNotNull { byKey[it] }
        used += page.keys
        if (items.isNotEmpty()) {
            sections += PageSection(page.page, items)
        }
    }
    val rest = entries.filter { it.key !in used }
    if (rest.isNotEmpty()) {
        sections += PageSection(SettingsPage.OTHER, rest)
    }
    return sections
}

/** 一页渲染用的数据（页枚举 + 该页条目快照；页名与页尾说明按当前语言取）。 */
private class PageSection(val page: SettingsPage, val entries: List<SettingsEntry>)

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

/**
 * 口上导入的 SAF 多选契约（ACTION_OPEN_DOCUMENT，多选）：
 *
 * 与 [ActivityResultContracts.OpenMultipleDocuments] 的唯一差异是 createIntent 额外带
 * EXTRA_MIME_TYPES：点名列 zip 的三个常见 mime（部分 OEM 文件选择器只按 mime 列表过滤
 * 可见项，缺它玩家看不见 zip）、text 前缀万用项（文档提供方常把 .ERB/.ERH 标成
 * text/plain）、
 * 万用 mime 兜底一项（不缩窄既有可见面——`.ERB` 无注册 mime 时靠它保底）。
 * parseResult 语义照抄框架版：OK 时取 ClipData 逐项 uri（无 ClipData 回落单 data，
 * 兼容老设备的单选返回形态），非 OK 返回空表。
 */
private class OpenModDocuments : ActivityResultContract<Unit, List<Uri>>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                    "text/*",
                    "*/*",
                ),
            )
            .addCategory(Intent.CATEGORY_OPENABLE)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
        parseOpenDocumentUris(resultCode, intent)
}

/**
 * 素材直投的多选契约（图片 / 音频）：与 [OpenModDocuments] 同形，只换 mime 提示。
 *
 * 手机玩家选素材时看到的是相册 / 音乐库，image 通配、audio 通配是这两类文件管理器都认的
 * 档位；比万用 mime 少一层「什么都能选」的误导。真正能不能用仍由
 * [MaterialTarget] 的扩展名判定兜底（选错的文件会逐条跳过并点名）。
 */
private class OpenMaterialDocuments(
    private val mimeTypes: Array<String>,
) : ActivityResultContract<Unit, List<Uri>>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            .addCategory(Intent.CATEGORY_OPENABLE)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
        parseOpenDocumentUris(resultCode, intent)
}

/** 多选结果解析：有 ClipData 取逐项，无则回落单 data（老设备的单选返回形态）。 */
private fun parseOpenDocumentUris(resultCode: Int, intent: Intent?): List<Uri> {
    if (resultCode != Activity.RESULT_OK) return emptyList()
    val clip: ClipData? = intent?.clipData
    if (clip != null) {
        val uris = ArrayList<Uri>(clip.itemCount)
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i).uri?.let { uris.add(it) }
        }
        return uris
    }
    return intent?.data?.let { listOf(it) } ?: emptyList()
}
