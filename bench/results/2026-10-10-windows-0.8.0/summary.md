### Time

|  | Pi-Bolt 0.8.0 | Pi 1.1.0 on stock Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Launch to interactive (TUI) | 92 ms | 177 ms | 345 ms | 311 ms |
| pi --version | 35 ms | 98 ms | 242 ms | 219 ms |
| pi -p: one prompt, 4 tool calls | 113 ms | 203 ms | 438 ms | 383 ms |
| Time per prompt, 4.2M-token session | 400 ms | 506 ms | 816 ms | 794 ms |
| Launch to interactive (TUI), default theme | 89 ms | 175 ms | 343 ms | 318 ms |
| Process floor (minimal program): launch to interactive | 24 ms | 24 ms | 24 ms | 24 ms |
| Process floor (minimal program): --version | 18 ms | 18 ms | 18 ms | 18 ms |
| Process floor (minimal program): -p | 18 ms | 18 ms | 18 ms | 18 ms |
| Launch to interactive (TUI), over floor | 68 ms | 154 ms | 321 ms | 287 ms |
| pi --version, over floor | 17 ms | 80 ms | 224 ms | 201 ms |
| pi -p: one prompt, 4 tool calls, over floor | 95 ms | 185 ms | 420 ms | 365 ms |

### CPU

|  | Pi-Bolt 0.8.0 | Pi 1.1.0 on stock Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Interactive session: 5 prompts | 291 ms | 805 ms | 1,206 ms | 1,125 ms |
| pi -p: one prompt | 92 ms | 329 ms | 582 ms | 544 ms |
| pi --version | 17 ms | 133 ms | 277 ms | 268 ms |
| Per prompt, 4.2M-token session | 166 ms | 298 ms | 684 ms | 634 ms |
| Process floor (minimal program): interactive session | 9.8 ms | 9.8 ms | 9.8 ms | 9.8 ms |
| Process floor (minimal program): -p | 4.5 ms | 4.5 ms | 4.5 ms | 4.5 ms |
| Process floor (minimal program): --version | 4.3 ms | 4.3 ms | 4.3 ms | 4.3 ms |
| Interactive session: 5 prompts, over floor | 281 ms | 795 ms | 1,196 ms | 1,115 ms |
| pi -p: one prompt, over floor | 88 ms | 324 ms | 577 ms | 540 ms |
| pi --version, over floor | 13 ms | 129 ms | 273 ms | 263 ms |

Process floor: a minimal native program (bench/floor/floor.c: prints a line; in the ConPTY, the marker the TUI is waited for) started the same way, in the same rounds. Over floor: a build's median less the floor's.

### Memory and streaming

|  | Pi-Bolt 0.8.0 | Pi 1.1.0 on stock Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Peak working set, interactive session | 95 MB | 164 MB | 165 MB | 178 MB |
| Peak private working set, interactive session | 56 MB | 126 MB | 132 MB | 138 MB |
| Peak private bytes (commit), interactive session | 113 MB | 345 MB | 176 MB | 197 MB |
| System commit rise, interactive session | 113 MB | 339 MB | 170 MB | 193 MB |
| Private working set, ConPTY session | 32 MB | 97 MB | 105 MB | 60 MB |
| Private bytes (commit), ConPTY session | 89 MB | 272 MB | 156 MB | 102 MB |
| Private working set, end of 4.2M-token session | 159 MB | 248 MB | 391 MB | 413 MB |
| Private bytes (commit), end of 4.2M-token session | 245 MB | 465 MB | 437 MB | 467 MB |
| Streaming replies (ConPTY), CPU | 840 ms | 982 ms | 1,468 ms | 1,285 ms |
| Frame time p99, streaming replies (ConPTY) | 17 ms | 17 ms | 20 ms | 19 ms |
| Frame time p99, 20,000-char answer | 16 ms | 17 ms | 17 ms | 17 ms |
| Frame time p99, 50 KB file written | 18 ms | 59 ms | 75 ms | 64 ms |
| Longest stall, 50 KB file written | 107 ms | 3,612 ms | 2,727 ms | 858 ms |
| Frame time p99, 200 KB file written | 18 ms | 154 ms | 302 ms | 71 ms |
| Longest stall, 200 KB file written | 63 ms | 43,934 ms | 57,962 ms | 46,595 ms |
| GC pause p99, 20,000-char answer | 2.9 ms | 4.3 ms | 1.6 ms | 1.6 ms |
| GC pause p99, 50 KB file written | 2.3 ms | 3.0 ms | 1.5 ms | 6.9 ms |
| Longest GC pause | 6.3 ms | 11 ms | 3.6 ms | 8.4 ms |

### Plugins

|  | launch | hot loop |
|---|---:|---:|
| Pi-Bolt 0.8.0, compiled in | 114 ms | 41 ms |
| Pi-Bolt (JIT on), compiled in | 125 ms | 36 ms |
| Pi-Bolt 0.8.0, run time | 146 ms | 756 ms |
| Pi-Bolt (JIT on), run time | 161 ms | 32 ms |
| Pi 1.1.0 on stock Bun 1.4.2, run time | 249 ms | 32 ms |

runs: {'startup': 11, 'headless': 11, 'interactive': 11}, long sessions: 4, ConPTY rounds: 5
