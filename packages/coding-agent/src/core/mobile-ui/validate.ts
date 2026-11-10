/**
 * Checks a mobile UI view (docs/mobile-ui.md) against kit version 1 and its limits. Strict: unknown types and fields
 * are errors, which catches typos while a view is written. Errors name the place: `body[2].children[0]: label: ...`.
 */

import {
	MOBILE_VIEW_LIMITS as LIMITS,
	MOBILE_VIEW_ICONS,
	MOBILE_VIEW_ID_PATTERN,
	MOBILE_VIEW_TILE_TYPES,
	type MobileView,
	type MobileViewNode,
	type MobileViewNodeType,
} from "./types.ts";

const MAX_ERRORS = 20;
const ICONS: ReadonlySet<string> = new Set(MOBILE_VIEW_ICONS);
const TONES = ["neutral", "success", "warning", "danger", "running"];
const NOTICE_TONES = ["info", "success", "warning", "danger"];
const FIELD_TYPES = new Set(["input", "toggle", "select", "slider"]);
const LINK_SCHEMES = new Set(["https:", "http:", "mailto:"]);
const IMAGE_DATA = /^data:image\/(png|jpeg|webp|gif);base64,[A-Za-z0-9+/]+={0,2}$/;

type Record_ = Record<string, unknown>;

/** Checks one field of an object; returns an error message or undefined. `node` is the whole object. */
type FieldCheck = (value: unknown, node: Record_) => string | undefined;

interface Context {
	errors: string[];
	ids: Set<string>;
	nodes: number;
}

function isRecord(value: unknown): value is Record_ {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

const str =
	(max: number = LIMITS.short): FieldCheck =>
	(value) =>
		typeof value !== "string"
			? "expected a string"
			: value.length > max
				? `longer than ${max} characters`
				: undefined;
const bool: FieldCheck = (value) => (typeof value === "boolean" ? undefined : "expected true or false");
const num =
	(min = Number.NEGATIVE_INFINITY, max = Number.POSITIVE_INFINITY): FieldCheck =>
	(value) =>
		typeof value !== "number" || !Number.isFinite(value)
			? "expected a number"
			: value < min || value > max
				? `expected ${min} to ${max}`
				: undefined;
const oneOf =
	(values: readonly unknown[]): FieldCheck =>
	(value) =>
		values.includes(value) ? undefined : `expected one of ${values.map((v) => JSON.stringify(v)).join(", ")}`;
const icon: FieldCheck = (value) =>
	typeof value === "string" && ICONS.has(value) ? undefined : `unknown icon ${JSON.stringify(value)}`;
const id: FieldCheck = (value) =>
	typeof value === "string" && MOBILE_VIEW_ID_PATTERN.test(value)
		? undefined
		: "expected 1 to 64 of A-Z a-z 0-9 _ . : -";

/** `https:`, `http:` or `mailto:`. */
export function isAllowedLink(value: string): boolean {
	try {
		return LINK_SCHEMES.has(new URL(value).protocol);
	} catch {
		return false;
	}
}

/** A host name the app may fetch from: a dotted public name, no IP address, localhost or LAN name. */
export function isPublicHost(host: string): boolean {
	const name = host.toLowerCase().replace(/\.$/, "");
	if (!name.includes(".") || name.startsWith("[")) return false;
	if (/^\d+(\.\d+){3}$/.test(name)) return false;
	return !/(^|\.)(localhost|local|lan|internal|home|home\.arpa|localdomain)$/.test(name);
}

const link: FieldCheck = (value) =>
	typeof value === "string" && value.length <= 2048 && isAllowedLink(value)
		? undefined
		: "expected an https:, http: or mailto: link";

const imageSource: FieldCheck = (value) => {
	if (typeof value !== "string") return "expected a string";
	if (value.startsWith("data:")) {
		if (!IMAGE_DATA.test(value)) return "expected data:image/png, jpeg, webp or gif in base64";
		return value.length > LIMITS.imageDataBytes
			? `data: image larger than ${LIMITS.imageDataBytes} bytes`
			: undefined;
	}
	try {
		const url = new URL(value);
		if (url.protocol === "https:" && isPublicHost(url.hostname) && value.length <= 2048) return undefined;
	} catch {}
	return "expected a data: image or an https: URL on a public host";
};

const chipProps: FieldCheck = (value) => {
	if (!isRecord(value)) return "expected { label, tone?, icon? }";
	return checkObject(value, { label: str(), tone: oneOf(TONES), icon }, ["label"]);
};

const confirm: FieldCheck = (value) => {
	if (!isRecord(value)) return "expected { title, message?, label? }";
	return checkObject(value, { title: str(), message: str(LIMITS.text), label: str() }, ["title"]);
};

const submit: FieldCheck = (value) => {
	if (!isRecord(value)) return "expected { label, action? }";
	return checkObject(value, { label: str(), action: id }, ["label"]);
};

const kvItems: FieldCheck = (value) => {
	if (!Array.isArray(value)) return "expected a list";
	if (value.length > LIMITS.kvItems) return `more than ${LIMITS.kvItems} items`;
	for (const [index, item] of value.entries()) {
		const error = isRecord(item)
			? checkObject(item, { key: str(), value: str(), mono: bool, copy: bool }, ["key", "value"])
			: "expected { key, value }";
		if (error) return `[${index}]: ${error}`;
	}
	return undefined;
};

const selectOptions: FieldCheck = (value) => {
	if (!Array.isArray(value) || value.length === 0) return "expected a list of options";
	if (value.length > LIMITS.options) return `more than ${LIMITS.options} options`;
	const seen = new Set<string>();
	for (const [index, option] of value.entries()) {
		const error =
			typeof option === "string"
				? option.length > LIMITS.short
					? `longer than ${LIMITS.short} characters`
					: undefined
				: isRecord(option)
					? checkObject(option, { value: str(), label: str() }, ["value", "label"])
					: "expected a string or { value, label }";
		if (error) return `[${index}]: ${error}`;
		const optionValue = typeof option === "string" ? option : String(option.value);
		if (seen.has(optionValue)) return `[${index}]: option ${JSON.stringify(optionValue)} twice`;
		seen.add(optionValue);
	}
	return undefined;
};

const chartSeries: FieldCheck = (value, node) => {
	if (!Array.isArray(value) || value.length === 0) return "expected a list of series";
	const max = node.kind === "sparkline" ? 1 : LIMITS.chartSeries;
	if (value.length > max) return `more than ${max} series${node.kind === "sparkline" ? " in a sparkline" : ""}`;
	let length: number | undefined;
	for (const [index, series] of value.entries()) {
		if (!isRecord(series)) return `[${index}]: expected { name, values }`;
		const error = checkObject(
			series,
			{
				name: str(),
				values: (values) =>
					!Array.isArray(values) || values.length === 0
						? "expected a list of numbers"
						: values.length > LIMITS.chartValues
							? `more than ${LIMITS.chartValues} values`
							: values.some((v) => v !== null && (typeof v !== "number" || !Number.isFinite(v)))
								? "expected numbers or null"
								: values.some((v) => typeof v === "number" && Math.abs(v) > LIMITS.chartMagnitude)
									? `expected numbers of at most ${LIMITS.chartMagnitude} in size`
									: undefined,
			},
			["name", "values"],
		);
		if (error) return `[${index}]: ${error}`;
		const count = (series.values as unknown[]).length;
		if (length !== undefined && count !== length)
			return `[${index}]: ${count} values, the first series has ${length}`;
		length = count;
	}
	return undefined;
};

const stringList =
	(max: number): FieldCheck =>
	(value) =>
		!Array.isArray(value)
			? "expected a list of strings"
			: value.length > max
				? `more than ${max}`
				: value.some((item) => typeof item !== "string" || item.length > LIMITS.short)
					? `expected strings of at most ${LIMITS.short} characters`
					: undefined;

const tableColumns: FieldCheck = (value) => {
	if (!Array.isArray(value) || value.length === 0) return "expected a list of columns";
	if (value.length > LIMITS.tableColumns) return `more than ${LIMITS.tableColumns} columns`;
	const seen = new Set<string>();
	for (const [index, column] of value.entries()) {
		const error = isRecord(column)
			? checkObject(column, { id, label: str(), kind: oneOf(["text", "number", "date", "mono"]), sortable: bool }, [
					"id",
					"label",
				])
			: "expected { id, label }";
		if (error) return `[${index}]: ${error}`;
		const columnId = (column as Record_).id as string;
		if (seen.has(columnId)) return `[${index}]: column ${JSON.stringify(columnId)} twice`;
		seen.add(columnId);
	}
	return undefined;
};

function columnIds(node: Record_): Set<string> {
	return new Set(
		Array.isArray(node.columns) ? node.columns.map((column) => (isRecord(column) ? String(column.id) : "")) : [],
	);
}

const tableRows: FieldCheck = (value, node) => {
	if (!Array.isArray(value)) return "expected a list of rows";
	if (value.length > LIMITS.tableRows) return `more than ${LIMITS.tableRows} rows`;
	const columns = columnIds(node);
	const seen = new Set<string>();
	for (const [index, row] of value.entries()) {
		if (!isRecord(row)) return `[${index}]: expected { id, cells }`;
		const error = checkObject(
			row,
			{
				id,
				action: id,
				cells: (cells) => {
					if (!isRecord(cells)) return "expected an object of column ids to values";
					for (const [column, cell] of Object.entries(cells)) {
						if (!columns.has(column)) return `${column}: no such column`;
						if (cell === null) continue;
						if (typeof cell === "number" ? !Number.isFinite(cell) : typeof cell !== "string")
							return `${column}: expected a string, a number or null`;
						if (typeof cell === "string" && cell.length > LIMITS.short)
							return `${column}: longer than ${LIMITS.short} characters`;
					}
					return undefined;
				},
			},
			["id", "cells"],
		);
		if (error) return `[${index}]: ${error}`;
		if (seen.has(row.id as string)) return `[${index}]: row ${JSON.stringify(row.id)} twice`;
		seen.add(row.id as string);
	}
	return undefined;
};

const tableSort: FieldCheck = (value, node) => {
	if (!isRecord(value)) return "expected { column, desc? }";
	const error = checkObject(value, { column: str(64), desc: bool }, ["column"]);
	if (error) return error;
	return columnIds(node).has(value.column as string) ? undefined : `column: no column ${JSON.stringify(value.column)}`;
};

/** The values of a select's options. */
export function optionValues(options: unknown): string[] {
	if (!Array.isArray(options)) return [];
	return options.map((option) => (typeof option === "string" ? option : String((option as Record_).value)));
}

/** Checks `node`'s fields against `fields`: unknown fields, missing required ones, then each value. */
function checkObject(node: Record_, fields: Record<string, FieldCheck>, required: string[]): string | undefined {
	for (const name of required) if (node[name] === undefined) return `${name}: missing`;
	for (const [name, value] of Object.entries(node)) {
		const check = fields[name];
		if (!check) return `${name}: unknown field`;
		if (value === undefined) continue;
		const error = check(value, node);
		if (error) return `${name}: ${error}`;
	}
	return undefined;
}

interface NodeSpec {
	fields: Record<string, FieldCheck>;
	required: string[];
	/** Node types allowed as `children`; undefined: no children. */
	children?: ReadonlySet<MobileViewNodeType> | "any";
	/** Needs an `id`: it sends events. */
	interactive?: (node: Record_) => boolean;
	/** Checks across fields. */
	extra?: (node: Record_) => string | undefined;
}

const common = { id, hidden: bool };
const ANY = "any" as const;

const SPECS: Record<MobileViewNodeType, NodeSpec> = {
	text: {
		fields: { ...common, type: str(), text: str(LIMITS.text), style: oneOf(["body", "title", "meta", "mono"]) },
		required: ["text"],
	},
	markdown: { fields: { ...common, type: str(), text: str(LIMITS.markdown) }, required: ["text"] },
	code: {
		fields: { ...common, type: str(), text: str(LIMITS.code), language: str(40), title: str() },
		required: ["text"],
	},
	image: {
		fields: { ...common, type: str(), src: imageSource, alt: str(), decorative: bool, aspect: num(0.25, 4) },
		required: ["src"],
		extra: (node) =>
			node.decorative !== true && (typeof node.alt !== "string" || node.alt.trim() === "")
				? "alt: needed unless decorative: true"
				: undefined,
	},
	divider: { fields: { ...common, type: str() }, required: [] },
	notice: {
		fields: { ...common, type: str(), tone: oneOf(NOTICE_TONES), title: str(), text: str(LIMITS.text) },
		required: ["tone", "text"],
	},
	kv: { fields: { ...common, type: str(), items: kvItems }, required: ["items"] },
	progress: {
		fields: { ...common, type: str(), value: num(0, 1), label: str(), detail: str() },
		required: [],
	},
	chip: { fields: { ...common, type: str(), label: str(), tone: oneOf(TONES), icon }, required: ["label"] },
	tile: {
		fields: { ...common, type: str(), label: str(), value: str(), icon, tone: oneOf(TONES), action: id },
		required: ["label", "value"],
		interactive: (node) => node.action !== undefined,
	},
	chart: {
		fields: {
			...common,
			type: str(),
			kind: oneOf(["line", "bar", "area", "sparkline"]),
			series: chartSeries,
			labels: stringList(LIMITS.chartValues),
			unit: str(20),
			title: str(),
			min: num(-LIMITS.chartMagnitude, LIMITS.chartMagnitude),
			max: num(-LIMITS.chartMagnitude, LIMITS.chartMagnitude),
			summary: str(LIMITS.text),
		},
		required: ["kind", "series"],
		extra: (node) => {
			const series = node.series as Array<{ values: unknown[] }>;
			if (Array.isArray(node.labels) && node.labels.length !== series[0].values.length)
				return `labels: ${node.labels.length} labels for ${series[0].values.length} values`;
			if (typeof node.min === "number" && typeof node.max === "number" && !(node.min < node.max))
				return "max: must be greater than min";
			return undefined;
		},
	},
	table: {
		fields: {
			...common,
			type: str(),
			columns: tableColumns,
			rows: tableRows,
			sort: tableSort,
			filter: bool,
			empty: str(),
		},
		required: ["columns", "rows"],
		interactive: (node) =>
			Array.isArray(node.rows) && node.rows.some((row) => isRecord(row) && row.action !== undefined),
	},
	location: {
		fields: { ...common, type: str(), name: str(), address: str(), lat: num(-90, 90), lon: num(-180, 180) },
		required: ["name"],
		extra: (node) =>
			(node.lat === undefined) !== (node.lon === undefined)
				? "lat, lon: give both or neither"
				: node.lat === undefined && node.address === undefined
					? "address: needed without lat and lon"
					: undefined,
	},
	button: {
		fields: {
			...common,
			type: str(),
			label: str(),
			style: oneOf(["primary", "secondary", "ghost", "danger"]),
			icon,
			action: id,
			disabled: bool,
			busy: bool,
			url: link,
			confirm,
		},
		required: ["label"],
		interactive: (node) => node.url === undefined,
		extra: (node) =>
			node.url !== undefined && (node.action !== undefined || node.confirm !== undefined)
				? "url: a link button takes no action or confirm"
				: undefined,
	},
	card: {
		fields: {
			...common,
			type: str(),
			title: str(),
			subtitle: str(),
			icon,
			status: chipProps,
			children: () => undefined,
			actions: () => undefined,
		},
		required: ["children"],
		children: ANY,
	},
	section: {
		fields: { ...common, type: str(), title: str(), children: () => undefined },
		required: ["children"],
		children: ANY,
	},
	list: {
		fields: { ...common, type: str(), children: () => undefined, empty: str() },
		required: ["children"],
		children: new Set(["item"]),
	},
	item: {
		fields: {
			...common,
			type: str(),
			title: str(),
			subtitle: str(),
			icon,
			value: str(),
			chip: chipProps,
			progress: num(0, 1),
			action: id,
			chevron: bool,
		},
		required: ["title"],
		interactive: (node) => node.action !== undefined,
	},
	row: {
		fields: { ...common, type: str(), children: () => undefined },
		required: ["children"],
		children: new Set(["chip", "button", "text"]),
	},
	grid: {
		fields: { ...common, type: str(), columns: oneOf([2, 3, 4]), children: () => undefined },
		required: ["children"],
		children: new Set(["tile"]),
	},
	form: {
		fields: { ...common, type: str(), children: () => undefined, submit, disabled: bool, busy: bool },
		required: ["children", "submit"],
		children: new Set(["input", "toggle", "select", "slider", "text", "notice", "divider"]),
		interactive: () => true,
	},
	input: {
		fields: {
			...common,
			type: str(),
			label: str(),
			value: str(LIMITS.inputMaxLength),
			placeholder: str(),
			hint: str(LIMITS.text),
			error: str(LIMITS.text),
			kind: oneOf(["text", "multiline", "number", "email", "url", "password"]),
			required: bool,
			maxLength: num(1, LIMITS.inputMaxLength),
		},
		required: ["label"],
		interactive: () => true,
		extra: (node) => {
			if (node.maxLength !== undefined && !Number.isInteger(node.maxLength)) return "maxLength: expected an integer";
			const max = typeof node.maxLength === "number" ? node.maxLength : LIMITS.inputDefaultMaxLength;
			return typeof node.value === "string" && node.value.length > max ? `value: longer than ${max}` : undefined;
		},
	},
	toggle: {
		fields: { ...common, type: str(), label: str(), value: bool, hint: str(LIMITS.text) },
		required: ["label", "value"],
		interactive: () => true,
	},
	select: {
		fields: { ...common, type: str(), label: str(), options: selectOptions, value: str() },
		required: ["label", "options"],
		interactive: () => true,
		extra: (node) =>
			node.value !== undefined && !optionValues(node.options).includes(node.value as string)
				? "value: not one of the options"
				: undefined,
	},
	slider: {
		fields: {
			...common,
			type: str(),
			label: str(),
			min: num(),
			max: num(),
			step: num(Number.MIN_VALUE),
			value: num(),
			unit: str(20),
		},
		required: ["label", "min", "max", "value"],
		interactive: () => true,
		extra: (node) => {
			const { min, max, value } = node as { min: number; max: number; value: number };
			if (!(min < max)) return "max: must be greater than min";
			return value < min || value > max ? "value: outside min to max" : undefined;
		},
	},
};

function push(ctx: Context, message: string): void {
	if (ctx.errors.length < MAX_ERRORS) ctx.errors.push(message);
}

function checkButtons(ctx: Context, value: unknown, path: string, depth: number): void {
	if (!Array.isArray(value)) {
		push(ctx, `${path}: expected a list of buttons`);
		return;
	}
	if (value.length > LIMITS.actions) push(ctx, `${path}: more than ${LIMITS.actions} buttons`);
	for (const [index, button] of value.entries()) {
		const at = `${path}[${index}]`;
		if (!isRecord(button) || button.type !== "button") push(ctx, `${at}: expected a button`);
		else checkNode(ctx, button, at, depth);
	}
}

function checkNode(ctx: Context, node: unknown, path: string, depth: number): void {
	if (ctx.errors.length >= MAX_ERRORS) return;
	ctx.nodes++;
	if (ctx.nodes === LIMITS.nodes + 1) push(ctx, `${path}: more than ${LIMITS.nodes} nodes in the view`);
	if (depth > LIMITS.depth) {
		push(ctx, `${path}: nested deeper than ${LIMITS.depth}`);
		return;
	}
	if (!isRecord(node)) {
		push(ctx, `${path}: expected a node object`);
		return;
	}
	const type = node.type;
	const spec = typeof type === "string" ? SPECS[type as MobileViewNodeType] : undefined;
	if (!spec || !Object.hasOwn(SPECS, type as string)) {
		push(ctx, `${path}: unknown type ${JSON.stringify(type)}`);
		return;
	}
	const error = checkObject(node, spec.fields, spec.required) ?? spec.extra?.(node);
	if (error) push(ctx, `${path}: ${error}`);
	if (typeof node.id === "string") {
		if (ctx.ids.has(node.id)) push(ctx, `${path}: id ${JSON.stringify(node.id)} is used twice`);
		ctx.ids.add(node.id);
	} else if (spec.interactive?.(node)) {
		push(ctx, `${path}: id: needed, the ${type} sends events`);
	}
	if (node.actions !== undefined) checkButtons(ctx, node.actions, `${path}.actions`, depth + 1);
	if (spec.children === undefined) return;
	const children = node.children;
	if (!Array.isArray(children)) {
		push(ctx, `${path}.children: expected a list`);
		return;
	}
	if (children.length > LIMITS.children) push(ctx, `${path}.children: more than ${LIMITS.children}`);
	for (const [index, child] of children.entries()) {
		const at = `${path}.children[${index}]`;
		const childType = isRecord(child) ? child.type : undefined;
		if (spec.children !== ANY && !spec.children.has(childType as MobileViewNodeType)) {
			push(ctx, `${at}: a ${type} cannot hold ${JSON.stringify(childType)}`);
			continue;
		}
		checkNode(ctx, child, at, depth + 1);
	}
}

/** Every problem of `value` as a view (at most 20), or [] when it is a valid view. */
export function validateView(value: unknown): string[] {
	const ctx: Context = { errors: [], ids: new Set(), nodes: 0 };
	if (!isRecord(value)) return ["view: expected an object"];
	const header = checkObject(
		value,
		{
			v: oneOf([1]),
			title: str(),
			subtitle: str(),
			icon,
			body: () => undefined,
			actions: () => undefined,
			fallback: (lines) =>
				!Array.isArray(lines) || lines.some((line) => typeof line !== "string" || line.length > LIMITS.text)
					? "expected a list of lines"
					: lines.length > LIMITS.fallbackLines
						? `more than ${LIMITS.fallbackLines} lines`
						: undefined,
		},
		["v", "body"],
	);
	if (header) push(ctx, header);
	if (Array.isArray(value.body)) {
		if (value.body.length > LIMITS.children) push(ctx, `body: more than ${LIMITS.children} nodes`);
		for (const [index, node] of value.body.entries()) checkNode(ctx, node, `body[${index}]`, 1);
	} else if (value.body !== undefined) {
		push(ctx, "body: expected a list");
	}
	if (value.actions !== undefined) checkButtons(ctx, value.actions, "actions", 1);
	if (ctx.errors.length === 0) {
		const bytes = Buffer.byteLength(JSON.stringify(value), "utf8");
		if (bytes > LIMITS.viewBytes) push(ctx, `view: ${bytes} bytes, more than ${LIMITS.viewBytes}`);
	}
	return ctx.errors;
}

/**
 * The problems of `view` (already a valid view) as a Home tile: a title, only the tile's node types, at most 4 `kv`
 * items, no actions or fallback, and the tile's size limits.
 */
export function validateTile(view: MobileView): string[] {
	const errors: string[] = [];
	if (typeof view.title !== "string" || view.title.trim() === "") errors.push("title: a tile needs one");
	if (view.actions !== undefined) errors.push("actions: a tile has no buttons");
	if (view.fallback !== undefined) errors.push("fallback: a tile has no text form");
	let nodes = 0;
	const walk = (list: readonly MobileViewNode[], path: string): void => {
		for (const [index, node] of list.entries()) {
			const at = `${path}[${index}]`;
			nodes++;
			if (!MOBILE_VIEW_TILE_TYPES.has(node.type))
				errors.push(`${at}: a tile cannot hold ${JSON.stringify(node.type)}`);
			else if (node.type === "kv" && node.items.length > LIMITS.tileKvItems)
				errors.push(`${at}: more than ${LIMITS.tileKvItems} items in a tile`);
			else if (node.type === "row") walk(node.children, `${at}.children`);
		}
	};
	walk(view.body, "body");
	if (nodes > LIMITS.tileNodes) errors.push(`body: more than ${LIMITS.tileNodes} nodes in a tile`);
	const bytes = Buffer.byteLength(JSON.stringify(view), "utf8");
	if (bytes > LIMITS.tileBytes) errors.push(`view: ${bytes} bytes, more than ${LIMITS.tileBytes} for a tile`);
	return errors.slice(0, MAX_ERRORS);
}

/** Throws an Error that lists the problems when `value` is not a valid view. */
export function assertView(value: unknown, what: string): asserts value is MobileView {
	const errors = validateView(value);
	if (errors.length > 0) throw new Error(`Invalid view ${what}: ${errors.join("; ")}`);
}

/** Calls `visit` for every node of `view` (body, children, actions), depth first, with its parent's children list. */
export function forEachNode(
	view: MobileView,
	visit: (node: MobileViewNode, siblings: MobileViewNode[], index: number) => boolean | undefined,
): boolean {
	const walk = (nodes: MobileViewNode[]): boolean => {
		for (let index = 0; index < nodes.length; index++) {
			const node = nodes[index];
			if (visit(node, nodes, index)) return true;
			const record = node as unknown as Record_;
			if (Array.isArray(record.actions) && walk(record.actions as MobileViewNode[])) return true;
			if (Array.isArray(record.children) && walk(record.children as MobileViewNode[])) return true;
		}
		return false;
	};
	return walk(view.body) || (view.actions !== undefined && walk(view.actions));
}

/** The node with `id` in `view`. */
export function findNode(view: MobileView, nodeId: string): MobileViewNode | undefined {
	let found: MobileViewNode | undefined;
	forEachNode(view, (node) => {
		if (node.id === nodeId) found = node;
		return found !== undefined;
	});
	return found;
}

/** The node with `id` in `view` when it is shown: neither it nor a node it sits in is hidden. */
export function findShownNode(view: MobileView, nodeId: string): MobileViewNode | undefined {
	const walk = (nodes: readonly MobileViewNode[] | undefined): MobileViewNode | undefined => {
		for (const node of nodes ?? []) {
			if (node.hidden) continue;
			if (node.id === nodeId) return node;
			const record = node as unknown as { actions?: MobileViewNode[]; children?: MobileViewNode[] };
			const found = walk(record.actions) ?? walk(record.children);
			if (found) return found;
		}
		return undefined;
	};
	return walk(view.body) ?? walk(view.actions);
}

export function isFieldType(type: string): boolean {
	return FIELD_TYPES.has(type);
}
