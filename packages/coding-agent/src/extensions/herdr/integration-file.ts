import { closeSync, openSync, readSync } from "node:fs";
import { basename } from "node:path";

/**
 * Whether `path` is the extension Herdr's Pi integration installs (`herdr integration install pi`). Pi-Bolt's built-in
 * Herdr extension takes its place.
 */
export function isHerdrPiIntegration(path: string): boolean {
	if (basename(path) !== "herdr-agent-state.ts") return false;
	let head = "";
	try {
		const fd = openSync(path, "r");
		try {
			const buffer = Buffer.alloc(512);
			head = buffer.toString("utf8", 0, readSync(fd, buffer, 0, buffer.length, 0));
		} finally {
			closeSync(fd);
		}
	} catch {
		return false;
	}
	return head.startsWith("// installed by herdr") && /^\/\/ HERDR_INTEGRATION_ID=pi$/m.test(head);
}
