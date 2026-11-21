// Workers under the static heap: each worker does string/regexp/Map/JSON/class work and posts a digest; the main thread
// does the same work and compares. Runs several workers in parallel, then sequentially.
const code = `
  function work(seed) {
    const m = new Map(); const parts = [];
    class P { constructor(x) { this.x = x; this.y = x * 2; } sum() { return this.x + this.y; } }
    let acc = 0;
    for (let i = 0; i < 20000; i++) {
      const s = "item-" + ((i * 7919 + seed) % 1000) + "-\\u00e9\\uD83D\\uDE00";
      m.set(s, (m.get(s) ?? 0) + 1);
      if (/^item-(\\d+)-/.test(s)) acc += new P(i).sum() % 97;
      if (i % 1000 === 0) parts.push(JSON.stringify({ i, s, n: m.size }));
    }
    const text = parts.join("\\n").replace(/[\\uD800-\\uDBFF](?![\\uDC00-\\uDFFF])|(?<![\\uD800-\\uDBFF])[\\uDC00-\\uDFFF]/g, "");
    return { acc, size: m.size, len: text.length, head: text.slice(0, 40), nav: typeof navigator, hw: typeof navigator === "object" ? navigator.hardwareConcurrency > 0 : null };
  }
  self.onmessage = (e) => { postMessage({ seed: e.data, out: work(e.data) }); };
`;
const url = URL.createObjectURL(new Blob([code], { type: "application/javascript" }));
function work(seed) { // the same, on the main thread
  const m = new Map(); const parts = [];
  class P { constructor(x) { this.x = x; this.y = x * 2; } sum() { return this.x + this.y; } }
  let acc = 0;
  for (let i = 0; i < 20000; i++) {
    const s = "item-" + ((i * 7919 + seed) % 1000) + "-\u00e9\uD83D\uDE00";
    m.set(s, (m.get(s) ?? 0) + 1);
    if (/^item-(\d+)-/.test(s)) acc += new P(i).sum() % 97;
    if (i % 1000 === 0) parts.push(JSON.stringify({ i, s, n: m.size }));
  }
  const text = parts.join("\n").replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, "");
  return { acc, size: m.size, len: text.length, head: text.slice(0, 40) };
}
function run(seed) {
  return new Promise((resolve, reject) => {
    const w = new Worker(url);
    w.onmessage = (e) => { w.terminate(); resolve(e.data); };
    w.onerror = (e) => reject(e.message ?? e);
    w.postMessage(seed);
  });
}
const parallel = await Promise.all([1, 2, 3, 4].map(run));
for (const r of parallel) { const e = work(r.seed); if (JSON.stringify({ ...r.out, nav: undefined, hw: undefined }) !== JSON.stringify({ ...e, nav: undefined, hw: undefined })) throw new Error("mismatch " + JSON.stringify([r, e])); }
for (let seed = 5; seed < 13; seed++) { const r = await run(seed); const e = work(seed); if (r.out.acc !== e.acc || r.out.len !== e.len) throw new Error("mismatch seq " + seed); }
console.log("workers ok", parallel.map((r) => r.out.acc).join(","), parallel[0].out.nav, parallel[0].out.hw);

// A separately compiled entry reads the shared bytecode and string table on the worker's VM.
const entry = import.meta.url.endsWith(".mjs") ? "./workers-entry.mjs" : "./workers-entry.js";
const embedded = new Worker(new URL(entry, import.meta.url).href);
try {
  const result = await new Promise((resolve, reject) => {
    embedded.onmessage = (event) => resolve(event.data);
    embedded.onerror = (event) => reject(new Error(event.message));
  });
  console.log("embedded worker", result);
} finally {
  embedded.terminate();
}
