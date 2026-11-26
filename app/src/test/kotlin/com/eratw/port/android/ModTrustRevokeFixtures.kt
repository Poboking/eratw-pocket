package com.eratw.port.android

import com.eratw.port.modapi.ModApi
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 撤销信任 → 装载闸（task-165 / ADR-013 路 B）端到端用例的共用夹具。
 *
 * 全部是真实数据面：`mods/rt2/code/<pkg>/<sha16>.mod` 产物、`.trusted` 标记、[ModCodeStore]、
 * [ModTrustStore] / [ModTrustBoard]、[ModDexLoader] 与 [ModDexGate::verdict] 都是生产实现；
 * 唯一替身是 [RevokeRecordingFactory]——它只记录「装了几次、给的是哪个包」，绝不真构造
 * `DexClassLoader`（本波禁真装载，也避免 JVM 单测依赖 dalvik/art）。
 *
 * 放成独立文件而不是塞进测试类：detekt 默认 `TooManyFunctions`（类内 11）对两个测试类各自的
 * 用例数都紧，夹具集中在这里还能让两个测试类共用同一套形状（禁改阈值 / 禁 baseline / 禁 suppress）。
 */

/** 用例统一使用的合法包名（与既有 [ModDexLoaderTest] 同口径的 `eratw.dexload` 形状）。 */
internal const val REVOKE_PACKAGE_ID: String = "eratw.revoke"

/** 产物文件名里的内容寻址前缀长度（`sha256` 前 16 位，见 [MOD_CODE_ARTIFACT_EXTENSION]）。 */
internal const val REVOKE_SHA_PREFIX_LENGTH: Int = 16

/** 当前引擎 mod-api surface（与装载侧判据同源，禁止在测试里另写常量）。 */
internal val REVOKE_ENGINE_API_VERSION: Int = ModApi.VERSION

/** 超前编译面：触发 ABI 闸拒绝。 */
internal val REVOKE_SURFACE_AHEAD: Int = ModApi.VERSION + 1

/** 一份合法包体（内容任意，装载侧只按内容寻址与完整性校验，不解析 dex）。 */
internal val REVOKE_PAYLOAD_BYTES: ByteArray = "revoke-e2e-dex-payload".toByteArray(Charsets.UTF_8)

/** 建一个临时 rt2 根（`mods/rt2` 形状），交给调用方的 [deleteRevokeTree] 收尾。 */
internal fun revokeTempDir(): File = Files.createTempDirectory("mod-trust-revoke").toFile()

/** 递归删掉临时树；只读产物先置可写再删（与 [ModCodeStore] 的只读落盘同构）。 */
internal fun deleteRevokeTree(dir: File) {
    dir.walkBottomUp().forEach { file ->
        file.setWritable(true)
        file.delete()
    }
}

/** 以内容寻址形状落一份产物：`code/<pkg>/<sha16>.mod`（真实盘面，非 mock）。 */
internal fun writeRevokeArtifact(
    rt2Dir: File,
    bytes: ByteArray = REVOKE_PAYLOAD_BYTES,
    packageId: String = REVOKE_PACKAGE_ID,
): File {
    val dir = File(File(rt2Dir, MOD_CODE_DIR_NAME), packageId)
    dir.mkdirs()
    val fileName = revokeSha256Hex(bytes).take(REVOKE_SHA_PREFIX_LENGTH) + MOD_CODE_ARTIFACT_EXTENSION
    val file = File(dir, fileName)
    file.writeBytes(bytes)
    return file
}

/** 包元数据：未声明编译面即放行（老包常态），`compiledSurface` 只在测闸序时填。 */
internal fun revokeMetaOf(compiledSurface: Int? = null): PackMeta =
    PackMeta(
        packageId = REVOKE_PACKAGE_ID,
        declaredVersion = "2.0",
        version = "2.0",
        ignored = null,
        evidence = PackMetaEvidence(compiledSurface = compiledSurface),
    )

/** 真实 [ModDexLoader]：真实 [ModCodeStore] + 真实 ABI 闸，只把类加载器工厂换成记录器。 */
internal fun revokeLoaderOf(
    rt2Dir: File,
    factory: RevokeRecordingFactory = RevokeRecordingFactory(),
    enabled: Boolean = true,
    gate: (PackMeta, Int, ModDexGate.SurfaceCompat) -> ModDexGate.Verdict = ModDexGate::verdict,
): ModDexLoader = ModDexLoader(
    codeStore = ModCodeStore(rt2Dir),
    gate = gate,
    factory = factory,
    logSink = {},
    dexLoaderEnabled = enabled,
)

/** 与 [ModCodeStore] 同口径的 sha256 hex（测试独立实现，不复用生产私有函数）。 */
internal fun revokeSha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

/** 不装载真的 dex：只记「装了几次、装的是谁」，成功时返回 JVM 系统类加载器。 */
internal class RevokeRecordingFactory : DexLoaderFactory {
    val createdFor = mutableListOf<String>()

    override fun create(packageId: String, artifactFile: File): ClassLoader {
        createdFor += packageId
        return ClassLoader.getSystemClassLoader()
    }
}
