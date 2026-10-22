import React from "react";
import { AbsoluteFill, useCurrentFrame, useVideoConfig } from "remotion";
import { C, FONT, SNAP } from "../look";
import { enter, focusIn, ramp } from "../parts/motion";

/// 転換。無音のあと、ヒットと同時に 1 行が結像する
export const Turn: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps, durationInFrames } = useVideoConfig();
  const p = enter(frame, 0, fps, SNAP);
  const out = 1 - ramp(frame, durationInFrames - 6, durationInFrames);
  return (
    <AbsoluteFill style={{ justifyContent: "center", alignItems: "center", opacity: out }}>
      <div
        style={{
          fontFamily: FONT.ja,
          fontWeight: 400,
          fontSize: 104,
          letterSpacing: "0.05em",
          color: C.text,
          transform: `scale(${1.06 - 0.06 * p})`,
          ...focusIn(p, 26),
        }}
      >
        画面は、全部見てた。
      </div>
    </AbsoluteFill>
  );
};
