// Nuvio Z on iOS: the installer core running inside the app (Phase 2 spike). See ffi/src/lib.rs.
#ifndef NUVIOZ_SIGNER_H
#define NUVIOZ_SIGNER_H

// Blocks. Returns JSON; free it with nzs_string_free. address may be NULL (LocalDevVPN's 10.7.0.1).
char *nzs_probe(const char *documents_dir, const char *address);

void nzs_string_free(char *s);

#endif
