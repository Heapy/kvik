#include "process.h"
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void record(const char *root, const char *kind, int depth, int value) {
    char path[4096]; snprintf(path, sizeof(path), "%s/%s-%d", root, kind, depth);
    FILE *out = fopen(path, "w"); if (!out) exit(90);
    fprintf(out, "%d\n", value); fclose(out);
}
static int exists(const char *root, const char *kind, int depth) {
    char path[4096]; snprintf(path, sizeof(path), "%s/%s-%d", root, kind, depth);
    return access(path, F_OK) == 0;
}
int main(int argc, char **argv) {
    if (argc != 7) return 90;
    const char *helper = argv[1], *root = argv[2], *mode = argv[4], *self = argv[0];
    int depth = atoi(argv[3]); long long timeout = atoll(argv[5]);
    char *environment[] = {"PATH=/usr/bin:/bin", NULL};
    signal(SIGTERM, SIG_IGN);
    record(root, "owner", depth, getpid());
    /* Constructor must consume the inherited marker in every cooperative owner. */
    if (getenv("KVIK_PROCESS_BARRIER_V1")) return 91;
    if (depth == 0) {
        for (int fd = 3; fd < 256; ++fd) {
            if (fd != 6 && fcntl(fd, F_GETFD) >= 0) return 99;
        }
        record(root, "only-barrier-inherited", 0, 1);
        pid_t child = fork(); if (child < 0) return 92;
        if (!child) { record(root, "leaf-child", 0, getpid()); for (;;) pause(); }
        record(root, "leaf-ready", 0, getpid()); for (;;) pause();
    }
    if (!strcmp(mode, "exec") && depth == 1) {
        char *args[] = {(char *)self, (char *)helper, (char *)root, argv[3], "wait", argv[5], argv[6], NULL};
        kvik_foreground_exec(self, args, environment); return 93;
    }
    if (!strcmp(mode, "failed-exec") && depth == 1) {
        char *args[] = {"/nonexistent-kvik-test-program", NULL};
        if (kvik_foreground_exec(args[0], args, environment) != -1 || errno != ENOENT) return 94;
        record(root, "failed-exec", depth, 1);
    }
    char next[32], out[4096], err[4096];
    snprintf(next, sizeof(next), "%d", depth - 1);
    snprintf(out, sizeof(out), "%s/out-%d", root, depth); snprintf(err, sizeof(err), "%s/err-%d", root, depth);
    char *args[] = {(char *)self, (char *)helper, (char *)root, next, (char *)mode, "0", argv[6], NULL};
    char *override[] = {"PATH=/usr/bin:/bin", "KVIK_PROCESS_BARRIER_V1=6:0:0", NULL};
    const char *actual_helper = (!strcmp(mode, "poison") && depth == 1) ? argv[6] : helper;
    if ((!strcmp(mode, "bad-result") || !strcmp(mode, "nested-bad-result")) && depth == 1) actual_helper = argv[6];
    void *owned = kvik_process_start(actual_helper, root, out, err, timeout, args,
                                    !strcmp(mode, "override") ? override : environment);
    if (!owned) { record(root, "refused", depth, errno); return !strcmp(mode, "override") || !strcmp(mode, "invalid") ? 0 : 95; }
    if (!strcmp(mode, "invalid")) return 96;
    int code, timed, cancelled, result;
    for (;;) {
        if (exists(root, "exit", depth)) _exit(7);
        if (exists(root, "cancel", depth)) kvik_process_cancel(owned);
        result = kvik_process_poll(owned, &code, &timed, &cancelled);
        if (result) break;
        usleep(1000);
    }
    int closed = kvik_process_close(owned);
    record(root, "done", depth, result < 0 ? -1 : code);
    record(root, "close", depth, closed);
    if (result < 0) return closed == -1 ? 42 : 97;
    return closed == 0 ? 0 : 98;
}
