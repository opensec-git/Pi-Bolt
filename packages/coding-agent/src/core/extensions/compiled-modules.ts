/**
 * Pi-Bolt: Pi's modules for the modules an extension's code loads from disk without jiti.
 *
 * jiti gives every module it transforms Pi's own modules by the names Pi gives them (VIRTUAL_MODULES): a module of an
 * extension that imports `typebox` or `@earendil-works/pi-coding-agent` gets Pi's. Not every module goes through jiti:
 * - Compiled in, the extension's code is Pi-Bolt's own, and a module it loads with `await import(path)` is loaded by the
 *   runtime, which finds no package of that name beside it (#6 of the loader-parity review: "Cannot find package 'typebox'").
 *   There, the runtime is given every one of those names (PI_MODULE_NAMES). The bundler resolved the extension's own
 *   imports when it was compiled in; these are for the modules it loads from disk as it runs, which have no jiti to fall
 *   back on (tests/pi/compile.py, loader parity #6, fails without typebox). The runtime has one set of names for the
 *   process: once a compiled-in extension has loaded, every module the runtime loads itself gets Pi's typebox, a library of
 *   an extension loaded from its files too (docs/PLUGINS.md).
 * - Loaded from its files, jiti hands a module that is JavaScript as it is to the runtime: an ES module (`.mjs`, or `.js` in a
 *   package of `"type": "module"`) that is imported, and a `.cjs` (or CommonJS `.js`) that is required. The runtime resolves
 *   its imports as Node does, and finds the copy of Pi's package that npm or bun installed beside the extension, if there is
 *   one: a second Pi, of another version, for that module and everything it imports (#331: an extension's `dist/*.js`, in
 *   node_modules, got pi-coding-agent 1.4.0 from disk under Pi 1.5.2). There, the runtime is given the names of Pi's own
 *   packages only (PI_PACKAGE_NAMES), for every module. `typebox` and `@sinclair/typebox` are packages of their own, which a
 *   library the extension uses may need in another version: such a module resolves them as on Pi on Node, from disk, and
 *   when there is none there, jiti loads it transformed, with Pi's. (The runtime cannot give a name to some modules and not
 *   others cheaply: its plugins' onResolve is not asked about package names, and onLoad would read every module.)
 * The runtime takes a name registered this way before a package on disk, for import(), static imports and require() alike;
 * nothing else about how it loads a module changes (no module is transformed for this, and no callback runs for any other
 * name).
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
	"@earendil-works/pi-ai/session-resources",
	"@earendil-works/pi-ai/bun-oauth",
	"@earendil-works/pi-ai/bedrock-provider",
	"@earendil-works/pi-coding-agent",
	"@earendil-works/pi-coding-agent/pi-ai-extension-view",
	"@mariozechner/pi-agent-core",
	"@mariozechner/pi-tui",
	"@mariozechner/pi-ai",
	"@mariozechner/pi-ai/compat",
	"@mariozechner/pi-ai/models",
	"@mariozechner/pi-ai/oauth",
	"@mariozechner/pi-ai/providers/all",
	"@mariozechner/pi-coding-agent",
];

/** The names of Pi's own packages among them: @earendil-works/pi-*, and @mariozechner/pi-*, their names before. */
export const PI_PACKAGE_NAMES = PI_MODULE_NAMES.filter((name) => /^@(?:earendil-works|mariozechner)\//.test(name));

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

/** What a runtime has been given: the names, and Pi's modules once they are loaded. */
interface Provided {
	names: Set<string>;
	modules?: Record<string, unknown>;
}

/** By runtime (its `Bun` object): the process has one. */
const providedTo = new WeakMap<BunPlugins, Provided>();

/**
 * Gives the runtime Pi's modules (`load` loads them, with the names `names` among them) by those names, each name once.
 * `names` may include a name whose module throws when it is read (loader.ts, JIT_ONLY_PACKAGES): importing it then fails.
 * `loaded`, the modules when the caller has them already, lets a require() of one work before anything imported one.
 */
export function provideModulesToFiles(
	names: readonly string[],
	load: () => Promise<Record<string, unknown>>,
	loaded?: Record<string, unknown>,
): void {
	const bun = (globalThis as { Bun?: BunPlugins }).Bun;
	if (!bun) return;
	let provided = providedTo.get(bun);
	if (!provided) {
		provided = { names: new Set() };
		providedTo.set(bun, provided);
	}
	const state = provided;
	state.modules ??= loaded;
	const added = names.filter((name) => !state.names.has(name));
	if (added.length === 0) return;
	for (const name of added) state.names.add(name);
	const result = (modules: Record<string, unknown>, name: string): ModuleResult => ({
		exports: modules[name] as Record<string, unknown>,
		loader: "object",
	});
	bun.plugin({
		name: "pi-bolt: Pi's modules",
		setup(build) {
			for (const name of added) {
				build.module(name, () =>
					state.modules
						? result(state.modules, name)
						: load().then((loadedNow) => {
								state.modules ??= loadedNow;
								return result(state.modules, name);
							}),
				);
			}
		},
	});
}
