import type { JsonValue } from "@earendil-works/chord";

type JsonContainer = Record<string, JsonValue> | JsonValue[];

/**
 * Assign `value` at `target[key]` leaf by leaf. Chord records a container assignment as one full set and only emits an
 * append when a string leaf is reassigned with a longer string, so writing the partial whole would store and publish the
 * complete message on every flush.
 */
export function assignJson(target: JsonContainer, key: string | number, value: JsonValue): void {
	const slots = target as Record<string | number, JsonValue>;
	// Own properties only: a key of parsed JSON is data, so "__proto__" or "toString" must not reach what objects inherit.
	const current = Object.hasOwn(slots, key) ? slots[key] : undefined;
	if (isRecord(current) && isRecord(value)) {
		for (const name of Object.keys(current)) if (!Object.hasOwn(value, name)) delete current[name];
		for (const [name, child] of Object.entries(value)) assignJson(current, name, child);
		return;
	}
	if (Array.isArray(current) && Array.isArray(value) && current.length <= value.length) {
		const items = current as JsonValue[];
		for (let index = 0; index < value.length; index++) {
			if (index < items.length) assignJson(items, index, value[index]!);
			else items.push(value[index]!);
		}
		return;
	}
	if (current === value) return;
	if (key === "__proto__") {
		// (Assigning it would set the prototype; JSON.parse() makes it an ordinary property, and so does this.)
		Object.defineProperty(slots, key, { value, writable: true, enumerable: true, configurable: true });
	} else {
		slots[key] = value;
	}
}

function isRecord(value: JsonValue | undefined): value is Record<string, JsonValue> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}
