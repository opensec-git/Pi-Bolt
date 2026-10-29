import { PIBOLT, piBoltInstallDir, piBoltInstallMethod } from "./pi-bolt.ts";
import { spawnProcess } from "./utils/child-process.ts";

export interface PiBoltAutoUpdateResult {
	updated: boolean;
	output: string;
}

/** Whether startup should apply a verified release automatically for this installation. */
export function shouldAutoUpdatePiBolt(): boolean {
	if (!PIBOLT || !piBoltInstallDir() || piBoltInstallMethod() !== "installer") return false;
	const setting = process.env.PIBOLT_AUTO_UPDATE?.trim().toLowerCase();
	return setting !== "0" && setting !== "false" && setting !== "no" && setting !== "off";
}

/** Runs the native executable's existing signed self-update flow, without requiring Node.js or a package manager. */
export function autoUpdatePiBolt(): Promise<PiBoltAutoUpdateResult> {
	if (!shouldAutoUpdatePiBolt()) return Promise.resolve({ updated: false, output: "Automatic updates are disabled." });

	return new Promise((resolve) => {
		const child = spawnProcess(process.execPath, ["update", "--self"], {
			stdio: ["ignore", "pipe", "pipe"],
		});
		let output = "";
		const append = (chunk: Buffer | string) => {
			output = `${output}${chunk.toString()}`.slice(-8000);
		};
		child.stdout.on("data", append);
		child.stderr.on("data", append);
		child.once("error", (error) => resolve({ updated: false, output: error.message }));
		child.once("close", (code, signal) => {
			if (code === 0) {
				resolve({ updated: true, output });
			} else {
				resolve({
					updated: false,
					output: `${output}${signal ? `\nUpdater stopped by ${signal}.` : `\nUpdater exited with code ${code ?? "unknown"}.`}`,
				});
			}
		});
	});
}
