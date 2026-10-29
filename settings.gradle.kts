pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}

rootProject.name = "LanhangLite"
include(":app")

/**
 * 闭源网络层模块。
 *
 * 源码不在公开仓（见 .gitignore 的 `engine/`），只在本地存在时参与构建。
 * 没有该目录时 app 退回依赖 `app/libs/engine.aar`（同样不入库）。
 * 这样公开仓 clone 下来是「读得到业务代码、读不到接口地址」的状态。
 */
if (file("engine").exists()) {
    include(":engine")
}
