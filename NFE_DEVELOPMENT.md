# NFE development workflow

Nearly Free Energy uses trunk-based development for this OpenEMS fork.

- `main` is the only long-lived NFE branch.
- Create a short-lived branch from the latest `main` for each change.
- Build and test the affected Backend, Edge, or UI components locally before opening a pull request.
- Open pull requests against `main`; do not push feature work directly to `main`.
- Keep pull requests focused and merge them after the required GitHub checks pass.
- Publish container images manually from an exact commit reachable from `main`.

The standard local validation commands are documented in
[the contribution guide](.github/CONTRIBUTING.md). At minimum, run the narrowest
relevant tests for the changed area. For broad changes, run:

```shell
./gradlew checkstyleAll
./gradlew build
./gradlew resolve
```

For UI changes, run:

```shell
cd ui
npm ci
npm run lint
npm test -- --no-watch --browsers=ChromeHeadlessCI
```

Successful local validation is required before opening a pull request. GitHub
checks remain an independent verification of the submitted commit.
