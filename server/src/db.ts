import { existsSync, mkdirSync, readFileSync, renameSync } from "node:fs";
import path from "node:path";
import { DatabaseSync } from "node:sqlite";

export type Database = DatabaseSync;

/**
 * Everything the gateway keeps lives in one SQLite file (`<dataDir>/gateway.db`): phones, calls and
 * their events, transcripts, taken messages, webhook deliveries, settings, API keys and console
 * sessions. Without a data directory (tests) the database is in memory.
 */
export function openDatabase(dataDir?: string): Database {
  let file = ":memory:";
  if (dataDir) {
    mkdirSync(dataDir, { recursive: true });
    file = path.join(dataDir, "gateway.db");
  }
  const db = new DatabaseSync(file);
  db.exec("PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 3000; PRAGMA foreign_keys = ON;");
  migrate(db);
  if (dataDir) importLegacyFiles(db, dataDir);
  return db;
}

const MIGRATIONS: string[] = [
  `
  CREATE TABLE settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
  );
  CREATE TABLE devices (
    id TEXT PRIMARY KEY,
    name TEXT,
    metadata TEXT,
    token_hash TEXT,
    registered_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    health TEXT,
    health_at TEXT
  );
  CREATE TABLE calls (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    remote_number TEXT NOT NULL,
    direction TEXT NOT NULL,
    state TEXT NOT NULL,
    native_call_id TEXT,
    webhook_url TEXT,
    metadata TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    answered_at TEXT,
    ended_at TEXT
  );
  CREATE INDEX calls_created ON calls (created_at DESC);
  CREATE INDEX calls_device ON calls (device_id, created_at DESC);
  CREATE TABLE call_events (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    call_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    event TEXT NOT NULL,
    payload TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    received_at TEXT NOT NULL,
    UNIQUE (device_id, event_id)
  );
  CREATE INDEX call_events_call ON call_events (call_id, seq);
  CREATE TABLE transcripts (
    call_id TEXT PRIMARY KEY,
    lines TEXT NOT NULL,
    updated_at TEXT NOT NULL
  );
  CREATE TABLE messages (
    call_id TEXT PRIMARY KEY,
    body TEXT NOT NULL,
    taken_at TEXT NOT NULL
  );
  CREATE TABLE webhook_deliveries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    call_id TEXT,
    event_id TEXT NOT NULL,
    event TEXT NOT NULL,
    url TEXT NOT NULL,
    attempt INTEGER NOT NULL,
    ok INTEGER NOT NULL,
    error TEXT,
    at TEXT NOT NULL
  );
  CREATE INDEX webhook_deliveries_at ON webhook_deliveries (at DESC);
  CREATE TABLE api_keys (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    prefix TEXT NOT NULL,
    key_hash TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    last_used_at TEXT,
    revoked_at TEXT
  );
  CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
  );
  `,
  // Email + password accounts replace the single console password.
  `
  CREATE TABLE users (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
  );
  DELETE FROM sessions;
  ALTER TABLE sessions ADD COLUMN user_id TEXT NOT NULL DEFAULT '';
  DELETE FROM settings WHERE key = 'adminPassword';
  `,
  // Agent stream (server dials out to a WebSocket) attached to a call.
  `
  ALTER TABLE calls ADD COLUMN stream TEXT;
  `,
  // Clients: businesses the receptionist answers for, each with its own phones.
  `
  CREATE TABLE clients (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    settings TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
  );
  ALTER TABLE devices ADD COLUMN client_id TEXT;
  `,
  // Analytics: which client a call belonged to when it happened, and booking links sent.
  `
  ALTER TABLE calls ADD COLUMN client_id TEXT;
  UPDATE calls SET client_id = (SELECT client_id FROM devices WHERE devices.id = calls.device_id);
  CREATE INDEX calls_client ON calls (client_id, created_at DESC);
  CREATE TABLE whatsapp_sends (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    call_id TEXT,
    client_id TEXT,
    to_suffix TEXT NOT NULL,
    ok INTEGER NOT NULL,
    error TEXT,
    at TEXT NOT NULL
  );
  CREATE INDEX whatsapp_sends_at ON whatsapp_sends (at DESC);
  `,
  // SaaS: paying customers, plans, licenses, usage, payments and an audit log. A central server
  // serves many customers; each customer owns one client (its business settings and phones).
  `
  CREATE TABLE plans (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    price_monthly INTEGER NOT NULL,
    price_yearly INTEGER NOT NULL,
    setup_fee INTEGER NOT NULL DEFAULT 0,
    currency TEXT NOT NULL DEFAULT 'INR',
    minutes_included INTEGER NOT NULL,
    max_phones INTEGER NOT NULL,
    trial_days INTEGER NOT NULL DEFAULT 7,
    features TEXT,
    active INTEGER NOT NULL DEFAULT 1,
    public INTEGER NOT NULL DEFAULT 1,
    sort INTEGER NOT NULL DEFAULT 0,
    razorpay_plan_monthly TEXT,
    razorpay_plan_yearly TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
  );
  CREATE TABLE customers (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    client_id TEXT,
    plan_id TEXT,
    billing_cycle TEXT NOT NULL DEFAULT 'monthly',
    status TEXT NOT NULL DEFAULT 'trialing',
    lifetime INTEGER NOT NULL DEFAULT 0,
    trial_ends_at TEXT,
    current_period_end TEXT,
    razorpay_customer_id TEXT,
    razorpay_subscription_id TEXT,
    notes TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
  );
  CREATE INDEX customers_client ON customers (client_id);
  CREATE TABLE licenses (
    key TEXT PRIMARY KEY,
    customer_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    issued_at TEXT NOT NULL,
    activated_at TEXT,
    last_seen_at TEXT,
    last_device TEXT
  );
  CREATE INDEX licenses_customer ON licenses (customer_id);
  CREATE TABLE usage_counters (
    customer_id TEXT NOT NULL,
    period TEXT NOT NULL,
    seconds INTEGER NOT NULL DEFAULT 0,
    calls INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (customer_id, period)
  );
  CREATE TABLE payments (
    id TEXT PRIMARY KEY,
    customer_id TEXT,
    amount INTEGER NOT NULL,
    currency TEXT NOT NULL DEFAULT 'INR',
    status TEXT NOT NULL,
    kind TEXT,
    razorpay_payment_id TEXT,
    razorpay_order_id TEXT,
    at TEXT NOT NULL,
    meta TEXT
  );
  CREATE INDEX payments_customer ON payments (customer_id, at DESC);
  CREATE TABLE audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT,
    detail TEXT,
    at TEXT NOT NULL
  );
  CREATE INDEX audit_log_at ON audit_log (at DESC);
  ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'admin';
  ALTER TABLE users ADD COLUMN customer_id TEXT;
  `,
  // Each connected call is billed to its customer exactly once.
  `
  CREATE TABLE billed_calls (
    call_id TEXT PRIMARY KEY,
    customer_id TEXT NOT NULL,
    seconds INTEGER NOT NULL,
    at TEXT NOT NULL
  );
  `,
  // Built-in WhatsApp automation: one linked WhatsApp per scope (a client id, or "default"),
  // its contacts (one per chat) and the full message history shown in the inbox.
  `
  CREATE TABLE wa_sessions (
    scope TEXT PRIMARY KEY,
    data TEXT NOT NULL,
    updated_at TEXT NOT NULL
  );
  CREATE TABLE wa_contacts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scope TEXT NOT NULL,
    jid TEXT NOT NULL,
    phone TEXT,
    name TEXT NOT NULL DEFAULT '',
    ai_enabled INTEGER NOT NULL DEFAULT 1,
    needs_human INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'open',
    unread INTEGER NOT NULL DEFAULT 0,
    notes TEXT NOT NULL DEFAULT '',
    tags TEXT NOT NULL DEFAULT '',
    msg_in INTEGER NOT NULL DEFAULT 0,
    msg_out INTEGER NOT NULL DEFAULT 0,
    last_message_at TEXT,
    last_fallback_at TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (scope, jid)
  );
  CREATE INDEX wa_contacts_scope ON wa_contacts (scope, last_message_at DESC);
  CREATE TABLE wa_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    contact_id INTEGER NOT NULL REFERENCES wa_contacts (id) ON DELETE CASCADE,
    scope TEXT NOT NULL,
    direction TEXT NOT NULL,
    source TEXT NOT NULL,
    body TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'sent',
    error TEXT,
    wa_id TEXT,
    sent_by TEXT,
    provider TEXT,
    latency_ms INTEGER,
    created_at TEXT NOT NULL
  );
  CREATE INDEX wa_messages_contact ON wa_messages (contact_id, id DESC);
  CREATE INDEX wa_messages_scope ON wa_messages (scope, created_at);
  CREATE UNIQUE INDEX wa_messages_wa_id ON wa_messages (scope, wa_id) WHERE wa_id IS NOT NULL;
  `,
  // WhatsApp parity with the original gateway: each chat's language, AI tokens per reply, and the
  // connection event log (qr, connected, disconnected, logout, restart, boot).
  `
  ALTER TABLE wa_contacts ADD COLUMN lang TEXT NOT NULL DEFAULT '';
  ALTER TABLE wa_messages ADD COLUMN tokens INTEGER;
  CREATE TABLE wa_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scope TEXT NOT NULL,
    type TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    at TEXT NOT NULL
  );
  CREATE INDEX wa_events_scope ON wa_events (scope, id DESC);
  `,
];

function migrate(db: Database): void {
  const { user_version: version } = db.prepare("PRAGMA user_version").get() as { user_version: number };
  for (let i = version; i < MIGRATIONS.length; i++) {
    db.exec("BEGIN");
    try {
      db.exec(MIGRATIONS[i]!);
      db.exec(`PRAGMA user_version = ${i + 1}`);
      db.exec("COMMIT");
    } catch (error) {
      db.exec("ROLLBACK");
      throw error;
    }
  }
}

/** One-time import of the JSON files older versions wrote; each file is renamed to *.imported. */
function importLegacyFiles(db: Database, dataDir: string): void {
  const devicesFile = path.join(dataDir, "devices.json");
  if (existsSync(devicesFile)) {
    const saved = JSON.parse(readFileSync(devicesFile, "utf8")) as {
      devices?: { id: string; name?: string; metadata?: unknown; registeredAt: string; tokenHash?: string }[];
    };
    const insert = db.prepare(
      `INSERT OR IGNORE INTO devices (id, name, metadata, token_hash, registered_at, last_seen_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
    );
    for (const d of saved.devices ?? []) {
      insert.run(d.id, d.name ?? null, json(d.metadata), d.tokenHash ?? null, d.registeredAt, d.registeredAt);
    }
    renameSync(devicesFile, `${devicesFile}.imported`);
  }

  const settingsFile = path.join(dataDir, "agent-settings.json");
  if (existsSync(settingsFile)) {
    db.prepare("INSERT OR IGNORE INTO settings (key, value) VALUES ('agent', ?)").run(
      readFileSync(settingsFile, "utf8"),
    );
    renameSync(settingsFile, `${settingsFile}.imported`);
  }

  const messagesFile = path.join(dataDir, "agent-messages.jsonl");
  if (existsSync(messagesFile)) {
    const upsert = db.prepare(
      "INSERT OR REPLACE INTO messages (call_id, body, taken_at) VALUES (?, ?, ?)",
    );
    for (const line of readFileSync(messagesFile, "utf8").split("\n").filter(Boolean)) {
      const message = JSON.parse(line) as { callId: string; takenAt: string };
      upsert.run(message.callId, line, message.takenAt);
    }
    renameSync(messagesFile, `${messagesFile}.imported`);
  }
}

export function json(value: unknown): string | null {
  return value === undefined || value === null ? null : JSON.stringify(value);
}

export function parseJson<T>(value: unknown): T | undefined {
  return typeof value === "string" ? (JSON.parse(value) as T) : undefined;
}

/** Key/value settings stored as JSON. */
export class SettingsStore {
  constructor(private readonly db: Database) {}

  get<T>(key: string): T | undefined {
    const row = this.db.prepare("SELECT value FROM settings WHERE key = ?").get(key) as { value: string } | undefined;
    return row ? (JSON.parse(row.value) as T) : undefined;
  }

  set(key: string, value: unknown): void {
    if (value === undefined || value === null) {
      this.db.prepare("DELETE FROM settings WHERE key = ?").run(key);
      return;
    }
    this.db
      .prepare("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value")
      .run(key, JSON.stringify(value));
  }
}
