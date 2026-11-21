import { expect, it } from "vitest";
import { ShortHash, shortHash } from "../src/utils/hash.ts";

it("preserves existing short hashes across chunks, intermediate digests and copies", () => {
	const start = new ShortHash().update("a");
	const split = start.copy().update("\ud83d");
	split.digest();
	expect(split.update("\ude00b").digest()).toBe("12yrce3kjl8pw");
	expect(start.copy().update("b").digest()).toBe("m1bnh312fly6q");
	expect(start.update("\ud800b").digest()).toBe("1oe1ja218oa8u8");
	expect(shortHash("")).toBe("k4n83c7h0j2b");
});
