import React from "react";
import { AbsoluteFill, interpolate, Sequence, useCurrentFrame, useVideoConfig } from "remotion";
import { C, FONT, HEAVY, SNAP } from "../look";
import { clamp, enter, focusIn, ramp } from "../parts/motion";
import { Word } from "../parts/Word";
import { BEAT } from "../timeline";

/// 締め。「手元に、残す。」を 1 小節置いて、ドロップと同時に名前が結像し、光が字の上を一度走る
const Mark: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();
  const mark = enter(frame, 0, fps, HEAVY);
  const shine = interpolate(frame, [6, 36], [-30, 130], clamp);
  const tag = enter(frame, BEAT * 2, fps, SNAP);
  const foot = enter(frame, BEAT * 3, fps, SNAP);
  const out = 1 - ramp(frame, durationInFrames - 40, durationInFrames - 6);
  return (
    <AbsoluteFill style={{ justifyContent: "center", alignItems: "center", opacity: out }}>
      <div
        style={{
          fontFamily: FONT.latin,
          fontWeight: 300,
          fontSize: 176,
          letterSpacing: "-0.035em",
          backgroundImage: `linear-gradient(100deg, ${C.text} 0%, ${C.text} ${shine - 12}%, #ffffff ${shine}%, ${C.signal} ${shine + 6}%, ${C.text} ${shine + 18}%, ${C.text} 100%)`,
          WebkitBackgroundClip: "text",
          backgroundClip: "text",
          color: "transparent",
          transform: `scale(${1.08 - 0.08 * mark})`,
          ...focusIn(mark, 30),
        }}
      >
        ContextCap
      </div>
      <div style={{ marginTop: 34, fontFamily: FONT.ja, fontWeight: 400, fontSize: 38, letterSpacing: "0.12em", color: C.dim, ...focusIn(tag, 10) }}>
        コンテキストしか勝たん。
      </div>
      <div
        style={{
          position: "absolute",
          bottom: 90,
          fontFamily: FONT.mono,
          fontSize: 18,
          letterSpacing: "0.24em",
          color: C.faint,
          ...focusIn(foot, 6),
        }}
      >
        MACOS · ANDROID · LOCAL
      </div>
    </AbsoluteFill>
  );
};

export const Close: React.FC = () => (
  <AbsoluteFill>
    <Sequence from={0} durationInFrames={BEAT * 4} layout="none">
      <AbsoluteFill>
        <Word text="手元に、残す。" size={104} sub="YOUR SCREENS · YOUR CONTEXT" />
      </AbsoluteFill>
    </Sequence>
    <Sequence from={BEAT * 4} layout="none">
      <AbsoluteFill>
        <Mark />
      </AbsoluteFill>
    </Sequence>
  </AbsoluteFill>
);
