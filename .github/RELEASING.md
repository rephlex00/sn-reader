# Releasing

Publishing a GitHub Release triggers [`workflows/release.yml`](workflows/release.yml), which runs
the tests, picks a version, builds a signed release APK, tags the commit, and attaches
`sn-reader-<version>.apk` to the release.

## Versioning

Versions are `YYYY.MM.build`: calendar year and month plus a build number that restarts each
month (`2026.07.1`, `2026.07.2`, and so on). The workflow reads existing tags to pick the next
build number, or honours the release's own tag if you tagged it `YYYY.MM.N` explicitly.

The Android `versionCode` is derived as `(YYYY*100 + MM)*1000 + build`, which always increases,
so releases install as upgrades over one another.

Local builds are stamped `0.0.0-dev` / versionCode `1`, so a hand-built APK is never mistaken for
a release artifact.

## Signing secrets

Signing requires four repository secrets. Generate a keystore once and keep it somewhere safe:
losing it means future releases can no longer upgrade an already-installed app.

```
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias sn-reader
base64 -i release.jks | pbcopy   # macOS; paste as ANDROID_KEYSTORE_BASE64
```

Add these under **Settings > Secrets and variables > Actions**:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | the key alias (`sn-reader` above) |
| `ANDROID_KEY_PASSWORD` | the key password |

The keystore is decoded into the runner's temp directory and deleted afterwards even if the build
fails; it is never committed. Without these secrets the workflow stops with an explicit error
rather than publishing an unsigned APK.

## Dry run

You can rehearse the whole pipeline without publishing anything: **Actions > Release APK > Run
workflow**. That path builds the APK and uploads it as a workflow artifact instead of attaching it
to a release.
