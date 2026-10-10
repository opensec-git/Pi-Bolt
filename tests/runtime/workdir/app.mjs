// A compiled executable started in a directory that supplies packages and files: what its embedded code resolves.
import { createRequire } from "node:module";
import { join } from "node:path";
import { embedded } from "./embedded.mjs";

const out = [`embedded=${embedded}`];
const attempt = async (name, load) => {
	try {
		out.push(`${name}=${await load()}`);
	} catch {
		out.push(`${name}=not-found`);
	}
};
const variable = (specifier) => import(specifier); // a specifier the bundler cannot follow
await attempt("bare", async () => (await variable("planted")).default);
await attempt("package", async () => (await variable("planted-pkg")).default);
await attempt("relative", async () => (await variable("./planted-file.mjs")).default);
await attempt("require", () => createRequire(import.meta.url)("planted"));
await attempt("resolve", () => (createRequire(import.meta.url).resolve("planted"), "found"));
// Directories the caller names itself are searched, as on Node.
await attempt("paths", () => (createRequire(import.meta.url).resolve("planted-pkg", { paths: [process.cwd()] }), "found"));
await attempt("builtin", async () => typeof (await variable("fs")).readFileSync);
await attempt("node-builtin", async () => typeof (await variable("node:os")).homedir);
await attempt("absolute", async () => (await variable(join(process.cwd(), "planted-file.mjs"))).default);
console.log(out.join(" "));
