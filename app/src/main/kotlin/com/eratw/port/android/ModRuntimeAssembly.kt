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
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.mod.ActionExecutionOutcome
import com.eratw.port.core.mod.DecisionPipeline
import com.eratw.port.core.mod.DecisionSeed
import com.eratw.port.core.mod.DecisionState
import com.eratw.port.core.mod.DecisionStateCodec
import com.eratw.port.core.mod.GameTickDriver
import com.eratw.port.core.mod.JobInstance
import com.eratw.port.core.mod.JobSchedulerSnapshot
import com.eratw.port.core.mod.JobSchedulerStateCodec
import com.eratw.port.core.mod.ModContextImpl
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModRuntimeState
import com.eratw.port.core.mod.ModStorageStore
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
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.patch.DeclaredArchetype
import com.eratw.port.core.patch.DeclaredCharaFieldRoute
import com.eratw.port.core.patch.ManifestLoader
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.NamePolicy
import com.eratw.port.core.vars.ModCsvCapacityLimitException
import com.eratw.port.core.vars.ModCsvLoadContext
import com.eratw.port.core.vars.ModCsvPlanLoader
import com.eratw.port.core.vars.ModCsvValueBag
import com.eratw.port.core.vars.SystemLayout
import com.eratw.port.core.vars.VarDef
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.GameTime
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
 * ## 总闸：默认关
 *
 * 开关由 GameHost 构造参数 `modRuntime` 承载（缺省 false = 关），本文件在 [attach] 入口先查闸：
 * 关 = 一个对象都不建、磁盘都不碰，返回 null——与未接线前逐字节等价。**不做成
 * 「探测到异常就静默跳过」**：开态下任一 manifest 坏档（[com.eratw.port.core.patch.ManifestFormatException]）
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
 * 时段，与 `GameTime.phase`（相位名字符串）之间**没有权威映射锚**——本车不发明映射，
 * `GameTime.phase` 恒空串 = JobScheduler 既有的「不限相位」语义（GameScheduler.kt 的
 * 「GameTime.phase 为空时不限相位」），任务照旧按游戏日触发；相位派发（DAY_START / DAY_END /
 * ACTION）留空闸，映射锚属需组长接线（TODO(verify)，见交付）。
 *
 * 纯 JVM、无 Android API、无静态可变全局（铁律 4）：全部状态挂实例（[ModRuntimeSession] /
 * [VmBoundaryScheduler] / [PackageShortIdRegistry]），本 object 不留任何 var。
 */
/**
 * mod 链链接产物 [SymbolCatalog] 快照的 aux 槽名（W2-SUPCAT；RuntimeAux.kt:17 的命名约定，
 * `mod` 能力家族前缀全小写）。写侧 = mod 链装配面（生产接线归组长，卡点取证见
 * [ModChainAssembly] assembleChain 的注释与 CONTRACT-RT2-SUPCAT 交付报告「需组长接线」）；
 * 读侧唯一 = [ModRuntimeAssembly.attach] 尾段 [backfillEntityRuntimeSeam]（RuntimeAux.peek
 * 只查不建，读侧不可能伪造装配态）。快照生命周期随 store（会话级），不跨会话持有。
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
     * `chara:<entityId>` 预留即占槽」；key = 实体 id 十进制串（TODO(verify)：SDK 无生产
     * claimant，格式以该 KDoc 记法为准，M10-1 接入后如有出入由组长裁定）。
     */
    const val CHARA_RESERVATION_KIND: String = "chara"

    /**
     * 装配入口：总闸关 ⇒ 零接触返回 null；开 ⇒ 走完整装载链，返回会话级运行时。
     *
     * @param gameRoot 游戏根（manifest 目录 = `ModStore.modsDirFor(gameRoot)/rt2`）
     * @param store 本会话的 [VariableStore]（`intVarView`/`strVarView` 读数与节拍的 `DAY` 读数同源）
     * @param nativeRegistry 生产本地方法注册表（GameHost 经 `nativeMethods(store.aux)` 取的
     *   aux 槽实例——CALLSHARP 策略缺席位同表，不新建第二份）
     * @param onVmThread VM 线程判据（GameHost 的 `onVmThread()`；scheduler 的主线程判据同源）
     * @param enabled 总闸读数（GameHost 构造参数 `modRuntime` 原样透传）
     * @param logSink 日志面（宿主注入；本文件不直接依赖 android.util.Log）
     * @param modProviders Kotlin mod 供应商名单（生产来源 = [com.eratw.port.android.ModCarrierRegistry]；
     *   packageId 不在磁盘 manifest 快照内 = install 逐 mod fail-closed，active=false 上日志面）
     * @param newHost 宿主构造缝（唯一构造点；测试经它数构造次数做关态反向证明）
     * @param decisionEntropyTail 决策种子的真熵尾巴供给（W2-SEED 判据 1）。缺省
     *   `freshDecisionEntropyTail` = 每次装配局部新建 `SecureRandom` 取 16 字节——每局
     *   新种子；测试注入确定字节做「同熵同种子 / 异熵异种子」定向。lambda 只在开态
     *   构造管线时求值（关态零求值 = 关态零接触），核心层只收字节参数（熵纪律）。
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
        decisionEntropyTail: () -> ByteArray = ::freshDecisionEntropyTail,
        newHost: (AssemblyInputs) -> ModRuntimeHost = ::defaultHost,
    ): ModRuntimeSession? {
        // 总闸第一查：关 = 装配链一次都不进（ModRuntimeHost 构造次数 == 0，反向证明①的判据）。
        if (!enabled) return null

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

        val inputs = AssemblyInputs(
            snapshot = snapshot,
            entityKernel = kernel,
            nativeMethods = nativeRegistry,
            logSink = logSink,
            runtime = scheduler,
            shortIdOf = { pkg -> shortIdRegistry.allocate(pkg) },
            intVarView = { name -> readIntVar(store, name) },
            strVarView = { name -> readStrVar(store, name) },
            storage = storage,
            projector = projector,
            decisionPipeline = decisionPipeline,
        )
        val host = newHost(inputs)

        // ---- 阶段三：逐 mod 装载（宿主内失败隔离：单 mod 失败只回滚它自己） ----
        val report = host.install(modProviders)
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
        // 注册落点就是各 mod context 自己的 phaseRegistry/jobScheduler（ModContextImpl 的
        // 装配产物，宿主 dispatch 接线按其 KDoc 消费）；跨 mod 派发序 = install 序 = 链接序。
        val drivers = ArrayList<GameTickDriver>(report.size)
        for (state in report) {
            if (!state.active) continue
            val context = checkNotNull(host.contextOf(state.packageId) as? ModContextImpl) {
                "启用 mod 的装配 context 缺失或类型不符：${state.packageId}（宿主接线不变量破坏）"
            }
            drivers += GameTickDriver(context.phaseRegistry, context.jobScheduler, diagnostics)
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
        // W1-DSTATE：决策/任务态缝后期装配（构造签名被 detekt baseline 的 LongParameterList
        // 条目钉住，新增构造参数 = 结构性新 ID；手法与 bindRegistry 同源，逻辑住独立类）。
        session.decisionState = ModDecisionJobState(
            pipeline = decisionPipeline,
            host = host,
            activePackageIds = activePackageIds,
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
        val port = ModSidecarStoragePort(
            saveService = saveService,
            storageProvider = { session.storage },
            adoptStorage = { session.adoptStorage(it) },
            activePackages = { activePackageIds },
            decisionStateProvider = { session.decisionState },
            logSink = logSink,
        )
        // M1-4 阶段二：注入计划与关键字查询面同源于装载相位上下文（裁定 3：不在这里派生第二份）。
        // 上下文缺席 ⇒ 计划 null / 查询面 null ⇒ 侧车「无 CSV 段」原路（阶段一既有零变化口径）。
        val csvPlan = csvContext?.plan
        val csvKeywordIndex = if (csvPlan != null && !csvPlan.isEmpty) {
            { table: String, keyword: String ->
                runCatching { store.registry.csvTable(table).keywordIndex(keyword) }.getOrNull()
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
        backfillEntityRuntimeSeam(
            store,
            EntitySeamInputs(snapshot, kernel, projector, shortIdRegistry, report),
            logSink,
        )
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

    /** 目录内的 manifest 清单（文件名升序 = 确定性链接序；目录缺失 / 非目录 = 空清单）。 */
    private fun manifestFilesIn(dir: File): List<File> {
        val entries = dir.listFiles() ?: return emptyList()
        return entries
            .filter { it.isFile && it.name.endsWith(MANIFEST_EXTENSION) }
            .sortedBy { it.name }
    }

    /**
     * 唯一宿主构造点：六个注入位全部来自 [AssemblyInputs] 的真值，缺省位一个不留；
     * F 车再补第七项 [AssemblyInputs.projector]（不传它 = E 车之前的 fail-closed 形态，
     * `context.roster` 四入口一律抛，生产缝退化不会被既有测试误判为绿）；
     * W1-DSTATE 再补第八项 [AssemblyInputs.decisionPipeline]（判据 2：开态真管线注入，
     * 产物是 `context.actions` 脱 fail-closed 桩）。
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
 *   [MOD_LINK_CATALOG_SLOT] 槽的链接产物快照（W2-SUPCAT attach 读缝）：HAS_SUPER 判据源
 *   是 mod 链装配产物 SymbolCatalog，单源 = ModChainAssembler.assemble 五步链的链接产物
 *   （不二次装配、不复制链接语义；ModChainRuntimePlan 不保留 catalog、装配点与 store
 *   构造时序错位，生产写侧接线归组长）；槽缺席（生产现状）⇒ null ⇒ HAS_SUPER 按
 *   「未装配 ⇒ 0」的既有旁路口径如实回答（接口 KDoc 明文允许 null），不是吞语义。
 * - 短 ID 不预分配：registry 就是会话级 PackageShortIdRegistry 原样（install 期为
 *   载体包逐包 allocate）；无 Kotlin 载体的声明包调 ENTITY_CREATE 按函数层既有口径
 *   报「声明包未登记短 ID」—— 是否给声明包预占短 ID 牵动 regbin 字节确定性，归组长
 *   裁定（登记见交付报告），本波不擅自扩。
 */
/**
 * [backfillEntityRuntimeSeam] 的入参包：attach 五个产物的只读集合（收参数数过
 * LongParameterList 闸；做成单参载体，同 [AssemblyInputs] 的手法）。
 */
private class EntitySeamInputs(
    val snapshot: DeclarationSnapshot,
    val kernel: EntityKernel,
    val projector: ICharaSlotProjector,
    val registry: PackageShortIdRegistry,
    val report: List<ModRuntimeState>,
)

private fun backfillEntityRuntimeSeam(
    store: VariableStore,
    inputs: EntitySeamInputs,
    logSink: (String) -> Unit,
) {
    // W2-SUPCAT 读缝：链接产物快照挂在 [MOD_LINK_CATALOG_SLOT]（写侧 = mod 链装配面），
    // peek 只查不建——槽缺席 = 未装配语义（生产现状旁路 0），不可能伪造装配态。
    val linkCatalog = store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT)
    store.aux.slot(ENTITY_RUNTIME_SLOT) {
        AttachedEntityRuntime(inputs, linkCatalog)
    }
    logSink(
        "mod.attach entityRuntime 回填 archetypes=${inputs.snapshot.entityArchetypes.size} " +
            "mods=${inputs.report.count { it.active }} " +
            "catalog=${if (linkCatalog != null) "attached" else "absent"}",
    )
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
     * 链接产物快照（[MOD_LINK_CATALOG_SLOT] 读缝；null = 槽缺席 = 生产现状旁路 0）。
     * 只读引用原样持有：不复制、不改写链接语义（契约判据 3），生命周期随 store。
     */
    private val linkCatalog: SymbolCatalog?,
) : EntityRuntime {

    override val kernel: IEntityKernel = inputs.kernel

    override val projector: ICharaSlotProjector? = inputs.projector

    override val registry: PackageShortIdRegistry = inputs.registry

    /** 原型查表按 NamePolicy.canonical 归一（接口 KDoc 的 canonical 查表口径；快照键本就是规范形）。 */
    override val archetypes: (String) -> DeclaredArchetype? =
        { name -> inputs.snapshot.entityArchetypes[NamePolicy.canonical(name)] }

    /** 见 [backfillEntityRuntimeSeam] 的 catalog 说明（W2-SUPCAT 读缝注入）。 */
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
    private val activePackageIds: Set<String>,
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
     * 任务态段载荷（存档抓取路径调）：全活跃 mod 调度器快照的**会话并集**
     * （静态键 / 序号表键自带 `packageId::` 前缀、实例自带 packageId，并集无撞键），
     * `JobSchedulerStateCodec` VERSION 2 字节。无活跃 mod = 空表快照照编（确定性字节）。
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
        logSink("mod.rt2 决策种子随档采纳 seed=$payloadSeed（原装配种子=$before）")
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
     * 任务态回灌（读档缝调，判据 3/5）：会话并集快照按活跃包拆分，逐 mod 喷进各自
     * 调度器（`JobScheduler.restore` 同款校验 fail-closed）。非活跃包的条目随拆分丢弃
     * （mod 未安装 = 无宿主可回灌；与孤儿分片「原样保留」不同层：任务态是易失运行态，
     * 无透传语义，回落「无实例」）。
     */
    fun restoreJobState(snapshot: JobSchedulerSnapshot) {
        for (pkg in activePackageIds) {
            val scheduler = (host.contextOf(pkg) as? ModContextImpl)?.jobScheduler ?: continue
            scheduler.restore(
                JobSchedulerSnapshot(
                    nextFireDays = snapshot.nextFireDays.filterKeys { it.startsWith("$pkg::") },
                    instances = snapshot.instances.filter { it.packageId == pkg },
                    nextSeqByTemplate = snapshot.nextSeqByTemplate.filterKeys { it.startsWith("$pkg::") },
                ),
            )
        }
    }

    /** 全活跃 mod 的任务态并集（确定性：snapshot 各自排序，并集按键无歧义）。 */
    private fun mergedJobSnapshot(): JobSchedulerSnapshot {
        val nextFire = HashMap<String, Long>()
        val instances = ArrayList<JobInstance>()
        val nextSeq = HashMap<String, Long>()
        for (pkg in activePackageIds) {
            val scheduler = (host.contextOf(pkg) as? ModContextImpl)?.jobScheduler ?: continue
            val snap = scheduler.snapshot()
            nextFire.putAll(snap.nextFireDays)
            instances.addAll(snap.instances)
            nextSeq.putAll(snap.nextSeqByTemplate)
        }
        return JobSchedulerSnapshot(nextFire, instances, nextSeq)
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

    /** 由 [ModRuntimeAssembly.attach] 在会话构造后、返回前绑定一次；只在开态发生。 */
    internal fun bindRegistry(pkgRegistry: PackageShortIdRegistry, bin: File) {
        registryBinding = RegistryBinding(pkgRegistry, bin)
    }

    /** 会话注册表只读观察面（并集装载取证 / 定向测试；未绑定返回 null）。 */
    fun shortIdOf(packageId: String): Int? = registryBinding?.pkgRegistry?.shortIdOf(packageId)

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

    /** 上一条「DAY 读数失败」是否已留痕（同因只报一次，不刷屏）。 */
    private var dayReadFailureLogged: Boolean = false

    /** 最近一次已消费的退场回写报告（页边界日志消费的幂等水位；按引用比对）。 */
    private var lastConsumedRelationReport: RelationProjectionSynchronizer.RelationWriteBackReport? = null

    /**
     * 页边界节拍（GameHost 的 `onTurnReady` 安全点调；本函数**保证不抛**）。
     *
     * 顺序固定三步：① 兑现转移边界积压的提交（[VmBoundaryScheduler.drainPending]）；
     * ② 读 `DAY`（只读，写入次数必须为 0——ERB 脚本拥有时钟）构造 [GameTime]（相位恒空串，
     * 理由见 [ModRuntimeAssembly] 类头「单一时间源」节），逐 driver 推进
     * [GameTickDriver.tick]（相位派发 + `jobs.runDue`，per-mod 失败隔离在 core 内）；
     * ③ 仲裁外轮（W2-ARDRV 判据 1，[ModArbitrationRound.runOuterRound]）——每页边界
     * **只跑一次**（2026-10-31 真机 OOM 先例的频率纪律：不许每帧轮询），排在 driver 节拍
     * 之后（蓝图 §21.3「JobScheduler/相位决定何时评估，仲裁器决定做哪一件」：先让任务/
     * 相位把世界状态推进到位，再评估候选）。
     *
     * 宿主对 [com.eratw.port.modapi.InteractionRunResult.failures] 同款义务在相位/任务报告上
     * 就地兑现：`hasFailures` 的报告逐条记诊断（JOB / PHASE 域 FAILED）并上日志面——
     * 不静默（P2-1 的消费判据；交互管线本身没有生产触发点，随输入状态机接线走）。
     */
    fun onTurnBoundary() {
        try {
            if (shutDown) return
            // ① 转移边界：先兑现积压提交，再推进节拍。
            scheduler.drainPending()
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
            // phase 恒空是**裁定**不是漏接：反编译 C# 全库（reference/emuera-cs 与 unpacked，
            // 大小写不敏感搜 phase / GameTime）零命中 ⇒ 官方引擎没有「游戏相位」这个概念，
            // GameTime.phase 是 modapi 的移植侧扩展，TIME→phase 无权威映射可照抄。
            // 按「不确定降 confidence、禁止脑补」的纪律不发明映射：空相位 ⇒ PhaseRegistry
            // 直接 fired=0，按游戏日的任务照常跑（GameTickDriver.tick）。将来要启用相位派发，
            // 须先立项定义「谁决定 phase」并由宿主显式注入；本处不猜。
            // 反向证明：ModRuntimeAssemblyWireTest 的「空相位是裁定而非漏接」用例。
            val time = GameTime(day = day, phase = "")
            for (driver in drivers) {
                val report = try {
                    driver.tick(time)
                } catch (t: Throwable) {
                    logSink("mod.tick driver 异常已隔离（本拍该 mod 旁路）：${t.javaClass.simpleName}: ${t.message}")
                    continue
                }
                // 报告消费：失败逐条记诊断 + 上日志面（不静默）；有活动的拍留一行读数。
                for (failure in report.phase.failures) {
                    recordFailureDiagnostic(time, DiagnosticDomain.PHASE, failure.packageId, failure.phase, failure.cause)
                    logSink(
                        "mod.tick 相位处理器失败 pkg=${failure.packageId} phase=${failure.phase} " +
                            "${failure.cause.javaClass.simpleName}: ${failure.cause.message}",
                    )
                }
                for (failure in report.jobs.failures) {
                    recordFailureDiagnostic(time, DiagnosticDomain.JOB, failure.packageId, failure.jobId, failure.cause)
                    logSink(
                        "mod.tick 任务处理器失败 pkg=${failure.packageId} job=${failure.jobId} " +
                            "${failure.cause.javaClass.simpleName}: ${failure.cause.message}",
                    )
                }
                if (report.firedPhaseHandlers > 0 || report.firedJobs > 0) {
                    logSink(
                        "mod.tick day=$day phaseHandlers=${report.firedPhaseHandlers} jobs=${report.firedJobs}",
                    )
                }
            }
            // ③ 仲裁外轮（W2-ARDRV 判据 1）：缝缺席（关态 / 未装配）= no-op；域内无候选由
            // 管线自身完全旁路（不执行处理器、不建预留、不记账，决策态零变化）。
            arbitrationRound?.runOuterRound(day)
        } catch (t: Throwable) {
            // 兜底隔离：节拍绝不把异常抛回页边界；留痕即不静默。
            logSink("mod.tick 边界异常已隔离：${t.javaClass.simpleName}: ${t.message}")
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
        for (state in host.shutdown()) {
            val failure = state.failure
            if (failure != null) {
                logSink(
                    "mod.shutdown pkg=${state.packageId} 安装期失败(回滚态) ${failure.javaClass.simpleName}: ${failure.message}",
                )
            }
        }
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

    private fun hostCount(): Int = drivers.size

    private companion object {
        /** 游戏日权威的系统变量名（SystemLayout.int1D 第 0 项；VariableCode.kt:34 DAY=720896）。 */
        const val DAY_NAME: String = "DAY"
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
 * 物理上就是会话级 ModStorageStore 本身，SAVEGLOBAL 抓的分片与 SAVEGAME 抓的分片
 * 是同一次 `sectionsSnapshot` 的同一批段；差别只在容器与恢复语义——global 走
 * [SaveService.captureGlobalBundle]（实体载荷恒空）+ [SaveService.restoreGlobalBundle]
 * （只交还分片，不并注册表、不灌内核），槽档走 captureBundle + restoreBundle。
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
) : SidecarStoragePort, GlobalSidecarStoragePort {

    override fun captureChunks(csv: ModCsvValueBag): SidecarChunkGroups {
        val byPackage = csv.byPackage()
        val active = ArrayList<PackageChunk>()
        val orphan = ArrayList<PackageChunk>()
        for (section in storageProvider().sectionsSnapshot()) {
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
        return SidecarChunkGroups(active, orphan)
    }

    override fun captureRt2Bytes(
        binding: SidecarBinding,
        activeChunks: List<PackageChunk>,
        orphanChunks: List<PackageChunk>,
    ): ByteArray {
        val base = saveService.captureBundle(binding, activeChunks, orphanChunks)
        // W1-DSTATE 判据 1/3：SaveService 组装的分段原样保留，追加决策态段 + 任务态段
        // （载荷取自会话缝快照；缝缺席/载荷空 = 旧档同形，SidecarSaveBundle 的
        // 「都写或都不写」纪律）。
        val state = decisionStateProvider()
        return SidecarSaveBundle(
            binding = base.binding,
            registrySnapshot = base.registrySnapshot,
            entityGraphPayload = base.entityGraphPayload,
            chunks = base.chunks,
            virtualNoSnapshot = base.virtualNoSnapshot,
            stateSegments = if (state == null) {
                SidecarStateSegments.EMPTY
            } else {
                SidecarStateSegments(state.decisionStatePayload(), state.jobStatePayload())
            },
        ).encode()
    }

    override fun restoreRt2(bytes: ByteArray, binding: SidecarBinding): SidecarRestoreResult {
        val bundle = SidecarSaveBundle.decode(bytes)
        return when (val outcome = saveService.restoreBundle(bundle, binding)) {
            is SaveService.RestoreOutcome.Applied -> {
                val sections = bundle.chunks.flatMap { chunk ->
                    com.eratw.port.core.mod.ModStorageSectionCodec.decode(
                        ModCsvValueCodec.decode(chunk.payload).storage,
                        activePackages(),
                    )
                }
                val rebuilt = ModStorageStore.load(
                    com.eratw.port.core.mod.ModStorageSectionCodec.encode(sections),
                    knownPackages = activePackages(),
                )
                adoptStorage(rebuilt)
                // W1-DSTATE 判据 3：决策态/任务态回灌。旧档无段 = 空载荷跳过（回落口径）；
                // 缝未装配 = 状态段无处可回灌（fail-closed 上日志面，不静默丢）；
                // 坏段/种子不符 = codec 或 restoreState fail-closed 原样抛（读档缝的既有
                // catch 收警告，「恢复中途失败」不吞、不新造放宽）。
                val decisionState = decisionStateProvider()
                if (decisionState == null) {
                    if (bundle.decisionStatePayload.isNotEmpty() || bundle.jobStatePayload.isNotEmpty()) {
                        logSink("mod.rt2 warn 容器带决策/任务态段但本会话未装配决策缝，状态段未回灌")
                    }
                } else {
                    if (bundle.decisionStatePayload.isNotEmpty()) {
                        val decoded = DecisionStateCodec.decode(bundle.decisionStatePayload)
                        // W2-SEED 判据 2：档内种子先采纳（相等 = no-op 走原闸；不等 =
                        // supplySeed fail-closed），相等闸语义不动、只补供给序。
                        decisionState.adoptSeedFromSave(decoded.decisionSeed)
                        decisionState.restoreDecisionState(decoded)
                    }
                    if (bundle.jobStatePayload.isNotEmpty()) {
                        decisionState.restoreJobState(
                            JobSchedulerStateCodec.decode(bundle.jobStatePayload),
                        )
                    }
                }
                // CSV 值只并激活包：孤儿片的注入值不写回变量池（禁用期间不覆盖当前档）。
                val csv = ModCsvValueCodec.mergeApplied(bundle.chunks, activePackages())
                logSink(
                    "mod.rt2 restore ok chunks=${bundle.chunks.size} " +
                        "packages=${rebuilt.packages().size} active=${activePackages().size} csv=${csv.size}",
                )
                SidecarRestoreResult(true, csv)
            }
            is SaveService.RestoreOutcome.BindingMismatch -> {
                logSink(
                    "mod.rt2 绑定失配，扩展状态未应用（期望=$binding 实际=${outcome.actual}）；" +
                        "以 Base 状态继续运行",
                )
                SidecarRestoreResult(false)
            }
        }
    }

    /**
     * global.rt2 分片抓取：与槽档 [captureChunks] 同源同构（同一份 sectionsSnapshot、
     * 同一种 MST1 片载荷）——GLOBAL 域 Mod 载体的物理位就是会话 ModStorageStore 本身。
     */
    override fun captureGlobalChunks(): SidecarChunkGroups = captureChunks()

    /** global.rt2 容器：走 [SaveService.captureGlobalBundle]（实体载荷恒空、虚拟 NO 恒空）。 */
    override fun captureGlobalRt2Bytes(
        binding: SidecarBinding,
        activeChunks: List<PackageChunk>,
        orphanChunks: List<PackageChunk>,
    ): ByteArray = saveService.captureGlobalBundle(binding, activeChunks, orphanChunks).encode()

    /**
     * LOADGLOBAL 交还：[SaveService.restoreGlobalBundle] 只验绑定与 global 形状，分片在此
     * 重建容器并经 [adoptStorage] 交还——重绑链（`ModRuntimeHost.rebindStorage` →
     * `ModContextImpl.rebindStorage`）让 mod 侧 `context.storage` 立即可见（RT2 D 车已通）。
     */
    override fun restoreGlobalRt2(bytes: ByteArray, binding: SidecarBinding): Boolean {
        val bundle = SidecarSaveBundle.decode(bytes)
        return when (val outcome = saveService.restoreGlobalBundle(bundle, binding)) {
            is SaveService.GlobalRestoreOutcome.Applied -> {
                val sections = outcome.chunks.flatMap { chunk ->
                    com.eratw.port.core.mod.ModStorageSectionCodec.decode(
                        ModCsvValueCodec.decode(chunk.payload).storage,
                        activePackages(),
                    )
                }
                val rebuilt = ModStorageStore.load(
                    com.eratw.port.core.mod.ModStorageSectionCodec.encode(sections),
                    knownPackages = activePackages(),
                )
                adoptStorage(rebuilt)
                logSink(
                    "mod.global.rt2 restore ok chunks=${outcome.chunks.size} " +
                        "packages=${rebuilt.packages().size} active=${activePackages().size}",
                )
                true
            }
            is SaveService.GlobalRestoreOutcome.BindingMismatch -> {
                logSink(
                    "mod.global.rt2 绑定失配，全局扩展状态未应用（期望=$binding 实际=${outcome.actual}）；" +
                        "以 Base 状态继续运行",
                )
                false
            }
        }
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
    for ((pkg, mod) in providers) {
        (mod as? CharacterMod)?.decisionDriver =
            CharacterMod.DecisionDriver { domain, metadata -> arbitration.resolveNested(domain, metadata) }
    }
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
