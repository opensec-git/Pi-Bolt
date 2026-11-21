export type SessionResourceCleanup = (sessionId?: string) => void;
type RequestCacheCleanup = (sessionId?: string, retainedStrings?: () => Iterable<string>) => void;

const sessionResourceCleanups = new Set<SessionResourceCleanup>();
const requestCacheCleanups = new Set<RequestCacheCleanup>();

export function registerSessionResourceCleanup(cleanup: RequestCacheCleanup, kind?: "request-cache"): () => void {
	sessionResourceCleanups.add(cleanup);
	if (kind === "request-cache") requestCacheCleanups.add(cleanup);
	return () => {
		sessionResourceCleanups.delete(cleanup);
		requestCacheCleanups.delete(cleanup);
	};
}

function runCleanups(
	cleanups: ReadonlySet<RequestCacheCleanup>,
	sessionId?: string,
	retainedStrings?: () => Iterable<string>,
): void {
	const errors: unknown[] = [];
	for (const cleanup of cleanups) {
		try {
			cleanup(sessionId, retainedStrings);
		} catch (error) {
			errors.push(error);
		}
	}
	if (errors.length > 0) {
		throw new AggregateError(errors, "Failed to cleanup session resources");
	}
}

/** Drop local request encodings after compaction, while the session and its provider connections remain active. */
export function clearSessionRequestCaches(sessionId: string, retainedStrings?: () => Iterable<string>): void {
	runCleanups(requestCacheCleanups, sessionId, retainedStrings);
}

export function cleanupSessionResources(sessionId?: string): void {
	runCleanups(sessionResourceCleanups, sessionId);
}
