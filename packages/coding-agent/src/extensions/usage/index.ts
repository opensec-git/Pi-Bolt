import type { ExtensionFactory } from "../../core/extensions/types.ts";
import { loadUsageCommand } from "./command.lazy.ts";

export function createUsageExtension(): ExtensionFactory {
	return (pi) => {
		pi.registerCommand("usage", {
			description: "Show token and cost usage",
			handler: async (_args, ctx) => {
				if (ctx.mode !== "tui") {
					ctx.ui.notify("/usage is available in interactive mode", "warning");
					return;
				}
				const { runUsageCommand } = await loadUsageCommand();
				await runUsageCommand(ctx);
			},
		});
	};
}

export default createUsageExtension();
