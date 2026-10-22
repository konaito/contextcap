import React from "react";
import { AbsoluteFill, Sequence } from "remotion";
import { Grain } from "./components/Grain";
import { Close } from "./scenes/Close";
import { Hook } from "./scenes/Hook";
import { How } from "./scenes/How";
import { LongTerm } from "./scenes/LongTerm";
import { ShortTerm } from "./scenes/ShortTerm";
import { Sound } from "./Sound";
import { COLOR } from "./theme";
import { SCENES } from "./timeline";

export const Promo: React.FC = () => (
  <AbsoluteFill style={{ backgroundColor: COLOR.background }}>
    <Sequence from={SCENES.hook.from} durationInFrames={SCENES.hook.duration}>
      <Hook />
    </Sequence>
    <Sequence from={SCENES.shortTerm.from} durationInFrames={SCENES.shortTerm.duration}>
      <ShortTerm />
    </Sequence>
    <Sequence from={SCENES.longTerm.from} durationInFrames={SCENES.longTerm.duration}>
      <LongTerm />
    </Sequence>
    <Sequence from={SCENES.how.from} durationInFrames={SCENES.how.duration}>
      <How />
    </Sequence>
    <Sequence from={SCENES.close.from} durationInFrames={SCENES.close.duration}>
      <Close />
    </Sequence>
    <Grain />
    <Sound />
  </AbsoluteFill>
);
