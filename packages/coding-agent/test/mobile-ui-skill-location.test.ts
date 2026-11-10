import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const agent = join(import.meta.dirname, "..");
const root = join(agent, "..", "..");

describe("the pibolt-mobile-ui skill", () => {
	it("is in the repository's skills/ folder, for package authors", () => {
		const skill = readFileSync(join(root, "skills", "pibolt-mobile-ui", "SKILL.md"), "utf8");
		expect(skill).toMatch(/^---\nname: pibolt-mobile-ui\n/);
		expect(readFileSync(join(root, "skills", "pibolt-mobile-ui", "README.md"), "utf8")).toContain(
			"~/.pi/agent/skills",
		);
	});

	it("is not named in anything a build ships: the package's docs, examples, README and changelog, Pi-Bolt's shipped docs", () => {
		const textFiles = (folder: string) =>
			readdirSync(join(agent, folder), { recursive: true, encoding: "utf8" })
				.filter((file) => /\.(md|json|ts|js|mjs|txt|yaml|yml)$/.test(file))
				.map((file) => join(agent, folder, file));
		const shipped = [
			...textFiles("docs"),
			...textFiles("examples"),
			join(agent, "README.md"),
			join(agent, "CHANGELOG.md"),
			...["PLUGINS.md", "ACP.md", "TROUBLESHOOTING.md"].map((file) => join(root, "docs", file)),
		];
		expect(shipped.some((file) => file.includes(`${join(agent, "examples")}`))).toBe(true);
		for (const file of shipped) {
			expect(readFileSync(file, "utf8"), file).not.toMatch(/pibolt[-_ ]?mobile[-_ ]?ui|mobile[- ]ui skill/i);
		}
	});

	it("is in no package, build or archive", () => {
		expect(existsSync(join(agent, "skills"))).toBe(false);
		const pkg = JSON.parse(readFileSync(join(agent, "package.json"), "utf8")) as {
			files: string[];
			scripts: Record<string, string>;
		};
		expect(pkg.files).not.toContain("skills");
		expect(pkg.scripts["copy-binary-assets"]).not.toMatch(/\bskills\b/);
		for (const script of ["scripts/build-pi.sh", "scripts/build-pi.ps1", "scripts/build-binaries.sh"]) {
			expect(readFileSync(join(root, script), "utf8"), script).not.toMatch(/\bskills\b/);
		}
	});
});
