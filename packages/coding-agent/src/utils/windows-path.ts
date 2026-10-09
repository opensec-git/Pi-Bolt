import { delimiter } from "node:path";

/** PATH's directories as Windows reads them: an entry may be in quotes ("C:\Program Files\x"), which are not part of it. */
export function windowsPathDirectories(path: string): string[] {
	return path
		.split(delimiter)
		.map((dir) => dir.trim().replace(/^"(.*)"$/, "$1"))
		.filter(Boolean);
}
