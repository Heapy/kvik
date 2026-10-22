#include <unistd.h>
int main(void) { int malformed[4] = {0, 0, 0, 0}; return write(4, malformed, sizeof(malformed)) < 0 ? 1 : 0; }
