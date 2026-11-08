/**
 * A task board with a native UI in the Pi-Bolt app (the mobile UI kit, docs/mobile-ui.md) and the same board as text
 * in the terminal.
 *
 * - App: a panel above the composer (progress, the open tasks, Add task), a page from the session's menu (open and
 *   done tasks, an add form), and a sheet to add a task. Tapping a task completes or reopens it.
 * - Terminal: the panel is a widget; `/board`, `/board add <text>`, `/board done <n>` do what the buttons do, and
 *   Add task runs as an input dialog.
 * - The model: a `board` tool (list, add, toggle), so "put X on my board" works too.
 *
 * Tasks are stored in the session (`pi.appendEntry`), so the board comes back when the session reopens.
 *
 * Install: `pi install ./examples/extensions/mobile-ui-board`, or try it with `pi -e ./examples/extensions/mobile-ui-board`.
 */

import { StringEnum } from "@earendil-works/pi-ai";
import type { ExtensionAPI, ExtensionContext, MobileView, MobileViewNode } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

interface Task {
	id: number;
	text: string;
	done: boolean;
}

const PANEL = "board:panel";
const PAGE = "board:page";
const STATE = "board-state";
/** Open tasks the panel lists; the page lists all. */
const PANEL_TASKS = 4;

export default function (pi: ExtensionAPI) {
	let tasks: Task[] = [];
	let nextId = 1;

	function taskItem(task: Task): MobileViewNode {
		return {
			type: "item",
			id: `task-${task.id}`,
			title: task.text,
			subtitle: `#${task.id}`,
			icon: task.done ? "check" : undefined,
			chip: task.done ? { label: "Done", tone: "success" } : undefined,
			action: "toggle",
		};
	}

	function panelView(): MobileView {
		const open = tasks.filter((task) => !task.done);
		const done = tasks.length - open.length;
		return {
			v: 1,
			title: "Board",
			subtitle: `${open.length} open · ${done} done`,
			icon: "flag",
			body: [
				...(tasks.length > 0
					? [
							{
								type: "progress",
								id: "progress",
								value: done / tasks.length,
								label: `${done} of ${tasks.length} done`,
							} as const,
						]
					: []),
				{
					type: "list",
					id: "open",
					empty: "Nothing open. Add a task.",
					children: open.slice(0, PANEL_TASKS).map(taskItem) as Extract<MobileViewNode, { type: "item" }>[],
				},
				...(open.length > PANEL_TASKS
					? [{ type: "text", text: `${open.length - PANEL_TASKS} more on the board page`, style: "meta" } as const]
					: []),
			],
			actions: [{ type: "button", id: "add", label: "Add task", style: "primary", icon: "add" }],
		};
	}

	function pageView(): MobileView {
		const open = tasks.filter((task) => !task.done);
		const done = tasks.filter((task) => task.done);
		return {
			v: 1,
			title: "Board",
			subtitle: `${tasks.length} tasks`,
			icon: "flag",
			body: [
				{
					type: "grid",
					children: [
						{ type: "tile", label: "Open", value: String(open.length), icon: "flag" },
						{
							type: "tile",
							label: "Done",
							value: String(done.length),
							icon: "check",
							tone: done.length > 0 ? "success" : "neutral",
						},
					],
				},
				{
					type: "section",
					title: "Open",
					children: [
						{
							type: "list",
							id: "page-open",
							empty: "Nothing open",
							children: open.map(taskItem) as Extract<MobileViewNode, { type: "item" }>[],
						},
					],
				},
				{
					type: "section",
					title: "Done",
					children: [
						{
							type: "list",
							id: "page-done",
							empty: "Nothing done yet",
							children: done.map(taskItem) as Extract<MobileViewNode, { type: "item" }>[],
						},
					],
				},
				{
					type: "form",
					id: "page-add",
					children: [
						{
							type: "input",
							id: "text",
							label: "New task",
							placeholder: "What needs doing?",
							required: true,
							maxLength: 200,
						},
					],
					submit: { label: "Add", action: "add-task" },
				},
			],
			actions: [
				{
					type: "button",
					id: "clear-done",
					label: "Clear done",
					style: "ghost",
					disabled: done.length === 0,
					confirm: { title: "Clear the done tasks?" },
				},
			],
		};
	}

	/** Shows the board in every slot it uses, and stores it in the session. */
	function show(ctx: ExtensionContext, persist = true): void {
		if (persist) pi.appendEntry(STATE, { tasks, nextId });
		if (!ctx.ui.setView) {
			// Pi before the mobile UI kit: text only.
			ctx.ui.setWidget(PANEL, [`Board: ${tasks.filter((t) => !t.done).length} open`]);
			return;
		}
		ctx.ui.setView(PANEL, panelView());
		ctx.ui.setView(PAGE, pageView(), { slot: "page" });
	}

	function add(text: string): Task {
		const task = { id: nextId++, text: text.trim().slice(0, 200), done: false };
		tasks.push(task);
		return task;
	}

	function toggle(id: number): Task | undefined {
		const task = tasks.find((t) => t.id === id);
		if (task) task.done = !task.done;
		return task;
	}

	async function addFromSheet(ctx: ExtensionContext): Promise<void> {
		const result = await ctx.ui.showView({
			v: 1,
			title: "Add a task",
			body: [
				{
					type: "form",
					id: "new",
					children: [
						{
							type: "input",
							id: "text",
							label: "Task",
							placeholder: "What needs doing?",
							required: true,
							maxLength: 200,
						},
					],
					submit: { label: "Add", action: "add-task" },
				},
			],
		});
		const text = result?.values.text;
		if (typeof text === "string" && text.trim()) {
			add(text);
			show(ctx);
		}
	}

	pi.on("session_start", (_event, ctx) => {
		for (const entry of ctx.sessionManager.getEntries()) {
			if (entry.type === "custom" && entry.customType === STATE) {
				const data = entry.data as { tasks: Task[]; nextId: number };
				tasks = data.tasks;
				nextId = data.nextId;
			}
		}
		if (tasks.length > 0) show(ctx, false);
	});

	pi.on("view_event", async (event, ctx) => {
		if (event.key !== PANEL && event.key !== PAGE) return;
		if (event.action === "toggle") toggle(Number(event.id.replace("task-", "")));
		else if (event.action === "add") return addFromSheet(ctx);
		else if (event.action === "add-task" && typeof event.values?.text === "string") add(event.values.text);
		else if (event.action === "clear-done") tasks = tasks.filter((task) => !task.done);
		else return;
		show(ctx);
	});

	pi.registerCommand("board", {
		description: "Show the task board; /board add <text>, /board done <n>",
		handler: async (args, ctx) => {
			const [verb, ...rest] = (args ?? "").trim().split(/\s+/);
			if (verb === "add" && rest.length > 0) add(rest.join(" "));
			else if (verb === "add") return addFromSheet(ctx);
			else if (verb === "done" && toggle(Number(rest[0])) === undefined)
				ctx.ui.notify(`No task #${rest[0]}`, "warning");
			show(ctx);
		},
	});

	pi.registerTool({
		name: "board",
		label: "Board",
		description: "The user's task board. Actions: list, add (text), toggle (id: done or open again).",
		parameters: Type.Object({
			action: StringEnum(["list", "add", "toggle"] as const),
			text: Type.Optional(Type.String({ description: "Task text, for add" })),
			id: Type.Optional(Type.Number({ description: "Task number, for toggle" })),
		}),
		async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
			let text: string;
			if (params.action === "add" && params.text) text = `Added #${add(params.text).id}`;
			else if (params.action === "toggle" && params.id !== undefined) {
				const task = toggle(params.id);
				text = task ? `#${task.id} is ${task.done ? "done" : "open"}` : `No task #${params.id}`;
			} else
				text = tasks.map((t) => `[${t.done ? "x" : " "}] #${t.id} ${t.text}`).join("\n") || "The board is empty";
			show(ctx);
			return { content: [{ type: "text", text }], details: { tasks } };
		},
	});
}
