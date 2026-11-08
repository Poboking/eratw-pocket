plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// M4-SKEL：Android 绑定层。只做「DisplayList 的 DrawCmd → Canvas」这一段，
// 不持有语义（行号、滚动、命中全在 :render）。:render 是纯 JVM 模块，依赖方向仍是单向。
android {
    namespace = "com.eratw.port.render.canvas"
    // A-5 白名单：仍锁 35 —— 本波零行为变化纪律只放行 platform 35→36 一处对齐；
    // 本模块跟涨到 36（catalog 的 versions.compileSdk）须由组长单独决策，未动。
    compileSdk = 35

    defaultConfig {
        // 低版本兼容的承重点：minSdk 21（Android 5.0）由 app 的 core library desugaring 兜住
        // core-vm 用到的 java.nio.file / java.time（见 :app 的 desugar_jdk_libs_nio）。
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
    }
}

// 字体随包：把 eraKcore :render 的成品与度量表拷进本模块 assets，避免 1.1 MB 二进制在仓内出现第二份。
// AGP 的 assets 合并任务挂在 preBuild 之后，所以这里只需让 preBuild 依赖同步任务。
val renderFontAssets = layout.buildDirectory.dir("generated/renderFontAssets")
val syncRenderFonts = tasks.register<Sync>("syncRenderFonts") {
    from(rootProject.file("../eraKcore/render/src/main/resources/fonts"))
    into(renderFontAssets.map { it.dir("fonts") })
}
android.sourceSets.getByName("main").assets.srcDir(renderFontAssets)
tasks.named("preBuild").configure { dependsOn(syncRenderFonts) }

dependencies {
    // 对外暴露 ConsoleScreen / DisplayList 类型，app 不必再单独依赖 eraKcore 的 :render
    api("com.eratw.port:render")
    // `:render` 对 `:core-vm` 是 implementation，但它的**公共 API** 里出现了 core 类型
    // （`GraphicsLayer.cbgLayers: List<CbgLayerSnapshot>`、`ScreenScene.cbgLayers` —— CBG 图层的
    // 契约 DTO 就是 core 那一份，render 不复制字段以免两处漂移）。所以本模块绘制 CBG 图层时
    // 必须能解析该类型 ⇒ 直接依赖 :core-vm（方向仍是单向 canvas → render/core，无反向依赖）。
    // 2026-11-06 实测：缺这一行 `:render-canvas:compileDebugKotlin` 报
    // `Cannot access class 'com.eratw.port.core.host.CbgLayerSnapshot'`。
    implementation("com.eratw.port:core-vm")
    // W6-mappingcolor：取色几何单元（MappingColorGeometry / MappingColorScan）是纯 JVM 的，
    // 同坐标对拍用例走 JUnit5（口径与 :render 的测试面一致，不需要 Robolectric/真机）。
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
