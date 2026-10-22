#ifndef KVIK_STREAM_HASH_H
#define KVIK_STREAM_HASH_H
#include <CommonCrypto/CommonDigest.h>
#include <stdlib.h>
#include <stddef.h>

/* Own one context per stream. CommonCrypto is supplied by macOS libSystem. */
static inline void *kvik_sha_new(void) {
    CC_SHA256_CTX *context = (CC_SHA256_CTX *)malloc(sizeof(CC_SHA256_CTX));
    if (context && !CC_SHA256_Init(context)) { free(context); return NULL; }
    return context;
}
static inline int kvik_sha_update(void *context, const void *bytes, unsigned int count) {
    return CC_SHA256_Update((CC_SHA256_CTX *)context, bytes, count);
}
static inline int kvik_sha_finish(void *context, unsigned char *digest) {
    return CC_SHA256_Final(digest, (CC_SHA256_CTX *)context);
}
static inline void kvik_sha_free(void *context) { free(context); }
#endif
