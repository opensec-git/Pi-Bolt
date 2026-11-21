import { afterEach, expect, it } from "vitest";
import {
	cleanupSessionResources,
	clearSessionRequestCaches,
	registerSessionResourceCleanup,
} from "../src/session-resources.ts";
import { jsonBodyParts } from "../src/utils/request-body.ts";
import { beginRequestCache } from "../src/utils/request-cache.ts";

afterEach(() => cleanupSessionResources());

it("clears the compacted session's body cache without closing its provider resources", () => {
	const closed: (string | undefined)[] = [];
	const unregister = registerSessionResourceCleanup((id) => closed.push(id));
	try {
		const body = { messages: [{ role: "user", content: "test" }] };
		const encode = (id: string) => jsonBodyParts(body, "messages", id)![2];
		const first = encode("compacted");
		const other = encode("active");
		clearSessionRequestCaches("compacted");
		expect(encode("compacted")).not.toBe(first);
		expect(encode("active")).toBe(other);
		expect(closed).toEqual([]);
		cleanupSessionResources("compacted");
		expect(closed).toEqual(["compacted"]);
	} finally {
		unregister();
	}
});

it("drops only the disposed session's encoded bodies and model conversions", async () => {
	const body = {
		messages: [
			{ role: "user", content: "first" },
			{ role: "user", content: "second" },
		],
	};
	const encode = (id: string) => jsonBodyParts(body, "messages", id)!;
	const run = (id: string, model: string) =>
		beginRequestCache("test", { immutableMessages: true, sessionId: id }, [model])!;
	function convert(id: string, model: string) {
		const request = run(id, model);
		const result = request.derive(body.messages[0], "", () => ({}));
		request.end();
		return result;
	}
	const closed = "project";
	const live = "project-fork";
	const oldParts = encode(closed);
	const liveParts = encode(live);
	expect(encode(closed)[2]).toBe(oldParts[2]);
	for (const id of [closed, live]) {
		convert(id, "a");
		convert(id, "b");
	}
	const oldA = convert(closed, "a");
	const oldB = convert(closed, "b");
	const liveA = convert(live, "a");
	const pending = run(closed, "a");
	cleanupSessionResources(closed);
	pending.end();
	expect(convert(closed, "a")).not.toBe(oldA);
	expect(convert(closed, "b")).not.toBe(oldB);
	expect(convert(live, "a")).toBe(liveA);
	expect(encode(live)[2]).toBe(liveParts[2]);
	expect(encode(closed)[2]).not.toBe(oldParts[2]);
	expect(await new Blob(oldParts).text()).toBe(JSON.stringify(body));

	cleanupSessionResources();
	expect(convert(live, "a")).not.toBe(liveA);
	expect(encode(live)[2]).not.toBe(liveParts[2]);
});
