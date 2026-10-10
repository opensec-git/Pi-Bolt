import { expect, test } from "vitest";
import { assignJson } from "../src/harness/json.ts";

test("keys are data", () => {
	const target: Record<string, unknown> = {};
	const value = JSON.parse('{"constructor":"c","prototype":{"a":1},"__proto__":{"polluted":true},"toString":"t"}');
	for (const [k, v] of Object.entries(value)) assignJson(target as never, k, v as never);
	expect(target.constructor).toBe("c");
	expect(target.prototype).toEqual({ a: 1 });
	expect(Object.getOwnPropertyDescriptor(target, "__proto__")?.value).toEqual({ polluted: true });
	expect(Object.getPrototypeOf(target)).toBe(Object.prototype);
	expect(({} as Record<string, unknown>).polluted).toBeUndefined();
	expect(target.toString).toBe("t");
	// Assigned again: merged as own data, the prototype untouched.
	assignJson(target as never, "__proto__", { polluted: false, more: 1 } as never);
	expect(Object.getOwnPropertyDescriptor(target, "__proto__")?.value).toEqual({ polluted: false, more: 1 });
	expect(({} as Record<string, unknown>).polluted).toBeUndefined();
});
