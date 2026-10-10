# Real extensions on Pi-Bolt: JIT off, JIT on, stock Bun (Windows x64, 2026-10-10)

Nine extensions from registry.npmjs.org, each installed with Pi's own `install` into an agent directory of its own (install
scripts off): opensec-pi-subagents 0.20.0, opensec-pi-todo 2.13.0, pi-mcp-adapter 5.2.0, pi-subagents 0.76.1,
pi-powerline-footer 0.19.1, @gotgenes/pi-permission-system 40.1.2, pi-lens 4.4.1, @reedchan/statusline 1.11.3, pi-docparser
4.0.0. Builds: Pi-Bolt 0.7.0 (runtime 16ed51941) with the JIT off (`out\pi-bolt`) and on (`out\pi-bolt-aot-lto-jit`), and Pi
1.0.3 on stock Bun 1.4.2. A fake model on 127.0.0.1 (`bench\plugins_real\fake_model.py`) calls the extensions' tools; no
provider is called. Code: `bench\plugins_real` (`install.ps1`, then `measure.py load|actions|tui`, `summarize.py`); raw data:
`load.jsonl`, `actions.jsonl` (5 rounds), `tui.jsonl` (7 rounds: launch, then 5 prompts streamed a word every 10 ms, in a
ConPTY). Same Windows 11 laptop as `bench/results/2026-10-10-windows`.

Method note: each build is measured as a block, after a warm-up of its own. Code transformed for one runtime version is kept
in caches the builds share (one entry for each file); Pi-Bolt's runtime and stock Bun's are different versions, so taking the
builds in turns made each start transform everything again (a plain start with pi-subagents: 4.3 s instead of 0.6 s), which
no user's single installation does. `load.jsonl` is only whether each extension loads and which tools it registers: its times are first loads, with builds taken in turns, and are not to be quoted.

## Findings

- All nine load and run on all three builds, Windows included.
- pi-docparser 4.0.0's `document_parse` fails on every compiled Pi (stock Bun's too): it starts its worker as
  `spawn(process.execPath, [worker])`, and in a compiled Pi that is pi.exe, not a JavaScript runtime ("Native document worker
  protocol error"). It works where Pi runs on Node or Bun directly. Its timings are left out.
- Launch: Pi-Bolt with the JIT off is the fastest with every extension; what an extension adds to launching is the same on the
  three builds (pi-subagents +526 / +532 / +526 ms): loading is not where interpretation costs.
- Per prompt and per frame: no cost of the JIT being off. Frame time p99 is 17 ms everywhere; the two footers cost the same
  CPU per prompt on every build (statusline +46 ms, powerline +6 ms).
- Memory: the JIT-off build has the least with every extension.
- Actions: the same on the three builds for todo, both subagent extensions, MCP and the permission system (a few ms, or
  dominated by a child process or the MCP server). One exception: pi-lens's `module_report`, CPU-heavy JavaScript, takes
  838 ms with the JIT off against 154 ms with it on and 187 ms on Bun.
- Compiling in: every package's entry is plain JavaScript or TypeScript (`pi.extensions`), which `build-pi -Plugins` can take
  in principle (not built here). A plugin compiled in loads as a factory and cannot be turned off in settings today; making the
  build register them as built-in extensions (`builtin:<name>`) would.
### Launch and use in the TUI (medians; + is over no extension, same build)

| Extension | Build | Launch to interactive | CPU per prompt | Frame time p99 | Peak private bytes |
|---|---|---:|---:|---:|---:|
| none | Pi-Bolt, JIT off | 93 ms | 162 ms | 17.2 ms | 75 MB |
| none | Pi-Bolt, JIT on | 110 ms | 166 ms | 17.0 ms | 93 MB |
| none | Pi on stock Bun | 178 ms | 184 ms | 17.2 ms | 229 MB |
| opensec-pi-subagents | Pi-Bolt, JIT off | 128 ms (+35) | 166 ms (+4) | 16.8 ms | 87 MB (+12) |
| opensec-pi-subagents | Pi-Bolt, JIT on | 138 ms (+28) | 175 ms (+9) | 17.0 ms | 106 MB (+13) |
| opensec-pi-subagents | Pi on stock Bun | 222 ms (+43) | 180 ms (-4) | 17.1 ms | 249 MB (+20) |
| opensec-pi-todo | Pi-Bolt, JIT off | 125 ms (+32) | 165 ms (+3) | 17.0 ms | 82 MB (+8) |
| opensec-pi-todo | Pi-Bolt, JIT on | 129 ms (+19) | 165 ms (-1) | 16.9 ms | 101 MB (+8) |
| opensec-pi-todo | Pi on stock Bun | 214 ms (+36) | 184 ms (-0) | 16.9 ms | 240 MB (+11) |
| pi-mcp-adapter | Pi-Bolt, JIT off | 210 ms (+117) | 178 ms (+16) | 17.0 ms | 93 MB (+18) |
| pi-mcp-adapter | Pi-Bolt, JIT on | 224 ms (+114) | 176 ms (+10) | 17.2 ms | 113 MB (+20) |
| pi-mcp-adapter | Pi on stock Bun | 309 ms (+130) | 192 ms (+7) | 17.1 ms | 251 MB (+22) |
| pi-subagents | Pi-Bolt, JIT off | 619 ms (+526) | 174 ms (+12) | 17.2 ms | 130 MB (+55) |
| pi-subagents | Pi-Bolt, JIT on | 642 ms (+532) | 172 ms (+6) | 17.0 ms | 152 MB (+59) |
| pi-subagents | Pi on stock Bun | 704 ms (+526) | 204 ms (+20) | 17.2 ms | 291 MB (+62) |
| pi-powerline-footer | Pi-Bolt, JIT off | 160 ms (+67) | 168 ms (+6) | 17.1 ms | 87 MB (+13) |
| pi-powerline-footer | Pi-Bolt, JIT on | 178 ms (+68) | 169 ms (+2) | 17.1 ms | 108 MB (+15) |
| pi-powerline-footer | Pi on stock Bun | 260 ms (+82) | 181 ms (-3) | 17.1 ms | 246 MB (+17) |
| pi-permission-system | Pi-Bolt, JIT off | 361 ms (+268) | 167 ms (+5) | 17.1 ms | 151 MB (+76) |
| pi-permission-system | Pi-Bolt, JIT on | 376 ms (+266) | 172 ms (+6) | 17.1 ms | 178 MB (+85) |
| pi-permission-system | Pi on stock Bun | 447 ms (+268) | 189 ms (+4) | 17.2 ms | 400 MB (+172) |
| pi-lens | Pi-Bolt, JIT off | 351 ms (+258) | 154 ms (-9) | 17.2 ms | 141 MB (+66) |
| pi-lens | Pi-Bolt, JIT on | 374 ms (+264) | 155 ms (-11) | 17.5 ms | 166 MB (+73) |
| pi-lens | Pi on stock Bun | 443 ms (+264) | 194 ms (+10) | 17.3 ms | 383 MB (+154) |
| statusline | Pi-Bolt, JIT off | 115 ms (+22) | 209 ms (+46) | 17.2 ms | 90 MB (+15) |
| statusline | Pi-Bolt, JIT on | 126 ms (+16) | 207 ms (+41) | 16.9 ms | 110 MB (+17) |
| statusline | Pi on stock Bun | 211 ms (+32) | 231 ms (+47) | 17.0 ms | 274 MB (+45) |
| pi-docparser | Pi-Bolt, JIT off | 129 ms (+36) | 166 ms (+4) | 16.9 ms | 84 MB (+9) |
| pi-docparser | Pi-Bolt, JIT on | 145 ms (+35) | 165 ms (-1) | 17.0 ms | 102 MB (+9) |
| pi-docparser | Pi on stock Bun | 229 ms (+50) | 184 ms (-0) | 17.2 ms | 243 MB (+14) |

### One typical action (`pi -p`, medians; the action's own cost is the run with it less a plain prompt's)

| Extension | Build | Run with the action | Plain prompt | The action's own time | Its own CPU |
|---|---|---:|---:|---:|---:|
| opensec-pi-todo | Pi-Bolt, JIT off | 124 ms | 114 ms | 9 ms | 6 ms |
| opensec-pi-todo | Pi-Bolt, JIT on | 138 ms | 132 ms | 7 ms | 6 ms |
| opensec-pi-todo | Pi on stock Bun | 220 ms | 203 ms | 17 ms | 39 ms |
| opensec-pi-subagents | Pi-Bolt, JIT off | 255 ms | 180 ms | 75 ms | 24 ms |
| opensec-pi-subagents | Pi-Bolt, JIT on | 268 ms | 190 ms | 78 ms | 27 ms |
| opensec-pi-subagents | Pi on stock Bun | 348 ms | 259 ms | 90 ms | 71 ms |
| pi-subagents | Pi-Bolt, JIT off | 1,692 ms | 658 ms | 1,035 ms | 959 ms |
| pi-subagents | Pi-Bolt, JIT on | 1,656 ms | 670 ms | 986 ms | 923 ms |
| pi-subagents | Pi on stock Bun | 1,679 ms | 733 ms | 946 ms | 974 ms |
| pi-mcp-adapter | Pi-Bolt, JIT off | 594 ms | 209 ms | 385 ms | 291 ms |
| pi-mcp-adapter | Pi-Bolt, JIT on | 624 ms | 225 ms | 399 ms | 383 ms |
| pi-mcp-adapter | Pi on stock Bun | 684 ms | 306 ms | 378 ms | 379 ms |
| pi-permission-system | Pi-Bolt, JIT off | 391 ms | 383 ms | 8 ms | 9 ms |
| pi-permission-system | Pi-Bolt, JIT on | 412 ms | 407 ms | 5 ms | 2 ms |
| pi-permission-system | Pi on stock Bun | 484 ms | 477 ms | 7 ms | -5 ms |
| pi-lens | Pi-Bolt, JIT off | 1,788 ms | 950 ms | 838 ms | 895 ms |
| pi-lens | Pi-Bolt, JIT on | 1,154 ms | 1,000 ms | 154 ms | 294 ms |
| pi-lens | Pi on stock Bun | 1,231 ms | 1,045 ms | 187 ms | 368 ms |
