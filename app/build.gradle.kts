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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lanhang.lite"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.9.8"
    }

    /**
     * 两个版本（2026-10-31 用户决定）：
     * - **lite**：轻量版，不含私信，维持 3 MB 左右；
     * - **im**：带私信（融云 IMLib 私有云）。IMLib 的 aar 自带 so，只保留 arm64-v8a，
     *   预计 +2.7 MB（so 1.46 MB + dex 约 1.3 MB）。
     *
     * im 版加了 `.im` 后缀：两个包**可以共存安装**，代价是各自登录一次、课表缓存各一份
     * （SharedPreferences / filesDir 按 applicationId 隔离，互不影响）。
     *
     * 共享代码里一律只看 `R.bool.has_im` 决定要不要显示私信入口，
     * `src/main` 里**不出现任何 IM 符号** —— 这条是保证 lite 版真的不带 IMLib 的唯一手段，
     * 一旦 main 里 import 了 io.rong.*，lite 变体要么编译失败，要么把 SDK 拖进包体。
     */
    flavorDimensions += "edition"
    productFlavors {
        create("lite") {
            dimension = "edition"
        }
        create("im") {
            dimension = "edition"
            applicationIdSuffix = ".im"
            // IMLib 的 so 在 v7a 上还要多 0.8 MB，而这个学校场景基本见不到 32 位设备；
            // 只留 arm64 的代价是 32 位机装不上（会解析失败），换来包体小一档
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
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

    androidResources {
        // 只保留 App 实际提供的两种语言。Material / AppCompat 自带 100+ 语言的翻译，
        // 这些字符串全打在 resources.arsc 里，而本 App 没有对应译文 —— 纯死重量。
        // 默认配置（values/，中文）始终保留，无需列出。
        localeFilters += listOf("zh", "en")
    }

    packaging {
        resources {
            // coroutines 的调试探针（DebugProbesKt.bin）和 Kotlin 编译期元数据
            // 都只在开发期有用，装进包里纯属死重量
            excludes += setOf("DebugProbesKt.bin", "kotlin-tooling-metadata.json")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 闭源网络层（学校接口域名/路径、校园网认证协议、会话与 RSA 公钥）。
    // 源码不在公开仓，见 settings.gradle.kts 与 .gitignore 的 `engine/`。
    if (findProject(":engine") != null) {
        implementation(project(":engine"))
    } else {
        // 没有任何 engine 来源时，与其让人对着一屏 "Unresolved reference: RceApi" 猜，
        // 不如直接说明缺什么、去哪里拿。
        if (!file("libs/engine.aar").exists()) {
            throw GradleException(
                """
                |缺少闭源网络层模块，无法构建。
                |
                |本项目把学校接口地址与校园网认证实现放在未公开的 :engine 模块里，
                |公开仓只包含业务代码。构建需要满足下面任一条件：
                |  1) 仓库根目录存在 engine/（私有开发环境）；
                |  2) app/libs/engine.aar 存在（预编译产物）。
                |
                |两者的来源见 README「关于闭源模块」。只读源码了解实现不需要构建。
                """.trimMargin()
            )
        }
        implementation(files("libs/engine.aar"))
    }

    // 私信：融云 IMLib **2.9.24**（本地 aar，不走 Maven）。
    // 为什么不走 Maven：学校私有云 navi 只认 2.9 这一代（官方蓝航 1.0.14 内置 2.9.19），
    // 5.x/4.x 连上去直接 RC_CONN_TOKEN_INCORRECT；且 aar 里有两处必须自己 patch
    // （PendingIntent flags=0 崩 :ipc 进程、support-compat 与 androidx 撞类），
    // 详见 AGENTS.md §5「已批准例外」。
    // 只有 im flavor 带它 —— lite 变体连编译都碰不到，这是轻量版体积的唯一保证。
    if (file("libs/im_lib-2.9.24-patched.aar").exists()) {
        "imImplementation"(files("libs/im_lib-2.9.24-patched.aar"))
    }

    // 1.17.0：Android 16「实时动态通知」（NotificationCompat.setRequestPromotedOngoing）
    // 从 1.16 起才有；1.13.1 上没有这个方法。属于已有依赖的版本升级，不是新依赖。
    implementation("androidx.core:core-ktx:1.17.0")
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

    // 身份码二维码/条形码编码（用户批准的破例，见 AGENTS.md §5）
    implementation("com.google.zxing:core:3.5.3")

    // Compose（液态玻璃底栏，照抄 PocketDiary）
    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // AndroidLiquidGlass：backdrop 模糊/透镜折射/色散
    implementation("io.github.kyant0:backdrop:1.0.6")
}
