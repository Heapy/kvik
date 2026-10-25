#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

int main(int argc, char **argv) {
    size_t size = 64 * 1024 * 1024;
    volatile unsigned char *memory = malloc(size);
    if (!memory) return 90;
    for (size_t i = 0; i < size; i += 4096) memory[i] = (unsigned char)i;
    struct timespec start, now;
    clock_gettime(CLOCK_MONOTONIC, &start);
    do {
        for (size_t i = 0; i < size; i += 4096) memory[i]++;
        clock_gettime(CLOCK_MONOTONIC, &now);
    } while ((now.tv_sec - start.tv_sec) * 1000000000LL + now.tv_nsec - start.tv_nsec < 100000000);
    if (argc > 1 && strcmp(argv[1], "usage")) {
        char ready[4096]; snprintf(ready, sizeof(ready), "%s/setup-ready", getenv("KVIK_TEST_SETUP_ROOT"));
        FILE *file = fopen(ready, "w"); if (!file) return 91;
        fclose(file);
        for (;;) pause();
    }
    free((void *)memory);
    return 0;
}
