/**
 * The skills Pi-Bolt ships in skills/ (main.ts registers them at every start), with the frontmatter of their SKILL.md known
 * ahead of time. Registering one then reads no file and runs no YAML parser: the parser's first use cost about 3.5 M
 * instructions and 1 MB at every start. The model reads the SKILL.md itself only when it uses the skill.
 * test/bundled-skills.test.ts keeps each entry equal to its file's frontmatter.
 */
export interface BundledSkill {
	/** The skill's folder in skills/. */
	dir: string;
	name: string;
	description: string;
}

export const BUNDLED_SKILLS: readonly BundledSkill[] = [
	{
		dir: "pibolt-mobile-ui",
		name: "pibolt-mobile-ui",
		description:
			"Give a Pi package or extension a native-looking UI in the Pi-Bolt mobile app (cards, lists, progress, buttons, forms) with the mobile UI kit, keeping a text fallback for the terminal. Use when asked to add a mobile or app UI, a panel, a status card or a settings sheet to an extension.",
	},
];
