#ifndef KVIK_STREAM_HASH_H
#define KVIK_STREAM_HASH_H
#ifdef __APPLE__
#include <CommonCrypto/CommonDigest.h>
#elif defined(__linux__)
#include <openssl/evp.h>
#else
#error Unsupported SHA-256 host
#endif
#include <stdlib.h>
#include <stddef.h>

/* Own one context per stream. Providers are part of the pinned host tools. */
static inline void *kvik_sha_new(void) {
#ifdef __APPLE__
    CC_SHA256_CTX *context = (CC_SHA256_CTX *)malloc(sizeof(CC_SHA256_CTX));
    if (context && !CC_SHA256_Init(context)) { free(context); return NULL; }
    return context;
#else
    EVP_MD_CTX *context = EVP_MD_CTX_new();
    if (context && !EVP_DigestInit_ex(context, EVP_sha256(), NULL)) { EVP_MD_CTX_free(context); return NULL; }
    return context;
#endif
}
static inline int kvik_sha_update(void *context, const void *bytes, unsigned int count) {
#ifdef __APPLE__
    return CC_SHA256_Update((CC_SHA256_CTX *)context, bytes, count);
#else
    return EVP_DigestUpdate((EVP_MD_CTX *)context, bytes, count);
#endif
}
static inline int kvik_sha_finish(void *context, unsigned char *digest) {
#ifdef __APPLE__
    return CC_SHA256_Final(digest, (CC_SHA256_CTX *)context);
#else
    unsigned int size = 0;
    return EVP_DigestFinal_ex((EVP_MD_CTX *)context, digest, &size) && size == 32;
#endif
}
static inline void kvik_sha_free(void *context) {
#ifdef __APPLE__
    free(context);
#else
    EVP_MD_CTX_free((EVP_MD_CTX *)context);
#endif
}
#endif
