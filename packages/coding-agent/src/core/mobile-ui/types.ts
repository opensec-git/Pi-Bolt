/**
 * The mobile UI kit (docs/mobile-ui.md): a view is a JSON tree of components that a client draws with its own design
 * language. These are its types; validate.ts checks values against them and the limits below.
 */

export const MOBILE_VIEW_VERSION = 1;

/** The `set_client_features` name of this kit version. */
export const VIEWS_FEATURE = "views";

export const MOBILE_VIEW_ICONS = [
	"add",
	"alert",
	"archive",
	"bell",
	"bolt",
	"book",
	"branch",
	"bug",
	"build",
	"calendar",
	"chart",
	"check",
	"clock",
	"close",
	"cloud",
	"code",
	"copy",
	"database",
	"delete",
	"download",
	"edit",
	"error",
	"file",
	"filter",
	"flag",
	"folder",
	"globe",
	"heart",
	"home",
	"info",
	"key",
	"link",
	"lock",
	"mail",
	"package",
	"pause",
	"person",
	"play",
	"refresh",
	"rocket",
	"search",
	"send",
	"server",
	"settings",
	"shield",
	"star",
	"stop",
	"sync",
	"terminal",
	"upload",
	"warning",
] as const;

export type MobileViewIcon = (typeof MOBILE_VIEW_ICONS)[number];

export type MobileViewTone = "neutral" | "success" | "warning" | "danger" | "running";
export type MobileViewNoticeTone = "info" | "success" | "warning" | "danger";
export type MobileViewSlot = "panel" | "inline" | "page" | "tile";
/** A field's value: text and select fields hold strings, toggles booleans, sliders numbers. */
export type MobileViewValue = string | boolean | number;

interface NodeBase {
	/** Unique within the view; needed to update the node or to get its events. */
	id?: string;
	hidden?: boolean;
}

export interface MobileViewText extends NodeBase {
	type: "text";
	text: string;
	style?: "body" | "title" | "meta" | "mono";
}

export interface MobileViewMarkdown extends NodeBase {
	type: "markdown";
	text: string;
}

export interface MobileViewCode extends NodeBase {
	type: "code";
	text: string;
	language?: string;
	title?: string;
}

export interface MobileViewImage extends NodeBase {
	type: "image";
	/** `data:image/(png|jpeg|webp|gif);base64,...` or an `https:` URL on a public host (loaded on a tap). */
	src: string;
	alt?: string;
	decorative?: boolean;
	/** Width / height. */
	aspect?: number;
}

export interface MobileViewDivider extends NodeBase {
	type: "divider";
}

export interface MobileViewNotice extends NodeBase {
	type: "notice";
	tone: MobileViewNoticeTone;
	title?: string;
	text: string;
}

export interface MobileViewKvItem {
	key: string;
	value: string;
	mono?: boolean;
	copy?: boolean;
}

export interface MobileViewKv extends NodeBase {
	type: "kv";
	items: MobileViewKvItem[];
}

export interface MobileViewProgress extends NodeBase {
	type: "progress";
	/** 0 to 1; absent: indeterminate. */
	value?: number;
	label?: string;
	detail?: string;
}

export interface MobileViewChipProps {
	label: string;
	tone?: MobileViewTone;
	icon?: MobileViewIcon;
}

export interface MobileViewChip extends NodeBase, MobileViewChipProps {
	type: "chip";
}

export interface MobileViewTile extends NodeBase {
	type: "tile";
	label: string;
	value: string;
	icon?: MobileViewIcon;
	tone?: MobileViewTone;
	action?: string;
}

export interface MobileViewChartSeries {
	name: string;
	/** Numbers, or null for a gap. Every series has as many as the chart's `labels`. */
	values: Array<number | null>;
}

export interface MobileViewChart extends NodeBase {
	type: "chart";
	kind: "line" | "bar" | "area" | "sparkline";
	series: MobileViewChartSeries[];
	/** The x axis, one per value. */
	labels?: string[];
	unit?: string;
	title?: string;
	/** Fix the y range; otherwise the data's range (including 0 for bar and area). */
	min?: number;
	max?: number;
	/** What screen readers say; derived from the data when absent. */
	summary?: string;
}

export interface MobileViewTableColumn {
	id: string;
	label: string;
	kind?: "text" | "number" | "date" | "mono";
	sortable?: boolean;
}

export interface MobileViewTableRow {
	/** Unique within the table; a row tap's `value`. */
	id: string;
	/** Column id to a string, a number or null (empty). */
	cells: Record<string, string | number | null>;
	/** Makes the row tappable. */
	action?: string;
}

export interface MobileViewTable extends NodeBase {
	type: "table";
	columns: MobileViewTableColumn[];
	rows: MobileViewTableRow[];
	/** The order shown at first. */
	sort?: { column: string; desc?: boolean };
	/** A search box over the rows. */
	filter?: boolean;
	/** Shown when the table has no rows. */
	empty?: string;
}

/** A place; a tap opens the phone's maps app. Needs `lat` and `lon` together, or `address`. */
export interface MobileViewLocation extends NodeBase {
	type: "location";
	name: string;
	address?: string;
	lat?: number;
	lon?: number;
}

export interface MobileViewButton extends NodeBase {
	type: "button";
	label: string;
	style?: "primary" | "secondary" | "ghost" | "danger";
	icon?: MobileViewIcon;
	action?: string;
	disabled?: boolean;
	busy?: boolean;
	/** Opens this link (`https:`, `http:` or `mailto:`) instead of sending an event. */
	url?: string;
	confirm?: { title: string; message?: string; label?: string };
}

export interface MobileViewCard extends NodeBase {
	type: "card";
	title?: string;
	subtitle?: string;
	icon?: MobileViewIcon;
	status?: MobileViewChipProps;
	children: MobileViewNode[];
	actions?: MobileViewButton[];
}

export interface MobileViewSection extends NodeBase {
	type: "section";
	title?: string;
	children: MobileViewNode[];
}

export interface MobileViewItem extends NodeBase {
	type: "item";
	title: string;
	subtitle?: string;
	icon?: MobileViewIcon;
	value?: string;
	chip?: MobileViewChipProps;
	/** 0 to 1. */
	progress?: number;
	/** Makes the row tappable; the `view_event` carries it. */
	action?: string;
	chevron?: boolean;
}

export interface MobileViewList extends NodeBase {
	type: "list";
	children: MobileViewItem[];
	/** Shown when the list has no items. */
	empty?: string;
}

export interface MobileViewRow extends NodeBase {
	type: "row";
	children: Array<MobileViewChip | MobileViewButton | MobileViewText>;
}

export interface MobileViewGrid extends NodeBase {
	type: "grid";
	columns?: 2 | 3 | 4;
	children: MobileViewTile[];
}

export interface MobileViewInput extends NodeBase {
	type: "input";
	label: string;
	value?: string;
	placeholder?: string;
	hint?: string;
	error?: string;
	kind?: "text" | "multiline" | "number" | "email" | "url" | "password";
	required?: boolean;
	maxLength?: number;
}

export interface MobileViewToggle extends NodeBase {
	type: "toggle";
	label: string;
	value: boolean;
	hint?: string;
}

export interface MobileViewSelectOption {
	value: string;
	label: string;
}

export interface MobileViewSelect extends NodeBase {
	type: "select";
	label: string;
	options: Array<string | MobileViewSelectOption>;
	value?: string;
}

export interface MobileViewSlider extends NodeBase {
	type: "slider";
	label: string;
	min: number;
	max: number;
	step?: number;
	value: number;
	unit?: string;
}

export type MobileViewField = MobileViewInput | MobileViewToggle | MobileViewSelect | MobileViewSlider;

export interface MobileViewForm extends NodeBase {
	type: "form";
	children: Array<MobileViewField | MobileViewText | MobileViewNotice | MobileViewDivider>;
	submit: { label: string; action?: string };
	disabled?: boolean;
	busy?: boolean;
}

export type MobileViewNode =
	| MobileViewText
	| MobileViewMarkdown
	| MobileViewCode
	| MobileViewImage
	| MobileViewDivider
	| MobileViewNotice
	| MobileViewKv
	| MobileViewProgress
	| MobileViewChip
	| MobileViewTile
	| MobileViewChart
	| MobileViewTable
	| MobileViewLocation
	| MobileViewButton
	| MobileViewCard
	| MobileViewSection
	| MobileViewList
	| MobileViewItem
	| MobileViewRow
	| MobileViewGrid
	| MobileViewForm
	| MobileViewInput
	| MobileViewToggle
	| MobileViewSelect
	| MobileViewSlider;

export type MobileViewNodeType = MobileViewNode["type"];

export interface MobileView {
	v: 1;
	title?: string;
	subtitle?: string;
	icon?: MobileViewIcon;
	body: MobileViewNode[];
	actions?: MobileViewButton[];
	/** Text lines shown instead of the derived text form (terminal, clients without the kit). */
	fallback?: string[];
}

/** One change of `updateView`. A prop set to null in `set` is removed. */
export type MobileViewOp =
	| { op: "set"; id: string; props: Record<string, unknown> }
	| { op: "replace"; id: string; node: MobileViewNode }
	| { op: "remove"; id: string }
	| { op: "insert"; parent?: string; index?: number; node: MobileViewNode };

export interface MobileViewOptions {
	slot?: MobileViewSlot;
	/** Inline views: show under this tool call's row. */
	toolCallId?: string;
}

export interface ShowViewOptions {
	/** Lets `updateView` change the sheet while it is open, and names it in its `view_event`s. */
	key?: string;
	timeout?: number;
	signal?: AbortSignal;
}

/** How a sheet closed: the submitted form or the pressed `actions` button, and every field of the sheet. */
export interface MobileViewResult {
	id: string;
	action: string;
	values: Record<string, MobileViewValue>;
}

export type ViewEventKind = "tap" | "change" | "submit";

/** A tap, a committed field value or a form submit, after Pi checked it against the view. */
export interface ViewEvent {
	type: "view_event";
	key: string;
	id: string;
	action: string;
	kind: ViewEventKind;
	value?: MobileViewValue;
	values?: Record<string, MobileViewValue>;
}

export const MOBILE_VIEW_LIMITS = {
	viewBytes: 256 * 1024,
	imageDataBytes: 192 * 1024,
	nodes: 400,
	depth: 8,
	children: 200,
	kvItems: 100,
	options: 100,
	actions: 3,
	short: 200,
	text: 4000,
	markdown: 32_000,
	code: 64_000,
	inputMaxLength: 10_000,
	inputDefaultMaxLength: 2000,
	viewsPerSession: 16,
	fallbackLines: 200,
	/** Minimum time between two sends of one view, ms. */
	sendIntervalMs: 100,
	eventsPerSecond: 20,
	chartSeries: 4,
	/** The largest chart reading, and min or max, by magnitude: bounds can be padded and scaled without overflowing. */
	chartMagnitude: 1e15,
	chartValues: 200,
	tableColumns: 12,
	tableRows: 500,
	tileNodes: 24,
	tileBytes: 8 * 1024,
	tileKvItems: 4,
	/** Minimum time between two sends of a tile, ms. */
	tileSendIntervalMs: 1000,
	/** Registered pages and tiles, together, per session. */
	registeredPages: 16,
} as const;

/** The nodes a tile's body may hold. */
export const MOBILE_VIEW_TILE_TYPES: ReadonlySet<string> = new Set(["text", "chip", "row", "progress", "kv", "chart"]);

/** A page or tile a package registered (`pi.registerPage`, `pi.registerTile`). */
export interface MobilePageDefinition<Ctx = unknown> {
	title: string;
	icon?: MobileViewIcon;
	description?: string;
	/** Builds the view when a client opens it. */
	open(ctx: Ctx): MobileView | Promise<MobileView>;
	/** The client closed it, or the session ended while it was open. */
	close?(ctx: Ctx): void | Promise<void>;
}

export interface MobileTileDefinition<Ctx = unknown> extends MobilePageDefinition<Ctx> {
	/** The registered page a tap on the tile opens. */
	page?: string;
}

export const MOBILE_VIEW_ID_PATTERN = /^[A-Za-z0-9_.:-]{1,64}$/;
