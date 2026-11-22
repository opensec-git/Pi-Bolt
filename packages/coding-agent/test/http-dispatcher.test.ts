import http from "node:http";
import net from "node:net";
import tls from "node:tls";
import * as undici from "undici";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { applyHttpProxySettings, configureHttpDispatcher } from "../src/core/http-dispatcher.ts";

const PROXY_ENV_KEYS = ["HTTP_PROXY", "HTTPS_PROXY"] as const;
const DISPATCHER_PROXY_ENV_KEYS = [...PROXY_ENV_KEYS, "http_proxy", "https_proxy", "NO_PROXY", "no_proxy"] as const;

describe("http proxy settings", () => {
	let savedEnv: Record<(typeof PROXY_ENV_KEYS)[number], string | undefined>;

	beforeEach(() => {
		savedEnv = Object.fromEntries(PROXY_ENV_KEYS.map((key) => [key, process.env[key]])) as Record<
			(typeof PROXY_ENV_KEYS)[number],
			string | undefined
		>;
		for (const key of PROXY_ENV_KEYS) {
			delete process.env[key];
		}
	});

	afterEach(() => {
		for (const key of PROXY_ENV_KEYS) {
			const value = savedEnv[key];
			if (value === undefined) {
				delete process.env[key];
			} else {
				process.env[key] = value;
			}
		}
	});

	it("applies httpProxy to HTTP_PROXY and HTTPS_PROXY", () => {
		applyHttpProxySettings("http://127.0.0.1:7890");

		expect(process.env.HTTP_PROXY).toBe("http://127.0.0.1:7890");
		expect(process.env.HTTPS_PROXY).toBe("http://127.0.0.1:7890");
	});

	it("does not override existing proxy env vars", () => {
		process.env.HTTP_PROXY = "http://env-http:8080";
		process.env.HTTPS_PROXY = "http://env-https:8080";

		applyHttpProxySettings("http://settings:7890");

		expect(process.env.HTTP_PROXY).toBe("http://env-http:8080");
		expect(process.env.HTTPS_PROXY).toBe("http://env-https:8080");
	});

	it("ignores empty values", () => {
		applyHttpProxySettings("   ");

		expect(process.env.HTTP_PROXY).toBeUndefined();
		expect(process.env.HTTPS_PROXY).toBeUndefined();
	});
});

describe("http dispatcher", () => {
	const originalDispatcher = undici.getGlobalDispatcher();
	const originalFetch = globalThis.fetch;
	let savedProxyEnv: Record<(typeof DISPATCHER_PROXY_ENV_KEYS)[number], string | undefined>;

	beforeEach(() => {
		savedProxyEnv = Object.fromEntries(DISPATCHER_PROXY_ENV_KEYS.map((key) => [key, process.env[key]])) as Record<
			(typeof DISPATCHER_PROXY_ENV_KEYS)[number],
			string | undefined
		>;
		for (const key of DISPATCHER_PROXY_ENV_KEYS) {
			delete process.env[key];
		}
	});

	afterEach(async () => {
		const dispatcher = undici.getGlobalDispatcher();
		if (dispatcher !== originalDispatcher) {
			await dispatcher.close();
			undici.setGlobalDispatcher(originalDispatcher);
		}
		for (const key of DISPATCHER_PROXY_ENV_KEYS) {
			const value = savedProxyEnv[key];
			if (value === undefined) {
				delete process.env[key];
			} else {
				process.env[key] = value;
			}
		}
		globalThis.fetch = originalFetch;
		vi.restoreAllMocks();
	});

	it("tunnels proxied HTTP origins", async () => {
		const origin = http.createServer((_request, response) => {
			response.end("origin");
		});
		await new Promise<void>((resolve) => origin.listen(0, "127.0.0.1", resolve));
		const originAddress = origin.address();
		if (!originAddress || typeof originAddress === "string") {
			throw new Error("Origin did not bind to a TCP port");
		}

		const proxyRequestLines: string[] = [];
		const proxy = net.createServer((client) => {
			client.once("data", (data) => {
				const [requestLine = ""] = data.toString().split("\r\n");
				proxyRequestLines.push(requestLine);
				if (!requestLine.startsWith("CONNECT ")) {
					client.end("HTTP/1.1 501 Not Implemented\r\ncontent-length: 0\r\nconnection: close\r\n\r\n");
					return;
				}

				const upstream = net.connect(originAddress.port, "127.0.0.1", () => {
					client.write("HTTP/1.1 200 Connection Established\r\n\r\n");
					client.pipe(upstream).pipe(client);
				});
				upstream.on("error", () => client.destroy());
				client.on("error", () => upstream.destroy());
			});
		});
		await new Promise<void>((resolve) => proxy.listen(0, "127.0.0.1", resolve));
		const proxyAddress = proxy.address();
		if (!proxyAddress || typeof proxyAddress === "string") {
			throw new Error("Proxy did not bind to a TCP port");
		}

		process.env.HTTP_PROXY = `http://127.0.0.1:${proxyAddress.port}`;
		configureHttpDispatcher();
		const dispatcher = undici.getGlobalDispatcher();
		try {
			const originUrl = `http://127.0.0.1:${originAddress.port}/v1/chat/completions`;
			await expect(undici.fetch(originUrl).then((response) => response.text())).resolves.toBe("origin");
			await expect(undici.fetch(originUrl).then((response) => response.text())).resolves.toBe("origin");
			expect(proxyRequestLines).not.toHaveLength(0);
			expect(proxyRequestLines).toEqual(
				expect.arrayContaining([
					expect.stringMatching(`^CONNECT 127\\.0\\.0\\.1:${originAddress.port} HTTP/1\\.1$`),
				]),
			);
		} finally {
			await dispatcher.close();
			undici.setGlobalDispatcher(originalDispatcher);
			await Promise.all([
				new Promise<void>((resolve) => proxy.close(() => resolve())),
				new Promise<void>((resolve) => origin.close(() => resolve())),
			]);
		}
	});

	it("keeps an idle connection pooled longer than undici's 4 second default", async () => {
		let connections = 0;
		const sockets = new Set<net.Socket>();
		// A minimal HTTP/1.1 server that keeps connections open and, like many provider endpoints, sends no
		// `Keep-Alive: timeout=N` hint (Node's http server always sends one, which undici would follow instead).
		const origin = net.createServer((socket) => {
			connections++;
			sockets.add(socket);
			let pending = "";
			socket.on("data", (data) => {
				pending += data.toString();
				while (pending.includes("\r\n\r\n")) {
					pending = pending.slice(pending.indexOf("\r\n\r\n") + 4);
					socket.write("HTTP/1.1 200 OK\r\ncontent-length: 6\r\n\r\norigin");
				}
			});
			socket.on("close", () => sockets.delete(socket));
		});
		await new Promise<void>((resolve) => origin.listen(0, "127.0.0.1", resolve));
		const address = origin.address();
		if (!address || typeof address === "string") {
			throw new Error("Origin did not bind to a TCP port");
		}

		configureHttpDispatcher();
		const dispatcher = undici.getGlobalDispatcher();
		try {
			const url = `http://127.0.0.1:${address.port}/`;
			await expect(undici.fetch(url).then((response) => response.text())).resolves.toBe("origin");
			// A pause like a tool batch between two model requests, past undici's default.
			await new Promise((resolve) => setTimeout(resolve, 4_500));
			await expect(undici.fetch(url).then((response) => response.text())).resolves.toBe("origin");
			expect(connections).toBe(1);
		} finally {
			await dispatcher.close();
			undici.setGlobalDispatcher(originalDispatcher);
			for (const socket of sockets) socket.destroy();
			await new Promise<void>((resolve) => origin.close(() => resolve()));
		}
	}, 15_000);

	it("allows two seconds for HTTPS connection attempts without changing the Node default", async () => {
		// Preserve a deliberate host fetch override while testing the dispatcher itself.
		globalThis.fetch = async () => {
			throw new Error("Unexpected global fetch");
		};
		const originalAttemptTimeoutMs = net.getDefaultAutoSelectFamilyAttemptTimeout();
		const connectSpy = vi.spyOn(tls, "connect").mockImplementation(() => {
			throw new Error("Connection captured");
		});

		configureHttpDispatcher();
		await expect(undici.fetch("https://example.invalid")).rejects.toThrow();

		expect(connectSpy).toHaveBeenCalledWith(
			expect.objectContaining({
				autoSelectFamilyAttemptTimeout: 2_000,
			}),
		);
		expect(connectSpy.mock.calls[0]?.[0]).not.toHaveProperty("autoSelectFamily");
		expect(net.getDefaultAutoSelectFamilyAttemptTimeout()).toBe(originalAttemptTimeoutMs);
	});
});
