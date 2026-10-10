r"""Checks a Windows LTO build of the runtime for indirect calls to SysV-convention functions that Control Flow Guard would check
with the target in the wrong register.

On Windows x64, LLVM gives a CFG-checked indirect call's target to the dispatch function in RAX only for the Win64 convention; for
a call to a sysv_abi function (JavaScriptCore's host functions, JIT operations, custom accessors, compiled regular expressions)
the target goes in the next argument register, and the dispatcher jumps to whatever RAX held. Such calls must go through
WTF::callSysV() (wtf/SysVCall.h), which checks the target itself and makes the call unchecked (guard_nocf). This reads every
bitcode object of the build (an LTO build's objects are bitcode; Rust's are in its .rlib archives, which it unpacks into a
temporary directory) and reports each such call that is still checked the broken way. Exit status 1 if there is one.

  py -3 scripts\lib\check-cfg-sysv-calls.py BUILD_DIR      (e.g. .work\bun\build\pibolt-release)
"""
import collections
import concurrent.futures
import os
import re
import subprocess
import sys
import tempfile

CALL = re.compile(r"(?:call|invoke) x86_64_sysvcc [^@\n]*?%[\w.]+\(")
DEFINE = re.compile(r"^define .*?@(\"[^\"]+\"|[\w.$?@]+)\(")
NOCF_GROUP = re.compile(r"^attributes #(\d+) = \{[^}]*\"guard_nocf\"", re.M)


def long_path(path):
    return "\\\\?\\" + os.path.abspath(path) if os.name == "nt" and not path.startswith("\\\\?\\") else path


def scan(path):
    path = long_path(path)
    with open(path, "rb") as f:
        if f.read(4) not in (b"BC\xc0\xde", b"\xde\xc0\x17\x0b"):
            return path, []
    ir = subprocess.run(["llvm-dis", path, "-o", "-"], capture_output=True, text=True, errors="replace").stdout
    if '!"cfguard"' not in ir:
        return path, []
    nocf = set(NOCF_GROUP.findall(ir))
    found = []
    function = "?"
    for line in ir.splitlines():
        if line.startswith("define "):
            m = DEFINE.match(line)
            function = m.group(1) if m else "?"
        elif "x86_64_sysvcc" in line and CALL.search(line):
            if not any(group in nocf for group in re.findall(r"\) #(\d+)", line)):
                found.append(function)
    return path, found


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    build = sys.argv[1]
    objects, archives = [], []
    for root, _, files in os.walk(build):
        for f in files:
            if f.endswith((".obj", ".o")):
                objects.append(os.path.join(root, f))
            elif f.endswith(".rlib") and "x86_64-pc-windows-msvc" in root:
                archives.append(os.path.join(root, f))
    with tempfile.TemporaryDirectory(prefix="cfg-sysv-") as scratch:
        for i, archive in enumerate(archives):
            into = os.path.join(scratch, str(i))
            os.makedirs(into)
            subprocess.run(["llvm-ar", "x", os.path.abspath(archive)], cwd=into, capture_output=True)
            objects += [os.path.join(into, f) for f in os.listdir(into)]
        bad = collections.Counter()
        with concurrent.futures.ThreadPoolExecutor(os.cpu_count() or 4) as pool:
            for path, functions in pool.map(scan, objects):
                for function in functions:
                    bad[(os.path.basename(path), function)] += 1
    print(f"{len(objects)} objects; {sum(bad.values())} indirect SysV calls checked by CFG's dispatch (target in the wrong register)")
    for (obj, function), n in bad.most_common():
        print(f"  {n:3} {obj}: {function[:160]}")
    sys.exit(1 if bad else 0)


main()
