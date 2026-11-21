/**
 * Automatic updates (#208): with `piBolt.autoUpdate` on in the global settings, an interactive session of an installation the
 * installers manage starts `pi-bolt update --background` after its first frame, at most once a day. That process takes a lock,
 * looks up the latest release on the installation's channel, and runs the same signed update as `pi-bolt update` (the
 * signature of SHA256SUMS, the version on its first line, the installer's checksum; on Windows the in-process update). Like
 * `pi-bolt update`, it compiles nothing: extensions `pi-bolt compile` compiled into the build it replaced load from their files
 * until `pi-bolt compile` compiles them in again. The session goes on; the new release starts next time. It reports one line when it is done, or one with the reason when it fails, and
 * nothing when there is nothing to install.
 *
 * Off by default. PIBOLT_NO_AUTO_UPDATE=1 turns it off whatever the setting says, and it never runs where the version check
 * does not (PI_SKIP_VERSION_CHECK, PI_OFFLINE), nor for an installation npm, Homebrew, Scoop or mise manage, nor for a build
 * run from anywhere else.
 */
import { spawn } from "node:child_process";
import { accessSync, closeSync, constants, openSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import {
	PIBOLT,
	type PiBoltBuild,
	type PiBoltInstallMethod,
	piBoltExecutableIn,
	piBoltInstallDir,
	piBoltInstalledByInstaller,
	piBoltInstallMethod,
} from "./pi-bolt.ts";

/** The variable that turns automatic updates off whatever the setting says (for CI and scripts). */
export const PIBOLT_NO_AUTO_UPDATE = "PIBOLT_NO_AUTO_UPDATE";

/** How often a session may start an update: a day after the last one started. */
export const AUTO_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000;

/**
 * How long the lock of an update that stopped holding it (it crashed, or was killed) keeps another out. Its holder renews it
 * every half of that while it downloads and runs the installer.
 */
export const AUTO_UPDATE_LOCK_STALE_MS = 10 * 60 * 1000;

/** The hidden argument `pi-bolt update` runs the background update with. */
export const AUTO_UPDATE_ARGUMENT = "--background";

/** What a background update ended with. */
export type AutoUpdateResult =
	| { kind: "current"; version: string }
	| { kind: "installed"; version: string; extensionsFromFiles?: true }
	| { kind: "failed"; reason: string; version?: string };

/** <install>/auto-update.json: when the last update started, and how it ended. */
export interface AutoUpdateRecord {
	checkedAt: number;
	result?: AutoUpdateResult;
}

/** Where the record, the lock (proper-lockfile's <path>.lock) and the last update's output are. */
export function autoUpdatePaths(installDir: string): { record: string; lock: string; log: string } {
	return {
		record: join(installDir, "auto-update.json"),
		lock: join(installDir, "auto-update"),
		log: join(installDir, "auto-update.log"),
	};
}

export function readAutoUpdateRecord(installDir: string): AutoUpdateRecord | undefined {
	try {
		const record = JSON.parse(readFileSync(autoUpdatePaths(installDir).record, "utf8")) as AutoUpdateRecord;
		return typeof record?.checkedAt === "number" ? record : undefined;
	} catch {
		return undefined;
	}
}

/** Writes the record whole (a reader never sees half of it). */
export function writeAutoUpdateRecord(installDir: string, record: AutoUpdateRecord): void {
	const path = autoUpdatePaths(installDir).record;
	const partial = `${path}.${process.pid}.tmp`;
	writeFileSync(partial, `${JSON.stringify(record, null, "\t")}\n`);
	renameSync(partial, path);
}

/** Whether a session starts an update now, or why not. */
export type AutoUpdatePlan = { kind: "start"; installDir: string } | { kind: "skip"; reason: string };

/**
 * Whether this session starts a background update. `enabled`: the global setting. The rest is this executable's and the
 * environment's, given for tests.
 */
export function planAutoUpdate(
	enabled: boolean,
	context: {
		env?: NodeJS.ProcessEnv;
		now?: number;
		build?: PiBoltBuild;
		method?: PiBoltInstallMethod;
		installDir?: string;
		byInstaller?: boolean;
	} = {},
): AutoUpdatePlan {
	const env = context.env ?? process.env;
	const build = "build" in context ? context.build : PIBOLT;
	if (!build) return { kind: "skip", reason: "not a Pi-Bolt build" };
	const off = env[PIBOLT_NO_AUTO_UPDATE];
	if (off !== undefined && off !== "" && off !== "0")
		return { kind: "skip", reason: `${PIBOLT_NO_AUTO_UPDATE} is set` };
	if (!enabled) return { kind: "skip", reason: "piBolt.autoUpdate is off" };
	if (env.PI_SKIP_VERSION_CHECK || env.PI_OFFLINE) return { kind: "skip", reason: "the version check is off" };
	const method = context.method ?? piBoltInstallMethod();
	if (method !== "installer") return { kind: "skip", reason: `installed with ${method}` };
	const installDir = "installDir" in context ? context.installDir : piBoltInstallDir();
	if (!installDir) return { kind: "skip", reason: "not in an installation" };
	const byInstaller = context.byInstaller ?? piBoltInstalledByInstaller(installDir, dirname(process.execPath));
	if (!byInstaller) return { kind: "skip", reason: "not an installation the installer made" };
	// (The installer could not replace it either; the notice of a newer version says to run `pi-bolt update`.)
	try {
		accessSync(installDir, constants.W_OK);
	} catch {
		return { kind: "skip", reason: "the installation cannot be written to" };
	}
	if (startedRecently(readAutoUpdateRecord(installDir), context.now ?? Date.now())) {
		return { kind: "skip", reason: "an update started less than a day ago" };
	}
	return { kind: "start", installDir };
}

/** Whether `record` is of an update that started less than AUTO_UPDATE_INTERVAL_MS before `now`. */
export function startedRecently(record: AutoUpdateRecord | undefined, now: number): boolean {
	return record !== undefined && record.checkedAt <= now && now - record.checkedAt < AUTO_UPDATE_INTERVAL_MS;
}

/** The line a session shows for an update. */
export interface AutoUpdateLine {
	kind: "info" | "error";
	text: string;
}

/** What an update says when the build it replaced had extensions compiled in (install.sh's compiled_extensions_note too). */
export const COMPILED_EXTENSIONS_NOTE =
	"Extensions were compiled into the build this replaced: they load from their files now; run `pi-bolt compile` to compile them into this one.";

/** The line for how an update ended; none when there was nothing to install. */
export function autoUpdateLine(result: AutoUpdateResult | undefined, log: string): AutoUpdateLine | undefined {
	if (!result || result.kind === "current") return undefined;
	if (result.kind === "installed") {
		const note = result.extensionsFromFiles ? ` ${COMPILED_EXTENSIONS_NOTE}` : "";
		return { kind: "info", text: `Pi-Bolt ${result.version} is installed; it starts next time.${note}` };
	}
	return {
		kind: "error",
		text: `Pi-Bolt could not update itself${result.version ? ` to ${result.version}` : ""}: ${result.reason}. Run \`pi-bolt update\` to update it yourself (${log}).`,
	};
}

/**
 * Starts the background update for the installation in `installDir`, in a process of its own that outlives this session,
 * its output in <install>/auto-update.log. `report` gets the line for how it ended, if this session is still running then
 * (one that stopped before it recorded how it ended too); nothing for an update with nothing to install, or one another
 * Pi-Bolt was already running. `command`: the executable and its arguments, for tests.
 */
export function startAutoUpdate(
	installDir: string,
	report: (line: AutoUpdateLine) => void,
	command: { executable: string; args: string[] } = {
		executable: piBoltExecutableIn(installDir),
		args: ["update", AUTO_UPDATE_ARGUMENT],
	},
): void {
	const { log } = autoUpdatePaths(installDir);
	const started = Date.now();
	let reported = false;
	const once = (line: AutoUpdateLine | undefined) => {
		if (reported || !line) return;
		reported = true;
		report(line);
	};
	// A process that could not start is an attempt all the same: the next sessions of the day do not try again.
	const notStarted = (error: Error) => {
		try {
			writeAutoUpdateRecord(installDir, { checkedAt: started, result: { kind: "failed", reason: error.message } });
		} catch {
			// The line below says why; the next session tries again.
		}
		once({ kind: "error", text: `Pi-Bolt could not start its update: ${error.message}` });
	};
	let output: number;
	try {
		// (Appended to, not emptied: a second Pi-Bolt starting at the same moment must not cut the output of the update that
		// runs. The update empties it when it takes the lock.)
		output = openSync(log, "a");
	} catch (error) {
		notStarted(error as Error);
		return;
	}
	try {
		const child = spawn(command.executable, command.args, {
			cwd: homedir(),
			detached: true,
			windowsHide: true,
			stdio: ["ignore", output, output],
		});
		child.on("error", notStarted);
		child.on("exit", (code, signal) => {
			// (No pid: it never started, and "error" says why.)
			if (child.pid === undefined) return;
			// (A record older than this start is an earlier update's: this one found another running, and wrote nothing.)
			const record = readAutoUpdateRecord(installDir);
			if (record?.result && record.checkedAt >= started) {
				once(autoUpdateLine(record.result, log));
				return;
			}
			// It stopped before it recorded how it ended: it crashed or was killed.
			if (code !== 0) {
				once({
					kind: "error",
					text: `Pi-Bolt's update stopped (${signal ? `signal ${signal}` : `exit code ${code}`}) before it finished. Run \`pi-bolt update\` to update it yourself (${log}).`,
				});
			}
		});
		child.unref();
	} finally {
		closeSync(output);
	}
}
