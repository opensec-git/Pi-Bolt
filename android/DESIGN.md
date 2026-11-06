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
Diff: add bg success @ 11% / fg `#9BE6BF`, delete bg danger @ 11% / fg `#F7A9A5`, 3dp gutter bar @ 60%.
Syntax: keywords ink1 SemiBold, strings `#9ED3C6`, numbers `#E5A97E`, types `#C9B8F0`, params ink2, comments ink3
italic, punctuation ink2.

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
| mono | 13.5 / 20 | 400 | code blocks, diffs, terminal |
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

**Computers (home).** Large title "Pi-Bolt" with the mark, then paired computers as 72dp cards (surface1, line,
radius 12): status dot, name (body/600), second line "LAN · tunnel · v1.1.1 · last seen 5 min ago" in meta ink3,
overflow menu (Forget, with a confirmation). The live computer shows a Quad in its state. Bottom: "Scan QR code"
(accent, primary) and "Paste link" (surface2, secondary), both 52dp. Empty state: animated mark, "Control Pi-Bolt
from your phone", three numbered steps (run `/remote-control`, scan, done). Top-right: Settings.

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
  - three or more consecutive finished Read/Search/Find/List steps fold into one step row: check glyph, "Read 3
    files, ran 1 search" (label ink2), the base names (mono ink3), summed duration, chevron; tap lists them. Run,
    Edit and Write are never grouped.
  - code blocks: surface1, radius 12, header 36dp (language, Copy), horizontal scroll, folded beyond 30 lines.
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
- Queue strip above the composer: 44dp rows with a 2dp bar (warning = steer, ink4 = follow-up), swipe to remove.
- Composer: surface1, radius 18, 1dp lineStrong (focus line when focused), 16sp text, 1–8 lines; bar: attach (+,
  48dp; ink4 and a "<model> can't read images" snackbar when the model's `input` lacks "image"), model chip, thinking
  chip, send (40dp accent circle). Attached images (Photo Picker, images only, at most 4) sit in a tray above the
  text: 56dp thumbnails, radius 8, 1dp line, a Quad Write while each encodes, a 20dp ✕ (scrim, lineStrong ring, 48dp
  touch) at the top-end corner. Encoding runs off the main thread: long edge at most 2000 px, JPEG 85 (PNG only when
  transparent and under 1.5 MB), refused over Pi's 4.5 MiB base64 limit. Send works with images and no text. While the agent works: send becomes Stop (surface3 circle, rounded square
  glyph) when the field is empty, and a "Steer | Follow-up" segmented control appears while typing. Slash
  suggestions above the field. IME insets animate with the keyboard.
- Empty session: the mark, "New session" and the folder tag, then "Commands" (the host's slash commands, mono name
  + description in meta ink3, the first 6 then "Show all N commands"; tap puts "/name " in the composer and focuses
  it) and "Recent" (the last 5 distinct prompts this phone sent to this computer; tap fills the composer). 48dp rows,
  SectionLabel headings, nothing sends by itself. Recent prompts persist per computer (20 at most) in
  `recent-prompts.json`.
- Connection banner under the top bar: Quad Connect + "Reconnecting in 3s" countdown + Retry; slides down/up.
- Sheets (ModalBottomSheet, predictive back): model picker (search, current first, 60dp rows with provider initial),
  thinking level (52dp option rows with a description), Pi's dialogs (Quad Wait eyebrow "Pi is asking", title,
  message in a command well when it looks like a command (surface1, radius 12, the cwd in mono meta ink3 with Copy,
  then `$` in success and the command in mono), options as 52dp rows, primary button; swipe or scrim tap
  does not answer: an explicit Cancel does; countdown chip when the dialog has a timeout; haptic on appear).
- Snackbars for notify: surface3, radius 12, line, above the composer, kind-coloured icon.

**Settings.**
- Appearance: theme tiles (seven swatches, four a row, drawn with each theme's canvas, surface and ink lines; selected = 1.5dp accent
  ring), accent circles, text size slider with a live prose preview, density (Comfortable / Compact).
- Motion and feedback: Reduce motion (Follow system / On), Haptics.
- Session: show thinking expanded, tool output preview lines (6 / 12 / 24).
- Notifications: permission state with a button to the system settings.
- About: app version, device name, licences (Inter, JetBrains Mono: OFL).
Changes apply live, without recreating the activity, and persist in a small JSON file (read synchronously at
startup so the first frame has the right theme).

## Not carried over from the desktop app

Its hex values, Geist fonts, 4x4 pixel grid, 80 ms cadence, bolt pattern and lime colour. Hover actions become
long-press sheets; long output goes to full-screen viewers, never nested scroll boxes.
