/**
 * Agent loop that works with AgentMessage throughout.
 * Transforms to Message[] only at the LLM call boundary.
 */

import {
	type AssistantMessage,
	type AssistantMessageEvent,
	type ClientIdentity,
	EventStream,
	type JsonObject,
	mergeClientIdentity,
	normalizeContext,
	PI_ATTRIBUTION,
	type SystemMessage,
	type Tool,
	type ToolResultMessage,
	type ToolStateChanges,
	toToolDeclaration,
	validateToolArguments,
} from "@earendil-works/pi-ai";
import { getDefaultStreamFn } from "./stream-fn.ts";
import type {
	AgentContext,
	AgentEvent,
	AgentLoopConfig,
	AgentMessage,
	AgentTool,
	AgentToolCall,
	AgentToolCallOutcome,
	AgentToolResult,
	EarlyToolExecution,
	PrepareNextTurnContext,
	StreamFn,
} from "./types.ts";

export type AgentEventSink = (event: AgentEvent) => Promise<void> | void;

/**
 * Request identity of agent loops: pi-ai's defaults with provider attribution to pi. `AgentLoopConfig.identity`
 * overrides it field by field.
 */
export const DEFAULT_AGENT_IDENTITY: ClientIdentity = { attribution: PI_ATTRIBUTION };

/**
 * Start an agent loop with a new prompt message.
 * The prompt is added to the context and events are emitted for it.
 */
export function agentLoop(
	prompts: AgentMessage[],
	context: AgentContext,
	config: AgentLoopConfig,
	signal: AbortSignal | undefined,
	streamFn: StreamFn,
): EventStream<AgentEvent, AgentMessage[]> {
	const stream = createAgentStream();

	void runAgentLoop(
		prompts,
		context,
		config,
		async (event) => {
			stream.push(event);
		},
		signal,
		streamFn,
	).then((messages) => {
		stream.end(messages);
	});

	return stream;
}

/**
 * Continue an agent loop from the current context without adding a new message.
 * Used for retries - context already has user message or tool results.
 *
 * **Important:** The last message in context must convert to a `user` or `toolResult` message
 * via `convertToLlm`. If it doesn't, the LLM provider will reject the request.
 * This cannot be validated here since `convertToLlm` is only called once per turn.
 */
export function agentLoopContinue(
	context: AgentContext,
	config: AgentLoopConfig,
	signal: AbortSignal | undefined,
	streamFn: StreamFn,
): EventStream<AgentEvent, AgentMessage[]> {
	if (context.messages.length === 0) {
		throw new Error("Cannot continue: no messages in context");
	}

	if (context.messages[context.messages.length - 1].role === "assistant") {
		throw new Error("Cannot continue from message role: assistant");
	}

	const stream = createAgentStream();

	void runAgentLoopContinue(
		context,
		config,
		async (event) => {
			stream.push(event);
		},
		signal,
		streamFn,
	).then((messages) => {
		stream.end(messages);
	});

	return stream;
}

export async function runAgentLoop(
	prompts: AgentMessage[],
	context: AgentContext,
	config: AgentLoopConfig,
	emit: AgentEventSink,
	signal: AbortSignal | undefined,
	streamFn: StreamFn,
): Promise<AgentMessage[]> {
	const initialMessages = declareToolChanges(context, prompts);
	const newMessages: AgentMessage[] = [...initialMessages];
	const currentContext: AgentContext = {
		...context,
		messages: [...context.messages, ...initialMessages],
	};

	await emit({ type: "agent_start" });
	await emit({ type: "turn_start" });
	for (const message of initialMessages) {
		await emit({ type: "message_start", message });
		await emit({ type: "message_end", message });
	}

	await runLoop(currentContext, newMessages, config, signal, emit, streamFn ?? getDefaultStreamFn());
	return newMessages;
}

export async function runAgentLoopContinue(
	context: AgentContext,
	config: AgentLoopConfig,
	emit: AgentEventSink,
	signal: AbortSignal | undefined,
	streamFn: StreamFn,
): Promise<AgentMessage[]> {
	if (context.messages.length === 0) {
		throw new Error("Cannot continue: no messages in context");
	}

	if (context.messages[context.messages.length - 1].role === "assistant") {
		throw new Error("Cannot continue from message role: assistant");
	}

	const newMessages: AgentMessage[] = [];
	const currentContext: AgentContext = { ...context };

	await emit({ type: "agent_start" });
	await emit({ type: "turn_start" });

	await runLoop(currentContext, newMessages, config, signal, emit, streamFn ?? getDefaultStreamFn());
	return newMessages;
}

function createAgentStream(): EventStream<AgentEvent, AgentMessage[]> {
	return new EventStream<AgentEvent, AgentMessage[]>(
		(event: AgentEvent) => event.type === "agent_end",
		(event: AgentEvent) => (event.type === "agent_end" ? event.messages : []),
	);
}

/**
 * Main loop logic shared by agentLoop and agentLoopContinue.
 */
async function runLoop(
	initialContext: AgentContext,
	newMessages: AgentMessage[],
	initialConfig: AgentLoopConfig,
	signal: AbortSignal | undefined,
	emit: AgentEventSink,
	streamFunction: StreamFn,
): Promise<void> {
	let currentContext = initialContext;
	let config = initialConfig;
	let lastCompletedTurn: PrepareNextTurnContext | undefined;
	let explicitContinuation = false;
	// Check for steering messages at start (user may have typed while waiting)
	let pendingMessages: AgentMessage[] = (await config.getSteeringMessages?.()) || [];

	// Outer loop: continues when queued follow-up messages arrive after agent would stop
	while (true) {
		let hasMoreToolCalls = true;

		// Inner loop: process tool calls and steering messages
		while (hasMoreToolCalls || pendingMessages.length > 0) {
			let preparedMessages: AgentMessage[] = [];
			if (lastCompletedTurn) {
				const nextTurnSnapshot = await config.prepareNextTurn?.(lastCompletedTurn);
				if (nextTurnSnapshot) {
					currentContext = nextTurnSnapshot.context ?? currentContext;
					preparedMessages = nextTurnSnapshot.messages ?? [];
					config = {
						...config,
						model: nextTurnSnapshot.model ?? config.model,
						reasoning:
							nextTurnSnapshot.thinkingLevel === undefined
								? config.reasoning
								: nextTurnSnapshot.thinkingLevel === "off"
									? undefined
									: nextTurnSnapshot.thinkingLevel,
					};
				}
				// Preparation can be long-running (for example, compaction). Pick up steering
				// queued while it ran. Only poll again if the earlier poll returned nothing;
				// otherwise one-at-a-time mode would deliver two messages in this turn.
				if (pendingMessages.length === 0) {
					pendingMessages = (await config.getSteeringMessages?.()) || [];
				}
				await emit({ type: "turn_start" });
			}

			// Process prepared and queued messages before the next assistant response.
			for (const message of declareToolChanges(currentContext, [...preparedMessages, ...pendingMessages])) {
				await emit({ type: "message_start", message });
				await emit({ type: "message_end", message });
				currentContext.messages.push(message);
				newMessages.push(message);
			}
			pendingMessages = [];

			const requestUpdate = await config.prepareRequest?.(
				{
					context: currentContext,
					model: config.model,
					thinkingLevel: config.reasoning ?? "off",
				},
				signal,
			);
			if (requestUpdate) {
				currentContext = requestUpdate.context ?? currentContext;
				config = {
					...config,
					model: requestUpdate.model ?? config.model,
					reasoning:
						requestUpdate.thinkingLevel === undefined
							? config.reasoning
							: requestUpdate.thinkingLevel === "off"
								? undefined
								: requestUpdate.thinkingLevel,
				};
			}

			// Stream assistant response
			const earlyToolCalls =
				config.earlyToolExecution && currentContext.tools?.length
					? new EarlyToolCalls(currentContext.tools, config.earlyToolExecution, signal)
					: undefined;
			let message: AssistantMessage;
			try {
				message = await streamAssistantResponse(
					currentContext,
					config,
					signal,
					emit,
					streamFunction,
					earlyToolCalls,
				);
			} catch (error) {
				earlyToolCalls?.discard();
				throw error;
			}
			newMessages.push(message);

			if (message.stopReason === "error" || message.stopReason === "aborted") {
				earlyToolCalls?.discard();
				lastCompletedTurn = {
					message,
					toolResults: [],
					context: currentContext,
					newMessages,
				};
				await config.finishTurn?.(lastCompletedTurn, signal);
				await emit({ type: "turn_end", message, toolResults: [] });
				await emit({ type: "agent_end", messages: newMessages });
				return;
			}

			// Check for tool calls
			const toolCalls = message.content.filter((c) => c.type === "toolCall");

			const toolResults: ToolResultMessage[] = [];
			hasMoreToolCalls = false;
			if (toolCalls.length > 0) {
				// A "length" stop means the output was cut off by the token limit, so
				// every tool call in the message may carry truncated arguments. Fail
				// them all instead of executing potentially borked calls.
				let executedToolBatch: ExecutedToolCallBatch;
				try {
					executedToolBatch =
						message.stopReason === "length"
							? await failToolCallsFromTruncatedMessage(toolCalls, emit)
							: await executeToolCalls(currentContext, message, config, signal, emit, earlyToolCalls);
				} finally {
					earlyToolCalls?.discard();
				}
				toolResults.push(...executedToolBatch.messages);
				hasMoreToolCalls = !executedToolBatch.terminate;

				for (const result of toolResults) {
					currentContext.messages.push(result);
					newMessages.push(result);
				}
			} else {
				earlyToolCalls?.discard();
			}

			lastCompletedTurn = {
				message,
				toolResults,
				context: currentContext,
				newMessages,
			};
			const decision = await config.finishTurn?.(lastCompletedTurn, signal);
			await emit({ type: "turn_end", message, toolResults });

			if (decision?.action === "end") {
				await emit({ type: "agent_end", messages: newMessages });
				return;
			}

			explicitContinuation = decision?.action === "continue";
			pendingMessages = (await config.getSteeringMessages?.()) || [];
			if (hasMoreToolCalls || pendingMessages.length > 0) {
				explicitContinuation = false;
			}
		}

		// Agent would stop here. Check for follow-up messages.
		const followUpMessages = (await config.getFollowUpMessages?.()) || [];
		if (followUpMessages.length > 0) {
			// Set as pending so inner loop processes them
			explicitContinuation = false;
			pendingMessages = followUpMessages;
			continue;
		}

		// No natural request was selected, so fulfill the continuation decision with one context-only turn.
		if (explicitContinuation) {
			explicitContinuation = false;
			continue;
		}

		// No more messages, exit
		break;
	}

	await emit({ type: "agent_end", messages: newMessages });
}

/**
 * Declare tool loadout changes to the model.
 *
 * `context.tools` is what the runtime can execute; the transcript's system messages declare
 * what the model may call. Before each request the difference becomes `toolsAdded` and
 * `toolsRemoved` on a system message. When a pending system message exists, its tool fields
 * are treated as intent and replaced with the delta between the committed transcript and
 * the executable set, so replay always yields exactly `context.tools`. Otherwise a new
 * system message is inserted before the first non-system pending message.
 */
function declareToolChanges(context: AgentContext, pendingMessages: AgentMessage[]): AgentMessage[] {
	let systemIndex = -1;
	for (let i = pendingMessages.length - 1; i >= 0; i--) {
		if (pendingMessages[i].role === "system") {
			systemIndex = i;
			break;
		}
	}
	const pending = pendingMessages[systemIndex] as SystemMessage | undefined;
	const baseline = pending
		? pendingMessages.map((message, index) =>
				index === systemIndex ? withToolChanges(pending, NO_CHANGES) : message,
			)
		: pendingMessages;
	const changes = getToolChanges(getDeclaredTools(context.messages, baseline), context.tools ?? []);
	const unchanged = changes.toolsAdded.length === 0 && changes.toolsRemoved.length === 0;

	if (pending) {
		// Keep the caller's message object when it already declares no tool changes.
		if (unchanged && !pending.toolsAdded?.length && !pending.toolsRemoved?.length) return pendingMessages;
		return baseline.map((message, index) => (index === systemIndex ? withToolChanges(pending, changes) : message));
	}
	if (unchanged) return pendingMessages;
	const update = withToolChanges({ role: "system", content: "", timestamp: Date.now() }, changes);
	const insertIndex = pendingMessages.findIndex((message) => message.role !== "system");
	const index = insertIndex === -1 ? pendingMessages.length : insertIndex;
	return [...pendingMessages.slice(0, index), update, ...pendingMessages.slice(index)];
}

const NO_CHANGES: ToolStateChanges = { toolsAdded: [], toolsRemoved: [] };

/** `getCurrentTools([...transcript, ...pending])` without copying the transcript: the tools its system messages declare. */
function getDeclaredTools(transcript: readonly AgentMessage[], pending: readonly AgentMessage[]): Tool[] {
	const tools = new Map<string, Tool>();
	for (const messages of [transcript, pending]) {
		for (const message of messages) {
			if (message.role !== "system") continue;
			for (const tool of (message as SystemMessage).toolsRemoved ?? []) tools.delete(tool.name);
			for (const tool of (message as SystemMessage).toolsAdded ?? []) tools.set(tool.name, tool);
		}
	}
	return [...tools.values()];
}

/**
 * `JSON.stringify(toToolDeclaration(tool))`, which is what `declarationsEqual` compares, with one serialization instead of
 * a JSON round-trip and a serialization: serializing the parsed copy of `parameters` gives the same text as serializing
 * `parameters`. Computed again on every request, so a schema changed in place is seen. Parameters that do not serialize to
 * JSON text (undefined, a function, or a `toJSON` method) take the original path, which throws the same errors.
 */
function declarationKey(tool: Tool): string {
	const parameters: unknown = tool.parameters;
	if (
		parameters === undefined ||
		typeof parameters === "function" ||
		typeof parameters === "symbol" ||
		typeof (parameters as { toJSON?: unknown } | null)?.toJSON === "function"
	) {
		return JSON.stringify(toToolDeclaration(tool));
	}
	return JSON.stringify({
		name: tool.name,
		description: tool.description,
		parameters,
		...(tool.constrainedSampling === undefined ? {} : { constrainedSampling: tool.constrainedSampling }),
	});
}

/**
 * `getToolStateChanges(previous, current.map(toToolDeclaration))`, serializing each declaration once instead of round-tripping
 * and serializing both sides of every comparison again (about nine JSON passes per tool per request).
 */
function getToolChanges(previous: readonly Tool[], current: readonly Tool[]): ToolStateChanges {
	const currentKeys = current.map(declarationKey);
	const previousKeys = new Map<Tool, string>();
	const previousKey = (tool: Tool): string => {
		let key = previousKeys.get(tool);
		if (key === undefined) {
			key = declarationKey(tool);
			previousKeys.set(tool, key);
		}
		return key;
	};
	const previousTools = new Map(previous.map((tool) => [tool.name, tool]));
	const currentIndexes = new Map(current.map((tool, index) => [tool.name, index]));
	return {
		toolsAdded: current
			.filter((tool, index) => {
				const previousTool = previousTools.get(tool.name);
				return previousTool === undefined || previousKey(previousTool) !== currentKeys[index];
			})
			.map(toToolDeclaration),
		toolsRemoved: previous
			.filter((tool) => {
				const currentIndex = currentIndexes.get(tool.name);
				return currentIndex === undefined || previousKey(tool) !== currentKeys[currentIndex];
			})
			.map((tool) => ({ name: tool.name })),
	};
}

/** Copy a system message with its tool fields replaced by `changes`; empty lists omit the field. */
function withToolChanges(message: SystemMessage, { toolsAdded, toolsRemoved }: ToolStateChanges): SystemMessage {
	const { toolsAdded: _added, toolsRemoved: _removed, ...rest } = message;
	return {
		...rest,
		...(toolsAdded.length > 0 ? { toolsAdded } : {}),
		...(toolsRemoved.length > 0 ? { toolsRemoved } : {}),
	};
}

/**
 * Stream an assistant response from the LLM.
 * This is where AgentMessage[] gets transformed to Message[] for the LLM.
 */
async function streamAssistantResponse(
	context: AgentContext,
	config: AgentLoopConfig,
	signal: AbortSignal | undefined,
	emit: AgentEventSink,
	streamFunction: StreamFn,
	earlyToolCalls: EarlyToolCalls | undefined,
): Promise<AssistantMessage> {
	// Apply context transform if configured (AgentMessage[] → AgentMessage[])
	let messages = context.messages;
	if (config.transformContext) {
		messages = await config.transformContext(messages, signal);
	}

	// Convert to LLM-compatible messages (AgentMessage[] → Message[])
	const llmMessages = await config.convertToLlm(messages);

	const llmContext = normalizeContext({ messages: llmMessages });

	// Resolve API key (important for expiring tokens)
	const resolvedApiKey =
		(config.getApiKey ? await config.getApiKey(config.model.provider) : undefined) || config.apiKey;

	const response = await streamFunction(config.model, llmContext, {
		...config,
		apiKey: resolvedApiKey,
		identity: mergeClientIdentity(DEFAULT_AGENT_IDENTITY, config.identity),
		signal,
	});
	// Record the requested level, whichever stream function answered.
	const result = async () => Object.assign(await response.result(), { thinkingLevel: config.reasoning ?? "off" });

	let partialMessage: AssistantMessage | null = null;
	let addedPartial = false;

	for await (const event of response) {
		switch (event.type) {
			case "start":
				partialMessage = event.partial;
				context.messages.push(partialMessage);
				addedPartial = true;
				await emit({ type: "message_start", message: { ...partialMessage } });
				break;

			case "text_start":
			case "text_delta":
			case "text_end":
			case "thinking_start":
			case "thinking_delta":
			case "thinking_end":
			case "toolcall_start":
			case "toolcall_delta":
			case "toolcall_end":
				if (partialMessage) {
					partialMessage = event.partial;
					context.messages[context.messages.length - 1] = partialMessage;
					if (
						earlyToolCalls &&
						(event.type === "toolcall_start" || event.type === "toolcall_delta" || event.type === "toolcall_end")
					) {
						earlyToolCalls.observe(event);
					}
					// One event per delta: await only a sink that returned a promise.
					const pending = emit({
						type: "message_update",
						assistantMessageEvent: event,
						message: { ...partialMessage },
					}) as Promise<void> | undefined;
					if (pending) await pending;
				}
				break;

			case "done":
			case "error": {
				const finalMessage = await result();
				if (addedPartial) {
					context.messages[context.messages.length - 1] = finalMessage;
				} else {
					context.messages.push(finalMessage);
				}
				if (!addedPartial) {
					await emit({ type: "message_start", message: { ...finalMessage } });
				}
				await emit({ type: "message_end", message: finalMessage });
				return finalMessage;
			}
		}
	}

	const finalMessage = await result();
	if (addedPartial) {
		context.messages[context.messages.length - 1] = finalMessage;
	} else {
		context.messages.push(finalMessage);
		await emit({ type: "message_start", message: { ...finalMessage } });
	}
	await emit({ type: "message_end", message: finalMessage });
	return finalMessage;
}

/**
 * Fail all tool calls from an assistant message that was truncated by the
 * output token limit. Streamed tool-call arguments are finalized with a
 * best-effort JSON salvage parser, so a truncated message can yield tool calls
 * whose arguments parse and validate but are silently incomplete. None of them
 * are safe to execute; report each as an error so the model can re-issue them.
 */
async function failToolCallsFromTruncatedMessage(
	toolCalls: AgentToolCall[],
	emit: AgentEventSink,
): Promise<ExecutedToolCallBatch> {
	const messages: ToolResultMessage[] = [];
	for (const toolCall of toolCalls) {
		await emit({
			type: "tool_execution_start",
			toolCallId: toolCall.id,
			toolName: toolCall.name,
			args: toolCall.arguments,
		});
		const finalized: FinalizedToolCallOutcome = {
			toolCall,
			result: createErrorToolResult(
				`Tool call "${toolCall.name}" was not executed: the response hit the output token limit, so its arguments may be truncated. Re-issue the tool call with complete arguments.`,
			),
			isError: true,
		};
		await emitToolExecutionEnd(finalized, emit);
		const toolResultMessage = createToolResultMessage(finalized);
		await emitToolResultMessage(toolResultMessage, emit);
		messages.push(toolResultMessage);
	}
	return { messages, terminate: false };
}

/**
 * Execute tool calls from an assistant message.
 */
async function executeToolCalls(
	currentContext: AgentContext,
	assistantMessage: AssistantMessage,
	config: AgentLoopConfig,
	signal: AbortSignal | undefined,
	emit: AgentEventSink,
	earlyToolCalls: EarlyToolCalls | undefined,
): Promise<ExecutedToolCallBatch> {
	const toolCalls = assistantMessage.content.filter((c) => c.type === "toolCall");
	const hasSequentialToolCall = toolCalls.some(
		(tc) => currentContext.tools?.find((t) => t.name === tc.name)?.executionMode === "sequential",
	);
	if (config.toolExecution === "sequential" || hasSequentialToolCall) {
		return executeToolCallsSequential(
			currentContext,
			assistantMessage,
			toolCalls,
			config,
			signal,
			emit,
			earlyToolCalls,
		);
	}
	return executeToolCallsParallel(currentContext, assistantMessage, toolCalls, config, signal, emit, earlyToolCalls);
}

type ExecutedToolCallBatch = {
	messages: ToolResultMessage[];
	terminate: boolean;
};

async function executeToolCallsSequential(
	currentContext: AgentContext,
	assistantMessage: AssistantMessage,
	toolCalls: AgentToolCall[],
	config: AgentLoopConfig,
	signal: AbortSignal | undefined,
	emit: AgentEventSink,
	earlyToolCalls: EarlyToolCalls | undefined,
): Promise<ExecutedToolCallBatch> {
	const finalizedCalls: FinalizedToolCallOutcome[] = [];
	const messages: ToolResultMessage[] = [];

	for (const toolCall of toolCalls) {
		await emit({
			type: "tool_execution_start",
			toolCallId: toolCall.id,
			toolName: toolCall.name,
			args: toolCall.arguments,
		});

		const preparation = await prepareToolCall(currentContext, assistantMessage, toolCall, config, signal);
		let finalized: FinalizedToolCallOutcome;
		if (preparation.kind === "immediate") {
			earlyToolCalls?.drop(toolCall.id);
			finalized = {
				toolCall,
				result: preparation.result,
				isError: preparation.isError,
			};
		} else {
			const onUpdate = emitToolExecutionUpdate(toolCall, emit);
			const early = earlyToolCalls?.take(preparation, onUpdate);
			const executed = early ? await early : await executePreparedToolCall(preparation, signal, onUpdate);
			finalized = await finalizeExecutedToolCall(
				currentContext,
				assistantMessage,
				preparation,
				executed,
				config,
				signal,
			);
		}

		await emitToolExecutionEnd(finalized, emit);
		const toolResultMessage = createToolResultMessage(finalized);
		await emitToolResultMessage(toolResultMessage, emit);
		finalizedCalls.push(finalized);
		messages.push(toolResultMessage);
	}

	return {
		messages,
		terminate: shouldTerminateToolBatch(finalizedCalls),
	};
}

async function executeToolCallsParallel(
	currentContext: AgentContext,
	assistantMessage: AssistantMessage,
	toolCalls: AgentToolCall[],
	config: AgentLoopConfig,
	signal: AbortSignal | undefined,
	emit: AgentEventSink,
	earlyToolCalls: EarlyToolCalls | undefined,
): Promise<ExecutedToolCallBatch> {
	const finalizedCalls: FinalizedToolCallEntry[] = [];

	for (const toolCall of toolCalls) {
		await emit({
			type: "tool_execution_start",
			toolCallId: toolCall.id,
			toolName: toolCall.name,
			args: toolCall.arguments,
		});

		const preparation = await prepareToolCall(currentContext, assistantMessage, toolCall, config, signal);
		if (preparation.kind === "immediate") {
			earlyToolCalls?.drop(toolCall.id);
			const finalized = {
				toolCall,
				result: preparation.result,
				isError: preparation.isError,
			} satisfies FinalizedToolCallOutcome;
			await emitToolExecutionEnd(finalized, emit);
			finalizedCalls.push(finalized);
			continue;
		}

		finalizedCalls.push(async () => {
			if (signal?.aborted) {
				const finalized = {
					toolCall,
					result: createErrorToolResult("Operation aborted"),
					isError: true,
				} satisfies FinalizedToolCallOutcome;
				await emitToolExecutionEnd(finalized, emit);
				return finalized;
			}
			const onUpdate = emitToolExecutionUpdate(toolCall, emit);
			const early = earlyToolCalls?.take(preparation, onUpdate);
			const executed = early ? await early : await executePreparedToolCall(preparation, signal, onUpdate);
			const finalized = await finalizeExecutedToolCall(
				currentContext,
				assistantMessage,
				preparation,
				executed,
				config,
				signal,
			);
			await emitToolExecutionEnd(finalized, emit);
			return finalized;
		});
	}

	const orderedFinalizedCalls = await Promise.all(
		finalizedCalls.map((entry) => (typeof entry === "function" ? entry() : Promise.resolve(entry))),
	);
	const messages: ToolResultMessage[] = [];
	for (const finalized of orderedFinalizedCalls) {
		const toolResultMessage = createToolResultMessage(finalized);
		await emitToolResultMessage(toolResultMessage, emit);
		messages.push(toolResultMessage);
	}

	return {
		messages,
		terminate: shouldTerminateToolBatch(orderedFinalizedCalls),
	};
}

type PreparedToolCall = {
	kind: "prepared";
	toolCall: AgentToolCall;
	tool: AgentTool<any>;
	args: unknown;
};

type ImmediateToolCallOutcome = {
	kind: "immediate";
	result: AgentToolResult<any>;
	isError: boolean;
};

type ExecutedToolCallOutcome = {
	result: AgentToolResult<any>;
	isError: boolean;
	durationMs: number;
};

type FinalizedToolCallOutcome = AgentToolCallOutcome;

/** The `beforeToolCall` and `afterToolCall` hooks of {@link AgentLoopConfig}. */
export type ToolCallHooks = Pick<AgentLoopConfig, "beforeToolCall" | "afterToolCall">;

type ToolUpdateSink = (partialResult: AgentToolResult<any>) => Promise<void> | void;

type FinalizedToolCallEntry = FinalizedToolCallOutcome | (() => Promise<FinalizedToolCallOutcome>);

function shouldTerminateToolBatch(finalizedCalls: FinalizedToolCallOutcome[]): boolean {
	return finalizedCalls.length > 0 && finalizedCalls.every((finalized) => finalized.result.terminate === true);
}

function prepareToolCallArguments(tool: AgentTool<any>, toolCall: AgentToolCall): AgentToolCall {
	if (!tool.prepareArguments) {
		return toolCall;
	}
	const preparedArguments = tool.prepareArguments(toolCall.arguments);
	if (preparedArguments === toolCall.arguments) {
		return toolCall;
	}
	return {
		...toolCall,
		arguments: preparedArguments as Record<string, any>,
	};
}

async function prepareToolCall(
	currentContext: AgentContext,
	assistantMessage: AssistantMessage,
	toolCall: AgentToolCall,
	config: ToolCallHooks,
	signal: AbortSignal | undefined,
	tools: readonly AgentTool<any>[] = currentContext.tools ?? [],
): Promise<PreparedToolCall | ImmediateToolCallOutcome> {
	// Once aborted, skip preparation and the hooks; callers still record this result, so no call
	// is left without one (#9986).
	if (signal?.aborted) {
		return {
			kind: "immediate",
			result: createErrorToolResult("Operation aborted"),
			isError: true,
		};
	}
	const tool = tools.find((t) => t.name === toolCall.name);
	if (!tool) {
		return {
			kind: "immediate",
			result: createErrorToolResult(`Tool ${toolCall.name} not found`),
			isError: true,
		};
	}

	try {
		const preparedToolCall = prepareToolCallArguments(tool, toolCall);
		const validatedArgs = validateToolArguments(tool, preparedToolCall);
		if (config.beforeToolCall) {
			const beforeResult = await config.beforeToolCall(
				{
					assistantMessage,
					toolCall,
					args: validatedArgs,
					context: currentContext,
				},
				signal,
			);
			if (signal?.aborted) {
				return {
					kind: "immediate",
					result: createErrorToolResult("Operation aborted"),
					isError: true,
				};
			}
			if (beforeResult?.block) {
				const result = createErrorToolResult(beforeResult.reason || "Tool execution was blocked");
				if (beforeResult.terminate === true) {
					result.terminate = true;
				}
				return {
					kind: "immediate",
					result,
					isError: true,
				};
			}
		}
		return {
			kind: "prepared",
			toolCall,
			tool,
			args: validatedArgs,
		};
	} catch (error) {
		return {
			kind: "immediate",
			result: createErrorToolResult(error instanceof Error ? error.message : String(error)),
			isError: true,
		};
	}
}

function emitToolExecutionUpdate(toolCall: AgentToolCall, emit: AgentEventSink): ToolUpdateSink {
	return (partialResult) =>
		emit({
			type: "tool_execution_update",
			toolCallId: toolCall.id,
			toolName: toolCall.name,
			args: toolCall.arguments,
			partialResult,
		});
}

/** Options for {@link runToolCall}. */
export interface RunToolCallOptions extends ToolCallHooks {
	/** Tools the call resolves against. */
	tools: readonly AgentTool<any>[];
	/** Passed to the hooks as the message that issued the call. */
	assistantMessage: AssistantMessage;
	/** Passed to the hooks as the current agent context. */
	context: AgentContext;
	signal?: AbortSignal;
	onUpdate?: ToolUpdateSink;
}

/**
 * Run one tool call through the same steps as a model-issued call: argument preparation, schema
 * validation, `beforeToolCall`, execution, and `afterToolCall`. Emits no events and adds no
 * messages. Tools that call other tools use this so the hooks (for example permission checks)
 * apply to those calls too.
 *
 * Never rejects for tool failures: an aborted `signal`, unknown tools, validation errors, blocked
 * calls, and thrown errors come back as `isError: true`. An aborted `signal` is checked first,
 * before the hooks run.
 */
export async function runToolCall(toolCall: AgentToolCall, options: RunToolCallOptions): Promise<AgentToolCallOutcome> {
	const { assistantMessage, context, signal } = options;
	const preparation = await prepareToolCall(context, assistantMessage, toolCall, options, signal, options.tools);
	if (preparation.kind === "immediate") {
		return { toolCall, result: preparation.result, isError: preparation.isError };
	}
	const executed = await executePreparedToolCall(preparation, signal, options.onUpdate ?? (() => {}));
	return finalizeExecutedToolCall(context, assistantMessage, preparation, executed, options, signal);
}

async function executePreparedToolCall(
	prepared: PreparedToolCall,
	signal: AbortSignal | undefined,
	onUpdate: ToolUpdateSink,
): Promise<ExecutedToolCallOutcome> {
	const updateEvents: Promise<void>[] = [];
	let acceptingUpdates = true;
	const startedAt = performance.now();
	const elapsed = () => Math.round(performance.now() - startedAt);

	try {
		const result = await prepared.tool.execute(
			prepared.toolCall.id,
			prepared.args as never,
			signal,
			(partialResult) => {
				if (!acceptingUpdates) return;
				updateEvents.push(Promise.resolve(onUpdate(partialResult)));
			},
		);
		const durationMs = elapsed();
		acceptingUpdates = false;
		await Promise.all(updateEvents);
		return { result, isError: result.isError === true, durationMs };
	} catch (error) {
		const durationMs = elapsed();
		acceptingUpdates = false;
		await Promise.all(updateEvents);
		return {
			result: createErrorToolResult(error instanceof Error ? error.message : String(error)),
			isError: true,
			durationMs,
		};
	} finally {
		acceptingUpdates = false;
	}
}

async function finalizeExecutedToolCall(
	currentContext: AgentContext,
	assistantMessage: AssistantMessage,
	prepared: PreparedToolCall,
	executed: ExecutedToolCallOutcome,
	config: ToolCallHooks,
	signal: AbortSignal | undefined,
): Promise<FinalizedToolCallOutcome> {
	let result = executed.result;
	let isError = executed.isError;

	if (config.afterToolCall) {
		try {
			const afterResult = await config.afterToolCall(
				{
					assistantMessage,
					toolCall: prepared.toolCall,
					args: prepared.args,
					result,
					isError,
					context: currentContext,
				},
				signal,
			);
			if (afterResult) {
				// Structured content not replaced along with the content may no longer match it.
				const structuredContent =
					afterResult.structuredContent ?? (afterResult.content ? undefined : result.structuredContent);
				result = {
					...result,
					content: afterResult.content ?? result.content,
					details: afterResult.details ?? result.details,
					usage: afterResult.usage ?? result.usage,
					terminate: afterResult.terminate ?? result.terminate,
				};
				if (structuredContent === undefined) delete result.structuredContent;
				else result.structuredContent = structuredContent;
				isError = afterResult.isError ?? isError;
			}
		} catch (error) {
			result = createErrorToolResult(error instanceof Error ? error.message : String(error));
			isError = true;
		}
	}

	return {
		toolCall: prepared.toolCall,
		result,
		isError,
		durationMs: executed.durationMs,
	};
}

function createErrorToolResult(message: string): AgentToolResult<any> {
	return {
		content: [{ type: "text", text: message }],
		details: {},
	};
}

async function emitToolExecutionEnd(finalized: FinalizedToolCallOutcome, emit: AgentEventSink): Promise<void> {
	await emit({
		type: "tool_execution_end",
		toolCallId: finalized.toolCall.id,
		toolName: finalized.toolCall.name,
		result: finalized.result,
		isError: finalized.isError,
		...(finalized.durationMs === undefined ? {} : { durationMs: finalized.durationMs }),
	});
}

function createToolResultMessage(finalized: FinalizedToolCallOutcome): ToolResultMessage {
	return {
		role: "toolResult",
		toolCallId: finalized.toolCall.id,
		toolName: finalized.toolCall.name,
		// Untyped tools (JS extensions) can return results without content; normalize
		// so the null never enters session history or provider payloads.
		content: finalized.result.content ?? [],
		details: finalized.result.details,
		usage: finalized.result.usage,
		isError: finalized.isError,
		...(finalized.durationMs === undefined ? {} : { durationMs: finalized.durationMs }),
		timestamp: Date.now(),
	};
}

async function emitToolResultMessage(toolResultMessage: ToolResultMessage, emit: AgentEventSink): Promise<void> {
	await emit({ type: "message_start", message: toolResultMessage });
	await emit({ type: "message_end", message: toolResultMessage });
}

type ToolCallStreamEvent = Extract<
	AssistantMessageEvent,
	{ type: "toolcall_start" } | { type: "toolcall_delta" } | { type: "toolcall_end" }
>;

/** A tool call started before its assistant message ended. */
type EarlyToolCall = {
	id: string;
	name: string;
	tool: AgentTool<any>;
	/** The call's arguments as JSON, to see whether the provider's final arguments are the ones it started with. */
	argumentsJson: string;
	/** The validated arguments it runs with, as JSON. */
	argsJson: string;
	stillValid: () => boolean;
	controller: AbortController;
	outcome: Promise<ExecutedToolCallOutcome>;
	/** Where updates go once the loop reaches the call (`sink`); until then they are kept (`buffered`). */
	updates: { sink: ToolUpdateSink | undefined; buffered: AgentToolResult<any>[] };
};

/**
 * The tool calls of one assistant message that start while the message is still streaming (see
 * {@link EarlyToolExecution}). A call starts once its arguments are complete and every tool call before it in the
 * message has started; the first call that cannot start early ends early starts for the rest of the message.
 */
class EarlyToolCalls {
	private readonly tools: readonly AgentTool<any>[];
	private readonly decide: EarlyToolExecution;
	private readonly signal: AbortSignal | undefined;
	/** Streamed JSON arguments of calls whose arguments are not complete yet, by content index. */
	private readonly streamedArguments = new Map<number, { json: string; endsWithBrace: boolean }>();
	/** Calls with complete arguments, waiting for the calls before them to start, by content index. */
	private readonly complete = new Map<number, AgentToolCall>();
	/** Started calls by content index. */
	private readonly started = new Map<number, EarlyToolCall>();
	/** Started calls the loop has not reached yet, by tool call id. */
	private readonly runs = new Map<string, EarlyToolCall>();
	/** Content indexes of calls seen to be candidates (their tool may start early). */
	private readonly candidates = new Set<number>();
	/** Controllers of the runs that have not settled, taken or not: an abort of the agent run aborts them all. */
	private readonly unsettled = new Set<AbortController>();
	private closed = false;
	private listening = false;
	private readonly onAbort = () => {
		for (const controller of this.unsettled) controller.abort();
		this.discard();
	};

	constructor(tools: readonly AgentTool<any>[], decide: EarlyToolExecution, signal: AbortSignal | undefined) {
		this.tools = tools;
		this.decide = decide;
		this.signal = signal;
	}

	/** Follow a tool call event of the streaming message. */
	observe(event: ToolCallStreamEvent): void {
		if (this.closed) return;
		const index = event.contentIndex;
		if (event.type === "toolcall_end") {
			this.streamedArguments.delete(index);
			const toolCall: AgentToolCall = { ...event.toolCall, arguments: cloneJson(event.toolCall.arguments) };
			const started = this.started.get(index);
			if (started) {
				// Started from its streamed arguments: keep it if the provider's final arguments are the same.
				if (started.id === toolCall.id && started.argumentsJson === JSON.stringify(toolCall.arguments)) return;
				this.started.delete(index);
				this.drop(started.id);
			}
			this.complete.set(index, toolCall);
			this.startReady(event.partial);
			return;
		}
		if (this.started.has(index) || this.complete.has(index)) return;
		const block = event.partial.content[index];
		if (block?.type !== "toolCall") return;
		let streamed = this.streamedArguments.get(index);
		if (event.type === "toolcall_delta") {
			streamed ??= { json: "", endsWithBrace: false };
			streamed.json += event.delta;
			const delta = event.delta.trimEnd();
			if (delta) streamed.endsWithBrace = delta.endsWith("}");
			this.streamedArguments.set(index, streamed);
		}
		if (!block.name) return;
		if (!this.candidates.has(index)) {
			// A call that cannot start early means no later call may, so stop following the message.
			const tool = this.tools.find((t) => t.name === block.name);
			if (!tool || !this.decide(block, tool)) {
				this.close();
				return;
			}
			this.candidates.add(index);
		}
		// A complete JSON object cannot be continued: these are the call's arguments, unless the provider changes them.
		if (!streamed?.endsWithBrace || !block.id) return;
		let args: unknown;
		try {
			args = JSON.parse(streamed.json);
		} catch {
			return;
		}
		if (typeof args !== "object" || args === null || Array.isArray(args)) return;
		this.streamedArguments.delete(index);
		this.complete.set(index, { type: "toolCall", id: block.id, name: block.name, arguments: args as JsonObject });
		this.startReady(event.partial);
	}

	/**
	 * The early run of `prepared` in place of executing it, or undefined to execute it as usual. Updates the run
	 * reported so far go to `onUpdate` now, later ones as they come.
	 */
	take(prepared: PreparedToolCall, onUpdate: ToolUpdateSink): Promise<ExecutedToolCallOutcome> | undefined {
		const run = this.runs.get(prepared.toolCall.id);
		if (!run) return undefined;
		this.runs.delete(run.id);
		if (
			run.tool !== prepared.tool ||
			run.name !== prepared.toolCall.name ||
			this.signal?.aborted ||
			run.controller.signal.aborted ||
			run.argsJson !== JSON.stringify(prepared.args) ||
			!run.stillValid()
		) {
			run.controller.abort();
			return undefined;
		}
		run.updates.sink = onUpdate;
		const flushed = run.updates.buffered.map((partialResult) => Promise.resolve(onUpdate(partialResult)));
		run.updates.buffered = [];
		return flushed.length === 0
			? run.outcome
			: run.outcome.then(async (outcome) => {
					await Promise.all(flushed);
					return outcome;
				});
	}

	/** Abort the early run of a call that will not execute. */
	drop(toolCallId: string): void {
		const run = this.runs.get(toolCallId);
		if (!run) return;
		this.runs.delete(toolCallId);
		run.controller.abort();
	}

	/**
	 * Abort every early run the loop has not taken. Called when the message's tool calls are done (taken runs have
	 * settled then) or will not run.
	 */
	discard(): void {
		this.close();
		for (const run of this.runs.values()) run.controller.abort();
		this.runs.clear();
		if (this.listening) {
			this.listening = false;
			this.signal?.removeEventListener("abort", this.onAbort);
		}
	}

	private close(): void {
		this.closed = true;
		this.streamedArguments.clear();
		this.complete.clear();
	}

	/** Start the calls with complete arguments whose earlier calls have all started, in message order. */
	private startReady(partial: AssistantMessage): void {
		const content = partial.content;
		for (let index = 0; index < content.length; index++) {
			if (content[index].type !== "toolCall" || this.started.has(index)) continue;
			const toolCall = this.complete.get(index);
			if (!toolCall) return;
			this.complete.delete(index);
			const run = this.start(toolCall);
			if (!run) {
				this.close();
				return;
			}
			this.started.set(index, run);
		}
	}

	private start(toolCall: AgentToolCall): EarlyToolCall | undefined {
		if (!toolCall.id || this.runs.has(toolCall.id) || this.signal?.aborted) return undefined;
		const tool = this.tools.find((t) => t.name === toolCall.name);
		if (!tool) return undefined;
		const stillValid = this.decide(toolCall, tool);
		if (!stillValid) return undefined;
		let args: unknown;
		try {
			args = validateToolArguments(tool, prepareToolCallArguments(tool, toolCall));
		} catch {
			return undefined;
		}
		if (this.signal && !this.listening) {
			this.listening = true;
			this.signal.addEventListener("abort", this.onAbort, { once: true });
		}
		const controller = new AbortController();
		const updates: EarlyToolCall["updates"] = { sink: undefined, buffered: [] };
		const argsJson = JSON.stringify(args);
		this.unsettled.add(controller);
		const outcome = executePreparedToolCall(
			{ kind: "prepared", toolCall, tool, args },
			controller.signal,
			(partial) => {
				if (updates.sink) return updates.sink(partial);
				updates.buffered.push(partial);
			},
		);
		void outcome.then(() => this.unsettled.delete(controller));
		const run: EarlyToolCall = {
			id: toolCall.id,
			name: toolCall.name,
			tool,
			argumentsJson: JSON.stringify(toolCall.arguments),
			argsJson,
			stillValid,
			controller,
			outcome,
			updates,
		};
		this.runs.set(run.id, run);
		return run;
	}
}

function cloneJson<T>(value: T): T {
	return value === undefined ? value : JSON.parse(JSON.stringify(value));
}
