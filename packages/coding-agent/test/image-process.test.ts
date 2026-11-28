import { deflateSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import { processImage } from "../src/utils/image-process.ts";
import { detectSupportedImageMimeType } from "../src/utils/mime.ts";

function createTinyBmp1x1Red24bpp(): Buffer {
	const buffer = Buffer.alloc(58);
	buffer.write("BM", 0, "ascii");
	buffer.writeUInt32LE(buffer.length, 2);
	buffer.writeUInt32LE(54, 10);
	buffer.writeUInt32LE(40, 14);
	buffer.writeInt32LE(1, 18);
	buffer.writeInt32LE(1, 22);
	buffer.writeUInt16LE(1, 26);
	buffer.writeUInt16LE(24, 28);
	buffer.writeUInt32LE(0, 30);
	buffer.writeUInt32LE(4, 34);
	buffer[56] = 0xff;
	return buffer;
}

function crc32(buffer: Buffer): number {
	let crc = 0xffffffff;
	for (const byte of buffer) {
		crc ^= byte;
		for (let k = 0; k < 8; k++) crc = crc & 1 ? 0xedb88320 ^ (crc >>> 1) : crc >>> 1;
	}
	return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type: string, data: Buffer): Buffer {
	const length = Buffer.alloc(4);
	length.writeUInt32BE(data.length);
	const typed = Buffer.concat([Buffer.from(type, "ascii"), data]);
	const crc = Buffer.alloc(4);
	crc.writeUInt32BE(crc32(typed));
	return Buffer.concat([length, typed, crc]);
}

/** A valid 4x4 RGB PNG. */
function createTinyPng(): Buffer {
	const header = Buffer.alloc(13);
	header.writeUInt32BE(4, 0);
	header.writeUInt32BE(4, 4);
	header[8] = 8;
	header[9] = 2;
	return Buffer.concat([
		Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
		pngChunk("IHDR", header),
		pngChunk("IDAT", deflateSync(Buffer.alloc(4 * 13))),
		pngChunk("IEND", Buffer.alloc(0)),
	]);
}

function expectPngMagic(base64Data: string): void {
	const buffer = Buffer.from(base64Data, "base64");
	expect(buffer[0]).toBe(0x89);
	expect(buffer[1]).toBe(0x50);
	expect(buffer[2]).toBe(0x4e);
	expect(buffer[3]).toBe(0x47);
}

describe("image processing pipeline", () => {
	it.each(["GIF87a", "GIF89a"])("detects the complete %s signature", (signature) => {
		expect(detectSupportedImageMimeType(Buffer.from(signature, "ascii"))).toBe("image/gif");
	});

	it("detects BMP files from magic bytes", () => {
		expect(detectSupportedImageMimeType(createTinyBmp1x1Red24bpp())).toBe("image/bmp");
	});

	it("converts BMP files to PNG attachments when auto-resize is disabled", async () => {
		const result = await processImage(createTinyBmp1x1Red24bpp(), "image/bmp", { autoResizeImages: false });

		expect(result.ok).toBe(true);
		if (!result.ok) return;
		expect(result.mimeType).toBe("image/png");
		expect(result.hints).toContain("[Image converted from image/bmp to image/png.]");
		expectPngMagic(result.data);
	});

	it("converts BMP files before auto-resizing", async () => {
		const result = await processImage(createTinyBmp1x1Red24bpp(), "image/bmp");

		expect(result.ok).toBe(true);
		if (!result.ok) return;
		expect(result.mimeType).toBe("image/png");
		expect(result.hints).toContain("[Image converted from image/bmp to image/png.]");
		expectPngMagic(result.data);
	});

	// upstream #12239: an image whose declared type did not match its bytes was sent as declared; the provider rejected
	// it on every later request of the session.
	it.each([true, false])("sends a PNG declared as JPEG as a PNG (auto-resize %s)", async (autoResizeImages) => {
		const result = await processImage(createTinyPng(), "image/jpeg", { autoResizeImages });

		expect(result.ok).toBe(true);
		if (!result.ok) return;
		expect(result.mimeType).toBe("image/png");
		expectPngMagic(result.data);
	});

	it("converts a BMP declared as PNG", async () => {
		const result = await processImage(createTinyBmp1x1Red24bpp(), "image/png", { autoResizeImages: false });

		expect(result.ok).toBe(true);
		if (!result.ok) return;
		expect(result.mimeType).toBe("image/png");
		expectPngMagic(result.data);
	});

	it("drops bytes of no image type with a note instead of sending them", async () => {
		const result = await processImage(Buffer.from("<html>not an image</html>"), "image/png", {
			autoResizeImages: false,
		});

		expect(result.ok).toBe(false);
		if (result.ok) return;
		expect(result.message).toContain("Image omitted");
	});
});
