import React from "react";
import { spring, useCurrentFrame, useVideoConfig } from "remotion";
import { ENTER } from "../theme";

type SpringConfig = { damping: number; stiffness: number; mass: number };

/// 文字の入場。opacity と、ごくわずかな上方向の移動だけ。跳ねさせない
export const Appear: React.FC<{
  at: number;
  drift?: number;
  config?: SpringConfig;
  style?: React.CSSProperties;
  children: React.ReactNode;
}> = ({ at, drift = 10, config = ENTER, style, children }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const p = spring({ frame: frame - at, fps, config, durationRestThreshold: 0.0005 });
  return (
    <div
      style={{
        opacity: p,
        transform: `translateY(${(1 - p) * drift}px)`,
        ...style,
      }}
    >
      {children}
    </div>
  );
};
