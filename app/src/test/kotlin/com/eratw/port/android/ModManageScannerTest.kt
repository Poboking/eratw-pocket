package com.eratw.port.android

import com.eratw.port.core.patch.BoolSettingDeclaration
import com.eratw.port.core.patch.EnumSettingDeclaration
import com.eratw.port.core.patch.IntSettingDeclaration
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.modfamily.FamilyDeclarations
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批2-3 设置页列表的数据面：把 `mods/rt2/` 扫成 [InstalledMod]。
 *
 * 断言四件事：① 来源判定只看盘面（内置 / 带 `.erbm` 的数据包 / `kojo-` 导入包）；
 * ② 坏 manifest 只进 skipped、不挡其它 Mod；③ 停用项沉底且带停用读数；
 * ④ 缺依赖体检（ModDX2-C）：`requires` 声明的前置不在「其余已扫 manifest ∪ 内置载体」
 * 名单里就标出，静态事实、不随启停变。
 */
class ModManageScannerTest {

    private fun tempGameRoot(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun rt2Of(gameRoot: File): File = ModEnablement.rt2Dir(gameRoot).also { it.mkdirs() }

    private fun writeManifest(
        rt2: File,
        packageId: String,
        displayName: String = "",
        author: String = "",
        version: String = "0.1",
    ) {
        val descriptor = ModuleDescriptor(
            packageId = packageId,
            version = version,
            displayName = displayName,
            author = author,
        )
        File(rt2, "$packageId.manifest").writeBytes(ManifestCodec.encode(descriptor))
    }

    /** 缺依赖体检用例（ModDX2-C）的夹具：`requires` 声明进 manifest，其余字段缺省。 */
    private fun writeManifestWithRequires(
        rt2: File,
        packageId: String,
        requires: List<String>,
        version: String = "0.1",
    ) {
        val descriptor = ModuleDescriptor(
            packageId = packageId,
            version = version,
            requires = requires,
        )
        File(rt2, "$packageId.manifest").writeBytes(ManifestCodec.encode(descriptor))
    }

    @Test
    fun `来源按盘面判定 显示名缺失回退包名`() {
        val root = tempGameRoot("rt2-scan-source")
        try {
            val rt2 = rt2Of(root)
            writeManifest(rt2, "eratw.family", displayName = "家族系统", author = "eratw-port")
            writeManifest(rt2, "eratw.datapack")
            File(rt2, "eratw.datapack.erbm").writeText("module")
            writeManifest(rt2, "kojo-1a2b3c4d", displayName = "口上补丁")
            File(rt2, "kojo-1a2b3c4d.erbm").writeText("module")

            val scan = ModManageScanner.scan(root)

            assertTrue("坏件不该出现在正常扫描里：${scan.skipped}", scan.skipped.isEmpty())
            assertEquals(
                listOf("eratw.datapack", "eratw.family", "kojo-1a2b3c4d").sortedBy { it.lowercase() },
                scan.mods.map { it.packageId }.sortedBy { it.lowercase() },
            )
            val byId = scan.mods.associateBy { it.packageId }
            assertEquals(ModSource.BUILTIN, byId.getValue("eratw.family").source)
            assertEquals("家族系统", byId.getValue("eratw.family").title)
            assertEquals("eratw-port", byId.getValue("eratw.family").author)
            assertEquals("0.1", byId.getValue("eratw.family").version)
            // 没有显示名就用包名兜底：列表里不能出现空白行。
            assertEquals("eratw.datapack", byId.getValue("eratw.datapack").title)
            assertEquals(ModSource.DATA_PACKAGE, byId.getValue("eratw.datapack").source)
            assertEquals(ModSource.IMPORTED, byId.getValue("kojo-1a2b3c4d").source)
            // task-2 详情配对读数：`.erbm` 是否在场；内置载体本来就没有模块文件。
            assertFalse(byId.getValue("eratw.family").hasModule)
            assertTrue(byId.getValue("eratw.datapack").hasModule)
            assertTrue(byId.getValue("kojo-1a2b3c4d").hasModule)
            assertTrue(scan.mods.none { it.missingModule })
            assertTrue(scan.mods.all { it.enabled })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `坏 manifest 只进 skipped 不挡其它 mod`() {
        val root = tempGameRoot("rt2-scan-broken")
        try {
            val rt2 = rt2Of(root)
            writeManifest(rt2, "eratw.family", displayName = "家族系统")
            File(rt2, "eratw.broken.manifest").writeText("这不是 manifest")

            val scan = ModManageScanner.scan(root)

            assertEquals(listOf("eratw.family"), scan.mods.map { it.packageId })
            // 明细带解码器给的原因（`文件名：原因`），但必须以文件名开头以便界面点名。
            assertEquals(1, scan.skipped.size)
            assertTrue(scan.skipped.single().startsWith("eratw.broken.manifest"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `停用项沉底并带停用读数`() {
        val root = tempGameRoot("rt2-scan-disabled")
        try {
            val rt2 = rt2Of(root)
            writeManifest(rt2, "eratw.aaaa", displayName = "A")
            writeManifest(rt2, "eratw.zzzz", displayName = "Z")
            ModEnablement.setEnabled(rt2, "eratw.aaaa", false)

            val scan = ModManageScanner.scan(root)

            assertEquals(listOf("eratw.zzzz", "eratw.aaaa"), scan.mods.map { it.packageId })
            val disabled = scan.mods.last()
            assertFalse(disabled.enabled)
            assertTrue(disabled.title.isNotBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `没有 rt2 目录时扫出空表而不是抛`() {
        val root = tempGameRoot("rt2-scan-empty")
        try {
            val scan = ModManageScanner.scan(root)
            assertTrue(scan.mods.isEmpty())
            assertTrue(scan.skipped.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `作者声明的设置项随扫描结果进列表 声明序即渲染序`() {
        val root = tempGameRoot("rt2-scan-settings")
        try {
            val rt2 = rt2Of(root)
            val descriptor = ModuleDescriptor(
                packageId = "eratw.family",
                version = "0.2",
                displayName = "家族系统",
                settings = listOf(
                    IntSettingDeclaration("family.growthPerDay", default = 1, min = 1, max = 10, title = "每日成长次数"),
                    BoolSettingDeclaration("family.showTitles", default = true, title = "显示家族称号"),
                    EnumSettingDeclaration(
                        "family.successionMode",
                        default = "eldest",
                        candidates = listOf("eldest", "random"),
                        title = "继承规则",
                    ),
                ),
            )
            File(rt2, "eratw.family.manifest").writeBytes(ManifestCodec.encode(descriptor))
            // 没有声明 setting 的包：设置页不得凭空长出一节。
            writeManifest(rt2, "eratw.plain")

            val byId = ModManageScanner.scan(root).mods.associateBy { it.packageId }

            val family = byId.getValue("eratw.family")
            assertEquals(
                "设置页渲染序必须等于作者声明序",
                listOf("family.growthPerDay", "family.showTitles", "family.successionMode"),
                family.settings.map { it.key },
            )
            assertEquals(listOf("每日成长次数", "显示家族称号", "继承规则"), family.settings.map { it.title })
            assertEquals(
                "值必须经声明类型读，不能只剩键名",
                "1",
                family.settings.first().defaultText(),
            )
            assertTrue(byId.getValue("eratw.plain").settings.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `导入包缺 erbm 标为半成品 内置载体不算`() {
        val root = tempGameRoot("rt2-scan-half")
        try {
            val rt2 = rt2Of(root)
            // 导入链只落下清单、没落模块容器：数据包配对不全，装配期不会当模块装载。
            writeManifest(rt2, "kojo-deadbeef")
            writeManifest(rt2, "eratw.family")

            val byId = ModManageScanner.scan(root).mods.associateBy { it.packageId }

            assertFalse(byId.getValue("kojo-deadbeef").hasModule)
            assertTrue("导入包缺 .erbm 必须是半成品读数", byId.getValue("kojo-deadbeef").missingModule)
            // 内置载体只有 manifest 是正常形态，不能误报成半成品。
            assertFalse(byId.getValue("eratw.family").missingModule)
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- 缺依赖体检（ModDX2-C） ----

    @Test
    fun `缺依赖标出 在场依赖不标 约束条目按链接期同口径取键`() {
        val root = tempGameRoot("rt2-scan-requires")
        try {
            val rt2 = rt2Of(root)
            // 三个依赖键：缺的裸包名 / 在场的约束形态（键取 @ 前段，语法权威 =
            // ModuleDescriptor.parseRequiresConstraint，与 ModuleLinkOrder.buildGraph 同口径）/
            // 指向自身（「其余已扫」不含自身：链接期把 requires 指向自身判「依赖环」拒载）。
            writeManifestWithRequires(
                rt2,
                "eratw.dependent",
                requires = listOf("eratw.gone", "eratw.present@[1.0,2.0]", "eratw.dependent"),
            )
            writeManifest(rt2, "eratw.present", version = "1.5")

            val byId = ModManageScanner.scan(root).mods.associateBy { it.packageId }

            assertEquals(
                "缺的键按声明序标出；自引用不算已满足",
                listOf("eratw.gone", "eratw.dependent"),
                byId.getValue("eratw.dependent").missingRequires,
            )
            // 反向口径：依赖在场的包一个都不标。
            assertTrue(byId.getValue("eratw.present").missingRequires.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `依赖是内置载体不算缺 名单走 ModRegistry 单源`() {
        val root = tempGameRoot("rt2-scan-carrier")
        try {
            val rt2 = rt2Of(root)
            // 盘上没有 eratw.family 的 manifest：这个依赖能成立只因它在 ModRegistry
            // 在册载体名单里（ModCarrierRegistry.versionDeclarations 读单源，不建实例）。
            // 旁边放一个真缺的键做对照。
            writeManifestWithRequires(
                rt2,
                "kojo-needsbase",
                requires = listOf(FamilyDeclarations.PACKAGE_ID, "eratw.nope"),
            )

            val mod = ModManageScanner.scan(root).mods.single()

            assertEquals(listOf("eratw.nope"), mod.missingRequires)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `停用 mod 仍标缺依赖 体检不随启停变`() {
        val root = tempGameRoot("rt2-scan-requires-off")
        try {
            val rt2 = rt2Of(root)
            writeManifestWithRequires(rt2, "eratw.dependent", requires = listOf("eratw.gone"))
            writeManifestWithRequires(rt2, "eratw.offdep", requires = listOf("eratw.gone2"))
            ModEnablement.setEnabled(rt2, "eratw.dependent", false)
            ModEnablement.setEnabled(rt2, "eratw.offdep", false)

            val byId = ModManageScanner.scan(root).mods.associateBy { it.packageId }

            // 体检是盘面静态事实：被体检的包自己关掉了，缺前置读数也不消失 ——
            // 玩家重新打开之前就该看到它缺什么。
            assertFalse(byId.getValue("eratw.dependent").enabled)
            assertEquals(listOf("eratw.gone"), byId.getValue("eratw.dependent").missingRequires)
            assertFalse(byId.getValue("eratw.offdep").enabled)
            assertEquals(listOf("eratw.gone2"), byId.getValue("eratw.offdep").missingRequires)
        } finally {
            root.deleteRecursively()
        }
    }
}
