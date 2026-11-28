// hunt-engine-3: under an address space limit (ulimit -v) that leaves room for the program, workers that find no block of the static
// region run without the static heap, but each still reserved its realm's room for compiled code's data (256 MB of addresses), and
// the first that could not ended the process: "ASSERTION FAILED: Out of address space for a realm's ahead-of-time code". Stock Bun
// runs the same workers under the same limit.
// Usage: realm-limit measure | run N   (compiled ahead of time: tests/runtime/run.sh)
import fs from "node:fs";
import { Worker, isMainThread, parentPort } from "node:worker_threads";

export function work(n) {
  let h = n | 0;
  for (let i = 0; i < 10000; i++) h = (Math.imul(h, 31) + i) | 0;
  return h;
}

if (!isMainThread) {
  parentPort.on("message", (n) => parentPort.postMessage(work(n)));
} else if (process.argv[2] === "measure") {
  console.log(fs.readFileSync("/proc/self/status", "utf8").match(/VmSize:\s+(\d+)/)[1]);
} else {
  const count = Number(process.argv[3]);
  const workers = Array.from({ length: count }, () => new Worker(new URL(import.meta.url)));
  const answers = await Promise.all(workers.map((worker, i) => new Promise((resolve) => {
    worker.once("message", resolve);
    worker.postMessage(i);
  })));
  console.log("workers", count, answers.every((answer, i) => answer === work(i)) ? "ok" : "wrong");
  for (const worker of workers) await worker.terminate();
}
