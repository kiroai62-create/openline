/** PCM helpers for the speech pipeline. Call audio is 16 kHz mono s16le. */

export const PHONE_RATE = 16000;

/** Wraps 16-bit mono PCM in a WAV header (for speech-to-text uploads). */
export function pcmToWav(pcm: Buffer, rate = PHONE_RATE): Buffer {
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(1, 22); // mono
  header.writeUInt32LE(rate, 24);
  header.writeUInt32LE(rate * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write("data", 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

/**
 * Text-to-speech audio -> 16 kHz mono s16le. Accepts a 16-bit PCM WAV (any rate, mono or
 * stereo) or headerless 16-bit PCM at `rawRate`.
 */
export function toPhonePcm(audio: Buffer, rawRate = 24000): Buffer {
  let pcm = audio;
  let rate = rawRate;
  let channels = 1;
  if (audio.length >= 12 && audio.toString("ascii", 0, 4) === "RIFF" && audio.toString("ascii", 8, 12) === "WAVE") {
    let offset = 12;
    let data: Buffer | undefined;
    while (offset + 8 <= audio.length) {
      const id = audio.toString("ascii", offset, offset + 4);
      let size = audio.readUInt32LE(offset + 4);
      const body = offset + 8;
      if (id === "fmt ") {
        const format = audio.readUInt16LE(body);
        channels = audio.readUInt16LE(body + 2);
        rate = audio.readUInt32LE(body + 4);
        const bits = audio.readUInt16LE(body + 14);
        if ((format !== 1 && format !== 0xfffe) || bits !== 16) throw new Error(`unsupported WAV (format ${format}, ${bits} bit)`);
      } else if (id === "data") {
        // Streaming WAVs may carry a placeholder size; use what is there.
        if (size === 0 || size === 0xffffffff || body + size > audio.length) size = audio.length - body;
        data = audio.subarray(body, body + size);
        break;
      }
      offset = body + size + (size % 2);
    }
    if (!data) throw new Error("WAV without data");
    pcm = data;
  }
  let samples = new Int16Array(pcm.buffer.slice(pcm.byteOffset, pcm.byteOffset + (pcm.length - (pcm.length % 2))));
  if (channels > 1) {
    const mono = new Int16Array(Math.floor(samples.length / channels));
    for (let i = 0; i < mono.length; i++) {
      let sum = 0;
      for (let c = 0; c < channels; c++) sum += samples[i * channels + c]!;
      mono[i] = Math.round(sum / channels);
    }
    samples = mono;
  }
  if (rate !== PHONE_RATE) samples = resample(samples, rate, PHONE_RATE);
  return Buffer.from(samples.buffer, samples.byteOffset, samples.byteLength);
}

/** Linear-interpolation resampler for a whole clip. */
export function resample(input: Int16Array, from: number, to: number): Int16Array {
  if (from === to || !input.length) return input;
  const out = new Int16Array(Math.max(1, Math.floor((input.length * to) / from)));
  const step = from / to;
  for (let i = 0; i < out.length; i++) {
    const pos = i * step;
    const a = Math.floor(pos);
    const b = Math.min(a + 1, input.length - 1);
    const frac = pos - a;
    out[i] = Math.round(input[a]! + (input[b]! - input[a]!) * frac);
  }
  return out;
}

/** Root-mean-square level of a 16-bit PCM frame. */
export function rms(frame: Buffer): number {
  const n = Math.floor(frame.length / 2);
  if (!n) return 0;
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const s = frame.readInt16LE(i * 2);
    sum += s * s;
  }
  return Math.sqrt(sum / n);
}
