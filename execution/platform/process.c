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
    if (write(lease->control, &request, sizeof(request)) != sizeof(request) || terminal_receive(lease->reply, reply)) return -1;
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
    Foreground *f = foreground_open(0);
    if (!f) return NULL;
    int error = 0, control[2] = {-1, -1}, reply[2] = {-1, -1};
    int input = -1, output = -1, tty = -1, backup_valid = 0;
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
    arc4random_buf(lease->cookie, 16);
    char cookie[33], owner[32];
    for (int i = 0; i < 16; ++i) snprintf(cookie + i * 2, 3, "%02x", lease->cookie[i]);
    snprintf(owner, sizeof(owner), "%d", (int)getpid());
    char *arguments[] = {(char *)guardian, "--terminal", owner, cookie, NULL};
    char *environment[] = {"PATH=/usr/bin:/bin", NULL};
    posix_spawn_file_actions_t actions; posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_adddup2(&actions, input, 3); posix_spawn_file_actions_adddup2(&actions, output, 4);
    posix_spawn_file_actions_adddup2(&actions, tty, 5);
    posix_spawnattr_t attributes; posix_spawnattr_init(&attributes);
    sigset_t empty, defaults; sigemptyset(&empty); sigfillset(&defaults);
    posix_spawnattr_setsigmask(&attributes, &empty); posix_spawnattr_setsigdefault(&attributes, &defaults);
    posix_spawnattr_setpgroup(&attributes, 0);
    posix_spawnattr_setflags(&attributes, POSIX_SPAWN_CLOEXEC_DEFAULT | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETPGROUP);
    error = posix_spawn(&lease->helper, guardian, &actions, &attributes, arguments, environment);
    posix_spawnattr_destroy(&attributes); posix_spawn_file_actions_destroy(&actions);
    close(input); input = -1; close(output); output = -1; close(tty); tty = -1;
    close(control[0]); control[0] = -1; close(reply[1]); reply[1] = -1;
    if (error) goto fail;
    fcntl(lease->control, F_SETNOSIGPIPE, 1); fcntl(lease->reply, F_SETFL, O_NONBLOCK);
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
    execve(path, argv, envp);
    int error = errno;
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
