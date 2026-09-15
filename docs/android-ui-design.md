# ContextCap Android UI 設計

作成日: 2026-08-15

画面を Jetpack Compose + Material 3 Expressive で作り直す。
撮影ロジックには手を入れない。

## なぜ作り直すか

初期実装は「実行時依存ゼロ」を優先して素の `View` で組んだ。動作の検証には十分だったが、
画面が**典型的な設定画面**（状態 → 統計 → ボタン → プレビューの縦積み）になっていて、
このアプリで一番大事な情報が伝わっていない。

このアプリで一番大事なのは「**ちゃんと動いているか**」であって、設定項目の一覧ではない。
ユーザーは普段このアプリを開かない。開くのは不安になった時だけで、
その時に欲しいのは数字ではなく「今まさに撮れている」という手応え。

## 技術選定

**Jetpack Compose + Material 3 Expressive。**

2026 年 8 月時点で Android の UI ライブラリは M3 Expressive がほぼ唯一の選択肢で、
代替の動きが観測されない。2025 年 5 月発表、Android 16 QPR1 で Pixel にロールアウト済み。
ネイティブ Android では実験フラグなしでフルサポートされる。

- <https://m3.material.io/blog/building-with-m3-expressive>
- <https://developer.android.com/develop/ui/compose/designsystems/material3>

**依存を増やす判断について。** 初期方針は実行時依存ゼロで、APK は release 2.0MB だった。
Compose 導入で 3MB 前後になる見込み。撮影は常駐サービスで動いており、
APK サイズがバッテリーや撮影間隔に影響しないため、この増加は許容する。
**撮影パスには一切依存を持ち込まない**（下記の境界を参照）。

Compose Compiler の適用方法（AGP 9 の built-in Kotlin との併用可否、
`org.jetbrains.kotlin.plugin.compose` の要否）は着手時に実測で確定する。

## 変えない境界

以下は UI に依存しないので**そのまま残す**。

| ファイル | 役割 |
|---|---|
| `CaptureService.kt` | 常駐・撮影・保存・容量管理 |
| `Compactor.kt` | 段階再圧縮 |
| `CaptureFile.kt` | 命名規約 |
| `CompressionRung.kt` | 圧縮段 |
| `StatsStore.kt` | 統計 |
| `CaptureBrowser.kt` | 最新 N 件の取得 |
| `CaptureThumbnail.kt` | 縮小デコード |

unit test 28 件もそのまま通る必要がある。**UI の作り直しでロジックのテストが壊れたら、
それは境界を越えている合図。**

## 画面設計

### 逸脱は部品ではなく構成でやる

ボタン・カード・タイポグラフィは M3 Expressive の標準部品をそのまま使う。
独自性は**何を主役に置くか**と**言葉**で出す。設定項目を並べない。画像を主役にする。
状態を常時見せる。

### ホーム

上から順に:

1. **最新の 1 枚**を画面上部にほぼ全幅で敷く。カードで囲わない。撮れているものが直接見える
2. その上に**状態**を重ねる。記録中は次の撮影までの 10 秒が進むインジケータを出す。
   放置していても「生きている」ことが目に入るのが、このアプリで一番大事な情報
3. **直近 20 枚の横スクロール帯**。タップでビューアへ。何が溜まっているかが流れとして見える
4. **数字**（枚数・期間・容量・画質・保存先）は最下部に小さく。確認したい時だけ読めればいい
5. **一時停止は目立たせない**。誤って止める方が、押しにくいことより損失が大きい

権限が無効なときだけ、最上部に有効化への導線を出す。

### ビューア

全画面で 1 枚。前後移動と、ファイル名・日時・現在位置のオーバーレイ。
M3 の部品に置き換えるが、構造は現状のままでよい（すでに用途を満たしている）。

## 言葉

機能の説明から、観測記録の語彙へ寄せる。

| 旧 | 新 |
|---|---|
| 撮影中（10 秒間隔） | 記録中 · 10 秒ごと |
| 累計枚数 | これまでに見たもの |
| 記録期間 | 記録している期間 |
| 最新の 1 枚（タップで全画面） | いま見えているもの |
| 停止中: アクセシビリティが有効になっていません | 止まっています · 権限がありません |

## パフォーマンス上の制約（既存の実測を引き継ぐ）

UI を作り直しても、以下は絶対に守る。**どちらも実測で踏んだ地雷**。

- **`StatsStore` のフルスキャンを UI スレッドで走らせない。**
  1 件あたり約 0.05ms、10 万件で 5 秒（ANR 閾値）。Compose では `LaunchedEffect` +
  `Dispatchers.IO` に逃がす
- **画像を原寸でデコードしない。** 1080×2364 を `ARGB_8888` で読むと 1 枚 10MB 近い。
  サムネイル帯は複数枚を同時に持つので、`CaptureThumbnail` で小さく読む。
  帯のサムネイルは長辺 300px 程度で足りる

サムネイル帯は最大 20 枚。`CaptureBrowser.latest(root, 20)` は全件走査しないので、
枚数が増えても取得コストは変わらない。

## 検証

- unit test 28 件が引き続き pass すること（ロジックに触れていない証拠）
- エミュレータで、記録中インジケータが進むこと・サムネイル帯がスクロールすること・
  タップでビューアが開くこと
- 大量ファイル（8,000 件以上）でホームの起動が遅くならないこと。
  以前 470ms → 20ms に直した経路を再び塞がない
- サムネイル帯を往復スクロールして Java Heap が増え続けないこと


---

## 実装後に判明したこと（2026-08-15 実測）

設計時の想定と実際が食い違った点を記録する。

### Material 3 Expressive の API は安定版に入っていない

調査では「Compose Material 3 は stable」と読めたが、**Expressive の API は
material3 1.4.0（安定版の最新）では internal** で使えなかった。
`MaterialExpressiveTheme` も `LinearWavyProgressIndicator` も 1.5.0-alpha にしかない。

BOM を入れるだけでは 1.4.0 に解決されるため、`material3:1.5.0-alpha26` を明示して上書きしている。
alpha を採るのは、これが個人用の道具でストア配布もしないため。
安定版に落とす場合は Theme と該当部品の差し替えだけで戻せる。

### compileSdk 37 が必要で、SDK は手で入れる

Compose 1.12 系が compileSdk 37 以上を要求する。36.1 では足りない。
SDK 37 は安定版一覧に出ずプレビュー扱いだが、Android 17 自体はリリース済みで実機も動いている。
cmdline-tools が入っていなかったので、先にそれを入れてから
`sdkmanager "platforms;android-37.0"` で導入した。

### R8 を切ると APK が 23MB になる

Compose は未使用コードが大量に入る。`isMinifyEnabled = false` のままだと **23MB**。
R8 + リソース shrink を有効にすると **1.6MB** で、素の View で組んでいた頃の 2.0MB より小さい。
**Compose 導入で APK が増えるという前提が間違いだった**（R8 を通す限りは）。

R8 を通した APK が壊れていないことは、debug 鍵で署名して実機にインストールし、
記録・閲覧・インジケータが動くことを目視で確認した。

### 撮影ロジックへの越境が 1 箇所だけ発生した（後に取り消し）

**この節の実装は後で撤回した。** 記録の連続性が途切れる方が損だという判断で、
ContextCap 自身も撮るように戻してある。止めたい場合は「アプリ別」から除外指定する
（特別扱いをコードに埋め込まず、他のアプリと同じ仕組みで止める）。以下は当時の記録。



「撮影ロジックには手を入れない」と決めていたが、1 箇所だけ破った。

UI を作り直したことで、**ContextCap 自身の画面が記録を埋め尽くす問題**が顕在化した。
アプリを開いている間も撮り続けるため、確認しに来ると直近が自分のスクリーンショットの
入れ子で埋まり、何が撮れていたか分からなくなる。

`CaptureService.uiInForeground` を足し、Activity の `onResume` / `onPause` で切り替えて、
前面にいる間は撮影をスキップするようにした。検証は「開いている 25 秒で増分 0、
閉じてからの 25 秒で増分 2」で確認済み。

これは UI の問題ではなく仕様の欠落だったので、越境する価値があると判断した。


## lint を 0 件にした際の変更（2026-08-15）

`./gradlew :app:lintDebug` を初めて回したところ error 1 + warning 15 が出た。すべて潰した。

| 指摘 | 対応 |
|---|---|
| `UnspecifiedRegisterReceiverFlag`（**error**） | minSdk を 30 → 33 に上げ、`registerReceiver` の分岐を消して常に `RECEIVER_NOT_EXPORTED` を渡すようにした |
| `OldTargetApi` | targetSdk 36 → 37 |
| `ConfigurationScreenWidthHeight` | `LocalConfiguration` + `LocalDensity` から `LocalWindowInfo.containerSize` へ。px で返るので dp 換算自体が不要になった |
| `MissingApplicationIcon` | adaptive icon を追加（画面の枠＋記録中の点。vector なので画像ファイルを持たない） |
| `DataExtractionRules` | `allowBackup` を廃し、クラウドバックアップと端末間転送の両方から全ドメインを除外。撮影データがバックアップ経由で外に出るのを防ぐ |
| `RedundantLabel` ×2 | activity の冗長な `android:label` を削除 |
| `UseKtx` ×5 | `Bitmap.scale` / `SharedPreferences.edit {}` / `String.toColorInt` へ。core-ktx は既に推移的に入っていたので明示宣言に変えただけ |
| `SetTextI18n` ×4 | debug 専用画面の文字列をリソース化 |
| `ObsoleteSdkInt` | minSdk 33 なので `mipmap-anydpi-v26` の修飾子を外した |
| `GradleDependency` / `AndroidGradlePluginVersion` | core-ktx 1.19.0 / Gradle 9.7.0 へ |

### 副次的に得られたもの

`LocalWindowInfo.containerSize` への移行で、ビューアのデコード解像度が正しくなった。
`screenHeightDp * 3` という概算では 2742px でデコードしていたが、実画面は 2400px。
**約 23% 分を無駄に確保していた**ことになる。

minSdk を上げたことで `registerReceiver` の分岐が消え、`Build.VERSION` の import も不要になった。

### 検証

- `lintDebug` / `lintRelease` とも **0 件**
- unit test 28 件 pass、release APK 1.6MB（変化なし）
- 実機（エミュレータ）で撮影 10 枚、画面 OFF 中の増分 0 / ON 後 3、FATAL 0
- アイコンと UI を目視確認
