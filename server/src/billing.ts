import { createHmac, timingSafeEqual } from "node:crypto";
import type { ConfigStore } from "./config.js";
import type { SaasStore, Plan, BillingCycle } from "./saas.js";

/**
 * Razorpay billing. Keys come from the environment (set in server/.env):
 *   RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET
 * The Key ID is public (used by checkout.js in the browser); the secret and webhook secret never
 * leave the server.
 *
 * Recurring billing uses Razorpay Subscriptions when a plan has a Razorpay plan id for the chosen
 * cycle; otherwise it falls back to a one-time Order that activates the plan for one period
 * (manual renewal). The real subscription lifecycle is driven by Razorpay webhooks.
 */
export interface BillingConfig {
  keyId?: string;
  keySecret?: string;
  webhookSecret?: string;
  apiBase: string;
}

export class Billing {
  constructor(
    private readonly saas: SaasStore,
    private readonly config: ConfigStore,
    private readonly env: BillingConfig,
  ) {}

  get configured(): boolean {
    return Boolean(this.env.keyId && this.env.keySecret);
  }

  get keyId(): string | undefined {
    return this.env.keyId;
  }

  private auth(): string {
    return "Basic " + Buffer.from(`${this.env.keyId}:${this.env.keySecret}`).toString("base64");
  }

  private async rzp(path: string, body: unknown): Promise<Record<string, unknown>> {
    const response = await fetch(`${this.env.apiBase}${path}`, {
      method: "POST",
      headers: { authorization: this.auth(), "content-type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(20_000),
    });
    const json = (await response.json().catch(() => ({}))) as Record<string, unknown>;
    if (!response.ok) {
      const err = json.error as { description?: string } | undefined;
      throw new Error(err?.description ?? `Razorpay HTTP ${response.status}`);
    }
    return json;
  }

  /**
   * Starts a purchase for a customer. Returns what the browser's Razorpay checkout needs, or
   * `{ manual: true }` when online payment isn't set up (admin must activate by hand).
   */
  async checkout(customerId: string, plan: Plan, cycle: "monthly" | "yearly"): Promise<Record<string, unknown>> {
    const customer = this.saas.getCustomer(customerId);
    if (!customer) throw new Error("customer not found");
    if (!this.configured) {
      return { manual: true, message: "Online payment isn't set up yet. Please contact us to activate this plan." };
    }
    const amount = cycle === "yearly" ? plan.priceYearly : plan.priceMonthly;
    const rzpPlan = cycle === "yearly" ? plan.razorpayPlanYearly : plan.razorpayPlanMonthly;
    const notes = { customerId, planId: plan.id, cycle };

    if (rzpPlan) {
      const sub = await this.rzp("/v1/subscriptions", {
        plan_id: rzpPlan,
        total_count: cycle === "yearly" ? 5 : 60,
        customer_notify: 1,
        notes,
      });
      // The plan changes only once Razorpay confirms the first charge (webhook or confirm).
      this.saas.recordPayment({ customerId, amount, status: "created", kind: "subscription", razorpayOrderId: String(sub.id), meta: { planId: plan.id, cycle } });
      return { subscriptionId: sub.id, keyId: this.env.keyId, description: `${plan.name} (${cycle})` };
    }

    const order = await this.rzp("/v1/orders", { amount, currency: plan.currency || "INR", notes });
    this.saas.recordPayment({ customerId, amount, status: "created", kind: "one_time", razorpayOrderId: String(order.id), meta: { planId: plan.id, cycle } });
    return { orderId: order.id, amount, keyId: this.env.keyId, description: `${plan.name} (${cycle})`, planId: plan.id, cycle };
  }

  /** Verifies the checkout result signature and activates one period for an Order payment. */
  confirm(customerId: string, payload: Record<string, string>): { ok: boolean } {
    const paymentId = payload.razorpay_payment_id;
    const orderId = payload.razorpay_order_id;
    const subId = payload.razorpay_subscription_id;
    const signature = payload.razorpay_signature;
    if (!paymentId || !signature || !this.env.keySecret) return { ok: false };
    const base = subId ? `${paymentId}|${subId}` : orderId ? `${orderId}|${paymentId}` : "";
    if (!base || !this.safeEqual(this.hmac(base, this.env.keySecret), signature)) return { ok: false };
    const pending = this.saas.pendingPayment(subId ?? orderId!);
    // The checkout must belong to this customer.
    if (!pending || pending.customerId !== customerId) {
      return { ok: this.saas.hasPayment(paymentId) }; // already handled by the webhook
    }
    if (this.saas.capturePayment(pending.id, paymentId)) {
      this.applyPurchase(customerId, pending.meta, subId ? { id: subId } : undefined);
    }
    return { ok: true };
  }

  /** Handles a Razorpay webhook. `raw` is the exact request body bytes. */
  webhook(raw: Buffer, signature: string | undefined): { ok: boolean; handled?: string } {
    if (!this.env.webhookSecret || !signature) return { ok: false };
    if (!this.safeEqual(this.hmac(raw.toString("utf8"), this.env.webhookSecret), signature)) return { ok: false };
    let event: RazorpayEvent;
    try {
      event = JSON.parse(raw.toString("utf8")) as RazorpayEvent;
    } catch {
      return { ok: false };
    }
    const sub = event.payload?.subscription?.entity;
    const payment = event.payload?.payment?.entity;
    // Who owns this? Our own records first (the pending checkout or the stored subscription), and
    // only then the notes we set when creating the subscription server-side.
    const pendingOrder = payment?.order_id ? this.saas.pendingPayment(payment.order_id) : undefined;
    const pendingSub = sub?.id ? this.saas.pendingPayment(sub.id) : undefined;
    const customerId =
      pendingOrder?.customerId ??
      pendingSub?.customerId ??
      (sub?.id ? this.saas.customerByRazorpaySub(sub.id)?.id : undefined) ??
      (sub?.notes?.customerId as string | undefined);
    if (!customerId || !this.saas.getCustomer(customerId)) return { ok: true, handled: "ignored" };

    // A subscription event for an older subscription (the customer has since switched plans) must
    // not change the current plan or status.
    const current = this.saas.getCustomer(customerId)!.razorpaySubscriptionId;
    if (sub?.id && event.event.startsWith("subscription.") && current && sub.id !== current && !pendingSub) {
      return { ok: true, handled: "stale_subscription" };
    }

    switch (event.event) {
      case "subscription.activated":
      case "subscription.charged":
      case "subscription.resumed": {
        const meta = { planId: sub?.notes?.planId as string | undefined, cycle: sub?.notes?.cycle as string | undefined };
        const pending = sub?.id ? this.saas.pendingPayment(sub.id) : undefined;
        if (payment?.id && this.saas.hasPayment(payment.id)) {
          // Already captured by confirm: just refresh the period from Razorpay.
          this.applyPurchase(customerId, meta, sub);
        } else if (pending && payment?.id) {
          this.saas.capturePayment(pending.id, payment.id, Number(payment.amount ?? pending.amount));
          this.applyPurchase(customerId, meta, sub);
        } else {
          this.applyPurchase(customerId, meta, sub);
          if (payment?.id) this.saas.recordPayment({ customerId, amount: Number(payment.amount ?? 0), status: "captured", kind: "subscription", razorpayPaymentId: payment.id });
        }
        break;
      }
      case "subscription.halted":
      case "subscription.pending":
        this.saas.updateCustomer(customerId, { status: "past_due" });
        break;
      case "subscription.cancelled":
      case "subscription.completed":
        this.saas.updateCustomer(customerId, { status: "cancelled" });
        break;
      case "payment.captured": {
        // One-time order paid (the browser may have closed before confirm ran).
        if (pendingOrder && payment?.id && this.saas.capturePayment(pendingOrder.id, payment.id, Number(payment.amount ?? pendingOrder.amount))) {
          this.applyPurchase(customerId, pendingOrder.meta);
        } else {
          return { ok: true, handled: "ignored" };
        }
        break;
      }
      default:
        return { ok: true, handled: "ignored" };
    }
    this.saas.audit("razorpay", event.event, customerId);
    return { ok: true, handled: event.event };
  }

  /** Switches the customer to the bought plan and starts/extends the paid period. */
  private applyPurchase(customerId: string, meta: { planId?: string; cycle?: string }, sub?: RazorpaySubscription): void {
    const customer = this.saas.getCustomer(customerId);
    if (!customer) return;
    const cycle = (meta.cycle === "yearly" || meta.cycle === "monthly" ? meta.cycle : customer.billingCycle) as BillingCycle;
    const planId = meta.planId && this.saas.getPlan(meta.planId) ? meta.planId : customer.planId;
    let end: string;
    if (sub?.current_end != null) {
      end = new Date(Number(sub.current_end) * 1000).toISOString();
    } else {
      // One-time payment: extend from the current end if still in the future.
      const from = Math.max(Date.now(), customer.currentPeriodEnd ? Date.parse(customer.currentPeriodEnd) : 0);
      end = new Date(from + (cycle === "yearly" ? 365 : 30) * 86_400_000).toISOString();
    }
    const previousSub = customer.razorpaySubscriptionId;
    this.saas.updateCustomer(customerId, {
      status: "active", planId, billingCycle: cycle, currentPeriodEnd: end,
      ...(sub?.id ? { razorpaySubscriptionId: sub.id } : {}),
    });
    // Switched to a new subscription: stop billing the old one.
    if (sub?.id && previousSub && previousSub !== sub.id) void this.cancelSubscription(previousSub);
  }

  private async cancelSubscription(id: string): Promise<void> {
    try {
      await this.rzp(`/v1/subscriptions/${encodeURIComponent(id)}/cancel`, { cancel_at_cycle_end: 0 });
      this.saas.audit("razorpay", "subscription.cancel_old", id);
    } catch (error) {
      console.error(`could not cancel old subscription ${id}: ${error instanceof Error ? error.message : error}`);
    }
  }

  private hmac(data: string, secret: string): string {
    return createHmac("sha256", secret).update(data).digest("hex");
  }

  private safeEqual(a: string, b: string): boolean {
    const x = Buffer.from(a);
    const y = Buffer.from(b);
    return x.length === y.length && timingSafeEqual(x, y);
  }
}

interface RazorpaySubscription {
  id?: string;
  current_end?: number;
  notes?: Record<string, unknown>;
}
interface RazorpayPayment {
  id?: string;
  amount?: number;
  order_id?: string;
  notes?: Record<string, unknown>;
}
interface RazorpayEvent {
  event: string;
  payload?: {
    subscription?: { entity?: RazorpaySubscription };
    payment?: { entity?: RazorpayPayment };
  };
}
