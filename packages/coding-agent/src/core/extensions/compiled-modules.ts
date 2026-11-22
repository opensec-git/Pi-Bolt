/**
 * Pi-Bolt: Pi's modules for the modules an extension's code loads from disk without jiti.
 *
 * jiti gives every module it transforms Pi's own modules by the names Pi gives them (VIRTUAL_MODULES): a module of an
 * extension that imports `typebox` or `@earendil-works/pi-coding-agent` gets Pi's. Not every module goes through jiti:
 * - Compiled in, the extension's code is Pi-Bolt's own, and a module it loads with `await import(path)` is loaded by the
 *   runtime, which finds no package of that name beside it (#6 of the loader-parity review: "Cannot find package 'typebox'").
 * - Loaded from its files, jiti hands a module that is JavaScript as it is to the runtime: an ES module (`.mjs`, or `.js` in a
 *   package of `"type": "module"`) that is imported, and a `.cjs` (or CommonJS `.js`) that is required. The runtime resolves
 *   its imports as Node does, and finds the copy of Pi's package that npm or bun installed beside the extension, if there is
 *   one: a second Pi, of another version, for that module and everything it imports (#331: an extension's `dist/*.js`, in
 *   node_modules, got pi-coding-agent 1.4.0 from disk under Pi 1.5.2).
 * So, in the standalone executable, the runtime is given those names too, once an extension is loaded. Its resolution takes
 * a name registered this way before a package on disk, for import(), static imports and require() alike; nothing else about
 * how it loads a module changes (no module is transformed for this, and no callback runs for any other name).
 *
 * Only the names are registered then: Pi's modules are loaded (`load`) when a module first imports one of them, so a start
 * costs nothing more. A require() of one, which cannot wait, gets them once they are loaded.
 */

/** Each name Pi gives its modules to extensions (virtual-modules.ts, VIRTUAL_MODULES; a test keeps the two the same). */
export const PI_MODULE_NAMES = [
	"typebox",
	"typebox/compile",
	"typebox/value",
	"@sinclair/typebox",
	"@sinclair/typebox/compile",
	"@sinclair/typebox/value",
	"@earendil-works/pi-agent-core",
	"@earendil-works/pi-tui",
	"@earendil-works/pi-ai",
	"@earendil-works/pi-ai/compat",
	"@earendil-works/pi-ai/models",
	"@earendil-works/pi-ai/oauth",
	"@earendil-works/pi-ai/providers/all",
	"@earendil-works/pi-coding-agent",
	"@mariozechner/pi-agent-core",
	"@mariozechner/pi-tui",
	"@mariozechner/pi-ai",
	"@mariozechner/pi-ai/compat",
	"@mariozechner/pi-ai/models",
	"@mariozechner/pi-ai/oauth",
	"@mariozechner/pi-ai/providers/all",
	"@mariozechner/pi-coding-agent",
];

interface ModuleResult {
	exports: Record<string, unknown>;
	loader: "object";
}

/** The part of Bun's runtime plugin API this uses. */
interface BunPlugins {
	plugin(plugin: {
		name: string;
		setup(build: { module(name: string, load: () => ModuleResult | Promise<ModuleResult>): void }): void;
	}): void;
}

let provided = false;
let modules: Record<string, unknown> | undefined;

/**
 * Gives the runtime Pi's modules (`load` loads them, with the names `names` among them) by those names, once. `names`
 * may include a name whose module throws when it is read (loader.ts, JIT_ONLY_PACKAGES): importing it then fails.
 * `loaded`, the modules when the caller has them already, lets a require() of one work before anything imported one.
 */
export function provideModulesToFiles(
	names: readonly string[],
	load: () => Promise<Record<string, unknown>>,
	loaded?: Record<string, unknown>,
): void {
	const bun = (globalThis as { Bun?: BunPlugins }).Bun;
	if (!bun) return;
	modules ??= loaded;
	if (provided) return;
	provided = true;
	const result = (name: string): ModuleResult => ({
		exports: modules![name] as Record<string, unknown>,
		loader: "object",
	});
	bun.plugin({
		name: "pi-bolt: Pi's modules",
		setup(build) {
			for (const name of names) {
				build.module(name, () =>
					modules
						? result(name)
						: load().then((loadedNow) => {
								modules ??= loadedNow;
								return result(name);
							}),
				);
			}
		},
	});
}
