#include "files.h"
#include <assert.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void directory(const char *path) { assert(mkdir(path, 0700) == 0); }

int main(int argc, char **argv) {
    assert(argc == 2);
    assert(chdir(argv[1]) == 0);
    struct stat stamp = {0};
#if defined(__APPLE__)
    stamp.st_mtimespec.tv_sec = -10;
    stamp.st_mtimespec.tv_nsec = 123456789;
    stamp.st_ctimespec.tv_sec = 30;
    stamp.st_ctimespec.tv_nsec = 987654321;
#else
    stamp.st_mtim.tv_sec = -10;
    stamp.st_mtim.tv_nsec = 123456789;
    stamp.st_ctim.tv_sec = 30;
    stamp.st_ctim.tv_nsec = 987654321;
#endif
    assert(kvik_files_mtime_seconds(&stamp) == -10);
    assert(kvik_files_mtime_nanoseconds(&stamp) == 123456789);
    assert(kvik_files_ctime_seconds(&stamp) == 30);
    assert(kvik_files_ctime_nanoseconds(&stamp) == 987654321);

    assert(kvik_files_potential_component_alias("Source", "source") == 1);
    assert(kvik_files_potential_component_alias("sources", "source") == 0);
    assert(kvik_files_potential_component_alias("source", "output") == 0);
    assert(kvik_files_potential_component_alias("caf\xc3\xa9", "cafe\xcc\x81") == 1);
    assert(kvik_files_potential_component_alias("\xe2\x84\xaa", "K") == 1);
#if defined(__APPLE__)
    assert(kvik_files_potential_component_alias("caf\xc3\xa9", "different") == 0);
    assert(kvik_files_potential_component_alias("\xff", "invalid") == -1);
#else
    assert(kvik_files_potential_component_alias("caf\xc3\xa9", "different") == 1);
#endif

    int lock = open("lock", O_CREAT | O_RDWR, 0600);
    int contender = open("lock", O_RDWR);
    assert(lock >= 0 && contender >= 0);
    assert(kvik_files_flock(lock, LOCK_EX) == 0);
    assert(kvik_files_flock(contender, LOCK_EX | LOCK_NB) == -1 && (errno == EWOULDBLOCK || errno == EAGAIN));
    assert(kvik_files_flock(lock, LOCK_UN) == 0);
    assert(kvik_files_flock(contender, LOCK_EX | LOCK_NB) == 0);
    assert(kvik_files_flock(contender, LOCK_UN) == 0);
    assert(close(lock) == 0 && close(contender) == 0);

    directory("stage");
    int fd = open("stage/content", O_CREAT | O_EXCL | O_WRONLY, 0600);
    assert(fd >= 0 && write(fd, "content", 7) == 7 && close(fd) == 0);
    struct stat before, after;
    assert(stat("stage/content", &before) == 0);
    assert(kvik_files_rename_exclusive("stage", "published") == 0);
    assert(lstat("stage", &after) == -1 && errno == ENOENT);
    assert(stat("published/content", &after) == 0 && before.st_ino == after.st_ino);
    assert(kvik_files_mtime_seconds(&before) == kvik_files_mtime_seconds(&after));
    assert(kvik_files_mtime_nanoseconds(&before) == kvik_files_mtime_nanoseconds(&after));

    directory("second");
    assert(kvik_files_rename_exclusive("second", "published") == -1 && errno == EEXIST);
    directory("empty");
    assert(lstat("empty", &before) == 0);
    assert(kvik_files_rename_exclusive("second", "empty") == -1 && errno == EEXIST);
    assert(lstat("empty", &after) == 0 && before.st_ino == after.st_ino);
    assert(symlink("published", "alias") == 0);
    assert(kvik_files_rename_exclusive("second", "alias") == -1 && errno == EEXIST);
    assert(lstat("alias", &after) == 0 && S_ISLNK(after.st_mode));
    assert(kvik_files_rename_exclusive("absent", "unused") == -1 && errno == ENOENT);
    assert(lstat("second", &after) == 0 && S_ISDIR(after.st_mode));
    puts("PASS locks, timestamps, conservative path aliases and exclusive publication");
    return 0;
}
