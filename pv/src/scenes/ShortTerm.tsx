import React from "react";
import { CUES, FADE_OUT } from "../timeline";
import { Lines } from "./Lines";

const LINES = ["AIは、今しか持っていない。", "直近の意図は、画面の中にある。", "残さないと、汲めない。"] as const;

export const ShortTerm: React.FC = () => (
  <Lines lines={LINES} cues={CUES.shortTerm} fadeOut={FADE_OUT.shortTerm} fontSize={54} gap={44} />
);
