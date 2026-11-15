import type { SettingsStore } from "./db.js";

/** Settings editable from the console. Values saved there win over server/.env defaults. */
export interface GatewayConfig {
  geminiApiKey?: string;
  geminiModel?: string;
  publicUrl?: string;
  webhookUrl?: string;
  docsUrl?: string;
  /** Every incoming call is answered and streamed to this agent WebSocket. */
  incomingStreamUrl?: string;
  incomingStreamFormat?: string;
  /** WhatsApp gateway (POST /send with X-API-KEY) used to send the booking link after a call. */
  whatsappGatewayUrl?: string;
  whatsappApiSecret?: string;
  /** Added to 10-digit local numbers before sending, e.g. "91". */
  whatsappCountryCode?: string;
  /** Message template with {name}, {business} and {link}. */
  whatsappMessage?: string;
  /** Product name shown in the console header and title (white label). */
  brandName?: string;
}

const KEYS = [
  "geminiApiKey",
  "geminiModel",
  "publicUrl",
  "webhookUrl",
  "docsUrl",
  "incomingStreamUrl",
  "incomingStreamFormat",
  "whatsappGatewayUrl",
  "whatsappApiSecret",
  "whatsappCountryCode",
  "whatsappMessage",
  "brandName",
] as const;
export type ConfigKey = (typeof KEYS)[number];

export class ConfigStore {
  constructor(
    private readonly settings: SettingsStore,
    private readonly defaults: GatewayConfig = {},
  ) {}

  get(key: ConfigKey): string | undefined {
    return this.saved()[key] || this.defaults[key] || undefined;
  }

  /** Empty string clears a saved value (falls back to the .env default). */
  update(patch: Partial<Record<ConfigKey, string>>): void {
    const next = { ...this.saved() };
    for (const key of KEYS) {
      const value = patch[key];
      if (value === undefined) continue;
      if (value.trim()) next[key] = value.trim();
      else delete next[key];
    }
    this.settings.set("config", next);
  }

  /** Safe to send to the browser: secrets are reduced to whether they are set and their last digits. */
  view() {
    const saved = this.saved();
    const gemini = this.get("geminiApiKey");
    const waSecret = this.get("whatsappApiSecret");
    return {
      publicUrl: this.get("publicUrl") ?? null,
      webhookUrl: this.get("webhookUrl") ?? null,
      docsUrl: this.get("docsUrl") ?? null,
      incomingStreamUrl: this.get("incomingStreamUrl") ?? null,
      incomingStreamFormat: this.get("incomingStreamFormat") ?? "raw",
      geminiModel: this.get("geminiModel") ?? null,
      geminiApiKey: {
        configured: Boolean(gemini),
        hint: gemini ? `…${gemini.slice(-4)}` : null,
        source: saved.geminiApiKey ? "console" : gemini ? "env" : null,
      },
      brandName: this.get("brandName") ?? null,
      whatsappGatewayUrl: this.get("whatsappGatewayUrl") ?? null,
      whatsappCountryCode: this.get("whatsappCountryCode") ?? "91",
      whatsappMessage: this.get("whatsappMessage") ?? null,
      whatsappApiSecret: {
        configured: Boolean(waSecret),
        hint: waSecret ? `…${waSecret.slice(-4)}` : null,
        source: saved.whatsappApiSecret ? "console" : waSecret ? "env" : null,
      },
    };
  }

  private saved(): GatewayConfig {
    return this.settings.get<GatewayConfig>("config") ?? {};
  }
}
