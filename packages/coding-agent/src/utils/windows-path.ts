import { lstatSync, statSync } from "node:fs";
import { delimiter } from "node:path";

/** PATH's directories as Windows reads them: an entry may be in quotes ("C:\Program Files\x"), which are not part of it. */
export function windowsPathDirectories(path: string): string[] {
	return path
		.split(delimiter)
		.map((dir) => dir.trim().replace(/^"(.*)"$/, "$1"))
		.filter(Boolean);
}

/**
 * A file there to run. An app execution alias (a Store app's pwsh.exe, python.exe or winget.exe in WindowsApps) is a reparse
 * point that stat may refuse to follow, or say is not there; Windows starts it all the same, and `where` lists it: it counts
 * when the link itself is there and is not a folder.
 */
export function isProgramFile(file: string): boolean {
	try {
		const stats = statSync(file, { throwIfNoEntry: false });
		if (stats) return stats.isFile();
	} catch {}
	try {
		const link = lstatSync(file, { throwIfNoEntry: false });
		return link !== undefined && !link.isDirectory();
	} catch {
		return false;
	}
}
