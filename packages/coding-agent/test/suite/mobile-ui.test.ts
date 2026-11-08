import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fauxAssistantMessage, fauxToolCall } from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { afterEach, describe, expect, it } from "vitest";
import boardExtension from "../../examples/extensions/mobile-ui-board/index.ts";
import type { ExtensionAPI, ExtensionContext } from "../../src/core/extensions/types.ts";
import type { ViewChannel, ViewRequest } from "../../src/core/mobile-ui/host.ts";
import type { MobileView, MobileViewResult, ViewEvent } from "../../src/core/mobile-ui/types.ts";
import { validateView } from "../../src/core/mobile-ui/validate.ts";
import { generateKeyPair } from "../../src/remote-control/noise.ts";
import { RemoteControlServer } from "../../src/remote-control/server.ts";
import { RemoteControlStore } from "../../src/remote-control/store.ts";
import { createHarness, createTestUiContext, type Harness } from "./harness.ts";
import { connectTestClient, type TestClient } from "./remote-control-client.ts";

const DEPLOY: MobileView = {
	v: 1,
	title: "Deploy",
	subtitle: "production",
	body: [
		{ type: "chip", id: "state", label: "Running", tone: "running" },
		{ type: "progress", id: "bar", value: 0, label: "Uploading" },
	],
	actions: [{ type: "button", id: "cancel", label: "Cancel" }],
};

const SETTINGS: MobileView = {
	v: 1,
	title: "Deploy settings",
	body: [
		{
			type: "form",
			id: "f",
			children: [
				{ type: "input", id: "branch", label: "Branch", value: "main", required: true },
				{ type: "toggle", id: "migrate", label: "Run migrations", value: false },
			],
			submit: { label: "Deploy", action: "deploy" },
		},
	],
	actions: [{ type: "button", id: "cancel", label: "Cancel" }],
};

/** A deploy extension: `/deploy` shows a panel and fills its progress, `/settings` opens a sheet. */
function deployExtension(record: { events: ViewEvent[]; results: Array<MobileViewResult | undefined> }) {
	return (pi: ExtensionAPI) => {
		pi.registerCommand("deploy", {
			description: "Deploy",
			handler: async (_args, ctx) => {
				ctx.ui.setView("deploy", DEPLOY);
				ctx.ui.updateView("deploy", [{ op: "set", id: "bar", props: { value: 0.5 } }]);
			},
		});
		pi.registerCommand("settings", {
			description: "Settings",
			handler: async (_args, ctx) => {
				record.results.push(await ctx.ui.showView(SETTINGS, { key: "settings" }));
			},
		});
		pi.on("view_event", (event: ViewEvent, ctx: ExtensionContext) => {
			record.events.push(event);
			if (event.id === "cancel") {
				ctx.ui.updateView("deploy", [{ op: "set", id: "state", props: { label: "Cancelled", tone: "danger" } }]);
			}
		});
	};
}

function fakeChannel(native: boolean): ViewChannel & { sent: ViewRequest[] } {
	const sent: ViewRequest[] = [];
	return {
		sent,
		hasNative: () => native,
		keepText: false,
		send: (request) => void sent.push(structuredClone(request)),
		sheet: (_request, signal) =>
			new Promise((resolve) => signal.addEventListener("abort", () => resolve(undefined), { once: true })),
	};
}

const until = async (check: () => boolean) => {
	for (let i = 0; i < 200 && !check(); i++) await new Promise((resolve) => setTimeout(resolve, 5));
	expect(check()).toBe(true);
};

describe("mobile UI kit in a session", () => {
	const cleanups: Array<() => unknown> = [];

	afterEach(async () => {
		while (cleanups.length > 0) await cleanups.pop()?.();
	});

	async function setup() {
		const record = { events: [] as ViewEvent[], results: [] as Array<MobileViewResult | undefined> };
		const harness: Harness = await createHarness({ extensionFactories: [deployExtension(record)] });
		cleanups.push(() => harness.cleanup());
		return { harness, record };
	}

	it("sends an extension's view to a client that renders views, and its tap back to the extension", async () => {
		const { harness, record } = await setup();
		const channel = fakeChannel(true);
		const widgets = new Map<string, unknown>();
		await harness.session.bindExtensions({
			uiContext: createTestUiContext({ setWidget: (key: string, lines: unknown) => void widgets.set(key, lines) }),
			mode: "rpc",
			viewChannel: channel,
		});
		await harness.session.prompt("/deploy");
		// The update right after the view is merged into one full send at the end of the 100 ms window.
		expect(channel.sent).toMatchObject([
			{ method: "setView", viewKey: "deploy", slot: "panel", rev: 1, view: DEPLOY },
		]);
		await until(() => channel.sent.length === 2);
		expect(channel.sent[1]).toMatchObject({ method: "setView", rev: 2, view: { body: [{}, { value: 0.5 }] } });
		expect(widgets.size).toBe(0);

		expect(
			harness.session.extensionRunner.handleViewEvent({ viewKey: "deploy", nodeId: "cancel", kind: "tap" }),
		).toBe(true);
		expect(harness.session.extensionRunner.handleViewEvent({ viewKey: "deploy", nodeId: "state", kind: "tap" })).toBe(
			false,
		);
		await until(() => record.events.length === 1);
		expect(record.events[0]).toEqual({
			type: "view_event",
			key: "deploy",
			id: "cancel",
			action: "cancel",
			kind: "tap",
		});
		await until(() => channel.sent.length === 3);
		expect(channel.sent[2]).toMatchObject({ viewKey: "deploy", rev: 3 });
		expect(harness.session.extensionRunner.getUIContext().getView("deploy")?.body[0]).toMatchObject({
			label: "Cancelled",
		});
	});

	it("shows the text form as a widget and runs a sheet as dialogs for a client without the kit", async () => {
		const { harness, record } = await setup();
		const widgets = new Map<string, string[] | undefined>();
		const answers = ["Deploy", "release", "On"];
		await harness.session.bindExtensions({
			uiContext: createTestUiContext({
				setWidget: (key: string, lines: unknown) => void widgets.set(key, lines as string[] | undefined),
				select: async () => answers.shift(),
				input: async () => answers.shift(),
			}),
			mode: "rpc",
			viewChannel: fakeChannel(false),
		});
		await harness.session.prompt("/deploy");
		await until(() => widgets.get("view:deploy")?.[2]?.includes("50%") === true);
		expect(widgets.get("view:deploy")).toEqual([
			"Deploy · production",
			"● Running",
			"[#####-----] 50%  Uploading",
			"[ Cancel ]",
		]);
		await harness.session.prompt("/settings");
		expect(record.results).toEqual([{ id: "f", action: "deploy", values: { branch: "release", migrate: true } }]);
	});

	it("removes the session's views when it reloads", async () => {
		const { harness } = await setup();
		const channel = fakeChannel(true);
		await harness.session.bindExtensions({ uiContext: createTestUiContext(), mode: "rpc", viewChannel: channel });
		await harness.session.prompt("/deploy");
		await harness.session.reload();
		expect(channel.sent.at(-1)).toMatchObject({ method: "setView", viewKey: "deploy", slot: "panel" });
		expect(channel.sent.at(-1)).not.toHaveProperty("view");
		expect(harness.session.extensionRunner.viewSnapshot()).toEqual([]);
	});

	it("pins an inline view to the tool call that set it, or to the end of the latest turn", async () => {
		const card: MobileView = { v: 1, body: [{ type: "text", text: "Indexed" }] };
		const harness = await createHarness({
			extensionFactories: [
				(pi: ExtensionAPI) => {
					pi.registerTool({
						name: "probe",
						label: "Probe",
						description: "Shows a card",
						parameters: Type.Object({}),
						async execute(_id, _params, _signal, _onUpdate, ctx) {
							ctx.ui.setView("probe", card, { slot: "inline" });
							return { content: [{ type: "text", text: "ok" }], details: {} };
						},
					});
					pi.registerCommand("later", {
						description: "Shows a card after the turn",
						handler: async (_args, ctx) => ctx.ui.setView("later", card, { slot: "inline" }),
					});
				},
			],
		});
		cleanups.push(() => harness.cleanup());
		await harness.session.bindExtensions({
			uiContext: createTestUiContext(),
			mode: "rpc",
			viewChannel: fakeChannel(true),
		});
		harness.setResponses([
			fauxAssistantMessage([fauxToolCall("probe", {}, { id: "call-probe" })], { stopReason: "toolUse" }),
			fauxAssistantMessage("Done."),
		]);
		await harness.session.prompt("index it");
		await harness.session.prompt("/later");
		const user = harness.session.messages.find((m) => m.role === "user") as { timestamp: number };
		const snapshot = harness.session.extensionRunner.viewSnapshot();
		expect(snapshot.find((v) => v.viewKey === "probe")).toMatchObject({ slot: "inline", toolCallId: "call-probe" });
		expect(snapshot.find((v) => v.viewKey === "later")).toMatchObject({ slot: "inline", turn: user.timestamp });
		expect(snapshot.find((v) => v.viewKey === "later")).not.toHaveProperty("toolCallId");
	});

	it("runs the example board package: panel and page, a tap completes a task, the sheet adds one", async () => {
		const harness = await createHarness({ extensionFactories: [boardExtension] });
		cleanups.push(() => harness.cleanup());
		const channel = fakeChannel(true);
		let sheetAnswer: unknown;
		channel.sheet = async (request) => {
			expect(validateView(request.view)).toEqual([]);
			return sheetAnswer as undefined;
		};
		await harness.session.bindExtensions({ uiContext: createTestUiContext(), mode: "rpc", viewChannel: channel });
		await harness.session.prompt("/board add Write the spec");
		await harness.session.prompt("/board add Ship it");
		const runner = harness.session.extensionRunner;
		const ui = runner.getUIContext();
		expect(runner.viewSnapshot().map((v) => [v.viewKey, v.slot])).toEqual([
			["board:panel", "panel"],
			["board:page", "page"],
		]);
		for (const view of runner.viewSnapshot()) expect(validateView(view.view)).toEqual([]);
		expect(ui.getView("board:panel")?.subtitle).toBe("2 open · 0 done");

		runner.handleViewEvent({ viewKey: "board:panel", nodeId: "task-1", kind: "tap" });
		await until(() => ui.getView("board:panel")?.subtitle === "1 open · 1 done");

		sheetAnswer = { nodeId: "new", values: { text: "Celebrate" } };
		runner.handleViewEvent({ viewKey: "board:panel", nodeId: "add", kind: "tap" });
		await until(() => ui.getView("board:panel")?.subtitle === "2 open · 1 done");

		runner.handleViewEvent({
			viewKey: "board:page",
			nodeId: "page-add",
			kind: "submit",
			values: { text: "From the page" },
		});
		await until(() => ui.getView("board:page")?.subtitle === "4 tasks");
	});

	describe("over remote control", () => {
		function tempDir(): string {
			const dir = mkdtempSync(join(tmpdir(), "pi-remote-views-"));
			cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
			return dir;
		}

		async function remoteSetup() {
			const { harness, record } = await setup();
			const server = new RemoteControlServer({
				store: new RemoteControlStore(tempDir()),
				getSession: () => harness.session,
				hostName: "test-host",
				version: "9.9.9",
				bindAddress: "127.0.0.1",
			});
			const port = await server.start();
			cleanups.push(() => server.stop());
			// As the background service binds a session: views to the phones that render them, sheets as their dialogs.
			await harness.session.bindExtensions({
				uiContext: createTestUiContext(),
				mode: "rpc",
				viewChannel: {
					hasNative: () => server.hasViewClients(),
					keepText: false,
					send: (request) => server.sendView(request),
					sheet: (request, signal) => server.requestViewSheet(request, signal),
					sheetChanged: (viewKey, view, rev) => server.updateViewSheet(viewKey, view, rev),
				},
			});
			const uri = () => server.pairingUri([`ws://127.0.0.1:${port}/rc`]);
			return { harness, record, server, uri };
		}

		async function phone(uri: string, views: boolean): Promise<TestClient> {
			const client = await connectTestClient(uri, generateKeyPair());
			cleanups.push(() => client.close());
			await client.waitFor((message) => message.type === "remote_hello");
			if (views) {
				const response = await client.request({ type: "set_client_features", features: ["views", "later"] });
				expect(response).toMatchObject({ success: true, data: { features: ["views"] } });
			}
			return client;
		}

		it("sends views to phones that declared them, current views in remote_hello, and taps back", async () => {
			const { harness, record, uri } = await remoteSetup();
			const withViews = await phone(uri(), true);
			const without = await phone(uri(), false);
			await harness.session.prompt("/deploy");
			await withViews.waitFor((m) => m.type === "extension_ui_request" && m.method === "setView" && m.rev === 2);
			expect(without.received.some((m) => m.method === "setView")).toBe(false);

			const late = await connectTestClient(uri(), generateKeyPair());
			cleanups.push(() => late.close());
			const hello = await late.waitFor((message) => message.type === "remote_hello");
			expect(hello.features).toContain("views");
			expect(hello.views).toMatchObject([{ viewKey: "deploy", slot: "panel", rev: 2, view: { title: "Deploy" } }]);

			withViews.send({ type: "extension_ui_event", viewKey: "deploy", nodeId: "cancel", kind: "tap" });
			await until(() => record.events.length === 1);
			await withViews.waitFor((m) => m.viewKey === "deploy" && JSON.stringify(m).includes("Cancelled"));
		});

		it("sends a phone that turns views on late every view as it is now", async () => {
			const { harness, uri } = await remoteSetup();
			const client = await phone(uri(), false);
			await harness.session.prompt("/deploy");
			await until(() => harness.session.extensionRunner.viewSnapshot()[0]?.rev === 2);
			expect(client.received.some((m) => m.method === "setView")).toBe(false);
			await client.request({ type: "set_client_features", features: ["views"] });
			const view = await client.waitFor((m) => m.method === "setView" && m.viewKey === "deploy");
			expect(view).toMatchObject({ rev: 2, view: { body: [{}, { value: 0.5 }] } });
		});

		it("gives a phone that connects to an open, updated sheet its current state", async () => {
			const { harness, uri } = await remoteSetup();
			const first = await phone(uri(), true);
			const done = harness.session.prompt("/settings");
			const request = await first.waitFor((m) => m.method === "showView");
			harness.session.extensionRunner
				.getUIContext()
				.updateView("settings", [{ op: "set", id: "branch", props: { value: "next" } }]);
			const late = await connectTestClient(uri(), generateKeyPair());
			cleanups.push(() => late.close());
			const hello = await late.waitFor((m) => m.type === "remote_hello");
			const sheet = (hello.dialogs as Array<Record<string, unknown>>).find((d) => d.method === "showView");
			expect(sheet).toMatchObject({ rev: 2, view: { body: [{ children: [{ value: "next" }, {}] }] } });
			first.send({ type: "extension_ui_response", id: request.id, cancelled: true });
			await done;
		});

		it("opens a sheet on a phone that renders views and returns its answer", async () => {
			const { harness, record, uri } = await remoteSetup();
			const client = await phone(uri(), true);
			const done = harness.session.prompt("/settings");
			const request = await client.waitFor((m) => m.type === "extension_ui_request" && m.method === "showView");
			expect(request).toMatchObject({ viewKey: "settings", view: { title: "Deploy settings" } });
			client.send({
				type: "extension_ui_response",
				id: request.id,
				nodeId: "f",
				values: { branch: "hotfix", migrate: true },
			});
			await done;
			expect(record.results).toEqual([{ id: "f", action: "deploy", values: { branch: "hotfix", migrate: true } }]);
		});
	});
});
