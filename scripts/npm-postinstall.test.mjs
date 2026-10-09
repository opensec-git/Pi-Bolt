// Tests of the pi-bolt npm package's install script (npm/install.cjs), with a fake release signed with a key made here:
// no network, no real release key.
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import zlib from "node:zlib";

const require = createRequire(import.meta.url);
const npmDir = fileURLToPath(new URL("../npm/", import.meta.url));
const root = fileURLToPath(new URL("../", import.meta.url));
const install = require(join(npmDir, "install.cjs"));
const VERSION = JSON.parse(readFileSync(join(npmDir, "package.json"), "utf8")).version;
const PLACEHOLDER = readFileSync(join(npmDir, "bin", "pi-bolt.exe"));
const WINDOWS_10_1809 = "10.0.17763";

// --- Archives, made as the release makes them -------------------------------------------------------------------------

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
	let c = n;
	for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
	return c >>> 0;
});

function crc32(data) {
	let c = 0xffffffff;
	for (const byte of data) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
	return (c ^ 0xffffffff) >>> 0;
}

/** A .zip of `entries` ({ name, data }): deflated, as .NET's ZipFile makes them; a name ending in / is a folder. */
function makeZip(entries) {
	const locals = [];
	const centrals = [];
	let offset = 0;
	for (const { name, data = Buffer.alloc(0), method = name.endsWith("/") ? 0 : 8 } of entries) {
		const nameBytes = Buffer.from(name, "utf8");
		const body = method === 8 ? zlib.deflateRawSync(data) : data;
		const local = Buffer.alloc(30);
		local.writeUInt32LE(0x04034b50, 0);
		local.writeUInt16LE(20, 4);
		local.writeUInt16LE(0x800, 6);
		local.writeUInt16LE(method, 8);
		local.writeUInt32LE(crc32(data), 14);
		local.writeUInt32LE(body.length, 18);
		local.writeUInt32LE(data.length, 22);
		local.writeUInt16LE(nameBytes.length, 26);
		const central = Buffer.alloc(46);
		central.writeUInt32LE(0x02014b50, 0);
		central.writeUInt16LE(20, 4);
		central.writeUInt16LE(20, 6);
		central.writeUInt16LE(0x800, 8);
		central.writeUInt16LE(method, 10);
		central.writeUInt32LE(crc32(data), 16);
		central.writeUInt32LE(body.length, 20);
		central.writeUInt32LE(data.length, 24);
		central.writeUInt16LE(nameBytes.length, 28);
		central.writeUInt32LE(offset, 42);
		locals.push(local, nameBytes, body);
		centrals.push(central, nameBytes);
		offset += local.length + nameBytes.length + body.length;
	}
	const directory = Buffer.concat(centrals);
	const end = Buffer.alloc(22);
	end.writeUInt32LE(0x06054b50, 0);
	end.writeUInt16LE(entries.length, 8);
	end.writeUInt16LE(entries.length, 10);
	end.writeUInt32LE(directory.length, 12);
	end.writeUInt32LE(offset, 16);
	return Buffer.concat([...locals, directory, end]);
}

function tarHeader(name, size, type = "0") {
	const header = Buffer.alloc(512);
	header.write(name, 0, 100, "utf8");
	header.write("0000644\0", 100);
	header.write("0000000\0", 108);
	header.write("0000000\0", 116);
	header.write(`${size.toString(8).padStart(11, "0")}\0`, 124);
	header.write("00000000000\0", 136);
	header.write("        ", 148);
	header.write(type, 156);
	header.write("ustar\0", 257);
	header.write("00", 263);
	let sum = 0;
	for (const byte of header) sum += byte;
	header.write(`${sum.toString(8).padStart(6, "0")}\0 `, 148);
	return header;
}

/** An npm package (.tgz) of `files` ({ name, data }); `paxName` gives the first file its name with a pax record. */
function makeTgz(files, { paxName } = {}) {
	const parts = [];
	const pad = (n) => Buffer.alloc((512 - (n % 512)) % 512);
	for (const [i, { name, data }] of files.entries()) {
		if (i === 0 && paxName) {
			// "LENGTH path=NAME\n", LENGTH counting itself.
			const body = ` path=${paxName}\n`;
			let length = Buffer.byteLength(body);
			while (String(length).length + Buffer.byteLength(body) !== length) length = String(length).length + Buffer.byteLength(body);
			const bytes = Buffer.from(`${length}${body}`);
			parts.push(tarHeader("PaxHeader/x", bytes.length, "x"), bytes, pad(bytes.length));
		}
		parts.push(tarHeader(name, data.length), data, pad(data.length));
	}
	parts.push(Buffer.alloc(1024));
	return zlib.gzipSync(Buffer.concat(parts));
}

// --- A release ----------------------------------------------------------------------------------------------------------

function newKey() {
	const { publicKey, privateKey } = crypto.generateKeyPairSync("ed25519");
	return { privateKey, key: publicKey.export({ type: "spki", format: "der" }).toString("base64") };
}

const sha256 = (data) => crypto.createHash("sha256").update(data).digest("hex");
const EXE = Buffer.from("MZ the new pi-bolt.exe");

function buildZip(variant = "x64", extra = []) {
	const top = `pi-bolt-win32-${variant}`;
	return makeZip([
		{ name: `${top}/pi-bolt.exe`, data: EXE },
		{ name: `${top}/pi-bolt.txt`, data: Buffer.from(`Pi-Bolt ${VERSION} (Pi 1.0.3), win32-${variant}, JIT off\n`) },
		{ name: `${top}/package.json`, data: Buffer.from('{"name":"pi"}\n') },
		{ name: `${top}/theme/` },
		{ name: `${top}/theme/dark.json`, data: Buffer.from("{}\n") },
		{ name: `${top}/native/win32/prebuilds/win32-x64/win32-platform.node`, data: crypto.randomBytes(5000) },
		...extra,
	]);
}

/**
 * The files of a release (as served from GitHub) and of its npm packages: `signer` signs the checksums (none: unsigned),
 * `sumsVersion` is the version the checksums say.
 */
function makeRelease({ signer, sumsVersion = VERSION, zips = { x64: buildZip() }, firstLine } = {}) {
	const lines = [firstLine ?? `# pi-bolt ${sumsVersion}`];
	const files = {};
	for (const [variant, zip] of Object.entries(zips)) {
		files[`pi-bolt-win32-${variant}.zip`] = zip;
		lines.push(`${sha256(zip)}  pi-bolt-win32-${variant}.zip`);
	}
	files.SHA256SUMS = Buffer.from(`${lines.join("\n")}\n`);
	if (signer) files["SHA256SUMS.sig"] = crypto.sign(null, files.SHA256SUMS, signer.privateKey);
	return files;
}

const GITHUB = `https://github.com/opensec-git/Pi-Bolt/releases/download/bolt-v${VERSION}`;
const REGISTRY = "https://registry.npmjs.org";
const npmUrl = (variant = "x64") => `${REGISTRY}/pi-bolt-win32-${variant}/-/pi-bolt-win32-${variant}-${VERSION}.tgz`;

/** A fetch that serves `routes` (URL -> bytes) and records what was asked for. */
function fakeFetch(routes) {
	const asked = [];
	const fetch = async (url) => {
		asked.push(url);
		const body = routes[url];
		return body ? new Response(body) : new Response(null, { status: 404 });
	};
	return { fetch, asked };
}

function githubRoutes(release, base = GITHUB) {
	return Object.fromEntries(Object.entries(release).map(([name, data]) => [`${base}/${name}`, data]));
}

function npmPackage(zip, variant = "x64") {
	return makeTgz([
		{ name: "package/package.json", data: Buffer.from(`{"name":"pi-bolt-win32-${variant}","version":"${VERSION}"}`) },
		{ name: `package/pi-bolt-win32-${variant}.zip`, data: zip },
	]);
}

/** A package folder as npm leaves it before the install script runs. */
function makePackage(t) {
	const dir = mkdtempSync(join(tmpdir(), "pi-bolt-npm-test-"));
	t.after(() => rmSync(dir, { recursive: true, force: true }));
	mkdirSync(join(dir, "bin"));
	writeFileSync(join(dir, "bin", "pi-bolt.exe"), PLACEHOLDER);
	writeFileSync(join(dir, "bin", "pi-bolt"), readFileSync(join(npmDir, "bin", "pi-bolt")));
	writeFileSync(join(dir, "package.json"), readFileSync(join(npmDir, "package.json")));
	return dir;
}

function run(pkgDir, { fetch, key, env = {}, runsVersion = () => true, hasAvx2 = () => true }) {
	const log = [];
	const promise = install.installWindows({
		pkgDir,
		version: VERSION,
		env,
		fetch,
		key,
		runsVersion,
		hasAvx2,
		release: WINDOWS_10_1809,
		log: (line) => log.push(line),
	});
	return { promise, log };
}

function assertPlaceholder(pkgDir) {
	assert.deepEqual(readFileSync(join(pkgDir, "bin", "pi-bolt.exe")), PLACEHOLDER);
	assert.deepEqual(readdirSync(pkgDir).sort(), ["bin", "package.json"], "nothing is left behind");
}

// --- The package --------------------------------------------------------------------------------------------------------

test("the package: bin/pi-bolt.exe on every platform, a placeholder with no #! line, and the install script", () => {
	const pkg = JSON.parse(readFileSync(join(npmDir, "package.json"), "utf8"));
	assert.deepEqual(pkg.bin, { "pi-bolt": "bin/pi-bolt.exe" });
	assert.equal(pkg.scripts.postinstall, "node install.cjs");
	for (const file of ["bin/pi-bolt", "bin/pi-bolt.exe", "install.cjs", "install.sh"]) assert.ok(pkg.files.includes(file), file);
	assert.ok(pkg.os.includes("win32"));
	assert.notEqual(PLACEHOLDER.toString("latin1", 0, 2), "#!", "a #! line would make npm's Windows commands start it with sh");
	assert.ok(!PLACEHOLDER.includes("\r"), "LF line ends");
	assert.ok(readFileSync(join(npmDir, "bin", "pi-bolt"), "latin1").startsWith("#!/bin/sh\n"));
	assert.equal(pkg.dependencies, undefined);
});

test("the release key is keys/release.pub, the one install.sh and install.ps1 have", () => {
	const pem = readFileSync(join(root, "keys", "release.pub"), "utf8");
	assert.equal(install.RELEASE_KEY, pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s/g, ""));
	assert.ok(readFileSync(join(root, "install.ps1"), "utf8").includes(`$PiBoltReleaseKey = '${install.RELEASE_KEY}'`));
	assert.ok(readFileSync(join(root, "install.sh"), "utf8").includes(install.RELEASE_KEY));
});

test("in Pi-Bolt's repository the install script leaves the placeholder alone", () => {
	assert.equal(install.isSourceTree(npmDir.replace(/[\\/]$/, "")), true);
	assert.equal(install.isSourceTree(join(tmpdir(), "node_modules", "pi-bolt")), false);
});

// --- Windows ------------------------------------------------------------------------------------------------------------

test("Windows: installs the build from npm, signature and checksum verified, over the placeholder", async (t) => {
	const signer = newKey();
	const release = makeRelease({ signer });
	const { fetch, asked } = fakeFetch({ ...githubRoutes(release), [npmUrl()]: npmPackage(release["pi-bolt-win32-x64.zip"]) });
	const pkgDir = makePackage(t);
	let ran;
	const { promise, log } = run(pkgDir, { fetch, key: signer.key, runsVersion: (exe) => ((ran = readFileSync(exe)), true) });
	assert.deepEqual(await promise, { variant: "x64", from: "npm", signed: true });
	assert.deepEqual(ran, EXE, "the new executable ran (--version) before it was installed");
	assert.deepEqual(readFileSync(join(pkgDir, "bin", "pi-bolt.exe")), EXE);
	assert.equal(readFileSync(join(pkgDir, "bin", "theme", "dark.json"), "utf8"), "{}\n");
	assert.ok(existsSync(join(pkgDir, "bin", "native", "win32", "prebuilds", "win32-x64", "win32-platform.node")));
	assert.ok(existsSync(join(pkgDir, "bin", "pi-bolt.txt")));
	assert.deepEqual(readdirSync(pkgDir).sort(), ["bin", "package.json"], "the staging folder is gone");
	assert.ok(!asked.includes(`${GITHUB}/pi-bolt-win32-x64.zip`), "nothing from GitHub but the checksums and their signature");
	assert.match(log.join("\n"), /installed Pi-Bolt .* from npm, signature verified/);
});

test("Windows: a build from npm that does not match the checksum is not used: GitHub's is, checked the same way", async (t) => {
	const signer = newKey();
	const release = makeRelease({ signer });
	const { fetch } = fakeFetch({ ...githubRoutes(release), [npmUrl()]: npmPackage(buildZip("x64", [{ name: "pi-bolt-win32-x64/evil.dll", data: Buffer.from("x") }])) });
	const pkgDir = makePackage(t);
	const { promise, log } = run(pkgDir, { fetch, key: signer.key });
	assert.deepEqual(await promise, { variant: "x64", from: "GitHub", signed: true });
	assert.ok(!existsSync(join(pkgDir, "bin", "evil.dll")));
	assert.match(log.join("\n"), /the download from npm failed; downloading from GitHub instead/);
});

test("Windows: PIBOLT_SOURCE=npm refuses when npm does not have the build", async (t) => {
	const signer = newKey();
	const { fetch } = fakeFetch(githubRoutes(makeRelease({ signer })));
	const pkgDir = makePackage(t);
	await assert.rejects(run(pkgDir, { fetch, key: signer.key, env: { PIBOLT_SOURCE: "npm" } }).promise, /npm does not have Pi-Bolt/);
	assertPlaceholder(pkgDir);
});

test("Windows: PIBOLT_SOURCE=github downloads from GitHub only", async (t) => {
	const signer = newKey();
	const release = makeRelease({ signer });
	const { fetch, asked } = fakeFetch({ ...githubRoutes(release), [npmUrl()]: npmPackage(release["pi-bolt-win32-x64.zip"]) });
	const pkgDir = makePackage(t);
	assert.equal((await run(pkgDir, { fetch, key: signer.key, env: { PIBOLT_SOURCE: "github" } }).promise).from, "GitHub");
	assert.ok(!asked.includes(npmUrl()));
});

test("Windows: a build from GitHub that does not match the checksum is refused", async (t) => {
	const signer = newKey();
	const release = makeRelease({ signer });
	release["pi-bolt-win32-x64.zip"] = buildZip("x64", [{ name: "pi-bolt-win32-x64/extra", data: Buffer.from("x") }]);
	const { fetch } = fakeFetch(githubRoutes(release));
	const pkgDir = makePackage(t);
	await assert.rejects(run(pkgDir, { fetch, key: signer.key }).promise, /checksum mismatch/);
	assertPlaceholder(pkgDir);
});

test("Windows: checksums signed with another key are refused before anything else is downloaded", async (t) => {
	const release = makeRelease({ signer: newKey() });
	const { fetch, asked } = fakeFetch({ ...githubRoutes(release), [npmUrl()]: npmPackage(release["pi-bolt-win32-x64.zip"]) });
	const pkgDir = makePackage(t);
	await assert.rejects(run(pkgDir, { fetch, key: newKey().key }).promise, /signature does not verify: the download is not Pi-Bolt's/);
	assert.deepEqual(asked, [`${GITHUB}/SHA256SUMS`, `${GITHUB}/SHA256SUMS.sig`]);
	assertPlaceholder(pkgDir);
});

test("Windows: checksums changed after they were signed are refused", async (t) => {
	const signer = newKey();
	const release = makeRelease({ signer });
	release.SHA256SUMS = Buffer.concat([release.SHA256SUMS, Buffer.from(`${"0".repeat(64)}  other.zip\n`)]);
	const { fetch } = fakeFetch(githubRoutes(release));
	const pkgDir = makePackage(t);
	await assert.rejects(run(pkgDir, { fetch, key: signer.key }).promise, /signature does not verify/);
	assertPlaceholder(pkgDir);
});

test("Windows: the real release key does not accept a fake release", async (t) => {
	const release = makeRelease({ signer: newKey() });
	const { fetch } = fakeFetch(githubRoutes(release));
	const pkgDir = makePackage(t);
	await assert.rejects(run(pkgDir, { fetch, key: install.RELEASE_KEY }).promise, /signature does not verify/);
	assertPlaceholder(pkgDir);
});

test("Windows: a release without a signature is refused from GitHub", async (t) => {
	const signer = newKey();
	const { fetch } = fakeFetch(githubRoutes(makeRelease()));
	const pkgDir = makePackage(t);
	await assert.rejects(run(pkgDir, { fetch, key: signer.key }).promise, /has no signature \(SHA256SUMS\.sig\)/);
	assertPlaceholder(pkgDir);
});

test("Windows: a mirror (PIBOLT_DOWNLOAD_BASE) without a signature is refused", async (t) => {
	const release = makeRelease();
	const { fetch } = fakeFetch(githubRoutes(release, "http://mirror.test/pi-bolt"));
	const pkgDir = makePackage(t);
	await assert.rejects(
		run(pkgDir, { fetch, key: install.RELEASE_KEY, env: { PIBOLT_DOWNLOAD_BASE: "http://mirror.test/pi-bolt/" } }).promise,
		/has no signature/,
	);
	assertPlaceholder(pkgDir);
});

test("Windows: an unsigned release only with PIBOLT_ALLOW_UNSIGNED=1, and only the checksum is verified, which is said", async (t) => {
	const mirror = "http://mirror.test/pi-bolt/";
	const release = makeRelease();
	const { fetch, asked } = fakeFetch(githubRoutes(release, "http://mirror.test/pi-bolt"));
	const pkgDir = makePackage(t);
	const env = { PIBOLT_DOWNLOAD_BASE: mirror, PIBOLT_ALLOW_UNSIGNED: "1" };
	const { promise, log } = run(pkgDir, { fetch, key: install.RELEASE_KEY, env });
	assert.deepEqual(await promise, { variant: "x64", from: "http://mirror.test/pi-bolt", signed: false });
	assert.ok(asked.every((url) => url.startsWith("http://mirror.test/pi-bolt/")));
	assert.match(log.join("\n"), /note: http:\/\/mirror\.test\/pi-bolt has no signature for this release \(SHA256SUMS\.sig\): only its checksum was verified/);
});

test("Windows: a mirror's signature is checked all the same", async (t) => {
	const release = makeRelease({ signer: newKey() });
	const { fetch } = fakeFetch(githubRoutes(release, "http://mirror.test"));
	const pkgDir = makePackage(t);
	await assert.rejects(run(pkgDir, { fetch, key: install.RELEASE_KEY, env: { PIBOLT_DOWNLOAD_BASE: "http://mirror.test" } }).promise, /signature does not verify/);
	assertPlaceholder(pkgDir);
});

test("Windows: checksums of another version, or that do not say their version, are refused", async (t) => {
	const signer = newKey();
	for (const [release, error] of [
		[makeRelease({ signer, sumsVersion: "0.1.0" }), /the download is Pi-Bolt 0\.1\.0, not .* \(an older release served as a newer one\?\)/],
		[makeRelease({ signer, firstLine: "# checksums" }), /does not say which version it is/],
	]) {
		const { fetch } = fakeFetch(githubRoutes(release));
		const pkgDir = makePackage(t);
		await assert.rejects(run(pkgDir, { fetch, key: signer.key }).promise, error);
		assertPlaceholder(pkgDir);
	}
});

test("Windows: a release without a Windows build, or without the variant asked for, is refused", async (t) => {
	const signer = newKey();
	const { fetch } = fakeFetch(githubRoutes(makeRelease({ signer, zips: {} })));
	await assert.rejects(run(makePackage(t), { fetch, key: signer.key }).promise, /has no Windows build x64 \(pi-bolt-win32-x64\.zip is not in its SHA256SUMS\)/);
	const other = fakeFetch(githubRoutes(makeRelease({ signer })));
	await assert.rejects(run(makePackage(t), { fetch: other.fetch, key: signer.key, env: { PIBOLT_VARIANT: "x64-jit" } }).promise, /pi-bolt-win32-x64-jit\.zip is not in its SHA256SUMS/);
	await assert.rejects(run(makePackage(t), { fetch: other.fetch, key: signer.key, env: { PIBOLT_VARIANT: "arm64" } }).promise, /PIBOLT_VARIANT must be x64, x64-baseline or x64-jit/);
	await assert.rejects(run(makePackage(t), { fetch: other.fetch, key: signer.key, env: { PIBOLT_SOURCE: "ftp" } }).promise, /PIBOLT_SOURCE must be auto, npm or github/);
});

test("Windows: the baseline build on a CPU without AVX2, when the release has one", async (t) => {
	const signer = newKey();
	const release = makeRelease({ signer, zips: { x64: buildZip("x64"), "x64-baseline": buildZip("x64-baseline") } });
	const { fetch } = fakeFetch(githubRoutes(release));
	assert.equal((await run(makePackage(t), { fetch, key: signer.key, hasAvx2: () => false }).promise).variant, "x64-baseline");
	assert.equal((await run(makePackage(t), { fetch, key: signer.key, hasAvx2: () => true }).promise).variant, "x64");
	const onlyX64 = fakeFetch(githubRoutes(makeRelease({ signer })));
	let asked = false;
	const result = await run(makePackage(t), { fetch: onlyX64.fetch, key: signer.key, hasAvx2: () => ((asked = true), false) }).promise;
	assert.equal(result.variant, "x64");
	assert.equal(asked, false, "the CPU is only looked at when the release has a baseline build");
});

test("Windows: an executable that does not run is not installed", async (t) => {
	const signer = newKey();
	const { fetch } = fakeFetch(githubRoutes(makeRelease({ signer })));
	const pkgDir = makePackage(t);
	await assert.rejects(run(pkgDir, { fetch, key: signer.key, runsVersion: () => false }).promise, /does not run on this system/);
	assertPlaceholder(pkgDir);
});

test("Windows: a .zip with an entry outside its folder is refused, signed or not", async (t) => {
	const signer = newKey();
	for (const name of ["pi-bolt-win32-x64/../../evil.exe", "../evil.exe", "/evil.exe", "C:/evil.exe", "pi-bolt-win32-x64\\..\\evil.exe"]) {
		const release = makeRelease({ signer, zips: { x64: buildZip("x64", [{ name, data: Buffer.from("x") }]) } });
		const { fetch } = fakeFetch(githubRoutes(release));
		const pkgDir = makePackage(t);
		await assert.rejects(run(pkgDir, { fetch, key: signer.key }).promise, /an entry outside its folder/, name);
		assertPlaceholder(pkgDir);
	}
});

test("Windows: an older Windows is refused before anything is downloaded", async (t) => {
	const { fetch, asked } = fakeFetch({});
	const pkgDir = makePackage(t);
	const promise = install.installWindows({ pkgDir, version: VERSION, env: {}, fetch, release: "10.0.17134", log: () => {} });
	await assert.rejects(promise, /needs Windows 10 version 1809 or later \(this is build 17134\)/);
	assert.deepEqual(asked, []);
	assert.match(install.windowsProblems({ PROCESSOR_ARCHITECTURE: "x86" }, WINDOWS_10_1809).join(), /runs on x86-64 \(this is x86\)/);
	assert.deepEqual(install.windowsProblems({ PROCESSOR_ARCHITECTURE: "x86", PROCESSOR_ARCHITEW6432: "AMD64" }, WINDOWS_10_1809), []);
	assert.deepEqual(install.windowsProblems({ PROCESSOR_ARCHITECTURE: "ARM64" }, "10.0.26100"), []);
	// Windows 10 on ARM has no x64 emulation.
	assert.match(install.windowsProblems({ PROCESSOR_ARCHITECTURE: "ARM64" }, "10.0.19045").join(), /On ARM, Pi-Bolt needs Windows 11/);
});

test("Windows: installing again replaces the files that are there", async (t) => {
	const signer = newKey();
	const { fetch } = fakeFetch(githubRoutes(makeRelease({ signer })));
	const pkgDir = makePackage(t);
	await run(pkgDir, { fetch, key: signer.key }).promise;
	writeFileSync(join(pkgDir, "bin", "theme", "dark.json"), "changed");
	await run(pkgDir, { fetch, key: signer.key }).promise;
	assert.equal(readFileSync(join(pkgDir, "bin", "theme", "dark.json"), "utf8"), "{}\n");
	assert.deepEqual(readFileSync(join(pkgDir, "bin", "pi-bolt.exe")), EXE);
	const leftovers = readdirSync(join(pkgDir, "bin"), { recursive: true }).filter((name) => String(name).endsWith(".pibolt-old"));
	assert.deepEqual(leftovers, []);
});

test("the npm package's tar: the file asked for, also under a pax name; false when it is not there", async (t) => {
	const dir = mkdtempSync(join(tmpdir(), "pi-bolt-npm-test-"));
	t.after(() => rmSync(dir, { recursive: true, force: true }));
	const data = crypto.randomBytes(700000);
	const tgz = join(dir, "a.tgz");
	writeFileSync(tgz, makeTgz([{ name: "package/a.bin", data: Buffer.from("not this") }, { name: "package/b.zip", data }]));
	assert.equal(await install.extractFromTgz(tgz, "package/b.zip", join(dir, "out")), true);
	assert.deepEqual(readFileSync(join(dir, "out")), data);
	assert.equal(await install.extractFromTgz(tgz, "package/c.zip", join(dir, "none")), false);
	writeFileSync(tgz, makeTgz([{ name: "package/short", data }], { paxName: `package/${"long-".repeat(30)}b.zip` }));
	assert.equal(await install.extractFromTgz(tgz, `package/${"long-".repeat(30)}b.zip`, join(dir, "pax")), true);
	assert.deepEqual(readFileSync(join(dir, "pax")), data);
	writeFileSync(tgz, "not gzip");
	assert.equal(await install.extractFromTgz(tgz, "package/b.zip", join(dir, "bad")), false);
});

test("the checksums' lines, as sha256sum writes them", () => {
	const sums = `# pi-bolt ${VERSION}\n${"a".repeat(64)}  pi-bolt-win32-x64.zip\n${"B".repeat(64)} *other.zip\n`;
	assert.equal(install.checksumOf(sums, "pi-bolt-win32-x64.zip"), "a".repeat(64));
	assert.equal(install.checksumOf(sums, "other.zip"), "b".repeat(64));
	assert.equal(install.checksumOf(sums, "win32-x64.zip"), undefined);
	assert.equal(install.checksumOf(sums, "pi-bolt-win32-x64"), undefined);
});

// --- Linux and macOS ----------------------------------------------------------------------------------------------------

test("Linux and macOS: the install script puts the launcher, with its #!/bin/sh, over the placeholder", (t) => {
	const pkgDir = makePackage(t);
	install.installPosix({ pkgDir });
	const launcher = readFileSync(join(npmDir, "bin", "pi-bolt"));
	assert.deepEqual(readFileSync(join(pkgDir, "bin", "pi-bolt.exe")), launcher);
	if (process.platform !== "win32") assert.equal(statSync(join(pkgDir, "bin", "pi-bolt.exe")).mode & 0o777, 0o755);
	install.installPosix({ pkgDir });
	assert.deepEqual(readFileSync(join(pkgDir, "bin", "pi-bolt.exe")), launcher);
	assert.deepEqual(readdirSync(join(pkgDir, "bin")).sort(), ["pi-bolt", "pi-bolt.exe"]);
});

/** sh, if there is one (Git's on Windows). */
function findSh() {
	const result = spawnSync("sh", ["-c", "echo ok"], { encoding: "utf8" });
	return result.status === 0 && result.stdout.trim() === "ok";
}

function fakeUname(dir, system) {
	const bin = join(dir, "fake-bin");
	mkdirSync(bin, { recursive: true });
	writeFileSync(join(bin, "uname"), `#!/bin/sh\necho ${system}\n`);
	chmodSync(join(bin, "uname"), 0o755);
	return bin;
}

test("the placeholder, run without the install script: on Linux it starts the launcher; elsewhere it says what to run", { skip: !findSh() && "no sh" }, (t) => {
	const pkgDir = makePackage(t);
	const separator = process.platform === "win32" ? ";" : ":";
	// Elsewhere (Git Bash on Windows): how to run the install script, and a failure.
	const windows = spawnSync("sh", [join(pkgDir, "bin", "pi-bolt.exe"), "--version"], {
		encoding: "utf8",
		env: { ...process.env, PATH: `${fakeUname(pkgDir, "MINGW64_NT-10.0-26200")}${separator}${process.env.PATH}` },
	});
	assert.equal(windows.status, 1);
	assert.match(windows.stderr, /the package's install script did not run/);
	assert.match(windows.stderr, /node ".*\/install\.cjs"/);
	// Linux: the launcher, which works without the install script (here, with PIBOLT_HOME set to where a "build" is).
	const home = join(pkgDir, "home");
	const dir = join(home, "npm", VERSION, "pi-bolt-linux-x64");
	mkdirSync(dir, { recursive: true });
	writeFileSync(join(dir, "pi"), '#!/bin/sh\necho "the build: $*"\n');
	chmodSync(join(dir, "pi"), 0o755);
	const linux = spawnSync("sh", [join(pkgDir, "bin", "pi-bolt.exe"), "--version"], {
		encoding: "utf8",
		env: { ...process.env, PIBOLT_HOME: home, PIBOLT_VARIANT: "x64", PATH: `${fakeUname(pkgDir, "Linux")}${separator}${process.env.PATH}` },
	});
	assert.equal(linux.stdout, "the build: --version\n", linux.stderr);
	assert.equal(linux.status, 0);
});

test("an archive entry's name: inside its folder, with / between its parts and no \\, :, .. or empty part", () => {
	for (const name of ["pi-bolt-win32-x64/pi-bolt.exe", "pi-bolt-win32-x64/", "a/b/c.txt"]) assert.equal(install.isSafeEntryName(name), true, name);
	for (const name of ["", "/x", "C:/x", "a/../x", "a//x", "./x", "a\\x", "pi-bolt-win32-x64/..\\..\\x", "a\0x"]) {
		assert.equal(install.isSafeEntryName(name), false, JSON.stringify(name));
	}
});

test("the proxy to download through: the environment's, then npm's configuration", () => {
	assert.equal(install.proxyOf({}), "");
	assert.equal(install.proxyOf({ npm_config_proxy: "http://p:1" }), "http://p:1");
	assert.equal(install.proxyOf({ npm_config_proxy: "http://p:1", npm_config_https_proxy: "http://s:2" }), "http://s:2");
	assert.equal(install.proxyOf({ npm_config_https_proxy: "http://s:2", HTTPS_PROXY: "http://e:3" }), "http://e:3");
});
