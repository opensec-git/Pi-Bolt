// pibolt-preview: written by the Pi-Bolt Android app; do not edit (it is rewritten).
//
// Tools that let Pi check its own UI work in the app's in-app preview (a WebView showing the project's dev server).
// The app drives the WebView; this extension forwards each call to the app over a unix socket the app owns
// ($PIBOLT_PREVIEW_SOCKET, in a 0700 folder of the app, peers checked by uid): one JSON line per request, one JSON line
// back. The app sets the variable only for project engines on the phone, so chats and paired computers never get
// these tools.
import { connect } from "node:net";

export const SOCKET_VAR = "PIBOLT_PREVIEW_SOCKET";

/** Longest wait for one answer: a page load plus a screenshot stays well under it. */
const TIMEOUT_MS = 45_000;
/** Longest answer line (a downscaled PNG in base64 is a few MB at most). */
const MAX_REPLY = 24 * 1024 * 1024;
const MAX_SELECTOR = 1_000;
const MAX_TEXT = 10_000;
const MAX_JS = 20_000;

/** Sends one request to the app and resolves with its parsed answer. Rejects on a socket error, timeout or abort. */
export function ask(socketPath, request, { signal, timeoutMs = TIMEOUT_MS } = {}) {
	return new Promise((resolve, reject) => {
		if (signal?.aborted) {
			reject(new Error("Cancelled"));
			return;
		}
		const socket = connect(socketPath);
		const chunks = [];
		let size = 0;
		let done = false;
		const finish = (error, value) => {
			if (done) return;
			done = true;
			clearTimeout(timer);
			signal?.removeEventListener("abort", onAbort);
			socket.destroy();
			if (error) reject(error);
			else resolve(value);
		};
		const onAbort = () => finish(new Error("Cancelled"));
		const timer = setTimeout(() => finish(new Error("The app did not answer in time")), timeoutMs);
		signal?.addEventListener("abort", onAbort, { once: true });
		socket.on("connect", () => socket.write(`${JSON.stringify(request)}\n`));
		socket.on("data", (chunk) => {
			size += chunk.length;
			if (size > MAX_REPLY) {
				finish(new Error("The app's answer is too large"));
				return;
			}
			const newline = chunk.indexOf(10);
			if (newline < 0) {
				chunks.push(chunk);
				return;
			}
			chunks.push(chunk.subarray(0, newline));
			try {
				finish(null, JSON.parse(Buffer.concat(chunks).toString("utf8")));
			} catch {
				finish(new Error("The app sent an answer that is not JSON"));
			}
		});
		socket.on("error", (error) =>
			finish(new Error(error?.code === "ENOENT" || error?.code === "ECONNREFUSED" ? "The Pi-Bolt app is not listening" : String(error?.message ?? error))),
		);
		socket.on("end", () => finish(new Error("The app closed the connection without an answer")));
	});
}

const isNumber = (value) => typeof value === "number" && Number.isFinite(value);
const text = (value) => (typeof value === "string" ? value : "");

/**
 * The app's request for tool [name] with [params], or `{ error }` for arguments the tool rejects. The app checks
 * everything again; this gives the model a clear message without a round trip.
 */
export function requestFor(name, params, cwd) {
	const p = params ?? {};
	const op = name.replace(/^preview_/, "");
	const fail = (error) => ({ error });
	const args = {};
	switch (name) {
		case "preview_open": {
			const url = text(p.url).trim();
			if (url) {
				if (url.length > 2_000) return fail("url is too long");
				const isPath = url.startsWith("/") && !url.startsWith("//") && !url.includes("\\");
				if (!isPath && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?(\/|$|\?|#)/i.test(url)) {
					return fail("url must be the project's dev server on http://localhost:<port> or 127.0.0.1, or a path such as /about");
				}
				args.url = url;
			}
			if (p.reload === true) args.reload = true;
			break;
		}
		case "preview_screenshot":
			break;
		case "preview_console":
			if (p.since !== undefined) {
				if (!isNumber(p.since) || p.since < 0) return fail("since must be a number from a previous preview_console call");
				args.since = Math.floor(p.since);
			}
			if (p.errorsOnly === true) args.errorsOnly = true;
			break;
		case "preview_click": {
			const selector = text(p.selector).trim();
			const hasPoint = p.x !== undefined || p.y !== undefined;
			if (selector && hasPoint) return fail("give either selector or x and y, not both");
			if (selector) {
				if (selector.length > MAX_SELECTOR) return fail("selector is too long");
				args.selector = selector;
			} else if (hasPoint) {
				if (!isNumber(p.x) || !isNumber(p.y) || p.x < 0 || p.y < 0) return fail("x and y must both be numbers of 0 or more (pixels of the last screenshot)");
				args.x = p.x;
				args.y = p.y;
			} else {
				return fail("give a CSS selector, or x and y from the last screenshot");
			}
			break;
		}
		case "preview_type": {
			const selector = text(p.selector).trim();
			if (!selector) return fail("selector is required");
			if (selector.length > MAX_SELECTOR) return fail("selector is too long");
			if (typeof p.text !== "string") return fail("text is required");
			if (p.text.length > MAX_TEXT) return fail("text is too long");
			args.selector = selector;
			args.text = p.text;
			if (p.submit === true) args.submit = true;
			break;
		}
		case "preview_scroll": {
			const selector = text(p.selector).trim();
			if (selector.length > MAX_SELECTOR) return fail("selector is too long");
			if (p.to !== undefined && p.to !== "top" && p.to !== "bottom") return fail('to must be "top" or "bottom"');
			if (p.dy !== undefined && !isNumber(p.dy)) return fail("dy must be a number of CSS pixels");
			if (!selector && p.to === undefined && p.dy === undefined) return fail("give a selector to scroll into view, dy, or to");
			if (selector) args.selector = selector;
			if (p.to !== undefined) args.to = p.to;
			if (p.dy !== undefined) args.dy = p.dy;
			break;
		}
		case "preview_eval": {
			const js = text(p.js);
			if (!js.trim()) return fail("js is required");
			if (js.length > MAX_JS) return fail("js is too long");
			args.js = js;
			break;
		}
		default:
			return fail(`unknown tool ${name}`);
	}
	return { request: { op, cwd, args } };
}

/** The tool result for the app's [reply]: its text, plus the screenshot as an image block. Throws on an error reply. */
export function resultOf(reply) {
	if (!reply || typeof reply !== "object") throw new Error("No answer from the app");
	if (reply.ok !== true) throw new Error(text(reply.error) || "The preview could not do that");
	const content = [{ type: "text", text: text(reply.text) || "Done." }];
	const image = reply.image;
	if (image && typeof image.data === "string" && image.data.length > 0) {
		content.push({ type: "image", data: image.data, mimeType: text(image.mimeType) || "image/png" });
	}
	return { content, details: reply.details && typeof reply.details === "object" ? reply.details : {} };
}

const SELECTOR = { type: "string", description: "A CSS selector, e.g. `button[type=submit]` or `#email`" };

/** The tools, their descriptions and JSON-schema parameters. */
export const TOOLS = [
	{
		name: "preview_open",
		label: "Open preview",
		description:
			"Open or refresh the app's in-app preview of this project's dev server (a phone WebView). Without url it opens the running dev server it detected. Start the dev server first (for example `npm run dev` in the background).",
		promptSnippet: "Open the project's dev server in the phone's in-app preview",
		parameters: {
			type: "object",
			properties: {
				url: { type: "string", description: "http://localhost:<port>/path of the dev server, or a path such as /about on the open one" },
				reload: { type: "boolean", description: "Reload the page even if it is already open" },
			},
			additionalProperties: false,
		},
	},
	{
		name: "preview_screenshot",
		label: "Screenshot",
		description:
			"A PNG screenshot of the in-app preview as the user sees it on the phone (opens the dev server first if nothing is open). Coordinates in it are what preview_click takes as x and y.",
		promptSnippet: "See the in-app preview as an image",
		parameters: { type: "object", properties: {}, additionalProperties: false },
	},
	{
		name: "preview_console",
		label: "Console",
		description:
			"Console messages, uncaught JS errors and failed requests of the page in the in-app preview. Returns a cursor: pass it as since to get only newer messages.",
		promptSnippet: "Read the preview page's console messages and JS errors",
		parameters: {
			type: "object",
			properties: {
				since: { type: "number", description: "The cursor from a previous preview_console call" },
				errorsOnly: { type: "boolean", description: "Only errors and warnings" },
			},
			additionalProperties: false,
		},
	},
	{
		name: "preview_click",
		label: "Click",
		description: "Click an element of the page in the in-app preview: by CSS selector, or at x,y pixels of the last preview_screenshot.",
		promptSnippet: "Click in the preview by selector or screenshot coordinates",
		parameters: {
			type: "object",
			properties: {
				selector: SELECTOR,
				x: { type: "number", description: "Pixels from the left of the last screenshot" },
				y: { type: "number", description: "Pixels from the top of the last screenshot" },
			},
			additionalProperties: false,
		},
	},
	{
		name: "preview_type",
		label: "Type",
		description: "Type text into an input, textarea or contenteditable element of the page in the in-app preview (replacing its value), optionally submitting its form.",
		promptSnippet: "Type into a field of the preview page",
		parameters: {
			type: "object",
			properties: {
				selector: SELECTOR,
				text: { type: "string", description: "The text to enter" },
				submit: { type: "boolean", description: "Press Enter and submit the element's form afterwards" },
			},
			required: ["selector", "text"],
			additionalProperties: false,
		},
	},
	{
		name: "preview_scroll",
		label: "Scroll",
		description: "Scroll the page in the in-app preview: an element into view, by dy CSS pixels, or to the top or bottom.",
		promptSnippet: "Scroll the preview page",
		parameters: {
			type: "object",
			properties: {
				selector: SELECTOR,
				dy: { type: "number", description: "CSS pixels to scroll down (negative: up)" },
				to: { type: "string", enum: ["top", "bottom"] },
			},
			additionalProperties: false,
		},
	},
	{
		name: "preview_eval",
		label: "Run in page",
		description:
			"Evaluate a synchronous JavaScript expression in the page of the in-app preview and return its value as JSON (DOM queries, app state). Promises are not awaited.",
		promptSnippet: "Evaluate a JS expression in the preview page",
		parameters: {
			type: "object",
			properties: { js: { type: "string", description: "The expression, e.g. document.title or [...document.querySelectorAll('li')].length" } },
			required: ["js"],
			additionalProperties: false,
		},
	},
];

export const GUIDELINES = [
	"After changing a web UI whose dev server runs on this phone, check it with preview_screenshot (and preview_console for errors) instead of assuming it works.",
	"preview_click x and y are pixels of the last preview_screenshot; prefer a CSS selector when the element has one.",
];

export default function (pi) {
	const socketPath = process.env[SOCKET_VAR];
	if (!socketPath) return;
	for (const tool of TOOLS) {
		pi.registerTool({
			...tool,
			promptGuidelines: tool.name === "preview_screenshot" ? GUIDELINES : undefined,
			executionMode: "sequential",
			async execute(_toolCallId, params, signal, _onUpdate, ctx) {
				const prepared = requestFor(tool.name, params, ctx?.cwd ?? process.cwd());
				if (prepared.error) throw new Error(prepared.error);
				return resultOf(await ask(socketPath, prepared.request, { signal }));
			},
		});
	}
}
