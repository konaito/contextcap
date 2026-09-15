# ContextCap Android 設計

作成日: 2026-08-13

macOS 版 ContextCap（`macos/`）の Android 移植。
スマホの画面を 10 秒に 1 回撮ってローカルに溜め続けるだけの常駐アプリ。
MiaChat の正解データ（ground truth）を、PC 利用文脈だけでなくスマホ利用文脈からも取ることが目的。

macOS 版と同じく、**クリック・入力・送信などの操作は一切しない。撮って保存するだけ。ネットワーク送信機構を持たない。**

## 仕様

| 項目 | 仕様 | macOS 版との差 |
|------|------|---------------|
| 撮影間隔 | 10 秒 | 5 秒 → 10 秒（容量制約） |
| 撮影対象 | デフォルトディスプレイ全体 | 同じ思想 |
| 撮影手段 | `AccessibilityService.takeScreenshot()` | ScreenCaptureKit → AccessibilityService |
| 保存形式 | JPEG（初期は等倍 quality 0.75、段階的に劣化） | 同じ |
| 保存先 | `<root>/YYYY-MM-DD/HHmmss_SSS[.gN].jpg` | **命名規約は完全に同一** |
| 容量上限 | 10GB。超えたら 5GB まで押し戻す | 40GB → 20GB と同比率 |
| 重複スキップ | なし | 同じ（正解データ用途なので間引かない） |
| UI | 設定画面 1 枚のみ。常駐通知なし | メニューバー → Activity |
| 画面 OFF 中 | 撮影停止 | **Android 固有の追加** |
| 回収 | `adb pull` | 同じ（ネットワーク機構なし） |

命名規約を macOS 版と揃えることで、回収後の解析スクリプトが両プラットフォームに効く。

## なぜ AccessibilityService なのか

Android で画面を撮る手段は 2 つあり、標準ルートの MediaProjection は**この用途では使えない**。

一次ソースで確認した MediaProjection の制約:

- **Android 15 QPR1 以降、デバイスがロックされると screen projection は自動停止する。**
  全アプリ対象・targetSdk 非依存・opt-out なし
  （<https://developer.android.com/about/versions/15/behavior-changes-all>）
- consent トークンは 1 回限り。`createScreenCaptureIntent()` の Intent を `getMediaProjection()` に
  2 回渡すと `SecurityException`。同一 `MediaProjection` での `createVirtualDisplay()` も 1 回限り
  （<https://developer.android.com/media/grow/media-projection>）

つまりロック解除のたびに「通知タップ → システムダイアログ承諾」の 2 タップが必要になり、
放置して溜め続けるレコーダーとしては破綻する。

AccessibilityService の `takeScreenshot()`（API 30+）はこの制約をすべて回避する。
承諾ダイアログなし、ロックを跨いで生存、単発呼び出しなので常時 VirtualDisplay より電池も軽い。

**OS 再起動を跨いでも有効なまま**で、起動後に撮影が自動再開することを実測で確認した
（2026-08-13、エミュレータを強制終了 → 再起動して設定が保持され撮影が続いた）。
MediaProjection では consent が消えるため不可能な挙動で、
「入れたら放っておく」運用が成立するかどうかはここで決まる。

トレードオフとして Play ストアの審査は通らないが、配布は手動 APK / adb install なので問題にならない。

## アーキテクチャ

### 核心: Service を 1 個も立てない

`AccessibilityService` は system_server にバインドされた OS 管理の常駐プロセスなので、
**ForegroundService も常時通知もバッテリー最適化の除外も不要**。
MediaProjection 案で必要だった「FGS + 常時通知 + consent 復旧 + ロック復帰」の層がまるごと消える。

```
AccessibilityService（OS が生かし続ける）
  └ Handler で 10 秒 tick
      └ takeScreenshot(DEFAULT_DISPLAY)
          └ HardwareBuffer → Bitmap.wrapHardwareBuffer → JPEG → File
              └ hardwareBuffer.close()
```

### 依存ゼロ

- Kotlin + AGP のみ。**依存ライブラリなし**
- Jetpack Compose なし、AndroidX なし（素の `Activity`、素の `View`）
- WorkManager なし（最小 15 分間隔なので 10 秒 tick には元から使えない）
- minSdk 30（`takeScreenshot` の要件）/ targetSdk 36

### ファイル構成

```
android/
├── README.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties           # org.gradle.java.home = Studio 同梱 JBR 21
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/xml/accessibility_service_config.xml
│       └── java/app/imichat/contextcap/
│           ├── CaptureService.kt   # 常駐 + tick + 撮影 + 保存
│           ├── Compactor.kt        # 上限超過時の段階再圧縮
│           ├── CaptureFile.kt      # 命名規約とパスからの撮影時刻解釈
│           ├── StatsStore.kt       # 枚数・期間・容量の集計
│           └── SetupActivity.kt    # 権限状態 + 統計の 1 画面
└── scripts/
    └── install.sh                  # build → adb install → 制限付き設定の解除
```

macOS 版の 7 ファイルとの対応:

| macOS 版 | Android 版 |
|---|---|
| `CaptureManager.swift` | `CaptureService.kt` |
| `Compactor.swift` | `Compactor.kt` |
| `CaptureFile.swift` | `CaptureFile.kt` |
| `StatsStore.swift` | `StatsStore.kt` |
| `App.swift` + `AppDelegate.swift` | `SetupActivity.kt` |
| `LaunchAtLogin.swift` | 不要（AccessibilityService は有効化した時点で永続） |

## コンポーネント

### CaptureService

`AccessibilityService` を継承。アプリの本体はここに集約する。

- `onServiceConnected()` で tick 開始と `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` の
  BroadcastReceiver を動的登録する（この 2 つは manifest 静的登録できない）
- tick は `Handler(Looper.getMainLooper()).postDelayed`。
  **次の tick は撮影と保存の完了コールバック後にスケジュールする**ので、
  in-flight guard を別途持たなくても直列性が保たれる（macOS 版の in-flight guard と同じ思想）
- `onAccessibilityEvent()` / `onInterrupt()` は空実装。イベントは一切購読しない
  （`accessibility_service_config.xml` の `accessibilityEventTypes` も空にする）

撮影とデコード:

```
takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
  → onSuccess(ScreenshotResult)
      → Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
      → (必要なら縮小) → compress(JPEG, quality) → File
      → result.hardwareBuffer.close()      // finally で必ず。怠るとバッファ枯渇で死ぬ
```

`accessibility_service_config.xml` に `android:canTakeScreenshot="true"` を書くと
`CAPABILITY_CAN_TAKE_SCREENSHOT` が付く。追加の permission 宣言は不要
（SDK ソース `android-36/android/accessibilityservice/AccessibilityServiceInfo.java:787` で確認）。

### エラー処理

`takeScreenshot` の失敗コードは SDK ソース
（`android-36/android/accessibilityservice/AccessibilityService.java:752-788`）で確認済み。

| エラー | 値 | 扱い |
|---|---|---|
| `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` | 6 | スキップして次 tick。**ただし Android 16 の実測ではこのエラーは返らなかった**（下記） |
| `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` | 3 | スキップ。制限は `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 333`(ms) なので 10 秒間隔では通常発生しない |
| `ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR` | 1 | スキップ |
| `ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY` | 4 | スキップ |
| `ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS` | 2 | **UI に出す。** サービスが無効化された状態。macOS 版の「権限がありません」表示に相当 |

原則すべて「無視して次の tick」。撮影失敗でクラッシュさせない。

**`FLAG_SECURE` についての訂正（2026-08-14 実測）**

設計時は「`FLAG_SECURE` の画面は撮影失敗で返るので写らない」と想定していたが、**誤りだった**。
Android 16 で `FLAG_SECURE` を立てた Activity を前面にして確かめたところ:

- `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` は返らない（失敗ログ 0 件）
- 撮影は成功し、**該当ウィンドウの領域が真っ黒に塗られた JPEG がそのまま保存される**
  （ステータスバーとナビゲーションバーは通常どおり写る）
- 撮影は止まらず、サービスも落ちない

macOS 版の DRM 黒画面と同じ挙動になる。プライバシー上の保護は効いているが、
**情報量ゼロの黒画像がファイルとして残り、容量を食い、解析時のノイズになる**。
MiaChat 本体の #1523（DRM 再生中の黒画面を annotator が誤読して閉ループを作った件）と
同型なので、収集データを解析に回すときは黒画像を除外すること。

エラー 6 を返す端末があるかもしれないので、分岐自体は残してある。
検証用の画面は `app/src/debug/.../SecureTestActivity.kt`（debug ビルド専用）。

### 画面 OFF 中の停止

スマホは 1 日の大半が画面 OFF で、撮っても黒画面のゴミが溜まるだけ。
`ACTION_SCREEN_OFF` で tick を止め、`ACTION_SCREEN_ON` で再開する。
macOS 版の「スリープ・画面ロック中は失敗を無視して次の tick へ」を、
能動的な停止として実装したもの。

この設計により、1 日の実撮影量は画面 ON 時間に比例する。
画面 ON が 1 日 4 時間なら 10 秒間隔で 1,440 枚/日 ≒ 0.5GB/日。
10GB 上限には等倍画質で約 20 日、圧縮段が入れば 1〜2 ヶ月分が残る。

### Compactor

macOS 版の 4 段構成を移植する。スマホは縦長なので、**「最大幅」ではなく「長辺の最大値」**で定義する。

| 段 | ファイル名 | 長辺 | quality |
|----|-----------|------|---------|
| gen0 | `HHmmss_SSS.jpg` | 等倍 | 0.75 |
| g1 | `HHmmss_SSS.g1.jpg` | 1920 | 0.6 |
| g2 | `HHmmss_SSS.g2.jpg` | 1280 | 0.5 |
| g3 | `HHmmss_SSS.g3.jpg` | 800 | 0.4 |

- 総量が 10GB を超えたら 5GB まで、**古いファイルから**段階再圧縮して押し戻す
- そのパスで使った最深の段を以降の撮影プロファイルとして採用・永続化する（`SharedPreferences`）
- 全ファイルが g3 でもまだ超える場合だけ、最終手段として最古から削除する
- チェックは撮影ごとの軽量判定（統計値ベース）+ 5 分ごと + サービス接続時

### CaptureFile

命名規約とパスからの撮影時刻解釈。macOS 版と同一規約なので、ロジックもほぼ直訳になる。
**撮影時刻はファイル属性ではなくパス（日付ディレクトリ + ファイル名）から解釈する。**
再圧縮でファイルが作り直されても期間集計・古い順判定が壊れないため。

### StatsStore

累計枚数・記録期間・合計容量・現在の撮影プロファイルを保持。
サービス接続時にフルスキャンし、以降は撮影ごとに増分更新する（macOS 版と同じ）。

### SetupActivity

素の `Activity` 1 枚。起動時とレジューム時に状態を読んで表示するだけ。

- アクセシビリティ有効状態（`AccessibilityManager.getEnabledAccessibilityServiceList` で自分を探す）
- 無効なら「設定を開く」ボタン → `Settings.ACTION_ACCESSIBILITY_SETTINGS`
- 統計表示（累計枚数 / 記録期間 / 合計容量と上限 / 現在の撮影画質 / 前回の圧縮結果）
- 一時停止・再開トグル（`SharedPreferences`）

macOS 版のメニューバーと違って常時可視ではないが、常駐通知を持たない代わりの割り切り。

**統計のフルスキャンは必ず別スレッドで取る（2026-08-14 実測で修正）。**
当初は `onResume` で同期実行していたが、ダミー 8,684 件で起動時間を測ったところ
47 件の 22ms に対して 470ms かかり、1 件あたり約 0.05ms でファイル数に比例していた。
外挿すると 10 万ファイルで 5.2 秒に達し ANR 閾値を超える。
10 秒間隔・画面 ON 4 時間/日なら 2 ヶ月ちょっとで到達するので、想定運用そのもので踏む。

専用 executor へ逃がして「集計中…」を出す方式に変え、8,684 件でも 20ms に戻った。
権限状態とボタンは `SharedPreferences` を読むだけなので UI スレッドのままでよい。

## 保存先（実測で確定済み）

`getExternalFilesDir(null)/ContextCap`（権限不要）を使う。実パスは:

```
/storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap
```

Android 13+ では `/sdcard/Android/data/<pkg>` が adb からも permission denied になる
という報告があるため着手時に実測した。**エミュレータ（Android 16 / API 36）では
`adb pull` が通る**ことを確認済み（2026-08-13）。`MANAGE_EXTERNAL_STORAGE` は不要。

```
$ adb pull /storage/emulated/0/Android/data/app.imichat.contextcap/files/ContextCap /tmp/probe
1 file pulled, 0 skipped.
```

補足として、`adb shell run-as` からこのディレクトリに書き込むことはできない
（`Permission denied`）。これは run-as シェル側の制約で、アプリ自身の書き込みとは別。
**動作確認をするときは run-as ではなくアプリに書かせて確かめる。**

実機（特に一部 OEM）で pull できない場合は `MANAGE_EXTERNAL_STORAGE`（全ファイルアクセス）を取り、
`/sdcard/ContextCap/` に直接書く方式へ切り替える。ストア外配布なので宣言自体は問題にならない。

MediaStore 経由は採用しない。10 秒ごとの `ContentResolver.insert` は重く、
端末のギャラリーが数万件のスクリーンショットで汚染されるため。

## ビルドと配布（実測で確定済み）

ツールチェーンは以下で確定した（2026-08-13 実測）。

| 項目 | 値 |
|------|-----|
| Android Gradle Plugin | 9.3.0 |
| Gradle | 9.5.0（AGP 9.3.0 のデフォルト。wrapper で固定） |
| Kotlin | **宣言しない**。AGP 9.0 以降は KGP 2.2.10 が内蔵される |
| ビルド用 JDK | Android Studio 同梱の JBR 21 |

**AGP 9.0 以降、`org.jetbrains.kotlin.android` プラグインを宣言するとビルドが失敗する。**

```
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```

Kotlin サポートは AGP に内蔵されたため、`plugins` ブロックには
`com.android.application` だけを書く。古い記事の
「AGP + kotlin.android を両方書く」構成をコピーしない。
（<https://developer.android.com/build/releases/agp-9-0-0-release-notes>）

JDK は `gradle.properties` の `org.gradle.java.home` で JBR 21 に固定する。
Android SDK の場所は `local.properties` の `sdk.dir` で指定し、マシン固有なので git 管理しない。

配布手順:

- インストールは `adb install`
- Android 13+ はサイドロードしたアプリのアクセシビリティ有効化が「制限付き設定」でブロックされる。
  `adb shell appops set app.imichat.contextcap ACCESS_RESTRICTED_SETTINGS allow` で解除する。
  `scripts/install.sh` にこの手順まで含める
- **アクセシビリティの有効化は adb だけでは完結しない。**
  `settings put secure enabled_accessibility_services` は Android 16 では反映されない
  （`settings get` が `null` のまま）。設定アプリを開いてトグルと確認ダイアログの
  「Allow」を踏む必要がある

APK サイズは debug 2.4MB / release（minify なし）2.0MB。大半が Kotlin 標準ライブラリで、
実行時依存ライブラリはゼロだが、Kotlin を使う以上この下限は動かせない。

## テスト時のパラメータ変更

macOS 版は `defaults write` で上限や保存先を差し替えられるようにしているが、
Android 版では**定数を書き換えて再ビルドする**方式にする。

- Gradle の差分ビルドは数秒で終わるため、実行時オーバーライドの仕組みを持つ利点が薄い
- `am broadcast` を受ける debug レシーバーは、テストのためだけに本番コードへ
  受信経路を 1 つ増やすことになる。「Service を 1 個も立てない」という設計方針と釣り合わない

書き換える定数は `CaptureService` の companion object に集約しておく
（`INTERVAL_MS` / `DEFAULT_BUDGET_BYTES` / `captureRoot()`）。
手順は `README.md` に書く。

## 検証計画

実機またはエミュレータ（`Medium_Phone` / android-36.1）で以下を確認する。
着手順は上から。

1. **`adb pull` が `getExternalFilesDir` から通るか**（保存先の確定。最優先）
2. **Gradle が JBR 21 でビルドできるか**
3. サービス有効化後、10 秒ごとにファイルが増えるか
4. 画面 OFF で tick が止まり、ON で再開するか
5. `FLAG_SECURE` を立てたテスト用 Activity を前面にした時、クラッシュせず次の tick に進むか
   （**実測の結果、エラーは返らず黒塗り画像が保存された**。上の「訂正」を参照）
6. 上限を小さく設定した状態で Compactor が段階再圧縮を行い、
   採用した段が以降の撮影に永続化されるか
7. 数時間の連続稼働で `HardwareBuffer` のリークが起きないか（メモリ推移を確認）

macOS 版と違い自動テストの土台がないため、検証は実機・エミュレータでの目視と
`adb shell ls` によるファイル増加の確認を一次証拠とする。

## やらないこと

- ネットワーク送信・アップロード機構（macOS 版と同じくローカル保存のみ）
- 重複スキップ（正解データ用途なので間引かない）
- Play ストア配布
- MediaProjection によるフォールバック実装（ロック自動停止で用途を満たさないため、
  二重実装する価値がない）
- 常駐通知（AccessibilityService には FGS が不要なため）

## プライバシー上の性質

macOS 版と同じ扱いだが、スマホは PC より機微な情報が写る頻度が高い。

- 画面全体を 10 秒おきに撮るため、通知・メッセージ・位置情報・決済画面が写り得る
- `FLAG_SECURE` が立っている画面（多くの銀行・決済アプリ）は、**ファイル自体は保存されるが
  中身が黒く塗られる**（Android 16 で実測）。中身は残らないが、アプリ側の宣言に依存するので
  保護の保証にはならない
- ネットワーク送信機構を持たないので、データは `adb pull` するまで端末内に留まる
- 回収したデータを MiaChat の解析に使う際は、`dump/` 配下と同じく PII を含む前提で扱う
