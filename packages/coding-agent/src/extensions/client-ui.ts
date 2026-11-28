/**
 * The extension UI of a headless mode whose client answers dialogs (ACP and MCP mode): the dialogs and notices go to the
 * client, and what needs Pi's terminal does nothing.
 */

import type { ExtensionUIDialogOptions, ExtensionUIHost } from "../core/extensions/index.ts";
import { theme } from "../modes/interactive/theme/theme.ts";

export type ClientDialogs = Pick<ExtensionUIHost, "select" | "confirm" | "input" | "editor" | "notify">;

export function clientUIContext(dialogs: ClientDialogs): ExtensionUIHost {
	return {
		...dialogs,
		onTerminalInput: () => () => {},
		setStatus: () => {},
		setWorkingMessage: () => {},
		setWorkingVisible: () => {},
		setWorkingIndicator: () => {},
		setHiddenThinkingLabel: () => {},
		setWidget: () => {},
		setFooter: () => {},
		setHeader: () => {},
		setTitle: () => {},
		custom: async () => undefined as never,
		pasteToEditor: () => {},
		setEditorText: () => {},
		getEditorText: () => "",
		addAutocompleteProvider: () => {},
		setEditorComponent: () => {},
		getEditorComponent: () => undefined,
		get theme() {
			return theme;
		},
		getAllThemes: () => [],
		getTheme: () => undefined,
		setTheme: () => ({ success: false, error: "Themes do not apply without Pi's terminal UI" }),
		getToolsExpanded: () => false,
		setToolsExpanded: () => {},
	};
}

/**
 * The client's answer to a dialog, or undefined when the request fails, or when the dialog's signal or `run` (the
 * prompt the dialog belongs to) aborts or its timeout passes first. `request` gets the signal that ends the wait, and
 * drops the request when it aborts.
 */
export async function settleWithin<T>(
	request: (signal: AbortSignal | undefined) => Promise<T>,
	opts: ExtensionUIDialogOptions | undefined,
	run?: AbortSignal,
): Promise<T | undefined> {
	const timeout = opts?.timeout ? new AbortController() : undefined;
	const timer = timeout ? setTimeout(() => timeout.abort(), opts?.timeout) : undefined;
	const signals = [opts?.signal, run, timeout?.signal].filter((signal) => signal !== undefined);
	try {
		return await request(signals.length > 1 ? AbortSignal.any(signals) : signals[0]);
	} catch {
		return undefined;
	} finally {
		clearTimeout(timer);
	}
}
