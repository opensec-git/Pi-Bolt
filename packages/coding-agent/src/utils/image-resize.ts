import type { Worker } from "node:worker_threads";
import {
	IMAGE_RESIZE_WORKER_RESPONSE_TYPE,
	type ImageResizeOptions,
	isWithinLimits,
	keepIfWithinLimits,
	type ResizedImage,
	type ResizeImageWorkerResponse,
	readDisplayedImageSize,
	resizeImageInProcess,
	resolveImageResizeOptions,
} from "./image-resize-core.ts";
import {
	decodesNatively,
	getNativeImage,
	type NativeImageConstructor,
	resizeImageNative,
} from "./image-resize-native.ts";

export type { ImageResizeOptions, ResizedImage } from "./image-resize-core.ts";

function toTransferableBytes(input: Uint8Array): Uint8Array<ArrayBuffer> {
	// Transfer detaches the buffer, so transfer a worker-owned copy and leave the
	// caller's bytes intact.
	return new Uint8Array(input);
}

function isResizeImageWorkerResponse(value: unknown): value is ResizeImageWorkerResponse {
	return (
		value !== null && typeof value === "object" && "type" in value && value.type === IMAGE_RESIZE_WORKER_RESPONSE_TYPE
	);
}

function createResizeWorker(workerSpecifier: string | URL): Worker {
	// worker_threads loads on first use, not at every start.
	return new (process.getBuiltinModule("node:worker_threads").Worker)(workerSpecifier);
}

async function resizeImageInWorker(
	workerSpecifier: string | URL,
	inputBytes: Uint8Array,
	mimeType: string,
	options?: ImageResizeOptions,
): Promise<ResizedImage | null> {
	const worker = createResizeWorker(workerSpecifier);
	try {
		const inputBytesForWorker = toTransferableBytes(inputBytes);
		return await new Promise<ResizedImage | null>((resolve, reject) => {
			let settled = false;
			const settle = (result: ResizedImage | null): void => {
				if (settled) return;
				settled = true;
				resolve(result);
			};
			const fail = (error: Error): void => {
				if (settled) return;
				settled = true;
				reject(error);
			};

			worker.on("message", (message: unknown) => {
				// Ignore messages Node posts on the worker channel itself, such as
				// `{ "watch:require": [...] }` under `node --watch` (nodejs/node#65044).
				if (!isResizeImageWorkerResponse(message)) return;
				if ("error" in message) fail(new Error(message.error));
				else settle(message.result);
			});
			worker.once("error", fail);
			worker.once("exit", (code) => {
				if (!settled) {
					fail(new Error(`Image resize worker exited with code ${code}`));
				}
			});
			worker.postMessage(
				{
					inputBytes: inputBytesForWorker,
					mimeType,
					options,
				},
				[inputBytesForWorker.buffer],
			);
		});
	} finally {
		// Await termination so a serial resize never starts a worker while this one is
		// still shutting down; that overlap aborted the process on macOS with Node 24 (#10695).
		await worker.terminate().catch(() => undefined);
	}
}

/**
 * The input as it is, when its header shows it is within the limits and its data is known to
 * decode, else undefined (the caller then decodes it with photon, which also rejects damaged data).
 *
 * - PNG and WebP, with a native image pipeline: the native decoder must decode it.
 * - Not JPEG or GIF: the native decoders accept damaged data photon rejects (libjpeg-turbo decodes
 *   corrupt or truncated scan data with only a warning), and such an image must still be omitted.
 * - Not without a native pipeline (Node, where photon runs with a JIT): photon decodes it as before.
 */
export async function keepValidImageWithinLimits(
	inputBytes: Uint8Array,
	mimeType: string,
	width: number,
	height: number,
	opts: Required<ImageResizeOptions>,
	NativeImage: NativeImageConstructor | undefined,
): Promise<ResizedImage | undefined> {
	if (!isWithinLimits(width, height, inputBytes.byteLength, opts)) return undefined;
	if (!NativeImage || (mimeType !== "image/png" && mimeType !== "image/webp")) return undefined;
	if (!(await decodesNatively(NativeImage, inputBytes))) return undefined;
	return keepIfWithinLimits(inputBytes, mimeType, width, height, opts);
}

/**
 * Resize an image to fit within the specified max dimensions and encoded file size.
 *
 * - An image within the limits whose data is known to decode is returned as it is, without photon
 *   (see keepValidImageWithinLimits).
 * - An image over the limits, when the runtime has a native image pipeline (`Bun.Image`), is
 *   resized there.
 * - Otherwise, or when the header cannot be read or the native pipeline fails, Photon runs in a
 *   worker thread so WASM decoding, resizing, and encoding do not block the TUI event loop. If the
 *   worker cannot be loaded (for example in some Bun compiled executable layouts), fall back to
 *   in-process resizing so image reads still work.
 */
export async function resizeImage(
	inputBytes: Uint8Array,
	mimeType: string,
	options?: ImageResizeOptions,
): Promise<ResizedImage | null> {
	const opts = resolveImageResizeOptions(options);
	const size = readDisplayedImageSize(inputBytes, mimeType);
	if (size) {
		const NativeImage = getNativeImage();
		if (isWithinLimits(size.width, size.height, inputBytes.byteLength, opts)) {
			const unchanged = await keepValidImageWithinLimits(
				inputBytes,
				mimeType,
				size.width,
				size.height,
				opts,
				NativeImage,
			);
			if (unchanged) return unchanged;
		} else if (NativeImage) {
			try {
				return await resizeImageNative(NativeImage, inputBytes, size.width, size.height, opts);
			} catch {}
		}
	}

	const isTypeScriptRuntime = import.meta.url.endsWith(".ts");
	const workerUrl = new URL(
		isTypeScriptRuntime ? "./image-resize-worker.ts" : "./image-resize-worker.js",
		import.meta.url,
	);

	// Bun compiled executables resolve worker entrypoints by string path, not via
	// new URL(..., import.meta.url). Try the string path first under Bun so the
	// release binary uses the embedded worker instead of falling back in-process.
	if (typeof process.versions.bun === "string") {
		try {
			return await resizeImageInWorker("./src/utils/image-resize-worker.ts", inputBytes, mimeType, options);
		} catch {}
	}

	try {
		return await resizeImageInWorker(workerUrl, inputBytes, mimeType, options);
	} catch {
		return resizeImageInProcess(inputBytes, mimeType, options);
	}
}

/**
 * Format a dimension note for resized images.
 * This helps the model understand the coordinate mapping.
 */
export function formatDimensionNote(result: ResizedImage): string | undefined {
	if (!result.wasResized) {
		return undefined;
	}

	const scale = result.originalWidth / result.width;
	return `[Image: original ${result.originalWidth}x${result.originalHeight}, displayed at ${result.width}x${result.height}. Multiply coordinates by ${scale.toFixed(2)} to map to original image.]`;
}
