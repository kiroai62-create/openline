# VaaniFlow: enterprise plan

Status: proposal, waiting for the owner's OK on scope and order.

## 1. Where the platform is today (checked in code)

| Area | Today | Gap |
|---|---|---|
| Customer AI keys | Customer can save only a **Gemini** key (`/v1/me/business`, "Your Gemini key" panel). Groq, Cloudflare, OpenRouter etc. exist only on the admin's AI providers page. | Customers need their own provider list with every type, a Test button and failover order. |
| WhatsApp engine | Built into the server (`server/src/whatsapp/`), written to replace the original 32 Teeth gateway + PHP. | Behaviour differs from the original (see §3). The original PHP AI logic (`incoming.php`, `config.php` …) is **not in any repo**, only `index.js` and `chats.php`. |
| Admin view of a customer | Customer modal shows status, plan, licence, minutes. "Log in as" replaces the admin session. Admin WhatsApp page can pick a business. | No single customer profile with calls, transcripts, chats, business info, phones, payments, usage, audit. No safe "return to admin". |
| Login page | Left art panel is always dark; no theme toggle on the sign-in page. | Follow light/dark properly and add a toggle. |
| Web UI | Redesigned styles only; no features were removed (diff since `e1b83fa` is CSS, fonts, logo). Automated check at 320–1920 px found no page wider than the screen. | Owner reports breakage on real devices: needs a per-page pass on real phone sizes, tables to cards on mobile, long text, empty and error states. |
| Phone app | Google Phone style UI, AI tab, call screen. | Closer Google Dialer match (call screen layout, incoming full-screen and heads-up notification, call history detail, contact detail, settings), and check incoming calls work out of the box. |

## 2. What similar platforms offer (research, Nov 2026)

- AI receptionists ([Retell](https://www.retellai.com/blog/best-ai-voice-platforms-virtual-receptionists), Synthflow, Smith.ai, Goodcall, PolyAI): caller lookup in CRM by number, live tools (calendar, order check, payment link), warm transfer to a human with the transcript, post-call analytics, call flows per business.
- WhatsApp platforms in India ([Wati](https://www.wati.io/en/blog/whatsapp-business-messaging-platform-for-marketing-sales-and-support-in-a-single-inbox/), [AiSensy](https://m.aisensy.com/blog/whatsapp-api-providers/), [Gallabox](https://gallabox.com/whatsapp-business-api), Interakt): shared team inbox, AI agents with "bring your own AI", broadcasts and campaigns, no-code flows, contact CRM with tags, Shopify and other integrations, analytics.

## 3. WhatsApp: match the original

Restore the original behaviour as the default:

1. Reply delay default **5–8 s** (now 2–5 s); typing shown during the whole wait, as before.
2. Fallback message default **"Dhanyavaad! Abhi system busy hai…"** sent when the AI fails (now empty by default, sent without delay).
3. Media labels in Hinglish (`[Voice message bheja]`, `[Photo bheji]` …).
4. Logged-out number: reconnect and show a new QR automatically (now waits for a click).
5. Health/status endpoint and event log (qr, connected, disconnected, heartbeat).
6. Per-chat language and token counts like the original inbox.
7. Keep the new extras that the original lacked (inbox, staff take-over, quick replies, booking links).

**Needed from the owner:** the original PHP files (`api/incoming.php`, `config.php`, the AI prompt) so the reply logic matches exactly.

## 4. Build order (each phase tested and pushed to `main`)

1. **Customer AI providers**: customers add Gemini, Groq, Cloudflare, OpenRouter, Cerebras, Mistral, Sarvam, OpenAI-compatible keys, with Test and order; their calls and chats use their keys first, then the platform's.
2. **WhatsApp parity** (§3).
3. **Admin: Customer 360**: one page per customer with overview, calls + transcripts, WhatsApp chats, business info, phones, plan/payments, usage, audit, notes; "view as customer" that returns to admin.
4. **Web UI hardening**: login theme, every page checked on 360/390/768/1024/1440 px in both themes, tables become cards on phones.
5. **Phone app**: closer Google Dialer match, incoming call heads-up and full-screen, call detail, contact detail, settings; incoming calls checked on Android 10–16.
6. **Growth features** (pick): WhatsApp broadcasts/campaigns, contact CRM with tags and segments, calendar booking (Google Calendar), call transfer to staff, outbound reminder calls, team members and roles per customer, webhooks/Zapier, Shopify.

Every phase: server tests + new tests for the feature, browser checks on all sizes, Android unit and screenshot tests, CI green.
