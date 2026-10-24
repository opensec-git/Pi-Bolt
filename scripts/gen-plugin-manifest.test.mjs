/**
 * Covers gen-plugin-manifest.mjs, and in particular the discovery rules it repeats from Pi.
 *
 * Those rules are Pi's: packages/coding-agent/src/core/extensions/loader.ts, `discoverExtensionsInDir` and
 * `resolveExtensionEntries`. The generator cannot import them, because they are private to Pi and this script belongs to
 * Pi-Bolt. `pi rules` below is therefore a case for every rule in Pi's own list, so that a change in Pi fails this test
 * instead of quietly shortening a manifest. A plugin missing from a manifest still loads at run time, so a short manifest
 * is invisible.
 */

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
	chmodSync,
	mkdirSync,
	mkdtempSync,
	readFileSync,
	realpathSync,
	rmSync,
	symlinkSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { after, describe, it } from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";
import ignore from "ignore";
import {
	defaultAgentDir,
	expandPackageEntry,
	ignoreRulesOf,
	pinImportMeta,
	planPlugins,
	renderManifest,
	writePluginFolder,
} from "./gen-plugin-manifest.mjs";

const temporary = [];
function scratch(prefix = "pibolt-plugins-") {
	const dir = mkdtempSync(join(tmpdir(), prefix));
	temporary.push(dir);
	return dir;
}
after(() => {
	for (const dir of temporary) rmSync(dir, { recursive: true, force: true });
});

/** An extension whose factory does nothing: discovery looks at where a file is, not at what is in it. */
const FACTORY = "export default () => {};\n";

function write(file, contents = FACTORY) {
	mkdirSync(dirname(file), { recursive: true });
	writeFileSync(file, contents);
}

/** An agent directory with a settings.json, as the generator reads it. */
function agent(settings = {}) {
	const dir = scratch();
	mkdirSync(join(dir, "extensions"), { recursive: true });
	writeFileSync(join(dir, "settings.json"), JSON.stringify(settings));
	return dir;
}

function installPackage(agentDir, name, manifest, files) {
	const dir = join(agentDir, "npm", "node_modules", ...name.split("/"));
	write(join(dir, "package.json"), JSON.stringify({ name, version: "1.0.0", ...manifest }));
	for (const file of files) write(join(dir, file));
	return dir;
}

const names = (plan) => plan.plugins.map((plugin) => plugin.name);

describe("pi rules", () => {
	it("rule 1: a direct .ts or .js file in the folder", () => {
		const agentDir = agent();
		write(join(agentDir, "extensions", "ban-sleep.ts"));
		write(join(agentDir, "extensions", "legacy.js"));
		write(join(agentDir, "extensions", "notes.md"), "# not an extension");

		assert.deepEqual(names(planPlugins({ agentDir })).sort(), ["ban-sleep", "legacy"]);
	});

	it("rule 2: a subfolder with an index, .ts before .js", () => {
		const agentDir = agent();
		write(join(agentDir, "extensions", "reader", "index.ts"));
		write(join(agentDir, "extensions", "reader", "index.js"));

		const plan = planPlugins({ agentDir });
		assert.deepEqual(names(plan), ["reader"]);
		assert.ok(plan.plugins[0].entry.endsWith("index.ts"));
	});

	it("rule 3: a package.json with a pi.extensions list, which wins over an index", () => {
		const agentDir = agent();
		write(join(agentDir, "extensions", "tools", "index.ts"));
		write(join(agentDir, "extensions", "tools", "src", "alpha.ts"));
		write(join(agentDir, "extensions", "tools", "src", "beta.ts"));
		write(
			join(agentDir, "extensions", "tools", "package.json"),
			JSON.stringify({ name: "tools", pi: { extensions: ["src/alpha.ts", "src/beta.ts"] } }),
		);

		assert.deepEqual(names(planPlugins({ agentDir })), ["tools-alpha", "tools-beta"]);
	});

	it("a declared entry that does not exist is ignored, and the folder falls back to its index", () => {
		const agentDir = agent();
		write(join(agentDir, "extensions", "half", "index.ts"));
		write(
			join(agentDir, "extensions", "half", "package.json"),
			JSON.stringify({ name: "half", pi: { extensions: ["src/absent.ts"] } }),
		);

		assert.deepEqual(names(planPlugins({ agentDir })), ["half"]);
	});

	it("does not recurse past one level", () => {
		const agentDir = agent();
		write(join(agentDir, "extensions", "deep", "nested", "index.ts"));

		assert.deepEqual(names(planPlugins({ agentDir })), []);
	});

	it("takes a symbolic link, as Pi does", () => {
		const agentDir = agent();
		const elsewhere = scratch("pibolt-elsewhere-");
		write(join(elsewhere, "index.ts"));
		symlinkSync(elsewhere, join(agentDir, "extensions", "linked"), "dir");

		assert.deepEqual(names(planPlugins({ agentDir })), ["linked"]);
	});
});

describe("where it looks", () => {
	it("takes the agent directory, a project, and the extensions setting, in Pi's order", () => {
		const outside = scratch("pibolt-configured-");
		write(join(outside, "bridge", "index.ts"));
		const agentDir = agent({ extensions: [join(outside, "bridge", "index.ts")] });
		write(join(agentDir, "extensions", "global-one.ts"));
		const project = scratch("pibolt-project-");
		write(join(project, ".pi", "extensions", "project-one.ts"));

		const plan = planPlugins({ agentDir, cwd: project });
		assert.deepEqual(names(plan), ["project-one", "global-one", "bridge"]);
		assert.deepEqual(
			plan.plugins.map((plugin) => plugin.origin),
			["project", "global", "configured"],
		);
	});

	it("names a configured path after its folder, because `index` names nothing", () => {
		const outside = scratch("pibolt-configured-");
		for (const name of ["pap-code-bridge", "autolith-bridge"]) write(join(outside, name, "index.ts"));
		const agentDir = agent({
			extensions: [join(outside, "pap-code-bridge", "index.ts"), join(outside, "autolith-bridge", "index.ts")],
		});

		assert.deepEqual(names(planPlugins({ agentDir })), ["pap-code-bridge", "autolith-bridge"]);
	});

	it("does not read a project's settings, because it cannot tell whether the project is trusted", () => {
		const agentDir = agent();
		const project = scratch("pibolt-project-");
		write(join(project, ".pi", "extensions", "trusted-folder.ts"));
		const outside = scratch("pibolt-untrusted-");
		write(join(outside, "from-project-settings.ts"));
		writeFileSync(
			join(project, ".pi", "settings.json"),
			JSON.stringify({ extensions: [join(outside, "from-project-settings.ts")] }),
		);

		assert.deepEqual(names(planPlugins({ agentDir, cwd: project })), ["trusted-folder"]);
	});

	it("keeps a project and a global extension of the same name apart", () => {
		const agentDir = agent();
		write(join(agentDir, "extensions", "preset", "index.ts"));
		const project = scratch("pibolt-project-");
		write(join(project, ".pi", "extensions", "preset", "index.ts"));

		assert.deepEqual(names(planPlugins({ agentDir, cwd: project })), ["preset", "preset-global"]);
	});
});

describe("packages installed with pi install", () => {
	it("expands a declared directory into concrete imports, as pi-computer-use requires", () => {
		const agentDir = agent({ packages: ["npm:@injaneity/pi-computer-use"] });
		const dir = installPackage(agentDir, "@injaneity/pi-computer-use", { pi: { extensions: ["./extensions"] } }, [
			"extensions/computer-use.ts",
		]);

		const plan = planPlugins({ agentDir });
		assert.deepEqual(names(plan), ["injaneity-pi-computer-use"]);
		assert.equal(plan.plugins[0].entry, join(dir, "extensions", "computer-use.ts"));
		assert.equal(plan.plugins[0].importPath, "./package-injaneity-pi-computer-use/extensions/computer-use.ts");

		const out = join(scratch(), "folder");
		const manifest = writePluginFolder(plan, out);
		assert.match(
			readFileSync(manifest, "utf8"),
			/from "\.\/package-injaneity-pi-computer-use\/extensions\/computer-use\.ts";/,
		);
		assert.equal(readFileSync(join(out, plan.plugins[0].importPath), "utf8"), FACTORY);
	});

	it("takes a directory's index before discovering sibling helpers", () => {
		const agentDir = agent({ packages: ["npm:with-index"] });
		const dir = installPackage(agentDir, "with-index", { pi: { extensions: ["./extensions"] } }, [
			"extensions/index.ts",
			"extensions/index.js",
			"extensions/helper.ts",
		]);

		assert.deepEqual(
			planPlugins({ agentDir }).plugins.map((plugin) => plugin.entry),
			[join(dir, "extensions", "index.ts")],
		);
	});

	it("expands mixed file and directory entries once, without scanning arbitrary nested helpers", () => {
		const agentDir = agent({ packages: ["npm:mixed"] });
		const dir = installPackage(
			agentDir,
			"mixed",
			{ pi: { extensions: ["./extensions", "./extra.ts", "./extensions/alpha.ts"] } },
			[
				"extensions/alpha.ts",
				"extensions/beta.js",
				"extensions/nested/index.ts",
				"extensions/shared/deeper/helper.ts",
				"extra.ts",
			],
		);

		const plan = planPlugins({ agentDir });
		assert.deepEqual(names(plan), ["mixed-alpha", "mixed-beta", "mixed-index", "mixed-extra"]);
		assert.deepEqual(plan.plugins.map((plugin) => plugin.entry), [
			join(dir, "extensions", "alpha.ts"),
			join(dir, "extensions", "beta.js"),
			join(dir, "extensions", "nested", "index.ts"),
			join(dir, "extra.ts"),
		]);
	});

	it("honours a declared directory's own manifest before its index", () => {
		const agentDir = agent({ packages: ["npm:nested-manifest"] });
		const dir = installPackage(agentDir, "nested-manifest", { pi: { extensions: ["./extensions"] } }, [
			"extensions/index.ts",
			"extensions/src/entry.ts",
		]);
		write(join(dir, "extensions", "package.json"), JSON.stringify({ pi: { extensions: ["./src/entry.ts"] } }));

		assert.deepEqual(
			planPlugins({ agentDir }).plugins.map((plugin) => plugin.entry),
			[join(dir, "extensions", "src", "entry.ts")],
		);
	});

	it("reports a declared directory containing no extension instead of importing the directory", () => {
		const agentDir = agent({ packages: ["npm:empty-directory"] });
		const dir = installPackage(agentDir, "empty-directory", { pi: { extensions: ["./extensions"] } }, [
			"extensions/README.md",
		]);

		const plan = planPlugins({ agentDir });
		assert.deepEqual(plan.plugins, []);
		assert.match(plan.skipped.find((entry) => entry.path === dir).reason, /no entry point/);
	});

	it("checks concrete directory entries for cores that need vendoring", () => {
		const agentDir = agent({ packages: ["npm:directory-core"] });
		const dir = installPackage(agentDir, "directory-core", { pi: { extensions: ["./dist-bun"] } }, [
			"dist-bun/index.js",
			"dist-bun/core.cjs",
		]);

		const plan = planPlugins({ agentDir });
		assert.deepEqual(plan.plugins, []);
		assert.match(plan.skipped.find((entry) => entry.path === dir).reason, /vendoring/);
	});

	it("names a package after the package, not after its entry file", () => {
		const agentDir = agent({ packages: ["npm:pi-provider-litellm"] });
		installPackage(agentDir, "pi-provider-litellm", { pi: { extensions: ["./src/index.ts"] } }, ["src/index.ts"]);

		const plan = planPlugins({ agentDir });
		assert.deepEqual(names(plan), ["pi-provider-litellm"]);
		assert.equal(plan.plugins[0].importPath, "./package-pi-provider-litellm/src/index.ts");
	});

	it("takes a pinned version and a scope, and flattens a name a setting could not carry", () => {
		const agentDir = agent({ packages: ["npm:@scope/thing", "npm:pinned@2.13.0"] });
		installPackage(agentDir, "@scope/thing", { pi: { extensions: ["./index.js"] } }, ["index.js"]);
		installPackage(agentDir, "pinned", { pi: { extensions: ["./index.js"] } }, ["index.js"]);

		assert.deepEqual(names(planPlugins({ agentDir })), ["scope-thing", "pinned"]);
	});

	it("copies the dependency closure, which is hoisted beside the package and not inside it", () => {
		const agentDir = agent({ packages: ["npm:needs-deps"] });
		installPackage(agentDir, "needs-deps", { pi: { extensions: ["./index.js"] }, dependencies: { direct: "1" } }, [
			"index.js",
		]);
		installPackage(agentDir, "direct", { dependencies: { transitive: "1" } }, ["index.js"]);
		installPackage(agentDir, "transitive", {}, ["index.js"]);
		installPackage(agentDir, "unrelated", {}, ["index.js"]);

		const plan = planPlugins({ agentDir });
		assert.deepEqual(
			plan.dependencies.map((dependency) => dependency.name).sort(),
			["direct", "transitive"],
		);

		const out = join(scratch(), "folder");
		writePluginFolder(plan, out);
		assert.ok(readFileSync(join(out, "node_modules", "transitive", "index.js"), "utf8"));
		assert.throws(() => readFileSync(join(out, "node_modules", "unrelated", "index.js"), "utf8"));
	});

	it("copies an installed optionalDependency, which the bundler still has to resolve", () => {
		// pi-lens declares web-tree-sitter optional. Leaving it out failed the build; it did not degrade.
		const agentDir = agent({ packages: ["npm:lens-like", "npm:opt-absent"] });
		installPackage(
			agentDir,
			"lens-like",
			{ pi: { extensions: ["./index.js"] }, optionalDependencies: { "tree-sitter-ish": "1" } },
			["index.js"],
		);
		installPackage(agentDir, "tree-sitter-ish", {}, ["index.js"]);
		installPackage(agentDir, "opt-absent", { pi: { extensions: ["./index.js"] }, optionalDependencies: { gone: "1" } }, [
			"index.js",
		]);

		const plan = planPlugins({ agentDir });
		assert.deepEqual(
			plan.dependencies.map((dependency) => dependency.name),
			["tree-sitter-ish"],
		);
	});

	it("does not copy Pi's own packages, which would be a second Pi", () => {
		const agentDir = agent({ packages: ["npm:peer-user"] });
		installPackage(
			agentDir,
			"peer-user",
			{ pi: { extensions: ["./index.js"] }, dependencies: { "@earendil-works/pi-tui": "1", typebox: "1" } },
			["index.js"],
		);
		installPackage(agentDir, "@earendil-works/pi-tui", {}, ["index.js"]);
		installPackage(agentDir, "typebox", {}, ["index.js"]);

		assert.deepEqual(planPlugins({ agentDir }).dependencies, []);
	});

	it("takes the files of a folder in pi.extensions, as Pi does, and not the folder", () => {
		// pi-openrouter-session declares "./extensions", a folder holding one .ts file and no index.
		const agentDir = agent({ packages: ["npm:pi-openrouter-session", "npm:two-files"] });
		installPackage(agentDir, "pi-openrouter-session", { pi: { extensions: ["./extensions"] } }, [
			"extensions/openrouter-session.ts",
		]);
		installPackage(agentDir, "two-files", { pi: { extensions: ["./extensions"] } }, [
			"extensions/a.ts",
			"extensions/b/index.ts",
			"extensions/.hidden.ts",
			"extensions/node_modules/dep/index.ts",
		]);

		const plan = planPlugins({ agentDir });
		assert.deepEqual(
			plan.plugins.map((plugin) => [plugin.name, plugin.importPath]),
			[
				["pi-openrouter-session", "./package-pi-openrouter-session/extensions/openrouter-session.ts"],
				["two-files-a", "./package-two-files/extensions/a.ts"],
				["two-files-index", "./package-two-files/extensions/b/index.ts"],
			],
		);

		const out = join(scratch(), "folder");
		const manifest = writePluginFolder(plan, out);
		const imports = [...readFileSync(manifest, "utf8").matchAll(/^import \w+ from "\.\/(.+)";$/gm)];
		assert.equal(imports.length, 3);
		for (const [, path] of imports) assert.ok(readFileSync(join(out, path), "utf8"));
	});

	it("leaves out of a folder in pi.extensions what its ignore files leave out, as Pi does", () => {
		// A named-export-only helper.js listed in .ignore: imported as an extension, the build would stop (PR #110 review).
		const agentDir = agent({ packages: ["npm:ignoring"] });
		const dir = installPackage(agentDir, "ignoring", { pi: { extensions: ["./extensions"] } }, [
			"extensions/extension.js",
			"extensions/helper.js",
			"extensions/lib/index.ts",
			"extensions/kept/index.ts",
		]);
		write(join(dir, "extensions", ".ignore"), "helper.js\n");
		write(join(dir, "extensions", ".gitignore"), "# folders\n*/\n!kept/\n");

		assert.deepEqual(expandPackageEntry(join(dir, "extensions")), [
			join(dir, "extensions", "extension.js"),
			join(dir, "extensions", "kept", "index.ts"),
		]);
	});

	it("applies ignore rules as the ignore package Pi uses does, for names directly in the folder", () => {
		const rules = [
			"*.js", "!keep.js", "/anchored.ts", "dir/", "**/deep.ts", "**", "a/b.ts", "x/**", "[ab].ts", "[!c]x.ts", "?.ts",
			"file.ts   ", "esc\\ aped\\ ", "\\#hash.ts", "\\!bang.ts", "# comment", "", "a**b.ts", "*.d.ts", "q/",
		];
		const names = [
			["one.js", false], ["keep.js", false], ["anchored.ts", false], ["dir", true], ["dir", false], ["deep.ts", false],
			["b.ts", false], ["x", true], ["a.ts", false], ["c.ts", false], ["cx.ts", false], ["dx.ts", false], ["z.ts", false],
			["file.ts", false], ["esc aped ", false], ["#hash.ts", false], ["!bang.ts", false], ["axyb.ts", false],
			["types.d.ts", false], ["q", true], ["q", false], ["other.md", false],
		];
		// Each rule alone, then all of them in order, against Pi's handling (package-manager.ts, addIgnoreRules).
		for (const set of [...rules.map((rule) => [rule]), rules.filter((rule) => rule !== "**")]) {
			const dir = scratch();
			writeFileSync(join(dir, ".fdignore"), set.join("\n"));
			const ours = ignoreRulesOf(dir);
			const pis = ignore().add(
				set
					.filter((line) => line.trim() && !(line.trim().startsWith("#") && !line.trim().startsWith("\\#")))
					.map((line) => {
						const negated = line.startsWith("!");
						let pattern = negated || line.startsWith("\\!") ? line.slice(1) : line;
						if (pattern.startsWith("/")) pattern = pattern.slice(1);
						return negated ? `!${pattern}` : pattern;
					}),
			);
			for (const [name, folder] of names) {
				assert.equal(ours(name, folder), pis.ignores(folder ? `${name}/` : name), `${JSON.stringify(set)} ${name}${folder ? "/" : ""}`);
			}
		}
	});

	it("says a folder in pi.extensions with no extension in it has no entry point", () => {
		const agentDir = agent({ packages: ["npm:empty-folder"] });
		installPackage(agentDir, "empty-folder", { pi: { extensions: ["./extensions"] } }, ["extensions/README.md"]);

		const plan = planPlugins({ agentDir });
		assert.deepEqual(plan.plugins, []);
		assert.match(plan.skipped.map((entry) => entry.reason).join("\n"), /empty-folder: no entry point/);
	});

	it("passes over a Bun build that loads its core at run time, and says why", () => {
		const agentDir = agent({ packages: ["npm:opensec-pi-todo"] });
		const dir = installPackage(agentDir, "opensec-pi-todo", { pi: { extensions: ["./dist-bun/index.js"] } }, [
			"dist-bun/index.js",
			"dist-bun/core.cjs",
		]);

		const plan = planPlugins({ agentDir });
		assert.deepEqual(plan.plugins, []);
		assert.match(plan.skipped.find((entry) => entry.path === dir).reason, /vendoring/);
	});

	it("says so for a package that is absent, is not an npm source, or has no entry", () => {
		const agentDir = agent({ packages: ["npm:absent", "git:https://example.invalid/x.git", "npm:no-entry"] });
		installPackage(agentDir, "no-entry", {}, ["README.md"]);

		const reasons = planPlugins({ agentDir }).skipped.map((entry) => entry.reason).join("\n");
		assert.match(reasons, /not installed/);
		assert.match(reasons, /only npm: packages/);
		assert.match(reasons, /no entry point/);
	});

	it("leaves packages out with --no-packages", () => {
		const agentDir = agent({ packages: ["npm:a-package"] });
		write(join(agentDir, "extensions", "local.ts"));
		installPackage(agentDir, "a-package", { pi: { extensions: ["./index.js"] } }, ["index.js"]);

		assert.deepEqual(names(planPlugins({ agentDir, noPackages: true })), ["local"]);
	});
});

describe("the packages pi-bolt compile gives it (--packages)", () => {
	it("takes them instead of the packages setting, local folders too, each with the entries given (#108)", () => {
		// The settings name npm:pi-web-access; piBolt.packages removed it and added these, which pi-bolt compile resolved.
		const agentDir = agent({ packages: ["npm:pi-web-access"] });
		installPackage(agentDir, "pi-web-access", { pi: { extensions: ["./index.js"] } }, ["index.js"]);
		const fff = installPackage(agentDir, "opensec-pi-fff", { dependencies: { direct: "1" } }, ["src/index.ts"]);
		installPackage(agentDir, "direct", {}, ["index.js"]);
		const bundle = join(agentDir, "pi-bolt-bundles", "pi-bolt-web-access");
		write(join(bundle, "package.json"), JSON.stringify({ name: "pi-bolt-web-access", dependencies: { own: "1" } }));
		write(join(bundle, "extensions", "a.ts"));
		write(join(bundle, "node_modules", "own", "index.js"));
		const bare = join(agentDir, "pi-bolt-bundles", "no-manifest");
		write(join(bare, "index.ts"));

		const plan = planPlugins({
			agentDir,
			packages: [
				{ source: "npm:opensec-pi-fff@0.11.0", root: fff, entries: [join(fff, "src", "index.ts")] },
				{ source: "pi-bolt-bundles/pi-bolt-web-access", root: bundle, entries: [join(bundle, "extensions", "a.ts")] },
				{ source: "pi-bolt-bundles/no-manifest", root: bare, entries: [join(bare, "index.ts")] },
			],
		});
		assert.deepEqual(
			plan.plugins.map((plugin) => [plugin.name, plugin.origin, plugin.importPath]),
			[
				["opensec-pi-fff", "package", "./package-opensec-pi-fff/src/index.ts"],
				["pi-bolt-web-access", "package", "./package-pi-bolt-web-access/extensions/a.ts"],
				["no-manifest", "package", "./package-no-manifest/index.ts"],
			],
		);
		// An npm package's dependencies are hoisted beside it; a local folder's are in its own node_modules, copied with it.
		assert.deepEqual(
			plan.dependencies.map((dependency) => dependency.name),
			["direct"],
		);
		const out = join(scratch(), "folder");
		writePluginFolder(plan, out);
		assert.ok(readFileSync(join(out, "package-pi-bolt-web-access", "node_modules", "own", "index.js"), "utf8"));
	});

	it("finds a workspace package's dependencies hoisted above it, as Node does", () => {
		// workspace/packages/plugin imports `hoisted`, installed in workspace/node_modules (PR #114 review).
		const agentDir = agent();
		const workspace = join(scratch(), "workspace");
		const plugin = join(workspace, "packages", "plugin");
		write(join(plugin, "package.json"), JSON.stringify({ name: "plugin", dependencies: { hoisted: "1", own: "1" } }));
		write(join(plugin, "index.ts"), 'import value from "hoisted";\nexport default () => value;\n');
		write(join(plugin, "node_modules", "own", "package.json"), JSON.stringify({ name: "own" }));
		write(join(workspace, "node_modules", "hoisted", "package.json"), JSON.stringify({ name: "hoisted", dependencies: { deep: "1" } }));
		write(join(workspace, "node_modules", "hoisted", "index.js"), "export default 1;\n");
		write(join(workspace, "node_modules", "deep", "package.json"), JSON.stringify({ name: "deep" }));

		const plan = planPlugins({
			agentDir,
			packages: [{ source: plugin, root: plugin, entries: [join(plugin, "index.ts")] }],
		});
		assert.deepEqual(
			plan.dependencies.map((dependency) => [dependency.name, dependency.from]),
			[
				["hoisted", join(realpathSync(workspace), "node_modules", "hoisted")],
				["deep", join(realpathSync(workspace), "node_modules", "deep")],
			],
		);
		const out = join(scratch(), "folder");
		writePluginFolder(plan, out);
		const staged = join(out, plan.plugins[0].importPath);
		const resolved = spawnSync(
			process.execPath,
			["--input-type=module", "-e", `import("hoisted").then(() => process.exit(0))`],
			{ cwd: dirname(staged), encoding: "utf8" },
		);
		assert.equal(resolved.status, 0, resolved.stderr);
	});

	describe("one node_modules holds one folder per name", () => {
		/** A plugin in `workspace/packages/<name>` that needs `shared`, hoisted to `workspace/node_modules/shared`. */
		const workspacePlugin = (workspace, name, version, leaf) => {
			const plugin = join(workspace, "packages", name);
			write(join(plugin, "package.json"), JSON.stringify({ name, type: "module", dependencies: { shared: "*" } }));
			write(join(plugin, "index.js"), 'import shared from "shared";\nexport const value = shared;\nexport default () => {};\n');
			const shared = join(workspace, "node_modules", "shared");
			write(join(shared, "package.json"), JSON.stringify({ name: "shared", version, type: "module" }));
			if (leaf === undefined) {
				write(join(shared, "index.js"), `export default ${JSON.stringify(version)};\n`);
			} else {
				// shared's own leaf, nested in it: two copies of shared@1.0.0 may carry different ones.
				write(join(shared, "index.js"), 'import leaf from "leaf";\nexport default leaf;\n');
				write(join(shared, "node_modules", "leaf", "package.json"), JSON.stringify({ name: "leaf", version: leaf, type: "module" }));
				write(join(shared, "node_modules", "leaf", "index.js"), `export default ${JSON.stringify(leaf)};\n`);
			}
			return { source: `./${name}`, root: plugin, entries: [join(plugin, "index.js")] };
		};
		const realShared = (workspace) => realpathSync(join(workspace, "node_modules", "shared"));
		/** What each plugin of `plan` exports from its staged copy. */
		const stagedValues = (plan) => {
			const out = join(scratch(), "folder");
			writePluginFolder(plan, out);
			return plan.plugins.map((plugin) => {
				const url = pathToFileURL(join(out, plugin.importPath)).href;
				const loaded = spawnSync(
					process.execPath,
					["--input-type=module", "-e", `import(${JSON.stringify(url)}).then((m) => console.log(m.value))`],
					{ encoding: "utf8" },
				);
				assert.equal(loaded.status, 0, loaded.stderr);
				return loaded.stdout.trim();
			});
		};

		it("shares a name among packages that find it in the same folder, and passes over one that finds another", () => {
			// Two workspaces hoist different versions of `shared`; a third plugin is in the first workspace.
			const first = scratch();
			const second = scratch();
			const one = workspacePlugin(first, "plugin-one", "1.0.0");
			const two = workspacePlugin(second, "plugin-two", "2.0.0");
			const three = workspacePlugin(first, "plugin-three", "1.0.0");

			const plan = planPlugins({ agentDir: agent(), packages: [one, two, three] });
			assert.deepEqual(names(plan), ["plugin-one", "plugin-three"]);
			assert.deepEqual(plan.skipped, [
				{
					path: two.source,
					reason: `its shared@2.0.0 (${realShared(second)}) differs from the one plugin-one is compiled in with (${realShared(first)})`,
				},
			]);
			assert.deepEqual(
				plan.dependencies.map((dependency) => dependency.name),
				["shared"],
			);
			assert.deepEqual(stagedValues(plan), ["1.0.0", "1.0.0"]);
		});

		it("passes over one whose folder of the same version differs, as two copies with other nested leaves do (PR #114 review)", () => {
			const first = scratch();
			const second = scratch();
			const a = workspacePlugin(first, "plugin-a", "1.0.0", "1");
			const b = workspacePlugin(second, "plugin-b", "1.0.0", "2");

			const plan = planPlugins({ agentDir: agent(), packages: [a, b] });
			assert.deepEqual(names(plan), ["plugin-a"]);
			assert.deepEqual(plan.skipped, [
				{
					path: b.source,
					reason: `its shared@1.0.0 (${realShared(second)}) differs from the one plugin-a is compiled in with (${realShared(first)})`,
				},
			]);
			assert.deepEqual(stagedValues(plan), ["1"]);
		});
	});

	it("follows a pnpm link to find what the linked dependency needs beside its target", () => {
		// plugin/node_modules/foo links to .pnpm/foo@1.0.0/node_modules/foo, whose own dependency bar is beside it there.
		const agentDir = agent();
		const plugin = join(scratch(), "plugin");
		const store = join(plugin, "node_modules", ".pnpm", "foo@1.0.0", "node_modules");
		write(join(plugin, "package.json"), JSON.stringify({ name: "plugin", type: "module", dependencies: { foo: "1" } }));
		write(join(plugin, "index.js"), 'import foo from "foo";\nexport const value = foo;\nexport default () => {};\n');
		write(join(store, "foo", "package.json"), JSON.stringify({ name: "foo", type: "module", dependencies: { bar: "1" } }));
		write(join(store, "foo", "index.js"), 'import bar from "bar";\nexport default bar;\n');
		write(join(store, "bar", "package.json"), JSON.stringify({ name: "bar", type: "module" }));
		write(join(store, "bar", "index.js"), "export default 42;\n");
		symlinkSync(join(store, "foo"), join(plugin, "node_modules", "foo"), "dir");

		const plan = planPlugins({
			agentDir,
			packages: [{ source: plugin, root: plugin, entries: [join(plugin, "index.js")] }],
		});
		assert.deepEqual(
			plan.dependencies.map((dependency) => dependency.name),
			["foo", "bar"],
		);
		const out = join(scratch(), "folder");
		writePluginFolder(plan, out);
		const staged = join(out, plan.plugins[0].importPath);
		const loaded = spawnSync(
			process.execPath,
			["--input-type=module", "-e", `import(${JSON.stringify(pathToFileURL(staged).href)}).then((m) => console.log(m.value))`],
			{ encoding: "utf8" },
		);
		assert.equal(loaded.stdout.trim(), "42", loaded.stderr);
	});

	it("still passes over a package that needs vendoring, by its source", () => {
		const agentDir = agent();
		const dir = installPackage(agentDir, "opensec-pi-todo", {}, ["dist-bun/index.js", "dist-bun/core.cjs"]);

		const plan = planPlugins({
			agentDir,
			packages: [{ source: "npm:opensec-pi-todo@2.13.1", root: dir, entries: [join(dir, "dist-bun", "index.js")] }],
		});
		assert.deepEqual(plan.plugins, []);
		assert.match(plan.skipped.find((entry) => entry.path === "npm:opensec-pi-todo@2.13.1").reason, /vendoring/);
	});

	it("reads them from a file given to the command", () => {
		const agentDir = agent();
		const bundle = join(agentDir, "pi-bolt-bundles", "b");
		write(join(bundle, "index.ts"));
		const list = join(scratch(), "packages.json");
		writeFileSync(list, JSON.stringify([{ source: "pi-bolt-bundles/b", root: bundle, entries: [join(bundle, "index.ts")] }]));
		const out = join(scratch(), "out");
		const script = fileURLToPath(new URL("./gen-plugin-manifest.mjs", import.meta.url));
		const run = (file) =>
			spawnSync(process.execPath, [script, "--out", out, "--agent-dir", agentDir, "--packages", file, "--json"], {
				encoding: "utf8",
			});

		const result = run(list);
		assert.equal(result.status, 0, result.stderr);
		assert.deepEqual(
			JSON.parse(result.stdout).plugins.map((plugin) => plugin.name),
			["b"],
		);
		writeFileSync(list, JSON.stringify([{ source: "x" }]));
		assert.match(run(list).stderr, /not a list of \{ source, root, entries \}/);
	});
});

describe("the dependencies of a local package, as the staged copy finds them", () => {
	/** A module's JSON file: its package.json, with `type: module`. */
	const pkg = (dir, manifest) => write(join(dir, "package.json"), JSON.stringify({ type: "module", ...manifest }));
	const link = (target, path) => {
		mkdirSync(dirname(path), { recursive: true });
		symlinkSync(target, path, "dir");
	};
	/** Plans `plugin` (a folder with index.js), writes the folder, and returns what the staged copy's `value` is. */
	function staged(plugin) {
		const plan = planPlugins({
			agentDir: agent(),
			packages: [{ source: plugin, root: plugin, entries: [join(plugin, "index.js")] }],
		});
		const out = join(scratch(), "folder");
		writePluginFolder(plan, out);
		const url = pathToFileURL(join(out, plan.plugins[0].importPath)).href;
		const loaded = spawnSync(
			process.execPath,
			["--input-type=module", "-e", `import(${JSON.stringify(url)}).then((m) => console.log(JSON.stringify(m.value)))`],
			{ encoding: "utf8" },
		);
		assert.equal(loaded.status, 0, loaded.stderr);
		return { plan, value: JSON.parse(loaded.stdout) };
	}
	/** A plugin in `workspace/packages/plugin` that needs `dependencies` and exports `value` from `code`. */
	function workspacePlugin(dependencies, code) {
		const workspace = join(scratch(), "workspace");
		const plugin = join(workspace, "packages", "plugin");
		pkg(plugin, { name: "plugin", dependencies: Object.fromEntries(dependencies.map((name) => [name, "*"])) });
		write(join(plugin, "index.js"), `${code}\nexport default () => {};\n`);
		return { workspace, plugin };
	}

	it("copies each name two links give one folder (PR #114 review)", () => {
		const { workspace, plugin } = workspacePlugin(
			["alias-a", "alias-b"],
			'import a from "alias-a";\nimport b from "alias-b";\nexport const value = [a, b];',
		);
		const shared = join(workspace, "store", "shared");
		pkg(shared, { name: "shared", version: "1.0.0" });
		write(join(shared, "index.js"), "export default 42;\n");
		link(shared, join(workspace, "node_modules", "alias-a"));
		link(shared, join(workspace, "node_modules", "alias-b"));

		const { plan, value } = staged(plugin);
		assert.deepEqual(value, [42, 42]);
		assert.deepEqual(
			plan.dependencies.map((dependency) => dependency.name),
			["alias-a", "alias-b"],
		);
	});

	it("looks again at a folder copied with the package when a dependency outside it needs it too", () => {
		// plugin/node_modules/m and n come with the plugin's folder; `outer`, hoisted above it, reaches m by a link into it,
		// and m's copy beside outer then needs n from elsewhere.
		const { workspace, plugin } = workspacePlugin(
			["m", "outer"],
			'import m from "m";\nimport outer from "outer";\nexport const value = [m, outer];',
		);
		const m = join(plugin, "node_modules", "m");
		pkg(m, { name: "m", version: "1.0.0", dependencies: { n: "*" } });
		write(join(m, "index.js"), 'import n from "n";\nexport default `m+${n}`;\n');
		pkg(join(plugin, "node_modules", "n"), { name: "n" });
		write(join(plugin, "node_modules", "n", "index.js"), 'export default "n";\n');
		const outer = join(workspace, "node_modules", "outer");
		pkg(outer, { name: "outer", dependencies: { m: "*" } });
		write(join(outer, "index.js"), 'import m from "m";\nexport default `outer+${m}`;\n');
		link(m, join(outer, "node_modules", "m"));

		assert.deepEqual(staged(plugin).value, ["m+n", "outer+m+n"]);
	});

	it("finds a scoped dependency and its own scoped dependency", () => {
		const { workspace, plugin } = workspacePlugin(["@scope/pkg"], 'import pkg from "@scope/pkg";\nexport const value = pkg;');
		const scoped = join(workspace, "node_modules", "@scope", "pkg");
		pkg(scoped, { name: "@scope/pkg", dependencies: { "@scope/dep": "*" } });
		write(join(scoped, "index.js"), 'import dep from "@scope/dep";\nexport default `pkg+${dep}`;\n');
		pkg(join(workspace, "node_modules", "@scope", "dep"), { name: "@scope/dep" });
		write(join(workspace, "node_modules", "@scope", "dep", "index.js"), 'export default "dep";\n');

		const { plan, value } = staged(plugin);
		assert.equal(value, "pkg+dep");
		assert.deepEqual(
			plan.dependencies.map((dependency) => dependency.name),
			["@scope/pkg", "@scope/dep"],
		);
	});

	it("follows a workspace link to another local folder, and finds what that folder needs from where it is", () => {
		// npm workspaces link workspace/node_modules/lib to packages/lib; lib needs `leaf`, hoisted beside the link.
		const { workspace, plugin } = workspacePlugin(["lib"], 'import lib from "lib";\nexport const value = lib;');
		const lib = join(workspace, "packages", "lib");
		pkg(lib, { name: "lib", dependencies: { leaf: "*" } });
		write(join(lib, "index.js"), 'import leaf from "leaf";\nexport default `lib+${leaf}`;\n');
		pkg(join(workspace, "node_modules", "leaf"), { name: "leaf" });
		write(join(workspace, "node_modules", "leaf", "index.js"), 'export default "leaf";\n');
		link(lib, join(workspace, "node_modules", "lib"));

		assert.equal(staged(plugin).value, "lib+leaf");
	});

	it("copies packages that link to each other, or to themselves, without copying them into themselves", () => {
		// A pnpm workspace links packages/plugin/node_modules/b to ../../b, and b's node_modules/plugin back to the plugin.
		const { workspace, plugin } = workspacePlugin(["b", "plugin"], 'import b from "b";\nexport const value = b;');
		const b = join(workspace, "packages", "b");
		pkg(b, { name: "b", dependencies: { plugin: "*" } });
		write(join(b, "index.js"), 'export default "b";\n');
		link(b, join(plugin, "node_modules", "b"));
		link(plugin, join(b, "node_modules", "plugin"));
		link(plugin, join(plugin, "node_modules", "plugin"));

		assert.equal(staged(plugin).value, "b");
	});

	it("copies a dependency whose name only begins like one of Pi's own packages", () => {
		const { workspace, plugin } = workspacePlugin(["typebox-extra"], 'import extra from "typebox-extra";\nexport const value = extra;');
		pkg(join(workspace, "node_modules", "typebox-extra"), { name: "typebox-extra" });
		write(join(workspace, "node_modules", "typebox-extra", "index.js"), 'export default "extra";\n');

		assert.equal(staged(plugin).value, "extra");
	});
});

describe("what it leaves out", () => {
	it("lists each extension --only or --exclude leaves out, and by which (#108)", () => {
		const agentDir = agent();
		for (const name of ["a", "b", "c"]) write(join(agentDir, "extensions", `${name}.ts`));

		const excluded = (options) => planPlugins({ agentDir, ...options }).excluded.map((entry) => [entry.name, entry.by]);
		assert.deepEqual(excluded({ exclude: ["b"] }), [["b", "exclude"]]);
		assert.deepEqual(excluded({ only: ["a", "b"], exclude: ["b"] }), [["b", "exclude"], ["c", "only"]]);
		assert.deepEqual(excluded({}), []);
	});
});

describe("what it passes over", () => {
	it("reports an empty folder, shared code, and a file that is not .ts or .js", () => {
		const agentDir = agent();
		write(join(agentDir, "extensions", "real", "index.ts"));
		mkdirSync(join(agentDir, "extensions", "ao-bridge"), { recursive: true });
		write(join(agentDir, "extensions", "shared", "skills.ts"));
		write(join(agentDir, "extensions", "notes.md"), "# notes");

		const plan = planPlugins({ agentDir });
		assert.deepEqual(names(plan), ["real"]);
		const reasons = Object.fromEntries(plan.skipped.map((entry) => [entry.path, entry.reason]));
		assert.equal(reasons[join(agentDir, "extensions", "ao-bridge")], "empty folder");
		assert.match(reasons[join(agentDir, "extensions", "shared")], /shared code/);
		assert.equal(reasons[join(agentDir, "extensions", "notes.md")], "not a .ts or .js file");
	});

	it("takes no extension from a filter in the extensions setting, and reports a path that is not there", () => {
		const agentDir = agent({
			extensions: ["-builtin:opensec-pi-todo", "!~/old/*.ts", "+pinned.ts", "builtin:mcp", join(tmpdir(), "pibolt-no-such.ts")],
		});
		write(join(agentDir, "extensions", "real.ts"));

		const plan = planPlugins({ agentDir });
		assert.deepEqual(names(plan), ["real"]);
		assert.deepEqual(plan.skipped, [
			{ path: join(tmpdir(), "pibolt-no-such.ts"), reason: "in the extensions setting, but not found" },
		]);
	});
});

describe("the manifest and its folder", () => {
	it("imports the type only, and names each plugin a replaceable built-in", () => {
		const agentDir = agent();
		write(join(agentDir, "extensions", "pdf-reader", "index.ts"));

		const text = renderManifest(planPlugins({ agentDir }));
		assert.match(text, /^import type \{ InlineExtension \}/m);
		assert.match(text, /^import pdfReader from "\.\/global\/pdf-reader\/index\.ts";$/m);
		assert.match(text, /\{ name: "pdf-reader", builtin: true, replaceable: true, factory: pdfReader \},/);
		// A value import would evaluate Pi's whole public API at every launch.
		assert.doesNotMatch(text, /^import \{/m);
	});

	it("writes a manifest whose every import resolves to a file it copied", () => {
		const agentDir = agent();
		write(join(agentDir, "extensions", "ban-sleep.ts"));
		write(join(agentDir, "extensions", "pdf-reader", "index.ts"));

		const out = join(scratch(), "folder");
		const manifest = writePluginFolder(planPlugins({ agentDir }), out);
		const imports = [...readFileSync(manifest, "utf8").matchAll(/^import \w+ from "\.\/(.+)";$/gm)];
		assert.equal(imports.length, 2);
		for (const [, path] of imports) assert.ok(readFileSync(join(out, path), "utf8"));
	});

	it("brings along a sibling that is not an extension itself", () => {
		const agentDir = agent();
		write(join(agentDir, "extensions", "shared", "skills.ts"), "export const skills = [];\n");
		write(join(agentDir, "extensions", "uses-shared", "index.ts"), 'import "../shared/skills.ts";\n');

		const out = join(scratch(), "folder");
		writePluginFolder(planPlugins({ agentDir }), out);
		assert.ok(readFileSync(join(out, "global", "shared", "skills.ts"), "utf8"));
	});

	it("replaces its own output, and refuses a folder holding anything else", () => {
		const agentDir = agent();
		write(join(agentDir, "extensions", "alpha.ts"));
		const plan = planPlugins({ agentDir });

		const out = join(scratch(), "folder");
		writePluginFolder(plan, out);
		writeFileSync(join(out, "stale.txt"), "from an earlier run");
		writePluginFolder(plan, out);
		assert.throws(() => readFileSync(join(out, "stale.txt"), "utf8"));
	});
});

// Issue #53: bundling resolves import.meta.url to the executable, so an extension lost the skills and prompts beside it.
describe("import.meta in the copies", () => {
	const FILE = "/installed/ext/src/index.ts";
	const URL_OF = JSON.stringify(pathToFileURL(FILE).href);

	it("replaces each property the bundler resolves to the output file, with its value for the installed file", () => {
		const source = [
			"const a = import.meta.url;",
			"const b = [import.meta.dir, import.meta.dirname, import.meta.filename, import.meta.path, import.meta.file];",
		].join("\n");
		assert.equal(
			pinImportMeta(source, FILE),
			[
				`const a = ${URL_OF};`,
				'const b = ["/installed/ext/src", "/installed/ext/src", "/installed/ext/src/index.ts", "/installed/ext/src/index.ts", "index.ts"];',
			].join("\n"),
		);
	});

	it("leaves comments, strings, template text and regular expressions, and other properties", () => {
		const source = [
			"#!/usr/bin/env node",
			"// import.meta.url in a comment",
			"/* import.meta.dir */",
			"const s = \"import.meta.url\" + 'import.meta.url';",
			"const r = /import.meta.url/g;",
			"const t = `import.meta.url`;",
			"const keep = [import.meta.main, import.meta.resolve, import.meta.urls, import.meta.url$, x.import];",
		].join("\n");
		assert.equal(pinImportMeta(source, FILE), source);
	});

	it("replaces inside a template literal's expressions, and tells a division from a regular expression", () => {
		assert.equal(
			pinImportMeta("`at ${import.meta.url} in ${ { f: import.meta.file }.f }`", FILE),
			`\`at \${${URL_OF}} in \${ { f: "index.ts" }.f }\``,
		);
		assert.equal(
			pinImportMeta("const n = total / 2 / import.meta.url.length;", FILE),
			`const n = total / 2 / ${URL_OF}.length;`,
		);
		assert.equal(
			pinImportMeta("if (x) return /'/.test(y) ? import.meta.url : 'it\\'s';", FILE),
			`if (x) return /'/.test(y) ? ${URL_OF} : 'it\\'s';`,
		);
	});

	it("takes a / after a condition's parentheses or a block for a regular expression, and after an object for a division", () => {
		// Each regular expression holds a quote: taken for a division, the quote would begin a string to the end of the line.
		for (const source of [
			`if (ok) /"/.test(s); const u = import.meta.url;`,
			`while (m) /'/g.exec(s); const u = import.meta.url;`,
			`for (;;) /"/.test(s) && import.meta.url;`,
			`function f() {} /'/.test(s) && import.meta.url;`,
		]) {
			assert.equal(pinImportMeta(source, FILE), source.replace("import.meta.url", URL_OF), source);
		}
		for (const source of [
			`const n = f(x) / y; const s = '/'; const u = import.meta.url;`,
			`const n = { a: 1 } / y; const s = '/'; const u = import.meta.url;`,
			`const n = (() => {}) / y; const s = '/'; const u = import.meta.url;`,
		]) {
			assert.equal(pinImportMeta(source, FILE), source.replace("import.meta.url", URL_OF), source);
		}
	});

	it("in a .jsx or .tsx file, takes JSX text and attribute strings for text, and its {} for code", () => {
		const TSX = "/installed/ext/src/view.tsx";
		const url = JSON.stringify(pathToFileURL(TSX).href);
		const cases = [
			["const x = <div><span>it's</span>{import.meta.url}</div>;", `const x = <div><span>it's</span>{${url}}</div>;`],
			[
				'const x = <img src={import.meta.url} alt="import.meta.url" />;',
				`const x = <img src={${url}} alt="import.meta.url" />;`,
			],
			[
				"const x = <>{items.map((i) => <li key={i}>{import.meta.file}</li>)}</>;",
				'const x = <>{items.map((i) => <li key={i}>{"view.tsx"}</li>)}</>;',
			],
			["const x = <p>import.meta.url</p>; const u = import.meta.url;", `const x = <p>import.meta.url</p>; const u = ${url};`],
			["const id = <T,>(x: T) => x; const u = import.meta.url;", `const id = <T,>(x: T) => x; const u = ${url};`],
			["const less = a < b && c > d; const u = import.meta.url;", `const less = a < b && c > d; const u = ${url};`],
		];
		for (const [source, expected] of cases) assert.equal(pinImportMeta(source, TSX), expected, source);
		// (A .ts file has no JSX: there `<number>value` is a type assertion.)
		assert.equal(
			pinImportMeta("const n = <number>value; const u = import.meta.url;", FILE),
			`const n = <number>value; const u = ${URL_OF};`,
		);
	});

	it("keeps line numbers, and writes any path as a valid string literal", () => {
		assert.equal(pinImportMeta("f(import\n\t.meta\n\t.url);\ng();", FILE), `f(${URL_OF}\n\n);\ng();`);
		const odd = '/home/o"brien/a\\b/ext.ts';
		assert.equal(pinImportMeta("x = import.meta.filename;", odd), `x = ${JSON.stringify(odd)};`);
	});

	it("pins the copies to the installed files, and leaves the installed files as they were", async () => {
		const agentDir = agent({ packages: ["npm:uses-dep"] });
		const extension = join(agentDir, "extensions", "with-skills");
		const RESOLVES = [
			'import { dirname, join } from "node:path";',
			'import { fileURLToPath } from "node:url";',
			'export const skills = () => join(dirname(fileURLToPath(import.meta.url)), "..", "skills");',
			"export default () => {};",
			"",
		].join("\n");
		write(join(extension, "package.json"), JSON.stringify({ type: "module", pi: { extensions: ["./src/index.js"] } }));
		write(join(extension, "src", "index.js"), RESOLVES);
		write(join(extension, "skills", "probe", "SKILL.md"), "---\nname: probe\n---\n");
		installPackage(agentDir, "uses-dep", { pi: { extensions: ["./index.js"] }, dependencies: { dep: "1" } }, []);
		write(join(agentDir, "npm", "node_modules", "uses-dep", "index.js"), "export default () => {};\n");
		const dep = installPackage(agentDir, "dep", { type: "module" }, []);
		write(join(dep, "index.js"), "export const here = import.meta.dirname;\n");
		// (An installed file may be read-only, and its copy keeps the mode.)
		chmodSync(join(dep, "index.js"), 0o444);

		const out = join(scratch(), "folder");
		writePluginFolder(planPlugins({ agentDir }), out);

		const copy = await import(pathToFileURL(join(out, "global", "with-skills", "src", "index.js")).href);
		assert.equal(copy.skills(), join(extension, "skills"));
		assert.equal(readFileSync(join(extension, "src", "index.js"), "utf8"), RESOLVES);
		// (A dependency is pinned to its real path: where it is when the installed extension imports it, as Node resolves links.)
		assert.equal(
			readFileSync(join(out, "node_modules", "dep", "index.js"), "utf8"),
			`export const here = ${JSON.stringify(realpathSync(dep))};\n`,
		);
		assert.equal(readFileSync(join(dep, "index.js"), "utf8"), "export const here = import.meta.dirname;\n");
	});
});

describe("the command", () => {
	it("runs when started through a link to it, as /var is to /private/var on macOS", () => {
		const link = join(scratch(), "gen-plugin-manifest.mjs");
		symlinkSync(fileURLToPath(new URL("./gen-plugin-manifest.mjs", import.meta.url)), link);
		const result = spawnSync(process.execPath, [link, "--help"], { encoding: "utf8" });
		assert.equal(result.status, 0, result.stderr);
		assert.match(result.stdout, /^Usage: scripts\/gen-plugin-manifest\.mjs/);
	});
});

describe("the agent directory", () => {
	it("follows PI_CODING_AGENT_DIR, then ~/.pi/agent", () => {
		assert.equal(defaultAgentDir({ PI_CODING_AGENT_DIR: "/somewhere/agent" }), "/somewhere/agent");
		assert.match(defaultAgentDir({}), /[\\/]\.pi[\\/]agent$/);
	});
});
