"""Third-party Pi extensions with runtime dependencies, for the engine pack (CONTRACT.md, `extensions[]`).

Each extension in npm/extensions.txt is its npm tarball (integrity pinned there) plus the dependencies its lockfile
npm/<dir>.lock.json pins (scripts/lock-npm-extensions.sh), every tarball checked against its sha512 integrity. The
package's files go to assets engine/extensions/<dir>/ like the OpenSec extensions; its node_modules tree ships as one
zip asset, engine/modules/<dir>.zip (thousands of files, some in directories the APK build would drop), which the app
unpacks into PREFIX/share/pi-extensions/<dir>/node_modules. Nothing is run: no install scripts (a dependency that has
them is refused), no npm. TypeScript declarations and source maps are left out of node_modules (never loaded), and media
the README shows (a demo video, a banner) out of the package.

Loaded by assemble.py; `assemble(...)` returns the manifest entries.
"""

import base64
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import zipfile

REGISTRY = "https://registry.npmjs.org/"
MEDIA = (".mp4", ".webm", ".mov", ".gif", ".png", ".jpg", ".jpeg")
NOT_LOADED = (".d.ts", ".d.mts", ".d.cts", ".map")
LICENCE_PREFIXES = ("LICENSE", "LICENCE", "COPYING", "NOTICE")


def read_pins(path):
    pins = []
    with open(path) as f:
        for line in f:
            parts = line.split()
            if parts and not parts[0].startswith("#"):
                name, version, integrity = parts
                pins.append((name, version, integrity))
    return pins


def dir_name(name):
    """The directory (and manifest name) of a package: "@scope/pkg" is "scope-pkg"."""
    return name.lstrip("@").replace("/", "-")


def integrity_of(path):
    h = hashlib.sha512()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return "sha512-" + base64.b64encode(h.digest()).decode()


def fetch(url, dst, integrity):
    """[url] into [dst] (a cached copy is reused), checked against [integrity]."""
    if not url.startswith(REGISTRY):
        raise SystemExit(f"{url}: not on {REGISTRY}")
    if not (os.path.exists(dst) and integrity_of(dst) == integrity):
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        subprocess.run(["curl", "-fsSL", "--retry", "3", "-o", dst + ".part", url], check=True)
        os.replace(dst + ".part", dst)
    got = integrity_of(dst)
    if got != integrity:
        raise SystemExit(f"{url}: integrity {got} != pinned {integrity}")


def unpack(tgz, root):
    """The package directory of an npm tarball (its one top-level directory, usually package/) under [root]."""
    shutil.rmtree(root, ignore_errors=True)
    with tarfile.open(tgz) as t:
        t.extractall(root, filter="data")
    tops = os.listdir(root)
    if len(tops) != 1:
        raise SystemExit(f"{tgz}: {len(tops)} top-level entries")
    return os.path.join(root, tops[0])


def walk(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for fn in sorted(filenames):
            yield os.path.relpath(os.path.join(dirpath, fn), root)


def tarball_url(name, version):
    return f"{REGISTRY}{name}/-/{name.rsplit('/', 1)[-1]}-{version}.tgz"


def assemble(npm_dir, dl, assets, licenses):
    """Every pinned extension into [assets] (engine/extensions/<dir>/, engine/modules/<dir>.zip) and its licences into
    [licenses]/extensions/<dir>/. Returns manifest entries plus `integrity` and `dependencies` for VERSIONS.txt."""
    entries = []
    for name, version, integrity in read_pins(f"{npm_dir}/extensions.txt"):
        d = dir_name(name)
        tgz = f"{dl}/npm/{d}-{version}.tgz"
        fetch(tarball_url(name, version), tgz, integrity)
        src = unpack(tgz, f"/tmp/npm-ext/{d}")
        with open(f"{src}/package.json") as f:
            meta = json.load(f)
        if meta.get("name") != name or meta.get("version") != version:
            raise SystemExit(f"{tgz} holds {meta.get('name')}@{meta.get('version')}")
        files = []
        for rel in walk(src):
            if rel.lower().endswith(MEDIA) and "/" not in rel:
                continue
            # The APK build drops names starting with "." (files) and "_" (directories): such a file would be missing.
            if any(p.startswith((".", "_")) for p in rel.split("/")):
                raise SystemExit(f"{name}: {rel} would not survive the APK build")
            files.append(rel)
            os.makedirs(os.path.dirname(f"{assets}/extensions/{d}/{rel}"), exist_ok=True)
            shutil.copyfile(f"{src}/{rel}", f"{assets}/extensions/{d}/{rel}")
        lic = f"{licenses}/extensions/{d}"
        os.makedirs(lic)
        for fn in sorted(os.listdir(src)):
            if fn.upper().startswith(LICENCE_PREFIXES) and os.path.isfile(f"{src}/{fn}"):
                shutil.copyfile(f"{src}/{fn}", f"{lic}/{fn}")

        with open(f"{npm_dir}/{d}.lock.json") as f:
            lock = json.load(f)
        root = lock["packages"][""]
        if root.get("name") != name or root.get("version") != version:
            raise SystemExit(f"{d}.lock.json locks {root.get('name')}@{root.get('version')}, not {name}@{version}")
        if (root.get("dependencies") or {}) != (meta.get("dependencies") or {}):
            raise SystemExit(f"{d}.lock.json: its dependencies differ from {name}@{version}'s: run lock-npm-extensions.sh")
        modules = f"/tmp/npm-ext/{d}-modules"
        shutil.rmtree(modules, ignore_errors=True)
        os.makedirs(modules)
        deps = []
        for path, pkg in sorted(lock["packages"].items()):
            if not path or pkg.get("dev") or pkg.get("peer") or pkg.get("extraneous") or pkg.get("inBundle"):
                continue
            if pkg.get("link") or pkg.get("hasInstallScript"):
                raise SystemExit(f"{d}.lock.json: {path} is a link or has install scripts")
            if "os" in pkg and "linux" not in pkg["os"] or "cpu" in pkg and "arm64" not in pkg["cpu"]:
                continue
            rel = path.removeprefix("node_modules/")
            if not path.startswith("node_modules/") or ".." in rel.split("/"):
                raise SystemExit(f"{d}.lock.json: bad path {path}")
            dep_tgz = f"{dl}/npm/deps/{rel.replace('/', '+')}-{pkg['version']}.tgz"
            fetch(pkg["resolved"], dep_tgz, pkg["integrity"])
            dep_src = unpack(dep_tgz, f"/tmp/npm-ext/dep")
            for f_rel in walk(dep_src):
                if f_rel.endswith(NOT_LOADED):
                    continue
                os.makedirs(os.path.dirname(f"{modules}/{rel}/{f_rel}"), exist_ok=True)
                shutil.copyfile(f"{dep_src}/{f_rel}", f"{modules}/{rel}/{f_rel}")
            for fn in sorted(os.listdir(dep_src)):
                if fn.upper().startswith(LICENCE_PREFIXES) and os.path.isfile(f"{dep_src}/{fn}"):
                    os.makedirs(f"{lic}/node_modules/{rel}", exist_ok=True)
                    shutil.copyfile(f"{dep_src}/{fn}", f"{lic}/node_modules/{rel}/{fn}")
            deps.append(f"{rel} {pkg['version']} {pkg.get('license', '?')} {pkg['integrity']}")
        with open(f"{lic}/DEPENDENCIES.txt", "w") as f:
            f.write(f"# Runtime dependencies of {name} {version}, as shipped (node_modules path, version, licence, npm integrity)\n")
            f.write("\n".join(deps) + "\n")

        asset = f"engine/modules/{d}.zip"
        dst = f"{assets}/modules/{d}.zip"
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        count = size = 0
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for rel in walk(modules):
                info = zipfile.ZipInfo(rel, date_time=(2000, 1, 1, 0, 0, 0))
                info.external_attr = (0o100000 | 0o644) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                with open(f"{modules}/{rel}", "rb") as f:
                    data = f.read()
                z.writestr(info, data)
                count += 1
                size += len(data)
        h = hashlib.sha256()
        with open(dst, "rb") as f:
            h.update(f.read())
        entries.append({
            "name": d, "package": name, "version": version, "integrity": integrity, "files": files,
            "modules": {"asset": asset, "sha256": h.hexdigest(), "files": count, "bytes": size},
            "dependencies": deps,
        })
        print(f"{name}@{version}: {len(files)} files, {len(deps)} dependencies ({count} files, {size / 1e6:.1f} MB; "
              f"zip {os.path.getsize(dst) / 1e6:.1f} MB)")
    return entries
