plugins {
    id("com.android.library")
    kotlin("android")
}

// M4-SKEL：Android 绑定层。只做「DisplayList 的 DrawCmd → Canvas」这一段，
// 不持有语义（行号、滚动、命中全在 :render）。:render 是纯 JVM 模块，依赖方向仍是单向。
android {
    namespace = "com.eratw.port.render.canvas"
    compileSdk = 35

    defaultConfig {
        // 低版本兼容的承重点：minSdk 21（Android 5.0）由 app 的 core library desugaring 兜住
        // core-vm 用到的 java.nio.file / java.time（见 :app 的 desugar_jdk_libs_nio）。
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
}
