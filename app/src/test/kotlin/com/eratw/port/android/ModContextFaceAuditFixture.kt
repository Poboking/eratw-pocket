package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.ComponentDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.FieldDecl
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.IntSettingDecl
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.PhaseDecl
import com.eratw.port.modapi.RelationshipDecl
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * task-106 作者面接线审计的**真装配**探针夹具。
 *
 * 与既有 `ModSettingsAttachWireTest` 的差别：这里故意**不覆写** `newHost`，走
 * `ModRuntimeAssembly.attach` 的生产缺省宿主（settings / resources / audioSink /
 * timing / handlerBudget / decisionPipeline 全部由 attach 自己注入），因此探针结论
 * 就是「真装配下接没接线」，不是台架口径。
 *
 * 三态判据：属性 getter 抛 `UnsupportedOperationException` = FAIL_CLOSED（此处没接线）；
 * 正常返回 = WIRED；需要真 run/tick 才有语义的入口不进探针（本夹具只探可同步观测的入口）。
 */
internal object ModContextFaceAuditFixture {

    /** 真装配唯一 mod 包名。 */
    const val PKG: String = "mod.face.audit"

    /** 设置项键（manifest settings 段声明）。 */
    const val KNOB_KEY: String = "audit.knob"

    /** 设置项声明默认值。 */
    const val KNOB_DEFAULT: Long = 7L

    /** ModContext.kt 作者面属性数（真源 interface ModContext 的 val 个数）。 */
    const val FACE_COUNT: Int = 24

    /** hook 探针目标函数键（manifest hooks 段声明）。 */
    const val HOOK_TARGET: String = "TARGET"

    /** job 探针槽（manifest jobs 段声明）。 */
    const val JOB_ID: String = "audit.job"

    /** 实体原型名（manifest archetypes 段声明）。 */
    const val ARCHETYPE: String = "AUDIT_ARCH"

    /** 关系类型名（manifest relationships 段声明）。 */
    const val RELATION: String = "AUDIT_REL"

    /** 内建相位名（PhaseRegistry 恒声明）。 */
    const val DAY_START: String = "DAY_START"

    private const val KNOB_MIN: Long = 0L
    private const val KNOB_MAX: Long = 10L
    private const val MODS_DIR: String = "mods"
    private const val RT2_DIR: String = "rt2"
    private const val MANIFEST_FILE: String = "audit.manifest"

    private val logLines: MutableList<String> = mutableListOf()

    /**
     * ModContext.kt 全部作者面（顺序 = 真源声明序）。
     *
     * 这张表是**冻结闸**：源码新增属性而这里没加 ⇒ [sourceFaceNames] 双向差集非空 ⇒ 红；
     * 这里多写一个源码没有的面 ⇒ 同样红。
     */
    val AUTHOR_FACES: List<String> = listOf(
        "packageId",
        "logger",
        "runtime",
        "entities",
        "relationships",
        "roster",
        "events",
        "hooks",
        "phases",
        "jobs",
        "interactions",
        "actions",
        "query",
        "functions",
        "storage",
        "gameState",
        "rng",
        "timing",
        "settings",
        "services",
        "resources",
        "strings",
        "audio",
        "ui",
    )

    /** 真装配探针所需的全部声明域（hooks / interactionHooks / phases / jobs / 实体 / 关系 / settings）。 */
    internal fun manifest(): ModManifest = ModManifest(
        packageId = PKG,
        hooks = listOf(HookDecl(HOOK_TARGET, HookPhase.PREFIX)),
        interactionHooks = listOf(InteractionHookDecl(InteractionPhase.MAIN)),
        phases = listOf(PhaseDecl("AUDIT_PHASE")),
        jobs = listOf(JobDecl(JOB_ID)),
        components = listOf(ComponentDecl("AuditComp", listOf(FieldDecl("hp")))),
        archetypes = listOf(ArchetypeDecl(ARCHETYPE, listOf("AuditComp"))),
        relationships = listOf(RelationshipDecl(RELATION)),
        settings = listOf(
            IntSettingDecl(KNOB_KEY, KNOB_DEFAULT, KNOB_MIN, KNOB_MAX, title = "Knob"),
        ),
    )

    /** 建临时 gameRoot 并写 mods/rt2 目录下的 manifest（attach 的声明真源）。 */
    internal fun tempRoot(): Path {
        val root = Files.createTempDirectory("mod-face-audit")
        val manifestDir = root.resolve(MODS_DIR).resolve(RT2_DIR)
        Files.createDirectories(manifestDir)
        val descriptor = ModDeclarationAdapter.toDescriptor(manifest())
        Files.write(manifestDir.resolve(MANIFEST_FILE), ManifestCodec.encode(descriptor))
        return root
    }

    /** 真装配（缺省宿主）装载 one-mod，并在 onLoad 内执行 [block]；异常记进 [FaceProbe]。 */
    internal fun probe(root: Path, block: (ModContext) -> Unit): FaceProbe {
        logLines.clear()
        val mod = ProbeMod(block)
        val session = attach(root, listOf(PKG to mod))
            ?: error("真装配 attach 返回 null：enabled=true 时不应发生")
        session.shutdown()
        return FaceProbe(mod.context, mod.failure, logLines.toList())
    }

    /** 从 ModContext.kt 真源解析 `val` 属性名（双向差集的源码侧）。 */
    internal fun sourceFaceNames(): Set<String> {
        val source = sourceFile()
        val text = source.readText()
        val body = text.substringAfter("interface ModContext").substringBefore("interface ModLogger")
        return Regex("(?m)^\\s+val\\s+([A-Za-z_]\\w*)\\s*:").findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    /** 编译产物里 ModContext 接口声明的零参 getter 名（双向差集的编译侧）。 */
    internal fun compiledFaceNames(): Set<String> =
        ModContext::class.java.declaredMethods
            .filter { method -> method.parameterCount == 0 && method.name.startsWith("get") }
            .map { method -> method.name.removePrefix("get").replaceFirstChar { it.lowercase() } }
            .toSet()

    private fun attach(root: Path, providers: List<Pair<String, EraMod>>): ModRuntimeSession? =
        ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = VariableStore(),
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logLines += line },
            modProviders = providers,
        )

    private fun sourceFile(): File = listOf(
        File("../mod-api/src/main/kotlin/com/eratw/port/modapi/ModContext.kt"),
        File("mod-api/src/main/kotlin/com/eratw/port/modapi/ModContext.kt"),
        // 公开分仓后 mod-api 在同级引擎仓 Kotera（composite build 的 ../Kotera）。
        File("../../Kotera/mod-api/src/main/kotlin/com/eratw/port/modapi/ModContext.kt"),
    ).firstOrNull { it.isFile }
        ?: error("找不到 mod-api 的 ModContext.kt 真源")

    private class ProbeMod(private val block: (ModContext) -> Unit) : EraMod {

        var context: ModContext? = null

        var failure: Throwable? = null

        override fun onLoad(context: ModContext) {
            this.context = context
            try {
                block(context)
            } catch (failure: UnsupportedOperationException) {
                this.failure = failure
            } catch (failure: IllegalArgumentException) {
                this.failure = failure
            } catch (failure: IllegalStateException) {
                this.failure = failure
            } catch (failure: AssertionError) {
                this.failure = failure
            }
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }
}

/** 一次真装配探针的观测结果：[context] 为空 = onLoad 没跑到；[failure] 非空 = 探针内异常。 */
internal class FaceProbe(
    val context: ModContext?,
    val failure: Throwable?,
    val logs: List<String>,
) {
    /** 包是否装载成功（装配日志里没有 `active=false`）。 */
    val loaded: Boolean
        get() = logs.none { it.contains("active=false") }
}
