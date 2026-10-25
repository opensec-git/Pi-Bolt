import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ENV_AGENT_DIR } from "../src/config.ts";
import { handlePackageCommand } from "../src/package-manager-cli.ts";
import type * as PiBolt from "../src/pi-bolt.ts";
import {
	getLatestPiRelease,
	newestPiBoltVersionInFeed,
	piBoltVersionOfReleaseUrl,
} from "../src/utils/version-check.ts";
import { allowNetwork } from "./test-network-env.ts";

// A Pi-Bolt build (scripts/build-pi.sh defines PIBOLT_BUILD); the tests run as Pi otherwise. (vi.mock runs before the imports.)
vi.mock("../src/pi-bolt.ts", async (importOriginal) => ({
	...(await importOriginal<typeof PiBolt>()),
	PIBOLT: { version: "1.0.2", variant: "x64", jit: false },
}));

const LATEST = "https://github.com/opensec-git/Pi-Bolt/releases/latest";
const FEED = "https://github.com/opensec-git/Pi-Bolt/releases.atom";
const API_LATEST = "https://api.github.com/repos/opensec-git/Pi-Bolt/releases/latest";
const API_RELEASES = "https://api.github.com/repos/opensec-git/Pi-Bolt/releases?per_page=30";

/** What api.github.com answers an address that has used up its 60 requests an hour. */
function rateLimited(): Response {
	return Response.json(
		{ message: "API rate limit exceeded for 203.0.113.7." },
		{ status: 403, headers: { "x-ratelimit-limit": "60", "x-ratelimit-remaining": "0" } },
	);
}

function redirectTo(location: string): Response {
	return new Response(null, { status: 302, headers: { location } });
}

const feed = (tags: string[]): string =>
	`<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <link type="text/html" rel="alternate" href="https://github.com/opensec-git/Pi-Bolt/releases"/>
${tags
	.map(
		(tag) => `  <entry>
    <id>tag:github.com,2008:Repository/1402211512/${tag}</id>
    <link rel="alternate" type="text/html" href="https://github.com/opensec-git/Pi-Bolt/releases/tag/${tag}"/>
    <content type="html">&lt;a href=&quot;https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v9.9.9&quot;&gt;&lt;/a&gt;</content>
  </entry>`,
	)
	.join("\n")}
</feed>`;

/** fetch, answered by address; anything else is a test error. */
function serve(routes: Record<string, () => Response>) {
	const fetchMock = vi.fn(async (input: string | URL | Request, _init?: RequestInit) => {
		const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
		const route = routes[url];
		if (!route) throw new Error(`unexpected request: ${url}`);
		return route();
	});
	vi.stubGlobal("fetch", fetchMock);
	return fetchMock;
}

const requested = (fetchMock: ReturnType<typeof serve>): string[] =>
	fetchMock.mock.calls.map((call) => String(call[0]));

const realExecPath = process.execPath;
let dir: string;

beforeEach(() => {
	allowNetwork();
	dir = mkdtempSync(join(tmpdir(), "pibolt-latest-"));
	vi.stubEnv("PIBOLT_CHANNEL", "");
	vi.stubEnv("PIBOLT_NPM", "");
	vi.stubEnv("SCOOP", "");
	vi.stubEnv("SCOOP_GLOBAL", "");
	vi.stubEnv("MISE_DATA_DIR", "");
	vi.stubEnv(ENV_AGENT_DIR, join(dir, "agent"));
	// An installation of the installer's (~/.pi-bolt/pi-bolt-<platform>-x64), with no channel file: stable.
	const variantDir = join(dir, ".pi-bolt", `pi-bolt-${process.platform}-x64`);
	mkdirSync(variantDir, { recursive: true });
	const exe = join(variantDir, process.platform === "win32" ? "pi-bolt.exe" : "pi");
	writeFileSync(exe, "");
	Object.defineProperty(process, "execPath", { value: exe, configurable: true });
});

afterEach(() => {
	Object.defineProperty(process, "execPath", { value: realExecPath, configurable: true });
	vi.unstubAllGlobals();
	vi.unstubAllEnvs();
	vi.restoreAllMocks();
	process.exitCode = undefined;
	rmSync(dir, { recursive: true, force: true });
});

describe("the latest Pi-Bolt release", () => {
	it("is read from a release page's address", () => {
		expect(piBoltVersionOfReleaseUrl("https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v1.0.3")).toBe(
			"1.0.3",
		);
		expect(piBoltVersionOfReleaseUrl("/opensec-git/Pi-Bolt/releases/tag/bolt-v1.0.3")).toBe("1.0.3");
		expect(piBoltVersionOfReleaseUrl("https://github.com/opensec-git/Pi-Bolt/releases")).toBeUndefined();
		expect(piBoltVersionOfReleaseUrl("https://github.com/opensec-git/Pi-Bolt/releases/tag/v1.4.0")).toBeUndefined();
		expect(piBoltVersionOfReleaseUrl("https://github.com/login?return_to=x")).toBeUndefined();
		expect(piBoltVersionOfReleaseUrl(null)).toBeUndefined();
	});

	it("is found where releases/latest redirects, without GitHub's API", async () => {
		// #149: the API had answered 403 (its limit for an address without a token), and the update stopped there.
		const fetchMock = serve({
			[LATEST]: () => redirectTo("https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v1.0.3"),
			[API_LATEST]: rateLimited,
		});
		await expect(getLatestPiRelease("1.0.2", { retry: true })).resolves.toEqual({ version: "1.0.3" });
		expect(requested(fetchMock)).toEqual([LATEST]);
		expect(fetchMock.mock.calls[0][1]).toMatchObject({ redirect: "manual" });
	});

	it("is asked of the API when the redirect does not say", async () => {
		const fetchMock = serve({
			[LATEST]: () => new Response("Not Found", { status: 404 }),
			[API_LATEST]: () => Response.json({ tag_name: "bolt-v1.0.3" }),
		});
		await expect(getLatestPiRelease("1.0.2")).resolves.toEqual({ version: "1.0.3" });
		expect(requested(fetchMock)).toEqual([LATEST, API_LATEST]);
	});

	it("says what each source answered when none says", async () => {
		serve({ [LATEST]: () => new Response("", { status: 503 }), [API_LATEST]: rateLimited });
		await expect(getLatestPiRelease("1.0.2")).rejects.toThrow(
			`${LATEST}: HTTP 503; ${API_LATEST}: HTTP 403 (rate limit exceeded)`,
		);
	});

	it("on the beta channel, is the newest release the feed lists when the API is limited", async () => {
		vi.stubEnv("PIBOLT_CHANNEL", "beta");
		// (A link in a release's notes, escaped in the feed, is not a release.)
		expect(newestPiBoltVersionInFeed(feed(["bolt-v1.0.2", "v1.4.0", "bolt-v1.0.10", "bolt-v1.0.9"]))).toBe("1.0.10");
		const fetchMock = serve({
			[API_RELEASES]: rateLimited,
			[FEED]: () => new Response(feed(["bolt-v1.0.3", "bolt-v1.0.2"])),
		});
		await expect(getLatestPiRelease("1.0.2")).resolves.toEqual({ version: "1.0.3" });
		expect(requested(fetchMock)).toEqual([API_RELEASES, FEED]);
	});

	it("pi-bolt update works while GitHub's API is limited", async () => {
		// #149: "Error: Could not determine latest pi-bolt version." on 1.0.2, with the latest release 1.0.2.
		serve({
			[LATEST]: () => redirectTo("https://github.com/opensec-git/Pi-Bolt/releases/tag/bolt-v1.0.2"),
			[API_LATEST]: rateLimited,
		});
		const log = vi.spyOn(console, "log").mockImplementation(() => {});
		const error = vi.spyOn(console, "error").mockImplementation(() => {});
		expect(await handlePackageCommand(["update", "--self"])).toBe(true);
		expect(error.mock.calls.flat().join("\n")).toBe("");
		expect(log.mock.calls.flat().join("\n")).toContain("Pi-Bolt is already up to date (v1.0.2)");
		expect(process.exitCode).toBeUndefined();
	});

	it("pi-bolt update says why, and how to update without it, when the release cannot be found", async () => {
		serve({ [LATEST]: () => new Response("", { status: 503 }), [API_LATEST]: rateLimited });
		vi.spyOn(console, "log").mockImplementation(() => {});
		const error = vi.spyOn(console, "error").mockImplementation(() => {});
		expect(await handlePackageCommand(["update", "--self"])).toBe(true);
		const output = error.mock.calls.flat().join("\n");
		expect(output).toMatch(/Could not determine latest \S+ version: /);
		expect(output).toContain("HTTP 403 (rate limit exceeded)");
		expect(output).toContain("You can update without it: ");
		expect(output).toMatch(/install\.(sh|ps1)/);
		expect(process.exitCode).toBe(1);
	});
});
