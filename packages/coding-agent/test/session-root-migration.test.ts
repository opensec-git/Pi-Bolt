import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CONFIG_DIR_NAME, ENV_AGENT_DIR } from "../src/config.ts";
import { migrateSessionsFromAgentRoot, runMigrations } from "../src/migrations.ts";

describe("agent root startup migrations", () => {
	let root: string;
	let agent: string;
	let project: string;
	beforeEach(() => {
		root = mkdtempSync(join(tmpdir(), "pi-root-migration-"));
		agent = join(root, "agent");
		project = join(root, "project");
		mkdirSync(agent);
		mkdirSync(project);
		vi.stubEnv(ENV_AGENT_DIR, agent);
	});
	afterEach(() => {
		vi.unstubAllEnvs();
		rmSync(root, { recursive: true, force: true });
	});

	it("preserves the body and decodes a legacy cwd across a read boundary", () => {
		const prefix = '{"type":"session","pad":"';
		const cwd = "/𠮷";
		const middle = '","cwd":"/';
		const header = `${prefix + "a".repeat(4095 - prefix.length - middle.length) + middle}𠮷"}`;
		expect(Buffer.byteLength(header.slice(0, header.indexOf("𠮷")))).toBe(4095);
		const contents = Buffer.concat([Buffer.from(`${header}\r\n`), Buffer.alloc(512 * 1024, 255)]);
		writeFileSync(join(agent, "old.jsonl"), contents);
		runMigrations(project);
		expect(readFileSync(join(agent, "sessions", `--${cwd.slice(1)}--`, "old.jsonl")).equals(contents)).toBe(true);
		expect(existsSync(join(agent, "old.jsonl"))).toBe(false);
	});

	it("accepts an oversized first line without a final newline or session id", () => {
		const header = JSON.stringify({ type: "session", cwd: "/legacy", metadata: "x".repeat(2 ** 20 + 1) });
		writeFileSync(join(agent, "old.jsonl"), header);
		migrateSessionsFromAgentRoot();
		expect(readFileSync(join(agent, "sessions", "--legacy--", "old.jsonl"), "utf8")).toBe(header);
	});

	it("keeps malformed leading lines and existing destinations untouched across repeated launches", () => {
		const valid = JSON.stringify({ type: "session", cwd: "/legacy" });
		const directory = join(agent, "sessions", "--legacy--");
		mkdirSync(directory, { recursive: true });
		writeFileSync(join(directory, "existing.jsonl"), "preserved");
		for (const [name, text] of Object.entries({
			existing: valid,
			blank: `\n${valid}`,
			invalid: `bad\n${valid}`,
			empty: "",
		})) {
			writeFileSync(join(agent, `${name}.jsonl`), text);
		}
		runMigrations(project);
		runMigrations(project);
		expect(readFileSync(join(directory, "existing.jsonl"), "utf8")).toBe("preserved");
		for (const name of ["blank", "invalid", "empty", "existing"])
			expect(existsSync(join(agent, `${name}.jsonl`))).toBe(true);
	});

	it("keeps global and project migrations and warnings in order", () => {
		const local = join(project, CONFIG_DIR_NAME);
		for (const dir of [agent, local]) {
			for (const name of ["commands", "hooks", "tools"]) mkdirSync(join(dir, name), { recursive: true });
			writeFileSync(join(dir, "tools", "custom"), "tool");
		}
		writeFileSync(join(agent, "tools", "rg"), "managed");
		const { deprecationWarnings } = runMigrations(project);
		expect(deprecationWarnings.map((s) => s.split(" ").slice(0, 2).join(" "))).toEqual([
			"Global hooks/",
			"Global tools/",
			"Project hooks/",
			"Project tools/",
		]);
		expect(readFileSync(join(agent, "bin", "rg"), "utf8")).toBe("managed");
		for (const dir of [agent, local]) expect(existsSync(join(dir, "prompts"))).toBe(true);
	});

	it.skipIf(process.platform === "win32" || process.getuid?.() === 0)(
		"migrates named files in a searchable but unlistable home",
		() => {
			const keyFile = join(agent, "keybindings.json");
			writeFileSync(keyFile, JSON.stringify({ expandTools: "ctrl+x" }));
			chmodSync(agent, 0o311);
			try {
				runMigrations(project);
				expect(JSON.parse(readFileSync(keyFile, "utf8"))).toEqual({ "app.tools.expand": "ctrl+x" });
			} finally {
				chmodSync(agent, 0o700);
			}
		},
	);
});
