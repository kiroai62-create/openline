import type { Express, Request, Response } from "express";
import { waChatUpdateSchema, waChatsQuerySchema, waNewChatSchema, waSendSchema, waSettingsSchema } from "../schemas.js";
import type { WhatsAppManager } from "./manager.js";

type ErrorBody = (code: string, message: string) => { error: { code: string; message: string } };

export interface WhatsAppRouteOptions {
  /** e.g. "/v1/me/whatsapp" (customer) or "/v1/whatsapp" (platform admin). */
  base: string;
  manager: WhatsAppManager;
  /** The business this request may manage; sends the error response itself when there is none. */
  scope(request: Request, response: Response): string | undefined;
  actor(request: Request): string;
  errorBody: ErrorBody;
  validationError(response: Response, issues: unknown): unknown;
  audit?(request: Request, action: string, scope: string, detail?: string): void;
}

/** Connection, automation settings and inbox routes for one kind of caller. */
export function registerWhatsAppRoutes(app: Express, o: WhatsAppRouteOptions): void {
  const { base, manager, errorBody } = o;
  const chatId = (request: Request) => Number.parseInt(String(request.params.id), 10);

  app.get(`${base}/status`, (request, response) => {
    const scope = o.scope(request, response);
    if (!scope) return;
    const days = Math.min(Math.max(Number(request.query.days) || 7, 1), 365);
    return response.json({
      ...manager.status(scope),
      settings: manager.settings(scope),
      stats: manager.store.stats(scope, new Date(Date.now() - days * 86_400_000).toISOString()),
    });
  });

  app.post(`${base}/connect`, async (request, response) => {
    const scope = o.scope(request, response);
    if (!scope) return;
    await manager.connect(scope);
    // Give WhatsApp a moment to hand out the first QR so the page can show it right away.
    for (let i = 0; i < 30 && manager.status(scope).state === "connecting"; i++) await new Promise((r) => setTimeout(r, 200));
    o.audit?.(request, "whatsapp.connect", scope);
    return response.json(manager.status(scope));
  });

  app.post(`${base}/logout`, async (request, response) => {
    const scope = o.scope(request, response);
    if (!scope) return;
    await manager.logout(scope);
    o.audit?.(request, "whatsapp.logout", scope);
    return response.json(manager.status(scope));
  });

  app.post(`${base}/restart`, async (request, response) => {
    const scope = o.scope(request, response);
    if (!scope) return;
    await manager.restart(scope);
    return response.json(manager.status(scope));
  });

  app.get(`${base}/settings`, (request, response) => {
    const scope = o.scope(request, response);
    if (!scope) return;
    return response.json({ settings: manager.settings(scope) });
  });

  app.put(`${base}/settings`, (request, response) => {
    const scope = o.scope(request, response);
    if (!scope) return;
    const parsed = waSettingsSchema.safeParse(request.body);
    if (!parsed.success) return o.validationError(response, parsed.error.issues);
    const settings = manager.updateSettings(scope, parsed.data);
    o.audit?.(request, "whatsapp.settings", scope);
    return response.json({ settings });
  });

  app.get(`${base}/chats`, (request, response) => {
    const scope = o.scope(request, response);
    if (!scope) return;
    const parsed = waChatsQuerySchema.safeParse(request.query);
    if (!parsed.success) return o.validationError(response, parsed.error.issues);
    return response.json({
      chats: manager.store.listContacts(scope, parsed.data.filter, parsed.data.q),
      counts: manager.store.counts(scope),
    });
  });

  app.get(`${base}/chats/:id`, (request, response) => {
    const scope = o.scope(request, response);
    if (!scope) return;
    const contact = manager.store.contact(scope, chatId(request));
    if (!contact) return response.status(404).json(errorBody("NOT_FOUND", "chat not found"));
    if (contact.unread && request.query.peek !== "1") manager.store.updateContact(scope, contact.id, { unread: 0 });
    return response.json({ chat: manager.store.contact(scope, contact.id), messages: manager.store.messages(contact.id) });
  });

  app.put(`${base}/chats/:id`, (request, response) => {
    const scope = o.scope(request, response);
    if (!scope) return;
    const parsed = waChatUpdateSchema.safeParse(request.body);
    if (!parsed.success) return o.validationError(response, parsed.error.issues);
    const patch = { ...parsed.data };
    // Turning the AI back on hands the chat back from staff.
    if (patch.aiEnabled === true && patch.needsHuman === undefined) patch.needsHuman = false;
    if (patch.status === "resolved" && patch.needsHuman === undefined) patch.needsHuman = false;
    const chat = manager.store.updateContact(scope, chatId(request), patch);
    if (!chat) return response.status(404).json(errorBody("NOT_FOUND", "chat not found"));
    return response.json({ chat });
  });

  app.delete(`${base}/chats/:id`, (request, response) => {
    const scope = o.scope(request, response);
    if (!scope) return;
    if (!manager.store.deleteContact(scope, chatId(request))) return response.status(404).json(errorBody("NOT_FOUND", "chat not found"));
    o.audit?.(request, "whatsapp.chat_delete", scope, String(chatId(request)));
    return response.status(204).end();
  });

  app.post(`${base}/chats/:id/messages`, async (request, response) => {
    const scope = o.scope(request, response);
    if (!scope) return;
    const parsed = waSendSchema.safeParse(request.body);
    if (!parsed.success) return o.validationError(response, parsed.error.issues);
    if (!manager.store.contact(scope, chatId(request))) return response.status(404).json(errorBody("NOT_FOUND", "chat not found"));
    try {
      const result = await manager.sendText(scope, { contactId: chatId(request) }, parsed.data.text, { source: "staff", sentBy: o.actor(request) });
      return response.status(201).json(result);
    } catch (error) {
      return response.status(409).json(errorBody("WHATSAPP_SEND_FAILED", (error as Error).message));
    }
  });

  app.post(`${base}/send`, async (request, response) => {
    const scope = o.scope(request, response);
    if (!scope) return;
    const parsed = waNewChatSchema.safeParse(request.body);
    if (!parsed.success) return o.validationError(response, parsed.error.issues);
    try {
      const result = await manager.sendText(scope, parsed.data.to, parsed.data.text, { source: "staff", sentBy: o.actor(request) });
      return response.status(201).json(result);
    } catch (error) {
      return response.status(409).json(errorBody("WHATSAPP_SEND_FAILED", (error as Error).message));
    }
  });
}
