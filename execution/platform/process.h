#ifndef KVIK_PROCESS_H
#define KVIK_PROCESS_H
/* timeout_ms: zero means no deadline, positive means bounded milliseconds; negative is EINVAL.
 * Every lifetime remains owned and cancellable, including when the owner disappears. */
void *kvik_process_start(const char *helper, const char *cwd, const char *out, const char *err,
                         long long timeout_ms, char *const *argv, char *const *envp);
int kvik_process_poll(void *handle, int *exit_code, int *timed_out, int *cancelled);
void kvik_process_cancel(void *handle);
void kvik_process_close(void *handle);
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
