// Nuvio Z on iOS: the installer core running inside the app (Phase 2). See ffi/src/lib.rs.
// Functions that do I/O block: call them off the main thread. Free returned strings with nzs_string_free.
#ifndef NUVIOZ_SIGNER_H
#define NUVIOZ_SIGNER_H

#include <stdbool.h>

// Connects to this phone through LocalDevVPN by both routes and lists its apps. address may be NULL (10.7.0.1).
char *nzs_probe(const char *documents_dir, const char *address);

// Bundle ids and the current signature's expiry of the app at app_dir. JSON.
char *nzs_status(const char *app_dir);

// The newest profile installed on the phone for each of the app's bundle ids. JSON. address may be NULL.
char *nzs_installed_profiles(const char *app_dir, const char *documents_dir, const char *address);

// Renews the app at app_dir with fresh profiles (no reinstall). JSON. address may be NULL.
// Not interactive: never waits for a code; Apple asking for one ends it with kind "codeNeeded".
char *nzs_refresh(const char *app_dir, const char *documents_dir, const char *email, const char *password, const char *address, bool interactive);

// Signs and installs a newer IPA (https URL or path on the phone) over this app. sha256 and address may be NULL.
// iOS ends the app when the install finishes. JSON.
char *nzs_install_update(const char *app_dir, const char *documents_dir, const char *source, const char *sha256, const char *email, const char *password, const char *address);

// While nzs_install_update runs: { stage, fraction } as JSON; otherwise NULL.
char *nzs_update_progress(void);

// While a sign-in waits for Apple's code: the prompt as JSON; otherwise NULL.
char *nzs_pending_two_factor(void);

// Answers the waiting prompt with a code, or NULL to give up. False if nothing was waiting.
bool nzs_answer_two_factor(const char *code);

// While an update waits for the person to pick certificates to revoke: the list as JSON; otherwise NULL.
char *nzs_pending_certificates(void);

// Answers the chooser with a JSON array of serials, or NULL to stop. False if nothing was waiting.
bool nzs_answer_certificates(const char *serials_json);

// Moves the signing key the installer left in Documents into private storage. JSON.
char *nzs_import_signing_key(const char *documents_dir);

// Presence of the signing key (for email, may be NULL) and the anisette identity. JSON.
char *nzs_signing_state(const char *email);

// Forgets the anisette identity. JSON.
char *nzs_reset_sign_in(void);

// This library's version.
char *nzs_version(void);

void nzs_string_free(char *s);

#endif
