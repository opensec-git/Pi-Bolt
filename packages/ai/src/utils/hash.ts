/** Incremental form of the portable short hash. Taking a digest leaves the prefix available for further updates. */
export class ShortHash {
	private h1 = 0xdeadbeef;
	private h2 = 0x41c6ce57;

	update(text: string): this {
		let { h1, h2 } = this;
		for (let i = 0; i < text.length; i++) {
			const ch = text.charCodeAt(i);
			h1 = Math.imul(h1 ^ ch, 2654435761);
			h2 = Math.imul(h2 ^ ch, 1597334677);
		}
		this.h1 = h1;
		this.h2 = h2;
		return this;
	}

	copy(): ShortHash {
		const result = new ShortHash();
		result.h1 = this.h1;
		result.h2 = this.h2;
		return result;
	}

	digest(): string {
		const h1 = Math.imul(this.h1 ^ (this.h1 >>> 16), 2246822507) ^ Math.imul(this.h2 ^ (this.h2 >>> 13), 3266489909);
		const h2 = Math.imul(this.h2 ^ (this.h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
		return (h2 >>> 0).toString(36) + (h1 >>> 0).toString(36);
	}
}

/** Fast deterministic hash to shorten long strings. */
export function shortHash(str: string): string {
	return new ShortHash().update(str).digest();
}
