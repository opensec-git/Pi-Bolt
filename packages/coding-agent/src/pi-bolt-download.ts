/**
 * A release file downloaded whole, through a connection that drops: what `pi-bolt compile`, `pi-bolt update` (its installer and
 * the Windows update) download with.
 *
 * A download that fails in a way that may not happen again (the connection reset or refused, nothing received for a while, the
 * body cut short, an HTTP 5xx, 408 or 429) is tried again a few times, after a short delay that grows, and said on the terminal.
 * What had come is kept when the server answers a Range request with 206 from where it stopped; otherwise the download starts
 * over. Any other HTTP answer (a 404, a 403) fails at once. What is downloaded is not checked here: the caller checks the whole
 * file against the release's signed checksums.
 */

/** Why a download failed: the reason, and the HTTP status when the server answered one. */
export class DownloadError extends Error {
	status?: number;
	/** Whether another attempt may succeed. */
	transient: boolean;
	constructor(message: string, transient: boolean, status?: number) {
		super(message);
		this.transient = transient;
		this.status = status;
	}
}

export interface DownloadOptions {
	headers?: Record<string, string>;
	/** How long an attempt may go without receiving anything, connecting included, before it is given up (default 20 s). */
	idleSeconds?: number;
	/** How many attempts in all (default 4). */
	attempts?: number;
	/** The delay before the first retry; each next one is twice the one before (default 1 s). */
	retryDelayMs?: number;
	/** The size of the file, when the server says it, once, before the body comes. */
	onSize?: (bytes: number | undefined) => void;
	/** What has come so far, and the size when known. */
	onProgress?: (received: number, total: number | undefined) => void;
	/** Before each retry: why the attempt failed, and what happens next. */
	onRetry?: (line: string) => void;
}

/** The HTTP answers worth asking again: the server's trouble, or a limit that passes. */
const RETRY_STATUS = new Set([408, 429, 500, 502, 503, 504]);

/**
 * Downloads `url` whole, trying again after a failure that may pass (above). Throws a DownloadError with the reason of the
 * last attempt.
 */
export async function downloadWithRetry(url: string, options: DownloadOptions = {}): Promise<Buffer> {
	const attempts = Math.max(1, options.attempts ?? 4);
	const delay = options.retryDelayMs ?? 1000;
	const state: DownloadState = { chunks: [], received: 0, sizeSaid: false };
	for (let attempt = 1; ; attempt++) {
		try {
			return await downloadOnce(url, state, options);
		} catch (error) {
			const failure = error instanceof DownloadError ? error : new DownloadError(describeError(error), true);
			if (!failure.transient || attempt >= attempts) throw failure;
			const wait = delay * 2 ** (attempt - 1);
			const resume = state.received > 0 ? `, from ${megabytes(state.received)} on` : "";
			options.onRetry?.(
				`${failure.message}: trying again in ${formatSeconds(wait)} (attempt ${attempt + 1} of ${attempts}${resume})`,
			);
			await new Promise((done) => setTimeout(done, wait));
		}
	}
}

/** What the attempts so far received: kept for a Range request, dropped when the server sends the whole file again. */
interface DownloadState {
	chunks: Buffer[];
	received: number;
	/** The file's size, when a response said it. */
	total?: number;
	sizeSaid: boolean;
	/** False once a response came encoded (gzip): its lengths are not the file's, so it is not resumed. */
	resumable?: boolean;
}

/** One attempt: from what `state` has, when there is some and the server can send the rest. */
async function downloadOnce(url: string, state: DownloadState, options: DownloadOptions): Promise<Buffer> {
	const idleSeconds = options.idleSeconds ?? 20;
	const controller = new AbortController();
	let timedOut = false;
	let timer: ReturnType<typeof setTimeout> | undefined;
	const wait = () => {
		clearTimeout(timer);
		timer = setTimeout(() => {
			timedOut = true;
			controller.abort();
		}, idleSeconds * 1000);
	};
	const from = state.resumable === false ? 0 : state.received;
	const headers: Record<string, string> = { ...options.headers };
	if (from > 0) headers.range = `bytes=${from}-`;
	wait();
	try {
		const response = await fetch(url, { headers, redirect: "follow", signal: controller.signal });
		const resumed = from > 0 && response.status === 206 && contentRangeStart(response) === from;
		if (!response.ok || (response.status === 206 && !resumed)) {
			await response.body?.cancel().catch(() => {});
			if (response.status === 206 || response.status === 416) {
				// (A part that does not start where the download stopped: the next attempt asks for the whole file.)
				state.resumable = false;
				throw new DownloadError(`the server sent the wrong part (${response.status})`, true, response.status);
			}
			throw new DownloadError(
				`the server answered ${response.status}`,
				RETRY_STATUS.has(response.status),
				response.status,
			);
		}
		if (!resumed) {
			state.chunks = [];
			state.received = 0;
			state.resumable = !response.headers.has("content-encoding");
			const length = Number(response.headers.get("content-length"));
			state.total = state.resumable && Number.isFinite(length) && length > 0 ? length : undefined;
		}
		if (!state.sizeSaid) {
			state.sizeSaid = true;
			options.onSize?.(state.total);
		}
		if (response.body) {
			const reader = response.body.getReader();
			for (;;) {
				wait();
				const { done, value } = await reader.read();
				if (done) break;
				state.chunks.push(Buffer.from(value));
				state.received += value.length;
				options.onProgress?.(state.received, state.total);
			}
		} else {
			const body = Buffer.from(await response.arrayBuffer());
			state.chunks.push(body);
			state.received += body.length;
		}
		if (state.total !== undefined && state.received !== state.total) {
			throw new DownloadError(
				`the connection closed after ${megabytes(state.received)} of ${megabytes(state.total)}`,
				true,
			);
		}
		return Buffer.concat(state.chunks);
	} catch (error) {
		if (timedOut) throw new DownloadError(`nothing came for ${idleSeconds} s`, true);
		throw error;
	} finally {
		clearTimeout(timer);
	}
}

/** Where a 206 answer's part starts ("Content-Range: bytes 100-199/200" -> 100), or undefined. */
function contentRangeStart(response: Response): number | undefined {
	const match = /^bytes (\d+)-\d+\/(?:\d+|\*)$/.exec(response.headers.get("content-range")?.trim() ?? "");
	return match ? Number(match[1]) : undefined;
}

/** An error as a reason: its message, and the system's reason behind it (Node's fetch says only "fetch failed"). */
function describeError(error: unknown): string {
	const cause = error instanceof Error && error.cause instanceof Error ? `: ${error.cause.message}` : "";
	return `${error instanceof Error ? error.message : String(error)}${cause}`;
}

function formatSeconds(ms: number): string {
	return `${Number((ms / 1000).toFixed(1))} s`;
}

export function megabytes(bytes: number): string {
	return `${(bytes / 1e6).toFixed(bytes < 1e7 ? 1 : 0)} MB`;
}
