### Time

|  | Pi-Bolt 0.7.3 | Pi-Bolt 0.7.0 | Bun 1.4.2 | Node 24 |
|---|---:|---:|---:|---:|
| Launch to interactive (TUI) | 47 ms | 48 ms | 143 ms | 334 ms |
| pi --version | 13 ms | 14 ms | 89 ms | 255 ms |
| pi -p: one prompt, 4 tool calls | 83 ms | 87 ms | 193 ms | 454 ms |
| Time per prompt, 4.2M-token session | 565 ms | 576 ms | 777 ms | 961 ms |

### CPU

|  | Pi-Bolt 0.7.3 | Pi-Bolt 0.7.0 | Bun 1.4.2 | Node 24 |
|---|---:|---:|---:|---:|
| Interactive session: 5 prompts | 309 ms | 328 ms | 913 ms | 1,312 ms |
| pi -p: one prompt | 85 ms | 89 ms | 333 ms | 645 ms |
| pi --version | 14 ms | 15 ms | 150 ms | 322 ms |
| Per prompt, 4.2M-token session | 301 ms | 319 ms | 548 ms | 794 ms |

### Memory and streaming

|  | Pi-Bolt 0.7.3 | Pi-Bolt 0.7.0 | Bun 1.4.2 | Node 24 |
|---|---:|---:|---:|---:|
| Peak memory, interactive session | 154 MB | 170 MB | 218 MB | 213 MB |
| Own memory, tmux session | 28 MB | 28 MB | 87 MB | 93 MB |
| Own memory, end of 4.2M-token session | 139 MB | 198 MB | 296 MB | 537 MB |
| Streaming replies (tmux), CPU | 332 ms | 362 ms | 646 ms | 564 ms |

### Plugins

|  | launch | hot loop |
|---|---:|---:|
| Pi-Bolt 0.7.3, compiled in | 52 ms | 55 ms |
| Pi-Bolt (JIT on), compiled in | 50 ms | 68 ms |
| Pi-Bolt 0.7.3, run time | 91 ms | 1,315 ms |
| Pi-Bolt (JIT on), run time | 83 ms | 48 ms |
| Bun 1.4.2, run time | 244 ms | 45 ms |

runs: {'startup': 21, 'headless': 21, 'interactive': 21}, long sessions: 4, tmux rounds: 5
