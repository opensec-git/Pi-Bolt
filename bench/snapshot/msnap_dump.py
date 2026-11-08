#!/usr/bin/env python3
"""Lists the records of a .msnap file: chunk, children, externals, variables set. Usage: msnap_dump.py FILE [--grep NAME]"""
import struct, sys
d = open(sys.argv[1], 'rb').read(); g = sys.argv[sys.argv.index('--grep') + 1] if '--grep' in sys.argv else None
o = 0
def u8():
    global o; o += 1; return d[o - 1]
def u32():
    global o; o += 4; return struct.unpack_from('<I', d, o - 4)[0]
def u64():
    global o; o += 8; return struct.unpack_from('<Q', d, o - 8)[0]
def st():
    global o
    h = u32(); n = h >> 1
    if h & 1:
        s = d[o:o + 2 * n].decode('utf-16-le'); o += 2 * n
    else:
        s = d[o:o + n].decode('latin-1'); o += n
    return s
assert u64() == 0x31504e534d424950
n = u32(); recs = [(u64(), u8(), u32()) for _ in range(n)]
names = ["End","NewObject","NewArray","NewFunction","NewScope","NewRegExp","NewMap","NewSet","NewSymbol","NewBound","NewBigInt","Define","DefineAccessor","SetIndex","SetLength","SetScope","MapSet","SetAdd","SetLastIndex","PreventExtensions","SetVariable","Result"]
def val():
    t = u8()
    if t in (0,1,2,3,11): return ['u','n','T','F',None,None,None,None,None,None,None,'empty'][t]
    if t == 4: return u32()
    if t == 5: return u64()
    if t == 6: return repr(st()[:20])
    if t == 7: return f"#{u32()}"
    if t == 8: a = u32(); b = u32(); return f"#{a}.{b}"
    if t == 9: return f"static:{u64():x}"
    if t == 10: return f"ext{u32()}"
    raise Exception(f"tag {t} at {o}")
def key():
    k = u8()
    if k == 0: return st()
    if k == 2: return f"atom:{u64():x}"
    return val()
for i, (k, kind, size) in enumerate(recs):
    start = o
    url = st(); a = u32(); b = u32()
    kids = [u64() for _ in range(u32())]
    unset = [st() for _ in range(u32())]
    exts = []
    for _ in range(u32()):
        root = u8(); rn = st(); steps = [(u8(), st()) for _ in range(u32())]
        exts.append((root, rn, steps))
    vars_ = []; ops = 0
    while True:
        op = u8()
        if op == 0: break
        ops += 1
        if op == 1: u32(); val()
        elif op == 2: u32(); u32()
        elif op == 3: u32(); u8(); u64(); val()
        elif op == 4: u32(); u64(); val()
        elif op == 5:
            u32()
            if u8(): u64()
            else: st(); u32(); u32(); st(); u8(); u8()
        elif op in (6, 7): u32()
        elif op == 8: u32(); u8(); st()
        elif op == 9: u32(); val(); val(); [val() for _ in range(u32())]
        elif op == 10: u32(); st()
        elif op == 11: val(); key(); val(); u32()
        elif op == 12: val(); key(); val(); val(); u32()
        elif op == 13: val(); u32(); val()
        elif op == 14: val(); u32()
        elif op == 15: val(); u32(); val()
        elif op == 16: val(); val(); val()
        elif op in (17, 18): val(); val()
        elif op == 19: val()
        elif op == 20: vars_.append(key()); val()
        elif op == 21: val()
        elif op == 22: val(); val(); val()
        else: raise Exception(f"op {op}")
    assert o - start == size, (o - start, size)
    line = f"{i:4d} kind={kind} ops={ops} kids={len(kids)} unset={unset} ext={len(exts)} {url.rsplit('/',1)[-1]}:{a} vars={vars_[:12]}"
    if not g or g in line or any(g in str(e) for e in exts):
        print(line)
        if g: print("     ext:", exts[:10])
