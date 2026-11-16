import { randomBytes, randomUUID } from "node:crypto";
import type { Database } from "./db.js";

/**
 * The SaaS layer: paying customers, their plans, licenses, usage and billing, on one central
 * server. Each customer owns one client (the business settings and phones in the `clients` and
 * `devices` tables) and one customer login (a `users` row, role "customer").
 *
 * Entitlement (may this customer's calls be answered right now?) depends on three things:
 *  - the license is active,
 *  - the status is trialing/active (not suspended, cancelled or past the grace period),
 *  - this month's used minutes are below the plan's included minutes (unlimited = -1).
 */

export type CustomerStatus = "trialing" | "active" | "past_due" | "suspended" | "cancelled";
export type BillingCycle = "monthly" | "yearly" | "lifetime";

export interface Plan {
  id: string;
  name: string;
  /** All money is in paise (₹1 = 100). */
  priceMonthly: number;
  priceYearly: number;
  setupFee: number;
  currency: string;
  /** Minutes included per month; -1 = unlimited. */
  minutesIncluded: number;
  maxPhones: number;
  trialDays: number;
  features: string[];
  active: boolean;
  /** Shown on the public pricing page. */
  public: boolean;
  sort: number;
  razorpayPlanMonthly?: string;
  razorpayPlanYearly?: string;
}

export interface Customer {
  id: string;
  name: string;
  clientId?: string;
  planId?: string;
  billingCycle: BillingCycle;
  status: CustomerStatus;
  lifetime: boolean;
  trialEndsAt?: string;
  currentPeriodEnd?: string;
  razorpayCustomerId?: string;
  razorpaySubscriptionId?: string;
  notes?: string;
  createdAt: string;
  updatedAt: string;
}

export interface License {
  key: string;
  customerId: string;
  status: "active" | "suspended" | "revoked";
  issuedAt: string;
  activatedAt?: string;
  lastSeenAt?: string;
  lastDevice?: string;
}

export interface Entitlement {
  entitled: boolean;
  /** Short machine reason when not entitled. */
  reason?: "no_customer" | "no_license" | "license_" | "suspended" | "cancelled" | "expired" | "minutes_exhausted";
  message?: string;
  status: CustomerStatus;
  minutesIncluded: number;
  minutesUsed: number;
  maxPhones: number;
}

const GRACE_DAYS = 3;

function nowIso(): string {
  return new Date().toISOString();
}

export function period(date = new Date()): string {
  return date.toISOString().slice(0, 7);
}

/** License key like OL-7F3A-91C2-XK4D (no confusable characters). */
export function newLicenseKey(): string {
  const alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  const group = () =>
    Array.from(randomBytes(4))
      .map((b) => alphabet[b % alphabet.length])
      .join("");
  return `OL-${group()}-${group()}-${group()}`;
}

export class SaasStore {
  constructor(private readonly db: Database) {}

  // ---- plans ----

  listPlans(includeHidden = true): Plan[] {
    const rows = this.db.prepare("SELECT * FROM plans ORDER BY sort, price_monthly").all() as unknown as PlanRow[];
    return rows.map(toPlan).filter((p) => includeHidden || (p.active && p.public));
  }

  getPlan(id: string): Plan | undefined {
    const row = this.db.prepare("SELECT * FROM plans WHERE id = ?").get(id) as PlanRow | undefined;
    return row ? toPlan(row) : undefined;
  }

  upsertPlan(input: Partial<Plan> & { name: string }): Plan {
    const now = nowIso();
    const id = input.id ?? slug(input.name);
    const existing = this.getPlan(id);
    const p: Plan = {
      id,
      name: input.name,
      priceMonthly: input.priceMonthly ?? existing?.priceMonthly ?? 0,
      priceYearly: input.priceYearly ?? existing?.priceYearly ?? 0,
      setupFee: input.setupFee ?? existing?.setupFee ?? 0,
      currency: input.currency ?? existing?.currency ?? "INR",
      minutesIncluded: input.minutesIncluded ?? existing?.minutesIncluded ?? 0,
      maxPhones: input.maxPhones ?? existing?.maxPhones ?? 1,
      trialDays: input.trialDays ?? existing?.trialDays ?? 7,
      features: input.features ?? existing?.features ?? [],
      active: input.active ?? existing?.active ?? true,
      public: input.public ?? existing?.public ?? true,
      sort: input.sort ?? existing?.sort ?? 0,
      razorpayPlanMonthly: input.razorpayPlanMonthly ?? existing?.razorpayPlanMonthly,
      razorpayPlanYearly: input.razorpayPlanYearly ?? existing?.razorpayPlanYearly,
    };
    this.db
      .prepare(
        `INSERT INTO plans (id, name, price_monthly, price_yearly, setup_fee, currency, minutes_included, max_phones, trial_days, features, active, public, sort, razorpay_plan_monthly, razorpay_plan_yearly, created_at, updated_at)
         VALUES (@id,@name,@priceMonthly,@priceYearly,@setupFee,@currency,@minutesIncluded,@maxPhones,@trialDays,@features,@active,@public,@sort,@rpm,@rpy,@now,@now)
         ON CONFLICT (id) DO UPDATE SET name=@name, price_monthly=@priceMonthly, price_yearly=@priceYearly,
           setup_fee=@setupFee, currency=@currency, minutes_included=@minutesIncluded, max_phones=@maxPhones,
           trial_days=@trialDays, features=@features, active=@active, public=@public, sort=@sort,
           razorpay_plan_monthly=@rpm, razorpay_plan_yearly=@rpy, updated_at=@now`,
      )
      .run({
        id: p.id, name: p.name, priceMonthly: p.priceMonthly, priceYearly: p.priceYearly, setupFee: p.setupFee,
        currency: p.currency, minutesIncluded: p.minutesIncluded, maxPhones: p.maxPhones, trialDays: p.trialDays,
        features: JSON.stringify(p.features), active: p.active ? 1 : 0, public: p.public ? 1 : 0, sort: p.sort,
        rpm: p.razorpayPlanMonthly ?? null, rpy: p.razorpayPlanYearly ?? null, now,
      });
    return this.getPlan(id)!;
  }

  deletePlan(id: string): boolean {
    if (this.db.prepare("SELECT 1 FROM customers WHERE plan_id = ? LIMIT 1").get(id)) {
      // Keep the history; hide it instead of breaking existing customers.
      this.db.prepare("UPDATE plans SET active = 0, public = 0, updated_at = ? WHERE id = ?").run(nowIso(), id);
      return false;
    }
    return Number(this.db.prepare("DELETE FROM plans WHERE id = ?").run(id).changes) > 0;
  }

  /** Writes the default plan line-up once, on an empty database. */
  seedPlans(): void {
    if (this.listPlans().length) return;
    const defaults: (Partial<Plan> & { name: string })[] = [
      { id: "starter", name: "Starter", priceMonthly: 199900, priceYearly: 1999000, setupFee: 299900, minutesIncluded: 300, maxPhones: 1, sort: 1, features: ["1 phone line", "300 AI minutes / month", "Dashboard & analytics", "Call transcripts"] },
      { id: "growth", name: "Growth", priceMonthly: 399900, priceYearly: 3999000, setupFee: 299900, minutesIncluded: 1000, maxPhones: 2, sort: 2, features: ["2 phone lines", "1,000 AI minutes / month", "WhatsApp booking links", "CSV reports", "Everything in Starter"] },
      { id: "pro", name: "Pro", priceMonthly: 799900, priceYearly: 7999000, setupFee: 0, minutesIncluded: 3000, maxPhones: 5, sort: 3, features: ["5 phone lines", "3,000 AI minutes / month", "Priority support", "Everything in Growth"] },
    ];
    for (const p of defaults) this.upsertPlan(p);
  }

  // ---- customers ----

  listCustomers(): Customer[] {
    return (this.db.prepare("SELECT * FROM customers ORDER BY created_at DESC").all() as unknown as CustomerRow[]).map(toCustomer);
  }

  getCustomer(id: string): Customer | undefined {
    const row = this.db.prepare("SELECT * FROM customers WHERE id = ?").get(id) as CustomerRow | undefined;
    return row ? toCustomer(row) : undefined;
  }

  customerByClient(clientId: string): Customer | undefined {
    const row = this.db.prepare("SELECT * FROM customers WHERE client_id = ?").get(clientId) as CustomerRow | undefined;
    return row ? toCustomer(row) : undefined;
  }

  customerByRazorpaySub(subId: string): Customer | undefined {
    const row = this.db.prepare("SELECT * FROM customers WHERE razorpay_subscription_id = ?").get(subId) as CustomerRow | undefined;
    return row ? toCustomer(row) : undefined;
  }

  createCustomer(input: { name: string; clientId: string; planId?: string; billingCycle?: BillingCycle; lifetime?: boolean; notes?: string }): Customer {
    const now = nowIso();
    const id = randomUUID();
    const plan = input.planId ? this.getPlan(input.planId) : undefined;
    const trialEnds = plan && plan.trialDays > 0 ? new Date(Date.now() + plan.trialDays * 86_400_000).toISOString() : undefined;
    this.db
      .prepare(
        `INSERT INTO customers (id, name, client_id, plan_id, billing_cycle, status, lifetime, trial_ends_at, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        id, input.name, input.clientId, input.planId ?? null, input.billingCycle ?? "monthly",
        input.lifetime ? "active" : "trialing", input.lifetime ? 1 : 0, trialEnds ?? null, now, now,
      );
    if (input.notes) this.db.prepare("UPDATE customers SET notes = ? WHERE id = ?").run(input.notes, id);
    this.issueLicense(id);
    return this.getCustomer(id)!;
  }

  updateCustomer(id: string, patch: Partial<Omit<Customer, "id" | "createdAt" | "updatedAt">>): Customer | undefined {
    const current = this.getCustomer(id);
    if (!current) return undefined;
    const next = { ...current, ...patch };
    this.db
      .prepare(
        `UPDATE customers SET name=?, plan_id=?, billing_cycle=?, status=?, lifetime=?, trial_ends_at=?,
           current_period_end=?, razorpay_customer_id=?, razorpay_subscription_id=?, notes=?, updated_at=? WHERE id=?`,
      )
      .run(
        next.name, next.planId ?? null, next.billingCycle, next.status, next.lifetime ? 1 : 0,
        next.trialEndsAt ?? null, next.currentPeriodEnd ?? null, next.razorpayCustomerId ?? null,
        next.razorpaySubscriptionId ?? null, next.notes ?? null, nowIso(), id,
      );
    return this.getCustomer(id);
  }

  deleteCustomer(id: string): void {
    this.db.prepare("DELETE FROM licenses WHERE customer_id = ?").run(id);
    this.db.prepare("DELETE FROM usage_counters WHERE customer_id = ?").run(id);
    this.db.prepare("DELETE FROM customers WHERE id = ?").run(id);
  }

  // ---- licenses ----

  issueLicense(customerId: string): License {
    let key = newLicenseKey();
    while (this.db.prepare("SELECT 1 FROM licenses WHERE key = ?").get(key)) key = newLicenseKey();
    this.db.prepare("INSERT INTO licenses (key, customer_id, status, issued_at) VALUES (?, ?, 'active', ?)").run(key, customerId, nowIso());
    return this.getLicense(key)!;
  }

  getLicense(key: string): License | undefined {
    const row = this.db.prepare("SELECT * FROM licenses WHERE key = ?").get(key) as LicenseRow | undefined;
    return row ? toLicense(row) : undefined;
  }

  licenseFor(customerId: string): License | undefined {
    const row = this.db.prepare("SELECT * FROM licenses WHERE customer_id = ? ORDER BY issued_at DESC LIMIT 1").get(customerId) as LicenseRow | undefined;
    return row ? toLicense(row) : undefined;
  }

  setLicenseStatus(key: string, status: License["status"]): void {
    this.db.prepare("UPDATE licenses SET status = ? WHERE key = ?").run(status, key);
  }

  /** The app calls this on start: records activation and returns what the app should show. */
  touchLicense(key: string, device?: string): { customer: Customer; license: License } | undefined {
    const license = this.getLicense(key);
    if (!license) return undefined;
    const now = nowIso();
    this.db
      .prepare("UPDATE licenses SET last_seen_at = ?, activated_at = COALESCE(activated_at, ?), last_device = COALESCE(?, last_device) WHERE key = ?")
      .run(now, now, device ?? null, key);
    const customer = this.getCustomer(license.customerId);
    return customer ? { customer, license: this.getLicense(key)! } : undefined;
  }

  // ---- usage ----

  addUsage(customerId: string, seconds: number): void {
    this.db
      .prepare(
        `INSERT INTO usage_counters (customer_id, period, seconds, calls) VALUES (?, ?, ?, 1)
         ON CONFLICT (customer_id, period) DO UPDATE SET seconds = seconds + ?, calls = calls + 1`,
      )
      .run(customerId, period(), Math.max(0, Math.round(seconds)), Math.max(0, Math.round(seconds)));
  }

  usageSeconds(customerId: string, p = period()): number {
    const row = this.db.prepare("SELECT seconds FROM usage_counters WHERE customer_id = ? AND period = ?").get(customerId, p) as { seconds: number } | undefined;
    return row?.seconds ?? 0;
  }

  usageHistory(customerId: string, months = 12): { period: string; minutes: number; calls: number }[] {
    const rows = this.db
      .prepare("SELECT period, seconds, calls FROM usage_counters WHERE customer_id = ? ORDER BY period DESC LIMIT ?")
      .all(customerId, months) as { period: string; seconds: number; calls: number }[];
    return rows.map((r) => ({ period: r.period, minutes: Math.round(r.seconds / 60), calls: r.calls }));
  }

  // ---- entitlement ----

  entitlementForClient(clientId: string): Entitlement | undefined {
    const customer = this.customerByClient(clientId);
    return customer ? this.entitlement(customer) : undefined;
  }

  entitlement(customer: Customer): Entitlement {
    const plan = customer.planId ? this.getPlan(customer.planId) : undefined;
    const minutesIncluded = customer.lifetime ? plan?.minutesIncluded ?? -1 : plan?.minutesIncluded ?? 0;
    const minutesUsed = Math.round(this.usageSeconds(customer.id) / 60);
    const maxPhones = plan?.maxPhones ?? 1;
    const base = { status: customer.status, minutesIncluded, minutesUsed, maxPhones };
    const license = this.licenseFor(customer.id);
    if (!license || license.status === "revoked") return { ...base, entitled: false, reason: "no_license", message: "License is not active." };
    if (license.status === "suspended") return { ...base, entitled: false, reason: "suspended", message: "License is suspended." };
    if (customer.status === "cancelled") return { ...base, entitled: false, reason: "cancelled", message: "Subscription cancelled." };
    if (customer.status === "suspended") return { ...base, entitled: false, reason: "suspended", message: "Account suspended." };
    if (!customer.lifetime) {
      const end = customer.status === "trialing" ? customer.trialEndsAt : customer.currentPeriodEnd;
      if (end && Date.now() > Date.parse(end) + (customer.status === "past_due" ? GRACE_DAYS * 86_400_000 : 0)) {
        return { ...base, entitled: false, reason: "expired", message: customer.status === "trialing" ? "Free trial ended." : "Subscription expired." };
      }
    }
    if (minutesIncluded >= 0 && minutesUsed >= minutesIncluded) {
      return { ...base, entitled: false, reason: "minutes_exhausted", message: "This month's call minutes are used up." };
    }
    return { ...base, entitled: true };
  }

  // ---- payments & audit ----

  recordPayment(p: { customerId?: string; amount: number; currency?: string; status: string; kind?: string; razorpayPaymentId?: string; razorpayOrderId?: string; meta?: unknown }): void {
    this.db
      .prepare(
        `INSERT INTO payments (id, customer_id, amount, currency, status, kind, razorpay_payment_id, razorpay_order_id, at, meta)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(randomUUID(), p.customerId ?? null, p.amount, p.currency ?? "INR", p.status, p.kind ?? null, p.razorpayPaymentId ?? null, p.razorpayOrderId ?? null, nowIso(), p.meta ? JSON.stringify(p.meta) : null);
  }

  payments(customerId?: string, limit = 100): Record<string, unknown>[] {
    const rows = customerId
      ? this.db.prepare("SELECT * FROM payments WHERE customer_id = ? ORDER BY at DESC LIMIT ?").all(customerId, limit)
      : this.db.prepare("SELECT * FROM payments ORDER BY at DESC LIMIT ?").all(limit);
    return (rows as unknown as PaymentRow[]).map((r) => ({
      id: r.id, customerId: r.customer_id, amount: r.amount, currency: r.currency, status: r.status,
      kind: r.kind, razorpayPaymentId: r.razorpay_payment_id, at: r.at,
    }));
  }

  audit(actor: string, action: string, target?: string, detail?: string): void {
    this.db.prepare("INSERT INTO audit_log (actor, action, target, detail, at) VALUES (?, ?, ?, ?, ?)").run(actor, action, target ?? null, detail ?? null, nowIso());
  }

  auditLog(limit = 200): { actor: string; action: string; target?: string; detail?: string; at: string }[] {
    const rows = this.db.prepare("SELECT actor, action, target, detail, at FROM audit_log ORDER BY id DESC LIMIT ?").all(limit) as {
      actor: string; action: string; target: string | null; detail: string | null; at: string;
    }[];
    return rows.map((r) => ({ actor: r.actor, action: r.action, target: r.target ?? undefined, detail: r.detail ?? undefined, at: r.at }));
  }

  /** Moves expired trials/subscriptions to the state the entitlement check expects. */
  reconcile(): void {
    const now = Date.now();
    for (const c of this.listCustomers()) {
      if (c.lifetime || c.status === "cancelled" || c.status === "suspended") continue;
      const end = c.status === "trialing" ? c.trialEndsAt : c.currentPeriodEnd;
      if (end && now > Date.parse(end) + GRACE_DAYS * 86_400_000 && c.status !== "past_due") {
        this.updateCustomer(c.id, { status: "past_due" });
      }
    }
  }
}

// ---- row mappers ----

interface PlanRow { id: string; name: string; price_monthly: number; price_yearly: number; setup_fee: number; currency: string; minutes_included: number; max_phones: number; trial_days: number; features: string | null; active: number; public: number; sort: number; razorpay_plan_monthly: string | null; razorpay_plan_yearly: string | null; }
interface CustomerRow { id: string; name: string; client_id: string | null; plan_id: string | null; billing_cycle: string; status: string; lifetime: number; trial_ends_at: string | null; current_period_end: string | null; razorpay_customer_id: string | null; razorpay_subscription_id: string | null; notes: string | null; created_at: string; updated_at: string; }
interface LicenseRow { key: string; customer_id: string; status: string; issued_at: string; activated_at: string | null; last_seen_at: string | null; last_device: string | null; }
interface PaymentRow { id: string; customer_id: string | null; amount: number; currency: string; status: string; kind: string | null; razorpay_payment_id: string | null; at: string; }

function toPlan(r: PlanRow): Plan {
  return {
    id: r.id, name: r.name, priceMonthly: r.price_monthly, priceYearly: r.price_yearly, setupFee: r.setup_fee,
    currency: r.currency, minutesIncluded: r.minutes_included, maxPhones: r.max_phones, trialDays: r.trial_days,
    features: r.features ? (JSON.parse(r.features) as string[]) : [], active: r.active === 1, public: r.public === 1,
    sort: r.sort, razorpayPlanMonthly: r.razorpay_plan_monthly ?? undefined, razorpayPlanYearly: r.razorpay_plan_yearly ?? undefined,
  };
}

function toCustomer(r: CustomerRow): Customer {
  return {
    id: r.id, name: r.name, clientId: r.client_id ?? undefined, planId: r.plan_id ?? undefined,
    billingCycle: r.billing_cycle as BillingCycle, status: r.status as CustomerStatus, lifetime: r.lifetime === 1,
    trialEndsAt: r.trial_ends_at ?? undefined, currentPeriodEnd: r.current_period_end ?? undefined,
    razorpayCustomerId: r.razorpay_customer_id ?? undefined, razorpaySubscriptionId: r.razorpay_subscription_id ?? undefined,
    notes: r.notes ?? undefined, createdAt: r.created_at, updatedAt: r.updated_at,
  };
}

function toLicense(r: LicenseRow): License {
  return { key: r.key, customerId: r.customer_id, status: r.status as License["status"], issuedAt: r.issued_at, activatedAt: r.activated_at ?? undefined, lastSeenAt: r.last_seen_at ?? undefined, lastDevice: r.last_device ?? undefined };
}

function slug(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 32) || randomUUID().slice(0, 8);
}
