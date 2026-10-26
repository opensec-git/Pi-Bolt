/**
 * The extension ACP mode adds to each of its sessions: it gives Pi's MCP support (extensions/mcp) the MCP servers the
 * editor passed with `session/new` or `session/load`, such as an IDE's own tool server, next to the servers of `mcp.json`.
 * They connect and are exposed like any other server (`/mcp` lists them); a server of the same name in `mcp.json` wins.
 */

import type { InlineExtension } from "../../core/extensions/types.ts";
import type { McpServerConfig } from "../../core/mcp-servers.ts";
import type { McpServer } from "./types.ts";

/** MCP servers by Pi session id, set by ACP mode before it creates or loads the session. */
const sessionMcpServers = new Map<string, McpServer[]>();

/** The tools read-only mode keeps: they only read. */
export const READ_ONLY_TOOLS: readonly string[] = ["read", "grep", "find", "ls"];
const readOnlyTools = new Set(READ_ONLY_TOOLS);

/** Pi session ids whose ACP session is in read-only mode. */
const readOnlySessions = new Set<string>();

export function setSessionReadOnly(sessionId: string, readOnly: boolean): void {
	if (readOnly) readOnlySessions.add(sessionId);
	else readOnlySessions.delete(sessionId);
}

export function setSessionMcpServers(sessionId: string, servers: readonly McpServer[] | undefined): void {
	if (servers && servers.length > 0) sessionMcpServers.set(sessionId, [...servers]);
	else sessionMcpServers.delete(sessionId);
}

/**
 * Pi resolves `${NAME}` and `!command` in MCP header and env values; values from the editor are taken as they are.
 */
export function literalConfigValue(value: string): string {
	const escaped = value.replace(/\$/g, "$$$$");
	return escaped.startsWith("!") ? `$${escaped}` : escaped;
}

/** A name Pi accepts for an MCP server: letters, digits, "_" and "-". */
export function mcpServerName(name: string): string {
	const cleaned = name.replace(/[^A-Za-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "");
	return cleaned || "server";
}

/** Pi's config for an ACP MCP server, or undefined for a transport Pi does not have (legacy SSE). */
export function toMcpServerConfig(server: McpServer): McpServerConfig | undefined {
	if (server.type === "sse") return undefined;
	if (server.type === "http") {
		return {
			type: "http",
			url: server.url,
			headers: Object.fromEntries(server.headers.map((header) => [header.name, literalConfigValue(header.value)])),
		};
	}
	return {
		type: "stdio",
		command: server.command,
		args: server.args,
		env: Object.fromEntries(
			(server.env ?? []).map((variable) => [variable.name, literalConfigValue(variable.value)]),
		),
	};
}

export const acpExtension: InlineExtension = {
	name: "acp",
	builtin: true,
	factory: (pi) => {
		// Read-only mode holds when each call is made, not only for the tools active when it was chosen: a tool registered
		// later (an MCP server that connects during a prompt, an extension's tool) is active at once. The tools it keeps
		// pass unchanged, so they still may start early.
		pi.on("tool_call", (event, ctx) => {
			if (readOnlyTools.has(event.toolName) || !readOnlySessions.has(ctx.sessionManager.getSessionId())) return;
			return {
				block: true,
				reason: `Read-only mode: ${event.toolName} is not available. Only read, grep, find and ls are.`,
			};
		});
		let registered: string[] = [];
		pi.on("session_start", (_event, ctx) => {
			for (const name of registered) pi.unregisterMcpServer(name);
			registered = [];
			const taken = new Set(pi.getMcpServers().map((server) => server.name));
			for (const server of sessionMcpServers.get(ctx.sessionManager.getSessionId()) ?? []) {
				const config = toMcpServerConfig(server);
				if (!config) {
					ctx.ui.notify(`MCP server "${server.name}": legacy SSE servers are not supported`, "warning");
					continue;
				}
				let name = mcpServerName(server.name);
				for (let i = 2; taken.has(name); i++) name = `${mcpServerName(server.name)}-${i}`;
				try {
					pi.registerMcpServer(name, config);
					taken.add(name);
					registered.push(name);
				} catch (error) {
					ctx.ui.notify(error instanceof Error ? error.message : String(error), "warning");
				}
			}
		});
	},
};
