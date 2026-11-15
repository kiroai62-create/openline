import type { ConfigStore } from "../config.js";

/**
 * Sends WhatsApp messages through an external WhatsApp gateway (e.g. a Baileys server) that
 * exposes `POST /send` with `X-API-KEY` and a JSON body `{ jid, text }`; `jid` may be plain
 * digits with the country code. Used by the receptionist to send the booking link after a call.
 */
export class WhatsAppSender {
  constructor(
    private readonly config: ConfigStore,
    private readonly timeoutMs = 20_000,
  ) {}

  get configured(): boolean {
    return Boolean(this.config.get("whatsappGatewayUrl") && this.config.get("whatsappApiSecret"));
  }

  /** International number without "+", or undefined when the caller's number can't be used. */
  normalize(raw: string | undefined): string | undefined {
    return toWhatsAppNumber(raw, this.config.get("whatsappCountryCode") ?? "91");
  }

  async send(number: string, text: string): Promise<void> {
    const url = this.config.get("whatsappGatewayUrl")?.replace(/\/+$/, "");
    const secret = this.config.get("whatsappApiSecret");
    if (!url || !secret) throw new Error("WhatsApp gateway is not set up");
    const response = await fetch(`${url}/send`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-api-key": secret },
      body: JSON.stringify({ jid: number, text }),
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    if (!response.ok) {
      const body = (await response.json().catch(() => ({}))) as { error?: string };
      throw new Error(body.error ?? `WhatsApp gateway returned HTTP ${response.status}`);
    }
  }
}

/**
 * "+91 98765-43210", "098765 43210" and "9876543210" all become "919876543210" (for country code
 * 91). Numbers that already carry a country code are kept. Returns undefined for hidden or
 * implausible numbers.
 */
export function toWhatsAppNumber(raw: string | undefined, countryCode = "91"): string | undefined {
  if (!raw) return undefined;
  const plus = raw.trim().startsWith("+");
  let digits = raw.replace(/\D/g, "");
  if (digits.startsWith("00")) return valid(digits.slice(2));
  if (plus) return valid(digits);
  digits = digits.replace(/^0+/, "");
  if (digits.length === 10) return valid(countryCode + digits);
  return valid(digits);
}

function valid(digits: string): string | undefined {
  return digits.length >= 10 && digits.length <= 15 ? digits : undefined;
}

/**
 * Replaces {name}, {business} and {link} in the message template. A line whose only placeholder
 * values are empty is dropped (no booking link set -> no "open this link:" line); an empty {name}
 * elsewhere just disappears.
 */
export function renderTemplate(template: string, values: Record<string, string>): string {
  return template
    .split("\n")
    .filter((line) => {
      const keys = [...line.matchAll(/\{(\w+)\}/g)].map((m) => m[1]!).filter((k) => k in values && k !== "name");
      return !keys.length || keys.some((k) => values[k]);
    })
    .join("\n")
    .replace(/\{(\w+)\}/g, (match, key: string) => values[key] ?? match)
    .replace(/ +([!,.])/g, "$1")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

export const DEFAULT_WHATSAPP_MESSAGE =
  "Namaste {name}! {business} ko call karne ke liye dhanyavaad.\n\n" +
  "Appointment book karne ke liye ye link kholein: {link}\n\n" +
  "Ya isi WhatsApp par reply karke apna naam aur aane ka din bata dijiye, hum appointment confirm kar denge.";
