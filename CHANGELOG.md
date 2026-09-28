# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [v2.0.0]

### Fixed

- **Bundled OpenSSH client crashed on startup with `SIGSEGV`.** OpenSSH calls `getpwuid()`
  unconditionally at the top of `main`, and bionic resolves that through NSS, which reaches its
  service modules through the dynamic loader. The client was built fully static, so it had no
  loader, and NSS dereferenced a NULL function pointer: the client died at fault address `0x0`
  before printing anything, on every invocation including `ssh -V`. The client is now a dynamically
  linked bionic PIE.
- **`getpwuid`/`getpwnam` no longer reach bionic's NSS.** `native/ssh_compat.c` defines
  `getpwuid`, `getpwuid_r`, `getpwnam` and `getpwnam_r`. As the executable, the client's
  definitions take precedence over the shared libc. This covers `auth.c`'s `getpwnam()` during
  authentication too, which would otherwise have failed the same way later.
- **`HAVE_REALLOCARRAY`, `HAVE_NL_LANGINFO` and `HAVE_LANGINFO_H` are no longer claimed.** They
  were listed as provided by bionic, but neither `libc.so` nor `libc.a` at API 24 defines those
  symbols and the headers do not declare them (`reallocarray` is API 26). The claim made OpenSSH
  call functions that do not exist, failing to link with undefined references.
- **Empty and quoted arguments in one-shot proot commands are no longer lost.** The command was
  interpolated raw into `sh -c '...'`, so `ssh-keygen -N '' -C comment` became `-N -C comment`:
  keygen read `-C` as the passphrase and rejected the command line. Commands are now escaped with
  proper POSIX quoting, with regression tests. This affected every one-shot command, not only key
  generation.
- **No infinite recursion or ANR when the client is unusable.** `ensureInstalled()` is re-entrant
  guarded, the install check and the diagnostic no longer route back through it, and the
  diagnostic runs off the UI thread. A failing probe used to recurse until the stack was exhausted
  while spawning a `logcat` process per level.
- Paths passed to the bundled client map into the rootfs correctly: keys are generated at
  `/root/.ssh/...` rather than `/.ssh/...`.

- **Fixed: backup excluded more than the launcher binds.** The bind list is now a single
  source of truth in `ProotLaunch` (`BOUND_DIRS` / `BOUND_FILES`) used by both the
  launcher and the backup, so a bind added in one place can no longer be forgotten in the
  other. `/linkerconfig/ld.config.txt` is bound as a single *file*, but the backup
  excluded the whole `/linkerconfig` directory, which would have silently discarded any
  real content a distro shipped there. The old test passed that over-exclusion only
  because it explicitly excused it; it now fails on it.
- **Fixed: the welcome header still filled the screen in landscape.** The collapsed
  header relied on a `values-land` override, which is not reliably applied on an activity
  that handles rotation itself to keep an in-progress install alive. The header metrics
  are now applied directly from the configuration the activity has, so the distro list
  gets the height instead of a strip showing one card's name.
- **Fixed: tapping the terminal did not open the soft keyboard.** It was being requested
  on focus, but tapping a view that already holds focus produces no focus change, so
  nothing happened. It is requested on touch again, without the synthetic
  `performClick()` that previously stopped text being entered.
- **Fixed: the soft keyboard no longer opened when tapping the terminal, and text could
  not be entered.** The terminal now asks the input method to show itself when it gains
  focus, rather than through an OnTouchListener. Tapping a focusable view focuses it, so
  the effect is the same, and the terminal's own touch handling is left alone. The
  OnTouchListener version fired a synthetic click on every release, which stopped text
  being entered.
- **Fixed: the welcome screen kept the portrait layout after rotating.** WelcomeActivity
  handles the configuration change itself so an in-progress install survives, which means
  the card rows are no longer rebuilt automatically; they are now rebuilt explicitly.
- **Fixed: rotating the phone during a distro install reported "installation
  cancelled" though the user cancelled nothing.** `onDestroy` cancelled the download
  unconditionally, and it also runs when the activity is recreated by a configuration
  change. It now cancels only when the activity is actually finishing.
- **Fixed: the soft keyboard no longer opened when tapping the terminal.** Tapping did
  not bring the keyboard up, and with no keyboard the back key had nothing to dismiss.
  The terminal now asks the input method to show itself on touch. The listener returns
  false, so selection and long-press are unaffected, and it is a no-op when the keyboard
  is already visible.
- **Removed the manual keyboard-inset padding.** `adjustResize` already resizes the
  window; padding the content by the same inset as well consumed the terminal's whole
  height on a landscape screen.
- **Fixed: the welcome header consumed the whole screen in landscape.** The title sat at
  48dp with a 36sp text size, with the Home and Settings rows at 16dp, all pinned to the
  top of the parent. On a short landscape screen that left the distro list almost no
  height. The header metrics now come from dimension resources with a landscape-specific
  override; portrait is unchanged.
- **Fixed: the second extra-keys row was pushed off screen in landscape.** The keyboard
  inset was applied as bottom padding in full. On a landscape phone the keyboard is tall
  enough that the toolbar plus the two 40dp key rows no longer fit, so the weighted
  terminal view collapsed to zero height and the second row was clipped. The padding is
  now capped at the space remaining after the fixed chrome, so the key rows always win
  and the terminal view absorbs what is left. Portrait was unaffected because it has the
  height to spare.
- **Fixed: hiding the extra keys left an empty 40dp strip.** Only the inner container was
  hidden while the outer `HorizontalScrollView`, which is what actually occupies space,
  stayed visible. Both are now hidden together.
- **Fixed: distro card descriptions were clipped on the welcome screen in landscape.**
  The card rows now measure by content, and in multi-column mode the description is
  bounded to three lines with an ellipsis so a long description cannot push the card past
  the visible area. Portrait still shows the full text.
- **Fixed: the back button could not dismiss the soft keyboard in the terminal.** The
  activity's `dispatchKeyEvent` forwards key events to the focused terminal view, which
  is focusable and consumes them, so the `||` short-circuit meant the platform never saw
  BACK and the input method was never closed. The only way out was to background the app,
  and the keyboard reappeared on return. BACK is now handled above that forwarding
  whenever the IME is visible, and the up event is swallowed too. This follows the
  platform rule that BACK belongs to the input method while it is showing, matches
  Termux, and behaves identically during first-time distro setup.
- **curl progress is left visible during downloads.** A silent transfer on a phone gives
  no sign of life and a slow link looks like a hang. Errors still stand out on stderr.
- **Documented that Arch and openSUSE have a deliberately long first startup**, why it
  is a full system upgrade and repository key import respectively, and that a first
  start returning in about a second is a bug rather than a fast system.
- **Fixed: the root cause behind every distribution script bug.** The distro was
  taken from `/etc/os-release`'s `ID` verbatim as a `plan()` key. Arch Linux ARM reports
  `ID="archlinuxarm"` and openSUSE Leap reports `ID="opensuse-leap"`, so neither matched
  a key and both silently fell through to the `else` fallback, which is a completely
  empty plan. The resulting startup script contained `update_ok=1` and `then : bash` -
  no mirror setup, no download command, no package-manager repair and no system update -
  while still printing "Setup complete." within a second. This is why so many
  distribution fixes appeared to change nothing. IDs are now mapped onto known plan keys,
  with the substring tests used only as a last resort for a missing or unreadable
  `/etc/os-release`.
- **Known: a partially upgraded Arch rootfs can leave pacman unusable.** Installing
  packages without first running the full `pacman -Syyu` can pull in a `libcurl` built
  against a newer glibc than the rootfs provides, and because pacman is linked against
  libcurl, pacman then fails with "`GLIBC_2.43' not found". A full `pacman -Syyu` upgrades
  glibc in the same transaction and avoids this. A rootfs already in that state has to
  be reinstalled.
- **Fixed: `.startup` was never rewritten on a resumed session.** This is the one
  that mattered most. The startup script is generated by the app, but it was only
  written by `createNewSession()`. A session that outlived an app update was
  re-attached without ever reaching that code, so it kept running the script from the
  day its rootfs was created. Every repository and package-manager fix in this
  changelog was therefore dead code on the device: the openSUSE repository files were
  never rewritten, and the Arch download command was never installed. `.startup` is now
  regenerated whenever a session is resumed, and because a shell cannot be
  retro-fitted, the session is restarted when the script has actually changed.
- **openSUSE: fixed the zypp cache never being cleared.** zypp creates its raw and
  solv directories read-only, so `rm -rf` failed with "Operation not permitted" and the
  error was discarded. The stale raw cache kept naming the old colon-prefixed aliases.
  The directories are now made writable before removal.
- **Arch: fixed the `gcc-libs` conflict with `--overwrite`.** Installing the split
  `libgcc`/`libstdc++` packages first is refused, because pacman checks file conflicts
  before unpacking anything; removing `gcc-libs` first is worse, because it owns the
  `libstdc++.so.6` that pacman itself is linked against. `--overwrite` takes ownership
  of the files as the replacements are unpacked, in one transaction.
- **Arch: fixed the pacman download command.** The image's `pacman.conf` ships the
  `XferCommand` example commented out. The insert that was meant to enable it used
  `sed '/pattern/a text'`, a GNU extension busybox does not implement, so it failed
  silently and pacman fell back to its own downloader, which stalls on a mobile link.
  It is now a plain substitution, verified with `grep` so a failure cannot be silent
  again. `-C -` was also dropped: its resume requests were answered with 404, and
  combined with `-f` that turned completed transfers into "failed to download".
- **Arch: fixed the `gcc-libs` repair never running.** Its guard required
  `pacman -Si libstdc++`, which queries the sync database, and the prepare step runs
  before the first `pacman -Syy` on a fresh rootfs, so the lookup failed and the repair
  was skipped. The check is now local and idempotent. The repair also installs the
  split `libgcc`/`libstdc++` packages *before* removing `gcc-libs`, because `gcc-libs`
  owns the `libstdc++.so.6` that pacman itself is linked against; removing it first can
  leave pacman unable to start.
- **Arch: fixed an invalid `case` branch and a missing command separator in the
  generated startup script.** The `aarch64|armv7l)` branch had an empty body and the
  `XferCommand` insert had no trailing `;`, which is a shell syntax error, so the whole
  prepare step silently did nothing. Every generated script is now checked with `sh -n`,
  which is what caught it.
- **Known: a fresh Arch rootfs needs `pacman -Syyu` once.** The image's package
  database is older than the mirror, so versions it names have been superseded and
  removed, and installing against it 404s. Documented in the README.
- **Fixed: first-time setup was skipped for every distro except Alpine.** Each
  distro's update step ran inside `if ! command -v bash`. Every image ships bash, so
  the update never ran, the `if` returned 0, and setup reported success while having
  done nothing. Alpine was the only distro that worked, and only by accident. The
  update now runs unconditionally and its exit status is checked.
- **Fixed: openSUSE repository aliases were never rewritten.** The `sed` expression
  used `[^]]`, which is not portable; sed aborted with "unterminated `s' command" on
  every run and the error was discarded, so the files kept their `openSUSE:` aliases
  and every `zypper refresh` failed with "Can't open solv-file:
  /var/cache/zypp/solv/openSUSE:repo-oss/solv". The expression is now portable POSIX
  and both `/etc/zypp/repos.d` and `/usr/share/zypp/repos.d` are covered. A failed
  rewrite now warns instead of passing unnoticed.
- **Regression tests now execute the generated shell instead of only parsing it.**
  `sh -n` cannot detect a `sed` expression that parses but fails at runtime, which is
  exactly how the openSUSE bug survived.
- **Arch and Manjaro self-heal the obsolete `gcc-libs` conflict.** The cleanup ran
  *after* the first upgrade, so an already-broken rootfs stayed broken forever; it now
  runs before the upgrade and on every session.
- **Removed the temporary mirror diagnostics** from Arch, Manjaro, Rocky and AlmaLinux.
  First-run setup and genuine failures are still reported.

### Added

- **SSH → Test** runs a non-interactive connection check (`BatchMode`, 10 second connect timeout,
  remote command `true`) and shows the verbose trace, so a failed connection names its cause
  instead of dying silently.


- **Kali Linux**: added as a supported distro, installed from NetHunter's own rootfs on `kali.download` (it is not published by Termux proot-distro). Available on all four architectures.
- **Free-space check before install**: the installer now measures available storage against the space a distro needs and refuses up front with a clear message instead of failing part-way through extraction.
- **Distro diagnostics**: a **Diagnose** action on every installed distro reports on 12 health checks (rootfs layout, root uid, DNS, `bash`, `busybox`, `/bin/sh`, `/usr` permissions, `/tmp`, device nodes, `sudo`, `/root`, size) and can be copied to the clipboard.
- **File manager actions**: every row has a visible **⋮** button for Rename, Copy, Cut, Delete, plus Edit for text files, Open in terminal and Folder info for folders. New folder, paste and refresh actions, a hidden-files toggle, Go to `/root` and a folder size summary.
- **Recursive file search**: search now walks the entire subtree below the current folder and updates as you type, instead of only matching names in the folder you were looking at. Results are capped and it says so when truncated.
- **File manager navigation**: the back arrow and the system back gesture both go up one directory instead of leaving the app, and the arrow keeps the same appearance used everywhere else in the app.
- **Image and video preview**: images open with automatic downscaling and EXIF rotation so photos are not sideways; videos play in place.
- **Text editor**: text files can be edited in the app and saved back into the rootfs. Saving stages a temporary file and renames it over the original, so an interrupted write cannot truncate a config, and the original permission bits are re-applied first so scripts keep their executable bit.
- **Per-distro and multiple sessions**: launching a distro that has no session open now creates one instead of re-showing whatever session happened to be open, and **New Session** offers every installed distro — picking the current one starts a second, independent session.
- **Package updates**: run a distro's own package manager update from the app, with the real output streamed live, and a notification when it finishes.
- **Base image update**: check whether Termux publishes a newer proot-distro image for an installed distro and swap it in, keeping `/root` and `/home`. If anything fails, the previous install is put back.
- **Storage usage**: free and total space on shared storage, plus per-distro the largest directories with proportional bars.
- **SSH**: save servers and connect straight from the app. A full **OpenSSH client is bundled with the app**, cross-compiled for all four ABIs, so a connection needs no distribution installed and does not depend on a distro shipping `openssh-client`. It is unpacked into a small rootfs of its own and run through proot, because Android 12+ mounts app storage `noexec` — the same constraint the distro binaries already work around — so the client is subject to exactly the same rule. Host key checking is on, and an ed25519 key pair can be generated from the SSH screen.
- **ANSI recordings**: record the output of a command with colours and cursor control intact, then replay it in the app. Recordings can be shared or deleted.
- **Backup manager**: a dedicated screen lists backup archives with size and date, and offers restore, share or delete for each, plus cleanup of files left behind by interrupted backups.
- **App log**: Settings → App log keeps a persistent, bounded log of the app. It can be copied to the clipboard, shared, or downloaded to `Downloads/`, and **Capture system log** adds everything the process wrote to logcat, including output from the support libraries, so a failure that ends a process immediately can actually be diagnosed.
- **Completion notifications**: background work (package updates, base image updates, backups, recordings) posts a notification when it finishes.
- **Termux proot-distro rootfs**: all distros now install from the official prebuilt rootfs images published by [termux/proot-distro](https://github.com/termux/proot-distro), extracted and verified in-app.
- **Per-architecture SHA-256 verification**: every distro/arch pair ships a hardcoded checksum; downloads are verified before extraction and a mismatch discards the partial file and asks for a clean retry.
- **Resumable distro downloads**: interrupted downloads resume over HTTP `Range` instead of restarting from zero; a corrupt or server-rejected range falls back to a full re-download automatically.
- **Crash reports**: uncaught exceptions are recorded centrally under Settings → Crash Reports, following Termux's rollover scheme (`crash-report.txt`, then `crash-report2.txt`, … at 4096 characters each). Reports can be viewed and copied in-app, downloaded to `Downloads/RedTerm-crash-report-<timestamp>.txt`, or deleted.
- **File viewer**: tapping a file in the built-in file manager now opens it in a new viewer with monospace text, horizontal scrolling, binary detection, a 2 MB preview cap and a Copy action. Previously files could be listed but never opened.
- **Home button on the welcome screen**: a Home icon now sits opposite Settings, so Home is reachable before any distro is installed.
- **openSUSE**: added as a supported distro.
- **Settings section icons**: seven new vector drawables (`ic_section_distro`, `ic_section_appearance`, `ic_section_font`, `ic_section_terminal`, `ic_section_power`, `ic_section_extra_keys`, `ic_section_app_lock`) shown next to each settings section header.
- **Hardcoded strings extracted**: all hardcoded text in XML layouts moved to `strings.xml` (~90 string resources), enabling full localization.
- **RTL symmetry**: settings layout uses symmetric horizontal padding for proper right-to-left support.
- **Plurals support**: `match_count_for_query` converted from `<string>` to `<plurals>` with proper `getQuantityString()` in code.
- **`KILL_BACKGROUND_PROCESSES` permission**: added to manifest.
- **`ToolbarTitle` theme**: new `TextAppearance` style for toolbar titles (single-line, ellipsize end).

### Changed

- **Rootfs source**: distro downloads point at `termux/proot-distro` release assets instead of self-hosted tarballs.
- **Distro lineup**: openSUSE added. Artix is no longer offered; Kali returns as a NetHunter rootfs (see Added).
- **First-time distro setup**: the setup script now installs `bash` and `sudo` with each distro's own package manager, `unset`s `ENV` and falls back to the distro's own `/bin/sh` when `bash` is unavailable. Alpine previously had no `bash`, leaving users in Android's `mksh` where `bash` was "inaccessible or not found". The script is version-stamped and regenerated on existing installs, and failures no longer abort the whole setup.
- **Per-distro setup correctness**: Arch runs a full `pacman -Syyu` upgrade (a partial `pacman -Sy` against a prebuilt rootfs left packages requiring a newer `GLIBC` than the image shipped); openSUSE clears and recreates `/var/cache/zypp` and disables the x86-only `repo-openh264` repository that broke `solv` cache builds; Fedora/Rocky/AlmaLinux pass `skip_if_unavailable`, lower retries and shorter timeouts so a stale mirror 404 cannot fail the whole run.
- **Shell configs preserved**: `.bashrc` and `.bash_profile` are only written when missing, so user customizations are never overwritten.
- **SHA256 checksums**: populated for every supported distro/architecture rather than omitted.
- **Notification permission flow**: the foreground service is no longer gated on `POST_NOTIFICATIONS` being granted at the moment the activity is created, which meant it was never started and no ongoing notification ever appeared. The service now starts from `onResume` regardless, and the Android 14+ `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` is declared.
- **Rootfs permissions**: extracted directories are `0755` and files `0644`/`0755` (applied with `Os.chmod`), with `umask 022` in the proot launcher. Previously every directory was forced to `0700`, which produced `directory permissions differ on /usr` warnings and blocked some package managers.
- **Distro size display**: computed on a background thread and cached with a 10-minute TTL instead of walking the entire rootfs on the main thread.
- **Terminal toolbar title**: shows only the distro/session name instead of appending the full working-directory path.
- **Version bump**: versionName `1.0.4` → `2.0.0`, targetSdk `35` → `37`.
- **Resource shrinking**: `isShrinkResources = true` enabled in release build.
- **Toolchain**: Gradle `9.7.1` → `9.8.0`.
- **Dependencies**: `core-ktx` `1.18.0` → `1.19.1`, `appcompat` `1.7.0` → `1.8.0`.
- **proot rebuilt from source**: cross-compiled at the current `termux/proot` commit for all four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) and linked with 16 KB page alignment, so RedTerm's own native libraries are loadable on 16 KB-page devices. `native/build-proot.sh` was fixed to install the `libproot*.so` names the app actually loads, build talloc per-ABI, and support all four architectures.
- **Welcome buttons**: changed from `<Button>` to `<MaterialButton>` for proper Material Design styling.
- **Welcome settings button**: simplified from nested `LinearLayout` + `ImageView` to a single `TextView` with `app:drawableEndCompat`.
- **Settings section headers**: now use `app:drawableStartCompat` with tinted icons instead of plain text.
- **Programmatic strings**: all `setText()` calls in Kotlin now use `getString()`/`getQuantityString()` with format strings instead of hardcoded text.
- **Kotlin KTX adoption**: `prefs.edit { }` KTX extension, `.toUri()` extension, `.isVisible` KTX property, and `apply` blocks replaced with `editor.apply { }` throughout.
- **RTL text alignment**: `GravityCompat.END` used instead of hardcoded `gravity="end"` in layouts.
- **Drawable references**: `android:drawableEnd`/`android:drawableTint` replaced with `app:drawableEndCompat`/`app:drawableTint` in welcome layout for backward compatibility.
- **Welcome screen buttons**: removed `buttonBarButtonStyle` attribute (was causing plain-text appearance); Material Button styling now applied directly.

### Fixed

- **SSH request ignored when a session was already open**: opening a saved server was delivered to the existing terminal activity, which re-attached whichever session was in front — so tapping Connect appeared to do nothing, or dropped you into a distro session. An explicit SSH request is now honoured even when other sessions exist.
- **Bundled `ssh` failing with `error=13, Permission denied`**: the binaries were being executed straight from app storage, which Android 12+ mounts `noexec`, so `execve` was refused however the file was chmod'ed and every session exited at once. They are now run through proot's `-L` loader inside their own minimal rootfs, and the client is verified before a session is created.

- **Bundled `ssh` failing with `error=13, Permission denied`**: the binaries were being executed straight from app storage, which Android 12+ mounts `noexec`, so `execve` was refused however the file was chmod'ed and every session exited at once. They are now run through proot's `-L` loader inside their own minimal rootfs, and the client is verified before a session is created.

- **Deprecated APIs used without acknowledgement**: the clipboard fallbacks used the deprecated `setText`/`text` even though `setPrimaryClip`/`primaryClip` work on every supported API, and `dispatchKeyEvent` handled the deprecated `KeyEvent.ACTION_MULTIPLE` path. Both are gone; key events are now delegated whole to the focused terminal view, which is what `TerminalView` expects and which also fixes input in split view.
- **Lint now gates the build**: `abortOnError` was `false`, so a lint error would not have failed the build. The build is lint-clean, so it is now enabled.
- **Distros never reaching bash, and no first-boot setup**: `writeShellConfigs()` was dropped from the session start path, so `/root/.bashrc` and `/root/.startup` were never written. `ENV` pointed at a file that did not exist, which is why every distro started in Android's shell with no setup output, no `bash`, and no way to install it.
- **Stale DNS breaking every package manager**: the rootfs `resolv.conf` was only written at install and repair time, but Android hands out DNS servers over DHCP, so moving between Wi-Fi and mobile data left the distro resolving through an old address — surfacing as `temporary error` from apk and `Unable to locate package` from apt. The resolver configuration is now refreshed from the live network on every launch and before every background command.
- **`sudo` crashing with a segmentation fault**: the sudo shim was only installed when `/usr/bin/sudo` was missing, so distros shipping the real binary (Kali) used it, and it relies on setuid behaviour proot cannot emulate. The shim is now always written, since proot already runs as root.
- **`Could not get lock` after a crashed package manager**: a killed apt or dpkg leaves its lock files behind and every later apt call then fails. Startup now clears `lists/lock`, `dpkg/lock`, `lock-frontend` and `archives/lock`, but only after confirming no apt or dpkg process is actually running.
- **Extra keys row alignment**: the bottom row had seven keys against the top row's eight. The pipe `|` was added next to `&&`, giving both rows eight keys.
- **Tool screens missing a back button**: Package Updates, Storage Usage, SSH, Recordings, Base Image Update and Manage Backups built their layout in code and so had no toolbar at all, leaving only the system back gesture. They now share a toolbar with the app's standard back icon.
- **Backups appearing to vanish after reinstall**: the "all files access" grant is reset on every install, and because it was only requested from the terminal, the backup list silently came back empty. Backup, restore and backup management now check the grant, explain why it is needed and offer to grant it.
- **Image previews showing nothing**: images were decoded as `RGB_565`, which drops the alpha channel and turns transparent PNGs and WebP solid black. Decoding now keeps full colour, and a placeholder plus an explicit reason is shown while decoding or on failure.
- **File rows not responding to taps**: the actions button in each row made the ListView's own item-click dispatch unreliable, so tapping a folder or file did nothing. Rows now handle their own taps.
- **Settings distro card text collapsing**: the distro name column is weighted, so adding a wide action squeezed it to zero width and wrapped the name one character per line. It now has a minimum width and stays on one line.
- **Base image check stuck on "Checking…"**: a failed check left the row in its loading state. Every outcome now settles the row, and a failure can be retried by tapping it.
- **Updates never reporting completion**: the finishing notification was posted from a `runOnUiThread` that returned early when the screen had been closed, leaving the "started" notification up forever. The final notification no longer depends on the screen still being open.

- **Crash above ~2 GB distros**: four full `rootfs` directory walks (distro-size display on the terminal, main and settings screens, plus the widget) ran on the main thread during layout, which ANR'd on large trees. Size is now cached and scanned in the background, and rootfs repair runs off the UI thread.
- **Foreground-service notification never appeared**: the permission dialog is asynchronous, so the `POST_NOTIFICATIONS` check always failed on first open and the service was never started. See "Changed" for the new flow.
- **ESC and TAB extra keys**: both bypassed the terminal view and wrote to `sessions[currentIndex]`, which could be null or stale. They now go through the focused `TerminalView` (the same path as the working arrow keys), honour the CTRL/ALT toggles, and fall back to a direct code-point write. The extra-key touch handler calls `performClick()` exactly once on release, fixing the earlier double-fire that broke CTRL, ALT and `&&`.
- **Clipboard paste hangs in TUIs**: paste wrote raw text on the main thread; `ByteQueue.write()` blocks once its 4096-byte buffer fills, and without bracketed-paste markers interactive programs (opencode and similar) reprocessed the content line by line. Pastes now use `TerminalEmulator.paste()` on a background executor, so `ESC [ 200 ~ … ESC [ 201 ~` wrapping is applied when the program enables mode 2004.
- **Theme toggle from the terminal**: selecting a theme broadcast a change that called `recreate()`, tearing down the live proot session mid-interaction and intermittently crashing. It now recolours in place, and the menu's "Red Terminal" entry no longer applies the AMOLED theme.
- **Arch package installs failing with `GLIBC_2.43 not found`**: caused by a partial upgrade; first-time setup now performs a full system upgrade.
- **openSUSE `Can't open solv-file`**: the x86-only `repo-openh264` repository was skipped and left a corrupt cache; it is now disabled and the cache is rebuilt.
- **Distro install cancel and resume**: cancelling now deletes the partial rootfs and partial download, while a network or other failure keeps the partial download so Retry resumes; previously every failure discarded the whole download and a cancelled extraction could leave a half-extracted rootfs.
- **Lint: HardcodedText (73)**: all hardcoded strings in XML layouts extracted to string resources.
- **Lint: SetTextI18n (54)**: all programmatic `setText()` calls use `getString()`/`getQuantityString()` with format strings.
- **Lint: RtlSymmetry (2)**: symmetric horizontal padding added in settings layout.
- **Lint: PrivateResource**: the `copy` string renamed to `action_copy` to avoid the clash with `androidx.preference`.
- **Lint: PluralsCandidate**: byte-size strings reworded to avoid quantity-dependent formats; `match_count_for_query` converted to `<plurals>`.
- **Lint: TypographyDashes (2)**: en dashes (`–`) used instead of hyphens (`-`) in PIN strings.
- **Lint: ButtonStyle (3)**: welcome screen buttons changed to Material Button for proper button appearance.
- **Lint: ClickableViewAccessibility (2)**: `v.performClick()` called from the `ACTION_UP` handler and the event consumed so the action fires exactly once.
- **Lint: Overdraw (8)**: redundant root `android:background` removed and each theme now paints its own `android:windowBackground`.
- **Lint: TooManyViews**: `activity_settings.xml` split into included section layouts.
- **Lint: DataExtractionRules**: added a legacy `android:fullBackupContent` resource for API 24–30.
- **Lint: SetWorldReadable**: file modes applied with `Os.chmod` instead of `File.setReadable`.
- **Lint: UnusedResources / AndroidGradlePluginVersion / GradleDependency**: unused strings removed, Gradle and `core-ktx` updated.
- **Lint: UseCompatTextViewDrawableXml (2)**: `android:drawableEnd`/`android:drawableTint` replaced with `app:drawableEndCompat`/`app:drawableTint`.
- **Lint: UseKtx (all)**: `prefs.edit {}` KTX, `.toUri()`, `.isVisible`, and `apply` blocks cleaned up across all files.
- **Lint: SwitchCompat (all)**: `SwitchCompat` used consistently in XML layouts and Kotlin code.
- **Lint: ScrollViewSize**: terminal layout uses `match_parent` height correctly.
- **Lint: SmallSp**: terminal text sizes use `sp` units properly.
- **Lint: ObsoleteSdkInt**: removed unnecessary `Build.VERSION.SDK_INT >= N` guard (minSdk is 24).
- **Lint: WakelockTimeout**: wake lock now uses `acquire(long)` with explicit timeout.
- **Lint: DefaultUncaughtExceptionDelegation**: crash handler properly delegates to the default handler.
- **Lint: PrivateApi (DnsHelper)**: removed reflection-based private API access.
- **Lint: SdCardPath**: replaced hardcoded `/sdcard` with `Environment.getExternalStorageDirectory()`.
- **Lint: RedundantNamespace**: removed redundant `tools` namespace in `ic_back_chip.xml`.
- **Lint: MonochromeLauncherIcon**: added monochrome layer in launcher icon adaptive foreground.
- **Unused resources deleted**: `rounded_bg.xml`, `spinner_bg.xml`, `spinner_dropdown_item.xml` removed.

## [v1.0.4]

### Added

- **RedTerm bash template**: new power-user `.bashrc` template with RedTerm branding, colored prompt, extended aliases, functions and startup banner.

### Changed

- **Toolchain**: Gradle 8.14.5 → 9.7.0, Android Gradle Plugin 8.13.2 → 9.2.1, `compileSdk` 36 → 37; removed explicit `org.jetbrains.kotlin.android` plugin (bundled in AGP 9.x).
- Settings page shows version v1.0.4.

### Removed

- **Exit-status indicator**: the red "✗ Last command failed" strip has been removed; the failure bell remains as the sole command-failure feedback.

### Fixed

- Replaced deprecated `startActivityForResult` with the Activity Result API in Settings.
- Replaced deprecated `ProgressDialog` with `ProgressBar` inside `AlertDialog`.
- Replaced deprecated `Intent.EXTRA_SHORTCUT_*` constants with string literals for home-screen shortcuts.
- Replaced deprecated `InputMethodManager.SHOW_IMPLICIT` with `0`.
- Replaced deprecated `Thread.id` with `Thread.threadId()` in the crash handler.
- Replaced deprecated `VIBRATOR_SERVICE` with service-name strings; uses `VibratorManager` on API 31+ and reflection for `vibrate(Long)` on older APIs.
- Cleaned up deprecated `KeyEvent.ACTION_MULTIPLE` guard in terminal key dispatch.

## [v1.0.3]

### Changed

- **Extra keys**: swapped the positions of the left and right arrow triangles in the default second key row (`◀ ▼ ▶` instead of `▶ ▼ ◀`); Reset in Settings uses the new order too.
- Settings page shows version v1.0.3.

### Fixed

- **Shared storage in distros**: the terminal's proot session now binds `/sdcard`, `/storage` and `/mnt` into the distro, so `/storage/emulated/0` files are visible and usable inside every distro.
- **All files access**: the All files access request moved back to the terminal screen (where it was in earlier builds) instead of the welcome screen, where it could be skipped — the terminal re-asks until it is granted and shows a toast explaining why it is needed.

## [v1.0.2]

### Added

- **Setup permissions**: storage and notification permissions are now requested on the welcome screen during setup instead of when the terminal opens.
- **Custom bash templates**: create your own named `.bashrc` templates in Settings → Bashrc templates, then edit or delete them; deleting a template resets any distro using it back to its original `.bashrc`.
- **Custom fonts**: import your own `.ttf`/`.otf` fonts in Settings — one at a time or several at once from the storage picker — then select them from the font dropdown or the terminal's Fonts menu; custom fonts can be renamed and removed, and names default to the file name without the extension.
- **Modern back button**: the back control is now a round, theme-aware chip; it also appears on the main page and in Settings, with the same style applied in the terminal, file browser and bash templates screens.

### Changed

- **Theme sync**: changing the theme in Settings or from the terminal's Theme menu now broadcasts the change, so the main page and every other screen repaint immediately with the selected theme.
- The terminal's Fonts menu lists imported custom fonts and refreshes every time the menu opens.
- Settings page shows version v1.0.2.
- Settings label renamed to **Bashrc templates**.
- **Toolchain**: Gradle 8.13 → 8.14.5, Kotlin 2.3.0 → 2.4.10, `compileSdk` 35 → 36; dependency updates across androidx (lifecycle 2.8.7 → 2.11.0, activity 1.9.3 → 1.13.0, material 1.12.0 → 1.14.0, constraintlayout 2.2.0 → 2.2.2, appcompat, preference, viewpager2), commons-compress 1.27.1 → 1.28.0 and xz 1.10 → 1.12; `core-ktx` pinned at 1.18.0 (1.19.0 requires AGP 9.1 and compileSdk 37).
- GitHub Actions updated: `setup-android` v3 → v4, `setup-java` v4 → v5.6.0, `gradle/actions/wrapper-validation` v3 → v6.

### Fixed

- **Android 7.0/7.1 support**: the app no longer crashes on API 24–25 at startup (`NotificationChannel` is now only created on Android 8+), and distro extraction/repair no longer calls `java.nio.file` (API 26) or `Process#destroyForcibly` (API 26) unguarded — symlinks are created with `Os.symlink` (API 21+), so first-time setup and busybox repair work on Android 7+.
- CI: official Gradle wrapper jar (checksum validation was failing), compileSdk 36 for the new androidx versions, lint set to non-fatal, docs updated with the current build requirements.

## [v1.0.1]

### Added

- **Bash templates**: pick from eight built-in `.bashrc` templates (Stock, Powerline, Minimal, DevOps, Hacker, Starship, Matrix, Retro CRT) in Settings → Bash templates; applied per-distro, auto-applies directly when only one distro is installed, one template can be applied to multiple distros at once, and each distro can be reset to its original `.bashrc`. The original `.bashrc` is backed up before the first overwrite.
- **Bell/command feedback**: terminal rings (haptic vibration) on BEL, and `PROMPT_COMMAND` rings the bell when a command exits with a non-zero status.
- **Export terminal output**: Copy, Paste and Export buttons in the sessions drawer; exported transcripts are saved to `/sdcard/RedTerm/exports/`.
- **Dynamic session title**: toolbar now shows `distro › /cwd`, updated live while a session runs.
- **Per-distro management**: MainActivity cards now show rootfs size and a long-press menu with Launch / Files / Backup now / Remove.
- **Custom extra keys**: two editable key rows in Settings (space-separated tokens: ESC, TAB, CTRL, ALT, HOME, END, UP/DOWN/LEFT/RIGHT, INS, DEL, BACKSPACE, MENU, `&&` — anything else is typed as text).
- **Open terminal here**: FileBrowser menu action launches a terminal session rooted at the current directory.
- **App lock**: optional 4-8 digit PIN required when opening the app.
- **Settings backup**: Import Config button added next to Export Config (`RedTerm_config.json`).
- **Keep screen on**: the wake lock toggle now also keeps the screen lit while the terminal is open.
- **Widget session count**: widget shows the number of active sessions and a contextual tap hint; a widget config screen lets you pick which distro the widget launches.
- **Split view**: Split button in the quick panel shows two terminal panes side-by-side; extra keys route to the focused pane; split mode collapses automatically when a session exits.
- **FileBrowser search**: search files by name in the current directory with the option to descend into found folders.
- **Terminal find**: highlight all matches in the scrollback, jump and cycle between them.
- **Bundled fonts**: real font files (JetBrains Mono, Fira Code, Source Code Pro, Ubuntu Mono, Droid Sans Mono, Noto Sans Mono, Cascadia Code) shipped in assets and loaded from there.
- **Files browser**: browse the distro rootfs.
- **Home-screen widget**: quick launch a distro from the launcher.
- **Night mode**: automatic switching to AMOLED between 6 PM and 6 AM (toggle in Settings).
- **Session persistence**: sessions survive activity restarts and are resumed on reopen.
- **Distro backup/restore**: improved multi-select backup with progress, restore validation, and backups under `/sdcard/RedTerm/`.
- **CPU indicator**: live CPU usage shown in the service notification.
- **Keyboard shortcuts**: F1-F12 keys emit the proper escape sequences.
- **Output coalescing**: terminal redraws batched via Choreographer for smoother rendering.
- **Tap links and paths**: tapping a URL or a file path in the terminal pops up actions to open it in the browser / file browser or copy it.
- **Quick Settings tile**: a tile that launches the last-used distro straight from the quick settings shade.
- **Home-screen shortcut**: distro menu gains "Home shortcut" to pin a launcher shortcut for that distro.
- **Dynamic (Material You) theme**: new "Dynamic" theme option (Android 12+) that follows the system wallpaper palette, applied to both the app and the terminal colors.

### Changed

- Session persistence is now process-wide; sessions survive activity restarts and are resumed on reopen.
- Session creation, switch and finish flows share a single handler (also used by split view).
- Extra keys are rendered from preferences instead of a hardcoded list.
- Widget configurable per instance via a configuration activity.
- Improved session drawer controls.
- Fonts loaded from app assets instead of the system font directory.
- Base tarballs kept in app files after install so distro reset runs offline; removed on uninstall.
- Shell configs (`.bashrc`, `.bash_profile`, `.startup`) only written when missing, so user customizations are never overwritten.
- Terminal bell toggle in Settings (vibration on BEL / failed commands).
- "Quick settings" and "Split view" entries in the terminal's three-dots menu, so both are discoverable without tapping the top edge of the screen.
- Dynamic theme added to the Settings theme picker and the terminal's Theme menu.
- Split view: each pane now has its own terminal client (live output in both), tapping a pane selects that session (title, drawer highlight, extra keys, CTRL/ALT target it), and the keyboard is no longer force-restarted when switching panes (no more freeze).

### Fixed

- Rootfs `.startup` script now only marks first-time setup complete when package installation succeeds, falling back to a repair shell instead of failing silently.
- CPU indicator integer-division bug that always showed 0%.
- Terminal now renders custom fonts through a shared helper so split panes use the same font.
- Layout-params type mismatch in FileBrowser search bar (LinearLayout params on a LinearLayout child).
- Several Kotlin type-inference issues around key actions.
- Main navigation mix-up where the home button launched Settings instead of Main.
- Terminal activity buttons not respecting theme changes.
- Bash prompt errors (`=0: command not found`, `[: -ne: unary operator expected`) from a fragile `PROMPT_COMMAND`; replaced with a simple `[ $? -eq 0 ] || printf "\a"`.
- Distro reset (long-press card) now restores a fresh state offline: the rootfs is re-extracted from the cached base tarball, wiping installed packages, caches and shell configs; the next launch runs first-time setup again.
- Distro removal now fully removes the distro: rootfs, cached tarball and registry entry are deleted, and any running sessions for that distro are killed, so a later reinstall starts completely fresh.
