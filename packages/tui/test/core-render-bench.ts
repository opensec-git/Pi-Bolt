/**
 * Run each case in a fresh process from the repository root:
 * node --expose-gc packages/tui/test/core-render-bench.ts plain|image|overlay|search|dense-search|query-change|wrap|long-word|keys
 * Heap includes the input and component tree. Time excludes setup and forced GC.
 */
import { performance } from "node:perf_hooks";
import { AltScreenSearchIndex } from "../src/alt-screen-search.ts";
import { Text } from "../src/components/text.ts";
import { StdinBuffer } from "../src/stdin-buffer.ts";
import { ProcessTerminal } from "../src/terminal.ts";
import { encodeKitty } from "../src/terminal-image.ts";
import { markReportsChanges, markStableRender } from "../src/tui.ts";
import { TuiMainScreen } from "../src/tui-main-screen.ts";
import { wrapTextWithAnsi } from "../src/utils.ts";

class History {
	readonly lines = Array.from({ length: 25_000 }, (_, i) => `history row ${i}`);
	render(): string[] {
		return this.lines;
	}
	invalidate(): void {}
}
markStableRender(History);
markReportsChanges(History);

class Output extends ProcessTerminal {
	characters = 0;
	override get columns(): number {
		return 100;
	}
	override get rows(): number {
		return 40;
	}
	override write(text: string): void {
		this.characters += text.length;
	}
	override showCursor(): void {}
	override hideCursor(): void {}
}

function measure(count: number, run: (iteration: number) => number): void {
	let checksum = 0;
	for (let i = 0; i < Math.min(10, count); i++) run(i);
	globalThis.gc?.();
	const heapBefore = process.memoryUsage().heapUsed;
	const durations: number[] = [];
	const cpuStart = process.cpuUsage();
	const start = performance.now();
	for (let i = 0; i < count; i++) {
		const before = performance.now();
		checksum += run(i);
		durations.push(performance.now() - before);
	}
	const elapsedMs = performance.now() - start;
	const cpu = process.cpuUsage(cpuStart);
	globalThis.gc?.();
	const heapAfter = process.memoryUsage().heapUsed;
	durations.sort((a, b) => a - b);
	console.log(
		JSON.stringify({
			scenario: process.argv[2],
			count,
			elapsedMs,
			cpuMs: (cpu.user + cpu.system) / 1000,
			p95Ms: durations[Math.ceil(count * 0.95) - 1],
			heapBefore,
			heapAfter,
			checksum,
		}),
	);
}

const scenario = process.argv[2];
const paragraph = "Terminal output spans multiple words and columns. ";
switch (scenario) {
	case "plain":
	case "image":
	case "overlay": {
		const history = new History();
		if (scenario === "image") history.lines[20] = encodeKitty("AAAA", { columns: 2, rows: 1, imageId: 19 });
		const terminal = new Output();
		const screen = new TuiMainScreen(terminal);
		const editor = new Text("", 0, 0);
		screen.addChild(history);
		screen.addChild(editor);
		if (scenario === "overlay") screen.showOverlay(new Text("Select model", 0, 0), { width: 25 });
		screen.renderNow();
		measure(600, (i) => {
			editor.setText(`edit ${i}`);
			const before = terminal.characters;
			screen.renderNow();
			return terminal.characters - before;
		});
		break;
	}
	case "search":
	case "dense-search": {
		const lines = Array.from({ length: 20_000 }, (_, i) => paragraph + i);
		const index = new AltScreenSearchIndex();
		measure(40, (i) => {
			lines[lines.length - 1] = `next search result ${i}`;
			return index.search(lines, scenario === "search" ? "search result" : "output spans").matches.length;
		});
		break;
	}
	case "query-change": {
		const lines = Array.from({ length: 20_000 }, (_, i) => paragraph + i);
		const index = new AltScreenSearchIndex();
		measure(12, (i) => index.search(lines, i % 2 ? "output spans" : "multiple words").matches.length);
		break;
	}
	case "wrap":
	case "long-word": {
		const line = scenario === "wrap" ? paragraph.repeat(200) : "x".repeat(32_000);
		measure(200, (i) => wrapTextWithAnsi(line, 80 + (i % 3)).length);
		break;
	}
	case "keys": {
		const input = new StdinBuffer();
		const keys = "abcdefgh\x1b[A\x1b[<0;5;3M\x1b[97u".repeat(100);
		let characters = 0;
		input.on("data", (key) => {
			characters += key.length;
		});
		measure(400, () => {
			const before = characters;
			input.process(keys);
			return characters - before;
		});
		input.destroy();
		break;
	}
	default:
		throw new Error("Expected plain, image, overlay, search, dense-search, query-change, wrap, long-word, or keys");
}
