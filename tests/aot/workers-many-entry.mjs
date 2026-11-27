// The worker of workers-many.mjs.
import { work } from "./workers-many-work.mjs";

self.onmessage = (event) => {
  const message = event.data;
  if (message.kind === "work") postMessage({ seed: message.seed, digest: work(message.seed), echo: message });
  else if (message.kind === "atomics") postMessage(Atomics.add(new Int32Array(message.shared), 0, 1) + 1);
  else {
    try {
      (function thrower() {
        throw new Error("thrown in a worker");
      })();
    } catch (error) {
      postMessage(`${error.message} ${error.stack.split("\n")[1].trim().startsWith("at thrower")}`);
    }
  }
};
postMessage("ready");
