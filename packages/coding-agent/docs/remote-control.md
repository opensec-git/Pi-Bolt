# Remote Control

`/remote-control` lets a phone running the Pi-Bolt Android app attach to the session in this terminal. The phone shows
the live session (messages, thinking, tool calls and their output as they stream) and can send prompts, steer, queue
follow-ups, abort, switch model or thinking level, and answer the dialogs Pi raises.

Nothing of this loads until the command runs. Without `/remote-control`, Pi behaves exactly as before. To reach the
computer with no terminal open, install the [background service](#background-service).

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
| `remoteControl.port` | `0` (random) | Port for the local listener. A fixed port lets paired phones reconnect on the LAN without a new QR code. |
| `remoteControl.tunnel` | `"quick"` | `"quick"` for a random `*.trycloudflare.com` address, `{ "name": "<tunnel>", "hostname": "<host>" }` for a named tunnel you configured with `cloudflared tunnel create` and a DNS route, or `"off"` for the local network only unless you run `/remote-control tunnel`. |
| `remoteControl.cloudflared` | `"cloudflared"` | Path to the `cloudflared` executable. |
| `remoteControl.askCloudflared` | `true` | Offer to install `cloudflared` when it is missing; "Don't ask again" sets it to `false`. |
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

| Command | What it does |
|---|---|
| `pi-bolt service install [--no-tunnel]` | Start the service now and at every login. `--no-tunnel` sets `remoteControl.service.tunnel` to `"off"`. Run it again after changing a setting, or after installing `cloudflared`. |
| `pi-bolt service uninstall` | Stop it and remove every file it wrote (listed below). Paired phones stay paired, and settings stay as they are. |
| `pi-bolt service status` | Show whether it runs, its port, addresses, tunnel and phones, plus a QR code with the current addresses. |
| `pi-bolt service pair [--link]` | Pair a new phone with the running service: show a pairing QR code and wait until a phone uses it. `--link` also prints the link for "Paste pairing link". |
| `pi-bolt service logs [-n <lines>] [-f]` | Print the service's log; `-f` follows it. |

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
  (readable only with Pi-Bolt's private key), is single use, and expires after 10 minutes.
- Paired phones are listed in `~/.pi/agent/remote-control/devices.json` by their public key. Only listed phones get past
  the handshake. `/remote-control devices` revokes a phone; it is disconnected at once.
- A paired phone can do what you can do by typing into Pi: Pi's tools run with your permissions. Revoke phones you lose.
- The background service's control channel (`pi-bolt service status`, `pair`, and the terminal handoff) is a Unix
  socket in `~/.pi/agent/remote-control` (mode 0600, in a 0700 folder), or a named pipe on Windows. Every request
  carries the token from `service.token` (mode 0600), so only your user account gets an answer.

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
     secret (the device is then stored and the secret consumed). Otherwise it closes with code 4403 and sends nothing.
   - Frame 2, host to client: handshake message 2 (`e, ee, se`). Payload JSON:
     `{ "host": "macbook", "version": "1.1.1", "paired": true }` (`paired` is true when this connection paired the device).
   - Frame 3, client to host: the first transport message (step 3), `{ "type": "remote_ready" }`. It proves the
     client holds the session keys: anyone who recorded frame 1 can send it again, but cannot read frame 2. Until it
     arrives the host sends nothing more, runs no command, does not list the client as connected, and stores a newly
     paired device only now. Without it within 3 seconds of frame 2 the host closes with 4408; any other first
     message closes with 4400.
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
| `remote_hello` | `{ protocol: 1, host: { name, version, cwd, service?, parallelSessions? }, state: RpcSessionState, messages: AgentMessage[], streamingMessage?: AgentMessage, dialogs: extension_ui_request[], commands: RpcSlashCommand[], omittedMessages: number }`. It stays under 24 MiB: a longer session sends only its newest messages, and `omittedMessages` counts the older ones left out. Sent on connect and again whenever the TUI switches to another session (`/new`, `/resume`, `/fork`): the client replaces everything it shows. `host.service` is `true` from the background service, which also answers the service commands below; `host.parallelSessions` is `true` from a service that keeps a running session going when the phone opens another (below). |
| `remote_endpoints` | `{ endpoints: string[] }`: every address the host listens on now, in the order of the pairing code's `e` (LAN, Tailscale, then the tunnel). Sent after `remote_hello` on connect, and again whenever the list changes (a quick tunnel's new address, another network). No `id`; clients that do not know it ignore it. |
| session events | Exactly the RPC/JSON event stream ([JSON Event Stream](json.md)): `agent_start`, `message_start`, `message_update` (deltas), `message_end`, `tool_execution_start/update/end`, `queue_update`, `compaction_start/end`, `auto_retry_start/end`, `agent_end`, `agent_settled`, ... Clients ignore types they do not know. |
| `response` | Answer to a client command, as in [RPC Commands](rpc-commands.md). |
| `extension_ui_request` | A dialog (`select`, `confirm`, `input`, `editor`) or `notify`, as in [RPC Extension UI](rpc-extension-ui.md). The same dialog is open in the terminal; whichever side answers first wins. |
| `extension_ui_dismissed` | `{ id }`: the dialog was answered in the terminal, timed out or was cancelled; the client closes it. |

Client to host: `remote_ready` once, first; then RPC commands with an `id` for correlation, and `extension_ui_response`. Accepted commands: `prompt`,
`steer`, `follow_up`, `abort`, `clear_queue`, `get_state`, `get_messages`, `get_available_models`, `set_model`,
`cycle_model`, `get_available_thinking_levels`, `set_thinking_level`, `cycle_thinking_level`, `compact`, `abort_retry`,
`get_session_stats`, `get_commands`, `get_last_assistant_text`, `set_session_name`. Any other type gets
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
