# TSF Droid Releases

TSF Droid ships signed APKs through GitHub Releases. Every release artifact is
built in public CI (GitHub Actions), unit-test gated, signed with the dedicated
TSF Droid release key, and published with SHA-256 checksums.

## Cutting a release

1. **Bump the version** in `app/build.gradle` (`versionName`, `versionCode`).
2. **Add a changelog section** at the top of `CHANGELOG.md` with a header
   matching the tag (e.g. `## v1.0.1 — Summary (date)`). The workflow publishes
   this section as the release notes.
3. **Tag and push**:
   ```bash
   git tag v1.0.1
   git push origin main v1.0.1
   ```
   Alternatively use **Actions → TSF Droid Release → Run workflow** and enter
   the version; the tag is created for you. Mark pre-releases with the
   "pre-release" input.

The workflow (`release.yml`) then: verifies signing secrets exist → runs
`testDebugUnitTest` → assembles signed `release` + `debug` APKs → verifies the
APK signature with `apksigner` → generates `SHA256SUMS.txt` → extracts the
matching `CHANGELOG.md` section → publishes the GitHub Release with both APKs.

## Release assets

| Artifact | Purpose |
|---|---|
| `TSF-Droid-vX.Y.Z-release.apk` | Signed, R8-minified build for sideloading (recommended) |
| `TSF-Droid-vX.Y.Z-debug.apk` | Debug build with logging, for developer testing |
| `SHA256SUMS.txt` | Checksums of every attached artifact |

Historical changelogs: see [CHANGELOG.md](CHANGELOG.md).

## Signing

* The release key lives in GitHub repo secrets (`RELEASE_KEYSTORE_BASE64`,
  `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) and is
  decoded by CI at build time; it is never committed.
* Locally, put the keystore at the project root as `tsf-droid-release.keystore`
  and the three credential properties in your untracked `~/.gradle/gradle.properties`
  (see `gradle.properties.example`).
* Release builds **fail loudly** without signing credentials rather than falling
  back to the publicly known debug key; CI compile-only checks can pass
  `-PallowUnsignedRelease` explicitly.
* Updates install over previous versions only when signed with this same key —
  keep the keystore backup safe.

## Continuous integration

* `build-and-test.yml` runs on every push to `main`: unit tests + debug APK
  artifact upload (`tsf-droid-debug`, 7-day retention).
* `release.yml` runs on `v*` tags and manual dispatch as described above.
