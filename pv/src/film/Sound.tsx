import React from "react";
import { Sequence, staticFile } from "remotion";
import { Audio } from "@remotion/media";
import plan from "./plan.json";
import { ACTS, BEAT, LONG_EXPLANATION, SHORT_PROMPT } from "./timeline";

/// 音楽（public/film/music.wav）は plan.json の拍と区切りに合わせて合成してある
/// （scripts/generate_film_audio.py）。ここでは効果音だけを映像のタイミングに置く
const keyFrames = (from: number, start: number, perFrame: number, length: number, until: number, every: number) => {
  const out: number[] = [];
  const end = Math.min(from + start + Math.ceil(length / perFrame), until);
  for (let f = from + start; f < end; f += every) out.push(f);
  return out;
};

// Pain の打鍵は、1 語の画面（4〜6 拍・9〜11 拍・14 拍以降）の間だけ止める
const painKeys = keyFrames(0, 4, 0.8, LONG_EXPLANATION.length, plan.silence.from, 3).filter((f) => {
  const b = f / BEAT;
  return !((b >= 4 && b < 6) || (b >= 9 && b < 11) || b >= 14);
});
const shortKeys = keyFrames(ACTS.short.from, 4, 1.1, SHORT_PROMPT.length, ACTS.short.from + BEAT, 2);

const At: React.FC<{ at: number; src: string; volume?: number }> = ({ at, src, volume = 1 }) => (
  <Sequence from={at} durationInFrames={90} layout="none">
    <Audio src={staticFile(src)} volume={volume} />
  </Sequence>
);

export const Sound: React.FC = () => (
  <>
    <Audio src={staticFile("film/music.wav")} />
    {painKeys.map((f) => (
      <At key={`pk${f}`} at={f} src="film/key.wav" />
    ))}
    {shortKeys.map((f) => (
      <At key={`sk${f}`} at={f} src="film/key.wav" />
    ))}
    <At at={ACTS.text.from + 8} src="film/sweep.wav" />
    <At at={ACTS.short.from + BEAT * 6} src="film/whoosh.wav" />
  </>
);
