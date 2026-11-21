import { afterEach, expect, it, vi } from "vitest";
import * as memory from "../src/core/idle-gc.ts";
import { createEditTool } from "../src/core/tools/edit.ts";

afterEach(() => vi.restoreAllMocks());

it("reclaims growth across large edits and resets the floor after collection", async () => {
	const mib = 1024 * 1024;
	const large = Buffer.from("old\n" + "x".repeat(mib));
	const small = Buffer.from("old\n");
	const writes = vi.fn(async () => {});
	const collect = vi.fn();
	vi.spyOn(memory, "runtimeFullCollection").mockReturnValue(collect);
	const size = vi.spyOn(memory, "processHeapBytes");
	const tool = createEditTool(process.cwd(), {
		operations: {
			access: async () => {},
			readFile: async (path) => (path.endsWith("small") ? small : large),
			writeFile: writes,
		},
	});
	const edit = (path: string) => tool.execute("test", { path, edits: [{ oldText: "old", newText: "new" }] });

	await edit("small");
	expect(size).not.toHaveBeenCalled();
	// Natural collections can lower the floor before a later burst.
	for (const usage of [100, 40, 120]) {
		size.mockReturnValue(usage * mib);
		await edit("large");
	}
	expect(collect).not.toHaveBeenCalled();
	size.mockReturnValueOnce(180 * mib).mockReturnValue(70 * mib);
	await edit("large");
	expect(collect).toHaveBeenCalledTimes(1);
	size.mockReturnValue(180 * mib);
	await edit("large");
	expect(collect).toHaveBeenCalledTimes(1);
	expect(writes).toHaveBeenCalledTimes(6);
});
