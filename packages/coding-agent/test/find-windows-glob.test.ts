import { describe, expect, it } from "vitest";
import { globToWindowsPathRegex, windowsGlobSeparators } from "../src/core/tools/find.ts";

// What fd is given on Windows for a pattern with a "/" (find.ts adds the leading "**/").
function matches(glob: string, path: string): boolean {
	return new RegExp(globToWindowsPathRegex(glob)).test(path);
}

describe("globToWindowsPathRegex", () => {
	const root = "C:\\work\\project";
	it("lets ** span any number of folders, none included", () => {
		expect(matches("**/src/**/*.spec.ts", `${root}\\src\\y.spec.ts`)).toBe(true);
		expect(matches("**/src/**/*.spec.ts", `${root}\\src\\a\\b\\x.spec.ts`)).toBe(true);
		expect(matches("**/src/**/*.spec.ts", `${root}\\other\\z.spec.ts`)).toBe(false);
	});
	it("keeps * and ? within one name", () => {
		expect(matches("**/src/*.spec.ts", `${root}\\src\\y.spec.ts`)).toBe(true);
		expect(matches("**/src/*.spec.ts", `${root}\\src\\a\\b\\x.spec.ts`)).toBe(false);
		expect(matches("**/src/?.ts", `${root}\\src\\a.ts`)).toBe(true);
		expect(matches("**/src/?.ts", `${root}\\src\\ab.ts`)).toBe(false);
	});
	it("takes either separator, and the glob's classes and alternatives", () => {
		expect(matches("**/src/*.spec.ts", "C:/work/project/src/y.spec.ts")).toBe(true);
		expect(matches("**/src/*.{ts,tsx}", `${root}\\src\\view.tsx`)).toBe(true);
		expect(matches("**/src/*.{ts,tsx}", `${root}\\src\\view.js`)).toBe(false);
		expect(matches("**/src/[a-c]*.ts", `${root}\\src\\b.ts`)).toBe(true);
		expect(matches("**/src/[!a-c]*.ts", `${root}\\src\\b.ts`)).toBe(false);
	});
	it("escapes what is special in a regular expression", () => {
		expect(matches("**/a+b/(x).ts", `${root}\\a+b\\(x).ts`)).toBe(true);
		expect(matches("**/a.b/*.ts", `${root}\\aXb\\c.ts`)).toBe(false);
	});
	it("matches everything below for a trailing **", () => {
		expect(matches("**/src/**", `${root}\\src\\a\\b\\x.spec.ts`)).toBe(true);
		expect(matches("**/src/**", `${root}\\other\\x.ts`)).toBe(false);
	});
	it("takes glob escapes and a leading ] in a class for brackets in names (Next.js route folders)", () => {
		expect(matches("**/src/[[]id]/*.ts", `${root}\\src\\[id]\\page.ts`)).toBe(true);
		expect(matches("**/src/\\[id\\]/*.ts", `${root}\\src\\[id]\\page.ts`)).toBe(true);
		expect(matches("**/src/[[]...slug]/*.ts", `${root}\\src\\[...slug]\\page.ts`)).toBe(true);
		expect(matches("**/src/\\[id\\]/*.ts", `${root}\\src\\i\\page.ts`)).toBe(false);
		expect(matches("**/x[]]y.ts", `${root}\\x]y.ts`)).toBe(true);
	});
	it("keeps a negated class within one name", () => {
		expect(matches("**/a[!x]b.ts", `${root}\\a\\b.ts`)).toBe(false);
		expect(matches("**/a[!x]b.ts", `${root}\\azb.ts`)).toBe(true);
	});
});

describe("windowsGlobSeparators", () => {
	it("takes \\ for the separator, except before what a name may have and a glob escapes", () => {
		expect(windowsGlobSeparators("src\\**\\*.ts")).toBe("src/**/*.ts");
		expect(windowsGlobSeparators("src\\\\a\\b.ts")).toBe("src/a/b.ts");
		expect(windowsGlobSeparators("src/\\[id\\]/*.ts")).toBe("src/\\[id\\]/*.ts");
		expect(windowsGlobSeparators("src/*.ts")).toBe("src/*.ts");
	});
});
