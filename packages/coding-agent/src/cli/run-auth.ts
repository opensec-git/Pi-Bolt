import { COMMAND_NAME, getAgentDir } from "../config.ts";
import { AuthStorage, ReadOnlyAuthStorage } from "../core/auth-storage.ts";
import { applyHttpProxySettings, configureHttpDispatcher } from "../core/http-dispatcher.ts";
import { ModelRuntime } from "../core/model-runtime.ts";
import { SettingsManager } from "../core/settings-manager.ts";
import { chalk } from "../utils/lazy-packages.ts";
import { parseArgs } from "./args.ts";
import {
	type AuthCheckResult,
	checkProviderAuth,
	createAuthCheckModelRuntime,
	getProviderCredential,
} from "./auth-check.ts";
import {
	type AuthCommand,
	AuthCommandError,
	getAuthCommandName,
	getAuthCommandUsage,
	isAuthCommandHelp,
	parseAuthCommand,
	printAuthCommandHelp,
	validateAuthCommandArgs,
} from "./auth-command.ts";
import { runAuthLogin } from "./auth-login.ts";
import { runAuthLogout } from "./auth-logout.ts";
import { createProcessLoginTerminal } from "./auth-terminal.ts";
import { resolveCredentialForPrint } from "./credential-print.ts";

async function runAuthLoginCommand(args: string[]): Promise<number> {
	const cwd = process.cwd();
	const agentDir = getAgentDir();
	const settingsManager = SettingsManager.create(cwd, agentDir, { projectTrusted: false });
	applyHttpProxySettings(settingsManager.getGlobalHttpProxy());
	configureHttpDispatcher(settingsManager.getHttpIdleTimeoutMs());
	const modelRuntime = await ModelRuntime.create({ allowModelNetwork: false, signal: AbortSignal.timeout(15_000) });

	const controller = new AbortController();
	const interrupt = () => controller.abort();
	process.on("SIGINT", interrupt);
	try {
		return await runAuthLogin(args, {
			modelRuntime,
			terminal: createProcessLoginTerminal(interrupt),
			agentDir,
			cwd,
			getDeviceId: () => settingsManager.getOrCreateDeviceId(),
			signal: controller.signal,
		});
	} finally {
		process.off("SIGINT", interrupt);
	}
}

async function runAuthLogoutCommand(args: string[]): Promise<number> {
	const signal = AbortSignal.timeout(15_000);
	const modelRuntime = await ModelRuntime.create({ allowModelNetwork: false, signal });
	return runAuthLogout(args, {
		modelRuntime,
		terminal: { write: (line) => console.log(line), error: (line) => console.error(line) },
		signal,
	});
}

export async function runAuthCommand(args: string[]): Promise<boolean> {
	if (isAuthCommandHelp(args)) {
		printAuthCommandHelp();
		return true;
	}
	if (args[0] === "auth" && args[1] === "login") {
		// main() already applied --offline.
		process.exitCode = await runAuthLoginCommand(args.slice(2).filter((arg) => arg !== "--offline"));
		return true;
	}
	if (args[0] === "auth" && args[1] === "logout") {
		process.exitCode = await runAuthLogoutCommand(args.slice(2).filter((arg) => arg !== "--offline"));
		return true;
	}

	let command: AuthCommand | undefined;
	try {
		command = parseAuthCommand(args);
	} catch (error) {
		const message = error instanceof AuthCommandError ? error.message : "Failed to parse auth command";
		console.error(chalk.red(`Error: ${message}`));
		process.exitCode = 1;
		return true;
	}
	if (!command) return false;

	const parsed = parseArgs(command.args);
	if (parsed.unknownFlags.size > 0) {
		const option = parsed.unknownFlags.keys().next().value;
		console.error(chalk.red(`Unknown option --${option} for "${getAuthCommandName(command.kind)}".`));
		console.error(chalk.dim(`Use "${COMMAND_NAME} --help" or "${getAuthCommandUsage(command.kind)}".`));
		process.exitCode = 1;
		return true;
	}
	try {
		if (parsed.diagnostics.length > 0) {
			throw new AuthCommandError(parsed.diagnostics.map((diagnostic) => diagnostic.message).join("\n"));
		}
		if (command.kind !== "check") {
			const signal = AbortSignal.timeout(15_000);
			const modelRuntime = await ModelRuntime.create({ allowModelNetwork: false, signal });
			const credential = await resolveCredentialForPrint(
				parsed,
				modelRuntime,
				command.kind,
				command.minExpiryMs,
				signal,
			);
			process.stdout.write(`${credential}\n`);
			return true;
		}

		const requestedAuth = validateAuthCommandArgs(parsed, command.kind);
		let result: AuthCheckResult;
		let credential: string | undefined;
		try {
			const credentials = command.noRefresh ? new ReadOnlyAuthStorage() : AuthStorage.create();
			const modelRuntime = await createAuthCheckModelRuntime(credentials);
			result = await checkProviderAuth(parsed, modelRuntime, { refresh: !command.noRefresh });
			if (command.credentials && result.status === "ready") {
				credential = await getProviderCredential(result.provider, modelRuntime, credentials, {
					refresh: !command.noRefresh,
				});
				if (!credential) {
					result = { status: "not_ready", provider: result.provider, reason: "credential_not_available" };
				}
			}
		} catch {
			result = {
				status: "invalid",
				provider: requestedAuth.provider ?? requestedAuth.model!,
				reason: "invalid_state",
			};
		}
		const output = command.json
			? JSON.stringify({ ...result, ...(credential ? { credentials: credential } : {}) })
			: (credential ?? result.status);
		process.stdout.write(`${output}\n`);
		process.exitCode = result.status === "ready" ? 0 : result.status === "not_ready" ? 1 : 2;
	} catch (error) {
		const message = error instanceof AuthCommandError ? error.message : "Failed to resolve credential";
		console.error(chalk.red(`Error: ${message}`));
		process.exitCode = command.kind === "check" ? 2 : 1;
	}
	return true;
}
