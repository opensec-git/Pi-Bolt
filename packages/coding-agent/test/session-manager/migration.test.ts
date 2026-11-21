import { describe, expect, it, vi } from "vitest";
import { type FileEntry, migrateSessionEntries } from "../../src/core/session-manager.ts";

describe("migrateSessionEntries", () => {
	it("retries a repeated short ID before linking the next legacy entry", () => {
		const entries: FileEntry[] = [
			{ type: "session", id: "legacy", cwd: "/tmp", timestamp: "2025-01-01T00:00:00Z" },
			...["one", "two"].map((name) => ({
				type: "session_info" as const,
				id: "",
				parentId: null,
				timestamp: "2025-01-01T00:00:00Z",
				name,
			})),
		];
		const random = vi
			.spyOn(crypto, "randomUUID")
			.mockReturnValueOnce("12345678-0000-4000-8000-000000000001")
			.mockReturnValueOnce("12345678-0000-4000-8000-000000000002")
			.mockReturnValueOnce("abcdef01-0000-4000-8000-000000000003");
		try {
			migrateSessionEntries(entries);
			expect(entries[1]).toMatchObject({ id: "12345678", parentId: null });
			expect(entries[2]).toMatchObject({ id: "abcdef01", parentId: "12345678" });
		} finally {
			random.mockRestore();
		}
	});

	it("should add id/parentId to v1 entries", () => {
		const entries: FileEntry[] = [
			{ type: "session", id: "sess-1", timestamp: "2025-01-01T00:00:00Z", cwd: "/tmp" },
			{ type: "message", timestamp: "2025-01-01T00:00:01Z", message: { role: "user", content: "hi", timestamp: 1 } },
			{
				type: "message",
				timestamp: "2025-01-01T00:00:02Z",
				message: {
					role: "assistant",
					content: [{ type: "text", text: "hello" }],
					api: "test",
					provider: "test",
					model: "test",
					usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0 },
					stopReason: "stop",
					timestamp: 2,
				},
			},
		] as FileEntry[];

		migrateSessionEntries(entries);

		// Header should have version set (v3 is current after hookMessage->custom migration)
		expect((entries[0] as any).version).toBe(3);

		// Entries should have id/parentId
		const msg1 = entries[1] as any;
		const msg2 = entries[2] as any;

		expect(msg1.id).toBeDefined();
		expect(msg1.id.length).toBe(8);
		expect(msg1.parentId).toBeNull();

		expect(msg2.id).toBeDefined();
		expect(msg2.id.length).toBe(8);
		expect(msg2.parentId).toBe(msg1.id);
	});

	it("should be idempotent (skip already migrated)", () => {
		const entries: FileEntry[] = [
			{ type: "session", id: "sess-1", version: 2, timestamp: "2025-01-01T00:00:00Z", cwd: "/tmp" },
			{
				type: "message",
				id: "abc12345",
				parentId: null,
				timestamp: "2025-01-01T00:00:01Z",
				message: { role: "user", content: "hi", timestamp: 1 },
			},
			{
				type: "message",
				id: "def67890",
				parentId: "abc12345",
				timestamp: "2025-01-01T00:00:02Z",
				message: {
					role: "assistant",
					content: [{ type: "text", text: "hello" }],
					api: "test",
					provider: "test",
					model: "test",
					usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0 },
					stopReason: "stop",
					timestamp: 2,
				},
			},
		] as FileEntry[];

		migrateSessionEntries(entries);

		// IDs should be unchanged
		expect((entries[1] as any).id).toBe("abc12345");
		expect((entries[2] as any).id).toBe("def67890");
		expect((entries[2] as any).parentId).toBe("abc12345");
	});
});
