# Releasing

Every release must be signed with the same key, otherwise Android refuses to update an installed copy.
Keep the keystore and its passwords outside the repository and back them up.

## Build a signed APK locally

```
export RELEASE_KEYSTORE_PATH=/path/to/cartshelf.jks
export RELEASE_KEYSTORE_PASSWORD=...
export RELEASE_KEY_ALIAS=cartshelf
export RELEASE_KEY_PASSWORD=...
./gradlew testDebugUnitTest assembleRelease -PversionName=1.0.0 -PversionCode=1
```

The APK is `app/build/outputs/apk/release/app-release.apk`. Without the `RELEASE_*` variables the APK is built
unsigned and cannot be installed. Attach the APK to a GitHub Release named after the version (`v1.0.0`), and increase
`versionCode` for every release.

## Let GitHub Actions build releases (optional)

Add these repository secrets (Settings → Secrets and variables → Actions):

- `RELEASE_KEYSTORE_BASE64` – the keystore, base64 encoded (`base64 -w0 cartshelf.jks`)
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Then pushing a tag `v*` runs the tests, builds the signed APK with `versionName` taken from the tag and the workflow
run number plus 100 as `versionCode` (so it stays above the locally built 1.1.0, versionCode 3), and attaches `cartshelf-<version>.apk` to the release. Without the secrets the
workflow only reports that signing is not configured.

Release notes: write them in `docs/releases/v<version>.md` before pushing the tag. The workflow puts them at the top
of the release, and the app shows their first lines in the new-version notice, so keep them short and plain.
