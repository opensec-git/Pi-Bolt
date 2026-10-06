import { Container, setKeybindings, TuiMainScreen } from "@earendil-works/pi-tui";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { VirtualTerminal } from "../../tui/test/virtual-terminal.ts";
import { KeybindingsManager } from "../src/core/keybindings.ts";
import { SettingsManager } from "../src/core/settings-manager.ts";
import { InteractiveMode } from "../src/modes/interactive/interactive-mode.ts";
import { initTheme, setTerminalColorScheme, setTerminalColors, theme } from "../src/modes/interactive/theme/theme.ts";
import { InteractiveThemeController } from "../src/modes/interactive/theme/theme-controller.ts";

// The terminal's answer to the color query: default foreground and background, then the DA1 reply that ends it.
const FOREGROUND_REPLY = "\x1b]10;rgb:f8f8/f8f8/f2f2\x07";
const BACKGROUND_REPLY = "\x1b]11;rgb:2828/2a2a/3636\x07";
const DA1_REPLY = "\x1b[?62;22c";
// Text from the built-in header.
const HEADER_TEXT = "Pi can explain its own features";

type StartupHeader = {
	terminal: VirtualTerminal;
	themeController: InteractiveThemeController;
	/** Settles when the startup step settles: the colors arrived or the query timed out. */
	done: Promise<void>;
	settled: () => boolean;
	answer: () => void;
	screen: () => string;
	stop: () => void;
};

/**
 * Run the startup step that applies the theme, queries the terminal's colors, and adds the header, on a real
 * TUI whose terminal answers only when the test says so.
 */
function startStartupHeader(themeSetting: string | undefined): StartupHeader {
	const terminal = new VirtualTerminal(100, 30);
	const ui = new TuiMainScreen(terminal);
	const headerContainer = new Container();
	ui.addChild(headerContainer);
	const settingsManager = SettingsManager.inMemory(themeSetting === undefined ? {} : { theme: themeSetting });
	const themeController = new InteractiveThemeController(ui, {
		getSettingsManager: () => settingsManager,
		showError: vi.fn(),
		onChanged: vi.fn(),
	});
	const context = Object.assign(Object.create(InteractiveMode.prototype), {
		ui,
		renderer: ui,
		headerContainer,
		runtimeHost: { session: { settingsManager } },
		themeController,
		options: {},
		version: "9.9.9",
		toolOutputExpanded: false,
	});
	const { applyThemeAndShowStartupHeader } = InteractiveMode.prototype as unknown as {
		applyThemeAndShowStartupHeader(this: unknown): Promise<void>;
	};

	// As in InteractiveMode.init(): the UI starts, then the theme applies and the header goes up.
	ui.start();
	let settled = false;
	const done = applyThemeAndShowStartupHeader.call(context).then(() => {
		settled = true;
	});
	return {
		terminal,
		themeController,
		done,
		settled: () => settled,
		answer: () => {
			terminal.sendInput(FOREGROUND_REPLY);
			terminal.sendInput(BACKGROUND_REPLY);
			terminal.sendInput(DA1_REPLY);
		},
		screen: () => terminal.getViewport().join("\n"),
		stop: () => {
			themeController.dispose();
			ui.stop();
		},
	};
}

describe("startup header and the terminal color query", () => {
	let running: StartupHeader | undefined;
	const start = (themeSetting: string | undefined) => {
		running = startStartupHeader(themeSetting);
		return running;
	};

	beforeEach(() => {
		vi.stubEnv("COLORFGBG", "");
		setKeybindings(new KeybindingsManager());
	});

	afterEach(() => {
		running?.stop();
		running = undefined;
		setTerminalColors({});
		setTerminalColorScheme(undefined);
		initTheme("dark");
		vi.unstubAllEnvs();
	});

	it("draws the header in the first frame for a theme that does not use the terminal's colors", async () => {
		const startup = start("dark");
		await startup.terminal.waitForRender();

		// The terminal has not answered: the header is on screen, and startup still waits for the colors.
		expect(startup.screen()).toContain(HEADER_TEXT);
		expect(startup.settled()).toBe(false);
		const firstFrame = startup.terminal.getStyledViewport();
		const themeBeforeReply = theme;

		startup.answer();
		await startup.done;
		await startup.terminal.waitForRender();

		// Startup settled on the reply, after applying it; the theme and everything on screen stayed the same.
		expect(theme).toBe(themeBeforeReply);
		expect(startup.terminal.getStyledViewport()).toEqual(firstFrame);
	});

	it("waits for the colors before drawing the header with the system theme", async () => {
		const startup = start(undefined);
		await startup.terminal.waitForRender();

		// The system theme is grayscale until the terminal answers, so the header is not drawn yet.
		expect(startup.themeController.dependsOnTerminalColors()).toBe(true);
		expect(startup.screen()).not.toContain(HEADER_TEXT);
		expect(startup.settled()).toBe(false);

		startup.answer();
		await startup.done;
		await startup.terminal.waitForRender();

		// The header appears with the theme generated from the reported colors.
		expect(startup.screen()).toContain(HEADER_TEXT);
		expect(theme.name).toBe("system");
		expect(theme.getFgAnsi("error")).toMatch(/^\x1b\[38;2;/);
	});

	it("waits for the colors before drawing the header with a theme pair", async () => {
		const startup = start("light/dark");
		await startup.terminal.waitForRender();
		expect(startup.screen()).not.toContain(HEADER_TEXT);

		startup.answer();
		await startup.done;
		await startup.terminal.waitForRender();

		// The reported dark background picked the dark theme before the header was drawn.
		expect(theme.name).toBe("dark");
		expect(startup.screen()).toContain(HEADER_TEXT);
	});

	it("keeps the header when the reply is lost, and settles at the timeout", async () => {
		const startup = start("dark");
		await startup.terminal.waitForRender();
		expect(startup.screen()).toContain(HEADER_TEXT);
		const firstFrame = startup.terminal.getStyledViewport();

		// No reply: startup goes on once the query times out, and the screen stays as it was.
		await startup.done;
		await startup.terminal.waitForRender();
		expect(startup.terminal.getStyledViewport()).toEqual(firstFrame);
	});

	it("draws the system theme's header at the timeout when the reply is lost, then recolors it when the reply arrives late", async () => {
		const startup = start(undefined);
		await startup.done;
		await startup.terminal.waitForRender();

		// Timed out: the header is drawn with palette indices.
		expect(startup.screen()).toContain(HEADER_TEXT);
		expect(theme.getFgAnsi("error")).toBe("\x1b[38;5;1m");
		const beforeLateReply = startup.terminal.getStyledViewport();

		startup.answer();
		await startup.terminal.waitForRender();

		// The late reply regenerated the theme from the reported colors and re-rendered the header.
		expect(theme.getFgAnsi("error")).toMatch(/^\x1b\[38;2;/);
		expect(startup.screen()).toContain(HEADER_TEXT);
		expect(startup.terminal.getStyledViewport()).not.toEqual(beforeLateReply);
	});
});
