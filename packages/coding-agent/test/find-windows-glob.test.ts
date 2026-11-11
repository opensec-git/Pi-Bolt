import { describe, expect, it } from "vitest";
import { windowsGlobSeparators } from "../src/core/tools/find.ts";

describe("windowsGlobSeparators", () => {
	it("takes \\ for the separator, except before what a name may have and a glob escapes", () => {
		expect(windowsGlobSeparators("src\\**\\*.ts")).toBe("src/**/*.ts");
		expect(windowsGlobSeparators("src\\\\a\\b.ts")).toBe("src/a/b.ts");
		expect(windowsGlobSeparators("src/\\[id\\]/*.ts")).toBe("src/\\[id\\]/*.ts");
		expect(windowsGlobSeparators("src/*.ts")).toBe("src/*.ts");
	});
});
