# ContextCap

あとで解析するための画面記録を溜めておく常駐スクリーンショットレコーダー。
5 秒に 1 回、プライマリディスプレイ全体を撮影してローカルに溜め続けるだけの macOS メニューバーアプリ。

- クリック・送信などの操作（自動化）は一切しない。撮って保存するだけ
- キー入力（押下）と使用ショートカットを `keys.jsonl` に記録する。入力を代行するわけではなく、観測して残すだけ
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
| キー記録 | キー押下と修飾キー（ショートカット）を前面アプリ付きで `<day>/keys.jsonl` に残す。押下のみ・保持無期限・要アクセシビリティ権限 |
| UI | メニューバー常駐のみ（Dock に出ない = `LSUIElement`） |
| 自動起動 | 既定はオフ。メニューの「ログイン時に起動」で ON にした時だけ登録する（`SMAppService`） |

### メニューバーをクリックすると見えるもの

- **累計枚数** — 保存済みスクショの総数
- **画像の期間** — 残っている最初の 1 枚〜最新の 1 枚までの期間（例: `3日と4時間`）
- **文字** — 読み取った文字が残っている日数と最初の日（例: `76 日分（2026-08-03〜）`）。
  画像は 3 日で消すが `ocr.sqlite` の行は消さないので、画像の期間とは別に数える
- **合計容量** — 保存先ディレクトリの合計サイズと上限（例: `12.4 GB / 40 GB`）
- **撮影画質** — 常にフル解像度 q0.75
- **保持** — 保持日数と、削除が OCR 済みに限られること
- **OCR** — 追いついているか、追いつき中なら残り枚数
- **前回の削除** — 削除枚数と、未 OCR で保留した枚数
- 一時停止 / 再開 — 撮影とキー記録の両方を止める・再開する
- 読み取った文字を開く… — 日付 / その日の区間 / 全文の 3 段の窓。上の欄で全期間を検索する。
  同じ文字が続く間は 1 区間にまとめる。3 文字以上は FTS5 trigram、2 文字以下は新しい順の走査で、
  どちらも新しい順に 300 件で打ち切る。画像が残っている区間だけ「画像を開く」が押せる
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
（`scripts/ocr-extract.swift` も `OCRStore.swift` を一緒にコンパイルして、同じファイルに同じ形で書く）。

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

### 文字は 1 行ずつの辞書で持つ

5 秒おきに同じ画面を読むので、同じ行が何千回も出る。2026-10-23 の 486,622 枚では、
本文 1.54GB のうち種類の違う行は 2,725,936 種類・118MB（7.7%）しかなかった。
そこで `ocr.sqlite` は行の辞書で持つ（schema 2、`OCRStore`）。

| テーブル | 中身 |
|---|---|
| `lines` | 行の辞書。検索索引 `lines_fts`（FTS5 trigram）はここにだけ張る |
| `texts` | 1 枚分の本文。行番号の並びを JSON 配列で持つ。同じ本文は 1 つにまとめる |
| `line_runs` | 行 → その行を含む本文の範囲。本文には撮影順に連番を振るので、続けて撮った画面に同じ行が出る間は 1 行で済む |
| `frames` | 1 枚ごとのメタデータと `text_id` |
| `shots`（ビュー） | 旧形式と同じ列を返す。`select text from shots` はそのまま通る |

- **検索の結果は旧形式と同じ。** 改行を含まない語は 1 行の中でしか当たらないので、行ごとに索引を張っても
  取りこぼしは出ない。移行のときに全行の本文と列、9 語の検索結果、索引の整合性を突き合わせている
- 2026-10-23 の移行で 6.86GB → 1.00GB（14.5%）。いちばん大きかったのは本文ではなく trigram 索引
  （本文 1.54GB に対して 3.27GB）で、辞書の 118MB にだけ張れば 237MB で済む。
  移行後の内訳は `texts` 281MB・`lines_fts` 237MB・`lines` 171MB・`line_runs` 131MB・`frames` 63MB
- 検索は速くなった（「ContextCap」0.83 → 0.13 秒、2 文字の「会議」3.71 → 0.54 秒、日の一覧 0.14 秒）
- ここより先に縮めるには、行番号の並びを JSON ではなくバイナリにすることになる。そうすると
  `sqlite3` コマンドから読めなくなる（`contextcap` スキルは素の SQL で読む）ので、そこで止めた
- 1 枚の書き込み（辞書・本文・範囲・frames）は 1 トランザクション。本文の連番が `line_runs` の前提なので、
  `ocr-extract` と同時に書いてもずれない
- **アプリは旧形式の DB には書かない。** 撮影は続き、Retention は未 OCR の画像を消さないので、
  `scripts/ocr-compact.py` で移行したあとに backlog として読む

```bash
pkill -x ContextCap
./scripts/ocr-compact.py          # ocr.sqlite.new を作って全件で検算する
./scripts/ocr-compact.py --swap   # 検算が通ったら差し替える（旧は ocr.sqlite.bak-<日時> に退避）
open /Applications/ContextCap.app
```

### スリープ復帰後に Vision が壊れる

**撮影直後の OCR が 10 回続けて失敗したら、アプリが自分で起動し直す**（`OCRFailureStreak`、
1 時間に 1 回まで）。起動時の reconcile は **failed の行も読み直す**。

- 実測（2026-10-09・10・21・22）では、スリープ復帰の直後から全件が `CRImageReaderError 1` で
  失敗し、プロセスを再起動するまで戻らなかった。10-22 は 08:30〜16:04 に 4,983 件、
  10-21 は 12:50〜23:02 に 4,711 件。4 日で合わせて 16,698 件
- 失敗した画像を別プロセスで読ませると通った（3 枚中 3 枚）。画像ではなく、プロセスの中の状態の問題。
  復帰のたびに起きるわけではなく、壊れる仕組みは確かめていない
- 以前は failed を読み直さなかったので、その画面の文字が欠けたままになっていた。
  画像は `Retention` が failed を消さないので 16,698 枚とも残っていて、読み直しで取り返せる
- 数えるのは撮影直後の分（fresh）だけ。backlog の読み直しの失敗では再起動しない

テスト用オーバーライド（通常は未設定のまま）:

```bash
defaults write app.imichat.contextcap StorageBudgetGB -float 0.03   # 上限を30MBに
defaults write app.imichat.contextcap CaptureRoot /path/to/dir      # 保存先変更
defaults delete app.imichat.contextcap StorageBudgetGB              # 戻す
```

## キー記録（`keys.jsonl`）

`KeyLog` が `NSEvent.addGlobalMonitorForEvents(matching: [.keyDown])` でキー押下を拾い、
日付ごとの `<root>/<day>/keys.jsonl` に 1 行ずつ追記する。撮影・OCR とは独立に動くが、
メニューの「一時停止」では撮影と一緒に止まる（以前は撮影だけ止まり、キー記録は続いていた）。

1 行の形（`gaps.jsonl` / `blackouts.jsonl` と `t` を揃えてある）:

```json
{"app":"com.google.Chrome","key":"c","mods":["cmd"],"t":"162303_273"}
```

- `t` … `HHmmss_SSS`（`CaptureFile.timeStem`）。同時刻のスクショと突き合わせられる
- `key` … 修飾を無視した基底キー（`charactersIgnoringModifiers`）か、特殊キー名（`return`/`space`/`left`/`f5` …）
- `mods` … 押されていた修飾キー（`cmd`/`opt`/`ctrl`/`shift`/`fn`）。無ければ省く。ショートカットの識別に使う
- `app` … 前面アプリの bundle id。**⌘W の意味はアプリで変わる**ので必ず添える

決め事:

- **押下（keyDown）だけ。**離す（keyUp）は取らない。行数が半分で済み、打鍵と使用ショートカットの
  復元にはこれで足りる
- **保持は無期限。**`Retention` は `.jpg` しか消さないので、この JSONL は削除対象に入らない
  （テキストなので容量は画像より桁違いに小さい）
- **要アクセシビリティ権限。**無いとモニタは 1 件も配信しない（黙って空振り）。`AXIsProcessTrusted()`
  で確認し、無ければプロンプト＋10 秒ごとに再試行する。メニューに「キー記録: 有効／権限が必要」を出す。
  ad-hoc 署名の場合は、再ビルドで画面収録と同様この権限も剥がれる（Developer ID 署名なら外れない）
- **IME の変換前ローマ字しか取れない。**確定後の日本語は画面の OCR 側で補う前提
- **パスワードは基本入らない。**セキュア入力中は OS が secure event input を有効にし、
  グローバルモニタにキーが届かない。とはいえ通常テキストは全部残るので、`~/ContextCap` は
  他人に渡さない前提で扱う

**Android には対応物が無い。**アクセシビリティサービスの `TYPE_VIEW_TEXT_CHANGED` 等で
似たことは可能だが、契約（保存形式）には手を入れていない。

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
│   ├── OCRStore.swift          # ocr.sqlite への書き込み（行の辞書・本文・範囲・frames）
│   ├── Retention.swift         # 3日保持 + 40GB 上限の削除（StorageBudget）
│   ├── CaptureFile.swift       # 命名規約とパスからの撮影時刻解釈
│   ├── StatsStore.swift        # 枚数・期間・容量の集計（フルスキャン + 増分）
│   ├── TextArchive.swift       # ocr.sqlite の読み取り専用接続（日の一覧・区間・検索）
│   ├── TextBrowserWindow.swift # 「読み取った文字」の窓（AppKit）
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
- 署名は、キーチェーンに Developer ID Application の証明書があればそれで行う（`--options runtime --timestamp`）。
  designated requirement が identifier + チーム ID になり、**再ビルドしても権限は外れない**。
  証明書が無ければ ad-hoc になり、designated requirement が cdhash そのものなので、ビルドのたびに再許可が要る。
  `SIGN_IDENTITY` で明示でき、`SIGN_IDENTITY=-` で ad-hoc を強制する。公証（notarize）はまだしていない

## 撮影パラメータの考え方

「5 秒間隔・フル解像度・重複スキップなし」は、**情報量を落とさないこと**を優先した結果。

- 間引かないので、同じ画面が続いた区間も何枚撮れたかが分かる
- フル解像度で撮るので、後から縮小・間引きの影響を評価できる。逆はできない
- 容量が問題になった時は、画質を落とさず**古い方から丸ごと削除する**。
  劣化した画像を残すより、OCR 済みのテキストだけを残すほうが情報量が多い
