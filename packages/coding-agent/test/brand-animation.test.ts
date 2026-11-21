import { setKeybindings, type TUI } from "@earendil-works/pi-tui";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { KeybindingsManager } from "../src/core/keybindings.ts";
import { OAuthSelectorComponent } from "../src/modes/interactive/components/oauth-selector.ts";
import { createLoginMenuSelector } from "../src/modes/interactive/components/radius-login-selector.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";

beforeEach(() => {
	initTheme("dark");
	setKeybindings(new KeybindingsManager());
	vi.useFakeTimers();
});
afterEach(() => vi.useRealTimers());

it("parks the provider animation when filtering or moving to an unbranded row", () => {
	const requestRender = vi.fn();
	const view = new OAuthSelectorComponent(
		"login",
		[
			{ id: "test", name: "Test", authType: "api_key" },
			{ id: "openrouter", name: "OpenRouter", authType: "oauth" },
		],
		() => {},
		() => {},
		undefined,
		{ requestRender } as unknown as TUI,
	);
	try {
		view.render(80);
		expect(vi.getTimerCount()).toBe(0);
		view.handleInput("\x1b[B");
		view.render(80);
		view.render(80);
		expect(vi.getTimerCount()).toBe(1);
		vi.advanceTimersByTime(150);
		expect(requestRender).toHaveBeenCalledTimes(3);
		view.handleInput("z");
		expect(view.render(80).join("\n")).toContain("No matching providers");
		expect(vi.getTimerCount()).toBe(0);
		view.handleInput("\x7f");
		view.handleInput("\x1b[B");
		view.render(80);
		expect(vi.getTimerCount()).toBe(1);
	} finally {
		view.dispose();
	}
	view.render(80);
	expect(vi.getTimerCount()).toBe(0);
});

it("stops the login menu timer when leaving Radius and cannot restart it after disposal", () => {
	const view = createLoginMenuSelector(
		{ requestRender: vi.fn() } as unknown as TUI,
		"Test login menu",
		["Account", "Radius"],
		{ label: "Radius", text: "Radius" },
		() => {},
		() => {},
	);
	try {
		view.render(80);
		expect(vi.getTimerCount()).toBe(0);
		view.handleInput("\x1b[B");
		view.render(80);
		expect(vi.getTimerCount()).toBe(1);
		view.handleInput("\x1b[A");
		view.render(80);
		expect(vi.getTimerCount()).toBe(0);
		view.handleInput("\x1b[B");
		view.render(80);
		expect(vi.getTimerCount()).toBe(1);
	} finally {
		view.dispose();
	}
	view.render(80);
	expect(vi.getTimerCount()).toBe(0);
});
