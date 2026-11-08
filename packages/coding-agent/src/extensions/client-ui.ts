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

/** The client's answer, or undefined when the dialog's signal aborts or its timeout passes first. */
export async function settleWithin<T>(
	request: Promise<T>,
	opts: ExtensionUIDialogOptions | undefined,
): Promise<T | undefined> {
	const guarded = request.catch(() => undefined);
	if (!opts?.signal && !opts?.timeout) return guarded;
	return new Promise<T | undefined>((resolve) => {
		let timer: ReturnType<typeof setTimeout> | undefined;
		const finish = (value: T | undefined) => {
			clearTimeout(timer);
			opts.signal?.removeEventListener("abort", onAbort);
			resolve(value);
		};
		const onAbort = () => finish(undefined);
		opts.signal?.addEventListener("abort", onAbort, { once: true });
		if (opts.timeout) timer = setTimeout(() => finish(undefined), opts.timeout);
		void guarded.then(finish);
	});
}
