let waitWord: Int32Array<SharedArrayBuffer> | undefined;

/** Sleep between synchronous filesystem lock attempts without spinning while another process owns the lock. */
export function waitForLockRetry(delayMs: number): void {
	waitWord ??= new Int32Array(new SharedArrayBuffer(Int32Array.BYTES_PER_ELEMENT));
	Atomics.wait(waitWord, 0, 0, delayMs);
}
