/* Test-only syscall/report interception. No environment fault hook enters production. */
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static int mode_is(const char *expected) { const char *mode = getenv("KVIK_TEST_SETUP_MODE"); return mode && !strcmp(mode, expected); }
static pid_t setup_test_fork(void) {
    if (mode_is("fork")) { errno = EAGAIN; return -1; }
    return fork();
}
static int setup_test_setpgid(pid_t pid, pid_t group) {
    if ((pid && mode_is("parent-group")) || (!pid && mode_is("child-group"))) { errno = EPERM; return -1; }
    return setpgid(pid, group);
}
static ssize_t setup_test_write(int fd, const void *bytes, size_t size) {
    if (size == sizeof(int) * 3 && ((const int *)bytes)[0] == 0x4b565331) {
        if (mode_is("partial-status")) { (void)write(fd, bytes, sizeof(int)); return (ssize_t)size; }
        if (mode_is("unknown-status")) { int bad[3] = {0x4b565331, 999, 2}; return write(fd, bad, sizeof(bad)); }
        if (mode_is("extra-status")) { int bad[4] = {0x4b565331, 17, 2, 0}; (void)write(fd, bad, sizeof(bad)); return (ssize_t)size; }
        ssize_t result = write(fd, bytes, size);
        if (mode_is("cancel") || mode_is("timeout")) {
            char path[4096]; snprintf(path, sizeof(path), "%s/setup-ready", getenv("KVIK_TEST_SETUP_ROOT"));
            int file = open(path, O_CREAT | O_EXCL | O_WRONLY, 0600); if (file >= 0) close(file);
            raise(SIGSTOP);
        }
        return result;
    }
    return write(fd, bytes, size);
}
#define fork setup_test_fork
#define setpgid setup_test_setpgid
#define write setup_test_write
#include "../../execution/platform/guardian.c"
