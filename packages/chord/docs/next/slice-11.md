# Slice 11: web and TUI surfaces (spec)

Status: **draft** (owner, 2026-10-30: "start on slice 11"; "this one needs to be generic, so apps can do whatever they
want: css, tui components, themes"). Points marked **Decision needed** go to the owner one at a time.

Slice 11 builds the generic plumbing of the app kit's two client surfaces (decision "Slice 7b's open points" (6)):
`chord/next/web` (a browser page as a Chord client host that loads the plugins' web facets the server built) and
`chord/next/tui` (a terminal client that does the same for TUI facets). The app keeps everything visible: its HTML
shell, CSS, components, themes, navigation, layout, keys. Built on slice 10 (`http` serves, the plugins component
builds and ships) and 7c (scripts as plain data, `createPluginRuntime`). Rule "The app kit is malleable by plugins"
applies.

Read in full before writing code: this spec; `decisions.md` ("Slice 7c's points", "Slice 10's points"); `design.md`
§3 (clients), §21, §23; `slice-10.md`; p2's `src/hosts/web.ts`, `src/hosts/web/main.tsx`, `src/web/reconnect.ts`,
`src/runtime/web.tsx`, `src/hosts/bundles.ts`, `src/hosts/tui.ts`, `src/hosts/tui-client.ts`, `src/hosts/tui-sandbox.ts`,
`src/hosts/tui-bootstrap.mjs`, `src/hosts/tui-worker-runtime.ts`.

## 1. What p2 has, and what is generic

| p2 today | Generic (slice 11) | The app's |
| --- | --- | --- |
| one esbuild bundle per surface: runtime, React, every plugin's web facet, CSS; the page reloads whole when its version changes | a base bundle per surface (host packages and the surface runtime) plus each plugin's facet script (7c); new scripts applied in place | which host packages a surface has |
| the page: HTML with a CSP nonce, a theme script, `#root`, the bundle | serving the base bundle, scripts, stylesheets and assets through `http`; a page template hook | the HTML shell, CSP, theme, manifest and icons, service worker |
| `startWeb`: a client host, `keepConnected` (reconnect with backoff, wake on visibility and network change, stale-socket checks), protocol mismatch reloads the page | `startPage`: the client host, the connection loop, facet loading and reloading | `canRetry` policy (an ended share), connection UI |
| `runtime/web.tsx`: `HostContext`, `useObject`, `useReadable`, `useRegistry`, slots, sheets, navigation, owner state | optional React bindings: provider, hold, watch, registry, reference state | slots, sheets, navigation, everything else |
| TUI: a trusted launcher (auth, terminal, local preferences, actions), a permission-dropped worker thread running the downloaded bundle, a bridge terminal, a link through the launcher | a Node client surface: connect, download the base bundle and TUI scripts, evaluate, reload in place; the sandbox as an option | the TUI's components (pi-tui or other), commands, keys, themes, launcher actions |

## 2. `chord/next/web`

```ts
// server: serve the page surface (routes contributed to `Http`, so they reload like any contribution)
const web = webSurface(server, {
	kind: "web",
	plugins,                                              // the plugins component: the web kind's scripts
	hostPackages: { "@earendil-works/chord/next": "chord-next", react: "react", "react/jsx-runtime": "react-jsx", "@app/web": "app-web" },
	base: "./src/web/base.ts",                            // the surface runtime's entry: built once into the base bundle
	page: (request, ctx, assets) => appShell(assets),     // the app's HTML; `assets` lists the base script and stylesheets
}, ctx);

// browser: src/web/base.ts (in the base bundle)
startPage({
	principal: { kind: "server" },
	connect: () => new WebSocket(`${location.origin.replace(/^http/, "ws")}/chord`),
	hostPackages: { "@earendil-works/chord/next": chordNext, react: React, "react/jsx-runtime": jsx, "@app/web": appWeb },
	core: [{ name: "@app/web", facet: webCore }],
	canRetry: async () => !(await shareEnded()),
	onConnected: (connected) => setBanner(!connected),
});
```

> **Decision needed P1: scope.** What `chord/next/web` ships: serving the surface (base bundle, scripts, stylesheets,
> assets, the page through an app template), the browser's client host with its connection loop, loading the plugins'
> web facets and applying new ones. Not shipped: HTML, CSS, components, themes, navigation, slots. React bindings
> separately (P7).

> **Decision needed P2: how the browser evaluates a facet script.** (a) Each script served as a file wrapped to register
> itself (`chordFacet("<plugin>", "<digest>", function (require, module, exports) { … })`), loaded with `<script
> src>` under a `'self'` CSP; (b) fetched and run with `new Function` (needs `'unsafe-eval'`).

> **Decision needed P3: the base bundle.** Built on the server per surface by esbuild from the app's `base` entry with
> the host packages inlined once; content-hashed; a change of base code reloads the page (as a server restart).

> **Decision needed P4: applying new scripts in a page.** (a) In place: the page's host reloads per plugin
> (`host.reload`, 7b), the UI re-renders from its registries; (b) the whole page reloads, as p2.

> **Decision needed P5: how a page learns of new scripts.** (a) Through Chord: the surface serves a `remote` object
> whose state is the current set (digests and URLs); every page holds it and applies changes; the rollout step publishes
> without waiting for pages; (b) polling a manifest (p2: every 5 s while visible).

> **Decision needed P6: the connection loop.** p2's `keepConnected`: reconnect after a drop with backoff (250 ms to 5
> s), wake on visibility, `online` and network change, replace a stale socket, stop on an outdated protocol (reload) or
> when the app's `canRetry` says no. A network reconnect, not a crash restart.

> **Decision needed P7: React bindings.** An optional subpath (`chord/next/react`) with `HostProvider`,
> `useHold(Type, params)`, `useWatch(watchable)`, `useRegistry(R)`, `useRefState(ref)`; or none in Chord.

## 3. `chord/next/tui`

> **Decision needed P8: scope.** A Node client surface (`startTerminal`): connect over WebSocket with the app's
> credentials, download the base bundle and the TUI scripts from the server, evaluate them, run the app's entry with
> its host, apply new scripts in place; the terminal belongs to the app. p2's sandbox (a permission-dropped worker
> thread for code from another person's server, a bridge terminal, launcher actions) as an option or not in Chord.

> **Decision needed P9: pi-tui bindings.** None in Chord (the app chooses its TUI library), or optional helpers.

## 4. Not in slice 11

App shells, themes, navigation, slots, offline support (service workers), the TUI's commands and preferences;
telemetry (slice 9).

## 5. Tests, examples, cost

Filled once the points are decided. Examples: small and cute (owner), each a folder.
