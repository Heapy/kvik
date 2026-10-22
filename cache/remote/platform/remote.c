#include "remote.h"
#include <curl/curl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

static pthread_once_t initialized = PTHREAD_ONCE_INIT;
static CURLcode initialization;
static void initialize(void) { initialization = curl_global_init(CURL_GLOBAL_DEFAULT); }
typedef struct { kvik_rpc_reply *reply; size_t limit, headers; int content_type; } Transfer;
typedef struct { int (*cancelled)(void *); void *context; } Cancellation;
static int progress(void *context, curl_off_t a, curl_off_t b, curl_off_t c, curl_off_t d) {
    (void)a; (void)b; (void)c; (void)d;
    Cancellation *cancellation = context;
    return cancellation->cancelled && cancellation->cancelled(cancellation->context);
}
static size_t receive(char *bytes, size_t size, size_t count, void *context) {
    Transfer *transfer = context;
    if (size && count > SIZE_MAX / size) return 0;
    size_t length = size * count;
    if (length > transfer->limit - transfer->reply->size) return 0;
    unsigned char *next = realloc(transfer->reply->data, transfer->reply->size + length + 1);
    if (!next) return 0;
    transfer->reply->data = next;
    memcpy(next + transfer->reply->size, bytes, length);
    transfer->reply->size += length;
    return length;
}
static size_t header(char *bytes, size_t size, size_t count, void *context) {
    Transfer *transfer = context;
    if (size && count > SIZE_MAX / size) return 0;
    size_t length = size * count;
    if (length > 65536 - transfer->headers) return 0;
    transfer->headers += length;
    if (length > 12 && !strncasecmp(bytes, "grpc-status:", 12)) {
        char value[32];
        size_t remaining = length - 12;
        if (remaining >= sizeof(value)) return 0;
        memcpy(value, bytes + 12, remaining); value[remaining] = 0;
        char *end;
        long status = strtol(value, &end, 10);
        if (end == value || status < 0 || status > 16) return 0;
        while (*end == ' ' || *end == '\t' || *end == '\r' || *end == '\n') ++end;
        if (*end || transfer->reply->grpc_status != -1) return 0;
        transfer->reply->grpc_status = (int)status;
    }
    if (length >= 30 && !strncasecmp(bytes, "content-type:", 13)) {
        size_t offset = 13;
        while (offset < length && (bytes[offset] == ' ' || bytes[offset] == '\t')) ++offset;
        if (length - offset >= 16 && !strncasecmp(bytes + offset, "application/grpc", 16)) transfer->content_type = 1;
    }
    return length;
}
kvik_rpc_reply *kvik_rpc(const char *url, const char *authorization,
    const unsigned char *request, size_t request_size, size_t response_limit, long timeout_ms, int (*cancelled)(void *), void *cancel_context) {
    kvik_rpc_reply *reply = calloc(1, sizeof(*reply));
    if (!reply) return NULL;
    reply->grpc_status = -1; reply->transport_error = -1;
    pthread_once(&initialized, initialize);
    if (initialization != CURLE_OK || !response_limit || timeout_ms <= 0) return reply;
    CURL *curl = curl_easy_init();
    if (!curl) return reply;
    struct curl_slist *headers = NULL;
    headers = curl_slist_append(headers, "content-type: application/grpc");
    headers = curl_slist_append(headers, "te: trailers");
    if (authorization && *authorization) headers = curl_slist_append(headers, authorization);
    Transfer transfer = {reply, response_limit, 0, 0};
    Cancellation cancellation = {cancelled, cancel_context};
    curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 0L);
    curl_easy_setopt(curl, CURLOPT_XFERINFOFUNCTION, progress);
    curl_easy_setopt(curl, CURLOPT_XFERINFODATA, &cancellation);
    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_HTTP_VERSION, CURL_HTTP_VERSION_2_PRIOR_KNOWLEDGE);
    curl_easy_setopt(curl, CURLOPT_PROTOCOLS_STR, "http,https");
    curl_easy_setopt(curl, CURLOPT_PROXY, "");
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT_MS, timeout_ms);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT_MS, timeout_ms);
    curl_easy_setopt(curl, CURLOPT_POST, 1L);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, request);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE_LARGE, (curl_off_t)request_size);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, receive);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &transfer);
    curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, header);
    curl_easy_setopt(curl, CURLOPT_HEADERDATA, &transfer);
    reply->transport_error = (int)curl_easy_perform(curl);
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &reply->http_status);
    long version = 0;
    curl_easy_getinfo(curl, CURLINFO_HTTP_VERSION, &version);
    if (version != CURL_HTTP_VERSION_2_0 && reply->transport_error == 0) reply->transport_error = -3;
    if (!transfer.content_type && reply->transport_error == 0) reply->transport_error = -2;
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    return reply;
}
void kvik_rpc_free(kvik_rpc_reply *reply) { if (reply) { free(reply->data); free(reply); } }
