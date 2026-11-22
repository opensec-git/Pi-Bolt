import { describe, expect, it, vi } from "vitest";

// The standalone executable's loader path (isBunBinary), with jiti and Pi's modules stubbed out.
const state = vi.hoisted(() => ({
	provide: vi.fn(),
	jitiImport: vi.fn(async () => () => {}),
}));

vi.mock("../src/config.ts", async (importOriginal) => ({
	...(await importOriginal<Record<string, unknown>>()),
	isBunBinary: true,
}));

vi.mock("../src/core/extensions/jiti-bun-loader.lazy.ts", () => ({
	loadJitiBunLoader: async () => ({ createJiti: () => ({ import: state.jitiImport }) }),
}));

vi.mock("../src/core/extensions/virtual-modules.ts", () => ({ VIRTUAL_MODULES: {} }));

vi.mock("../src/core/extensions/compiled-modules.ts", async (importOriginal) => ({
	...(await importOriginal<Record<string, unknown>>()),
	provideModulesToFiles: state.provide,
}));

import { PI_PACKAGE_NAMES } from "../src/core/extensions/compiled-modules.ts";
import { loadExtensions } from "../src/core/extensions/loader.ts";

describe("an extension loaded from its files in the standalone executable", () => {
	// #331: the runtime is given Pi's own packages, not typebox, before jiti imports the extension.
	it("gives the runtime Pi's own packages only, with Pi's modules, before the extension is imported", async () => {
		const result = await loadExtensions(["/extension.ts"], "/");

		expect(result.errors).toEqual([]);
		expect(state.provide).toHaveBeenCalledOnce();
		const [names, load, loaded] = state.provide.mock.calls[0] as unknown[];
		expect(names).toBe(PI_PACKAGE_NAMES);
		expect(typeof load).toBe("function");
		expect(loaded).toEqual({});
		expect(state.provide.mock.invocationCallOrder[0]).toBeLessThan(state.jitiImport.mock.invocationCallOrder[0]);
	});
});
