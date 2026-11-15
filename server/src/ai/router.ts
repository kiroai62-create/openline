import type { SettingsStore } from "../db.js";
import type { ConfigStore } from "../config.js";
import { pcmToWav, toPhonePcm } from "./audio.js";

/**
 * AI providers and automatic failover.
 *
 * Calls are answered by Gemini Live (speech-to-speech) when it is available. Several Gemini keys
 * can be listed; when one hits its limit the next is used. When no Live key works, the call runs on
 * a "speech pipeline": speech-to-text -> chat model -> text-to-speech, each with its own ordered
 * list of providers (Groq, Cloudflare Workers AI, OpenRouter, Cerebras, Mistral, Sarvam, Gemini...).
 *
 * A provider that fails with a rate limit, quota, auth or server error is put on cooldown and the
 * next one in the list is tried, for every request. Cooldowns are kept in memory.
 */

export type ProviderKind = "live" | "llm" | "stt" | "tts";

export interface ProviderConfig {
  id: string;
  kind: ProviderKind;
  /** Preset: gemini, groq, cloudflare, openrouter, cerebras, mistral, sarvam, openai (any compatible). */
  type: string;
  label: string;
  enabled: boolean;
  apiKey: string;
  model: string;
  /** OpenAI-compatible base URL (llm/stt/tts of type openai and friends). */
  baseUrl?: string;
  /** Cloudflare account id. */
  accountId?: string;
  /** TTS voice / speaker. */
  voice?: string;
}

export interface RoutingSettings {
  /** "auto": Gemini Live first, the speech pipeline when Live is unavailable. */
  mode: "auto" | "live" | "pipeline";
  providers: ProviderConfig[];
}

export interface ChatMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string | null;
  tool_calls?: { id: string; type: "function"; function: { name: string; arguments: string } }[];
  tool_call_id?: string;
  name?: string;
}

export interface ChatTool {
  type: "function";
  function: { name: string; description: string; parameters: Record<string, unknown> };
}

export interface ProviderStatus {
  id: string;
  kind: ProviderKind;
  label: string;
  state: "ready" | "cooldown" | "off";
  cooldownUntil?: string;
  lastError?: string;
  lastErrorAt?: string;
  ok: number;
  failed: number;
  lastUsedAt?: string;
}

/** Presets shown in the console. Model names change often: they stay editable. */
export const PRESETS: Record<string, { kinds: ProviderKind[]; baseUrl?: string; models: Partial<Record<ProviderKind, string>>; voice?: string; signup: string }> = {
  gemini: {
    kinds: ["live", "llm", "tts"],
    baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai",
    models: { live: "gemini-3.8-live", llm: "gemini-3.8-flash", tts: "gemini-3.8-flash-lite-tts" },
    voice: "Kore",
    signup: "https://aistudio.google.com/apikey",
  },
  groq: {
    kinds: ["llm", "stt", "tts"],
    baseUrl: "https://api.groq.com/openai/v1",
    models: { llm: "llama-3.3-70b-versatile", stt: "whisper-large-v3-turbo", tts: "canopylabs/orpheus-v1-english" },
    voice: "autumn",
    signup: "https://console.groq.com/keys",
  },
  cloudflare: {
    kinds: ["llm", "stt"],
    models: { llm: "@cf/meta/llama-3.3-70b-instruct-fp8-fast", stt: "@cf/openai/whisper-large-v3-turbo" },
    signup: "https://dash.cloudflare.com/profile/api-tokens",
  },
  openrouter: {
    kinds: ["llm"],
    baseUrl: "https://openrouter.ai/api/v1",
    models: { llm: "meta-llama/llama-3.3-70b-instruct:free" },
    signup: "https://openrouter.ai/keys",
  },
  cerebras: { kinds: ["llm"], baseUrl: "https://api.cerebras.ai/v1", models: { llm: "llama-3.3-70b" }, signup: "https://cloud.cerebras.ai" },
  mistral: { kinds: ["llm", "stt"], baseUrl: "https://api.mistral.ai/v1", models: { llm: "mistral-small-latest", stt: "voxtral-mini-latest" }, signup: "https://console.mistral.ai/api-keys" },
  sarvam: {
    kinds: ["stt", "tts"],
    models: { stt: "saaras:v4", tts: "bulbul:v3" },
    voice: "priya",
    signup: "https://dashboard.sarvam.ai",
  },
  openai: { kinds: ["llm", "stt", "tts"], baseUrl: "https://api.openai.com/v1", models: { llm: "gpt-4.1-mini", stt: "whisper-1", tts: "gpt-4o-mini-tts" }, voice: "alloy", signup: "https://platform.openai.com/api-keys" },
};

const REQUEST_TIMEOUT_MS = 15_000;

class ProviderError extends Error {
  constructor(message: string, readonly status?: number, readonly retryAfterMs?: number) {
    super(message);
  }
}

export class AiRouter {
  private readonly cooldown = new Map<string, number>();
  private readonly stats = new Map<string, { ok: number; failed: number; lastError?: string; lastErrorAt?: string; lastUsedAt?: string }>();

  constructor(
    private readonly settings: SettingsStore,
    private readonly config: ConfigStore,
  ) {}

  routing(): RoutingSettings {
    const saved = this.settings.get<Partial<RoutingSettings>>("aiRouting") ?? {};
    return { mode: saved.mode ?? "auto", providers: saved.providers ?? [] };
  }

  save(next: RoutingSettings): void {
    this.settings.set("aiRouting", next);
  }

  /** Enabled providers of a kind, in order. The Gemini key in Settings is always the first Live key. */
  list(kind: ProviderKind): ProviderConfig[] {
    const own = this.routing().providers.filter((p) => p.kind === kind && p.enabled && p.apiKey);
    if (kind !== "live") return own;
    const primary = this.config.get("geminiApiKey");
    const base: ProviderConfig[] = primary
      ? [{ id: "settings-gemini", kind: "live", type: "gemini", label: "Gemini (Settings)", enabled: true, apiKey: primary, model: this.config.get("geminiModel") ?? PRESETS.gemini!.models.live! }]
      : [];
    return [...base, ...own];
  }

  available(kind: ProviderKind): ProviderConfig[] {
    const now = Date.now();
    return this.list(kind).filter((p) => (this.cooldown.get(p.id) ?? 0) <= now);
  }

  /** The speech pipeline needs at least one provider of each kind. */
  pipelineReady(): boolean {
    return (["llm", "stt", "tts"] as const).every((k) => this.list(k).length > 0);
  }

  anyConfigured(): boolean {
    return this.list("live").length > 0 || this.pipelineReady();
  }

  /** Records a failure and decides how long the provider sits out. */
  fail(provider: Pick<ProviderConfig, "id">, error: unknown): void {
    const message = error instanceof Error ? error.message : String(error);
    const status = error instanceof ProviderError ? error.status : undefined;
    const retryAfter = error instanceof ProviderError ? error.retryAfterMs : undefined;
    const lower = message.toLowerCase();
    let ms = 20_000;
    if (status === 401 || status === 403 || /api key|unauthori[sz]ed|permission/.test(lower)) ms = 10 * 60_000;
    else if (status === 429 || /quota|rate.?limit|resource.?exhausted|too many/.test(lower)) {
      ms = retryAfter ?? (/day|daily|per.?day/.test(lower) ? 60 * 60_000 : 90_000);
    }
    this.cooldown.set(provider.id, Date.now() + ms);
    const s = this.stat(provider.id);
    s.failed++;
    s.lastError = message.slice(0, 300);
    s.lastErrorAt = new Date().toISOString();
    console.warn(`AI provider ${provider.id} failed (${message.slice(0, 120)}); cooling down ${Math.round(ms / 1000)} s`);
  }

  succeed(provider: Pick<ProviderConfig, "id">): void {
    const s = this.stat(provider.id);
    s.ok++;
    s.lastUsedAt = new Date().toISOString();
  }

  status(): ProviderStatus[] {
    const now = Date.now();
    const all = [...this.list("live"), ...(["llm", "stt", "tts"] as const).flatMap((k) => this.list(k))];
    const disabled = this.routing().providers.filter((p) => !p.enabled || !p.apiKey);
    return [...all, ...disabled].map((p) => {
      const until = this.cooldown.get(p.id) ?? 0;
      const s = this.stats.get(p.id);
      return {
        id: p.id,
        kind: p.kind,
        label: p.label,
        state: !p.enabled || !p.apiKey ? "off" : until > now ? "cooldown" : "ready",
        ...(until > now ? { cooldownUntil: new Date(until).toISOString() } : {}),
        ok: s?.ok ?? 0,
        failed: s?.failed ?? 0,
        lastError: s?.lastError,
        lastErrorAt: s?.lastErrorAt,
        lastUsedAt: s?.lastUsedAt,
      };
    });
  }

  resetCooldowns(): void {
    this.cooldown.clear();
  }

  // ---- requests with failover ----

  async chat(messages: ChatMessage[], tools: ChatTool[]): Promise<{ message: ChatMessage; provider: string }> {
    return this.each("llm", async (p) => {
      const body: Record<string, unknown> = { model: p.model, messages, temperature: 0.4, max_tokens: 300 };
      if (tools.length) Object.assign(body, { tools, tool_choice: "auto" });
      const json = (await this.json(`${this.openAiBase(p)}/chat/completions`, p, body)) as {
        choices?: { message?: ChatMessage }[];
      };
      const message = json.choices?.[0]?.message;
      if (!message) throw new ProviderError("empty reply");
      return { message: { role: "assistant", content: message.content ?? null, tool_calls: message.tool_calls }, provider: p.label };
    });
  }

  /** 16 kHz PCM -> text. */
  async transcribe(pcm: Buffer): Promise<string> {
    const wav = pcmToWav(pcm);
    return this.each("stt", async (p) => {
      if (p.type === "cloudflare") {
        const json = (await this.json(
          `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(p.accountId ?? "")}/ai/run/${p.model}`,
          p,
          { audio: wav.toString("base64") },
        )) as { result?: { text?: string } };
        return json.result?.text?.trim() ?? "";
      }
      const form = new FormData();
      form.append("file", new Blob([new Uint8Array(wav)], { type: "audio/wav" }), "speech.wav");
      form.append("model", p.model);
      if (p.type === "sarvam") {
        form.append("mode", "transcribe");
        const json = (await this.request("https://api.sarvam.ai/speech-to-text", p, form)) as { transcript?: string };
        return json.transcript?.trim() ?? "";
      }
      form.append("response_format", "json");
      const json = (await this.request(`${this.openAiBase(p)}/audio/transcriptions`, p, form)) as { text?: string };
      return json.text?.trim() ?? "";
    });
  }

  /** Text -> 16 kHz PCM. */
  async speak(text: string): Promise<Buffer> {
    const devanagari = /[\u0900-\u097F]/.test(text);
    return this.each("tts", async (p) => {
      if (p.type === "sarvam") {
        const json = (await this.json("https://api.sarvam.ai/text-to-speech", p, {
          text: text.slice(0, 2400),
          language_code: devanagari ? "hi-IN" : "en-IN",
          model: p.model,
          ...(p.voice ? { speaker: p.voice } : {}),
          speech_sample_rate: 16000,
        })) as { audios?: string[] };
        if (!json.audios?.length) throw new ProviderError("no audio");
        return toPhonePcm(Buffer.concat(json.audios.map((a) => Buffer.from(a, "base64"))));
      }
      if (p.type === "gemini") {
        const json = (await this.json(
          `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(p.model)}:generateContent`,
          p,
          {
            contents: [{ parts: [{ text }] }],
            generationConfig: {
              responseModalities: ["AUDIO"],
              speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: p.voice || "Kore" } } },
            },
          },
        )) as { candidates?: { content?: { parts?: { inlineData?: { data?: string; mimeType?: string } }[] } }[] };
        const part = json.candidates?.[0]?.content?.parts?.find((x) => x.inlineData?.data)?.inlineData;
        if (!part?.data) throw new ProviderError("no audio");
        const rate = Number(/rate=(\d+)/.exec(part.mimeType ?? "")?.[1] ?? 24000);
        return toPhonePcm(Buffer.from(part.data, "base64"), rate);
      }
      const audio = await this.binary(`${this.openAiBase(p)}/audio/speech`, p, {
        model: p.model,
        input: text,
        voice: p.voice || "alloy",
        response_format: "wav",
      });
      return toPhonePcm(audio);
    });
  }

  // ---- internals ----

  private async each<T>(kind: Exclude<ProviderKind, "live">, run: (p: ProviderConfig) => Promise<T>): Promise<T> {
    const candidates = this.available(kind);
    // Everything on cooldown: try the configured ones anyway rather than leave the caller in silence.
    const order = candidates.length ? candidates : this.list(kind);
    if (!order.length) throw new Error(`no ${kind} provider configured`);
    let last: unknown;
    for (const p of order) {
      try {
        const result = await run(p);
        this.succeed(p);
        return result;
      } catch (error) {
        last = error;
        this.fail(p, error);
      }
    }
    throw last instanceof Error ? last : new Error(String(last));
  }

  private openAiBase(p: ProviderConfig): string {
    if (p.type === "cloudflare") return `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(p.accountId ?? "")}/ai/v1`;
    return (p.baseUrl || PRESETS[p.type]?.baseUrl || "").replace(/\/+$/, "");
  }

  private headers(p: ProviderConfig): Record<string, string> {
    if (p.type === "sarvam") return { "api-subscription-key": p.apiKey };
    if (p.type === "gemini" && p.kind === "tts") return { "x-goog-api-key": p.apiKey };
    return { authorization: `Bearer ${p.apiKey}` };
  }

  private json(url: string, p: ProviderConfig, body: unknown): Promise<unknown> {
    return this.request(url, p, JSON.stringify(body), { "content-type": "application/json" });
  }

  private async request(url: string, p: ProviderConfig, body: string | FormData, extra: Record<string, string> = {}): Promise<unknown> {
    const response = await this.fetch(url, p, body, extra);
    return response.json();
  }

  private async binary(url: string, p: ProviderConfig, body: unknown): Promise<Buffer> {
    const response = await this.fetch(url, p, JSON.stringify(body), { "content-type": "application/json" });
    return Buffer.from(await response.arrayBuffer());
  }

  private async fetch(url: string, p: ProviderConfig, body: string | FormData, extra: Record<string, string>): Promise<Response> {
    let response: Response;
    try {
      response = await fetch(url, {
        method: "POST",
        headers: { ...this.headers(p), ...extra },
        body,
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
    } catch (error) {
      throw new ProviderError(`${p.label}: ${error instanceof Error ? error.message : String(error)}`);
    }
    if (!response.ok) {
      const text = await response.text().catch(() => "");
      const retry = Number(response.headers.get("retry-after"));
      throw new ProviderError(
        `${p.label}: HTTP ${response.status} ${text.slice(0, 200)}`,
        response.status,
        Number.isFinite(retry) && retry > 0 ? Math.min(retry * 1000, 60 * 60_000) : undefined,
      );
    }
    return response;
  }

  private stat(id: string) {
    let s = this.stats.get(id);
    if (!s) this.stats.set(id, (s = { ok: 0, failed: 0 }));
    return s;
  }
}
