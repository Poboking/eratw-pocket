package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ModResourceStore
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「作者从零到玩家可装」真闭环烟测（第二段：玩家侧导入 + 运行期读回资源）。
 *
 * ## 这是「两段接力」，不是同进程 A/B
 *
 * 第一段在 `tools/modgen` 侧（`AuthorClosedLoopSmokeTest`）：真跑
 * `new-mod → 作者补 ERB 源与资源 → check-mod → pack-mod`（两次同字节），并把真产物 zip 的字节
 * 冻结成 Base64 常量 [FIXTURE_BASE64]。本段（`:app` 测试源集）内嵌**同一份** Base64 夹具，走真
 * 导入 `ModImportController`，再用运行期 `ModResourceStore` 按导入端派生的目标 packageId 读回
 * 原字节 —— 两段由同一份字节钉住：第一段断言「新产物 == 该夹具」，本段断言「该夹具玩家侧收得下、
 * 资源读得回原字节」。夹具含 `pack-mod` 写的包级元数据条目 `eratw-pack.json`，本段另断言它的
 * version 经真导入链路落进产物 manifest（不再是硬编码 `"1"`）。本段不读任何别的测试留下的临时文件。
 *
 * 为什么必须拆两段：`pack-mod` 的实现是 `internal object PackMod`（`PackMod.kt:34`），
 * 其 CLI 入口 `ManifestBuild.kt:46-57` 对 `pack-mod` 直接 `exitProcess`；`:app` 测试源集不依赖
 * `:tools:modgen`，本 lane 授权写面也不含 `app/build.gradle.kts`，无法在本进程真跑 pack-mod。
 *
 * 刷新夹具：本夹具逐字节冻结 scaffold 全部生成物（含 `<pkg>.assets/README.txt`，其正文来自
 * `NewModTemplates.assetsReadmeFaces()`），任何修改 `NewModTemplates` 正文的波次都必须同步刷新它。
 * 常量正文夹在两行锚点注释 `// @eratw-pack-fixture-begin` / `// @eratw-pack-fixture-end` 之间：
 * 给测试 JVM 置位刷新旋钮 `-Deratw.refreshPackFixture=true`（或环境变量
 * `ERATW_REFRESH_PACK_FIXTURE=true`）后单跑本类 ⇒ 从 modgen 侧 `AuthorClosedLoopSmokeTest.kt`
 * 的锚点区取权威 Base64 重钉本文件（不用手工粘）；modgen 侧置位时也会顺手重写本文件，两处始终
 * 是同一份 dump。未置位时行为与加旋钮前逐位一致。
 */
class ModImportAuthorPackEndToEndTest {

    @Test
    fun `作者包——真导入后运行期按目标packageId读回原字节资源`() {
        val zip = fixtureZipForRun()
        val readmeBytes = assertFixtureLayout(zip)

        val tmp = Files.createTempDirectory("eratw-authorpacke2e").toFile()
        val detail = imported(controllerIn(tmp).import(listOf(PickedModFile("mod-mymod.ZIP", zip))))

        // 目标 packageId 由导入端派生（重挂），必然不等于模块包源码里的 PACKAGE_ID。
        val targetPackageId = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        assertNotEquals("目标 packageId 必须由导入端派生", MODULE_PACKAGE_ID, targetPackageId)
        assertTrue("目标 packageId 形态：$targetPackageId", targetPackageId.startsWith("kojo-"))
        // 包级元数据条目在 zip 根、不是 `<prefix>.assets/` 前缀 ⇒ 不算资源，资源数仍是两条。
        assertEquals("两条资源都应计数（元数据条目不是资源）", 2, detail.resourceEntryCount)

        // 包级元数据 → 产物 manifest 版本：真链路上版本号不再硬编码 "1"。
        val manifest = File(rt2Of(tmp), targetPackageId + ModPackageInventory.MANIFEST_EXTENSION)
        assertEquals(
            "包级元数据 version 应随导入落到产物 manifest",
            PACK_META_VERSION,
            ManifestCodec.decode(manifest.readBytes()).version,
        )

        // 落盘口径：mods/rt2/<目标 packageId>.assets/<相对路径>。
        val assets = assetDirOf(tmp, targetPackageId)
        assertTrue("资源目录应落在目标包名下：$assets", assets.isDirectory)
        assertArrayEquals("README 字节原样", readmeBytes, File(assets, ASSET_README).readBytes())
        assertArrayEquals("图片字节原样", PNG, File(assets, ASSET_LOGO).readBytes())
        assertFalse("不得按模块包 PACKAGE_ID 建目录", File(rt2Of(tmp), "$MODULE_PACKAGE_ID.assets").exists())

        // 运行期读口径：ModResourceStore 按目标 packageId 装配后读得回原字节。
        val api = ModResourceStore(rt2Of(tmp)).apiFor(targetPackageId)
        assertArrayEquals("运行期按目标 packageId 读回 README", readmeBytes, api.open(ASSET_README)!!)
        assertArrayEquals("运行期按目标 packageId 读回图片", PNG, api.open(ASSET_LOGO)!!)

        // 重挂必须留一条玩家可见 WARN（点名来源目录名），不静默搬。
        val warnings = detail.resourceWarnings
        assertEquals("重挂汇总成一条 WARN：$warnings", 1, warnings.size)
        assertTrue("WARN 点名来源目录名：${warnings[0]}", warnings[0].contains(MODULE_PACKAGE_ID))
        assertTrue("临时目录不得残留：${residueOf(tmp)}", residueOf(tmp).isEmpty())
    }

    /** 夹具自检：真 pack-mod 产物的条目布局/字节（防「假链路」）；返回骨架资源原字节。 */
    private fun assertFixtureLayout(zip: ByteArray): ByteArray {
        assertEquals(
            "条目布局（字典序；包级元数据条目在 zip 根，排最前）",
            listOf(
                PACK_META_ENTRY_NAME,
                "$MODULE_PACKAGE_ID.assets/$ASSET_README",
                "$MODULE_PACKAGE_ID.assets/$ASSET_LOGO",
                "main.ERB",
            ),
            zipEntryNames(zip),
        )
        assertEquals(
            "包级元数据内容（schema / packageId / version）",
            "{\"schema\":1,\"packageId\":\"$MODULE_PACKAGE_ID\",\"version\":\"$PACK_META_VERSION\"}",
            String(zipEntryBytes(zip, PACK_META_ENTRY_NAME), Charsets.UTF_8),
        )
        assertEquals("补丁源原样", PATCH_SOURCE, String(zipEntryBytes(zip, "main.ERB"), Charsets.UTF_8))
        assertArrayEquals("作者资源原字节", PNG, zipEntryBytes(zip, "$MODULE_PACKAGE_ID.assets/$ASSET_LOGO"))
        return zipEntryBytes(zip, "$MODULE_PACKAGE_ID.assets/$ASSET_README")
    }

    // ------------------------------------------------------------------
    // 夹具：mini base + 临时 mods（与其它导入用例同款，private 故复制）
    // ------------------------------------------------------------------

    private fun miniMetadata(): BootMetadata {
        val names = listOf("A_ONE", "A_TWO", "A_SUM", "A_DEF")
        val argCounts = mapOf("A_TWO" to 1, "A_DEF" to 3)
        return BootMetadata(
            functions = names.mapIndexed { i, name ->
                FuncMeta(
                    index = i,
                    key = name,
                    name = name,
                    isMethod = name == "A_SUM",
                    argIntCount = argCounts[name] ?: 0,
                    argStrCount = 0,
                    returnsStr = false,
                    defIndex = i,
                    position = "mini.ERB:${i + 1}",
                )
            },
            methodTypes = mapOf("A_SUM" to BootMetadata.MethodType.INT),
        )
    }

    private fun miniBaseCounts(): PatchCompiler.BaseCounts =
        PatchCompiler.BaseCounts(
            instructionCount = 4000,
            functionCount = 4,
            statementCount = 100,
            stringCount = 10,
            longCount = 5,
            exprCount = 20,
        )

    private fun controllerIn(tmp: File): ModImportController =
        ModImportController(
            modStore = ModStore(File(tmp, "mods")),
            baseSource = { BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null) },
            gameRoot = tmp,
        )

    private fun imported(outcome: ModImportOutcome): ImportedPatchDetail =
        (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("导入应成功，实为 $outcome")

    private fun rt2Of(tmp: File): File = File(File(tmp, "mods"), "rt2")

    private fun assetDirOf(tmp: File, packageId: String): File = File(rt2Of(tmp), packageId + ".assets")

    /** rt2 下残留的临时/备份目录（原子落盘的收尾判据：正常路径必须为空）。 */
    private fun residueOf(tmp: File): List<String> =
        rt2Of(tmp).list()?.filter { it.contains(".tmp-") || it.contains(".bak-") } ?: emptyList()

    private fun zipEntryNames(zip: ByteArray): List<String> =
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            val names = ArrayList<String>()
            while (true) {
                val entry = zin.nextEntry ?: break
                names += entry.name
            }
            names
        }

    private fun zipEntryBytes(zip: ByteArray, name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.name == name) return zin.readBytes()
            }
        }
        throw AssertionError("entry not found: $name")
    }

    private fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) {
        assertEquals(message, expected.toList(), actual.toList())
    }

    private companion object {
        /** 模块包源码里的 PACKAGE_ID（= pack-mod 给资源加的前缀）。 */
        private const val MODULE_PACKAGE_ID = "eratw.mymod"

        /** 包级元数据条目的 zip 根固定名（与 modgen 侧 `PackModMeta.PACK_META_ENTRY_NAME` 同合同）。 */
        private const val PACK_META_ENTRY_NAME = "eratw-pack.json"

        /** 骨架自述版本（`NewModTemplates.kt` 的 `const val VERSION`，经元数据条目落进产物 manifest）。 */
        private const val PACK_META_VERSION = "0.1"

        /** 骨架自带的资源相对路径。 */
        private const val ASSET_README = "README.txt"

        /** 作者放进去的资源相对路径。 */
        private const val ASSET_LOGO = "img/logo.bin"

        /** 合成 base sha（64 位小写十六进制）。 */
        private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        /** 可编译的最小补丁源（= 第一段作者写进 zip 的 `main.ERB`）。 */
        private const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"

        /** 「一张图」的合成字节（读取侧不校验格式，= 第一段作者放进去的资源）。 */
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

        /**
         * 真 `pack-mod` 产物（zip 字节，Base64），与 `AuthorClosedLoopSmokeTest.FIXTURE_BASE64` 逐字节相同。
         * 内容（条目字典序、时间戳 1980-01-01）：`eratw-pack.json`（打包器写的包级元数据，
         * `{"schema":1,"packageId":"eratw.mymod","version":"0.1"}`）+ `<PACKAGE_ID>.assets/README.txt`
         * （骨架自带）+ `<PACKAGE_ID>.assets/img/logo.bin`（作者资源）+ `main.ERB`（作者源）。
         * 元数据条目在 zip 根、不带 `.assets/` 前缀 ⇒ 导入端不算资源（`resourceEntryCount` 仍是 2）。
         *
         * 本夹具逐字节冻结 scaffold 全部生成物（含 `<pkg>.assets/README.txt`），任何修改
         * `NewModTemplates` 正文的波次都必须同步刷新它（一条命令重钉，见类 KDoc 的「刷新夹具」段；
         * 与 modgen 侧 `AuthorClosedLoopSmokeTest.FIXTURE_BASE64` 同源，勿手工粘）。
         */
        // @eratw-pack-fixture-begin
        private const val FIXTURE_BASE64: String =
            "UEsDBBQACAgIAAAAIQAAAAAAAAAAAAAAAAAPAAkAZXJhdHctcGFjay5qc29uVVQFAAGANc4Sq1YqTs5IzU1UsjLUUSpITM5O" +
            "TE/1TFGyUkotSiwp18utzM1PUdJRKkstKs7MzwOKG+gZKtUCAFBLBwhOTLDRNwAAADYAAABQSwMEFAAICAgAAAAhAAAAAAAA" +
            "AAAAAAAAAB0ACQBlcmF0dy5teW1vZC5hc3NldHMvUkVBRE1FLnR4dFVUBQABgDXOEq1bbVMbR7b+rl/RxSeTayQ7m03tTXld" +
            "hY2SsAHsCyS7e+tWuWQY20qEREnCG28qVQJbIIGEeH8TGIR58wsSjgkWAsF/idUzo0/7F+5z+syMhMHevbc2tbUezXSfPn1e" +
            "n3O60cK+6N/cfY/6Qr3/OF6qLmVkKm7++kQvTRjZvCzPGhulysmYy6Uvv+IXGCIwWGDcP44Tf/f3/+M4WSnGzF/fysSwzEyC" +
            "Sr+v54cmGqOPjsr8Y312v1Ke1mcOzNOsmpUyxp/L/IEsHMv4pj5/YG4/k4k9vVjUExlj6YkLUyOecPRTzzUeYrws6Psnxswq" +
            "PhJ7pZeVo/J1ty8S0aIRj6gUx8AJD3U+GzOv5XBc5g/ldEIWUzz/XWxQltfl1pD4IfR9qOmq79O7v+v5DBtPuSrFtP7LqTGz" +
            "g43qOzm5Mo/FBO3Ed19r7RW/xWbwPyETL/XUiL6cZWHIibSxm6yUxoVWL8fku9iQeTph5lL68iqEI0/GjKOMqycUjGo/Rt1h" +
            "LRIaCPdoEaGnkka2KAuHIGQWjrAND5YYri7NgAjYAlfm1qA+dmw8GzSyC+bbgjx5gl3w+8rJivnrnMMJjT8Zw+6gApdLiHPL" +
            "uUP9WvBSQ6e3uaXd647+GG1oFEJ4PAJLy915cxQzs8ZxSZ8bqRxBWzMy+1QEBwKBC4lpP/oj0ch75EDsfQL3fIGIdiGFH7RH" +
            "kUvEQv1/oCDjO9XHO45gSLnpEVnKQCYX0on4/65dtC3ekz6796FtucztJzKxCHnJzAszXxS29KF7Xp4lTvO3BmUyDSPzyL0T" +
            "+IA+t6yvrEEVxvOSnv+VHoYO8eBxuwX/lpm0M0gf35aJA/BjvNqiodkFPMjhKZl5C1NxyY1tuZeRw4tkD0rTcnwVO6/GJjBH" +
            "pg6rcZJCc7A3HPL3wuBH9dmM/mSVLU2mZ3lrZBUHcfN0RHwm2v03YA9wPXhE7f3Vz+kDLaln942jYezTLOxWDpPm9qBjsKI9" +
            "1NtpCbcrGgprglzhm5ZQD0106clpRXNcHs/q0+PwvuriBLjr8web+/1YVGYK1ViSlFU9WjDzG7R8/qQ6X4QYIWe5vAM3g8Ob" +
            "J4/1uT0KJk2+fr8wH5flaFYZ9wymkNMuZ/X5bV7GfBanbc8f6NDCsz19YdxxyeUd17UOX592vUXrCfjgh/5QEMYVVWyDev1r" +
            "IfNPZWaoOpKSo2tysgwh8xvB7Is/imsd17GbDnFdXBFYDDHM1ecL+u9pkaggO0zsic7uT9ubO1q/9HZ1ez4X8vAAqiMCah/Q" +
            "PtubsTwIEn8UdftPVUrbRvI5GyZEiO0ZyQSFz5F95fHKcd/j+dLt5pvfNH/lvdPaclk81MIRvATZ77ydXa23Oi7XOP+8UfwE" +
            "qWyJn10uOVKCliAvOZGSsePK8ZpwwnJTk80r7xXcc2iWcQTrPT2dV5JjstWZvOIsCw3ADGEfrBAXrATf3RYbolIepzjYIThI" +
            "w/Kq0yf62JRZLmMJPTOBTPIulqouxyiiZfdJ9b7+/nexNFtxzwPNYo/cbn8VjEBM1ZX16khGH910eTs7b3WSWxUOqy8XsJQs" +
            "T8MjxXts0OrKPDBH/Lm5swOLV9efGLOL5F5vZsmyEhvVmUUOsFfg8hzBmRy0gQRAyWB0U1l7pZiEh8C/9FwCpB5evUKGzWYs" +
            "QsG2kK9XkDWpNVXEjumvcsbRYrU8If77a4+3w/OnZphAUs89g1ODEImSXQ3GDH9XuRAGYpTzWMncicnMnD3yTByPRMP+4P2I" +
            "u1fZBiJeXc5x3w9rWhSfGy6Lvz+ALTRUymtys4yfGhlLw9daIBDCr+999Ovd4PS7oel3gy/fDebeDRYaGi9YJnTvAyvY0RVs" +
            "KplZXNub05dfWBqA8eHl2lvEW2VFKSXX7AVrPfBFPrwY1oJOyKYWCnJii4kjmpmFTZC9gFrAF7w/gLR9LrecSzR1/Btrg4Rn" +
            "HsAgIDEPBEWKPpMh6lRu6ztbr1l5Gq/mjuotAKGsuvIUKYK8bOKJuPZH8furnwpOBOxW2AInH84A+KQX0h62fs8dj9vTRPCk" +
            "OnsqS1s0//PP3ncWeFXongciFIirldJwpTzsgBj9l5yw7AWeBufAcnWeoY+WlUPUXE3uva6uLCJws7rk4IhRWmcPRVDJnhjJ" +
            "EUQJY3+NZv+al6+HzkR8awTHt8NVfebEAY4kAiAvfx80E/Hc9QWDWtjdH7zPjkiAc22QPCljmQ+F5t158gEVEb/1t9LMSw3n" +
            "CMCqfYFoNyyATJsBK7PG3DQ01qm8patNGG/mQLOdaHoDWp8WjLqZtg0pKJsQrfNLNZ6xH/3pk2psTS7ukAwmyPfl5ry+lqu+" +
            "SLkcwEER5nntGfagzxT01KC+diyPM3gD+Iuty43X5v4mGVr+tFI80ou/6KvTzsb0xJxMr1XKaT03jHxj5vcsFZKSKpi0/1wB" +
            "CTUXZiBu+KN9vv4vfT1I4I+sJaCB2x1fYcU/3fbSP3/W7t6m6CeLReR/EIe8mCs2TXlUMvN58+QQ1lfDEDDg/h9psZoRggHe" +
            "taM5ym224nnr7Lx2qkvUxEP7oLiu1E3SeTMrVLC3rM7BJfqbHTmcIjNSahX/ISqljUppqpo7hBywA8EwAIq1s38o6A367gY0" +
            "K7fWQsWAH/jxPgCsFv5/mxUyr+/HLn+vdvtHofy68QML4E0YikDaPhvkYHkDoPLQH/GDR9D4SUT9PT/cDA0EowQ/2sTPtYzu" +
            "7JX2FfDd1QLfaI9QQIzIiUkOj/WpSsXbBECrvruOOsgcnLFqrkwBgrLTGSnZXl0vZBC29NVNs7BujO/p2GbsWHl9dfVNdX1S" +
            "Jb+rCtWp3x/zcd9Arz/kiT6Ab7lD9/+ph2erL3b0pwdULao3oEihFvuLuLu/9rZ77zR/29J6i+KgrVCBegpmSwHreBb2biyv" +
            "mnuP8XBWzYoRd3/A9+jS+9Qui0Ao1A+hR8MDGpQQCgwop7/i/sM9Tjr0AS/Yp+TbX2Rm3pgYPkc9Eg31fzzTfCD5HA7B90gw" +
            "v+T05SR2rE8Biq/KfBIFkA0xvmsXRunU2Bmzt5dg+MUvyUk5XIyOiv6w1o8474lEfWFEjNQLiusT4zKzx55UH5GcmgFOzdWC" +
            "8LjkSoYK5sUd4VE1BgryqV1SxuKOuf4S8YflywVtfXVHENicKNeFJN1C8mljbY4Le4I9+RO5MULpZfUNwAHtFeb4uKzPr8Eg" +
            "yNbYEDCNGxHsdLb9LQEyWqX5q1ecZc23k4K0K5B6jScUYyyGEBswYCVns2SeLlIA2pw0Sqi74pQIF9NGaZuZxCcgQrNQAJMu" +
            "iLLewrF/gOMzFg5EPZG+wMhJ2CoLIF4hj8ImWX4fCJaK80sNbrcbaQUcffKJk4XxqDzh3xRGaVv13kIGkFnATzzoY8f6+GYN" +
            "CNTyf0LmUcQW9GPC3+zDgO5m/rk58kJ/FjOyBUi1UnzBS2IiJMdWSlA7MeHsRkVxBXDxG3T7tbCSRESLEsaLKOAAW/9IFWcX" +
            "YA7UZv0TS0w49yu4kqM7qHtQbEMg5BqxYwBsAo6pwcphXKbmsAobYeVkm8KJ+XZH7i4AwFEaxvPrWOV0BbVCrX8DjI7AOJZG" +
            "SOQGzQfyTIvPH3jUjehdK+P8wWgX75CDT5e3u7u146s7397+qrO5xXvn5tfNHTe9l4HP7vkGApRlfn+l7bIIA72qQNTmdl+9" +
            "cqWNssrdUCjwUVrejuYbbd6WemIUvmgukkzfhXM7b7W13Wm/1XKGBTXC+XKns7mj5Vb7ZdHjC/b6e31RLYIxAaS1W/cuwXAb" +
            "aQHA7gvpd3v/0l1PuqHhgl4PpaLlV6xEcqzy+pmSx7IQN2T5MSHWIzyn/iDg9fpIPh2jMPRqy0DNSYaevYg+CVhtCJOojnCe" +
            "SXqkbfXCVTNjK2gDsMc3ZeYZVG4lWGVhbJH6DAEThdKB8Ml6stQ/RJqLHRvH07A8KruR76hjkKLSJrGIObYwxuFt1ZE0Cmgd" +
            "hekMKuIEN2CW03I0x64Eo1Xd2G9C0YA/KPgTV6iTsvhYnzu0KxbVi31KmT01RN3WcWLbls8o1W0HCXP/EDPIFdvrHA6J0tfb" +
            "fA8gxqrDwJq45w/2vl+fauGHfurH9Q/chYk8uNT+6M+hcKC3i99/8QX8OhJxf+976LuMMqA/cNmZ6vRZyZ4e+gLCogWrOUed" +
            "Vv4Iaas6hX4qpRJL4UzLD0bGu+aqC3GEQpgtLYp6G0u0R34zf6B2CumhUFtEmjOGqCUpMyk1WKHkdBEqopCaT5rP4ozjsZ5+" +
            "tCTTb/SpjepMjKmpMHxhWodeHfFTHD6Zp2hUoJ9QPPWpJneAgIHDCQejdlhOsqo5niuzMGPzYNcyi8IRUiCsE3b18OrvBHXn" +
            "hxcR9AivLRQq5WUzFldDk1bNQEwFoPKeRz1AVx4FRPHPg1CI/tEeokgSMoty9wUkgOIWURHPyvY48KIkMfe3qCJWxLEQMjsG" +
            "0te3rxEkK8UlMAxbN3ObGKxy6Rn7ifr74IvuiA+moXFjmBSZSopoKOoLdPiCoYhA2lad4Cw7IDaKUpkVjCrLzBFCQgTgZhX5" +
            "02j2bNvYWgWlnha1YButUoxTkabMm2mCDmmi/FpOg85Lq5f4AVo9oXDvpQbfQPRBKAxwT5kByL8f4D5IXDeeD3vJMZYZ7M94" +
            "NXOeZp/miwxQk+c8UaoJekNdoT4t+oCCbqP4mQEllQAxDiZQOJA/a8blMo7UKcT6YnVqVXHU7QfWhf+r3jYAzUuYoSxtUS14" +
            "Qq1dQWibjDvSE+rXqH41FsnECS1e/fQPVgODXuwzwYjlIZRUgS3BiuCp1MhI7IlPf/+5QNigph+/hx8oyALkVjZmN7k1bCyP" +
            "UV9YWQzBP+pOky447M1vytN5tm9bWAEALy1MkFMZnT4+hQJGodQUw7oLqx4XN21gfmfpIMvcC9mAzCP+5gsHa7+0cDgU5p+U" +
            "DgciWiNhau7eFVA1HVHrEZDxV+h0FGzSSdaZkow4T8GUZO4pIUq1HSIxs1opbYvbHALbFCcItEliciVn5GCSc5hYXfgFGrX2" +
            "qebKxFsjMYzofL/HF1VIbk6PrzO+sVyrSQH9lZzd/r9y5YrTfFrSEy/0ud1KsURt1LpPiBzvYilUnZd4RKPq0abUnqnbbs07" +
            "HCOFKvRIgY0WYztBJNMHAaUT/siNgC/4Awy0UpwByGexMUBikFkprsvjx1arKb1GgGt2r7q+Alu0aVqBxtIUBBNDgGZ2afju" +
            "M9V1TJkHo9XFCeJIvWHy6hTmBSoTLgZk/LGcHDV3t1zkhWAQ22RR1ssHA0mBdKaBnDxuHO1yyrDtzwLHhFpyKZJCMo3iUB+d" +
            "lIkD0UHTVbmVtptmejJWOdq01tkaZGxMK0PFpzlICgiZTGdvEg8gydK2Tv64Eg4FvwxTS8ojGIwi2MFhCQuXp/FgFmbpUCat" +
            "/OQLOk8BTiNcAy/bQz3FZxHV2ATJgDuy5VlwS7uYU71P5Xd2B1c1+8HoBoFetk4F9MkcgfKxoHOS+cAX7tWCbLOXGrkewVIy" +
            "Q6duSN6WbZNcPWxhVsyhYkIOp3l5UeeG3mA07Nci3HsvTSF8scf7a02UiNNZ+RrZiY6TVZn40QaQoKB25iCovqxQMUH1V4xs" +
            "sVJOV6eey10YU4pEFU/Y3YpxuwO19KF6hKeretNuWiXTnAetFrZNwSoQ7D3RTi7VNYpuP/BFNPftTm/jv9pOqJ1RKX6YE327" +
            "XJdaPijDD6z8E6f/putnFrqgH0dIaoM6JHVLc6ak/+qId2oR1ALum7c6AOK/9f4fOybONKrJcym4Q2tHN2rcb293Q7+7CI94" +
            "1em93dZ8E2MyE1ApWPiZ8t+kUxUwd+qk9yUfVeH/ISc6C1guqb5Fyuq1LRwgmbItONOVd2bZAfWZA8ptrWfVeJOl7TprtK29" +
            "WFJ1Ywlc+cL3tWgET/1hf58v/KhbvaDfJH38G9b6A74erZcFZvVvFOdAPy5oB7Joae2mE6/fhqcEXqh/25tbrRe3urrVA9VL" +
            "bd52b0e3A80Igg4u6fOD5luK0hyZSCCoMsp5fWwLAZ5qp6NpIz+Pl3r2VE+vs8+61AoU9Tbm6JBcjeEjZAgNZs7dKeJWOSV5" +
            "GTFneRRS/H7BzOeAaElhhLI+3ri14sCY1TtXVIg3W4mVoyOnFvr3hAFFEPaA3GGpfrtcfVymMIBIfN7xL3L2SQah1jHYNiEn" +
            "FHIUUI8mZX7JMbVar+D70F2ub1uaW9v+eqe79eY3lwl1hx+1+B5RzX21rfFfcxLOl9xzg2o5LmEFcmVe4utbt765093c+ZUX" +
            "5TlZrOPyX7b+hd/c6ueTZ5hnKOyPPiIGrjRaiNwRSz14xwZQRVNp9t42KIx4PlGJC9wo6YpPPARZL+KeBGTHEUcVdcuoQqQW" +
            "vC41eL+DaX/Z2tnVDVjmpa83Bnp+0KLujlud7c1ttLp6+37k+bmOKImmjub7QnIHYU3nJUWk6Z1NGcT8wQHtDGXlzhF3ZOBu" +
            "pCfsv6tdujHgD2AY02lp/uudLqzBK9iCmt80Tw6N2ZSS0r/5yOJfOKFQu0e8+PZOW/MNb9s/P54Az1QUjxwxw5a3UocUmbyP" +
            "ytCNbeqTlKeoBkHOX920bo6ogVbJNnZM9bXtGCi0q4tPZGyRjxXpPgUHP8o069WFON3tOiq5HAnCw+jZ29GCp+abFBipDEYl" +
            "kV9CbOLZBGrsbE599+VX+pt1kFSNHKJnJU38VAzq+yco3BExLSgHJEeFt0p7CAlWW6FTCZHjCaEahdda/BEVbxAmuPgSA0Fb" +
            "2hSqZlYBAqkLrRreZPKQ0GbZxoyqwZ2kZsCROhtUd+0QrsUNxAvaVXFLqJaHRtcBfjxzBKsv5quxJWG1ncCw8HbeICRs4ZRx" +
            "vt5HGJOJx3fospzaMYtBNN9uVfnzJEtQkJYVZv55pbxGHV67nrd7TkzFperlmILq9YwluZNv3w0k8VhLcaf8NGvsjEFK5ttJ" +
            "usqUf+zcGeKOl3NbkNCwKpo5+VeKG/ouHZe6fptcEwR/j2PI3OcFh4Stx47U7Rs6FSO5EGduamqp0tGCrPE3bGi/TeZEfRPv" +
            "Q0RlOge2XdSkUcgOPkBDc3ni/jSH4IVB2BIzShlkcEnmIfM0t/XAL/nD6xgljWdxs1ymJtCbHf1Jhlsb+i+LRv41HVAcrlrF" +
            "7Ngr4+UYi4x2zuzQTazSDHXdgVdXHqsqKGUPSsn953rpMZ0QF7dQmtKFMCypsoNzgUq1pKDdSmnY3H6mr2zCPPimlb0R2yRj" +
            "LHPjeK5eyy46UrAvp3W1fKOSau8fqOVYu015PGcWJvn6E7QF6xfKPpxLbV1ROk9RUylI9goeCxlTz2o56bIWFNZ9x0yBG7py" +
            "boi9BuRUVlaXMOjsBW4ct5qc3Le39LhrHWZww5VlT9ulGz9U6FEtCicGPQX2VH6+Vre96567xGBTNBQKRDzX+MrQdQ823NQU" +
            "8N99byxwXPReKNwX8fj4ddPvPrMf3d/7wuJ/LJjc1BQaiPYPRIWi7qHt8JO1cc8Pyt88fT5/kKKskuRCoTbcU6cSsqXlVxxd" +
            "COXaghBf/hcFRkcSXA6qjol1Ql5/QN1OTSdWfSnFqqc4hSDxekgvxmV61gFE7u8joaBLTqXqDUPwxWDWN08T6sYwQsUGoZQV" +
            "VEx58g3IGx+EvnZolLZZn2coC+paFjf1tWPxpy6K7SkXXZwpZYz4Nt1AXFmv5kqqMZiwb8gBbvf7v7N+kLksD2Ja3TuQ5Gs2" +
            "7Pi1u76WCalrYa4zaJHCOSFa50IeKAJchB/1h/zUILUuAI078qXRTU11Q/hKJxKNOvtVtvWTMoAGZ/2GL0T9NYGGy/zd2hd9" +
            "veK+ar+tkX5vGiuvwSqAapa/xDcD+VLNfS1YuxZ4zfJU5V/X2R7FNYx1QzX0uyfUq+GFo8frdbZbt8XzFnTmQrgzXbDxoYoi" +
            "69iYs1+zEZDkaEG3mqcMhXwWwCA2Uc0d8hGqauFZY35LbvBJNkKm66zxqE9X/vNz60YwBtSUTUesat98b5xPVjGiXq3ldWP0" +
            "oFIsua5Zl7ToDCR93X2NoQ2dJ+CnUBdXx6m2OdpAiqpXNeU2FD9Hi7YYKcGpX7V1CAYl5mQmbZ6OIAjDKmGhFFv1N7MAR+re" +
            "MEW8aiwGHVIVqi64U7iaSMAz+D0ygriKj9Y6fEGESlz7irDcXYC71QZ/qi4O2QGC0izd6VriwyLhDfugQbrCDCChDnZ6BJ3X" +
            "Z4acy0/EmJJAdWuOr6+xWOgQVQVXPhJBDrIOT+K7xnYaNSRkTS7Aa7OQrYNrdXJXi8NCnedQTjy7srqnQJnRYlPUXckiFHk8" +
            "JItFdedqUQcASqzI7TGZmnM+8aW29AjMjcxgfApidukzh/bdWqra9Lk9zl2cEAb5FTtR7Sf/8UH6DeIM5XxOzByiU8n6P0SA" +
            "9I0sXa4jCIBZEynrK4rDmR31Zwggt/5UQd4FuoWRHzNmF0mB6q4wXQeYeW0lreVVOmeaOaCbtqkknwUhLjuCxwBzbFMmCE7J" +
            "kWHCCNMvCKUdlcmX8oP62BQZYmqZfiotuejmeXYNgkcyJFyqDrXIGZUZpGViHoidFUlx/ISQCOVNxSzbltxYonMiZnbpSb00" +
            "TBBNxTl3oEIm60Q9bB1i2S1TxmukkQJhRwYhMA7S4T9DZvrWkLmdYHxmtabo4DWprxXVVUk6KHsWd/5e5LfhKcaZeEA8MQuD" +
            "BKGdeEUugq1TVCKJnmbtuyxL9VyYOZTZdLuuXR0QMWULIsaSnPFSVHkoZqrrTxSHg4wVqa2YTLOJmRNlZRx0TPiMxFkcQj1E" +
            "4KVuNX5J5NlvaJ8pRp8Mg50B6i9zxmAH3OcCB9YmAMqWSzxMlqad6fVbxHQX8CKjdGr8qk3B0PjEQiHzJeuUDeARz4UV9j3+" +
            "kxe6SxbbBuBFbKdaSzXBmQifHLxTyvyYkRG8hjPKiYIcXHaGk5uzdVeOF7AldYnHQuMYDSdWMEAVaOeAts058Me4Tcu+5+EU" +
            "CuSTVps6a9kiog/I0IVm/oMkyJNjr4LmTqWCMWr+OLeO31ufrdu2sjMWdIYRszCqz+3yplyODauzjALChGrcJ43lMYtbKDz+" +
            "RgXoV1hEnCHFt4XsuwODLGdq9U0U+BoQCKvYUDLzOYAhSl/qPkzlZNssbxHM+piGnJBeH+fZ2LBT+5oPb1TJaYflQZfITlco" +
            "WEyMM9uwUzKaMUuWLgbsZJP2hYtaePhfUEsHCNTL7v3nGgAAszYAAFBLAwQUAAgICAAAACEAAAAAAAAAAAAAAAAAHwAJAGVy" +
            "YXR3Lm15bW9kLmFzc2V0cy9pbWcvbG9nby5iaW5VVAUAAYA1zhLrDPBz5+WS4mJkYgYAUEsHCDFba8QNAAAACwAAAFBLAwQU" +
            "AAgICAAAACEAAAAAAAAAAAAAAAAACAAJAG1haW4uRVJCVVQFAAGANc4Sc3CK93D18fHncnb08VFwjPf3c9Uw1uQKCPL0C/FR" +
            "AEvFBziGOHtwBbmGhAb5KRhwAQBQSwcI+gV0szIAAAAzAAAAUEsBAhQAFAAICAgAAAAhAE5MsNE3AAAANgAAAA8ACQAAAAAA" +
            "AAAAAAAAAAAAAGVyYXR3LXBhY2suanNvblVUBQABgDXOElBLAQIUABQACAgIAAAAIQDUy+795xoAALM2AAAdAAkAAAAAAAAA" +
            "AAAAAH0AAABlcmF0dy5teW1vZC5hc3NldHMvUkVBRE1FLnR4dFVUBQABgDXOElBLAQIUABQACAgIAAAAIQAxW2vEDQAAAAsA" +
            "AAAfAAkAAAAAAAAAAAAAALgbAABlcmF0dy5teW1vZC5hc3NldHMvaW1nL2xvZ28uYmluVVQFAAGANc4SUEsBAhQAFAAICAgA" +
            "AAAhAPoFdLMyAAAAMwAAAAgACQAAAAAAAAAAAAAAGxwAAG1haW4uRVJCVVQFAAGANc4SUEsFBgAAAAAEAAQALwEAAIwcAAAA" +
            "AA=="
        // @eratw-pack-fixture-end

        /** 刷新旋钮的系统属性名（`-Deratw.refreshPackFixture=true`）。 */
        private const val REFRESH_FLAG = "eratw.refreshPackFixture"

        /** 刷新旋钮的等价环境变量名（Gradle 命令行 `-D` 默认不进 fork 的 test worker）。 */
        private const val REFRESH_ENV = "ERATW_REFRESH_PACK_FIXTURE"

        /** 夹具常量区起始锚点行（含行首缩进与换行：KDoc 散文里的同名字样不会被误认）。 */
        private const val FIXTURE_ANCHOR_LINE_BEGIN = "\n        // @eratw-pack-fixture-begin\n"

        /** 夹具常量区结束锚点行，形式同 [FIXTURE_ANCHOR_LINE_BEGIN]。 */
        private const val FIXTURE_ANCHOR_LINE_END = "\n        // @eratw-pack-fixture-end\n"

        /** 重写夹具常量的每行 Base64 字符数（`"…" +` 拼接线口径）。 */
        private const val FIXTURE_BASE64_LINE_WIDTH = 96

        /** 本文件里夹具常量区所在源文件（相对 `<worktree>/eratw-android`）。 */
        private const val APP_FIXTURE_SOURCE =
            "app/src/test/kotlin/com/eratw/port/android/ModImportAuthorPackEndToEndTest.kt"

        /** modgen 侧权威夹具常量区所在源文件（相对 `<worktree>/eratw-android`）。 */
        private const val MODGEN_FIXTURE_SOURCE =
            "tools/modgen/src/test/kotlin/com/eratw/port/modgen/AuthorClosedLoopSmokeTest.kt"

        /** 从锚点区里取 Base64 字面量的正则（`"…"` 逐段拼接，忽略 `+` 与换行）。 */
        private val BASE64_LITERAL = Regex("\"([A-Za-z0-9+/=]+)\"")

        /** 刷新旋钮：系统属性 `-Deratw.refreshPackFixture=true` 或环境变量等价入口置位时返回 true。 */
        private fun refreshRequested(): Boolean =
            System.getProperty(REFRESH_FLAG) == "true" || System.getenv(REFRESH_ENV) == "true"

        /** 测试用夹具字节：未置旋钮时直接解码 [FIXTURE_BASE64]（与加旋钮前逐位一致）。 */
        private fun fixtureZipForRun(): ByteArray {
            val base64 = if (refreshRequested()) refreshOwnFixtureFromModgenSource() else FIXTURE_BASE64
            return Base64.getDecoder().decode(base64)
        }

        /** 从 modgen 侧锚点区取权威 Base64（第一段真产物），重钉本文件锚点区后返回该串。 */
        private fun refreshOwnFixtureFromModgenSource(): String {
            val source = modgenSourceFile()
            require(Files.isRegularFile(source)) { "modgen 侧夹具源文件不存在：$source" }
            val base64 = readAnchorBase64(source)
            require(base64.isNotEmpty()) { "modgen 侧夹具锚点区为空：$source" }
            rewriteFixtureRegion(ownSourceFile(), fixtureBlock(base64))
            println(
                "pack-mod 夹具已从 $MODGEN_FIXTURE_SOURCE 重钉到 $APP_FIXTURE_SOURCE（${base64.length} 字符）；" +
                    "请去掉 $REFRESH_FLAG 重跑确认",
            )
            return base64
        }

        /** 取 [file] 锚点区里的 Base64 字面量（`"…"` 逐段拼接，忽略 `+` 与换行）。 */
        private fun readAnchorBase64(file: Path): String {
            val text = String(Files.readAllBytes(file), Charsets.UTF_8).replace("\r\n", "\n")
            val beginAt = text.indexOf(FIXTURE_ANCHOR_LINE_BEGIN)
            require(beginAt >= 0) { "夹具起始锚点缺失：$FIXTURE_ANCHOR_LINE_BEGIN @ $file" }
            val bodyStart = beginAt + FIXTURE_ANCHOR_LINE_BEGIN.length
            val endAt = text.indexOf(FIXTURE_ANCHOR_LINE_END, bodyStart)
            require(endAt >= 0) { "夹具结束锚点缺失：$FIXTURE_ANCHOR_LINE_END @ $file" }
            return BASE64_LITERAL.findAll(text.substring(bodyStart, endAt))
                .map { it.groupValues[1] }
                .joinToString("")
        }

        /** 渲染锚点区正文：常量声明 + 每行 96 字符的 `"…" +`（末行不带 `+`）。 */
        private fun fixtureBlock(base64: String): String {
            val chunks = base64.chunked(FIXTURE_BASE64_LINE_WIDTH)
            val body = chunks.mapIndexed { index, chunk ->
                val suffix = if (index == chunks.lastIndex) "" else " +"
                "            \"$chunk\"$suffix\n"
            }
            return "        private const val FIXTURE_BASE64: String =\n" + body.joinToString("")
        }

        /** 把 [file] 两个锚点行之间的正文换成 [block]；内容相同不落盘，换行符沿用原文件。 */
        private fun rewriteFixtureRegion(file: Path, block: String) {
            val raw = String(Files.readAllBytes(file), Charsets.UTF_8)
            val newline = if (raw.contains("\r\n")) "\r\n" else "\n"
            val text = raw.replace("\r\n", "\n")
            val beginAt = text.indexOf(FIXTURE_ANCHOR_LINE_BEGIN)
            require(beginAt >= 0) { "夹具起始锚点缺失：$FIXTURE_ANCHOR_LINE_BEGIN @ $file" }
            val bodyStart = beginAt + FIXTURE_ANCHOR_LINE_BEGIN.length
            val endAt = text.indexOf(FIXTURE_ANCHOR_LINE_END, bodyStart)
            require(endAt >= 0) { "夹具结束锚点缺失：$FIXTURE_ANCHOR_LINE_END @ $file" }
            val updated = (text.substring(0, bodyStart) + block + text.substring(endAt + 1))
                .replace("\n", newline)
            if (updated != raw) Files.write(file, updated.toByteArray(Charsets.UTF_8))
        }

        /** 从测试 JVM 的 cwd（`user.dir`）向上找 `settings.gradle.kts` ⇒ `<worktree>/eratw-android`。 */
        private fun gradleRoot(): Path {
            var dir: Path? = Path.of("").toAbsolutePath().normalize()
            while (dir != null) {
                if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))) return dir
                dir = dir.parent
            }
            error("找不到 settings.gradle.kts（cwd=${Path.of("")}）")
        }

        /** 本文件路径。 */
        private fun ownSourceFile(): Path = gradleRoot().resolve(APP_FIXTURE_SOURCE)

        /** modgen 侧权威夹具源文件路径。 */
        private fun modgenSourceFile(): Path = gradleRoot().resolve(MODGEN_FIXTURE_SOURCE)

    }
}
