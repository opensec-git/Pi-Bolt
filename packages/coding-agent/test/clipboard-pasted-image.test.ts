import { existsSync, readFileSync, statSync } from "fs";
import { tmpdir } from "os";
import { dirname, sep } from "path";
import { afterEach, describe, expect, it } from "vitest";
import { removePastedImages, writePastedImage } from "../src/utils/clipboard-image.ts";

const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

describe("pasted images", () => {
	afterEach(() => {
		removePastedImages();
	});

	it("are written to a folder of their own in the temp folder, and removed with it", () => {
		const first = writePastedImage({ bytes: png, mimeType: "image/png" });
		const second = writePastedImage({ bytes: png, mimeType: "image/jpeg" });
		expect(first.endsWith(".png")).toBe(true);
		expect(second.endsWith(".jpg")).toBe(true);
		expect(readFileSync(first)).toEqual(Buffer.from(png));
		const folder = dirname(first);
		expect(dirname(second)).toBe(folder);
		expect(folder.startsWith(`${tmpdir()}${sep}pi-clipboard-`)).toBe(true);

		removePastedImages();
		expect(existsSync(folder)).toBe(false);
	});

	// Windows keeps only the read-only bit of a mode; there the temp folder is the user's own.
	it.skipIf(process.platform === "win32")("can be read only by the user", () => {
		const file = writePastedImage({ bytes: png, mimeType: "image/png" });
		expect(statSync(dirname(file)).mode & 0o777).toBe(0o700);
		expect(statSync(file).mode & 0o777).toBe(0o600);
	});
});
