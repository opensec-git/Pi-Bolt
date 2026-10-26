<!-- Title: type(scope): what changed, e.g. fix(coding-agent): grep reads large files in pieces. Security fixes: report privately first (SECURITY.md). -->

Fixes #

**What and why**


**How it was tested**


**Checklist**

- [ ] `npm run check` is clean and `./test.sh` passes
- [ ] A test fails without this change (or: why not)
- [ ] Speed or memory may change: before/after numbers from `bench/` are above
- [ ] Engine or build change: patches regenerated, `tests/aot/run.sh` and `tests/pi/run.sh` pass on a build with it
- [ ] No `CHANGELOG.md` edits
