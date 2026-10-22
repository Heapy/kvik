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

int main(int argc, char **argv) {
    if (argc < 6) return 125;
    signal(SIGPIPE, SIG_IGN);
    struct sigaction action = {0}; action.sa_handler = on_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, NULL); sigaction(SIGTERM, &action, NULL); sigaction(SIGHUP, &action, NULL);
    fcntl(3, F_SETFD, FD_CLOEXEC); fcntl(4, F_SETFD, FD_CLOEXEC);
    long long timeout_ms = strtoll(argv[4], NULL, 10), start = now_ms();
    if (timeout_ms <= 0 || owner_gone()) { report(130, 0, 1); return 0; }
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
        if (now_ms() - start >= timeout_ms) { timed_out = 1; code = 124; break; }
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
