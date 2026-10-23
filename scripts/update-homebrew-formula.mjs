// Points the Homebrew formula (Formula/pi-bolt.rb in opensec-git/homebrew-tap) at a release: its version, each archive's URL
// and the archive's SHA-256 from the release's SHA256SUMS. The publish workflow runs it once the release is published.
//
//   node scripts/update-homebrew-formula.mjs <Formula/pi-bolt.rb> <SHA256SUMS> <version>
//
// Each `url` names a release archive (.../releases/download/bolt-vX.Y.Z/<archive>); the `sha256` after it takes that
// archive's checksum. An archive the SHA256SUMS does not list, or a SHA256SUMS of another release, is an error and the formula
// is left as it was.
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const RELEASE_URL = /^(\s*url\s+")(https:\/\/github\.com\/opensec-git\/Pi-Bolt\/releases\/download\/)bolt-v[^/"]+\/([^/"]+)(".*)$/;
const SHA256 = /^(\s*sha256\s+")[0-9a-f]*(".*)$/;
const VERSION = /^(\s*version\s+")[^"]*(".*)$/;

/** The checksums of SHA256SUMS by archive name, and the release it says it is (its first line, `# pi-bolt X.Y.Z`). */
export function parseSums(text) {
	const sums = new Map();
	let release;
	for (const line of text.split(/\r?\n/)) {
		const header = /^# pi-bolt (\S+)$/.exec(line);
		if (header) release = header[1];
		const entry = /^([0-9a-f]{64}) [ *](\S+)$/.exec(line);
		if (entry) sums.set(entry[2], entry[1]);
	}
	return { release, sums };
}

/** The formula `text` for release `version`, with the checksums of `sumsText`. Throws where it cannot be made exactly. */
export function updateFormula(text, sumsText, version) {
	if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error(`not a release version: ${version}`);
	const { release, sums } = parseSums(sumsText);
	if (release !== version) throw new Error(`the SHA256SUMS is release ${release ?? "(none)"}'s, not ${version}'s`);
	const lines = text.split("\n");
	let versions = 0;
	let urls = 0;
	let pending; // The archive of the last url, whose sha256 comes next.
	for (let index = 0; index < lines.length; index++) {
		const line = lines[index];
		const url = RELEASE_URL.exec(line);
		if (url) {
			if (pending) throw new Error(`no sha256 after the url of ${pending}`);
			const [, before, base, archive, after] = url;
			if (!sums.has(archive)) throw new Error(`the release's SHA256SUMS does not list ${archive}`);
			lines[index] = `${before}${base}bolt-v${version}/${archive}${after}`;
			pending = archive;
			urls++;
			continue;
		}
		const sha = SHA256.exec(line);
		if (sha) {
			if (!pending) throw new Error(`a sha256 without a release url before it (line ${index + 1})`);
			lines[index] = `${sha[1]}${sums.get(pending)}${sha[2]}`;
			pending = undefined;
			continue;
		}
		const versionLine = VERSION.exec(line);
		if (versionLine) {
			lines[index] = `${versionLine[1]}${version}${versionLine[2]}`;
			versions++;
		}
	}
	if (pending) throw new Error(`no sha256 after the url of ${pending}`);
	if (urls === 0) throw new Error("the formula has no release url");
	if (versions !== 1) throw new Error(`the formula has ${versions} version lines, not one`);
	return lines.join("\n");
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
	const [formula, sumsFile, version] = process.argv.slice(2);
	if (!formula || !sumsFile || !version) {
		console.error("usage: node scripts/update-homebrew-formula.mjs <Formula/pi-bolt.rb> <SHA256SUMS> <version>");
		process.exit(2);
	}
	try {
		const before = readFileSync(formula, "utf8");
		const after = updateFormula(before, readFileSync(sumsFile, "utf8"), version);
		writeFileSync(formula, after);
		console.log(after === before ? `${formula} is already pi-bolt ${version}` : `${formula}: pi-bolt ${version}`);
	} catch (error) {
		console.error(`error: ${error instanceof Error ? error.message : String(error)}`);
		process.exit(1);
	}
}
