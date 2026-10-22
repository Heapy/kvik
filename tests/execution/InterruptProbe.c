#include "process.h"
#include <assert.h>
#include <errno.h>
#include <signal.h>

static volatile sig_atomic_t prior_int = 0, prior_term = 0;
static void previous(int number) {
    if (number == SIGINT) prior_int++;
    if (number == SIGTERM) prior_term++;
}
int main(void) {
    signal(SIGINT, previous);
    signal(SIGTERM, previous);
    assert(kvik_interrupt_install() == 0);
    assert(kvik_interrupt_requested() == 0);
    assert(kvik_interrupt_install() == -1 && errno == EBUSY);
    raise(SIGINT);
    assert(kvik_interrupt_requested() == SIGINT && prior_int == 0);
    raise(SIGTERM);
    assert(kvik_interrupt_requested() == SIGTERM && prior_term == 0);
    kvik_interrupt_restore();
    raise(SIGINT);
    raise(SIGTERM);
    assert(prior_int == 1 && prior_term == 1);
    assert(kvik_interrupt_install() == 0);
    assert(kvik_interrupt_requested() == 0);
    kvik_interrupt_restore();
    kvik_interrupt_restore();
    return 0;
}
