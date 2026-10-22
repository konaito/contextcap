// 尺とタイミングの正本。シーンとクリック音の両方がここを読む（映像と音をずらさないため）
export const FPS = 30;
export const WIDTH = 1920;
export const HEIGHT = 1080;

const s = (seconds: number) => Math.round(seconds * FPS);

/// 各シーンの開始と長さ（フレーム）。シーン同士は重ねず、間は黒を挟む
export const SCENES = {
  hook: { from: 0, duration: s(8) },
  shortTerm: { from: s(8), duration: s(10) },
  longTerm: { from: s(18), duration: s(12) },
  how: { from: s(30), duration: s(15) },
  close: { from: s(45), duration: s(10) },
} as const;

export const TOTAL_FRAMES = SCENES.close.from + SCENES.close.duration;

/// 各シーンの中で、文字が出始めるフレーム（シーン先頭からの相対）
export const CUES = {
  hook: [s(1)],
  shortTerm: [s(0.7), s(3.4), s(6.1)],
  longTerm: [s(1), s(4.2)],
  how: [s(0.7), s(3.7), s(6.7), s(9.7)],
  close: [s(1), s(3.4)],
} as const;

/// シーン末尾のフェードアウトの長さ。close だけ極端に遅くし、最後は黒で終わる
export const FADE_OUT = {
  hook: s(1.2),
  shortTerm: s(1.2),
  longTerm: s(1.4),
  how: s(1.4),
  close: s(3),
} as const;

/// close は文字が消えきってから、黒のまま `CLOSE_TAIL` だけ保つ
export const CLOSE_TAIL = s(1);

/// クリック音を鳴らす絶対フレーム。文字が出るたびに 1 回
export const CLICK_FRAMES: number[] = (Object.keys(CUES) as (keyof typeof CUES)[]).flatMap((key) =>
  CUES[key].map((cue) => SCENES[key].from + cue),
);
