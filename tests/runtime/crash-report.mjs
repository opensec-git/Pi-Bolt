// A crash of the engine is reported, with the message of the assertion that failed and Bun's crash report, and ends the process
// with SIGABRT's status, even when a JS listener has SIGABRT: signal-exit has one (Pi loads it with proper-lockfile), and Bun's
// forwarding of the signal to it replaced the crash handler, so that such a crash ended the process with status 134 and nothing
// on stderr. A signal that is sent (kill, process.kill()) still goes to the listener.
// Usage: crash-report measure | crash | crash-unlistened | kill   (compiled ahead of time: tests/runtime/run.sh)
// The crash: realms other than the program's (ShadowRealm) each reserve their compiled code's room for data, and under an address
// space limit (run.sh: ulimit -v) the engine runs out of it: "Out of address space for a realm's ahead-of-time code".
import { spawnSync } from "node:child_process";
import fs from "node:fs";

const mode = process.argv[2];
const module = new URL(import.meta.url.endsWith(".mjs") ? "./crash-report-mod.mjs" : "./crash-report-mod.js", import.meta.url).href;
// What signal-exit 3 does for SIGABRT: a listener that, when it is the only one, removes itself and sends the signal again.
function listenAsSignalExitDoes() {
  const listener = () => {
    if (process.listeners("SIGABRT").length === 1) {
      process.removeListener("SIGABRT", listener);
      process.kill(process.pid, "SIGABRT");
    }
  };
  process.on("SIGABRT", listener);
  return () => process.removeListener("SIGABRT", listener);
}
const realms = [];
async function addRealm() {
  const realm = new ShadowRealm();
  realms.push(realm, await realm.importValue(module, "compute"));
}

await addRealm();
if (mode === "measure") {
  console.log(fs.readFileSync("/proc/self/status", "utf8").match(/VmSize:\s+(\d+)/)[1]);
} else if (mode === "crash" || mode === "crash-unlistened") {
  const unload = listenAsSignalExitDoes();
  // Its listener gone: the crash handler is back.
  if (mode === "crash-unlistened") unload();
  for (let i = 0; i < 64; i++) await addRealm();
  console.log("no crash");
} else if (mode === "kill") {
  listenAsSignalExitDoes();
  let heard = 0;
  process.on("SIGABRT", () => heard++);
  process.kill(process.pid, "SIGABRT");
  await new Promise((resolve) => setTimeout(resolve, 50));
  spawnSync("sh", ["-c", `kill -ABRT ${process.pid}`]);
  await new Promise((resolve) => setTimeout(resolve, 50));
  console.log("listener heard", heard);
}
