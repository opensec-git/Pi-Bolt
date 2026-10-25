import { compare, valid } from "semver";
import {
	PIBOLT,
	PIBOLT_LATEST_RELEASE_API,
	PIBOLT_LATEST_RELEASE_URL,
	PIBOLT_RELEASES_API,
	PIBOLT_RELEASES_FEED,
	piBoltChannel,
	piBoltVersionOfTag,
} from "../pi-bolt.ts";
import { fetchWithRetry } from "./management-http.ts";
import { getPiUserAgent } from "./pi-user-agent.ts";

const LATEST_VERSION_URL = "https://pi.dev/api/latest-version";
const DEFAULT_VERSION_CHECK_TIMEOUT_MS = 10000;

export interface LatestPiRelease {
	version: string;
	packageName?: string;
	note?: string;
}

/** Include useful errno details hidden behind Node's generic "fetch failed" error. */
export function formatVersionCheckError(error: unknown): string {
	const rootMessage = error instanceof Error && error.message ? error.message : String(error);
	const cause = error instanceof Error ? error.cause : undefined;
	const causes = cause instanceof AggregateError ? cause.errors : cause === undefined ? [] : [cause];
	const codes = causes
		.map((value) =>
			typeof value === "object" && value !== null && "code" in value && typeof value.code === "string"
				? value.code
				: undefined,
		)
		.filter((code): code is string => code !== undefined);

	if (codes.length > 0) return `${rootMessage} (${[...new Set(codes)].join(", ")})`;
	const causeMessage = causes.find(
		(value): value is Error => value instanceof Error && Boolean(value.message),
	)?.message;
	return causeMessage ? `${rootMessage} (cause: ${causeMessage})` : rootMessage;
}

export function comparePackageVersions(leftVersion: string, rightVersion: string): number | undefined {
	const left = valid(leftVersion.trim());
	const right = valid(rightVersion.trim());
	if (!left || !right) {
		return undefined;
	}
	return compare(left, right);
}

export function isNewerPackageVersion(candidateVersion: string, currentVersion: string): boolean {
	const comparison = comparePackageVersions(candidateVersion, currentVersion);
	if (comparison !== undefined) {
		return comparison > 0;
	}
	return candidateVersion.trim() !== currentVersion.trim();
}

export async function getLatestPiRelease(
	currentVersion: string,
	options: { timeoutMs?: number; retry?: boolean } = {},
): Promise<LatestPiRelease | undefined> {
	if (process.env.PI_OFFLINE) return undefined;
	if (PIBOLT) return getLatestPiBoltRelease(currentVersion, options);

	const response = await fetchWithRetry(
		LATEST_VERSION_URL,
		{
			headers: {
				"User-Agent": getPiUserAgent(currentVersion),
				accept: "application/json",
			},
		},
		{
			maxRetries: options.retry ? 2 : 0,
			timeoutMs: options.timeoutMs ?? DEFAULT_VERSION_CHECK_TIMEOUT_MS,
		},
	);
	if (!response.ok) return undefined;

	const data = (await response.json()) as {
		packageName?: unknown;
		version?: unknown;
		note?: unknown;
	};
	if (typeof data.version !== "string" || !data.version.trim()) {
		return undefined;
	}
	const packageName =
		typeof data.packageName === "string" && data.packageName.trim() ? data.packageName.trim() : undefined;
	const note = typeof data.note === "string" && data.note.trim() ? data.note.trim() : undefined;
	return {
		version: data.version.trim(),
		packageName,
		...(note ? { note } : {}),
	};
}

/** The highest of `versions` (X.Y.Z), or undefined if there are none. */
function highestVersion(versions: Iterable<string | undefined>): string | undefined {
	let highest: string | undefined;
	for (const version of versions) {
		if (version && (!highest || (comparePackageVersions(version, highest) ?? 0) > 0)) highest = version;
	}
	return highest;
}

/** The newest Pi-Bolt version among GitHub's releases (pre-releases too; drafts never). */
export function newestPiBoltVersion(releases: unknown): string | undefined {
	if (!Array.isArray(releases)) return undefined;
	return highestVersion(
		(releases as Array<{ tag_name?: unknown; draft?: unknown }>).map((release) =>
			release?.draft === true ? undefined : piBoltVersionOfTag(release?.tag_name),
		),
	);
}

/** The version of a release page's address (".../releases/tag/bolt-v1.0.2" -> "1.0.2"), or undefined for any other. */
export function piBoltVersionOfReleaseUrl(url: string | null | undefined): string | undefined {
	if (!url) return undefined;
	try {
		const tag = /\/releases\/tag\/([^/]+)$/.exec(new URL(url, PIBOLT_LATEST_RELEASE_URL).pathname)?.[1];
		return tag ? piBoltVersionOfTag(decodeURIComponent(tag)) : undefined;
	} catch {
		return undefined;
	}
}

/**
 * The newest Pi-Bolt version GitHub's releases feed (releases.atom) lists, pre-releases included: each release is an entry
 * whose link is its page. (Release notes are in the feed as escaped HTML, so links in them are not taken for entries.)
 */
export function newestPiBoltVersionInFeed(feed: string): string | undefined {
	const links = feed.matchAll(/<link\b[^>]*\bhref="([^"]*\/releases\/tag\/[^"]*)"/g);
	return highestVersion(Array.from(links, (link) => piBoltVersionOfReleaseUrl(link[1])));
}

/** "HTTP 403 (rate limit exceeded)": an unsuccessful response, in a message. */
function describeResponse(response: Response): string {
	const rateLimited = response.status === 403 || response.status === 429;
	return rateLimited && response.headers.get("x-ratelimit-remaining") === "0"
		? `HTTP ${response.status} (rate limit exceeded)`
		: `HTTP ${response.status}`;
}

/**
 * Pi-Bolt updates as a whole (its Pi and its engine together), so a Pi-Bolt build looks at Pi-Bolt's releases, by Pi-Bolt
 * version: the latest release, or on the beta channel the newest, pre-releases included.
 *
 * The latest release is the one github.com/.../releases/latest redirects to, as the installers find it (#149): GitHub's API
 * allows 60 requests an hour from an address without a token, which a shared address (an office, a VPN, a carrier's NAT)
 * uses up, and then answers 403. The API is asked only if the redirect does not say. The beta channel needs pre-releases,
 * which the redirect does not include: the API's list, else the releases feed (which, like the redirect, is not limited).
 * Throws, saying what each answered, when none says.
 */
async function getLatestPiBoltRelease(
	currentVersion: string,
	options: { timeoutMs?: number; retry?: boolean },
): Promise<LatestPiRelease | undefined> {
	const headers = { "User-Agent": getPiUserAgent(currentVersion) };
	// One time budget for the whole lookup: a network that does not answer is not waited for once per source.
	const signal = AbortSignal.timeout(options.timeoutMs ?? DEFAULT_VERSION_CHECK_TIMEOUT_MS);
	const retry = { maxRetries: options.retry ? 2 : 0 };
	const latestRedirect = async (): Promise<string | undefined> => {
		const response = await fetchWithRetry(PIBOLT_LATEST_RELEASE_URL, { headers, redirect: "manual", signal }, retry);
		try {
			await response.body?.cancel();
		} catch {
			// Only the status and the Location header are read.
		}
		const location = response.status >= 300 && response.status < 400 ? response.headers.get("location") : null;
		const version = piBoltVersionOfReleaseUrl(location);
		if (!version) throw new Error(location ? `redirected to ${location}` : describeResponse(response));
		return version;
	};
	const api = async (url: string, versionOf: (data: unknown) => string | undefined): Promise<string | undefined> => {
		const response = await fetchWithRetry(
			url,
			{ headers: { ...headers, accept: "application/vnd.github+json" }, signal },
			retry,
		);
		if (!response.ok) throw new Error(describeResponse(response));
		const version = versionOf(await response.json());
		if (!version) throw new Error("no Pi-Bolt release in the answer");
		return version;
	};
	const feed = async (): Promise<string | undefined> => {
		const response = await fetchWithRetry(PIBOLT_RELEASES_FEED, { headers, signal }, retry);
		if (!response.ok) throw new Error(describeResponse(response));
		const version = newestPiBoltVersionInFeed(await response.text());
		if (!version) throw new Error("no Pi-Bolt release in the feed");
		return version;
	};
	const sources: Array<[string, () => Promise<string | undefined>]> =
		piBoltChannel() === "beta"
			? [
					[PIBOLT_RELEASES_API, () => api(PIBOLT_RELEASES_API, newestPiBoltVersion)],
					[PIBOLT_RELEASES_FEED, feed],
				]
			: [
					[PIBOLT_LATEST_RELEASE_URL, latestRedirect],
					[
						PIBOLT_LATEST_RELEASE_API,
						() =>
							api(PIBOLT_LATEST_RELEASE_API, (data) =>
								piBoltVersionOfTag((data as { tag_name?: unknown } | null)?.tag_name),
							),
					],
				];
	const failures: string[] = [];
	for (const [url, lookup] of sources) {
		try {
			const version = await lookup();
			if (version) return { version };
		} catch (error) {
			failures.push(`${url}: ${formatVersionCheckError(error)}`);
		}
	}
	throw new Error(failures.join("; "));
}

export async function getLatestPiVersion(
	currentVersion: string,
	options: { timeoutMs?: number; retry?: boolean } = {},
): Promise<string | undefined> {
	return (await getLatestPiRelease(currentVersion, options))?.version;
}

export async function checkForNewPiVersion(currentVersion: string): Promise<LatestPiRelease | undefined> {
	if (process.env.PI_SKIP_VERSION_CHECK) return undefined;
	if (PIBOLT) currentVersion = PIBOLT.version;

	try {
		const latestRelease = await getLatestPiRelease(currentVersion);
		if (latestRelease && isNewerPackageVersion(latestRelease.version, currentVersion)) {
			return latestRelease;
		}
		return undefined;
	} catch {
		return undefined;
	}
}
