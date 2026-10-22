#include "process.h"
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
extern char **environ;
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    if (!strcmp(argv[1], "resumed")) {
        if (kvik_interrupt_install()) return 3;
        kvik_foreground_resume_signals();
        if (kvik_interrupt_requested() != SIGTERM) return 4;
        puts("PASS pending TERM survived exec"); return 0;
    }
    if (kvik_interrupt_install()) return 5;
    if (!strcmp(argv[1], "failure")) {
        char *args[] = {"/missing/kvik", NULL};
        if (kvik_foreground_exec(args[0], args, environ) != -1 || errno != ENOENT) return 6;
        raise(SIGINT);
        if (kvik_interrupt_requested() != SIGINT) return 7;
        puts("PASS failed exec preserved old signal handlers"); return 0;
    }
    sigset_t mask; sigemptyset(&mask); sigaddset(&mask, SIGTERM);
    if (sigprocmask(SIG_BLOCK, &mask, NULL)) return 8;
    raise(SIGTERM);
    char *args[] = {argv[0], "resumed", NULL};
    kvik_foreground_exec(argv[0], args, environ);
    return 9;
}
