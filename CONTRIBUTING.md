# Contributing to VPNHide

We welcome contributions of all types: bug fixes, new features, documentation, and translations.

## Commit messages

We follow a loose conventional-commits style for changelog generation and easier history browsing.

- `feat:` — new feature
- `fix:` — bug fix
- `docs:` — documentation
- `style:` — formatting, missing semi colons, etc
- `refactor:` — code change that neither fixes a bug nor adds a feature
- `perf:` — code change that improves performance
- `test:` — adding missing tests
- `chore:` — tooling, release, CI, build
- `ci:` — CI-only changes

Scope the prefix where useful: `feat(lsposed): …`, `fix(kmod): …`.

Keep messages focused on *why*, not *what* — the diff already shows what changed.

## Branching model

- `main` — production-ready code. Every commit here must pass all CI checks.
- `dev` — ongoing development. Can be unstable.
- feature branches — use `feat/name` or `fix/name` branches for PRs.

## Development workflow

1. Fork the repository.
2. Create a feature branch.
3. Make your changes.
4. Add a changelog fragment (see [AGENTS.md](AGENTS.md)).
5. Open a Pull Request.

## Coding style

- **Kotlin**: Follow official Kotlin style guide. Use `ktlint` to check.
- **Rust**: Use `rustfmt` and `clippy`.
- **C**: Follow Linux kernel coding style for `kmod`.

## UI/UX guidelines

We aim for a "premium" feel. Use modern components, smooth animations, and a cohesive color palette. Avoid simple MVPs; prioritize visual excellence.
