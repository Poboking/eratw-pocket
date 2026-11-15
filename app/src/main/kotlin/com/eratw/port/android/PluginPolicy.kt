package com.eratw.port.android

import com.eratw.port.core.policy.DisabledErbPatchCatalog
import com.eratw.port.core.policy.ErbPatchPolicy

/**
 * app 侧的补丁策略**只读视图**（PLUGIN-POLICY，2026-11-07；第二轮改为整体禁载）。
 *
 * ## 权威面已下沉到引擎
 *
 * 「哪些 ERB 补丁被整体禁载」的真相在 `:core-vm` 的 [DisabledErbPatchCatalog]，判定机制在
 * [ErbPatchPolicy]。`erbcc`（编译 PC 产物时按目录整体不装载脚本）与 app（运行期缺席位 +
 * 设置页列出）共用同一张表 —— 本对象不再自带数据，只做三件 app 专属的事：
 * 1. 给 [GameHost.wireCorpusNativeMethods] 一个稳定的本地名字，继续消费全目录打缺席位；
 * 2. 给设置页卡片一个稳定的本地名字，遍历全目录列出；
 * 3. 把目录 id 映射到 [PluginPolicyCopy] 的三语展示名（文案属 app，不进引擎）。
 *
 * ## 两代能力的分工
 *
 * - **编译期整体禁载（主手段）**：[ErbPatchPolicy] 命中的 `.ERB` 脚本零函数体进产物，
 *   补丁挂在 `@EVENTCOMEND` 的成员也一并失效；`.ERH` 变量头与空体标签占位按通用规则保留，
 *   以免主语料静态引用（`KOJO_MESSAGE.ERB:263 CALL AI_MESSAGE`）编译不过。见引擎侧 KDoc。
 * - **运行期缺席位（纵深防御）**：旧 `.erbc` / 玩家导入 mod / 后续新增同名 CALLSHARP
 *   仍由 `NativeMethodRegistry.markPolicyAbsent` 静默空转；目录外未知名字走官方形态
 *   （ADR-005 收窄不废除）。
 *
 * 本版不落盘、不开运行期开关（主人裁定）：要启用某补丁只能改 [DisabledErbPatchCatalog]
 * 重新出包，设置页卡片是只读说明，不是开关。
 */
object PluginPolicyCatalog {

    /** 全目录（引擎侧数据的只读视图；顺序即设置页列出序）。 */
    val all: List<ErbPatchPolicy.Entry> get() = DisabledErbPatchCatalog.all

    /** 4.981 AI对话补丁条目（测试与文档用的稳定别名）。 */
    val AI_CHAT: ErbPatchPolicy.Entry get() = DisabledErbPatchCatalog.AI_CHAT

    /**
     * 目录内补丁的设置页展示名（id → [PluginPolicyCopy] 文案键）。**未映射直接炸** ——
     * 目录新增条目漏加三语文案键属于验收红（AGENTS.md §6），不让裸 id 上屏冒充文案。
     */
    fun displayName(id: String, copy: PluginPolicyCopy): String = copy.nameFor(id)
}
