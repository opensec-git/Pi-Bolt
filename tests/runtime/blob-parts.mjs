// hunt-engine-1: fetch() of a slice of a Blob made of other Blobs (256 KiB or more, which the runtime keeps as parts) must send
// the slice. It sent the whole parent Blob, with the parent's Content-Length.
// Prints, for each body, the bytes the server received and whether they are the body's.
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
const a = new Blob([new Uint8Array(200 * 1024).fill(65)]);
const b = new Blob([new Uint8Array(200 * 1024).fill(66)]);
const bodies = {
  whole: () => new Blob([a, b]),
  head: () => new Blob([a, b]).slice(0, 1000),
  middle: () => new Blob([a, b]).slice(200 * 1024 - 5, 200 * 1024 + 5),
  tail: () => new Blob([a, b]).slice(-100),
};
const out = [];
for (const [name, make] of Object.entries(bodies)) {
  const body = make();
  const received = await (await fetch(`http://127.0.0.1:${server.port}/`, { method: "POST", body })).text();
  const expected = hash(new Uint8Array(await make().arrayBuffer()));
  out.push(`${name}=${received === expected ? "ok" : `sent ${received.split(":")[0]} of ${body.size}`}`);
}
server.stop(true);
console.log(out.join(" "));
