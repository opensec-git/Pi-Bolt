import { readdirSync, readFileSync } from "node:fs";
import { compile } from "@earendil-works/pi-schema";
import { afterEach, describe, expect, it, vi } from "vitest";
import { runViewDialogs, type ViewDialogs } from "../src/core/mobile-ui/dialogs.ts";
import { type ViewChannel, ViewHost, type ViewRequest, type ViewShowRequest } from "../src/core/mobile-ui/host.ts";
import { applyViewOps } from "../src/core/mobile-ui/ops.ts";
import { chartRange, chartSummary, cleanText, sortedRows, viewToLines } from "../src/core/mobile-ui/text.ts";
import type { MobileView, MobileViewChart, MobileViewTable, ViewEvent } from "../src/core/mobile-ui/types.ts";
import { validateTile, validateView } from "../src/core/mobile-ui/validate.ts";

/** Every component of kit version 1 once. */
const EVERY_COMPONENT: MobileView = {
	v: 1,
	title: "Deploy",
	subtitle: "production",
	icon: "rocket",
	body: [
		{ type: "text", text: "Ready to ship.", style: "title" },
		{ type: "markdown", text: "**3** commits, see [the log](https://example.com/log)." },
		{ type: "code", text: "npm run deploy", language: "bash", title: "Command" },
		{ type: "image", src: "data:image/png;base64,iVBORw0KGgo=", alt: "Diagram" },
		{ type: "image", src: "https://example.com/a.png", decorative: true },
		{ type: "divider" },
		{ type: "notice", tone: "warning", title: "Migration pending", text: "Run it first." },
		{
			type: "chart",
			id: "build-time",
			kind: "line",
			title: "Build time",
			unit: "s",
			labels: ["Mon", "Tue", "Wed"],
			series: [
				{ name: "main", values: [41, null, 38] },
				{ name: "dev", values: [52, 47, 45] },
			],
		},
		{ type: "chart", kind: "sparkline", series: [{ name: "Load", values: [1, 3, 2] }], summary: "Load is steady" },
		{
			type: "table",
			id: "deps",
			filter: true,
			sort: { column: "size", desc: true },
			columns: [
				{ id: "name", label: "Package", sortable: true },
				{ id: "size", label: "Size", kind: "number", sortable: true },
				{ id: "updated", label: "Updated", kind: "date" },
			],
			rows: [
				{ id: "r1", cells: { name: "undici", size: 1532, updated: "2026-10-30" }, action: "open" },
				{ id: "r2", cells: { name: "chalk", size: null } },
			],
		},
		{ type: "location", name: "Office", address: "1 Market St", lat: 37.79, lon: -122.39 },
		{
			type: "kv",
			items: [
				{ key: "Branch", value: "main" },
				{ key: "Commit", value: "1a2b3c4", mono: true, copy: true },
			],
		},
		{ type: "progress", id: "bar", value: 0.6, label: "Uploading" },
		{ type: "progress", label: "Waiting" },
		{
			type: "row",
			children: [
				{ type: "chip", id: "state", label: "Running", tone: "running" },
				{ type: "button", id: "logs", label: "Logs" },
			],
		},
		{
			type: "grid",
			columns: 2,
			children: [
				{ type: "tile", id: "builds", label: "Builds", value: "42", action: "open" },
				{ type: "tile", label: "Failures", value: "1", tone: "danger" },
			],
		},
		{
			type: "card",
			id: "card",
			title: "Checks",
			status: { label: "Passed", tone: "success" },
			children: [
				{
					type: "list",
					id: "jobs",
					empty: "No jobs",
					children: [
						{
							type: "item",
							id: "job-1",
							title: "Build #1",
							subtitle: "main",
							value: "2m",
							chip: { label: "Passed", tone: "success" },
							action: "open",
							chevron: true,
						},
					],
				},
			],
			actions: [{ type: "button", id: "rerun", label: "Run again", style: "primary" }],
		},
		{
			type: "section",
			title: "Settings",
			children: [
				{
					type: "form",
					id: "settings",
					children: [
						{ type: "input", id: "branch", label: "Branch", value: "main", required: true },
						{ type: "input", id: "token", label: "Token", kind: "password" },
						{ type: "toggle", id: "migrate", label: "Run migrations", value: true },
						{ type: "select", id: "region", label: "Region", options: ["eu", "us"], value: "eu" },
						{ type: "slider", id: "replicas", label: "Replicas", min: 1, max: 5, value: 2 },
					],
					submit: { label: "Save", action: "save" },
				},
			],
		},
		{ type: "toggle", id: "notify", label: "Notify me", value: false },
		{ type: "button", id: "docs", label: "Docs", url: "https://example.com/docs" },
	],
	actions: [
		{ type: "button", id: "cancel", label: "Cancel", style: "danger", confirm: { title: "Cancel the deploy?" } },
	],
};

function view(body: unknown[], extra: Record<string, unknown> = {}): unknown {
	return { v: 1, body, ...extra };
}

describe("mobile UI validation", () => {
	it("accepts every component", () => {
		expect(validateView(EVERY_COMPONENT)).toEqual([]);
	});

	it("accepts the skill's example views", () => {
		const dir = new URL("../../../skills/pibolt-mobile-ui/examples/", import.meta.url);
		const files = readdirSync(dir).filter((name) => name.endsWith(".json"));
		expect(files.length).toBeGreaterThan(0);
		for (const name of files) {
			const parsed = JSON.parse(readFileSync(new URL(name, dir), "utf8"));
			const views: unknown[] = parsed.views ?? (parsed.tiles ? [] : [parsed]);
			for (const value of views) expect(validateView(value), name).toEqual([]);
			for (const tile of parsed.tiles ?? []) {
				expect(validateView(tile), name).toEqual([]);
				expect(validateTile(tile), name).toEqual([]);
			}
		}
	});

	it("names each problem with its place", () => {
		expect(validateView({ body: [] })).toEqual(["v: missing"]);
		expect(validateView(view([{ type: "rwo" }]))).toEqual(['body[0]: unknown type "rwo"']);
		expect(validateView(view([{ type: "text", text: "a", colour: "red" }]))).toEqual([
			"body[0]: colour: unknown field",
		]);
		expect(validateView(view([{ type: "button", id: "b" }]))).toEqual(["body[0]: label: missing"]);
		expect(validateView(view([{ type: "button", label: "Go" }]))).toEqual([
			"body[0]: id: needed, the button sends events",
		]);
		expect(validateView(view([{ type: "list", children: [{ type: "text", text: "x" }] }]))).toEqual([
			'body[0].children[0]: a list cannot hold "text"',
		]);
		expect(
			validateView(
				view([
					{ type: "chip", id: "a", label: "x" },
					{ type: "card", children: [{ type: "chip", id: "a", label: "y" }] },
				]),
			),
		).toEqual(['body[1].children[0]: id "a" is used twice']);
	});

	it("allows only the app's link schemes and safe image sources", () => {
		expect(validateView(view([{ type: "button", label: "x", url: "javascript:alert(1)" }]))).toEqual([
			"body[0]: url: expected an https:, http: or mailto: link",
		]);
		expect(validateView(view([{ type: "button", label: "x", url: "intent://scan" }]))).toHaveLength(1);
		for (const src of [
			"http://example.com/a.png",
			"https://192.168.1.2/a.png",
			"https://localhost/a.png",
			"https://nas.local/a.png",
			"file:///etc/passwd",
			"data:text/html;base64,AAAA",
		]) {
			expect(validateView(view([{ type: "image", src, alt: "x" }])), src).toHaveLength(1);
		}
		expect(validateView(view([{ type: "image", src: "https://example.com/a.png" }]))).toEqual([
			"body[0]: alt: needed unless decorative: true",
		]);
	});

	it("checks field values against the field", () => {
		expect(validateView(view([{ type: "select", id: "s", label: "S", options: ["a"], value: "b" }]))).toEqual([
			"body[0]: value: not one of the options",
		]);
		expect(validateView(view([{ type: "slider", id: "s", label: "S", min: 5, max: 1, value: 2 }]))).toEqual([
			"body[0]: max: must be greater than min",
		]);
		expect(validateView(view([{ type: "input", id: "i", label: "I", maxLength: 3, value: "long" }]))).toEqual([
			"body[0]: value: longer than 3",
		]);
	});

	it("enforces the limits", () => {
		const many = Array.from({ length: 201 }, (_, i) => ({ type: "text", text: `${i}` }));
		expect(validateView(view(many))).toContain("body: more than 200 nodes");
		let deep: Record<string, unknown> = { type: "text", text: "x" };
		for (let i = 0; i < 8; i++) deep = { type: "section", children: [deep] };
		expect(validateView(view([deep]))[0]).toMatch(/nested deeper than 8/);
		expect(validateView(view([{ type: "text", text: "x".repeat(4001) }]))).toEqual([
			"body[0]: text: longer than 4000 characters",
		]);
		const big = Array.from({ length: 70 }, () => ({ type: "markdown", text: "x".repeat(4000) }));
		expect(validateView(view(big))[0]).toMatch(/^view: \d+ bytes, more than 262144$/);
		expect(
			validateView(view([], { actions: [1, 2, 3, 4].map((n) => ({ type: "button", id: `b${n}`, label: "x" })) })),
		).toEqual(["actions: more than 3 buttons"]);
	});
});

describe("mobile UI JSON Schema", () => {
	const schema = JSON.parse(readFileSync(new URL("../schemas/mobile-ui.schema.json", import.meta.url), "utf8"));
	const check = compile(schema);

	it("accepts what Pi accepts", () => {
		expect(check.errors(EVERY_COMPONENT)).toEqual([]);
	});

	it("refuses shapes Pi refuses", () => {
		for (const bad of [
			{ body: [] },
			view([{ type: "rwo" }]),
			view([{ type: "text", text: "a", colour: "red" }]),
			view([{ type: "list", children: [{ type: "text", text: "x" }] }]),
			view([{ type: "button", id: "b", label: "x", url: "javascript:alert(1)" }]),
			view([{ type: "chip", label: "x", icon: "unicorn" }]),
			view([{ type: "image", src: "file:///etc/passwd", alt: "x" }]),
		]) {
			expect(check.check(bad), JSON.stringify(bad)).toBe(false);
			expect(validateView(bad), JSON.stringify(bad)).not.toEqual([]);
		}
	});
});

describe("mobile UI ops", () => {
	const base: MobileView = {
		v: 1,
		body: [
			{ type: "list", id: "jobs", children: [{ type: "item", id: "a", title: "A" }] },
			{ type: "progress", id: "bar", value: 0 },
		],
	};

	it("sets, replaces, removes and inserts by id without touching the original", () => {
		const next = applyViewOps(base, [
			{ op: "set", id: "bar", props: { value: 0.5, label: "Half" } },
			{ op: "insert", parent: "jobs", index: 0, node: { type: "item", id: "b", title: "B" } },
			{ op: "replace", id: "a", node: { type: "item", id: "a2", title: "A2" } },
			{ op: "insert", node: { type: "divider" } },
			{ op: "set", id: "bar", props: { label: null } },
		]);
		expect(next.body).toEqual([
			{
				type: "list",
				id: "jobs",
				children: [
					{ type: "item", id: "b", title: "B" },
					{ type: "item", id: "a2", title: "A2" },
				],
			},
			{ type: "progress", id: "bar", value: 0.5 },
			{ type: "divider" },
		]);
		expect(applyViewOps(next, [{ op: "remove", id: "jobs" }]).body).toHaveLength(2);
		expect(base.body[1]).toEqual({ type: "progress", id: "bar", value: 0 });
	});

	it("refuses ops that cannot apply", () => {
		expect(() => applyViewOps(base, [{ op: "set", id: "nope", props: {} }])).toThrow(
			'ops[0]: no node with id "nope"',
		);
		expect(() => applyViewOps(base, [{ op: "set", id: "bar", props: { type: "chip" } }])).toThrow(
			"type cannot change",
		);
		expect(() => applyViewOps(base, [{ op: "insert", parent: "bar", node: { type: "divider" } }])).toThrow(
			"has no children",
		);
		expect(() => applyViewOps(base, [{ op: "insert", index: 9, node: { type: "divider" } }])).toThrow(
			"index: expected 0 to 2",
		);
	});
});

describe("mobile UI text form", () => {
	it("renders each component as plain lines", () => {
		const lines = viewToLines({
			v: 1,
			title: "Deploy",
			subtitle: "production",
			body: [
				{ type: "chip", label: "Running", tone: "running" },
				{ type: "progress", value: 0.6, label: "Uploading" },
				{
					type: "kv",
					items: [
						{ key: "Branch", value: "main" },
						{ key: "Commit", value: "1a2b3c4" },
					],
				},
				{
					type: "list",
					children: [
						{ type: "item", title: "Build #42", subtitle: "now", chip: { label: "Failed", tone: "danger" } },
					],
				},
				{ type: "notice", tone: "warning", title: "Migration pending", text: "Run it first." },
				{ type: "toggle", id: "t", label: "Notify", value: true },
				{ type: "text", text: "gone", hidden: true },
			],
			actions: [
				{ type: "button", id: "retry", label: "Retry" },
				{ type: "button", id: "logs", label: "Logs" },
			],
		});
		expect(lines).toEqual([
			"Deploy · production",
			"● Running",
			"[######----] 60%  Uploading",
			"Branch  main",
			"Commit  1a2b3c4",
			"• Build #42 — now  ✗ Failed",
			"! Migration pending: Run it first.",
			"Notify: on",
			"[ Retry ]  [ Logs ]",
		]);
	});

	it("uses the view's fallback lines and strips escape sequences", () => {
		expect(viewToLines({ v: 1, body: [], fallback: ["a\u001b[31mred\u001b[0m", "b\u0007"] })).toEqual(["ared", "b"]);
		expect(cleanText("\u001b]8;;https://x\u0007link\u001b]8;;\u0007")).toBe("link");
	});
});

function scriptedDialogs(answers: Array<string | boolean | undefined>): ViewDialogs & { asked: string[] } {
	const asked: string[] = [];
	// Out of answers: the dialog stays open, as a terminal no one answers.
	const next = () => (answers.length > 0 ? Promise.resolve(answers.shift()) : new Promise<undefined>(() => {}));
	return {
		asked,
		select: async (title: string, options: string[]) => {
			asked.push(`select ${title.split("\n").at(-1)} [${options.join("|")}]`);
			return (await next()) as string | undefined;
		},
		confirm: async (title: string) => {
			asked.push(`confirm ${title}`);
			return (await next()) === true;
		},
		input: async (title: string) => {
			asked.push(`input ${title}`);
			return (await next()) as string | undefined;
		},
	};
}

const SHEET: MobileView = {
	v: 1,
	title: "Deploy settings",
	body: [
		{
			type: "form",
			id: "f",
			children: [
				{ type: "input", id: "branch", label: "Branch", value: "main", required: true },
				{ type: "toggle", id: "migrate", label: "Run migrations", value: false },
				{
					type: "select",
					id: "region",
					label: "Region",
					options: [
						{ value: "eu", label: "Europe" },
						{ value: "us", label: "US" },
					],
				},
			],
			submit: { label: "Deploy", action: "deploy" },
		},
	],
	actions: [{ type: "button", id: "cancel", label: "Cancel", confirm: { title: "Discard?" } }],
};

describe("mobile UI sheets as dialogs", () => {
	it("picks the action, then asks the form's fields", async () => {
		const dialogs = scriptedDialogs(["Deploy", "", "On", "US"]);
		await expect(runViewDialogs(SHEET, dialogs)).resolves.toEqual({
			id: "f",
			action: "deploy",
			values: { branch: "main", migrate: true, region: "us" },
		});
		expect(dialogs.asked).toEqual([
			"select Deploy settings [Deploy|Cancel]",
			"input Branch (required)",
			"select Run migrations [Off|On]",
			"select Region [Europe|US]",
		]);
	});

	it("asks a sheet's fields outside forms before an action button", async () => {
		const sheet: MobileView = {
			v: 1,
			title: "Rename",
			body: [{ type: "input", id: "name", label: "Name", required: true }],
			actions: [{ type: "button", id: "save", label: "Save" }],
		};
		const dialogs = scriptedDialogs(["Save", "", "", "Bolt"]);
		await expect(runViewDialogs(sheet, dialogs)).resolves.toEqual({
			id: "save",
			action: "save",
			values: { name: "Bolt" },
		});
		expect(dialogs.asked).toEqual([
			"select Name:  [Save]",
			"input Name (required)",
			"input Name (required)",
			"input Name (required)",
		]);
	});

	it("asks a sheet's fields outside forms before a form's own", async () => {
		const sheet: MobileView = {
			v: 1,
			body: [
				{ type: "input", id: "name", label: "Name", required: true },
				{
					type: "form",
					id: "f",
					children: [{ type: "input", id: "branch", label: "Branch" }],
					submit: { label: "Go" },
				},
			],
		};
		const dialogs = scriptedDialogs(["Bolt", "main"]);
		await expect(runViewDialogs(sheet, dialogs)).resolves.toEqual({
			id: "f",
			action: "f",
			values: { name: "Bolt", branch: "main" },
		});
		expect(dialogs.asked).toEqual(["input Name (required)", "input Branch"]);
	});

	it("offers no form of a hidden container, and keeps options that share a label apart", async () => {
		const hidden: MobileView = {
			v: 1,
			body: [
				{
					type: "section",
					hidden: true,
					children: [{ type: "form", id: "f", children: [], submit: { label: "Go" } }],
				},
			],
		};
		const busy: MobileView = { v: 1, body: [], actions: [{ type: "button", id: "go", label: "Deploy", busy: true }] };
		const busyDialogs = scriptedDialogs(["Close"]);
		await expect(runViewDialogs(busy, busyDialogs)).resolves.toBeUndefined();
		expect(busyDialogs.asked).toEqual(["select  [Close]"]);
		const closeOnly = scriptedDialogs(["Close"]);
		await expect(runViewDialogs(hidden, closeOnly)).resolves.toBeUndefined();
		expect(closeOnly.asked).toEqual(["select  [Close]"]);
		const regions: MobileView = {
			v: 1,
			body: [
				{
					type: "form",
					id: "f",
					children: [
						{
							type: "select",
							id: "r",
							label: "Region",
							options: [
								{ value: "east", label: "US" },
								{ value: "west", label: "US" },
							],
						},
					],
					submit: { label: "Go" },
				},
			],
		};
		const dialogs = scriptedDialogs(["US (2)"]);
		await expect(runViewDialogs(regions, dialogs)).resolves.toMatchObject({ values: { r: "west" } });
		expect(dialogs.asked).toEqual(["select Region [US|US (2)]"]);
	});

	it("confirms a button with confirm, and cancels when any dialog is dismissed", async () => {
		await expect(runViewDialogs(SHEET, scriptedDialogs(["Cancel", true]))).resolves.toMatchObject({ id: "cancel" });
		await expect(runViewDialogs(SHEET, scriptedDialogs(["Cancel", false]))).resolves.toBeUndefined();
		await expect(runViewDialogs(SHEET, scriptedDialogs(["Deploy", undefined]))).resolves.toBeUndefined();
	});
});

interface FakeChannel extends ViewChannel {
	sent: ViewRequest[];
	sheets: ViewShowRequest[];
	native: boolean;
	answer?: (request: ViewShowRequest) => Promise<unknown>;
}

function fakeChannel(native: boolean, keepText = false): FakeChannel {
	const channel: FakeChannel = {
		sent: [],
		sheets: [],
		native,
		keepText,
		hasNative: () => channel.native,
		send: (request) => void channel.sent.push(structuredClone(request)),
		sheet: (request, signal) => {
			channel.sheets.push(request);
			if (channel.answer) return channel.answer(request) as Promise<undefined>;
			return new Promise((resolve) => signal.addEventListener("abort", () => resolve(undefined), { once: true }));
		},
	};
	return channel;
}

function hostWith(channel: ViewChannel | undefined, dialogs = scriptedDialogs([])) {
	const widgets = new Map<string, string[] | undefined>();
	const events: ViewEvent[] = [];
	const host = new ViewHost({
		ui: { ...dialogs, setWidget: (key, lines) => widgets.set(key, lines) },
		channel,
		emit: (event) => events.push(event),
	});
	return { host, widgets, events };
}

const PANEL: MobileView = {
	v: 1,
	title: "Deploy",
	body: [
		{ type: "progress", id: "bar", value: 0 },
		{ type: "list", id: "jobs", children: [{ type: "item", id: "job-1", title: "Build #1", action: "open" }] },
		{ type: "toggle", id: "notify", label: "Notify", value: false },
		{
			type: "form",
			id: "f",
			children: [{ type: "input", id: "name", label: "Name", required: true, maxLength: 5 }],
			submit: { label: "Save" },
		},
	],
	actions: [
		{ type: "button", id: "cancel", label: "Cancel" },
		{ type: "button", id: "off", label: "Off", disabled: true },
	],
};

describe("mobile UI host", () => {
	afterEach(() => {
		vi.useRealTimers();
	});

	it("sends views to clients that render them and text to the others", () => {
		const native = fakeChannel(true);
		const a = hostWith(native);
		a.host.set("deploy", PANEL);
		expect(native.sent).toEqual([{ method: "setView", viewKey: "deploy", slot: "panel", view: PANEL, rev: 1 }]);
		expect(a.widgets.size).toBe(0);

		const text = hostWith(fakeChannel(false));
		text.host.set("deploy", PANEL);
		expect(text.widgets.get("view:deploy")?.[0]).toBe("Deploy");
		text.host.set("deploy", undefined);
		expect(text.widgets.get("view:deploy")).toBeUndefined();
		expect(text.widgets.has("view:deploy")).toBe(true);
	});

	it("keeps the text form beside native clients when the channel says so (a terminal with phones)", () => {
		const channel = fakeChannel(true, true);
		const { host, widgets } = hostWith(channel);
		host.set("deploy", PANEL);
		expect(channel.sent).toHaveLength(1);
		expect(widgets.get("view:deploy")).toBeDefined();
	});

	it("sends the first update at once and merges the rest of 100 ms into one full view", () => {
		vi.useFakeTimers();
		const channel = fakeChannel(true);
		const { host } = hostWith(channel);
		host.set("deploy", PANEL);
		vi.advanceTimersByTime(200);
		host.update("deploy", [{ op: "set", id: "bar", props: { value: 0.1 } }]);
		for (let i = 2; i <= 9; i++) host.update("deploy", [{ op: "set", id: "bar", props: { value: i / 10 } }]);
		expect(channel.sent.map((r) => r.method)).toEqual(["setView", "updateView"]);
		expect(channel.sent[1]).toMatchObject({ rev: 2, ops: [{ op: "set", id: "bar", props: { value: 0.1 } }] });
		vi.advanceTimersByTime(100);
		expect(channel.sent).toHaveLength(3);
		expect(channel.sent[2]).toMatchObject({ method: "setView", rev: 10 });
		expect((channel.sent[2] as { view: MobileView }).view.body[0]).toEqual({
			type: "progress",
			id: "bar",
			value: 0.9,
		});
	});

	it("refuses an invalid view or update and keeps the last good state", () => {
		const { host } = hostWith(fakeChannel(true));
		expect(() =>
			host.set("deploy", { v: 1, body: [{ type: "button", label: "x" }] } as unknown as MobileView),
		).toThrow('Invalid view "deploy": body[0]: id: needed');
		host.set("deploy", PANEL);
		expect(() => host.update("deploy", [{ op: "set", id: "bar", props: { value: 2 } }])).toThrow(
			"value: expected 0 to 1",
		);
		expect(host.get("deploy")).toEqual(PANEL);
		expect(() => host.set("bad key!", PANEL)).toThrow("Invalid view key");
		for (let i = 1; i < 16; i++) host.set(`v${i}`, PANEL);
		expect(() => host.set("one-more", PANEL)).toThrow("Too many views");
	});

	it("checks client events against the view before emitting them, and stores committed values", () => {
		const { host, events } = hostWith(fakeChannel(true));
		host.set("deploy", PANEL);
		expect(host.handleEvent({ viewKey: "deploy", nodeId: "job-1", kind: "tap" })).toBe(true);
		expect(host.handleEvent({ viewKey: "deploy", nodeId: "cancel", kind: "tap" })).toBe(true);
		expect(host.handleEvent({ viewKey: "deploy", nodeId: "notify", kind: "change", value: true })).toBe(true);
		expect(host.handleEvent({ viewKey: "deploy", nodeId: "f", kind: "submit", values: { name: "bob" } })).toBe(true);
		expect(events).toEqual([
			{ type: "view_event", key: "deploy", id: "job-1", action: "open", kind: "tap" },
			{ type: "view_event", key: "deploy", id: "cancel", action: "cancel", kind: "tap" },
			{ type: "view_event", key: "deploy", id: "notify", action: "notify", kind: "change", value: true },
			{ type: "view_event", key: "deploy", id: "f", action: "f", kind: "submit", values: { name: "bob" } },
		]);
		const stored = host.get("deploy");
		expect(stored?.body[2]).toMatchObject({ value: true });
		// Dropped: unknown view or node, a disabled button, a tap on a field, a change of a form's field, a value that
		// does not fit, a missing required field, a field the form does not have.
		for (const bad of [
			{ viewKey: "nope", nodeId: "cancel", kind: "tap" },
			{ viewKey: "deploy", nodeId: "nope", kind: "tap" },
			{ viewKey: "deploy", nodeId: "off", kind: "tap" },
			{ viewKey: "deploy", nodeId: "notify", kind: "tap" },
			{ viewKey: "deploy", nodeId: "name", kind: "change", value: "x" },
			{ viewKey: "deploy", nodeId: "notify", kind: "change", value: "yes" },
			{ viewKey: "deploy", nodeId: "f", kind: "submit", values: { name: "" } },
			{ viewKey: "deploy", nodeId: "f", kind: "submit", values: { name: "toolong" } },
			{ viewKey: "deploy", nodeId: "f", kind: "submit", values: { name: "a", extra: 1 } },
			{ viewKey: "deploy", nodeId: "f", kind: "explode" },
		]) {
			expect(host.handleEvent(bad), JSON.stringify(bad)).toBe(false);
		}
		expect(events).toHaveLength(4);
	});

	it("drops events for nodes inside a hidden container", () => {
		const { host, events } = hostWith(fakeChannel(true));
		host.set("deploy", {
			v: 1,
			body: [
				{
					type: "section",
					id: "more",
					hidden: true,
					children: [{ type: "button", id: "nuke", label: "Delete all" }],
				},
			],
		});
		expect(host.handleEvent({ viewKey: "deploy", nodeId: "nuke", kind: "tap" })).toBe(false);
		host.update("deploy", [{ op: "set", id: "more", props: { hidden: null } }]);
		expect(host.handleEvent({ viewKey: "deploy", nodeId: "nuke", kind: "tap" })).toBe(true);
		expect(events).toHaveLength(1);
	});

	it("drops events beyond 20 per second per view", () => {
		const { host, events } = hostWith(fakeChannel(true));
		host.set("deploy", PANEL);
		for (let i = 0; i < 30; i++) host.handleEvent({ viewKey: "deploy", nodeId: "cancel", kind: "tap" });
		expect(events.length).toBeLessThanOrEqual(20);
	});

	it("resolves a sheet with a native client's checked answer, and as dismissed on cancel or a wrong answer", async () => {
		const channel = fakeChannel(true);
		const { host } = hostWith(channel);
		channel.answer = async () => ({ nodeId: "f", values: { branch: "dev", migrate: true, region: "eu" } });
		await expect(host.show(SHEET, { key: "settings" })).resolves.toEqual({
			id: "f",
			action: "deploy",
			values: { branch: "dev", migrate: true, region: "eu" },
		});
		expect(channel.sheets[0]).toMatchObject({ method: "showView", viewKey: "settings" });
		channel.answer = async () => ({ cancelled: true });
		await expect(host.show(SHEET)).resolves.toBeUndefined();
		channel.answer = async () => ({ nodeId: "f", values: { region: "mars" } });
		await expect(host.show(SHEET)).resolves.toBeUndefined();
		channel.answer = async () => ({ nodeId: "cancel" });
		await expect(host.show(SHEET)).resolves.toMatchObject({ id: "cancel", action: "cancel" });
	});

	it("holds a sheet's answer to the required fields that go with it", async () => {
		const channel = fakeChannel(true);
		const { host } = hostWith(channel);
		const two: MobileView = {
			v: 1,
			body: [
				{
					type: "form",
					id: "a",
					children: [{ type: "input", id: "x", label: "X", required: true }],
					submit: { label: "A" },
				},
				{
					type: "form",
					id: "b",
					children: [{ type: "input", id: "y", label: "Y", required: true }],
					submit: { label: "B" },
				},
			],
		};
		channel.answer = async () => ({ nodeId: "a", values: { x: "1" } });
		await expect(host.show(two)).resolves.toEqual({ id: "a", action: "a", values: { x: "1", y: "" } });
		channel.answer = async () => ({ nodeId: "a", values: { y: "2" } });
		await expect(host.show(two)).resolves.toBeUndefined();
	});

	it("leaves fields inside hidden containers out of a sheet's answer", async () => {
		const channel = fakeChannel(true);
		const { host } = hostWith(channel);
		const sheet: MobileView = {
			v: 1,
			body: [
				{
					type: "section",
					hidden: true,
					children: [{ type: "input", id: "secret", label: "Secret", required: true }],
				},
				{
					type: "form",
					id: "f",
					children: [{ type: "input", id: "name", label: "Name", required: true }],
					submit: { label: "Go" },
				},
			],
		};
		channel.answer = async () => ({ nodeId: "f", values: { name: "bob" } });
		await expect(host.show(sheet)).resolves.toEqual({ id: "f", action: "f", values: { name: "bob" } });
	});

	it("runs a sheet as dialogs without native clients, and races both with a terminal", async () => {
		const text = hostWith(fakeChannel(false), scriptedDialogs(["Cancel", true]));
		await expect(text.host.show(SHEET)).resolves.toMatchObject({ id: "cancel" });

		const channel = fakeChannel(true, true);
		let answerPhone: (value: unknown) => void = () => {};
		channel.answer = () =>
			new Promise((resolve) => {
				answerPhone = resolve;
			});
		const both = hostWith(channel, scriptedDialogs([]));
		const result = both.host.show(SHEET, { key: "s" });
		await Promise.resolve();
		both.host.update("s", [{ op: "set", id: "branch", props: { value: "next" } }]);
		expect(channel.sent).toMatchObject([{ method: "updateView", viewKey: "s", rev: 2 }]);
		answerPhone({ nodeId: "f", values: {} });
		await expect(result).resolves.toMatchObject({ values: { branch: "next" } });
	});

	it("dismisses a sheet whose channel closed it when no dialogs race it", async () => {
		const channel = fakeChannel(true);
		channel.answer = async () => undefined;
		const { host } = hostWith(channel);
		await expect(host.show(SHEET)).resolves.toBeUndefined();
	});

	it("checks a sheet answered as dialogs against the sheet as it is then", async () => {
		let pick: (value: string) => void = () => {};
		const dialogs: ViewDialogs = {
			select: () =>
				new Promise((resolve) => {
					pick = resolve as (value: string) => void;
				}),
			confirm: async () => true,
			input: async () => "",
		};
		const { host } = hostWith(fakeChannel(false), dialogs as ReturnType<typeof scriptedDialogs>);
		const sheet: MobileView = {
			v: 1,
			body: [],
			actions: [
				{ type: "button", id: "go", label: "Deploy" },
				{ type: "button", id: "no", label: "Cancel" },
			],
		};
		const result = host.show(sheet, { key: "s" });
		await Promise.resolve();
		host.update("s", [{ op: "set", id: "go", props: { disabled: true } }]);
		pick("Deploy");
		await expect(result).resolves.toBeUndefined();
	});

	it("ends a sheet on its timeout", async () => {
		const { host } = hostWith(fakeChannel(true));
		await expect(host.show(SHEET, { timeout: 10 })).resolves.toBeUndefined();
	});

	it("replays every view when a client starts rendering them, and removes them on dispose", () => {
		const channel = fakeChannel(false);
		const { host, widgets } = hostWith(channel);
		host.set("a", PANEL);
		host.set("b", PANEL, { slot: "inline", toolCallId: "call-1" });
		expect(widgets.get("view:a")).toBeDefined();
		channel.native = true;
		host.resync();
		expect(widgets.get("view:a")).toBeUndefined();
		expect(channel.sent).toMatchObject([
			{ method: "setView", viewKey: "a", slot: "panel", rev: 1 },
			{ method: "setView", viewKey: "b", slot: "inline", rev: 1, toolCallId: "call-1" },
		]);
		expect(host.snapshot().map((s) => s.viewKey)).toEqual(["a", "b"]);
		host.dispose();
		expect(channel.sent.slice(2)).toEqual([
			{ method: "setView", viewKey: "a", slot: "panel", rev: 2 },
			{ method: "setView", viewKey: "b", slot: "inline", rev: 2 },
		]);
	});
});

describe("mobile UI charts, tables and places", () => {
	it("refuses charts whose series do not fit", () => {
		const chart = (props: Record<string, unknown>) =>
			view([{ type: "chart", kind: "line", series: [{ name: "a", values: [1, 2] }], ...props }]);
		expect(validateView(chart({}))).toEqual([]);
		expect(
			validateView(
				chart({
					series: [
						{ name: "a", values: [1, 2] },
						{ name: "b", values: [1] },
					],
				}),
			),
		).toEqual(["body[0]: series: [1]: 1 values, the first series has 2"]);
		expect(
			validateView(
				chart({
					kind: "sparkline",
					series: [
						{ name: "a", values: [1] },
						{ name: "b", values: [1] },
					],
				}),
			),
		).toEqual(["body[0]: series: more than 1 series in a sparkline"]);
		expect(validateView(chart({ labels: ["Mon"] }))).toEqual(["body[0]: labels: 1 labels for 2 values"]);
		expect(validateView(chart({ series: [{ name: "a", values: [1, "2"] }] }))).toEqual([
			"body[0]: series: [0]: values: expected numbers or null",
		]);
		expect(validateView(chart({ min: 5, max: 5 }))).toEqual(["body[0]: max: must be greater than min"]);
		expect(validateView(chart({ series: [{ name: "a", values: [1, 1.7e308] }] }))).toEqual([
			"body[0]: series: [0]: values: expected numbers of at most 1000000000000000 in size",
		]);
		expect(validateView(chart({ max: 1e16 }))).toEqual([
			"body[0]: max: expected -1000000000000000 to 1000000000000000",
		]);
		expect(validateView(chart({ series: [{ name: "a", values: new Array(201).fill(1) }] }))).toEqual([
			"body[0]: series: [0]: values: more than 200 values",
		]);
	});

	it("refuses tables whose rows do not fit their columns, and asks an id of a table with tappable rows", () => {
		const table = (props: Record<string, unknown>) =>
			view([
				{
					type: "table",
					id: "t",
					columns: [{ id: "a", label: "A" }],
					rows: [{ id: "r1", cells: { a: "x" } }],
					...props,
				},
			]);
		expect(validateView(table({}))).toEqual([]);
		expect(validateView(table({ rows: [{ id: "r1", cells: { b: "x" } }] }))).toEqual([
			"body[0]: rows: [0]: cells: b: no such column",
		]);
		expect(
			validateView(
				table({
					rows: [
						{ id: "r1", cells: {} },
						{ id: "r1", cells: {} },
					],
				}),
			),
		).toEqual(['body[0]: rows: [1]: row "r1" twice']);
		expect(validateView(table({ sort: { column: "z" } }))).toEqual(['body[0]: sort: column: no column "z"']);
		expect(
			validateView(
				table({
					columns: [
						{ id: "a", label: "A" },
						{ id: "a", label: "B" },
					],
				}),
			),
		).toEqual(['body[0]: columns: [1]: column "a" twice']);
		expect(
			validateView(
				view([
					{ type: "table", columns: [{ id: "a", label: "A" }], rows: [{ id: "r", cells: {}, action: "open" }] },
				]),
			),
		).toEqual(["body[0]: id: needed, the table sends events"]);
	});

	it("needs a place's coordinates together, or its address", () => {
		const place = (props: Record<string, unknown>) => view([{ type: "location", name: "Office", ...props }]);
		expect(validateView(place({ address: "1 Market St" }))).toEqual([]);
		expect(validateView(place({ lat: 1, lon: 2 }))).toEqual([]);
		expect(validateView(place({}))).toEqual(["body[0]: address: needed without lat and lon"]);
		expect(validateView(place({ lat: 1 }))).toEqual(["body[0]: lat, lon: give both or neither"]);
		expect(validateView(place({ lat: 91, lon: 0 }))).toEqual(["body[0]: lat: expected -90 to 90"]);
	});

	it("renders charts as sparklines with their last, low and high, tables aligned, and places as one line", () => {
		const lines = viewToLines({
			v: 1,
			body: [
				{
					type: "chart",
					kind: "bar",
					title: "Builds",
					series: [
						{ name: "Passed", values: [0, 4, 8] },
						{ name: "Failed", values: [2, null, 0] },
					],
				},
				{
					type: "table",
					columns: [
						{ id: "name", label: "Package" },
						{ id: "size", label: "Size", kind: "number" },
					],
					sort: { column: "size", desc: true },
					rows: [
						{ id: "a", cells: { name: "chalk", size: 44 } },
						{ id: "b", cells: { name: "undici", size: 1532 } },
						{ id: "c", cells: { name: "none", size: null } },
					],
				},
				{ type: "location", name: "Office", address: "1 Market St", lat: 37.79, lon: -122.39 },
			],
		});
		expect(lines).toEqual([
			"Builds",
			"Passed  ▁▅█  last 8 · low 0 · high 8",
			"Failed  ▃ ▁  last 0 · low 0 · high 2",
			"Package  Size",
			"undici   1532",
			"chalk      44",
			"none",
			"⌖ Office — 1 Market St (37.79, -122.39)",
		]);
		const rows = Array.from({ length: 53 }, (_, i) => ({ id: `r${i}`, cells: { n: i } }));
		const long = viewToLines({ v: 1, body: [{ type: "table", columns: [{ id: "n", label: "N" }], rows }] });
		expect(long).toHaveLength(52);
		expect(long.at(-1)).toBe("… 3 more rows");
	});

	it("says what a chart shows for screen readers, and sorts table rows by their column's kind", () => {
		const chart: MobileViewChart = {
			type: "chart",
			kind: "line",
			title: "Build time",
			unit: "s",
			series: [{ name: "main", values: [52, 41, null] }],
		};
		expect(chartSummary(chart)).toBe("Line chart, Build time: last 41 s, low 41 s, high 52 s");
		expect(chartRange({ type: "chart", kind: "bar", series: [{ name: "a", values: [-5, -2] }] })).toEqual({
			min: -5,
			max: 0,
		});
		expect(chartRange({ type: "chart", kind: "line", series: [{ name: "a", values: [-5, -2] }] })).toEqual({
			min: -5,
			max: -2,
		});
		// A flat series keeps a span relative to its size.
		expect(chartRange({ type: "chart", kind: "line", series: [{ name: "a", values: [1e18, 1e18] }] })).toEqual({
			min: 0.9e18,
			max: 1.1e18,
		});
		expect(chartRange({ type: "chart", kind: "sparkline", series: [{ name: "a", values: [0, 0] }] })).toEqual({
			min: -1,
			max: 1,
		});
		expect(chartSummary({ ...chart, summary: "Faster" })).toBe("Faster");
		const table: MobileViewTable = {
			type: "table",
			columns: [{ id: "d", label: "Date", kind: "date" }],
			sort: { column: "d" },
			rows: [
				{ id: "a", cells: { d: "2026-10-30" } },
				{ id: "b", cells: { d: null } },
				{ id: "c", cells: { d: "2026-01-02" } },
			],
		};
		expect(sortedRows(table).map((row) => row.id)).toEqual(["c", "a", "b"]);
		expect(sortedRows({ ...table, sort: { column: "d", desc: true } }).map((row) => row.id)).toEqual(["a", "c", "b"]);
	});
});

describe("mobile UI Home tiles", () => {
	const TILE: MobileView = {
		v: 1,
		title: "Board",
		icon: "flag",
		body: [
			{ type: "text", text: "3 open" },
			{ type: "chart", kind: "sparkline", series: [{ name: "Done", values: [1, 0, 2] }] },
		],
	};

	it("holds a tile to its title, node types and size", () => {
		expect(validateTile(TILE)).toEqual([]);
		expect(validateTile({ ...TILE, title: undefined })).toEqual(["title: a tile needs one"]);
		expect(
			validateTile({
				...TILE,
				actions: [{ type: "button", id: "x", label: "X" }],
				body: [
					{ type: "row", children: [{ type: "button", id: "b", label: "Go" }] },
					{ type: "kv", items: Array.from({ length: 5 }, (_, i) => ({ key: `k${i}`, value: "v" })) },
				],
			}),
		).toEqual([
			"actions: a tile has no buttons",
			'body[0].children[0]: a tile cannot hold "button"',
			"body[1]: more than 4 items in a tile",
		]);
		expect(validateTile({ ...TILE, body: Array.from({ length: 25 }, () => ({ type: "text", text: "x" })) })).toEqual([
			"body: more than 24 nodes in a tile",
		]);
	});

	it("takes the tile slot only for an open registered tile, and sends its changes at most once a second", () => {
		vi.useFakeTimers();
		const channel = fakeChannel(true);
		const { host, widgets } = hostWith(channel);
		expect(() => host.set("board:tile", TILE, { slot: "tile" })).toThrow(/only for an open registered tile/);
		host.allowTile("board:tile", true);
		expect(() => host.set("board:tile", { ...TILE, body: [{ type: "divider" }] }, { slot: "tile" })).toThrow(
			/a tile cannot hold "divider"/,
		);
		host.set("board:tile", TILE, { slot: "tile" });
		expect(() => host.update("board:tile", [{ op: "insert", node: { type: "divider" } }])).toThrow(/Invalid tile/);
		vi.advanceTimersByTime(1000);
		for (let i = 0; i < 5; i++) host.update("board:tile", [{ op: "insert", node: { type: "text", text: `${i}` } }]);
		expect(channel.sent).toHaveLength(2);
		vi.advanceTimersByTime(500);
		expect(channel.sent).toHaveLength(2);
		vi.advanceTimersByTime(500);
		expect(channel.sent).toHaveLength(3);
		expect(channel.sent[1]).toMatchObject({ method: "updateView", rev: 2 });
		expect(channel.sent[2]).toMatchObject({ method: "setView", slot: "tile", rev: 6 });
		// Tiles have no text form.
		expect(widgets.size).toBe(0);
	});

	it("emits a table row tap with the row's id as the value, and drops taps on rows without an action", () => {
		const { host, events } = hostWith(fakeChannel(true));
		host.set("deps", {
			v: 1,
			body: [
				{
					type: "table",
					id: "t",
					columns: [{ id: "a", label: "A" }],
					rows: [
						{ id: "r1", cells: { a: "x" }, action: "open" },
						{ id: "r2", cells: { a: "y" } },
					],
				},
			],
		});
		expect(host.handleEvent({ viewKey: "deps", nodeId: "t", kind: "tap", value: "r1" })).toBe(true);
		expect(host.handleEvent({ viewKey: "deps", nodeId: "t", kind: "tap", value: "r2" })).toBe(false);
		expect(host.handleEvent({ viewKey: "deps", nodeId: "t", kind: "tap", value: "r9" })).toBe(false);
		expect(events).toEqual([{ type: "view_event", key: "deps", id: "t", action: "open", kind: "tap", value: "r1" }]);
	});
});
