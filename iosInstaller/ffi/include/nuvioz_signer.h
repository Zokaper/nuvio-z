// Nuvio Z on iOS: the installer core running inside the app (Phase 2). See ffi/src/lib.rs.
// Functions that do I/O block: call them off the main thread. Free returned strings with nzs_string_free.
#ifndef NUVIOZ_SIGNER_H
#define NUVIOZ_SIGNER_H

#include <stdbool.h>

// Connects to this phone through LocalDevVPN by both routes and lists its apps. address may be NULL (10.7.0.1).
char *nzs_probe(const char *documents_dir, const char *address);

// Bundle ids and the current signature's expiry of the app at app_dir. JSON.
char *nzs_status(const char *app_dir);

// Renews the app at app_dir with fresh profiles (no reinstall). JSON. address may be NULL.
char *nzs_refresh(const char *app_dir, const char *documents_dir, const char *email, const char *password, const char *address);

// While nzs_refresh waits for Apple's code: the prompt as JSON; otherwise NULL.
char *nzs_pending_two_factor(void);

// Answers the waiting prompt with a code, or NULL to give up. False if nothing was waiting.
bool nzs_answer_two_factor(const char *code);

void nzs_string_free(char *s);

#endif
