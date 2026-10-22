#include "process.h"
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>
#include <mach-o/dyld.h>

char *kvik_executable_directory(void) {
    uint32_t size = 0;
    (void)_NSGetExecutablePath(NULL, &size);
    if (!size) return NULL;
    char *buffer = malloc(size);
    if (!buffer) return NULL;
    if (_NSGetExecutablePath(buffer, &size)) { free(buffer); return NULL; }
    char *resolved = realpath(buffer, NULL);
    free(buffer);
    if (!resolved) return NULL;
    char *separator = strrchr(resolved, '/');
    if (!separator || separator == resolved) { free(resolved); errno = EINVAL; return NULL; }
    *separator = '\0';
    return resolved;
}

typedef struct {
    pid_t guardian;
    int control, result, received, reaped;
    int values[3];
} Process;

/* All pipe ends are CLOEXEC; only the guardian's declared 3/4 descriptors survive spawn.
 * POSIX_SPAWN_CLOEXEC_DEFAULT also prevents leaking other invocations' ownership pipes. */
void *kvik_process_start(const char *helper, const char *cwd, const char *out, const char *err,
                         long long timeout_ms, char *const *argv, char *const *envp) {
    int control[2], result[2];
    if (pipe(control)) return NULL;
    if (pipe(result)) { close(control[0]); close(control[1]); return NULL; }
    for (int i = 0; i < 2; ++i) { fcntl(control[i], F_SETFD, FD_CLOEXEC); fcntl(result[i], F_SETFD, FD_CLOEXEC); }
    /* Move sources above fixed child FDs to avoid dup2 source/target collisions. */
    int input = fcntl(control[0], F_DUPFD_CLOEXEC, 10);
    int output = fcntl(result[1], F_DUPFD_CLOEXEC, 10);
    close(control[0]); close(result[1]);
    Process *p = calloc(1, sizeof(*p));
    size_t count = 0;
    while (argv[count]) ++count;
    char **args = calloc(count + 7, sizeof(char *));
    if (input < 0 || output < 0 || !p || !args) {
        close(input); close(output); close(control[1]); close(result[0]); free(p); free(args); return NULL;
    }
    char timeout[32];
    snprintf(timeout, sizeof(timeout), "%lld", timeout_ms);
    args[0] = (char *)helper; args[1] = (char *)cwd; args[2] = (char *)out;
    args[3] = (char *)err; args[4] = timeout;
    for (size_t i = 0; i < count; ++i) args[i + 5] = argv[i];
    posix_spawn_file_actions_t actions;
    posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_adddup2(&actions, input, 3);
    posix_spawn_file_actions_adddup2(&actions, output, 4);
    posix_spawnattr_t attributes;
    posix_spawnattr_init(&attributes);
    sigset_t empty, defaults;
    sigemptyset(&empty); sigfillset(&defaults);
    posix_spawnattr_setsigmask(&attributes, &empty);
    posix_spawnattr_setsigdefault(&attributes, &defaults);
    posix_spawnattr_setflags(&attributes, POSIX_SPAWN_CLOEXEC_DEFAULT | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF);
    int error = posix_spawn(&p->guardian, helper, &actions, &attributes, args, envp);
    posix_spawnattr_destroy(&attributes); posix_spawn_file_actions_destroy(&actions);
    close(input); close(output); free(args);
    if (error) { close(control[1]); close(result[0]); free(p); errno = error; return NULL; }
    p->control = control[1]; p->result = result[0];
    /* Suppress SIGPIPE on a guardian that has already completed. */
    fcntl(p->control, F_SETNOSIGPIPE, 1);
    fcntl(p->result, F_SETFL, O_NONBLOCK);
    return p;
}

int kvik_process_poll(void *handle, int *exit_code, int *timed_out, int *cancelled) {
    Process *p = handle;
    if (!p->received) {
        ssize_t count = read(p->result, p->values, sizeof(p->values));
        if (count == sizeof(p->values)) p->received = 1;
        else if (count >= 0 || (errno != EAGAIN && errno != EINTR)) {
            p->values[0] = 125; p->values[1] = p->values[2] = 0; p->received = 1;
        }
    }
    if (!p->reaped) {
        int status;
        pid_t observed = waitpid(p->guardian, &status, WNOHANG);
        if (observed == p->guardian || (observed < 0 && errno == ECHILD)) p->reaped = 1;
    }
    if (!p->received || !p->reaped) return 0;
    *exit_code = p->values[0]; *timed_out = p->values[1]; *cancelled = p->values[2];
    return 1;
}

void kvik_process_cancel(void *handle) {
    Process *p = handle;
    /* EOF is idempotent and cannot block even if the guardian has not read yet. */
    if (p->control >= 0) { close(p->control); p->control = -1; }
}

void kvik_process_close(void *handle) {
    Process *p = handle;
    kvik_process_cancel(p);
    int code, timeout, cancelled;
    while (!kvik_process_poll(p, &code, &timeout, &cancelled)) usleep(10000);
    close(p->result); free(p);
}
