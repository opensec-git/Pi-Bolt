### Time

|  | Pi-Bolt 0.7.3 | Pi-Bolt 0.7.0 | Bun 1.4.2 | Node 26 |
|---|---:|---:|---:|---:|
| Launch to interactive (TUI) | 46 ms | 43 ms | 101 ms | 347 ms |
| pi --version | 20 ms | 18 ms | 59 ms | 291 ms |
| pi -p: one prompt, 4 tool calls | 64 ms | 64 ms | 122 ms | 412 ms |
| Time per prompt, 4.2M-token session | 344 ms | 354 ms | 497 ms | 613 ms |

### CPU

|  | Pi-Bolt 0.7.3 | Pi-Bolt 0.7.0 | Bun 1.4.2 | Node 26 |
|---|---:|---:|---:|---:|
| Interactive session: 5 prompts | 175 ms | 185 ms | 555 ms | 915 ms |
| pi -p: one prompt | 58 ms | 59 ms | 236 ms | 525 ms |
| pi --version | 14 ms | 14 ms | 98 ms | 312 ms |
| Per prompt, 4.2M-token session | 136 ms | 154 ms | 320 ms | 516 ms |

### Memory and streaming

|  | Pi-Bolt 0.7.3 | Pi-Bolt 0.7.0 | Bun 1.4.2 | Node 26 |
|---|---:|---:|---:|---:|
| Peak memory, interactive session | 100 MB | 105 MB | 208 MB | 230 MB |
| Own memory, tmux session | 25 MB | 24 MB | 68 MB | 152 MB |
| Own memory, end of 4.2M-token session | 39 MB | 41 MB | 91 MB | 2,441 MB |
| Streaming replies (tmux), CPU | 649 ms | 645 ms | 971 ms | 1,024 ms |

### Plugins

|  | launch | hot loop |
|---|---:|---:|
| Pi-Bolt 0.7.3, compiled in | 47 ms | 48 ms |
| Pi-Bolt (JIT on), compiled in | 47 ms | 48 ms |
| Pi-Bolt 0.7.3, run time | 67 ms | 895 ms |
| Pi-Bolt (JIT on), run time | 70 ms | 43 ms |
| Bun 1.4.2, run time | 142 ms | 42 ms |

runs: {'startup': 21, 'headless': 21, 'interactive': 21}, long sessions: 4, tmux rounds: 5
