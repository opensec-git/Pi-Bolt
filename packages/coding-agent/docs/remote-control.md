# Remote Control

`/remote-control` lets a phone running the Pi-Bolt Android app attach to the session in this terminal. The phone shows
the live session (messages, thinking, tool calls and their output as they stream) and can send prompts, steer, queue
follow-ups, abort, switch model or thinking level, and answer the dialogs Pi raises.

Nothing starts until the command runs: no listener, no tunnel, no key files. Without `/remote-control`, Pi behaves exactly as before.

## Use

| Command | What it does |
|---|---|
| `/remote-control` | Start the server on the local network and show a pairing QR code. While running: show a new QR code. |
| `/remote-control tunnel` | Also start a Cloudflare quick tunnel, so the phone can connect from anywhere. Needs `cloudflared`. |
| `/remote-control copy` | Copy the pairing link, for the app's "Paste pairing link" (a phone without a camera, an emulator). It pairs one phone within 10 minutes; paste it only into the app. |
| `/remote-control stop` | Stop the server and the tunnel, and disconnect every phone. |
| `/remote-control devices` | List paired phones and revoke one. |

Scan the QR code with the app (or the phone's camera). The first scan pairs the phone; later scans of any QR code from the
same computer reconnect without pairing again. The footer shows `remote` with the number of connected phones.

### Settings

| Setting | Default | Meaning |
|---|---|---|
| `remoteControl.port` | `0` (random) | Port for the local listener. A fixed port lets paired phones reconnect on the LAN without a new QR code. |
| `remoteControl.tunnel` | `"quick"` | `"quick"` for a random `*.trycloudflare.com` address, or `{ "name": "<tunnel>", "hostname": "<host>" }` for a named tunnel you configured with `cloudflared tunnel create` and a DNS route. |
| `remoteControl.cloudflared` | `"cloudflared"` | Path to the `cloudflared` executable. |

### Install cloudflared

macOS `brew install cloudflared`; Debian/Ubuntu: Cloudflare's apt repository; Windows `winget install --id Cloudflare.cloudflared`.
Pi-Bolt never downloads it.

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
3. Transport: each later binary frame is one Noise transport message (AES-256-GCM, nonce = per-direction counter
   starting at 0, as in the Noise spec). The plaintext is `flag || data`: flag `0x00` marks the last chunk of a
   message, `0x01` a chunk with more to follow. Chunks carry at most 65000 data bytes. The concatenated data of a
   message is one UTF-8 JSON object.
4. Close codes: 4400 malformed handshake or decryption failure, 4403 not authorized, 4408 no handshake message within 3 seconds of connecting (counts as a failed handshake), 4429 too many failed handshakes from this address; an address may have at most 3 connections in their handshake at once, 1001 host stopping.
5. Keepalive: the host sends WebSocket pings every 20 seconds and drops a client that misses two; clients may ping too.

### Messages

After the handshake the host sends `remote_hello`, then the session's event stream.

Host to client:

| `type` | Meaning |
|---|---|
| `remote_hello` | `{ protocol: 1, host: { name, version, cwd }, state: RpcSessionState, messages: AgentMessage[], streamingMessage?: AgentMessage, dialogs: extension_ui_request[], commands: RpcSlashCommand[] }`. Sent on connect and again whenever the TUI switches to another session (`/new`, `/resume`, `/fork`): the client replaces everything it shows. |
| session events | Exactly the RPC/JSON event stream ([JSON Event Stream](json.md)): `agent_start`, `message_start`, `message_update` (deltas), `message_end`, `tool_execution_start/update/end`, `queue_update`, `compaction_start/end`, `auto_retry_start/end`, `agent_end`, `agent_settled`, ... Clients ignore types they do not know. |
| `response` | Answer to a client command, as in [RPC Commands](rpc-commands.md). |
| `extension_ui_request` | A dialog (`select`, `confirm`, `input`, `editor`) or `notify`, as in [RPC Extension UI](rpc-extension-ui.md). The same dialog is open in the terminal; whichever side answers first wins. |
| `extension_ui_dismissed` | `{ id }`: the dialog was answered in the terminal, timed out or was cancelled; the client closes it. |

Client to host: RPC commands with an `id` for correlation, and `extension_ui_response`. Accepted commands: `prompt`,
`steer`, `follow_up`, `abort`, `clear_queue`, `get_state`, `get_messages`, `get_available_models`, `set_model`,
`cycle_model`, `get_available_thinking_levels`, `set_thinking_level`, `cycle_thinking_level`, `compact`, `abort_retry`,
`get_session_stats`, `get_commands`, `get_last_assistant_text`, `set_session_name`. Any other type gets
`{ type: "response", success: false, error: "Not available over remote control" }`.

For an extension command (`/name ...`), the `prompt` response arrives only when the command finishes; its dialogs
arrive meanwhile as `extension_ui_request`, so clients must not wait for the response before showing them.

`prompt` while the agent is streaming needs `streamingBehavior` (`"steer"` or `"followUp"`), as in RPC mode. Prompts run
extension commands, prompt templates and skills; the terminal's own built-in commands (such as `/model`) do not run from
a phone.
