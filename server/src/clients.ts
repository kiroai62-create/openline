import { randomUUID } from "node:crypto";
import type { Database } from "./db.js";

/**
 * A business the receptionist answers for (clinic, hospital, shop). Each client has its own
 * phones (SIMs), knowledge, booking link and WhatsApp gateway, so one server can run the
 * receptionist for many businesses at once. Phones without a client use the global assistant.
 */
export interface ClientProfile {
  id: string;
  name: string;
  /** Answer this client's incoming calls with the receptionist. */
  enabled: boolean;
  /** Everything the receptionist should know (services, timings, address, doctors, fees). */
  about: string;
  bookingUrl: string;
  voice: string;
  whatsappGatewayUrl: string;
  whatsappApiSecret: string;
  whatsappCountryCode: string;
  /** Template with {name}, {business}, {link}; empty = default message. */
  whatsappMessage: string;
  createdAt: string;
  updatedAt: string;
}

export type ClientInput = Partial<Omit<ClientProfile, "id" | "createdAt" | "updatedAt">>;

const DEFAULTS: Omit<ClientProfile, "id" | "name" | "createdAt" | "updatedAt"> = {
  enabled: true,
  about: "",
  bookingUrl: "",
  voice: "Kore",
  whatsappGatewayUrl: "",
  whatsappApiSecret: "",
  whatsappCountryCode: "91",
  whatsappMessage: "",
};

interface ClientRow {
  id: string;
  name: string;
  settings: string;
  created_at: string;
  updated_at: string;
}

export class ClientStore {
  constructor(private readonly db: Database) {}

  list(): ClientProfile[] {
    const rows = this.db.prepare("SELECT * FROM clients ORDER BY name COLLATE NOCASE").all() as unknown as ClientRow[];
    return rows.map(toClient);
  }

  get(id: string): ClientProfile | undefined {
    const row = this.db.prepare("SELECT * FROM clients WHERE id = ?").get(id) as ClientRow | undefined;
    return row ? toClient(row) : undefined;
  }

  create(input: ClientInput & { name: string }): ClientProfile {
    const now = new Date().toISOString();
    const id = randomUUID();
    const { name, ...rest } = { ...DEFAULTS, ...stripUndefined(input) };
    this.db
      .prepare("INSERT INTO clients (id, name, settings, created_at, updated_at) VALUES (?, ?, ?, ?, ?)")
      .run(id, name, JSON.stringify(rest), now, now);
    return this.get(id)!;
  }

  /** Omitted fields keep their value; `whatsappApiSecret: undefined` keeps the saved secret. */
  update(id: string, patch: ClientInput): ClientProfile | undefined {
    const current = this.get(id);
    if (!current) return undefined;
    const { id: _id, createdAt: _c, updatedAt: _u, name, ...rest } = { ...current, ...stripUndefined(patch) };
    this.db
      .prepare("UPDATE clients SET name = ?, settings = ?, updated_at = ? WHERE id = ?")
      .run(name, JSON.stringify(rest), new Date().toISOString(), id);
    return this.get(id);
  }

  /** Deletes the client; its phones fall back to the global assistant. */
  remove(id: string): boolean {
    this.db.prepare("UPDATE devices SET client_id = NULL WHERE client_id = ?").run(id);
    return Number(this.db.prepare("DELETE FROM clients WHERE id = ?").run(id).changes) > 0;
  }

  assignDevice(deviceId: string, clientId: string | null): boolean {
    return Number(this.db.prepare("UPDATE devices SET client_id = ? WHERE id = ?").run(clientId, deviceId).changes) > 0;
  }

  deviceIds(clientId: string): string[] {
    const rows = this.db.prepare("SELECT id FROM devices WHERE client_id = ? ORDER BY registered_at").all(clientId) as {
      id: string;
    }[];
    return rows.map((r) => r.id);
  }

  /** The client a phone answers for, if any. */
  forDevice(deviceId: string): ClientProfile | undefined {
    const row = this.db
      .prepare("SELECT c.* FROM clients c JOIN devices d ON d.client_id = c.id WHERE d.id = ?")
      .get(deviceId) as ClientRow | undefined;
    return row ? toClient(row) : undefined;
  }

  /** Safe for the browser: the WhatsApp secret is reduced to whether it is set. */
  view(client: ClientProfile) {
    const { whatsappApiSecret, ...rest } = client;
    return {
      ...rest,
      deviceIds: this.deviceIds(client.id),
      whatsappApiSecret: { configured: Boolean(whatsappApiSecret), hint: whatsappApiSecret ? `…${whatsappApiSecret.slice(-4)}` : null },
    };
  }
}

function toClient(row: ClientRow): ClientProfile {
  return {
    ...DEFAULTS,
    ...(JSON.parse(row.settings) as Partial<ClientProfile>),
    id: row.id,
    name: row.name,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

function stripUndefined<T extends object>(value: T): T {
  return Object.fromEntries(Object.entries(value).filter(([, v]) => v !== undefined)) as T;
}
