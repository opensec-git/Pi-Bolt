import type * as ChildProcess from "node:child_process";
import type * as Fs from "node:fs";
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ensureTool, getLatestVersion, getToolPath, type ToolStatus } from "../src/utils/tools-manager.ts";

const originalOffline = process.env.PI_OFFLINE;
const originalPath = process.env.PATH;
const originalCwd = process.cwd();
const temporary: string[] = [];

vi.mock("fs", async (importOriginal) => {
	const actual = await importOriginal<typeof Fs>();
	return {
		...actual,
		existsSync: vi.fn(() => false),
	};
});

vi.mock("child_process", async (importOriginal) => {
	const actual = await importOriginal<typeof ChildProcess>();
	return {
		...actual,
		spawnSync: vi.fn(() => ({ error: new Error("not found") })),
	};
});

afterEach(() => {
	if (originalOffline === undefined) delete process.env.PI_OFFLINE;
	else process.env.PI_OFFLINE = originalOffline;
	process.env.PATH = originalPath;
	process.chdir(originalCwd);
	for (const dir of temporary.splice(0)) rmSync(dir, { recursive: true, force: true });
	vi.unstubAllGlobals();
});

function temporaryDir(): string {
	const dir = mkdtempSync(join(tmpdir(), "pi-tools-manager-"));
	temporary.push(dir);
	return dir;
}

function redirectResponse(location: string): Response {
	return new Response(null, { status: 302, headers: { location } });
}

describe("getLatestVersion", () => {
	it("resolves the version from the release page redirect", async () => {
		const fetchMock = vi.fn(async () => redirectResponse("https://github.com/sharkdp/fd/releases/tag/v10.4.2"));
		vi.stubGlobal("fetch", fetchMock);

		await expect(getLatestVersion("sharkdp/fd")).resolves.toBe("10.4.2");
		expect(fetchMock).toHaveBeenCalledWith(
			"https://github.com/sharkdp/fd/releases/latest",
			expect.objectContaining({ redirect: "manual" }),
		);
	});

	it("keeps tags without a v prefix intact", async () => {
		vi.stubGlobal(
			"fetch",
			vi.fn(async () => redirectResponse("https://github.com/BurntSushi/ripgrep/releases/tag/15.2.0")),
		);

		await expect(getLatestVersion("BurntSushi/ripgrep")).resolves.toBe("15.2.0");
	});

	it("resolves relative redirect targets", async () => {
		vi.stubGlobal(
			"fetch",
			vi.fn(async () => redirectResponse("/sharkdp/fd/releases/tag/v10.4.2")),
		);

		await expect(getLatestVersion("sharkdp/fd")).resolves.toBe("10.4.2");
	});

	it("discards the redirect response body", async () => {
		const response = new Response("<html></html>", {
			status: 302,
			headers: { location: "https://github.com/sharkdp/fd/releases/tag/v10.4.2" },
		});
		vi.stubGlobal(
			"fetch",
			vi.fn(async () => response),
		);

		await expect(getLatestVersion("sharkdp/fd")).resolves.toBe("10.4.2");
		expect(response.bodyUsed).toBe(true);
	});

	it("fails clearly when the endpoint does not redirect", async () => {
		vi.stubGlobal(
			"fetch",
			vi.fn(async () => new Response("not found", { status: 404 })),
		);

		await expect(getLatestVersion("sharkdp/fd")).rejects.toThrow(
			"Failed to resolve latest sharkdp/fd release: HTTP 404 without redirect",
		);
	});

	it("fails clearly when the redirect does not point at a release tag", async () => {
		vi.stubGlobal(
			"fetch",
			vi.fn(async () => redirectResponse("https://github.com/login")),
		);

		await expect(getLatestVersion("sharkdp/fd")).rejects.toThrow(
			"Failed to resolve latest sharkdp/fd release: unexpected redirect to https://github.com/login",
		);
	});
});

describe("getToolPath", () => {
	it("finds no tool that PATH does not have", () => {
		process.env.PATH = temporaryDir();
		expect(getToolPath("rg")).toBeNull();
	});

	it.runIf(process.platform !== "win32")("finds an executable tool in PATH by its name", () => {
		const bin = temporaryDir();
		writeFileSync(join(bin, "rg"), "");
		chmodSync(join(bin, "rg"), 0o755);
		process.env.PATH = bin;
		expect(getToolPath("rg")).toBe("rg");
	});

	it.runIf(process.platform === "win32")(
		"finds a tool in PATH by its path, and not one in the working directory",
		() => {
			const bin = temporaryDir();
			const project = temporaryDir();
			writeFileSync(join(bin, "rg.cmd"), "");
			writeFileSync(join(bin, "rg.exe"), "");
			writeFileSync(join(project, "rg.exe"), "");
			process.chdir(project);
			process.env.PATH = `.;${bin}`;
			expect(getToolPath("rg")).toBe(join(bin, "rg.exe"));
		},
	);

	it.runIf(process.platform === "win32")(
		"does not take a batch file for a tool (cmd.exe would parse the model's patterns again)",
		() => {
			const shims = temporaryDir();
			const bin = temporaryDir();
			writeFileSync(join(shims, "rg.cmd"), "");
			writeFileSync(join(shims, "rg.bat"), "");
			process.env.PATH = shims;
			expect(getToolPath("rg")).toBeNull();
			writeFileSync(join(bin, "rg.exe"), "");
			process.env.PATH = `${shims};${bin}`;
			expect(getToolPath("rg")).toBe(join(bin, "rg.exe"));
		},
	);
});

describe("ensureTool", () => {
	it("reports status through a callback without writing to the console", async () => {
		process.env.PI_OFFLINE = "1";
		const statuses: ToolStatus[] = [];
		const consoleLog = vi.spyOn(console, "log").mockImplementation(() => {});

		const result = await ensureTool("fd", (status) => statuses.push(status));

		expect(result).toBeUndefined();
		expect(statuses).toEqual([
			{
				type: "warning",
				message: "fd not found. Offline mode enabled, skipping download.",
			},
		]);
		expect(consoleLog).not.toHaveBeenCalled();
		consoleLog.mockRestore();
	});

	it("surfaces the error cause chain when a download fails", async () => {
		delete process.env.PI_OFFLINE;
		const cause = new Error("connect ETIMEDOUT 140.82.113.3:443");
		vi.stubGlobal(
			"fetch",
			vi.fn(async () => {
				throw new TypeError("fetch failed", { cause });
			}),
		);
		const statuses: ToolStatus[] = [];

		const result = await ensureTool("fd", (status) => statuses.push(status));

		expect(result).toBeUndefined();
		expect(statuses).toEqual([
			{ type: "info", message: "fd not found. Downloading..." },
			{
				type: "warning",
				message: "Failed to download fd: fetch failed: connect ETIMEDOUT 140.82.113.3:443",
			},
		]);
	});
});
