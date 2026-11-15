import { WebSocket, type RawData } from "ws";
import type { AgentMessage, AgentStatus, CallAgentOptions, TranscriptLine } from "../agent/gemini-call-agent.js";
import { buildInstructions } from "../agent/gemini-call-agent.js";
import { PHONE_RATE, rms } from "./audio.js";
import type { AiRouter, ChatMessage, ChatTool } from "./router.js";

/**
 * The fallback call agent: speech-to-text -> chat model -> text-to-speech, each through the
 * router's provider list. Used when no Gemini Live key is available. Slower than Live (about 1.5-3 s
 * per reply) but runs on free tiers of many providers.
 *
 * Turn taking uses a simple energy detector: speech starts after 60 ms above the noise floor and
 * ends after 700 ms of silence. Talking over the agent for 300 ms interrupts it (barge-in).
 */
const FRAME_BYTES = 640; // 20 ms
const START_FRAMES = 3;
const END_SILENCE_FRAMES = 35;
const BARGE_IN_FRAMES = 15;
const PRE_ROLL_FRAMES = 15;
const MAX_UTTERANCE_FRAMES = 20 * 50;
const MIN_LEVEL = 450;

export class PipelineCallAgent {
  status: AgentStatus = "connecting";
  error?: string;
  readonly transcript: TranscriptLine[];
  message?: AgentMessage;
  /** Which providers answered, for the call's status. */
  lastProvider?: string;

  private media?: WebSocket;
  private closed = false;
  private live = false;
  private hangupRequested = false;
  private readonly history: ChatMessage[] = [];
  private noise = 300;
  private voiced = 0;
  private silent = 0;
  private speaking = false;
  private utterance: Buffer[] = [];
  private preRoll: Buffer[] = [];
  private generation = 0;
  private busy = false;
  private pendingText: string[] = [];
  private playbackEndsAt = 0;
  private failures = 0;

  constructor(private readonly options: CallAgentOptions & { router: AiRouter }) {
    this.transcript = [...(options.history ?? [])];
    for (const line of this.transcript) {
      this.history.push({ role: line.role === "caller" ? "user" : "assistant", content: line.text });
    }
  }

  async start(): Promise<void> {
    const ws = new WebSocket(this.options.mediaUrl, { headers: { "x-api-key": this.options.apiKey } });
    this.media = ws;
    ws.on("message", (data: RawData, isBinary) => {
      if (isBinary) {
        if (this.live) this.onAudio(data as Buffer);
        return;
      }
      let control: { type?: string } = {};
      try {
        control = JSON.parse(data.toString()) as { type?: string };
      } catch {
        return;
      }
      if (control.type === "start" && !this.live) {
        this.live = true;
        this.status = "live";
        void this.respond(this.options.history?.length ? "[The line was switched to you during the call. Continue from the conversation so far without greeting again; if the caller was waiting, say sorry for the pause in a few words.]" : `[The call has just been answered. Greet the caller now.${this.callerHint()}]`, false);
      }
      if (control.type === "stop") this.stop();
    });
    ws.on("close", () => this.stop());
    ws.on("error", (error) => this.fail(error));
  }

  stop(): void {
    if (this.closed) return;
    this.closed = true;
    this.generation++;
    if (this.status !== "error") this.status = "ended";
    this.media?.close();
    this.options.onEnded();
  }

  // ---- listening ----

  private onAudio(chunk: Buffer): void {
    for (let i = 0; i + FRAME_BYTES <= chunk.length; i += FRAME_BYTES) this.onFrame(chunk.subarray(i, i + FRAME_BYTES));
  }

  private onFrame(frame: Buffer): void {
    const level = rms(frame);
    const threshold = Math.max(MIN_LEVEL, this.noise * 3);
    const voice = level > threshold;
    if (!voice) this.noise = this.noise * 0.95 + level * 0.05;

    if (!this.speaking) {
      this.preRoll.push(Buffer.from(frame));
      if (this.preRoll.length > PRE_ROLL_FRAMES) this.preRoll.shift();
      this.voiced = voice ? this.voiced + 1 : 0;
      const agentTalking = Date.now() < this.playbackEndsAt;
      if (this.voiced >= (agentTalking ? BARGE_IN_FRAMES : START_FRAMES)) {
        this.speaking = true;
        this.silent = 0;
        this.utterance = [...this.preRoll];
        this.preRoll = [];
        if (agentTalking) this.interrupt();
      }
      return;
    }
    this.utterance.push(Buffer.from(frame));
    this.silent = voice ? 0 : this.silent + 1;
    if (this.silent >= END_SILENCE_FRAMES || this.utterance.length >= MAX_UTTERANCE_FRAMES) {
      const pcm = Buffer.concat(this.utterance);
      this.speaking = false;
      this.utterance = [];
      this.voiced = 0;
      if (pcm.length > FRAME_BYTES * (START_FRAMES + END_SILENCE_FRAMES)) void this.onUtterance(pcm);
    }
  }

  private async onUtterance(pcm: Buffer): Promise<void> {
    if (this.hangupRequested || this.closed) return;
    let text = "";
    try {
      text = await this.options.router.transcribe(pcm);
    } catch (error) {
      return this.onProviderError(error);
    }
    if (!text || /^[\s.,!?…-]*$/.test(text)) return;
    this.append("caller", text);
    if (this.busy) {
      // The caller kept talking: answer everything together once the current reply is cut.
      this.pendingText.push(text);
      this.generation++;
      return;
    }
    void this.respond(text, true);
  }

  // ---- replying ----

  private async respond(text: string, fromCaller: boolean): Promise<void> {
    if (this.closed || this.hangupRequested) return;
    this.busy = true;
    const gen = ++this.generation;
    this.history.push({ role: "user", content: text });
    try {
      for (let round = 0; round < 3; round++) {
        const { message, provider } = await this.options.router.chat(
          [{ role: "system", content: this.instructions() }, ...this.history.slice(-30)],
          this.tools(),
        );
        this.lastProvider = provider;
        if (gen !== this.generation) break;
        this.history.push(message);
        if (message.content?.trim()) await this.say(message.content.trim(), gen);
        if (!message.tool_calls?.length) break;
        for (const call of message.tool_calls) {
          let args: Record<string, unknown> = {};
          try {
            args = JSON.parse(call.function.arguments || "{}") as Record<string, unknown>;
          } catch {
            /* keep empty */
          }
          const result = await this.runTool(call.function.name, args);
          this.history.push({ role: "tool", tool_call_id: call.id, name: call.function.name, content: result });
        }
        if (this.hangupRequested) break;
      }
      this.failures = 0;
    } catch (error) {
      this.onProviderError(error);
    } finally {
      this.busy = false;
    }
    if (!fromCaller && !this.pendingText.length) return;
    const queued = this.pendingText.splice(0);
    if (queued.length && !this.closed && !this.hangupRequested) void this.respond(queued.join(" "), true);
  }

  /** Speaks sentence by sentence so the first words play while the rest is synthesised. */
  private async say(text: string, gen: number): Promise<void> {
    this.append("agent", text);
    const sentences = text.match(/[^.!?।]+[.!?।]*/g)?.map((s) => s.trim()).filter(Boolean) ?? [text];
    // Merge very short pieces to keep requests few.
    const parts: string[] = [];
    for (const s of sentences) {
      if (parts.length && (parts.at(-1)!.length < 40 || s.length < 12)) parts[parts.length - 1] += ` ${s}`;
      else parts.push(s);
    }
    let next = parts.length ? this.options.router.speak(parts[0]!) : undefined;
    for (let i = 0; i < parts.length && next; i++) {
      const pcm = await next;
      next = i + 1 < parts.length ? this.options.router.speak(parts[i + 1]!) : undefined;
      if (gen !== this.generation || this.closed) {
        next?.catch(() => undefined);
        return;
      }
      this.play(pcm);
    }
  }

  private play(pcm: Buffer): void {
    if (this.media?.readyState !== WebSocket.OPEN) return;
    for (let i = 0; i < pcm.length; i += FRAME_BYTES) {
      const frame = pcm.subarray(i, i + FRAME_BYTES);
      this.media.send(frame, { binary: true });
    }
    this.playbackEndsAt = Math.max(this.playbackEndsAt, Date.now()) + (pcm.length / 2 / PHONE_RATE) * 1000;
  }

  private interrupt(): void {
    this.generation++;
    this.playbackEndsAt = Date.now();
    if (this.media?.readyState === WebSocket.OPEN) this.media.send(JSON.stringify({ type: "clear" }));
  }

  private onProviderError(error: unknown): void {
    this.failures++;
    console.error(`pipeline ${this.options.callId}: ${error instanceof Error ? error.message : error}`);
    // Every provider of a kind failed several times in a row: give up so the call is handed on.
    if (this.failures >= 3) this.fail(error);
  }

  // ---- tools ----

  private async runTool(name: string, args: Record<string, unknown>): Promise<string> {
    if (name === "take_message") {
      const str = (v: unknown) => (typeof v === "string" && v.trim() ? v.trim() : undefined);
      this.message = {
        callId: this.options.callId,
        remoteNumber: this.options.remoteNumber,
        callerName: str(args.caller_name),
        reason: str(args.reason),
        callbackNumber: str(args.callback_number),
        urgency: (["low", "normal", "high"] as const).find((u) => u === args.urgency) ?? "normal",
        ...(this.options.mode === "receptionist" ? { business: this.options.businessName || this.options.ownerName } : {}),
        takenAt: new Date().toISOString(),
      };
      this.options.onMessage(this.message);
      return "Message saved.";
    }
    if (name === "send_booking_link" && this.options.sendBookingLink) {
      try {
        return await this.options.sendBookingLink({
          phoneNumber: typeof args.phone_number === "string" ? args.phone_number : undefined,
          patientName: typeof args.patient_name === "string" ? args.patient_name : undefined,
        });
      } catch (error) {
        return `Could not send: ${error instanceof Error ? error.message : String(error)}`;
      }
    }
    if (name === "end_call") {
      if (!this.hangupRequested) {
        this.hangupRequested = true;
        const wait = Math.max(0, this.playbackEndsAt - Date.now()) + 800;
        setTimeout(() => {
          if (!this.closed) this.options.onHangup();
        }, wait);
      }
      return "Ending the call.";
    }
    return "Unknown tool.";
  }

  private tools(): ChatTool[] {
    const tools: ChatTool[] = [
      {
        type: "function",
        function: {
          name: "take_message",
          description: "Save the caller's message for the staff. Call once you know who is calling and why.",
          parameters: {
            type: "object",
            properties: {
              caller_name: { type: "string" },
              reason: { type: "string", description: "Why they are calling, one or two sentences" },
              callback_number: { type: "string" },
              urgency: { type: "string", enum: ["low", "normal", "high"] },
            },
            required: ["reason"],
          },
        },
      },
      {
        type: "function",
        function: { name: "end_call", description: "Hang up after you have said goodbye.", parameters: { type: "object", properties: {} } },
      },
    ];
    if (this.options.mode === "receptionist" && this.options.sendBookingLink) {
      tools.push({
        type: "function",
        function: {
          name: "send_booking_link",
          description: "Send the appointment booking link to the caller on WhatsApp, once they agree and the number is confirmed.",
          parameters: {
            type: "object",
            properties: {
              phone_number: { type: "string", description: "Only if the caller gave a different WhatsApp number or theirs is hidden" },
              patient_name: { type: "string" },
            },
          },
        },
      });
    }
    return tools;
  }

  private instructions(): string {
    return `${buildInstructions(this.options)}

Speech output rules (your text is read aloud by a text-to-speech voice):
- Write Hindi in Devanagari script (for example "नमस्ते, मैं आपकी कैसे मदद कर सकती हूँ?"), English in Latin script.
- Never use emojis, lists, brackets, markdown or abbreviations that are hard to say.
- Before calling a tool, say a short sentence first if the caller would otherwise wait in silence.`;
  }

  private callerHint(): string {
    if (this.options.mode !== "receptionist") return "";
    const digits = this.options.remoteNumber.replace(/\D/g, "");
    return digits.length >= 10 ? ` The caller is calling from a number ending in ${digits.slice(-4)}.` : " The caller's number is hidden.";
  }

  private append(role: TranscriptLine["role"], text: string): void {
    this.transcript.push({ role, text, at: new Date().toISOString() });
  }

  private fail(error: unknown): void {
    if (this.closed) return;
    this.status = "error";
    this.error = error instanceof Error ? error.message : String(error);
    this.stop();
  }
}
