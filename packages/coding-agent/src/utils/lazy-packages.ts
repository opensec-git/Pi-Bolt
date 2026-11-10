// npm packages loaded at their first use rather than with Pi's modules: a start that compares no versions, parses no git
// source and prints nothing in color does not evaluate them. Bundlers (Bun for the binary, esbuild for the Node bundle)
// inline the literal require(), which evaluates the package at that call; elsewhere it resolves from node_modules.
import { createRequire } from "node:module";
import type { ChalkInstance } from "chalk";
import type HostedGitInfo from "hosted-git-info";
import type * as Semver from "semver";
import { isBunBinary, isBundledNode } from "../config.ts";

let semverModule: typeof Semver | undefined;

/** The semver package, evaluated at the first call. */
export function semver(): typeof Semver {
	semverModule ??= (
		isBunBinary || isBundledNode ? require("semver") : createRequire(import.meta.url)("semver")
	) as typeof Semver;
	return semverModule;
}

let hostedGitInfoModule: typeof HostedGitInfo | undefined;

/** The hosted-git-info package, evaluated at the first call. */
export function hostedGitInfo(): typeof HostedGitInfo {
	hostedGitInfoModule ??= (
		isBunBinary || isBundledNode ? require("hosted-git-info") : createRequire(import.meta.url)("hosted-git-info")
	) as typeof HostedGitInfo;
	return hostedGitInfoModule;
}

let chalkInstance: ChalkInstance | undefined;

/**
 * chalk's default instance, for `import { chalk }` where `import chalk from "chalk"` was: the package is evaluated (and the
 * terminal's color support detected) at the first use of a style.
 */
export const chalk = new Proxy(function chalk() {}, {
	apply: (_target, thisArg, args) => Reflect.apply(loadChalk(), thisArg, args),
	get: (_target, key) => Reflect.get(loadChalk(), key),
	set: (_target, key, value) => Reflect.set(loadChalk(), key, value),
	has: (_target, key) => Reflect.has(loadChalk(), key),
}) as unknown as ChalkInstance;

/** chalk's default instance, the package evaluated at the first call: for code that styles often (no proxy in between). */
export function loadChalk(): ChalkInstance {
	chalkInstance ??= (
		(isBunBinary || isBundledNode ? require("chalk") : createRequire(import.meta.url)("chalk")) as {
			default: ChalkInstance;
		}
	).default;
	return chalkInstance;
}
