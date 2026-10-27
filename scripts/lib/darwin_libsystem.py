#!/usr/bin/env python3
"""libsystem/ beside pi-bin: a libSystem through which dyld binds pi-bin's ~480 libSystem imports in a fifth of the time.

dyld looks an import from libSystem up in libSystem's re-exported libraries one after another (39 of them) until one exports
it: pi-bin's imports take about 24 tries each (libsystem_kernel's 175 are 32nd), some 25 million instructions per start. The
runtime loads @rpath/libSystem.B.dylib, with the rpaths @executable_path/libsystem and /usr/lib (scripts/build-runtime.sh).
This writes DIR/libsystem/libSystem.B.dylib: no code, re-exporting libsystem_c, _m, _kernel, _platform and _pthread (in
libSystem's own order), then /usr/lib/libSystem.B.dylib. 420 of the imports are then found in two to six lookups, the others
through libSystem as before. Nothing in it is new to the system: a symbol that moves to another of libSystem's libraries is
still found, through libSystem; a library that is gone is replaced by an empty one from libsystem/fallback (the second
rpath); without libsystem/ dyld loads /usr/lib/libSystem.B.dylib as it would have. libSystem's libraries export no name
twice, so the first library that has a name is the one libSystem would have taken it from.

Checked before it is kept: a program that imports the same symbols from libSystem binds each to the same address with
libsystem/ as without it, also with the fallback libraries in place of the system's. Otherwise libsystem/ is removed (the
start is then as before) and the build goes on.
Usage: darwin_libsystem.py DIR   (DIR holds pi-bin)"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

LIBRARIES = ["libsystem_c", "libsystem_m", "libsystem_kernel", "libsystem_platform", "libsystem_pthread"]
RPATH = "@executable_path/libsystem"
CLANG = ["xcrun", "clang", "-arch", "arm64", "-mmacosx-version-min=13.0"]


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs).stdout


def dependents(path):
    """pi-bin's linked libraries and rpaths, from its load commands (otool -l). Not from dyld_info -dependents: Xcode 16's
    (GitHub's macOS 15 runners) lists no rpaths, so libsystem/ was never made there."""
    libraries, rpaths, command = [], [], None
    for line in run("otool", "-l", str(path)).splitlines():
        line = line.strip()
        if line.startswith("cmd "):
            command = line.split()[1]
        elif command in ("LC_LOAD_DYLIB", "LC_LOAD_WEAK_DYLIB", "LC_REEXPORT_DYLIB") and line.startswith("name "):
            libraries.append(line[len("name "):].rsplit(" (offset ", 1)[0])
        elif command == "LC_RPATH" and line.startswith("path "):
            rpaths.append(line[len("path "):].rsplit(" (offset ", 1)[0])
    return libraries, rpaths


def build(target):
    """target/libSystem.B.dylib and target/fallback/<library>.dylib."""
    with tempfile.TemporaryDirectory() as temp:
        empty = Path(temp) / "empty.c"
        empty.write_text("")
        (target / "fallback").mkdir(parents=True)
        reexports = []
        for name in LIBRARIES:
            stub = target / "fallback" / f"{name}.dylib"
            run(*CLANG, "-dynamiclib", str(empty), "-o", str(stub), "-install_name", f"@rpath/{name}.dylib")
            reexports.append(f"-Wl,-reexport_library,{stub}")
        sdk = run("xcrun", "--show-sdk-path").strip()
        run(*CLANG, "-dynamiclib", "-nostdlib", str(empty), "-o", str(target / "libSystem.B.dylib"),
            "-install_name", "@rpath/libSystem.B.dylib", *reexports, f"-Wl,-reexport_library,{sdk}/usr/lib/libSystem.tbd",
            "-Wl,-rpath,/usr/lib/system", "-Wl,-rpath,@loader_path/fallback")


def check(executable, target):
    """Every libSystem import of `executable` binds to the same address with `target` as without it."""
    # (The name is the word before "(from libSystem)": Xcode 16's dyld_info prints no index before it.)
    imports = [line.split()[-3] for line in run("xcrun", "dyld_info", "-imports", str(executable)).splitlines()
               if line.strip().endswith("(from libSystem)")]
    if len(imports) < 100:
        raise RuntimeError(f"only {len(imports)} imports from libSystem: not the runtime this was made for")
    with tempfile.TemporaryDirectory() as temp:
        temp = Path(temp)
        source = temp / "probe.c"
        lines = ['#include <stdio.h>']
        lines += [f'extern char s{i} __asm__("{name}");' for i, name in enumerate(imports)]
        lines.append("void *volatile table[] = {" + ", ".join(f"&s{i}" for i in range(len(imports))) + "};")
        lines.append('int main(void) { for (unsigned i = 0; i < sizeof table / sizeof *table; i++) printf("%p\\n", table[i]); }')
        source.write_text("\n".join(lines) + "\n")
        plain, through = temp / "plain", temp / "through"
        run(*CLANG, "-O1", "-Wl,-headerpad_max_install_names", str(source), "-o", str(plain))
        shutil.copy(plain, through)
        run("install_name_tool", "-change", "/usr/lib/libSystem.B.dylib", "@rpath/libSystem.B.dylib",
            "-add_rpath", RPATH, "-add_rpath", "/usr/lib", str(through))
        run("codesign", "-s", "-", "-f", str(through))
        shutil.copytree(target, temp / "libsystem")
        expected = run(str(plain))
        if run(str(through)) != expected:
            raise RuntimeError("an import binds elsewhere through libsystem/")
        loaded = subprocess.run([str(through)], capture_output=True, text=True, env={**os.environ, "DYLD_PRINT_LIBRARIES": "1"}).stderr
        if str(temp / "libsystem" / "libSystem.B.dylib") not in loaded:
            raise RuntimeError("dyld did not load libsystem/libSystem.B.dylib")
        # The fallback libraries, as on a system without these: the first rpath leads nowhere.
        shim = temp / "libsystem" / "libSystem.B.dylib"
        run("install_name_tool", "-rpath", "/usr/lib/system", "/nonexistent/system", str(shim))
        run("codesign", "-s", "-", "-f", str(shim))
        if run(str(through)) != expected:
            raise RuntimeError("an import binds elsewhere through libsystem/fallback")
        # And without libsystem/ at all.
        shutil.rmtree(temp / "libsystem")
        if run(str(through)) != expected:
            raise RuntimeError("an import binds elsewhere without libsystem/")
    return len(imports)


def main():
    directory = Path(sys.argv[1])
    executable = directory / "pi-bin"
    target = directory / "libsystem"
    shutil.rmtree(target, ignore_errors=True)
    libraries, rpaths = dependents(executable)
    if "@rpath/libSystem.B.dylib" not in libraries or rpaths[:1] != [RPATH]:
        print("libsystem: pi-bin loads libSystem from /usr/lib (an older runtime): none made", file=sys.stderr)
        return
    try:
        build(target)
        count = check(executable, target)
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        shutil.rmtree(target, ignore_errors=True)
        detail = getattr(error, "stderr", None) or error
        print(f"libsystem: none made, pi-bin binds through /usr/lib/libSystem.B.dylib as before ({detail})", file=sys.stderr)
        return
    print(f"libsystem: {count} imports bind to the same addresses through it")


main()
