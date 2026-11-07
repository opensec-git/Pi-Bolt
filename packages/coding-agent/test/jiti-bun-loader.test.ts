import { describe, expect, it, vi } from "vitest";

const state = vi.hoisted(() => ({
	babelModuleLoads: 0,
	createJiti: vi.fn((_id: string, _options: Record<string, unknown>) => ({})),
}));

vi.mock("jiti", () => ({ createJiti: state.createJiti }));

// jiti/static embeds the Babel transform: the Bun loader must not load it until jiti has a file to transform.
vi.mock("jiti/static", () => {
	state.babelModuleLoads++;
	return { createJiti: () => ({ options: { transform: () => ({ code: "" }) } }) };
});

import { createJiti } from "../src/core/extensions/jiti-bun-loader.ts";

describe("jiti for compiled Bun binaries", () => {
	it("gives jiti a transform of its own and loads no Babel to make an instance", () => {
		createJiti("file:///loader.js", { moduleCache: false, tryNative: false });

		expect(state.babelModuleLoads).toBe(0);
		const options = state.createJiti.mock.calls[0][1];
		expect(options).toMatchObject({ moduleCache: false, tryNative: false });
		expect(typeof options.transform).toBe("function");
	});

	it("keeps a transform the caller passes", () => {
		const transform = () => ({ code: "" });
		createJiti("file:///loader.js", { transform });

		expect(state.createJiti.mock.calls[1][1].transform).toBe(transform);
		expect(state.babelModuleLoads).toBe(0);
	});
});
