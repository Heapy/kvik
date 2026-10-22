#ifndef KVIK_PROCESS_H
#define KVIK_PROCESS_H
/* Recursive guardian/barrier protocol supported by this compiled boundary. */
int kvik_process_ownership_version(void);
/* timeout_ms: zero means no deadline, positive means bounded milliseconds; negative is EINVAL.
 * Every lifetime remains owned and cancellable, including when the owner disappears. */
void *kvik_process_start(const char *helper, const char *cwd, const char *out, const char *err,
                         long long timeout_ms, char *const *argv, char *const *envp);
int kvik_process_poll(void *handle, int *exit_code, int *timed_out, int *cancelled);
/* Only after poll returns 1: 1 setup failure, 0 executable outcome, -1 invalid state. */
int kvik_process_setup_error(void *handle, int *stage, int *error);
enum KvikProcessSetupStage {
    KVIK_SETUP_NONE = 0, KVIK_SETUP_TIMEOUT_ARGUMENT = 1, KVIK_SETUP_BARRIER_PIPE = 2,
    KVIK_SETUP_BARRIER_READ_DUP = 3, KVIK_SETUP_BARRIER_WRITE_DUP = 4, KVIK_SETUP_BARRIER_STAT = 5,
    KVIK_SETUP_BARRIER_READ_FLAGS = 6, KVIK_SETUP_BARRIER_WRITE_FLAGS = 7, KVIK_SETUP_BARRIER_ENVIRONMENT = 8,
    KVIK_SETUP_STATUS_PIPE = 9, KVIK_SETUP_STATUS_READ_DUP = 10, KVIK_SETUP_STATUS_WRITE_DUP = 11,
    KVIK_SETUP_STATUS_READ_FLAGS = 12, KVIK_SETUP_FORK = 13, KVIK_SETUP_PARENT_GROUP = 14,
    KVIK_SETUP_CHILD_GROUP = 15, KVIK_SETUP_BARRIER_BIND = 16, KVIK_SETUP_WORKING_DIRECTORY = 17,
    KVIK_SETUP_STDIN_OPEN = 18, KVIK_SETUP_STDOUT_OPEN = 19, KVIK_SETUP_STDERR_OPEN = 20,
    KVIK_SETUP_STDIN_BIND = 21, KVIK_SETUP_STDOUT_BIND = 22, KVIK_SETUP_STDERR_BIND = 23, KVIK_SETUP_EXEC = 24
};
void kvik_process_cancel(void *handle);
/* poll: 0 pending, 1 verified terminal, -1 ownership protocol failure.
 * close always frees the handle; -1 reports an unverified cleanup result. */
int kvik_process_close(void *handle);
/* Caller frees the returned absolute directory. NULL indicates an OS/path error. */
char *kvik_executable_directory(void);
/* Foreground coordinator only; handler stores a sig_atomic_t flag, never calls Kotlin. */
int kvik_interrupt_install(void);
int kvik_interrupt_requested(void);
void kvik_interrupt_restore(void);
/* Invocation-local stream/parent boundary. Every TTY input takes an exclusive advisory lock.
 * Keys mode requires an exclusive foreground controlling TTY in canonical+ISIG state. */
void *kvik_foreground_open(void);
void *kvik_foreground_open_keys(const char *guardian);
void *kvik_foreground_resume_keys(const char *lease);
/* Platform-bound hex continuation, caller frees. Only for an active keys boundary. */
char *kvik_foreground_export_terminal(void *handle);
int kvik_foreground_terminal_lease_bytes(void);
int kvik_foreground_terminal_healthy(void *handle);
/* Restores+acknowledges terminal state, frees handle. -1 means restoration failed. */
int kvik_foreground_close_checked(void *handle);
/* >0 bytes; 0 not ready; -2 EOF; -3 parent exited; -4 terminal helper failed; -1 IO error. */
int kvik_foreground_read(void *handle, char *buffer, int capacity);
int kvik_foreground_write(void *handle, const char *buffer, int size);
void kvik_foreground_close(void *handle);
int kvik_foreground_exec(const char *path, char *const *argv, char *const *envp);
void kvik_foreground_resume_signals(void);
int kvik_foreground_exec_terminal(const char *path, char *const *argv, char *const *envp, const char *lease);
#ifdef KVIK_TERMINAL_INTERNAL
#include <stdint.h>
#include <sys/types.h>
#include <termios.h>
#include <unistd.h>
/* Internal invocation-local capability; never accepted from a request environment. */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#define KVIK_BARRIER_ENV "KVIK_PROCESS_BARRIER_V1"
#define KVIK_BARRIER_FD 6
#define KVIK_BARRIER_MARKER_BYTES 128
#define KVIK_PROCESS_RESULT_MAGIC 0x4b565032
#define KVIK_PROCESS_RESULT_INTS 6
#define KVIK_PROCESS_SETUP_MAGIC 0x4b565331
#define KVIK_PROCESS_SETUP_INTS 3
typedef struct { int fd; uint64_t device, inode; } KvikBarrier;
static inline int kvik_barrier_validate(const KvikBarrier *cap) {
    if (cap->fd < 0) return 0;
    struct stat value;
    if (fstat(cap->fd, &value) || !S_ISFIFO(value.st_mode) ||
        (uint64_t)value.st_dev != cap->device || (uint64_t)value.st_ino != cap->inode ||
        (fcntl(cap->fd, F_GETFL) & O_ACCMODE) != O_WRONLY ||
        !(fcntl(cap->fd, F_GETFL) & O_NONBLOCK)) { errno = EPROTO; return -1; }
    return 0;
}
static inline void kvik_barrier_poison(const KvikBarrier *cap) {
    if (cap->fd < 0 || kvik_barrier_validate(cap)) return;
    char byte = '!'; ssize_t size;
    do { size = write(cap->fd, &byte, 1); } while (size < 0 && errno == EINTR);
}
static inline int kvik_barrier_capture(KvikBarrier *cap) {
    cap->fd = -1;
    const char *text = getenv(KVIK_BARRIER_ENV);
    if (!text) return 0;
    char copy[KVIK_BARRIER_MARKER_BYTES];
    if (strnlen(text, sizeof(copy)) >= sizeof(copy)) { unsetenv(KVIK_BARRIER_ENV); errno = EPROTO; return -1; }
    strcpy(copy, text); unsetenv(KVIK_BARRIER_ENV);
    if (strncmp(copy, "6:", 2) || copy[2] < '0' || copy[2] > '9') { errno = EPROTO; return -1; }
    int fd = KVIK_BARRIER_FD; char *end;
    errno = 0; unsigned long long device = strtoull(copy + 2, &end, 10);
    if (errno == ERANGE || *end != ':' || end[1] < '0' || end[1] > '9') { errno = EPROTO; return -1; }
    char *inode_text = end + 1;
    errno = 0; unsigned long long inode = strtoull(inode_text, &end, 10);
    if (errno == ERANGE || *end) { errno = EPROTO; return -1; }
    char canonical[KVIK_BARRIER_MARKER_BYTES];
    snprintf(canonical, sizeof(canonical), "%d:%llu:%llu", fd, device, inode);
    if (strcmp(copy, canonical)) { errno = EPROTO; return -1; }
    cap->fd = fd; cap->device = device; cap->inode = inode;
    if (kvik_barrier_validate(cap) || fcntl(fd, F_SETFD, FD_CLOEXEC) || fcntl(fd, F_SETNOSIGPIPE, 1)) { cap->fd = -1; errno = EPROTO; return -1; }
    return 0;
}
/* Returned array borrows original strings and marker; caller frees only the array. */
static inline char **kvik_barrier_environment(char *const *env, const KvikBarrier *cap,
                                              char marker[KVIK_BARRIER_MARKER_BYTES]) {
    if (kvik_barrier_validate(cap)) return NULL;
    size_t count = 0;
    while (env[count]) {
        if (!strncmp(env[count], KVIK_BARRIER_ENV "=", sizeof(KVIK_BARRIER_ENV))) { errno = EINVAL; return NULL; }
        ++count;
    }
    char **result = calloc(count + 2, sizeof(char *));
    if (!result) return NULL;
    for (size_t i = 0; i < count; ++i) result[i] = env[i];
    if (cap->fd >= 0) {
        snprintf(marker, KVIK_BARRIER_MARKER_BYTES, KVIK_BARRIER_ENV "=%d:%llu:%llu", KVIK_BARRIER_FD,
                 (unsigned long long)cap->device, (unsigned long long)cap->inode);
        result[count] = marker;
    }
    return result;
}
#define KVIK_TERMINAL_MAGIC UINT64_C(0x4b56494b54545931)
#define KVIK_TERMINAL_VERSION 1
#define KVIK_TERMINAL_ENTER 1
#define KVIK_TERMINAL_CHECK 2
#define KVIK_TERMINAL_CLOSE 3
#define KVIK_TERMINAL_DEADLINE_MS 1000
/* Invocation-local and macOS/ABI-bound. Zero initialize before capture/serialization. */
typedef struct {
    uint64_t magic;
    uint32_t version, bytes;
    pid_t owner, helper;
    int control, reply, terminal_fd;
    uint64_t tty_device, tty_inode, control_device, control_inode, reply_device, reply_inode;
    unsigned char cookie[16];
    struct termios original, active;
} KvikTerminalLease;
static inline int kvik_terminal_supported(const struct termios *value) {
    return (value->c_lflag & ICANON) && (value->c_lflag & ISIG) && !(value->c_lflag & (PENDIN | FLUSHO)) &&
        value->c_cc[VINTR] == 3 && (value->c_cc[VQUIT] == 28 || value->c_cc[VQUIT] == _POSIX_VDISABLE);
}
typedef struct { int operation; pid_t owner; unsigned char cookie[16]; } KvikTerminalRequest;
typedef struct { int operation, error; unsigned char cookie[16]; struct termios original, active; } KvikTerminalReply;
#endif
#endif
