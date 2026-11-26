package com.eratw.port.android.market

/**
 * `pack.json` 的 `overrideFunctions` 形状闸（R7-① 可选收窄声明，次要能力）。
 *
 * 独立成文件的理由：它是**清单解析里唯一与应用骨架无关的判据**（纯 JSON 形状 → 声明清单），
 * 而 `MarketVerifierImpl` 的函数数已到 detekt `TooManyFunctions` 阈值边缘——把这块挂在那里
 * 只会让「加一个清单字段就要挪一个方法」变成常态。语义闸也不在这里：声明是否与实际可覆盖集
 * 一致，要等补丁编译出模块函数表才知道，落在导入期
 * [com.eratw.port.core.mod.PatchImportTranscoder]（见 `OverrideInputs.allowList`）。
 *
 * 返回 (声明, 问题)：首个问题即拒，两选一永远非空。
 *
 * - 缺席与显式 `null` 都 = 未声明（`null, null`）——两者语义一致（都走自动推导），区分只会让
 *   作者以为 `null` 有额外含义。
 * - 在册必须是字符串数组且逐项非空（空串不是函数名，放过去只会在导入期变成莫名其妙的
 *   「不可覆盖目标」）。
 * - 空数组**合法且有含义**：显式声明「本包不覆盖任何函数」，不折成 `null`。
 */
internal fun parseDeclaredOverrides(obj: JsonObject): Pair<List<String>?, String?> =
    when (val node = obj.opt("overrideFunctions")) {
        null, is JsonNull -> null to null
        !is JsonArray -> null to "overrideFunctions 必须是字符串数组（缺省 = 自动推导；[] = 显式不覆盖）"
        else -> {
            val bad = node.items.indexOfFirst { (it as? JsonString)?.value?.isNotBlank() != true }
            if (bad >= 0) {
                null to "overrideFunctions[$bad] 必须是函数名（非空字符串）"
            } else {
                node.items.map { (it as JsonString).value } to null
            }
        }
    }
