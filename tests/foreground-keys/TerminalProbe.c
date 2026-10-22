#define KVIK_TERMINAL_INTERNAL
#include "process.h"
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>

static void emit(const char *format, ...) {
    char line[4096]; va_list args; va_start(args, format);
    int size = vsnprintf(line, sizeof(line), format, args); va_end(args);
    if (size <= 0 || size >= (int)sizeof(line) || write(1, line, (size_t)size) != size) exit(30);
}
static void state(void) {
    struct termios value; memset(&value, 0, sizeof(value));
    if (tcgetattr(0, &value)) { perror("tcgetattr"); exit(10); }
    const unsigned char *bytes = (const unsigned char *)&value;
    emit("state ");
    for (size_t i = 0; i < sizeof(value); ++i) emit("%02x", bytes[i]);
    emit(" %d %d %d %d\n", fcntl(0, F_GETFL), fcntl(0, F_GETFD), fcntl(1, F_GETFL), fcntl(1, F_GETFD)); 
}
static KvikTerminalLease decode(const char *text) {
    KvikTerminalLease lease; unsigned char *bytes = (unsigned char *)&lease;
    for (size_t i = 0; i < sizeof(lease); ++i) { unsigned value; sscanf(text + i * 2, "%2x", &value); bytes[i] = (unsigned char)value; }
    return lease;
}
static char *encode(const KvikTerminalLease *lease) {
    char *text = malloc(sizeof(*lease) * 2 + 1);
    if (!text) exit(31);
    for (size_t i = 0; i < sizeof(*lease); ++i) snprintf(text + i * 2, 3, "%02x", ((const unsigned char *)lease)[i]);
    return text;
}
int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "inspect")) { state(); return 0; }
    if (argc < 3) return 11;
    if (kvik_interrupt_install()) return 12;
    signal(SIGPIPE, SIG_IGN);
    /* Test broker sets the child's foreground process group before releasing fd7. */
    if (getenv("KVIK_TEST_GATE")) { int fd = atoi(getenv("KVIK_TEST_GATE")); char gate; if (read(fd, &gate, 1) != 1) return 13; close(fd); unsetenv("KVIK_TEST_GATE"); }
    int resumed = !strcmp(argv[2], "resumed");
    if (!resumed && !getenv("KVIK_TEST_SKIP_STATE")) state();
    int lines = !strcmp(argv[2], "lines");
    void *boundary = resumed ? kvik_foreground_resume_keys(argv[3]) : lines ? kvik_foreground_open() : kvik_foreground_open_keys(argv[1]);
    if (!boundary) { emit("rejected %d\n", errno);  return 20; }
    if (resumed) kvik_foreground_resume_signals();
    char *encoded = lines ? NULL : kvik_foreground_export_terminal(boundary);
    if (!lines && !encoded) return 14;
    KvikTerminalLease lease; memset(&lease, 0, sizeof(lease)); if (encoded) lease = decode(encoded);
    emit("%s %d %d\n", resumed ? "resumed" : "ready", (int)getpid(), (int)lease.helper); 
    int reason = 0;
    for (;;) {
        char key; int count = kvik_foreground_read(boundary, &key, 1);
        if (count < 0) { reason = count; break; }
        if (kvik_interrupt_requested()) { reason = 130; break; }
        if (!count) { usleep(10000); continue; }
        if (key == 'q' || key == 4) break;
        if (key == 'e' || key == 'i' || key == 'j') {
            if (key != 'e') {
                int pending = key == 'i' ? SIGINT : SIGTERM;
                sigset_t blocked; sigemptyset(&blocked); sigaddset(&blocked, pending);
                if (sigprocmask(SIG_BLOCK, &blocked, NULL) || raise(pending)) return 32;
            }
            char *next[] = {argv[0], argv[1], "resumed", encoded, NULL};
            char *environment[] = {"PATH=/usr/bin:/bin", NULL};
            kvik_foreground_exec_terminal(argv[0], next, environment, encoded);
            emit("unexpected-exec-failure %d\n", errno);  return 15;
        }
        if (key == 'v') {
            char *next[] = {"/kvik-deliberately-absent", NULL}; char *environment[] = {NULL};
            for (int variant = 0; variant < 4; ++variant) {
                KvikTerminalLease wrong = lease;
                if (variant == 0) wrong.owner++;
                if (variant == 1) wrong.control = wrong.terminal_fd;
                if (variant == 2) wrong.reply_inode++;
                if (variant == 3) wrong.original.c_lflag ^= ECHO;
                char *bad = encode(&wrong);
                if (kvik_foreground_exec_terminal(next[0], next, environment, bad) != -1 || errno == ENOENT) return 33;
                free(bad);
            }
            emit("invalid-leases-rejected\n");
        } else if (key == 'f') {
            char *next[] = {"/kvik-deliberately-absent", NULL}; char *environment[] = {NULL};
            if (kvik_foreground_exec_terminal(next[0], next, environment, encoded) != -1 || errno != ENOENT) return 16;
            if (!(fcntl(lease.control, F_GETFD) & FD_CLOEXEC) || !(fcntl(lease.reply, F_GETFD) & FD_CLOEXEC) ||
                !(fcntl(lease.terminal_fd, F_GETFD) & FD_CLOEXEC)) return 17;
            emit("exec-failed-recovered\n");
        } else emit("key %u\n", (unsigned char)key);
        
    }
    int restored = kvik_foreground_close_checked(boundary);
    emit("closed %d %d\n", reason, restored); state(); free(encoded);
    kvik_interrupt_restore(); return restored ? 21 : 0;
}
