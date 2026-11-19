import type { Database } from "../db.js";

/**
 * Inbox storage for the built-in WhatsApp automation. A "scope" is the business a WhatsApp number
 * belongs to: a client id (SaaS customer) or "default" (a single-business deployment).
 */

export type WaSource = "patient" | "ai" | "staff" | "phone" | "fallback" | "system";

export interface WaContact {
  id: number;
  scope: string;
  jid: string;
  phone: string | null;
  name: string;
  aiEnabled: boolean;
  needsHuman: boolean;
  status: "open" | "resolved";
  unread: number;
  notes: string;
  tags: string;
  msgIn: number;
  msgOut: number;
  lastMessageAt: string | null;
  lastFallbackAt: string | null;
  createdAt: string;
  /** Last message preview (list view only). */
  preview?: string;
  previewSource?: WaSource;
}

export interface WaMessage {
  id: number;
  contactId: number;
  direction: "in" | "out";
  source: WaSource;
  body: string;
  status: "received" | "sent" | "failed" | "pending";
  error: string | null;
  waId: string | null;
  sentBy: string | null;
  provider: string | null;
  latencyMs: number | null;
  createdAt: string;
}

export type ContactFilter = "all" | "unread" | "human" | "open" | "resolved" | "aioff";

interface ContactRow {
  id: number;
  scope: string;
  jid: string;
  phone: string | null;
  name: string;
  ai_enabled: number;
  needs_human: number;
  status: string;
  unread: number;
  notes: string;
  tags: string;
  msg_in: number;
  msg_out: number;
  last_message_at: string | null;
  last_fallback_at: string | null;
  created_at: string;
  pv?: string | null;
  pvs?: string | null;
}

interface MessageRow {
  id: number;
  contact_id: number;
  direction: string;
  source: string;
  body: string;
  status: string;
  error: string | null;
  wa_id: string | null;
  sent_by: string | null;
  provider: string | null;
  latency_ms: number | null;
  created_at: string;
}

const FILTERS: Record<ContactFilter, string> = {
  all: "1",
  unread: "c.unread > 0",
  human: "c.needs_human = 1 AND c.status = 'open'",
  open: "c.status = 'open'",
  resolved: "c.status = 'resolved'",
  aioff: "c.ai_enabled = 0",
};

export class WhatsAppStore {
  constructor(private readonly db: Database) {}

  // ---- linked-device session (Baileys auth state) ----

  loadSession(scope: string): string | undefined {
    const row = this.db.prepare("SELECT data FROM wa_sessions WHERE scope = ?").get(scope) as { data: string } | undefined;
    return row?.data;
  }

  saveSession(scope: string, data: string): void {
    this.db
      .prepare("INSERT INTO wa_sessions (scope, data, updated_at) VALUES (?, ?, ?) ON CONFLICT(scope) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at")
      .run(scope, data, new Date().toISOString());
  }

  clearSession(scope: string): void {
    this.db.prepare("DELETE FROM wa_sessions WHERE scope = ?").run(scope);
  }

  savedScopes(): string[] {
    return (this.db.prepare("SELECT scope FROM wa_sessions").all() as { scope: string }[]).map((r) => r.scope);
  }

  /** Removes everything of a business (account deleted). */
  removeScope(scope: string): void {
    this.db.prepare("DELETE FROM wa_messages WHERE scope = ?").run(scope);
    this.db.prepare("DELETE FROM wa_contacts WHERE scope = ?").run(scope);
    this.clearSession(scope);
  }

  // ---- contacts ----

  upsertContact(scope: string, jid: string, phone: string | null, name: string): WaContact {
    const now = new Date().toISOString();
    this.db
      .prepare(
        `INSERT INTO wa_contacts (scope, jid, phone, name, created_at) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT(scope, jid) DO UPDATE SET
           phone = COALESCE(excluded.phone, wa_contacts.phone),
           name = CASE WHEN wa_contacts.name = '' THEN excluded.name ELSE wa_contacts.name END`,
      )
      .run(scope, jid, phone, name.slice(0, 150), now);
    return this.contactByJid(scope, jid)!;
  }

  contactByJid(scope: string, jid: string): WaContact | undefined {
    const row = this.db.prepare("SELECT * FROM wa_contacts WHERE scope = ? AND jid = ?").get(scope, jid) as ContactRow | undefined;
    return row ? toContact(row) : undefined;
  }

  contact(scope: string, id: number): WaContact | undefined {
    const row = this.db.prepare("SELECT * FROM wa_contacts WHERE scope = ? AND id = ?").get(scope, id) as ContactRow | undefined;
    return row ? toContact(row) : undefined;
  }

  listContacts(scope: string, filter: ContactFilter = "all", search = "", limit = 200): WaContact[] {
    const where = [`c.scope = ?`, FILTERS[filter] ?? "1"];
    const params: (string | number)[] = [scope];
    if (search.trim()) {
      const like = `%${search.trim().replace(/[%_]/g, "")}%`;
      where.push(
        "(c.name LIKE ? OR c.phone LIKE ? OR c.notes LIKE ? OR c.tags LIKE ? OR EXISTS (SELECT 1 FROM wa_messages m WHERE m.contact_id = c.id AND m.body LIKE ?))",
      );
      params.push(like, like, like, like, like);
    }
    params.push(Math.min(Math.max(limit, 1), 500));
    const rows = this.db
      .prepare(
        `SELECT c.*,
           (SELECT body FROM wa_messages WHERE contact_id = c.id ORDER BY id DESC LIMIT 1) AS pv,
           (SELECT source FROM wa_messages WHERE contact_id = c.id ORDER BY id DESC LIMIT 1) AS pvs
         FROM wa_contacts c WHERE ${where.join(" AND ")}
         ORDER BY (c.needs_human = 1 AND c.status = 'open') DESC, c.last_message_at DESC LIMIT ?`,
      )
      .all(...params) as unknown as ContactRow[];
    return rows.map(toContact);
  }

  counts(scope: string): Record<ContactFilter, number> {
    const r = this.db
      .prepare(
        `SELECT COUNT(*) AS a, SUM(unread > 0) AS u, SUM(needs_human = 1 AND status = 'open') AS h,
           SUM(status = 'open') AS o, SUM(status = 'resolved') AS r, SUM(ai_enabled = 0) AS x
         FROM wa_contacts WHERE scope = ?`,
      )
      .get(scope) as Record<string, number | null>;
    return { all: r.a ?? 0, unread: r.u ?? 0, human: r.h ?? 0, open: r.o ?? 0, resolved: r.r ?? 0, aioff: r.x ?? 0 };
  }

  updateContact(
    scope: string,
    id: number,
    patch: Partial<Pick<WaContact, "name" | "notes" | "tags" | "aiEnabled" | "needsHuman" | "status" | "unread">>,
  ): WaContact | undefined {
    const current = this.contact(scope, id);
    if (!current) return undefined;
    const next = { ...current, ...patch };
    this.db
      .prepare("UPDATE wa_contacts SET name = ?, notes = ?, tags = ?, ai_enabled = ?, needs_human = ?, status = ?, unread = ? WHERE id = ?")
      .run(next.name, next.notes, next.tags, next.aiEnabled ? 1 : 0, next.needsHuman ? 1 : 0, next.status, next.unread, id);
    return this.contact(scope, id);
  }

  markFallback(id: number): void {
    this.db.prepare("UPDATE wa_contacts SET last_fallback_at = ? WHERE id = ?").run(new Date().toISOString(), id);
  }

  deleteContact(scope: string, id: number): boolean {
    this.db.prepare("DELETE FROM wa_messages WHERE contact_id = ? AND scope = ?").run(id, scope);
    return this.db.prepare("DELETE FROM wa_contacts WHERE id = ? AND scope = ?").run(id, scope).changes > 0;
  }

  // ---- messages ----

  hasMessage(scope: string, waId: string): boolean {
    return Boolean(this.db.prepare("SELECT 1 FROM wa_messages WHERE scope = ? AND wa_id = ?").get(scope, waId));
  }

  addMessage(
    contact: WaContact,
    m: { direction: "in" | "out"; source: WaSource; body: string; status?: WaMessage["status"]; error?: string; waId?: string; sentBy?: string; provider?: string; latencyMs?: number; at?: string },
  ): WaMessage {
    const at = m.at ?? new Date().toISOString();
    const result = this.db
      .prepare(
        `INSERT INTO wa_messages (contact_id, scope, direction, source, body, status, error, wa_id, sent_by, provider, latency_ms, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        contact.id, contact.scope, m.direction, m.source, m.body.slice(0, 8000),
        m.status ?? (m.direction === "in" ? "received" : "sent"), m.error?.slice(0, 300) ?? null, m.waId ?? null,
        m.sentBy ?? null, m.provider ?? null, m.latencyMs ?? null, at,
      );
    if (m.direction === "in") {
      this.db.prepare("UPDATE wa_contacts SET msg_in = msg_in + 1, unread = unread + 1, last_message_at = ?, status = 'open' WHERE id = ?").run(at, contact.id);
    } else if ((m.status ?? "sent") === "sent") {
      this.db.prepare("UPDATE wa_contacts SET msg_out = msg_out + 1, last_message_at = ? WHERE id = ?").run(at, contact.id);
    }
    return this.message(Number(result.lastInsertRowid))!;
  }

  setDelivery(id: number, ok: boolean, waId?: string, error?: string): void {
    this.db.prepare("UPDATE wa_messages SET status = ?, wa_id = COALESCE(?, wa_id), error = ? WHERE id = ?").run(ok ? "sent" : "failed", waId ?? null, error?.slice(0, 300) ?? null, id);
  }

  /** A pending message went out: count it and move the chat to the top. */
  countOut(contactId: number): void {
    this.db.prepare("UPDATE wa_contacts SET msg_out = msg_out + 1, last_message_at = ? WHERE id = ?").run(new Date().toISOString(), contactId);
  }

  message(id: number): WaMessage | undefined {
    const row = this.db.prepare("SELECT * FROM wa_messages WHERE id = ?").get(id) as MessageRow | undefined;
    return row ? toMessage(row) : undefined;
  }

  messages(contactId: number, limit = 400): WaMessage[] {
    const rows = this.db.prepare("SELECT * FROM wa_messages WHERE contact_id = ? ORDER BY id DESC LIMIT ?").all(contactId, limit) as unknown as MessageRow[];
    return rows.reverse().map(toMessage);
  }

  /** Totals for the overview cards. */
  stats(scope: string, sinceIso: string) {
    const r = this.db
      .prepare(
        `SELECT SUM(direction = 'in') AS received, SUM(direction = 'out' AND source = 'ai' AND status = 'sent') AS ai,
           SUM(direction = 'out' AND source IN ('staff', 'phone') AND status = 'sent') AS staff,
           SUM(status = 'failed') AS failed, AVG(CASE WHEN source = 'ai' THEN latency_ms END) AS latency,
           COUNT(DISTINCT contact_id) AS chats
         FROM wa_messages WHERE scope = ? AND created_at >= ?`,
      )
      .get(scope, sinceIso) as Record<string, number | null>;
    const fresh = this.db.prepare("SELECT COUNT(*) AS n FROM wa_contacts WHERE scope = ? AND created_at >= ?").get(scope, sinceIso) as { n: number };
    return {
      received: r.received ?? 0,
      aiReplies: r.ai ?? 0,
      staffReplies: r.staff ?? 0,
      failed: r.failed ?? 0,
      avgReplyMs: r.latency ? Math.round(r.latency) : null,
      chats: r.chats ?? 0,
      newContacts: fresh.n,
      ...this.counts(scope),
    };
  }
}

function toContact(r: ContactRow): WaContact {
  return {
    id: r.id,
    scope: r.scope,
    jid: r.jid,
    phone: r.phone,
    name: r.name,
    aiEnabled: r.ai_enabled === 1,
    needsHuman: r.needs_human === 1,
    status: r.status === "resolved" ? "resolved" : "open",
    unread: r.unread,
    notes: r.notes,
    tags: r.tags,
    msgIn: r.msg_in,
    msgOut: r.msg_out,
    lastMessageAt: r.last_message_at,
    lastFallbackAt: r.last_fallback_at,
    createdAt: r.created_at,
    ...(r.pv !== undefined ? { preview: r.pv ?? "", previewSource: (r.pvs ?? undefined) as WaSource | undefined } : {}),
  };
}

function toMessage(r: MessageRow): WaMessage {
  return {
    id: r.id,
    contactId: r.contact_id,
    direction: r.direction === "in" ? "in" : "out",
    source: r.source as WaSource,
    body: r.body,
    status: r.status as WaMessage["status"],
    error: r.error,
    waId: r.wa_id,
    sentBy: r.sent_by,
    provider: r.provider,
    latencyMs: r.latency_ms,
    createdAt: r.created_at,
  };
}
