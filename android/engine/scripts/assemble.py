#!/usr/bin/env python3
"""Assemble the engine pack (CONTRACT.md) from the engine release, the glibc stage and the userland stage.

Runs in the builder container: /engine (this tree, read-only), /dl (cache), /stage (glibc/, userland/), /out.
Writes /out/jniLibs/arm64-v8a (every ELF and executable script, named lib*.so) and /out/assets/engine
(manifest.json, files/ data copied into PREFIX, package/ engine side files, licenses/).

  python3 -I assemble.py [--engine-only]
"""

import base64
import hashlib
import importlib.util
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import zipfile

ENGINE_ONLY = "--engine-only" in sys.argv[1:]
OUT = "/out"
JNI = f"{OUT}/jniLibs/arm64-v8a"
ASSETS = f"{OUT}/assets/engine"
GLIBC_LIB = "/stage/glibc/lib"
USERLAND = "/stage/userland/root"
RUNTIMES = "/stage/runtimes/root"
ESBUILD = "/stage/esbuild/root"
LICENSES_SRC = "/engine/licenses"
EXTENSION_PINS = "/extensions.txt"  # the repo's extensions.txt (name, version, npm integrity), mounted by build-pack.sh

# OpenSec's extensions bundled with the engine (Bun builds without runtime dependencies), at the versions extensions.txt
# pins. Not opensec-pi-fff.
BUNDLED_EXTENSIONS = ["opensec-pi-subagents", "opensec-pi-todo"]
# Third-party extensions with their runtime dependencies (web search, ask_user_question): npm/extensions.txt and the
# lockfiles beside it, assembled by npm_extensions.py.
NPM_DIR = "/engine/npm"
_spec = importlib.util.spec_from_file_location("npm_extensions", "/engine/scripts/npm_extensions.py")
NPM_EXTENSIONS = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(NPM_EXTENSIONS)

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
PYTHON_LIB = "lib/python" + ".".join(V["PYTHON_VERSION"].split(".")[:2])

# Language runtimes (runtimes/build-runtimes.sh). Their library trees hold thousands of files, some in directories
# the APK build would drop (names starting with "_"), so each ships as one zip asset that the app unpacks into
# PREFIX/<path>. Extension modules (ELF) inside a tree ship from jniLibs like any library, linked into the tree.
RUNTIME_TREES = {"lib/node_modules/npm": "npm.zip", PYTHON_LIB: "python.zip"}
# id, display name, version, commands, and the prefix paths that belong to it (for its size on the Runtimes screen).
RUNTIMES_INFO = [
    ("node", "Node.js", V["NODE_VERSION"], ["node", "npm", "npx", "esbuild"],
     ["bin/node", "bin/npm", "bin/npx", "lib/node_modules/npm", "bin/esbuild"]),
    ("python", "Python", V["PYTHON_VERSION"], ["python3", "python", "pip", "pip3"],
     ["bin/python", "bin/pip", PYTHON_LIB, "pip.conf"]),
]
LOADER_SONAME = "ld-linux-aarch64.so.1"
LOADER = f"{PREFIX}/lib/{LOADER_SONAME}"
# Android's own linker: the interpreter of programs built for Android (esbuild), which ship unchanged.
ANDROID_LINKER = "/system/bin/linker64"


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


# ---- extensions --------------------------------------------------------------------------------------------------

def read_extension_pins():
    pins = {}
    with open(EXTENSION_PINS) as f:
        for line in f:
            parts = line.split()
            if parts and not parts[0].startswith("#"):
                name, version, integrity = parts
                pins[name] = (version, integrity)
    return pins


def npm_integrity(path):
    h = hashlib.sha512()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return "sha512-" + base64.b64encode(h.digest()).decode()


def copy_extensions():
    """Each bundled extension's npm tarball, checked against its pinned integrity, unpacked to assets
    engine/extensions/<name>/. Returns the manifest entries (every file listed, like `package`)."""
    pins = read_extension_pins()
    entries = []
    for name in BUNDLED_EXTENSIONS:
        if name not in pins:
            sys.exit(f"extensions.txt does not pin {name}")
        version, want = pins[name]
        dst = f"/dl/{name}-{version}.tgz"
        if not (os.path.exists(dst) and npm_integrity(dst) == want):
            run("curl", "-fsSL", "--retry", "3", "-o", dst + ".part", f"https://registry.npmjs.org/{name}/-/{name}-{version}.tgz")
            os.replace(dst + ".part", dst)
        got = npm_integrity(dst)
        if got != want:
            sys.exit(f"{name}@{version} integrity {got} != pinned {want}")
        root = f"/tmp/ext/{name}"
        shutil.rmtree(root, ignore_errors=True)
        with tarfile.open(dst) as t:
            t.extractall(root, filter="data")
        src = f"{root}/package"
        with open(f"{src}/package.json") as f:
            meta = json.load(f)
        if meta.get("name") != name or meta.get("version") != version:
            sys.exit(f"{dst} holds {meta.get('name')}@{meta.get('version')}")
        if meta.get("dependencies"):
            sys.exit(f"{name} has runtime dependencies, which the pack does not install: {meta['dependencies']}")
        listed = []
        for dirpath, dirnames, filenames in os.walk(src):
            dirnames.sort()
            for fn in sorted(filenames):
                rel = os.path.relpath(os.path.join(dirpath, fn), src)
                listed.append(rel)
                os.makedirs(os.path.dirname(f"{ASSETS}/extensions/{name}/{rel}"), exist_ok=True)
                shutil.copyfile(f"{src}/{rel}", f"{ASSETS}/extensions/{name}/{rel}")
        lic = f"{ASSETS}/licenses/extensions/{name}"
        os.makedirs(lic)
        for fn in ["LICENSE", "NOTICE"]:
            if os.path.isfile(f"{src}/{fn}"):
                shutil.copyfile(f"{src}/{fn}", f"{lic}/{fn}")
        entries.append({"name": name, "version": version, "integrity": want, "files": sorted(listed)})
    return entries


# ---- userland tree -----------------------------------------------------------------------------------------------

def is_script(path):
    with open(path, "rb") as f:
        return f.read(2) == b"#!"


def walk_root(root):
    """Yield (relpath, source) for every file/symlink in a stage root."""
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for fn in sorted(filenames):
            p = os.path.join(dirpath, fn)
            yield os.path.relpath(p, root), p


def walk_stage():
    """(relpath, source, root) for the userland, the runtimes and esbuild; a path two provide is an error."""
    seen = {}
    for root in [USERLAND, RUNTIMES, ESBUILD]:
        for rel, src in walk_root(root):
            if rel in seen:
                sys.exit(f"{rel} is in both {seen[rel]} and {root}")
            seen[rel] = root
            yield rel, src, root


def tree_of(rel):
    for tree in RUNTIME_TREES:
        if rel.startswith(tree + "/"):
            return tree
    return None


def write_tree_zip(tree, members):
    """members: [(relpath inside the tree, source)]. Deterministic: sorted, fixed times. Returns the manifest entry."""
    asset = f"engine/trees/{RUNTIME_TREES[tree]}"
    dst = f"{OUT}/assets/{asset}"
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    executables = []
    size = 0
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel, src in sorted(members):
            info = zipfile.ZipInfo(rel, date_time=(2000, 1, 1, 0, 0, 0))
            mode = 0o755 if os.stat(src).st_mode & 0o111 else 0o644  # by mode bits: root passes X_OK on some mounts
            info.external_attr = (0o100000 | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(src, "rb") as f:
                data = f.read()
            z.writestr(info, data)
            size += len(data)
            if mode == 0o755:
                executables.append(rel)
    return {"path": tree, "asset": asset, "sha256": sha256_file(dst), "files": len(members), "bytes": size,
            "executables": executables}


def write_licenses(extensions):
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
    for e in extensions:
        lines.append(f"{e.get('package', e['name'])} {e['version']} (npm, {e['integrity']})")
        lines += [f"  {dep}" for dep in e.get("dependencies", [])]
    if not ENGINE_ONLY:
        lines += [f"curl {V['CURL_VERSION']}", f"git {V['GIT_VERSION']}"]
        lines += [f"Node.js {V['NODE_VERSION']} ({V['NODE_URL']}, sha256 {V['NODE_SHA256']})",
                  f"CPython {V['PYTHON_VERSION']} ({V['PYTHON_URL']}, sha256 {V['PYTHON_SHA256']})",
                  f"esbuild {V['ESBUILD_VERSION']} ({V['ESBUILD_URL']}, sha256 {V['ESBUILD_SHA256']}) + "
                  "runtimes/esbuild-host-versions.patch",
                  f"Go {V['GO_VERSION']} ({V['GO_URL']}, sha256 {V['GO_SHA256']})", ""]
        with open("/stage/userland/licenses/debian-packages.txt") as f:
            lines += f.read().splitlines()
    with open(f"{lic}/VERSIONS.txt", "w") as f:
        f.write("\n".join(lines) + "\n")


def runtimes_info(links, files, trees, engine_needed):
    """Manifest `runtimes`: what the Runtimes screen shows. bytes = its executables, modules and tree, plus the shared
    libraries only it needs (not the engine or the userland), uncompressed as installed."""
    if ENGINE_ONLY or not os.path.isdir(RUNTIMES):
        return []
    def owner(rel):
        for rid, _, _, _, paths in RUNTIMES_INFO:
            if any(rel == p or rel.startswith(p + "/") or (p.startswith("bin/") and rel.startswith(p)) for p in paths):
                return rid
        return None
    jni_size = lambda name: os.path.getsize(f"{JNI}/{name}")
    # Who needs each shared library (NEEDED closure per owner: engine/userland = None).
    lib_owners = {}
    def need(soname, who, seen):
        if soname in seen or f"lib/{soname}" not in links:
            return
        seen.add(soname)
        lib_owners.setdefault(soname, set()).add(who)
        for dep in elf_info(f"{JNI}/{links['lib/' + soname]}")[1]:
            need(dep, who, seen)
    for so in engine_needed:
        need(so, None, set())
    counted = set()
    for rel, name in links.items():
        if rel.startswith("lib/") and "/" not in rel[4:] or name in counted:
            continue
        counted.add(name)
        p = f"{JNI}/{name}"
        if is_elf(p):
            for so in elf_info(p)[1]:
                need(so, owner(rel), set())
    out = []
    for rid, title, version, commands, _ in RUNTIMES_INFO:
        size, seen = 0, set()
        for rel, name in links.items():
            if name in seen:
                continue
            if owner(rel) == rid or (rel.startswith("lib/") and "/" not in rel[4:] and lib_owners.get(rel[4:]) == {rid}):
                seen.add(name)
                size += jni_size(name)
        size += sum(t["bytes"] for t in trees if owner(t["path"]) == rid)
        size += sum(os.path.getsize(f"{OUT}/assets/{a}") for rel, a in files.items() if owner(rel) == rid)
        out.append({"id": rid, "name": title, "version": version, "commands": commands, "bytes": size,
                    "details": runtime_details(rid)})
    return out


def runtime_details(rid):
    """Versions of the package managers that come with a runtime."""
    if rid == "node":
        with open(f"{RUNTIMES}/lib/node_modules/npm/package.json") as f:
            return {"npm": json.load(f)["version"], "esbuild": V["ESBUILD_VERSION"]}
    site = f"{RUNTIMES}/{PYTHON_LIB}/site-packages"
    pips = [d for d in os.listdir(site) if d.startswith("pip-") and d.endswith(".dist-info")]
    return {"pip": pips[0][len("pip-"):-len(".dist-info")]} if pips else {}


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
    modules = []  # jni names of extension modules inside runtime trees (no SONAME, never NEEDED)
    tree_members = {}  # runtime tree -> [(path inside the tree, source)]
    trees = []  # manifest entries of the runtime tree zips

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
        for rel, src, _ in walk_stage():
            if rel.startswith("lib/") and "/" not in rel[4:] and not os.path.islink(src) and is_elf(src):
                soname = elf_info(src)[2] or os.path.basename(rel)
                if soname in libs:
                    sys.exit(f"{rel}: soname {soname} also provided by {libs[soname]}")
                libs[soname] = src
        for rel, src, root in walk_stage():
            if rel.startswith("lib/") and "/" not in rel[4:] and is_elf(src):
                continue  # shared libraries are added by need, below
            real = os.path.realpath(src)
            if not real.startswith(root + "/"):
                sys.exit(f"{rel} points outside its stage root: {os.readlink(src)}")
            tree = tree_of(rel)
            if tree is not None and is_elf(real):
                # An extension module (Python lib-dynload, a .node addon): from jniLibs, linked into its tree.
                if not (rel.endswith(".so") or rel.endswith(".node")):
                    sys.exit(f"{rel}: an ELF file in a runtime tree that is not an extension module")
                name = lib_file_name(os.path.basename(rel))
                if os.path.exists(f"{JNI}/{name}"):
                    sys.exit(f"{rel}: jniLibs name {name} already taken")
                shutil.copyfile(real, f"{JNI}/{name}")
                modules.append(name)
                roots += elf_info(real)[1]
                links[rel] = name
                continue
            if tree is not None:
                tree_members.setdefault(tree, []).append((os.path.relpath(rel, tree), real))
                continue
            executable = os.access(real, os.X_OK) and (rel.startswith("bin/") or rel.startswith("libexec/"))
            if executable and (is_elf(real) or is_script(real)):
                digest = sha256_file(real)
                name = jni_by_hash.get(digest)
                if name is None:
                    name = exe_file_name(os.path.basename(os.path.relpath(real, root)))
                    if os.path.exists(f"{JNI}/{name}"):
                        sys.exit(f"{rel}: jniLibs name {name} already taken")
                    shutil.copyfile(real, f"{JNI}/{name}")
                    jni_by_hash[digest] = name
                    if is_elf(real):
                        interp, needed, _, _ = elf_info(real)
                        if interp == ANDROID_LINKER:
                            # Built for Android (no glibc): the kernel runs it with Android's linker. Not patched.
                            if needed:
                                sys.exit(f"{rel}: an Android program that needs libraries: {needed}")
                        else:
                            exes.append(name)
                            roots += needed
                links[rel] = name
            else:
                asset = f"engine/files/{rel}"
                os.makedirs(os.path.dirname(f"{OUT}/assets/{asset}"), exist_ok=True)
                shutil.copyfile(real, f"{OUT}/assets/{asset}")
                files[rel] = asset
        lic = "/stage/userland/licenses"
        if os.path.isdir(lic):
            shutil.copytree(lic, f"{ASSETS}/licenses/userland")
        lic = "/stage/runtimes/licenses"
        if os.path.isdir(lic):
            shutil.copytree(lic, f"{ASSETS}/licenses/runtimes")
        lic = "/stage/esbuild/licenses"
        if os.path.isdir(lic):
            shutil.copytree(lic, f"{ASSETS}/licenses/esbuild")
        for tree in sorted(tree_members):
            trees.append(write_tree_zip(tree, tree_members[tree]))

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
    for name in exes + shared + modules:
        p = f"{JNI}/{name}"
        run("strip", "--strip-unneeded", p)
        if name in exes:
            run("patchelf", "--set-interpreter", LOADER, "--remove-rpath", p)
        else:
            run("patchelf", "--remove-rpath", p)
        interp, _, _, has_rpath = elf_info(p)
        assert not has_rpath, p
        assert name not in exes or interp == LOADER, (p, interp)
    for name in os.listdir(JNI):
        os.chmod(f"{JNI}/{name}", 0o755)

    extensions = copy_extensions()
    extensions += NPM_EXTENSIONS.assemble(NPM_DIR, "/dl", ASSETS, f"{ASSETS}/licenses")
    write_licenses(extensions)

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
        "extensions": [
            {k: e[k] for k in ["name", "package", "version", "files", "modules"] if k in e} for e in extensions
        ],
        "trees": trees,
        "runtimes": runtimes_info(links, files, trees, engine_needed),
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
          f"package {len(package)}, extensions {', '.join(e['name'] + '@' + e['version'] for e in extensions)}")
    print(f"packFingerprint {manifest['packFingerprint']}")


main()
