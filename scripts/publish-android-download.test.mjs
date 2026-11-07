// Tests of scripts/publish-android-download.sh against stand-ins for gh and curl: GitHub's Git Data and Pages APIs over a
// gh-pages branch held in a JSON file, and the site serving whatever its last deployment published.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(new URL("./publish-android-download.sh", import.meta.url));
const VERSION = "0.6.0";
const CERT = "c".repeat(64);
const APK = Buffer.from("not really an apk");

const fake = String.raw`
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
const [tool, ...args] = process.argv.slice(2);
const statePath = process.env.FAKE_STATE;
const state = JSON.parse(readFileSync(statePath, "utf8"));
state.calls.push([tool, ...args]);
const save = () => writeFileSync(statePath, JSON.stringify(state));
const reply = (value) => { save(); process.stdout.write(JSON.stringify(value)); process.exit(0); };
const fail = (message) => { save(); process.stdout.write(JSON.stringify({ message })); process.stderr.write("gh: " + message + "\n"); process.exit(1); };
const option = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
const filesAt = (commit) => state.trees[state.commits[commit].tree];
const deploy = () => { state.live = { ...filesAt(state.head) }; };
if (tool === "curl") {
	const url = new URL(args.find((arg) => arg.startsWith("https://")));
	const name = url.pathname.slice(1);
	if (!(name in state.live)) { save(); process.exit(22); }
	writeFileSync(option("-o"), state.live[name]);
	save();
	process.exit(0);
}
if (args[0] === "release" && args[1] === "download") {
	writeFileSync(join(option("--dir"), state.release.apk), Buffer.from(state.release.bytes, "base64"));
	writeFileSync(join(option("--dir"), state.release.apk + ".sha256"), state.release.sha256 + "  " + state.release.apk + "\n");
	save();
	process.exit(0);
}
if (args[0] === "release" && args[1] === "view") reply({ body: state.release.body });
if (args[0] !== "api") fail("unexpected gh " + args.join(" "));
const method = option("-X") ?? "GET";
const path = args.slice(1).find((arg, i, rest) => !arg.startsWith("-") && !["-X", "-H", "--input"].includes(rest[i - 1]));
const input = option("--input") ? JSON.parse(readFileSync(option("--input"), "utf8")) : undefined;
const repo = "repos/o/r/";
const route = path.slice(repo.length);
const id = (prefix) => prefix + Object.keys(state.trees).length + Object.keys(state.commits).length + Object.keys(state.blobs).length;
if (method === "GET" && route === "git/ref/heads/gh-pages") reply({ object: { sha: state.head } });
if (method === "GET" && route.startsWith("git/commits/")) reply({ tree: { sha: state.commits[route.split("/")[2]].tree } });
if (method === "GET" && route.startsWith("contents/")) {
	const [name, query] = route.slice("contents/".length).split("?ref=");
	const content = state.commits[query] && filesAt(query)[name];
	if (content === undefined) fail("Not Found (HTTP 404)");
	save();
	process.stdout.write(content);
	process.exit(0);
}
if (method === "GET" && route === "pages") reply({ html_url: "https://site.test/" });
if (method === "POST" && route === "git/blobs") {
	const sha = id("blob");
	state.blobs[sha] = Buffer.from(input.content, input.encoding).toString("utf8");
	reply({ sha });
}
if (method === "POST" && route === "git/trees") {
	const sha = id("tree");
	const files = { ...state.trees[input.base_tree] };
	for (const entry of input.tree) files[entry.path] = state.blobs[entry.sha];
	state.trees[sha] = files;
	reply({ sha });
}
if (method === "POST" && route === "git/commits") {
	const sha = id("commit");
	state.commits[sha] = { tree: input.tree, parents: input.parents, message: input.message };
	reply({ sha });
}
if (method === "PATCH" && route === "git/refs/heads/gh-pages") {
	if (state.moveBeforePatch) state.head = state.moveBeforePatch;
	if (!input.force && state.commits[input.sha].parents[0] !== state.head) fail("Update is not a fast forward (HTTP 422)");
	state.head = input.sha;
	if (state.deployOnPush) deploy();
	reply({ object: { sha: input.sha } });
}
if (method === "POST" && route === "pages/builds") {
	if (state.deployOnRequest) deploy();
	reply({ status: "queued" });
}
fail("unexpected gh api " + method + " " + path);
`;

const oldPage = [
	"<title>Pi-Bolt for Android · 0.5.5</title>",
	'<a href="https://github.com/o/r/releases/download/android-v0.5.5/pi-bolt-android-0.5.5.apk">Download</a>',
	'<a href="https://github.com/o/r/releases/tag/android-v0.5.5">android-v0.5.5</a>',
	"<p>Version 0.5.5 · about 180 MB</p>",
	"",
].join("\n");

function releaseJson() {
	return `${JSON.stringify(
		{
			version: VERSION,
			apk: `https://github.com/o/r/releases/download/android-v${VERSION}/pi-bolt-android-${VERSION}.apk`,
			page: `https://github.com/o/r/releases/tag/android-v${VERSION}`,
			size: APK.length,
			sha256: createHash("sha256").update(APK).digest("hex"),
			certSha256: CERT,
		},
		null,
		2,
	)}\n`;
}

function setup(t, overrides = {}) {
	const dir = mkdtempSync(join(tmpdir(), "pi-publish-android-"));
	t.after(() => rmSync(dir, { recursive: true, force: true }));
	writeFileSync(join(dir, "fake.mjs"), fake);
	for (const tool of ["gh", "curl"]) {
		const path = join(dir, tool);
		writeFileSync(path, `#!/bin/sh\nexec "${process.execPath}" "${join(dir, "fake.mjs")}" ${tool} "$@"\n`);
		chmodSync(path, 0o755);
	}
	const files = overrides.files ?? { "android.json": '{"version": "0.5.5"}\n', "android.html": oldPage, "index.html": "home" };
	const state = {
		head: "c0",
		commits: { c0: { tree: "t0", parents: [] } },
		trees: { t0: files },
		blobs: {},
		live: overrides.live ?? { ...files },
		deployOnPush: true,
		deployOnRequest: true,
		release: {
			apk: `pi-bolt-android-${VERSION}.apk`,
			bytes: APK.toString("base64"),
			sha256: createHash("sha256").update(APK).digest("hex"),
			body: `Signing certificate:\n\`\`\`\nSigner #1 certificate SHA-256 digest: ${CERT}\n\`\`\`\n`,
		},
		calls: [],
		...overrides.state,
	};
	const statePath = join(dir, "state.json");
	writeFileSync(statePath, JSON.stringify(state));
	const run = (...extra) => {
		const result = spawnSync("bash", [script, VERSION, ...extra], {
			encoding: "utf8",
			env: {
				...process.env,
				PATH: `${dir}:${process.env.PATH}`,
				FAKE_STATE: statePath,
				GITHUB_REPOSITORY: "o/r",
				PAGES_POLL_SECONDS: "0",
				PAGES_POLL_TRIES: "2",
			},
		});
		return { ...result, state: JSON.parse(readFileSync(statePath, "utf8")) };
	};
	return { run };
}

const writes = (state) =>
	state.calls.filter((call) => call[0] === "gh" && call.includes("-X")).map((call) => `${call[call.indexOf("-X") + 1]} ${call.find((arg) => arg.startsWith("repos/"))}`);

test("commits android.json and android.html together and waits for the push's own Pages build", (t) => {
	const { run } = setup(t);
	const result = run();
	assert.equal(result.status, 0, result.stderr);
	const { state } = result;
	// One commit on top of the old head, made of blobs and a tree, and a ref update that is not forced; no build request.
	assert.deepEqual(writes(state), [
		"POST repos/o/r/git/blobs",
		"POST repos/o/r/git/blobs",
		"POST repos/o/r/git/trees",
		"POST repos/o/r/git/commits",
		"PATCH repos/o/r/git/refs/heads/gh-pages",
	]);
	const commit = state.commits[state.head];
	assert.deepEqual(commit.parents, ["c0"]);
	assert.equal(commit.message, `chore(pages): offer Android app ${VERSION}`);
	const files = state.trees[commit.tree];
	assert.equal(files["android.json"], releaseJson());
	assert.equal(files["index.html"], "home");
	assert.match(files["android.html"], /Pi-Bolt for Android · 0\.6\.0/);
	assert.match(files["android.html"], /releases\/download\/android-v0\.6\.0\/pi-bolt-android-0\.6\.0\.apk/);
	assert.match(files["android.html"], /releases\/tag\/android-v0\.6\.0">android-v0\.6\.0/);
	assert.match(files["android.html"], /Version 0\.6\.0 · about 0 MB/);
	assert.equal(state.live["android.json"], releaseJson());
	assert.match(result.stderr, /site\.test\/ now offers Android app 0\.6\.0/);
});

test("requests one Pages build when the push's build does not update the site", (t) => {
	const { run } = setup(t, { state: { deployOnPush: false } });
	const result = run();
	assert.equal(result.status, 0, result.stderr);
	assert.equal(writes(result.state).filter((call) => call.endsWith("pages/builds")).length, 1);
	assert.equal(result.state.live["android.json"], releaseJson());
});

test("requests a build at most twice, then fails while the site still serves the old files", (t) => {
	const { run } = setup(t, { state: { deployOnPush: false, deployOnRequest: false } });
	const result = run();
	assert.notEqual(result.status, 0);
	assert.equal(writes(result.state).filter((call) => call.endsWith("pages/builds")).length, 2);
	assert.match(result.stderr, /still serves older files than gh-pages/);
});

test("publishes nothing when gh-pages moves meanwhile", (t) => {
	const { run } = setup(t, {
		state: { moveBeforePatch: "c9", commits: { c0: { tree: "t0", parents: [] }, c9: { tree: "t0", parents: ["c0"] } } },
	});
	const result = run();
	assert.notEqual(result.status, 0);
	assert.equal(result.state.head, "c9");
	assert.equal(writes(result.state).filter((call) => call.endsWith("pages/builds")).length, 0);
	assert.match(result.stderr, /gh-pages changed while this ran/);
});

test("creates android.json on a gh-pages without one, and leaves a missing android.html alone", (t) => {
	const { run } = setup(t, { files: { "index.html": "home" } });
	const result = run();
	assert.equal(result.status, 0, result.stderr);
	const files = result.state.trees[result.state.commits[result.state.head].tree];
	assert.deepEqual(Object.keys(files).sort(), ["android.json", "index.html"]);
	assert.equal(writes(result.state).filter((call) => call.endsWith("git/blobs")).length, 1);
});

test("writes nothing when gh-pages and the site already offer the release", (t) => {
	const files = { "android.json": releaseJson(), "android.html": oldPage.replaceAll("0.5.5", VERSION).replace("180 MB", "0 MB") };
	const { run } = setup(t, { files });
	const result = run();
	assert.equal(result.status, 0, result.stderr);
	assert.deepEqual(writes(result.state), []);
	assert.match(result.stderr, /already offer Android app 0\.6\.0/);
});

test("requests a build when gh-pages offers the release but the site does not (a failed build)", (t) => {
	const files = { "android.json": releaseJson(), "android.html": oldPage.replaceAll("0.5.5", VERSION).replace("180 MB", "0 MB") };
	const { run } = setup(t, { files, live: { "android.json": '{"version": "0.5.5"}\n', "android.html": oldPage } });
	const result = run();
	assert.equal(result.status, 0, result.stderr);
	assert.deepEqual(writes(result.state), ["POST repos/o/r/pages/builds"]);
	assert.equal(result.state.live["android.json"], releaseJson());
});

test("--dry-run prints the JSON and says what would change, writing nothing", (t) => {
	const { run } = setup(t);
	const result = run("--dry-run");
	assert.equal(result.status, 0, result.stderr);
	assert.equal(result.stdout, releaseJson());
	assert.match(result.stderr, /would commit android\.html android\.json on gh-pages/);
	assert.deepEqual(writes(result.state), []);
});

test("refuses an APK whose checksum does not match the release's", (t) => {
	const { run } = setup(t, { state: { release: { apk: `pi-bolt-android-${VERSION}.apk`, bytes: APK.toString("base64"), sha256: "0".repeat(64), body: `SHA-256 digest: ${CERT}` } } });
	const result = run();
	assert.notEqual(result.status, 0);
	assert.match(result.stderr, /does not match the release's/);
	assert.deepEqual(writes(result.state), []);
});
