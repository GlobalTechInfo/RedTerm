# RedTerm: PRoot Git Repository Deletion Failure

**Project:**
[GlobalTechInfo/RedTerm](https://github.com/GlobalTechInfo/RedTerm)\
**Affected area:** Bundled PRoot runtime (`link2symlink`)\
**Report status:** Investigation and proposed fix --- not yet verified
on the affected Android device\
**Prepared:** 24 October 2026

------------------------------------------------------------------------

## 1. Executive summary

RedTerm users can encounter failures when deleting Git repositories from
inside a PRoot distribution. Running `rm -rf` on a cloned repository may
fail with `Operation not permitted` for files under
`.git/objects/pack/`. Directory listings may show affected entries with
unknown metadata, for example:

``` text
l????????? ? ? ? ? pack-....pack -> ?
```

The leading hypothesis is a bug in PRoot's `link2symlink` hard-link
emulation. PRoot uses this mechanism to represent hard links in
environments where ordinary hard links are not available in the usual
way.

A closely related upstream PRoot issue is:

-   Issue: https://github.com/termux/proot/issues/393
-   Candidate upstream fix:
    https://github.com/termux/proot/commit/03aea836179e5fec9c4cb6ea8eb05a6145ef55b9

The candidate commit changes the ordering used when creating an emulated
link: it reserves the intermediate link before moving the original file.
This is intended to avoid a race or invalid intermediate state that can
leave a file inaccessible.

**This is a plausible fix, not a confirmed RedTerm fix.** It must be
built into RedTerm and tested on the affected phone before release.

------------------------------------------------------------------------

## 2. Observed symptoms

The reported behavior is:

1.  Start a RedTerm PRoot distribution (for example, Alpine or Debian).
2.  Clone a Git repository inside that distribution.
3.  Attempt to delete the cloned directory with `rm -rf`.
4.  Deletion fails with `Operation not permitted` on one or more files
    in `.git/objects/pack/`.
5.  `ls -la` can show the affected entries with question marks instead
    of normal metadata.

The problem was also reported with a fresh clone, making it less likely
to be caused only by a damaged pre-existing repository.

### Example affected paths

``` text
repository/.git/objects/pack/pack-<hash>.pack
repository/.git/objects/pack/pack-<hash>.idx
repository/.git/objects/pack/pack-<hash>.rev
```

The exact filenames and affected files may vary.

------------------------------------------------------------------------

## 3. Environment and implementation context

The relevant RedTerm implementation detail is that PRoot is bundled as
native Android binaries and uses a temporary directory under the app's
private storage for PRoot-related temporary data.

The investigation identified `PROOT_TMP_DIR` and PRoot's `link2symlink`
emulation as relevant areas to inspect.

The repository's native build script was reported to pin this PRoot
revision:

``` text
d4d2a19081c3c07f75250e4ce2980b9fa2f5720f
```

That revision predates the candidate upstream fix listed above.

### RedTerm repository

https://github.com/GlobalTechInfo/RedTerm

### Files identified for the proposed update

-   `native/build-proot.sh` --- selects and builds the PRoot source
    revision.
-   `app/src/main/jniLibs/` --- contains the bundled native binaries.
-   `app/build.gradle.kts` --- Android app version metadata.
-   `CHANGELOG.md` --- release notes.
-   `CONTRIBUTING.md` --- native build documentation.
-   `.github/workflows/` --- CI/build automation.

The existing native build script was reported to build four Android
ABIs:

  Android ABI     Architecture
  --------------- --------------
  `arm64-v8a`     AArch64
  `armeabi-v7a`   ARM 32-bit
  `x86_64`        x86 64-bit
  `x86`           x86 32-bit

Verify the current script and generated artifacts before relying on this
list during implementation.

------------------------------------------------------------------------

## 4. Likely technical cause

PRoot's `link2symlink` feature emulates hard links using symlink-based
bookkeeping.

The upstream issue and candidate commit indicate that the order of
operations matters. If the intermediate link is not reserved before the
original file is moved, another operation or an unexpected intermediate
state can interfere with the emulation. The result can be an entry that
exists in the directory but cannot be resolved or operated on normally.

This explanation is consistent with the symptoms, including inaccessible
Git pack files and `Operation not permitted` during deletion.

However, the exact failure path in RedTerm has not yet been proven with
a trace or a successful before/after test. Other environmental causes
should not be ruled out until the candidate build is tested.

------------------------------------------------------------------------

## 5. Candidate upstream fix

**Upstream commit:**

https://github.com/termux/proot/commit/03aea836179e5fec9c4cb6ea8eb05a6145ef55b9

**Related upstream issue:**

https://github.com/termux/proot/issues/393

The proposed change adjusts the ordering of the intermediate-link
operation so the intermediate link is reserved before the original file
is moved.

### Proposed RedTerm source pin

Update `native/build-proot.sh` from the old pin:

``` bash
PROOT_VERSION="d4d2a19081c3c07f75250e4ce2980b9fa2f5720f"
```

to the candidate fix commit:

``` bash
PROOT_VERSION="03aea836179e5fec9c4cb6ea8eb05a6145ef55b9"
```

Before merging, confirm that this commit is compatible with RedTerm's
current PRoot patches, build flags, Android NDK, and supported ABIs.
Review the full upstream diff and any intervening changes required by
the project.

------------------------------------------------------------------------

## 6. Proposed implementation plan

### Step 1 --- Create a dedicated branch

Use a feature branch rather than committing directly to `main`:

``` text
fix/proot-link2symlink-2.3.1
```

Base it on the current `main` branch.

### Step 2 --- Update the PRoot source pin

Change `PROOT_VERSION` in `native/build-proot.sh` to the candidate
upstream commit. Keep the build reproducible by pinning an exact commit,
not a moving branch name.

### Step 3 --- Rebuild all supported ABIs

Run the project's native build script using the Android NDK and its
documented dependencies. Rebuild all four supported ABIs and replace the
corresponding bundled binaries under:

``` text
app/src/main/jniLibs/
```

Do not upload placeholder files or binaries built for only one
architecture.

### Step 4 --- Update version metadata

The proposed release version is:

-   `versionCode`: `7`
-   `versionName`: `2.3.1`

Update `app/build.gradle.kts` only if the project still has version
`2.3.0` / code `6` at implementation time. Check the current branch
first.

### Step 5 --- Update the changelog

Suggested entry, subject to successful validation:

``` markdown
## [v2.3.1]

### Fixed

- Update the bundled PRoot runtime with an upstream `link2symlink`
  fix intended to prevent files from becoming inaccessible when
  hard links are emulated.
```

Do not describe the issue as definitively fixed until the regression
test passes.

### Step 6 --- Correct native build documentation

The existing `CONTRIBUTING.md` was reported to describe prebuilt
binaries for only two ABIs. If that is still inaccurate, update it to
describe all four supported ABIs and the actual command used to rebuild
them.

Suggested wording:

``` markdown
Pre-built PRoot binaries for the supported Android ABIs are committed
under `app/src/main/jniLibs/`. To rebuild them, install the documented
Android NDK and native build dependencies, then run
`native/build-proot.sh` as described below.
```

Keep the instructions aligned with the real script's arguments and
prerequisites.

### Step 7 --- Build and test before release

Open a pull request, run CI, build a debug APK, and install it on the
affected Android phone.

Do not merge, tag, or publish a release until the regression test and
compatibility checks pass.

------------------------------------------------------------------------

## 7. Regression test plan

Test on the same RedTerm version, Android device, and PRoot distribution
where the failure was originally observed.

### A. Reproduce the original failure on the old build

If possible, record:

-   RedTerm version and build number.
-   Android version and device architecture.
-   PRoot distribution and version.
-   Exact Git command used to clone.
-   Exact `rm -rf` command and complete error output.
-   `ls -la` output for the affected pack directory.

### B. Test the candidate build

Inside the PRoot distribution:

``` sh
mkdir -p ~/proot-link-test
cd ~/proot-link-test
git clone <a-small-test-repository-url> repo
cd repo
ls -la .git/objects/pack/
cd ../..
rm -rf repo
test ! -e repo && echo "PASS: repository deleted"
```

Use a real repository URL in place of the placeholder. Prefer a
repository that has packed Git objects so the test exercises
`.git/objects/pack/`.

### C. Repeat and broaden the tests

-   Clone and delete the same repository several times.
-   Test a larger repository with pack files.
-   Test deletion of a repository after normal Git operations such as
    `git fetch` or `git gc`.
-   Test ordinary file and directory deletion.
-   Test both Alpine and Debian if both are supported and available.
-   Confirm no unrelated PRoot behavior regresses.
-   Test each ABI that the project can practically run on.

### Pass criteria

-   `rm -rf` completes without `Operation not permitted`.
-   The cloned directory and its `.git/objects/pack/` contents are
    removed.
-   No affected entries remain with unknown metadata.
-   Ordinary PRoot operations continue to work.

Capture logs and device/build details with the test result.

------------------------------------------------------------------------

## 8. Build automation considerations

A GitHub Actions workflow can rebuild and commit the native binaries,
provided the workflow has the required repository write permissions.

A proposed workflow file is:

``` text
.github/workflows/build-proot.yml
```

It should:

1.  Run only for the feature branch or by an explicitly selected manual
    dispatch.
2.  Check out the selected branch.
3.  Install the native build dependencies, including the required `gawk`
    version/package.
4.  Install the Android SDK and the NDK version supported by the
    project.
5.  Run `native/build-proot.sh` for all supported ABIs.
6.  Verify that the expected output binaries exist.
7.  Commit the rebuilt binaries to the same feature branch.

Before using automation, inspect the existing CI and build workflows and
reuse their SDK/NDK versions and conventions. The workflow must not push
to `main` or publish a release automatically.

A workflow using `GITHUB_TOKEN` needs an explicit `contents: write`
permission. Repository or organization policy can still restrict writes.
Also note that pushes made using the standard Actions token generally do
not trigger another workflow run.

------------------------------------------------------------------------

## 9. GitHub integration obstacle encountered during this work

An attempt was made to create the feature branch through the connected
GitHub integration:

``` text
fix/proot-link2symlink-2.3.1
```

The operation repeatedly failed with:

``` text
HTTP 403
Resource not accessible by integration
```

Checks performed through the connection reported:

-   Authenticated GitHub account: `GlobalTechInfo`.
-   Repository: `GlobalTechInfo/RedTerm`.
-   Repository metadata reported `admin: true` and `push: true`.
-   The integration could read repository metadata and files.
-   The integration's installation listing returned no installations.
-   Disabling branch protection and reconnecting did not resolve the
    failure.

**No branch was created and no repository files were modified by those
failed attempts.**

The available evidence points to a write-authorization or integration
limitation in the ChatGPT GitHub connection. It does not establish that
the user's GitHub account lacks repository permissions. This integration
issue is separate from the PRoot runtime bug.

Possible ways to proceed:

-   Retry the GitHub integration after its authorization issue is
    resolved.
-   Create the feature branch and apply the patch locally using Git.
-   Use GitHub Actions to build the native binaries once the branch and
    workflow are in place.

Do not share personal access tokens, passwords, or private SSH keys in
chat.

------------------------------------------------------------------------

## 10. Risks and safeguards

-   **Candidate fix may not address the exact RedTerm failure.** Require
    an on-device before/after regression test.
-   **Upstream changes may affect other behavior.** Review the full
    commit and any required surrounding changes.
-   **Native ABI mismatch can break app startup.** Verify each generated
    binary's architecture and package it under the correct ABI
    directory.
-   **Build environment drift can make binaries non-reproducible.** Pin
    source revisions and document SDK/NDK versions.
-   **Do not release prematurely.** Use a pull request and test APK
    before merging or publishing.
-   **Do not overwrite user data during testing.** Use disposable test
    repositories and directories.

------------------------------------------------------------------------

## 11. Current status

  -----------------------------------------------------------------------
  Item                                Status
  ----------------------------------- -----------------------------------
  Deletion failure reported           Observed by user

  Affected `.git/objects/pack/`       Reported
  entries identified                  

  `link2symlink` identified as likely Strong hypothesis
  cause                               

  Related upstream issue identified   Yes

  Candidate upstream fix identified   Yes

  Candidate fix tested in RedTerm     Not yet

  Feature branch created through      Blocked by HTTP 403
  ChatGPT integration                 

  Repository files modified           No

  New APK built and tested            No

  Release `v2.3.1` published          No
  -----------------------------------------------------------------------

### Recommended next action

Prepare the patch on a feature branch, rebuild the PRoot binaries for
all supported ABIs, and test a debug APK on the affected Android phone.
Treat the upstream commit as a candidate until the regression test
confirms the fix.
