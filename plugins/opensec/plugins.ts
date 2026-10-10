/**
 * OpenSec's extensions compiled into Pi-Bolt's release executables: opensec-pi-subagents and opensec-pi-todo, at the versions
 * extensions.txt pins (package.json, package-lock.json: `npm ci --ignore-scripts`, then `node prepare.mjs`, here before building).
 *
 * Build:  scripts\build-pi.ps1 -Plugins plugins\opensec\plugins.ts -Out out\pi-bolt   (scripts\package-release.ps1 does)
 *
 * Each package's own entry (dist-bun/index.js) hands Pi's modules to its core (dist-bun/core.cjs) on a global and loads the core
 * by a path it works out at run time, which a compiled executable has no file for. This does the same with the core imported,
 * so that it is compiled ahead of time with Pi. Being named and built-in, each can be turned off (`-builtin:<name>` in the
 * `extensions` setting, or `pi-bolt config`), and a copy installed from npm replaces it (replaceable) rather than conflicting.
 */
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import type { InlineExtension } from "@earendil-works/pi-coding-agent";
import todoDe from "./node_modules/opensec-pi-todo/locales/de.json";
import todoEn from "./node_modules/opensec-pi-todo/locales/en.json";
import todoEs from "./node_modules/opensec-pi-todo/locales/es.json";
import todoFr from "./node_modules/opensec-pi-todo/locales/fr.json";
import todoPt from "./node_modules/opensec-pi-todo/locales/pt.json";
import todoPtBr from "./node_modules/opensec-pi-todo/locales/pt-BR.json";
import todoRu from "./node_modules/opensec-pi-todo/locales/ru.json";
import todoUk from "./node_modules/opensec-pi-todo/locales/uk.json";
import todoZh from "./node_modules/opensec-pi-todo/locales/zh.json";

type HostModules = Record<string, unknown>;

/** What each package's entry does first: its core asks this global for the modules Pi provides. */
function provideHostModules(packageAndVersion: string, modules: HostModules): void {
	(globalThis as Record<symbol, unknown>)[Symbol.for(`${packageAndVersion}:host-modules`)] = (id: string) => {
		const mod = modules[id];
		if (mod === undefined) throw new Error(`${packageAndVersion}: host module ${id} is not available`);
		return mod;
	};
}

const TODO_LOCALES: Record<string, unknown> = {
	de: todoDe,
	en: todoEn,
	es: todoEs,
	fr: todoFr,
	pt: todoPt,
	"pt-BR": todoPtBr,
	ru: todoRu,
	uk: todoUk,
	zh: todoZh,
};

/**
 * The folder opensec-pi-todo reads its translations from (registerLocales(): `<url>/locales/<code>.json`). The package folder it
 * expects is not there in an executable: the files are written once, from the copies compiled in, to the agent directory's cache.
 */
function todoLocalesUrl(agentDir: string): string {
	const root = join(agentDir, "cache", "opensec-pi-todo-2.13.0");
	const locales = join(root, "locales");
	if (!existsSync(join(locales, "zh.json"))) {
		mkdirSync(locales, { recursive: true });
		for (const [code, strings] of Object.entries(TODO_LOCALES)) {
			writeFileSync(join(locales, `${code}.json`), JSON.stringify(strings));
		}
	}
	return pathToFileURL(`${root}/`).href;
}

const subagents: InlineExtension = {
	name: "opensec-pi-subagents",
	builtin: true,
	replaceable: true,
	// (Pi's modules imported when the extension loads, not when the executable starts: pi-bolt --version loads no extension.)
	factory: async (pi) => {
		const [piCodingAgent, piTui, typebox, typeboxValue] = await Promise.all([
			import("@earendil-works/pi-coding-agent"),
			import("@earendil-works/pi-tui"),
			import("typebox"),
			import("typebox/value"),
		]);
		provideHostModules("opensec-pi-subagents@0.20.0", {
			"@earendil-works/pi-coding-agent": piCodingAgent,
			"@earendil-works/pi-tui": piTui,
			"@sinclair/typebox": typebox,
			"typebox/value": typeboxValue,
		});
		const core = require("./vendor/opensec-pi-subagents.core.cjs");
		return core.default(pi);
	},
};

const todo: InlineExtension = {
	name: "opensec-pi-todo",
	builtin: true,
	replaceable: true,
	factory: async (pi) => {
		const [piAi, piCodingAgent, piTui, typebox, typeboxValue] = await Promise.all([
			import("@earendil-works/pi-ai"),
			import("@earendil-works/pi-coding-agent"),
			import("@earendil-works/pi-tui"),
			import("typebox"),
			import("typebox/value"),
		]);
		provideHostModules("opensec-pi-todo@2.13.0", {
			"@earendil-works/pi-ai": piAi,
			"@earendil-works/pi-tui": piTui,
			typebox: typebox,
			"typebox/value": typeboxValue,
		});
		const core = require("./vendor/opensec-pi-todo.core.cjs");
		core.registerLocales(todoLocalesUrl(piCodingAgent.getAgentDir()));
		return core.default(pi);
	},
};

const plugins: InlineExtension[] = [subagents, todo];

export default plugins;
