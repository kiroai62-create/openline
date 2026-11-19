---
title: Built-in AI assistant
description: A Gemini Live voice assistant that answers calls for you and takes messages.
---

The gateway includes a ready-made assistant on Google's Gemini Live API. It's a personal
receptionist: it answers, says it's your assistant, asks who's calling and why, offers to take
a message, and hangs up politely. For anything custom, use
[your own agent](../agent-streams/) instead.

## Turn it on

1. Get a Gemini API key at aistudio.google.com.
2. Console **Settings → AI assistant**: paste the key and save. (Or `GEMINI_API_KEY` in
   `server/.env`.)
3. Console **Playground → Assistant**: set **Answers on behalf of** to your name, optionally fill
   **About you**, and turn on **Answer incoming calls for me**.

**About you** is what the assistant may tell callers ("Alex is travelling until Monday; for the
bike service, he'll pick it up Saturday"). Write it in the third person and include only what
you're happy for any caller to hear.

## Receptionist for a business

Set **Role** to **Receptionist** to have the assistant answer for a clinic, hospital or shop,
24 hours a day. It introduces itself as the business's virtual assistant and answers questions
from your notes. When a caller wants an appointment, it sends them the booking link on WhatsApp.

1. Console **Playground → Assistant**: choose **Receptionist**, enter the **Business name** and
   the **Booking link** (your website's booking page).
2. In **Everything the receptionist should know**, paste what front-desk staff would know:
   services and fees, timings, address and landmark, doctors and their days, the emergency
   number. Up to 20,000 characters, so the text of your website fits. The receptionist won't
   guess anything that isn't in there. It takes a message and says the staff will confirm.
3. Console **Settings → WhatsApp**: enter the address and API secret of your WhatsApp gateway
   and press **Send test**. (Or `WHATSAPP_GATEWAY_URL` / `WHATSAPP_API_SECRET` in
   `server/.env`.)

The gateway must accept `POST /send` with an `X-API-KEY` header and a JSON body
`{"jid": "919876543210", "text": "..."}`; a Baileys-based WhatsApp server works as is. Local
10-digit numbers get the **Country code** (default `91`) added in front.

On the call, the receptionist confirms the caller's number by its last four digits. If the caller
wants the link on a different number, or their number is hidden, it asks for one. The message
template can use `{name}`, `{business}` and `{link}`. If WhatsApp isn't set up or sending fails,
the receptionist takes the caller's name and preferred day as a message instead.

It never diagnoses or prescribes. For emergencies it tells the caller to come in or call the
emergency number from your notes.

## Several AI providers and automatic failover

**Settings → AI providers & failover** holds backup providers. Calls use Gemini Live first: the
key from Settings, then any backup Gemini keys, in order. When none is usable, calls run on a
speech pipeline (speech-to-text, chat model, text-to-speech), each step with its own ordered list:
Gemini, Groq, Cloudflare Workers AI, OpenRouter, Cerebras, Mistral, Sarvam AI or any
OpenAI-compatible API.

A provider that returns a rate limit, quota, auth or server error is put on cooldown (90 s for
rate limits, 1 h for daily quotas, 10 min for bad keys, 20 s otherwise) and the next one is used.
If the engine fails mid-call, the next engine takes over with the conversation so far. Status is
at `GET /v1/ai/providers`; save the list with `PUT /v1/ai/providers`. Keys are never returned.

## On a single call

```bash
curl -X POST $VAANIFLOW_URL/v1/calls/$CALL_ID/agent -H "Authorization: Bearer $VAANIFLOW_KEY"   # hand over
curl -X DELETE $VAANIFLOW_URL/v1/calls/$CALL_ID/agent -H "Authorization: Bearer $VAANIFLOW_KEY" # take back
curl $VAANIFLOW_URL/v1/calls/$CALL_ID/agent -H "Authorization: Bearer $VAANIFLOW_KEY"           # status + live transcript
```

## Messages

When a caller leaves a message, it's saved with their name, reason, callback number and urgency:

```bash
curl $VAANIFLOW_URL/v1/agent/messages -H "Authorization: Bearer $VAANIFLOW_KEY"
```

The full conversation is at `GET /v1/calls/{id}/transcript`.

## Settings

`GET /v1/agent` / `PUT /v1/agent` with `autoAnswer`, `ownerName`, `about`, `voice` (a Gemini
voice name such as `Kore`, `Puck` or `Charon`), `mode` (`message` or `receptionist`),
`businessName` and `bookingUrl`. The model can be changed in Settings.

`POST /v1/whatsapp/test` with `{"to": "9876543210"}` sends the booking message to a number, to
check the WhatsApp setup.
