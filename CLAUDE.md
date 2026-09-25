# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## このリポジトリの位置づけ

`imi-chat/contextcap` は **MiaChat 本体（`imi-chat/imi-chat`）とは別の独立リポジトリ**。
親ディレクトリ経由で `imi-chat` の `AGENTS.md` / `CLAUDE.md` が読み込まれることがあるが、
**あちらの規約（`develop` 向け PR、issue assign 排他ロック、`pr-size-check`、Vercel / Prisma / Supabase の手順）はここには一切適用されない。**

ここは `main` 単独。CI もリリースワークフローもない。検証はローカルで完結させる。

## コマンド

### macOS 版（`macos/`）

```bash
cd macos
swift build                          # 型チェック相当。最速の検証
./scripts/build-app.sh               # release ビルド → build/ContextCap.app → /Applications へコピー
SKIP_INSTALL=1 ./scripts/build-app.sh  # .app 生成まで（/Applications を触らない）
open /Applications/ContextCap.app
```

**テストターゲットは存在しない**（`Package.swift` は executable のみ）。
`swift test` は `error: no tests found; create a target in the 'Tests' directory` で落ちる。
macOS 側のロジックを変えたら、対応する Android 側の unit test（下記）で契約を確認するか、実機で見る。

再ビルドすると ad-hoc 署名が変わって画面収録権限が剥がれる。**ビルドのたびに再許可が要る。**

パラメータのテスト用オーバーライド（macOS 版のみ。Android にはない）:

```bash
defaults write app.imichat.contextcap StorageBudgetGB -float 0.03
defaults write app.imichat.contextcap CaptureRoot /path/to/dir
defaults delete app.imichat.contextcap StorageBudgetGB
```

### Android 版（`android/`）

```bash
cd android
./gradlew :app:testDebugUnitTest                          # JVM unit test（全件）
./gradlew :app:testDebugUnitTest --tests "*RetentionTest" # 単一テストクラス
./gradlew :app:lintDebug :app:lintRelease                 # lint は 0 件を維持している
./gradlew assembleRelease                                 # R8 を通す（release は必ずこちらで確認）
./scripts/install.sh                                      # assembleDebug → adb install → 設定アプリを開く
```

`install.sh` のあと、**アクセシビリティの有効化は手で操作する**。
`settings put secure enabled_accessibility_services` は Android 16 以降で反映されない。

`local.properties`（`sdk.dir`）と `gradle.properties` の `org.gradle.java.home`（JBR 21 固定）は
マシン固有。Homebrew の Java では AGP が動かない。

## アーキテクチャ

同じ道具の 2 実装（Swift / Kotlin）で、**共有コードは一切ない。共有しているのは保存形式の契約だけ。**

### 保存形式の契約（変更は必ず両プラットフォーム同時）

```
<root>/YYYY-MM-DD/HHmmss_SSS[.gN].jpg
```

- **撮影時刻はファイル属性ではなくパスから解釈する**（`CaptureFile.captureDate` / `captureDateOf`）。
  再圧縮でファイルが作り直されても期間集計・古い順判定が壊れないための設計。ここを属性ベースに戻さない
- **段階再圧縮は 2026-08-18 に両プラットフォームで廃止した**（`Compactor.swift` /
  `Compactor.kt` / `CompressionRung.kt` は削除済み）。常に gen0 で撮り、撮影直後に OCR し、
  `Retention` が保持期間超と容量超過分を古い順に削除する。**OCR 済みでない画像は消さない。**
  適応撮影プロファイル（`CaptureGeneration`）も廃止した — 一度深い段が採用されると
  新規撮影まで劣化し、実際 macOS では gen0 が 1 枚も撮られない状態が続いていた
- 非対称なのは OCR エンジンと保持期間だけ。macOS = Apple Vision / `ocr.sqlite` / 3 日 / 40GB、
  Android = ML Kit 日本語 bundled / `<day>/ocr.jsonl` / 14 日 / 10GB。
  1 枚が 813KB（3600px）と 95.3KB（1080x2400）で桁が違うため
- 廃止の根拠は同一画像の統制実験（60 枚・`lines` 分位で層化・単スレッド計測）。
  **縮小しても OCR は速くならない**（時間は解像度ではなく行数に比例。≒ 5.5ms/行 + 60ms）のに、
  テキストだけが壊れる。行近似一致(≥0.85) は g1 0.751 / g2 0.568 / g3 0.056。
  実測の全文は `~/ContextCap-analysis/ocr-findings.md`
- **`.gN` 接尾辞のパースは両プラットフォームに残す**（macOS は `CaptureFile.legacyGenerationSuffixes`）。
  既存アーカイブに `.g1` が 24,148 枚あり、読めないと撮影時刻の解釈と OCR の同一性判定が壊れる

**契約の unit test は主に Android 側にある**（`CaptureFileTest` / `RetentionTest` / `StatsStoreTest`）。
macOS 側にはテストターゲットが無いので `macos/scripts/run-tests.sh` が対象ソースを直接
コンパイルして叩く（`Retention` の未 OCR ガードはここで実発火させて確認する）。

`OcrLog` は `org.json` と `android.util.Log` を使わない。使うと JVM unit test から検証できなくなる
（`BlackoutLog` に同じ理由が明記されている）。ここを「普通に JSONObject でいいだろう」と
書き換えると、テストが `RuntimeException` / `NullPointerException` で全滅する。

### macOS 版（`macos/Sources/ContextCap/`）

SwiftPM の executable。SwiftUI は使わず AppKit の `NSStatusItem` + `NSMenu` のみ。

`App.swift`（@main）→ `AppDelegate.swift`（メニュー構築）→ `CaptureManager.swift`（5 秒タイマー +
ScreenCaptureKit 撮影 + 等倍 JPEG 保存 + OCR キューへの enqueue）。
`OCRIndexer` / `Retention` / `StatsStore` / `CaptureFile` / `LaunchAtLogin` が支える。

`OCRIndexer` のキューは 2 段。撮影直後の分（fresh）を必ず先に処理し、起動時に拾った未 OCR の分
（backlog）は fresh が空の時だけ進める。1 本にすると追いつき処理が終わるまで新規撮影が待たされ、
「撮った瞬間に OCR」が成立しない。**並列にしない** — Vision は内部でシリアライズしていてスレッドを
2/4/8/12/20 と増やしても 1.55〜1.57 枚/秒で変わらず、同時に 1 枚しか持たないほうがメモリも軽い。
DB は `scripts/ocr-extract.swift` と同じ `~/ContextCap-analysis/ocr.sqlite`・同じスキーマ・同じ主キー。

- 撮影は直列。前の撮影＋保存が終わっていなければ次の tick はスキップ
- スリープ・ロック中は ScreenCaptureKit が失敗する。**エラーは無視して次の tick へ**（落とさない）
- ディスプレイ構成が変わるたび `SCShareableContent` を取り直す

### Android 版（`android/app/src/main/java/app/imichat/contextcap/`）

**ForegroundService も常時通知もバッテリー最適化除外も持たない。**
`AccessibilityService` 自体が system_server にバインドされた OS 管理の常駐プロセスだから成立している。
MediaProjection に置き換えようとしないこと（Android 15 QPR1 以降ロックで自動停止・consent は 1 回限り）。

層が 2 つに分かれている。**撮影パスは Compose に一切依存しない**:

- 撮影パス: `CaptureService`（tick・受信・除外判定）、`CaptureFile`、`OcrIndexer`、`OcrLog`、`Retention`、
  `StatsStore`、`CaptureBrowser`、`CaptureThumbnail`、`ForegroundAppLog`、`AppUsageIndex`、`AppUsageStats`、
  `FrameBlackness`、`BlackoutLog`
- UI: `SetupActivity` / `ViewerActivity` + `ui/`（Compose + Material 3 Expressive）

`CaptureService` の定数（`INTERVAL_MS` / `DEFAULT_BUDGET_BYTES` / `COMPACT_INTERVAL_MS` / `captureRoot()`）は
companion object に集約。**テスト時はここを書き換えて再ビルドする**（`SharedPreferences` の
`StorageBudgetBytes` は読むだけで書き手がいない）。

前面アプリの追跡は `apps.jsonl`（**Android のみ。macOS 版にはない**）。
切り替わった瞬間だけ 1 行書き、`AppUsageIndex` が「その時刻以前で最も新しい記録」で画像の所属を決める。
撮影のたびに書くと 1 日 8,000 行を超えるための設計。アプリ別集計・一括削除・撮影除外はすべてこの索引の上に乗る。

**`TYPE_WINDOW_STATE_CHANGED` のパッケージ名をそのまま前面アプリにしない**（`OverlayPackages`）。
通知シェード・キーボードは開いた時だけイベントが飛び、閉じた時は飛ばないので、上書きすると
`com.android.systemui` に張り付いて戻らない。実機 7 日分で **37%（9,483 枚）が誤帰属**していた。
ランチャーは除外しない（実際に前面にあるため）。詳細と残る限界は `android/README.md`。

情報のない画面（DRM 保護・AOD）は**保存せずに捨て**、区間だけ `blackouts.jsonl` に残す。
判定は `FrameBlackness`（純ロジック・unit test 済み）で、格子に割って「セルごと真っ黒」を数える。
**黒いピクセルの割合で判定しない**（ダークモード画面が同じ形になる）。閾値 85% なのは、
保護画面でもステータスバーとナビゲーションバーが写るから。**判定は必ず保存の前**に置く
（後段で落とすと MiaChat 本体 #1523 と同じ構図になる）。

踏み抜きやすい罠（詳細と実測値は `android/README.md`）:

- `ScreenshotResult.hardwareBuffer` は必ず `close()`。怠るとバッファ枯渇で以降の撮影が全部落ちる
- **`AccessibilityManager.getEnabledAccessibilityServiceList()` を使わない。**
  Android 17 実機で自分自身が返ってこない（Android 16 エミュレータでは返る／同じ APK で挙動が違った）。
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` を直接読む
- 撮影ループは callback 駆動。callback が来ないと二度と再開しないので `STALL_THRESHOLD_MS` の watchdog がある
- **再圧縮は撮影 worker と別スレッド。**同じ executor に乗せると撮影 tick が詰まる
- `StatsStore` / `AppUsageStats` のフルスキャンは 1 件約 0.05ms。**10 万件で 5 秒 = ANR。UI スレッドで呼ばない**
- 一覧表示は `CaptureBrowser`（日付ディレクトリ降順・必要件数で打ち切り）を使い、全件走査に落とさない
- Bitmap は `CaptureThumbnail` 経由で `inSampleSize` を決めてデコードし、差し替えのたびに前を `recycle()` する

### ツールチェーンの制約（Android）

`android/README.md` の「ツールチェーン」節が正本。要点だけ:

- **AGP 9 以降 `org.jetbrains.kotlin.android` を宣言するとビルドが落ちる**（Kotlin は AGP 内蔵）
- Compose Compiler プラグインは内蔵 KGP と同じ 2.2.10。ずらすと Compose が落ちる
- `material3:1.5.0-alpha26` を直接指定。M3 Expressive の API は安定版 1.4.0 では internal
- `compileSdk` / `targetSdk` = 37、`minSdk` = 33（`RECEIVER_NOT_EXPORTED` の 3 引数 `registerReceiver` が API 33）
- **release は R8 必須**（dex 2.6MB）。APK 全体は **16MB**（2026-08-20 実測）で、
  うち 14MB は ML Kit の日本語 OCR（`libmlkit_google_ocr_pipeline.so` 11MB + モデル 3MB）。
  README にあった「1.8MB」は OCR を bundled にする前の値

## アイコン

マスターは `assets/icon.png`（1024×1024、角丸の外は透過）**1 枚だけ**。両プラットフォームの
アイコンはここから生成する。マスターを差し替えたら下記を両方やり直す。

```bash
python3 scripts/make-android-icon.py   # → mipmap-*/ic_launcher_foreground.webp（要 Pillow）
cd macos && SKIP_INSTALL=1 ./scripts/build-app.sh   # → .app 内に AppIcon.icns を生成
```

- **`.icns` はリポジトリに置かない。** `build-app.sh` が `sips` + `iconutil`（どちらも macOS 標準）で
  ビルドのたびに生成する。マスターと二重管理にしないため
- Android の前景は**不透明**。108dp 全面に紺を敷いた上に元絵を 64dp で置いている。
  64dp なのは、円マスク（Pixel のランチャー）で四隅のブラケットが欠けない上限だから。
  これより大きくすると円で切れる
- テーマアイコン用の `monochrome` だけは別のベクタ（`drawable/ic_launcher_monochrome.xml`）。
  系統色で塗り直される層なので、写実的な前景を流用しない

## 変更時の作法

- README とコードが食い違ったら**コードが正**。README には実測値（枚数・ミリ秒・MB）が大量に書かれている。
  実測していない値で上書きしない。未検証のものは「未検証」と書く
- 保存形式・圧縮段・撮影間隔・容量上限を変えたら、ルート `README.md` と対象プラットフォームの
  `README.md` を同じ変更で更新する
- 収集データ（`~/ContextCap/`、端末内）は PII を含む前提。リポジトリに入れない（`.gitignore` の
  `/snapshot/` `/snapshots/`）
- **黒画像を無視しない。** DRM / `FLAG_SECURE` の領域は黒塗りで撮影される。撮影は止まらない。
  Android 版は全面が黒いフレームだけ保存せずに捨てるが、**部分的に黒い画面と macOS 版は残る**。
  解析に回すときは除外する（MiaChat 本体 #1523 と同型の罠）
