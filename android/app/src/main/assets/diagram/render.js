// Offscreen Mermaid renderer for ui/diagram/MermaidRenderer.kt.
//
// The app calls piRender(id, code, theme, opts) through evaluateJavascript with every argument as a JSON literal
// (never spliced into code). The page renders the diagram with securityLevel "strict" and SVG labels only, rasterises
// the SVG into a PNG on a canvas and hands it back through the one bridge method, PiBoltDiagram.result(...).
"use strict";
(function () {
	const bridge = window.PiBoltDiagram;
	const SVG_NS = "http://www.w3.org/2000/svg";
	// Every diagram type sizes itself instead of filling its container.
	const SIZED = ["flowchart", "sequence", "class", "state", "er", "gantt", "pie", "mindmap", "journey", "timeline", "gitGraph", "quadrantChart", "xyChart", "requirement", "block", "sankey", "architecture", "packet", "radar", "kanban", "treemap"];

	// Inter, embedded into each SVG as a data URL: an SVG drawn as an image cannot see the page's fonts, and its labels
	// were measured in Inter.
	const fontCss = fetch("inter.ttf")
		.then((r) => r.blob())
		.then(
			(blob) =>
				new Promise((resolve) => {
					const reader = new FileReader();
					reader.onload = () => resolve(`@font-face{font-family:Inter;src:url(${reader.result}) format("truetype");}`);
					reader.onerror = () => resolve("");
					reader.readAsDataURL(blob);
				}),
		)
		.catch(() => "");
	const ready = Promise.all([fontCss, document.fonts.load("14px Inter").catch(() => null)]);

	let queue = Promise.resolve();

	function config(theme) {
		const c = {
			startOnLoad: false,
			securityLevel: "strict",
			htmlLabels: false,
			look: "classic",
			theme: "base",
			darkMode: theme.dark !== false,
			themeVariables: theme.vars,
			fontFamily: "Inter, sans-serif",
			suppressErrorRendering: true,
			maxTextSize: 100000,
		};
		for (const key of SIZED) c[key] = { useMaxWidth: false };
		c.flowchart.htmlLabels = false;
		c.class = { useMaxWidth: false, htmlLabels: false };
		c.gantt.useWidth = 720;
		return c;
	}

	// The first node, actor or entity label (edge labels come first in the SVG), else the first text with a letter.
	function firstLabel(svg) {
		for (const selector of ["[class*='node'] text", "text.actor, .actor text", "[class*='title'], [class*='Title']", "text"]) {
			for (const t of svg.querySelectorAll(selector)) {
				const s = (t.textContent || "").replace(/\s+/g, " ").trim();
				if (/\p{L}/u.test(s)) return s.slice(0, 80);
			}
		}
		return "";
	}

	function message(e) {
		if (!e) return "Unknown error";
		if (typeof e === "string") return e;
		return String(e.message || e.str || e);
	}

	async function draw(id, code, theme, opts) {
		await ready;
		const stage = document.getElementById("stage");
		mermaid.initialize(config(theme));
		let out;
		try {
			out = await mermaid.render(`pi-diagram-${id}`, code, stage);
		} finally {
			stage.textContent = "";
		}
		const doc = new DOMParser().parseFromString(out.svg, "image/svg+xml");
		const svg = doc.documentElement;
		if (!svg || svg.nodeName !== "svg") throw new Error("Mermaid returned no SVG");
		let w = 0;
		let h = 0;
		const box = (svg.getAttribute("viewBox") || "").trim().split(/[\s,]+/).map(Number);
		if (box.length === 4 && box[2] > 0 && box[3] > 0) {
			w = box[2];
			h = box[3];
		} else {
			w = parseFloat(svg.getAttribute("width")) || 0;
			h = parseFloat(svg.getAttribute("height")) || 0;
		}
		if (!(w > 0 && h > 0)) throw new Error("The diagram is empty");
		svg.setAttribute("width", String(w));
		svg.setAttribute("height", String(h));
		svg.removeAttribute("style");
		const font = await fontCss;
		if (font) {
			const style = doc.createElementNS(SVG_NS, "style");
			style.textContent = font;
			svg.insertBefore(style, svg.firstChild);
		}

		let s = opts.scale;
		if (opts.maxWidth > 0) s = Math.min(s, opts.maxWidth / w);
		if (opts.maxHeight > 0) s = Math.min(s, opts.maxHeight / h);
		if (w * h * s * s > opts.maxPixels) s = Math.sqrt(opts.maxPixels / (w * h));
		const cw = Math.max(1, Math.round(w * s));
		const ch = Math.max(1, Math.round(h * s));

		// A data: URL: SVG images from blob: URLs never finish loading in Android WebView.
		const url = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(new XMLSerializer().serializeToString(svg));
		const img = new Image();
		await new Promise((resolve, reject) => {
			img.onload = resolve;
			img.onerror = () => reject(new Error("The diagram image could not be decoded"));
			img.src = url;
		});
		const canvas = document.createElement("canvas");
		canvas.width = cw;
		canvas.height = ch;
		const ctx = canvas.getContext("2d");
		ctx.drawImage(img, 0, 0, cw, ch);
		const png = canvas.toDataURL("image/png");
		const comma = png.indexOf(",");
		return { w, h, kind: out.diagramType || "", label: firstLabel(svg), png: png.slice(comma + 1) };
	}

	window.piRender = function (id, code, theme, opts) {
		queue = queue.then(async () => {
			try {
				const r = await draw(id, code, theme, opts);
				bridge.result(id, r.w, r.h, r.kind, r.label, r.png, "");
			} catch (e) {
				bridge.result(id, 0, 0, "", "", "", message(e));
			}
		});
	};
})();
