#include "process.h"
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
int main(int argc, char **argv) {
    if (argc != 2 || getenv("KVIK_PROCESS_BARRIER_V1")) return 90;
    int gate = atoi(getenv("KVIK_TEST_GATE")); char byte;
    if (read(gate, &byte, 1) != 1) return 91;
    close(gate);
    void *terminal = kvik_foreground_open_keys(argv[1]);
    if (!terminal) return 92;
    puts("READY"); fflush(stdout);
    for (;;) pause();
}
