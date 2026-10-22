# ContextCap プロモ映像

Remotion で作る 55 秒・1920×1080・30fps のプロモ映像。

## 本番は `ContextCapFilm`（`src/film/`）

120BPM の拍（1 拍 15 フレーム）を骨組みにして、0.5〜2 秒ごとにカットを切り替える。幕は 7 つ。

| 幕 | 秒 | 見せるもの |
|---|---|---|
| pain | 0〜8 | 長い状況説明を打ち続けるターミナルと、1 語の画面「毎回、」「最初から、」「説明してる。」 |
| turn | 8〜10 | 無音のあと、ヒットと同時に「画面は、全部見てた。」 |
| capture | 10〜18 | 画面のカードが 1 拍ごとに積み重なる。撮った枚数が 481,856 まで数え上がる |
| text | 18〜24 | 光の線がなぞって、文字が剥がれる。「画像は、3日で消す。」「文字は、ずっと残す。」 |
| short | 24〜31 | 「さっきのエラー、直して」だけで、手元の文字からエラーの行が見つかる。長い説明が 1 行に潰れる |
| long | 31〜47 | 1 日 1 マスの格子が 1 年先まで伸びる（見込み）。文字の海から引くと、人の輪郭が浮かぶ |
| close | 47〜55 | 「手元に、残す。」のあと、ドロップと同時に ContextCap |

```bash
bun install
bun run film:audio    # public/film/*.wav を合成する（uv が要る）
bun run dev           # Remotion Studio
bun run film:render   # out/contextcap-film.mp4
```

- 尺・幕の区切り・無音・ヒットの位置は `src/film/plan.json` だけで決める。映像（`src/film/timeline.ts`）も
  音の合成（`scripts/generate_film_audio.py`）も同じファイルを読むので、ずれない
- 画面に出す数字は実データ（2026-10-22 時点の Mac の `ocr.sqlite` を読み取り専用で集計）。
  1 年分の字数だけは、1 日あたりの字数 × 365 の見込みで、画面にも「見込み」と出す
- 画面はすべて映像用に作った架空のもの。実際の撮影データは映さない
- 日本語は Noto Sans JP、英字は Geist / Geist Mono。Noto Sans JP の日本語は `[0]`〜`[119]` の断片に
  分かれているので、使う文字の断片だけ読む。セリフを変えたら `bun scripts/ja-subsets.ts` で
  `src/film/jaSubsets.ts` を作り直す
- 音は外部の素材を使わずに合成している。Suno などで作った曲に差し替えるときは、曲の小節に合わせて
  `plan.json` の区切りを動かす

書き出した mp4 で測った値（2026-10-22）: 平均 -18.1 dB・最大 -1.5 dB。転換の直前の 0.5 秒は無音。

## 比較用に残している `ContextCapPromo`（`src/` 直下）

1 本目。黒地に文字が順にフェードインするだけの版で、「浅い・テンポが悪い」と却下された。
比べるためだけに残している。音は `bun run audio`（`scripts/generate_audio.py`）。
