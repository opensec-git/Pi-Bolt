/**
 * An HTML preview of mobile UI views in the Pi-Bolt app's default look (android/DESIGN.md), dark and light at phone
 * width, so authors can check a layout without a phone (`pi-bolt mobile-ui preview`). An approximation: the app is the
 * reference. The page has no scripts.
 */

import { chartRange, chartSummary, formatNumber, sortedRows } from "./text.ts";
import type {
	MobileView,
	MobileViewButton,
	MobileViewChart,
	MobileViewChipProps,
	MobileViewNode,
	MobileViewTable,
	MobileViewTone,
} from "./types.ts";
import { optionValues } from "./validate.ts";

const ICON_GLYPHS: Record<string, string> = {
	add: "+",
	alert: "⚠",
	check: "✓",
	close: "✕",
	delete: "⌫",
	error: "⊗",
	info: "ⓘ",
	pause: "❚❚",
	play: "▶",
	refresh: "↻",
	rocket: "➚",
	search: "⌕",
	settings: "⚙",
	star: "★",
	stop: "■",
	sync: "⇅",
	warning: "⚠",
	upload: "↑",
	download: "↓",
	send: "➤",
	link: "🔗︎",
	lock: "🔒︎",
	bolt: "ϟ",
	branch: "⑂",
	build: "⚒",
	terminal: "›_",
	edit: "✎",
	clock: "◷",
	mail: "✉",
	flag: "⚑",
	folder: "▤",
	file: "▯",
	person: "◉",
	globe: "◍",
	heart: "♥",
	home: "⌂",
	copy: "⧉",
	key: "⚿",
	bell: "♪",
	chart: "▥",
};

const TONE_GLYPHS: Record<MobileViewTone, string> = {
	neutral: "•",
	success: "✓",
	warning: "!",
	danger: "✕",
	running: "◌",
};

function esc(text: string): string {
	return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

function icon(name: string | undefined): string {
	return name ? `<span class="icon" aria-hidden="true">${esc(ICON_GLYPHS[name] ?? "•")}</span>` : "";
}

/** A little Markdown: paragraphs, line breaks, **bold**, *italic*, `code` and https/http/mailto links. */
function markdown(text: string): string {
	return text
		.split(/\n{2,}/)
		.map((paragraph) => {
			let html = esc(paragraph);
			html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
			html = html.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>").replace(/\*([^*]+)\*/g, "<em>$1</em>");
			html = html.replace(/\[([^\]]+)\]\(((?:https?|mailto):[^)\s]+)\)/g, '<a href="$2">$1</a>');
			const heading = /^(#{1,3}) (.*)$/.exec(html);
			if (heading) return `<p class="h${heading[1].length}">${heading[2]}</p>`;
			return `<p>${html.replace(/\n/g, "<br>")}</p>`;
		})
		.join("");
}

function chip(props: MobileViewChipProps): string {
	const tone = props.tone ?? "neutral";
	return `<span class="chip ${tone}">${props.icon ? icon(props.icon) : `<span aria-hidden="true">${TONE_GLYPHS[tone]}</span>`}${esc(props.label)}</span>`;
}

function button(node: MobileViewButton, extra = ""): string {
	const style = node.style ?? "secondary";
	const disabled = node.disabled || node.busy ? " disabled" : "";
	return `<span class="button ${style}${disabled}${extra}">${node.busy ? '<span class="spin" aria-hidden="true">◌</span>' : icon(node.icon)}${esc(node.label)}${node.url ? ' <span aria-hidden="true">↗</span>' : ""}</span>`;
}

function buttons(list: MobileViewButton[] | undefined): string {
	const shown = (list ?? []).filter((b) => !b.hidden);
	return shown.length > 0 ? `<div class="buttons">${shown.map((b) => button(b)).join("")}</div>` : "";
}

const CHART_WIDTH = 358;

/** An SVG chart: series in the app's chart colours (--c1 to --c4, DESIGN.md), a dashed line for the second series and on. */
function chartSvg(chart: MobileViewChart): string {
	const spark = chart.kind === "sparkline";
	const height = spark ? 32 : 160;
	const { min, max } = chartRange(chart);
	const count = chart.series[0].values.length;
	const x = (i: number) => (count <= 1 ? CHART_WIDTH / 2 : (i / (count - 1)) * (CHART_WIDTH - 8) + 4);
	const y = (v: number) => height - 4 - ((Math.min(max, Math.max(min, v)) - min) / (max - min)) * (height - 8);
	const marks = chart.series
		.map((series, index) => {
			const colour = `var(--c${index + 1})`;
			if (chart.kind === "bar") {
				const slot = (CHART_WIDTH - 8) / count;
				const width = Math.max(2, (slot * 0.7) / chart.series.length);
				return series.values
					.map((v, i) =>
						v === null
							? ""
							: `<rect x="${(4 + i * slot + slot * 0.15 + index * width).toFixed(1)}" y="${Math.min(y(v), y(Math.max(min, 0))).toFixed(1)}" width="${width.toFixed(1)}" height="${Math.abs(y(Math.max(min, 0)) - y(v)).toFixed(1)}" rx="2" fill="${colour}"/>`,
					)
					.join("");
			}
			const segments: string[][] = [[]];
			series.values.forEach((v, i) => {
				if (v === null) segments.push([]);
				else segments[segments.length - 1].push(`${x(i).toFixed(1)},${y(v).toFixed(1)}`);
			});
			return segments
				.filter((points) => points.length > 0)
				.map((points) => {
					const line = `<polyline points="${points.join(" ")}" fill="none" stroke="${colour}" stroke-width="2"${index > 0 ? ' stroke-dasharray="6 4"' : ""}/>`;
					if (chart.kind !== "area") return line;
					const base = y(Math.max(min, 0)).toFixed(1);
					const first = points[0].split(",")[0];
					const last = points[points.length - 1].split(",")[0];
					return `<polygon points="${first},${base} ${points.join(" ")} ${last},${base}" fill="${colour}" opacity=".18"/>${line}`;
				})
				.join("");
		})
		.join("");
	const svg = `<svg class="chart-svg" viewBox="0 0 ${CHART_WIDTH} ${height}" width="100%" height="${height}" role="img" aria-label="${esc(chartSummary(chart))}">${marks}</svg>`;
	if (spark) return `<div class="chart spark">${svg}</div>`;
	const legend =
		chart.series.length > 1
			? `<div class="legend">${chart.series.map((series, index) => `<span><i style="background:var(--c${index + 1})"></i>${esc(series.name)}</span>`).join("")}</div>`
			: "";
	const labels = chart.labels?.length
		? `<div class="axis meta"><span>${esc(chart.labels[0])}</span><span>${esc(chart.labels[chart.labels.length - 1])}</span></div>`
		: "";
	// The y range: the top value over the plot, the bottom value under it.
	return `<div class="chart">${chart.title ? `<div class="card-title">${esc(chart.title)}</div>` : ""}<div class="meta">${esc(formatNumber(max, chart.unit))}</div>${svg}<div class="meta">${esc(formatNumber(min, chart.unit))}</div>${labels}${legend}</div>`;
}

function tableHtml(table: MobileViewTable): string {
	const rows = sortedRows(table);
	const head = table.columns
		.map((column) => {
			const arrow = table.sort?.column === column.id ? (table.sort.desc ? " ↓" : " ↑") : "";
			return `<th class="${column.kind ?? "text"}">${esc(column.label)}${arrow}</th>`;
		})
		.join("");
	const body =
		rows.length === 0
			? `<tr><td class="meta" colspan="${table.columns.length}">${esc(table.empty ?? "No rows")}</td></tr>`
			: rows
					.map(
						(row) =>
							`<tr${row.action ? ' class="tappable"' : ""}>${table.columns.map((column) => `<td class="${column.kind ?? "text"}">${esc(String(row.cells[column.id] ?? ""))}</td>`).join("")}</tr>`,
					)
					.join("");
	return `<div class="table">${table.filter ? '<div class="input filter"><span class="meta">⌕ Filter</span></div>' : ""}<div class="table-scroll"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div></div>`;
}

function renderNode(node: MobileViewNode): string {
	if (node.hidden) return "";
	switch (node.type) {
		case "text":
			return `<p class="text ${node.style ?? "body"}">${esc(node.text).replace(/\n/g, "<br>")}</p>`;
		case "markdown":
			return `<div class="markdown">${markdown(node.text)}</div>`;
		case "code":
			return `<div class="code">${node.title ? `<div class="code-title">${esc(node.title)}</div>` : ""}<pre>${esc(node.text)}</pre></div>`;
		case "image":
			return node.src.startsWith("data:")
				? `<img class="image" src="${esc(node.src)}" alt="${esc(node.alt ?? "")}">`
				: `<div class="image-card"><span>🖼︎</span><div><div>${esc(node.alt ?? "Image")}</div><div class="meta">${esc(node.src)} · Tap to load</div></div></div>`;
		case "divider":
			return '<hr class="divider">';
		case "notice":
			return `<div class="notice ${node.tone}"><span class="glyph" aria-hidden="true">${{ info: "ⓘ", success: "✓", warning: "⚠", danger: "⊗" }[node.tone]}</span><div>${node.title ? `<div class="notice-title">${esc(node.title)}</div>` : ""}<div>${esc(node.text)}</div></div></div>`;
		case "kv":
			return `<div class="kv">${node.items.map((item) => `<div class="kv-row"><span class="key">${esc(item.key)}</span><span class="value${item.mono ? " mono" : ""}">${esc(item.value)}${item.copy ? ' <span class="meta" aria-hidden="true">⧉</span>' : ""}</span></div>`).join("")}</div>`;
		case "progress":
			if (node.value === undefined) {
				return `<div class="working"><span class="quad" aria-hidden="true">⁂</span>${esc(node.label ?? "Working")}${node.detail ? `<span class="meta"> ${esc(node.detail)}</span>` : ""}</div>`;
			}
			return `<div class="progress"><div class="progress-head"><span>${esc(node.label ?? "")}</span><span class="meta">${Math.round(node.value * 100)}%</span></div><div class="track"><div class="fill" style="width:${Math.round(node.value * 100)}%"></div></div>${node.detail ? `<div class="meta">${esc(node.detail)}</div>` : ""}</div>`;
		case "chip":
			return chip(node);
		case "tile":
			return `<div class="tile${node.action ? " tappable" : ""}">${node.icon ? icon(node.icon) : node.tone ? `<span class="${node.tone}">${TONE_GLYPHS[node.tone]}</span>` : ""}<div class="tile-label">${esc(node.label)}</div><div class="tile-value">${esc(node.value)}</div></div>`;
		case "chart":
			return chartSvg(node);
		case "table":
			return tableHtml(node);
		case "location": {
			const coords = node.lat !== undefined && node.lon !== undefined ? `${node.lat}, ${node.lon}` : "";
			return `<div class="item location tappable"><span class="icon" aria-hidden="true">⌖</span><div class="grow"><div class="item-title">${esc(node.name)}</div>${node.address ? `<div class="meta">${esc(node.address)}</div>` : ""}${coords ? `<div class="meta mono">${coords}</div>` : ""}</div><span class="meta" aria-hidden="true">↗</span></div>`;
		}
		case "button":
			return `<div class="buttons">${button(node, " wide")}</div>`;
		case "card":
			return `<div class="card">${node.title || node.status ? `<div class="card-head">${icon(node.icon)}<div class="grow"><div class="card-title">${esc(node.title ?? "")}</div>${node.subtitle ? `<div class="meta">${esc(node.subtitle)}</div>` : ""}</div>${node.status ? chip(node.status) : ""}</div>` : ""}${node.children.map(renderNode).join("")}${buttons(node.actions)}</div>`;
		case "section":
			return `<div class="section">${node.title ? `<div class="section-title">${esc(node.title)}</div>` : ""}${node.children.map(renderNode).join("")}</div>`;
		case "list": {
			const rows = node.children.filter((c) => !c.hidden);
			if (rows.length === 0) return `<div class="list empty">${esc(node.empty ?? "Nothing here")}</div>`;
			return `<div class="list">${rows.map(renderNode).join("")}</div>`;
		}
		case "item":
			return `<div class="item${node.action ? " tappable" : ""}">${icon(node.icon)}<div class="grow"><div class="item-title">${esc(node.title)}</div>${node.subtitle ? `<div class="meta">${esc(node.subtitle)}</div>` : ""}${node.progress !== undefined ? `<div class="track small"><div class="fill" style="width:${Math.round(node.progress * 100)}%"></div></div>` : ""}</div>${node.value ? `<span class="meta">${esc(node.value)}</span>` : ""}${node.chip ? chip(node.chip) : ""}${node.chevron ? '<span class="meta" aria-hidden="true">›</span>' : ""}</div>`;
		case "row":
			return `<div class="row">${node.children
				.filter((c) => !c.hidden)
				.map((c) => (c.type === "button" ? button(c, " small") : renderNode(c)))
				.join("")}</div>`;
		case "grid":
			return `<div class="grid cols-${node.columns ?? 2}">${node.children.map(renderNode).join("")}</div>`;
		case "form":
			return `<div class="form">${node.children.map(renderNode).join("")}<div class="buttons">${button({ type: "button", label: node.submit.label, style: "primary", busy: node.busy, disabled: node.disabled }, " wide")}</div></div>`;
		case "input":
			return `<label class="field"><span class="field-label">${esc(node.label)}${node.required ? " *" : ""}</span><span class="input${node.error ? " error" : ""}${node.kind === "multiline" ? " multiline" : ""}">${node.value ? esc(node.kind === "password" ? "••••••" : node.value) : `<span class="meta">${esc(node.placeholder ?? "")}</span>`}</span>${node.error ? `<span class="hint danger">${esc(node.error)}</span>` : node.hint ? `<span class="hint">${esc(node.hint)}</span>` : ""}</label>`;
		case "toggle":
			return `<div class="switch-row"><div class="grow"><div>${esc(node.label)}</div>${node.hint ? `<div class="meta">${esc(node.hint)}</div>` : ""}</div><span class="switch${node.value ? " on" : ""}"><span></span></span></div>`;
		case "select": {
			const values = optionValues(node.options);
			const labels = node.options.map((o) => (typeof o === "string" ? o : o.label));
			const current = node.value ?? values[0];
			if (values.length <= 3) {
				return `<div class="field"><span class="field-label">${esc(node.label)}</span><div class="segmented">${labels.map((label, i) => `<span class="${values[i] === current ? "on" : ""}">${esc(label)}</span>`).join("")}</div></div>`;
			}
			return `<div class="item tappable"><div class="grow">${esc(node.label)}</div><span class="meta">${esc(labels[values.indexOf(current)] ?? "")} ›</span></div>`;
		}
		case "slider": {
			const at = ((node.value - node.min) / (node.max - node.min)) * 100;
			return `<div class="field"><div class="progress-head"><span class="field-label">${esc(node.label)}</span><span class="meta">${node.value}${node.unit ? ` ${esc(node.unit)}` : ""}</span></div><div class="track slider"><div class="fill" style="width:${at}%"></div><span class="thumb" style="left:${at}%"></span></div></div>`;
		}
	}
}

function renderView(view: MobileView): string {
	return `<section class="sheet">
<div class="handle"></div>
${view.title ? `<div class="sheet-head">${icon(view.icon)}<div class="grow"><div class="sheet-title">${esc(view.title)}</div>${view.subtitle ? `<div class="meta">${esc(view.subtitle)}</div>` : ""}</div><span class="close" aria-hidden="true">✕</span></div>` : ""}
<div class="body">${view.body.map(renderNode).join("\n")}</div>
${buttons(view.actions)}
</section>`;
}

const CSS = `
:root { color-scheme: dark light; }
* { box-sizing: border-box; }
body { margin: 0; padding: 24px 16px; font: 15px/22px Inter, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; background: #111; color: #ECEBE8; }
h1 { font-size: 14px; font-weight: 500; color: #9C9A94; margin: 0 0 16px; }
.columns { display: flex; flex-wrap: wrap; gap: 24px; }
.phone { width: 390px; max-width: 100%; border-radius: 28px; overflow: hidden; padding: 12px 0 16px; }
.dark { --c1:#56B4E9; --c2:#E69F00; --c3:#CC79A7; --c4:#009E73; --canvas:#1C1B19; --s1:#232220; --s2:#2A2926; --s3:#32312E; --ink1:#ECEBE8; --ink2:#B3B1AB; --ink3:#9C9A94; --ink4:#5E5C57; --line:rgba(255,255,255,.07); --lineStrong:rgba(255,255,255,.12); --accent:#ECEBE8; --onAccent:#1C1B19; --success:#4CC38A; --warning:#D4A64A; --danger:#F4817D; }
.light { --c1:#005E93; --c2:#665A05; --c3:#964966; --c4:#089656; --canvas:#FFFFFF; --s1:#F7F7F7; --s2:#F0F0F0; --s3:#E8E8E8; --ink1:#171717; --ink2:#525252; --ink3:#666666; --ink4:#949494; --line:rgba(0,0,0,.08); --lineStrong:rgba(0,0,0,.14); --accent:#0A0A0A; --onAccent:#FFFFFF; --success:#11733F; --warning:#8A6410; --danger:#B83430; }
.phone { background: var(--canvas); color: var(--ink1); }
.label { font-size: 12.5px; color: var(--ink3); padding: 0 20px 8px; }
.sheet { background: var(--s1); border: 1px solid var(--lineStrong); border-radius: 28px 28px 0 0; padding: 10px 0 20px; margin: 0 0 0; }
.handle { width: 36px; height: 5px; border-radius: 3px; background: var(--ink4); margin: 0 auto 14px; }
.sheet-head { display: flex; gap: 12px; align-items: center; padding: 0 20px 12px; }
.sheet-title { font-size: 28px; line-height: 34px; font-weight: 600; letter-spacing: -.01em; }
.close { width: 36px; height: 36px; border-radius: 50%; display: grid; place-items: center; background: var(--s2); border: 1px solid var(--lineStrong); color: var(--ink1); font-size: 14px; }
.body { padding: 0 16px; display: flex; flex-direction: column; gap: 12px; }
.grow { flex: 1; min-width: 0; }
.meta { font-size: 12.5px; line-height: 17px; color: var(--ink3); }
.icon { display: inline-grid; place-items: center; width: 20px; color: var(--ink2); margin-right: 4px; }
.text { margin: 0 4px; } .text.title { font-size: 17px; font-weight: 600; } .text.meta { font-size: 12.5px; color: var(--ink3); } .text.mono { font-family: "JetBrains Mono", ui-monospace, monospace; font-size: 13.5px; }
.markdown { margin: 0 4px; } .markdown p { margin: 0 0 8px; } .markdown a { color: var(--accent); }
.markdown code, .mono { font-family: "JetBrains Mono", ui-monospace, monospace; font-size: 13px; }
.markdown code { background: var(--s3); border-radius: 4px; padding: 1px 4px; }
.code { background: var(--s1); border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
.code-title { font-size: 12.5px; color: var(--ink3); padding: 8px 12px 0; }
.code pre { margin: 0; padding: 10px 12px; font: 12.5px/19px "JetBrains Mono", ui-monospace, monospace; overflow-x: auto; }
.image { max-width: 100%; border-radius: 12px; border: 1px solid var(--line); }
.image-card { display: flex; gap: 12px; align-items: center; padding: 12px; border-radius: 12px; border: 1px solid var(--line); background: var(--s1); }
.divider { border: 0; border-top: 1px solid var(--line); margin: 4px 0; }
.notice { display: flex; gap: 12px; padding: 12px 14px; border-radius: 10px; border: 1px solid var(--line); background: var(--s1); }
.notice .glyph { width: 20px; } .notice.info .glyph { color: var(--ink2); } .notice.success .glyph { color: var(--success); }
.notice.warning { background: color-mix(in srgb, var(--warning) 12%, transparent); border-color: color-mix(in srgb, var(--warning) 40%, transparent); } .notice.warning .glyph { color: var(--warning); }
.notice.danger { background: color-mix(in srgb, var(--danger) 12%, transparent); border-color: color-mix(in srgb, var(--danger) 40%, transparent); } .notice.danger .glyph { color: var(--danger); }
.notice-title { font-weight: 600; } .notice-title + div { color: var(--ink2); }
.kv-row { display: flex; justify-content: space-between; gap: 12px; min-height: 48px; align-items: center; padding: 0 4px; border-bottom: 1px solid var(--line); }
.kv-row:last-child { border-bottom: 0; } .key { color: var(--ink2); } .value { text-align: end; word-break: break-all; }
.progress-head { display: flex; justify-content: space-between; margin-bottom: 6px; }
.track { height: 2px; background: var(--s3); border-radius: 1px; position: relative; } .track.small { margin-top: 6px; }
.fill { height: 100%; background: var(--accent); border-radius: 1px; }
.track.slider { height: 4px; margin: 10px 0; } .thumb { position: absolute; top: -8px; width: 20px; height: 20px; margin-left: -10px; border-radius: 50%; background: var(--accent); }
.working { display: flex; gap: 8px; align-items: center; color: var(--ink2); } .quad { color: var(--ink1); }
.chip { display: inline-flex; gap: 6px; align-items: center; height: 28px; padding: 0 10px; border-radius: 6px; font-size: 14px; font-weight: 500; background: var(--s3); color: var(--ink1); white-space: nowrap; }
.chip.success span:first-child { color: var(--success); } .chip.warning span:first-child { color: var(--warning); } .chip.danger span:first-child { color: var(--danger); } .chip.running span:first-child { color: var(--ink1); }
.success { color: var(--success); } .warning { color: var(--warning); } .danger { color: var(--danger); }
.row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.grid { display: grid; gap: 12px; } .cols-2 { grid-template-columns: 1fr 1fr; } .cols-3 { grid-template-columns: repeat(3, 1fr); } .cols-4 { grid-template-columns: repeat(4, 1fr); }
.tile { min-height: 104px; border-radius: 16px; background: var(--s1); border: 1px solid var(--line); padding: 14px; display: flex; flex-direction: column; justify-content: space-between; }
.tile-label { font-size: 15px; } .tile-value { font-size: 15px; color: var(--ink3); font-variant-numeric: tabular-nums; }
.card { background: var(--s1); border: 1px solid var(--line); border-radius: 10px; padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.card-head { display: flex; gap: 10px; align-items: center; } .card-title { font-size: 17px; font-weight: 600; }
.section { display: flex; flex-direction: column; gap: 8px; } .section-title { font-size: 12.5px; font-weight: 500; color: var(--ink3); padding: 0 4px; }
.list { background: var(--s1); border: 1px solid var(--line); border-radius: 16px; overflow: hidden; } .list.empty { padding: 16px; color: var(--ink3); }
.card .list { background: transparent; border: 0; border-radius: 0; margin: 0 -16px; }
.item { display: flex; gap: 12px; align-items: center; min-height: 56px; padding: 10px 16px; border-bottom: 1px solid var(--line); }
.item:last-child { border-bottom: 0; } .item-title { font-size: 17px; line-height: 24px; }
.buttons { display: flex; gap: 12px; padding: 12px 16px 0; } .card .buttons, .form .buttons, .body .buttons { padding: 0; }
.sheet > .buttons { padding: 16px 20px 0; }
.button { flex: 1; display: inline-flex; justify-content: center; align-items: center; gap: 6px; min-height: 48px; padding: 0 16px; border-radius: 28px; font-size: 14px; font-weight: 500; text-align: center; }
.button.small { flex: 0 0 auto; min-height: 36px; padding: 0 12px; }
.button.primary { background: var(--accent); color: var(--onAccent); }
.button.secondary { background: var(--s2); border: 1px solid var(--lineStrong); color: var(--ink1); }
.button.ghost { color: var(--ink2); }
.button.danger { background: color-mix(in srgb, var(--danger) 12%, transparent); color: var(--danger); }
.button.disabled { opacity: .4; }
.form { display: flex; flex-direction: column; gap: 14px; }
.field { display: flex; flex-direction: column; gap: 6px; } .field-label { font-size: 12.5px; font-weight: 500; color: var(--ink2); }
.input { min-height: 48px; display: flex; align-items: center; padding: 0 14px; border-radius: 10px; background: var(--s1); border: 1px solid var(--lineStrong); }
.input.multiline { min-height: 96px; align-items: flex-start; padding-top: 12px; } .input.error { border-color: var(--danger); }
.hint { font-size: 12.5px; color: var(--ink3); } .hint.danger { color: var(--danger); }
.switch-row { display: flex; align-items: center; gap: 12px; min-height: 56px; padding: 0 4px; }
.switch { width: 52px; height: 32px; border-radius: 16px; background: var(--s3); border: 1px solid var(--lineStrong); position: relative; flex: none; }
.switch span { position: absolute; top: 7px; left: 7px; width: 16px; height: 16px; border-radius: 50%; background: var(--ink2); }
.switch.on { background: var(--accent); border-color: var(--accent); } .switch.on span { left: 25px; top: 4px; width: 24px; height: 24px; background: var(--onAccent); }
.segmented { display: flex; padding: 2px; border-radius: 10px; background: var(--s1); border: 1px solid var(--line); }
.segmented span { flex: 1; text-align: center; min-height: 44px; display: grid; place-items: center; border-radius: 8px; color: var(--ink3); font-size: 14px; font-weight: 500; }
.segmented span.on { background: var(--s3); border: 1px solid var(--lineStrong); color: var(--ink1); }
.tappable { cursor: pointer; }
.home-tile { margin: 0 16px; width: 180px; min-height: 120px; border-radius: 16px; background: var(--s1); border: 1px solid var(--line); padding: 14px; display: flex; flex-direction: column; gap: 8px; font-size: 14px; }
.home-tile .tile-title { font-size: 15px; font-weight: 500; } .home-tile .kv-row { min-height: 28px; }
.chart { display: flex; flex-direction: column; gap: 6px; padding: 0 4px; } .chart.spark { padding: 0; }
.chart-svg { display: block; } .axis { display: flex; justify-content: space-between; }
.legend { display: flex; flex-wrap: wrap; gap: 12px; font-size: 12.5px; color: var(--ink2); } .legend i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 6px; }
.table { display: flex; flex-direction: column; gap: 8px; } .table-scroll { overflow: auto; max-height: 540px; border: 1px solid var(--line); border-radius: 12px; background: var(--s1); }
.table table { border-collapse: collapse; width: 100%; font-size: 14px; } .table th { position: sticky; top: 0; background: var(--s2); color: var(--ink2); font-weight: 500; text-align: start; }
.table th, .table td { padding: 10px 12px; border-bottom: 1px solid var(--line); white-space: nowrap; } .table .number { text-align: end; font-variant-numeric: tabular-nums; } .table td.mono { font-family: "JetBrains Mono", ui-monospace, monospace; font-size: 13px; }
.location { background: var(--s1); border: 1px solid var(--line); border-radius: 12px; }
`;

/** A Home tile: a small card with the title and the body, the whole card one tap target. */
function renderTile(view: MobileView): string {
	return `<div class="home-tile">${view.title ? `<div class="card-head">${icon(view.icon)}<div class="grow"><div class="tile-title">${esc(view.title)}</div>${view.subtitle ? `<div class="meta">${esc(view.subtitle)}</div>` : ""}</div></div>` : ""}${view.body.map(renderNode).join("")}</div>`;
}

/** A standalone HTML page that draws each view (or Home tile) dark and light. */
export function viewsToHtml(views: Array<{ name: string; view: MobileView; tile?: boolean }>): string {
	const sections = views
		.map(({ name, view, tile }) => {
			const draw = tile ? renderTile : renderView;
			return `<h1>${esc(name)}</h1>
<div class="columns">
<div class="phone dark"><div class="label">Pi-Bolt, dark${tile ? " · Home tile" : ""}</div>${draw(view)}</div>
<div class="phone light"><div class="label">Paper, light${tile ? " · Home tile" : ""}</div>${draw(view)}</div>
</div>`;
		})
		.join('\n<div style="height:32px"></div>\n');
	return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'">
<title>Mobile UI preview</title>
<style>${CSS}</style>
</head>
<body>
${sections}
</body>
</html>
`;
}
