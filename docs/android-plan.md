# ContextCap Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android 端末の画面を 10 秒に 1 回撮ってローカルに溜め続けるだけの常駐アプリを作り、MiaChat の正解データをスマホ利用文脈からも収集できるようにする。

**Architecture:** `AccessibilityService.takeScreenshot()` を単一の常駐点として使う。AccessibilityService は system_server にバインドされた OS 管理プロセスなので、ForegroundService も常時通知もバッテリー最適化の除外も持たない。撮影・保存・容量管理はすべてこのサービス内で完結し、UI は状態表示用の Activity 1 枚だけ。

**Tech Stack:** Kotlin / Android Gradle Plugin / 実行時依存ライブラリなし（Compose・AndroidX・WorkManager をいずれも使わない）。テストは JVM unit test のみ。

**Spec:** `docs/superpowers/specs/2026-08-13-contextcap-android-design.md`

## Global Constraints

- パッケージ名 / applicationId: `app.imichat.contextcap`（macOS 版の bundle id と同一）
- ディレクトリ: リポジトリルート直下の `android/`
- `minSdk = 30`（`takeScreenshot` の要件）/ `compileSdk = 36` / `targetSdk = 36`
- **実行時依存ライブラリを追加しない。** `dependencies` に書いてよいのは `testImplementation` のみ
- Jetpack Compose、AndroidX、WorkManager、Coroutines ライブラリを使わない。`Handler` と `java.util.concurrent.Executor` で組む
- 撮影間隔 10 秒 / 容量上限 10GB（超過したら 5GB まで押し戻す）
- 保存の命名規約は macOS 版と**完全に同一**: `<root>/YYYY-MM-DD/HHmmss_SSS[.gN].jpg`
- 撮影時刻はファイル属性ではなく**パスから解釈する**（再圧縮でファイルが作り直されても壊れないため）
- 縮小は**すべて長辺基準**。macOS 版は撮影時が幅基準・再圧縮時が長辺基準で食い違っているが、横長ディスプレイでは同じ値になるので顕在化していないだけ。縦長のスマホでは長辺に統一する
- ネットワーク送信機構を実装しない。`INTERNET` permission を宣言しない
- コード内コメントは日本語。macOS 版（`macos/Sources/ContextCap/`）の記述密度に合わせる

---

## File Structure

```
android/
├── README.md                        # Task 8
├── settings.gradle.kts              # Task 1
├── build.gradle.kts                 # Task 1
├── gradle.properties                # Task 1
├── app/
│   ├── build.gradle.kts             # Task 1
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml  # Task 1
│       │   ├── res/
│       │   │   ├── values/strings.xml            # Task 1
│       │   │   └── xml/accessibility_service_config.xml  # Task 1
│       │   └── java/app/imichat/contextcap/
│       │       ├── CaptureService.kt   # Task 4, 5
│       │       ├── CompressionRung.kt  # Task 2
│       │       ├── CaptureFile.kt      # Task 2
│       │       ├── StatsStore.kt       # Task 3
│       │       ├── Compactor.kt        # Task 6
│       │       └── SetupActivity.kt    # Task 7
│       └── test/java/app/imichat/contextcap/
│           ├── CaptureFileTest.kt      # Task 2
│           ├── StatsStoreTest.kt       # Task 3
│           └── CompactorTest.kt        # Task 6
└── scripts/
    └── install.sh                   # Task 8
```

責務の分割は macOS 版と 1 対 1 対応させる。`CaptureService.kt` だけが Android API に依存し、
`CaptureFile` / `CompressionRung` / `StatsStore` / `Compactor` は
`android.graphics.Bitmap` 以外の Android 依存を持たない純粋ロジックにする
（そのため JVM unit test だけで検証できる）。

`Compactor` は JPEG のデコード・エンコードに `Bitmap` を使うため、
**画像処理を関数として外から注入する**設計にしてテスト可能にする（Task 6 で詳述）。

---

### Task 1: プロジェクト scaffold と環境の実測

**このタスクの目的は「動く空 APK」ではなく、後続タスクが依存する 3 つの未確定事項を実測で確定させること。**
確定させるまで他のタスクに進まない。

1. Gradle が Android Studio 同梱の JBR 21 でビルドできるか（Homebrew の Java 26 では AGP が動かない見込み）
2. AGP と Kotlin プラグインの実際に解決できるバージョン
3. `getExternalFilesDir` に書いたファイルが `adb pull` で回収できるか

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/xml/accessibility_service_config.xml`
- Create: `android/app/src/main/java/app/imichat/contextcap/CaptureService.kt`
- Create: `android/app/src/main/java/app/imichat/contextcap/SetupActivity.kt`

**Interfaces:**
- Consumes: なし（最初のタスク）
- Produces: ビルドが通るプロジェクト。`CaptureService` は Task 4 で撮影処理を追加する土台。
  確定した保存先ルートの取得方法は Task 3 以降の `StatsStore` / `Compactor` が使う

- [ ] **Step 1: Gradle wrapper を生成する**

Android Studio 同梱の Gradle を使って wrapper を作る。ローカルに `gradle` コマンドはない。

```bash
mkdir -p /Users/konaito/Documents/imi-chat/android
cd /Users/konaito/Documents/imi-chat/android
find "/Applications/Android Studio.app" -name "gradle" -type f -perm +111 2>/dev/null | head -3
```

見つかった Gradle バイナリで `gradle wrapper` を実行する。見つからない場合は
`brew install gradle` ではなく、`https://services.gradle.org/distributions/` から
`gradle-8.14-bin.zip` 相当を取得して一度だけ使い、wrapper を生成したら削除する。

**この時点で使える Gradle のバージョンを記録する。以降の AGP 選定に効く。**

- [ ] **Step 2: JDK を JBR 21 に固定する**

`android/gradle.properties`:

```properties
# Homebrew の Java 26 では AGP が動かないため、Android Studio 同梱の JBR 21 に固定する
org.gradle.java.home=/Applications/Android Studio.app/Contents/jbr/Contents/Home
org.gradle.jvmargs=-Xmx2048m
```

- [ ] **Step 3: Gradle プロジェクトを定義する**

`android/settings.gradle.kts`:

```kotlin
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
```

`android/build.gradle.kts`:

```kotlin
// AGP 9.0 以降は Kotlin サポートが AGP に内蔵されているため、
// org.jetbrains.kotlin.android プラグインは宣言しない（宣言するとビルドが失敗する）。
// https://developer.android.com/build/releases/agp-9-0-0-release-notes
plugins {
    id("com.android.application") version "9.3.0" apply false
}
```

**Kotlin プラグインを足さないこと。** AGP 9.0 以降で宣言すると次のエラーで落ちる。

```
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```

Kotlin のバージョン指定も不要（AGP が KGP 2.2.10 を内蔵する）。

`android/app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
}

android {
    namespace = "app.imichat.contextcap"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.imichat.contextcap"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // 実行時依存は追加しない。テスト用のみ
    testImplementation(kotlin("test"))
}
```

- [ ] **Step 4: Manifest とアクセシビリティ設定を書く**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="false">

        <activity
            android:name=".SetupActivity"
            android:exported="true"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".CaptureService"
            android:exported="true"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>
    </application>
</manifest>
```

`INTERNET` permission は宣言しない（ネットワーク送信機構を持たないため）。

`app/src/main/res/xml/accessibility_service_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  アクセシビリティイベントは一切購読しない。takeScreenshot() を使うためだけに
  AccessibilityService を利用している。accessibilityEventTypes を指定しない場合の
  デフォルトは 0（購読なし）。
-->
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canTakeScreenshot="true"
    android:description="@string/accessibility_description"
    android:notificationTimeout="0" />
```

`app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">ContextCap</string>
    <string name="accessibility_description">画面を 10 秒ごとに撮影して端末内に保存します。操作の代行や送信は行いません。</string>
</resources>
```

- [ ] **Step 5: 最小の Service と Activity を書いてビルドする**

`app/src/main/java/app/imichat/contextcap/CaptureService.kt`:

```kotlin
package app.imichat.contextcap

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.io.File

/// 画面を一定間隔で撮影して端末内に保存する常駐サービス。
/// AccessibilityService 自体が OS 管理の常駐プロセスなので、ForegroundService は持たない。
class CaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "connected. root=${captureRoot().absolutePath}")
    }

    // イベントは一切購読しない
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /// 保存先ルート。Task 1 の実測で確定する
    private fun captureRoot(): File {
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "ContextCap").apply { mkdirs() }
    }

    companion object {
        const val TAG = "ContextCap"
    }
}
```

`app/src/main/java/app/imichat/contextcap/SetupActivity.kt`:

```kotlin
package app.imichat.contextcap

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/// 権限状態と統計を表示するだけの 1 画面。Task 7 で中身を作り込む。
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(48, 96, 48, 48)
            text = "ContextCap"
        }
        setContentView(text)
    }
}
```

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。失敗したら Step 3 のバージョンを対応表で確認して直す

- [ ] **Step 6: エミュレータを起動してインストールする**

```bash
~/Library/Android/sdk/emulator/emulator -avd Medium_Phone -no-snapshot-load &
adb wait-for-device
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 7: 制限付き設定を解除してサービスを有効化する**

Android 13+ はサイドロードしたアプリのアクセシビリティ有効化がブロックされる。

```bash
adb shell appops set app.imichat.contextcap ACCESS_RESTRICTED_SETTINGS allow
adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS
```

エミュレータの画面で ContextCap を有効化する。

Run: `adb logcat -d -s ContextCap`
Expected: `connected. root=/storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap`

- [ ] **Step 8: 保存先を実測で確定する（このタスクの本題）**

サービスが作ったディレクトリに対して `adb pull` が通るか確認する。

```bash
adb shell "run-as app.imichat.contextcap touch /storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap/probe.txt" 2>&1
adb pull /storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap/ /tmp/contextcap-probe
```

**判定:**
- pull が成功 → `getExternalFilesDir` を保存先として確定。以降のタスクはこのまま進む
- `Permission denied` → 下の切り替えを行い、**spec の「保存先」節を実測結果で更新する**

切り替える場合の変更点:
1. `AndroidManifest.xml` の `<application>` の前に追加

```xml
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

2. `CaptureService.captureRoot()` を差し替え

```kotlin
    /// 保存先ルート。adb pull を確実に通すため外部ストレージ直下に置く。
    /// MANAGE_EXTERNAL_STORAGE の許可が要る（scripts/install.sh が付与する）。
    private fun captureRoot(): File =
        File(android.os.Environment.getExternalStorageDirectory(), "ContextCap").apply { mkdirs() }
```

3. 許可を与える

```bash
adb shell appops set app.imichat.contextcap MANAGE_EXTERNAL_STORAGE allow
```

4. もう一度 `adb pull` して通ることを確認する

- [ ] **Step 9: 実測結果を spec に反映してコミットする**

`docs/superpowers/specs/2026-08-13-contextcap-android-design.md` の
「保存先（着手直後に実測で確定する）」節と「ビルドと配布」節を、
実際に確定した値（保存先パス、AGP / Kotlin / Gradle のバージョン）で書き換える。
**「〜の見込み」「〜する予定」という記述を残さない。**

```bash
cd /Users/konaito/Documents/imi-chat
git add android docs/superpowers/specs/2026-08-13-contextcap-android-design.md docs/superpowers/plans/2026-08-13-contextcap-android.md
git commit -m "feat: ContextCap Android の scaffold と環境実測"
```

---

### Task 2: CompressionRung と CaptureFile

命名規約とパースの純粋ロジック。Android 依存がないので JVM unit test で TDD する。

**Files:**
- Create: `android/app/src/main/java/app/imichat/contextcap/CompressionRung.kt`
- Create: `android/app/src/main/java/app/imichat/contextcap/CaptureFile.kt`
- Test: `android/app/src/test/java/app/imichat/contextcap/CaptureFileTest.kt`

**Interfaces:**
- Consumes: Task 1 のプロジェクト構成
- Produces:
  - `CompressionRung(gen: Int, suffix: String, maxPixel: Int, quality: Int)` — data class
  - `CompressionRung.LADDER: List<CompressionRung>`
  - `CompressionRung.forGen(gen: Int): CompressionRung?`
  - `CompressionRung.DEEPEST_GEN: Int`
  - `CompressionRung.label: String`
  - `CaptureFile.generationOf(file: File): Int`
  - `CaptureFile.baseNameOf(file: File): String`
  - `CaptureFile.captureDateOf(file: File): Date?`
  - `CaptureFile.dayDirName(date: Date): String`
  - `CaptureFile.timeStem(date: Date): String`

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/app/imichat/contextcap/CaptureFileTest.kt`:

```kotlin
package app.imichat.contextcap

import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureFileTest {

    @Test
    fun `接尾辞のないファイルは gen0`() {
        assertEquals(0, CaptureFile.generationOf(File("/root/2026-08-13/162303_273.jpg")))
    }

    @Test
    fun `g2 接尾辞から世代を読む`() {
        assertEquals(2, CaptureFile.generationOf(File("/root/2026-08-13/162303_273.g2.jpg")))
    }

    @Test
    fun `基底名は gen 接尾辞を除いたもの`() {
        assertEquals("162303_273", CaptureFile.baseNameOf(File("/root/2026-08-13/162303_273.g1.jpg")))
        assertEquals("162303_273", CaptureFile.baseNameOf(File("/root/2026-08-13/162303_273.jpg")))
    }

    @Test
    fun `撮影時刻をパスから解釈する`() {
        val date = CaptureFile.captureDateOf(File("/root/2026-08-13/162303_273.g2.jpg"))
        val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply { time = date!! }
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(7, cal.get(Calendar.MONTH)) // 0-indexed なので 8 月は 7
        assertEquals(13, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(16, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(23, cal.get(Calendar.MINUTE))
        assertEquals(3, cal.get(Calendar.SECOND))
        assertEquals(273, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `規約外の名前は null`() {
        assertNull(CaptureFile.captureDateOf(File("/root/notadate/whatever.jpg")))
    }

    @Test
    fun `日付ディレクトリ名と時刻ステムを生成できる`() {
        val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply {
            set(2026, 7, 13, 16, 23, 3)
            set(Calendar.MILLISECOND, 273)
        }
        assertEquals("2026-08-13", CaptureFile.dayDirName(cal.time))
        assertEquals("162303_273", CaptureFile.timeStem(cal.time))
    }

    @Test
    fun `生成した名前を読み戻せる`() {
        val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US).apply {
            set(2026, 7, 13, 16, 23, 3)
            set(Calendar.MILLISECOND, 273)
        }
        val file = File("/root/${CaptureFile.dayDirName(cal.time)}/${CaptureFile.timeStem(cal.time)}.jpg")
        assertEquals(cal.time.time, CaptureFile.captureDateOf(file)!!.time)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*CaptureFileTest*'`
Expected: FAIL（`CaptureFile` が未定義でコンパイルエラー）

- [ ] **Step 3: CompressionRung を実装する**

`app/src/main/java/app/imichat/contextcap/CompressionRung.kt`:

```kotlin
package app.imichat.contextcap

/// 圧縮の段。gen0（等倍 q75）からここに並ぶ順で深くなる。
/// Compactor の再圧縮と CaptureService の適応撮影の両方が参照する。
///
/// maxPixel は macOS 版と揃えて**長辺**の最大ピクセル数。
/// macOS 版は撮影時だけ幅基準になっているが、横長ディスプレイでは同値なので
/// 顕在化していないだけ。縦長のスマホでは長辺で統一する。
data class CompressionRung(
    val gen: Int,
    val suffix: String,
    val maxPixel: Int,
    /// Bitmap.compress に渡す 0-100 の値（macOS 版の 0.0-1.0 を 100 倍したもの）
    val quality: Int,
) {
    val label: String get() = "長辺$maxPixel q$quality ($suffix)"

    companion object {
        val LADDER: List<CompressionRung> = listOf(
            CompressionRung(gen = 1, suffix = "g1", maxPixel = 1920, quality = 60),
            CompressionRung(gen = 2, suffix = "g2", maxPixel = 1280, quality = 50),
            CompressionRung(gen = 3, suffix = "g3", maxPixel = 800, quality = 40),
        )

        /// gen0（等倍）の JPEG 品質
        const val FULL_RES_QUALITY = 75

        val DEEPEST_GEN: Int get() = LADDER.last().gen

        fun forGen(gen: Int): CompressionRung? = LADDER.firstOrNull { it.gen == gen }

        fun labelForGen(gen: Int): String =
            forGen(gen)?.label ?: "等倍 q$FULL_RES_QUALITY"
    }
}
```

- [ ] **Step 4: CaptureFile を実装する**

`app/src/main/java/app/imichat/contextcap/CaptureFile.kt`:

```kotlin
package app.imichat.contextcap

import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// 保存ファイルの命名規約: <root>/YYYY-MM-DD/HHmmss_SSS[.gN].jpg
/// 撮影時刻はファイル属性ではなくパスから解釈する（再圧縮でファイルが作り直されても不変）。
object CaptureFile {
    const val DAY_FORMAT = "yyyy-MM-dd"
    const val TIME_FORMAT = "HHmmss_SSS"

    // SimpleDateFormat はスレッドセーフでないため都度生成する
    private fun parser() = SimpleDateFormat("$DAY_FORMAT $TIME_FORMAT", Locale.US)

    fun dayDirName(date: Date): String = SimpleDateFormat(DAY_FORMAT, Locale.US).format(date)

    fun timeStem(date: Date): String = SimpleDateFormat(TIME_FORMAT, Locale.US).format(date)

    /// "162303_273.jpg" → 0, "162303_273.g2.jpg" → 2
    fun generationOf(file: File): Int {
        val stem = file.name.substringBeforeLast('.')
        for (rung in CompressionRung.LADDER) {
            if (stem.endsWith(".${rung.suffix}")) return rung.gen
        }
        return 0
    }

    /// gen 接尾辞を除いた基底名（"162303_273.g1" → "162303_273"）
    fun baseNameOf(file: File): String {
        val stem = file.name.substringBeforeLast('.')
        for (rung in CompressionRung.LADDER) {
            if (stem.endsWith(".${rung.suffix}")) {
                return stem.dropLast(rung.suffix.length + 1)
            }
        }
        return stem
    }

    /// 親ディレクトリ名 + 基底名から撮影時刻を解釈する。規約外の名前なら null
    fun captureDateOf(file: File): Date? {
        val day = file.parentFile?.name ?: return null
        return try {
            parser().parse("$day ${baseNameOf(file)}")
        } catch (e: ParseException) {
            null
        }
    }
}
```

- [ ] **Step 5: テストが通ることを確認する**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*CaptureFileTest*'`
Expected: PASS（6 テストすべて）

`SimpleDateFormat` は lenient がデフォルト有効なので、`notadate` のような文字列でも
例外にならず誤ったパースをする可能性がある。テストが落ちたら `parser()` に
`isLenient = false` を設定して再実行する。

- [ ] **Step 6: コミットする**

```bash
cd /Users/konaito/Documents/imi-chat
git add android/app/src/main/java/app/imichat/contextcap/CompressionRung.kt \
        android/app/src/main/java/app/imichat/contextcap/CaptureFile.kt \
        android/app/src/test/java/app/imichat/contextcap/CaptureFileTest.kt
git commit -m "feat: ContextCap Android の命名規約と圧縮段を実装"
```

---

### Task 3: StatsStore

保存済みスクショの枚数・期間・容量の集計。ファイルシステムだけを触るので JVM unit test で TDD する。

**Files:**
- Create: `android/app/src/main/java/app/imichat/contextcap/StatsStore.kt`
- Test: `android/app/src/test/java/app/imichat/contextcap/StatsStoreTest.kt`

**Interfaces:**
- Consumes: `CaptureFile.captureDateOf`（Task 2）
- Produces:
  - `StatsStore(root: File)`
  - `StatsStore.count: Int` / `totalBytes: Long` / `firstDate: Date?` / `lastDate: Date?`
  - `StatsStore.rescan()`
  - `StatsStore.recordCapture(bytes: Long, date: Date)`
  - `StatsStore.countText: String` / `sizeText: String` / `durationText: String`

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/app/imichat/contextcap/StatsStoreTest.kt`:

```kotlin
package app.imichat.contextcap

import java.io.File
import java.nio.file.Files
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsStoreTest {

    private val root: File = Files.createTempDirectory("contextcap-stats").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun write(day: String, stem: String, bytes: Int) {
        val dir = File(root, day).apply { mkdirs() }
        File(dir, "$stem.jpg").writeBytes(ByteArray(bytes))
    }

    @Test
    fun `空ディレクトリは 0 枚`() {
        val stats = StatsStore(root)
        assertEquals(0, stats.count)
        assertEquals(0L, stats.totalBytes)
        assertEquals(null, stats.firstDate)
    }

    @Test
    fun `フルスキャンで枚数と容量を数える`() {
        write("2026-08-13", "100000_000", 100)
        write("2026-08-13", "100010_000", 200)
        write("2026-08-14", "100020_000", 300)

        val stats = StatsStore(root)
        assertEquals(3, stats.count)
        assertEquals(600L, stats.totalBytes)
    }

    @Test
    fun `jpg 以外は数えない`() {
        write("2026-08-13", "100000_000", 100)
        File(root, "2026-08-13/notes.txt").writeBytes(ByteArray(999))

        val stats = StatsStore(root)
        assertEquals(1, stats.count)
        assertEquals(100L, stats.totalBytes)
    }

    @Test
    fun `最初と最後の撮影時刻をパスから求める`() {
        write("2026-08-13", "100000_000", 10)
        write("2026-08-15", "230000_000", 10)
        write("2026-08-14", "120000_000", 10)

        val stats = StatsStore(root)
        assertEquals(
            CaptureFile.captureDateOf(File(root, "2026-08-13/100000_000.jpg"))!!.time,
            stats.firstDate!!.time,
        )
        assertEquals(
            CaptureFile.captureDateOf(File(root, "2026-08-15/230000_000.jpg"))!!.time,
            stats.lastDate!!.time,
        )
    }

    @Test
    fun `増分更新で枚数と容量が増える`() {
        write("2026-08-13", "100000_000", 100)
        val stats = StatsStore(root)
        stats.recordCapture(bytes = 50, date = Date())

        assertEquals(2, stats.count)
        assertEquals(150L, stats.totalBytes)
    }

    @Test
    fun `記録期間は日と時間で表示する`() {
        write("2026-08-13", "100000_000", 10)
        write("2026-08-16", "140000_000", 10)

        val stats = StatsStore(root)
        assertEquals("3日と4時間", stats.durationText)
    }

    @Test
    fun `記録が 1 枚だけなら期間はダッシュ`() {
        write("2026-08-13", "100000_000", 10)
        assertEquals("—", StatsStore(root).durationText)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*StatsStoreTest*'`
Expected: FAIL（`StatsStore` が未定義）

- [ ] **Step 3: StatsStore を実装する**

`app/src/main/java/app/imichat/contextcap/StatsStore.kt`:

```kotlin
package app.imichat.contextcap

import java.io.File
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/// 保存済みスクショの枚数・期間・容量を集計する。
/// 生成時にフルスキャンし、以降は撮影ごとに増分更新する。
class StatsStore(val root: File) {

    var count: Int = 0
        private set
    var totalBytes: Long = 0
        private set
    var firstDate: Date? = null
        private set
    var lastDate: Date? = null
        private set

    init {
        rescan()
    }

    /// ディレクトリをフルスキャンして実測値に合わせる
    fun rescan() {
        var newCount = 0
        var newBytes = 0L
        var newFirst: Date? = null
        var newLast: Date? = null

        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "jpg" }
            .forEach { file ->
                newCount += 1
                newBytes += file.length()
                val captured = CaptureFile.captureDateOf(file) ?: Date(file.lastModified())
                if (newFirst == null || captured.before(newFirst)) newFirst = captured
                if (newLast == null || captured.after(newLast)) newLast = captured
            }

        count = newCount
        totalBytes = newBytes
        firstDate = newFirst
        lastDate = newLast
    }

    /// 撮影 1 枚分の増分更新
    fun recordCapture(bytes: Long, date: Date) {
        count += 1
        totalBytes += bytes
        if (firstDate == null) firstDate = date
        lastDate = date
    }

    // MARK: - 表示用フォーマット

    val countText: String
        get() = "${NumberFormat.getIntegerInstance(Locale.US).format(count)} 枚"

    val sizeText: String get() = formatBytes(totalBytes)

    val durationText: String
        get() {
            val first = firstDate ?: return "—"
            val last = lastDate ?: return "—"
            if (!last.after(first)) return "—"

            val seconds = (last.time - first.time) / 1000
            val days = seconds / 86_400
            val hours = (seconds % 86_400) / 3_600
            val minutes = (seconds % 3_600) / 60
            return when {
                days > 0 -> "${days}日と${hours}時間"
                hours > 0 -> "${hours}時間${minutes}分"
                else -> "${minutes}分"
            }
        }

    companion object {
        /// macOS 版の ByteCountFormatter(.file) に合わせて 1000 進で表示する
        fun formatBytes(bytes: Long): String {
            if (bytes < 1000) return "$bytes bytes"
            val units = listOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1000
            var index = 0
            while (value >= 1000 && index < units.size - 1) {
                value /= 1000
                index += 1
            }
            return String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*StatsStoreTest*'`
Expected: PASS（7 テストすべて）

- [ ] **Step 5: コミットする**

```bash
cd /Users/konaito/Documents/imi-chat
git add android/app/src/main/java/app/imichat/contextcap/StatsStore.kt \
        android/app/src/test/java/app/imichat/contextcap/StatsStoreTest.kt
git commit -m "feat: ContextCap Android の統計集計を実装"
```

---

### Task 4: 撮影と保存の最小ループ

`takeScreenshot` を 10 秒間隔で回して JPEG 保存する。ここだけは実機・エミュレータでしか検証できない。

**Files:**
- Modify: `android/app/src/main/java/app/imichat/contextcap/CaptureService.kt`（全面的に書き換え）

**Interfaces:**
- Consumes: `CaptureFile.dayDirName` / `timeStem`（Task 2）、`CompressionRung.forGen` /
  `FULL_RES_QUALITY`（Task 2）、`StatsStore(root)` / `recordCapture`（Task 3）
- Produces:
  - `CaptureService.captureRoot(context: Context): File`（companion object。Task 7 の Activity が使う）
  - `CaptureService.PREFS_NAME` / `KEY_CAPTURE_GEN` / `KEY_PAUSED`（SharedPreferences のキー。Task 5, 6, 7 が使う）

- [ ] **Step 1: CaptureService を撮影ループ付きに書き換える**

```kotlin
package app.imichat.contextcap

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.util.Date
import java.util.concurrent.Executors

/// 画面を一定間隔で撮影して端末内に保存する常駐サービス。
/// AccessibilityService 自体が OS 管理の常駐プロセスなので、ForegroundService は持たない。
///
/// 次の tick は「撮影 → 保存」の完了後にスケジュールするので、
/// 別途 in-flight guard を持たなくても直列性が保たれる（macOS 版の in-flight guard と同じ思想）。
class CaptureService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    /// 撮影のコールバック受けと JPEG 保存を行う。メインスレッドを I/O で塞がない
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var stats: StatsStore
    /// Compactor のスレッドから書き換わり、撮影スレッドから読まれるので volatile にする
    @Volatile
    private var captureGen: Int = 0
    private var running = false
    /// 最後に撮影サイクルが完了した時刻。watchdog が tick の停止を検出するのに使う
    private var lastCycleAt = 0L

    private val tickRunnable = Runnable { captureOnce() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val root = captureRoot(this)
        captureGen = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CAPTURE_GEN, 0)
        worker.execute {
            stats = StatsStore(root)
            handler.post { start() }
        }
        Log.i(TAG, "connected. root=${root.absolutePath} gen=$captureGen")
    }

    override fun onDestroy() {
        stop()
        worker.shutdown()
        super.onDestroy()
    }

    // イベントは一切購読しない
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // MARK: - 制御

    private fun start() {
        if (running) return
        running = true
        handler.post(tickRunnable) // 有効化直後にも 1 枚撮る
    }

    private fun stop() {
        running = false
        handler.removeCallbacks(tickRunnable)
    }

    private fun scheduleNext() {
        lastCycleAt = System.currentTimeMillis()
        if (!running) return
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, INTERVAL_MS)
    }

    /// 撮影ループは callback 駆動なので、takeScreenshot が callback を呼ばずに終わると
    /// 二度と再開しない。放置前提のアプリでは静かに死ぬのが最悪なので、
    /// 一定時間サイクルが完了していなければ tick を叩き直す。
    private fun restartIfStalled() {
        if (!running) return
        val silence = System.currentTimeMillis() - lastCycleAt
        if (silence < STALL_THRESHOLD_MS) return
        Log.w(TAG, "tick stalled for ${silence}ms. restarting")
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    // MARK: - 撮影

    private fun captureOnce() {
        if (!running) return
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            worker,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace,
                        )
                        if (bitmap == null) {
                            Log.w(TAG, "wrapHardwareBuffer returned null")
                            return
                        }
                        val now = Date()
                        val bytes = save(bitmap, now)
                        stats.recordCapture(bytes, now)
                    } catch (e: Exception) {
                        Log.w(TAG, "save failed", e)
                    } finally {
                        // 解放しないとバッファが枯渇して以降の撮影が全部落ちる
                        screenshot.hardwareBuffer.close()
                        handler.post { scheduleNext() }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    // 失敗しても無視して次の tick へ
                    // （※実測では FLAG_SECURE でもエラーは返らず、黒塗り画像が保存された）
                    if (errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) {
                        Log.e(TAG, "accessibility access lost")
                    } else {
                        Log.d(TAG, "screenshot failed: $errorCode")
                    }
                    handler.post { scheduleNext() }
                }
            },
        )
    }

    /// 採用中の段があれば撮影段階で縮小し、gen 接尾辞を付けて保存する
    /// （接尾辞を付けることで Compactor の二度掛けを防ぐ）。
    private fun save(bitmap: Bitmap, date: Date): Long {
        val rung = CompressionRung.forGen(captureGen)
        val quality = rung?.quality ?: CompressionRung.FULL_RES_QUALITY

        val scaled = if (rung != null) scaleToLongestSide(bitmap, rung.maxPixel) else bitmap

        val dayDir = File(captureRoot(this), CaptureFile.dayDirName(date)).apply { mkdirs() }
        val stem = CaptureFile.timeStem(date) + (rung?.let { ".${it.suffix}" } ?: "")
        val target = File(dayDir, "$stem.jpg")
        // 書き込み途中のファイルを回収されないよう、一時ファイルに書いてから rename する
        val tmp = File(dayDir, "$stem.jpg.tmp")

        tmp.outputStream().use { out ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw IllegalStateException("JPEG エンコード失敗")
            }
        }
        if (scaled !== bitmap) scaled.recycle()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("rename 失敗: ${target.absolutePath}")
        }
        return target.length()
    }

    /// 長辺が maxPixel を超える場合だけ縮小する。
    /// HARDWARE config の Bitmap は直接スケールできないので ARGB_8888 にコピーしてから縮小する。
    private fun scaleToLongestSide(bitmap: Bitmap, maxPixel: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxPixel) return bitmap
        val ratio = maxPixel.toDouble() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return bitmap
        } else {
            bitmap
        }
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (source !== bitmap) source.recycle()
        return scaled
    }

    companion object {
        const val TAG = "ContextCap"
        const val PREFS_NAME = "contextcap"
        const val KEY_CAPTURE_GEN = "CaptureGeneration"
        const val KEY_PAUSED = "Paused"

        /// 撮影間隔。macOS 版は 5 秒だが、スマホは容量制約から 10 秒にする
        const val INTERVAL_MS = 10_000L

        /// これだけサイクルが完了していなければ tick が死んだとみなして叩き直す
        const val STALL_THRESHOLD_MS = 60_000L

        /// 保存先ルート。Task 1 の実測で確定した方式に合わせる
        fun captureRoot(context: Context): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "ContextCap").apply { mkdirs() }
        }
    }
}
```

**Task 1 Step 8 で `MANAGE_EXTERNAL_STORAGE` 方式に切り替えていた場合は、
`captureRoot` を Task 1 で確定した実装に合わせること。**

- [ ] **Step 2: ビルドしてインストールする**

```bash
cd /Users/konaito/Documents/imi-chat/android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

インストール後にアクセシビリティを一度 OFF→ON する（サービスの再接続が必要）。

- [ ] **Step 3: 10 秒ごとにファイルが増えることを確認する**

```bash
sleep 45
adb shell ls -l /storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap/*/
```

Expected: `HHmmss_SSS.jpg` が 4〜5 枚。ファイルサイズが 0 でないこと

- [ ] **Step 4: 撮影された画像が実際に見られることを確認する**

```bash
adb pull /storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap /tmp/contextcap-check
ls -lh /tmp/contextcap-check/*/
```

**pull した JPEG を Read tool で実際に開いて、エミュレータの画面が写っていることを目視で確認する。**
真っ黒・真っ白・破損していたら `wrapHardwareBuffer` か `compress` の問題なので先に進まない。

- [ ] **Step 5: HardwareBuffer が枯渇しないことを確認する**

```bash
adb logcat -c
sleep 300
adb logcat -d -s ContextCap | tail -20
```

Expected: `save failed` や `screenshot failed` が連続していないこと。
30 枚前後が保存され続けていれば `close()` が効いている

- [ ] **Step 6: コミットする**

```bash
cd /Users/konaito/Documents/imi-chat
git add android/app/src/main/java/app/imichat/contextcap/CaptureService.kt
git commit -m "feat: ContextCap Android の撮影ループを実装"
```

---

### Task 5: 画面 ON/OFF 連動と一時停止

スマホは 1 日の大半が画面 OFF で、撮っても黒画面のゴミが溜まるだけなので tick を止める。

**Files:**
- Modify: `android/app/src/main/java/app/imichat/contextcap/CaptureService.kt`

**Interfaces:**
- Consumes: Task 4 の `CaptureService`
- Produces: `CaptureService.ACTION_SETTINGS_CHANGED`（Task 7 の Activity が一時停止トグルで送る broadcast）

- [ ] **Step 1: BroadcastReceiver を追加する**

`CaptureService` に以下を追加する。`ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` は
manifest 静的登録ができないので、必ず動的登録する。

import に追加:

```kotlin
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
```

フィールドに追加:

```kotlin
    /// 画面 ON/OFF と設定変更を受ける。ACTION_SCREEN_* は manifest 静的登録できないので動的登録する
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "screen on")
                    if (!isPaused()) start()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "screen off")
                    stop()
                }
                ACTION_SETTINGS_CHANGED -> {
                    if (isPaused()) stop() else start()
                }
            }
        }
    }

    private fun isPaused(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PAUSED, false)
```

`onServiceConnected()` の `worker.execute { ... }` の直前に登録を追加:

```kotlin
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ACTION_SETTINGS_CHANGED)
        }
        // 3 引数版 registerReceiver は API 33 で追加。minSdk 30 なので分岐が要る。
        // API 34+ では自アプリ内 broadcast の受信に RECEIVER_NOT_EXPORTED の明示が必須
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(systemReceiver, filter)
        }
```

`onDestroy()` の先頭に解除を追加:

```kotlin
        runCatching { unregisterReceiver(systemReceiver) }
```

`start()` の先頭を、一時停止中は動かないように変更:

```kotlin
    private fun start() {
        if (running || isPaused()) return
        running = true
        handler.post(tickRunnable) // 有効化直後にも 1 枚撮る
    }
```

companion object に追加:

```kotlin
        /// SetupActivity の一時停止トグルから送られる
        const val ACTION_SETTINGS_CHANGED = "app.imichat.contextcap.SETTINGS_CHANGED"
```

- [ ] **Step 2: ビルドしてインストールする**

```bash
cd /Users/konaito/Documents/imi-chat/android
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

アクセシビリティを OFF→ON して再接続する。

- [ ] **Step 3: 画面 OFF で tick が止まることを確認する**

```bash
adb logcat -c
adb shell input keyevent KEYCODE_SLEEP
sleep 40
adb shell ls /storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap/*/ | wc -l
```

枚数を記録しておく。

```bash
sleep 40
adb shell ls /storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap/*/ | wc -l
```

Expected: **枚数が増えていない**。`adb logcat -d -s ContextCap` に `screen off` が出ている

- [ ] **Step 4: 画面 ON で再開することを確認する**

```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent 82  # ロック解除
sleep 35
adb shell ls /storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap/*/ | wc -l
```

Expected: 枚数が 3〜4 増えている。ログに `screen on`

- [ ] **Step 5: コミットする**

```bash
cd /Users/konaito/Documents/imi-chat
git add android/app/src/main/java/app/imichat/contextcap/CaptureService.kt
git commit -m "feat: ContextCap Android を画面 ON/OFF に連動させる"
```

---

### Task 6: Compactor

総量が 10GB を超えたら 5GB まで、古いファイルから段階的に再圧縮して押し戻す。

画像のデコード・再エンコードは `Bitmap` に依存するが、**再圧縮処理を関数として注入する**
設計にすることで、走査・段選択・削除判断のロジックを JVM unit test で検証できるようにする。

**Files:**
- Create: `android/app/src/main/java/app/imichat/contextcap/Compactor.kt`
- Test: `android/app/src/test/java/app/imichat/contextcap/CompactorTest.kt`
- Modify: `android/app/src/main/java/app/imichat/contextcap/CaptureService.kt`

**Interfaces:**
- Consumes: `CaptureFile.generationOf` / `captureDateOf` / `baseNameOf`（Task 2）、
  `CompressionRung.LADDER` / `DEEPEST_GEN`（Task 2）
- Produces:
  - `CompactionResult(recompressed: Int, deleted: Int, adoptedGen: Int?)`
  - `Compactor(root: File, budgetBytes: Long, recompress: (File, CompressionRung) -> File?)`
  - `Compactor.compactIfNeeded(): CompactionResult?`
  - `Compactor.Companion.bitmapRecompressor(): (File, CompressionRung) -> File?`

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/java/app/imichat/contextcap/CompactorTest.kt`:

```kotlin
package app.imichat.contextcap

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactorTest {

    private val root: File = Files.createTempDirectory("contextcap-compact").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun write(day: String, stem: String, bytes: Int): File {
        val dir = File(root, day).apply { mkdirs() }
        return File(dir, "$stem.jpg").apply { writeBytes(ByteArray(bytes)) }
    }

    /// 再圧縮を「元の 1/4 サイズの新ファイルを作って元を消す」で模擬する
    private val fakeRecompress: (File, CompressionRung) -> File? = { file, rung ->
        val target = File(file.parentFile, "${CaptureFile.baseNameOf(file)}.${rung.suffix}.jpg")
        target.writeBytes(ByteArray((file.length() / 4).toInt().coerceAtLeast(1)))
        if (target != file) file.delete()
        target
    }

    @Test
    fun `上限以下なら何もしない`() {
        write("2026-08-13", "100000_000", 100)
        val result = Compactor(root, budgetBytes = 1000, recompress = fakeRecompress).compactIfNeeded()
        assertNull(result)
    }

    @Test
    fun `上限超過で古いファイルから再圧縮する`() {
        write("2026-08-13", "100000_000", 400)
        write("2026-08-14", "100000_000", 400)
        write("2026-08-15", "100000_000", 400)

        // budget 1000 → trigger 1000 / target 500。合計 1200 なので圧縮が走る
        val result = Compactor(root, budgetBytes = 1000, recompress = fakeRecompress).compactIfNeeded()!!

        assertTrue(result.recompressed > 0)
        // 最も古い 2026-08-13 が g1 になっている
        assertTrue(File(root, "2026-08-13/100000_000.g1.jpg").exists())
        assertTrue(!File(root, "2026-08-13/100000_000.jpg").exists())
    }

    @Test
    fun `採用した段を返す`() {
        write("2026-08-13", "100000_000", 400)
        write("2026-08-14", "100000_000", 400)
        write("2026-08-15", "100000_000", 400)

        val result = Compactor(root, budgetBytes = 1000, recompress = fakeRecompress).compactIfNeeded()!!
        assertEquals(1, result.adoptedGen)
    }

    @Test
    fun `全段まで圧縮しても超えるなら最古から削除する`() {
        // 1 ファイル 4000 バイト × 3。g3 まで落としても 1/64 にはならない模擬なので削除に到達する
        write("2026-08-13", "100000_000", 4000)
        write("2026-08-14", "100000_000", 4000)
        write("2026-08-15", "100000_000", 4000)

        val result = Compactor(root, budgetBytes = 200, recompress = fakeRecompress).compactIfNeeded()!!

        assertTrue(result.deleted > 0)
        assertEquals(CompressionRung.DEEPEST_GEN, result.adoptedGen)
    }

    @Test
    fun `既に深い段のファイルは浅い段で再圧縮しない`() {
        write("2026-08-13", "100000_000.g3", 400)
        write("2026-08-14", "100000_000", 400)
        write("2026-08-15", "100000_000", 400)

        Compactor(root, budgetBytes = 1000, recompress = fakeRecompress).compactIfNeeded()

        // g3 のファイルが g1 に「戻って」いないこと
        assertTrue(File(root, "2026-08-13/100000_000.g3.jpg").exists())
        assertTrue(!File(root, "2026-08-13/100000_000.g1.jpg").exists())
    }

    @Test
    fun `空になった日付ディレクトリを削除する`() {
        write("2026-08-13", "100000_000", 4000)
        write("2026-08-14", "100000_000", 4000)
        write("2026-08-15", "100000_000", 4000)

        Compactor(root, budgetBytes = 200, recompress = fakeRecompress).compactIfNeeded()

        val remainingDirs = root.listFiles()?.filter { it.isDirectory && it.listFiles()?.isEmpty() == true }
        assertEquals(emptyList(), remainingDirs)
    }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*CompactorTest*'`
Expected: FAIL（`Compactor` が未定義）

- [ ] **Step 3: Compactor を実装する**

`app/src/main/java/app/imichat/contextcap/Compactor.kt`:

```kotlin
package app.imichat.contextcap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.Date

data class CompactionResult(
    var recompressed: Int = 0,
    var deleted: Int = 0,
    /// このパスで使った最深の段。以降の撮影プロファイルとして採用する
    var adoptedGen: Int? = null,
)

/// 総量が上限を超えたら、古いファイルから段階的に再圧縮して上限の半分まで押し戻す。
/// 全ファイルが最終段まで圧縮されてもまだ超える場合だけ、最古から削除する。
///
/// 世代（ファイル名で管理）:
///   gen0: HHmmss_SSS.jpg      等倍 q75
///   gen1: HHmmss_SSS.g1.jpg   長辺 1920 q60
///   gen2: HHmmss_SSS.g2.jpg   長辺 1280 q50
///   gen3: HHmmss_SSS.g3.jpg   長辺  800 q40
///
/// 画像処理を `recompress` として外から注入するのは、走査・段選択・削除判断を
/// Android 依存なしで単体テストできるようにするため。
class Compactor(
    private val root: File,
    private val budgetBytes: Long,
    private val recompress: (File, CompressionRung) -> File?,
) {
    private data class Entry(
        var file: File,
        var size: Long,
        val date: Date,
        var gen: Int,
        var deleted: Boolean = false,
    )

    /// これを超えたら圧縮を開始する
    private val trigger: Long get() = budgetBytes
    /// ここまで減らす
    private val target: Long get() = budgetBytes / 2

    /// 上限超過なら圧縮パスを 1 回実行する。変更がなければ null
    fun compactIfNeeded(): CompactionResult? {
        val entries = scan().toMutableList()
        var total = entries.sumOf { it.size }
        if (total <= trigger) return null

        val result = CompactionResult()

        // 段階圧縮: 各段について、その段より浅いファイルを古い順に落とす
        for (rung in CompressionRung.LADDER) {
            if (total <= target) break
            val candidates = entries.indices
                .filter { !entries[it].deleted && entries[it].gen < rung.gen }
                .sortedBy { entries[it].date }
            for (idx in candidates) {
                if (total <= target) break
                val entry = entries[idx]
                val newFile = recompress(entry.file, rung) ?: continue
                val newSize = newFile.length()
                total += newSize - entry.size
                entries[idx] = Entry(newFile, newSize, entry.date, rung.gen)
                result.recompressed += 1
                result.adoptedGen = maxOf(result.adoptedGen ?: 0, rung.gen)
            }
        }

        // 最終手段: それでも超えるなら最古から削除
        if (total > target) {
            val byAge = entries.indices
                .filter { !entries[it].deleted }
                .sortedBy { entries[it].date }
            for (idx in byAge) {
                if (total <= target) break
                entries[idx].file.delete()
                total -= entries[idx].size
                entries[idx].deleted = true
                result.deleted += 1
            }
            result.adoptedGen = CompressionRung.DEEPEST_GEN
            removeEmptyDayDirectories()
        }

        if (result.recompressed == 0 && result.deleted == 0) return null
        return result
    }

    private fun scan(): List<Entry> =
        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "jpg" }
            .map { file ->
                Entry(
                    file = file,
                    size = file.length(),
                    date = CaptureFile.captureDateOf(file) ?: Date(file.lastModified()),
                    gen = CaptureFile.generationOf(file),
                )
            }
            .toList()

    private fun removeEmptyDayDirectories() {
        root.listFiles()
            ?.filter { it.isDirectory && it.listFiles()?.isEmpty() == true }
            ?.forEach { it.delete() }
    }

    companion object {
        /// 実機で使う再圧縮。JPEG を読み直して長辺 maxPixel に縮小し、指定 quality で書き戻す。
        /// 元ファイルと別名になる場合は元を削除する。失敗したら null を返して呼び出し側でスキップする。
        fun bitmapRecompressor(): (File, CompressionRung) -> File? = { file, rung ->
            runCatching {
                // まずサイズだけ読んで inSampleSize を決める（フル解像度でのデコードを避ける）
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                if (longest <= 0) throw IllegalStateException("decode bounds 失敗")

                var sample = 1
                while (longest / (sample * 2) >= rung.maxPixel) sample *= 2

                val decoded = BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: throw IllegalStateException("decode 失敗")

                val decodedLongest = maxOf(decoded.width, decoded.height)
                val scaled = if (decodedLongest > rung.maxPixel) {
                    val ratio = rung.maxPixel.toDouble() / decodedLongest
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * ratio).toInt().coerceAtLeast(1),
                        (decoded.height * ratio).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    decoded
                }

                val target = File(
                    file.parentFile,
                    "${CaptureFile.baseNameOf(file)}.${rung.suffix}.jpg",
                )
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.outputStream().use { out ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, rung.quality, out)) {
                        throw IllegalStateException("JPEG エンコード失敗")
                    }
                }
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()

                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    throw IllegalStateException("rename 失敗")
                }
                if (target != file) file.delete()
                target
            }.getOrNull()
        }
    }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*CompactorTest*'`
Expected: PASS（6 テストすべて）

- [ ] **Step 5: CaptureService から Compactor を呼ぶ**

`CaptureService` に追加する。import:

```kotlin
import android.os.Environment
```

companion object に追加:

```kotlin
        /// 容量上限。テスト時は adb shell で上書きする（README 参照）
        const val KEY_BUDGET_BYTES = "StorageBudgetBytes"
        const val DEFAULT_BUDGET_BYTES = 10_000_000_000L

        /// 定期的な容量チェックの間隔
        const val COMPACT_INTERVAL_MS = 5 * 60 * 1000L
```

フィールドに追加。容量チェックは撮影用の `worker` と**別スレッド**で回す。
10GB 溜まった状態の再圧縮は数十秒かかることがあり、同じ single thread executor に
乗せると撮影の callback が詰まって tick が止まるため。

```kotlin
    /// 再圧縮は重いので撮影用 worker と分ける。ここを共有すると撮影が詰まる
    private val compactWorker = Executors.newSingleThreadExecutor()

    private val compactRunnable = Runnable {
        restartIfStalled()
        compactNow()
    }
```

`onDestroy()` の `worker.shutdown()` の隣に追加:

```kotlin
        compactWorker.shutdown()
```

`onServiceConnected()` の `handler.post { start() }` を次に変える:

```kotlin
            handler.post {
                start()
                handler.postDelayed(compactRunnable, COMPACT_INTERVAL_MS)
            }
```

`onDestroy()` に追加:

```kotlin
        handler.removeCallbacks(compactRunnable)
```

メソッドを追加:

```kotlin
    private fun budgetBytes(): Long =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_BUDGET_BYTES, DEFAULT_BUDGET_BYTES)

    /// 5 分ごとの定期チェック。撮影ごとの軽量判定から呼ばれることもある
    private fun compactNow() {
        handler.removeCallbacks(compactRunnable)
        compactWorker.execute {
            val result = Compactor(
                root = captureRoot(this),
                budgetBytes = budgetBytes(),
                recompress = Compactor.bitmapRecompressor(),
            ).compactIfNeeded()

            if (result != null) {
                Log.i(TAG, "compacted: recompressed=${result.recompressed} deleted=${result.deleted} adopted=${result.adoptedGen}")
                result.adoptedGen?.let { adoptGeneration(it) }
                // stats への書き込みは撮影スレッドに寄せて競合を避ける
                worker.execute { stats.rescan() }
            }
            handler.post { handler.postDelayed(compactRunnable, COMPACT_INTERVAL_MS) }
        }
    }

    /// Compactor が使った圧縮段を撮影プロファイルとして採用する（深くする方向のみ）
    private fun adoptGeneration(gen: Int) {
        if (gen <= captureGen) return
        captureGen = minOf(gen, CompressionRung.DEEPEST_GEN)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CAPTURE_GEN, captureGen)
            .apply()
        Log.i(TAG, "adopted capture generation: $captureGen")
    }
```

撮影ごとの軽量判定を `captureOnce()` の `onSuccess` 内、`stats.recordCapture(bytes, now)` の直後に追加:

```kotlin
                        if (stats.totalBytes > budgetBytes()) {
                            handler.post { compactNow() }
                        }
```

- [ ] **Step 6: 小さい上限で実機動作を確認する**

```bash
cd /Users/konaito/Documents/imi-chat/android
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

アクセシビリティを OFF→ON してから、上限を 3MB に落とす。

```bash
adb shell "run-as app.imichat.contextcap ls /data/data/app.imichat.contextcap/shared_prefs/"
```

SharedPreferences を直接書き換えるのは壊れやすいので、**代わりに一度アプリを止めて
`DEFAULT_BUDGET_BYTES` を一時的に `3_000_000L` に書き換えてビルドし直す**。
（恒久的なテスト用オーバーライドは Task 8 の README で adb 手順として整備する）

```bash
sleep 400
adb logcat -d -s ContextCap | grep -E "compacted|adopted"
```

Expected: `compacted: recompressed=N ...` と `adopted capture generation: 1` が出る。

```bash
adb shell ls /storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap/*/
```

Expected: `.g1.jpg` のファイルが存在し、以降の新規撮影も `.g1.jpg` で保存されている

確認できたら `DEFAULT_BUDGET_BYTES` を `10_000_000_000L` に戻してビルドし直す。

- [ ] **Step 7: コミットする**

```bash
cd /Users/konaito/Documents/imi-chat
git add android/app/src/main/java/app/imichat/contextcap/Compactor.kt \
        android/app/src/test/java/app/imichat/contextcap/CompactorTest.kt \
        android/app/src/main/java/app/imichat/contextcap/CaptureService.kt
git commit -m "feat: ContextCap Android の容量管理と適応撮影を実装"
```

---

### Task 7: SetupActivity

権限状態と統計を表示する 1 画面。macOS 版のメニューバーに相当する。

**Files:**
- Modify: `android/app/src/main/java/app/imichat/contextcap/SetupActivity.kt`
- Modify: `android/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `CaptureService.captureRoot` / `PREFS_NAME` / `KEY_PAUSED` / `KEY_CAPTURE_GEN` /
  `ACTION_SETTINGS_CHANGED` / `DEFAULT_BUDGET_BYTES` / `KEY_BUDGET_BYTES`（Task 4, 6）、
  `StatsStore`（Task 3）、`CompressionRung.labelForGen`（Task 2）
- Produces: なし（末端）

- [ ] **Step 1: SetupActivity を実装する**

レイアウト XML は使わず、コードで `LinearLayout` を組む（依存とファイルを増やさないため）。

```kotlin
package app.imichat.contextcap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/// 権限状態と統計を表示するだけの 1 画面。
/// macOS 版のメニューバー常駐に相当するが、常駐通知を持たない代わりに
/// 状態を見たい時だけここを開く。
class SetupActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var enableButton: Button
    private lateinit var pauseButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 96, 56, 56)
        }

        root.addView(TextView(this).apply {
            text = "ContextCap"
            textSize = 28f
        })

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 48, 0, 0)
        }
        root.addView(statusText)

        enableButton = Button(this).apply {
            text = "アクセシビリティ設定を開く"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(enableButton)

        statsText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 48, 0, 0)
        }
        root.addView(statsText)

        pauseButton = Button(this).apply {
            setOnClickListener { togglePause() }
        }
        root.addView(pauseButton)

        root.addView(TextView(this).apply {
            text = "撮った画像は端末内にだけ保存されます。送信は行いません。"
            textSize = 12f
            gravity = Gravity.START
            setPadding(0, 64, 0, 0)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun prefs() = getSharedPreferences(CaptureService.PREFS_NAME, Context.MODE_PRIVATE)

    private fun isServiceEnabled(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun togglePause() {
        val paused = prefs().getBoolean(CaptureService.KEY_PAUSED, false)
        prefs().edit().putBoolean(CaptureService.KEY_PAUSED, !paused).apply()
        sendBroadcast(Intent(CaptureService.ACTION_SETTINGS_CHANGED).setPackage(packageName))
        refresh()
    }

    private fun refresh() {
        val enabled = isServiceEnabled()
        val paused = prefs().getBoolean(CaptureService.KEY_PAUSED, false)

        statusText.text = when {
            !enabled -> "停止中: アクセシビリティが有効になっていません"
            paused -> "一時停止中"
            else -> "撮影中（${CaptureService.INTERVAL_MS / 1000} 秒間隔）"
        }
        enableButton.visibility = if (enabled) View.GONE else View.VISIBLE
        pauseButton.text = if (paused) "再開" else "一時停止"
        pauseButton.isEnabled = enabled

        val stats = StatsStore(CaptureService.captureRoot(this))
        val budget = prefs().getLong(
            CaptureService.KEY_BUDGET_BYTES,
            CaptureService.DEFAULT_BUDGET_BYTES,
        )
        val gen = prefs().getInt(CaptureService.KEY_CAPTURE_GEN, 0)

        statsText.text = buildString {
            appendLine("累計枚数: ${stats.countText}")
            appendLine("記録期間: ${stats.durationText}")
            appendLine("合計容量: ${stats.sizeText} / ${StatsStore.formatBytes(budget)}")
            appendLine("撮影画質: ${CompressionRung.labelForGen(gen)}")
            append("保存先: ${CaptureService.captureRoot(this@SetupActivity).absolutePath}")
        }
    }
}
```

`refresh()` は `StatsStore` のフルスキャンを走らせるので、枚数が数万件になると
画面表示が一瞬止まる。macOS 版と同じくフルスキャンで実測値を出す方針を優先し、
体感で問題が出たら後から `worker` へ逃がす。

- [ ] **Step 2: ビルドしてインストールする**

```bash
cd /Users/konaito/Documents/imi-chat/android
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.imichat.contextcap/.SetupActivity
```

- [ ] **Step 3: 画面を目視で確認する**

`adb exec-out screencap -p > /tmp/contextcap-ui.png` でスクリーンショットを撮り、
**Read tool で開いて実際に確認する。**

Expected:
- 「撮影中（10 秒間隔）」が出ている
- 累計枚数・記録期間・合計容量・撮影画質・保存先がすべて表示されている
- アクセシビリティが有効なので「設定を開く」ボタンは非表示

- [ ] **Step 4: 一時停止トグルが効くことを確認する**

「一時停止」ボタンの座標を UI ダンプから求めてタップする。

```bash
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/ui.xml
grep -o 'text="一時停止"[^>]*bounds="[^"]*"' /tmp/ui.xml
```

出力の `bounds="[x1,y1][x2,y2]"` の中心をタップする。

```bash
adb shell input tap <中心x> <中心y>
adb shell ls /storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap/*/ | wc -l
sleep 40
adb shell ls /storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap/*/ | wc -l
```

Expected: 枚数が変わらない。再開すると増え始める

- [ ] **Step 5: コミットする**

```bash
cd /Users/konaito/Documents/imi-chat
git add android/app/src/main/java/app/imichat/contextcap/SetupActivity.kt
git commit -m "feat: ContextCap Android の設定画面を実装"
```

---

### Task 8: install.sh、README、長時間稼働の検証

**Files:**
- Create: `android/scripts/install.sh`
- Create: `android/README.md`

**Interfaces:**
- Consumes: 全タスク
- Produces: なし（末端）

- [ ] **Step 1: install.sh を書く**

```bash
#!/usr/bin/env bash
# ContextCap Android をビルドして端末にインストールし、
# サイドロード時にブロックされるアクセシビリティ有効化まで解除する。
set -euo pipefail

cd "$(dirname "$0")/.."

PKG=app.imichat.contextcap

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Android 13+ はサイドロードしたアプリのアクセシビリティ有効化がブロックされる
adb shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow

echo
echo "インストール完了。設定画面を開くので ContextCap を有効化してください。"
adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS
```

```bash
chmod +x android/scripts/install.sh
```

**Task 1 Step 8 で `MANAGE_EXTERNAL_STORAGE` 方式に切り替えていた場合は、
`appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow` の行も追加する。**

- [ ] **Step 2: README を書く**

`android/README.md` に以下を含める。**Task 1 で実測した実際の値を書く**
（保存先パス、AGP / Kotlin / Gradle のバージョン）。

- 何をするアプリか（macOS 版 README の冒頭と同じ位置づけの説明）
- 仕様表（撮影間隔 10 秒 / 長辺基準の圧縮段 / 容量上限 10GB / 画面 OFF 中は停止）
- なぜ MediaProjection ではなく AccessibilityService なのか（ロック自動停止と consent 1 回限りの実測根拠、出典 URL つき）
- ビルドと導入手順（`scripts/install.sh`）
- データの回収手順（`adb pull` の実コマンド）
- テスト時のパラメータ変更手順（`CaptureService` の `INTERVAL_MS` / `DEFAULT_BUDGET_BYTES` /
  `captureRoot()` を書き換えて再ビルドする。実行時オーバーライドは持たない）
- 実装上の注意（`HardwareBuffer` の close 必須、`FLAG_SECURE` のウィンドウは黒塗りで保存される、撮影段階で縮小したファイルには gen 接尾辞を付けて Compactor の二度掛けを防ぐ）
- macOS 版との差分（間隔 5 秒 → 10 秒、上限 40GB → 10GB、幅基準 → 長辺基準）
- プライバシー上の性質（画面全体が写る / `FLAG_SECURE` は黒塗りになるが保証ではない /
  ネットワーク送信機構を持たないので `adb pull` するまで端末内に留まる）

- [ ] **Step 3: 全テストを通す**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: PASS（CaptureFileTest 7 + StatsStoreTest 7 + CompactorTest 6 = 20 テスト）

- [ ] **Step 4: リリースビルドが通ることを確認する**

Run: `cd android && ./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 数時間の連続稼働を確認する**

エミュレータを起動したまま放置し、以下を確認する。

```bash
adb logcat -c
# 3 時間以上放置してから
adb logcat -d -s ContextCap | grep -cE "save failed|screenshot failed: 1"
adb shell ls /storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap/*/ | wc -l
adb shell dumpsys meminfo app.imichat.contextcap | head -20
```

Expected:
- `save failed` / 内部エラーがゼロか、ごく少数で連続していない
- 枚数が経過時間 ÷ 10 秒におおむね一致する
- メモリ使用量が時間とともに単調増加していない（`HardwareBuffer` / `Bitmap` のリークがない）

**リークが見つかったら Task 4 の `finally` ブロックと `recycle()` を見直す。
リークを抱えたまま完了報告しない。**

- [ ] **Step 6: 最終コミット**

```bash
cd /Users/konaito/Documents/imi-chat
git add android/scripts/install.sh android/README.md
git commit -m "docs: ContextCap Android の導入手順と README を追加"
```

---

## 完了条件

- `./gradlew :app:testDebugUnitTest` が全 20 テスト PASS
- `./gradlew assembleRelease` が成功
- エミュレータまたは実機で 3 時間以上の連続稼働を確認し、枚数が想定どおり増え、メモリが単調増加していない
- `adb pull` で実際に画像を回収し、**Read tool で開いて画面が写っていることを目視確認済み**
- 画面 OFF で停止し、ON で再開することを確認済み
- 容量上限を小さくした状態で Compactor が再圧縮し、採用段が以降の撮影に反映されることを確認済み
- spec の「保存先」「ビルドと配布」節が実測値で更新されている
