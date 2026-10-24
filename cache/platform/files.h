#ifndef KVIK_FILES_H
#define KVIK_FILES_H

#include <stdint.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <stdio.h>
#include <string.h>
#if defined(__APPLE__)
#include <CoreFoundation/CoreFoundation.h>
#endif
#if defined(__linux__)
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>
#endif

static inline int kvik_files_flock(int descriptor, int operation) {
    return flock(descriptor, operation);
}

/* Preserve nanosecond change detection without leaking host-specific struct fields. */
static inline int64_t kvik_files_mtime_seconds(const void *opaque) {
    const struct stat *value = (const struct stat *)opaque;
#if defined(__APPLE__)
    return (int64_t)value->st_mtimespec.tv_sec;
#else
    return (int64_t)value->st_mtim.tv_sec;
#endif
}
static inline int64_t kvik_files_mtime_nanoseconds(const void *opaque) {
    const struct stat *value = (const struct stat *)opaque;
#if defined(__APPLE__)
    return (int64_t)value->st_mtimespec.tv_nsec;
#else
    return (int64_t)value->st_mtim.tv_nsec;
#endif
}
static inline int64_t kvik_files_ctime_seconds(const void *opaque) {
    const struct stat *value = (const struct stat *)opaque;
#if defined(__APPLE__)
    return (int64_t)value->st_ctimespec.tv_sec;
#else
    return (int64_t)value->st_ctim.tv_sec;
#endif
}
static inline int64_t kvik_files_ctime_nanoseconds(const void *opaque) {
    const struct stat *value = (const struct stat *)opaque;
#if defined(__APPLE__)
    return (int64_t)value->st_ctimespec.tv_nsec;
#else
    return (int64_t)value->st_ctim.tv_nsec;
#endif
}

/* Compare potentially missing path components conservatively. Existing ancestors are also
 * compared by device/inode by the caller. Darwin preserves canonical Unicode/case aliases.
 * Linux filesystems may opt into Unicode casefolding, so without a normalization dependency
 * non-ASCII components are conservatively considered potential aliases. ASCII names retain
 * case-insensitive comparison. A negative result is allocation/invalid UTF-8 failure. */
static inline int kvik_files_potential_component_alias(const char *left, const char *right) {
#if defined(__APPLE__)
    CFStringRef a = CFStringCreateWithCString(NULL, left, kCFStringEncodingUTF8);
    CFStringRef b = CFStringCreateWithCString(NULL, right, kCFStringEncodingUTF8);
    if (!a || !b) {
        if (a) CFRelease(a);
        if (b) CFRelease(b);
        return -1;
    }
    int equal = CFStringCompare(a, b, kCFCompareCaseInsensitive | kCFCompareNonliteral) == kCFCompareEqualTo;
    CFRelease(a);
    CFRelease(b);
    return equal;
#else
    for (const unsigned char *p = (const unsigned char *)left; *p; ++p) if (*p >= 128) return 1;
    for (const unsigned char *p = (const unsigned char *)right; *p; ++p) if (*p >= 128) return 1;
    while (*left && *right) {
        unsigned char a = (unsigned char)*left++, b = (unsigned char)*right++;
        if (a >= 'A' && a <= 'Z') a = (unsigned char)(a + ('a' - 'A'));
        if (b >= 'A' && b <= 'Z') b = (unsigned char)(b + ('a' - 'A'));
        if (a != b) return 0;
    }
    return *left == *right;
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
