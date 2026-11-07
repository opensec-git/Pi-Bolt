// jiti's Babel transform, which jiti/static imports so that Bun embeds it in a compiled binary. Evaluating it is most of
// what loading an extension costs when jiti's cache already has the extension transformed: jiti-bun-loader.ts requires
// this module only when jiti has a file to transform.
import type { TransformOptions, TransformResult } from "jiti";
import { createJiti } from "jiti/static";

/** The transform jiti/static gives its instances, read from one made without a cache of its own. */
export const babelTransform = createJiti(import.meta.url, { fsCache: false, moduleCache: false }).options.transform as (
	opts: TransformOptions,
) => TransformResult;
