// LD_PRELOAD=dumpmaps.so: copies /proc/self/smaps to $DUMPMAPS_OUT when the process exits (through exit()).
#include <fcntl.h>
#include <stdlib.h>
#include <unistd.h>

__attribute__((destructor)) static void dump(void)
{
    const char* out = getenv("DUMPMAPS_OUT");
    if (!out)
        return;
    int in = open("/proc/self/smaps", O_RDONLY);
    int fd = open(out, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    char buf[65536];
    ssize_t n;
    while (in >= 0 && fd >= 0 && (n = read(in, buf, sizeof buf)) > 0)
        write(fd, buf, n);
}
