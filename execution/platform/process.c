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

static volatile sig_atomic_t interrupted = 0;
static int interrupt_installed = 0;
static struct sigaction previous_int, previous_term;
static void record_interrupt(int signal_number) { interrupted = signal_number; }
int kvik_interrupt_install(void) {
    if (interrupt_installed) { errno = EBUSY; return -1; }
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = record_interrupt;
    sigemptyset(&action.sa_mask);
    interrupted = 0;
    if (sigaction(SIGINT, &action, &previous_int)) return -1;
    if (sigaction(SIGTERM, &action, &previous_term)) {
        int error = errno;
        (void)sigaction(SIGINT, &previous_int, NULL);
        errno = error;
        return -1;
    }
    interrupt_installed = 1;
    return 0;
}
int kvik_interrupt_requested(void) { return (int)interrupted; }
void kvik_interrupt_restore(void) {
    if (!interrupt_installed) return;
    (void)sigaction(SIGINT, &previous_int, NULL);
    (void)sigaction(SIGTERM, &previous_term, NULL);
    interrupt_installed = 0;
}

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

#include <poll.h>
#include <sys/event.h>
#include <sys/stat.h>
#include <limits.h>
/* Session has exclusive stream ownership. Never mutate inherited open-file flags:
 * TTY descriptors are independent opens; pipes use readiness and atomic-size writes.
 * Regular-file IO can still be delayed by the filesystem, as can journal IO. */
typedef struct { int queue, input, output, own_input, own_output; pid_t parent; } Foreground;
static int foreground_stream(int fd, int output, int *owned) {
    if (isatty(fd)) {
        char name[PATH_MAX];
        if (ttyname_r(fd, name, sizeof(name))) return -1;
        struct stat original, reopened;
        if (fstat(fd, &original)) return -1;
        int copy = open(name, (output ? O_WRONLY : O_RDONLY) | O_NONBLOCK | O_NOCTTY | O_CLOEXEC);
        if (copy < 0) return -1;
        if (fstat(copy, &reopened) || !isatty(copy) || original.st_rdev != reopened.st_rdev) {
            close(copy); errno = ESTALE; return -1;
        }
        *owned = 1;
        return copy;
    }
    struct stat status, null_status;
    if (fstat(fd, &status)) return -1;
    if (S_ISREG(status.st_mode) || S_ISFIFO(status.st_mode)) return fd;
    if (S_ISCHR(status.st_mode) && !stat("/dev/null", &null_status) && status.st_rdev == null_status.st_rdev) return fd;
    errno = ENOTSUP; return -1;
}
void *kvik_foreground_open(void) {
    Foreground *f = calloc(1, sizeof(*f));
    if (!f) return NULL;
    f->parent = getppid(); f->queue = kqueue(); f->input = f->output = -1;
    if (f->queue < 0) { free(f); return NULL; }
    fcntl(f->queue, F_SETFD, FD_CLOEXEC);
    struct kevent change;
    EV_SET(&change, f->parent, EVFILT_PROC, EV_ADD | EV_ENABLE | EV_ONESHOT, NOTE_EXIT, 0, NULL);
    if (f->parent <= 1 || kevent(f->queue, &change, 1, NULL, 0, NULL) < 0 || getppid() != f->parent) {
        close(f->queue); free(f); errno = ESRCH; return NULL;
    }
    f->input = foreground_stream(STDIN_FILENO, 0, &f->own_input);
    f->output = foreground_stream(STDOUT_FILENO, 1, &f->own_output);
    if (f->input < 0 || f->output < 0) { kvik_foreground_close(f); return NULL; }
    return f;
}
int kvik_foreground_read(void *handle, char *buffer, int capacity) {
    Foreground *f = handle;
    struct kevent event; struct timespec zero = {0, 0};
    int parent_event = kevent(f->queue, NULL, 0, &event, 1, &zero);
    if (getppid() != f->parent || parent_event > 0) return -3;
    if (parent_event < 0 && errno != EINTR) return -1;
    struct pollfd input = {f->input, POLLIN | POLLHUP, 0};
    int ready = poll(&input, 1, 0);
    if (ready < 0) return errno == EINTR ? 0 : -1;
    if (!ready) return 0;
    ssize_t count = read(f->input, buffer, (size_t)capacity);
    if (count == 0) return -2;
    if (count < 0) return errno == EAGAIN || errno == EINTR ? 0 : -1;
    return (int)count;
}
int kvik_foreground_write(void *handle, const char *buffer, int size) {
    Foreground *f = handle;
    struct pollfd output = {f->output, POLLOUT, 0};
    int ready = poll(&output, 1, 0);
    if (ready < 0) return errno == EINTR ? 0 : -1;
    if (!ready) return 0;
    /* POSIX pipe readiness guarantees an atomic write of PIPE_BUF with one writer. */
    if (size > _POSIX_PIPE_BUF) size = _POSIX_PIPE_BUF;
    ssize_t count = write(f->output, buffer, (size_t)size);
    if (count < 0) return errno == EAGAIN || errno == EINTR ? 0 : -1;
    return (int)count;
}
void kvik_foreground_close(void *handle) {
    if (!handle) return;
    Foreground *f = handle;
    if (f->own_input) close(f->input);
    if (f->own_output) close(f->output);
    close(f->queue); free(f);
}

/* Same-PID handoff: a pending cancellation cannot disappear between old/new handlers.
 * On success the resumed loop installs its handlers before unblocking INT/TERM.
 * On failure every old stream/handler remains usable. No inherited IO flags change. */
int kvik_foreground_exec(const char *path, char *const *argv, char *const *envp) {
    sigset_t blocked, previous;
    sigemptyset(&blocked); sigaddset(&blocked, SIGINT); sigaddset(&blocked, SIGTERM);
    if (sigprocmask(SIG_BLOCK, &blocked, &previous)) return -1;
    if (interrupted) { sigprocmask(SIG_SETMASK, &previous, NULL); errno = EINTR; return -1; }
    execve(path, argv, envp);
    int error = errno;
    sigprocmask(SIG_SETMASK, &previous, NULL); errno = error; return -1;
}
void kvik_foreground_resume_signals(void) {
    sigset_t blocked;
    sigemptyset(&blocked); sigaddset(&blocked, SIGINT); sigaddset(&blocked, SIGTERM);
    (void)sigprocmask(SIG_UNBLOCK, &blocked, NULL);
}
