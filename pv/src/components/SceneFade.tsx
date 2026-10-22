import React from "react";
import { AbsoluteFill, interpolate, useCurrentFrame, useVideoConfig } from "remotion";

/// シーン全体のフェードアウト。退場は fade だけで、動かさない。
/// `tail` は消えきったあと黒のまま保つフレーム数
export const SceneFade: React.FC<{ fadeOut: number; tail?: number; children: React.ReactNode }> = ({
  fadeOut,
  tail = 0,
  children,
}) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  const end = durationInFrames - tail;
  const opacity = interpolate(frame, [end - fadeOut, end], [1, 0], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  return <AbsoluteFill style={{ opacity }}>{children}</AbsoluteFill>;
};
