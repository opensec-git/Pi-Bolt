// An extension that starts workers, a few at once, as Pi does to resize an image (utils/image-resize.ts). Each worker is a realm
// of its own, which takes a block of the static region; on Windows the region had room for the main realm's only, and the first
// worker ended the process (0xC0000409, "The static region is too small for the program").
const source = "self.onmessage = (event) => postMessage(event.data * 2);";
function once(value) {
	return new Promise((resolve, reject) => {
		const worker = new Worker(URL.createObjectURL(new Blob([source], { type: "application/javascript" })));
		worker.onmessage = (event) => {
			worker.terminate();
			resolve(event.data);
		};
		worker.onerror = (event) => reject(new Error(event.message));
		worker.postMessage(value);
	});
}

export default function () {
	const rounds = async () => {
		const results = [];
		// Four at once, three times: blocks are given back when a worker ends, and taken again.
		for (let round = 0; round < 3; round++) results.push(...(await Promise.all([1, 2, 3, 4].map((n) => once(n + round * 10)))));
		return results;
	};
	rounds().then(
		(results) => {
			console.log(`workers: ${results.length} answers, sum ${results.reduce((a, b) => a + b, 0)}`);
			process.exit(0);
		},
		(error) => {
			console.log(`workers failed: ${error.message}`);
			process.exit(0);
		},
	);
}
