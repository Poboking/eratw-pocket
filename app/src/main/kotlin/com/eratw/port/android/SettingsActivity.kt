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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eratw.port.android.ui.design.ShellGroupHeader
import com.eratw.port.android.ui.design.ShellAlertDialog
import com.eratw.port.android.ui.design.ShellDialogActions
import com.eratw.port.android.ui.design.ShellField
import com.eratw.port.android.ui.design.ShellMotion
import com.eratw.port.android.ui.design.ShellPress
import com.eratw.port.android.ui.design.ShellShape
import com.eratw.port.android.ui.design.ShellSpace
import com.eratw.port.android.ui.design.ShellType
import com.eratw.port.android.ui.design.ShellTag
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
import com.eratw.port.core.mod.ModSettingsSnapshot
import com.eratw.port.core.patch.BoolSettingDeclaration
import com.eratw.port.core.patch.EnumSettingDeclaration
import com.eratw.port.core.patch.IntSettingDeclaration
import com.eratw.port.core.patch.SettingDeclaration
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.vars.GameBaseData
import com.eratw.port.modfamily.FamilyDeclarations
import com.eratw.port.platform.config.SettingsStore
import com.eratw.port.platform.save.SaveExport
import com.eratw.port.platform.save.SaveImport
import com.eratw.port.platform.storage.AndroidAssetTree
import com.eratw.port.platform.storage.GameStorage
import com.eratw.port.platform.storage.MaterialSource
import com.eratw.port.platform.storage.MaterialTarget
import com.eratw.port.platform.storage.ModStore
import com.eratw.port.platform.storage.ResourcePackCleanup
import com.eratw.port.platform.storage.ResourcePackReport
import com.eratw.port.platform.storage.ResourcePackStatus
import com.eratw.port.platform.storage.ResourcePackStore
import com.eratw.port.platform.storage.StructureNoteKind
import com.eratw.port.android.market.BridgeSyncOutcome
import com.eratw.port.android.market.EndpointEditResult
import com.eratw.port.android.market.InstallOutcome
import com.eratw.port.android.market.InstalledPack
import com.eratw.port.android.market.MarketBridgeOutcome
import com.eratw.port.android.market.MarketBridgeUi
import com.eratw.port.android.market.MarketChrome
import com.eratw.port.android.market.MarketChromeActions
import com.eratw.port.android.market.MarketContract
import com.eratw.port.android.market.MarketCopy
import com.eratw.port.android.market.MarketEndpointUi
import com.eratw.port.android.market.MarketGameBridge
import com.eratw.port.android.market.MarketImportsLedger
import com.eratw.port.android.market.MarketIndexState
import com.eratw.port.android.market.MarketOptimistic
import com.eratw.port.android.market.MarketPackageUninstall
import com.eratw.port.android.market.MarketPhrases
import com.eratw.port.android.market.MarketRefreshOutcome
import com.eratw.port.android.market.MarketService
import com.eratw.port.android.market.MarketServiceImpl
import com.eratw.port.android.market.MarketFavoritesStore
import com.eratw.port.android.market.MarketReadouts
import com.eratw.port.android.market.MarketStatsHttpFetcher
import com.eratw.port.android.market.MarketStatsLoader
import com.eratw.port.android.market.MarketStatsState
import com.eratw.port.android.market.MarketStatsStore
import com.eratw.port.android.market.MarketUpdateChain
import com.eratw.port.android.market.MarketVerifierImpl
import com.eratw.port.android.market.MarketViewModel
import com.eratw.port.android.market.MarketViewModel.MarketUiEvent
import com.eratw.port.android.market.UninstallOutcome
import com.eratw.port.android.ui.EratwFilledButton
import com.eratw.port.android.ui.EratwGhostButton
import com.eratw.port.android.ui.EratwGhostTone
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.render.font.BundledFontFamilies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/** 设置页轮询「本次运行装载失败」快照的间隔（内存态，开销可忽略）。 */
private const val MOD_FAILURE_POLL_MILLIS: Long = 2_000L

/** 页面级 composable 的 logcat 标签（Activity 内的同名常量在 companion 里，顶层函数取不到）。 */
private const val SETTINGS_LOG_TAG: String = "EratwSettings"

/**
 * 诊断快照弹窗正文的滚动上限：快照是几十行的取证文本，给一屏多一点的高度就够读，
 * 再多就把弹窗顶到遮住背后页面（弹窗高度不应由内容长度决定）。
 */
private val REPORT_VIEW_MAX_HEIGHT = 360.dp

/**
 * 顶栏标题的两档：常规 20sp、顶部已塞动作按钮时退到 18sp。
 *
 * 两档都从 [ShellType.Title] 派生（同字重、同字距），只改字号与行高 —— 直接写 `fontSize = 20.sp`
 * 会连行高一起丢掉，横竖屏切换时标题基线会跳。
 */
private val TOP_BAR_TITLE = ShellType.Title.copy(fontSize = 20.sp, lineHeight = 26.sp)
private val TOP_BAR_TITLE_COMPACT = ShellType.Title.copy(fontSize = 18.sp, lineHeight = 24.sp)

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
        // 市场服务装配（组长接线①，CONTRACT-MKM-W1）：filesDir 在 onCreate 后才就绪，
        // 不能挂字段初始化期；构造零 IO（真实 IO 全部延迟到首次取数/装卸调用）。
        marketService = MarketServiceImpl(
            marketRoot = File(filesDir, MarketContract.DIR_NAME),
            verifier = MarketVerifierImpl(),
        )
        // 桥装配（W3-UI2 裁定 2）：controllerFactory 照 modImportController() 的「无状态随用
        // 随建」形态（ModStore + ProductionBaseCompileContextSource + gameRoot 现用现建），
        // 形参收下不另拼第二份控制器口径；verifier 与市场服务同一条校验缝。gameRootFile 在
        // 上一行已就绪且整个生命周期单次赋值，工厂闭包吃成员与吃形参是同一个根。
        marketGameBridge = MarketGameBridge(
            marketRoot = File(filesDir, MarketContract.DIR_NAME),
            gameRoot = gameRootFile,
            controllerFactory = { _ -> modImportController() },
            verifier = MarketVerifierImpl(),
        )
        // 一键更新链的台账查询缝（CONTRACT-MKM-W4-UI3 裁定 1）：只读消费 [MarketImportsLedger]
        // 的 lookup API（返回 null = 该 (kind,id) 从未装进游戏）。桥内部持有自己的写实例，
        // 这里独立只读实例指向同一文件；市场装卸由 installingKey 单飞闸串行，无并发写竞争。
        // 构造零 IO（真实读延迟到链的 lookup 调用）。
        marketImportsLedger =
            MarketImportsLedger(File(File(filesDir, MarketContract.DIR_NAME), MarketImportsLedger.FILE_NAME))
        // 下载量统计与本机收藏（CONTRACT-MKM-STATS-UI §1）：构造零 IO，真实读写延迟到调用；
        // 文件面全部住 <filesDir>/market/ 同一棵子树（与索引镜像同根，卸载清扫口径不变）。
        marketStatsLoader = MarketStatsLoader(
            store = MarketStatsStore(File(File(filesDir, MarketContract.DIR_NAME), MarketContract.STATS_FILE)),
            fetcher = MarketStatsHttpFetcher(),
        )
        marketFavoritesStore =
            MarketFavoritesStore(File(File(filesDir, MarketContract.DIR_NAME), MarketContract.FAVORITES_FILE))
        // 入口卡概览（CONTRACT-MKM-STATS-UI §2）：零网络盘读一次，读完落屏；失败留 null =
        // 卡面统计格演「—」。installed() 带在位自愈（与开页 sweep 后台段同一服务实例、
        // 同一把 mutationLock 串行，无并发写竞争）；占用读数从同一条核验结果折出，不二扫。
        Thread({
            val service = marketService ?: return@Thread
            val index = service.indexState()
            val packs = (index as? MarketIndexState.Ok)?.index
            val installed = runCatching { service.installed() }.getOrElse {
                Log.w(TAG, "市场：入口卡已装读数读取失败", it)
                emptyList()
            }
            val overview = MarketEntryOverview(
                packCount = packs?.packs?.size,
                installedCount = installed.size,
                installedBytes = installed.sumOf { it.bytes },
                favoriteCount = marketFavoritesStore.read().size,
                syncedAtMs = packs?.fetchedAtMs,
            )
            runOnUiThread { if (!isFinishing) marketEntryOverview = overview }
        }, "market-overview").start()
        // 库内清扫（CONTRACT-MKM-W5-GC 挂账接线）：开设置页后台扫一次被超越版本（幂等 + 锁内，
        // 与装卸互斥；W4-UI3 已装列表按包去重后，旧版本目录只剩这里能清）。零 UI、失败只记日志。
        // sweepSuperseded 是实现类方法（骨架 MarketService 接口零改动口径），须按 MarketServiceImpl 调。
        Thread({
            runCatching { (marketService as? MarketServiceImpl)?.sweepSuperseded() }
                .onFailure { Log.w(TAG, "市场：库内被超越版本清扫未预期失败", it) }
        }).start()
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
                // 素材清理的确认框与素材卡共用同一份三语表：语言只从这条 lang 取一次，
                // 确认框与卡片都按这个 copy 出词（不在对话框里再判一次语言）。
                val materialCopy = ResourcePackCopy.of(lang)
                // 市场域三语表（MKM-W1）：与素材/补丁卡同口径，语言只从这条 lang 取一次，
                // 入口卡与覆盖层只做「按这个 copy 出词」（后台线程的任务在起线程前已把 copy 捕好）。
                val marketCopy = MarketCopy.of(lang)
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
                    // 市场入口卡（MKM-W1）：与补丁导入卡同一口径——语言在 setContent 单路取好传入，
                    // 卡片只做「按这个 copy 出词」；打开动作走 ::openMarket。
                    marketCopy = marketCopy,
                    marketEntryOverview = marketEntryOverview,
                    onOpenMarket = ::openMarket,
                    backgroundPackState = backgroundPackState,
                    bgmPackState = bgmPackState,
                    archivePackState = archivePackState,
                    bgmLocation = bgmLocationInput,
                    onBgmLocationChange = { bgmLocationInput = it },
                    // 素材导入域三语表（Lane B）：与补丁导入同口径，语言只从上面那条
                    // `language` 读数取一次，卡片只做「按这个 copy 出词」。
                    resourcePackCopy = materialCopy,
                    onPickBackgroundFiles = { pickBackgroundImages.launch(Unit) },
                    onPickBackgroundFolder = { pickBackgroundFolder.launch(null) },
                    onPickBgmFiles = { pickBgmTracks.launch(Unit) },
                    onPickBgmFolder = { pickBgmFolder.launch(null) },
                    onPickResourcePacks = { pickResourcePacks.launch(Unit) },
                    // 清理入口：卡片按钮只置确认目标，真正的删除在确认框点「清除」之后才跑
                    // （见 clearImportedMaterials；单飞闸与导入共用 resourcePackBusy）。
                    onClearBackground = { clearTarget = MaterialTarget.BACKGROUND },
                    onClearBgm = { clearTarget = MaterialTarget.BGM },
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
                        actions = ModImportChromeActions(
                            onDismiss = ::dismissModImport,
                            onPickFiles = { pickModFiles.launch(Unit) },
                            onConfirmImport = ::confirmModImport,
                            onCancelPreview = ::cancelModImportPreview,
                            onRestartGame = ::finishForPatchRestart,
                            // KI-4：报错页错误报告 / 成功页告警清单的一键复制口，写剪贴板后 Toast 确认。
                            onCopyText = { payload ->
                                copyTextToClipboard(this, payload, ModImportCopy.of(lang).copyDoneToast)
                            },
                        ),
                        text = ModImportChromeText(
                            intro = strings.modImportIntro,
                            pickButton = strings.modImportPickButton,
                            // 确认 / 取消复用设置页既有的三语键（预览面不另开文案键）。
                            confirmButton = strings.okText,
                            cancelButton = strings.cancelText,
                        ),
                        previewPhase = modImportPreviewPhaseOf(modImportFeed),
                        copy = ModImportCopy.of(lang),
                    )
                }
                // 口上市场覆盖层（MKM-W1）：入口卡片点「打开市场」后弹出；按需组合，关掉即从
                // 组合树摘除。服务未装配（取数管线未合流，marketService = null）时按诚实态出词，
                // 入口卡与覆盖层照样可开（market-source.ts NO_SERVICE_VIEW 口径）。
                if (marketVisible) {
                    MarketChrome(
                        state = marketState,
                        copy = marketCopy,
                        serviceReady = marketService != null,
                        actions = MarketChromeActions(
                            onDismiss = { marketVisible = false },
                            onPull = { marketReload(force = true) },
                            onFilterKind = { kind -> marketPost(MarketUiEvent.FilterChanged(kind), marketCopy) },
                            onInstall = { row -> marketInstall(row, marketCopy) },
                            onBridgeImport = { row -> marketBridgeImport(row, marketCopy) },
                            onUninstallRequest = { row -> marketPost(MarketUiEvent.UninstallConfirm(row), marketCopy) },
                            onUninstallConfirm = { marketRunUninstall(marketCopy) },
                            onUninstallCancel = { marketPost(MarketUiEvent.UninstallConfirm(null), marketCopy) },
                            onEndpointDraftChange = { draft ->
                                marketPost(MarketUiEvent.EndpointDraftChanged(draft), marketCopy)
                            },
                            onEndpointSave = { marketSaveEndpoint(marketState.endpointDraft, marketCopy) },
                            onEndpointReset = { marketSaveEndpoint("", marketCopy) },
                            onToggleFavorite = { row -> marketToggleFavorite(row, marketCopy) },
                            onFavoritesOnly = { only ->
                                marketPost(MarketUiEvent.FavoritesOnlyChanged(only), marketCopy)
                            },
                        ),
                    )
                }
                // 「清除已导入素材」的二次确认：卡片上的按钮只置 clearTarget，这里才真正动手。
                // 形态与「恢复默认设置」那张确认框一致（共享 [ShellAlertDialog] + 警示色确认键），句子
                // 全部来自 ResourcePackCopy，壳层不拼字。
                val pendingClear = clearTarget
                if (pendingClear != null) {
                    ShellAlertDialog(
                        title = materialCopy.clearConfirmTitle,
                        onDismissRequest = { clearTarget = null },
                        actions = ShellDialogActions(
                            confirmLabel = materialCopy.clearConfirmOk,
                            onConfirm = {
                                clearTarget = null
                                clearImportedMaterials(pendingClear)
                            },
                            confirmTone = EratwInk.warn(),
                            dismissLabel = materialCopy.clearConfirmCancel,
                            onDismiss = { clearTarget = null },
                        ),
                    ) {
                        Text(
                            text = clearConfirmBody(materialCopy, pendingClear),
                            color = EratwInk.text(),
                            style = ShellType.Body,
                        )
                    }
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
                // 先读齐（单文件体积已由 readBounded 卡住）：配对判据要看**整批**，
                // 侧车能否落位取决于同批本体是否也判可落位，与玩家选文件的顺序无关。
                val (items, unread) = readImportItems(uris, t)
                lines += unread
                val context = SaveImport.Context(config, base, slotCount, ::occupiedAt)
                val (judgeLines, judgePending) = planImportUnits(items, SaveImport.planBatch(items, context), t)
                lines += judgeLines
                pending += judgePending
            } catch (error: Throwable) {
                // 整批判读抛了未预期的异常（逐条的 Exception 已在 planBatch 内收成拒收；
                // 到这里多半是 OOM 一类的 Error 或编程错误）：不许拖死整批，也不许让按钮
                // 永久卡在「正在导入」。批级失败只有一句，名字用「选中的文件」兜底。
                val detail = error.message?.takeIf { it.isNotBlank() } ?: t.unexpectedFailure
                lines += ImportLine(t.importRejected(t.selectedFileFallback, detail), false)
            } finally {
                runOnUiThread {
                    importing = false
                    importLines = (lines + importLines).take(IMPORT_LINES_MAX)
                    overwritePending = pending
                }
            }
        }.start()
    }

    /**
     * 读齐一次多选：名字 + 字节（单文件体积闸在 [readBounded] 里）。
     * 读不动的项当场出一条「未导入」行，不进返回的 [SaveImport.Item] 列表。
     */
    private fun readImportItems(
        uris: List<Uri>,
        t: SettingsStrings,
    ): Pair<List<SaveImport.Item>, List<ImportLine>> {
        val items = ArrayList<SaveImport.Item>(uris.size)
        val lines = mutableListOf<ImportLine>()
        for (uri in uris) {
            val name = displayNameOf(uri, t.selectedFileFallback)
            val bytes = readBounded(uri)
            if (bytes == null) {
                lines += ImportLine(t.importUnread(name), false)
                continue
            }
            items += SaveImport.Item(name, bytes)
        }
        return items to lines
    }

    /**
     * 判定结果 → （提示行, 待确认单元）。
     *
     * 按**逻辑存档单元**归组（key = 本体落位路径，[SaveImport.bodyTargetOf]）：本体与它的
     * `.rt2` 侧车一起进覆盖确认、一起落盘/一起取消（MODGC2 裁定 1）。单元内任一成员占用
     * ⇒ 整单元等玩家确认；不许「侧车先写进去、本体等确认」。
     */
    private fun planImportUnits(
        items: List<SaveImport.Item>,
        plans: List<SaveImport.Plan>,
        t: SettingsStrings,
    ): Pair<List<ImportLine>, List<PendingImport>> {
        val lines = mutableListOf<ImportLine>()
        val pending = mutableListOf<PendingImport>()
        val units = LinkedHashMap<String, MutableList<PendingImport.Member>>()
        items.forEachIndexed { index, item ->
            when (val plan = plans[index]) {
                is SaveImport.Reject ->
                    lines += ImportLine(t.importRejected(item.fileName, t.saveReason(plan.reason)), false)

                is SaveImport.Place ->
                    units.getOrPut(SaveImport.bodyTargetOf(plan.targetRel)) { mutableListOf() } +=
                        PendingImport.Member(plan, item.bytes, item.fileName)
            }
        }
        for (members in units.values) {
            if (members.any { it.place.occupied }) pending += PendingImport(members) else lines += placeUnit(members, t)
        }
        return lines to pending
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

    /**
     * 一个逻辑存档单元的落盘（MODGC2 裁定 1）：**本体先、侧车随后**。
     *
     * 本体写失败 ⇒ 整单元不写（只报本体那一条）：半套配对比少一件扩展状态更坏 ——
     * 留下的侧车会在下次读档时配到旧的/别的本体上。本体写成、侧车写失败则两行都照实报
     * （读档时引擎会按「绑定失配/缺失」给警告，era-arch §8 `:225-231`）。
     */
    private fun placeUnit(
        members: List<PendingImport.Member>,
        t: SettingsStrings,
    ): List<ImportLine> {
        val ordered = members.sortedBy { if (SaveImport.isSidecarName(it.fileName)) 1 else 0 }
        val body = ordered.first()
        val bodyLine = placeLine(body.place, body.bytes, t)
        if (!bodyLine.ok) return listOf(bodyLine)
        return listOf(bodyLine) + ordered.drop(1).map { placeLine(it.place, it.bytes, t) }
    }

    /** 对话框选「覆盖」：把攒下的项后台落盘，结果并进同一叠提示行。 */
    private fun commitOverwrite() {
        val items = overwritePending
        overwritePending = emptyList()
        if (items.isEmpty()) return
        val t = text
        importing = true
        Thread {
            // 逐单元落盘：本体先、侧车随后（[placeUnit]），单元内不会只写进去一半。
            val lines = items.flatMap { placeUnit(it.members, t) }
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
    // 在 [runModImport] 的后台线程；启动路径（MainActivity 的 onCreate / startGameOrDemo）
    // 与 GameHost 装载链不碰本段任何入口。
    // -----------------------------------------------------------------

    /** 导入覆盖层是否打开（[ModImportChrome] 的组合时机；状态本身仍走 [ModImportUiState]）。 */
    private var modImportVisible by mutableStateOf(false)

    /** 导入页状态机（导入中 / 成功 / 失败三态 + 报告快照；转移只走 [ModImportUiReducer]）。 */
    private var modImportState by mutableStateOf<ModImportUiState>(ModImportUiState.Idle(null))

    /** 导入单飞闸：编译上下文要读 328 MB 的 base 产物，并行导入只会翻倍内存峰值。 */
    private val modImportBusy = AtomicBoolean(false)

    /**
     * 预览喂入状态机（选完文件 → 只读预览上屏 → 玩家确认 → 才进导入事务）。
     * 转移唯一入口 = [ModImportFeedReducer.reduce]；导入的放行口只有
     * [ModImportFeedReducer.importBatchOf]（预览**先于**导入的判据所在）。
     */
    private var modImportFeed by mutableStateOf<ModImportFeedState>(ModImportFeedState.Idle)

    /** 预览 IO 单飞闸：读字节 + 算预览期间不收新一批（重选会先清旧预览再算）。 */
    private val modImportPreviewBusy = AtomicBoolean(false)

    /** 选区序号（预览迟到结果按它丢弃，避免上一批读数串到新一批；只在 UI 线程推进）。 */
    private var modImportSelectionSeq = 0L

    // -----------------------------------------------------------------
    // 口上市场（MKM-W1 UI 骨架）：入口卡 + 全屏覆盖层。状态提升照补丁导入段的同一形态
    // （Activity 持 mutableStateOf，转移唯一入口 = MarketViewModel.reduce，服务调用在
    // 后台 Thread 阻塞跑、经 runOnUiThread 落屏——照 ModImportController 后台线程模式）。
    // -----------------------------------------------------------------

    /** 市场覆盖层是否打开（[MarketChrome] 的组合时机）。 */
    private var marketVisible by mutableStateOf(false)

    /**
     * 市场界面状态（快照 / 分组 / 互斥闸 / 进度 / 卸载确认框 / 端点草稿）。
     * 应用版本在构造期从 [BuildConfig.VERSION_NAME] 注入（契约裁定 8：minAppVersion 闸的
     * 当前版本由 UI 路传入）。
     */
    private var marketState by mutableStateOf(MarketViewModel.initial(BuildConfig.VERSION_NAME))

    /**
     * 市场服务实例（[MarketService] 门面缝）。实现 = mkm-pipe 路交付的 [MarketServiceImpl]，
     * 组长接线（CONTRACT-MKM-W1 需组长接线①）：[onCreateCore] 开头构造，根 = `<filesDir>/market`，
     * 校验缝 = domain 路交付的 [MarketVerifierImpl]。字段本身保 nullable（覆盖层「服务未装配」
     * 诚实态分支保留，测试/未来装配失败回退口径不变）。
     */
    private var marketService: MarketService? = null

    /** 下载量统计编排（缓存+TTL+降级，CONTRACT-MKM-STATS-UI §1 D1）；onCreateCore 装配。 */
    private lateinit var marketStatsLoader: MarketStatsLoader

    /** 本机收藏存储（落盘真源，CONTRACT-MKM-STATS-UI §1 D2）；onCreateCore 装配。 */
    private lateinit var marketFavoritesStore: MarketFavoritesStore

    /**
     * 市场入口卡概览读数（CONTRACT-MKM-STATS-UI §2）：onCreateCore 后台盘读一次落屏，
     * 收藏 toggle 后同步收藏计数；null = 还没读出来（卡面统计格演「—」，不演假零）。
     */
    private var marketEntryOverview by mutableStateOf<MarketEntryOverview?>(null)

    /**
     * 「装进游戏」接线桥（wire 路交付的 [MarketGameBridge]；CONTRACT-MKM-W3-UI2 裁定 2 的
     * 生产装配点在 [onCreateCore]）：把市场已装包送进既有导入链并记账到导入台账。字段保
     * nullable 与 [marketService] 同一条诚实态分支；构造零 IO（真实 IO 全部延迟到首次
     * importInstalled）。
     */
    private var marketGameBridge: MarketGameBridge? = null

    /**
     * 一键更新链的台账只读查询缝（CONTRACT-MKM-W4-UI3 裁定 1）：判定「该 `(kind,id)` 是否
     * 装过游戏」用的 [MarketImportsLedger] 实例（[onCreateCore] 与桥同段装配，指向同一个
     * imports.jsonl）。只消费其只读 [MarketImportsLedger.lookup]；写路径仍归桥独占（桥文件
     * 禁触，本字段不做任何 upsert）。字段保 nullable 与 [marketService] 同一条诚实态分支。
     */
    private var marketImportsLedger: MarketImportsLedger? = null

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
        // 选完先进「只读预览」：确认后才调 [runModImport]（预览先于导入；取消不留盘面）。
        if (!uris.isNullOrEmpty()) startModImportPreview(uris)
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

    /**
     * 「清除已导入素材」的二次确认目标（null = 不弹框）。
     *
     * 破坏性动作（删文件）一律先确认：卡片上的按钮只置这一格，真正的清理要等确认框点「清除」
     * 才落到 [clearImportedMaterials]。与「恢复默认设置」的 `confirmRestore` 同一模式，
     * 区别只是这里的范围只有两个互斥值（[MaterialTarget.BACKGROUND] / [MaterialTarget.BGM]）。
     */
    private var clearTarget by mutableStateOf<MaterialTarget?>(null)

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

    /** 关覆盖层：待确认批与旧预览一并作废（不留盘面，下次打开从干净状态起）。 */
    private fun dismissModImport() {
        modImportVisible = false
        cancelModImportPreview()
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

    /**
     * 导入链与 mods 清单共用的控制器（无状态，随用随建；[ModStore] 也是薄包装）。
     *
     * [codeGateEnabled] 是第三方代码装载总闸快照（task-179）：调用方在 UI 线程读好再传进来，
     * 后台线程不碰 prefs（照 `modRuntimeEnabled` 的口径）。
     */
    private fun modImportController(codeGateEnabled: Boolean = true): ModImportController = ModImportController(
        modStore = ModStore(ModStore.modsDirFor(gameRootFile)),
        baseSource = ProductionBaseCompileContextSource,
        gameRoot = gameRootFile,
        codeGateEnabled = codeGateEnabled,
    )

    // -----------------------------------------------------------------
    // 口上市场的响应链（MKM-W1）：两段式刷新（stale-while-revalidate）、装/卸（后台 Thread
    // 阻塞跑服务 + runOnUiThread 落屏）、端点保存。**全部判定在 MarketViewModel.reduce 与
    // 纯函数里**，本段只做线程编排与事件投递。
    // -----------------------------------------------------------------

    /** 打开市场覆盖层并按 TTL 两段式刷新（stale 段先秒上屏，revalidate 段后台回源）。 */
    private fun openMarket() {
        marketVisible = true
        marketReload(force = false)
        marketLoadStats()
        marketLoadFavorites()
    }

    /**
     * 下载量统计独立异步段（CONTRACT-MKM-STATS-UI §1 D1）：TTL 内回盘上缓存不联网，
     * 过期或缺席才联网；成与败都以缓存态落屏——UI 按有无数据折「—」，失败不进状态行
     * （统计是锦上添花，不挤掉安装回执那句话，也不阻塞市场列表段）。
     */
    private fun marketLoadStats() {
        marketAsync {
            val outcome = marketStatsLoader.load(force = false)
            val state = when (outcome) {
                is MarketStatsLoader.Outcome.Ok -> MarketStatsState.Ok(outcome.snapshot)
                is MarketStatsLoader.Outcome.Stale ->
                    outcome.previous?.let { MarketStatsState.Ok(it) } ?: MarketStatsState.Absent
            }
            marketPost(MarketUiEvent.StatsLoaded(state), MarketCopy.of(language))
        }
    }

    /** 本机收藏集读盘段（CONTRACT-MKM-STATS-UI §1 D2；损坏回空集是存储侧纪律，宿主只透传）。 */
    private fun marketLoadFavorites() {
        marketAsync {
            marketPost(MarketUiEvent.FavoritesLoaded(marketFavoritesStore.read()), MarketCopy.of(language))
        }
    }

    /**
     * 收藏 toggle（CONTRACT-MKM-STATS-UI §1 D2）：改集 + 落盘真源，回执带 toggle 后的新全集
     * 落屏。落盘失败不改显示面（收藏没成的事实玩家从按钮无反应看到，不演出假成功），
     * Log 记录取证；收藏态与入口卡概览同步（真源同一文件）。
     */
    private fun marketToggleFavorite(row: MarketViewModel.MarketRow, copy: MarketCopy) {
        marketAsync {
            val key = MarketContract.favoriteKey(row.entry.kind, row.entry.id)
            val current = marketFavoritesStore.read()
            val next = if (key in current) current - key else current + key
            if (marketFavoritesStore.write(next)) {
                marketPost(MarketUiEvent.FavoriteToggled(next), copy)
                marketEntryOverview = marketEntryOverview?.copy(favoriteCount = next.size)
            } else {
                Log.w(TAG, "市场：收藏落盘失败 kind=${row.entry.kind} id=${row.entry.id}")
            }
        }
    }

    /**
     * 刷新：force=false = 打开时的两段式（先本地段秒上屏，再按 TTL 回源，market-panel.ts:207-223）；
     * force=true = 「拉取列表」的硬回源（无视 TTL）。服务阻塞调用 ⇒ 全部挪后台线程。
     */
    private fun marketReload(force: Boolean) {
        val service = marketService ?: return
        val copy = MarketCopy.of(language)
        marketPost(MarketUiEvent.ReloadStarted, copy)
        marketAsync {
            if (!force) {
                // 本地段：零网络读盘上现状，秒上屏（busy 只罩这一下，远程慢不许卡住打开）
                marketPost(MarketUiEvent.LocalLoaded(marketLocalSnapshot(service, copy)), copy)
            }
            // 回源回执三态（CONTRACT-MKM-W6 缝 1）：Fresh/Cached/Stale 各出各的词；
            // Stale 时本机现列表以 indexState() 权威（快照重读，坏响应不覆盖盘上文件）。
            val outcome = service.refreshIndex(force)
            val note = marketPullNote(copy, outcome, service.indexState())
            marketPost(MarketUiEvent.PullDone(marketLocalSnapshot(service, copy), note), copy)
        }
    }

    /**
     * 一次本地快照（零网络）：索引三态 + 已装表 + 占用 + 生效端点 + 逐行更新三态。
     * 服务读数任何一条失败都折成一句人话上屏（禁静默，actions.ts failedSnapshot :99-113 口径）。
     */
    private fun marketLocalSnapshot(
        service: MarketService,
        copy: MarketCopy,
    ): MarketViewModel.MarketSnapshot = runCatching {
        val index = service.indexState()
        val packs = (index as? MarketIndexState.Ok)?.index?.packs ?: emptyList()
        MarketOptimistic.snapshotOf(
            index = index,
            updates = packs.associate { MarketViewModel.indexRowKey(it) to service.updateCheck(it) },
            installed = service.installed(),
            installedBytes = service.installedBytes(),
            endpoint = service.endpoint(),
        )
    }.getOrElse { e ->
        Log.w(TAG, "市场：本地快照读取失败（按失败句呈现）", e)
        MarketOptimistic.snapshotOf(
            index = MarketIndexState.Invalid(copy.fill(copy.readFailedFormat, e.message ?: e::class.simpleName ?: "")),
            updates = emptyMap(),
            installed = emptyList(),
            installedBytes = 0L,
            endpoint = MarketContract.DEFAULT_INDEX_URL,
        )
    }

    /**
     * 一次回源的回执（CONTRACT-MKM-W6 缝 1，market-view.ts refreshNote :287-299 的可观测半边）：
     * Fresh = 真回执条数；Cached = 没回源的新句（不装作拉过）；Stale = 回源失败，本机三态为
     * Invalid 时沿用「已拉取但读不出内容」句（回源成功但校验拒的原形态），否则按失败原话出
     * [MarketCopy.readFailedFormat]（W1 的「失败也报已拉取」缝缺口由此收口）。
     */
    private fun marketPullNote(copy: MarketCopy, outcome: MarketRefreshOutcome, state: MarketIndexState): String =
        when (outcome) {
            is MarketRefreshOutcome.Fresh -> copy.fill(copy.pullOkFormat, outcome.index.packs.size)
            is MarketRefreshOutcome.Cached -> copy.pullCachedNote
            is MarketRefreshOutcome.Stale -> when (state) {
                is MarketIndexState.Invalid -> copy.pullInvalidNote
                else -> copy.fill(copy.readFailedFormat, outcome.problem)
            }
        }

    /** 状态机投递（与 postPackEvent 同款：runOnUiThread + isFinishing 闸）。 */
    private fun marketPost(event: MarketUiEvent, copy: MarketCopy) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            marketState = MarketViewModel.reduce(marketState, event, copy)
        }
    }

    /** 市场后台线程（照 ModImportController 的裸 Thread 模式；服务阻塞调用不占 UI 线程）。 */
    private fun marketAsync(block: () -> Unit) {
        Thread({
            runCatching(block).onFailure { e -> Log.w(TAG, "市场：后台任务未预期失败", e) }
        }, "market-io").start()
    }

    /**
     * 装 / 更新这一行（market-panel.ts doInstall :244-276）：NONE 行不放按钮（market-view.ts
     * rowAction :471-476）所以这里再挡一道；单键互斥在 UI 禁用之外再挡一道。进度回调接进
     * 同一条状态行（节流在服务侧，壳层不重复闸）；拒因一律出自 [MarketPhrases.rejectPhrase]
     * 那一张表；失败半边主动重读（:272-275）。装库成功后走一键更新链（CONTRACT-MKM-W4-UI3
     * 裁定 1，见 [marketUpdateChainOutcome]）——装库失败进不到桥段，既有拒因文案收口。
     */
    private fun marketInstall(row: MarketViewModel.MarketRow, copy: MarketCopy) {
        val service = marketService ?: return
        val runnable = row.action != MarketViewModel.MarketRowAction.NONE && marketState.installingKey == null
        if (!runnable) return
        val entry = row.entry
        val isUpdate = row.action == MarketViewModel.MarketRowAction.UPDATE
        val key = MarketViewModel.indexRowKey(entry)
        // 一键更新链的总闸读数照 marketBridgeImport 口径：UI 线程一次取好随调用进线程（后台线程不碰 prefs）。
        val modRuntimeOn = UiPrefs.modRuntime(this)
        marketPost(MarketUiEvent.InstallStarted(key, MarketPhrases.installBusyPhrase(copy, isUpdate, entry.name)), copy)
        marketAsync {
            val outcome = runCatching {
                // 阶段回调直通（CONTRACT-MKM-W6 缝 3）：DOWNLOAD/EXTRACT/COMMIT 随事件进状态机，
                // 进度条对三阶段、EXTRACT/COMMIT 段的句子在 MarketViewModel 的转移里折
                service.install(entry) { phase, done, total ->
                    marketPost(MarketUiEvent.InstallProgress(phase, done, total), copy)
                }
            }.getOrElse { e ->
                Log.w(TAG, "市场：安装未预期异常", e)
                InstallOutcome.Failed(e.message ?: e::class.simpleName ?: "")
            }
            when (outcome) {
                is InstallOutcome.Ok -> marketInstallOkFinished(key, copy, outcome.pack, modRuntimeOn)
                is InstallOutcome.Rejected -> {
                    marketPost(
                        MarketUiEvent.InstallFinished(
                            key,
                            copy.fill(
                                copy.rejectNoteFormat,
                                entry.name,
                                MarketPhrases.rejectPhrase(copy, outcome.code),
                            ),
                            null,
                        ),
                        copy,
                    )
                    marketPost(MarketUiEvent.InstalledRevalidated(marketLocalSnapshot(service, copy)), copy)
                }
                is InstallOutcome.Failed -> {
                    marketPost(
                        MarketUiEvent.InstallFinished(
                            key,
                            copy.fill(copy.rejectNoteFormat, entry.name, outcome.problem),
                            null,
                        ),
                        copy,
                    )
                    marketPost(MarketUiEvent.InstalledRevalidated(marketLocalSnapshot(service, copy)), copy)
                }
            }
        }
    }

    /**
     * 装库成功的落屏（CONTRACT-MKM-W4-UI3 裁定 1）：装库成功后条件链调桥——台账里有该
     * `(kind,id)` 记录（玩家此前装过游戏）才把新版自动装进游戏；台账无记录只装库，不擅自改
     * 游戏状态。完成句两形态折算在 [MarketUpdateChain.doneNote]；乐观落账随 InstallFinished
     * 的记账条走（kind/id 与索引条目同源，manifest-mismatch 闸保证装回来的就是这一条）。
     */
    private fun marketInstallOkFinished(key: String, copy: MarketCopy, pack: InstalledPack, modRuntimeOn: Boolean) {
        val chain = marketUpdateChainOutcome(pack.kind, pack.id, pack, modRuntimeOn)
        marketPost(
            MarketUiEvent.InstallFinished(
                key,
                MarketUpdateChain.doneNote(copy, pack.name, pack.version, chain),
                pack,
            ),
            copy,
        )
    }

    /**
     * 一键更新链的桥段（CONTRACT-MKM-W4-UI3 裁定 1）：判定真源 = [MarketUpdateChain.run]
     * （JVM 级假桥单测钉调用次数的那条缝）。台账查询缝 = [MarketImportsLedger.lookup]
     * （只读，null = 无记录）；台账读不出（IOException）按「无记录」处理——不知道玩家装没
     * 装过游戏时，宁可只装库也不擅动游戏状态。桥或台账缝缺位（诚实态分支）= 链不跑。
     */
    private fun marketUpdateChainOutcome(
        kind: String,
        id: String,
        pack: InstalledPack,
        modRuntimeEnabled: Boolean,
    ): MarketBridgeOutcome? {
        val seams = marketGameBridge?.let { bridge -> marketImportsLedger?.let { ledger -> bridge to ledger } }
            ?: return null
        val (bridge, ledger) = seams
        val hasRecord = runCatching { ledger.lookup(kind, id) }
            .onFailure { e -> Log.w(TAG, "市场：更新链的导入台账读不出（按从未装进游戏处理）", e) }
            .getOrNull() != null
        return MarketUpdateChain.run(
            installOk = true,
            ledgerHasRecord = hasRecord,
            pack = pack,
            modRuntimeEnabled = modRuntimeEnabled,
        ) { p, on -> bridge.importInstalled(p, modRuntimeEnabled = on) }
    }

    /**
     * 按包卸载这一条（CONTRACT-MKM-W4-UI3 裁定 3；market-panel.ts doUninstall :284-307 的
     * 壳层口径）：已装行按包去重后一行 = 一个 `(kind,id)`，确认后对该包**全部已装版本**逐个
     * 调 [MarketService.uninstall]（骨架签名零改动，循环调用；各版本暂存残壳清扫随每次卸载
     * 自带）——全成才出成功文案；任一失败出失败文案（首个 problem 上屏，detail 纪律照旧）。
     * 与安装同一把单飞闸，漏了互斥会把成功演成失败；「取消」永远可点不动（确认门前一个字节
     * 都不动）。成败折算在 [marketPackageUninstallOutcome]，这里只做事件编排。
     */
    private fun marketRunUninstall(copy: MarketCopy) {
        val service = marketService ?: return
        // 确认目标与互斥闸一起判：没确认目标、或正在装别的包 ⇒ 一个字节都不动（:242-244/:285）
        val pending = marketState.pendingUninstall?.takeIf { marketState.installingKey == null } ?: return
        val rec = pending.rec
        marketPost(MarketUiEvent.UninstallStarted(pending.key, copy.fill(copy.uninstallRunningFormat, rec.name)), copy)
        marketAsync {
            val outcome = marketPackageUninstallOutcome(service, rec)
            val removed = if (outcome is UninstallOutcome.Ok) rec else null
            // 卸载联动（CONTRACT-MKM-W4-UNLINK 组长接线）：库全清后顺路清游戏产物与台账
            // （fail-soft；Cleaned/Kept 静默，失败缀一句人话在卸载成功句后，detail 不上屏）。
            val syncNote = if (removed != null) marketUninstallSyncNote(service, rec, copy) else null
            val note = buildString {
                append(MarketPhrases.uninstallPhrase(copy, rec.name, outcome))
                if (syncNote != null) {
                    append(' ')
                    append(syncNote)
                }
            }
            marketPost(
                MarketUiEvent.PackageUninstallFinished(
                    pending.key,
                    note,
                    removed,
                ),
                copy,
            )
            // 同 doInstall：失败不出声于服务，但半趟只有重读能判 ⇒ 主动核一趟（market-panel.ts:300-303）
            if (removed == null) {
                marketPost(MarketUiEvent.InstalledRevalidated(marketLocalSnapshot(service, copy)), copy)
            }
        }
    }

    /**
     * 卸载联动一步（组长接线①，CONTRACT-MKM-W4-UNLINK）：剩余版本以服务权威在位表现算，
     * 交桥 [MarketGameBridge.syncUninstalled] 三相位清产物与台账。Kept/Cleaned 静默返回 null；
     * Failed 或未预期异常返回 [MarketCopy.uninstallSyncFailedFormat] 人话（problem 原话只进日志）。
     */
    private fun marketUninstallSyncNote(service: MarketService, rec: InstalledPack, copy: MarketCopy): String? =
        marketGameBridge?.let { bridge ->
            uninstallSyncNoteOf(marketUninstallSyncOutcome(bridge, service, rec), copy)
        }

    /** 联动结果折算：仅 Failed 出一句人话（problem 原话已在 [marketUninstallSyncOutcome] 进日志）；Cleaned/Kept 静默。 */
    private fun uninstallSyncNoteOf(outcome: BridgeSyncOutcome, copy: MarketCopy): String? =
        if (outcome is BridgeSyncOutcome.Failed) copy.fill(copy.uninstallSyncFailedFormat) else null

    /** 联动执行件：在位读数与桥调用各自 fail-soft 折成 [BridgeSyncOutcome]（异常归 Failed，problem 原话只进日志）。 */
    private fun marketUninstallSyncOutcome(
        bridge: MarketGameBridge,
        service: MarketService,
        rec: InstalledPack,
    ): BridgeSyncOutcome =
        runCatching { service.installed().count { it.kind == rec.kind && it.id == rec.id } }.fold(
            onSuccess = { remaining ->
                runCatching { bridge.syncUninstalled(rec.kind, rec.id, remaining) }.getOrElse { e ->
                    Log.w(TAG, "市场：卸载联动未预期异常", e)
                    BridgeSyncOutcome.Failed(e.message ?: e::class.simpleName ?: "")
                }
            },
            onFailure = { e ->
                Log.w(TAG, "市场：卸载联动的在位读数未预期失败", e)
                BridgeSyncOutcome.Failed(e.message ?: e::class.simpleName ?: "")
            },
        )

    /**
     * 按包卸载的成败折算（裁定 3）：版本圈定以服务的权威在位表为准（快照可能滞后；
     * installed() 自带在位自愈），逐版本卸完后经 [MarketPackageUninstall.fold] 折成一个
     * [UninstallOutcome]（任一失败取首个 problem；一个都没卸成 = NotFound）。
     */
    private fun marketPackageUninstallOutcome(service: MarketService, rec: InstalledPack): UninstallOutcome =
        runCatching { service.installed().filter { it.kind == rec.kind && it.id == rec.id } }.fold(
            onSuccess = { versions -> marketUninstallEach(service, versions) },
            onFailure = { e ->
                Log.w(TAG, "市场：按包卸载的在位读数未预期失败", e)
                UninstallOutcome.Failed(e.message ?: e::class.simpleName ?: "")
            },
        )

    /** 逐个卸掉该包的全部已装版本（每个版本的暂存残壳清扫随 [MarketService.uninstall] 自带），结果交纯折叠。 */
    private fun marketUninstallEach(service: MarketService, versions: List<InstalledPack>): UninstallOutcome =
        MarketPackageUninstall.fold(
            versions.map { v ->
                runCatching { service.uninstall(v.kind, v.id, v.version) }.getOrElse { e ->
                    Log.w(TAG, "市场：按包卸载未预期异常", e)
                    UninstallOutcome.Failed(e.message ?: e::class.simpleName ?: "")
                }
            },
        )

    /**
     * 「装进游戏」这一条（CONTRACT-MKM-W3-UI2 裁定 3）：已装行的桥按钮 → 后台 Thread 跑
     * 阻塞 [MarketGameBridge.importInstalled]（清单读取、编译链、台账 IO 全同步）→ 五态折成
     * 一句人话进同一条状态行（[MarketBridgeUi.bridgeNote]；failure 原文不上屏）。
     *
     * 单飞与装/卸共用 installingKey 一把闸：忙时行按钮 disabled 之外，宿主入口再挡一道
     * （[MarketBridgeUi.bridgeImportEnabled] 三处同一函数单源）；装无确认门（裁定 4）。
     * 总闸读数照 UpdateCopy 口径在 UI 线程一次取好随调用进线程（后台线程不碰 prefs）。
     * 装进游戏不动市场库 ⇒ Ok 半边把原记账条原样带回（乐观更新退化为幂等替换，只为
     * warn = false 与清闸）；失败半边 installedRec = null 并由宿主主动重读（照 marketInstall）。
     * service 与桥在 onCreateCore 同处装配，桥非空 ⇒ service 理论非空；失败重读是增益
     * 动作不是成败判据，service 缺位就跳过（不作为第二道成败出口）。
     */
    private fun marketBridgeImport(row: MarketViewModel.MarketInstalledRow, copy: MarketCopy) {
        val bridge = marketGameBridge ?: return
        if (!MarketBridgeUi.bridgeImportEnabled(marketState)) return
        val rec = row.rec
        val modRuntimeOn = UiPrefs.modRuntime(this)
        marketPost(MarketUiEvent.InstallStarted(row.key, MarketBridgeUi.bridgeBusyPhrase(copy, rec.name)), copy)
        marketAsync {
            val outcome = runCatching {
                bridge.importInstalled(rec, modRuntimeEnabled = modRuntimeOn)
            }.getOrElse { e ->
                Log.w(TAG, "市场：装进游戏未预期异常", e)
                MarketBridgeOutcome.ImportFailed(
                    ModImportOutcome.Failed(ModImportFailureKind.UNEXPECTED, e.message ?: e::class.simpleName ?: ""),
                )
            }
            marketPost(
                MarketUiEvent.InstallFinished(
                    row.key,
                    MarketBridgeUi.bridgeNote(copy, rec.name, outcome),
                    if (outcome is MarketBridgeOutcome.Ok) rec else null,
                ),
                copy,
            )
            if (outcome !is MarketBridgeOutcome.Ok) {
                marketService?.let { service ->
                    marketPost(MarketUiEvent.InstalledRevalidated(marketLocalSnapshot(service, copy)), copy)
                }
            }
        }
    }

    /**
     * 端点保存（market-source.ts planEndpointEdit :30-37 + applyEndpointEdit :155-183）：
     * keep/reset 判定在壳层，形态闸在 [MarketService.setEndpoint]（骨架唯一写入口，白名单
     * 在取数时核）。「恢复默认」按钮走的就是空草稿这条既有 reset 通路（:189-196）。
     */
    private fun marketSaveEndpoint(draft: String, copy: MarketCopy) {
        val service = marketService ?: return
        marketAsync {
            val trimmed = draft.trim()
            val result = when {
                trimmed == service.endpoint() -> EndpointEditResult.Kept
                trimmed.isEmpty() -> service.setEndpoint("")
                else -> service.setEndpoint(trimmed)
            }
            marketPost(
                MarketUiEvent.EndpointSaved(service.endpoint(), MarketEndpointUi.endpointNote(copy, result)),
                copy,
            )
        }
    }

    /**
     * SAF 选完的喂入点：**先只读预览、确认后才进导入事务**。
     *
     * 后台只做「读字节 + [ModImportPreview.compute]」两件事，绝不落盘、不碰 rt2 写；语言与
     * rt2 目录一次取好随调用进线程（照 `UpdateCopy` 的口径：纯层与后台线程不碰 prefs、
     * 不判 Locale）。预览算不出来只降级显示「预览不可用…（不影响导入）」，**不阻断确认**；
     * 取消 / 关页不产生任何落盘。读字节阶段全失败也走同一条降级路
     * （[computeModImportPreviewSafely]）。
     */
    private fun startModImportPreview(uris: List<Uri>) {
        if (!modImportPreviewBusy.compareAndSet(false, true)) {
            Log.w(TAG, "补丁预览请求被忽略：上一批预览还在计算")
            return
        }
        val copy = ModImportCopy.of(language)
        val rt2Dir = ModRuntimeAssembly.manifestDirFor(gameRootFile)
        val selectionId = ++modImportSelectionSeq
        modImportFeed = ModImportFeedReducer.reduce(modImportFeed, ModImportFeedEvent.SelectionStarted(selectionId))
        // 重选先清旧预览：旧读数立刻下屏，新一批算完再上（迟到结果按 selectionId 丢弃）。
        modImportState = ModImportUiReducer.reduce(modImportState, ModImportEvent.PreviewRefreshed(null), copy)
        Thread({
            val picked = ArrayList<PickedModFile>(uris.size)
            val readErrors = ArrayList<String>()
            try {
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
                val preview = computeModImportPreviewSafely(picked, readErrors, rt2Dir, copy)
                runOnUiThread { showModImportPreview(selectionId, picked, readErrors, preview, copy) }
            } catch (t: Throwable) {
                // 预览线程没有静默出口：未预期异常同样折成降级预览（不阻断确认），照常上屏。
                val fallback = Preview.Unavailable(
                    copy.fill(copy.unexpectedFormat, t::class.simpleName ?: "null", t.message ?: "null"),
                )
                runOnUiThread { showModImportPreview(selectionId, picked, readErrors, fallback, copy) }
            } finally {
                modImportPreviewBusy.set(false)
            }
        }, "eratw-modimport-preview").start()
    }

    /**
     * 预览线程回 UI 线程的唯一落点：只有当前仍是 [selectionId] 那批（没被重选 / 取消）
     * 状态机才推进到「待确认」，预览读数也只在真推进时才上屏。
     */
    private fun showModImportPreview(
        selectionId: Long,
        picked: List<PickedModFile>,
        readErrors: List<String>,
        preview: Preview,
        copy: ModImportCopy,
    ) {
        if (isFinishing) return
        val next = ModImportFeedReducer.reduce(
            modImportFeed,
            ModImportFeedEvent.PreviewComputed(selectionId, picked, readErrors, preview),
        )
        if (next !is ModImportFeedState.AwaitingConfirm) return
        modImportFeed = next
        modImportState = ModImportUiReducer.reduce(
            modImportState,
            ModImportEvent.PreviewRefreshed(next.preview),
            copy,
        )
    }

    /**
     * 玩家确认预览：把待确认批交给导入链。放行口只有
     * [ModImportFeedReducer.importBatchOf]（[ModImportFeedState.Running] 之外一律 null）——
     * 「预览先于导入」的判据就在这里，不靠 UI 按钮的可见性。
     */
    private fun confirmModImport() {
        val next = ModImportFeedReducer.reduce(modImportFeed, ModImportFeedEvent.Confirmed)
        modImportFeed = next
        val picked = ModImportFeedReducer.importBatchOf(next) ?: return
        runModImport(picked, ModImportFeedReducer.readErrorsOf(next), ModImportCopy.of(language))
    }

    /** 取消预览 / 关页：清掉待确认批与预览读数（不落盘、不进导入事务）。 */
    private fun cancelModImportPreview() {
        // 导入已接管（Running）时取消是 no-op：线程已起跑，也不该把结果页的预览读数撤掉。
        if (modImportFeed is ModImportFeedState.Running) return
        modImportFeed = ModImportFeedReducer.reduce(modImportFeed, ModImportFeedEvent.Cancelled)
        modImportState = ModImportUiReducer.reduce(
            modImportState,
            ModImportEvent.PreviewRefreshed(null),
            ModImportCopy.of(language),
        )
    }

    /**
     * 导入事务的响应链（玩家确认预览后才进来）：单包走 [ModImportController.import]（源落盘 →
     * 读 base 上下文 → 编译 → I3 预检 → 产物落 `.patch`），多选（>1 个选中文件）走
     * [ModImportController.importBatch]（逐项复用同一单包链、坏包只失败自己那一项），
     * 再回 UI 线程更新状态机。
     *
     * 为什么多选要单独一条：批导入的结果是「逐包结果 + 批级失败」，单包 [ModImportEvent.ImportSucceeded] /
     * [ModImportEvent.ImportFailed] 装不下；单包入口保持原事件语义（既有调用点与测试零影响），
     * 批量只多一个 [ModImportEvent.BatchImportFinished] 落点。
     *
     * 为什么全后台：上下文读取（mmap 装载 + 侧车解析）十几秒，UI 线程做必 ANR。
     * [copy] 与总闸状态由确认动作（UI 线程）取好传入，本段不再碰 prefs；读失败的清单
     * [readErrors] 照旧附在结果后面一起上屏。导入结束把预览喂入状态机复位成 Idle。
     */
    private fun runModImport(picked: List<PickedModFile>, readErrors: List<String>, copy: ModImportCopy) {
        if (!modImportBusy.compareAndSet(false, true)) {
            Log.w(TAG, "补丁导入请求被忽略：上一次导入还在进行")
            return
        }
        val modRuntimeOn = UiPrefs.modRuntime(this)
        // task-179：代码装载总闸的快照同拍读好传进导入线程（后台线程不碰 prefs，照 modRuntimeOn 口径）。
        val codeGateOn = UiPrefs.modDexLoaderEnabled(this)
        modImportState = ModImportUiReducer.reduce(modImportState, ModImportEvent.ImportStarted(picked.size), copy)
        Thread({
            try {
                // 入口判定与事件拼装全在 [modImportEventFor]（纯函数、可单测）：多选走批导入
                // （逐项复用单包 import(...)），单包/空选走单包链；两条都带同一发总闸快照。
                val event = modImportEventFor(
                    ModImportRequest(picked, readErrors, copy, modRuntimeOn, codeGateOn),
                    modImportController(codeGateEnabled = codeGateOn),
                    modImportReportSnapshot(copy),
                )
                postModImportEvent(event, copy)
            } catch (t: Throwable) {
                // 导入线程没有静默出口：兜底也上屏（报错页），宁可重复呈现不可吞。
                postModImportEvent(
                    ModImportEvent.ImportFailed(
                        ModImportFailureKind.UNEXPECTED,
                        copy.fill(copy.unexpectedFormat, t::class.simpleName ?: "null", t.message ?: "null"),
                        modImportReportSnapshot(copy),
                    ),
                    copy,
                )
            } finally {
                modImportBusy.set(false)
            }
        }, "eratw-modimport").start()
    }

    /**
     * 导入线程回 UI 线程的唯一落点（结束后预览喂入状态机复位：下一轮从干净盘面起）。
     *
     * ModDX-4 A+（判据 1 + 判据 3 现场核实）：导入成功即置「mod 已变更」标记 ——
     * 成功分类只有两态：单包 [ModImportEvent.ImportSucceeded]、批量
     * [ModImportEvent.BatchImportFinished] 且 [ModImportBatchOutcome.succeededCount] > 0
     * （逐包独立提交，只要有包落盘 mod 面就变了）；纯失败批不置。放在 `isFinishing` 收场
     * **之前**：玩家在导入跑完前退出设置页时事件照样落（`runOnUiThread` 不看 Activity 态），
     * 盘面已变，标记不能跟着设置页一起丢 —— 下次返回游戏（同代 MainActivity 还活着时）
     * 才提示得了。
     *
     * 防双弹核实（判据 3）：导入成功现有的唯一玩家面反馈是导入覆盖层**内部**的结果页
     * （[ModImportUiState.Succeeded] / [ModImportUiState.BatchFinished] 的逐包分行展示），
     * 全链无 Toast、无重开询问弹窗（本文件全部 Toast 点已逐一核过：卸载 / 排序 / 重试 /
     * 桥装等各处都是动作反馈，不是「重开生效」询问），与返回游戏时的这条提示分属两个
     * 时点、不重叠。
     */
    private fun postModImportEvent(event: ModImportEvent, copy: ModImportCopy) {
        runOnUiThread {
            if (event is ModImportEvent.ImportSucceeded ||
                (event is ModImportEvent.BatchImportFinished && event.outcome.succeededCount > 0)
            ) {
                UiPrefs.setModRestartPending(this)
            }
            if (isFinishing) return@runOnUiThread
            modImportFeed = ModImportFeedState.Idle
            modImportState = ModImportUiReducer.reduce(modImportState, event, copy)
        }
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
     * 「清除已导入的素材」：确认框点「清除」之后才走到这里。
     *
     * 与素材导入共用同一把单飞闸 `resourcePackBusy` —— 两条都是几百 MB 量级的文件系统写入，
     * 互相踩只会留下半套素材。真正的删除全在存储层 [ResourcePackCleanup]（逐文件判「玩家导入
     * vs 随包基准」，随包素材一个字节都不动），这里只做线程、状态机与读数刷新。
     *
     * `base` 必须给 APK assets 树：平台侧「基准源存活闸」在盘上摘要读不到时靠它证明基准清单
     * 可用；缺了它，老盘上只会得到一句「基准清单读不到」。assets 在 UI 线程取好再进后台线程
     * （与 liveReflow / failureCopy 同口径：后台线程不读宿主与偏好）。
     *
     * 结果落 [ResourcePackEvent.ClearSucceeded] / `ClearFailed`（后者复用既有失败态），
     * 上屏句子由 [cleanupEvent] 按 [ResourcePackCopy] 拼好；现状读数一律重扫一次再带回，
     * 免得清理完卡上还挂着清理前的数字。
     */
    private fun clearImportedMaterials(target: MaterialTarget) {
        val slot = if (target == MaterialTarget.BGM) PackSlot.BGM else PackSlot.BACKGROUND
        if (!resourcePackBusy.compareAndSet(false, true)) {
            Log.w(TAG, "清理请求被忽略：上一次导入或清理还在进行")
            return
        }
        val copy = ResourcePackCopy.of(language)
        val base = AndroidAssetTree(assets)
        setPackState(slot, ResourcePackUiReducer.reduce(packStateOf(slot), ResourcePackEvent.ClearStarted))
        Thread({
            try {
                val report = ResourcePackCleanup.clear(gameRootFile, target, base)
                val status = runCatching { ResourcePackStore.scanStatus(gameRootFile) }.getOrNull()
                postPackEvent(slot, cleanupEvent(copy, report, status))
            } catch (t: Throwable) {
                Log.e(TAG, "素材清理未预期异常", t)
                val status = runCatching { ResourcePackStore.scanStatus(gameRootFile) }.getOrNull()
                postPackEvent(
                    slot,
                    ResourcePackEvent.ClearFailed(
                        message = copy.fill(
                            copy.clearFailedFormat,
                            t::class.simpleName ?: copy.unknownStorageError,
                        ),
                        status = status,
                    ),
                )
            } finally {
                resourcePackBusy.set(false)
            }
        }, "eratw-respack-clear").start()
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
 * 设置页的主题入口 —— 只是 [EratwTheme] 的转接，不另建色表。
 *
 * 此前这里自带一份只有五个角色的 `darkColorScheme`：设置页里凡是没显式写颜色的 Material 控件
 * （`Switch` 轨道、`HorizontalDivider`、文本域边框）落到这份薄表上，就与游戏壳层里同名控件
 * 深浅不一。主界面那份 [EratwTheme] 已把 Material 3 的 surface 层级、outline、error 全部对齐到
 * [EratwInk]，设置页照用同一份才不会在「游戏 ↔ 设置」之间跳变。
 */
@Composable
private fun SettingsTheme(content: @Composable () -> Unit) {
    EratwTheme(content = content)
}

/**
 * 设置页的四个分区（顶栏下方的一排标签页）。
 *
 * 分档的依据是**写什么**，不是主题：[PORT] 与 [ABOUT] 里的东西随改随存、只落
 * `SharedPreferences`（`UiPrefs`）和根本不写；[ENGINE] 那一档是原版 Emuera 的
 * `emuera.config` 条目，改完必须点「保存」才生效；[MOD] 不写配置，只读写游戏根
 * 下的 Mod 状态（清单、`mods/rt2` 目录里的 `.off` 启停标记、卸载）与投放素材、
 * 补丁文件（补丁与图像类素材要重启才生效）。
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
    marketCopy: MarketCopy,
    marketEntryOverview: MarketEntryOverview?,
    onOpenMarket: () -> Unit,
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
    onClearBackground: () -> Unit,
    onClearBgm: () -> Unit,
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
    // 竖屏模式（默认关 = 锁横屏）：随改随存；重建 Activity 才换壳，返回游戏时由
    // MainActivity.onActivityResult 认这次改动并征求「现在重开」。
    var portraitMode by remember { mutableStateOf(UiPrefs.portraitMode(context)) }
    // 竖屏字号档（竖屏设计稿 §5.2：50/62/75/90，缺省 62）：与横屏档互相独立、随改随存；
    // 返回游戏时由 MainActivity.onActivityResult **按当前方向**取键判重开（竖屏比竖屏键）。
    var portraitFontColumns by remember { mutableIntStateOf(UiPrefs.fontColumnsPortrait(context)) }
    // 窗口宽窄变化时重新排版（LIVE-REFLOW，默认关）：随改随存，下一次会话装配生效
    // （与字号/列数档同口径：改完要重开一局）。
    var liveReflow by remember { mutableStateOf(UiPrefs.liveReflow(context)) }
    // 键入回显复刻（ECHO-INPUT，默认关 = 点剧本后不再多出一行编号）：随改随存，
    // 下一次会话装配生效（与 LIVE-REFLOW 同口径，改完要重开一局）。
    var echoInput by remember { mutableStateOf(UiPrefs.echoInput(context)) }
    // 指令后等一次按键（COMEND-WAIT，默认开 = 原版）：随改随存，下一次会话装配生效
    // （与 ECHO-INPUT 同口径，改完要重开一局）。
    var commandEndWait by remember { mutableStateOf(UiPrefs.commandEndWait(context)) }
    // 长页忙碌提示（PRAY-BUSY，默认开）：随改随存，下一次会话装配生效（宿主构造期定死）。
    var busyIndicator by remember { mutableStateOf(UiPrefs.busyIndicator(context)) }
    // 逐帧诊断记录（玩家取证通道，默认关）：开 = 回到游戏画面后每帧记一条版面事实，
    // 「分享诊断」时整段进报告；关 = 立刻摘掉并清空环（当场生效，不必等回游戏）。
    var frameTrace by remember { mutableStateOf(UiPrefs.frameTraceEnabled(context)) }
    // RT2 Mod 运行时总闸（默认开）：与会话构造期参数同口径，改完重开一局生效。
    var modRuntime by remember { mutableStateOf(UiPrefs.modRuntime(context)) }
    // 已安装 Mod 列表（批2-3）：列表只是盘上 `mods/rt2/` 的镜像（manifest + `.off` 标记）。
    // 2026-11-26 体感优化：首次扫描挪到 IO 线程，进页先出卡壳 / 进度，不卡设置页首帧；
    // 启停 / 卸载改为「本地先改、盘面成功再异步重扫」，开关即时响应，失败回滚。
    val modManageRoot = remember { GameStorage(context).gameRoot() }
    var modManageScan by remember { mutableStateOf(ModManageScan.Empty) }
    var modScanBusy by remember { mutableStateOf(true) }
    var modScanRequest by remember { mutableIntStateOf(0) }
    val modScanScope = rememberCoroutineScope()
    LaunchedEffect(modManageRoot, modScanRequest) {
        modScanBusy = true
        modManageScan = withContext(Dispatchers.IO) { ModManageScanner.scan(modManageRoot) }
        modScanBusy = false
    }
    // 装载失败读数（task-2）：装配链 `mod.install pkg=… active=false …` 经宿主 logSink 旁路转进
    // ModManageDiagnostics（内存态、不持久化）；设置页打开期间每 2s 拉一次快照，不碰会话对象。
    var modFailures by remember { mutableStateOf(ModManageDiagnostics.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MOD_FAILURE_POLL_MILLIS)
            val latest = ModManageDiagnostics.snapshot()
            if (latest != modFailures) modFailures = latest
        }
    }
    // 版本读数（VS-1）：作者面 API 版本只读 build-time 载体声明（不造实例，守住「关态零实例」），
    // 数据版本缺口只从装载失败原因里取（成功迁移后侧车已落成新声明值，缺口在读数时刻不可观测）。
    // 纯读数放 remember 区，不往 SettingsScreen 的 60+ 形参签名里加参。
    val modVersionRows = remember(modManageScan, modFailures) {
        buildModVersionRows(
            packageIds = modManageScan.mods.map { it.packageId },
            declaredApiVersions = ModCarrierRegistry.versionDeclarations().toMap(),
            engineApiVersion = MOD_ENGINE_API_VERSION,
            failures = modFailures,
        )
    }
    // 信任面跨卡刷新（task-165）：信任卡里确认 / 撤销落盘后递增这个计数，只读概览卡拿它当
    // remember key 重扫，撤销后同页立即回「未信任」（原先概览只 remember(context)，同页不刷新）。
    // 纯本屏局部 state，不往这张屏的 60+ 形参签名里加参。
    var modTrustRevision by remember { mutableIntStateOf(0) }
    // Mod 列表搜索 / 来源筛选（Lane modsearch）：纯视图过滤，只决定「画哪些行」；不写盘、不改
    // marker、不碰启停 / 卸载 / 重试。状态落本屏体内（同 modManageScan 先例）而不是卡内
    // remember —— 这张屏是 LazyColumn，卡滚出视口会被回收，卡内 state 会丢；也不给本屏
    // 60+ 形参加参。
    var modQuery by remember { mutableStateOf("") }
    var modSourceFilter by remember { mutableStateOf(ModManageSourceFilter.ALL) }
    val onToggleMod: (InstalledMod, Boolean) -> Unit = { mod, enabled ->
        // 启用方向先过版本门：声明版本高于引擎的 mod 装上去必被拒载（core-vm 的
        // enforceVersionGate 同判据），这里不写 marker、直接把原因给玩家；停用方向照旧写 marker
        // ——「marker 是唯一真源」，玩家随时能把不兼容的关掉（所以不禁用开关）。
        val blocked = if (enabled) modVersionBlocksEnable(modVersionRows, mod.packageId) else null
        if (blocked != null) {
            val required = blocked.declaredApiVersion ?: blocked.engineApiVersion
            val hint = text.modVersionEnableBlocked(required, blocked.engineApiVersion)
            Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
        } else {
            // 体感优化（2026-11-26）：先在本地把开关翻过去（行内即时响应），盘面写失败再回滚；
            // 成功只排一次异步重扫，点击回调里不再同步扫盘（原先每点一次都要卡一帧）。
            val before = modManageScan
            modManageScan = before.copy(
                mods = before.mods.map { item ->
                    if (item.packageId == mod.packageId) item.copy(enabled = enabled) else item
                },
            )
            val ok = ModEnablement.setEnabled(ModEnablement.rt2Dir(modManageRoot), mod.packageId, enabled)
            if (ok) {
                // ModDX-4 A+（判据 1）：marker 是唯一真源，写成功才置标记 —— 失败说明盘面没动，
                // 谈不上「已变更」（本地那格已回滚）。生效口径 ModEnablement.kt:13-14：装配在会话
                // 构造期一次性完成，当前局仍跑旧装配；返回游戏时 MainActivity 消费标记弹询问。
                UiPrefs.setModRestartPending(context)
                modScanRequest += 1
            } else {
                modManageScan = before
                Toast.makeText(context, text.modManageToggleFailed, Toast.LENGTH_LONG).show()
            }
        }
    }
    // RT2 玩家排序配置（逗号分隔 packageId 原始串）：随改随存，下一次会话装配生效。
    // 读的是原始串（玩家输入原样回填输入框），归一化在 UiPrefs.preferredPackageIds 构造期做。
    var modPackageOrder by remember { mutableStateOf(UiPrefs.preferredPackageIdsRaw(context)) }
    // 卸载（task-2 ③）：删盘上三件套 + 停用标记，成功重扫并清掉该包的旧失败读数。
    // 回收（task-134 ③ / task-199）：卸载成功即把**运行期身份**投进「设置 → 会话」数据队列，由
    // 运行中会话的 VM 页边界 drain 后调 `storage.release`（内存容器摘段）—— storage 段按运行期
    // 身份命名，投磁盘身份只会空转；持久台账由 ModManageUninstall 内部写（那份按磁盘身份，抓取侧
    // 据它让段减在下次存档）。身份必须在删成员**之前**解析：`.codeid` 侧车本身是包的成员，卸载会删掉它。
    val onUninstallMod: (InstalledMod) -> Unit = { mod ->
        // 体感优化（2026-11-26）：确认框一关，列表先摘掉这一行；删盘与重扫都挪到 IO，
        // 失败再把行放回去（不阻塞主线程，也不让玩家盯着不动的列表等文件删除）。
        modManageScan = modManageScan.copy(
            mods = modManageScan.mods.filterNot { it.packageId == mod.packageId },
        )
        modScanScope.launch {
            val removed = withContext(Dispatchers.IO) {
                uninstallModAndQueueReclaim(ModEnablement.rt2Dir(modManageRoot), mod.packageId)
            }
            if (removed) {
                ModManageDiagnostics.forget(mod.packageId)
                modScanRequest += 1
                Toast.makeText(context, text.modManageUninstallDone(mod.title), Toast.LENGTH_SHORT).show()
            } else {
                modManageScan = modManageScan.copy(mods = modManageScan.mods + mod)
                Toast.makeText(context, text.modManageUninstallFailed(mod.title), Toast.LENGTH_LONG).show()
            }
        }
    }
    // 回滚态重装（Lane DZ）：把请求投进「设置 → 会话」数据队列，由 GameHost 在 VM 线程
    // 页边界 drain 后走 host.install 的重装槽。设置页拿不到会话引用，也不许在 UI 线程
    // 直接调 mod 生命周期；队列只存 packageId（口径见 ModReinstallRequests 类头）。
    // 结果不回流：失败/成功都由会话打 canonical `mod.install` 行 → ModManageDiagnostics
    // 的 2s 轮询自然刷新失败行（成功即清、仍失败换新原因）。
    val onRetryModInstall: (InstalledMod) -> Unit = { mod ->
        ModReinstallRequests.request(mod.packageId)
        Toast.makeText(context, text.modManageRetryQueued, Toast.LENGTH_SHORT).show()
    }
    // 启动画面样式（玩家面加载页外观）：随改随存，下一次启动生效
    var loadingStyle by remember { mutableStateOf(UiPrefs.loadingStyle(context)) }
    // 底边抓手位置（输入栏手势条）
    var knobAt by remember { mutableFloatStateOf(UiPrefs.inputKnobFraction(context)) }
    // 画面左右留白居中档位（圆屏适配）：会话构造期参数，随改随存、改完重开一局生效。
    var contentMargin by remember { mutableFloatStateOf(UiPrefs.contentMarginFraction(context)) }
    // 右侧选项栏按钮大小档（随改随存；纯壳层几何，不必重开游戏）
    var railButtonSize by remember { mutableStateOf(UiPrefs.railButtonSize(context)) }
    // 右栏布局模式 + 每行最多按钮数（随改随存；纯壳层摆法，不必重开游戏）
    var railLayout by remember { mutableStateOf(UiPrefs.railLayout(context)) }
    var railChipsPerRow by remember { mutableIntStateOf(UiPrefs.railChipsPerRow(context)) }
    // 右栏过滤（规则开关 + 隐藏名单）与常用置顶（名单 + 历史候选）：随改随存
    var railHideUnnumbered by remember { mutableStateOf(UiPrefs.railHideUnnumbered(context)) }
    var railHiddenLabels by remember { mutableStateOf(UiPrefs.railHiddenLabels(context)) }
    var railPinned by remember { mutableStateOf(UiPrefs.railPinned(context)) }
    var railHistory by remember { mutableStateOf(UiPrefs.railHistory(context)) }
    // 快捷宏（2026-11-11）：设置页里增删改，游戏壳常用区按它渲染。
    var quickMacros by remember { mutableStateOf(UiPrefs.quickMacros(context)) }
    // 标题彩蛋屏蔽（引擎级 EngineFunctionPolicy 的宿主开关）
    var disableBadApple by remember { mutableStateOf(UiPrefs.disableBadApple(context)) }
    // 自动更新开关（国内镜像仓通道）：随改随存，下一次检查生效。
    var autoUpdate by remember { mutableStateOf(UiPrefs.autoUpdateEnabled(context)) }
    // 仅 Wi-Fi 下载（U5 P2-10）：默认关，随改随存，偏好键在 UiPrefs。
    var wifiOnly by remember { mutableStateOf(UiPrefs.wifiOnlyDownload(context)) }
    // 匿名玩家数统计：默认开、可在关于档关；随改随存，冷启接线读的就是这个键。
    var playerStats by remember { mutableStateOf(UiPrefs.playerStatsEnabled(context)) }
    // 教程重阅（关于档入口）：首启那次由 MainActivity 弹并落「已读」，这里只复用弹窗。
    var tutorialVisible by remember { mutableStateOf(false) }
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
                                // 存档导入/导出是玩家进设置页最常做的事（换机导档、备份），
                                // 放在本档最前；两张卡不占满行，宽布局下并排，窄布局上下叠。
                                item(key = "save-group-header", span = StaggeredGridItemSpan.FullLine) {
                                    GroupHeader(title = text.saveGroupTitle, subtitle = text.saveGroupSubtitle)
                                }
                                item(key = "save-import") {
                                    SaveImportCard(
                                        text = text,
                                        busy = importing || exporting,
                                        importing = importing,
                                        lines = importLines,
                                        onPickSaveFiles = onPickSaveFiles,
                                    )
                                }
                                item(key = "save-export") {
                                    SaveExportCard(
                                        text = text,
                                        busy = importing || exporting,
                                        exporting = exporting,
                                        lines = exportLines,
                                        onExport = onExport,
                                    )
                                }
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
                                // 应用偏好内再分三组（2026-11-26 信息架构收口）：字号/画面、右栏/快捷、启动/维护。
                                // 12 张卡一字排开时玩家要滚两三屏才找得到下一类设置，分组标题就是「目录」。
                                item(key = "display-group-header", span = StaggeredGridItemSpan.FullLine) {
                                    GroupHeader(
                                        title = text.displayGroupTitle,
                                        subtitle = text.displayGroupSubtitle,
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
                                // 竖屏字号（竖屏三区壳 §5.2 四档）：独立卡片，复用既有共用件，
                                // 不改 ControlCard / FontCard 的形参表（两张都已进 detekt baseline）。
                                item(key = "ui-portrait-font") {
                                    PortraitFontCard(
                                        text = text,
                                        columns = portraitFontColumns,
                                        onColumns = { columns ->
                                            portraitFontColumns = columns
                                            UiPrefs.setFontColumnsPortrait(context, columns)
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
                                        railLayout = railLayout,
                                        onRailLayout = { mode ->
                                            railLayout = mode
                                            UiPrefs.setRailLayout(context, mode)
                                        },
                                        railChipsPerRow = railChipsPerRow,
                                        onRailChipsPerRow = { count ->
                                            railChipsPerRow = count
                                            UiPrefs.setRailChipsPerRow(context, count)
                                        },
                                        screenFit = screenFit,
                                        onScreenFit = { enabled ->
                                            screenFit = enabled
                                            UiPrefs.setScreenFit(context, enabled)
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
                                        commandEndWait = commandEndWait,
                                        onCommandEndWait = { enabled ->
                                            commandEndWait = enabled
                                            UiPrefs.setCommandEndWait(context, enabled)
                                        },
                                        busyIndicator = busyIndicator,
                                        onBusyIndicator = { enabled ->
                                            busyIndicator = enabled
                                            UiPrefs.setBusyIndicator(context, enabled)
                                        },
                                        modRuntime = modRuntime,
                                        onModRuntime = { enabled ->
                                            modRuntime = enabled
                                            UiPrefs.setModRuntime(context, enabled)
                                        },
                                        knobAt = knobAt,
                                        onKnobAt = { f ->
                                            knobAt = f
                                            UiPrefs.setInputKnobFraction(context, f)
                                        },
                                    )
                                }
                                // 画面左右留白居中（圆屏适配）：独立卡片，复用既有共用件，不改 ControlCard 形参。
                                item(key = "ui-content-margin") {
                                    ContentMarginCard(
                                        text = text,
                                        contentMargin = contentMargin,
                                        onContentMargin = { fraction ->
                                            contentMargin = fraction
                                            UiPrefs.setContentMarginFraction(context, fraction)
                                        },
                                    )
                                }
                                item(key = "rail-group-header", span = StaggeredGridItemSpan.FullLine) {
                                    GroupHeader(
                                        title = text.railGroupTitle,
                                        subtitle = text.railGroupSubtitle,
                                    )
                                }
                                item(key = "ui-rail-filter") {
                                    RailFilterCard(
                                        text = text,
                                        hideUnnumbered = railHideUnnumbered,
                                        actions = RailFilterActions(
                                            onHideUnnumbered = { enabled ->
                                                railHideUnnumbered = enabled
                                                UiPrefs.setRailHideUnnumbered(context, enabled)
                                            },
                                            onRestore = { label ->
                                                UiPrefs.removeRailHiddenLabel(context, label)
                                                railHiddenLabels = UiPrefs.railHiddenLabels(context)
                                            },
                                            onClearHidden = {
                                                UiPrefs.clearRailHiddenLabels(context)
                                                railHiddenLabels = UiPrefs.railHiddenLabels(context)
                                            },
                                        ),
                                        hiddenLabels = railHiddenLabels,
                                    )
                                }
                                item(key = "ui-rail-pinned") {
                                    RailPinnedCard(
                                        text = text,
                                        pinned = railPinned,
                                        history = railHistory,
                                        pinnedCap = UiPrefs.RAIL_PINNED_CAP,
                                        actions = RailPinnedActions(
                                            onUnpin = { value ->
                                                UiPrefs.unpinRailOption(context, value)
                                                railPinned = UiPrefs.railPinned(context)
                                            },
                                            onPin = { value ->
                                                UiPrefs.pinRailOption(context, value)
                                                railPinned = UiPrefs.railPinned(context)
                                            },
                                            onClearPinned = {
                                                UiPrefs.clearRailPinned(context)
                                                railPinned = UiPrefs.railPinned(context)
                                            },
                                        ),
                                    )
                                }
                                item(key = "ui-quick-macro") {
                                    RailQuickMacroCard(
                                        text = text,
                                        macros = quickMacros,
                                        // 步骤候选 = 最近见过的带编号指令（与「常用置顶」共用同一份历史）。
                                        recent = railHistory,
                                        onAdd = { macro ->
                                            val ok = UiPrefs.addQuickMacro(context, macro)
                                            quickMacros = UiPrefs.quickMacros(context)
                                            ok
                                        },
                                        onUpdate = { index, macro ->
                                            UiPrefs.updateQuickMacro(context, index, macro)
                                            quickMacros = UiPrefs.quickMacros(context)
                                        },
                                        onDelete = { index ->
                                            UiPrefs.removeQuickMacro(context, index)
                                            quickMacros = UiPrefs.quickMacros(context)
                                        },
                                        onClear = {
                                            UiPrefs.clearQuickMacros(context)
                                            quickMacros = UiPrefs.quickMacros(context)
                                        },
                                    )
                                }
                                item(key = "launch-group-header", span = StaggeredGridItemSpan.FullLine) {
                                    GroupHeader(
                                        title = text.launchGroupTitle,
                                        subtitle = text.launchGroupSubtitle,
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
                                        stagedApkPath = updateReadout.stagedApkPath,
                                        onInstallStagedApk = {
                                            val apk = UpdateManager.handedOffApk(context)
                                            if (apk != null) {
                                                val problem = UpdateManager.launchInstaller(context, apk)
                                                // 29+ 会话提交返回 SESSION_SUBMITTED_SIGNAL（不是 problem，
                                                // 回执在会话侧：成功多半收不到，取消/失败各自有通知+Toast）；
                                                // 只有真 problem（最常见「安装未知应用」未放行）才上屏。
                                                val isRealProblem =
                                                    problem != null && problem != UpdateManager.SESSION_SUBMITTED_SIGNAL
                                                if (isRealProblem) {
                                                    Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
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
                                        frameTrace = FrameTraceSwitch(
                                            checked = frameTrace,
                                            onCheckedChange = { enabled ->
                                                frameTrace = enabled
                                                UiPrefs.setFrameTraceEnabled(context, enabled)
                                                // 关档当场摘掉并清空环（报告里立刻变回「未开启」）；
                                                // 开档由游戏画面回前台时挂上（FrameTraceChannel.sync），
                                                // 本页拿不到那个 View，也不该伸手去碰它。
                                                if (!enabled) FrameTraceChannel.detach()
                                            },
                                        ),
                                        actions = DiagActions(
                                            onShare = onShareDiagnostics,
                                            onCopy = onCopyDiagnostics,
                                        ),
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

                                // 已安装 Mod（2026-11-26 从「移植版」档整体迁入本档）：玩家进 Mod
                                // 档先看「装了什么、哪些没生效」，再谈导入与素材。清单只是盘上
                                // `mods/rt2/` 的镜像（manifest + `.off` 标记），进页扫一次、每次切换
                                // 开关后重扫；界面不做乐观更新，失败只弹提示。紧随其后的运行读数 /
                                // 诊断 / 信任 / 代码包 / 加载顺序共用同一次扫描，保证「列表 N / 摘要 M」
                                // 不分叉。RT2-GATE-OPEN 起卡尾还有只读诊断摘要 + 复制（读数与
                                // `DevToolsHost.modgate` 同源 [ModGateDiagnostics]）。
                                item(key = "ui-mod-manage") {
                                    ModManageCard(
                                        text = text,
                                        scan = modManageScan,
                                        readouts = ModManageReadouts(
                                            failures = modFailures,
                                            versions = modVersionRows,
                                            scanning = modScanBusy,
                                        ),
                                        filter = ModManageFilterUi(
                                            query = modQuery,
                                            sourceFilter = modSourceFilter,
                                            onQueryChange = { modQuery = it },
                                            onSourceFilterChange = { modSourceFilter = it },
                                        ),
                                        actions = ModManageActions(
                                            onToggle = onToggleMod,
                                            onUninstall = onUninstallMod,
                                            onRetryInstall = onRetryModInstall,
                                            recompileCopy = modImportCopy,
                                            uninstallImpact = { mod ->
                                                buildUninstallImpactLines(
                                                    text = text,
                                                    rt2Dir = ModEnablement.rt2Dir(modManageRoot),
                                                    packageId = mod.packageId,
                                                    dexLoaderEnabled = UiPrefs.modDexLoaderEnabled(context),
                                                )
                                            },
                                            codeState = { mod ->
                                                modCodePackState(
                                                    rt2Dir = ModEnablement.rt2Dir(modManageRoot),
                                                    packageId = mod.packageId,
                                                    dexLoaderEnabled = UiPrefs.modDexLoaderEnabled(context),
                                                    // task-195：引擎太旧只有装载结论知道，盘面读不出。
                                                    loadReport = ModCodeLoadReports.last,
                                                )
                                            },
                                        ),
                                    )
                                }
                                // Mod 运行时概览（Lane AW）：纯只读，每个已装 Mod 一行——装载失败
                                // 原因 / 累计耗时 top 3 作用域 / 慢处理器次数。账本为 null（总闸关 /
                                // 手工装配）时卡片自己整块不渲染；刷新沿用本页既有重组时机，不新增
                                // 定时器。渲染与聚合都在 ModRuntimeOverview*.kt，本处只接线。
                                // 紧贴清单：玩家「点开关」之后第一时间看到的就是它的运行读数。
                                item(key = "ui-mod-runtime-overview") {
                                    ModRuntimeOverviewCard(
                                        text = text,
                                        scan = modManageScan,
                                        failures = modFailures,
                                    )
                                }
                                // Mod 运行时只读诊断（RT2-GATE-OPEN）：release 玩家面也能取的读数，
                                // 与 debug 包 `modgate` 同源 [ModGateDiagnostics]，且与上方列表同一次
                                // 扫描（防「列表 N / 摘要 M」分叉）。独立成卡而非塞进列表卡尾：诊断读数
                                // 不属于「已安装的 Mod」这一件事，混在一起会同时撑爆卡的形参表与行长。
                                item(key = "ui-mod-diagnostics") {
                                    val diagnostics = remember(modManageScan, modRuntime) {
                                        ModGateDiagnostics.snapshot(context, modManageScan)
                                    }
                                    ModDiagnosticsCard(
                                        text = text,
                                        diagnostics = diagnostics,
                                        onCopy = { copyModDiagnostics(context, text, diagnostics) },
                                    )
                                }
                                // 诊断快照导出（task-172）：只读纯文本，把「装了啥 / 哪一步失败 /
                                // 两把总闸」整段带走贴给作者。拼装是纯函数（[ModDiagnosticReport]），
                                // 本屏只负责取数与弹窗壳；不新增 SettingsScreen 形参、不改
                                // [ModManageCard] 的 5 参签名。接在诊断卡之后：先看得懂，再带走。
                                item(key = "ui-mod-diagnostic-export") {
                                    ModDiagnosticExportCard(
                                        text = text,
                                        scan = modManageScan,
                                        failures = modFailures,
                                    )
                                }

                                // 判词 9（PDIAG1）：玩家显式授权的只读诊断通道。缺省关 ⇒ 正式包里连
                                // DevTools 的轮询线程都不建；开档只放开 25 条只读观测命令，写侧
                                // （改档 / 注入按键 / 改运行时行为）与未归类命令一律 fail-closed
                                // （分区与拒绝在 [DevToolsHost] 里）。卡片自装配：不给 SettingsScreen
                                // 那 60+ 形参加参，照上方 ModDiagnosticExportCard 的范式。
                                item(key = "ui-mod-player-diagnostics") {
                                    PlayerDiagnosticsCard(text)
                                }
                                // 第三方代码包信任确认（ADR-013 D3 / task-153 S4b）：独立自装配卡，
                                // 数据面走 [ModTrustStore]，状态全在卡内 remember，不改本屏形参签名。
                                // task-165：确认 / 撤销落盘后经 onTrustChanged 递增本屏局部 revision，
                                // 让下方只读概览卡同页重扫（信任态即时翻转）。
                                item(key = "ui-mod-trust") {
                                    ModTrustCard(
                                        text = text,
                                        onTrustChanged = { modTrustRevision += 1 },
                                    )
                                }
                                // 第三方代码包只读概览（ADR-013 路 B 玩家可见面 / task-162）：总闸状态
                                // + 每包版本数 / 体积 / 信任态。纯只读、卡内 remember 自装配，不改本屏
                                // 那 60+ 个形参；装载 / 信任变更仍归上方两张卡，本卡没有任何按钮。
                                // [trustRevision] 变化即重扫，撤销后该包立即显示「未信任」。
                                item(key = "ui-mod-code-overview") {
                                    ModCodeOverviewCard(text = text, trustRevision = modTrustRevision)
                                }
                                // RT2 玩家排序配置（L2）：独立卡片，复用既有共用件，不改 ControlCard 形参。
                                item(key = "ui-mod-package-order") {
                                    ModPackageOrderCard(
                                        text = text,
                                        value = modPackageOrder,
                                        installed = modManageScan.mods,
                                        onValueChange = { raw ->
                                            modPackageOrder = raw
                                            UiPrefs.setPreferredPackageIds(context, raw)
                                        },
                                    )
                                }

                                // 导入与清理都要禁掉同一批按钮：单飞闸在 resourcePackBusy，
                                // 这里只管视觉（多选一批素材时不许同时点清理）。
                                val materialBusy = backgroundPackState.isPackBusy ||
                                    bgmPackState.isPackBusy ||
                                    archivePackState.isPackBusy
                                item(key = "resource-pack-background", span = StaggeredGridItemSpan.FullLine) {
                                    ResourcePackBackgroundCard(
                                        state = backgroundPackState,
                                        busy = materialBusy,
                                        text = text,
                                        copy = resourcePackCopy,
                                        onPickFiles = onPickBackgroundFiles,
                                        onPickFolder = onPickBackgroundFolder,
                                        onClear = onClearBackground,
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
                                        onClear = onClearBgm,
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
                                // 口上包管理卡（KI-6）：只列导入的口上包（启停/卸载/覆盖关系展示），
                                // 与 mod-import 卡同档相邻（主人指令：口上导入与 Mod/素材导入分开管理）。
                                // 自装配卡，卡内自己扫描与重扫，不加本屏形参。
                                item(key = "kojo-manage", span = StaggeredGridItemSpan.FullLine) {
                                    KojoManageCard(text = text)
                                }
                                item(key = "market-entry", span = StaggeredGridItemSpan.FullLine) {
                                    MarketEntryCard(
                                        onOpenMarket = onOpenMarket,
                                        copy = marketCopy,
                                        overview = marketEntryOverview,
                                    )
                                }
                                item(key = "plugin-policy", span = StaggeredGridItemSpan.FullLine) {
                                    PluginPolicyCard(text = text)
                                }
                            }

                            SettingsZone.ABOUT -> {
                                item(key = "about-header", span = StaggeredGridItemSpan.FullLine) {
                                    GroupHeader(title = text.zoneAboutTab, subtitle = text.aboutGroupSubtitle)
                                }
                                item(key = "about", span = StaggeredGridItemSpan.FullLine) {
                                    AboutCard(text = text)
                                }
                                // 匿名统计开关从启动卡迁到关于档（2026-11-09）：它是「这个应用
                                // 拿我的数据做什么」的知情同意项，和版本/署名/反馈同档才好找。
                                item(key = "player-stats", span = StaggeredGridItemSpan.FullLine) {
                                    PlayerStatsCard(
                                        text = text,
                                        checked = playerStats,
                                        onCheckedChange = { enabled ->
                                            playerStats = enabled
                                            UiPrefs.setPlayerStatsEnabled(context, enabled)
                                        },
                                    )
                                }
                                item(key = "tutorial-revisit", span = StaggeredGridItemSpan.FullLine) {
                                    TutorialRevisitCard(
                                        language = language,
                                        onOpen = { tutorialVisible = true },
                                    )
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
        // 内容根末尾：mod 贡献项浮层（与 MainActivity.kt:989 那一行是同一件 `ModUiOverlay`，
        // 读数口走设置页侧通道 `ModUiSettingsEntry`，见该件类头）。不新增形参 —— 语言复用
        // 本函数已有的 `language`；读数口没接线/会话没装配时 provider 返回 null，整块不渲染。
        // 页边界：本行只读表面 + 点击入队，兑现唯一在 VM 线程页边界（ModRuntimeAssembly.kt:1298、
        // :1357-1360），设置页的 onPause/onStop **不新增任何动作**（照 MainActivity 现状：
        // 它的生命周期里也没有任何 mod 相关行）。
        ModUiSettingsOverlay(language = language)
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
        ShellAlertDialog(
            title = text.restoreDefaultsConfirmTitle,
            onDismissRequest = { confirmRestore = false },
            actions = ShellDialogActions(
                confirmLabel = text.restoreDefaultsConfirmOk,
                onConfirm = {
                    confirmRestore = false
                    onRestoreDefaults()
                },
                confirmTone = EratwInk.warn(),
                dismissLabel = text.cancelText,
                onDismiss = { confirmRestore = false },
            ),
        ) {
            Text(text = text.restoreDefaultsConfirmBody, color = EratwInk.text(), style = ShellType.Body)
        }
    }

    // 目标格里已经有玩家的东西 ⇒ 只有玩家自己能决定要不要盖掉。
    if (overwritePending.isNotEmpty()) {
        ShellAlertDialog(
            title = text.overwriteTitle,
            onDismissRequest = onOverwriteCancel,
            actions = ShellDialogActions(
                confirmLabel = text.overwriteConfirm(overwritePending.size),
                onConfirm = onOverwriteConfirm,
                dismissLabel = text.cancelText,
                onDismiss = onOverwriteCancel,
            ),
        ) {
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
                    color = EratwInk.text(),
                    style = ShellType.Caption,
                )
            }
        }
    }

    // 教程重阅：与首启那次同一只像素指南弹窗；这里不落「已读」（首启时早已落过）。
    // immersive=false：设置页系统栏可见（这是页面语义的一部分），弹窗窗口只做
    // edge-to-edge 几何装配（挖孔延伸 + MATCH_PARENT，防「右侧截断」），不藏栏。
    if (tutorialVisible) {
        TutorialDialog(
            copy = TutorialCopy.of(LocalContext.current, language),
            onDismiss = { tutorialVisible = false },
            immersive = false,
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
                    // 紧凑档里只剩一个箭头，放大到标题档才点得准、看得清。
                    style = if (actionsInTopBar) ShellType.Title else ShellType.Body,
                )
            }
            Text(
                text = text.title,
                color = UiColors.Text,
                style = if (actionsInTopBar) TOP_BAR_TITLE_COMPACT else TOP_BAR_TITLE,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onResetAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    text = if (actionsInTopBar) text.reset else text.resetAll,
                    color = UiColors.Accent,
                    style = ShellType.Tab,
                    maxLines = 1,
                )
            }
            if (actionsInTopBar && saveActionsVisible) {
                OutlinedButton(
                    onClick = onSaveAndRestart,
                    modifier = Modifier.height(36.dp),
                    shape = ShellShape.Chip,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(text.saveAndRestart, style = ShellType.Caption, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSave,
                    modifier = Modifier.height(36.dp),
                    shape = ShellShape.Chip,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UiColors.PrimaryButton,
                        contentColor = UiColors.Text,
                    ),
                ) {
                    Text(text.save, style = ShellType.Tab, maxLines = 1)
                }
            }
        }
        Text(
            text = text.filePrefix + filePath + if (dirty) text.unsavedSuffix else "",
            color = if (dirty) UiColors.Warn else UiColors.TextMuted,
            style = ShellType.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        HorizontalDivider(color = UiColors.Border)
    }
}

private val SEGMENT_CONTAINER_PADDING = PaddingValues(3.dp)
private val SEGMENT_GAP = 3.dp
private const val SEGMENT_FILL_ALPHA = 0.16f

/**
 * 分区标签页：移植版功能 / 原版引擎 / Mod / 关于。
 *
 * 钉在顶栏之下、列表之上，滚动时不跟着走 —— 玩家在 100 多条引擎项里翻到一半，抬头还能
 * 换档，不必先滚回页顶。每一档用自己的主题色；卡片色带和卡片标题取同一个色，所以
 * 「现在在哪一档」在页面上任何一处都读得出来。
 *
 * 2026-11-26 换成 Material 的**分段控形态**：所有档装进同一个共享容器（发丝描边 + Chip 圆角），
 * 选中的那一档亮底 + 同色描边，未选中透明 —— 旧版每档各自一块浮底，四个档看着像四颗互不相
 * 干的按钮，扫不出「这是一个分段选择器」。容器内边距 3dp，让选中块与容器边留一线，避免贴边
 * 发黑；换档仍是 200ms 颜色补间（走 `ShellMotion.content()`），不做硬切。
 */
@Composable
private fun ZoneTabs(zone: SettingsZone, onZone: (SettingsZone) -> Unit, text: SettingsStrings) {
    Column(modifier = Modifier.fillMaxWidth().background(UiColors.CardAlt)) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .clip(ShellShape.Chip)
                .border(1.dp, UiColors.Border, ShellShape.Chip)
                .padding(SEGMENT_CONTAINER_PADDING),
            horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsZone.entries.forEach { option ->
                val selected = option == zone
                val tabColor by animateColorAsState(
                    targetValue = if (selected) option.accent else UiColors.TextDim,
                    animationSpec = ShellMotion.content(),
                    label = "zone-tab-color",
                )
                val tabBg by animateColorAsState(
                    targetValue = if (selected) option.accent.copy(alpha = SEGMENT_FILL_ALPHA) else Color.Transparent,
                    animationSpec = ShellMotion.content(),
                    label = "zone-tab-bg",
                )
                val tabBorder by animateColorAsState(
                    targetValue = if (selected) option.accent else Color.Transparent,
                    animationSpec = ShellMotion.content(),
                    label = "zone-tab-border",
                )
                Text(
                    text = text.zoneTabLabel(option),
                    color = tabColor,
                    style = ShellType.Tab,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(ShellShape.Chip)
                        .background(tabBg)
                        .border(1.dp, tabBorder, ShellShape.Chip)
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
            Text(it, color = UiColors.Warn, style = ShellType.Caption)
        }
        clampNotices.forEach {
            Text(it, color = UiColors.Warn, style = ShellType.Caption)
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
        Text(text.languageHint, color = UiColors.TextMuted, style = ShellType.Label)
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
            style = ShellType.Label,
        )
        // MODDX4-D3 组长接线：装了 mod 的存档带 .rt2 扩展数据，PC 端读不到扩展部分
        // （口径 = specs/save-format.md §11；未装 mod 互通不受影响）。
        Text(
            text = text.saveModExportHint,
            color = UiColors.TextMuted,
            style = ShellType.Label,
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onExport,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            shape = ShellShape.Inset,
            colors = ButtonDefaults.buttonColors(
                containerColor = UiColors.PrimaryButton,
                contentColor = UiColors.Text,
            ),
        ) {
            Text(if (exporting) text.exportBusy else text.exportButton, style = ShellType.Body)
        }
        lines.forEach { line ->
            Text(
                text = line.text,
                color = if (line.ok) UiColors.TextDim else UiColors.Warn,
                style = ShellType.Caption,
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
            style = ShellType.Label,
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onOpenModImport,
            // 进行中不放行第二次（与覆盖层内「选择口上文件」按钮同一判据）。
            enabled = state !is ModImportUiState.Importing,
            modifier = Modifier.height(40.dp),
            shape = ShellShape.Chip,
            colors = ButtonDefaults.buttonColors(
                containerColor = UiColors.PrimaryButton,
                contentColor = UiColors.Text,
            ),
        ) {
            Text(copy.cardOpenButton, style = ShellType.Body)
        }
        val summary = modImportCardSummary(state, copy)
        if (summary != null) {
            Text(
                text = summary,
                color = if (state is ModImportUiState.Failed) UiColors.Warn else UiColors.TextDim,
                style = ShellType.Caption,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 入口卡概览读数形状（CONTRACT-MKM-STATS-UI §2；与卡面三格一一对应）。
 * packCount / syncedAtMs 索引缺席时为 null（「尚未同步」，不演假数）；文件顶层声明——
 * 宿主（产生）与 [SettingsScreen]/[MarketEntryCard]（渲染）跨同文件顶层共享。
 */
private data class MarketEntryOverview(
    val packCount: Int?,
    val installedCount: Int,
    val installedBytes: Long,
    val favoriteCount: Int,
    val syncedAtMs: Long?,
)

/**
 * 市场入口功能卡（MKM-W1 建卡，CONTRACT-MKM-STATS-UI §2 升级）：在补丁导入卡与插件策略卡
 * 之间。概览三格（包数 / 已装+占用 / 收藏数）+ 同步行 + 打开按钮；浏览 / 详情 / 安装 /
 * 已装管理 / 端点编辑的完整形态在 [MarketChrome] 覆盖层里，点「打开市场」弹出。
 * 文案全部来自 [MarketCopy]，本卡不写字面量；概览读数由宿主后台盘读后下传（[overview]，
 * null = 还没读出来，统计格演「—」不演假零）。
 */
@Composable
private fun MarketEntryCard(
    onOpenMarket: () -> Unit,
    copy: MarketCopy,
    overview: MarketEntryOverview?,
) {
    SettingsCard {
        SectionTitle(copy.cardTitle)
        Text(
            text = copy.subtitle,
            color = UiColors.TextMuted,
            style = ShellType.Label,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(10.dp))
        // 概览三格：数字大一号、标签小一号（应用商店入口卡惯例）；三格等宽平铺，
        // 分隔靠间隔色不画竖线（与设置页其余卡片的轻分隔口径一致）。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarketEntryStatCell(
                value = overview?.packCount?.toString() ?: "—",
                label = copy.cardPacksLabel,
                modifier = Modifier.weight(1f),
            )
            MarketEntryStatCell(
                value = overview?.installedCount?.toString() ?: "—",
                label = copy.installedTitle,
                sub = overview?.let { MarketReadouts.formatBytes(it.installedBytes) },
                modifier = Modifier.weight(1f),
            )
            MarketEntryStatCell(
                value = overview?.favoriteCount?.toString() ?: "—",
                label = copy.filterFavorites,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        // 同步行：索引缺席 = 尚未同步（不演假时间）；有数据 = 上次同步时刻（市场读数同源）。
        Text(
            text = when (val syncedAt = overview?.syncedAtMs) {
                null -> copy.cardNotSynced
                else -> copy.fill(copy.syncTimeFormat, MarketReadouts.formatClock(syncedAt))
            },
            color = UiColors.TextMuted,
            style = ShellType.Label,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onOpenMarket,
            modifier = Modifier.height(40.dp),
            shape = ShellShape.Chip,
            colors = ButtonDefaults.buttonColors(
                containerColor = UiColors.PrimaryButton,
                contentColor = UiColors.Text,
            ),
        ) {
            Text(copy.cardOpenButton, style = ShellType.Body)
        }
    }
}

/** 概览统计格：主数值 + 标签 + 可选副读数（占用体积）；纯渲染，零判定。 */
@Composable
private fun MarketEntryStatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    Column(
        modifier = modifier
            .clip(ShellShape.Chip)
            .background(UiColors.CardAlt)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(text = value, color = UiColors.Text, style = ShellType.Figure)
        Text(text = label, color = UiColors.TextMuted, style = ShellType.Label)
        sub?.let {
            Text(text = it, color = UiColors.TextDim, style = ShellType.Overline)
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
    onClear: () -> Unit,
    onRestart: () -> Unit,
) {
    MaterialImportCard(
        title = copy.backgroundTitle,
        intro = copy.backgroundIntro,
    ) {
        val status = state.status
        if (status == null) {
            Text(text = text.resourcePackStatusUnavailable, color = UiColors.Warn, style = ShellType.Caption)
        } else {
            Text(
                text = text.resourcePackBackground(status.backgroundImages, status.backgroundIndexRows),
                color = if (status.backgroundReady) UiColors.TextDim else UiColors.TextMuted,
                style = ShellType.Caption,
            )
            // 导入成功 ≠ 游戏内已启用：4.981 的背景图 / 音乐都是 DLC 开关，默认关。
            // 刚导完的结果区会把启用步骤再顶一次，这里就不再重复。
            if (status.backgroundReady && !state.showsEnableStepsInOutcome) {
                Text(
                    text = copy.backgroundEnabledHint,
                    color = UiColors.TextMuted,
                    style = ShellType.Label,
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
        MaterialClearButton(
            text = if (state is ResourcePackUiState.Clearing) copy.clearRunningText else copy.clearBackgroundButton,
            enabled = !busy,
            onClick = onClear,
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
    onClear: () -> Unit,
    onRestart: () -> Unit,
) {
    MaterialImportCard(
        title = copy.bgmTitle,
        intro = copy.bgmIntro,
    ) {
        val status = state.status
        if (status == null) {
            Text(text = text.resourcePackStatusUnavailable, color = UiColors.Warn, style = ShellType.Caption)
        } else {
            Text(
                text = text.resourcePackBgm(status.bgmTracks, status.bgmFolders),
                color = if (status.bgmReady) UiColors.TextDim else UiColors.TextMuted,
                style = ShellType.Caption,
            )
            // 官方 BGM 包只有 上午/下午/晚上 子目录，不切差分模式同样没声音。
            // 刚导完的结果区会把启用步骤再顶一次，这里就不再重复。
            if (status.bgmReady && !state.showsEnableStepsInOutcome) {
                Text(
                    text = copy.bgmEnabledHint,
                    color = UiColors.TextMuted,
                    style = ShellType.Label,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = location,
            onValueChange = onLocationChange,
            singleLine = true,
            label = { Text(copy.bgmLocationLabel, style = ShellType.Caption) },
            // 与快捷宏编辑器的手输框同一份配色：缺省那套取系统主题 primary，实机上是默认紫。
            textStyle = ShellType.Body,
            colors = ShellField.outlined(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Text(
            text = copy.bgmLocationHint,
            color = UiColors.TextMuted,
            style = ShellType.Label,
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
        MaterialClearButton(
            text = if (state is ResourcePackUiState.Clearing) copy.clearRunningText else copy.clearBgmButton,
            enabled = !busy,
            onClick = onClear,
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

/**
 * 素材卡忙碌判据：导入与清理都要禁掉同一批按钮。
 *
 * 单飞闸本身在 Activity 的 resourcePackBusy（导入与清理共用同一个），这里只决定按钮的可用性；
 * 少算清理态会让玩家在删除进行中再点一次导入，两批文件系统写入互相踩。
 */
private val ResourcePackUiState.isPackBusy: Boolean
    get() = this is ResourcePackUiState.Importing || this is ResourcePackUiState.Clearing

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
        Text(text = intro, color = UiColors.TextMuted, style = ShellType.Label)
        Spacer(Modifier.height(ShellSpace.CardGap))
        content()
    }
}

/**
 * 素材卡底部的「清除已导入 …」按钮：描边 + 警示色，与上面「导入」的主按钮分开。
 *
 * 只置确认目标（真正的删除在确认框之后），所以这里不管单飞闸；禁不禁用由 busy 决定，
 * 清理进行中把标签换成 [ResourcePackCopy.clearRunningText]，玩家能看出这一格正在动。
 */
@Composable
private fun MaterialClearButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // 危险动作走次按钮形态 + 警示色字与描边：玩家一眼看出「这一格会删东西」，
    // 形态与圆角仍与壳层其它次按钮同一副（不再各自写 40dp / 8dp）。
    EratwGhostButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        tone = EratwGhostTone(
            content = UiColors.Warn,
            border = UiColors.Warn.copy(alpha = 0.55f),
        ),
        modifier = Modifier.fillMaxWidth().padding(top = ShellSpace.CardGap),
    )
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
        EratwGhostButton(
            text = folderLabel,
            onClick = onPickFolder,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MaterialPickButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EratwFilledButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        container = UiColors.PrimaryButton,
        modifier = modifier,
    )
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
                style = ShellType.Caption,
                modifier = Modifier.padding(top = 2.dp),
            )
            state.skippedSamples.forEach { sample ->
                Text(
                    text = copy.fill(copy.skippedLineFormat, sample),
                    color = UiColors.TextMuted,
                    style = ShellType.Label,
                )
            }
        }

        is ResourcePackUiState.Clearing -> Text(
            text = copy.clearRunningText,
            color = UiColors.TextDim,
            style = ShellType.Caption,
            modifier = Modifier.padding(top = 2.dp),
        )

        is ResourcePackUiState.Cleared -> ResourcePackCleanupArea(state.summary, copy, onRestart)

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
        style = ShellType.Caption,
        modifier = Modifier.padding(top = 2.dp),
    )
    summary.skippedSamples.forEach { sample ->
        Text(
            text = copy.fill(copy.skippedLineFormat, sample),
            color = UiColors.TextMuted,
            style = ShellType.Label,
        )
    }
    summary.warningLines.forEach { line ->
        Text(text = line, color = UiColors.Warn, style = ShellType.Label)
    }
    if (summary.hasLandedFiles) {
        // 落盘了但现状读数显示还没就绪（例如图片缺索引）时没有启用步骤可给，
        // 只剩结构体检行；别留一个空的小标题。
        if (enableHints.isNotEmpty()) {
            Spacer(Modifier.height(ShellSpace.CardGap))
            Text(text = copy.enableStepsTitle, color = UiColors.TextDim, style = ShellType.Caption)
            enableHints.forEach { hint ->
                Text(
                    text = hint,
                    color = UiColors.TextMuted,
                    style = ShellType.Label,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(ShellSpace.CardGap))
        EratwFilledButton(
            text = copy.restartButton,
            onClick = onRestart,
            container = UiColors.PrimaryButton,
        )
    }
}

/**
 * 清理结果区：汇总句 + 还原/失败取证行 + 「重启才生效」提示与重启按钮。
 *
 * 与 [ResourcePackResultArea] 分开摆位：清理**不产生**启用步骤（刚把素材撤了，没有要开的开关），
 * 所以只在真动了盘（删了文件或被覆盖的随包文件被还原）时才顶出重启按钮与那句提示 ——
 * 「没找到需要清理的东西」不该顺手让人重开一局。
 */
@Composable
private fun ResourcePackCleanupArea(
    summary: ResourcePackCleanupSummary,
    copy: ResourcePackCopy,
    onRestart: () -> Unit,
) {
    Text(
        text = summary.message,
        color = if (summary.failedDeletions > 0) UiColors.Warn else UiColors.TextDim,
        style = ShellType.Caption,
        modifier = Modifier.padding(top = 2.dp),
    )
    summary.warningLines.forEach { line ->
        Text(text = line, color = UiColors.Warn, style = ShellType.Label)
    }
    if (summary.deletedFiles > 0 || summary.restoredBaseFiles > 0) {
        Spacer(Modifier.height(ShellSpace.CardGap))
        Text(text = copy.clearRestartHint, color = UiColors.TextMuted, style = ShellType.Label)
        Spacer(Modifier.height(ShellSpace.CardGap))
        EratwFilledButton(
            text = copy.restartButton,
            onClick = onRestart,
            container = UiColors.PrimaryButton,
        )
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
        // MODDX4-D3 组长接线：导入面同口径例外提示（样式照导出卡 hint）。
        Text(
            text = text.saveModImportHint,
            color = UiColors.TextMuted,
            style = ShellType.Label,
        )
        Spacer(Modifier.height(2.dp))
        Button(
            onClick = onPickSaveFiles,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            shape = ShellShape.Inset,
            colors = ButtonDefaults.buttonColors(
                containerColor = UiColors.PrimaryButton,
                contentColor = UiColors.Text,
            ),
        ) {
            Text(if (importing) text.importBusy else text.importButton, style = ShellType.Body)
        }
        lines.forEach { line ->
            Text(
                text = line.text,
                color = if (line.ok) UiColors.TextDim else UiColors.Warn,
                style = ShellType.Caption,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 补丁兼容性卡（PLUGIN-POLICY，2026-11-07；第二轮改为整体禁载）：目录内被禁补丁的只读列出。
 *
 * 无开关——「启用」只会把官方缺插件报错请回来（装载期英文警告 + 执行期 CodeEE），对玩家
 * 没有价值；权威面是引擎侧 `com.eratw.port.core.policy.DisabledErbPatchCatalog`，
 * [PluginPolicyCatalog] 只是 app 的只读视图，erbcc 编译期按同一张表把这些 `.ERB` 脚本
 * 整体排除（不是只停用某条 CALLSHARP）。要支持某补丁的正确路径是实现本机方法并重新出包，
 * 而不是放出开关。文案走域表 [PluginPolicyCopy]（[SettingsStrings] 构造器已顶到 JVM 参数
 * 上限，本卡不往大表加键），补丁名按 id 映射，漏档 [PluginPolicyCatalog.displayName] 直接炸。
 */
@Composable
private fun PluginPolicyCard(text: SettingsStrings) {
    val copy = PluginPolicyCopy.of(text.lang)
    SettingsCard {
        SectionTitle(copy.title)
        Text(text = copy.intro, color = UiColors.TextMuted, style = ShellType.Label)
        Spacer(Modifier.height(6.dp))
        PluginPolicyCatalog.all.forEach { plugin ->
            Text(
                text = "${PluginPolicyCatalog.displayName(plugin.id, copy)} —— ${copy.disabled}",
                color = UiColors.TextDim,
                style = ShellType.Caption,
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
 *//**
 * 诊断卡里「逐帧诊断记录」这一枚开关的状态 + 回调（打包成一个形参，
 * 防 composable 形参表越过 detekt 的 LongParameterList 阈 —— 与 [ModManageActions] 同款处理）。
 *
 * @property checked 当前是否在记
 * @property onCheckedChange 玩家拨动开关（写偏好 + 关档当场摘环）
 */
private data class FrameTraceSwitch(
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

/** 诊断卡的两个按钮回调（同样打包成形参，防形参表越阈）。 */
private data class DiagActions(
    val onShare: () -> Unit,
    val onCopy: () -> Unit,
)

@Composable
private fun DiagnosticsCard(
    text: SettingsStrings,
    busy: Boolean,
    lines: List<DiagLine>,
    frameTrace: FrameTraceSwitch,
    actions: DiagActions,
) {
    SettingsCard {
        SectionTitle(text.diagTitle)
        Text(text = text.diagHint, color = UiColors.TextMuted, style = ShellType.Label)
        Spacer(Modifier.height(6.dp))
        // 逐帧诊断记录（默认关）：玩家侧唯一的「视图层逐帧现场」通道 —— release 包里没有
        // DevTools 的 cmd.txt 通道，只能靠这个开关 + 「分享诊断」把现场带出来。
        // 关态零开销（游戏画面每帧只多一次 volatile 读），所以敢放在玩家能点到的地方。
        SwitchRow(
            title = text.frameTraceTitle,
            hint = text.frameTraceHint,
            checked = frameTrace.checked,
            onCheckedChange = frameTrace.onCheckedChange,
        )
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = actions.onShare,
                enabled = !busy,
                modifier = Modifier.height(40.dp),
                shape = ShellShape.Chip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = UiColors.PrimaryButton,
                    contentColor = UiColors.Text,
                ),
            ) {
                Text(if (busy) text.diagBusy else text.diagShareButton, style = ShellType.Body)
            }
            OutlinedButton(
                onClick = actions.onCopy,
                enabled = !busy,
                modifier = Modifier.height(40.dp),
                shape = ShellShape.Chip,
                border = BorderStroke(1.dp, UiColors.Border),
            ) {
                Text(text.diagCopyButton, style = ShellType.Body)
            }
        }
        lines.forEach { line ->
            Text(
                text = line.text,
                color = if (line.ok) UiColors.TextDim else UiColors.Warn,
                style = ShellType.Caption,
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
                Text(text.aboutDeveloperCaption, color = UiColors.TextMuted, style = ShellType.Label)
                Text(text.aboutDeveloperName, color = LocalZoneAccent.current, style = ShellType.Title)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = text.aboutVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    color = UiColors.Text,
                    style = ShellType.Caption,
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
                style = ShellType.Label,
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
        Text(caption, color = UiColors.TextMuted, style = ShellType.Label)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = UiColors.Text,
                style = ShellType.Tab,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.height(36.dp),
                shape = ShellShape.Chip,
                border = BorderStroke(1.dp, UiColors.Border),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(button, style = ShellType.Caption, maxLines = 1)
            }
        }
    }
}

/**
 * 匿名统计卡（关于档，2026-11-09 从启动卡迁来）：开关 + 正式说明四段。
 *
 * 这是知情同意项而非普通偏好：标题行整行可点（不必戳小开关），下面把「为什么收、
 * 收什么、怎么关」逐段说清。措辞全在 [PlayerStatsCopy] 三语小表里
 * （[SettingsStrings] 已贴着 JVM 255 参数槽上限，见其类头），这里一个字不写死。
 */
@Composable
private fun PlayerStatsCard(
    text: SettingsStrings,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val copy = PlayerStatsCopy.of(text.lang)
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShellShape.Tag)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(copy.title, color = LocalZoneAccent.current, style = ShellType.Section)
                Text(
                    text = copy.hint,
                    color = UiColors.TextMuted,
                    style = ShellType.Label,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = copy.detailTitle,
            color = LocalZoneAccent.current,
            style = ShellType.Caption,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        listOf(copy.purposeBody, copy.privacyBody, copy.controlBody).forEach { body ->
            Text(
                text = body,
                color = UiColors.TextDim,
                style = ShellType.Caption,
                lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

/** 教程重阅入口（关于档）：首启像素指南的常驻回头路，文案走 [TutorialCopy]。 */
@Composable
private fun TutorialRevisitCard(language: SettingsLanguage, onOpen: () -> Unit) {
    val copy = TutorialCopy.of(LocalContext.current, language)
    SettingsCard {
        SectionTitle(copy.revisitTitle)
        Text(
            text = copy.revisitHint,
            color = UiColors.TextMuted,
            style = ShellType.Label,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = onOpen,
            modifier = Modifier.height(40.dp),
            shape = ShellShape.Chip,
            border = BorderStroke(1.dp, UiColors.Border),
        ) {
            Text(copy.revisitButton, color = UiColors.Accent, style = ShellType.Body)
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
        style = ShellType.Caption,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
        text = body,
        color = UiColors.TextDim,
        style = ShellType.Caption,
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
        style = ShellType.Caption,
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
                    style = ShellType.Caption,
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
        style = ShellType.Label,
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
    CollapsibleSettingsCard(title = text.fontTitle, hint = text.fontSizeLabel) {
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
            style = ShellType.Label,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 「竖屏字号」档位卡（竖屏三区壳设计稿 §5.2：50/62/75/90，缺省 62）。
 *
 * 档位表与读写层同源（[UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT] /
 * [UiPrefs.setFontColumnsPortrait]），四档一行分段滑选，当前档回显；文案一律取
 * [SettingsStrings] 三语表（不内联玩家可见字面量）。
 *
 * 独立成卡而不塞进 [ControlCard] 或 [FontCard]：那两张卡的形参表已进 detekt baseline
 * （`LongMethod` 的 ID 含完整签名文本），本卡三形参最稳（契约 §1.5）。
 *
 * 生效口径：竖屏档是装配期参数（`CanvasConsoleView.minColumnsOverride` / `fontScale`
 * 按方向二选一），只在**竖屏模式**下生效；改完返回游戏由 `MainActivity` 按当前方向判重开。
 * 横屏实例里改它不影响当前会话，也就不会误弹「重开」确认。
 */
@Composable
private fun PortraitFontCard(
    text: SettingsStrings,
    columns: Int,
    onColumns: (Int) -> Unit,
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(text.portraitFontTitle)
            Spacer(Modifier.width(6.dp))
            ShellTag(text.experimentalTag)
        }
        PrefCaption(text.portraitFontHint)
        SegmentedSwitcher(
            // 选项值直接取自档位表（与 UiPrefs 的校验、装配同源）；档名在三语表里。
            options = UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT.map { it.columns },
            selected = columns,
            labelOf = text::portraitFontColumnLabel,
            onSelected = onColumns,
        )
        // 当前档的取舍说明（每档一句）：分段条只装得下档名，玩家选完还得知道这一档意味着什么。
        PrefCaption(text.portraitFontColumnHint(columns))
    }
}

/**
 * 「画面左右留白居中」档位卡（圆屏适配，PORT 分区）：四档分段滑选，随改随存写 [UiPrefs]。
 *
 * 文案一律取 [SettingsStrings] 三语表（不内联玩家可见字面量）；复用 `SettingsCard` /
 * `SectionTitle` / `PrefCaption` / `SegmentedSwitcher` 既有共用件，不新建第二套卡片。
 * 独立成卡而不塞进 [ControlCard] 的理由与 ModPackageOrderCard 相同：ControlCard 形参表
 * 已进 detekt baseline（LongMethod），不再添形参。
 * 生效口径与字号档相同：会话构造期参数（`CanvasConsoleView.contentMarginFraction`，
 * 解算收口在 `ScreenLayout.resolve` 的 `contentMarginPx`），改完要重开一局。
 */
@Composable
private fun ContentMarginCard(
    text: SettingsStrings,
    contentMargin: Float,
    onContentMargin: (Float) -> Unit,
) {
    SettingsCard {
        SectionTitle(text.contentMarginLabel)
        PrefCaption(text.contentMarginHint)
        SegmentedSwitcher(
            options = UiPrefs.CONTENT_MARGIN_OPTIONS,
            selected = contentMargin,
            // 档位值是 UiPrefs 白名单常量，按常量配对即按档配对。
            labelOf = { fraction ->
                when (fraction) {
                    UiPrefs.CONTENT_MARGIN_SMALL -> text.contentMarginSmall
                    UiPrefs.CONTENT_MARGIN_MEDIUM -> text.contentMarginMedium
                    UiPrefs.CONTENT_MARGIN_LARGE -> text.contentMarginLarge
                    else -> text.contentMarginOff
                }
            },
            onSelected = onContentMargin,
        )
    }
}

/** 操作与显示：选项按钮大小与右栏布局、底边抓手、适应屏幕。 */
@Composable
private fun ControlCard(
    text: SettingsStrings,
    railButtonSize: UiPrefs.RailButtonSize,
    onRailButtonSize: (UiPrefs.RailButtonSize) -> Unit,
    railLayout: UiPrefs.RailLayoutMode,
    onRailLayout: (UiPrefs.RailLayoutMode) -> Unit,
    railChipsPerRow: Int,
    onRailChipsPerRow: (Int) -> Unit,
    screenFit: Boolean,
    onScreenFit: (Boolean) -> Unit,
    portraitMode: Boolean,
    onPortraitMode: (Boolean) -> Unit,
    liveReflow: Boolean,
    onLiveReflow: (Boolean) -> Unit,
    echoInput: Boolean,
    onEchoInput: (Boolean) -> Unit,
    commandEndWait: Boolean,
    onCommandEndWait: (Boolean) -> Unit,
    busyIndicator: Boolean,
    onBusyIndicator: (Boolean) -> Unit,
    modRuntime: Boolean,
    onModRuntime: (Boolean) -> Unit,
    knobAt: Float,
    onKnobAt: (Float) -> Unit,
) {
    CollapsibleSettingsCard(title = text.controlTitle, hint = null) {
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
            style = ShellType.Label,
            modifier = Modifier.padding(top = 6.dp),
        )
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        // 右栏布局（2026-11-11 三档：行内横滑/竖列/完全展开）+ 每行最多按钮数（1..6，三档通用）。
        // 都是纯壳层摆法，随改随存不必重开游戏；摆法纯函数在 ShellUi.railLayoutOf。
        PrefCaption(text.railLayoutLabel)
        SegmentedSwitcher(
            options = UiPrefs.RailLayoutMode.entries.toList(),
            selected = railLayout,
            labelOf = text::railModeLabel,
            onSelected = onRailLayout,
        )
        // 说明只留选中档那一句：三条并成一条控件之后，「读三段话才知道自己在哪档」的问题
        // 本身就没了。
        Text(
            text = text.railModeHint(railLayout),
            color = UiColors.TextMuted,
            style = ShellType.Label,
            modifier = Modifier.padding(top = 6.dp),
        )
        RailLayoutPreview(
            // 与右栏同一条绘制通路（railLayoutOf + OptionRailRow）：滑到哪档就看见哪档的真摆法。
            metrics = railMetricsForFontScale(railMetricsOf(railButtonSize), LocalDensity.current.fontScale),
            mode = railLayout,
            perRow = railChipsPerRow,
            labels = listOf(
                text.railSampleTalk to 31,
                text.railSampleMove to 400,
                text.railSampleBuy to 10000,
                text.railSampleAttack to 7,
                // 后两枚是屏上不印名字、只印编号的裸数字按钮（自动按钮那一形）：
                // 它们排在第二条显示行上，让预览同时演示「按游戏行分组」这件事。
                "" to 208,
                "" to 310,
            ),
            modifier = Modifier.padding(top = 8.dp),
        )
        PrefCaption(text.railPerRowLabel)
        SegmentedSwitcher(
            options = (UiPrefs.RAIL_CHIPS_PER_ROW_MIN..UiPrefs.RAIL_CHIPS_PER_ROW_MAX).toList(),
            selected = railChipsPerRow,
            labelOf = Int::toString,
            onSelected = onRailChipsPerRow,
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
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        // 竖屏是**显式开关**（默认关）：关 = 锁横屏，与原版版面一致；开 = 锁竖屏三区壳。
        // 没有「跟随机转」——手机竖着拿启动时那会让整个壳意外切成竖屏。
        SwitchRow(
            title = text.portraitTitle,
            hint = text.portraitHint,
            checked = portraitMode,
            onCheckedChange = onPortraitMode,
            tag = text.experimentalTag,
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
        // 指令后等一次按键（COMEND-WAIT，默认开 = 原版）：开 = 指令打完停下等你点一下，
        // 打出的口上不被下个回合刷掉；关 = 不等（2026-11-02 那版的三日常行为）。
        SwitchRow(
            title = text.commandEndWaitTitle,
            hint = text.commandEndWaitHint,
            checked = commandEndWait,
            onCheckedChange = onCommandEndWait,
        )
        // 长页忙碌提示（PRAY-BUSY，默认开）：一页算满 1 秒后在最下面一行画「少女祈祷中…」；
        // 纯显示态、不打印行，关掉不影响任何脚本语义。
        SwitchRow(
            title = text.busyIndicatorTitle,
            hint = text.busyIndicatorHint,
            checked = busyIndicator,
            onCheckedChange = onBusyIndicator,
        )
        // RT2 Mod 运行时（默认开，2026-11-17 主人裁定）：会话构造期参数，关态装配链一次
        // 都不进、磁盘都不碰；开 = 随包内置 Mod 才装载、活动 Mod 数据随存档写 `.rt2` 侧车。
        // 没有安装任何 Mod 时装配链自动旁路，游戏内容不变。改完要重开一局。
        SwitchRow(
            title = text.modRuntimeTitle,
            hint = text.modRuntimeHint,
            checked = modRuntime,
            onCheckedChange = onModRuntime,
        )
    }
}

/**
 * Mod 管理卡的回调 + 语言口径合集（打包成一个形参，防 composable 形参表越过 detekt 阈值）。
 *
 * [onRetryInstall] 是回滚态重装的投递口（Lane DZ：只投数据队列，不碰会话引用）；
 * [recompileCopy] 是重编失败证据串的语言口径（与导入卡同一份；后台线程不碰语言源）；
 * [uninstallImpact] 是卸载确认前现取的只读影响预览（Lane uninstallpreview：读盘只读，
 * 删除语义仍在 [ModManageUninstall]，预览不写盘不删文件）。
 */
private data class ModManageActions(
    val onToggle: (InstalledMod, Boolean) -> Unit,
    val onUninstall: (InstalledMod) -> Unit,
    val onRetryInstall: (InstalledMod) -> Unit,
    val recompileCopy: ModImportCopy,
    val uninstallImpact: (InstalledMod) -> List<String>,
    /**
     * task-179：该包盘面上的第三方代码态（null = 这个包没有代码产物，整行读数不出）。
     *
     * 与 [uninstallImpact] 同一注射口径：纯读 + 由屏体在闭包里捕 context / rt2 根，
     * 不新增 SettingsScreen 形参、不改 [ModManageCard] 的 5 参签名；本回调不写盘、不裁决装载。
     */
    val codeState: (InstalledMod) -> ModCodePackState?,
)

/**
 * 已装列表的来源筛选档（Lane modsearch）：[ALL] 不筛，其余一一对应 [ModSource]。
 *
 * 只做**视图过滤**：不读盘、不写 marker、不参与装配裁决；列表行怎么画全看这个档位。
 */
internal enum class ModManageSourceFilter {
    ALL,
    BUILTIN,
    DATA_PACKAGE,
    IMPORTED,
}

/** 来源筛选档是否放行某条来源（[ModManageSourceFilter.ALL] 恒放行）。 */
internal fun ModManageSourceFilter.matches(source: ModSource): Boolean = when (this) {
    ModManageSourceFilter.ALL -> true
    ModManageSourceFilter.BUILTIN -> source == ModSource.BUILTIN
    ModManageSourceFilter.DATA_PACKAGE -> source == ModSource.DATA_PACKAGE
    ModManageSourceFilter.IMPORTED -> source == ModSource.IMPORTED
}

/**
 * 已装列表的纯视图过滤（Lane modsearch）：关键词 × 来源档，保扫描序、不重排。
 *
 * 关键词按 `displayName` / `packageId` / `author` 做**大小写不敏感包含**匹配；只含空白的
 * 查询等同空串（= 全部），免得玩家多敲一个空格就得到空列表。筛选只看来源，**不看启停态**
 * —— 停用的 Mod 照常留在结果里（要藏它只能靠来源档，不是靠搜索框）。
 */
internal fun filterInstalledMods(
    mods: List<InstalledMod>,
    query: String,
    sourceFilter: ModManageSourceFilter,
): List<InstalledMod> {
    val needle = query.trim()
    return mods.filter { mod ->
        sourceFilter.matches(mod.source) && (
            needle.isEmpty() ||
                mod.displayName.contains(needle, ignoreCase = true) ||
                mod.packageId.contains(needle, ignoreCase = true) ||
                mod.author.contains(needle, ignoreCase = true)
            )
    }
}

/**
 * 「已安装的 Mod」管理卡（批2-3 列表 + task-2 管理面）：启停、详情展开、卸载、装载失败读数。
 *
 * 四类读数在界面上**分开报**（产品裁定，别混成一句）：
 * - 盘面扫描出的坏 manifest（[ModManageScan.skipped]，读不出来）；
 * - 本次运行装配链上报的装载失败（[ModManageReadouts.failures]，代码/声明问题）；
 * - 条目详情里的 `.erbm` 配对状态（数据包半成品问题）。
 * - 条目状态区的缺依赖行（ModDX2-C：`requires` 前置不在场，盘面静态事实、不随启停变）。
 *
 * 搜索 / 来源筛选（Lane modsearch）是**纯视图过滤**：值经 [ModManageFilterUi] 传入，真值在
 * `SettingsScreen` 体内（LazyColumn 回收卡时会丢卡内 `remember`）；过滤只决定画哪些行，装载
 * 失败读数仍按整张已装清单报。卸载走二次确认对话框（防玩家误删导入包）。卡里只用
 * `SettingsStrings` 三语文案，不内联玩家可见字面量。
 */
@Composable
private fun ModManageCard(
    text: SettingsStrings,
    scan: ModManageScan,
    readouts: ModManageReadouts,
    filter: ModManageFilterUi,
    actions: ModManageActions,
) {
    val versionsById = remember(readouts.versions) { readouts.versions.associateBy { it.packageId } }
    val visibleMods = filterInstalledMods(scan.mods, filter.query, filter.sourceFilter)
    // 折叠态默认展开（rememberSaveable 记住玩家选择）；收起后标题行仍带已装计数，随时能再打开。
    var expanded by rememberSaveable { mutableStateOf(true) }
    SettingsCard {
        CollapsibleHeading(
            title = text.modManageTitle,
            badge = scan.mods.size.takeIf { it > 0 }?.toString(),
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = ShellMotion.disclosureEnter(),
            exit = ShellMotion.disclosureExit(),
        ) {
            // 必须自己套一层 Column：`AnimatedVisibility` 的 content 是**叠放**布局（多个子项
            // 全部落脚在同一原点），直接并列写正文会变成「说明句与每一行 Mod 重叠在一行上」。
            // 揭示式卡（ShellDisclosureSection）里那层 Column 就是干这个的，本卡手写的这一处
            // 漏了 —— 玩家看到的就是文字互相压住。
            Column(modifier = Modifier.fillMaxWidth()) {
                ModManageIntro(
                    text = text,
                    scan = scan,
                    filter = filter,
                    visibleCount = visibleMods.size,
                    scanning = readouts.scanning,
                )
                for (mod in visibleMods) {
                    ModManageRow(
                        text = text,
                        mod = mod,
                        failure = readouts.failures[mod.packageId],
                        version = versionsById[mod.packageId],
                        actions = actions,
                    )
                }
                // 装载失败读数按**整张已装清单**渲染：它是本会话的诊断面，不该被搜索框藏起来。
                ModManageFailures(text, readouts.failures, scan.mods)
                ModManageBrokenLine(text, scan.skipped)
                ModHeirSection(text, scan)
                Text(
                    text = text.modManageHint,
                    color = UiColors.TextMuted,
                    style = ShellType.Label,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * 卡体上半段：引擎读数、生效时机、首扫进度、空态与筛选条（从 [ModManageCard] 拆出，压长函数）。
 *
 * [scanning] 只决定「首扫未完时别先说一个都没装」；重扫期间清单保留旧值，不出进度条。
 */
@Composable
private fun ModManageIntro(
    text: SettingsStrings,
    scan: ModManageScan,
    filter: ModManageFilterUi,
    visibleCount: Int,
    scanning: Boolean,
) {
    // 引擎读数只在段首出现一次（不逐行重复）：玩家一眼看到本体内核支持到哪一版。
    Text(text.modVersionEngine(MOD_ENGINE_API_VERSION), color = UiColors.TextMuted, style = ShellType.Label)
    // 生效时机一次性说清（启停/卸载 = 下次进入游戏；重编 = 重开一局；per-mod 设置 = 本局
    // 下一拍）。说明行直接读 text 插在组合体里，不再给卡加形参。
    Text(
        text = text.modManageTakeEffectHint,
        color = UiColors.TextMuted,
        style = ShellType.Label,
        modifier = Modifier.padding(top = 2.dp),
    )
    Text(text.modManageExpandHint, color = UiColors.TextMuted, style = ShellType.Label)
    // 首扫未完：只出细进度条，不出「还没有安装任何 Mod」的空态（避免先空后满的闪烁）。
    if (scanning && scan.mods.isEmpty()) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            color = LocalZoneAccent.current,
            trackColor = UiColors.CardAlt,
        )
    }
    if (scan.mods.isEmpty()) {
        // 一个都没装：只报「还没装」，不出搜索/筛选控件（没东西可筛，控件只会误导）。
        if (!scanning) {
            Text(text.modManageEmpty, color = UiColors.TextMuted, style = ShellType.Caption)
        }
    } else {
        ModManageFilterBar(
            text = text,
            filter = filter,
            visibleCount = visibleCount,
            totalCount = scan.mods.size,
        )
        if (visibleCount == 0) {
            // 两态分开：这里是「筛选后没有匹配」，不是「一个都没装」。
            Text(text.modManageFilterEmpty, color = UiColors.TextMuted, style = ShellType.Caption)
        }
    }
}

/**
 * Mod 管理卡的逐包读数束（Lane modsearch）：装载失败 + 版本读数，都是本会话的只读快照。
 *
 * 与 [ModManageFilterUi] 分开成两个小束是为了让 [ModManageCard] 的形参留在 detekt
 * LongParameterList 默认阈值内，同时不把视图状态混进 [ModManageActions]。
 */
private data class ModManageReadouts(
    val failures: Map<String, String>,
    val versions: List<ModVersionRow>,
    /** 首扫是否还在进行：卡体据此只出进度条、不先报「一个都没装」。 */
    val scanning: Boolean,
)

/**
 * Mod 列表的搜索 + 来源筛选视图状态（Lane modsearch）：值 + 回写回调，真值在 [SettingsScreen]
 * 体内（本屏是 LazyColumn，卡滚出视口会被回收，所以不落卡内 `remember`）。
 *
 * 只影响「画哪些行」：不写盘、不改 marker、不参与装配裁决，也不影响启停 / 卸载 / 重试。
 */
private data class ModManageFilterUi(
    val query: String,
    val sourceFilter: ModManageSourceFilter,
    val onQueryChange: (String) -> Unit,
    val onSourceFilterChange: (ModManageSourceFilter) -> Unit,
)

/**
 * 已装列表的搜索框 + 来源筛选条 + 计数行（Lane modsearch）。
 *
 * 控件状态由调用方（本屏局部 `remember`）持有，本函数只画控件、不持真值。筛选档文案复用
 * 来源标签（内置/数据包/导入），第一档「全部」用 [SettingsStrings.modManageFilterAll]。
 */
@Composable
private fun ModManageFilterBar(
    text: SettingsStrings,
    filter: ModManageFilterUi,
    visibleCount: Int,
    totalCount: Int,
) {
    OutlinedTextField(
        value = filter.query,
        onValueChange = filter.onQueryChange,
        singleLine = true,
        label = { Text(text.modManageSearchLabel, style = ShellType.Caption) },
        textStyle = ShellType.Body,
        colors = ShellField.outlined(),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
    SegmentedSwitcher(
        options = ModManageSourceFilter.entries,
        selected = filter.sourceFilter,
        labelOf = { source -> modSourceFilterLabel(text, source) },
        onSelected = filter.onSourceFilterChange,
        modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        text = text.modManageFilterCount(visibleCount, totalCount),
        color = UiColors.TextMuted,
        style = ShellType.Label,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** 来源筛选档的三语文案：后三档复用详情页的来源标签，[ModManageSourceFilter.ALL] 单独一条。 */
private fun modSourceFilterLabel(text: SettingsStrings, filter: ModManageSourceFilter): String =
    when (filter) {
        ModManageSourceFilter.ALL -> text.modManageFilterAll
        ModManageSourceFilter.BUILTIN -> text.modManageSourceBuiltin
        ModManageSourceFilter.DATA_PACKAGE -> text.modManageSourceData
        ModManageSourceFilter.IMPORTED -> text.modManageSourceImported
    }

/**
 * 「家督继任」小节（task-160 / Lane HB）：mod-family 手动继任面的玩家入口。
 *
 * 嵌在 [ModManageCard] 卡尾、**不**给 `SettingsScreen` 的 60+ 形参加参：数据面只读
 * [ModHeirFeed] 快照（会话 → 设置页的真值回写），点击只进 [ModHeirRequests] 队列
 * （设置页 → VM 线程页边界）。两侧都不持 host / session 引用——设置页与游戏不在同一线程。
 *
 * 显示口径：family 载体在场且启用才渲染整块（否则连标题都不出现）；有载体但本局还没回写过
 * 快照时显示「尚未就绪」+ 破折号，而不是留白；规则不是 `manual` 时候选只能看不能点，
 * 并明说「先改成手动」。清除是破坏性动作，二次确认照 [ModSettingResetControl] 形制。
 */
@Composable
private fun ModHeirSection(text: SettingsStrings, scan: ModManageScan) {
    val familyEnabled = scan.mods.any {
        it.packageId == FamilyDeclarations.PACKAGE_ID && it.enabled
    }
    if (!familyEnabled) return
    val context = LocalContext.current
    val snapshot = remember { ModHeirFeed.read() }
    // 本地选中态：初值 = 快照里的已指定；点候选只改高亮，按「指定」才入队。
    var chosenRaw by remember { mutableStateOf(snapshot?.pendingRaw) }
    var confirmingClear by remember { mutableStateOf(false) }
    val manual = snapshot?.mode == FamilyDeclarations.SUCCESSION_MANUAL
    ModHeirSummary(text, snapshot, chosenRaw)
    if (snapshot == null) {
        // 有载体但本局还没到过页边界：显式说「还没就绪」，不拿空列表冒充「没有候选」。
        Text(text.modHeirUnavailable, color = UiColors.TextMuted, style = ShellType.Label)
    } else {
        if (!manual) {
            Text(text.modHeirManualOnlyHint, color = UiColors.TextMuted, style = ShellType.Label)
        }
        ModHeirCandidateList(text, snapshot.candidates, manual, chosenRaw) { chosenRaw = it }
        ModHeirActionRow(
            text = text,
            enabled = manual && chosenRaw != null,
            onChoose = { chosenRaw?.let { raw -> queueHeirChoose(context, text, raw) } },
            onClear = { confirmingClear = true },
        )
        if (confirmingClear) {
            ModHeirClearConfirm(
                text = text,
                onConfirm = {
                    confirmingClear = false
                    if (queueHeirClear(context, text)) chosenRaw = null
                },
                onDismiss = { confirmingClear = false },
            )
        }
    }
}

/** 小节摘要：标题 / 说明 / 当前规则行 / 已指定行（只读快照，读不到就显示破折号）。 */
@Composable
private fun ModHeirSummary(text: SettingsStrings, snapshot: ModHeirSnapshot?, chosenRaw: Long?) {
    val modeLabel = when (snapshot?.mode) {
        FamilyDeclarations.SUCCESSION_MANUAL -> text.modHeirModeManual
        FamilyDeclarations.SUCCESSION_ELDEST -> text.modHeirModeEldest
        FamilyDeclarations.SUCCESSION_YOUNGEST -> text.modHeirModeYoungest
        else -> text.modHeirModeUnknown
    }
    Text(
        text = text.modHeirSectionTitle,
        color = UiColors.Text,
        style = ShellType.Caption,
        modifier = Modifier.padding(top = 10.dp),
    )
    Text(text.modHeirHint, color = UiColors.TextMuted, style = ShellType.Label)
    Text(
        text = text.modHeirModeLine(text.modHeirModeLabel, modeLabel),
        color = UiColors.Text,
        style = ShellType.Caption,
        modifier = Modifier.padding(top = 2.dp),
    )
    Text(
        text = if (chosenRaw == null) {
            text.modHeirPendingNone
        } else {
            // 已指定行与候选行同口径：有名字就带名字，没名字回落 #raw（task-171）。
            val chosen = snapshot?.candidates?.firstOrNull { it.raw == chosenRaw }
            text.modHeirPending(heirCandidateLabel(text, chosenRaw, chosen))
        },
        color = if (chosenRaw == null) UiColors.TextMuted else UiColors.Text,
        style = ShellType.Caption,
    )
}

/**
 * 候选行文本（task-171）：有显示名 ⇒「名字（#id）」，缺名 ⇒ 只显示 `#id`。
 *
 * [candidate] 传 null 表示已指定的人已不在当前候选表里（如已退场但指定保留），同样回落 `#raw`；
 * 空串 / 纯空白名字视为缺名（作者面归一化会省掉空标题，这里再兜一层）。
 */
private fun heirCandidateLabel(
    text: SettingsStrings,
    raw: Long,
    candidate: ModHeirCandidate?,
): String = text.modHeirCandidateLabel(candidate?.displayName, raw)

/** 候选列表：非 manual 规则只读（enabled = false），空列表显式说「本局没有可选继任者」。 */
@Composable
private fun ModHeirCandidateList(
    text: SettingsStrings,
    candidates: List<ModHeirCandidate>,
    manual: Boolean,
    chosenRaw: Long?,
    onPick: (Long) -> Unit,
) {
    Text(
        text = text.modHeirCandidatesLabel,
        color = UiColors.Text,
        style = ShellType.Caption,
        modifier = Modifier.padding(top = 8.dp),
    )
    if (candidates.isEmpty()) {
        Text(text.modHeirNoCandidates, color = UiColors.TextMuted, style = ShellType.Label)
        return
    }
    for (candidate in candidates) {
        val label = heirCandidateLabel(text, candidate.raw, candidate)
        val pressSource = remember { MutableInteractionSource() }
        Text(
            text = label,
            color = if (candidate.raw == chosenRaw) UiColors.Accent else UiColors.Text,
            style = ShellType.Caption,
            modifier = Modifier
                .fillMaxWidth()
                // 候选是「一行一条的选项」：热区按行高撑到 44dp，按压压一档底色，
                // 非 manual（这一行只是读数）时既不可点也不染色。
                .heightIn(min = ShellSpace.RowMinHeight)
                .background(
                    if (manual) ShellPress.tint(pressSource, UiColors.Accent) else Color.Transparent,
                )
                .clickable(
                    enabled = manual,
                    interactionSource = pressSource,
                    indication = null,
                ) { onPick(candidate.raw) }
                .padding(vertical = 4.dp),
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
        )
    }
}

/** 操作行：指定 / 清除。两者都只入队，页边界才兑现；非 manual 或没选中时禁用。 */
@Composable
private fun ModHeirActionRow(
    text: SettingsStrings,
    enabled: Boolean,
    onChoose: () -> Unit,
    onClear: () -> Unit,
) {
    Row(modifier = Modifier.padding(top = 4.dp)) {
        TextButton(onClick = onChoose, enabled = enabled) {
            Text(
                text = text.modHeirChooseButton,
                color = if (enabled) UiColors.Text else UiColors.TextMuted,
                style = ShellType.Tab,
            )
        }
        TextButton(onClick = onClear, enabled = enabled) {
            Text(
                text = text.modHeirClearButton,
                color = if (enabled) UiColors.Warn else UiColors.TextMuted,
                style = ShellType.Tab,
            )
        }
    }
}

/** 清除二次确认（破坏性：清除 = 交回当前继承规则自动选择，不是「这局没有继任者」）。 */
@Composable
private fun ModHeirClearConfirm(text: SettingsStrings, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ShellAlertDialog(
        title = text.modHeirClearConfirmTitle,
        onDismissRequest = onDismiss,
        actions = ShellDialogActions(
            confirmLabel = text.modHeirClearConfirmOk,
            onConfirm = onConfirm,
            confirmTone = EratwInk.warn(),
            dismissLabel = text.cancelText,
            onDismiss = onDismiss,
        ),
    ) {
        Text(text = text.modHeirClearConfirmBody, color = EratwInk.text(), style = ShellType.Body)
    }
}

/** 入队「指定」并给回执；入队被拒（非法 id / 非法包名）时静默，按钮下一帧按快照回真值。 */
private fun queueHeirChoose(context: Context, text: SettingsStrings, raw: Long) {
    if (ModHeirRequests.requestChoose(FamilyDeclarations.PACKAGE_ID, raw)) {
        Toast.makeText(context, text.modHeirChooseQueued, Toast.LENGTH_SHORT).show()
    }
}

/** 入队「清除」并给回执；返 true = 已入队（调用方据此清本地高亮）。 */
private fun queueHeirClear(context: Context, text: SettingsStrings): Boolean {
    val queued = ModHeirRequests.requestClear(FamilyDeclarations.PACKAGE_ID)
    if (queued) Toast.makeText(context, text.modHeirClearQueued, Toast.LENGTH_SHORT).show()
    return queued
}
/**
 * 「第三方代码包」信任卡（ADR-013 D3 / task-153 S4b）：每个 code 下有 .mod 产物的包一行。
 *
 * 数据面 = [ModTrustStore]（`.trusted` 标记与 `.off` 同构），状态 = [ModTrustBoard]，全在卡内
 * `remember`，不加 `SettingsScreen` 的 60+ 形参。首次信任先弹诚实二次确认（同进程 / 无沙箱 /
 * 等同自行安装的应用内代码），取消不写盘；撤销同样先二次确认（task-165：撤销是安全面动作，
 * 点了行内按钮只进弹窗），确认后才删标记、下次装载回 NeedsTrust。落盘成功经 [onTrustChanged]
 * 通知宿主，让同页只读概览卡重扫（信任态即时翻转）。
 */
@Composable
private fun ModTrustCard(text: SettingsStrings, onTrustChanged: () -> Unit) {
    val context = LocalContext.current
    val rt2Dir = remember { ModEnablement.rt2Dir(GameStorage(context).gameRoot()) }
    val board = remember(rt2Dir) { ModTrustBoard(rt2Dir) }
    var rows by remember(board) { mutableStateOf(board.scan()) }
    var pending by remember(board) { mutableStateOf<String?>(null) }
    // 待撤销包：非 null 才弹撤销二次确认；确认前不碰盘面。
    var revokeTarget by remember(board) { mutableStateOf<String?>(null) }
    SettingsCard {
        SectionTitle(text.modTrustTitle)
        Text(text.modTrustHint, color = UiColors.TextMuted, style = ShellType.Label)
        Text(
            text = text.modTrustHonestNote,
            color = UiColors.Warn,
            style = ShellType.Label,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (rows.isEmpty()) {
            Text(text.modTrustEmpty, color = UiColors.TextMuted, style = ShellType.Caption)
        }
        for (row in rows) {
            ModTrustRowView(
                text = text,
                row = row,
                onRequestTrust = {
                    board.requestTrust(row.packageId)
                    pending = board.pendingTrustPackageId
                },
                onRevoke = { revokeTarget = row.packageId },
            )
        }
    }
    ModTrustConfirmHost(
        text = text,
        target = pending,
        onCancel = {
            board.cancelTrust()
            pending = board.pendingTrustPackageId
        },
    ) { packageId ->
        val written = board.confirmTrust()
        rows = board.rows
        pending = board.pendingTrustPackageId
        if (written) onTrustChanged()
        modTrustFailureCopy(text, written, board.lastBlockedReason)
    }
    ModTrustRevokeConfirmHost(
        text = text,
        target = revokeTarget,
        onDismiss = { revokeTarget = null },
    ) { packageId2 ->
        val removed = board.revoke(packageId2)
        rows = board.rows
        if (removed) onTrustChanged()
        modTrustFailureCopy(text, removed, board.lastBlockedReason)
    }
}

/**
 * 首次信任的二次确认宿主（task-165 拆出）：[target] 非 null 时弹确认框，确认后回调 [onConfirm]。
 *
 * 与 [ModTrustRevokeConfirmHost] 同形——拆出来是为让 [ModTrustCard] 本体守住 detekt 的
 * `LongMethod` 阈值（60 行）；[onConfirm] 返回 null = 标记写成，否则返回失败上屏文案（含身份
 * 侧车坏档这种「补信任没用」的受控失败原因，见 [modTrustFailureCopy]）。
 */
@Composable
private fun ModTrustConfirmHost(
    text: SettingsStrings,
    target: String?,
    onCancel: () -> Unit,
    onConfirm: (String) -> String?,
) {
    val packageId = target ?: return
    val context = LocalContext.current
    ModTrustConfirmDialog(text = text, packageId = packageId, onCancel = onCancel) {
        showModTrustToast(context, text.modTrustGranted(packageId), onConfirm(packageId))
    }
}

/** 单行读数：包名 + 产物数 + 信任态 + 对应的信任 / 撤销按钮。 */
@Composable
private fun ModTrustRowView(
    text: SettingsStrings,
    row: ModTrustRow,
    onRequestTrust: () -> Unit,
    onRevoke: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(modTrustRowReadout(text, row), color = UiColors.Text, style = ShellType.Tab)
            Text(
                text = modTrustRowStateLabel(text, row),
                color = if (row.trusted) UiColors.Accent else UiColors.Warn,
                style = ShellType.Label,
            )
        }
        // 身份坏档（侧车损坏）：信任 / 撤销两个方向都不能写盘，不给按钮；状态行已说明只能重新导入。
        if (row.blockedReason == null) {
            if (row.trusted) {
                TextButton(onClick = onRevoke) {
                    Text(text.modTrustRevokeButton, color = UiColors.Warn, style = ShellType.Tab)
                }
            } else {
                TextButton(onClick = onRequestTrust) {
                    Text(text.modTrustGrantButton, color = UiColors.Accent, style = ShellType.Tab)
                }
            }
        }
    }
}

/** 二次信任确认：正文如实说明风险（同进程 / 无沙箱 / 等同自行安装），不许淡化。 */
@Composable
private fun ModTrustConfirmDialog(
    text: SettingsStrings,
    packageId: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    ShellAlertDialog(
        title = text.modTrustConfirmTitle,
        onDismissRequest = onCancel,
        actions = ShellDialogActions(
            confirmLabel = text.modTrustConfirmOk,
            onConfirm = onConfirm,
            confirmTone = EratwInk.warn(),
            dismissLabel = text.cancelText,
            onDismiss = onCancel,
        ),
    ) {
        Text(text = text.modTrustConfirmBody(packageId), color = EratwInk.text(), style = ShellType.Body)
    }
}

/**
 * 撤销信任的二次确认宿主（task-165）：[target] 非 null 时弹确认框，确认后才走 [onRevoke]。
 *
 * 拆成独立 composable 而不是塞进 [ModTrustCard]：信任卡本体已贴近 detekt 的 LongMethod 阈值，
 * 这里同时承接「撤销落盘后的 toast」；[onRevoke] 返回 null = 标记删成，否则返回失败上屏文案
 * （身份侧车坏档时是「身份信息损坏」，其余是「没能更新信任记录」）。
 */
@Composable
private fun ModTrustRevokeConfirmHost(
    text: SettingsStrings,
    target: String?,
    onDismiss: () -> Unit,
    onRevoke: (String) -> String?,
) {
    val packageId = target ?: return
    val context = LocalContext.current
    ModTrustRevokeConfirmDialog(
        text = text,
        packageId = packageId,
        onCancel = onDismiss,
        onConfirm = {
            val failure = onRevoke(packageId)
            onDismiss()
            showModTrustToast(context, text.modTrustRevoked(packageId), failure)
        },
    )
}

/** 撤销信任二次确认：正文如实说明删标记、下次装载回待确认、本局代码不中途卸载。 */
@Composable
private fun ModTrustRevokeConfirmDialog(
    text: SettingsStrings,
    packageId: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    ShellAlertDialog(
        title = text.modTrustRevokeConfirmTitle,
        onDismissRequest = onCancel,
        actions = ShellDialogActions(
            confirmLabel = text.modTrustRevokeConfirmOk,
            onConfirm = onConfirm,
            confirmTone = EratwInk.warn(),
            dismissLabel = text.cancelText,
            onDismiss = onCancel,
        ),
    ) {
        Text(
            text = text.modTrustRevokeConfirmBody(packageId),
            color = EratwInk.text(),
            style = ShellType.Body,
        )
    }
}

/**
 * 总闸被拨动后的去向（纯函数，JVM 可测）：开启方向必须先过二次确认，关方向直接落盘。
 *
 * 拆成纯函数而不是把分支散在 composable 里，是为了让「开启必须确认」与「取消零写点」这两条
 * 安全性质能被桌面单测逐条钉死（[ModDexLoaderSwitchWireTest]），而不是只靠源码哨兵对字符串。
 */
internal enum class ModDexLoaderToggleAction { ASK_CONFIRM, WRITE, IGNORE }

/** [requested] 是玩家拨到的目标态，[confirmed] 是确认弹窗的结论（null = 尚未询问）。 */
internal fun modDexLoaderToggleAction(
    requested: Boolean,
    confirmed: Boolean?,
): ModDexLoaderToggleAction = when {
    confirmed == false -> ModDexLoaderToggleAction.IGNORE
    confirmed == true -> ModDexLoaderToggleAction.WRITE
    requested -> ModDexLoaderToggleAction.ASK_CONFIRM
    else -> ModDexLoaderToggleAction.WRITE
}

/**
 * dex 装载总闸开启前的二次确认（task-191）：讲清三件事 —— 同进程同权限无沙箱、出错崩溃会带走
 * 整局；只在了解来源时开启；下次进入游戏才生效。
 *
 * [visible] false 时不构造 `AlertDialog`（同 [ModTrustRevokeConfirmHost] 的 `target ?: return` 手法）。
 * 取消路径只调 [onDismiss]，**没有任何写点**：真正写盘只发生在 [onConfirm]。
 */
@Composable
private fun ModDexLoaderConfirmHost(
    text: SettingsStrings,
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    ShellAlertDialog(
        title = text.modCodeLoaderConfirmTitle,
        onDismissRequest = onDismiss,
        actions = ShellDialogActions(
            confirmLabel = text.modCodeLoaderConfirmOk,
            onConfirm = onConfirm,
            confirmTone = EratwInk.warn(),
            dismissLabel = text.cancelText,
            onDismiss = onDismiss,
        ),
    ) {
        Text(text = text.modCodeLoaderConfirmBody, color = EratwInk.text(), style = ShellType.Body)
    }
}

/**
 * 「第三方代码包概览」卡（ADR-013 路 B 玩家可见面 / task-162，task-191 补总闸开关）：总闸 + 每包读数。
 *
 * 数据面 = [buildModCodeOverview]（扫 `code/` 与 `*.trusted` 标记，只读），总闸读数与 rt2
 * 目录都在卡内 `remember` 取，不加 `SettingsScreen` 那 60+ 个形参。卡上唯一的动作是本卡的
 * 装载总闸开关：开启方向必须过 [ModDexLoaderConfirmHost] 二次确认（第三方机器码与应用同进程
 * 同权限、无沙箱，出错崩溃会带走整局游戏），关方向直接落盘；写盘结果按
 * [UiPrefs.setModDexLoader] 的读回校验判，失败给红字而不静默当成功。总闸关时整块只显式
 * 「未开启」——不渲染空表，免得玩家把「未开启」误读成「本机没装」。
 *
 * [trustRevision] 由宿主在信任卡确认 / 撤销落盘后递增（task-165）；[gateRevision] 是本卡
 * 自己在总闸落盘成功后递增的局部 revision（task-191）—— 两者都只是重扫的 `remember` key，
 * 让同页读数即时翻转，卡片仍然不新增 `SettingsScreen` 形参。
 */
@Composable
private fun ModCodeOverviewCard(text: SettingsStrings, trustRevision: Int) {
    val context = LocalContext.current
    var gateRevision by remember { mutableIntStateOf(0) }
    var confirmEnable by remember { mutableStateOf(false) }
    var writeFailed by remember { mutableStateOf(false) }
    val overview = remember(context, trustRevision, gateRevision) {
        val enabled = UiPrefs.modDexLoaderEnabled(context)
        val rt2Dir = runCatching { ModEnablement.rt2Dir(GameStorage(context).gameRoot()) }.getOrNull()
        // task-195：装配期发布的最近一次装载结论（进程内快照，不落盘）；拿不到就照「—」口径。
        buildModCodeOverview(rt2Dir, enabled, loadReport = ModCodeLoadReports.last)
    }
    fun writeGate(enabled: Boolean) {
        if (UiPrefs.setModDexLoader(context, enabled)) {
            writeFailed = false
            gateRevision += 1
        } else {
            writeFailed = true
        }
    }
    SettingsCard {
        SectionTitle(text.modCodeOverviewTitle)
        Text(text.modCodeOverviewHint, color = UiColors.TextMuted, style = ShellType.Label)
        Text(
            text = modCodeGateLine(text, overview.dexLoaderEnabled),
            color = if (overview.dexLoaderEnabled) UiColors.Text else UiColors.Warn,
            style = ShellType.Caption,
            modifier = Modifier.padding(top = 4.dp),
        )
        SwitchRow(
            title = text.modCodeLoaderSwitchTitle,
            hint = text.modCodeLoaderSwitchHint,
            checked = overview.dexLoaderEnabled,
            onCheckedChange = { requested ->
                when (modDexLoaderToggleAction(requested, confirmed = null)) {
                    ModDexLoaderToggleAction.ASK_CONFIRM -> {
                        writeFailed = false
                        confirmEnable = true
                    }
                    ModDexLoaderToggleAction.WRITE -> writeGate(requested)
                    ModDexLoaderToggleAction.IGNORE -> Unit
                }
            },
        )
        if (writeFailed) {
            Text(text.modCodeLoaderWriteFailed, color = UiColors.Warn, style = ShellType.Caption)
        }
        if (overview.gateOff) return@SettingsCard
        if (overview.readFailed) {
            Text(text.modCodeOverviewUnreadable, color = UiColors.Warn, style = ShellType.Label)
            return@SettingsCard
        }
        if (overview.empty) {
            Text(text.modCodeOverviewEmpty, color = UiColors.TextMuted, style = ShellType.Caption)
        }
        for (row in overview.rows) {
            ModCodeRowView(text = text, row = row)
        }
    }
    ModDexLoaderConfirmHost(
        text = text,
        visible = confirmEnable,
        onDismiss = { confirmEnable = false },
        onConfirm = {
            confirmEnable = false
            writeGate(true)
        },
    )
}

/** 单个代码包读数：包名 · 版本数 · 体积，第二行信任态（绿 = 已信任 / 黄 = 未信任或身份坏档），第三行装载结论。 */
@Composable
private fun ModCodeRowView(text: SettingsStrings, row: ModCodeRow) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(modCodeRowReadout(text, row), color = UiColors.Text, style = ShellType.Tab)
        Text(
            text = modCodeRowTrustLabel(text, row),
            color = if (row.trusted) UiColors.Accent else UiColors.Warn,
            style = ShellType.Label,
        )
        // task-195：本次启动的装载结论（缺数据显式「—」）；问题态用 Warn 色，与 Mod 管理行同形。
        ModCodeDetailLine(
            text = modCodeLoadLine(text, row),
            warn = row.loadConclusion != null && row.loadConclusion.outcome != CodeCarrierOutcome.LOADED,
        )
        // Lane DZ（ERAFIN2）：结论非「已生效」的行给「重试装载」入口——请求进既有
        // [ModReinstallRequests] 队列，VM 线程页边界由 GameHost 消费：内置载体定取口
        // 不在册（第三方 dex 包不在 ModCarrierRegistry）时回退 dex 供给核单包取件
        // 现造新实例；玩家侧状态变化（停用 / 总闸关 / 盘面清理）= 消费端静默作废，
        // 与队列既有语义同源。按钮与等待提示复用 Lane DZ 既有三语键，零新增文案。
        if (row.loadConclusion != null && row.loadConclusion.outcome != CodeCarrierOutcome.LOADED) {
            ModReinstallRetryButton(text = text, onClick = {
                ModReinstallRequests.request(row.packageId)
                Toast.makeText(context, text.modManageRetryQueued, Toast.LENGTH_SHORT).show()
            })
        }
    }
}

/**
 * 「代码」读数行小字：Mod 管理行与代码包概览行共用同一渲染（问题态 Warn、已生效 / 缺数据次要色）。
 *
 * 抽出来是为了让 task-195 的装载结论行与 task-179 的代码态行**同一副长相**，同时不给
 * [SettingsScreen] / [ModManageCard] 加形参。
 */
@Composable
private fun ModCodeDetailLine(text: String, warn: Boolean) {
    Text(
        text = text,
        color = if (warn) UiColors.Warn else UiColors.TextMuted,
        style = ShellType.Label,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * 卸载入口的唯一实现（设置页闭包与 e2e 共用）：先解析运行期身份，再卸载，成功才投递回收。
 *
 * 身份必须在删成员**之前**解析：`.codeid` 侧车本身是包的成员，卸载会删掉它，事后再解析就只能读到
 * 「侧车缺失」而退回磁盘身份（= 又一次写错键）。身份不可判时 [queueStorageReclaimAfterUninstall]
 * 只留日志、不投递。
 *
 * @return true = 盘上成员删净（与 [ModManageUninstall.uninstall] 同口径）
 */
internal fun uninstallModAndQueueReclaim(rt2Dir: File, diskPackageId: String): Boolean {
    val runtimeId = ModTrustStore.resolveRuntimeId(rt2Dir, diskPackageId)
    val removed = runCatching {
        ModManageUninstall.uninstall(rt2Dir, diskPackageId)
    }.getOrDefault(false)
    if (removed) {
        queueStorageReclaimAfterUninstall(runtimeId, diskPackageId)
    }
    return removed
}

/**
 * 卸载成功后的 storage 段回收投递：键 = 运行期身份（与 `storage.activate` 同源，task-199）。
 *
 * [runtimeId] 由调用方在删盘上成员**之前**解析（`.codeid` 侧车本身会被卸载删掉）；null = 身份不可判
 * （侧车损坏 / 包名非法），此时宁可不投递也不猜一个 id —— 段留在内存容器里，抓取侧按持久台账让它
 * 在下次存档减掉，只是本局内存不即时摘除。
 */
private fun queueStorageReclaimAfterUninstall(runtimeId: String?, diskPackageId: String) {
    if (runtimeId == null) {
        // 纯 JVM 单测没有 mock android.util.Log；日志失败不得影响卸载主流程（同 ModManageUninstall 口径）。
        runCatching {
            Log.w(
                SETTINGS_LOG_TAG,
                "卸载后 storage 回收未投递：运行期身份不可判（侧车损坏或包名非法）pkg=$diskPackageId",
            )
        }
        return
    }
    ModStorageReclaimRequests.request(runtimeId)
}

/**
 * 信任 / 撤销落盘失败时的上屏文案：身份侧车坏了要说「身份信息损坏（补信任也没用）」；
 * 其余失败（非法包名 / 无产物 / 写盘失败）仍走「没能更新信任记录」。
 *
 * 只有 [TrustMarkerBlockReason.IDENTITY_CORRUPT] 才把玩家指向「重新导入」——包名形态非法是内部
 * 契约问题，给玩家看「身份损坏」会指错修复动作。null = 落盘成功，调用方按寒暄文案上屏。
 */
internal fun modTrustFailureCopy(
    text: SettingsStrings,
    written: Boolean,
    blocked: TrustMarkerBlockReason?,
): String? = when {
    written -> null
    blocked == TrustMarkerBlockReason.IDENTITY_CORRUPT -> text.modCodeStateCorrupt
    else -> text.modTrustWriteFailed
}

/** 信任 / 撤销落盘后的 toast：成功用寒暄文案，失败按 [failure] 上屏（null = 成功）。 */
private fun showModTrustToast(context: Context, okMessage: String, failure: String?) {
    val message = failure ?: okMessage
    Toast.makeText(context, message, if (failure == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
}

/**
 * 单个 Mod 条目：标题行（点击展开详情）+ 启停开关；展开后追加详情与卸载入口。
 *
 * 展开态是行内 `remember`（key = packageId）：重扫列表不会把玩家展开的那条收回去，卸载/重装
 * 换了包名则自然重建。[version] 是版本读数（VS-1）：muted 小字显示该包声明的 API 版本与（仅在
 * 装载门真判过时）数据版本缺口；声明版本高于引擎时换一行 Warn 文案说清两侧版本号。
 * 缺依赖行（ModDX2-C）同区 Warn 色：前置包不在场是盘面静态事实，停用的包照标。
 */
@Composable
private fun ModManageRow(
    text: SettingsStrings,
    mod: InstalledMod,
    failure: String?,
    version: ModVersionRow?,
    actions: ModManageActions,
) {
    var expanded by remember(mod.packageId) { mutableStateOf(false) }
    val pressSource = remember { MutableInteractionSource() }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        // 整行（读数 + 箭头）是一块热区，与口上包管理行同形：热区 ≥44dp、按下叠色，
        // 开关自己吃掉点按，点别处才展开/收起。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ShellSpace.RowMinHeight)
                .background(ShellPress.tint(pressSource, UiColors.Accent))
                .clickable(
                    interactionSource = pressSource,
                    indication = null,
                ) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ModManageRowInfo(text = text, mod = mod, failure = failure, version = version, actions = actions)
            }
            ExpandChevron(expanded = expanded, modifier = Modifier.padding(end = 8.dp))
            Switch(
                checked = mod.enabled,
                onCheckedChange = { enabled -> actions.onToggle(mod, enabled) },
                colors = SwitchDefaults.colors(uncheckedTrackColor = UiColors.CardAlt),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = ShellMotion.rowEnter(),
            exit = ShellMotion.rowExit(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(
                    color = UiColors.Border,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                ModManageDetails(text, mod, actions)
            }
        }
    }
}

/** 行内读数（标题、来源、版本、代码态、缺依赖、装载失败、重装入入口），与开关同排。 */
@Composable
private fun ModManageRowInfo(
    text: SettingsStrings,
    mod: InstalledMod,
    failure: String?,
    version: ModVersionRow?,
    actions: ModManageActions,
) {
    Text(mod.title, color = UiColors.Text, style = ShellType.Body)
    Text(modManageDetail(text, mod), color = UiColors.TextMuted, style = ShellType.Label)
    val versionLine = version?.let { modVersionLine(text, it) }
    if (versionLine != null) {
        Text(versionLine, color = UiColors.TextMuted, style = ShellType.Label)
    }
    // task-179：装了第三方代码却没生效时，在这一行就说清为什么（四态；没有代码产物
    // 整行不出，普通口上包不多一行噪音）。问题态用 Warn 色，与装载失败 / 缺依赖同色系。
    ModCodeStateRow(text = text, mod = mod, actions = actions)
    val incompatibleLine = version?.let { modVersionIncompatibleLine(text, it) }
    if (incompatibleLine != null) {
        Text(
            text = incompatibleLine,
            color = UiColors.Warn,
            style = ShellType.Label,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    // 缺依赖体检（ModDX2-C）：盘面静态事实，不随启停变；与「上次启动时装载失败」
    // 分开报 —— 装完包没开游戏也能看到还差哪个前置（对标 LOOT/SMAPI 交互警告）。
    if (mod.missingRequires.isNotEmpty()) {
        Text(
            text = text.modMissingRequires(
                mod.missingRequires.joinToString(text.listSeparator),
            ),
            color = UiColors.Warn,
            style = ShellType.Label,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    if (failure != null) ModManageFailureLine(text, mod.title, failure)
    // 回滚态重装入口（Lane DZ）：只对「内置载体 + 本会话装载失败」亮。
    // 点击只投递请求（下一回合边界由会话执行），不在这里碰会话引用。
    if (reinstallRetryable(mod.source, failure)) {
        ModReinstallRetryButton(text) { actions.onRetryInstall(mod) }
    }
}

/**
 * task-179：「代码」读数行（未开启 / 待信任 / 已启用 / 身份信息损坏）。
 *
 * 取数与判定都在 [ModCodeGateHint]（与装载侧同源），这里只负责一行小字；没有代码产物的包
 * 整行不出（[ModCodePackState] 为 null）。问题态用 Warn 色，已启用用次要色。
 */
@Composable
private fun ModCodeStateRow(text: SettingsStrings, mod: InstalledMod, actions: ModManageActions) {
    val state = actions.codeState(mod) ?: return
    ModCodeDetailLine(text = modCodeStateLine(text, state), warn = state.hint != null)
}

/**
 * 「上次启动时装载失败」那行 Warn 小字（把行内空间让给重试入口；文案仍是 [SettingsStrings.modManageFailureLine]）。
 */
@Composable
private fun ModManageFailureLine(text: SettingsStrings, modName: String, failure: String) {
    Text(
        text = text.modManageFailureLine(modName, failure),
        color = UiColors.Warn,
        style = ShellType.Label,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * 回滚态重装的按钮（Lane DZ）：点击只把 packageId 投进设置 → 会话的数据队列
 * （[ModReinstallRequests]），真正的 `host.install` 在 VM 线程页边界执行。
 */
@Composable
private fun ModReinstallRetryButton(text: SettingsStrings, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = ShellSpace.RowMinHeight),
    ) {
        Text(text.modManageRetryInstall, color = UiColors.Accent, style = ShellType.Caption)
    }
}

/**
 * 展开后的详情：包名 / 版本 / 作者 / 来源 / 说明全文 / `.erbm` 配对状态 / 卸载入口。
 *
 * 内置载体（[ModSource.BUILTIN]）随游戏本体提供、删了没法从游戏内恢复，所以只给说明不给卸载按钮；
 * 导入包与数据包给「卸载」（卡里走二次确认）。
 */
@Composable
private fun ModManageDetails(
    text: SettingsStrings,
    mod: InstalledMod,
    actions: ModManageActions,
) {
    var confirmingUninstall by remember(mod.packageId) { mutableStateOf(false) }
    ModManageDetailLine(text, text.modManageDetailPackageId, mod.packageId)
    if (mod.version.isNotBlank()) {
        ModManageDetailLine(text, text.modManageDetailVersion, mod.version)
    }
    if (mod.author.isNotBlank()) {
        ModManageDetailLine(text, text.modManageDetailAuthor, mod.author)
    }
    ModManageDetailLine(text, text.modManageDetailSource, modSourceLabel(text, mod.source))
    Text(
        text = text.modManageDetailDescription,
        color = UiColors.TextMuted,
        style = ShellType.Label,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        text = mod.description.ifBlank { text.modManageDescriptionEmpty },
        color = UiColors.Text,
        style = ShellType.Caption,
    )
    Text(
        text = modModuleStatus(text, mod),
        color = if (mod.missingModule) UiColors.Warn else UiColors.TextMuted,
        style = ShellType.Label,
        modifier = Modifier.padding(top = 4.dp),
    )
    ModSettingsSection(text, mod)
    // 每包日志回看入口（MODDX4-G8）：本会话该包最近发出的日志（诊断面，与上方失败行同族）。
    ModLogViewerSection(mod)
    // 重编入口只给「导入包」（`kojo-*`）：内置/数据包没有 `.sources` 留档源，无可重编。
    if (mod.source == ModSource.IMPORTED) {
        ModRecompileSection(text, mod, actions.recompileCopy)
    }
    if (mod.source == ModSource.BUILTIN) {
        Text(
            text = text.modManageUninstallBuiltin,
            color = UiColors.TextMuted,
            style = ShellType.Label,
            modifier = Modifier.padding(top = 6.dp),
        )
    } else {
        TextButton(onClick = { confirmingUninstall = true }) {
            Text(text.modManageUninstall, color = UiColors.Warn, style = ShellType.Tab)
        }
    }
    if (confirmingUninstall) {
        // 预览只在确认框打开时现取（Lane uninstallpreview）：读盘只读、不写盘、不删文件。
        val impactLines = remember(mod.packageId) { actions.uninstallImpact(mod) }
        ModUninstallDialog(
            text = text,
            modTitle = mod.title,
            impactLines = impactLines,
            onConfirm = {
                confirmingUninstall = false
                actions.onUninstall(mod)
            },
            onDismiss = { confirmingUninstall = false },
        )
    }
}

/**
 * 每包日志回看小节（MODDX4-G8）：入口按钮展开本会话该包最近发出的日志（core 侧每包有界环
 * 的只读快照，经 [ModRuntimeLogViewerEntry] 现取宿主聚合缝 `ModRuntimeHost.recentLogs`）。
 *
 * 快照语义：每次展开/再展开那一拍现读——环在页面停留期间继续写入不改变已取得的快照
 * （G2 环 KDoc 的只读副本口径），玩家想看最新就再点一次入口。无会话 / 未接线 / 无日志
 * ⇒ 诚实空态文案（fail-closed，不造假数据）；空态对玩家是同一件事：当前没有可回看的日志。
 *
 * 文案取 [ModUiCopy]（mod 域三语表，AGENTS §6；`SettingsStrings` 属另一 lane 禁改），
 * 语言单路 `UiPrefs.settingsLanguage(context)`（照 MainActivity 浮层 `ModUiCopy.of(...)` 的
 * 既有取法，只在 UI 线程读）；日志正文是取证面，不进文案表（AGENTS §6 口径）。
 * 本节不进 `SettingsScreen` 的 60+ 形参签名、不改 [ModManageCard] 的 5 参签名——
 * 自装配读 [ModRuntimeLogViewerEntry]，与 ModTrustCard / ModCodeOverviewCard 同范式。
 */
@Composable
private fun ModLogViewerSection(mod: InstalledMod) {
    val context = LocalContext.current
    val copy = ModUiCopy.of(UiPrefs.settingsLanguage(context))
    var expanded by remember(mod.packageId) { mutableStateOf(false) }
    var revision by remember(mod.packageId) { mutableIntStateOf(0) }
    TextButton(onClick = {
        expanded = !expanded
        revision++
    }) {
        Text(copy.logViewerLabel, color = UiColors.Accent, style = ShellType.Tab)
    }
    if (!expanded) return
    val lines = remember(mod.packageId, revision) { ModRuntimeLogViewerEntry.read(mod.packageId) }
    if (lines.isEmpty()) {
        Text(copy.logViewerEmpty, color = UiColors.TextMuted, style = ShellType.Label)
    } else {
        Text(
            text = lines.joinToString("\n"),
            color = UiColors.Text,
            style = ShellType.Overline,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 2.dp),
        )
        TextButton(onClick = { copyModLogs(context, copy, lines) }) {
            Text(copy.logViewerCopy, color = UiColors.Accent, style = ShellType.Tab)
        }
    }
}

/** 把最近日志快照整段写进剪贴板并提示（照 [copyModDiagnostics] 同口径，标签独立便于玩家回找）。 */
private fun copyModLogs(context: Context, copy: ModUiCopy, lines: List<String>) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("eratw-mod-recent-logs", lines.joinToString("\n")))
    Toast.makeText(context, copy.logViewerCopied, Toast.LENGTH_SHORT).show()
}

/**
 * 卸载二次确认对话框（防玩家误删导入包）：确认才真删盘上三件套 + 停用标记。
 *
 * 状态由调用方持有（每条详情一个 `confirmingUninstall`），本函数只画对话框。
 * [impactLines] 是卸载会删掉的各类读数（只读预览，首行小标题由文案表出），插在正文下面。
 */
@Composable
private fun ModUninstallDialog(
    text: SettingsStrings,
    modTitle: String,
    impactLines: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ShellAlertDialog(
        title = text.modManageUninstallConfirmTitle,
        onDismissRequest = onDismiss,
        actions = ShellDialogActions(
            confirmLabel = text.modManageUninstall,
            onConfirm = onConfirm,
            confirmTone = EratwInk.warn(),
            dismissLabel = text.cancelText,
            onDismiss = onDismiss,
        ),
    ) {
        Text(
            text = text.modManageUninstallConfirmBody(modTitle),
            color = EratwInk.text(),
            style = ShellType.Body,
        )
        impactLines.forEach { line ->
            Text(
                text = line,
                color = EratwInk.textMuted(),
                style = ShellType.Caption,
            )
        }
    }
}

/**
 * 导入包的重编入口：「重新编译」按钮 + 三态提示（成功 / 无变化 / 失败）。
 *
 * 状态全在本段（`remember(mod.packageId)`），不进 `SettingsScreen` 的 60+ 形参；编译在
 * `Dispatchers.IO` 上跑（[ModRecompiler.recompile] 全程编译器 + 盘面 IO），结果回主线程
 * 落提示。[copy] 是壳层在起协程前取好的语言口径（纯层不碰 SharedPreferences，照
 * `UpdateCopy` 的既有约束）。重编只换盘上 `.erbm` / `.manifest`，不改变列表读数
 * （启用态 / 模块在场），所以成功后不重扫 [ModManageScan]。
 */
@Composable
private fun ModRecompileSection(text: SettingsStrings, mod: InstalledMod, copy: ModImportCopy) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gameRoot = remember { GameStorage(context).gameRoot() }
    var busy by remember(mod.packageId) { mutableStateOf(false) }
    var notice by remember(mod.packageId) { mutableStateOf<String?>(null) }
    TextButton(
        enabled = !busy,
        onClick = {
            busy = true
            notice = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ModRecompiler.recompile(gameRoot, mod.packageId, copy)
                }
                busy = false
                notice = when (result) {
                    is RecompileResult.Updated -> text.modManageRecompileDone(mod.title)
                    is RecompileResult.Skipped ->
                        if (result.reason == ModRecompiler.SKIP_REASON_UP_TO_DATE) {
                            text.modManageRecompileUpToDate
                        } else {
                            text.modManageRecompileFailed(mod.title, result.reason)
                        }
                    is RecompileResult.Failed -> text.modManageRecompileFailed(mod.title, result.message)
                }
            }
        },
    ) {
        Text(
            text = if (busy) text.modManageRecompileBusy else text.modManageRecompile,
            color = UiColors.Text,
            style = ShellType.Tab,
        )
    }
    notice?.let {
        Text(text = it, color = UiColors.TextMuted, style = ShellType.Label)
    }
    Text(
        text = text.modManageRecompileHint,
        color = UiColors.TextMuted,
        style = ShellType.Label,
    )
}

/** 详情单行：标签 + 值（统一字号/颜色，避免组合代码里出现超长行）。 */
@Composable
private fun ModManageDetailLine(text: SettingsStrings, label: String, value: String) {
    Text(
        text = text.modManageDetailLine(label, value),
        color = UiColors.Text,
        style = ShellType.Caption,
    )
}

/** 装载失败小节：标题 + 逐条「标题：异常摘要」（空表不占位）。 */
@Composable
private fun ModManageFailures(
    text: SettingsStrings,
    failures: Map<String, String>,
    installed: List<InstalledMod>,
) {
    if (failures.isEmpty()) return
    val titles = installed.associate { it.packageId to it.title }
    Text(
        text = text.modManageFailureHeading,
        color = UiColors.Warn,
        style = ShellType.Label,
        modifier = Modifier.padding(top = 8.dp),
    )
    for ((packageId, reason) in failures) {
        Text(
            text = text.modManageFailureLine(titles[packageId] ?: packageId, reason),
            color = UiColors.TextMuted,
            style = ShellType.Label,
        )
    }
}

/** 坏 manifest 小字：前缀 + 文件名/原因列表（扫描结果，不是装载结论）。 */
@Composable
private fun ModManageBrokenLine(text: SettingsStrings, skipped: List<String>) {
    if (skipped.isEmpty()) return
    Text(
        text = text.modManageBrokenPrefix + skipped.joinToString("、"),
        color = UiColors.TextMuted,
        style = ShellType.Label,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * per-mod 设置小节（RT2 per-mod 配置）：作者在 manifest 里声明一次，这里自动出控件。
 *
 * 值来源 = 设备侧 `mods/rt2/<packageId>.settings`（[ModSettingsStore]）；读侧对「没改过」与
 * 「值坏了 / 文件坏了」统一回退声明默认值（[ModSettingsSnapshot.resolveText]），所以坏文件
 * 只表现为「显示默认」，不会崩、不会误报。
 *
 * 控件口径：BOOL 直接拨开关（与列表启停同观感）；INT / STR / ENUM 点行开弹窗编辑 ——
 * 页面既有的编辑交互就是弹窗，不把 OutlinedTextField 塞进瀑布网格（焦点/键盘会打架）。
 * 写盘成功才刷新本地副本；失败只提示，盘上原值不变。
 *
 * ## 停用包不出编辑面（task-130 审计）
 *
 * `mod.enabled == false` 时直接早退：停用包的详情里不显示 per-mod 设置区。判据取设置页扫描
 * 出来的真源字段（[InstalledMod.enabled]，扫描处读 `ModEnablement.isDisabled`），本文件不另读
 * `.off` marker，两处口径同源、以后改启停语义不会分叉。理由两条：
 *
 * 1. 停用包的设置在本局**兑现不了**：装配期 `ModSettingsStore.snapshotsFor` 按停用标记过滤
 *    （`ModSettingsStore.kt:203` 的 `enabledOnly`），本会话中途停用后保存的那一次热通知 rebind
 *    也被同一过滤跳过（`ModRuntimeAssembly.kt:1532-1533`）。此时照常给编辑面，保存 Toast
 *    （[SettingsStrings.modSettingSaved]）与下方提示（[SettingsStrings.modSettingHint]）承诺的
 *    「下一次回合推进时生效、不用重开」就是假承诺。
 * 2. 不在引擎侧改 `notifySettingsChanged`：让刚停用的 Mod 继续吃新设置语义别扭，而且判定要跨
 *    装配期与会话期两层改，改动面远大于这里一条早退。
 *
 * 已保存值**不丢**：[ModSettingsStore.saveValues] 不检查启用状态（`ModSettingsStore.kt:159-164`），
 * 历史值留在 `mods/rt2/<packageId>.settings`；删掉停用标记重新启用后本小节照旧渲染并读到那份值。
 * 早退放在状态声明之前，重新启用时组合节点重建会重新从盘上读值，不会拿停用期的旧副本。
 */
@Composable
private fun ModSettingsSection(
    text: SettingsStrings,
    mod: InstalledMod,
) {
    if (mod.settings.isEmpty()) return
    if (!mod.enabled) return
    val context = LocalContext.current
    // 值落点与 manifest 同目录（mods/rt2），由 gameRoot 派生：不新增调用面形参。
    val rt2Dir = remember { ModEnablement.rt2Dir(GameStorage(context).gameRoot()) }
    var values by remember(mod.packageId) { mutableStateOf(ModSettingsStore.read(rt2Dir, mod.packageId)) }
    var editing by remember(mod.packageId) { mutableStateOf<SettingDeclaration?>(null) }
    val snapshot = remember(mod.packageId, mod.settings, values) {
        ModSettingsSnapshot(mod.packageId, mod.settings, values)
    }
    val persist: (String, String) -> Unit = { key, value ->
        val ok = ModSettingsStore.saveValues(rt2Dir, mod.packageId, mapOf(key to value))
        if (ok) {
            // 重读盘上真值（而不是乐观写本地）：坏值/并发写都以盘为准。
            values = ModSettingsStore.read(rt2Dir, mod.packageId)
            // 热通知（DX-A2）：写成功才投递，投递只入队不调 mod —— 兑现唯一在 VM 线程页边界
            // （GameHost.onTurnBoundary drain → ModRuntimeSession.notifySettingsChanged）。
            // 键来自本节声明明细行循环，结构性必是声明键；终审在会话侧按会话内声明快照过滤。
            ModSettingsNotifyChannel.enqueue(mod.packageId, listOf(key))
            Toast.makeText(context, text.modSettingSaved, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, text.modSettingSaveFailed, Toast.LENGTH_LONG).show()
        }
    }
    Text(
        text = text.modSettingSectionTitle,
        color = UiColors.Text,
        style = ShellType.Caption,
        modifier = Modifier.padding(top = 8.dp),
    )
    for (decl in mod.settings) {
        ModSettingRow(
            text = text,
            decl = decl,
            value = snapshot.resolveText(decl.key),
            onEdit = { editing = decl },
            onPersist = persist,
        )
    }
    Text(
        text = text.modSettingHint,
        color = UiColors.TextMuted,
        style = ShellType.Label,
        modifier = Modifier.padding(top = 4.dp),
    )
    ModSettingResetControl(text, rt2Dir, mod.packageId) { values = it }
    editing?.let { decl ->
        ModSettingEditDialog(
            text = text,
            decl = decl,
            initial = snapshot.resolveText(decl.key),
            onDismiss = { editing = null },
            onConfirm = { raw ->
                editing = null
                persist(decl.key, raw)
            },
        )
    }
}

/**
 * 单个设置项的展示行：BOOL 直接拨开关，INT / STR / ENUM 点行开编辑弹窗。
 *
 * 行内只做「显示 + 触发编辑」，值合法性与写盘全在 [ModSettingsSection]，所以坏值显示的是
 * 回退后的默认值（与 mod 侧读到的完全一致，不出现「设置页显示 X、mod 读到 Y」的分叉）。
 */
@Composable
private fun ModSettingRow(
    text: SettingsStrings,
    decl: SettingDeclaration,
    value: String,
    onEdit: () -> Unit,
    onPersist: (String, String) -> Unit,
) {
    val label = decl.title.ifBlank { decl.key }
    if (decl is BoolSettingDeclaration) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = UiColors.Text, style = ShellType.Caption)
                ModSettingDescription(decl)
            }
            Switch(
                checked = value == "true",
                onCheckedChange = { checked -> onPersist(decl.key, if (checked) "true" else "false") },
                colors = SwitchDefaults.colors(uncheckedTrackColor = UiColors.CardAlt),
            )
        }
        return
    }
    val pressSource = remember { MutableInteractionSource() }
    Text(
        text = text.modSettingLine(label, value),
        color = UiColors.Text,
        style = ShellType.Caption,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ShellSpace.RowMinHeight)
            .background(ShellPress.tint(pressSource, UiColors.Accent))
            .clickable(
                interactionSource = pressSource,
                indication = null,
                onClick = onEdit,
            )
            .padding(vertical = 4.dp),
        maxLines = 2,
    )
    ModSettingDescription(decl)
}

/** 设置项说明正文（作者填；空则不占行）。 */
@Composable
private fun ModSettingDescription(decl: SettingDeclaration) {
    if (decl.description.isBlank()) return
    Text(decl.description, color = UiColors.TextMuted, style = ShellType.Label)
}

/**
 * 单个设置项的编辑弹窗：INT（数字输入 + 越界 clamp 提示）/ STR（单行文本）/ ENUM（候选列表）。
 *
 * 矩形口径与页面其它弹窗一致（RoundedCornerShape(14.dp)、[EratwInk.raised]）。
 * INT 的 clamp 是「先修正显示值再让玩家确认」：弹窗里把 `raw` 改成边界值并给提示，避免
 * 「提示修正了但盘上还是旧值」这种口径分叉。正文与校验各自拆成独立函数，弹窗只做装配。
 */
@Composable
private fun ModSettingEditDialog(
    text: SettingsStrings,
    decl: SettingDeclaration,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var raw by remember(decl.key) { mutableStateOf(initial) }
    var notice by remember(decl.key) { mutableStateOf<String?>(null) }
    ShellAlertDialog(
        title = decl.title.ifBlank { decl.key },
        onDismissRequest = onDismiss,
        actions = ShellDialogActions(
            confirmLabel = text.okText,
            onConfirm = {
                when (val outcome = validateSettingEdit(text, decl, raw)) {
                    is SettingEditOutcome.Commit -> onConfirm(outcome.value)
                    is SettingEditOutcome.Fix -> {
                        raw = outcome.raw
                        notice = outcome.notice
                    }
                }
            },
            dismissLabel = text.cancelText,
            onDismiss = onDismiss,
        ),
    ) {
        ModSettingEditBody(
            decl = decl,
            raw = raw,
            notice = notice,
            onRawChange = { typed ->
                raw = typed
                notice = null
            },
        )
    }
}

/** 编辑弹窗正文：作者说明 + 类型专属输入控件 + 校验提示（空说明不占行）。 */
@Composable
private fun ModSettingEditBody(
    decl: SettingDeclaration,
    raw: String,
    notice: String?,
    onRawChange: (String) -> Unit,
) {
    Column {
        if (decl.description.isNotBlank()) {
            Text(decl.description, color = EratwInk.textMuted(), style = ShellType.Caption)
        }
        if (decl is EnumSettingDeclaration) {
            ModSettingEnumChoices(decl, raw, onRawChange)
        } else {
            ModSettingTextField(decl, raw, onRawChange)
        }
        notice?.let { message ->
            Text(
                text = message,
                color = EratwInk.warn(),
                style = ShellType.Label,
                modifier = Modifier.padding(top = ShellSpace.CardGap),
            )
        }
    }
}

/** ENUM 候选项：整列按钮，选中项用正文色、其余弱化。 */
@Composable
private fun ModSettingEnumChoices(
    decl: EnumSettingDeclaration,
    raw: String,
    onRawChange: (String) -> Unit,
) {
    for (candidate in decl.candidates) {
        TextButton(onClick = { onRawChange(candidate) }) {
            Text(
                text = candidate,
                // 选中项用正文色、其余次级色；选中同时回填输入框，读屏侧不只有颜色一条路。
                color = if (candidate == raw) EratwInk.text() else EratwInk.textMuted(),
                style = ShellType.Tab,
            )
        }
    }
}

/** INT 用数字键盘、其余用文本键盘，单行输入。 */
@Composable
private fun ModSettingTextField(
    decl: SettingDeclaration,
    raw: String,
    onRawChange: (String) -> Unit,
) {
    val keyboardType = if (decl is IntSettingDeclaration) KeyboardType.Number else KeyboardType.Text
    OutlinedTextField(
        value = raw,
        onValueChange = onRawChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        textStyle = ShellType.Body,
        colors = ShellField.outlined(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/** 编辑结果：直接提交，或先修正显示值再等玩家确认。 */
private sealed class SettingEditOutcome {
    data class Commit(val value: String) : SettingEditOutcome()
    data class Fix(val raw: String, val notice: String) : SettingEditOutcome()
}

/**
 * 校验一次编辑：INT 非数字报「请输入整数」、越界先修正再确认，其余类型原样提交。
 *
 * 越界修正**不**直接落盘：把修正值写回输入框、给提示，玩家再点一次确认才持久化。
 */
private fun validateSettingEdit(
    text: SettingsStrings,
    decl: SettingDeclaration,
    raw: String,
): SettingEditOutcome {
    if (decl !is IntSettingDeclaration) return SettingEditOutcome.Commit(raw)
    val parsed = raw.trim().toLongOrNull()
    return when {
        parsed == null -> SettingEditOutcome.Fix(raw, text.modSettingInvalidInt)
        else -> {
            val clamped = parsed.coerceIn(decl.min ?: Long.MIN_VALUE, decl.max ?: Long.MAX_VALUE)
            if (clamped == parsed) {
                SettingEditOutcome.Commit(clamped.toString())
            } else {
                SettingEditOutcome.Fix(clamped.toString(), text.modSettingClamped(clamped.toString()))
            }
        }
    }
}

/**
 * 本包设置「恢复默认」控件：按钮 + 二次确认弹窗 + Toast；状态（是否在确认）全在内部 `remember`。
 *
 * ## 为什么下沉到这里而不是给 [SettingsScreen] 加形参
 * 设置页 `SettingsScreen` 已有 60+ 形参，本控件只需要 `text` / 落点目录 / 包名 / 一个「值已重读」
 * 回调，全部可从 [ModSettingsSection] 的局部状态现取；把确认态挂在页面级只会再加两条形参并让
 * 「哪一包在确认」与列表行脱节。
 *
 * ## 语义（与 [ModSettingsStore.saveValues] 同一条热生效口径）
 * 确认后**先读旧键集合，再整包删侧车**（缺文件 = 已是默认态）；删除成功才把旧键投递
 * [ModSettingsNotifyChannel]（VM 线程页边界 drain → 下一拍生效），并让调用方重读盘上值刷新
 * 本节显示。删除失败盘上原值不动，只提示失败。
 *
 * @param rt2Dir 本包设置侧车所在目录（`mods/rt2`）
 * @param packageId 目标包（控件只作用于本行所属包）
 * @param onValuesReloaded 恢复成功后回调，参数 = 重读盘上值（调用方据此重建快照）
 */
@Composable
private fun ModSettingResetControl(
    text: SettingsStrings,
    rt2Dir: File,
    packageId: String,
    onValuesReloaded: (Map<String, String>) -> Unit,
) {
    val context = LocalContext.current
    var confirming by remember(packageId) { mutableStateOf(false) }
    Text(
        text = text.modSettingResetButton,
        color = UiColors.Warn,
        style = ShellType.Tab,
        modifier = Modifier
            .padding(top = 6.dp)
            .clickable { confirming = true },
    )
    if (!confirming) return
    ShellAlertDialog(
        title = text.modSettingResetConfirmTitle,
        onDismissRequest = { confirming = false },
        actions = ShellDialogActions(
            confirmLabel = text.modSettingResetConfirmOk,
            confirmTone = EratwInk.warn(),
            dismissLabel = text.cancelText,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                val oldKeys = ModSettingsStore.read(rt2Dir, packageId).keys.toList()
                if (!ModSettingsStore.resetToDefaults(rt2Dir, packageId)) {
                    Toast.makeText(context, text.modSettingResetFailed, Toast.LENGTH_LONG).show()
                } else {
                    onValuesReloaded(ModSettingsStore.read(rt2Dir, packageId))
                    if (oldKeys.isNotEmpty()) {
                        ModSettingsNotifyChannel.enqueue(packageId, oldKeys)
                    }
                    Toast.makeText(context, text.modSettingResetDone, Toast.LENGTH_SHORT).show()
                }
            },
        ),
    ) {
        Text(text = text.modSettingResetConfirmBody, color = EratwInk.textMuted(), style = ShellType.Body)
    }
}

/** 详情里的模块配对读数：缺 `.erbm` = 半成品（导入包），有 = 已配对，没有也不该有 = 内置载体。 */
private fun modModuleStatus(text: SettingsStrings, mod: InstalledMod): String = when {
    mod.missingModule -> text.modManageModuleMissing
    mod.hasModule -> text.modManageModulePaired
    else -> text.modManageModuleBuiltin
}

/** 来源标签（列表小字与详情共用，避免两处映射漂移）。 */
private fun modSourceLabel(text: SettingsStrings, source: ModSource): String = when (source) {
    ModSource.BUILTIN -> text.modManageSourceBuiltin
    ModSource.DATA_PACKAGE -> text.modManageSourceData
    ModSource.IMPORTED -> text.modManageSourceImported
}

/**
 * Mod 运行时诊断卡（RT2-GATE-OPEN，2026-11-17）：**release 玩家面**的只读摘要 + 复制。
 *
 * 与 `DevToolsHost.modgate`（仅 debug 包）共用同一读数源 [ModGateDiagnostics]；四项读数
 * = 总闸 / 本局 Mod 运行时 / Mod 计数 / 存档侧车。摘要**没有任何写入面**：不写偏好、不改
 * 装配状态、不删文件，取不到就按空/未装配降级。
 *
 * 独立成卡（而不塞进 [ModManageCard]）的理由：诊断读数不属于「已安装的 Mod」这一件事；
 * 混在一起会同时撑爆那张卡的形参表（detekt LongParameterList）与行长（LongMethod）。
 * 文案全取 `SettingsStrings` 三语表，不内联玩家可见字面量。
 */
@Composable
private fun ModDiagnosticsCard(
    text: SettingsStrings,
    diagnostics: ModGateDiagnostics.Snapshot,
    onCopy: () -> Unit,
) {
    SettingsCard {
        SectionTitle(text.modDiagnosticsTitle)
        Text(
            text = text.modDiagnosticsHint,
            color = UiColors.TextMuted,
            style = ShellType.Label,
        )
        for (line in modDiagnosticsLines(text, diagnostics)) {
            Text(
                text = line,
                color = UiColors.Text,
                style = ShellType.Caption,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        OutlinedButton(
            onClick = onCopy,
            shape = ShellShape.Chip,
            border = BorderStroke(1.dp, UiColors.Border),
            modifier = Modifier
                .padding(top = 8.dp)
                .heightIn(min = ShellSpace.TouchMin),
        ) {
            Text(text.modDiagnosticsCopy, color = UiColors.Text, style = ShellType.Body)
        }
    }
}

/**
 * 诊断摘要四行（屏显与复制共用一份拼装：两处不许各写一套口径）。
 *
 * 语言只取调用方传入的 [text]（壳层单路），不在这里判 Locale。
 */
private fun modDiagnosticsLines(
    text: SettingsStrings,
    diagnostics: ModGateDiagnostics.Snapshot,
): List<String> = listOf(
    if (diagnostics.gate) text.modDiagnosticsGateOn else text.modDiagnosticsGateOff,
    if (diagnostics.sessionAttached) {
        text.modDiagnosticsSessionOn
    } else {
        text.modDiagnosticsSessionOff
    },
    text.modDiagnosticsMods(diagnostics.installed, diagnostics.enabled, diagnostics.unreadable),
    text.modDiagnosticsSidecars(diagnostics.sidecars, diagnostics.sidecarBytes),
)

/**
 * 「复制诊断信息」：把 [modDiagnosticsLines] 的同一份拼装写进剪贴板并提示。
 *
 * 独立成函数让 `SettingsScreen` 的 item 块只留一行声明式调用（复制的副作用不散在 UI 树里）；
 * 文案仍只取传入的 [text]（壳层单路），[context] 只用于取系统剪贴板与 Toast。
 */
private fun copyModDiagnostics(
    context: Context,
    text: SettingsStrings,
    diagnostics: ModGateDiagnostics.Snapshot,
) {
    val payload = modDiagnosticsLines(text, diagnostics).joinToString("\n")
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("eratw-mod-diagnostics", payload))
    Toast.makeText(context, text.modDiagnosticsCopied, Toast.LENGTH_SHORT).show()
}

/**
 * 把任意文本写进剪贴板并 Toast 确认（KI-4 报错页/成功页的复制口；照 [copyModDiagnostics]
 * 同口径，标签独立便于玩家回找）。[toastText] 由调用点从当前语言档取好传入
 * （[ModImportCopy.copyDoneToast]），本函数不判语言。
 */
private fun copyTextToClipboard(activity: SettingsActivity, payload: String, toastText: String) {
    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("eratw-kojo-report", payload))
    Toast.makeText(activity, toastText, Toast.LENGTH_SHORT).show()
}

/**
 * 「导出诊断」（task-172）：点一次把 [buildModDiagnosticReport] 拼好的只读快照弹成等宽可滚文本，
 * 弹窗里可整段复制。
 *
 * 体检面拆分：拼装是纯 JVM 函数（单测覆盖），取数集中到 [collectModDiagnosticSnapshot]，
 * 本 composable 只留 remember + 弹窗壳——照 ModTrustCard / ModCodeOverviewCard 的自装配范式，
 * 不给 `SettingsScreen` 那 60+ 个形参加参，也不改 [ModManageCard] 的 5 参签名。
 */
@Composable
private fun ModDiagnosticExportCard(
    text: SettingsStrings,
    scan: ModManageScan,
    failures: Map<String, String>,
) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    SettingsCard {
        Text(text.modDiagnosticExportHint, color = UiColors.TextMuted, style = ShellType.Label)
        TextButton(
            onClick = {
                val input = collectModDiagnosticSnapshot(context, scan, failures)
                report = buildModDiagnosticReport(input)
            },
        ) {
            Text(text.modDiagnosticExport, color = UiColors.Accent, style = ShellType.Tab)
        }
    }
    val snapshot = report
    if (snapshot != null) {
        ModDiagnosticReportDialog(text = text, report = snapshot, onDismiss = { report = null })
    }
}

/**
 * 玩家授权诊断通道开关的去向（纯函数，JVM 可测）：开启方向必须先过二次确认，关方向直接落盘。
 *
 * 拆成纯函数而不是把分支散在 composable 里，照 [modDexLoaderToggleAction] 的口径：
 * 「开启必须确认」与「取消零写点」这两条安全性质要能在桌面单测里逐条钉死，而不是只靠源码哨兵。
 */
internal enum class PlayerDiagToggleAction { ASK_CONFIRM, WRITE, IGNORE }

/** [requested] 是玩家拨到的目标态，[confirmed] 是确认弹窗的结论（null = 尚未询问）。 */
internal fun playerDiagToggleAction(
    requested: Boolean,
    confirmed: Boolean?,
): PlayerDiagToggleAction = when {
    confirmed == false -> PlayerDiagToggleAction.IGNORE
    confirmed == true -> PlayerDiagToggleAction.WRITE
    requested -> PlayerDiagToggleAction.ASK_CONFIRM
    else -> PlayerDiagToggleAction.WRITE
}

/**
 * 「只读诊断通道」授权卡（判词 9 / PDIAG1）：玩家显式授权后，正式包里也会起 DevTools 的
 * 轮询通道，但档位记成只读 —— 只放观测命令，写侧与未归类命令在 [DevToolsHost.execute] 顶部被拒。
 *
 * 三条纪律：①开启方向必须过 [PlayerDiagConfirmHost] 二次确认，取消路径零写点；②落盘按
 * [UiPrefs.setPlayerDiagnostics] 的读回校验判成败，失败给红字而不静默当成功（UI 线程禁 commit 探盘）；
 * ③关闭当场 [DevToolsHost.stop()] —— 撤销不能等重开一局。开启侧只能等下一次装载：启动点在
 * GameHost 装载尾段（GameHost.kt:1050），本波不许动 GameHost / MainActivity，所以文案明写
 * 「重开一局生效」并把这条代价写进交付的「需组长接线」。
 *
 * 卡内自装配状态（`remember`），不新增 SettingsScreen 形参；`enabled` 是形参名而不是状态名，
 * 避免与卡内状态遮蔽（写盘点必须逐字是 `UiPrefs.setPlayerDiagnostics(context, enabled)`，
 * 被 [ModDexLoaderSwitchWireTest] 同族的源码钉子钉住）。
 */
@Composable
private fun PlayerDiagnosticsCard(text: SettingsStrings) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(UiPrefs.playerDiagnosticsEnabled(context)) }
    var confirmEnable by remember { mutableStateOf(false) }
    var writeFailed by remember { mutableStateOf(false) }
    val writeGate: (Boolean) -> Unit = { enabled ->
        if (UiPrefs.setPlayerDiagnostics(context, enabled)) {
            granted = enabled
            writeFailed = false
            // 撤销当场撤通道：关态必须让轮询线程真的不存在，不是留着线程不读命令。
            if (!enabled) DevToolsHost.stop()
        } else {
            writeFailed = true
        }
    }
    SettingsCard {
        SwitchRow(
            title = text.playerDiagTitle,
            hint = text.playerDiagHint,
            checked = granted,
            onCheckedChange = { requested ->
                when (playerDiagToggleAction(requested, confirmed = null)) {
                    PlayerDiagToggleAction.ASK_CONFIRM -> confirmEnable = true
                    PlayerDiagToggleAction.WRITE -> writeGate(requested)
                    PlayerDiagToggleAction.IGNORE -> Unit
                }
            },
        )
        if (writeFailed) {
            Text(text.playerDiagWriteFailed, color = UiColors.Warn, style = ShellType.Label)
        }
    }
    PlayerDiagConfirmHost(
        text = text,
        visible = confirmEnable,
        onDismiss = { confirmEnable = false },
        onConfirm = {
            confirmEnable = false
            writeGate(true)
        },
    )
}

/**
 * 授权开启前的二次确认（照 [ModDexLoaderConfirmHost] 的形状）：讲清只放读侧、只落本机不自动发送、
 * 开启要重开一局而关闭当场停。[visible] false 时不构造 AlertDialog；取消路径只调 [onDismiss]，
 * 没有任何写点 —— 真正写盘只发生在 [onConfirm]。
 */
@Composable
private fun PlayerDiagConfirmHost(
    text: SettingsStrings,
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    ShellAlertDialog(
        title = text.playerDiagConfirmTitle,
        onDismissRequest = onDismiss,
        actions = ShellDialogActions(
            confirmLabel = text.playerDiagConfirmOk,
            onConfirm = onConfirm,
            confirmTone = EratwInk.warn(),
            dismissLabel = text.cancelText,
            onDismiss = onDismiss,
        ),
    ) {
        Text(text = text.playerDiagConfirmBody, color = EratwInk.text(), style = ShellType.Body)
    }
}

/** 快照弹窗：等宽可滚正文 + 「复制全部」。正文已是完整多行文本，弹窗不再二次拼装。 */
@Composable
private fun ModDiagnosticReportDialog(
    text: SettingsStrings,
    report: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ShellAlertDialog(
        title = text.modDiagnosticDialogTitle,
        onDismissRequest = onDismiss,
        actions = ShellDialogActions(
            confirmLabel = text.modDiagnosticDialogCopy,
            onConfirm = { copyModDiagnosticReport(context, text, report) },
            dismissLabel = text.cancelText,
            onDismiss = onDismiss,
        ),
    ) {
        Text(text = text.modDiagnosticDialogHint, color = EratwInk.textMuted(), style = ShellType.Label)
        // 快照是等宽取证文本：字族保留 Monospace，字号随 `ShellType.Label` 与页面小字同拍。
        Text(
            text = report,
            color = EratwInk.text(),
            style = ShellType.Label,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = REPORT_VIEW_MAX_HEIGHT)
                .verticalScroll(rememberScrollState())
                .padding(top = ShellSpace.CardGap),
        )
    }
}

/** 把快照正文写进剪贴板并提示（与 [copyModDiagnostics] 同口径，独立标签便于玩家回找）。 */
private fun copyModDiagnosticReport(context: Context, text: SettingsStrings, report: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("eratw-mod-diagnostic-report", report))
    Toast.makeText(context, text.modDiagnosticsCopied, Toast.LENGTH_SHORT).show()
}

/**
 * 取一次 Mod 诊断快照的全部输入：两把总闸读 [UiPrefs]，运行时读数读 [ModRuntimeOverviewEntry]，
 * 代码包概览读 [buildModCodeOverview]（rt2 目录取不到就传 null，由拼装器照「—」口径）。
 *
 * 只在玩家点「导出诊断」时调用一次，不随重组轮询；纯读，不写盘、不联网。
 */
private fun collectModDiagnosticSnapshot(
    context: Context,
    scan: ModManageScan,
    failures: Map<String, String>,
): ModDiagnosticReportInput {
    val dexLoader = UiPrefs.modDexLoaderEnabled(context)
    val runtime = ModRuntimeOverviewEntry.read(scan.mods.map { it.packageId })
    val rt2Dir = runCatching { ModEnablement.rt2Dir(GameStorage(context).gameRoot()) }.getOrNull()
    return ModDiagnosticReportInput(
        engineVersion = BuildConfig.VERSION_NAME,
        runtimeGate = UiPrefs.modRuntime(context),
        dexLoaderGate = dexLoader,
        scan = scan,
        failures = failures,
        storageByPackage = runtime?.storageByPackage.orEmpty(),
        settingsChangedByPackage = runtime?.settingsChangedByPackage.orEmpty(),
        codeOverview = buildModCodeOverview(rt2Dir, dexLoader),
        codeFace = readModCodeFace(rt2Dir, dexLoader, loadReport = ModCodeLoadReports.last),
    )
}

/** 列表项小字：`[已停用 · ]v版本 · 作者 · 来源`（缺失项自动省略，不留空分隔符）。 */
private fun modManageDetail(text: SettingsStrings, mod: InstalledMod): String {
    val parts = mutableListOf<String>()
    if (!mod.enabled) parts += text.modManageDisabledTag
    if (mod.version.isNotBlank()) parts += "v" + mod.version
    if (mod.author.isNotBlank()) parts += mod.author
    parts += modSourceLabel(text, mod.source)
    return parts.joinToString(" · ")
}

/**
 * 「Mod 装载优先级」卡（RT2 L2，task-2 ④；ModDX-4 加载顺序 UI 波升级）：已装列表勾选 +
 * 上下移，结果仍落 `UiPrefs.setPreferredPackageIds` 的原始串（逗号分隔 packageId，
 * 存储格式零迁移，消费路径 GameHost → LinkOrder 零改动——本卡只改编辑形态）。
 *
 * ModDX-4 新增「未知包」行：偏好串里已不在扫描结果的残留 ID（旧串死数据）以前被
 * [ModManageOrder.seedOrder] 静默滤掉——既看不见又删不掉。现在经
 * [ModManageOrder.leftoverIds] 取出、按「未知包 + 移除」行展示。形态裁定：
 *
 * - 未知包行放在已装列表之后，不参与勾选与上下移（链接期本就忽略未知 ID，
 *   ModuleLinkOrder.preferredRanks 按 ID 查表、只在已知包之间比相对序，展示位无语义）；
 * - 玩家没点「移除」就一直保留：勾选 / 上下移写回经 `ModManageOrder.toRaw(unknown, chosen)`
 *   把残留原样带回串首（位置无语义），不因动了别的行悄悄丢数据；
 * - 「恢复缺省」仍是整串清空（未知包一并清掉）——它是玩家手里对付整条死串的兜底；
 * - 兜底输入框与未知包行同源（都读 `value`）：玩家在输入框里手删残留，行随 `value`
 *   变化经 `remember(value, installedIds)` 键控重建而消失，双向一致。
 *
 * 「手动编辑」兜底输入框保留：勾选面只能排**已装**列表，玩家想给还没装上的包
 * 预留优先级、或 manifest 读不出来扫不进列表时，仍只能靠这串原始串；`value` 与输入框双向
 * 同源，勾选操作写回后输入框立刻反映（`remember(value, installedIds)` 键控重建勾选序）。
 *
 * 「优先级而非完整顺序」的说明来自契约 §1.2：依赖关系会改变实际装载序，别让玩家误读成
 * 对最终序的逐位指定。独立成卡（不塞进 `ControlCard`）是为了不再给那个长参数表添形参。
 */
@Composable
private fun ModPackageOrderCard(
    text: SettingsStrings,
    value: String,
    installed: List<InstalledMod>,
    onValueChange: (String) -> Unit,
) {
    val installedIds = installed.map { it.packageId }
    val titles = installed.associate { it.packageId to it.title }
    var chosen by remember(value, installedIds) {
        mutableStateOf(ModManageOrder.seedOrder(value, installedIds))
    }
    // 未知包残留行（ModDX-4）：与 chosen 同键控重建——勾选写回的串里带上了它们
    // （toRaw(unknown, chosen)），重建后取到的仍是同一批，行不会闪失。
    var unknown by remember(value, installedIds) {
        mutableStateOf(ModManageOrder.leftoverIds(value, installedIds))
    }
    val order = ModManageOrder.displayOrder(chosen, installed)
    // 勾选 / 上下移写回：未知包残留原样带回（玩家没点「移除」就不丢，见卡 KDoc 裁定）。
    val commit: (List<String>) -> Unit = { ids -> onValueChange(ModManageOrder.toRaw(unknown, ids)) }
    SettingsCard {
        SectionTitle(text.modPackageOrderTitle)
        Text(text.modPackageOrderChosenCount(chosen.size), color = UiColors.TextMuted, style = ShellType.Label)
        if (chosen.isEmpty() && unknown.isEmpty()) {
            Text(text.modPackageOrderListEmpty, color = UiColors.TextMuted, style = ShellType.Label)
        }
        for (packageId in order) {
            ModPackageOrderKnownRow(
                text = text,
                title = titles[packageId] ?: packageId,
                packageId = packageId,
                chosen = chosen,
                onCommit = commit,
            )
        }
        // 未知包残留行（ModDX-4 判据 1）：旧串死数据可见、可逐行移除；不参与勾选与上下移。
        for (staleId in unknown) {
            ModPackageOrderUnknownRow(
                text = text,
                staleId = staleId,
                onRemove = { onValueChange(ModManageOrder.toRaw(unknown - staleId, chosen)) },
            )
        }
        TextButton(onClick = { onValueChange("") }) {
            Text(text.modPackageOrderReset, color = LocalZoneAccent.current, style = ShellType.Tab)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            label = { Text(text.modPackageOrderManualLabel, style = ShellType.Caption) },
            supportingText = { Text(text.modPackageOrderEmpty, style = ShellType.Label) },
            textStyle = ShellType.Body,
            colors = ShellField.outlined(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Text(
            text = text.modPackageOrderHint,
            color = UiColors.TextMuted,
            style = ShellType.Label,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 已装包一行（ModDX-4 从 [ModPackageOrderCard] 拆出，拆行只为 detekt LongMethod 闸）：勾选 + 上下移。 */
@Composable
private fun ModPackageOrderKnownRow(
    text: SettingsStrings,
    title: String,
    packageId: String,
    chosen: List<String>,
    onCommit: (List<String>) -> Unit,
) {
    val picked = packageId in chosen
    val position = chosen.indexOf(packageId)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            checked = picked,
            onCheckedChange = { on -> onCommit(if (on) chosen + packageId else chosen - packageId) },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = UiColors.Text, style = ShellType.Tab)
            Text(packageId, color = UiColors.TextMuted, style = ShellType.Overline)
        }
        TextButton(
            onClick = { onCommit(ModManageOrder.move(chosen, packageId, -1)) },
            enabled = picked && position > 0,
        ) {
            Text(text.modPackageOrderMoveUp, style = ShellType.Caption)
        }
        TextButton(
            onClick = { onCommit(ModManageOrder.move(chosen, packageId, 1)) },
            enabled = picked && position >= 0 && position < chosen.lastIndex,
        ) {
            Text(text.modPackageOrderMoveDown, style = ShellType.Caption)
        }
    }
}

/**
 * 未知包残留一行（ModDX-4 判据 1）：偏好串里有、扫描结果里没有的旧 ID，标签如实说
 * 「不在已装列表」，右侧「移除」逐行清掉；行本身不参与勾选与上下移（链接期本就忽略）。
 */
@Composable
private fun ModPackageOrderUnknownRow(
    text: SettingsStrings,
    staleId: String,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text.modPackageOrderUnknownTag, color = UiColors.Text, style = ShellType.Tab)
            Text(staleId, color = UiColors.TextMuted, style = ShellType.Overline)
        }
        TextButton(onClick = onRemove) {
            Text(text.modPackageOrderUnknownRemove, style = ShellType.Caption)
        }
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
    stagedApkPath: String,
    onInstallStagedApk: () -> Unit,
) {
    CollapsibleSettingsCard(title = text.launchTitle, hint = text.loadingStyleLabel) {
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
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        // 进行中态（EP4 U2-②）：只有真的拿到单飞闸才禁用，结果回来随改随消。话术用按钮自己的词条加省略号，
        // 不在这里新增一套只有中文的文案（三语表键位归 U3）。
        OutlinedButton(
            onClick = onCheckUpdate,
            enabled = !updateChecking,
            shape = ShellShape.Chip,
            modifier = Modifier.height(40.dp),
        ) {
            val label = UpdateUiText.checkButtonLabel(text.checkUpdateButton, updateChecking)
            Text(label, color = if (updateChecking) UiColors.TextMuted else UiColors.Text, style = ShellType.Body)
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
                shape = ShellShape.Chip,
                border = BorderStroke(1.dp, UiColors.Border),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(updateCopy.cancelNotifyAction, color = UiColors.Accent, style = ShellType.Tab)
            }
        }
        // 「安装已下载的更新」（下载完成拍登记的已核对整包）：完成通知是会丢的（权限被拒/
        // 渠道被关/被划掉/被 ROM 折叠），这颗按钮是通知之外唯一持久的安装入口 —— 玩家从
        // 系统设置授权回来，回到设置页总有路可走，不再依赖「回头再点一次下载」的快路径。
        if (stagedApkPath.isNotEmpty()) {
            OutlinedButton(
                onClick = onInstallStagedApk,
                shape = ShellShape.Chip,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .height(40.dp),
            ) {
                Text(updateCopy.installStagedApk, color = UiColors.Accent, style = ShellType.Body)
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
                style = ShellType.Label,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (updateReadout.hasTechDetail) {
            Text(
                text = UpdateUiText.techDetailLabel(updateCopy, showTechDetail),
                color = UiColors.Accent,
                style = ShellType.Label,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(ShellShape.Tag)
                    .clickable { showTechDetail = !showTechDetail }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
            if (showTechDetail) {
                updateReadout.techLines.forEach { line ->
                    Text(
                        text = line,
                        color = UiColors.TextMuted,
                        style = ShellType.Overline,
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
                shape = ShellShape.Chip,
                border = BorderStroke(1.dp, UiColors.Border),
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = UpdateProgressText.settingsRetryLabel(updateCopy, kind),
                    color = UiColors.Accent,
                    style = ShellType.Tab,
                )
            }
        }
        // 「以后都不」的恢复口（契约 §1.5）：跳过的只是那一个 versionCode，这里给出口；
        // 「立即检查更新」不看这条（UpdateUi.checkNowInteractive 传 0 无视抑制）。
        if (skippedUpdateVersion > 0L) {
            Text(
                text = text.updateSkippedLine(skippedUpdateVersion),
                color = UiColors.Warn,
                style = ShellType.Label,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = onRestoreUpdateReminder,
                shape = ShellShape.Chip,
                border = BorderStroke(1.dp, UiColors.Border),
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(text.updateRestoreButton, color = UiColors.Accent, style = ShellType.Tab)
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
            .clip(ShellShape.Inset)
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.45f), ShellShape.Inset)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = UiColors.Text,
                style = ShellType.Tab,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (fraction != null) "$percent%" else "· · ·",
                color = accent,
                // 进度百分比是这一段的头号读数：比统计格再大一档，仍从 Figure 派生。
                style = ShellType.Figure.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (fraction != null) {
            DeterminateBar(fraction = fraction, accent = accent)
        } else {
            IndeterminateBar(accent = accent)
        }
        Spacer(Modifier.height(6.dp))
        Text(text = caption, color = UiColors.TextDim, style = ShellType.Label)
    }
}

/** 已知总量：按比例填充；读数每 250ms 一跳，用短补间抹平台阶感，不让它一格一格蹦。 */
@Composable
private fun DeterminateBar(fraction: Float, accent: Color) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = ShellMotion.content(),
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
        animationSpec = infiniteRepeatable(ShellMotion.Sweep),
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
            style = ShellType.Label,
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
        Text(text = text.restoreDefaultsHint, color = UiColors.TextMuted, style = ShellType.Label)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRestore,
            modifier = Modifier.height(40.dp),
            shape = ShellShape.Chip,
            border = BorderStroke(1.dp, UiColors.Warn),
        ) {
            Text(text.restoreDefaultsButton, color = UiColors.Warn, style = ShellType.Body)
        }
    }
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
            .clip(ShellShape.Tag)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, color = UiColors.Text, style = ShellType.Body, maxLines = 1)
        if (hint != null) {
            Text(
                text = hint,
                color = UiColors.TextMuted,
                style = ShellType.Label,
                maxLines = hintMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        }
    }
}

/**
 * 分段滑选控件单档高度：取壳层行高下限 [ShellSpace.RowMinHeight]。
 *
 * 原来写死 38dp（一行 13sp 文案 + 上下留白），视觉上够紧凑却跌破 44dp 触控下限 ——
 * 一排四档在 Mod 卡里本来就是指头连点连拖的地方，热区不能靠视觉尺寸省。
 */
private val SEGMENT_HEIGHT = ShellSpace.RowMinHeight

/**
 * 分段滑选控件：一排等宽胶囊，选中档底下压一条跟着滑的圆角底色。
 * 点任意一档直接切；按住整排横拖就是**滑**着换档 —— 拖过半个档宽跳一档，跳完清零，
 * 所以慢拖是一档一档过、快拖能连跳。
 *
 * 立它的直接动机是右栏布局那一组：三档原来是三条单选行、各带两行说明，玩家得读完三段话
 * 才知道自己在哪一档；收成一条之后「一共几档 / 我在第几档 / 往哪边拖」一眼有数。
 *
 * 控件只认「第几档」，档位文案与语义都在调用侧 ⇒ 同一件东西也装得下每行枚数 1..6。
 * 读屏走 `selectableGroup` + 每档 `selectable(role = Role.Tab)`：念「第 2 个，共 3 个，已选中」。
 */
@Composable
private fun <T> SegmentedSwitcher(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShellShape.Chip)
            .background(UiColors.CardAlt),
    ) {
        val cellWidth = maxWidth / options.size
        val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
        // 拖拽回调里读到必须是最新一档：拖过一档后 `selected` 会变，但 pointerInput 的
        // 闭包不会重跑，直接闭包捕获会把连跳锁死在第一档。
        val latestIndex = rememberUpdatedState(options.indexOf(selected))
        val indicatorLeft by animateDpAsState(
            targetValue = cellWidth * selectedIndex,
            animationSpec = ShellMotion.segment(),
            label = "segmentIndicator",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SEGMENT_HEIGHT)
                .pointerInput(options, cellWidth) {
                    val cellPx = with(density) { cellWidth.toPx() }
                    if (cellPx <= 0f) return@pointerInput
                    var dragged = 0f
                    val stepTo: (Int) -> Unit = { target ->
                        val next = target.coerceIn(options.indices)
                        if (next != latestIndex.value) onSelected(options[next])
                    }
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        dragged += dragAmount.x
                        if (dragged >= cellPx / 2f) {
                            dragged = 0f
                            stepTo(latestIndex.value + 1)
                        } else if (dragged <= -cellPx / 2f) {
                            dragged = 0f
                            stepTo(latestIndex.value - 1)
                        }
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .offset(x = indicatorLeft)
                    .width(cellWidth)
                    .fillMaxHeight()
                    .clip(ShellShape.Chip)
                    .background(UiColors.Accent.copy(alpha = 0.30f)),
            )
            Row(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                options.forEachIndexed { position, option ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = position == selectedIndex,
                                role = Role.Tab,
                                onClick = { onSelected(option) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = labelOf(option),
                            color = if (position == selectedIndex) UiColors.Text else UiColors.TextMuted,
                            style = ShellType.Tab,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
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
    ShellGroupHeader(title = title, subtitle = subtitle, accent = LocalZoneAccent.current)
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
            Text(hint, color = UiColors.TextMuted, style = ShellType.Label)
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
        .clip(ShellShape.Tag)
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
                    style = ShellType.Body,
                )
                if (entry.fixed) {
                    Text(text.pinnedByFixed, color = UiColors.Warn, style = ShellType.Label)
                }
            }
            when (entry.kind) {
                SettingsKind.BOOL -> Switch(checked = boolValue, onCheckedChange = null)

                else -> OutlinedButton(
                    onClick = { onEdit(entry) },
                    shape = ShellShape.Chip,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = display(current).ifEmpty { text.emptyValue },
                        color = UiColors.Accent,
                        style = ShellType.Tab,
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
                style = ShellType.Label,
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
            style = ShellType.Label,
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
                        style = ShellType.Body,
                    )
                    Text(
                        text = text.currentDefaultEffective(
                            display(entry.currentValue),
                            display(entry.defaultValue),
                            display(entry.effectiveValue),
                        ),
                        color = UiColors.TextMuted,
                        style = ShellType.Label,
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
                shape = ShellShape.Chip,
            ) {
                Text(text.saveAndRestart, style = ShellType.Tab)
            }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f).height(40.dp),
                shape = ShellShape.Chip,
                colors = ButtonDefaults.buttonColors(
                    containerColor = UiColors.PrimaryButton,
                    contentColor = UiColors.Text,
                ),
            ) {
                Text(text.save, style = ShellType.Section)
            }
        }
        Text(
            text = text.bottomFootnote,
            color = UiColors.TextMuted,
            style = ShellType.Overline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp),
        )
    }
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
 * 一次「需要先确认」的导入：按**逻辑存档单元**攒（MODGC2 裁定 1）。
 *
 * 判据、落位路径、那一格里已有谁都全由 [SaveImport] 定，这里只搬运结果 ——
 * 页面上不再判一次，免得设置页和游戏内 `CHKDATA` 说出不一样的一句话。
 *
 * 为什么持**成员列表**而不是单个文件：`.sav` 本体与它的 `.rt2` 侧车是一个单元，对话框只能
 * 问玩家一次、落盘也只能要么全写要么全不写（只写侧车 = 半套配对）。展示走 [place]/[fileName]
 * 这两个代表项（有本体就用本体），落盘走 [members] 全部成员。
 */
private class PendingImport(val members: List<Member>) {

    /** 单元里的一个成员：判定 + 字节 + 显示名（落盘与上屏都按成员逐条做）。 */
    class Member(val place: SaveImport.Place, val bytes: ByteArray, val fileName: String)

    /** 代表项：有本体成员就用本体（[placeUnit] 同一排序口径），否则第一个。 */
    private val representative: Member =
        members.firstOrNull { !SaveImport.isSidecarName(it.fileName) } ?: members.first()

    val place: SaveImport.Place get() = representative.place

    val fileName: String get() = representative.fileName
}

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

    ShellAlertDialog(
        title = request.title,
        onDismissRequest = onDismiss,
        actions = ShellDialogActions(
            confirmLabel = text.okText,
            onConfirm = confirm,
            dismissLabel = text.cancelText,
            onDismiss = onDismiss,
        ),
    ) {
        if (isEnum) {
            request.choices.forEach { choice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = choice }
                        .padding(vertical = ShellSpace.CardGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected == choice,
                        onClick = { selected = choice },
                    )
                    Text(choice, color = EratwInk.text(), style = ShellType.Body)
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
                textStyle = ShellType.Body,
                colors = ShellField.outlined(),
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
                color = EratwInk.warn(),
                style = ShellType.Caption,
            )
        }
    }
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

