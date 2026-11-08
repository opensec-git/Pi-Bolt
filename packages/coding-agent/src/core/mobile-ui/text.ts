/**
 * The text form of a mobile UI view (docs/mobile-ui.md#fallback): what the terminal and clients without the kit show.
 */

import type {
	MobileView,
	MobileViewButton,
	MobileViewChipProps,
	MobileViewNode,
	MobileViewNoticeTone,
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
