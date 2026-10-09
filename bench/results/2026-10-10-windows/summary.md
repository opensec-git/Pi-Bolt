### Time

|  | Pi-Bolt (LTO+CFG AOT) | Pi 1.0.3 on stock Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Launch to interactive (TUI) | 91 ms | 178 ms | 346 ms | 312 ms |
| pi --version | 35 ms | 100 ms | 262 ms | 233 ms |
| pi -p: one prompt, 4 tool calls | 124 ms | 222 ms | 468 ms | 415 ms |
| Time per prompt, 4.2M-token session | 413 ms | 514 ms | 821 ms | 804 ms |
| Launch to interactive (TUI), default theme | 89 ms | 175 ms | 344 ms | 310 ms |
| Process floor (minimal program): launch to interactive | 24 ms | 24 ms | 24 ms | 24 ms |
| Process floor (minimal program): --version | 18 ms | 18 ms | 18 ms | 18 ms |
| Process floor (minimal program): -p | 19 ms | 19 ms | 19 ms | 19 ms |
| Launch to interactive (TUI), over floor | 67 ms | 154 ms | 322 ms | 288 ms |
| pi --version, over floor | 17 ms | 82 ms | 244 ms | 215 ms |
| pi -p: one prompt, 4 tool calls, over floor | 105 ms | 203 ms | 449 ms | 396 ms |

### CPU

|  | Pi-Bolt (LTO+CFG AOT) | Pi 1.0.3 on stock Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Interactive session: 5 prompts | 296 ms | 816 ms | 1,231 ms | 1,189 ms |
| pi -p: one prompt | 101 ms | 355 ms | 627 ms | 595 ms |
| pi --version | 18 ms | 141 ms | 303 ms | 290 ms |
| Per prompt, 4.2M-token session | 188 ms | 313 ms | 694 ms | 648 ms |
| Process floor (minimal program): interactive session | 10.0 ms | 10.0 ms | 10.0 ms | 10.0 ms |
| Process floor (minimal program): -p | 4.5 ms | 4.5 ms | 4.5 ms | 4.5 ms |
| Process floor (minimal program): --version | 4.5 ms | 4.5 ms | 4.5 ms | 4.5 ms |
| Interactive session: 5 prompts, over floor | 286 ms | 806 ms | 1,221 ms | 1,179 ms |
| pi -p: one prompt, over floor | 96 ms | 350 ms | 623 ms | 591 ms |
| pi --version, over floor | 14 ms | 136 ms | 298 ms | 285 ms |

Process floor: a minimal native program (bench/floor/floor.c: prints a line; in the ConPTY, the marker the TUI is waited for) started the same way, in the same rounds. Over floor: a build's median less the floor's.

### Memory and streaming

|  | Pi-Bolt (LTO+CFG AOT) | Pi 1.0.3 on stock Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Peak working set, interactive session | 96 MB | 164 MB | 167 MB | 195 MB |
| Peak private working set, interactive session | 57 MB | 126 MB | 134 MB | 153 MB |
| Peak private bytes (commit), interactive session | 114 MB | 344 MB | 178 MB | 270 MB |
| System commit rise, interactive session | 117 MB | 344 MB | 171 MB | 269 MB |
| Private working set, ConPTY session | 33 MB | 98 MB | 99 MB | 178 MB |
| Private bytes (commit), ConPTY session | 88 MB | 273 MB | 139 MB | 244 MB |
| Private working set, end of 4.2M-token session | 148 MB | 230 MB | 376 MB | 423 MB |
| Private bytes (commit), end of 4.2M-token session | 217 MB | 437 MB | 421 MB | 471 MB |
| Streaming replies (ConPTY), CPU | 959 ms | 1,116 ms | 1,613 ms | 1,420 ms |
| Frame time p99, streaming replies (ConPTY) | 17 ms | 17 ms | 20 ms | 20 ms |
| Frame time p99, 20,000-char answer | 17 ms | 16 ms | 18 ms | 17 ms |
| Frame time p99, 50 KB file written | 18 ms | 47 ms | 35 ms | 61 ms |
| Longest stall, 50 KB file written | 116 ms | 1,946 ms | 1,303 ms | 915 ms |
| Frame time p99, 200 KB file written | 18 ms | 124 ms | 46 ms | 75 ms |
| Longest stall, 200 KB file written | 62 ms | 26,515 ms | 54,249 ms | 43,644 ms |
| GC pause p99, 20,000-char answer | 2.2 ms | 4.2 ms | 1.1 ms | 1.6 ms |
| GC pause p99, 50 KB file written | 2.6 ms | 3.3 ms | 1.6 ms | 6.8 ms |
| Longest GC pause | 2.9 ms | 29 ms | 3.8 ms | 9.2 ms |

### Plugins

|  | launch | hot loop |
|---|---:|---:|
| Pi-Bolt (LTO+CFG AOT), compiled in | 116 ms | 37 ms |
| Pi-Bolt (JIT on), compiled in | 130 ms | 37 ms |
| Pi-Bolt (LTO+CFG AOT), run time | 146 ms | 758 ms |
| Pi-Bolt (JIT on), run time | 158 ms | 32 ms |
| Pi 1.0.3 on stock Bun 1.4.2, run time | 251 ms | 32 ms |

runs: {'startup': 11, 'headless': 11, 'interactive': 11}, long sessions: 4, ConPTY rounds: 5
