package com.eratw.port.android

import com.eratw.port.core.proc.EngineStamp
import com.eratw.port.core.proc.EngineStampRead
import com.eratw.port.platform.storage.GameStorage

/**
 * APK 内构建期印章的**读取壳**（ENGC-2）。
 *
 * 解析与三态判定全在 core 的 [EngineStamp]（纯 JVM，单测在 `:core-vm`）；本对象只把
 * assets 字节读成文本。**读失败也转成 Malformed**：印章存在与否决定「跑哪家引擎」，
 * 读不出来时静默用缺省档等于把出包通道的问题伪装成 eraTW 包（D14 的同一取向）。
 *
 * 调用纪律：只在 `GameHost` 构造期（UI 线程）调用一次，只读几字节；VM 线程不再碰
 * assets（与 liveReflow / disableBadApple 的「装配期一次取好」同款）。
 */
internal object EngineProfileStamp {

    fun read(storage: GameStorage): EngineStampRead = read { storage.openAsset(it) }

    /**
     * 纯读入版本：`open` 返回 null = 文件不存在；抛异常 = 读失败（同样转 Malformed）。
     *
     * 单独暴露是为了让 `:app` 的普通 JVM 单测用一个字节流钉住「缺失 / 非法 / 读失败」
     * 三态，而不需要造 `GameStorage`（它要 Android `Context`）。
     */
    fun read(open: (String) -> java.io.InputStream?): EngineStampRead {
        val text = try {
            open(EngineStamp.ASSET_NAME)?.use { input ->
                String(input.readBytes(), Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            return EngineStampRead.Malformed(
                "",
                "asset read failed: ${t.javaClass.simpleName}: ${t.message ?: ""}",
            )
        }
        return EngineStamp.parse(text)
    }
}
