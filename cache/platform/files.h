#ifndef KVIK_FILES_H
#define KVIK_FILES_H

#include <stdint.h>
#include <errno.h>
#include <sys/stat.h>
#include <stdio.h>
#if defined(__linux__)
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>
#endif

/* Preserve nanosecond change detection without leaking host-specific struct fields. */
static inline int64_t kvik_files_mtime_seconds(const struct stat *value) {
#if defined(__APPLE__)
    return (int64_t)value->st_mtimespec.tv_sec;
#else
    return (int64_t)value->st_mtim.tv_sec;
#endif
}
static inline int64_t kvik_files_mtime_nanoseconds(const struct stat *value) {
#if defined(__APPLE__)
    return (int64_t)value->st_mtimespec.tv_nsec;
#else
    return (int64_t)value->st_mtim.tv_nsec;
#endif
}
static inline int64_t kvik_files_ctime_seconds(const struct stat *value) {
#if defined(__APPLE__)
    return (int64_t)value->st_ctimespec.tv_sec;
#else
    return (int64_t)value->st_ctim.tv_sec;
#endif
}
static inline int64_t kvik_files_ctime_nanoseconds(const struct stat *value) {
#if defined(__APPLE__)
    return (int64_t)value->st_ctimespec.tv_nsec;
#else
    return (int64_t)value->st_ctim.tv_nsec;
#endif
}

/* Never fall back to a replacing rename when the kernel lacks this operation. */
static inline int kvik_files_rename_exclusive(const char *source, const char *destination) {
#if defined(__APPLE__)
    return renamex_np(source, destination, RENAME_EXCL);
#elif defined(__linux__) && defined(SYS_renameat2)
    return (int)syscall(SYS_renameat2, AT_FDCWD, source, AT_FDCWD, destination, 1U /* RENAME_NOREPLACE */);
#else
    (void)source;
    (void)destination;
    errno = ENOSYS;
    return -1;
#endif
}
#endif
