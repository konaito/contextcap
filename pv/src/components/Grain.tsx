import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";

/// ごく薄い粒子感。種をフレームから決めるので、描画はフレームだけで決まる
export const Grain: React.FC<{ opacity?: number }> = ({ opacity = 0.045 }) => {
  const frame = useCurrentFrame();
  // 毎フレーム変えるとちらつくので、2 フレームごとに種を替える
  const seed = Math.floor(frame / 2) % 97;
  return (
    <AbsoluteFill style={{ opacity, pointerEvents: "none", mixBlendMode: "screen" }}>
      <svg width="100%" height="100%" xmlns="http://www.w3.org/2000/svg">
        <filter id="grain">
          <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" seed={seed} stitchTiles="stitch" />
          <feColorMatrix type="saturate" values="0" />
        </filter>
        <rect width="100%" height="100%" filter="url(#grain)" />
      </svg>
    </AbsoluteFill>
  );
};
