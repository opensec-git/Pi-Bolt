import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { ModelConfig } from "../src/core/model-config.ts";

function provider(name: string) {
	return { baseUrl: "https://provider.test/v1", apiKey: "key", api: "openai-completions", name };
}

describe("ModelConfig.load", () => {
	let dir: string;
	let path: string;

	beforeEach(() => {
		dir = mkdtempSync(join(tmpdir(), "pi-model-config-"));
		path = join(dir, "models.json");
	});

	afterEach(() => {
		rmSync(dir, { recursive: true, force: true });
	});

	it("reads models.json once while it is unchanged, and again when it changes", async () => {
		writeFileSync(path, JSON.stringify({ providers: { one: provider("One") } }));
		const first = await ModelConfig.load(path);
		expect(await ModelConfig.load(path)).toBe(first);
		expect(first.getProviderIds()).toEqual(["one"]);

		writeFileSync(path, JSON.stringify({ providers: { one: provider("One"), two: provider("Two") } }));
		const changed = await ModelConfig.load(path);
		expect(changed).not.toBe(first);
		expect(changed.getProviderIds()).toEqual(["one", "two"]);
	});

	it("keeps a parse error only while the file is unchanged", async () => {
		writeFileSync(path, "{ not json");
		const broken = await ModelConfig.load(path);
		expect(broken.getError()).toContain("Failed to parse models.json");
		expect(await ModelConfig.load(path)).toBe(broken);

		writeFileSync(path, JSON.stringify({ providers: { one: provider("One") } }));
		const fixed = await ModelConfig.load(path);
		expect(fixed.getError()).toBeUndefined();
		expect(fixed.getProviderIds()).toEqual(["one"]);
	});

	it("accepts and rejects files as TypeBox decides, and explains a rejected one with TypeBox's errors", async () => {
		const model = { id: "m", contextWindow: 1000, maxTokens: 100, input: ["text"] };
		const cases: [unknown, string | undefined][] = [
			[{ providers: { one: { ...provider("One"), models: [model] } } }, undefined],
			[{ providers: {} }, undefined],
			[[], "  - root: must be object"],
			[{}, "  - providers: must have required properties providers"],
			[
				{ providers: { one: { ...provider("One"), baseUrl: "" } } },
				"  - providers.one.baseUrl: must not have fewer than 1 characters",
			],
			[
				{ providers: { one: { ...provider("One"), models: [{ ...model, input: ["smell"] }] } } },
				"  - providers.one.models.0.input.0: must be equal to one of the allowed values",
			],
			[
				{ providers: { one: { modelOverrides: { m: { contextWindow: 0 } } } } },
				"  - providers.one.modelOverrides.m.contextWindow: must be > 0",
			],
		];
		for (const [document, error] of cases) {
			writeFileSync(path, JSON.stringify(document));
			const config = await ModelConfig.load(path);
			expect(config.getError(), JSON.stringify(document)).toBe(
				error === undefined ? undefined : `Invalid models.json schema:\n${error}\n\nFile: ${path}`,
			);
		}
	});

	it("reads a file that appears after a load found none", async () => {
		expect((await ModelConfig.load(path)).getProviderIds()).toEqual([]);
		writeFileSync(path, JSON.stringify({ providers: { one: provider("One") } }));
		expect((await ModelConfig.load(path)).getProviderIds()).toEqual(["one"]);
	});
});
