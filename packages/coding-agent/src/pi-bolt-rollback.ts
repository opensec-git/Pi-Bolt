/**
 * `pi-bolt update --rollback`: puts back the build that the last update (or `pi-bolt compile`) replaced.
 *
 * The installers keep the build an update replaces beside the new one, as <folder>.previous (install.sh renames it there;
 * install.ps1 copies it there first, since Windows does not let a running executable's folder be renamed). A rollback swaps
 * the two, so running it again goes forward again. It downloads nothing: both builds were checked when they were installed.
 */
import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import chalk from "chalk";
import { PIBOLT, PIBOLT_INSTALL_URL, piBoltInstallDir, piBoltInstallMethod, piBoltPackageManager } from "./pi-bolt.ts";

/** "Pi-Bolt 0.9.0 (Pi 1.2.0), linux-x64, JIT off, built ..." and what is compiled in, from a build's pi-bolt.txt. */
function describe(folder: string): string {
	try {
		const [about, compiled] = readFileSync(join(folder, "pi-bolt.txt"), "utf8").split("\n");
		const name = /^(Pi-Bolt \S+)/.exec(about)?.[1] ?? about;
		const extensions = compiled?.match(/: (.+)$/)?.[1];
		return extensions ? `${name} with ${extensions} compiled in` : name;
	} catch {
		return folder;
	}
}

/** Every file below `dir`, relative to it. */
function filesIn(dir: string): string[] {
	const files: string[] = [];
	const walk = (folder: string): void => {
		for (const entry of readdirSync(folder, { withFileTypes: true })) {
			const path = join(folder, entry.name);
			if (entry.isDirectory()) walk(path);
			else files.push(relative(dir, path));
		}
	};
	walk(dir);
	return files;
}

const ASIDE = /\.pibolt-(old|new)$/;

/**
 * Swaps the files of `current` and `previous` without renaming `current`, which holds the running executable: on Windows a
 * running executable may be renamed but not replaced or deleted, and its folder not renamed at all. Each file of `current`
 * is renamed aside (as install.ps1 does, <file>.<stamp>.pibolt-old; the next install deletes what is left of those), and the
 * file of `previous` renamed into its place; the executable last. `current`'s files were copied first, to become `previous`.
 */
export function swapFolderContents(current: string, previous: string): void {
	const stamp = Date.now();
	const keep = `${current}.rollback-${stamp}`;
	cpSync(current, keep, { recursive: true, filter: (source) => !ASIDE.test(source) });
	try {
		const incoming = filesIn(previous).sort(
			(a, b) => Number(/pi-bolt\.exe$/i.test(a)) - Number(/pi-bolt\.exe$/i.test(b)),
		);
		const wanted = new Set(incoming);
		const aside = (file: string): void => {
			const target = join(current, file);
			if (existsSync(target)) renameSync(target, `${target}.${stamp}.pibolt-old`);
		};
		for (const file of filesIn(current)) {
			if (!wanted.has(file) && !ASIDE.test(file)) aside(file);
		}
		for (const file of incoming) {
			aside(file);
			mkdirSync(dirname(join(current, file)), { recursive: true });
			renameSync(join(previous, file), join(current, file));
		}
	} catch (error) {
		rmSync(keep, { recursive: true, force: true });
		throw error;
	}
	rmSync(previous, { recursive: true, force: true });
	renameSync(keep, previous);
	for (const file of filesIn(current)) {
		if (!ASIDE.test(file)) continue;
		try {
			rmSync(join(current, file), { force: true });
		} catch {
			// A file still running (this executable, or another Pi-Bolt's) cannot be deleted: the next install deletes it.
		}
	}
}

/**
 * Swaps the build in `current` (the folder of the running executable) with <current>.previous. Returns what was there and
 * what is there now; throws, changing nothing, when there is no previous build or it does not run.
 */
export function rollBackInstallation(
	current: string,
	platform: NodeJS.Platform = process.platform,
): { from: string; to: string } {
	const previous = `${current}.previous`;
	if (!existsSync(previous)) {
		throw new Error(
			`there is no build to go back to (${previous}): the installer keeps the one an update replaces, from the next update on`,
		);
	}
	// The build to go back to has to run here before it takes this one's place.
	const executable = join(previous, platform === "win32" ? "pi-bolt.exe" : "pi");
	const check = spawnSync(executable, ["--version"], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
	if (check.status !== 0 || !check.stdout.includes("Pi-Bolt ")) {
		throw new Error(
			`the build in ${previous} does not run: ${`${check.stdout ?? ""}${check.stderr ?? ""}`.trim() || check.error?.message}`,
		);
	}
	const from = describe(current);
	const to = describe(previous);
	if (platform === "win32") {
		swapFolderContents(current, previous);
	} else {
		const swap = `${current}.rollback-${process.pid}`;
		renameSync(current, swap);
		try {
			renameSync(previous, current);
		} catch (error) {
			renameSync(swap, current);
			throw error;
		}
		renameSync(swap, previous);
	}
	return { from, to };
}

/** `pi-bolt update --rollback`. Returns the exit status. */
export function rollbackPiBolt(): number {
	if (!PIBOLT) return 1;
	const method = piBoltInstallMethod();
	if (method === "npm") {
		console.error(
			"This Pi-Bolt was installed with the npm package. Install the version you want with the package manager that installed it:",
		);
		console.error("  npm install -g pi-bolt@<version>      (the versions: npm view pi-bolt versions)");
		return 1;
	}
	const manager = piBoltPackageManager(method);
	if (manager?.switchVersion) {
		console.error(`This Pi-Bolt was installed with ${manager.name}. Switch to another version with ${manager.name}:`);
		console.error(`  ${manager.switchVersion}`);
		return 1;
	}
	// (Homebrew keeps no build before this one: its formula has the latest release only.)
	if (manager) {
		console.error(
			`This Pi-Bolt was installed with ${manager.name}, which keeps no earlier build to go back to. To run another version, uninstall it and install that version with the installer:`,
		);
		console.error(`  ${manager.uninstall} && curl -fsSL ${PIBOLT_INSTALL_URL} | PIBOLT_VERSION=bolt-v<version> sh`);
		return 1;
	}
	if (!piBoltInstallDir()) {
		console.error(chalk.red(`error: this Pi-Bolt is not in an installation (${dirname(process.execPath)})`));
		return 1;
	}
	try {
		const { from, to } = rollBackInstallation(dirname(process.execPath));
		console.log(chalk.green(`Rolled back from ${from} to ${to}.`));
		console.log(chalk.dim(`pi-bolt update --rollback again goes back to ${from}.`));
		return 0;
	} catch (error) {
		console.error(chalk.red(`error: ${error instanceof Error ? error.message : String(error)}`));
		return 1;
	}
}
