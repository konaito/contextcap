pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ContextCap"
include(":app")

// 俺用の companion アプリ。WRITE_SECURE_SETTINGS（adb で 1 回付与）を持ち、
// 決済アプリの前面時だけ ContextCap のアクセシビリティを自動で OFF/ON する。
// 配布版の :app には一切の特権権限を入れないため、別 applicationId の別アプリに切り出す。
include(":sub")
