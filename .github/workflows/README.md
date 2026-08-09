# Continuous Integration

## `bump-version-on-merge.yml` — Version bump

Runs on every push to `main` (including PR merges) and bumps the `versionName`
patch and `versionCode` in `app/build.gradle.kts`, committing the result back to
`main`. It runs **before** the build workflows: both `build-debug-apk.yml` and
the `main` half of `android-ci.yml` are chained off it via `workflow_run`, so
every build packages the freshly bumped version. Those chained builds check out
`main` explicitly, because a `workflow_run` event points at the pre-bump commit.
The bump commit is pushed with `GITHUB_TOKEN`, which does not re-trigger
workflows, so the chain cannot loop.

## `build-debug-apk.yml` — Debug APK delivery

Runs after `Bump Version On Merge` completes successfully on `main`, and on
manual dispatch. Builds `:app:assembleGithubDebug`,
uploads the generated `.apk` as a downloadable workflow artifact, and publishes
the same file directly as a prerelease asset on the `debug-apk-latest` tag.
The published APK is renamed to `{AppName}-v{Version}-pr{PullRequestNumber}.apk`
(using `pr0` when no PR is associated, e.g. manual runs).

## `android-ci.yml` — Android CI

Runs on every pull request, and on `main` after `Bump Version On Merge`
completes successfully. Two parallel jobs:

| Check                        | What it does                                   | Intended to block? |
|------------------------------|------------------------------------------------|:------------------:|
| **Build (github debug)**     | `./gradlew :app:assembleGithubDebug` — compiles the app (including native code and the `:contract` module) and uploads the debug APK as an artifact. | Yes |
| **Unit tests (github debug)**| `./gradlew :app:testGithubDebugUnitTest` — runs the JVM unit tests and uploads the HTML/XML report. | Yes |

"Intended to block" reflects the design; a failing check only actually
prevents merge once the [branch-protection rule](#branch-protection) below is
configured.

The build is **debug, unsigned, and uses no repository secrets**, so it runs
unchanged on pull requests opened from forks.

### Branch protection

To make CI enforce quality on `main`, add a branch-protection rule requiring
these status checks:

- `Build (github debug)`
- `Unit tests (github debug)`

### Notes

- **`github` flavor only.** The `playstore` flavor is not built because it
  cannot currently compile: `VpnControl.kt` lives only in the `github` source
  set (`app/src/github/...`) but is imported by shared `main` code. Once that
  split is resolved, add a `playstore` build to the `build` job.
- **`main` may show red** on `Build`/`Unit tests` until the known
  `SettingsFragment.kt:767` smart-cast compile error (issue #659) is fixed.
  This is expected, not a CI misconfiguration.
- **No lint job (yet).** Android Lint is intentionally not run in CI:
  `:app:lintAnalyzeGithubDebug` hangs for many minutes on GitHub-hosted runners
  and produces no report (likely choking on the large generated protobuf
  sources). Run it locally instead — `./gradlew :app:lintGithubDebug` — and
  note that the project sets `lint { abortOnError = false }`, so lint findings
  are advisory by design. Re-adding a CI lint job is worthwhile once the hang
  is diagnosed.
- **Toolchain:** Temurin JDK 21 (Gradle 8.13 rejects Java 24+), plus the pinned
  NDK `27.0.12077973` and CMake `3.22.1` installed via `sdkmanager`. The
  `sdkmanager` install is retried up to three times because its downloads
  occasionally arrive corrupt on GitHub-hosted runners.
- **Action pinning:** all actions (GitHub first-party and third-party alike)
  are pinned to major-version tags. Maintainers who want stricter supply-chain
  hygiene can repin them to full commit SHAs.
