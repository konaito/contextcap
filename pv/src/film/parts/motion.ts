import { interpolate, spring } from "remotion";
import { HEAVY } from "../look";

export const clamp = { extrapolateLeft: "clamp", extrapolateRight: "clamp" } as const;

/// 0→1 の入場。跳ねない
type Config = { damping: number; stiffness: number; mass: number };

export const enter = (frame: number, at: number, fps: number, config: Config = HEAVY) =>
  spring({ frame: frame - at, fps, config, durationRestThreshold: 0.0005 });

/// [a, b] のフレームで 0→1
export const ramp = (frame: number, a: number, b: number) => interpolate(frame, [a, b], [0, 1], clamp);

/// ぼけから結像する。p は 0→1
export const focusIn = (p: number, maxBlur = 18) => ({
  opacity: p,
  filter: `blur(${(1 - p) * maxBlur}px)`,
});
