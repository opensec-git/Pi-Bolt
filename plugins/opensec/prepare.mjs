// Before building with plugins.ts (after `npm ci --ignore-scripts` here): each package's core, dist-bun/core.cjs, as plain CommonJS
// in vendor/. The packages ship it as Bun's own output for a file it has wrapped already (`// @bun @bun-cjs` and
// `(function(exports, require, module, __filename, __dirname) {...})`), which Bun runs as it is but its bundler takes for text to
// keep, and the executable's bytecode cannot be made with it inside. The body is the module itself: only the wrapper is taken off.
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const HEAD = "(function(exports, require, module, __filename, __dirname) {";
mkdirSync(join(here, "vendor"), { recursive: true });
for (const name of ["opensec-pi-subagents", "opensec-pi-todo"]) {
	const source = readFileSync(join(here, "node_modules", name, "dist-bun", "core.cjs"), "utf8").replace(/\r\n/g, "\n");
	const lines = source.split("\n");
	while (lines.length && lines.at(-1) === "") lines.pop();
	if (!lines[0].startsWith("// @bun") || !lines[1].startsWith(HEAD) || lines.at(-1) !== "})") {
		throw new Error(`${name}: dist-bun/core.cjs is not the wrapped Bun output this unwraps (a new version of the package?)`);
	}
	const body = [lines[1].slice(HEAD.length), ...lines.slice(2, -1)].join("\n");
	writeFileSync(join(here, "vendor", `${name}.core.cjs`), `// ${name}'s dist-bun/core.cjs without Bun's wrapper (prepare.mjs).\n${body}\n`);
	console.log(`vendor/${name}.core.cjs`);
}
