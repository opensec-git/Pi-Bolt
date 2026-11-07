/**
 * The agent sessions open in this process, so a reader can find one by its file: remote control and RPC mode follow a
 * subagent's session (the subagents extension runs each agent as its own session here) while a phone shows it.
 *
 * The set lives on `globalThis` so every copy of this module (an extension's host module loaded through jiti) shares it,
 * and holds weak references: a session never disposed is still collected.
 */

import type { AgentSession } from "./agent-session.ts";

const REGISTRY_KEY = Symbol.for("@earendil-works/pi-coding-agent:liveSessions");

function registry(): Set<WeakRef<AgentSession>> {
	const holder = globalThis as Record<symbol, Set<WeakRef<AgentSession>> | undefined>;
	let set = holder[REGISTRY_KEY];
	if (!set) {
		set = new Set();
		holder[REGISTRY_KEY] = set;
	}
	return set;
}

export function registerLiveSession(session: AgentSession): void {
	registry().add(new WeakRef(session));
}

export function unregisterLiveSession(session: AgentSession): void {
	for (const ref of registry()) {
		const live = ref.deref();
		if (live === undefined || live === session) registry().delete(ref);
	}
}

/** The open sessions, oldest first. */
export function liveSessions(): AgentSession[] {
	const sessions: AgentSession[] = [];
	for (const ref of registry()) {
		const live = ref.deref();
		if (live) sessions.push(live);
		else registry().delete(ref);
	}
	return sessions;
}
