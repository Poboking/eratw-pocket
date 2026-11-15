package com.eratw.port.android

import com.eratw.port.core.vars.VariableStore
import java.io.File
import java.security.SecureRandom

/**
 * 决策种子熵基座与真熵尾巴（W2-SEED 熵源两件，自 `ModRuntimeAssembly.kt` 拆出——
 * 合并树顶层函数数收口：两波并流后该文件顶层函数触及 detekt `TooManyFunctions`
 * 阈值 11，本文件承载种子熵源两件，源头修不加 Suppress、不动 baseline）。
 */

/**
 * 决策种子的**安装级熵基座**（W1-DSTATE 判据 2，W2-SEED 起降级为混合基座）：
 * gameCode + 游戏根规范路径。它本身每安装恒定（同安装同值），不足以做「每局新种子」
 * （W2-SEED 判据 1），故只作为熵串的前缀与真熵尾巴拼接——保留它使派生锚仍含游戏身份
 * （换游戏必然换种子），尾段见 [freshDecisionEntropyTail]。纯函数、无静态可变全局。
 */
internal fun decisionEntropy(gameRoot: File, store: VariableStore): ByteArray =
    "${store.registry.gameBase.gameCode}\u0000${gameRoot.canonicalPath}".toByteArray(Charsets.UTF_8)

/**
 * 决策种子的**真熵尾巴**（W2-SEED 判据 1 的宿主侧熵源）：每次装配局部新建 [SecureRandom]
 * 取 16 字节。熵纪律：app 层产熵（真熵在宿主侧），core 层只收 `ByteArray` 参数
 * （`DecisionSeed.derive(entropy)` 签名不动）；实例是局部值不跨调用，无静态可变全局
 * （铁律 4；不用 kotlin.random）。每次 attach 独立取样 ⇒ 同一安装连续两局种子必不同
 * （SHA-256 前 8 字节全撞 = 2 的负 64 次方量级）。
 */
internal fun freshDecisionEntropyTail(): ByteArray =
    ByteArray(DECISION_ENTROPY_TAIL_BYTES).also { SecureRandom().nextBytes(it) }

/** 真熵尾巴字节数（128 位，与种子派生所需的 8 字节量级留足余量）。 */
internal const val DECISION_ENTROPY_TAIL_BYTES: Int = 16
