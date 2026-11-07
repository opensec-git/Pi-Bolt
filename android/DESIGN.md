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
| surface1 (cards, composer) | `#121417` | `#0D0E10` | `#1C1A16` | `#262624` | `#171717` | `#0F0F0F` | `#151B23` |
| surface2 (bubbles, sheets) | `#191C20` | `#101113` | `#24211C` | `#30302E` | `#212121` | `#111111` | `#1C2128` |
| surface3 (pressed, menus) | `#21252A` | `#17191C` | `#2C2822` | `#3A3936` | `#2A2A2A` | `#1A1A1A` | `#262C36` |
| ink1 (text) | `#E8EAED` | `#E8EAED` | `#EFE9DD` | `#F5F4EE` | `#ECECEC` | `#EDEDED` | `#F0F6FC` |
| ink2 (secondary) | `#A3A8B0` | `#A3A8B0` | `#B9B1A3` | `#C3C0B6` | `#B4B4B4` | `#A1A1A1` | `#B7BDC8` |
| ink3 (meta, lowest text) | `#878D96` | `#7D838C` | `#979084` | `#A6A49C` | `#929292` | `#8F8F8F` | `#9198A1` |
| ink4 (rules, never text) | `#4C5158` | `#4C5158` | `#5A554D` | `#5E5D59` | `#545454` | `#454545` | `#3D444D` |
| line / lineStrong | ink1 @ 9% / 16% | ink1 @ 11% / 19% | ink1 @ 10% / 17% | ink1 @ 10% / 17% | ink1 @ 10% / 17% | ink1 @ 12% / 20% | ink1 @ 10% / 18% |
| scrim | black @ 56% | black @ 70% | black @ 56% | black @ 60% | black @ 60% | black @ 70% | black @ 60% |

Claude, Codex, Vercel and GitHub are palettes in the spirit of those products' dark modes, not their assets.

Overlays: press = ink1 @ 7%, selected = ink1 @ 11%, focus line = ink1 @ 40%.

Accent (independent of theme): **Theme** (default: the theme's own accent, Claude coral `#D97757` / `#1F1E1D`,
GitHub blue `#4493F8` / `#0D1117`, ink1 / canvas for the others), **Ink** (accent = ink1, onAccent = canvas),
**Iris** `#A99CFF` / `#120F2A`, **Glacier** `#7CC4F0` / `#06202E`. Accent marks the primary button, send button, focus ring, selection, links and
the working hairline. Never status.

Brand: the Pi-Bolt logo's colours, only on the app mark (launcher, splash, Home, empty state). The mark is the logo's
bolt (`ic_pibolt_logo`, cropped from docs/images/logo.svg): an upper blade in yellow (`#F7C04A` lit face, `#E9A42C`
shaded face) and a lower blade in blue (`#4C96EA` lit, `#2A78D6` shaded). Yellow and blue are both brand colours and
never stand for state or action. `PiColors.brand` (`#FFD84D`) is the single-colour fallback where the logo can't be drawn.

Status (all themes): success `#5FD39A`, warning `#E8B04B` (needs you, steer), danger `#F2706B` (Claude: `#FF8681`, its
lighter canvas needs it); soft fills @ 14% (Claude's danger fill @ 12%).
Diff: add bg success @ 11% / fg `#9BE6BF`, delete bg danger @ 11% / fg `#F7A9A5`; changed words in paired lines
success / danger @ 22%. Add and delete never differ by hue alone: a `+` / `−` sign column, a solid 3dp gutter bar
(success @ 60%) for additions and a hatched one (2dp stripes every 4dp, danger @ 70%) for deletions. Context rows
ink2, line numbers ink3, hunk rows monoMeta ink3 on surface2.
Syntax: one palette per theme (`SyntaxColors`): keyword, type (capitalised names and built-ins such as `string`),
function (calls and declarations), string, number, constant (`true`, `null`, ALL_CAPS), operator, attribute
(annotations, decorators, object and YAML keys, HTML attributes), tag, regex, escape (escapes and `${…}`), variable
(`$x`, `@x`), comments italic; punctuation ink2, base text ink1, the shell `$` prompt success SemiBold, diff lines in
the diff colours. Graphite, Void and Codex share the cool "Pi" palette (violet keywords, yellow types, blue calls,
green strings, orange numbers); Ember is warm and earthy, Claude coral / sand / sage, Vercel pink / purple / blue /
green, GitHub uses its dark "prettylights" roles. Only `text`, `log` and `output` fences stay plain.

| Role | Pi | Ember | Claude | Vercel | GitHub |
|---|---|---|---|---|---|
| keyword | `#C9A0F5` | `#F28E7E` | `#E8956F` | `#FF6B98` | `#FF7B72` |
| type | `#E8C67E` | `#F2C46A` | `#E3C590` | `#C49BFF` | `#FFA657` |
| function | `#7FB6F5` | `#9CC78B` | `#9DBAE8` | `#6CB4FF` | `#D2A8FF` |
| string | `#A3D58E` | `#C8CC6E` | `#B6CF8F` | `#6CD58B` | `#A5D6FF` |
| number / constant | `#E9A77C` | `#D99BB8` | `#D9A3D9` | `#F5B562` / `#6CB4FF` | `#79C0FF` |
| operator | `#86CBDD` | `#F0A35C` | ink2 | ink2 | `#FF7B72` |
| attribute | `#E9A77C` | `#8DB8A8` | `#E3C590` | `#C49BFF` | `#79C0FF` |
| tag | `#F08E9A` | `#F28E7E` | `#E8956F` | `#6CD58B` | `#7EE787` |
| regex / escape | `#86CBDD` | `#9CC78B` / `#F0A35C` | `#8FCBC0` | `#F5B562` | `#7EE787` |
| variable | `#F08E9A` | `#8DB8A8` | `#9DBAE8` | `#FF9F6B` | `#FFA657` |
| comment | `#858B94` | ink3 | ink3 | ink3 | ink3 |

Contrast (WCAG 2.1, checked by `ContrastTest` for every theme and accent):
- ink1, ink2 and ink3 pass AA (4.5:1) on canvas, surface1, surface2 and surface3. ink3 is the lowest text and was
  raised where it missed AA on surface2 or surface3 (Graphite `#7D838C` → `#878D96`, Ember `#8E877B` → `#979084`,
  Claude `#9C9A92` → `#A6A49C`, Codex `#8F8F8F` → `#929292`). ink3 on surface3 (menus, snackbars, inline code) now
  measures 4.61 to 5.38:1; it was 4.03 to 4.44 in Graphite, Ember, Claude and Codex.
- Rule: no text below ink3, and no ink4 text anywhere. On surface3 prefer ink2 for anything longer than a meta line.
- Status colours pass on canvas, surface1 and surface2; danger passes on dangerSoft over cards and sheets (the danger
  button), which is why Claude has its own danger.
- onAccent passes on accent for every accent in every theme.
- Void and Vercel cards (surface1) are one step lighter (`#0D0E10`, `#0F0F0F`) so they stand off the pure-black canvas.
- Syntax colours pass on canvas, surface1 and surface2 (`SyntaxColorsTest`). Lines (1.2 to 1.3:1) are decoration: a
  1dp line is never the only cue that something is interactive without a fill or label.

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

The app mark (`BrandMark`) is the logo's yellow-and-blue bolt (see Colour), not a Quad: it enters once per launch
and place (fade, scale 0.82→1 and a 6dp rise over the sheet duration); never while reduced motion is on.

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
- "Running": the on-device sessions working or waiting for an answer now, in any project (56dp rows: the live Quad,
  the project, what it does). "Continue": up to 3 projects' newest sessions (64dp rows, one tap resumes), the open
  idle session first; a project already under Running is not repeated here.
- "Projects" (on this phone): project cards, 72dp like host cards (surface1, line, radius 12): a 40dp tile (surface2, radius 10,
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
(monoSmall, URI keyboard, no autocorrect) and, when signed in to GitHub, "Choose from GitHub" (accent text action) that
opens the repository picker: a search field, then 56dp rows (full name in body ink1, description meta ink3, a lock
glyph for private ones), 100 per page with "Load more". The URL names the folder until the name is edited. Name field,
then `projects/<name>` (monoMeta ink3) or the error in danger. Cancel and Create/Clone. The app clones with the
engine's git (the hint line shows its progress, "Receiving objects · 45%"); a failed clone removes the folder and shows
the explained error. Then a new session opens in the folder and asks Pi for a short overview. URLs with shell syntax are refused; names may not contain slashes, start with a dot
or collide (case-insensitive) with another project.

**Rename and delete.** Rename changes the title shown in the app only ("The folder stays projects/x"): Pi files its
sessions by path, so the folder never moves. Delete is a confirmation sheet that counts what goes ("with 312 files and
4 saved sessions"), then removes the folder and those session files.

**Project.** One workspace with four places, Sessions, Files, Changes and Terminal, under one top bar and over one
bottom bar, so the project reads as a single tool rather than four screens. Shared bounds from the project card (title
as a shared element, like host card to session).
- Top bar 56dp: back ("Projects"), the title (subhead ink1, a 13dp pin glyph when pinned) over `projects/x` and, in a
  git repository, the branch glyph + the branch from `.git/HEAD` (monoMeta ink3); then the shown tab's own action
  (Sessions: search and manage sessions; Terminal: Secrets) and ⋮ (Pin, Rename, Export as zip, Secrets, Runtimes,
  Delete in danger). Nothing else sits above a tab's content, so each tab starts 56dp under the status bar.
- Bottom bar (`ProjectNavBar`): surface1, a 1dp line above, 64dp plus the navigation bar. Four equal items: a 22dp
  glyph (filled when selected, outlined otherwise; the terminal glyph for Terminal) in a 56x30 pill (surface3 when
  selected, crossfading), the label under it (metaStrong ink1 selected, meta ink3 otherwise). Changes carries the
  number of changed paths (micro onAccent on accent, at least 16dp wide, "99+" past 99, ringed in surface1); Sessions
  (Pi working in this project) and Terminal (a shell running) an 8dp success dot. TalkBack: tabs with Selected state
  and the badge in words ("4 changed files", "Shell running", "Pi is working"). It is read when the project opens
  (git status once), not only when Changes is shown.
- The bar steps aside while the keyboard is up (the commit field, a new file's name and the terminal keep the room)
  and while a full-screen view (file, diff, history) covers the project. In a window under 480dp tall (landscape) the
  top bar also steps aside while the keyboard is up, so the terminal keeps its lines.
- Each tab keeps its own scroll position; the shown tab survives rotation and process death.
- Empty and loading states share one pattern: an `EmptyCard` (surface1, line, radius 12, 16dp padding; a 20dp ink3
  glyph, the title in bodyStrong, one line in body ink2, then its actions) and a `LoadingRow` (16dp Quad Run ink3 +
  "Reading sessions…" in meta ink3, 48dp) while a list is read from disk.
- Sessions: New session (primary, full width) first, then "6 sessions" (SectionLabel) over the rows; without any,
  "No sessions yet" (EmptyCard, chat glyph). Rows: 64dp cards (surface1, line, radius 12): chat glyph ink3 (the live session: its Quad, lineStrong border,
  "Open" first in the meta line), the session name or first prompt (body ink1), "24 messages · 2 h ago" (meta ink3).
  Tap resumes; the live one resumes without reopening.
- Files: one folder at a time, under a sticky breadcrumb bar (+ for new file, folder and import; sort and hidden
  files). An empty folder is an EmptyCard ("No files yet" at the root, "This folder is empty" inside, or the count of
  hidden files) with New file and Import (secondary, side by side); 52dp rows: folder or file glyph, the name in monoSmall (dot files ink3), size or "N items" (meta ink3) at the end.
  Folders first, then files, by name. Tapping a file opens the full-screen viewer (first 512 KB; binary files say so).
  Back goes up a folder before it leaves the screen.
  The "+" menu also imports: "Import files…" (ACTION_OPEN_DOCUMENT, several) and "Import a folder…"
  (ACTION_OPEN_DOCUMENT_TREE) into the folder shown; names that exist get " (2)". A folder's long-press sheet has
  "Export as zip", a file's "Share"; the project's ⋮ menu has "Export as zip". The export sheet: "Leave out .git and
  dependencies" (a 56dp checkbox row, on by default), then Share (secondary) and Save to… (primary, ACTION_CREATE_DOCUMENT).
  A transfer shows a card above the bottom edge (surface2, lineStrong, radius 12): "Importing 3 files · 40%"
  (bodyStrong), "2 files · 1.2 MB of 3 MB · src/a.kt" (monoMeta ink3), Cancel (danger text action) and a 3dp accent
  progress line on surface3 (no fill when the total is unknown: never an indeterminate animation). Progress updates
  at most every 100 ms. New project has "Import a zip as a project…" (accent text action) under its buttons.
- Changes (git, through the engine's own git off the main thread with timeouts): outside a repository a card offers
  "Initialise git". Inside: the branch card (surface1, radius 12): branch glyph, the branch in bodyStrong and
  "origin/main · 2 to push · 1 to pull" in meta ink3, tap for the Branches sheet; while an operation runs, a 16dp Run
  Quad and git's progress in meta ink2; then Pull, Push (text actions), "Pull request" (accent, GitHub remotes when
  signed in) and History. Sections "Conflicts", "Staged · N" (Unstage all) and "Changes · N" (Stage all) list 56dp file
  rows: the change letter in a 20dp surface2 tile (added/untracked success, deleted/conflict danger, renamed warning,
  modified ink2; TalkBack reads the word), the file name in body over its folder in monoMeta ink3, and a 48dp + / −
  button to stage or unstage. Tap opens the diff; long press asks to discard (ConfirmSheet, danger; untracked files are
  deleted). The commit card comes right under the branch card as soon as something is staged (and stays while a
  message is written), so committing never needs a scroll past the file list: "Commit 2 files" (bodyStrong), the
  message field (2 to 8 lines), "Ask Pi" (secondary, sparkle glyph: Pi drafts from the staged diff in the project's
  session and the answer fills the field) and Commit (primary). With changes but nothing staged, a meta ink3 line under
  "Changes · N" says how to stage. Clean: "No changes" (EmptyCard, check glyph); outside a repository "Not a git
  repository" (EmptyCard, branch glyph) with Initialise git; "Reading git status…" (LoadingRow) until git answers. Without a git identity
  the commit opens "Who is committing?" (name, email; prefilled with the GitHub name and no-reply address).
- Diff viewer: full screen (fade and 10% rise, sheet duration). Bar: back, file name over its path, Stage or Unstage
  and, unstaged, Discard. Each hunk is a card: its `@@` header (monoMeta ink3 on surface2) with Stage hunk / Unstage
  hunk and, unstaged, a discard glyph (confirmed), then the rows renderer (DiffBlock: two gutters, sign column, solid
  and hatched bars, syntax colours, word marks). Binary files, renames without changes and very large diffs say so.
- History: full screen list of 64dp rows (subject in body, "abc1234 · author · 2 h ago" meta ink3, refs monoMeta ink2),
  100 at a time. A commit opens its subject (subhead), body, author, date and a copy-hash button, then a card per file
  (path in monoSmall, +/− counts) that folds its diff (folded first when more than three files).
- Branches sheet: a new-branch field (mono, validated like `git check-ref-format`) with Create, then "On this phone"
  (current first, success check) and "On the remote" (switching creates a tracking branch).
- Pull request sheet: title, description (Markdown), "Merge into" chips (the repository's default branch first),
  "Ask Pi" (Pi drafts title and body from the branch's commits) and Create (pushes first when needed). Done, it shows
  "Pull request #12", the link in accent mono, Copy and "Open on GitHub".
- Terminal: see Terminal below; the pane fills the tab between the top bar and the bottom bar (or the keyboard), so the
  extra-keys row always sits on the keyboard.

**Terminal.** Termux's TerminalView in JetBrains Mono on canvas: text ink1, cursor accent, the 16 base colours mapped
to tokens as in tool output (red danger, green success, blue the syntax function colour...), no cursor blink. A 48dp
bar: tab chips (`WorkspaceTab`, the editor's too: 36dp drawn in a 48dp row, radius 8, surface2 + lineStrong when
selected, line otherwise; "1 · title" in monoSmall, ink3 when the shell ended; a 16dp close glyph with a 48dp target),
+ (new terminal), Paste, Text size (one menu: Larger, Smaller, "13 sp · pinch the terminal too"; 8 to 28 sp, also by
pinch), full screen / leave full screen. Under it the Preview chips. The extra-keys row (surface1, 48dp keys, monoMeta ink1 or
a 20dp arrow glyph): Esc, Tab, Ctrl, Alt, the four arrows, |, ~, /, -; Ctrl and Alt fill with accent (onAccent text)
while on and apply to the next key only. Long press selects text (Termux's floating Copy / Paste bar); tap shows the
keyboard (character input, no suggestions). An ended shell says "[Process completed - press Enter]"; Enter closes the
tab. No tab yet: "A shell in this project" (EmptyCard, terminal glyph), one line, Start a terminal (primary). Full screen: the same pane above
the keyboard, status and navigation bars padded. Shells keep running off screen; a low-importance "N terminals
running" notification (Close all) holds them in the background.

**Preview.** Chips "Preview: localhost:5173" (label ink1 on successSoft, success @ 40% line, radius 8, 48dp touch) in
the terminal and above the composer of an on-device session, while a dev server listens. The preview screen: back,
the URL (monoMeta ink2, middle ellipsis), Reload, Open in browser; a 2dp accent progress line while loading; the page
in a WebView (http://localhost and 127.0.0.1 only; tapped links elsewhere open in the browser).

**Secrets.** Back, "Secrets" in display, one paragraph on where values go (engine and terminal environment, never the
model's messages). 64dp rows: the name in mono ink1, the value masked as eight dots (monoMeta ink3, fixed length),
Show / Hide, Edit, Delete (danger). Add secret (primary) opens a form card (surface1, line, radius 12): NAME (mono,
validated: letters, digits, _, not the engine's own variables), Value (masked while typing, eye toggle), Cancel / Save.
Write .env file (secondary) confirms first: values land in plain text in the project and git would commit them; .env
is added to .gitignore in git projects. The window is FLAG_SECURE on this screen.

**File viewer and editor.** Full screen over the project (or the session, for a file on a tool card). Top bar: ✕,
the name and "path · type · N lines · read-only · unsaved" (monoMeta; warning when unsaved), Find, Edit (pencil; files that can be edited) or
Save (editing, accent text action), ⋮ (Ask Pi about the selection / this file, Go to line, Wrap lines, Copy path, Copy
text, Share). Open files are tabs once there are two: a 48dp strip of `WorkspaceTab`s (as the terminal's: surface2 +
lineStrong when shown), a 7dp warning dot when unsaved, the name (monoSmall, at most 160dp) and a ✕ (48dp target).
With the keyboard up in a window under 480dp tall the strip steps aside. Menus in the workspace (project, files,
viewer, terminal) are one `WorkspaceMenu`: surface3, radius 12, lineStrong border, 48dp rows with a 20dp glyph slot. Find bar: the query
(mono), "3 of 12" (meta, live region), ↑ ↓ ✕, then 48dp text toggles "Aa" (match case), "ab" underlined (whole
word), ".*" (regex) in accent with a 18% accent fill when on (TalkBack: switches), and ⇄ for Replace: a second row
with the replacement and "Replace" / "All" (accent text actions); a bad regex or group shows in danger meta below.
Matches are accent 22%, the current one 50%. Editing: the same monoSmall text, colours as in code blocks (updated
as you type), the bracket pair at the cursor on surface3, a 48dp bar over the keyboard (surface1, top line): Undo,
Redo, Insert indent, Go to line, "Ln 12, Col 4" (monoMeta ink3; "No colours over 400 KB" first for big files).
Files over 1 MB open read-only. Leaving or closing a tab with unsaved edits asks first ("Discard").

**Share into Pi-Bolt.** Another app's share sheet lists Pi-Bolt for files, images and text. A sheet: "Share to
Pi-Bolt" with "2 images" / "1 file" / "Text"; a segmented choice when there is more than one (Attach to chat |
Import files, New project | Import files, Import files | Add to chat), the projects as 52dp radio rows (folder glyph,
title, accent check), then the primary action. Attach and Add to chat open the project's session with the images in
the tray or the text in the composer.

**Provider sheet (first run and Add provider).** Opens by itself once, 450 ms after the first frame of Home, when no
provider has a key. Eyebrow "Welcome to Pi-Bolt" (first run only), "Connect a model", one line saying the key stays on
the phone. Search field, then 60dp provider rows (28dp initial badge surface3 radius 7, name body, note meta ink3,
"✓ Added" for configured ones); "Later" (first run). Picking one slides to the key step (standard, enter easing):
back, badge, provider name (title), what happens to the key, the key field (mono, obscured, Password keyboard, no
autocorrect; Paste when empty, Show/Hide when filled), a lock line "Stored on this device only" (or the error in
danger), "Get a key at console.anthropic.com" (accent, opens the browser), Cancel and Save key. The key lives only in
the sheet's memory (never saved instance state), is cleared after saving and never logged.

**Settings > GitHub.** Signed out: one line on what it enables, "Sign in with GitHub" (primary; only when the build has
an OAuth client id) and "Use a personal access token" (secondary, key glyph). Device flow sheet: the user code in
display mono on a surface1 well (TalkBack spells it), Copy, "Open github.com/login/device" (primary), then a 16dp Wait
Quad and "Waiting for you to approve on GitHub"; errors in danger with Try again. Token sheet: what scopes to pick, a
link to create one, a masked mono field, the storage line (Keystore, never shown again, never logged) and Save, which
checks the token against GitHub first. Signed in: name and @login, how and which scopes, a line on where it is used,
Sign out (confirmed).

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
  - tool row: 20dp slot (Run/Write spinner → result glyph), verb (label ink2: "Run" while active, "Ran", "Read",
    "Edited", "Wrote", "Searched", "Found", "Listed" once done), argument (mono ink3, middle ellipsis for paths; a read
    carries its range, `src/app.ts:10-80`), right meta (+n success, −n danger, a failing bash exit "exit 1" in danger,
    "no results" ink3 for an empty search or listing, a duration only from 1 s, tnum; while running, the elapsed time
    from 1 s: "4s", "1m 3s", ticking once a second). A tool the host lacks reads as one meta ink3 line ("List isn't
    available here", no danger glyph) and is hidden once a later step succeeds. Images in a result (`content` items
    of type image) show as 120dp thumbnails under the row, open or not; a tap opens the image viewer.
  - tool cards (tap a row): one Copy at most, and a footer only when something is cut: "Show all N lines" opens the
    rest in place (up to 400 lines), past that "Open output / file / diff" goes to the full-screen viewer.
    - bash: `$` (success, danger on a failing exit), command, exit badge, Copy (the output). The output is a terminal
      on the canvas: SGR colours mapped to tokens (red danger, green success, yellow warning, blue/magenta/cyan the
      syntax function/keyword/operator colours, 256 and 24-bit as given), bold SemiBold, dim 60%; other escapes are
      dropped and a bare CR rewinds the line. Plain output stays ink2 whatever the exit code; without colours of its
      own, lines starting FAIL/ERROR/Error:/✗ get danger and ok/PASS/✓ success at 80%. Pi's "Command exited with
      code N" line is dropped (the badge says it). The tail shows first (summaries come last).
    - edit: the path (tap opens the file), +n −n, an open-in-full action; rows with old and new line numbers in two
      ink3 gutters (a deletion has no new number, an addition no old one), the sign, the code in its syntax colours
      over the add/delete tints, changed words of paired lines marked, unchanged runs folded to "⋯ N unchanged lines".
      The card lays out as two texts (gutters, code) with the row tints drawn behind them.
    - write: the new file as a numbered, highlighted preview with +n. read: path chip (tap opens the file), "L10–49"
      range chip, numbered preview in the file's syntax colours; Pi's "[Showing lines …]" note is dropped.
    - grep/find/ls: a line-count chip; grep lines as path ink1, line number ink3, text ink2 with a literal pattern in
      warning; listings as paths, folders ink1.
  - three or more consecutive finished Read/Search/Find/List steps fold into one step row, and `todo` calls at any
    count: check glyph, "Read 3 files, ran 1 search" or "Updated todos 7 times" (label ink2), the base names or task
    subjects (mono ink3), summed duration from 1 s, chevron; tap lists them. Run, Edit and Write are never grouped.
  - subagent calls (opensec-pi-subagents): each `Agent` call is a card, parallel calls a stack (see Subagents below).
    `steer_subagent`, `get_subagent_result` and `SubagentWorkflow` stay step rows: "Steer agent", "Agent result",
    "Workflow" + the workflow's name.
  - `todo` steps read as one line: "Todo  Added: Write tests", "Done: …", "Started: …", "Cleared the list".
  - code blocks: surface1, radius 12, 1dp line. Header 40dp: code glyph 14dp ink3, then the filename (monoSmall
    ink1, middle ellipsis) from the fence info string (`ts title="src/a.ts"`, `ts:src/a.ts`, `src/a.ts`) or the
    language (monoMeta ink3; shell aliases show as `shell`; untagged code shows the language detected from its first
    lines, else `code`); right side line numbers (toggle, 3+ lines), Wrap (only when a line overflows or wrap is
    on), Open (folded or 40+ lines) and the shared Copy button (check + "Copied", 1500 ms). Code Mono 12.5/19, 10dp
    vertical padding; line numbers from 3 lines on, Mono 11.5 ink3 right-aligned, then a 1dp line rule and 10dp to
    the code. No wrap by default: one horizontal scroll with 20dp edge fades into surface1.
    Over 20 lines the block folds to 14 with a 40dp bottom fade and a 44dp footer ("Show all 84 lines" + chevron)
    that expands in place up to 150 lines ("Show less" scrolls the header back into view) and opens the viewer
    beyond ("Open all 1,240 lines"). Streaming: the header shows Quad Write 12dp + a ticking line count, the code
    end pulses like prose, and a block past 20 lines shows its last 14 lines at a fixed height under a 32dp top
    fade; on close it crossfades (quick) to the folded start at the same height. Only complete lines are
    highlighted, each once: the tokenizer resumes from the previous line's state (open comments, strings,
    heredocs). Closed blocks are cached by content; blocks over 12,000 characters are highlighted on
    `Dispatchers.Default` and show plain meanwhile. ```diff / ```patch (and untagged text that is mostly a unified
    diff) render as diff rows with numbers from the `@@` headers, the path from `+++ b/…` (which also gives the rows
    their syntax colours) and `+n −n` in the header. ```mermaid fences go to `ui/diagram/MermaidBlock`.
  - markdown: CommonMark + GFM as models write it. Lists nest by indentation (a 26dp step per level, at most 8),
    ordered lists renumber from their start (`1. 1. 1.` reads 1, 2, 3), continuation paragraphs and code fences
    indent with their item, task items draw a 16dp box (ink3 outline; done: success outline, 18% fill and a tick).
    Bullets • ◦ ▪ by depth. Headings: h1 headline, h2 subhead, h3 prose SemiBold, h4 body SemiBold, h5 label
    SemiBold, h6 label SemiBold ink2; setext underlines count. Quotes: a 2dp lineStrong rail and their own blocks
    in ink2 (nested quotes, lists, code), at most 6 deep; GitHub alerts (`> [!NOTE]`, TIP, IMPORTANT, WARNING,
    CAUTION) colour the rail and add a 16dp icon + title (function colour, success, keyword colour, warning,
    danger). Footnote references are accent superscripts; definitions are rows with the label in accent meta and
    the text in body ink2. Autolinks (`<…>`, bare `https://`, `www.`), entities and every backslash escape work;
    `<br>`, `<sub>`, `<sup>`, `<kbd>` (as inline code), `<b>`, `<i>`, `<u>`, `<s>`, `<mark>` render, wrapper tags
    (`<details>`, `<div>`) are dropped and comments hidden. Soft line breaks stay line breaks (as on GitHub).
  - images: never fetched by themselves. A card (surface1, radius 12, 1dp line, 56dp row: image glyph ink3, the alt
    text body ink1, the address monoMeta ink3 middle-ellipsized, "Tap to load" meta ink3, an Open-in-browser
    action); tapping loads it (12 MB at most, long edge 2048 px) above the row, max 360dp tall. Inline images show
    their alt text in ink2 italic with the card under the paragraph.
  - math: `$…$`, `\(…\)`, `$$…$$`, `\[…\]`, `\begin{align}…` and ```math render natively (no WebView): serif
    italic variables, TeX atom spacing, fractions on the math axis, big operators with limits in display style,
    radicals, growing delimiters, accents, `\mathbb` / `\mathcal` / `\mathfrak` alphabets, matrices, `cases` and
    `aligned`. Display math centres in the column and scrolls sideways when wider. TeX that does not parse (or
    display math still streaming) shows as its source: inline in Mono ink2, blocks in a surface1 box with a
    "TeX" micro label. Currency (`$5 and $10`) stays text.
  - tables: a grid, not rows of loose cells. Column width = widest single-line cell + 24dp, clamped 64…240dp; spare
    width is spread in proportion so the table spans the card, wider tables scroll with edge fades. While streaming,
    widths only grow. Radius 12, 1dp line border, header surface1 metaStrong ink2, 1dp lineStrong under it; body
    Inter 14/20 ink1, cells 12×9dp, 1dp line between rows, no column lines, zebra ink1 @ 3% from 8 rows. `:--`,
    `:--:`, `--:` are honoured; all-numeric columns align to the end with tnum. ✓ / yes success, ✗ / no danger,
    a lone dash ink3.
  - inline code: Mono 0.88em ink1 on a surface3 chip (radius 4, line box inset 14%), one chip per line segment
    when it wraps. A narrow no-break space pads each side inside the chip so it never touches neighbours; code of
    24+ characters without spaces may break after `/ . _ -`.
  - after a turn: "Edited 3 files · ran 2 commands · 1m 12s" fold collapses its steps (at most three parts: edited
    files, commands, distinct files read, searches, agents; "N steps" when none apply; the time only when this phone
    watched the turn). "· 1 step failed" in ink3 after a 6dp danger dot; "· failed" in danger only when the turn ended
    in an error or abort; the final answer stays visible. Under it,
    chips for the files the turn edited or wrote (28dp, radius 8, surface1 + 1dp line, name in mono meta ink1, +n
    success, −n danger, tnum; at most 3, then "+k more"); a chip opens that file's diff. An opened fold draws a 1dp
    line rail at the slot centre through its steps (broken 4dp around each glyph, ending at the last one) and indents
    the prose between steps to the step text.
  - under that fold, in a session on this phone: "Review changes (3 files, +40 −12)" (difference glyph 16dp ink3,
    label ink2, counts success / danger tnum, " · undone" in ink3 after an undo; 48dp tall), only for turns whose
    checkpoints show changed files. Over remote control the newest turn that edited files says instead, in meta ink3,
    that review and undo work in sessions on this phone (the files are on the computer).
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

**Review changes.** A full screen over the session, like the viewer (sheet motion, predictive back): Close, "Changes"
and "3 files · +40 −12" (meta ink3, tnum), a 1dp line. Then the prompt (body ink2, three lines), "Undo this turn" and
"Restore to before" (secondary buttons with undo and history glyphs; off while Pi works, with a meta line saying
so), and after an undo a surface1 card "Undid 3 files of this turn." with Redo (accent). Each file: a 1dp line, a
56dp row (chevron, the name in mono meta ink1 over its folder in meta ink3, "new" success or "deleted" danger, +n
success −n danger tnum or "binary", and a 48dp undo button for that file); the first four files start open. An open
file shows its unified diff with the tool rows' renderer (gutter numbers, changed words, the file's syntax colours) on
surface1, one lazy row each, at most 1500 rows. The diff is between the turn's two snapshots: the start when the user
message reached the engine, the end when the agent settled (a steer ends one turn and starts the next).
- Undo restores the files the turn changed to the start snapshot. Files no longer as the turn left them (a later turn
  or the user changed them) open a sheet "Changed since": per file a 48dp Keep / Restore segmented control (Keep is
  the default), "k other files go back as they are", Cancel and "Undo n files". Otherwise it runs at once and a
  success snackbar says "Undid 3 files".
- "Restore to before" always asks: the files that differ from the start snapshot (later turns too; the user's own
  edits are the Keep / Restore rows), then "Restore and fork" restores them and forks the conversation at the message,
  which returns to the composer as "Edit and resend" does.
- Every restore first snapshots the project, so it can be undone: Redo puts the files back (same sheet when they
  changed since). A file over 5 MB is never in a snapshot, so a restore never deletes one ("too large to restore").
- Snapshots live outside the project, in `HOME/.pi/agent/checkpoints/<hash>.git` (a shadow git repository run with
  the pack's git; the project's own `.git` is untouched), honour .gitignore, skip node_modules-like folders and files
  over 5 MB, and keep the last 50 turns of the last 14 days. They run on a background queue; a failure is one warning
  snackbar ("Checkpoints paused: ...") until a snapshot works again.

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

**Dictation.** A 48dp mic beside Send: MicNone ink2 on a 34dp surface1 disc; listening is an accent disc with an
onAccent Mic (static, no pulse) and TalkBack's state "Listening". The first tap explains the microphone in a sheet
("Continue"), then Android asks; partial words appear in the draft as they are heard.

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

**Subagents.** The subagents extension (opensec-pi-subagents) runs each agent in its own in-process Pi session. Its
TUI widget is a component, so over RPC it arrives empty; the app builds `SessionState.subagents` (by `Agent` call id)
from what does arrive:
- the call's arguments (type, task, prompt, `run_in_background`);
- a foreground run's partial results, about 12 a second: `details` has the activity line, tool uses, turns, tokens
  and run time (kept in whole seconds so the state changes about once a second);
- the final result (the answer; for a background start, the agent id);
- `subagent-notification` messages when background runs end (status, metrics, a preview of the answer; a group
  names several runs), `subagents-interrupted`, and `get_subagent_result` results (the full answer);
- on this phone only, the agent's session file (`sessions/--<cwd>--/…jsonl`, one line per finished message, its
  header naming the parent session and its name "<type>#<agent id prefix>"). `SubagentTail` matches a run to its file
  by that id, or by its prompt before the id is known, and reads new complete lines every 400 ms while it runs (once
  more when it ends, and while its screen is open), on `Dispatchers.IO`, as `subagent_transcript` events.
Subagent session files (a `parentSession` header and that name) are not listed or resumed as project sessions.
Remote control gets the events only: progress of foreground runs, the start and end of background runs, never their
messages. Pi has no RPC to read or steer a subagent: steering and resuming go through a prompt to Pi.

- Card (surface1, radius 12, 1dp line; lineStrong while active): 32dp avatar (the type's initial in its tint on the
  tint @ 16%, radius 30%; tints glacier `#7CC4F0` Explore, iris `#A99CFF` Plan, mint `#9ED3C6` general-purpose, peach
  `#E5A97E` and lilac `#C9B8F0` hashed for others: never a status colour), the type (label ink1), a "background · id"
  pill (micro, surface3) for background runs, the task (meta ink2), the run time (meta ink3 tnum, ticking once a
  second while it runs, the final time after). Then the activity line: a 20dp-high slot under the avatar with Quad
  Agent 12dp in the tint (still Quad Think ink3 while queued; check success, cross danger, stop ink3 after, swapped
  with a 160 ms scale 0.7→1 in the live turn) and the activity (meta ink3, one line) that crossfades (quick, 4dp rise)
  at most every 450 ms, so a streaming reply does not flicker. Then the metrics ("4 tools · 12.3k tokens · turn 2",
  meta ink3 tnum). While it runs a 2dp band in the tint sweeps the top edge (1400 ms) and every line keeps one line,
  so the card never changes height. Done, the activity gives way to the answer's first lines as one paragraph (headings end
  with a colon, list items keep a bullet; up to 3 lines, ink2; the error in danger); a finished run counts "5 turns" and the card grows to them with the settle spring (`animateContentSize`). Tap opens the agent; press
  scale 0.98.
- Stack: two or more consecutive `Agent` calls (one message's parallel calls). Closed: one card with a 48dp header
  (up to 3 avatars, 22dp, overlapping by 8dp with a 2dp ring; "3 agents running", "1 of 3 agents running", "3 agents
  done", "3 agents · 1 failed"; the longest run time; chevron) over one 40dp line per agent (glyph, type, crossfading
  activity or task, time; tap opens it). Open (header tap): the header, then each agent's card 8dp apart; the switch
  resizes with the settle spring. The open state lives with the tool groups'.
- A finished turn folds its steps but not its agent cards: each is a unit of work of its own, and background runs
  outlive the turn.
  A background run's end shows where the notification lands: a step line "Explore finished" + task + time, tapping
  opens the agent.
- Detail screen (over the whole app; the session screen stays composed below, so its scroll position, draft and
  images are as they were): enters like a screen (24dp slide + fade, sheet), predictive back scales it to 0.9. Top bar:
  back, 28dp avatar, type (subhead) and task (meta ink3), Copy answer once there is one. A 36dp strip: glyph, state,
  run time, metrics; the model, "background" and the agent id under it (monoMeta ink3). The transcript is the main one's
  rows (user bubble for its prompt, thinking, tool rows and cards, prose, notices) built by `TranscriptModel` with every
  turn open by default and `Agent` calls as plain rows. Reversed list with a 32dp live line at index 0 (Quad Agent 16dp
  in the tint, the activity in shimmer, the run time; kept while done so nothing jumps); it follows the newest row only
  while the reader is at the bottom, else the 40dp jump button with the unseen dot. Each agent keeps its list position
  and toggles for the app's life. Over remote control (or when its file was not saved) it shows the prompt and the
  answer with one info line saying why.
- Reaching the agent (bottom bar, 1dp line above): a running background agent takes a message ("Message this agent",
  sent to Pi as a prompt that asks it to call `steer_subagent`); a finished agent with an id can be continued ("Continue
  this agent": Pi resumes it with `Agent` `resume`); a running foreground agent cannot be messaged (Pi is inside its
  call: a message would reach Pi only after it returns), so the bar says so and offers Stop (surface3 pill; a first tap
  arms "Stop turn?" in danger for 3 s, the second aborts Pi's turn, which stops the agent). A green line confirms a send
  for 3.5 s.
- TalkBack: a card is one node ("Explore agent, Find the parser code, running, Reading 3 files…") with the click label
  "Open agent".

**Settings.** A pinned 56dp bar (back, "Settings" in title; a 1dp line under it once the page scrolls), then a
search field ("Search settings") that narrows the page to matching settings: every query word must appear in the
group, the label or its keywords (`SettingsIndex`, so "font" finds Text size and "vibrate" finds Haptics); "No setting
matches" otherwise. Groups, in order, each a SectionLabel over a surface1 card (radius 18):
- Model providers: configured providers by display name (`ProviderCatalog`), Add provider.
- Pi on this phone: Default model (a 56dp row: the model and provider; it opens the model picker with the models of
  the open on-device session, and says to open a session when none is), thinking level for new sessions, steering
  and follow-up delivery (One at a time / All at once), Auto-compact, Auto-retry with the retry count (1 / 3 / 5 / 10),
  Skill commands, Block images. They are the engine's own settings (`agent/settings.json`, other keys kept); the
  queue, compaction and retry switches reach the open on-device session at once over RPC, the rest apply from the next
  session.
- Runtimes: the shipped runtimes on one line ("Node.js 22.23.4 · Python 3.14.10") and a row to the Runtimes screen.
- Appearance: theme tiles (seven, four a row, each drawn in its own theme: canvas, user bubble, prose with an inline
  code chip, a code card in the theme's syntax colours, the composer with the accent send button; selected = 1.5dp
  accent ring; the near-black themes show their canvas hex under the name in monoMeta ink3), accent circles ("Theme"
  is a split disc: the theme's own accent beside surface3), text size ("Text size" with its value in label ink2 beside
  it, A-glyph steps and a live prose preview), density (Comfortable / Compact).
- Motion and haptics: Reduce motion (Follow system / On), Haptics.
- Session display: show thinking expanded, tool output preview lines (6 / 12 / 24).
- Notifications: one 56dp row, "Notifications" with "Allowed" (meta success) or "Off" (meta warning); tapping opens
  the system page.
- Security: device key, Reset device key.
- About: app version, device name, the update status and its action (see Updates), "Check for updates automatically",
  licences (Inter, JetBrains Mono: OFL; Termux terminal libraries: Apache-2.0).
Segmented controls in Settings are 48dp tall.
Changes apply live, without recreating the activity, and persist in a small JSON file (read synchronously at
startup so the first frame has the right theme).

**Runtimes** (Settings > Runtimes, and "Runtimes" in a project's menu).
- Header like Settings (back, display title), then one line: the runtimes come with the app and run in every project.
- Installed: one card row per runtime: name in bodyStrong, version in monoMeta ink2, installed size right-aligned in
  meta ink3 (tabular figures); below, what comes with it ("npm 10.9.4", "pip 25.2") in meta ink3, then its commands as
  mono chips (surface2, 1dp line, Radius.sm). No Install/Remove: the runtimes are part of the APK (android/engine/CONTRACT.md).
- Packages and caches: npm cache and pip cache with their size and a secondary "Clear" (disabled at 0 B; "Clearing…"
  while it runs; a ConfirmSheet first, naming the size), then npm global packages and pip packages with their size
  only (removed with npm uninstall -g / pip uninstall). A failed clear says so in danger under the row.
- Sizes are measured on IO when the screen opens and after a clear; nothing animates while idle.
- TalkBack: a runtime is one node ("Node.js 22.23.4, npm 10.9.4, 150 MB. Commands: node, npm, npx"); each Clear is
  labelled "Clear the npm cache"; the title is a heading. Every row is at least 48 dp tall.

## Updates

The app is sideloaded, so it updates itself from GitHub releases. Source of truth: `pi-bolt.opensec.in/android.json`
(written by the Android release workflow), or GitHub's newest `android-v*` release when the site is down. Both are a
plain GET with no identifiers. The APK URL must be under `github.com/opensec-git/Pi-Bolt/releases/download/`.
- Check: on launch at most once a day (on IO, "Check for updates automatically", on by default) and from Settings >
  About > "Check for updates". About shows "Up to date (0.2.1)" or "0.2.2 is available · 109 MB", then "Checked 3 min
  ago".
- Home card (64dp, surface1, radius 12, after the engine banner): system-update glyph, "Pi-Bolt 0.2.2 is available",
  the size (meta ink3), Later (ink2, snoozes that version only) and Update (accent). The same card then shows the job:
  Quad Write + "54 MB of 109 MB · 49%" and a 2dp accent bar (Cancel); Quad Connect + "Waiting for network" when the
  connection drops; Quad Run "Checking"; a success shield and Install; danger soft fill with the reason on failure.
- Download sheet first: the size and the network it would use (Wi-Fi in ink2; mobile data or metered in warning).
  The download runs in a dataSync foreground service with a progress notification (Cancel), to the cache, and resumes
  with an HTTP Range request. Finished in the background, it posts "Pi-Bolt 0.2.2 is ready to install"; back in the
  app, the install sheet opens.
- Verify before installing, deleting the file on any failure: SHA-256 equals android.json's; the archive's signing
  certificate equals android.json's `certSha256` and the installed app's own; same package name; higher versionCode.
  Messages say what failed ("This update is not signed by OpenSec; not installed.").
- Install sheet: "Signed by OpenSec · checksum matches" (success), "Pi is working; updating stops it." (warning) while
  a turn runs, and when "Install unknown apps" is off, one line on why plus "Open settings"; returning with it allowed
  continues by itself. The file is hashed again and handed to Android's installer (ACTION_INSTALL_PACKAGE through a
  FileProvider), which always asks to confirm and then offers Open: Android stops the app to replace it and blocks a
  package-installer session or a MY_PACKAGE_REPLACED receiver from reopening it. Back in the app without an update
  (cancelled), the card offers Install again. The first launch of the new version says "Updated to 0.2.2" once.

## Diagrams

```` ```mermaid ```` fences render as diagrams (`ui/diagram/MermaidBlock`), offline. Flowcharts, sequence, class,
state, ER, gantt, pie and mind maps are checked; the other Mermaid types work the same way.
- Card: surface1, radius 12, 1dp line. Header 40dp: diagram glyph 14dp ink3, the type (monoMeta ink3), full screen
  and Copy (the source). The diagram fits the column, at most 480dp tall, centred, 12dp inset; tap opens it full
  screen.
- Streaming: each change to the source waits 300 ms for the next (a fence known to be closed draws at once), so a
  diagram appears when the model pauses or the fence closes, never per token. Until then a 160dp skeleton (Quad Write
  20dp ink3, "Drawing diagram"), or the previous drawing with a Quad Write 12dp in the header. An error in a fence
  that is still open is not shown.
- Error: the source as a normal code block, then "Diagram could not be drawn: <Mermaid's first error line>" (meta
  ink3, a 14dp danger error glyph).
- Theme: Mermaid's base theme from the palette: nodes surface3 with a solid border (ink1 @ 26% over surface3), text
  ink1, edges ink2, clusters surface2, notes warning @ 16%. Charts use a fixed categorical set (types, strings,
  numbers, glacier, success, warning, iris, rose, danger, diff fgs, ink2), full strength for pie slices and dimmed to
  42% over surface2 where text sits on them. Every theme is dark; `darkMode` follows the canvas luminance.
- Full screen (a dialog on canvas): pinch zoom 1x (fit) to 8x around the fingers, pan, double-tap to 2.5x or back,
  Fit, Share as PNG (FileProvider, cache/diagrams/ only) and Save as PNG (the system file picker, no permission). It
  shows the inline bitmap at once and swaps in one drawn at twice the screen density.
- TalkBack: "Diagram: Flowchart, Start" (type and first node label), click label "Open full screen".

**Rendering.** Mermaid 12.2.0 (MIT, `dist/mermaid.min.js` from npm) is fetched by the `fetchMermaid` Gradle task and
must match npm's sha512 integrity pinned in `app/build.gradle.kts`; it is packaged in `assets/diagram/` and never
committed. Licence: Settings > About > Mermaid licence (`res/raw/license_mermaid.txt`, with the projects it bundles).

One offscreen WebView, created on first use, never attached to a window, renders one diagram at a time. Mermaid draws
the SVG, the page rasterises it on a canvas (as a `data:` URL: an SVG image from a `blob:` URL never finishes loading
in Android WebView; with Inter embedded, since an SVG drawn as an image cannot see the page's fonts) and returns a PNG, which becomes a hardware bitmap shown with a plain `Image`. Bitmaps are cached by
SHA-256 of source, theme and render size (LRU, 1/8 of the heap up to 48 MB; failures in a small map), and a render
whose block scrolled away still finishes into the cache. So scrolling never hosts live WebViews: a diagram row is a
bitmap like any image, and nothing is drawn for rows flung past within 300 ms. Live WebViews per diagram would be
simpler but cost a renderer view each, re-layout on every scroll and cannot be skipped by Compose.

**Why a WebView, and how it is locked down.** Mermaid is JavaScript that needs a DOM to measure text; no native
renderer exists. The app has no other WebView, and this one is kept minimal:
- JavaScript is on only for the renderer page, `https://appassets.androidplatform.net/diagram/index.html`, served
  from the APK by `WebViewAssetLoader`. Its path handler serves exactly four files (the page, `render.js`,
  `mermaid.min.js`, Inter); everything else is answered 403 in `shouldInterceptRequest` (and logged as "Blocked
  request"), before the network stack. `blockNetworkLoads` and `blockNetworkImage` are on as well, and the page's CSP
  is `default-src 'none'` with only `'self'` scripts.
- No file access, no content access, no file-URL access, no DOM storage, no windows, no geolocation, no Safe
  Browsing lookups, no cache, no WebView metrics (`MetricsOptOut` in the manifest); every navigation is refused; the
  view is not focusable and hidden from accessibility. Debug builds only: page console lines go to logcat
  (`PiBoltDiagram`) and the page can be inspected over adb.
- Mermaid runs with `securityLevel: "strict"` (DOMPurify, no click handlers or scripts in diagrams) and
  `htmlLabels: false` (SVG text, no `foreignObject`), `look: "classic"`, and flat theme variables (no drop shadows or
  gradients).
- The source reaches the page only as a JSON string literal (`JsonText.quote`, which also escapes `<`, U+2028/2029
  and lone surrogates) in `piRender(id, "<json>", {theme}, {size})`; it is never concatenated into code.
- The only JavaScript interface is `PiBoltDiagram.result(id, width, height, type, label, png, error)`, typed. It
  ignores ids the app is not waiting for and caps the PNG (64 MB of base64, 24 M pixels).
- A dead renderer process (`onRenderProcessGone`) drops the WebView and fails its requests; the app keeps running and
  the next diagram starts a new one.

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
