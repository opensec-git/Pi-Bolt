import { describe, expect, it } from "vitest";
import { PI_MODULE_NAMES } from "../src/core/extensions/compiled-modules.ts";
import { VIRTUAL_MODULES } from "../src/core/extensions/virtual-modules.ts";

// Loader-parity review #6: a module a compiled-in extension loads from disk gets Pi's modules by the names jiti gives them.
describe("Pi's modules for what a compiled-in extension loads from disk", () => {
	it("are registered by every name Pi gives extensions, and no other", () => {
		expect([...PI_MODULE_NAMES].sort()).toEqual(Object.keys(VIRTUAL_MODULES).sort());
	});
});
