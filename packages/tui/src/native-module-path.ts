import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const moduleRequire = createRequire(import.meta.url);
const TUI_PACKAGE_NAME = "@earendil-works/pi-tui";

export interface NativeModuleCandidateOptions {
	moduleUrl?: string;
	execPath?: string;
	resolvePackage?: (specifier: string) => string;
}

// A module of a compiled executable is in its embedded file system (/$bunfs/ elsewhere, B:\~BUN\ on Windows).
const EMBEDDED_MODULE_DIR = /^(?:\/\$bunfs\/|[A-Za-z]:[\\/]~BUN[\\/])/;

export function getNativeModuleCandidates(nativePath: string, options: NativeModuleCandidateOptions = {}): string[] {
	const moduleDir = dirname(fileURLToPath(options.moduleUrl ?? import.meta.url));
	const candidates: string[] = [];

	// Not in a compiled executable, which has no installed TUI package: resolving one from its embedded files looks up the
	// working directory's folders instead, so a project's own node_modules would supply the native helper that is loaded.
	if (!EMBEDDED_MODULE_DIR.test(moduleDir)) {
		try {
			const packageEntry = (options.resolvePackage ?? moduleRequire.resolve)(TUI_PACKAGE_NAME);
			candidates.push(join(dirname(packageEntry), "..", nativePath));
		} catch {
			// No installed TUI package.
		}
	}

	candidates.push(
		join(moduleDir, "..", nativePath),
		join(moduleDir, nativePath),
		join(dirname(options.execPath ?? process.execPath), nativePath),
	);
	return Array.from(new Set(candidates));
}
