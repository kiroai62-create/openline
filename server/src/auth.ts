import { randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import type { IncomingMessage } from "node:http";
import type { NextFunction, Request, Response } from "express";
import type { Database } from "./db.js";
import { hashToken } from "./state.js";

const SESSION_COOKIE = "gw_session";
const SESSION_TTL_MS = 30 * 24 * 60 * 60_000;
const MAX_LOGIN_FAILURES_PER_MINUTE = 10;

export interface UserInfo {
  id: string;
  email: string;
  createdAt: string;
  role: "admin" | "customer";
  customerId?: string;
}

export interface ApiKeyInfo {
  id: string;
  name: string;
  prefix: string;
  createdAt: string;
  lastUsedAt?: string;
  revokedAt?: string;
}

function secureEquals(a: string, b: string): boolean {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
}

export function extractApiKey(request: { headers: IncomingMessage["headers"] }, url?: URL): string | undefined {
  const direct = request.headers["x-api-key"];
  if (typeof direct === "string" && direct) return direct;
  const authorization = request.headers.authorization;
  if (authorization?.toLowerCase().startsWith("bearer ")) return authorization.slice(7).trim();
  return url?.searchParams.get("apiKey") ?? undefined;
}

export function isApiKeyValid(provided: string | undefined, expected: string): boolean {
  return Boolean(provided && secureEquals(provided, expected));
}

function readCookie(header: string | undefined, name: string): string | undefined {
  for (const part of header?.split(";") ?? []) {
    const [key, ...rest] = part.trim().split("=");
    if (key === name) return decodeURIComponent(rest.join("="));
  }
  return undefined;
}

/**
 * Who may use the API: the root key from server/.env, API keys created in the console (stored as
 * hashes), and console sessions (password login, httpOnly cookie).
 */
export class AuthService {
  /** Failed logins per email; one attacker can't lock everyone else out. */
  private readonly loginFailures = new Map<string, number[]>();

  constructor(
    private readonly db: Database,
    private readonly rootApiKey: string,
  ) {}

  // ---- API keys ----

  verifyApiKey(key: string | undefined): boolean {
    if (!key) return false;
    if (isApiKeyValid(key, this.rootApiKey)) return true;
    const row = this.db
      .prepare("SELECT id, last_used_at FROM api_keys WHERE key_hash = ? AND revoked_at IS NULL")
      .get(hashToken(key)) as { id: string; last_used_at: string | null } | undefined;
    if (!row) return false;
    const now = Date.now();
    if (!row.last_used_at || now - Date.parse(row.last_used_at) > 60_000) {
      this.db.prepare("UPDATE api_keys SET last_used_at = ? WHERE id = ?").run(new Date(now).toISOString(), row.id);
    }
    return true;
  }

  createApiKey(name: string): ApiKeyInfo & { key: string } {
    const key = `gw_${randomBytes(24).toString("base64url")}`;
    const info = { id: randomUUID(), name, prefix: key.slice(0, 7), createdAt: new Date().toISOString() };
    this.db
      .prepare("INSERT INTO api_keys (id, name, prefix, key_hash, created_at) VALUES (?, ?, ?, ?, ?)")
      .run(info.id, info.name, info.prefix, hashToken(key), info.createdAt);
    return { ...info, key };
  }

  listApiKeys(): ApiKeyInfo[] {
    const rows = this.db
      .prepare("SELECT * FROM api_keys WHERE revoked_at IS NULL ORDER BY created_at DESC")
      .all() as { id: string; name: string; prefix: string; created_at: string; last_used_at: string | null }[];
    return rows.map((r) => ({
      id: r.id,
      name: r.name,
      prefix: r.prefix,
      createdAt: r.created_at,
      lastUsedAt: r.last_used_at ?? undefined,
    }));
  }

  revokeApiKey(id: string): boolean {
    const result = this.db
      .prepare("UPDATE api_keys SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")
      .run(new Date().toISOString(), id);
    return Number(result.changes) > 0;
  }

  // ---- accounts and sessions ----

  get setupRequired(): boolean {
    return !this.db.prepare("SELECT 1 FROM users LIMIT 1").get();
  }

  /**
   * Creates the first account. On this PC (localhost) email and password are enough; through a
   * tunnel the setup key from server/.env is also required, so a stranger can't claim the console.
   */
  setup(input: { email: string; password: string; setupKey?: string }, local: boolean): "ok" | "invalid_key" | "already_set" {
    if (!this.setupRequired) return "already_set";
    if (!local && !isApiKeyValid(input.setupKey, this.rootApiKey)) return "invalid_key";
    this.addUser(input.email, input.password);
    return "ok";
  }

  /** Platform owner (admin) or a paying customer's login. */
  addUser(email: string, password: string, role: "admin" | "customer" = "admin", customerId?: string): UserInfo | "exists" {
    const user: UserInfo = { id: randomUUID(), email: email.trim().toLowerCase(), createdAt: new Date().toISOString(), role, customerId };
    try {
      this.db
        .prepare("INSERT INTO users (id, email, password_hash, created_at, role, customer_id) VALUES (?, ?, ?, ?, ?, ?)")
        .run(user.id, user.email, hashPassword(password), user.createdAt, role, customerId ?? null);
    } catch {
      return "exists";
    }
    return user;
  }

  /** Changes a user's password without knowing the old one (admin resetting a customer's login). */
  setPassword(userId: string, password: string): boolean {
    const changed = Number(this.db.prepare("UPDATE users SET password_hash = ? WHERE id = ?").run(hashPassword(password), userId).changes);
    if (changed) this.db.prepare("DELETE FROM sessions WHERE user_id = ?").run(userId);
    return changed > 0;
  }

  userByCustomer(customerId: string): UserInfo | undefined {
    const r = this.db.prepare("SELECT id, email, created_at, role, customer_id FROM users WHERE customer_id = ? LIMIT 1").get(customerId) as UserRow | undefined;
    return r ? toUser(r) : undefined;
  }

  getUser(id: string): UserInfo | undefined {
    const r = this.db.prepare("SELECT id, email, created_at, role, customer_id FROM users WHERE id = ?").get(id) as UserRow | undefined;
    return r ? toUser(r) : undefined;
  }

  /** Starts a session for a user without a password: admin "log in as customer". */
  issueSessionFor(userId: string, ttlMs = 2 * 60 * 60_000): { token: string; expiresAt: Date } | undefined {
    if (!this.getUser(userId)) return undefined;
    const now = Date.now();
    const token = randomBytes(32).toString("base64url");
    const expiresAt = new Date(now + ttlMs);
    this.db.prepare("INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)").run(hashToken(token), userId, new Date(now).toISOString(), expiresAt.toISOString());
    return { token, expiresAt };
  }

  adminExists(): boolean {
    return Boolean(this.db.prepare("SELECT 1 FROM users WHERE role = 'admin' LIMIT 1").get());
  }

  listUsers(): UserInfo[] {
    return (this.db.prepare("SELECT id, email, created_at, role, customer_id FROM users WHERE role = 'admin' ORDER BY created_at").all() as unknown as UserRow[]).map(toUser);
  }

  /** Never removes the last admin. */
  removeUser(id: string): "ok" | "not_found" | "last" {
    const u = this.getUser(id);
    if (u?.role === "admin" && this.listUsers().length <= 1) return "last";
    if (Number(this.db.prepare("DELETE FROM users WHERE id = ?").run(id).changes) === 0) return "not_found";
    this.db.prepare("DELETE FROM sessions WHERE user_id = ?").run(id);
    return "ok";
  }

  /** Signs out the user's other browsers. */
  changePassword(userId: string, current: string, next: string, keepToken?: string): boolean {
    const row = this.db.prepare("SELECT password_hash FROM users WHERE id = ?").get(userId) as
      | { password_hash: string }
      | undefined;
    if (!row || !verifyPassword(current, row.password_hash)) return false;
    this.db.prepare("UPDATE users SET password_hash = ? WHERE id = ?").run(hashPassword(next), userId);
    this.db
      .prepare("DELETE FROM sessions WHERE user_id = ? AND token_hash != ?")
      .run(userId, keepToken ? hashToken(keepToken) : "");
    return true;
  }

  /** Returns a new session token, or why not. */
  login(email: string, password: string): { token: string; expiresAt: Date } | "invalid" | "throttled" {
    const now = Date.now();
    const who = email.trim().toLowerCase();
    const recent = (this.loginFailures.get(who) ?? []).filter((at) => now - at < 60_000);
    this.loginFailures.set(who, recent);
    if (recent.length >= MAX_LOGIN_FAILURES_PER_MINUTE) return "throttled";
    if (this.loginFailures.size > 10_000) this.loginFailures.clear();
    const row = this.db.prepare("SELECT id, password_hash FROM users WHERE email = ?").get(email.trim().toLowerCase()) as
      | { id: string; password_hash: string }
      | undefined;
    if (!row || !verifyPassword(password, row.password_hash)) {
      recent.push(now);
      return "invalid";
    }
    this.loginFailures.delete(who);
    const token = randomBytes(32).toString("base64url");
    const expiresAt = new Date(now + SESSION_TTL_MS);
    this.db.prepare("DELETE FROM sessions WHERE expires_at < ?").run(new Date(now).toISOString());
    this.db
      .prepare("INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)")
      .run(hashToken(token), row.id, new Date(now).toISOString(), expiresAt.toISOString());
    return { token, expiresAt };
  }

  logout(token: string | undefined): void {
    if (token) this.db.prepare("DELETE FROM sessions WHERE token_hash = ?").run(hashToken(token));
  }

  /** The signed-in account for a session token. */
  sessionUser(token: string | undefined): UserInfo | undefined {
    if (!token) return undefined;
    const row = this.db
      .prepare(
        `SELECT u.id, u.email, u.created_at, u.role, u.customer_id, s.expires_at FROM sessions s JOIN users u ON u.id = s.user_id
         WHERE s.token_hash = ?`,
      )
      .get(hashToken(token)) as (UserRow & { expires_at: string }) | undefined;
    if (!row || Date.parse(row.expires_at) <= Date.now()) return undefined;
    return toUser(row);
  }

  verifySession(token: string | undefined): boolean {
    return Boolean(this.sessionUser(token));
  }

  // ---- request helpers ----

  sessionToken(request: { headers: IncomingMessage["headers"] }): string | undefined {
    return readCookie(request.headers.cookie, SESSION_COOKIE);
  }

  /** API key (header, bearer or ?apiKey=) or console session cookie. Used for HTTP and WebSockets. */
  /** API key or an admin session. Customer sessions are excluded (live call audio, admin APIs). */
  isAdminAuthorized(request: { headers: IncomingMessage["headers"] }, url?: URL): boolean {
    if (this.verifyApiKey(extractApiKey(request, url))) return true;
    return this.sessionUser(this.sessionToken(request))?.role === "admin";
  }

  isAuthorized(request: { headers: IncomingMessage["headers"] }, url?: URL): boolean {
    return this.verifyApiKey(extractApiKey(request, url)) || this.verifySession(this.sessionToken(request));
  }

  /**
   * Opened on this machine directly, not through a tunnel. Tailscale Funnel and Cloudflare Tunnel
   * also connect from loopback but always add forwarding headers, which a visitor can't remove.
   */
  isLocalRequest(request: { headers: IncomingMessage["headers"]; socket: { remoteAddress?: string } }): boolean {
    const address = request.socket.remoteAddress ?? "";
    const loopback = address === "127.0.0.1" || address === "::1" || address === "::ffff:127.0.0.1";
    const h = request.headers;
    const proxied = Boolean(
      h["x-forwarded-for"] || h["forwarded"] || h["x-real-ip"] || h["cf-connecting-ip"] || h["tailscale-funnel-request"],
    );
    return loopback && !proxied;
  }

  middleware() {
    return (request: Request, response: Response, next: NextFunction): void => {
      if (!this.isAuthorized(request)) {
        response.status(401).json({ error: { code: "UNAUTHORIZED", message: "invalid API key or session" } });
        return;
      }
      next();
    };
  }

  setSessionCookie(request: Request, response: Response, token: string, expiresAt: Date): void {
    response.cookie(SESSION_COOKIE, token, {
      httpOnly: true,
      sameSite: "strict",
      secure: request.secure,
      expires: expiresAt,
      path: "/",
    });
  }

  clearSessionCookie(response: Response): void {
    response.clearCookie(SESSION_COOKIE, { path: "/" });
  }
}

interface UserRow { id: string; email: string; created_at: string; role: string; customer_id: string | null; }
function toUser(r: UserRow): UserInfo {
  return { id: r.id, email: r.email, createdAt: r.created_at, role: (r.role as "admin" | "customer") ?? "admin", customerId: r.customer_id ?? undefined };
}

function hashPassword(password: string): string {
  const salt = randomBytes(16);
  return `scrypt$${salt.toString("base64")}$${scryptSync(password, salt, 32).toString("base64")}`;
}

function verifyPassword(password: string, stored: string): boolean {
  const [scheme, salt, hash] = stored.split("$");
  if (scheme !== "scrypt" || !salt || !hash) return false;
  const expected = Buffer.from(hash, "base64");
  const actual = scryptSync(password, Buffer.from(salt, "base64"), expected.length);
  return timingSafeEqual(actual, expected);
}
