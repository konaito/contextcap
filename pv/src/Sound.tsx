import React from "react";
import { Sequence, staticFile } from "remotion";
import { Audio } from "@remotion/media";
import { CLICK_FRAMES } from "./timeline";

/// 低い帯域のノイズを通しで敷き、文字が出るたびに柔らかいクリックを 1 回。
/// 音量はファイル側で決めてある（ambient は約 -47 dBFS RMS・山 -36 dBFS、click は peak -33 dBFS。
/// scripts/generate_audio.py）。ここでは上げ下げしない
export const Sound: React.FC = () => (
  <>
    <Audio src={staticFile("ambient.wav")} />
    {CLICK_FRAMES.map((at) => (
      <Sequence key={at} from={at} durationInFrames={30} layout="none">
        <Audio src={staticFile("click.wav")} />
      </Sequence>
    ))}
  </>
);
