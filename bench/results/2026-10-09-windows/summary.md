### Time

|  | Pi-Bolt (LTO+CFG AOT) | Pi 1.0.3 on stock Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Launch to interactive (TUI) | 92 ms | 179 ms | 345 ms | 312 ms |
| pi --version | 33 ms | 95 ms | 241 ms | 218 ms |
| pi -p: one prompt, 4 tool calls | 113 ms | 202 ms | 435 ms | 383 ms |
| Time per prompt, 4.2M-token session | 410 ms | 522 ms | 817 ms | 802 ms |
| Launch to interactive (TUI), default theme | 90 ms | 176 ms | 343 ms | 308 ms |
| Process floor (minimal program): launch to interactive | 22 ms | 22 ms | 22 ms | 22 ms |
| Process floor (minimal program): --version | 17 ms | 17 ms | 17 ms | 17 ms |
| Process floor (minimal program): -p | 17 ms | 17 ms | 17 ms | 17 ms |
| Launch to interactive (TUI), over floor | 69 ms | 156 ms | 323 ms | 290 ms |
| pi --version, over floor | 17 ms | 78 ms | 225 ms | 202 ms |
| pi -p: one prompt, 4 tool calls, over floor | 96 ms | 185 ms | 419 ms | 366 ms |

### CPU

|  | Pi-Bolt (LTO+CFG AOT) | Pi 1.0.3 on stock Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Interactive session: 5 prompts | 295 ms | 812 ms | 1,225 ms | 1,139 ms |
| pi -p: one prompt | 92 ms | 335 ms | 582 ms | 554 ms |
| pi --version | 17 ms | 135 ms | 280 ms | 271 ms |
| Per prompt, 4.2M-token session | 186 ms | 316 ms | 691 ms | 647 ms |
| Process floor (minimal program): interactive session | 10 ms | 10 ms | 10 ms | 10 ms |
| Process floor (minimal program): -p | 4.4 ms | 4.4 ms | 4.4 ms | 4.4 ms |
| Process floor (minimal program): --version | 4.3 ms | 4.3 ms | 4.3 ms | 4.3 ms |
| Interactive session: 5 prompts, over floor | 284 ms | 801 ms | 1,215 ms | 1,129 ms |
| pi -p: one prompt, over floor | 88 ms | 331 ms | 578 ms | 549 ms |
| pi --version, over floor | 13 ms | 131 ms | 276 ms | 266 ms |

Process floor: a minimal native program (bench/floor/floor.c: prints a line; in the ConPTY, the marker the TUI is waited for) started the same way, in the same rounds. Over floor: a build's median less the floor's.

### Memory and streaming

|  | Pi-Bolt (LTO+CFG AOT) | Pi 1.0.3 on stock Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Peak working set, interactive session | 95 MB | 164 MB | 166 MB | 196 MB |
| Peak private working set, interactive session | 57 MB | 126 MB | 133 MB | 155 MB |
| Peak private bytes (commit), interactive session | 114 MB | 340 MB | 176 MB | 270 MB |
| System commit rise, interactive session | 119 MB | 340 MB | 172 MB | 268 MB |
| Private working set, ConPTY session | 32 MB | 97 MB | 101 MB | 60 MB |
| Private bytes (commit), ConPTY session | 88 MB | 269 MB | 140 MB | 100 MB |
| Private working set, end of 4.2M-token session | 140 MB | 255 MB | 393 MB | 399 MB |
| Private bytes (commit), end of 4.2M-token session | 211 MB | 474 MB | 438 MB | 446 MB |
| Streaming replies (ConPTY), CPU | 859 ms | 1,011 ms | 1,498 ms | 1,258 ms |
| Frame time p99, streaming replies (ConPTY) | 17 ms | 17 ms | 22 ms | 22 ms |
| Frame time p99, 20,000-char answer | 17 ms | 16 ms | 17 ms | 17 ms |
| Frame time p99, 50 KB file written | 18 ms | 52 ms | 47 ms | 51 ms |
| Longest stall, 50 KB file written | 87 ms | 2,036 ms | 688 ms | 106 ms |
| Frame time p99, 200 KB file written | 18 ms | 118 ms | 80 ms | 64 ms |
| Longest stall, 200 KB file written | 61 ms | 46,688 ms | 51,893 ms | 40,928 ms |
| GC pause p99, 20,000-char answer | 2.2 ms | 5.4 ms | 1.4 ms | 1.6 ms |
| GC pause p99, 50 KB file written | 2.0 ms | 3.1 ms | 1.6 ms | 6.6 ms |
| Longest GC pause | 2.5 ms | 8.5 ms | 3.8 ms | 8.6 ms |

### Plugins

|  | launch | hot loop |
|---|---:|---:|
| Pi-Bolt (LTO+CFG AOT), compiled in | 116 ms | 36 ms |
| Pi-Bolt (JIT on), compiled in | 126 ms | 36 ms |
| Pi-Bolt (LTO+CFG AOT), run time | 147 ms | 750 ms |
| Pi-Bolt (JIT on), run time | 158 ms | 32 ms |
| Pi 1.0.3 on stock Bun 1.4.2, run time | 248 ms | 32 ms |

runs: {'startup': 11, 'headless': 11, 'interactive': 11}, long sessions: 4, ConPTY rounds: 5
