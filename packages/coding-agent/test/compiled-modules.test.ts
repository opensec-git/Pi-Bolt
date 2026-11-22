import { afterEach, describe, expect, it, vi } from "vitest";
import { PI_MODULE_NAMES, PI_PACKAGE_NAMES, provideModulesToFiles } from "../src/core/extensions/compiled-modules.ts";
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

	// #331: a library's own typebox stays its own; only Pi's own packages are given to every module.
	it("are Pi's own packages, by their names now and before, for an extension loaded from its files", () => {
		expect(PI_PACKAGE_NAMES).toContain("@earendil-works/pi-coding-agent");
		expect(PI_PACKAGE_NAMES).toContain("@mariozechner/pi-tui");
		expect(PI_PACKAGE_NAMES.filter((name) => name.includes("typebox"))).toEqual([]);
		expect(PI_PACKAGE_NAMES.every((name) => /^@(?:earendil-works|mariozechner)\/pi-/.test(name))).toBe(true);
	});

	// #331
	it("registers each name once, and gives a require() Pi's modules at once when the loader has them", async () => {
		const registered = new Map<string, () => unknown>();
		let plugins = 0;
		vi.stubGlobal("Bun", {
			plugin(plugin: { setup(build: { module(name: string, load: () => unknown): void }): void }) {
				plugins++;
				plugin.setup({ module: (name, load) => registered.set(name, load) });
			},
		});
		const modules = { "@earendil-works/pi-tui": { Container: "Pi's" }, typebox: { Type: "Pi's" } };
		const load = vi.fn(async () => modules);

		// Loaded from its files: Pi's own packages, with Pi's modules in hand.
		provideModulesToFiles(["@earendil-works/pi-tui"], load, modules);
		provideModulesToFiles(["@earendil-works/pi-tui"], load, modules);
		expect(plugins).toBe(1);
		// Compiled in: every name, the ones not given yet added.
		provideModulesToFiles(["@earendil-works/pi-tui", "typebox"], load);
		expect(plugins).toBe(2);
		expect([...registered.keys()]).toEqual(["@earendil-works/pi-tui", "typebox"]);

		// Not a promise: a require() cannot wait for one ("require() async module ... is unsupported").
		expect(registered.get("@earendil-works/pi-tui")?.()).toEqual({
			exports: modules["@earendil-works/pi-tui"],
			loader: "object",
		});
		expect(registered.get("typebox")?.()).toEqual({ exports: modules.typebox, loader: "object" });
		expect(load).not.toHaveBeenCalled();
	});
});
