---
name: pibolt-mobile-ui
description: Give a Pi package or extension a native-looking UI in the Pi-Bolt mobile app (cards, lists, progress, buttons, forms) with the mobile UI kit, keeping a text fallback for the terminal. Use when asked to add a mobile or app UI, a panel, a status card or a settings sheet to an extension.
---

# Pi-Bolt mobile UI

An extension describes its UI as JSON *views*; the Pi-Bolt app draws them with its own components, and the terminal
shows them as text. You write data, never code, HTML or colours. The full reference is `docs/mobile-ui.md` in Pi's
documentation (the "Additional docs" path in your instructions); read it when something here is not enough.

## Workflow

1. Read the extension. Find what a user would want to *see* (state, progress, results) and *do* (actions, settings).
2. Pick slots (below). One panel per long-running thing; a sheet for a short form; inline for a tool's live result.
3. Write the views as constants or small builder functions next to the code they describe. Give every node you update
   or that sends events a stable `id`; prefix view keys with the package name (`"ci-board:jobs"`).
4. Wire updates with `ctx.ui.updateView` and actions with `pi.on("view_event", ...)`.
5. Keep the terminal working: every button's action should also be reachable as a command or the model's tool.
6. Check every view: write it to a JSON file (or `{ "views": [...] }`) and run `pi-bolt mobile-ui check <file>`; fix
   every error. `pi-bolt mobile-ui preview <file>` writes an HTML preview in the app's look.
7. Guard for Pi without the kit when the package may run on older Pi: `if (ctx.ui.setView) ... else ctx.ui.setWidget(...)`.

## API

```typescript
ctx.ui.setView(key, view, { slot: "panel" | "inline" | "page", toolCallId? });  // show or replace; undefined removes
ctx.ui.updateView(key, [{ op: "set", id, props }, { op: "insert", parent?, index?, node }, { op: "replace", id, node }, { op: "remove", id }]);
const result = await ctx.ui.showView(view, { key?, timeout?, signal? });  // sheet: { id, action, values } or undefined
ctx.ui.getView(key);                                                       // current state, with the user's values
pi.on("view_event", (event, ctx) => { event.key; event.id; event.action; event.kind; event.value; event.values; });
```

`setView` and `updateView` throw on an invalid view or op: Pi validates everything. Updates are cheap (Pi merges them
to 10 per second), so update progress as often as it changes.

## Views

```json
{ "v": 1, "title": "Deploy", "subtitle": "production", "icon": "rocket", "body": [ ...nodes ], "actions": [ ...buttons ] }
```

| Node | Key fields |
|---|---|
| `text` | `text`, `style`: body, title, meta, mono |
| `markdown` | `text` (links https/http/mailto only) |
| `code` | `text`, `language`, `title` |
| `notice` | `tone`: info, success, warning, danger; `title`, `text` |
| `kv` | `items: [{ key, value, mono?, copy? }]` |
| `progress` | `value` 0..1 (omit for indeterminate), `label`, `detail` |
| `chip` | `label`, `tone`: neutral, success, warning, danger, running; `icon` |
| `card` | `title`, `subtitle`, `icon`, `status` (chip fields), `children`, `actions` |
| `section` | `title`, `children` |
| `list` / `item` | list `children` are items: `title`, `subtitle`, `icon`, `value`, `chip`, `progress`, `action`, `chevron`; list `empty` |
| `row` | `children`: chips, buttons, meta text, side by side |
| `grid` / `tile` | grid `columns` 2-4, tiles: `label`, `value`, `icon`, `tone`, `action` |
| `button` | `label`, `style`: primary, secondary, ghost, danger; `icon`, `action`, `disabled`, `busy`, `url`, `confirm: { title, message?, label? }` |
| `form` | `children` (fields, text, notice, divider), `submit: { label, action? }`, `busy` |
| `input` | `label`, `value`, `placeholder`, `hint`, `error`, `kind`: text, multiline, number, email, url, password; `required`, `maxLength` |
| `toggle` | `label`, `value` |
| `select` | `label`, `options`: strings or `{ value, label }`, `value` |
| `slider` | `label`, `min`, `max`, `step`, `value`, `unit` |
| `image` | `src` (data:image/... or https on a public host), `alt` or `decorative: true` |
| `divider` | |

Icons: add, alert, archive, bell, bolt, book, branch, bug, build, calendar, chart, check, clock, close, cloud, code,
copy, database, delete, download, edit, error, file, filter, flag, folder, globe, heart, home, info, key, link, lock,
mail, package, pause, person, play, refresh, rocket, search, send, server, settings, shield, star, stop, sync, terminal,
upload, warning.

Rules Pi enforces: `id` on every button (unless it has `url`), tappable item or tile, field and form; ids unique in a
view; labels on every control; `alt` on images; at most 3 `actions`; 256 KiB per view.

## Slots and events

- `panel` (default): a card above the phone's composer; a widget in the terminal. Keep it glanceable: a chip, a
  progress line, one or two buttons.
- `inline`: a live card in the transcript where it was set (under the tool call with `toolCallId`). Not saved in the
  session; send a message too if the result should stay.
- `page`: a full screen from the session's Packages list. Detail, settings, history. Not shown in the terminal.
- sheet (`showView`): modal. A form submit or an `actions` button closes it and resolves; dismiss resolves `undefined`.
  In the terminal it runs as dialogs.

Events: `tap` (button, item and tile with `action`), `change` (a field outside a form, once committed), `submit` (a
form, with `values`). `event.action` is the node's `action`, else its `id`; rows sharing `action: "open"` differ by
`id`. Pi drops events that do not fit the view (disabled, busy, wrong values), so handlers can trust them.

## Design rules

- One `primary` button per view; `danger` only for the destructive step, with `confirm`.
- Tones mean state (passed, failed, running), never decoration. Colour, fonts and sizes are the app's.
- Short sentence-case labels, verbs on buttons ("Deploy", "Retry"), no trailing punctuation.
- Many similar things: `item`s in one `list`, not a card each. Numbers at a glance: `tile`s in a `grid`.
- While work runs: a `running` chip or an indeterminate `progress`; set `busy: true` on the button or form that
  started it, and clear it when done.
- Update by `id` instead of replacing the view, so the user's scroll position and typing stay.
- Errors: a `danger` notice with what to do, or `error` on the field.

## Example

```typescript
import type { ExtensionAPI, ExtensionContext, MobileView } from "@earendil-works/pi-coding-agent";

const KEY = "deploy:status";

function statusView(state: "running" | "done", progress: number): MobileView {
  return {
    v: 1,
    title: "Deploy",
    subtitle: "production",
    icon: "rocket",
    body: [
      { type: "chip", id: "state", label: state === "running" ? "Running" : "Deployed", tone: state === "running" ? "running" : "success" },
      { type: "progress", id: "bar", value: progress, label: "Uploading" },
    ],
    actions: [{ type: "button", id: "cancel", label: "Cancel", disabled: state !== "running" }],
  };
}

export default function (pi: ExtensionAPI) {
  pi.registerCommand("deploy", {
    description: "Deploy to production",
    handler: async (_args, ctx) => {
      ctx.ui.setView(KEY, statusView("running", 0));
      for (let i = 1; i <= 10; i++) {
        await new Promise((r) => setTimeout(r, 500));
        ctx.ui.updateView(KEY, [{ op: "set", id: "bar", props: { value: i / 10 } }]);
      }
      ctx.ui.setView(KEY, statusView("done", 1));
    },
  });
  pi.registerCommand("deploy-cancel", { description: "Cancel the deploy", handler: async (_a, ctx) => cancel(ctx) });
  pi.on("view_event", (event, ctx) => {
    if (event.key === KEY && event.action === "cancel") cancel(ctx);
  });
}

function cancel(ctx: ExtensionContext) {
  ctx.ui.updateView(KEY, [{ op: "set", id: "state", props: { label: "Cancelled", tone: "danger" } }]);
}
```

More views to start from: `examples/` next to this file (`pi-bolt mobile-ui check examples/ci-board.json`). A full
package: `examples/extensions/mobile-ui-board/` in Pi's examples.
