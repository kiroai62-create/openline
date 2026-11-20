import type { ConfigStore } from "../config.js";
import type { Database, SettingsStore } from "../db.js";
import type { DeviceCommandChannel } from "../device-channel.js";
import type { StateStore } from "../state.js";
import type { CallRecord, DeviceEventName } from "../types.js";
import { GeminiCallAgent, type AgentMessage, type AgentMode, type TranscriptLine } from "./gemini-call-agent.js";
import { clientAiProviders, type ClientStore } from "../clients.js";
import type { AiRouter, ProviderConfig } from "../ai/router.js";
import { PipelineCallAgent } from "../ai/pipeline-agent.js";
import type { FallbackAudio } from "../ai/fallback-audio.js";
import {
  DEFAULT_WHATSAPP_MESSAGE,
  renderTemplate,
  sendWhatsApp,
  toWhatsAppNumber,
  whatsappFromClient,
  whatsappFromConfig,
  type WhatsAppTarget,
} from "./whatsapp.js";

export interface AgentSettings {
  /** Answer incoming calls automatically and hand them to the AI assistant. */
  autoAnswer: boolean;
  ownerName: string;
  /** Free-form notes about the owner that the assistant may use with callers. */
  about: string;
  voice: string;
  /** "message" (personal assistant, default) or "receptionist" (answers for a business). */
  mode: AgentMode;
  /** Receptionist: the business it answers for. */
  businessName: string;
  /** Receptionist: website booking page sent on WhatsApp. */
  bookingUrl: string;
}

export interface AgentManagerOptions {
  state: StateStore;
  deviceChannel: DeviceCommandChannel;
  apiKey: string;
  db: Database;
  settings: SettingsStore;
  /** Gemini key and model (console settings, falling back to server/.env). */
  config: ConfigStore;
  ownerName?: string;
  /** Loopback URL of this server's client media WebSocket for a call. */
  mediaUrl(callId: string): string;
  /** Businesses with their own phones; their calls use the client's receptionist settings. */
  clients?: ClientStore;
  /** Provider list and failover. Without it only the Gemini key in Settings is used. */
  router?: AiRouter;
  /** "Please hold" / "please call later" audio for switches and total failure. */
  fallback?: FallbackAudio;
  /** Central SaaS: may this client's calls be answered now? (license, trial, minute limit). */
  entitled?(clientId: string | undefined): { ok: boolean; message?: string };
  /** Built-in WhatsApp: booking links go out on the business's own linked number when it is connected. */
  whatsapp?: BuiltInWhatsApp;
}

/** The part of the WhatsApp manager the call assistant needs. */
export interface BuiltInWhatsApp {
  isConnected(scope: string): boolean;
  settings(scope: string): { useForBookingLinks: boolean };
  sendText(scope: string, to: string, text: string, meta: { source: "system"; sentBy?: string }): Promise<unknown>;
}

/**
 * What the phone asks for an incoming call (payload field "ai"): "now" (default), "after:<s>"
 * (ring on the phone first, then the AI answers if nobody did), or "never" (a personal call).
 */
export type AiPolicy = { mode: "now" } | { mode: "after"; seconds: number } | { mode: "never" };

export function aiPolicy(payload: Record<string, unknown> | undefined): AiPolicy {
  const raw = typeof payload?.ai === "string" ? payload.ai.trim().toLowerCase() : "";
  if (raw === "never" || raw === "off") return { mode: "never" };
  const after = /^after:(\d{1,3})$/.exec(raw);
  if (after) return { mode: "after", seconds: Math.min(Number(after[1]), 120) };
  return { mode: "now" };
}

type CallAgent = GeminiCallAgent | PipelineCallAgent;
/**
 * Engines tried on one call before giving up. A failing engine hands over to the next one; the
 * call is only ended, after the "please call later" message, when all of these failed.
 */
const MAX_AGENTS_PER_CALL = 8;

/** What the assistant does on one call: the client's receptionist, or the global settings. */
export interface CallProfile {
  mode: AgentMode;
  autoAnswer: boolean;
  ownerName: string;
  businessName: string;
  about: string;
  voice: string;
  bookingUrl: string;
  whatsapp?: WhatsAppTarget;
  clientId?: string;
  /** The client's own AI providers (Gemini, Groq, Cloudflare...), tried before the shared ones. */
  providers?: ProviderConfig[];
  /** Country code and template for booking links on the built-in WhatsApp. */
  whatsappCountryCode: string;
  whatsappTemplate: string;
}

const LIVE_STATES = new Set<CallRecord["state"]>(["queued", "dialing", "ringing", "active", "held"]);

/** Runs the Gemini personal assistant on calls: auto-answer, transcripts and taken messages. */
export class AgentManager {
  readonly settings: AgentSettings;
  private readonly agents = new Map<string, CallAgent>();
  private readonly engines = new Map<string, string>();
  private readonly attempts = new Map<string, number>();
  private readonly heldOnce = new Set<string>();
  private readonly givingUp = new Set<string>();
  private readonly requested = new Set<string>();
  /** Incoming calls the assistant will answer when their ring delay is over. */
  private readonly pendingAnswer = new Map<string, NodeJS.Timeout>();

  constructor(private readonly options: AgentManagerOptions) {
    this.settings = {
      autoAnswer: false,
      ownerName: options.ownerName ?? "the owner",
      about: "",
      voice: "Kore",
      mode: "message",
      businessName: "",
      bookingUrl: "",
      ...options.settings.get<Partial<AgentSettings>>("agent"),
    };
  }

  get configured(): boolean {
    return this.options.router?.anyConfigured() ?? Boolean(this.options.config.get("geminiApiKey"));
  }

  get model(): string {
    return this.options.config.get("geminiModel") ?? "gemini-3.8-live";
  }

  updateSettings(patch: Partial<AgentSettings>): AgentSettings {
    Object.assign(this.settings, patch);
    this.options.settings.set("agent", this.settings);
    return this.settings;
  }

  listMessages(limit = 100): AgentMessage[] {
    const rows = this.options.db
      .prepare("SELECT body FROM messages ORDER BY taken_at DESC LIMIT ?")
      .all(limit) as { body: string }[];
    return rows.map((r) => JSON.parse(r.body) as AgentMessage);
  }

  getMessage(callId: string): AgentMessage | undefined {
    const row = this.options.db.prepare("SELECT body FROM messages WHERE call_id = ?").get(callId) as
      | { body: string }
      | undefined;
    return row ? (JSON.parse(row.body) as AgentMessage) : undefined;
  }

  /** Live transcript while the assistant is on the call, the saved one afterwards. */
  transcript(callId: string): TranscriptLine[] {
    const live = this.agents.get(callId)?.transcript;
    if (live?.length) return live;
    const row = this.options.db.prepare("SELECT lines FROM transcripts WHERE call_id = ?").get(callId) as
      | { lines: string }
      | undefined;
    return row ? (JSON.parse(row.lines) as TranscriptLine[]) : [];
  }

  info(callId: string) {
    const agent = this.agents.get(callId);
    return {
      requested: this.requested.has(callId),
      status: agent?.status ?? (this.requested.has(callId) ? "waiting" : "off"),
      error: agent?.error,
      engine: this.engines.get(callId),
      transcript: this.transcript(callId),
      message: agent?.message ?? this.getMessage(callId),
    };
  }

  /** Hand a call to the assistant; answers it first if it is ringing. */
  takeOver(callId: string): void {
    if (!this.configured) throw new Error("No AI provider is set up (console Settings)");
    const call = this.options.state.getCall(callId);
    if (!call || !LIVE_STATES.has(call.state)) throw new Error("call is not live");
    this.requested.add(callId);
    if (call.state === "ringing" && call.direction === "incoming") this.command(call, "answer");
    if (call.state === "active") this.startAgent(call);
  }

  release(callId: string): void {
    this.requested.delete(callId);
    clearTimeout(this.pendingAnswer.get(callId));
    this.pendingAnswer.delete(callId);
    const agent = this.agents.get(callId);
    agent?.stop();
    if (agent) this.saveTranscript(callId, agent.transcript);
    this.agents.delete(callId);
    this.engines.delete(callId);
    this.attempts.delete(callId);
    this.heldOnce.delete(callId);
    this.givingUp.delete(callId);
  }

  /**
   * Called for every device event after the call record is updated. `streamed` calls belong to an
   * external agent, so the assistant doesn't auto-answer them.
   */
  onCallEvent(call: CallRecord, event: DeviceEventName, streamed = false, payload?: Record<string, unknown>): void {
    const policy = aiPolicy(payload);
    if (
      event === "call.ringing" &&
      !streamed &&
      call.direction === "incoming" &&
      policy.mode !== "never" &&
      this.profileFor(call).autoAnswer &&
      Boolean(this.pickEngine(this.profileFor(call).providers)) &&
      this.checkEntitled(call) &&
      !this.requested.has(call.id) &&
      !this.pendingAnswer.has(call.id)
    ) {
      // "now": let the phone settle for a second. "after": the phone rings for the owner first.
      const delay = policy.mode === "after" ? Math.max(1, policy.seconds) * 1000 : 1_000;
      const timer = setTimeout(() => {
        this.pendingAnswer.delete(call.id);
        const latest = this.options.state.getCall(call.id);
        if (latest?.state !== "ringing") return; // answered on the phone, declined or hung up
        this.requested.add(call.id);
        this.command(latest, "answer");
      }, delay);
      timer.unref?.();
      this.pendingAnswer.set(call.id, timer);
    }
    if (call.state === "active" && this.requested.has(call.id)) this.startAgent(call);
    if (!LIVE_STATES.has(call.state)) this.release(call.id);
  }

  /**
   * Starts the best available engine: a Gemini Live key not on cooldown, else the speech pipeline.
   * When an agent fails mid-call (limit hit, provider down), the next engine takes over with the
   * conversation so far.
   */
  private startAgent(call: CallRecord, history?: TranscriptLine[]): void {
    const current = this.agents.get(call.id);
    // A finished or failed agent may be replaced (e.g. handed back to the assistant again).
    if (current && current.status !== "ended" && current.status !== "error") return;
    if (this.givingUp.has(call.id)) return;
    if (!this.checkEntitled(call)) {
      void this.giveUp(call, this.entitlementMessage(call));
      return;
    }
    const profileNow = this.profileFor(call);
    const engine = this.pickEngine(profileNow.providers);
    if (!engine) {
      console.error(`no AI provider available for call ${call.id}`);
      void this.giveUp(call);
      return;
    }
    const attempts = (this.attempts.get(call.id) ?? 0) + 1;
    this.attempts.set(call.id, attempts);
    const profile = this.profileFor(call);
    const whatsapp = profile.whatsapp;
    const receptionist = profile.mode === "receptionist";
    const common = {
      callId: call.id,
      remoteNumber: call.remoteNumber,
      mediaUrl: this.options.mediaUrl(call.id),
      apiKey: this.options.apiKey,
      voice: profile.voice,
      ownerName: profile.ownerName,
      about: profile.about,
      mode: profile.mode,
      businessName: profile.businessName,
      history,
      sendBookingLink: receptionist && (whatsapp || this.builtInScope(profile)) ? (args: { phoneNumber?: string; patientName?: string }) => this.sendBookingLink(call, args) : undefined,
      onMessage: (message: AgentMessage) => this.saveMessage(message),
      onHangup: () => this.command(call, "hangup"),
      onEnded: () => this.onAgentEnded(call, agent, engine.live),
    };
    const agent: CallAgent = engine.live
      ? new GeminiCallAgent({ ...common, geminiApiKey: engine.live.apiKey, model: engine.live.model })
      : new PipelineCallAgent({ ...common, geminiApiKey: "", model: "", router: this.options.router!, providers: profileNow.providers ?? [] });
    this.agents.set(call.id, agent);
    this.engines.set(call.id, engine.live ? `Gemini Live · ${engine.live.label}` : "Speech pipeline");
    console.log(`call ${call.id}: AI engine ${this.engines.get(call.id)} (attempt ${attempts})`);
    void agent.start();
  }

  /**
   * The customer's own providers come first (each cools down on its own), then the platform's:
   * a Gemini Live key not on cooldown, else the speech pipeline.
   */
  private pickEngine(extra: ProviderConfig[] = []): { live?: ProviderConfig } | undefined {
    const router = this.options.router;
    if (!router) {
      const own = extra.find((p) => p.kind === "live" && p.enabled && p.apiKey);
      if (own) return { live: own };
      const key = this.options.config.get("geminiApiKey");
      return key ? { live: { id: "settings-gemini", kind: "live", type: "gemini", label: "Gemini", enabled: true, apiKey: key, model: this.model } } : undefined;
    }
    const mode = router.routing().mode;
    if (mode !== "pipeline") {
      const live = router.available("live", extra)[0];
      if (live) return { live };
    }
    if (mode !== "live" && router.pipelineAvailable(extra)) return {};
    // Everything is on cooldown: the caller hears "please call later" instead of waiting in silence.
    return undefined;
  }

  private onAgentEnded(call: CallRecord, agent: CallAgent, live?: ProviderConfig): void {
    this.saveTranscript(call.id, agent.transcript);
    if (agent.status !== "error" || this.agents.get(call.id) !== agent) return;
    if (live) this.options.router?.fail(live, new Error(agent.error ?? "Gemini Live failed"));
    const latest = this.options.state.getCall(call.id);
    if (!latest || latest.state !== "active" || !this.requested.has(call.id)) return;
    if (agent.message) this.saveMessage(agent.message);
    if ((this.attempts.get(call.id) ?? 0) >= MAX_AGENTS_PER_CALL) {
      console.error(`call ${call.id}: every AI engine failed`);
      void this.giveUp(latest);
      return;
    }
    console.warn(`call ${call.id}: ${this.engines.get(call.id)} failed (${agent.error}); switching`);
    void this.switchEngine(latest, agent.transcript);
  }

  /** Says "one moment" (once per call, only if the caller has already been talking) and starts the next engine. */
  private async switchEngine(call: CallRecord, transcript: TranscriptLine[]): Promise<void> {
    const fallback = this.options.fallback;
    if (fallback && transcript.length && !this.heldOnce.has(call.id)) {
      this.heldOnce.add(call.id);
      await fallback.play(this.options.mediaUrl(call.id), this.options.apiKey, "hold");
    }
    const latest = this.options.state.getCall(call.id);
    if (latest?.state === "active" && this.requested.has(call.id)) this.startAgent(latest, transcript);
  }

  /** Nothing can answer: tell the caller to call later, then hang up, instead of a silent line. */
  private checkEntitled(call: CallRecord): boolean {
    const gate = this.options.entitled;
    if (!gate) return true;
    const profile = this.profileFor(call);
    return gate(profile.clientId).ok;
  }

  private entitlementMessage(call: CallRecord): "sorry" {
    // The caller always hears the recorded "please call later"; the reason is for the console.
    const gate = this.options.entitled;
    if (gate) {
      const r = gate(this.profileFor(call).clientId);
      if (!r.ok && r.message) console.warn(`call ${call.id} not answered: ${r.message}`);
    }
    return "sorry";
  }

  private async giveUp(call: CallRecord, kind: "sorry" = "sorry"): Promise<void> {
    if (this.givingUp.has(call.id)) return;
    this.givingUp.add(call.id);
    this.agents.get(call.id)?.stop();
    const played = this.options.fallback
      ? await this.options.fallback.play(this.options.mediaUrl(call.id), this.options.apiKey, kind)
      : 0;
    setTimeout(() => {
      const latest = this.options.state.getCall(call.id);
      if (latest && LIVE_STATES.has(latest.state)) this.command(latest, "hangup");
    }, played + 800);
  }

  /** Sends the booking link to the caller (or the number they gave) and tells the model the outcome. */
  /** The business's built-in WhatsApp scope, when it is linked and used for booking links. */
  private builtInScope(profile: CallProfile): string | undefined {
    const wa = this.options.whatsapp;
    const scope = profile.clientId ?? "default";
    return wa?.isConnected(scope) && wa.settings(scope).useForBookingLinks ? scope : undefined;
  }

  private async sendBookingLink(call: CallRecord, args: { phoneNumber?: string; patientName?: string }): Promise<string> {
    const profile = this.profileFor(call);
    const whatsapp = profile.whatsapp;
    const builtIn = this.builtInScope(profile);
    if (!whatsapp && !builtIn) return "WhatsApp is not set up. Take a message instead.";
    const number = toWhatsAppNumber(args.phoneNumber ?? call.remoteNumber, whatsapp?.countryCode ?? profile.whatsappCountryCode);
    if (!number) {
      return args.phoneNumber
        ? "That number doesn't look valid. Ask the caller to say it again, digit by digit."
        : "The caller's number is hidden. Ask for their WhatsApp number, then call send_booking_link with phone_number.";
    }
    const text = renderTemplate(whatsapp?.message ?? profile.whatsappTemplate, {
      name: args.patientName ?? "",
      business: profile.businessName || profile.ownerName,
      link: profile.bookingUrl,
    });
    try {
      if (builtIn) await this.options.whatsapp!.sendText(builtIn, number, text, { source: "system", sentBy: "call" });
      else await sendWhatsApp(whatsapp!, number, text);
    } catch (error) {
      this.logSend(call, profile, number, error instanceof Error ? error.message : String(error));
      throw error;
    }
    this.logSend(call, profile, number);
    console.log(`booking link sent on WhatsApp for call ${call.id} to …${number.slice(-4)}`);
    return `Sent on WhatsApp to the number ending in ${number.slice(-4)}.`;
  }

  private logSend(call: CallRecord, profile: CallProfile, number: string, error?: string): void {
    this.options.db
      .prepare("INSERT INTO whatsapp_sends (call_id, client_id, to_suffix, ok, error, at) VALUES (?, ?, ?, ?, ?, ?)")
      .run(call.id, profile.clientId ?? null, number.slice(-4), error ? 0 : 1, error?.slice(0, 300) ?? null, new Date().toISOString());
  }

  /** The phone's client wins; phones without a client use the global assistant settings. */
  profileFor(call: Pick<CallRecord, "deviceId">): CallProfile {
    const client = this.options.clients?.forDevice(call.deviceId);
    if (client) {
      return {
        mode: "receptionist",
        autoAnswer: client.enabled,
        ownerName: client.name,
        businessName: client.name,
        about: client.about,
        voice: client.voice,
        bookingUrl: client.bookingUrl,
        whatsapp: whatsappFromClient(client),
        clientId: client.id,
        providers: clientAiProviders(client),
        whatsappCountryCode: client.whatsappCountryCode || "91",
        whatsappTemplate: client.whatsappMessage?.trim() || DEFAULT_WHATSAPP_MESSAGE,
      };
    }
    const s = this.settings;
    return {
      mode: s.mode,
      autoAnswer: s.autoAnswer,
      ownerName: s.ownerName,
      businessName: s.businessName,
      about: s.about,
      voice: s.voice,
      bookingUrl: s.bookingUrl,
      whatsapp: whatsappFromConfig(this.options.config),
      whatsappCountryCode: this.options.config.get("whatsappCountryCode") || "91",
      whatsappTemplate: this.options.config.get("whatsappMessage")?.trim() || DEFAULT_WHATSAPP_MESSAGE,
    };
  }

  private saveMessage(message: AgentMessage): void {
    console.log(`message for ${message.business ?? this.settings.ownerName}:`, JSON.stringify(message));
    this.options.db
      .prepare("INSERT OR REPLACE INTO messages (call_id, body, taken_at) VALUES (?, ?, ?)")
      .run(message.callId, JSON.stringify(message), message.takenAt);
  }

  private saveTranscript(callId: string, lines: TranscriptLine[]): void {
    if (!lines.length) return;
    this.options.db
      .prepare("INSERT OR REPLACE INTO transcripts (call_id, lines, updated_at) VALUES (?, ?, ?)")
      .run(callId, JSON.stringify(lines), new Date().toISOString());
  }

  private command(call: CallRecord, action: "answer" | "hangup"): void {
    try {
      this.options.deviceChannel.send(call.deviceId, { action, callId: call.id });
    } catch (error) {
      console.error(`agent could not ${action} ${call.id}:`, error instanceof Error ? error.message : error);
    }
  }
}
