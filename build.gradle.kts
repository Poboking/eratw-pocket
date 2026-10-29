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
