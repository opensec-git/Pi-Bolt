import assert from "node:assert";
import { it } from "node:test";
import { Loader } from "../src/components/loader.ts";
import { ScrollView } from "../src/components/scroll-view.ts";
import { Text } from "../src/components/text.ts";
import { VStack } from "../src/components/v-stack.ts";
import { TuiAltScreen } from "../src/tui-alt-screen.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

// hunt-tui-1: requestRenderWithin() returned early while a scroll frame was pending, and that frame reused the
// components' output: the spinner did not move while the fullscreen transcript was being scrolled.
it("an animation step made while a scroll frame is pending is drawn", async () => {
	const terminal = new VirtualTerminal(40, 10);
	const tui = new TuiAltScreen(terminal);
	const transcript = new Text(Array.from({ length: 60 }, (_, i) => `line ${i}`).join("\n"), 0, 0);
	const loader = new Loader(
		tui,
		(s) => s,
		(s) => s,
		"Working",
		{ frames: ["A", "B", "C"], intervalMs: 1_000_000 },
	);
	// As in fullscreen interactive mode: the status line is below the transcript's scroll view.
	tui.setLayoutRoot(
		new VStack([
			{
				component: new ScrollView(transcript, { follow: "end", primary: true }),
				basis: 0,
				grow: 1,
				shrink: 1,
				minSize: 1,
			},
			{ component: loader, basis: "auto", grow: 0, shrink: 0, minSize: 1 },
		]),
	);
	tui.start();
	loader.start();
	try {
		await terminal.waitForRender();
		const shows = (frame: string) => terminal.getViewport().some((row) => row.trim() === `${frame} Working`);
		assert.ok(shows("A"));

		// A wheel scroll asks for a frame (a view change); the spinner steps before that frame is drawn.
		tui.scrollBy(-1);
		(loader as unknown as { currentFrame: number }).currentFrame = 1;
		(loader as unknown as { updateDisplay(delay: number): void }).updateDisplay(40);
		await terminal.waitForRender();
		await new Promise((resolve) => setTimeout(resolve, 80));
		await terminal.flush();
		assert.ok(shows("B"), `the step is drawn: ${JSON.stringify(terminal.getViewport())}`);
	} finally {
		loader.stop();
		tui.stop();
	}
});
