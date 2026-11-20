import { EventEmitter } from "node:events";
import request from "supertest";
import { afterEach, describe, expect, it, vi } from "vitest";
import { aiPolicy } from "../src/agent/agent-manager.js";
import { createGatewayServer } from "../src/server.js";
import type { WaSocket, WaSocketFactory } from "../src/whatsapp/manager.js";
import { historyMessages, parseReply } from "../src/whatsapp/prompt.js";

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
  incoming(text: string, opts: { from?: string; id?: string; fromMe?: boolean; name?: string } = {}) {
    this.emit("messages.upsert", {
      type: "notify",
      messages: [
        {
          key: { remoteJid: `${opts.from ?? "919812345678"}@s.whatsapp.net`, id: opts.id ?? `in-${Math.random()}`, fromMe: opts.fromMe ?? false },
          pushName: opts.name ?? "Asha",
          messageTimestamp: Math.floor(Date.now() / 1000),
          message: { conversation: text },
        },
      ],
    });
  }
}

function fakeWhatsApp() {
  const sockets: FakeSocket[] = [];
  const factory: WaSocketFactory = async () => {
    const socket = new FakeSocket();
    sockets.push(socket);
    return socket;
  };
  return { sockets, factory };
}

/** Answers every chat completion with the given text (and records the prompts). */
function stubChat(reply: string) {
  const prompts: unknown[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_url: string, init: RequestInit) => {
      prompts.push(JSON.parse(String(init.body)));
      return new Response(JSON.stringify({ choices: [{ message: { content: reply } }] }), { status: 200, headers: { "content-type": "application/json" } });
    }),
  );
  return prompts;
}

const groq = { mode: "auto", providers: [{ id: "groq-1", kind: "llm", type: "groq", label: "Groq", enabled: true, apiKey: "gsk_test_key", model: "llama-3.3-70b-versatile" }] };

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("built-in WhatsApp automation", () => {
  it("links with a QR, answers customers with the AI and keeps the whole chat in the inbox", async () => {
    const wa = fakeWhatsApp();
    const gateway = createGatewayServer({ apiKey: API_KEY, whatsappSocketFactory: wa.factory, whatsappDelayScale: 0 });
    const http = gateway.httpServer;
    await request(http).put("/v1/agent").set(root).send({ mode: "receptionist", businessName: "Smile Dental", about: "Open 10 am to 7 pm, Monday to Saturday.", bookingUrl: "https://smile.example/book" }).expect(200);
    await request(http).put("/v1/ai/providers").set(root).send(groq).expect(200);

    expect((await request(http).get("/v1/whatsapp/status").set(root)).body).toMatchObject({ state: "logged_out", connected: false });
    const connecting = request(http).post("/v1/whatsapp/connect").set(root);
    const pending = connecting.then((r) => r);
    await vi.waitFor(() => expect(wa.sockets.length).toBe(1));
    wa.sockets[0]!.emit("connection.update", { qr: "2@fake-qr-payload" });
    const linked = await pending;
    expect(linked.body.state).toBe("qr");
    await vi.waitFor(async () => expect((await request(http).get("/v1/whatsapp/status").set(root)).body.qrSvg).toContain("<svg"));

    wa.sockets[0]!.emit("connection.update", { connection: "open" });
    const status = (await request(http).get("/v1/whatsapp/status").set(root)).body;
    expect(status).toMatchObject({ state: "connected", connected: true, me: { number: "919900000001" }, settings: { autoReply: true } });

    const prompts = stubChat("Namaste Asha! Hum Monday se Saturday 10 se 7 baje tak khule hain.");
    wa.sockets[0]!.incoming("Clinic kab khulta hai?");
    await vi.waitFor(() => expect(wa.sockets[0]!.sent).toHaveLength(1));
    expect(wa.sockets[0]!.sent[0]).toEqual({ jid: "919812345678@s.whatsapp.net", text: expect.stringContaining("10 se 7") });
    const system = (prompts[0] as { messages: { role: string; content: string }[] }).messages[0]!;
    expect(system.role).toBe("system");
    expect(system.content).toContain("Smile Dental");
    expect(system.content).toContain("https://smile.example/book");

    const list = (await request(http).get("/v1/whatsapp/chats").set(root)).body;
    expect(list.counts).toMatchObject({ all: 1, unread: 1 });
    expect(list.chats[0]).toMatchObject({ name: "Asha", phone: "919812345678", aiEnabled: true, preview: expect.stringContaining("10 se 7"), previewSource: "ai" });
    const chat = (await request(http).get(`/v1/whatsapp/chats/${list.chats[0].id}`).set(root)).body;
    expect(chat.messages.map((m: { source: string }) => m.source)).toEqual(["patient", "ai"]);
    expect(chat.messages[1]).toMatchObject({ status: "sent", provider: "Groq" });
    expect(chat.chat.unread).toBe(0);

    // The same message delivered twice (reconnect) is stored and answered once.
    wa.sockets[0]!.incoming("Duplicate?", { id: "dup-1" });
    wa.sockets[0]!.incoming("Duplicate?", { id: "dup-1" });
    await vi.waitFor(() => expect(wa.sockets[0]!.sent).toHaveLength(2));
    await gateway.whatsapp.idle("default");
    expect(wa.sockets[0]!.sent).toHaveLength(2);

    // The AI asks for a person: the chat is flagged for staff and the AI stays quiet after that.
    stubChat("[[HANDOFF]] Main team ko bata rahi hoon, woh jaldi reply karenge.");
    wa.sockets[0]!.incoming("Mujhe kisi insaan se baat karni hai");
    await vi.waitFor(() => expect(wa.sockets[0]!.sent).toHaveLength(3));
    expect(wa.sockets[0]!.sent[2]!.text).toBe("Main team ko bata rahi hoon, woh jaldi reply karenge.");
    await gateway.whatsapp.idle("default");
    expect((await request(http).get("/v1/whatsapp/chats?filter=human").set(root)).body.chats).toHaveLength(1);
    wa.sockets[0]!.incoming("Hello?");
    await gateway.whatsapp.idle("default");
    expect(wa.sockets[0]!.sent).toHaveLength(3);

    // Staff replies from the console; that clears the flag.
    const id = list.chats[0].id;
    const staff = await request(http).post(`/v1/whatsapp/chats/${id}/messages`).set(root).send({ text: "Ji, main Dr. Rao bol raha hoon." });
    expect(staff.status).toBe(201);
    expect(staff.body.message).toMatchObject({ source: "staff", status: "sent" });
    expect(staff.body.contact.needsHuman).toBe(false);

    // A reply typed on the business phone pauses the AI for that chat.
    wa.sockets[0]!.incoming("Main aapko kal call karunga", { fromMe: true, id: "phone-1" });
    await gateway.whatsapp.idle("default");
    const paused = (await request(http).get(`/v1/whatsapp/chats/${id}`).set(root)).body;
    expect(paused.chat.aiEnabled).toBe(false);
    expect(paused.messages.at(-1)).toMatchObject({ source: "phone", direction: "out" });

    // Resume the AI from the inbox.
    expect((await request(http).put(`/v1/whatsapp/chats/${id}`).set(root).send({ aiEnabled: true })).body.chat).toMatchObject({ aiEnabled: true, needsHuman: false });

    // Settings are validated and saved per business.
    expect((await request(http).put("/v1/whatsapp/settings").set(root).send({ replyDelayMinMs: -1 })).status).toBe(400);
    const saved = await request(http).put("/v1/whatsapp/settings").set(root).send({ autoReply: false, quickReplies: ["Namaste!"], fallbackMessage: "Team jaldi reply karegi." });
    expect(saved.body.settings).toMatchObject({ autoReply: false, quickReplies: ["Namaste!"] });

    // New chat to a number typed in the console.
    const fresh = await request(http).post("/v1/whatsapp/send").set(root).send({ to: "98765 43210", text: "Your appointment is confirmed." });
    expect(fresh.status).toBe(201);
    expect(wa.sockets[0]!.sent.at(-1)).toEqual({ jid: "919876543210@s.whatsapp.net", text: "Your appointment is confirmed." });

    await request(http).post("/v1/whatsapp/logout").set(root).expect(200);
    expect((await request(http).get("/v1/whatsapp/status").set(root)).body.connected).toBe(false);
    const offline = await request(http).post(`/v1/whatsapp/chats/${id}/messages`).set(root).send({ text: "hi" });
    expect(offline.status).toBe(409);
  });

  it("sends a fallback once and flags the chat when no AI can answer", async () => {
    const wa = fakeWhatsApp();
    const gateway = createGatewayServer({ apiKey: API_KEY, whatsappSocketFactory: wa.factory, whatsappDelayScale: 0 });
    const http = gateway.httpServer;
    await request(http).put("/v1/ai/providers").set(root).send(groq).expect(200);
    await request(http).put("/v1/whatsapp/settings").set(root).send({ fallbackMessage: "Abhi system busy hai, team jaldi reply karegi." }).expect(200);
    const pending = request(http).post("/v1/whatsapp/connect").set(root).then((r) => r);
    await vi.waitFor(() => expect(wa.sockets.length).toBe(1));
    wa.sockets[0]!.emit("connection.update", { connection: "open" });
    await pending;
    vi.stubGlobal("fetch", vi.fn(async () => new Response("quota exceeded", { status: 429 })));

    wa.sockets[0]!.incoming("Hello");
    await vi.waitFor(() => expect(wa.sockets[0]!.sent).toHaveLength(1));
    expect(wa.sockets[0]!.sent[0]!.text).toContain("system busy");
    await gateway.whatsapp.idle("default");
    const chats = (await request(http).get("/v1/whatsapp/chats").set(root)).body.chats;
    expect(chats[0].needsHuman).toBe(true);
  });

  it("keeps each customer inside their own WhatsApp", async () => {
    const wa = fakeWhatsApp();
    const gateway = createGatewayServer({ apiKey: API_KEY, whatsappSocketFactory: wa.factory, whatsappDelayScale: 0 });
    const http = gateway.httpServer;
    const created = await request(http).post("/v1/customers").set(root).send({ businessName: "Care Clinic", email: "care@example.com", password: "care-password", lifetime: true });
    expect(created.status).toBe(201);
    const clientId = created.body.clientId as string;

    const customer = request.agent(http);
    await customer.post("/v1/auth/login").send({ email: "care@example.com", password: "care-password" }).expect(200);
    expect((await customer.get("/v1/me/whatsapp/status")).status).toBe(200);
    expect((await customer.get("/v1/whatsapp/status")).status).toBe(403);
    await customer.put("/v1/me/whatsapp/settings").send({ instructions: "Never quote prices." }).expect(200);
    expect(gateway.whatsapp.settings(clientId).instructions).toBe("Never quote prices.");
    expect(gateway.whatsapp.settings("default").instructions).toBe("");
    // The platform admin reaches the customer's inbox with ?clientId=.
    expect((await request(http).get(`/v1/whatsapp/settings?clientId=${clientId}`).set(root)).body.settings.instructions).toBe("Never quote prices.");
    expect((await request(http).get("/v1/whatsapp/settings?clientId=nope").set(root)).status).toBe(404);
  });
});

describe("helpers", () => {
  it("parses the phone's AI policy for incoming calls", () => {
    expect(aiPolicy(undefined)).toEqual({ mode: "now" });
    expect(aiPolicy({ ai: "never" })).toEqual({ mode: "never" });
    expect(aiPolicy({ ai: "after:20" })).toEqual({ mode: "after", seconds: 20 });
    expect(aiPolicy({ ai: "after:900" })).toEqual({ mode: "after", seconds: 120 });
    expect(aiPolicy({ ai: "garbage" })).toEqual({ mode: "now" });
  });

  it("builds chat history ending with the customer and strips the hand-off marker", () => {
    const base = { contactId: 1, status: "sent" as const, error: null, waId: null, sentBy: null, provider: null, latencyMs: null, createdAt: "" };
    const history = historyMessages([
      { ...base, id: 1, direction: "in", source: "patient", body: "Hi" },
      { ...base, id: 2, direction: "in", source: "patient", body: "Timings?" },
      { ...base, id: 3, direction: "out", source: "ai", body: "10 to 7" },
    ]);
    expect(history).toEqual([{ role: "user", content: "Hi\nTimings?" }]);
    expect(parseReply("[[HANDOFF]] Team will reply")).toEqual({ text: "Team will reply", handoff: true });
  });
});

describe("hybrid calls: the phone decides when the AI may answer", () => {
  it("never answers personal calls and waits for the ring delay before answering", async () => {
    vi.useFakeTimers();
    const gateway = createGatewayServer({ apiKey: API_KEY, geminiApiKey: "AIzaSyTest-key-123456" });
    gateway.agents.updateSettings({ autoAnswer: true });
    gateway.state.upsertDevice({ id: "p1", name: "Phone" });
    const send = vi.spyOn(gateway.deviceHub, "send").mockImplementation(() => ({ commandId: "c", callId: "x", action: "answer" }) as never);
    const ring = async (callId: string, ai?: string) =>
      gateway.events.ingest("p1", {
        type: "event", eventId: `e-${callId}`, callId, event: "call.ringing", timestamp: new Date().toISOString(),
        payload: { number: "+919812345678", direction: "incoming", ...(ai ? { ai } : {}) },
      });

    await ring("personal", "never");
    await vi.advanceTimersByTimeAsync(5_000);
    expect(send).not.toHaveBeenCalled();

    await ring("business", "after:15");
    await vi.advanceTimersByTimeAsync(10_000);
    expect(send).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(6_000);
    expect(send).toHaveBeenCalledWith("p1", { action: "answer", callId: "business" });

    // Picked up on the phone before the delay: the AI stays out.
    send.mockClear();
    await ring("picked", "after:15");
    await gateway.events.ingest("p1", { type: "event", eventId: "e-picked-active", callId: "picked", event: "call.active", timestamp: new Date().toISOString(), payload: {} });
    await vi.advanceTimersByTimeAsync(20_000);
    expect(send).not.toHaveBeenCalled();
  });
});

describe("parity with the original gateway", () => {
  it("defaults to a 5-8 s wait and the 'system busy' fallback, labels media in Hinglish and logs events", async () => {
    const { DEFAULT_WA_SETTINGS, textOf, detectLang } = await import("../src/whatsapp/manager.js");
    expect(DEFAULT_WA_SETTINGS.replyDelayMinMs).toBe(5000);
    expect(DEFAULT_WA_SETTINGS.replyDelayMaxMs).toBe(8000);
    expect(DEFAULT_WA_SETTINGS.fallbackMessage).toContain("system busy");
    expect(textOf({ imageMessage: {} })).toBe("[Photo bheji]");
    expect(textOf({ audioMessage: {} })).toBe("[Voice message bheja]");
    expect(textOf({ locationMessage: {} })).toBe("[Location bheji]");
    expect(detectLang("Clinic kal kitne baje khulega?")).toBe("hinglish");
    expect(detectLang("क्लिनिक कब खुलेगा")).toBe("hi");
    expect(detectLang("What time do you open tomorrow?")).toBe("en");

    const wa = fakeWhatsApp();
    const gateway = createGatewayServer({ apiKey: API_KEY, whatsappSocketFactory: wa.factory, whatsappDelayScale: 0 });
    const http = gateway.httpServer;
    await request(http).put("/v1/ai/providers").set(root).send(groq).expect(200);
    const pending = request(http).post("/v1/whatsapp/connect").set(root).then((r) => r);
    await vi.waitFor(() => expect(wa.sockets.length).toBe(1));
    wa.sockets[0]!.emit("connection.update", { qr: "2@qr" });
    wa.sockets[0]!.emit("connection.update", { connection: "open" });
    await pending;

    // The AI is down: the default fallback goes out and the chat is flagged for staff.
    vi.stubGlobal("fetch", vi.fn(async () => new Response("quota exceeded", { status: 429 })));
    wa.sockets[0]!.incoming("Clinic kal kitne baje khulega?");
    await vi.waitFor(() => expect(wa.sockets[0]!.sent).toHaveLength(1));
    expect(wa.sockets[0]!.sent[0]!.text).toContain("Dhanyavaad! Abhi system busy hai");
    await gateway.whatsapp.idle("default");
    const chat = (await request(http).get("/v1/whatsapp/chats").set(root)).body.chats[0];
    expect(chat).toMatchObject({ needsHuman: true, lang: "hinglish" });

    // Tokens are recorded per AI reply.
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ choices: [{ message: { content: "Kal 10 baje." } }], usage: { total_tokens: 321 } }), { status: 200, headers: { "content-type": "application/json" } })));
    await request(http).put(`/v1/whatsapp/chats/${chat.id}`).set(root).send({ aiEnabled: true }).expect(200);
    wa.sockets[0]!.incoming("Aur Sunday?");
    await vi.waitFor(() => expect(wa.sockets[0]!.sent).toHaveLength(2));
    await gateway.whatsapp.idle("default");
    const status = (await request(http).get("/v1/whatsapp/status").set(root)).body;
    expect(status.stats.tokens).toBe(321);
    expect(status.live).toMatchObject({ received: 2, replied: 2 });

    // Unlinked from the phone (401): like the original, a fresh connection opens with a new QR.
    wa.sockets[0]!.emit("connection.update", { connection: "close", lastDisconnect: { error: { message: "logged out", output: { statusCode: 401 } } } });
    await vi.waitFor(() => expect(wa.sockets.length).toBe(2), { timeout: 4000 });
    const events = (await request(http).get("/v1/whatsapp/events").set(root)).body.events.map((e: { type: string }) => e.type);
    expect(events).toEqual(expect.arrayContaining(["qr", "connected", "disconnected"]));
  });
});
