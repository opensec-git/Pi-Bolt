/**
 * A task board with a native UI in the Pi-Bolt app (the mobile UI kit, docs/mobile-ui.md) and the same board as text
 * in the terminal.
 *
 * - App, in a session: a panel above the composer (progress, the open tasks, Add task) and a sheet to add a task.
 *   Tapping a task completes or reopens it.
 * - App, anywhere: the Board page (`pi.registerPage`: a chart of tasks done per day, a sortable table of every task,
 *   an add form), from the session's menu or from Settings -> Packages without a session; and a Home tile
 *   (`pi.registerTile`: open and done counts with a sparkline) that opens the page.
 * - Terminal: the panel is a widget; `/board`, `/board add <text>`, `/board done <n>` do what the buttons do, and
 *   Add task runs as an input dialog.
 * - The model: a `board` tool (list, add, toggle), so "put X on my board" works too.
 *
 * The board is the user's, not a session's: it is kept in `mobile-ui-board.json` in Pi's agent folder, so the page
 * and the tile show it without a session.
 *
 * Install: `pi install ./examples/extensions/mobile-ui-board`, or try it with `pi -e ./examples/extensions/mobile-ui-board`.
 */

import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { StringEnum } from "@earendil-works/pi-ai";
import {
	type ExtensionAPI,
	type ExtensionContext,
	getAgentDir,
	type MobileView,
	type MobileViewNode,
} from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

interface Task {
	id: number;
	text: string;
	done: boolean;
	/** ISO 8601 times. */
	added: string;
	doneAt?: string;
}

interface Board {
	tasks: Task[];
	nextId: number;
}

type Item = Extract<MobileViewNode, { type: "item" }>;

const PANEL = "board:panel";
const PAGE = "board:page";
const TILE = "board:tile";
/** Open tasks the panel lists; the page lists all. */
const PANEL_TASKS = 4;
/** Tasks the page's table lists (the newest); the kit's tables hold at most 500 rows. */
const TABLE_TASKS = 200;
/** Days the chart and the sparkline cover. */
const DAYS = 7;
/** How long a change waits for another Pi's change of the board, and when a lock is taken as left behind. */
const LOCK_WAIT_MS = 5000;
const LOCK_STALE_MS = 10_000;

/** The board extension; `file` is where the board is kept (default: `mobile-ui-board.json` in the agent folder). */
export function boardExtension(options: { file?: string } = {}) {
	return (pi: ExtensionAPI) => {
		const file = options.file ?? join(getAgentDir(), "mobile-ui-board.json");
		let board: Board = { tasks: [], nextId: 1 };

		/** Reads the board again: another Pi (a session, the app's page engine) may have changed it. */
		function load(): void {
			try {
				if (existsSync(file)) board = JSON.parse(readFileSync(file, "utf8")) as Board;
			} catch {
				// A damaged file: keep what is in memory; the next save replaces it.
			}
		}

		function save(): void {
			// A temp file per process, so two Pis never rename each other's half-written copy.
			const temp = `${file}.${process.pid}.tmp`;
			writeFileSync(temp, JSON.stringify(board));
			renameSync(temp, file);
		}

		/**
		 * Reads the board, applies `mutate` and saves it, holding a lock directory next to the file: a session and the app's
		 * page engine never overwrite each other's change. A lock left by a process that died is taken over after 10 s.
		 */
		function change<T>(mutate: () => T): T {
			mkdirSync(dirname(file), { recursive: true });
			const lock = `${file}.lock`;
			const started = Date.now();
			for (;;) {
				try {
					mkdirSync(lock);
					break;
				} catch (error) {
					if ((error as { code?: string }).code !== "EEXIST") throw error;
					try {
						if (Date.now() - statSync(lock).mtimeMs > LOCK_STALE_MS)
							rmSync(lock, { recursive: true, force: true });
					} catch {
						// Released meanwhile: try again.
					}
					if (Date.now() - started > LOCK_WAIT_MS) throw new Error("The board is busy; try again");
					Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 20);
				}
			}
			try {
				load();
				const result = mutate();
				save();
				return result;
			} finally {
				rmSync(lock, { recursive: true, force: true });
			}
		}

		const open = () => board.tasks.filter((task) => !task.done);
		const done = () => board.tasks.filter((task) => task.done);

		/** Tasks done on each of the last 7 days, oldest first, with the days' short names. */
		function donePerDay(): { labels: string[]; values: number[] } {
			const labels: string[] = [];
			const values: number[] = [];
			const today = new Date();
			for (let back = DAYS - 1; back >= 0; back--) {
				const day = new Date(today.getFullYear(), today.getMonth(), today.getDate() - back);
				const key = day.toDateString();
				labels.push(day.toLocaleDateString("en", { weekday: "short" }));
				values.push(done().filter((task) => task.doneAt && new Date(task.doneAt).toDateString() === key).length);
			}
			return { labels, values };
		}

		function taskItem(task: Task): Item {
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
			const total = board.tasks.length;
			const openTasks = open();
			const doneCount = total - openTasks.length;
			return {
				v: 1,
				title: "Board",
				subtitle: `${openTasks.length} open · ${doneCount} done`,
				icon: "flag",
				body: [
					...(total > 0
						? [
								{
									type: "progress",
									id: "progress",
									value: doneCount / total,
									label: `${doneCount} of ${total} done`,
								} as const,
							]
						: []),
					{
						type: "list",
						id: "open",
						empty: "Nothing open. Add a task.",
						children: openTasks.slice(0, PANEL_TASKS).map(taskItem),
					},
					...(openTasks.length > PANEL_TASKS
						? [
								{
									type: "text",
									text: `${openTasks.length - PANEL_TASKS} more on the board page`,
									style: "meta",
								} as const,
							]
						: []),
				],
				actions: [{ type: "button", id: "add", label: "Add task", style: "primary", icon: "add" }],
			};
		}

		function pageView(): MobileView {
			const openTasks = open();
			const doneTasks = done();
			const perDay = donePerDay();
			return {
				v: 1,
				title: "Board",
				subtitle: `${board.tasks.length} tasks`,
				icon: "flag",
				body: [
					{
						type: "grid",
						children: [
							{ type: "tile", label: "Open", value: String(openTasks.length), icon: "flag" },
							{
								type: "tile",
								label: "Done",
								value: String(doneTasks.length),
								icon: "check",
								tone: doneTasks.length > 0 ? "success" : "neutral",
							},
						],
					},
					{
						type: "chart",
						id: "per-day",
						kind: "bar",
						title: "Done per day",
						labels: perDay.labels,
						series: [{ name: "Done", values: perDay.values }],
					},
					{
						type: "section",
						title: "Tasks",
						children: [
							{
								type: "table",
								id: "tasks",
								filter: board.tasks.length > 8,
								empty: "No tasks yet",
								sort: { column: "id", desc: true },
								columns: [
									{ id: "id", label: "#", kind: "number", sortable: true },
									{ id: "text", label: "Task", sortable: true },
									{ id: "state", label: "State", sortable: true },
									{ id: "added", label: "Added", kind: "date", sortable: true },
								],
								// The newest tasks only: a table holds at most 500 rows, a board any number.
								rows: board.tasks.slice(-TABLE_TASKS).map((task) => ({
									id: `task-${task.id}`,
									cells: {
										id: task.id,
										text: task.text,
										state: task.done ? "Done" : "Open",
										added: task.added,
									},
									action: "toggle",
								})),
							},
							...(board.tasks.length > TABLE_TASKS
								? [
										{
											type: "text",
											text: `The newest ${TABLE_TASKS} of ${board.tasks.length} tasks; /board lists all`,
											style: "meta",
										} as const,
									]
								: []),
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
						disabled: doneTasks.length === 0,
						confirm: { title: "Clear the done tasks?" },
					},
				],
			};
		}

		function tileView(): MobileView {
			return {
				v: 1,
				title: "Board",
				icon: "flag",
				body: [
					{ type: "text", text: `${open().length} open · ${done().length} done` },
					{
						type: "chart",
						id: "tile-per-day",
						kind: "sparkline",
						summary: `Tasks done in the last ${DAYS} days`,
						series: [{ name: "Done", values: donePerDay().values }],
					},
				],
			};
		}

		/** Redraws every view of the board that is shown. */
		function show(ctx: ExtensionContext): void {
			if (!ctx.ui.setView) {
				// Pi before the mobile UI kit: text only.
				ctx.ui.setWidget(PANEL, [`Board: ${open().length} open`]);
				return;
			}
			ctx.ui.setView(PANEL, panelView());
			// The page and the tile are shown only while a client has them open.
			if (ctx.ui.getView(PAGE)) ctx.ui.setView(PAGE, pageView(), { slot: "page" });
			if (ctx.ui.getView(TILE)) ctx.ui.setView(TILE, tileView(), { slot: "tile" });
		}

		function add(text: string): Task {
			return change(() => {
				const task = {
					id: board.nextId++,
					text: text.trim().slice(0, 200),
					done: false,
					added: new Date().toISOString(),
				};
				board.tasks.push(task);
				return task;
			});
		}

		function toggle(id: number): Task | undefined {
			return change(() => {
				const task = board.tasks.find((t) => t.id === id);
				if (task) {
					task.done = !task.done;
					task.doneAt = task.done ? new Date().toISOString() : undefined;
				}
				return task;
			});
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

		pi.registerPage(PAGE, {
			title: "Board",
			icon: "flag",
			description: "Your tasks, done per day, and a form to add one",
			open: () => {
				load();
				return pageView();
			},
		});

		pi.registerTile(TILE, {
			title: "Board",
			icon: "flag",
			description: "Open and done tasks, and the last week",
			page: PAGE,
			open: () => {
				load();
				return tileView();
			},
		});

		pi.on("session_start", (_event, ctx) => {
			load();
			if (board.tasks.length > 0 && ctx.ui.setView) ctx.ui.setView(PANEL, panelView());
		});

		pi.on("view_event", async (event, ctx) => {
			if (event.key !== PANEL && event.key !== PAGE) return;
			if (event.action === "toggle") {
				// A list row's id is `task-<n>`; a table row's id comes as the event's value.
				const row = event.id === "tasks" ? String(event.value) : event.id;
				toggle(Number(row.replace("task-", "")));
			} else if (event.action === "add") return addFromSheet(ctx);
			else if (event.action === "add-task" && typeof event.values?.text === "string") add(event.values.text);
			else if (event.action === "clear-done") {
				change(() => {
					board.tasks = board.tasks.filter((task) => !task.done);
				});
			} else return;
			show(ctx);
		});

		pi.registerCommand("board", {
			description: "Show the task board; /board add <text>, /board done <n>",
			handler: async (args, ctx) => {
				const [verb, ...rest] = (args ?? "").trim().split(/\s+/);
				if (verb === "add" && rest.length > 0) add(rest.join(" "));
				else if (verb === "add") return addFromSheet(ctx);
				else if (verb === "done") {
					if (toggle(Number(rest[0])) === undefined) ctx.ui.notify(`No task #${rest[0]}`, "warning");
				} else load();
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
				} else {
					load();
					text =
						board.tasks.map((t) => `[${t.done ? "x" : " "}] #${t.id} ${t.text}`).join("\n") ||
						"The board is empty";
				}
				show(ctx);
				return { content: [{ type: "text", text }], details: { tasks: board.tasks } };
			},
		});
	};
}

export default boardExtension();
