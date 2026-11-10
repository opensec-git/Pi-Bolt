/**
 * The background service's log, `remote-control/service.log` in the agent directory (mode 0600). It says when the service
 * listens, yields, opens sessions, and which phones connect or are refused; it never holds a key, a pairing secret or a
 * message. At 1 MiB it moves to `service.log.1`, replacing the one before.
 */

import { appendFileSync, chmodSync, existsSync, mkdirSync, readFileSync, renameSync, statSync } from "node:fs";
import { dirname, join } from "node:path";

const MAX_LOG_BYTES = 1024 * 1024;

export type ServiceLogLevel = "info" | "warn" | "error";

/** The files of the service in the agent directory, besides the pairing store's. */
export function serviceLogPaths(agentDir: string): { log: string; previous: string; stderr: string } {
	const dir = join(agentDir, "remote-control");
	return {
		log: join(dir, "service.log"),
		previous: join(dir, "service.log.1"),
		// What the process itself prints (an extension's output, a crash's stack trace), where the service manager puts it.
		stderr: join(dir, "service.err.log"),
	};
}

export class ServiceLog {
	readonly path: string;
	private readonly previous: string;
	private readonly echo: ((line: string) => void) | undefined;

	constructor(agentDir: string, echo?: (line: string) => void) {
		const paths = serviceLogPaths(agentDir);
		this.path = paths.log;
		this.previous = paths.previous;
		this.echo = echo;
	}

	info(message: string): void {
		this.write("info", message);
	}

	warn(message: string): void {
		this.write("warn", message);
	}

	error(message: string): void {
		this.write("error", message);
	}

	private write(level: ServiceLogLevel, message: string): void {
		// One line per entry, without control characters (a device name is chosen by the phone).
		const line = `${new Date().toISOString()} ${level.padEnd(5)} ${message.replace(/[\u0000-\u001f\u007f-\u009f]+/g, " ")}\n`;
		this.echo?.(line);
		try {
			const dir = dirname(this.path);
			if (!existsSync(dir)) mkdirSync(dir, { recursive: true, mode: 0o700 });
			if (existsSync(this.path) && statSync(this.path).size > MAX_LOG_BYTES) renameSync(this.path, this.previous);
			const created = !existsSync(this.path);
			appendFileSync(this.path, line, { mode: 0o600 });
			if (created) chmodSync(this.path, 0o600);
		} catch {
			// A log that cannot be written does not stop the service.
		}
	}
}

/** The last `count` lines of the log (the rotated file first when the current one is shorter). */
export function readLogTail(agentDir: string, count: number): string[] {
	const paths = serviceLogPaths(agentDir);
	const read = (path: string) => (existsSync(path) ? readFileSync(path, "utf8").split("\n").filter(Boolean) : []);
	const current = read(paths.log);
	const lines = current.length >= count ? current : [...read(paths.previous), ...current];
	return lines.slice(-count);
}
