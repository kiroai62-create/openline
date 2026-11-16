import { randomBytes } from "node:crypto";
import QRCode from "qrcode";
import { fileURLToPath } from "node:url";
import express, { type Request, type Response } from "express";
import type { AuthService } from "./auth.js";
import type { ConfigStore } from "./config.js";
import type { DeviceCommandChannel } from "./device-channel.js";
import { DeviceOfflineError } from "./device-channel.js";
import type { DeviceEventService } from "./events.js";
import {
  agentSettingsSchema,
  changePasswordSchema,
  configSchema,
  createApiKeySchema,
  createUserSchema,
  listCallsQuerySchema,
  loginSchema,
  setupSchema,
  streamSchema,
  pairingRequestSchema,
  pairSchema,
  callActionSchema,
  createCallSchema,
  deviceEventSchema,
  dtmfBodySchema,
  registerDeviceSchema,
  whatsappTestSchema,
  createClientSchema,
  analyticsQuerySchema,
  routingSchema,
  fallbackTextSchema,
  planSchema,
  createCustomerSchema,
  updateCustomerSchema,
  licenseActivateSchema,
  customerBusinessSchema,
  checkoutSchema,
  updateClientSchema,
} from "./schemas.js";
import type { AgentManager } from "./agent/agent-manager.js";
import type { StreamManager } from "./stream/stream-manager.js";
import { MEDIA_FORMAT } from "./media-relay.js";
import { PairingService, pairUri } from "./pairing.js";
import type { StateStore } from "./state.js";
import { renderTemplate, sendWhatsApp, toWhatsAppNumber, whatsappFromClient, whatsappFromConfig, type WhatsAppTarget } from "./agent/whatsapp.js";
import type { SaasStore } from "./saas.js";
import type { Billing } from "./billing.js";
import type { ClientStore } from "./clients.js";
import { Analytics } from "./analytics.js";
import { PRESETS, type AiRouter } from "./ai/router.js";
import type { FallbackAudio } from "./ai/fallback-audio.js";

export interface AppDependencies {
  auth: AuthService;
  config: ConfigStore;
  state: StateStore;
  deviceChannel: DeviceCommandChannel;
  events: DeviceEventService;
  media?: { isStreaming(callId: string): boolean };
  agents?: AgentManager;
  streams?: StreamManager;
  pairing?: PairingService;
  clients?: ClientStore;
  analytics?: Analytics;
  router?: AiRouter;
  fallback?: FallbackAudio;
  saas?: SaasStore;
  billing?: Billing;
  /** Show the Clients page (several businesses on one server). Off for one deployment per client. */
  multiClient?: boolean;
}

export function createApp(deps: AppDependencies) {
  const mediaInfo = (callId: string) => ({
    path: `/v1/calls/${encodeURIComponent(callId)}/media`,
    format: MEDIA_FORMAT,
    streaming: deps.media?.isStreaming(callId) ?? false,
  });

  const pairing = deps.pairing ?? new PairingService();
  const app = express();
  app.disable("x-powered-by");
  // Behind Tailscale Funnel / Cloudflare Tunnel: honour X-Forwarded-Proto/Host for URLs we hand out.
  app.set("trust proxy", true);
  // Razorpay webhook needs the raw body for signature checking, so it is mounted before the JSON
  // parser and before the auth middleware (Razorpay signs, there is no session).
  app.post("/v1/billing/webhook", express.raw({ type: "*/*", limit: "1mb" }), (request, response) => {
    if (!deps.billing) return response.status(404).end();
    const result = deps.billing.webhook(request.body as Buffer, request.header("x-razorpay-signature"));
    if (!result.ok) return response.status(400).json(errorBody("BAD_SIGNATURE", "invalid webhook signature"));
    return response.json({ ok: true, handled: result.handled });
  });

  app.use(express.json({ limit: "64kb" }));

  // Web console (home, playground, connection, settings). The page is public; it signs in with the
  // console password and then uses a session cookie for the API.
  const consolePage = fileURLToPath(new URL("../public/console.html", import.meta.url));
  app.get(["/", "/console"], (_request, response) => response.sendFile(consolePage));

  app.get("/health", (_request, response) => {
    response.json({ ok: true, service: "openline-server" });
  });

  // REST fallback for device events. Device credentials are separate from the public API key.
  app.post("/v1/devices/:deviceId/events", async (request, response) => {
    const deviceId = request.params.deviceId;
    if (!deps.state.verifyDeviceToken(deviceId, request.header("x-device-token"))) {
      return response.status(401).json(errorBody("UNAUTHORIZED", "invalid device token"));
    }

    const parsed = deviceEventSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);

    try {
      await deps.events.ingest(deviceId, parsed.data);
      return response.status(202).json({ accepted: true, eventId: parsed.data.eventId });
    } catch (error) {
      return response.status(409).json(
        errorBody("EVENT_REJECTED", error instanceof Error ? error.message : "event rejected"),
      );
    }
  });

  // Phone side of QR pairing: exchanges a one-time code for device credentials (no API key).
  app.post("/v1/pair", (request, response) => {
    const parsed = pairSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    const result = pairing.consume(parsed.data.code);
    if (result === "throttled") {
      return response.status(429).json(errorBody("TOO_MANY_ATTEMPTS", "too many invalid codes, wait a minute"));
    }
    if (result === "invalid") {
      return response.status(401).json(errorBody("INVALID_CODE", "pairing code is invalid or expired"));
    }
    const deviceId = `phone-${randomBytes(4).toString("hex")}`;
    const device = deps.state.upsertDevice({ id: deviceId, name: parsed.data.name ?? "Android phone" });
    if (result.clientId) deps.clients?.assignDevice(deviceId, result.clientId);
    const deviceToken = randomBytes(32).toString("base64url");
    deps.state.setDeviceToken(deviceId, deviceToken);
    return response.status(201).json({ device, deviceToken, deviceChannelPath: "/v1/device-channel" });
  });

  // ---- console sign-in ----
  app.get("/v1/auth/status", (request, response) => {
    const setupRequired = deps.auth.setupRequired;
    const user = deps.auth.sessionUser(deps.auth.sessionToken(request));
    response.json({
      brandName: deps.config.get("brandName") ?? null,
      setupRequired,
      // Creating the first account through a tunnel also needs the setup key from server/.env.
      setupKeyRequired: setupRequired && !deps.auth.isLocalRequest(request),
      authenticated: Boolean(user) || deps.auth.isAuthorized(request),
      user: user ?? null,
      role: user?.role ?? (deps.auth.isAuthorized(request) ? "admin" : null),
    });
  });

  // Public pricing page data (no sign-in). Only active, public plans.
  app.get("/v1/pricing", (_request, response) => {
    if (!deps.saas) return response.json({ plans: [] });
    return response.json({ plans: deps.saas.listPlans(false), brandName: deps.config.get("brandName") ?? null });
  });

  // The phone app checks its license here (no session; the key is the credential).
  app.post("/v1/license/activate", (request, response) => {
    if (!deps.saas) return response.status(404).json(errorBody("NOT_FOUND", "licensing unavailable"));
    const parsed = licenseActivateSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    const touched = deps.saas.touchLicense(parsed.data.key.trim().toUpperCase(), parsed.data.device);
    if (!touched) return response.status(404).json(errorBody("INVALID_LICENSE", "license not found"));
    const { customer } = touched;
    const ent = deps.saas.entitlement(customer);
    return response.json({
      valid: ent.entitled,
      customerName: customer.name,
      status: customer.status,
      lifetime: customer.lifetime,
      plan: customer.planId ?? null,
      expiresAt: customer.lifetime ? null : customer.status === "trialing" ? customer.trialEndsAt : customer.currentPeriodEnd,
      reason: ent.reason ?? null,
      message: ent.message ?? null,
    });
  });

  app.post("/v1/auth/setup", (request, response) => {
    const parsed = setupSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    const result = deps.auth.setup(parsed.data, deps.auth.isLocalRequest(request));
    if (result === "already_set") return response.status(409).json(errorBody("ALREADY_SET", "an account already exists, sign in"));
    if (result === "invalid_key") {
      return response.status(401).json(errorBody("INVALID_SETUP_KEY", "setup key does not match HTTP_GATEWAY_API_KEY"));
    }
    return signIn(request, response, parsed.data.email, parsed.data.password);
  });

  app.post("/v1/auth/login", (request, response) => {
    const parsed = loginSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    return signIn(request, response, parsed.data.email, parsed.data.password);
  });

  app.post("/v1/auth/logout", (request, response) => {
    deps.auth.logout(deps.auth.sessionToken(request));
    deps.auth.clearSessionCookie(response);
    response.status(204).end();
  });

  function signIn(request: Request, response: Response, email: string, password: string) {
    const result = deps.auth.login(email, password);
    if (result === "throttled") return response.status(429).json(errorBody("TOO_MANY_ATTEMPTS", "too many attempts, wait a minute"));
    if (result === "invalid") return response.status(401).json(errorBody("INVALID_LOGIN", "wrong email or password"));
    deps.auth.setSessionCookie(request, response, result.token, result.expiresAt);
    return response.json({ authenticated: true });
  }

  app.use("/v1", deps.auth.middleware());

  // ---- SaaS: platform admin and customer self-service ----
  const saas = deps.saas;
  const actingUser = (request: Request) => deps.auth.sessionUser(deps.auth.sessionToken(request));
  const isAdmin = (request: Request) => {
    const u = actingUser(request);
    // A root API key (no session) counts as admin; a customer session does not.
    return u ? u.role === "admin" : deps.auth.isAuthorized(request);
  };
  const adminActor = (request: Request) => actingUser(request)?.email ?? "admin";

  function requireAdmin(request: Request, response: Response): boolean {
    if (isAdmin(request)) return true;
    response.status(403).json(errorBody("FORBIDDEN", "admin only"));
    return false;
  }

  /** The customer whose data the current session may touch, or undefined for admins/root. */
  function sessionCustomer(request: Request) {
    const u = actingUser(request);
    return u?.role === "customer" && u.customerId ? saas?.getCustomer(u.customerId) : undefined;
  }

  if (saas) {
    // Full customer view (account, plan, license, usage, entitlement). Admin passes ?customerId.
    const resolveCustomer = (request: Request) => {
      const own = sessionCustomer(request);
      if (own) return own;
      const id = typeof request.query.customerId === "string" ? request.query.customerId : undefined;
      return isAdmin(request) && id ? saas.getCustomer(id) : undefined;
    };

    const customerView = (customerId: string) => {
      const c = saas.getCustomer(customerId);
      if (!c) return undefined;
      const plan = c.planId ? saas.getPlan(c.planId) : null;
      const license = saas.licenseFor(c.id);
      const ent = saas.entitlement(c);
      const login = deps.auth.userByCustomer(c.id);
      const client = c.clientId && deps.clients ? deps.clients.get(c.clientId) : undefined;
      return {
        customer: c,
        plan,
        license,
        entitlement: ent,
        email: login?.email ?? null,
        clientId: c.clientId ?? null,
        phones: c.clientId && deps.clients ? deps.clients.deviceIds(c.clientId) : [],
        business: client ? deps.clients!.view(client) : null,
        usage: saas.usageHistory(c.id),
      };
    };

    // --- admin: plans ---
    app.get("/v1/plans", (request, response) => {
      // Any signed-in user may read plans (customer upgrade screen); only admins see hidden ones.
      return response.json({ plans: saas.listPlans(isAdmin(request)) });
    });

    app.post("/v1/plans", (request, response) => {
      if (!requireAdmin(request, response)) return;
      const parsed = planSchema.safeParse(request.body);
      if (!parsed.success) return validationError(response, parsed.error.issues);
      const plan = saas.upsertPlan(parsed.data);
      saas.audit(adminActor(request), "plan.save", plan.id, plan.name);
      return response.status(201).json({ plan });
    });

    app.delete("/v1/plans/:id", (request, response) => {
      if (!requireAdmin(request, response)) return;
      const removed = saas.deletePlan(request.params.id);
      saas.audit(adminActor(request), removed ? "plan.delete" : "plan.hide", request.params.id);
      return response.json({ deleted: removed });
    });

    // --- admin: customers ---
    app.get("/v1/customers", (request, response) => {
      if (!requireAdmin(request, response)) return;
      const customers = saas.listCustomers().map((c) => {
        const plan = c.planId ? saas.getPlan(c.planId) : undefined;
        const ent = saas.entitlement(c);
        return {
          ...c,
          planName: plan?.name ?? null,
          email: deps.auth.userByCustomer(c.id)?.email ?? null,
          license: saas.licenseFor(c.id)?.key ?? null,
          licenseStatus: saas.licenseFor(c.id)?.status ?? null,
          entitled: ent.entitled,
          minutesUsed: ent.minutesUsed,
          minutesIncluded: ent.minutesIncluded,
          phones: c.clientId && deps.clients ? deps.clients.deviceIds(c.clientId).length : 0,
        };
      });
      return response.json({ customers });
    });

    app.post("/v1/customers", (request, response) => {
      if (!requireAdmin(request, response)) return;
      if (!deps.clients) return response.status(404).json(errorBody("NOT_FOUND", "clients unavailable"));
      const parsed = createCustomerSchema.safeParse(request.body);
      if (!parsed.success) return validationError(response, parsed.error.issues);
      const data = parsed.data;
      const client = deps.clients.create({ name: data.businessName, voice: "Kore" });
      const customer = saas.createCustomer({
        name: data.businessName, clientId: client.id, planId: data.planId,
        billingCycle: data.billingCycle, lifetime: data.lifetime, notes: data.notes,
      });
      const login = deps.auth.addUser(data.email, data.password, "customer", customer.id);
      if (login === "exists") {
        saas.deleteCustomer(customer.id);
        deps.clients.remove(client.id);
        return response.status(409).json(errorBody("EMAIL_EXISTS", "an account with this email already exists"));
      }
      if (data.geminiApiKey) deps.clients.update(client.id, { geminiApiKey: data.geminiApiKey });
      saas.audit(adminActor(request), "customer.create", customer.id, `${data.businessName} <${data.email}>`);
      return response.status(201).json(customerView(customer.id));
    });

    app.get("/v1/customers/:id", (request, response) => {
      if (!requireAdmin(request, response)) return;
      const view = customerView(request.params.id);
      if (!view) return response.status(404).json(errorBody("NOT_FOUND", "customer not found"));
      return response.json({ ...view, payments: saas.payments(request.params.id, 50), loginId: deps.auth.userByCustomer(request.params.id)?.id ?? null });
    });

    app.put("/v1/customers/:id", (request, response) => {
      if (!requireAdmin(request, response)) return;
      const parsed = updateCustomerSchema.safeParse(request.body);
      if (!parsed.success) return validationError(response, parsed.error.issues);
      const customer = saas.getCustomer(request.params.id);
      if (!customer) return response.status(404).json(errorBody("NOT_FOUND", "customer not found"));
      const { password, licenseStatus, ...patch } = parsed.data;
      saas.updateCustomer(customer.id, patch);
      if (patch.name && customer.clientId) deps.clients?.update(customer.clientId, { name: patch.name });
      if (password) {
        const login = deps.auth.userByCustomer(customer.id);
        if (login) deps.auth.setPassword(login.id, password);
      }
      if (licenseStatus) {
        const lic = saas.licenseFor(customer.id);
        if (lic) saas.setLicenseStatus(lic.key, licenseStatus);
      }
      saas.audit(adminActor(request), "customer.update", customer.id, JSON.stringify(parsed.data.password ? { ...parsed.data, password: "***" } : parsed.data));
      return response.json(customerView(customer.id));
    });

    app.delete("/v1/customers/:id", (request, response) => {
      if (!requireAdmin(request, response)) return;
      const customer = saas.getCustomer(request.params.id);
      if (!customer) return response.status(404).json(errorBody("NOT_FOUND", "customer not found"));
      const login = deps.auth.userByCustomer(customer.id);
      if (login) deps.auth.removeUser(login.id);
      if (customer.clientId) deps.clients?.remove(customer.clientId);
      saas.deleteCustomer(customer.id);
      saas.audit(adminActor(request), "customer.delete", customer.id, customer.name);
      return response.status(204).end();
    });

    app.post("/v1/customers/:id/reissue-license", (request, response) => {
      if (!requireAdmin(request, response)) return;
      if (!saas.getCustomer(request.params.id)) return response.status(404).json(errorBody("NOT_FOUND", "customer not found"));
      const license = saas.issueLicense(request.params.id);
      saas.audit(adminActor(request), "license.reissue", request.params.id, license.key);
      return response.status(201).json({ license });
    });

    // Admin "log in as customer": starts a customer session in this browser.
    app.post("/v1/customers/:id/login-as", (request, response) => {
      if (!requireAdmin(request, response)) return;
      const login = deps.auth.userByCustomer(request.params.id);
      if (!login) return response.status(404).json(errorBody("NOT_FOUND", "customer login not found"));
      const session = deps.auth.issueSessionFor(login.id);
      if (!session) return response.status(404).json(errorBody("NOT_FOUND", "customer login not found"));
      deps.auth.setSessionCookie(request, response, session.token, session.expiresAt);
      saas.audit(adminActor(request), "customer.login_as", request.params.id, login.email);
      return response.json({ ok: true, email: login.email });
    });

    app.get("/v1/billing/payments", (request, response) => {
      if (!requireAdmin(request, response)) return;
      return response.json({ payments: saas.payments(undefined, 200) });
    });

    app.get("/v1/audit", (request, response) => {
      if (!requireAdmin(request, response)) return;
      return response.json({ entries: saas.auditLog(300) });
    });

    // --- billing (customer buys/upgrades) ---
    app.post("/v1/billing/checkout", async (request, response) => {
      const customer = sessionCustomer(request);
      if (!customer) return response.status(403).json(errorBody("FORBIDDEN", "customers only"));
      const parsed = checkoutSchema.safeParse(request.body);
      if (!parsed.success) return validationError(response, parsed.error.issues);
      const plan = saas.getPlan(parsed.data.planId);
      if (!plan || !plan.active) return response.status(404).json(errorBody("NOT_FOUND", "plan not available"));
      if (!deps.billing) return response.json({ manual: true, message: "Online payment isn't set up. Please contact us." });
      try {
        const out = await deps.billing.checkout(customer.id, plan, parsed.data.cycle);
        saas.audit(customer.name, "billing.checkout", customer.id, `${plan.id}/${parsed.data.cycle}`);
        return response.json(out);
      } catch (error) {
        return response.status(502).json(errorBody("RAZORPAY_ERROR", (error as Error).message));
      }
    });

    app.post("/v1/billing/confirm", (request, response) => {
      const customer = sessionCustomer(request);
      if (!customer || !deps.billing) return response.status(403).json(errorBody("FORBIDDEN", "customers only"));
      const result = deps.billing.confirm(customer.id, (request.body ?? {}) as Record<string, string>);
      if (!result.ok) return response.status(400).json(errorBody("PAYMENT_UNVERIFIED", "could not verify the payment"));
      return response.json({ ok: true });
    });

    // --- customer self-service ---
    app.get("/v1/me", (request, response) => {
      const customer = resolveCustomer(request);
      if (!customer) return response.status(404).json(errorBody("NOT_FOUND", "no customer for this account"));
      return response.json(customerView(customer.id));
    });

    // The customer's own business settings (and admin via ?customerId=). Secrets stay masked.
    const meClient = (request: Request) => {
      const c = resolveCustomer(request);
      return c?.clientId && deps.clients ? { customer: c, client: deps.clients.get(c.clientId) } : undefined;
    };

    app.get("/v1/me/business", (request, response) => {
      const ctx = meClient(request);
      if (!ctx?.client) return response.status(404).json(errorBody("NOT_FOUND", "no business for this account"));
      return response.json({ business: deps.clients!.view(ctx.client) });
    });

    app.put("/v1/me/business", (request, response) => {
      const ctx = meClient(request);
      if (!ctx?.client || !deps.clients) return response.status(404).json(errorBody("NOT_FOUND", "no business for this account"));
      const parsed = customerBusinessSchema.safeParse(request.body);
      if (!parsed.success) return validationError(response, parsed.error.issues);
      // Empty-string secrets clear a key; omitted keeps it.
      const patch: Record<string, unknown> = { ...parsed.data };
      if (parsed.data.whatsappApiSecret === undefined) delete patch.whatsappApiSecret;
      if (parsed.data.geminiApiKey === undefined) delete patch.geminiApiKey;
      const client = deps.clients.update(ctx.client.id, patch as never)!;
      saas!.audit(actingUser(request)?.email ?? "customer", "business.update", ctx.customer.id);
      return response.json({ business: deps.clients.view(client) });
    });

    // Scoped dashboard data for the signed-in customer (admins read any via ?customerId=).
    app.get("/v1/me/analytics", (request, response) => {
      const ctx = meClient(request);
      if (!ctx?.client || !deps.analytics) return response.status(404).json(errorBody("NOT_FOUND", "unavailable"));
      const days = Math.min(Math.max(Number(request.query.days) || 7, 1), 365);
      const tzOffset = Math.min(Math.max(Number(request.query.tzOffset) || 0, -840), 840);
      const report = deps.analytics.report({ days, clientId: ctx.client.id, tzOffset });
      return response.json({ ...report, entitlement: saas!.entitlement(ctx.customer), usage: saas!.usageHistory(ctx.customer.id) });
    });

    // A pairing code for the customer's own phone; the paired phone binds to their business.
    app.post("/v1/me/pairing", async (request, response) => {
      const ctx = meClient(request);
      if (!ctx?.client) return response.status(404).json(errorBody("NOT_FOUND", "no business for this account"));
      const ent = saas!.entitlement(ctx.customer);
      const phones = deps.clients!.deviceIds(ctx.client.id).length;
      if (phones >= ent.maxPhones) {
        return response.status(409).json(errorBody("PHONE_LIMIT", `Your plan allows ${ent.maxPhones} phone(s). Upgrade to add more.`));
      }
      const publicUrl = (deps.config.get("publicUrl") ?? `${request.protocol}://${request.host}`).replace(/\/+$/, "");
      const { code, expiresAt } = pairing.create(ctx.client.id);
      const uri = pairUri(publicUrl, code);
      const qrSvg = await QRCode.toString(uri, { type: "svg", margin: 1, errorCorrectionLevel: "M" });
      return response.status(201).json({ code, expiresAt, publicUrl, pairUri: uri, qrSvg });
    });

    app.get("/v1/me/devices", (request, response) => {
      const ctx = meClient(request);
      if (!ctx?.client) return response.status(404).json(errorBody("NOT_FOUND", "no business for this account"));
      const ids = new Set(deps.clients!.deviceIds(ctx.client.id));
      return response.json({ devices: deps.state.listDevices().filter((d) => ids.has(d.id)) });
    });

    app.delete("/v1/me/devices/:id", (request, response) => {
      const ctx = meClient(request);
      if (!ctx?.client) return response.status(404).json(errorBody("NOT_FOUND", "no business for this account"));
      if (!deps.clients!.deviceIds(ctx.client.id).includes(request.params.id)) {
        return response.status(404).json(errorBody("NOT_FOUND", "phone not found"));
      }
      deps.deviceChannel.disconnect?.(request.params.id);
      deps.state.removeDevice(request.params.id);
      return response.status(204).end();
    });

    app.get("/v1/me/calls", (request, response) => {
      const ctx = meClient(request);
      if (!ctx?.client) return response.status(404).json(errorBody("NOT_FOUND", "no business for this account"));
      const ids = new Set(deps.clients!.deviceIds(ctx.client.id));
      const calls = deps.state.listCalls({ limit: 100 }).filter((c) => ids.has(c.deviceId));
      return response.json({ calls });
    });

    app.get("/v1/me/calls/:callId/transcript", (request, response) => {
      const ctx = meClient(request);
      const call = deps.state.getCall(request.params.callId);
      if (!ctx?.client || !call || !deps.clients!.deviceIds(ctx.client.id).includes(call.deviceId)) {
        return response.status(404).json(errorBody("NOT_FOUND", "call not found"));
      }
      return response.json({ transcript: deps.agents?.transcript(call.id) ?? [] });
    });
  }


  app.post("/v1/auth/password", (request, response) => {
    const parsed = changePasswordSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    const token = deps.auth.sessionToken(request);
    const user = deps.auth.sessionUser(token);
    if (!user) return response.status(403).json(errorBody("SESSION_REQUIRED", "sign in to the console to change a password"));
    if (!deps.auth.changePassword(user.id, parsed.data.current, parsed.data.password, token)) {
      return response.status(401).json(errorBody("INVALID_PASSWORD", "current password is wrong"));
    }
    return response.status(204).end();
  });

  // ---- console accounts ----
  app.get("/v1/users", (_request, response) => {
    response.json({ users: deps.auth.listUsers() });
  });

  app.post("/v1/users", (request, response) => {
    const parsed = createUserSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    const user = deps.auth.addUser(parsed.data.email, parsed.data.password);
    if (user === "exists") return response.status(409).json(errorBody("EXISTS", "an account with this email exists"));
    return response.status(201).json({ user });
  });

  app.delete("/v1/users/:id", (request, response) => {
    const result = deps.auth.removeUser(request.params.id);
    if (result === "last") return response.status(409).json(errorBody("LAST_USER", "can't remove the only account"));
    if (result === "not_found") return response.status(404).json(errorBody("NOT_FOUND", "account not found"));
    return response.status(204).end();
  });

  // ---- settings, API keys, webhook log ----
  app.get("/v1/settings", (_request, response) => {
    response.json({ settings: deps.config.view() });
  });

  app.put("/v1/settings", (request, response) => {
    const parsed = configSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    deps.config.update(parsed.data);
    if (parsed.data.geminiApiKey !== undefined) void deps.fallback?.refresh();
    return response.json({ settings: deps.config.view() });
  });

  app.get("/v1/api-keys", (_request, response) => {
    response.json({ apiKeys: deps.auth.listApiKeys() });
  });

  app.post("/v1/api-keys", (request, response) => {
    const parsed = createApiKeySchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    return response.status(201).json({ apiKey: deps.auth.createApiKey(parsed.data.name) });
  });

  app.delete("/v1/api-keys/:id", (request, response) => {
    if (!deps.auth.revokeApiKey(request.params.id)) {
      return response.status(404).json(errorBody("NOT_FOUND", "API key not found"));
    }
    return response.status(204).end();
  });

  app.get("/v1/webhook-deliveries", (request, response) => {
    const callId = typeof request.query.callId === "string" ? request.query.callId : undefined;
    const limit = Number(request.query.limit) || undefined;
    response.json({ deliveries: deps.events.listDeliveries({ callId, limit }) });
  });

  // Admin side of QR pairing: a fresh one-time code and its QR (SVG).
  app.post("/v1/pairing", async (request, response) => {
    const parsed = pairingRequestSchema.safeParse(request.body ?? {});
    if (!parsed.success) return validationError(response, parsed.error.issues);
    const publicUrl = (parsed.data.publicUrl ?? deps.config.get("publicUrl") ?? `${request.protocol}://${request.host}`)
      .replace(/\/+$/, "");
    const { code, expiresAt } = pairing.create();
    const uri = pairUri(publicUrl, code);
    const qrSvg = await QRCode.toString(uri, { type: "svg", margin: 1, errorCorrectionLevel: "M" });
    return response.status(201).json({ code, expiresAt, publicUrl, pairUri: uri, qrSvg });
  });

  app.get("/v1/server", (request, response) => {
    response.json({
      publicUrl: deps.config.get("publicUrl") ?? null,
      docsUrl: deps.config.get("docsUrl") ?? null,
      requestOrigin: `${request.protocol}://${request.host}`,
      brandName: deps.config.get("brandName") ?? null,
      multiClient: deps.multiClient ?? false,
    });
  });

  app.delete("/v1/devices/:deviceId", (request, response) => {
    deps.deviceChannel.disconnect?.(request.params.deviceId);
    if (!deps.state.removeDevice(request.params.deviceId)) {
      return response.status(404).json(errorBody("NOT_FOUND", "device not found"));
    }
    return response.status(204).end();
  });

  app.post("/v1/devices", (request, response) => {
    const parsed = registerDeviceSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);

    const { deviceId, name, metadata, rotateToken } = parsed.data;
    const existed = Boolean(deps.state.getDevice(deviceId));
    const device = deps.state.upsertDevice({ id: deviceId, name, metadata });
    const existingToken = deps.state.hasDeviceToken(deviceId);
    let deviceToken: string | undefined;

    if (!existingToken || rotateToken) {
      deviceToken = randomBytes(32).toString("base64url");
      deps.state.setDeviceToken(deviceId, deviceToken);
      if (existingToken && rotateToken) deps.deviceChannel.disconnect?.(deviceId);
    }

    return response.status(existed ? 200 : 201).json({
      device,
      ...(deviceToken ? { deviceToken } : {}),
      deviceChannel: {
        path: "/v1/device-channel",
        auth: ["deviceId", "deviceToken"],
      },
    });
  });

  app.get("/v1/devices", (_request, response) => {
    response.json({ devices: deps.state.listDevices() });
  });

  app.get("/v1/devices/:deviceId", (request, response) => {
    const device = deps.state.getDevice(request.params.deviceId);
    if (!device) return response.status(404).json(errorBody("NOT_FOUND", "device not found"));
    return response.json({ device });
  });

  app.post("/v1/calls", (request, response) => {
    const parsed = createCallSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);

    const device = deps.state.getDevice(parsed.data.deviceId);
    if (!device) return response.status(404).json(errorBody("NOT_FOUND", "device not found"));
    if (!deps.deviceChannel.isConnected(device.id)) {
      return response.status(409).json(errorBody("DEVICE_OFFLINE", "device is offline"));
    }

    const call = deps.state.createOutgoingCall({
      deviceId: device.id,
      to: parsed.data.to,
      webhookUrl: parsed.data.webhookUrl,
      metadata: parsed.data.metadata,
      stream: parsed.data.stream,
    });

    try {
      const command = deps.deviceChannel.send(device.id, {
        action: "dial",
        callId: call.id,
        payload: { to: call.remoteNumber },
      });
      return response.status(202).json({ call, command, media: mediaInfo(call.id) });
    } catch (error) {
      deps.state.setCallState(call.id, "failed");
      if (error instanceof DeviceOfflineError) {
        return response.status(409).json(errorBody("DEVICE_OFFLINE", "device is offline"));
      }
      throw error;
    }
  });

  // Newest first. Page with ?before=<nextBefore>; filter by deviceId, state, direction.
  app.get("/v1/calls", (request, response) => {
    const parsed = listCallsQuerySchema.safeParse(request.query);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    const calls = deps.state.listCalls(parsed.data);
    const limit = parsed.data.limit ?? 50;
    return response.json({ calls, nextBefore: calls.length === limit ? calls.at(-1)!.createdAt : null });
  });

  app.get("/v1/calls/:callId", (request, response) => {
    const call = deps.state.getCall(request.params.callId);
    if (!call) return response.status(404).json(errorBody("NOT_FOUND", "call not found"));
    return response.json({ call, media: mediaInfo(call.id) });
  });

  app.get("/v1/calls/:callId/events", (request, response) => {
    const call = deps.state.getCall(request.params.callId);
    if (!call) return response.status(404).json(errorBody("NOT_FOUND", "call not found"));
    return response.json({ events: deps.state.listCallEvents(call.id) });
  });

  app.get("/v1/calls/:callId/transcript", (request, response) => {
    const call = deps.state.getCall(request.params.callId);
    if (!call) return response.status(404).json(errorBody("NOT_FOUND", "call not found"));
    return response.json({ transcript: deps.agents?.transcript(call.id) ?? [] });
  });

  // ---- Agent streams: the server connects the call to your WebSocket ----
  app.get("/v1/calls/:callId/stream", (request, response) => {
    if (!deps.streams) return response.status(404).json(errorBody("NOT_FOUND", "streams unavailable"));
    if (!deps.state.getCall(request.params.callId)) return response.status(404).json(errorBody("NOT_FOUND", "call not found"));
    return response.json(deps.streams.info(request.params.callId));
  });

  app.post("/v1/calls/:callId/stream", (request, response) => {
    if (!deps.streams) return response.status(404).json(errorBody("NOT_FOUND", "streams unavailable"));
    const parsed = streamSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    try {
      const call = deps.streams.attach(request.params.callId, parsed.data);
      return response.status(202).json({ call, ...deps.streams.info(call.id) });
    } catch (error) {
      return response.status(409).json(errorBody("CALL_NOT_LIVE", (error as Error).message));
    }
  });

  app.delete("/v1/calls/:callId/stream", (request, response) => {
    deps.streams?.detach(request.params.callId);
    response.status(204).end();
  });

  // ---- AI phone assistant (Gemini Live) ----
  app.get("/v1/agent", (_request, response) => {
    const agents = deps.agents;
    response.json({
      configured: agents?.configured ?? false,
      model: agents?.model,
      settings: agents?.settings,
      whatsapp: Boolean(whatsappFromConfig(deps.config)),
    });
  });

  // Sends the booking message to a number, to check a WhatsApp gateway setup.
  async function whatsappTest(request: Request, response: Response, target: WhatsAppTarget | undefined, business: string, link: string) {
    const parsed = whatsappTestSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    if (!target) {
      return response.status(409).json(errorBody("WHATSAPP_NOT_SET", "set the WhatsApp gateway address and secret first"));
    }
    const to = toWhatsAppNumber(parsed.data.to, target.countryCode);
    if (!to) return response.status(400).json(errorBody("INVALID_NUMBER", "enter a WhatsApp number with 10 or more digits"));
    const text = renderTemplate(target.message, { name: "", business, link });
    try {
      await sendWhatsApp(target, to, text);
      return response.json({ ok: true, to, text });
    } catch (error) {
      return response.status(502).json(errorBody("WHATSAPP_FAILED", (error as Error).message));
    }
  }

  app.post("/v1/whatsapp/test", (request, response) => {
    const settings = deps.agents?.settings;
    return whatsappTest(
      request,
      response,
      whatsappFromConfig(deps.config),
      settings?.businessName || settings?.ownerName || "OpenLine",
      settings?.bookingUrl ?? "",
    );
  });

  // ---- Clients: businesses the receptionist answers for, each with its own phones ----
  const clientsOr404 = (response: Response) => {
    if (!deps.clients) response.status(404).json(errorBody("NOT_FOUND", "clients unavailable"));
    return deps.clients;
  };

  /** Assigns exactly these phones to the client (phones of other clients move over). */
  function setClientDevices(clients: ClientStore, clientId: string, deviceIds: string[] | undefined): string | undefined {
    if (!deviceIds) return undefined;
    const unknown = deviceIds.find((id) => !deps.state.getDevice(id));
    if (unknown) return unknown;
    for (const id of clients.deviceIds(clientId)) if (!deviceIds.includes(id)) clients.assignDevice(id, null);
    for (const id of deviceIds) clients.assignDevice(id, clientId);
    return undefined;
  }

  // ---- AI providers and automatic failover ----
  app.get("/v1/ai/providers", (_request, response) => {
    const router = deps.router;
    if (!router) return response.status(404).json(errorBody("NOT_FOUND", "routing unavailable"));
    const routing = router.routing();
    return response.json({
      mode: routing.mode,
      // Keys never leave the server: only whether one is saved and its last 4 characters.
      providers: routing.providers.map(({ apiKey, ...p }) => ({ ...p, keySet: Boolean(apiKey), keyHint: apiKey ? `…${apiKey.slice(-4)}` : null })),
      status: router.status(),
      pipelineReady: router.pipelineReady(),
      presets: PRESETS,
    });
  });

  app.put("/v1/ai/providers", (request, response) => {
    const router = deps.router;
    if (!router) return response.status(404).json(errorBody("NOT_FOUND", "routing unavailable"));
    const parsed = routingSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    const saved = new Map(router.routing().providers.map((p) => [p.id, p]));
    router.save({
      mode: parsed.data.mode,
      providers: parsed.data.providers.map((p) => ({
        ...p,
        apiKey: p.apiKey === undefined ? saved.get(p.id)?.apiKey ?? "" : p.apiKey,
        baseUrl: p.baseUrl || undefined,
        accountId: p.accountId || undefined,
        voice: p.voice || undefined,
      })),
    });
    router.resetCooldowns();
    void deps.fallback?.refresh();
    return response.json({ ok: true, status: router.status(), pipelineReady: router.pipelineReady() });
  });

  // "Please hold" / "please call later" phrases played when the AI can't speak.
  app.get("/v1/ai/fallback", (_request, response) => {
    if (!deps.fallback) return response.status(404).json(errorBody("NOT_FOUND", "unavailable"));
    return response.json({ phrases: deps.fallback.info() });
  });

  app.put("/v1/ai/fallback", async (request, response) => {
    if (!deps.fallback) return response.status(404).json(errorBody("NOT_FOUND", "unavailable"));
    const parsed = fallbackTextSchema.safeParse(request.body ?? {});
    if (!parsed.success) return validationError(response, parsed.error.issues);
    deps.fallback.setText(parsed.data);
    await deps.fallback.refresh(true);
    return response.json({ phrases: deps.fallback.info() });
  });

  // ---- Dashboard analytics ----
  app.get("/v1/analytics", (request, response) => {
    if (!deps.analytics) return response.status(404).json(errorBody("NOT_FOUND", "analytics unavailable"));
    const parsed = analyticsQuerySchema.safeParse(request.query);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    return response.json(deps.analytics.report(parsed.data));
  });

  app.get("/v1/clients", (_request, response) => {
    const clients = clientsOr404(response);
    if (!clients) return;
    response.json({ clients: clients.list().map((c) => clients.view(c)) });
  });

  app.post("/v1/clients", (request, response) => {
    const clients = clientsOr404(response);
    if (!clients) return;
    const parsed = createClientSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    const { deviceIds, ...input } = parsed.data;
    const client = clients.create(input);
    const unknown = setClientDevices(clients, client.id, deviceIds);
    if (unknown) {
      clients.remove(client.id);
      return response.status(404).json(errorBody("NOT_FOUND", `phone ${unknown} not found`));
    }
    return response.status(201).json({ client: clients.view(clients.get(client.id)!) });
  });

  app.get("/v1/clients/:id", (request, response) => {
    const clients = clientsOr404(response);
    if (!clients) return;
    const client = clients.get(request.params.id);
    if (!client) return response.status(404).json(errorBody("NOT_FOUND", "client not found"));
    return response.json({ client: clients.view(client) });
  });

  app.put("/v1/clients/:id", (request, response) => {
    const clients = clientsOr404(response);
    if (!clients) return;
    const parsed = updateClientSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    if (!clients.get(request.params.id)) return response.status(404).json(errorBody("NOT_FOUND", "client not found"));
    const { deviceIds, ...patch } = parsed.data;
    const unknown = setClientDevices(clients, request.params.id, deviceIds);
    if (unknown) return response.status(404).json(errorBody("NOT_FOUND", `phone ${unknown} not found`));
    const client = clients.update(request.params.id, patch)!;
    return response.json({ client: clients.view(client) });
  });

  app.delete("/v1/clients/:id", (request, response) => {
    const clients = clientsOr404(response);
    if (!clients) return;
    if (!clients.remove(request.params.id)) return response.status(404).json(errorBody("NOT_FOUND", "client not found"));
    return response.status(204).end();
  });

  app.post("/v1/clients/:id/whatsapp-test", (request, response) => {
    const clients = clientsOr404(response);
    if (!clients) return;
    const client = clients.get(request.params.id);
    if (!client) return response.status(404).json(errorBody("NOT_FOUND", "client not found"));
    return whatsappTest(request, response, whatsappFromClient(client), client.name, client.bookingUrl);
  });

  app.put("/v1/agent", (request, response) => {
    if (!deps.agents) return response.status(404).json(errorBody("NOT_FOUND", "assistant unavailable"));
    const parsed = agentSettingsSchema.safeParse(request.body);
    if (!parsed.success) return validationError(response, parsed.error.issues);
    return response.json({ settings: deps.agents.updateSettings(parsed.data) });
  });

  app.get("/v1/agent/messages", (request, response) => {
    const limit = Number(request.query.limit) || undefined;
    response.json({ messages: deps.agents?.listMessages(limit) ?? [] });
  });

  app.get("/v1/calls/:callId/agent", (request, response) => {
    if (!deps.agents) return response.status(404).json(errorBody("NOT_FOUND", "assistant unavailable"));
    return response.json(deps.agents.info(request.params.callId));
  });

  app.post("/v1/calls/:callId/agent", (request, response) => {
    if (!deps.agents) return response.status(404).json(errorBody("NOT_FOUND", "assistant unavailable"));
    try {
      deps.agents.takeOver(request.params.callId);
      return response.status(202).json(deps.agents.info(request.params.callId));
    } catch (error) {
      return response.status(409).json(errorBody("AGENT_UNAVAILABLE", (error as Error).message));
    }
  });

  app.delete("/v1/calls/:callId/agent", (request, response) => {
    deps.agents?.release(request.params.callId);
    response.status(204).end();
  });

  app.post(
    ["/v1/calls/:callId/:action", "/v1/calls/:callId/actions/:action"],
    (request, response) => {
      const actionResult = callActionSchema.safeParse({ action: firstParam(request.params.action) });
      if (!actionResult.success) return validationError(response, actionResult.error.issues);

      const call = deps.state.getCall(firstParam(request.params.callId));
      if (!call) return response.status(404).json(errorBody("NOT_FOUND", "call not found"));
      if (!deps.deviceChannel.isConnected(call.deviceId)) {
        return response.status(409).json(errorBody("DEVICE_OFFLINE", "device is offline"));
      }

      let payload: Record<string, unknown> | undefined;
      if (actionResult.data.action === "dtmf") {
        const bodyResult = dtmfBodySchema.safeParse(request.body);
        if (!bodyResult.success) return validationError(response, bodyResult.error.issues);
        payload = { digits: bodyResult.data.digits };
      }

      try {
        const command = deps.deviceChannel.send(call.deviceId, {
          action: actionResult.data.action,
          callId: call.id,
          payload,
        });
        return response.status(202).json({ accepted: true, command, call });
      } catch (error) {
        if (error instanceof DeviceOfflineError) {
          return response.status(409).json(errorBody("DEVICE_OFFLINE", "device is offline"));
        }
        throw error;
      }
    },
  );

  app.use((_request, response) => {
    response.status(404).json(errorBody("NOT_FOUND", "route not found"));
  });

  app.use((error: unknown, _request: Request, response: Response, _next: express.NextFunction) => {
    if (error instanceof SyntaxError && "status" in error && error.status === 400) {
      return response.status(400).json(errorBody("INVALID_JSON", "request body must be valid JSON"));
    }
    console.error("unhandled request error", error);
    return response.status(500).json(errorBody("INTERNAL_ERROR", "internal server error"));
  });

  return app;
}

function errorBody(code: string, message: string) {
  return { error: { code, message } };
}

function validationError(response: Response, issues: unknown) {
  return response.status(400).json({
    error: { code: "VALIDATION_ERROR", message: "invalid request", issues },
  });
}

function firstParam(value: string | string[] | undefined): string {
  return Array.isArray(value) ? value[0] ?? "" : value ?? "";
}
