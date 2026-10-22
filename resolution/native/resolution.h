#ifndef KVIK_RESOLUTION_H
#define KVIK_RESOLUTION_H
#include <curl/curl.h>
#include <libxml/parser.h>
#include <libxml/tree.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
typedef struct { unsigned char *data; size_t size; size_t limit; long status; int error; int limit_exceeded; } kvik_response;
static size_t kvik_write(char *p, size_t size, size_t count, void *user) {
    kvik_response *r = (kvik_response *)user;
    if (count && size > SIZE_MAX / count) { r->limit_exceeded = 1; return 0; }
    size_t n = size * count;
    if (n > r->limit - r->size) { r->limit_exceeded = 1; return 0; }
    unsigned char *next = realloc(r->data, r->size + n);
    if (!next && n) return 0;
    r->data = next; memcpy(r->data + r->size, p, n); r->size += n; return n;
}
static kvik_response *kvik_fetch(const char *url, size_t limit, long timeout) {
    kvik_response *r = calloc(1, sizeof(kvik_response));
    if (!r) return NULL;
    r->limit = limit;
    if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) { r->error = -1; return r; }
    CURL *c = curl_easy_init();
    if (!c) { r->error = -1; return r; }
    curl_easy_setopt(c, CURLOPT_URL, url);
    curl_easy_setopt(c, CURLOPT_PROTOCOLS, CURLPROTO_HTTPS);
    curl_easy_setopt(c, CURLOPT_REDIR_PROTOCOLS, CURLPROTO_HTTPS);
    /* No redirects: repository URL is the lock provenance and trust boundary. */
    curl_easy_setopt(c, CURLOPT_FOLLOWLOCATION, 0L);
    curl_easy_setopt(c, CURLOPT_SSL_VERIFYPEER, 1L);
    curl_easy_setopt(c, CURLOPT_SSL_VERIFYHOST, 2L);
    curl_easy_setopt(c, CURLOPT_CONNECTTIMEOUT, timeout < 10 ? timeout : 10L);
    curl_easy_setopt(c, CURLOPT_TIMEOUT, timeout);
    curl_easy_setopt(c, CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(c, CURLOPT_WRITEFUNCTION, kvik_write);
    curl_easy_setopt(c, CURLOPT_WRITEDATA, r);
    r->error = curl_easy_perform(c);
    curl_easy_getinfo(c, CURLINFO_RESPONSE_CODE, &r->status);
    curl_easy_cleanup(c);
    return r;
}
static void kvik_response_free(kvik_response *r) { if (r) { free(r->data); free(r); } }
static xmlDocPtr kvik_xml(const char *bytes, int size) {
    xmlDocPtr d = xmlReadMemory(bytes, size, NULL, "UTF-8", XML_PARSE_NONET | XML_PARSE_NOERROR | XML_PARSE_NOWARNING);
    if (d && (d->intSubset || d->extSubset)) { xmlFreeDoc(d); return NULL; }
    return d;
}
static void kvik_xml_free(xmlDocPtr d) { xmlFreeDoc(d); }
static xmlNodePtr kvik_root(xmlDocPtr d) { return xmlDocGetRootElement(d); }
static xmlNodePtr kvik_child(xmlNodePtr n) { return xmlFirstElementChild(n); }
static xmlNodePtr kvik_next(xmlNodePtr n) { return xmlNextElementSibling(n); }
static const char *kvik_name(xmlNodePtr n) { return (const char *)n->name; }
static const char *kvik_ns(xmlNodePtr n) { return n->ns ? (const char *)n->ns->href : ""; }
static char *kvik_text(xmlNodePtr n) { return (char *)xmlNodeGetContent(n); }
static void kvik_text_free(char *s) { xmlFree(s); }
#endif
