import QRCode from "qrcode";
import type { SettingsStore } from "../db.js";
import type { AiRouter, ProviderConfig } from "../ai/router.js";
import { toWhatsAppNumber } from "../agent/whatsapp.js";
import { WhatsAppStore, type WaContact, type WaSource } from "./store.js";
import { historyMessages, parseReply, systemPrompt, type WaBusiness } from "./prompt.js";

/**
 * Built-in WhatsApp automation. Each business (scope) links its own WhatsApp number by scanning a
 * QR code (WhatsApp → Linked devices), the same way WhatsApp Web works. Incoming messages are
 * stored in the inbox and, unless a person has taken over the chat, answered by the AI from the
 * business's own information. Staff reply from the console or from the phone itself.
 *
 * The WhatsApp connection uses Baileys (an unofficial WhatsApp Web client). The socket is behind
 * a small interface so tests can drive the manager with a fake one.
 */

export interface WaAutomationSettings {
  /** Reply to incoming messages with the AI. */
  autoReply: boolean;
  /** Random wait before a reply is sent, so replies feel human (ms). */
  replyDelayMinMs: number;
  replyDelayMaxMs: number;
  typingIndicator: boolean;
  markRead: boolean;
  ignoreGroups: boolean;
  /** A reply from the business's own phone pauses the AI for that chat. */
  pauseOnStaffReply: boolean;
  /** Sent when no AI can answer (after the usual delay, like the original gateway). Empty: stay silent. */
  fallbackMessage: string;
  /** Extra rules for the AI. */
  instructions: string;
  /** Ready-made replies shown in the inbox. */
  quickReplies: string[];
  /** Booking links after calls go out on this WhatsApp (instead of an external gateway). */
  useForBookingLinks: boolean;
}

/** Defaults match the original 32 Teeth gateway (5-8 s human-like wait, fallback when the AI fails). */
export const DEFAULT_FALLBACK_MESSAGE = "Dhanyavaad! Abhi system busy hai. Hamari team aapko jaldi reply karegi.";
export const DEFAULT_WA_SETTINGS: WaAutomationSettings = {
  autoReply: true,
  replyDelayMinMs: 5_000,
  replyDelayMaxMs: 8_000,
  typingIndicator: true,
  markRead: true,
  ignoreGroups: true,
  pauseOnStaffReply: true,
  fallbackMessage: DEFAULT_FALLBACK_MESSAGE,
  instructions: "",
  quickReplies: [],
  useForBookingLinks: true,
};

export type WaConnectionState = "idle" | "connecting" | "qr" | "connected" | "logged_out" | "error";

/** The part of a Baileys socket the manager uses. */
export interface WaSocket {
  ev: { on(event: string, listener: (arg: any) => void): void };
  user?: { id: string; name?: string } | null;
  sendMessage(jid: string, content: { text: string }): Promise<{ key?: { id?: string | null } } | undefined>;
  sendPresenceUpdate(type: "composing" | "paused" | "available" | "unavailable", jid?: string): Promise<void>;
  readMessages(keys: unknown[]): Promise<void>;
  logout(): Promise<void>;
  end(error?: Error): void;
}

export interface WaAuthStore {
  creds: any;
  keys: Record<string, Record<string, any>>;
}

export type WaSocketFactory = (input: { auth: { creds: any; keys: any }; scope: string }) => Promise<WaSocket>;

/** Business details and keys for a scope; undefined when the business no longer exists. */
export type WaBusinessResolver = (scope: string) => (WaBusiness & { providers?: ProviderConfig[]; countryCode: string }) | undefined;

export interface WhatsAppManagerOptions {
  store: WhatsAppStore;
  settings: SettingsStore;
  router?: AiRouter;
  business: WaBusinessResolver;
  /** SaaS gate: may this business use the automation now? */
  entitled?(scope: string): { ok: boolean; message?: string };
  socketFactory?: WaSocketFactory;
  /** Delay multiplier (tests use 0). */
  delayScale?: number;
}

interface Session {
  scope: string;
  sock?: WaSocket;
  state: WaConnectionState;
  qr?: string;
  qrSvg?: string;
  me?: { id: string; name?: string };
  lastError?: string;
  everConnected: boolean;
  /** Set when the user asked to connect; a QR timeout then stops instead of looping. */
  wanted: boolean;
  reconnectTimer?: NodeJS.Timeout;
  reconnects: number;
  auth: WaAuthStore;
  saveTimer?: NodeJS.Timeout;
  queues: Map<string, Promise<void>>;
  /** Ids of messages we sent, so their echo is not stored twice. */
  sentIds: Set<string>;
  connectedAt?: string;
  startedAt: string;
  /** Counters like the original gateway's /health. */
  stats: { received: number; replied: number; failed: number; ignored: number; lastMessageAt?: string; lastReplyAt?: string };
}

/** Messages older than this when they arrive (history after a reconnect) are stored, not answered. */
const MAX_REPLY_AGE_MS = 10 * 60_000;
/** Guards against a fallback storm if a customer sends many messages while the AI is down. */
const FALLBACK_EVERY_MS = 60_000;
const newStats = () => ({ received: 0, replied: 0, failed: 0, ignored: 0 });

export class WhatsAppManager {
  private readonly sessions = new Map<string, Session>();
  private libPromise?: Promise<typeof import("@whiskeysockets/baileys")>;

  constructor(private readonly options: WhatsAppManagerOptions) {}

  get store(): WhatsAppStore {
    return this.options.store;
  }

  // ---- settings ----

  settings(scope: string): WaAutomationSettings {
    return { ...DEFAULT_WA_SETTINGS, ...this.options.settings.get<Partial<WaAutomationSettings>>(`whatsapp:${scope}`) };
  }

  updateSettings(scope: string, patch: Partial<WaAutomationSettings>): WaAutomationSettings {
    const next = { ...this.settings(scope), ...patch };
    if (next.replyDelayMaxMs < next.replyDelayMinMs) next.replyDelayMaxMs = next.replyDelayMinMs;
    this.options.settings.set(`whatsapp:${scope}`, next);
    return next;
  }

  // ---- connection ----

  status(scope: string) {
    const s = this.sessions.get(scope);
    return {
      state: s?.state ?? (this.options.store.loadSession(scope) ? "idle" : "logged_out"),
      connected: s?.state === "connected",
      qrSvg: s?.state === "qr" ? s.qrSvg ?? null : null,
      me: s?.me ? { id: s.me.id, name: s.me.name ?? null, number: numberFromJid(s.me.id) } : null,
      lastError: s?.lastError ?? null,
      connectedAt: s?.connectedAt ?? null,
      reconnects: s?.reconnects ?? 0,
      uptimeSeconds: s ? Math.floor((Date.now() - Date.parse(s.startedAt)) / 1000) : 0,
      live: s?.stats ?? newStats(),
    };
  }

  isConnected(scope: string): boolean {
    return this.sessions.get(scope)?.state === "connected";
  }

  /** Starts (or keeps) the connection. Shows a QR when the number isn't linked yet. */
  async connect(scope: string): Promise<void> {
    const existing = this.sessions.get(scope);
    if (existing && existing.state !== "logged_out" && existing.state !== "error" && existing.state !== "idle") {
      existing.wanted = true;
      return;
    }
    const session: Session = existing ?? {
      scope,
      state: "idle",
      everConnected: false,
      wanted: true,
      reconnects: 0,
      auth: await this.loadAuth(scope),
      queues: new Map(),
      sentIds: new Set(),
      startedAt: new Date().toISOString(),
      stats: newStats(),
    };
    session.wanted = true;
    this.sessions.set(scope, session);
    await this.open(session);
  }

  /** Reconnects every business that was linked before the server restarted. */
  async resumeAll(): Promise<void> {
    for (const scope of this.options.store.savedScopes()) {
      if (!this.options.business(scope)) continue;
      const auth = await this.loadAuth(scope);
      if (!auth.creds?.me) continue; // never finished linking
      const session: Session = { scope, state: "idle", everConnected: true, wanted: true, reconnects: 0, auth, queues: new Map(), sentIds: new Set(), startedAt: new Date().toISOString(), stats: newStats() };
      this.sessions.set(scope, session);
      this.options.store.addEvent(scope, "boot", "server started, reconnecting");
      await this.open(session).catch((error) => console.error(`whatsapp ${scope}: resume failed`, error));
    }
  }

  /** Unlinks the number (WhatsApp → Linked devices loses this device) and forgets the session. */
  async logout(scope: string): Promise<void> {
    const s = this.sessions.get(scope);
    if (s) {
      s.wanted = false;
      clearTimeout(s.reconnectTimer);
      try { await s.sock?.logout(); } catch { /* already gone */ }
      try { s.sock?.end(); } catch { /* ignore */ }
      s.sock = undefined;
      s.state = "logged_out";
      s.qr = s.qrSvg = undefined;
      s.me = undefined;
      clearTimeout(s.saveTimer);
    }
    this.options.store.clearSession(scope);
    this.sessions.delete(scope);
    this.options.store.addEvent(scope, "logout", "unlinked from the dashboard");
  }

  /** Drops the connection and opens it again (keeps the link). */
  async restart(scope: string): Promise<void> {
    const s = this.sessions.get(scope);
    if (!s) return this.connect(scope);
    clearTimeout(s.reconnectTimer);
    try { s.sock?.end(new Error("restart")); } catch { /* ignore */ }
    s.sock = undefined;
    s.state = "idle";
    s.wanted = true;
    this.options.store.addEvent(scope, "restart", "reconnect requested");
    await this.open(s);
  }

  /** Account deleted: unlink and remove all its chats. */
  async removeScope(scope: string): Promise<void> {
    await this.logout(scope);
    this.options.store.removeScope(scope);
    this.options.settings.set(`whatsapp:${scope}`, null);
  }

  stopAll(): void {
    for (const s of this.sessions.values()) {
      clearTimeout(s.reconnectTimer);
      try { s.sock?.end(); } catch { /* ignore */ }
    }
    this.sessions.clear();
  }

  // ---- sending ----

  /**
   * Sends a text to a number or chat and records it in the inbox. `source` tells the inbox who sent
   * it (staff in the console, a booking link from a call, the AI...).
   */
  async sendText(scope: string, to: string | { contactId: number }, text: string, meta: { source: WaSource; sentBy?: string; provider?: string; latencyMs?: number; tokens?: number } = { source: "staff" }) {
    const s = this.sessions.get(scope);
    let contact: WaContact | undefined;
    if (typeof to === "object") {
      contact = this.options.store.contact(scope, to.contactId);
      if (!contact) throw new Error("chat not found");
    } else {
      const countryCode = this.options.business(scope)?.countryCode ?? "91";
      const number = toWhatsAppNumber(to, countryCode);
      if (!number) throw new Error("enter a WhatsApp number with the country code, 10 or more digits");
      contact = this.options.store.upsertContact(scope, `${number}@s.whatsapp.net`, number, "");
    }
    const record = this.options.store.addMessage(contact, { direction: "out", source: meta.source, body: text, status: "pending", sentBy: meta.sentBy, provider: meta.provider, latencyMs: meta.latencyMs, tokens: meta.tokens });
    if (!s?.sock || s.state !== "connected") {
      this.options.store.setDelivery(record.id, false, undefined, "WhatsApp is not connected");
      throw new Error("WhatsApp is not connected. Scan the QR code on the WhatsApp page first.");
    }
    try {
      if (meta.source === "staff" && this.settings(scope).typingIndicator) {
        await s.sock.sendPresenceUpdate("composing", contact.jid).catch(() => undefined);
        await sleep(600 * (this.options.delayScale ?? 1));
      }
      const sent = await s.sock.sendMessage(contact.jid, { text });
      const id = sent?.key?.id ?? undefined;
      if (id) rememberId(s, id);
      this.options.store.setDelivery(record.id, true, id);
      this.bumpOut(contact.id);
      if (s) { s.stats.replied++; s.stats.lastReplyAt = new Date().toISOString(); }
      if (meta.source === "staff") this.options.store.updateContact(scope, contact.id, { needsHuman: false, unread: 0 });
      return { message: this.options.store.message(record.id)!, contact: this.options.store.contact(scope, contact.id)! };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.options.store.setDelivery(record.id, false, undefined, message);
      s.stats.failed++;
      throw new Error(`WhatsApp send failed: ${message}`);
    }
  }

  // ---- internals: connection ----

  private lib() {
    this.libPromise ??= import("@whiskeysockets/baileys");
    return this.libPromise;
  }

  private async loadAuth(scope: string): Promise<WaAuthStore> {
    const raw = this.options.store.loadSession(scope);
    if (raw) {
      try {
        const { BufferJSON } = await this.lib();
        const parsed = JSON.parse(raw, BufferJSON.reviver) as WaAuthStore;
        if (parsed?.creds) return { creds: parsed.creds, keys: parsed.keys ?? {} };
      } catch (error) {
        console.error(`whatsapp ${scope}: saved session unreadable, starting fresh`, error);
      }
    }
    if (this.options.socketFactory) return { creds: {}, keys: {} };
    const { initAuthCreds } = await this.lib();
    return { creds: initAuthCreds(), keys: {} };
  }

  private scheduleSave(s: Session, now = false): void {
    clearTimeout(s.saveTimer);
    const run = async () => {
      try {
        const { BufferJSON } = await this.lib();
        this.options.store.saveSession(s.scope, JSON.stringify(s.auth, BufferJSON.replacer));
      } catch (error) {
        console.error(`whatsapp ${s.scope}: session save failed`, error);
      }
    };
    if (now) void run();
    else s.saveTimer = setTimeout(() => void run(), 1_500);
  }

  private async authState(s: Session) {
    const lib = this.options.socketFactory ? undefined : await this.lib();
    return {
      creds: s.auth.creds,
      keys: {
        get: async (type: string, ids: string[]) => {
          const out: Record<string, unknown> = {};
          for (const id of ids) {
            let value = s.auth.keys[type]?.[id];
            if (value && type === "app-state-sync-key" && lib) value = lib.proto.Message.AppStateSyncKeyData.fromObject(value);
            if (value) out[id] = value;
          }
          return out;
        },
        set: async (data: Record<string, Record<string, unknown>>) => {
          for (const type in data) {
            s.auth.keys[type] ??= {};
            for (const id in data[type]) {
              const value = data[type]![id];
              if (value) s.auth.keys[type]![id] = value;
              else delete s.auth.keys[type]![id];
            }
          }
          this.scheduleSave(s);
        },
      },
    };
  }

  private async createSocket(s: Session): Promise<WaSocket> {
    const auth = await this.authState(s);
    if (this.options.socketFactory) return this.options.socketFactory({ auth, scope: s.scope });
    const lib = await this.lib();
    const { default: pino } = await import("pino");
    let version: [number, number, number] | undefined;
    try {
      ({ version } = await lib.fetchLatestBaileysVersion());
    } catch { /* use the bundled version */ }
    const makeWASocket = lib.default ?? lib.makeWASocket;
    return makeWASocket({
      ...(version ? { version } : {}),
      auth: auth as never,
      logger: pino({ level: "silent" }) as never,
      browser: lib.Browsers.ubuntu("Chrome"),
      markOnlineOnConnect: false,
      syncFullHistory: false,
      generateHighQualityLinkPreview: false,
      connectTimeoutMs: 60_000,
      keepAliveIntervalMs: 25_000,
    }) as unknown as WaSocket;
  }

  private async open(s: Session): Promise<void> {
    clearTimeout(s.reconnectTimer);
    s.state = "connecting";
    s.lastError = undefined;
    let sock: WaSocket;
    try {
      sock = await this.createSocket(s);
    } catch (error) {
      s.state = "error";
      s.lastError = error instanceof Error ? error.message : String(error);
      this.retry(s, 15_000);
      return;
    }
    s.sock = sock;

    sock.ev.on("creds.update", (update: Record<string, unknown>) => {
      Object.assign(s.auth.creds, update);
      this.scheduleSave(s);
    });

    sock.ev.on("connection.update", (update: { connection?: string; qr?: string; lastDisconnect?: { error?: { message?: string; output?: { statusCode?: number } } } }) => {
      if (s.sock !== sock) return;
      if (update.qr) {
        s.qr = update.qr;
        s.state = "qr";
        void QRCode.toString(update.qr, { type: "svg", margin: 1, errorCorrectionLevel: "M" }).then((svg) => {
          if (s.qr === update.qr) s.qrSvg = svg;
        });
        this.options.store.addEvent(s.scope, "qr", "QR ready to scan");
      }
      if (update.connection === "open") {
        s.state = "connected";
        s.qr = s.qrSvg = undefined;
        s.me = sock.user ?? s.auth.creds?.me;
        s.everConnected = true;
        s.reconnects = 0;
        s.connectedAt = new Date().toISOString();
        this.scheduleSave(s, true);
        console.log(`whatsapp ${s.scope}: connected as ${numberFromJid(s.me?.id ?? "") ?? "?"}`);
        this.options.store.addEvent(s.scope, "connected", numberFromJid(s.me?.id ?? "") ?? "");
      }
      if (update.connection === "close") {
        const code = update.lastDisconnect?.error?.output?.statusCode;
        s.lastError = `${update.lastDisconnect?.error?.message ?? "connection closed"}${code ? ` (${code})` : ""}`;
        s.sock = undefined;
        this.options.store.addEvent(s.scope, "disconnected", s.lastError);
        if (code === 401 || code === 403) {
          // Unlinked from the phone: forget the session and, like the original gateway, open a fresh
          // connection so a new QR is ready to scan. A QR nobody scans stops after it expires (408).
          console.warn(`whatsapp ${s.scope}: logged out (${code})`);
          s.me = undefined;
          this.options.store.clearSession(s.scope);
          void this.lib().then((lib) => {
            s.auth = { creds: this.options.socketFactory ? {} : lib.initAuthCreds(), keys: {} };
            s.everConnected = false;
            s.state = "connecting";
            this.retry(s, 1_500);
          }).catch(() => { s.state = "logged_out"; this.sessions.delete(s.scope); });
          return;
        }
        if (!s.everConnected && code === 408) {
          this.options.store.addEvent(s.scope, "qr_expired", "nobody scanned the QR");
          // Nobody scanned the QR in time: stop until the user asks again.
          s.state = "idle";
          s.lastError = "The QR code expired. Click Connect to get a new one.";
          return;
        }
        s.state = "connecting";
        s.reconnects++;
        this.retry(s, code === 515 ? 500 : Math.min(30_000, 2_000 * s.reconnects));
      }
    });

    sock.ev.on("messages.upsert", (event: { messages?: any[]; type?: string }) => {
      if (s.sock !== sock) return;
      for (const m of event.messages ?? []) {
        if (event.type !== "notify" && !m?.key?.fromMe) continue;
        const jid = m?.key?.remoteJid ?? "x";
        this.enqueue(s, jid, () => this.onMessage(s, m));
      }
    });
  }

  private retry(s: Session, ms: number): void {
    clearTimeout(s.reconnectTimer);
    if (!s.wanted) return;
    s.reconnectTimer = setTimeout(() => void this.open(s), ms);
    s.reconnectTimer.unref?.();
  }

  private enqueue(s: Session, jid: string, run: () => Promise<void>): void {
    const previous = s.queues.get(jid) ?? Promise.resolve();
    const next = previous
      .then(run)
      .catch((error) => console.error(`whatsapp ${s.scope}: message handling failed`, error))
      .finally(() => {
        if (s.queues.get(jid) === next) s.queues.delete(jid);
      });
    s.queues.set(jid, next);
  }

  /** Resolves when every queued message of a scope has been handled (tests). */
  async idle(scope: string): Promise<void> {
    const s = this.sessions.get(scope);
    while (s && s.queues.size) await Promise.all([...s.queues.values()]);
  }

  private bumpOut(contactId: number): void {
    this.options.store.countOut(contactId);
  }

  // ---- internals: messages ----

  private async onMessage(s: Session, m: any): Promise<void> {
    const rawJid: string | undefined = m?.key?.remoteJid;
    if (!rawJid || !m.message) return;
    if (rawJid === "status@broadcast" || rawJid.endsWith("@broadcast") || rawJid.endsWith("@newsletter")) { s.stats.ignored++; return; }
    const settings = this.settings(s.scope);
    if (rawJid.endsWith("@g.us") && settings.ignoreGroups) { s.stats.ignored++; return; }
    const waId: string | undefined = m.key.id ?? undefined;
    if (waId && (s.sentIds.has(waId) || this.options.store.hasMessage(s.scope, waId))) return;

    const text = textOf(m.message);
    if (text === undefined) return; // reactions, receipts, protocol messages

    // Prefer the phone-number address of the chat so calls' booking links and replies share a chat.
    const alt: string | undefined = m.key.remoteJidAlt ?? m.key.senderPn ?? undefined;
    const pnJid = rawJid.endsWith("@s.whatsapp.net") ? rawJid : alt?.endsWith("@s.whatsapp.net") ? alt : undefined;
    const jid = pnJid ?? rawJid;
    const phone = pnJid ? numberFromJid(pnJid) ?? null : null;
    const at = timestampOf(m);

    if (m.key.fromMe) {
      // Someone replied from the business's own phone.
      const contact = this.options.store.upsertContact(s.scope, jid, phone, "");
      this.options.store.addMessage(contact, { direction: "out", source: "phone", body: text, waId, sentBy: "phone", at });
      if (settings.pauseOnStaffReply && contact.aiEnabled) this.options.store.updateContact(s.scope, contact.id, { aiEnabled: false, needsHuman: false, unread: 0 });
      return;
    }

    const contact = this.options.store.upsertContact(s.scope, jid, phone, String(m.pushName ?? ""));
    this.options.store.addMessage(contact, { direction: "in", source: "patient", body: text, waId, at });
    s.stats.received++;
    s.stats.lastMessageAt = new Date().toISOString();
    const lang = detectLang(text);
    if (lang && lang !== contact.lang) this.options.store.setLang(contact.id, lang);
    if (settings.markRead) await s.sock?.readMessages([m.key]).catch(() => undefined);

    if (!settings.autoReply) return;
    const fresh = this.options.store.contact(s.scope, contact.id)!;
    if (!fresh.aiEnabled || fresh.needsHuman) return;
    if (Date.now() - Date.parse(at) > MAX_REPLY_AGE_MS) return;
    const gate = this.options.entitled?.(s.scope);
    if (gate && !gate.ok) {
      this.options.store.updateContact(s.scope, contact.id, { needsHuman: true });
      return;
    }
    await this.reply(s, fresh, settings);
  }

  private async reply(s: Session, contact: WaContact, settings: WaAutomationSettings): Promise<void> {
    const business = this.options.business(s.scope);
    const router = this.options.router;
    if (!business || !router) return;
    const started = Date.now();
    if (settings.typingIndicator) await s.sock?.sendPresenceUpdate("composing", contact.jid).catch(() => undefined);
    let reply: { text: string; handoff: boolean; provider?: string; tokens?: number } | undefined;
    try {
      // The business's own providers first (chat models and its Gemini key), then the platform's.
      const extra: ProviderConfig[] = business.providers ?? [];
      const history = historyMessages(this.options.store.messages(contact.id, 40));
      if (!history.length) return;
      const result = await router.chatText([{ role: "system", content: systemPrompt(business, contact) }, ...history], { extra });
      reply = { ...parseReply(result.text), provider: result.provider, tokens: result.tokens };
    } catch (error) {
      console.warn(`whatsapp ${s.scope}: AI reply failed: ${error instanceof Error ? error.message : error}`);
    }

    const scale = this.options.delayScale ?? 1;
    const wait = randomBetween(settings.replyDelayMinMs, settings.replyDelayMaxMs) * scale - (Date.now() - started);

    if (!reply?.text) {
      // No AI could answer: hand the chat to staff and, like the original gateway, send the fallback
      // message after the usual human-like wait.
      this.options.store.updateContact(s.scope, contact.id, { needsHuman: true });
      const last = contact.lastFallbackAt ? Date.parse(contact.lastFallbackAt) : 0;
      if (settings.fallbackMessage.trim() && Date.now() - last > FALLBACK_EVERY_MS) {
        if (wait > 0) await sleep(wait);
        if (settings.typingIndicator) await s.sock?.sendPresenceUpdate("paused", contact.jid).catch(() => undefined);
        this.options.store.markFallback(contact.id);
        await this.sendText(s.scope, { contactId: contact.id }, settings.fallbackMessage.trim(), { source: "fallback" }).catch(() => undefined);
      } else if (settings.typingIndicator) await s.sock?.sendPresenceUpdate("paused", contact.jid).catch(() => undefined);
      return;
    }

    if (wait > 0) await sleep(wait);
    if (settings.typingIndicator) await s.sock?.sendPresenceUpdate("paused", contact.jid).catch(() => undefined);
    // Staff may have taken over while the AI was thinking.
    const latest = this.options.store.contact(s.scope, contact.id);
    if (!latest || !latest.aiEnabled) return;
    await this.sendText(s.scope, { contactId: contact.id }, reply.text, { source: "ai", provider: reply.provider, latencyMs: Date.now() - started, tokens: reply.tokens }).catch((error) =>
      console.warn(`whatsapp ${s.scope}: ${error instanceof Error ? error.message : error}`),
    );
    if (reply.handoff) this.options.store.updateContact(s.scope, contact.id, { needsHuman: true });
  }
}

function rememberId(s: Session, id: string): void {
  s.sentIds.add(id);
  if (s.sentIds.size > 500) s.sentIds.delete(s.sentIds.values().next().value!);
}

/** The text of a message, a short label for media, or undefined for non-chat messages. */
export function textOf(message: any): string | undefined {
  const inner = message?.ephemeralMessage?.message ?? message?.viewOnceMessage?.message ?? message?.viewOnceMessageV2?.message ?? message?.documentWithCaptionMessage?.message ?? message;
  if (!inner) return undefined;
  const text =
    inner.conversation ??
    inner.extendedTextMessage?.text ??
    inner.imageMessage?.caption ??
    inner.videoMessage?.caption ??
    inner.documentMessage?.caption ??
    inner.buttonsResponseMessage?.selectedDisplayText ??
    inner.listResponseMessage?.title ??
    inner.templateButtonReplyMessage?.selectedDisplayText;
  if (typeof text === "string" && text.trim()) return text;
  // Same labels as the original gateway, so the AI and staff see what was sent.
  if (inner.audioMessage) return "[Voice message bheja]";
  if (inner.imageMessage) return "[Photo bheji]";
  if (inner.videoMessage) return "[Video bheja]";
  if (inner.documentMessage) return `[Document bheja${inner.documentMessage.fileName ? `: ${inner.documentMessage.fileName}` : ""}]`;
  if (inner.locationMessage || inner.liveLocationMessage) return "[Location bheji]";
  if (inner.contactMessage || inner.contactsArrayMessage) return "[Contact bheja]";
  if (inner.stickerMessage) return "[Sticker bheja]";
  return undefined;
}

export function numberFromJid(jid: string): string | undefined {
  const m = /^(\d{6,15})(?::\d+)?@s\.whatsapp\.net$/.exec(jid);
  return m?.[1];
}

function timestampOf(m: any): string {
  const t = Number(typeof m.messageTimestamp === "object" ? m.messageTimestamp?.low : m.messageTimestamp);
  return t > 0 ? new Date(t * 1000).toISOString() : new Date().toISOString();
}

function randomBetween(min: number, max: number): number {
  return Math.round(min + Math.random() * Math.max(0, max - min));
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** "hi" for Devanagari, "hinglish" for romanised Hindi, "en" for English, "" when unsure. */
export function detectLang(text: string): string {
  const t = text.replace(/\[[^\]]*\]/g, " ").trim();
  if (!t) return "";
  if (/[\u0900-\u097F]/.test(t)) return "hi";
  const words = t.toLowerCase().match(/[a-z]+/g) ?? [];
  if (!words.length) return "";
  const hinglish = new Set(["hai", "hain", "kya", "kab", "kaise", "kitne", "kitna", "baje", "nahi", "nahin", "haan", "ji", "aap", "aapka", "mera", "meri", "mujhe", "hum", "kal", "aaj", "karna", "karein", "kar", "chahiye", "dard", "bhai", "acha", "accha", "theek", "thik", "namaste", "dhanyavaad", "kripya", "khulega", "milega", "batao", "bataiye", "ke", "ka", "ki", "ko", "se", "me", "mein", "par", "wala", "abhi", "kyu", "kyun", "sakta", "sakti"]);
  const hits = words.filter((w) => hinglish.has(w)).length;
  return hits >= 1 && hits / words.length >= 0.15 ? "hinglish" : "en";
}
