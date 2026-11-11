// Release downloads through a connection that drops (pi-bolt-download.ts): `pi-bolt compile` gave up on one ECONNRESET after
// `pi-bolt update` from 1.1.4 to 1.2.0, where the same URL downloaded a minute later.
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { download as compileDownload } from "../src/pi-bolt-compile.ts";
import { DownloadError, downloadWithRetry } from "../src/pi-bolt-download.ts";
import { download as windowsDownload } from "../src/pi-bolt-update-windows.ts";

/** The file every route serves: big enough that half of it is sent before the connection is dropped. */
const FILE = Buffer.from(Array.from({ length: 200_000 }, (_, index) => index % 251));

type Handler = (request: IncomingMessage, response: ServerResponse, attempt: number) => void;

let server: Server;
let base: string;
let requests: Array<{ url: string; range?: string }>;

/** Serves `handler` on 127.0.0.1; `attempt` counts the requests from 1. */
async function serve(handler: Handler): Promise<void> {
	server = createServer((request, response) => {
		requests.push({ url: request.url ?? "", range: request.headers.range });
		handler(request, response, requests.length);
	});
	await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
	base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

/** Sends the headers of the whole file, half of its body, then drops the connection. */
function dropHalfway(response: ServerResponse): void {
	response.writeHead(200, { "content-length": String(FILE.length), "content-type": "application/octet-stream" });
	response.write(FILE.subarray(0, FILE.length / 2), () => setTimeout(() => response.socket?.destroy(), 20));
}

/** Answers a Range request for the rest of the file with 206, and anything else with the whole file. */
function sendRest(request: IncomingMessage, response: ServerResponse): void {
	const start = Number(/^bytes=(\d+)-$/.exec(request.headers.range ?? "")?.[1] ?? Number.NaN);
	if (Number.isFinite(start)) {
		response.writeHead(206, {
			"content-length": String(FILE.length - start),
			"content-range": `bytes ${start}-${FILE.length - 1}/${FILE.length}`,
		});
		response.end(FILE.subarray(start));
		return;
	}
	response.writeHead(200, { "content-length": String(FILE.length) });
	response.end(FILE);
}

beforeEach(() => {
	requests = [];
});

afterEach(async () => {
	vi.restoreAllMocks();
	server?.closeAllConnections();
	await new Promise((done) => server?.close(done));
});

describe("a release download tries again after a failure that may pass", () => {
	test("a connection dropped halfway is resumed with a Range request the server answers with 206", async () => {
		await serve((request, response, attempt) =>
			attempt === 1 ? dropHalfway(response) : sendRest(request, response),
		);
		const retries: string[] = [];
		const data = await downloadWithRetry(`${base}/runtime.tar.gz`, {
			retryDelayMs: 0,
			onRetry: (line) => retries.push(line),
		});
		expect(data.equals(FILE)).toBe(true);
		expect(requests).toEqual([
			{ url: "/runtime.tar.gz", range: undefined },
			{ url: "/runtime.tar.gz", range: `bytes=${FILE.length / 2}-` },
		]);
		expect(retries).toHaveLength(1);
		expect(retries[0]).toMatch(/trying again in 0 s \(attempt 2 of 4, from 0\.1 MB on\)$/);
	});

	test("a server that answers the Range request with the whole file is downloaded from the start", async () => {
		await serve((_request, response, attempt) => {
			if (attempt === 1) return dropHalfway(response);
			response.writeHead(200, { "content-length": String(FILE.length) });
			response.end(FILE);
		});
		const data = await downloadWithRetry(`${base}/runtime.tar.gz`, { retryDelayMs: 0 });
		expect(data.equals(FILE)).toBe(true);
		expect(requests).toHaveLength(2);
	});

	test("a 206 that does not start where the download stopped is not joined to it: the next attempt starts over", async () => {
		await serve((request, response, attempt) => {
			if (attempt === 1) return dropHalfway(response);
			if (attempt === 2) {
				response.writeHead(206, { "content-range": `bytes 0-9/${FILE.length}`, "content-length": "10" });
				response.end(FILE.subarray(0, 10));
				return;
			}
			sendRest(request, response);
		});
		const data = await downloadWithRetry(`${base}/runtime.tar.gz`, { retryDelayMs: 0 });
		expect(data.equals(FILE)).toBe(true);
		expect(requests.map((request) => request.range)).toEqual([undefined, `bytes=${FILE.length / 2}-`, undefined]);
	});

	test("a 503, then the file", async () => {
		await serve((request, response, attempt) => {
			if (attempt === 1) {
				response.writeHead(503);
				response.end("busy");
				return;
			}
			sendRest(request, response);
		});
		const retries: string[] = [];
		const data = await downloadWithRetry(`${base}/SHA256SUMS`, {
			retryDelayMs: 0,
			onRetry: (line) => retries.push(line),
		});
		expect(data.equals(FILE)).toBe(true);
		expect(retries).toEqual(["the server answered 503: trying again in 0 s (attempt 2 of 4)"]);
	});

	test("a 404 fails at once", async () => {
		await serve((_request, response) => {
			response.writeHead(404);
			response.end("not found");
		});
		const failure = await downloadWithRetry(`${base}/missing`, { retryDelayMs: 0 }).catch((error: unknown) => error);
		expect(failure).toBeInstanceOf(DownloadError);
		expect((failure as DownloadError).status).toBe(404);
		expect(requests).toHaveLength(1);
	});

	test("a failure that does not pass gives up after the attempts, with the last reason", async () => {
		await serve((_request, response) => dropHalfway(response));
		const retries: string[] = [];
		await expect(
			downloadWithRetry(`${base}/runtime.tar.gz`, {
				retryDelayMs: 0,
				attempts: 3,
				onRetry: (line) => retries.push(line),
			}),
		).rejects.toThrow();
		expect(requests).toHaveLength(3);
		expect(retries).toHaveLength(2);
	});

	test("the delay grows", async () => {
		await serve((_request, response) => {
			response.writeHead(502);
			response.end();
		});
		const retries: string[] = [];
		await expect(
			downloadWithRetry(`${base}/x`, { retryDelayMs: 100, onRetry: (line) => retries.push(line) }),
		).rejects.toThrow("the server answered 502");
		expect(retries.map((line) => /in ([\d.]+ s)/.exec(line)?.[1])).toEqual(["0.1 s", "0.2 s", "0.4 s"]);
		expect(retries.map((line) => /attempt (\d) of 4/.exec(line)?.[1])).toEqual(["2", "3", "4"]);
	});
});

describe("pi-bolt compile's download says it is trying again, and names the URL when it gives up", () => {
	test("dropped halfway, then resumed: the whole file, and a line on the terminal", async () => {
		const stderr = vi.spyOn(process.stderr, "write").mockImplementation(() => true);
		await serve((request, response, attempt) =>
			attempt === 1 ? dropHalfway(response) : sendRest(request, response),
		);
		const data = await compileDownload(`${base}/pi-bolt-runtime-darwin-arm64.tar.gz`, { retryDelayMs: 0 });
		expect(data.equals(FILE)).toBe(true);
		const said = stderr.mock.calls.map((call) => String(call[0])).join("");
		expect(said).toContain("download of pi-bolt-runtime-darwin-arm64.tar.gz failed: ");
		expect(said).toContain("trying again in 0 s (attempt 2 of 4, from 0.1 MB on)");
	});

	test("a 404: one request, and the URL and status in the error", async () => {
		await serve((_request, response) => {
			response.writeHead(404);
			response.end();
		});
		await expect(compileDownload(`${base}/SHA256SUMS`, { retryDelayMs: 0 })).rejects.toThrow(
			`could not download ${base}/SHA256SUMS: the server answered 404`,
		);
		expect(requests).toHaveLength(1);
	});
});

describe("the Windows update's and the installer's download tries again too", () => {
	test("dropped halfway, then resumed", async () => {
		vi.spyOn(console, "log").mockImplementation(() => {});
		await serve((request, response, attempt) =>
			attempt === 1 ? dropHalfway(response) : sendRest(request, response),
		);
		const data = await windowsDownload(`${base}/pi-bolt-win32-x64.zip`, undefined, { retryDelayMs: 0 });
		expect(data?.equals(FILE)).toBe(true);
		expect(requests).toHaveLength(2);
	});

	test("a 404 is undefined after one request", async () => {
		await serve((_request, response) => {
			response.writeHead(404);
			response.end();
		});
		expect(await windowsDownload(`${base}/SHA256SUMS.sig`, undefined, { retryDelayMs: 0 })).toBeUndefined();
		expect(requests).toHaveLength(1);
	});
});
