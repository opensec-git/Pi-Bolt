import { mkdtempSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createBashTool } from "../src/core/tools/bash.ts";

let tempRoot: string;

beforeEach(() => {
	tempRoot = mkdtempSync(join(tmpdir(), "pi-self-feed-"));
	for (const name of ["TMPDIR", "TEMP", "TMP"]) vi.stubEnv(name, tempRoot);
});

afterEach(() => {
	vi.unstubAllEnvs();
	rmSync(tempRoot, { recursive: true, force: true });
});

// upstream #12516: the bash output file was `$TMPDIR/pi-bash-<hex>.log`, so a command that read
// `$TMPDIR/pi-bash-*` read its own output file while its output was appended to it, and the file grew without end.
describe("bash output file", () => {
	it.skipIf(process.platform === "win32")(
		"is not read back by a command that reads the temp directory",
		async () => {
			const command = `head -c 300000 /dev/zero | tr '\\0' a; echo; cat "$TMPDIR"/pi-bash-*.log "$TMPDIR"/* 2>/dev/null; true`;
			const result = await createBashTool(tempRoot).execute("call", { command, timeout: 5 });

			const path = (result.details as { fullOutputPath?: string } | undefined)?.fullOutputPath;
			expect(path).toBeTypeOf("string");
			expect(dirname(path as string)).not.toBe(tempRoot);
			expect(statSync(path as string).size).toBeLessThan(1024 * 1024);
			expect(statSync(dirname(path as string)).mode & 0o777).toBe(0o700);
		},
		30_000,
	);
});
