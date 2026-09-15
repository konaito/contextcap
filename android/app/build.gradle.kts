plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.imichat.contextcap"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.imichat.contextcap"
        minSdk = 33

        // ML Kit の bundled モデルはネイティブライブラリを ABI ごとに持つ。
        // 4 ABI 全部入れると release APK が 44.4MB（うち 39MB が lib/）になるので、
        // 実機とこのプロジェクトで使うエミュレータ（sdk_gphone64_arm64）に合わせて arm64 に絞る。
        // x86_64 のエミュレータで動かす必要が出たら "x86_64" を足す（+11MB）。
        ndk { abiFilters += "arm64-v8a" }
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // 個人用の道具なので release も debug 鍵で署名する。
    // これで R8 を通した APK をそのまま実機で確認・運用できる。
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            // Compose は未使用コードが大量に入るため R8 を必ず通す。
            // 切ったままだと APK が 23MB になる（実測）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
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
    // UI は Compose + Material 3 Expressive。
    // 撮影パス（CaptureService / Compactor / CaptureFile / StatsStore / CaptureBrowser /
    // CaptureThumbnail）はこれらに一切依存しない。
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3:1.5.0-alpha26")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.activity:activity-compose:1.13.0")
    // 推移的に入っているが、KTX 拡張に直接依存するので明示する
    implementation("androidx.core:core-ktx:1.19.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // OCR は ML Kit の日本語モデルを **bundled** で入れる。
    // play-services-mlkit-* (unbundled) は初回にモデルをネットワーク取得し Play Services を
    // 必須にするので、「ネットワーク送信なし・すべてローカル」という前提と噛み合わない。
    // 代償として APK が数 MB 増える（release で R8 を通した実測値は README に書く）。
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    // AGP 9 の built-in Kotlin では kotlin("test") にバージョンが付かず解決に失敗するので、
    // AGP が内蔵する KGP と同じバージョン (2.2.10) を明示する。
    // kotlin-test だけでは kotlin.test.Test が解決できない（JUnit バックエンドが要る）ため
    // kotlin-test-junit も入れる。
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.10")
}
