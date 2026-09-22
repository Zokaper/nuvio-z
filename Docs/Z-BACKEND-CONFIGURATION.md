# Nuvio Z backend client configuration

Social and Watch Together use Nuvio Z's Supabase project `pzbpghmmordvzcfbayoh`; accounts and base
profile data continue to use the official Nuvio backend. The clients must never be interchanged.

## Local builds

Put these values in the ignored root `local.properties` file:

```properties
NUVIO_Z_SUPABASE_URL=https://pzbpghmmordvzcfbayoh.supabase.co
NUVIO_Z_SUPABASE_PUBLISHABLE_KEY=<project publishable key>
```

`composeApp/build.gradle.kts` also accepts the same names as process environment variables. Values
from `local.properties` take precedence. Blank values are valid: `ZSupabaseConfig.isConfigured` is
then false and the Social/Watch Together runtime stays unavailable rather than falling back to
`api.nuvio.tv`.

The publishable client key is configuration, not a service-role credential, but it still stays out
of Git with the rest of the build's local configuration. Never put a service-role or secret key in
the client.

## GitHub Actions

Android CI, debug releases, stable releases, and iOS CI all decode the repository Actions secret
`NUVIO_LOCAL_PROPERTIES_BASE64` into `local.properties`. Its decoded properties payload must include
the two names above. The workflows intentionally tolerate an absent secret so upstream-style builds
can compile with Social disabled; a release intended to exercise Social must use the configured
secret.

The iOS workflow is the compiler gate for `iosMain`. A successful Windows build or static
expect/actual inspection is not iOS verification.
