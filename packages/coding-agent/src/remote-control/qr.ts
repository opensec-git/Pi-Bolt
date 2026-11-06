/** The pairing QR code as terminal text: two modules per character cell, black on white whatever the theme. */

import { encode } from "uqr";

const BLACK = 16;
const WHITE = 231;
const RESET = "\x1b[0m";

/** Lines of the QR code; each line is `width` cells wide. */
export function renderQrLines(text: string): { lines: string[]; width: number } {
	const { data } = encode(text, { ecc: "L", border: 2 });
	const lines: string[] = [];
	for (let row = 0; row < data.length; row += 2) {
		let line = "";
		for (let column = 0; column < data[row].length; column++) {
			const top = data[row][column] ? BLACK : WHITE;
			const bottom = row + 1 < data.length && data[row + 1][column] ? BLACK : WHITE;
			line += `\x1b[38;5;${top}m\x1b[48;5;${bottom}m▀`;
		}
		lines.push(`${line}${RESET}`);
	}
	return { lines, width: data[0]?.length ?? 0 };
}
