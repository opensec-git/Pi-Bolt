// hunt-engine-2: `import * as ns from "<built-in>"` in a module loaded later (`import()`) is a module namespace object: tagged
// "Module", not extensible, assigning to it throws, and its keys are the module's exports. An executable compiled ahead of time
// gave such a module a plain object made from the built-in's `require()` value: "[object Object]", writable, and with keys that are
// not exports (`length` and `name` of EventEmitter, zlib's legacy constants).
const mod = await import("./deferred-namespace-mod.mjs");
for (const line of mod.describe()) console.log(line);
