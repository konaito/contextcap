import React from "react";
import { AbsoluteFill, interpolate, Sequence, useCurrentFrame } from "remotion";
import { C, FONT } from "../look";
import { clamp } from "../parts/motion";
import { Caret, Terminal } from "../parts/Terminal";
import { Word } from "../parts/Word";
import { BEAT, LONG_EXPLANATION } from "../timeline";

/// 痛み。長い状況説明を打ち続けるターミナルを、角度を変えて 3 カット。間に 1 語の画面を 2 拍ずつ挟む。
/// 打鍵はカットの開始フレーム（offset）を足した通し時間で進めるので、カットをまたいでも文が続く
const TYPE_START = 4;
const CHARS_PER_FRAME = 0.8;

const TypingShot: React.FC<{ offset: number; rx: number; rz: number; scale: number; drift: number; y: number; band: boolean }> = ({
  offset,
  rx,
  rz,
  scale,
  drift,
  y,
  band,
}) => {
  const local = useCurrentFrame();
  const total = local + offset;
  const typed = Math.max(0, Math.floor((total - TYPE_START) * CHARS_PER_FRAME));
  const body = (
    <Terminal title="~/projects/search — claude">
      <div
        style={{
          position: "absolute",
          inset: "34px 44px",
          display: "flex",
          flexDirection: "column",
          justifyContent: "flex-end",
          fontFamily: FONT.mono,
          fontSize: 34,
          lineHeight: 1.7,
          color: C.text,
        }}
      >
        <div>
          <span style={{ color: C.faint }}>{"> "}</span>
          {LONG_EXPLANATION.slice(0, typed)}
          <Caret frame={total} />
        </div>
      </div>
    </Terminal>
  );
  // ピントは打っている行の帯だけ（Linear の被写界深度）
  const mask = "linear-gradient(180deg, transparent 40%, #000 64%, #000 94%, transparent 100%)";
  return (
    <AbsoluteFill style={{ background: C.bg, justifyContent: "center", alignItems: "center", perspective: 1800 }}>
      <div
        style={{
          position: "relative",
          transform: `translateY(${y}px) scale(${scale + drift * local}) rotateX(${rx}deg) rotateZ(${rz}deg)`,
        }}
      >
        {band ? (
          <>
            <div style={{ filter: "blur(7px)", opacity: 0.75 }}>{body}</div>
            <div style={{ position: "absolute", inset: 0, WebkitMaskImage: mask, maskImage: mask }}>{body}</div>
          </>
        ) : (
          body
        )}
      </div>
    </AbsoluteFill>
  );
};

export const Pain: React.FC = () => {
  const frame = useCurrentFrame();
  // 最後の 1 語は無音の中で出て、拍の頭で消える（音は plan.json の silence で抜く）
  const lastOut = interpolate(frame, [232, 240], [1, 0], clamp);
  const cuts = [
    { at: 0, beats: 4, el: (o: number) => <TypingShot offset={o} rx={24} rz={-10} scale={1.38} drift={0.0015} y={-250} band /> },
    { at: 4, beats: 2, el: () => <Word text="毎回、" size={110} /> },
    { at: 6, beats: 3, el: (o: number) => <TypingShot offset={o} rx={14} rz={7} scale={1.25} drift={0.002} y={-200} band /> },
    { at: 9, beats: 2, el: () => <Word text="最初から、" size={110} /> },
    { at: 11, beats: 3, el: (o: number) => <TypingShot offset={o} rx={8} rz={-3} scale={0.92} drift={0.001} y={0} band={false} /> },
    {
      at: 14,
      beats: 2,
      el: () => (
        <AbsoluteFill style={{ opacity: lastOut }}>
          <Word text="説明してる。" size={110} />
        </AbsoluteFill>
      ),
    },
  ];
  return (
    <AbsoluteFill style={{ background: C.bg }}>
      {cuts.map((c) => (
        <Sequence key={c.at} from={c.at * BEAT} durationInFrames={c.beats * BEAT} layout="none">
          <AbsoluteFill>{c.el(c.at * BEAT)}</AbsoluteFill>
        </Sequence>
      ))}
    </AbsoluteFill>
  );
};
