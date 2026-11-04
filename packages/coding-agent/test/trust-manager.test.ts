import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { hasTrustRequiringProjectResources, ProjectTrustStore } from "../src/core/trust-manager.ts";

describe("ProjectTrustStore", () => {
	let tempDir: string;
	let agentDir: string;
	let cwd: string;

	beforeEach(() => {
		tempDir = join(tmpdir(), `trust-test-${Date.now()}-${Math.random().toString(36).slice(2)}`);
		agentDir = join(tempDir, "agent");
		cwd = join(tempDir, "project");
		mkdirSync(agentDir, { recursive: true });
		mkdirSync(cwd, { recursive: true });
	});

	afterEach(() => {
		rmSync(tempDir, { recursive: true, force: true });
	});

	it("stores decisions and inherits from parent directories", () => {
		const store = new ProjectTrustStore(agentDir);
		const parentDir = join(tempDir, "trusted-parent");
		const childDir = join(parentDir, "project");
		mkdirSync(childDir, { recursive: true });

		expect(store.get(childDir)).toBeNull();
		store.set(parentDir, true);
		expect(store.get(childDir)).toBe(true);
		store.set(childDir, false);
		expect(store.get(childDir)).toBe(false);
		store.set(childDir, null);
		expect(store.get(childDir)).toBe(true);
	});

	it.skipIf(process.platform !== "win32")(
		"on Windows, trusts a folder whatever the letter case it is given in",
		() => {
			const store = new ProjectTrustStore(agentDir);
			store.set(cwd, true);
			expect(store.get(cwd.toUpperCase())).toBe(true);
			expect(store.get(cwd.toLowerCase())).toBe(true);
			// One entry for the folder: deciding again in other letter case replaces it.
			store.set(cwd.toUpperCase(), false);
			expect(store.get(cwd)).toBe(false);
			store.set(cwd.toLowerCase(), null);
			expect(store.get(cwd)).toBeNull();
		},
	);

	it("detects trust-requiring project resources", () => {
		const originalHome = process.env.HOME;
		process.env.HOME = tempDir;
		try {
			mkdirSync(join(tempDir, ".pi", "agent"), { recursive: true });
			mkdirSync(join(tempDir, ".agents", "skills"), { recursive: true });
			expect(hasTrustRequiringProjectResources(tempDir)).toBe(false);
			expect(hasTrustRequiringProjectResources(cwd)).toBe(false);

			writeFileSync(join(tempDir, ".pi", "settings.json"), "{}");
			expect(hasTrustRequiringProjectResources(tempDir)).toBe(true);
			rmSync(join(tempDir, ".pi", "settings.json"), { force: true });

			mkdirSync(join(cwd, ".pi"), { recursive: true });
			writeFileSync(join(cwd, ".pi", "settings.json"), "{}");
			expect(hasTrustRequiringProjectResources(cwd)).toBe(true);

			rmSync(join(cwd, ".pi"), { recursive: true, force: true });
			mkdirSync(join(cwd, ".agents", "skills"), { recursive: true });
			expect(hasTrustRequiringProjectResources(cwd)).toBe(true);

			rmSync(join(cwd, ".agents"), { recursive: true, force: true });
			// Bare repo with only an AGENTS.md: its content reaches the model via
			// project_context, so it must trigger the trust prompt.
			writeFileSync(join(cwd, "AGENTS.md"), "# test");
			expect(hasTrustRequiringProjectResources(cwd)).toBe(true);
			rmSync(join(cwd, "AGENTS.md"), { force: true });
			expect(hasTrustRequiringProjectResources(cwd)).toBe(false);

			// Ancestor context files load too: opening a subdir of a repo with an
			// AGENTS.md at the root must trigger the trust prompt.
			writeFileSync(join(cwd, "AGENTS.md"), "# test");
			const subdir = join(cwd, "sub", "dir");
			mkdirSync(subdir, { recursive: true });
			expect(hasTrustRequiringProjectResources(subdir)).toBe(true);
		} finally {
			if (originalHome === undefined) {
				delete process.env.HOME;
			} else {
				process.env.HOME = originalHome;
			}
		}
	});
});
