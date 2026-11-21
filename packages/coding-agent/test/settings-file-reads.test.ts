import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, expect, it, vi } from "vitest";
import { FileSettingsStorage, SettingsManager } from "../src/core/settings-manager.ts";

const roots: string[] = [];
afterEach(() => {
	for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true });
});

it("shares a locked read between new managers and keeps active projects' cached reads independent", () => {
	const root = mkdtempSync(join(tmpdir(), "pi-settings-"));
	roots.push(root);
	const first = join(root, "a");
	const second = join(root, "b");
	for (const dir of [first, second]) {
		mkdirSync(join(dir, ".pi"), { recursive: true });
		writeFileSync(join(dir, ".pi/settings.json"), JSON.stringify({ name: dir }));
	}

	const a = new FileSettingsStorage(first, root);
	const b = new FileSettingsStorage(second, root);
	const repeated = new FileSettingsStorage(first, root);
	const aLock = vi.spyOn(a, "withLock");
	const repeatedLock = vi.spyOn(repeated, "withLock");
	expect(a.read("project")).toBe(JSON.stringify({ name: first }));
	expect(repeated.read("project")).toBe(a.read("project"));
	expect(b.read("project")).toBe(JSON.stringify({ name: second }));
	expect(a.read("project")).toBe(repeated.read("project"));
	expect(aLock).toHaveBeenCalledTimes(1);
	expect(repeatedLock).toHaveBeenCalledTimes(0);

	const file = join(first, ".pi/settings.json");
	writeFileSync(file, '{"theme":"dark"}');
	expect(repeated.read("project")).toBe('{"theme":"dark"}');
	expect(a.read("project")).toBe('{"theme":"dark"}');
	rmSync(file);
	expect(a.read("project")).toBeUndefined();
	expect(repeated.read("project")).toBeUndefined();
	expect(b.read("project")).toBe(JSON.stringify({ name: second }));
});

it("reads only the globally configured HTTP proxy even in a trusted project", () => {
	const root = mkdtempSync(join(tmpdir(), "pi-proxy-settings-"));
	roots.push(root);
	const project = join(root, "work");
	mkdirSync(join(project, ".pi"), { recursive: true });
	writeFileSync(join(project, ".pi/settings.json"), '{"httpProxy":"http://project.invalid"}');
	expect(SettingsManager.create(project, root).getGlobalHttpProxy()).toBeUndefined();
	writeFileSync(join(root, "settings.json"), '{"httpProxy":"http://global.invalid"}');
	expect(SettingsManager.create(project, root).getGlobalHttpProxy()).toBe("http://global.invalid");
});
