#include "platform.h"
#include <CommonCrypto/CommonDigest.h>
#include <curl/curl.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/event.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

extern char **environ;

int kvik_temp_directory(char *buffer, size_t capacity) {
    const char *base = getenv("TMPDIR");
    int count = snprintf(buffer, capacity, "%s/kvik-k01-XXXXXX", base ? base : "/tmp");
    if (count < 0 || (size_t)count >= capacity) return -1;
    return mkdtemp(buffer) ? 0 : -1;
}

int kvik_write_text(const char *path, const char *text) {
    FILE *file = fopen(path, "wb");
    if (!file) return -1;
    size_t size = strlen(text);
    int result = fwrite(text, 1, size, file) == size ? 0 : -1;
    if (fclose(file)) result = -1;
    return result;
}

int kvik_sha256(const char *path, char *hex) {
    FILE *file = fopen(path, "rb");
    if (!file) return -1;
    CC_SHA256_CTX context;
    CC_SHA256_Init(&context);
    unsigned char bytes[16384], hash[CC_SHA256_DIGEST_LENGTH];
    size_t count;
    while ((count = fread(bytes, 1, sizeof(bytes), file))) CC_SHA256_Update(&context, bytes, (CC_LONG)count);
    int error = ferror(file);
    fclose(file);
    if (error) return -1;
    CC_SHA256_Final(hash, &context);
    for (size_t i = 0; i < sizeof(hash); i++) snprintf(hex + i * 2, 3, "%02x", hash[i]);
    return 0;
}

static size_t write_response(char *data, size_t size, size_t count, void *context) {
    return fwrite(data, 1, size * count, (FILE *)context);
}

long kvik_fetch(const char *url, const char *destination) {
    FILE *file = fopen(destination, "wb");
    if (!file) return -1;
    if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) { fclose(file); return -1; }
    CURL *curl = curl_easy_init();
    if (!curl) { fclose(file); curl_global_cleanup(); return -1; }
    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_PROTOCOLS_STR, "https");
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 15L);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, write_response);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, file);
    CURLcode result = curl_easy_perform(curl);
    long status = -1;
    if (result == CURLE_OK) curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
    curl_easy_cleanup(curl);
    curl_global_cleanup();
    if (fclose(file)) status = -1;
    if (result != CURLE_OK || status != 200) unlink(destination);
    return status;
}

typedef struct { int queue; int directory; } Watcher;

void *kvik_watch_open(const char *directory) {
    Watcher *watcher = malloc(sizeof(Watcher));
    if (!watcher) return NULL;
    watcher->queue = kqueue();
    watcher->directory = open(directory, O_EVTONLY);
    struct kevent change;
    EV_SET(&change, watcher->directory, EVFILT_VNODE, EV_ADD | EV_CLEAR, NOTE_WRITE, 0, NULL);
    if (watcher->queue < 0 || watcher->directory < 0 || kevent(watcher->queue, &change, 1, NULL, 0, NULL) < 0) {
        kvik_watch_close(watcher);
        return NULL;
    }
    return watcher;
}

int kvik_watch_wait(void *value, int timeout_seconds) {
    Watcher *watcher = value;
    struct kevent event;
    struct timespec timeout = { timeout_seconds, 0 };
    return kevent(watcher->queue, NULL, 0, &event, 1, &timeout);
}

void kvik_watch_close(void *value) {
    Watcher *watcher = value;
    if (watcher->queue >= 0) close(watcher->queue);
    if (watcher->directory >= 0) close(watcher->directory);
    free(watcher);
}

int kvik_remove(const char *path) { return remove(path); }

static volatile sig_atomic_t interrupted;
static void on_signal(int number) { interrupted = number; }

int kvik_run(char *const *arguments, int timeout_seconds) {
    posix_spawnattr_t attributes;
    posix_spawnattr_init(&attributes);
    posix_spawnattr_setflags(&attributes, POSIX_SPAWN_SETPGROUP);
    posix_spawnattr_setpgroup(&attributes, 0);
    pid_t child;
    int result = posix_spawnp(&child, arguments[0], NULL, &attributes, arguments, environ);
    posix_spawnattr_destroy(&attributes);
    if (result) return 127;
    interrupted = 0;
    struct sigaction action = {0}, old_int, old_term;
    action.sa_handler = on_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGINT, &action, &old_int);
    sigaction(SIGTERM, &action, &old_term);
    struct timespec start, now, pause = {0, 20000000};
    clock_gettime(CLOCK_MONOTONIC, &start);
    int status = 0, exit_code = 0;
    for (;;) {
        pid_t observed = waitpid(child, &status, WNOHANG);
        if (observed == child) {
            exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            break;
        }
        if (observed < 0 && errno != EINTR) { exit_code = 1; break; }
        clock_gettime(CLOCK_MONOTONIC, &now);
        if (interrupted || now.tv_sec - start.tv_sec >= timeout_seconds) {
            exit_code = interrupted ? 128 + interrupted : 124;
            kill(-child, SIGTERM);
            struct timespec grace = {0, 200000000};
            nanosleep(&grace, NULL);
            kill(-child, SIGKILL);
            while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
            break;
        }
        nanosleep(&pause, NULL);
    }
    sigaction(SIGINT, &old_int, NULL);
    sigaction(SIGTERM, &old_term, NULL);
    return exit_code;
}
