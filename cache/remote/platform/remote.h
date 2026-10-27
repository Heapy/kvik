#ifndef KVIK_REMOTE_H
#define KVIK_REMOTE_H
#include <stddef.h>
#include <stdint.h>
typedef struct {
    unsigned char *data;
    size_t size;
    long http_status;
    int grpc_status;
    int transport_error;
    int64_t uploaded_bytes;
    int64_t downloaded_bytes;
} kvik_rpc_reply;
kvik_rpc_reply *kvik_rpc(const char *url, const char *authorization,
    const unsigned char *request, size_t request_size, size_t response_limit, long timeout_ms, int (*cancelled)(void *), void *cancel_context);
/* A bounded GET (put=0) or PUT (put=1). No redirects, proxies, or credential forwarding. */
kvik_rpc_reply *kvik_http_cache(const char *url, const char *authorization, int put,
    const unsigned char *request, size_t request_size, size_t response_limit, long timeout_ms, int (*cancelled)(void *), void *cancel_context);
/* PUT with If-None-Match: *. Repository must enforce conditional creation. */
kvik_rpc_reply *kvik_http_create(const char *url, const char *authorization,
    const unsigned char *request, size_t request_size, size_t response_limit, long timeout_ms, int (*cancelled)(void *), void *cancel_context);
/* One JSON POST, fixed 1 MiB body limits and 30 second deadline; no redirects or proxies. */
kvik_rpc_reply *kvik_json_post(const char *url, const char *bearer_token,
    const unsigned char *request, size_t request_size, int (*cancelled)(void *), void *cancel_context);
void kvik_rpc_free(kvik_rpc_reply *reply);
/* Deterministic gzip encoding or one-member decoding. A failed result owns no data.
 * Both byte counts are capped at 512 MiB; cancellation is polled every 32 KiB step. */
typedef struct {
    unsigned char *data;
    size_t size;
    int error;
} kvik_gzip_reply;
enum { KVIK_GZIP_OK = 0, KVIK_GZIP_INVALID = 1, KVIK_GZIP_LIMIT = 2,
    KVIK_GZIP_CANCELLED = 3, KVIK_GZIP_MEMORY = 4 };
kvik_gzip_reply *kvik_gzip_codec(const unsigned char *input, size_t input_size,
    size_t output_limit, int decompress, int (*cancelled)(void *), void *cancel_context);
void kvik_gzip_free(kvik_gzip_reply *reply);
#endif
