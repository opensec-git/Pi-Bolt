// How the programs Pi starts on macOS get the system's own process setup (docs/ARCHITECTURE.md, "The macOS ARM64 port"). pi-bin
// runs at its linked address,
// and macOS passes that on to every process it starts, and to theirs. So the launcher (darwin-launcher.c) forks a helper
// before it starts pi-bin (darwin-spawn-helper.c): a process started the usual way, which is not pi-bin's descendant. pi-bin (Bun's
// posix_spawn.rs) starts pi-spawn (darwin-spawn-proxy.c) in a program's place; pi-spawn passes what it was given to the
// helper, which starts the program, and then stands in for the program until it exits.
//
// pi-spawn -> helper, a datagram on the helper's socket, with SCM_RIGHTS: a stream socket, which the rest goes over; the
// directory to start the program in; then the files the program gets (`targets`: as which descriptor each).
// pi-spawn -> helper's worker, on the stream: struct pibolt_spawn_params, then the path, the arguments and the environment.
// worker -> pi-spawn, on the stream: struct pibolt_spawn_reply, `started` (or `failed`, `unavailable`) and then `exited`.
// pi-spawn -> pi-bin, on the status pipe: the reply `started` or `failed`; nothing if pi-spawn started the program itself.
#pragma once
#include <stdint.h>

#define PIBOLT_SPAWN_MAGIC 0x50425350u // "PBSP"
#define PIBOLT_SPAWN_VERSION 1u
#define PIBOLT_SPAWN_MAX_FDS 126
#define PIBOLT_SPAWN_RLIMITS 9 // RLIM_NLIMITS

// Where the program goes, as the process group and session pi-spawn was started in say.
enum pibolt_spawn_mode {
    PIBOLT_SPAWN_SHARE_GROUP = 0, // pi-spawn is in pi-bin's group: the program joins it, as it would have
    PIBOLT_SPAWN_NEW_GROUP = 1, // pi-spawn leads a group of its own: the program leads one
    PIBOLT_SPAWN_NEW_SESSION = 2, // pi-spawn leads a session (detached): the program leads one
};

struct pibolt_spawn_request {
    uint32_t magic;
    uint32_t version;
    int32_t nfds;
    int32_t targets[PIBOLT_SPAWN_MAX_FDS];
};

struct pibolt_spawn_params {
    uint32_t mode;
    int32_t pgid;
    uint32_t ignored_signals; // bit n: signal n is ignored
    uint32_t blocked_signals; // bit n: signal n is blocked
    uint32_t umask;
    uint32_t path_len; // with the NUL
    uint32_t argc;
    uint32_t argv_len; // the arguments, each with its NUL
    uint32_t envc;
    uint32_t envp_len;
    uint64_t rlimits[PIBOLT_SPAWN_RLIMITS][2]; // soft, hard
};

enum pibolt_spawn_reply_kind {
    PIBOLT_SPAWN_STARTED = 1, // value: the program's pid
    PIBOLT_SPAWN_FAILED = 2, // value: errno, from posix_spawn
    PIBOLT_SPAWN_UNAVAILABLE = 3, // the helper cannot do it: pi-spawn starts the program itself
    PIBOLT_SPAWN_EXITED = 4, // value: the wait status
};

struct pibolt_spawn_reply {
    int32_t kind;
    int32_t value;
};

// For pi-bin, from the launcher: BUN_INTERNAL_SPAWN_HELPER=<pid of pi-bin>:<descriptor of the helper's socket>:<path of pi-spawn>.
#define PIBOLT_SPAWN_HELPER_ENV "BUN_INTERNAL_SPAWN_HELPER"
// The lowest descriptor the launcher gives the socket in pi-bin, out of the way of the descriptors programs are given.
#define PIBOLT_SPAWN_HELPER_MIN_FD 64

// darwin-spawn-helper.c: forks the helper; returns the descriptor of the socket pi-bin keeps, or -1.
int pibolt_spawn_helper_start(void);
