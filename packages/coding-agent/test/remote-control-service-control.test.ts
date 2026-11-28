import { chmodSync, lstatSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { connectControl, controlPaths, listenControl } from "../src/remote-control/service-control.ts";

describe.skipIf(process.platform === "win32")("remote control's control socket", () => {
	const cleanups: Array<() => void> = [];
	afterEach(() => {
		vi.restoreAllMocks();
		for (const cleanup of cleanups.splice(0).reverse()) cleanup();
	});

	function tempDir(prefix: string): string {
		const dir = mkdtempSync(join(tmpdir(), prefix));
		cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
		return dir;
	}

	function setEnv(name: string, value: string | undefined): void {
		const saved = process.env[name];
		if (value === undefined) delete process.env[name];
		else process.env[name] = value;
		cleanups.push(() => {
			if (saved === undefined) delete process.env[name];
			else process.env[name] = saved;
		});
	}

	/** An agent directory whose `remote-control/service.sock` is too long for a Unix socket path. */
	function longAgentDir(): string {
		const agentDir = join(tempDir("rc-"), "x".repeat(90), "agent");
		mkdirSync(agentDir, { recursive: true });
		return agentDir;
	}

	function listenAt(path: string, received: string[]): Promise<Server> {
		const server = createServer((socket) => {
			socket.setEncoding("utf8");
			socket.on("data", (chunk: string) => received.push(chunk));
		});
		cleanups.push(() => server.close());
		return new Promise((resolve) => server.listen(path, () => resolve(server)));
	}

	it("puts the socket of a long agent directory in a 0700 folder under $XDG_RUNTIME_DIR", async () => {
		const runtimeDir = tempDir("xdg-");
		setEnv("XDG_RUNTIME_DIR", runtimeDir);
		const agentDir = longAgentDir();
		const paths = controlPaths(agentDir);
		expect(dirname(paths.socket)).toBe(join(runtimeDir, "pi-bolt"));

		const listener = await listenControl(paths, () => {});
		cleanups.push(() => listener?.close());
		expect(listener).toBeDefined();
		const dir = lstatSync(dirname(paths.socket));
		expect(dir.isDirectory()).toBe(true);
		expect(dir.uid).toBe(process.getuid?.());
		expect(dir.mode & 0o777).toBe(0o700);
		const client = await connectControl(paths);
		expect(client).toBeDefined();
		client?.close();
	});

	it("uses a per-user 0700 folder in the temp directory without $XDG_RUNTIME_DIR", async () => {
		const agentDir = longAgentDir();
		const temp = tempDir("t-");
		setEnv("XDG_RUNTIME_DIR", undefined);
		setEnv("TMPDIR", temp);
		const paths = controlPaths(agentDir, "linux");
		expect(dirname(paths.socket)).toBe(join(temp, `pi-bolt-${process.getuid?.()}`));

		const listener = await listenControl(paths, () => {});
		cleanups.push(() => listener?.close());
		expect(listener).toBeDefined();
		expect(lstatSync(dirname(paths.socket)).mode & 0o777).toBe(0o700);
	});

	it("refuses a socket folder that others can open or that is not a directory", async () => {
		const runtimeDir = tempDir("xdg-");
		setEnv("XDG_RUNTIME_DIR", runtimeDir);
		const paths = controlPaths(longAgentDir());
		const socketDir = dirname(paths.socket);

		mkdirSync(socketDir);
		chmodSync(socketDir, 0o777);
		await expect(listenControl(paths, () => {})).rejects.toThrow("mode 0700");

		rmSync(socketDir, { recursive: true });
		const elsewhere = tempDir("elsewhere-");
		symlinkSync(elsewhere, socketDir);
		await expect(listenControl(paths, () => {})).rejects.toThrow("mode 0700");
	});

	it("connects only to a socket that is not a link and that this user owns", async () => {
		const agentDir = join(tempDir("rc-"), "agent");
		const paths = controlPaths(agentDir);
		mkdirSync(dirname(paths.token), { recursive: true, mode: 0o700 });
		writeFileSync(paths.token, `${Buffer.alloc(32, 1).toString("base64url")}\n`, { mode: 0o600 });
		const received: string[] = [];
		const other = join(tempDir("o-"), "other.sock");
		await listenAt(other, received);

		symlinkSync(other, paths.socket);
		expect(await connectControl(paths)).toBeUndefined();
		rmSync(paths.socket);

		await listenAt(paths.socket, received);
		vi.spyOn(process, "getuid").mockReturnValue((process.getuid?.() ?? 0) + 1);
		expect(await connectControl(paths)).toBeUndefined();
		vi.restoreAllMocks();

		const client = await connectControl(paths);
		expect(client).toBeDefined();
		client?.close();
		await new Promise((resolve) => setTimeout(resolve, 20));
		expect(received).toEqual([]);
	});
});
