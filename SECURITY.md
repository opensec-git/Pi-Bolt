# Security

## Supported versions

Only the latest release gets security fixes. Update with `pi-bolt update` (or your package manager) before reporting.

| Version | Supported |
|---|---|
| Latest release | Yes |
| Anything older | No: update first |

## Reporting a vulnerability

Report privately through GitHub: **[open a private security advisory](https://github.com/opensec-git/Pi-Bolt/security/advisories/new)**.
Do not open a public issue, pull request or discussion for a vulnerability.

Include:

- what an attacker can do, and what they need first (a repository you open, a model or MCP server response, a network
  position, a local account);
- steps to reproduce, ideally a minimal proof of concept that only writes a marker file;
- `pi-bolt --version` and your operating system;
- whether stock Pi (on Node or Bun) behaves the same.

## What happens next

- We acknowledge your report within 3 business days and tell you whether we can reproduce it within 7.
- We aim to release a fix within 30 days for high and critical issues, and in the next release for the rest.
- We agree a disclosure date with you, at most 90 days after the report. We credit you in the release notes and the advisory
  unless you ask us not to.
- If the problem is also in upstream Pi, Bun or JavaScriptCore, we report it to that project privately, with your consent, and
  coordinate disclosure with them.

## Scope

In scope: what Pi-Bolt adds or changes:

- the runtime and engine changes (`patches/`) and the release executables;
- the installers (`install.sh`, `install.ps1`), `pi-bolt update`, the npm packages and the release signing;
- `pi-bolt compile`, the built-in tools Pi-Bolt replaces (such as `grep` and `find`), and the other Pi-Bolt code in this
  repository.

Out of scope here:

- Bugs that stock Pi has too: report them to [Pi](https://github.com/earendil-works/pi/security) (you are welcome to tell us as
  well).
- Bugs in upstream Bun or JavaScriptCore that Pi-Bolt does not change: report them to those projects.
- What an extension, skill or model does with the permissions you gave it (see "Extensions and projects" below), and social
  engineering.

We will not pursue legal action against good-faith research that respects this policy: test only on your own machines and
accounts, don't access others' data, and give us a reasonable time to fix before disclosure.

## Verifying downloads

The installers check every download for you. To check a manual download, verify the signature of `SHA256SUMS` first, then the
files against it. With OpenSSH 8.1 or later:

```bash
echo 'pi-bolt namespaces="pi-bolt-release" ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKC26CarSmqCEj6n35NN7x2a/tbEQRt7iESFnijnsPmm' > allowed_signers
ssh-keygen -Y verify -f allowed_signers -I pi-bolt -n pi-bolt-release -s SHA256SUMS.sshsig < SHA256SUMS
sha256sum -c --ignore-missing SHA256SUMS
```

Or with OpenSSL 3, using [`keys/release.pub`](keys/release.pub):

```bash
openssl pkeyutl -verify -pubin -inkey release.pub -rawin -in SHA256SUMS -sigfile SHA256SUMS.sig
```

Every archive also has a GitHub build attestation: `gh attestation verify <file> -R opensec-git/Pi-Bolt`.

## Extensions and projects

Extensions run inside Pi with your permissions, whether loaded at run time or compiled in with `pi-bolt compile`. Install only
extensions you trust. A project's own extensions, skills, prompts and settings load only after you trust that project; answer
"no" for repositories you don't know.
