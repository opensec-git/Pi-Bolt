# Android end-to-end suite

Drives the debug APK on emulators with `adb`, `uiautomator` dumps and a fake OpenAI-compatible model on the host.
No real provider or API key is used: the fake provider's key is the dummy string `e2e-dummy-not-a-key`.

## Requirements

- macOS host with the Android SDK at `$HOME/Library/Android/sdk` (or `ANDROID_HOME`), Node.js, bash.
- A debug APK of `in.opensec.pibolt` that ships the on-device engine (`lib/arm64-v8a/libpibolt.so`).
- Emulators. `emulator-5554` (`pibolt-api36`) is shared and refused.

## Run

```sh
cd android/e2e
cp /path/to/app-debug.apk build/app-debug.apk      # or set APK=/path/to/app-debug.apk

./emulators.sh create                              # pibolt-e2e-1, pibolt-e2e-2 (once)
./emulators.sh start pibolt-bench:5556 pibolt-e2e-1:5558 pibolt-e2e-2:5560
./run-all.sh --install emulator-5556 emulator-5558 emulator-5560
./emulators.sh stop 5556 5558 5560
```

`run-all.sh` shards the areas over the serials, runs them in parallel and prints a pass/fail table.
`--areas session,extensions` picks areas; `--skip-engine` runs only the cases that need no engine.

One area on one device:

```sh
SERIAL=emulator-5556 E2E_INSTALL=1 ./extensions.sh
```

On a 16 GB Mac, start at most three emulators and wait for the load average to settle first.

## Areas

| Script | What it checks |
| --- | --- |
| `session.sh` | new session, streamed reply, steer, abort, fork, resume, engine killed, app killed |
| `lifecycle.sh` | rotate mid-stream, background and foreground, the foreground-service notification while a turn runs |
| `tools.sh` | bash tool calls on the device: ls, write a file, git commit, rg, fd |
| `providers.sh` | first-run key sheet, adding a (dummy) key, the key reaching the model, removing it, "Later" |
| `projects.sh` | create, rename, pin, reopen after restart, delete with confirmation |
| `files.sh` | Files tab: listing, folders, text and binary viewer, a file Pi writes |
| `extensions.sh` | bundled extensions: `todo` tool and todo panel, foreground `Agent` card, no engine processes left after close |
| `frames.sh` | frame timing per scenario on a phone (not pass/fail; measures the installed build, see its header) |

## Files

- `lib.sh`: shared helpers (device setup, UI queries, assertions, fake model, evidence). Its header lists the
  environment variables (`SERIAL`, `APK`, `E2E_OUT`, `E2E_ENGINE`, `E2E_INSTALL`, `FAKE_PORT`, `FAKE_MODEL`, timeouts).
- `ui.mjs`: queries a `uiautomator` dump (`text=`, `text~=`, `desc=`, `desc~=`, `any=`, `re=`).
- `fake-model-scripted.mjs`: the fake model. Keywords such as `e2e-ls` trigger a scripted bash tool call.
- `fake-model-ext.mjs`: adds `todo` and `Agent` scripts for `extensions.sh`, and `smooth-md` / `smooth-agents` for
  `frames.sh`.
- `gen-long-session.mjs`: a 40-turn session with code, tables, mermaid and tool steps for `frames.sh`.
- `fake-model-todo.mjs`: multi-step todo and Explore-agent scripts for manual UI checks.
- `emulators.sh`: create, start and stop the headless emulators.

## Output

Everything goes under `out/` (gitignored); the APK copy goes under `build/` (gitignored).
A run writes `out/run-<time>/<serial>/<area>/`: `results.tsv`, screenshots, `logcat.txt`,
`fake-model-requests.jsonl` and, for each failed case, a screenshot with its visible texts and logcat.
