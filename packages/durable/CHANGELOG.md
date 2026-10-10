# Changelog

## [1.1.0] - 2026-10-07

### Breaking Changes

- `Storage` implementations must honor the new `order` field of `ConversationQuery`, `EntryQuery`, `TaskQuery`, and `SubmissionQuery`, and continue a cursor in the order it was returned with. A storage that ignores it returns pages in the wrong direction; the conformance suite covers both orders ([#10546](https://github.com/earendil-works/pi/issues/10546)).
- `TaskRuntime.context()` takes the cutoff as an options object, matching `Conversation.context()`: `runtime.context(conversationId, context, { at })` replaces `runtime.context(conversationId, context, at)`.

### Added

- `ToolExecutionApi.models` and `HookApi.models`: the Harness's `models`, so tools and hooks can resolve models and make requests with the same catalog, credentials, and request transforms as generation ([#10395](https://github.com/earendil-works/pi/issues/10395)).
- `Conversation.context(context, { at })` returns the model context as of a visible earlier entry, the same view `fork(at)` would start with, without creating a conversation ([#10512](https://github.com/earendil-works/pi/issues/10512)).
- `ScanOrder` (`ascending` or `descending`) as `order` on conversation, entry, task, and submission scans, including `Tx.scanTasks()` and `Conversation.entries()`, so hosts can page the newest tasks first. Defaults are unchanged: entries newest first, everything else oldest first. A cursor continues in its scan's order, so the query may omit `order`; asking for the other order with it throws ([#10546](https://github.com/earendil-works/pi/issues/10546)).
- Task records carry `startedAt` and `endedAt`, wall-clock times the Session stamps at the first change to `running` and at the change to `terminal`, with `HarnessOptions.now` or the new `createSession(storage, { now })` option. `startedAt` survives waits and reopen. `inspect()` shows them on each live task's record. Records written by earlier versions have neither ([#10549](https://github.com/earendil-works/pi/issues/10549)).
- `pi.tool-result` messages carry `durationMs`: how long `execute()` took in that attempt, measured with a monotonic clock. Calls that did not execute, and interrupted or aborted calls, have none ([#10549](https://github.com/earendil-works/pi/issues/10549)).
- Assistant messages carry the `durationMs` pi-ai measures for each response ([#10549](https://github.com/earendil-works/pi/issues/10549)).
- `openDurableObjectSqliteStorage(ctx.storage)` in `@earendil-works/pi-durable/storage/sqlite/cloudflare`: SQLite storage on a SQLite-backed Cloudflare Durable Object.
- `settings.contextRetentionMs` (default ten minutes): how long an idle conversation keeps its last context read in memory; `0` drops it once idle.

### Changed

- `runtime.context()` keeps each conversation's last read range and derived view in memory: a later read by any of its tasks with the same head marker scans and derives only the entries committed since. A generation no longer rereads and re-derives the whole transcript for each model request. Busy conversations keep it; idle ones for `settings.contextRetentionMs`.

### Fixed

- A conversation's first system message, and the baseline after a compaction or reset, now leads the model context instead of following the input that started the run. Providers with native mid-conversation tool changes, such as Anthropic, keep the prompt cache across later tool changes instead of rewriting it in full ([#10542](https://github.com/earendil-works/pi/issues/10542)).

## [1.0.4] - 2026-10-05

### Breaking Changes

- `NodeExecutionEnv.watch()` fails with `permission_denied` when a target, or a watched directory itself, cannot be read for lack of permission; before, it was watched as if missing. Later rescans that hit this report `{ error }` with `permission_denied` instead of `invalid`.
- The env conformance suite checks three more `watch()` behaviors: changes to the file a watched symbolic link points to, recursive coverage below a target that a non-recursive target overlaps, and a directory replaced at the same path. Custom environments that passed the 1.0.3 suite may need changes.

### Added

- `createPowerShellTool()` in `@earendil-works/pi-durable/tools`: a `powershell` tool that runs `pwsh`, else Windows PowerShell, directly through argv `exec` (no other shell parses the command), with UTF-8 output and the `bash` tool's output window, spill and errors.

### Fixed

- The `read` tool reads a file that grows while it is read (an active log) instead of failing with "changed while it was read"; it reads again only when the file shrank or was rewritten in place.
- `NodeExecutionEnv.watch()`: a directory replaced at the same path gets a new native watcher; a target that is a symbolic link to a file reports changes to that file; a non-recursive target no longer stops an overlapping recursive target from covering subdirectories; closing during a rescan no longer leaks watchers.
- Bounded tool output keeps a U+FEFF at the start of the retained tail, and drops a byte-order mark only at the very start of byte output.
- `settings.progress` fields given as `undefined` keep their defaults.

## [1.0.3] - 2026-10-05

### Breaking Changes

- `FileSystem` requires `openBinaryReader()` and `openDirReader()`; custom environments must implement them.
- `BinaryReader` requires `scanLines()`.
- `FileSystem` requires `watch()`.
- `Shell.exec()` accepts an argv array besides a shell string, and `ShellExecOptions.onOutput` receives a third `info` argument naming the stream (`stdout` or `stderr`); custom environments must accept both forms and pass the stream.

### Added

- `openBinaryReader()` for bounded positional reads of one opened regular file, with `noFollow` to refuse a final-component symlink.
- `openDirReader()` for paged directory listings that read metadata only for returned entries.
- Argv form of `exec()`, which runs a program without a shell.
- `createEnvConformance()` and `registerEnvConformance()` in `@earendil-works/pi-durable/testing` for checking custom `ExecutionEnv` implementations.
- `settings.progress` with `partialIntervalMs` and `outputIntervalMs` configures how often generation partials and running tool output are committed; defaults stay 100 ms ([#10357](https://github.com/earendil-works/pi/issues/10357))
- `ShellExecOptions.window` and `ShellOutputInfo.skipped`: an environment may omit shell output outside the caller's retained tail and report the omission, so remote environments need not transfer output the caller drops. `ToolExecutionApi.outputWindow` provides the window and `output(chunk, skipped)` counts omissions; the `bash` tool passes them through.
- `BinaryReader.scanLines()` locates and measures a span of lines in one pass inside the environment; `LineScanner`, `StreamDecoder`, `rangeDecoder()` and `startsWithBom()` in `@earendil-works/pi-durable/env` let other environments decode and scan exactly like `NodeExecutionEnv`.
- `FileSystem.watch()` reports changes to files and directories, recursive with excludes, including missing targets, with explicit `overflow` and `error` and a `native` or `polling` mode. `NodeExecutionEnv` polls on Windows (native watchers there keep directories open, which blocks renaming their parents) and on network and FUSE file systems, and on macOS rescans shortly after installing native watchers because FSEvents misses changes made right after `fs.watch` returns; its `watch` option sets the mode, poll interval, and directory limit.

### Changed

- The `read` tool reads only the file's header, one scan, and the lines it shows, instead of loading the whole file; its results are unchanged.

### Fixed

- `NodeExecutionEnv.flushFile()` on a directory fails with `is_directory` on Windows, as on POSIX.
- Tail-retained tool output no longer depends on when progress commits happened: a progress snapshot compacted the stored output to the kept window, which could move where a later window's first line started.
- `NodeExecutionEnv` shell output and text line readers no longer drop a U+FEFF that follows a chunk boundary; Node's streaming `TextDecoder` with BOM handling dropped it, unlike decoding all of the bytes at once.

## [1.0.2] - 2026-10-04

### Fixed

- Persisted a distinct provider session UUID per conversation and forwarded it for prompt-cache and session affinity ([#10424](https://github.com/earendil-works/pi/issues/10424))

## [1.0.1] - 2026-10-03

## [1.0.0] - 2026-10-01

### Added

- Initial release of `@earendil-works/pi-durable`, a durable agent harness. See the [README](README.md) and the [design document](https://github.com/earendil-works/pi/blob/main/packages/durable/docs/spec.md).
