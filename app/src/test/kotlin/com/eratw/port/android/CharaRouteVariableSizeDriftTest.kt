package com.eratw.port.android

import com.eratw.port.core.data.csv.VariableSizeLoader
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableSizes
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.CharaRouteDecl
import com.eratw.port.modapi.ModManifest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VARIABLESIZE.CSV 漂移整包拒的**真改档装置**（CONTRACT-RT2-M54IMPL3 判据 4；刀 2 交付
 * TODO 收口）：系统族 CFLAG 改档后，全跨度路由的 freeze 派生（出厂 1000 格）与运行期
 * `def.dims`（改档 500）漂移，attach 终审两段式第二段整包拒并点名。
 *
 * 改档装置 = 测试内临时根（`VariableSize.csv` 写进临时目录，经生产装载器
 * [VariableSizeLoader] 进 [VariableRegistry]），不改共享根语料（AGENTS 铁律：语料一个
 * 字节都不许改）。freeze 侧只查出厂尺寸（DeclarationRegistry 域 10 KDoc 自证），运行期
 * 漂移只在 attach 终审露头——这正是本用例要钉的两段式分工。
 */
class CharaRouteVariableSizeDriftTest {

    @Test
    fun `variablesize改档_全跨度路由_attach终审整包拒点名`() {
        val root: Path = Files.createTempDirectory("rt2-m54d3-drift")
        val csvDir = Files.createDirectories(root.resolve("CSV"))
        Files.write(csvDir.resolve("VariableSize.csv"), "CFLAG,500\r\n".toByteArray())
        val sizes = VariableSizes()
        val warnings = mutableListOf<String>()
        VariableSizeLoader.load(csvDir, sizes, warnings)
        assertTrue("改档装置必须干净装载：$warnings", warnings.isEmpty())

        // 改档后的运行期 store：def.dims 是终审的权威（freeze 看不到它）。
        val store = VariableStore(VariableRegistry(sizes))
        assertEquals(
            "改档生效自证（出厂 1000 → 运行期 500）",
            500,
            store.registry.def("CFLAG")!!.dims[0],
        )

        // 全跨度路由声明：freeze 侧以出厂尺寸展开 1000 格（声明侧不写尺寸）。
        val modsDir = Files.createDirectories(root.resolve("mods").resolve("rt2"))
        val manifest = ModManifest(
            packageId = "drift.probe",
            charaRoutes = listOf(CharaRouteDecl("CFLAG")),
        )
        Files.write(
            modsDir.resolve("drift.manifest"),
            ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest)),
        )

        val logs = mutableListOf<String>()
        val e = assertThrows(IllegalStateException::class.java) {
            ModRuntimeAssembly.attach(
                gameRoot = root.toFile(),
                store = store,
                nativeRegistry = NativeMethodRegistry(),
                onVmThread = { true },
                enabled = true,
                logSink = { line -> logs += line },
                modProviders = emptyList(),
            )
        }
        assertTrue(
            "整包拒文案必须点名漂移：${e.message}",
            e.message!!.contains("整包拒") && e.message!!.contains("CFLAG"),
        )
        assertTrue(
            "终审必须点名运行期尺寸 500（freeze 只查出厂值 1000，两段式分工在此露头）",
            logs.any { it.contains("越出运行期尺寸 500") },
        )
    }

    @Test
    fun `同装置未漂移_全跨度路由attach放行_对照组`() {
        // 对照组：同一声明、出厂尺寸的 store（无改档）——attach 终审放行，整包拒只吃真漂移。
        val root: Path = Files.createTempDirectory("rt2-m54d3-drift-control")
        val modsDir = Files.createDirectories(root.resolve("mods").resolve("rt2"))
        val manifest = ModManifest(
            packageId = "drift.control",
            charaRoutes = listOf(CharaRouteDecl("CFLAG")),
        )
        Files.write(
            modsDir.resolve("control.manifest"),
            ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest)),
        )
        val logs = mutableListOf<String>()
        val session = checkNotNull(
            ModRuntimeAssembly.attach(
                gameRoot = root.toFile(),
                store = VariableStore(),
                nativeRegistry = NativeMethodRegistry(),
                onVmThread = { true },
                enabled = true,
                logSink = { line -> logs += line },
                modProviders = emptyList(),
            ),
        ) { "出厂尺寸下全跨度路由必须放行（对照组）；logs=$logs" }
        assertTrue(
            "出厂尺寸下全跨度 1000 格放行（对照组，排除『整包拒是路由本身引起』的误判）；logs=${logs.take(3)}",
            logs.any { it == "mod.attach charaRoutes routes=1000 fields=attached mirror=as-frozen" },
        )
        session.shutdown()
    }
}
