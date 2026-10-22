#include "resolution.h"
#include <assert.h>

int main(void) {
    char data[] = "abcdefgh";
    kvik_response r = {0};
    r.limit = 8;
    assert(kvik_write(data, 2, 4, &r) == 8);
    assert(r.size == 8 && r.limit_exceeded == 0);
    assert(memcmp(r.data, data, 8) == 0);
    assert(kvik_write(data, 1, 0, &r) == 0);
    assert(r.limit_exceeded == 0);
    assert(kvik_write(data, 1, 1, &r) == 0);
    assert(r.limit_exceeded == 1 && r.size == 8);
    assert(memcmp(r.data, data, 8) == 0);
    free(r.data);

    kvik_response overflow = {0};
    overflow.limit = SIZE_MAX;
    assert(kvik_write(data, SIZE_MAX, 2, &overflow) == 0);
    assert(overflow.limit_exceeded == 1 && overflow.data == NULL && overflow.size == 0);

    kvik_response small = {0};
    small.limit = 3;
    assert(kvik_write(data, 1, 4, &small) == 0);
    assert(small.limit_exceeded == 1 && small.data == NULL && small.size == 0);
    return 0;
}
