import { loadFont as loadGeist } from "@remotion/google-fonts/Geist";
import { loadFont as loadNotoSansJP } from "@remotion/google-fonts/NotoSansJP";

// 英字（ContextCap）は Geist、日本語は Noto Sans JP。Inter / Geist には日本語の字形が無い
const geist = loadGeist("normal", { weights: ["200", "300"], subsets: ["latin"] });
// Noto Sans JP の日本語は [0]〜[119] の断片に分かれている。全部読むと 1 ウェイト 120 個になるので、
// 映像に出てくる文字が入っている断片だけを読む。セリフを変えたら `bun scripts/ja-subsets.ts` で出し直す
// （scenes/*.tsx の非 ASCII 文字をすべて拾うので、コメントの文字の分だけ多めに入る）
const JA_SUBSETS = [
  "[62]", "[97]", "[99]", "[102]", "[103]", "[104]", "[105]", "[106]", "[107]", "[108]",
  "[109]", "[110]", "[111]", "[112]", "[113]", "[114]", "[115]", "[116]", "[117]", "[119]",
] as const;
// 型定義（d.ts）は subsets を "japanese" などと宣言しているが、実データの断片名は "[n]"。
// "japanese" を渡すと実行時に「not available」で落ちるので、実データに合わせて型を外す
const noto = loadNotoSansJP("normal", {
  weights: ["300", "400"],
  subsets: [...JA_SUBSETS, "latin"] as unknown as "latin"[],
});

export const FONT = {
  latin: geist.fontFamily,
  // 日本語の行に英字（AI・Claude）が混ざるので、英字は Geist を先に当てる
  ja: `${geist.fontFamily}, ${noto.fontFamily}`,
};

export const COLOR = {
  background: "#0A0A0A",
  text: "#EDEDED",
  /// 補助の行。白のまま濃度だけ落とす
  muted: "rgba(237, 237, 237, 0.52)",
  /// アクセントは 1 か所だけ（Scene 3 の「シグナル」）
  accent: "#6B7A8A",
};

/// 入場の spring。跳ねない・遅い・重い
export const ENTER = { damping: 20, stiffness: 32, mass: 1.2 } as const;
/// 補助の行は、さらに遅く
export const ENTER_SLOW = { damping: 22, stiffness: 18, mass: 1.4 } as const;

/// 行の左端。左揃えのシーンで共通にする
export const MARGIN_X = 300;
