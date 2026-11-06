# Pi-Bolt Android: design and UX audit (0.2.1)

An audit of the released Android app 0.2.1 (`android-v0.2.1`, commit aa67f53aa), with a prioritised spec for a polish
pass. It is written against [DESIGN.md](DESIGN.md): every fix is stated in its tokens (colours, type scale, spacing,
radii, `PiMotion`, Quad modes), and DESIGN.md changes are listed where a fix changes the system itself.

## How this was made

- **Device.** The release APK (not a debug build) on `pibolt-bench`: Pixel 8 profile, API 36, 1080x2400 at 420 dpi.
  It used a fake OpenAI-compatible model on the host (`fake-model-audit.mjs`, modelled on the e2e suite's
  `fake-model-scripted.mjs`). The provider config was written as root, because a release build has no `run-as`.
- **Content.** Four projects (one a git repo on `feat/depth-guard`), a 50-message session with streamed markdown
  (headings, a 23-line code block, a 5-column table, a diff, a quote, links), a 7-step tool turn (list, read, search,
  find, write, edit, a failing test run), a todo list, an Explore subagent, a 400 error, a 503 with three automatic
  retries, a restored session, and a remote-control pairing with a desktop `pi-bolt` 1.1.2.
- **States.** Themes Graphite, Claude and Vercel; landscape; font scale 1.3.
- **Screenshots.** They live outside git, in `~/pibolt-shots/audit/current/` (79 files). Below, `shot:NN` means
  `~/pibolt-shots/audit/current/NN-*.png`. The full list is in the Appendix.
- **References.** Mobbin screens. The Mobbin tool only searches iOS and web, so every reference is an iOS screen.
  Use them for patterns, never as copies.
- **Work in flight.** Four branches will merge before the polish pass: `android/features` (command palette, mentions,
  search, stats, message actions), `android/markdown`, `android/diagrams` and `android/subagents`. Where an issue
  overlaps one of them, the overlap is marked **[after features]** (or markdown, subagents). Re-check those issues on
  the merged build before implementing.

## Summary

The visual base is strong. Markdown, code blocks, tables and the file viewer look good (shot:17, shot:19, shot:44).
The experience breaks down in four places:

1. **Space.** The bottom stack (todo card, status line, steer control, composer) plus the keyboard leaves about one
   line of transcript. In landscape it leaves none, and the composer goes behind the keyboard.
2. **Failure.** Errors are raw JSON, with one card per retry and no way to recover. A failing test paints the whole
   tool output red and marks the turn "failed".
3. **Noise.** Up to four Quad spinners animate at once. Status is said twice ("Thinking" as a row and again in the
   status line). Durations of a few milliseconds are shown, along with internal rows such as "Updated todos 7 times".
4. **Getting back in.** Home has no "continue where I left off". Every resume takes two taps. A subagent's internal
   session shows up as a user session.

---

## Top 15 issues

Ranked by how much each one hurts daily use now. Each issue gives what is wrong, why it matters, a reference and the
fix.

### 1. The bottom stack eats the transcript, and in landscape the composer disappears

- **Screenshots:** shot:28 (keyboard open while running), shot:27, shot:30, shot:68 (landscape), shot:69 (landscape
  with keyboard).
- **What is wrong:**
  - The todo card (4 rows, about 330 px), the status line, the Steer | Follow-up control and the composer stack above
    the keyboard. In shot:28, 230 px of transcript is left on a 2400 px screen.
  - In landscape (shot:68) the transcript gets no space at all.
  - In landscape with the keyboard open (shot:69), the todo card stays visible and the composer field is hidden
    behind the keyboard, so you cannot see what you type.
  - The todo card also stays fully expanded when idle (shot:30), and after an error (shot:33).
- **Why it matters:** Steering while Pi works is the core loop, and it is exactly when this happens. Typing blind in
  landscape is a functional bug.
- **Reference:** Manus pins a single "current step" line above the composer and opens the full plan on tap
  (https://mobbin.com/screens/61020e0d-0c5b-4cc4-a5f4-5a2f33198a43). Claude keeps only the composer and a ↓ pill
  while streaming (https://mobbin.com/screens/42340550-73a5-46d0-973d-21316812ae5c).
- **Fix:**
  - **Bottom-stack budget.** Everything between the transcript and the IME (status extras, todo, pending dialog strip,
    queue, steer control, composer) gets at most 45% of the window height, and at most 50% of what is left above the
    IME while it is open. Overflow is resolved in this order:
    1. The todo card collapses.
    2. Status extras go to 1 line.
    3. The queue goes to its 1-row form.
    4. The composer caps at 4 lines.
  - **Todo card, collapsed form.** A 40dp row: 14dp progress ring, "Todos 1/4" in label ink1, then " · Fixing caret
    associativity" (meta ink2, ellipsized), and a chevron. It collapses on its own while the IME is visible, while
    idle (not `isRunning`), and whenever the window height is under 560dp. It expands only on tap, and a tap while the
    IME is open first hides the IME. The 6-row cap stays for the expanded form. Animation: `animateContentSize`
    (standard, in place).
  - **Steer | Follow-up.** It moves into the composer bar as a 32dp chip that toggles ("Steer ⌄" / "Follow-up ⌄",
    label ink2, warning dot for Steer), next to the send button. It is no longer a 36dp row plus 8dp gap.
  - **Landscape and compact height (< 480dp tall).** Hide the todo card behind a 16dp ring button in the top bar. The
    composer is 1 line and its bar merges into the same row (attach, field, chips collapsed into a single "⋯" chip,
    send). Use `WindowInsets.ime` with `imePadding()` on the whole column so the field always sits on the keyboard.
    The current `imePadding()` on the column is right, but the fixed-height siblings push the composer out.
  - **Reading width.** Cap the transcript, the bottom stack and the composer at 720dp, centred, when the window is
    wider than 840dp (landscape phones, tablets, foldables). Use `widthIn(max = 720.dp)` inside a centred box.
- **Files:** `session/SessionScreen.kt`, `session/TodoPanel.kt`, `session/Composer.kt`, `session/SessionChrome.kt`
  (QueueStrip, StatusExtras).

### 2. Errors are raw JSON, repeat per retry, and offer no way out

- **Screenshots:** shot:33 (400 context length), shot:35 and shot:39-session-errors-stacked (503 with three retries),
  shot:64b (the restored session drops the first error).
- **What is wrong:**
  - Each notice prints the provider's JSON body in mono, for example
    `400: {"message":"This model's maximum context length…","type":"invalid_request_error","code":…}`.
  - An automatic retry adds a new full-size "Error" card per attempt, then a "Retry failed" card with the same JSON.
    That is three near-identical 160dp cards.
  - "Compaction failed" says what to do ("Try switching to a larger-context model or starting a new session") but has
    no buttons.
  - Under the errors, the reply actions offer "Copy" and "Ask again" as if a reply existed.
  - The status line shows "Retrying 3/3: 503: {"message":…" in warning mono, cut off.
  - After the session is restored, the first error is gone (shot:64b), so history differs from what was seen live.
- **Why it matters:** Errors are where users decide whether the app works. JSON reads as a crash. There is no
  one-tap path to the fix (retry, switch model, compact, update key).
- **Reference:** ChatGPT's stopped research card puts the reason in its header and keeps the plan state
  (https://mobbin.com/screens/d046e8d0-2f88-4f1a-a222-b8fd57d5917e). Revolut's dark empty card explains the cause
  in plain words inside a card (https://mobbin.com/screens/62cbed44-117a-4aa8-91a3-bfbfdacd8f20).
- **Fix: a new `ErrorNotice` design**
  - **Layout.** Radius 12, 1dp danger @ 40% line, dangerSoft fill.
    - Row 1: error glyph 18dp danger, then a human title (bodyStrong ink1).
    - Row 2: one sentence of explanation (body ink2).
    - Row 3: up to 3 actions as `TextAction`s, the first in accent.
    - A "Details" disclosure (meta ink3 + chevron) opens the raw text in a mono well: surface1, radius 8, monoSmall
      ink2, with Copy.
  - **Error classes.** Classify by HTTP status and the `code`/`type` fields of the provider body:

    | Class | Title | Actions |
    |---|---|---|
    | `context_length_exceeded`, 413 | "Too long for Fake Sonnet 4.5" | Compact · Switch model · New session |
    | 401, 403, `invalid_api_key` | "The provider rejected the key" | Update key (opens the provider key step) · Switch model |
    | 429 | "Rate limited" | Retry (with a countdown when `retry-after` is known) |
    | 5xx, `overloaded`, `server_error` | "Fake provider is overloaded" | Retry · Switch model |
    | Network | "Can't reach the provider" | Retry |
    | Anything else | "The model returned an error" | Retry |

  - **Retries.** Consecutive retry errors of one turn collapse into a single notice. While retrying, it reads
    "Overloaded · retrying 2 of 3" with Quad Connect 12dp and Stop retrying. Once the retries run out it becomes the
    final notice ("Tried 3 times"). The status-line extra goes away: the notice is the status.
  - **Reply actions.** No ActionsRow under a turn that produced only an error notice. "Ask again" moves into the
    notice as Retry.
  - **Restored history.** Restore must keep error notices the way the live reducer built them.
- **Files:**
  - `protocol/…/session/SessionReducer.kt` (merge retry notices, keep restored errors).
  - `session/Rows.kt` (`NoticeRowView`) and a new `session/ErrorText.kt` (classify, title, actions).
  - `session/TranscriptModel.kt` (no ActionsRow after an error-only turn).
  - `session/SessionChrome.kt` (drop the retry line from `StatusExtras`).

### 3. Home has no "continue", and every resume takes 2+ taps

- **Screenshots:** shot:46, shot:57, shot:05, shot:41.
- **What is wrong:**
  - The project card is the only way back to a session, and tapping it always opens the project screen, even when its
    session is live (the Quad and green pip in shot:46).
  - A session that finished in the background looks the same as one that did not.
  - There is no cross-project recent list, so resuming yesterday's work is Home → project → session row, 2 taps and
    a screen of reading.
- **Why it matters:** Most opens of a coding-agent app are "check on it" or "continue". The ChatGPT, Claude and
  Cursor apps put recents first for this reason.
- **Reference:** Cursor's "All Agents" rows (status dot, title, `+273 −127`, repo; "22m" on the right)
  (https://mobbin.com/screens/1066acb9-1146-46e2-b56d-3381cc6899fd,
  https://mobbin.com/screens/968ea1e2-797e-46cf-a30b-b6551cc0375e). The Gemini drawer marks an unread finished
  reply with a dot (https://mobbin.com/screens/7838c98a-2cc1-4fea-824c-c2a32bbf01ad). ChatGPT Codex shows the
  machine plus projects on one home (https://mobbin.com/screens/2db4384c-4fd7-4b23-8612-251c6b59a99e).
- **Fix:**
  - **"Continue" section** at the top of Home (SectionLabel "Continue"), up to 3 rows, 64dp, surface1, radius 12:
    - Leading 20dp slot: the live Quad (settled mode, 600 ms debounce) for a running session, a warning Quad Wait for
      "needs an answer", an 8dp accent dot for "finished, not opened since", or the chat glyph ink3.
    - Title: session name or first prompt (body ink1, 1 line).
    - Meta: "calc-parser · 3 min ago" (meta ink3). The age never truncates.
    - Tap resumes the session directly, 1 tap. The live session resumes without reopening, as
      `ProjectScreen.open()` already does.
    - Remote hosts with a live session appear here too, with their name as the project part.
  - **Project cards** stay below, under "Projects".
  - **Unread state.** Store `lastOpenedAt` per session file in `ProjectStore`. A session whose `updatedAt` is later
    than that is unread. Opening a session clears it.
  - **Notifications.** A "Pi finished" notification tap opens that session, not Home.
- **Files:** `home/HomeScreen.kt`, `projects/ProjectCard.kt` (reuse it for the row), `projects/Projects.kt` (recent
  sessions across projects, read on IO), `data/ProjectStore` (lastOpenedAt).

### 4. Four spinners at once, and the status is said twice

- **Screenshots:** shot:12, shot:27, shot:31, shot:35.
- **What is wrong:**
  - While a tool runs inside a todo plan, the following all animate together:
    - the top-bar Quad (shown lit at rest even while the status line is on screen);
    - the step-row Run Quad;
    - the status-line Quad;
    - the todo row's Quad Think;
    - the composer hairline;
    - the running card's top sweep.
  - Text repeats too:
    - "Thinking" is a row header and "Thinking · 0:03" is the status line, one row apart (shot:12).
    - "Run · 10s" is on the step row and "Run for i in 1 2 3… · 0:11" on the status line, with two different elapsed
      formats (shot:27).
  - In shot:31 the todo row shows "Fixing caret associativity" with Quad Think while an unrelated Explore agent is
    working.
- **Why it matters:** DESIGN.md's own rule is "one spinner at a time", and calm is the brand. Duplicated motion reads
  as busy and makes the true state harder to see.
- **Reference:** Cursor's live steps: flat rows, one shimmering "Thinking…", finished steps static
  (https://mobbin.com/screens/36869aa5-dae8-4b65-995f-04e4bc185129).
- **Fix: one live indicator per screen**
  1. The status line owns the animation while it is on screen.
  2. Step rows in the live turn show the Quad's rest frame in ink2 (static) while the status line is visible, and
     animate only when the status line has scrolled away.
  3. The top-bar Quad is hidden (alpha 0, keeping its slot) while the status line is on screen, not drawn at rest. It
     stays at rest in ink3 when idle and is not announced.
  4. The todo row for the in-progress task uses the half ring (ink1) while the agent runs. It shows the Quad only when
     the status line is off screen and the todo card is the only visible live element.
  5. The composer hairline and the card top sweep remain as non-Quad signals. The card sweep is removed when the
     status line is visible.
- **Status line copy** says what the rows do not:
  - While a thinking row is visible and streaming, the status line shows only the elapsed time and the token rate
    ("0:03 · 41 tok/s" when known). Otherwise it says "Thinking".
  - While a tool row is visible, it shows "Step 3 · 0:11".
- **Elapsed format.** One format everywhere: "11s", "1m 3s" (DESIGN.md's step format). Never "0:11".
- **Files:** `session/Rows.kt` (StatusLine, ThinkingRowView), `components/Quad.kt` (StepSlot gets a `quiet` flag),
  `session/ToolRowView.kt` (slot, topSweep), `session/TodoPanel.kt`, `session/SessionChrome.kt` (top-bar Quad),
  `session/Transcript.kt` (passes `statusOnScreen` down through a CompositionLocal).

### 5. A failing test turns the whole output red and marks the turn "failed"

- **Screenshots:** shot:24, shot:22.
- **What is wrong:**
  - The bash card for a non-zero exit paints every output line danger, including "ok tokenize", and then adds
    "Command exited with code 1" (danger mono). The header already says `exit 1` in a danger pill.
  - The turn fold reads "Worked for 2s · 7 steps · failed" in danger, although the agent finished normally and
    explained the failure. A failing test is information, not a failed turn.
  - The card has Copy twice (a header icon and a footer "Copy"), and "View full output" appears although all 9 lines
    are already shown.
- **Why it matters:** Red everywhere trains users to ignore red. The real failure (the one FAIL line) is no easier to
  find than the passes.
- **Reference:** Cursor's changes and diff cards keep colour for `+/−` counts and the failing row only
  (https://mobbin.com/screens/6120217a-19ae-4e7e-91e1-08dffb9bb98a).
- **Fix:**
  - **Output colour.** Output text is always ink2, whatever the exit code. Lines matching
    `^\s*(FAIL|ERROR|Error:|✗|×)` get danger; lines matching `^\s*(ok|PASS|✓)` get success at 80%. This is a cheap
    highlighter in `ToolFormat`, run once per finished card.
  - **Exit line.** Drop the "Command exited with code N" line from the body (the badge already says it). Keep it only
    in the full-screen viewer.
  - **Turn status in the fold.** "· failed" (danger) only when the turn ended with an error notice or abort. Otherwise
    "· 1 step failed" in meta ink3 with a 6dp danger dot before it. `TranscriptModel.endedInFailure` already
    distinguishes this.
  - **Card actions.** One Copy, in the header. The footer appears only when the output is truncated, and then holds a
    single "Show N more lines" (or "Open output" past the expand limit). The same applies to the Read card
    (shot:25, "View full output" under a complete preview).
- **Files:** `session/ToolRowView.kt` (BashCard, OutputCard), `session/ToolFormat.kt` (line classes), `session/Rows.kt`
  (FoldRowView).

### 6. First run asks for a key, then leaves you with two equal paths

- **Screenshots:** shot:02, shot:03, shot:04.
- **What is wrong:**
  - The key sheet is clear. But after "Later", Home shows "Connect a model" as a quiet card and, below it, a large
    "Code on this phone" card whose primary button is "New project". Creating a project without a key leads to a
    session that cannot answer.
  - Four of the first seven providers share the badge "O" (OpenAI, OpenRouter, OpenCode Zen, OpenCode Go).
  - There is no entry for an OpenAI-compatible endpoint (Ollama, LM Studio, a proxy). Users of local or self-hosted
    models have no path except editing `models.json`.
  - The key step's copy, "It is saved in Pi's auth.json on this phone", is jargon.
  - Back from the key step closes the whole sheet. Back again leaves the app.
- **Why it matters:** This is the first 60 seconds. A wrong first path (a project without a key) is the most likely
  way to lose a new user.
- **Reference:**
  - Raycast's login fills only one button, so the primary path is unambiguous
    (https://mobbin.com/screens/a11766a5-76b5-44a3-8436-ce83f5f5aba6).
  - ChatGPT's key card puts the reassurance next to the secret field
    (https://mobbin.com/screens/3e9a0746-48ed-401c-9aab-8698f07fc030).
  - Cursor's connect-first empty state (https://mobbin.com/screens/15cf2ccc-5802-48ae-be53-3707cecc7c1d).
- **Fix:**
  - **Home without a key.** Replace the two cards with one setup card (surface1, radius 18, 20dp padding):
    - Title "Set up Pi-Bolt" (title).
    - Two numbered rows of 56dp each: "1 Connect a model" (done shows a success check and the provider name), then
      "2 Create a project".
    - The only primary button is the next step, so "Connect a model" first. "Clone from git" and "New project" become
      secondary until a key exists.
  - **Provider list.**
    - "Popular" (Anthropic, OpenAI, Google Gemini, OpenRouter), then "More providers" (SectionLabel).
    - Badges use two letters (An, Op, Gm, OR, OZ, OG…): label SemiBold ink1 on surface3, 32dp, radius 8.
    - Add a last row, "Custom endpoint (OpenAI-compatible)", whose step asks for a base URL, a model id and an
      optional key, and writes `models.json`. This needs an engine API, so it is P1.
  - **Copy.** "Your key stays on this phone and is sent only to Anthropic." "Paste" stays.
  - **Back.** On the key step, Back returns to the list (a `BackHandler` inside the sheet).
- **Files:** `home/HomeScreen.kt` (EmptyProjects, ProviderNudge), `providers/ProviderSheet.kt`,
  `providers/ProviderCatalog.kt`.

### 7. The empty session is a raw command dump

- **Screenshots:** shot:08, shot:54 (remote).
- **What is wrong:**
  - Under a display-size "New session" title, the first five host commands are listed: `/agents`, `/todos`, `/llama`
    ("Manage llama.cpp router models"), `/mcp`, `/usage`.
  - They are extension internals, two of them meaningless on a phone, and their description columns start at ragged
    x positions.
  - Nothing helps a user start: no suggestions about the project.
  - The title repeats what the top bar says.
- **Why it matters:** The empty session is the most-seen screen after Home. It should get the first prompt written.
- **Reference:** ChatGPT's first-project empty state is one action-phrased title and one CTA
  (https://mobbin.com/screens/0e3ce245-62be-49b4-93aa-54108b9fcd85). Claude's composer-first layout
  (https://mobbin.com/screens/0c723473-1bab-4bba-8786-10abe4b1d5a2).
- **Fix:**
  - **Header.** The 40dp mark, then the project name (title, not display) and below it the branch (branch glyph +
    monoMeta ink3). Drop "New session".
  - **"Start with".** A wrapped row of up to 4 suggestion chips: 36dp, radius 18, surface1, 1dp line, label ink1.
    They fill the composer and never send.
    - Picked from the project: "Explain this project", "Run the tests" (when a `package.json`, `Cargo.toml`,
      `pyproject.toml` or `go.mod` exists), "Summarise recent commits" (git repo), "Find TODOs".
    - For an empty folder: "Set up a new project", "Clone a repository".
  - **"Recent".** As today, 48dp rows.
  - **Commands.** One "All commands" `TextAction` (ink2) that opens the palette **[after features]**. Remove the inline
    command list.
  - **Filter.** In the palette, hide commands that do nothing on a phone (`/llama`). Keep that list in `ToolFormat` or
    `features/Commands.kt`.
  - **Description column.** If a two-column list remains anywhere, it starts at a fixed 120dp, so descriptions align.
- **Files:** `session/SessionChrome.kt` (EmptySession), `projects/ProjectFiles.kt` (cheap marker-file check, IO).

### 8. Subagents are opaque, and their sessions leak into the project's list

- **Screenshots:** shot:31, shot:41, shot:72.
- **What is wrong:**
  - The live Agent step is an "Explore" row, then a card that repeats "Explore Find every caller of parse()", a body
    reading "thinking…", and "View full output". There is no sign of what the agent is doing, how many tools it used,
    or for how long beyond the row timer.
  - After the run, the project's Sessions list shows "Explore#05353d0b · 3 messages", the subagent's internal session
    file, as if the user had created it.
- **Why it matters:** Subagents are long-running and are where users wonder "is it stuck?". A stray session with a
  hash name looks like a bug.
- **Reference:** Manus shows nested tool pills under a plan step
  (https://mobbin.com/screens/61020e0d-0c5b-4cc4-a5f4-5a2f33198a43). Copilot's agent shows a running dot, the task
  and the elapsed time (https://mobbin.com/screens/a121c71f-f680-42ea-b08f-c491427e01a3).
- **Fix [after subagents]:**
  - **Collapsed row.** Keep the meta line under it. Below the row, while running, show up to 3 of the subagent's latest
    steps as 28dp mini rows indented to the step text: 12dp glyph slot, verb label ink3, argument monoMeta ink3.
    Older steps fade out (quick).
  - **Running card.** It shows a step count and the elapsed time in its header ("Explore · 6 steps · 41s"). The body
    is the live step list, not "thinking…".
  - **Done.** The card shows the first 6 lines of the answer as prose (markdown) and "Open full answer".
  - **Sessions list.** Exclude subagent session files: filter by the subagents extension's naming (`<Type>#<hex>`) or,
    better, a parent pointer in the session header. Show them nested under their parent as "1 agent run" (meta ink3)
    when the parent row is expanded.
- **Files:** `session/ToolRowView.kt`, `session/ToolFormat.kt` (subagentProgress), `projects/Projects.kt` (session
  listing), `engine/…` session index (filter).

### 9. The transcript is cut hard at both edges, and the scroll button covers content

- **Screenshots:** shot:14, shot:17, shot:19, shot:25, shot:64.
- **What is wrong:**
  - Text slides under the top bar with only a 12dp fade. Half-cut glyph rows are visible under the subtitle in almost
    every scrolled screenshot.
  - At the bottom there is no fade at all. Code rows and table cells are sliced by the composer's top edge (shot:17:
    "48 if (!op || …" cut; shot:19: the table's third row).
  - The 40dp scroll button sits at the end edge over content (it covers the table's "Tested" ✓ in shot:19 and a code
    line in shot:17).
- **Why it matters:** Hard cuts look broken and hide the line you are reading.
- **Reference:** Claude centres a ↓ pill above the composer, out of the text
  (https://mobbin.com/screens/42340550-73a5-46d0-973d-21316812ae5c).
- **Fix:**
  - **Top fade.** 24dp, canvas → transparent. It is already drawn in `Transcript`; change the height.
  - **Bottom fade.** 16dp, transparent → canvas, over the last 16dp of the list, drawn in the Transcript box above the
    bottom stack. Add 16dp of bottom `contentPadding` so the last row can scroll above it.
  - **Scroll button.** Centre it horizontally, 12dp above the bottom stack. Keep it 40dp (surface3, 1dp lineStrong,
    48dp touch) and the accent unseen dot. Its enter and exit motion are unchanged. Update DESIGN.md
    ("at the end edge" becomes "centred above the composer").
- **Files:** `session/Transcript.kt`, DESIGN.md (Motion, auto-scroll).

### 10. Diffs in edit cards number lines wrongly and lose syntax colour

- **Screenshots:** shot:25 (edit card), shot:16 and shot:17 (fenced diff).
- **What is wrong:**
  - The edit card's single number column mixes old and new numbers: 1, 2, 3+, 4+, 3, 6+, 4, 5, 6, 7. Context lines
    carry old numbers and additions carry new ones, so line "3" appears twice.
  - Diff text has no syntax colour, while the same code in a fenced block does.
  - Fenced diffs repeat `--- a/src/parse.ts` and `+++ b/src/parse.ts` as body rows under a header that already shows
    `src/parse.ts +4 −1`, and the hunk header is truncated mid-word.
  - The footer of a diff says "View full output".
- **Why it matters:** Reviewing edits is the main reason to open a tool card on a phone. Wrong numbers break trust.
- **Reference:** Cursor's full diff: path + `+19 −16` header, tinted rows, a gutter bar, "4 unmodified lines" folds
  (https://mobbin.com/screens/20e04ff8-1fc6-40c6-8226-6a0cc083cb6a). GitHub's Files Changed
  (https://mobbin.com/screens/57e1b0d0-9d3e-4bcd-b81b-2880ec01c3d9).
- **Fix:**
  - **Line numbers.** New-file numbers only. Deleted rows get no number: the gutter shows the hatched bar and "−".
    Width is that of the largest number. Mono 11.5 ink3, right-aligned, as code blocks.
  - **Syntax colour.** Highlight diff text with the language from the path (`Highlighter.highlight(lang, line)` per
    line, cached). The add/delete fg colours become the backgrounds only: success @ 11%, danger @ 11%. The text keeps
    its syntax colours, with deletions at 70% alpha.
  - **Fenced diffs.** Drop `---`/`+++` rows when the header has the path. A hunk row shows `@@ −42,7 +42,10 @@` in
    monoMeta ink3 and the function context ellipsized at the end.
  - **Footer.** "Open diff", using the viewer with `diff = true`.
- **Files:** `components/DiffLines.kt`, `session/ToolRowView.kt` (DiffCard), `components/CodeBlock.kt` (diff fences).
  The highlighter overlaps **[after markdown]**.

### 11. The top bar says the wrong things

- **Screenshots:** shot:12 ("0% context" at the first prompt), shot:33 and shot:38 ("0% of 200k context" right after
  21%, because the failed turn reports 0 tokens), shot:54, shot:08.
- **What is wrong:**
  - The subtitle starts as "0% context". It then jumps to 21%, and back to 0% after an error.
  - On this phone the green "connected" dot is always on, so it carries no information.
  - The idle Quad's accessibility name is "Thinking".
  - Session info shows "Pi-Bolt v1.1.1" (the engine) while Settings shows app 0.2.1. Two versions are unexplained.
- **Why it matters:** The top bar is read on every glance. A wrong number there undermines every other number.
- **Fix:**
  - **Context.** Keep the last non-zero usage; a turn with no usage must not reset it (fix in the reducer or in
    `SessionContent`).
  - **Subtitle.** Show the session name (meta ink3) when there is one, else nothing. The context moves to a 16dp ring
    (ink4 track, ink2 arc, settle) right of the title. It turns warning from 80%, shows danger and "92%" (micro tnum)
    from 90%, and is hidden under 50%. Tap opens Session info.
  - **Status dot.** On this phone, show it only when not connected (warning or danger). Remote keeps it.
  - **Quad semantics.** `contentDescription` is the live status ("Pi is running a command") only while busy, else
    cleared.
  - **Versions.** Session info reads "Engine 1.1.1" and "App 0.2.1".
- **Files:** `session/SessionChrome.kt`, `session/SessionScreen.kt`, `session/SessionSheets.kt` (SessionInfoSheet).

### 12. Composer chips are vague, and the idle send button looks broken in coloured accents

- **Screenshots:** shot:08, shot:10, shot:64 (Claude theme), shot:73 (font 1.3).
- **What is wrong:**
  - "Medium ⌄" next to the model does not say medium what.
  - The disabled send button is `accent @ 20%`. In Graphite that is a grey disc that reads as pressable; in Claude it
    is a muddy brown disc (shot:64).
  - At font 1.3 the chips nearly touch the send button.
  - The idle composer is two rows, about 100dp, with an empty 52dp bar under a one-line field.
- **Why it matters:** The composer is on screen 100% of session time.
- **Reference:** Claude's composer shows "Sonnet 4.6 Low" as one chip (model and effort together)
  (https://mobbin.com/screens/0c723473-1bab-4bba-8786-10abe4b1d5a2). Cursor's composer separates context chips from
  run settings (https://mobbin.com/screens/e8b74435-43aa-48c9-8a50-d4762b40ea19).
- **Fix:**
  - **One chip.** Merge the two into "Fake Sonnet 4.5 · Medium". The model is label ink2 (middle ellipsis past 160dp),
    then " · Medium" in label ink3, hidden when the model has no reasoning.
  - **Chip actions.** Tap opens one sheet with two sections: "Model" (rows as today) and "Thinking" (52dp option rows
    as today). Long press keeps "next model" **[after features]**.
  - **Disabled send.** A 40dp circle with a 1dp lineStrong ring, no fill, and the arrow in ink4 (decorative while
    disabled). Enabled stays the accent fill with onAccent.
  - **Idle height.** The field's top padding goes from 14 to 12dp and the bar from 52 to 48dp (touch targets stay 48dp).
  - **Placeholder.** "Ask Pi to change, run or explain…" when idle, "Steer Pi…" or "Queue a follow-up…" while running.
- **Files:** `session/Composer.kt`, `session/SessionSheets.kt` (merged sheet), `components/Controls.kt` (Chip).

### 13. Process rows are noisy

- **Screenshots:** shot:23, shot:26, shot:27.
- **What is wrong:**
  - "Updated todos 7 times · Add a depth guard… · 4ms" is a full step row, although the todo card already shows the
    plan.
  - Durations of "1ms", "2ms" and "12ms" are printed on every step.
  - A failed "List ." row appears for a tool that is not enabled on the device, which Pi then retried another way.
  - "Read 1 file, ran 2 searches · parse.ts, MAX_DEPTH, *.test.ts" packs three kinds of things into one mono string.
- **Why it matters:** The answer is the product, and the process should recede. The references keep step rows muted
  and short.
- **Reference:** v0's collapsed "Thought for 1s ›" and "Explored project structure ›"
  (https://mobbin.com/screens/e180ae62-64e2-40eb-a851-dba120707fba). Perplexity's one-icon-per-tool log
  (https://mobbin.com/screens/4ea08f69-9f99-40a6-bc83-f003dcd20d3c).
- **Fix:**
  - **Todo calls.** Never shown as steps; the card is their view. A history fold still counts them in "N steps".
  - **Durations.** Hidden under 1 s on finished steps; the summed fold duration stays. Running steps keep their ticking
    elapsed time from 1 s, as today.
  - **Unavailable tools.** A tool that failed with "tool not found" or "disabled" reads as one meta ink3 line
    ("List isn't available here"), with no danger glyph. Hide it when the next step succeeds.
  - **Group rows.** "Read 1 file, ran 2 searches" (label ink2) only. The names move to the expanded group.
- **Files:** `session/TranscriptModel.kt`, `session/ToolFormat.kt`, `session/Rows.kt` (ToolGroupRowView).

### 14. Home and project lists have no hierarchy

- **Screenshots:** shot:05, shot:57, shot:06, shot:41, shot:66 (Vercel).
- **What is wrong:**
  - **Home.**
    - Before the first card come an empty 56dp bar (only a gear), then a 34sp title with the mark: about 230dp, a
      quarter of the screen.
    - Every project has the same folder tile, and three of four say "No sessions yet".
    - Each card carries a ⋮ button. That makes 4 identical overflow icons in a column.
    - The host card's meta truncates the most useful part: "web-dashboard · LAN · v1.1.2 · last seen 3 min. …"
      (shot:66; shot:70 at font 1.3).
  - **Project screen.**
    - Title, folder chip and full-width "New session" take 1/3 of the screen.
    - The empty state is one grey sentence floating on canvas (shot:06).
- **Why it matters:** Scanning gets slower as the list grows. On Vercel and Void, card fills barely differ from the
  canvas (surface1 vs canvas is 1.05 to 1.10:1), so the 1dp line is the only separation.
- **Reference:** Linear's dark project list keeps colour in tiny icons and grey group headers
  (https://mobbin.com/screens/c8edee5b-336d-4da6-a287-1b784d07e223). Todoist's grouped cards are one shade up from
  canvas (https://mobbin.com/screens/637e1a92-9c96-423a-9ba5-a72f3ec2049b).
- **Fix:**
  - **Home header.**
    - A 56dp large-title bar: the 24dp mark plus "Pi-Bolt" in title (20sp), and actions on the right (search
      **[after features]**, settings).
    - Collapse to a plain 56dp bar on scroll. Use `TopAppBarScrollBehavior`-like offset math in a `graphicsLayer`,
      without M3's tonal colours.
  - **Project rows.**
    - Group as "Pinned" and "Projects".
    - Drop the per-row ⋮ (keep long press, and add swipe-to-reveal Pin and Delete with 72dp actions).
    - The tile shows the project's monogram (2 letters, label SemiBold ink2 on surface2) unless a session is live (the
      Quad, as today).
    - Meta: "3 sessions · 2 h ago", or "Empty folder" in meta ink3 for no sessions.
  - **Host meta.** "Connected · web-dashboard" or "Last seen 3 min ago · web-dashboard", status first. Transport and
    version move to the host's overflow → Details.
  - **Project screen.**
    - The title collapses into the top bar on scroll.
    - "New session" becomes an extended FAB-style pill (52dp, accent) docked at the bottom like Home's button.
    - The tabs become a sticky 40dp `Segmented`.
    - Empty state: a surface1 card (radius 12) with "No sessions yet" (bodyStrong), one line, and the "Start with"
      chips from issue 7.
  - **Surfaces.** Raise the surface1 contrast on near-black themes: Void `#08090A` becomes `#0D0E10`, and Vercel
    `#0A0A0A` becomes `#0F0F0F`. Check that ink3 still passes on them (it does: 5.06:1 on Void, 5.93:1 on Vercel).
- **Files:** `home/HomeScreen.kt`, `projects/ProjectCard.kt`, `projects/ProjectScreen.kt`, `hosts/HostCard.kt`,
  `theme/Palette.kt`.

### 15. Settings is one long scroll with no structure

- **Screenshots:** shot:58 to shot:61, shot:71 (font 1.3).
- **What is wrong:**
  - Everything (providers, 7 theme tiles, accents, text size, density, motion, session, notifications, security,
    about) is on one page. The back arrow scrolls away.
  - The provider row shows the raw id "fake" (lowercase).
  - Four of the seven theme tiles (Graphite, Void, Codex, Vercel) are indistinguishable at tile size.
  - The accent option "Theme" is a white disc with a check, the same as "Ink".
  - There is no default-model or engine section **[after features: agent settings]**.
- **Why it matters:** Settings grows with every feature. Without structure, "where is X" becomes a scroll hunt, and
  the first item a new user needs (the provider) is mixed with appearance.
- **Reference:** Meta AI splits assistant settings from app settings, with icon rows
  (https://mobbin.com/screens/19fe6492-4ec9-40e7-b6c0-b8d4e986e83a). Raycast's "Manage Models" lists providers with
  a count subtitle (https://mobbin.com/screens/2565ce7a-1cbd-48cc-8b62-5342e0e540ce). YNAB's theme list has a live
  preview (https://mobbin.com/screens/d8a1904e-6749-4f36-a2f4-da5d61b08da4).
- **Fix:**
  - **Root page.** A pinned 56dp top bar with back and "Settings" (title), so the title is no longer a display-size
    block.
  - **Root rows.** 56dp rows (icon 20dp ink2, label body ink1, value meta ink3, chevron):
    - Model providers ("1 provider")
    - Pi on this phone (default model, thinking, retry) **[after features]**
    - Appearance ("Graphite · Theme accent")
    - Motion and haptics
    - Session display
    - Notifications ("Allowed")
    - Security
    - About ("0.2.1")
  - **Sub-pages.** The existing sections become sub-pages with the same 56dp bar.
  - **Theme tiles.** Draw the accent as the send button (already done) plus one line of syntax colour (string
    `#9ED3C6`) and an inline code chip, so tiles differ by more than canvas lightness. Add the hex of canvas under the
    name (monoMeta ink3) for the near-black themes.
  - **Accent "Theme".** Draw it as a split disc showing the current theme's own accent.
  - **Providers.** Show the display name from `ProviderCatalog`, falling back to the id with a capital first letter.
- **Files:** `settings/SettingsScreen.kt` (split into `settings/SettingsRoot.kt` plus per-section files),
  `settings/ProvidersSection.kt`, `nav/AppNav.kt` (sub-routes or an in-screen pager).

---

## Screen by screen

The remaining issues, by screen. P = priority in the implementation plan.

### First run and key sheet (shot:01–04)

- The sheet opens 450 ms after Home's first frame, over a Home that already shows two CTAs. Hold Home's body until
  the sheet decision is made (already gated on `metaLoaded && authLoaded`), so the cards do not flash first. (P2)
- "Search 21 providers" is good. Add `ImeAction.Search` and, with no match, "Use a custom endpoint" as a row instead
  of the dead-end "…set up with /login in a session" (a remote-only hint). (P1)
- "Save key" disabled reads well. After saving, close the sheet into the setup card's step 2 ("Create a project")
  rather than a snackbar alone. (P1)

### Home (shot:04, 05, 46, 47, 57, 66, 70)

- "Computers · 1 … Remote control ⌄": two labels on one row compete. Use "Computers" (SectionLabel) with the count as
  a micro badge, and the chevron only. Keep "Remote control" for the empty-state hint. (P2)
- No computers: the hint line plus two secondary buttons is fine. Add a 1-row illustration of the flow (computer
  glyph → QR glyph → phone glyph, ink3, 20dp) like Telegram Devices
  (https://mobbin.com/screens/a3c7d97d-e05a-45d6-a308-00ac1e44173d). (P2)
- At font 1.3, "Scan QR c…" truncates (shot:70). Let the two buttons stack vertically when either label would
  ellipsize (measure with `BoxWithConstraints`), or use the short label "Scan code". (P1)
- The host card shows both a status dot and the live Quad. Show only the Quad while live, and only the dot when
  not. (P2)
- Pairing a computer ends the on-device session ("This ends the current session with calc-parser", shot:52). Say it
  on Home too: the live project card gets "Ends if you open a computer" in its long-press sheet. Better: lift the
  one-session limit (an engine question, outside this pass). (P2)

### Project screen and files (shot:06, 41, 42, 43, 44, 45, 72)

- The folder chip `projects/calc-parser` middle-ellipsizes at font 1.3 to `projects/…lc-parser` (shot:72). Drop the
  `projects/` prefix in the chip (the title already says which project), and keep the full path in the overflow →
  Details. (P1)
- Session rows: the title is a truncated first prompt. Show `sessionName` when set, else the first prompt (newlines collapsed, end ellipsis). Add the
  unread dot from issue 3. (P1)
- Files:
  - Rows are 52dp with a generic file glyph for every type. Give the glyph a type from the extension:
    - code: `</>` 18dp ink3;
    - markdown: text glyph;
    - image: image glyph;
    - binary: generic file glyph.
  - Folders get a trailing chevron (ink4). (P1)
- Hide `.git` by default, with a "Show hidden files" toggle in the overflow menu. Other dot files stay, in ink3. (P1)
- Mark files changed in the last turn with a 6dp accent dot after the name, reusing `FileChange` from the turn fold.
  (P2)
- Add a sticky 40dp path bar (breadcrumbs: `calc-parser / src`, monoSmall; each segment tappable) instead of the "up"
  row. (P1)
- Viewer (shot:44):
  - Good: name and path in the title, wrap and copy, line numbers.
  - Add "Ask Pi about this file": a `TextAction` in the bar that opens the project's live session (or a new one) with
    `@src/parse.ts ` in the composer **[after features: mentions]**. (P1)
  - Tapping a line number selects the line range for the same action. (P2)
- Binary viewer (shot:45) says it is binary. Show its size and type, and offer "Share". (P2)

### New project and clone (shot:48, 49)

- The URL field validated while typing: after `https://github.c` it showed "Use an https://, ssh:// or git@host:path
  URL" in danger, and the name field filled with `github.c`. (This was seen live; shot:49 was retaken with a complete
  URL.)
  - Validate on blur or after 600 ms idle.
  - Derive the name only from a URL that already parses as a repository (`…/name(.git)?$`). (P0, small)
- Paste: when the clipboard holds a git URL, offer a "Paste github.com/opensec-git/Pi-Bolt" chip under the field.
  (P2)
- After "Clone", the session opens and sends `git clone <url> .`. Show a "Cloning…" notice row with Quad Run, not
  just a user bubble with a raw command. (P2)

### Session: idle, streaming, tools (shot:08–32)

- The user bubble keeps the typed keyword case but shows no sending state beyond 60% alpha. That is fine. A tap on a
  user bubble does nothing; make tap show the timestamp (meta ink3) under it. (P2)
- Thinking body: the 5-line live window is good. The collapsed "Thought" label after restore has no duration
  ("Thought"): persist `thinkDurations` per session in SessionMemory. (P2)
- Code block header: Wrap and Open are bare icons next to a labelled Copy (shot:19). Give them the same treatment:
  icon only for all three, with `contentDescription`, or labels for all. Icons only, plus the "Copied" toast state,
  is the recommendation. (P1)
- Code block streaming shows numbered blank lines between statements (shot:14: lines 2, 4). This comes from the fake
  model's word splitting and also happens with real models that emit `\n\n`; it is acceptable. (No action.)
- Table (shot:17): excellent. One gap: the numeric column header ("Left bp") is not end-aligned while its cells are.
  Align the header with its column. (P2)
- Links (shot:16): the underline is the only cue, in ink1. Use accent for link text (DESIGN.md already says accent
  marks links). (P1)
- Reply actions: "Copy · Ask again · ⋯" fade in after the turn. Good. They should not appear under error-only turns
  (issue 2). (P0, part of 2)
- Turn fold "Worked for 32s · 8 steps": add the file chips' count when collapsed ("· 2 files") so the fold says what
  changed. (P2)

### Session: errors and dialogs (shot:33–35)

- See issue 2. Also: "Compaction failed" after a context overflow is a second card for the same event. Merge it into
  the "Too long for…" notice as its explanation. (P0, part of 2)
- Pi's dialogs were not exercised (they need an extension that asks). Review them on the merged build. (–)

### Sheets (shot:36, 37, 38, 40, 41)

- Model picker: the subtitle "fake · 200k context · reasoning" uses the provider id. Use the provider display name.
  Group by capability when there are more than 8 models ("Reasoning", "Fast"), as Granola does
  (https://mobbin.com/screens/c67bcedd-a70d-4f27-a63d-1920ce43d40a); the features branch groups by provider, so
  keep provider groups and add a capability tag (micro badge) instead. (P2)
- Thinking sheet: each option is a bordered card, so five cards each have their own border. Use plain 56dp rows with
  a radio (DESIGN.md "option rows"), the selected one with a surface3 fill and a check, as in Grok's two-line rows
  (https://mobbin.com/screens/a2965031-64bf-4d8d-8116-c323b0c46c95). (P2)
- Session info: the context card is good. Add the session's cost and token totals, which are already in SessionFacts
  (`cost`, `cacheRead`), and the version fix from issue 11 **[after features: stats sheet]**. (P1)
- Reply long-press sheet (shot:40): Copy, Select text and View as Markdown. Add "Share" (it exists only under the
  reply's ⋯) so both entry points match. (P2)
- Overflow menu (shot:41): New session, Rename session, Session info. Add "Open project files" (it jumps to the
  project's Files tab), since there is no other path from a session to its files. (P1)

### Remote control (shot:50–56)

- Scan (shot:50): the camera window is a black square on the emulator, which is expected. Add a title above the
  window: "Scan the code from /remote-control" (title ink1). Today this sits in a pill at the bottom, which reads as
  a disabled button. Also add a torch toggle (48dp, top end), like Telegram's dark scanner
  (https://mobbin.com/screens/0444d454-4fcc-49cc-b07d-296a6af86b77). (P1)
- Pair confirmation (shot:52): clear and safe. "This ends the current session with calc-parser" is warning text
  without a warning colour. Use the warning glyph and warningSoft row. (P2)
- Pairing → session took about 4 s with only "Pairing…". Show the steps ("Connecting to Kushals-MacBook-Air",
  "Checking the key", "Opening web-dashboard") as the LoadingState sub-line. (P2)
- Remote session (shot:55): the subtitle "web-dashboard · 21% context" is right for remote. Apply issue 11's ring
  there too. (P1, part of 11)

### Settings (shot:58–61, 62, 65, 71)

- See issue 15.
- Text size preview: good. Show "Larger" etc. next to "Text size" in label ink2, not meta ink3 on the far end
  (contrast and proximity). (P2)
- The density setting has no visible preview. Reuse the prose preview card to show two rows at the chosen density.
  (P2)
- Notifications: "Allowed" in bodyStrong reads like a heading. Use a 56dp row: "Notifications" (body), "Allowed"
  (meta success) or "Off" (meta warning), "Open system settings" as the row's action. (P2)

### Landscape and large text (shot:68–74)

- See issue 1 for landscape.
- At font 1.3 the transcript is capped at 1.3x (good), but the chrome is not capped. "Explain how the par… · 3 min
  ago" still fits; the host meta and the Scan button truncate. The fixes are listed under Home. (P1)

---

## Navigation and information architecture

### Today

```
Home ─┬─ Project ─┬─ Session (on device)
      │           └─ Files ─ Viewer
      ├─ Settings (one long page)
      ├─ Scan ─ (pair) ─ Session (remote)
      └─ Host card ─ Session (remote)
```

### Proposed

```
Home ─┬─ Continue (recent sessions, all projects + computers)  ── Session
      ├─ Projects ─ Project ─┬─ Sessions ─ Session
      │                     └─ Files ─ Viewer ─ "Ask Pi about this file" ─ Session
      ├─ Computers ─ Session (remote) / Scan / Paste
      └─ Settings ─ Providers | Pi on this phone | Appearance | Motion | Session display | Notifications | Security | About
Session ─ overflow: New session · Rename · Session info · Project files
```

### Rules

- One session screen for both kinds. On-device and remote differ only in the overflow and the banner (already true).
- Back from a session returns to where it was opened from (Home's Continue or the project). Today it always goes to
  the project for local sessions.
- A notification opens its session directly.

### Taps to common tasks

Measured on 0.2.1 from Home with no sheet open; the proposed column assumes the fixes above.

| Task | 0.2.1 | Proposed | How |
|---|---|---|---|
| Continue the last session | 2 (project, session row) | 1 | Continue row |
| Check a running session | 2 | 1 | Continue row with live Quad |
| New session in an existing project | 2 (project, New session) | 2 | (or long press a project card → New session: 2) |
| Send a first prompt in a new session | 3 + typing | 2 + typing, or 3 with no typing | starter chip fills the composer |
| Change model | 2 (chip, row) | 2 | merged chip, Model section |
| Change thinking level | 2 | 2 | same sheet |
| Open a file in the current project from a session | 3+ (back, Files, folder…, file) | 2+ | overflow → Project files |
| Ask about a file | not possible from Files | 2 | Viewer → Ask Pi |
| Add an API key | 4 (Settings, Add provider, provider, Save) + paste | 4 | unchanged; first run 2 |
| Change theme | 2 (Settings, tile) | 3 (Settings, Appearance, tile) | the price of structure; acceptable |
| Pair a computer (section folded) | 3 (Computers, Scan, scan) | 3 | unchanged |
| See why a turn failed | 1 to read JSON; 0 actions | 0 to read, 1 to fix | ErrorNotice actions |

---

## Composer and session (detailed spec)

This is the key experience. The target layout, top to bottom:

```
┌ Top bar 56dp ───────────────────────────────────────────────┐
│ ←  calc-parser                         ◔ 21%   ⋮           │   ring only ≥ 50%
│    Parser depth guard (session name, meta ink3, optional)  │
├─────────────────────────────────────────────────────────────┤
│ 24dp top fade                                               │
│ transcript (max 720dp wide, 16dp gutters)                   │
│ …                                                           │
│ status line 32dp: ▦ Run  npm test · 11s   (one live Quad)   │
│ 16dp bottom fade                     (↓) centred, 12dp above│
├ Bottom stack (≤ 45% of window; ≤ 50% of space above IME) ───┤
│ Todos 1/4 · Fixing caret associativity              ⌄  40dp │   collapsed unless tapped
│ Queued · Steer "Also keep the README short"         ✕  44dp │
│ ┌ composer, radius 18, surface1, 1dp lineStrong ─────────┐  │
│ │ [img][img]                          (tray when images)  │  │
│ │ Steer Pi…                                    1–8 lines  │  │
│ │ +   Fake Sonnet 4.5 · Medium ⌄      Steer ⌄       ■/↑   │  │   bar 48dp
│ └─────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Composer

- **Field.** Prose 16sp, ink1, accent cursor; 12dp top and 6dp bottom padding; 1–8 lines (4 when the budget is tight).
  When the field is not focused, multi-line drafts show 3 lines and a fade.
- **Attach (+).** 48dp, ink2. When the model cannot read images, ink4 and a snackbar on tap (as today).
  **[after features]**: `@` mentions and the `/` palette are reached by typing. Keep `+` for images, and add "Mention
  a file" and "Commands" to a small `+` sheet like v0's "Add to Chat"
  (https://mobbin.com/screens/1e28a1e7-b82c-4077-879f-20972b255616), so the features can be found without knowing
  the syntax.
- **Model and thinking chip.** One chip (issue 12): 32dp visual, 48dp touch, pressed surface3.
- **Steer | Follow-up.** It appears only while running and the field has content: a 32dp chip "Steer ⌄" with a 6dp
  warning dot, or "Follow-up ⌄" with an ink4 dot. Tap toggles it, with haptic tick. The value is remembered per
  session.
- **Send / Stop.** 40dp in a 48dp target.
  - Send enabled: accent fill, onAccent arrow 20dp.
  - Disabled: no fill, a 1dp lineStrong ring, an ink4 arrow.
  - Stop: surface3 fill, a 12dp ink1 rounded square. To make Stop findable mid-run, add a 1dp ink3 ring around it
    while running and the field is empty.
  - Press scale 0.92 (tap). The mode swap stays as today (scale 0.6 → 1, fast spring).
- **Focus.** After Send, keep focus only if the IME was open and the user is steering (running). After a prompt that
  starts a run, hide the IME so the reply has room (today the keyboard stays up and the reply streams into about 400
  px, shot:12).
- **Slash suggestions and mentions.** A surface2 list above the field, capped at 5 rows (44dp), inside the budget.
  **[after features]**: check that the palette respects the budget.
- **Queue strip.** Keep it. The "Back to composer" swipe is good. Its rows are 44dp. When the budget is tight, show
  only "Queued · 2" (36dp) with Edit.
- **Working hairline.** Keep it (1dp accent, 35% band, 1400 ms). It is the only motion on the composer.

### Transcript

- **User bubble.** Keep it as is: surface2, radius 18/6, max 84%. In Void and Vercel, add a 1dp line border, because
  surface2 on `#000` is only 1.11:1 (shot:66).
- **Assistant prose.** Keep it. The table and code rendering are the best part of the app today.
- **Thinking.** Header row as today. While a thinking row is visible, the status line shows only the elapsed time
  (issue 4).
- **Tool rows.** Issue 13 for density, issue 5 for failure colour, issue 10 for diffs. The running bash card's live
  tail with the cursor is good; keep it.
- **Turn fold.** Keep it. Add "· 2 files" (P2).
- **Notices.** Issue 2.
- **Live status line.** Issue 4. It keeps its fixed 32dp height (DESIGN.md).
- **Scroll button.** Issue 9.
- **Empty session.** Issue 7.

### Session top bar

Covered by issue 11. The overflow gains "Project files" (P1). The connection banner stays.

---

## Motion

What should animate (keep or add):

| Element | Spec | Note |
|---|---|---|
| Live status line | Quad (mode), shimmer label, crossfade text (quick) | the single live spinner (issue 4) |
| Composer hairline | 1dp accent band, 1400 ms | keep |
| Streaming text reveal | 120 ms fade of new text, 6x12dp end block pulse 900 ms | keep |
| New live rows | rise 6dp (steps), 10dp (user bubble), standard, enter easing | keep |
| Tool card expand | `animateContentSize` standard in place, chevron 90° (quick) | keep |
| Todo card collapse/expand | `animateContentSize` standard in place; heading text crossfade (quick) | new auto-collapse (issue 1) must animate only on user action; IME-driven collapse is instant so it stays in sync with the keyboard |
| Scroll button | rise 16dp + scale 0.95→1 + fade (quick), exit 0.7x | moves to centre (issue 9) |
| Error notice actions | none on appear; Details disclosure `expandVertically` standard | errors must not bounce |
| Retry notice | Quad Connect 12dp while retrying; on final failure crossfade to the error glyph (quick) | replaces 3 cards |
| Continue row (Home) | live Quad with 600 ms debounce; unread dot appears without motion | Home is idle-silent otherwise |
| Screen transitions | shared-axis X, sheet duration; predictive back | keep |
| Settings sub-pages | same shared-axis X | new |

What should not animate:

- Anything in history, on restore, or on theme change (theme changes swap colours instantly; today they do, keep it).
- Step-row spinners while the status line is visible (issue 4); the top-bar Quad in that case.
- The todo in-progress row while the status line is visible.
- The card top sweep while the status line is visible.
- Error cards, retry counts and context-ring changes beyond a settle (400 ms) tween.
- IME-driven layout changes (they follow the keyboard inset frame by frame; no extra tween).
- Home while idle. Today it already stays still apart from the live Quad.

Reduce motion stays as defined in DESIGN.md. The new elements follow it: the ring settles without tween, chips toggle
without sliding.

---

## Accessibility

### Contrast (WCAG 2.1, computed from DESIGN.md hex values)

| Theme | ink3 / canvas | ink3 / surface2 | ink3 / surface3 | line / canvas | surface1 / canvas | accent / canvas | onAccent / accent |
|---|---|---|---|---|---|---|---|
| Graphite | 5.12 | **4.48** | **4.03** | 1.20 | 1.06 | 16.2 | 16.2 |
| Void | 5.50 | 4.95 | 4.61 | 1.21 | 1.05 | 17.4 | 17.4 |
| Ember | 5.21 | 4.51 | **4.12** | 1.26 | 1.07 | 15.4 | 15.4 |
| Claude | 5.91 | 4.69 | **4.10** | 1.32 | 1.10 | 5.33 | 5.33 |
| Codex | 6.01 | 4.98 | **4.44** | 1.24 | 1.08 | 16.5 | 16.5 |
| Vercel | 6.49 | 5.84 | 5.38 | 1.23 | 1.06 | 17.9 | 17.9 |
| GitHub | 6.50 | 5.56 | 4.82 | 1.28 | 1.09 | 6.11 | 6.11 |

Status colours on surface2 all pass: success 7.1 to 10.1, warning 6.8 to 9.7, danger 4.6 to 6.6. Danger on dangerSoft
passes in all themes: 4.72 (Claude) to 6.36.

Findings:

- **Graphite ink3 on surface2 is 4.48:1**, below AA for meta text, which is used on sheets (surface2: the model
  picker subtitles in shot:36, the key-sheet notes). DESIGN.md claims every text token passes on surface2. Raise
  Graphite ink3 to `#80868F` (4.66:1 on surface2, 5.33:1 on canvas). (P0, Palette.kt + DESIGN.md)
- **ink3 on surface3 fails in Graphite, Ember, Claude and Codex** (4.03 to 4.44). surface3 is used for menus, the
  snackbar, inline code chips and pressed chips. Rule: no ink3 text on surface3; use ink2 there. Check `MenuRow`, the
  snackbar message, `ImagesChip` (already ink2) and the QueueStrip swipe background ("Back to composer" meta ink2,
  fine). (P1)
- **Lines are 1.20 to 1.32:1.** That is fine for decoration. But for the composer, text fields and secondary buttons
  the 1dp line is the only boundary of an interactive component (WCAG 1.4.11 asks 3:1). The composer uses lineStrong
  (about 1.5:1). Proposal: at rest, text fields and the composer use ink4 (1.9 to 2.6:1; GitHub is lowest) for their 1dp
  border. This is a deliberate trade-off against the quiet look; document it in DESIGN.md either way. (P2)
- **The disabled send button** (accent @ 20%) is fine as disabled (exempt), but it reads as enabled (issue 12).

### Touch targets

- Good: IconAction, Chip, SendButton, TextAction and the image ✕ are all 48dp.
- **TextAction is 40dp tall** ("Copy", "Ask again", "Add key", "Retry"). The minimum is 48dp. Change `heightIn(min =
  40.dp)` to 48dp in `TextAction`, or keep the 40dp visual and add `minimumInteractiveComponentSize()`. (P0, one line)
- Code-block header icons (Wrap, Open) sit in a 40dp header. Check that they are 48dp wide and overlap the header
  edges rather than shrinking. (P1)

### TalkBack

Checked from `uiautomator` dumps, not by running TalkBack (a gap; run it on the merged build).

- **The send/stop button has no content description.** It has only an `onClickLabel`, so TalkBack reads "Button,
  double-tap to Send". Give it `contentDescription` "Send" or "Stop" via `semantics { contentDescription = … }`, and
  the e2e `ui.mjs send` helper can then use it. (P0)
- **The top-bar Quad's name is "Thinking" while idle.** Clear it when idle; when busy, use the live status text
  (issue 11). (P0)
- **The bottom stack has no live region.** Mark the status line `liveRegion = Polite` with debounced text (at most
  once per 2 s, and only on mode change), so blind users hear "Running npm test", then "Done". (P1)
- **Error notices:** heading semantics for the title, and the actions as buttons (issue 2). (P0, part of 2)
- **The todo card** semantics are well done (one node, state, row descriptions). Keep them through the collapse
  change.
- **The turn fold** reads "Worked for 2s · 7 steps · failed". After issue 5 it should read "…, 1 step failed". (P1)

### Large fonts

- The transcript cap (1.3x) works. In the chrome, fix the truncations (Home buttons, host meta, the folder chip).
  Test at 1.5 and 2.0 too, since Android 14+ allows up to 200% with non-linear scaling.
- The composer chip row at 2.0 will not fit. With the merged chip (issue 12) plus the Steer chip, the model name must
  ellipsize first, and the thinking part drops at fontScale ≥ 1.5. (P1)

---

## Performance felt by users

Measured on an emulator with a host load average of about 26 (three emulators and Gradle on a 16 GB Mac), so the
absolute numbers overstate the real cost. They are useful only to compare against each other and to say what to
measure on a device.

| Metric | Value | Note |
|---|---|---|
| Cold start (`am start -W`, TotalTime), first launch after install | 418 ms | lighter state: no projects, no hosts |
| Cold start, 5 runs with 4 projects and a host | 1485–1912 ms (median 1800) | `Displayed … +1s8` |
| First frame of the key sheet | +450 ms after Home's first frame (by design) | shot:01 |
| New on-device session until the composer accepts input | ≈ 4.5 s (5.5 s including the tap) | "Starting Pi-Bolt…" (shot:07); engine start |
| Session reopen from Home (restored history) | ≈ 4 s to the transcript | same engine start |
| Streaming, `gfxinfo` over 40 s | 95% janky frames, 50th pct 105 ms, 631 slow UI-thread frames | host-bound; not attributable |

What users feel, and what to do:

- **Engine start dominates.** Every new or reopened session waits about 4 s on "Starting Pi-Bolt…". Show the
  transcript from the session file at once (it is local) and let the composer show "Starting…" in its placeholder.
  The history renders while the engine boots. Today the LoadingState replaces the whole transcript. (P1,
  `SessionScreen`/`LocalSessions`)
- **Warm the engine.** Pre-warm when Home appears with projects (already `engine.prepare()`), and keep it alive for
  2 minutes after leaving a session, so a quick back-and-forth does not restart it. Verify the current lifetime. (P1,
  engine)
- **Cold start.** Measure on a real mid-range device (Pixel 6a class) with Macrobenchmark `StartupTimingMetric`. The
  emulator says at most 1.9 s under heavy host load, and the 418 ms clean-state launch suggests the work grows with
  data (projects listed, hosts, meta). DESIGN.md already moves this off the main thread. Confirm with a
  `reportFullyDrawn()` after `list != null`. (P1)
- **Streaming jank.** Not attributable on this host. Add `JankStats` in debug builds around the transcript, and run a
  Macrobenchmark `FrameTimingMetric` on the "audit-slow" stream with the fake model on a device before and after the
  polish pass. (P1, test infrastructure)

---

## Copy and tone fixes

| Where | Now | Proposed |
|---|---|---|
| Key step | "It is saved in Pi's auth.json on this phone and sent only to Anthropic." | "Your key stays on this phone and is sent only to Anthropic." |
| Provider search, no match | "No provider matches "x". Others can be set up with /login in a session." | "No provider matches "x"." + row "Use a custom endpoint" |
| Home, empty | "Code on this phone" / "A project is a folder Pi works in: it reads and edits files, runs git and your tests, here, without a computer." | "Set up Pi-Bolt" / step rows (issue 6); after setup: "Start your first project" / "A folder on this phone where Pi reads, edits and runs code." |
| Home, no sessions | "No sessions yet" ×3 | "Empty folder" or "3 sessions · 2 h ago" |
| Project, empty | "No sessions yet. Start one and ask Pi to look around, set the project up, or clone a repository." | "No sessions yet" + "Start with" chips |
| Empty session | "New session" (display) | project name (title) + branch |
| Composer placeholder idle | "Message Pi…" | "Ask Pi to change, run or explain…" |
| Composer, not connected | "Not connected" | "Starting Pi-Bolt…" (on device) or "Reconnecting…" (remote) |
| Error title | "Error" | class titles (issue 2) |
| Error body | raw JSON | one sentence; JSON under "Details" |
| Retry | "Retrying 3/3: 503: {"message":…" | "Overloaded · retrying 2 of 3" |
| Retry final | "Retry failed" | "Still overloaded after 3 tries" |
| Turn fold | "· failed" | "· 1 step failed" (unless the turn errored) |
| Bash card | "Command exited with code 1" | removed (badge says `exit 1`) |
| Diff/read footer | "View full output" | "Open diff" / "Open file" / "Show N more lines" |
| Status line | "Thinking · 0:03" next to a "Thinking" row | "0:03" (issue 4) |
| Subagent body | "thinking…" | live step list |
| Session info | "Pi-Bolt v1.1.1" | "Engine 1.1.1" and "App 0.2.1" |
| Host meta | "web-dashboard · LAN · v1.1.2 · last seen 3 min. …" | "Last seen 3 min ago · web-dashboard" |
| Model rows | "fake · 200k context · reasoning" | "Fake provider · 200k · Reasoning" |
| Scan | pill "Scan the code from /remote-control" | title "Scan the code from /remote-control"; sub-line "On the computer, run /remote-control in Pi-Bolt." |
| Settings, notifications | "Allowed" (bodyStrong) | row "Notifications · Allowed" |
| Accent option | "Theme" | "Theme's own" |

Tone: keep the current voice (short, concrete, second person, no exclamation marks). It is already good, apart from
the jargon above (auth.json, LAN, JSON).

---

## DESIGN.md changes implied

1. **Brand mark.** The app uses the logo drawable `ic_pibolt_logo` (yellow and blue bolt, shot:04). DESIGN.md
   describes a Quad-cell mark in brand yellow only, and says brand yellow is the only brand colour. Update DESIGN.md
   to the shipped logo (or ship the Quad mark), and record the blue as part of the logo.
2. Graphite ink3 becomes `#80868F`. Void surface1 becomes `#0D0E10`, Vercel surface1 `#0F0F0F`. Add the rule "no ink3
   on surface3".
3. The scroll button is centred above the composer, with a 24dp top fade and a 16dp bottom fade.
4. One live indicator rule (issue 4). Elapsed time is always written "11s" / "1m 3s".
5. The bottom-stack budget and the todo auto-collapse (issue 1). The reading width is capped at 720dp.
6. ErrorNotice (issue 2). The merged model chip and the disabled send (issue 12). TextAction becomes 48dp tall.
7. Home "Continue" section; Settings root plus sub-pages.

---

## Implementation plan

Tasks are sized at about 30 to 90 minutes each. They are grouped so that three agents can work in parallel without
editing the same files. Order inside a group is the suggested order. Start only after `android/features`,
`android/markdown`, `android/diagrams` and `android/subagents` have merged, then re-check every **[after …]** item
against the merged code. Each task ends with `./gradlew` checks, the relevant e2e area
(`android/e2e/session.sh`, `projects.sh`, …), and screenshots in the three audited themes.

### Agent A: session shell (bottom stack, composer, top bar)

Owns: `ui/session/SessionScreen.kt`, `ui/session/SessionChrome.kt`, `ui/session/Composer.kt`,
`ui/session/TodoPanel.kt`, `ui/session/Transcript.kt`, `ui/session/SessionSheets.kt`, `ui/components/Controls.kt`.

| ID | P | Task | Size |
|---|---|---|---|
| A1 | P0 | Bottom-stack budget + todo collapsed form + auto-collapse on IME / idle / short window (issue 1) | 90 |
| A2 | P0 | Landscape and compact height: composer stays on the IME, 1-line composer, todo behind a top-bar ring; 720dp reading width (issue 1) | 90 |
| A3 | P0 | Send/Stop `contentDescription`; top-bar Quad semantics; TextAction 48dp (Accessibility) | 30 |
| A4 | P0 | Context: keep last non-zero usage; subtitle = session name; context ring ≥ 50%; local status dot only when not connected (issue 11) | 60 |
| A5 | P1 | Steer/Follow-up as a composer chip; merged model+thinking chip and sheet; disabled send style; placeholder copy; hide IME after a prompt that starts a run (issue 12, Composer spec) | 90 |
| A6 | P1 | Transcript fades (24dp top, 16dp bottom) and centred scroll button (issue 9) | 45 |
| A7 | P1 | Top-bar Quad hidden while status line is on screen; publish `statusOnScreen` via CompositionLocal for B4 (issue 4, shell part) | 30 |
| A8 | P1 | Empty session: header, "Start with" chips, "All commands" link; hide `/llama` (issue 7) | 60 |
| A9 | P1 | Show restored history while the engine starts; composer placeholder "Starting Pi-Bolt…" (Performance) | 60 |
| A10 | P1 | Overflow "Project files"; Session info "Engine x / App y" and totals | 30 |
| A11 | P1 | Status line `liveRegion` with debounced text | 30 |

### Agent B: transcript rows (tools, errors, diffs)

Owns: `ui/session/Rows.kt`, `ui/session/ToolRowView.kt`, `ui/session/ToolFormat.kt`, `ui/session/TranscriptModel.kt`,
new `ui/session/ErrorText.kt`, `ui/components/DiffLines.kt`, `ui/components/CodeBlock.kt`, `ui/components/Quad.kt`,
`protocol/…/session/SessionReducer.kt` (notices only).

| ID | P | Task | Size |
|---|---|---|---|
| B1 | P0 | ErrorNotice: classify, title, sentence, actions (Retry, Switch model, Compact, Update key, New session), Details well (issue 2) | 90 |
| B2 | P0 | Retry collapse in the reducer; keep error notices on restore; no ActionsRow after error-only turns; merge "Compaction failed" into the overflow notice (issue 2) | 90 |
| B3 | P0 | Bash/Output cards: ink2 output with FAIL/ok line classes, drop exit line, single Copy, footer only when truncated; fold "· 1 step failed" (issue 5) | 60 |
| B4 | P1 | One live indicator: StepSlot `quiet` rest frame while status line visible; card sweep off; status line text without duplication; one elapsed format (issue 4, rows part; needs A7's local) | 60 |
| B5 | P1 | Diff numbering (new-file only), syntax colour in diffs, drop `---/+++` under a pathed header, "Open diff" (issue 10) | 90 |
| B6 | P1 | Step noise: hide todo steps, durations < 1 s, unavailable-tool rows; group row label only (issue 13) | 60 |
| B7 | P1 | Subagent card: live mini steps, header count + elapsed, answer preview (issue 8, transcript part) **[after subagents]** | 90 |
| B8 | P1 | Code block header icons consistent + 48dp; link colour accent; table numeric header alignment | 45 |
| B9 | P2 | Fold "· 2 files"; persisted thinking durations; user-bubble tap timestamp | 45 |

### Agent C: Home, projects, onboarding, settings, theme

Owns: `ui/home/*`, `ui/projects/*`, `ui/providers/*`, `ui/settings/*`, `ui/hosts/*`, `ui/scan/*`, `ui/nav/AppNav.kt`,
`ui/theme/Palette.kt`, `data/ProjectStore.kt`, `DESIGN.md`.

| ID | P | Task | Size |
|---|---|---|---|
| C1 | P0 | Palette: Graphite ink3 `#80868F`; Void/Vercel surface1; "no ink3 on surface3" sweep in owned files; DESIGN.md contrast table (Accessibility) | 45 |
| C2 | P0 | Home "Continue" section: recent sessions across projects and hosts, live/wait/unread states, 1-tap resume; `lastOpenedAt` in ProjectStore; notification opens the session (issue 3) | 90 |
| C3 | P0 | First run: setup card with steps, single primary action, provider list Popular/More with 2-letter badges, key-step Back, copy (issue 6) | 90 |
| C4 | P0 | Clone sheet: validate on blur / 600 ms idle; derive name only from a valid repo URL | 30 |
| C5 | P1 | Subagent sessions filtered from the project list and nested under the parent (issue 8, list part) **[after subagents]** | 60 |
| C6 | P1 | Home header collapse; project rows without ⋮ (long press + swipe), monogram tile, meta copy; host meta status-first; button stacking at large fonts (issue 14) | 90 |
| C7 | P1 | Project screen: collapsing title, docked New session, sticky tabs, empty card with chips, folder chip without `projects/` (issue 14) | 90 |
| C8 | P1 | Files: type glyphs, chevrons, hide `.git`, breadcrumb bar; Viewer "Ask Pi about this file" **[after features: mentions]** | 90 |
| C9 | P1 | Settings root + sub-pages, pinned top bar, provider display names, theme tiles with syntax line, accent "Theme's own" (issue 15) | 90 |
| C10 | P1 | Scan: title, sub-line, torch; pairing step text | 45 |
| C11 | P2 | Custom OpenAI-compatible endpoint in the provider sheet (needs an engine API for `models.json`) | 90 |
| C12 | P2 | DESIGN.md: brand mark, scroll button, one-indicator rule, bottom-stack budget, ErrorNotice, Continue, Settings structure | 45 |

### Coordination points

- **A7 → B4.** A7 publishes `LocalStatusOnScreen` (a `State<Boolean>`) from `Transcript.kt`, and B4 reads it in
  `Rows.kt` and `ToolRowView.kt`. Land A7 first, or let B4 stub the local and rebase.
- **B1 → A5.** The "Switch model" action in an ErrorNotice opens the model sheet. Expose `onSwitchModel` through the
  existing `onActions` callback in `SessionScreen` (A owns the wiring; B emits an `ErrorAction` value).
- **A8 needs the project marker files** (`package.json`, …). Read them in `ProjectFiles.kt`, which C owns. C adds
  `ProjectFiles.markers(dir)` as a 15-minute task first, or A reads the files inline on IO and C moves the code later.
- **C2 and the notification route** touch `nav/AppNav.kt` (C) and the notification builder in the service; neither is
  owned by A or B.

### Order across P levels

1. **P0 (11 tasks).** About 13 hours of agent time, about 4.5 hours in parallel. Ship as 0.3.x polish.
2. **P1.** Next.
3. **P2.** When convenient.

Re-shoot the screenshots in `~/pibolt-shots/audit/after/` with the same fake-model script for the before/after
comparison.

---

## Appendix: screenshots

All in `~/pibolt-shots/audit/current/` (Graphite unless noted; 1080x2400 portrait unless noted):

| File | State |
|---|---|
| 01-first-run-0300ms | launch, 300 ms |
| 02-first-run-key-sheet | first-run provider sheet |
| 03-first-run-key-step | Anthropic key step |
| 04-home-empty-no-key | Home, no projects, no key |
| 05-home-projects | Home with 4 projects |
| 06-project-no-sessions | project screen, empty |
| 07-session-starting | new session while the engine starts |
| 08-session-empty | empty session, commands list |
| 09-composer-focused-keyboard | composer focused |
| 10-composer-typed | prompt typed, keyboard open |
| 11-session-sending | just sent |
| 12-session-thinking-live | live thinking with status line |
| 13-session-streaming-text | streaming prose |
| 14-session-streaming-code | streaming code block |
| 15-session-streaming-nokbd | streaming, keyboard hidden |
| 16–19-session-finished-* | finished long answer: diff, shell block, table, code fold |
| 20, 21-session-tools-live* | tool turn (fast) |
| 22-session-tools-finished | finished tool turn, "failed" fold |
| 23-session-fold-open | opened turn fold with step rows |
| 24-session-tool-cards-open | failed bash card |
| 25-session-edit-card-rail | edit diff card, read card |
| 26, 27-session-todo-running-bash* | todo card + running bash + status line |
| 28-session-steer-typing | steering while running, keyboard open |
| 29-session-steer-sent, 30-session-queue-nokbd | after the steer, idle todo card |
| 31-session-subagent-running | Explore subagent running |
| 32-session-subagent-done | subagent done |
| 33-session-error | 400 context error + compaction failed |
| 34-session-retrying, 35-session-retry-failed, 39-session-errors-stacked | 503 retries |
| 36-sheet-model-picker, 37-sheet-thinking, 38-sheet-session-info | sheets |
| 40-sheet-reply-actions, 41-session-overflow-menu | reply long press, session menu |
| 41-project-sessions | sessions list with the leaked subagent session |
| 42-project-files, 43-project-files-src | Files tab |
| 44-file-viewer, 45-file-viewer-binary | viewer |
| 46-home-live-session | Home with a live session |
| 47-home-computers-open-empty | Computers section open, none paired |
| 48-sheet-new-project, 49-sheet-clone | new project, clone |
| 50-scan, 51-sheet-paste-link | scanner, paste sheet |
| 52-sheet-pair-confirm, 53-remote-pairing | pairing from an external link |
| 54-remote-session-empty, 55-remote-session-reply, 56-remote-overflow-menu | host session |
| 57-home-with-computer | Home with a paired computer |
| 58–61-settings-* | Settings, top to bottom |
| 62-theme-claude-settings, 63-theme-claude-home, 63b-theme-claude-project, 64-theme-claude-session, 64c-theme-claude-session-code | Claude theme |
| 64b-session-restored-graphite | restored session (missing first error) |
| 65-theme-vercel-settings, 66-theme-vercel-home, 67-theme-vercel-session | Vercel theme |
| 68-landscape-session, 69-landscape-session-keyboard | landscape (2400x1080) |
| 70–74-fontscale-* | font scale 1.3: Home, Settings, project, session |

The fake model and provider config used: `fake-model-audit.mjs`, with the keywords `audit-explain`, `audit-slow`,
`audit-tools`, `audit-todo`, `audit-agent`, `audit-error`, `audit-retry` and `audit-longtool`. It is not committed;
a copy is at `~/pibolt-shots/audit/fake-model-audit.mjs`. Move it into `android/e2e/` if the before/after re-shoot
should be repeatable.
