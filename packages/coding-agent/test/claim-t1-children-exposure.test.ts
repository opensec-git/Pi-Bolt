// claim T1: internal reads of Container.children (the TUI's mount check) mark the containers they walk as exposed and, for
// the fullscreen transcript, build every deferred row (TranscriptContainer.completeNow()).
import {
	type Component,
	Container,
	type Focusable,
	ScrollView,
	Text,
	TuiAltScreen,
	VStack,
} from "@earendil-works/pi-tui";
import { describe, expect, test } from "vitest";
import { VirtualTerminal } from "../../tui/test/virtual-terminal.ts";
import { TranscriptContainer } from "../src/modes/interactive/components/transcript-container.ts";

const WIDTH = 80;
const ROWS = 24;

class Focus implements Component, Focusable {
	focused = false;
	private readonly label: string;
	constructor(label: string) {
		this.label = label;
	}
	render(): string[] {
		return [this.label];
	}
	handleInput(): void {}
	invalidate(): void {}
}

/** A transcript row that is a Container (as the message components are), counting its renders. */
class RowContainer extends Container {
	renders = 0;
	protected override renderLiveChildren(width: number) {
		this.renders++;
		return super.renderLiveChildren(width);
	}
}

async function flush(tui: TuiAltScreen, terminal: VirtualTerminal): Promise<void> {
	tui.requestRender();
	await new Promise<void>((resolve) => setTimeout(resolve, 30));
	await terminal.waitForRender();
}

describe("claim T1: the overlay focus mount check walks Container.children", () => {
	test("returning focus from a dialog opened over an overlay keeps the fullscreen transcript lazy", async () => {
		const terminal = new VirtualTerminal(WIDTH, ROWS);
		const tui = new TuiAltScreen(terminal);
		const transcript = new TranscriptContainer();
		const built: number[] = [];
		const total = 2000;
		for (let i = 0; i < total; i++) {
			transcript.addDeferredChild(() => {
				built.push(i);
				return new Text(`row ${i}`, 0, 0);
			});
		}
		const document = new Container();
		document.addChild(transcript);
		const editor = new Focus("editor");
		const editorContainer = new Container();
		editorContainer.addChild(editor);
		const root = new VStack([
			{ component: new ScrollView(document, { follow: "end", primary: true }), basis: 0, grow: 1, shrink: 1 },
			{ component: editorContainer, basis: "auto", grow: 0, shrink: 0 },
		]);
		tui.setLayoutRoot(root);
		transcript.renderEndFirst(() => ROWS + 1);
		tui.beforeDocumentRead = () => transcript.completeNow();
		tui.setFocus(editor);
		tui.start();
		try {
			await flush(tui, terminal);
			expect(transcript.isHoldingBack).toBe(true);
			const builtBefore = built.length;

			// An extension overlay takes focus; a dialog then replaces the editor; closing it gives focus back to the editor.
			const overlay = new Focus("overlay");
			tui.showOverlay(overlay);
			const dialog = new Focus("dialog");
			editorContainer.clear();
			editorContainer.addChild(dialog);
			tui.setFocus(dialog);
			editorContainer.clear();
			editorContainer.addChild(editor);
			tui.setFocus(editor);

			expect(built.length).toBe(builtBefore);
			expect(transcript.isHoldingBack).toBe(true);
		} finally {
			tui.stop();
		}
	});

	test("after the mount check, unchanged transcript rows are not rendered again on every frame", async () => {
		const terminal = new VirtualTerminal(WIDTH, ROWS);
		const tui = new TuiAltScreen(terminal);
		const rows: RowContainer[] = [];
		const transcript = new Container();
		for (let i = 0; i < 40; i++) {
			const row = new RowContainer();
			row.addChild(new Text(`row ${i}`, 0, 0));
			rows.push(row);
			transcript.addChild(row);
		}
		const editor = new Focus("editor");
		const editorContainer = new Container();
		editorContainer.addChild(editor);
		const status = new Text("status", 0, 0);
		const root = new VStack([
			{ component: new ScrollView(transcript, { follow: "end", primary: true }), basis: 0, grow: 1, shrink: 1 },
			{ component: status, basis: "auto", grow: 0, shrink: 0 },
			{ component: editorContainer, basis: "auto", grow: 0, shrink: 0 },
		]);
		tui.setLayoutRoot(root);
		tui.setFocus(editor);
		tui.start();
		try {
			await flush(tui, terminal);
			const frame = async (label: string) => {
				status.setText(label);
				await flush(tui, terminal);
			};
			await frame("a");
			const before = rows[0]!.renders;
			await frame("b");
			expect(rows[0]!.renders).toBe(before);

			const overlay = new Focus("overlay");
			const handle = tui.showOverlay(overlay);
			const dialog = new Focus("dialog");
			tui.setFocus(dialog);
			tui.setFocus(editor);
			handle.hide();
			await frame("c");

			const after = rows[0]!.renders;
			await frame("d");
			await frame("e");
			expect(rows[0]!.renders).toBe(after);
		} finally {
			tui.stop();
		}
	});
});
