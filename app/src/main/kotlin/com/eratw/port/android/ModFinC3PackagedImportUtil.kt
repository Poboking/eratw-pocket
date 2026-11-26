package com.eratw.port.android

import com.eratw.port.core.patch.ModuleDescriptor
import java.io.IOException
import java.security.MessageDigest

/**
 * ERAFIN-C3 成品包导入合同的共享小工具（internal：主文件与相位文件跨文件复用一份实现，
 * 不复制——`ModImportController.kt` 的同款 private 工具在两文件里各存一份会漂移）。
 *
 * 文件级 internal 函数不占主 object 的函数数（detekt `TooManyFunctions` 的 file 阈值），
 * 与 `ModImportController.commitMedia` 搬文件级压类指标是同一件事。带 `packaged` 前缀命名：
 * 同包既有文件级 private 同名函数（detailText / hasFoldedExtension）在本文件提为 internal
 * 后会撞名，前缀即消歧。
 */

/** 相位函数的失败单点（返回 Nothing，调用点后无需再判；与 ModImportController.failCompile 同构）。 */
internal fun failPackaged(kind: ModImportFailureKind, message: String, cause: Throwable): Nothing =
    throw PackagedImportException(kind, message, cause)

/** 相位异常：kind 值域 = [ModImportFailureKind]，文案已按调用方语言拼好（与 ModCompileException 同构）。 */
internal class PackagedImportException(
    val kind: ModImportFailureKind,
    override val message: String,
    cause: Throwable?,
) : Exception(message, cause)

/** 失败统一出口（与 ModImportController.failed 同口径：异常只进日志面，失败不向上抛）。 */
internal fun failedPackaged(kind: ModImportFailureKind, message: String, cause: Throwable): ModImportOutcome.Failed {
    cause.printStackTrace()
    return ModImportOutcome.Failed(kind, message)
}

/** 异常原话段（与 ModImportController.detailText 同取法；internal 一份，两文件共享不漂移）。 */
internal fun packagedDetailText(e: Throwable): String = e.message ?: e::class.simpleName ?: "null"

/** 尾段扩展名逐字符 ASCII 折叠比较（与 ModImportController.kt 顶层 private 同款写法，locale 无关）。 */
internal fun packagedHasFoldedExtension(name: String, extension: String): Boolean {
    val start = name.length - extension.length
    var matched = start > 0
    var i = 0
    while (matched && i < extension.length) {
        matched = name[start + i].lowercaseChar() == extension[i].lowercaseChar()
        i++
    }
    return matched
}

/** SHA-256 字节摘要的 64 位小写 hex（与市场域 Digests / modgen sha256Hex 同口径）。 */
internal fun packagedSha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and BYTE_MASK_PACKAGED).toString(HEX_RADIX_PACKAGED).padStart(2, '0') }

/**
 * 身份校验后的成品包事实：[descriptor]（manifest 全量）与 [module]（容器全量）的 packageId
 * 已互证相等、过形状闸，manifest 钉的摘要与容器实际摘要逐字一致。
 */
internal class PackagedValidatedIdentity(
    val descriptor: ModuleDescriptor,
    val module: com.eratw.port.core.mod.ErbModuleContainer.ErbModule,
) {
    val packageId: String get() = descriptor.packageId
}

/** 成品包两件套条目名的扩展名常量（识别谓词与主名切分共用；大写给折叠比较用）。 */
internal const val PACKAGED_MANIFEST_ENTRY_SUFFIX = ".MANIFEST"
internal const val PACKAGED_MODULE_ENTRY_SUFFIX = ".ERBM"

private const val BYTE_MASK_PACKAGED = 0xFF
private const val HEX_RADIX_PACKAGED = 16
