package com.eratw.port.android

import android.util.Log
import java.io.File

/**
 * 「已安装的 Mod」卸载：删掉 `mods/rt2/` 下某个包的全部盘上成员。
 *
 * 成员清单 = 导入产物（[ModPackageInventory.memberNames]，四件套 + `.assetmap` 覆盖侧车）+
 * 设置侧车（[ModSettingsStore.memberName]）+ 停用标记 + 资源目录 + 随包角色模板目录
 * （见 [removableMemberNames]）：
 *
 * - `<packageId>.sources`（provenance：来源盘面文件名）
 * - `<packageId>.sources.sha256`（内容指纹侧车；老包可能没有）
 * - `<packageId>.manifest`（RT2MANIFEST 侧车）
 * - `<packageId>.erbm`（模块容器）
 * - `<packageId>.assetmap`（覆盖侧车，ERA-A3；老包可能没有）
 * - `<packageId>.settings`（设备侧 per-mod 设置，[ModSettingsStore]；卸载必须删净）
 * - `<packageId>.off`（停用标记；停用中的包删完也不该残留）
 * - `<packageId>.trusted`（信任确认标记，ADR-013 D3；[ModDexGate.trustMarkerName]）。卸载必须
 *   删净：磁带上留着旧信任标记，同包名重装后会**静默继承**旧信任，安全面不能留（重装应回到
 *   「待确认信任」初始态）
 * - `<运行期身份>.trusted`（双身份第二份信任标记，ADR-013 S6）。装载闸按**运行期身份**查信任，
 *   而磁盘包名是编译派生的磁盘身份；两套身份不等时上面那条磁盘面标记根本不在装载闸的读取面上，
 *   真正要让玩家再确认一次的是这份运行期身份标记。卸载按 [ModCodeIdentity] 侧车解析出运行期
 *   身份后一并删净（见 [clearRuntimeTrustMarker]）
 * - `<packageId>.assets/`（资源目录，读取侧见 `core-vm` 的 `ModResourceStore`；整棵树一起删，
 *   见 [ModPackageInventory.ASSET_DIR_SUFFIX]）
 * - `<packageId>.chara/`（随包角色模板目录，ERA-A2；整棵树一起删，见
 *   [ModPackageInventory.CHARA_DIR_SUFFIX]）
 * - `<packageId>.media` 清单内两树旁挂文件 + 空目录 + 清单本身（ADR-014 S5；由 [ModMediaReclaim]
 *   按清单逐条删，只删清单内路径、只删空目录，绝不整目录清空。清单损坏 / 非标准布局时
 *   fail-closed 保留全部旁挂文件，只 WARN，**不**把卸载报成失败——见 [ModMediaReclaim] KDoc）
 * - `code/<packageId>/` 下全部 dex 产物（ADR-013 D4/S6）。逐件删（含只读产物）+ 删空包目录，
 *   止于 `code/` 根之前；删不掉只 WARN、**不**改卸载结局（残留交下轮孤儿 GC 兜底，见
 *   [ModCodeReclaim]）
 *
 * ## 删除语义与其他路径的分界
 *
 * 本类只是**显式卸载**：玩家点的是「卸载」，留任何本包文件都是脏。因此设置也一起删。
 * 另外两条路径不归这里管，语义也不同（见 [ModSettingsStore] 类 KDoc）：
 *
 * - 孤儿 GC（[ModPackageInventory.gcOrphans]）：仅当包已被判孤儿时连带删设置；
 * - 重导 / 升级（[ModImportController] reimport）：**保留**设置与数据（BU 波显式语义）。
 *
 * ## 边界
 *
 * 1. **只删本包成员**：文件名/目录名一律由 packageId + [ModPackageInventory] 的扩展名派生，
 *    不枚举目录找别的包。packageId 走 [ModEnablement.markerFile] 的同一套形状校验，
 *    含 `/`、`\`、`..`、以 `.`/`-` 开头的输入直接 `IllegalArgumentException`（防路径逃逸）。
 * 2. **不删留档源**：`mods/` 根目录的原始口上文件是玩家的东西（`.sources` 只是出处记录），
 *    卸载后要重装随时能再导入一次。
 * 3. **幂等**：文件本来就不在也算成功；个别文件删不掉（占用/权限）返回 `false`，
 *    由调用方把失败上屏，**不吞成成功**。
 * 4. 内置 Mod（无 `.erbm` 的载体清单）由游戏本体随包提供，这里不做区分——是否允许卸载
 *    由 UI 按 `InstalledMod.source` 决定（当前只对可再导入的来源开这个按钮）。
 */
object ModManageUninstall {

    private const val TAG: String = "ModManageUninstall"

    /**
     * 删掉 [packageId] 的全部成员。
     *
     * @return 全部成员都已不存在（含原本就不存在）时为 `true`；仍有残留为 `false`。
     * @throws IllegalArgumentException packageId 不是合法形状（与
     *   [ModEnablement.markerFile] 同口径）。
     */
    fun uninstall(rt2Dir: File, packageId: String): Boolean {
        // markerFile 同时起到「拼路径前的 packageId 形状校验」作用（非法即抛）。
        val marker = ModEnablement.markerFile(rt2Dir, packageId)
        // 运行期身份必须在删成员之前解析：`.codeid` 侧车本身在本包成员名单里，下面一批删除会把它
        // 一起删掉；删完再读就只能按磁盘身份处理，运行期那份信任标记会留下来。
        val runtimeTrustOwner = runtimeTrustOwnerOf(rt2Dir, packageId)
        val members: List<File> = removableMemberNames(packageId).map { File(rt2Dir, it) } + marker
        // 资源目录与随包模板目录是包的第 7/8 个盘上形状（前者读取侧 ModResourceStore 同路径、
        // 后者装配期模板注入读取）：整棵树一起删。
        val assets = File(rt2Dir, packageId + ModPackageInventory.ASSET_DIR_SUFFIX)
        val charaDir = File(rt2Dir, packageId + ModPackageInventory.CHARA_DIR_SUFFIX)
        for (file in members) {
            if (!file.exists()) continue
            // 删不掉就留着：返回 false 由调用方上屏，别假装成功。
            runCatching { file.delete() }
        }
        if (assets.exists()) runCatching { assets.deleteRecursively() }
        if (charaDir.exists()) runCatching { charaDir.deleteRecursively() }
        // 旁挂媒体面（ADR-014 S5）：按 `<packageId>.media` 清单逐条删游戏根两树内的文件、删空目录、
        // 再删清单本身（只删清单内路径 + 只删空目录，见 [ModMediaReclaim]）。失败只 WARN、**不**改
        // 卸载结局：卸载本体是 `mods/rt2/` 成员删净；损坏清单 fail-closed 保留旁挂文件（下轮 GC 兜底），
        // 不能把已删净的包报成「卸载失败」让玩家误以为要重试。
        // Log 用 runCatching 包住：android.jar 桩在纯 JVM 单测里对 Log 抛「not mocked」（同
        // EratwApplication / EventJournal 的既有写法），告警面不该把卸载结局连坐。
        if (!ModMediaReclaim.uninstall(rt2Dir, packageId)) {
            runCatching { Log.w(TAG, "媒体面未删净（清单损坏/IO/非标准布局），旁挂文件保留 pkg=$packageId") }
        }
        // dex 代码产物面（ADR-013 D4/S6）：`code/<packageId>/` 整棵树一起删（只读产物逐件删、
        // 空包目录一起删，止于 `code/` 根之前）。失败只 WARN、不改卸载结局：代码产物残留由下一轮
        // 孤儿 GC 兜底（那时包级成员已删净 ⇒ 判孤儿），不能把已删净的包报成「卸载失败」。
        ModCodeReclaim.uninstall(rt2Dir, packageId)
        // 双身份收口（task-196）：装载闸按运行期身份查 `<runtime>.trusted`，与磁盘面成员删除
        // 删的不是同一个文件；不在这里补删，同一代码包重装后会**静默继承**旧信任（安全面）。
        val runtimeMarkerCleared = clearRuntimeTrustMarker(rt2Dir, packageId, runtimeTrustOwner)
        val removed = members.none { it.exists() } && !assets.exists() && !charaDir.exists() &&
            runtimeMarkerCleared
        // 回收接点（task-134）：显式卸载成功后把 packageId 记进 `mods/rt2/released-packages.txt`，
        // 抓取侧据此让该包的分段**减在下次存档**（既有存档文件不改写，这行不是删除历史档）。
        // 目录本来就不存在（从未落过盘）= 没有可回收的东西，也不留清单/目录。
        // 清单写失败**不**把卸载报成失败：卸载的本体是「盘上成员删净」，台账只是回收附加面
        // （投回 false 会让玩家看到「卸载失败」而盘上其实已删净、列表里也找不到可重试的项）。
        // 失败只留一条告警：本次回收不生效，该包分段继续随档走，下次卸载/重存才有机会清。
        if (rt2Dir.isDirectory && !runCatching { ModStorageReclaim.record(rt2Dir, packageId) }.getOrDefault(false)) {
            Log.w(TAG, "卸载回收台账写入失败（卸载本身已完成，该包存档段本次未回收）pkg=$packageId")
        }
        return removed
    }

    /**
     * 本包「运行期身份」信任标记的主人，`null` = 没有第二份标记要处理。
     *
     * 三态直接照 [ModCodeIdentity.read] / [ModDexIdentity] 的既有裁决，不另造映射（`.codeid`
     * 是磁盘身份与运行期身份之间唯一的桥）：
     * - [CodeIdSidecar.Bound] 且运行期身份 ≠ 磁盘身份 ⇒ 返回运行期身份，卸载要额外删它的标记；
     * - [CodeIdSidecar.Bound] 相等 / [CodeIdSidecar.Absent]（无代码产物或老代码包）⇒ 磁盘面成员
     *   删除已覆盖同一份标记，返回 `null`；
     * - [CodeIdSidecar.Corrupt] ⇒ 运行期身份不可判，**不猜**：只删磁盘面成员并留一条 WARN 取证，
     *   残留的运行期标记由 [gcOrphanTrustMarkers] 在包变孤儿后回收（见 [ModTrustStore]）。
     */
    private fun runtimeTrustOwnerOf(rt2Dir: File, packageId: String): String? =
        when (val sidecar = ModCodeIdentity.read(rt2Dir, packageId)) {
            is CodeIdSidecar.Bound -> sidecar.runtimePackageId.takeIf { it != packageId }
            is CodeIdSidecar.Corrupt -> {
                // Log 用 runCatching 包住（android.jar 桩在纯 JVM 单测里对 Log 抛 not mocked）。
                runCatching { Log.w(TAG, "身份侧车损坏，运行期信任标记无法定位（磁盘面成员照删）pkg=$packageId") }
                null
            }
            is CodeIdSidecar.Absent -> null
        }

    /**
     * 删本包运行期身份的信任标记；返回「标记已不在场」。
     *
     * `owner == null` = 没有第二份标记（见 [runtimeTrustOwnerOf]），算已清理。[owner] 已经是运行期
     * 身份（标记文件名里的那个 id），所以走按标记身份直删的 [ModTrustStore.distrustMarker]：再经
     * [ModTrustStore.distrust] 会把运行期身份当磁盘身份解析一遍（侧车缺失时侥幸同值、侧车指向自己
     * 以外时就会解析到第三个 id）。规范路径/越界/符号链接闸与幂等语义由该层复用，不自己拼路径。
     */
    private fun clearRuntimeTrustMarker(rt2Dir: File, packageId: String, owner: String?): Boolean {
        if (owner == null) return true
        val cleared = ModTrustStore.distrustMarker(rt2Dir, owner)
        if (!cleared) {
            runCatching { Log.w(TAG, "运行期信任标记删除失败（卸载有残留）pkg=$packageId runtime=$owner") }
        }
        return cleared
    }

    /**
     * 一个包在 `mods/rt2/` 下的**全部删除语义成员** = 导入产物（[ModPackageInventory.memberNames]）
     * + 设置侧车（[ModSettingsStore.memberName]）+ 信任确认标记（[ModDexGate.trustMarkerName]）。
     *
     * 名字全部由同源出口拼装，本类不另写字面量。只有**显式卸载**用这份名单；重导 / 升级保留
     * 设置（见 [ModSettingsStore] 类 KDoc）。信任标记也只在显式卸载时删（重导同包名必须重新确认，
     * 见类 KDoc 的 `.trusted` 条目）。
     */
    private fun removableMemberNames(packageId: String): List<String> =
        ModPackageInventory.memberNames(packageId) +
            ModSettingsStore.memberName(packageId) +
            ModDexGate.trustMarkerName(packageId)
}
