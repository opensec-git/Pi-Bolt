// hunt-engine-1: fetch() of a slice of a Blob made of other Blobs (256 KiB or more, which the runtime keeps as parts) must send
// the slice. It sent the whole parent Blob, with the parent's Content-Length. The slice keeps only the parts it covers; reading it
// must give the same bytes.
// Prints, for each body, whether the bytes the server received are the body's, then whether arrayBuffer() of it is.
const hash = (bytes) => {
  let h = 0;
  for (const b of bytes) h = (Math.imul(h, 31) + b) | 0;
  return `${bytes.length}:${h}`;
};
const server = Bun.serve({
  port: 0,
  async fetch(req) {
    return new Response(hash(new Uint8Array(await req.arrayBuffer())));
  },
});
const kib = 1024;
const a = new Uint8Array(200 * kib).map((_, i) => 65 + (i % 26));
const b = new Uint8Array(200 * kib).map((_, i) => 97 + (i % 26));
const reference = new Uint8Array([...a, ...b]);
const big = () => new Blob([new Blob([a]), new Blob([b])]);
// Each body, and the bytes of `reference` it is.
const bodies = {
  whole: [() => big(), 0, reference.length],
  head: [() => big().slice(0, 1000), 0, 1000],
  middle: [() => big().slice(200 * kib - 5, 200 * kib + 5), 200 * kib - 5, 200 * kib + 5],
  tail: [() => big().slice(-100), reference.length - 100, reference.length],
  within: [() => big().slice(210 * kib, 300 * kib), 210 * kib, 300 * kib],
  nested: [() => big().slice(100, 300 * kib).slice(1000, 250 * kib), 1100, 100 + 250 * kib],
  empty: [() => big().slice(300, 300), 0, 0],
};
const sent = [];
const read = [];
for (const [name, [make, start, end]] of Object.entries(bodies)) {
  const expected = hash(reference.subarray(start, end));
  const body = make();
  const received = await (await fetch(`http://127.0.0.1:${server.port}/`, { method: "POST", body })).text();
  sent.push(`${name}=${received === expected ? "ok" : `sent ${received.split(":")[0]} of ${body.size}`}`);
  const bytes = hash(new Uint8Array(await make().arrayBuffer()));
  read.push(`${name}=${bytes === expected ? "ok" : `read ${bytes.split(":")[0]} of ${end - start}`}`);
}
server.stop(true);
console.log(`${sent.join(" ")} fetch; read: ${read.join(" ")}`);
