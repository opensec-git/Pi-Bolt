#!/usr/bin/env python3
"""exit_smaps.py OUT COMMAND...: runs COMMAND under strace with its exit_group delayed, and copies /proc/PID/smaps and status
while it waits: what the process has mapped and touched when it is done (VmHWM: its peak)."""
import os, subprocess, sys, time

out, cmd = sys.argv[1], sys.argv[2:]
st = subprocess.Popen(["strace", "-qq", "-e", "trace=exit_group", "-e", "inject=exit_group:delay_enter=3000000", "-o", "/dev/null", *cmd],
                      stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(1.5)
kids = []
for task in os.listdir(f"/proc/{st.pid}/task"):
    with open(f"/proc/{st.pid}/task/{task}/children") as f:
        kids += f.read().split()
pid = kids[0]
with open(f"/proc/{pid}/smaps") as f, open(out, "w") as o:
    o.write(f.read())
with open(f"/proc/{pid}/status") as f:
    print("".join(l for l in f if l.startswith(("VmHWM", "VmRSS", "RssAnon", "RssFile", "RssShmem"))), end="")
st.wait()
