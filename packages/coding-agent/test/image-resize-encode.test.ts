import { describe, expect, it } from "vitest";
import { encodeWithinLimits, type ResizedFrame, resolveImageResizeOptions } from "../src/utils/image-resize-core.ts";
import { resizeImageNative } from "../src/utils/image-resize-native.ts";

/** A frame whose encodings have the given byte sizes, recording what was encoded. */
function fakeFrames(sizes: { png: number; jpeg: Record<number, number> }, log: string[]) {
	return (width: number, height: number): ResizedFrame => {
		log.push(`resize ${width}x${height}`);
		return {
			png: () => {
				log.push("png");
				return new Uint8Array(sizes.png);
			},
			jpeg: (quality) => {
				log.push(`jpeg ${quality}`);
				return new Uint8Array(sizes.jpeg[quality] ?? 1_000_000);
			},
			free: () => log.push("free"),
		};
	};
}

describe("encodeWithinLimits", () => {
	// maxBytes counts base64: n bytes encode to ceil(n / 3) * 4 characters.
	const opts = resolveImageResizeOptions({ maxWidth: 100, maxHeight: 100, maxBytes: 4000 });

	it("encodes the PNG and one JPEG, and keeps the PNG when it is the smaller", async () => {
		const log: string[] = [];
		const result = await encodeWithinLimits(400, 200, opts, fakeFrames({ png: 2000, jpeg: {} }, log));
		expect(log).toEqual(["resize 100x50", "png", "jpeg 80", "free"]);
		expect(result).toMatchObject({ mimeType: "image/png", width: 100, height: 50, wasResized: true });
	});

	it("tries JPEG qualities in order and stops at the first that fits", async () => {
		const log: string[] = [];
		const result = await encodeWithinLimits(
			400,
			200,
			opts,
			fakeFrames({ png: 5000, jpeg: { 80: 3500, 85: 3600, 70: 2900 } }, log),
		);
		expect(log).toEqual(["resize 100x50", "png", "jpeg 80", "jpeg 85", "jpeg 70", "free"]);
		expect(result).toMatchObject({ mimeType: "image/jpeg", width: 100, height: 50 });
	});

	it("shrinks by 75% when no encoding fits, and gives up at 1x1", async () => {
		const log: string[] = [];
		expect(await encodeWithinLimits(2, 2, opts, fakeFrames({ png: 5000, jpeg: {} }, log))).toBeNull();
		expect(log.filter((entry) => entry.startsWith("resize"))).toEqual(["resize 2x2", "resize 1x1"]);
	});
});

describe("resizeImageNative", () => {
	/** A stand-in for Bun.Image that records the pipeline and encodes to `size` bytes. */
	function fakeNativeImage(log: string[], size: (format: string) => number) {
		return class {
			constructor(_input: Uint8Array, options: { autoOrient: boolean }) {
				log.push(`new autoOrient=${options.autoOrient}`);
			}
			resize(width: number, height: number, options: { filter: string; fit: string }) {
				log.push(`resize ${width}x${height} ${options.filter} ${options.fit}`);
				return this;
			}
			rotate(degrees: number) {
				log.push(`rotate ${degrees}`);
				return this;
			}
			flip() {
				log.push("flip");
				return this;
			}
			flop() {
				log.push("flop");
				return this;
			}
			private format = "";
			png() {
				this.format = "png";
				return this;
			}
			jpeg(options: { quality: number }) {
				this.format = `jpeg ${options.quality}`;
				return this;
			}
			async bytes(): Promise<Uint8Array> {
				log.push(`encode ${this.format}`);
				return new Uint8Array(size(this.format));
			}
		};
	}

	it("applies the EXIF orientation itself, resizes with Lanczos3 and stops at the first fit", async () => {
		// A JPEG start with an APP1 Exif segment, orientation 6 (rotate 90 degrees clockwise).
		const exif = Buffer.from("457869660000" + "49492a00080000000100120103000100000006000000000000000000", "hex");
		const app1 = Buffer.concat([Buffer.from([0xff, 0xe1, 0, exif.length + 2]), exif]);
		const input = Buffer.concat([Buffer.from([0xff, 0xd8]), app1, Buffer.from([0xff, 0xd9])]);
		const log: string[] = [];
		const NativeImage = fakeNativeImage(log, (format) => (format === "png" ? 9000 : 1000));

		const result = await resizeImageNative(
			NativeImage,
			input,
			300,
			600,
			resolveImageResizeOptions({ maxWidth: 200, maxHeight: 200, maxBytes: 4000 }),
		);

		expect(log).toEqual([
			"new autoOrient=false",
			"rotate 90",
			"resize 100x200 lanczos3 fill",
			"encode png",
			"new autoOrient=false",
			"rotate 90",
			"resize 100x200 lanczos3 fill",
			"encode jpeg 80",
		]);
		expect(result).toMatchObject({
			mimeType: "image/jpeg",
			originalWidth: 300,
			originalHeight: 600,
			width: 100,
			height: 200,
			wasResized: true,
		});
	});

	it("passes native failures to the caller", async () => {
		const log: string[] = [];
		const Failing = class extends fakeNativeImage(log, () => 0) {
			override async bytes(): Promise<Uint8Array> {
				throw new Error("ERR_IMAGE_DECODE_FAILED");
			}
		};
		await expect(
			resizeImageNative(Failing, new Uint8Array([0xff, 0xd8, 0xff, 0xd9]), 300, 300, resolveImageResizeOptions()),
		).rejects.toThrow("ERR_IMAGE_DECODE_FAILED");
	});
});
