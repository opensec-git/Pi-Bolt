/*
 * Adapted from modelcontextprotocol/typescript-sdk v1.29.0 src/client/auth.ts.
 * Copyright (c) 2024 Anthropic, PBC. Licensed under MIT; see LICENSES/.
 * Modified to remove Zod/CORS shims and enforce authorization-server issuer validation.
 */

import type { McpFetch } from "../auth-provider.ts";
import { LATEST_PROTOCOL_VERSION } from "../protocol/types.ts";
import { OAuthInsecureEndpointError, OAuthIssuerMismatchError } from "./errors.ts";
import {
	type AuthorizationServerMetadata,
	isHttpUrl,
	isSecureOAuthUrl,
	type OAuthChallenge,
	type OAuthProtectedResourceMetadata,
	type OAuthServerInfo,
	parseAuthorizationServerMetadata,
	parseProtectedResourceMetadata,
} from "./types.ts";

/** The most of an OAuth response read. Metadata, registration and token responses are a few KiB. */
const MAX_OAUTH_RESPONSE_BYTES = 1024 * 1024;
/** Redirects followed when loading metadata, each checked as the first URL is. */
const MAX_METADATA_REDIRECTS = 5;

function discard(response: Response | undefined): void {
	void response?.body?.cancel().catch(() => {});
}

/** The body of an OAuth response as text. A body larger than MAX_OAUTH_RESPONSE_BYTES is an error, and is not read on. */
export async function readOAuthResponseText(response: Response): Promise<string> {
	const tooLarge = () => new Error(`OAuth response from ${response.url || "the server"} is larger than 1 MiB`);
	const length = Number(response.headers.get("content-length"));
	if (length > MAX_OAUTH_RESPONSE_BYTES) {
		discard(response);
		throw tooLarge();
	}
	if (!response.body) return "";
	const reader = response.body.getReader();
	const chunks: Uint8Array[] = [];
	let size = 0;
	while (true) {
		const { done, value } = await reader.read();
		if (done) break;
		size += value.byteLength;
		if (size > MAX_OAUTH_RESPONSE_BYTES) {
			void reader.cancel().catch(() => {});
			throw tooLarge();
		}
		chunks.push(value);
	}
	return Buffer.concat(chunks).toString("utf8");
}

/**
 * Checks a URL that discovery is about to load: https, http to this machine, or the MCP server's own origin (which the
 * user configured). Servers name these URLs, so anything else (a `file:` URL, which some runtimes' fetch reads, or plain
 * http to another host) would have Pi read local files or send requests within the user's network for them.
 */
function discoveryUrl(url: URL, server: URL | undefined): URL {
	if (isSecureOAuthUrl(url) || (server !== undefined && isHttpUrl(url) && url.origin === server.origin)) return url;
	throw new OAuthInsecureEndpointError(url.href);
}

/** 4xx and 502 mean "not here", so discovery tries the next candidate URL. */
function isDiscoveryMiss(status: number): boolean {
	return (status >= 400 && status < 500) || status === 502;
}

/** Path suffix for `/.well-known/<kind><path>`; empty for the root path. */
function pathSuffix(pathname: string): string {
	return pathname.endsWith("/") ? pathname.slice(0, -1) : pathname;
}

function field(header: string, name: string): string | undefined {
	const match = header.match(new RegExp(String.raw`(?:^|[,\s])${name}=(?:"([^"]*)"|([^\s,]+))`, "i"));
	// An empty value (`scope=""`) carries no information, so it counts as absent.
	return match?.[1] || match?.[2] || undefined;
}

export function parseWwwAuthenticate(header: string | null): OAuthChallenge {
	if (!header) return {};
	const scheme = header.trimStart().split(/\s+/, 1)[0]?.toLowerCase();
	if (scheme !== "bearer" && scheme !== "dpop") return {};
	const resourceMetadata = field(header, "resource_metadata");
	let resourceMetadataUrl: URL | undefined;
	// Only http and https URLs: discovery loads it, and some runtimes' fetch reads `file:` URLs.
	if (resourceMetadata && URL.canParse(resourceMetadata) && isHttpUrl(new URL(resourceMetadata))) {
		resourceMetadataUrl = new URL(resourceMetadata);
	}
	return {
		resourceMetadataUrl,
		scope: field(header, "scope"),
		error: field(header, "error"),
		errorDescription: field(header, "error_description"),
	};
}

/** Loads a metadata document, following redirects only to URLs that `discoveryUrl` accepts. */
async function fetchMetadata(
	url: URL,
	fetch: McpFetch,
	protocolVersion: string,
	signal: AbortSignal | undefined,
	server: URL | undefined,
): Promise<Response> {
	let current = discoveryUrl(url, server);
	for (let redirects = 0; ; redirects++) {
		const response = await fetch(current, {
			headers: { Accept: "application/json", "MCP-Protocol-Version": protocolVersion },
			signal,
			redirect: "manual",
		});
		const location = response.headers.get("location");
		if (response.status < 300 || response.status > 399 || location === null) return response;
		discard(response);
		if (redirects === MAX_METADATA_REDIRECTS)
			throw new Error(`Too many redirects loading OAuth metadata from ${url}`);
		current = discoveryUrl(new URL(location, current), server);
	}
}

async function readMetadataJson(response: Response): Promise<unknown> {
	return JSON.parse(await readOAuthResponseText(response));
}

export async function discoverProtectedResourceMetadata(
	serverUrl: string | URL,
	options: {
		resourceMetadataUrl?: string | URL;
		protocolVersion?: string;
		fetch?: McpFetch;
		signal?: AbortSignal;
	} = {},
): Promise<OAuthProtectedResourceMetadata> {
	const server = new URL(serverUrl);
	const fetch = options.fetch ?? globalThis.fetch;
	const version = options.protocolVersion ?? LATEST_PROTOCOL_VERSION;
	let response = await fetchMetadata(
		options.resourceMetadataUrl
			? new URL(options.resourceMetadataUrl)
			: new URL(`/.well-known/oauth-protected-resource${pathSuffix(server.pathname)}`, server.origin),
		fetch,
		version,
		options.signal,
		server,
	);
	if (!options.resourceMetadataUrl && server.pathname !== "/" && isDiscoveryMiss(response.status)) {
		discard(response);
		response = await fetchMetadata(
			new URL("/.well-known/oauth-protected-resource", server.origin),
			fetch,
			version,
			options.signal,
			server,
		);
	}
	if (!response.ok) {
		discard(response);
		throw new Error(`HTTP ${response.status} loading OAuth protected resource metadata`);
	}
	return parseProtectedResourceMetadata(await readMetadataJson(response));
}

export function buildAuthorizationServerDiscoveryUrls(
	authorizationServerUrl: string | URL,
): { url: URL; type: "oauth" | "oidc" }[] {
	const issuer = new URL(authorizationServerUrl);
	const path = pathSuffix(issuer.pathname);
	const urls: { url: URL; type: "oauth" | "oidc" }[] = [
		{ url: new URL(`/.well-known/oauth-authorization-server${path}`, issuer.origin), type: "oauth" },
		{ url: new URL(`/.well-known/openid-configuration${path}`, issuer.origin), type: "oidc" },
	];
	if (path) urls.push({ url: new URL(`${path}/.well-known/openid-configuration`, issuer.origin), type: "oidc" });
	return urls;
}

export async function discoverAuthorizationServerMetadata(
	authorizationServerUrl: string | URL,
	options: {
		/** The MCP server, whose own origin may be plain http; other authorization servers must use https or loopback. */
		serverUrl?: string | URL;
		fetch?: McpFetch;
		protocolVersion?: string;
		skipIssuerValidation?: boolean;
		signal?: AbortSignal;
	} = {},
): Promise<AuthorizationServerMetadata | undefined> {
	const fetch = options.fetch ?? globalThis.fetch;
	const server = options.serverUrl === undefined ? undefined : new URL(options.serverUrl);
	for (const { url } of buildAuthorizationServerDiscoveryUrls(authorizationServerUrl)) {
		const response = await fetchMetadata(
			url,
			fetch,
			options.protocolVersion ?? LATEST_PROTOCOL_VERSION,
			options.signal,
			server,
		);
		if (!response.ok) {
			discard(response);
			if (isDiscoveryMiss(response.status)) continue;
			throw new Error(`HTTP ${response.status} loading authorization server metadata from ${url}`);
		}
		const metadata = parseAuthorizationServerMetadata(await readMetadataJson(response));
		if (!options.skipIssuerValidation) {
			const expected = String(authorizationServerUrl);
			// URL parsing adds a trailing slash to bare origins, so compare without one on either side.
			const trim = (value: string) => (value.endsWith("/") ? value.slice(0, -1) : value);
			if (trim(metadata.issuer) !== trim(expected)) throw new OAuthIssuerMismatchError(expected, metadata.issuer);
		}
		return metadata;
	}
	return undefined;
}

export async function discoverOAuthServerInfo(
	serverUrl: string | URL,
	options: {
		resourceMetadataUrl?: URL;
		/** Metadata document to use instead of discovery. It is trusted as configured, so its issuer is not checked. */
		authorizationServerMetadataUrl?: URL;
		fetch?: McpFetch;
		skipIssuerValidation?: boolean;
		signal?: AbortSignal;
	} = {},
): Promise<OAuthServerInfo> {
	let resourceMetadata: OAuthProtectedResourceMetadata | undefined;
	try {
		resourceMetadata = await discoverProtectedResourceMetadata(serverUrl, {
			resourceMetadataUrl: options.resourceMetadataUrl,
			fetch: options.fetch,
			signal: options.signal,
		});
	} catch (error) {
		if (error instanceof TypeError) throw error;
	}
	if (options.authorizationServerMetadataUrl) {
		const url = options.authorizationServerMetadataUrl;
		const response = await fetchMetadata(
			url,
			options.fetch ?? globalThis.fetch,
			LATEST_PROTOCOL_VERSION,
			options.signal,
			new URL(serverUrl),
		);
		if (!response.ok) {
			discard(response);
			throw new Error(`HTTP ${response.status} loading authorization server metadata from ${url}`);
		}
		const metadata = parseAuthorizationServerMetadata(await readMetadataJson(response));
		return { authorizationServerUrl: metadata.issuer, authorizationServerMetadata: metadata, resourceMetadata };
	}
	const authorizationServerUrl = resourceMetadata?.authorization_servers?.[0] ?? String(new URL("/", serverUrl));
	return {
		authorizationServerUrl,
		authorizationServerMetadata: await discoverAuthorizationServerMetadata(authorizationServerUrl, {
			serverUrl,
			fetch: options.fetch,
			skipIssuerValidation: options.skipIssuerValidation,
			signal: options.signal,
		}),
		resourceMetadata,
	};
}

export function resourceUrlFromServerUrl(value: string | URL): URL {
	const url = new URL(value);
	url.hash = "";
	return url;
}

export function selectResource(serverUrl: string | URL, metadata?: OAuthProtectedResourceMetadata): string | undefined {
	if (!metadata) return undefined;
	const requested = resourceUrlFromServerUrl(serverUrl);
	const configured = new URL(metadata.resource);
	if (requested.origin !== configured.origin) {
		throw new Error(`Protected resource ${metadata.resource} does not match MCP server ${requested}`);
	}
	const requestedPath = requested.pathname.endsWith("/") ? requested.pathname : `${requested.pathname}/`;
	const configuredPath = configured.pathname.endsWith("/") ? configured.pathname : `${configured.pathname}/`;
	if (!requestedPath.startsWith(configuredPath)) {
		throw new Error(`Protected resource ${metadata.resource} does not match MCP server ${requested}`);
	}
	return metadata.resource;
}
