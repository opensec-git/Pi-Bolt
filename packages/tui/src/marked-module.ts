// marked and marked-cjk-friendly, evaluated at the first Markdown lex rather than when pi-tui loads: a start that renders no
// Markdown before its first frame (`pi -p` in text mode, the interactive mode until a message arrives) does not evaluate
// them. Bundlers (Bun for Pi-Bolt's executable, esbuild for the Node bundle) inline the literal require(), which then
// evaluates the package at that call; without a bundler it resolves from node_modules.
import { createRequire } from "node:module";
import type * as MarkedModule from "marked";
import type MarkedCjkFriendly from "marked-cjk-friendly";

/** Defined (true) by the esbuild build of the Node bundle (scripts/build-coding-agent-bundle.mjs). */
declare const PI_BUNDLED_NODE: boolean;

/**
 * Whether require() is there to call: Bun has it in an ES module (bundled or not), the Node bundle defines it. (Not
 * `typeof require`: esbuild would add a require shim of its own for that.)
 */
const hasRequire = !!process.versions.bun || (typeof PI_BUNDLED_NODE !== "undefined" && PI_BUNDLED_NODE);

let markedModule: typeof MarkedModule | undefined;

/** The marked package, evaluated at the first call. */
export function marked(): typeof MarkedModule {
	markedModule ??= (hasRequire ? require("marked") : createRequire(import.meta.url)("marked")) as typeof MarkedModule;
	return markedModule;
}

let markedCjkFriendlyFactory: typeof MarkedCjkFriendly | undefined;

/** marked-cjk-friendly's extension (its default export, called), the package evaluated at the first call. */
export function markedCjkFriendly(): ReturnType<typeof MarkedCjkFriendly> {
	markedCjkFriendlyFactory ??= (
		(hasRequire ? require("marked-cjk-friendly") : createRequire(import.meta.url)("marked-cjk-friendly")) as {
			default: typeof MarkedCjkFriendly;
		}
	).default;
	return markedCjkFriendlyFactory();
}

/** An instance of marked's Marked class. */
export type Marked = MarkedModule.Marked;

/**
 * marked's Marked class, as pi-tui exports it: constructing it, extending it, reading, writing or listing its properties
 * evaluates marked then and forwards to the class itself. (An ES module cannot export a getter; the placeholder is a plain
 * function because a class's `prototype` could not be forwarded.) Not forwarded: identity (`Marked` is not marked's class,
 * so `new Marked().constructor !== Marked`).
 */
export const Marked = new Proxy(function Marked() {}, {
	apply: (_target, thisArg, args) => Reflect.apply(marked().Marked, thisArg, args),
	construct: (_target, args, newTarget) =>
		Reflect.construct(marked().Marked, args, newTarget === Marked ? marked().Marked : newTarget),
	get: (_target, key) => Reflect.get(marked().Marked, key),
	set: (_target, key, value) => Reflect.set(marked().Marked, key, value),
	has: (_target, key) => Reflect.has(marked().Marked, key),
	deleteProperty: (_target, key) => Reflect.deleteProperty(marked().Marked, key),
	defineProperty: (_target, key, descriptor) => Reflect.defineProperty(marked().Marked, key, descriptor),
	ownKeys: () => Reflect.ownKeys(marked().Marked),
	getOwnPropertyDescriptor: (_target, key) => {
		const descriptor = Reflect.getOwnPropertyDescriptor(marked().Marked, key);
		// The placeholder's `prototype` cannot be configured and can be written, so a proxy may report it only as
		// writable (the class's own cannot be written).
		return key === "prototype" && descriptor !== undefined ? { ...descriptor, writable: true } : descriptor;
	},
}) as unknown as typeof MarkedModule.Marked;
