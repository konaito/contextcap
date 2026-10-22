import { loadFont as loadGeist } from "@remotion/google-fonts/Geist";
import { loadFont as loadGeistMono } from "@remotion/google-fonts/GeistMono";
import { loadFont as loadNotoSansJP } from "@remotion/google-fonts/NotoSansJP";
import { JA_SUBSETS } from "./jaSubsets";

const geist = loadGeist("normal", { weights: ["200", "300", "400"], subsets: ["latin"] });
const mono = loadGeistMono("normal", { weights: ["300", "400"], subsets: ["latin"] });
// 型定義は "japanese" と宣言しているが、実データの断片名は "[n]"（scripts/ja-subsets.ts）
const noto = loadNotoSansJP("normal", {
  weights: ["300", "400", "500"],
  subsets: [...JA_SUBSETS, "latin"] as unknown as "latin"[],
});

export const FONT = {
  latin: geist.fontFamily,
  ja: `${geist.fontFamily}, ${noto.fontFamily}`,
  mono: `${mono.fontFamily}, ${noto.fontFamily}`,
};

export const C = {
  bg: "#0A0A0A",
  text: "#EDEDED",
  dim: "rgba(237,237,237,0.55)",
  faint: "rgba(237,237,237,0.22)",
  line: "rgba(255,255,255,0.08)",
  panel: "#111214",
  /// 信号の色。光の走査・見つかった行・ワードマークの光にだけ使う
  signal: "#8FA3B8",
};

/// 跳ねない spring。v3 はテンポを上げるため、入場を 8〜12 フレームで決める
export const SNAP = { damping: 20, stiffness: 190, mass: 0.7 } as const;
export const HEAVY = { damping: 20, stiffness: 120, mass: 0.9 } as const;
export const SOFT = { damping: 22, stiffness: 70, mass: 1 } as const;
