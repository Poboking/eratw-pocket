package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.BoolSettingDecl
import com.eratw.port.modapi.EnumSettingDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.IntSettingDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.StrSettingDecl
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * per-mod 设置的 app 侧端到端行为面（RT2 per-mod 配置，Lane D）。
 *
 * 与 [ModRuntimeAssemblyWireTest]（宿主装配 / 时间源 / 诊断）分文件：本类只钉「设置链路」——
 * 设备侧文件 → attach 装配期注入 → mod 的 `context.settings` 读到什么。判据四条：
 *
 * 1. 玩家改过的值覆盖作者声明默认值（按包落盘，跨 attach / 跨存档保留）；
 * 2. 未改的键读声明默认值；
 * 3. 配置文件被删 / 坏 → 默认值且不崩（attach 照常成功）；
 * 4. 读发生在 `onLoad`（adopt 段之后立即可用），不是「下次才生效」。
 *
 * 直接打生产缝 [ModRuntimeAssembly.attach]（缺省 newHost），因此这同时证明
 * `host.settings` 的注入点在 `install` 之前。
 */
class ModSettingsAttachWireTest {

    private val logs: MutableList<String> = mutableListOf()

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-settings-root")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, fileName: String, manifest: ModManifest) {
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    private fun attach(
        root: Path,
        providers: List<Pair<String, EraMod>>,
    ): ModRuntimeSession? = ModRuntimeAssembly.attach(
        gameRoot = root.toFile(),
        store = VariableStore(),
        nativeRegistry = NativeMethodRegistry(),
        onVmThread = { true },
        enabled = true,
        logSink = { line -> logs += line },
        modProviders = providers,
        newHost = { inputs ->
            ModRuntimeHost(
                snapshot = inputs.snapshot,
                entityKernel = inputs.entityKernel,
                nativeMethods = inputs.nativeMethods,
                logSink = inputs.logSink,
                runtime = inputs.runtime,
                shortIdOf = inputs.shortIdOf,
                intVarView = inputs.intVarView,
                strVarView = inputs.strVarView,
                storage = inputs.storage,
            )
        },
    )

    /**
     * onLoad 把 `context.settings` 的四个类型化读数抄进 [reads] 的 mod（per-mod 配置 e2e 载体）。
     *
     * 断言口径：读数必须在 `onLoad`（即 adopt 段之后）就能取到 —— 宿主在 install 之前注入
     * 按包快照；没有注入时这里会拿到 fail-closed 异常而不是默认值。
     */
    private class SettingsProbeMod(private val reads: MutableMap<String, String>) : EraMod {
        override fun onLoad(context: ModContext) {
            reads["volume"] = context.settings.int("cfg.volume").toString()
            reads["auto"] = context.settings.bool("cfg.auto").toString()
            reads["label"] = context.settings.str("cfg.label")
            reads["mode"] = context.settings.enumName("cfg.mode")
            reads["keys"] = context.settings.keys.joinToString(",")
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    @Test
    fun `生产缝在install前注入按包设置快照 改值下次attach生效`() {
        val root = tempGameRoot()
        val rt2 = root.resolve("mods").resolve("rt2").toFile()
        writeManifest(
            root,
            "mod_settings.manifest",
            ModManifest(
                packageId = "mod.settings",
                displayName = "带设置项的包",
                settings = listOf(
                    IntSettingDecl("cfg.volume", default = 5L, min = 0L, max = 10L, title = "音量"),
                    BoolSettingDecl("cfg.auto", default = true, title = "自动"),
                    StrSettingDecl("cfg.label", default = "默认标签", title = "标签"),
                    EnumSettingDecl("cfg.mode", default = "b", candidates = listOf("a", "b"), title = "模式"),
                ),
            ),
        )
        assertTrue(
            "先落一份设备侧取值（模拟玩家在设置页改过）",
            ModSettingsStore.write(rt2, "mod.settings", mapOf("cfg.volume" to "8", "cfg.mode" to "a")),
        )

        val first = mutableMapOf<String, String>()
        val firstSession = checkNotNull(
            attach(root, listOf("mod.settings" to SettingsProbeMod(first))),
        ) { "开态必须产出运行时" }
        assertEquals("持久化值必须覆盖声明默认值", "8", first["volume"])
        assertEquals("a", first["mode"])
        assertEquals("未改的键回落默认值", "true", first["auto"])
        assertEquals("默认标签", first["label"])
        assertEquals(
            "keys 只报声明的键且按声明序",
            "cfg.volume,cfg.auto,cfg.label,cfg.mode",
            first["keys"],
        )
        firstSession.shutdown()

        // 改值 → 下一次 attach（= 下次开局）读到新值：配置按包落盘、不随存档回滚。
        assertTrue(ModSettingsStore.write(rt2, "mod.settings", mapOf("cfg.volume" to "3", "cfg.mode" to "b")))
        val second = mutableMapOf<String, String>()
        val secondSession = checkNotNull(
            attach(root, listOf("mod.settings" to SettingsProbeMod(second))),
        ) { "开态必须产出运行时" }
        assertEquals("3", second["volume"])
        assertEquals("b", second["mode"])
        secondSession.shutdown()

        // 配置文件被删（玩家清数据 / 首次安装）→ 默认值，且不崩。
        assertTrue(ModSettingsStore.settingsFile(rt2, "mod.settings").delete())
        val third = mutableMapOf<String, String>()
        val thirdSession = checkNotNull(
            attach(root, listOf("mod.settings" to SettingsProbeMod(third))),
        ) { "缺配置文件不得挡 attach" }
        assertEquals("5", third["volume"])
        assertEquals("b", third["mode"])
        assertEquals("true", third["auto"])
        thirdSession.shutdown()
    }
}
