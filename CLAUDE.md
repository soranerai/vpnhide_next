# vpnhide_next — repo conventions for AI agents and contributors

Quick orientation file for anyone (or anything) working on this repo.
Read in full before opening a PR; it's short on purpose.

This file is also exposed as `AGENTS.md` (symlink) so vendor-neutral
tooling that follows the AGENTS-convention picks it up automatically.

## Project layout

- `kmod/` — kernel module (hiding interfaces + ports), kretprobes-based
- `lsposed/` — LSPosed module + Compose target-picker app

## Read before touching code

These short files cover everything specific to this repo. Skipping them leads to broken commits and unnecessary review iterations.

- [CONTRIBUTING.md](CONTRIBUTING.md) — PR process, commit conventions, changelog requirement
- [docs/development.md](docs/development.md) — prereqs, per-module build quickstart, keystore setup, device install, CI lints
- [docs/state.md](docs/state.md) — every persistent path / proc entry / iptables chain the project touches; who writes, who reads, lifetime
- [docs/changelog.md](docs/changelog.md) — changelog storage (`changelog.d/` fragments + history JSON), `./scripts/changelog.py` usage
- [docs/releasing.md](docs/releasing.md) — `./scripts/release.py` usage, version-bump flow
- [kmod/BUILDING.md](kmod/BUILDING.md) — kernel-module build (one-command DDK via `./kmod/build.py`, GKI matrix, troubleshooting)

## Workflow rules

- **Add a changelog entry BEFORE committing user-visible changes:**
  ```sh
  python3 ./scripts/changelog.py <type> "<EN text>" "<RU text>"
  # types: added | changed | fixed | removed | deprecated | security
  ```
  This writes a Markdown fragment to `changelog.d/<type>-<slug>-<hex4>.md` — nothing else. `CHANGELOG.md` is regenerated only at release time (that's what keeps PRs from conflicting on it). Commit just the new fragment alongside the code change. To preview pending entries: `./scripts/preview-changelog.py`. Skip the entry for internal refactors / docs-only / CI-only / test-only changes.
- **Do not bump `VERSION` or run `./scripts/release.py` unless the maintainer explicitly asks for a release.** Fragments under `changelog.d/` don't need a version number. `release.py` rotates every fragment into `history[0]` of `changelog.json`, deletes the fragment files, and is maintainer-only.
- **Don't put `#NN` in commit messages or PR titles to refer to local review notes.** GitHub auto-links `#NN` to PR/issue numbers in this repo, and the cross-reference will almost certainly point at the wrong PR. Real GitHub references (`fixes #38` where #38 is an actual issue) are fine — verify the number first.

## Build entry points

Single-command builds for both CI and local — the same scripts run in both places.

- **kmod**: `./kmod/build.py --kmi <kmi>` (or `--all`). Auto-spawns the DDK podman/docker container if you're not already inside it; CI passes `--inside-container` to skip the spawn. The DDK image tag is `DDK_IMAGE_TAG` in `kmod/build.py` — `.github/workflows/ci.yml` mirrors it, bump both together.
- **lsposed APK**: `cd lsposed && ./gradlew :app:assembleRelease`.

The private native repository builds the Rust cdylib with the 16 KiB-page
alignment required by modern Android devices. The public repo consumes the
resulting prebuilt `.so` from CI; do not add a public native source copy.

## Design notes

- **KPatch-Next KPM:** `kmod/` currently uses kretprobes and requires per-GKI-generation builds (kernel headers + `Module.symvers`). Porting to a [KPatch-Next](https://github.com/KernelSU-Next/KPatch-Next) KPM would produce a single binary for kernels 3.18–6.12, eliminating the multi-GKI build problem. Tradeoff: adds KPatch-Next as a hard dependency (already bundled in KernelSU-Next).
