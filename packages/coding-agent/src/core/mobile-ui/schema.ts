/**
 * The JSON Schema of a mobile UI view (kit version 1), for editors and authors: `schemas/mobile-ui.schema.json`
 * (scripts/generate-schemas.ts). It describes the shapes; validate.ts is what Pi runs, and also checks what a schema
 * cannot say well: unique ids, ids on nodes that send events, value fits, link and image rules, sizes.
 */

import type { TSchema } from "typebox";
import { MOBILE_VIEW_LIMITS as LIMITS, MOBILE_VIEW_ICONS } from "./types.ts";

type Json = Record<string, unknown>;

const ref = (name: string): Json => ({ $ref: `#/$defs/${name}` });
const str = (maxLength: number = LIMITS.short, description?: string): Json => ({
	type: "string",
	maxLength,
	...(description ? { description } : {}),
});
const bool = (description?: string): Json => ({ type: "boolean", ...(description ? { description } : {}) });
const list = (items: Json, maxItems: number = LIMITS.children): Json => ({ type: "array", items, maxItems });
const anyOf = (...names: string[]): Json => ({ anyOf: names.map(ref) });

function node(type: string, description: string, properties: Json, required: string[] = []): Json {
	return {
		type: "object",
		description,
		properties: {
			type: { const: type },
			id: ref("id"),
			hidden: bool("Not shown while true."),
			...properties,
		},
		required: ["type", ...required],
		additionalProperties: false,
	};
}

const FIELDS = ["input", "toggle", "select", "slider"];

const defs: Record<string, Json> = {
	id: { type: "string", pattern: "^[A-Za-z0-9_.:-]{1,64}$", description: "Unique within the view." },
	icon: { enum: [...MOBILE_VIEW_ICONS] },
	tone: { enum: ["neutral", "success", "warning", "danger", "running"] },
	chipProps: {
		type: "object",
		properties: { label: str(), tone: ref("tone"), icon: ref("icon") },
		required: ["label"],
		additionalProperties: false,
	},
	link: { type: "string", pattern: "^(https?|mailto):", maxLength: 2048 },
	node: anyOf(
		"text",
		"markdown",
		"code",
		"image",
		"divider",
		"notice",
		"kv",
		"progress",
		"chip",
		"tile",
		"chart",
		"table",
		"location",
		"button",
		"card",
		"section",
		"list",
		"item",
		"row",
		"grid",
		"form",
		...FIELDS,
	),
	text: node("text", "Plain text.", { text: str(LIMITS.text), style: { enum: ["body", "title", "meta", "mono"] } }, [
		"text",
	]),
	markdown: node("markdown", "Markdown, as the transcript renders it.", { text: str(LIMITS.markdown) }, ["text"]),
	code: node("code", "A code block.", { text: str(LIMITS.code), language: str(40), title: str() }, ["text"]),
	image: node(
		"image",
		"A data: image, or an https: image on a public host that loads on a tap.",
		{
			src: { type: "string", pattern: "^(data:image/(png|jpeg|webp|gif);base64,|https://)" },
			alt: str(),
			decorative: bool("No alt text needed: the image adds nothing to read."),
			aspect: { type: "number", minimum: 0.25, maximum: 4, description: "Width / height." },
		},
		["src"],
	),
	divider: node("divider", "A line.", {}),
	notice: node(
		"notice",
		"A notice with a glyph in its tone's colour.",
		{ tone: { enum: ["info", "success", "warning", "danger"] }, title: str(), text: str(LIMITS.text) },
		["tone", "text"],
	),
	kv: node(
		"kv",
		"Key and value rows.",
		{
			items: list(
				{
					type: "object",
					properties: { key: str(), value: str(), mono: bool(), copy: bool("Adds a copy action.") },
					required: ["key", "value"],
					additionalProperties: false,
				},
				LIMITS.kvItems,
			),
		},
		["items"],
	),
	progress: node("progress", "Progress: determinate with value, indeterminate without.", {
		value: { type: "number", minimum: 0, maximum: 1 },
		label: str(),
		detail: str(),
	}),
	chip: node("chip", "A status chip.", { label: str(), tone: ref("tone"), icon: ref("icon") }, ["label"]),
	tile: node(
		"tile",
		"A status tile; tappable with action. Put tiles in a grid.",
		{ label: str(), value: str(), icon: ref("icon"), tone: ref("tone"), action: ref("id") },
		["label", "value"],
	),
	chart: node(
		"chart",
		"A line, bar or area chart, or a sparkline, in the app's chart colours. Every series has as many values as labels.",
		{
			kind: { enum: ["line", "bar", "area", "sparkline"] },
			series: list(
				{
					type: "object",
					properties: {
						name: str(),
						values: list(
							{
								anyOf: [
									{ type: "number", minimum: -LIMITS.chartMagnitude, maximum: LIMITS.chartMagnitude },
									{ type: "null" },
								],
							},
							LIMITS.chartValues,
						),
					},
					required: ["name", "values"],
					additionalProperties: false,
				},
				LIMITS.chartSeries,
			),
			labels: list(str(), LIMITS.chartValues),
			unit: str(20),
			title: str(),
			min: { type: "number", minimum: -LIMITS.chartMagnitude, maximum: LIMITS.chartMagnitude },
			max: { type: "number", minimum: -LIMITS.chartMagnitude, maximum: LIMITS.chartMagnitude },
			summary: str(LIMITS.text, "What screen readers say; derived from the data when absent."),
		},
		["kind", "series"],
	),
	table: node(
		"table",
		"Rows under typed columns; sorting and filtering happen in the app. A row with action sends a tap with its id as value.",
		{
			columns: list(
				{
					type: "object",
					properties: {
						id: ref("id"),
						label: str(),
						kind: { enum: ["text", "number", "date", "mono"] },
						sortable: bool(),
					},
					required: ["id", "label"],
					additionalProperties: false,
				},
				LIMITS.tableColumns,
			),
			rows: list(
				{
					type: "object",
					properties: {
						id: ref("id"),
						cells: {
							type: "object",
							additionalProperties: { anyOf: [str(), { type: "number" }, { type: "null" }] },
						},
						action: ref("id"),
					},
					required: ["id", "cells"],
					additionalProperties: false,
				},
				LIMITS.tableRows,
			),
			sort: {
				type: "object",
				properties: { column: str(64), desc: bool() },
				required: ["column"],
				additionalProperties: false,
			},
			filter: bool("A search box over the rows."),
			empty: str(),
		},
		["columns", "rows"],
	),
	location: node(
		"location",
		"A place; a tap opens the phone's maps app. Needs lat and lon together, or address.",
		{
			name: str(),
			address: str(),
			lat: { type: "number", minimum: -90, maximum: 90 },
			lon: { type: "number", minimum: -180, maximum: 180 },
		},
		["name"],
	),
	button: node(
		"button",
		"A button: sends a tap event, or opens url.",
		{
			label: str(),
			style: { enum: ["primary", "secondary", "ghost", "danger"] },
			icon: ref("icon"),
			action: ref("id"),
			disabled: bool(),
			busy: bool("Shows a spinner and takes no taps."),
			url: ref("link"),
			confirm: {
				type: "object",
				properties: { title: str(), message: str(LIMITS.text), label: str() },
				required: ["title"],
				additionalProperties: false,
			},
		},
		["label"],
	),
	card: node(
		"card",
		"A card.",
		{
			title: str(),
			subtitle: str(),
			icon: ref("icon"),
			status: ref("chipProps"),
			children: list(ref("node")),
			actions: list(ref("button"), LIMITS.actions),
		},
		["children"],
	),
	section: node("section", "A titled group without a box.", { title: str(), children: list(ref("node")) }, [
		"children",
	]),
	list: node("list", "Rows.", { children: list(ref("item")), empty: str() }, ["children"]),
	item: node(
		"item",
		"A list row; tappable with action.",
		{
			title: str(),
			subtitle: str(),
			icon: ref("icon"),
			value: str(),
			chip: ref("chipProps"),
			progress: { type: "number", minimum: 0, maximum: 1 },
			action: ref("id"),
			chevron: bool(),
		},
		["title"],
	),
	row: node(
		"row",
		"Chips, buttons and meta text side by side, wrapping.",
		{ children: list(anyOf("chip", "button", "text")) },
		["children"],
	),
	grid: node("grid", "Tiles in columns.", { columns: { enum: [2, 3, 4] }, children: list(ref("tile")) }, ["children"]),
	form: node(
		"form",
		"Fields with a submit button.",
		{
			children: list(anyOf(...FIELDS, "text", "notice", "divider")),
			submit: {
				type: "object",
				properties: { label: str(), action: ref("id") },
				required: ["label"],
				additionalProperties: false,
			},
			disabled: bool(),
			busy: bool(),
		},
		["children", "submit"],
	),
	input: node(
		"input",
		"A text field.",
		{
			label: str(),
			value: str(LIMITS.inputMaxLength),
			placeholder: str(),
			hint: str(LIMITS.text),
			error: str(LIMITS.text),
			kind: { enum: ["text", "multiline", "number", "email", "url", "password"] },
			required: bool(),
			maxLength: { type: "integer", minimum: 1, maximum: LIMITS.inputMaxLength },
		},
		["label"],
	),
	toggle: node("toggle", "A switch.", { label: str(), value: bool(), hint: str(LIMITS.text) }, ["label", "value"]),
	select: node(
		"select",
		"One of a few options.",
		{
			label: str(),
			options: {
				type: "array",
				minItems: 1,
				maxItems: LIMITS.options,
				items: {
					anyOf: [
						str(),
						{
							type: "object",
							properties: { value: str(), label: str() },
							required: ["value", "label"],
							additionalProperties: false,
						},
					],
				},
			},
			value: str(),
		},
		["label", "options"],
	),
	slider: node(
		"slider",
		"A number in a range.",
		{
			label: str(),
			min: { type: "number" },
			max: { type: "number" },
			step: { type: "number", exclusiveMinimum: 0 },
			value: { type: "number" },
			unit: str(20),
		},
		["label", "min", "max", "value"],
	),
};

/** The view schema, `$defs` included. */
export const MOBILE_VIEW_SCHEMA = {
	title: "Pi mobile UI view",
	description: "A view of the mobile UI kit, version 1. See docs/mobile-ui.md.",
	type: "object",
	properties: {
		v: { const: 1, description: "Kit version the view needs." },
		title: str(),
		subtitle: str(),
		icon: ref("icon"),
		body: list(ref("node")),
		actions: list(ref("button"), LIMITS.actions),
		fallback: {
			...list(str(LIMITS.text), LIMITS.fallbackLines),
			description: "Text lines instead of the derived text form.",
		},
	},
	required: ["v", "body"],
	additionalProperties: false,
	$defs: defs,
} as unknown as TSchema;
