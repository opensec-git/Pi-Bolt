// The helper that starts the programs Pi starts on macOS, so that they get the system's own process setup (darwin-spawn.h). The launcher forks it
// before it starts pi-bin at its linked address, so the helper keeps the usual setup, and so does every process the helper starts. It lives as
// long as pi-bin does. For each program it forks a worker, which starts the program as pi-spawn was asked to, tells pi-spawn
// when it has exited, and kills it if pi-spawn is killed first.
#include "darwin-spawn.h"

#include <errno.h>
#include <fcntl.h>
#include <libproc.h>
#include <signal.h>
#include <spawn.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/event.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

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

static void reply(int stream, int kind, int value)
{
    struct pibolt_spawn_reply message = { kind, value };
    const char* at = (const char*)&message;
    size_t size = sizeof(message);
    while (size) {
        ssize_t wrote = write(stream, at, size);
        if (wrote < 0 && errno == EINTR)
            continue;
        if (wrote <= 0)
            return;
        at += wrote;
        size -= (size_t)wrote;
    }
}

// Splits `size` bytes of NUL-terminated strings into a NULL-terminated array of `count` of them.
static char** splitStrings(char* strings, size_t size, uint32_t count)
{
    char** array = calloc((size_t)count + 1, sizeof(char*));
    if (!array)
        return NULL;
    size_t at = 0;
    for (uint32_t i = 0; i < count; i++) {
        if (at >= size)
            return NULL;
        array[i] = strings + at;
        char* end = memchr(strings + at, 0, size - at);
        if (!end)
            return NULL;
        at = (size_t)(end - strings) + 1;
    }
    return at == size ? array : NULL;
}

static char* readStrings(int stream, uint32_t size)
{
    if (size > (64u << 20))
        return NULL;
    char* strings = malloc(size ? size : 1);
    if (strings && size && readFully(stream, strings, size)) {
        free(strings);
        return NULL;
    }
    return strings;
}

// In the worker, a child of the helper: starts the program, and waits for it.
static void work(int stream, int directory, int count, int* fds, const int32_t* targets)
{
    struct sigaction byDefault = { .sa_handler = SIG_DFL };
    sigaction(SIGCHLD, &byDefault, NULL);

    struct pibolt_spawn_params params;
    // pi-spawn keeps its copy of this end of the stream until it has an answer: every way out answers.
    if (readFully(stream, &params, sizeof(params))) {
        reply(stream, PIBOLT_SPAWN_UNAVAILABLE, 0);
        _exit(0);
    }
    char* path = readStrings(stream, params.path_len);
    char* arguments = path ? readStrings(stream, params.argv_len) : NULL;
    char* environment = arguments ? readStrings(stream, params.envp_len) : NULL;
    if (!environment || !params.path_len || path[params.path_len - 1]) {
        reply(stream, PIBOLT_SPAWN_UNAVAILABLE, 0);
        _exit(0);
    }
    char** argv = splitStrings(arguments, params.argv_len, params.argc);
    char** envp = splitStrings(environment, params.envp_len, params.envc);
    if (!argv || !envp || !params.argc) {
        reply(stream, PIBOLT_SPAWN_UNAVAILABLE, 0);
        _exit(0);
    }

    // posix_spawn's dup2s run in order: no file may sit where an earlier one goes.
    int highest = 2;
    for (int i = 0; i < count; i++)
        highest = targets[i] > highest ? targets[i] : highest;
    for (int i = 0; i < count; i++) {
        if (fds[i] > highest)
            continue;
        int moved = fcntl(fds[i], F_DUPFD_CLOEXEC, highest + 1);
        if (moved < 0) {
            reply(stream, PIBOLT_SPAWN_UNAVAILABLE, 0);
            _exit(0);
        }
        close(fds[i]);
        fds[i] = moved;
    }

    posix_spawn_file_actions_t actions;
    posix_spawnattr_t attributes;
    if (posix_spawn_file_actions_init(&actions) || posix_spawnattr_init(&attributes)) {
        reply(stream, PIBOLT_SPAWN_UNAVAILABLE, 0);
        _exit(0);
    }
    int failed = posix_spawn_file_actions_addfchdir_np(&actions, directory);
    for (int i = 0; i < count && !failed; i++)
        failed = posix_spawn_file_actions_adddup2(&actions, fds[i], targets[i]);

    // The program gets pi-spawn's signal mask, and what pi-spawn ignored it ignores (the worker ignores the same, and the
    // rest are reset). Not SIGCHLD: the worker waits for the program.
    sigset_t reset, blocked;
    sigemptyset(&reset);
    sigemptyset(&blocked);
    struct sigaction ignore = { .sa_handler = SIG_IGN };
    for (int signal = 1; signal < NSIG; signal++) {
        if (params.blocked_signals & (1u << signal))
            sigaddset(&blocked, signal);
        if (signal == SIGKILL || signal == SIGSTOP)
            continue;
        if ((params.ignored_signals & (1u << signal)) && signal != SIGCHLD)
            sigaction(signal, &ignore, NULL);
        else
            sigaddset(&reset, signal);
    }
    short flags = POSIX_SPAWN_CLOEXEC_DEFAULT | POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETSIGMASK;
    if (params.mode == PIBOLT_SPAWN_NEW_SESSION)
        flags |= POSIX_SPAWN_SETSID;
    else {
        flags |= POSIX_SPAWN_SETPGROUP;
        posix_spawnattr_setpgroup(&attributes, params.mode == PIBOLT_SPAWN_SHARE_GROUP ? params.pgid : 0);
    }
    failed = failed || posix_spawnattr_setflags(&attributes, flags) || posix_spawnattr_setsigdefault(&attributes, &reset)
        || posix_spawnattr_setsigmask(&attributes, &blocked);
    if (failed) {
        reply(stream, PIBOLT_SPAWN_UNAVAILABLE, 0);
        _exit(0);
    }
    umask((mode_t)params.umask);
    for (int resource = 0; resource < PIBOLT_SPAWN_RLIMITS; resource++) {
        struct rlimit limit = { (rlim_t)params.rlimits[resource][0], (rlim_t)params.rlimits[resource][1] };
        setrlimit(resource, &limit);
    }

    pid_t pid;
    int error = posix_spawn(&pid, path, &actions, &attributes, argv, envp);
    if (error == EPERM && params.mode == PIBOLT_SPAWN_SHARE_GROUP) {
        // Not allowed into pi-bin's process group: pi-spawn starts the program itself, where it belongs.
        reply(stream, PIBOLT_SPAWN_UNAVAILABLE, 0);
        _exit(0);
    }
    if (error) {
        reply(stream, PIBOLT_SPAWN_FAILED, error);
        _exit(0);
    }
    for (int i = 0; i < count; i++)
        close(fds[i]);
    close(directory);
    reply(stream, PIBOLT_SPAWN_STARTED, pid);

    // Until the program exits, or pi-spawn is gone (killed with SIGKILL, so the program is too).
    int queue = kqueue();
    struct kevent changes[2];
    EV_SET(&changes[0], pid, EVFILT_PROC, EV_ADD | EV_ONESHOT, NOTE_EXIT, 0, NULL);
    EV_SET(&changes[1], stream, EVFILT_READ, EV_ADD, 0, 0, NULL);
    int exited = queue < 0;
    if (!exited) {
        struct kevent receipts[2];
        changes[0].flags |= EV_RECEIPT;
        changes[1].flags |= EV_RECEIPT;
        if (kevent(queue, changes, 2, receipts, 2, NULL) != 2 || receipts[0].data)
            exited = 1; // An exited child cannot be watched; anything else: wait for it below.
    }
    while (!exited) {
        struct kevent event;
        int got = kevent(queue, NULL, 0, &event, 1, NULL);
        if (got < 0 && errno == EINTR)
            continue;
        if (got <= 0 || event.filter == EVFILT_PROC)
            break;
        char discard[64];
        ssize_t read_ = read(stream, discard, sizeof(discard));
        if (read_ == 0 || (read_ < 0 && errno != EINTR && errno != EAGAIN)) {
            kill(pid, SIGKILL);
            waitpid(pid, NULL, 0);
            _exit(0);
        }
    }
    siginfo_t info;
    while (waitid(P_PID, (id_t)pid, &info, WEXITED | WNOWAIT) < 0 && errno == EINTR) { }
    int status = info.si_code == CLD_EXITED ? (info.si_status & 0xff) << 8 : (info.si_status & 0x7f) | (info.si_code == CLD_DUMPED ? 0x80 : 0);
    reply(stream, PIBOLT_SPAWN_EXITED, status);
    // The program's pid stays taken until pi-spawn has exited (pi-bin may signal the pid until it learns that).
    char discard[64];
    for (;;) {
        ssize_t got = read(stream, discard, sizeof(discard));
        if (got > 0 || (got < 0 && errno == EINTR))
            continue;
        break; // (The end, or an error: errno is only looked at when read() failed.)
    }
    waitpid(pid, NULL, 0);
    _exit(0);
}

static void closeDescriptors(int* fds, int count)
{
    for (int i = 0; i < count; i++)
        close(fds[i]);
}

// One request from a pi-spawn; -1 when there is none.
static int serve(int socket_)
{
    struct pibolt_spawn_request request;
    union {
        struct cmsghdr header;
        char space[CMSG_SPACE((PIBOLT_SPAWN_MAX_FDS + 2) * sizeof(int))];
    } control;
    struct iovec vector = { &request, sizeof(request) };
    struct msghdr message = { .msg_iov = &vector, .msg_iovlen = 1, .msg_control = &control, .msg_controllen = sizeof(control) };
    ssize_t size = recvmsg(socket_, &message, MSG_DONTWAIT);
    if (size < 0)
        return errno == EINTR ? 0 : -1;

    int fds[PIBOLT_SPAWN_MAX_FDS + 2];
    int count = 0;
    for (struct cmsghdr* header = CMSG_FIRSTHDR(&message); header; header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS)
            continue;
        int n = (int)((header->cmsg_len - CMSG_LEN(0)) / sizeof(int));
        for (int i = 0; i < n && count < PIBOLT_SPAWN_MAX_FDS + 2; i++)
            memcpy(&fds[count++], CMSG_DATA(header) + i * sizeof(int), sizeof(int));
    }
    size_t header = offsetof(struct pibolt_spawn_request, targets);
    int valid = !(message.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) && (size_t)size >= header && request.magic == PIBOLT_SPAWN_MAGIC
        && request.version == PIBOLT_SPAWN_VERSION && request.nfds >= 0 && request.nfds <= PIBOLT_SPAWN_MAX_FDS
        && (size_t)size == header + (size_t)request.nfds * sizeof(int32_t) && count == request.nfds + 2;
    for (int i = 0; valid && i < request.nfds; i++)
        valid = request.targets[i] >= 0 && request.targets[i] < 4096;
    if (!valid) {
        // pi-spawn waits for an answer on the stream, the first descriptor (it keeps its own copy of it until then), and
        // then starts the program itself.
        if (count > 0)
            reply(fds[0], PIBOLT_SPAWN_UNAVAILABLE, 0);
        closeDescriptors(fds, count);
        return 0;
    }
    pid_t worker = fork();
    if (worker == 0) {
        close(socket_);
        work(fds[0], fds[1], request.nfds, fds + 2, request.targets);
    }
    if (worker < 0)
        reply(fds[0], PIBOLT_SPAWN_UNAVAILABLE, 0);
    closeDescriptors(fds, count);
    return 0;
}

static void serveUntilParentExits(int socket_, pid_t parent)
{
    // Out of pi-bin's process group (the terminal's signals are not for the helper), but in its session: a program can only
    // join pi-bin's group from there.
    setpgid(0, 0);
    struct sigaction ignore = { .sa_handler = SIG_IGN };
    const int ignored[] = { SIGHUP, SIGINT, SIGQUIT, SIGPIPE, SIGTSTP, SIGTTIN, SIGTTOU };
    for (size_t i = 0; i < sizeof(ignored) / sizeof(ignored[0]); i++)
        sigaction(ignored[i], &ignore, NULL);
    struct sigaction reap = { .sa_handler = SIG_IGN, .sa_flags = SA_NOCLDWAIT }; // workers leave no zombies
    sigaction(SIGCHLD, &reap, NULL);

    // Nothing of pi-bin's: a pipe that pi-bin writes to must close when pi-bin and its programs are done with it.
    int null = open("/dev/null", O_RDWR);
    if (null >= 0) {
        dup2(null, 0);
        dup2(null, 1);
        dup2(null, 2);
    }
    struct proc_fdinfo open_[512];
    int listed = proc_pidinfo(getpid(), PROC_PIDLISTFDS, 0, open_, sizeof(open_));
    if (listed <= 0 || listed >= (int)sizeof(open_)) {
        int top = getdtablesize();
        for (int fd = 3; fd < top; fd++) {
            if (fd != socket_)
                close(fd);
        }
    } else {
        for (int i = 0; i < listed / (int)sizeof(open_[0]); i++) {
            if (open_[i].proc_fd > 2 && open_[i].proc_fd != socket_)
                close(open_[i].proc_fd);
        }
    }

    // Take the socket over: until the helper first receives on it, its peer's LOCAL_PEERPID is the launcher's (pi-bin's),
    // and pi-spawn watches the helper by that pid.
    char none;
    recv(socket_, &none, 0, MSG_DONTWAIT | MSG_PEEK);
    int queue = kqueue();
    if (queue < 0)
        _exit(0);
    struct kevent changes[2];
    EV_SET(&changes[0], parent, EVFILT_PROC, EV_ADD, NOTE_EXIT, 0, NULL);
    EV_SET(&changes[1], socket_, EVFILT_READ, EV_ADD, 0, 0, NULL);
    if (kevent(queue, changes, 2, NULL, 0, NULL) < 0 || getppid() != parent)
        _exit(0);
    for (;;) {
        struct kevent events[2];
        int got = kevent(queue, NULL, 0, events, 2, NULL);
        if (got < 0 && errno == EINTR)
            continue;
        if (got <= 0)
            _exit(0);
        for (int i = 0; i < got; i++) {
            if (events[i].filter == EVFILT_PROC)
                _exit(0);
        }
        while (!serve(socket_)) { }
    }
}

int pibolt_spawn_helper_start(void)
{
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_DGRAM, 0, pair))
        return -1;
    int kept = fcntl(pair[0], F_DUPFD, PIBOLT_SPAWN_HELPER_MIN_FD);
    close(pair[0]);
    if (kept < 0) {
        close(pair[1]);
        return -1;
    }
    // Room for the requests of many programs started at once (macOS's default is about 4 KB).
    int room = 256 * 1024;
    setsockopt(pair[1], SOL_SOCKET, SO_RCVBUF, &room, sizeof(room));
    pid_t parent = getpid();
    pid_t helper = fork();
    if (helper == 0) {
        close(kept);
        serveUntilParentExits(pair[1], parent);
        _exit(0);
    }
    close(pair[1]);
    if (helper < 0) {
        close(kept);
        return -1;
    }
    return kept;
}
