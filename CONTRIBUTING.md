# Contributing

Thanks for helping make Pi-Bolt better. Bug reports, benchmarks on other hardware and fixes are all welcome.

Pi-Bolt is a fork of [Pi](https://github.com/earendil-works/pi). If something also happens with stock Pi, it belongs upstream.
This repository takes what Pi-Bolt adds: the engine patches, the installers and updater, `pi-bolt compile`, the tools Pi-Bolt
replaces, scripts, benchmarks and docs.

Security problems: never in a public issue. See [SECURITY.md](SECURITY.md). Everyone taking part follows the
[Code of Conduct](CODE_OF_CONDUCT.md).

## Opening an issue

1. Update first (`pi-bolt update`) and search [existing issues](https://github.com/opensec-git/Pi-Bolt/issues).
2. Check whether stock Pi does the same (`npx @earendil-works/pi-coding-agent`). If it does, report it to Pi instead.
3. Open a [bug report](https://github.com/opensec-git/Pi-Bolt/issues/new?template=bug.yml) or a
   [feature request](https://github.com/opensec-git/Pi-Bolt/issues/new?template=feature.yml) and fill in the form:
   - what you did, what you expected, what happened, with error messages as text;
   - `pi-bolt --version`, your OS and how you installed;
   - the smallest steps that reproduce it.

Questions and ideas that aren't bugs go to [Discussions](https://github.com/opensec-git/Pi-Bolt/discussions).
[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) covers common problems and what to collect.

## Opening a pull request

1. For anything bigger than a small fix, open an issue first so we can agree on the approach.
2. Fork, then branch from `pi-bolt`.
3. Set up and check:

   ```bash
   npm ci --ignore-scripts
   npm run check      # lint, types, formatting: must be clean
   ./test.sh          # the tests (no network or API keys needed)
   ```

4. Add a test that fails without your change. For an engine or build change, also run `tests/aot/run.sh` and
   `tests/pi/run.sh` on a build with it ([docs/BUILDING.md](docs/BUILDING.md)).
5. If it can affect speed or memory, include before/after numbers from `bench/` (same machine, runs interleaved).
6. Commit with a short message: `fix(coding-agent): what changed`. Types: `feat`, `fix`, `perf`, `docs`, `build`, `test`,
   `chore`. Don't edit `CHANGELOG.md` files; release notes are written at release time.
7. Open the pull request against `pi-bolt`, link the issue (`Fixes #123`), and fill in the template.

A maintainer reviews it. CI must pass and every review comment must be resolved before it can be merged. We merge; you don't
need to rebase unless asked.

## Where things live

| Area | Where | Check with |
|---|---|---|
| Pi's code as Pi-Bolt changes it | `packages/` | `npm run check`, `./test.sh` |
| Build scripts, tools, docs | `scripts/`, `bench/`, `docs/` | `shellcheck -x scripts/*.sh`, and a build |
| The engine (JavaScriptCore AOT compiler, runtime) | `.work/webkit`, then regenerate `patches/webkit.patch` | `tests/aot/run.sh`, `bench/e2e_tools.py`, `bench/ui_check.py` |
| The executable format | `.work/bun`, then regenerate `patches/bun.patch` | the same |
| Installers and updater | `install.sh`, `install.ps1`, `npm/` | `tests/installer/run.sh` (and `run.ps1` on Windows) |

## Style

- TypeScript: as the surrounding code; no `any` unless unavoidable; imports at the top.
- Shell: `bash`, `set -euo pipefail` (via `scripts/lib/common.sh`), clean under `shellcheck -S warning`.
- Python: standard library only, Python 3.9+.
- Engine code: WebKit's style.
