#include "process.h"
#include <errno.h>
#include <limits.h>
#include <stdio.h>

int main(void) {
    long long invalid[] = {-1, LLONG_MIN};
    for (unsigned i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i) {
        errno = 0;
        /* Rejection precedes even dereferencing the other arguments or creating pipes. */
        if (kvik_process_start(NULL, NULL, NULL, NULL, invalid[i], NULL, NULL) || errno != EINVAL) return 1;
    }
    puts("NEGATIVE BOUNDARY PASS");
    return 0;
}
