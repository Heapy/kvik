#define KVIK_TERMINAL_INTERNAL
#include "process.h"
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
int main(void) {
    const char *mode = getenv("KVIK_TEST_SETUP_MODE");
    int64_t frame[KVIK_PROCESS_RESULT_FIELDS + 1] = {KVIK_PROCESS_RESULT_MAGIC, 125, 0, 0, KVIK_SETUP_FORK, 35, 0};
    size_t size = sizeof(int64_t) * KVIK_PROCESS_RESULT_FIELDS;
    if (!strcmp(mode, "unknown-frame")) frame[4] = 999;
    else if (!strcmp(mode, "zero-error")) frame[5] = 0;
    else if (!strcmp(mode, "negative-error")) frame[5] = -1;
    else if (!strcmp(mode, "orphan-error")) frame[4] = 0;
    else if (!strcmp(mode, "success-with-error")) frame[1] = 0;
    else if (!strcmp(mode, "exec-wrong-code")) frame[4] = KVIK_SETUP_EXEC;
    else if (!strcmp(mode, "cancel-wrong-code")) frame[3] = 1;
    else if (!strcmp(mode, "both-flags")) { frame[1] = 130; frame[2] = 1; frame[3] = 1; }
    else if (!strcmp(mode, "negative-usage")) { frame[4] = frame[5] = 0; frame[6] = 1; frame[7] = -1; }
    else if (!strcmp(mode, "orphan-usage")) frame[9] = 1;
    else if (!strcmp(mode, "setup-usage")) frame[6] = 1;
    else if (!strcmp(mode, "unknown-usage")) frame[6] = 2;
    else if (!strcmp(mode, "old-frame")) { frame[0] = 0x4b565031; size = sizeof(int) * 4; }
    else if (!strcmp(mode, "partial-frame")) --size;
    else if (!strcmp(mode, "extra-frame")) ++size;
    else if (!strcmp(mode, "empty-frame")) size = 0;
    else return 90;
    return write(4, frame, size) < 0 ? 91 : 0;
}
