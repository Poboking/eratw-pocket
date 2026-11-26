package com.eratw.port.android

import com.eratw.port.modapi.EraMod
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException

/** entrypoint 的形状闸：Java 全限定类名（至少一段包名 + 类名，各段不以数字开头）。 */
private val ENTRYPOINT_FQN: Regex = Regex("([A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$][A-Za-z0-9_$]*")

/** 诊断 detail 的单行长度上限：包内自抛的异常 message 可能很长，落日志前截断。 */
private const val DETAIL_MAX_LENGTH: Int = 200

/** 「类此前初始化失败」的 NoClassDefFoundError 文案特征（JDK 与 ART 一致）。 */
private const val ERRONEOUS_CLASS_MESSAGE: String = "Could not initialize class"

/** 诊断 detail 里的换行清洗：日志必须是单行（一条装载尝试一行）。 */
private val LINE_BREAKS: Regex = Regex("[\\r\\n\\t]+")

/**
 * 运行包装载结果的封闭数据面：成功只带 [EraMod] 实例与建出它的 ClassLoader；
 * 失败只带可落日志的 detail，一律不携带 ClassLoader 引用（ADR-013 D4）。
 */
internal sealed interface ModuleLoadOutcome {
    /** 实例化成功：`loader` 即 [LoadOutcome.Loaded] 里那一个，供调用方按包管理生命周期。 */
    data class Loaded(
        val packageId: String,
        val entrypoint: String,
        val mod: EraMod,
        val loader: ClassLoader,
    ) : ModuleLoadOutcome

    /** entrypoint 不是合法 Java 全限定名：在 loadClass 之前拒掉，不触发任何类加载。 */
    data class InvalidEntrypoint(val packageId: String, val entrypoint: String) : ModuleLoadOutcome

    /** 入口类或其依赖类在当前 loader 下不可用（ClassNotFoundException 与 LinkageError 同归此类）。 */
    data class ClassNotFound(
        val packageId: String,
        val entrypoint: String,
        val detail: String,
    ) : ModuleLoadOutcome

    /** 入口类拿不到无参构造（NoSuchMethodException）。 */
    data class NoDefaultConstructor(
        val packageId: String,
        val entrypoint: String,
        val detail: String,
    ) : ModuleLoadOutcome

    /** 实例不是 [EraMod]：多因包内 ModApi 与宿主不是同一 ClassLoader（ADR-013 D4 的类型身份分裂）。 */
    data class NotAnEraMod(
        val packageId: String,
        val entrypoint: String,
        val detail: String,
    ) : ModuleLoadOutcome

    /** 构造过程失败（private 构造、抽象类实例化、构造器内抛异常等）。 */
    data class ConstructorFailed(
        val packageId: String,
        val entrypoint: String,
        val detail: String,
    ) : ModuleLoadOutcome

    /** 入口类静态初始化失败（ExceptionInInitializerError，及之后重复装载的 erroneous class NDCFE）。 */
    data class StaticInitializationFailed(
        val packageId: String,
        val entrypoint: String,
        val detail: String,
    ) : ModuleLoadOutcome
}

/**
 * ADR-013 S4c 前半：「代码包 → [EraMod] 实例」的装载器（载体注册表/运行时装配不在本切片）。
 *
 * 输入只接受已过 [ModDexLoader] 闸链的成功结果 [LoadOutcome.Loaded]（任务书里的 DexLoadResult
 * 即本仓此类型），因此信任闸、总闸、sha256 复算都不在这里重复判——本类只做三件事：
 * 1. entrypoint 形状校验（Java 全限定名），非法即拒、不 loadClass；
 * 2. `loadClass(entrypoint)` → `getDeclaredConstructor().newInstance()`；
 * 3. `as? EraMod` 收口，任何失败都收敛成 [ModuleLoadOutcome] 数据面，不向调用方抛。
 *
 * 失败分类与日志：一条装载尝试恰好一行日志（`mod.dex module pkg=… entrypoint=… outcome=…`），
 * 失败行带真实异常类名与 message（detail 已清洗为单行并截断）；logSink 自身抛异常不影响结果。
 * 静态初始化失败从 newInstance 的 ExceptionInInitializerError 识别；若该类此前已初始化失败、
 * 后续再装会拿到 `Could not initialize class` 的 NoClassDefFoundError，同样归
 * [ModuleLoadOutcome.StaticInitializationFailed]。
 */
internal class ModDexModuleLoader(private val logSink: (String) -> Unit = {}) {

    fun load(loaded: LoadOutcome.Loaded, entrypoint: String): ModuleLoadOutcome {
        val outcome = decide(loaded, entrypoint)
        trace(loaded.packageId, entrypoint, outcome)
        return outcome
    }

    private fun decide(loaded: LoadOutcome.Loaded, entrypoint: String): ModuleLoadOutcome {
        if (!ENTRYPOINT_FQN.matches(entrypoint)) {
            return ModuleLoadOutcome.InvalidEntrypoint(loaded.packageId, entrypoint)
        }
        val loadedClass = runCatching { loaded.loader.loadClass(entrypoint) }
        val classFailure = loadedClass.exceptionOrNull()
        return when (classFailure) {
            null -> construct(loaded, entrypoint, loadedClass.getOrThrow())
            else -> ModuleLoadOutcome.ClassNotFound(loaded.packageId, entrypoint, describe(classFailure))
        }
    }

    private fun construct(
        loaded: LoadOutcome.Loaded,
        entrypoint: String,
        clazz: Class<*>,
    ): ModuleLoadOutcome {
        val constructor = runCatching { clazz.getDeclaredConstructor() }
        val constructorFailure = constructor.exceptionOrNull()
        return when (constructorFailure) {
            null -> instantiate(loaded, entrypoint, constructor.getOrThrow())
            is NoSuchMethodException -> ModuleLoadOutcome.NoDefaultConstructor(
                loaded.packageId,
                entrypoint,
                describe(constructorFailure),
            )
            else -> ModuleLoadOutcome.ConstructorFailed(
                loaded.packageId,
                entrypoint,
                describe(constructorFailure),
            )
        }
    }

    private fun instantiate(
        loaded: LoadOutcome.Loaded,
        entrypoint: String,
        constructor: Constructor<*>,
    ): ModuleLoadOutcome {
        val created = runCatching { constructor.newInstance() }
        val failure = created.exceptionOrNull()
        return when (failure) {
            null -> asEraMod(loaded, entrypoint, created.getOrThrow())
            is ExceptionInInitializerError -> staticInitFailure(loaded.packageId, entrypoint, failure)
            is InvocationTargetException -> invocationFailure(loaded, entrypoint, failure)
            is NoClassDefFoundError -> if (failure.message?.contains(ERRONEOUS_CLASS_MESSAGE) == true) {
                staticInitFailure(loaded.packageId, entrypoint, failure)
            } else {
                ModuleLoadOutcome.ClassNotFound(loaded.packageId, entrypoint, describe(failure))
            }
            else -> ModuleLoadOutcome.ConstructorFailed(loaded.packageId, entrypoint, describe(failure))
        }
    }

    private fun invocationFailure(
        loaded: LoadOutcome.Loaded,
        entrypoint: String,
        failure: InvocationTargetException,
    ): ModuleLoadOutcome {
        val cause = failure.targetException ?: failure
        return when (cause) {
            is ExceptionInInitializerError -> staticInitFailure(loaded.packageId, entrypoint, cause)
            else -> ModuleLoadOutcome.ConstructorFailed(loaded.packageId, entrypoint, describe(cause))
        }
    }

    private fun asEraMod(
        loaded: LoadOutcome.Loaded,
        entrypoint: String,
        instance: Any,
    ): ModuleLoadOutcome {
        val mod = instance as? EraMod
        return when (mod) {
            null -> ModuleLoadOutcome.NotAnEraMod(
                loaded.packageId,
                entrypoint,
                "class=" + instance.javaClass.name,
            )
            else -> ModuleLoadOutcome.Loaded(loaded.packageId, entrypoint, mod, loaded.loader)
        }
    }

    private fun trace(packageId: String, entrypoint: String, outcome: ModuleLoadOutcome) {
        val line = "mod.dex module pkg=" + sanitize(packageId) +
            " entrypoint=" + sanitize(entrypoint) + " " + describeOutcome(outcome)
        runCatching { logSink(line) }
    }
}

private fun staticInitFailure(
    packageId: String,
    entrypoint: String,
    failure: Throwable,
): ModuleLoadOutcome = ModuleLoadOutcome.StaticInitializationFailed(
    packageId,
    entrypoint,
    describe(failure),
)

private fun describe(failure: Throwable): String =
    "exception=" + failure.javaClass.name + " message=" + sanitize(failure.message ?: "")

private fun sanitize(raw: String): String = raw.replace(LINE_BREAKS, " ").trim().take(DETAIL_MAX_LENGTH)

private fun describeOutcome(outcome: ModuleLoadOutcome): String = when (outcome) {
    is ModuleLoadOutcome.Loaded -> "outcome=loaded class=" + outcome.mod.javaClass.name
    is ModuleLoadOutcome.InvalidEntrypoint -> "outcome=invalid-entrypoint"
    is ModuleLoadOutcome.ClassNotFound -> "outcome=class-not-found " + outcome.detail
    is ModuleLoadOutcome.NoDefaultConstructor -> "outcome=no-default-constructor " + outcome.detail
    is ModuleLoadOutcome.NotAnEraMod -> "outcome=not-an-era-mod " + outcome.detail
    is ModuleLoadOutcome.ConstructorFailed -> "outcome=constructor-failed " + outcome.detail
    is ModuleLoadOutcome.StaticInitializationFailed -> "outcome=static-init-failed " + outcome.detail
}
