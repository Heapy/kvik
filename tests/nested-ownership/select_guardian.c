#include <stdlib.h>
#include <string.h>
#include <unistd.h>
/* No-child malformed result for /bin/false, real guardian otherwise. */
int main(int argc, char **argv) {
    if (argc < 6) return 90;
    if (!strcmp(argv[5], "/bin/false")) { int malformed[4] = {0}; return write(4, malformed, sizeof(malformed)) < 0 ? 1 : 0; }
    const char *helper = getenv("KVIK_TEST_REAL_GUARDIAN");
    if (!helper) return 91;
    argv[0] = (char *)helper; execv(helper, argv); return 92;
}
