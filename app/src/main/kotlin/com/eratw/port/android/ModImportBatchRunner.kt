package com.eratw.port.android

/**
 * 批导入的分组与循环（task-178）：[ModImportController.importBatch] 的唯一实现体。
 *
 * 单独成文件是为了不把控制器类撑破 detekt 的类体上限（[ModImportController] 的既有复杂度
 * 已按签名冻结在 baseline，禁放宽闸门）；分组 / 循环 / 收结果本身与单包链无关，只依赖
 * 「一项 = 一组 [PickedModFile]」和「怎么跑一项」两个口——[run] 的 [importOne] 可注入，
 * 测试因此能构造单项异常 / 循环本体异常，而不必在生产分支里埋开关。
 *
 * 复用口径不变：每项执行仍是调用方传入的**既有单包导入**（生产 = [ModImportController.import]），
 * 编译 / 转码 / 落盘 / 身份校验 / 迁移仍只有那一份实现。
 */
internal object ModImportBatchRunner {

    /**
     * 选中文件 → 批次项分组（纯函数、顺序稳定）：zip 整包各成一项；非 zip 的直选源合成
     * 一项；组在结果里的位置 = 其首个成员在选中序里的位置（所以「zip、散装、zip」⇒ 三项，
     * 中间那项是散装组）。判 zip 用 [ModZip.isZipName]（与相位⓪同一口径）。
     */
    fun groupsOf(files: List<PickedModFile>): List<List<PickedModFile>> {
        val groups = ArrayList<MutableList<PickedModFile>>()
        var loose: MutableList<PickedModFile>? = null
        for (file in files) {
            if (ModZip.isZipName(file.displayName)) {
                groups.add(mutableListOf(file))
            } else {
                var target = loose
                if (target == null) {
                    target = ArrayList()
                    loose = target
                    groups.add(target)
                }
                target.add(file)
            }
        }
        return groups
    }

    /**
     * 逐项顺序跑 [groups]，收成 [ModImportBatchOutcome]。
     *
     * 两层隔离：单项异常由 [itemOutcome] 折成该项自己的一行（后续项继续）；外层 try/catch
     * 兜**循环本体**异常（迭代器 / 项名拼接炸了），已完成项原样保留、原因进
     * [ModImportBatchOutcome.batchFailure]。两个兜底都用调用方的 [unexpected] 折文案——
     * 生产口径是控制器既有的 [ModImportOutcome.Failed] + 玩家面 [ModImportCopy.unexpectedFormat]。
     */
    fun run(
        groups: List<List<PickedModFile>>,
        copy: ModImportCopy,
        importOne: (List<PickedModFile>) -> ModImportOutcome,
        unexpected: (Exception) -> ModImportOutcome.Failed,
    ): ModImportBatchOutcome {
        val items = ArrayList<ModImportBatchOutcome.Item>(groups.size)
        val loopFailure = runCatching {
            for (group in groups) {
                val name = group.joinToString(copy.nameListSeparator) { it.displayName }
                items.add(ModImportBatchOutcome.Item(name, itemOutcome(group, importOne, unexpected)))
            }
        }.exceptionOrNull()
        return if (loopFailure == null) {
            ModImportBatchOutcome(items = items.toList())
        } else {
            ModImportBatchOutcome(items = items.toList(), batchFailure = foldFailure(loopFailure, unexpected))
        }
    }

    /** 单项异常隔离：可预期的失败由 [importOne] 自己折好；这里兜「跑这一项时冒出来的异常」。 */
    private fun itemOutcome(
        group: List<PickedModFile>,
        importOne: (List<PickedModFile>) -> ModImportOutcome,
        unexpected: (Exception) -> ModImportOutcome.Failed,
    ): ModImportOutcome = runCatching { importOne(group) }
        .getOrElse { foldFailure(it, unexpected) }

    /**
     * 兜底口径与单包链一致：Exception 折成该项 / 批级失败；Error（OOM、栈溢出这类）**不吞**，
     * 原样抛给调用方——批量层不该把进程级故障伪装成「这一包导入失败」。
     */
    private fun foldFailure(t: Throwable, unexpected: (Exception) -> ModImportOutcome.Failed): ModImportOutcome.Failed =
        if (t is Exception) unexpected(t) else throw t
}
