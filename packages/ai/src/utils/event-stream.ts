import type { AssistantMessage, AssistantMessageEvent, StreamOptions } from "../types.ts";

export class FifoQueue<T> {
	private incoming: T[] = [];
	private outgoing: T[] = [];

	get length(): number {
		return this.incoming.length + this.outgoing.length;
	}

	enqueue(value: T): void {
		this.incoming.push(value);
	}

	dequeue(): T | undefined {
		if (this.outgoing.length === 0) {
			while (this.incoming.length > 0) {
				this.outgoing.push(this.incoming.pop()!);
			}
		}
		return this.outgoing.pop();
	}
}

// Generic event stream class for async iteration
export class EventStream<T, R = T> implements AsyncIterable<T> {
	private queue = new FifoQueue<T>();
	private waiting = new FifoQueue<(value: IteratorResult<T>) => void>();
	private sink: ((event: T) => void) | undefined;
	protected done = false;
	private finalResultPromise: Promise<R>;
	private resolveFinalResult!: (result: R) => void;
	private isComplete: (event: T) => boolean;
	private extractResult: (event: T) => R;

	constructor(isComplete: (event: T) => boolean, extractResult: (event: T) => R) {
		this.isComplete = isComplete;
		this.extractResult = extractResult;
		this.finalResultPromise = new Promise((resolve) => {
			this.resolveFinalResult = resolve;
		});
	}

	push(event: T): void {
		if (this.done) return;

		if (this.isComplete(event)) {
			this.done = true;
			this.resolveFinalResult(this.extractResult(event));
		}

		if (this.sink) {
			this.sink(event);
			return;
		}

		// Deliver to waiting consumer or queue it
		const waiter = this.waiting.dequeue();
		if (waiter) {
			waiter({ value: event, done: false });
		} else {
			this.queue.enqueue(event);
		}
	}

	end(result?: R): void {
		this.done = true;
		if (result !== undefined) {
			this.resolveFinalResult(result);
		}
		// Notify all waiting consumers that we're done
		while (this.waiting.length > 0) {
			const waiter = this.waiting.dequeue()!;
			waiter({ value: undefined as any, done: true });
		}
	}

	async *[Symbol.asyncIterator](): AsyncIterator<T> {
		while (true) {
			if (this.queue.length > 0) {
				yield this.queue.dequeue()!;
			} else if (this.done) {
				return;
			} else {
				const result = await new Promise<IteratorResult<T>>((resolve) => this.waiting.enqueue(resolve));
				if (result.done) return;
				yield result.value;
			}
		}
	}

	result(): Promise<R> {
		return this.finalResultPromise;
	}

	/**
	 * Hands every event to `sink` instead of to an iterator: the queued ones at once, in order, then each one as it is
	 * pushed. For a stream that has no consumer but the one forwarding it (lazyStream()): an iterator's `for await` takes
	 * several promise reactions per event, which a chain of forwarding streams paid for every event of a response.
	 */
	forwardTo(sink: (event: T) => void): void {
		while (this.queue.length > 0) sink(this.queue.dequeue()!);
		this.sink = sink;
	}
}

/**
 * Event stream of one assistant response. It also times the response: the final message (`done` or `error` event, or
 * the result passed to `end()`) gets `durationMs`, measured with a monotonic clock from the stream's creation, unless
 * the message already has one or its `timestamp` predates the stream. A stream that forwards a response which started
 * elsewhere, such as a deferred result fetched later, therefore leaves it untimed.
 */
export class AssistantMessageEventStream extends EventStream<AssistantMessageEvent, AssistantMessage> {
	readonly #startedAt = Date.now();
	readonly #startedAtMonotonic = performance.now();
	#finalizers?: Set<() => void>;
	/** @internal Whether streamed JSON arguments may wait until completion. */
	readonly skipToolArgumentPreviews: boolean;

	readonly #skipTextDeltas: boolean;
	readonly #coalesceTextDeltas: boolean;
	#lastTextAt = -Infinity;
	#pendingText?: Extract<AssistantMessageEvent, { type: "text_delta" | "thinking_delta" }>;
	#textTimer?: ReturnType<typeof setTimeout>;

	constructor(options?: Pick<StreamOptions, "skipToolArgumentPreviews" | "skipTextDeltas" | "coalesceTextDeltas">) {
		super(
			(event) => event.type === "done" || event.type === "error",
			(event) => {
				if (event.type === "done") {
					return event.message;
				} else if (event.type === "error") {
					return event.error;
				}
				throw new Error("Unexpected event type for final result");
			},
		);
		this.skipToolArgumentPreviews = options?.skipToolArgumentPreviews === true;
		this.#skipTextDeltas = options?.skipTextDeltas === true;
		this.#coalesceTextDeltas = options?.coalesceTextDeltas === true;
	}

	override push(event: AssistantMessageEvent): void {
		if (event.type === "text_delta" || event.type === "thinking_delta") {
			if (this.#skipTextDeltas || this.done) return;
			if (this.#coalesceTextDeltas) {
				const now = performance.now();
				if (
					this.#pendingText &&
					(event.type !== this.#pendingText.type || event.contentIndex !== this.#pendingText.contentIndex)
				)
					this.#flushText();
				// Deliver isolated updates immediately. A burst can share a notification within a fraction of a TUI frame.
				const wait = 4 - (now - this.#lastTextAt);
				if (wait > 0 || this.#pendingText) {
					this.#pendingText = { ...event, delta: (this.#pendingText?.delta ?? "") + event.delta };
					if (wait <= 0) this.#flushText();
					else this.#textTimer ??= setTimeout(() => this.#flushText(), wait);
					return;
				}
				this.#lastTextAt = now;
			}
		} else if (this.#pendingText) this.#flushText();
		if (event.type === "done" || event.type === "error") {
			this.#finalize();
			this.#time(event.type === "done" ? event.message : event.error);
		}
		super.push(event);
	}

	#flushText(): void {
		if (!this.#pendingText) return;
		clearTimeout(this.#textTimer);
		this.#textTimer = undefined;
		const pending = this.#pendingText;
		this.#pendingText = undefined;
		this.#lastTextAt = performance.now();
		super.push(pending);
	}

	override end(result?: AssistantMessage): void {
		if (this.#pendingText) this.#flushText();
		this.#finalize();
		if (result !== undefined) this.#time(result);
		super.end(result);
	}

	/** @internal Release an open content block before a terminal event reaches consumers. */
	onFinalize(finalizer: () => void): () => void {
		this.#finalizers ??= new Set();
		const finalizers = this.#finalizers;
		finalizers.add(finalizer);
		return () => finalizers.delete(finalizer);
	}

	#finalize(): void {
		if (!this.#finalizers) return;
		for (const finalize of this.#finalizers) finalize();
		this.#finalizers = undefined;
	}

	#time(message: AssistantMessage): void {
		if (this.done || message.durationMs !== undefined || message.timestamp < this.#startedAt) return;
		message.durationMs = Math.max(0, Math.round(performance.now() - this.#startedAtMonotonic));
	}
}

/** Factory function for AssistantMessageEventStream (for use in extensions) */
export function createAssistantMessageEventStream(): AssistantMessageEventStream {
	return new AssistantMessageEventStream();
}
