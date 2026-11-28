/**
 * Uncaught errors end Pi as they do on Node, also on Bun. Node turns an unhandled promise rejection into an uncaught
 * exception (its default `--unhandled-rejections=throw`), so the `uncaughtException` listeners clean up (the terminal,
 * tool processes). Bun ends the process on a rejection without calling them.
 */

import { inspect } from "node:util";
import { killTrackedDetachedChildren } from "../utils/shell.ts";

/**
 * Calls `crash` for an unhandled rejection, with the error Node would raise for it, unless another `unhandledRejection`
 * listener (an extension's) takes the rejection, which on Node keeps the process running. Returns a function that
 * removes the listener.
 */
export function onUnhandledRejection(crash: (error: Error) => void): () => void {
	const listener = (reason: unknown) => {
		if (process.listenerCount("unhandledRejection") > 1) return;
		crash(rejectionError(reason));
	};
	process.on("unhandledRejection", listener);
	return () => process.off("unhandledRejection", listener);
}

/**
 * For the modes without a terminal UI (print, JSON, RPC, ACP, MCP): an uncaught exception or unhandled rejection kills
 * the tool processes Pi started, prints the error and exits with status 1, as Node does without a listener. An
 * extension's own `uncaughtException` listener takes the error instead, as on Node. Returns a function that removes the
 * listeners.
 */
export function exitOnUncaughtErrors(): () => void {
	const onException = (error: Error) => {
		if (process.listenerCount("uncaughtException") > 1) return;
		try {
			killTrackedDetachedChildren();
		} catch {}
		console.error(error);
		process.exit(1);
	};
	process.on("uncaughtException", onException);
	const offRejection = onUnhandledRejection((error) => {
		process.emit("uncaughtException", error);
	});
	return () => {
		process.off("uncaughtException", onException);
		offRejection();
	};
}

/** The reason when it is an Error, else the error Node raises for such a rejection. */
function rejectionError(reason: unknown): Error {
	if (reason instanceof Error) return reason;
	const error = new Error(
		"This error originated either by throwing inside of an async function without a catch block, or by rejecting a " +
			`promise which was not handled with .catch(). The promise rejected with the reason "${inspect(reason)}".`,
	);
	error.name = "UnhandledPromiseRejection";
	return Object.assign(error, { code: "ERR_UNHANDLED_REJECTION" });
}
