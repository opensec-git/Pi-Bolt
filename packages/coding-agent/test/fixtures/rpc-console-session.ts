// Run by test/suite/regressions/rpc-console-stdout.test.ts under Bun: an RPC session (faux provider) whose extension
// prints with every console method that writes to stdout. Commands come on stdin; the session ends when stdin closes.
import { fauxAssistantMessage } from "@earendil-works/pi-ai";
import type { AgentSessionRuntime } from "../../src/core/agent-session-runtime.ts";
import type { ExtensionAPI } from "../../src/core/extensions/types.ts";
import { takeOverStdout } from "../../src/core/output-guard.ts";
import { runRpcMode } from "../../src/modes/rpc/rpc-mode.ts";
import { createHarness } from "../suite/harness.ts";

// As main.ts does: stdout is taken over before extensions load.
takeOverStdout();
const harness = await createHarness({
	extensionFactories: [
		(pi: ExtensionAPI) => {
			console.log("console-marker extension loaded", { a: 1 });
			pi.on("agent_start", () => {
				console.log("console-marker log %s", "x");
				console.info("console-marker info");
				console.debug("console-marker debug");
				console.dir({ marker: "console-marker dir" });
				console.dirxml("console-marker dirxml");
				console.table([{ marker: "console-marker table" }]);
				console.trace("console-marker trace");
				console.count("console-marker count");
				console.group("console-marker group");
				console.log("console-marker in group");
				console.groupEnd();
				(console as { write?: (text: string) => void }).write?.("console-marker write\n");
			});
		},
	],
});
harness.setResponses([fauxAssistantMessage("done")]);
process.on("exit", () => harness.cleanup());

await runRpcMode({
	session: harness.session,
	dispose: async () => {},
	setRebindSession: () => {},
} as unknown as AgentSessionRuntime);
