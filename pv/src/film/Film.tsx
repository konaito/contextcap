import React from "react";
import { AbsoluteFill, Sequence } from "remotion";
import { Backdrop, Grain, Vignette } from "./parts/Stage";
import { Capture } from "./scenes/Capture";
import { Close } from "./scenes/Close";
import { Long } from "./scenes/Long";
import { Pain } from "./scenes/Pain";
import { Short } from "./scenes/Short";
import { Text } from "./scenes/Text";
import { Turn } from "./scenes/Turn";
import { Sound } from "./Sound";
import { ACTS } from "./timeline";

const ACT_COMPONENTS = {
  pain: Pain,
  turn: Turn,
  capture: Capture,
  text: Text,
  short: Short,
  long: Long,
  close: Close,
} as const;

export const Film: React.FC = () => (
  <AbsoluteFill>
    <Backdrop />
    {(Object.keys(ACTS) as (keyof typeof ACTS)[]).map((key) => {
      const Act = ACT_COMPONENTS[key];
      return (
        <Sequence key={key} from={ACTS[key].from} durationInFrames={ACTS[key].duration} name={key}>
          <Act />
        </Sequence>
      );
    })}
    <Vignette />
    <Grain />
    <Sound />
  </AbsoluteFill>
);
