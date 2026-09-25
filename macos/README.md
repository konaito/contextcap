# ContextCap

あとで解析するための画面記録を溜めておく常駐スクリーンショットレコーダー。
5 秒に 1 回、プライマリディスプレイ全体を撮影してローカルに溜め続けるだけの macOS メニューバーアプリ。

- クリック・入力・送信などの操作は一切しない。撮って保存するだけ
- ネットワーク送信なし。**すべてローカル保存**（アップロード機構を持たない）
- 撮るのはプライマリディスプレイ全体。アクティブウィンドウの切り抜きではない

## 何をするか

| 項目 | 仕様 |
|------|------|
| 撮影間隔 | 5 秒（`CaptureConfig.interval` で変更可） |
| 撮影対象 | プライマリディスプレイ全体 |
| 保存形式 | JPEG **常にフル解像度 quality 0.75**（適応圧縮は 2026-08-18 に廃止） |
| 保存先 | `~/ContextCap/YYYY-MM-DD/HHmmss_SSS[.gN].jpg`（日付ごとのサブディレクトリ） |
| 容量上限 | **40GB**。再圧縮せず、3 日超と超過分を古い順に削除（下記） |
| OCR | 撮影直後に Apple Vision。テキストは `~/ContextCap-analysis/ocr.sqlite` |
| 重複スキップ | なし（正解データ用途なので間引かず全部残す） |
| 黒画面 | 全面が黒いフレームは**保存せずに捨て**、区間だけ `blackouts.jsonl` に残す |
| 途切れの記録 | スリープ・消灯・ロック・撮影失敗・起動終了を `gaps.jsonl` に残す |
| UI | メニューバー常駐のみ（Dock に出ない = `LSUIElement`） |
| 自動起動 | ログイン時に自動起動（`SMAppService`、アプリ内トグルで ON/OFF） |

### メニューバーをクリックすると見えるもの

- **累計枚数** — 保存済みスクショの総数
- **記録期間** — 最初の 1 枚〜最新の 1 枚までの期間（例: `3日と4時間`）
- **合計容量** — 保存先ディレクトリの合計サイズと上限（例: `12.4 GB / 40 GB`）
- **撮影画質** — 常にフル解像度 q0.75
- **保持** — 保持日数と、削除が OCR 済みに限られること
- **OCR** — 追いついているか、追いつき中なら残り枚数
- **前回の削除** — 削除枚数と、未 OCR で保留した枚数
- 一時停止 / 再開
- 保存先を Finder で開く
- ログイン時に起動（トグル）
- 終了

統計はファイルシステムの実測値（起動時にフルスキャン → 以降は撮影ごとに増分更新）。

## 容量管理（3 日保持・再圧縮なし）

**再圧縮はしない。**常に gen0 で撮り、撮った瞬間に OCR し、テキストを確定させてから
画像を捨てる。テキストが本体で、画像は一時キャッシュという位置づけ。

- 3 日（72 時間）を過ぎた画像を削除する（`Retention.retentionDays`）
- 3 日以内でも 40GB を超えたら、古い順に追加で削除する
- **OCR が済んでいない画像は絶対に消さない。**消したらテキストごと永久に失われる。
  消せなかった枚数はメニューの「未 OCR で保留」に出る。0 でないなら OCR が詰まっている
- チェックは 5 分ごと + 起動時（削除は日単位の判断なので撮影ごとにやる必要がない）
- 撮影時刻はファイル属性ではなく**パス（日付ディレクトリ + ファイル名）から解釈**する

容量の見積り: ピーク実績 9,383 枚/日 × 813KB = **7.3GB/日**。3 日で 21.9GB なので 40GB に収まる。
理論最大（24h 稼働・全フレーム差分あり）だと 13.4GB/日 で 3 日 40.2GB と僅かに超えるため、
日数だけでなく容量側の逃げ道も残してある。

### なぜ段階再圧縮をやめたか（2026-08-18）

同一画像の統制実験（60 枚・`lines` 分位で層化・単スレッド計測）で、再圧縮に
**実害しか無い**と分かった。

| 段 | 平均行数 | 文字保持 | 行近似一致(≥0.85) | 平均 OCR ms | 1 枚 |
|----|---------|---------|------------------|-----------|------|
| gen0 | 101.0 | 1.000 | 1.000 | 592 | 813KB |
| g1 (1920/q60) | 97.3 | 0.948 | 0.751 | 575 | 278KB |
| g2 (1280/q50) | 88.5 | 0.891 | 0.568 | 547 | 106KB |
| g3 (800/q40) | 24.1 | 0.258 | 0.056 | 327 | 40KB |

- **縮小しても OCR は速くならない。**時間は解像度ではなく行数に比例する（≒ 5.5ms/行 + 60ms）。
  g3 だけ速いのは行数が 1/4 に落ちている＝読めていないから
- 適応撮影プロファイル（`CaptureGeneration`）も同時に廃止した。一度 g1 が採用されると
  新規撮影まで 1920/q60 に落ちる。実際 `CaptureGeneration = 1` が永続化されていて、
  gen0 が 1 枚も撮られていない状態が続いていた
- この順序ミスにより、2026-08-18 時点の corpus の 39%（20,007 枚）が圧縮後の画像で
  OCR され、未 OCR の 4,068 枚は gen0 が 1 枚も残っていなかった

`.gN` 接尾辞の**パースは残す**（`CaptureFile.legacyGenerationSuffixes`）。
既存アーカイブに `.g1` が 24,148 枚あり、読めないと撮影時刻の解釈と OCR の同一性判定が壊れる。

## OCR（撮影直後）

`OCRIndexer` が Apple Vision で全文 OCR し、`~/ContextCap-analysis/ocr.sqlite` に貯める
（`scripts/ocr-extract.swift` と同じファイル・同じスキーマ・同じ主キー）。

- **撮影 tick を待たせない。**撮影は「保存して enqueue」で終わり。OCR は専用のシリアルキュー
  が後ろで消化する。重いフレームは実測 1,043ms あり、5,000ms の tick 予算に載せると
  スキップが増える
- **キューは 2 段。**撮影直後の分（fresh）を必ず先に処理し、起動時に拾った未 OCR の分
  （backlog）は fresh が空の時だけ 1 枚ずつ進める。1 本にすると追いつき処理が終わるまで
  新規撮影が待たされ、「撮った瞬間に OCR」が成立しない
- **並列にしない。**Vision は内部でシリアライズしていて、スレッドを 2/4/8/12/20 と増やしても
  1.55〜1.57 枚/秒で変わらない（統制測定・同一 24 枚）。メモリ的にも同時に 1 枚だけ持つ
- `qos` は `.userInitiated`。`.utility` だと Apple Silicon で efficiency コアに寄せられ、
  実測 0.4 枚/秒 まで落ちる（同条件の `.userInitiated` は 2.1 枚/秒）
- `usesLanguageCorrection = false`。補正 on 0.54 枚/秒 / off 1.20 枚/秒 で 2.2 倍速く、
  認識行数はむしろ off のほうが多い（5781 → 5817）

速度は問題にならない。撮影 0.2 枚/秒 に対して OCR は単プロセス 1.72 枚/秒 で、**8.6 倍の余裕**がある。

テスト用オーバーライド（通常は未設定のまま）:

```bash
defaults write app.imichat.contextcap StorageBudgetGB -float 0.03   # 上限を30MBに
defaults write app.imichat.contextcap CaptureRoot /path/to/dir      # 保存先変更
defaults delete app.imichat.contextcap StorageBudgetGB              # 戻す
```

## 技術構成

- Swift 6 / AppKit（SwiftUI は使わない。NSStatusItem + NSMenu のみ）
- Swift Package Manager（Xcode プロジェクトなし）+ `.app` バンドル生成スクリプト
- 撮影: **ScreenCaptureKit** `SCScreenshotManager.captureImage`（macOS 14+）
- ログイン時起動: **ServiceManagement** `SMAppService.mainApp`（macOS 13+）
- 対応 OS: macOS 14 Sonoma 以降（開発環境は macOS 26）
- アイコン: `../assets/icon.png`（1024 マスター）から `sips` + `iconutil` でビルドのたびに生成。
  `.icns` はリポジトリに置かない

```
macos/
├── README.md
├── Package.swift
├── Sources/ContextCap/
│   ├── App.swift               # @main エントリポイント（NSApplication 起動）
│   ├── AppDelegate.swift       # StatusItem・メニュー構築
│   ├── CaptureManager.swift    # 5秒タイマー + ScreenCaptureKit 撮影 + 等倍JPEG保存
│   ├── OCRIndexer.swift        # 撮影直後の Vision OCR（fresh/backlog 2段シリアルキュー）
│   ├── Retention.swift         # 3日保持 + 40GB 上限の削除（StorageBudget）
│   ├── CaptureFile.swift       # 命名規約とパスからの撮影時刻解釈
│   ├── StatsStore.swift        # 枚数・期間・容量の集計（フルスキャン + 増分）
│   └── LaunchAtLogin.swift     # SMAppService ラッパー
├── Resources/Info.plist        # LSUIElement=true、CFBundleIconFile=AppIcon
└── scripts/
    └── build-app.sh            # swift build → ContextCap.app 生成 → /Applications へ
                                # ../assets/icon.png から AppIcon.icns も生成する
```

## ビルドと起動

```bash
cd macos
./scripts/build-app.sh          # → build/ContextCap.app を生成し /Applications にコピー
open /Applications/ContextCap.app
```

初回起動時に **画面収録（Screen Recording）権限**を求められる。
システム設定 → プライバシーとセキュリティ → 画面収録 で ContextCap を許可 → アプリ再起動。

ログイン時の自動起動は、メニューの「ログイン時に起動」を ON にすると
`SMAppService` 経由で登録される（システム設定 → 一般 → ログイン項目 に現れる）。

## 実装上の注意

- 撮影は直列。前の撮影＋保存が終わっていなければ次の tick はスキップする
- スリープ・画面ロック中は ScreenCaptureKit が失敗するので、エラーは無視して次の tick へ（クラッシュさせない）
- ディスプレイ構成変更（外部モニタ抜き差し）のたびに `SCShareableContent` を取り直す
- 権限がない状態では撮影を止め、メニューに「権限がありません」を表示する
- 署名は ad-hoc（配布しない前提）。画面収録権限は ad-hoc 署名でも付与できるが、
  **再ビルドすると署名が変わって権限が剥がれる**ので、ビルドのたびに再許可が必要

## 撮影パラメータの考え方

「5 秒間隔・フル解像度・重複スキップなし」は、**情報量を落とさないこと**を優先した結果。

- 間引かないので、同じ画面が続いた区間も何枚撮れたかが分かる
- フル解像度で撮るので、後から縮小・間引きの影響を評価できる。逆はできない
- 容量が問題になった時は、画質を落とさず**古い方から丸ごと削除する**。
  劣化した画像を残すより、OCR 済みのテキストだけを残すほうが情報量が多い
