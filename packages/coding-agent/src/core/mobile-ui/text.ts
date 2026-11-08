/**
 * The text form of a mobile UI view (docs/mobile-ui.md#fallback): what the terminal and clients without the kit show.
 */

import type {
	MobileView,
	MobileViewButton,
	MobileViewChart,
	MobileViewChipProps,
	MobileViewLocation,
	MobileViewNode,
	MobileViewNoticeTone,
	MobileViewTable,
	MobileViewTableRow,
	MobileViewTone,
} from "./types.ts";

/** Optional styling of the text form (the terminal's theme); plain text without it. */
export interface MobileViewTextStyle {
	title(text: string): string;
	muted(text: string): string;
	success(text: string): string;
	warning(text: string): string;
	danger(text: string): string;
	accent(text: string): string;
}

const PLAIN: MobileViewTextStyle = {
	title: (text) => text,
	muted: (text) => text,
	success: (text) => text,
	warning: (text) => text,
	danger: (text) => text,
	accent: (text) => text,
};

// Escape sequences (CSI, OSC and other ESC forms) and control characters other than tab and newline.
const ESCAPES = /\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07\x1b]*(?:\x07|\x1b\\)?|[@-Z\\-_])|[\x00-\x08\x0b-\x1f\x7f-\x9f]/g;

/** `text` without escape sequences and control characters, so a view cannot drive the terminal. */
export function cleanText(text: string): string {
	return text.replace(ESCAPES, "");
}

function lines(text: string): string[] {
	return cleanText(text).replace(/\r\n?/g, "\n").split("\n");
}

const TONE_GLYPH: Record<MobileViewTone, string> = {
	neutral: "·",
	success: "✓",
	warning: "!",
	danger: "✗",
	running: "●",
};

const NOTICE_GLYPH: Record<MobileViewNoticeTone, string> = { info: "i", success: "✓", warning: "!", danger: "✗" };

function toned(style: MobileViewTextStyle, tone: string | undefined, text: string): string {
	switch (tone) {
		case "success":
			return style.success(text);
		case "warning":
			return style.warning(text);
		case "danger":
			return style.danger(text);
		case "running":
			return style.accent(text);
		default:
			return text;
	}
}

function chip(style: MobileViewTextStyle, props: MobileViewChipProps): string {
	const tone = props.tone ?? "neutral";
	return toned(style, tone, `${TONE_GLYPH[tone]} ${cleanText(props.label)}`);
}

function button(button: MobileViewButton): string {
	return `[ ${cleanText(button.label)} ]`;
}

function percent(value: number): string {
	return `${Math.round(value * 100)}%`;
}

function progressBar(value: number): string {
	const filled = Math.round(Math.min(1, Math.max(0, value)) * 10);
	return `[${"#".repeat(filled)}${"-".repeat(10 - filled)}] ${percent(value)}`;
}

const SPARK = "▁▂▃▄▅▆▇█";
const CHART_TEXT_VALUES = 40;
const TABLE_TEXT_ROWS = 50;
const TABLE_TEXT_WIDTH = 40;

/** A number as the text form shows it: at most 3 decimals, then the unit. */
export function formatNumber(value: number, unit: string | undefined): string {
	const text = Number.isInteger(value) ? String(value) : String(Number(value.toFixed(3)));
	return unit ? `${text}${/^[%\s]/.test(unit) ? "" : " "}${cleanText(unit)}` : text;
}

/** The y range of a chart: `min`/`max` when given, else the data's (including 0 for bar and area). */
export function chartRange(chart: MobileViewChart): { min: number; max: number } {
	const values = chart.series.flatMap((series) => series.values.filter((v): v is number => v !== null));
	let min = chart.min ?? (values.length > 0 ? Math.min(...values) : 0);
	let max = chart.max ?? (values.length > 0 ? Math.max(...values) : 1);
	// Bars and areas grow from zero: the range always includes it (above all-negative data too).
	if (chart.kind === "bar" || chart.kind === "area") {
		if (chart.min === undefined) min = Math.min(0, min);
		if (chart.max === undefined) max = Math.max(0, max);
	}
	// A flat series is centred, with room relative to its size (1 would vanish next to 1e18).
	if (max <= min) {
		const pad = min === 0 ? 1 : Math.abs(min) * 0.1;
		return { min: min - pad, max: min + pad };
	}
	return { min, max };
}

/** Per series: its last, lowest and highest value (undefined when it has none). */
export function seriesStats(values: Array<number | null>): { last: number; low: number; high: number } | undefined {
	const numbers = values.filter((v): v is number => v !== null);
	if (numbers.length === 0) return undefined;
	return { last: numbers[numbers.length - 1], low: Math.min(...numbers), high: Math.max(...numbers) };
}

/** What a screen reader says for a chart: its `summary`, or the kind, title and per series last, low and high. */
export function chartSummary(chart: MobileViewChart): string {
	if (chart.summary) return chart.summary;
	const kind = { line: "Line chart", bar: "Bar chart", area: "Area chart", sparkline: "Sparkline" }[chart.kind];
	const series = chart.series.map((s) => {
		const stats = seriesStats(s.values);
		const name = chart.series.length > 1 || !chart.title ? `${s.name}: ` : "";
		return stats
			? `${name}last ${formatNumber(stats.last, chart.unit)}, low ${formatNumber(stats.low, chart.unit)}, high ${formatNumber(stats.high, chart.unit)}`
			: `${name}no data`;
	});
	return `${kind}${chart.title ? `, ${chart.title}` : ""}: ${series.join("; ")}`;
}

function chartLines(style: MobileViewTextStyle, chart: MobileViewChart, pad: (text: string) => string): string[] {
	const { min, max } = chartRange(chart);
	const width = Math.max(0, ...chart.series.map((series) => cleanText(series.name).length));
	const rows = chart.series.map((series) => {
		const spark = series.values
			.slice(-CHART_TEXT_VALUES)
			.map((v) =>
				v === null
					? " "
					: SPARK[Math.round(((Math.min(max, Math.max(min, v)) - min) / (max - min)) * (SPARK.length - 1))],
			)
			.join("");
		const stats = seriesStats(series.values);
		const summary = stats
			? `last ${formatNumber(stats.last, chart.unit)} · low ${formatNumber(stats.low, chart.unit)} · high ${formatNumber(stats.high, chart.unit)}`
			: "no data";
		return pad(
			`${style.muted(cleanText(series.name).padEnd(width))}  ${style.accent(spark)}  ${style.muted(summary)}`,
		);
	});
	return [...(chart.title ? [pad(cleanText(chart.title))] : []), ...rows];
}

function compareCells(
	a: string | number | null | undefined,
	b: string | number | null | undefined,
	kind: string | undefined,
): number {
	const aEmpty = a === null || a === undefined || a === "";
	const bEmpty = b === null || b === undefined || b === "";
	if (aEmpty || bEmpty) return aEmpty === bEmpty ? 0 : aEmpty ? 1 : -1;
	if (kind === "number") {
		const x = typeof a === "number" ? a : Number(a);
		const y = typeof b === "number" ? b : Number(b);
		if (!Number.isNaN(x) && !Number.isNaN(y)) return x - y;
	}
	if (kind === "date") {
		const x = Date.parse(String(a));
		const y = Date.parse(String(b));
		if (!Number.isNaN(x) && !Number.isNaN(y)) return x - y;
	}
	return String(a).localeCompare(String(b));
}

/** The table's rows in its `sort` order (stable; empty cells last either way), or as given without `sort`. */
export function sortedRows(table: MobileViewTable): MobileViewTableRow[] {
	const sort = table.sort;
	if (!sort) return table.rows;
	const kind = table.columns.find((column) => column.id === sort.column)?.kind;
	return table.rows
		.map((row, index) => ({ row, index }))
		.sort((a, b) => {
			const x = a.row.cells[sort.column];
			const y = b.row.cells[sort.column];
			const empty = (v: unknown) => v === null || v === undefined || v === "";
			if (empty(x) || empty(y)) return empty(x) === empty(y) ? a.index - b.index : empty(x) ? 1 : -1;
			const order = compareCells(x, y, kind);
			return (sort.desc ? -order : order) || a.index - b.index;
		})
		.map(({ row }) => row);
}

function cellText(value: string | number | null | undefined): string {
	if (value === null || value === undefined) return "";
	const text = cleanText(String(value)).replace(/\s+/g, " ");
	return text.length > TABLE_TEXT_WIDTH ? `${text.slice(0, TABLE_TEXT_WIDTH - 1)}…` : text;
}

function tableLines(style: MobileViewTextStyle, table: MobileViewTable, pad: (text: string) => string): string[] {
	if (table.rows.length === 0) return table.empty ? [pad(style.muted(cleanText(table.empty)))] : [];
	const rows = sortedRows(table).slice(0, TABLE_TEXT_ROWS);
	const widths = table.columns.map((column) =>
		Math.max(cellText(column.label).length, ...rows.map((row) => cellText(row.cells[column.id]).length)),
	);
	const line = (cells: string[]) =>
		cells
			.map((cell, i) => (table.columns[i].kind === "number" ? cell.padStart(widths[i]) : cell.padEnd(widths[i])))
			.join("  ")
			.trimEnd();
	const more = table.rows.length - rows.length;
	return [
		pad(style.muted(line(table.columns.map((column) => cellText(column.label))))),
		...rows.map((row) => pad(line(table.columns.map((column) => cellText(row.cells[column.id]))))),
		...(more > 0 ? [pad(style.muted(`… ${more} more row${more === 1 ? "" : "s"}`))] : []),
	];
}

function locationLine(place: MobileViewLocation): string {
	const coords = place.lat !== undefined && place.lon !== undefined ? ` (${place.lat}, ${place.lon})` : "";
	return `⌖ ${cleanText(place.name)}${place.address ? ` — ${cleanText(place.address)}` : ""}${coords}`;
}

function nodeLines(style: MobileViewTextStyle, node: MobileViewNode, indent: string): string[] {
	if (node.hidden) return [];
	const pad = (text: string) => `${indent}${text}`;
	switch (node.type) {
		case "text": {
			const format = node.style === "title" ? style.title : node.style === "meta" ? style.muted : (t: string) => t;
			return lines(node.text).map((line) => pad(format(line)));
		}
		case "markdown":
			return lines(node.text).map(pad);
		case "code":
			return [
				...(node.title ? [pad(style.muted(cleanText(node.title)))] : []),
				...lines(node.text).map((l) => pad(`  ${l}`)),
			];
		case "image":
			return [pad(style.muted(`[image${node.alt ? `: ${cleanText(node.alt)}` : ""}]`))];
		case "divider":
			return [pad(style.muted("─".repeat(24)))];
		case "notice": {
			const head = `${NOTICE_GLYPH[node.tone]} ${node.title ? `${cleanText(node.title)}: ` : ""}`;
			const [first = "", ...rest] = lines(node.text);
			return [pad(toned(style, node.tone, head) + first), ...rest.map((line) => pad(`  ${line}`))];
		}
		case "kv": {
			const width = Math.max(0, ...node.items.map((item) => cleanText(item.key).length));
			return node.items.map((item) =>
				pad(`${style.muted(cleanText(item.key).padEnd(width))}  ${cleanText(item.value)}`),
			);
		}
		case "progress": {
			const label = [node.label, node.detail].filter((part) => part).map((part) => cleanText(part as string));
			const head = node.value === undefined ? style.accent("…") : progressBar(node.value);
			return [pad([head, ...label].join("  "))];
		}
		case "chip":
			return [pad(chip(style, node))];
		case "tile":
			return [pad(`${cleanText(node.label)}: ${toned(style, node.tone, cleanText(node.value))}`)];
		case "chart":
			return chartLines(style, node, pad);
		case "table":
			return tableLines(style, node, pad);
		case "location":
			return [pad(locationLine(node))];
		case "button":
			return [pad(button(node))];
		case "card": {
			const head = [
				node.title ? style.title(cleanText(node.title)) : "",
				node.subtitle ? style.muted(cleanText(node.subtitle)) : "",
			]
				.filter((part) => part)
				.join(style.muted(" · "));
			const status = node.status ? chip(style, node.status) : "";
			const title = [head, status].filter((part) => part).join("  ");
			return [
				...(title ? [pad(title)] : []),
				...node.children.flatMap((child) => nodeLines(style, child, `${indent}  `)),
				...(node.actions?.length
					? [
							pad(
								`  ${node.actions
									.filter((a) => !a.hidden)
									.map(button)
									.join("  ")}`,
							),
						]
					: []),
			];
		}
		case "section":
			return [
				...(node.title ? [pad(style.muted(cleanText(node.title)))] : []),
				...node.children.flatMap((child) => nodeLines(style, child, indent)),
			];
		case "list": {
			const items = node.children.flatMap((child) => nodeLines(style, child, indent));
			return items.length > 0 || !node.empty ? items : [pad(style.muted(cleanText(node.empty)))];
		}
		case "item": {
			const parts = [`• ${cleanText(node.title)}`];
			if (node.subtitle) parts[0] += style.muted(` — ${cleanText(node.subtitle)}`);
			if (node.value) parts.push(cleanText(node.value));
			if (node.progress !== undefined) parts.push(percent(node.progress));
			if (node.chip) parts.push(chip(style, node.chip));
			return [pad(parts.join("  "))];
		}
		case "row":
		case "grid": {
			const parts = node.children
				.filter((child) => !child.hidden)
				.map((child) => nodeLines(style, child, "").join(" "));
			return parts.length > 0 ? [pad(parts.join("  "))] : [];
		}
		case "form":
			return [
				...node.children.flatMap((child) => nodeLines(style, child, indent)),
				pad(button({ type: "button", label: node.submit.label })),
			];
		case "input": {
			const value = node.value
				? node.kind === "password"
					? "••••"
					: cleanText(node.value)
				: style.muted(cleanText(node.placeholder ?? ""));
			return [pad(`${cleanText(node.label)}: ${value}`)];
		}
		case "toggle":
			return [pad(`${cleanText(node.label)}: ${node.value ? "on" : "off"}`)];
		case "select": {
			const option = node.options.find((o) => (typeof o === "string" ? o : o.value) === node.value);
			const label = option === undefined ? "" : typeof option === "string" ? option : option.label;
			return [pad(`${cleanText(node.label)}: ${cleanText(label)}`)];
		}
		case "slider":
			return [pad(`${cleanText(node.label)}: ${node.value}${node.unit ? ` ${cleanText(node.unit)}` : ""}`)];
	}
}

/** The view's header line: `Title · subtitle`, or undefined without a title. */
export function viewTitle(view: MobileView, style: MobileViewTextStyle = PLAIN): string | undefined {
	if (!view.title) return undefined;
	const title = style.title(cleanText(view.title));
	return view.subtitle ? `${title}${style.muted(` · ${cleanText(view.subtitle)}`)}` : title;
}

/** The text form of `view`: its `fallback` lines, or lines derived from its nodes. */
export function viewToLines(view: MobileView, style: MobileViewTextStyle = PLAIN): string[] {
	if (view.fallback) return view.fallback.flatMap(lines);
	const title = viewTitle(view, style);
	const actions = (view.actions ?? []).filter((action) => !action.hidden);
	return [
		...(title ? [title] : []),
		...view.body.flatMap((node) => nodeLines(style, node, "")),
		...(actions.length > 0 ? [actions.map(button).join("  ")] : []),
	];
}
