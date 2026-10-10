### Time

|  | Pi-Bolt (LTO+CFG AOT) | Pi 1.0.3 on stock Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Launch to interactive (TUI) | 100 ms | 165 ms | 344 ms | 311 ms |
| pi --version | 39 ms | 92 ms | 237 ms | 213 ms |
| pi -p: one prompt, 4 tool calls | 121 ms | 195 ms | 422 ms | 377 ms |
| Time per prompt, 4.2M-token session | 533 ms | 557 ms | 959 ms | 908 ms |

### CPU

|  | Pi-Bolt (LTO+CFG AOT) | Pi 1.0.3 on stock Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Interactive session: 5 prompts | 317 ms | 791 ms | 1,209 ms | 1,134 ms |
| pi -p: one prompt | 100 ms | 319 ms | 572 ms | 546 ms |
| pi --version | 23 ms | 132 ms | 275 ms | 266 ms |
| Per prompt, 4.2M-token session | 253 ms | 332 ms | 828 ms | 749 ms |

### Memory and streaming

|  | Pi-Bolt (LTO+CFG AOT) | Pi 1.0.3 on stock Bun 1.4.2 | Node 22 | Node 24 |
|---|---:|---:|---:|---:|
| Peak working set, interactive session | 110 MB | 164 MB | 166 MB | 194 MB |
| Peak private working set, interactive session | 60 MB | 127 MB | 133 MB | 154 MB |
| Peak private bytes (commit), interactive session | 247 MB | 344 MB | 176 MB | 269 MB |
| System commit rise, interactive session | 247 MB | 343 MB | 174 MB | 265 MB |
| Private working set, ConPTY session | 32 MB | 94 MB | 98 MB | 181 MB |
| Private bytes (commit), ConPTY session | 214 MB | 268 MB | 137 MB | 245 MB |
| Private working set, end of 4.2M-token session | 157 MB | 249 MB | 387 MB | 405 MB |
| Private bytes (commit), end of 4.2M-token session | 370 MB | 469 MB | 433 MB | 452 MB |
| Streaming replies (ConPTY), CPU | 810 ms | 931 ms | 1,436 ms | 1,198 ms |
| Frame time p99, streaming replies (ConPTY) | 17 ms | 17 ms | 20 ms | 21 ms |
| Frame time p99, 20,000-char answer | 17 ms | 17 ms | 16 ms | 17 ms |
| Frame time p99, 50 KB file written | 18 ms | 50 ms | 36 ms | 98 ms |
| Longest stall, 50 KB file written | 164 ms | 1,408 ms | 2,606 ms | 3,519 ms |
| Frame time p99, 200 KB file written | 18 ms | 220 ms | 117 ms | 771 ms |
| Longest stall, 200 KB file written | 133 ms | 57,658 ms | 61,424 ms | 55,265 ms |
| GC pause p99, 20,000-char answer | 3.5 ms | 4.1 ms | 1.2 ms | 2.2 ms |
| GC pause p99, 50 KB file written | 1.9 ms | 3.2 ms | 1.7 ms | 6.7 ms |
| Longest GC pause | 8.3 ms | 8.7 ms | 3.4 ms | 8.8 ms |

### Plugins

|  | launch | hot loop |
|---|---:|---:|
| Pi-Bolt (LTO+CFG AOT), run time | 147 ms | 788 ms |
| Pi 1.0.3 on stock Bun 1.4.2, run time | 242 ms | 32 ms |

runs: {'startup': 11, 'headless': 11, 'interactive': 11}, long sessions: 4, ConPTY rounds: 5
