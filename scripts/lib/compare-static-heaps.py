#!/usr/bin/env python3
"""Windows: checks that a compiled executable's static heap has no pointer that its writer did not relocate.

Build the same program twice, with two copies of the runtime (two files load at two different addresses), and compare:

  python scripts/lib/compare-static-heaps.py A.exe B.exe

Every pointer into the executable that the writer found is written at the preferred base, so the heap's sections must be the same
byte for byte. A pointer it missed (in an encoding it does not know, or not on an 8-byte boundary) differs between the two by as
much as the two builds' addresses did; so does a hash table keyed by addresses. Each run of differing bytes is printed, with the
difference of the 8-byte little-endian words that contain it.
"""
import struct
import sys


def sections(path):
    data = open(path, "rb").read()
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    count = struct.unpack_from("<H", data, pe + 6)[0]
    table = pe + 24 + struct.unpack_from("<H", data, pe + 20)[0]
    out = []
    for i in range(count):
        name, vsize, va, rawsize, rawptr = struct.unpack_from("<8sIIII", data, table + 40 * i)
        name = name.rstrip(b"\0").decode("ascii", "replace")
        out.append((name, va, vsize, data[rawptr:rawptr + min(rawsize, vsize)] if rawsize else b""))
    return out


MAGIC = b"BTHEAP06"  # StaticHeap::Header::expectedMagic
OFFSET_OF_REGION_BASE = 336  # offsetof(StaticHeap::Header, regionBase), static_assert-ed in StaticHeap.cpp


def region_base(path):
    """Where the region was when the heap was built: the header is in the executable as it was written (in Bun's module graph),
    with its addresses not relocated. (The magic is also in the runtime's code, as an immediate: those are not page-aligned.)"""
    data = open(path, "rb").read()
    at = data.find(MAGIC)
    while at >= 0:
        size, = struct.unpack_from("<Q", data, at + 16)
        base, = struct.unpack_from("<Q", data, at + OFFSET_OF_REGION_BASE)
        if at % 512 == 0 and 0 < size <= len(data) and base and base % 0x10000 == 0 and base < 1 << 47:
            return base
        at = data.find(MAGIC, at + 1)
    return None


def main():
    # The comparison tests something only if the two builds were at different addresses.
    bases = region_base(sys.argv[1]), region_base(sys.argv[2])
    print(f"built with the region at {bases[0] and hex(bases[0])} and {bases[1] and hex(bases[1])}")
    if None in bases:
        print("no static heap header found")
        return 2
    if bases[0] == bases[1]:
        print("both builds had the region at the same address: the comparison would test nothing (build again)")
        return 2
    a, b = sections(sys.argv[1]), sections(sys.argv[2])
    wanted = (".pbheap", ".pbimage", ".pbcode")
    pa = [s for s in a if s[0] in wanted]
    pb = [s for s in b if s[0] in wanted]
    if [(s[0], s[1], len(s[3])) for s in pa] != [(s[0], s[1], len(s[3])) for s in pb]:
        print("the layouts differ:")
        print("  ", [(s[0], hex(s[1]), len(s[3])) for s in pa])
        print("  ", [(s[0], hex(s[1]), len(s[3])) for s in pb])
        return 2
    # Overview: the differing 8-byte words of each section, by kind. The delta between the two builds' addresses is the most
    # common difference of the low 48 bits.
    from collections import Counter
    words = []
    for (name, va, _, x), (_, _, _, y) in zip(pa, pb):
        for at in range(0, min(len(x), len(y)) - 7, 8):
            if x[at:at + 8] != y[at:at + 8]:
                words.append((name, va, at, int.from_bytes(x[at:at + 8], "little"), int.from_bytes(y[at:at + 8], "little")))
    mask = (1 << 48) - 1
    deltas = Counter(((b & mask) - (a & mask)) & mask for _, _, _, a, b in words)
    delta = deltas.most_common(1)[0][0] if deltas else 0
    kinds = Counter()
    examples = {}
    for name, va, at, a, b in words:
        same_top = (a >> 48) == (b >> 48)
        if ((b & mask) - (a & mask)) & mask == delta and same_top:
            kind = f"{name}@{va:#x}: pointer with tag {a >> 48:#06x} in the top 16 bits" if a >> 48 else f"{name}@{va:#x}: plain pointer"
        else:
            kind = f"{name}@{va:#x}: other"
        kinds[kind] += 1
        examples.setdefault(kind, []).append(f"+{at:#x}: {a:#018x} vs {b:#018x}")
    if words:
        print(f"delta between the builds' addresses: {delta:#x}")
        for kind, count in sorted(kinds.items()):
            print(f"  {count:6} words  {kind}   e.g. {'; '.join(examples[kind][:3])}")
    runs = 0
    total = 0
    for (name, va, _, x), (_, _, _, y) in zip(pa, pb):
        if x == y:
            print(f"{name} at {va:#x}: {len(x)} bytes, identical")
            continue
        at = 0
        n = len(x)
        while at < n:
            if x[at] == y[at]:
                at += 1
                continue
            start = at
            while at < n and x[at] != y[at]:
                at += 1
            runs += 1
            total += at - start
            if runs <= 40:
                word = start & ~7
                wa = int.from_bytes(x[word:word + 8].ljust(8, b"\0"), "little")
                wb = int.from_bytes(y[word:word + 8].ljust(8, b"\0"), "little")
                print(f"{name} at {va:#x} +{start:#x}..+{at:#x}: {wa:#018x} vs {wb:#018x} (difference {wb - wa:#x})")
    print(f"{runs} runs of differing bytes, {total} bytes")
    return 1 if runs else 0


if __name__ == "__main__":
    sys.exit(main())
