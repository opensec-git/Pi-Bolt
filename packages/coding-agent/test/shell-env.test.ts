import { delimiter } from "node:path";
import { describe, expect, it } from "vitest";
import { getBinDir } from "../src/config.ts";
import { getShellEnv } from "../src/utils/shell.ts";

describe("getShellEnv", () => {
	it("takes a fresh snapshot and prefixes the first case-insensitive path entry", () => {
		const original = process.env;
		try {
			process.env = { Path: "first", PATH: "second", LIVE_VALUE: "one" };
			const bin = getBinDir();
			const first = getShellEnv();
			expect(first.Path).toBe([bin, "first"].join(delimiter));
			expect(first.PATH).toBe("second");
			first.LIVE_VALUE = "child";
			expect(process.env.LIVE_VALUE).toBe("one");
			expect(process.env.Path).toBe("first");

			process.env.LIVE_VALUE = "two";
			process.env.Path = [bin, "third"].join(delimiter);
			const second = getShellEnv();
			expect(second.LIVE_VALUE).toBe("two");
			expect(second.Path).toBe(process.env.Path);

			delete process.env.Path;
			delete process.env.PATH;
			expect(getShellEnv().PATH).toBe(bin);
		} finally {
			process.env = original;
		}
	});
});
