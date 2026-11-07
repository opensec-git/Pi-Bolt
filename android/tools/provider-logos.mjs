#!/usr/bin/env node
// Converts provider and model-maker marks from Lobe Icons (@lobehub/icons-static-svg, MIT) into Android
// VectorDrawables: android/app/src/main/res/drawable/ic_provider_<slug>.xml.
//
//   node android/tools/provider-logos.mjs              fetches the pinned package with `npm pack --ignore-scripts`
//                                                      into a temp dir (checked against PINNED_INTEGRITY)
//   node android/tools/provider-logos.mjs <dir>        uses an unpacked package (the folder with package.json)
//
// Only the monochrome marks are used (`<name>.svg`, fill="currentColor"): the app tints them with the theme's ink.
// The converter accepts nothing but <svg>, <title> and <path d fill-rule clip-rule fill-opacity>; anything else in a
// file (gradients, transforms, strokes, other elements) is an error, so a changed upstream file is never half-drawn.
// Path data is re-tokenised and written with explicit separators (SVG's packed arc flags, "a1 1 0 00.5.5", are
// read differently by some Android path parsers).
//
// Treatment follows Pi-Bolt Desktop (src/lib/modelLogos.tsx): Claude's mark for Claude models and Anthropic's for the
// provider, Grok's for xAI, Google's "G" for Gemma, Gemini's for Gemini and the Google provider.
//
// The brand list must match `Brand` in ui/providers/ProviderLogos.kt (ProviderLogosTest checks both ways).
// The licence text is res/raw/license_lobe_icons.txt (Settings > About > Open-source licences).

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const PACKAGE = "@lobehub/icons-static-svg";
const PINNED_VERSION = "1.98.0";
const PINNED_INTEGRITY =
	"sha512-soyLV2g74ujV+9BBymPwXrorJ+kQsEz7WvVd4uzhN2wZnd4Jbyc2FsmyjZBosGaufOirCs1RGJQlLe6l9bjwLg==";

/** Drawable slug -> Lobe Icons file (without .svg). */
const BRANDS = {
	anthropic: "anthropic",
	claude: "claude",
	openai: "openai",
	gemini: "gemini",
	google: "google",
	xai: "grok",
	mistral: "mistral",
	deepseek: "deepseek",
	groq: "groq",
	openrouter: "openrouter",
	ollama: "ollama",
	moonshot: "moonshot",
	kimi: "kimi",
	qwen: "qwen",
	meta: "meta",
	cerebras: "cerebras",
	bedrock: "bedrock",
	aws: "aws",
	azure: "azure",
	githubcopilot: "githubcopilot",
	huggingface: "huggingface",
	together: "together",
	fireworks: "fireworks",
	perplexity: "perplexity",
	cohere: "cohere",
	zai: "zai",
	minimax: "minimax",
	vercel: "vercel",
	nvidia: "nvidia",
	baseten: "baseten",
	opencode: "opencode",
	lmstudio: "lmstudio",
	vllm: "vllm",
	microsoft: "microsoft",
	cloudflare: "cloudflare",
	xiaomi: "xiaomimimo",
	ling: "ling",
};

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, "..", "app", "src", "main", "res", "drawable");

function fetchPackage() {
	const dir = mkdtempSync(join(tmpdir(), "lobe-icons-"));
	execFileSync("npm", ["pack", `${PACKAGE}@${PINNED_VERSION}`, "--ignore-scripts", "--loglevel", "error", "--pack-destination", dir], {
		stdio: ["ignore", "ignore", "inherit"],
	});
	const tgz = readdirSync(dir).find((f) => f.endsWith(".tgz"));
	if (!tgz) throw new Error("npm pack wrote no archive");
	const integrity = `sha512-${createHash("sha512").update(readFileSync(join(dir, tgz))).digest("base64")}`;
	if (integrity !== PINNED_INTEGRITY) throw new Error(`integrity mismatch: ${integrity}`);
	execFileSync("tar", ["xzf", join(dir, tgz), "-C", dir]);
	return { dir: join(dir, "package"), cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

function attrs(tag) {
	const out = {};
	for (const m of tag.matchAll(/([a-zA-Z:-]+)="([^"]*)"/g)) out[m[1]] = m[2];
	return out;
}

const ARGS = { m: 2, l: 2, h: 1, v: 1, c: 6, s: 4, q: 4, t: 2, a: 7, z: 0 };

/** SVG path data with every number and arc flag separated: "M1 2 a1 1 0 0 0 .5 .5 z". */
export function normalizePath(d) {
	const out = [];
	let i = 0;
	let cmd = null;
	let n = 0;
	const number = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?/;
	while (i < d.length) {
		const ch = d[i];
		if (/[\s,]/.test(ch)) {
			i++;
			continue;
		}
		if (/[a-zA-Z]/.test(ch)) {
			const lower = ch.toLowerCase();
			if (!(lower in ARGS)) throw new Error(`unknown path command ${ch}`);
			if (cmd && ARGS[cmd.toLowerCase()] > 0 && n % ARGS[cmd.toLowerCase()] !== 0) throw new Error(`${cmd} has ${n} arguments`);
			cmd = ch;
			n = 0;
			out.push(ch);
			i++;
			continue;
		}
		if (!cmd) throw new Error("path data starts with a number");
		const lower = cmd.toLowerCase();
		if (lower === "z") throw new Error("number after z");
		if (lower === "a" && (n % 7 === 3 || n % 7 === 4)) {
			if (ch !== "0" && ch !== "1") throw new Error(`bad arc flag ${ch}`);
			out.push(ch);
			i++;
			n++;
			continue;
		}
		const m = number.exec(d.slice(i));
		if (!m) throw new Error(`bad path data at ${i}: ${d.slice(i, i + 12)}`);
		out.push(m[0]);
		i += m[0].length;
		n++;
	}
	if (cmd && ARGS[cmd.toLowerCase()] > 0 && n % ARGS[cmd.toLowerCase()] !== 0) throw new Error(`${cmd} has ${n} arguments`);
	return out.join(" ");
}

export function convert(svg, source, version) {
	const body = svg.replace(/<title>[^<]*<\/title>/g, "");
	const tags = [...body.matchAll(/<\s*([a-zA-Z]+)([^>]*?)\/?>/g)];
	const root = tags.find((t) => t[1] === "svg");
	if (!root) throw new Error(`${source}: no <svg>`);
	const r = attrs(root[2]);
	if (r.fill !== "currentColor") throw new Error(`${source}: not a monochrome mark (fill=${r.fill})`);
	const [x, y, w, h] = (r.viewBox ?? "").trim().split(/[\s,]+/).map(Number);
	if (![x, y, w, h].every(Number.isFinite) || x !== 0 || y !== 0) throw new Error(`${source}: unsupported viewBox ${r.viewBox}`);
	const rootRule = r["fill-rule"] ?? "nonzero";
	const paths = [];
	for (const [, name, rest] of tags) {
		if (name === "svg") continue;
		if (name !== "path") throw new Error(`${source}: unsupported element <${name}>`);
		const a = attrs(rest);
		for (const key of Object.keys(a)) {
			if (!["d", "fill-rule", "clip-rule", "fill-opacity"].includes(key)) throw new Error(`${source}: unsupported attribute ${key}`);
		}
		if (!a.d) throw new Error(`${source}: <path> without d`);
		const rule = a["fill-rule"] ?? rootRule;
		const alpha = a["fill-opacity"] !== undefined ? Number(a["fill-opacity"]) : 1;
		if (!(alpha > 0 && alpha <= 1)) throw new Error(`${source}: bad fill-opacity ${a["fill-opacity"]}`);
		const lines = ['    <path', '        android:fillColor="#FFFFFFFF"'];
		if (rule === "evenodd") lines.push('        android:fillType="evenOdd"');
		if (alpha < 1) lines.push(`        android:fillAlpha="${alpha}"`);
		lines.push(`        android:pathData="${normalizePath(a.d)}" />`);
		paths.push(lines.join("\n"));
	}
	if (paths.length === 0) throw new Error(`${source}: no paths`);
	return [
		'<?xml version="1.0" encoding="utf-8"?>',
		`<!-- Generated by android/tools/provider-logos.mjs from Lobe Icons (${PACKAGE} ${version}, MIT): ${source}. Do not edit. -->`,
		'<vector xmlns:android="http://schemas.android.com/apk/res/android"',
		'    xmlns:tools="http://schemas.android.com/tools"',
		'    android:width="24dp"',
		'    android:height="24dp"',
		`    android:viewportWidth="${w}"`,
		`    android:viewportHeight="${h}"`,
		'    tools:ignore="VectorPath">',
		...paths,
		"</vector>",
		"",
	].join("\n");
}

function main() {
	const given = process.argv[2];
	const pkg = given ? { dir: given, cleanup: () => {} } : fetchPackage();
	try {
		const meta = JSON.parse(readFileSync(join(pkg.dir, "package.json"), "utf8"));
		if (meta.name !== PACKAGE) throw new Error(`not ${PACKAGE}: ${meta.name}`);
		if (meta.license !== "MIT") throw new Error(`licence changed: ${meta.license}`);
		for (const old of readdirSync(outDir).filter((f) => f.startsWith("ic_provider_"))) rmSync(join(outDir, old));
		for (const [slug, file] of Object.entries(BRANDS)) {
			const source = `${file}.svg`;
			const xml = convert(readFileSync(join(pkg.dir, "icons", source), "utf8"), source, meta.version);
			writeFileSync(join(outDir, `ic_provider_${slug}.xml`), xml);
		}
		console.log(`Wrote ${Object.keys(BRANDS).length} drawables from ${PACKAGE} ${meta.version} to ${outDir}`);
	} finally {
		pkg.cleanup();
	}
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
