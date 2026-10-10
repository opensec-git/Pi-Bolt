import { mkdtempSync, readFileSync, rmSync, statSync } from "fs";
import { tmpdir } from "os";
import { join } from "path";
import { afterEach, describe, expect, it } from "vitest";
import { OutputFile } from "../src/utils/output-files.ts";

describe("OutputFile", () => {
	const dirs: string[] = [];
	const scratch = () => {
		const dir = mkdtempSync(join(tmpdir(), "pi-output-file-"));
		dirs.push(dir);
		return dir;
	};
	afterEach(() => {
		for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
	});

	it("writes what it is given, in order, readable only by the user", () => {
		const path = join(scratch(), "out.log");
		const file = new OutputFile(path);
		file.write("one\n");
		file.write(Buffer.from("two\n"));
		file.write("€\n");
		file.close();
		expect(readFileSync(path, "utf-8")).toBe("one\ntwo\n€\n");
		expect(statSync(path).mode & 0o777).toBe(0o600);
	});

	it("stops at its size limit with a note", () => {
		const path = join(scratch(), "out.log");
		const file = new OutputFile(path, 10);
		file.write("12345678");
		file.write("abcdef");
		file.write("more");
		file.close();
		expect(readFileSync(path, "utf-8")).toBe("12345678ab\n[Output beyond 10 bytes was not saved]\n");
	});

	it("does not open a path that exists, and reports that on close instead of on write", () => {
		const path = join(scratch(), "out.log");
		new OutputFile(path).close();
		const file = new OutputFile(path);
		expect(file.opened).toBe(false);
		expect(file.failed).toBe(true);
		expect(() => file.write("ignored")).not.toThrow();
		expect(() => file.close()).toThrow(/EEXIST/);
		expect(readFileSync(path, "utf-8")).toBe("");
	});
});
