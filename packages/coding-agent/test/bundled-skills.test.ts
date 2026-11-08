import { mkdtempSync, readFileSync, rmSync } from "fs";
import { tmpdir } from "os";
import { join } from "path";
import { describe, expect, it } from "vitest";
import { getBundledSkillsDir } from "../src/config.ts";
import { BUNDLED_SKILLS } from "../src/core/bundled-skills.ts";
import { formatSkillsForPrompt, loadSkills, loadSkillsFromDir } from "../src/core/skills.ts";
import { parseFrontmatter } from "../src/utils/frontmatter.ts";

describe("bundled skills", () => {
	it("know exactly the frontmatter of their SKILL.md", () => {
		for (const skill of BUNDLED_SKILLS) {
			const content = readFileSync(join(getBundledSkillsDir(), skill.dir, "SKILL.md"), "utf-8");
			const { frontmatter } = parseFrontmatter(content);
			// (Any other field, such as disable-model-invocation, would change the skill: it has to be in bundled-skills.ts.)
			expect(frontmatter).toEqual({ name: skill.name, description: skill.description });
		}
	});

	it("load as the same skills as from their files", () => {
		const agentDir = mkdtempSync(join(tmpdir(), "pi-bundled-skills-"));
		try {
			for (const skill of BUNDLED_SKILLS) {
				const dir = join(getBundledSkillsDir(), skill.dir);
				const known = loadSkills({ cwd: agentDir, agentDir, skillPaths: [dir], includeDefaults: false });
				const read = loadSkillsFromDir({ dir, source: "path" });
				expect(read.skills).toHaveLength(1);
				expect(known).toEqual(read);
				expect(formatSkillsForPrompt(known.skills)).toBe(formatSkillsForPrompt(read.skills));
			}
		} finally {
			rmSync(agentDir, { recursive: true, force: true });
		}
	});
});
