import { afterEach, describe, expect, test, vi } from "vitest";
import type { AgentSessionRuntime } from "../../../src/core/agent-session-runtime.ts";
import { runRpcMode } from "../../../src/modes/rpc/rpc-mode.ts";
import { createHarness, type Harness } from "../harness.ts";

// hunt-e2e-6 (upstream): an input line of `null` failed reading the command id, also in the error response, and the
// unhandled rejection ended the process.

const rpcIo = vi.hoisted(() => ({
	outputLines: [] as string[],
	lineHandler: undefined as ((line: string) => void) | undefined,
}));

vi.mock("../../../src/core/output-guard.js", () => ({
	flushRawStdout: vi.fn(async () => {}),
	takeOverStdout: vi.fn(),
	waitForRawStdoutBackpressure: vi.fn(async () => {}),
	writeRawStdout: (line: string) => {
		rpcIo.outputLines.push(line);
	},
}));

vi.mock("../../../src/modes/interactive/theme/theme.js", () => ({ theme: {} }));

vi.mock("../../../src/modes/rpc/jsonl.js", () => ({
	attachJsonlLineReader: vi.fn((_stream: NodeJS.ReadableStream, onLine: (line: string) => void) => {
		rpcIo.lineHandler = onLine;
		return () => {
			rpcIo.lineHandler = undefined;
		};
	}),
	serializeJsonLine: (value: unknown) => `${JSON.stringify(value)}\n`,
}));

type NodeListener = Parameters<typeof process.on>[1];

type ListenerSnapshot = {
	stdinEnd: NodeListener[];
	signals: Map<NodeJS.Signals, NodeListener[]>;
};

function takeListenerSnapshot(): ListenerSnapshot {
	const signals: NodeJS.Signals[] = process.platform === "win32" ? ["SIGTERM"] : ["SIGTERM", "SIGHUP"];
	return {
		stdinEnd: process.stdin.listeners("end") as NodeListener[],
		signals: new Map(signals.map((signal) => [signal, process.listeners(signal) as NodeListener[]])),
	};
}

function restoreListeners(snapshot: ListenerSnapshot): void {
	for (const listener of process.stdin.listeners("end") as NodeListener[]) {
		if (!snapshot.stdinEnd.includes(listener)) {
			process.stdin.off("end", listener);
		}
	}

	for (const [signal, previousListeners] of snapshot.signals) {
		for (const listener of process.listeners(signal) as NodeListener[]) {
			if (!previousListeners.includes(listener)) {
				process.off(signal, listener);
			}
		}
	}
}

function parseOutputLines(): Array<Record<string, unknown>> {
	return rpcIo.outputLines
		.flatMap((line) => line.split("\n"))
		.filter((line) => line.trim().length > 0)
		.map((line) => JSON.parse(line) as Record<string, unknown>);
}

function createRuntimeHost(harness: Harness): AgentSessionRuntime {
	return {
		session: harness.session,
		newSession: vi.fn(async () => ({ cancelled: true })),
		switchSession: vi.fn(async () => ({ cancelled: true })),
		fork: vi.fn(async () => ({ cancelled: true, selectedText: "" })),
		dispose: vi.fn(async () => {}),
		setRebindSession: vi.fn(),
	} as unknown as AgentSessionRuntime;
}

describe("RPC input lines that are not objects", () => {
	afterEach(() => {
		rpcIo.outputLines = [];
		rpcIo.lineHandler = undefined;
	});

	test("answers them with a parse error and keeps serving", async () => {
		const listenerSnapshot = takeListenerSnapshot();
		const harness = await createHarness();
		const unhandled: unknown[] = [];
		const onUnhandled = (reason: unknown) => unhandled.push(reason);
		process.on("unhandledRejection", onUnhandled);

		try {
			void runRpcMode(createRuntimeHost(harness));
			await vi.waitFor(() => expect(rpcIo.lineHandler).toBeDefined());

			for (const line of ["null", "42", "true", '"text"', "[1,2]"]) rpcIo.lineHandler?.(line);
			rpcIo.lineHandler?.(JSON.stringify({ id: "after", type: "foobar" }));

			await vi.waitFor(() => {
				expect(parseOutputLines()).toContainEqual({
					id: "after",
					type: "response",
					command: "foobar",
					success: false,
					error: "Unknown command: foobar",
				});
			});
			const parseErrors = parseOutputLines().filter((line) => line.command === "parse");
			expect(parseErrors).toHaveLength(5);
			for (const response of parseErrors) {
				expect(response).toEqual({
					type: "response",
					command: "parse",
					success: false,
					error: "Failed to parse command: not a JSON object",
				});
			}
			expect(unhandled).toEqual([]);
		} finally {
			process.off("unhandledRejection", onUnhandled);
			harness.cleanup();
			restoreListeners(listenerSnapshot);
		}
	});
});
