import { afterEach, describe, expect, it, vi } from "vitest";
import { PI_MODULE_NAMES, provideModulesToFiles } from "../src/core/extensions/compiled-modules.ts";
import { VIRTUAL_MODULES } from "../src/core/extensions/virtual-modules.ts";

// Loader-parity review #6: a module a compiled-in extension loads from disk gets Pi's modules by the names jiti gives them.
describe("Pi's modules for what a compiled-in extension loads from disk", () => {
	it("are registered by every name Pi gives extensions, and no other", () => {
		expect([...PI_MODULE_NAMES].sort()).toEqual(Object.keys(VIRTUAL_MODULES).sort());
	});
});

// #331: the runtime's plugin API, as provideModulesToFiles uses it (the tests run on Node, which has none).
describe("Pi's modules for what the runtime loads without jiti", () => {
	afterEach(() => {
		vi.unstubAllGlobals();
	});

	it("are registered once, and given to a require() at once when the loader has them", async () => {
		const registered = new Map<string, () => unknown>();
		let plugins = 0;
		vi.stubGlobal("Bun", {
			plugin(plugin: { setup(build: { module(name: string, load: () => unknown): void }): void }) {
				plugins++;
				plugin.setup({ module: (name, load) => registered.set(name, load) });
			},
		});
		const modules = { typebox: { Type: "Pi's" } };
		const load = vi.fn(async () => modules);

		// Compiled in: the names only, Pi's modules loaded when a module first imports one.
		provideModulesToFiles(["typebox"], load);
		// Loaded from its files: the loader has Pi's modules already.
		provideModulesToFiles(["typebox"], load, modules);

		expect(plugins).toBe(1);
		// Not a promise: a require() cannot wait for one ("require() async module ... is unsupported").
		expect(registered.get("typebox")?.()).toEqual({ exports: modules.typebox, loader: "object" });
		expect(load).not.toHaveBeenCalled();
	});
});
