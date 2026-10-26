import type { ThinkingLevel } from "@earendil-works/pi-agent-core";
import type { Model } from "@earendil-works/pi-ai";
import type { ScrollViewScrollbar, TerminalCapabilities, WheelScrollLines } from "@earendil-works/pi-tui";
import { randomUUID } from "crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "fs";
import { dirname, join } from "path";
import lockfile from "proper-lockfile";
import { CONFIG_DIR_NAME, getAgentDir } from "../config.ts";
import { PIBOLT } from "../pi-bolt.ts";
import { getFileRevision, normalizePath, resolvePath } from "../utils/paths.ts";
import { stripBom } from "../utils/text.ts";
import type { CompactionSettings as ResolvedCompactionSettings } from "./compaction/compaction.ts";
import { DEFAULT_HTTP_IDLE_TIMEOUT_MS, parseHttpIdleTimeoutMs } from "./http-dispatcher.ts";
import { getPackageIdentity, resolvePackageOverlay } from "./package-source.ts";
import { SETTINGS_DEFAULTS } from "./settings-defaults.ts";
import type {
	CacheWarmingMode,
	CompactionModelOverride,
	DefaultProjectTrust,
	FullscreenExitOutput,
	MermaidRenderingMode,
	PackageSource,
	QuietStartup,
	SessionShareDestination,
	Settings,
	ThinkingBudgetsSettings,
	TransportSetting,
	TuiMode,
	WarningSettings,
} from "./settings-schema.ts";

export type {
	BranchSummarySettings,
	CacheWarmingMode,
	CodemodeMode,
	CodemodeSettings,
	CompactionModelOverride,
	CompactionSettings,
	DefaultProjectTrust,
	FullscreenExitOutput,
	ImageSettings,
	MarkdownSettings,
	MermaidRenderingMode,
	PackageSource,
	ProviderRetrySettings,
	QuietStartup,
	RetrySettings,
	SessionShareDestination,
	Settings,
	TerminalSettings,
	ThinkingBudgetsSettings,
	TransportSetting,
	TuiMode,
	WarningSettings,
} from "./settings-schema.ts";
/** Cache-warming profile. "idle" also warms between agent runs. */
export const CACHE_WARMING_MODES = ["off", "streaming", "idle"] as const satisfies readonly CacheWarmingMode[];

/** Valid range of each compaction token setting, for its ordinary value and model overrides. */
const COMPACTION_SETTING_RANGES: Record<keyof CompactionModelOverride, { min: number; max: number }> = {
	reserveTokens: { min: 0, max: Number.MAX_SAFE_INTEGER },
	keepRecentTokens: { min: 0, max: Number.MAX_SAFE_INTEGER },
	thresholdPercent: { min: 1, max: 100 },
	summaryMaxTokens: { min: 1, max: Number.MAX_SAFE_INTEGER },
};

/** Share of the context window kept as recent context when keepRecentTokens is unset, in percent. */
const KEEP_RECENT_WINDOW_PERCENT = 5;

/** A model whose compaction settings are resolved: its key for model overrides and its context window. */
type CompactionSettingsModel = Pick<Model<string>, "provider" | "id"> & Partial<Pick<Model<string>, "contextWindow">>;

function isMergeableObject(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

function deepMergeObjects(base: Record<string, unknown>, overrides: Record<string, unknown>): Record<string, unknown> {
	const result = { ...base };

	for (const key of Object.keys(overrides)) {
		const overrideValue = overrides[key];
		if (overrideValue === undefined) {
			continue;
		}

		const baseValue = base[key];
		result[key] =
			isMergeableObject(baseValue) && isMergeableObject(overrideValue)
				? deepMergeObjects(baseValue, overrideValue)
				: overrideValue;
	}

	return result;
}

/** Tools enabled at startup when `defaultTools` does not change them. */
/** Pi's default tools; in Pi-Bolt, grep and find too (searching in-process: core/tools/native-search.ts). */
export const DEFAULT_TOOL_NAMES: readonly string[] =
	PIBOLT !== undefined ? ["read", "bash", "edit", "write", "grep", "find"] : ["read", "bash", "edit", "write"];

/** FFF's extension (opensec-pi-fff, @ff-labs/pi-fff), by the path it is loaded from: it brings search tools of its own. */
const FFF_EXTENSION = /(?:^builtin:|[\\/]node_modules[\\/])(?:opensec-pi-fff|@ff-labs[\\/]pi-fff)(?:[\\/]|$)/;

/**
 * The default tools with these extensions loaded: in Pi-Bolt, without its grep and find when FFF is loaded (it registers
 * its own, under those names or as ffgrep and fffind).
 */
export function defaultToolNamesFor(extensionPaths: readonly string[]): readonly string[] {
	if (PIBOLT === undefined || !extensionPaths.some((path) => FFF_EXTENSION.test(path))) return DEFAULT_TOOL_NAMES;
	return DEFAULT_TOOL_NAMES.filter((name) => name !== "grep" && name !== "find");
}

/** Whether a tool selection entry is a `+name` or `-name` modifier. */
export function isToolModifier(entry: unknown): boolean {
	return typeof entry === "string" && (entry.startsWith("+") || entry.startsWith("-"));
}

/**
 * Validate a tool list from `--tools` or the SDK `tools` option. It is either an allowlist of plain
 * names and patterns or a list of only `+name`/`-name` entries with exact names. Returns the
 * problem, or undefined when the list is valid.
 */
export function getToolListError(entries: readonly string[]): string | undefined {
	const modifiers = entries.filter(isToolModifier);
	if (modifiers.length === 0) return undefined;
	if (modifiers.length < entries.length) return "tool names cannot be mixed with +name or -name entries";
	const pattern = modifiers.find((entry) => entry.includes("*"));
	if (pattern) return `+name and -name entries take exact tool names, not patterns: ${pattern}`;
	return undefined;
}

/**
 * Apply the `+name` and `-name` entries of `entries` to `base` in order: `+name` adds a tool and
 * `-name` removes one. Other entries are ignored.
 */
export function applyToolModifiers(base: readonly string[], entries: readonly string[]): string[] {
	const tools = [...base];
	for (const entry of entries) {
		if (!isToolModifier(entry)) continue;
		const name = entry.slice(1);
		const index = tools.indexOf(name);
		if (entry.startsWith("+") && index === -1 && name) tools.push(name);
		else if (entry.startsWith("-") && index !== -1) tools.splice(index, 1);
	}
	return tools;
}

/**
 * Merge `defaultTools` of two settings layers. A list with plain tool names replaces the inherited
 * one; a list of only `+name`/`-name` entries is appended, so it modifies the inherited selection.
 */
function mergeDefaultTools(base: string[] | undefined, overrides: string[] | undefined): string[] | undefined {
	if (overrides === undefined) return base;
	// Settings files are not validated; a malformed value replaces instead of throwing here.
	if (!Array.isArray(base) || !Array.isArray(overrides) || !overrides.every(isToolModifier)) return overrides;
	return [...base, ...overrides];
}

/**
 * Resolve a merged `defaultTools` list: plain names replace `base` (`DEFAULT_TOOL_NAMES`), then `+name` adds
 * and `-name` removes a tool, in list order.
 */
function resolveDefaultTools(entries: string[], base: readonly string[]): string[] {
	const plain = entries.filter((entry) => !isToolModifier(entry));
	return applyToolModifiers(plain.length > 0 || entries.length === 0 ? plain : base, entries);
}

/** Deep merge settings: project/overrides take precedence, nested objects merge recursively */
function deepMergeSettings(base: Settings, overrides: Settings): Settings {
	const merged = deepMergeObjects(base as Record<string, unknown>, overrides as Record<string, unknown>) as Settings;
	const defaultTools = mergeDefaultTools(base.defaultTools, overrides.defaultTools);
	return defaultTools === undefined ? merged : { ...merged, defaultTools };
}

function parseTimeoutSetting(value: unknown, settingName: string): number | undefined {
	const timeoutMs = parseHttpIdleTimeoutMs(value);
	if (timeoutMs !== undefined) {
		return timeoutMs;
	}
	if (value !== undefined) {
		throw new Error(`Invalid ${settingName} setting: ${String(value)}`);
	}
	return undefined;
}

export type SettingsScope = "global" | "project";

export interface SettingsManagerCreateOptions {
	projectTrusted?: boolean;
}

export interface SettingsStorage {
	withLock(scope: SettingsScope, fn: (current: string | undefined) => string | undefined): void;
	/** What withLock() would hand its callback, for a caller that only reads. Optional: withLock() is used without it. */
	read?(scope: SettingsScope): string | undefined;
}

export interface SettingsError {
	scope: SettingsScope;
	path?: string;
	error: Error;
}

type SettingsPaths = Partial<Record<SettingsScope, string>>;

function toSettingsError(scope: SettingsScope, error: unknown, path?: string): SettingsError {
	return {
		scope,
		...(path ? { path } : {}),
		error: error instanceof Error ? error : new Error(String(error)),
	};
}

// What each settings file held when it was last read under its lock, with the file's revision at that time. A start reads
// settings.json four times (three SettingsManagers and a reload), and every read took the lock: a directory made and
// removed beside the file. An unchanged file is now read once.
const settingsFileReads = new Map<string, { revision: string; content: string }>();

export class FileSettingsStorage implements SettingsStorage {
	private globalSettingsPath: string;
	private projectSettingsPath: string;

	constructor(cwd: string, agentDir: string) {
		const resolvedCwd = resolvePath(cwd);
		const resolvedAgentDir = resolvePath(agentDir);
		this.globalSettingsPath = join(resolvedAgentDir, "settings.json");
		this.projectSettingsPath = join(resolvedCwd, CONFIG_DIR_NAME, "settings.json");
	}

	private acquireLockSyncWithRetry(path: string): () => void {
		const maxAttempts = 10;
		const delayMs = 20;
		let lastError: unknown;

		for (let attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return lockfile.lockSync(path, { realpath: false });
			} catch (error) {
				const code =
					typeof error === "object" && error !== null && "code" in error
						? String((error as { code?: unknown }).code)
						: undefined;
				if (code !== "ELOCKED" || attempt === maxAttempts) {
					throw error;
				}
				lastError = error;
				const start = Date.now();
				while (Date.now() - start < delayMs) {
					// Sleep synchronously to avoid changing callers to async.
				}
			}
		}

		throw (lastError as Error) ?? new Error("Failed to acquire settings lock");
	}

	read(scope: SettingsScope): string | undefined {
		const path = scope === "global" ? this.globalSettingsPath : this.projectSettingsPath;
		const revision = getFileRevision(path);
		if (revision === undefined) {
			settingsFileReads.delete(path);
			// A failed stat is not necessarily an absent file (for example, EACCES or a symlink loop).
			try {
				return readFileSync(path, "utf-8");
			} catch (error) {
				if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
				throw error;
			}
		}
		const cached = settingsFileReads.get(path);
		if (cached?.revision === revision) {
			return cached.content;
		}
		let content: string | undefined;
		this.withLock(scope, (current) => {
			content = current;
			return undefined;
		});
		// Kept only if the file is what it was before it was read.
		if (content !== undefined && getFileRevision(path) === revision) {
			settingsFileReads.set(path, { revision, content });
		} else {
			settingsFileReads.delete(path);
		}
		return content;
	}

	withLock(scope: SettingsScope, fn: (current: string | undefined) => string | undefined): void {
		const path = scope === "global" ? this.globalSettingsPath : this.projectSettingsPath;
		const dir = dirname(path);

		let release: (() => void) | undefined;
		try {
			// Only create directory and lock if file exists or we need to write
			const fileExists = existsSync(path);
			if (fileExists) {
				release = this.acquireLockSyncWithRetry(path);
			}
			const current = fileExists ? readFileSync(path, "utf-8") : undefined;
			const next = fn(current);
			if (next !== undefined) {
				// Only create directory when we actually need to write
				if (!existsSync(dir)) {
					mkdirSync(dir, { recursive: true });
				}
				if (!release) {
					release = this.acquireLockSyncWithRetry(path);
				}
				writeFileSync(path, next, "utf-8");
			}
		} finally {
			if (release) {
				release();
			}
		}
	}
}

export class InMemorySettingsStorage implements SettingsStorage {
	private global: string | undefined;
	private project: string | undefined;

	withLock(scope: SettingsScope, fn: (current: string | undefined) => string | undefined): void {
		const current = scope === "global" ? this.global : this.project;
		const next = fn(current);
		if (next !== undefined) {
			if (scope === "global") {
				this.global = next;
			} else {
				this.project = next;
			}
		}
	}
}

/**
 * OpenSec's extensions for Pi-Bolt are Bun builds, which Pi on Node cannot load; installers before Pi-Bolt 0.8.4 added them
 * to `packages`, so Pi stopped at them. Move only those entries to Pi-Bolt's additive list.
 */
const PIBOLT_ONLY_PACKAGE = /^npm:opensec-pi-(?:subagents|todo|fff)(?:@|$)/;

function sourceOfPackage(entry: unknown): string | undefined {
	if (typeof entry === "string") return entry;
	if (typeof entry === "object" && entry !== null && typeof (entry as { source?: unknown }).source === "string") {
		return (entry as { source: string }).source;
	}
	return undefined;
}

function isPiBoltOnlyPackage(entry: unknown): boolean {
	const source = sourceOfPackage(entry);
	return source !== undefined && PIBOLT_ONLY_PACKAGE.test(source);
}

function piBoltSettingsOf(settings: Record<string, unknown>): Record<string, unknown> {
	return (typeof settings.piBolt === "object" && settings.piBolt !== null ? settings.piBolt : {}) as Record<
		string,
		unknown
	>;
}

function packageIdentityOf(entry: unknown, baseDir: string): string | undefined {
	const source = sourceOfPackage(entry);
	return source === undefined
		? undefined
		: getPackageIdentity(source.startsWith("-") ? source.slice(1) : source, baseDir);
}

/** `settings` with Pi-Bolt's only packages moved out of Pi's list, or undefined when Pi's list has none. */
function withPiBoltOnlyPackagesMoved(
	settings: Record<string, unknown>,
	baseDir: string,
): Record<string, unknown> | undefined {
	const packages = settings.packages;
	if (!Array.isArray(packages) || !packages.some(isPiBoltOnlyPackage)) return undefined;
	const piBolt = piBoltSettingsOf(settings);
	const hasList = Array.isArray(piBolt.packages);
	const own: unknown[] = hasList ? (piBolt.packages as unknown[]) : [];
	const ownSources = new Set(own.map((entry) => packageIdentityOf(entry, baseDir)));
	const moved = packages.filter(
		(entry) => isPiBoltOnlyPackage(entry) && !ownSources.has(packageIdentityOf(entry, baseDir)),
	);
	return {
		...settings,
		packages: packages.filter((entry) => !isPiBoltOnlyPackage(entry)),
		// A list made here is an overlay; an existing unmarked one is a legacy copy, converted next.
		piBolt: { ...piBolt, packages: [...own, ...moved], ...(hasList ? {} : { packagesOverlay: true }) },
	};
}

/** Same package, version or ref and resource filters. Local paths compare by resolved path. */
function isSamePackageDeclaration(left: unknown, right: unknown, baseDir: string): boolean {
	const normalize = (entry: unknown) => {
		const { source, ...filters } = (typeof entry === "string" ? { source: entry } : entry) as Record<string, unknown>;
		const identity = packageIdentityOf(entry, baseDir);
		const spec = identity?.startsWith("local:") ? identity : String(source).trim();
		return JSON.stringify([spec, Object.entries(filters).sort(([a], [b]) => a.localeCompare(b))]);
	};
	return normalize(left) === normalize(right);
}

/**
 * Pi-Bolt before 0.9.1 kept `piBolt.packages` as an independent list: its first install or removal copied `packages` into it
 * and edited the copy. The list is now an overlay on `packages`, so a shared package missing from a copy would be loaded again.
 * Convert a copy once: exclude each shared package it lacks, drop entries identical to their shared entry, and keep the rest
 * (replacements and Pi-Bolt-only additions). `packagesOverlay` marks a list as an overlay; every writer of the list sets it.
 * Returns undefined when there is nothing to change.
 */
function withLegacyPiBoltPackagesConverted(
	settings: Record<string, unknown>,
	baseDir: string,
): Record<string, unknown> | undefined {
	const piBolt = piBoltSettingsOf(settings);
	if (!Array.isArray(piBolt.packages) || piBolt.packagesOverlay === true) return undefined;
	const own: unknown[] = piBolt.packages;
	// Exclusions exist only in overlays: such a list was written for the overlay format and is only marked.
	if (own.some((entry) => typeof entry === "string" && entry.startsWith("-"))) {
		return { ...settings, piBolt: { ...piBolt, packagesOverlay: true } };
	}
	const shared: unknown[] = Array.isArray(settings.packages) ? settings.packages : [];
	const identity = (entry: unknown) => packageIdentityOf(entry, baseDir);
	const ownIdentities = new Set(own.map(identity));
	const exclusions = new Map<string, string>();
	for (const entry of shared) {
		const key = identity(entry);
		if (key !== undefined && !ownIdentities.has(key) && !exclusions.has(key)) {
			exclusions.set(key, `-${sourceOfPackage(entry)}`);
		}
	}
	const kept = own.filter((entry) => {
		const key = identity(entry);
		const same = key === undefined ? [] : shared.filter((sharedEntry) => identity(sharedEntry) === key);
		return same.length === 0 || !same.every((sharedEntry) => isSamePackageDeclaration(sharedEntry, entry, baseDir));
	});
	return { ...settings, piBolt: { ...piBolt, packages: [...exclusions.values(), ...kept], packagesOverlay: true } };
}

/** Pi-Bolt's package migrations for one settings file, or undefined when it needs none. */
function withPiBoltPackagesMigrated(
	settings: Record<string, unknown>,
	scope: SettingsScope,
	baseDir: string,
): Record<string, unknown> | undefined {
	const moved = scope === "global" ? withPiBoltOnlyPackagesMoved(settings, baseDir) : undefined;
	return withLegacyPiBoltPackagesConverted(moved ?? settings, baseDir) ?? moved;
}

/** Directory relative package sources in a settings file resolve against. */
function getPackageBaseDir(scope: SettingsScope, settingsPaths: SettingsPaths): string {
	const path = settingsPaths[scope];
	if (path) return dirname(path);
	return scope === "global" ? getAgentDir() : join(process.cwd(), CONFIG_DIR_NAME);
}

export class SettingsManager {
	private storage: SettingsStorage;
	private globalSettings: Settings;
	private projectSettings: Settings;
	private settings: Settings;
	private packageSettings: Settings = {};
	private projectTrusted: boolean;
	private modifiedFields = new Set<keyof Settings>(); // Track global fields modified during session
	private modifiedNestedFields = new Map<keyof Settings, Set<string>>(); // Track global nested field modifications
	private modifiedProjectFields = new Set<keyof Settings>(); // Track project fields modified during session
	private modifiedProjectNestedFields = new Map<keyof Settings, Set<string>>(); // Track project nested field modifications
	private globalSettingsLoadError: Error | null = null; // Track if global settings file had parse errors
	private projectSettingsLoadError: Error | null = null; // Track if project settings file had parse errors
	private writeQueue: Promise<void> = Promise.resolve();
	private errors: SettingsError[];
	private settingsPaths: SettingsPaths;

	private constructor(
		storage: SettingsStorage,
		initialGlobal: Settings,
		initialProject: Settings,
		globalLoadError: Error | null = null,
		projectLoadError: Error | null = null,
		initialErrors: SettingsError[] = [],
		projectTrusted = true,
		settingsPaths: SettingsPaths = {},
	) {
		this.storage = storage;
		this.globalSettings = initialGlobal;
		this.projectSettings = initialProject;
		this.projectTrusted = projectTrusted;
		this.globalSettingsLoadError = globalLoadError;
		this.projectSettingsLoadError = projectLoadError;
		this.errors = [...initialErrors];
		this.settingsPaths = settingsPaths;
		this.settings = this.mergeSettings();
	}

	/** Create a SettingsManager that loads from files */
	static create(
		cwd: string,
		agentDir: string = getAgentDir(),
		options: SettingsManagerCreateOptions = {},
	): SettingsManager {
		const resolvedCwd = resolvePath(cwd);
		const resolvedAgentDir = resolvePath(agentDir);
		const storage = new FileSettingsStorage(resolvedCwd, resolvedAgentDir);
		return SettingsManager.fromStorageWithPaths(storage, options, {
			global: join(resolvedAgentDir, "settings.json"),
			project: join(resolvedCwd, CONFIG_DIR_NAME, "settings.json"),
		});
	}

	/** Create a SettingsManager from an arbitrary storage backend */
	static fromStorage(storage: SettingsStorage, options: SettingsManagerCreateOptions = {}): SettingsManager {
		return SettingsManager.fromStorageWithPaths(storage, options);
	}

	/** Create a manager while retaining optional file paths for reported storage errors. */
	private static fromStorageWithPaths(
		storage: SettingsStorage,
		options: SettingsManagerCreateOptions,
		settingsPaths: SettingsPaths = {},
	): SettingsManager {
		const projectTrusted = options.projectTrusted ?? true;
		const globalLoad = SettingsManager.tryLoadFromStorage(storage, "global", settingsPaths);
		const projectLoad = SettingsManager.tryLoadFromStorage(storage, "project", settingsPaths, projectTrusted);
		const initialErrors: SettingsError[] = [];
		if (globalLoad.error) {
			initialErrors.push(toSettingsError("global", globalLoad.error, settingsPaths.global));
		}
		if (projectLoad.error) {
			initialErrors.push(toSettingsError("project", projectLoad.error, settingsPaths.project));
		}

		return new SettingsManager(
			storage,
			globalLoad.settings,
			projectLoad.settings,
			globalLoad.error,
			projectLoad.error,
			initialErrors,
			projectTrusted,
			settingsPaths,
		);
	}

	/** Create an in-memory SettingsManager (no file I/O) */
	static inMemory(settings: Partial<Settings> = {}, options: SettingsManagerCreateOptions = {}): SettingsManager {
		const storage = new InMemorySettingsStorage();
		const initialSettings = SettingsManager.migrateSettings(structuredClone(settings) as Record<string, unknown>);
		storage.withLock("global", () => JSON.stringify(initialSettings, null, 2));
		return SettingsManager.fromStorage(storage, options);
	}

	private static loadFromStorage(
		storage: SettingsStorage,
		scope: SettingsScope,
		settingsPaths: SettingsPaths,
		projectTrusted = true,
	): Settings {
		if (scope === "project" && !projectTrusted) {
			return {};
		}

		let content: string | undefined;
		if (storage.read) {
			content = storage.read(scope);
		} else {
			storage.withLock(scope, (current) => {
				content = current;
				return undefined;
			});
		}

		if (!content) {
			return {};
		}
		let settings = JSON.parse(stripBom(content));
		if (PIBOLT !== undefined) {
			const baseDir = getPackageBaseDir(scope, settingsPaths);
			const migrated = withPiBoltPackagesMigrated(settings, scope, baseDir);
			if (migrated) {
				settings = migrated;
				try {
					storage.withLock(scope, (current) => {
						const next = current
							? withPiBoltPackagesMigrated(JSON.parse(stripBom(current)), scope, baseDir)
							: undefined;
						return next ? JSON.stringify(next, null, 2) : undefined;
					});
				} catch {
					// (Not written: Pi-Bolt uses the migrated lists in memory, and migrates the file at a later start.)
				}
			}
		}
		return SettingsManager.migrateSettings(settings);
	}

	private static tryLoadFromStorage(
		storage: SettingsStorage,
		scope: SettingsScope,
		settingsPaths: SettingsPaths,
		projectTrusted = true,
	): { settings: Settings; error: Error | null } {
		try {
			return {
				settings: SettingsManager.loadFromStorage(storage, scope, settingsPaths, projectTrusted),
				error: null,
			};
		} catch (error) {
			return { settings: {}, error: error as Error };
		}
	}

	/** Migrate old settings format to new format */
	private static migrateSettings(settings: Record<string, unknown>): Settings {
		// Migrate queueMode -> steeringMode
		if ("queueMode" in settings && !("steeringMode" in settings)) {
			settings.steeringMode = settings.queueMode;
			delete settings.queueMode;
		}

		// Migrate legacy websockets boolean -> transport enum
		if (!("transport" in settings) && typeof settings.websockets === "boolean") {
			settings.transport = settings.websockets ? "websocket" : "sse";
			delete settings.websockets;
		}

		// Migrate old skills object format to new array format
		if (
			"skills" in settings &&
			typeof settings.skills === "object" &&
			settings.skills !== null &&
			!Array.isArray(settings.skills)
		) {
			const skillsSettings = settings.skills as {
				enableSkillCommands?: boolean;
				customDirectories?: unknown;
			};
			if (skillsSettings.enableSkillCommands !== undefined && settings.enableSkillCommands === undefined) {
				settings.enableSkillCommands = skillsSettings.enableSkillCommands;
			}
			if (Array.isArray(skillsSettings.customDirectories) && skillsSettings.customDirectories.length > 0) {
				settings.skills = skillsSettings.customDirectories;
			} else {
				delete settings.skills;
			}
		}

		// Migrate retry.maxDelayMs -> retry.provider.maxRetryDelayMs
		if (
			"retry" in settings &&
			typeof settings.retry === "object" &&
			settings.retry !== null &&
			!Array.isArray(settings.retry)
		) {
			const retrySettings = settings.retry as Record<string, unknown>;
			const providerSettings =
				typeof retrySettings.provider === "object" && retrySettings.provider !== null
					? (retrySettings.provider as Record<string, unknown>)
					: undefined;
			if (
				typeof retrySettings.maxDelayMs === "number" &&
				(providerSettings?.maxRetryDelayMs === undefined || providerSettings?.maxRetryDelayMs === null)
			) {
				retrySettings.provider = {
					...(providerSettings ?? {}),
					maxRetryDelayMs: retrySettings.maxDelayMs,
				};
			}
			delete retrySettings.maxDelayMs;
		}

		return settings as Settings;
	}

	/** Raw merged settings, not resolved package overlays. Never write a resolved list back to a raw list. */
	getSettings(): Settings {
		return structuredClone(this.settings);
	}

	/** Raw global declarations, including separate shared and Pi-Bolt package lists. */
	getGlobalSettings(): Settings {
		return structuredClone(this.globalSettings);
	}

	/** Raw trusted project declarations. Untrusted projects return empty settings. */
	getProjectSettings(): Settings {
		return structuredClone(this.projectSettings);
	}

	isProjectTrusted(): boolean {
		return this.projectTrusted;
	}

	setProjectTrusted(trusted: boolean): void {
		if (this.projectTrusted === trusted) {
			return;
		}

		this.projectTrusted = trusted;
		this.modifiedProjectFields.clear();
		this.modifiedProjectNestedFields.clear();

		if (!trusted) {
			this.projectSettings = {};
			this.projectSettingsLoadError = null;
			this.settings = this.mergeSettings();
			return;
		}

		const projectLoad = SettingsManager.tryLoadFromStorage(this.storage, "project", this.settingsPaths, trusted);
		this.projectSettings = projectLoad.settings;
		this.projectSettingsLoadError = projectLoad.error;
		if (projectLoad.error) {
			this.recordError("project", projectLoad.error);
		}
		this.settings = this.mergeSettings();
	}

	async reload(): Promise<void> {
		await this.writeQueue;
		const globalLoad = SettingsManager.tryLoadFromStorage(this.storage, "global", this.settingsPaths);
		if (!globalLoad.error) {
			this.globalSettings = globalLoad.settings;
			this.globalSettingsLoadError = null;
		} else {
			this.globalSettingsLoadError = globalLoad.error;
			this.recordError("global", globalLoad.error);
		}

		this.modifiedFields.clear();
		this.modifiedNestedFields.clear();
		this.modifiedProjectFields.clear();
		this.modifiedProjectNestedFields.clear();

		const projectLoad = SettingsManager.tryLoadFromStorage(
			this.storage,
			"project",
			this.settingsPaths,
			this.projectTrusted,
		);
		if (!projectLoad.error) {
			this.projectSettings = projectLoad.settings;
			this.projectSettingsLoadError = null;
		} else {
			this.projectSettingsLoadError = projectLoad.error;
			this.recordError("project", projectLoad.error);
		}

		this.settings = this.mergeSettings();
	}

	/** Apply additional overrides on top of current settings */
	applyOverrides(overrides: Partial<Settings>): void {
		this.settings = deepMergeSettings(this.settings, overrides);
		this.packageSettings = deepMergeSettings(this.packageSettings, overrides);
	}

	/** Mark a global field as modified during this session */
	private markModified(field: keyof Settings, nestedKey?: string): void {
		this.modifiedFields.add(field);
		if (nestedKey) {
			if (!this.modifiedNestedFields.has(field)) {
				this.modifiedNestedFields.set(field, new Set());
			}
			this.modifiedNestedFields.get(field)!.add(nestedKey);
		}
	}

	/** Mark a project field as modified during this session */
	private markProjectModified(field: keyof Settings, nestedKey?: string): void {
		this.modifiedProjectFields.add(field);
		if (nestedKey) {
			if (!this.modifiedProjectNestedFields.has(field)) {
				this.modifiedProjectNestedFields.set(field, new Set());
			}
			this.modifiedProjectNestedFields.get(field)!.add(nestedKey);
		}
	}

	private assertProjectTrustedForWrite(): void {
		if (!this.projectTrusted) {
			throw new Error("Project is not trusted; refusing to write project settings");
		}
	}

	private recordError(scope: SettingsScope, error: unknown): void {
		this.errors.push(toSettingsError(scope, error, this.settingsPaths[scope]));
	}

	private clearModifiedScope(scope: SettingsScope): void {
		if (scope === "global") {
			this.modifiedFields.clear();
			this.modifiedNestedFields.clear();
			return;
		}

		this.modifiedProjectFields.clear();
		this.modifiedProjectNestedFields.clear();
	}

	private enqueueWrite(scope: SettingsScope, task: () => void): void {
		this.writeQueue = this.writeQueue
			.then(() => {
				if (scope === "project") {
					this.assertProjectTrustedForWrite();
				}
				task();
				this.clearModifiedScope(scope);
			})
			.catch((error) => {
				this.recordError(scope, error);
			});
	}

	private cloneModifiedNestedFields(source: Map<keyof Settings, Set<string>>): Map<keyof Settings, Set<string>> {
		const snapshot = new Map<keyof Settings, Set<string>>();
		for (const [key, value] of source.entries()) {
			snapshot.set(key, new Set(value));
		}
		return snapshot;
	}

	private persistScopedSettings(
		scope: SettingsScope,
		snapshotSettings: Settings,
		modifiedFields: Set<keyof Settings>,
		modifiedNestedFields: Map<keyof Settings, Set<string>>,
	): void {
		this.storage.withLock(scope, (current) => {
			const currentFileSettings = current
				? SettingsManager.migrateSettings(JSON.parse(stripBom(current)) as Record<string, unknown>)
				: {};
			const mergedSettings: Settings = { ...currentFileSettings };
			for (const field of modifiedFields) {
				const value = snapshotSettings[field];
				if (modifiedNestedFields.has(field) && typeof value === "object" && value !== null) {
					const nestedModified = modifiedNestedFields.get(field)!;
					const baseNested = (currentFileSettings[field] as Record<string, unknown>) ?? {};
					const inMemoryNested = value as Record<string, unknown>;
					const mergedNested = { ...baseNested };
					for (const nestedKey of nestedModified) {
						mergedNested[nestedKey] = inMemoryNested[nestedKey];
					}
					(mergedSettings as Record<string, unknown>)[field] = mergedNested;
				} else {
					(mergedSettings as Record<string, unknown>)[field] = value;
				}
			}

			return JSON.stringify(mergedSettings, null, 2);
		});
	}

	private save(): void {
		this.settings = this.mergeSettings();

		if (this.globalSettingsLoadError) {
			return;
		}

		const snapshotGlobalSettings = structuredClone(this.globalSettings);
		const modifiedFields = new Set(this.modifiedFields);
		const modifiedNestedFields = this.cloneModifiedNestedFields(this.modifiedNestedFields);

		this.enqueueWrite("global", () => {
			this.persistScopedSettings("global", snapshotGlobalSettings, modifiedFields, modifiedNestedFields);
		});
	}

	private saveProjectSettings(settings: Settings): void {
		this.assertProjectTrustedForWrite();
		this.projectSettings = structuredClone(settings);
		this.settings = this.mergeSettings();

		if (this.projectSettingsLoadError) {
			return;
		}

		const snapshotProjectSettings = structuredClone(this.projectSettings);
		const modifiedFields = new Set(this.modifiedProjectFields);
		const modifiedNestedFields = this.cloneModifiedNestedFields(this.modifiedProjectNestedFields);
		this.enqueueWrite("project", () => {
			this.persistScopedSettings("project", snapshotProjectSettings, modifiedFields, modifiedNestedFields);
		});
	}

	private updateProjectSettings(field: keyof Settings, update: (settings: Settings) => void): void {
		this.assertProjectTrustedForWrite();
		const projectSettings = structuredClone(this.projectSettings);
		update(projectSettings);
		this.markProjectModified(field);
		this.saveProjectSettings(projectSettings);
	}

	async flush(): Promise<void> {
		await this.writeQueue;
	}

	drainErrors(): SettingsError[] {
		const drained = [...this.errors];
		this.errors = [];
		return drained;
	}

	getLastChangelogVersion(): string | undefined {
		return this.settings.lastChangelogVersion;
	}

	setLastChangelogVersion(version: string): void {
		this.globalSettings.lastChangelogVersion = version;
		this.markModified("lastChangelogVersion");
		this.save();
	}

	getSessionDir(): string | undefined {
		const sessionDir = this.settings.sessionDir;
		return sessionDir ? normalizePath(sessionDir) : sessionDir;
	}

	getDefaultProvider(): string | undefined {
		return this.settings.defaultProvider;
	}

	getDefaultModel(): string | undefined {
		return this.settings.defaultModel;
	}

	setDefaultProvider(provider: string): void {
		this.globalSettings.defaultProvider = provider;
		this.markModified("defaultProvider");
		this.save();
	}

	setDefaultModel(modelId: string): void {
		this.globalSettings.defaultModel = modelId;
		this.markModified("defaultModel");
		this.save();
	}

	setDefaultModelAndProvider(provider: string, modelId: string): void {
		this.globalSettings.defaultProvider = provider;
		this.globalSettings.defaultModel = modelId;
		this.markModified("defaultProvider");
		this.markModified("defaultModel");
		this.save();
	}

	getSteeringMode(): "all" | "one-at-a-time" {
		return this.settings.steeringMode || SETTINGS_DEFAULTS.steeringMode;
	}

	setSteeringMode(mode: "all" | "one-at-a-time"): void {
		this.globalSettings.steeringMode = mode;
		this.markModified("steeringMode");
		this.save();
	}

	getFollowUpMode(): "all" | "one-at-a-time" {
		return this.settings.followUpMode || SETTINGS_DEFAULTS.followUpMode;
	}

	setFollowUpMode(mode: "all" | "one-at-a-time"): void {
		this.globalSettings.followUpMode = mode;
		this.markModified("followUpMode");
		this.save();
	}

	getThemeSetting(): string | undefined {
		const value = this.settings.theme;
		if (typeof value === "string") return value;
		return undefined;
	}

	getTheme(): string | undefined {
		const theme = this.getThemeSetting();
		return theme?.includes("/") ? undefined : theme;
	}

	setTheme(theme: string): void {
		this.globalSettings.theme = theme;
		this.markModified("theme");
		this.save();
	}

	getDefaultThinkingLevel(): ThinkingLevel | undefined {
		return this.settings.defaultThinkingLevel;
	}

	setDefaultThinkingLevel(level: ThinkingLevel): void {
		this.globalSettings.defaultThinkingLevel = level;
		this.markModified("defaultThinkingLevel");
		this.save();
	}

	getModelThinkingLevel(provider: string, modelId: string): ThinkingLevel | undefined {
		return this.settings.modelThinkingLevels?.[`${provider}/${modelId}`];
	}

	getAllModelThinkingLevels(): Record<string, ThinkingLevel> {
		return { ...(this.settings.modelThinkingLevels ?? {}) };
	}

	setModelThinkingLevel(provider: string, modelId: string, level: ThinkingLevel): void {
		if (!this.globalSettings.modelThinkingLevels) {
			this.globalSettings.modelThinkingLevels = {};
		}
		this.globalSettings.modelThinkingLevels[`${provider}/${modelId}`] = level;
		this.markModified("modelThinkingLevels");
		this.save();
	}

	removeModelThinkingLevel(provider: string, modelId: string): void {
		if (!this.globalSettings.modelThinkingLevels) return;
		delete this.globalSettings.modelThinkingLevels[`${provider}/${modelId}`];
		if (Object.keys(this.globalSettings.modelThinkingLevels).length === 0) {
			delete this.globalSettings.modelThinkingLevels;
		}
		this.markModified("modelThinkingLevels");
		this.save();
	}

	getTransport(): TransportSetting {
		return this.settings.transport ?? SETTINGS_DEFAULTS.transport;
	}

	setTransport(transport: TransportSetting): void {
		this.globalSettings.transport = transport;
		this.markModified("transport");
		this.save();
	}

	getSessionShareDestination(): SessionShareDestination {
		const destination = this.settings.sessionShareDestination;
		return destination === "gist" || destination === "radius"
			? destination
			: SETTINGS_DEFAULTS.sessionShareDestination;
	}

	setSessionShareDestination(destination: SessionShareDestination): void {
		this.globalSettings.sessionShareDestination = destination;
		this.markModified("sessionShareDestination");
		this.save();
	}

	getCompactionEnabled(): boolean {
		return this.settings.compaction?.enabled ?? SETTINGS_DEFAULTS.compaction.enabled;
	}

	setCompactionEnabled(enabled: boolean): void {
		if (!this.globalSettings.compaction) {
			this.globalSettings.compaction = {};
		}
		this.globalSettings.compaction.enabled = enabled;
		this.markModified("compaction", "enabled");
		this.save();
	}

	/** The compaction setting the user set, from the model override or the ordinary setting, if any. */
	private getExplicitCompactionSetting(
		field: keyof CompactionModelOverride,
		model?: Pick<Model<string>, "provider" | "id">,
	): number | undefined {
		const { min, max } = COMPACTION_SETTING_RANGES[field];
		const range =
			min === 0 && max === Number.MAX_SAFE_INTEGER
				? "a non-negative safe integer"
				: `an integer from ${min} to ${max}`;
		const isValid = (value: unknown): value is number =>
			typeof value === "number" && Number.isSafeInteger(value) && value >= min && value <= max;
		const compaction = this.settings.compaction;
		const ordinary = compaction?.[field];
		if (ordinary !== undefined && !isValid(ordinary)) {
			throw new Error(`Invalid compaction.${field} setting: ${String(ordinary)}. Expected ${range}.`);
		}

		const modelKey = model ? `${model.provider}/${model.id}` : undefined;
		const entry = modelKey !== undefined ? compaction?.modelOverrides?.[modelKey] : undefined;
		if (entry !== undefined && !isMergeableObject(entry)) {
			throw new Error(
				`Invalid compaction.modelOverrides["${modelKey}"] setting: ${String(entry)}. Expected an object.`,
			);
		}
		const override = entry?.[field];
		if (override !== undefined && !isValid(override)) {
			throw new Error(
				`Invalid compaction.modelOverrides["${modelKey}"].${field} setting: ${String(override)}. Expected ${range}.`,
			);
		}
		return override ?? ordinary;
	}

	getCompactionReserveTokens(model?: Pick<Model<string>, "provider" | "id">): number {
		return this.getExplicitCompactionSetting("reserveTokens", model) ?? SETTINGS_DEFAULTS.compaction.reserveTokens;
	}

	/** Explicit keepRecentTokens, else the larger of the default and 5% of the context window. */
	getCompactionKeepRecentTokens(model?: CompactionSettingsModel, contextWindow = model?.contextWindow ?? 0): number {
		return (
			this.getExplicitCompactionSetting("keepRecentTokens", model) ??
			Math.max(
				SETTINGS_DEFAULTS.compaction.keepRecentTokens,
				Math.floor((Math.max(contextWindow, 0) * KEEP_RECENT_WINDOW_PERCENT) / 100),
			)
		);
	}

	/**
	 * Resolve each setting through model override, ordinary setting, then built-in default. Unset
	 * defaults scale with `contextWindow` (default: the model's): the threshold is also capped at
	 * thresholdPercent of the window, keepRecentTokens grows to 5% of it, and the summary cap to 3.2%
	 * of the summarizer's window. An explicit reserveTokens keeps the threshold and summary cap derived
	 * from reserveTokens alone, unless thresholdPercent or summaryMaxTokens is set too.
	 */
	getCompactionSettings(
		model?: CompactionSettingsModel,
		contextWindow = model?.contextWindow ?? 0,
	): ResolvedCompactionSettings {
		const explicitReserveTokens = this.getExplicitCompactionSetting("reserveTokens", model);
		const thresholdPercent =
			this.getExplicitCompactionSetting("thresholdPercent", model) ??
			(explicitReserveTokens === undefined ? SETTINGS_DEFAULTS.compaction.thresholdPercent : undefined);
		const summaryMaxTokens = this.getExplicitCompactionSetting("summaryMaxTokens", model);
		return {
			enabled: this.getCompactionEnabled(),
			reserveTokens: explicitReserveTokens ?? SETTINGS_DEFAULTS.compaction.reserveTokens,
			keepRecentTokens: this.getCompactionKeepRecentTokens(model, contextWindow),
			...(thresholdPercent === undefined ? {} : { thresholdPercent }),
			...(summaryMaxTokens === undefined ? {} : { summaryMaxTokens }),
			...(explicitReserveTokens === undefined && summaryMaxTokens === undefined
				? { scaleSummaryWithContextWindow: true }
				: {}),
		};
	}

	getBranchSummarySettings(): { reserveTokens: number; skipPrompt: boolean } {
		return {
			reserveTokens: this.settings.branchSummary?.reserveTokens ?? SETTINGS_DEFAULTS.branchSummary.reserveTokens,
			skipPrompt: this.settings.branchSummary?.skipPrompt ?? SETTINGS_DEFAULTS.branchSummary.skipPrompt,
		};
	}

	getBranchSummarySkipPrompt(): boolean {
		return this.settings.branchSummary?.skipPrompt ?? SETTINGS_DEFAULTS.branchSummary.skipPrompt;
	}

	getRetryEnabled(): boolean {
		return this.settings.retry?.enabled ?? SETTINGS_DEFAULTS.retry.enabled;
	}

	setRetryEnabled(enabled: boolean): void {
		if (!this.globalSettings.retry) {
			this.globalSettings.retry = {};
		}
		this.globalSettings.retry.enabled = enabled;
		this.markModified("retry", "enabled");
		this.save();
	}

	getRetrySettings(): { enabled: boolean; maxRetries: number; baseDelayMs: number; maxAgentDelayMs: number } {
		return {
			enabled: this.getRetryEnabled(),
			maxRetries: this.settings.retry?.maxRetries ?? SETTINGS_DEFAULTS.retry.maxRetries,
			baseDelayMs: this.settings.retry?.baseDelayMs ?? SETTINGS_DEFAULTS.retry.baseDelayMs,
			maxAgentDelayMs: this.settings.retry?.maxAgentDelayMs ?? SETTINGS_DEFAULTS.retry.maxAgentDelayMs,
		};
	}

	getHttpIdleTimeoutMs(): number {
		return parseTimeoutSetting(this.settings.httpIdleTimeoutMs, "httpIdleTimeoutMs") ?? DEFAULT_HTTP_IDLE_TIMEOUT_MS;
	}

	setHttpIdleTimeoutMs(timeoutMs: number): void {
		if (!Number.isFinite(timeoutMs) || timeoutMs < 0) {
			throw new Error(`Invalid httpIdleTimeoutMs setting: ${String(timeoutMs)}`);
		}
		this.globalSettings.httpIdleTimeoutMs = Math.floor(timeoutMs);
		this.markModified("httpIdleTimeoutMs");
		this.save();
	}

	/** Read from global settings only because warming costs money. */
	getCacheWarmingMode(): CacheWarmingMode {
		const mode = this.globalSettings.cacheWarming;
		return mode !== undefined && CACHE_WARMING_MODES.includes(mode) ? mode : SETTINGS_DEFAULTS.cacheWarming;
	}

	setCacheWarmingMode(mode: CacheWarmingMode): void {
		this.globalSettings.cacheWarming = mode;
		this.markModified("cacheWarming");
		this.save();
	}

	getProviderRetrySettings(): { timeoutMs?: number; maxRetries?: number; maxRetryDelayMs: number } {
		return {
			timeoutMs: this.settings.retry?.provider?.timeoutMs,
			maxRetries: this.settings.retry?.provider?.maxRetries,
			maxRetryDelayMs:
				this.settings.retry?.provider?.maxRetryDelayMs ?? SETTINGS_DEFAULTS.retry.provider.maxRetryDelayMs,
		};
	}

	getWebSocketConnectTimeoutMs(): number | undefined {
		return parseTimeoutSetting(this.settings.websocketConnectTimeoutMs, "websocketConnectTimeoutMs");
	}

	getHideThinkingBlock(): boolean {
		return this.settings.hideThinkingBlock ?? SETTINGS_DEFAULTS.hideThinkingBlock;
	}

	getShowCacheMissNotices(): boolean {
		return this.settings.showCacheMissNotices ?? SETTINGS_DEFAULTS.showCacheMissNotices;
	}

	getShadedCodeBlocks(): boolean {
		return this.settings.shadedCodeBlocks ?? SETTINGS_DEFAULTS.shadedCodeBlocks;
	}

	getExternalEditorCommand(): string {
		const configuredEditor = this.settings.externalEditor;
		if (typeof configuredEditor === "string" && configuredEditor.trim() !== "") {
			return configuredEditor;
		}
		const environmentEditor = process.env.VISUAL || process.env.EDITOR;
		if (environmentEditor) {
			return environmentEditor;
		}
		return process.platform === "win32" ? "notepad" : "nano";
	}

	setHideThinkingBlock(hide: boolean): void {
		this.globalSettings.hideThinkingBlock = hide;
		this.markModified("hideThinkingBlock");
		this.save();
	}

	setShadedCodeBlocks(shaded: boolean): void {
		this.globalSettings.shadedCodeBlocks = shaded;
		this.markModified("shadedCodeBlocks");
		this.save();
	}

	setShowCacheMissNotices(show: boolean): void {
		this.globalSettings.showCacheMissNotices = show;
		this.markModified("showCacheMissNotices");
		this.save();
	}

	getShellPath(): string | undefined {
		const shellPath = this.settings.shellPath;
		return shellPath ? normalizePath(shellPath) : shellPath;
	}

	setShellPath(path: string | undefined): void {
		this.globalSettings.shellPath = path;
		this.markModified("shellPath");
		this.save();
	}

	getQuietStartup(): QuietStartup {
		const value = this.settings.quietStartup;
		return value === true || value === "header" ? value : SETTINGS_DEFAULTS.quietStartup;
	}

	setQuietStartup(quiet: QuietStartup): void {
		this.globalSettings.quietStartup = quiet;
		this.markModified("quietStartup");
		this.save();
	}

	getDefaultProjectTrust(): DefaultProjectTrust {
		const value = this.globalSettings.defaultProjectTrust;
		return value === "always" || value === "never" ? value : SETTINGS_DEFAULTS.defaultProjectTrust;
	}

	setDefaultProjectTrust(defaultProjectTrust: DefaultProjectTrust): void {
		this.globalSettings.defaultProjectTrust = defaultProjectTrust;
		this.markModified("defaultProjectTrust");
		this.save();
	}

	getShellCommandPrefix(): string | undefined {
		return this.settings.shellCommandPrefix;
	}

	setShellCommandPrefix(prefix: string | undefined): void {
		this.globalSettings.shellCommandPrefix = prefix;
		this.markModified("shellCommandPrefix");
		this.save();
	}

	getNpmCommand(): string[] | undefined {
		return this.settings.npmCommand ? [...this.settings.npmCommand] : undefined;
	}

	setNpmCommand(command: string[] | undefined): void {
		this.globalSettings.npmCommand = command ? [...command] : undefined;
		this.markModified("npmCommand");
		this.save();
	}

	getCollapseChangelog(): boolean {
		return this.settings.collapseChangelog ?? SETTINGS_DEFAULTS.collapseChangelog;
	}

	setCollapseChangelog(collapse: boolean): void {
		this.globalSettings.collapseChangelog = collapse;
		this.markModified("collapseChangelog");
		this.save();
	}

	getEnableInstallTelemetry(): boolean {
		return this.settings.enableInstallTelemetry ?? SETTINGS_DEFAULTS.enableInstallTelemetry;
	}

	setEnableInstallTelemetry(enabled: boolean): void {
		this.globalSettings.enableInstallTelemetry = enabled;
		this.markModified("enableInstallTelemetry");
		this.save();
	}

	getEnableAnalytics(): boolean {
		return this.settings.enableAnalytics ?? SETTINGS_DEFAULTS.enableAnalytics;
	}

	getTrackingId(): string | undefined {
		return this.settings.trackingId;
	}

	/** Set the analytics opt-in preference; generates a tracking identifier on first opt-in */
	setEnableAnalytics(enabled: boolean): void {
		this.globalSettings.enableAnalytics = enabled;
		this.markModified("enableAnalytics");
		if (enabled && !this.globalSettings.trackingId) {
			this.globalSettings.trackingId = randomUUID();
			this.markModified("trackingId");
		}
		this.save();
	}

	/**
	 * Stable ID of this installation, e.g. sent to OpenAI as its agent host ID.
	 * Created on first use. Project settings are ignored so a committed project
	 * settings file cannot give every clone the same ID.
	 */
	getOrCreateDeviceId(): string {
		if (!this.globalSettings.deviceId) {
			this.globalSettings.deviceId = randomUUID();
			this.markModified("deviceId");
			this.save();
		}
		return this.globalSettings.deviceId;
	}

	/** As in Pi, a project `packages` list replaces the global one. A project Pi-Bolt list alone does not. */
	private getPackageSettingsScope(): SettingsScope {
		return this.projectSettings.packages !== undefined ? "project" : "global";
	}

	private mergeSettings(): Settings {
		this.packageSettings = this.getPackageSettingsScope() === "project" ? this.projectSettings : this.globalSettings;
		return deepMergeSettings(this.globalSettings, this.projectSettings);
	}

	/** Resolved runtime packages with project-list-over-global SDK precedence and non-persistent applyOverrides. */
	getPackages(): PackageSource[] {
		const scope = this.getPackageSettingsScope();
		const packages = this.resolvePackages(this.packageSettings, scope);
		if (scope === "project" || PIBOLT === undefined || !Array.isArray(this.projectSettings.piBolt?.packages)) {
			return packages;
		}
		// Project Pi-Bolt entries without project `packages` add to the global packages or replace one of the same
		// identity, as the package manager loads them; their exclusions apply only to the project's own (empty) list.
		const project = this.resolvePackages(this.projectSettings, "project");
		const identity = (entry: PackageSource, entryScope: SettingsScope) =>
			getPackageIdentity(
				typeof entry === "string" ? entry : entry.source,
				getPackageBaseDir(entryScope, this.settingsPaths),
			);
		const projectIdentities = new Set(project.map((entry) => identity(entry, "project")));
		return [...packages.filter((entry) => !projectIdentities.has(identity(entry, "global"))), ...project];
	}

	/** Resolve only this file's overlay. Custom storage uses agent dir / cwd/.pi unless baseDir is supplied. */
	getResolvedPackages(scope: SettingsScope, baseDir?: string): PackageSource[] {
		const settings = scope === "project" ? this.projectSettings : this.globalSettings;
		return this.resolvePackages(settings, scope, baseDir);
	}

	/** Flush queued writes and read current package declarations without resetting unrelated runtime settings. */
	async getCurrentPackageSettings(scope: SettingsScope): Promise<Pick<Settings, "packages" | "piBolt">> {
		await this.flush();
		if (scope === "project" && !this.projectTrusted) return {};
		let content: string | undefined;
		if (this.storage.read) content = this.storage.read(scope);
		else
			this.storage.withLock(scope, (current) => {
				content = current;
				return undefined;
			});
		// An unreadable declaration file must abort removal, not authorise deletion of shared files.
		let persisted: Settings = content ? JSON.parse(stripBom(content)) : {};
		if (PIBOLT !== undefined) {
			// A file written by an older Pi-Bolt since this manager loaded it still holds a legacy copy.
			persisted =
				(withLegacyPiBoltPackagesConverted(
					persisted as Record<string, unknown>,
					getPackageBaseDir(scope, this.settingsPaths),
				) as Settings | undefined) ?? persisted;
		}
		const current = scope === "project" ? this.projectSettings : this.globalSettings;
		const modified = scope === "project" ? this.modifiedProjectFields : this.modifiedFields;
		const nested = scope === "project" ? this.modifiedProjectNestedFields : this.modifiedNestedFields;
		return structuredClone({
			packages: modified.has("packages") ? current.packages : persisted.packages,
			piBolt: {
				packages: nested.get("piBolt")?.has("packages") ? current.piBolt?.packages : persisted.piBolt?.packages,
			},
		});
	}

	private resolvePackages(settings: Settings, scope: SettingsScope, baseDir?: string): PackageSource[] {
		const shared = settings.packages ?? [];
		baseDir ??= getPackageBaseDir(scope, this.settingsPaths);
		const own = Array.isArray(settings.piBolt?.packages) ? settings.piBolt.packages : [];
		return structuredClone(PIBOLT === undefined ? shared : resolvePackageOverlay(shared, own, baseDir));
	}

	/**
	 * Write raw shared declarations. The piBolt option explicitly selects the additive Pi-Bolt list, marked as an overlay so
	 * it is never taken for a legacy copy (withLegacyPiBoltPackagesConverted).
	 */
	setPackages(packages: PackageSource[], options?: { piBolt?: boolean }): void {
		if (options?.piBolt) {
			if (PIBOLT === undefined) throw new Error("Pi-Bolt packages require a Pi-Bolt build");
			this.globalSettings.piBolt = {
				...this.globalSettings.piBolt,
				packages: structuredClone(packages),
				packagesOverlay: true,
			};
			this.markModified("piBolt", "packages");
			this.markModified("piBolt", "packagesOverlay");
		} else {
			this.globalSettings.packages = structuredClone(packages);
			this.markModified("packages");
		}
		this.save();
	}

	/** Write raw project declarations; never pass the resolved overlay here. */
	setProjectPackages(packages: PackageSource[], options?: { piBolt?: boolean }): void {
		if (options?.piBolt) {
			if (PIBOLT === undefined) throw new Error("Pi-Bolt packages require a Pi-Bolt build");
			this.assertProjectTrustedForWrite();
			const settings = this.getProjectSettings();
			settings.piBolt = { ...settings.piBolt, packages: structuredClone(packages), packagesOverlay: true };
			this.markProjectModified("piBolt", "packages");
			this.markProjectModified("piBolt", "packagesOverlay");
			this.saveProjectSettings(settings);
		} else {
			this.updateProjectSettings("packages", (settings) => {
				settings.packages = structuredClone(packages);
			});
		}
	}

	getExtensionPaths(): string[] {
		return [...(this.settings.extensions ?? [])];
	}

	setExtensionPaths(paths: string[]): void {
		this.globalSettings.extensions = paths;
		this.markModified("extensions");
		this.save();
	}

	setProjectExtensionPaths(paths: string[]): void {
		this.updateProjectSettings("extensions", (settings) => {
			settings.extensions = paths;
		});
	}

	getSkillPaths(): string[] {
		return [...(this.settings.skills ?? [])];
	}

	setSkillPaths(paths: string[]): void {
		this.globalSettings.skills = paths;
		this.markModified("skills");
		this.save();
	}

	setProjectSkillPaths(paths: string[]): void {
		this.updateProjectSettings("skills", (settings) => {
			settings.skills = paths;
		});
	}

	getPromptTemplatePaths(): string[] {
		return [...(this.settings.prompts ?? [])];
	}

	setPromptTemplatePaths(paths: string[]): void {
		this.globalSettings.prompts = paths;
		this.markModified("prompts");
		this.save();
	}

	setProjectPromptTemplatePaths(paths: string[]): void {
		this.updateProjectSettings("prompts", (settings) => {
			settings.prompts = paths;
		});
	}

	getThemePaths(): string[] {
		return [...(this.settings.themes ?? [])];
	}

	setThemePaths(paths: string[]): void {
		this.globalSettings.themes = paths;
		this.markModified("themes");
		this.save();
	}

	setProjectThemePaths(paths: string[]): void {
		this.updateProjectSettings("themes", (settings) => {
			settings.themes = paths;
		});
	}

	getEnableSkillCommands(): boolean {
		return this.settings.enableSkillCommands ?? SETTINGS_DEFAULTS.enableSkillCommands;
	}

	setEnableSkillCommands(enabled: boolean): void {
		this.globalSettings.enableSkillCommands = enabled;
		this.markModified("enableSkillCommands");
		this.save();
	}

	getThinkingBudgets(): ThinkingBudgetsSettings | undefined {
		return this.settings.thinkingBudgets;
	}

	getTerminalCapabilityOverrides(): Partial<TerminalCapabilities> {
		const terminal = this.settings.terminal;
		const images = terminal?.images;
		return {
			...(images === "kitty" || images === "iterm2" ? { images } : images === false ? { images: null } : {}),
			...(typeof terminal?.trueColor === "boolean" ? { trueColor: terminal.trueColor } : {}),
			...(typeof terminal?.hyperlinks === "boolean" ? { hyperlinks: terminal.hyperlinks } : {}),
		};
	}

	getShowImages(): boolean {
		return this.settings.terminal?.showImages ?? SETTINGS_DEFAULTS.terminal.showImages;
	}

	setShowImages(show: boolean): void {
		if (!this.globalSettings.terminal) {
			this.globalSettings.terminal = {};
		}
		this.globalSettings.terminal.showImages = show;
		this.markModified("terminal", "showImages");
		this.save();
	}

	getImageWidthCells(): number {
		const width = this.settings.terminal?.imageWidthCells;
		if (typeof width !== "number" || !Number.isFinite(width)) {
			return SETTINGS_DEFAULTS.terminal.imageWidthCells;
		}
		return Math.max(1, Math.floor(width));
	}

	setImageWidthCells(width: number): void {
		if (!this.globalSettings.terminal) {
			this.globalSettings.terminal = {};
		}
		this.globalSettings.terminal.imageWidthCells = Math.max(1, Math.floor(width));
		this.markModified("terminal", "imageWidthCells");
		this.save();
	}

	getClearOnShrink(): boolean {
		// Settings takes precedence, then env var, then default false
		if (this.settings.terminal?.clearOnShrink !== undefined) {
			return this.settings.terminal.clearOnShrink;
		}
		if (process.env.PI_CLEAR_ON_SHRINK === "1") return true;
		return SETTINGS_DEFAULTS.terminal.clearOnShrink;
	}

	setClearOnShrink(enabled: boolean): void {
		if (!this.globalSettings.terminal) {
			this.globalSettings.terminal = {};
		}
		this.globalSettings.terminal.clearOnShrink = enabled;
		this.markModified("terminal", "clearOnShrink");
		this.save();
	}

	getShowTerminalProgress(): boolean {
		return this.settings.terminal?.showTerminalProgress ?? SETTINGS_DEFAULTS.terminal.showTerminalProgress;
	}

	setShowTerminalProgress(enabled: boolean): void {
		if (!this.globalSettings.terminal) {
			this.globalSettings.terminal = {};
		}
		this.globalSettings.terminal.showTerminalProgress = enabled;
		this.markModified("terminal", "showTerminalProgress");
		this.save();
	}

	getTuiMode(): TuiMode {
		return this.settings.tuiMode === "regular" ? "regular" : SETTINGS_DEFAULTS.tuiMode;
	}

	setTuiMode(mode: TuiMode): void {
		this.globalSettings.tuiMode = mode;
		this.markModified("tuiMode");
		this.save();
	}

	getFullscreenExitOutput(): FullscreenExitOutput {
		return this.settings.fullscreenExitOutput === "resume-hint"
			? "resume-hint"
			: SETTINGS_DEFAULTS.fullscreenExitOutput;
	}

	setFullscreenExitOutput(output: FullscreenExitOutput): void {
		this.globalSettings.fullscreenExitOutput = output;
		this.markModified("fullscreenExitOutput");
		this.save();
	}

	getFullscreenScrollbar(): ScrollViewScrollbar {
		const mode = this.settings.fullscreenScrollbar;
		return mode === "always" || mode === "hidden" ? mode : SETTINGS_DEFAULTS.fullscreenScrollbar;
	}

	setFullscreenScrollbar(mode: ScrollViewScrollbar): void {
		this.globalSettings.fullscreenScrollbar = mode;
		this.markModified("fullscreenScrollbar");
		this.save();
	}

	getFullscreenCopyOnSelect(): boolean {
		return this.settings.fullscreenCopyOnSelect ?? SETTINGS_DEFAULTS.fullscreenCopyOnSelect;
	}

	setFullscreenCopyOnSelect(enabled: boolean): void {
		this.globalSettings.fullscreenCopyOnSelect = enabled;
		this.markModified("fullscreenCopyOnSelect");
		this.save();
	}

	getFullscreenWheelScrollLines(): WheelScrollLines {
		const lines = this.settings.fullscreenWheelScrollLines;
		return typeof lines === "number" && Number.isFinite(lines)
			? Math.max(1, Math.min(100, Math.floor(lines)))
			: SETTINGS_DEFAULTS.fullscreenWheelScrollLines;
	}

	setFullscreenWheelScrollLines(lines: WheelScrollLines): void {
		this.globalSettings.fullscreenWheelScrollLines =
			lines === "auto" ? lines : Math.max(1, Math.min(100, Math.floor(lines)));
		this.markModified("fullscreenWheelScrollLines");
		this.save();
	}

	getImageAutoResize(): boolean {
		return this.settings.images?.autoResize ?? SETTINGS_DEFAULTS.images.autoResize;
	}

	setImageAutoResize(enabled: boolean): void {
		if (!this.globalSettings.images) {
			this.globalSettings.images = {};
		}
		this.globalSettings.images.autoResize = enabled;
		this.markModified("images", "autoResize");
		this.save();
	}

	getBlockImages(): boolean {
		return this.settings.images?.blockImages ?? SETTINGS_DEFAULTS.images.blockImages;
	}

	setBlockImages(blocked: boolean): void {
		if (!this.globalSettings.images) {
			this.globalSettings.images = {};
		}
		this.globalSettings.images.blockImages = blocked;
		this.markModified("images", "blockImages");
		this.save();
	}

	getEnabledModels(): string[] | undefined {
		return this.settings.enabledModels;
	}

	/** The resolved `defaultTools` selection, or undefined when no settings layer sets it. */
	/** `base`: what a list of only `+name`/`-name` entries changes (Pi-Bolt: `defaultToolNamesFor()`, which knows the extensions). */
	getDefaultTools(base: readonly string[] = DEFAULT_TOOL_NAMES): string[] | undefined {
		const tools = this.settings.defaultTools;
		if (tools === undefined) return undefined;
		return resolveDefaultTools(Array.isArray(tools) ? tools.filter((tool) => typeof tool === "string") : [], base);
	}

	setEnabledModels(patterns: string[] | undefined): void {
		this.globalSettings.enabledModels = patterns;
		this.markModified("enabledModels");
		this.save();
	}

	getDoubleEscapeAction(): "fork" | "tree" | "none" {
		return this.settings.doubleEscapeAction ?? SETTINGS_DEFAULTS.doubleEscapeAction;
	}

	setDoubleEscapeAction(action: "fork" | "tree" | "none"): void {
		this.globalSettings.doubleEscapeAction = action;
		this.markModified("doubleEscapeAction");
		this.save();
	}

	getTreeFilterMode(): "default" | "no-tools" | "user-only" | "labeled-only" | "all" {
		const mode = this.settings.treeFilterMode;
		const valid = ["default", "no-tools", "user-only", "labeled-only", "all"];
		return mode && valid.includes(mode) ? mode : SETTINGS_DEFAULTS.treeFilterMode;
	}

	setTreeFilterMode(mode: "default" | "no-tools" | "user-only" | "labeled-only" | "all"): void {
		this.globalSettings.treeFilterMode = mode;
		this.markModified("treeFilterMode");
		this.save();
	}

	getShowHardwareCursor(): boolean {
		return this.settings.showHardwareCursor ?? process.env.PI_HARDWARE_CURSOR === "1";
	}

	setShowHardwareCursor(enabled: boolean): void {
		this.globalSettings.showHardwareCursor = enabled;
		this.markModified("showHardwareCursor");
		this.save();
	}

	getEditorPaddingX(): number {
		return this.settings.editorPaddingX ?? SETTINGS_DEFAULTS.editorPaddingX;
	}

	setEditorPaddingX(padding: number): void {
		this.globalSettings.editorPaddingX = Math.max(0, Math.min(3, Math.floor(padding)));
		this.markModified("editorPaddingX");
		this.save();
	}

	getOutputPad(): 0 | 1 {
		return this.settings.outputPad === 0 ? 0 : SETTINGS_DEFAULTS.outputPad;
	}

	setOutputPad(padding: 0 | 1): void {
		this.globalSettings.outputPad = padding;
		this.markModified("outputPad");
		this.save();
	}

	getAutocompleteMaxVisible(): number {
		return this.settings.autocompleteMaxVisible ?? SETTINGS_DEFAULTS.autocompleteMaxVisible;
	}

	setAutocompleteMaxVisible(maxVisible: number): void {
		this.globalSettings.autocompleteMaxVisible = Math.max(3, Math.min(20, Math.floor(maxVisible)));
		this.markModified("autocompleteMaxVisible");
		this.save();
	}

	getCodeBlockIndent(): string {
		return this.settings.markdown?.codeBlockIndent ?? SETTINGS_DEFAULTS.markdown.codeBlockIndent;
	}

	getMermaidRenderingMode(): MermaidRenderingMode {
		const mode = this.settings.markdown?.mermaid;
		return mode === "off" || mode === "final" ? mode : SETTINGS_DEFAULTS.markdown.mermaid;
	}

	setMermaidRenderingMode(mode: MermaidRenderingMode): void {
		this.globalSettings.markdown ??= {};
		this.globalSettings.markdown.mermaid = mode;
		this.markModified("markdown", "mermaid");
		this.save();
	}

	getWarnings(): WarningSettings {
		return { ...(this.settings.warnings ?? {}) };
	}

	setWarnings(warnings: WarningSettings): void {
		this.globalSettings.warnings = { ...warnings };
		this.markModified("warnings");
		this.save();
	}
}
