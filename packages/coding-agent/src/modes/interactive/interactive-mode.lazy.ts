/** Loads the TUI's interactive mode only when Pi runs it: print, JSON, RPC, ACP and MCP modes never evaluate it. */
export const loadInteractiveMode = () => import("./interactive-mode.ts");
