import { applyExifOrientation, getExifOrientation } from "./exif-orientation.ts";
import { type ImageHeader, readImageHeader } from "./image-header.ts";
import { loadPhoton } from "./photon.ts";

export interface ImageResizeOptions {
	maxWidth?: number; // Default: 2000
	maxHeight?: number; // Default: 2000
	maxBytes?: number; // Default: 4.5MB of base64 payload (below Anthropic's 5MB limit)
	jpegQuality?: number; // Default: 80
}

export interface ResizedImage {
	data: string; // base64
	mimeType: string;
	originalWidth: number;
	originalHeight: number;
	width: number;
	height: number;
	wasResized: boolean;
}

/**
 * Tag on image resize worker replies. Node can post its own messages on the worker channel
 * (for example `{ "watch:require": [...] }` under `node --watch`), so replies must be identifiable.
 */
export const IMAGE_RESIZE_WORKER_RESPONSE_TYPE = "pi:image-resize-response";

export type ResizeImageWorkerResponse =
	| { type: typeof IMAGE_RESIZE_WORKER_RESPONSE_TYPE; result: ResizedImage | null }
	| { type: typeof IMAGE_RESIZE_WORKER_RESPONSE_TYPE; error: string };

// 4.5MB of base64 payload. Provides headroom below Anthropic's 5MB limit.
const DEFAULT_MAX_BYTES = 4.5 * 1024 * 1024;

const DEFAULT_OPTIONS: Required<ImageResizeOptions> = {
	maxWidth: 2000,
	maxHeight: 2000,
	maxBytes: DEFAULT_MAX_BYTES,
	jpegQuality: 80,
};

/** The limits with defaults applied. */
export function resolveImageResizeOptions(options?: ImageResizeOptions): Required<ImageResizeOptions> {
	return { ...DEFAULT_OPTIONS, ...options };
}

/**
 * Width and height as displayed, read from the file header: EXIF orientations 5-8 rotate the image
 * by 90 degrees. Undefined when the header cannot be read; the caller then decodes the image.
 */
export function readDisplayedImageSize(inputBytes: Uint8Array, mimeType: string): ImageHeader | undefined {
	// The header is checked first: it also validates the structure the EXIF reader walks.
	const header = readImageHeader(inputBytes, mimeType);
	if (!header) return undefined;
	return getExifOrientation(inputBytes) >= 5 ? { width: header.height, height: header.width } : header;
}

function base64Size(byteLength: number): number {
	return Math.ceil(byteLength / 3) * 4;
}

/** Whether an image of this displayed size (after EXIF orientation) and byte length needs no change. */
export function isWithinLimits(
	width: number,
	height: number,
	byteLength: number,
	opts: Required<ImageResizeOptions>,
): boolean {
	return width <= opts.maxWidth && height <= opts.maxHeight && base64Size(byteLength) < opts.maxBytes;
}

/**
 * The input as it is when it is already within the dimension and size limits, else undefined.
 * `width` and `height` are the displayed size (after EXIF orientation).
 */
export function keepIfWithinLimits(
	inputBytes: Uint8Array,
	mimeType: string,
	width: number,
	height: number,
	opts: Required<ImageResizeOptions>,
): ResizedImage | undefined {
	if (!isWithinLimits(width, height, inputBytes.byteLength, opts)) return undefined;
	const format = mimeType.split("/")[1] ?? "png";
	return {
		data: Buffer.from(inputBytes.buffer, inputBytes.byteOffset, inputBytes.byteLength).toString("base64"),
		mimeType: mimeType || `image/${format}`,
		originalWidth: width,
		originalHeight: height,
		width,
		height,
		wasResized: false,
	};
}

/** One resized version of the image, encoded on demand. */
export interface ResizedFrame {
	png(): Uint8Array | Promise<Uint8Array>;
	jpeg(quality: number): Uint8Array | Promise<Uint8Array>;
	free(): void;
}

/**
 * Resize to fit `maxWidth`/`maxHeight`, then find the first encoding under `maxBytes`: the smaller of PNG and
 * JPEG at the configured quality, then the other, then JPEG at each lower quality step, then the same at 75% of
 * the size, down to 1x1. The lower quality steps are encoded one at a time and the first that fits wins, so later
 * candidates are not encoded at all. Returns null when nothing fits.
 */
export async function encodeWithinLimits(
	originalWidth: number,
	originalHeight: number,
	opts: Required<ImageResizeOptions>,
	resizeTo: (width: number, height: number) => ResizedFrame,
): Promise<ResizedImage | null> {
	let currentWidth = originalWidth;
	let currentHeight = originalHeight;
	if (currentWidth > opts.maxWidth) {
		currentHeight = Math.round((currentHeight * opts.maxWidth) / currentWidth);
		currentWidth = opts.maxWidth;
	}
	if (currentHeight > opts.maxHeight) {
		currentWidth = Math.round((currentWidth * opts.maxHeight) / currentHeight);
		currentHeight = opts.maxHeight;
	}

	const qualitySteps = Array.from(new Set([opts.jpegQuality, 85, 70, 55, 40]));
	const result = (bytes: Uint8Array, mimeType: string): ResizedImage | undefined => {
		const data = Buffer.from(bytes).toString("base64");
		if (Buffer.byteLength(data, "utf-8") >= opts.maxBytes) return undefined;
		return {
			data,
			mimeType,
			originalWidth,
			originalHeight,
			width: currentWidth,
			height: currentHeight,
			wasResized: true,
		};
	};

	while (true) {
		const frame = resizeTo(currentWidth, currentHeight);
		try {
			const [firstQuality, ...lowerQualities] = qualitySteps;
			const png = await frame.png();
			const jpeg = await frame.jpeg(firstQuality);
			const preferred = [
				{ bytes: png, mimeType: "image/png" },
				{ bytes: jpeg, mimeType: "image/jpeg" },
			];
			// Prefer the smaller of PNG and JPEG at the configured quality; lower JPEG quality only if neither fits.
			if (base64Size(jpeg.byteLength) < base64Size(png.byteLength)) preferred.reverse();
			for (const { bytes, mimeType } of preferred) {
				const fits = result(bytes, mimeType);
				if (fits) return fits;
			}
			for (const quality of lowerQualities) {
				const lower = result(await frame.jpeg(quality), "image/jpeg");
				if (lower) return lower;
			}
		} finally {
			frame.free();
		}

		if (currentWidth === 1 && currentHeight === 1) {
			return null;
		}

		const nextWidth = currentWidth === 1 ? 1 : Math.max(1, Math.floor(currentWidth * 0.75));
		const nextHeight = currentHeight === 1 ? 1 : Math.max(1, Math.floor(currentHeight * 0.75));
		if (nextWidth === currentWidth && nextHeight === currentHeight) {
			return null;
		}

		currentWidth = nextWidth;
		currentHeight = nextHeight;
	}
}

/**
 * Resize an image to fit within the specified max dimensions and encoded file size.
 * Returns null if the image cannot be resized below maxBytes.
 *
 * Photon (Rust/WASM) decodes the image once; an image within the limits is returned as it is, and
 * one over them is resized (see encodeWithinLimits). If Photon is not available or cannot decode
 * the image, returns null. resizeImage skips this decode when it can show the image is valid.
 */
export async function resizeImageInProcess(
	inputBytes: Uint8Array,
	mimeType: string,
	options?: ImageResizeOptions,
): Promise<ResizedImage | null> {
	const opts = resolveImageResizeOptions(options);
	const photon = await loadPhoton();
	if (!photon) {
		return null;
	}

	let image: ReturnType<typeof photon.PhotonImage.new_from_byteslice> | undefined;
	try {
		const rawImage = photon.PhotonImage.new_from_byteslice(inputBytes);
		image = applyExifOrientation(photon, rawImage, inputBytes);
		if (image !== rawImage) rawImage.free();
		const decoded = image;

		const originalWidth = decoded.get_width();
		const originalHeight = decoded.get_height();
		const decodedUnchanged = keepIfWithinLimits(inputBytes, mimeType, originalWidth, originalHeight, opts);
		if (decodedUnchanged) return decodedUnchanged;

		return await encodeWithinLimits(originalWidth, originalHeight, opts, (width, height) => {
			const resized = photon.resize(decoded, width, height, photon.SamplingFilter.Lanczos3);
			return {
				png: () => resized.get_bytes(),
				jpeg: (quality) => resized.get_bytes_jpeg(quality),
				free: () => resized.free(),
			};
		});
	} catch {
		return null;
	} finally {
		if (image) {
			image.free();
		}
	}
}
