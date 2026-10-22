import React from "react";
import { AbsoluteFill, interpolate, useCurrentFrame, useVideoConfig } from "remotion";
import { Appear } from "../components/Appear";
import { SceneFade } from "../components/SceneFade";
import { COLOR, FONT } from "../theme";
import { CUES, FADE_OUT } from "../timeline";

/// 黒から、1 行だけ。長く保つ
export const Hook: React.FC = () => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  // シーンの間ずっと 0.98 → 1.00。動いていると気づかない速さ
  const scale = interpolate(frame, [0, durationInFrames], [0.98, 1], { extrapolateRight: "clamp" });
  return (
    <SceneFade fadeOut={FADE_OUT.hook}>
      <AbsoluteFill style={{ justifyContent: "center", alignItems: "center" }}>
        <Appear at={CUES.hook[0]} drift={0}>
          <div
            style={{
              transform: `scale(${scale})`,
              fontFamily: FONT.ja,
              fontWeight: 300,
              fontSize: 76,
              letterSpacing: "0.06em",
              color: COLOR.text,
            }}
          >
            コンテキストは、残らない。
          </div>
        </Appear>
      </AbsoluteFill>
    </SceneFade>
  );
};
