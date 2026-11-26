package com.eratw.port.android

import com.eratw.port.core.mod.ModDx3AssetMap
import com.eratw.port.core.save.SaveDiagnostics
import com.eratw.port.platform.storage.ModStore
import java.io.File

/**
 * `mods/rt2/` 导入产物（容器 + manifest + provenance + 内容指纹）的**唯一形状与孤儿 GC
 * 来源**（RT2 MODGC 裁定 1/3/4；指纹侧车 = Lane O）。
 *
 * ## 为什么需要 provenance（`.sources`）
 *
 * 导入链的包身份 = `kojo-` + sha256(排序后留档源名) 前 8 位（[ModImportController.packageIdFor]），
 * 容器与 manifest 里都**不带**源文件名。没有第三件侧车就无法回答「这个包的源还在不在」，
 * 玩家删掉留档源后产物会永远生效（`era-arch-v61u.md` §23.4 的孤儿包余量）。
 * `<packageId>.sources` 逐行记下当次导入的留档源名（排序 = `packageIdFor` 的同序），
 * 只做 provenance，不是装载面文件（`manifestFilesIn` / `collectErbModules` 都不认它）。
 *
 * ## 为什么需要内容指纹（`.sources.sha256`，Lane O）
 *
 * `.sources` 线格式冻结（只记文件名），「源内容变没变」原先只能看 mtime；同一时间片内改
 * 内容 / 从 zip 解出 / 跨设备拷贝都会漏判。`<packageId>.sources.sha256` 是**同一份源名单的
 * 内容摘要侧车**（[ModSourceFingerprint]），供 [ModRecompiler.isUpToDate] 判「内容一致」。
 * 它是**可选件**：老包没有它 ⇒ 退回 mtime 判据，GC 不因缺它算半成品（判据 2 只看容器与
 * manifest），导入/重编/迁移路径会在下次落盘时补写。
 *
 * ## 资源目录（`<packageId>.assets/`）
 *
 * 资源面读取侧（`core-vm` 的 `ModResourceStore`）把包资源放在 `mods/rt2/<packageId>.assets/`
 * 目录里。它是**同一包的第 5 个成员形状**，但不是"文件成员"：
 *
 * - 导入包（口上 zip / `.ERB`/`.ERH` 直选）**携带**资源条目，由 [ModImportAssets] 落成
 *   `mods/rt2/<packageId>.assets/`（原子落盘 = 先写 `.tmp-<nano>` 再 rename）；
 * - 卸载（[ModManageUninstall]）按同包口径把它整棵树一起删；
 * - 孤儿 GC 把它算进所属包一起清：包是半成品或源失联时，资源目录跟着删；但**只有资源目录、
 *   没有任何包文件**时不进入孤儿判据（作者可能先摆资源再导入，保守不删），也绝不把它
 *   单独当成"额外包"或孤儿件。
 *
 * ## 资源面残留目录（本文件新增判据，2026-11）
 *
 * [ModImportAssets] 的原子落盘会在 `mods/rt2/` 下留两类**过程中的**目录：
 * `<packageId>.assets.tmp-<nano>`（写临时面）与 `<packageId>.assets.bak-<nano>`（替换前备份
 * 旧面）。正常收尾两者都会消失；进程在 swap 中途被杀会留下——它们既不是包成员（没有
 * 容器/manifest 归属），也不会被后续导入认领，只会白占存储并让玩家看见无法解释的目录。
 *
 * GC 补一轮残留清理，判据（与包级孤儿判据相互独立）：
 *
 * 1. **形状精确**：名字必须是 `<合法 packageId>.assets.tmp-<数字>` 或
 *    `<packageId>.assets.bak-<数字>`（后缀常量与 [ModImportAssets] 同源），且必须是目录；
 *    `foo.txt`、`x.assets.`、`eratw..assets.tmp-1`、非数字序号一律不碰；
 * 2. **在用面永不删**：同一 packageId 的 `<packageId>.assets/` 目录只要还在，残留就当
 *    "可能仍被替换流程引用"保守保留（宁可留垃圾也不删活资源）；
 * 3. 删除走 `deleteRecursively()` + `MOD_PKG_GC` 留痕；删除失败不抛、原地保留并留 WARN。
 *
 * ## 设置侧车（`<packageId>.settings`）
 *
 * 设备侧 per-mod 设置（[ModSettingsStore]，写盘由设置页触发）是包的第 6 个成员形状。它
 * **不在导入写序里**（导入只写四件套），因此 [memberNames] 仍只列导入产物；「删除整包」的
 * 名单 = [memberNames] + [ModSettingsStore.memberName]，由 [ModManageUninstall] 拼装：
 *
 * 1. **显式卸载** ⇒ 设置一起删净（玩家点的是「卸载」，留文件就是脏）；
 * 2. **孤儿 GC** ⇒ 仅当该包已被 [orphanishReason] 判为孤儿（半成品 / 源失联）时连带删；
 *    **只有设置、没有任何包文件 / 只有资源目录**时保守保留（作者先摆设置再导入是合法顺序，
 *    与「只有资源目录」同理）；
 * 3. **重导 / 升级** ⇒ 保留（BU 波显式语义「设置与数据保留」；导入链不经过本对象删除面）。
 *
 * ## 旁挂媒体暂存残留（`<packageId>.media.tmp-<数字>`，ADR-014，M1-002 补判据）
 *
 * [ModImportMediaAssets] 会在 `mods/rt2/` 留**两类同名字形**的过程残留，名字都是
 * `<packageId>.media.tmp-<数字>`：
 *
 * - **暂存树（目录）**：④″ stage 写出的临时树，commit 的 finally 会整树删掉；进程在
 *   stage 与 commit 之间被杀就留下（此前没有任何清理面认识这个形状 = M1-002）；
 * - **清单原子写临时件（文件）**：commit 末尾写 `<pkg>.media` 清单时先写
 *   `<pkg>.media.tmp-<nano>` 再 ATOMIC_MOVE，进程在这个窗口内被杀留下同名**文件**。
 *
 * 判据与资源面残留同款保守，只差「在用面」的形状：媒体面的正式件是 `<pkg>.media`
 * **清单文件**（不是目录），清单在场 ⇒ 保守保留残留（宁可留垃圾也不删在途替换面）；
 * 不在场才允许在 24h 时间窗外清理。已知保守缺口：`System.nanoTime()` 为负时序号带
 * `-`、过不了「全数字」闸，那条残留会留在盘上（与资源面同口径的宁漏删不误删）。
 *
 * 正式 `.media` 清单本身仍不是成员面（见「旁挂媒体面」段），回收归 [ModMediaReclaim]；
 * 本段只管「过程残留」这一层。
 *
 * ## 设置过程残留（`.<packageId>.settings.tmp`）
 *
 * [ModSettingsStore.write] 的原子写临时文件。正常收尾会被 rename 或失败清理掉；进程写到一半
 * 被杀会留在 `mods/rt2/` 下。清理实现落在 [ModSettingsStore.gcTempResidues]（名字拼装与形状
 * 解析同源，本对象在包级判据之后按序转交），判据与资源面残留同款保守：
 *
 * 1. **形状精确**：必须是 `.<合法 kojo- packageId>.settings.tmp` 的**文件**（前缀/后缀与
 *    [ModSettingsStore.tempMemberName] 同源）；其它名字、非 `kojo-` 前缀、目录一律不碰；
 * 2. **活配置永不因残留清理而丢**：同一 packageId 的正式 `.settings` 文件仍存在时保守保留
 *    残留（宁可留垃圾，也不在替换写窗口里动活配置面）；
 * 3. 删除 + `MOD_PKG_GC` 留痕；删除失败不抛、原地保留并留 WARN。
 *
 * 设置残留与资源/媒体面残留共用 [RESIDUE_GC_MIN_AGE_MILLIS] 保守时间窗（见 [gcStagingResidues]）。
 *
 * ## 覆盖侧车与随包模板目录（ERA-W2 新增成员形状）
 *
 * ERA-A2（`mods/rt2/<packageId>.chara/`，导入链 ⑤‴）与 ERA-A3
 * （`mods/rt2/<packageId>.assetmap`，导入链 ⑤⁗）各给包加了一件侧车产物，两件都纳入包成员
 * 全集（枚举 / 卸载 / GC）。判活规则**按产物类型区分**（宁保守勿激进）：
 *
 * - **`.assetmap`（文件侧车）**：与 `.sources` 同口径的导入链派生件——线格式
 *   （`ERATW-ASSETMAP/1`）只有导入链写得出，不构成「作者先手摆」的合法顺序，故包缺容器或
 *   manifest 判半成品时**连带删**（孤儿 assetmap 无消费可能：消费面按 packageId 读，包没
 *   manifest 就装配不出来）；四件套在 ⇒ 跟随保留。它不参与半成品判定本身（判据只看容器与
 *   manifest），老包缺失零行为差。
 * - **`.chara/`（目录侧车）**：与 `.assets/` 同判据——包级判活跟随（完整包保留、半成品
 *   连树删）；**「只有 chara 目录、没有任何文件成员」⇒ 保守保留**（作者先摆模板目录再导入
 *   是合法顺序，与资源目录同判据）。`.chara/` 目录内只有模板 CSV，**目录自身无 manifest 是
 *   常态而非半成品**——判据永不看目录内容，半成品判定只在包级（容器/manifest 是否在场）。
 * - 两者都不是过程残留形态（不走 [gcStagingResidues] 的 tmp/bak 残留清理）。
 *
 * ## 孤儿 GC 判据（组长裁定 3，冻结）
 *
 * 1. 只处理包名以 [PACKAGE_ID_PREFIX] 开头的导入产物；内置资产（`eratw.*`）与玩家手放件
 *    （非该前缀）永不删除；
 * 2. `.erbm` / `.manifest` 任一缺失 = 半成品（导入两次写之间崩溃的残留）。半成品会让
 *    `ModChainAssembly.assembleChain` 的单向配对整包 fail-closed ⇒ 启动砖化，故必须在
 *    容器被读取之前清掉；**缺指纹侧车不算半成品**（老包迁移期必须留下）；
 * 3. `.sources` 存在且名单非空、但列出的留档源**一个都不在** `mods/` = 源已失联，整套删除；
 *    `.sources` 缺失 / 空名单 / 读不出 = 保守不删（老包与手放件没有 provenance）；
 * 4. 只读 provenance、只删本包成员：GC 不枚举 `mods/` 的源文件，也不碰其它包。
 *
 * ## 旁挂媒体面（`<packageId>.media`，ADR-014 S5）
 *
 * `.media` 清单不在本对象成员面里（不参与 [memberNames] / [orphanishReason]，见
 * [ModImportMediaAssets] 头注），但孤儿 GC 在包级判据**之后**转交 [ModMediaReclaim.gcOrphans]
 * 独立判一次：该包在 rt2 已无任何成员时，按清单回收两树文件与空目录；完整包 /
 * 包级没删成的半成品 / 同 `<dir>` 仍被别的包声明的，一律保留并只留取证行。
 * 其**过程残留**（`<pkg>.media.tmp-<数字>`）归 [gcStagingResidues]，见「旁挂媒体暂存残留」段。
 */
object ModPackageInventory {

    /** 导入链产物包名前缀（[ModImportController.packageIdFor] 的唯一常量来源）。 */
    const val PACKAGE_ID_PREFIX: String = "kojo-"

    /** ERB 模块容器扩展名（erbcc `compile-module` 产物，装载收集面同源）。 */
    const val MODULE_EXTENSION: String = ".erbm"

    /** RT2 manifest 扩展名（与容器同 packageId 单向配对）。 */
    const val MANIFEST_EXTENSION: String = ".manifest"

    /** provenance 侧车扩展名（RT2 MODGC 新增，只记留档源名）。 */
    const val SOURCES_EXTENSION: String = ".sources"

    /**
     * 内容指纹侧车扩展名（Lane O）：`.sources` 同源的摘要集合，见 [ModSourceFingerprint]。
     *
     * 可选件：缺失 ⇒ 判据退回 mtime（老包迁移期），GC 不当半成品。
     */
    const val FINGERPRINT_EXTENSION: String = ".sources.sha256"

    /**
     * 资源目录后缀：`mods/rt2/<packageId>.assets/`（目录，不是文件成员）。
     *
     * 必须与 `core-vm` 侧 `ModResourceStore.ASSET_DIR_SUFFIX` 同值（运行期按同一路径读资源）；
     * 本对象只把它当**包归属判据**用（卸载/GC 一起清），不读目录内容。
     */
    const val ASSET_DIR_SUFFIX: String = ".assets"

    /**
     * 随包角色模板目录后缀（ERA-A2 收录面）：`mods/rt2/<packageId>.chara/`（目录，不是文件
     * 成员，目录内是 ERB/CSV 模板件）。
     *
     * 写方唯一 = 导入链 ⑤‴（`ModImportController` 的 `CHARA_TEMPLATE_DIR_SUFFIX`，private
     * 不可直引）；本常量为**同值镜像**，由 [EraW2SidecarInventoryTest] 的源码哨兵钉住两侧
     * 字面量同值（任一侧改名而不同步 ⇒ 哨兵红）。与 `.assets` 同判据：本对象只把它当包
     * 归属判据用（卸载/GC 整树一起清），不读目录内容。
     */
    const val CHARA_DIR_SUFFIX: String = ".chara"

    /**
     * 覆盖侧车扩展名（ERA-A3 二期 A）：`mods/rt2/<packageId>.assetmap`（单文件侧车）。
     *
     * 单源 = `core-vm` 的 [ModDx3AssetMap.SIDECAR_EXTENSION]（public，直引不镜像）；导入链
     * ⑤⁗ 无论有无 overrides 都落盘（无声明 = 空表，fail-closed 覆盖旧表）。
     */
    val ASSETMAP_EXTENSION: String = ModDx3AssetMap.SIDECAR_EXTENSION

    /**
     * 代码包运行期身份侧车扩展名（ADM 裁决：代码包身份一致化）：`mods/rt2/<diskPkg>.codeid`。
     *
     * 单文件、两行（见 [ModCodeIdentity]）；**只有带 `code.mod` 的包**才写，无代码产物的包
     * 在导入收口时显式清掉旧侧车。它不属于四件套写序，是 ⑤‴ 代码产物 commit 的伴生件，
     * 故列在 [memberNames] 末尾（写序即名单序）。
     */
    const val CODE_ID_EXTENSION: String = ".codeid"

    /**
     * 与包成员无关的**保留目录名**：`code/`（dex 产物树，归 [ModCodeStore]/[ModCodeReclaim]）
     * 永不进 [membersOf] 的成员枚举。
     *
     * 为什么不误判：`code/` 不是 `<kojo-…>.assets` / `.chara` 形状，本来也过不了成员枚举的
     * 前缀+后缀闸；这里再显式列一层是为了把「保留目录」写成契约而不是靠命名巧合——将来若给
     * 目录成员加新的后缀匹配，`code/` 不会被顺手当成某个包目录成员（一旦当成，GC 会按
     * packageId="code" 找不到包级成员而把整棵产物树删掉）。方向是单向的：本集合只影响成员枚举，
     * 代码产物的回收全部由 [ModCodeReclaim] 独立裁定。
     */
    private val RESERVED_DIR_NAMES: Set<String> = setOf(MOD_CODE_DIR_NAME)

    /**
     * 过程残留形状全集：资源面暂存/备份目录（`.assets.tmp-` / `.assets.bak-`）与旁挂媒体
     * （`.media.tmp-`，目录与文件两种形态都收）。
     *
     * 各面「在用面」判据不同：资源面的正式件是 `<pkg>.assets/` 目录，媒体面的正式件是
     * `<pkg>.media` 清单文件——残留只在对应正式件**不在场**时才允许清（在用面永不删）。
     * 后缀与写侧同源（[ModImportAssets] 的临时/备份后缀、[ModImportMediaAssets] 的清单后缀），
     * 拼装而非复制字面量，避免 GC 与落盘链各自改名后互不认识。
     *
     * [ResidueFace.dirOnly] 只挡「写侧从来写不出的形态」：资源面暂存/备份只会是目录，同形
     * **文件**必是玩家手放件，一律不碰；媒体面的清单原子写临时件本来就是文件，目录（暂存树）
     * 与文件（写清单临时件）都是写侧产物，两种形态都收。
     */
    private val residueFaces: List<ResidueFace> = listOf(
        ResidueFace(
            ASSET_DIR_SUFFIX + ModImportAssets.TEMP_SUFFIX,
            "资源面",
            dirOnly = true,
            reason = "导入/替换中途退出残留，无同名在用资源面",
        ) { rt2Dir, packageId -> File(rt2Dir, packageId + ASSET_DIR_SUFFIX).isDirectory },
        ResidueFace(
            ASSET_DIR_SUFFIX + ModImportAssets.BACKUP_SUFFIX,
            "资源面",
            dirOnly = true,
            reason = "导入/替换中途退出残留，无同名在用资源面",
        ) { rt2Dir, packageId -> File(rt2Dir, packageId + ASSET_DIR_SUFFIX).isDirectory },
        ResidueFace(
            ModImportMediaAssets.MEDIA_MANIFEST_SUFFIX + ModImportAssets.TEMP_SUFFIX,
            "媒体面",
            dirOnly = false,
            reason = "导入中途退出残留，无在用媒体清单",
        ) { rt2Dir, packageId ->
            File(rt2Dir, packageId + ModImportMediaAssets.MEDIA_MANIFEST_SUFFIX).isFile
        },
    )

    /**
     * 过程残留清理的**保守时间窗**：`lastModified` 早于 24 小时才允许删。
     *
     * 时间窗是为了不误删**在途** tmp，不是为了删得更干净：设置原子写 / 导入 staging 与启动 GC
     * 在时间上可能重叠（tmp 已写出、rename 尚未发生），旧判据「同名正式件不存在就删」会在这一
     * 窗口里把一次本该成功的写清掉。两个清理面（[ModSettingsStore.gcTempResidues] 与本对象的
     * [gcStagingResidues]）共用这一个常量，不许各自写一份。
     */
    const val RESIDUE_GC_MIN_AGE_MILLIS: Long = 24L * 60L * 60L * 1000L

    /**
     * 残留文件/目录是否已过 [RESIDUE_GC_MIN_AGE_MILLIS] 时间窗（⇒ 允许清理）。
     *
     * 写成函数类型属性而不是普通成员函数，是为了不把本对象的成员函数数顶过 detekt 的
     * `TooManyFunctions` 阈值（本类已 10 个，阈值 11），不是为了避免可测性：它照样是
     * `internal`、单测直接调用，两处清理面共用同一份判据。
     *
     * `lastModified` 不可读（返回 `<= 0`，含 0 = 取不到 / 文件已不在）⇒ **保守保留**；
     * 未来时间戳（时钟回拨 / 跨设备拷贝）同样按「不够旧」保留。窗内 = 可能是在途写，保留。
     */
    internal val residuePastGcWindow: (File, Long) -> Boolean = { file, nowMillis ->
        val modified = file.lastModified()
        modified > 0L && nowMillis - modified >= RESIDUE_GC_MIN_AGE_MILLIS
    }

    /**
     * 一个包在 `mods/rt2/` 下**由导入链写出的全部文件产物**（写序即名单序）。
     *
     * 顺序 = 写入序：provenance → 内容指纹 → manifest → 容器 → 覆盖侧车 → 代码身份侧车
     * （指纹紧跟 provenance：两件都是「源名单」派生物，写在产物之前；容器最后写，缺容器即
     * 半成品；覆盖侧车 ⑤⁗ 在四件套读回校验之后落盘，`ModImportController` 主链与纯资源包链
     * 都写；代码身份侧车在 ⑤‴ 代码产物 commit 时随产物落盘，故排最后）。
     *
     * 覆盖侧车与代码身份侧车都是**可选件**：A3 之前导入的老包没有前者、无 `code.mod` 的包
     * 没有后者 ⇒ 卸载/GC 按名单逐个 `exists` 跳过，缺失零行为差；它们也不参与半成品判定
     * （判据只看容器与 manifest，见 [orphanishReason]）。
     *
     * 目录成员（`<packageId>.assets/`、`<packageId>.chara/`）与设置侧车
     * [ModSettingsStore.SETTINGS_EXTENSION] **不在这里**：目录成员由各删除面按后缀常量整树
     * 删（本对象 [ASSET_DIR_SUFFIX] / [CHARA_DIR_SUFFIX] 是唯一后缀来源）；设置侧车由设置页
     * 独立写、不参与导入写序（导入后不一定存在）。「删除整包」语义 = 本名单 + 目录成员 +
     * [ModSettingsStore.memberName]，由 [ModManageUninstall] 按此拼装（本对象 GC 只删
     * **实际在场**的成员，自然含设置侧车与两类目录）。
     */
    fun memberNames(packageId: String): List<String> = listOf(
        packageId + SOURCES_EXTENSION,
        packageId + FINGERPRINT_EXTENSION,
        packageId + MANIFEST_EXTENSION,
        packageId + MODULE_EXTENSION,
        packageId + ASSETMAP_EXTENSION,
        packageId + CODE_ID_EXTENSION,
    )

    /**
     * provenance 字节：排序后的留档源名逐行 UTF-8 + 末尾换行。
     *
     * 排序口径与 [ModImportController.packageIdFor] 同源（`ModStore.compareNames`），
     * 让「包身份」「写盘名单」与「GC 判定」三处看到的顺序逐字一致。
     */
    fun sourcesBytes(diskNames: List<String>): ByteArray =
        (diskNames.sortedWith { a, b -> ModStore.compareNames(a, b) }
            .joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)

    /**
     * 读 provenance 名单；文件缺失 / 读失败 / 空名单 ⇒ null（= GC 保守不删）。
     *
     * 含路径分隔符或普通文件名不合法的行一律丢弃：provenance 只可能由本导入链写出，
     * 出现越界名说明文件被改过，宁可当「读不出」保守保留，也不拿它做删除依据。
     */
    fun storedSourceNames(rt2Dir: File, packageId: String): List<String>? {
        val file = File(rt2Dir, packageId + SOURCES_EXTENSION)
        return if (!file.isFile) {
            null
        } else {
            runCatching { file.readLines(Charsets.UTF_8) }.getOrNull()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !it.contains('/') && !it.contains('\\') }
                ?.ifEmpty { null }
        }
    }

    /**
     * `mods/rt2/` 孤儿包 GC：在容器被收集/读取**之前**调用（生产 = [ModChainAssembly.assemble]
     * 开态入口）。关态调用方不得调用本函数（零接触纪律在调用点）。
     *
     * 全程 runCatching，不抛、不改装配结局；每个删除动作经 [SaveDiagnostics] 留痕
     * （`MOD_PKG_GC`，与存档侧 `SIDECAR_GC` 同一取证面）。
     */
    fun gcOrphans(rt2Dir: File, modsDir: File, nowMillis: Long = System.currentTimeMillis()) {
        if (!rt2Dir.isDirectory) return
        // 成员枚举 = 文件成员（四件套/指纹侧车/覆盖侧车/设置侧车）+ 目录成员
        // （<packageId>.assets/ 与 <packageId>.chara/ 整棵树）。
        val entries = rt2Dir.listFiles().orEmpty().toList()
        val byPackage = membersOf(entries).groupBy({ it.first }, { it.second })
        val settingsNameOf = { packageId: String -> ModSettingsStore.memberName(packageId) }
        for ((packageId, packageMembers) in byPackage.entries.sortedBy { it.key }) {
            val names = packageMembers.filter { it.isFile }.mapTo(HashSet()) { it.name }
            // 只有目录成员（.assets/ / .chara/）或只有设置侧车、没有任何导入包文件：不参与
            // 孤儿判据（作者可能先摆资源或模板再导入），保守保留；判据仍只看既有
            // orphanishReason（半成品 / 源失联）。目录成员不进 names（只收文件）⇒ 不参与判定。
            val hasArtifactMember = names.any { it != settingsNameOf(packageId) }
            val reason = if (!hasArtifactMember) null else orphanishReason(packageId, names, rt2Dir, modsDir)
            if (reason != null) deleteMembers(packageMembers, reason)
        }
        // 包级判据之后再清 dex 代码产物面（ADR-013 D4 / S6）：包级成员一点不剩才回收
        // `code/<packageId>/` 树；完整包 / 包级没删成的半成品 / 同 sha 仍被活包引用的一律保留，
        // 判据见 [ModCodeReclaim]（分支顺序与 [ModMediaReclaim.reclaimOne] 同一纪律）。
        // `code/` 根本身永不删，且它不进 [membersOf] 成员面（见 RESERVED_DIR_NAMES）。
        ModCodeReclaim.gcOrphans(rt2Dir)
        // 双身份收口（task-196）：信任标记按运行期身份命名，磁盘包名 ≠ 运行期身份时上面两条删除
        // 路径（包级成员 / code 目录）都不碰 `<runtime>.trusted`。包已一点不剩或侧车没了，那份标记
        // 就永远没人认领——同一代码包重装后会静默继承旧信任（安全面）。判据只看盘面当前认领关系：
        // 存活代码包经 `.codeid` 解析出的运行期身份才算主人；坏侧车/不可读整轮保守跳过。
        // 放在 code 产物回收之后：残留包在这一步已定，认领集才是「盘上真实还活着的包」。
        gcOrphanTrustMarkers(rt2Dir)
        // 包级判据之后再清「确定无主」的旁挂媒体面（ADR-014 S5）：`<pkg>.media` 不在本对象成员面里，
        // 由 [ModMediaReclaim.gcOrphans] 独立判：包成员已一点不剩才回收清单内两树文件与空目录；
        // 完整包 / 包级没删成的半成品一律保留，同 <dir> 仍被别的包声明也保留（判据见其 KDoc）。
        ModMediaReclaim.gcOrphans(rt2Dir, modsDir)
        // 包级判据之后再清过程残留：上面若刚删掉某包的活资源面，同名残留这一轮就该跟着走。
        // `nowMillis` 只为测试注入时钟；生产一律取默认值（同一轮 GC 两个清理面看同一把钟）。
        gcStagingResidues(rt2Dir, entries, nowMillis)
        // 设置残留的清理归属设置存储（名字拼装/形状解析同源），这里只按序转交。
        ModSettingsStore.gcTempResidues(rt2Dir, entries, nowMillis)
    }

    /**
     * 资源/媒体面过程残留清理：`<packageId>.assets.tmp-<nano>` / `.bak-<nano>` 目录与
     * `<packageId>.media.tmp-<数字>`（目录 = 旁挂媒体暂存树、文件 = 清单原子写临时件）。
     *
     * 只在对应 packageId 的「在用面」**不在场**时删除：资源面 = `<pkg>.assets/` 目录不在、
     * 媒体面 = `<pkg>.media` 清单文件不在；在用面在场 ⇒ 残留当「可能仍被替换流程引用」
     * 保守保留（宁可留垃圾也不删活资源）。删除失败不抛（GC 全程不抛），原地保留并留 WARN。
     *
     * 另一道闸是 [RESIDUE_GC_MIN_AGE_MILLIS] 保守时间窗：`lastModified` 早于 24 小时才删，
     * 时间不可读 ⇒ 保留。**时间窗是为了不误删在途 tmp，不是为了删得更干净**——在途 staging
     * 恰好撞上启动 GC 时，窗内残留会被保留，让那次写还能正常收尾。
     */
    private fun gcStagingResidues(rt2Dir: File, entries: List<File>, nowMillis: Long) {
        // 先把「形状合法 + 在用面不在场 + 已过时间窗」三关都过的残留筛出来，再逐个删
        // （循环体里不跳步）。
        val residues = entries
            .mapNotNull { entry ->
                packageIdOfResidue(entry.name)?.let { (packageId, face) -> Triple(entry, packageId, face) }
            }
            .filter { (entry, _, face) -> !face.dirOnly || entry.isDirectory }
            .filter { (entry, packageId, face) -> !face.inUseFace(rt2Dir, packageId) }
            .filter { (entry, _, _) -> residuePastGcWindow(entry, nowMillis) }
        for ((entry, _, face) in residues) {
            val isDirectory = entry.isDirectory
            val noun = if (isDirectory) "目录" else "文件"
            val removed = runCatching {
                if (isDirectory) entry.deleteRecursively() else entry.delete()
            }.getOrDefault(false)
            SaveDiagnostics.note(
                "MOD_PKG_GC",
                entry.name,
                if (removed) {
                    "${face.label}残留${noun}已删除（${face.reason}）"
                } else {
                    "${face.label}残留${noun}删除失败，原地保留（${face.reason}）"
                },
            )
        }
    }

    /**
     * 残留条目名 → 所属 packageId 及其残留面；不是任何在册形状（`<合法 packageId>.assets.tmp-<数字>`
     * / `.assets.bak-<数字>` / `.media.tmp-<数字>`）⇒ null。
     *
     * 序号必须非空且全为数字；其余名字（含玩家手放件、非 `kojo-` 前缀、序号非数字/为空）一律不碰。
     * 用 `lastIndexOf` 而不是前缀匹配：packageId = `kojo-` + sha 前 8 位十六进制，不含点号，
     * 同一名字里 marker 最多命中一次，不会把 `a.assets.tmp-b.media.tmp-1` 这种串错误归面。
     */
    private fun packageIdOfResidue(name: String): Pair<String, ResidueFace>? =
        residueFaces.firstNotNullOfOrNull { face ->
            val at = name.lastIndexOf(face.marker)
            if (at <= 0) return@firstNotNullOfOrNull null
            val packageId = name.substring(0, at)
            val serial = name.substring(at + face.marker.length)
            packageId.takeIf { candidate ->
                serial.isNotEmpty() &&
                    serial.all { char -> char.isDigit() } &&
                    candidate.length > PACKAGE_ID_PREFIX.length &&
                    candidate.startsWith(PACKAGE_ID_PREFIX)
            }?.let { it to face }
        }

    /**
     * 成员枚举：文件成员（四件套 / 指纹侧车 / 覆盖侧车 / 设置侧车）+ 目录成员
     * （`<packageId>.assets` / `<packageId>.chara` 整棵树）。
     *
     * 其余条目（内置 `eratw.*`、玩家手放件、非 kojo- 前缀目录、[RESERVED_DIR_NAMES] 里的
     * 保留目录如 `code/`）一律不参与，也绝不单独当孤儿件。
     */
    private fun membersOf(entries: List<File>): List<Pair<String, File>> = entries.mapNotNull { entry ->
        // 保留目录显式排除：`code/` 是 dex 产物树，不是包成员（见 RESERVED_DIR_NAMES 的 KDoc）。
        if (entry.isDirectory && entry.name in RESERVED_DIR_NAMES) return@mapNotNull null
        val packageId = if (entry.isDirectory) {
            // 目录成员两种形状：`<kojo-…>.assets`（资源目录）与 `<kojo-…>.chara`（随包角色
            // 模板目录）：去后缀后仍须过前缀/长度闸，内置与手放目录一律不碰。
            val suffix = when {
                entry.name.endsWith(ASSET_DIR_SUFFIX) -> ASSET_DIR_SUFFIX
                entry.name.endsWith(CHARA_DIR_SUFFIX) -> CHARA_DIR_SUFFIX
                else -> null
            }
            suffix?.let {
                entry.name.removeSuffix(it).takeIf { candidate ->
                    candidate.length > PACKAGE_ID_PREFIX.length &&
                        candidate.startsWith(PACKAGE_ID_PREFIX)
                }
            }
        } else {
            packageIdOf(entry.name)
        }
        packageId?.let { it to entry }
    }

    /**
     * 删除一组同包成员：文件 `delete()`、目录成员（`.assets/` 与 `.chara/`）整树
     * `deleteRecursively()`。
     *
     * 逐项 runCatching + [SaveDiagnostics] 留痕（与单文件删除同口径，不因一个成员失败中断整包清理）。
     */
    private fun deleteMembers(members: List<File>, reason: String) {
        for (member in members) {
            val removed = runCatching {
                if (member.isDirectory) member.deleteRecursively() else member.delete()
            }.getOrDefault(false)
            SaveDiagnostics.note(
                "MOD_PKG_GC",
                member.name,
                if (removed) "孤儿包成员已删除（$reason）" else "孤儿包成员删除失败，原地保留（$reason）",
            )
        }
    }

    /**
     * 孤儿判定：返回 null = 保留；返回字符串 = 删除理由（进取证面）。
     *
     * **只删半成品**（缺容器或 manifest —— 它是启动砖化的直接来源，不删整链会 fail-closed）。
     *
     * 历史上还有第二条「源失联」判据（`.sources` 里记的留档源在 `mods/` 一个都不在 ⇒ 整套删）。
     * 该判据已**整体撤销**，理由（2026-11-18 误删风险审计，`.sources` 只能证明「作者留过哪些源」，
     * 证明不了「玩家还要不要这个包」）：
     *
     * - 玩家把源改名成 `xxx.ERB.bak` / 加 `!` 前缀做「临时停用」（社区常见做法）⇒ 源失联但包完好可运行；
     * - 玩家把源整理进 `mods/sub/` 子目录 ⇒ 旧判据只查一层 `isFile`，同样判失联；
     * - 源同名但是**另一个包**的 / 源被解压成同名目录 ⇒ 判据两类误判方向都出现过；
     * - 失联包**不影响启动**（容器与 manifest 都在，照常装载），删它没有任何正确性收益；
     * - 而删除是 `deleteRecursively()` 不可逆，且本函数只写 `SaveDiagnostics`（玩家零感知）——
     *   一次误删就同时吃掉 `.erbm`/`.manifest`/`.sources`/`.sources.sha256`/`.settings`/`.assets/`。
     *
     * 结论：能用的包绝不自动删；要清理请走**显式卸载**（`ModManageUninstall`，那是玩家的删除意图）。
     * 只保留半成品判据后，`rt2Dir`/`modsDir` 两个参数不参与删除判定，但作为取证面与载体判据签名保留
     * （诊断行要说出「哪个目录下」）。
     *
     * MODDX3-A3 载体例外：纯资源包是**合法的 manifest-only 形态**（有 manifest + 空 `.sources`
     * + `<pkg>.assets/`，无 `.erbm`）——它本就没有源可编译出容器，不构成「缺容器半成品」。
     * 先过 [isManifestOnlyCarrier]：命中则与完整包同口径保留；否则照旧把「有 manifest 无容器」的
     * 普通导入残留当半成品清掉（启动砖化来源）。此例外只放宽「缺容器」一支，不改变源失联已撤销的口径。
     */
    private fun orphanishReason(
        packageId: String,
        names: Set<String>,
        rt2Dir: File,
        modsDir: File,
    ): String? {
        val hasModule = (packageId + MODULE_EXTENSION) in names
        val hasManifest = (packageId + MANIFEST_EXTENSION) in names
        // MODDX3-A3 载体例外：manifest-only 纯资源包无 `.erbm` 是合法形态，不当半成品删。
        val carrier = !hasModule && hasManifest && isManifestOnlyCarrier(packageId, rt2Dir)
        if ((hasModule && hasManifest) || carrier) {
            // 完整包 / 纯资源载体：一律保留（源失联不再是删除依据，见 KDoc）。
            // 只在「源确实失联」这一罕见情形留一条取证行；正常包不写（否则每次启动每包一行噪声）。
            val sources = storedSourceNames(rt2Dir, packageId)
            if (sources != null && sources.none { File(modsDir, it).isFile }) {
                SaveDiagnostics.note(
                    "MOD_PKG_GC",
                    packageId,
                    "完整包保留：源 ${sources.size} 个留档源均不在 mods/（源失联不构成删除依据）",
                )
            }
            return null
        }
        return "半成品：缺" + when {
            !hasModule && !hasManifest -> "容器与 manifest"
            !hasModule -> "容器"
            else -> "manifest"
        }
    }

    /**
     * 纯资源包载体形态判据（MODDX3-A3）：manifest 在场、`.erbm` 缺、`.sources` 是空名单、
     * `<pkg>.assets/` 目录在场。四者同真 = 合法载体，不是半成品；其余组合（含任一文件被手动
     * 删掉）一律不认，保守退回半成品判定。空名单用 [storedSourceNames] 的 null 口径（读不出 /
     * 空表都返回 null），但额外要求 `.sources` 文件在场，避免「压根没写 provenance」的残留
     * 也命中载体例外。
     */
    internal fun isManifestOnlyCarrier(packageId: String, rt2Dir: File): Boolean {
        val sourcesFile = File(rt2Dir, packageId + SOURCES_EXTENSION)
        return sourcesFile.isFile &&
            storedSourceNames(rt2Dir, packageId) == null &&
            File(rt2Dir, packageId + ASSET_DIR_SUFFIX).isDirectory
    }

    /**
     * 文件名 → 所属 packageId；不是本对象认识的导入产物形状 ⇒ null。
     *
     * 只有七种成员后缀、且包名以 [PACKAGE_ID_PREFIX] 开头才算：`.erbm` / `.manifest` /
     * `.sources` / `.sources.sha256` / `.settings` / `.assetmap` / `.codeid` 之外的任何文件
     * （含玩家手放件与内置 `eratw.*` 资产）一律不参与 GC。指纹后缀比 provenance 长，放前面判
     * （避免将来前缀互吞时误归类）；后加的两个侧车后缀与既有后缀无前后缀包含关系，追加在尾。
     *
     * `.codeid` 纳进本表不是「误判成孤儿」而是**半成品清理**：代码身份侧车单独存在时没有
     * 容器/manifest 可归属（导入崩溃残留），归 [orphanishReason] 的「半成品」一支一并清掉；
     * 完整包在场时它与其他成员同生同死，不会单独删。
     */
    private fun packageIdOf(name: String): String? {
        val extensions = listOf(
            FINGERPRINT_EXTENSION,
            MODULE_EXTENSION,
            MANIFEST_EXTENSION,
            SOURCES_EXTENSION,
            ModSettingsStore.SETTINGS_EXTENSION,
            ASSETMAP_EXTENSION,
            CODE_ID_EXTENSION,
        )
        for (extension in extensions) {
            if (!name.endsWith(extension)) continue
            val packageId = name.removeSuffix(extension)
            if (packageId.length > PACKAGE_ID_PREFIX.length && packageId.startsWith(PACKAGE_ID_PREFIX)) {
                return packageId
            }
        }
        return null
    }
}

/**
 * 一个过程残留形状的判据三件套：名字后缀 [marker]、取证面用语 [label] 与删除理由 [reason]、
 * 「在用面」判据 [inUseFace]（返回 true = 同包正式件在场，残留保守保留）。
 *
 * [dirOnly] 挡「写侧从来写不出的形态」：只可能是目录的面（资源暂存/备份）遇到同形文件时
 * 不碰——那必是玩家手放件；写侧本就产出文件形态的面（媒体清单原子写临时件）两种形态都收。
 *
 * 放文件级而不是对象成员：[ModPackageInventory] 的成员函数数已顶在 detekt `TooManyFunctions`
 * 阈值边上，这里只添数据不添函数。
 */
private class ResidueFace(
    val marker: String,
    val label: String,
    val dirOnly: Boolean,
    val reason: String,
    val inUseFace: (rt2Dir: File, packageId: String) -> Boolean,
)
