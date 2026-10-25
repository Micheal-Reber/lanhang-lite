plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = providers.gradleProperty("lanhangReleaseStoreFile")
val releaseStorePassword = providers.gradleProperty("lanhangReleaseStorePassword")
val releaseKeyAlias = providers.gradleProperty("lanhangReleaseKeyAlias")
val releaseKeyPassword = providers.gradleProperty("lanhangReleaseKeyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it.isPresent }

android {
    namespace = "com.lanhang.lite"
    // Miuix 0.9.1 的 AAR 元数据要求调用方 compileSdk >= 37
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lanhang.lite"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.create("configuredRelease") {
                    storeFile = file(releaseStoreFile.get())
                    storePassword = releaseStorePassword.get()
                    keyAlias = releaseKeyAlias.get()
                    keyPassword = releaseKeyPassword.get()
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Compose：2026-10-24 起承载 Miuix 设计体系的界面（用户指定引入 miuix）
    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.activity:activity-compose:1.9.2")
    // Miuix 的 OverlayDialog 依赖 androidx.navigationevent（返回键处理）。它本来就在
    // miuix-ui 的运行时依赖里，只是不作为 api 暴露；这里仅 compileOnly 引用，
    // 不往 APK 里加任何东西，作用是给 ComposeView 补一个 LocalNavigationEventDispatcherOwner。
    compileOnly("androidx.navigationevent:navigationevent-compose:1.1.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // coil 的 Compose 扩展（与既有 coil 2.6.0 同版本，不引入新的图片加载栈）
    implementation("io.coil-kt:coil-compose:2.6.0")
    // Miuix：用户指定的设计库（github.com/compose-miuix-ui/miuix）
    // 0.9.1 = 与项目 Kotlin 2.3.21 / Compose 1.11 对齐的版本
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.1")
}
