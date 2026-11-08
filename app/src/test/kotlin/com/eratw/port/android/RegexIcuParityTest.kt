package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正则引擎平台对齐哨兵：app/platform 源码里的 `Regex("…")` 字面量**不许含 `\{` / `\}`**。
 *
 * 为什么源码扫描而不是行为测试：Android 的 `java.util.regex` 是 **ICU** 实现，`\{` 直接
 * `PatternSyntaxException`；桌面 JVM（单测跑的地方）却容忍。同一份字面量一边能编译一边
 * 类加载即炸——`SettingsStrings` 的 `SLOT` 就这么把设置页在真机上点一次崩一次
 * （2026-10-30，`ExceptionInInitializerError`，桌面 :app 58/0 全绿没接住）。
 * 行为测试永远绿（OpenJDK 下它是对的），能抓这条的只有「拿两边都认的写法」这个口径本身。
 *
 * 合法替代：字符类 `[{]` / `[}]`（两边引擎同义）。
 *
 * 与 `AndroidApiCompatTest`（core 侧扫 android.jar 缺 API）同族：都是「桌面闸认证不了
 * 设备行为」的静态补位。
 */
class RegexIcuParityTest {

    @Test
    fun app与platform源码里的正则字面量不含反斜杠花括号() {
        val offenders = ArrayList<String>()
        for (module in listOf("app", "platform")) {
            val root = File("../$module/src/main/kotlin")
            if (!root.isDirectory) continue // 单模块跑测时另一模块可能不在位
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                f.readLines().forEachIndexed { idx, line ->
                    for (m in LITERAL.findAll(line)) {
                        // 源文本里的 `\\{` 是 Kotlin 转义后的 `\{` 正则转义花括号。
                        if (m.value.contains("\\\\{") || m.value.contains("\\\\}")) {
                            offenders += "${f.path}:${idx + 1}: ${m.value}"
                        }
                    }
                }
            }
        }
        assertTrue(
            "ICU（Android）不认 `\\{`/`\\}`，OpenJDK 容忍——桌面绿真机类加载即炸。" +
                "改字符类 `[{]`/`[}]`。违例：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private companion object {
        /** 抓 `Regex("……")` 的字符串字面量段（本仓正则全是一行字面量，无拼接）。 */
        val LITERAL = Regex("Regex\\(\"[^\"\n]*\"")
    }
}
