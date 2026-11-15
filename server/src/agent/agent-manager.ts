import type { ConfigStore } from "../config.js";
import type { Database, SettingsStore } from "../db.js";
import type { DeviceCommandChannel } from "../device-channel.js";
import type { StateStore } from "../state.js";
import type { CallRecord, DeviceEventName } from "../types.js";
import { GeminiCallAgent, type AgentMessage, type AgentMode, type TranscriptLine } from "./gemini-call-agent.js";
import { DEFAULT_WHATSAPP_MESSAGE, renderTemplate, type WhatsAppSender } from "./whatsapp.js";

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
  /** Sends the receptionist's booking link on WhatsApp. */
  whatsapp?: WhatsAppSender;
}

const LIVE_STATES = new Set<CallRecord["state"]>(["queued", "dialing", "ringing", "active", "held"]);

/** Runs the Gemini personal assistant on calls: auto-answer, transcripts and taken messages. */
export class AgentManager {
  readonly settings: AgentSettings;
  private readonly agents = new Map<string, GeminiCallAgent>();
  private readonly requested = new Set<string>();

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
    return Boolean(this.options.config.get("geminiApiKey"));
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
      transcript: this.transcript(callId),
      message: agent?.message ?? this.getMessage(callId),
    };
  }

  /** Hand a call to the assistant; answers it first if it is ringing. */
  takeOver(callId: string): void {
    if (!this.configured) throw new Error("Gemini API key is not set (console Settings)");
    const call = this.options.state.getCall(callId);
    if (!call || !LIVE_STATES.has(call.state)) throw new Error("call is not live");
    this.requested.add(callId);
    if (call.state === "ringing" && call.direction === "incoming") this.command(call, "answer");
    if (call.state === "active") this.startAgent(call);
  }

  release(callId: string): void {
    this.requested.delete(callId);
    const agent = this.agents.get(callId);
    agent?.stop();
    if (agent) this.saveTranscript(callId, agent.transcript);
    this.agents.delete(callId);
  }

  /**
   * Called for every device event after the call record is updated. `streamed` calls belong to an
   * external agent, so the assistant doesn't auto-answer them.
   */
  onCallEvent(call: CallRecord, event: DeviceEventName, streamed = false): void {
    if (
      event === "call.ringing" &&
      !streamed &&
      call.direction === "incoming" &&
      this.settings.autoAnswer &&
      this.configured &&
      !this.requested.has(call.id)
    ) {
      this.requested.add(call.id);
      setTimeout(() => this.command(call, "answer"), 1_000); // let the phone settle before answering
    }
    if (call.state === "active" && this.requested.has(call.id)) this.startAgent(call);
    if (!LIVE_STATES.has(call.state)) this.release(call.id);
  }

  private startAgent(call: CallRecord): void {
    const geminiApiKey = this.options.config.get("geminiApiKey");
    const current = this.agents.get(call.id);
    // A finished or failed agent may be replaced (e.g. handed back to the assistant again).
    if ((current && current.status !== "ended" && current.status !== "error") || !geminiApiKey) return;
    const whatsapp = this.options.whatsapp;
    const receptionist = this.settings.mode === "receptionist";
    const agent = new GeminiCallAgent({
      callId: call.id,
      remoteNumber: call.remoteNumber,
      mediaUrl: this.options.mediaUrl(call.id),
      apiKey: this.options.apiKey,
      geminiApiKey,
      model: this.model,
      voice: this.settings.voice,
      ownerName: this.settings.ownerName,
      about: this.settings.about,
      mode: this.settings.mode,
      businessName: this.settings.businessName,
      sendBookingLink:
        receptionist && whatsapp?.configured
          ? (args) => this.sendBookingLink(call, args)
          : undefined,
      onMessage: (message) => this.saveMessage(message),
      onHangup: () => this.command(call, "hangup"),
      onEnded: () => this.saveTranscript(call.id, agent.transcript),
    });
    this.agents.set(call.id, agent);
    void agent.start();
  }

  /** Sends the booking link to the caller (or the number they gave) and tells the model the outcome. */
  private async sendBookingLink(call: CallRecord, args: { phoneNumber?: string; patientName?: string }): Promise<string> {
    const whatsapp = this.options.whatsapp!;
    const number = whatsapp.normalize(args.phoneNumber ?? call.remoteNumber);
    if (!number) {
      return args.phoneNumber
        ? "That number doesn't look valid. Ask the caller to say it again, digit by digit."
        : "The caller's number is hidden. Ask for their WhatsApp number, then call send_booking_link with phone_number.";
    }
    const text = renderTemplate(this.options.config.get("whatsappMessage") ?? DEFAULT_WHATSAPP_MESSAGE, {
      name: args.patientName ?? "",
      business: this.settings.businessName || this.settings.ownerName,
      link: this.settings.bookingUrl,
    });
    await whatsapp.send(number, text);
    console.log(`booking link sent on WhatsApp for call ${call.id} to …${number.slice(-4)}`);
    return `Sent on WhatsApp to the number ending in ${number.slice(-4)}.`;
  }

  private saveMessage(message: AgentMessage): void {
    console.log(`message for ${this.settings.ownerName}:`, JSON.stringify(message));
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
