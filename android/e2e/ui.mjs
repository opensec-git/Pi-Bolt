// Queries a `uiautomator dump` XML file. Used by lib.sh; no dependencies.
//
//   node ui.mjs find  <dump.xml> <query> [--index N] [--row-of <query>] [--clickable]
//        prints "x y left top right bottom" of the match (center first), exit 1 when none
//   node ui.mjs count <dump.xml> <query> [--clickable]
//   node ui.mjs texts <dump.xml>          every non-empty text / content-desc, one per line (for failure reports)
//   node ui.mjs send  <dump.xml>          center of the composer's send/stop button (no text or desc of its own:
//                                         the rightmost clickable on the row of "Attach images")
//   node ui.mjs field <dump.xml> [query]  text of the focused (or matching) editable field
//
// Queries: text=Foo (exact), text~=Foo (contains), desc=Foo, desc~=Foo, any=Foo (text or desc exact),
// any~=Foo (contains), re=<regex> (text or desc). Matching ignores case for ~= and collapses whitespace.
import { readFileSync } from "node:fs";

const [cmd, file, ...rest] = process.argv.slice(2);
const xml = readFileSync(file, "utf8");

const decode = (s) =>
	s
		.replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
		.replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
		.replace(/&quot;/g, '"')
		.replace(/&apos;/g, "'")
		.replace(/&lt;/g, "<")
		.replace(/&gt;/g, ">")
		.replace(/&amp;/g, "&");
const norm = (s) => s.replace(/[\u2060\u200b\u00a0]/g, " ").replace(/\s+/g, " ").trim();

const nodes = [];
for (const m of xml.matchAll(/<node\b([^>]*?)\/?>/g)) {
	const attrs = {};
	for (const a of m[1].matchAll(/([\w-]+)="([^"]*)"/g)) attrs[a[1]] = decode(a[2]);
	const b = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(attrs.bounds ?? "");
	if (!b) continue;
	const [l, t, r, btm] = b.slice(1).map(Number);
	if (r <= l || btm <= t) continue;
	nodes.push({
		text: norm(attrs.text ?? ""),
		desc: norm(attrs["content-desc"] ?? ""),
		cls: attrs.class ?? "",
		clickable: attrs.clickable === "true" || attrs["long-clickable"] === "true",
		focused: attrs.focused === "true",
		editable: /EditText/.test(attrs.class ?? ""),
		l,
		t,
		r,
		b: btm,
		x: Math.round((l + r) / 2),
		y: Math.round((t + btm) / 2),
	});
}

function matcher(query) {
	const eq = query.indexOf("=");
	if (eq < 0) throw new Error(`bad query: ${query}`);
	let key = query.slice(0, eq);
	const value = norm(query.slice(eq + 1));
	const contains = key.endsWith("~");
	if (contains) key = key.slice(0, -1);
	const lower = value.toLowerCase();
	const test = (s) => (contains ? s.toLowerCase().includes(lower) : s === value);
	switch (key) {
		case "text":
			return (n) => n.text !== "" && test(n.text);
		case "desc":
			return (n) => n.desc !== "" && test(n.desc);
		case "any":
			return (n) => (n.text !== "" && test(n.text)) || (n.desc !== "" && test(n.desc));
		case "re": {
			const re = new RegExp(query.slice(eq + 1), "i");
			return (n) => re.test(n.text) || re.test(n.desc);
		}
		default:
			throw new Error(`bad query key: ${key}`);
	}
}

function option(name) {
	const i = rest.indexOf(name);
	return i >= 0 ? rest[i + 1] : undefined;
}
const flag = (name) => rest.includes(name);

function select(query) {
	let found = nodes.filter(matcher(query));
	if (flag("--clickable")) found = found.filter((n) => n.clickable);
	// Prefer the smallest node: a merged parent and its child can both carry the text.
	return found;
}

const out = (n) => console.log(`${n.x} ${n.y} ${n.l} ${n.t} ${n.r} ${n.b}`);

switch (cmd) {
	case "find": {
		let found = select(rest[0]);
		const rowOf = option("--row-of");
		if (rowOf) {
			const anchor = nodes.find(matcher(rowOf));
			if (!anchor) process.exit(1);
			found = found.filter((n) => Math.abs(n.y - anchor.y) < Math.max(90, (anchor.b - anchor.t) * 1.5)).sort((a, b) => Math.abs(a.y - anchor.y) - Math.abs(b.y - anchor.y));
		}
		if (found.length === 0) process.exit(1);
		const index = Number(option("--index") ?? 0);
		const n = index < 0 ? found[found.length + index] : found[index];
		if (!n) process.exit(1);
		out(n);
		break;
	}
	case "count":
		console.log(select(rest[0]).length);
		break;
	case "texts":
		for (const n of nodes) {
			const parts = [n.text && `text="${n.text}"`, n.desc && `desc="${n.desc}"`].filter(Boolean);
			if (parts.length) console.log(`${parts.join(" ")} [${n.l},${n.t}][${n.r},${n.b}]${n.clickable ? " clickable" : ""}`);
		}
		break;
	case "send": {
		const attach = nodes.find((n) => n.desc.startsWith("Attach images"));
		if (!attach) process.exit(1);
		const row = nodes.filter((n) => n.clickable && n !== attach && Math.abs(n.y - attach.y) < 40 && n.r - n.l < 260).sort((a, b) => b.r - a.r);
		if (row.length === 0) process.exit(1);
		out(row[0]);
		break;
	}
	case "field": {
		const edits = nodes.filter((n) => n.editable);
		const n = rest[0] ? edits.find(matcher(rest[0])) : (edits.find((e) => e.focused) ?? edits[edits.length - 1]);
		if (!n) process.exit(1);
		console.log(n.text);
		break;
	}
	default:
		console.error(`unknown command ${cmd}`);
		process.exit(2);
}
