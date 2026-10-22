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
static void report(int code, int timeout, int cancelled) {
    int result[3] = {code, timeout, cancelled};
    (void)write(4, result, sizeof(result));
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

int main(int argc, char **argv) {
    if (argc > 1 && !strcmp(argv[1], "--terminal")) return terminal_main(argc, argv);
    if (argc < 6) return 125;
    signal(SIGPIPE, SIG_IGN);
    struct sigaction action = {0}; action.sa_handler = on_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, NULL); sigaction(SIGTERM, &action, NULL); sigaction(SIGHUP, &action, NULL);
    fcntl(3, F_SETFD, FD_CLOEXEC); fcntl(4, F_SETFD, FD_CLOEXEC);
    char *timeout_end = NULL;
    errno = 0;
    long long timeout_ms = strtoll(argv[4], &timeout_end, 10), start = now_ms();
    if (argv[4][0] < '0' || argv[4][0] > '9' || errno == ERANGE ||
            !timeout_end || *timeout_end || timeout_ms < 0) { report(125, 0, 0); return 0; }
    if (owner_gone()) { report(130, 0, 1); return 0; }
    pid_t child = fork();
    if (child < 0) { report(125, 0, 0); return 0; }
    if (!child) {
        /* Establish ownership before any adapter code can run. */
        if (setpgid(0, 0)) _exit(125);
        close(3); close(4);
        signal(SIGINT, SIG_DFL); signal(SIGTERM, SIG_DFL); signal(SIGHUP, SIG_DFL); signal(SIGPIPE, SIG_DFL);
        if (chdir(argv[1])) _exit(125);
        int in = open("/dev/null", O_RDONLY);
        int out = open(argv[2], O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
        int err = open(argv[3], O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
        if (in < 0 || out < 0 || err < 0) _exit(125);
        if (dup2(in, 0) < 0 || dup2(out, 1) < 0 || dup2(err, 2) < 0) _exit(125);
        if (in > 2) close(in);
        if (out > 2) close(out);
        if (err > 2) close(err);
        execve(argv[5], argv + 5, environ);
        _exit(127);
    }
    /* Both sides set the group: cancellation can never race an ungrouped worker. */
    if (setpgid(child, child) && errno != EACCES && errno != ESRCH) {
        kill(child, SIGKILL); waitpid(child, NULL, 0); report(125, 0, 0); return 0;
    }
    int code = 125, timed_out = 0, cancelled = 0;
    for (;;) {
        siginfo_t info = {0};
        /* Keep the leader unreaped until group cleanup, reserving its PID/PGID. */
        if (waitid(P_PID, child, &info, WEXITED | WNOHANG | WNOWAIT) == 0 && info.si_pid == child) {
            code = info.si_code == CLD_EXITED ? info.si_status : 128 + info.si_status;
            break;
        }
        if (owner_gone()) { cancelled = 1; code = 130; break; }
        if (timeout_ms > 0 && now_ms() - start >= timeout_ms) { timed_out = 1; code = 124; break; }
        usleep(10000);
    }
    /* Also clean descendants after ordinary leader exit; success cannot leave servers behind. */
    kill(-child, SIGTERM);
    long long deadline = now_ms() + 200;
    while (now_ms() < deadline) usleep(10000);
    kill(-child, SIGKILL);
    while (waitpid(child, NULL, 0) < 0 && errno == EINTR) {}
    report(code, timed_out, cancelled);
    return 0;
}
