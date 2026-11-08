// buildSrc 是独立小构建：不继承主工程 settings 的 pluginManagement / dependencyResolutionManagement，
// 仓库列表与主工程 settings.gradle.kts 逐行同源（buildSrc 解析 Kotlin 插件与测试依赖用）。
pluginManagement {
    repositories {
        // AGP/KGP 在 Google Maven；阿里云 google 镜像与 gradle-plugin 镜像作兜底/加速
        maven("https://maven.aliyun.com/repository/google")
        google()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        google()
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}
rootProject.name = "eratw-buildsrc"
