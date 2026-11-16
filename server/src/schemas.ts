import { z } from "zod";
const metadataSchema = z.record(z.string(), z.unknown()).optional();
const optionalHttpUrl = z.string().url().refine((value) => {
  const protocol = new URL(value).protocol;
  return protocol === "http:" || protocol === "https:";
}, "webhookUrl must use http or https");

export const registerDeviceSchema = z.object({
  deviceId: z.string().trim().min(1).max(128),
  name: z.string().trim().min(1).max(128).optional(),
  metadata: metadataSchema,
  rotateToken: z.boolean().optional().default(false),
});

export const streamSchema = z.object({
  url: z.string().trim().url().regex(/^wss?:\/\//, "stream url must be ws:// or wss://"),
  format: z.enum(["raw", "twilio"]).default("raw"),
  parameters: z.record(z.string(), z.string().max(500)).optional(),
});

export const createCallSchema = z.object({
  deviceId: z.string().trim().min(1).max(128),
  to: z.string().trim().min(1).max(64),
  webhookUrl: optionalHttpUrl.optional(),
  metadata: metadataSchema,
  /** Stream the call to your agent's WebSocket once it connects. */
  stream: streamSchema.optional(),
});

export const callActionSchema = z.object({
  action: z.enum(["answer", "reject", "hangup", "hold", "unhold", "dtmf"]),
});

export const dtmfBodySchema = z.object({
  digits: z.string().min(1).max(64).regex(/^[0-9*#]+$/, "invalid DTMF digits"),
});

export const emptyActionBodySchema = z.object({}).passthrough();

export const deviceEventSchema = z.object({
  type: z.literal("event"),
  eventId: z.string().min(1).max(128),
  callId: z.string().min(1).max(128),
  event: z.enum([
    "call.new",
    "call.connecting",
    "call.select_phone_account",
    "call.ringing",
    "call.dialing",
    "call.active",
    "call.holding",
    "call.disconnecting",
    "call.disconnected",
    "call.pulling",
    "call.audio_processing",
    "call.simulated_ringing",
    "call.rejected",
    "call.failed",
    "command.completed",
    "command.ack",
  ]),
  timestamp: z.string().datetime({ offset: true }),
  payload: z.record(z.string(), z.unknown()).default({}),
});

export const deviceAckSchema = z.object({
  type: z.literal("ack"),
  commandId: z.string().min(1).max(128),
  callId: z.string().min(1).max(128),
  ok: z.boolean(),
  error: z.string().max(1000).optional(),
  timestamp: z.string().datetime({ offset: true }),
});

export const deviceInboundMessageSchema = z.discriminatedUnion("type", [
  deviceEventSchema,
  deviceAckSchema,
]);

export const agentSettingsSchema = z
  .object({
    autoAnswer: z.boolean(),
    ownerName: z.string().trim().min(1).max(80),
    about: z.string().max(20000),
    voice: z.string().trim().min(1).max(40),
    mode: z.enum(["message", "receptionist"]),
    businessName: z.string().trim().max(120),
    bookingUrl: z.union([z.literal(""), z.string().trim().url().regex(/^https?:\/\//, "must be http(s)")]),
  })
  .partial();

const optionalUrl = z.union([z.literal(""), z.string().trim().url().regex(/^https?:\/\//, "must be http(s)")]);

/** A client (business) the receptionist answers for. */
export const clientSchema = z.object({
  name: z.string().trim().min(1).max(120),
  enabled: z.boolean(),
  about: z.string().max(20000),
  bookingUrl: optionalUrl,
  voice: z.string().trim().min(1).max(40),
  whatsappGatewayUrl: optionalUrl,
  /** Omit to keep the saved secret; "" removes it. */
  whatsappApiSecret: z.union([z.literal(""), z.string().trim().min(4).max(500)]),
  whatsappCountryCode: z.string().trim().regex(/^\d{1,4}$/, "digits only, e.g. 91"),
  whatsappMessage: z.string().trim().max(1000),
  /** Omit to keep the saved key; "" removes it. */
  geminiApiKey: z.union([z.literal(""), z.string().trim().min(10).max(200)]),
  /** Phones that answer for this client. */
  deviceIds: z.array(z.string().min(1).max(128)).max(50),
});
export const createClientSchema = clientSchema.partial().required({ name: true });
export const updateClientSchema = clientSchema.partial();

/** Rejects loopback, private, link-local and metadata addresses (the server would POST there). */
export function isPublicHttpsUrl(value: string): boolean {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  if (url.protocol !== "https:") return false;
  const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return false;
  const v4 = /^(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(host);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    if (a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127)) return false;
  }
  if (host.includes(":")) {
    if (host === "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80") || host.startsWith("::ffff:")) return false;
  }
  return true;
}

/** What a customer may edit about their own business (no phone assignment, no enable toggle). */
export const customerBusinessSchema = clientSchema
  .partial()
  .omit({ deviceIds: true, enabled: true })
  .extend({
    whatsappGatewayUrl: z
      .union([z.literal(""), z.string().trim().url().refine(isPublicHttpsUrl, "use the public https address of your WhatsApp gateway")])
      .optional(),
  });

const provider = z.object({
  id: z.string().trim().min(1).max(64),
  kind: z.enum(["live", "llm", "stt", "tts"]),
  type: z.enum(["gemini", "groq", "cloudflare", "openrouter", "cerebras", "mistral", "sarvam", "openai"]),
  label: z.string().trim().min(1).max(60),
  enabled: z.boolean(),
  /** Omit to keep the saved key. */
  apiKey: z.string().trim().max(500).optional(),
  model: z.string().trim().min(1).max(120),
  baseUrl: z.union([z.literal(""), z.string().trim().url().regex(/^https:\/\//, "must be https")]).optional(),
  accountId: z.string().trim().max(64).regex(/^[A-Za-z0-9]*$/, "letters and digits only").optional(),
  voice: z.string().trim().max(60).optional(),
});
export const routingSchema = z.object({
  mode: z.enum(["auto", "live", "pipeline"]),
  providers: z.array(provider).max(40),
});

const paise = z.number().int().min(0).max(100_000_000);
export const planSchema = z.object({
  id: z.string().trim().regex(/^[a-z0-9-]{2,32}$/).optional(),
  name: z.string().trim().min(1).max(60),
  priceMonthly: paise,
  priceYearly: paise,
  setupFee: paise.optional(),
  minutesIncluded: z.number().int().min(-1).max(1_000_000),
  maxPhones: z.number().int().min(1).max(100),
  trialDays: z.number().int().min(0).max(90).optional(),
  features: z.array(z.string().max(120)).max(20).optional(),
  active: z.boolean().optional(),
  public: z.boolean().optional(),
  sort: z.number().int().min(0).max(999).optional(),
  razorpayPlanMonthly: z.string().trim().max(64).optional(),
  razorpayPlanYearly: z.string().trim().max(64).optional(),
});


export const createCustomerSchema = z.object({
  businessName: z.string().trim().min(1).max(120),
  email: z.string().trim().toLowerCase().email().max(254),
  password: z.string().min(8).max(200),
  planId: z.string().trim().max(32).optional(),
  billingCycle: z.enum(["monthly", "yearly", "lifetime"]).optional(),
  lifetime: z.boolean().optional(),
  geminiApiKey: z.string().trim().max(200).optional(),
  notes: z.string().max(2000).optional(),
});

export const updateCustomerSchema = z.object({
  name: z.string().trim().min(1).max(120).optional(),
  planId: z.string().trim().max(32).optional(),
  billingCycle: z.enum(["monthly", "yearly", "lifetime"]).optional(),
  status: z.enum(["trialing", "active", "past_due", "suspended", "cancelled"]).optional(),
  lifetime: z.boolean().optional(),
  currentPeriodEnd: z.string().datetime({ offset: true }).optional(),
  notes: z.string().max(2000).optional(),
  password: z.string().min(8).max(200).optional(),
  licenseStatus: z.enum(["active", "suspended", "revoked"]).optional(),
});

export const licenseActivateSchema = z.object({
  key: z.string().trim().min(6).max(40),
  device: z.string().trim().max(120).optional(),
});

export const checkoutSchema = z.object({
  planId: z.string().trim().max(32),
  cycle: z.enum(["monthly", "yearly"]),
});

export const fallbackTextSchema = z.object({
  hold: z.string().trim().min(1).max(300).optional(),
  sorry: z.string().trim().min(1).max(500).optional(),
});

export const analyticsQuerySchema = z.object({
  days: z.coerce.number().int().min(1).max(365).default(7),
  clientId: z.string().min(1).max(64).default("all"),
  tzOffset: z.coerce.number().int().min(-840).max(840).default(0),
});

export const whatsappTestSchema = z.object({ to: z.string().trim().min(5).max(32) });

export const pairSchema = z.object({
  code: z.string().trim().min(4).max(32),
  name: z.string().trim().min(1).max(128).optional(),
});

export const pairingRequestSchema = z.object({
  publicUrl: z.string().trim().url().regex(/^https?:\/\//, "must be http(s)").optional(),
});

export const deviceHealthSchema = z.object({
  type: z.literal("health"),
  battery: z.number().int().min(0).max(100).optional(),
  charging: z.boolean().optional(),
  network: z.string().max(20).optional(),
  signal: z.number().int().min(0).max(4).optional(),
  carrier: z.string().max(64).optional(),
  audio: z.string().max(32).optional(),
  appVersion: z.string().max(32).optional(),
  android: z.string().max(16).optional(),
  model: z.string().max(64).optional(),
});

export const listCallsQuerySchema = z.object({
  deviceId: z.string().max(128).optional(),
  state: z.enum(["queued", "dialing", "ringing", "active", "held", "ended", "rejected", "failed"]).optional(),
  direction: z.enum(["incoming", "outgoing"]).optional(),
  before: z.string().datetime({ offset: true }).optional(),
  limit: z.coerce.number().int().min(1).max(200).optional(),
});

const password = z.string().min(8, "use at least 8 characters").max(200);

const email = z.string().trim().toLowerCase().email("enter a valid email").max(254);

export const setupSchema = z.object({ email, password, setupKey: z.string().max(500).optional() });
export const loginSchema = z.object({ email: z.string().trim().max(254), password: z.string().min(1).max(200) });
export const createUserSchema = z.object({ email, password });
export const changePasswordSchema = z.object({ current: z.string().min(1).max(200), password });
export const createApiKeySchema = z.object({ name: z.string().trim().min(1).max(80) });

const optionalSetting = (schema: z.ZodString) => z.union([z.literal(""), schema]).optional();
export const configSchema = z.object({
  geminiApiKey: optionalSetting(z.string().trim().min(10).max(200)),
  geminiModel: optionalSetting(z.string().trim().min(1).max(100)),
  publicUrl: optionalSetting(z.string().trim().url().regex(/^https?:\/\//, "must be http(s)")),
  webhookUrl: optionalSetting(z.string().trim().url().regex(/^https?:\/\//, "must be http(s)")),
  docsUrl: optionalSetting(z.string().trim().url().regex(/^https?:\/\//, "must be http(s)")),
  incomingStreamUrl: optionalSetting(z.string().trim().url().regex(/^wss?:\/\//, "must be ws:// or wss://")),
  incomingStreamFormat: z.enum(["raw", "twilio"]).optional(),
  whatsappGatewayUrl: optionalSetting(z.string().trim().url().regex(/^https?:\/\//, "must be http(s)")),
  whatsappApiSecret: optionalSetting(z.string().trim().min(4).max(500)),
  whatsappCountryCode: optionalSetting(z.string().trim().regex(/^\d{1,4}$/, "digits only, e.g. 91")),
  whatsappMessage: optionalSetting(z.string().trim().min(1).max(1000)),
  /** Product name shown in the console (white label). */
  brandName: optionalSetting(z.string().trim().min(1).max(60)),
});
