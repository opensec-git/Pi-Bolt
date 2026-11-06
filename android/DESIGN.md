# Pi-Bolt Android: design language

Dark only. Ink-first hierarchy: text in four grey levels does the work, colour is reserved for state (success,
warning, danger, diff), and one accent marks what you can act on. Motion is quiet and purposeful: only the live turn
animates, history never does. Everything that moves respects "Reduce motion".

## Colour

One `PiColors` set per theme, provided through `LocalPiColors` and mapped onto Material 3 `darkColorScheme`
(`surfaceTint = Transparent`, tonal elevation 0) so stock components match. Elevation = a surface step plus a 1dp
line; no shadows.

| Role | Graphite (default) | Void (AMOLED) | Ember (warm) | Claude | Codex | Vercel | GitHub |
|---|---|---|---|---|---|---|---|
| canvas | `#0B0C0E` | `#000000` | `#15130F` | `#1F1E1D` | `#0D0D0D` | `#000000` | `#0D1117` |
| surface1 (cards, composer) | `#121417` | `#08090A` | `#1C1A16` | `#262624` | `#171717` | `#0A0A0A` | `#151B23` |
| surface2 (bubbles, sheets) | `#191C20` | `#101113` | `#24211C` | `#30302E` | `#212121` | `#111111` | `#1C2128` |
| surface3 (pressed, menus) | `#21252A` | `#17191C` | `#2C2822` | `#3A3936` | `#2A2A2A` | `#1A1A1A` | `#262C36` |
| ink1 (text) | `#E8EAED` | `#E8EAED` | `#EFE9DD` | `#F5F4EE` | `#ECECEC` | `#EDEDED` | `#F0F6FC` |
| ink2 (secondary) | `#A3A8B0` | `#A3A8B0` | `#B9B1A3` | `#C3C0B6` | `#B4B4B4` | `#A1A1A1` | `#B7BDC8` |
| ink3 (meta, lowest text) | `#7D838C` | `#7D838C` | `#8E877B` | `#9C9A92` | `#8F8F8F` | `#8F8F8F` | `#9198A1` |
| ink4 (rules, never text) | `#4C5158` | `#4C5158` | `#5A554D` | `#5E5D59` | `#545454` | `#454545` | `#3D444D` |
| line / lineStrong | ink1 @ 9% / 16% | ink1 @ 11% / 19% | ink1 @ 10% / 17% | ink1 @ 10% / 17% | ink1 @ 10% / 17% | ink1 @ 12% / 20% | ink1 @ 10% / 18% |
| scrim | black @ 56% | black @ 70% | black @ 56% | black @ 60% | black @ 60% | black @ 70% | black @ 60% |

Claude, Codex, Vercel and GitHub are palettes in the spirit of those products' dark modes, not their assets.

Overlays: press = ink1 @ 7%, selected = ink1 @ 11%, focus line = ink1 @ 40%.

Accent (independent of theme): **Theme** (default: the theme's own accent, Claude coral `#D97757` / `#1F1E1D`,
GitHub blue `#4493F8` / `#0D1117`, ink1 / canvas for the others), **Ink** (accent = ink1, onAccent = canvas),
**Iris** `#A99CFF` / `#120F2A`, **Glacier** `#7CC4F0` / `#06202E`. Accent marks the primary button, send button, focus ring, selection, links and
the working hairline. Never status.

Brand `#FFD84D` (voltage yellow): only on the app mark (launcher, splash, empty state).

Status (all themes): success `#5FD39A`, warning `#E8B04B` (needs you, steer), danger `#F2706B`; soft fills @ 14%.
Diff: add bg success @ 11% / fg `#9BE6BF`, delete bg danger @ 11% / fg `#F7A9A5`; changed words in paired lines
success / danger @ 22%. Add and delete never differ by hue alone: a `+` / `−` sign column, a solid 3dp gutter bar
(success @ 60%) for additions and a hatched one (2dp stripes every 4dp, danger @ 70%) for deletions. Context rows
ink2, line numbers ink3, hunk rows monoMeta ink3 on surface2.
Syntax: base ink1, keywords ink1 SemiBold, calls ink1 Medium, strings `#9ED3C6`, numbers `#E5A97E`, types
`#C9B8F0` (capitalised names and built-ins such as `string`), comments ink3 italic, punctuation and operators ink2,
the shell `$` prompt success SemiBold, JSON keys ink1. Plain-text fences (`text`, `markdown`, `log`) stay plain.

Every text token passes WCAG AA (4.5:1) on canvas and surface2 in all seven themes; ink4 is decorative only.

## Type

Inter (UI, prose) and JetBrains Mono (code, paths, terminal), both OFL, bundled as static weights in `res/font`
(no downloadable fonts: the app must work without Google Play services). Mono has ligatures off
(`"liga" 0, "calt" 0`). Ticking numbers use `"tnum"`.

| Token | sp / line | Weight | Use |
|---|---|---|---|
| display | 26 / 32 | 600 | empty-state title |
| title | 20 / 26 | 600 | screen and sheet titles |
| headline | 19 / 26 | 600 | prose h1 |
| subhead | 17 / 24 | 600 | prose h2/h3, top bar title |
| prose | 16 / 25 | 400 | assistant text, user bubbles |
| body | 15 / 22 | 400 | rows, composer, options |
| label | 14 / 20 | 500 | tool verbs, buttons, chips |
| meta | 12.5 / 17 | 400 | timestamps, subtitles, section heads (500) |
| micro | 11 / 14 | 500 | badges, exit codes |
| mono | 13.5 / 20 | 400 | terminal, viewer text |
| monoSmall | 12.5 / 18 | 400 | diffs, tool cards; code blocks use 12.5 / 19 |
| thinking | 14.5 / 22 | 400 ink3 | reasoning text |

System font scale is respected; the transcript is capped at 1.3x, and the Text size setting (-1 … +2 steps) applies
on top.

## Space, shape, size

Spacing 2, 4, 8, 12, 16, 20, 24, 32, 40 dp. Side gutter 16; gap between turns 20; between steps 6.
Radii: xs 4 (inline code, tags), sm 8 (chips), md 12 (cards, code blocks, option rows), lg 18 (user bubble,
composer), xl 28 (sheet top corners), full (send button, pills). User bubble: 18 with the bottom-end corner 6.
Touch targets at least 48dp. Every timeline row has a 20dp leading slot (spinner or glyph) and a 12dp gap.
Strokes: 1dp lines, 1.5dp focus ring, 2dp queue bars, 3dp diff gutter.

## The Quad spinner

A Canvas-drawn 3x3 grid that shows what the agent is doing. Grid spans 11 units (`u = size / 11`): cell 3u,
gap 1u, corner radius 0.7u. Floor alpha 0.16 for outer cells, 0.34 for the centre, so the shape always reads.
Sizes: 12dp inline, 16dp status line and top bar, 20dp list slot, 44dp connecting and empty-state hero.

One shared clock per window (`withFrameNanos`, quantised to 50 ms = 20 fps), read only in the draw phase so ticks
invalidate draw, not composition. The clock pauses when the app is not started or no spinner is attached; at most 8
spinners animate in a list. Reduce motion draws each mode's rest frame.

Indices: `i = r*3 + c`, centre 4, ring `[0,1,2,5,8,7,6,3]`, snake `[0,1,2,5,4,3,6,7,8]`.
`bump(x)`: keyframes 0:0, 0.3:1, 0.7:0, 1:0 (FastOutSlowIn per segment).

| Mode | When | Period | Motion | Rest frame |
|---|---|---|---|---|
| Think | reasoning, waiting for the first token | 1600 ms | diagonal ripple: delay `(r+c)/4*0.55`, alpha `lerp(floor,1,bump(frac(p-d)))`; centre ≥ 0.5 | centre and cells 0, 8 lit |
| Run | a tool is running | 1100 ms | column beam ping-pong: `x = 2*tri(p)`, alpha `1-|c-x|/1.15`; lit cells scale 1, others 0.86 | middle column lit |
| Write | text or tool arguments streaming | 1800 ms | fill along the snake, one cell per 110 ms; hold to 1350; fade to floor by 1600 | first 5 snake cells |
| Agent | a subagent runs (subagent tint) | 1200 ms | head steps round the ring every 150 ms, trail 0.62 / 0.36; centre pulses 0.34↔0.7 over 2400 ms | cells 0, 1, 2 |
| Wait | a dialog needs the user (warning) | 2000 ms | all breathe 0.35↔1.0, centre leads by 140 ms, scale 0.92↔1 | all at 0.8 |
| Compact | compaction | 1500 ms | outer cells move 0.8u toward the centre, scale 1→0.55, back; centre grows 1→1.18 | half contracted |
| Connect | connecting, reconnecting | 1600 ms | corners light clockwise, 400 ms each; edges at floor; centre 0.12 (hollow) | top-left corner |

Mode priority: open dialog → Wait; compacting → Compact; subagent → Agent; tool running → Run; streaming text or
tool args → Write; not connected → Connect; else Think. List rows debounce mode changes by 600 ms; the live status
line switches at once. A finished step crossfades its spinner into a check (ink3) or cross (danger) over 160 ms,
scale 0.7→1, live turn only.

The app mark is built from Quad cells in brand yellow (new mark, not the desktop's bolt pattern): cells enter in a
diagonal stagger (30 ms per diagonal, 280 ms fade + scale 0.6→1), then one Think ripple.

## Motion

| Token | ms | Use |
|---|---|---|
| tap | 90 | press overlay, press scale |
| quick | 150 | chevrons, chip select, icon swaps, crossfades |
| standard | 220 | expand/collapse, message rise, menus |
| sheet | 300 | sheets, viewers, screen transitions |
| exit | 0.7 × enter | anything leaving |
| settle | 400 | meters, counters |

Easing: enter `cubic(0.05, 0.7, 0.1, 1)`, exit `cubic(0.3, 0, 0.8, 0.15)`, in place `cubic(0.2, 0, 0, 1)`.
Springs for drags and settles: damping 0.8 / stiffness 380 (default), 0.6 / 800 (fast), 1.0 / 1600 (colours,
alpha). No overshoot except sheet drag release.

- Screens: shared-axis X (24dp slide + fade, sheet duration); back reverses; predictive back previews the pop
  (`popExitTransition` scales to 0.9 with the gesture). Host card → session uses a shared-bounds transition, with the
  host name and status dot as shared elements.
- Live turn only: the user bubble rises 10dp and fades in; new steps rise 6dp. History and restored sessions never
  animate. Streaming text is updated at most once per frame and newly revealed text fades in over 120 ms; a 6x12dp
  ink3 block pulses at the end of the live paragraph (900 ms) instead of a caret.
- Auto-scroll sticks to the bottom only while the user is there; otherwise a 40dp round scroll button (surface3, 1dp
  lineStrong, arrow 20dp ink1, 48dp touch) appears at the end edge, 16dp in from the side and bottom, out of the
  reading column. Enter: rise 16dp, scale 0.95→1, fade, quick; exit 0.7x. Unseen content adds an 8dp accent dot at
  its top-end that pulses twice (scale 1↔1.3, 300 ms each way). Every send jumps to the bottom. The live status line
  keeps its 32dp height while hidden so the list does not jump.
- Labels such as "Thinking", "Running", "Writing" shimmer: ink2 base, a band of ink1 through 9 eased alpha stops per
  side (0, .04, .17, .33, .5, .67, .83, .96, 1), band max(96dp, 0.6 × label width), a constant 200dp/s with a 100 ms
  pause between sweeps; static under reduce motion.
- A fold or tool group the user opens shows its rows with a 40 ms stagger (fade + 4dp rise, standard, in place, at
  most 4 steps of delay); closing is instant.
- Tool cards expand with `animateContentSize` (standard, in-place easing), body fades (quick), chevron rotates 90°.
- Press: no ripple; ink overlay in `tap`, cards and chips scale 0.97, the send button 0.92. Long press opens a
  context sheet.
- Working: a 1dp accent hairline sweeps the composer's top edge (35% band, 1400 ms).
- Haptics via `ViewCompat.performHapticFeedback` (falls back on old API levels): send CLOCK_TICK, stop and approve
  CONFIRM, reject and error REJECT, long press LONG_PRESS. Never per token. Off when the setting is off.
- Reduce motion = the system "Remove animations" setting or the in-app toggle: transitions become short crossfades,
  spinners show rest frames, shimmer is static, auto-scroll jumps.

## Screens

**Home.** Pi-Bolt runs on the phone, so Home is projects first and remote control second. Large title "Pi-Bolt"
with the mark, then:
- Engine banner (only when it has something to say): Preparing (Quad Connect 20dp ink2, "Setting up Pi-Bolt", the
  step in meta ink3), Failed (dangerSoft fill, danger @ 40% line, error glyph, message, Retry in accent), Unavailable
  (surface1, phone-off glyph, the reason, Details). 64dp min, radius 12. Tapping it opens the details sheet: what
  happened, the raw detail in a mono well (surface1, radius 12, Copy), Close and Retry when a retry may help.
- "Connect a model" card when no provider has a key and the first-run sheet was already closed (key glyph, Add key).
- "On this phone": project cards, 72dp like host cards (surface1, line, radius 12): a 40dp tile (surface2, radius 10,
  folder glyph ink2; the live session's Quad 20dp with an 8dp status pip, success or warning, while one is open), the
  title (bodyStrong; a 13dp pin glyph when pinned), then the last session's name or first prompt (meta ink3, ellipsized)
  and " · 3 min ago" that never truncates. Overflow and long press: Pin to top, Rename, Delete (danger). Order: pinned,
  then most recently active, then title.
- No projects: a surface1 card (radius 18) with the mark, "Code on this phone", one line on what a project is, New
  project (primary) and Clone from git (secondary).
- "Computers · N" header (metaStrong ink3, "Remote control" meta and a chevron that turns 180° in a graphicsLayer):
  folded by default once projects exist, open when there are none, when the engine is unavailable or a computer
  session is live. Open: host cards as before, a one-line hint, Scan QR code and Paste link (secondary, side by side).
- Bottom: "New project" (accent, primary, full width) on a canvas fade. Nothing below the title is placed until the
  folders were read (a few ms, off the main thread), so the computers section never jumps.

**New project sheet.** Title, one line, a segmented "Empty folder | Clone from git". Clone shows the repository URL
(monoSmall, URI keyboard, no autocorrect) with "Pi runs git clone in the new folder"; the URL names the folder until
the name is edited. Name field, then `projects/<name>` (monoMeta ink3) or the error in danger. Cancel and Create/Clone.
Creating opens a new session in the folder; for a clone the first prompt asks Pi to `git clone <url> .` and is sent as
soon as the session can take it. URLs with shell syntax are refused; names may not contain slashes, start with a dot
or collide (case-insensitive) with another project.

**Rename and delete.** Rename changes the title shown in the app only ("The folder stays projects/x"): Pi files its
sessions by path, so the folder never moves. Delete is a confirmation sheet that counts what goes ("with 312 files and
4 saved sessions"), then removes the folder and those session files.

**Project.** Shared bounds from the project card (title as a shared element, like host card to session). Back,
overflow (Pin, Rename, Delete), the title in display, the folder chip (`projects/x`, monoMeta ink2 on surface1, radius
8) and, inside a git repository, the branch from `.git/HEAD` (branch glyph + monoMeta ink3). New session (primary,
full width), then a segmented "Sessions · N | Files".
- Sessions: 64dp cards (surface1, line, radius 12): chat glyph ink3 (the live session: its Quad, lineStrong border,
  "Open" first in the meta line), the session name or first prompt (body ink1), "24 messages · 2 h ago" (meta ink3).
  Tap resumes; the live one resumes without reopening.
- Files: one folder at a time. An "up" row (back glyph + the relative path, monoSmall ink2) when inside a folder;
  52dp rows: folder or file glyph, the name in monoSmall (dot files ink3), size or "N items" (meta ink3) at the end.
  Folders first, then files, by name. Tapping a file opens the full-screen viewer (first 512 KB; binary files say so).
  Back goes up a folder before it leaves the screen.

**Provider sheet (first run and Add provider).** Opens by itself once, 450 ms after the first frame of Home, when no
provider has a key. Eyebrow "Welcome to Pi-Bolt" (first run only), "Connect a model", one line saying the key stays on
the phone. Search field, then 60dp provider rows (28dp initial badge surface3 radius 7, name body, note meta ink3,
"✓ Added" for configured ones); "Later" (first run). Picking one slides to the key step (standard, enter easing):
back, badge, provider name (title), what happens to the key, the key field (mono, obscured, Password keyboard, no
autocorrect; Paste when empty, Show/Hide when filled), a lock line "Stored on this device only" (or the error in
danger), "Get a key at console.anthropic.com" (accent, opens the browser), Cancel and Save key. The key lives only in
the sheet's memory (never saved instance state), is cleared after saving and never logged.

**Settings > Model providers** comes first: configured providers (badge, name, "Key stored on this phone", a delete
action that asks first and says the key is not revoked at the provider) and Add provider.

**Pair confirmation.** A sheet (not an AlertDialog) for links opened from outside the app: host name, addresses,
"Only continue if you just ran /remote-control on this computer", a warning line if it ends the current session,
Pair/Connect and Cancel. Scanning and pasting stay one-step.

**Scan.** Full-bleed camera, canvas-tinted scrim with a 260dp rounded window, corner brackets that settle in on open,
a Quad Connect while searching, success haptic and a check when a code is read. "Paste link instead" at the bottom.

**Session.**
- Top bar 56dp: back, computer name (subhead) with the session's cwd basename beneath (meta, mono), Quad 16dp in the
  current mode, overflow (Disconnect, Forget computer with confirmation, Session info).
- Subtitle under the name: model · thinking level · context %, tap → Session info sheet (model, thinking, context
  ring, tokens, cost, compaction button).
- Transcript (LazyColumn, stable keys and contentType per block; only the last block changes while streaming):
  - user: right, max 84% width, surface2, radius 18/6, padding 12x16, prose. Images sent from this phone show as
    56dp thumbnails above the text (kept in memory for the session); images without a local copy read as a compact
    "2 images" chip (surface3, radius 4, image glyph, meta ink2).
  - assistant: full width on canvas, prose, no bubble; paragraphs 12dp apart; ink3 list markers.
  - user, sending: an idle prompt shows at once as its bubble at 60% with "Sending" (meta ink3) under it, and the
    status line shows Quad Think + "Sending". The host's echo takes over the row in place (no second rise); a failed
    send removes it and returns the text to the composer. Slash commands are not shown early.
  - thinking: row "Thinking" (Quad Think 12dp + shimmer) live, "Thought for 8s" after; collapsed by default; body
    thinking style with a 1dp rail; live body is a 5-line window pinned to the newest text that glides up (120 ms,
    in place) as lines arrive, with a 24dp top fade.
  - tool row: 20dp slot (Run/Write spinner → result glyph), verb (label ink2: Run, Read, Edit, Write, Search),
    argument (mono ink3, middle ellipsis for paths), right meta (+n success, −n danger, duration tnum; while running,
    the elapsed time from 1 s: "4s", "1m 3s", ticking once a second). Tap expands a card: bash (`$` glyph, command,
    Copy, exit badge, last 12 lines, footer Copy output + "Show N more" → full-screen viewer with copy and wrap
    toggle), edit/diff (gutter bars, unchanged runs collapsed), read (path, line range, preview).
  - running bash card: the output's live tail with a 24dp top fade and a terminal cursor after the last glyph (7x14dp
    ink2 block, hard blink 500 ms on / 500 ms off, solid at 60% under reduce motion; alone on the first line before
    any output). It is the card's only live signal: no top sweep (other running cards keep the 2dp sweep).
  - three or more consecutive finished Read/Search/Find/List steps (and `todo` calls) fold into one step row: check
    glyph, "Read 3 files, ran 1 search" or "Updated todos 7 times" (label ink2), the base names or task subjects (mono
    ink3), summed duration, chevron; tap lists them. Run, Edit and Write are never grouped.
  - subagent steps (opensec-pi-subagents): `Agent` reads as its type and task ("Explore  Find the parser code"),
    `steer_subagent`, `get_subagent_result` and `SubagentWorkflow` as "Steer agent", "Agent result", "Workflow" + the
    workflow's name. Under the row, one meta ink3 line: what the agent is doing while it runs ("reading 3 files… · 4
    tool uses"), the start of its answer when done (without the "Agent completed in" headline and the resume hint).
  - `todo` steps read as one line: "Todo  Added: Write tests", "Done: …", "Started: …", "Cleared the list".
  - code blocks: surface1, radius 12, 1dp line. Header 40dp: code glyph 14dp ink3, then the filename (monoSmall
    ink1, middle ellipsis) from the fence info string (`ts title="src/a.ts"`, `ts:src/a.ts`, `src/a.ts`) or the
    language (monoMeta ink3; shell aliases show as `shell`, none as `code`); right side Wrap (only when a line
    overflows or wrap is on), Open (folded or 40+ lines) and the shared Copy button (check + "Copied", 1500 ms).
    Code Mono 12.5/19, 10dp vertical padding; line numbers from 3 lines on, Mono 11.5 ink3 right-aligned, then a 1dp
    line rule and 10dp to the code. No wrap by default: one horizontal scroll with 20dp edge fades into surface1.
    Over 20 lines the block folds to 14 with a 40dp bottom fade and a 44dp footer ("Show all 84 lines" + chevron)
    that expands in place up to 150 lines ("Show less" scrolls the header back into view) and opens the viewer
    beyond ("Open all 1,240 lines"). Streaming: the header shows Quad Write 12dp + a ticking line count, the code
    end pulses like prose, and a block past 20 lines shows its last 14 lines at a fixed height under a 32dp top
    fade; on close it crossfades (quick) to the folded start at the same height. Only complete lines are
    highlighted (once per new line); closed blocks are cached by content. ```diff / ```patch (and untagged text that
    is mostly a unified diff) render as diff rows with numbers from the `@@` headers, the path from `+++ b/…` and
    `+n −n` in the header.
  - tables: a grid, not rows of loose cells. Column width = widest single-line cell + 24dp, clamped 64…240dp; spare
    width is spread in proportion so the table spans the card, wider tables scroll with edge fades. While streaming,
    widths only grow. Radius 12, 1dp line border, header surface1 metaStrong ink2, 1dp lineStrong under it; body
    Inter 14/20 ink1, cells 12×9dp, 1dp line between rows, no column lines, zebra ink1 @ 3% from 8 rows. `:--`,
    `:--:`, `--:` are honoured; all-numeric columns align to the end with tnum. ✓ / yes success, ✗ / no danger,
    a lone dash ink3.
  - inline code: Mono 0.88em ink1 on a surface3 chip (radius 4, line box inset 14%), one chip per line segment
    when it wraps. A narrow no-break space pads each side inside the chip so it never touches neighbours; code of
    24+ characters without spaces may break after `/ . _ -`.
  - after a turn: "Worked for 12s · 6 steps" fold collapses its steps; the final answer stays visible. Under it,
    chips for the files the turn edited or wrote (28dp, radius 8, surface1 + 1dp line, name in mono meta ink1, +n
    success, −n danger, tnum; at most 3, then "+k more"); a chip opens that file's diff. An opened fold draws a 1dp
    line rail at the slot centre through its steps (broken 4dp around each glyph, ending at the last one) and indents
    the prose between steps to the step text.
  - under the last finished reply: Copy (check + "Copied"), Ask again (sends the same prompt as a new turn; Pi has
    no regenerate; hidden for prompts with images) and More (Select text, View as Markdown, Share), 40dp tall,
    aligned with the prose. Fades in 150 ms after the turn settles; never animates in history.
  - live status line: Quad 16dp + shimmer label + elapsed timer. A running tool is named: the verb shimmers and its
    command or path follows in mono small ink3 ("Run  npm test"; a subagent reads "Agent working" + its task;
    parallel tools add "+n"); text changes crossfade (quick).
  - errors and notices: radius 12 outline in danger/warning @ 40%, text buttons.
- Todo panel above the queue strip (see Todo panel below).
- Queue strip above the composer: 44dp rows with a 2dp bar (warning = steer, ink4 = follow-up), swipe to remove.
- Composer: surface1, radius 18, 1dp lineStrong (focus line when focused), 16sp text, 1–6 lines; bar: attach (+,
  48dp; ink4 and a "<model> can't read images" snackbar when the model's `input` lacks "image"), model chip, thinking
  chip, send (40dp accent circle). Attached images (Photo Picker, images only, at most 4) sit in a tray above the
  text: 56dp thumbnails, radius 8, 1dp line, a Quad Write while each encodes, a 20dp ✕ (scrim, lineStrong ring, 48dp
  touch) at the top-end corner. Encoding runs off the main thread: long edge at most 2000 px, JPEG 85 (PNG only when
  transparent and under 1.5 MB), refused over Pi's 4.5 MiB base64 limit. Send works with images and no text. While the agent works: send becomes Stop (surface3 circle, rounded square
  glyph) when the field is empty, and a "Steer | Follow-up" segmented control appears while typing. The palette and
  `@` mentions open above the field (see Command palette below). A thumbnail in the tray opens the image viewer. The
  model and thinking chips cycle on long press (click label "Next model", "Next thinking level", a tick and a
  snackbar naming the new value). IME insets animate with the keyboard. The draft is kept per session (computer or
  project, and Pi session) across restarts.
- Empty session: the mark, "New session" and the folder tag, then "Commands" (the host's slash commands, mono name
  + description in meta ink3, the first 6 then "Show all N commands"; tap puts "/name " in the composer and focuses
  it) and "Recent" (the last 5 distinct prompts this phone sent to this computer; tap fills the composer). 48dp rows,
  SectionLabel headings, nothing sends by itself. Recent prompts persist per computer (20 at most) in
  `recent-prompts.json`.
- Connection banner under the top bar: Quad Connect + "Reconnecting in 3s" countdown + Retry; slides down/up.
- Top bar: a Search action (48dp) sits before the Quad. Overflow on a computer: Session and usage, Export or share,
  Disconnect, Forget computer.
- On this phone (a project's session): the title is the project's name and the subtitle drops the folder (session
  name and context only); back reads "Project". Overflow: New session, Resume a session, Fork from a message,
  Duplicate session, Rename session, Session and usage, Export or share (no Disconnect or Forget computer). The banner reads "Starting Pi-Bolt…" (Quad Connect) and "Engine stopped" with Restart (danger
  soft, power glyph). Long press on a user message adds "Fork from here": Pi forks the session at that message and
  its text comes back to the composer to edit and send. Recent prompts are kept per project.
- Sheets (ModalBottomSheet, predictive back): model picker (search, "Current" then one group per provider under a
  meta-strong heading, 60dp rows with provider initial; `/model text` opens it filtered),
  thinking level (52dp option rows with a description), Pi's dialogs (Quad Wait eyebrow "Pi is asking", title,
  message in a command well when it looks like a command (surface1, radius 12, the cwd in mono meta ink3 with Copy,
  then `$` in success and the command in mono), options as 52dp rows, primary button; swipe or scrim tap
  does not answer: an explicit Cancel does; countdown chip when the dialog has a timeout; haptic on appear).
- Snackbars for notify: surface3, radius 12, line, above the composer, kind-coloured icon.

**Command palette.** Typing `/` opens a panel above the composer (surface2, radius 12, 1dp line, at most 264dp,
scrolls): 48dp rows with the command in mono small ink1, its argument hint in mono meta ink3, the description in meta
ink3 under it and a source badge at the end (micro, surface3, radius 4: `app`, `extension`, `prompt`, `skill`).
Matching is fuzzy (prefix, then word starts, then scattered letters), by name and then by description; a space closes
the panel and a usage line takes its place (`/compact [instructions]`, mono meta ink3). The app's own commands are
the terminal built-ins that make sense on a phone: `/new`, `/resume`, `/fork`, `/clone` and `!` (on this phone only),
`/compact`, `/name`, `/session`, `/model`, `/thinking`, `/copy`, `/export`, `/search`. They run in the app (a sheet or
an RPC command), never reach the host, and a host command of the same name wins. Picking one that takes no argument
runs it at once; others fill `/name ` and wait. Picks tick (haptic).
- `@` (at the start or after a space) lists the project's files and folders the same way (file glyph, the name in
  mono small, its folder in mono meta ink3, start-ellipsised); the file name counts more than its folders. Picking
  puts `@path ` in the prompt (quoted when it has spaces). The list is read once per screen off the main thread,
  breadth first, skipping `.git`, `node_modules` and build folders, at most 20 000 entries. On this phone only.
- `!command` runs in the project's shell without the model; `!!command` keeps the output out of Pi's context. A
  quiet line above the field says which. The run shows at once as a Run step (its output streams in) and stays
  visible after the answer instead of folding with the agent's steps.
- Hardware keyboards: Ctrl+Enter sends, Esc stops a run (empty field), Ctrl+P next model, Shift+Tab next thinking
  level. Enter adds a line, as Android keyboards expect.

**Session sheet.** Opened from the title or "Session and usage": the context card (label, "30.0% of 200k" in meta
tnum, a 6dp bar on surface3, ink2, warning over 70%, danger over 90%, animated in place; then the terminal footer's
line `↑50k ↓10k R40k W5.0k $0.450` in mono meta ink3), a totals card (input, output, cost; cache read, cache write,
cache hit; prompts, replies, tool calls; values in body-strong tnum over meta labels), Model and Thinking rows (tap
to pick, a "Next" text action to cycle), folder and version, then Compact, Rename and Export. Totals come from
`get_session_stats` when the sheet opens; until then the bar uses the last reply's usage.

**Search.** The search action (or `/search text`) swaps the top bar for a search bar: back, the field (focused),
"3 of 12" (meta tnum, polite live region; "No match" in warning), previous and next (48dp). The line of the current
hit shows under it (meta ink2, two lines). Every message, reasoning block, tool call and output is searched; a hit in
a folded turn opens the fold, one in a "Read 3 files" group opens the group, then the row scrolls to a third of the
screen. Hits start at the newest. Back closes.

**Message actions.** Long press on a user message: Copy, Send again, Edit and resend (on this phone: forks before
the message and returns its text), Edit in composer, Select text, Share. On a reply block: Copy (plain text, without
Markdown), Copy as Markdown, Regenerate (on this phone: forks before the prompt and sends it again), Select text, View
as Markdown, Share. More under the last reply offers the same.

**Export.** "Export or share" (or `/export`): Share as Markdown (a `.md` file through the share sheet), Copy as
Markdown, and on this phone Share session file (the JSONL) and Share as HTML (`export_html`). Files are written to
`cache/exports` and shared through a FileProvider.

**Image viewer.** A tap on an image in a user message or the composer tray opens it full screen on black: swipe
between images, pinch or double-tap (2.5x) to zoom up to 6x, drag to pan a zoomed image (kept on screen), Back or ✕
to close. Save puts a PNG in Pictures/Pi-Bolt (Android 10+); Share sends it. The full image is kept for the six most
recent messages; older ones open their thumbnail.

**Compaction.** The status line names why it runs: "Compacting" (manual), "Auto-compacting" (threshold) or
"Context full, compacting" (overflow); its summary stays as an expandable notice.

**Todo panel.** The todo extension (opensec-pi-todo) draws its panel in the TUI only, so the app builds it from the
`todo` tool's results: each result's `details.tasks` is the whole list after the call (`SessionState.todos`, from
history and from live `tool_execution_end`).
- Card above the composer: surface1, radius 12, 1dp line, 12dp side inset. Heading 48dp (the tap target): a 14dp
  progress ring (ink4 track, success arc of done/total, settle), "Todos" (label ink1), "2/5" (meta ink3, tnum),
  a chevron (ink3) that turns in a graphicsLayer. Tapping collapses the card to the heading, which then names the
  current task (meta ink2).
- Rows 32dp: 20dp glyph slot, 12dp gap, body text. Pending: 12dp ring ink3, text ink2. In progress: Quad Think 12dp
  ink1 and the task's activeForm ("Writing tests") while the agent runs; idle, a half-filled ring ink2 and the
  subject; text ink1. Completed: check success, text ink3 with strikethrough. Glyph swaps crossfade (quick, scale
  0.7→1), text colour changes in place; none of it under reduce motion.
- At most 6 rows: past that the last line is "+3 more (2 completed, 1 pending)" (meta ink3), completed rows going
  first, as in the TUI.
- Lifetime as in the TUI: deleted tasks never show; completed rows stay until the next prompt starts a run, then
  leave; a run that ends with every task done closes the card. Resuming a session replays the same rules over the
  history, so a finished list does not come back. A reconnect to the same session whose messages lost the todo calls
  (compaction) keeps the list it had.
- The card enters and leaves with expandVertically + fade (standard); rows added or removed resize it with
  animateContentSize (in place). It reads only `todos` and `isRunning` through `derivedStateOf`, so streamed frames
  never recompose it.
- TalkBack: the heading is one node, "Todos, 2 of 5 done" (collapsed: ". Now: <task>"), with Expanded/Collapsed
  state and an Expand/Collapse click label; each row is "Done: …", "In progress: …" or "To do: …".

**Settings.**
- Appearance: theme tiles (seven swatches, four a row, drawn with each theme's canvas, surface and ink lines; selected = 1.5dp accent
  ring), accent circles, text size slider with a live prose preview, density (Comfortable / Compact).
- Motion and feedback: Reduce motion (Follow system / On), Haptics.
- Session: show thinking expanded, tool output preview lines (6 / 12 / 24).
- Pi on this phone: steering and follow-up delivery (One at a time / All at once), the thinking level new sessions
  start with (six segments), Auto-compact and Auto-retry toggles, and the default model (the last one picked, as in
  the terminal). They are the engine's own settings (`agent/settings.json`, other keys kept) and reach the open
  on-device session at once over RPC.
- Notifications: permission state with a button to the system settings.
- About: app version, device name, licences (Inter, JetBrains Mono: OFL).
Changes apply live, without recreating the activity, and persist in a small JSON file (read synchronously at
startup so the first frame has the right theme).

## Not carried over from the desktop app

Its hex values, Geist fonts, 4x4 pixel grid, 80 ms cadence, bolt pattern and lime colour. Hover actions become
long-press sheets; long output goes to full-screen viewers, never nested scroll boxes.

## Smoothness

- No disk or JSON work on the main thread at startup or on navigation: hosts, recent prompts, project metadata and
  the engine's session index load on background threads (`data/BackgroundFile`), project folders and sessions on
  `Dispatchers.IO`. The one exception is settings.json (a few hundred bytes), read before the first frame so it has
  the right theme instead of flashing the default one.
- Lists use stable keys and a contentType per row kind. Transcript rows, markdown blocks and tool data are
  `@Immutable`, so with strong skipping a finished row is compared by value and skipped; only the block being written
  recomposes per streamed frame. Markdown re-parses only the tail block; closed code blocks are highlighted once and
  cached by content. A message over 20,000 characters that needs a full parse (a restored session) is parsed on
  `Dispatchers.Default` and shows its first lines as plain text meanwhile.
- Cards that show a live session read only the two or three values they display through `derivedStateOf`, so a
  streamed frame does not recompose Home.
- Nothing animates while idle: spinners only while busy, the mark's entrance once per launch. Rotations, scales and
  press feedback run in `graphicsLayer`; size changes are limited to expand/collapse and sheets.
