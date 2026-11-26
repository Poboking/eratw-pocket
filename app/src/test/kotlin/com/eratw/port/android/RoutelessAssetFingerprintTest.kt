package com.eratw.port.android

import com.eratw.port.core.code.ResolverPolicy
import com.eratw.port.core.patch.LinkFingerprint
import com.eratw.port.core.patch.ManifestCodec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M5-4 刀 2 判据 4 的三资产指纹对账（family / memory / demo 载体，eratw.demo 是两件套
 * 之 manifest 件）：
 *
 * memory / demo 资产无路由声明（刀 1 交付实证：`routelessManifestRoundTripsByteIdentical` +
 * `BuiltinManifestAssetTest` 逐字节对账），指纹渲染行**不落行** ⇒ 两资产的指纹与渲染行
 * 加入前逐字节一致。「加入前一致」的传递链 = core-vm `LinkFingerprintTest` 的无路由
 * 字节锁（改造前钉的哈希，本波零漂移复绿）+ 资产无路由 + 条件落行。
 *
 * family 资产在 M5-4 刀 3 带 F2 路由声明重钉（入库静态资产首次实质变更，契约判据 6）：
 * 指纹含 `charaRoutes` 渲染行，钉值 = 重钉后首钉。指纹渲染行参与与归一口径由
 * `LinkFingerprintTest` 钉死，此处钉的是**资产面**的前向回归锁：入库资产解码 →
 * [LinkFingerprint.compute]（其余九因子用固定合成值）→ 与首钉值逐字节比对。资产被重钉
 * （改路由或改渲染规则）时此处当场红，指纹缓存错配不会静默。
 */
class RoutelessAssetFingerprintTest {

    private fun assetDir(): File =
        listOf("src/main/assets/mods/rt2", "app/src/main/assets/mods/rt2", "../app/src/main/assets/mods/rt2")
            .map(::File)
            .first { it.isDirectory }

    private fun fingerprintOf(assetName: String): String {
        val bytes = File(assetDir(), assetName).readBytes()
        val descriptor = ManifestCodec.decode(bytes)
        return LinkFingerprint.compute(
            LinkFingerprint.Inputs(
                baseProgramSha256 = "asset-fingerprint-probe",
                moduleArtifactSha256s = listOf(descriptor.artifactSha256),
                compilerAbi = "erbcc-1",
                linkerVersion = "link-1",
                bytecodeIsaVersion = 2,
                builtinRegistryFingerprint = "builtin-1",
                runtimeProfileFingerprint = "profile-1",
                resolutionPolicy = ResolverPolicy.MODERN,
                variableSchemaFingerprint = "var-1",
                entitySchemaFingerprint = "ent-1",
                manifests = listOf(descriptor),
            ),
        )
    }

    @Test
    fun `family资产指纹逐字节不变`() {
        // M5-4 刀 3 重钉后的首钉值（F2 路由声明 ABL/EXP/JUEL/CFLAG 进指纹渲染行）。
        val pin = "a42e7277197d04de64822982c315cfb9d84a38290ca13c48452f8aaf61bdc509"
        assertEquals(pin, fingerprintOf("eratw.family.manifest"))
    }

    @Test
    fun `memory资产指纹逐字节不变`() {
        // MODDX4-B4（组装线）重钉：`@relationship-schemas` I1→I2 并追加 acquaintance 无向边声明，
        // 派生资产 eratw.memory.manifest 的关系段随之变化 —— 指纹按新资产重钉；
        // 该波只改声明面，实体/变量/内置面逐字节不变，故只有这一枚 pin 变动。
        val pin = "6e63b1f26d9585fd2b958d843511b2717116781f4fdd35cfdb753ad2004f68da"
        assertEquals(pin, fingerprintOf("eratw.memory.manifest"))
    }

    @Test
    fun `demo资产指纹逐字节不变`() {
        val pin = "ed2aef77ec66cc9df8f3a1043761db6d753017fc7542b3a5f274a816dfc6c949"
        assertEquals(pin, fingerprintOf("eratw.demo.manifest"))
    }
}
