#ifndef KVIK_PROCESS_H
#define KVIK_PROCESS_H
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
/* Invocation-local line input/output and parent-exit monitor. No terminal mode changes. */
void *kvik_foreground_open(void);
/* >0 bytes; 0 not ready; -2 EOF; -3 parent exited; -1 IO error. */
int kvik_foreground_read(void *handle, char *buffer, int capacity);
int kvik_foreground_write(void *handle, const char *buffer, int size);
void kvik_foreground_close(void *handle);
int kvik_foreground_exec(const char *path, char *const *argv, char *const *envp);
void kvik_foreground_resume_signals(void);
#endif
