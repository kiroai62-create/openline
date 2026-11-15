import type { ClientProfile } from "../clients.js";
import type { ConfigStore } from "../config.js";

/**
 * WhatsApp messages go through an external WhatsApp gateway (e.g. a Baileys server) that exposes
 * `POST /send` with `X-API-KEY` and a JSON body `{ jid, text }`; `jid` may be plain digits with
 * the country code. The receptionist uses it to send the booking link after a call. Each client
 * has its own gateway (its own WhatsApp number); phones without a client use the one in Settings.
 */
export interface WhatsAppTarget {
  url: string;
  secret: string;
  /** Added to 10-digit local numbers, e.g. "91". */
  countryCode: string;
  /** Template with {name}, {business}, {link}. */
  message: string;
}

export function whatsappFromConfig(config: ConfigStore): WhatsAppTarget | undefined {
  return target(
    config.get("whatsappGatewayUrl"),
    config.get("whatsappApiSecret"),
    config.get("whatsappCountryCode"),
    config.get("whatsappMessage"),
  );
}

export function whatsappFromClient(client: ClientProfile): WhatsAppTarget | undefined {
  return target(client.whatsappGatewayUrl, client.whatsappApiSecret, client.whatsappCountryCode, client.whatsappMessage);
}

function target(url?: string, secret?: string, countryCode?: string, message?: string): WhatsAppTarget | undefined {
  if (!url || !secret) return undefined;
  return {
    url: url.replace(/\/+$/, ""),
    secret,
    countryCode: countryCode || "91",
    message: message?.trim() || DEFAULT_WHATSAPP_MESSAGE,
  };
}

export async function sendWhatsApp(target: WhatsAppTarget, number: string, text: string, timeoutMs = 20_000): Promise<void> {
  const response = await fetch(`${target.url}/send`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-api-key": target.secret },
    body: JSON.stringify({ jid: number, text }),
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(body.error ?? `WhatsApp gateway returned HTTP ${response.status}`);
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
