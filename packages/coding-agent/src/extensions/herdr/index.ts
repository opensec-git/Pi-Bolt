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
 * With `pi-bolt --herdr-pi`, Pi-Bolt reports as Herdr's official Pi integration (`source=herdr:pi`, `agent=pi`)
 * instead, so Herdr records `agent_session` for `herdr agent list`. Restoring is the same either way: Herdr runs the resume
 * command a report carries before its own plan, and the reported command still starts `pi-bolt`, with `--herdr-pi`. Herdr
 * only accepts that pair from a pane whose foreground process is named `pi` (its process table is fixed), so the flag
 * re-execs Pi-Bolt with `pi` as its command name (pi-name.ts); a display-only metadata report keeps the sidebar showing `Pi-Bolt`
 * rather than `pi`.
 */

import { isAbsolute } from "node:path";
import { COMMAND_NAME, PRODUCT_NAME } from "../../config.ts";
import type { ExtensionContext, ExtensionFactory } from "../../core/extensions/types.ts";
import { isHerdrPi } from "./pi-name.ts";
import { type HerdrAnswer, type HerdrRequest, type HerdrTransport, herdrFromEnvironment } from "./transport.ts";

const SOURCE = "pi-bolt";
const AGENT = "pi-bolt";
const PI_SOURCE = "herdr:pi";
const PI_AGENT = "pi";

type AgentState = "working" | "blocked" | "idle";

/** A report to send, with or without the resume command; undefined once it is no longer wanted. */
type Report = (withResume: boolean) => HerdrRequest | undefined;

/**
 * pi-herdr (an extension that runs agents in Herdr panes) also reports this pane's state, as `pi`, and the last report holds
 * the pane: Herdr would show `pi` and lose the resume command. Its PI_HERDR_NO_SELF_REPORT switch turns that off. pi-herdr
 * reads it when it loads, before this extension runs, so this is called at startup.
 */
export function quietOtherHerdrReporters(env: NodeJS.ProcessEnv = process.env): void {
	if (herdrFromEnvironment(env)) env.PI_HERDR_NO_SELF_REPORT ??= "1";
}

/**
 * Herdr's answer to a report whose resume command it did not record because the reporter does not hold the pane. Herdr
 * has applied the rest of the report by then (its state or session, under its own rules), so sending the same report
 * again changes nothing.
 */
const RESUME_NOT_ACCEPTED = "resume_not_accepted";

/**
 * How long a session's state reports go without the resume command after Herdr refused it. Herdr's answers do not say
 * whether Pi-Bolt holds the pane, so the next state report after this offers the command again: at most one refused
 * request per window, and a pane that became free gets its command within it.
 */
export const RESUME_RETRY_MS = 10 * 60 * 1000;

/**
 * Two tries; Herdr's last answer. Each try gets its own seq: Herdr may have applied a try whose answer was lost, and would
 * then refuse the same seq again. A refused resume command is not tried again. `request` returns undefined once the
 * report is no longer wanted.
 */
async function send(transport: HerdrTransport, request: () => HerdrRequest | undefined): Promise<HerdrAnswer> {
	let answer: HerdrAnswer = { taken: false };
	for (const timeoutMs of [500, 1500]) {
		const next = request();
		if (!next) return { taken: false };
		answer = await transport(next, timeoutMs);
		if (answer.taken || answer.code === RESUME_NOT_ACCEPTED) return answer;
	}
	return answer;
}

/**
 * The command Herdr runs to reopen this session after a server restart: `pi-bolt --session <file>`, or with `--herdr-pi`
 * in front when reporting as Herdr's Pi integration, so the restored process reports the same way. Herdr refuses a report
 * whose command has an apostrophe or a control character in it or is over 8 KiB, so such a session gets no command (its
 * state still reaches Herdr).
 */
export function resumeArgv(sessionFile: string | undefined, piIntegration = false): string[] | undefined {
	if (!sessionFile || !isAbsolute(sessionFile)) return undefined;
	const argv = piIntegration
		? [COMMAND_NAME, "--herdr-pi", "--session", sessionFile]
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
	reportsAsPi: () => boolean = isHerdrPi,
	now: () => number = Date.now,
): ExtensionFactory {
	return (pi) => {
		const herdr = herdrFromEnvironment(env, platform);
		if (!herdr) return;
		const { transport, paneId } = herdr;
		const piIntegration = reportsAsPi();
		const source = piIntegration ? PI_SOURCE : SOURCE;
		const agent = piIntegration ? PI_AGENT : AGENT;

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
		let backgroundBusy = false;
		let last: { state: AgentState; message?: string; sessionFile?: string } | undefined;
		// The last time Herdr refused the resume command of a state report, and for which session file (#330). Only an
		// answer changes it: a refusal sets it, a state report taken with the command clears it. A report without an
		// answer (timeout, no socket) leaves it as it is.
		let resumeRefusal: { sessionFile: string | undefined; at: number } | undefined;
		function offerResume(file: string | undefined): boolean {
			return !resumeRefusal || resumeRefusal.sessionFile !== file || now() - resumeRefusal.at >= RESUME_RETRY_MS;
		}

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
			if (agentActive || backgroundBusy || runningSubagents.size > 0) return { state: "working" };
			return { state: "idle" };
		}

		// One report in flight at a time; only the newest waiting one is sent. When the newest one does not get through,
		// `last` is cleared, so the next publish sends that state again instead of taking it as already reported. The
		// session report goes first: Herdr only anchors a full-lifecycle source (herdr:pi) with a session_start_source,
		// and state reports before that are ignored.
		let inFlight = false;
		let queued: { report: NonNullable<typeof last>; request: Report } | undefined;
		let queuedSession: { request: Report } | undefined;
		let sessionReported = false;
		let metadataSent = false;

		/**
		 * Send a report, with the resume command when `withResume`; whether Herdr took it, and whether it refused the
		 * command. Herdr records a command only from the source that holds the pane (#330): when another source's
		 * session is on the pane, Herdr keeps the pane for it and answers resume_not_accepted. The report then goes once
		 * more without the command, as the state report Herdr asks for: one refusal, never a loop.
		 */
		async function deliver(
			request: Report,
			withResume: boolean,
		): Promise<{ taken: boolean; resumeRefused: boolean }> {
			const answer = await send(transport, () => request(withResume));
			if (answer.taken || answer.code !== RESUME_NOT_ACCEPTED) return { taken: answer.taken, resumeRefused: false };
			return { taken: (await send(transport, () => request(false))).taken, resumeRefused: true };
		}

		function sessionRequest(withResume: boolean, sessionStartSource?: string): HerdrRequest | undefined {
			if (!reporting) return undefined;
			const sessionRef = sessionFile
				? { agent_session_path: sessionFile }
				: sessionId
					? { agent_session_id: sessionId }
					: undefined;
			if (!sessionRef) return undefined;
			const argv = withResume ? resumeArgv(sessionFile, piIntegration) : undefined;
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
			if (!piIntegration) return;
			// Nothing to report (an in-memory session) is not something to retry on every state change.
			if (!sessionFile && !sessionId) {
				queuedSession = undefined;
				sessionReported = true;
				return;
			}
			queuedSession = { request: (withResume) => sessionRequest(withResume, sessionStartSource) };
		}
		// No try after session_shutdown, as for a state report: its seq would be above pane.release_agent's.
		function metadataRequest(): HerdrRequest | undefined {
			if (!reporting) return undefined;
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
						const { request } = queuedSession;
						queuedSession = undefined;
						// Keep the session report first: without it the source is not anchored and state reports are ignored.
						// One Herdr refuses (an older Herdr without pane.report_agent_session, or a validation error) must
						// not hold back the state report behind it, or the sidebar stays empty: the state still goes, and
						// the session report is tried again with the next one. A refused resume command still goes with the
						// state report after it, which is what makes a source hold the pane.
						sessionReported = (await deliver(request, offerResume(sessionFile))).taken;
						continue;
					}
					if (!queued) break;
					const { report, request } = queued;
					queued = undefined;
					const withResume = offerResume(report.sessionFile);
					const delivered = await deliver(request, withResume);
					if (delivered.resumeRefused) resumeRefusal = { sessionFile: report.sessionFile, at: now() };
					else if (delivered.taken && withResume) resumeRefusal = undefined;
					const sent = delivered.taken;
					// The guards on the display name need the state report to have made `pi` the pane's agent first.
					if (sent && piIntegration && !metadataSent)
						metadataSent = (await send(transport, metadataRequest)).taken;
					if (!sent && !queued && last === report) last = undefined;
				}
			} finally {
				inFlight = false;
			}
		}

		function publish(force = false, sessionStartSource?: string) {
			if (!reporting) return;
			const next = { ...desiredState(), sessionFile };
			const sessionChanged = last?.sessionFile !== next.sessionFile;
			const needsSession = piIntegration && !sessionReported;
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
			if (piIntegration && (force || sessionChanged || !sessionReported)) queueSession(sessionStartSource);
			const argv = resumeArgv(sessionFile, piIntegration);
			const params = {
				pane_id: paneId,
				source,
				agent,
				state: next.state,
				...(next.message ? { message: next.message } : {}),
				...(sessionFile ? { agent_session_path: sessionFile } : sessionId ? { agent_session_id: sessionId } : {}),
			};
			queued = {
				report: next,
				// No try after session_shutdown: its seq would be above pane.release_agent's and take the pane back.
				request: (withResume) =>
					reporting
						? {
								method: "pane.report_agent",
								params: { ...params, ...(withResume && argv ? { resume_argv: argv } : {}), seq: nextSeq() },
							}
						: undefined,
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

		// pi-subagents (nicobailon) says on this channel whether its background runs are going: { active: true, label }
		// while any is, { active: false } once none is. It may describe the same runs as subagents:*; either keeps the pane
		// working, and neither counts twice. It relabels by sending false and then true at once, so the report waits for
		// the end of this tick rather than showing idle in between.
		let busyPublishing = false;
		pi.events.on("herdr:busy", (data) => {
			backgroundBusy = Boolean((data as { active?: unknown } | undefined)?.active);
			if (busyPublishing) return;
			busyPublishing = true;
			queueMicrotask(() => {
				busyPublishing = false;
				publish();
			});
		});

		pi.on("session_shutdown", async (event) => {
			if (!reporting) return;
			reporting = false;
			// Another session in this process (new, resume, fork) or a reload reports again from its session_start.
			if (event.reason !== "quit") return;
			queued = undefined;
			queuedSession = undefined;
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
