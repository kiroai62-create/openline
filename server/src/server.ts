import { createServer, type Server as HttpServer } from "node:http";
import type { AddressInfo } from "node:net";
import { AgentManager } from "./agent/agent-manager.js";
import { createApp } from "./app.js";
import { AuthService } from "./auth.js";
import { ConfigStore } from "./config.js";
import { openDatabase, SettingsStore, type Database } from "./db.js";
import { DeviceWebSocketHub } from "./device-channel.js";
import { DeviceEventService } from "./events.js";
import { MediaRelay } from "./media-relay.js";
import { StateStore } from "./state.js";
import { StreamManager } from "./stream/stream-manager.js";
import { ClientStore } from "./clients.js";
import { Analytics } from "./analytics.js";
import { AiRouter } from "./ai/router.js";
import { FallbackAudio } from "./ai/fallback-audio.js";
import { SaasStore } from "./saas.js";
import { Billing } from "./billing.js";
import { HttpWebhookDispatcher, type WebhookDispatcher } from "./webhooks.js";

export interface GatewayServerOptions {
  /** Root API key from server/.env; also the one-time setup key for the console password. */
  apiKey: string;
  webhookSecret?: string;
  webhookDispatcher?: WebhookDispatcher;
  /** Defaults from server/.env; the console's Settings page overrides them. */
  defaultWebhookUrl?: string;
  geminiApiKey?: string;
  agentModel?: string;
  agentOwnerName?: string;
  publicUrl?: string;
  docsUrl?: string;
  whatsappGatewayUrl?: string;
  whatsappApiSecret?: string;
  razorpayKeyId?: string;
  razorpayKeySecret?: string;
  razorpayWebhookSecret?: string;
  razorpayApiBase?: string;
  /** One deployment serves several businesses (Clients page). Default: one deployment per client. */
  multiClient?: boolean;
  /** This deployment's client: seeds the receptionist on first start. */
  clientName?: string;
  bookingUrl?: string;
  /** Directory of the SQLite database. Without it everything is kept in memory (tests). */
  dataDir?: string;
}

export interface GatewayServer {
  httpServer: HttpServer;
  db: Database;
  state: StateStore;
  config: ConfigStore;
  auth: AuthService;
  deviceHub: DeviceWebSocketHub;
  events: DeviceEventService;
  media: MediaRelay;
  agents: AgentManager;
  streams: StreamManager;
  clients: ClientStore;
  router: AiRouter;
  fallback: FallbackAudio;
  saas: SaasStore;
  billing: Billing;
}

export function createGatewayServer(options: GatewayServerOptions): GatewayServer {
  const db = openDatabase(options.dataDir);
  const settings = new SettingsStore(db);
  const config = new ConfigStore(settings, {
    geminiApiKey: options.geminiApiKey,
    geminiModel: options.agentModel,
    publicUrl: options.publicUrl,
    webhookUrl: options.defaultWebhookUrl,
    docsUrl: options.docsUrl,
    whatsappGatewayUrl: options.whatsappGatewayUrl,
    whatsappApiSecret: options.whatsappApiSecret,
  });
  const auth = new AuthService(db, options.apiKey);
  const state = new StateStore(db);
  const dispatcher =
    options.webhookDispatcher ?? new HttpWebhookDispatcher(options.webhookSecret ?? options.apiKey);
  const events = new DeviceEventService(state, dispatcher, () => config.get("webhookUrl"), db);
  const deviceHub = new DeviceWebSocketHub(state, (deviceId, event) => events.ingest(deviceId, event));
  const media = new MediaRelay(state, auth);
  let httpServer: HttpServer | undefined;
  const mediaUrl = (callId: string) => {
    const { port } = httpServer!.address() as AddressInfo;
    return `ws://127.0.0.1:${port}/v1/calls/${encodeURIComponent(callId)}/media`;
  };
  const streams = new StreamManager({ state, deviceChannel: deviceHub, config, apiKey: options.apiKey, mediaUrl });
  const clients = new ClientStore(db);
  const saas = new SaasStore(db);
  if (options.dataDir) saas.seedPlans();
  const billing = new Billing(saas, config, {
    keyId: options.razorpayKeyId,
    keySecret: options.razorpayKeySecret,
    webhookSecret: options.razorpayWebhookSecret,
    apiBase: options.razorpayApiBase ?? "https://api.razorpay.com",
  });
  const router = new AiRouter(settings, config);
  // First start of a per-client deployment: the receptionist answers for CLIENT_NAME.
  if (options.clientName && !settings.get("agent")) {
    settings.set("agent", {
      mode: "receptionist",
      autoAnswer: true,
      businessName: options.clientName,
      ownerName: options.clientName,
      bookingUrl: options.bookingUrl ?? "",
    });
    if (!config.get("brandName")) config.update({ brandName: options.clientName });
  }
  const fallback = new FallbackAudio(settings, router);
  // Record the "please hold" / "call later" phrases while providers work, and keep them fresh.
  if (options.dataDir) {
    setTimeout(() => void fallback.refresh(), 3_000).unref();
    setInterval(() => void fallback.refresh(), 6 * 60 * 60_000).unref();
  }
  const agents = new AgentManager({
    state,
    deviceChannel: deviceHub,
    apiKey: options.apiKey,
    db,
    settings,
    config,
    ownerName: options.agentOwnerName,
    mediaUrl,
    clients,
    router,
    fallback,
    entitled: (clientId) => {
      if (!clientId) return { ok: true };
      const customer = saas.customerByClient(clientId);
      if (!customer) return { ok: true };
      const ent = saas.entitlement(customer);
      return { ok: ent.entitled, message: ent.message };
    },
  });
  events.onCallEvent = (call, event) => {
    const streamed = streams.onCallEvent(call, event);
    agents.onCallEvent(state.getCall(call.id) ?? call, event, streamed);
    // Central SaaS: bill a connected call's minutes to its customer, once, when it ends.
    const latest = state.getCall(call.id) ?? call;
    const ended = latest.state === "ended" || latest.state === "rejected" || latest.state === "failed";
    if (ended && latest.answeredAt && latest.endedAt) {
      const client = clients.forDevice(latest.deviceId);
      const customer = client ? saas.customerByClient(client.id) : undefined;
      // Only calls the AI actually talked on count (staff-answered calls are free).
      if (customer && agents.transcript(latest.id).length > 0) {
        const seconds = Math.max(0, (Date.parse(latest.endedAt) - Date.parse(latest.answeredAt)) / 1000);
        if (seconds > 0) saas.addCallUsage(customer.id, latest.id, seconds);
      }
    }
  };
  const app = createApp({ auth, config, state, deviceChannel: deviceHub, events, media, agents, streams, clients, analytics: new Analytics(db), router, fallback, saas, billing, multiClient: options.multiClient || clients.list().length > 0 });
  httpServer = createServer(app);
  deviceHub.attach(httpServer);
  media.attach(httpServer);
  return { httpServer, db, state, config, auth, deviceHub, events, media, agents, streams, clients, router, fallback, saas, billing };
}
