import { posix } from "node:path";

function workerSite() {
  throw new Error("linked worker Ω");
}

const { isDeepStrictEqual } = await import("node:util");
try {
  workerSite();
} catch (error) {
  if (!/workerSite[^\n]*workers-entry\.m?js:\d+:\d+/.test(error.stack)) throw error;
  if (!workerSite.toString().includes("linked worker")) throw new Error("missing worker source");
  postMessage([posix.basename("graph/worker"), isDeepStrictEqual([error.message], ["linked worker Ω"])].join(" "));
}
