#define KVIK_TERMINAL_INTERNAL
#include "process.h"
/* Per-process foreground ownership guardian. No sockets, persistent state, or reuse.
 * Protocol: descriptor 3 reaches EOF on owner loss/cancel; descriptor 4 reports three ints.
 * Only this small C executable forks. The Kotlin runtime never executes after fork. */
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysctl.h>
#include <sys/proc.h>

extern char **environ;
static volatile sig_atomic_t interrupted;
static void on_signal(int sig) { interrupted = sig; }
static long long now_ms(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000LL + t.tv_nsec / 1000000;
}
static int owner_gone(void) {
    struct pollfd fd = {3, POLLIN | POLLHUP, 0};
    if (poll(&fd, 1, 0) > 0) return 1;
    return interrupted != 0;
}
typedef struct { int stage, error; } SetupFailure;
static void report_setup(int code, int timeout, int cancelled, SetupFailure setup) {
    int result[KVIK_PROCESS_RESULT_INTS] = {KVIK_PROCESS_RESULT_MAGIC, code, timeout, cancelled, setup.stage, setup.error};
    (void)write(4, result, sizeof(result));
}
static void report(int code, int timeout, int cancelled) { report_setup(code, timeout, cancelled, (SetupFailure){0, 0}); }
static int setup_error(SetupFailure *failure, int stage) {
    failure->stage = stage; failure->error = errno; return -1;
}
/* The child emits at most one atomic PIPE_BUF-sized frame before exec. The write
 * end is CLOEXEC, so successful exec is empty EOF, including an executable exit125. */
static void child_setup_error(int fd, int stage, int error) {
    int value[KVIK_PROCESS_SETUP_INTS] = {KVIK_PROCESS_SETUP_MAGIC, stage, error};
    ssize_t written;
    do { written = write(fd, value, sizeof(value)); } while (written < 0 && errno == EINTR);
    _exit(stage == KVIK_SETUP_EXEC ? 127 : 125);
}
static int setup_pipe(int *read_fd, int *write_fd, SetupFailure *failure, int pipe_stage, int read_stage, int write_stage) {
    int pair[2];
    if (pipe(pair)) return setup_error(failure, pipe_stage);
    *read_fd = fcntl(pair[0], F_DUPFD_CLOEXEC, 10);
    if (*read_fd < 0) setup_error(failure, read_stage);
    else {
        *write_fd = fcntl(pair[1], F_DUPFD_CLOEXEC, 10);
        if (*write_fd < 0) setup_error(failure, write_stage);
    }
    close(pair[0]); close(pair[1]);
    return failure->stage ? -1 : 0;
}

/* A terminal restorer exists only while its owner's pipe is open. It never starts
 * workers, reads terminal input or writes terminal output. Its own process group
 * prevents foreground Ctrl-C from killing the restoration boundary. */
static int terminal_reply(int operation, int error, const unsigned char *cookie,
                          const struct termios *original, const struct termios *active) {
    KvikTerminalReply reply; memset(&reply, 0, sizeof(reply));
    reply.operation = operation; reply.error = error;
    memcpy(reply.cookie, cookie, sizeof(reply.cookie));
    if (original) reply.original = *original;
    if (active) reply.active = *active;
    return write(4, &reply, sizeof(reply)) == sizeof(reply) ? 0 : -1;
}
static int terminal_restore(const struct termios *original) {
    struct termios actual; memset(&actual, 0, sizeof(actual));
    if (tcsetattr(5, TCSANOW, original)) return -1;
    int pending; (void)ioctl(5, FIONREAD, &pending);
    if (tcgetattr(5, &actual)) return -1;
    if (memcmp(original, &actual, sizeof(actual))) { errno = EIO; return -1; }
    return 0;
}
static int terminal_main(int argc, char **argv) {
    if (argc != 4 || strlen(argv[3]) != 32) return 125;
    char *end = NULL; long owner = strtol(argv[2], &end, 10);
    if (!end || *end || owner <= 1 || owner != getppid()) return 125;
    unsigned char cookie[16];
    for (int i = 0; i < 16; ++i) {
        char digits[3] = {argv[3][i * 2], argv[3][i * 2 + 1], 0};
        for (int n = 0; n < 2; ++n) if (!((digits[n] >= '0' && digits[n] <= '9') || (digits[n] >= 'a' && digits[n] <= 'f'))) return 125;
        unsigned long value = strtoul(digits, &end, 16);
        if (*end) return 125;
        cookie[i] = (unsigned char)value;
    }
    signal(SIGPIPE, SIG_IGN); signal(SIGTTOU, SIG_IGN);
    struct sigaction action; memset(&action, 0, sizeof(action));
    action.sa_handler = on_signal; sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, NULL); sigaction(SIGTERM, &action, NULL); sigaction(SIGHUP, &action, NULL);
    fcntl(3, F_SETFD, FD_CLOEXEC); fcntl(4, F_SETFD, FD_CLOEXEC); fcntl(5, F_SETFD, FD_CLOEXEC);
    struct termios original, active; memset(&original, 0, sizeof(original)); memset(&active, 0, sizeof(active));
    int error = 0, changed = 0;
    /* Lock before inspecting mode: two concurrent canonical checks are not ownership. */
    if (!isatty(5) || flock(5, LOCK_EX | LOCK_NB)) error = errno ? errno : ENOTTY;
    else if (tcgetpgrp(5) != getpgid((pid_t)owner)) error = EPERM;
    else if (tcgetattr(5, &original)) error = errno;
    else if (!kvik_terminal_supported(&original)) error = ENOTSUP;
    else {
        active = original;
        active.c_lflag &= ~(ICANON | ECHO | ECHONL | IEXTEN);
        active.c_iflag &= ~IXON;
        active.c_cc[VMIN] = 1; active.c_cc[VTIME] = 0;
        active.c_cc[VSUSP] = _POSIX_VDISABLE;
#ifdef VDSUSP
        active.c_cc[VDSUSP] = _POSIX_VDISABLE;
#endif
        if (owner_gone() || getppid() != owner) error = ECANCELED;
        else if (tcsetattr(5, TCSANOW, &active)) error = errno;
        else changed = 1;
    }
    if (terminal_reply(KVIK_TERMINAL_ENTER, error, cookie, &original, &active) || error) {
        if (changed) (void)terminal_restore(&original);
        return error ? 125 : 0;
    }
    for (;;) {
        struct pollfd control = {3, POLLIN | POLLHUP, 0};
        int ready = poll(&control, 1, 100);
        if (interrupted || getppid() != owner || (ready < 0 && errno != EINTR)) break;
        if (ready <= 0) continue;
        KvikTerminalRequest request;
        ssize_t size = read(3, &request, sizeof(request));
        if (size != sizeof(request) || request.owner != owner || memcmp(request.cookie, cookie, 16)) break;
        if (request.operation == KVIK_TERMINAL_CLOSE) {
            error = terminal_restore(&original) ? errno : 0;
            (void)terminal_reply(KVIK_TERMINAL_CLOSE, error, cookie, &original, &active);
            return error ? 125 : 0;
        }
        if (request.operation != KVIK_TERMINAL_CHECK) break;
        struct termios actual; memset(&actual, 0, sizeof(actual));
        error = tcgetattr(5, &actual) ? errno : memcmp(&active, &actual, sizeof(active)) ? ESTALE : 0;
        if (terminal_reply(KVIK_TERMINAL_CHECK, error, cookie, &original, &active) || error) break;
    }
    return terminal_restore(&original) ? 125 : 0;
}

/* Bounded per-level acknowledgement: empty EOF is success, any byte poisons it.
 * Writers are nonblocking; EAGAIN already means the pipe is nonempty, and EPIPE
 * means the upstream owner is gone. No signal path performs this IO. */
static KvikBarrier upstream_barrier = {.fd = -1};
/* Keep allocation bounded and never equate a query error with an empty group. */
static int group_quiet(pid_t group) {
    int query[] = {CTL_KERN, KERN_PROC, KERN_PROC_PGRP, group};
    size_t size = 0;
    if (sysctl(query, 4, NULL, &size, NULL, 0)) return -1;
    const size_t limit = 4 * 1024 * 1024;
    if (size > limit - 32 * sizeof(struct kinfo_proc)) { errno = EOVERFLOW; return -1; }
    size += 32 * sizeof(struct kinfo_proc);
    struct kinfo_proc *members = malloc(size);
    if (!members) return -1;
    if (sysctl(query, 4, members, &size, NULL, 0)) {
        int error = errno; free(members); errno = error;
        return error == ENOMEM || error == EINTR ? 0 : -1;
    }
    if (size % sizeof(*members)) { free(members); errno = EPROTO; return -1; }
    int quiet = 1;
    for (size_t i = 0; i < size / sizeof(*members); ++i)
        if (members[i].kp_proc.p_stat != SZOMB) quiet = 0;
    free(members); return quiet;
}
static int process_main(int argc, char **argv) {
    if (argc < 6) return 125;
    struct sigaction action = {0}; action.sa_handler = on_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, NULL); sigaction(SIGTERM, &action, NULL); sigaction(SIGHUP, &action, NULL);
    fcntl(3, F_SETFD, FD_CLOEXEC); fcntl(4, F_SETFD, FD_CLOEXEC);
    char *timeout_end = NULL;
    errno = 0;
    long long timeout_ms = strtoll(argv[4], &timeout_end, 10), start = now_ms();
    if (argv[4][0] < '0' || argv[4][0] > '9' || errno == ERANGE ||
            !timeout_end || *timeout_end || timeout_ms < 0) {
        report_setup(125, 0, 0, (SetupFailure){KVIK_SETUP_TIMEOUT_ARGUMENT, errno == ERANGE ? ERANGE : EINVAL}); return 0;
    }
    if (owner_gone()) { report(130, 0, 1); return 0; }
    int barrier_read = -1, barrier_write = -1, status_read = -1, status_write = -1;
    SetupFailure setup = {0, 0}; char **environment = NULL;
    if (setup_pipe(&barrier_read, &barrier_write, &setup, KVIK_SETUP_BARRIER_PIPE,
                   KVIK_SETUP_BARRIER_READ_DUP, KVIK_SETUP_BARRIER_WRITE_DUP)) goto early_setup_failure;
    struct stat barrier_stat;
    if (fstat(barrier_write, &barrier_stat)) { setup_error(&setup, KVIK_SETUP_BARRIER_STAT); goto early_setup_failure; }
    if (fcntl(barrier_read, F_SETFL, O_NONBLOCK)) { setup_error(&setup, KVIK_SETUP_BARRIER_READ_FLAGS); goto early_setup_failure; }
    if (fcntl(barrier_write, F_SETFL, O_NONBLOCK)) { setup_error(&setup, KVIK_SETUP_BARRIER_WRITE_FLAGS); goto early_setup_failure; }
    KvikBarrier downstream = {barrier_write, (uint64_t)barrier_stat.st_dev, (uint64_t)barrier_stat.st_ino};
    char marker[KVIK_BARRIER_MARKER_BYTES];
    environment = kvik_barrier_environment(environ, &downstream, marker);
    if (!environment) { setup_error(&setup, KVIK_SETUP_BARRIER_ENVIRONMENT); goto early_setup_failure; }
    if (setup_pipe(&status_read, &status_write, &setup, KVIK_SETUP_STATUS_PIPE,
                   KVIK_SETUP_STATUS_READ_DUP, KVIK_SETUP_STATUS_WRITE_DUP)) goto early_setup_failure;
    if (fcntl(status_read, F_SETFL, O_NONBLOCK)) { setup_error(&setup, KVIK_SETUP_STATUS_READ_FLAGS); goto early_setup_failure; }
    pid_t child = fork();
    if (child < 0) { setup_error(&setup, KVIK_SETUP_FORK); goto early_setup_failure; }
    if (!child) {
        if (setpgid(0, 0)) child_setup_error(status_write, KVIK_SETUP_CHILD_GROUP, errno);
        close(3); close(4); close(barrier_read); close(status_read);
        if (upstream_barrier.fd >= 0) close(upstream_barrier.fd);
        if (dup2(barrier_write, KVIK_BARRIER_FD) < 0) child_setup_error(status_write, KVIK_SETUP_BARRIER_BIND, errno);
        close(barrier_write);
        signal(SIGINT, SIG_DFL); signal(SIGTERM, SIG_DFL); signal(SIGHUP, SIG_DFL); signal(SIGPIPE, SIG_DFL);
        if (chdir(argv[1])) child_setup_error(status_write, KVIK_SETUP_WORKING_DIRECTORY, errno);
        int in = open("/dev/null", O_RDONLY);
        if (in < 0) child_setup_error(status_write, KVIK_SETUP_STDIN_OPEN, errno);
        int out = open(argv[2], O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
        if (out < 0) child_setup_error(status_write, KVIK_SETUP_STDOUT_OPEN, errno);
        int err = open(argv[3], O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
        if (err < 0) child_setup_error(status_write, KVIK_SETUP_STDERR_OPEN, errno);
        if (dup2(in, 0) < 0) child_setup_error(status_write, KVIK_SETUP_STDIN_BIND, errno);
        if (dup2(out, 1) < 0) child_setup_error(status_write, KVIK_SETUP_STDOUT_BIND, errno);
        if (dup2(err, 2) < 0) child_setup_error(status_write, KVIK_SETUP_STDERR_BIND, errno);
        if (in > 2) close(in);
        if (out > 2) close(out);
        if (err > 2) close(err);
        execve(argv[5], argv + 5, environment);
        child_setup_error(status_write, KVIK_SETUP_EXEC, errno);
    }
    /* The guardian never retains its own downstream writer. */
    close(barrier_write); close(status_write); free(environment);
    int code = 125, timed_out = 0, cancelled = 0;
    int setup_failed = setpgid(child, child) && errno != EACCES && errno != ESRCH;
    if (setup_failed) { setup_error(&setup, KVIK_SETUP_PARENT_GROUP); kill(child, SIGKILL); }
    while (!setup_failed) {
        siginfo_t info = {0};
        if (waitid(P_PID, child, &info, WEXITED | WNOHANG | WNOWAIT) == 0 && info.si_pid == child &&
            (info.si_code == CLD_EXITED || info.si_code == CLD_KILLED || info.si_code == CLD_DUMPED)) {
            code = info.si_code == CLD_EXITED ? info.si_status : 128 + info.si_status; break;
        }
        if (owner_gone()) { cancelled = 1; code = 130; break; }
        if (timeout_ms > 0 && now_ms() - start >= timeout_ms) { timed_out = 1; code = 124; break; }
        usleep(10000);
    }
    kill(-child, SIGTERM);
    long long deadline = now_ms() + 200;
    while (now_ms() < deadline) usleep(10000);
    kill(-child, SIGKILL);
    int poisoned = 0, eof = 0, quiet = 0;
    /* Drain while waiting; never block waitpid ahead of nested acknowledgements. */
    while (!eof || !quiet) {
        if (!eof) {
            char bytes[256]; ssize_t count = read(barrier_read, bytes, sizeof(bytes));
            if (count > 0) poisoned = 1;
            else if (!count) eof = 1;
            else if (errno != EAGAIN && errno != EINTR) { poisoned = 1; eof = 1; }
        }
        if (!quiet) {
            int observed = group_quiet(child);
            if (observed < 0) { poisoned = 1; quiet = 1; }
            else quiet = observed;
        }
        if (!eof || !quiet) usleep(10000);
    }
    close(barrier_read);
    pid_t reaped;
    do { reaped = waitpid(child, NULL, 0); } while (reaped < 0 && errno == EINTR);
    if (reaped != child) poisoned = 1;
    unsigned char setup_bytes[sizeof(int) * KVIK_PROCESS_SETUP_INTS + 1]; size_t setup_size = 0;
    for (;;) {
        ssize_t count = read(status_read, setup_bytes + setup_size, sizeof(setup_bytes) - setup_size);
        if (count > 0) { setup_size += count; if (setup_size == sizeof(setup_bytes)) { poisoned = 1; break; } }
        else if (!count) break;
        else if (errno != EINTR) { poisoned = 1; break; }
    }
    close(status_read);
    if (setup_size) {
        int value[KVIK_PROCESS_SETUP_INTS] = {0};
        if (setup_size != sizeof(value)) poisoned = 1;
        else {
            memcpy(value, setup_bytes, sizeof(value));
            if (value[0] != KVIK_PROCESS_SETUP_MAGIC || value[1] < KVIK_SETUP_CHILD_GROUP ||
                value[1] > KVIK_SETUP_EXEC || value[2] <= 0) poisoned = 1;
            else if (!setup.stage) setup = (SetupFailure){value[1], value[2]};
        }
    }
    if (poisoned) return 125;
    report_setup(code, timed_out, cancelled, setup); return 0;
early_setup_failure:
    free(environment);
    if (barrier_read >= 0) close(barrier_read);
    if (barrier_write >= 0) close(barrier_write);
    if (status_read >= 0) close(status_read);
    if (status_write >= 0) close(status_write);
    report_setup(125, 0, 0, setup); return 0;
}
int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);
    if (kvik_barrier_capture(&upstream_barrier)) return 125;
    int result = argc > 1 && !strcmp(argv[1], "--terminal") ? terminal_main(argc, argv) : process_main(argc, argv);
    if (result) kvik_barrier_poison(&upstream_barrier);
    /* Release upstream only through process exit, after all user-space cleanup. */
    return result;
}
