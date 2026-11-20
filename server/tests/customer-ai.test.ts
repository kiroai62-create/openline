import { EventEmitter } from "node:events";
import request from "supertest";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createGatewayServer } from "../src/server.js";
import type { WaSocket, WaSocketFactory } from "../src/whatsapp/manager.js";

const API_KEY = "test-api-key";
const root = { "x-api-key": API_KEY };

class FakeSocket extends EventEmitter implements WaSocket {
  readonly sent: { jid: string; text: string }[] = [];
  user = { id: "919900000001:3@s.whatsapp.net", name: "Clinic" };
  ev = { on: (event: string, listener: (arg: any) => void) => void this.on(event, listener) };
  async sendMessage(jid: string, content: { text: string }) {
    this.sent.push({ jid, text: content.text });
    return { key: { id: `out-${this.sent.length}` } };
  }
  async sendPresenceUpdate() {}
  async readMessages() {}
  async logout() {}
  end() {}
  incoming(text: string) {
    this.emit("messages.upsert", {
      type: "notify",
      messages: [{ key: { remoteJid: "919812345678@s.whatsapp.net", id: `in-${Math.random()}`, fromMe: false }, pushName: "Asha", messageTimestamp: Math.floor(Date.now() / 1000), message: { conversation: text } }],
    });
  }
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

async function setup() {
  const sockets: FakeSocket[] = [];
  const factory: WaSocketFactory = async () => {
    const s = new FakeSocket();
    sockets.push(s);
    return s;
  };
  const gateway = createGatewayServer({ apiKey: API_KEY, whatsappSocketFactory: factory, whatsappDelayScale: 0 });
  const http = gateway.httpServer;
  const created = await request(http).post("/v1/customers").set(root).send({ businessName: "Care Clinic", email: "care@example.com", password: "care-password", lifetime: true });
  expect(created.status).toBe(201);
  const customer = request.agent(http);
  await customer.post("/v1/auth/login").send({ email: "care@example.com", password: "care-password" }).expect(200);
  return { gateway, http, customer, sockets, clientId: created.body.clientId as string, customerId: created.body.customer.id as string };
}

describe("customer's own AI providers", () => {
  it("saves Groq, Cloudflare and OpenRouter keys masked, keeps keys on edit, and blocks private URLs", async () => {
    const { customer } = await setup();
    const empty = (await customer.get("/v1/me/ai")).body;
    expect(empty.providers).toEqual([]);
    expect(Object.keys(empty.presets)).toEqual(expect.arrayContaining(["gemini", "groq", "cloudflare", "openrouter", "sarvam", "openai"]));

    const providers = [
      { id: "groq-llm", kind: "llm", type: "groq", label: "My Groq", enabled: true, apiKey: "gsk_customer_secret_1111", model: "llama-3.3-70b-versatile" },
      { id: "cf-llm", kind: "llm", type: "cloudflare", label: "My Cloudflare", enabled: true, apiKey: "cf_customer_token_2222", model: "@cf/meta/llama-3.3-70b-instruct-fp8-fast", accountId: "abc123" },
      { id: "or-llm", kind: "llm", type: "openrouter", label: "OpenRouter", enabled: false, apiKey: "sk-or-3333", model: "x" },
    ];
    const saved = await customer.put("/v1/me/ai").send({ providers });
    expect(saved.status).toBe(200);
    expect(JSON.stringify(saved.body)).not.toContain("gsk_customer_secret");
    expect(saved.body.providers[0]).toMatchObject({ id: "groq-llm", keySet: true, keyHint: "…1111" });

    // Editing without a key keeps the saved one.
    await customer.put("/v1/me/ai").send({ providers: providers.map(({ apiKey: _k, ...p }) => p) }).expect(200);
    const after = (await customer.get("/v1/me/ai")).body;
    expect(after.providers.map((p: { keySet: boolean }) => p.keySet)).toEqual([true, true, true]);
    expect(JSON.stringify(after)).not.toContain("cf_customer_token");

    const bad = await customer.put("/v1/me/ai").send({ providers: [{ id: "x", kind: "llm", type: "openai", label: "x", enabled: true, apiKey: "k", model: "m", baseUrl: "https://127.0.0.1/v1" }] });
    expect(bad.status).toBe(400);
  });

  it("answers the customer's WhatsApp with their own Groq key before the platform's", async () => {
    const { http, customer, sockets, gateway, clientId } = await setup();
    await request(http).put("/v1/ai/providers").set(root).send({ mode: "auto", providers: [{ id: "platform", kind: "llm", type: "groq", label: "Platform Groq", enabled: true, apiKey: "gsk_platform", model: "m" }] }).expect(200);
    await customer.put("/v1/me/ai").send({ providers: [{ id: "mine", kind: "llm", type: "groq", label: "My Groq", enabled: true, apiKey: "gsk_mine", model: "m" }] }).expect(200);

    const auths: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_url: string, init: RequestInit) => {
      auths.push(String((init.headers as Record<string, string>).authorization));
      return new Response(JSON.stringify({ choices: [{ message: { content: "Namaste!" } }] }), { status: 200, headers: { "content-type": "application/json" } });
    }));
    const pending = customer.post("/v1/me/whatsapp/connect").then((r) => r);
    await vi.waitFor(() => expect(sockets.length).toBe(1));
    sockets[0]!.emit("connection.update", { connection: "open" });
    await pending;
    sockets[0]!.incoming("Hello");
    await vi.waitFor(() => expect(sockets[0]!.sent).toHaveLength(1));
    await gateway.whatsapp.idle(clientId);
    expect(auths[0]).toBe("Bearer gsk_mine");
  });

  it("falls over to the platform when the customer's key fails, and tests a key on request", async () => {
    const { http, customer, sockets, gateway, clientId } = await setup();
    await request(http).put("/v1/ai/providers").set(root).send({ mode: "auto", providers: [{ id: "platform", kind: "llm", type: "groq", label: "Platform Groq", enabled: true, apiKey: "gsk_platform", model: "m" }] }).expect(200);
    await customer.put("/v1/me/ai").send({ providers: [{ id: "mine", kind: "llm", type: "groq", label: "My Groq", enabled: true, apiKey: "gsk_mine", model: "m" }] }).expect(200);

    const auths: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_url: string, init: RequestInit) => {
      const auth = String((init.headers as Record<string, string>).authorization);
      auths.push(auth);
      if (auth.includes("gsk_mine")) return new Response("rate limit", { status: 429 });
      return new Response(JSON.stringify({ choices: [{ message: { content: "ok" } }] }), { status: 200, headers: { "content-type": "application/json" } });
    }));
    const pending = customer.post("/v1/me/whatsapp/connect").then((r) => r);
    await vi.waitFor(() => expect(sockets.length).toBe(1));
    sockets[0]!.emit("connection.update", { connection: "open" });
    await pending;
    sockets[0]!.incoming("Hello");
    await vi.waitFor(() => expect(sockets[0]!.sent).toHaveLength(1));
    await gateway.whatsapp.idle(clientId);
    expect(auths).toEqual(["Bearer gsk_mine", "Bearer gsk_platform"]);

    const test = await customer.post("/v1/me/ai/mine/test");
    expect(test.body.ok).toBe(false);
    expect((await customer.post("/v1/me/ai/missing/test")).status).toBe(404);
    const status = (await customer.get("/v1/me/ai")).body.status;
    expect(status[0]).toMatchObject({ id: "mine", state: "cooldown" });
  });

  it("lets the platform admin read and edit a customer's providers, but not other customers", async () => {
    const { http, customerId, customer } = await setup();
    await request(http).put(`/v1/me/ai?customerId=${customerId}`).set(root).send({ providers: [{ id: "g", kind: "live", type: "gemini", label: "Gemini", enabled: true, apiKey: "AIza-customer-9999", model: "gemini-3.8-live" }] }).expect(200);
    expect((await customer.get("/v1/me/ai")).body.providers[0]).toMatchObject({ id: "g", keyHint: "…9999" });
    expect((await customer.get("/v1/ai/providers")).status).toBe(403);
  });
});

describe("admin: customer 360", () => {
  it("views the console as a customer and returns to the admin session without signing in again", async () => {
    const gateway = createGatewayServer({ apiKey: API_KEY });
    const http = gateway.httpServer;
    const admin = request.agent(http);
    await admin.post("/v1/auth/setup").send({ email: "admin@vaaniflow.in", password: "admin-password" }).expect(200);
    const created = await admin.post("/v1/customers").send({ businessName: "Care Clinic", email: "care@example.com", password: "care-password", lifetime: true });
    const id = created.body.customer.id as string;

    await admin.put(`/v1/me/business?customerId=${id}`).send({ about: "Open 10 to 7." }).expect(200);
    expect((await admin.get(`/v1/me/calls?customerId=${id}`)).body.calls).toEqual([]);
    expect((await admin.get(`/v1/audit?customerId=${id}`)).body.entries.map((e: { action: string }) => e.action)).toEqual(expect.arrayContaining(["customer.create", "business.update"]));

    await admin.post(`/v1/customers/${id}/login-as`).expect(200);
    const viewing = (await admin.get("/v1/auth/status")).body;
    expect(viewing).toMatchObject({ role: "customer", viewingAs: "care@example.com" });
    expect((await admin.get("/v1/customers")).status).toBe(403);

    await admin.post("/v1/auth/return").expect(200);
    const back = (await admin.get("/v1/auth/status")).body;
    expect(back).toMatchObject({ role: "admin", viewingAs: null });
    expect((await admin.get("/v1/customers")).status).toBe(200);
    expect((await admin.post("/v1/auth/return")).status).toBe(404);

    // A customer who signed in normally can't "return" to anything.
    const customer = request.agent(http);
    await customer.post("/v1/auth/login").send({ email: "care@example.com", password: "care-password" }).expect(200);
    expect((await customer.post("/v1/auth/return")).status).toBe(404);
  });
});
