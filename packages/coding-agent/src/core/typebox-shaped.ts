/**
 * Schema objects shaped as TypeBox builds them, for the schemas generated at build time (scripts/generate-start-schemas.ts)
 * so that a start does not evaluate TypeBox. TypeBox's Create() makes a schema object of its JSON Schema keywords, then
 * adds its markers ("~kind", "~optional", "~unsafe") as non-enumerable properties after them. JSON.stringify, spreads and
 * validators see only the keywords; TypeBox's functions that an extension may apply to a built-in schema (Type.Partial,
 * Type.Object of its properties, Value.Check, ...) also read the markers. This builds the same object.
 */
export function typeBoxShaped<T>(keywords: object, markers: Readonly<Record<string, string | boolean | null>>): T {
	for (const [key, value] of Object.entries(markers)) {
		Object.defineProperty(keywords, key, { configurable: true, writable: true, enumerable: false, value });
	}
	// The generated module states the TypeBox type of the object it built (it is one of the schemas TypeBox would build).
	return keywords as T;
}
