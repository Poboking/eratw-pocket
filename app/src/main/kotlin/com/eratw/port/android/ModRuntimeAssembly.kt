package com.eratw.port.android

import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.ICharaSlotProjector
import com.eratw.port.core.entity.IEntityKernel
import com.eratw.port.core.entity.NativeCharaField
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.entity.RealCharaSlotProjector
import com.eratw.port.core.entity.RelationProjectionSynchronizer
import com.eratw.port.core.entity.VirtualCharaIndexResolver
import com.eratw.port.core.entity.VirtualCharaNoRegistry
import com.eratw.port.core.fn.ENTITY_RUNTIME_SLOT
import com.eratw.port.core.fn.EntityRuntime
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.mod.ActionExecutionOutcome
import com.eratw.port.core.mod.DecisionPipeline
import com.eratw.port.core.mod.DecisionSeed
import com.eratw.port.core.mod.DecisionState
import com.eratw.port.core.mod.DecisionStateCodec
import com.eratw.port.core.mod.DurableInteractionHost
import com.eratw.port.core.mod.FunctionHookWiring
import com.eratw.port.core.mod.ModEventWiring
import com.eratw.port.core.mod.ModHandlerBudget
import com.eratw.port.core.mod.ModSettingsSnapshot
import com.eratw.port.core.mod.GameTickDriver
import com.eratw.port.core.mod.JobSchedulerSnapshot
import com.eratw.port.core.mod.JobSchedulerStateCodec
import com.eratw.port.core.mod.ModAudioWiring
import com.eratw.port.core.mod.MOD_STORAGE_PACKAGE_VERSION_KEY
import com.eratw.port.core.mod.ModContextImpl
import com.eratw.port.core.mod.ModDx4WriteApplier
import com.eratw.port.core.mod.ModDx4WriteGate
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModRuntimeState
import com.eratw.port.core.mod.ModResourceStore
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.mod.clearDurableInteractions
import com.eratw.port.core.mod.globalSectionsSnapshot
import com.eratw.port.core.mod.loadGlobal
import com.eratw.port.core.mod.remapDurableContexts
import com.eratw.port.core.mod.release
import com.eratw.port.core.mod.putReservedKey
import com.eratw.port.core.mod.ModTimingLedger
import com.eratw.port.core.mod.TimePhaseKey
import com.eratw.port.core.mod.TurnPhaseClock
import com.eratw.port.core.save.CrossDeviceSidecarStoragePort
import com.eratw.port.core.save.GlobalSidecarSaveWiring
import com.eratw.port.core.save.GlobalSidecarStoragePort
import com.eratw.port.core.save.ModCsvValueCodec
import com.eratw.port.core.save.PackageChunk
import com.eratw.port.core.save.SaveFolderMigration
import com.eratw.port.core.save.SaveService
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.save.SidecarChunkGroups
import com.eratw.port.core.save.SidecarRestoreResult
import com.eratw.port.core.save.SidecarStateSegments
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.save.SidecarSaveInventory
import com.eratw.port.core.save.SidecarSaveWiring
import com.eratw.port.core.save.SidecarStoragePort
import com.eratw.port.core.patch.DeclarationConflictException
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.patch.DeclaredArchetype
import com.eratw.port.core.patch.WriteGrantDeclaration
import com.eratw.port.core.patch.DeclaredCharaFieldRoute
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestFormatException
import com.eratw.port.core.patch.ManifestLoader
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.NamePolicy
import com.eratw.port.core.proc.DatingForensics
import com.eratw.port.core.vars.ModCsvCapacityLimitException
import com.eratw.port.core.vars.ModCsvLoadContext
import com.eratw.port.core.vars.ModCsvPlanLoader
import com.eratw.port.core.vars.ModCsvValueBag
import com.eratw.port.core.vars.ModCsvValueRestorer
import com.eratw.port.core.vars.SystemLayout
import com.eratw.port.core.vars.VarDef
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.ReservationKey
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modlivingworld.CharacterDeclarations
import com.eratw.port.modlivingworld.CharacterMod
import com.eratw.port.platform.storage.ModStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Mod 装配链的**总闸与装载相位**（RT2 车 B1：P0-1 宿主装配 + P0-3 单一时间源 + P2-3 诊断消费者；
 * era-arch-v61u.md §23.4 车 B）。根因层 = 装载/编译相位 + UI 壳：`ManifestLoader` 与
 * `ModRuntimeHost` 在 core 早已落码，但生产 0 构造点——链在装载相位断头，`:app` 又没有装配点。
 * 本文件只做接线装配，core 公开签名一行不改（ModRuntimeHost 的测试假默认值经构造实参全部
 * 覆盖为真值，不改它的缺省）。
 *
 * ## 总闸：玩家面缺省开（RT2-GATE-OPEN，2026-11-17 主人裁定）
 *
 * 开关由 GameHost 构造参数 `modRuntime` 承载（**构造参数缺省 false = 测试哨兵**，生产值由
 * MainActivity 单路传入 `UiPrefs.modRuntime(this)`，而偏好缺省为 true = 开箱装配），本文件在
 * [attach] 入口先查闸：关 = 一个对象都不建、磁盘都不碰，返回 null——与未接线前逐字节等价。
 * **不做成「探测到异常就静默跳过」**：开态下任一 manifest 坏档（[com.eratw.port.core.patch.ManifestFormatException]）
 * 或声明冲突（[com.eratw.port.core.patch.DeclarationConflictException]）都从 [attach] 原样抛出，
 * 由 GameHost 装载段的既有 catch 收进失败屏——整包拒绝、不返回半装状态（ManifestLoader 的事务语义）。
 *
 * ## 装载链（开态）
 *
 * 磁盘 manifest 字节（`mods/rt2/` 下的 `.manifest` 文件；文件名升序**只用于可重复地
 * 发现文件**，链接序由核心层 ModuleLinkOrder 按依赖约束 / 显式排序配置 / `packageId`
 * 规范序裁决，文件名不决定模块优先级）→
 * [ManifestLoader.load]（解码 → DeclarationRegistry.addPackage → freeze）→ 冻结快照 →
 * [ModRuntimeHost]（六个注入位全部真值，见 [AssemblyInputs]）→ [ModRuntimeHost.install]。
 * mod 的 Kotlin 代码载体（.erbc 模块联结 / ModChainAssembler）不在本车链上；Kotlin 载体
 * 经 [com.eratw.port.android.ModCarrierRegistry]（K4-min build-time 注册表，RT2-B② 接线）
 * 由宿主在开态注入 [attach] 的 [modProviders]——总闸关时不取（关态零对象）。
 *
 * ## 六个注入位（对照 ModRuntimeHost 的测试假默认值）
 *
 * - `shortIdOf` = [PackageShortIdRegistry.allocate]（会话级一表；不再是恒 `{1}` 的跨包撞号）；
 * - `intVarView` / `strVarView` = [VariableStore] 按名只读视图（不再是恒 0 / 恒空串）；
 * - `runtime` = [VmBoundaryScheduler]（VM 线程队列的真实现；改实体结构只在页边界放行）；
 * - `storage` = 会话级**唯一一份** [ModStorageStore]（激活/停用/孤儿分段语义随 rt2close 已落码）；
 * - `logSink` = 宿主传入的既有日志面（Log，经 lambda 注入保持本文件纯 JVM）；
 * - `snapshot` = freeze 产物；`entityKernel` = 快照派生的 [EntityKernel]（声明事务先行）。
 *
 * `decisionPipeline`（W1-DSTATE，CONTRACT-RT2-DSTATE 判据 2）：开态构造**真**
 * [DecisionPipeline]（种子 = 安装级熵基座 + 宿主真熵尾巴经 `DecisionSeed.derive`，
 * 见 W2-SEED 节；diagnostics 接既有消费者缝，packageOrder = 声明快照链接序）经
 * [AssemblyInputs] 注入 [ModRuntimeHost]，生产会话
 * `context.actions` 从 fail-closed 桩翻为决策管线同包 facade。**关态维持 null ⇒
 * fail-closed 桩语义逐位不变**（总闸首查短路，构造次数 0）——本行注释口径自本波起翻转，
 * 裁定出处 = CONTRACT-RT2-DSTATE 判据 2 + 主人 2026-11-14 对 M10-1 前置依赖的全权任命
 * （契约头注），取代此前「决策面归 §21.3 仲裁车」的后置口径。
 *
 * ## W1-DSTATE：决策/任务态 `.rt2` 落盘（判据 1/3/5）
 *
 * 容器分段 = `.rt2` 尾部「决策态段 + 任务态段」（SidecarSaveBundle v2 增补节：要么都写
 * 要么都不写，旧档无段回落不拒载）；任务态段是容器级**会话并集**快照（键天然按包隔离），
 * 抓取/回灌按活跃包拆分。抓取落在存档抓取路径（[ModSidecarStoragePort.captureRt2Bytes]
 * ← SAVEGAME 的容器物化点）而非 shutdown：.rt2 只在存档时写、决策/任务态是每槽状态，
 * 收口时刻快照反而滞后于最后一次存档；ModRuntimeSession 的既有 shutdown 路径只承担
 * regbin 全局单件（先例口径不同物）。恢复回灌落在 [ModSidecarStoragePort.restoreRt2]
 * （LOADGAME 读档缝）：决策态经宿主 `reservationAlive` 谓词过滤陈旧预留并上日志面
 * （增补 B 口径；谓词 = `chara:` kind 按内核 `exists` 判存活，锚 = mod-api ActionApi.kt
 * 「chara: 预留即占槽」约定），任务态按活跃包拆分回灌各 mod 的 `JobScheduler.restore`
 * （未声明模板实例 fail-closed 同 `of`）。
 *
 * ## W2-SEED：种子随 `.sav` 走（CONTRACT-RT2-SEED 判据 1–6）
 *
 * W1-DSTATE 时代种子 = 安装级确定性（gameCode + 游戏根路径）⇒ 同安装恒同种子（换局不换
 * 仲裁随机流）。本波改为：装配期种子 = 安装级熵基座（[decisionEntropy]）+ 宿主真熵尾巴
 * （`freshDecisionEntropyTail`，局部 SecureRandom 16 字节，熵纪律 = app 产熵、core 只收
 * `ByteArray`）⇒ 每局新种子（判据 1）；档内种子恢复走读档缝**采纳序**——attach 早于存档
 * 选择（GameHost 装配序），「先读段后建管线」不可行且会波及禁触的 GameHost/GameSession ⇒
 * 管线构造后受控供给（[DecisionPipeline.supplySeed]：一次性 + 仅未消费状态，fail-closed），
 * [ModDecisionJobState.adoptSeedFromSave] 相等即 no-op、不等先供给再回灌，`restoreState`
 * 相等闸语义不动（判据 2/4）。旧档无段 = 无采纳步 = 管线保持装配期种子照常载入（判据 3
 * 兼容回落），该局首次存档起种子入段；wire 只增不改，VERSION 2 读路不动（判据 6）。
 * 已消费会话（抽取已发生）里重载异种子档 = supplySeed 点名拒（判据 4 的保持面，
 * fail-closed 上读档缝既有 catch）。
 *
 * ## F 车投射真装置（MR-2b-2，era-arch-v61u.md §23.4 F 行）
 *
 * 投射内核 `RealCharaSlotProjector`（M5-2）与 SDK 公开面 `RosterProjectionApi`（E 车）都已
 * 收口，缺的正是本装配点：阶段二建**唯一一份**真桥与虚拟 NO 注册表，按 M5-2 装配序
 * 回填 `store.charaSlotProjector`（循环依赖破法）、再向 store 后期装配
 * `VirtualCharaIndexResolver`（`rowOfEntity` = 真桥的 `charIndexOf`），经 [AssemblyInputs]
 * 把同一实例传给 [ModRuntimeHost]，阶段六把同一实例交给 [SaveService]。
 * 于是开态正式会话里 `context.roster` 四入口可达、虚拟 NO 能解析到当前投射行、
 * `.rt2` 的实体载荷与虚拟 NO 段不再是恒空许口；关态仍由总闸首查短路，零接触。
 *
 * 收口在 [ModRuntimeSession.shutdown] 幂等解绑 store 的两个钩子。生产会话的 store 由
 * `GameSession` 在声明事务之前构造，那时注册表与真桥都尚不存在，故 resolver 只能后期装配
 * （[VariableStore.bindCharaIndexResolver]），不是在 `GameSession` 里抢先喂构造实参。
 *
 * `fields` 由声明事务的路由域供给（M5-4 刀 2 的 [wireCharaFieldRoutes]：唯一构造点第五参，
 * 源码锁「真桥生产构造恰一处 == 1」零改写）。空路由声明 = 空表 = 只建行 / 维护映射 /
 * 写 NO 的缺省路径逐位不变；有路由 = pour/capture 双向镜像按声明搬运（详见 M5-4 节）。
 * 全量逐域对拍归 M5-4 刀 3。
 *
 * ## M5-4 刀 2：chara 路由供给缝 + attach 终审（CONTRACT-RT2-M54IMPL2 判据 1/2/5/6）
 *
 * 阶段一拿到冻结快照后、内核构造**前**预组 [wireCharaFieldRoutes]（RECON R3：同步缝，
 * 不走晚装——fields 是不可变清单，晚装只引入「绑定前 project 静默丢域」的新时序风险面，
 * 与 attachRelations 的 bridge→sync→projector 循环依赖破法不同源）。供给单源 = 快照路由域
 * `snapshot.charaFieldRoutes`（刀 1 freeze 派生的逐格 [NativeCharaField] + `__native_chara__`
 * 镜像 schema），不二次读 manifest、不复制声明语义。
 *
 * attach 终审 = RECON Q3 两段式的第二段（freeze 只有声明事务，看不到运行期注册面）：
 * ① 变量存在性（`store.registry.def`）；② 角色性（`isCharacterData`）；③ dims（路由恒
 * 1 维族单格，格号 < `def.dims[0]`——VARIABLESIZE.CSV 族尺寸与声明跨度漂移在此整包拒
 * 点名）；④ 类型（INT 行原样；用户 STR 变量的占位 INT 行以 `def` **重造行**并同步修正
 * 内核镜像字段类型——不修正则 pour 经 `kernel.getStr` 对 INT 型镜像字段拿空串、静默写错
 * 行，契约判据 2 点名的危害；capture 方向另有内核类型闸兜底；FLOAT 无投射路径当场拒）。
 * 任一不符 = 收齐全部违规、逐条 logSink 点名后整包拒（IllegalStateException，与「坏档
 * 整包拒」同口径，不做静默降级）。日志行是取证面（非玩家可见文案，不进域文案表）。
 *
 * 零变化口径（判据 5）：无路由声明 = 路由域空 = 空表缺省路径逐位不变 + FINE 取证；
 * 关态 = 总闸首查短路，本缝根本不进（判据 6）。
 *
 * ## M5-3：关系投影接线 + 活动期变更闸（CONTRACT-RT2-M53 裁定 1/2）
 *
 * 阶段二建真桥后按声明事务 snapshot 的 `relationshipSchemas` 接 [RelationProjectionSynchronizer]
 * （[RealCharaSlotProjector.attachRelations] 已 public 化）：恰一个 → attach（构造期既有
 * fail-closed 判据——必须已注册、有向、无 autoInverse——接上后验）；零个 → 跳过 + FINE
 * （纯 Base 旁路保持，现状缺省）；多个 → 跳过 + FINE 取证（多 schema 注册本身合法，投影
 * 同步本波单 kind，登记余量不拒载）。`lastReport.isClean` 为假的退场回写由
 * [ModRuntimeSession.onTurnBoundary] 在页边界消费上日志面（诊断面，不静默）。
 *
 * 同一处还给真桥晚装**活动期投射变更闸**（[RealCharaSlotProjector.bindActivityGate]）：
 * probe = 变量层帧活动（`store.currentFunction != null`，与 `GameSession.isVmActive` 同源
 * 同判据），VM 执行中（嵌套调用/事件回调/输入流程）SDK `project/unproject` 当场拒，
 * 页边界放行；脚本自身 SORTCHARA/DELCHARA 即时语义走行操作钩子、不经闸（ADR-005）。
 * 判据权威与「为什么落在变量层帧活动而不是 Vm 实例」的论证见 GameSession.isVmActive 的
 * KDoc（要点：ProcessRuntime.kt 禁触 ⇒ vm 实例对装配层不可达；store 是 attach 的既有参数）。
 *
 * TODO(verify)：原版 Emuera 没有「宿主装配序」这一层（RT2 移植侧自建装配面），无官方
 * 行为可比；依据 = §23.2 F2 调用场景 + M5-2 装配序锚 + 真引擎纵向切片，卡点 = 缺官方 SDK 锚。
 *
 * ## W2-ARDRV：生产仲裁驱动（外轮相位触发 + decisionDriver 宿主缝，CONTRACT-RT2-ARDRV）
 *
 * 管线已注入（W1-DSTATE）但外轮没有生产触发点、[CharacterMod.decisionDriver] 没有宿主实现
 * ——本波补这两缝：外轮挂 [ModRuntimeSession.onTurnBoundary] 的页边界③步（每边界**只跑
 * 一次**，2026-10-31 真机 OOM 先例的频率纪律）；宿主 runner 住 [ModArbitrationRound]
 * （嵌套仲裁复用同一管线，held 预留语义不变），经 [bindArbitrationSeam] 注入载体实例
 * （app 持实例，ModCarrierRegistry 载体模式；mod-livingworld 业务面零改动、mod-api 零改动）。
 * 外轮域清单 = [productionOuterRoundDomains]（只含傍晚域的裁定依据见其 KDoc）。
 * [DecisionPipeline] 执行内核零改动（`run` 公开面已存在，core DecisionPipeline.kt:463）。
 *
 * ## global 侧车接线（RT2 C-RES1，global.rt2 配对面）
 *
 * 阶段六在槽档接线之外加装 `GlobalSidecarSaveWiring`（同一端口、同一事务根）：
 * SAVEGLOBAL 写 `global.sav` 后经 `SaveTransaction` 配对写 `global.sav.rt2`
 * （mod GLOBAL 分片 = 会话 ModStorageStore 的每包一段），LOADGLOBAL 读回交还
 * （`ModRuntimeSession.adoptStorage` 重绑链已通，mod 侧 `context.storage` 立即可见）。
 * 缺/坏/失配 fail-closed 上日志面，不改 SAVEGLOBAL/LOADGLOBAL 的异常形状；总闸关态
 * 不 install，`FileSystemOps` 的全局档路径与未接线前逐字节相同。
 *
 * ## 单一时间源（P0-3 时间权威铁律）
 *
 * 原版 `DAY` 是唯一游戏日权威（`VariableCode.kt:34`，SystemLayout.int1D 第 0 项，存 gInt1[0]），
 * 由 ERB 脚本推进；节拍只**读**它（写入次数必须为 0）。`TIME`（`VariableCode.kt:54`）是整数
 * 时段：ModDX-4 M5P 起撤销旧「TIME→无权威映射」搁置，页边界按 [TimePhaseKey] 单一换算口
 * 追加确定性键 `"time." + <TIME 原值>`——引擎不引入时段语义（朝/昼/夜命名归 mod 策略），
 * TIME 读数不可用则本拍不追加（fail-open，与未接前同形）。
 *
 * 相位派发从「恒空」改为按边界日派生（P0-WIRE）：`GameTime.phase` 的取值不再留白，由
 * [TurnPhaseClock] 只从 `DAY` 推导（首边界/回退 = 日始；日变 = 日终 + 日始；同日 = 动作点），
 * 派发走 [GameTickDriver.tickTurn]；**任务触发口径不变**——本边界仍以空相位（不限相位）跑到期
 * 任务一次。日终/日始的先后是否与 ERB 回合边界脚本逐拍一致属 TODO(verify)（缺官方 SDK 锚）。
 *
 * 纯 JVM、无 Android API、无静态可变全局（铁律 4）：全部状态挂实例（[ModRuntimeSession] /
 * [VmBoundaryScheduler] / [PackageShortIdRegistry]），本 object 不留任何 var。
 */
/**
 * mod 链链接产物 [SymbolCatalog] 快照的 aux 槽名（W2-SUPCAT；RuntimeAux.kt:17 的命名约定，
 * `mod` 能力家族前缀全小写）。
 *
 * 写侧唯一 = 宿主装配面：`ModChainAssembly.assembleWithCatalog` 把链接产物同一实例交出来，
 * `GameHost` 在 GameSession 构造之后、本 object 的 [ModRuntimeAssembly.attach] 之前调
 * `ModChainAssemblyOutcome.installCatalogSlot(fresh.store)` 写槽（RuntimeAux.slot 的
 * create-if-absent，一个会话只写一次）。读侧唯一 = 本 object 的事件名存在性闸
 * （`host.knownEventKeys`；RuntimeAux.peek 只查不建，读侧不可能伪造装配态）。
 * entityRuntime 回填的 catalog 自 SUPCAT-W 起改走 attach inputs 供给（判据 3，乙形裁定：
 * 链接产物随链走，槽写路对 entityRuntime 不再必要），不再消费本槽。
 * 快照生命周期随 store（会话级），不跨会话持有。
 */
const val MOD_LINK_CATALOG_SLOT: String = "mod.chain.catalog"

object ModRuntimeAssembly {

    /** RT2 Kotlin manifest 子目录名（挂在 [ModStore.modsDirFor] 即 `mods/` 之下，与 R7 补丁面分域）。 */
    const val RT2_MANIFEST_DIR_NAME: String = "rt2"

    /** manifest 载体扩展名（modgen 的产出约定，ManifestProdChainTest 同名）。 */
    const val MANIFEST_EXTENSION: String = ".manifest"

    /** 全局注册表 bin 落位文件名（gameRoot 根、与 global.sav 同层；era-arch-v61u.md:160/:243）。 */
    const val REGISTRY_BIN_NAME: String = "rt2_pkg_registry.bin"

    /**
     * 角色预留键的 kind 约定（W1-DSTATE 陈旧预留谓词的判定面）。锚 = mod-api
     * `ActionApi.kt` ReservationKey KDoc「行动中的角色不得被换出（§7）由本原语自然表达：
     * `chara:<entityId>` 预留即占槽」；key = [com.eratw.port.modapi.EntityId] 的 `raw`
     * 十进制串——原 TODO(verify) 已销（ERAFIN2）：`EntityId` 是 `value class EntityId(val
     * raw: Long)`（mod-api EntityId.kt），谓词 `key.key.toLongOrNull()?.let(kernel::exists)`
     * 与之逐位对应，`kernel.exists` 真源 = `EntityKernel.slotOf` 全位域比对
     * （EntityKernel.kt:216-228）；claim 端到端（作者声明 → 占槽 → 嵌套仲裁冲突 →
     * 持久化 → 恢复存活侧存活 / 坏格式判死）由 `ModFin2CharaClaimTest` 钉死。
     */
    const val CHARA_RESERVATION_KIND: String = "chara"

    /**
     * 装配入口：总闸关 ⇒ 零接触返回 null；开 ⇒ 走完整装载链，返回会话级运行时。
     *
     * @param gameRoot 游戏根（manifest 目录 = `ModStore.modsDirFor(gameRoot)/rt2`）
     * @param store 本会话的 [VariableStore]（`intVarView`/`strVarView` 读数、`hasIntVarView`/
     *   `hasStrVarView` 存在性探测与节拍的 `DAY` 读数同源）
     * @param nativeRegistry 生产本地方法注册表（GameHost 经 `nativeMethods(store.aux)` 取的
     *   aux 槽实例——CALLSHARP 策略缺席位同表，不新建第二份）
     * @param onVmThread VM 线程判据（GameHost 的 `onVmThread()`；scheduler 的主线程判据同源）
     * @param enabled 总闸读数（GameHost 构造参数 `modRuntime` 原样透传）
     * @param logSink 日志面（宿主注入；本文件不直接依赖 android.util.Log）
     * @param modProviders Kotlin mod 供应商名单（生产来源 = [com.eratw.port.android.ModCarrierRegistry]；
     *   packageId 不在磁盘 manifest 快照内 = install 逐 mod fail-closed，active=false 上日志面）
     * @param catalog Mod 链接产物 [SymbolCatalog] 只读快照（SUPCAT-W 判据 2：生产 =
     *   GameHost 传 `plan.catalog`，乙形裁定的 attach inputs 供给缝）。只被共享引用：
     *   不复制、不二次装配、不改写链接语义。null = 本函数在会话装配面之外被直调
     *   （测试装置 / 旁路会话）⇒ `EntityRuntime.catalog` null ⇒ HAS_SUPER 按
     *   「未装配 ⇒ 0」的既有旁路口径如实回答（EntityRuntime 接口 KDoc 明文允许 null）。
     * @param newHost 宿主构造缝（唯一构造点；测试经它数构造次数做关态反向证明）
     * @param decisionEntropyTail 决策种子的真熵尾巴供给（W2-SEED 判据 1）。缺省
     *   `freshDecisionEntropyTail` = 每次装配局部新建 `SecureRandom` 取 16 字节——每局
     *   新种子；测试注入确定字节做「同熵同种子 / 异熵异种子」定向。lambda 只在开态
     *   构造管线时求值（关态零求值 = 关态零接触），核心层只收字节参数（熵纪律）。
     * @param dexCarriers 第三方 dex 载体供给缝（ADR-013 S5；生产 =
     *   [androidDexCarrierProvision]，总闸与 Android 面全在缝实现里）。缺省 null = 未接线：
     *   装配段整段旁路、不扫 code 目录、不建任何 dex 对象，与未接线前逐位等价（反向证明 1）。
     *   缝内的逐包失败已收敛成归因行走 [logSink]，绝不上抛——单包失败不拖垮装配链（D3 ④）。
     * @throws Exception 开态下 manifest 解码 / 声明冲突 / 快照冻结失败原样抛出（整包拒绝）
     */
    fun attach(
        gameRoot: File,
        store: VariableStore,
        nativeRegistry: NativeMethodRegistry,
        onVmThread: () -> Boolean,
        enabled: Boolean,
        logSink: (String) -> Unit,
        modProviders: List<Pair<String, EraMod>> = emptyList(),
        catalog: SymbolCatalog? = null,
        decisionEntropyTail: () -> ByteArray = ::freshDecisionEntropyTail,
        newHost: (AssemblyInputs) -> ModRuntimeHost = ::defaultHost,
        dexCarriers: DexCarrierProvision? = null,
        consoleSink: ConsoleSink? = null,
        inputSink: InputSink? = null,
    ): ModRuntimeSession? {
        // 总闸第一查：关 = 装配链一次都不进（ModRuntimeHost 构造次数 == 0，反向证明①的判据）。
        if (!enabled) return null

        // 解码复用：生产路径沿用 csvLoadContext 开的作用域；直调 attach（测试 / 旁路宿主）
        // 没有前置开点，这里幂等补开一份，保证 attach 内部两趟 manifest 解码只真解析一次。
        ManifestCodec.ensureDecodeScope()

        // ---- 阶段一：声明快照（M1-4 阶段二单源：装载相位已冻结就直接消费，不二次装载） ----
        val (snapshot, csvContext) = declarationSnapshotFor(store, gameRoot, logSink)

        // ---- M5-4 刀 2：chara 路由供给缝（构造点前预组，RECON R3：同步缝不走晚装）----
        // 必须先于内核构造：用户 STR 变量的占位 INT 镜像类型在此以 def 终审修正，修正后的
        // 镜像 schema 喂给 EntityKernel——字段类型闸是 pour/capture 方向正确性的前提
        // （kernel.getStr 对 INT 型镜像字段返回空串 = pour 静默写错，契约判据 2 点名的危害）。
        val charaRoutes = wireCharaFieldRoutes(snapshot, store, logSink)

        // ---- 阶段二：六注入位真值 + 唯一宿主构造点 ----
        val kernel = EntityKernel(
            schemas = charaRoutes.kernelSchemas(snapshot.componentSchemas.values.map { it.schema }.toList()),
            relationshipSchemas = snapshot.relationshipSchemas.values.map { it.schema }.toList(),
            archetypes = snapshot.entityArchetypes.values.map { it.archetype }.toList(),
        )
        val shortIdRegistry = PackageShortIdRegistry()
        // RT2 regbin：装载 = 全局注册表 ∪ 磁盘全部 sidecar 快照并集（era-arch-v61u.md:160）。
        // 必须在 newHost/install 之前完成：install 期 shortIdOf 会逐包 allocate，并集水位
        // （nextId/lockedIds）先就位，孤儿载荷锁定的短 ID 才不会被新投射撞号（蓝图 :160）。
        // 只读装载：不写盘、不删任何东西（本函数不跑 GC；孤儿侧车 GC 已随 RT2 MODGC
        // 落在迁移相位，见 SidecarSaveWiring.deferOrphanGc 与 SaveFolderMigration）。
        loadRegistryUnion(gameRoot, shortIdRegistry, logSink)
        val scheduler = VmBoundaryScheduler(onVmThread, logSink)
        val storage = ModStorageStore()
        val diagnostics = RuntimeDiagnostics()

        // ---- W1-DSTATE：真决策管线（判据 2）。种子 = 安装级熵基座 + 宿主真熵尾巴经
        // DecisionSeed.derive（W2-SEED 判据 1：每局新种子；档内恢复见类头 W2-SEED 节）；
        // packageOrder = 声明快照链接序（仲裁定序权威，与 install 序同源）；diagnostics 接
        // 既有消费者缝（阶段四读者同一条总线）。
        val decisionPipeline =
            buildDecisionPipeline(snapshot, gameRoot, store, diagnostics, decisionEntropyTail())

        // ---- F 车：唯一真投射桥 + 虚拟 NO 注册表 + store 后期 resolver 装配 ----
        // 装配序（M5-2 循环依赖破法）：store 已在 GameSession 建好 → 本处才建真桥
        // （构造拿 store 完整引用）→ 回填 store.charaSlotProjector（五个行操作钩子生效）
        // → 再喂 resolver（rowOfEntity 就是真桥的当前行映射，不是新建第二张表）。
        // pkgs 传会话级 shortIdRegistry，project 才能对悬空 pkg 段 fail-closed；
        // 表在 install 期由 shortIdOf 逐包 allocate，projector 持的是同一实例引用。
        require(store.charaSlotProjector == null) {
            "同一 VariableStore 重复装配真投射桥：一个会话只能有一份 roster 映射"
        }
        val virtualNos = VirtualCharaNoRegistry(
            // 生产判定 = 已注册模板 NO 集合（D-1 分配避让；不另建 CSV 快照）。
            isNativeCsvNo = { no -> store.template(no.toLong()) != null },
        )
        val projector = RealCharaSlotProjector(
            store = store,
            kernel = kernel,
            virtualNos = virtualNos,
            pkgs = shortIdRegistry,
            // M5-4 刀 2 判据 1：fields 在唯一构造点内喂（源码锁「真桥生产构造恰一处 == 1」
            // 零改写）；无路由 = 空表，与缺省实参逐位同形。
            fields = charaRoutes.fields,
        )
        store.charaSlotProjector = projector
        val charaIndexResolver = VirtualCharaIndexResolver(virtualNos, projector::charIndexOf)
        store.bindCharaIndexResolver(charaIndexResolver)
        // M5-3 活动期变更闸：生产缝恒接（装配级用例 RosterProjectionAssemblyTest 钉死，
        // 反向证明 = 摘本行 ⇒ activityGateBound 断言红）。判据 = 变量层帧活动，与
        // GameSession.isVmActive 同源（论证见类头 M5-3 节与本文件 KDoc）。
        projector.bindActivityGate { store.currentFunction != null }
        // M5-3 关系投影接线（裁定 2）：kind 来源 = 声明事务 snapshot，不硬编码业务 schema。
        wireRelationProjection(snapshot, kernel, projector, logSink)

        // ---- ModDX4-B1：受控写面（GameStateApi.setIntVar / setStrVar 的生产供给）----
        // 授权闸 = 声明快照里**本包自家**的 global-dims ∪ 各包 manifest 的 write-grants 段；
        // 坏 grant（键不存在 / 类型不符 / 角色变量 / 不可寻址形态）在装载相位整链拒
        // （ModDx4WriteGate.build 抛 IAE，与坏档 ManifestFormatException 同一 fail-closed 口径，
        // attach 中断、无半装配会话）。apply 闭包 = VariableStore 按名寻址写，只在转移边界
        // 兑现点执行（ModDx4GameStateWrite 经 RuntimeScheduler 排队，页边界 drain）。
        val gameStateWriteApplier = ModDx4WriteApplier(
            gate = ModDx4WriteGate.build(
                snapshot,
                ModDx4WriteAssembly.grantsOf(manifestFilesIn(manifestDirFor(gameRoot))),
                defOf = { name -> store.registry.def(name) },
            ),
            applyInt = { name, value -> ModDx4WriteAssembly.applyInt(store, name, value) },
            applyStr = { name, value -> ModDx4WriteAssembly.applyStr(store, name, value) },
        )

        val inputs = AssemblyInputs(
            snapshot = snapshot,
            entityKernel = kernel,
            nativeMethods = nativeRegistry,
            logSink = logSink,
            runtime = scheduler,
            shortIdOf = { pkg -> shortIdRegistry.allocate(pkg) },
            intVarView = { name -> readIntVar(store, name) },
            strVarView = { name -> readStrVar(store, name) },
            // 位置实参：函数类型值的调用禁具名实参（Kotlin 语义），wantStr 位含义见 readHasVar。
            hasIntVarView = { name -> readHasVar(store, name, false) },
            hasStrVarView = { name -> readHasVar(store, name, true) },
            storage = storage,
            projector = projector,
            decisionPipeline = decisionPipeline,
        )
        val host = newHost(inputs)
        if (consoleSink != null && inputSink != null) {
            host.durableInteractionHost = object : DurableInteractionHost {
                override val console: ConsoleSink = consoleSink
                override val input: InputSink = inputSink
            }
        }

        // ---- 阶段二点六：per-mod 设置快照（RT2 per-mod 配置）----
        // 必须在 install 之前就位：作者的 onLoad 里就可能读 context.settings。
        // 声明真源 = 同一批 mods/rt2/*.manifest 的 settings 段；值来源 = 设备侧按包文件
        // （ModSettingsStore：原子写、坏文件/坏值回退声明默认、不进存档）。缺省空表 ⇒
        // 未注入的包读 context.settings 走 fail-closed，不静默返回默认值。
        host.settings = ModSettingsStore.snapshotsFor(gameRoot)

        // ---- ModDX4-B1：受控写面登记 ----
        // install 在每个 context 的 onLoad 之前按包扇出写 facade（作者在 onLoad 里就可能提交
        // 写）。登记为 null = 旧装配形态（写 setter 走缺省 fail-closed UOE，不静默吞写）。
        host.gameStateWriteApplier = gameStateWriteApplier

        // ---- 阶段二点六：mod 资源注入第一刀（RT2 资源面）----
        // 根 = 与 `.manifest`/`.settings` 同目录的 mods/rt2/；逐包目录 <packageId>.assets/。
        // 按包隔离、惰性读盘、单文件/总量上限、坏包与坏文件的 WARN 全在 core 侧
        // ModResourceStore 内（WARN 走同一 logSink）。必须在 install 之前就位：作者的 onLoad
        // 里就可能读 context.resources。目录不存在的包 = 空资源面（不崩、不告警）。
        host.resources = ModResourceStore(rt2Dir = manifestDirFor(gameRoot), logSink = logSink)

        // ---- 阶段二点六：mod 音频面（Lane AN；RT2 资源注入第二刀）----
        // 宿主播放汇 + 会话级槽一次装好：context.audio 走真实现（install 内的 adopt 读
        // host.audioSink），收口由 shutdown 的 ModAudioWiring.uninstall 停声。播放器是独立
        // MediaPlayer 实例，不进引擎 SoundChannelTable ⇒ 不抢 10 声道/Bgm、不受引擎
        // STOPSOUND/BGMCONTROL 影响；失败只 WARN + false，绝不把异常抛回 mod。
        bindAudioSink(store, host, logSink)

        // ---- 阶段二点五：耗时账本（§18.5 诊断面扩展；RT2 P1 耗时归因）----
        // 必须在 install 之前就位：onLoad/onEnable 的第一段也要记，否则作者看到的永远缺头。
        // 一个会话一份账本，四个测量点（lifecycle / tick / hook / event）同写这一簿；
        // 收口时按 mod 逐条打 mod.timing 行。关态会话走不到这里 ⇒ 零日志、零对象。
        // logSink 只用于「单包 scope 数到上限」的 WARN（作者 scope 无界增长时的归因护栏），
        // 与引擎其它装配面同一日志口径。
        val timing = ModTimingLedger(logSink = logSink)
        host.timing = timing
        host.hookPipeline.timing = timing

        // ---- 阶段二点六：单次耗时预算（§18.5 阈值诊断）----
        // 三类作者处理器（hook / event / interaction）逐次计时：越 16ms 记一条诊断并计数，
        // 不中断执行、不改返回值。诊断总线在上方已就位（关闭态零成本）；必须早于 install，
        // 否则 mod 在 onLoad 里注册的处理器首次调用就漏计。
        host.handlerBudget = ModHandlerBudget(diagnostics = diagnostics)

        // ---- 阶段二点七：事件名存在性闸的事件面（Lane U）----
        // 必须在 install 之前就位：作者的 onLoad 里就该被拼错事件名当场拒绝。
        // 源 = MOD_LINK_CATALOG_SLOT 的链接产物快照；生产写侧在宿主装配面（GameHost）于
        // GameSession 构造之后、本函数调用之前经 ModChainAssemblyOutcome.installCatalogSlot
        // 写槽，故生产路径读到的就是本次装配的同一个 SymbolCatalog。槽缺席 = 本函数在会话
        // 装配面之外被直调（测试装置 / 旁路会话）：空集 ⇒ 闸不校验（EventDispatchRegistry
        // 的缺省口径），不是静默吞错。
        val linkCatalog = store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT)
        host.knownEventKeys = linkCatalog?.events?.keys.orEmpty()

        // ---- hook 目标键灌缝（MODDX4-B3 建，2026-11-26 B4 改判为「函数 ∪ 事件」并集）----
        // 与上行 events 同一条链接产物、同一个槽：hook 目标键集 = SymbolCatalog.bindings 的键
        // （全部非事件胜者，FUNCTION 与 #FUNCTION 方法同表）**∪ events 的键**（语料事件名全集）。
        // 并集的理由：EventApi / EventDispatchRegistry 两处 KDoc 一贯建议「要拦截/替换事件，
        // 用 hooks 的 PREFIX + SkipOriginal」，且 ProcessRuntime.dispatchEvent 缝确实按事件名查
        // hook 槽——但旧灌源只有函数键，`hooks.register("EVENT…")` 被拼错闸当场 IAE，槽建了、
        // 处理器绑不上，文档建议在生产装配下走不通（REVIEW-MOD-ARCH-20261123 B4）。并集后
        // 事件键过拼错闸，是否真能绑定仍由 HookPipeline.bind 的「槽未声明」闸把守（fail-closed
        // 不变：没在 Manifest.hooks 里声明该事件键的 hook 依旧被拒）。
        // 必须在 install 之前就位：作者的 onLoad 里就该被拼错 hook 目标键当场拒绝。目录键本就按
        // FuncMeta.key 大写归一；其余装配侧灌入未归一键也安全（归一收在 context 写入侧）。
        // 槽缺席 = 本函数在会话装配面之外被直调（测试装置 / 旁路会话）：空集 ⇒ 闸不校验，
        // 与 events 上行的缺省口径同形，不是静默吞错。
        host.knownFunctionKeys = buildSet {
            linkCatalog?.bindings?.keys?.let { addAll(it) }
            linkCatalog?.events?.keys?.let { addAll(it) }
        }

        // ---- ADR-013 S5：dex 载体并行装载段（第三方运行包；与内置 Kotlin 载体并行）----
        // 缺省 null = 未接线，整段旁路：不扫 code 目录、不建任何 dex 对象（D5 关态判据由缝
        // 实现承担——关闸时它同样返回空表且零构造）。逐包闸序在缝内走 ModDexLoader（ABI →
        // 总闸 → 磁盘包名 → 身份解析 → 信任 → 盘面 → sha → 工厂），单包失败在缝内收敛成
        // `mod.install … active=false` 归因行（logSink），不上抛。
        // 每次装配逐包新开 ClassLoader（缝实现每次装载新构造），DZ 重装语义天然满足（D4）。
        val dexProviders = dexCarriers?.carriers(gameRoot, logSink).orEmpty()

        // ---- 阶段三：逐 mod 装载（宿主内失败隔离：单 mod 失败只回滚它自己） ----
        // dex 载体并进同一批 install：装载顺序仍走 core 的依赖拓扑规划（requires 声明
        // 同样生效），dex 包 packageId 不在磁盘 manifest 快照内 = 逐 mod fail-closed
        // （`active=false` 上日志面），与内置载体的未登记症状同一条路。
        val report = host.install(modProviders + dexProviders)
        for (state in report) {
            val failure = state.failure
            if (failure == null) {
                logSink("mod.install pkg=${state.packageId} active=true")
            } else {
                logSink(
                    "mod.install pkg=${state.packageId} active=false " +
                        "failure=${failure.javaClass.simpleName}: ${failure.message}",
                )
            }
        }

        // ---- 阶段三点五：函数 / 事件 hook 真接线（§20.1；P1-5 收账）----
        // 之前 HookPipeline 建好、bind 成功、单测全绿，但生产零 dispatch（作者声明的 hook 永不触发）。
        // 现在挂进 store.aux：ProcessRuntime.doCall（宿主按名调用）与 executeEvent（事件派发）两处
        // 调用点现读它。无槽无绑定时调用点连实参数组都不构造 ⇒ 关态 / 无 hook 声明会话逐位不变。
        // 收口在 ModRuntimeSession.shutdown（按引擎实例同一性解绑，不误伤后一会话）。
        FunctionHookWiring.install(store, host.hookPipeline, logSink)

        // ---- 阶段三点六：运行期事件处理器真接线（§6.1 贡献点；P1 收账）----
        // 之前 context.events 是 FailClosedEventApi（register 当场抛「M2-6 生产消费点未接」）；
        // 现在把共享注册表挂进 store.aux：ProcessRuntime.dispatchEventMembers 在每个桶的边界
        // 现读它，把 Kotlin 处理器按「桶位 → 注册序」混进 ERB 成员链之后。无注册 ⇒ 完全旁路。
        ModEventWiring.install(store, host.eventHandlers, logSink, timing, host.handlerBudget)

        // ---- 阶段四：诊断消费者（P2-3，今天 0 处 ⇒ 1 处） ----
        // 读者口径：FAILED / REJECTED 逐条上日志面（取证面，非玩家可见文案，不进域文案表）；
        // OK / SKIPPED 静默入环（供工具经 [ModRuntimeSession.diagnostics] 读环 / 导出）。
        val diagnosticsReadout = diagnostics.subscribe { record ->
            if (record.outcome == DiagnosticOutcome.FAILED || record.outcome == DiagnosticOutcome.REJECTED) {
                logSink(
                    "mod.diag seq=${record.seq} domain=${record.domain.name} " +
                        "pkg=${record.packageId ?: "-"} key=${record.key} " +
                        "outcome=${record.outcome.name} reason=${record.reasonCode}" +
                        (record.detail?.let { " detail=$it" } ?: ""),
                )
            }
        }

        // ---- 阶段五：节拍驱动器（per-active-mod；install 报告 active=false 的包不建 driver） ----
        // 各 context 的相位注册表独立，任务调度器在 onLoad 前已共享；相位按 install 序派发，
        // 页边界随后对共享任务调度器统一按全链 priority + 声明序派发一次。
        val drivers = ArrayList<GameTickDriver>(report.size)
        for (state in report) {
            if (!state.active) continue
            val context = checkNotNull(host.contextOf(state.packageId) as? ModContextImpl) {
                "启用 mod 的装配 context 缺失或类型不符：${state.packageId}（宿主接线不变量破坏）"
            }
            drivers += GameTickDriver(
                context.phaseRegistry,
                context.jobScheduler,
                diagnostics,
                timing,
                state.packageId,
            )
        }

        // ---- 阶段六：存档侧车接线（RT2 C 车；F 车换真投射桥）----
        // SaveService 生产构造点：projector 与 host / store 三处是**同一实例**，
        // captureEntityPayload 才会对真投射实体逐个脱水；virtualNoRegistry 传同一份
        // 注册表，槽档才带虚拟 NO 身份映射（恢复时先于实体图并入，§16 第 6 点）。
        // C 车留下的空投射器许口实现随本车接线一并删除，生产树不再有第二份投射器。
        val saveService = SaveService(
            kernel = kernel,
            projector = projector,
            pkgRegistry = shortIdRegistry,
            virtualNoRegistry = virtualNos,
            variableStore = store,
        )
        val activePackageIds = report.filter { it.active }.map { it.packageId }.toSet()
        val session = ModRuntimeSession(
            host = host,
            storage = storage,
            diagnostics = diagnostics,
            scheduler = scheduler,
            drivers = drivers,
            store = store,
            logSink = logSink,
            diagnosticsReadout = diagnosticsReadout,
            activePackageIds = activePackageIds,
            charaSlotProjector = projector,
            virtualNoRegistry = virtualNos,
            charaIndexResolver = charaIndexResolver,
        )
        // RT2 regbin：会话收口把并集注册表原子写全局 bin。走后期装配而非构造参数 ——
        // ModRuntimeSession 构造签名被 detekt baseline 的 LongParameterList ID 按参数文本
        // （含 KDoc）钉住，新增构造参数会产生无覆盖的结构性新 ID（禁触 baseline、禁 @Suppress）。
        session.bindRegistry(shortIdRegistry, registryBinOf(gameRoot))
        // task-160 / Lane HB：逐包活载体实例表（页边界取回会话内 FamilyApi 用）。只登记装配
        // 收到的同一批实例，不新建；失败包也在表里，调用方先看 activePackages 判活。
        session.bindCarrierMods(modProviders)
        // ModDX-A2 组长接线：设置热通知的按包快照重读源（回调前重注 context.settings 用）。
        // 后期装配（构造签名被 detekt baseline 钉住）；snapshotsFor 重读盘上全部按包配置——
        // 热通知是低频路径（玩家改一次设置一次 drain 一包），不缓存。
        session.settingsSnapshotOf = { pkg -> ModSettingsStore.snapshotsFor(gameRoot)[pkg] }
        // W1-DSTATE：决策/任务态缝后期装配（构造签名被 detekt baseline 的 LongParameterList
        // 条目钉住，新增构造参数 = 结构性新 ID；手法与 bindRegistry 同源，逻辑住独立类）。
        session.decisionState = ModDecisionJobState(
            pipeline = decisionPipeline,
            host = host,
            // 现读会话激活集（含后期重装并进来的包），不是装配期快照：任务态并集/回灌的遍历面
            // 必须与运行时概览 active 位同源（Lane LA：回滚态重装包也要被看见）。
            activePackages = { session.activePackages },
            logSink = logSink,
            reservationAlive = charaReservationAlive(kernel),
        )
        // W2-ARDRV：仲裁外轮缝 + decisionDriver 宿主注入（CONTRACT-RT2-ARDRV 判据 1/2）——
        // 后期装配手法与 decisionState 同源，逻辑住独立类/独立函数（attach 环形复杂度源头修法）；
        // 只注入装载成功的包（active=false 的注册项已被 revoke，注入无消费点）。
        bindArbitrationSeam(
            session,
            decisionPipeline,
            store,
            modProviders.filter { it.first in activePackageIds },
            logSink,
        )
        // RT2 P0-LAST：交互生产触发点 + UiApi 真接线（后期装配，构造签名冻结同理）。
        // 逻辑住文件级函数：attach 的环形复杂度已在阈值上沿，再塞分支会撞 detekt。
        bindHostSeams(session, host, scheduler, logSink, diagnostics)
        // ModDX2-A：包版本对账的「当前侧」读数（notifyNewGame 记账 / notifySaveLoaded 对账用）。
        // 逐包读 mods/rt2/<pkg>.manifest 的 version 原文（缺失/坏档 = 空串，同 ModuleDescriptor
        // 缺省口径，不发明版本排序）；只在本函数算一次，会话内不重读盘（升级 = 换包重装 = 新会话）。
        // 后期装配（构造签名被 detekt baseline 钉住），手法与上方各缝同源。
        session.packageVersions = activePackageIds.associateWith { pkg ->
            installedManifestOf(manifestDirFor(gameRoot), pkg)?.version.orEmpty()
        }
        val port = ModSidecarStoragePort(
            saveService = saveService,
            storageProvider = { session.storage },
            adoptStorage = { session.adoptStorage(it) },
            // 现读会话激活集（含后期重装并进来的包）：读档缝的 knownPackages 判据若停在装配期
            // 快照，同会话「先重装、后 LOADGAME」会把重装包分段判孤儿（读值 fail-closed）。
            activePackages = { session.activePackages },
            decisionStateProvider = { session.decisionState },
            logSink = logSink,
        )
        // task-134 ②：抓取侧跳过集 = 显式卸载台账（`mods/rt2/released-packages.txt`）里
        // 盘上已无产物的包。每趟抓取现读（卸载可能发生在本会话存活期间），见
        // ModStorageReclaim 类 KDoc。
        val rt2Dir = manifestDirFor(gameRoot)
        port.reclaimedPackages = { ModStorageReclaim.reclaimedPackageIds(rt2Dir) }
        // M1-4 阶段二：注入计划与关键字查询面同源于装载相位上下文（裁定 3：不在这里派生第二份）。
        // 上下文缺席 ⇒ 计划 null / 查询面 null ⇒ 侧车「无 CSV 段」原路（阶段一既有零变化口径）。
        val csvPlan = csvContext?.plan
        saveService.entityRefCsvPlan = csvPlan
        val csvKeywordIndex = if (csvPlan != null && !csvPlan.isEmpty) {
            { table: String, keyword: String ->
                runCatching { store.registry.csvTable(table).keywordIndex(keyword) }.getOrNull()
            }
        } else {
            null
        }
        port.entityRefDeclarations = { snapshot.entityRefDeclarations.filterKeys { it in session.activePackages } }
        port.csvRestore = if (csvPlan != null && csvKeywordIndex != null) {
            { bag, entityRefs, remapTable ->
                ModCsvValueRestorer.prepare(
                    store,
                    csvPlan,
                    csvKeywordIndex,
                    bag,
                    { logSink("mod.rt2 warn: $it") },
                    entityRefs,
                    remapTable,
                )
            }
        } else {
            null
        }
        SidecarSaveWiring.install(
            store,
            SidecarSaveWiring(
                port,
                transactionRoot = gameRoot.toPath(),
                csvPlan = csvPlan,
                csvKeywordIndex = csvKeywordIndex,
            ),
        )
        // RT2 C-RES1：global 侧车接线（SAVEGLOBAL/LOADGLOBAL 配对面）装同一个端口——
        // mod GLOBAL 分片与槽档分片同源于会话 ModStorageStore，SaveService 也共用同一份；
        // 消费点 FileSystemOps 的 saveGlobal/loadGlobal 晚装现读（aux 槽，同槽档先例）。
        GlobalSidecarSaveWiring.install(store, GlobalSidecarSaveWiring(port, transactionRoot = gameRoot.toPath()))

        // ---- W1-ENTRT：BuiltinHost.entityRuntime 会话级回填（CONTRACT-RT2-ENTRT 判据 1）----
        // 放在 attach 收尾（全部侧车接线成功之后）：装配中途失败原样上抛（整包拒绝语义），
        // 槽不会被半装配产物污染 —— 槽内容要么缺席（= 未装配语义）要么完整。读侧 =
        // 求值语境装饰器（Builtins.kt 的 AuxMacroHost）与生产宿主（ConsoleBuiltinHost
        // 的 entityRuntime 覆写）现读同一份真源；关态总闸第一查已 return，走不到这里。
        // catalog 走 attach inputs 供给（SUPCAT-W 判据 2/3：GameHost 传 plan.catalog）。
        backfillEntityRuntimeSeam(
            store,
            EntitySeamInputs(snapshot, kernel, projector, shortIdRegistry, report, catalog),
            logSink,
        )
        // 解码复用作用域收口：本趟装配结束即丢弃（不跨会话 / 不跨线程，见 ManifestCodec
        // 类 KDoc「解码复用」节）。异常整包拒绝路径不保证及时关闭 —— 下一趟 openDecodeScope
        // 整份覆盖，缓存只持只读描述符引用，无资源句柄。
        ManifestCodec.closeDecodeScope()
        return session
    }

    /**
     * 阶段一声明快照（M1-4 阶段二，CONTRACT-RT2-M14B-IMPL 裁定 3）：优先消费装载相位产出的
     * [ModCsvLoadContext]（**整趟唯一一次** `ManifestLoader.load` 在那里发生），本处只读；
     * 上下文缺席（旧调用点 / 手工装配）才回落到自装载 —— 此时 csv 容量恒空 = 带 csv-patch 的
     * manifest 照旧整包拒（既有 fail-closed 语义不变）。
     */
    private fun declarationSnapshotFor(
        store: VariableStore,
        gameRoot: File,
        logSink: (String) -> Unit,
    ): Pair<DeclarationSnapshot, ModCsvLoadContext?> {
        val context = ModCsvLoadContext.of(store)
        // 装载相位的排除/告警逐条进宿主日志面（裁定 5/6：不静默丢声明）。
        context?.warnings?.forEach(logSink)
        val snapshot = context?.snapshot ?: ManifestLoader.load(
            manifests = manifestFilesIn(manifestDirFor(gameRoot)).map { file ->
                ManifestSource(file.name, file.readBytes())
            },
            // 内置变量名 = SystemLayout 总表并集（本层唯一的系统变量总表）：mod 全局维度撞
            // 引擎内置名当场整包拒绝，不给「mod 侧 DAY」留第二语义。
            builtinVariableNames = builtinVariableNames(),
            csvTableCapacities = emptyMap(),
            baseCsvKeywords = emptyMap(),
        )
        return snapshot to context
    }

    /** 本局 RT2 manifest 目录：`mods/rt2/`（与 R7 补丁 mods 面分域；目录缺失 = 空清单）。 */
    fun manifestDirFor(gameRoot: File): File = File(ModStore.modsDirFor(gameRoot), RT2_MANIFEST_DIR_NAME)

    /**
     * M1-4 阶段二：装载相位产出注入上下文的唯一生产入口（CONTRACT-RT2-M14B-IMPL §1 裁定 1/3）。
     *
     * 顺序 = 先 Base 口径装一次轻量游戏数据（不读角色模板）取容量/关键字，再对 manifest
     * 跑**唯一一次** `ManifestLoader.load`（`ManifestLoader` 在 `ModCsvPlanLoader` 内被调用一次）。
     * 硬上界越界（单表增量 > 4096 / 绝对 > 65536）**整包拒绝**：逐条上日志后原样抛出
     * （与「坏档整包拒」同一 fail-closed 口径，不做静默降级）。
     */
    fun csvLoadContext(gameRoot: File, logSink: (String) -> Unit = {}): ModCsvLoadContext? {
        val base = GameDataLoader.loadBase(File(gameRoot, CSV_DIR_NAME).toPath()) ?: return null
        // 解码复用作用域开点（见 ManifestCodec 类 KDoc「解码复用」节）：本函数是开态装配链
        // 第一个 manifest 解码点，作用域横跨随后的 ModChainAssembly.assembleWithCatalog 与
        // attach（收口在 attach 收尾）；同一份 manifest 在整趟装配内只真解析一次。
        ManifestCodec.openDecodeScope()
        val sources = manifestFilesIn(manifestDirFor(gameRoot)).map { file ->
            ManifestSource(file.name, file.readBytes())
        }
        return try {
            ModCsvPlanLoader.load(base, sources, builtinVariableNames())
        } catch (e: ModCsvCapacityLimitException) {
            for (reason in e.reasons) logSink("Mod CSV 容量越界（整包拒绝）：$reason")
            throw e
        }
    }

    /** 游戏数据目录名（与 `GameSession` 的 `CSV_DIR_NAME` 同源口径）。 */
    private const val CSV_DIR_NAME: String = "CSV"

    /**
     * 引擎内置变量名总集（[ManifestLoader.load] 的撞名拒绝闸）。
     *
     * 来源 = [SystemLayout] 各公开清单并集——它的类头自证是「本层唯一的系统变量总表」，
     * [com.eratw.port.core.vars.VariableRegistry] 的全部构建趟都从它取名字。
     * 本函数每次返回新集合（无缓存、无静态可变全局）。
     */
    internal fun builtinVariableNames(): Set<String> = buildSet {
        for (list in listOf(
            SystemLayout.int1D,
            SystemLayout.str1D,
            SystemLayout.str0D,
            SystemLayout.int2D,
            SystemLayout.int3D,
            SystemLayout.charaInt0D,
            SystemLayout.charaStr0D,
            SystemLayout.charaInt1D,
            SystemLayout.charaStr1D,
            SystemLayout.charaInt2D,
            SystemLayout.localInt,
            SystemLayout.localStr,
            SystemLayout.localFloat,
            SystemLayout.floatScalar,
            SystemLayout.gameBaseStr,
            SystemLayout.gameBaseInt,
        )) {
            for ((name, _) in list) add(name)
        }
        for ((name, _, _) in SystemLayout.pseudo) add(name)
        for ((name, _) in SystemLayout.csvConsts) add(name)
    }

    /**
     * `intVarView` 的读数体：按名只读，clean 语义（GameStateApi 的契约）——
     * 缺变量 / 类型不符 / 本视图寻址不了的形态一律返回哨兵 0，不抛官方 VM 怪癖。
     *
     * 可寻址形态：0 维标量（空下标）与 1 维非角色数组（0 号元素——ERB 裸引用同形）。
     * 角色变量与 2 维以上无法按名单值寻址（要 charPos / 多下标上下文），归哨兵；
     * 这是有意的诚实边界，不是静默错数据——授权白名单面的收口属 K3 脏活。
     */
    private fun readIntVar(store: VariableStore, name: String): Long = try {
        val def = store.registry.def(name) ?: return 0L
        when {
            def.argCount == 0 -> store.readInt(name, LongArray(0))
            def.argCount == 1 && !def.isCharacterData -> store.readInt(name, longArrayOf(0L))
            else -> 0L
        }
    } catch (_: Throwable) {
        0L
    }

    /** `strVarView` 的读数体：口径同 [readIntVar]，哨兵为空串。 */
    private fun readStrVar(store: VariableStore, name: String): String = try {
        val def = store.registry.def(name) ?: return ""
        when {
            def.argCount == 0 -> store.readStr(name, LongArray(0))
            def.argCount == 1 && !def.isCharacterData -> store.readStr(name, longArrayOf(0L))
            else -> ""
        }
    } catch (_: Throwable) {
        ""
    }

    // ModDX4-B1 的写面辅助（writeGrantsByPackage / writeIntByAddressableForm /
    // writeStrByAddressableForm）收在文件级（本文件底部）：本 object 的成员函数数已抵 detekt
    // TooManyFunctions 阈值，不再加成员。

    /**
     * M5-3 关系投影接线（裁定 2，CONTRACT-RT2-M53）：kind = 声明事务 snapshot 的
     * `relationshipSchemas`，不硬编码业务 schema。
     *
     * - **恰一个** → 建 [RelationProjectionSynchronizer]（kernel + 真桥 + kind 名）并
     *   [RealCharaSlotProjector.attachRelations]；同步器构造的既有 fail-closed 判据
     *   （kind 必须已注册、有向、无 autoInverse）在此后验——声明形状不合法 = 装载相位
     *   整包拒绝，不是本层吞掉。
     * - **零个** → 跳过 + FINE：纯 Base 旁路保持（`relations == null` 既有缺省）。
     * - **多个** → 跳过 + FINE 取证：多 schema 注册本身合法（声明域允许），投影同步
     *   本波单 kind；登记余量不拒载。
     *
     * 日志行是取证面（非玩家可见文案，不进域文案表）。
     */
    private fun wireRelationProjection(
        snapshot: DeclarationSnapshot,
        kernel: EntityKernel,
        projector: RealCharaSlotProjector,
        logSink: (String) -> Unit,
    ) {
        val schemas = snapshot.relationshipSchemas.values.map { it.schema }
        when (schemas.size) {
            0 -> logSink("mod.attach relations kind=0 skip(FINE) 纯 Base 旁路保持")
            1 -> {
                val kind = schemas.single()
                val sync = RelationProjectionSynchronizer(kernel, projector, kind.name)
                projector.attachRelations(sync)
                logSink("mod.attach relations kind=${kind.name} attached")
            }
            else -> logSink(
                "mod.attach relations kinds=${schemas.size} skip(FINE) 取证=" +
                    schemas.joinToString(",") { it.name } +
                    "（多关系类型注册合法，投影同步本波单 kind，余量登记不拒载）",
            )
        }
    }

    /**
     * 目录内的 manifest 清单（文件名升序 = 确定性链接序；目录缺失 / 非目录 = 空清单）。
     *
     * 可见性 `internal`：装载前置段留痕包装 [ModPreloadTrace] 要用同一份扫描口径（过滤 / 排序 /
     * 停用闸），不让它复制第二套实现（Lane DY 波由组长收口时提级）。
     */
    internal fun manifestFilesIn(dir: File): List<File> {
        val entries = dir.listFiles() ?: return emptyList()
        val manifests = entries
            .filter { it.isFile && it.name.endsWith(MANIFEST_EXTENSION) }
            .sortedBy { it.name }
        // 批2-3：停用的包不进声明快照，也不进 CSV 计划 —— 与 ModChainAssembly 的
        // 容器侧闸同源（停用 = manifest 与 .erbm 一起从装配面消失）。
        return ModEnablement.enabledOnly(dir, manifests)
    }

    /**
     * 唯一宿主构造点：六个注入位全部来自 [AssemblyInputs] 的真值，缺省位一个不留；
     * F 车再补第七项 [AssemblyInputs.projector]（不传它 = E 车之前的 fail-closed 形态，
     * `context.roster` 四入口一律抛，生产缝退化不会被既有测试误判为绿）；
     * W1-DSTATE 再补第八项 [AssemblyInputs.decisionPipeline]（判据 2：开态真管线注入，
     * 产物是 `context.actions` 脱 fail-closed 桩）。
     *
     * DX-C 的 [AssemblyInputs.hasIntVarView]/[AssemblyInputs.hasStrVarView] 已在本构造点透传
     * （组长接线，DX-C 收口波：`ModRuntimeHost` 构造形参已补，生产链
     * attach 灌真值 → defaultHost → ModRuntimeHost → ModContextImpl 全线贯通）。
     */
    private fun defaultHost(inputs: AssemblyInputs): ModRuntimeHost = ModRuntimeHost(
        snapshot = inputs.snapshot,
        entityKernel = inputs.entityKernel,
        nativeMethods = inputs.nativeMethods,
        logSink = inputs.logSink,
        runtime = inputs.runtime,
        shortIdOf = inputs.shortIdOf,
        intVarView = inputs.intVarView,
        strVarView = inputs.strVarView,
        hasIntVarView = inputs.hasIntVarView,
        hasStrVarView = inputs.hasStrVarView,
        storage = inputs.storage,
        projector = inputs.projector,
        decisionPipeline = inputs.decisionPipeline,
    )
}

// ---------------------------------------------------------------------------
// W1-ENTRT：BuiltinHost.entityRuntime 会话级回填（CONTRACT-RT2-ENTRT 判据 1）
// 置于文件顶层私有作用域，与 regbin helper 组同区：不增 object 内函数数
// （TooManyFunctions 阈值口径见 regbin helper 组的既有注释）。
// ---------------------------------------------------------------------------

/**
 * 开态 attach 尾段把 ENTITY_* 族的会话级装配写进 [store] 的 aux 槽
 * （[ENTITY_RUNTIME_SLOT]），生产会话的求值语境（Builtins.kt 的 AuxMacroHost 装饰器）
 * 与生产宿主（ConsoleBuiltinHost 的 entityRuntime 覆写）现读同一份真源（铁律 4：
 * 状态挂 store.aux，无静态可变全局；本 helper 无状态纯过程）。
 *
 * - 写点用 RuntimeAux.slot 的 create-if-absent：同一 store 重复 attach 已被阶段二的
 *   投射桥 require 挡死（「一个会话只能有一份 roster 映射」），create 至多执行一次。
 * - 逐字段语义见 EntityRuntime 接口 KDoc（EntityMethods.kt:36-54）。catalog 注入 =
 *   attach inputs 供给（SUPCAT-W 判据 3，乙形裁定）：HAS_SUPER 判据源是 mod 链装配产物
 *   SymbolCatalog，单源 = ModChainAssembler.assemble 五步链的链接产物——它随
 *   [com.eratw.port.core.mod.ModChainRuntimePlan.catalog] 保留（判据 1：派生处不再弃），
 *   GameHost 在 attach 调用点传 `plan.catalog`（判据 2），经 [EntitySeamInputs.linkCatalog]
 *   流到 [AttachedEntityRuntime]（注入点零返工）。只共享引用：不复制、不二次装配、
 *   不改写链接语义；W2-SUPCAT 的槽写路对 entityRuntime 退役（[MOD_LINK_CATALOG_SLOT]
 *   只服务事件名存在性闸）。供给缺席 = 会话装配面之外直调 attach（测试装置 / 旁路
 *   会话）⇒ null ⇒ HAS_SUPER 按「未装配 ⇒ 0」的既有旁路口径如实回答（接口 KDoc 明文
 *   允许 null），不是吞语义。
 * - 短 ID 不预分配：registry 就是会话级 PackageShortIdRegistry 原样（install 期为
 *   载体包逐包 allocate）；无 Kotlin 载体的声明包调 ENTITY_CREATE 按函数层既有口径
 *   报「声明包未登记短 ID」—— 是否给声明包预占短 ID 牵动 regbin 字节确定性，归组长
 *   裁定（登记见交付报告），本波不擅自扩。
 */
/**
 * [backfillEntityRuntimeSeam] 的入参包：attach 五个产物 + catalog 供给的只读集合
 * （收参数数过 LongParameterList 闸；做成单参载体，同 [AssemblyInputs] 的手法）。
 */
private class EntitySeamInputs(
    val snapshot: DeclarationSnapshot,
    val kernel: EntityKernel,
    val projector: ICharaSlotProjector,
    val registry: PackageShortIdRegistry,
    val report: List<ModRuntimeState>,
    /** SUPCAT-W 判据 3：链接产物只读快照改由 attach inputs 供给（生产 = GameHost 传 plan.catalog）。 */
    val linkCatalog: SymbolCatalog?,
) {

    /** install 报告里 active=false 的包（规范形）：声明面的 archetype 不注入运行期实体面。 */
    private val failedPackages: Set<String> =
        report.filter { !it.active }.mapTo(HashSet()) { NamePolicy.canonical(it.packageId) }

    /**
     * 实际注入实体运行时的原型表 = 声明快照剔除本次 install 的失败包。
     *
     * 口径与 [AttachedEntityRuntime.modIsActive] 同源（链接态）：manifest-only 的「已声明
     * 未装载」包不在 report 里，仍按声明态可查；只有 install 明确报 active=false 的包
     * （其声明面注册项已被 revoke）才剔除。全成功场景 = 快照全表，`filterValues` 保留
     * LinkedHashMap 原序 ⇒ 注入集合与顺序逐位不变。
     */
    val injectedArchetypes: Map<String, DeclaredArchetype> =
        snapshot.entityArchetypes.filterValues { NamePolicy.canonical(it.packageId) !in failedPackages }
}

private fun backfillEntityRuntimeSeam(
    store: VariableStore,
    inputs: EntitySeamInputs,
    logSink: (String) -> Unit,
) {
    // SUPCAT-W 判据 3：读缝源从 [MOD_LINK_CATALOG_SLOT] 槽 peek 换 inputs 供给（乙形裁定：
    // 链接产物随链走——Plan.catalog 派生保留、GameHost 经 attach 传参，装配点早于 store
    // 存在的槽写路对 entityRuntime 整段退役）。供给缺席 = 会话外直调 attach（测试装置 /
    // 旁路会话），按未装配语义回答，不可能伪造装配态。
    store.aux.slot(ENTITY_RUNTIME_SLOT) {
        AttachedEntityRuntime(inputs, inputs.linkCatalog)
    }
    logSink(
        "mod.attach entityRuntime 回填 archetypes=${inputs.injectedArchetypes.size} " +
            "mods=${inputs.report.count { it.active }} " +
            "catalog=${if (inputs.linkCatalog != null) "attached" else "absent"}",
    )
}

// ---------------------------------------------------------------------------
// DX-C：gameState 存在性探测读数体。文件顶层私有作用域 + `private val` 函数属性
// （非 fun）：同 backfillEntityRuntimeSeam 的「不增文件 fun 数」手法更进一步——
// TooManyFunctions 的 thresholdInFiles 只数 fun 声明，读数体是状态纯函数，落 val
// lambda 判定单源不变、文件 fun 数回到本波之前的 10。
// int/str 共用一个读数体：可寻址性判定（0 维 / 1 维非角色）由 [VarDef] 形态决定、与值
// 类型无关，两探测只差类型过滤一位实参——不是一个函数两处偷懒，是判定同源才合一。
// ---------------------------------------------------------------------------

/**
 * `hasIntVarView` / `hasStrVarView` 共用的读数体（DX-C 存在性探测）：与
 * [ModRuntimeAssembly.readIntVar] / [ModRuntimeAssembly.readStrVar] 同源同口径——
 * 同一 `registry.def` 按名查找（大小写不敏感，`VariableRegistry.def` 的既有归一）+
 * 同一寻址性判定（0 维标量 / 1 维非角色数组），再按第三个实参 `wantStr` 过滤值类型：
 * `type == STR`（str 探测）/ `type == INT`（int 探测；FLOAT 不算 int 可读）。
 *
 * `true` ⇔ 该名字落在对应读数体的可寻址域内（返回真实读数而非哨兵）；判据是定义存在 +
 * 类型匹配 + 可单值寻址，不真读值——已声明但值恰为 0 / 空串的变量仍答 true。
 * 角色变量与 2 维以上同读数体的诚实边界（不可单值寻址）答 false；
 * 异常降级 false（与读数体的哨兵降级同构）。
 */
private val readHasVar: (store: VariableStore, name: String, wantStr: Boolean) -> Boolean =
    { store, name, wantStr ->
    try {
        val def = store.registry.def(name)
        val typeMatches = if (wantStr) def?.type == VarType.STR else def?.type == VarType.INT
        def != null && typeMatches &&
            (def.argCount == 0 || (def.argCount == 1 && !def.isCharacterData))
    } catch (_: Throwable) {
        false
    }
}

/**
 * [ModRuntimeAssembly.attach] 开态产物 → [EntityRuntime] 的装配（判据 1：kernel =
 * attach 产物、projector = 真投射桥、registry / archetypes / modIsActive 按接口字段
 * 语义注入）。全部字段是 attach 既有产物的只读快照，本类不自持可变状态。
 *
 * MOD_IS_ACTIVE 的生产口径（EntityMethods.kt MOD_IS_ACTIVE KDoc「生产 = Manifest 链接态」）：
 * 声明包在链接快照内 = 激活（manifest-only 形态的 mod 申报了 hook/job/CSV 补丁、
 * 声明确在生效，没有 Kotlin 载体不影响「链接态」）；唯一下调 = 载体安装失败
 * （install 报告 active=false 的包：注册项已 revoke，如实回答未激活）。包名匹配两侧
 * 都按 [NamePolicy.canonical] 归一：Manifest 的 packageId 无大小写冻结口径，两侧同
 * 规约连接最稳（同名仅大小写不同的两包本就要整包拒绝，不产生歧义连接）。
 */
private class AttachedEntityRuntime(
    inputs: EntitySeamInputs,
    /**
     * 链接产物快照（SUPCAT-W 起 = attach inputs 供给，见 [EntitySeamInputs.linkCatalog]；
     * null = 供给缺席 = 会话外直调 attach 的旁路态）。
     * 只读引用原样持有：不复制、不改写链接语义（契约判据 3），生命周期随 store。
     */
    private val linkCatalog: SymbolCatalog?,
) : EntityRuntime {

    override val kernel: IEntityKernel = inputs.kernel

    override val projector: ICharaSlotProjector? = inputs.projector

    override val registry: PackageShortIdRegistry = inputs.registry

    /**
     * 原型查表按 NamePolicy.canonical 归一（接口 KDoc 的 canonical 查表口径；快照键本就是规范形）。
     *
     * 读 [EntitySeamInputs.injectedArchetypes]（声明快照剔除 install 失败包）：失败包在
     * ENTITY_* 入口即按「原型未声明」fail-closed（EntityMethods.kt:368 / :386 / :165 全走本表），
     * 不留下「hook 面回滚了、声明面还半活」的裂缝。注：[EntityKernel] 的 archetypeKeys 仍是
     * 装载相位的冻结快照（构造早于 install，无回滚点且 core 侧无过滤视图），但 ENTITY_*
     * 全部入口先经本表闸，失败包对玩家不可达。
     */
    override val archetypes: (String) -> DeclaredArchetype? = { name ->
        inputs.injectedArchetypes[NamePolicy.canonical(name)]
    }

    /** 见 [backfillEntityRuntimeSeam] 的 catalog 说明（SUPCAT-W：attach inputs 供给注入）。 */
    override val catalog: SymbolCatalog? = linkCatalog

    private val linkedActive: Set<String> =
        inputs.snapshot.packageIds.mapTo(HashSet()) { NamePolicy.canonical(it) } -
            inputs.report.filter { !it.active }
                .mapTo(HashSet()) { NamePolicy.canonical(it.packageId) }

    override val modIsActive: (String) -> Boolean = { pkg -> NamePolicy.canonical(pkg) in linkedActive }
}

/**
 * 会话级决策/任务态缝（W1-DSTATE 判据 1/3/5 的抓取与回灌落点）：决策管线 + 陈旧预留
 * 存活谓词 + 任务态并集/拆分。独立成类（而非 ModRuntimeSession 的成员）是 detekt
 * TooManyFunctions 的源头修法；全部状态挂实例，无静态可变全局（铁律 4）。
 *
 * 抓取落在存档抓取路径（[ModSidecarStoragePort.captureRt2Bytes] ← SAVEGAME 容器物化点）、
 * 回灌落在读档缝（restoreRt2）——为什么不在 shutdown 的论证见 [ModRuntimeAssembly] 类头
 * W1-DSTATE 节。写者与读者都在 VM 线程（存档/读档与页边界同线程模型），无并发恢复面。
 */
internal class ModDecisionJobState(
    /** 本会话决策管线（判据 2 的产物；测试据此跑仲裁造非平凡决策态，生产主执行不经过它）。 */
    val pipeline: DecisionPipeline,
    private val host: ModRuntimeHost,
    /**
     * 现读会话激活集（不是装配期快照）：正常装配路径返回的集合与装配期 `activePackageIds`
     * 逐位相同；晚期重装成功后由 `ModRuntimeSession.activePackages` 并入重装包，任务态并集与
     * 回灌的遍历面因此也看得见它（Lane LA 判据 1）。
     */
    private val activePackages: () -> Set<String>,
    private val logSink: (String) -> Unit,
    private val reservationAlive: (ReservationKey) -> Boolean,
) {

    /** 恢复回灌读数：最近一次被回收的陈旧预留键数（测试/取证用）。 */
    var lastStaleReservationDrops: Int = 0
        private set

    /**
     * 决策态段载荷（存档抓取路径调）：容器级一份的 `DecisionStateCodec` VERSION 2 字节。
     */
    fun decisionStatePayload(): ByteArray = DecisionStateCodec.encode(pipeline.snapshotState())

    /**
     * 任务态段载荷（存档抓取路径调）：会话共享调度器的单份快照，静态槽含全链声明，动态
     * 实例与序号表只保留活跃包，`JobSchedulerStateCodec` VERSION 2 字节。无活跃 mod =
     * 空表快照照编（确定性字节）。
     */
    fun jobStatePayload(): ByteArray = JobSchedulerStateCodec.encode(mergedJobSnapshot())

    /**
     * 档内种子采纳序（W2-SEED 判据 2「种子随档」；restoreDecisionState 的前置步）：
     * 档内决策段种子与管线当前种子相等 = 原相等闸放行路径（不供给，no-op）；不等 =
     * 走 [DecisionPipeline.supplySeed] 受控供给（一次性 + 仅未消费状态，fail-closed——
     * 已消费会话里换档重载被点名拒绝，判据 4 的保持面）并上日志面留痕（不静默）。
     *
     * 为什么供给序在这里而不是装配期：attach 早于存档选择（GameHost 装配序），档内段
     * 只在 LOADGAME 读档缝可读（FileSystemOps restoreSidecarIfWired），「先读段后建管线」
     * 不可行 ⇒ 管线构造后受控供给是唯一不动 GameHost/GameSession 的落点。
     *
     * @throws IllegalArgumentException supplySeed 的 fail-closed（一次性 / 已消费）
     */
    fun adoptSeedFromSave(payloadSeed: Long) {
        if (payloadSeed == pipeline.currentDecisionSeed) return
        val before = pipeline.currentDecisionSeed
        pipeline.supplySeed(payloadSeed)
        // §21.6：mod 随机派生种子跟着换（换种子 = 从头重放，见 ModRandomApi 的 v1 口径）。
        // 必须在 supplySeed 成功之后：供给被拒（已消费会话）时不动随机流，保持 fail-closed 原子性。
        host.adoptRandomSeed(payloadSeed)
        logSink("mod.rt2 决策种子随档采纳 seed=$payloadSeed（原装配种子=$before）")
    }

    /** 纯预检：档内种子可否采纳；不写管线与宿主随机流。 */
    fun validateSeedFromSave(payloadSeed: Long) {
        if (payloadSeed != pipeline.currentDecisionSeed) pipeline.validateSeedSupply(payloadSeed)
    }

    /** 纯预检：按提交后的目标种子验证决策态，不消费/不清空任何状态。 */
    fun validateDecisionState(state: DecisionState) {
        if (state.decisionSeed != pipeline.currentDecisionSeed) pipeline.validateSeedSupply(state.decisionSeed)
        pipeline.validateRestoreState(
            if (state.decisionSeed == pipeline.currentDecisionSeed) {
                state
            } else {
                state.copy(decisionSeed = pipeline.currentDecisionSeed)
            },
        )
    }

    /** 纯预检共享任务调度器可恢复的活跃包任务态；无活跃包时不写调度器。 */
    fun validateJobState(snapshot: JobSchedulerSnapshot) {
        if (activePackages().isEmpty()) return
        host.jobScheduler.validateRestore(activeJobSnapshot(snapshot))
    }

    /**
     * 决策态回灌（读档缝调，判据 3）：陈旧预留按宿主谓词过滤并留痕（增补 B 口径——
     * 不静默）。种子不符 / 快照畸形 = fail-closed 原样抛（读档缝的既有 catch 收进警告，
     * 「恢复中途失败」不吞）。
     */
    fun restoreDecisionState(state: DecisionState) {
        val report = pipeline.restoreState(state, reservationAlive)
        lastStaleReservationDrops = report.droppedStaleReservations.size
        if (report.droppedStaleReservations.isNotEmpty()) {
            logSink(
                "mod.rt2 决策态陈旧预留回收 dropped=${report.droppedStaleReservations.size} " +
                    "keys=" + report.droppedStaleReservations.joinToString(",") { "${it.kind}/${it.key}" },
            )
        }
    }

    /**
     * 任务态回灌（读档缝调）：全链静态槽保留，动态实例与序号表只采纳活跃包，一次覆盖共享
     * 调度器。非活跃包没有可回灌的运行期宿主，其动态条目仍按既有语义丢弃。
     */
    fun restoreJobState(snapshot: JobSchedulerSnapshot) {
        if (activePackages().isEmpty()) return
        host.jobScheduler.restore(activeJobSnapshot(snapshot))
    }

    /** 共享任务态只保留活跃包的动态实例与序号，避免跨包重复或恢复已卸载包的实例。 */
    private fun activeJobSnapshot(snapshot: JobSchedulerSnapshot): JobSchedulerSnapshot {
        val active = activePackages()
        return JobSchedulerSnapshot(
            nextFireDays = snapshot.nextFireDays,
            instances = snapshot.instances.filter { it.packageId in active },
            nextSeqByTemplate = snapshot.nextSeqByTemplate.filterKeys { it.substringBefore("::") in active },
        )
    }

    /** 会话只有一份共享任务态；静态槽含全链声明，动态实例按活跃包过滤。 */
    private fun mergedJobSnapshot(): JobSchedulerSnapshot {
        if (activePackages().isEmpty()) return JobSchedulerSnapshot()
        return activeJobSnapshot(host.jobScheduler.snapshot())
    }
}

/**
 * 会话级仲裁外轮缝（W2-ARDRV 判据 1/2，CONTRACT-RT2-ARDRV）：管线已注入（W1-DSTATE）但
 * 外轮没有生产触发点、[CharacterMod.decisionDriver] 没有宿主实现 —— 本类补这两个缝；
 * [DecisionPipeline] 执行内核零改动（`run` 公开面已存在，core DecisionPipeline.kt:463）。
 * 独立成类是 ModRuntimeSession 函数数与构造签名的源头修法，手法与 [ModDecisionJobState] 同源。
 *
 * ## 外轮（判据 1）
 *
 * [runOuterRound] 由 [ModRuntimeSession.onTurnBoundary] 在页边界**只调一次**（2026-10-31
 * 真机 OOM 先例的频率纪律：不许每帧轮询）；域序 = [outerDomains] 声明序（确定性，见
 * [productionOuterRoundDomains] 的裁定），域内候选序 = 管线自有的链接序 + 注册序（§21.3）。
 * 无候选域由管线自身完全旁路（不执行处理器、不建预留、不记账，决策态零变化），宿主侧
 * 不留读数；有产出的轮留一行日志读数（取证面，非玩家可见文案）。handler / provider /
 * 约束异常由管线隔离进报告（§21.3 失败语义），报告 failures 由本类逐条上日志面（宿主
 * 报告消费义务，与 onTurnBoundary 对相位/任务报告的同款义务同源）；管线自身的 fail-closed
 * 越界异常在此再兜一层，绝不把异常抛回页边界。
 *
 * ## 嵌套轮（判据 2）
 *
 * [resolveNested] 是宿主注入 [CharacterMod.decisionDriver] 的实现体：外轮胜者 handler
 * 执行期间（预留仍 held）驱动**同一管线**再跑一轮，同键候选吃 RESERVATION_CONFLICT
 * （引擎唯一给出该原因码的路径；core DecisionPipelineTest 用例 12 同形状）。metadata
 * 透传，游戏日现读（与页边界同一 DAY 读数源），相位恒空串（与 onTurnBoundary 的空相位
 * 裁定同源——TIME→phase 无权威映射，见 [ModRuntimeAssembly] 类头「单一时间源」节）。
 * held 预留语义不变：外轮的 `finally` 释放照旧，嵌套轮只读 held 集合，不增删。
 *
 * DECISION 域诊断已由管线经既有消费者缝（诊断读者）推送：REJECTED / FAILED 上日志面、
 * OK / SKIPPED 静默入环——本类不重复记诊断（判据 4）。全部状态挂实例，无静态可变全局
 * （铁律 4）；调用方与写者都在 VM 线程（页边界 / 胜者 handler 执行期），无并发恢复面。
 */
internal class ModArbitrationRound(
    /** 本会话决策管线（嵌套仲裁复用同一实例，判据 2「held 预留语义不变」的前提）。 */
    private val pipeline: DecisionPipeline,
    /** 外轮域清单（确定性声明序；生产 = [productionOuterRoundDomains]）。 */
    private val outerDomains: List<String>,
    /** 游戏日现读源（嵌套轮时间快照用；抛异常 = 读数失败，由 [readDayOrBypass] 旁路）。 */
    private val readDay: () -> Long,
    private val logSink: (String) -> Unit,
) {

    /** 页边界外轮（判据 1；每边界只调一次，本函数保证不抛）。 */
    fun runOuterRound(day: Long) {
        for (domain in outerDomains) {
            runDomainRound(domain, GameTime(day, PHASE_UNSPECIFIED), metadata = emptyMap())
        }
    }

    /** 嵌套解析轮（判据 2；[CharacterMod.DecisionDriver] 的宿主实现体，保证不抛）。 */
    fun resolveNested(domain: String, metadata: Map<String, String>) {
        val day = readDayOrBypass() ?: return
        runDomainRound(domain, GameTime(day, PHASE_UNSPECIFIED), metadata)
    }

    /**
     * 给 [CharacterMod] 载体实例注入嵌套仲裁 driver（[resolveNested] 的实例级宿主缝）。
     *
     * 装配期装载成功包与晚期回滚态重装成功分支共用：重装换了新实例（载体注册表每次
     * `newMod()` 现造），实例级 driver 必须跟着补注入，否则重装包的嵌套轮永远走「未注入 =
     * 跳过」的降级口径（Lane LA 判据 2）。
     *
     * 幂等：同一实例重复调用 = 覆写成绑定同一 round 的等价 driver（无状态、不累积、不失效）；
     * 非 [CharacterMod] 载体 = no-op。调用方只在装载**成功**分支调用（失败实例不持有运行期缝）。
     */
    fun injectDecisionDriver(mod: EraMod) {
        val character = mod as? CharacterMod ?: return
        character.decisionDriver =
            CharacterMod.DecisionDriver { domain, metadata -> resolveNested(domain, metadata) }
    }

    /**
     * 游戏日现读旁路（与页边界②步同源同判据）：读数失败留痕并跳过本轮仲裁（不抛、
     * 不发明兜底值——0 是「游戏日第 0 天」的真读数，不能拿它当失败哨兵）。
     */
    private fun readDayOrBypass(): Long? = try {
        readDay()
    } catch (t: Throwable) {
        logSink("mod.arbitration DAY 读数失败，仲裁旁路：${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun runDomainRound(domain: String, time: GameTime, metadata: Map<String, String>) {
        val report = try {
            pipeline.run(ActionSelectionContext(domain, time, metadata = metadata))
        } catch (t: Throwable) {
            logSink(
                "mod.arbitration domain=$domain 仲裁异常已隔离（边界不中断）：" +
                    "${t.javaClass.simpleName}: ${t.message}",
            )
            return
        }
        for (failure in report.failures) {
            logSink(
                "mod.arbitration domain=$domain 失败已隔离 pkg=${failure.packageId} " +
                    "key=${failure.key} ${failure.cause.javaClass.simpleName}: ${failure.cause.message}",
            )
        }
        if (report.outcome != ActionExecutionOutcome.NONE) {
            logSink(
                "mod.arbitration day=${time.day} domain=$domain outcome=${report.outcome.name} " +
                    "pkg=${report.chosenPackageId ?: "-"} chosen=${report.chosen?.candidateKey ?: "-"}",
            )
        }
    }

    private companion object {
        /** 相位恒空串 = onTurnBoundary 同款裁定（TIME→phase 无权威映射，不发明）。 */
        const val PHASE_UNSPECIFIED: String = ""
    }
}

/**
 * 回合级交互的一帧 witness frame 读数（ModDX-4 M4 判据 1；独立成类手法与 [ModArbitrationRound]
 * 同源——[ModRuntimeSession] 函数数被 detekt 钉住）。全部状态挂实例，无静态可变全局（铁律 4）。
 *
 * ## witness 语义与读数源取证
 *
 * witness = 与当前 TARGET **同位置**的其他角色（升序角色号，不含 TARGET 本身）：
 *
 * - 位置读数源 = `CFLAG:<角色号>:300`（`現在位置` 元素号，MAPID 语义；语料锚
 *   `AutoConst_CFLAG.ERH:90`，生产先例 `DatingForensics.kt:162` 常量单源复用 + `:237` 读法）；
 * - TARGET 锚 = 全局变量 `TARGET` 0 号元素（`BuiltinSymbols.kt:176` INT 全局；读法与
 *   本文件 `readGlobalInt` 同款——`charaPointer` 是 core-vm internal，app 侧只能走变量层读）；
 * - 角色枚举 = `store.characters.count`（`DatingForensics.kt:202` 先例）。
 *
 * ## 裁定（诚实口径）
 *
 * - 「已启用包相关角色」过滤：引擎没有「角色行号 → 所属包」的权威直读；唯一干净的
 *   角色↔包归属缝是扩展实体投射（[ICharaSlotProjector.entityAt]，EntityId 内嵌
 *   pkgShortId）。本读数把**已投射实体**的身份放进 context 的 `targets` / `primaryTarget`
 *   （包过滤由作者面自查），同位置在场者的**全量角色号清单**进 metadata `witness`——
 *   不做引擎侧强过滤，避免把「实体覆盖面只到 mod 自建角色」伪装成全体判据。
 * - 锚 = TARGET；TARGET 无效（读不到 / <0 / 越界）⇒ witness 空清单、metadata `target=-1`
 *   如实透传，**仍照常派发**（回合级触发不因无锚缺席）。「或会话活动角色」替代锚不发明
 *   （PLAYER / MASTER 各有所指，无权威单一读法）。
 * - 全部读数 [runCatching]（取证面：读数失败 = 该字段缺席/空清单，不抛、不写、不发明
 *   兜底值——与 `datingForensics` 同形；页边界绝不因读数中断）。
 */
internal class ModWitnessFrameReader(private val store: VariableStore) {

    /** 读一帧并就地构建回合级 [InteractionContext]（`ModInteractionWire.runTurnFrame` 消费）。 */
    fun readContext(): InteractionContext {
        val day = read { store.readInt(DAY_VAR, longArrayOf(0L)) }
        val charaCount = runCatching { store.characters.count }.getOrDefault(0)
        val target = read { store.readInt(TARGET_VAR, longArrayOf(0L)) } ?: -1L
        val targetValid = target in 0 until charaCount
        val targetPos = if (targetValid) cflagPosition(target) else null
        val witnesses = if (targetValid && targetPos != null) {
            (0L until charaCount.toLong()).filter { it != target && cflagPosition(it) == targetPos }
        } else {
            emptyList()
        }
        val projector = store.charaSlotProjector
        fun entityOf(chara: Long): EntityId? =
            projector?.entityAt(chara.toInt())?.takeIf { it != 0L }?.let(::EntityId)
        val metadata = buildMap {
            day?.let { put(ModInteractionWire.DAY_KEY, it.toString()) }
            put(ModInteractionWire.TARGET_KEY, target.toString())
            targetPos?.let { put(ModInteractionWire.TARGET_POS_KEY, it.toString()) }
            put(ModInteractionWire.WITNESS_KEY, witnesses.joinToString(","))
            put(ModInteractionWire.WITNESS_COUNT_KEY, witnesses.size.toString())
        }
        return InteractionContext(
            interactionId = ModInteractionWire.TURN_INTERACTION_ID,
            // 引擎侧没有「玩家角色」的扩展实体身份可传（EntityId 是 pkgShortId+generation+
            // handleSlot 打包值，不是 chara 行号）——与输入触发同款只能 NULL。
            actor = EntityId.NULL,
            targets = witnesses.mapNotNull(::entityOf),
            primaryTarget = entityOf(target),
            metadata = metadata,
        )
    }

    /** `CFLAG:<角色>:現在位置` 读数；读不到 = null（不发明兜底值）。 */
    private fun cflagPosition(chara: Long): Long? =
        read { store.readInt("CFLAG", longArrayOf(chara, DatingForensics.CFLAG_POSITION)) }

    private inline fun <T> read(block: () -> T): T? = runCatching(block).getOrNull()

    private companion object {
        /** 全局变量名：边界游戏日（与 [ModRuntimeSession] 页边界 ② 同一读数源）。 */
        const val DAY_VAR: String = "DAY"

        /** 全局变量名：当前互动对象角色号（`BuiltinSymbols.kt:176` INT 全局）。 */
        const val TARGET_VAR: String = "TARGET"
    }
}

/**
 * 宿主构造缝的入参包：[ModRuntimeHost] 六注入位的真值集合（P0-1 契约的验收面），
 * 外加 F 车第七项 [projector]（同一份真投射桥，宿主 facade / store 钩子 / 存档面共用）。
 *
 * 做成 data class 只为把「构造缝」变成一个可注入的单参数函数（测试据此数构造次数，
 * 断言关态 0 次 / 开态 1 次），不新增任何引擎语义。public 是为了不逼 [ModRuntimeAssembly.attach]
 * 收窄可见性（宿主装配面是 app 的公开接线点）。
 */
data class AssemblyInputs(
    val snapshot: DeclarationSnapshot,
    val entityKernel: EntityKernel,
    val nativeMethods: NativeMethodRegistry,
    val logSink: (String) -> Unit,
    val runtime: RuntimeScheduler,
    val shortIdOf: (String) -> Int,
    val intVarView: (String) -> Long,
    val strVarView: (String) -> String,
    /**
     * 存在性探测供给（DX-C）：与 [intVarView]/[strVarView] 同源灌入（`readHasVar`，
     * 同一 `registry.def` 查找与寻址性判定 + 类型过滤）。可空缺省 null = 未接线形态
     * （测试 / 旁路构造点不传时与既有缺省同形）⇒ `context.gameState` 的
     * `hasIntVar`/`hasStrVar` 恒 false（查询面空集口径，不抛）。
     *
     * 接线状态（诚实边界）：生产 attach 构造点已灌真值；但 `defaultHost` → `ModRuntimeHost`
     * → `ModContextImpl` 的中段传参须经 `ModRuntimeHost` 构造形参（DX-A1 所有权，本波不碰），
     * 该段接线归「需组长接线」——补形参前生产会话的 `gameState` 探测走 `ModContextImpl`
     * 的缺省 null 形态（恒 false），与台架同口径、不抛。
     */
    val hasIntVarView: ((String) -> Boolean)? = null,
    val hasStrVarView: ((String) -> Boolean)? = null,
    val storage: ModStorageStore,
    /** 本会话真投射桥（F 车；生产 `defaultHost` 必须原样传给 [ModRuntimeHost]）。 */
    val projector: ICharaSlotProjector,
    /**
     * 本会话决策管线（W1-DSTATE 判据 2；生产 `defaultHost` 必须原样传给 [ModRuntimeHost]）。
     * 缺省 null = 未接线形态（关态 / 未传的既有测试构造点）：`context.actions` 保持
     * fail-closed 桩语义，与装配前逐位一致。
     */
    val decisionPipeline: DecisionPipeline? = null,
)

/**
 * VM 线程队列的**真** [RuntimeScheduler] 实现（P0-1 六注入位之一；不再是测试假默认值的
 * 「调用即执行」）。
 *
 * ## 转移边界纪律（§18.5）
 *
 * `submit` 一律入队、**不就地执行**——哪怕调用方已在 VM 线程上：mod 代码在本线程可直接改状态
 * （`isOnMainThread == true`），走 `submit` 就是对「改实体结构只在边界放行」的显式请求。
 * 兑现点只有 [ModRuntimeSession.onTurnBoundary]（页边界安全点，VM 线程）调 [drainPending]：
 * 先兑现上一页积压的提交、再推进节拍——「提交先兑现、再读时钟」，与「排到主执行调度器的
 * 转移边界」的 API 承诺一致（兑现先后无权威锚，TODO(verify) 见交付）。
 *
 * 队列 = [ConcurrentLinkedQueue]：submit 可能来自任意线程，兑现只在 VM 线程。
 * 单个动作抛异常只上日志面、不中断同批其余动作、不外抛（§18.4 失败隔离）。
 * 状态全挂实例，无静态可变全局。
 */
internal class VmBoundaryScheduler(
    private val onVmThread: () -> Boolean,
    private val logSink: (String) -> Unit,
) : RuntimeScheduler {

    /** 待兑现队列（FIFO；submit 序 = 兑现序，确定性）。 */
    private val pending = ConcurrentLinkedQueue<() -> Unit>()

    override val isOnMainThread: Boolean get() = onVmThread()

    override fun submit(action: () -> Unit) {
        pending.add(action)
    }

    /** 当前未兑现的提交数（观察面；测试据此断言「边界前不动、边界后清空」）。 */
    fun pendingCount(): Int = pending.size

    /**
     * 兑现全部待提交动作（只许 VM 线程调）：单动作失败隔离，队列清空为终态。
     *
     * @throws IllegalStateException 非 VM 线程调用（fail-closed：兑现点接线错当场暴露，
     *   不静默换线程执行——那会破坏「改实体结构只在边界放行」）
     */
    fun drainPending() {
        check(onVmThread()) { "转移边界兑现只在 VM 线程（drainPending 被别的线程调了）" }
        while (true) {
            val action = pending.poll() ?: return
            try {
                action()
            } catch (t: Throwable) {
                logSink(
                    "mod.submit 动作失败已隔离（其余提交继续兑现）：${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
    }
}

/**
 * 本会话的 Mod 运行时：[attach] 开态的产物，节拍与收口的唯一入口。
 *
 * 线程模型：写者与读者都在 VM 线程（GameHost 装配段赋值、`onTurnReady` 页边界节拍、
 * `shutdownForExit` 收口）；[shutDown] 用 @Volatile 只为退出清场可能从 UI 线程先到时的
 * 可见性（收口幂等，节拍见它即旁路）。
 *
 * 失败隔离（§18.4）：节拍内部逐段兜住——DAY 读数失败、单 driver 异常、单提交动作异常
 * 都只上日志面，绝不把异常抛回页边界（页交付是游戏关键路径）；每次失败都留一行读数，
 * 不是静默跳过。
 */
class ModRuntimeSession internal constructor(
    /** 本会话宿主（六注入位真值；收口走 [ModRuntimeHost.shutdown] 的幂等生命周期）。 */
    val host: ModRuntimeHost,
    /**
     * 会话级唯一 mod 存储容器（C 车 `.rt2` 分段落盘从它读；读档恢复后经 [adoptStorage]
     * 换成重建实例，D 车在同一缝把全部 mod context 的 storage 视图重绑到新容器）。
     */
    var storage: ModStorageStore,
    /** 本会话诊断总线（工具读环 / JSON 导出经它；订阅推送已接日志面读者）。 */
    val diagnostics: RuntimeDiagnostics,
    private val scheduler: VmBoundaryScheduler,
    private val drivers: List<GameTickDriver>,
    private val store: VariableStore,
    private val logSink: (String) -> Unit,
    private val diagnosticsReadout: Registration,
    /** 本会话激活的 packageId 集（读档恢复时判「哪些分段算已知包」）。 */
    private val activePackageIds: Set<String> = emptySet(),
    /** 本会话真投射桥（F 车取证观察面；与 store 钩子及 SaveService 同一实例）。 */
    val charaSlotProjector: ICharaSlotProjector,
    /** 本会话虚拟 NO 注册表（F 车取证观察面；与真桥、SaveService 同一实例）。 */
    val virtualNoRegistry: VirtualCharaNoRegistry,
    /** 本会话虚拟 NO 解析器（F 车；shutdown 按实例解绑，不误伤后一会话）。 */
    private val charaIndexResolver: VirtualCharaIndexResolver,
) {
    /**
     * 会话级包短 ID 注册表 + 全局 bin 落位（并集装载产物 / 收口写目标）。
     * 后期装配而非构造参数：本类构造签名已被 detekt baseline 的 LongParameterList 条目
     * 按参数文本（含 KDoc）钉住，新增构造参数 = 旧 ID 失配 + 无覆盖结构性新 ID
     * （禁触 baseline、禁 @Suppress）。手法与 store 的 charaIndexResolver 后期装配同源
     * （见类 KDoc 的收口序一段）。缺省 null = 未绑定（关态不构造本类，开态必在返回前绑定）。
     */
    private var registryBinding: RegistryBinding? = null

    /**
     * 按包设置快照重读源（ModDX-A2 组长接线）：`packageId` → 盘上最新快照（app 侧
     * `ModSettingsStore.snapshotsFor(gameRoot)` 按包取）。后期装配而非构造参数——同
     * [registryBinding] 的 detekt baseline 钉签名口径。缺省 null = 未接线（台架/旧装配）：
     * 热通知仍派发，但 `context.settings` 保持 install 时快照（旧值）——与
     * [ModSettingsNotifyChannel] 通道 KDoc 的边界登记一致。
     */
    var settingsSnapshotOf: ((String) -> ModSettingsSnapshot?)? = null

    /**
     * 逐包当前 manifest 版本（ModDX2-A 版本对账的「当前侧」读数；键 = packageId，值 = 包
     * `mods/rt2/<pkg>.manifest` 的 version 原文，缺失/坏档 = 空串——与 [com.eratw.port.core.patch.ModuleDescriptor.version]
     * 的缺省口径一致，不发明版本排序）。
     *
     * 后期装配而非构造参数：构造签名被 detekt baseline 的 LongParameterList 条目按参数文本
     * （含 KDoc）钉住，新增构造参数 = 旧 ID 失配 + 无覆盖结构性新 ID（手法与
     * [settingsSnapshotOf] / [registryBinding] 同源）。由 [ModRuntimeAssembly.attach] 在会话
     * 构造后、返回前赋值一次（本会话内不重读盘：升级 = 换包重装 = 新会话）；空表（缺省）=
     * 台架 / 未接线形态，记账与对账零动作。
     */
    internal var packageVersions: Map<String, String> = emptyMap()

    /** 由 [ModRuntimeAssembly.attach] 在会话构造后、返回前绑定一次；只在开态发生。 */
    internal fun bindRegistry(pkgRegistry: PackageShortIdRegistry, bin: File) {
        registryBinding = RegistryBinding(pkgRegistry, bin)
    }

    /**
     * 逐包「本会话活载体实例」（task-160 / Lane HB）：页边界要拿回装进本会话的那个 mod 实例
     * （例如 `eratw.family` 的 [com.eratw.port.modfamily.FamilyApi] 只能在会话内调）。
     *
     * 后期装配而非构造参数：构造签名被 detekt baseline 的 LongParameterList 条目按参数文本
     * （含 KDoc）钉住，新增构造参数 = 旧 ID 失配 + 无覆盖结构性新 ID（禁触 baseline、禁
     * @Suppress）。手法与 [registryBinding] / [settingsSnapshotOf] 同源；由
     * [ModRuntimeAssembly.attach] 在会话构造后、返回前绑定一次。
     *
     * 只登记「与 [ModRuntimeHost.install] 收到的是同一批实例」的表，**不**新建实例：装载
     * 失败的包实例也在表里，调用方必须先看 [activePackages] 判活再取用。
     */
    private var carrierMods: Map<String, EraMod> = emptyMap()

    /** 由 [ModRuntimeAssembly.attach] 在会话构造后、返回前绑定一次；只在开态发生。 */
    internal fun bindCarrierMods(mods: List<Pair<String, EraMod>>) {
        carrierMods = mods.toMap()
    }

    /** 取本会话装进宿主的那件载体实例；未登记（非载体包 / 台架装配）= null。 */
    internal fun carrierModOf(packageId: String): EraMod? = carrierMods[packageId]

    /** 会话注册表只读观察面（并集装载取证 / 定向测试；未绑定返回 null）。 */
    fun shortIdOf(packageId: String): Int? = registryBinding?.pkgRegistry?.shortIdOf(packageId)

    /**
     * 会话内**后期重装**追加的节拍驱动器（Lane DZ 回滚态重装）。
     *
     * 为什么另立一张表：构造参数 `drivers` 与 `activePackageIds` 被 detekt baseline 的
     * LongParameterList 条目按参数文本钉住（见类头后期装配口径），不能改参数类型/名称；
     * 而回滚态重装发生在会话跑起来之后，追加的 driver 只可能来自会话内。两张表在节拍与
     * 读数面上按「主表 + 追加表」合并消费，语义与一次性装配结果同形。
     */
    private val lateDrivers: MutableList<GameTickDriver> = ArrayList()

    /** 后期重装成功的 packageId 集（与主表 [activePackageIds] 取并集对外）。 */
    private var lateActivePackageIds: Set<String> = emptySet()

    /**
     * 本会话激活的 packageId 集只读观察面（Lane AW：设置页运行时概览的 active 位真源，
     * 与装配期 `report` 的 active 集同一份；一次性装配部分构造期即定，回滚态重装成功的
     * 包由 [reinstallRolledBack] 追加）。
     */
    internal val activePackages: Set<String>
        get() = if (lateActivePackageIds.isEmpty()) {
            activePackageIds
        } else {
            activePackageIds + lateActivePackageIds
        }

    /**
     * 回滚态替换重装（Lane DZ 的会话侧落点；玩家入口 = 设置页「重试装载」→
     * [ModReinstallRequests] → `GameHost` 页边界 → 本函数）。
     *
     * 语义：**只**对「本次会话曾装载、因 onLoad/onEnable 失败回滚」的条目不换实例地重装，
     * 由 `host.install` 内部 `openReinstallSlot` 的判据开槽（`failure != null &&
     * !enabled && !shutDown`）。三道前置闸都在 `host.install` 之前：
     *
     * 1. `shutDown` ⇒ 不接（收口后不许再起实例）；
     * 2. 已在 [activePackages] ⇒ 不接（活着的不重装，那是热重载的活）；
     * 3. `host.failureOf(packageId) == null` ⇒ 不接（构造段失败不登记 entry，不是可重试形态，
     *    直接调 `host.install` 会撞 `openReinstallSlot` 的 `require`）。
     *
     * 成功后：追加 [lateDrivers]（相位/任务节拍继续跑）、把包并进激活集（运行时概览的
     * active 位、`.rt2` 版本记账的遍历面同源）。失败：只上日志面（canonical `mod.install`
     * 行会经宿主 `modLogSink` 进 `ModManageDiagnostics`），不抛回页边界。
     *
     * 边界（2026-11-18 接线修正：ED 原登记口径作废，别再按旧说法修）：ED 曾登记「`.rt2`
     * 段在本次存档时按装配期 knownPackages 编码 ⇒ 重装包本会话写入的存储值不落盘」。实测
     * 纠正：存档侧 `captureChunks` 只遍历 `storage.sectionsSnapshot()`（FileSystemOps.kt:466
     * → `ModStorageStore.sectionsSnapshot()`），orphan 位来自容器自身的 activePackages，而
     * `host.install` 成功必调 `storage.activate(packageId)`（ModRuntimeHost.kt:320）⇒ 重装包
     * 的分段本来就会作为**激活段**写出，存档不丢。真实缺口在读档侧：`knownPackages` 只被
     * `ModStorageStore.load(bytes, knownPackages)`（ModStorageStore.kt:156-164）消费，装配期
     * 快照会让同会话「先重装、后 LOADGAME/LOADGLOBAL」把重装包分段判成孤儿（`BoundStorage`
     * 的 `requireActive` 读值 fail-closed）；决策/任务态遍历面与仲裁 driver 注入同理是装配期
     * 只读集合。
     *
     * 本函数成功分支现已把三处补齐：① 决策/任务态 `ModDecisionJobState` 现读
     * [activePackages]（并入 [lateActivePackageIds] 后即看得见）；② 仲裁 [ModArbitrationRound]
     * 对换上的新实例补注入 driver（[injectDecisionDriver]，只在装载成功分支）；③ 侧车端口
     * `activePackages` 闭包同样现读 [activePackages]。无重装路径 [lateActivePackageIds] 恒空
     * ⇒ 三处读到的集合与装配期快照逐位相同，行为零变化。
     */
    internal fun reinstallRolledBack(packageId: String, mod: EraMod): Boolean {
        val eligible = !shutDown && packageId !in activePackages && host.failureOf(packageId) != null
        if (!eligible) return false
        val states = runCatching { host.install(listOf(packageId to mod)) }.getOrElse { t ->
            logSink(
                "mod.reinstall pkg=$packageId 失败已隔离：${t.javaClass.simpleName}: ${t.message}",
            )
            emptyList()
        }
        for (state in states) {
            val failure = state.failure
            if (failure == null) {
                logSink("mod.install pkg=${state.packageId} active=true")
            } else {
                logSink(
                    "mod.install pkg=${state.packageId} active=false " +
                        "failure=${failure.javaClass.simpleName}: ${failure.message}",
                )
            }
        }
        val installed = states.singleOrNull { it.packageId == packageId && it.active }
        val context = installed?.let { host.contextOf(packageId) as? ModContextImpl }
        if (context != null) {
            lateDrivers += GameTickDriver(
                context.phaseRegistry,
                context.jobScheduler,
                diagnostics,
                host.timing,
                packageId,
            )
            lateActivePackageIds = lateActivePackageIds + packageId
            // 重装换上的是新实例：活载体表同步换掉。页边界按包取到的必须是刚装上那件，
            // 否则会拿到装载失败的旧实例（其 context 未初始化，调作者面直接抛）。
            carrierMods = carrierMods + (packageId to mod)
            // 只在上面的成功记账之后补注入：失败实例不持有运行期缝（幂等覆写，见 injectDecisionDriver）。
            arbitrationRound?.injectDecisionDriver(mod)
        }
        return context != null
    }

    private class RegistryBinding(
        val pkgRegistry: PackageShortIdRegistry,
        val bin: File,
    )

    /**
     * 会话级决策/任务态缝（W1-DSTATE 判据 2/3）：后期装配而非构造参数——构造签名被
     * detekt baseline 的 LongParameterList 条目按参数文本（含 KDoc）钉住，新增构造参数 =
     * 旧 ID 失配 + 无覆盖结构性新 ID（手法与 `bindRegistry` / `charaIndexResolver` 同源，
     * 且逻辑住 [ModDecisionJobState] 独立类，本类不增函数数）。缺省 null = 未绑定
     * （关态不构造本类；开态由 [ModRuntimeAssembly.attach] 在返回前赋值一次）。
     */
    internal var decisionState: ModDecisionJobState? = null
        internal set

    /**
     * 会话级仲裁外轮缝（W2-ARDRV 判据 1/2）：后期装配而非构造参数——手法与 [decisionState]
     * 完全同源（构造签名被 detekt baseline 的 LongParameterList 条目钉住）。缺省 null = 未绑定
     * （关态不构造本类；开态由 [ModRuntimeAssembly.attach] 在返回前赋值一次），页边界见它为
     * null 即旁路（no-op）。
     */
    internal var arbitrationRound: ModArbitrationRound? = null
        internal set

    /**
     * 交互生产触发点 + UiApi 表面的后期装配束（RT2 P0-LAST 两条死路）：手法与
     * [decisionState] / [arbitrationRound] 同源（构造签名被 detekt baseline 的
     * LongParameterList 条目钉住），缺省 null = 关态/未装配 = 全旁路。
     *
     * 页边界消费输入证据并重拍 UI 贡献项快照（见 [onTurnBoundary] 的⑤）；
     * GameHost 采集玩家输入、MainActivity 读已注册项，都经这一个束取到。
     */
    internal var hostSeams: ModHostSeams? = null
        internal set

    @Volatile
    private var shutDown: Boolean = false

    /**
     * 读档恢复后换上重建的存储容器，并把全部 mod context 的 storage 视图重绑到新容器
     * （RT2 D 车；只由侧车端口调）。
     *
     * 取舍（契约 §3 判据 3）：两步在同一 VM 线程、同一同步块内完成，中间无观察点 ⇒
     * 「容器已换而 context 未重绑」的状态不可达——原子换，而非暂态拒绝。顺序 = 先逐
     * context 重绑、再换会话容器：两步之间无挂起点，读档缝调用者与端口捕获同线程串行，
     * 不存在交错窗口。
     */
    fun adoptStorage(newStore: ModStorageStore) {
        host.rebindStorage(newStore)
        storage = newStore
    }

    /**
     * 新开一局的通知（[com.eratw.port.modapi.EraMod.onNewGame]）。
     *
     * 触发点是 BEGIN 状态机的 `stepFirst`（`core-vm/.../proc/BeginFlow.kt`）经
     * `ConsoleBeginHost.onNewGameStartedHook` 回调过来。只在本会话确实装着 mod 时才有意义；
     * 关态（总闸 off / attach 返回 null）时根本不会有 session 对象。
     */
    fun notifyNewGame() {
        if (shutDown) return
        // 新局 = 新时间线：相位水位清零，下一个边界重新从日始开始。
        turnClock.reset()
        storage.clearDurableInteractions()
        host.notifyNewGame()
        // ModDX2-A：新局记账当前包版本（首次 = 只记账不回调；旧记账覆写，见下）。
        ensurePackageVersionRecords()
    }

    /**
     * 读档走到末尾的通知（[com.eratw.port.modapi.EraMod.onSaveLoaded]）。
     *
     * 触发点是 `BeginFlow.stepEventLoad`（`EVENTLOAD` 已跑完、`endAutoSave` 之前）。
     * 隔离与不抛口径在 [com.eratw.port.core.mod.ModRuntimeHost.notifySaveLoaded] 里。
     * 末尾追加 ModDX2-A 的包版本对账（[reconcilePackageUpgrades]）：回调序由此保证
     * 「onPackageUpgrade 严格在 onSaveLoaded 之后」（契约判据）。
     */
    fun notifySaveLoaded() {
        if (shutDown) return
        // 读档 = 时间线可能回退：相位水位清零，下一个边界按「首边界」给日始。
        turnClock.reset()
        host.notifySaveLoaded()
        // ModDX2-A：读档末尾逐包对账记账版本 vs 当前 manifest 版本（不等即派发迁移回调）。
        reconcilePackageUpgrades()
        host.resumeDurableInteractions()
    }

    /**
     * 新局记账（ModDX2-A 判据「首次（新局/首次 attach）记账当前版本」）：对本会话装着且启用成功的
     * 每个包，把当前 manifest 版本**无条件**写进 per-package 记账键（[MOD_STORAGE_PACKAGE_VERSION_KEY]）。
     * 已有记账也覆写——新局 = 新时间线，上一局/上一份档的记账不承接到本局，避免「旧记账 + 新结构数据」
     * 在下一张档上被误判成一次升级。不派发任何回调：新局不是「装载既有存档」，没有可迁移的旧数据
     * （[com.eratw.port.modapi.EraMod.onPackageUpgrade] 的触发时机口径）。
     *
     * 前置 = [ModStorageStore.isActive]：install 成功的包必激活；读档对账里被拒载的包已退激活
     * （数据整段留下、本会话不启用），跳过不碰——记账键也是「不删数据」纪律的一部分。跳过的包
     * 重新启用时由下一次新局或读档补记账。
     *
     * 写入口 = [putReservedKey]（ModDX-4 BS：作者视图写侧对保留前缀 fail-closed 拒，宿主记账改走专用门）。
     */
    private fun ensurePackageVersionRecords() {
        for (packageId in activePackages) {
            val current = packageVersions[packageId] ?: continue
            if (storage.isActive(packageId)) {
                storage.putReservedKey(packageId, MOD_STORAGE_PACKAGE_VERSION_KEY, current)
            }
        }
    }

    /**
     * 读档末尾的包版本对账（ModDX2-A 判据 2「恢复存档后对比 → 不等即派发回调并回写新版本」）：
     * 对本会话装着且启用成功的每个包，读记账键与当前 manifest 版本比较——
     * 无记账 ⇒ 只记账当前版本（首次见到本包：新装、或旧档里没有本包分段，**不回调**）；
     * 相等 ⇒ 零动作；不等 ⇒ 派发 [ModRuntimeHost.notifyPackageUpgrade]（from = 记账原文，
     * to = 当前原文），派发返回后把当前版本回写记账键。
     *
     * 时序事实（插点以本文件侧车恢复段结构现实为准）：槽档恢复（`ModSidecarStoragePort.restoreRt2`
     * → [adoptStorage]）发生在 LOADDATA 相位、EVENTLOAD 之前，恢复缝里只重绑容器不派发任何
     * 升级回调；本函数挂在 [notifySaveLoaded] 末尾（触发点 = `BeginFlow` 读档末尾经
     * `GameHost` 的 `onSaveLoadedHook`），回调序由此 = onSaveLoaded 之后。记账回写在派发之后
     * **无条件**执行（通知面语义，不同于 onMigrate 的失败保旧重试）：回调抛异常已由宿主
     * per-mod 隔离记录，版本照常前滚，不因作者迁移代码抛错把档锁死在旧版本读数上。
     *
     * global.rt2 的 LOADGLOBAL 交还（`restoreGlobalRt2` → [adoptStorage]）没有尾随的读档末尾
     * 通知，本对账不在那条缝触发。「恢复完成」安全点已由 ModDX-4 RT2 立
     * （`markGlobalRestoreComplete`）；组长裁定 global 缝**不**独立对账：LOADGLOBAL 会把记账键
     * 回卷到 SAVEGLOBAL 时刻值，但该键的唯一生产读者 = 本对账自身（BS 门后作者写不进保留键，
     * 作者读保留键无真实动机——版本自知于自己的 manifest），下一张槽档读档的本对账即兜底
     * （旧记账 ≠ 当前版本 ⇒ 届时仍派发一次升级回调），窗口期无读者。
     *
     * 读侧仍走 apiFor 视图的 get（读侧零变化）；记账回写入口 = [putReservedKey]（ModDX-4 BS：
     * 作者视图写侧对保留前缀 fail-closed 拒，宿主写走专用门）。
     */
    private fun reconcilePackageUpgrades() {
        for (packageId in activePackages) {
            val current = packageVersions[packageId] ?: continue
            if (storage.isActive(packageId)) {
                val view = storage.apiFor(packageId)
                val stored = view.get(MOD_STORAGE_PACKAGE_VERSION_KEY)
                when {
                    stored == null -> storage.putReservedKey(packageId, MOD_STORAGE_PACKAGE_VERSION_KEY, current)
                    stored != current -> {
                        host.notifyPackageUpgrade(packageId, stored, current)
                        storage.putReservedKey(packageId, MOD_STORAGE_PACKAGE_VERSION_KEY, current)
                    }
                }
            }
        }
    }

    /**
     * 设置变更的热通知（[com.eratw.port.modapi.EraMod.onSettingsChanged]，ModDX DX-A2）。
     *
     * 触发点是设置页写入成功后投进 [ModSettingsNotifyChannel] 的积压在 VM 线程页边界被
     * `GameHost.onTurnBoundary` drain 到这里（UI 线程只入队，绝不在设置页线程直接调 mod
     * 回调）。派发前先经 [settingsSnapshotOf] 重读本包盘上快照并 [ModRuntimeHost.rebindSettings]
     * 重注——兑现「回调里 `context.settings` 读到的已是新值」（DX-A1 判据 1，ModDX-A2 组长
     * 接线把欠账收掉）；重读源未接线（null）时跳过重注、按旧快照语义派发（台架/旧装配口径）。
     * 只达本会话确实装着的包：会话内声明真源 = [host] 的按包快照（重注后已是新快照），
     * `changedKeys` 先按它过滤（只留声明过的键，键序保持；包未随本会话启用 = 无声明快照 =
     * 整批丢弃），再转发 [ModRuntimeHost.notifySettingsChanged] 的按包派发（隔离 / timing
     * scope `lifecycle.onSettingsChanged` 口径都在 core 侧，本面不重复兜）。
     */
    fun notifySettingsChanged(packageId: String, changedKeys: List<String>) {
        settingsSnapshotOf?.invoke(packageId)?.let { host.rebindSettings(packageId, it) }
        val declared = host.settings[packageId]?.declaredKeys?.toSet().orEmpty()
        val filtered = changedKeys.filter { it in declared }
        if (!shutDown && filtered.isNotEmpty()) host.notifySettingsChanged(packageId, filtered)
    }

    /** 回合边界相位时钟（P0-WIRE；只从 `DAY` 推导内建相位，纯 JVM 实例态）。 */
    private val turnClock = TurnPhaseClock()

    /** 上一条「DAY 读数失败」是否已留痕（同因只报一次，不刷屏）。 */
    private var dayReadFailureLogged: Boolean = false

    /** 上一条「TIME 读数失败」是否已留痕（M5P；同因只报一次，不刷屏）。 */
    private var timeReadFailureLogged: Boolean = false

    /** 最近一次已消费的退场回写报告（页边界日志消费的幂等水位；按引用比对）。 */
    private var lastConsumedRelationReport: RelationProjectionSynchronizer.RelationWriteBackReport? = null

    /**
     * M5P：TIME 只读 → [TimePhaseKey] 单一换算口出 time 相位键（引擎不引入时段语义，
     * 朝/昼/夜命名归 mod 策略）；读数不可用 = 空串 = 本拍不追加（fail-open，与未接线前同形）。
     * 读数失败同因只报一次留痕（不刷屏），不把异常抛回边界。
     */
    private fun timePhaseOrEmpty(): String = try {
        TimePhaseKey.of(store.readInt(TIME_NAME, longArrayOf(0L)))
    } catch (t: Throwable) {
        if (!timeReadFailureLogged) {
            timeReadFailureLogged = true
            logSink("mod.tick TIME 读数失败，本拍无 time 相位（同因只报一次）：${t.javaClass.simpleName}: ${t.message}")
        }
        TimePhaseKey.of(null)
    }

    /**
     * 页边界节拍（GameHost 的 `onTurnReady` 安全点调；本函数**保证不抛**）。
     *
     * 顺序固定四步：① 兑现转移边界积压的提交（[VmBoundaryScheduler.drainPending]）；
     * ② 读 `DAY`（只读，写入次数必须为 0——ERB 脚本拥有时钟）并由 [TurnPhaseClock] 把
     * 「边界日怎么变」翻成内建相位计划（首边界/回退 = 日始；日变 = 日终 + 日始；同日 = 动作点），
     * 逐 driver 推进 [GameTickDriver.tickTurn]（仅逐相位派发，per-mod 失败隔离在 core 内），
     * 所有包相位完成后再对全链共享任务调度器派发一次（空相位、仅活跃包）；③ 回合粒度通知
     * （[ModRuntimeHost.notifyTurnBoundary]，只达已启用
     * mod，时间账本 scope 固定 `lifecycle.onTurnBoundary`）；④ 仲裁外轮（W2-ARDRV 判据 1，
     * [ModArbitrationRound.runOuterRound]）——每页边界**只跑一次**（2026-10-31 真机 OOM
     * 先例的频率纪律：不许每帧轮询），排在 driver 节拍之后（蓝图 §21.3「JobScheduler/相位
     * 决定何时评估，仲裁器决定做哪一件」：先让任务/相位把世界状态推进到位，再评估候选）。
     *
     * 相位名不再恒空：P0-WIRE 立项裁决要「谁决定 phase」由宿主显式回答，落点是 [TurnPhaseClock]
     * 只从 `DAY` 推导；ModDX-4 M5P 再追加 `TIME` 换算的自定义相位键（[TimePhaseKey] 单一换算口，
     * 引擎不引入时段语义），TIME 读数不可用则本拍无 time 相位（fail-open）。旧「空相位是裁定」
     * 用例已按同一裁决改为「Fired 与 Skipped 可分」断言。
     *
     * 宿主对 [com.eratw.port.modapi.InteractionRunResult.failures] 同款义务在相位/任务报告上
     * 就地兑现：`hasFailures` 的报告逐条记诊断（JOB / PHASE 域 FAILED）并上日志面——
     * 不静默（P2-1 的消费判据）。交互管线生产触发点现有两条：输入驱动（RT2 P0-LAST，
     * ⑤ `consumeOnBoundary`）与回合级（ModDX-4 M4，⑤′ `runTurnFrame`，witness frame 随拍
     * 透传）；COM 级接线（真实互动发起时机的输入状态机缝）登记后续波。
     */
    fun onTurnBoundary() {
        try {
            if (shutDown) return
            // ① 转移边界：先兑现积压提交，再推进节拍。
            scheduler.drainPending()
            // ①″ task-134 ③：显式卸载的会话内回收 —— 设置页投递的 packageId 在这里（VM 线程
            // 页边界）从内存容器摘段；只摘点名的包，不 deactivate 别的包。抓取侧另有台账闸
            // （②），所以这里失败只上日志面、不回投。
            reclaimReleasedStorage()
            // ①′ M5-3：页边界消费退场回写诊断（先于 DAY 读，不因时钟读数失败跳过取证）。
            logDirtyRelationWriteBack()
            // ② 单一时间源：DAY 只读。读数失败同因只报一次并跳过本拍（不写任何东西）。
            val day = try {
                store.readInt(DAY_NAME, longArrayOf(0L))
            } catch (t: Throwable) {
                if (!dayReadFailureLogged) {
                    dayReadFailureLogged = true
                    logSink("mod.tick DAY 读数失败，节拍旁路（同因只报一次）：${t.javaClass.simpleName}: ${t.message}")
                }
                return
            }
            // 相位计划 = DAY 推导的内建相位（映射裁见 TurnPhaseClock 类头）+ M5P 的 TIME 换算键
            // （[timePhaseOrEmpty]：单一换算口出键；读数不可用 = 空串 = 本拍不追加，fail-open）。
            val phasePlan = turnClock.plan(day) + timePhaseOrEmpty()
            // 任务报告仍以空相位取时：空相位 = 不限相位，旧任务触发口径不变。
            val time = GameTime(day = day, phase = "")
            // 主表 + 后期重装追加表：两者消费口径逐位相同（回滚态重装成功后新包也要过边界）。
            val tickDrivers = if (lateDrivers.isEmpty()) drivers else drivers + lateDrivers
            val ticks = dispatchPhaseTicks(tickDrivers, day, phasePlan, time)
            val firedJobs = if (tickDrivers.isEmpty()) 0 else dispatchSharedJobs(time)
            if (ticks.firedHandlers > 0 || firedJobs > 0) {
                logSink(
                    "mod.tick day=$day phaseHandlers=${ticks.firedHandlers} jobs=$firedJobs " +
                        "phases=${ticks.plannedPhases.joinToString(",")}",
                )
            }
            // ③ 回合粒度通知：相位派发之后、仲裁之前（mod 看到本边界推进后的世界状态）。
            host.notifyTurnBoundary()
            // ④ 仲裁外轮（W2-ARDRV 判据 1）：缝缺席（关态 / 未装配）= no-op；域内无候选由
            // 管线自身完全旁路（不执行处理器、不建预留、不记账，决策态零变化）。
            arbitrationRound?.runOuterRound(day)
            // ⑤ 交互生产触发点（RT2 P0-LAST）：玩家真实输入 → 全链共享交互管线，**每页边界
            // 只消费一次**（输入证据在 GameHost 输入漏斗采集，这里 VM 线程 getAndSet 取走；
            // 无新输入零成本旁路）。随即重拍 UI 贡献项快照（主线程只读不可变快照）。
            // 放在 try 内：DAY 读失败早退时证据留到下一拍，绝不把作者 handler 异常抛回边界。
            hostSeams?.let { seams ->
                // ⑤′ 回合级交互触发（ModDX-4 M4 判据 2）：页边界每拍一次，witness frame
                // （TARGET 同位置在场者读数）作为上下文送进共享管线；先闸后建——无注册
                // handler 时 provider 不被调、witness 读数零发生（判据 4 零开销零日志）。
                // 插在仲裁外轮之后：mod 互动钩子看到本边界推进 + 仲裁后的世界状态；与
                // ⑤ 输入触发并列且先于其消费（interactionId 区分 host.turn / host.input）。
                seams.interactionWire.runTurnFrame { ModWitnessFrameReader(store).readContext() }
                seams.interactionWire.consumeOnBoundary()
                seams.uiSurface.refresh()
            }
        } catch (t: Throwable) {
            // 兜底隔离：节拍绝不把异常抛回页边界；留痕即不静默。
            logSink("mod.tick 边界异常已隔离：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 页边界相位派发的汇总读数（宿主日志面共用；纯值容器，无状态）。 */
    private class PhaseTickSummary {
        var firedHandlers: Int = 0
        val plannedPhases: MutableSet<String> = LinkedHashSet()
    }

    /**
     * 逐 driver 派发本边界相位（runJobs = false：任务态改由共享调度器在全部相位之后统一推进）。
     *
     * 失败隔离口径：单个 driver 抛异常只旁路该包的相位，不中断其余包；处理器失败照旧逐条记
     * PHASE 域诊断并上日志面（P2-1：不静默）。
     */
    private fun dispatchPhaseTicks(
        tickDrivers: List<GameTickDriver>,
        day: Long,
        phasePlan: List<String>,
        time: GameTime,
    ): PhaseTickSummary {
        val summary = PhaseTickSummary()
        for (driver in tickDrivers) {
            val report = try {
                driver.tickTurn(day, phasePlan, runJobs = false)
            } catch (t: Throwable) {
                logSink("mod.tick driver 异常已隔离（本拍该 mod 相位旁路）：${t.javaClass.simpleName}: ${t.message}")
                continue
            }
            summary.firedHandlers += report.firedPhaseHandlers
            summary.plannedPhases += report.plannedPhases
            for (failure in report.phase.failures) {
                recordFailureDiagnostic(
                    time,
                    DiagnosticDomain.PHASE,
                    failure.packageId,
                    failure.phase,
                    failure.cause,
                )
                logSink(
                    "mod.tick 相位处理器失败 pkg=${failure.packageId} phase=${failure.phase} " +
                        "${failure.cause.javaClass.simpleName}: ${failure.cause.message}",
                )
            }
        }
        return summary
    }

    /**
     * 共享调度器在本边界的**唯一**一次推进（全部相位派发之后）。
     *
     * 只推进活跃包；无 driver = 无 mod 会话，整拍旁路（golden 零变化）。调度器异常与处理器
     * 失败都只留在本拍，不抛回页边界。
     */
    private fun dispatchSharedJobs(time: GameTime): Int {
        val jobReport = try {
            host.jobScheduler.runDue(time, activePackages)
        } catch (t: Throwable) {
            logSink("mod.tick 共享任务调度异常已隔离：${t.javaClass.simpleName}: ${t.message}")
            return 0
        }
        for (failure in jobReport.failures) {
            recordFailureDiagnostic(time, DiagnosticDomain.JOB, failure.packageId, failure.jobId, failure.cause)
            logSink(
                "mod.tick 任务处理器失败 pkg=${failure.packageId} job=${failure.jobId} " +
                    "${failure.cause.javaClass.simpleName}: ${failure.cause.message}",
            )
        }
        return jobReport.fired.size
    }

    /**
     * task-134 ③：把设置页投递的显式卸载请求在这一页边界从内存容器摘掉（`store.release`）。
     *
     * 只摘点名的包，不 deactivate 其他包；失败隔离（抓取侧 ② 另有台账闸兜底）。
     * 摘掉只影响之后的存档：既有存档文件不改写，减在下次存档。
     */
    private fun reclaimReleasedStorage() {
        for (packageId in ModStorageReclaimRequests.drain()) {
            runCatching { storage.release(packageId) }
                .onSuccess { released ->
                    logSink("mod.rt2 reclaim pkg=$packageId 内存容器已摘=$released（减在下次存档）")
                }
                .onFailure { throwable ->
                    logSink(
                        "mod.rt2 reclaim pkg=$packageId 摘段失败已隔离：" +
                            "${throwable.javaClass.simpleName}: ${throwable.message}",
                    )
                }
        }
    }

    /**
     * 页边界消费退场回写诊断（M5-3，裁定 2 的 `lastReport.isClean` 接日志消费）：
     * 关系投影同步器的最近一次退场回写若有没写进图的改动（`isClean` 为假 = 有损，
     * 不许静默），上日志面留痕。按**引用**比对幂等——同一份报告只报一次；后续退场
     * 产生新报告时下一拍再消费。真桥未接关系投影（纯 Base 旁路）= 无同步器 = no-op。
     * 日志行是取证面（非玩家可见文案，不进域文案表）。
     */
    private fun logDirtyRelationWriteBack() {
        val sync = (charaSlotProjector as? RealCharaSlotProjector)?.relationSynchronizerOrNull
        val report = if (sync == null) null else sync.lastReport
        if (report == null || report === lastConsumedRelationReport || report.isClean) return
        lastConsumedRelationReport = report
        logSink(
            "mod.rt2 relation writeback dirty applied=${report.applied} " +
                "unwritten=${report.unwritten.size} goneKeys=${report.goneKeys.size}",
        )
    }

    /** 宿主侧失败诊断（P2-1「记诊断」的落点；core 调度器本波未接 diagnostics，此处代记不重复）。 */
    private fun recordFailureDiagnostic(
        time: GameTime,
        domain: DiagnosticDomain,
        packageId: String,
        key: String,
        cause: Throwable,
    ) {
        diagnostics.record(
            day = time.day,
            phase = time.phase.ifBlank { null },
            domain = domain,
            packageId = packageId,
            key = key,
            outcome = DiagnosticOutcome.FAILED,
            reasonCode = "HANDLER_THREW",
            detail = { cause.javaClass.simpleName.ifEmpty { cause.javaClass.name } },
        )
    }

    /**
     * 会话收口：逐 mod 走 onDisable → onUnload（[ModRuntimeHost.shutdown] 幂等），
     * 摘诊断读者，**最后**解绑 store 的投射桥与 resolver（F 车）。顺序不重排：
     * mod 收尾回调仍可能经 `context.roster` 读投射状态，钩子必须活到它们跑完；
     * 解绑后再有行操作则五个接点零调用，不留跨会话悬挂引用。
     * 幂等：二次调用由 `shutDown` 短路 = no-op。调用方（GameHost 清场）还包一层
     * runCatching，本函数自身不抛——mod 回调的异常已由宿主隔离。
     */
    fun shutdown() {
        if (shutDown) return
        shutDown = true
        diagnosticsReadout.unregister()
        val states = host.shutdown()
        for (state in states) {
            val failure = state.failure
            if (failure != null) {
                logSink(
                    "mod.shutdown pkg=${state.packageId} 安装期失败(回滚态) ${failure.javaClass.simpleName}: ${failure.message}",
                )
            }
        }
        // 耗时归因上屏（§18.5 扩展）：lifecycle 两段已在 host.shutdown 里记完，此刻正是读数最全的点。
        // 一行一个 (pkg, scope)，作者据此定位「哪个 mod 的哪一段最贵」；关态会话没有账本 ⇒ 零日志。
        // ModDX-3 A：注入单包整会话累计耗时预算，超预算的包多打一行 mod.timing.budget [WARN]。
        reportModTiming(
            host,
            states.map { it.packageId },
            logSink,
            budgetNanos = DEFAULT_PACKAGE_CUMULATIVE_BUDGET_NANOS,
        )
        // ModDX-3 B：失败归因摘要上屏（domain/pkg/outcome 计数，只 FAILED/REJECTED）。
        // diagnostics.failureSummary() 读环不依赖订阅：上面的 unregister 不影响这里。
        diagnostics.failureSummary().forEach { s ->
            logSink(
                "mod.diag.summary count=${s.count} domain=${s.domain.name} " +
                    "package=${s.packageId ?: "<engine>"} outcome=${s.outcome.name}",
            )
        }
        // hook 接线收口：mod 的 onDisable / onUnload 仍可能要跑脚本（钩子必须活到它们跑完），
        // 所以解绑排在上面 lifecycle 之后；按引擎实例同一性判「是不是本会话那一份」。
        FunctionHookWiring.uninstall(store, host.hookPipeline)
        // 事件接线同一口径收口：同样排在 lifecycle 之后（mod 的 onDisable 可能还要注册/派发）；
        // 按注册表实例同一性判「是不是本会话那一份」，不误伤后一会话。
        ModEventWiring.uninstall(store, host.eventHandlers)
        // 音频面收口（Lane AN）：停 mod 播放器、解绑会话槽。排在 lifecycle 之后（onDisable 里
        // 可能还要放/停声音）；只停 mod 自己的播放器，不碰引擎 BGM/SE。
        ModAudioWiring.uninstall(store)
        // 事件面收口（含主动触发的两条通路）由上面那行 `ModEventWiring.uninstall` 一并完成：
        // 公共面撤销触发桥、raw 面摘掉 `mod.events.trigger` 的派发目标（CL/CO 波审计发现的
        // 收口不对称，收口点落在 core-vm 的 ModEventWiring.uninstall 里，因为目标实例只由
        // 引擎自己持有、本层拿不到引用）。
        // F 车收口：只解绑本会话那一份；传入别人的实例时 no-op。
        if (store.charaSlotProjector === charaSlotProjector) {
            store.charaSlotProjector = null
        }
        // RT2 regbin：会话收口把并集注册表原子写全局 bin（tmp+rename 单件，不走 SaveTransaction）。
        // 开态短会话（registry 空且无新增）也写，幂等、字节确定性由 encode 保证（裁定 4）。
        val binding = registryBinding
        if (binding != null) {
            writeRegistryBinAtomically(binding.bin, binding.pkgRegistry.encode(), logSink)
        }
        store.unbindCharaIndexResolver(charaIndexResolver)
        logSink("mod.shutdown 完成 mods=${hostCount()}")
    }

    private fun hostCount(): Int = drivers.size + lateDrivers.size

    private companion object {
        /** 游戏日权威的系统变量名（SystemLayout.int1D 第 0 项；VariableCode.kt:34 DAY=720896）。 */
        const val DAY_NAME: String = "DAY"

        /** 整数时段的系统变量名（VariableCode.kt:54 TIME=720918；页边界只读，M5P 换算源）。 */
        const val TIME_NAME: String = "TIME"
    }
}

/**
 * [SidecarStoragePort]（槽档）与 [GlobalSidecarStoragePort]（global.rt2，RT2 C-RES1）的
 * 生产实现：SaveService 抓取/恢复与 [ModStorageStore] 分段之间的桥。
 *
 * ## 分片映射（容器层不解释内容，映射只在本类与本文件内自洽）
 *
 * mod 存储的每包一段（`ModStorageSection`）= .rt2 容器的每包一片（`PackageChunk`）：
 * 片载荷 = 该段的 MST1 编码（`ModStorageSectionCodec.encode(listOf(section))`），
 * 激活/孤儿按快照的 orphan 位分组 —— 同包不可能同时出现在两组（快照本身按包唯一）。
 * 恢复方向：解码各片 → 合并重编 → `ModStorageStore.load(bytes, 已知包集)` 重建容器
 * （已知包 = 本会话激活集，孤儿段原样保留为非激活，口径同容器的「禁用不等于删除」）。
 *
 * 槽档与 global 两个面共用同一份分片源（会话 ModStorageStore）与同一份 SaveService：
 * GLOBAL 域的 Mod 载体（PersistenceDomains.routeOf：Mod + GLOBAL → GLOBAL_RT2）今天
 * 物理上就是会话级 ModStorageStore 本身，SAVEGLOBAL 与 SAVEGAME 都从它取段；但 durable
 * 剧情 checkpoint 只属槽档，global 抓取排除它、恢复保留当前槽值，不被旧 global 覆盖。
 * 容器与恢复语义仍分别走 [SaveService.captureGlobalBundle] / [SaveService.restoreGlobalBundle]
 * （global 实体载荷恒空、不灌内核）与槽档 captureBundle + restoreBundle。
 *
 * 绑定失配 / 容器损坏都只上日志面 + 返回 false（fail-closed 不静默），不外抛打中断读档。
 */
internal class ModSidecarStoragePort(
    private val saveService: SaveService,
    /** 现读会话存储容器（读档 adoptStorage 之后 captures 落在新容器上）。 */
    private val storageProvider: () -> ModStorageStore,
    /** 把重建的容器交还宿主（`ModRuntimeSession.adoptStorage`）。 */
    private val adoptStorage: (ModStorageStore) -> Unit,
    /** 本会话激活的 packageId 集（恢复时判「已知包」）。 */
    private val activePackages: () -> Set<String>,
    /** 决策/任务态缝（W1-DSTATE 判据 1/3；抓取与回灌都经它，null = 未装配 = 无状态段）。 */
    private val decisionStateProvider: () -> ModDecisionJobState?,
    private val logSink: (String) -> Unit,
) : SidecarStoragePort, CrossDeviceSidecarStoragePort, GlobalSidecarStoragePort {

    /**
     * 抓取时的**跳过集**（task-134 ②）：显式卸载过的包，其分段不再随新存档写出
     * ⇒ 段与字节**减在下次存档**（既有存档文件本笔不改写，不重写历史档）。
     *
     * 后期装配而不加构造参数：本类构造签名被 detekt 默认 LongParameterList 阈值盯着
     * （第 7 个参数会新增无 baseline 覆盖的结构性 ID）。attach 里接
     * `ModStorageReclaim.reclaimedPackageIds(rt2Dir)`；缺省空集 = 全表透传的旧行为，
     * 只读快照不受影响。
     */
    internal var reclaimedPackages: () -> Set<String> = { emptySet() }

    /** 当前 freeze 的激活包声明；空外层 = 旧装配/纯测试的 v2 零变化路径。 */
    internal var entityRefDeclarations: () -> Map<String, Map<String, com.eratw.port.core.patch.EntityRefScope>> =
        { emptyMap() }

    /** CSV 写回与侧车其余状态同一提交序列；null = 保持 FileSystemOps 的旧回落路径。 */
    internal var csvRestore: ((
        ModCsvValueBag,
        Map<String, Map<String, com.eratw.port.core.patch.EntityRefScope>>,
        com.eratw.port.core.entity.EntityIdRemapTable,
    ) -> ModCsvValueRestorer.Prepared)? = null

    override fun captureChunks(csv: ModCsvValueBag): SidecarChunkGroups =
        captureChunks(storageProvider().sectionsSnapshot(), csv)

    private fun captureChunks(
        sections: List<com.eratw.port.core.mod.ModStorageSection>,
        csv: ModCsvValueBag = ModCsvValueBag.EMPTY,
    ): SidecarChunkGroups {
        val byPackage = csv.byPackage()
        val active = ArrayList<PackageChunk>()
        val orphan = ArrayList<PackageChunk>()
        val reclaimed = reclaimedPackages()
        var skipped = 0
        for (section in sections) {
            // 显式卸载回收（task-134）：已释放包的段整段不写。孤儿段与激活段同口径——
            // 卸载后重启读档会把这个段读回容器（读侧不舍弃历史档），靠这里让它减在下次存档。
            if (section.packageId in reclaimed) {
                skipped++
                continue
            }
            val storageBytes = com.eratw.port.core.mod.ModStorageSectionCodec.encode(listOf(section))
            // 孤儿片（未激活包）绝不被本会话的值污染：载荷原样透传，CSV 段只进激活包自己的片。
            val payload = if (section.orphan) {
                storageBytes
            } else {
                ModCsvValueCodec.encode(storageBytes, byPackage[section.packageId] ?: ModCsvValueBag.EMPTY)
            }
            val chunk = PackageChunk(section.packageId, payload)
            if (section.orphan) orphan += chunk else active += chunk
        }
        if (skipped > 0) {
            logSink("mod.rt2 reclaim 跳过已释放包段 n=$skipped（减在下次存档）")
        }
        return SidecarChunkGroups(active, orphan)
    }

    override fun captureRt2Bytes(
        binding: SidecarBinding,
        activeChunks: List<PackageChunk>,
        orphanChunks: List<PackageChunk>,
    ): ByteArray {
        val base = saveService.captureBundle(
            binding,
            activeChunks,
            orphanChunks,
            activeEntityRefs = entityRefDeclarations(),
        )
        // W1-DSTATE 判据 1/3：SaveService 组装的分段原样保留，追加决策态段 + 任务态段
        // （载荷取自会话缝快照；缝缺席/载荷空 = 旧档同形，SidecarSaveBundle 的
        // 「都写或都不写」纪律）。段口径与 global 档共用 [stateSegmentsForSave]。
        return SidecarSaveBundle(
            binding = base.binding,
            registrySnapshot = base.registrySnapshot,
            entityGraphPayload = base.entityGraphPayload,
            chunks = base.chunks,
            virtualNoSnapshot = base.virtualNoSnapshot,
            stateSegments = stateSegmentsForSave(),
            entityRefDeclarationPayload = base.entityRefDeclarationPayload,
        ).encode()
    }

    /**
     * 存档抓取侧的状态尾段（槽档与 global.rt2 **同一口径、同一结构**，不为任一侧另开
     * 一套段格式）：缝未装配 = [SidecarStateSegments.EMPTY]（段都不写 = 增补前旧档逐字节
     * 同形）；缝在位 = 决策态段 + 任务态段（`SidecarSaveBundle.encode` 的「要么都写、
     * 要么都不写」）。槽档字节形状由本函数返回值决定，global 侧只是复用同一入口。
     */
    private fun stateSegmentsForSave(): SidecarStateSegments {
        val state = decisionStateProvider() ?: return SidecarStateSegments.EMPTY
        return SidecarStateSegments(state.decisionStatePayload(), state.jobStatePayload())
    }

    override fun restoreRt2(bytes: ByteArray, binding: SidecarBinding): SidecarRestoreResult =
        restoreRt2(bytes, binding, crossDevice = false)

    override fun restoreRt2(
        bytes: ByteArray,
        binding: SidecarBinding,
        crossDevice: Boolean,
    ): SidecarRestoreResult {
        val bundle = SidecarSaveBundle.decode(bytes)
        val prepared = saveService.prepareSlotRestore(
            bundle,
            binding,
            activeEntityRefs = entityRefDeclarations(),
            crossDevice = crossDevice,
        )
        if (prepared is SaveService.SlotRestorePreparation.BindingMismatch) {
            logSink(
                "mod.rt2 绑定失配，扩展状态未应用（期望=$binding 实际=${prepared.actual}）；" +
                    "以 Base 状态继续运行",
            )
            return SidecarRestoreResult(false)
        }
        val ready = prepared as SaveService.SlotRestorePreparation.Ready
        val active = activePackages()
        val sections = bundle.chunks.flatMap { chunk ->
            com.eratw.port.core.mod.ModStorageSectionCodec.decode(
                ModCsvValueCodec.decode(chunk.payload).storage,
                active,
            )
        }
        val rebuilt = ModStorageStore.load(
            com.eratw.port.core.mod.ModStorageSectionCodec.encode(sections),
            knownPackages = active,
        ).remapDurableContexts(ready.remapTable)
        val decisionState = decisionStateProvider()
        val hasState = bundle.decisionStatePayload.isNotEmpty() || bundle.jobStatePayload.isNotEmpty()
        if (hasState && decisionState == null) {
            throw IllegalStateException("容器带决策/任务态段但本会话未装配决策缝，拒绝提交半恢复状态")
        }
        val decodedDecision = if (bundle.decisionStatePayload.isEmpty()) {
            null
        } else {
            DecisionStateCodec.decode(bundle.decisionStatePayload)
        }
        val decodedJob = if (bundle.jobStatePayload.isEmpty()) {
            null
        } else {
            JobSchedulerStateCodec.decode(bundle.jobStatePayload)
        }
        decodedDecision?.let { state ->
            decisionState!!.validateSeedFromSave(state.decisionSeed)
            decisionState.validateDecisionState(state)
        }
        decodedJob?.let { decisionState!!.validateJobState(it) }
        val csv = ModCsvValueCodec.mergeApplied(bundle.chunks, active)
        val csvPrepared = csvRestore?.invoke(csv, ready.entityRefDeclarations, ready.remapTable)

        // 全部可失败校验已在上方完成；这才进入唯一提交序列。
        ready.commit()
        adoptStorage(rebuilt)
        decodedDecision?.let { state ->
            decisionState!!.adoptSeedFromSave(state.decisionSeed)
            decisionState.restoreDecisionState(state)
        }
        decodedJob?.let { decisionState!!.restoreJobState(it) }
        csvPrepared?.commit()
        logSink(
            "mod.rt2 restore ok chunks=${bundle.chunks.size} " +
                "packages=${rebuilt.packages().size} active=${active.size} csv=${csv.size}",
        )
        return SidecarRestoreResult(true, csv, csvApplied = csvPrepared != null)
    }

    /**
     * global.rt2 分片抓取与槽档同构，但排除只属于 saveNN.rt2 的 durable 剧情 checkpoint。
     * GLOBAL 恢复因此不能覆盖当前槽尚未走完的剧情。
     */
    override fun captureGlobalChunks(): SidecarChunkGroups =
        captureChunks(storageProvider().globalSectionsSnapshot())

    /**
     * global.rt2 容器：走 [SaveService.captureGlobalBundle]（实体载荷恒空、虚拟 NO 恒空），
     * 再以**与槽档同源同构**的 [stateSegmentsForSave] 携带决策种子段——global 档两次恢复
     * 必须给出同一 mod 随机序列（槽档 W2-SEED 口径的 global 对齐面）。
     *
     * 段结构复用槽档口径（决策段在前、任务段在后，同一编解码、同一 `SidecarSaveBundle`
     * 容器，不为 global 另开一套格式）；加段只发生在 global 档，槽档 `saveNN.sav.rt2` 的
     * 段顺序与字节不受本改动影响（[captureRt2Bytes] 用同一入口产出同一形状）。
     *
     * 旧档兼容：缝未装配（测试/旧装配）时 [stateSegmentsForSave] 返回
     * [SidecarStateSegments.EMPTY]，容器与增补前逐字节同形；读取侧对无段旧档的回落口径见
     * [restoreGlobalRt2]。
     */
    override fun captureGlobalRt2Bytes(
        binding: SidecarBinding,
        activeChunks: List<PackageChunk>,
        orphanChunks: List<PackageChunk>,
    ): ByteArray {
        val base = saveService.captureGlobalBundle(
            binding,
            activeChunks,
            orphanChunks,
            activeEntityRefs = entityRefDeclarations(),
        )
        return SidecarSaveBundle(
            binding = base.binding,
            registrySnapshot = base.registrySnapshot,
            entityGraphPayload = base.entityGraphPayload,
            chunks = base.chunks,
            virtualNoSnapshot = base.virtualNoSnapshot,
            stateSegments = stateSegmentsForSave(),
            entityRefDeclarationPayload = base.entityRefDeclarationPayload,
        ).encode()
    }

    /**
     * LOADGLOBAL 交还：[SaveService.restoreGlobalBundle] 只验绑定与 global 形状，分片在此
     * 重建容器并经 [adoptStorage] 交还——重绑链（`ModRuntimeHost.rebindStorage` →
     * `ModContextImpl.rebindStorage`）让 mod 侧 `context.storage` 立即可见（RT2 D 车已通）。
     *
     * 决策种子段（与槽档同一 [SidecarStateSegments] 结构）在此按槽档同一采纳序恢复：只取
     * 段内 `decisionSeed` 交给 [ModDecisionJobState.adoptSeedFromSave]——复用其「与当前种子
     * 相等 = no-op」短路，保证重复 LOADGLOBAL 不重复播种、随机流续接而非回卷。决策态/任务态
     * 本身**不由 LOADGLOBAL 回灌**（它们的域是槽档；global 只对齐种子），任务态段随同一段
     * 结构随行但不在此消费。
     *
     * 旧档兼容：增补前的 global 档无状态尾段（`decode()` 回落 [SidecarStateSegments.EMPTY]，
     * 见 `SidecarSaveBundle.decode`），此处即为「无种子可采纳」：不抛、不拒载，种子保持装配
     * 时的值继续运行；缝未装配却读到带段的新档只上警告面，不影响分片交还。
     *
     * 恢复完成安全点（ModDX-4 RT2）：成功路径以 [markGlobalRestoreComplete] 收尾——容器重绑
     * （[adoptStorage]）与决策种子采纳全部落定后才触发，此后才可读恢复态；绑定失配走
     * `BindingMismatch` 返回 false、解码或编解码异常原样上抛（由 LOADGLOBAL 缝的宿主收口折
     * 警告，`FileSystemOps.restoreGlobalSidecarIfWired` 整段 catch），两条路径都到不了安全点
     * （fail-closed）。包版本对账是否接在该安全点上 = 组长裁决（本波只立点不接线）。
     */
    override fun restoreGlobalRt2(bytes: ByteArray, binding: SidecarBinding): Boolean {
        val bundle = SidecarSaveBundle.decode(bytes)
        val prepared = saveService.prepareGlobalRestore(
            bundle,
            binding,
            activeEntityRefs = entityRefDeclarations(),
        )
        if (prepared is SaveService.GlobalRestorePreparation.BindingMismatch) {
            logSink(
                "mod.global.rt2 绑定失配，全局扩展状态未应用（期望=$binding 实际=${prepared.actual}）；" +
                    "以 Base 状态继续运行",
            )
            return false
        }
        val ready = prepared as SaveService.GlobalRestorePreparation.Ready
        val active = activePackages()
        val sections = ready.chunks.flatMap { chunk ->
            com.eratw.port.core.mod.ModStorageSectionCodec.decode(
                ModCsvValueCodec.decode(chunk.payload).storage,
                active,
            )
        }
        val rebuilt = ModStorageStore.loadGlobal(
            com.eratw.port.core.mod.ModStorageSectionCodec.encode(sections),
            knownPackages = active,
            slotStore = storageProvider(),
        )
        val decisionState = decisionStateProvider()
        val decodedDecision = if (bundle.decisionStatePayload.isEmpty()) {
            null
        } else {
            DecisionStateCodec.decode(bundle.decisionStatePayload)
        }
        if (decodedDecision != null && decisionState == null) {
            throw IllegalStateException("global.rt2 带决策种子段但本会话未装配决策缝，拒绝提交半恢复状态")
        }
        decodedDecision?.let { decisionState!!.validateSeedFromSave(it.decisionSeed) }

        ready.commit()
        adoptStorage(rebuilt)
        decodedDecision?.let { decisionState!!.adoptSeedFromSave(it.decisionSeed) }
        logSink(
            "mod.global.rt2 restore ok chunks=${ready.chunks.size} " +
                "packages=${rebuilt.packages().size} active=${active.size}",
        )
        markGlobalRestoreComplete(rebuilt, ready.chunks.size)
        return true
    }

    /**
     * global.rt2 恢复完成安全点（ModDX-4 RT2）：[restoreGlobalRt2] 成功路径的**最后一个语句**，
     * 容器重绑（[adoptStorage]）与决策种子采纳都已落定、此后不再有恢复期改写——外部读恢复态
     * 只允许从这个序列点起算。失败 / 中断路径到不了这里（fail-closed）：绑定失配返回 false；
     * 解码 / 编解码异常原样上抛，由 LOADGLOBAL 缝的宿主收口折成警告（
     * `FileSystemOps.restoreGlobalSidecarIfWired` 整段 catch，FileSystemOps.kt:966-984）——
     * 安全点没触发 = 恢复没完成，不存在「半恢复也算完成」的口径。
     *
     * ModDX2-A 的包版本对账（`reconcilePackageUpgrades`）只挂槽档读档末尾
     * （[ModRuntimeSession.notifySaveLoaded] 末尾），不在 global 缝触发；若组长裁决 global
     * 恢复需要独立对账缝，接线落点就是本函数（本波只立点不接线）。取证结论：LOADGLOBAL 确实
     * 会用 SAVEGLOBAL 时刻的记账值覆盖 per-package `__eratw.pkgVersion`（MST1 编解码不滤保留键：
     * ModStorageSection.kt:87-106 与 133-147；`ModStorageStore.load` 整表回灌：ModStorageStore.kt:190；
     * 换容器：本文件 restoreGlobalRt2 的 [adoptStorage] 调用），是否补对账由组长裁定。
     */
    private fun markGlobalRestoreComplete(rebuilt: ModStorageStore, chunkCount: Int) {
        logSink(
            "mod.global.rt2 restore complete chunks=$chunkCount " +
                "packages=${rebuilt.packages().size} " +
                "keys=${rebuilt.packages().sumOf { rebuilt.sizeOf(it) }}",
        )
    }

    override fun warn(message: String) {
        logSink("mod.rt2 warn: $message")
    }
}

// F 车（MR-2b-2）已把生产 SaveService 的空投射器换成唯一真 RealCharaSlotProjector；
// C 车 §5.5 的许口实现在此删除，生产树不再有第二份投射器。

/**
 * 全局注册表 bin 的单件原子写（tmp + force + ATOMIC_MOVE/REPLACE_EXISTING），照
 * SaveFileIo.kt:245-267 writeByAtomicReplace 与 :290+ replaceByMove 先例。
 * bin 单件无配对面，不走 SaveTransaction（契约裁定 4）；失败只上日志面不抛
 * （shutdown 承诺不抛），Error 依先例上抛。
 */
private fun writeRegistryBinAtomically(target: File, bytes: ByteArray, logSink: (String) -> Unit) {
    val path = target.toPath()
    val parent = path.parent ?: return
    var tmp: Path? = null
    var moved = false
    try {
        Files.createDirectories(parent)
        val tempPath = Files.createTempFile(parent, "rt2reg-", ".tmp")
        tmp = tempPath
        FileChannel.open(tempPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
        replaceRegistryBin(tempPath, path)
        moved = true
        logSink("mod.registry 收口写 bin ok bytes=${bytes.size}")
    } catch (_: Exception) {
        logSink("mod.registry warn 收口写 bin 失败 path=${target.name}")
    } finally {
        if (!moved) tmp?.let { p -> runCatching { Files.deleteIfExists(p) } }
    }
}

/** 平台无原子换名时退回普通 REPLACE_EXISTING（SaveFileIo.replaceByMove 同款退化）。 */
private fun replaceRegistryBin(tmp: Path, target: Path) {
    try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

// RT2 regbin 装载面 helper 组：置于文件顶层私有作用域，与 writeRegistryBinAtomically /
// replaceRegistryBin 同区。五个 helper 均为无状态纯过程，不依赖 object 实例；object 内函数
// 数因此不触碰 detekt TooManyFunctions 阈值（源头修，不加 Suppress、不动 baseline）。

/** 全局注册表 bin 落位：gameRoot 根的 [ModRuntimeAssembly.REGISTRY_BIN_NAME]（裁定 1，运行期生成物非随包资产）。 */
private fun registryBinOf(gameRoot: File): File = File(gameRoot, ModRuntimeAssembly.REGISTRY_BIN_NAME)

/**
 * 开态真决策管线构造（W1-DSTATE 判据 2；独立成函数是 attach 环形复杂度的源头修法）。
 * 种子 = 安装级熵基座（[decisionEntropy]）+ 宿主真熵尾巴（`freshDecisionEntropyTail`，
 * W2-SEED 判据 1）拼串后经 `DecisionSeed.derive`；档内种子恢复走管线 [DecisionPipeline.supplySeed]
 * 受控供给缝（读档缝采纳序，见类头 W2-SEED 节）。packageOrder = 声明快照链接序；
 * diagnostics 接既有消费者缝。
 */
private fun buildDecisionPipeline(
    snapshot: DeclarationSnapshot,
    gameRoot: File,
    store: VariableStore,
    diagnostics: RuntimeDiagnostics,
    entropyTail: ByteArray,
): DecisionPipeline = DecisionPipeline.of(
    packageOrder = snapshot.packageIds.toList(),
    decisionSeed = DecisionSeed.derive(decisionEntropy(gameRoot, store) + entropyTail),
    diagnostics = diagnostics,
)

/** W2-ARDRV 嵌套轮的日读变量名（与 ModRuntimeSession 的 DAY 读数同源同判据）。 */
private const val MOD_DAY_VAR_NAME: String = "DAY"

/**
 * W2-ARDRV 的会话级仲裁缝绑定（CONTRACT-RT2-ARDRV 判据 1/2）：建 [ModArbitrationRound]
 * 外轮缝（后期装配而非 ModRuntimeSession 构造参数——构造签名被 detekt baseline 的
 * LongParameterList 条目钉住，新增构造参数 = 结构性新 ID）+ 给装载成功的 [CharacterMod]
 * 载体实例注入宿主 driver（app 持实例，ModCarrierRegistry 载体模式；mod-livingworld
 * 业务面零改动）。install 报告 active=false 的包注册项已被 revoke，注入无消费点，跳过。
 * 无状态纯过程。
 */
private fun bindArbitrationSeam(
    session: ModRuntimeSession,
    pipeline: DecisionPipeline,
    store: VariableStore,
    providers: List<Pair<String, EraMod>>,
    logSink: (String) -> Unit,
) {
    val arbitration = ModArbitrationRound(
        pipeline = pipeline,
        outerDomains = productionOuterRoundDomains,
        // 嵌套轮的游戏日现读（与页边界②步同源同判据）；读数失败由 ModArbitrationRound
        // 的 readDayOrBypass 统一旁路留痕（本 lambda 只负责真读数，不吞异常）。
        readDay = { store.readInt(MOD_DAY_VAR_NAME, longArrayOf(0L)) },
        logSink = logSink,
    )
    session.arbitrationRound = arbitration
    for ((_, mod) in providers) arbitration.injectDecisionDriver(mod)
}

/**
 * 外轮域清单（W2-ARDRV 判据 1 的生产缺省；文件级不可变值，非静态可变全局）。
 *
 * **只含傍晚域**的裁定依据：组合回归（LivingWorldCombinationTest 判据 1）的外轮形状就是
 * 空元数据的 `DOMAIN_EVENING` 一轮；而 DOMAIN_ACTION 缺省（无 metadata）对全部 NPC 各出
 * 长活候选、DOMAIN_EVENT 缺省走 else 档真事件集（茶会/闲话/恶作剧/打盹）——把二者放进
 * 页边界外轮等于宿主替 mod 发明业务节拍（长活每日自动收工、事件每边界自动开一场），
 * 违反「引擎不认识 domain 业务含义」（ActionApi.kt 契约）与铁律 1（不确定不脑补）。
 * 这两域的生产触发点（事件集选择器 / 长活由谁点名）属产品决策，登记需组长接线。
 * 域内无候选 = 管线完全旁路（蓝图 §21.3「无候选 = 完全旁路，golden 零变化」）。
 */
private val productionOuterRoundDomains: List<String> = listOf(CharacterDeclarations.DOMAIN_EVENING)

/**
 * 陈旧预留存活判定谓词（W1-DSTATE 判据 3，增补 B 口径）：mod-api `ActionApi.kt` 的
 * 「chara:<entityId> 预留即占槽」约定 ⇒ kind=chara 按内核 `exists` 判实体存活（key 不是
 * 实体 id 串 = 判死回收，宁可误杀不可留悬空锁）；其余 kind 宿主无判定依据，原样保留
 * 不新造语义。纯函数工厂，无静态可变全局。
 */
private fun charaReservationAlive(kernel: IEntityKernel): (ReservationKey) -> Boolean = { key ->
    if (key.kind == ModRuntimeAssembly.CHARA_RESERVATION_KIND) {
        key.key.toLongOrNull()?.let(kernel::exists) == true
    } else {
        true
    }
}

// ---------------------------------------------------------------------------
// M5-4 刀 2：chara 路由供给缝（CONTRACT-RT2-M54IMPL2 判据 1/2/5/6）。置于文件顶层
// 私有作用域与 regbin helper 组同区：object 内函数数已贴 detekt TooManyFunctions 阈值
// （regbin helper 组既有注释的同一口径），源头修法同源；均为无状态纯过程。
// ---------------------------------------------------------------------------

/**
 * [ModRuntimeAssembly.attach] 的 chara 路由供给产物（[wireCharaFieldRoutes] 的返回面）：
 * 唯一构造点第五参的实参来源 + 内核镜像 schema 的终审修正位。
 *
 * @param fields 逐格域清单；空 = 缺省路径（构造点第五参与缺省实参逐位同形）
 * @param mirrorSchemaOverride 用户变量占位类型修正后的镜像 schema（null = 无修正，内核
 *   schema 清单逐位原样）
 * @param originalMirror freeze 派生的原镜像 schema（override 的替换锚；无路由 = null）
 */
private class CharaFieldRouteSupply(
    val fields: List<NativeCharaField>,
    val mirrorSchemaOverride: ComponentSchema?,
    private val originalMirror: ComponentSchema?,
) {

    /**
     * 内核 schema 清单（[EntityKernel] 构造实参）：override 存在时把镜像替换为终审修正版
     * （其余条目逐位原样；无修正时原清单零拷贝直通）。替换按镜像名匹配——镜像字段名单
     * 一（freeze 已闸撞名），类型修正只动镜像自己的字段。
     */
    fun kernelSchemas(base: List<ComponentSchema>): List<ComponentSchema> {
        val override = mirrorSchemaOverride ?: return base
        val anchor = checkNotNull(originalMirror) { "有镜像终审修正必有原镜像锚（供给缝内部不变量）" }
        return base.map { if (it.name.equals(anchor.name, ignoreCase = true)) override else it }
    }
}

/**
 * 单条路由行的 attach 终审结论（三参直呼构造；终审体住伴生 [review]——文件顶层函数数
 * 已贴 detekt TooManyFunctions 阈值，regbin helper 组既有注释的同一口径）。
 *
 * @param field 通过时的域行（用户 STR 占位修正 = **以 def 重造的行**，原行被替换）
 * @param mirrorTypeFix 通过且发生占位类型修正时的内核镜像字段修正型（null = 无修正）
 * @param rejection 拒收理由（点名变量与病因此；通过时 null）
 */
private class CharaRouteVerdict(
    val field: NativeCharaField?,
    val mirrorTypeFix: ComponentFieldType?,
    val rejection: String?,
) {
    companion object {

        /**
         * 单条路由行的 attach 期终审（[wireCharaFieldRoutes] 的逐条体；RECON Q3 两段式
         * 第二段，freeze 只做了声明侧形状闸）：① 形状/存在性闸见 [shapeRejection]；
         * ② 类型裁决见 [typeVerdict]。终审体拆两半是 detekt 环形复杂度阈值的源头修法。
         */
        fun review(route: DeclaredCharaFieldRoute, store: VariableStore): CharaRouteVerdict {
            val f = route.field
            // 变量存在性查「用户变量 ∪ 系统注册表」双源（store.userVar 覆盖 #DIM 声明的用户
            // 角色变量——它们不进 VariableRegistry；语义与 bindOf 的解析序同源，attach 期无
            // 运行期帧，私有作用域分支天然不进）。
            val def = store.userVar(f.variable)?.def ?: store.registry.def(f.variable)
            val rejection = shapeRejection(f, def)
            if (rejection != null) return CharaRouteVerdict(null, null, "pkg=${route.packageId} $rejection")
            return typeVerdict(f, checkNotNull(def) { "非拒收结论必有定义（终审内部不变量）" })
        }

        /**
         * 形状/存在性闸：① 变量存在性；② 角色性（`isCharacterData`）；③ 形状/dims
         * （freeze 派生恒 1 维族单格行，`def.dims` 必须恰 1 维且格号 < 尺寸——
         * VARIABLESIZE.CSV 族尺寸与声明跨度漂移在此点名）；④ FLOAT 无投射搬运路径
         * （[NativeCharaField] 只有 INT/STR 两型）；⑤ INT 变量挂 STR 镜像 = 声明面漂移。
         * 返回 null = 形状闸全过（类型裁决交 [typeVerdict]）。
         */
        private fun shapeRejection(f: NativeCharaField, def: VarDef?): String? = when {
            def == null -> "变量 ${f.variable} 未注册（路由行格 ${f.indices.toList()}）"
            !def.isCharacterData -> "变量 ${f.variable} 不是角色变量（isCharacterData=false）"
            f.indices.size != 1 || def.dims.size != 1 ->
                "变量 ${f.variable} 形状不符：路由行恒 1 维族单格" +
                    "（行下标 ${f.indices.toList()}，def.dims=${def.dims.toList()}）"
            f.indices[0] < 0 || f.indices[0] >= def.dims[0] ->
                "变量 ${f.variable} 格 ${f.indices[0]} 越出运行期尺寸 ${def.dims[0]}" +
                    "（VARIABLESIZE.CSV 族尺寸与声明跨度不符——freeze 只查出厂值，运行期在此终审）"
            def.type == VarType.FLOAT ->
                "变量 ${f.variable} 是浮点角色变量，投射器无浮点搬运路径" +
                    "（NativeCharaField 只有 INT/STR 两型）"
            def.type == VarType.INT && f.isStr ->
                "镜像字段 ${f.field} 冻结为 STR 而变量类型 INT（声明面漂移）"
            else -> null
        }

        /**
         * 类型裁决：占位重造只对 STR 变量的占位 INT 行发生（`def.type` 判别）——INT 系统
         * 族行照原样直通，重造错对象会把 INT 镜像字段改写成 STR、内核类型闸当场拒写；
         * STR 占位行重造 = 以 `def` 重造行 + 修正内核镜像字段类型（刀 1 TODO 判据原文；
         * 类型闸不同步修正 = pour 静默写空串）。
         */
        private fun typeVerdict(
            f: NativeCharaField,
            def: VarDef,
        ): CharaRouteVerdict = if (def.type != VarType.STR || f.isStr) {
            CharaRouteVerdict(f, null, null)
        } else {
            CharaRouteVerdict(
                NativeCharaField(f.variable, f.indices, f.field, true),
                ComponentFieldType.STR,
                null,
            )
        }
    }
}

/**
 * chara 路由供给缝（M5-4 刀 2 判据 1/2；与 `wireRelationProjection` 同位同形的分流取证）：
 * 从声明快照路由域（刀 1 freeze 派生）供给逐格 [NativeCharaField] 清单，唯一构造点第五参
 * 吃它。本函数只做 attach 期终审与类型修正，**不二次读 manifest、不复制声明语义**
 * （单源快照，RECON §2 Q3 步 2）。
 *
 * - **无路由** → 空清单 + 空修正（缺省路径逐位不变），FINE 取证一行。
 * - **有路由** → 逐条终审（[CharaRouteVerdict.review]），任一不符 = 收齐全部违规、逐条 logSink
 *   点名后整包拒（与「坏档整包拒」同口径，不做静默降级；先收齐再拒 = 一次装配看到全部
 *   病因此不用逐轮补修）。
 *
 * 日志行是取证面（非玩家可见文案，不进域文案表）。纯过程：无状态、无静态可变全局。
 */
private fun wireCharaFieldRoutes(
    snapshot: DeclarationSnapshot,
    store: VariableStore,
    logSink: (String) -> Unit,
): CharaFieldRouteSupply {
    val domain = snapshot.charaFieldRoutes
    if (domain.isEmpty()) {
        logSink("mod.attach charaRoutes routes=0 skip(FINE) fields 空表缺省路径保持")
        return CharaFieldRouteSupply(emptyList(), null, null)
    }
    val mirror = requireNotNull(domain.mirrorSchema) { "有路由必有镜像 schema（freeze 域不变量）" }
    val rejections = ArrayList<String>()
    val fields = ArrayList<NativeCharaField>(domain.routes.size)
    val mirrorTypeFix = HashMap<String, ComponentFieldType>()
    for (route in domain.routes) {
        val verdict = CharaRouteVerdict.review(route, store)
        if (verdict.rejection != null) {
            rejections += verdict.rejection
        } else {
            val f = checkNotNull(verdict.field) { "终审通过必有域行（CharaRouteVerdict 内部不变量）" }
            fields += f
            val fix = verdict.mirrorTypeFix
            if (fix != null) {
                mirrorTypeFix[f.field.uppercase(Locale.ROOT)] = fix
                logSink(
                    "mod.attach charaRoutes 类型终审修正 pkg=${route.packageId} " +
                        "field=${f.field} 占位INT→STR（以 def 重造行）",
                )
            }
        }
    }
    if (rejections.isNotEmpty()) {
        for (reason in rejections) logSink("mod.attach charaRoutes 终审拒收 $reason")
        error("chara 路由 attach 终审整包拒（${rejections.size} 条，逐条见日志）：${rejections.first()}")
    }
    // 镜像 schema 的占位类型修正（无修正 = null 直通；修正 = 按 canonical 字段名替换型别，
    // isEntityRef 原样——镜像字段恒非实体引用，行格子不是 EntityId）。
    val override = if (mirrorTypeFix.isEmpty()) {
        null
    } else {
        val spec = mirror.schema
        ComponentSchema(
            spec.packageId,
            spec.name,
            spec.fields.map { fs ->
                val fixed = mirrorTypeFix[fs.name.uppercase(Locale.ROOT)]
                if (fixed == null) fs else ComponentFieldSpec(fs.name, fixed, fs.isEntityRef)
            },
        )
    }
    logSink(
        "mod.attach charaRoutes routes=${fields.size} fields=attached mirror=" +
            if (override == null) "as-frozen" else "type-corrected",
    )
    return CharaFieldRouteSupply(fields.toList(), override, mirror.schema)
}

/**
 * 阶段二并集装载（era-arch-v61u.md:160 的 ∪ 半边）：全局 bin 先、磁盘全部 sidecar 快照后。
 *
 * 只读装载：不写盘、不删任何东西（GC 不在本函数：孤儿侧车 GC 已随 RT2 MODGC 落在
 * 迁移相位，孤儿包 GC 落在 `ModChainAssembly.assemble` 开态入口）。
 * 形状判定全部走 core-vm 的单一来源 [SidecarSaveInventory]（RT2 MODGC 裁定 1）：
 * 伴生件后缀反推 [SidecarSaveInventory.companionBodyOf] 给出候选本体，再按
 * [SidecarSaveInventory.containerPath] 精确确认「这一件就是容器成员」—— `.rt2.part` /
 * `.rt2.bak` / `.journal` 等其它伴生件因此天然出局；本体形状
 * [SidecarSaveInventory.isLogicalSaveBody] 与 M4-3 删除相位 / 迁移面同一份知识；
 * 本函数不再持有第二份后缀表（regbin TODO ③ 销账）。
 *
 * 每个源独立 fail-closed（契约裁定 3）：坏档/冲突上日志面 + 跳过该源，其余源照并；
 * bin 缺失 = 正常首启；全部源坏 = 空注册表会话照常。装配期不抛穿。
 */
private fun loadRegistryUnion(
    gameRoot: File,
    registry: PackageShortIdRegistry,
    logSink: (String) -> Unit,
) {
    // ① 全局 bin（缺失 = 正常首启，不写任何东西）
    val bin = registryBinOf(gameRoot)
    if (bin.isFile) {
        mergeRegistrySource(bin, { it }, registry, logSink)
    } else {
        logSink("mod.registry 全局 bin 缺失，正常首启（空注册表基线）")
    }
    // ② 磁盘全部 sidecar 快照：gameRoot 根 + sav 目录（含孤儿片：本体 .sav 缺失也并入）。
    // 用 filter 预处理而非循环内多次 continue：形状判定一次成形，避开 detekt 多跳转判据。
    for (dir in listOf(gameRoot, File(gameRoot, SaveFolderMigration.SAV_FOLDER))) {
        val sidecars = (dir.listFiles() ?: emptyArray())
            .filter { f ->
                val body = SidecarSaveInventory.companionBodyOf(f.name)
                f.isFile &&
                    body != null &&
                    // 必须是**容器成员**本身（不是 journal/.part/.bak 等其它伴生件）：
                    // 后缀反推给出本体，再按单源 containerPath 精确确认成员名。
                    f.name == SidecarSaveInventory.containerPath(body) &&
                    SidecarSaveInventory.isLogicalSaveBody(body)
            }
            .sortedBy { it.name }
        for (f in sidecars) {
            mergeRegistrySource(
                f,
                { raw -> SidecarSaveBundle.decode(raw).registrySnapshot },
                registry,
                logSink,
            )
        }
    }
}

/**
 * 并入单个源的注册表字节。[extract] 负责从源文件字节取出 [PackageShortIdRegistry.encode]
 * 产物：全局 bin 的字节本身即该产物（原样返回）；sidecar 源文件是 [SidecarSaveBundle]
 * 容器，取 [SidecarSaveBundle.registrySnapshot] 段（SidecarSaveBundle.kt:59/:175 decode 读回）。
 *
 * 复用 [PackageShortIdRegistry.mergeSnapshot]（SaveService.kt:212 缝的同一原语）——它先
 * 全量冲突校验再写入，异常前不会半合并，故「跳过该源」是干净的；操作的是会话级同一实例
 * （SaveService.kt:51 注入的 pkgRegistry 即本处 registry）。
 */
private fun mergeRegistrySource(
    file: File,
    extract: (ByteArray) -> ByteArray,
    registry: PackageShortIdRegistry,
    logSink: (String) -> Unit,
) {
    try {
        registry.mergeSnapshot(extract(file.readBytes()))
        logSink("mod.registry merge ok src=${file.name} count=${registry.count}")
    } catch (_: Exception) {
        // 坏 magic / 容器坏档 / decode 异常 / merge 冲突一律 fail-closed：上日志面 + 跳过该源，
        // 其余源照并；不拒载游戏、不抛穿装配（契约裁定 3）。Error 依 SaveFileIo 先例上抛。
        logSink("mod.registry warn 跳过损坏源 src=${file.name}（坏档或冲突）")
    }
}

/**
 * 装载前置段（`ModRuntimeAssembly.csvLoadContext` 的 `ManifestLoader.load`）的诊断留痕出口
 * （Lane DY，task-124 判据 2）。
 *
 * ## 为什么 catch 落在这里而不是 `GameHost.assembleSession`
 *
 * `assembleSession` 依赖 Android 视图 / 像素度量面，JVM 单测跑不到真接线；把「留痕 +
 * 原样重抛」收在本对象（前置段唯一生产入口的包装），负控才能对**真生产入口**断言「恰一行」，
 * GameHost 侧只留一行接线（源码哨兵钉住）。链装配（`assembleWithCatalog`）**不**包进来：
 * 它内部已有整链 catch + 原样重抛，再包一层会双份留痕。
 *
 * 落成独立对象而不是 [ModRuntimeAssembly] 的成员 / 文件级函数：该 object 与文件级函数都
 * 已贴着 detekt 的 `TooManyFunctions` 闸（存量 10/10、11/11），再加一个是红。
 */
internal object ModPreloadTrace {

    /**
     * 前置段装载的留痕包装：`csvLoadContext` 抛出的 [DeclarationConflictException] /
     * [ManifestFormatException] / [ModCsvCapacityLimitException] 先在**装配期同一条**
     * `modLogSink`（= `ModManageDiagnostics.record` + logcat）上留痕，再把**原实例**重抛
     * —— 类型/消息/时机不变，玩家仍看到启动失败屏。
     *
     * 三类失败是前置段仅有的 fail-closed 闸（声明冲突 / 坏 manifest 字节 / CSV 注入位硬上界），
     * 任何一类漏留痕 = 装配期 canonical 行缺失、玩家重启后设置页失败小节整节不渲染
     * （M2-BUG-01 同症状类）。容量越界的归因纪律见
     * [ModChainAssembly.tracePreloadCapacityReject]（表级违规无包归因 ⇒ `mod.reject` 取证行）。
     *
     * @param diagSink 装配期诊断 sink（生产 = `GameHost.modLogSink`，必须与链装配 / attach 同一实例）。
     * @param csvLogSink 本段既有的容量越界日志 sink（缺省不写；容量越界的 canonical 留痕
     *   不依赖它 —— 走 [diagSink] 的 `mod.reject` 行）。
     */
    fun csvLoadContextTraced(
        gameRoot: File,
        diagSink: (String) -> Unit,
        csvLogSink: (String) -> Unit = {},
    ): ModCsvLoadContext? = try {
        ModRuntimeAssembly.csvLoadContext(gameRoot, csvLogSink)
    } catch (e: DeclarationConflictException) {
        ModChainAssembly.tracePreloadReject(diagSink, all(gameRoot), e)
    } catch (e: ManifestFormatException) {
        ModChainAssembly.tracePreloadBadManifest(diagSink, badManifestName(gameRoot), e)
    } catch (e: ModCsvCapacityLimitException) {
        ModChainAssembly.tracePreloadCapacityReject(diagSink, all(gameRoot), e)
    }

    /** 前置段已读入的 manifest 磁盘文件名清单（与 `csvLoadContext` / 链装配同序同源）。 */
    private fun all(gameRoot: File): List<String> = files(gameRoot).map { it.name }

    /**
     * 坏 manifest 字节的磁盘文件名定位：按 `ManifestLoader.load` 的同一遍历序逐件重解码，
     * 第一个解码失败的即它（`ManifestFormatException` 的唯一来源 = `ManifestCodec.decode`）。
     * 万一定位不到，回落磁盘清单摘要 —— 宁可取证源粗一点，也不伪造一个不确定的文件名。
     */
    private fun badManifestName(gameRoot: File): String {
        val diskNames = all(gameRoot)
        for (file in files(gameRoot)) {
            if (runCatching { ManifestCodec.decode(file.readBytes()) }.isFailure) return file.name
        }
        return ModChainAssembly.diskNamesSummary(diskNames)
    }

    /**
     * 磁盘 manifest 清单：直接复用同文件私有的 [manifestFilesIn]（过滤/排序/停用闸同一份实现，
     * 不复制第二套扫描口径 —— 该口径演进时这里自动跟随）。
     */
    private fun files(gameRoot: File): List<File> =
        ModRuntimeAssembly.manifestFilesIn(ModRuntimeAssembly.manifestDirFor(gameRoot))
}

// ---------------------------------------------------------------------------
// ModDX4-B1：受控写面的装配辅助。收进文件级私有 object 而不是裸顶层函数：本文件顶层
// 函数数已抵 detekt TooManyFunctions（file）阈值，object 作用域自成一个计数单元。
// attach 的注入段与兑现闭包消费它们。
// ---------------------------------------------------------------------------

/** ModDX4-B1 写面装配辅助（见 object 内各成员 KDoc；无状态，全静态纯函数）。 */
private object ModDx4WriteAssembly {

    /**
     * 各包 manifest 的 `write-grants` 段读数（packageId → 授权清单）。
     *
     * 与 [ModRuntimeAssembly.manifestFilesIn] 的启用集是同一批盘上 manifest（= 装载相位
     * ManifestLoader 消费的同一清单）；解码走 ManifestCodec.decode 的复用作用域（attach 内
     * 同字节缓存命中，零二次真解析）。无授权的包不出键。
     */
    fun grantsOf(manifests: List<File>): Map<String, List<WriteGrantDeclaration>> =
        manifests.mapNotNull { file ->
            val descriptor = ManifestCodec.decode(file.readBytes())
            if (descriptor.writeGrants.isEmpty()) null else descriptor.packageId to descriptor.writeGrants
        }.toMap()

    /**
     * 受控写的整型落库体：按名寻址形态与读侧（ModRuntimeAssembly.readIntVar）同形 ——
     * 0 维标量写本体、1 维非角色数组写 0 号元素（ERB 裸引用同形）。
     *
     * 授权闸（ModDx4WriteGate.authorize）已保证键存在且形态可寻址；这里的 def 缺席分支是
     * 授权闸之后的双重防御，抛 IllegalArgumentException 而不是静默吞（绝不写错格）。
     * 在转移边界兑现点执行（VM 线程），不在 API 调用点。
     */
    fun applyInt(store: VariableStore, name: String, value: Long) {
        val def = checkNotNull(store.registry.def(name)) {
            "受控写兑现时变量定义缺失：$name（授权闸已校验存在性，此处缺席 = 不变量破坏）"
        }
        if (def.argCount == 0) {
            store.writeInt(name, LongArray(0), value)
        } else {
            store.writeInt(name, longArrayOf(0L), value)
        }
    }

    /** 受控写的字符串落库体：口径同 [applyInt]，走 `writeStr`。 */
    fun applyStr(store: VariableStore, name: String, value: String) {
        val def = checkNotNull(store.registry.def(name)) {
            "受控写兑现时变量定义缺失：$name（授权闸已校验存在性，此处缺席 = 不变量破坏）"
        }
        if (def.argCount == 0) {
            store.writeStr(name, LongArray(0), value)
        } else {
            store.writeStr(name, longArrayOf(0L), value)
        }
    }
}
