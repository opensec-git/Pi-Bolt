// Many workers alive at once, each running code of the program (workers-many-entry.mjs, compiled in). Every worker's realm took a
// block of the static region's room for realms, which held 14 (the main realm and 13 workers on Linux and macOS): the 14th worker
// aborted the process ("The static region is too small for the program"), and Pi's codemode starts a worker per script
// (PI_CODEMODE_MAX_WORKERS=51 did). A worker that finds no room runs without the static heap; all of them must work alike:
// messages with structured clone, SharedArrayBuffer and Atomics, errors, and starting and stopping again and again.
import { work } from "./workers-many-work.mjs";

const alive = 48;
const cycles = 300;
const entry = new URL(import.meta.url.endsWith(".mjs") ? "./workers-many-entry.mjs" : "./workers-many-entry.js", import.meta.url).href;

function start() {
  const worker = new Worker(entry);
  return new Promise((resolve, reject) => {
    worker.addEventListener("message", (event) => event.data === "ready" && resolve(worker), { once: true });
    worker.addEventListener("error", (event) => reject(new Error(event.message)), { once: true });
  });
}
function ask(worker, data) {
  return new Promise((resolve) => {
    worker.addEventListener("message", (event) => resolve(event.data), { once: true });
    worker.postMessage(data);
  });
}

const workers = await Promise.all(Array.from({ length: alive }, start));
const shared = new SharedArrayBuffer(8);
const counter = new Int32Array(shared);
for (const [i, worker] of workers.entries()) {
  const reply = await ask(worker, { kind: "work", seed: i, nested: { list: [i, "é😀", 2n ** 70n], when: new Date(i), seen: new Map([[i, new Set([i])]]) } });
  if (reply.seed !== i || reply.digest !== work(i) || reply.echo.nested.list[2] !== 2n ** 70n || reply.echo.nested.when.getTime() !== i || !reply.echo.nested.seen.get(i).has(i)) throw new Error(`worker ${i}: bad reply`);
  const added = await ask(worker, { kind: "atomics", shared });
  if (added !== i + 1) throw new Error(`worker ${i}: atomics ${added}`);
  const thrown = await ask(worker, { kind: "throw" });
  if (thrown !== "thrown in a worker true") throw new Error(`worker ${i}: ${thrown}`);
}
console.log("alive", alive, "atomics", Atomics.load(counter, 0));
for (const worker of workers) worker.terminate();

let done = 0;
async function lane() {
  while (done < cycles) {
    const n = ++done;
    const worker = await start();
    const reply = await ask(worker, { kind: "work", seed: n, nested: {} });
    if (reply.seed !== n || reply.digest !== work(n)) throw new Error("churn: bad reply");
    worker.terminate();
  }
}
await Promise.all(Array.from({ length: 16 }, lane));
console.log("started and stopped", cycles, "more, 16 at a time");
