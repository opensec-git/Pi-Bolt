"use strict";
// The pi-bolt package's install script (postinstall): puts the `pi-bolt` command in place. package.json's "bin" is
// bin/pi-bolt.exe on every platform, a placeholder until this runs (npm links commands before it runs install scripts).
//
// On Windows it installs the native executable: the release build of this package's version, checked as install.ps1 checks
// it (the Ed25519 signature of the release's SHA256SUMS, the version they say, the build's SHA-256), and unpacked into bin\
// over the placeholder, so that npm's pi-bolt.cmd and pi-bolt.ps1 start pi-bolt.exe itself, with no Node.js in between.
// The build comes from the npm package pi-bolt-win32-<variant> of the same version (the release's .zip, byte for byte),
// or from the GitHub release if that fails; the checksums and their signature always come from the release.
// On Linux and macOS it puts the launcher (bin/pi-bolt) over the placeholder, and the first run downloads the build, as
// before.
//
// Plain Node.js (18 or later), no dependencies. On Windows it reads, as install.ps1 does:
//   PIBOLT_VARIANT        x64 (the default), x64-baseline (picked when the CPU has no AVX2 and the release has it) or x64-jit
//   PIBOLT_SOURCE         auto (npm, then GitHub; the default), npm or github
//   PIBOLT_NPM_REGISTRY   the npm registry or mirror to download the build from (default: https://registry.npmjs.org)
//   PIBOLT_DOWNLOAD_BASE  a mirror of the release to download everything from instead (its SHA256SUMS.sig too)
//   PIBOLT_ALLOW_UNSIGNED=1  install a release whose checksums have no signature (a build of one's own)
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { Readable, Transform } = require("node:stream");
const { pipeline } = require("node:stream/promises");
const zlib = require("node:zlib");

// The public key that releases are signed with (keys/release.pub in the repository, as in install.sh and install.ps1).
const RELEASE_KEY = "MCowBQYDK2VwAyEAoLboJqtKaoISPqffk03vHZr+1sRBG3uIRIWeKOew+aY=";
const REPO = "https://github.com/opensec-git/Pi-Bolt";
const VARIANTS = ["x64", "x64-baseline", "x64-jit"];

/** A reason not to install, said to the user as it is. */
class InstallError extends Error {}

function fail(message) {
	throw new InstallError(message);
}

/** The SHA-256 that SHA256SUMS (sha256sum's format) has for `name`, in lower case; undefined if it has no line for it. */
function checksumOf(sums, name) {
	for (const line of sums.split(/\r?\n/)) {
		const match = /^([0-9a-fA-F]{64}) [ *]?(.+)$/.exec(line);
		if (match && match[2] === name) return match[1].toLowerCase();
	}
	return undefined;
}

/**
 * Checks the release's SHA256SUMS before anything else in the release is looked at: its signature by the release key, and
 * that its first line says it is `version` (otherwise an older release, signed all the same, could be served as this one).
 * A release without a signature is refused, unless PIBOLT_ALLOW_UNSIGNED=1 (a build of one's own), as install.ps1 does; the caller
 * then says that only the checksum was verified. Returns whether the signature was verified, and the checksums as text.
 */
function checkSums({ sums, signature, version, key = RELEASE_KEY, unsignedAllowed = false }) {
	let signed = false;
	if (signature) {
		let publicKey;
		try {
			publicKey = crypto.createPublicKey({ key: Buffer.from(key, "base64"), format: "der", type: "spki" });
		} catch (error) {
			fail(`this Node.js cannot read the release key (${error.message}), so it cannot check the release's signature. Nothing was installed.`);
		}
		let verified = false;
		if (signature.length === 64) {
			try {
				verified = crypto.verify(null, sums, publicKey, signature);
			} catch (error) {
				fail(`this Node.js cannot check Ed25519 signatures (${error.message}). Nothing was installed: use Node.js 18 or later.`);
			}
		}
		if (!verified) fail("the release's signature does not verify: the download is not Pi-Bolt's. Nothing was installed.");
		signed = true;
	} else if (!unsignedAllowed) {
		fail("the release has no signature (SHA256SUMS.sig), and every Windows release is signed. Nothing was installed.");
	}
	const text = sums.toString("utf8");
	const match = /^# pi-bolt (\d+\.\d+\.\d+)\r?$/.exec(text.split("\n", 1)[0]);
	if (!match) fail("the release's SHA256SUMS does not say which version it is. Nothing was installed.");
	if (match[1] !== version) {
		fail(`the download is Pi-Bolt ${match[1]}, not ${version} as asked for (an older release served as a newer one?). Nothing was installed.`);
	}
	return { signed, text };
}

// --- Downloads ----------------------------------------------------------------------------------------------------------

const TIMEOUT = 30 * 60 * 1000;
// What is read, unpacked and written at a time (a build is hundreds of MB: in small pieces, writing it takes many times longer).
const CHUNK = 1 << 20;

async function request(fetchImpl, url) {
	try {
		const response = await fetchImpl(url, {
			headers: { "user-agent": "pi-bolt-installer" },
			redirect: "follow",
			signal: AbortSignal.timeout(TIMEOUT),
		});
		return response.ok && response.body ? response : undefined;
	} catch {
		return undefined;
	}
}

/** A small file (the checksums, their signature) as a Buffer; undefined if it could not be downloaded. */
async function fetchBytes(fetchImpl, url) {
	const response = await request(fetchImpl, url);
	if (!response) return undefined;
	try {
		return Buffer.from(await response.arrayBuffer());
	} catch {
		return undefined;
	}
}

/** Downloads `url` to `file`; false if it could not, or not all of it. */
async function download(fetchImpl, url, file) {
	const response = await request(fetchImpl, url);
	if (!response) return false;
	const total = response.headers.get("content-encoding") ? 0 : Number(response.headers.get("content-length")) || 0;
	try {
		await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(file, { highWaterMark: CHUNK }));
	} catch {
		return false;
	}
	return !total || fs.statSync(file).size === total;
}

async function sha256(file) {
	const hash = crypto.createHash("sha256");
	await pipeline(fs.createReadStream(file, { highWaterMark: CHUNK }), hash);
	return hash.digest("hex");
}

// --- Archives -----------------------------------------------------------------------------------------------------------

/**
 * Whether `name` (a path in an archive, with / between its parts) stays inside the folder it is unpacked into. A \ is refused
 * too (Windows takes it for a separator: the .zip reader turns them into / first, but this does not count on it).
 */
function isSafeEntryName(name) {
	if (!name || name.startsWith("/") || name.includes(":") || name.includes("\0") || name.includes("\\")) return false;
	const parts = name.replace(/\/$/, "").split("/");
	return parts.every((part) => part !== "" && part !== "." && part !== "..");
}

function tarString(block, start, length) {
	const end = block.indexOf(0, start);
	return block.toString("utf8", start, end >= start && end < start + length ? end : start + length);
}

// (A plain Error, not fail(): a package that is not a tar file this reads is a defect of the package, before anything is
// verified, and the release on GitHub is tried instead, as for a long name record below.)
function tarSize(block) {
	if (block[124] & 0x80) throw new Error("the npm package is not a tar file this installer reads (an entry of 8 GB or more)");
	const text = tarString(block, 124, 12).trim();
	if (!/^[0-7]*$/.test(text)) throw new Error("the npm package is not a valid tar file");
	return text ? Number.parseInt(text, 8) : 0;
}

/**
 * Copies the file `wanted` ("package/pi-bolt-win32-x64.zip") of an npm package (a .tgz) to `out`, as it reads it; false if
 * the package has no such file. (ustar, with the pax and GNU records for long names that npm's tar may write.)
 */
async function extractFromTgz(tgz, wanted, out) {
	let held = Buffer.alloc(0);
	let dataLeft = 0;
	let padLeft = 0;
	let sink;
	let record; // the data of a pax or GNU long name record, collected
	let recordType = "";
	let nextName;
	let found = false;
	const finishEntry = async () => {
		if (sink) {
			await new Promise((resolve, reject) => sink.end((error) => (error ? reject(error) : resolve())));
			sink = undefined;
			found = true;
		}
		if (record) {
			const data = Buffer.concat(record);
			record = undefined;
			if (recordType === "L") nextName = tarString(data, 0, data.length);
			else {
				const match = /(?:^|\n)\d+ path=([^\n]*)\n/.exec(data.toString("utf8"));
				if (match) nextName = match[1];
			}
		}
	};
	const source = fs.createReadStream(tgz, { highWaterMark: CHUNK }).pipe(zlib.createGunzip({ chunkSize: CHUNK }));
	try {
		for await (const chunk of source) {
			held = held.length ? Buffer.concat([held, chunk]) : chunk;
			let at = 0;
			while (at < held.length) {
				if (dataLeft > 0) {
					const n = Math.min(dataLeft, held.length - at);
					const piece = held.subarray(at, at + n);
					if (sink) {
						if (!sink.write(piece)) await new Promise((resolve) => sink.once("drain", resolve));
					} else if (record) record.push(Buffer.from(piece));
					dataLeft -= n;
					at += n;
					if (dataLeft === 0) await finishEntry();
					continue;
				}
				if (padLeft > 0) {
					const n = Math.min(padLeft, held.length - at);
					padLeft -= n;
					at += n;
					continue;
				}
				if (held.length - at < 512) break;
				const block = held.subarray(at, at + 512);
				at += 512;
				if (block.every((byte) => byte === 0)) continue;
				const type = String.fromCharCode(block[156] || 0x30);
				const size = tarSize(block);
				let name = tarString(block, 0, 100);
				const prefix = block.toString("latin1", 257, 263).startsWith("ustar") ? tarString(block, 345, 155) : "";
				if (prefix) name = `${prefix}/${name}`;
				if (nextName !== undefined && type !== "x" && type !== "L") {
					name = nextName;
					nextName = undefined;
				}
				if (type === "x" || type === "L") {
					// (A name: a few hundred bytes. Read before anything is verified, so a record is not let fill memory.)
					// (As any other defect of the package: not this way, then; the release on GitHub is the other.)
					if (size > 65536) throw new Error("a name record too long to be one");
					record = [];
					recordType = type;
				} else if ((type === "0" || type === "\0") && name === wanted && !found) {
					sink = fs.createWriteStream(out, { highWaterMark: CHUNK });
				}
				dataLeft = size;
				padLeft = (512 - (size % 512)) % 512;
				if (size === 0) await finishEntry();
			}
			held = held.subarray(at);
		}
	} catch (error) {
		if (sink) sink.destroy();
		if (error instanceof InstallError) throw error;
		return false;
	}
	if (sink || dataLeft > 0) {
		if (sink) sink.destroy();
		return false; // cut short
	}
	return found;
}

/**
 * Unpacks the entries of the .zip `zipFile` that are under `top`/ (the build's folder) into `dest`. A release's .zip is
 * made by .NET's ZipFile (scripts/package-release.ps1): stored or deflated entries, no encryption, no Zip64. An entry that
 * would land outside `dest` is refused, whatever folder it is in.
 */
async function extractZip(zipFile, top, dest) {
	const fd = fs.openSync(zipFile, "r");
	const entries = [];
	try {
		const size = fs.fstatSync(fd).size;
		const tailLength = Math.min(size, 22 + 0xffff);
		const tail = Buffer.alloc(tailLength);
		fs.readSync(fd, tail, 0, tailLength, size - tailLength);
		let end = -1;
		for (let i = tailLength - 22; i >= 0; i--) {
			if (tail.readUInt32LE(i) === 0x06054b50) {
				end = i;
				break;
			}
		}
		if (end < 0) fail("the build is not a .zip file");
		const count = tail.readUInt16LE(end + 10);
		const directorySize = tail.readUInt32LE(end + 12);
		const directoryOffset = tail.readUInt32LE(end + 16);
		if (count === 0xffff || directoryOffset === 0xffffffff || directorySize === 0xffffffff) {
			fail("the build is a Zip64 file, which this installer does not read");
		}
		if (directoryOffset + directorySize > size) fail("the build's .zip file is cut short");
		const directory = Buffer.alloc(directorySize);
		fs.readSync(fd, directory, 0, directorySize, directoryOffset);
		let p = 0;
		for (let i = 0; i < count; i++) {
			if (p + 46 > directory.length || directory.readUInt32LE(p) !== 0x02014b50) fail("the build's .zip file is damaged");
			const flags = directory.readUInt16LE(p + 8);
			const method = directory.readUInt16LE(p + 10);
			const compressedSize = directory.readUInt32LE(p + 20);
			const uncompressedSize = directory.readUInt32LE(p + 24);
			const nameLength = directory.readUInt16LE(p + 28);
			const extraLength = directory.readUInt16LE(p + 30);
			const commentLength = directory.readUInt16LE(p + 32);
			const localOffset = directory.readUInt32LE(p + 42);
			const name = directory.toString("utf8", p + 46, p + 46 + nameLength).replace(/\\/g, "/");
			p += 46 + nameLength + extraLength + commentLength;
			if (!isSafeEntryName(name)) fail(`the build's .zip file has an entry outside its folder (${JSON.stringify(name)})`);
			if (flags & 1) fail("the build's .zip file is encrypted");
			if (method !== 0 && method !== 8) fail(`the build's .zip file uses a compression method this installer does not read (${method})`);
			if (compressedSize === 0xffffffff || uncompressedSize === 0xffffffff || localOffset === 0xffffffff) {
				fail("the build is a Zip64 file, which this installer does not read");
			}
			entries.push({ name, method, compressedSize, uncompressedSize, localOffset });
		}
		for (const entry of entries) {
			if (!entry.name.startsWith(`${top}/`)) continue;
			const target = path.join(dest, ...entry.name.replace(/\/$/, "").split("/"));
			if (entry.name.endsWith("/")) {
				fs.mkdirSync(target, { recursive: true });
				continue;
			}
			const local = Buffer.alloc(30);
			fs.readSync(fd, local, 0, 30, entry.localOffset);
			if (local.readUInt32LE(0) !== 0x04034b50) fail("the build's .zip file is damaged");
			const start = entry.localOffset + 30 + local.readUInt16LE(26) + local.readUInt16LE(28);
			if (start + entry.compressedSize > size) fail("the build's .zip file is cut short");
			fs.mkdirSync(path.dirname(target), { recursive: true });
			let written = 0;
			const counter = new Transform({
				transform(chunk, _encoding, callback) {
					written += chunk.length;
					callback(null, chunk);
				},
			});
			const steps = [];
			if (entry.compressedSize > 0) {
				steps.push(fs.createReadStream(zipFile, { start, end: start + entry.compressedSize - 1, highWaterMark: CHUNK }));
			} else {
				steps.push(Readable.from([]));
			}
			if (entry.method === 8) steps.push(zlib.createInflateRaw({ chunkSize: CHUNK }));
			try {
				await pipeline(...steps, counter, fs.createWriteStream(target, { highWaterMark: CHUNK }));
			} catch {
				fail(`could not unpack ${entry.name} from the build's .zip file`);
			}
			if (written !== entry.uncompressedSize) fail(`could not unpack ${entry.name} from the build's .zip file`);
		}
	} finally {
		fs.closeSync(fd);
	}
}

// --- Windows ------------------------------------------------------------------------------------------------------------

function windowsProblems(env, release = os.release()) {
	const problems = [];
	const arch = env.PROCESSOR_ARCHITEW6432 || env.PROCESSOR_ARCHITECTURE || process.arch;
	if (!/^(AMD64|ARM64|x64|arm64)$/.test(arch)) problems.push(`Pi-Bolt runs on x86-64 (this is ${arch}).`);
	// ConPTY, and the console's virtual terminal sequences, which Pi's interface needs: Windows 10 1809 (build 17763).
	const build = Number(release.split(".")[2]) || 0;
	if (build < 17763) problems.push(`Pi-Bolt needs Windows 10 version 1809 or later (this is build ${build}).`);
	// Windows on ARM runs x64 code from Windows 11 on; Windows 10 on ARM emulates 32-bit x86 only.
	if (/^(ARM64|arm64)$/.test(arch) && build >= 17763 && build < 22000) {
		problems.push(`On ARM, Pi-Bolt needs Windows 11, whose x64 emulation runs it (this is Windows 10, build ${build}).`);
	}
	return problems;
}

/** Whether the CPU has AVX2, as install.ps1 finds out; true if that cannot be found out (the x64 build runs on any x86-64 CPU). */
function hasAvx2() {
	const powershell = path.join(process.env.SystemRoot || "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
	const script =
		"Add-Type -Namespace PiBoltInstaller -Name Cpu -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern bool IsProcessorFeaturePresent(uint feature);'; [PiBoltInstaller.Cpu]::IsProcessorFeaturePresent(40)";
	const result = spawnSync(powershell, ["-NoProfile", "-NonInteractive", "-Command", script], {
		encoding: "utf8",
		timeout: 60000,
		windowsHide: true,
	});
	// (Windows 10 may not fill that feature in: no is only known from Windows 11 on.)
	const build = Number(os.release().split(".")[2]) || 0;
	return result.status !== 0 || result.stdout.trim() !== "False" || build < 22000;
}

function runsVersion(exe) {
	const result = spawnSync(exe, ["--version"], { stdio: "ignore", timeout: 120000, windowsHide: true });
	return result.status === 0;
}

/** Removes what an earlier installation renamed out of the way (it was in use then). */
function removeOld(dir) {
	let entries;
	try {
		entries = fs.readdirSync(dir, { withFileTypes: true });
	} catch {
		return;
	}
	for (const entry of entries) {
		const file = path.join(dir, entry.name);
		if (entry.isDirectory()) removeOld(file);
		else if (entry.name.endsWith(".pibolt-old")) {
			try {
				fs.unlinkSync(file);
			} catch {}
		}
	}
}

/**
 * Moves the files of the tree `from` into `to`. A running Pi-Bolt keeps its executable open, and Windows lets that be renamed
 * but not replaced: each file that is there is renamed out of the way first, and deleted now if it can be (or by the next
 * installation). pi-bolt.exe goes last, so that the command is the new executable only once everything it needs is there.
 */
function moveFiles(from, to, stamp, last) {
	fs.mkdirSync(to, { recursive: true });
	const entries = fs.readdirSync(from, { withFileTypes: true });
	entries.sort((a, b) => (a.name === last) - (b.name === last));
	for (const entry of entries) {
		const source = path.join(from, entry.name);
		const target = path.join(to, entry.name);
		let existing;
		try {
			existing = fs.lstatSync(target);
		} catch {}
		if (entry.isDirectory()) {
			if (existing && !existing.isDirectory()) fs.renameSync(target, `${target}.${stamp}.pibolt-old`);
			moveFiles(source, target, stamp);
			continue;
		}
		const aside = `${target}.${stamp}.pibolt-old`;
		if (existing) fs.renameSync(target, aside);
		try {
			fs.renameSync(source, target);
		} catch (error) {
			// (The old one back, rather than none: the command keeps working.)
			if (existing && !fs.existsSync(target)) {
				try {
					fs.renameSync(aside, target);
				} catch {}
			}
			throw error;
		}
		if (existing) {
			try {
				fs.rmSync(aside, { recursive: true, force: true });
			} catch {}
		}
	}
}

/**
 * Installs the Windows build of `version` into `pkgDir`\bin. Options are for the tests: `fetch`, `key` (the release key),
 * `runsVersion` and `hasAvx2` stand for the real ones; `env` for process.env.
 */
async function installWindows({
	pkgDir,
	version,
	env = process.env,
	fetch: fetchImpl = globalThis.fetch,
	key = RELEASE_KEY,
	runsVersion: runs = runsVersion,
	hasAvx2: avx2 = hasAvx2,
	release = os.release(),
	log = (line) => console.error(line),
}) {
	const problems = windowsProblems(env, release);
	if (problems.length) fail(problems.join(" "));
	if (typeof fetchImpl !== "function") fail("this Node.js has no fetch: use Node.js 18 or later");
	const source = env.PIBOLT_SOURCE || "auto";
	if (!["auto", "npm", "github"].includes(source)) fail("PIBOLT_SOURCE must be auto, npm or github");
	let variant = env.PIBOLT_VARIANT || "";
	if (variant && !VARIANTS.includes(variant)) fail("PIBOLT_VARIANT must be x64, x64-baseline or x64-jit");
	const mirror = (env.PIBOLT_DOWNLOAD_BASE || "").replace(/\/+$/, "");
	if (source === "npm" && mirror) fail("PIBOLT_SOURCE=npm needs the release from GitHub (no PIBOLT_DOWNLOAD_BASE)");
	const base = mirror || `${REPO}/releases/download/bolt-v${version}`;
	const bin = path.join(pkgDir, "bin");
	const stage = fs.mkdtempSync(path.join(pkgDir, ".install-"));
	try {
		const sums = await fetchBytes(fetchImpl, `${base}/SHA256SUMS`);
		if (!sums) fail(`download failed: ${base}/SHA256SUMS`);
		const signature = await fetchBytes(fetchImpl, `${base}/SHA256SUMS.sig`);
		// (A mirror copies the signature too; only a build of one's own, PIBOLT_ALLOW_UNSIGNED=1, has none.)
		const { signed, text } = checkSums({ sums, signature, version, key, unsignedAllowed: env.PIBOLT_ALLOW_UNSIGNED === "1" });
		if (!variant) {
			variant = "x64";
			if (checksumOf(text, "pi-bolt-win32-x64-baseline.zip") && !avx2()) variant = "x64-baseline";
		}
		const name = `pi-bolt-win32-${variant}`;
		const archive = `${name}.zip`;
		const want = checksumOf(text, archive);
		if (!want) fail(`this release has no Windows build ${variant} (${archive} is not in its SHA256SUMS)`);
		const zip = path.join(stage, archive);
		let from = "";
		if (source !== "github" && !mirror) {
			// The npm package pi-bolt-win32-<variant> of the same version: a .tgz with the release's .zip in it.
			const registry = (env.PIBOLT_NPM_REGISTRY || "https://registry.npmjs.org").replace(/\/+$/, "");
			const tgz = path.join(stage, "npm.tgz");
			if (
				(await download(fetchImpl, `${registry}/${name}/-/${name}-${version}.tgz`, tgz)) &&
				(await extractFromTgz(tgz, `package/${archive}`, zip)) &&
				(await sha256(zip)) === want
			) {
				from = "npm";
			}
			fs.rmSync(tgz, { force: true });
			if (!from) {
				if (source === "npm") fail(`npm does not have Pi-Bolt ${version}, or its download does not match the release's checksum. Nothing was installed.`);
				log("pi-bolt: the download from npm failed; downloading from GitHub instead");
			}
		}
		if (!from) {
			if (!(await download(fetchImpl, `${base}/${archive}`, zip))) fail(`download failed: ${base}/${archive}`);
			if ((await sha256(zip)) !== want) fail("checksum mismatch: the download is corrupt or incomplete. Nothing was installed.");
			from = mirror || "GitHub";
		}
		const unpacked = path.join(stage, "unpacked");
		await extractZip(zip, name, unpacked);
		const tree = path.join(unpacked, name);
		const exe = path.join(tree, "pi-bolt.exe");
		if (!fs.existsSync(exe)) fail(`${archive} does not have ${name}\\pi-bolt.exe`);
		if (!runs(exe)) fail("the downloaded executable does not run on this system. Nothing was installed.");
		removeOld(bin);
		moveFiles(tree, bin, Date.now(), "pi-bolt.exe");
		const size = (fs.statSync(zip).size / 1048576).toFixed(1);
		log(`pi-bolt: installed Pi-Bolt ${version} (win32-${variant}, ${size} MB from ${from}${signed ? ", signature verified" : ""})`);
		if (!signed) log(`pi-bolt: note: ${from} has no signature for this release (SHA256SUMS.sig): only its checksum was verified`);
		return { variant, from, signed };
	} finally {
		fs.rmSync(stage, { recursive: true, force: true });
	}
}

// --- Linux and macOS ----------------------------------------------------------------------------------------------------

/** Puts the launcher (bin/pi-bolt, with its #!/bin/sh) over the placeholder, executable. */
function installPosix({ pkgDir }) {
	const launcher = fs.readFileSync(path.join(pkgDir, "bin", "pi-bolt"));
	if (!launcher.toString("latin1", 0, 10).startsWith("#!/bin/sh")) fail("bin/pi-bolt is not the launcher");
	const target = path.join(pkgDir, "bin", "pi-bolt.exe");
	const part = `${target}.${process.pid}.part`;
	fs.writeFileSync(part, launcher, { mode: 0o755 });
	fs.chmodSync(part, 0o755);
	fs.renameSync(part, target);
}

/** Whether `pkgDir` is npm/ in Pi-Bolt's repository (`npm install` or `npm link` there), whose placeholder stays as it is. */
function isSourceTree(pkgDir) {
	return path.basename(pkgDir) === "npm" && ["VERSION", "install.ps1", "install.sh"].every((file) => fs.existsSync(path.join(pkgDir, "..", file)));
}

/**
 * The proxy to download through, if one is set: in the environment, or in npm's configuration, which npm gives its install
 * scripts as npm_config_https_proxy and npm_config_proxy. (Node's fetch uses none of them by itself.)
 */
function proxyOf(env) {
	return env.HTTPS_PROXY || env.https_proxy || env.npm_config_https_proxy || env.npm_config_proxy || env.HTTP_PROXY || env.http_proxy || "";
}

async function main() {
	const pkgDir = __dirname;
	if (isSourceTree(pkgDir)) {
		console.error("pi-bolt: this is Pi-Bolt's repository, not an installed package: bin/pi-bolt.exe stays the placeholder");
		return;
	}
	if (process.platform !== "win32") {
		installPosix({ pkgDir });
		return;
	}
	// Behind a proxy: again, with Node's fetch told to use it (NODE_USE_ENV_PROXY, Node 22.21 and 24.5 or later; an older
	// Node ignores it, and a download that fails then says which proxy was not used).
	const proxy = proxyOf(process.env);
	if (proxy && process.env.NODE_USE_ENV_PROXY !== "1") {
		const env = { ...process.env, NODE_USE_ENV_PROXY: "1", HTTPS_PROXY: process.env.HTTPS_PROXY || process.env.https_proxy || proxy };
		env.HTTP_PROXY = process.env.HTTP_PROXY || process.env.http_proxy || env.HTTPS_PROXY;
		const result = spawnSync(process.execPath, [__filename], { stdio: "inherit", env });
		process.exitCode = result.status ?? 1;
		return;
	}
	const { version } = JSON.parse(fs.readFileSync(path.join(pkgDir, "package.json"), "utf8"));
	await installWindows({ pkgDir, version });
}

module.exports = {
	RELEASE_KEY,
	InstallError,
	checksumOf,
	checkSums,
	extractFromTgz,
	extractZip,
	installPosix,
	installWindows,
	isSafeEntryName,
	isSourceTree,
	proxyOf,
	windowsProblems,
};

if (require.main === module) {
	main().catch((error) => {
		const message = error instanceof InstallError ? error.message : error && error.stack ? error.stack : String(error);
		console.error(`pi-bolt: error: ${message}`);
		const proxy = proxyOf(process.env);
		if (proxy && /download failed/.test(message)) {
			console.error(`pi-bolt: downloads went through the proxy ${proxy.replace(/\/\/[^@/]*@/, "//")}; a Node.js older than 22.21 or 24.5 cannot use one (NODE_USE_ENV_PROXY)`);
		}
		if (process.platform === "win32") {
			console.error('pi-bolt: Pi-Bolt was not installed. Try again, or install it with: powershell -c "[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072; irm https://pi-bolt.opensec.in/install.ps1 | iex"');
		}
		process.exitCode = 1;
	});
}
