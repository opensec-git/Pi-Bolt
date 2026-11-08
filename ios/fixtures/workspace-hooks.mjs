// Resolves the workspace packages (`@earendil-works/*`) to their TypeScript sources instead of `dist/`, as the
// vitest configs do with aliases, so `node --import ./workspace-hooks.mjs rc-host.ts` runs Pi-Bolt's remote control
// server from a checkout after `npm ci`, without a build. Each package's `exports` entry is mapped from
// `./dist/<path>.js` to `./src/<path>.ts` (`main` for a package without `exports`).
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { registerHooks } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const packages = new Map();
for (const dir of readdirSync(join(root, "packages"))) {
	const manifest = join(root, "packages", dir, "package.json");
	if (!existsSync(manifest)) continue;
	const json = JSON.parse(readFileSync(manifest, "utf8"));
	// A package without `exports` has only its `main`.
	const exports =
		typeof json.exports === "object" && json.exports !== null ? json.exports : { ".": json.exports ?? json.main };
	packages.set(json.name, { dir: join(root, "packages", dir), exports });
}

function exportTarget(entry) {
	if (typeof entry === "string") return entry;
	return entry?.import ?? entry?.default;
}

function sourceFor(specifier) {
	const match = /^(@earendil-works\/[^/]+)(\/.*)?$/.exec(specifier);
	const pkg = match && packages.get(match[1]);
	if (!pkg) return undefined;
	const subpath = `.${match[2] ?? ""}`;
	let target = exportTarget(pkg.exports[subpath]);
	if (!target) {
		// A pattern export: "./providers/*" -> "./dist/providers/*.js".
		for (const [key, value] of Object.entries(pkg.exports)) {
			if (!key.endsWith("*") || !subpath.startsWith(key.slice(0, -1))) continue;
			const pattern = exportTarget(value);
			if (pattern) target = pattern.replaceAll("*", subpath.slice(key.length - 1));
		}
	}
	if (!target) return undefined;
	const source = join(pkg.dir, target.replace(/^(\.\/)?dist\//, "src/").replace(/\.js$/, ".ts"));
	return existsSync(source) ? pathToFileURL(source).href : undefined;
}

registerHooks({
	resolve(specifier, context, nextResolve) {
		const url = sourceFor(specifier);
		return url ? { url, shortCircuit: true, format: "module-typescript" } : nextResolve(specifier, context);
	},
});
