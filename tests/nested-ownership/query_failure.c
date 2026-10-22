#include <errno.h>
#include <stddef.h>
int kvik_test_sysctl(int *name, unsigned int count, void *out, size_t *size, void *in, size_t in_size) {
    (void)name; (void)count; (void)out; (void)size; (void)in; (void)in_size;
    errno = EPERM; return -1;
}
