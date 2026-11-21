// Start the sibling Pi executable after queuing its startup pages. The generated
// ranges describe this package's executable. Errors in read-ahead are harmless.
#define _GNU_SOURCE
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/stat.h>
#include <unistd.h>
#include "linux-hot-pages.h"

static void read_ahead(const char *target) {
    int fd = open(target, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return;
    struct stat info;
    if (fstat(fd, &info) == 0 && info.st_size == trained_size) {
        // The kernel may limit a WILLNEED request to the device's read-ahead window.
        const off_t piece = 128 * 1024;
        for (size_t i = 0; i < sizeof(ranges) / sizeof(ranges[0]); i++) {
            const off_t end = ranges[i][0] + ranges[i][1];
            for (off_t at = ranges[i][0]; at < end; at += piece) {
                off_t len = end - at < piece ? end - at : piece;
                if (posix_fadvise(fd, at, len, POSIX_FADV_WILLNEED) != 0)
                    goto done;
            }
        }
    }
done:
    close(fd);
}

int main(int argc, char **argv) {
    char target[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", target, sizeof(target));
    if (length > 0 && (size_t)length < sizeof(target))
        target[length] = '\0';
    else {
        const char *self = (const char *)getauxval(AT_EXECFN);
        if (!self || !realpath(self, target)) {
            perror("pi-start: cannot locate executable");
            return 127;
        }
    }
    char *name = strrchr(target, '/');
    if (!name || (size_t)(name - target) + sizeof("/pi") > sizeof(target))
        return 127;
    strcpy(name, "/pi");
    if (!(argc == 2 && (!strcmp(argv[1], "-v") || !strcmp(argv[1], "--version"))))
        read_ahead(target);
    argv[0] = target;
    execv(target, argv);
    perror("pi-start: cannot start pi");
    return 127;
}
