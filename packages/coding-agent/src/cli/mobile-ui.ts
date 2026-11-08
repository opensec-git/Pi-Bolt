/**
 * `pi-bolt mobile-ui check|preview <file>`: validate mobile UI views (docs/mobile-ui.md) and preview them without a
 * phone. The file holds one view or `{ "views": [...] }`.
 */

import { readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join, resolve } from "node:path";
import { COMMAND_NAME } from "../config.ts";
import { viewsToHtml } from "../core/mobile-ui/preview.ts";
import { viewToLines } from "../core/mobile-ui/text.ts";
import type { MobileView } from "../core/mobile-ui/types.ts";
import { validateView } from "../core/mobile-ui/validate.ts";

function usage(): string {
	return `Usage:
  ${COMMAND_NAME} mobile-ui check <file.json>                 Validate views; print errors or their text form
  ${COMMAND_NAME} mobile-ui preview <file.json> [--out page.html]  Write an HTML preview in the app's look

The file holds one view or { "views": [...] }. See docs/mobile-ui.md.`;
}

/** The views of a file, named for messages: `file.json`, or `file.json #2` for the second of a list. */
function readViews(path: string): Array<{ name: string; value: unknown }> {
	const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
	const name = basename(path);
	if (typeof parsed === "object" && parsed !== null && Array.isArray((parsed as { views?: unknown }).views)) {
		return (parsed as { views: unknown[] }).views.map((value, index) => ({ name: `${name} #${index + 1}`, value }));
	}
	return [{ name, value: parsed }];
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
	let views: Array<{ name: string; value: unknown }>;
	try {
		views = readViews(resolve(file));
	} catch (error) {
		console.error(`Cannot read ${file}: ${error instanceof Error ? error.message : String(error)}`);
		return 1;
	}
	let failed = false;
	for (const { name, value } of views) {
		const errors = validateView(value);
		if (errors.length > 0) {
			failed = true;
			console.error(`${name}: ${errors.length} problem${errors.length === 1 ? "" : "s"}`);
			for (const error of errors) console.error(`  ${error}`);
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
	writeFileSync(out, viewsToHtml(views.map(({ name, value }) => ({ name, view: value as MobileView }))));
	console.log(out);
	return 0;
}
