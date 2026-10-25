/**
 * Herdr (https://herdr.dev) support for Pi-Bolt: in a Herdr pane, report the agent's state (idle, working, blocked) and the
 * command that reopens its session, so Herdr shows `pi-bolt` in its sidebar, notifies when it finishes or needs the user, and
 * brings the same session back in the same pane after a Herdr server restart.
 *
 * Herdr's own Pi integration (`herdr integration install pi`, ~/.pi/agent/extensions/herdr-agent-state.ts) reports as `pi` and
 * restores with `pi --session`, which is not Pi-Bolt, so Pi-Bolt does not load that file (integration-file.ts) and
 * reports for itself, as Herdr's "Add Herdr support to your agent" describes: its own source and agent name, its own resume
 * command. Outside Herdr (HERDR_ENV and HERDR_PANE_ID unset) it does nothing. Reports go over Herdr's socket, or on
 * Windows through the `herdr` executable (transport.ts). Disable it with
 * `-builtin:herdr` in the `extensions` setting.
 *
 * Only the interactive session reports: print, JSON and RPC runs, and subagents (sessions without a UI), would otherwise
 * take over the pane of the session that started them.
 *
 * With `pi-bolt --herdr-shim`, Pi-Bolt reports as Herdr's official Pi integration (`source=herdr:pi`, `agent=pi`)
 * instead, so Herdr records `agent_session` for `herdr agent list`. Restoring is the same either way: Herdr runs the resume
 * command a report carries before its own plan, and the reported command still starts `pi-bolt`, with `--herdr-shim`. Herdr
 * only accepts that pair from a pane whose foreground process is named `pi` (its process table is fixed), so the flag
 * re-execs Pi-Bolt through a `pi` symlink (shim.ts); a display-only metadata report keeps the sidebar showing `Pi-Bolt`
 * rather than `pi`.
 */

import { isAbsolute } from "node:path";
import { COMMAND_NAME, PRODUCT_NAME } from "../../config.ts";
import type { ExtensionContext, ExtensionFactory } from "../../core/extensions/types.ts";
import { isHerdrShim } from "./shim.ts";
import { type HerdrRequest, type HerdrTransport, herdrFromEnvironment } from "./transport.ts";

const SOURCE = "pi-bolt";
const AGENT = "pi-bolt";
const PI_SOURCE = "herdr:pi";
const PI_AGENT = "pi";

type AgentState = "working" | "blocked" | "idle";

/**
 * pi-herdr (an extension that runs agents in Herdr panes) also reports this pane's state, as `pi`, and the last report holds
 * the pane: Herdr would show `pi` and lose the resume command. Its PI_HERDR_NO_SELF_REPORT switch turns that off. pi-herdr
 * reads it when it loads, before this extension runs, so this is called at startup.
 */
export function quietOtherHerdrReporters(env: NodeJS.ProcessEnv = process.env): void {
	if (herdrFromEnvironment(env)) env.PI_HERDR_NO_SELF_REPORT ??= "1";
}

async function send(transport: HerdrTransport, request: HerdrRequest): Promise<boolean> {
	if (await transport(request, 500)) return true;
	return transport(request, 1500);
}

/**
 * The command Herdr runs to reopen this session after a server restart: `pi-bolt --session <file>`, or with `--herdr-shim`
 * in shim mode so the restored process keeps reporting as the official Pi integration. Herdr refuses a report whose
 * command has an apostrophe or a control character in it or is over 8 KiB, so such a session gets no command (its state
 * still reaches Herdr).
 */
export function resumeArgv(sessionFile: string | undefined, shim = false): string[] | undefined {
	if (!sessionFile || !isAbsolute(sessionFile)) return undefined;
	const argv = shim
		? [COMMAND_NAME, "--herdr-shim", "--session", sessionFile]
		: [COMMAND_NAME, "--session", sessionFile];
	if (argv.some((arg) => arg.includes("'") || hasControlCharacter(arg))) return undefined;
	if (argv.reduce((bytes, arg) => bytes + Buffer.byteLength(arg), 0) > 8192) return undefined;
	return argv;
}

/** Rust's char::is_control, which Herdr checks: C0, DEL and C1. */
function hasControlCharacter(text: string): boolean {
	for (let i = 0; i < text.length; i++) {
		const code = text.charCodeAt(i);
		if (code < 0x20 || (code >= 0x7f && code <= 0x9f)) return true;
	}
	return false;
}

export function createHerdrExtension(
	env: NodeJS.ProcessEnv = process.env,
	platform: NodeJS.Platform = process.platform,
): ExtensionFactory {
	return (pi) => {
		const herdr = herdrFromEnvironment(env, platform);
		if (!herdr) return;
		const { transport, paneId } = herdr;
		const shim = isHerdrShim(env);
		const source = shim ? PI_SOURCE : SOURCE;
		const agent = shim ? PI_AGENT : AGENT;

		// Herdr ignores a report whose number is not above the last one from this source, across restarts of Pi-Bolt too.
		let seq = Date.now() * 1000;
		const nextSeq = () => ++seq;

		let reporting = false;
		let sessionFile: string | undefined;
		let sessionId: string | undefined;
		let agentActive = false;
		let uiPrompt = false;
		const blockedLabels: string[] = [];
		const runningSubagents = new Set<string>();
		let last: { state: AgentState; message?: string; sessionFile?: string } | undefined;

		function updateSession(ctx: ExtensionContext) {
			try {
				const file = ctx.sessionManager.getSessionFile();
				sessionFile = typeof file === "string" && isAbsolute(file) ? file : undefined;
			} catch {
				sessionFile = undefined;
			}
			try {
				const id = ctx.sessionManager.getSessionId();
				sessionId = typeof id === "string" && id.length > 0 ? id : undefined;
			} catch {
				sessionId = undefined;
			}
		}

		function desiredState(): { state: AgentState; message?: string } {
			if (blockedLabels.length > 0) return { state: "blocked", message: blockedLabels[blockedLabels.length - 1] };
			// A dialog during a run is a tool or extension waiting for the user; one opened at the prompt is the user's own.
			if (uiPrompt && agentActive) return { state: "blocked" };
			if (agentActive || runningSubagents.size > 0) return { state: "working" };
			return { state: "idle" };
		}

		// One report in flight at a time; only the newest waiting state is sent. The session report comes first: Herdr
		// only anchors a full-lifecycle source (herdr:pi) with a session_start_source, and state reports before that are
		// ignored.
		let inFlight = false;
		let queuedSession: HerdrRequest | undefined;
		let queued: HerdrRequest | undefined;
		let sessionReported = false;
		let metadataSent = false;
		function sessionRequest(sessionStartSource?: string): HerdrRequest | undefined {
			const sessionRef = sessionFile
				? { agent_session_path: sessionFile }
				: sessionId
					? { agent_session_id: sessionId }
					: undefined;
			if (!sessionRef) return undefined;
			const argv = resumeArgv(sessionFile, shim);
			return {
				method: "pane.report_agent_session",
				params: {
					pane_id: paneId,
					source,
					agent,
					seq: nextSeq(),
					...sessionRef,
					...(sessionStartSource ? { session_start_source: sessionStartSource } : {}),
					...(argv ? { resume_argv: argv } : {}),
				},
			};
		}
		function queueSession(sessionStartSource?: string) {
			if (!shim) return;
			queuedSession = sessionRequest(sessionStartSource);
			// Nothing to report (an in-memory session) is not something to retry on every state change.
			sessionReported = queuedSession === undefined;
		}
		function metadataRequest(): HerdrRequest {
			return {
				method: "pane.report_metadata",
				params: {
					pane_id: paneId,
					source: SOURCE,
					agent: PI_AGENT,
					applies_to_source: PI_SOURCE,
					display_agent: PRODUCT_NAME,
					seq: nextSeq(),
				},
			};
		}
		async function drain() {
			if (inFlight) return;
			inFlight = true;
			try {
				while (queuedSession || queued) {
					if (queuedSession) {
						if (!(await send(transport, queuedSession))) break;
						queuedSession = undefined;
						sessionReported = true;
						continue;
					}
					const request = queued;
					if (!request) break;
					queued = undefined;
					const sent = await send(transport, request);
					// The guards on the display name need the state report to have made `pi` the pane's agent first.
					if (sent && shim && !metadataSent && request.method === "pane.report_agent") {
						metadataSent = await send(transport, metadataRequest());
					}
				}
			} finally {
				inFlight = false;
			}
		}

		function publish(force = false, sessionStartSource?: string) {
			if (!reporting) return;
			const next = { ...desiredState(), sessionFile };
			const sessionChanged = last?.sessionFile !== next.sessionFile;
			const needsSession = shim && !sessionReported;
			if (
				!force &&
				!needsSession &&
				last &&
				last.state === next.state &&
				last.message === next.message &&
				!sessionChanged
			) {
				return;
			}
			last = next;
			if (shim && (force || sessionChanged || !sessionReported)) queueSession(sessionStartSource);
			const argv = resumeArgv(sessionFile, shim);
			queued = {
				method: "pane.report_agent",
				params: {
					pane_id: paneId,
					source,
					agent,
					state: next.state,
					...(next.message ? { message: next.message } : {}),
					seq: nextSeq(),
					...(sessionFile
						? { agent_session_path: sessionFile }
						: sessionId
							? { agent_session_id: sessionId }
							: {}),
					...(argv ? { resume_argv: argv } : {}),
				},
			};
			void drain();
		}

		pi.on("session_start", (event, ctx) => {
			if (ctx.mode !== "tui") return;
			reporting = true;
			metadataSent = false;
			updateSession(ctx);
			// A reload replaces this extension in the middle of a run without another agent_start.
			agentActive = ctx.isIdle() === false;
			publish(true, event.reason);
		});

		pi.on("agent_start", (_event, ctx) => {
			if (!reporting) return;
			updateSession(ctx);
			agentActive = true;
			publish();
		});

		pi.on("agent_settled", (_event, ctx) => {
			if (!reporting || !ctx.isIdle()) return;
			agentActive = false;
			publish();
		});

		pi.on("ui_prompt_start", () => {
			uiPrompt = true;
			publish();
		});

		pi.on("ui_prompt_end", () => {
			uiPrompt = false;
			publish();
		});

		// Extensions that wait for the user outside a dialog (pi-ask-user, pi-subagents) say so on this channel, which
		// Herdr's own Pi integration also listens to: { active: boolean, label?: string }.
		pi.events.on("herdr:blocked", (data) => {
			const { active, label } = (data ?? {}) as { active?: unknown; label?: unknown };
			if (active) blockedLabels.push(typeof label === "string" ? label : "");
			else blockedLabels.pop();
			publish();
		});

		// Background subagents keep working after the run that started them settles.
		for (const [event, running] of [
			["subagents:started", true],
			["subagents:completed", false],
			["subagents:failed", false],
		] as const) {
			pi.events.on(event, (data) => {
				const id = (data as { id?: unknown } | undefined)?.id;
				if (typeof id !== "string") return;
				if (running) runningSubagents.add(id);
				else runningSubagents.delete(id);
				publish();
			});
		}

		pi.on("session_shutdown", async (event) => {
			if (!reporting) return;
			reporting = false;
			// Another session in this process (new, resume, fork) or a reload reports again from its session_start.
			if (event.reason !== "quit") return;
			queued = undefined;
			// One short try: Herdr also notices the exit once the pane is back at its shell prompt.
			await transport(
				{
					method: "pane.release_agent",
					params: { pane_id: paneId, source, agent, seq: nextSeq() },
				},
				500,
			);
		});
	};
}

export default createHerdrExtension();
