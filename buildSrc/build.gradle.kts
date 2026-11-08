// eratw-android 的 buildSrc：放 app 构建脚本用的纯函数与任务类（A-2 波抽出的三刀）。
//
// 版本纪律：这里**接不到**主工程的 gradle/libs.versions.toml（buildSrc 是独立构建，
// dependencyResolutionManagement 与主构建的 catalog 都不传导进来），所以依赖版本写字面值；
// 字面值与主工程 catalog 逐项对齐（kotlin = 2.0.20、junit4 = 4.13.2、jvmTarget = 17），
// 漂移时两边一起改——这是「buildSrc 自带 build 脚本」形态下唯一可守的单源口径。
plugins {
    kotlin("jvm") version "2.0.20"
}

dependencies {
    // 任务类/纯函数要用 org.gradle.api（GradleException、Logger、DefaultTask…）。
    // buildSrc 用 kotlin("jvm") 时 API 不会自动进编译类路径（kotlin-dsl 插件才会隐式带），
    // 显式声明一份；运行时由 Gradle 自己的 loader 提供，版本永远与本构建一致。
    implementation(gradleApi())
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(17)
    targetCompatibility = JavaVersion.toVersion(17)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
