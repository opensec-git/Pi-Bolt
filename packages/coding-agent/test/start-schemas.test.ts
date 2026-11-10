import { readFileSync } from "node:fs";
import { type TSchema, Type } from "typebox";
import { Compile } from "typebox/compile";
import { Value } from "typebox/value";
import { describe, expect, it } from "vitest";
import { renderStartSchemaModules } from "../scripts/generate-start-schemas.ts";
import { checkKeybindingValue, checkModelsConfig } from "../src/core/config-checks.generated.ts";
import { KeybindingValueSchema } from "../src/core/key-id-schema.ts";
import { modelsConfigSchema } from "../src/core/model-config-schema.generated.ts";
import { createAllToolDefinitions } from "../src/core/tools/index.ts";
import * as Generated from "../src/core/tools/tool-schemas.generated.ts";
import * as Built from "../src/core/tools/tool-schemas.ts";
import { createCodemodeToolDefinition } from "../src/extensions/codemode/tool.ts";
import { createToolSearchToolDefinition } from "../src/extensions/tool-search/tool.ts";

/**
 * Everything an object graph exposes: each object's own keys in order with their attributes, its prototype and
 * whether it is extensible, and which objects are the same object.
 */
function describeGraph(root: unknown): unknown {
	const ids = new Map<object, number>();
	const visit = (value: unknown): unknown => {
		if (typeof value !== "object" || value === null) return { value };
		const id = ids.get(value);
		if (id !== undefined) return { sameAs: id };
		ids.set(value, ids.size);
		return {
			array: Array.isArray(value),
			plain: Object.getPrototypeOf(value) === (Array.isArray(value) ? Array.prototype : Object.prototype),
			extensible: Object.isExtensible(value),
			keys: Reflect.ownKeys(value).map((key) => {
				const { value: child, ...attributes } = Object.getOwnPropertyDescriptor(value, key) ?? {};
				return { key: String(key), attributes, child: visit(child) };
			}),
		};
	};
	return visit(root);
}

describe("generated start schemas", () => {
	it("are what scripts/generate-start-schemas.ts generates (else run npm run generate:start-schemas)", () => {
		for (const [path, expected] of renderStartSchemaModules()) {
			expect(readFileSync(new URL(`../${path}`, import.meta.url), "utf-8"), path).toBe(expected);
		}
	});

	it("do not depend on what TypeBox built before in the process: rendered again, and after another schema compiled", () => {
		const files = new Map(
			[...renderStartSchemaModules().keys()].map((path) => [
				path,
				readFileSync(new URL(`../${path}`, import.meta.url), "utf-8"),
			]),
		);
		const expectCurrent = () => {
			for (const [path, content] of renderStartSchemaModules()) expect(content, path).toBe(files.get(path));
		};
		expectCurrent();
		expectCurrent();
		const unrelated = Compile(Type.Object({ a: Type.String({ minLength: 2 }), b: Type.Array(Type.Number()) }));
		expect(unrelated.Check({ a: "xy", b: [1] })).toBe(true);
		expectCurrent();
	});

	it("build every tool schema as the object TypeBox builds, markers included", () => {
		const generated = new Map<string, unknown>(Object.entries(Generated));
		expect([...generated.keys()].sort()).toEqual(Object.keys(Built).sort());
		for (const [name, built] of Object.entries(Built)) {
			expect(JSON.stringify(generated.get(name)), name).toBe(JSON.stringify(built));
			expect(describeGraph(generated.get(name)), name).toEqual(describeGraph(built));
		}
	});

	it("give the built-in tools the parameters and output schemas they had", () => {
		const definitions = createAllToolDefinitions(process.cwd());
		const expected: Record<string, { parameters: TSchema; outputSchema?: TSchema }> = {
			read: { parameters: Built.readSchema, outputSchema: Built.readOutputSchema },
			bash: { parameters: Built.bashSchema, outputSchema: Built.bashOutputSchema },
			powershell: { parameters: Built.bashSchema, outputSchema: Built.bashOutputSchema },
			edit: { parameters: Built.editSchema },
			write: { parameters: Built.writeSchema },
			grep: { parameters: Built.grepSchema },
			find: { parameters: Built.findSchema },
			ls: { parameters: Built.lsSchema },
		};
		expect(Object.keys(definitions).sort()).toEqual(Object.keys(expected).sort());
		const tools = [...Object.values(definitions), createCodemodeToolDefinition(), createToolSearchToolDefinition()];
		expected.codemode = { parameters: Built.codemodeSchema };
		expected.tool_search = { parameters: Built.toolSearchSchema };
		for (const tool of tools) {
			const { parameters, outputSchema } = expected[tool.name];
			// What a provider is sent, and what an extension reading the schema sees.
			expect(JSON.stringify(tool.parameters), tool.name).toBe(JSON.stringify(parameters));
			expect(describeGraph(tool.parameters), tool.name).toEqual(describeGraph(parameters));
			expect(JSON.stringify(tool.outputSchema), tool.name).toBe(JSON.stringify(outputSchema));
			expect(describeGraph(tool.outputSchema), tool.name).toEqual(describeGraph(outputSchema));
		}
	});

	it("work with TypeBox's type functions as TypeBox's own objects do", () => {
		const derived = (schemas: typeof Built) => [
			Type.Partial(schemas.grepSchema),
			Type.Required(schemas.readSchema),
			Type.Object({ ...schemas.lsSchema.properties, extra: Type.String() }),
			Type.Omit(schemas.bashSchema, ["timeout"]),
		];
		expect(describeGraph(derived(Generated))).toEqual(describeGraph(derived(Built)));
	});
});

/** Values around the models.json schema: valid documents, and each changed in one place. */
function modelsDocuments(): unknown[] {
	const model = { id: "m", name: "M", contextWindow: 1000, maxTokens: 100, input: ["text", "image"], reasoning: true };
	const provider = { baseUrl: "https://x.test/v1", api: "openai-completions", apiKey: "k", models: [model] };
	const valid = [
		{ providers: {} },
		{ $schema: "https://pi.dev/schemas/models.schema.json", providers: { a: provider } },
		{ providers: { a: { ...provider, headers: { "x-a": "b" }, compat: { supportsDeveloperRole: false } } } },
		{ providers: { a: { modelOverrides: { m: { name: "N" } } } } },
		{
			providers: {
				a: { ...provider, models: [{ ...model, cost: { input: 1, output: 2, cacheRead: 0, cacheWrite: 0 } }] },
			},
		},
	];
	const replacements: unknown[] = [undefined, null, "", "x", 0, -1, 1.5, true, [], {}, ["text"], { a: 1 }];
	const documents: unknown[] = [null, [], "{}", 0, {}, { providers: [] }, { providers: null }, ...valid];
	// Every place in every valid document, set to each replacement (undefined: the key removed).
	const variants = (value: unknown, set: (next: unknown) => unknown): void => {
		if (typeof value !== "object" || value === null) return;
		for (const key of Object.keys(value)) {
			const child = (value as Record<string, unknown>)[key];
			const at = (next: unknown) => {
				const copy: Record<string, unknown> | unknown[] = Array.isArray(value) ? [...value] : { ...value };
				if (next === undefined && !Array.isArray(copy)) delete copy[key];
				else (copy as Record<string, unknown>)[key] = next;
				return set(copy);
			};
			for (const replacement of replacements) documents.push(at(replacement));
			variants(child, at);
		}
	};
	for (const document of valid) variants(document, (next) => next);
	return documents;
}

describe("generated config checks", () => {
	it("decide every models.json document as TypeBox's Value.Check does", () => {
		const documents = modelsDocuments();
		expect(documents.length).toBeGreaterThan(500);
		let accepted = 0;
		for (const document of documents) {
			const expected = Value.Check(modelsConfigSchema(), document);
			if (expected) accepted++;
			expect(checkModelsConfig(document), JSON.stringify(document)).toBe(expected);
		}
		// Both outcomes are covered.
		expect(accepted).toBeGreaterThan(10);
		expect(accepted).toBeLessThan(documents.length - 100);
	});

	it("decide every keybindings.json value as TypeBox's Value.Check does", () => {
		const values: unknown[] = [
			"ctrl+c",
			"shift+ctrl+p",
			"ctrl+ctrl+c",
			"alt+enter",
			"super+shift+alt+ctrl+x",
			"ctrl+shift+alt+super+ctrl+x",
			"pageUp",
			"f12",
			"f13",
			"Ctrl+C",
			"",
			" ctrl+c",
			"ctrl+c\n",
			["ctrl+c", "alt+up"],
			["ctrl+c", "nope"],
			[],
			[["ctrl+c"]],
			null,
			1,
			{},
			true,
		];
		for (const value of values) {
			expect(checkKeybindingValue(value), JSON.stringify(value)).toBe(Value.Check(KeybindingValueSchema, value));
		}
	});
});
