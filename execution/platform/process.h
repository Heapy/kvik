#ifndef KVIK_PROCESS_H
#define KVIK_PROCESS_H
void *kvik_process_start(const char *helper, const char *cwd, const char *out, const char *err,
                         long long timeout_ms, char *const *argv, char *const *envp);
int kvik_process_poll(void *handle, int *exit_code, int *timed_out, int *cancelled);
void kvik_process_cancel(void *handle);
void kvik_process_close(void *handle);
#endif
