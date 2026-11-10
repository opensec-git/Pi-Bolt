# Remote Control

`/remote-control` lets a phone running the Pi-Bolt Android app attach to the session in this terminal. The phone shows
the live session (messages, thinking, tool calls and their output as they stream) and can send prompts, steer, queue
follow-ups, abort, switch model or thinking level, and answer the dialogs Pi raises. It can also [review](#review) the
session's folder read-only: git status, diffs, history, and the files themselves.

Remote control is off by default. Turn it on with:

```bash
pi-bolt remote-control enable
```

`pi-bolt remote-control disable` turns it off again, and `pi-bolt remote-control status` shows which it is. The command
sets `remoteControl.enabled` in your global settings (`~/.pi/agent/settings.json`), which is also the "Remote control"
toggle in `/settings`. A project's `.pi/settings.json` cannot turn it on. A session that is already open sees a change
made with the command at `/reload`; the toggle in `/settings` applies at once. When a session sees remote control turned
off while its `/remote-control` runs, it stops serving and says so.

While it is off, `/remote-control` is not listed in autocomplete. Typing it prints
`Remote control is off. Turn it on with: pi-bolt remote-control enable` and does nothing else. The
[background service](#background-service) does not install or serve.

Nothing of remote control loads until the command runs. To reach the computer with no terminal open, install the
[background service](#background-service).

## Use

| Command | What it does |
|---|---|
| `/remote-control` | Start the server and show a pairing QR code with the local network address and, when `cloudflared` is installed, a Cloudflare tunnel address, so the phone connects from anywhere. Without `cloudflared` it offers to install it (Homebrew on macOS, winget on Windows) and otherwise works on the local network. While running: show a new QR code. |
| `/remote-control lan` | Local network only: no tunnel (stops a running one). |
| `/remote-control tunnel` | Require the tunnel: an error if it cannot start. |
| `/remote-control copy` | Copy the pairing link, for the app's "Paste pairing link" (a phone without a camera, an emulator). It pairs one phone within 10 minutes; paste it only into the app. |
| `/remote-control stop` | Stop the server and the tunnel, and disconnect every phone. |
| `/remote-control devices` | List paired phones and revoke one. |

Scan the QR code with the app (or the phone's camera). The first scan pairs the phone; later scans of any QR code from the
same computer reconnect without pairing again. The footer shows `remote` with the number of connected phones.

The phone tries the addresses in order: the local network, then a Tailscale address (`100.x`, labelled `tailscale`, when
Tailscale runs on this computer and the phone is in the same tailnet), then the tunnel. A quick tunnel's address changes
each time it starts, so a phone away from home needs the new QR code after a restart; a named tunnel keeps its hostname.

### Settings

| Setting | Default | Meaning |
|---|---|---|
| `remoteControl.enabled` | `false` | Turns on `/remote-control` and the background service. Read from the global settings only; `pi-bolt remote-control enable` and `disable` set it. |
| `remoteControl.port` | `0` (random) | Port for the local listener. A fixed port lets paired phones reconnect on the LAN without a new QR code. |
| `remoteControl.tunnel` | `"quick"` | `"quick"` for a random `*.trycloudflare.com` address, `{ "name": "<tunnel>", "hostname": "<host>" }` for a named tunnel you configured with `cloudflared tunnel create` and a DNS route, or `"off"` for the local network only unless you run `/remote-control tunnel`. |
| `remoteControl.cloudflared` | `"cloudflared"` | Path to the `cloudflared` executable. |
| `remoteControl.askCloudflared` | `true` | Offer to install `cloudflared` when it is missing; "Don't ask again" sets it to `false`. |
| `remoteControl.readSecretFiles` | `false` | Let phones read files that look like secrets when [reviewing](#review) (`.env*`, keys, `.git/`, ...). |
| `remoteControl.service.tunnel` | the `remoteControl.tunnel` value | The [background service](#background-service)'s tunnel: `"quick"`, a named tunnel as above, or `"off"` for the local network and Tailscale only. `pi-bolt service install --no-tunnel` sets `"off"`. |

### Install cloudflared

macOS `brew install cloudflared`; Debian/Ubuntu: Cloudflare's apt repository; Windows `winget install --id Cloudflare.cloudflared`.
`/remote-control` offers to run the macOS or Windows command for you the first time, and only runs it after you choose
"Install it now"; Pi-Bolt never downloads it itself.

## Background service

The background service keeps remote control up after you log in, with no Pi-Bolt terminal open. Paired phones connect
to it as they connect to `/remote-control`. They see the computer's most recent session, and can list its projects and
sessions, open one, or start a new one in a project Pi-Bolt has worked in before. It is opt-in: nothing is installed until
you run `pi-bolt service install`.

`install` and `pair` need remote control on (`pi-bolt remote-control enable`); while it is off they print
`Remote control is off. Turn it on with: pi-bolt remote-control enable` and change nothing. `uninstall`, `status` and
`logs` work either way, and `status` says when it is off. A service that the service manager starts while remote control
is off logs why it does not serve and exits without opening a port or a tunnel; it exits cleanly, so the service manager
does not start it again until the next login. Turned off while it runs (serving, waiting for its port, or with its port
handed to a terminal's `/remote-control`), the service exits within 10 seconds, and from the moment it sees the setting
off it does not open its port or start a tunnel. After `pi-bolt remote-control enable`, run `pi-bolt service install` to
start it again, or `pi-bolt service uninstall` to remove it.

Installing or updating Pi-Bolt with the installers (`install.sh`, `install.ps1`) or `pi-bolt update` restarts a service
that runs, so that it runs the new version; with remote control off it then exits. A service that does not run is not
started, and nothing is installed. After an update through npm, Homebrew, Scoop or mise, `pi-bolt service restart` does
the same.

| Command | What it does |
|---|---|
| `pi-bolt service install [--no-tunnel]` | Start the service now and at every login. `--no-tunnel` sets `remoteControl.service.tunnel` to `"off"`. Run it again after changing a setting, or after installing `cloudflared`. |
| `pi-bolt service uninstall` | Stop it and remove every file it wrote (listed below). Paired phones stay paired, and settings stay as they are. |
| `pi-bolt service status` | Show whether it runs, its port, addresses, tunnel and phones, plus a QR code with the current addresses. |
| `pi-bolt service pair [--link]` | Pair a new phone with the running service: show a pairing QR code and wait until a phone uses it. `--link` also prints the link for "Paste pairing link". |
| `pi-bolt service logs [-n <lines>] [-f]` | Print the service's log; `-f` follows it. |
| `pi-bolt service restart` | Restart the service if it runs, so that it runs this version. It does nothing when the service is not installed or does not run. |

What `install` writes. It all goes in your user account's own folders, and none of it needs administrator rights:

| System | Service | Files |
|---|---|---|
| macOS | LaunchAgent `in.opensec.pi-bolt.remote-control`: starts at login (`RunAtLoad`), and starts again unless it exited cleanly (`KeepAlive` / `SuccessfulExit`). | `~/Library/LaunchAgents/in.opensec.pi-bolt.remote-control.plist` |
| Linux | systemd user unit `pi-bolt-remote-control.service` (`systemctl --user`): starts with your session (`default.target`), with `Restart=on-failure`. | `~/.config/systemd/user/pi-bolt-remote-control.service` (or under `$XDG_CONFIG_HOME`) |
| Windows | Scheduled task `Pi-Bolt Remote Control`: starts at your logon with your rights (`InteractiveToken`, `LeastPrivilege`). It runs through `conhost.exe --headless`, so no console window opens, and it starts again up to 3 times after a failure. | `~/.pi/agent/remote-control/service-task.xml` (what `schtasks /Create /XML` registered) and `service-task.cmd`, the script the task runs: a task cannot set environment variables, so the script sets them and starts the service |

The service file (on Windows, the task's script) holds the command, which is the `pi-bolt` executable followed by `service run`. It also holds `PATH`,
plus `PI_CODING_AGENT_DIR` and `PI_CODING_AGENT_SESSION_DIR` when your shell sets them. While it runs, the service writes
the following in `~/.pi/agent/remote-control/`:

- `service.log`: the log, mode 0600. It moves to `service.log.1` at 1 MiB.
- `service.err.log`: the process's own output on macOS. systemd keeps it in the journal instead.
- `service.token` and `service.sock`: the control channel (named pipe on Windows).

`uninstall` removes all of them.

How it behaves:

- **Port.** It listens on `remoteControl.port`, or on 48123 when that setting is 0. A fixed port lets phones find it
  again. It listens on every interface: the LAN, and Tailscale when Tailscale runs.
- **Tunnel.** It runs the tunnel of `remoteControl.service.tunnel`, which defaults to `remoteControl.tunnel` (a quick
  tunnel). It never asks anything. If `cloudflared` is missing, it logs that and serves the LAN and Tailscale only;
  `install` offers to install `cloudflared` in a terminal. If the tunnel stops, the service starts it again.
- **Changing addresses.** A quick tunnel gets a new address each time it starts. The service sends every connected
  phone the current addresses ([`remote_endpoints`](#messages)), on connect and whenever they change, so a phone that
  last connected at home can still reach the computer from elsewhere. Nothing outside this computer and the phone
  learns the address: there is no lookup or rendezvous server.
- **Pairing.** Only phones that are already paired get in. The service shows no QR code itself. `pi-bolt service pair`
  asks it for a one-time pairing code (single use, 10 minutes, as with `/remote-control`).
- **Status QR code.** `pi-bolt service status` shows a QR code with the current addresses. Its secret is all zeros and it
  has already expired, so it pairs nothing. Scanned by a paired phone, it gives that phone the new addresses; any other
  phone says it has expired.
- **Revocation.** `/remote-control devices` in any terminal revokes a phone. The service drops it within 10 seconds, or
  at its next message.
- **Sessions.** Sessions are created as in every other mode, so settings, extensions, skills, MCP servers and project
  trust apply as usual. While a phone is connected, a project's trust prompt goes to the phone. Without a phone, a
  project you never decided on stays untrusted. Extension dialogs go to the phones. A session that no phone used for 15
  minutes closes, and opens again from its file when a phone connects. On macOS, the first time the service works in
  Documents, Desktop or Downloads, macOS may ask whether to allow it.

### With `/remote-control` in a terminal

There is one listener at a time, so no two programs fight over the port and no phone is served twice. The service
gives way to the terminal:

1. `/remote-control` asks the service, over the control channel, for its port.
2. The service closes its connections with code 1012. Phones reconnect at once, to the same addresses.
3. The service stops listening, and hands over its tunnel when the terminal wants one.
4. The terminal listens on that port (unless `remoteControl.port` says otherwise), so phones now reach the terminal's
   session. `/remote-control lan` stops the handed-over tunnel too.
5. When the terminal runs `/remote-control stop` or exits, the service listens again, and its tunnel keeps its address.

The other way round: if a terminal already serves `/remote-control` when the service starts, the service waits until
that terminal lets go. Two copies of the service for one agent directory do not run: the second exits.

`pi-bolt service status` shows which side has the port. While a terminal has it, `pi-bolt service pair` refuses; use
`/remote-control` in that terminal.

## Security

- The listener accepts only WebSocket connections on `/rc` and answers nothing before the client completes a Noise
  handshake. Nothing secret is ever in a URL.
- Every connection is encrypted end to end with Noise (`Noise_IK_25519_AESGCM_SHA256`) between Pi-Bolt and the app.
  A tunnel (Cloudflare terminates its own TLS) only carries ciphertext.
- Pi-Bolt's static key lives in `~/.pi/agent/remote-control/host-key` (mode 0600). The QR code carries its public key, so
  the app talks only to this computer.
- Pairing needs a one-time 32-byte secret from the QR code. It travels only inside the encrypted first handshake message
  (readable only with Pi-Bolt's private key), is single use, and expires after 10 minutes. It is spent when a phone
  completes the handshake, so a phone that timed out halfway can retry with the same QR code.
- Paired phones are listed in `~/.pi/agent/remote-control/devices.json` by their public key. Only listed phones get past
  the handshake. `/remote-control devices` revokes a phone; it is disconnected at once.
- A paired phone can do what you can do by typing into Pi: Pi's tools run with your permissions. Revoke phones you lose.
- The [review commands](#review) only read. They see the session's folder and nothing outside it, cap what they send,
  run no shell, and refuse files that look like secrets unless `remoteControl.readSecretFiles` is set.
- The background service's control channel (`pi-bolt service status`, `pair`, and the terminal handoff) is a Unix
  socket in `~/.pi/agent/remote-control` (mode 0600, in a 0700 folder), or a named pipe on Windows. Every request
  carries the token from `service.token` (mode 0600), so only your user account gets an answer.

## Review

A phone can review the session's working tree without changing it: the app's Changes and Files views for a computer.
Both `/remote-control` and the background service answer these commands, and say so with `host.review: true` in
`remote_hello`. An app shows its review views only when the host sets it, so older apps and hosts keep working.

The commands are read-only. None stages, commits, edits, or runs a shell:

- **One folder.** Every path is relative to the open session's cwd and uses `/`. An absolute path, or any `..`
  segment, is refused. So is a path that leads outside the cwd through a symlink: the path is resolved (for a missing
  file, its nearest existing parent) and must stay inside the cwd's real path. When the cwd is a subfolder of a
  repository, status, diffs and history cover that subfolder only, with paths relative to it.
- **Secret files.** `rc_read_file` and `rc_git_diff` refuse paths that usually hold secrets, checking both the path
  asked for and the path it resolves to through symlinks. A commit shown with `rc_git_show` leaves those files out of
  its patch and names them in `withheld`; a rename is left out whole when either name matches. Matched
  case-insensitively:
  - names starting with `.env` (`.env`, `.env.local`, `.envrc`);
  - SSH keys: `id_` followed by a name without an extension, or with `.pub` (`id_rsa`, `id_ed25519.pub`);
  - `*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.jks`, `*.keystore`, `*.kdbx`, `*.ppk`;
  - `.netrc`, `_netrc`, `.npmrc`, `.pypirc`, `.pgpass`, `.git-credentials`, `credentials`, `credentials.json`,
    `auth.json`;
  - anything inside `.git`, `.ssh`, `.gnupg` or `.aws`.

  Setting `remoteControl.readSecretFiles` to `true` turns this off. Listings and status still show these files'
  names.
- **Caps.** A diff or commit patch stops at 2 MiB, cut at a line end; a file read at 1 MiB, cut at a character
  boundary (`truncated: true`). A listing holds at most 5000 entries, status at most 5000, a log page at most 200
  commits. At most 4 review commands run at once; more are refused until one finishes.
- **git.** The `git` on `PATH`, run with an argument list (no shell) in the cwd and stopped after 15 seconds. Every run
  passes `--no-pager --literal-pathspecs -c core.quotePath=false -c color.ui=false -c core.fsmonitor=false
  -c log.showSignature=false -c diff.submodule=short`; diffs add `--no-ext-diff --no-textconv --submodule=short`, so
  the repository's own diff, textconv and fsmonitor programs never run and a submodule shows only its commit ids.
  Every configured content filter is turned off (`-c filter.<name>.clean= -c filter.<name>.smudge=
  -c filter.<name>.process= -c filter.<name>.required=false` for each name in the git config), and status and diffs
  pass `--ignore-submodules=dirty`, so no filter program runs and a submodule's own files are not scanned.
  `GIT_OPTIONAL_LOCKS=0` keeps status from rewriting the index, and `GIT_TERMINAL_PROMPT=0` keeps git from asking for
  anything. A commit hash must be 4 to 64 lowercase hex characters and name a commit (a tag is peeled to its commit; a
  blob or tree is refused).

Each command takes an `id` and gets a `response` like any other. A refused or failed command has `success: false` and
an `error` to show. Before the background service has a session open, every review command fails with
`No session is open yet`.

| `type` | Fields | `data` of the response |
|---|---|---|
| `rc_git_status` | | `{ repo, branch, entries, total, truncated }`, below. Outside a git working tree: `{ repo: false, branch: null, entries: [], total: { added: 0, removed: 0 }, truncated: false }`. |
| `rc_git_diff` | `path`; `staged?` (default `false`); `origPath?`, the source of a staged rename | `{ path, staged, untracked, diff, truncated }`. `diff` is the unified diff (`git diff`), empty when nothing changed. Unstaged is the worktree against the index; `staged` the index against `HEAD`, with rename detection. `path` names one file: a folder is refused. An untracked file (`untracked: true`) diffs against `/dev/null`, so every line reads as added. Fails outside a repository. |
| `rc_git_log` | `limit?` (1 to 200, default 50); `skip?` (default 0) | `{ repo, commits: Commit[], more }`, newest first. `more` is true when older commits follow. A repository without commits gives `commits: []`. |
| `rc_git_show` | `hash`: 4 to 64 lowercase hex characters | `{ commit: Commit \| null, diff, truncated, withheld: string[] }`. `diff` is the patch against the first parent. |
| `rc_list_dir` | `path?` (default the cwd, `""`) | `{ path, entries: DirEntry[], truncated }`, sorted by name, `.git` included. |
| `rc_read_file` | `path` | `{ path, size, mtime, binary, truncated, content? }`. `size` is the file's full size in bytes, `mtime` Unix milliseconds. `content` is UTF-8 text and is left out for a binary file (a NUL byte in the first 8000 bytes). |

`rc_git_status` data:

```json
{
  "repo": true,
  "branch": { "head": "main", "oid": "4f1c…", "upstream": "origin/main", "ahead": 1, "behind": 0 },
  "entries": [
    { "path": "src/app.ts", "index": null, "worktree": "M", "kind": "modified", "conflicted": false,
      "staged": null, "unstaged": { "added": 3, "removed": 1 } },
    { "path": "docs/new.md", "origPath": "docs/old.md", "index": "R", "worktree": null, "kind": "renamed",
      "conflicted": false, "staged": { "added": 1, "removed": 0 }, "unstaged": null }
  ],
  "total": { "added": 4, "removed": 1 },
  "truncated": false
}
```

- `branch.head` is null when `HEAD` is detached, `branch.oid` before the first commit, `branch.upstream` without one.
- `index` is the staged change and `worktree` the unstaged one, each a porcelain letter or null: `M` modified, `A`
  added, `D` deleted, `R` renamed, `C` copied, `T` type changed, `?` untracked (worktree only), `U` conflict (both).
- `kind` sums the entry up: `modified`, `added`, `deleted`, `renamed`, `copied`, `typechange`, `untracked` or
  `conflicted`.
- `staged` and `unstaged` are the side's line counts from `git diff --numstat`, null when git counts none (no change
  on that side, a binary file, an untracked file). `total` adds them all up; as on the phone, untracked files do not
  count until they are added.

`Commit`: `{ hash, short, parents: string[], author, email, time, subject, body, refs: string[] }`. `time` is the
author time in Unix seconds, `body` the message after the subject, trimmed, and `refs` the names pointing at it
(`HEAD -> main`, `origin/main`, `tag: v1.0`).

`DirEntry`: `{ name, kind, size, mtime, symlink?, outside? }`. `kind` is `"file"`, `"dir"` or `"other"`. A symlink
has `symlink: true` and the `kind`, `size` and `mtime` of its target; one whose target is outside the cwd also has
`outside: true` and `kind: "other"`, and opening it is refused.

## Protocol

Version 1. The Android app and any other client implement this.

### Pairing QR code

The QR code holds the text `pibolt://pair#` followed by the unpadded base64url of a UTF-8 JSON object:

```json
{ "v": 1, "n": "macbook", "k": "<host public key>", "s": "<pairing secret>", "e": ["ws://192.168.1.20:48123/rc", "wss://quiet-fox.trycloudflare.com/rc"], "x": 1793000000 }
```

| Field | Meaning |
|---|---|
| `v` | Protocol version, `1`. |
| `n` | Host display name. |
| `k` | Host static X25519 public key, 32 bytes, base64url without padding. |
| `s` | Pairing secret, 32 bytes, base64url without padding. |
| `e` | WebSocket endpoints in preference order: LAN addresses (including Tailscale `100.x` addresses) first, then the tunnel. |
| `x` | Expiry of `s`, Unix seconds. |

The fragment (`#...`) is never sent anywhere; the app parses it locally.

### Connection

1. WebSocket to an endpoint, binary frames only. The client sends the subprotocol `pibolt-rc.1`.
2. Noise handshake `Noise_IK_25519_AESGCM_SHA256`, prologue the ASCII bytes `pibolt-rc/1`. The client is the
   initiator and knows the host static key from the QR code.
   - Frame 1, client to host: handshake message 1 (`e, es, s, ss`). Its payload is UTF-8 JSON:
     `{ "device": "Pixel 9", "app": "0.1.0", "pair": "<pairing secret>" }`. `pair` is present only when the client
     is not paired with this host yet.
   - The host accepts if the client static key is a paired device, or if `pair` equals the current unexpired, unused
     secret. Otherwise it closes with code 4403 and sends nothing. Accepting the secret here does not spend it: a client
     that gives up before frame 3 (a timeout, a permission prompt) can retry with the same QR code.
   - Frame 2, host to client: handshake message 2 (`e, ee, se`). Payload JSON:
     `{ "host": "macbook", "version": "1.1.1", "paired": true }` (`paired` is true when this connection paired the device).
   - Frame 3, client to host: the first transport message (step 3), `{ "type": "remote_ready" }`. It proves the
     client holds the session keys: anyone who recorded frame 1 can send it again, but cannot read frame 2. Until it
     arrives the host sends nothing more, runs no command, does not list the client as connected, and stores a newly
     paired device only now. Storing the device spends the secret, in one step: the secret pairs the first client to
     send `remote_ready`. Another client that offered it (frame 2 said `paired: true`) is closed with 4403 on its
     `remote_ready`, as is one whose secret expired or was replaced by a new QR code meanwhile; the same device on a
     second connection gets in as paired. Without `remote_ready` within 3 seconds of frame 2 the host closes with 4408;
     any other first message closes with 4400.
3. Transport: each later binary frame is one Noise transport message (AES-256-GCM, nonce = per-direction counter
   starting at 0, as in the Noise spec). The plaintext is `flag || data`: flag `0x00` marks the last chunk of a
   message, `0x01` a chunk with more to follow. Chunks carry at most 65000 data bytes. The concatenated data of a
   message is one UTF-8 JSON object.
4. Close codes: 4400 malformed handshake or decryption failure, 4403 not authorized, 4408 no handshake message within 3 seconds of connecting, or no `remote_ready` within 3 seconds of frame 2 (both count as failed handshakes), 4429 too many failed handshakes from this address; an address may have at most 3 connections in their handshake at once, 1001 host stopping, 1012 the background service handed the port to a terminal or took it back (reconnect at once, to the same addresses), 1000 replaced: the same device completed a newer handshake (after `remote_ready`), so the host closed its older connection (a client does not reconnect on it; it already uses the newer one).
5. Keepalive: the host sends WebSocket pings every 20 seconds and drops a client that has not answered the previous one (20 to 40 seconds after it went silent); clients may ping too.

### Messages

After the client's `remote_ready` the host sends `remote_hello`, then the session's event stream.

Host to client:

| `type` | Meaning |
|---|---|
| `remote_hello` | `{ protocol: 1, host: { name, version, cwd, service?, parallelSessions?, review? }, state: RpcSessionState, messages: AgentMessage[], streamingMessage?: AgentMessage, dialogs: extension_ui_request[], commands: RpcSlashCommand[], omittedMessages: number, features: string[] }`. `features` names the optional parts of the protocol this host answers (`"subagent_stream"`: [Subagent streams](#subagent-streams)); hosts before it send none. It stays under 24 MiB: a longer session sends only its newest messages, and `omittedMessages` counts the older ones left out. Sent on connect and again whenever the TUI switches to another session (`/new`, `/resume`, `/fork`): the client replaces everything it shows. `host.service` is `true` from the background service, which also answers the service commands below; `host.parallelSessions` is `true` from a service that keeps a running session going when the phone opens another (below). `host.review` is `true` from hosts that answer the [review commands](#review); older hosts leave it out. |
| `remote_endpoints` | `{ endpoints: string[] }`: every address the host listens on now, in the order of the pairing code's `e` (LAN, Tailscale, then the tunnel). Sent after `remote_hello` on connect, and again whenever the list changes (a quick tunnel's new address, another network). No `id`; clients that do not know it ignore it. |
| session events | Exactly the RPC/JSON event stream ([JSON Event Stream](json.md)): `agent_start`, `message_start`, `message_update` (deltas), `message_end`, `tool_execution_start/update/end`, `queue_update`, `compaction_start/end`, `auto_retry_start/end`, `agent_end`, `agent_settled`, ... Clients ignore types they do not know. |
| `response` | Answer to a client command, as in [RPC Commands](rpc-commands.md). |
| `extension_ui_request` | A dialog (`select`, `confirm`, `input`, `editor`) or `notify`, as in [RPC Extension UI](rpc-extension-ui.md). The same dialog is open in the terminal; whichever side answers first wins. |
| `extension_ui_dismissed` | `{ id }`: the dialog was answered in the terminal, timed out or was cancelled; the client closes it. |
| `extension_ui_request` (views) | `setView`, `updateView` and `showView` of the [mobile UI kit](mobile-ui.md#protocol), to phones that sent `set_client_features` with `"views"`. `remote_hello` carries the current views as `views` (`[{ viewKey, slot, view, rev, toolCallId?, turn? }]`) to every phone, and `"views"` in `features`; open sheets are among its `dialogs`. |

Client to host: `remote_ready` once, first; then RPC commands with an `id` for correlation, `extension_ui_response`, and
`extension_ui_event` (a view's tap, change or submit, [mobile UI kit](mobile-ui.md#events)). `set_client_features` applies
to that phone's connection only. Accepted commands: `set_client_features`, `prompt`,
`steer`, `follow_up`, `abort`, `clear_queue`, `get_state`, `get_messages`, `get_available_models`, `set_model`,
`cycle_model`, `get_available_thinking_levels`, `set_thinking_level`, `cycle_thinking_level`, `compact`, `abort_retry`,
`get_session_stats`, `get_commands`, `get_last_assistant_text`, `set_session_name`, `get_pages`, `open_page`,
`close_page` (the session's [package pages](mobile-ui.md#package-pages-and-home-tiles); `open_page` needs `"views"`), and
the [review commands](#review)
when `host.review` is true. Any other type gets
`{ type: "response", success: false, error: "Not available over remote control" }`.

The background service (`host.service: true`) also answers these. They work before a session is open:

| `type` | Fields | `data` of the response |
|---|---|---|
| `list_projects` | | `{ projects: [{ cwd, name, sessions, updatedAt }] }`: folders with Pi-Bolt sessions that still exist, newest first (at most 100). |
| `list_sessions` | `cwd?` (default: the open session's folder) | `{ sessions: [{ path, id, cwd, name?, preview, updatedAt, messageCount, open?, running? }] }`, newest first (at most 100). `preview` is the first message, shortened. `open: true`: the service has it open; `running: true`: it runs a turn or a compaction now. |
| `switch_session` | `sessionPath`: a `path` from `list_sessions` | `{ cancelled }`, then a new `remote_hello`. Any other file is refused. |
| `new_session` | `cwd?`: a `cwd` from `list_projects` (default: the open session's folder); `parentSession?` | `{ cancelled }`, then a new `remote_hello`. A folder Pi-Bolt has no sessions in is refused. |

`switch_session` and `new_session` never abort a turn (`host.parallelSessions`). The service keeps up to 3 sessions
open, each with its own runtime, and phones see one of them. An idle session moves to the new one, as `/resume` and
`/new` do in a terminal. A session that runs a turn keeps running in the background, and `switch_session` to it shows
it again as it is. To make room, an idle background session closes, the least recently used first. When all 3 are
running, the command fails with `3 sessions are running on this computer. Stop one, or wait.` A background session
closes after 15 idle minutes. A background session's dialogs still reach the phones.

For an extension command (`/name ...`), the `prompt` response arrives only when the command finishes; its dialogs
arrive meanwhile as `extension_ui_request`, so clients must not wait for the response before showing them.

`prompt` while the agent is streaming needs `streamingBehavior` (`"steer"` or `"followUp"`), as in RPC mode. Prompts run
extension commands, prompt templates and skills; the terminal's own built-in commands (such as `/model`) do not run from
a phone.

### Subagent streams

A subagent of the subagents extension runs as a session of its own on the computer, so the parent's events carry only
its prompt, progress line and answer. A client that shows one run asks for the agent's own conversation (hosts with
`"subagent_stream"` in `remote_hello.features`; RPC mode answers the same commands):

| Client to host | Fields | Response |
|---|---|---|
| `subagent_subscribe` | `runId`: the id of the `Agent` tool call that started the run | `success: true`, or an error for an unknown run, a missing `runId` or more than 8 streams at once |
| `subagent_unsubscribe` | `runId` | `success: true` |

The host then sends, until the client unsubscribes, disconnects or gets a new `remote_hello`:

| `type` | Meaning |
|---|---|
| `subagent_entries` | `{ runId, file?, reset, messages: AgentMessage[] }`: finished messages of the agent's session file, oldest first. The first batch has `reset: true` and starts from the beginning; later ones follow lines as they are appended. A compaction arrives as `{ role: "compactionSummary", summary, tokensBefore }`; the system prompt is left out. |
| `subagent_events` | `{ runId, events }`: while the agent's session runs in this process, its assistant message as it streams (`message_start`, `message_update` deltas as in the event stream, `message_end`) and `tool_execution_start` / `tool_execution_end` (`{ toolCallId, toolName, args }`, `{ toolCallId, toolName, isError }`), sent in batches about every 80 ms. A client that joins mid-message gets a `message_start` with what is written so far. The finished message also arrives in `subagent_entries`. |

The `Agent` call is looked up in the parent's saved current branch as well as its messages, so a run that a compaction
took out of the model's context can still be followed. The run is found by the `Agent` call's agent id (from its
result, or `resume`) or by its prompt, among sessions whose header names the parent's file in `parentSession`. Matching by prompt skips sessions made before the call and sessions
of other runs' agents, takes the newest, and is checked again once the result names the agent: a wrong guess is dropped
and the stream starts over with `reset: true`. Runs with a known agent id may share a file (a resumed run and the
original). Streams are read-only. A message or event larger than 256 KiB of JSON is cut (long text, thinking and tool
arguments, nested values too, shortened; images and `details` dropped; when still too large, only its text is kept).
