/**
 * A copy of `entry`: the same prototype and fields, where a field that holds an array, a Map, a Set or a plain object holds a
 * copy of it (one level down: class instances, functions and strings are shared). For a transcript entry that compacted
 * (compact()): what is left of it was allocated while the transcript was rendered, among a great deal that died since, and
 * each of those small objects keeps a block of the heap from being given back. A copy made now is allocated with what lives,
 * and the entry it replaces dies with them.
 */
export function freshCopy<T extends object>(entry: T): T {
	const copy = Object.create(Object.getPrototypeOf(entry)) as Record<string, unknown>;
	for (const [key, value] of Object.entries(entry)) copy[key] = copyOf(value);
	return copy as T;
}

function copyOf(value: unknown): unknown {
	if (Array.isArray(value)) return value.slice();
	if (value instanceof Map) return new Map(value);
	if (value instanceof Set) return new Set(value);
	if (value !== null && typeof value === "object" && Object.getPrototypeOf(value) === Object.prototype) {
		return { ...value };
	}
	return value;
}
