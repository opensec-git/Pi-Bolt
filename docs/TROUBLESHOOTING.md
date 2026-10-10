# Troubleshooting

- [Is the compiled code being used?](#is-the-compiled-code-being-used)
- [Notices at startup](#notices-at-startup)
- [Environment variables](#environment-variables)
- [Common problems](#common-problems)
- [macOS](#macos)
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
| `PI_TUI_SCROLL_ROWS=0` | Fullscreen mode draws every row that changed instead of scrolling the rows that only moved (as up to 0.5.1). For a terminal that does not scroll a region of the screen correctly; tmux, zmx, xterm, kitty, Ghostty and VTE terminals do. |
| `BUN_JSC_numberOfGCMarkers=N` | Threads that mark during a garbage collection (default 2, from 0.5.2; the engine's own default is up to 8). More shorten collections of a very large heap and cost wake-ups on every small one. |
| `MIMALLOC_PURGE_HOLES_MIN_INTERVAL=N` | Milliseconds between two sweeps that give freed memory back to the system (default 250, from 0.5.2; the allocator's own default is 100). Lower gives memory back sooner for more CPU while Pi is busy. |
| `JITI_FS_CACHE=false` | Do not keep the transformed source of run-time extensions (it is kept in `cache/jiti` of the agent directory). |

Pi's own variables (`PI_CODING_AGENT_DIR`, `PI_OFFLINE`, ...) work as documented by Pi.

A `.env` file in the working directory is not read (from 0.6.0). Pi's own Bun binary loads one into its environment, as Bun does for
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
build runs on it; the installer says so since 0.5.1.

**A theme, the HTML export or image tools are missing.** The executable was moved away from the files around it. Keep the whole
folder together, and link or alias the executable instead of copying it.

**A plugin works with stock Pi but not when compiled in.** See the [compatibility checklist](PLUGINS.md#compatibility-checklist):
usually a dynamic import, or a file read from next to the plugin's source.

**The build stops with "cannot be compiled ahead of time".** The compiler declined a function, and in a compiled executable a
function that is not compiled cannot be relied on to run. The message names the function and says why; the usual way out is to
change that function. `BUN_JSC_allowAOTDeclinedFunctions=1` builds anyway: the function then runs from bytecode if it uses no
variables from outside itself, and stops the program when it is called if it does.

**The first prompt after a pause hangs until it times out.** Up to 0.5.0 an idle connection to the model provider was kept for
5 minutes, and one that a NAT, a load balancer or a suspended laptop had dropped in the meantime was used again. Update; from
0.5.1 a connection waits 4 seconds (or as long as the server says it may), as in Node.

**A run-time plugin is slow.** On the default build, plugins loaded at run time are interpreted. Compile them in
([PLUGINS.md](PLUGINS.md)) or use the `linux-x64-jit` build.

**In a container (Docker).** Pi-Bolt needs nothing from the image but glibc 2.17 or later, and runs in tmux or zmx there as
anywhere. With a memory limit, count the executable too: its code is file-backed memory that the kernel charges to the
container and has to read again when the limit pushes it out. Pi-Bolt works from a limit of about 100 MB, at twice the CPU and
with pauses of a second; 256 MB or more leaves it alone. A CPU limit (`--cpus`) lowers the number of threads Pi-Bolt starts.

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

## Reporting a problem

Please [open an issue](https://github.com/opensec-git/Pi-Bolt/issues/new) with:

- the output of `BUN_STATIC_HEAP_VERBOSE=1 pi-bolt --version`;
- your distribution, `ldd --version | head -1`, and the CPU model (`grep -m1 "model name" /proc/cpuinfo`); on macOS, `sw_vers` and
  `sysctl -n machdep.cpu.brand_string`;
- whether the problem also happens with `BUN_AOT=0 pi-bolt`, and with stock Pi.

If it only happens with the compiled code, it is a Pi-Bolt bug. If it also happens with stock Pi, report it to
[Pi](https://github.com/earendil-works/pi/issues).

### A crash

When Pi-Bolt crashes it prints "Pi-Bolt has crashed", some lines about the system, and a link that starts with
`https://pi-bolt.opensec.in/crash/`. The link holds an encoded stack trace and nothing else: no file names, paths or data of
yours. Please include it in the issue, with what you were doing. Pi-Bolt's maintainers decode it against the symbols of that
release. (In Pi-Bolt 0.3.0 and earlier the message named Bun and the link went to bun.report, which cannot decode Pi-Bolt
traces: report those here too.)
