/**
 * The mobile UI kit's session side (docs/mobile-ui.md): keeps every view's current state, sends changes to the clients
 * that render views (throttled), renders the text form for the others, runs sheets, and checks client events before
 * they reach extensions. One per extension runner; the mode supplies the clients through a [ViewChannel].
 */

import { runViewDialogs, type ViewDialogs } from "./dialogs.ts";
import { applyViewOps } from "./ops.ts";
import { type MobileViewTextStyle, viewToLines } from "./text.ts";
import {
	MOBILE_VIEW_LIMITS as LIMITS,
	MOBILE_VIEW_ID_PATTERN,
	type MobileView,
	type MobileViewButton,
	type MobileViewField,
	type MobileViewForm,
	type MobileViewNode,
	type MobileViewOp,
	type MobileViewOptions,
	type MobileViewResult,
	type MobileViewSlot,
	type MobileViewValue,
	type ShowViewOptions,
	type ViewEvent,
	type ViewEventKind,
} from "./types.ts";
import { assertView, findShownNode, forEachNode, isFieldType, optionValues, validateTile } from "./validate.ts";

export interface ViewSetRequest {
	method: "setView";
	viewKey: string;
	slot: MobileViewSlot;
	/** Absent: the view was removed. */
	view?: MobileView;
	rev: number;
	/** Inline views: shown under this tool call's row. */
	toolCallId?: string;
	/** Inline views without a tool call: shown at the end of the turn of the user message with this `timestamp`. */
	turn?: number;
}

/** Where an inline view sits in the transcript, fixed when it is first set. */
export interface ViewAnchor {
	toolCallId?: string;
	turn?: number;
}

export interface ViewUpdateRequest {
	method: "updateView";
	viewKey: string;
	ops: MobileViewOp[];
	/** The rev after the ops. */
	rev: number;
}

export interface ViewShowRequest {
	method: "showView";
	viewKey: string;
	view: MobileView;
	/** Raised by each `updateView` of the open sheet. */
	rev: number;
	timeout?: number;
}

export type ViewRequest = ViewSetRequest | ViewUpdateRequest;

/** A client's answer to a sheet (an `extension_ui_response`), unchecked. */
export interface ViewSheetAnswer {
	nodeId?: unknown;
	values?: unknown;
	/** The user dismissed the sheet. */
	cancelled?: unknown;
}

/** A client's `extension_ui_event`, unchecked. */
export interface ViewClientEvent {
	viewKey?: unknown;
	nodeId?: unknown;
	kind?: unknown;
	value?: unknown;
	values?: unknown;
}

/** The current state of one view, as a client that connects gets it. */
export type ViewSnapshot = Omit<ViewSetRequest, "method"> & { view: MobileView };

/** Where views go besides the text form: the clients of the mode that render views. */
export interface ViewChannel {
	/** A client renders views now. */
	hasNative(): boolean;
	/** The text form stays while clients render views (a terminal with phones attached). */
	readonly keepText: boolean;
	/** To the clients that render views. */
	send(request: ViewRequest): void;
	/**
	 * Opens a sheet on the clients that render views (and on those that connect while it is open). Resolves with the
	 * first answer, or undefined when `signal` aborts first.
	 */
	sheet(request: ViewShowRequest, signal: AbortSignal): Promise<ViewSheetAnswer | undefined>;
	/** An open sheet changed: clients that connect from now on get it as it is now (remote control's `dialogs`). */
	sheetChanged?(viewKey: string, view: MobileView, rev: number): void;
	/** The text form of an inline view; default: a widget, as for panels. */
	inlineText?(key: string, lines: string[] | undefined): void;
	/** Styling of the text form; plain without. */
	textStyle?(): MobileViewTextStyle | undefined;
}

/** What the host needs of the mode's UI. */
export interface ViewHostUI extends ViewDialogs {
	setWidget(key: string, content: string[] | undefined): void;
}

interface ViewRecord {
	slot: MobileViewSlot;
	view: MobileView;
	rev: number;
	anchor?: ViewAnchor;
	/** Sends throttled: the last send, a timer for the merged one, and whether a change waits for it. */
	lastSent: number;
	timer?: ReturnType<typeof setTimeout>;
	pending: boolean;
	/** Events accepted in the current second. */
	eventWindow: number;
	events: number;
}

function assertTile(view: MobileView, key: string): void {
	const errors = validateTile(view);
	if (errors.length > 0) throw new Error(`Invalid tile ${JSON.stringify(key)}: ${errors.join("; ")}`);
}

/** The text widget key of a view. */
export function viewWidgetKey(key: string): string {
	return `view:${key}`;
}

function checkKey(key: unknown): asserts key is string {
	if (typeof key !== "string" || !MOBILE_VIEW_ID_PATTERN.test(key)) {
		throw new Error(`Invalid view key ${JSON.stringify(key)}: expected 1 to 64 of A-Z a-z 0-9 _ . : -`);
	}
}

/** The fields shown in `nodes`, by id: hidden nodes and everything inside them are left out, as the app leaves them. */
function fieldsOf(nodes: readonly MobileViewNode[]): Map<string, MobileViewField> {
	const fields = new Map<string, MobileViewField>();
	const walk = (list: readonly MobileViewNode[]) => {
		for (const node of list) {
			if (node.hidden) continue;
			if (node.id && isFieldType(node.type)) fields.set(node.id, node as MobileViewField);
			const children = (node as { children?: MobileViewNode[] }).children;
			if (Array.isArray(children)) walk(children);
		}
	};
	walk(nodes);
	return fields;
}

/** The ids of the shown fields of `nodes` outside forms. */
function standaloneFieldIds(nodes: readonly MobileViewNode[]): string[] {
	const ids: string[] = [];
	const walk = (list: readonly MobileViewNode[]) => {
		for (const node of list) {
			if (node.hidden || node.type === "form") continue;
			if (node.id && isFieldType(node.type)) ids.push(node.id);
			const children = (node as { children?: MobileViewNode[] }).children;
			if (Array.isArray(children)) walk(children);
		}
	};
	walk(nodes);
	return ids;
}

/** `value` checked for `field`, or undefined when it does not fit. */
function checkValue(field: MobileViewField, value: unknown): MobileViewValue | undefined {
	switch (field.type) {
		case "toggle":
			return typeof value === "boolean" ? value : undefined;
		case "select":
			return typeof value === "string" && optionValues(field.options).includes(value) ? value : undefined;
		case "slider":
			return typeof value === "number" && Number.isFinite(value) && value >= field.min && value <= field.max
				? value
				: undefined;
		case "input": {
			if (typeof value !== "string" || value.length > (field.maxLength ?? LIMITS.inputDefaultMaxLength))
				return undefined;
			if (field.kind === "number" && value.trim() !== "" && !Number.isFinite(Number(value))) return undefined;
			return value;
		}
	}
}

/** `values` checked against `fields`: every field gets the given or the current value. Undefined when one does not fit. */
function checkValues(
	fields: Map<string, MobileViewField>,
	values: unknown,
	/** The fields whose `required` counts (those that go with the answer); default all. */
	required: (id: string) => boolean = () => true,
): Record<string, MobileViewValue> | undefined {
	if (values !== undefined && (typeof values !== "object" || values === null || Array.isArray(values)))
		return undefined;
	const given = (values ?? {}) as Record<string, unknown>;
	const result: Record<string, MobileViewValue> = {};
	for (const name of Object.keys(given)) if (!fields.has(name)) return undefined;
	for (const [name, field] of fields) {
		const value =
			name in given
				? checkValue(field, given[name])
				: (field.value ?? (field.type === "select" ? optionValues(field.options)[0] : ""));
		if (value === undefined) return undefined;
		if (field.type === "input" && field.required && required(name) && String(value).trim() === "") return undefined;
		result[name] = value;
	}
	return result;
}

/** Writes committed `values` into the fields of the stored view (not passwords: the user's secret stays out of it). */
function storeValues(fields: Map<string, MobileViewField>, values: Record<string, MobileViewValue>): void {
	for (const [name, value] of Object.entries(values)) {
		const field = fields.get(name);
		if (!field || (field.type === "input" && field.kind === "password")) continue;
		(field as { value?: MobileViewValue }).value = value;
	}
}

export class ViewHost {
	private ui: ViewHostUI;
	private dialogs: ViewDialogs;
	private channel: ViewChannel | undefined;
	private readonly emit: (event: ViewEvent) => void;
	private readonly anchor: () => ViewAnchor | undefined;
	private readonly views = new Map<string, ViewRecord>();
	/** Open sheets by key: their views take updates and events while open. */
	private readonly sheets = new Map<string, ViewRecord>();
	private readonly sheetAborts = new Set<AbortController>();
	/** Keys of registered tiles that are open: the only keys the `tile` slot takes. */
	private readonly tileKeys = new Set<string>();
	private disposed = false;

	constructor(options: {
		ui: ViewHostUI;
		dialogs?: ViewDialogs;
		channel?: ViewChannel;
		emit: (event: ViewEvent) => void;
		/** Where an inline view set now belongs: the latest tool call of the session, or its latest turn. */
		anchor?: () => ViewAnchor | undefined;
	}) {
		this.ui = options.ui;
		this.dialogs = options.dialogs ?? options.ui;
		this.channel = options.channel;
		this.emit = options.emit;
		this.anchor = options.anchor ?? (() => undefined);
	}

	/** The mode bound new UI (a rebind of the same session). Views stay. */
	rebind(ui: ViewHostUI, dialogs: ViewDialogs, channel: ViewChannel | undefined): void {
		this.ui = ui;
		this.dialogs = dialogs;
		this.channel = channel;
	}

	private get native(): boolean {
		return this.channel?.hasNative() === true;
	}

	private get text(): boolean {
		return !this.native || this.channel?.keepText === true;
	}

	/** Lets the `tile` slot take `key` (a registered tile was opened), or stops it (closed). */
	allowTile(key: string, allowed: boolean): void {
		if (allowed) this.tileKeys.add(key);
		else this.tileKeys.delete(key);
	}

	set(key: string, view: MobileView | undefined, options: MobileViewOptions = {}): void {
		checkKey(key);
		// The session ended: nothing is shown any more (a late write from an async open, say).
		if (this.disposed) return;
		const existing = this.views.get(key);
		if (view === undefined) {
			if (existing) this.remove(key, existing);
			return;
		}
		assertView(view, JSON.stringify(key));
		const slot = options.slot ?? "panel";
		if (slot !== "panel" && slot !== "inline" && slot !== "page" && slot !== "tile")
			throw new Error(`Invalid view slot ${JSON.stringify(slot)}`);
		if (slot === "tile") {
			if (!this.tileKeys.has(key))
				throw new Error(`View ${JSON.stringify(key)}: the tile slot is only for an open registered tile`);
			assertTile(view, key);
		}
		if (
			options.toolCallId !== undefined &&
			(typeof options.toolCallId !== "string" || options.toolCallId.length > 200)
		) {
			throw new Error("Invalid toolCallId: expected a string of at most 200 characters");
		}
		if (!existing && this.views.size >= LIMITS.viewsPerSession) {
			throw new Error(`Too many views: at most ${LIMITS.viewsPerSession} per session`);
		}
		const copy = structuredClone(view);
		if (existing && existing.slot !== slot) {
			this.renderText(key, existing, false);
		}
		const record: ViewRecord = existing ?? {
			slot,
			view: copy,
			rev: 0,
			lastSent: 0,
			pending: false,
			eventWindow: 0,
			events: 0,
		};
		// An inline view keeps the place it got when it was first set there (or the tool call it names).
		if (slot !== "inline") record.anchor = undefined;
		else if (options.toolCallId !== undefined) record.anchor = { toolCallId: options.toolCallId };
		else if (!existing || existing.slot !== "inline" || !record.anchor) record.anchor = this.anchor();
		record.slot = slot;
		record.view = copy;
		record.rev++;
		this.views.set(key, record);
		this.changed(key, record);
	}

	update(key: string, ops: MobileViewOp[]): void {
		checkKey(key);
		const record = this.views.get(key) ?? this.sheets.get(key);
		if (!record) throw new Error(`No view ${JSON.stringify(key)}`);
		const next = applyViewOps(record.view, ops);
		assertView(next, JSON.stringify(key));
		if (record.slot === "tile") assertTile(next, key);
		record.view = next;
		record.rev++;
		// An open sheet's changes go to its clients as they come (its text form is the dialogs already open).
		if (this.sheets.get(key) === record) {
			if (this.native)
				this.channel?.send({ method: "updateView", viewKey: key, ops: structuredClone(ops), rev: record.rev });
			this.channel?.sheetChanged?.(key, structuredClone(record.view), record.rev);
			return;
		}
		this.changed(key, record, structuredClone(ops));
	}

	get(key: string): MobileView | undefined {
		const record = this.views.get(key) ?? this.sheets.get(key);
		return record ? structuredClone(record.view) : undefined;
	}

	/** Every view now, for a client that connects. */
	snapshot(): ViewSnapshot[] {
		return [...this.views].map(([viewKey, record]) => this.setRequest(viewKey, record));
	}

	private setRequest(viewKey: string, record: ViewRecord): ViewSetRequest & { view: MobileView } {
		return {
			method: "setView",
			viewKey,
			slot: record.slot,
			view: record.view,
			rev: record.rev,
			...(record.anchor?.toolCallId ? { toolCallId: record.anchor.toolCallId } : {}),
			...(record.anchor?.turn !== undefined && !record.anchor.toolCallId ? { turn: record.anchor.turn } : {}),
		};
	}

	/** Sends the view `key` again as it is now (a client opened a page that is open already). */
	resend(key: string): void {
		const record = this.views.get(key);
		if (!record || !this.native) return;
		this.channel?.send(this.setRequest(key, record));
		record.lastSent = Date.now();
	}

	/**
	 * A client now renders views (RPC's `set_client_features`): it gets every view, and the text form leaves unless the
	 * mode keeps it.
	 */
	resync(): void {
		for (const [key, record] of this.views) {
			if (!this.text) this.renderText(key, record, false);
			if (this.native) {
				this.channel?.send(this.setRequest(key, record));
				record.lastSent = Date.now();
			}
		}
	}

	/** Sends a change at once, or merges it into one full send at the end of the 100 ms window. */
	private changed(key: string, record: ViewRecord, ops?: MobileViewOp[]): void {
		const now = Date.now();
		const interval = record.slot === "tile" ? LIMITS.tileSendIntervalMs : LIMITS.sendIntervalMs;
		if (record.timer === undefined && now - record.lastSent >= interval) {
			record.lastSent = now;
			if (this.native) {
				this.channel?.send(
					ops ? { method: "updateView", viewKey: key, ops, rev: record.rev } : this.setRequest(key, record),
				);
			}
			if (this.text) this.renderText(key, record, true);
			return;
		}
		record.pending = true;
		if (record.timer !== undefined) return;
		record.timer = setTimeout(
			() => {
				record.timer = undefined;
				if (!record.pending || this.disposed) return;
				record.pending = false;
				record.lastSent = Date.now();
				if (this.native) this.channel?.send(this.setRequest(key, record));
				if (this.views.get(key) === record && this.text) this.renderText(key, record, true);
			},
			interval - (now - record.lastSent),
		);
		record.timer.unref?.();
	}

	private remove(key: string, record: ViewRecord): void {
		if (record.timer !== undefined) clearTimeout(record.timer);
		this.views.delete(key);
		this.renderText(key, record, false);
		if (this.native) this.channel?.send({ method: "setView", viewKey: key, slot: record.slot, rev: record.rev + 1 });
	}

	private renderText(key: string, record: ViewRecord, show: boolean): void {
		if (record.slot === "page" || record.slot === "tile") return;
		const lines = show ? viewToLines(record.view, this.channel?.textStyle?.()) : undefined;
		if (record.slot === "inline" && this.channel?.inlineText) this.channel.inlineText(key, lines);
		else this.ui.setWidget(viewWidgetKey(key), lines);
	}

	/** Opens `view` as a sheet; resolves with the submitted form or pressed action, or undefined. */
	async show(view: MobileView, options: ShowViewOptions = {}): Promise<MobileViewResult | undefined> {
		assertView(view, "sheet");
		const key = options.key ?? `sheet:${crypto.randomUUID().slice(0, 8)}`;
		checkKey(key);
		if (this.sheets.has(key) || this.views.has(key)) throw new Error(`View key ${JSON.stringify(key)} is in use`);
		if (options.signal?.aborted) return undefined;
		const record: ViewRecord = {
			slot: "panel",
			view: structuredClone(view),
			rev: 1,
			lastSent: 0,
			pending: false,
			eventWindow: 0,
			events: 0,
		};
		this.sheets.set(key, record);
		const abort = new AbortController();
		this.sheetAborts.add(abort);
		const onAbort = () => abort.abort();
		options.signal?.addEventListener("abort", onAbort, { once: true });
		const timer = options.timeout ? setTimeout(onAbort, options.timeout) : undefined;
		const never = new Promise<undefined>(() => {});
		const closed = new Promise<undefined>((resolve) =>
			abort.signal.addEventListener("abort", () => resolve(undefined), { once: true }),
		);
		try {
			const racers: Array<Promise<MobileViewResult | undefined>> = [closed];
			const channel = this.channel;
			const native = channel !== undefined && (channel.keepText || channel.hasNative());
			const text = !native || channel.keepText || !channel.hasNative();
			if (native) {
				const request: ViewShowRequest = {
					method: "showView",
					viewKey: key,
					view: structuredClone(record.view),
					rev: record.rev,
					...(options.timeout ? { timeout: options.timeout } : {}),
				};
				racers.push(
					channel.sheet(request, abort.signal).then((answer) => {
						// The channel closed it (remote control stopped, say): with no dialogs racing, that dismisses it.
						if (!answer) return text ? never : undefined;
						if (answer.cancelled === true) return undefined;
						// An answer that does not fit the sheet closes it as dismissed: the client's sheet is gone.
						return this.sheetResult(record, answer);
					}),
				);
			}
			if (text) {
				// Checked against the sheet as it is when answered: an update meanwhile may have disabled or removed the
				// chosen action, as it would for a native answer.
				racers.push(
					runViewDialogs(record.view, this.dialogs, abort.signal).then((result) =>
						result ? this.sheetResult(record, { nodeId: result.id, values: result.values }) : undefined,
					),
				);
			}
			return await Promise.race(racers);
		} finally {
			if (timer) clearTimeout(timer);
			options.signal?.removeEventListener("abort", onAbort);
			abort.abort();
			this.sheetAborts.delete(abort);
			if (record.timer !== undefined) clearTimeout(record.timer);
			this.sheets.delete(key);
		}
	}

	/** A client's sheet answer checked against the sheet: a form of it or one of its `actions`, values that fit. */
	private sheetResult(record: ViewRecord, answer: ViewSheetAnswer): MobileViewResult | undefined {
		const view = record.view;
		if (typeof answer.nodeId !== "string") return undefined;
		const node = findShownNode(view, answer.nodeId);
		const isAction = view.actions?.some((action) => action.id === answer.nodeId) === true;
		if (!node || node.hidden) return undefined;
		if (
			node.type === "button"
				? !isAction || node.disabled || node.busy || node.url !== undefined
				: node.type !== "form" || node.disabled || node.busy
		) {
			return undefined;
		}
		const fields = fieldsOf(view.body);
		// Required fields count when they go with the answer: those outside forms, and the submitted form's own.
		const scope = new Set(standaloneFieldIds(view.body));
		if (node.type === "form") for (const id of fieldsOf(node.children).keys()) scope.add(id);
		const values = checkValues(fields, answer.values, (id) => scope.has(id));
		if (!values) return undefined;
		const action =
			node.type === "form"
				? (node.submit.action ?? answer.nodeId)
				: ((node as MobileViewButton).action ?? answer.nodeId);
		return { id: answer.nodeId, action, values };
	}

	/** A client's tap, change or submit: checked against the view, written into it, then emitted. Dropped when it does not fit. */
	handleEvent(event: ViewClientEvent): boolean {
		if (typeof event.viewKey !== "string" || typeof event.nodeId !== "string") return false;
		const key = event.viewKey;
		const nodeId = event.nodeId;
		const record = this.views.get(key) ?? this.sheets.get(key);
		if (!record) return false;
		const now = Date.now();
		const second = Math.floor(now / 1000);
		if (record.eventWindow !== second) {
			record.eventWindow = second;
			record.events = 0;
		}
		if (++record.events > LIMITS.eventsPerSecond) return false;
		const node = findShownNode(record.view, nodeId);
		if (!node || node.hidden) return false;
		const kind = event.kind as ViewEventKind;
		const emitted: ViewEvent = { type: "view_event", key, id: nodeId, action: nodeId, kind };
		const forms: MobileViewForm[] = [];
		forEachNode(record.view, (n) => void (n.type === "form" ? forms.push(n) : undefined));
		const formOf = (fieldId: string) => forms.find((form) => form.children.some((child) => child.id === fieldId));
		switch (kind) {
			case "tap": {
				if (node.type === "button") {
					if (node.url !== undefined || node.disabled || node.busy) return false;
					emitted.action = node.action ?? nodeId;
				} else if ((node.type === "item" || node.type === "tile") && node.action !== undefined) {
					emitted.action = node.action;
				} else if (node.type === "table") {
					// A row tap: the table's id, the row's action, and the row's id as the value.
					const row = node.rows.find((candidate) => candidate.id === event.value);
					if (row?.action === undefined) return false;
					emitted.action = row.action;
					emitted.value = row.id;
				} else {
					return false;
				}
				break;
			}
			case "change": {
				if (!isFieldType(node.type) || formOf(nodeId)) return false;
				const field = node as MobileViewField;
				const value = checkValue(field, event.value);
				if (value === undefined) return false;
				storeValues(new Map([[nodeId, field]]), { [nodeId]: value });
				emitted.value = value;
				break;
			}
			case "submit": {
				if (node.type !== "form" || node.disabled || node.busy) return false;
				const fields = fieldsOf(node.children);
				const values = checkValues(fields, event.values);
				if (!values) return false;
				storeValues(fields, values);
				emitted.action = node.submit.action ?? nodeId;
				emitted.values = values;
				break;
			}
			default:
				return false;
		}
		this.emit(emitted);
		return true;
	}

	/** Removes every view (the session ends or reloads) and closes open sheets' native parts. */
	dispose(): void {
		if (this.disposed) return;
		for (const [key, record] of [...this.views]) this.remove(key, record);
		for (const abort of [...this.sheetAborts]) abort.abort();
		this.disposed = true;
	}
}
