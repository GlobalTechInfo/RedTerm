# Contributing to RedTerm

Thanks for considering contributing to RedTerm. This document covers how to build the project, run it, and get your changes merged.

## Table of contents

- [Building](#building)
- [Running locally](#running-locally)
- [Project layout](#project-layout)
- [Code style](#code-style)
- [Testing](#testing)
- [Changelog](#changelog)
- [Commits](#commits)
- [Pull requests](#pull-requests)
- [Continuous integration](#continuous-integration)

## Building

Requirements:

- JDK 17
- Android SDK with `build-tools` and platform `android-37`
  (the app builds with `compileSdk 37`, `targetSdk 35`, `minSdk 24`)
- Gradle 9.7.0 (via the included wrapper)
- Android Gradle Plugin 9.2.1 and Kotlin 2.4.10 (declared in the root `build.gradle.kts`)
- `ANDROID_HOME` (or `local.properties` pointing at your SDK)

```bash
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

To build a release APK you need the release keystore configured in
`app/build.gradle.kts` (the repository ships only with the signing
configuration used by CI; locally you can install with the debug build).

### Building proot from source

Proot is cross-compiled for Android with the NDK. See `native/build-proot.sh`.
Pre-built binaries for `arm64-v8a` and `armeabi-v7a` are committed under
`app/src/main/jniLibs/`, so a normal build does not require the NDK.

## Running locally

Install the debug APK, launch the app and follow the first-time setup:

1. Accept the storage and notification permissions on the welcome screen.
2. Pick a distribution and wait for the first-time setup to finish
   (it installs `nano`, `wget`, `sudo`, `bash` and `openssl`).
3. Open a session from the main page.

## Project layout

- `app/src/main/java/com/redtermapp/ui/` - activities and terminal UI
- `app/src/main/java/com/redtermapp/distro/` - distro registry and installation
- `app/src/main/java/com/redtermapp/proot/` - proot binary handling and launch
- `app/src/main/java/com/redtermapp/service/` - foreground service
- `app/src/main/java/com/redtermapp/util/` - helpers (app lock, etc.)
- `app/src/main/assets/bashrc/` - built-in `.bashrc` templates
- `app/src/main/assets/fonts/` - bundled monospace fonts
- `native/` - proot build scripts
- `.github/workflows/` - CI and release automation

## Code style

- Kotlin, following the official Kotlin code style (`kotlin.code.style=official`).
- 4-space indentation.
- Prefer existing patterns: theme-aware colors via `?attr/terminalBg`,
  `?attr/terminalText`, `?attr/extraKeysBg`; `tc()` helpers for colors in code.
- Do not introduce hardcoded colors where a theme attribute exists.
- Do not add comments to code unless they explain something non-obvious.

## Testing

There is no unit test suite yet; verification is manual:

1. Build and install the debug APK.
2. Launch a distro and check the terminal renders and echoes input.
3. Exercise the feature you touched and its neighbours
   (settings, themes, fonts, bash templates, sessions, split view).
4. Run `./gradlew assembleDebug lintDebug` and make sure it is clean.

## Changelog

Every user-visible change must get an entry in `CHANGELOG.md` under
`## [Unreleased]`, using the Keep a Changelog format and the existing
heading style (`### Added`, `### Changed`, `### Fixed`).

The release workflow extracts only the section matching the release tag,
so keep entries inside the section that is currently `[Unreleased]`.

## Commits

- One logical change per commit.
- Write a short imperative subject line, e.g. `add bash template preview`.
- Do not put version numbers or tag names in commit messages;
  versions live only in tags and `CHANGELOG.md`.

## Pull requests

1. Base your branch on `main`.
2. Rebase your branch onto `main` before opening the PR.
3. Describe what the PR changes and why, and reference related issues.
4. Mention how you tested the change.
5. A CI check compiles the debug build for every PR; make sure it passes.

## Continuous integration

- `.github/workflows/ci.yml` - runs on every pull request: verifies the
  Gradle wrapper checksum, compiles the debug build and runs lint.
- `.github/workflows/build.yml` - runs on pushes to `main` and on version
  tags: builds and signs the release APK and creates a GitHub release
  with the changelog section for that tag.

CI signs releases with repository secrets (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); these are never
available in pull requests, so PR checks only compile the debug variant.
