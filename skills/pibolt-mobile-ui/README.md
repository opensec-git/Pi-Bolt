# pibolt-mobile-ui

A skill for authors of Pi packages and extensions. It teaches Pi the mobile UI kit: ask Pi to "add a mobile UI to this
package" and it reads the skill, writes the views (panels, pages, sheets, Home tiles) with a text form for the terminal,
and checks them with `pi-bolt mobile-ui check`. `examples/` holds views to start from. The kit itself is described in
[docs/mobile-ui.md](../../packages/coding-agent/docs/mobile-ui.md).

Pi-Bolt does not install this skill. To use it, add it to your own Pi.

## Add it to your Pi

1. Get the folder from this repository:

   ```bash
   git clone --depth 1 --branch pi-bolt --filter=blob:none --sparse https://github.com/opensec-git/Pi-Bolt.git pi-bolt-src
   git -C pi-bolt-src sparse-checkout set skills/pibolt-mobile-ui
   ```

2. Copy it where Pi finds skills. For every project (your user skills folder):

   ```bash
   mkdir -p ~/.pi/agent/skills
   cp -R pi-bolt-src/skills/pibolt-mobile-ui ~/.pi/agent/skills/
   ```

   For one project only, copy it into that project's `.pi/skills/` instead: Pi loads a project's skills once you trust
   the project. If you set `PI_CODING_AGENT_DIR`, the user skills folder is `$PI_CODING_AGENT_DIR/skills`.

3. Start a new session, or run `/reload` in an open one. The skill is listed as `/skill:pibolt-mobile-ui`, and Pi loads
   it when you ask for a mobile UI.

To try it for one run without copying it, pass the folder: `pi-bolt --skill pi-bolt-src/skills/pibolt-mobile-ui`.

To remove it, delete the folder you copied.
