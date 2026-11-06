#!/usr/bin/env python3
"""Assemble the engine pack (CONTRACT.md) from the engine release, the glibc stage and the userland stage.

Runs in the builder container: /engine (this tree, read-only), /dl (cache), /stage (glibc/, userland/), /out.
Writes /out/jniLibs/arm64-v8a (every ELF and executable script, named lib*.so) and /out/assets/engine
(manifest.json, files/ data copied into PREFIX, package/ engine side files, licenses/).

  python3 -I assemble.py [--engine-only]
"""

import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile

ENGINE_ONLY = "--engine-only" in sys.argv[1:]
OUT = "/out"
JNI = f"{OUT}/jniLibs/arm64-v8a"
ASSETS = f"{OUT}/assets/engine"
GLIBC_LIB = "/stage/glibc/lib"
USERLAND = "/stage/userland/root"
LICENSES_SRC = "/engine/licenses"

# Engine side files PI_PACKAGE_DIR must hold (theme/, export-html/, assets/, package.json per the contract), plus the
# docs the system prompt points the model at and photon's wasm. Not shipped: CHANGELOG.md (interactive "what's new"
# only), native/ (an X11 addon), the doom example's prebuilt game.
PACKAGE_KEEP = ["theme", "export-html", "assets", "package.json", "README.md", "docs", "examples", "photon_rs_bg.wasm"]
PACKAGE_SKIP = ["examples/extensions/doom-overlay/doom/build"]


def read_env(path):
    env = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k] = v
    return env


V = read_env("/engine/versions.env")
PREFIX = V["PREFIX"]
LOADER_SONAME = "ld-linux-aarch64.so.1"
LOADER = f"{PREFIX}/lib/{LOADER_SONAME}"


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(*cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


# ---- ELF helpers -------------------------------------------------------------------------------------------------

def is_elf(path):
    with open(path, "rb") as f:
        return f.read(4) == b"\x7fELF"


def elf_info(path):
    """(interp, needed, soname, has_rpath) via readelf."""
    interp = None
    for m in re.finditer(r"\[Requesting program interpreter: ([^\]]+)\]", run("readelf", "-lW", path)):
        interp = m.group(1)
    dyn = run("readelf", "-dW", path)
    needed = re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", dyn)
    soname = re.search(r"\(SONAME\)\s+Library soname: \[([^\]]+)\]", dyn)
    has_rpath = bool(re.search(r"\((RPATH|RUNPATH)\)", dyn))
    return interp, needed, soname.group(1) if soname else None, has_rpath


def set_interp_in_place(path, interp):
    """Rewrite PT_INTERP inside its existing room (no segment moves; the engine is never patchelf'd)."""
    data = interp.encode()
    with open(path, "r+b") as f:
        h = f.read(64)
        assert h[:6] == b"\x7fELF\x02\x01", "not a little-endian ELF64"
        (phoff,) = struct.unpack_from("<Q", h, 32)
        phentsize, phnum = struct.unpack_from("<HH", h, 54)
        for i in range(phnum):
            f.seek(phoff + i * phentsize)
            ph = f.read(phentsize)
            if struct.unpack_from("<I", ph, 0)[0] == 3:  # PT_INTERP
                (off,) = struct.unpack_from("<Q", ph, 8)
                (size,) = struct.unpack_from("<Q", ph, 32)
                if len(data) >= size:
                    sys.exit(f"PT_INTERP room {size} too small for {interp}")
                f.seek(off)
                f.write(data + b"\0" * (size - len(data)))
                return
    sys.exit(f"{path}: no PT_INTERP")


def mangle(name):
    return re.sub(r"[^A-Za-z0-9_]", "_", name)


def lib_file_name(soname):
    if soname == LOADER_SONAME:
        return "libld.so"
    m = mangle(soname)
    return (m if m.startswith("lib") else "lib" + m) + ".so"


def exe_file_name(basename):
    return f"lib{mangle(basename)}_exe.so"


# ---- engine ------------------------------------------------------------------------------------------------------

def fetch_engine():
    url, want = V["ENGINE_URL"], V["ENGINE_SHA256"]
    dst = f"/dl/{os.path.basename(url).replace('.tar.gz', '')}-{V['ENGINE_VERSION']}.tar.gz"
    if not (os.path.exists(dst) and sha256_file(dst) == want):
        legacy = f"/dl/{os.path.basename(url)}"  # a manually seeded download
        if os.path.exists(legacy) and sha256_file(legacy) == want:
            shutil.copyfile(legacy, dst)
        else:
            run("curl", "-fsSL", "--retry", "3", "-o", dst + ".part", url)
            os.replace(dst + ".part", dst)
    got = sha256_file(dst)
    if got != want:
        sys.exit(f"engine sha256 {got} != pinned {want}")
    root = "/tmp/engine"
    shutil.rmtree(root, ignore_errors=True)
    with tarfile.open(dst) as t:
        t.extractall(root, filter="data")
    (top,) = os.listdir(root)
    return f"{root}/{top}"


def copy_package(src_root):
    dst_root = f"{ASSETS}/package"
    listed = []
    for item in PACKAGE_KEEP:
        src = f"{src_root}/{item}"
        if os.path.isdir(src):
            for dirpath, dirnames, filenames in os.walk(src):
                rel_dir = os.path.relpath(dirpath, src_root)
                dirnames[:] = sorted(d for d in dirnames if os.path.join(rel_dir, d) not in PACKAGE_SKIP)
                for fn in sorted(filenames):
                    listed.append(os.path.join(rel_dir, fn))
        elif os.path.isfile(src):
            listed.append(item)
        else:
            sys.exit(f"engine tarball lacks {item}")
    for rel in listed:
        os.makedirs(os.path.dirname(f"{dst_root}/{rel}"), exist_ok=True)
        shutil.copyfile(f"{src_root}/{rel}", f"{dst_root}/{rel}")
    return sorted(listed)


# ---- userland tree -----------------------------------------------------------------------------------------------

def is_script(path):
    with open(path, "rb") as f:
        return f.read(2) == b"#!"


def walk_userland():
    """Yield (relpath, kind, source) for every file/symlink in the userland root. kind: link|file."""
    for dirpath, dirnames, filenames in os.walk(USERLAND):
        dirnames.sort()
        for fn in sorted(filenames):
            p = os.path.join(dirpath, fn)
            yield os.path.relpath(p, USERLAND), p


def write_licenses():
    lic = f"{ASSETS}/licenses"
    for fn in sorted(os.listdir(LICENSES_SRC)):
        if os.path.isfile(f"{LICENSES_SRC}/{fn}"):
            shutil.copyfile(f"{LICENSES_SRC}/{fn}", f"{lic}/{fn}")
    os.makedirs(f"{lic}/texts")
    for name in ["LGPL-2.1", "GPL-2", "GPL-3", "Apache-2.0", "MPL-2.0"]:
        shutil.copyfile(f"/usr/share/common-licenses/{name}", f"{lic}/texts/{name}")
    os.makedirs(f"{lic}/glibc")
    with tarfile.open(f"/dl/glibc-{V['GLIBC_VERSION']}.tar.xz") as t:
        for name in ["COPYING.LIB", "LICENSES"]:
            with t.extractfile(f"glibc-{V['GLIBC_VERSION']}/{name}") as src, open(f"{lic}/glibc/{name}", "wb") as dst:
                shutil.copyfileobj(src, dst)
    shutil.copyfile(f"/dl/glibc-packages-{V['TERMUX_GLIBC_COMMIT']}/LICENSE.md", f"{lic}/glibc/termux-LICENSE.md")
    lines = [
        f"Pi-Bolt engine {V['ENGINE_VERSION']} ({V['ENGINE_URL']}, sha256 {V['ENGINE_SHA256']})",
        f"glibc {V['GLIBC_VERSION']} + termux-pacman/glibc-packages {V['TERMUX_GLIBC_COMMIT']}",
    ]
    if not ENGINE_ONLY:
        lines += [f"curl {V['CURL_VERSION']}", f"git {V['GIT_VERSION']}", ""]
        with open("/stage/userland/licenses/debian-packages.txt") as f:
            lines += f.read().splitlines()
    with open(f"{lic}/VERSIONS.txt", "w") as f:
        f.write("\n".join(lines) + "\n")


def main():
    shutil.rmtree(JNI, ignore_errors=True)
    shutil.rmtree(ASSETS, ignore_errors=True)
    os.makedirs(JNI)
    os.makedirs(f"{ASSETS}/files")
    os.makedirs(f"{ASSETS}/licenses")

    links = {}  # prefix-relative path -> jniLibs file name
    files = {}  # prefix-relative path -> asset path
    jni_by_hash = {}  # content sha256 -> jniLibs file name (hardlinks and copies ship once)
    exes = []  # (jni name, source) of userland ELF executables, patched after copying
    shared = []  # jni names of userland shared libraries

    # Engine: exec'd directly as nativeLibraryDir/libpibolt.so; PT_INTERP rewritten in place, never patchelf'd or
    # stripped (its embedded code and static heap are read from /proc/self/exe).
    engine_root = fetch_engine()
    shutil.copyfile(f"{engine_root}/pi", f"{JNI}/libpibolt.so")
    set_interp_in_place(f"{JNI}/libpibolt.so", LOADER)
    package = copy_package(engine_root)
    os.makedirs(f"{ASSETS}/licenses/pi-bolt")
    for fn in ["LICENSE", "THIRD_PARTY_NOTICES.md"]:
        shutil.copyfile(f"{engine_root}/{fn}", f"{ASSETS}/licenses/pi-bolt/{fn}")
    _, engine_needed, _, _ = elf_info(f"{JNI}/libpibolt.so")

    # Candidate shared libraries by soname: glibc first, then the userland's.
    libs = {}
    for fn in sorted(os.listdir(GLIBC_LIB)):
        libs[fn] = f"{GLIBC_LIB}/{fn}"
    glibc_sonames = set(libs)

    roots = list(engine_needed)
    if not ENGINE_ONLY:
        for rel, src in walk_userland():
            if rel.startswith("lib/") and not os.path.islink(src) and is_elf(src):
                soname = elf_info(src)[2] or os.path.basename(rel)
                if soname in libs:
                    sys.exit(f"{rel}: soname {soname} also provided by {libs[soname]}")
                libs[soname] = src
        for rel, src in walk_userland():
            if rel.startswith("lib/") and is_elf(src):
                continue  # shared libraries are added by need, below
            real = os.path.realpath(src)
            if not real.startswith(USERLAND + "/"):
                sys.exit(f"{rel} points outside the userland root: {os.readlink(src)}")
            executable = os.access(real, os.X_OK) and (rel.startswith("bin/") or rel.startswith("libexec/"))
            if executable and (is_elf(real) or is_script(real)):
                digest = sha256_file(real)
                name = jni_by_hash.get(digest)
                if name is None:
                    name = exe_file_name(os.path.basename(os.path.relpath(real, USERLAND)))
                    if os.path.exists(f"{JNI}/{name}"):
                        sys.exit(f"{rel}: jniLibs name {name} already taken")
                    shutil.copyfile(real, f"{JNI}/{name}")
                    jni_by_hash[digest] = name
                    if is_elf(real):
                        exes.append(name)
                        roots += elf_info(real)[1]
                links[rel] = name
            else:
                asset = f"engine/files/{rel}"
                os.makedirs(os.path.dirname(f"{OUT}/assets/{asset}"), exist_ok=True)
                shutil.copyfile(real, f"{OUT}/assets/{asset}")
                files[rel] = asset
        lic = "/stage/userland/licenses"
        if os.path.isdir(lic):
            shutil.copytree(lic, f"{ASSETS}/licenses/userland")

    # Shared libraries: the NEEDED closure of the engine and every shipped executable.
    todo, seen = list(roots), set()
    while todo:
        soname = todo.pop()
        if soname in seen:
            continue
        seen.add(soname)
        if soname not in libs:
            sys.exit(f"NEEDED {soname} is not provided by glibc or the userland")
        name = lib_file_name(soname)
        shutil.copyfile(libs[soname], f"{JNI}/{name}")
        links[f"lib/{soname}"] = name
        if soname not in glibc_sonames:
            shared.append(name)
        todo += elf_info(libs[soname])[1]
    links[f"lib/{LOADER_SONAME}"] = "libld.so"
    if not os.path.exists(f"{JNI}/libld.so"):
        shutil.copyfile(libs[LOADER_SONAME], f"{JNI}/libld.so")

    # Userland ELFs: debug info stripped, loader set to the shipped one, no RPATH/RUNPATH.
    for name in exes + shared:
        p = f"{JNI}/{name}"
        run("strip", "--strip-unneeded", p)
        if name in exes:
            run("patchelf", "--set-interpreter", LOADER, "--remove-rpath", p)
        else:
            run("patchelf", "--remove-rpath", p)
        interp, _, _, has_rpath = elf_info(p)
        assert not has_rpath, p
        assert name in shared or interp == LOADER, (p, interp)
    for name in os.listdir(JNI):
        os.chmod(f"{JNI}/{name}", 0o755)

    write_licenses()

    env = {
        "PATH": f"{PREFIX}/bin:/system/bin",
        "SHELL": f"{PREFIX}/bin/bash",
        "LANG": "C.UTF-8",
        "TMPDIR": f"{PREFIX}/tmp",
    }
    if not ENGINE_ONLY:
        env.update({
            "GIT_EXEC_PATH": f"{PREFIX}/libexec/git-core",
            "GIT_TEMPLATE_DIR": f"{PREFIX}/share/git-core/templates",
            "SSL_CERT_FILE": f"{PREFIX}/etc/ssl/certs/ca-certificates.crt",
            "TERMINFO": f"{PREFIX}/share/terminfo",
        })
    else:
        env["SHELL"] = "/system/bin/sh"

    manifest = {
        "version": 1,
        "engine": V["ENGINE_VERSION"],
        "glibc": V["GLIBC_VERSION"],
        "prefix": PREFIX,
        "links": [{"path": p, "target": links[p]} for p in sorted(links)],
        "files": [{"path": p, "asset": files[p]} for p in sorted(files)],
        "package": package,
        "env": env,
    }
    # Fingerprint: every shipped file's content plus the manifest itself.
    h = hashlib.sha256()
    for top in [f"{OUT}/jniLibs", f"{OUT}/assets"]:
        for dirpath, dirnames, filenames in os.walk(top):
            dirnames.sort()
            for fn in sorted(filenames):
                p = os.path.join(dirpath, fn)
                h.update(f"{sha256_file(p)}  {os.path.relpath(p, OUT)}\n".encode())
    h.update(json.dumps(manifest, sort_keys=True).encode())
    manifest["packFingerprint"] = h.hexdigest()
    with open(f"{ASSETS}/manifest.json", "w") as f:
        json.dump(manifest, f, indent=1)
        f.write("\n")

    total = sum(os.path.getsize(f"{JNI}/{n}") for n in os.listdir(JNI))
    print(f"jniLibs: {len(os.listdir(JNI))} files, {total / 1e6:.1f} MB; links {len(links)}, files {len(files)}, "
          f"package {len(package)}")
    print(f"packFingerprint {manifest['packFingerprint']}")


main()
