import { randomInt } from "node:crypto";

// No 0/O/1/I/L: codes are also typed by hand.
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const CODE_LENGTH = 8;
const MAX_FAILURES_PER_MINUTE = 20;

/**
 * One-time pairing codes shown as a QR code in the admin console. A phone exchanges a code for
 * its device credentials (POST /v1/pair), so the QR itself never carries a secret that lasts.
 */
interface PendingCode {
  expiresAt: number;
  /** Bind the paired phone to this client (central SaaS: a customer's own business). */
  clientId?: string;
}

export class PairingService {
  private readonly codes = new Map<string, PendingCode>();
  private failures: number[] = [];

  constructor(private readonly ttlMs = 10 * 60_000) {}

  create(clientId?: string): { code: string; expiresAt: string } {
    this.prune();
    let code = "";
    for (let i = 0; i < CODE_LENGTH; i++) code += ALPHABET[randomInt(ALPHABET.length)];
    const expiresAt = Date.now() + this.ttlMs;
    this.codes.set(code, { expiresAt, clientId });
    return { code, expiresAt: new Date(expiresAt).toISOString() };
  }

  /** Single use. Returns the bound clientId (or null) on success, else why not. */
  consume(input: string): { clientId: string | null } | "invalid" | "throttled" {
    const now = Date.now();
    this.failures = this.failures.filter((at) => now - at < 60_000);
    if (this.failures.length >= MAX_FAILURES_PER_MINUTE) return "throttled";

    const code = input.trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
    const pending = this.codes.get(code);
    if (!pending || pending.expiresAt < now) {
      this.failures.push(now);
      return "invalid";
    }
    this.codes.delete(code);
    return { clientId: pending.clientId ?? null };
  }

  private prune(): void {
    const now = Date.now();
    for (const [code, p] of this.codes) if (p.expiresAt < now) this.codes.delete(code);
  }
}

export function pairUri(publicUrl: string, code: string, license?: string): string {
  const base = `httpgateway://pair?url=${encodeURIComponent(publicUrl)}&code=${code}`;
  return license ? `${base}&lic=${encodeURIComponent(license)}` : base;
}
