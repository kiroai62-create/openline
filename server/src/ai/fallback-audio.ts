import { WebSocket } from "ws";
import type { SettingsStore } from "../db.js";
import { PHONE_RATE } from "./audio.js";
import type { AiRouter } from "./router.js";

/**
 * Pre-recorded phrases for when the AI can't speak:
 * - hold:  played once while the call switches to another AI provider,
 * - sorry: played when every provider failed, right before hanging up, so a caller never just
 *          hears the line go dead.
 *
 * They are synthesised in advance (with any working text-to-speech provider, or the Gemini key)
 * and stored in the database, so they still play when every AI provider is down. Without any
 * stored audio a short tone is played instead.
 */
export type FallbackKind = "hold" | "sorry";

export const DEFAULT_FALLBACK_TEXT: Record<FallbackKind, string> = {
  hold: "एक पल, कृपया लाइन पर बने रहिए।",
  sorry: "माफ़ कीजिए, अभी हमारी लाइन में तकनीकी दिक्कत है। कृपया थोड़ी देर बाद कॉल करें। धन्यवाद।",
};

interface Stored {
  text: string;
  pcm: string; // base64 16 kHz s16le
  at: string;
}

const FRAME_BYTES = 640;

export class FallbackAudio {
  private refreshing?: Promise<void>;

  constructor(
    private readonly settings: SettingsStore,
    private readonly router: AiRouter,
  ) {}

  text(kind: FallbackKind): string {
    return this.settings.get<Partial<Record<FallbackKind, string>>>("fallbackText")?.[kind]?.trim() || DEFAULT_FALLBACK_TEXT[kind];
  }

  setText(texts: Partial<Record<FallbackKind, string>>): void {
    const next = { ...this.settings.get<Partial<Record<FallbackKind, string>>>("fallbackText"), ...texts };
    this.settings.set("fallbackText", next);
  }

  info() {
    const stored = this.stored();
    return (["hold", "sorry"] as const).map((kind) => ({
      kind,
      text: this.text(kind),
      ready: Boolean(stored[kind] && stored[kind]!.text === this.text(kind)),
      seconds: stored[kind] ? Math.round((Buffer.from(stored[kind]!.pcm, "base64").length / 2 / PHONE_RATE) * 10) / 10 : 0,
      at: stored[kind]?.at ?? null,
    }));
  }

  /** Synthesises any phrase that is missing or whose text changed. Safe to call often. */
  refresh(force = false): Promise<void> {
    this.refreshing ??= (async () => {
      const stored = this.stored();
      for (const kind of ["hold", "sorry"] as const) {
        const text = this.text(kind);
        if (!force && stored[kind]?.text === text) continue;
        try {
          const pcm = await this.router.speakAny(text);
          stored[kind] = { text, pcm: pcm.toString("base64"), at: new Date().toISOString() };
          this.settings.set("fallbackAudio", stored);
          console.log(`fallback audio "${kind}" ready (${Math.round(pcm.length / 32000)} s)`);
        } catch (error) {
          console.warn(`fallback audio "${kind}" not generated yet: ${error instanceof Error ? error.message : error}`);
        }
      }
    })().finally(() => (this.refreshing = undefined));
    return this.refreshing;
  }

  pcm(kind: FallbackKind): Buffer {
    const stored = this.stored()[kind];
    return stored ? Buffer.from(stored.pcm, "base64") : tone(kind);
  }

  /**
   * Plays a phrase into the call through the client media socket. Resolves once the phone has had
   * time to play it. A newer client (the next AI) may take the socket over; the audio already sent
   * still plays on the phone.
   */
  play(mediaUrl: string, apiKey: string, kind: FallbackKind): Promise<number> {
    const pcm = this.pcm(kind);
    const ms = (pcm.length / 2 / PHONE_RATE) * 1000;
    return new Promise((resolve) => {
      const ws = new WebSocket(mediaUrl, { headers: { "x-api-key": apiKey } });
      let sent = false;
      const done = () => resolve(sent ? ms : 0);
      const timer = setTimeout(() => {
        ws.close();
        done();
      }, 4_000);
      ws.on("message", (data, isBinary) => {
        if (isBinary || sent) return;
        let type: unknown;
        try {
          type = (JSON.parse(data.toString()) as { type?: unknown }).type;
        } catch {
          return;
        }
        if (type !== "start") return;
        sent = true;
        clearTimeout(timer);
        for (let i = 0; i < pcm.length; i += FRAME_BYTES) ws.send(pcm.subarray(i, i + FRAME_BYTES), { binary: true });
        setTimeout(() => {
          ws.close();
          done();
        }, 300);
      });
      ws.on("error", () => {
        clearTimeout(timer);
        done();
      });
    });
  }

  private stored(): Partial<Record<FallbackKind, Stored>> {
    return this.settings.get<Partial<Record<FallbackKind, Stored>>>("fallbackAudio") ?? {};
  }
}

/** Last resort when no phrase was ever recorded: two soft beeps (hold) or three (sorry). */
function tone(kind: FallbackKind): Buffer {
  const beeps = kind === "hold" ? 2 : 3;
  const beep = 0.18, gap = 0.22;
  const total = Math.round((beeps * (beep + gap) + 0.2) * PHONE_RATE);
  const out = Buffer.alloc(total * 2);
  for (let b = 0; b < beeps; b++) {
    const start = Math.round(b * (beep + gap) * PHONE_RATE);
    const len = Math.round(beep * PHONE_RATE);
    for (let i = 0; i < len; i++) {
      const fade = Math.min(1, i / 160, (len - i) / 160);
      out.writeInt16LE(Math.round(Math.sin((2 * Math.PI * 440 * i) / PHONE_RATE) * 6000 * fade), (start + i) * 2);
    }
  }
  return out;
}
