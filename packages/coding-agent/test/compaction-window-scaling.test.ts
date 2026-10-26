import type { Model } from "@earendil-works/pi-ai";
import { describe, expect, it } from "vitest";
import { getCompactionThreshold, getSummaryMaxTokens, shouldCompact } from "../src/core/compaction/index.ts";
import type { Settings } from "../src/core/settings-manager.ts";
import { InMemorySettingsStorage, SettingsManager } from "../src/core/settings-manager.ts";

function model(contextWindow: number, maxTokens = 128_000): Model<string> {
	return {
		id: `model-${contextWindow}`,
		name: "model",
		api: "faux",
		provider: "provider",
		baseUrl: "http://localhost",
		reasoning: false,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow,
		maxTokens,
	};
}

/** Threshold, kept recent tokens and summary caps that `settings` give for a `contextWindow`-token model. */
function budgets(settings: Settings["compaction"], contextWindow: number, maxTokens?: number) {
	const target = model(contextWindow, maxTokens);
	const resolved = SettingsManager.inMemory({ compaction: settings }).getCompactionSettings(target);
	return {
		threshold: getCompactionThreshold(contextWindow, resolved),
		keepRecentTokens: resolved.keepRecentTokens,
		summary: getSummaryMaxTokens(target, resolved),
		turnPrefix: getSummaryMaxTokens(target, resolved, "turnPrefix"),
	};
}

describe("window-scaled compaction budgets", () => {
	it.each([
		// 128K and smaller: as before the defaults scaled.
		{ window: 128_000, threshold: 111_616, keepRecentTokens: 20_000, summary: 13_107, turnPrefix: 8_192 },
		{ window: 131_072, threshold: 114_688, keepRecentTokens: 20_000, summary: 13_107, turnPrefix: 8_192 },
		{ window: 200_000, threshold: 180_000, keepRecentTokens: 20_000, summary: 13_107, turnPrefix: 8_192 },
		{ window: 400_000, threshold: 360_000, keepRecentTokens: 20_000, summary: 13_107, turnPrefix: 8_192 },
		{ window: 1_000_000, threshold: 900_000, keepRecentTokens: 50_000, summary: 32_000, turnPrefix: 16_000 },
	])("defaults for a $window-token window", ({ window, ...expected }) => {
		expect(budgets(undefined, window)).toEqual(expected);
	});

	it.each([
		{ window: 128_000, threshold: 111_616, summary: 13_107 },
		{ window: 200_000, threshold: 183_616, summary: 13_107 },
		{ window: 1_000_000, threshold: 983_616, summary: 13_107 },
	])("explicit reserveTokens keeps the old threshold and summary cap at $window", ({ window, threshold, summary }) => {
		const settings = { reserveTokens: 16_384 };
		expect(budgets(settings, window)).toMatchObject({ threshold, summary, turnPrefix: 8_192 });
		const resolved = SettingsManager.inMemory({ compaction: settings }).getCompactionSettings(model(window));
		expect(resolved).not.toHaveProperty("thresholdPercent");
		expect(resolved).not.toHaveProperty("scaleSummaryWithContextWindow");
		// The old trigger: contextTokens > contextWindow - reserveTokens.
		expect(shouldCompact(window - 16_384, window, resolved)).toBe(false);
		expect(shouldCompact(window - 16_383, window, resolved)).toBe(true);
	});

	it("explicit reserveTokens still scales an unset keepRecentTokens", () => {
		expect(budgets({ reserveTokens: 50_000 }, 1_000_000)).toEqual({
			threshold: 950_000,
			keepRecentTokens: 50_000,
			summary: 40_000,
			turnPrefix: 25_000,
		});
	});

	it.each([128_000, 200_000, 1_000_000])("explicit keepRecentTokens is kept as set at %i", (window) => {
		expect(budgets({ keepRecentTokens: 20_000 }, window).keepRecentTokens).toBe(20_000);
		expect(budgets({ keepRecentTokens: 0 }, window).keepRecentTokens).toBe(0);
	});

	it.each([
		{ window: 128_000, thresholdPercent: 80, threshold: 102_400 },
		// Above contextWindow - reserveTokens, so the reserve still decides.
		{ window: 128_000, thresholdPercent: 95, threshold: 111_616 },
		{ window: 200_000, thresholdPercent: 80, threshold: 160_000 },
		{ window: 1_000_000, thresholdPercent: 95, threshold: 950_000 },
		{ window: 1_000_000, thresholdPercent: 100, threshold: 983_616 },
	])("thresholdPercent $thresholdPercent at $window", ({ window, thresholdPercent, threshold }) => {
		expect(budgets({ thresholdPercent }, window).threshold).toBe(threshold);
		// With an explicit reserveTokens too, the lower of the two limits applies.
		expect(budgets({ thresholdPercent, reserveTokens: 16_384 }, window).threshold).toBe(threshold);
	});

	it.each([128_000, 200_000, 1_000_000])("explicit summaryMaxTokens is used as set at %i", (window) => {
		expect(budgets({ summaryMaxTokens: 20_000 }, window)).toMatchObject({ summary: 20_000, turnPrefix: 10_000 });
		expect(budgets({ summaryMaxTokens: 2_001, reserveTokens: 16_384 }, window)).toMatchObject({
			summary: 2_001,
			turnPrefix: 1_001,
		});
	});

	it("caps every summary by the model's output limit", () => {
		expect(budgets(undefined, 1_000_000, 16_384)).toMatchObject({ summary: 16_384, turnPrefix: 16_000 });
		expect(budgets({ summaryMaxTokens: 50_000 }, 1_000_000, 16_384)).toMatchObject({
			summary: 16_384,
			turnPrefix: 16_384,
		});
	});

	it("does not scale without a context window", () => {
		const manager = SettingsManager.inMemory();
		expect(manager.getCompactionSettings({ provider: "provider", id: "model" }).keepRecentTokens).toBe(20_000);
		expect(manager.getCompactionSettings(model(0)).keepRecentTokens).toBe(20_000);
		expect(getSummaryMaxTokens(model(0), manager.getCompactionSettings(model(0)))).toBe(13_107);
	});

	it("resolves new settings through model overrides", () => {
		const target = model(1_000_000);
		const manager = SettingsManager.inMemory({
			compaction: {
				thresholdPercent: 80,
				modelOverrides: { [`${target.provider}/${target.id}`]: { thresholdPercent: 95, summaryMaxTokens: 24_000 } },
			},
		});
		expect(manager.getCompactionSettings(target)).toEqual({
			enabled: true,
			reserveTokens: 16_384,
			keepRecentTokens: 50_000,
			thresholdPercent: 95,
			summaryMaxTokens: 24_000,
		});
		expect(manager.getCompactionSettings(model(200_000))).toMatchObject({ thresholdPercent: 80 });
	});

	it("treats a reserveTokens model override as explicit", () => {
		const target = model(1_000_000);
		const manager = SettingsManager.inMemory({
			compaction: { modelOverrides: { [`${target.provider}/${target.id}`]: { reserveTokens: 16_384 } } },
		});
		expect(manager.getCompactionSettings(target)).toEqual({
			enabled: true,
			reserveTokens: 16_384,
			keepRecentTokens: 50_000,
		});
		expect(manager.getCompactionSettings(model(200_000))).toMatchObject({
			thresholdPercent: 90,
			scaleSummaryWithContextWindow: true,
		});
	});

	it("uses the given context window over the model's", () => {
		const manager = SettingsManager.inMemory();
		expect(manager.getCompactionSettings(model(128_000), 1_000_000).keepRecentTokens).toBe(50_000);
	});

	it.each([
		["thresholdPercent", 0, "an integer from 1 to 100"],
		["thresholdPercent", 101, "an integer from 1 to 100"],
		["thresholdPercent", 90.5, "an integer from 1 to 100"],
		["summaryMaxTokens", 0, `an integer from 1 to ${Number.MAX_SAFE_INTEGER}`],
		["summaryMaxTokens", "4096", `an integer from 1 to ${Number.MAX_SAFE_INTEGER}`],
	] as const)("reports invalid %s %j", (field, value, range) => {
		const storage = new InMemorySettingsStorage();
		storage.withLock("global", () => JSON.stringify({ compaction: { [field]: value } }));
		expect(() => SettingsManager.fromStorage(storage).getCompactionSettings()).toThrow(
			`Invalid compaction.${field} setting: ${String(value)}. Expected ${range}.`,
		);
	});
});
