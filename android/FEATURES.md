# Pi-Bolt Android: feature parity with the terminal

Every user-facing feature of Pi-Bolt's terminal UI (TUI) and RPC, and its state in the Android app as of 0.5.0.

- **State**: `done` (works on a phone as in the terminal, or better), `partial` (some of it, or only on-device or only
  remote), `missing`, `n/a` (has no meaning on a phone; not counted below).
- **Priority** for a phone: `P0` (expected in daily use), `P1` (regularly useful), `P2` (rare, or for power users).
- **Where**: `both` = on-device sessions and remote control; `device` = only the engine on this phone (remote control
  accepts a subset of RPC commands, see docs/remote-control.md).

Sources: packages/coding-agent/docs (slash-commands.md, keybindings.md, settings.md, rpc-commands.md, rpc.md,
rpc-extension-ui.md, remote-control.md) and src/modes/interactive.

## Summary

| | P0 | P1 | P2 | Total |
|---|---|---|---|---|
| done | 41 | 63 | 31 | 135 |
| partial | 0 | 1 | 4 | 5 |
| missing | 0 | 1 | 6 | 7 |
| **Total** | 41 | 65 | 41 | 147 |

Not counted: 12 `n/a` rows (terminal-only features). Before this branch the same rows stood at 53 done, 15 partial and
55 missing: it moved 37 rows (25 missing to done, 11 partial to done, 1 missing to partial) and closed every P0 gap.

Built in 0.3.0 (this branch): command palette with built-ins, extension commands, prompt templates and skills;
argument hints; `@` file mentions; `!` / `!!` shell; model and thinking cycling (long-press, hardware keys, session
sheet); models grouped by provider; session statistics sheet with the footer's context bar and token line; transcript
search with jump to match; message actions (copy plain or Markdown, send again, edit and resend, regenerate, share);
resume, fork and duplicate from the session; export or share as Markdown, JSONL or HTML; image viewer (zoom, save,
share); auto-compaction status; per-session drafts; hardware-keyboard shortcuts; on-device agent settings.

Built in the features2 branch (16 P2 rows missing to done, 1 to partial, 3 new rows): session tree (`/tree`: branches,
go back, labels); session list with sort, name filter, search inside messages, rename and delete; packages manager
(`/packages`, Settings); MCP servers (`/mcp-servers`, Settings); project trust (`/trust`); `/reload`; `/scoped-models`;
retry and compaction numbers (`/tuning`, Settings); "Stop retrying"; `/import`, `/share`, `/bug`; skills and prompt
templates browser (`/skills`); notifications that open their exact session, and one when a background agent finishes.
Tree navigation, labels and the tool list go through a small helper extension the app writes to
`~/.pi/agent/extensions/pibolt-app.js` (RPC has no command for them).

Built in the undo branch (3 new rows): per-turn checkpoints in a shadow git repository outside the project, "Review
changes" with per-file diffs, undo of a turn or a file with per-file choices for files changed since, restore to
before a turn with a conversation fork, and redo of any restore. On this phone only (remote files are on the computer).

## Prompting and the composer

| Feature | Terminal | App | Where | Pri |
|---|---|---|---|---|
| Send a prompt | Enter | done | both | P0 |
| Multi-line input | Shift+Enter | done: the composer grows to 6 lines, then scrolls | both | P0 |
| Steer while Pi works | Enter while running | done: Steer / Follow-up switch | both | P0 |
| Follow-up while Pi works | Alt+Enter | done: Steer / Follow-up chip next to send, Alt+Enter | both | P0 |
| Stop the run | Esc | done: Stop button, Esc on a hardware keyboard | both | P0 |
| Queue view (steering and follow-ups) | above the editor | done: queue strip, steer/follow-up marked | both | P0 |
| Restore queued messages to the editor | Alt+Up | done: Edit / swipe returns them to the composer, Alt+Up | both | P1 |
| Attach images | Ctrl+V paste, drag | done: photo picker, up to 4, resized | both | P0 |
| Paste an image from the clipboard | Ctrl+V | done: Ctrl+V, or "Paste image" in the + sheet | both | P2 |
| Slash command menu | `/` | done: fuzzy palette, built-ins + extension, prompt and skill commands with source badges | both | P0 |
| Argument hints | menu | done for built-ins; host commands when the host sends `argumentHint` | both | P1 |
| Prompt templates (`/name`) | `/` | done (expanded by Pi) | both | P0 |
| Skills (`/skill:name`) | `/` | done | both | P1 |
| Extension commands | `/` | done | both | P0 |
| `@` file mention | `@` | done: fuzzy over the project's files and folders | device | P1 |
| `!command` shell | `!` | done: runs in the project, output streams into a step | device | P1 |
| `!!command` (out of context) | `!!` | done | device | P2 |
| Prompt history | Up / Down | done: Up / Down in the composer, "Prompt history" sheet, recent prompts on the empty session | both | P1 |
| Draft kept per session | editor buffer | done: saved per session across restarts | both | P1 |
| Send shortcut | Enter | done: Ctrl+Enter on a hardware keyboard | both | P1 |
| Haptics on send, stop, answers, errors | — | done | both | P1 |
| External editor | Ctrl+G | n/a | — | — |
| Kill ring, undo, word motions | Ctrl+K/Y/W, Ctrl+- | n/a (Android text editing) | — | — |
| Clear editor / exit | Ctrl+C / Ctrl+D | n/a | — | — |
| Suspend | Ctrl+Z | n/a | — | — |

## Models and thinking

| Feature | Terminal | App | Where | Pri |
|---|---|---|---|---|
| Model picker | `/model`, Ctrl+L | done: search, grouped by provider, current first | both | P0 |
| `/model <search>` | `/model provider/id` | done: opens the picker filtered | both | P1 |
| Cycle model | Ctrl+P | done: long-press the model chip, Next in Session, Ctrl+P | both | P1 |
| Cycle model backwards | Shift+Ctrl+P | missing | both | P2 |
| Thinking level picker | `/thinking` | done: levels the model supports | both | P0 |
| `/thinking <level>` | `/thinking high` | done | both | P1 |
| Cycle thinking level | Shift+Tab | done: long-press the thinking chip, Next in Session, Shift+Tab | both | P1 |
| Scoped models for cycling | `/scoped-models` | done: `/scoped-models` writes `enabledModels`; the picker shows only those | device | P2 |
| Save default model / thinking | Ctrl+S in selectors | partial: the last model picked becomes the default (as in Pi); thinking default in Settings | device | P1 |
| Provider sign-in (API key) | `/login` | done: Settings, Model providers | device | P0 |
| Provider sign-in (OAuth) | `/login` | missing | device | P1 |
| Sign out | `/logout` | done: remove a key in Settings | device | P1 |
| llama.cpp router | `/llama` | n/a | — | — |

## Sessions

| Feature | Terminal | App | Where | Pri |
|---|---|---|---|---|
| New session | `/new` | done: menu, `/new`, project screen | device | P0 |
| Resume a session | `/resume` | done: `/resume` sheet, menu, project screen list | device | P0 |
| Rename session | `/name` | done: menu, `/name <name>`, Session sheet | both | P1 |
| Fork from an earlier message | `/fork` | done: `/fork` sheet, "Edit and resend" on a message | device | P1 |
| Duplicate session | `/clone` | done: menu, `/clone` | device | P2 |
| Session tree navigation | `/tree`, double Esc | done: `/tree` sheet, forks indented, active path marked; continue from any entry (a prompt returns to the composer) | device | P2 |
| Tree labels, filters | `/tree` keys | done: label / unlabel an entry; Messages, Prompts, Labeled, Everything | device | P2 |
| Session info and statistics | `/session` | done: Session sheet (`get_session_stats`) | both | P0 |
| Context usage in the footer | footer | done: top-bar context meter (kept through failed turns), context bar in Session | both | P0 |
| Tokens in/out, cache, cost | footer | done: footer line and totals in Session | both | P0 |
| Delete a session | session picker Ctrl+D | done: sessions sheet (`/resume`, search on the project screen), with confirmation; not the open one | device | P2 |
| Rename a saved session | session picker | done: sessions sheet (a `session_info` entry, as `/name` writes) | device | P2 |
| Session list sort / named filter | Ctrl+S / Ctrl+N | done: recent, name or size; filter by name or first prompt | device | P2 |
| Search all sessions by text | — | done: "Messages" in the sessions sheet: matches per session with a snippet | device | P2 |
| Import a JSONL session | `/import` | done: `/import` opens the Android file picker; the file is copied into the project (id kept) and opened | device | P2 |
| Switch sessions from another project | `/resume` all | partial: per project, from Home | device | P2 |
| Quit | `/quit` | n/a (back / Home) | — | — |

## Context and compaction

| Feature | Terminal | App | Where | Pri |
|---|---|---|---|---|
| Manual compaction with instructions | `/compact [text]` | done: sheet or `/compact <instructions>` | both | P0 |
| Auto-compaction indicator | status | done: "Auto-compacting" / "Context full, compacting" | both | P0 |
| Compaction summary in the transcript | summary block | done: expandable notice with the summary | both | P0 |
| Branch summary | summary block | done | both | P2 |
| Auto-compaction on/off | settings | done: Settings, Pi on this phone | device | P1 |
| Compaction thresholds (reserve, keep recent) | settings.json | done: `/tuning`, Settings → Retry and compaction (threshold %, reserve, keep recent) | device | P2 |

## Transcript

| Feature | Terminal | App | Where | Pri |
|---|---|---|---|---|
| Streaming replies | — | done | both | P0 |
| Markdown | renderer | done (markdown work in 0.3.0) | both | P0 |
| Mermaid diagrams | `markdown.mermaid` | done (diagram work in 0.3.0) | both | P1 |
| Code blocks: highlight, copy, full view | — | done | both | P0 |
| Tool calls with output | tool blocks | done: folded per turn, cards, full-screen output | both | P0 |
| Expand / collapse all tool output | Ctrl+O | partial: per step and per turn | both | P2 |
| Thinking blocks | — | done: per block, default in Settings | both | P1 |
| Toggle all thinking | Ctrl+T | partial: Settings default, per block | both | P2 |
| Diffs of edits | diff view | done: inline and full screen | both | P0 |
| Search the transcript | Ctrl+Shift+F | done: search bar, n of m, jump (opens folds and groups) | both | P1 |
| Jump to previous / next prompt | Ctrl+Shift+Up/Down | missing | both | P2 |
| Scroll to bottom, follow output | Ctrl+End | done: scroll button with unseen dot | both | P0 |
| Select and copy text | selection | done: "Select text" | both | P0 |
| Images in user messages | inline | done: thumbnails, full-screen viewer (zoom, save, share) | both | P1 |
| Images in tool results | inline | done: thumbnails on the tool card, full-screen viewer | both | P1 |
| Todo list | extension | done: todo panel | both | P1 |
| Subagent progress | extension | done (subagent work in 0.3.0) | both | P1 |
| Extension notices / custom messages | — | done | both | P1 |
| Errors and retries | status | done: one notice per failed turn with a human title, the status code, attempts and Retry / Switch model / Compact / Update key | both | P0 |
| Stop an automatic retry | `abort_retry` | done: the error notice reads "Retrying 2 of 3" with "Stop retrying" (`abort_retry`) | both | P2 |
| Cache-miss notices | `showCacheMissNotices` | missing | both | P2 |

## Message actions

| Feature | Terminal | App | Where | Pri |
|---|---|---|---|---|
| Copy the last reply | `/copy`, Ctrl+X | done: Copy under the reply, `/copy` | both | P0 |
| Copy as plain text or Markdown | — | done | both | P1 |
| Send a prompt again | — | done: Ask again, "Send again" | both | P1 |
| Regenerate an answer | `/fork` + resend | done: "Regenerate" (forks before the prompt, sends it) | device | P1 |
| Edit and resend | `/fork` | done: "Edit and resend" | device | P1 |
| Share a message | — | done: Android share sheet | both | P1 |

## Review and undo (checkpoints)

| Feature | Terminal | App | Where | Pri |
|---|---|---|---|---|
| Review what a turn changed | — | done: "Review changes (3 files, +40 −12)" under the turn, per-file diffs from snapshots | device | P1 |
| Undo a turn or one file | — | done: Undo this turn / per file; files changed since ask Keep or Restore; Redo | device | P0 |
| Restore to before a turn | `/fork` (conversation only) | done: files back to the turn's start, then the conversation forks there | device | P1 |

## Export and share

| Feature | Terminal | App | Where | Pri |
|---|---|---|---|---|
| Export as HTML | `/export` | done: shared through Android | device | P1 |
| Export as JSONL | `/export x.jsonl` | done: the session file, shared | device | P1 |
| Export as Markdown | — | done: share or copy (remote too) | both | P1 |
| Share as gist / Radius link | `/share` | partial: `/share` opens the share sheet (Markdown, JSONL, HTML); no gist upload | device | P2 |
| Bug report | `/bug` | done: `/bug [text]`: description plus app, engine, device, model and engine log, shared through Android | both | P2 |

## Extensions and dialogs

| Feature | Terminal | App | Where | Pri |
|---|---|---|---|---|
| Select / confirm / input / editor dialogs | dialogs | done: sheets, countdown, hide and come back | both | P0 |
| Notifications from extensions | `notify` | done: snackbar | both | P1 |
| Status line and widgets | `setStatus`, `setWidget` | done: lines above the composer | both | P1 |
| Put text in the editor | `set_editor_text` | done | both | P1 |
| Bundled extensions (todo, subagents) | packages | done | device | P1 |
| Web search (bundled pi-web-access: `web_search`, `fetch_content`, `get_search_content`, `source_check`) | package | done (0.5.4): chats and projects, Settings > Agent > Web search (on; off leaves the tools out); a Sources pill per run of web steps with site favicons | device | P1 |
| Structured questions (bundled rpiv-ask-user-question: `ask_user_question`) | package (TUI questionnaire) | done (0.5.4): one native question card ("1 of 3", radio or checkboxes, Type something, Back / Next / Submit) answering the extension's RPC dialogs; dismiss declines | device | P1 |
| Background shell jobs (app extension pibolt-bg: `bash_background`, `bash_output`, `bash_kill`, `bash_jobs`) | — (Pi's `bash` blocks) | done (0.6.0): project sessions only (chats have no shell); each job in its own process group, log + 1 MB ring buffer, 32 KB per read, at most 5 running; ends with the session and the engine; Background pill and sheet (last lines, full log, Stop); running jobs keep the session busy and the notification up; printed ports feed the Preview chip | device | P1 |
| Install / manage packages | `pi install`, settings | done: `/packages`, Settings: bundled todo and subagents, npm/git install, switch off, remove, each package's commands and tools; built-in extensions on/off | device | P2 |
| MCP servers | `/mcp` | done: `/mcp-servers`, Settings: state and tools per server (`pi mcp list`), add by URL or command, remove | device | P2 |
| Reload resources | `/reload` | done: `/reload` restarts the engine in the same session (also re-reads trust, packages, MCP) | device | P2 |
| Project trust | `/trust` | done: `/trust`: what needs trust, saved decision, trust / don't / clear, default for all projects | device | P2 |
| Skills and prompt templates browser | — | done: `/skills`: read a skill or template, "Use" puts it in the composer | both | P2 |
| Custom TUI components | `ctx.ui.custom` | n/a | — | — |

## Settings (docs/settings.md)

| Feature | Terminal | App | Where | Pri |
|---|---|---|---|---|
| Steering mode | `steeringMode` | done | device | P1 |
| Follow-up mode | `followUpMode` | done | device | P1 |
| Default thinking level | `defaultThinkingLevel` | done | device | P1 |
| Auto-retry | `retry.enabled` | done | device | P2 |
| Retry counts and delays | `retry.*` | done: `/tuning`, Settings → Retry and compaction | device | P2 |
| Hide thinking blocks | `hideThinkingBlock` | done: thinking shown collapsed by default | both | P1 |
| Block images | `images.blockImages` | missing | device | P2 |
| Theme | `/theme` | done: seven app themes, accents, text size, density | both | P0 |
| Reduce motion | — | done | both | P1 |
| Skill commands on/off | `enableSkillCommands` | missing | device | P2 |
| Enabled models | `enabledModels` | done: `/scoped-models` | device | P2 |
| HTTP proxy, timeouts, transport | `httpProxy`, … | missing | device | P2 |
| Shell path and prefix | `shellPath`, … | n/a (bundled shell) | — | — |
| Terminal display options | `tuiMode`, `terminal.*`, `editorPaddingX`, … | n/a | — | — |
| Quiet startup, changelog | `quietStartup`, `collapseChangelog` | n/a | — | — |
| Show hotkeys / changelog | `/hotkeys`, `/changelog` | n/a | — | — |
| Settings menu | `/settings` | done: Settings screen | both | P0 |

## Keyboard (hardware keyboards on a phone or tablet)

| Feature | Terminal | App | Where | Pri |
|---|---|---|---|---|
| Send | Enter | done: Ctrl+Enter (Enter adds a line, as Android IMEs expect) | both | P1 |
| Stop | Esc | done | both | P1 |
| Next model | Ctrl+P | done | both | P2 |
| Next thinking level | Shift+Tab | done | both | P2 |
| Follow-up | Alt+Enter | done | both | P2 |
| Restore queue | Alt+Up | done | both | P2 |
| Open model picker | Ctrl+L | done | both | P2 |
| Search | Ctrl+Shift+F | done | both | P2 |
| Custom keybindings | keybindings.json | n/a | — | — |

## Remote control and the phone

| Feature | Terminal | App | Where | Pri |
|---|---|---|---|---|
| Pair with a computer (QR, paste) | `/remote-control` | done | remote | P0 |
| Reconnect after drops | — | done | remote | P0 |
| Several computers, forget, revoke | `/remote-control devices` | done | remote | P1 |
| Review the computer's changes and files | `git`, editors on the computer | done (0.6.0): with a Pi-Bolt that offers it (`host.review`), More has Changes (status, diffs, history and commits) and Files (folders, the file viewer, Ask Pi), read-only, and the composer shows the working tree's "+N −M" pill; secret files stay on the computer | remote | P1 |
| Background notifications (done, needs answer) | — | done: tapping one opens that exact session; also "background agent finished" | both | P0 |
| Keep running in the background | — | done: foreground service while a turn, a compaction, background agents or a `!` command runs; a partial wake lock while local work runs (2 h cap without progress); Stop and Open on its notification; answers and replies from notifications; Run in background setup (battery optimization, OEM autostart) | device | P0 |
| Several sessions at once | several terminals | done: up to three on this phone (one per project, each with its own engine) plus remote control; Running on Home, a switcher in the session bar; at the cap an idle session makes room, a busy one is never stopped | device | P1 |
| Projects (create, clone, rename, pin, delete) | — | done | device | P0 |
| Browse project files | — | done: Files tab and viewer | device | P1 |
| Git changes (status, diffs, stage files or hunks, discard, commit) | git in Pi's bash | done: project Changes tab with the engine's git; "Ask Pi" drafts the message | device | P0 |
| Git branches and history | git in Pi's bash | done: switch and create branches, log with commit details and diffs | device | P1 |
| GitHub sign-in | — | done: OAuth device flow (when the build has a client id) or a personal access token, sealed by the Keystore | device | P1 |
| Push, pull, clone private repositories | git in Pi's bash | done: with progress and plain errors; clone picks from the user's GitHub repositories; Pi's own git uses the account too | device | P1 |
| Pull requests | `gh pr create` | done: title, body (Pi can draft), base branch, then the link | device | P1 |
| Interactive terminal | `!` runs one command | done: bash on a pty in the project (engine environment, secrets), tabs, full screen, extra keys, copy and paste, font size, scrollback; keeps running in the background | device | P1 |
| Dev-server preview | — | done: "Preview: localhost:N" chip in the terminal and the session, in-app browser limited to localhost | device | P1 |
| Pi sees its own preview | — | done (0.6.0): project sessions on the phone get `preview_open`, `preview_screenshot` (a PNG of at most 1280 px, shown in the transcript), `preview_console` (console, uncaught errors with stacks, failed requests), `preview_click`, `preview_type`, `preview_scroll` and `preview_eval`, driving the same WebView the user sees ("Pi is using the preview"); only detected dev-server ports on localhost; never in chats | device | P1 |
| Project secrets (.env) | — | done: Keystore-encrypted, masked, injected into the project's terminals and engines (not the model's messages), optional .env | device | P1 |
| Edit project files | `edit` tool | done (0.5.0): tabs with unsaved dots; highlighting while typing (incremental, off the main thread); find and replace with regex groups, case, whole word, undo; go to line; auto-indent, bracket pairs, soft wrap; read-only over 1 MB | device | P1 |
| Ask Pi about a file or selection | `@file` | done (0.5.0): the viewer puts an `@mention` and the selected lines into the project's composer | device | P1 |
| Editor keyboard shortcuts | — | done (0.5.0): Ctrl+S, Ctrl+F, Ctrl+H, Ctrl+G, Ctrl+Z, Ctrl+Shift+Z / Ctrl+Y, Ctrl+W, Ctrl+Tab, F3 | device | P2 |
| Files in and out | — | done (0.5.0): import files or a folder (SAF), a zip as a new project; export a project or folder as a zip (Save to… or Share); share a file; streamed, cancellable, zip-slip safe | device | P1 |
| Share into Pi-Bolt | — | done (0.5.0): files, images and text from other apps: attach to a project's composer, import, or a zip as a project | device | P1 |
| Voice input | — | done (0.5.0): composer mic, Android speech recognition (on-device when available), live partial text | both | P1 |
| Hands-free voice mode | — | done (0.6.0): listen, send on end of speech, "Working" with the Quad and the tool, a spoken 2-3 sentence summary of the reply (Android TextToSpeech, no extra model call), listen again; question cards read out and answered by voice; mute, barge-in where the phone cancels echo, pauses for calls and other audio; speech rate and voice in Settings | both | P2 |
| Files on tool cards | — | done (0.5.0): a path on this phone opens in the file viewer and editor | device | P1 |
| Language runtimes: `node`, `npm`, `npx`, `python3`, `pip` | your system's | done: Node.js 22 LTS and Python 3.14 in the APK, in Pi's bash, `!` commands and the terminal (all with the engine's environment); npm/pip installs over HTTPS | device | P1 |
| Runtimes screen (versions, sizes, npm/pip caches) | — | done: Settings > Runtimes, project menu | device | P2 |
| TalkBack labels and 48 dp targets | — | done for the new controls | both | P0 |

## What remains

- **P1** (2 missing, 2 partial): OAuth sign-in; images in tool results; prompt history in the composer (Up/Down; today
  only the empty session lists recent prompts); choosing the default model in Settings (today the last model picked).
- **P2**: `/share` as a gist link; block images; cache-miss notices; skill commands on/off; HTTP proxy and timeouts;
  jump between prompts; cycle model backwards; paste images; the missing hardware shortcuts. "Retry now" (skip the
  retry delay) has no RPC command; Stop retrying is there.
