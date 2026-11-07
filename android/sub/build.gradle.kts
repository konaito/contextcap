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

    // 署名は :app と同じ固定の鍵でそろえる。~/.android/debug.keystore は AGP が黙って作り直すため、
    // 作り直されると端末の版に上書きできず（INSTALL_FAILED_UPDATE_INCOMPATIBLE）、
    // アンインストールすると端末内のデータが消える。置き場所は -Pcontextcap.keystore=<path> で変えられる。
    // 鍵が無い Mac では debug 鍵に戻して警告を出す（:app と同一のロジック）。
    val stableKeystore = file(
        providers.gradleProperty("contextcap.keystore").orNull
            ?: "${System.getProperty("user.home")}/.android-keystores/contextcap-debug.keystore",
    )
    signingConfigs {
        create("stable") {
            storeFile = stableKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    val signing = if (stableKeystore.isFile) {
        signingConfigs.getByName("stable")
    } else {
        logger.warn(
            "contextcap-sub: $stableKeystore が無いので ~/.android/debug.keystore で署名する。" +
                "端末の版と鍵が違うと上書きインストールできない",
        )
        signingConfigs.getByName("debug")
    }

    // 個人用の道具なので release も同じ鍵で署名する（:app と同じ方針）。
    buildTypes {
        debug {
            signingConfig = signing
        }
        release {
            signingConfig = signing
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
