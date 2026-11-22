import {
	Box,
	type Component,
	Container,
	Image,
	MouseRegion,
	resetCapabilitiesCache,
	Spacer,
	setCapabilities,
	Text,
	type TUI,
	TuiAltScreen,
	type TuiMouseEvent,
} from "@earendil-works/pi-tui";
import { Type } from "typebox";
import { afterEach, beforeAll, describe, expect, test, vi } from "vitest";
import { VirtualTerminal } from "../../tui/test/virtual-terminal.ts";
import type { ToolDefinition } from "../src/core/extensions/types.ts";
import { withBuiltInRenderers } from "../src/core/tools/renderers/index.ts";
import { ToolExecutionComponent } from "../src/modes/interactive/components/tool-execution.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";
import { stripAnsi } from "../src/utils/ansi.ts";

// #339: Pi 1.6.0's ToolExecutionComponent members that extensions read and patch (pi-tidy frames every tool row through
// them). Each must hand out the row's live parts, as upstream: what an extension changes in them is what the row draws.

/** The members as an extension reads them: private upstream, reached through a cast. */
interface UpstreamToolRow {
	hasRendererDefinition(): boolean;
	getRenderShell(): "default" | "self";
	contentBox: Box;
	contentText: Text;
	contentTextRegion: MouseRegion;
	selfRenderContainer: Container;
	imageComponents: readonly Image[];
	imageSpacers: readonly Spacer[];
	expanded: boolean;
	hideComponent: boolean;
}

const upstream = (row: ToolExecutionComponent) => row as unknown as UpstreamToolRow;

// SAFETY: ToolExecutionComponent only calls ui.requestRender(), which this stub implements.
const ui = { requestRender() {} } as TUI;

const PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

/** A tool whose result says whether it is expanded, drawn in `renderShell`. */
function definition(renderShell?: "default" | "self"): ToolDefinition {
	return {
		name: "probe",
		label: "probe",
		description: "probe",
		parameters: Type.Any(),
		execute: async () => ({ content: [{ type: "text", text: "ok" }], details: {} }),
		renderShell,
		renderCall: () => new Text("probe call", 0, 0),
		renderResult: (_result, options) => new Text(options.expanded ? "result expanded" : "result collapsed", 0, 0),
	};
}

function row(toolDefinition: ToolDefinition | undefined): ToolExecutionComponent {
	const component = new ToolExecutionComponent("probe", "call-1", { path: "a.txt" }, {}, toolDefinition, ui, "/tmp");
	component.updateResult({ content: [{ type: "text", text: "line 1\nline 2" }], isError: false });
	return component;
}

function mouse(type: TuiMouseEvent["type"], y: number, width: number, height: number): TuiMouseEvent {
	return {
		type,
		button: type === "move" ? "none" : "left",
		x: 2,
		y,
		screenX: 2,
		screenY: y,
		width,
		height,
		shift: false,
		alt: false,
		ctrl: false,
		clickCount: 1,
	};
}

const text = (lines: readonly string[]) => stripAnsi(lines.join("\n"));

/** pi-tidy's mouseTarget(): the component a tool row routes its clicks through. */
function mouseTarget(tool: UpstreamToolRow): Component {
	if (!tool.hasRendererDefinition()) return tool.contentTextRegion;
	return tool.getRenderShell() === "self" ? tool.selfRenderContainer : tool.contentBox;
}

describe("ToolExecutionComponent upstream surface (#339)", () => {
	beforeAll(() => {
		initTheme("dark");
	});
	afterEach(() => {
		resetCapabilitiesCache();
	});

	// #339
	test("a default-shell row hands out its mounted Box, and draws what an extension adds to it", () => {
		const component = row(definition());
		const tool = upstream(component);
		expect(tool.hasRendererDefinition()).toBe(true);
		expect(tool.getRenderShell()).toBe("default");
		expect(tool.contentBox).toBeInstanceOf(Box);
		expect(tool.contentBox).toBe(tool.contentBox);
		expect(component.children).toContain(tool.contentBox);

		// The row is the spacer above the Box and the Box: an extension that frames the Box's rows draws the row's body.
		const width = 60;
		expect(component.render(width).slice(1)).toEqual(tool.contentBox.render(width));
		tool.contentBox.addChild(new Text("added by an extension", 0, 0));
		expect(text(component.render(width))).toContain("added by an extension");

		// A click routed into the Box at its own coordinates reaches the result's region, which expands it.
		const lines = tool.contentBox.render(width);
		const resultRow = lines.findIndex((line) => stripAnsi(line).includes("result collapsed"));
		expect(resultRow).toBeGreaterThan(0);
		expect(tool.contentBox.handleMouse(mouse("click", resultRow, width, lines.length))?.handled).toBe(true);
		expect(tool.expanded).toBe(true);
		expect(text(component.render(width))).toContain("result expanded");
	});

	// #339
	test("a self-rendered row hands out its mounted Container, and draws what an extension adds to it", () => {
		const component = row(definition("self"));
		const tool = upstream(component);
		expect(tool.hasRendererDefinition()).toBe(true);
		expect(tool.getRenderShell()).toBe("self");
		expect(tool.selfRenderContainer).toBeInstanceOf(Container);
		expect(component.children).toContain(tool.selfRenderContainer);

		const width = 60;
		expect(component.render(width)).toEqual(["", ...tool.selfRenderContainer.render(width)]);
		tool.selfRenderContainer.addChild(new Text("added by an extension", 0, 0));
		const lines = component.render(width);
		expect(text(lines)).toContain("added by an extension");
		expect(lines).toEqual(["", ...tool.selfRenderContainer.render(width)]);
	});

	// #339
	test("a row without renderers hands out its mounted Text and the MouseRegion around it", () => {
		const component = row(undefined);
		const tool = upstream(component);
		expect(tool.hasRendererDefinition()).toBe(false);
		expect(tool.getRenderShell()).toBe("default");
		expect(tool.contentText).toBeInstanceOf(Text);
		expect(tool.contentTextRegion).toBeInstanceOf(MouseRegion);
		expect(component.children).toContain(tool.contentTextRegion);
		expect(text(tool.contentTextRegion.render(60))).toContain("line 2");

		const width = 60;
		expect(component.render(width).slice(1)).toEqual(tool.contentTextRegion.render(width));
		tool.contentText.setText("replaced by an extension");
		expect(text(component.render(width))).toContain("replaced by an extension");

		// A left click on the region toggles the row, as upstream's region does.
		expect(tool.contentTextRegion.handleMouse?.(mouse("click", 1, width, 3))).toEqual({ handled: true });
		expect(tool.expanded).toBe(true);
	});

	// #339
	test("the shell parts a row does not use exist, keep their identity, and are never drawn", () => {
		const withRenderers = row(definition());
		const tool = upstream(withRenderers);
		const width = 60;
		const before = withRenderers.render(width).slice();
		expect(tool.contentText).toBeInstanceOf(Text);
		expect(tool.contentText).toBe(tool.contentText);
		expect(tool.contentTextRegion).toBeInstanceOf(MouseRegion);
		expect(tool.contentTextRegion).toBe(tool.contentTextRegion);
		expect(tool.selfRenderContainer).toBeInstanceOf(Container);
		expect(tool.selfRenderContainer).toBe(tool.selfRenderContainer);
		tool.contentText.setText("never drawn");
		tool.selfRenderContainer.addChild(new Text("never drawn either", 0, 0));
		expect(withRenderers.children).not.toContain(tool.contentTextRegion);
		expect(withRenderers.children).not.toContain(tool.selfRenderContainer);
		expect(withRenderers.render(width)).toEqual(before);

		const fallback = upstream(row(undefined));
		expect(fallback.contentBox).toBeInstanceOf(Box);
		expect(fallback.contentBox).toBe(fallback.contentBox);
		expect(fallback.selfRenderContainer).toBeInstanceOf(Container);
		expect(fallback.hideComponent).toBe(false);
	});

	// #339
	test("imageComponents and imageSpacers list the row's mounted images", () => {
		setCapabilities({ images: "kitty", trueColor: true, hyperlinks: true });
		const component = row(definition());
		const tool = upstream(component);
		expect(tool.imageComponents).toHaveLength(0);
		expect(tool.imageSpacers).toHaveLength(0);
		component.updateResult({
			content: [
				{ type: "image", data: PNG, mimeType: "image/png" },
				{ type: "image", data: PNG, mimeType: "image/png" },
			],
			isError: false,
		});
		expect(tool.imageComponents).toHaveLength(2);
		expect(tool.imageSpacers).toHaveLength(2);
		for (let i = 0; i < 2; i++) {
			expect(tool.imageComponents[i]).toBeInstanceOf(Image);
			expect(tool.imageSpacers[i]).toBeInstanceOf(Spacer);
			const children = component.children;
			expect(children.indexOf(tool.imageComponents[i]!)).toBe(children.indexOf(tool.imageSpacers[i]!) + 1);
		}
	});

	// #339
	test("a compacted row makes its components again when an extension reads them", () => {
		const component = new ToolExecutionComponent(
			"bash",
			"call-1",
			{ command: "seq 1 3" },
			{ compactable: true },
			withBuiltInRenderers("bash", undefined),
			ui,
			"/tmp",
		);
		component.updateResult({ content: [{ type: "text", text: "1\n2\n3" }], isError: false, durationMs: 5 });
		const width = 60;
		const before = component.render(width).slice();
		expect(component.compact()).toBe(true);
		const tool = upstream(component);
		const box = tool.contentBox;
		expect(component.isCompacted()).toBe(false);
		expect(component.children).toContain(box);
		expect(component.render(width)).toEqual(before);
		box.addChild(new Text("added by an extension", 0, 0));
		expect(text(component.render(width))).toContain("added by an extension");
	});

	// #339: the crash of the issue, through the real mouse path: a terminal's click, the alt screen's dispatch, and a
	// handleMouse patched as pi-tidy patches it.
	test.each([
		["default", definition()],
		["self", definition("self")],
	] as const)("a click on a %s-shell row reaches the part pi-tidy routes it to", async (_shell, toolDefinition) => {
		const component = row(toolDefinition);
		const proto = ToolExecutionComponent.prototype as unknown as { handleMouse(event: TuiMouseEvent): unknown };
		const original = proto.handleMouse;
		const seen: string[] = [];
		proto.handleMouse = function (this: ToolExecutionComponent, event: TuiMouseEvent) {
			seen.push(event.type);
			const target = mouseTarget(upstream(this));
			// The rows under the spacer are the target's.
			return event.y >= 1 ? target.handleMouse?.({ ...event, y: event.y - 1 }) : undefined;
		};
		const terminal = new VirtualTerminal(60, 12);
		const tui = new TuiAltScreen(terminal);
		try {
			tui.addChild(component);
			tui.start();
			await terminal.waitForRender();
			const screen = terminal.getViewport();
			const y = screen.findIndex((line) => line.includes("result collapsed"));
			expect(y).toBeGreaterThan(0);
			const col = screen[y]!.indexOf("result collapsed") + 1;
			terminal.sendInput(`\x1b[<35;${col};${y + 1}M`);
			terminal.sendInput(`\x1b[<0;${col};${y + 1}M`);
			terminal.sendInput(`\x1b[<0;${col};${y + 1}m`);
			await terminal.waitForRender();
			expect(seen).toContain("move");
			expect(seen).toContain("click");
			expect(upstream(component).expanded).toBe(true);
			expect(terminal.getViewport().join("\n")).toContain("result expanded");
		} finally {
			tui.stop();
			proto.handleMouse = original;
		}
	});
});

describe("ToolExecutionComponent upstream fields assigned (#339)", () => {
	beforeAll(() => {
		initTheme("dark");
	});
	afterEach(() => {
		resetCapabilitiesCache();
	});

	// #339: upstream mounts its Box once and fills whatever the field holds.
	test("an assigned contentBox is filled by the next updates; the row keeps drawing the mounted Box", () => {
		const component = row(definition());
		const tool = upstream(component);
		const width = 60;
		const mounted = tool.contentBox;
		const before = component.render(width).slice();
		const replacement = new Box(1, 1);
		tool.contentBox = replacement;
		expect(tool.contentBox).toBe(replacement);
		expect(component.children).toContain(mounted);
		expect(component.children).not.toContain(replacement);

		component.setExpanded(true);
		expect(text(replacement.render(width))).toContain("result expanded");
		expect(component.render(width)).toEqual(before);
		expect(text(component.render(width))).toContain("result collapsed");
	});

	// #339: upstream sets background and padding only on a Box, whichever shell the row uses.
	test("a Container assigned to contentBox is filled without a background; the row's updates do not throw", () => {
		const component = row(definition());
		const tool = upstream(component);
		const replacement = new Container();
		(tool as unknown as { contentBox: Container }).contentBox = replacement;
		expect(() => component.setExpanded(true)).not.toThrow();
		expect(() => component.invalidate()).not.toThrow();
		expect(text(replacement.render(60))).toContain("result expanded");
	});

	// #339
	test("a Box assigned to selfRenderContainer gets the row's background and padding", () => {
		const component = row(definition("self"));
		const tool = upstream(component);
		const replacement = new Box(0, 0);
		const setBgFn = vi.spyOn(replacement, "setBgFn");
		const setPaddingX = vi.spyOn(replacement, "setPaddingX");
		(tool as unknown as { selfRenderContainer: Box }).selfRenderContainer = replacement;
		component.setOutputPad(2);
		expect(setBgFn).toHaveBeenCalledTimes(1);
		expect(setPaddingX).toHaveBeenCalledWith(2);
		const lines = component.render(60);
		expect(lines).toEqual(["", ...replacement.render(60)]);
		expect(stripAnsi(lines.find((line) => stripAnsi(line).includes("probe call"))!)).toMatch(/^ {2}probe call/);
	});

	// #339
	test("a row with an assigned part keeps it: compact() leaves it as it is", () => {
		const component = new ToolExecutionComponent(
			"bash",
			"call-1",
			{ command: "seq 1 3" },
			{ compactable: true },
			withBuiltInRenderers("bash", undefined),
			ui,
			"/tmp",
		);
		component.updateResult({ content: [{ type: "text", text: "1\n2\n3" }], isError: false, durationMs: 5 });
		const replacement = new Box(1, 1);
		upstream(component).contentBox = replacement;
		component.render(60);
		expect(component.compact()).toBe(false);
		expect(upstream(component).contentBox).toBe(replacement);
	});

	// #339
	test("an assigned contentText takes the next updates; the row keeps drawing the mounted Text", () => {
		const component = row(undefined);
		const tool = upstream(component);
		const width = 60;
		const replacement = new Text("", 1, 1);
		tool.contentText = replacement;
		expect(tool.contentText).toBe(replacement);
		component.updateResult({ content: [{ type: "text", text: "newer output" }], isError: false });
		expect(text(replacement.render(width))).toContain("newer output");
		const drawn = text(component.render(width));
		expect(drawn).toContain("line 2");
		expect(drawn).not.toContain("newer output");
	});

	// #339: upstream never reads the field after construction.
	test("an assigned contentTextRegion is what the field reads; the row draws and routes as before", () => {
		const component = row(undefined);
		const tool = upstream(component);
		const width = 60;
		const mounted = tool.contentTextRegion;
		const before = component.render(width).slice();
		const replacement = new MouseRegion(new Text("never drawn", 0, 0), () => undefined);
		tool.contentTextRegion = replacement;
		expect(tool.contentTextRegion).toBe(replacement);
		expect(component.children).toContain(mounted);
		expect(component.render(width)).toEqual(before);
		const result = component.handleMouse(mouse("click", 2, width, before.length));
		expect(result?.handled).toBe(true);
		expect(result && "target" in result ? result.target?.component : undefined).toBe(mounted);
		expect(tool.expanded).toBe(true);
	});

	// #339: upstream draws a self-rendered row from the field.
	test("an assigned selfRenderContainer is drawn, clicked and filled by the next updates", () => {
		const component = row(definition("self"));
		const tool = upstream(component);
		const width = 60;
		component.render(width);
		const replacement = new Container();
		replacement.addChild(new Text("drawn by the extension", 0, 0));
		tool.selfRenderContainer = replacement;
		expect(tool.selfRenderContainer).toBe(replacement);
		let lines = component.render(width);
		expect(lines).toEqual(["", ...replacement.render(width)]);
		expect(text(lines)).not.toContain("probe call");

		component.invalidate();
		lines = component.render(width);
		expect(text(replacement.render(width))).toContain("result collapsed");
		expect(lines).toEqual(["", ...replacement.render(width)]);
		const resultRow = lines.findIndex((line) => stripAnsi(line).includes("result collapsed"));
		expect(component.handleMouse(mouse("click", resultRow, width, lines.length))?.handled).toBe(true);
		expect(text(component.render(width))).toContain("result expanded");
	});

	// #339
	test("assigned parts a row does not use are what the fields read, and are not drawn", () => {
		const component = row(definition());
		const tool = upstream(component);
		const width = 60;
		const before = component.render(width).slice();
		const text1 = new Text("unused text", 0, 0);
		const container = new Container();
		container.addChild(new Text("unused container", 0, 0));
		tool.contentText = text1;
		tool.selfRenderContainer = container;
		expect(tool.contentText).toBe(text1);
		expect(tool.selfRenderContainer).toBe(container);
		expect(component.render(width)).toEqual(before);
	});

	// #339: upstream draws a self-rendered row's images from the fields, and makes them again at the next update.
	test("assigned image arrays are drawn by a self-rendered row until the next update", () => {
		setCapabilities({ images: "kitty", trueColor: true, hyperlinks: true });
		const component = row(definition("self"));
		const tool = upstream(component);
		const width = 60;
		const before = component.render(width).slice();
		const picture = new Text("an extension's picture", 0, 0) as unknown as Image;
		const gap = new Text("an extension's gap", 0, 0) as unknown as Spacer;
		tool.imageComponents = [picture];
		tool.imageSpacers = [gap];
		expect(tool.imageComponents).toEqual([picture]);
		expect(tool.imageSpacers).toEqual([gap]);
		const lines = component.render(width);
		expect(lines.slice(0, before.length)).toEqual(before);
		expect(lines.slice(before.length).map((line) => stripAnsi(line).trimEnd())).toEqual([
			"an extension's gap",
			"an extension's picture",
		]);

		component.updateResult({ content: [{ type: "image", data: PNG, mimeType: "image/png" }], isError: false });
		expect(tool.imageComponents).toHaveLength(1);
		expect(tool.imageComponents[0]).toBeInstanceOf(Image);
		expect(text(component.render(width))).not.toContain("an extension's");
	});

	// #339: a default-shell row draws its mounted children, as upstream.
	test("assigned image arrays are not drawn by a default-shell row", () => {
		const component = row(definition());
		const tool = upstream(component);
		const width = 60;
		const before = component.render(width).slice();
		tool.imageComponents = [new Text("not drawn", 0, 0) as unknown as Image];
		expect(component.render(width)).toEqual(before);
	});
});
