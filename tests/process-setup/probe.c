#include "process.h"
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc != 4) return 90;
    const char *root = argv[2], *mode = argv[3];
    char out[4096], err[4096], missing[4096], ready[4096], variable[128], root_env[4096];
    snprintf(out, sizeof(out), "%s/stdout", root); snprintf(err, sizeof(err), "%s/stderr", root);
    snprintf(missing, sizeof(missing), "%s/missing", root); snprintf(ready, sizeof(ready), "%s/setup-ready", root);
    snprintf(variable, sizeof(variable), "KVIK_TEST_SETUP_MODE=%s", mode);
    snprintf(root_env, sizeof(root_env), "KVIK_TEST_SETUP_ROOT=%s", root);
    char *env[] = {"PATH=/usr/bin:/bin", variable, root_env, NULL};
    char *args[] = {"/bin/sh", "-c", !strcmp(mode, "exit125") ? "exit 125" : !strcmp(mode, "exit127") ? "exit 127" :
        !strcmp(mode, "signal") ? "kill -TERM $$" :
        !strcmp(mode, "pause") ? "echo $$ > \"$KVIK_TEST_SETUP_ROOT/paused\"; kill -STOP $$; exit 0" : "exit 0", NULL};
    if (!strcmp(mode, "exec")) args[0] = missing;
    const char *cwd = !strcmp(mode, "cwd") || !strcmp(mode, "cancel") || !strcmp(mode, "timeout") || strstr(mode, "status") ? missing : root;
    void *owned = kvik_process_start(argv[1], cwd, out, err, !strcmp(mode, "timeout") ? 100 : 0, args, env);
    if (!owned) { fprintf(stderr, "start errno=%d\n", errno); return 91; }
    int stage, error;
    if (kvik_process_setup_error(owned, &stage, &error) != -1 || errno != EINVAL) return 92;
    int code = -1, timed = -1, cancelled = -1, result = 0;
    int pause_ticks = 0, resumed = 0;
    char paused[4096]; snprintf(paused, sizeof(paused), "%s/paused", root);
    for (int i = 0; i < 10000 && !result; ++i) {
        if (!strcmp(mode, "cancel") && !access(ready, F_OK)) kvik_process_cancel(owned);
        if (!strcmp(mode, "pause") && !resumed && !access(paused, F_OK) && ++pause_ticks == 100) {
            FILE *file = fopen(paused, "r"); int child = 0;
            if (!file || fscanf(file, "%d", &child) != 1 || child <= 1) return 94;
            fclose(file); if (kill(child, SIGCONT)) return 95; resumed = 1;
        }
        result = kvik_process_poll(owned, &code, &timed, &cancelled);
        if (!result) usleep(1000);
    }
    if (!result) { kvik_process_cancel(owned); kvik_process_close(owned); return 93; }
    if (!strcmp(mode, "pause") && !resumed) { kvik_process_close(owned); return 96; }
    stage = -1; error = -1;
    int setup = kvik_process_setup_error(owned, &stage, &error);
    int closed = kvik_process_close(owned);
    printf("{\"poll\":%d,\"exitCode\":%d,\"timedOut\":%d,\"cancelled\":%d,\"setup\":%d,\"stage\":%d,\"errno\":%d,\"close\":%d}\n",
           result, code, timed, cancelled, setup, stage, error, closed);
    return 0;
}
