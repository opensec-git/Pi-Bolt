import { PIBOLT } from "../pi-bolt.ts";
import { parseArgs } from "./args.ts";

/** Commands that use the package manager in both distributions. */
export function isPackageManagementCommand(
	command: string | undefined,
): command is "install" | "uninstall" | "update" | "remove" | "list" {
	return (
		command === "install" ||
		command === "uninstall" ||
		command === "update" ||
		command === "remove" ||
		command === "list"
	);
}

/** Load the installer, updater and config selector only when a command uses them. */
export const loadPackageCommands = () => import("../package-manager-cli.ts");

/** Checked before startup reads settings: even the historical safety migration is a write. */
export function getPiBoltPackageFlagError(args: string[]): string | undefined {
	if (!args.some((arg) => arg === "--pi-bolt" || arg.startsWith("--pi-bolt="))) return undefined;
	if (!isPackageManagementCommand(args[0]) && args[0] !== "config") {
		// Startup options can consume dash-prefixed values, including a literal "--".
		if (!parseArgs(args).unknownFlags.has("pi-bolt")) return undefined;
		if (PIBOLT === undefined) return "--pi-bolt requires a Pi-Bolt build";
		return "--pi-bolt is only supported by install, remove, and uninstall";
	}

	const endOfOptions = args.indexOf("--");
	const flags = args
		.slice(0, endOfOptions === -1 ? args.length : endOfOptions)
		.filter((arg) => arg === "--pi-bolt" || arg.startsWith("--pi-bolt="));
	if (flags.length === 0) return undefined;
	if (PIBOLT === undefined) return "--pi-bolt requires a Pi-Bolt build";
	if (flags.some((flag) => flag !== "--pi-bolt")) return "--pi-bolt does not take a value";
	if (!["install", "remove", "uninstall"].includes(args[0]))
		return "--pi-bolt is only supported by install, remove, and uninstall";
	return undefined;
}
