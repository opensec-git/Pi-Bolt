import { createRequire } from "node:module";
import type * as TypeBox from "typebox";
import type * as TypeBoxValue from "typebox/value";
import { isBunBinary, isBundledNode } from "../config.ts";

let typeBoxModule: typeof TypeBox | undefined;
let typeBoxValueModule: typeof TypeBoxValue | undefined;

/**
 * TypeBox, loaded on first use: evaluating it costs millions of instructions, which a start pays only for a user file that
 * needs it (a theme of the user's, an invalid models.json or keybindings.json). Bundlers inline the literal require();
 * elsewhere it resolves from node_modules.
 */
export function loadTypeBox(): typeof TypeBox {
	typeBoxModule ??= (
		isBunBinary || isBundledNode ? require("typebox") : createRequire(import.meta.url)("typebox")
	) as typeof TypeBox;
	return typeBoxModule;
}

/** TypeBox's `typebox/value`, loaded on first use (see loadTypeBox). */
export function loadTypeBoxValue(): typeof TypeBoxValue {
	typeBoxValueModule ??= (
		isBunBinary || isBundledNode ? require("typebox/value") : createRequire(import.meta.url)("typebox/value")
	) as typeof TypeBoxValue;
	return typeBoxValueModule;
}
