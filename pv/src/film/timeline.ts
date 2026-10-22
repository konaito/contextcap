// 尺の正本は plan.json（音の合成スクリプトも同じファイルを読む）。ここは映像側の派生値だけ
import plan from "./plan.json";

export const FPS = plan.fps;
/// 1 拍のフレーム数（120BPM で 15）
export const BEAT = plan.beat;
export const BAR = BEAT * 4;

type ActKey = keyof typeof plan.acts;
export const ACTS = Object.fromEntries(
  (Object.keys(plan.acts) as ActKey[]).map((k) => [k, { from: plan.acts[k].from, duration: plan.acts[k].to - plan.acts[k].from }]),
) as Record<ActKey, { from: number; duration: number }>;

export const TOTAL = plan.acts.close.to;

/// 実データ（2026-10-22 時点の Mac の ocr.sqlite を読み取り専用で集計）
export const FACTS = {
  shots: 481_856,
  days: 77,
  chars: 814_244_686,
  since: "2026.08.03",
  /// 1 日あたりの字数 × 365。見込みであって実測ではないので、画面でも「このペースで」と書く
  charsPerYear: Math.round((814_244_686 / 77) * 365),
};

export const LONG_EXPLANATION =
  "さっき Chrome で見てたエラーなんだけど、コンソールに TypeError って出てて、" +
  "たしか items が undefined になってて、昨日 useSearch のフックを直したあとからだと思う。" +
  "画面は検索結果の一覧で、2 ページ目に進むと落ちる。そのとき開いてたのは staging の方で、" +
  "ログインはテスト用のアカウントで……";

export const SHORT_PROMPT = "さっきのエラー、直して";
export const FOUND_LINE = "TypeError: Cannot read properties of undefined (reading 'items')";
