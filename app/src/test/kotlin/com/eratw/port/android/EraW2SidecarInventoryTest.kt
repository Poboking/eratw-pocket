package com.eratw.port.android

import com.eratw.port.core.mod.ModDx3AssetMap
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA-W2：`.chara/`（ERA-A2 随包角色模板目录）与 `.assetmap`（ERA-A3 覆盖侧车）纳入包成员
 * 全集的定向判据（成员清单 / 卸载 / 孤儿 GC 三面）。
 *
 * 判活口径（宁保守勿激进，钉死在 [ModPackageInventory] KDoc）：
 *
 * 1. **成员清单**：`.assetmap` 是导入链写出的第 5 个文件产物（写序在容器之后）；
 *    `.chara/` 是目录成员，与 `.assets/` 同判据（整树归包，不算独立件）。
 * 2. **卸载**：显式卸载把两类侧车一并删净（盘上零残留）。
 * 3. **GC 判活按产物类型区分**：`.assetmap` 跟随半成品判据（包缺容器/manifest ⇒ 连带删，
 *    与 `.sources` 同口径——线格式只有导入链写得出，不构成「作者先手摆」的合法顺序）；
 *    `.chara/` 目录自身无 manifest 是**常态而非半成品**（判据永不看目录内容），包级判活
 *    跟随（完整包保留、半成品连树删），「只有 chara 目录没有任何文件成员」⇒ 保守保留
 *    （作者先摆模板目录是合法顺序，与 `.assets/` 同判据）。
 * 4. **邻包零损伤**：GC 只删被判孤儿的那一包；显式卸载只动本 packageId。
 */
class EraW2SidecarInventoryTest {

    private fun tempRoot(): File = Files.createTempDirectory("era-w2-sidecar").toFile()

    private fun rt2(root: File): File = File(root, "mods/rt2").apply { mkdirs() }

    private fun mods(root: File): File = File(root, "mods").apply { mkdirs() }

    private fun put(dir: File, name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        File(dir, name).writeBytes(bytes)
    }

    /** 摆一个完整包的四件套 + 指纹（provenance 名单指向 `mods/` 里真实存在的源）。 */
    private fun putFullPackage(dir: File, packageId: String, modsDir: File) {
        put(dir, "$packageId.sources", "still.ERB\n".toByteArray(Charsets.UTF_8))
        put(dir, "$packageId.sources.sha256")
        put(dir, "$packageId.manifest")
        put(dir, "$packageId.erbm")
        File(modsDir, "still.ERB").writeText("src", Charsets.UTF_8)
    }

    private fun putCharaDir(dir: File, packageId: String, bytes: ByteArray = byteArrayOf(7, 7, 7)) {
        // 模板目录两层结构：目录内还有子目录（模板分节），验证「整树」语义。
        val sub = File(dir, "$packageId.chara/CharaTemplates").apply { mkdirs() }
        File(dir, "$packageId.chara/Chara999.csv").writeBytes(bytes)
        File(sub, "sub.csv").writeBytes(byteArrayOf(8, 8, 8))
    }

    private fun byteOf(file: File): ByteArray? = if (file.isFile) file.readBytes() else null

    /** 生产源码面读取（与 `ModPackageGcTest.sourceOf` 同款；CRLF 归一）。 */
    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    // ------------------------------------------------------------------
    // 成员清单：名单形状 + 与导入链字面量同源（源码哨兵）
    // ------------------------------------------------------------------

    @Test
    fun `memberNames含覆盖侧车且目录成员与设置侧车不入名单`() {
        assertEquals(
            "文件产物六件（写序 = 名单序），目录成员与设置侧车不混入",
            listOf(
                "kojo-x.sources",
                "kojo-x.sources.sha256",
                "kojo-x.manifest",
                "kojo-x.erbm",
                "kojo-x.assetmap",
                "kojo-x.codeid",
            ),
            ModPackageInventory.memberNames("kojo-x"),
        )
    }

    @Test
    fun `chara后缀与导入链字面量同值且assetmap写序在容器之后`() {
        // `.chara` 唯一写方 = 导入链 ⑤‴ 的 private 常量；两侧任一改名不同步 ⇒ 哨兵红。
        val controller = sourceOf("ModImportController.kt")
        val declared = Regex("CHARA_TEMPLATE_DIR_SUFFIX = \"([^\"]+)\"").find(controller)
            ?.groupValues?.get(1)
            ?: error("ModImportController.kt 里找不到 CHARA_TEMPLATE_DIR_SUFFIX 字面量")
        assertEquals("Inventory 镜像必须与导入链写方同值", declared, ModPackageInventory.CHARA_DIR_SUFFIX)

        // `.assetmap` 单源 = core-vm ModDx3AssetMap.SIDECAR_EXTENSION（直引不镜像）。
        assertEquals(ModDx3AssetMap.SIDECAR_EXTENSION, ModPackageInventory.ASSETMAP_EXTENSION)

        // 落盘写序：覆盖侧车 ⑤⁗ 在容器 ⑤ 之后（本面 KDoc「写序即名单序」的盘面依据）。
        val erbmAt = controller.indexOf("saveArtifact(rt2Dir, erbmName")
        val assetMapAt = controller.indexOf("saveArtifact(rt2Dir, assetMapName")
        assertTrue(
            "覆盖侧车落盘必须晚于容器（实得 erbm=$erbmAt assetmap=$assetMapAt）",
            erbmAt in 0 until assetMapAt,
        )
    }

    // ------------------------------------------------------------------
    // 孤儿 GC 判活矩阵（按产物类型区分）
    // ------------------------------------------------------------------

    @Test
    fun `完整包的侧车全保留且目录内容判据不看`() {
        val root = tempRoot()
        val dir = rt2(root)
        val md = mods(root)
        putFullPackage(dir, "kojo-aaaa1111", md)
        put(dir, "kojo-aaaa1111.assetmap", "ERATW-ASSETMAP/1\n".toByteArray(Charsets.UTF_8))
        putCharaDir(dir, "kojo-aaaa1111")

        ModPackageInventory.gcOrphans(dir, md)

        assertTrue("assetmap 保留", File(dir, "kojo-aaaa1111.assetmap").isFile)
        assertTrue("chara 目录保留", File(dir, "kojo-aaaa1111.chara").isDirectory)
        assertTrue(
            "chara 目录整树保留",
            File(dir, "kojo-aaaa1111.chara/CharaTemplates/sub.csv").isFile,
        )
    }

    @Test
    fun `半成品包连侧车一起清且chara整树不留残`() {
        val root = tempRoot()
        val dir = rt2(root)
        val md = mods(root)
        // 半成品：manifest 在、容器缺（启动砖化来源）。侧车跟随整包清。
        put(dir, "kojo-bbbb2222.manifest")
        put(dir, "kojo-bbbb2222.sources")
        put(dir, "kojo-bbbb2222.assetmap")
        putCharaDir(dir, "kojo-bbbb2222")

        ModPackageInventory.gcOrphans(dir, md)

        assertFalse(File(dir, "kojo-bbbb2222.assetmap").exists())
        assertFalse("半成品包的模板目录整树删净", File(dir, "kojo-bbbb2222.chara").exists())
    }

    @Test
    fun `孤儿assetmap按半成品判据清理且只有chara目录保守保留`() {
        val root = tempRoot()
        val dir = rt2(root)
        val md = mods(root)
        // ① 只有 assetmap、无四件套：导入链派生件单独在场 = 半成品残骸（与「只剩 .sources」
        //    同口径删除；它无消费可能——消费面按 packageId 读，包没 manifest 装配不出来）。
        put(dir, "kojo-cccc3333.assetmap")
        // ② 只有 chara 目录：作者先摆模板再导入是合法顺序 ⇒ 保守保留（与 .assets/ 同判据）。
        putCharaDir(dir, "kojo-dddd4444")

        ModPackageInventory.gcOrphans(dir, md)

        assertFalse("孤儿 assetmap 清掉", File(dir, "kojo-cccc3333.assetmap").exists())
        assertTrue("只有 chara 目录 ⇒ 保守保留", File(dir, "kojo-dddd4444.chara").isDirectory)
    }

    @Test
    fun `老包无侧车零行为差且手放件内置资产永不删`() {
        val root = tempRoot()
        val dir = rt2(root)
        val md = mods(root)
        // 老包（A3 之前导入）：四件套在、两类侧车都缺 ⇒ 保留（缺失零行为差）。
        putFullPackage(dir, "kojo-eeee5555", md)
        // 手放 assetmap 文件（非导入链形状来源，但文件名后缀命中）：不经 GC 删除面——
        // packageIdOf 认后缀但「完整包保留」判据不变；此处只证明其不被 GC 无故动。
        put(dir, "kojo-ffff6666.assetmap")
        put(dir, "kojo-ffff6666.manifest")
        put(dir, "kojo-ffff6666.erbm")
        // 内置资产与手放目录形状不参与。
        File(dir, "eratw.demo.chara").apply { mkdirs() }.resolve("x.csv").writeText("t")
        put(dir, "handmade.assetmap")

        ModPackageInventory.gcOrphans(dir, md)

        assertTrue("老包保留", File(dir, "kojo-eeee5555.erbm").isFile)
        assertTrue("有容器有 manifest 的包保留（手放 assetmap 随包保留）", File(dir, "kojo-ffff6666.assetmap").isFile)
        assertTrue("内置 chara 目录不碰", File(dir, "eratw.demo.chara/x.csv").isFile)
        assertTrue("非 kojo- 前缀手放件不碰", File(dir, "handmade.assetmap").isFile)
    }

    @Test
    fun `GC只删半成品A包邻包B的侧车与产物逐字节无损`() {
        val root = tempRoot()
        val dir = rt2(root)
        val md = mods(root)
        // A = 半成品（缺 manifest），带全部侧车形态；B = 完整包，带全部侧车形态。
        put(dir, "kojo-aaaa1111.erbm")
        put(dir, "kojo-aaaa1111.sources")
        put(dir, "kojo-aaaa1111.assetmap", byteArrayOf(1, 1, 1, 1))
        putCharaDir(dir, "kojo-aaaa1111", byteArrayOf(2, 2, 2, 2))
        putFullPackage(dir, "kojo-bbbb2222", md)
        val bAssetmapBytes = byteArrayOf(3, 3, 3, 3)
        val bCharaBytes = byteArrayOf(4, 4, 4, 4)
        put(dir, "kojo-bbbb2222.assetmap", bAssetmapBytes)
        putCharaDir(dir, "kojo-bbbb2222", bCharaBytes)

        ModPackageInventory.gcOrphans(dir, md)

        assertTrue("A 清净", File(dir, "kojo-aaaa1111.erbm").exists().not())
        assertFalse(File(dir, "kojo-aaaa1111.assetmap").exists())
        assertFalse(File(dir, "kojo-aaaa1111.chara").exists())
        assertArrayEquals("B 的 assetmap 逐字节无损", bAssetmapBytes, byteOf(File(dir, "kojo-bbbb2222.assetmap")))
        assertArrayEquals(
            "B 的模板逐字节无损",
            bCharaBytes,
            byteOf(File(dir, "kojo-bbbb2222.chara/Chara999.csv")),
        )
        assertTrue("B 的容器在", File(dir, "kojo-bbbb2222.erbm").isFile)
    }

    // ------------------------------------------------------------------
    // 显式卸载：两类侧车删净 + 邻包零损伤
    // ------------------------------------------------------------------

    @Test
    fun `卸载删净两类侧车与全部成员`() {
        val rt2 = tempRoot()
        try {
            val pkg = "kojo-1a2b3c4d"
            put(rt2, "$pkg.sources")
            put(rt2, "$pkg.sources.sha256")
            put(rt2, "$pkg.manifest")
            put(rt2, "$pkg.erbm")
            put(rt2, "$pkg.assetmap")
            putCharaDir(rt2, pkg)
            File(rt2, "$pkg.assets").apply { mkdirs() }.resolve("img.png").writeBytes(byteArrayOf(9))
            ModEnablement.setEnabled(rt2, pkg, false)

            assertTrue("卸载成功", ModManageUninstall.uninstall(rt2, pkg))

            val left = rt2.listFiles()!!.map { it.name }.toSet()
            for (name in ModPackageInventory.memberNames(pkg)) {
                assertFalse("成员 $name 应删净", left.contains(name))
            }
            assertFalse("模板目录整树删净", left.contains("$pkg.chara"))
            assertFalse("资源目录删净", left.contains("$pkg.assets"))
            assertFalse("停用标记删净", left.contains("$pkg.off"))
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `卸载只动本包邻包侧车逐字节无损`() {
        val rt2 = tempRoot()
        try {
            val a = "kojo-1a2b3c4d"
            val b = "kojo-ffffffff"
            for (pkg in listOf(a, b)) {
                put(rt2, "$pkg.sources")
                put(rt2, "$pkg.manifest")
                put(rt2, "$pkg.erbm")
                put(rt2, "$pkg.assetmap", byteArrayOf(0x11, 0x22, 0x33))
                putCharaDir(rt2, pkg, byteArrayOf(0x44, 0x55))
            }

            assertTrue(ModManageUninstall.uninstall(rt2, a))

            assertFalse(File(rt2, "$a.assetmap").exists())
            assertFalse(File(rt2, "$a.chara").exists())
            assertArrayEquals(
                "B 的 assetmap 逐字节无损",
                byteArrayOf(0x11, 0x22, 0x33),
                byteOf(File(rt2, "$b.assetmap")),
            )
            assertArrayEquals(
                "B 的模板逐字节无损",
                byteArrayOf(0x44, 0x55),
                byteOf(File(rt2, "$b.chara/Chara999.csv")),
            )
            assertTrue("B 的容器在", File(rt2, "$b.erbm").isFile)
        } finally {
            rt2.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 回收台账：chara 目录在 = 产物在（台账条目不吞模板段）
    // ------------------------------------------------------------------

    @Test
    fun `回收台账把chara目录算作在档产物`() {
        val rt2 = tempRoot()
        try {
            val pkg = "kojo-9e8d7c6b"
            ModStorageReclaim.record(rt2, pkg)
            // 产物在场 ⇒ 条目不生效（跳过集不含它）。
            putFullPackage(rt2, pkg, mods(tempRoot()))
            assertTrue(
                "产物在 ⇒ 不进回收跳过集",
                ModStorageReclaim.reclaimedPackageIds(rt2).contains(pkg).not(),
            )
            // 四件套清光、只剩 chara 目录 ⇒ 仍算「有产物」（模板段不许被台账回收掉）。
            for (name in ModPackageInventory.memberNames(pkg)) File(rt2, name).delete()
            putCharaDir(rt2, pkg)
            assertFalse(
                "只剩 chara 目录也算在档产物，台账条目不得生效",
                ModStorageReclaim.reclaimedPackageIds(rt2).contains(pkg),
            )
        } finally {
            rt2.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 源码哨兵：MarketGameBridge 的模板目录删除必须引用 Inventory 常量（照 CX 波口径）
    // ------------------------------------------------------------------

    @Test
    fun `MarketGameBridge删除面引用CHARA_DIR_SUFFIX单源`() {
        val bridge = sourceOf("market/MarketGameBridge.kt")
        assertTrue(
            "模板目录后缀必须引用 ModPackageInventory.CHARA_DIR_SUFFIX（源级哨兵）",
            bridge.contains("ModPackageInventory.CHARA_DIR_SUFFIX"),
        )
    }
}
