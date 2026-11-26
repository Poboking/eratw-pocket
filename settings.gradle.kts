// eratw-pocket 根工程 —— EraTW 安卓移植版（玩家端壳层）
//
// 引擎不在这里：core-vm / erbcc / render 在姊妹仓 Kotera，
// 以 composite build 方式接入（需与本仓同级检出：../Kotera）。
//
// 模块：
//   :render-canvas  Android 自绘 View 绑定（ConsoleScreen 命中/绘制）
//   :platform       平台能力（资源解包 / 存档 / 音频 / 设置 / 字体绑定）
//   :app            Compose 壳层 + 装配 + 出包

pluginManagement {
    repositories {
        // AGP 8.8.2 在 Google Maven；阿里云镜像作兜底/加速
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

rootProject.name = "eratw-pocket"

include(":render-canvas")
include(":platform")
include(":app")

// 引擎仓：core-vm / render / tools:erbcc / sqlite-adapter / mod-* 都在这边。
// 自动替换 com.eratw.port:* → Kotera 的对应工程。
includeBuild("../Kotera") {
    dependencySubstitution {
        substitute(module("com.eratw.port:core-vm")).using(project(":core-vm"))
        substitute(module("com.eratw.port:render")).using(project(":render"))
        substitute(module("com.eratw.port:erbcc")).using(project(":tools:erbcc"))
        // SQLite 真实驱动适配器与 RT2 Mod SDK（mod-*）都在引擎仓，纯 JVM。
        substitute(module("com.eratw.port:sqlite-adapter")).using(project(":sqlite-adapter"))
        substitute(module("com.eratw.port:mod-api")).using(project(":mod-api"))
        substitute(module("com.eratw.port:mod-family")).using(project(":mod-family"))
        substitute(module("com.eratw.port:mod-memory")).using(project(":mod-memory"))
        substitute(module("com.eratw.port:mod-livingworld")).using(project(":mod-livingworld"))
        // 载体注册表：构建期扫各 mod-* 的 @ModCarrier 生成 ModRegistry（宿主不再逐个点名载体模块）。
        // 与 app 侧 implementation("com.eratw.port:mod-registry") 配套。
        substitute(module("com.eratw.port:mod-registry")).using(project(":mod-registry"))
    }
}
