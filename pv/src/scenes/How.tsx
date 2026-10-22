import React from "react";
import { CUES, FADE_OUT } from "../timeline";
import { Lines } from "./Lines";

const LINES = ["撮る。", "文字にする。", "手元に残す。", "必要な時に、Claude が読む。"] as const;

export const How: React.FC = () => (
  <Lines lines={LINES} cues={CUES.how} fadeOut={FADE_OUT.how} fontSize={50} gap={40} />
);
