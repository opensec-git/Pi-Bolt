// Built-in modules in an executable whose static heap holds their code (built as Pi is): each is one object however it is
// reached, its functions' source text is the module's own, and what they throw is unchanged. The output is compared with
// builtins.expected, which `bun builtins.mjs` (no executable) prints too. Also the Date header of a server.
import fs, { readFileSync } from "node:fs";
import * as fsNamespace from "node:fs";
import { createRequire } from "node:module";
import stream, { Readable } from "node:stream";
import { EventEmitter } from "node:events";
import crypto from "node:crypto";
import tty from "node:tty";
import util from "node:util";
import assert from "node:assert";

const require = createRequire(import.meta.url);
const out = [];
const same = (name, a, b) => out.push(`${name}: ${a === b ? "same" : "DIFFERENT"}`);

// Identity, whichever way a module is reached.
same("fs default = require", fs, require("node:fs"));
same("fs default = require without node:", fs, require("fs"));
same("fs default = getBuiltinModule", fs, process.getBuiltinModule("node:fs"));
same("fs named = property", readFileSync, fs.readFileSync);
same("fs namespace = named", fsNamespace.readFileSync, readFileSync);
same("stream default = require", stream, require("node:stream"));
same("Readable = stream.Readable", Readable, stream.Readable);
same("Readable = require('stream').Readable", Readable, require("stream").Readable);
same("EventEmitter = require('events')", EventEmitter, require("node:events"));
same("crypto = require", crypto, require("node:crypto"));
same("tty = require", tty, require("node:tty"));
same("util.promisify = require('util').promisify", util.promisify, require("node:util").promisify);
same("assert = require", assert, require("node:assert"));

// Source text of functions the internal modules define (Function.prototype.toString reads the module's text).
const text = (f) => {
	const s = Function.prototype.toString.call(f);
	let h = 0;
	for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
	return `${s.length} ${(h >>> 0).toString(16)} ${JSON.stringify(s.slice(0, 40))}`;
};
for (const [name, f] of [
	["Readable", Readable],
	["Readable.prototype.pipe", Readable.prototype.pipe],
	["EventEmitter.prototype.emit", EventEmitter.prototype.emit],
	["util.inspect", util.inspect],
	["util.promisify", util.promisify],
	["assert.deepStrictEqual", assert.deepStrictEqual],
	["tty.WriteStream", tty.WriteStream],
	["fs.promises.readFile", fs.promises.readFile],
]) out.push(`text ${name}: ${text(f)}`);

// Errors thrown inside them: class, code, message, and the name of the function at the top of the stack.
const top = (e) =>
	(String(e.stack).split("\n").find((l) => l.trim().startsWith("at ")) || "")
		.trim()
		.split(" (")[0]
		.replace(/^at .*(builtins\.mjs|\$bunfs\/root\/).*/, "at this file");
for (const [name, f] of [
	["readFileSync of a missing file", () => readFileSync("/nonexistent/w5")],
	["Readable.from(1)", () => Readable.from(1)],
	["EventEmitter error event", () => new EventEmitter().emit("error", new Error("boom"))],
	["assert.strictEqual", () => assert.strictEqual(1, 2)],
	["util.promisify(1)", () => util.promisify(1)],
	["createHash of an unknown algorithm", () => crypto.createHash("w5-none")],
]) {
	try {
		f();
		out.push(`error ${name}: none`);
	} catch (e) {
		out.push(`error ${name}: ${e.constructor.name} ${e.code ?? "-"} ${JSON.stringify(String(e.message).split("\n")[0])} ${top(e)}`);
	}
}

// A server's Date header: an IMF-fixdate of the time it answered.
const server = Bun.serve({ port: 0, fetch: () => new Response("ok") });
const before = Math.floor(Date.now() / 1000) * 1000;
const date = (await fetch(`http://127.0.0.1:${server.port}/`)).headers.get("date");
const after = Date.now();
server.stop(true);
const parsed = Date.parse(date);
out.push(`date header: ${new Date(parsed).toUTCString() === date && parsed >= before - 1000 && parsed <= after ? "ok" : `BAD ${date}`}`);

console.log(out.join("\n"));
