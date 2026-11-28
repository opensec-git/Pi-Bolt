import { convertImageBytesToPng } from "./image-convert.ts";
import { formatDimensionNote, type ImageResizeOptions, resizeImage } from "./image-resize.ts";
import { resolveImageResizeOptions } from "./image-resize-core.ts";
import { detectSupportedImageMimeType } from "./mime.ts";

export interface ProcessImageOptions {
	/** Whether to resize images to inline provider limits. Default: true */
	autoResizeImages?: boolean;
	/** Optional resize overrides. Uses resizeImage defaults when omitted. */
	resizeOptions?: ImageResizeOptions;
}

export type ProcessImageResult =
	| {
			ok: true;
			data: string;
			mimeType: string;
			hints: string[];
	  }
	| {
			ok: false;
			message: string;
	  };

interface NormalizedImage {
	bytes: Uint8Array;
	mimeType: string;
	convertedFrom?: string;
}

function baseMimeType(mimeType: string): string {
	return mimeType.split(";")[0]?.trim().toLowerCase() ?? mimeType.toLowerCase();
}

function normalizeSupportedImageMimeType(mimeType: string): string | null {
	switch (baseMimeType(mimeType)) {
		case "image/png":
			return "image/png";
		case "image/jpeg":
		case "image/jpg":
			return "image/jpeg";
		case "image/gif":
			return "image/gif";
		case "image/webp":
			return "image/webp";
		default:
			return null;
	}
}

/** Whether `bytes` start with the signature of `mimeType`, for variants the detector does not accept (APNG, JPEG-LS). */
function hasSignatureOf(bytes: Uint8Array, mimeType: string): boolean {
	if (mimeType === "image/png")
		return bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
	if (mimeType === "image/jpeg") return bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
	return false;
}

/**
 * The image as an inline type, taken from its bytes rather than the declared type: providers reject an image whose
 * declared type does not match its bytes, and the image then fails every later request of the session (upstream
 * #12239). Bytes of no supported type are converted, or the image is dropped.
 */
async function normalizeImage(bytes: Uint8Array, mimeType: string): Promise<NormalizedImage | null> {
	const detectedMimeType = detectSupportedImageMimeType(bytes);
	const inlineMimeType = detectedMimeType ? normalizeSupportedImageMimeType(detectedMimeType) : null;
	if (inlineMimeType) {
		return { bytes, mimeType: inlineMimeType };
	}
	const declaredMimeType = normalizeSupportedImageMimeType(mimeType);
	if (!detectedMimeType && declaredMimeType && hasSignatureOf(bytes, declaredMimeType)) {
		return { bytes, mimeType: declaredMimeType };
	}

	const pngBytes = await convertImageBytesToPng(bytes);
	if (!pngBytes) {
		return null;
	}

	return {
		bytes: pngBytes,
		mimeType: "image/png",
		convertedFrom: detectedMimeType ?? baseMimeType(mimeType),
	};
}

function conversionHint(from: string | undefined, to: string): string | undefined {
	if (!from || from === to) return undefined;
	return `[Image converted from ${from} to ${to}.]`;
}

export async function processImage(
	bytes: Uint8Array,
	mimeType: string,
	options?: ProcessImageOptions,
): Promise<ProcessImageResult> {
	const autoResizeImages = options?.autoResizeImages ?? true;
	const normalized = await normalizeImage(bytes, mimeType);
	if (!normalized) {
		return {
			ok: false,
			message: "[Image omitted: could not be converted to a supported inline image format.]",
		};
	}

	if (autoResizeImages) {
		const resized = await resizeImage(normalized.bytes, normalized.mimeType, options?.resizeOptions);
		if (!resized) {
			return {
				ok: false,
				message: "[Image omitted: could not be resized below the inline image size limit.]",
			};
		}

		const hints: string[] = [];
		const convertedHint = conversionHint(normalized.convertedFrom, resized.mimeType);
		if (convertedHint) hints.push(convertedHint);
		const dimensionNote = formatDimensionNote(resized);
		if (dimensionNote) hints.push(dimensionNote);

		return {
			ok: true,
			data: resized.data,
			mimeType: resized.mimeType,
			hints,
		};
	}

	const hints: string[] = [];
	const convertedHint = conversionHint(normalized.convertedFrom, normalized.mimeType);
	if (convertedHint) hints.push(convertedHint);

	return {
		ok: true,
		data: Buffer.from(normalized.bytes).toString("base64"),
		mimeType: normalized.mimeType,
		hints,
	};
}

interface ProcessedImageBlock {
	data: string;
	mimeType: string;
	autoResizeImages: boolean;
	limits: Required<ImageResizeOptions>;
}

/** Image blocks built from processImage results, with what they were processed for. */
const processedImageBlocks = new WeakMap<object, ProcessedImageBlock>();

/**
 * Record that `block` holds a processImage result for `options`. Processing such a block again with
 * the same options returns it unchanged (the result is already within the limits and in a supported
 * format), so tool-result normalization can skip it: see isProcessedImageBlock.
 */
export function markProcessedImageBlock(
	block: { data: string; mimeType: string },
	options: ProcessImageOptions | undefined,
): void {
	processedImageBlocks.set(block, {
		data: block.data,
		mimeType: block.mimeType,
		autoResizeImages: options?.autoResizeImages ?? true,
		limits: resolveImageResizeOptions(options?.resizeOptions),
	});
}

/** Whether `block` is unchanged since markProcessedImageBlock recorded it for the same options. */
export function isProcessedImageBlock(
	block: { data: string; mimeType: string },
	options: ProcessImageOptions | undefined,
): boolean {
	const processed = processedImageBlocks.get(block);
	if (!processed || processed.data !== block.data || processed.mimeType !== block.mimeType) return false;
	const autoResizeImages = options?.autoResizeImages ?? true;
	if (processed.autoResizeImages !== autoResizeImages) return false;
	if (!autoResizeImages) return true;
	const limits = resolveImageResizeOptions(options?.resizeOptions);
	return (
		processed.limits.maxWidth === limits.maxWidth &&
		processed.limits.maxHeight === limits.maxHeight &&
		processed.limits.maxBytes === limits.maxBytes &&
		processed.limits.jpegQuality === limits.jpegQuality
	);
}
