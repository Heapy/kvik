#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#define KVIK_TERMINAL_INTERNAL
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
#ifdef __APPLE__
#include <mach-o/dyld.h>
#include <sys/event.h>
#else
#include <sys/syscall.h>
#include <sys/random.h>
#endif

/* Linux glibc closefrom actions run after dup actions: only declared capabilities
 * survive, including when unrelated owner threads open descriptors during spawn. */
static int spawn_close_undeclared(posix_spawn_file_actions_t *actions, int terminal, int upstream) {
#ifdef __linux__
    int error;
    if (!terminal && (error = posix_spawn_file_actions_addclose(actions, 5))) return error;
    if (!upstream && (error = posix_spawn_file_actions_addclose(actions, 6))) return error;
    return posix_spawn_file_actions_addclosefrom_np(actions, 7);
#else
    (void)actions; (void)terminal; (void)upstream; return 0;
#endif
}
#ifdef __APPLE__
#define KVIK_SPAWN_CLOSE_DEFAULT POSIX_SPAWN_CLOEXEC_DEFAULT
#else
#define KVIK_SPAWN_CLOSE_DEFAULT 0
#endif

int kvik_process_ownership_version(void) { return 1; }

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
#ifdef __APPLE__
    uint32_t size = 0;
    (void)_NSGetExecutablePath(NULL, &size);
    if (!size) return NULL;
    char *buffer = malloc(size);
    if (!buffer) return NULL;
    if (_NSGetExecutablePath(buffer, &size)) { free(buffer); return NULL; }
    char *resolved = realpath(buffer, NULL);
    free(buffer);
#else
    char buffer[65536];
    ssize_t size = readlink("/proc/self/exe", buffer, sizeof(buffer) - 1);
    if (size < 0) return NULL;
    if ((size_t)size >= sizeof(buffer) - 1) { errno = ENAMETOOLONG; return NULL; }
    buffer[size] = 0;
    char *resolved = realpath(buffer, NULL);
#endif
    if (!resolved) return NULL;
    char *separator = strrchr(resolved, '/');
    if (!separator || separator == resolved) { free(resolved); errno = EINVAL; return NULL; }
    *separator = '\0';
    return resolved;
}

/* Captured before Kotlin/user code runs; unrelated execs cannot inherit it. */
static KvikBarrier inherited_barrier = {.fd = -1};
static int barrier_error;
__attribute__((constructor)) static void capture_barrier(void) {
    if (kvik_barrier_capture(&inherited_barrier)) barrier_error = EPROTO;
}
static int barrier_ready(void) {
    if (barrier_error) { errno = barrier_error; return -1; }
    return kvik_barrier_validate(&inherited_barrier);
}
typedef struct {
    pid_t guardian;
    int control, result, eof, reaped, failed, notified, verified, setup_stage, setup_error;
    size_t received;
    unsigned char bytes[sizeof(int64_t) * KVIK_PROCESS_RESULT_FIELDS + 1];
} Process;

/* All pipes are CLOEXEC. Only declared 3/4 and the optional upstream capability 6
 * survive spawn; every process guardian has a group distinct from its owner. */
void *kvik_process_start(const char *helper, const char *cwd, const char *out, const char *err,
                         long long timeout_ms, char *const *argv, char *const *envp) {
    if (timeout_ms < 0) { errno = EINVAL; return NULL; }
    if (barrier_ready()) return NULL;
    char marker[KVIK_BARRIER_MARKER_BYTES];
    char **environment = kvik_barrier_environment(envp, &inherited_barrier, marker);
    if (!environment) return NULL;
    int control[2] = {-1, -1}, result[2] = {-1, -1};
    int input = -1, output = -1, upstream = -1, error = 0;
    Process *p = NULL; char **args = NULL;
    if (pipe(control) || pipe(result)) { error = errno; goto fail; }
    for (int i = 0; i < 2; ++i) {
        if (fcntl(control[i], F_SETFD, FD_CLOEXEC) || fcntl(result[i], F_SETFD, FD_CLOEXEC)) { error = errno; goto fail; }
    }
    if (kvik_pipe_no_sigpipe(control[1]) || fcntl(result[0], F_SETFL, O_NONBLOCK)) { error = errno; goto fail; }
    input = fcntl(control[0], F_DUPFD_CLOEXEC, 10);
    output = fcntl(result[1], F_DUPFD_CLOEXEC, 10);
    if (inherited_barrier.fd >= 0) upstream = fcntl(inherited_barrier.fd, F_DUPFD_CLOEXEC, 10);
    if (input < 0 || output < 0 || (inherited_barrier.fd >= 0 && upstream < 0)) { error = errno; goto fail; }
    close(control[0]); control[0] = -1; close(result[1]); result[1] = -1;
    p = calloc(1, sizeof(*p));
    size_t count = 0; while (argv[count]) ++count;
    args = calloc(count + 6, sizeof(char *));
    if (!p || !args) { error = ENOMEM; goto fail; }
    char timeout[32]; snprintf(timeout, sizeof(timeout), "%lld", timeout_ms);
    args[0] = (char *)helper; args[1] = (char *)cwd; args[2] = (char *)out;
    args[3] = (char *)err; args[4] = timeout;
    for (size_t i = 0; i < count; ++i) args[i + 5] = argv[i];
    posix_spawn_file_actions_t actions; posix_spawnattr_t attributes;
    if ((error = posix_spawn_file_actions_init(&actions))) goto fail;
    if ((error = posix_spawnattr_init(&attributes))) { posix_spawn_file_actions_destroy(&actions); goto fail; }
    sigset_t empty, defaults; sigemptyset(&empty); sigfillset(&defaults);
    if ((error = posix_spawn_file_actions_adddup2(&actions, input, 3)) ||
        (error = posix_spawn_file_actions_adddup2(&actions, output, 4)) ||
        (upstream >= 0 && (error = posix_spawn_file_actions_adddup2(&actions, upstream, KVIK_BARRIER_FD))) ||
        (error = spawn_close_undeclared(&actions, 0, upstream >= 0)) ||
        (error = posix_spawnattr_setsigmask(&attributes, &empty)) ||
        (error = posix_spawnattr_setsigdefault(&attributes, &defaults)) ||
        (error = posix_spawnattr_setpgroup(&attributes, 0)) ||
        (error = posix_spawnattr_setflags(&attributes, KVIK_SPAWN_CLOSE_DEFAULT | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETPGROUP))) {
        /* No child exists on any setup failure. */
    } else error = posix_spawn(&p->guardian, helper, &actions, &attributes, args, environment);
    posix_spawnattr_destroy(&attributes); posix_spawn_file_actions_destroy(&actions);
    if (error) goto fail;
    close(input); close(output); if (upstream >= 0) close(upstream); free(args); free(environment);
    p->control = control[1]; p->result = result[0];
    return p;
fail:
    if (input >= 0) close(input);
    if (output >= 0) close(output);
    if (upstream >= 0) close(upstream);
    for (int i = 0; i < 2; ++i) { if (control[i] >= 0) close(control[i]); if (result[i] >= 0) close(result[i]); }
    free(p); free(args); free(environment); errno = error ? error : EIO; return NULL;
}

int kvik_process_poll(void *handle, int *exit_code, int *timed_out, int *cancelled) {
    Process *p = handle;
    if (!p->eof) {
        ssize_t count = read(p->result, p->bytes + p->received, sizeof(p->bytes) - p->received);
        if (count > 0) {
            p->received += (size_t)count;
            if (p->received == sizeof(p->bytes)) { p->failed = 1; p->eof = 1; kvik_process_cancel(p); }
        } else if (count == 0 || (errno != EAGAIN && errno != EINTR)) {
            p->eof = 1;
            if (count < 0 || p->received != sizeof(int64_t) * KVIK_PROCESS_RESULT_FIELDS) p->failed = 1;
        }
    }
    if (!p->reaped) {
        int status = 0; pid_t observed = waitpid(p->guardian, &status, WNOHANG);
        if (observed == p->guardian) {
            p->reaped = 1;
            if (!WIFEXITED(status) || WEXITSTATUS(status)) p->failed = 1;
        } else if (observed < 0 && errno != EINTR) { p->reaped = 1; p->failed = 1; }
    }
    if (!p->eof || !p->reaped) return 0;
    int64_t values[KVIK_PROCESS_RESULT_FIELDS]; memcpy(values, p->bytes, sizeof(values));
    if (p->failed || values[0] != KVIK_PROCESS_RESULT_MAGIC || values[1] < 0 || values[1] > 255 ||
        values[2] < 0 || values[2] > 1 || values[3] < 0 || values[3] > 1 || (values[2] && values[3]) ||
        values[4] < KVIK_SETUP_NONE || values[4] > KVIK_SETUP_EXEC ||
        (!values[4] && values[5]) || (values[4] && (values[5] <= 0 || values[5] > INT32_MAX)) ||
        values[6] < 0 || values[6] > 1 || values[7] < 0 || values[8] < 0 || values[9] < 0 ||
        (!values[6] && (values[7] || values[8] || values[9])) || (values[4] && values[6]) ||
        (values[4] && values[1] != (values[3] ? 130 : values[2] ? 124 : values[4] == KVIK_SETUP_EXEC ? 127 : 125))) {
        if (!p->notified) { kvik_barrier_poison(&inherited_barrier); p->notified = 1; }
        errno = EPROTO; return -1;
    }
    p->verified = 1; p->setup_stage = values[4]; p->setup_error = values[5];
    *exit_code = values[1]; *timed_out = values[2]; *cancelled = values[3]; return 1;
}
int kvik_process_setup_error(void *handle, int *stage, int *error) {
    Process *p = handle;
    if (!p || !p->verified || !stage || !error) { errno = EINVAL; return -1; }
    *stage = p->setup_stage; *error = p->setup_error;
    return p->setup_stage != KVIK_SETUP_NONE;
}
int kvik_process_usage(void *handle, long long *user_nanos, long long *system_nanos, long long *peak_rss_bytes) {
    Process *p = handle;
    if (!p || !p->verified || !user_nanos || !system_nanos || !peak_rss_bytes) { errno = EINVAL; return -1; }
    int64_t values[KVIK_PROCESS_RESULT_FIELDS]; memcpy(values, p->bytes, sizeof(values));
    *user_nanos = values[7]; *system_nanos = values[8]; *peak_rss_bytes = values[9];
    return (int)values[6];
}
void kvik_process_cancel(void *handle) {
    Process *p = handle;
    if (p->control >= 0) { close(p->control); p->control = -1; }
}
int kvik_process_close(void *handle) {
    Process *p = handle; kvik_process_cancel(p);
    int code, timeout, cancelled, result;
    while (!(result = kvik_process_poll(p, &code, &timeout, &cancelled))) usleep(10000);
    close(p->result); free(p);
    if (result < 0) { errno = EPROTO; return -1; }
    return 0;
}

#include <poll.h>
#include <sys/stat.h>
#include <limits.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <time.h>
/* Session has exclusive stream ownership. Never mutate inherited open-file flags:
 * TTY descriptors are independent opens; pipes use readiness and atomic-size writes.
 * Regular-file IO can still be delayed by the filesystem, as can journal IO. */
typedef struct {
    int queue, input, output, own_input, own_output, terminal;
    pid_t parent;
    KvikTerminalLease lease;
} Foreground;
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
static void *foreground_open(int lock_terminal) {
    Foreground *f = calloc(1, sizeof(*f));
    if (!f) return NULL;
    f->parent = getppid(); f->input = f->output = -1;
#ifdef __APPLE__
    f->queue = kqueue();
    if (f->queue < 0) { free(f); return NULL; }
    fcntl(f->queue, F_SETFD, FD_CLOEXEC);
    struct kevent change;
    EV_SET(&change, f->parent, EVFILT_PROC, EV_ADD | EV_ENABLE | EV_ONESHOT, NOTE_EXIT, 0, NULL);
    int observed = kevent(f->queue, &change, 1, NULL, 0, NULL);
#else
    f->queue = (int)syscall(SYS_pidfd_open, f->parent, 0);
    if (f->queue < 0) { free(f); return NULL; }
    int observed = 0; /* pidfd is CLOEXEC and remains bound even after PID reuse. */
#endif
    if (f->parent <= 1 || observed < 0 || getppid() != f->parent) {
        close(f->queue); free(f); errno = ESRCH; return NULL;
    }
    f->input = foreground_stream(STDIN_FILENO, 0, &f->own_input);
    f->output = foreground_stream(STDOUT_FILENO, 1, &f->own_output);
    if (f->input < 0 || f->output < 0 || (lock_terminal && isatty(f->input) && flock(f->input, LOCK_EX | LOCK_NB))) {
        int error = errno; kvik_foreground_close(f); errno = error; return NULL;
    }
    return f;
}
void *kvik_foreground_open(void) { return foreground_open(1); }
static long long terminal_now(void) {
    struct timespec value; clock_gettime(CLOCK_MONOTONIC, &value);
    return value.tv_sec * 1000LL + value.tv_nsec / 1000000;
}
static int terminal_receive(int fd, KvikTerminalReply *reply) {
    long long deadline = terminal_now() + KVIK_TERMINAL_DEADLINE_MS;
    while (terminal_now() < deadline) {
        struct pollfd input = {fd, POLLIN | POLLHUP, 0};
        int ready = poll(&input, 1, 20);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) return -1;
        if (!ready) continue;
        ssize_t count = read(fd, reply, sizeof(*reply));
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (count != sizeof(*reply)) { errno = EPIPE; return -1; }
        return 0;
    }
    errno = ETIMEDOUT; return -1;
}
static int terminal_exchange(const KvikTerminalLease *lease, int operation, KvikTerminalReply *reply) {
    KvikTerminalRequest request; memset(&request, 0, sizeof(request));
    request.operation = operation; request.owner = getpid(); memcpy(request.cookie, lease->cookie, 16);
    if (kvik_pipe_write(lease->control, &request, sizeof(request)) != sizeof(request) || terminal_receive(lease->reply, reply)) return -1;
    if (reply->operation != operation || memcmp(reply->cookie, lease->cookie, 16)) { errno = EPROTO; return -1; }
    if (reply->error) { errno = reply->error; return -1; }
    return 0;
}
static int terminal_reap(pid_t child) {
    long long deadline = terminal_now() + KVIK_TERMINAL_DEADLINE_MS;
    int status;
    while (terminal_now() < deadline) {
        pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child || (result < 0 && errno == ECHILD)) return 0;
        if (result < 0 && errno != EINTR) return -1;
        usleep(10000);
    }
    errno = ETIMEDOUT; return -1;
}
static int terminal_end(pid_t helper) {
    if (!terminal_reap(helper)) return 0;
    kill(helper, SIGTERM);
    if (!terminal_reap(helper)) return 0;
    kill(helper, SIGKILL);
    return terminal_reap(helper);
}
static int terminal_backup(Foreground *f) {
    struct termios actual; memset(&actual, 0, sizeof(actual));
    if (tcsetattr(f->input, TCSANOW, &f->lease.original)) return -1;
    int pending; (void)ioctl(f->input, FIONREAD, &pending);
    if (tcgetattr(f->input, &actual)) return -1;
    if (memcmp(&actual, &f->lease.original, sizeof(actual))) { errno = EIO; return -1; }
    return 0;
}
static int terminal_identity(int fd, uint64_t device, uint64_t inode, int pipe_fd) {
    struct stat value;
    if (fstat(fd, &value) || (uint64_t)value.st_dev != device || (uint64_t)value.st_ino != inode ||
        (pipe_fd && !S_ISFIFO(value.st_mode))) { errno = ESTALE; return -1; }
    return 0;
}
static int terminal_decode(const char *encoded, KvikTerminalLease *lease) {
    if (!encoded || strnlen(encoded, sizeof(*lease) * 2 + 1) != sizeof(*lease) * 2) { errno = EINVAL; return -1; }
    unsigned char *bytes = (unsigned char *)lease;
    for (size_t i = 0; i < sizeof(*lease); ++i) {
        unsigned value = 0;
        for (size_t n = 0; n < 2; ++n) {
            char c = encoded[i * 2 + n];
            if (c >= '0' && c <= '9') value = value * 16 + (unsigned)(c - '0');
            else if (c >= 'a' && c <= 'f') value = value * 16 + (unsigned)(c - 'a' + 10);
            else { errno = EINVAL; return -1; }
        }
        bytes[i] = (unsigned char)value;
    }
    if (lease->magic != KVIK_TERMINAL_MAGIC || lease->version != KVIK_TERMINAL_VERSION || lease->bytes != sizeof(*lease) ||
        lease->owner != getpid() || lease->helper <= 1 || lease->control < 3 || lease->reply < 3 || lease->control == lease->reply || lease->terminal_fd < 3 ||
        lease->terminal_fd == lease->control || lease->terminal_fd == lease->reply) {
        errno = EINVAL; return -1;
    }
    if (terminal_identity(lease->control, lease->control_device, lease->control_inode, 1) ||
        terminal_identity(lease->reply, lease->reply_device, lease->reply_inode, 1)) return -1;
    if ((fcntl(lease->control, F_GETFL) & O_ACCMODE) != O_WRONLY || (fcntl(lease->reply, F_GETFL) & O_ACCMODE) != O_RDONLY) {
        errno = EINVAL; return -1;
    }
    struct stat tty;
    if (!isatty(lease->terminal_fd) || fstat(lease->terminal_fd, &tty) || (uint64_t)tty.st_rdev != lease->tty_device ||
        (uint64_t)tty.st_ino != lease->tty_inode) { errno = ESTALE; return -1; }
    return 0;
}
void *kvik_foreground_open_keys(const char *guardian) {
    if (barrier_ready()) return NULL;
    Foreground *f = foreground_open(0);
    if (!f) return NULL;
    int error = 0, control[2] = {-1, -1}, reply[2] = {-1, -1};
    int input = -1, output = -1, tty = -1, upstream = -1, backup_valid = 0;
    char **owned_environment = NULL;
    if (!isatty(f->input) || !f->own_input || tcgetpgrp(f->input) != getpgrp() ||
        (isatty(f->output) && (!f->own_output))) { error = ENOTTY; goto fail; }
    struct stat tty_status, output_status;
    if (fstat(f->input, &tty_status) || (isatty(f->output) && (fstat(f->output, &output_status) || tty_status.st_rdev != output_status.st_rdev))) {
        error = EINVAL; goto fail;
    }
    /* Parent retains the locked open-file description and original settings before
     * the helper can change anything. This also closes the helper-dies-before-ack gap. */
    if (flock(f->input, LOCK_EX | LOCK_NB) || tcgetattr(f->input, &f->lease.original)) { error = errno; goto fail; }
    if (!kvik_terminal_supported(&f->lease.original)) { error = ENOTSUP; goto fail; }
    backup_valid = 1;
    if (pipe(control) || pipe(reply)) { error = errno; goto fail; }
    for (int i = 0; i < 2; ++i) { fcntl(control[i], F_SETFD, FD_CLOEXEC); fcntl(reply[i], F_SETFD, FD_CLOEXEC); }
    input = fcntl(control[0], F_DUPFD_CLOEXEC, 10); output = fcntl(reply[1], F_DUPFD_CLOEXEC, 10);
    tty = fcntl(f->input, F_DUPFD_CLOEXEC, 10);
    if (input < 0 || output < 0 || tty < 0) { error = errno; goto fail; }
    KvikTerminalLease *lease = &f->lease;
    lease->magic = KVIK_TERMINAL_MAGIC; lease->version = KVIK_TERMINAL_VERSION; lease->bytes = sizeof(*lease);
    lease->owner = getpid(); lease->control = control[1]; lease->reply = reply[0]; lease->terminal_fd = f->input;
    lease->tty_device = tty_status.st_rdev; lease->tty_inode = tty_status.st_ino;
#ifdef __APPLE__
    arc4random_buf(lease->cookie, 16);
#else
    size_t random_bytes = 0;
    while (random_bytes < sizeof(lease->cookie)) {
        ssize_t count = getrandom(lease->cookie + random_bytes, sizeof(lease->cookie) - random_bytes, 0);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) { error = count < 0 ? errno : EIO; goto fail; }
        random_bytes += (size_t)count;
    }
#endif
    char cookie[33], owner[32];
    for (int i = 0; i < 16; ++i) snprintf(cookie + i * 2, 3, "%02x", lease->cookie[i]);
    snprintf(owner, sizeof(owner), "%d", (int)getpid());
    char *arguments[] = {(char *)guardian, "--terminal", owner, cookie, NULL};
    char *environment[] = {"PATH=/usr/bin:/bin", NULL};
    char marker[KVIK_BARRIER_MARKER_BYTES];
    owned_environment = kvik_barrier_environment(environment, &inherited_barrier, marker);
    if (!owned_environment) { error = errno; goto fail; }
    if (inherited_barrier.fd >= 0) {
        upstream = fcntl(inherited_barrier.fd, F_DUPFD_CLOEXEC, 10);
        if (upstream < 0) { error = errno; goto fail; }
    }
    posix_spawn_file_actions_t actions; posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_adddup2(&actions, input, 3); posix_spawn_file_actions_adddup2(&actions, output, 4);
    posix_spawn_file_actions_adddup2(&actions, tty, 5);
    if (upstream >= 0) posix_spawn_file_actions_adddup2(&actions, upstream, KVIK_BARRIER_FD);
    posix_spawnattr_t attributes; posix_spawnattr_init(&attributes);
    sigset_t empty, defaults; sigemptyset(&empty); sigfillset(&defaults);
    posix_spawnattr_setsigmask(&attributes, &empty); posix_spawnattr_setsigdefault(&attributes, &defaults);
    posix_spawnattr_setpgroup(&attributes, 0);
    posix_spawnattr_setflags(&attributes, KVIK_SPAWN_CLOSE_DEFAULT | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETPGROUP);
    error = spawn_close_undeclared(&actions, 1, upstream >= 0);
    if (!error) error = posix_spawn(&lease->helper, guardian, &actions, &attributes, arguments, owned_environment);
    posix_spawnattr_destroy(&attributes); posix_spawn_file_actions_destroy(&actions);
    close(input); input = -1; close(output); output = -1; close(tty); tty = -1;
    close(control[0]); control[0] = -1; close(reply[1]); reply[1] = -1;
    if (upstream >= 0) { close(upstream); upstream = -1; }
    free(owned_environment); owned_environment = NULL;
    if (error) goto fail;
    kvik_pipe_no_sigpipe(lease->control); fcntl(lease->reply, F_SETFL, O_NONBLOCK);
    struct stat cs, rs;
    if (fstat(lease->control, &cs) || fstat(lease->reply, &rs)) { error = errno; goto spawned_fail; }
    lease->control_device = cs.st_dev; lease->control_inode = cs.st_ino;
    lease->reply_device = rs.st_dev; lease->reply_inode = rs.st_ino;
    KvikTerminalReply response;
    if (terminal_receive(lease->reply, &response) || response.operation != KVIK_TERMINAL_ENTER || memcmp(response.cookie, lease->cookie, 16)) {
        error = errno ? errno : EPROTO; goto spawned_fail;
    }
    if (response.error || memcmp(&response.original, &lease->original, sizeof(lease->original))) { error = response.error ? response.error : ESTALE; goto spawned_fail; }
    lease->original = response.original; lease->active = response.active; f->terminal = 1;
    return f;
spawned_fail:
    close(control[1]); control[1] = -1;
    (void)terminal_end(lease->helper);
fail:
    if (input >= 0) close(input);
    if (output >= 0) close(output);
    if (tty >= 0) close(tty);
    if (upstream >= 0) close(upstream);
    free(owned_environment);
    for (int i = 0; i < 2; ++i) { if (control[i] >= 0) close(control[i]); if (reply[i] >= 0) close(reply[i]); }
    if (backup_valid) (void)terminal_backup(f);
    kvik_foreground_close(f); errno = error ? error : EIO; return NULL;
}
void *kvik_foreground_resume_keys(const char *encoded) {
    KvikTerminalLease lease;
    if (terminal_decode(encoded, &lease)) return NULL;
    /* Inheritance is intentional only across the replacement exec itself. */
    if (fcntl(lease.control, F_SETFD, FD_CLOEXEC) || fcntl(lease.reply, F_SETFD, FD_CLOEXEC) ||
        fcntl(lease.terminal_fd, F_SETFD, FD_CLOEXEC)) return NULL;
    Foreground *f = foreground_open(0);
    if (!f) return NULL;
    struct stat tty;
    if (!isatty(f->input) || fstat(f->input, &tty) || (uint64_t)tty.st_rdev != lease.tty_device ||
        (uint64_t)tty.st_ino != lease.tty_inode || tcgetpgrp(f->input) != getpgrp()) { kvik_foreground_close(f); errno = ESTALE; return NULL; }
    close(f->input); f->input = lease.terminal_fd; f->own_input = 1;
    f->lease = lease; f->terminal = 1;
    int status;
    if (waitpid(lease.helper, &status, WNOHANG) != 0) { kvik_foreground_close(f); errno = ECHILD; return NULL; }
    KvikTerminalReply response;
    if (terminal_exchange(&lease, KVIK_TERMINAL_CHECK, &response)) {
        int error = errno; kvik_foreground_close(f); errno = error; return NULL;
    }
    if (memcmp(&response.original, &lease.original, sizeof(lease.original)) ||
        memcmp(&response.active, &lease.active, sizeof(lease.active))) {
        /* A live guardian is authoritative. Never apply a mismatching serialized
         * backup while rejecting a continuation, even though its file is digest-bound. */
        f->lease.original = response.original; f->lease.active = response.active;
        kvik_foreground_close(f); errno = ESTALE; return NULL;
    }
    return f;
}
int kvik_foreground_terminal_lease_bytes(void) { return (int)sizeof(KvikTerminalLease); }
char *kvik_foreground_export_terminal(void *handle) {
    Foreground *f = handle;
    if (!f || !f->terminal) { errno = EINVAL; return NULL; }
    char *encoded = malloc(sizeof(f->lease) * 2 + 1);
    if (!encoded) return NULL;
    const unsigned char *bytes = (const unsigned char *)&f->lease;
    for (size_t i = 0; i < sizeof(f->lease); ++i) snprintf(encoded + i * 2, 3, "%02x", bytes[i]);
    return encoded;
}
int kvik_foreground_terminal_healthy(void *handle) {
    Foreground *f = handle;
    if (!f || !f->terminal) return 1;
    int status; pid_t ended = waitpid(f->lease.helper, &status, WNOHANG | WUNTRACED);
    if (ended == 0) return 1;
    if (ended > 0 && WIFSTOPPED(status)) { kill(f->lease.helper, SIGKILL); (void)terminal_reap(f->lease.helper); }
    (void)terminal_backup(f); errno = EPIPE; return 0;
}

int kvik_foreground_read(void *handle, char *buffer, int capacity) {
    Foreground *f = handle;
    if (!kvik_foreground_terminal_healthy(f)) return -4;
#ifdef __APPLE__
    struct kevent event; struct timespec zero = {0, 0};
    int parent_event = kevent(f->queue, NULL, 0, &event, 1, &zero);
#else
    struct pollfd parent = {f->queue, POLLIN, 0};
    int parent_event = poll(&parent, 1, 0);
#endif
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
    ssize_t count = kvik_pipe_write(f->output, buffer, (size_t)size);
    if (count < 0) return errno == EAGAIN || errno == EINTR ? 0 : -1;
    return (int)count;
}
int kvik_foreground_close_checked(void *handle) {
    if (!handle) return 0;
    Foreground *f = handle;
    int error = 0;
    if (f->terminal) {
        KvikTerminalReply response;
        if (terminal_exchange(&f->lease, KVIK_TERMINAL_CLOSE, &response)) error = errno;
        close(f->lease.control);
        if (terminal_end(f->lease.helper)) error = errno;
        close(f->lease.reply);
        /* Owner backup covers abrupt helper loss; never silently leave raw mode. */
        if (terminal_backup(f)) error = errno;
    }
    if (f->own_input) close(f->input);
    if (f->own_output) close(f->output);
    close(f->queue); free(f);
    if (error) { errno = error; return -1; }
    return 0;
}
void kvik_foreground_close(void *handle) { (void)kvik_foreground_close_checked(handle); }

/* Same-PID handoff: a pending cancellation cannot disappear between old/new handlers.
 * On success the resumed loop installs its handlers before unblocking INT/TERM.
 * On failure every old stream/handler remains usable. No inherited IO flags change. */
int kvik_foreground_exec(const char *path, char *const *argv, char *const *envp) {
    sigset_t blocked, previous;
    sigemptyset(&blocked); sigaddset(&blocked, SIGINT); sigaddset(&blocked, SIGTERM);
    if (sigprocmask(SIG_BLOCK, &blocked, &previous)) return -1;
    if (interrupted) { sigprocmask(SIG_SETMASK, &previous, NULL); errno = EINTR; return -1; }
    if (barrier_ready()) { int error = errno; sigprocmask(SIG_SETMASK, &previous, NULL); errno = error; return -1; }
    char marker[KVIK_BARRIER_MARKER_BYTES];
    char **environment = kvik_barrier_environment(envp, &inherited_barrier, marker);
    if (!environment) { int error = errno; sigprocmask(SIG_SETMASK, &previous, NULL); errno = error; return -1; }
    int flags = inherited_barrier.fd >= 0 ? fcntl(inherited_barrier.fd, F_GETFD) : 0;
    if (inherited_barrier.fd < 0 || (flags >= 0 && !fcntl(inherited_barrier.fd, F_SETFD, flags & ~FD_CLOEXEC)))
        execve(path, argv, environment);
    int error = errno;
    if (inherited_barrier.fd >= 0 && flags >= 0) (void)fcntl(inherited_barrier.fd, F_SETFD, flags);
    free(environment);
    sigprocmask(SIG_SETMASK, &previous, NULL); errno = error; return -1;
}
void kvik_foreground_resume_signals(void) {
    sigset_t blocked;
    sigemptyset(&blocked); sigaddset(&blocked, SIGINT); sigaddset(&blocked, SIGTERM);
    (void)sigprocmask(SIG_UNBLOCK, &blocked, NULL);
}

int kvik_foreground_exec_terminal(const char *path, char *const *argv, char *const *envp, const char *encoded) {
    KvikTerminalLease lease;
    if (terminal_decode(encoded, &lease)) return -1;
    KvikTerminalReply response;
    if (terminal_exchange(&lease, KVIK_TERMINAL_CHECK, &response)) return -1;
    if (memcmp(&response.original, &lease.original, sizeof(lease.original)) ||
        memcmp(&response.active, &lease.active, sizeof(lease.active))) { errno = ESTALE; return -1; }
    int descriptors[] = {lease.control, lease.reply, lease.terminal_fd};
    int flags[3] = {-1, -1, -1};
    for (int i = 0; i < 3; ++i) {
        flags[i] = fcntl(descriptors[i], F_GETFD);
        if (flags[i] < 0 || fcntl(descriptors[i], F_SETFD, flags[i] & ~FD_CLOEXEC)) {
            int error = errno;
            for (int n = 0; n <= i; ++n) if (flags[n] >= 0) fcntl(descriptors[n], F_SETFD, flags[n]);
            errno = error; return -1;
        }
    }
    kvik_foreground_exec(path, argv, envp);
    int error = errno;
    for (int i = 0; i < 3; ++i) fcntl(descriptors[i], F_SETFD, flags[i]);
    errno = error; return -1;
}
