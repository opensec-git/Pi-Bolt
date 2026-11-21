# Editors (Agent Client Protocol)

`pi-bolt --acp` runs Pi-Bolt as an [Agent Client Protocol](https://agentclientprotocol.com) (ACP) agent: editors that speak ACP
start it and show it in their agent panel. That covers Zed, JetBrains IDEs, VS Code (through an ACP extension), Neovim, Emacs and
[the other ACP clients](https://agentclientprotocol.com/get-started/clients). It is built in, so there is nothing to install. The
ACP code loads only when `--acp` is given; the terminal UI, `-p`, and RPC mode do not load it.

Editors launched from the desktop may not see your shell's `PATH`: give them the full path of the executable (`which pi-bolt`).

## Setting it up

**Zed**: in `settings.json`:

```json
"agent_servers": {
  "Pi-Bolt": { "type": "custom", "command": "/full/path/to/pi-bolt", "args": ["--acp"], "env": {} }
}
```

**JetBrains IDEs** (IntelliJ IDEA, PyCharm, WebStorm, ...): in the AI Chat tool window, choose *Add Custom Agent*, which opens
`~/.jetbrains/acp.json`, and add:

```json
{
  "agent_servers": {
    "Pi-Bolt": { "command": "/full/path/to/pi-bolt", "args": ["--acp"], "env": {} }
  }
}
```

With `"use_idea_mcp": true` in its `default_mcp_settings`, the IDE also passes its own MCP server, and Pi-Bolt gets the IDE's
tools.

**VS Code, Neovim, Emacs and others**: add a custom agent in the ACP client of your choice (for example the
[ACP Client](https://github.com/formulahendry/vscode-acp) extension for VS Code, or CodeCompanion for Neovim) with the command
`pi-bolt` and the argument `--acp`.

Other flags work as on the command line, for example `["--acp", "--model", "anthropic/claude-sonnet-4-5"]` or
`["--acp", "--no-mcp"]`.

## What the editor gets

- **Your Pi setup.** Each editor session is a Pi session made as the terminal makes one: settings, models and logins
  (`/login` in `pi-bolt`), extensions, skills, prompt templates, context files and `mcp.json` all apply. There is no separate
  sign-in in the editor; when no model is set up, a prompt says how to set one up.
- **Streaming replies and thinking**, as the model writes them.
- **Tool calls** with a title, a kind (read, edit, run, search) and the files they touch, so the editor can follow along. Edits and
  writes show as diffs, and commands show their output while they run (in a terminal view, with colours, in editors that
  offer one, such as Zed).
- **Mode, model and thinking level** as session settings the editor shows next to the prompt. The *Read-only* mode gives the
  model only the tools that read (`read`, `grep`, `find`, `ls`); *Default* gives back the usual ones. Changing a setting affects
  that session only.
- **Slash commands**: extension commands, prompt templates, skills (`/skill:name`), and the built-in commands that need no
  terminal: `/compact`, `/name`, `/model`, `/thinking`, `/session`, `/export` (the editor gets a link to the file) and `/reload`.
- **Context usage and cost** after each prompt.
- **Compactions** as an entry of their own with the summary, in editors that show compactions; a loaded session replays the
  summary with its history.
- **Sessions**: Pi's own session files. The editor lists them (`session/list`), opens one with its history (`session/load`),
  continues one without it (`session/resume`), forks one into a new session (`session/fork`), or deletes one
  (`session/delete`: to the trash when the `trash` command is installed, as `/resume` in the terminal deletes). Sessions
  started in the editor open in the terminal with `pi-bolt --session <id>`, and the other way round.
- **The editor's MCP servers**, passed when a session starts, connect next to those of `mcp.json` (stdio and HTTP; legacy SSE is not
  supported). `/mcp` lists them. A server of the same name in `mcp.json` takes precedence.
- **Images and files** in prompts: images are attached, `@` mentions read as the file's path, and embedded file contents are added to
  the prompt as `<file>` blocks.
- **Cancel** stops the model and any running tool.

Pi runs its own tools on the files and runs commands in its own shell. Extensions that ask the user something reach the editor:
`confirm` and `select` as a permission request, `input` and `editor` as a form when the editor supports forms (otherwise they get
no answer), and `notify` as a notice when the editor shows notices.

## Project trust

A project with its own extensions, settings or packages in `.pi/` is trusted as in the other headless modes: by a decision saved
earlier, or by the `defaultProjectTrust` setting. ACP sessions cannot ask, so to trust a project, open it once with `pi-bolt` in a
terminal and trust it there. (`--approve` in the agent's arguments trusts every project the editor opens.)
