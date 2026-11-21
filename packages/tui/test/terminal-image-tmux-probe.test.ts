import assert from "node:assert";
import { execFileSync } from "node:child_process";
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, join } from "node:path";
import { afterEach, beforeEach, describe, it } from "node:test";
import {
	getCapabilities,
	onCapabilitiesChange,
	resetCapabilitiesCache,
	setCapabilities,
} from "../src/terminal-image.ts";

const ENV_KEYS = [
	"PATH",
	"TMUX",
	"TERM",
	"TERM_PROGRAM",
	"PI_HYPERLINKS",
	"PI_IMAGE_PROTOCOL",
	"PI_TRUE_COLOR",
] as const;

describe("tmux hyperlink probe", { skip: process.platform === "win32", timeout: 10_000 }, () => {
	let dir: string;
	let saved: Record<string, string | undefined>;

	function fakeTmux(features: string, delaySeconds = 0): void {
		const script = join(dir, "tmux");
		writeFileSync(script, `#!/bin/sh\n[ -n "$WARM_UP" ] || sleep ${delaySeconds}\necho '${features}'\n`);
		chmodSync(script, 0o755);
		// The first run of a new script can be slow (macOS checks it): keep that out of the probe's 250 ms.
		execFileSync(script, { env: { ...process.env, WARM_UP: "1" } });
	}

	beforeEach(() => {
		dir = mkdtempSync(join(tmpdir(), "pi-tmux-probe-"));
		saved = Object.fromEntries(ENV_KEYS.map((key) => [key, process.env[key]]));
		for (const key of ENV_KEYS) delete process.env[key];
		process.env.PATH = `${dir}${delimiter}${saved.PATH ?? ""}`;
		process.env.TMUX = "/tmp/tmux-1000/default,1234,0";
		process.env.TERM = "tmux-256color";
		resetCapabilitiesCache();
	});

	afterEach(() => {
		for (const key of ENV_KEYS) {
			if (saved[key] === undefined) delete process.env[key];
			else process.env[key] = saved[key];
		}
		resetCapabilitiesCache();
		rmSync(dir, { recursive: true, force: true });
	});

	it("does not wait for tmux and draws again once it reports hyperlinks", async () => {
		fakeTmux("RGB,hyperlinks,title", 0.05);
		const changed = Promise.withResolvers<void>();
		const unsubscribe = onCapabilitiesChange(changed.resolve);
		// The probe releases its event-loop handles. Model a live terminal while waiting for its answer.
		const timeout = setTimeout(() => changed.reject(new Error("tmux capability update timed out")), 2000);
		try {
			const started = performance.now();
			assert.strictEqual(getCapabilities().hyperlinks, false);
			assert.ok(performance.now() - started < 40, "getCapabilities() waited for tmux");
			await changed.promise;
			assert.strictEqual(getCapabilities().hyperlinks, true);
		} finally {
			clearTimeout(timeout);
			unsubscribe();
		}
	});

	it("keeps hyperlinks off without a change notice when tmux does not forward them", async () => {
		fakeTmux("RGB,title");
		let notified = 0;
		const unsubscribe = onCapabilitiesChange(() => notified++);
		try {
			assert.strictEqual(getCapabilities().hyperlinks, false);
			await new Promise((resolve) => setTimeout(resolve, 150));
			assert.strictEqual(notified, 0);
			assert.strictEqual(getCapabilities().hyperlinks, false);
		} finally {
			unsubscribe();
		}
	});

	it("leaves capabilities set by hand alone", async () => {
		fakeTmux("hyperlinks", 0.05);
		assert.strictEqual(getCapabilities().hyperlinks, false);
		setCapabilities({ images: null, trueColor: false, hyperlinks: false });
		await new Promise((resolve) => setTimeout(resolve, 200));
		assert.strictEqual(getCapabilities().hyperlinks, false);
	});

	it("treats a tmux that does not answer in time as no hyperlinks", async () => {
		fakeTmux("hyperlinks", 1);
		let notified = 0;
		const unsubscribe = onCapabilitiesChange(() => notified++);
		try {
			assert.strictEqual(getCapabilities().hyperlinks, false);
			await new Promise((resolve) => setTimeout(resolve, 400));
			assert.strictEqual(notified, 0);
			assert.strictEqual(getCapabilities().hyperlinks, false);
		} finally {
			unsubscribe();
		}
	});
});
