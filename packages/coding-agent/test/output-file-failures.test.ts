// #10585: a full, over-quota or unwritable temp dir must not crash pi. Bash output that cannot be
// saved to a temp file keeps its in-memory tail and says why the full output is missing.
import type * as fs from "node:fs";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

const fsMocks = vi.hoisted(() => ({
	failure: undefined as "write" | "close" | "exists" | undefined,
	createdPaths: [] as string[],
}));

/** Node's errors: the message starts with the code, as the user-facing text shows it. */
function fsError(code: string, message: string): Error {
	return Object.assign(new Error(`${code}: ${message}`), { code });
}

// Fault injection for failures a test cannot cause on a real disk, on the output files' descriptors only (opened
// with `wx`). `write`: the file opens, then every write fails (ENOSPC). `close`: writes succeed, closing the
// descriptor fails (delayed EDQUOT on quota and network file systems). `exists`: something already sits at the
// path, so the `wx` open fails.
vi.mock("node:fs", async (importOriginal) => {
	const actual = await importOriginal<typeof fs>();
	const outputFds = new Set<number>();
	return {
		...actual,
		openSync: (path: fs.PathLike, flags: fs.OpenMode, mode?: fs.Mode) => {
			const failure = fsMocks.failure;
			if (failure === undefined || flags !== "wx") return actual.openSync(path, flags, mode);
			fsMocks.createdPaths.push(String(path));
			if (failure === "exists") {
				actual.writeFileSync(path, "partial");
				throw fsError("EEXIST", `file already exists, open '${path}'`);
			}
			const fd = actual.openSync(path, flags, mode);
			outputFds.add(fd);
			return fd;
		},
		writeSync: ((fd: number, ...rest: unknown[]) => {
			if (outputFds.has(fd) && fsMocks.failure === "write")
				throw fsError("ENOSPC", "no space left on device, write");
			return (actual.writeSync as (fd: number, ...rest: unknown[]) => number)(fd, ...rest);
		}) as typeof fs.writeSync,
		closeSync: (fd: number) => {
			actual.closeSync(fd);
			if (outputFds.delete(fd) && fsMocks.failure === "close") throw fsError("EDQUOT", "disk quota exceeded, close");
		},
	};
});

import { executeBashWithOperations } from "../src/core/bash-executor.ts";
import { type BashExecutionMessage, bashExecutionToText } from "../src/core/messages.ts";
import {
	type BashOperations,
	type BashToolDetails,
	type BashToolOutput,
	createBashTool,
	createBashToolDefinition,
} from "../src/core/tools/bash.ts";
import type { TruncationResult } from "../src/core/tools/truncate.ts";
import { BashExecutionComponent } from "../src/modes/interactive/components/bash-execution.ts";
import { initTheme, theme } from "../src/modes/interactive/theme/theme.ts";
import { stripAnsi } from "../src/utils/ansi.ts";
import { createOutputFile, type OutputFile } from "../src/utils/output-files.ts";

/** Minimal TUI for rendering components: a terminal size and no-op scheduling. */
const tuiStub = {
	terminal: { columns: 120, rows: 24 },
	addInterval: () => ({ dispose: () => {} }),
	removeInterval: () => {},
	requestRender: () => {},
} as never;

/** Operations that print `text` in 64 KiB chunks and exit with `exitCode`. */
function printing(text: string, exitCode = 0): BashOperations {
	const bytes = Buffer.from(text);
	return {
		exec: async (_command, _cwd, { onData }) => {
			for (let i = 0; i < bytes.length; i += 65536) onData(bytes.subarray(i, i + 65536));
			return { exitCode };
		},
	};
}

/** `seq 1 count` output. */
const numbers = (count: number) => Array.from({ length: count }, (_, i) => `${i + 1}\n`).join("");

/** 3000 numbered lines: over the 2000-line limit, so the full output goes to a temp file; about 14 KB. */
const SHORT = numbers(3000);
/** 300000 numbered lines, about 2 MB: more than the accumulator keeps in memory. */
const LONG = numbers(300_000);

let tempRoot: string;

beforeAll(() => initTheme(undefined, false));

beforeEach(() => {
	// Every file the tests create goes here and is removed with it.
	tempRoot = mkdtempSync(join(tmpdir(), "pi-output-file-failures-"));
	for (const name of ["TMPDIR", "TEMP", "TMP"]) vi.stubEnv(name, tempRoot);
	fsMocks.failure = undefined;
	fsMocks.createdPaths = [];
});

afterEach(() => {
	rmSync(tempRoot, { recursive: true, force: true });
});

/** Point the temp dir at a directory that does not exist, so opening output files fails. */
function useMissingTempDir(): void {
	const missing = join(tempRoot, "missing");
	for (const name of ["TMPDIR", "TEMP", "TMP"]) vi.stubEnv(name, missing);
}

/** Make output files fail on open (unwritable temp dir) or on write (full disk). */
function failOutputFiles(failure: "open" | "write"): string {
	if (failure === "open") useMissingTempDir();
	else fsMocks.failure = "write";
	return failure === "open" ? "ENOENT" : "ENOSPC";
}

/** Open an output file and return it with its path, which a `failed` result no longer carries. */
function openOutputFile(): { file: OutputFile; path: string } {
	const file = createOutputFile("pi-test", ".log");
	return { file, path: file.path };
}

describe("createOutputFile", () => {
	it("reports an open failure instead of throwing", () => {
		useMissingTempDir();
		const { file, path } = openOutputFile();
		file.write("data");
		expect(file.close()).toEqual({ kind: "failed", error: expect.stringContaining("ENOENT") });
		expect(existsSync(path)).toBe(false);
	});

	it("drops a partially written file", () => {
		fsMocks.failure = "write";
		const { file, path } = openOutputFile();
		file.write("data");
		expect(file.result).toEqual({ kind: "failed", error: expect.stringContaining("ENOSPC") });
		file.write("more");
		expect(file.close()).toEqual({ kind: "failed", error: expect.stringContaining("ENOSPC") });
		expect(existsSync(path)).toBe(false);
	});

	it("reports a failure while closing the file, not saved", () => {
		fsMocks.failure = "close";
		const { file, path } = openOutputFile();
		file.write("data");
		expect(file.close()).toEqual({ kind: "failed", error: expect.stringContaining("EDQUOT") });
		expect(existsSync(path)).toBe(false);
	});

	it("leaves a file it did not create in place", () => {
		fsMocks.failure = "exists";
		const { file, path } = openOutputFile();
		file.write("data");
		expect(file.close()).toEqual({ kind: "failed", error: expect.stringContaining("EEXIST") });
		expect(existsSync(path)).toBe(true);
	});

	it("saves the full output when writing succeeds", () => {
		const { file, path } = openOutputFile();
		file.write("data");
		expect(file.close()).toEqual({ kind: "saved", path });
		expect(file.result).toEqual({ kind: "saved", path });
		expect(readFileSync(path, "utf-8")).toBe("data");
	});

	it("ignores writes once closed", () => {
		const { file, path } = openOutputFile();
		file.write("data");
		const result = file.close();
		file.write("late");
		expect(result).toEqual({ kind: "saved", path });
		expect(readFileSync(path, "utf-8")).toBe("data");
	});
});

describe("bash tool", () => {
	it.each(["open", "write"] as const)("keeps the output tail when the temp file %s fails", async (failure) => {
		const code = failOutputFiles(failure);
		const result = await createBashTool(tempRoot, { operations: printing(LONG) }).execute("call", {
			command: "seq 300000",
		});

		const text = result.content[0]?.type === "text" ? result.content[0].text : "";
		expect(text).toContain("\n299999\n300000\n");
		expect(text).toMatch(
			new RegExp(`\\[Showing lines \\d+-300000 of 300000\\. Could not save the full output: .*${code}`),
		);
		expect(result.details?.fullOutputPath).toBeUndefined();
		expect(result.details?.fullOutputError).toContain(code);
		const structured = result.structuredContent as BashToolOutput;
		expect(structured.truncated).toBe(true);
		expect(structured.full_output_path).toBeUndefined();
		expect(structured.full_output_error).toContain(code);
		expect(structured.output).toMatch(/^\[\.\.\. [1-9]\d* bytes omitted \.\.\.\]\n\n/);
		expect(structured.output.endsWith("299999\n300000\n")).toBe(true);
		for (const path of fsMocks.createdPaths) expect(existsSync(path)).toBe(false);
	});

	it("gives scripts the whole output when it all fits in memory", async () => {
		const code = failOutputFiles("open");
		const result = await createBashTool(tempRoot, { operations: printing(SHORT) }).execute("call", {
			command: "seq 3000",
		});

		// The model sees the usual 2000 lines, without the file.
		expect(result.details?.truncation?.truncated).toBe(true);
		expect(result.details?.fullOutputError).toContain(code);
		// Scripts take up to 1 MiB and lose nothing.
		expect(result.structuredContent).toMatchObject({ output: SHORT, truncated: false });
		expect((result.structuredContent as BashToolOutput).full_output_error).toBeUndefined();
	});

	it("keeps the end of one over-long line for scripts", async () => {
		failOutputFiles("open");
		const result = await createBashTool(tempRoot, { operations: printing(`${"x".repeat(300_000)}\n`) }).execute(
			"call",
			{ command: "long-line" },
		);

		const structured = result.structuredContent as BashToolOutput;
		expect(structured.truncated).toBe(true);
		expect(structured.output).toMatch(/^\[\.\.\. [1-9]\d* bytes omitted \.\.\.\]\n\nx{1000,}\n$/);
	});

	it("shows why the full output is missing once, below the output", async () => {
		const code = failOutputFiles("open");
		const operations = printing(LONG);
		const result = await createBashTool(tempRoot, { operations }).execute("call", { command: "seq 300000" });
		const component = createBashToolDefinition(tempRoot, { operations }).renderResult!(
			result as { content: (typeof result)["content"]; details: BashToolDetails },
			{ expanded: false, isPartial: false },
			theme,
			{ state: {}, showImages: false, executionStarted: true, invalidate: () => {} } as never,
		);

		// The notice holds the temp file path, so it wraps when the temp dir path is long (macOS).
		const rendered = stripAnsi(component.render(200).join("\n")).replace(/\s+/g, " ");
		expect(rendered).not.toContain("[Showing lines");
		expect(rendered).toMatch(new RegExp(`\\[Could not save the full output: .*${code}.*\\. Truncated: `));
	});
});

describe("user bash executor", () => {
	it.each(["open", "write"] as const)("keeps the output tail when the temp file %s fails", async (failure) => {
		const code = failOutputFiles(failure);
		const result = await executeBashWithOperations("seq 3000", tempRoot, printing(SHORT));

		expect(result.truncated).toBe(true);
		expect(result.fullOutputPath).toBeUndefined();
		expect(result.fullOutputError).toContain(code);
		expect(result.output).toMatch(/\n2999\n3000\n?$/);
		for (const path of fsMocks.createdPaths) expect(existsSync(path)).toBe(false);

		// What the model reads and what the user sees both say the output is cut, and why.
		const message: BashExecutionMessage = { role: "bashExecution", command: "seq 3000", timestamp: 0, ...result };
		expect(bashExecutionToText(message)).toMatch(
			new RegExp(`\\[Output truncated\\. Could not save the full output: .*${code}.*\\]$`),
		);
		const component = new BashExecutionComponent("seq 3000", tuiStub);
		component.appendOutput(result.output);
		// As interactive mode passes it.
		component.setComplete(
			result.exitCode,
			result.cancelled,
			{ truncated: true, content: result.output } as TruncationResult,
			result.fullOutputPath,
			result.fullOutputError,
		);
		// The line wraps at the terminal width.
		expect(stripAnsi(component.render(120).join(" ")).replace(/\s+/g, " ")).toMatch(
			new RegExp(`Output truncated\\. Could not save the full output: .*${code}`),
		);
	});

	it("reports no failure for output that is not truncated", async () => {
		failOutputFiles("open");
		// Over 50 KB of color codes around one short line: the temp file opens on the raw bytes, but
		// stripping the codes leaves nothing to truncate.
		const colored = `${"\x1b[31m\x1b[0m".repeat(7000)}done\n`;
		const result = await executeBashWithOperations("build", tempRoot, printing(colored));

		expect(result.truncated).toBe(false);
		expect(result.fullOutputError).toBeUndefined();
	});

	it("decides the result when the command ends", async () => {
		const controller = new AbortController();
		const late = Buffer.from("late output\n");
		let onLateData: ((data: Buffer) => void) | undefined;
		const result = await executeBashWithOperations(
			"seq 300000",
			tempRoot,
			{
				exec: async (_command, _cwd, { onData }) => {
					onData(Buffer.from(LONG));
					onLateData = onData;
					// After the command ended: an abort, and output from a process the command left running.
					setImmediate(() => {
						controller.abort();
						onLateData?.(late);
					});
					return { exitCode: 7 };
				},
			},
			{ signal: controller.signal },
		);

		expect(result).toMatchObject({ exitCode: 7, cancelled: false, truncated: true });
		expect(result.output).not.toContain("late output");
		expect(readFileSync(result.fullOutputPath!, "utf-8")).toBe(LONG);
	});
});

// Output truncated without a saved file and without a reason (for example a result an extension returned).
describe("truncated user bash output without a file", () => {
	it("still says the output is truncated", () => {
		expect(
			bashExecutionToText({
				role: "bashExecution",
				command: "x",
				output: "tail",
				exitCode: 0,
				cancelled: false,
				truncated: true,
				timestamp: 0,
			}),
		).toMatch(/\[Output truncated\]$/);
	});
});
