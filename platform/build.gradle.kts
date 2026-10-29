plugins {
    id("com.android.library")
    kotlin("android")
}

// M4-SKEL：平台能力模块。M5 的触屏 / 音频 / 设置页绑定都落在本模块；
// 当前只提供真机数据根与 APK assets 读取口，core-vm 的启动接线从这里拿路径。
android {
    namespace = "com.eratw.port.platform"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
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

dependencies {
    // M5-A INPUT-BIND：手势/宏控制器直接复用 render/input 的预填-提交模型（纯 JVM）
    implementation("com.eratw.port:render")
    // M5-B 起平台侧接 core 的音频接口（SoundSourceResolver / ChannelDecoder）与设置条目表；
    // core-vm 是纯 JVM 库，不反向依赖 platform，无环。
    implementation("com.eratw.port:core-vm")
    // 纯 JVM 单测（M5-A：手势/宏控制器不碰 Android API）；Android 单测默认跑 JUnit4
    testImplementation("junit:junit:4.13.2")
}
