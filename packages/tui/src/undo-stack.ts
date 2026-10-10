/**
 * Generic undo stack with clone-on-push semantics.
 *
 * Stores detached copies of state snapshots. Popped snapshots are returned
 * directly (no re-cloning) since they are already detached.
 */
export class UndoStack<S> {
	private stack: S[] = [];
	private readonly clone: (state: S) => S;

	/**
	 * @param clone Copies a snapshot so that later changes to the live state do not reach it (default: a deep clone).
	 *   Strings are immutable: a state that holds large strings can copy only what is changed in place.
	 */
	constructor(clone: (state: S) => S = (state) => structuredClone(state)) {
		this.clone = clone;
	}

	/** Push a detached copy of the given state onto the stack. */
	push(state: S): void {
		this.stack.push(this.clone(state));
	}

	/** Pop and return the most recent snapshot, or undefined if empty. */
	pop(): S | undefined {
		return this.stack.pop();
	}

	/** Remove all snapshots. */
	clear(): void {
		this.stack.length = 0;
	}

	get length(): number {
		return this.stack.length;
	}
}
