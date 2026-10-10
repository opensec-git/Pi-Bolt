// pi-spawn: what pi-bin starts on macOS in the place of a program, so that the program gets the system's own process setup
// (darwin-spawn.h).
//
//     pi-spawn <helper's socket> <status pipe> <path> <argv[0]> [arguments...]
//
// pi-bin started it as it would have started the program: its files, directory, environment, signals, process group or
// session are the program's. It hands them to the helper, which starts the program; pi-bin is told the program's pid on the
// status pipe, and signals that pid. pi-spawn ignores signals, waits, and exits as the program did. When the helper cannot
// start the program, pi-spawn starts it in its own place, as pi-bin would have.
// Built by scripts/build-pi.sh: clang -O2 -mmacosx-version-min=13.0 darwin-spawn-proxy.c -o pi-spawn
#include "darwin-spawn.h"

#include <errno.h>
#include <fcntl.h>
#include <libproc.h>
#include <signal.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/event.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

extern char** environ;

// The signals that only come from outside; pi-spawn ignores them while the program runs.
static const int asynchronous[] = { SIGHUP, SIGINT, SIGQUIT, SIGPIPE, SIGALRM, SIGTERM, SIGURG, SIGTSTP, SIGCONT, SIGTTIN,
    SIGTTOU, SIGIO, SIGXCPU, SIGXFSZ, SIGVTALRM, SIGPROF, SIGWINCH, SIGINFO, SIGUSR1, SIGUSR2 };
#define ASYNCHRONOUS_COUNT (sizeof(asynchronous) / sizeof(asynchronous[0]))
static struct sigaction saved[ASYNCHRONOUS_COUNT];

static int writeFully(int fd, const void* buffer, size_t size)
{
    const char* at = buffer;
    while (size) {
        ssize_t wrote = write(fd, at, size);
        if (wrote < 0 && errno == EINTR)
            continue;
        if (wrote <= 0)
            return -1;
        at += wrote;
        size -= (size_t)wrote;
    }
    return 0;
}

static int readFully(int fd, void* buffer, size_t size)
{
    char* at = buffer;
    while (size) {
        ssize_t got = read(fd, at, size);
        if (got < 0 && errno == EINTR)
            continue;
        if (got <= 0)
            return -1;
        at += got;
        size -= (size_t)got;
    }
    return 0;
}

static void tell(int status, int kind, int value)
{
    struct pibolt_spawn_reply message = { kind, value };
    writeFully(status, &message, sizeof(message));
}

// The program in pi-spawn's place, as pi-bin would have started it.
static void execute(int helper, int status, const char* path, char** argv)
{
    for (size_t i = 0; i < ASYNCHRONOUS_COUNT; i++)
        sigaction(asynchronous[i], &saved[i], NULL);
    close(helper);
    fcntl(status, F_SETFD, FD_CLOEXEC);
    execve(path, argv, environ);
    int error = errno;
    tell(status, PIBOLT_SPAWN_FAILED, error);
    _exit(127);
}

static size_t stringsSize(char** strings, uint32_t* count)
{
    size_t size = 0;
    *count = 0;
    for (; strings[*count]; (*count)++)
        size += strlen(strings[*count]) + 1;
    return size;
}

static int writeStrings(int stream, char** strings)
{
    for (; *strings; strings++) {
        if (writeFully(stream, *strings, strlen(*strings) + 1))
            return -1;
    }
    return 0;
}

// Waits until the stream has the worker's first answer, or the helper has exited, or 30 seconds have passed. Only a wait:
// after it the stream is read as before, so a worker that has the request is always heard (it would otherwise start the
// program a second time).
static void waitForAnswer(int helper, int stream)
{
    int queue = kqueue();
    if (queue < 0)
        return;
    struct kevent changes[2];
    int count = 0;
    EV_SET(&changes[count++], stream, EVFILT_READ, EV_ADD, 0, 0, NULL);
    // (The helper's pid, as the peer of its socket: the helper takes the socket over at its start. Not when the peer is
    // still the launcher, which is pi-bin, pi-spawn's parent.)
    pid_t helperPid = 0;
    socklen_t size = sizeof(helperPid);
    if (!getsockopt(helper, SOL_LOCAL, LOCAL_PEERPID, &helperPid, &size) && helperPid > 0 && helperPid != getppid())
        EV_SET(&changes[count++], helperPid, EVFILT_PROC, EV_ADD, NOTE_EXIT, 0, NULL);
    if (!kevent(queue, changes, count, NULL, 0, NULL)) {
        struct timespec timeout = { 30, 0 };
        struct kevent event;
        while (kevent(queue, NULL, 0, &event, 1, &timeout) < 0 && errno == EINTR) { }
    }
    close(queue);
}

// Asks the helper to start the program. 0: it did (or posix_spawn failed, which pi-bin has been told); -1: it cannot, and
// nothing was started.
static int request(int helper, int status, const char* path, char** argv, uint32_t ignored, uint32_t blocked, int* stream)
{
    struct pibolt_spawn_request message = { .magic = PIBOLT_SPAWN_MAGIC, .version = PIBOLT_SPAWN_VERSION };
    int fds[PIBOLT_SPAWN_MAX_FDS + 2];
    struct proc_fdinfo open_[PIBOLT_SPAWN_MAX_FDS + 8];
    int listed = proc_pidinfo(getpid(), PROC_PIDLISTFDS, 0, open_, sizeof(open_));
    if (listed <= 0 || listed >= (int)sizeof(open_))
        return -1;
    for (int i = 0; i < listed / (int)sizeof(open_[0]); i++) {
        int fd = open_[i].proc_fd;
        if (fd == helper || fd == status || open_[i].proc_fdtype == PROX_FDTYPE_KQUEUE)
            continue;
        if (message.nfds == PIBOLT_SPAWN_MAX_FDS)
            return -1;
        message.targets[message.nfds] = fd;
        fds[2 + message.nfds++] = fd;
    }

    int pair[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair))
        return -1;
    // Room for the whole request (its arguments and environment fit in ARG_MAX, 1 MB, or pi-spawn would not have started):
    // writing it never waits for the worker, which may never come (below).
    int room = 2 << 20;
    setsockopt(pair[0], SOL_SOCKET, SO_SNDBUF, &room, sizeof(room));
    setsockopt(pair[1], SOL_SOCKET, SO_RCVBUF, &room, sizeof(room));
    int directory = open(".", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (directory < 0) {
        close(pair[0]);
        close(pair[1]);
        return -1;
    }
    fds[0] = pair[1];
    fds[1] = directory;

    union {
        struct cmsghdr header;
        char space[CMSG_SPACE((PIBOLT_SPAWN_MAX_FDS + 2) * sizeof(int))];
    } control;
    memset(&control, 0, sizeof(control));
    size_t descriptors = (size_t)(message.nfds + 2) * sizeof(int);
    struct iovec vector = { &message, offsetof(struct pibolt_spawn_request, targets) + (size_t)message.nfds * sizeof(int32_t) };
    struct msghdr header = { .msg_iov = &vector, .msg_iovlen = 1, .msg_control = &control, .msg_controllen = (socklen_t)CMSG_SPACE(descriptors) };
    struct cmsghdr* rights = CMSG_FIRSTHDR(&header);
    rights->cmsg_level = SOL_SOCKET;
    rights->cmsg_type = SCM_RIGHTS;
    rights->cmsg_len = (socklen_t)CMSG_LEN(descriptors);
    memcpy(CMSG_DATA(rights), fds, descriptors);
    ssize_t sent;
    // A datagram that does not fit in the helper's socket is refused at once (ENOBUFS): it is taking requests, so again.
    for (int attempt = 0; (sent = sendmsg(helper, &header, 0)) < 0 && (errno == ENOBUFS || errno == EINTR) && attempt < 2000; attempt++)
        usleep(500);
    close(directory);
    if (sent < 0) {
        close(pair[0]);
        close(pair[1]);
        return -1;
    }
    // The worker's end of the stream stays open here too until the worker has answered. A socket that is only in flight
    // (in a message not yet received) can be taken for garbage by the kernel's collector of descriptors in flight when
    // another Unix socket closes meanwhile: the worker then reads a stream that has ended, and the program was started here,
    // itself, with pi-bin's process setup (0.7.2: 73 of 3,000 programs started 20 at a time). Holding it, the stream cannot end before the worker
    // answers, so that is waited for with an eye on the helper. (The protocol is unchanged: a helper of an earlier release,
    // still serving a Pi started before an update, works with this pi-spawn, and the other way round.)
    *stream = pair[0];

    struct pibolt_spawn_params params = { .ignored_signals = ignored, .blocked_signals = blocked };
    pid_t self = getpid();
    params.mode = getsid(0) == self ? PIBOLT_SPAWN_NEW_SESSION : getpgrp() == self ? PIBOLT_SPAWN_NEW_GROUP : PIBOLT_SPAWN_SHARE_GROUP;
    params.pgid = getpgrp();
    mode_t mask = umask(0);
    umask(mask);
    params.umask = mask;
    for (int resource = 0; resource < PIBOLT_SPAWN_RLIMITS; resource++) {
        struct rlimit limit;
        if (getrlimit(resource, &limit)) {
            // (Closed: the program would otherwise inherit it, and the helper's worker would wait on it.)
            close(*stream);
            close(pair[1]);
            *stream = -1;
            return -1;
        }
        params.rlimits[resource][0] = limit.rlim_cur;
        params.rlimits[resource][1] = limit.rlim_max;
    }
    params.path_len = (uint32_t)strlen(path) + 1;
    params.argv_len = (uint32_t)stringsSize(argv, &params.argc);
    params.envp_len = (uint32_t)stringsSize(environ, &params.envc);
    struct pibolt_spawn_reply reply;
    int failed = writeFully(*stream, &params, sizeof(params)) || writeFully(*stream, path, params.path_len) || writeStrings(*stream, argv)
        || writeStrings(*stream, environ);
    if (!failed)
        waitForAnswer(helper, *stream);
    // The worker has its own copy by now, or never will (the helper is gone, or nothing for 30 seconds): from here the
    // stream ends when the worker does, and a request that never reached one ends it at once.
    close(pair[1]);
    if (failed || readFully(*stream, &reply, sizeof(reply)) || reply.kind == PIBOLT_SPAWN_UNAVAILABLE) {
        close(*stream);
        return -1;
    }
    if (reply.kind == PIBOLT_SPAWN_FAILED) {
        tell(status, PIBOLT_SPAWN_FAILED, reply.value);
        _exit(127);
    }
    if (reply.kind != PIBOLT_SPAWN_STARTED) {
        close(*stream);
        return -1;
    }
    tell(status, PIBOLT_SPAWN_STARTED, reply.value);
    // The program has them now: they must close when it closes them (a pipe's reader sees its end).
    for (int i = 2; i < message.nfds + 2; i++)
        close(fds[i]);
    close(status);
    close(helper);
    return 0;
}

int main(int argc, char** argv)
{
    if (argc < 5)
        return 127;
    int helper = atoi(argv[1]);
    int status = atoi(argv[2]);
    const char* path = argv[3];
    char** programArgv = argv + 4;

    uint32_t ignored = 0, blocked = 0;
    sigset_t mask;
    sigprocmask(SIG_BLOCK, NULL, &mask);
    for (int signal = 1; signal < NSIG; signal++) {
        struct sigaction action;
        if (!sigaction(signal, NULL, &action) && action.sa_handler == SIG_IGN)
            ignored |= 1u << signal;
        if (sigismember(&mask, signal) == 1)
            blocked |= 1u << signal;
    }
    struct sigaction ignore = { .sa_handler = SIG_IGN };
    for (size_t i = 0; i < ASYNCHRONOUS_COUNT; i++)
        sigaction(asynchronous[i], &ignore, &saved[i]);

    int stream = -1;
    if (request(helper, status, path, programArgv, ignored, blocked, &stream))
        execute(helper, status, path, programArgv);

    struct pibolt_spawn_reply exited;
    if (readFully(stream, &exited, sizeof(exited)) || exited.kind != PIBOLT_SPAWN_EXITED)
        _exit(127);
    int wait = exited.value;
    if (WIFEXITED(wait))
        _exit(WEXITSTATUS(wait));
    int signal = WTERMSIG(wait);
    struct rlimit noCore = { 0, 0 };
    setrlimit(RLIMIT_CORE, &noCore);
    struct sigaction byDefault = { .sa_handler = SIG_DFL };
    sigaction(signal, &byDefault, NULL);
    sigset_t only;
    sigemptyset(&only);
    sigaddset(&only, signal);
    sigprocmask(SIG_UNBLOCK, &only, NULL);
    kill(getpid(), signal);
    _exit(128 + signal);
}
