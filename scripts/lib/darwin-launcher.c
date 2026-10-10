// The `pi` of a macOS build: starts `pi-bin`, the Pi-Bolt executable beside it, in this same process, at its linked address.
//
// An executable with a static heap must run where it was linked to be (docs/ARCHITECTURE.md, "The macOS ARM64 port"). Started
// directly, it starts again itself, once dyld has loaded it and its libraries; from here it is started that way at once, which
// saves dyld's work on the first start (about a millisecond and a half). Without this launcher it works all the same.
//
// macOS passes how pi-bin was started on to everything it starts. So first it forks the helper that starts pi-bin's programs
// with the system's own process setup (darwin-spawn.h), and tells pi-bin where it is.
// Built by scripts/build-pi.sh: clang -O2 -mmacosx-version-min=13.0 darwin-launcher.c darwin-spawn-helper.c -o pi
#include "darwin-spawn.h"

#include <errno.h>
#include <fcntl.h>
#include <libgen.h>
#include <limits.h>
#include <mach-o/dyld.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

extern char** environ;

// A start with pi-bin not in memory (after a restart, an update, or when memory was short) reads some 50 MB of it, page by
// page as each is first touched: 180 ms rather than 30. pi-bin.hot, beside it (scripts/lib/darwin_hot_pages.py, at build
// time), lists the parts of pi-bin that a start reads; asking for them all at once, before pi-bin starts, has the disk read
// them while dyld and the engine start: 65 ms. Pages already in memory cost nothing. Only advice: a list that is missing,
// malformed or for another pi-bin (its first line is pi-bin's size) changes nothing.
//     pi-bin.hot:  <size of pi-bin>\n  then  <offset> <length>\n  for each run of pages, at most 4096 of them
static void readAhead(const char* target, const char* directory)
{
    char path[PATH_MAX];
    if (snprintf(path, sizeof(path), "%s/pi-bin.hot", directory) >= (int)sizeof(path))
        return;
    FILE* list = fopen(path, "re");
    if (!list)
        return;
    int binary = open(target, O_RDONLY | O_CLOEXEC);
    struct stat info;
    long long size, offset, length;
    if (binary >= 0 && !fstat(binary, &info) && fscanf(list, "%lld", &size) == 1 && size == (long long)info.st_size) {
        for (int runs = 0; runs < 4096 && fscanf(list, "%lld %lld", &offset, &length) == 2; runs++) {
            if (offset < 0 || length <= 0 || offset >= size)
                break;
            long long count = length < size - offset ? length : size - offset;
            struct radvisory advice = { (off_t)offset, (int)(count < (1 << 30) ? count : 1 << 30) };
            fcntl(binary, F_RDADVISE, &advice);
        }
    }
    if (binary >= 0)
        close(binary);
    fclose(list);
}

int main(int argc, char** argv)
{
    char self[PATH_MAX];
    uint32_t size = sizeof(self);
    char resolved[PATH_MAX];
    if (_NSGetExecutablePath(self, &size) || !realpath(self, resolved)) {
        fprintf(stderr, "pi: cannot tell where this executable is\n");
        return 127;
    }
    char directory[PATH_MAX];
    strlcpy(directory, dirname(resolved), sizeof(directory));
    char target[PATH_MAX];
    if (snprintf(target, sizeof(target), "%s/pi-bin", directory) >= (int)sizeof(target)) {
        fprintf(stderr, "pi: the path of this executable is too long\n");
        return 127;
    }

    // (Not for `pi --version`, which pi-bin answers without starting anything: the fork would be 3% of its time.)
    int wanted = !(argc == 2 && (!strcmp(argv[1], "--version") || !strcmp(argv[1], "-v")));
    if (wanted)
        readAhead(target, directory);
    char proxy[PATH_MAX];
    int helper = -1;
    if (wanted && snprintf(proxy, sizeof(proxy), "%s/pi-spawn", directory) < (int)sizeof(proxy) && !access(proxy, X_OK))
        helper = pibolt_spawn_helper_start();
    if (helper >= 0) {
        char value[sizeof(proxy) + 32];
        snprintf(value, sizeof(value), "%d:%d:%s", (int)getpid(), helper, proxy);
        setenv(PIBOLT_SPAWN_HELPER_ENV, value, 1);
    }

    // What pi-bin's own start at its linked address looks for (c-bindings.cpp in Bun): it is started that way already.
    setenv("BUN_INTERNAL_STATIC_HEAP_AT_LINKED_ADDRESS", "1", 1);
    posix_spawnattr_t attributes;
    if (!posix_spawnattr_init(&attributes)) {
        const short linkedAddress = 0x100; // (a private posix_spawn flag: no slide for the main executable)
        posix_spawnattr_setflags(&attributes, linkedAddress | POSIX_SPAWN_SETEXEC);
        posix_spawn(NULL, target, NULL, &attributes, argv, environ);
    }
    // Not started that way: as it is (it then starts again itself, if it can).
    unsetenv("BUN_INTERNAL_STATIC_HEAP_AT_LINKED_ADDRESS");
    execv(target, argv);
    fprintf(stderr, "pi: cannot start %s: %s\n", target, strerror(errno));
    return 127;
}
