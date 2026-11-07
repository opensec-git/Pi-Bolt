// node --test android/app/src/test/js/pibolt-preview.test.mjs (also run by PreviewExtensionTest when node is on PATH).
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import extension, { ask, requestFor, resultOf, SOCKET_VAR, TOOLS } from "../../main/assets/extensions/pibolt-preview.js";

const dir = mkdtempSync(join(tmpdir(), "pibolt-preview-"));
after(() => rmSync(dir, { recursive: true, force: true }));
let n = 0;

/** A fake app: a unix socket that answers each request line with answer(request), and the requests it got. */
async function fakeApp(answer) {
	const path = join(dir, `s${n++}`);
	const requests = [];
	const server = createServer((socket) => {
		let buffered = "";
		socket.on("data", (chunk) => {
			buffered += chunk;
			const newline = buffered.indexOf("\n");
			if (newline < 0) return;
			const request = JSON.parse(buffered.slice(0, newline));
			requests.push(request);
			const reply = answer(request);
			if (reply !== undefined) socket.end(`${JSON.stringify(reply)}\n`);
		});
	});
	await new Promise((resolve) => server.listen(path, resolve));
	return { path, requests, close: () => new Promise((resolve) => server.close(resolve)) };
}

function fakePi() {
	const tools = new Map();
	return { tools, registerTool: (tool) => tools.set(tool.name, tool) };
}

test("tools register only when the app gave a socket", () => {
	const saved = process.env[SOCKET_VAR];
	try {
		delete process.env[SOCKET_VAR];
		const none = fakePi();
		extension(none);
		assert.equal(none.tools.size, 0);
		process.env[SOCKET_VAR] = "/nonexistent/socket";
		const some = fakePi();
		extension(some);
		assert.deepEqual([...some.tools.keys()], TOOLS.map((t) => t.name));
		for (const tool of some.tools.values()) {
			assert.equal(tool.executionMode, "sequential");
			assert.equal(tool.parameters.type, "object");
		}
		assert.ok(some.tools.get("preview_screenshot").promptGuidelines.every((g) => g.includes("preview_")));
	} finally {
		if (saved === undefined) delete process.env[SOCKET_VAR];
		else process.env[SOCKET_VAR] = saved;
	}
});

test("click takes a selector or a point, not both and not neither", () => {
	assert.deepEqual(requestFor("preview_click", { selector: " #save " }, "/p"), { request: { op: "click", cwd: "/p", args: { selector: "#save" } } });
	assert.deepEqual(requestFor("preview_click", { x: 10, y: 20.5 }, "/p").request.args, { x: 10, y: 20.5 });
	assert.match(requestFor("preview_click", { selector: "a", x: 1, y: 2 }).error, /not both/);
	assert.match(requestFor("preview_click", {}).error, /selector/);
	assert.match(requestFor("preview_click", { x: 1 }).error, /x and y/);
	assert.match(requestFor("preview_click", { x: -1, y: 2 }).error, /0 or more/);
	assert.match(requestFor("preview_click", { x: Number.NaN, y: 2 }).error, /numbers/);
	assert.match(requestFor("preview_click", { selector: "a".repeat(1001) }).error, /too long/);
});

test("open accepts only the dev server on loopback, or a path", () => {
	assert.deepEqual(requestFor("preview_open", {}, "/p").request, { op: "open", cwd: "/p", args: {} });
	assert.deepEqual(requestFor("preview_open", { url: "http://localhost:5173/about", reload: true }).request.args, { url: "http://localhost:5173/about", reload: true });
	assert.equal(requestFor("preview_open", { url: "http://127.0.0.1:3000" }).request.args.url, "http://127.0.0.1:3000");
	assert.equal(requestFor("preview_open", { url: "/settings?tab=2" }).request.args.url, "/settings?tab=2");
	for (const url of ["https://example.com", "http://localhost.evil.com:5173/", "file:///etc/passwd", "javascript:alert(1)", "http://10.0.0.2:80/", "//example.com"]) {
		assert.ok(requestFor("preview_open", { url }).error, url);
	}
});

test("console, type, scroll and eval arguments", () => {
	assert.deepEqual(requestFor("preview_console", { since: 12.7, errorsOnly: true }).request.args, { since: 12, errorsOnly: true });
	assert.ok(requestFor("preview_console", { since: -1 }).error);
	assert.deepEqual(requestFor("preview_type", { selector: "#q", text: "hi", submit: true }).request.args, { selector: "#q", text: "hi", submit: true });
	assert.deepEqual(requestFor("preview_type", { selector: "#q", text: "" }).request.args, { selector: "#q", text: "" });
	assert.ok(requestFor("preview_type", { selector: "#q" }).error);
	assert.ok(requestFor("preview_type", { text: "x" }).error);
	assert.deepEqual(requestFor("preview_scroll", { to: "bottom" }).request.args, { to: "bottom" });
	assert.ok(requestFor("preview_scroll", {}).error);
	assert.ok(requestFor("preview_scroll", { to: "left" }).error);
	assert.ok(requestFor("preview_eval", { js: "  " }).error);
	assert.equal(requestFor("preview_eval", { js: "document.title" }).request.op, "eval");
	assert.ok(requestFor("preview_nope", {}).error);
});

test("a reply becomes text and an image block; an error reply throws", () => {
	const shot = resultOf({ ok: true, text: "Screenshot", image: { data: "iVBOR", mimeType: "image/png" }, details: { width: 10 } });
	assert.deepEqual(shot.content, [
		{ type: "text", text: "Screenshot" },
		{ type: "image", data: "iVBOR", mimeType: "image/png" },
	]);
	assert.deepEqual(shot.details, { width: 10 });
	assert.deepEqual(resultOf({ ok: true }).content, [{ type: "text", text: "Done." }]);
	assert.throws(() => resultOf({ ok: false, error: "No dev server is running" }), /No dev server/);
	assert.throws(() => resultOf(null), /No answer/);
});

test("execute round-trips through the app's socket with the session's cwd", async () => {
	const app = await fakeApp((request) =>
		request.op === "screenshot" ? { ok: true, text: "Screenshot of http://localhost:5173/", image: { data: "AAAA", mimeType: "image/png" } } : { ok: false, error: "nope" },
	);
	const saved = process.env[SOCKET_VAR];
	process.env[SOCKET_VAR] = app.path;
	try {
		const pi = fakePi();
		extension(pi);
		const result = await pi.tools.get("preview_screenshot").execute("c1", {}, undefined, undefined, { cwd: "/projects/site" });
		assert.equal(result.content[1].data, "AAAA");
		assert.deepEqual(app.requests, [{ op: "screenshot", cwd: "/projects/site", args: {} }]);
		await assert.rejects(pi.tools.get("preview_console").execute("c2", {}, undefined, undefined, { cwd: "/projects/site" }), /nope/);
		// Invalid arguments never reach the app.
		await assert.rejects(pi.tools.get("preview_click").execute("c3", {}, undefined, undefined, { cwd: "/projects/site" }), /selector/);
		assert.equal(app.requests.length, 2);
	} finally {
		if (saved === undefined) delete process.env[SOCKET_VAR];
		else process.env[SOCKET_VAR] = saved;
		await app.close();
	}
});

test("ask fails clearly without the app, on abort and on timeout", async () => {
	await assert.rejects(ask(join(dir, "missing"), { op: "screenshot" }), /not listening/);
	const silent = await fakeApp(() => undefined);
	try {
		const controller = new AbortController();
		const pending = ask(silent.path, { op: "screenshot" }, { signal: controller.signal });
		setTimeout(() => controller.abort(), 50);
		await assert.rejects(pending, /Cancelled/);
		await assert.rejects(ask(silent.path, { op: "screenshot" }, { timeoutMs: 50 }), /in time/);
	} finally {
		await silent.close();
	}
});
