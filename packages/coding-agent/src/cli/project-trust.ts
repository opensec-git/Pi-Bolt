import type { ProjectTrustContext } from "../core/extensions/types.ts";
import type { AppMode } from "../core/project-trust.ts";
import type { SettingsManager } from "../core/settings-manager.ts";
import { chalk } from "../utils/lazy-packages.ts";
import { loadStartupUi } from "./command-modes.lazy.ts";

export function createProjectTrustContext(options: {
	cwd: string;
	mode: AppMode;
	settingsManager: SettingsManager;
	hasUI: boolean;
}): ProjectTrustContext {
	return {
		cwd: options.cwd,
		mode: options.mode === "interactive" ? "tui" : options.mode,
		hasUI: options.hasUI,
		ui: {
			select: async (title, selectOptions, opts) => {
				if (!options.hasUI) {
					return undefined;
				}
				if (options.mode !== "interactive") {
					return undefined;
				}
				const { showStartupSelector } = await loadStartupUi();
				return showStartupSelector(
					options.settingsManager,
					title,
					selectOptions.map((option) => ({ label: option, value: option })),
					opts?.blockedKind,
				);
			},
			confirm: async (title, message, opts) => {
				if (!options.hasUI) {
					return false;
				}
				if (options.mode !== "interactive") {
					return false;
				}
				const { showStartupSelector } = await loadStartupUi();
				return (
					(await showStartupSelector(
						options.settingsManager,
						`${title}\n${message}`,
						[
							{ label: "Yes", value: true },
							{ label: "No", value: false },
						],
						opts?.blockedKind ?? "permission",
					)) ?? false
				);
			},
			input: async (title, placeholder, opts) => {
				if (!options.hasUI) {
					return undefined;
				}
				if (options.mode !== "interactive") {
					return undefined;
				}
				const { showStartupInput } = await loadStartupUi();
				return showStartupInput(options.settingsManager, title, placeholder, opts?.blockedKind);
			},
			notify: (message, type = "info") => {
				if (options.mode !== "interactive") {
					const color = type === "error" ? chalk.red : type === "warning" ? chalk.yellow : chalk.cyan;
					console.error(color(message));
				}
			},
		},
	};
}
