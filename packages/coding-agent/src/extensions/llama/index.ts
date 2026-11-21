import type { ExtensionAPI } from "../../core/extensions/types.ts";
import { loadLlamaCommand } from "./command.lazy.ts";
import { createLlamaProvider } from "./provider.ts";

export default function llamaExtension(pi: ExtensionAPI): void {
	const provider = createLlamaProvider();
	pi.registerProvider(provider.provider);

	pi.registerCommand("llama", {
		description: "Manage llama.cpp router models",
		handler: async (_args, ctx) => {
			if (ctx.mode !== "tui") {
				ctx.ui.notify("/llama is available in interactive mode", "warning");
				return;
			}
			const { runLlamaCommand } = await loadLlamaCommand();
			await runLlamaCommand(ctx, provider);
		},
	});
}
