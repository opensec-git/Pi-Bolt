/**
 * The `get_providers` RPC command: every provider the session's model runtime knows, with how it authenticates and
 * which of its chat models work now. A client uses it to list providers that extensions register and to tell whether
 * any model is usable, without a session model.
 */

import type { ModelRuntime } from "../../core/model-runtime.ts";
import { getConfigValueEnvVarNames } from "../../core/resolve-config-value.ts";
import type { RpcProviderInfo } from "./rpc-types.ts";

/**
 * Describes the providers of [runtime]. Availability is checked again first (credentials may have changed on disk
 * since the last check), so `configured` and `available` are current. Never includes credential values.
 */
export async function describeProviders(runtime: ModelRuntime, signal?: AbortSignal): Promise<RpcProviderInfo[]> {
	const available = await runtime.getAvailable(undefined, { signal });
	const credentials = new Map(
		(await runtime.listCredentials({ signal })).map((entry) => [entry.providerId, entry.type] as const),
	);
	const registered = new Set(runtime.getRegisteredProviderIds());
	const availableIds = new Map<string, string[]>();
	for (const model of available) {
		const ids = availableIds.get(model.provider) ?? [];
		ids.push(model.id);
		availableIds.set(model.provider, ids);
	}
	return runtime.getProviders().map((provider): RpcProviderInfo => {
		const status = runtime.getProviderAuthStatus(provider.id);
		// Only a `$NAME` reference names a variable; a literal key or a command is never reported.
		const rawKey = runtime.getRegisteredProviderConfig(provider.id)?.apiKey;
		const env = rawKey === undefined ? [] : getConfigValueEnvVarNames(rawKey);
		const { apiKey, oauth } = provider.auth;
		const credential = credentials.get(provider.id);
		return {
			id: provider.id,
			name: provider.name,
			origin: runtime.getProviderOrigin(provider.id) ?? "virtual",
			extension: registered.has(provider.id),
			configured: runtime.hasConfiguredAuth(provider.id),
			...(credential ? { credential } : {}),
			...(status.configured && status.source ? { authSource: status.source } : {}),
			...(apiKey ? { apiKey: { name: apiKey.name, env } } : {}),
			...(oauth
				? {
						oauth: {
							name: oauth.name,
							...(oauth.loginLabel ? { loginLabel: oauth.loginLabel } : {}),
							...(oauth.isSubscription ? { subscription: true } : {}),
						},
					}
				: {}),
			models: runtime.getModels(provider.id).map((model) => ({
				id: model.id,
				name: model.name,
				reasoning: model.reasoning,
				input: [...model.input],
				contextWindow: model.contextWindow,
			})),
			available: availableIds.get(provider.id) ?? [],
		};
	});
}
