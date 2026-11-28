// claim T2: in the main screen, an overlay over a Kitty image whose line is above the viewport strips the image from the
// kept frame lines, and the image is not drawn again when the overlay closes.
import assert from "node:assert";
import { describe, it } from "node:test";
import {
	deleteKittyImage,
	encodeKitty,
	registerKittyImageMetadata,
	resetCapabilitiesCache,
	setCapabilities,
} from "../src/terminal-image.ts";
import type { Component } from "../src/tui.ts";
import { TuiMainScreen } from "../src/tui-main-screen.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

class RecordingTerminal extends VirtualTerminal {
	writes = "";

	override write(data: string): void {
		this.writes += data;
		super.write(data);
	}
}

/** A frame waits for MIN_RENDER_INTERVAL_MS and the spacing of slow frames: wait past both. */
async function nextFrame(terminal: VirtualTerminal): Promise<void> {
	await new Promise<void>((resolve) => setTimeout(resolve, 100));
	await terminal.waitForRender();
}

function staticComponent(lines: string[]): Component {
	return { render: () => lines, invalidate: () => {} };
}

describe("claim T2: main-screen overlay over a Kitty image above the viewport", () => {
	it("draws the image again after the overlay closes", async () => {
		setCapabilities({ images: "kitty", trueColor: true, hyperlinks: true });
		const terminal = new RecordingTerminal(20, 6);
		const tui = new TuiMainScreen(terminal);
		try {
			const imageId = 13201;
			registerKittyImageMetadata({ imageId, columns: 4, rows: 4, widthPx: 40, heightPx: 40 });
			const image = encodeKitty("AAAA", { columns: 4, rows: 4, imageId, moveCursor: false });
			const lines: string[] = [];
			for (let i = 0; i < 10; i++) lines.push(`t${i}`);
			// Line 10 is the image line; the viewport (6 rows of 17 lines) starts at line 11, inside the image's rows.
			lines.push(image, "", "", "", "a", "b", "c");
			tui.addChild(staticComponent(lines));
			tui.start();
			await terminal.waitForRender();

			const panel = staticComponent(["PANEL"]);
			const overlay = tui.showOverlay(panel, { row: 0, col: 0, width: 8 });
			await nextFrame(terminal);

			terminal.writes = "";
			overlay.hide();
			await nextFrame(terminal);
			const lastDelete = terminal.writes.lastIndexOf(deleteKittyImage(imageId));
			const lastDraw = terminal.writes.lastIndexOf(image);
			assert.ok(lastDraw !== -1 && lastDraw > lastDelete, "the image is drawn again once uncovered");
		} finally {
			tui.stop();
			resetCapabilitiesCache();
		}
	});
});
