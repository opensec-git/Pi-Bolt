import { describe, expect, it } from "vitest";
import { resolveModelScopeFromModels } from "../src/core/model-resolver.ts";

describe("model scope with an empty catalog", () => {
	it("reports an unmatched glob even if the glob library could not compile it", () => {
		const pattern = "*".repeat(65_537);
		const result = resolveModelScopeFromModels([pattern], []);
		expect(result.scopedModels).toEqual([]);
		expect(result.diagnostics.map(({ code }) => code)).toEqual(["no-match"]);
	});
});
