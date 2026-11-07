// Compiled Bun binaries: jiti with the Babel transform that jiti/static embeds (jiti-bun-babel.ts), evaluated only when
// jiti has a file to transform. A start whose extensions are all in jiti's cache never evaluates Babel.
import {
	createJiti as createJitiCore,
	type Jiti,
	type JitiOptions,
	type TransformOptions,
	type TransformResult,
} from "jiti";
import type * as JitiBunBabel from "./jiti-bun-babel.ts";

let babelTransform: ((opts: TransformOptions) => TransformResult) | undefined;

function transform(opts: TransformOptions): TransformResult {
	// A require, not an import: Bun's bundler then evaluates jiti-bun-babel.ts, and Babel with it, here, at the first call.
	// (.js: TypeScript keeps a require's path as written, and the binary is built from the compiled files.)
	babelTransform ??= (require("./jiti-bun-babel.js") as typeof JitiBunBabel).babelTransform;
	return babelTransform(opts);
}

export function createJiti(id: string, opts: JitiOptions = {}): Jiti {
	return createJitiCore(id, opts.transform ? opts : { ...opts, transform });
}
