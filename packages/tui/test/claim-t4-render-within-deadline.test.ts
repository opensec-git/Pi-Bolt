// claim T4 (second half): requestRenderWithin() with a shorter deadline than the one already pending waits for the
// longer one, so the change is drawn later than the frame "within `delayMs`" the TUI interface promises.
import assert from "node:assert";
import { it } from "node:test";
import { Text } from "../src/components/text.ts";
import { TuiMainScreen } from "../src/tui-main-screen.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

it("claim T4: a shorter requestRenderWithin() deadline is kept while a longer one is pending", async () => {
	const terminal = new VirtualTerminal(40, 6);
	const tui = new TuiMainScreen(terminal);
	const slow = new Text("slow 0", 0, 0);
	const fast = new Text("fast 0", 0, 0);
	tui.addChild(slow);
	tui.addChild(fast);
	tui.start();
	try {
		await terminal.waitForRender();
		await new Promise<void>((resolve) => setTimeout(resolve, 50));

		slow.setText("slow 1");
		tui.requestRenderWithin(2000);
		fast.setText("fast 1");
		tui.requestRenderWithin(20);

		await new Promise<void>((resolve) => setTimeout(resolve, 300));
		await terminal.waitForRender();
		assert.ok(
			terminal.getViewport().some((row) => row.trim() === "fast 1"),
			`the change asked for within 20 ms is drawn after 300 ms: ${JSON.stringify(terminal.getViewport())}`,
		);
	} finally {
		tui.stop();
	}
});
