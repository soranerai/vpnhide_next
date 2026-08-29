# Releasing

## Model

- `VERSION` file = **the last released version** on `main`. It is only modified by `release.py`.
- `changelog.d/*.md` = work in progress. One Markdown file per unreleased entry, accumulated via `./scripts/changelog.py` during normal development. See [changelog.md](changelog.md).
- Intermediate builds (main, feature branches, local) get a version string derived from `git describe` — propagated into `module.prop` / APK `versionName` at build time. See [build versions](#build-versions) below.

## Cutting a release

1. Make sure everything you want in the release is merged to `main` and `changelog.d/` contains exactly the fragments that should appear in the release notes.
2. Run the release script with the new version:
   ```sh
   ./scripts/release.py 0.6.2
   ```
   Atomically:
   - rotates every fragment under `changelog.d/` into `history[0]` with `version: "0.6.2"` and deletes the fragment files,
   - writes `0.6.2` to `VERSION`,
   - patches `versionName`/`versionCode` in the public APK metadata and `build.gradle.kts`,
   - regenerates `CHANGELOG.md` and `update-json/changelog.md`.
3. Commit, tag, push:
   ```sh
   git commit -am "chore: release v0.6.2"
   git tag v0.6.2
   git push
   git push origin v0.6.2
   ```

Component compatibility is stored in `data/compatibility.json`. When a
release changes only one native component, update that component's column and
keep the other versions unchanged. `release.py` validates the source and
regenerates `CompatibilityMatrix.kt`; it does not assume that every component
has the release version.

For a public APK tag `vX.Y.Z`, CI downloads native build artifacts from the
private backend release with the same tag. The versions *inside* that release
set are the ones recorded in `data/compatibility.json`; they may differ from
`X.Y.Z` (for example, APK 2.5.4 with kmod 2.5.3).

The private native repository accepts the corresponding independent versions:

```sh
python3 scripts/release.py 2.2.1 --kmod-version 2.1.0 --kpatch-version 2.1.0
```

Omitting both options retains the legacy behavior and updates both native
components to the positional version.
4. Wait for CI to finish the build. CI creates a **draft** GitHub release with all artifacts attached and release notes extracted from `CHANGELOG.md` — review it on the Releases page and click **Publish release** when you're happy.
5. Generate update-json files pointing at the new release assets:
   ```sh
   ./scripts/update-json.sh
   ```
   For an independent native release, update only the component that changed:
   ```sh
   ./scripts/update-json.sh --kmod-version 2.5.5
   ./scripts/update-json.sh --kpatch-version 2.5.5
   # Bridge and KPatch released together, but independently from the APK:
   ./scripts/update-json.sh --bridge-version 2.5.5 --kpatch-version 2.5.5
   ```
   The script preserves the other bridge/KPatch field when only one is
   specified. The selected values must correspond to a row in
   `data/compatibility.json` before the APK that depends on them is released.
   The script downloads each published backend kmod artifact and records its SHA-256
   digest in the matching metadata file. The app requires this digest before
   it will offer root-assisted installation.
6. Commit and push:
   ```sh
   git commit -am "chore: update-json for v0.6.2"
   git push
   ```

## Why update-json is a separate commit

Update-json **must** be committed *after* the GitHub release is **published** (i.e. after you promote the draft to public). Magisk and KSU fetch these files to decide whether an update is available, then download the zip from the URL inside. Draft releases are private — their asset URLs require auth — so update-json must not point at them.

## Notes

- `versionCode` is derived automatically by `release.py` as `major*10000 + minor*100 + patch` (e.g. `0.6.2` → `602`).
- If `changelog.d/` is empty when you run `release.py`, it warns but proceeds — useful for version-only bumps.
- `release.py` refuses to release a version that already exists in `history[]`.

## Build versions

Every packaging step runs `./scripts/build-version.py` to compute the version string stamped into the artifact:

- **On a release tag `vX.Y.Z`:** `X.Y.Z`
- **N commits after the nearest tag:** `X.Y.Z-N-gSHA` (the git describe format)
- **Working tree dirty:** additional `-dirty` suffix
- **No git / no tags:** falls back to the `VERSION` file

This string goes into:

- `module.prop` `version=...` (visible in the Magisk/KSU manager app)
- APK `versionName` (visible in Android Settings → Apps, diagnostic debug zip, `BuildConfig.VERSION_NAME`)
- Inside the zip filenames (only for release tags; dev artifacts in CI keep a stable name)

The backend owns native module metadata and packaging. The public
`lsposed/app/build.gradle.kts` evaluates `build-version.py` at configure time
and sets `versionName` dynamically.

`versionCode` stays at the value baked in by the last `release.py` run (monotonically increasing integer required by Android/Magisk).
