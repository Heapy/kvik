#define KVIK_TERMINAL_INTERNAL
#include "process.h"
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static volatile sig_atomic_t pipe_signals;
static void pipe_signal(int signal_number) { (void)signal_number; ++pipe_signals; }
int main(int argc, char **argv) {
    if (argc != 2) return 90;
    if (!strcmp(argv[1], "directory")) {
        char *path = kvik_executable_directory();
        if (!path) return 91;
        puts(path); free(path); return 0;
    }
    if (!strcmp(argv[1], "pipe")) {
        int pair[2]; if (pipe(pair)) return 92;
        if (kvik_pipe_no_sigpipe(pair[1])) return 93;
        struct sigaction action = {0}, previous;
        action.sa_handler = pipe_signal; sigemptyset(&action.sa_mask);
        if (sigaction(SIGPIPE, &action, &previous)) return 94;
        close(pair[0]);
        if (kvik_pipe_write(pair[1], "!", 1) != -1 || errno != EPIPE || pipe_signals) return 95;
        sigset_t blocked, old, pending; sigemptyset(&blocked); sigaddset(&blocked, SIGPIPE);
        if (pthread_sigmask(SIG_BLOCK, &blocked, &old) || raise(SIGPIPE)) return 96;
        if (kvik_pipe_write(pair[1], "!", 1) != -1 || errno != EPIPE || sigpending(&pending) || !sigismember(&pending, SIGPIPE)) return 97;
        int received; if (sigwait(&blocked, &received) || received != SIGPIPE) return 98;
        if (pthread_sigmask(SIG_SETMASK, &old, NULL) || sigaction(SIGPIPE, &previous, NULL)) return 99;
        close(pair[1]); return 0;
    }
    if (!strcmp(argv[1], "parent")) {
        void *foreground = kvik_foreground_open();
        if (!foreground) { perror("foreground"); return 100; }
        puts("READY"); fflush(stdout);
        for (int i = 0; i < 1000; ++i) {
            char byte; int result = kvik_foreground_read(foreground, &byte, 1);
            if (result == -3) { kvik_foreground_close(foreground); puts("PARENT_GONE"); return 0; }
            if (result < 0) return 101;
            usleep(10000);
        }
        return 102;
    }
    return 103;
}
