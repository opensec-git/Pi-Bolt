import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { DefaultPackageManager } from "../src/core/package-manager.ts";
import { ResourcePathKeys } from "../src/core/resource-path-keys.ts";
import { SettingsManager } from "../src/core/settings-manager.ts";
import { getResourcePathKey } from "../src/core/source-info.ts";

// The temporary folder is reached through a symbolic link on macOS (/var -> /private/var), so every key here also
// checks that the folder's own links are resolved.
describe("ResourcePathKeys", () => {
	let tempDir: string;

	beforeEach(() => {
		tempDir = mkdtempSync(join(tmpdir(), "pi-path-keys-"));
	});

	afterEach(() => {
		rmSync(tempDir, { recursive: true, force: true });
	});

	function createLinkedFolder(): { dir: string; paths: string[] } {
		const dir = join(tempDir, "prompts");
		const other = join(tempDir, "other");
		mkdirSync(join(dir, "sub"), { recursive: true });
		mkdirSync(other, { recursive: true });
		writeFileSync(join(dir, "a.md"), "a");
		writeFileSync(join(dir, "b.md"), "b");
		writeFileSync(join(dir, "Mixed.md"), "m");
		writeFileSync(join(dir, "sub", "c.md"), "c");
		writeFileSync(join(other, "target.md"), "t");
		symlinkSync(join(other, "target.md"), join(dir, "link.md"));
		symlinkSync(join(dir, "a.md"), join(dir, "same-as-a.md"));
		symlinkSync(join(tempDir, "missing.md"), join(dir, "broken.md"));
		symlinkSync(other, join(dir, "linked-dir"), "dir");
		symlinkSync(dir, join(tempDir, "prompts-link"), "dir");
		const paths = [
			join(dir, "a.md"),
			join(dir, "b.md"),
			join(dir, "Mixed.md"),
			// Another case than the stored name: on a case-insensitive file system realpath returns the stored one.
			join(dir, "mixed.md"),
			join(dir, "A.md"),
			join(dir, "link.md"),
			join(dir, "same-as-a.md"),
			join(dir, "broken.md"),
			join(dir, "missing.md"),
			join(dir, "sub"),
			join(dir, "sub", "c.md"),
			join(dir, "linked-dir"),
			join(dir, "linked-dir", "target.md"),
			join(tempDir, "prompts-link", "a.md"),
			join(tempDir, "prompts-link", "b.md"),
			`${dir}/./a.md`,
			`${dir}/sub/`,
			"relative/a.md",
			"builtin:mcp",
			"<inline:x>",
		];
		return { dir, paths };
	}

	it("gives getResourcePathKey's key for every kind of path when computed together", () => {
		const { paths } = createLinkedFolder();
		const keys = new ResourcePathKeys();
		keys.prepare(paths);
		for (const path of paths) {
			expect(keys.keyOf(path), path).toBe(getResourcePathKey(path));
		}
	});

	it("gives the same key to two paths of one file and different keys to different files", () => {
		const { dir } = createLinkedFolder();
		const keys = new ResourcePathKeys();
		const paths = [
			join(dir, "a.md"),
			join(dir, "same-as-a.md"),
			join(tempDir, "prompts-link", "a.md"),
			join(dir, "b.md"),
		];
		keys.prepare(paths);
		expect(new Set(paths.slice(0, 3).map((path) => keys.keyOf(path))).size).toBe(1);
		expect(keys.keyOf(join(dir, "b.md"))).not.toBe(keys.keyOf(join(dir, "a.md")));
	});

	it("gives getResourcePathKey's key for paths noted as reached through no link", () => {
		const { dir } = createLinkedFolder();
		const keys = new ResourcePathKeys();
		const noted = [join(dir, "a.md"), join(dir, "sub", "c.md")];
		for (const path of noted) keys.notePlainEntry(dir, path);
		for (const path of noted) {
			expect(keys.keyOf(path), path).toBe(getResourcePathKey(path));
		}
	});

	it("keys every resource discovery finds as getResourcePathKey does, through linked folders and files", async () => {
		const agentDir = join(tempDir, "agent");
		const cwd = join(tempDir, "project");
		const shared = join(tempDir, "shared");
		mkdirSync(join(agentDir, "prompts"), { recursive: true });
		mkdirSync(join(agentDir, "skills", "plain", "nested"), { recursive: true });
		mkdirSync(join(agentDir, "themes"), { recursive: true });
		mkdirSync(join(cwd, ".pi"), { recursive: true });
		mkdirSync(join(shared, "linked-skill"), { recursive: true });
		const skill = (name: string) => `---\nname: ${name}\ndescription: ${name}\n---\n`;
		writeFileSync(join(agentDir, "prompts", "one.md"), "one");
		writeFileSync(join(agentDir, "prompts", "two.md"), "two");
		writeFileSync(join(shared, "three.md"), "three");
		symlinkSync(join(shared, "three.md"), join(agentDir, "prompts", "three.md"));
		writeFileSync(join(agentDir, "skills", "plain", "SKILL.md"), skill("plain"));
		writeFileSync(join(agentDir, "skills", "root.md"), skill("root"));
		writeFileSync(join(shared, "linked-skill", "SKILL.md"), skill("linked-skill"));
		symlinkSync(join(shared, "linked-skill"), join(agentDir, "skills", "linked-skill"), "dir");
		mkdirSync(join(agentDir, "skills", "file-link"), { recursive: true });
		symlinkSync(join(shared, "linked-skill", "SKILL.md"), join(agentDir, "skills", "file-link", "SKILL.md"));
		writeFileSync(join(agentDir, "themes", "theme.json"), "{}");
		// The project's folders are the agent's, through links: each resource must count once.
		symlinkSync(join(agentDir, "prompts"), join(cwd, ".pi", "prompts"), "dir");
		symlinkSync(join(agentDir, "skills"), join(cwd, ".pi", "skills"), "dir");

		const settingsManager = SettingsManager.inMemory();
		settingsManager.setProjectTrusted(true);
		const packageManager = new DefaultPackageManager({ cwd, agentDir, settingsManager });
		const keys = new ResourcePathKeys();
		const resolved = await packageManager.resolve(undefined, keys);
		const reference = await new DefaultPackageManager({ cwd, agentDir, settingsManager }).resolve();

		const all = [...resolved.prompts, ...resolved.skills, ...resolved.themes];
		expect(all.length).toBeGreaterThan(5);
		for (const resource of all) {
			expect(keys.keyOf(resource.path), resource.path).toBe(getResourcePathKey(resource.path));
		}
		expect(resolved.prompts.map((resource) => resource.path)).toEqual(
			reference.prompts.map((resource) => resource.path),
		);
		expect(resolved.skills.map((resource) => resource.path)).toEqual(
			reference.skills.map((resource) => resource.path),
		);
	});
});
