/**
 * `pi-bolt remote-control enable|disable|status`: turns remote control (`/remote-control` and the background service) on
 * or off. It is the `remoteControl.enabled` setting in the global settings, the only ones that can turn it on.
 */

import { COMMAND_NAME } from "../config.ts";
import { SettingsManager } from "../core/settings-manager.ts";
import { REMOTE_CONTROL_OFF_MESSAGE } from "../core/slash-commands.ts";

const USAGE = [
	`Usage: ${COMMAND_NAME} remote-control <command>`,
	"",
	"Remote control: control sessions from the Pi-Bolt phone app (/remote-control, and the background service of",
	`${COMMAND_NAME} service). Off unless turned on here; it is the remoteControl.enabled setting in the global settings.`,
	"",
	"Commands:",
	"  enable    Turn it on",
	"  disable   Turn it off",
	"  status    Show whether it is on",
];

const ON_MESSAGE = `Remote control is on: /remote-control in ${COMMAND_NAME}, or ${COMMAND_NAME} service install for the background service.`;

export interface RemoteControlSettingContext {
	/** Where the global settings are (settings.json). */
	agentDir: string;
	/** The directory the command runs in; its project settings are never read. */
	cwd: string;
	out?: (line: string) => void;
}

export async function runRemoteControlSettingCommand(
	args: string[],
	context: RemoteControlSettingContext,
): Promise<number> {
	const out = context.out ?? ((line: string) => process.stdout.write(`${line}\n`));
	const [command, ...rest] = args;
	if (command === undefined || command === "help" || command === "--help" || command === "-h") {
		for (const line of USAGE) out(line);
		return command === undefined ? 1 : 0;
	}
	if (!["enable", "disable", "status"].includes(command) || rest.length > 0) {
		out(`Unknown command: ${args.join(" ")}`);
		for (const line of USAGE) out(line);
		return 1;
	}
	const settingsManager = SettingsManager.create(context.cwd, context.agentDir, { projectTrusted: false });
	if (command !== "status") {
		settingsManager.setRemoteControlEnabled(command === "enable");
		await settingsManager.flush();
		const errors = settingsManager.drainErrors();
		if (errors.length > 0) {
			for (const error of errors) out(`Error: could not save the setting: ${error.error.message}`);
			return 1;
		}
	}
	out(settingsManager.getRemoteControlEnabled() ? ON_MESSAGE : REMOTE_CONTROL_OFF_MESSAGE);
	return 0;
}
