# Mobile UI Kit

An extension can give itself a native-looking interface in the Pi-Bolt app: cards, lists, progress, buttons and forms that
look and behave like the rest of the app. The extension describes the interface as JSON; the app draws it with its own
components. In the terminal, the same description is shown as text, so one package works everywhere.

**Problem.** In the app, an extension can only show text lines (`setWidget`) and dialogs. A deploy extension that wants
a status card with a progress bar and a Retry button has nothing to build it with, and `ctx.ui.custom()` needs a
terminal.

**Solution.** A small declarative kit: a *view* is a JSON tree of components (no code, no HTML, no WebView). Pi
validates it, keeps its current state, sends it to the clients that render views and renders it as text for the
others. Taps and form submits come back to the extension as `view_event`s.

```typescript
export default function (pi: ExtensionAPI) {
  pi.registerCommand("deploy", {
    description: "Deploy to production",
    handler: async (_args, ctx) => {
      ctx.ui.setView("deploy", {
        v: 1,
        title: "Deploy",
        subtitle: "production",
        body: [
          { type: "chip", id: "state", label: "Running", tone: "running" },
          { type: "progress", id: "bar", value: 0, label: "Uploading" },
        ],
        actions: [{ type: "button", id: "cancel", label: "Cancel", style: "secondary" }],
      });
      for (let i = 1; i <= 10; i++) {
        await step();
        ctx.ui.updateView("deploy", [{ op: "set", id: "bar", props: { value: i / 10 } }]);
      }
      ctx.ui.updateView("deploy", [
        { op: "set", id: "state", props: { label: "Deployed", tone: "success" } },
        { op: "remove", id: "bar" },
      ]);
    },
  });

  pi.on("view_event", (event, ctx) => {
    if (event.key === "deploy" && event.id === "cancel") cancelDeploy();
  });
}
```

On the phone this is a card above the composer with a status chip, a progress line and a Cancel button. In the terminal
it is a widget:

```
Deploy · production
● Running
[######----] 60%  Uploading
[ Cancel ]
```

## How it works

```
extension ── ctx.ui.setView / updateView / showView ──▶ Pi (validates, keeps state, throttles)
                                                          ├─▶ terminal: text (widget, chat line, dialogs)
                                                          ├─▶ RPC client with "views": extension_ui_request setView / updateView / showView
                                                          └─▶ phones over remote control with "views": the same requests
extension ◀── pi.on("view_event") / showView result ──── client: extension_ui_event / extension_ui_response
```

- Pi is the source of truth. It applies every update to its stored copy, so a client that connects later (a phone over
  [remote control](remote-control.md), an app that restarts its screen) gets the current state.
- Rendering is the client's. The JSON says *what* (a card with a title, a danger button); the app decides *how*
  (its colours, type, spacing, themes, dark mode, TalkBack). Authors cannot set colours, fonts or sizes.
- The kit is optional. A client that does not declare it gets text (see [Fallback](#fallback)).

## Views

A view is an object:

```json
{
  "v": 1,
  "title": "Deploy",
  "subtitle": "production",
  "icon": "rocket",
  "body": [ { "type": "text", "text": "Ready to ship 3 commits." } ],
  "actions": [ { "type": "button", "id": "go", "label": "Deploy", "style": "primary" } ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `v` | `1` | Kit version the view needs. Required. |
| `title` | string | Header of a panel, sheet or page; the first line of the text form. Optional for inline views. |
| `subtitle` | string | One line under the title. |
| `icon` | [icon name](#icons) | Shown before the title. |
| `body` | node[] | The content, top to bottom. Required, may be empty. |
| `actions` | button[] | The view's buttons: a sheet's footer, a panel's or inline card's bottom row. At most 3. |
| `fallback` | string[] | Text lines used instead of the derived text form (terminal, clients without the kit). |

Every node has a `type`. Nodes that are updated or interactive need an `id` (`[A-Za-z0-9_.:-]`, 1 to 64 characters,
unique within the view). Any node may set `hidden: true`.

## Components

### Content

| `type` | Fields | App rendering |
|---|---|---|
| `text` | `text`, `style?`: `"body"` (default), `"title"`, `"meta"`, `"mono"` | Plain text; `title` is a heading. Never colourful. |
| `markdown` | `text` | The transcript's Markdown renderer: GFM, code, tables, math. Links open only `https:`, `http:` and `mailto:`; images are never fetched by themselves (a "Tap to load" card). |
| `code` | `text`, `language?`, `title?` | The transcript's code block: syntax colours, copy button, horizontal scroll. |
| `image` | `src`, `alt`, `decorative?`, `aspect?` (width / height, 0.25 to 4) | See [Images](#images). |
| `divider` | | A 1dp line. |
| `notice` | `tone`: `"info"`, `"success"`, `"warning"`, `"danger"`; `title?`, `text` | The app's Notice: a glyph in the tone's colour, text in ink. Announced politely. |

```json
{ "type": "notice", "tone": "warning", "title": "Migration pending", "text": "Run it before deploying." }
```

### Data

| `type` | Fields | App rendering |
|---|---|---|
| `kv` | `items`: `{ key, value, mono?, copy? }[]` (at most 100) | Value rows: the key in ink2, the value in ink1 at the end; `mono` for hashes and paths; `copy` adds a copy action. |
| `progress` | `value?` (0 to 1; absent: indeterminate), `label?`, `detail?` | Determinate: the 2dp progress line with the percentage. Indeterminate: the app's working line (a Quad spinner and the label). |
| `chip` | `label`, `tone?`: `"neutral"` (default), `"success"`, `"warning"`, `"danger"`, `"running"`; `icon?` | A small capsule. Each tone has its own glyph as well as its colour; `running` shows the live spinner. |
| `tile` | `label`, `value`, `icon?`, `tone?`, `action?` | A status tile, as on Home. Tappable with `action`. Put tiles in a `grid`. |

```json
{ "type": "kv", "items": [ { "key": "Branch", "value": "main" }, { "key": "Commit", "value": "1a2b3c4", "mono": true, "copy": true } ] }
```

### Layout

| `type` | Fields | App rendering |
|---|---|---|
| `card` | `title?`, `subtitle?`, `icon?`, `status?` (a chip), `children`, `actions?` (buttons) | The app's card: surface1, 1dp line, radius 10. |
| `section` | `title?`, `children` | A quiet section label over its children; no box. |
| `list` | `children` (`item`s only), `empty?` (text when it has none) | An inset group of rows with hairlines. |
| `item` | `title`, `subtitle?`, `icon?`, `value?`, `chip?`, `progress?` (0 to 1), `action?`, `chevron?` | A 56dp list row; the whole row is the target when it has `action`. |
| `row` | `children` (`chip`, `button`, `text` with style `meta`) | A horizontal row that wraps: chips, or small buttons side by side. |
| `grid` | `columns?`: 2 (default), 3 or 4; `children` (`tile`s) | Tiles in equal columns; one row of four on a wide window. |

```json
{
  "type": "list",
  "children": [
    { "type": "item", "id": "job-41", "title": "Build #41", "subtitle": "main · 2m ago", "chip": { "label": "Passed", "tone": "success" }, "action": "open" },
    { "type": "item", "id": "job-42", "title": "Build #42", "subtitle": "main · now", "chip": { "label": "Running", "tone": "running" }, "action": "open" }
  ]
}
```

### Actions and input

| `type` | Fields | App rendering |
|---|---|---|
| `button` | `label`, `style?`: `"primary"`, `"secondary"` (default), `"ghost"`, `"danger"`; `icon?`, `action?`, `disabled?`, `busy?`, `url?`, `confirm?` | The app's capsule buttons. `busy` shows a spinner and blocks taps. `confirm: { title, message?, label? }` asks first (a confirm sheet). `url` opens a link instead of sending an event. |
| `form` | `children` (fields, `text`, `notice`, `divider`), `submit`: `{ label, action? }`, `disabled?`, `busy?` | The fields, then the submit button. |
| `input` | `label`, `value?`, `placeholder?`, `hint?`, `error?`, `kind?`: `"text"` (default), `"multiline"`, `"number"`, `"email"`, `"url"`, `"password"`; `required?`, `maxLength?` (default 2000, at most 10000) | The app's text field, the label above, the hint or error below. `password` is obscured and never kept in saved state. |
| `toggle` | `label`, `value`, `hint?` | A switch row. |
| `select` | `label`, `options`: `string[]` or `{ value, label }[]` (at most 100), `value?` | Up to 3 options: a segmented control; more: a row that opens a choice sheet. |
| `slider` | `label`, `min`, `max`, `step?`, `value`, `unit?` | A slider with the value read out. |

`input`, `toggle`, `select` and `slider` are *fields*. A field inside a `form` sends nothing until the form is
submitted. A field outside a form sends a `change` event each time its value is committed (a toggle flipped, an option
picked, a slider released, a text field's Done).

```json
{
  "type": "form",
  "id": "settings",
  "children": [
    { "type": "input", "id": "branch", "label": "Branch", "value": "main", "required": true },
    { "type": "toggle", "id": "migrate", "label": "Run migrations", "value": true },
    { "type": "select", "id": "region", "label": "Region", "options": ["eu", "us", "ap"], "value": "eu" }
  ],
  "submit": { "label": "Save", "action": "save" }
}
```

### Icons

Icons are names, never images: `add`, `alert`, `archive`, `bell`, `bolt`, `book`, `branch`, `bug`, `build`, `calendar`,
`chart`, `check`, `clock`, `close`, `cloud`, `code`, `copy`, `database`, `delete`, `download`, `edit`, `error`, `file`,
`filter`, `flag`, `folder`, `globe`, `heart`, `home`, `info`, `key`, `link`, `lock`, `mail`, `package`, `pause`,
`person`, `play`, `refresh`, `rocket`, `search`, `send`, `server`, `settings`, `shield`, `star`, `stop`, `sync`,
`terminal`, `upload`, `warning`. The app maps them to its Material Rounded glyphs. A client that lacks one leaves it out.

## Slots

Where a view appears is its slot, chosen when it is set:

| Slot | API | App | Terminal |
|---|---|---|---|
| `panel` (default) | `setView(key, view)` | A card above the composer. Its header (icon, title, the first chip) folds and unfolds it; several panels stack and the area scrolls. | A widget above the editor. |
| `inline` | `setView(key, view, { slot: "inline" })` | A card in the transcript, pinned where it was first set (below). It stays live; it is not saved in the session. | Lines in the chat, updated in place. |
| `page` | `setView(key, view, { slot: "page" })` | A tall sheet, opened from the session's menu (•••), one entry per page, named by its title. | Not shown. |
| `sheet` | `await showView(view)` | A modal bottom sheet with the title, the body and `actions` as its button row. | Dialogs, see [Fallback](#fallback). |

Inline views are live UI, not transcript content. To keep a record in the session, send a message as well (for example
`pi.sendMessage` with the final state).

An inline view is pinned when it is first set, so it keeps its place after a reconnect or a rebuilt transcript:

- with `{ toolCallId }` (the id a tool's `execute` receives): under that tool call's row;
- set while a turn is at a tool call (from a tool, say): under that call's row;
- otherwise: at the end of the latest turn (an extension command run between turns lands there).

A folded turn shows its views at its end. A view whose turn is not in the transcript (an older part a reconnect left
out) is shown at the end.

## Updates

`ctx.ui.setView(key, view)` shows or replaces the view `key`; `setView(key, undefined)` removes it.
`ctx.ui.updateView(key, ops)` changes it in place:

| Op | Fields | Effect |
|---|---|---|
| `set` | `id`, `props` | Merges `props` into the node `id`. `type` and `id` cannot change; a list (`children`, `items`, `options`, `actions`) is replaced whole. A prop set to `null` is removed. |
| `replace` | `id`, `node` | Puts `node` in place of the node `id`. |
| `remove` | `id` | Removes the node `id`. |
| `insert` | `parent`, `node`, `index?` | Inserts `node` into the children of the node `parent` (or into `body` when `parent` is omitted), at `index` (default: the end). |

```typescript
ctx.ui.updateView("ci", [
  { op: "set", id: "job-42", props: { chip: { label: "Failed", tone: "danger" } } },
  { op: "insert", parent: "jobs", index: 0, node: { type: "item", id: "job-43", title: "Build #43" } },
]);
```

- An update is atomic: Pi applies all ops to its copy and validates the result. If any op fails (an unknown id, a
  duplicate id, a limit), the call throws and nothing changes or is sent.
- Each change raises the view's `rev` by one. Clients apply an `updateView` only on top of the `rev` before it; a
  client that has another rev waits for the next full `setView`.
- Pi sends at most 10 changes per second per view. The first change goes at once; changes within the next 100 ms are
  merged and sent as one full `setView` at the end of that window. A progress loop can call `updateView` as often as it
  likes.
- Field values the user commits (`change` and `submit` events) are written into Pi's copy too, so a client that
  connects later shows them. A `set` of a field's `value` from the extension wins over what the user typed.
- Views belong to the session. Switching or reloading the session removes them.

## Events

A tap, a committed field value or a form submit reaches every extension as a `view_event`:

```typescript
pi.on("view_event", async (event, ctx) => {
  if (event.key !== "ci") return;
  if (event.action === "open") await showJob(event.id, ctx);
  if (event.kind === "submit" && event.id === "settings") await save(event.values);
});
```

| Field | Meaning |
|---|---|
| `key` | The view's key. |
| `id` | The node's `id`. |
| `action` | The node's `action`, or its `id` when it has none. Rows that share an action ("open") differ by `id`. |
| `kind` | `"tap"` (button, item, tile), `"change"` (a field outside a form), `"submit"` (a form). |
| `value` | The field's new value (`change`): string, boolean or number. |
| `values` | The form's field values by field `id` (`submit`). |

View keys are shared by all extensions, like widget keys: prefix them with the package name (`"ci-board:jobs"`).
Pi checks every event before it is emitted: the view and node exist, the node takes that kind of event and is not
disabled or busy, and the values fit their fields (a `select` value is one of its options, a number is in range, a
text is within `maxLength`, required fields are filled). Anything else is dropped. A button with `url` sends no event.

### Sheets

`showView` opens a modal sheet and resolves when it closes:

```typescript
const result = await ctx.ui.showView({
  v: 1,
  title: "Deploy settings",
  body: [
    { type: "form", id: "f", children: [
      { type: "input", id: "branch", label: "Branch", value: "main", required: true },
      { type: "toggle", id: "migrate", label: "Run migrations", value: false },
    ], submit: { label: "Deploy", action: "deploy" } },
  ],
  actions: [{ type: "button", id: "cancel", label: "Cancel" }],
}, { timeout: 120_000 });

if (result?.action === "deploy") deploy(result.values.branch as string, result.values.migrate === true);
```

- A form submit or a button in `actions` closes the sheet; the promise resolves with `{ id, action, values }`, where
  `values` holds every field of the sheet.
- Dismissing it (swipe, Back, the close button), the `timeout` or the `signal` resolves `undefined`.
- Buttons and items in the body send `view_event`s and keep the sheet open. Give the sheet a `key`
  (`showView(view, { key })`) to update it while it is open with `updateView(key, ops)`.
- With remote control, the sheet opens in the terminal (as dialogs) and on the phones; the first answer wins and the
  others close, as for [dialogs](remote-control.md#messages).

## Fallback

Every client shows something. Pi derives a text form from the view unless the view gives `fallback` lines:

| Node | Text |
|---|---|
| title, subtitle | `Deploy · production` |
| `text`, `markdown`, `code` | their text (code indented by two spaces) |
| `kv` | `Branch  main`, keys padded to one width |
| `progress` | `[######----] 60%  Uploading`, or `… Uploading` when indeterminate |
| `chip` | `● Running` (`✓` success, `!` warning, `✗` danger, `●` running, `·` neutral) |
| `item` | `• Build #42 — main · now  Running` |
| `tile` | `Builds: 42` |
| `notice` | `! Migration pending: Run it before deploying.` |
| `button`, `actions` | `[ Retry ]  [ Logs ]` |
| fields | `Branch: main`, `Run migrations: on`, `Region: eu` |

Where it goes:

- **Terminal.** `panel` views are widgets above the editor and `inline` views are lines in the chat, both updated in
  place; `page` views are not shown. Buttons cannot be pressed there: offer the same actions as commands. A sheet becomes
  a sequence of dialogs: each field (`input` for text and numbers, `select` for options and toggles), then a `select`
  of the actions when there is more than one (with a `confirm` for a button that has `confirm`). Cancelling any dialog
  resolves `undefined`.
- **RPC clients without the kit** (and ACP, MCP): the same widgets (`setWidget` with the key `view:<key>`) and the same
  dialogs.
- **Phones with remote control but without the kit** see nothing of `panel`, `inline` and `page` views, as with
  widgets; sheets become dialogs when no connected phone renders views and no terminal is attached.

## Capability negotiation

A client turns the kit on with one command:

```json
{"id":"f1","type":"set_client_features","features":["views"]}
{"id":"f1","type":"response","command":"set_client_features","success":true,"data":{"features":["views"]}}
```

`data.features` lists what Pi accepted. In RPC mode the command switches the whole connection: Pi removes the text
widgets of current views and sends a `setView` for each of them, so a client can send it any time (again after it
rebuilt its screen). Over remote control it applies to that phone's connection only, and `remote_hello.features` contains
`"views"` when the host supports it. Clients that never send it keep getting text.

Extensions do not need to check anything: `setView` works in every mode. To support Pi versions before the kit, guard
on the method:

```typescript
if (ctx.ui.setView) ctx.ui.setView("deploy", view);
else ctx.ui.setWidget("deploy", ["Deploy: running"]);
```

## Protocol

All requests from Pi are `extension_ui_request`s, as in [RPC Extension UI](rpc-extension-ui.md).

| `method` | Fields | Response |
|---|---|---|
| `setView` | `viewKey`, `slot`, `view` (absent: remove), `rev`; inline views: `toolCallId?` or `turn?` (the `timestamp` of the turn's user message) | none |
| `updateView` | `viewKey`, `ops`, `rev` (after the ops) | none |
| `showView` | `viewKey`, `view`, `timeout?` | `extension_ui_response` with `nodeId` and `values`, or `cancelled: true` |

```json
{"type":"extension_ui_request","id":"u1","method":"setView","viewKey":"deploy","slot":"panel","rev":1,"view":{"v":1,"title":"Deploy","body":[]}}
{"type":"extension_ui_request","id":"u2","method":"updateView","viewKey":"deploy","rev":2,"ops":[{"op":"set","id":"bar","props":{"value":0.5}}]}
{"type":"extension_ui_request","id":"u3","method":"showView","viewKey":"sheet:4f1c","view":{"v":1,"title":"Deploy settings","body":[]}}
```

From the client:

```json
{"type":"extension_ui_event","viewKey":"deploy","nodeId":"cancel","kind":"tap"}
{"type":"extension_ui_event","viewKey":"ci","nodeId":"notify","kind":"change","value":true}
{"type":"extension_ui_event","viewKey":"ci","nodeId":"settings","kind":"submit","values":{"branch":"main"}}
{"type":"extension_ui_response","id":"u3","nodeId":"f","values":{"branch":"main","migrate":false}}
```

`nodeId` of a sheet answer is the submitted form or the pressed `actions` button. Events get no response.

Over remote control the same records travel in the encrypted channel. `remote_hello` carries `views`
(`[{ viewKey, slot, view, rev, toolCallId?, turn? }]`, the current state) and open sheets among its `dialogs`.

## Limits

| Limit | Value |
|---|---|
| View size (JSON, UTF-8, images included) | 256 KiB |
| One image's `data:` URI | 192 KiB |
| Nodes per view | 400 |
| Nesting depth (body is 1) | 8 |
| Children of one node, `kv` items, `select` options | 200, 100, 100 |
| `title`, `label`, `subtitle`, `key`, `value` of `kv` | 200 characters |
| `text`, `hint`, notice text | 4,000 characters |
| `markdown` | 32,000 characters |
| `code` | 64,000 characters |
| Views per session | 16 |
| Changes sent per view | 10 per second (merged) |
| Events accepted per view | 20 per second (the rest dropped) |

A view over a limit is refused with an error that names the node (`body[2].children[0]: label: longer than 200`).

## Security

- **No code.** A view is data. The app has no script engine, WebView or HTML for views; `markdown` goes through the
  transcript's renderer, which drops HTML other than a few formatting tags.
- **Links.** `url` on buttons and Markdown links open only `https:`, `http:` and `mailto:` (the app's link rules); any
  other scheme is refused by Pi and ignored by the app.
- **Images.** `data:image/png`, `jpeg`, `webp` or `gif` (first frame) inline. `https:` images on public host names (no
  IP addresses, `localhost` or LAN names) are never fetched by themselves: the app shows a "Tap to load" card, as for
  Markdown images, and loads at most 12 MB, decoded to at most 2048 px. Other sources are refused.
- **Text is text.** Control characters and terminal escape sequences are removed before the terminal shows a view.
- **Input.** Events are checked against the view before an extension sees them (see [Events](#events)); a client
  cannot invoke an action the view does not show. `password` fields are not stored in the app's saved state, are never
  written into Pi's copy of the view, and are not logged.
- **Trust.** Views come from extensions, which already run with your permissions; the kit gives them no new power on
  the computer. Over remote control nothing changes: the same paired, encrypted connection carries them.

## Accessibility

The kit requires what the app needs to be accessible, and Pi refuses views without it:

- Every button, field and tappable item or tile has a text label; icon-only controls do not exist. An `image` needs
  `alt` unless it is `decorative: true`.
- Tones are never the only signal: chips, tiles and notices carry a label and a glyph per tone.

The app then provides: titles, card and section titles as headings; 48dp touch targets; text that follows the system
font scale and wraps instead of clipping; list rows read as one item ("Build #42, main · now, Running, button"); progress
announced politely when it moves by 10% or its label changes; notices and field errors as polite live regions;
Reduce motion respected by spinners and folds; every theme (light, dark, high contrast) and accent.

## Versioning

`v` is the kit version a view needs; this is version 1. Within a version, changes are additive: new optional fields and
new node types. A client renders a node type it does not know as its text form (its `title`, `label` or `text`) and
ignores fields it does not know, so an older app shows a newer view in a reduced form. Pi validates against the version
it implements and refuses unknown types, which catches typos while writing. A breaking change would be version 2, with
its own feature name (`"views2"`), and Pi would keep serving version 1 to clients that only declare `"views"`.

## Extension API

```typescript
interface ExtensionUIContext {
  /** Shows, replaces (same key) or removes (undefined) a view. Throws when the view is invalid. */
  setView(key: string, view: MobileView | undefined, options?: { slot?: "panel" | "inline" | "page"; toolCallId?: string }): void;
  /** Applies ops to the view in place, atomically. Throws when an op fails. */
  updateView(key: string, ops: MobileViewOp[]): void;
  /** Opens a modal sheet; resolves with the submitted form or pressed action, or undefined. */
  showView(view: MobileView, options?: { key?: string; timeout?: number; signal?: AbortSignal }): Promise<MobileViewResult | undefined>;
  /** The view as Pi holds it now (with the user's committed values), or undefined. */
  getView(key: string): MobileView | undefined;
}
```

The types (`MobileView`, `MobileViewNode`, `MobileViewOp`, `MobileViewResult`, `ViewEvent`) are exported from
`@earendil-works/pi-coding-agent`. The JSON Schema is [`schemas/mobile-ui.schema.json`](../schemas/mobile-ui.schema.json).

## Check and preview

```bash
pi-bolt mobile-ui check view.json          # validate: errors with paths, then the text form
pi-bolt mobile-ui preview view.json        # write an HTML preview in the app's style and print its path
```

The file holds one view, or `{ "views": [...] }`. The preview draws the components with the app's default theme,
light and dark, at phone width, so a layout can be checked without a phone. It is an approximation; the app is the
reference.

The `pibolt-mobile-ui` skill teaches Pi the kit: ask Pi to "add a mobile UI to this package" and it reads the skill,
writes the views and checks them with `pi-bolt mobile-ui check`.

## Design rules

The app's design language applies to every view (Pi-Bolt's `android/DESIGN.md`). For authors this means:

- **One primary action.** At most one `primary` button per view; others `secondary` or `ghost`; `danger` only for the
  step that destroys, with `confirm`.
- **State is colour, nothing else is.** Use tones for state (passed, failed, running), never for decoration.
- **Short labels.** Sentence case, verbs on buttons ("Deploy", not "Click here to deploy"), no trailing punctuation.
- **Panels are glanceable.** A panel should fit in about 3 rows folded open: a chip, a progress line, one or two
  buttons. Put detail in a `page` or a sheet.
- **Lists over cards.** Many similar things are `item`s in one `list`, not a card each.
- **Prefer updates to replacements.** `updateView` with ids keeps the user's scroll position and field edits.
