#!/usr/bin/env python3
"""Windows: where a process's private bytes (commit charge) are. Walks its address space (VirtualQueryEx) and groups committed
memory by allocation: what is private (heaps, stacks, reservations the program commits as it goes), what of its image it has made
its own (written, copy-on-write), and mapped files. Prints the largest allocations.

  python bench/win_memory_map.py PID [--top 25]
"""

import argparse
import ctypes
import sys
from collections import defaultdict
from ctypes import wintypes as w

k32 = ctypes.WinDLL("kernel32", use_last_error=True)
psapi = ctypes.WinDLL("psapi", use_last_error=True)

MEM_COMMIT, MEM_RESERVE, MEM_FREE = 0x1000, 0x2000, 0x10000
MEM_PRIVATE, MEM_MAPPED, MEM_IMAGE = 0x20000, 0x40000, 0x1000000
PAGE_GUARD = 0x100
WRITABLE = {0x04: "RW", 0x08: "WC", 0x40: "RWX", 0x80: "WCX"}


class MBI(ctypes.Structure):
    _fields_ = [("BaseAddress", ctypes.c_void_p), ("AllocationBase", ctypes.c_void_p), ("AllocationProtect", w.DWORD),
                ("PartitionId", w.WORD), ("RegionSize", ctypes.c_size_t), ("State", w.DWORD), ("Protect", w.DWORD), ("Type", w.DWORD)]


k32.OpenProcess.restype = w.HANDLE
k32.VirtualQueryEx.argtypes = [w.HANDLE, ctypes.c_void_p, ctypes.POINTER(MBI), ctypes.c_size_t]
k32.VirtualQueryEx.restype = ctypes.c_size_t
psapi.GetMappedFileNameW.argtypes = [w.HANDLE, ctypes.c_void_p, w.LPWSTR, w.DWORD]


def regions(handle):
    address = 0
    mbi = MBI()
    while k32.VirtualQueryEx(handle, ctypes.c_void_p(address), ctypes.byref(mbi), ctypes.sizeof(mbi)):
        yield MBI.from_buffer_copy(mbi)
        address = (mbi.BaseAddress or 0) + mbi.RegionSize
        if address >= 1 << 47:
            break


def mapped_name(handle, address):
    buf = ctypes.create_unicode_buffer(520)
    if psapi.GetMappedFileNameW(handle, ctypes.c_void_p(address), buf, 520):
        return buf.value.rsplit("\\", 1)[-1]
    return "?"


def snapshot(pid, top):
    handle = k32.OpenProcess(0x0400 | 0x0010, False, pid)  # PROCESS_QUERY_INFORMATION | PROCESS_VM_READ
    if not handle:
        raise SystemExit(f"cannot open process {pid}: {ctypes.get_last_error()}")
    by_allocation = defaultdict(lambda: {"committed": 0, "reserved": 0, "type": 0, "protect": set()})
    totals = defaultdict(int)
    for r in regions(handle):
        if r.State == MEM_FREE:
            continue
        a = by_allocation[r.AllocationBase or 0]
        a["type"] = r.Type
        if r.State == MEM_COMMIT:
            a["committed"] += r.RegionSize
            a["protect"].add(r.Protect & 0xFF)
            if r.Type == MEM_PRIVATE:
                totals["private committed"] += r.RegionSize
            elif r.Type == MEM_IMAGE and (r.Protect & 0xFF) in WRITABLE:
                totals["image, writable (copy-on-write or written)"] += r.RegionSize
            elif r.Type == MEM_MAPPED and (r.Protect & 0xFF) in WRITABLE:
                totals["mapped, writable"] += r.RegionSize
        else:
            a["reserved"] += r.RegionSize
    print(f"process {pid}")
    for k, v in totals.items():
        print(f"  {k:45} {v / 2**20:9.1f} MB")
    rows = sorted(((base, a) for base, a in by_allocation.items() if a["type"] == MEM_PRIVATE), key=lambda x: -x[1]["committed"])
    print(f"\n  largest private allocations (committed / reserved):")
    for base, a in rows[:top]:
        prot = ",".join(sorted(WRITABLE.get(p, hex(p)) for p in a["protect"]))
        print(f"  {base:#016x}  {a['committed'] / 2**20:8.2f} MB / {a['reserved'] / 2**20:9.1f} MB  {prot}")
    images = sorted(((base, a) for base, a in by_allocation.items() if a["type"] == MEM_IMAGE), key=lambda x: -x[1]["committed"])
    print(f"\n  images (committed):")
    for base, a in images[:8]:
        print(f"  {base:#016x}  {a['committed'] / 2**20:8.2f} MB  {mapped_name(handle, base)}")
    k32.CloseHandle(handle)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pid", type=int)
    ap.add_argument("--top", type=int, default=25)
    a = ap.parse_args()
    snapshot(a.pid, a.top)


if __name__ == "__main__":
    main()
