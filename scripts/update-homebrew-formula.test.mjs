// Tests of scripts/update-homebrew-formula.mjs, with the formula as opensec-git/homebrew-tap has it.
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { updateFormula } from "./update-homebrew-formula.mjs";

const script = fileURLToPath(new URL("./update-homebrew-formula.mjs", import.meta.url));
const base = "https://github.com/opensec-git/Pi-Bolt/releases/download";
const old = (n) => String(n).repeat(64);
const sum = (c) => c.repeat(64);

const formula = `class PiBolt < Formula
  desc "Pi coding agent, compiled ahead of time to native code"
  homepage "https://pi-bolt.opensec.in"
  url "${base}/bolt-v1.0.0/pi-bolt-darwin-arm64.tar.gz"
  version "1.0.0"
  sha256 "${old(1)}"
  license "MIT"

  on_macos do
    depends_on arch: :arm64
  end

  on_linux do
    on_intel do
      url "${base}/bolt-v1.0.0/pi-bolt-linux-x64.tar.gz"
      sha256 "${old(2)}"
    end
    on_arm do
      url "${base}/bolt-v1.0.0/pi-bolt-linux-arm64.tar.gz"
      sha256 "${old(3)}"
    end
  end

  test do
    assert_match "Pi-Bolt #{version},", shell_output("#{bin}/pi-bolt --version")
  end
end
`;

const sums = `# pi-bolt 1.0.1
${sum("a")}  pi-bolt-darwin-arm64.tar.gz
${sum("b")}  pi-bolt-linux-x64.tar.gz
${sum("c")}  pi-bolt-linux-arm64.tar.gz
${sum("d")}  pi-bolt-linux-x64-baseline.tar.gz
${sum("e")}  pi-bolt-darwin-arm64.tar.xz
`;

test("points every url at the release and takes each archive's checksum", () => {
	const updated = updateFormula(formula, sums, "1.0.1");
	assert.equal(
		updated,
		formula
			.replaceAll("bolt-v1.0.0/", "bolt-v1.0.1/")
			.replace('version "1.0.0"', 'version "1.0.1"')
			.replace(old(1), sum("a"))
			.replace(old(2), sum("b"))
			.replace(old(3), sum("c")),
	);
	// The same release again changes nothing.
	assert.equal(updateFormula(updated, sums, "1.0.1"), updated);
});

test("reads checksum lines in binary mode too", () => {
	const binary = sums.replace(`${sum("b")}  pi-bolt-linux-x64`, `${sum("b")} *pi-bolt-linux-x64`);
	assert.match(updateFormula(formula, binary, "1.0.1"), new RegExp(sum("b")));
});

test("refuses another release's checksums, an archive they do not list, and a version that is not one", () => {
	assert.throws(() => updateFormula(formula, sums, "1.0.2"), /SHA256SUMS is release 1\.0\.1's, not 1\.0\.2's/);
	assert.throws(
		() => updateFormula(formula, sums.replace(/^.*pi-bolt-linux-arm64\.tar\.gz\n/m, ""), "1.0.1"),
		/does not list pi-bolt-linux-arm64\.tar\.gz/,
	);
	assert.throws(() => updateFormula(formula, sums.replace("# pi-bolt 1.0.1\n", ""), "1.0.1"), /release \(none\)'s/);
	assert.throws(() => updateFormula(formula, sums, "bolt-v1.0.1"), /not a release version/);
});

test("refuses a formula it cannot update exactly", () => {
	const noSha = formula.replace(`      sha256 "${old(3)}"\n`, "");
	assert.throws(() => updateFormula(noSha, sums, "1.0.1"), /no sha256 after the url of pi-bolt-linux-arm64\.tar\.gz/);
	assert.throws(() => updateFormula(formula.replace('  version "1.0.0"\n', ""), sums, "1.0.1"), /0 version lines/);
	assert.throws(() => updateFormula('class PiBolt < Formula\n  version "1.0.0"\nend\n', sums, "1.0.1"), /no release url/);
});

test("the command rewrites the file, and leaves it as it was on an error", () => {
	const dir = mkdtempSync(join(tmpdir(), "pibolt-formula-"));
	try {
		const file = join(dir, "pi-bolt.rb");
		const sumsFile = join(dir, "SHA256SUMS");
		writeFileSync(file, formula);
		writeFileSync(sumsFile, sums);
		const failed = spawnSync(process.execPath, [script, file, sumsFile, "1.0.2"], { encoding: "utf8" });
		assert.equal(failed.status, 1);
		assert.match(failed.stderr, /not 1\.0\.2's/);
		assert.equal(readFileSync(file, "utf8"), formula);
		const done = spawnSync(process.execPath, [script, file, sumsFile, "1.0.1"], { encoding: "utf8" });
		assert.equal(done.status, 0, done.stderr);
		assert.match(done.stdout, /pi-bolt 1\.0\.1/);
		assert.equal(readFileSync(file, "utf8"), updateFormula(formula, sums, "1.0.1"));
		assert.equal(spawnSync(process.execPath, [script, file], { encoding: "utf8" }).status, 2);
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
});
