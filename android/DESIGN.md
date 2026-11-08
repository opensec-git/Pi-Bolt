# Pi-Bolt Android: design language

Dark first, with one light theme (Paper). Ink-first hierarchy: text in four grey levels does the work, colour is reserved for state (success,
warning, danger, diff), and one accent marks what you can act on. Motion is quiet and purposeful: only the live turn
animates, history never does. Everything that moves respects "Reduce motion".

## Colour

One `PiColors` set per theme, provided through `LocalPiColors` and mapped onto Material 3 `darkColorScheme`
(`surfaceTint = Transparent`, tonal elevation 0) so stock components match. Elevation = a surface step plus a 1dp
line; no shadows.

| Role | Pi-Bolt (default, id `Graphite`) | Ember (warm) | Claude | Codex | Vercel | GitHub |
|---|---|---|---|---|---|---|
| canvas | `#1C1B19` | `#15130F` | `#1F1E1D` | `#0D0D0D` | `#000000` | `#0D1117` |
| surface1 (cards, composer) | `#232220` | `#1C1A16` | `#262624` | `#171717` | `#0F0F0F` | `#151B23` |
| surface2 (bubbles, sheets) | `#2A2926` | `#24211C` | `#30302E` | `#212121` | `#111111` | `#1C2128` |
| surface3 (pressed, menus) | `#32312E` | `#2C2822` | `#3A3936` | `#2A2A2A` | `#1A1A1A` | `#262C36` |
| ink1 (text) | `#ECEBE8` | `#EFE9DD` | `#F5F4EE` | `#ECECEC` | `#EDEDED` | `#F0F6FC` |
| ink2 (secondary) | `#B3B1AB` | `#B9B1A3` | `#C3C0B6` | `#B4B4B4` | `#A1A1A1` | `#B7BDC8` |
| ink3 (meta, lowest text) | `#9C9A94` | `#979084` | `#A6A49C` | `#929292` | `#8F8F8F` | `#9198A1` |
| ink4 (rules, never text) | `#5E5C57` | `#5A554D` | `#5E5D59` | `#545454` | `#454545` | `#3D444D` |
| line / lineStrong | white @ 7% / 12% | ink1 @ 10% / 17% | ink1 @ 10% / 17% | ink1 @ 10% / 17% | ink1 @ 12% / 20% | ink1 @ 10% / 18% |
| scrim | black @ 50% | black @ 56% | black @ 60% | black @ 60% | black @ 70% | black @ 60% |

Claude, Codex, Vercel and GitHub are palettes in the spirit of those products' dark modes, not their assets. Void
(a black AMOLED theme with the old blue-grey inks) was removed in 0.5.2, since the default is now black; a saved Void
choice loads as Pi-Bolt (`SettingsCodec` falls back on unknown names).

The app's own themes besides the default, each built in full by `Palette.kt`'s `full()` (surfaces, inks, lines and
overlays, accent, status, diff, syntax, ANSI, tints, chart colours), and each held to the role checks below:

| Role | Paper (light) | Contrast (high contrast) | Lime (soft dark) | Moss (forest dark) |
|---|---|---|---|---|
| canvas | `#FFFFFF` | `#000000` | `#1A1A1A` | `#0C100D` |
| surface1 / 2 / 3 | `#F7F7F7` / `#F0F0F0` / `#E8E8E8` | `#0A0A0A` / `#141414` / `#1F1F1F` | `#202020` / `#272727` / `#2D2D2D` | `#121814` / `#19201B` / `#212A23` |
| ink1 / 2 / 3 / 4 | `#171717` / `#525252` / `#666666` / `#949494` | `#FFFFFF` / `#DADADA` / `#B8B8B8` / `#7A7A7A` | `#F0F0F0` / `#B8B8B8` / `#9A9A9A` / `#5C5C5C` | `#E4EBE1` / `#A9B4A5` / `#8D998A` / `#4F5A4D` |
| lines, overlays | black @ 8 / 14 / 35%, press 6%, selected 8% | white @ 22 / 36 / 75%, press 12%, selected 16% | white @ 9 / 15 / 36%, 7%, 10% | ink1 @ 10 / 17 / 40%, 7%, 11% |
| scrim | black @ 24% | black @ 75% | black @ 60% | black @ 60% |
| accent / onAccent | `#0A0A0A` / `#FFFFFF` | `#FFFFFF` / `#000000` | OpenSec lime `#BAFC6B` / `#16210A` | fern `#B4D39A` / `#0C100D` |
| success / warning / danger | `#11733F` / `#8A6410` / `#B83430`, soft 9% | `#6EE7A8` / `#FFCB5C` / `#FF8A85`, soft 16% | `#5FD39A` / `#E8B04B` / `#FF8681`, soft 12% | `#5FD39A` / `#E8B04B` / `#F2706B`, soft 14% |
| diff fg (bg) | `#116B40` / `#A2302C` (8%) | `#B4F5D2` / `#FFC2BF` (16%) | `#9BE6BF` / `#F7A9A5` (11%) | `#9BE6BF` / `#F7A9A5` (11%) |
| ANSI blue / magenta / cyan | `#4D4D4D` / `#8C3A73` / `#39605F` | `#E0E0E0` / `#F0B8E0` / `#C8E8E8` | `#C4C9D4` / `#D7A6C8` / `#A6DCC3` | `#8FB3C7` / `#C9A3C8` / `#8FC7B0` |
| tints | the light set (below) | the shared set | the shared set | the shared set |
| chart colours | the light set (below) | the shared set | the shared set | the shared set |

- **Paper** is the desktop's light mode (`tokens.css` light tokens and its light highlight.js colours). Surfaces step
  down from white, because white is the top. Status, type and the tints are deepened until they read as text on its
  sheets: the desktop's green `#16834F`, amber `#A77B12` and red `#C93B37` measure 4.2, 3.4 and 4.4:1 on surface2.
  It is the one light theme (`PiColors.isLight`): Material's light scheme, dark status and navigation bar icons,
  Mermaid with `darkMode` off. The window colour before the first frame stays the default's black.
- **Contrast** is high-contrast mono, for bright light or low vision. Every text ink, status, diff and syntax colour
  is at 7:1 (AAA) on canvas, cards and sheets. lineStrong is 3:1, so a border alone reads. Keywords are SemiBold.
- **Lime** is a soft neutral dark, lighter than Codex, with the OpenSec brand lime as its accent only. Lime never
  appears in code or state. Its danger is Claude's lighter `#FF8681`, so a danger button passes on its sheets.
- **Moss** is a green-black forest dark: lichen-grey ink, a soft fern accent (yellow-green, apart from the mint
  success) and earthy code colours.

On a light theme the Iris and Glacier accents take deep variants of the same hue (`#5B4BD1`, `#0B6E9E`, white on
them). The pastels would not read as links on white.

**Pi-Bolt (the default)** is a warm charcoal (the owner's 2026-11-07 references, `~/pbw/research/UI-REFERENCES.md`):
a charcoal canvas with surfaces a warm step above it, quiet warm-grey ink, hairlines and overlays as white alphas, the
warm white ink as the only accent, green, amber and red for state only, and no blue. The enum id stays `Graphite`, so
saved settings keep the default; its label is "Pi-Bolt". True black stays available as Contrast and Vercel.

| Role | Value |
|---|---|
| canvas, surface1, surface2, surface3 | `#1C1B19`, `#232220`, `#2A2926`, `#32312E` |
| ink1, ink2, ink3, ink4 | `#ECEBE8`, `#B3B1AB`, `#9C9A94`, `#5E5C57` |
| line, lineStrong, lineFocus | white @ 7%, 12%, 30% |
| press, selected | white @ 7%, 9% |
| scrim | black @ 50% |
| accent, onAccent | `#ECEBE8`, `#1C1B19` |
| success, warning, danger (soft @ 12%) | `#4CC38A`, `#D4A64A`, `#F4817D` |
| diff add / delete fg | `#86DFB0`, `#F5A3A1` |
| syntax | the desktop's monochrome highlight.js set, its greys warmed: comment `#9C9A94`, operator and tag `#B3B1AB` |

The references' inks (`#A8A6A1`, `#77756F`) are lifted: ink2 to `#B3B1AB` so it stays AA on a pressed menu row (4.56:1),
ink3 to `#9C9A94` so it stays AA on surface3 (4.62:1). Danger is lifted from the desktop's `#EF6461` to `#F4817D`, AA
on its 12% fill over sheets (4.69:1 on surface2). The text selection is the accent at 28% (`SELECTION_ALPHA`, every
theme): at 32% ink1 measured 4.34:1 on a warm surface3. The splash (`splash_bg`) is the canvas.

Before the redesign the default was Pi-Bolt Desktop's pure black (`#000000`, `#0E0E0E` / `#161616` / `#1D1D1D`, ink
`#EDEDED` / `#A1A1A1` / `#858585` / `#595959`, white accent); before 0.5.2 a blue-grey Graphite (`#0B0C0E`, ink2 `#A3A8B0`).
`defaultThemeHasNoBlueCast` lets the default's greys lean warm but never cool (blue ≤ green ≤ red, channels at most 10
apart).

**Subagent tints** (`PiColors.tint0` to `tint4`, `tints[n % 5]`) are the desktop's `--tint-0` to `--tint-4`,
converted from OKLCH to sRGB. They have the same lightness and chroma, with hues kept clear of the status colours,
the brand and blue. They are shared by every theme. A tint marks which agent is which and never stands for state. Each one
passes 3:1 as a glyph on every surface, and AA as an initial on its own 16% fill over the canvas and cards.

| Token | Name | OKLCH | sRGB |
|---|---|---|---|
| tint0 | apricot | 0.80 0.13 55 | `#FEA668` |
| tint1 | rose | 0.78 0.14 0 | `#FF8FB4` |
| tint2 | orchid | 0.76 0.15 325 | `#E08EE5` |
| tint3 | violet | 0.74 0.14 298 | `#B696F7` |
| tint4 | seafoam | 0.80 0.11 185 | `#5CD5C7` |

Paper uses `LightTints`: the same hues at lightness 0.50 (seafoam 0.47, chroma 0.09), which are `#994A00`,
`#A33460`, `#8E3B94`, `#6F4BAC` and `#006B61`. The desktop's light tints (L 0.54 to 0.60) are 3.1 to 4.3:1 as an initial.

**Chart colours** (`PiColors.chart0` to `chart3`, `charts[n % 4]`) draw the series of the mobile UI kit's `chart`, one
per series in order; authors never choose them. They are their own set: never the accent, a tint (tints are agents
only) or a status colour, and a series colour never stands for state. The set is Okabe–Ito's colour-blind-safe blue,
orange, reddish purple and bluish green, and series also differ by dash (solid, dashed, dotted, dash-dot) and the
legend, so colour is never the only cue. Each one passes 3:1 as a line or bar on every surface of every theme, and
every pair stays apart (CIELAB ΔE at least 15) for normal vision and under simulated protanopia, deuteranopia and
tritanopia (Machado 2009; `chartColoursReadAndStayApartInEveryTheme`).

| Token | Name | Dark themes | Paper (`LightCharts`) |
|---|---|---|---|
| chart0 | blue | `#56B4E9` | `#005E93` |
| chart1 | orange | `#E69F00` | `#665A05` |
| chart2 | reddish purple | `#CC79A7` | `#964966` |
| chart3 | bluish green | `#009E73` | `#089656` |

Paper deepens each hue until it passes 3:1 on its greys; the orange becomes an ochre there because a deeper orange
falls too close to the purple for protanopes. Axis labels, the legend's text and the TalkBack summary stay in the inks.

Overlays: press = ink1 @ 7%, selected = ink1 @ 11%, focus line = ink1 @ 40% (Pi-Bolt: white @ 8.5%, 10%, 32%).

Accent (independent of theme): **Theme** (default: the theme's own accent: Pi-Bolt white `#FAFAFA` / `#000000`, Claude coral `#E08566` / `#1F1E1D` (lifted
from `#D97757`, which measured 4.24:1 as link text on Claude's sheets),
GitHub blue `#4493F8` / `#0D1117`, ink1 / canvas for the others), **Ink** (accent = ink1, onAccent = canvas),
**Iris** `#A99CFF` / `#120F2A`, **Glacier** `#7CC4F0` / `#06202E`. Accent marks the primary button, send button, focus ring, selection, links and
the working hairline. Never status.

Brand: the Pi-Bolt logo's colours, only on the app mark (launcher, splash, Home, empty state). The mark is the logo's
bolt (`ic_pibolt_logo`, cropped from docs/images/logo.svg): an upper blade in yellow (`#F7C04A` lit face, `#E9A42C`
shaded face) and a lower blade in blue (`#4C96EA` lit, `#2A78D6` shaded). Yellow and blue are both brand colours and
never stand for state or action. `PiColors.brand` (`#FFD84D`) is the single-colour fallback where the logo can't be drawn.

Status: success `#5FD39A`, warning `#E8B04B` (needs you, steer), danger `#F2706B` (Claude: `#FF8681`, its
lighter canvas needs it); soft fills @ 14% (Claude's danger fill @ 12%). Pi-Bolt uses the desktop's `#4CC38A`,
`#D4A64A`, `#EF6461` with soft fills @ 12%.
Diff: add bg success @ 11% / fg `#9BE6BF`, delete bg danger @ 11% / fg `#F7A9A5` (Pi-Bolt: @ 9%, `#86DFB0`, `#F5A3A1`); changed words in paired lines
success / danger @ 22%. Add and delete never differ by hue alone: a `+` / `−` sign column, a solid 3dp gutter bar
(success @ 60%) for additions and a hatched one (2dp stripes every 4dp, danger @ 70%) for deletions. Context rows
ink2, line numbers ink3, hunk rows monoMeta ink3 on surface2.
Syntax: one palette per theme (`SyntaxColors`): keyword, type (capitalised names and built-ins such as `string`),
function (calls and declarations), string, number, constant (`true`, `null`, ALL_CAPS), operator, attribute
(annotations, decorators, object and YAML keys, HTML attributes), tag, regex, escape (escapes and `${…}`), variable
(`$x`, `@x`), comments italic; punctuation ink2, base text ink1, the shell `$` prompt success SemiBold, diff lines in
the diff colours. Pi-Bolt uses the desktop's monochrome highlight.js theme: white keywords and constants in Medium
weight (`keywordWeight`), near-white calls, sand types, green strings, orange numbers, greys for the rest, no blue.
Codex uses the "Pi" palette (violet keywords, yellow types, blue calls, green strings, orange numbers, a neutral
comment grey); Paper is the desktop's light highlight.js colours, deepened for AA; Contrast is bright hues at 7:1 with
SemiBold keywords; Lime is lavender / apricot / mint / coral (no lime in code); Moss is ochre / straw / lichen / moss /
clay; Ember is warm and earthy, Claude coral / sand / sage, Vercel pink / purple / blue /
green, GitHub uses its dark "prettylights" roles. Only `text`, `log` and `output` fences stay plain.

| Role | Pi-Bolt | Pi | Ember | Claude | Vercel | GitHub |
|---|---|---|---|---|---|---|
| keyword | `#FFFFFF` Medium | `#C9A0F5` | `#F28E7E` | `#E8956F` | `#FF6B98` | `#FF7B72` |
| type | `#DCC38C` | `#E8C67E` | `#F2C46A` | `#E3C590` | `#C49BFF` | `#FFA657` |
| function | `#F0F0F0` | `#7FB6F5` | `#9CC78B` | `#9DBAE8` | `#6CB4FF` | `#D2A8FF` |
| string | `#9FD4A8` | `#A3D58E` | `#C8CC6E` | `#B6CF8F` | `#6CD58B` | `#A5D6FF` |
| number / constant | `#E9B77C` / `#FFFFFF` Medium | `#E9A77C` | `#D99BB8` | `#D9A3D9` | `#F5B562` / `#6CB4FF` | `#79C0FF` |
| operator | `#A1A1A1` | `#86CBDD` | `#F0A35C` | ink2 | ink2 | `#FF7B72` |
| attribute | `#C4C4C4` | `#E9A77C` | `#8DB8A8` | `#E3C590` | `#C49BFF` | `#79C0FF` |
| tag | `#A1A1A1` | `#F08E9A` | `#F28E7E` | `#E8956F` | `#6CD58B` | `#7EE787` |
| regex / escape | `#9FD4A8` / `#E9B77C` | `#86CBDD` | `#9CC78B` / `#F0A35C` | `#8FCBC0` | `#F5B562` | `#7EE787` |
| variable | `#C4C4C4` | `#F08E9A` | `#8DB8A8` | `#9DBAE8` | `#FF9F6B` | `#FFA657` |
| comment | ink3 | `#8F8E8C` | ink3 | ink3 | ink3 | ink3 |

| Role | Paper | Contrast | Lime | Moss |
|---|---|---|---|---|
| keyword / constant | `#000000` Medium | `#FFFFFF` SemiBold / `#FFB98A` | `#C3B1F5` / `#F5A7A0` | `#D6A86C` / `#E59A7A` |
| type | `#7D600F` | `#FFE08A` | `#F2C48D` | `#E3CF8C` |
| function | `#171717` | `#FFFFFF` | `#E8E8E8` | `#9FC7B8` |
| string / regex | `#2F7A3E` | `#9FF0C0` | `#A6DCC3` | `#B9D38A` / `#8FC7B0` |
| number / escape | `#A5571A` | `#FFB98A` | `#F5A7A0` / `#F2C48D` | `#E59A7A` / `#E3CF8C` |
| operator, tag | `#5A5A5A` | `#DADADA` / `#FFE08A` | ink2 / `#F2C48D` | ink2 / `#D6A86C` |
| attribute, variable | `#404040` | `#E0E0E0` / `#F0C8E8` | `#C3B1F5` / `#F5A7A0` | `#C4B48A` / `#D9A3A0` |
| comment | ink3 | ink3 | ink3 | ink3 |

Contrast (WCAG 2.1, checked by `ContrastTest` for every theme and accent):
- ink1, ink2 and ink3 pass AA (4.5:1) on canvas, surface1, surface2 and surface3. ink3 is the lowest text and was
  raised where it missed AA on surface2 or surface3 (old Graphite `#7D838C` → `#878D96`, Pi-Bolt `#808080` →
  `#858585`, Ember `#8E877B` → `#979084`, Claude `#9C9A92` → `#A6A49C`, Codex `#8F8F8F` → `#929292`). ink3 on surface3
  (menus, snackbars, inline code) now measures 4.57 to 5.38:1; it was 4.03 to 4.44 in Graphite, Ember, Claude and Codex.
- Rule: no text below ink3, and no ink4 text anywhere. On surface3 prefer ink2 for anything longer than a meta line.
- Status colours pass on canvas, surface1 and surface2; danger passes on dangerSoft over cards and sheets (the danger
  button), which is why Claude has its own danger.
- onAccent passes on accent for every accent in every theme, and accent text (links, text actions) passes on canvas,
  surface1 and surface2. Snackbar actions sit on surface3, so they are ink1, not accent.
- A Notice's text (ink1, ink2 under a title) passes on its fill over canvas, cards and sheets; its glyph passes 3:1.
- Button labels pass: secondary ink1 on surface2, danger on dangerSoft, the disabled primary's ink3 on surface3.
- Vercel cards (surface1) are one step lighter (`#0F0F0F`) so they stand off the pure-black canvas.
- The app's own themes (Pi-Bolt, Paper, Contrast, Lime, Moss) are checked by role in every accent (`ownThemesRolesPass`): ink1 and ink2 AA on each surface,
  plain, pressed and selected; ink3 at least 3:1 as a glyph on all of those; the accent's label AA on it, its fill 3:1
  against the surface (a button's shape) and AA as link text; ink1 AA on the text selection. `defaultThemeHasNoBlueCast`
  keeps the default's greys and everything drawn from them free of a cool cast, `defaultThemeIsTheWarmCharcoal` pins
  its values, `subagentTintsReadInEveryTheme` checks the tints, `chartColoursReadAndStayApartInEveryTheme` the chart colours, `elevationStepsStayDistinct` keeps each surface a step further
  from the canvas in every theme (lighter on dark themes, darker on Paper; each step of the own themes at least
  1.05:1), `onlyPaperIsLight` pins the one light theme, `contrastThemeIsAaa` holds Contrast to 7:1, and the window
  colour must be the default canvas. Paper, Contrast and Lime greys carry no tint either.
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
| monoMeta | 12 / 17 | 400 | paths, hashes, hex values and other mono meta lines |
| thinking | 14.5 / 22 | 400 ink3 | reasoning text |
| largeTitle | 28 / 34 | 600, -0.01em | the redesign's screen titles |
| rowTitle | 17 / 24 | 400 | the redesign's list rows, the composer pill |
| rowMeta | 15 / 20 | 400 | the line under a row title, tile labels and counts |

System font scale is respected; the transcript is capped at 1.3x, and the Text size setting (-1 … +2 steps) applies
on top.

## Space, shape, size

Spacing 2, 4, 8, 12, 16, 20, 24, 32, 40 dp (`Space.xxs … x4`): the desktop's 4px rhythm (`--sp-0` to `--sp-8`, plus 40).
The redesign's side gutter is 20 (`Space.page`: Home, and the session's transcript, composer, todo card and preview
chips); elsewhere it is still 16; sheet and header text inset 20; gap
between turns 20; between steps 6.
Radii are the desktop's 4 / 6 / 10 / 14, assigned by role:
- xs 4: inline code, tags (`--radius-xs`).
- sm 6: chips, small buttons (`--radius-sm`).
- md 10: cards, code blocks, option rows, menus, snackbars, fields (`--radius-lg`, the desktop's card, menu and
  toast).
- lg 14: user bubble, composer, setting groups (`--radius-xl`, the desktop's composer and bubble).
- xl 14: the desktop's dialog radius; sheets moved to pill (28) in the redesign.
- full: send button, pills.
- tile 16: status tiles, media cards (the redesign).
- bubble 22: the user bubble (the redesign).
- media 12: code blocks, tables and image cards in the transcript (`RadiusMedia` in `components/MediaRadius.kt`, until
  the theme's `Radius` takes it).
- pill 28: the pill composer (a full round at its 56dp one-line height, kept as it grows), sheet top corners,
  buttons and snackbars (capsules).

Before 0.5.2 the values were 4 / 8 / 12 / 18 / 28. The desktop's user bubble is 14 with its bottom-end corner at
4 (`--radius-xs`).
Compact density: Home rows 48dp (`homeRow`), option rows 48dp (never under the touch target), turn gap 14, step
gap 4, prose 15 / 23.
Touch targets at least 48dp. Every timeline row has a 20dp leading slot (spinner or glyph) and a 12dp gap.
Icons: one family, Material Rounded (`Icons.Rounded`, `Icons.AutoMirrored.Rounded`). Sizes (`IconSize`): 14 beside
meta text, 16 beside labels and in small buttons, 20 leading glyphs in rows, notices and buttons, 22 toolbar
actions (`IconAction`), 32 hero glyphs of empty and permission states. A row, menu item, sheet action, Settings group
and empty state leads with a glyph when the glyph names the thing faster than its words (a key for providers, a
history clock for recent prompts, search-off for "no match"); a row whose group heading already carries the same
glyph does not repeat it. Leading glyphs in rows are ink3 (`ListRow`), in menus ink1 or the row's state colour.
Logos: see Logos below; they are the only non-Rounded marks.
Strokes: 1dp lines, 1.5dp focus ring, 2dp queue bars, 3dp diff gutter.

## Logos

Providers and model makers show their mark, as Pi-Bolt Desktop does (`src/lib/modelLogos.tsx`): monochrome only,
tinted with the theme's ink, never a brand colour, so every theme and accent keeps one palette and passes contrast.
- Source: Lobe Icons (`@lobehub/icons-static-svg`, MIT; the `<name>.svg` monochrome marks), converted to
  `res/drawable/ic_provider_<slug>.xml` by `android/tools/provider-logos.mjs` (pinned version and sha512, strict
  converter: paths only). Never edit the drawables by hand; add a brand to the script's list and to `Brand`
  (`ui/providers/ProviderLogos.kt`), then rerun it. Licence: Settings > About > Open-source licences > Lobe Icons.
- Which mark: `ProviderLogos.forProvider(id)` (Pi's provider ids, then the longest known hyphen prefix:
  `qwen-token-plan-cn` is Qwen), `forEndpoint` for custom endpoints (the base URL: a known API host, Ollama's 11434 or
  LM Studio's 1234, a server named in the host or name; never a guess from the id) and `maker(modelId)` for models
  (the family name first, `claude`, `gpt`/`o3`, `gemini`, `llama`, `qwen`…, earliest in the id; then the
  organisation, `meta-llama/…`, Bedrock's `us.anthropic.…`). A model with no known maker takes its provider's mark;
  an unknown provider or custom endpoint the server glyph (`Dns`). Claude models carry Claude's mark and the Anthropic
  provider Anthropic's; Gemma takes Google's "G", Gemini and the Google provider Gemini's; xAI is Grok's mark.
- Where and how big (`LogoMark`, `ProviderBadge`): the composer's run-settings chip, 14dp ink1 before the model name;
  model sheet rows, 16dp ink2 (the current model ink1); provider rows (Settings > Model providers, the provider
  sheet, custom endpoints), 16dp ink2 centred in the 20dp glyph slot, no tile; the key step's title, 20dp ink1; the
  session sheet's Model row and Settings' Default model, 16 and 20dp ink2. Always decorative for TalkBack: the name
  beside it is read.
- Layers some marks draw at partial alpha (Azure, LM Studio) are decoration; each mark's main shape is full ink.

## The Quad spinner

A Canvas-drawn 3x3 grid that shows what the agent is doing. Grid spans 11 units (`u = size / 11`): cell 3u,
gap 1u, corner radius 0.7u. Floor alpha 0.16 for outer cells, 0.34 for the centre, so the shape always reads.
Sizes, on the icon tokens, one per place: 14dp (`IconSize.xs`) in a timeline row's 20dp slot and inline in headers,
16dp (`sm`) in the status line and top bars, 20dp (`md`) in a card's or list's leading slot, 44dp connecting and
empty-state hero. Subagents follow the same places (see Subagents).

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
| tap | 120 | press overlay, press scale, colour (`--dur-1`) |
| quick | 140 | chevrons, chip select, icon swaps, crossfades, menus (`--dur-2`) |
| standard | 180 | expand/collapse, message rise, folds (`--dur-3`) |
| sheet | 240 | sheets, viewers, screen transitions (`--dur-4`) |
| exit | 110, sheets 0.7 × enter | anything leaving (`--dur-exit`; sheets and screens at 0.7x) |
| settle | 360 | meters, counters (`--dur-slow`) |
| glyph | 180 | a finished step's spinner becoming its check or cross (the desktop's pop-in) |
| fade | 120 | reduced motion: every transition becomes this crossfade |
| pulse | 500 | half the end-of-text caret pulse (`--dur-blink`) |
| sweep | 1400 | the working hairline, a running card's top band (`--dur-pulse`) |

All durations come from `PiMotion` (`DesignRulesTest` refuses a numeric `tween` outside the reviewed exceptions).

Easing (the desktop's): enter `cubic(0.16, 1, 0.3, 1)` (`--ease-out`), exit `cubic(0.4, 0, 1, 1)` (`--ease-in`), in
place `cubic(0.65, 0, 0.35, 1)` (`--ease-in-out`). Before 0.5.2 the values were tap 90, quick 150, standard 220,
sheet 300, settle 400, glyph 160, pulse 450, exits at 0.7x, enter `(0.05, 0.7, 0.1, 1)`, exit `(0.3, 0, 0.8, 0.15)`
and in place `(0.2, 0, 0, 1)`.
Springs for drags and settles: damping 0.8 / stiffness 380 (default), 0.6 / 800 (fast), 1.0 / 1600 (colours,
alpha). No overshoot except sheet drag release.

- Screens: shared-axis X (24dp slide + fade, sheet duration); back reverses; predictive back previews the pop
  (`popExitTransition` scales to 0.9 with the gesture). Host card → session uses a shared-bounds transition, with the
  host name and status dot as shared elements.
- Live turn only: the user bubble rises 10dp and fades in; new steps rise 6dp. History and restored sessions never
  animate. Streaming text is updated at most once per frame and newly revealed text fades in over 120 ms; a 6x12dp
  ink3 block pulses at the end of the live paragraph (900 ms) instead of a caret.
- Auto-scroll sticks to the bottom only while the user is there; otherwise a scroll button appears centred above the
  composer, 8dp up: a 36dp round button (surface3, 1dp lineStrong, arrow 18dp ink1) in a 4dp ring of canvas so text
  passing under it is cut cleanly (the desktop's), 48dp touch. When content arrived while the reader was away it
  widens (quick, in place) into a pill, arrow + "New messages" (metaStrong ink1). Enter: rise 16dp, scale 0.95→1,
  fade, quick; exit 0.7x. Every send jumps to the bottom. The live status line
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

## Components

`ui/components` is the kit; screens compose it and never restyle it.

**The redesign kit** (`components/Glass.kt`, 2026-11-07), after the owner's references:
- `Modifier.glass(shape, colors)`: a vertical gradient (a touch of ink over surface2 at the top, the canvas on Paper,
  surface2 at the bottom), a 1dp lineStrong ring and a 1dp inner highlight (ink1 @ 8%, fading to the sides) along the
  top. No shadows.
- `GlassIconButton`: a 44dp glass circle in a 48dp target, a 22dp ink1 glyph (back, more, search, +); press 0.96.
- `GlassPill`: a floating glass pill, 40dp (48dp target), 16dp sides ("View changes +2 −2").
- `StatusTile`: one tile of a 2x2 grid; radius tile (16), surface1 + 1dp line (lineStrong when active), 104dp; a 20dp
  glyph top-left in its own colour, the label (rowMeta ink1, two lines before an ellipsis at a large font scale) and its
  count (rowMeta ink3, tnum, on the label's last line) bottom-left; read as "label, count". A row of tiles shares one
  height.
- `InsetRow`: the plain list row: no card, 14dp vertical padding, at least 56dp; an optional 24dp leading slot (outline
  glyph ink2, a Quad), the title (rowTitle ink1), an optional meta line (rowMeta ink3), then count (ink3), unread dot
  (8dp accent) and chevron (ink3); a 1dp hairline under it inset from the text start to the 20dp gutter; `current` sets
  it on a soft radius-md surface2 highlight. Never a letter tile.
- `PillComposerShell`: the pill composer: radius pill (28), glass, 56dp at least, 4dp inside; `ComposerCircle` (a 36dp
  circle on the selected overlay, 20dp ink1 glyph, for + and the mic; `active` while listening: the accent fill and an
  onAccent glyph, "Listening" to TalkBack) and `ComposerPrimary` (one 36dp slot: Send, an onAccent arrow on the accent,
  once there is text; Stop, a surface3 disc with a lineStrong ring and a 12dp ink1 square, while Pi works and the field
  is empty; otherwise voice input, a waveform glyph on the accent; Send at 18% when there is no voice action;
  `ComposerPrimaryMode` decides, the swap crossfades) in 48dp targets on one centre line.
- `DotWave`: the dot-matrix waveform of voice input: 9 rows of 2.5dp dots every 6dp, each column lit in a band around
  the centre whose height follows the audio level (0..1) times two slow travelling sines (`DotWaveMath`); 14% floor,
  faded edges; still under reduce motion.
- `WorkingLine`: a 14dp Quad and the shimmering label ("Working"), for live lines and rows.
- Screen chrome on the kit (`components/GlassChrome.kt`): `GlassBar` (56dp on the canvas: a glass leading button and
  glass actions on the 20dp gutter, a compact subhead title with a monoMeta subtitle that fades in with
  `titleVisible` once the large title scrolled away, hidden for TalkBack meanwhile; a `center` slot for other content),
  `PageTitle` (largeTitle ink1, a heading, 20dp in, an optional rowMeta ink3 meta line), `InsetSectionHead` (the quiet
  head of an inset list: rowMeta ink3, a tabular count, an optional text action, 44dp, 16dp above), `GlassChip` (a glass
  capsule action, 40dp in a 48dp target, a 16dp glyph and the label; 40% when disabled) and `LineCounts` ("+12 −3",
  success / danger, rowMeta tnum). Every row of glass top-bar buttons (`GlassBar`, `LargeTitlePage`, Home, Chats,
  the session, Scan) pads its sides by `GlassBarInset` (18dp), so each 44dp circle's edge sits on the 20dp gutter,
  in line with the page title. Full-screen overlays (the output viewer, a turn's Changes, a subagent, a diagram) are
  `GlassBar`s too: a glass ✕ or back, glass actions, and `GlassCopyButton` (the copy glyph that flips to a success
  check for 1.5 s) where the bar copies.
- Tokens: type `largeTitle`, `rowTitle`, `rowMeta`; `Space.page` 20; `Radius.tile`, `bubble`, `pill`, `media` (12,
  images and preview cards in the transcript); `SOFT_PRESS`
  0.96; `PiMotion.stagger(step)` (120 ms + 70 ms per step, 8 steps at most) for staggered entrances. `DesignRulesTest` checks the rules below on the
UI sources (with a reviewed exception list that may only shrink) and `ContrastTest` their colour pairs.

**Buttons.** Capsules (`Radius.pill`) since the redesign (they were the desktop's radius-sm rectangles), the label in
label weight (500), optional leading icon 6dp before it; press = ink overlay and 0.97 scale. A label that does not fit on one line wraps to two, centred, never cut.

| Kind | Fill / text | Use |
|---|---|---|
| `PrimaryButton` | accent / onAccent (disabled: surface3 / ink3) | the one next step of a screen or sheet |
| `SecondaryButton` | surface2 + 1dp lineStrong ring / ink1 | the alternatives, Cancel |
| `GhostButton` | none / ink2 | a quiet third choice in a button row |
| `DangerButton` | dangerSoft / danger | only the step that destroys (confirm sheets) |
| `TextAction` | none / ink1 or accent, label weight | actions inside rows, notices and fields |
| `IconAction` | 48dp, 22dp glyph ink2 | toolbars and row ends; always a content description |

Sizes (`ButtonSize`): Large 48dp (default; screens and sheets; padding 16), Small 36dp inside cards and rows ("Clear",
"Save" in an inline form; padding 12), still a 48dp target. Disabled secondary, ghost and danger buttons show content
and line at 40%; the disabled primary is surface3 / ink3.
Button rows are `ButtonRow`: Cancel (secondary) first, the action last, equal widths, 12dp apart, inset 20dp in sheets.
When any label would not fit its half on one line (font scale 1.3 on a 360dp phone), the row stacks full width with
the action on top. A standing destructive action on a screen is a row in danger glyph that opens a `ConfirmSheet`,
never a red button.

**Rows.** `ListRow`: optional 20dp glyph (ink3) or leading slot, the title in body ink1, a meta line (meta ink3, or a
state colour), then trailing content or `RowEnd.Chevron` (auto-mirrored, so it points the reading way in RTL) / `RowEnd.External`; at least 56dp, the whole row is the
target, no press scale. In sheets rows bring the 20dp inset (`RowInsetSheet`); inside a card they take the card's
padding (`RowInsetCard`, rounded press). `SwitchRow` is the same row with `PiSwitch` (accent track and onAccent
thumb on; surface3 with a lineStrong ring and an ink2 thumb off), read as one switch with its On / Off state. `ValueRow`: a read-only fact, label body ink2 and value body ink1 at the
end, 48dp. `RowDivider`: 1dp line between rows of a card. Row titles are never bodyStrong: weight is for headings.

**Grouped lists** (`components/Grouped.kt`, the redesign's Settings): `LargeTitlePage` (a glass back button on a 56dp
bar, the title in largeTitle as the first thing in the scroll; once it scrolls under the bar the bar shows it small
and centred over a hairline), `InsetGroup` (an optional rowMeta ink3 header, a surface1 card with radius tile and a
1dp line, clipped so a row's press fills its band, an optional meta ink3 footer; 16dp from the window's sides, 16dp
between groups), `GroupRow` (a 30dp `GlyphTile`, the title in rowTitle, an optional meta line, a short value in
rowMeta ink3 at the end, trailing content, a chevron or open-in-new; at least 52dp; a hairline from the text start),
`GroupSwitchRow` (the same row with `PiSwitch`, so every switch sits on one end column), `GroupControl` (a block for a
control inside a group) and `PillSearchField` (a 48dp surface1 pill with the search glyph and a clear button).
`GlyphTile` is the icon chip, monochrome: a 30dp radius sm + 2 surface3 tile with an ink2 glyph (or a logo). Only a
status colour (danger, success, warning) colours the glyph, and only where the row reports that state (Reset device key,
a connected computer); sections are never colour-coded (`GlyphTone`). `ChoiceSheet` picks one of a few values (a sheet of `InsetRow`s, an accent check on the current one; a pick
applies and closes). `PiMenu` / `PiMenuItem` are the popup menu: surface3, radius tile, a lineStrong ring, rowMeta
items with 20dp glyphs; a picker's current item ends in a 20dp accent check (`checked`, read as Selected).

**Cards and groups.** `PiCard`: surface1, 1dp line, radius md, 16dp padding: a surface step plus a line, never a
shadow. `CardSection`: a `SectionLabel` (metaStrong ink3, sentence case, never capitals) over a radius-md card with
16dp sides and 8dp top and bottom; controls inside start with a `ControlLabel` (body ink1, value in meta ink3).
`Tag`: micro on surface3, radius xs, for a source or state; TalkBack reads its text once. A `SectionLabel` (and so a
`CardSection`) may lead with a 14dp ink3 glyph: Settings' groups do, as the desktop's settings sections.

**Screens.** Settings, Providers and Runtimes are `LargeTitlePage`s. Another pushed screen starts with `ScreenTopBar`:
56dp under the status bar, a glass back button, the title in title style
(a heading) with an optional meta line, actions at the end, a 1dp line once the content scrolls. `ScreenIntro` is
its one paragraph (body ink2). Display type is for empty-state heroes and Project/Home titles only.

**Elevation** (the desktop app's model): one surface per layer and a 1dp alpha ring for its edge, no shadows. Canvas;
cards and sheets surface1 with line (cards) or lineStrong (sheets); snackbars surface2 with lineStrong; pressed and
selected fills surface3.

**Sheets and dialogs.** `PiSheet` (the dialog layer: surface1 with a 1dp lineStrong ring down the sides and round the
28dp (`Radius.pill`) top corners, the theme scrim, predictive back) with `SheetHeader` for everything modal: the
title in largeTitle (a heading), an optional body ink2 subtitle, an optional glass back button before it and a glass
close button (36dp in a 48dp target, from `LocalSheetClose`) at its end, so every sheet closes the same way;
`ConfirmSheet` (no close button: Cancel is in its button row) for consequential yes/no. A sheet step has one primary
action, full width. No `AlertDialog`. The drag handle (36x5 ink4, 10dp above and 4dp below) is the kit's own, so TalkBack reads it as "Drag handle" with a Close action. ModalBottomSheet lifts
the whole sheet above the keyboard; `PiSheet` also stops it below the status bar, so a tall sheet's list shrinks and
scrolls instead of running under the clock. Fields and cards in a sheet sit 16dp in, text and button rows 20dp.
Menus are `PiMenu`: surface3, a lineStrong ring, radius tile.

**Feedback.** Snackbars (`PiSnackHost`, `show(kind)`): a glass capsule (`Radius.pill`) 20dp from the sides, a 20dp
kind glyph, rowMeta ink1 text (four lines at most) and an ink1 action; a host sits above the navigation bar and the keyboard. `Notice` is the inline and banner form: radius md, 20dp glyph in the kind colour, optional title
(bodyStrong), text ink1 (ink2 under a title) and text actions under it; info and success on surface1 with a line,
warning and danger on their soft fill with a 40% line. Text is never in the status colour. No Toasts.

**Empty, loading, error.** `EmptyState`: unboxed, as the workspace's empty views: an optional 48dp glass circle with a
22dp ink2 glyph, the title (rowTitle ink1, a heading), one or two lines (rowMeta ink2) and at most one action, centred;
never a lone grey sentence. `LoadingRow`:
a 16dp Quad Think and the label (body ink3), polite live region; never a Material spinner. `ErrorState`: a danger
`Notice` with the reason and "Try again" when it may help; an error never looks like an empty list.
`ProgressLine`: 2dp, surface3 track, accent fill, determinate only.

**Fields.** `PiTextField` (surface1, radius md, lineStrong turning into the focus line, danger on
error) for text;
`SecretField` for keys, tokens and secret values (mono, obscured, Password keyboard without suggestions, Paste while
empty, Show / Hide once filled, 52dp). `FieldLabel` above (metaStrong ink2), `FieldHint` below (meta ink3, the error
in danger, announced politely). A field's keyboard action does what the sheet's primary button does (Go pastes a
pairing link, Done saves a token). Secret values live in plain `remember`, never saved state, and are cleared after use.

**Segmented and tabs.** `Segmented` / `SegmentedSlots`: surface1 track with a line and 2dp inset, the selected segment
surface3 with a lineStrong ring sliding (quick, in place), label ink1 on / ink3 off, role Tab with its Selected state in
a selectable group; 48dp in Settings and forms, 36dp only in dense bars.

## Screens

**Home.** The redesign's Inbox (the owner's 2026-11-07 references), built from the kit; production copy only, no
explanatory lines. Top to bottom, on the 20dp page gutter:
- The title row under the status bar: the brand is the title, the bare bolt (`BrandMark` 26dp, unchanged, no circle) at
  the cap height and "Pi-Bolt" in largeTitle (a heading), then `GlassIconButton`s on the same centre line: Settings
  (gear) and + ("Add", once there are projects) whose menu holds New project, Clone from git, Import a zip and Add a
  computer.
- Engine banner (only when it has something to say), as before, on the gutter.
- The update offer as a slim line (`UpdateCard`): "Pi-Bolt 0.5.3" (rowTitle ink1) over "Update available · 109 MB"
  (rowMeta ink3), Later (ink2) and Update (accent) at the end; downloading adds the 2dp progress line; only a failure is
  filled (dangerSoft, danger @ 40% line). No glyph, no card.
- First run (no model or no project): the setup steps on the canvas: two 56dp numbered steps ("Connect a model" with
  the connected provider's name once done, "Create a project"), the next step's primary button full width, then New
  project and Clone from git as ink2 text actions. No paragraph.
- With projects, a 2x2 grid of `StatusTile`s from real state, 12dp apart (one row of four in a window 600dp wide or
  more), one tint per tile and every glyph 20dp, counts ink3: Working (a Quad in tint4, Run and animated while any
  session works, a still frame at 0), Waiting for you (a Quad in tint0, Wait while a session asks), Projects (folder in
  tint3) and Computers (computer in tint1). Working and Waiting open their sessions in a sheet (`HomeListSheet`, the
  rows of Recents; "Nothing is running." when empty); Projects scrolls to its section; Computers opens and scrolls to
  its section, or the Add sheet when there is none. They enter once per launch with the stagger (fade, 8dp
  rise). Home keeps to a 760dp column, centred, in a wide window.
- "Recents" (a `HomeHead`: rowMeta ink3): the live sessions first (`RunningRow`: the Quad in its mode, what it was asked,
  "Working · calc-parser"), then each project's newest session (`ContinueRow`: the still ink4 Quad as the bullet, the
  session's name or first prompt, "calc-parser · 3h", an accent dot when something new happened, the open session on
  the soft current highlight). Up to 3 projects' sessions; a running project is not repeated.
- "Projects  3": `ProjectCard` rows (the live Quad or the still bullet, never a folder glyph or letter tile; the title;
  "6 sessions · 4h" or "Open · 6 sessions"; ages on Home's rows are all the short form, as in Recents and Chats; a 14dp pin when pinned; chevron). Long press: Pin, Rename, Delete.
- "Computers  N", a head with Add (opens `AddComputerSheet`: "Run /remote-control on it, then scan its code.", Scan a
  pairing code, Paste a pairing link) and a chevron that folds the section (folded once there are projects, open when
  there are none, when the engine is unavailable or a computer session is live). `HostCard` rows: status dot or live
  Quad, the name (the one given on this phone first), "Connected · repo · LAN · Tailscale · v1.1.1" (`Connections`:
  Tailscale for `*.ts.net` and 100.64.0.0/10, Tunnel for other `wss://`, LAN for other `ws://`), and a 48dp Manage
  (…; also a long press) that opens `ManageComputerSheet`: the status, each endpoint with its kind, "Name on this phone"
  (a field and Save; the name is `Host.alias`, kept when the computer connects again) and Forget computer (danger glyph,
  then the confirm sheet: "To revoke this phone on the computer too, run /remote-control devices there."). With no
  computer, one row: "Add a computer".
- The persistent pill at the bottom (`PillComposerShell`, over a canvas fade, above the navigation bar and the
  keyboard), shown once a model is connected: a project chip above it, left edges aligned and 8dp apart (`GlassPill`:
  folder glyph, "calc-parser", chevron; its menu, a `PiMenu`, lists Chat (chat glyph), the projects (folder glyphs)
  and New project, an accent check at the end of the current one; with no project it reads "New project" and opens
  that sheet), then the pill: + (New project; with Chat picked a `PiMenu` of a chat's actions instead: Photos, Paste
  image while the clipboard holds one, Voice and New chat, each opening a new chat with the draft and the images in
  its composer, never sent; `ProjectRules.pillPlus`), the field "Ask Pi…" (rowTitle; up to 4 lines) and
  `ComposerPrimary` (voice while empty: Android's speech recognizer fills the field, never sends; Send with text). The
  chip defaults to the project worked in last (one with no session running); sending opens a new session there with
  the text as its first prompt. The list's bottom padding clears chip, pill and the navigation bar, so no row is ever
  under them.
- Rows are `InsetRow`s: hairlines inset to the text, none under a section's last row. Nothing below the title is placed
  until the folders were read, so the sections never jump.

**New project sheet.** Title, then a 48dp segmented "Empty folder | Clone from git". Clone shows the repository URL
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
- Landscape: Home, the project (with its overlays), the preview and the full-screen terminal keep the side navigation
  bar and a display cutout's side (`safeDrawing`, horizontal); the canvas still fills the window.
- Top bar (`GlassBar`, 56dp on the canvas): a glass back button ("Projects") on the 20dp gutter; then the shown tab's
  own glass button (Sessions: search and manage sessions; Terminal: Secrets) and the glass ••• (Pin, Rename, Export as
  zip, Secrets, Runtimes, Delete in danger). Sessions, Files and Changes open on the project's large title (largeTitle
  ink1, the shared element from Home, a 16dp pin glyph when pinned) over `projects/x` and, in a git repository, the
  branch glyph + the branch from `.git/HEAD` (monoMeta ink3); it scrolls with the list, and the bar's compact title
  (subhead) fades in once it has gone. The terminal has no large title: its bar always names the project.
- Bottom bar (`ProjectNavBar`): one floating glass capsule (radius full), 20dp from the window's sides and 8dp above the
  navigation bar, at least 64dp (it grows with large text); the four tabs are one selectable group, so TalkBack reads
  "Tab, 2 of 4". Four equal items: a 22dp glyph over the label (metaStrong ink1 selected, meta ink3 otherwise); the
  selected one sits on a soft surface3 capsule (crossfading), press 0.96. Changes carries the
  number of changed paths (micro onAccent on accent, at least 16dp wide, "99+" past 99, ringed in surface2); Sessions
  (Pi working in this project) and Terminal (a shell running) an 8dp success dot. TalkBack: tabs with Selected state
  and the badge in words ("4 changed files", "Shell running", "Pi is working"). It is read when the project opens
  (git status once), not only when Changes is shown.
- The bar steps aside while the keyboard is up (the commit field, a new file's name and the terminal keep the room)
  and while a full-screen view (file, diff, history) covers the project. In a window under 480dp tall (landscape) the
  top bar also steps aside while the keyboard is up, so the terminal keeps its lines.
- Each tab keeps its own scroll position; the shown tab survives rotation and process death.
- Lists are lists: `InsetRow`s with hairlines inset to the text, under quiet `InsetSectionHead`s (rowMeta ink3, a
  count), never boxed cards; code (hunks, a commit's files) keeps its cards.
- Empty, loading and error states share one pattern: an `EmptyCard`, calm and unboxed at the 20dp gutter (a 22dp ink2
  glyph in a 48dp glass circle, the title in rowTitle ink1, one line in rowMeta ink2, then its actions), the kit's `LoadingRow` ("Reading
  sessions…") while a list, a file, a diff or the history is read, and the kit's `ErrorState` (with Try again) when a
  read fails: an error never looks like an empty list, and no view is ever blank while it loads.
- Sessions: New session (primary, full width, at the gutter) first, then the "Sessions  6" head over `SessionRow`s
  (`InsetRow`): a 20dp Quad in the leading slot (live in its mode while Pi works, warning while it waits for an answer,
  a still ink4 frame otherwise), the session name or first prompt (rowTitle ink1), and under it "Working · main",
  "Waiting for you · main" or "Open · 24 messages" for the open session (which sits on the row highlight) and
  "2h · 24 messages" for the others (`SessionRowRules`); new activity is the 8dp accent unread dot at the end. TalkBack
  reads the line in words. While Pi-Bolt cannot run, a tap says so. Without any, "No sessions yet"
  (EmptyCard, chat glyph). Tap resumes; the live one resumes without reopening. The search sheet stacks its two
  48dp segmented controls (Names | Messages, then the sort) so neither clips at 360dp and font scale 1.3.
- Files: one folder at a time, under a sticky glass toolbar (56dp on the canvas): the breadcrumb (rowMeta; the folder
  shown ink1, the way back ink2, auto-mirrored chevrons ink3 between), then 40dp glass buttons + (new file, folder and
  import) and sort (and hidden files). An empty folder is an EmptyCard ("No files yet" at the root, "This folder is
  empty" inside, or the count of hidden files) with New file and Import (secondary, side by side). Rows are `InsetRow`s:
  the type glyph (folder ink2, files ink3) in the leading slot, the name (dot files at 62% ink1, which lands on ink3),
  size or "N items" (rowMeta ink3, tnum) at the end, a chevron for folders; long press is also a TalkBack action
  ("File actions").
  Folders first, then files, by name. Tapping a file opens the full-screen viewer (first 512 KB; binary files say so).
  Back goes up a folder before it leaves the screen.
  The "+" menu also imports: "Import files…" (ACTION_OPEN_DOCUMENT, several) and "Import a folder…"
  (ACTION_OPEN_DOCUMENT_TREE) into the folder shown; names that exist get " (2)". A folder's long-press sheet has
  "Export as zip", a file's "Share"; the project's ⋮ menu has "Export as zip". The export sheet: "Leave out .git and
  dependencies" (a 56dp checkbox row, on by default), then Share (secondary) and Save to… (primary, ACTION_CREATE_DOCUMENT).
  A transfer shows a floating glass card above the bottom edge (radius tile, 20dp from the sides): "Importing 3 files ·
  40%" (rowTitle; TalkBack announces only the label, once), "2 files · 1.2 MB of 3 MB · src/a.kt" (monoMeta ink3),
  Cancel (danger text action, 48dp) and a 3dp accent
  progress line on surface3 (no fill when the total is unknown: never an indeterminate animation). Progress updates
  at most every 100 ms. New project has "Import a zip as a project…" (accent text action) under its buttons.
- Changes (git, through the engine's own git off the main thread with timeouts): outside a repository an EmptyCard
  offers "Initialise git". Inside, under the large title, the branch as a calm header (no card): the branch glyph, the
  branch in rowTitle ink1 with a chooser glyph over "origin/main · 2 to push · 1 to pull" (rowMeta ink3), tap for the
  Branches sheet, and at its end a `GlassPill` with the working tree's "+N −M" (success / danger, rowMeta tnum; from
  `git diff --numstat`, staged plus unstaged; untracked files are not counted until added; TalkBack: "12 lines added,
  1 line removed"). While an operation runs, a `WorkingLine` (Run Quad, git's progress shimmering). Then the actions
  as `GlassChip`s: Pull, Push, Pull request (GitHub remotes when signed in) and History; a rowMeta ink3 line says what
  is missing to push. Sections ("Conflicts", "Staged" with Unstage all, "Changes" with Stage all; `InsetSectionHead`
  with the count) list `InsetRow`s: the change letter in the leading slot, mono SemiBold with no tile, as the desktop's
  `.gx-st` (added/untracked success, deleted/conflict danger, modified warning, renamed ink2; TalkBack reads the word),
  the file name over its folder, the file's "+N −M" and a 48dp + / − button to stage or unstage. Tap opens the diff;
  long press asks to discard (ConfirmSheet, danger; untracked files are deleted). The commit flow comes right under the
  branch as soon as something is staged (and stays while a message is written), so committing never needs a scroll
  past the file list: "Commit 2 files" (rowTitle, a heading), the message field (2 to 8 lines), "Ask Pi" (secondary,
  sparkle glyph: Pi drafts from the staged diff in the project's session and the answer fills the field) and Commit
  (primary, commit glyph). Clean: "No changes" (EmptyCard, check glyph, title only); outside a
  repository "Not a git repository" (EmptyCard, branch glyph) with Initialise git; "Loading changes…" (LoadingRow)
  until git answers. Without a git identity
  the commit opens "Who is committing?" (name, email; prefilled with the GitHub name and no-reply address).
- Closing: a full-screen view over the workspace (file viewer, diff, history) closes with ✕ ("Close"); a step inside
  one (a commit opened from the history) goes back with ← ("Back to history"). `OverlayBar(back = …)` holds the rule;
  it is a `GlassBar` with glass buttons and chips.
- Diff viewer: full screen (fade and 10% rise, sheet duration). Bar: glass ✕, file name over its path, then (unstaged)
  a glass Discard button and a Stage chip, or an Unstage chip. Under it "Not staged" / "Staged" / "New file" (rowMeta
  ink3) and the file's "+N −M" on a `GlassPill`. Each hunk is a card (radius lg, 16dp in): its `@@` header (monoMeta ink3 on surface2, a hairline under it) with Stage hunk / Unstage
  hunk and, unstaged, a discard glyph (confirmed), then the rows renderer (DiffBlock: two gutters, sign column, solid
  and hatched bars, syntax colours, word marks). Binary files, renames without changes and a file with no changes left are EmptyCards; very large diffs say so;
  "Loading diff…" while git answers, a failed read an ErrorState.
- History: full screen (glass ✕), the large title "History" over the branch (the bar's title fades in once it scrolled
  away), "Loading history…" first, "No commits yet" as an EmptyCard, a failed read an ErrorState with Try again;
  `InsetRow`s: a commit glyph (ink2 when the commit carries a ref, ink4 otherwise), the subject, "abc1234 · 2h · author"
  and the first ref as a `Tag` ("main +1"), 100 at a time. A commit (glass ←, back to the list) opens its subject
  (title), body, author, date (rowMeta ink3) and a copy-hash button, then a card per file (path in monoSmall, "+N −M")
  that folds its diff (folded first when more than three files).
- Branches sheet: a new-branch field (mono, validated like `git check-ref-format`) with Create, then "On this phone"
  (current first, on the row highlight with a success check) and "On the remote" (switching creates a tracking branch);
  branches are `InsetRow`s with the branch glyph.
- Pull request sheet: title, description (Markdown), "Merge into" capsule chips (the repository's default branch first),
  "Ask Pi" (Pi drafts title and body from the branch's commits) and Create (pushes first when needed). Done, it shows
  "Pull request #12", the link in accent mono, Copy and "Open on GitHub".
- Terminal: see Terminal below; the pane fills the tab between the top bar and the bottom bar (or the keyboard), so the
  extra-keys row always sits on the keyboard.

**Terminal.** Termux's TerminalView in JetBrains Mono on canvas: text ink1, cursor accent, the 16 base colours mapped
to tokens as in tool output (red danger, green success, blue / magenta / cyan `ansiBlue` / `ansiMagenta` / `ansiCyan`...), no cursor blink. A 56dp
bar: tab capsules (`WorkspaceTab`, the editor's too: 36dp drawn in a 48dp row, radius full, glass when shown (fading
in), no fill or border otherwise; "1 · title" in monoSmall, ink1 shown, ink3 otherwise and
when the shell ended; a 16dp close glyph with a 48dp target; the shown tab scrolls itself into view),
+ (new terminal), then 40dp glass buttons: Paste, Text size (one menu: Larger, Smaller, "13 sp · or pinch"; 8 to 28 sp, also by
pinch), full screen / leave full screen. Under it the Preview pills. The extra-keys row (canvas; each key a 48dp target with a glass face, radius md, monoMeta ink1 or
a 20dp arrow glyph, press 0.96; the keys share the width where all twelve fit, landscape and tablets, and scroll on a phone): Esc, Tab, Ctrl, Alt, the four arrows, |, ~, /, -; Ctrl and Alt fill with accent (onAccent text)
while on and apply to the next key only. Long press selects text (Termux's floating Copy / Paste bar); tap shows the
keyboard (character input, no suggestions). An ended shell says "[Process completed - press Enter]"; Enter closes the
tab. No tab yet: "A shell in this project" (EmptyCard, terminal glyph), "bash with git, curl and rg", Start a terminal (primary). Full screen: the same pane above
the keyboard, status and navigation bars padded. Shells keep running off screen; a low-importance "N terminals
running" notification (Close all) holds them in the background.

**Preview.** `GlassPill`s "Preview · localhost:5173" (an 8dp success dot, label ink1, 48dp touch) in
the terminal and above the composer of an on-device session, while a dev server listens. The preview screen: a
`GlassBar` with glass back, the URL in a glass capsule (monoMeta ink2, middle ellipsis), glass Reload and Open in browser; a 2dp `ProgressLine` while loading, filled from
WebView's own progress (determinate, never an indeterminate bar); the page
in a WebView (http://localhost and 127.0.0.1 only; tapped links elsewhere open in the browser). When the dev server does not
answer (stopped, still starting), an EmptyCard over the page says so ("Nothing answers on localhost:5173", what to
check, the WebView's reason) with Reload (primary) instead of WebView's own error page.

**Secrets.** A project page: a `GlassBar` (glass back; "Secrets" fades in once the large title scrolled away), the
large title "Secrets" over the project's name, a `ScreenIntro`
("Encrypted on this phone and kept out of the model's messages. Commands in this project can read them.") and "Applies
to new terminals and sessions" (rowMeta ink3). None yet: an `EmptyState` (key glyph, "No secrets yet"). Then "Variables  N"
(`InsetSectionHead`) and 64dp rows with hairlines inset to the gutter: the name in mono ink1, the value masked as eight dots (monoMeta ink3, fixed length), Show / Hide, Edit, Delete
(ink3 glyph; the confirm sheet carries the danger). Add secret (primary) opens a `PiCard` form: Name (`FieldLabel`,
mono, validated: letters, digits, _, not the engine's own variables, the rule or the error in `FieldHint`), Value
(`SecretField`), then small Cancel (secondary) and Save (primary).
Write .env file (secondary) confirms first: values land in plain text in the project and git would commit them; .env
is added to .gitignore in git projects. The window is FLAG_SECURE on this screen.

**File viewer and editor.** Full screen over the project (or the session, for a file on a tool card). Top bar
(`GlassBar`): glass ✕, the name and "path · type · N lines · read-only · unsaved" (monoMeta; warning when unsaved),
glass Find (accent while open), Edit (pencil; files that can be edited) or Save (editing, an accent `GlassChip`),
••• (Ask Pi about the selection / this file, Go to line, Wrap lines, Copy path, Copy
text, Share). Open files are tabs once there are two: a 48dp strip of `WorkspaceTab`s (as the terminal's: glass
capsules when shown), a 7dp warning dot when unsaved, the name (monoSmall, at most 160dp) and a ✕ (48dp target).
With the keyboard up in a window under 480dp tall the strip steps aside. Menus in the workspace (project, files,
viewer, terminal) are one `WorkspaceMenu`, the kit's `PiMenu` (as every popup menu: the session's ⋯, the running
sessions switcher), with 48dp rows and a 20dp glyph slot. Find bar: the query
(mono), "3 of 12" (meta, live region), ↑ ↓ ✕, then 48dp text toggles "Aa" (match case), "ab" underlined (whole
word), ".*" (regex) in accent with a 18% accent fill when on (TalkBack: switches), and ⇄ for Replace: a second row
with the replacement and "Replace" / "All" (accent text actions); a bad regex or group shows in danger meta below.
Matches are accent 22%, the current one 50%. Editing: the same monoSmall text, colours as in code blocks (updated
as you type), the bracket pair at the cursor on surface3, a 48dp bar over the keyboard (surface1, top line): Undo,
Redo, Insert indent, Go to line, "Ln 12, Col 4" (monoMeta ink3; "No colours over 400 KB" first for big files).
Files over 1 MB open read-only. Leaving or closing a tab with unsaved edits asks first ("Discard"). The editing bar's
fill runs under the navigation bar with its keys above it, and sits on the keyboard when it is up. "Opening x…"
(LoadingRow) while a file loads. Markdown's Rendered | Source is a 48dp segmented control.

**Share into Pi-Bolt.** Another app's share sheet lists Pi-Bolt for files, images and text. A sheet: "Share to
Pi-Bolt" with "2 images" / "1 file" / "Text"; a segmented choice when there is more than one (Attach to chat |
Import files, New project | Import files, Import files | Add to chat), the projects as 52dp radio rows (folder glyph,
title, accent check), then the primary action. Attach and Add to chat open the project's session with the images in
the tray or the text in the composer. Text that is a Pi package link (a pi.dev, npm or GitHub URL, or a copied
`pi install <source>`) says "A Pi package link" and defaults to Install package | Add to chat; Review package opens the
package's install sheet (below), so a share never installs anything by itself.

**Packages sheet.** "Packages", then a 48dp segmented Installed | Browse. Installed is the list as before (the source
field and Install, the packages with on/off tags, the built-ins). Browse: a `PillSearchField` ("Search Pi packages",
debounced 350 ms), then "Most downloaded" or "N found" (F2Section) and one `F2Row` per package (Inventory2 glyph, the
name on one line, "v1.2.0 · 19k weekly · description" in meta, a success "installed" tag), "Show more" (secondary) for
the next 20, and a meta footer naming the source (the npm registry's `pi-package` keyword, as pi.dev lists). Loading
is a `LoadingRow`, offline or a registry error an `ErrorState` with Try again, no results an `EmptyState`. Only plain
HTTPS GETs: registry.npmjs.org, api.npmjs.org, data.jsdelivr.com (file lists), raw.githubusercontent.com.

**Package install sheet** (a Browse result, a `pibolt://install?source=` link, a shared link): the name and
description in the header; a well with the source (monoSmall), version, weekly downloads and licence; "Compatibility":
one `Notice` per finding (danger: `os`/`cpu`/`libc` exclude linux-arm64 glibc, Install becomes a disabled "Not for this
phone"; warning: native code built at install, prebuilt binaries or platform packages without linux-arm64 glibc,
`.node` files, native dependencies; info: install scripts, with their commands), or one success notice when nothing
was found; "What it adds" (an `F2Row` per extension, skill, prompt and theme: Extension, AutoAwesome,
ChatBubbleOutline, Palette); "Links" (Repository, pi.dev; an open glyph trails); with a project in context, "Install
for" All projects | This project (48dp; this project needs the project trusted, else a meta line says to trust it
first); the trust warning, or the install's progress (`LoadingRow`), success notice or error log; then Cancel and
Install (Done once installed). Nothing installs before Install is tapped.

**Private registry (packages sheet, Installed, "Advanced").** A collapsed `F2Row` ("Advanced", Tune glyph, the
saved scope and URL or "Private registry" in meta, a chevron) opens: one meta line, "Registry URL" (`PiTextField`,
default https://registry.npmjs.org/; https only, http just for 127.0.0.1/localhost), "Scope (optional)" (@company),
and "Token" as a `SecretField`, or, once saved, a Key glyph with "Token saved" and a danger "Remove". Remove registry
(secondary) and Save (primary). The token is sealed with the Android Keystore (prefs `npm-registry`, excluded from
backup and transfer) and never shown again; it reaches npm only during an install, in a 0600 `.npmrc` under
no_backup that npm reads as its global config and that is deleted afterwards. With a scope, Browse shows a 48dp
segmented npm | @scope and searches the private registry (no keyword filter, the token sent only to it); a registry
without npm's search API says to install by name. A private package's detail is read from that registry only. pi.dev pages are not an app link (pi.dev
cannot be verified for Pi-Bolt), so they open in the browser; sharing one to Pi-Bolt is the way in.

**Provider sheet (first run and Add provider).** Opens by itself once, 450 ms after the first frame of Home, when no
provider has a key. First run: the bolt on a 56dp glass disc, then "Connect a model" (largeTitle) and one line ("Pi-Bolt
runs on this phone with your API key. The key stays here."); Add provider: the same title, "The key stays on this
phone." A `PillSearchField`, then plain `InsetRow`s under "Popular" and "More providers" (rowMeta ink3 heads): the
provider's mark (20dp ink2), name, note, a success check for configured ones; last, "Custom endpoint" with the server
glyph. First run ends with a ghost "Later"; every step has the glass close. Picking one slides to the key step
(standard, enter easing): the header with a glass back ("All providers"), the mark 22dp ink1 and the provider's name,
"Paste your API key.", the `SecretField` (mono, obscured, Password keyboard, no autocorrect; Paste when empty,
Show/Hide when filled), a lock line "Stays on this phone, sent only to <provider>." (or the error in danger), "Get a
key at console.anthropic.com" (accent, opens the browser) and one full-width primary, Save key. The custom endpoint
step is the same: back, "Any OpenAI-compatible server.", its fields, one primary "Add endpoint". The key lives only in
the sheet's memory (never saved instance state), is cleared after saving and never logged.

**Setup steps (Home, first run).** Two numbered steps on the canvas, as on the desktop onboarding: the current step a
28dp accent disc with its number in onAccent, a finished one surface3 with an ink1 check (and the provider's name in
meta ink3), a later one a lineStrong ring with the number in ink3 (green stays for state, not progress); labels in
body. Then the next step's primary button and the others as ink2 text actions.

**Settings > GitHub.** A group. Signed out: "Sign in with GitHub" (only when the build has an OAuth client id) and
"Use a personal access token". Signed in: the account row (name and @login; how and which scopes) and a danger "Sign
out" row (confirmed: the token stays valid on GitHub until revoked there). Device flow sheet: "Enter this code on
GitHub.", the user code in display mono on a surface2 radius-tile well (TalkBack spells it), Copy, "Open
github.com/login/device" (primary), then a 16dp Wait Quad and "Waiting for your approval"; errors in danger with Try
again; the glass close cancels. Token sheet: what scopes to pick, a link to create one, a `SecretField`, the storage
line as its `FieldHint` (Keystore, never shown again, never logged; the error in its place) and Save, which checks the
token against GitHub first (the keyboard's Done does the same).

**Pair confirmation.** A sheet (not an AlertDialog) for links opened from outside the app: "Pair with <host>?" or
"Connect to <host>?" and "Only continue if you just ran /remote-control on this computer.", "Address(es)" over a
surface2 radius-tile card of the endpoints (monoMeta ink2), a warning `Notice` when it ends the current session, Cancel
and Pair / Connect. Scanning and pasting stay one-step. The paste sheet ("Paste a link", "Run /remote-control copy on
the computer."): the link field (monoSmall, URI keyboard; Go connects), its error as a `FieldHint`, "Paste from
clipboard" and one primary, Connect.

**Scan.** Full-bleed camera, canvas-tinted scrim with a 260dp rounded window, corner brackets that settle in on open.
At the top, on a canvas fade: a glass back button, "Scan the code" (largeTitle) and "Run /remote-control on your
computer." (rowMeta ink2). At the bottom a glass status capsule: Quad Connect + "Looking for a code", the reason in ink1
when a code is refused, a check and "Code read. Connecting…" with a success haptic; TalkBack hears each change (polite
live region). "Paste a link instead" (secondary) under it. Landscape: the capsule and the button move to a 320dp column
at the end side and the window (at most 260dp, never under the title) centres in the rest. Camera refused: a 72dp
glass disc, "Allow the camera" and one line; "Allow camera" asks again; once Android stops asking, "Open settings"
opens the app's system page and the camera starts on return.

**Session.**
- Top bar 56dp: back, computer name (subhead) with the session's cwd basename beneath (meta, mono), Quad 16dp in the
  current mode, overflow (Disconnect, Forget computer with confirmation, Session info).
- Subtitle under the name: model · thinking level · context %, tap → Session info sheet (model, thinking, context
  ring, tokens, cost, compaction button).
- Transcript (LazyColumn, stable keys and contentType per block; only the last block changes while streaming):
  - user: right, max 80% width, the redesign's soft bubble: surface2 with a subtle 1dp line, radius bubble (22) on
    every corner, padding 12x16, prose ink1. Images sent from this phone show as 56dp thumbnails (radius md) above the
    text (kept in memory for the session); images without a local copy read as a compact "2 images" chip (surface3,
    radius 4, image glyph, meta ink2). A message dictated on this phone carries a waveform badge on its top-start
    corner, 6dp outside it (22dp surface3 circle, 1dp lineStrong, 12dp GraphicEq ink2; TalkBack "Dictated"); the phone
    remembers which messages it dictated for the session (`SessionMemory.dictated`, matched to the echo by row key, or
    by text for a steer).
  - assistant, finished turn: a quiet meta line first, "Finished 1m 12s" (rowMeta ink3, tnum; the time only when this
    phone watched the turn), "Stopped" after an abort or "Failed" after an error, each after a 6dp dot (ink3 / danger)
    (`TurnMetaRow`). Then the folded tools ("Used 4 tools", below). When the reply starts with a heading it reads as
    the turn's bold title (subhead 17/24 ink1, 2dp above), then prose in ink2 on canvas, no bubble; paragraphs 12dp
    apart; ink3 list markers; headings ink1. The live turn has no meta line: its "Working" line sits at the bottom.
  - user, sending: an idle prompt shows at once as its bubble at 60% with "Sending" (meta ink3) under it, and the
    status line shows Quad Think + "Sending". The host's echo takes over the row in place (no second rise); a failed
    send removes it and returns the text to the composer. Slash commands are not shown early.
  - thinking: row "Thinking" (Quad Think 14dp + shimmer, the chevron right after the label) live, "Thought for 8s"
    (the chevron in the slot) after; collapsed by default; body
    thinking style with a 1dp rail; live body is a 5-line window pinned to the newest text that glides up (120 ms,
    in place) as lines arrive, with a 24dp top fade.
  - tool row (assistant-ui's one collapsed line): 20dp slot (Run/Write spinner 14dp → result glyph), verb (label ink2,
    danger when the step failed: "Run" while active, "Ran", "Read",
    "Edited", "Wrote", "Searched", "Found", "Listed" once done), argument (mono ink3, middle ellipsis for paths; a read
    carries its range, `src/app.ts:10-80`), right meta (+n success, −n danger, a failing bash exit "exit 1" in danger,
    "no results" ink3 for an empty search or listing, a duration only from 1 s, tnum; while running, the elapsed time
    from 1 s: "4s", "1m 3s", ticking once a second). A tool the host lacks reads as one meta ink3 line ("List isn't
    available here", no danger glyph) and is hidden once a later step succeeds. Images in a result (`content` items
    of type image) show as 120dp thumbnails under the row, open or not; a tap opens the image viewer.
  - tool cards (tap a row) hang under the row's text (32dp in, `STEP_TEXT_START`, so an opened fold's rail runs
    past them, as the desktop's `.tool-detail`); a failed step's card has a danger @ 32% outline. A path in a card
    header takes all the room up to the header's trailing meta. One Copy at most, and a footer only when something is
    cut: "Show all N lines" opens the
    rest in place (up to 400 lines), past that "Open output / file / diff" goes to the full-screen viewer.
    - bash: `$` (success, danger on a failing exit), command, exit badge, Copy (the output). The output is a terminal
      on the canvas: SGR colours mapped to tokens (red danger, green success, yellow warning, blue/magenta/cyan
      `ansiBlue` / `ansiMagenta` / `ansiCyan`: the syntax function/keyword/operator colours, Pi-Bolt's off-blue greys; 256 and 24-bit as given), bold SemiBold, dim 60%; other escapes are
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
  - code blocks: surface1, radius media (12), 1dp line. A quiet header 40dp on the same surface, no rule under it: the language (monoMeta ink3; shell
    aliases show as `shell`; untagged code shows the language detected from its first lines, else `code`), then
    " · " and the filename (monoMeta ink2, middle ellipsis) when the fence info string names one (`ts title="src/a.ts"`,
    `ts:src/a.ts`, `src/a.ts`); at the end icon actions: line numbers (toggle, 3+ lines), Wrap (only when a line
    overflows or wrap is on), Open (folded or 40+ lines) and Copy (icon only; a check while copied, 1500 ms). Code Mono 12.5/19, 10dp
    vertical padding; line numbers from 3 lines on, Mono 11.5 ink3 right-aligned, then a 1dp line rule and 10dp to
    the code. No wrap by default: one horizontal scroll with 20dp edge fades into surface1.
    Over 20 lines the block folds to 14 with a 40dp bottom fade and a 44dp footer ("Show all 84 lines" + chevron)
    that expands in place up to 150 lines ("Show less" scrolls the header back into view) and opens the viewer
    beyond ("Open all 1,240 lines"). Streaming: the header shows Quad Write 14dp + a ticking line count, the code
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
  - images: never fetched by themselves. A card (surface1, radius media (12), 1dp line, 56dp row: image glyph ink3, the alt
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
    widths only grow. Radius media (12), 1dp line border, header surface1 metaStrong ink2, 1dp line under it; body
    Inter 14/20 ink1, cells 12×9dp, 1dp line between rows, no column lines, zebra ink1 @ 3% from 8 rows. `:--`,
    `:--:`, `--:` are honoured; all-numeric columns align to the end with tnum. ✓ / yes success, ✗ / no danger,
    a lone dash ink3.
  - inline code: Mono 0.88em ink1 on a surface3 chip (radius 4, line box inset 14%), one chip per line segment
    when it wraps. A narrow no-break space pads each side inside the chip so it never touches neighbours; code of
    24+ characters without spaces may break after `/ . _ -`.
  - after a turn: its steps fold into one quiet line, "Used 4 tools" (rowMeta ink3, a chevron after it; "Thought" when
    it only reasoned), as OpenCode's; "· 1 failed" after a 6dp danger dot when a step failed (the turn's own failure is
    on the meta line). TalkBack reads the summary ("Used 4 tools: Edited 3 files · ran 2 commands", Expanded /
    Collapsed). Tap opens the steps: first the chips of the files the turn edited or wrote (28dp, radius sm, surface1 +
    1dp line, name in mono meta ink1, +n success, −n danger, tnum; at most 3, then "+k more"; a chip opens that file's
    diff), then, in a session on this phone, "Review changes (3 files, +40 −12)" (difference glyph 16dp ink3, label
    ink2, counts success / danger tnum, " · undone" in ink3 after an undo; 48dp tall) for a turn whose checkpoints show
    changed files (over remote control the newest turn that edited files says instead, in meta ink3, that review and
    undo work in sessions on this phone), then the steps. An opened fold draws a 1dp line rail at the slot centre
    through its steps (broken 4dp around each glyph, ending at the last one) and indents the prose between steps to
    the step text.
  - the session's changes: in a session on this phone, a floating `GlassPill` at the end of the composer's slim row,
    "Changes +12 −3" (label ink1, the counts success / danger, tnum), the sum of the session's turns whose files
    changed (from the turn checkpoints; an undone turn is left out, a redone one counts again; `SessionChanges`).
    One such turn opens its review; several open a "Changes" sheet of those turns, newest first (`InsetRow`: the
    prompt's first line, "3 files · +40 −12"), each opening its review. TalkBack: "View changes, 3 files, 40 lines
    added, 12 removed".
  - under the last finished reply: three icons, ink2 16-18dp, each in a 48dp target, no labels (one calm toolbar):
    Copy (a success check while copied), Ask again (sends the same prompt as a new turn; Pi has no regenerate; hidden
    for prompts with images) and More (Select text, View as Markdown, Share), 40dp tall, the copy glyph aligned with
    the prose. Fades in 150 ms after the turn settles; never animates in history.
  - live status line, the turn's "Working" line: the kit's `WorkingLine` (a 14dp Quad in the mode, ink2, warning while
    Pi asks, and the shimmering label in rowMeta) then " · " and the elapsed timer (rowMeta ink3, tnum). A running tool
    is named: the verb shimmers and its command or path follows in mono small ink3 ("Run  npm test"; a subagent reads
    "Agent working" + its task in rowMeta ink3; parallel tools add "+n"); a retry reads "Retrying" + "attempt 2 of 3"
    in rowMeta ink3 (tnum), never mono; text changes crossfade (quick), and both sides draw the Quad from the one
    clock, so only the words visibly change. With thinking off (level off, or a model that does not reason;
    `ThinkingRules`) nothing says "Thinking": the idle line reads "Working" and an empty reasoning block gets no row.
  - errors and notices: a hairline, not a filled slab (the desktop's): radius md, 1dp outline in danger / warning @
    32%, a 20dp glyph in the kind colour, the title bodyStrong ink1, text buttons.
- Todo panel above the queue strip (see Todo panel below).
- Queue strip above the composer: 44dp rows (48dp with the remove button) with a 2dp bar (warning = steer, ink4 =
  follow-up), the label on one line at least 62dp wide, swipe to remove.
- Composer, on the redesign's pill (`PillComposerShell`, after the references and OpenCode's composer), 20dp from the
  window's sides:
  - The pill: glass, radius pill (28), 56dp at least: a + `ComposerCircle` (opens the attach sheet), the field
    (rowTitle 17/24 ink1, its placeholder in the same style in ink3, one line, ellipsized; accent cursor and
    selection; grows to 6 lines, 4 in a short window, then scrolls), the mic (`VoiceMic`, a 36dp circle on the selected overlay with
    an ink1 MicNone, the same circle as +; accent with an onAccent Mic while it dictates in place) and the primary circle in one slot:
    `ComposerPrimary` (white in the default) with a waveform that opens voice input while the field is empty and an
    arrow that sends once there is text or an image (18% and disabled while an image encodes); Stop in the same slot
    while Pi works and nothing is typed (a 36dp surface3 circle, 1dp lineStrong ring, ink1 Stop glyph), and beside the
    primary while a steer is typed. Every circle is 36dp in a 48dp target on one line along the pill's bottom, 10dp
    from its edges (`ComposerLayout`); the field's top and bottom padding is (48dp − line) / 2, so one line is centred
    on them and the last of several stays there (also at font scale 1.3). Attached images sit in a tray inside the
    pill above the text.
  - Above the pill, a slim row (48dp): the run-settings chip at the start ("Sonnet 4.5 · High": the model maker's
    mark 14dp ink1 2dp before it (see Logos), the model in label ink2, middle-truncated past 180dp, then " · High" in
    label ink3 and a 16dp chevron; the level is left out when thinking is off or the model does not reason; TalkBack
    "Model: Sonnet 4.5, thinking high"), so the model is always in view; at the end the session's changes pill
    ("Changes +12 −3", above), or the "Steer | Follow-up" chip while a steer is typed. The chip opens the model sheet,
    whose "Thinking" row (36dp level pills, radius full, the current one accent 1.5dp on the selected fill, the level's
    meaning under it in meta ink3) sets the level without closing it; long press cycles the model (click label "Next
    model", a tick and a snackbar naming it); Shift+Tab cycles the thinking level.
  - Under 360dp of room above the keyboard (landscape) the slim row steps aside and the field takes at most 3 lines;
    the Steer chip moves into the pill, before Stop. In a window under 420dp wide the + steps aside while a steer is
    typed (never the mic, which may be listening). The field keeps its place in the composition, so the switch never
    drops focus or the keyboard.
  The attach sheet: Photos, Paste image, Mention a file, Commands, Prompt history; when the model's `input` lacks
  "image" its Photos row says so and picking one shows a "<model> can't read images" snackbar. Attached images (Photo
  Picker, images only, at most 4): 56dp thumbnails, radius md, 1dp line, a Quad Write while each encodes ("Image 2,
  preparing" to TalkBack; "Image 2 of 3" once ready), a 20dp ✕ (scrim, lineStrong ring) centred 4dp inside each
  thumbnail's top-end corner, its 48dp target centred on it ("Remove image 2"); 76dp per item. Encoding runs off the
  main thread: long edge at most 2000 px, JPEG 85 (PNG only when transparent and under 1.5 MB), refused over Pi's 4.5
  MiB base64 limit. Send works with images and no text. A thumbnail opens the image viewer.
  While the agent works a 1dp accent band (40%) sweeps the pill's top edge, inset by its radius. The palette and `@`
  mentions open above the pill (see Command palette below). IME insets animate with the keyboard. The draft is kept
  per session (computer or project, and Pi session) across restarts.
- Empty session (assistant-ui's Welcome in the redesign's language): the project's name in largeTitle, centred in
  the room above the suggestions, its branch under it (monoMeta ink3; on a computer, the computer's name). Just above
  the composer, in thumb reach, the suggestions as `InsetRow`s with hairlines (picked from the project: Inspect the
  project, Run the tests, Explain the structure, Summarise recent commits or Set up git, Start a new project in an
  empty folder), then "Recent" and the last 3 prompts this phone sent here (`Welcome.RECENT`; Prompt history in the +
  sheet keeps all 20), then "/ Commands". Every tap fills the composer; nothing sends by itself. It enters with
  assistant-ui's stagger (fade + 8dp rise, 120 ms + 70 ms per step: title, its line, each suggestion, the recent
  prompts, Commands) and the composer comes last (`Welcome.composerStep`); reduce motion shows it at rest. Recent
  prompts persist per computer (20 at most) in `recent-prompts.json`.
- Top bar: glass back, search and more (`GlassIconButton`, 44dp in 48dp targets); the Quad shows beside them only while
  Pi works (the live line off screen) or the link has failed, never as a resting grid that reads as a button.
- Connection banner under the top bar: Quad Connect + "Reconnecting in 3s" countdown + Retry; slides down/up. Not
  while the session first connects: the centred loading state (Quad Connect 44dp, "Starting Pi-Bolt in x") says that
  alone, so one spinner and one sentence show at a time.
- Top bar actions, left to right: the Quad (only while Pi is busy and the live status line is off screen: ink1,
  warning while Pi asks; or danger while the link has failed), the session switcher (while other sessions are open; its
  accent badge counts only those in a turn and is gone while all are idle; TalkBack: "Other sessions, 2 running" or
  "Other sessions, 1 open"; `SwitcherRules`), Search and More (glass). The Quad
  is not a control: no press, and TalkBack hears what it reports. Search and
  overflow always sit together. Overflow on a computer: Session and usage, Export or share,
  Disconnect, Forget computer. With a Pi-Bolt that offers review (`host.review`), Changes and Files follow Projects
  and sessions: the project's Changes and Files views, full screen and read-only, over the computer's working tree (no
  staging, commit, branch, push or edit; Refresh in the bar). The working tree's "Changes +N −M" pill in the
  composer's slim row opens Changes too (ui/session/remote).
- On this phone (a project's session): the title is the project's name and the subtitle drops the folder (session
  name and context only); back reads "Project". Overflow: New session, Resume a session, Fork from a message,
  Duplicate session, Rename session, Session and usage, Export or share (no Disconnect or Forget computer). The banner reads "Starting Pi-Bolt…" (Quad Connect) and "Engine stopped" with Restart (danger
  soft, power glyph). Long press on a user message adds "Fork from here": Pi forks the session at that message and
  its text comes back to the composer to edit and send. Recent prompts are kept per project.
- Sheets (ModalBottomSheet, predictive back): model picker (the Thinking pills when the model reasons, then search,
  "Current" then one group per maker, as the desktop's picker ("Anthropic" holds Claude from Anthropic, OpenRouter and
  Bedrock; a model without a known maker goes under its provider's name), under a meta-strong heading; the kit's
  `InsetRow`s: the maker's mark in the leading slot, the name (rowTitle) over "OpenRouter · 200k context · reasoning"
  (rowMeta ink3, the provider first), inset hairlines, the current model on the soft surface2 highlight with a check; search matches the
  maker and provider names too; no match is an EmptyState with the search-off glyph; `/model text` opens it filtered),
  thinking level (option rows: radius tile, surface1, rowTitle over a rowMeta description; the selected one on the
  selected fill with an accent ring and check), Pi's dialogs (Quad Wait eyebrow "Pi is asking", title,
  message in a command well when it looks like a command (surface1, radius md, the cwd in mono meta ink3 with Copy,
  then `$` in success and the command in mono), options as 52dp rows, primary button; swipe or scrim tap
  does not answer: an explicit Cancel does; countdown chip when the dialog has a timeout; haptic on appear).
- Snackbars for notify: surface3, radius md, line, above the composer, kind-coloured icon.

**Command palette.** Typing `/` opens a panel above the composer pill (glass, as the pill, radius tile, at most 264dp and
never more than the room between the top bar and the composer above the keyboard, so it cannot push the composer
under the keyboard in landscape; scrolls): 48dp rows with the command in mono small ink1, its argument hint in mono meta ink3, the description in meta
ink3 under it (rowMeta ink3) and a source badge at the end (micro, surface3, radius 4: `app`, `extension`, `prompt`, `skill`).
Matching is fuzzy (prefix, then word starts, then scattered letters), by name and then by description; a space closes
the panel and a usage line takes its place (`/compact [instructions]`, mono meta ink3, 16dp in). The app's own commands are
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
  level. Enter adds a line, as Android keyboards expect. While the panel is open Up / Down move a highlight (the
  selected overlay, kept in view) instead of walking the prompt history, Tab picks the highlighted row (the first
  before any arrow), Enter picks once an arrow chose a row (a soft keyboard's Enter is a key event too, so before that
  it still adds a line), and Esc closes the panel until the text changes. The panel shrinks away with its last rows.

**Review changes.** A full screen over the session, like the viewer (sheet motion, predictive back): Close, "Changes"
and "3 files · +40 −12" (meta ink3, tnum), a 1dp line. Then the prompt (body ink2, three lines) and, after an undo, a
surface1 card "Undid 3 files of this turn." with Redo (accent). The turn's actions sit in a bottom bar, in thumb reach
(canvas, 1dp line above, 12dp padding, over the navigation bar): "Restore to before" (secondary, history glyph) and
"Undo this turn" (primary, undo glyph: it can be redone); both off while Pi works, with a meta line above them saying
so. Each file: a 1dp line, a
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

**Message actions.** Messages take a long press only (`Modifier.longPress`: the long-press haptic, and TalkBack
offers "Message actions" as a long-click action instead of announcing each paragraph as a button); taps reach links.
Long press on a user message: Copy, Send again, Edit and resend (on this phone: forks before
the message and returns its text), Edit in composer, Select text, Share. On a reply block: Copy (plain text, without
Markdown), Copy as Markdown, Regenerate (on this phone: forks before the prompt and sends it again), Select text, View
as Markdown, Share. More under the last reply offers the same.

**Session tree** (`/tree`). A sheet: "Session tree" with "2 forks · Pi sees the marked path" (meta ink3; no subtitle
with one branch), the Messages | Prompts | Labeled | Everything filter, then the entries (forks indented, the active
path marked). Loading reads "Loading tree" (Quad Think). A failed read (or a reply without data) is an error, never an
empty list: no subtitle, no filter, a dangerSoft card (radius md, danger @ 40% line) with the error glyph, "Could not
read the session tree" (bodyStrong ink1), Pi's message (meta ink3, four lines) and Retry (accent).

**Export.** "Export or share" (or `/export`): Share as Markdown (a `.md` file through the share sheet), Copy as
Markdown, and on this phone Share session file (the JSONL) and Share as HTML (`export_html`). Files are written to
`cache/exports` and shared through a FileProvider.

**Dictation and voice input** (`ui/session/voice`). One recogniser (`VoiceInput`, held by the screen) behind two ways
in. The pill's mic dictates in place: tap to start, again (or stop talking) to finish; partial words appear in the
draft as they are heard, the final result replaces them (`VoiceDraft`). The pill's primary circle, while the field is
empty, opens the voice surface (OpenCode's voice screen) in the composer's place: what was heard in large type (title
20/26 regular, ink1; "Listening…" or "Tap the mic to talk" in ink3 before any words; the end of a long dictation from a
word start), then one row: a round 56dp mic (accent while it listens, surface3 with a lineStrong ring otherwise; tap
to stop or to talk again, each time adding to the draft), the kit's `DotWave` driven by the microphone's level
(`onRmsChanged`, −2…10 dB mapped to 0…1 by `VoiceLevel`, eased over the tap duration) in ink1 while listening and ink3
otherwise, and a round 56dp danger button with a white Stop glyph that ends voice input (Back too). The words are
already in the draft, so ending it leaves them in the composer, ready to send. The first use explains the microphone
in a sheet ("Continue"), then Android asks. A message sent with dictated words carries the waveform badge.

**Voice mode** (`ui/session/voice`, `VoiceMode`). A hands-free conversation in chats and project sessions, started by
"Voice mode" on the voice surface (a small ghost button above its row) or a long press on the pill's waveform button
(a TalkBack action too). It takes the composer's place like the voice surface, and loops: listen (Android's
SpeechRecognizer, on the phone when it can, `VoiceRecognizer`), send when the speaker stops (a steer while Pi works),
"Working" with the Quad in its mode and the live tool line, then say a short summary with TextToSpeech and listen
again. The summary (`SpokenSummary`) is the final reply's first paragraph as plain prose (no code, tables, links, URLs
or marks), at most 3 sentences and about 320 characters, then "I changed 3 files." from the turn's finished
write/edit calls, "Stopped." or the error's plain title; never an extra model call. Layout: a state line (polite live
region: "Listening", the Working line, "Pi is asking" with the Wait Quad in warning, the pause reason; "On-device
speech" or "Online speech" at its end, or "Show card" while a question is read), the captions (heard: title 20/26
regular ink2; spoken: rowTitle ink1, 6 lines; not live regions, the speech says them), a read question's options
("1  Postgres", rowMeta), then one row: the 56dp mic (accent while it listens; tap to talk, to finish, or to interrupt
the speech), the `DotWave` (the mic level while listening; while speaking a slow breath plus a lift at each word the
engine reports, still under reduce motion), a 48dp mute circle (VolumeUp / VolumeOff) and the 56dp danger end button
(CallEnd, white). Behaviour lives in a pure state machine (`VoiceModeMachine`): two silent listens wait for a tap;
"stop" or "wait" is never sent; `ask_user_question` cards are read out ("Option 1, Postgres.") and answered by number,
ordinal or label (`SpokenAnswers`; "other …" types an answer, "never mind" declines), two unclear answers give the card
back on screen; other dialogs are announced and answered on screen. Barge-in (talking stops the speech) is on only
where the phone has an echo canceller and TalkBack is off; its own words are told apart from the user's (`BargeIn`).
It holds transient audio focus as an assistant, pauses for calls (audio mode) and other apps' audio, and while the
app is in the background; it keeps the screen on; the end button and Back leave it. With TalkBack on it waits a moment
before listening. Settings > Agent: Speak replies, Speech rate (0.75x to 2x) and Voice (the engine's installed voices
in the phone's language, on-device first, each tried with a sample).

**Image viewer.** A tap on an image in a user message or the composer tray opens it full screen on black: swipe
between images, pinch or double-tap (2.5x) to zoom up to 6x, drag to pan a zoomed image (kept on screen), Back or ✕
to close. Save puts a PNG in Pictures/Pi-Bolt (Android 10+); Share sends it. The full image is kept for the six most
recent messages; older ones open their thumbnail.

**Compaction.** The status line names why it runs: "Compacting" (manual), "Auto-compacting" (threshold) or
"Context full, compacting" (overflow); its summary stays as an expandable notice.

**Todo panel.** The todo extension (opensec-pi-todo) draws its panel in the TUI only, so the app builds it from the
`todo` tool's results: each result's `details.tasks` is the whole list after the call (`SessionState.todos`, from
history and from live `tool_execution_end`).
- Card above the composer: surface1, radius tile, 1dp line, 20dp side inset (the composer's). Heading 48dp (the tap target): a 14dp
  progress ring (ink4 track, success arc of done/total, settle), "Todos" (label ink1), "2/5" (meta ink3, tnum),
  a chevron (ink3) that turns in a graphicsLayer. Tapping collapses the card to the heading, which then names the
  current task (meta ink2).
- Rows 32dp: 20dp glyph slot, 12dp gap, body text. Pending: 12dp ring ink3, text ink2. In progress: Quad Think 14dp
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

- Each agent has its own colour, Pi-Bolt Desktop's five subagent tints (the theme tokens `PiColors.tints`: apricot `#FEA668`,
  rose `#FF8FB4`, orchid `#E08EE5`, violet `#B696F7`, seafoam `#5CD5C7`; each 3:1 or more on surface1 and surface2 in
  every theme, `ContrastTest`), given by its order among the session's runs (`hueSlot`), never by its type: two
  general-purpose agents differ. No letters or avatars.
- State glyph (`AgentGlyph`), as on the desktop: starting (the call made, its session not running yet) a Quad Wait
  breathing in warning; running Quad Agent in the agent's tint; queued for a slot a still ink3 Quad; then a check
  (ink3), a cross (danger) or a dash (ink3). Colour is for the running agent and for failure only. The swap scales in
  from 0.7 over the glyph duration in the live turn. Sizes on the icon tokens (`AgentGlyphSize`, the rest glyph one
  step larger since a Material glyph draws inside padding): Card slot 22 / Quad 20 / glyph 22 (an agent's own
  two-line block), Row 20 / 14 / 16 (one-line timeline rows), Bar 20 / 16 / 20 (top bars, live lines).
- Card (surface1, radius tile, 1dp line; lineStrong while active, 16x14dp padding): the glyph, then the agent's name
  (rowMeta ink1: the task title the model gave the call, else its prompt's first line, else the type) over one meta
  line, the type and what it does now: "Explore · Reading src/auth.ts" (meta ink3; "background" when it is one; the
  error in danger; done, its metrics "4 tools · 12.3k tokens · 5 turns"), and the run time at the end (meta ink3
  tnum, ticking once a second). No narration: the extension sends the start of the agent's reply in the same field
  as its tool line, so the card names the tool ("Reading…", with the file or command from the agent's session file
  when there is one) and reads "Writing…" while it writes text; the reply itself is on the agent's screen. The meta
  line crossfades (quick, 4dp rise) at most every 450 ms. The card keeps one height from start to end; while it runs
  a 2dp band in its tint sweeps the top edge (sweep). Tap opens the agent; press scale 0.98.
- Stack: two or more consecutive `Agent` calls (one message's parallel calls). Closed: one card with a 48dp header
  (the group's state, still, in a Card slot so it lines up; "3 agents running", "1 of 3 agents running", "3 agents
  done", "3 agents · 1 failed" in label ink2; the longest run time; chevron) over each agent in its two lines (56dp
  rows, its glyph in its tint; tap opens it). Open (header tap): the header, then each agent's card 8dp apart; the
  switch resizes with the settle spring. The open state lives with the tool groups'.
- A finished turn folds its steps but not its agent cards: each is a unit of work of its own, and background runs
  outlive the turn.
  A background run's end shows where the notification lands: a step line with its glyph (Row size), its name, "Explore
  finished" (meta ink3) and its time; tapping opens the agent.
- Detail screen (over the whole app; the session screen stays composed below, so its scroll position, draft and
  images are as they were): enters like a screen (24dp slide + fade, sheet), predictive back scales it to 0.9. Top bar:
  back, the agent's glyph (Bar size, in its tint; animated only while the live line is off screen), its name (subhead)
  over "Explore agent" (meta ink3), Copy answer once there is one. A 36dp strip: state, run time, metrics; the model,
  "background" and the agent id under it (monoMeta ink3). The transcript is the main one's
  rows (user bubble for its prompt, thinking, tool rows and cards, prose, notices) built by `TranscriptModel` with every
  turn open by default and `Agent` calls as plain rows. Reversed list with a 32dp live line at index 0 (Quad Agent 16dp
  in the agent's tint, the activity in shimmer, including what it writes, the run time; kept while done so nothing jumps); it follows the newest row only
  while the reader is at the bottom, else the 40dp jump button with the unseen dot. Each agent keeps its list position
  and toggles for the app's life. Over remote control (or when its file was not saved) it shows the prompt and the
  answer with one info line saying why.
- Reaching the agent (bottom bar, 1dp line above): a running background agent takes a message ("Message this agent",
  sent to Pi as a prompt that asks it to call `steer_subagent`); a finished agent with an id can be continued ("Continue
  this agent": Pi resumes it with `Agent` `resume`); a running foreground agent cannot be messaged (Pi is inside its
  call: a message would reach Pi only after it returns), so the bar says so and offers Stop (surface3 pill; a first tap
  arms "Stop turn?" in danger for 3 s, the second aborts Pi's turn, which stops the agent). A green line confirms a send
  for 3.5 s.
- TalkBack: a card is one node ("Find the parser code, Explore agent, running, Reading src/parser.ts") with the click
  label "Open agent".

**Settings.** A `LargeTitlePage` "Settings" in the iOS-settings manner, and the owner's priority screen. Under the
title a `PillSearchField` ("Search") narrows the page to matching settings: every query word must appear in the group,
the label or its keywords (`SettingsIndex`, so "font" finds Text size and "vibrate" finds Haptics); "No results"
otherwise. With no query an account card leads: the GitHub identity (a 48dp avatar disc, the name and "@login ·
GitHub", or "Sign in with GitHub", which opens the sign-in sheet) over the default model ("Default model" with the
model and provider beneath, led by its mark; it opens the model picker of the open on-device session, or Providers
when there is no key, or says to open a session). Then the groups, in order, as `InsetGroup`s of `GroupRow`s with
monochrome glyph tiles (surface3, ink2 glyphs; colour only for a state), values in ink3 and chevrons;
switches on one end column; no explanatory paragraphs:
- Models & keys (apricot): Providers (count; opens the Providers page), Thinking level (a choice sheet). While
  searching, Default model is a row here too.
- Appearance (orchid): Theme and Accent (both open the theme sheet: the ten theme tiles, four a row, each drawn in its
  own colours, then the accent circles; a pick applies at once), Text size (a sheet with the A steps and a live prose
  preview), Density (a choice sheet).
- Agent (violet): Steering and Follow-up (One at a time / All at once), Auto-compact, Auto-retry with Retries (1 / 3 /
  5 / 10), Skill commands, Block images, Expand thinking, Tool output (6 / 12 / 24 lines). The engine's own settings
  (`agent/settings.json`, other keys kept) reach the open on-device session at once over RPC where Pi has a command.
- Projects & runtimes (seafoam): Runtimes (the shipped runtimes' names; the Runtimes page), Packages, MCP servers
  (their sheets; messages from them are snackbars on Settings).
- Remote control (rose): the paired computers (desktop glyph, green while connected; "Connected", "Connecting…" or
  "Last seen 5 min. ago", `RemoteRows`); a tap connects or returns to the live session, a long press or the TalkBack
  action "Forget computer" asks first. Pair with a code (the scanner), Paste a pairing link, Device key (mono, so "l"
  and "I" differ), and Reset device key (danger glyph, confirmed; the old key stays listed with a footer on revoking it).
- GitHub (see Settings > GitHub).
- Advanced: Retry and compaction (the tuning sheet), Reduce motion (Follow system / On), Haptics, Notifications
  (Allowed in success or Off in warning; opens the system page).
- About: Version, This phone, the update status (a Quad while it works, a progress line while it downloads, see
  Updates) with its one action as a button, Check automatically, and Licences (a sheet of the five: Inter, JetBrains
  Mono: OFL; Mermaid: MIT; Termux terminal libraries: Apache-2.0; Lobe Icons, the provider and model logos: MIT; a
  pick opens the text in the viewer).
Changes apply live, without recreating the activity, and persist in a small JSON file (read synchronously at
startup so the first frame has the right theme).

**Providers** (Settings > Models & keys > Providers). A `LargeTitlePage`: "On this phone" (or "No provider yet"), each
provider by its display name (`ProviderCatalog`) with its mark on a neutral tile, "Key on this phone" or the custom
endpoint's host and model, and a remove action that asks first (the key is not revoked at the provider; a custom
endpoint's models.json entry goes too); then Add provider (the provider sheet). Footer: "Keys stay on this phone."

**Runtimes** (Settings > Projects & runtimes, and "Runtimes" in a project's menu).
- A `LargeTitlePage` "Runtimes". While measuring: a `LoadingRow`; a build without runtimes: an `EmptyState`.
- Included: one group row per runtime: name in rowTitle, version in monoMeta ink2, installed size right-aligned in
  rowMeta ink3 (tabular figures); below, what comes with it ("npm 10.9.4", "pip 25.2"), then its commands as mono chips
  (surface2, 1dp line, Radius.sm). No Install/Remove: the runtimes are part of the APK (android/engine/CONTRACT.md).
- Packages and caches: npm cache and pip cache with their size and a small secondary "Clear" (disabled at 0 B;
  "Clearing…" while it runs; a ConfirmSheet first, naming the size), then npm global packages and pip packages with
  their size only. Footer: "Clearing a cache is always safe." A failed clear says so in danger under the row.
- Sizes are measured on IO when the screen opens and after a clear; nothing animates while idle.
- TalkBack: a runtime is one node ("Node.js 22.23.4, npm 10.9.4, 150 MB. Commands: node, npm, npx"); each Clear is
  labelled "Clear the npm cache"; the title is a heading. Every row is at least 48 dp tall.

**Notifications.** The small icon is the logo's bolt as one outline (no seams between its facets) at about 20dp in the
24dp status icon, tinted in the shade by `notification_accent` (the logo's amber, #E9A42C). Titles say what happened
("Pi finished on <host>", "Pi needs an answer on <host>", "1 terminal running"); text is one short line ("Running until
you close them"). Lock screens show only a public title and "Unlock to see it".

## Updates

The app is sideloaded, so it updates itself from GitHub releases. Source of truth: `pi-bolt.opensec.in/android.json`
(written by the Android release workflow), or GitHub's newest `android-v*` release when the site is down. Both are a
plain GET with no identifiers. The APK URL must be under `github.com/opensec-git/Pi-Bolt/releases/download/`.
- Check: on launch at most once a day (on IO, "Check for updates automatically", on by default) and from Settings >
  About > "Check for updates". About shows "Up to date (0.2.1)" or "0.2.2 is available · 109 MB", then "Checked 3 min
  ago".
- Home card (64dp, surface1, radius md, after the engine banner): system-update glyph, "Pi-Bolt 0.2.2 is available",
  the size (meta ink3), then Later (ink2, snoozes that version only) and Update (accent) end-aligned under the text, so
  the title keeps its width at large font sizes (a single action stays at the row's end). The same card then shows the job:
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
- Card: surface1, radius md, 1dp line. Header 40dp: diagram glyph 14dp ink3, the type (monoMeta ink3), full screen
  and Copy (the source). The diagram fits the column, at most 480dp tall, centred, 12dp inset; tap opens it full
  screen.
- Streaming: each change to the source waits 300 ms for the next (a fence known to be closed draws at once), so a
  diagram appears when the model pauses or the fence closes, never per token. Until then a 160dp skeleton (Quad Write
  20dp ink3, "Drawing diagram"), or the previous drawing with a Quad Write 14dp in the header. An error in a fence
  that is still open is not shown.
- Error: the source as a normal code block, then "Diagram could not be drawn: <Mermaid's first error line>" (meta
  ink3, a 14dp danger error glyph).
- Theme: Mermaid's base theme from the palette: nodes surface3 with a solid border (ink1 @ 26% over surface3), text
  ink1, edges ink2, clusters surface2, notes warning @ 16%. Charts use a fixed categorical set (types, strings,
  numbers, seafoam, success, warning, violet, rose (the tint tokens), danger, diff fgs, ink2), full strength for pie slices and dimmed to
  42% over surface2 where text sits on them. `darkMode` follows the canvas luminance (off for Paper).
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

## Copy

Short, confident, sentence case; no exclamation marks, no emoji. No sentence explains how the app works inside, why a
design is the way it is, or what is obvious on screen. A settings row is a title plus at most one short meta line that
carries a value or a consequence ("Applies to new sessions"). An empty state is one line plus its action; an error says
what happened and what to do. Safety, consent, permission and privacy lines stay, kept short and true.

One name per concept:
- **Pi** the agent; **Pi-Bolt** the app; **this phone** the device; **computer** the paired machine (never desktop,
  host or Mac); **remote control**, **pair**, **pairing code** / **pairing link**.
- **project**, **session**, **provider**, **model**, **API key**, **custom endpoint** (an OpenAI-compatible server),
  **engine** (only where the user acts on it: "Restart the engine").
- **steer** / **follow-up**; **compact** (the context); **fork**, **branch**, **label**; **undo**, **put back**,
  **restore**; **secrets**; **packages**, **skills**, **prompt templates**, **MCP servers**; **sign in** (verb) /
  **sign-in** (noun).
- Fixed phrases: "Loading…" or "Loading <thing>" (never Reading or Measuring), "Reload the session to apply changes", "Applies to new sessions",
  "switch model", "This can't be undone." (destructive confirmations).

## Not carried over from the desktop app

Geist fonts, 4x4 pixel grid, 80 ms cadence, bolt pattern and lime colour. Hover actions become
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
- What redraws every frame has a layer of its own (a Quad), or puts what it decorates in one (the composer's working
  hairline, a running agent's sweep), so a frame re-records the moving part only. Animated values are read in the
  draw phase, never while composing. The keyboard's height changes every frame of its animation: the session screen
  reads it through `derivedStateOf` (`ImeRoom`), so it recomposes only when the composer's layout changes.
- `e2e/frames.sh` measures each of these scenarios on a phone (README, "Frame timing").

## Chats

Plain chats talk to the model without a project (`engine/ChatSpace.kt`): one app-private folder, their own session
folder, and an engine limited to the web and question tools (`--tools web_search,fetch_content,get_search_content,
ask_user_question --no-mcp`; only `ask_user_question` with Settings > Agent > Web search off): no shell, no files and no
MCP server. They should feel like ChatGPT's or Claude's app, in the redesign's language.

**Home.** Chats are a section of their own under Recents (Recents holds only project sessions): a `HomeHead` "Chats  3"
whose end is a "New chat" `TextAction` and a chevron (the head opens the Chats list), the three newest chats as
`InsetRow`s (chat glyph ink2, title, "2h · 12 messages"; a live chat that is answering leads with a tint2 Quad Write and
says "Working"), then "All chats  N" (forum glyph, count, chevron) when there are more. With none yet: one row, "Start
a chat" / "Ask anything, without a project", with a + glyph. The pill's chip lists "Chat" first and defaults to it when
there is no project or a chat changed after the last project work (`ProjectRules.pillDefaultsToChat`); then the field
says "Ask anything…".

**The Chats screen.** Two glass buttons (back, +) on a 56dp bar, "Chats" in largeTitle, a `GlassSearchField`, then the
chats newest first under date heads as ChatGPT's list does ("Today", "Yesterday", "Previous 7 days", "Previous 30 days",
then the month; `ChatList.sections`), rowMeta ink3 heads, `InsetRow`s with the title and "2h · 12 messages"; the live
chat sits on the current highlight. A long press offers Rename and Delete (a ConfirmSheet, "This can't be undone.").
No chats: an `EmptyState` with the chat glyph, "Ask anything, without a project. Your chats show here." and "New chat".
No match: an `EmptyState` with search-off, never a lone grey line.

**A chat** opens on the session screen without the project-only parts (`SessionParts.CHAT`: no `@` files, `!` shell,
preview chips, project starters, folder or subtitle; no checkpoints, so no undo or changes pill), and with a chat's own:
- Top bar: back ("Back"), the chat's title (its name, else its first prompt; tap: chat info), then All chats (forum
  glyph: back to the Chats list, or the list in the chat's place) and New chat (+) as glass buttons, and More. More
  reads New chat, All chats, Search this chat, Rename chat, Fork from a message, Duplicate chat, Chat info and usage,
  Export or share, and Delete chat (danger, confirmed). The context meter shows from 1% ("0%" said nothing on a 1M
  model).
- Welcome (`ChatWelcome`): "Good evening" (by the hour) in ink1 over "What's on your mind?" in ink3, both largeTitle;
  then four everyday suggestions (explain, write, brainstorm, travel, learn, cook, decide, summarise, quiz, polish; one
  topic each, rotating daily) as `InsetRow`s that lead with their topic's glyph in ink2. A suggestion fills the composer
  with a prompt that ends where the user goes on typing ("Plan a relaxed three-day trip to "); nothing sends by itself.
  Recent prompts and Commands follow, as in a project.
- Transcript (`LocalPlainChat`): no "Finished 4s" line before an answer (Failed and Stopped still show). The reply's
  second action is Regenerate, not Ask again, and Regenerate and Edit and resend rewind the same chat (Pi's tree,
  `navigateTree` through the app's helper extension) instead of forking into a new chat that would crowd the list; the
  old answer stays a branch in /tree. A fork is the fallback.
- A new chat starts on the model and thinking level last picked in a chat (`ChatModel`; opening an old chat does not
  change it), not Pi's global default, which belongs to projects.
- Words: the palette, sheets and messages say "chat" where a project says "session" (`Builtin.inChat`).

## Sources (web search)

The bundled pi-web-access tools (`web_search`, `source_check`, `fetch_content`) never show as tool rows: each run of
consecutive web steps is one Sources pill (`ui/session/web/SourcesPill.kt`), on the step rail inside an opened fold
like any step, and a folded turn shows one pill for all its web steps after the answer. `get_search_content` stays a
plain row ("Read results").
- **Pill:** the timeline's 20dp slot (a Run Quad while searching or reading, else the Rounded Public globe in ink3),
  12dp, then a `glass` capsule, 40dp in a 48dp target: up to three overlapping 24dp favicon circles, each on a 1.5dp
  ring of the canvas so they separate, overlapping by 9dp, the first on top; "Sources" (label ink1), the count (label
  ink3, tnum) and an ExpandMore chevron that turns 180° (quick, in place). Read as "Sources, 5, show".
- **Before the first result:** "Searching the web" (or "Reading the page" for a read) shimmering, then the query in
  meta ink3, as long as any step is still written or running; "No sources found" only once all have finished. A search that failed with
  nothing found reads its reason in ink2 beside the globe.
- **List** (tap): a surface1 card, radius tile, 1dp line, under the pill from the text start; rows at least 52dp: a
  20dp favicon, the page title (body ink1, one line) over the domain (meta ink3, without "www."; "· reading", "·
  couldn't read"), and Open in browser (`IconAction`, OpenInNew 18dp). The whole row opens the page too.
- **Motion, live turn only:** each favicon pops in as its results arrive: scale 0.6 to 1 and fade, standard, enter
  easing, 120ms apart by position (`PiMotion.STAGGER`, eight steps at most), even when all arrive in one frame. A page being read (`fetch_content`) wears a 1.5dp
  ink2 arc of 110° that turns once per `SWEEP`; when the read ends the arc fades out (settle); a page that could not be
  read dims to 40%. History never animates; reduce motion draws the arc still and nothing pops.
- **Favicons** (`FaviconRules`, `Favicons`): from the site itself, `https://<host>/favicon.ico`, else the first
  `<link rel="icon">` of its home page (first 64KB); never a Google or DuckDuckGo favicon service. https only, public
  host names only (no IP, localhost or LAN names), 256KB at most, decoded to 64px. Memory (64) and disk cache
  (`cacheDir/favicons`, sha256 of the host, 4MB, least recently used out first; a host without one is retried after
  3 days), all off the main thread. On a dark theme a site's icon sits on a light disc (ink1 at 90%) with a 1dp
  lineStrong ring, so black marks (GitHub) stay visible. Without one: the Rounded Public glyph on surface3, never a
  letter tile.

## Background jobs (bash_background)

The app's `pibolt-bg` extension runs shell commands in the background for the agent (`engine/BackgroundJobs.kt`,
`ui/session/BackgroundJobsUi.kt`). In a project session with jobs, a `GlassPill` sits under the Preview chips: an 8dp
dot (success while any job runs, else ink3) and "Background · 2 running · 1 ended" (label ink1). Tap opens a `PiSheet`
"Background": running jobs first, newest first; each row a status dot (success running, ink3 done, danger failed),
"#id name" (bodyStrong ink1, one line) over "Running · 3m 4s" (label ink2), Log and Stop (`GhostButton` Small, Stop
in danger, "Stopping" while it waits), then the last six output lines (monoSmall ink2). Log opens the full log in the
same sheet (monoSmall, scrolled to the end, back arrow). Read every 2 s, every second while the sheet is open. The
extension's status line (`bg-jobs`) is not shown as a status line: the pill tells it.

## Question card (ask_user_question)

`ask_user_question` (rpiv-ask-user-question) walks its questions with Pi's select and input dialogs; the app shows
them as one card instead (`ui/session/ask/QuestionSheet.kt`, a `PiSheet`), built from the call's arguments. Other
extensions' dialogs keep the generic sheet.
- The Wait Quad and "Pi is asking" (metaStrong warning), "1 of 3" (meta ink3, tnum) at the end, and a bar of 3dp
  segments (accent up to the current question, surface3 after).
- The header chip (`Tag`, ink1), "Choose any" (meta ink3) on a multi-select question, the question in rowTitle (a
  heading), then the options: radius tile rows at least `optionRow` tall, a 20dp radio or checkbox glyph (accent when
  on, ink3 off), the label (rowTitle ink1) over its description (rowMeta ink3), selected: the selected fill and a 1.5dp
  accent ring. An option's preview shows under it when picked (monoSmall on surface2). A "Type something" field ends
  the card, always in view under the options (which scroll when long): for one choice it replaces the option, for
  several it adds to them.
- Back (secondary; Cancel on the first question) and Next / Submit (primary, enabled once the question is answered; a
  multi-select one may stay empty). Questions crossfade (quick).
- Nothing is sent until Submit; then the walker's dialogs are answered from the card as they arrive (option line, or
  the "Type something." row and the text; for several: `1,3; text`). Dismissing the sheet (swipe, scrim, Cancel)
  declines the whole questionnaire, as the extension's own dismiss does.
- Turning Web search on or off stops the idle engines and every on-device session not in a turn
  (`LivePolicy.restartsForSettings`), so New chat in an open chat gets the new tools too; a session in a turn keeps its
  engine until it ends. The "Searches go to Exa" note shows only while it is on.
- A dismissed questionnaire's row reads "Declined" with the neutral dash, not "Asked" with a check. With the keyboard
  up the options shrink and scroll, and the action row stays above the keyboard.

## Spending

Pi reports a cost with every reply (`usage.cost.total`); the app adds it up (`cost/`). Figures are monochrome, in
tabular numerals, `$0.42` with two decimals (`<$0.01` below a cent); use on a model without a price (a custom endpoint)
shows "—", never `$0`.
- **Where:** the session's cost after the context meter in the top bar (meta ink3; tap: the Spending sheet); "Today
  $0.42" as a rowMeta ink3 line under Home's title once anything was spent today (tap: the same sheet without the
  session); with Settings > Agent > Spending > Show costs in lists, at the end of the meta line of saved sessions and
  chats ("2h · 12 messages · $0.42").
- **The Spending sheet:** an inset group of this session, this project (on this phone), today and this month, each with
  its limit and a `ProgressLine`; then this month by model; then "Spending limits".
- **Counting:** a session shows its own total (its file and its subagents' files on this phone, `get_session_stats`
  from a computer) plus each reply as it ends. The day and month (`spend.json`, 35 days and 13 months, the phone's
  calendar) count only what happened while the app watched: live replies, compactions and subagent messages, each once
  by key. Reopening, resuming, forking or reconnecting never counts history again. Subagents on a computer are not
  counted (remote control sends no costs for them).
- **Limits:** per session, day and month, all off by default. At 80% a warning snackbar, once per period. At a limit no
  new prompt goes out, a running turn finishes its step (the reply and its tool calls) and is stopped through abort, and
  a run that starts by itself (a background agent's report) stops at once; a warning `Notice` above the composer says
  "Daily limit reached ($5.00). Raise it in Settings or continue once." with Not now and Continue once (the next run
  goes to its end; after a stop it sends "Continue."). Away from the app the "Pi stopped" notification says the same,
  without Reply.
