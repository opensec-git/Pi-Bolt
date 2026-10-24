import { createRequire } from "node:module";
import type * as Readline from "node:readline";

let readline: typeof Readline | undefined;

/**
 * `createInterface()` of node:readline, which is loaded the first time this is called: imported, it would be loaded at every
 * start (with what it loads in turn), and Pi only reads lines with it in a few cases (find and grep through fd and rg, a
 * confirmation at the prompt).
 */
export function createInterface(options: Readline.ReadLineOptions): Readline.Interface {
	readline ??= createRequire(import.meta.url)("node:readline") as typeof Readline;
	return readline.createInterface(options);
}
