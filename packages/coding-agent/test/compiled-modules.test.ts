import { afterEach, describe, expect, it, vi } from "vitest";
import { PI_MODULE_NAMES, PI_PACKAGE_NAMES, provideModulesToFiles } from "../src/core/extensions/compiled-modules.ts";
import { VIRTUAL_MODULES } from "../src/core/extensions/virtual-modules.ts";

// Loader-parity review #6: a module a compiled-in extension loads from disk gets Pi's modules by the names jiti gives them.
describe("Pi's modules for what a compiled-in extension loads from disk", () => {
	it("are registered by every name Pi gives extensions, and no other", () => {
		expect([...PI_MODULE_NAMES].sort()).toEqual(Object.keys(VIRTUAL_MODULES).sort());
	});
});

type Load = () => unknown;

/** A runtime of its own for each test (the tests run on Node, which has none): what it was given, by name. */
function stubRuntime(): { registered: Map<string, Load>; plugins: () => number } {
	const registered = new Map<string, Load>();
	let plugins = 0;
	vi.stubGlobal("Bun", {
		plugin(plugin: { setup(build: { module(name: string, load: Load): void }): void }) {
			plugins++;
			plugin.setup({ module: (name, load) => registered.set(name, load) });
		},
	});
	return { registered, plugins: () => plugins };
}

// #331: the runtime's plugin API, as provideModulesToFiles uses it.
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
	it("registers each name once, and gives a require() Pi's modules at once when the loader has them", () => {
		const runtime = stubRuntime();
		const modules = { "@earendil-works/pi-tui": { Container: "Pi's" }, typebox: { Type: "Pi's" } };
		const load = vi.fn(async () => modules);

		// Loaded from its files: Pi's own packages, with Pi's modules in hand.
		provideModulesToFiles(["@earendil-works/pi-tui"], load, modules);
		provideModulesToFiles(["@earendil-works/pi-tui"], load, modules);
		expect(runtime.plugins()).toBe(1);
		// Compiled in: every name, the ones not given yet added.
		provideModulesToFiles(["@earendil-works/pi-tui", "typebox"], load);
		expect(runtime.plugins()).toBe(2);
		expect([...runtime.registered.keys()]).toEqual(["@earendil-works/pi-tui", "typebox"]);

		// Not a promise: a require() cannot wait for one ("require() async module ... is unsupported").
		expect(runtime.registered.get("@earendil-works/pi-tui")?.()).toEqual({
			exports: modules["@earendil-works/pi-tui"],
			loader: "object",
		});
		expect(runtime.registered.get("typebox")?.()).toEqual({ exports: modules.typebox, loader: "object" });
		expect(load).not.toHaveBeenCalled();
	});

	// #331: compiled in, Pi's modules are loaded when a module first imports one, then given at once.
	it("loads Pi's modules on the first import when the caller does not have them", async () => {
		const runtime = stubRuntime();
		const modules = { "@earendil-works/pi-tui": { Container: "Pi's" } };
		const load = vi.fn(async () => modules);

		provideModulesToFiles(["@earendil-works/pi-tui"], load);
		expect(runtime.plugins()).toBe(1);
		const first = runtime.registered.get("@earendil-works/pi-tui")?.();
		expect(first).toBeInstanceOf(Promise);
		expect(await first).toEqual({ exports: modules["@earendil-works/pi-tui"], loader: "object" });
		expect(runtime.registered.get("@earendil-works/pi-tui")?.()).toEqual({
			exports: modules["@earendil-works/pi-tui"],
			loader: "object",
		});
		expect(load).toHaveBeenCalledOnce();
	});
});
