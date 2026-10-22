#ifndef KVIK_SPIKE_PLATFORM_H
#define KVIK_SPIKE_PLATFORM_H
#include <stddef.h>
int kvik_temp_directory(char *buffer, size_t capacity);
int kvik_write_text(const char *path, const char *text);
int kvik_sha256(const char *path, char *hex);
long kvik_fetch(const char *url, const char *destination);
void *kvik_watch_open(const char *directory);
int kvik_watch_wait(void *watcher, int timeout_seconds);
void kvik_watch_close(void *watcher);
int kvik_remove(const char *path);
int kvik_run(char *const *arguments, int timeout_seconds);
#endif
