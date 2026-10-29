// eratw-pocket 根工程 —— EraTW 安卓移植版（玩家端壳层）
//
// 引擎不在这里：core-vm / erbcc / render 在姊妹仓 eraKcore，
// 以 composite build 方式接入（需与本仓同级检出：../eraKcore）。
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

// 引擎仓：core-vm / render / tools:erbcc 都在这边。
// 自动替换 com.eratw.port:core-vm / :render → eraKcore 的对应工程。
includeBuild("../eraKcore") {
    dependencySubstitution {
        substitute(module("com.eratw.port:core-vm")).using(project(":core-vm"))
        substitute(module("com.eratw.port:render")).using(project(":render"))
    }
}
