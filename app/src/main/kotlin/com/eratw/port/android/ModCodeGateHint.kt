package com.eratw.port.android

import java.io.File

/**
 * 「第三方代码包装了但不生效」的提示种类（ADR-013 路 B 代码闸的玩家可见面）。
 *
 * 叙事顺序即优先级顺序：盘上多版本残留最重（重新导入才能修），身份损坏次之（同样只能重导），
 * 引擎太旧再次之（只能更新应用），总闸关再次之（一开就恢复），未信任最后（玩家自己确认才放行）。
 * 装载侧 [ModDexLoader.decide] 的裁决是「ABI → 拒载 → 总闸关 → 未信任」，提示不许把更重的状态
 * 降级成更轻的说明。
 *
 * 多版本排在身份损坏之前的理由（与装载侧裁决序不同，**刻意**）：两者修复动作相同（重新导入），
 * 先报哪个都不会把玩家引到错误动作；而多版本一旦残留会让装载端永久拒载（AmbiguousArtifact），
 * 是「升级后一直不生效」这条主诉的直接解释，故优先上屏。
 *
 * 「引擎太旧」排在身份损坏之后、总闸关之前的理由（task-195）：它是装载侧 ABI 闸给出的**确定
 * 事实**——只要缺口还在，改总闸、补信任都救不回来，必须先于这两项上屏（否则玩家去点开关、
 * 点信任，重启后照样不生效）；而身份损坏是多版本之后的盘面损坏，两者都要先「重新导入」，
 * 导入收尾会把 ABI 结论重算一遍，故按盘面取证先报损坏不会指错动作。多版本 / 损坏 / 引擎太旧
 * 各自的修法仍然成立，只是要等前一层清掉才轮到下一层（每清一层露出下一层）。
 */
enum class ModCodeGateHintKind {
    /** 盘上同一个包有多份代码产物版本：装载端拒绝猜版本，重新导入会自动只留最新的一份。 */
    MULTIPLE_VERSIONS,

    /** 代码身份侧车损坏：装载侧按拒载处理，玩家只能重新导入这个包。 */
    CODE_ID_CORRUPT,

    /** 包要求的引擎面高于本机：装载闸拒载，改开关 / 补信任都救不回来，只能更新应用。 */
    ENGINE_TOO_OLD,

    /** Mod 运行总闸关着：盘上有代码产物，但本局与下次装载都不会运行。 */
    GATE_OFF,

    /** 已装代码产物但玩家还没信任：装载侧按 NeedsTrust 跳过。 */
    UNTRUSTED,
}

/**
 * 一条代码闸提示：种类 + 所属包名（引擎太旧时另带版本缺口）。
 *
 * 纯数据，不带任何语言：句式由展示面各自的文案表拼（导入成功页 [ModImportCopy]、
 * 设置页 Mod 管理行 [SettingsStrings]），本层不做三语选择。
 */
data class ModCodeGateHint(
    val kind: ModCodeGateHintKind,
    val packageId: String,
    /** 仅 [ModCodeGateHintKind.ENGINE_TOO_OLD]：包要求 / 本机引擎的版本缺口；其它种类 null。 */
    val engineGap: EngineSurfaceGap? = null,
)

/**
 * 一个包在盘面上的代码态：有没有代码产物 + 为什么没生效。
 *
 * [hint] 为 null 表示「有代码产物且现在就会生效」（总闸开 + 已信任 + 身份完好）；
 * 整个对象为 null（见 [modCodePackState]）表示这个包压根没有代码产物，展示面整行不出
 * —— 普通口上包不该多出一行「代码」读数。
 */
internal data class ModCodePackState(val packageId: String, val hint: ModCodeGateHint?)

/**
 * 代码闸裁决的盘面事实（不含包名与语言）。
 *
 * 聚成值对象而不是散成五个布尔入参：事实来源只有 [modCodePackState]（设置页读盘）与
 * [codeGateHintFor]（导入收尾）两处，调用点读起来是「盘面是什么样」而非一串无名开关；
 * 也避开 detekt 的 LongParameterList（[modCodeGateHint] 只剩两个入参）。
 */
internal data class ModCodeGateFacts(
    /** 盘上是否有该包的代码产物（false = 普通口上包，整行不出）。 */
    val hasArtifacts: Boolean,
    /** 该包目录下是否有多份产物（装载端据此按多版本歧义拒载）。 */
    val multipleVersions: Boolean,
    /** 身份侧车是否损坏（[ModCodeIdentity.read] 折成 [CodeIdSidecar.Corrupt]）。 */
    val identityCorrupt: Boolean,
    /** 运行总闸是否开着（关闭 = 本机任何第三方代码都不跑）。 */
    val runtimeGateEnabled: Boolean,
    /** 玩家是否已信任该包。 */
    val trusted: Boolean,
    /** 最近一次装载是否因「引擎太旧」被拒（[ModCodeLoadReport.engineGapFor] 的读数；无结论 = null）。 */
    val engineTooOld: EngineSurfaceGap? = null,
)

/**
 * 六态矩阵 + 无代码零提示（纯函数，唯一裁决点）。
 *
 * 判据顺序：
 * 1. [ModCodeGateFacts.hasArtifacts] 为 false ⇒ null（普通口上包零提示，不制造「代码」噪音）；
 * 2. [ModCodeGateFacts.multipleVersions] ⇒ [ModCodeGateHintKind.MULTIPLE_VERSIONS]（盘上多份
 *    产物 = 装载端 AmbiguousArtifact 永久拒载：改总闸 / 补信任都救不回来，重导才会清成一份）；
 * 3. [ModCodeGateFacts.identityCorrupt] ⇒ [ModCodeGateHintKind.CODE_ID_CORRUPT]（同样只能重新导入）；
 * 4. [ModCodeGateFacts.engineTooOld] 非 null ⇒ [ModCodeGateHintKind.ENGINE_TOO_OLD]（装载侧 ABI 闸
 *    的确定结论：改总闸 / 补信任都没用，只能更新应用）；
 * 5. [ModCodeGateFacts.runtimeGateEnabled] 为 false ⇒ [ModCodeGateHintKind.GATE_OFF]（总闸关时
 *    连信任都谈不上，先让玩家看到最粗的那道闸）；
 * 6. [ModCodeGateFacts.trusted] 为 false ⇒ [ModCodeGateHintKind.UNTRUSTED]；
 * 7. 否则 null（已生效）。
 *
 * 优先级「多版本 > 损坏 > 引擎太旧 > 总闸关 > 未信任」是**承重**的：装载侧 [ModDexLoader.decide]
 * 先判 ABI 再判总闸、最后判信任，提示若反过来就会指错修复动作（让玩家去点信任 / 开总闸，而
 * 这些动作在 ABI 缺口还在时都白做）。多版本与损坏的修复动作同为「重新导入」，故 2/3 谁先报都
 * 不会指错；把多版本放前面，是因为它是「升级后一直不生效」这条主诉的直接原因，而损坏只在
 * 侧车被外部改坏时出现。
 */
internal fun modCodeGateHint(packageId: String, facts: ModCodeGateFacts): ModCodeGateHint? {
    val kind = when {
        !facts.hasArtifacts -> null
        facts.multipleVersions -> ModCodeGateHintKind.MULTIPLE_VERSIONS
        facts.identityCorrupt -> ModCodeGateHintKind.CODE_ID_CORRUPT
        facts.engineTooOld != null -> ModCodeGateHintKind.ENGINE_TOO_OLD
        !facts.runtimeGateEnabled -> ModCodeGateHintKind.GATE_OFF
        !facts.trusted -> ModCodeGateHintKind.UNTRUSTED
        else -> null
    } ?: return null
    return ModCodeGateHint(
        kind = kind,
        packageId = packageId,
        engineGap = facts.engineTooOld.takeIf { kind == ModCodeGateHintKind.ENGINE_TOO_OLD },
    )
}

/**
 * 从盘面读一个包的代码态（设置页每行 / 导入成功页共用）。
 *
 * 返回 null = 该包没有代码产物（`code/<包名>/` 下没有合法产物）⇒ 展示面不出代码行，
 * 与普通口上包同一形态。读盘失败一律按「没有代码产物」收敛（宁可少一行读数，不让
 * 设置页因为一个坏目录崩掉）；包名形状非法同样折成 null（[ModCodeStore.list] 的形状闸
 * 不向上抛）。
 *
 * 判据与装载侧同源：产物表 [ModCodeStore.list]（多版本 = 条目数 > 1，装载侧正是对它做
 * 「只许一件」裁决）、信任标记 [ModTrustStore.isTrusted]（入参磁盘身份，内部经 `.codeid` 解析成
 * 装载闸读取的运行期身份）、身份侧车 [ModCodeIdentity.read]、
 * 最近一次装载结论 [ModCodeLoadReport]（ABI 缺口只有装载侧知道，盘面读不出来）——
 * 不另造第二套「什么叫有代码 / 什么叫坏了 / 什么叫多版本 / 什么叫引擎太旧」。
 *
 * @param loadReport 装配期发布的最近一次装载结论；null = 本次进程还没有结论（按盘面事实判，
 *   引擎太旧一态不出）。
 */
internal fun modCodePackState(
    rt2Dir: File,
    packageId: String,
    dexLoaderEnabled: Boolean,
    loadReport: ModCodeLoadReport? = null,
): ModCodePackState? {
    val artifacts = runCatching { ModCodeStore(rt2Dir).list(packageId) }.getOrNull().orEmpty()
    if (artifacts.isEmpty()) return null
    val corrupt = runCatching {
        ModCodeIdentity.read(rt2Dir, packageId) is CodeIdSidecar.Corrupt
    }.getOrDefault(false)
    val trusted = runCatching { ModTrustStore.isTrusted(rt2Dir, packageId) }.getOrDefault(false)
    return ModCodePackState(
        packageId = packageId,
        hint = modCodeGateHint(
            packageId = packageId,
            facts = ModCodeGateFacts(
                hasArtifacts = true,
                multipleVersions = artifacts.size > 1,
                identityCorrupt = corrupt,
                runtimeGateEnabled = dexLoaderEnabled,
                trusted = trusted,
                engineTooOld = loadReport?.engineGapFor(packageId),
            ),
        ),
    )
}

/** 设置页「代码」读数的状态词（[hint] 为 null = 已启用）。 */
internal fun modCodeStateLabel(text: SettingsStrings, hint: ModCodeGateHint?): String = when (hint?.kind) {
    ModCodeGateHintKind.MULTIPLE_VERSIONS -> text.modCodeStateMultipleVersions
    ModCodeGateHintKind.CODE_ID_CORRUPT -> text.modCodeStateCorrupt
    ModCodeGateHintKind.ENGINE_TOO_OLD -> text.modCodeStateEngineTooOld
    ModCodeGateHintKind.GATE_OFF -> text.modCodeStateGateOff
    ModCodeGateHintKind.UNTRUSTED -> text.modCodeStateUntrusted
    null -> text.modCodeStateEnabled
}

/**
 * 设置页 Mod 管理行的「代码」读数行：复用既有「标签：值」句式
 * （[SettingsStrings.modManageDetailLine]），不新增句式键。
 */
internal fun modCodeStateLine(text: SettingsStrings, state: ModCodePackState): String =
    text.modManageDetailLine(text.modManageCodeLabel, modCodeStateLabel(text, state.hint))

/**
 * 导入成功页的代码闸说明句（null = 不渲染：无代码产物 / 已生效 / 引擎太旧）。
 *
 * 只在这一层做「种类 → 原因句」的映射，句式和原因文案都来自 [ModImportCopy]：
 * 成功页与设置页说的是同一条盘面事实，但不许互相借键（两张表的形状闸各管各的）。
 *
 * 「引擎太旧」不上导入页：它只由**装载结论**（[ModCodeLoadReport]）产生，而导入收尾只读盘面
 * （[codeGateHintFor] 不判 ABI，也不该在这里复制装载闸的判据）；给导入页加一条「导入完成时
 * 不可能出现」的原因句只会让玩家把两件事混起来。该态的玩家可见面 = 设置页 Mod 管理行 /
 * 代码包概览行。
 */
internal fun modCodeGateHintText(copy: ModImportCopy, hint: ModCodeGateHint?): String? {
    val kind = hint?.kind ?: return null
    val reason = when (kind) {
        ModCodeGateHintKind.MULTIPLE_VERSIONS -> copy.modCodeGateReasonMultipleVersions
        ModCodeGateHintKind.CODE_ID_CORRUPT -> copy.modCodeGateReasonCorrupt
        ModCodeGateHintKind.GATE_OFF -> copy.modCodeGateReasonGateOff
        ModCodeGateHintKind.UNTRUSTED -> copy.modCodeGateReasonUntrusted
        // 导入面不判 ABI：该态只由装载结论产生，见本函数 KDoc。
        ModCodeGateHintKind.ENGINE_TOO_OLD -> null
    }
    return reason?.let { copy.fill(copy.modCodeGateHintFormat, hint.packageId, it) }
}

/**
 * 导入收尾时算一次代码闸提示（[ModImportController] 成功页用）。
 *
 * 从控制器类体搬出来（该类贴 detekt `LargeClass` 600 行门槛），语义是「把 ⑤′ 之后刚写完盘的
 * 侧车读一遍」：信任 = [ModTrustStore.isTrusted]（磁盘身份解析后读运行期标记），身份 = [ModCodeIdentity.read]，多版本 =
 * [ModCodeStore.list] 条目数（正常路径刚被导入收尾清成一件，读到多件说明清理失败，照实报）。
 * 每处 `runCatching` 的兜底值都是「不报异常那一侧」——读盘失败当未信任/未损坏/只有一份，
 * 宁可少一条提示也不误指修复动作；身份侧车刚由本笔写盘，损坏只可能是写后被外部改坏，
 * 照实报（不假设「刚写就一定好」）。
 */
internal fun codeGateHintFor(rt2Dir: File, packageId: String, codeGateEnabled: Boolean): ModCodeGateHint? =
    modCodeGateHint(
        packageId = packageId,
        facts = ModCodeGateFacts(
            hasArtifacts = true,
            multipleVersions = runCatching {
                ModCodeStore(rt2Dir).list(packageId).size > 1
            }.getOrDefault(false),
            identityCorrupt = runCatching {
                ModCodeIdentity.read(rt2Dir, packageId) is CodeIdSidecar.Corrupt
            }.getOrDefault(false),
            runtimeGateEnabled = codeGateEnabled,
            trusted = runCatching { ModTrustStore.isTrusted(rt2Dir, packageId) }
                .getOrDefault(false),
        ),
    )
