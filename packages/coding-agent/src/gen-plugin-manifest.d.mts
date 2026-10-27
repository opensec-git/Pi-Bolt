/** The types of gen-plugin-manifest.mjs, which `pi-bolt compile` (pi-bolt-compile.ts) imports. */

/** An extension the plan compiles in. */
export interface PlannedPlugin {
	/** The name this prints, which the manifest gives it. */
	name: string;
	entry: string;
	origin: string;
	stageDir: string;
	importPath: string;
	/** An entry imported under another name: its copy, beside it. */
	entryCopy?: { from: string; to: string };
	/** The other names --only and --exclude take for it (namesPlugin). */
	aliases: string[];
}

/** One --only or --exclude leaves out. */
export interface ExcludedPlugin {
	name: string;
	origin: string;
	entry: string;
	by: "only" | "exclude";
}

export interface PluginPlan {
	plugins: PlannedPlugin[];
	roots: Array<{ stageDir: string; root: string; origin: string; files?: string[] }>;
	/** The packages copied into the node_modules beside the manifest, and the folder each was copied from. */
	dependencies: Array<{ name: string; from: string }>;
	skipped: Array<{ path: string; reason: string }>;
	excluded: ExcludedPlugin[];
	/** Every extension found, compiled in or not, with the other names --only and --exclude take for it. */
	found: Array<Pick<PlannedPlugin, "name" | "aliases">>;
	/** The values of --only and --exclude that name no extension found. */
	unknown: { only: string[]; exclude: string[] };
	/** The extensions both --only and --exclude name. */
	contradicted: string[];
}

export interface PlanOptions {
	agentDir: string;
	cwd?: string;
	only?: string[];
	exclude?: string[];
	noPackages?: boolean;
	packages?: Array<{ source: string; root: string; entries: string[] }>;
	/** The extensions Pi loads (paths): any other found is turned off. */
	enabled?: string[];
}

export const PI_MODULE_ALIASES: Record<string, string>;
export function resolveExtensionEntries(dir: string): string[] | undefined;
export function collectAutoExtensionEntries(dir: string): string[];
export function ignoreRulesOf(dir: string): (name: string, folder: boolean) => boolean;
export function expandPackageEntry(entry: string): string[];
export function planPlugins(options: PlanOptions): PluginPlan;
export function namesPlugin(value: string, plugin: Pick<PlannedPlugin, "name" | "aliases">): boolean;
export function selectionProblem(plan: PluginPlan, agentDir: string): string | undefined;
export function rewriteCopy(source: string, file: string): string;
export function renderManifest(plan: Pick<PluginPlan, "plugins" | "roots">): string;
export function writePluginFolder(plan: PluginPlan, out: string): string;
export function copyDependenciesFound(plan: PluginPlan, out: string): Promise<Array<{ name: string; from: string }>>;
export function withoutBunCondition(value: unknown): unknown;
export function forBuildWithoutJit(dir: string): void;
export function defaultAgentDir(env?: Record<string, string | undefined>): string;
export function main(argv: string[]): Promise<void>;
