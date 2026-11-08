import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthStorage } from "../src/core/auth-storage.ts";
import { createEventBus } from "../src/core/event-bus.ts";
import {
	extensionDisplayName,
	extensionOfStack,
	JIT_REQUIRED_MESSAGE,
	withJitHint,
} from "../src/core/extensions/jit-hint.ts";
import { loadExtensions } from "../src/core/extensions/loader.ts";
import { ExtensionRunner } from "../src/core/extensions/runner.ts";
import { SessionManager } from "../src/core/session-manager.ts";
import type * as PiBoltModule from "../src/pi-bolt.ts";
import {
	type PiBoltBuild,
	parsePiBoltBuild,
	piBoltJitSwitchCommand,
	piBoltJitVariant,
	piBoltReleasePlatform,
} from "../src/pi-bolt.ts";
import { createInMemoryModelRegistry } from "./model-runtime-test-utils.ts";

// The build these tests run as: Pi-Bolt without the JIT, unless a test says otherwise. (In source runs PIBOLT is undefined.)
const build = vi.hoisted(() => ({ value: undefined as PiBoltBuild | undefined }));
vi.mock("../src/pi-bolt.ts", async (importOriginal) => {
	const original = await importOriginal<typeof PiBoltModule>();
	return {
		...original,
		get PIBOLT() {
			return build.value;
		},
	};
});

let tempDir: string;

beforeEach(() => {
	build.value = { version: "0.8.8", variant: "arm64", jit: false };
	tempDir = mkdtempSync(join(tmpdir(), "pi-bolt-jit-hint-"));
});

afterEach(() => {
	rmSync(tempDir, { recursive: true, force: true });
});

/** An extension package in the temporary folder: package.json with `name`, and `files`. */
function writePackage(name: string, files: Record<string, string>): string {
	const root = join(tempDir, name);
	mkdirSync(root, { recursive: true });
	writeFileSync(join(root, "package.json"), JSON.stringify({ name, version: "1.0.0" }));
	for (const [file, content] of Object.entries(files)) writeFileSync(join(root, file), content);
	return root;
}

describe("PIBOLT_BUILD", () => {
	it("reads the runtime stamp when the build has one", () => {
		expect(parsePiBoltBuild("0.8.8 arm64 jit-off 0f61517d28995399")).toEqual({
			version: "0.8.8",
			variant: "arm64",
			jit: false,
			runtimeStamp: "0f61517d28995399",
		});
		expect(parsePiBoltBuild("0.8.8 x64-jit jit-on")).toEqual({ version: "0.8.8", variant: "x64-jit", jit: true });
		expect(parsePiBoltBuild(undefined)).toBeUndefined();
	});
});

describe("the JIT build to switch to", () => {
	it("is the -jit variant for the CPU", () => {
		expect(piBoltJitVariant("x64")).toBe("x64-jit");
		expect(piBoltJitVariant("x64-baseline")).toBe("x64-jit");
		expect(piBoltJitVariant("arm64")).toBe("arm64-jit");
		expect(piBoltJitVariant("arm64-jit")).toBe("arm64-jit");
		// The musl builds (#280) keep their libc: a glibc build does not run on Alpine.
		expect(piBoltJitVariant("x64-musl")).toBe("x64-musl-jit");
		expect(piBoltJitVariant("x64-musl-baseline")).toBe("x64-musl-jit");
		expect(piBoltJitVariant("x64-musl-jit")).toBe("x64-musl-jit");
	});

	it("names the musl builds' release files with their libc (#280)", () => {
		const arch = process.arch;
		expect(piBoltReleasePlatform("x64-musl")).toBe(`${process.platform}-${arch}-musl`);
		expect(piBoltReleasePlatform("x64-musl-baseline")).toBe(`${process.platform}-${arch}-musl`);
		expect(piBoltReleasePlatform("x64-baseline")).toBe(`${process.platform}-${arch}`);
		expect(piBoltReleasePlatform("arm64-jit")).toBe(`${process.platform}-${arch}`);
	});

	it("is installed as this one was installed", () => {
		expect(piBoltJitSwitchCommand("installer", "linux", "x64-jit")).toBe(
			"curl -fsSL https://pi-bolt.opensec.in/install.sh | PIBOLT_VARIANT=x64-jit sh",
		);
		expect(piBoltJitSwitchCommand("installer", "win32", "x64-jit")).toBe(
			'$env:PIBOLT_VARIANT = "x64-jit"; irm https://pi-bolt.opensec.in/install.ps1 | iex',
		);
		// The npm package reads PIBOLT_VARIANT at every run on macOS and Linux, and when it is installed on Windows.
		expect(piBoltJitSwitchCommand("npm", "darwin", "arm64-jit")).toBe(
			"export PIBOLT_VARIANT=arm64-jit (in your shell profile)",
		);
		expect(piBoltJitSwitchCommand("npm", "win32", "x64-jit")).toBe(
			'$env:PIBOLT_VARIANT = "x64-jit"; npm install -g --allow-scripts=pi-bolt pi-bolt',
		);
	});
});

describe("withJitHint", () => {
	it("names the extension and the command after the engine's message", () => {
		const message = withJitHint(`FFF init failed: ${JIT_REQUIRED_MESSAGE}`, () => "pi-foo");
		expect(message).toMatch(
			/^FFF init failed: bun:ffi requires the JIT\. pi-foo needs Pi-Bolt's JIT build \(bun:ffi\);/,
		);
		expect(message).toContain("switch with: ");
		expect(message).toContain("PIBOLT_VARIANT=");
		// Once: a message that has the hint already keeps it as it is.
		expect(withJitHint(message, () => "pi-foo")).toBe(message);
	});

	it("leaves every other message, and every other build, alone", () => {
		const name = vi.fn(() => "pi-foo");
		expect(withJitHint("ENOENT: no such file", name)).toBe("ENOENT: no such file");
		expect(withJitHint(42, name)).toBe(42);
		build.value = { version: "0.8.8", variant: "arm64-jit", jit: true };
		expect(withJitHint(JIT_REQUIRED_MESSAGE, name)).toBe(JIT_REQUIRED_MESSAGE);
		build.value = undefined;
		expect(withJitHint(JIT_REQUIRED_MESSAGE, name)).toBe(JIT_REQUIRED_MESSAGE);
		// The extension is looked up only for a message that gets the hint.
		expect(name).not.toHaveBeenCalled();
	});
});

describe("extensionDisplayName and extensionOfStack", () => {
	it("names an extension by its package, else by its file", () => {
		const root = writePackage("pi-foo", { "index.ts": "" });
		mkdirSync(join(root, "dist"));
		expect(extensionDisplayName(join(root, "index.ts"))).toBe("pi-foo");
		expect(extensionDisplayName(join(root, "dist", "index.js"))).toBe("pi-foo");
		// A single file in an extensions folder: a package.json above it is not the extension's.
		const extensions = join(tempDir, "agent", "extensions");
		mkdirSync(extensions, { recursive: true });
		writeFileSync(join(tempDir, "agent", "package.json"), JSON.stringify({ name: "not-it" }));
		expect(extensionDisplayName(join(extensions, "one.ts"))).toBe(join(extensions, "one.ts"));
		expect(extensionDisplayName("builtin:mcp")).toBe("builtin:mcp");
	});

	it("finds the extension a stack trace's code belongs to, its dependencies included", () => {
		const foo = writePackage("pi-foo", { "index.ts": "" });
		const bar = writePackage("pi-bar", { "index.ts": "" });
		const stack = [
			"TypeError: bun:ffi requires the JIT",
			"    at dlopen (native)",
			"    at dlopen (bun:ffi:157:28)",
			`    at open (${join(bar, "node_modules", "native-lib", "index.js")}:3:9)`,
			`    at <anonymous> (${join(bar, "index.ts")}:10:18)`,
			"    at initializeExtension (/$bunfs/root/pi:275755:18)",
		].join("\n");
		expect(extensionOfStack(stack, [join(foo, "index.ts"), join(bar, "index.ts")])).toBe(join(bar, "index.ts"));
		expect(extensionOfStack("Error\n    at main (/$bunfs/root/pi:1:1)", [join(foo, "index.ts")])).toBeUndefined();
		// Single-file extensions in one folder: the frame's own file names it.
		const folder = join(tempDir, "extensions");
		mkdirSync(folder);
		const single = `Error\n    at <anonymous> (${join(folder, "b.ts")}:2:3)`;
		expect(extensionOfStack(single, [join(folder, "a.ts"), join(folder, "b.ts")])).toBe(join(folder, "b.ts"));
		expect(extensionOfStack(undefined, [join(foo, "index.ts")])).toBeUndefined();
	});
});

describe("where Pi-Bolt shows the error", () => {
	it("says which extension that failed to load needs the JIT build", async () => {
		const root = writePackage("pi-ffi", {
			"index.ts": `export default function () { throw new TypeError("${JIT_REQUIRED_MESSAGE}"); }\n`,
		});
		const result = await loadExtensions([join(root, "index.ts")], tempDir);
		expect(result.errors).toHaveLength(1);
		expect(result.errors[0].error).toMatch(
			/^Failed to load extension: bun:ffi requires the JIT\. pi-ffi needs Pi-Bolt's JIT build \(bun:ffi\); switch with: /,
		);
	});

	it("leaves the error as it is on the JIT build", async () => {
		build.value = { version: "0.8.8", variant: "arm64-jit", jit: true };
		const root = writePackage("pi-ffi", {
			"index.ts": `export default function () { throw new TypeError("${JIT_REQUIRED_MESSAGE}"); }\n`,
		});
		const result = await loadExtensions([join(root, "index.ts")], tempDir);
		expect(result.errors[0].error).toBe(`Failed to load extension: ${JIT_REQUIRED_MESSAGE}`);
	});

	it("adds the hint to a handler's error and to the extension's own notice", async () => {
		const notifier = writePackage("pi-notifier", {
			"index.ts": `export default function (pi) {
	pi.on("session_start", (_event, ctx) => { ctx.ui.notify("FFF init failed: ${JIT_REQUIRED_MESSAGE}", "error"); });
}
`,
		});
		const thrower = writePackage("pi-thrower", {
			"index.ts": `export default function (pi) {
	pi.on("session_start", () => { throw new TypeError("${JIT_REQUIRED_MESSAGE}"); });
}
`,
		});
		const result = await loadExtensions(
			[join(notifier, "index.ts"), join(thrower, "index.ts")],
			tempDir,
			createEventBus(),
		);
		expect(result.errors).toEqual([]);
		const modelRegistry = await createInMemoryModelRegistry(AuthStorage.inMemory());
		const runner = new ExtensionRunner(
			result.extensions,
			result.runtime,
			tempDir,
			SessionManager.inMemory(),
			modelRegistry,
		);
		const notices: string[] = [];
		runner.setUIContext({ ...runner.getUIContext(), notify: (message) => notices.push(message) }, "tui");
		const errors: string[] = [];
		runner.onError((error) => errors.push(error.error));

		await runner.emit({ type: "session_start", reason: "startup" });

		expect(notices).toHaveLength(1);
		expect(notices[0]).toMatch(/^FFF init failed: bun:ffi requires the JIT\. pi-notifier needs Pi-Bolt's JIT build/);
		expect(errors).toHaveLength(1);
		expect(errors[0]).toMatch(/^bun:ffi requires the JIT\. pi-thrower needs Pi-Bolt's JIT build/);
	});

	it("passes other notices through unchanged", async () => {
		const modelRegistry = await createInMemoryModelRegistry(AuthStorage.inMemory());
		const result = await loadExtensions([], tempDir);
		const runner = new ExtensionRunner([], result.runtime, tempDir, SessionManager.inMemory(), modelRegistry);
		const notices: Array<[string, string | undefined]> = [];
		runner.setUIContext(
			{ ...runner.getUIContext(), notify: (message, type) => notices.push([message, type]) },
			"tui",
		);
		runner.getUIContext().notify("Saved", "info");
		expect(notices).toEqual([["Saved", "info"]]);
	});
});
