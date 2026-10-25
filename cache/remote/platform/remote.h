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
/* One JSON POST, fixed 1 MiB body limits and 30 second deadline; no redirects or proxies. */
kvik_rpc_reply *kvik_json_post(const char *url, const char *bearer_token,
    const unsigned char *request, size_t request_size, int (*cancelled)(void *), void *cancel_context);
void kvik_rpc_free(kvik_rpc_reply *reply);
#endif
