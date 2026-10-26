/**
 * Pi-Bolt: Pi's modules for the modules a compiled-in extension loads from disk as it runs.
 *
 * Loaded from its files, an extension is loaded by jiti, which gives every module it loads Pi's own modules by the names
 * Pi gives them (VIRTUAL_MODULES): an extension that loads a module of its own with `await import(path)`, whose code
 * imports `typebox` or `@earendil-works/pi-coding-agent`, gets Pi's. Compiled in, the extension's code is Pi-Bolt's own,
 * and such a module is loaded by the runtime, which finds no package of that name beside it (#6 of the loader-parity
 * review: "Cannot find package 'typebox'"). So, once Pi uses a compiled-in extension, the runtime is given those names too.
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

/**
 * Gives the runtime Pi's modules (`load` loads them, with the names `names` among them) by those names, once. `names`
 * may include a name whose module throws when it is read (loader.ts, JIT_ONLY_PACKAGES): importing it then fails.
 */
export function provideModulesToFiles(names: readonly string[], load: () => Promise<Record<string, unknown>>): void {
	const bun = (globalThis as { Bun?: BunPlugins }).Bun;
	if (provided || !bun) return;
	provided = true;
	let modules: Record<string, unknown> | undefined;
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
						: load().then((loaded) => {
								modules = loaded;
								return result(name);
							}),
				);
			}
		},
	});
}
