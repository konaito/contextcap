# ContextCap

コンテキストしか勝たん。

AI に意図を汲んでもらうには、自分が何を見て、何をしていたかを渡すしかない。
ContextCap は、パソコンとスマホの画面を数秒おきに撮り続け、文字に起こして手元に溜める。
Claude Code はそれを必要な時に読みに行き、溜まった文脈から意図を汲む。

## なぜ撮り続けるのか

- 声を録り続けるのと同じ考え方で、パソコンとスマホの画面も撮り続ける
- iPhone ではそれができない。だからスマホ版は Android で作っている
- 短期的にも役に立つ。Claude Code が時々ここを読みに来て、自分の文脈から意図を汲んでくれる

## Claude Code から読む

パソコンの文字は SQLite に入っていて、全文検索できる（検索語は 3 文字以上）。

```bash
sqlite3 -readonly ~/ContextCap-analysis/ocr.sqlite \
  "select day, base, text from shots
   where status = 'ok' and text_id in (
     select t.id from line_runs r join texts t on t.id between r.first_text and r.last_text
     where r.line_id in (select rowid from lines_fts where lines_fts match '\"検索語\"'))
   order by ts desc limit 20"
```

スマホの文字と画像は、Tailscale 経由で読む。アプリのホームで「パソコンから読む」をオンにすると、
合鍵入りの URL が出る（既定はオフ）。

```bash
curl -H "Authorization: Bearer $TOKEN" http://<スマホの Tailscale アドレス>:8642/2026-10-22/ocr.jsonl
```

画像も 1 枚ずつ取れる。保存形式と手順の詳細は [`CLAUDE.md`](./CLAUDE.md) と各版の README にある。

## 何をして、何をしないか

- 画面全体を撮って保存するだけ。クリック・入力・送信などの操作は一切しない
- **すべてローカルに保存する**。Android 版だけ、tailnet 上の PC から読める読み取り専用の HTTP サーバーを持つ
  （既定はオフ。オンにすると合鍵付きの URL で読める）。Android 版は ML Kit が診断データを
  Google に送る（[ML Kit のデータ開示](https://developers.google.com/ml-kit/android-data-disclosure)。実際の通信は未計測）
- OCR してテキストを確定させてから画像を消す。画像は数日で消えるが、文字は消さずに残す。
  macOS 版は撮影直後、Android 版は充電中にまとめて OCR する
- 解析機能は持たない。溜めたものは Claude Code や別の道具で読む

macOS 版と Android 版があり、**保存ファイルの命名規約が同一**なので、
回収後の解析スクリプトが両方に効く。

| | macOS 版 | Android 版 |
|---|---|---|
| ディレクトリ | [`macos/`](./macos/) | [`android/`](./android/) |
| 撮影間隔 | 5 秒 | 10 秒 |
| 撮影手段 | ScreenCaptureKit | `AccessibilityService.takeScreenshot()` |
| 容量上限 | 40GB（超過分と 3 日超を削除。再圧縮しない） | 10GB（超過分と 14 日超を削除。再圧縮しない） |
| OCR | 撮影直後に Apple Vision | 充電中に ML Kit（日本語・bundled） |
| 常駐 | メニューバー（`LSUIElement`） | AccessibilityService（常駐通知なし） |
| 画面 | メニューバーのポップアップ + 文字の窓（日付・検索） | ホーム + 全画面ビューア + 文字の画面（日付ごと） |
| 画面 OFF 中 | 失敗を無視して次の tick へ | 能動的に停止 |
| 回収 | `~/ContextCap/` を直接 | Tailscale 経由の `curl`、または `adb pull` |
| 技術 | Swift / AppKit / SwiftPM | Kotlin / AGP / Jetpack Compose + Material 3 |

Android 版の UI は Compose だが、**撮影パス（撮る・保存する・再圧縮する・集計する）は
Compose に一切依存しない**。UI を落としても記録は動く。

## 保存形式（両プラットフォーム共通）

```
<root>/YYYY-MM-DD/HHmmss_SSS[.gN].jpg
```

- `gN` は圧縮段。接尾辞がなければ等倍
- **撮影時刻はファイル属性ではなくパスから解釈する。**
  再圧縮でファイルが作り直されても期間集計・古い順判定が壊れないため

| 段 | ファイル名 | 長辺 | quality |
|----|-----------|------|---------|
| gen0 | `HHmmss_SSS.jpg` | 等倍 | 0.75 / 75 |
| g1 | `HHmmss_SSS.g1.jpg` | 1920 | 0.6 / 60 |
| g2 | `HHmmss_SSS.g2.jpg` | 1280 | 0.5 / 50 |
| g3 | `HHmmss_SSS.g3.jpg` | 800 | 0.4 / 40 |

### 段階再圧縮は両プラットフォームで廃止した（2026-08-18）

**もう再圧縮しない。**常に gen0 で撮って OCR し（Android は充電中に）、保持期間を過ぎた
「OCR 済み」画像を削除する。**OCR が済んでいない画像は絶対に消さない。**

保持期間と OCR エンジンだけが非対称:

| | macOS | Android |
|---|---|---|
| OCR | Apple Vision | ML Kit Text Recognition v2（日本語・bundled） |
| 出力 | `~/ContextCap-analysis/ocr.sqlite` | `<day>/ocr.jsonl` |
| 1 枚 | 813KB（3600px） | 95.3KB（1080x2400） |
| 上限 / 保持 | 40GB / 3 日 | 10GB / 14 日 |
| スループット | 1.72 枚/秒（撮影の 8.6 倍） | 7.7 枚/秒（撮影の 77 倍・エミュレータ実測） |

どちらもキューは 2 段（撮影直後を優先、追いつきは暇な時だけ）で、**並列にしない**。

macOS の `ocr.sqlite` は 2026-10-23 から文字を 1 行ずつの辞書で持つ（schema 2）。
同じ画面を 5 秒おきに読むので、486,622 枚の本文 1.54GB のうち種類の違う行は 118MB しかない。
検索索引もこの辞書にだけ張り、ファイルは 6.86GB から 1.00GB になった。検索結果は前と同じ。
`shots` は前と同じ列を返すビューとして残している。詳細は `macos/README.md` の「文字は 1 行ずつの辞書で持つ」。

2026-10-30 から、macOS は OCR の行を画面の境目（分割ペインの境目・タブバー・枠線）で割り、
領域ごとに並べて空行で区切ってから保存する。分割画面で左右のペインの行が交互に並ばない。
詳細は `macos/README.md` の「分割画面の境目で行を割る」。Android は変えていない。

廃止した理由は、同一画像の統制実験（60 枚・`lines` 分位で層化）で
**再圧縮に実害しか無いと分かった**から。

| 段 | 平均行数 | 文字保持 | 行近似一致(≥0.85) | 平均 OCR ms | 1 枚 |
|----|---------|---------|------------------|-----------|------|
| gen0 | 101.0 | 1.000 | 1.000 | 592 | 813KB |
| g1 | 97.3 | 0.948 | 0.751 | 575 | 278KB |
| g2 | 88.5 | 0.891 | 0.568 | 547 | 106KB |
| g3 | 24.1 | 0.258 | 0.056 | 327 | 40KB |

- **縮小しても OCR は速くならない。**時間は解像度ではなく行数に比例する（≒ 5.5ms/行 + 60ms）。
  g3 だけ速いのは行数が 1/4 に落ちている＝読めていないから
- 適応撮影プロファイル（`CaptureGeneration`）も廃止。一度 g1 が採用されると
  新規撮影まで 1920/q60 に落ち、実際 gen0 が 1 枚も撮られない状態が続いていた
- この順序ミスで、2026-08-18 時点の corpus の 39%（20,007 枚）が圧縮後の画像で
  OCR され、未 OCR の 4,068 枚は gen0 が 1 枚も残っていなかった

`.gN` 接尾辞の**パースは残す**。既存アーカイブに `.g1` が 24,148 枚あり、
読めないと撮影時刻の解釈と OCR の同一性判定が壊れる。

保存形式そのもの（パス規約・時刻の読み方）は Swift と Kotlin に別々に実装されている。
**片方だけ変えると解析側が壊れる。**

## Android 版だけにあるもの

スマホは「どのアプリを見ていたか」がそのまま文脈になるので、Android 版だけ前面アプリを記録する。

```
<root>/YYYY-MM-DD/apps.jsonl
```

- 前面アプリが**切り替わった瞬間だけ** 1 行（`{"t":"HHmmss_SSS","pkg":...,"label":...}`）。
  撮影のたびに書くと 1 日 8,000 行を超えるが、変化点だけなら 1 日数十行で収まる
- 解析側は「この時刻以降はこのアプリ」と読む。最初の記録より前の画像は所属不明

この索引の上に、アプリ別の枚数・容量集計、アプリ単位の一括削除、アプリ単位の撮影除外、
名前での絞り込みが乗っている。画像 1 枚単位の削除はビューアから行う。
撮影を止めたアプリは一覧の先頭に別グループで固定し、**画像が 0 枚でも行を消さない**
（消すと一覧から辿れなくなり、解除できなくなる）。

もう 1 つ、**情報のない画面は保存しない**（これは macOS 版も同じ）。DRM 保護（`FLAG_SECURE`）の画面と
AOD は撮れても中身が真っ黒なので、画像は捨てて区間だけを `blackouts.jsonl` に残す。

```
<root>/YYYY-MM-DD/blackouts.jsonl
{"t":"213040_120","state":"start"}
```

2 時間の映画なら 720 枚の黒い JPEG が 2 行になる。黒でも `apps.jsonl` には残すので、
「Netflix を 2 時間見ていた、画面は取れていない」までは復元できる。

ContextCap 自身の画面も撮る。止めたければ他のアプリと同じく「アプリ別」から除外指定する。

macOS 版に `apps.jsonl` 相当はない。

## アイコン

`assets/icon.png`（1024 マスター）1 枚から両プラットフォーム分を生成する。
macOS は `.icns` をビルドのたびに作り、Android は
`python3 scripts/make-android-icon.py` でアダプティブアイコンの前景を書き出す。

## セットアップ

- macOS: [`macos/README.md`](./macos/README.md)
- Android: [`android/README.md`](./android/README.md)

実装・検証の作法は [`CLAUDE.md`](./CLAUDE.md)。

## エージェントから読む（macOS）

`scripts/contextcap-mcp.py` は `ocr.sqlite` を読むだけの MCP サーバー（stdio）。
エージェントが SQL を書かずに、語での検索・期間の一覧・記録の状態を引ける。書き込みの口は無い。

```bash
claude mcp add contextcap -- /path/to/contextcap/scripts/contextcap-mcp.py
```

| ツール | 返すもの |
|---|---|
| `contextcap_search` | 語を含む行を 10 分ごとの窓にまとめ、当たった語の多い窓・新しい窓から。窓ごとに当たった行だけ |
| `contextcap_timeline` | 期間内の文字を 10 分ごとに。窓の中で同じ行は 1 回だけ |
| `contextcap_status` | 記録の範囲、最新の OCR 済みフレームの時刻、直近 1 時間の件数 |

`"..."` で囲んだ句が 1 件も当たらなければ、前半と後半に割って引き直す（OCR の 1 文字誤読を拾う）。
ベクトル検索は入れていない。比べた結果と理由は
[`docs/results/2026-10-23-search-eval.md`](./docs/results/2026-10-23-search-eval.md)。
Android の文字は対象外で、端末の `RemoteServer` から読む。

## 設計文書

- [`docs/android-design.md`](./docs/android-design.md) — Android 版の設計と、
  なぜ MediaProjection ではなく AccessibilityService を使うのか
- [`docs/android-ui-design.md`](./docs/android-ui-design.md) — Android 版の画面設計
  （設定画面ではなく観測ダッシュボードとして組む、という判断）
- [`docs/android-plan.md`](./docs/android-plan.md) — Android 版の実装計画

## プライバシー上の性質

画面全体を数秒おきに撮るため、通知・メッセージ・決済画面などが写り得る。
撮影データを外部へ送る処理はどちらの版も持たないので、データは手で回収するまで端末内に留まる。
Android 版は `INTERNET` permission を宣言している。アプリ自身の使い道は、tailnet のアドレスでだけ
待ち受ける読み取り専用サーバー（`RemoteServer`）だけで、既定はオフ、合鍵が無いと読めない。
書き込み・削除の口は無い。ML Kit の診断データ送信はこれとは別にある（上記）。

回収したデータを解析に使う際は、PII を含む前提で扱うこと。
`apps.jsonl` は画像そのものではないが、利用アプリの遷移という別種の機微情報になる。

どちらの版も、保護された領域は**黒く塗られた状態で撮影される**（撮影自体は止まらない）。

- macOS 版は DRM 保護コンテンツの領域が黒くなり、**そのままファイルとして保存される**
- Android 版は `FLAG_SECURE` のウィンドウが黒くなる（Android 16 で実測）。
  全面が黒ければ保存せず区間だけ残すが、**部分的に黒い画面はそのまま保存される**
- どちらもアプリ側の宣言に依存するので、保護の保証にはならない

**全面が黒いフレームは、どちらの版も保存せずに捨てる。** 判定は保存の前に置いてある
（後段で落とすと、黒画面を annotator が誤読して閉ループを作った件と同型の罠になる）。ただし**部分的に黒い画面は残る**ので、解析に回すときは除外すること。

判定は「黒いピクセルの割合」ではなく格子のセル単位で行う。真っ黒地に白文字のダークモード画面は
9 割以上のピクセルが黒く、割合だけでは保護画面と区別が付かないため。

## 観測できなかった区間のログ

画像を保存しなかった区間も、**その事実だけは JSONL に残す**。変化点方式で、状態が変わった
瞬間だけ 1 行書く。日付をまたいで継続していたら翌日のファイルの先頭にも書き直すので、
日ごとのファイルだけ見れば区間が復元できる。

| ファイル | 対象 | macOS | Android |
|---|---|---|---|
| `blackouts.jsonl` | 全面が黒くて捨てたフレームの区間 | ✅ | ✅ |
| `gaps.jsonl` | **撮影が途切れた理由**（スリープ／消灯／ロック／撮影失敗／アプリ起動・終了） | ✅ | — |
| `apps.jsonl` | 前面アプリが切り替わった時刻 | — | ✅ |
| `keys.jsonl` | キー押下と使用ショートカット（前面アプリ付き・押下のみ・保持無期限） | ✅ | — |

```
<root>/YYYY-MM-DD/blackouts.jsonl   {"t":"HHmmss_SSS","state":"start"|"end"}
<root>/YYYY-MM-DD/gaps.jsonl        {"t":"HHmmss_SSS","state":"sleep","reason":"..."}
<root>/YYYY-MM-DD/keys.jsonl        {"t":"HHmmss_SSS","key":"c","mods":["cmd"],"app":"com.google.Chrome"}
```

`keys.jsonl` は macOS 版のみ。要アクセシビリティ権限で、詳細は `macos/README.md`。

`gaps.jsonl` が無いと、スクショが無い時間について「PC の前にいなかった」のか
「スリープしていた」のか「ロック中で撮影が失敗していた」のかを解析側で区別できない。
`state` は `app_start` / `app_stop` / `sleep` / `wake` / `screens_off` / `screens_on` /
`locked` / `unlocked` / `capture_failed` / `capture_ok`。`reason` は任意。
