plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// M4-SKEL：平台能力模块。M5 的触屏 / 音频 / 设置页绑定都落在本模块；
// 当前只提供真机数据根与 APK assets 读取口，core-vm 的启动接线从这里拿路径。
android {
    namespace = "com.eratw.port.platform"
    // A-5：本波唯一放行的值变化 —— compileSdk 35→36，对齐 app 的 36
    // （catalog 的 versions.compileSdk）。
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    testOptions {
        unitTests {
            // GameImageFiles 的并发单测在纯 JVM 上跑：android.jar 桩默认「调用即抛 Stub!」，
            // 这里改成返回缺省值（Log.i 静默、BitmapFactory.decodeFile 返回 null），
            // 让「索引并发读写」这条数据竞争守卫可不依赖真机验证。
            isReturnDefaultValues = true
        }
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

dependencies {
    // M5-A INPUT-BIND：手势/宏控制器直接复用 render/input 的预填-提交模型（纯 JVM）
    implementation("com.eratw.port:render")
    // M5-B 起平台侧接 core 的音频接口（SoundSourceResolver / ChannelDecoder）与设置条目表；
    // core-vm 是纯 JVM 库，不反向依赖 platform，无环。
    implementation("com.eratw.port:core-vm")
    // 纯 JVM 单测（M5-A：手势/宏控制器不碰 Android API）；Android 单测默认跑 JUnit4
    testImplementation(libs.junit4)
    // 素材包解包：官方 BGM 资源包以 .7z（LZMA2 solid）分发，JDK 的 ZipFile 读不了；
    // commons-compress 是纯 JVM 实现，xz 供 LZMA2 解码，两者都不碰 Android API。
    implementation(libs.commons.compress)
    implementation(libs.xz)
}
