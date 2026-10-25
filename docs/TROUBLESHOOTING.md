# Troubleshooting

- [Is the compiled code being used?](#is-the-compiled-code-being-used)
- [Notices at startup](#notices-at-startup)
- [Environment variables](#environment-variables)
- [Common problems](#common-problems)
- [macOS](#macos)
- [Windows](#windows)
- [Reporting a problem](#reporting-a-problem)

## Is the compiled code being used?

```bash
BUN_STATIC_HEAP_VERBOSE=1 pi-bolt --version
```

```
[aot] image of 42467328 bytes mapped, 35635200 of them code
[static heap] 94109736 bytes in executable, mapped: true
[aot] image registered: true
1.0.0
```

`image registered: true` means Pi runs its ahead-of-time compiled code. Without the variable, a healthy executable prints nothing
extra.

## Notices at startup

When the compiled code cannot be used, the executable prints one line on stderr, then runs Pi from its bytecode. That is correct,
but slower to start, and uses more CPU:

```
pi-bolt: the executable's ahead-of-time compiled code is not used (<reason>); running from bytecode
```

| Reason | Cause | What to do |
|---|---|---|
| `the address space for it is not available` | An address-space limit (`ulimit -v`, `RLIMIT_AS`, or `vm.overcommit_memory=2`) leaves no room for the fixed regions the code expects. | The compiled code needs about 10 GB of *virtual* address space. It is reserved, not used, so it costs no memory. Raise or remove the limit. Below it, Pi still works from bytecode, down to about 1.5 GB. |
| `this CPU lacks an instruction set the code was compiled for` | A `linux-x64` build on a CPU without AVX2. | Use the `linux-x64-baseline` build (`PIBOLT_VARIANT=x64-baseline` with the installer). |
| `the image is not one for this engine`, `the image could not be mapped executable`, `the prebuilt heap could not be mapped` | A damaged executable, or a system that forbids executable file mappings. | Re-download the release and check `SHA256SUMS`. Report it if it persists. On macOS, an executable re-signed with the hardened runtime needs `com.apple.security.cs.disable-library-validation` (see [macOS](#macos)). |
| `the executable could not be loaded where its prebuilt heap expects it` | macOS: the executable could not be started where its prebuilt heap expects it (see [ARCHITECTURE.md](ARCHITECTURE.md#the-macos-arm64-port)). | Report it, with `sw_vers` and how Pi-Bolt was started. |

One failure is fatal instead: `the executable's ahead-of-time code image was rejected by the engine; run with BUN_AOT=0 to use
the bytecode instead`. It should not happen with release builds. Run with `BUN_AOT=0` and report it.

## Environment variables

These are read by the executable at run time.

| Variable | Effect |
|---|---|
| `BUN_AOT=0` | Ignore the compiled code and static heap; run from bytecode. Use it to tell whether a problem is specific to the AOT code. |
| `BUN_STATIC_HEAP_VERBOSE=1` | Report how the static heap and code image were mapped. |
| `PI_TIMING=1` | Pi's own startup timings, including each extension's factory. |
| `BUN_CONFIG_HTTP_KEEPALIVE_TIMEOUT=N` | How many seconds an idle connection to a server waits to be used again when the server does not say (default 4, as in Node). Longer saves a handshake after a pause; a connection that went dead while it waited makes the next request wait for its timeout. |
| `PI_TUI_SCROLL_ROWS=0` | Fullscreen mode draws every row that changed instead of scrolling the rows that only moved. For a terminal that does not scroll a region of the screen correctly; tmux, zmx, xterm, kitty, Ghostty and VTE terminals do. |
| `BUN_JSC_numberOfGCMarkers=N` | Threads that mark during a garbage collection (default 2; the engine's own default is up to 8). More shorten collections of a very large heap and cost wake-ups on every small one. |
| `MIMALLOC_PURGE_HOLES_MIN_INTERVAL=N` | Milliseconds between two sweeps that give freed memory back to the system (default 250; the allocator's own default is 100). Lower gives memory back sooner for more CPU while Pi is busy. |
| `JITI_FS_CACHE=false` | Do not keep the transformed source of run-time extensions (it is kept in `cache/jiti` of the agent directory). |

Pi's own variables (`PI_CODING_AGENT_DIR`, `PI_OFFLINE`, ...) work as documented by Pi.

A `.env` file in the working directory is not read. Pi's own Bun binary loads one into its environment, as Bun does for
any program; Pi on Node does not, and a project's `.env` (its API keys, its proxy settings) is the project's, not Pi's. Set what
Pi should see in the shell that starts it.

Build-time variables (`BUN_STATIC_HEAP`, `BUN_AOT`, `BUN_AOT_JIT`, `BUN_AOT_CPU`, `BUN_JSC_*`) are set by `scripts/build-pi.sh`.
See [ARCHITECTURE.md](ARCHITECTURE.md#build-pipeline).

To rule a compiler optimization in or out of a problem, build with it off (each is on by default) and compare:

| Build with | Turns off |
|---|---|
| `BUN_JSC_useAOTVariableNarrowing=0` | Module and closure variables taken for what they are written with, in loops |
| `BUN_JSC_maximumAOTMethodCandidates=1` | Inlining of methods whose name several functions share |
| `BUN_JSC_useAOTMethodInliningByName=0` | All inlining of methods by name |
| `BUN_JSC_useAOTIntegerRemainderOfNumbers=0` | The integer fast path of `%` |
| `BUN_JSC_useImmutableIntrinsics=0` | Inlining of array callbacks, and the freezing of the standard objects it needs |

## Common problems

**`pi-bolt: command not found` after installing.** `~/.local/bin` is not on your `PATH`. Add
`export PATH="$HOME/.local/bin:$PATH"` to your shell profile.

**`GLIBC_2.xx not found` or `No such file or directory` when starting.** The system is musl-based (Alpine), or older than
glibc 2.17. Pi-Bolt needs glibc 2.17 or later (CentOS 7, Debian 8, Ubuntu 14.04 and anything newer).

**`Illegal instruction` when starting.** The CPU has no SSE4.2 (older than Intel Nehalem, 2008, or AMD Bulldozer, 2011). No
build runs on it; the installer says so.

**A theme, the HTML export or image tools are missing.** The executable was moved away from the files around it. Keep the whole
folder together, and link or alias the executable instead of copying it.

**`bun:ffi requires the JIT`.** The extension calls into a native library through `bun:ffi`, which works only on the JIT build.
The message names the extension and gives the command that installs the JIT build
([README](../README.md#jit-build-for-heavy-plugins)); `pi-bolt doctor` lists every extension that uses `bun:ffi`.

**A plugin works with stock Pi but not when compiled in.** See the [compatibility checklist](PLUGINS.md#compatibility-checklist):
usually a dynamic import, or a file read from next to the plugin's source.

**The build stops with "cannot be compiled ahead of time".** The compiler declined a function, and in a compiled executable a
function that is not compiled cannot be relied on to run. The message names the function and says why; the usual way out is to
change that function. `BUN_JSC_allowAOTDeclinedFunctions=1` builds anyway: the function then runs from bytecode if it uses no
variables from outside itself, and stops the program when it is called if it does.

**`pi-bolt compile` stops with "is bigger than maximumAOTCandidateBytecodeSize".** One function of the program holds more than
256 KB of bytecode. Each extension, with the modules only it imports, is a file of its own, compiled apart from Pi's code and
from the other extensions, so the limit is one extension's. Below the compiler's line, `pi-bolt compile` says which it is:
- `(a function)`: one very large function of an extension, or a CommonJS module (a package's prebuilt, bundled `dist/`). The
  message names the file and the extension.
- `(the top-level code of a module)`: the top-level code of one extension, usually one large literal (embedded data). The
  message names the extension.

Leave that extension out with `pi-bolt compile --exclude <name>`, by the name `pi-bolt compile` prints for it: it still loads
from its files at run time, and later compiles and `pi-bolt update` leave it out too (`--no-exclude` takes it back). Or compile
it anyway with `BUN_JSC_maximumAOTCandidateBytecodeSize=<n> pi-bolt compile`, `<n>` being at least the size the message gives.
compile.json keeps that limit: a later `pi-bolt compile` without the variable, and `pi-bolt update`, build with it too, and
`BUN_JSC_maximumAOTCandidateBytecodeSize=262144 pi-bolt compile` takes it back. The compiler warns that a big function can take
minutes and tens of GB; one of a few hundred KB has taken seconds. The message's third remedy,
`BUN_JSC_omitBytecodeFromStaticHeap=0`, does not apply to `pi-bolt compile`: the build keeps the release's setting.

Pi-Bolt 1.0.1 and earlier added the top-level code of every extension to the program's, about 200 KB of which is Pi's: there a
few extensions together passed the limit, and leaving out any one of them did not help (#129). Pi-Bolt 1.0.2 compiled each
module on its own, but stopped at an extension with an `await` at its top level (#142). Pi-Bolt 1.0.3 compiles each
extension on its own, `await` or not.

**A run-time plugin is slow.** On the default build, plugins loaded at run time are interpreted. Compile them in
([PLUGINS.md](PLUGINS.md)) or use the `linux-x64-jit` build.

**In a container (Docker).** Pi-Bolt needs nothing from the image but glibc 2.17 or later, and runs in tmux or zmx there as
anywhere. With a memory limit, count the executable too: its code is file-backed memory that the kernel charges to the
container and has to read again when the limit pushes it out. Pi-Bolt works from a limit of about 100 MB, at twice the CPU and
with pauses of a second; 256 MB or more leaves it alone. A CPU limit (`--cpus`) lowers the number of threads Pi-Bolt starts.

**A Herdr restart does not reopen the Pi-Bolt session.** Herdr runs the restore command with its server's `PATH`, which must
include `pi-bolt`: start Herdr from a terminal, not with `brew services`. Agents that pi-herdr starts in other panes run stock
Pi (`pi`), which must be installed.

**Startup is slower than expected.** Check that the compiled code is used (above). Check `PI_TIMING=1` for slow extension
factories. A cold page cache (the first start after boot or install) adds the time to read the executable from disk.

## macOS

**"pi" is killed at once (`zsh: killed`), or macOS says it cannot verify the developer.** The executable carries a quarantine
attribute: it was downloaded with a browser (or unpacked by something that keeps the attribute). Pi-Bolt's executables are signed
ad hoc, which Gatekeeper does not accept for quarantined files. Install with the installer or npm, which do not quarantine, or
remove the attribute from the unpacked folder: `xattr -dr com.apple.quarantine pi-bolt-darwin-arm64`.

**"Pi-Bolt runs on Macs with Apple silicon only".** Pi-Bolt has no build for Intel Macs. From a terminal that runs under Rosetta on
an Apple silicon Mac, the installer installs the ARM64 build, which runs natively.

**Re-signing the executable.** `codesign` may re-sign it, keeping the whole file covered: Pi-Bolt maps its compiled code from the
file. With the hardened runtime (`-o runtime`), add the entitlement `com.apple.security.cs.disable-library-validation`: without
it the executable runs from bytecode, with one notice. The `-jit` build also needs `com.apple.security.cs.allow-jit` for its JIT.

**`pi`, `pi-bin` and `pi-spawn`.** In a macOS build `pi` is a small launcher, and `pi-bin` beside it the Pi-Bolt executable,
which runs from either; `pi-spawn` starts Pi's programs (above). Link `pi` (as the installer does) rather than copy it alone: it
starts the `pi-bin` beside its real path. `BUN_STATIC_HEAP_VERBOSE=1 pi --version` reports on `pi-bin`. Re-sign `pi-bin` (above); the launcher needs nothing.

## Windows

**`pi-bolt` is not found after installing.** The installer adds `%USERPROFILE%\.pi-bolt\pi-bolt-win32-x64` to your user PATH,
which terminals opened afterwards have: open a new one. With `npm install -g pi-bolt`, npm's global folder (`npm prefix -g`) must
be on PATH, and npm must run the package's install script (`npm install -g pi-bolt --allow-scripts=pi-bolt` on npm 11.19 and
later).

**The bash tool says "No bash shell found", or answers "Windows Subsystem for Linux has no installed distributions".** Pi's
bash tool runs Git Bash. Install [Git for Windows](https://git-scm.com/download/win); Pi-Bolt finds it in Program Files, in
`%LOCALAPPDATA%\Programs\Git` (installed for one user), scoop's, or through the `git.exe` on PATH (a portable Git). WSL's
`bash.exe` is used only when there is no other bash. Or set `shellPath` in `~\.pi\agent\settings.json` to a `bash.exe` of your
choice. The PowerShell tool needs nothing.

**Pasting several lines sends each line as its own prompt.** The console window of Windows 10 (conhost) does not mark a paste as
one, so Pi sees the lines as typed, each ending with Enter. Use Windows Terminal (the default on Windows 11; on Windows 10 from
the Microsoft Store), or VS Code's terminal, which do.

**Characters like `é` from a Windows program come out as `?` through the bash tool.** Programs such as `cmd.exe` write in the
console's code page, not UTF-8, and the bash tool reads UTF-8. The PowerShell tool reads them correctly.

**A program in your project folder is not run by its name.** A command named without a path (`git`, `npm`) is looked up on
PATH only, never in the working directory, so a repository cannot put its own `git.cmd` in front of yours. Run a program of the
project by its path: `.\tools\build.cmd`. The same holds in the tools' shells, so an npm script that runs `gradlew` or `build`
of the project's folder by name fails inside Pi; have the script name it by its path (`.\gradlew`).

**A program keeps running after Escape or the bash tool's timeout.** Stopping a command ends the processes it started. On the
default build, though, a native program whose own parent has already exited (a dev server a launcher started and left) is not
found, and keeps running: end it in Task Manager, or use the `-jit` build (`$env:PIBOLT_VARIANT = "x64-jit"` before installing),
which ends those too.

**The first start after installing or updating an extension takes seconds.** Microsoft Defender checks each file of a newly
installed package the first time it is read (about 15-20 ms a file; a large extension has hundreds), and the extension's
TypeScript is compiled once into `~\.pi\agent\cache\jiti`. Later starts use that cache and read files Defender has already
checked: pi-subagents, for one, loads in about 0.5 s instead of 8. This is Defender doing its job; do not exclude Pi's folders
from it.

**The bash tool says `shellPath` "is not a bash".** `shellPath` names the bash the bash tool runs (Git Bash's `bash.exe`, say).
`cmd.exe` and PowerShell are not one; PowerShell commands go through the PowerShell tool.

**SmartScreen warns about `pi-bolt.exe` downloaded with a browser.** The executable has no Authenticode signature yet. The
installers check the release's own signature and do not mark what they download; install with them rather than from the browser.

**A crash on Windows.** The text Pi-Bolt prints includes an `Args:` line with the whole command line, `-p` prompt included:
remove anything private from it before you post it.

## Reporting a problem

Please [open an issue](https://github.com/opensec-git/Pi-Bolt/issues/new) with:

- the output of `pi-bolt doctor` (the build, installation, extensions and packages, and the problems it finds; no keys or
  tokens, and your home folder shown as `~`);
- on Linux, `ldd --version | head -1`; on Windows, the terminal (Windows Terminal, the console window, VS Code);
- whether the problem also happens with `BUN_AOT=0 pi-bolt`, and with stock Pi.

If it only happens with the compiled code, it is a Pi-Bolt bug. If it also happens with stock Pi, report it to
[Pi](https://github.com/earendil-works/pi/issues).

### A crash

When Pi-Bolt crashes it prints "Pi-Bolt has crashed", some lines about the system, and a link that starts with
`https://pi-bolt.opensec.in/crash/`. The link holds an encoded stack trace and nothing else: no file names, paths or data of
yours. Please include it in the issue, with what you were doing. Pi-Bolt's maintainers decode it against the symbols of that
release. (In Pi-Bolt 0.3.0 and earlier the message named Bun and the link went to bun.report, which cannot decode Pi-Bolt
traces: report those here too.)
