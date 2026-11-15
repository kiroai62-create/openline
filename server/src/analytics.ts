import type { Database } from "./db.js";

/**
 * Call analytics for the console dashboard: totals, daily and hourly charts, per-client and
 * per-phone breakdowns, and the call list. Days and hours are counted in the viewer's time zone.
 *
 * Definitions:
 * - answered: the call connected (answered_at is set).
 * - missed: an incoming call that ended without connecting.
 * - AI handled: the AI assistant spoke on the call (a transcript was saved).
 * - booking links: WhatsApp messages the receptionist sent (failed attempts counted apart).
 */
export interface AnalyticsQuery {
  days: number;
  /** "all", "none" (phones without a client) or a client id. */
  clientId: string;
  /** Viewer's offset from UTC in minutes, e.g. 330 for India. */
  tzOffset: number;
}

interface Totals {
  calls: number;
  incoming: number;
  outgoing: number;
  answered: number;
  missed: number;
  aiHandled: number;
  uniqueCallers: number;
  talkSeconds: number;
  avgSeconds: number;
  messages: number;
  bookingLinks: number;
  bookingFailed: number;
}

const DAY_MS = 86_400_000;

export class Analytics {
  constructor(private readonly db: Database) {}

  report(q: AnalyticsQuery) {
    const now = Date.now();
    const offsetMs = q.tzOffset * 60_000;
    const startOfToday = Math.floor((now + offsetMs) / DAY_MS) * DAY_MS - offsetMs;
    const since = new Date(startOfToday - (q.days - 1) * DAY_MS).toISOString();
    const prevSince = new Date(startOfToday - (2 * q.days - 1) * DAY_MS).toISOString();
    const until = new Date(now + 60_000).toISOString();
    const shift = `${q.tzOffset >= 0 ? "+" : ""}${q.tzOffset} minutes`;

    return {
      range: { days: q.days, since, until, tzOffset: q.tzOffset },
      totals: this.totals(q.clientId, since, until),
      previous: this.totals(q.clientId, prevSince, since),
      daily: this.daily(q.clientId, since, until, shift, startOfToday, q.days, offsetMs),
      hourly: this.hourly(q.clientId, since, until, shift),
      byClient: this.byClient(since, until),
      phones: this.phones(q.clientId, since, new Date(startOfToday).toISOString()),
      calls: this.calls(q.clientId, since, until),
    };
  }

  private where(clientId: string, alias = "c"): { sql: string; params: string[] } {
    if (clientId === "all") return { sql: "", params: [] };
    if (clientId === "none") return { sql: ` AND ${alias}.client_id IS NULL`, params: [] };
    return { sql: ` AND ${alias}.client_id = ?`, params: [clientId] };
  }

  private totals(clientId: string, since: string, until: string): Totals {
    const w = this.where(clientId);
    const row = this.db
      .prepare(
        `SELECT
           COUNT(*) AS calls,
           SUM(c.direction = 'incoming') AS incoming,
           SUM(c.direction = 'outgoing') AS outgoing,
           SUM(c.answered_at IS NOT NULL) AS answered,
           SUM(${MISSED}) AS missed,
           SUM(EXISTS (SELECT 1 FROM transcripts t WHERE t.call_id = c.id)) AS aiHandled,
           COUNT(DISTINCT CASE WHEN c.direction = 'incoming' AND c.remote_number != 'unknown' THEN c.remote_number END) AS uniqueCallers,
           SUM(${DURATION}) AS talkSeconds,
           AVG(${DURATION}) AS avgSeconds,
           SUM(EXISTS (SELECT 1 FROM messages m WHERE m.call_id = c.id)) AS messages
         FROM calls c WHERE c.created_at >= ? AND c.created_at < ?${w.sql}`,
      )
      .get(since, until, ...w.params) as Record<string, number | null>;
    const sw = this.where(clientId, "s");
    const sends = this.db
      .prepare(
        `SELECT SUM(s.ok = 1) AS ok, SUM(s.ok = 0) AS failed FROM whatsapp_sends s
         WHERE s.at >= ? AND s.at < ?${sw.sql}`,
      )
      .get(since, until, ...sw.params) as { ok: number | null; failed: number | null };
    const n = (v: number | null | undefined) => Math.round(Number(v ?? 0));
    return {
      calls: n(row.calls),
      incoming: n(row.incoming),
      outgoing: n(row.outgoing),
      answered: n(row.answered),
      missed: n(row.missed),
      aiHandled: n(row.aiHandled),
      uniqueCallers: n(row.uniqueCallers),
      talkSeconds: n(row.talkSeconds),
      avgSeconds: n(row.avgSeconds),
      messages: n(row.messages),
      bookingLinks: n(sends.ok),
      bookingFailed: n(sends.failed),
    };
  }

  private daily(clientId: string, since: string, until: string, shift: string, startOfToday: number, days: number, offsetMs: number) {
    const w = this.where(clientId);
    const rows = this.db
      .prepare(
        `SELECT date(c.created_at, ?) AS day, COUNT(*) AS calls,
           SUM(c.answered_at IS NOT NULL) AS answered, SUM(${MISSED}) AS missed
         FROM calls c WHERE c.created_at >= ? AND c.created_at < ?${w.sql} GROUP BY day`,
      )
      .all(shift, since, until, ...w.params) as { day: string; calls: number; answered: number; missed: number }[];
    const sw = this.where(clientId, "s");
    const sends = this.db
      .prepare(
        `SELECT date(s.at, ?) AS day, COUNT(*) AS n FROM whatsapp_sends s
         WHERE s.ok = 1 AND s.at >= ? AND s.at < ?${sw.sql} GROUP BY day`,
      )
      .all(shift, since, until, ...sw.params) as { day: string; n: number }[];
    const byDay = new Map(rows.map((r) => [r.day, r]));
    const sendsByDay = new Map(sends.map((r) => [r.day, r.n]));
    const out = [];
    for (let i = days - 1; i >= 0; i--) {
      const day = new Date(startOfToday - i * DAY_MS + offsetMs).toISOString().slice(0, 10);
      const r = byDay.get(day);
      out.push({ day, calls: r?.calls ?? 0, answered: r?.answered ?? 0, missed: r?.missed ?? 0, bookingLinks: sendsByDay.get(day) ?? 0 });
    }
    return out;
  }

  private hourly(clientId: string, since: string, until: string, shift: string) {
    const w = this.where(clientId);
    const rows = this.db
      .prepare(
        `SELECT CAST(strftime('%H', c.created_at, ?) AS INTEGER) AS hour, COUNT(*) AS calls
         FROM calls c WHERE c.created_at >= ? AND c.created_at < ?${w.sql} GROUP BY hour`,
      )
      .all(shift, since, until, ...w.params) as { hour: number; calls: number }[];
    const counts = Array.from({ length: 24 }, () => 0);
    for (const r of rows) counts[r.hour] = r.calls;
    return counts;
  }

  private byClient(since: string, until: string) {
    const rows = this.db
      .prepare(
        `SELECT c.client_id AS clientId, cl.name AS name, COUNT(*) AS calls,
           SUM(c.answered_at IS NOT NULL) AS answered, SUM(${MISSED}) AS missed,
           SUM(${DURATION}) AS talkSeconds,
           SUM(EXISTS (SELECT 1 FROM messages m WHERE m.call_id = c.id)) AS messages,
           (SELECT COUNT(*) FROM whatsapp_sends s WHERE s.ok = 1 AND s.at >= ? AND s.at < ?
              AND s.client_id IS c.client_id) AS bookingLinks
         FROM calls c LEFT JOIN clients cl ON cl.id = c.client_id
         WHERE c.created_at >= ? AND c.created_at < ?
         GROUP BY c.client_id ORDER BY calls DESC`,
      )
      .all(since, until, since, until) as Record<string, unknown>[];
    return rows.map((r) => ({ ...r, talkSeconds: Math.round(Number(r.talkSeconds ?? 0)) }));
  }

  private phones(clientId: string, since: string, today: string) {
    const w = this.where(clientId, "d");
    return this.db
      .prepare(
        `SELECT d.id, COUNT(c.id) AS calls, SUM(c.created_at >= ?) AS callsToday,
           SUM(${MISSED}) AS missed, MAX(c.created_at) AS lastCallAt
         FROM devices d LEFT JOIN calls c ON c.device_id = d.id AND c.created_at >= ?
         WHERE 1 = 1${w.sql} GROUP BY d.id`,
      )
      .all(today, since, ...w.params) as { id: string; calls: number; callsToday: number | null; missed: number | null; lastCallAt: string | null }[];
  }

  private calls(clientId: string, since: string, until: string) {
    const w = this.where(clientId);
    return this.db
      .prepare(
        `SELECT c.id, c.device_id AS deviceId, c.client_id AS clientId, cl.name AS clientName,
           c.remote_number AS remoteNumber, c.direction, c.state, c.created_at AS createdAt,
           c.answered_at AS answeredAt, c.ended_at AS endedAt,
           CAST(${DURATION} AS INTEGER) AS seconds,
           EXISTS (SELECT 1 FROM transcripts t WHERE t.call_id = c.id) AS ai,
           EXISTS (SELECT 1 FROM messages m WHERE m.call_id = c.id) AS message,
           EXISTS (SELECT 1 FROM whatsapp_sends s WHERE s.call_id = c.id AND s.ok = 1) AS booking,
           ${MISSED} AS missed
         FROM calls c LEFT JOIN clients cl ON cl.id = c.client_id
         WHERE c.created_at >= ? AND c.created_at < ?${w.sql}
         ORDER BY c.created_at DESC LIMIT 500`,
      )
      .all(since, until, ...w.params)
      .map((r) => {
        const row = r as Record<string, unknown>;
        return { ...row, ai: row.ai === 1, message: row.message === 1, booking: row.booking === 1, missed: row.missed === 1 };
      });
  }
}

const MISSED = `(c.direction = 'incoming' AND c.answered_at IS NULL AND c.state IN ('ended', 'rejected', 'failed'))`;
const DURATION = `CASE WHEN c.answered_at IS NOT NULL AND c.ended_at IS NOT NULL
  THEN MAX(0, (julianday(c.ended_at) - julianday(c.answered_at)) * 86400) END`;
