// The module that deferred-namespace.mjs loads later.
import * as events from "node:events";
import * as zlib from "node:zlib";
import * as path from "node:path";
import * as stream from "node:stream";

function describeOne(name, ns) {
  let assigned;
  try {
    const target = ns;
    target.added = 1;
    assigned = "assigned";
  } catch (error) {
    assigned = error.constructor.name;
  }
  return [name, Object.prototype.toString.call(ns), typeof ns, Object.isExtensible(ns), assigned, Object.keys(ns).length, "length" in ns, "Z_NO_FLUSH" in ns].join(" ");
}

export function describe() {
  return [describeOne("events", events), describeOne("zlib", zlib), describeOne("path", path), describeOne("stream", stream)];
}
