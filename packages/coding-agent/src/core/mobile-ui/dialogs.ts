/**
 * A sheet (`showView`) as a sequence of the dialogs every mode has, for the terminal and clients without the kit
 * (docs/mobile-ui.md#fallback): the action to take when there is a choice, then the chosen form's fields.
 */

import type { ExtensionUIContext, ExtensionUIDialogOptions } from "../extensions/types.ts";
import { cleanText, viewToLines } from "./text.ts";
import type {
	MobileView,
	MobileViewButton,
	MobileViewField,
	MobileViewForm,
	MobileViewNode,
	MobileViewResult,
	MobileViewValue,
} from "./types.ts";
import { isFieldType, optionValues } from "./validate.ts";

export type ViewDialogs = Pick<ExtensionUIContext, "select" | "confirm" | "input">;

const SUMMARY_LINES = 12;
/** A required or malformed answer is asked again this many times before the sheet counts as cancelled. */
const ATTEMPTS = 3;

/**
 * The current value of every shown field of `view` (hidden nodes and their insides left out), by field id. Password
 * fields included: the result goes to the extension.
 */
export function fieldValues(view: MobileView): Record<string, MobileViewValue> {
	const values: Record<string, MobileViewValue> = {};
	const walk = (nodes: readonly MobileViewNode[]) => {
		for (const node of nodes) {
			if (node.hidden) continue;
			if (node.id && isFieldType(node.type)) {
				const field = node as MobileViewField;
				if (field.type === "input") values[node.id] = field.value ?? "";
				else if (field.type === "select") values[node.id] = field.value ?? optionValues(field.options)[0] ?? "";
				else values[node.id] = field.value;
			}
			const children = (node as { children?: MobileViewNode[] }).children;
			if (Array.isArray(children)) walk(children);
		}
	};
	walk(view.body);
	return values;
}

function closers(view: MobileView): Array<MobileViewForm | MobileViewButton> {
	const found: Array<MobileViewForm | MobileViewButton> = [];
	// Forms that are shown: hidden nodes and everything inside them are left out, as the app leaves them out.
	const walk = (nodes: readonly MobileViewNode[]) => {
		for (const node of nodes) {
			if (node.hidden) continue;
			if (node.type === "form") {
				if (!node.disabled && !node.busy) found.push(node);
				continue;
			}
			const children = (node as { children?: MobileViewNode[] }).children;
			if (Array.isArray(children)) walk(children);
		}
	};
	walk(view.body);
	for (const action of view.actions ?? []) {
		if (!action.hidden && !action.disabled && !action.busy && action.url === undefined) found.push(action);
	}
	return found;
}

function closerLabel(closer: MobileViewForm | MobileViewButton): string {
	return cleanText(closer.type === "form" ? closer.submit.label : closer.label);
}

/** [labels] with repeats numbered ("US", "US (2)"), so a dialog's answer names one entry. */
function uniqueLabels(labels: string[]): string[] {
	return labels.map((label, index) => (labels.indexOf(label) < index ? `${label} (${index + 1})` : label));
}

/** The shown fields of `nodes` outside forms (hidden nodes and their insides left out). */
function standaloneFields(nodes: readonly MobileViewNode[]): MobileViewField[] {
	const found: MobileViewField[] = [];
	const walk = (list: readonly MobileViewNode[]) => {
		for (const node of list) {
			if (node.hidden || node.type === "form") continue;
			if (node.id && isFieldType(node.type)) found.push(node as MobileViewField);
			const children = (node as { children?: MobileViewNode[] }).children;
			if (Array.isArray(children)) walk(children);
		}
	};
	walk(nodes);
	return found;
}

async function askField(
	dialogs: ViewDialogs,
	field: MobileViewField,
	opts: ExtensionUIDialogOptions,
): Promise<{ value: MobileViewValue } | undefined> {
	const label = cleanText(field.label);
	switch (field.type) {
		case "toggle": {
			const choice = await dialogs.select(label, field.value ? ["On", "Off"] : ["Off", "On"], opts);
			return choice === undefined ? undefined : { value: choice === "On" };
		}
		case "select": {
			const values = optionValues(field.options);
			// Options can share a label: each choice gets a text of its own, so the pick maps back to its value.
			const labels = uniqueLabels(
				field.options.map((option) => cleanText(typeof option === "string" ? option : option.label)),
			);
			const choice = await dialogs.select(label, labels, opts);
			const index = choice === undefined ? -1 : labels.indexOf(choice);
			return index < 0 ? undefined : { value: values[index] };
		}
		case "slider": {
			const range = `${label} (${field.min} to ${field.max})`;
			for (let attempt = 0; attempt < ATTEMPTS; attempt++) {
				const text = await dialogs.input(range, String(field.value), opts);
				if (text === undefined) return undefined;
				if (text.trim() === "") return { value: field.value };
				const value = Number(text);
				if (Number.isFinite(value) && value >= field.min && value <= field.max) return { value };
			}
			return undefined;
		}
		case "input": {
			const max = field.maxLength ?? 2000;
			const title = field.required ? `${label} (required)` : label;
			const placeholder = field.kind === "password" ? field.placeholder : (field.value ?? field.placeholder);
			for (let attempt = 0; attempt < ATTEMPTS; attempt++) {
				const text = await dialogs.input(title, placeholder ? cleanText(placeholder) : undefined, opts);
				if (text === undefined) return undefined;
				// Enter on an empty field keeps what it held.
				const value = text === "" ? (field.value ?? "") : text;
				if (field.required && value.trim() === "") continue;
				if (field.kind === "number" && value !== "" && !Number.isFinite(Number(value))) continue;
				if (value.length <= max) return { value };
			}
			return undefined;
		}
	}
}

/**
 * Runs `view` as dialogs: picks the action (a form's submit or an `actions` button) when there is more than one, asks
 * the chosen form's fields, and confirms a button that has `confirm`. Undefined when any dialog is cancelled, times out
 * or `signal` aborts.
 */
export async function runViewDialogs(
	view: MobileView,
	dialogs: ViewDialogs,
	signal?: AbortSignal,
): Promise<MobileViewResult | undefined> {
	const opts: ExtensionUIDialogOptions = signal ? { signal } : {};
	const values = fieldValues(view);
	const choices = closers(view);
	// What the sheet says besides its choices: the forms are asked field by field, the actions are the options.
	const content = view.body.filter((node) => node.type !== "form");
	const summary = viewToLines({ ...view, body: content, actions: undefined })
		.slice(0, SUMMARY_LINES)
		.join("\n");
	const title = summary || cleanText(view.title ?? "");
	if (choices.length === 0) {
		await dialogs.select(title, ["Close"], opts);
		return undefined;
	}
	let chosen = choices[0];
	if (choices.length > 1 || chosen.type === "button") {
		const labels = uniqueLabels(choices.map(closerLabel));
		const picked = await dialogs.select(title, labels, opts);
		const index = picked === undefined ? -1 : labels.indexOf(picked);
		if (index < 0) return undefined;
		chosen = choices[index];
	}
	if (signal?.aborted || !chosen.id) return undefined;
	// The sheet's fields outside its forms go with any answer, as the app sends them: ask them first.
	for (const field of standaloneFields(view.body)) {
		const answer = await askField(dialogs, field, opts);
		if (!answer || signal?.aborted || !field.id) return undefined;
		values[field.id] = answer.value;
	}
	if (chosen.type === "button") {
		if (chosen.confirm) {
			const ok = await dialogs.confirm(
				cleanText(chosen.confirm.title),
				cleanText(chosen.confirm.message ?? ""),
				opts,
			);
			if (!ok) return undefined;
		}
		return { id: chosen.id, action: chosen.action ?? chosen.id, values };
	}
	for (const child of chosen.children as MobileViewNode[]) {
		if (!isFieldType(child.type) || child.hidden || !child.id) continue;
		const answer = await askField(dialogs, child as MobileViewField, opts);
		if (!answer || signal?.aborted) return undefined;
		values[child.id] = answer.value;
	}
	return { id: chosen.id, action: chosen.submit.action ?? chosen.id, values };
}
