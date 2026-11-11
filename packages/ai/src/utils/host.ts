/** The host name of `baseUrl`, lower-cased; "" when it is not a URL. */
export function hostOf(baseUrl: string): string {
	try {
		return new URL(baseUrl).hostname.toLowerCase();
	} catch {
		return "";
	}
}

/** Whether `host` is `domain` or a subdomain of it (not merely a URL that contains the name). */
export function hostMatches(host: string, domain: string): boolean {
	return host === domain || host.endsWith(`.${domain}`);
}
