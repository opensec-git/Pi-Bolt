// hunt-core: a dialog that ACP or MCP mode gives up on (timeout, abort) is dropped (finding hunt-core-3).
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { JsonRpcConnection } from "../src/extensions/json-rpc.ts";
import { McpServer } from "../src/extensions/mcp-server/mode.ts";

describe("MCP elicitation that times out", () => {
	const dirs: string[] = [];
	afterEach(() => {
		for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
	});

	it("stops waiting for the client's answer and tells the client", async () => {
		const dir = mkdtempSync(join(tmpdir(), "hunt-core-mcp-"));
		dirs.push(dir);
		const written: Array<Record<string, unknown>> = [];
		const server = new McpServer(
			{ createRuntime: (() => Promise.reject(new Error("unused"))) as never, agentDir: dir, cwd: dir },
			(line) => written.push(JSON.parse(line) as Record<string, unknown>),
		);
		server.connection.receive(
			JSON.stringify({
				jsonrpc: "2.0",
				id: 1,
				method: "initialize",
				params: { protocolVersion: "2025-06-18", capabilities: { elicitation: {} } },
			}),
		);
		// Ten dialogs of an extension that each wait 10 ms for a user who is not there.
		for (let i = 0; i < 10; i++) {
			const answer = await server.elicit("Proceed?", { confirm: { type: "boolean" } }, { timeout: 10 });
			expect(answer).toBeUndefined();
		}
		const requests = written.filter((message) => message.method === "elicitation/create");
		expect(requests).toHaveLength(10);
		// MCP: a sender that no longer waits for a request's result SHOULD send notifications/cancelled for it.
		const cancelled = written.filter((message) => message.method === "notifications/cancelled");
		expect
			.soft(cancelled.map((message) => (message.params as { requestId: unknown }).requestId))
			.toEqual(requests.map((request) => request.id));
		// No request stays pending on the connection after its caller stopped waiting.
		const pending = (server.connection as unknown as { pending: Map<unknown, unknown> }).pending;
		expect(pending.size).toBe(0);
	});
});

describe("JsonRpcConnection requests that are dropped", () => {
	it("drops a request whose signal aborts, ignores a late answer, and tells only a client that asks to be told", async () => {
		const written: Array<Record<string, unknown>> = [];
		const dropped: unknown[] = [];
		const connection = new JsonRpcConnection({
			write: (line) => written.push(JSON.parse(line) as Record<string, unknown>),
			onRequest: async () => ({}),
			onNotification: () => {},
			onDrop: (id) => dropped.push(id),
		});
		const controller = new AbortController();
		const request = connection.request("session/request_permission", {}, controller.signal);
		controller.abort();
		await expect(request).rejects.toBeDefined();
		expect(dropped).toEqual([written[0].id]);
		connection.receive(JSON.stringify({ jsonrpc: "2.0", id: written[0].id, result: { late: true } }));
		const pending = (connection as unknown as { pending: Map<unknown, unknown> }).pending;
		expect(pending.size).toBe(0);

		// An answer that comes first settles the request, and a later abort changes nothing.
		const answered = new AbortController();
		const second = connection.request("session/request_permission", {}, answered.signal);
		connection.receive(JSON.stringify({ jsonrpc: "2.0", id: written[1].id, result: { ok: true } }));
		expect(await second).toEqual({ ok: true });
		answered.abort();
		expect(dropped).toHaveLength(1);
	});
});
