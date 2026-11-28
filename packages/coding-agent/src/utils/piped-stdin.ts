/**
 * Reading piped stdin as the first prompt's prefix.
 */

/** Default for `PI_STDIN_TIMEOUT_MS`: how long a prompt argument waits for piped stdin to send its first data. */
const DEFAULT_STDIN_TIMEOUT_MS = 2_000;

/**
 * How long to wait for the first data on piped stdin when the command line already has a prompt, from
 * `PI_STDIN_TIMEOUT_MS`; 0 waits for end of input as without a prompt.
 */
export function getStdinFirstDataTimeoutMs(env: NodeJS.ProcessEnv = process.env): number {
	const raw = env.PI_STDIN_TIMEOUT_MS;
	if (raw === undefined || raw.trim() === "") return DEFAULT_STDIN_TIMEOUT_MS;
	const value = Number(raw);
	return Number.isFinite(value) && value >= 0 ? value : DEFAULT_STDIN_TIMEOUT_MS;
}

/**
 * Read all of piped stdin. Undefined when stdin is a terminal or empty. With `firstDataTimeoutMs` above 0, stdin that
 * sends nothing within that time counts as empty and is no longer read: a parent that keeps the pipe open without
 * writing does not block `pi -p "prompt"` (upstream #10415). Once data arrives, it is read to the end.
 */
export function readPipedStdin(
	stdin: NodeJS.ReadableStream & { isTTY?: boolean; destroy?: () => void },
	firstDataTimeoutMs = 0,
): Promise<string | undefined> {
	if (stdin.isTTY) return Promise.resolve(undefined);

	return new Promise((resolve) => {
		let data = "";
		let timer: ReturnType<typeof setTimeout> | undefined;
		const onData = (chunk: string) => {
			clearTimeout(timer);
			data += chunk;
		};
		const onEnd = () => {
			clearTimeout(timer);
			resolve(data.trim() || undefined);
		};
		stdin.setEncoding("utf8");
		stdin.on("data", onData);
		stdin.on("end", onEnd);
		if (firstDataTimeoutMs > 0) {
			timer = setTimeout(() => {
				stdin.off("data", onData);
				stdin.off("end", onEnd);
				// Stop reading, so the open pipe does not keep the process alive.
				stdin.pause();
				stdin.destroy?.();
				resolve(undefined);
			}, firstDataTimeoutMs);
		}
		stdin.resume();
	});
}
