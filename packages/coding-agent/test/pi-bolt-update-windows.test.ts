// The Windows update without PowerShell (#82): what it checks and how it puts the new build in place.
import { generateKeyPairSync, sign } from "node:crypto";
import {
	chmodSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { gzipSync } from "node:zlib";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import {
	installFiles,
	isInProfile,
	keepPrevious,
	sha256,
	sumsEntry,
	sumsSignatureValid,
	sumsVersion,
	tgzEntry,
} from "../src/pi-bolt-update-windows.ts";

let dir: string;

beforeEach(() => {
	dir = mkdtempSync(join(tmpdir(), "pibolt-update-windows-"));
});

afterEach(() => {
	rmSync(dir, { recursive: true, force: true });
});

/** A ustar archive of `files`, gzipped, as npm publishes a package. */
function tgz(files: Record<string, string | Buffer>): Buffer {
	const blocks: Buffer[] = [];
	for (const [name, content] of Object.entries(files)) {
		const body = Buffer.isBuffer(content) ? content : Buffer.from(content);
		const header = Buffer.alloc(512);
		header.write(name, 0, 100, "utf8");
		header.write("0000644\0", 100);
		header.write("0000000\0", 108);
		header.write("0000000\0", 116);
		header.write(`${body.length.toString(8).padStart(11, "0")}\0`, 124);
		header.write("00000000000\0", 136);
		header.write("0", 156);
		header.write("ustar\0", 257);
		header.write("00", 263);
		header.fill(" ", 148, 156);
		let sum = 0;
		for (const byte of header) sum += byte;
		header.write(`${sum.toString(8).padStart(6, "0")}\0 `, 148);
		blocks.push(header, body, Buffer.alloc((512 - (body.length % 512)) % 512));
	}
	blocks.push(Buffer.alloc(1024));
	return gzipSync(Buffer.concat(blocks));
}

describe("isInProfile", () => {
	const env = { USERPROFILE: "C:\\Users\\ana", LOCALAPPDATA: "C:\\Users\\ana\\AppData\\Local" };
	test("the default installation is in the profile, in any case", () => {
		expect(isInProfile("C:\\Users\\ana\\.pi-bolt", env)).toBe(true);
		expect(isInProfile("c:\\users\\ANA\\.pi-bolt", env)).toBe(true);
		expect(isInProfile("C:\\Users\\ana\\AppData\\Local\\pi-bolt", env)).toBe(true);
	});
	test("a folder elsewhere, or one whose name only starts like the profile, is not", () => {
		expect(isInProfile("C:\\tools\\pi-bolt", env)).toBe(false);
		expect(isInProfile("C:\\Users\\anabel\\.pi-bolt", env)).toBe(false);
		expect(isInProfile("C:\\Users\\ana", env)).toBe(false);
		expect(isInProfile("C:\\Users\\ana\\.pi-bolt", {})).toBe(false);
	});
});

describe("the signed checksums", () => {
	const sums =
		"# pi-bolt 0.9.7\nabc\n" +
		`${"a".repeat(64)}  pi-bolt-win32-x64.zip\n${"B".repeat(64)} *pi-bolt-win32-x64-jit.zip\n`;
	test("say which release they are, and the checksum of each file", () => {
		expect(sumsVersion(sums)).toBe("0.9.7");
		expect(sumsVersion(sums.replace(/\n/g, "\r\n"))).toBe("0.9.7");
		expect(sumsVersion("a".repeat(64))).toBeUndefined();
		expect(sumsEntry(sums, "pi-bolt-win32-x64.zip")).toBe("a".repeat(64));
		expect(sumsEntry(sums, "pi-bolt-win32-x64-jit.zip")).toBe("b".repeat(64));
		expect(sumsEntry(sums.replace(/\n/g, "\r\n"), "pi-bolt-win32-x64.zip")).toBe("a".repeat(64));
		expect(sumsEntry(sums, "pi-bolt-win32-x64-baseline.zip")).toBeUndefined();
	});
	test("are taken only with a signature from the key they are checked with", () => {
		const { publicKey, privateKey } = generateKeyPairSync("ed25519");
		const pem = publicKey.export({ type: "spki", format: "pem" }).toString();
		const data = Buffer.from(sums);
		const signature = sign(null, data, privateKey);
		expect(sumsSignatureValid(data, signature, pem)).toBe(true);
		expect(sumsSignatureValid(Buffer.from(sums.replace("0.9.7", "0.9.8")), signature, pem)).toBe(false);
		expect(sumsSignatureValid(data, Buffer.alloc(64), pem)).toBe(false);
		// With the release key, this test's signature is not Pi-Bolt's.
		expect(sumsSignatureValid(data, signature)).toBe(false);
	});
});

describe("tgzEntry", () => {
	test("reads one file out of npm's .tgz", () => {
		const zip = Buffer.from([0x50, 0x4b, 3, 4, 1, 2, 3]);
		const archive = tgz({ "package/package.json": "{}", "package/pi-bolt-win32-x64.zip": zip });
		expect(tgzEntry(archive, "package/pi-bolt-win32-x64.zip")?.equals(zip)).toBe(true);
		expect(tgzEntry(archive, "package/package.json")?.toString()).toBe("{}");
		expect(tgzEntry(archive, "package/other.zip")).toBeUndefined();
		expect(tgzEntry(Buffer.from("not a tarball"), "package/pi-bolt-win32-x64.zip")).toBeUndefined();
		expect(sha256(zip)).toMatch(/^[0-9a-f]{64}$/);
	});
});

describe("installFiles", () => {
	test("replaces the build's files, adds new ones, keeps the others, and leaves nothing renamed aside", () => {
		const from = join(dir, "new"),
			to = join(dir, "pi-bolt-win32-x64");
		mkdirSync(join(from, "theme"), { recursive: true });
		writeFileSync(join(from, "pi-bolt.exe"), "new exe");
		writeFileSync(join(from, "theme", "dark.json"), "new theme");
		writeFileSync(join(from, "added.txt"), "added");
		mkdirSync(join(to, "theme"), { recursive: true });
		writeFileSync(join(to, "pi-bolt.exe"), "old exe");
		writeFileSync(join(to, "theme", "dark.json"), "old theme");
		writeFileSync(join(to, "compiled.json"), "kept");
		writeFileSync(join(to, "pi-bolt.exe.1.pibolt-old"), "left over by an earlier update");
		installFiles(from, to, 2);
		expect(readFileSync(join(to, "pi-bolt.exe"), "utf8")).toBe("new exe");
		expect(readFileSync(join(to, "theme", "dark.json"), "utf8")).toBe("new theme");
		expect(readFileSync(join(to, "added.txt"), "utf8")).toBe("added");
		expect(readFileSync(join(to, "compiled.json"), "utf8")).toBe("kept");
		expect(readdirSync(to).filter((name) => /pibolt-(old|new)$/.test(name))).toEqual([]);
	});
	test("removes the mobile UI skill an earlier build left, and keeps the rest of the folder", () => {
		const from = join(dir, "new"),
			to = join(dir, "pi-bolt-win32-x64");
		mkdirSync(from, { recursive: true });
		writeFileSync(join(from, "pi-bolt.exe"), "new exe");
		mkdirSync(join(to, "skills", "pibolt-mobile-ui", "examples"), { recursive: true });
		writeFileSync(join(to, "skills", "pibolt-mobile-ui", "SKILL.md"), "old skill");
		writeFileSync(join(to, "skills", "pibolt-mobile-ui", "examples", "a.json"), "{}");
		mkdirSync(join(to, "skills", "mine"), { recursive: true });
		installFiles(from, to, 4);
		expect(existsSync(join(to, "skills", "pibolt-mobile-ui"))).toBe(false);
		expect(existsSync(join(to, "skills", "mine"))).toBe(true);
		expect(readFileSync(join(to, "pi-bolt.exe"), "utf8")).toBe("new exe");
	});
	// #316: a .pibolt-old an earlier update left, which an older Pi-Bolt still has mapped, cannot be deleted (Windows allows a
	// rename of a mapped file, not its deletion). It is left for a later install; the update goes on. A folder nothing may be
	// written to stands in for the lock: what is in it cannot be deleted.
	test.skipIf(process.platform === "win32" || process.getuid?.() === 0)(
		"a leftover that cannot be deleted is left for a later install, and the update goes on (#316)",
		() => {
			const from = join(dir, "new"),
				to = join(dir, "pi-bolt-win32-x64");
			const locked = join(to, "native", "win32", "prebuilds", "win32-x64");
			mkdirSync(from, { recursive: true });
			writeFileSync(join(from, "pi-bolt.exe"), "new exe");
			mkdirSync(locked, { recursive: true });
			writeFileSync(join(to, "pi-bolt.exe"), "old exe");
			const leftover = join(locked, "win32-platform.node.1794212257087.pibolt-old");
			writeFileSync(leftover, "mapped by a Pi-Bolt started days ago");
			chmodSync(locked, 0o555);
			try {
				installFiles(from, to, 5);
				expect(readFileSync(join(to, "pi-bolt.exe"), "utf8")).toBe("new exe");
				expect(existsSync(leftover)).toBe(true);
			} finally {
				chmodSync(locked, 0o755);
			}
			// Once the process that held it is gone, the next install deletes it.
			writeFileSync(join(from, "pi-bolt.exe"), "newer exe");
			installFiles(from, to, 6);
			expect(existsSync(leftover)).toBe(false);
			expect(readFileSync(join(to, "pi-bolt.exe"), "utf8")).toBe("newer exe");
		},
	);
	test.skipIf(process.platform === "win32" || process.getuid?.() === 0)(
		"a mobile UI skill that cannot be removed yet does not fail an update whose files are in place (#316)",
		() => {
			const from = join(dir, "new"),
				to = join(dir, "pi-bolt-win32-x64");
			mkdirSync(from, { recursive: true });
			writeFileSync(join(from, "pi-bolt.exe"), "new exe");
			mkdirSync(join(to, "skills", "pibolt-mobile-ui"), { recursive: true });
			writeFileSync(join(to, "skills", "pibolt-mobile-ui", "SKILL.md"), "old skill");
			chmodSync(join(to, "skills"), 0o555);
			try {
				installFiles(from, to, 7);
				expect(readFileSync(join(to, "pi-bolt.exe"), "utf8")).toBe("new exe");
			} finally {
				chmodSync(join(to, "skills"), 0o755);
			}
		},
	);
	test("says which file it could not put in place", () => {
		const from = join(dir, "new"),
			to = join(dir, "pi-bolt-win32-x64");
		mkdirSync(from, { recursive: true });
		writeFileSync(join(from, "pi-bolt.exe"), "new exe");
		mkdirSync(to, { recursive: true });
		writeFileSync(join(to, "pi-bolt.exe"), "old exe");
		// A folder nothing may be written to: the new file cannot be moved in, and the old one stays.
		chmodSync(to, 0o555);
		try {
			expect(() => installFiles(from, to, 3)).toThrow(/could not put .*pi-bolt\.exe in place/);
			expect(readFileSync(join(to, "pi-bolt.exe"), "utf8")).toBe("old exe");
		} finally {
			chmodSync(to, 0o755);
		}
	});
});

describe("keepPrevious", () => {
	const build = (folder: string, version: string) => {
		mkdirSync(folder, { recursive: true });
		writeFileSync(join(folder, "pi-bolt.exe"), `exe ${version}`);
		writeFileSync(join(folder, "pi-bolt.txt"), `Pi-Bolt ${version} (Pi 1.3.0), win32-x64, JIT off\n`);
	};
	test("keeps the build an update replaces as <folder>.previous, without what was renamed aside", () => {
		const current = join(dir, "pi-bolt-win32-x64"),
			incoming = join(dir, "new");
		build(current, "0.9.6");
		writeFileSync(join(current, "pi-bolt.exe.5.pibolt-old"), "aside");
		build(incoming, "0.9.7");
		keepPrevious(current, incoming);
		expect(readFileSync(join(`${current}.previous`, "pi-bolt.exe"), "utf8")).toBe("exe 0.9.6");
		expect(existsSync(join(`${current}.previous`, "pi-bolt.exe.5.pibolt-old"))).toBe(false);
	});
	test("not when the same build is installed again, nor when there is none", () => {
		const current = join(dir, "pi-bolt-win32-x64"),
			incoming = join(dir, "new");
		build(current, "0.9.7");
		build(incoming, "0.9.7");
		keepPrevious(current, incoming);
		expect(existsSync(`${current}.previous`)).toBe(false);
		keepPrevious(join(dir, "none"), incoming);
		expect(existsSync(join(dir, "none.previous"))).toBe(false);
	});
});
