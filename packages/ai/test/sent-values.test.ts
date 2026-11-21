import { describe, expect, it } from "vitest";
import { beginRequestCache, SentValues } from "../src/utils/request-cache.ts";

describe("encoded provider message positions", () => {
	it("skips one replaced value within or between nonempty units and keeps independent retry cursors", () => {
		const source = [{}, {}, {}, {}];
		const items = [{}, {}, {}, {}];
		const groups = [[items[0], items[1]], [], [items[2]], [items[3]]];
		const options = { immutableMessages: true, sessionId: "sent-values-test" };
		const build = () => {
			const run = beginRequestCache("unit-order", options, [])!;
			for (const [i, group] of groups.entries()) run.unit([source[i]], undefined, [], (out) => out.push(...group));
			run.end();
			return run;
		};
		build();
		const run = build();
		const initial = run.sentValues();
		const retry = run.sentValues();
		const bytes = items.map((_, i) => new Blob([String(i)]));
		for (const [i, item] of items.entries()) {
			expect(initial.bytes(item)).toBeNull();
			initial.keep(bytes[i]);
		}
		expect(retry.bytes({})).toBeUndefined();
		expect(retry.bytes(items[1])).toBe(bytes[1]);
		expect(retry.bytes(items[3])).toBe(bytes[3]);
		expect(retry.bytes(items[0])).toBeUndefined();
		const another = run.sentValues();
		expect(another.bytes(items[3])).toBeUndefined();
		expect(another.bytes(items[0])).toBe(bytes[0]);
		expect(another.bytes(items[2])).toBe(bytes[2]);
	});

	it("honors explicit slot mappings from callers of the public constructor", () => {
		const first = {};
		const second = {};
		const entry = { sources: [], extra: undefined, meta: undefined, items: [first, second], bytes: undefined };
		const mapped = new SentValues([second, first], [entry, entry], [1, 0]);
		const part = new Blob(["second"]);
		expect(mapped.bytes(second)).toBeNull();
		mapped.keep(part);
		const aligned = new SentValues([first, second], [entry, entry], [0, 1]);
		expect(aligned.bytes(second)).toBe(part);
	});
});
