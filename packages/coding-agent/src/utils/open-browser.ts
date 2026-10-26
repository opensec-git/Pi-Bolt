import { spawn } from "node:child_process";
import { dirname, extname, isAbsolute, join } from "node:path";
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
 * macOS and Linux: web and mail links are opened as given. A local file (a file: URL or an absolute path) is shown in the
 * file manager instead of being opened: a link the model wrote, or one Pi draws for a path a tool wrote (setup.command,
 * an .app, a .desktop file), would otherwise be one click from running. Anything else is not opened: another scheme (an
 * app's handler), a file on another computer, or a relative path (which `open` resolves against Pi's folder, and which,
 * starting with `-`, `open` reads as an option such as `-a`).
 */
function unixLaunch(target: string): { file: string; args: string[] } | undefined {
	const darwin = process.platform === "darwin";
	const url = URL.canParse(target) ? new URL(target) : undefined;
	let path: string;
	if (!url) {
		path = target;
	} else if (url.protocol === "http:" || url.protocol === "https:" || url.protocol === "mailto:") {
		// (The URL as parsed starts with its scheme: `open` and `xdg-open` cannot take it for an option.)
		return { file: darwin ? "open" : "xdg-open", args: [url.href] };
	} else if (url.protocol === "file:" && !url.hostname && !/%2f/i.test(url.pathname)) {
		// (A file: URL's host "localhost" parses as none; an encoded "/" is a path fileURLToPath() rejects.)
		path = fileURLToPath(url);
	} else {
		return undefined;
	}
	if (!isAbsolute(path)) return undefined;
	return darwin ? { file: "open", args: ["-R", path] } : { file: "xdg-open", args: [dirname(path)] };
}

/**
 * Open a URL or file in the platform browser/default handler.
 *
 * This intentionally never invokes a shell. On Windows, do not use
 * `cmd /c start`: cmd.exe re-parses metacharacters (&, |, ^, ...) before
 * `start` runs, which would make attacker-controlled URLs injectable.
 * Only web and mail links are opened as given. A local file is shown in the file manager instead (on Windows, one that is
 * only viewed, such as an image, is opened), and anything else is not opened (see windowsLaunch and unixLaunch).
 */
export function openBrowser(target: string): void {
	const launch: { file: string; args: string[]; verbatim?: boolean } | undefined =
		process.platform === "win32" ? windowsLaunch(target) : unixLaunch(target);
	if (!launch) return;

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
