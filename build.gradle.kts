// 根构建脚本：仅做插件版本声明与全模块公共配置
//
// AGP 8.8.2 的最低 Gradle 版本是 8.10.2（本仓 wrapper 正是该版本）。
plugins {
    kotlin("jvm") version "2.0.20" apply false
    kotlin("android") version "2.0.20" apply false
    // 壳层 Compose：编译器插件版本必须与 Kotlin 同版（2.0.20）。
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.android.application") version "8.8.2" apply false
    id("com.android.library") version "8.8.2" apply false
}

allprojects {
    group = "com.eratw.port"
    version = "0.1.0-SNAPSHOT"
}

// 失败摘要只给类名不给消息，排障要翻 XML 报告 —— 这里把失败用例的完整异常打进
// CI 日志（与引擎仓 Kotera 同一口径）：断言消息是判据的一部分，缺了就得重跑本地复现。
subprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }
}
