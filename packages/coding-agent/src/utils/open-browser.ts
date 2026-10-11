import { spawn } from "node:child_process";
import { extname, isAbsolute, join } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Windows: files that opening shows rather than runs. Any other local file is shown selected in Explorer instead: a link Pi
 * draws for a path a tool wrote (setup.bat, x.ps1, a .lnk), or one the model wrote in its answer, is one click from running
 * otherwise, as FileProtocolHandler runs what it is given.
 */
const WINDOWS_VIEWABLE = new Set([
	".png",
	".jpg",
	".jpeg",
	".gif",
	".webp",
	".bmp",
	".txt",
	".md",
	".log",
	".csv",
	".json",
	".pdf",
]);

function windowsLaunch(target: string): { file: string; args: string[]; verbatim?: boolean } | undefined {
	const systemRoot = process.env.SystemRoot ?? "C:\\Windows";
	const rundll32 = join(systemRoot, "System32", "rundll32.exe");
	let path: string | undefined;
	const url = URL.canParse(target) ? new URL(target) : undefined;
	if (!url || /^[a-z]:$/i.test(url.protocol)) {
		path = target;
	} else if (url.protocol === "http:" || url.protocol === "https:" || url.protocol === "mailto:") {
		return { file: rundll32, args: ["url.dll,FileProtocolHandler", target] };
	} else if (url.protocol === "file:" && !url.hostname) {
		path = fileURLToPath(url);
	} else {
		// Another scheme (ms-*, search-ms:, a protocol handler of some program), or a file on another computer (whose
		// share Windows would sign in to with the user's credentials).
		return undefined;
	}
	if (!isAbsolute(path) || path.startsWith("\\\\") || path.startsWith("//") || path.includes('"')) return undefined;
	if (WINDOWS_VIEWABLE.has(extname(path).toLowerCase())) {
		return { file: rundll32, args: ["url.dll,FileProtocolHandler", path] };
	}
	// (Explorer reads `/select,"<path>"` itself; quoted as a whole it would not.)
	return { file: join(systemRoot, "explorer.exe"), args: [`/select,"${path}"`], verbatim: true };
}

/**
 * Open a URL or file in the platform browser/default handler.
 *
 * This intentionally never invokes a shell. On Windows, do not use
 * `cmd /c start`: cmd.exe re-parses metacharacters (&, |, ^, ...) before
 * `start` runs, which would make attacker-controlled URLs injectable.
 * On Windows, only web and mail links are opened as given; a local file that could run is shown in Explorer instead, and
 * anything else is not opened (see windowsLaunch).
 */
export function openBrowser(target: string): void {
	let launch: { file: string; args: string[]; verbatim?: boolean } | undefined;
	if (process.platform === "win32") {
		launch = windowsLaunch(target);
		if (!launch) return;
	} else {
		launch = { file: process.platform === "darwin" ? "open" : "xdg-open", args: [target] };
	}

	// spawn reports launcher failures (for example, missing xdg-open) via an
	// error event. Browser launch is best-effort: callers still present the target
	// to the user, so keep the launcher failure from becoming a process crash.
	spawn(launch.file, launch.args, {
		stdio: "ignore",
		detached: true,
		...(launch.verbatim && { windowsVerbatimArguments: true }),
	})
		.on("error", () => {})
		.unref();
}
