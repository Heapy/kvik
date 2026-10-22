#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
/* Exec-time fixtures exercise capture before process.c's constructor runs. */
int main(int argc, char **argv) {
    if (argc != 9) return 90;
    if (!strcmp(argv[1], "collision")) {
        for (int fd = 3; fd <= 9; ++fd) {
            int source = open("/dev/null", O_RDONLY);
            if (source < 0 || dup2(source, fd) < 0) return 91;
            if (source != fd) close(source);
        }
        unsetenv("KVIK_PROCESS_BARRIER_V1");
    } else {
        int pipe_fds[2]; if (pipe(pipe_fds)) return 92;
        int source = pipe_fds[1];
        if (!strcmp(argv[1], "read-only")) source = pipe_fds[0];
        if (!strcmp(argv[1], "regular")) source = open(argv[2], O_RDONLY);
        if (source < 0 || dup2(source, 6) < 0) return 93;
        if (strcmp(argv[1], "blocking")) fcntl(6, F_SETFL, O_NONBLOCK);
        struct stat value; if (fstat(6, &value)) return 94;
        char marker[128];
        snprintf(marker, sizeof(marker), "6:%llu:%llu", (unsigned long long)value.st_dev,
                 (unsigned long long)value.st_ino + (!strcmp(argv[1], "identity") ? 1 : 0));
        setenv("KVIK_PROCESS_BARRIER_V1", marker, 1);
    }
    execv(argv[2], argv + 2); return 95;
}
