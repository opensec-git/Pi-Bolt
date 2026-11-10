// `pi-bolt update` installs the release's build and compiles nothing: compile.json stays for a later `pi-bolt compile`.
import { createHash, createPrivateKey, sign } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { handlePackageCommand } from "../src/package-manager-cli.ts";
import type * as PiBolt from "../src/pi-bolt.ts";
import { allowNetwork } from "./test-network-env.ts";

// A Pi-Bolt 1.0.2 build whose release key is this test's: the fake release's checksums are signed with it. (A key made for
// this test only, written out: vi.mock runs before the imports.)
const keys = vi.hoisted(() => ({
	publicPem:
		"-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEAE/DjxB4IY4fqkn4nUU7mHHb9DourHdRT213udqChdME=\n-----END PUBLIC KEY-----\n",
	privatePem:
		"-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEIEBIGPfvEgfu/Qx5nuxevl3y8Oy3vs88OhnLA5xn/Fau\n-----END PRIVATE KEY-----\n",
}));
vi.mock("../src/pi-bolt.ts", async (importOriginal) => ({
	...(await importOriginal<typeof PiBolt>()),
	PIBOLT: { version: "1.0.2", variant: "x64", jit: false },
	PIBOLT_RELEASE_KEY: keys.publicPem,
}));

const realExecPath = process.execPath;
const realFetch = globalThis.fetch;
let dir: string;
let installDir: string;
let server: Server;

/** The installer the fake release serves: a `pi` of 1.0.3 put in place, whose `compile` leaves a mark that it ran. */
const installer = (mark: string) => `#!/bin/sh
set -e
dir="$PIBOLT_INSTALL/pi-bolt-${process.platform}-$PIBOLT_VARIANT"
mkdir -p "$dir"
cat > "$dir/pi" <<'EOS'
#!/bin/sh
if [ "$1" = compile ]; then touch "${mark}"; exit 0; fi
echo "1.5.0 (Pi-Bolt 1.0.3, test, JIT off)"
EOS
chmod +x "$dir/pi"
`;

beforeEach(async () => {
	allowNetwork();
	dir = mkdtempSync(join(tmpdir(), "pibolt-update-no-compile-"));
	vi.stubEnv("HOME", dir);
	vi.stubEnv("PIBOLT_CHANNEL", "");
	vi.stubEnv("PIBOLT_NPM", "");
	vi.stubEnv("SCOOP", "");
	vi.stubEnv("SCOOP_GLOBAL", "");
	vi.stubEnv("MISE_DATA_DIR", "");
	installDir = join(dir, ".pi-bolt");
	const buildDir = join(installDir, `pi-bolt-${process.platform}-x64`);
	mkdirSync(buildDir, { recursive: true });
	writeFileSync(join(buildDir, "pi"), "");
	writeFileSync(
		join(buildDir, "pi-bolt.txt"),
		"Pi-Bolt 1.0.2\nExtensions compiled in (pi-bolt compile, 2026-11-10): a\n",
	);
	Object.defineProperty(process, "execPath", { value: join(buildDir, "pi"), configurable: true });

	const script = Buffer.from(installer(join(dir, "compiled")));
	const sums = Buffer.from(`# pi-bolt 1.0.3\n${createHash("sha256").update(script).digest("hex")}  install.sh\n`);
	const files: Record<string, Buffer> = {
		"/opensec-git/Pi-Bolt/releases/download/bolt-v1.0.3/SHA256SUMS": sums,
		"/opensec-git/Pi-Bolt/releases/download/bolt-v1.0.3/SHA256SUMS.sig": sign(
			null,
			sums,
			createPrivateKey(keys.privatePem),
		),
		"/opensec-git/Pi-Bolt/releases/download/bolt-v1.0.3/install.sh": script,
	};
	server = createServer((request, response) => {
		if (request.url === "/opensec-git/Pi-Bolt/releases/latest") {
			response.writeHead(302, { location: "https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v1.0.3" });
			response.end();
			return;
		}
		const body = files[request.url ?? ""];
		response.writeHead(body ? 200 : 404);
		response.end(body);
	});
	await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
	const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
	// github.com is this server: nothing is downloaded from GitHub.
	vi.stubGlobal("fetch", (input: string | URL | Request, init?: RequestInit) => {
		const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url);
		if (url.hostname !== "github.com" && url.hostname !== "api.github.com") {
			throw new Error(`unexpected request: ${url.href}`);
		}
		return realFetch(`${base}${url.pathname}`, init);
	});
	vi.spyOn(console, "log").mockImplementation(() => {});
	vi.spyOn(console, "error").mockImplementation(() => {});
});

afterEach(async () => {
	server.closeAllConnections();
	await new Promise((done) => server.close(done));
	Object.defineProperty(process, "execPath", { value: realExecPath, configurable: true });
	vi.unstubAllGlobals();
	vi.unstubAllEnvs();
	vi.restoreAllMocks();
	process.exitCode = undefined;
	rmSync(dir, { recursive: true, force: true });
});

describe.skipIf(process.platform === "win32")("pi-bolt update and extensions compiled in", () => {
	it("installs the release without compiling them in, and keeps compile.json", async () => {
		const record = JSON.stringify({ version: "1.0.2", extensions: ["a"], exclude: ["b"] });
		writeFileSync(join(installDir, "compile.json"), record);
		expect(await handlePackageCommand(["update", "--self"])).toBe(true);
		expect(process.exitCode).toBeUndefined();
		expect(readFileSync(join(installDir, `pi-bolt-${process.platform}-x64`, "pi"), "utf8")).toContain(
			"Pi-Bolt 1.0.3",
		);
		expect(existsSync(join(dir, "compiled"))).toBe(false);
		expect(readFileSync(join(installDir, "compile.json"), "utf8")).toBe(record);
	});
});
