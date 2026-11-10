import { spawn, spawnSync } from "node:child_process";
import { Console } from "node:console";
import { fileURLToPath } from "node:url";
import { fauxAssistantMessage } from "@earendil-works/pi-ai";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { AgentSessionRuntime } from "../../../src/core/agent-session-runtime.ts";
import { restoreStdout } from "../../../src/core/output-guard.ts";
import type * as Jsonl from "../../../src/modes/rpc/jsonl.ts";
import { runRpcMode } from "../../../src/modes/rpc/rpc-mode.ts";
import { createHarness, type Harness } from "../harness.ts";

// Bun's console methods print to fd 1 without calling a replaced process.stdout.write
// (https://github.com/oven-sh/bun/issues/47982), so an extension's console.log went into the RPC stream.

const rpcInput = vi.hoisted(() => ({ lineHandler: undefined as ((line: string) => void) | undefined }));

vi.mock("../../../src/modes/rpc/jsonl.js", async (importOriginal) => ({
	...(await importOriginal<typeof Jsonl>()),
	attachJsonlLineReader: vi.fn((_stream: NodeJS.ReadableStream, onLine: (line: string) => void) => {
		rpcInput.lineHandler = onLine;
		return () => {
			rpcInput.lineHandler = undefined;
		};
	}),
}));

const fixture = fileURLToPath(new URL("../../fixtures/rpc-console-session.ts", import.meta.url));
const hasBun = spawnSync("bun", ["--version"]).status === 0;

function expectJsonLines(stdout: string): Array<Record<string, unknown>> {
	const lines = stdout.split("\n").filter((line) => line.length > 0);
	expect(lines.length).toBeGreaterThan(0);
	return lines.map((line) => JSON.parse(line) as Record<string, unknown>);
}

describe("console output in RPC mode", () => {
	const harnesses: Harness[] = [];
	afterEach(() => {
		for (const harness of harnesses.splice(0)) harness.cleanup();
	});

	test("an extension's console.log goes to stderr and stdout stays JSON lines (Node)", async () => {
		const stdout: string[] = [];
		const stderr: string[] = [];
		const originalStdoutWrite = process.stdout.write;
		const originalStderrWrite = process.stderr.write;
		const originalLog = console.log;
		const originalInfo = console.info;
		// Vitest replaces the global console with its own; use one over process.stdout as Node's global console is.
		const nodeConsole = new Console({ stdout: process.stdout, stderr: process.stderr });
		console.log = nodeConsole.log.bind(nodeConsole);
		console.info = nodeConsole.info.bind(nodeConsole);
		process.stdout.write = ((chunk: string, ...rest: unknown[]) => {
			stdout.push(String(chunk));
			(rest.find((arg) => typeof arg === "function") as (() => void) | undefined)?.();
			return true;
		}) as typeof process.stdout.write;
		process.stderr.write = ((chunk: string, ...rest: unknown[]) => {
			stderr.push(String(chunk));
			(rest.find((arg) => typeof arg === "function") as (() => void) | undefined)?.();
			return true;
		}) as typeof process.stderr.write;

		try {
			const harness = await createHarness({
				extensionFactories: [
					(pi) => {
						pi.on("agent_start", () => {
							console.log("console-marker log", { a: 1 });
							console.info("console-marker info");
						});
					},
				],
			});
			harnesses.push(harness);
			harness.setResponses([fauxAssistantMessage("done")]);
			void runRpcMode({
				session: harness.session,
				dispose: async () => {},
				setRebindSession: () => {},
			} as unknown as AgentSessionRuntime);
			await vi.waitFor(() => expect(rpcInput.lineHandler).toBeDefined());
			rpcInput.lineHandler?.(JSON.stringify({ id: "p", type: "prompt", message: "hi" }));
			await vi.waitFor(() => expect(stdout.join("")).toContain('"type":"agent_end"'));
		} finally {
			restoreStdout();
			process.stdout.write = originalStdoutWrite;
			process.stderr.write = originalStderrWrite;
			console.log = originalLog;
			console.info = originalInfo;
		}

		expect(stdout.join("")).not.toContain("console-marker");
		expectJsonLines(stdout.join(""));
		expect(stderr.join("")).toContain("console-marker log { a: 1 }\n");
		expect(stderr.join("")).toContain("console-marker info\n");
	});

	test.skipIf(!hasBun)("every stdout console method of an extension goes to stderr under Bun", async () => {
		const child = spawn("bun", [fixture], { stdio: ["pipe", "pipe", "pipe"] });
		let stdout = "";
		let stderr = "";
		child.stdout.setEncoding("utf8");
		child.stderr.setEncoding("utf8");
		child.stdout.on("data", (chunk: string) => {
			stdout += chunk;
			if (stdout.includes('"type":"agent_end"')) child.stdin.end();
		});
		child.stderr.on("data", (chunk: string) => {
			stderr += chunk;
		});
		const exitCode = new Promise<number | null>((resolve) => child.on("exit", resolve));
		child.stdin.write(`${JSON.stringify({ id: "p", type: "prompt", message: "hi" })}\n`);
		expect(await exitCode).toBe(0);

		const events = expectJsonLines(stdout);
		expect(events.some((event) => event.type === "agent_end")).toBe(true);
		expect(stdout).not.toContain("console-marker");
		for (const marker of [
			"console-marker extension loaded { a: 1 }\n",
			"console-marker log x\n",
			"console-marker info\n",
			"console-marker debug\n",
			"console-marker dir",
			"console-marker dirxml\n",
			"console-marker table",
			"Trace: console-marker trace\n",
			"console-marker count: 1\n",
			"console-marker group\n  console-marker in group\n",
			"console-marker write\n",
		]) {
			expect(stderr).toContain(marker);
		}
	});
});
