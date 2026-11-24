import * as bundledPiAgentCore from "@earendil-works/pi-agent-core";
import * as bundledPiAiBedrockProvider from "@earendil-works/pi-ai/bedrock-provider";
import * as bundledPiAiBunOauth from "@earendil-works/pi-ai/bun-oauth";
import * as bundledPiAiModels from "@earendil-works/pi-ai/models";
import * as bundledPiAiOauth from "@earendil-works/pi-ai/oauth";
import * as bundledPiAiProviders from "@earendil-works/pi-ai/providers/all";
import * as bundledPiAiSessionResources from "@earendil-works/pi-ai/session-resources";
import * as bundledPiTui from "@earendil-works/pi-tui";
import * as bundledTypebox from "typebox";
import * as bundledTypeboxCompile from "typebox/compile";
import * as bundledTypeboxValue from "typebox/value";
// This import is safe because loader.ts exports are not re-exported from index.ts.
// Extensions can therefore import from @earendil-works/pi-coding-agent.
import * as bundledPiCodingAgent from "../../index.ts";
import * as bundledPiAiExtensionView from "./pi-ai-extension-view.ts";

/** Modules available to extensions in source and compiled binary runtimes. */
export const VIRTUAL_MODULES: Record<string, unknown> = {
	typebox: bundledTypebox,
	"typebox/compile": bundledTypeboxCompile,
	"typebox/value": bundledTypeboxValue,
	"@sinclair/typebox": bundledTypebox,
	"@sinclair/typebox/compile": bundledTypeboxCompile,
	"@sinclair/typebox/value": bundledTypeboxValue,
	"@earendil-works/pi-agent-core": bundledPiAgentCore,
	"@earendil-works/pi-tui": bundledPiTui,
	// Extensions resolve the pi-ai root to the compat entrypoint (a strict
	// superset of the core entrypoint) plus TypeBox's Type, Static and TSchema:
	// existing extensions using the old global API and pi-ai's former TypeBox
	// re-exports keep working.
	"@earendil-works/pi-ai": bundledPiAiExtensionView,
	"@earendil-works/pi-ai/compat": bundledPiAiExtensionView,
	"@earendil-works/pi-ai/models": bundledPiAiModels,
	"@earendil-works/pi-ai/oauth": bundledPiAiOauth,
	"@earendil-works/pi-ai/providers/all": bundledPiAiProviders,
	// The other subpaths of Pi's packages whose module Pi itself has (#331: a module that imports the root and one of these
	// gets both from Pi). pi-ai's `providers/*`, `api/*` and `utils/*`, its Node bundle helpers (`lazy-oauth`,
	// `lazy-bedrock-provider`) and the coding agent's `rpc-entry` (a bundle of its own) are not given.
	"@earendil-works/pi-ai/session-resources": bundledPiAiSessionResources,
	"@earendil-works/pi-ai/bun-oauth": bundledPiAiBunOauth,
	"@earendil-works/pi-ai/bedrock-provider": bundledPiAiBedrockProvider,
	"@earendil-works/pi-coding-agent": bundledPiCodingAgent,
	"@earendil-works/pi-coding-agent/pi-ai-extension-view": bundledPiAiExtensionView,
	"@mariozechner/pi-agent-core": bundledPiAgentCore,
	"@mariozechner/pi-tui": bundledPiTui,
	"@mariozechner/pi-ai": bundledPiAiExtensionView,
	"@mariozechner/pi-ai/compat": bundledPiAiExtensionView,
	"@mariozechner/pi-ai/models": bundledPiAiModels,
	"@mariozechner/pi-ai/oauth": bundledPiAiOauth,
	"@mariozechner/pi-ai/providers/all": bundledPiAiProviders,
	"@mariozechner/pi-coding-agent": bundledPiCodingAgent,
};
