import realChalk from "chalk";
import realHostedGitInfo from "hosted-git-info";
import * as realSemver from "semver";
import { describe, expect, it } from "vitest";
import { chalk, hostedGitInfo, loadChalk, semver } from "../src/utils/lazy-packages.ts";

describe("packages loaded on first use", () => {
	it("gives the packages themselves", () => {
		expect(semver().gt).toBe(realSemver.gt);
		expect(semver().compare("1.2.3", "1.10.0")).toBe(-1);
		expect(hostedGitInfo()).toBe(realHostedGitInfo);
		expect(hostedGitInfo().fromUrl("github:user/repo#v1")?.committish).toBe("v1");
		expect(loadChalk()).toBe(realChalk);
	});

	it('styles through chalk\'s default instance, as `import chalk from "chalk"` did', () => {
		const level = realChalk.level;
		try {
			for (const forced of [0, 1, 3] as const) {
				realChalk.level = forced;
				expect(chalk.level).toBe(forced);
				expect(chalk.red("a\nb")).toBe(realChalk.red("a\nb"));
				expect(chalk.bold.yellow("x")).toBe(realChalk.bold.yellow("x"));
				const color = chalk.dim;
				expect(color("y")).toBe(realChalk.dim("y"));
			}
			chalk.level = 2;
			expect(realChalk.level).toBe(2);
		} finally {
			realChalk.level = level;
		}
	});
});
