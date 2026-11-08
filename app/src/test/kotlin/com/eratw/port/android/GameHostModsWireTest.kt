package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2-M0 / R7-C4 的 app 侧接线哨兵（2026-11-07）。
 *
 * `GameHost` 挂在 Android 运行时上（app 的 JVM 单测面没有 Robolectric，实例化不了），
 * 与 [GameHostSharpWireTest] 同族用**源码面**钉——生产会话构造点必须把 mods 两参
 * 真的传给 `GameSession`，否则正式包「导入 Mod → 重启生效」链是断的（此前只有测试在传）。
 *
 * 钉住四件事：
 * 1. mods 目录与导入端同源（`ModStore.modsDirFor(gameRoot)`）；
 * 2. base SHA 取 `.meta` 侧车声明（`metadata.programSha256`），不重启哈希 100+ MiB；
 * 3. 侧车无声明时两参皆 null（fail-closed：无身份校验的补丁不加载，轨迹与接线前一致）；
 * 4. 取值发生在 `GameSession` 构造之前（metadata 已在装配段读出）。
 */
class GameHostModsWireTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `生产会话接线传入 modsDirectory 与 sidecar base SHA`() {
        val src = sourceOf("GameHost.kt")
        assertTrue(
            "mods 目录必须与导入端同源（ModStore.modsDirFor）且挂在 sha 安全调用下",
            src.contains(
                "modsDirectory = metadata.programSha256?.let { ModStore.modsDirFor(gameRoot).toPath() }",
            ),
        )
        assertTrue(
            "base SHA 必须取 .meta 侧车声明（小写化）",
            src.contains("baseProgramSha256 = metadata.programSha256?.lowercase()"),
        )
        assertTrue(
            "ModStore 必须有导入",
            src.contains("import com.eratw.port.platform.storage.ModStore"),
        )
    }

    @Test
    fun `侧车缺 sha 时两参皆 null 且取值先于会话构造`() {
        val src = sourceOf("GameHost.kt")
        // 安全调用形态保证：sha 缺失 ⇒ modsDirectory 也是 null（不会单独放开目录）。
        assertFalse(
            "不得出现无条件放开的 modsDirectory（缺 sha 会撞 GameSession 的 requireNotNull）",
            src.contains("modsDirectory = ModStore.modsDirFor(gameRoot).toPath()"),
        )
        val metaAt = src.indexOf("val metadata = metaTask?.await() ?: BootMetadata.read")
        val sessionAt = src.indexOf("val fresh = GameSession(")
        assertTrue("装配段必须读出 metadata", metaAt >= 0)
        assertTrue("必须构造 GameSession", sessionAt > metaAt)
        val modsAt = src.indexOf("modsDirectory = metadata.programSha256")
        assertTrue("mods 接线必须在会话构造点内", modsAt > sessionAt)
    }
}
