/**
 * `pi-bolt mobile-ui check|preview <file>`: validate mobile UI views (docs/mobile-ui.md) and preview them without a
 * phone. The file holds one view, or `{ "views": [...], "tiles": [...] }` (tiles are also checked as Home tiles).
 */

import { readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join, resolve } from "node:path";
import { COMMAND_NAME } from "../config.ts";
import { viewsToHtml } from "../core/mobile-ui/preview.ts";
import { viewToLines } from "../core/mobile-ui/text.ts";
import type { MobileView } from "../core/mobile-ui/types.ts";
import { validateTile, validateView } from "../core/mobile-ui/validate.ts";

function usage(): string {
	return `Usage:
  ${COMMAND_NAME} mobile-ui check <file.json> [--tile]                 Validate views; print errors or their text form
  ${COMMAND_NAME} mobile-ui preview <file.json> [--tile] [--out page.html]  Write an HTML preview in the app's look

The file holds one view, or { "views": [...], "tiles": [...] }. Tiles, and every view with --tile,
are also checked against the Home tile rules. See docs/mobile-ui.md.`;
}

interface FileView {
	name: string;
	value: unknown;
	tile: boolean;
}

/** The views of a file, named for messages: `file.json`, `file.json #2` for the second view, `file.json tile #1`. */
function readViews(path: string, allTiles: boolean): FileView[] {
	const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
	const name = basename(path);
	const lists = parsed as { views?: unknown; tiles?: unknown };
	if (typeof parsed === "object" && parsed !== null && (Array.isArray(lists.views) || Array.isArray(lists.tiles))) {
		const views = Array.isArray(lists.views) ? lists.views : [];
		const tiles = Array.isArray(lists.tiles) ? lists.tiles : [];
		return [
			...views.map((value, index) => ({ name: `${name} #${index + 1}`, value, tile: allTiles })),
			...tiles.map((value, index) => ({ name: `${name} tile #${index + 1}`, value, tile: true })),
		];
	}
	return [{ name, value: parsed, tile: allTiles }];
}

export async function runMobileUiCommand(args: string[]): Promise<number> {
	const [subcommand, file, ...rest] = args;
	if (
		!subcommand ||
		subcommand === "--help" ||
		subcommand === "-h" ||
		(subcommand !== "check" && subcommand !== "preview")
	) {
		console.log(usage());
		return subcommand && subcommand !== "--help" && subcommand !== "-h" ? 1 : 0;
	}
	if (!file) {
		console.error(usage());
		return 1;
	}
	let views: FileView[];
	try {
		views = readViews(resolve(file), rest.includes("--tile"));
	} catch (error) {
		console.error(`Cannot read ${file}: ${error instanceof Error ? error.message : String(error)}`);
		return 1;
	}
	let failed = false;
	for (const { name, value, tile } of views) {
		let errors = validateView(value);
		if (errors.length === 0 && tile) errors = validateTile(value as MobileView);
		if (errors.length > 0) {
			failed = true;
			console.error(`${name}: ${errors.length} problem${errors.length === 1 ? "" : "s"}`);
			for (const error of errors) console.error(`  ${error}`);
		} else if (subcommand === "check" && tile) {
			console.log(`${name}: a valid Home tile (no text form: the terminal does not show tiles).`);
		} else if (subcommand === "check") {
			console.log(`${name}: valid. Text form:`);
			for (const line of viewToLines(value as MobileView)) console.log(`  ${line}`);
		}
	}
	if (failed) return 1;
	if (subcommand === "check") return 0;
	const outIndex = rest.indexOf("--out");
	const out =
		outIndex >= 0 && rest[outIndex + 1]
			? resolve(rest[outIndex + 1])
			: join(tmpdir(), `mobile-ui-${basename(file, ".json")}.html`);
	writeFileSync(out, viewsToHtml(views.map(({ name, value, tile }) => ({ name, view: value as MobileView, tile }))));
	console.log(out);
	return 0;
}
