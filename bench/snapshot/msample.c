// Build: clang -O2 -o msample msample.c (it samples only programs that are not platform binaries, such as pi-bin).
// msample: starts a program suspended, at its linked address (no ASLR slide), and samples its main thread's stack every
// INTERVAL us (frame-pointer walk) until it exits. Output: one line per sample, "t_us pc0 pc1 ...", to OUT.
// Usage: msample OUT INTERVAL_US program args...   (environment is passed through, plus
// BUN_INTERNAL_STATIC_HEAP_AT_LINKED_ADDRESS=1 so that a Bun executable with a static heap does not start itself again).
#include <mach/mach.h>
#include <mach/mach_time.h>
#include <mach/mach_vm.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

extern char** environ;

int main(int argc, char** argv)
{
    if (argc < 4) {
        fprintf(stderr, "usage: msample OUT INTERVAL_US program args...\n");
        return 2;
    }
    FILE* out = fopen(argv[1], "w");
    int interval = atoi(argv[2]);
    setenv("BUN_INTERNAL_STATIC_HEAP_AT_LINKED_ADDRESS", "1", 1);
    posix_spawnattr_t attr;
    posix_spawnattr_init(&attr);
    posix_spawnattr_setflags(&attr, POSIX_SPAWN_START_SUSPENDED | 0x100);
    pid_t pid;
    if (posix_spawn(&pid, argv[3], NULL, &attr, argv + 3, environ)) {
        perror("posix_spawn");
        return 1;
    }
    mach_port_t task;
    kern_return_t kr = task_for_pid(mach_task_self(), pid, &task);
    if (kr != KERN_SUCCESS) {
        fprintf(stderr, "task_for_pid: %d\n", kr);
        kill(pid, SIGKILL);
        return 1;
    }
    mach_timebase_info_data_t tb;
    mach_timebase_info(&tb);
    uint64_t start = mach_absolute_time();
    task_resume(task);
    thread_act_t main_thread = MACH_PORT_NULL;
    int status;
    for (;;) {
        if (waitpid(pid, &status, WNOHANG) == pid)
            break;
        if (main_thread == MACH_PORT_NULL) {
            thread_act_array_t threads;
            mach_msg_type_number_t count;
            if (task_threads(task, &threads, &count) != KERN_SUCCESS)
                break;
            if (count)
                main_thread = threads[0];
            for (unsigned i = 1; i < count; i++)
                mach_port_deallocate(mach_task_self(), threads[i]);
            vm_deallocate(mach_task_self(), (vm_address_t)threads, count * sizeof(thread_act_t));
        }
        if (thread_suspend(main_thread) != KERN_SUCCESS)
            break;
        arm_thread_state64_t state;
        mach_msg_type_number_t sc = ARM_THREAD_STATE64_COUNT;
        uint64_t pcs[96];
        int n = 0;
        if (thread_get_state(main_thread, ARM_THREAD_STATE64, (thread_state_t)&state, &sc) == KERN_SUCCESS) {
            uint64_t pc = arm_thread_state64_get_pc(state) & 0x0000ffffffffffffULL;
            uint64_t lr = arm_thread_state64_get_lr(state) & 0x0000ffffffffffffULL;
            uint64_t fp = arm_thread_state64_get_fp(state);
            pcs[n++] = pc;
            pcs[n++] = lr | 1; // (marked: the link register, which may or may not be a frame of its own)
            while (fp && n < 96) {
                uint64_t frame[2];
                mach_vm_size_t got = 0;
                if (mach_vm_read_overwrite(task, fp, sizeof(frame), (mach_vm_address_t)frame, &got) != KERN_SUCCESS || got != sizeof(frame))
                    break;
                uint64_t ret = frame[1] & 0x0000ffffffffffffULL;
                if (!ret)
                    break;
                pcs[n++] = ret;
                if (frame[0] <= fp)
                    break;
                fp = frame[0];
            }
        }
        thread_resume(main_thread);
        uint64_t t = (mach_absolute_time() - start) * tb.numer / tb.denom / 1000;
        fprintf(out, "%llu", (unsigned long long)t);
        for (int i = 0; i < n; i++)
            fprintf(out, " %llx", (unsigned long long)pcs[i]);
        fputc('\n', out);
        usleep(interval);
    }
    fclose(out);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128;
}
