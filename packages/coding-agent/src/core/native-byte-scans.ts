import { createRequire } from "node:module";
import { isBunBinary } from "../config.ts";

type ByteScans = ((data: Uint8Array) => number) & {
	/** Offset of the first delimiter, control character, or incomplete/invalid JSON escape. */
	jsonStringBodyEnd?: (data: Uint8Array) => number;
};

let cached: ByteScans | null | undefined;

/** Older packages export only the line counter. Source installs and packages without the addon use JS. */
export function nativeByteScans(): ByteScans | undefined {
	if (!isBunBinary) return undefined;
	if (cached === undefined) {
		try {
			const helper = createRequire(process.execPath)("./native/count-lines.node");
			cached = typeof helper === "function" ? helper : null;
		} catch {
			cached = null;
		}
	}
	return cached ?? undefined;
}
