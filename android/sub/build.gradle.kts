plugins {
    id("com.android.application")
}

// contextcap-sub：俺用の companion アプリ。
// WRITE_SECURE_SETTINGS（adb で 1 回付与）を持ち、UsageStats で前面アプリを監視して、
// 決済アプリの前面時だけ ContextCap 本体（app.imichat.contextcap/.CaptureService）の
// アクセシビリティを自動で OFF/ON する。Compose も ML Kit も使わないので素の View のみ。
android {
    namespace = "app.imichat.contextcap.sub"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.imichat.contextcap.sub"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // 個人用の道具なので release も debug 鍵で署名する（:app と同じ方針）。
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
}
